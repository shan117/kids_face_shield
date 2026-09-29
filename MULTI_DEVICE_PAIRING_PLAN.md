# Multi-Device Pairing Plan — one parent phone, many child phones

Status: **in implementation**. Artifact version: https://claude.ai/code/artifact/698db787-344c-4116-8579-de4c3d73ffbb

---

## 0. The problem

Today the pairing identity is single-valued end to end:

- `DataStoreManager` stores one `remote_pairing_id` + one `remote_pairing_key` (plain `stringPreferencesKey`, not a set).
- `ParentSetupViewModel.onParentScanned()` calls `setRemotePairing()` **unconditionally**.
- Firestore doc paths are `reports/{pairingId}` and `commands/{pairingId}` — the parent listens to exactly one.

**Live defect:** a parent who has paired with child A and then scans child B's QR silently loses A.
A's phone keeps uploading to `reports/{A}` forever (the doc is never deleted, because `reset()` was never
called), and the parent permanently loses visibility and remote control of A. Enforcement on A continues
normally, so nothing appears broken.

Note: this is unrelated to the **Multiple Kids** feature, which is two kids sharing *one* phone, told
apart by face. That is carried inside a single report payload as `RemoteReportPayload.kids`.

| Scenario | Supported today |
|---|---|
| 2 kids, 1 shared phone, told apart by face | Yes |
| 2 kids, 2 separate phones, 1 parent phone | **No** |

---

## 1. What was verified before designing

| Finding | Evidence | Consequence |
|---|---|---|
| FCM is not in the command path | `ShieldMessagingService` only shows Console announcements; commands arrive via a Firestore listener at `AppLockForegroundService:303` | No messaging/token/topic work |
| Rules are bearer-token per document | `firestore.rules`: `allow get, create, update: if true` on `reports/{pairingId}`, `list: if false` | N pairings are just N doc ids. **No rules change, no deploy** |
| Remote controls are device-scoped, not kid-scoped | `ParentReportScreen` uses `payload.kids.firstOrNull()`; every command writes device-level DataStore keys | A device switcher does **not** need to compose with a per-kid control switcher |
| The child listener is role-gated | `AppLockForegroundService:302` attaches only when `role == "child"` | The child path cannot be reached by parent-side changes |

---

## 2. Core decision

> **A child device needs exactly one pairing — the one it minted. Only the parent needs many.**

All multiplicity lives on the parent side. The child keeps the existing single-valued keys untouched.
This is what makes a zero-regression change realistic.

### Blast radius

| Component | Role | Change |
|---|---|---|
| `AppLockForegroundService` command listener | Child | **None** — keeps reading `remotePairingId` |
| `RemoteCommandApplier` | Child | **None** — already 1:1 by nature |
| `RemoteReportSync` / `Worker` / `Gatherer` | Child | **None** — gated on `role == "child"` |
| `PairingManager`, QR codec, `ReportCrypto` | Both | **None** — wire format unchanged |
| `firestore.rules` | — | **None** |
| `DataStoreManager` | Parent | Additive — new keys + mutators |
| `RemoteCommandSender` | Parent | Additive — explicit target parameter |
| `ParentReportViewModel` | Parent | Rework — N listeners, per-device state |
| `ParentReportScreen` / `ParentSetupScreen` | Parent | Rework — switcher, add, per-device unpair |

**Rule for the whole change:** if a diff touches a file that runs in the `child` role, stop and
re-derive. The only legitimate exception is `DataStoreManager`, where new keys are purely additive
and existing keys keep their current meaning.

---

## 3. Data model

```kotlin
// remote/PairedDevice.kt
data class PairedDevice(
    val pairingId: String,   // hex; Firestore doc key AND bearer token
    val keyHex: String,      // AES-256 E2E key, never leaves the device
    val label: String,       // parent-chosen, e.g. "Aarav's phone"
    val addedAtMs: Long,
)
```

All list arithmetic lives in a **pure object**, testable without Android/DataStore/Firestore:

```kotlin
// remote/PairedDevices.kt — pure, no Android imports
object PairedDevices {
    fun add(list, device): List<PairedDevice>      // replaces in place on duplicate pairingId
    fun remove(list, pairingId): List<PairedDevice>
    fun rename(list, pairingId, label): List<PairedDevice>
    fun resolveActive(list, storedId): PairedDevice?  // stored → else first → else null
    fun migrate(legacyId, legacyKey, existing): List<PairedDevice>  // idempotent
}
```

### New DataStore keys

| Key | Type | Meaning |
|---|---|---|
| `remote_paired_devices` | String | Encoded `List<PairedDevice>`. Parent role only. |
| `remote_active_pairing` | String | Which device the parent is viewing. May go stale; always read through `resolveActive`. |
| `remote_pairing_id` *(existing)* | String | Unchanged for child. On parent, mirrors element 0 until Phase 5. |

---

## 4. Migration

The one place a mistake loses real user data.

**1. Atomic, single-edit.** DataStore `edit {}` is transactional. The migration writes the new list in
the *same* block as anything else it touches, so the list and the legacy keys can never disagree.
Never split across two `edit` calls.

**2. Legacy mirror, kept for two releases.** Every mutation writes element 0 back into
`remote_pairing_id` / `remote_pairing_key`. This buys:

- **Phase independence** — Phases 1 and 2 ship before any UI exists, because old read sites keep
  working off the mirror.
- **Sideload-downgrade safety** — an older APK still finds a usable pairing for the first device.

The mirror has exactly one writer (the DataStore mutators), so it cannot drift. Removed in Phase 5.

**3. Idempotency.** The app can die between a read and a write. `migrate()` is a no-op when the list
is already non-empty, so re-running at every startup is harmless. Pinned by a test that calls it twice.

---

## 5. Phases

Each phase compiles, passes tests, and is independently shippable.

### Phase 0 — Stop the silent overwrite (~1h, ships alone)

`onParentScanned` returns a result instead of a boolean:

```kotlin
sealed interface ScanResult {
    data object Invalid : ScanResult
    data object Added : ScanResult
    data class ReplacesExisting(val currentLabel: String) : ScanResult
}
```

On `ReplacesExisting` the UI confirms before committing.

### Phase 1 — Data model + migration (~4h, no behaviour change)

Add `PairedDevice`, `PairedDeviceCodec`, `PairedDevices`. `DataStoreManager` gains `pairedDevices`
flow plus `addPairedDevice` / `removePairedDevice` / `renamePairedDevice`, each mirroring element 0.
**Nothing reads the list yet** — the app is observably identical.

Tests: codec round-trip incl. delimiter-bearing and emoji labels; `add` replaces rather than
duplicates; `remove` of absent id is a no-op; `migrate` twice equals once; mirror equals element 0.

### Phase 2 — Explicit targeting (~3h, no behaviour change with one device)

```kotlin
suspend fun send(
    type: CommandType, arg: Int = 0, payload: String = "",
    target: PairedDevice,          // caller supplies; no hidden DataStore read
): Result
```

Keep the `role != "parent"` guard. `ParentReportViewModel` resolves the active device via
`resolveActive` and passes it.

### Phase 3 — Listener lifecycle (~4h, highest risk)

```kotlin
class ListenerBag {
    fun sync(ids: Set<String>, attach: (String) -> ListenerRegistration)
    fun clear()
}
```

The ViewModel collects the device list **once** and calls `sync()`. Per-device state becomes
`Map<String, State>`, each entry initialised to `Loading` so an attaching device never flashes `Empty`.

### Phase 4 — Parent UI (~8h, most surface area)

- Device switcher, **hidden entirely when only one device is paired** so the single-child experience
  is visually untouched.
- "Add another child device" entering the existing scan flow.
- Per-device unpair and rename.

Unpairing one device must **not** call `clearRemotePairing()` — that wipes role, share and control
flags globally. Dedicated path: delete `reports/{id}` + `commands/{id}`, remove from list, reassign
active, drop role to `"none"` only when the list becomes empty.

### Phase 5 — Remove the mirror (~1h, two releases after Phase 1)

Stop writing legacy keys on the parent side. The child keeps using them as today.

---

## 6. Invariants

| # | Invariant | Enforced by |
|---|---|---|
| 1 | Child role never reads `remote_paired_devices` | Review rule; no `PairedDevices` import in child-path files |
| 2 | `pairingId` is unique within the list | `PairedDevices.add` replaces in place |
| 3 | Active device is a member of the list, or null | `resolveActive` is the only reader of the stored id |
| 4 | Removing the active device reassigns to first remaining, else null | `resolveActive` fallback chain |
| 5 | Every live listener has a matching list entry | `ListenerBag.sync` is the only attach site |
| 6 | Full reset clears the list and the active id | `clearRemotePairing()` extended |
| 7 | Unpairing one device deletes only that device's cloud docs | Dedicated `unpairDevice(id)`, never `reset()` |
| 8 | Role stays `"parent"` while ≥1 device remains | `unpairDevice` drops role only at zero |
| 9 | Legacy mirror equals element 0, until Phase 5 | Single writer: the DataStore mutators |

---

## 7. Bug traps

| # | Trap | Fix |
|---|---|---|
| A | **Global wipe on single unpair.** `clearRemotePairing()` removes role, share-enabled, control-enabled, last-applied-command-id. Using it to remove one of three devices disables Remote Report for the others. | Separate `unpairDevice(id)`. Reserve `clearRemotePairing()` for "remove everything". |
| B | **Stale confirmation ticks across devices.** `pendingLimit` / `pendingGrant` / `grantTarget` are `remember`ed in `ParentReportScreen`. Switching A→B keeps them, so B shows a green "applied" tick for a limit set on A. | `remember(activePairingId) { ... }`. Subtlest bug in the change. |
| C | **Misattributed command status.** `commandStatus` is one `StateFlow`; a failed send to B surfaces while A is on screen. | Key status by `pairingId`, or clear on device switch. |
| D | **Listener churn from unstable keys.** `LaunchedEffect(deviceList)` re-fires on identity change even when contents are equal. | Key on the id set, never the object list. |
| E | **Empty-state flash.** Firestore `listen()` fires once on attach; devices not yet attached render as "no report yet". | Initialise every entry to `Loading`; only a delivered null becomes `Empty`. |
| F | ~~**Key rotation orphans an entry.** Child `rotateKey()` mints a new id; the parent's entry decrypts nothing and sits in `Error`.~~ **This analysis was wrong — see F′.** | — |
| F′ | **Key rotation silently freezes the parent's view.** Rotation does not invalidate anything: the child mints a new id and starts uploading to `reports/{newId}`, leaving `reports/{oldId}` intact and *still perfectly decryptable* with the key the parent holds. The parent goes on showing a real, valid, permanently-frozen report — no error, no clue. Found in device testing (step 10). | Child `revoke()`s the old report document on rotation, replacing the ciphertext with a `revoked: true` marker, and deletes the stale command doc. Parent surfaces `State.Revoked` with a re-scan button naming the child. |
| G | **Re-scanning the same child duplicates it.** A rotated id is genuinely new, so dedupe-by-id does not catch it. | Offer "replace existing device" in the add flow, matched by label. |
| H | **Migration racing startup.** Two entry points, or death mid-write. | Idempotent by construction + single call site + one atomic `edit {}`. |
| I | **Unbounded device list.** Nothing caps the number of open Firestore listeners. | Cap at 5 with a clear message. |
| J | **Codec injection via label.** Labels are free text; the codec is delimiter-based. | Strip delimiters on encode, as `RemoteReportCodec.clean()` already does. Test with a hostile label. |

---

## 8. Test plan

### Automated — pure JVM, no instrumentation

| Unit | Tests | Covers |
|---|---|---|
| `PairedDeviceCodec` | ~6 | Round-trip, empty list, hostile labels, forward compatibility |
| `PairedDevices` | ~10 | Invariants 2, 3, 4; migration idempotency |
| `ListenerBag` | ~5 | Invariant 5; no double-register; clear-all |
| `RemoteCommandSender` | ~3 | Targets the supplied device; role guard still refuses non-parent |

### Manual — on device

1. **Upgrade with an existing pairing.** Pair before the update, install the new build, confirm the
   child still appears and reports still arrive. *The migration's only real test.*
2. Add a second device; both report independently; switcher appears.
3. Send a command to B while viewing B; A unaffected. *(trap C)*
4. Set a limit on A, switch to B; no stale confirmation tick. *(trap B)*
5. Unpair B; A keeps working and Remote Report stays enabled. *(trap A)*
6. Unpair the last device; role drops to `none`, UI returns to unpaired state.
7. Airplane mode during a switch; no crash, sane offline state.
8. Rotate the key on child A; A alone shows an actionable error naming that child. *(trap F)*

---

## 9. Implementation log

Build: `assembleDebug` SUCCESSFUL. Tests: **178 passing, 0 failures** (was 144 before this work).

| Phase | Status | Notes |
|---|---|---|
| 0 | Done (folded into 4) | `ScanResult` shipped, but as `Added`/`Updated`/`AtCapacity` rather than `ReplacesExisting` — once scanning *adds*, "replace" is no longer the outcome. Implementing it standalone first would have been thrown away. |
| 1 | Done | `PairedDevice`, `PairedDevices`, `PairedDeviceCodec`; DataStore keys, mutators, atomic mirror, migration. 21 tests. |
| 2 | Done | `RemoteCommandSender.send(..., target:)`. Isolation covered by crypto tests instead of sender fakes — see below. |
| 3 | Done | `ListenerBag` + per-device state map. 8 tests. |
| 4 | Done | Device switcher (hidden at 1 device), link-another, per-device remove, trap-B keying. |
| 4b | Done | Rename UI, trap F (re-scan from the Error state), trap G (replace-on-rotate), scan one-shot latch. |
| 4c | Done | Extracted `PairedDevices.keyStateFor` so the legacy-mirror rule (invariant 9) is unit-testable. 10 tests. |
| 4d | Done | **Fixed trap F′ — found in device testing.** Revocation marker on rotate; `ReportDoc` sealed type; `State.Revoked`. |

### Phase 4d — rotation revocation (bug found on device)

Device testing step 10 showed the parent continuing to display the child's **old report** after a key
rotation, with no error at all. The original trap-F analysis assumed rotation made the document
undecryptable. It does not:

1. `rotateKey()` mints a new id + key and points the child at `reports/{newId}`.
2. `reports/{oldId}` is never touched. Its ciphertext is intact and still opens with the key the
   parent already stored.
3. The parent therefore reads a genuine, valid report — frozen at the moment of rotation, forever.

`State.Error` could never fire, because nothing ever failed. The fix makes the death of a pairing
**explicit** rather than inferred:

- `RemoteReportRepository.revoke(id)` overwrites the document with `{revoked: true}`, dropping the
  ciphertext. Overwrite, not delete — an absent document is indistinguishable from a pairing that has
  simply never uploaded, and the parent must tell those apart.
- `read`/`listen` now return a `ReportDoc` (`Missing` / `Revoked` / `Data`) instead of a nullable
  `Sealed`, so "no document" and "retired document" are different values in the type system.
- `rotateKey()` revokes the old report and deletes the stale command document (the child no longer
  listens there, so anything the parent sent would vanish silently).
- The parent renders `State.Revoked`: *"Aarav's phone needs reconnecting"* plus a re-scan button.

Firestore rules already permit this — `update` on `reports/{pairingId}` is allowed to any holder of the
id, and the marker carries no secrets.

**Known limitation:** if the child is offline at the moment it rotates, `revoke()` fails silently and
that rotation still leaves a stale document. Rotation is a deliberate, rare action taken in-app, so
this is narrow — but it is not airtight. A retry-on-next-sync would close it.
| 5 | **Blocked — do not start** | Two releases after Phase 1. Phase 1 has not shipped once yet. |

### Why Phase 5 must not be implemented yet

Phase 5 removes the legacy mirror. The mirror is the safety net for the migration — and the migration
has not yet run on a single real device. Removing it now would:

- delete the downgrade escape hatch before it has ever been needed;
- remove the property that lets the old read sites keep working, while those sites
  (`RemoteCommandApplier`, `RemoteReportSync`, `AppLockForegroundService:296`) still read the legacy
  keys on the child;
- take away the fallback if the migration turns out to be wrong in the field — precisely when it
  would be needed most.

The trigger is **two shipped releases**, not "the code is ready". Until then Phase 5 is not the next
phase; it is a regression wearing a phase number.

### Phase 4c — making the load-bearing rule testable

The legacy mirror is what carries an existing paired parent across the upgrade, yet its logic sat
inside a `MutablePreferences` extension where no unit test could reach it (the project has JUnit only —
no Robolectric, no coroutines-test, and adding them is not possible offline).

`PairedDevices.keyStateFor(devices, storedActiveId): PairingKeyState` now returns the key-by-key
outcome as data; the DataStore mutator only applies it. Behaviour is identical — same three decisions,
same order — but invariants 3, 4 and 9 are now pinned by tests, including the full upgrade path
(migrate → key state → link a second child, asserting the first child stays element 0).

One rule the extraction made explicit: a null field means **remove the key**, never write an empty
string. A blank pairing id would read as a real-but-broken pairing to the legacy call sites, where an
absent key correctly reads as unpaired.

### Phase 4b — closing the remaining gaps

- **Rename.** `renameDevice` existed in the ViewModel with no caller. Now reachable from the report
  footer via a dialog ("Name this device" at one child, "Rename X" at several). Auto labels are
  `Child device 1/2/…`, which tell a parent nothing about which phone they are about to lock.
- **Trap F — re-scan from the Error state.** The error now names the child
  ("Couldn't read Aarav's phone") and offers `Re-scan Aarav's code`.
- **Trap G — replace on rotate.** `onParentScanned(..., replacing =)` retires the old entry, deletes
  its now-unreadable cloud documents, and lets the replacement inherit the old label. Previously a
  rotated key produced a second dead row for the same phone, because dedupe-by-id cannot match a
  genuinely new id.

### Deviations from the plan

1. **Phase 0 folded into Phase 4.** Its guard changes meaning once multi-device exists. The end state
   is better (adds rather than replaces); only the standalone-ship benefit was lost.
2. **`RemoteCommandSender` tests replaced by `MultiDeviceIsolationTest`.** The sender needs
   `DataStoreManager` (Android `Context`) and Firestore, so a unit test would have been mostly fakes
   asserting on fakes. Instead the *actual* risk — a command reaching the wrong child — is pinned at
   the crypto layer: a command sealed for A returns null under B's key. Decryption is authorization,
   so this is the guarantee that matters.

### Regressions found and fixed during implementation

| Found | Fix |
|---|---|
| **Self-inflicted:** deriving `state` from `stateIn`-seeded flows made a paired parent flash "Not paired" on every open, because an unresolved `emptyList()` seed is indistinguishable from genuinely unpaired. | Derive from the raw cold flows so `Loading` holds until the real value lands. |
| **Latent, from the mirror:** on a parent phone the legacy keys mirror the first child's secrets, so `childQr` would compute a QR carrying *another device's* pairing key. Not rendered today (parent role shows a different pane), but one composable move from exposing it. | `childQr` is null in the `parent` role. |
| **Latent, from the mirror:** `becomeChild()` reused the legacy keys when non-blank, which on a converted parent phone would mean impersonating its first linked child. Unreachable today. | Mint fresh whenever linked devices exist. |
| **Pre-existing:** `reset()` deleted only the legacy pairing's cloud docs — a parent with three children would leave two ciphertext documents orphaned in Firestore. | Delete the union of the device list and the legacy id. |
| **Caught before shipping (Phase 4b):** `QrScanAnalyzer` has no one-shot guard — it calls back on *every* decodable frame (~30/sec, on a background executor). Harmless while a scan only rewrote two keys with identical values; destructive once linking deletes the device being replaced, since the second pass would run after the first had removed the old entry, lose the inherited label, and race the capacity check. | `AtomicBoolean` latch in the ViewModel: first decodable frame wins, rest dropped until `beginScan()` re-arms when the camera opens. |

### Verification performed

- `git diff` against `service/`, `RemoteReportSync`, `RemoteReportWorker`, `RemoteCommandApplier`,
  `RemoteReportGatherer`, `firestore.rules` → **empty**. The child path and the relay rules are
  untouched, as the core decision requires.
- Invariant 1 checked mechanically: no child-path file references `PairedDevice`.
- Remaining readers of the legacy keys audited — all four are child-role-gated
  (`RemoteCommandApplier`, `RemoteReportSync`, `AppLockForegroundService:296`, `becomeChild`).

**Not device-tested.** The manual matrix in §8 still has to be run — item 1 (upgrade with an existing
pairing) is the migration's only real test and cannot be covered by unit tests.
