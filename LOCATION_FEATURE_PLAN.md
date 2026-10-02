# Child Location Plan — "Where are they now?" + location history

Status: **plan only, nothing implemented.**

Two features, both config-tiered through the existing entitlement system:

| Feature | Default tier | What the parent gets |
|---|---|---|
| `LOCATION_NOW` | FREE | Request the child's current location on demand |
| `LOCATION_HISTORY` | PREMIUM | The log of every past request and its result |

---

## 1. What was verified first

Four findings that changed the design. Each removes work or a risk.

| Finding | Evidence | Consequence |
|---|---|---|
| **No Play Services location dependency** | `play-services-location` absent from `app/build.gradle.kts` and `libs.versions.toml` | Use the **platform `LocationManager`**. Adding a dependency is also impossible while building `--offline`. |
| **The foreground service already switches FGS types at runtime** | `updateForegroundService(useCamera)` at `AppLockForegroundService:1311` builds a dynamic bitmask with a safe `try/catch` fallback | Add `location` to that bitmask → **`ACCESS_BACKGROUND_LOCATION` is not needed**, which is the single biggest Play-review saving here |
| **Firestore rules deny enumeration** | `firestore.rules`: `allow list: if false` on every collection | History must live in **one document as a capped list**, not a subcollection. A subcollection would require `list`. |
| **Commands already support async "caller performs it" actions** | `RemoteCommandApplier.apply()` returns the command for `LOCK_NOW`/`UNLOCK` so the service acts | `REQUEST_LOCATION` follows the same shape. No new dispatch mechanism. |

Environment: `minSdk 24`, `compileSdk/targetSdk 35`.

---

## 2. Core design decision

> **Current location and history are the same list.** The latest entry is "now"; the whole list is
> "history". `LOCATION_HISTORY` gates *how much of that list the parent may see*, not whether it is
> recorded.

Why this is right:

- Every fix exists **because the parent asked for it**. There is no passive tracking, so the number of
  entries equals the number of requests. History is simply the request log.
- One document, one list → no subcollection, no `list` permission, no rules-model change beyond adding
  the collection.
- The child cannot know the parent's entitlement (it lives on the parent device, from Remote Config +
  Billing). Recording always and **gating display on the parent** is the only design that works without
  shipping entitlement state to the child.

### A privacy invariant is deliberately changing

`RemoteReportPayload` currently states:

> "It carries ONLY screen-time totals & patterns — NEVER content, raw events, messages, **location**,
> or face data. The invariant that makes this a privacy feature instead of spyware."

Location must **not** be added to that payload. It travels on its own channel, with its own child-side
consent toggle, so the aggregate-report invariant stays true as written. The comment gets a pointer to
this document noting location is a separate, separately-consented capability.

---

## 3. Data model

```kotlin
// remote/LocationFix.kt — new, pure
enum class FixStatus {
    OK,                 // a usable fix
    PERMISSION_DENIED,  // child revoked location permission
    LOCATION_DISABLED,  // device location services off
    TIMEOUT,            // no fix within the window (indoors, no signal)
    NOT_CONSENTED,      // child device has location sharing switched off
}

data class LocationFix(
    val requestedAtMs: Long,   // when the parent asked
    val fixedAtMs: Long,       // when the device got the fix (0 unless OK)
    val status: FixStatus,
    val lat: Double,           // 0.0 unless OK
    val lon: Double,
    val accuracyM: Float,      // horizontal accuracy — drives "±12 m" vs "±1.2 km"
    val batteryPct: Int,       // -1 unknown; costs nothing and answers "why no fix?"
)
```

**Every request produces exactly one entry, success or failure.** A failure must be recorded and
shown — otherwise the parent stares at a spinner forever, which is the silent-failure pattern this
codebase has been fixing all week.

### Transport

New collection, same bearer-token model as `reports/` and `commands/`:

```
locations/{pairingId}   →  { iv, ciphertext, updatedAt, schemaVersion }
```

Plaintext inside is a delimiter-encoded, capped list (newest first), following
`RemoteReportCodec` / `PairedDeviceCodec` convention — no JSON dependency, pure, unit-testable.

- **Cap: 50 entries.** ~60 bytes each ⇒ ~3 KB, far inside the 1 MB document limit.
- Oldest dropped on append. Capping in one pure function keeps it testable.

### Firestore rules — the one deploy this feature needs

```
match /locations/{pairingId} {
  allow get, create, update, delete: if true;
  allow list: if false;
}
```

Identical model to the existing two collections: the 128-bit `pairingId` is an unguessable bearer
token, and the payload is E2E encrypted so the relay cannot read a coordinate. **Unlike the
multi-device work, this phase does require publishing rules.**

---

## 4. Flow

```
PARENT                          RELAY                        CHILD
  │ tap "Locate now"              │                            │
  ├─ REQUEST_LOCATION ───────────►│ commands/{id}              │
  │  state: Requesting            │ ──── listener fires ──────►│
  │                               │                   consent? permission?
  │                               │                   FGS → +location type
  │                               │                   one-shot fix (≤45 s)
  │                               │◄── locations/{id} ─────────┤ append LocationFix
  │◄──── listener fires ──────────│                            │ (OK or failure status)
  │  reverse-geocode locally      │                            │
  │  show address + age + ±acc    │                            │
```

Idempotency and freshness come free from the existing `RemoteCommandGate.shouldApply` —
`commandId` dedupe plus a 6 h staleness drop, so a long-offline child does not chase a yesterday
request. Each tap mints a fresh UUID, so repeat requests are honoured.

### Geocoding happens on the PARENT

The wire carries only lat/lng. The parent reverse-geocodes with `Geocoder` for display.

- Keeps the payload tiny and avoids encrypting a second representation of the same fact.
- `Geocoder.isPresent()` can be **false** (AOSP builds, no Play Services). Fallback: show coordinates
  plus an **Open in Maps** `geo:` intent, which always works.
- API 33+ has the async `getFromLocation` callback; 24–32 blocks, so it must run off the main thread.

---

## 5. Child-side capture

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<!-- deliberately NOT ACCESS_BACKGROUND_LOCATION -->
```

Manifest service gains `location` to its `foregroundServiceType`, and
`updateForegroundService(useCamera)` becomes `updateForegroundService(useCamera, useLocation)` —
adding `FOREGROUND_SERVICE_TYPE_LOCATION` only while a fix is in flight, only when the runtime
permission is actually granted, preserving the existing `try/catch` fallback to `SPECIAL_USE`.

**Why this avoids background location:** a foreground service with the `location` type may use
while-in-use location. The service is already running permanently, so no new lifecycle is introduced —
and `ACCESS_BACKGROUND_LOCATION` (a sensitive permission needing written Play justification) is not
requested at all.

Acquisition, zero new dependencies:

| API | Call |
|---|---|
| 30+ | `LocationManager.getCurrentLocation(provider, …)` — one-shot, cancellable |
| 24–29 | `requestLocationUpdates` + remove on first fix, with a timeout |

Provider: prefer `GPS_PROVIDER`, fall back to `NETWORK_PROVIDER`, and seed the UI with
`getLastKnownLocation` so a stale-but-instant answer beats nothing. 45 s budget, then `TIMEOUT`.

One-shot on demand — **no continuous tracking, no geofencing, no background polling.** Battery cost is
per-request and negligible.

### Consent lives on the child device

New DataStore key `location_sharing_enabled`, defaulting **off**, presented in the child's Remote
report screen beside the existing Share / Remote Control switches, and weakened only behind the
existing parent face gate (`guard {}`). With it off, the child answers `NOT_CONSENTED` — an explicit
refusal, never silence.

---

## 6. Entitlement integration

```kotlin
enum class Feature {
    …, LOCATION_NOW,        // DEFAULT_FREE
    …, LOCATION_HISTORY,    // DEFAULT_PREMIUM
}
```

Both flippable via `feature_tiers_json`, like everything else. Additions to wire up:

- `Feature.DEFAULT_FREE` += `LOCATION_NOW`; `DEFAULT_PREMIUM` += `LOCATION_HISTORY`
- `FeatureCopy.title` / `.description` for both (the paywall list is generated from the live config, so
  they appear automatically once added)
- `PremiumGate` around the history list — **UI-visible gating, so no `LapseBanner` is needed.** Banners
  exist only for the enforcement-only features that fail invisibly.
- `EntitlementRevoker`: **no entry.** The child's consent toggle is not the parent's entitlement, and
  revoking a child's setting because a parent's subscription lapsed would be wrong. Parent-side gating
  is sufficient and correct.

Free-tier behaviour: latest fix shown in full; history list replaced by the paywall card.

---

## 7. Multi-device compatibility

The parent now links up to 5 children (`MULTI_DEVICE_PAIRING_PLAN.md`). Location is per-`PairedDevice`
and `locations/{pairingId}` is naturally per-device, but three lifecycle paths **must** be extended or
the feature leaks data:

| Path | Required change | If forgotten |
|---|---|---|
| `ParentReportViewModel.unpairDevice` | delete `locations/{id}` | A removed child's location history stays in Firestore forever |
| `ParentSetupViewModel.reset` | delete `locations/{id}` for every linked device | Same, multiplied |
| `ParentSetupViewModel.rotateKey` | revoke/delete `locations/{oldId}` | Stale coordinates readable with the old key, exactly the `reports` bug fixed in Phase 4d |

Listeners: a **second `ListenerBag`** for the locations collection rather than overloading the report
bag with composite keys — invariant 5 ("every live listener has a matching device entry") stays a
property of one small tested class per collection.

---

## 8. Phases

Each phase compiles, tests, and is independently shippable.

### Phase 1 — pure core (~3 h, no behaviour change)
`LocationFix`, `FixStatus`, `LocationFixCodec`, and a pure `LocationLog` object owning append + cap +
ordering. Nothing reads it yet.

Tests: codec round-trip incl. failure statuses and negative/zero coordinates; cap drops oldest and
only oldest; newest-first ordering; malformed record dropped without losing the rest; forward
compatibility with extra trailing fields.

### Phase 2 — transport + lifecycle (~4 h)
`LocationRepository` (`read` / `listen` / `append` / `delete`, mirroring `RemoteReportRepository`
including the `ReportDoc`-style sealed result). Rules published. **All three cleanup paths from §7 wired
in this phase, not later** — the document must never be able to outlive its pairing.

### Phase 3 — child capture (~5 h)
Permissions, runtime request UI, `LocationProvider` wrapper over `LocationManager`,
`consent → permission → FGS type → fix → append` sequence, 45 s timeout, all five statuses reachable.
`CommandType.REQUEST_LOCATION` + applier branch + service handler.

### Phase 4 — parent current location (~5 h)
Per-device request state machine (Idle → Requesting → Located → Failed), reverse geocoding off the main
thread with a coordinate fallback, **Open in Maps**, relative age ("2 minutes ago"), accuracy and
battery. One card on the parent report screen under the active device.

### Phase 5 — history + entitlement (~3 h)
`Feature` entries, `FeatureCopy`, the history list, `PremiumGate` wrapper, "clear history".

### Phase 6 — child transparency (~2 h)
Notification on the child when a location is shared. See §10.

**Total ≈ 22 h.**

---

## 9. Regression risks

| # | Risk | Mitigation |
|---|---|---|
| A | **Orphaned location documents** on unpair / reset / rotate — coordinates outliving the pairing | §7 cleanup wired in Phase 2, before any UI can create data |
| B | **FGS type change kills the service.** `startForeground` with a type whose permission is missing throws | Keep the existing `try/catch` → `SPECIAL_USE` fallback; add `LOCATION` only when the runtime permission is granted |
| C | **`SecurityException` on an unrequested permission** — a manifest entry is not a grant | Every call site checks `checkSelfPermission` first and maps a refusal to `PERMISSION_DENIED` |
| D | **Codec break.** Appending fields to an existing record format | New standalone codec, not an extension of `RemoteReportCodec`. Positional + tolerant, per house convention |
| E | **Listener leak** with a second collection × N devices | A second `ListenerBag`; never hand-managed registrations |
| F | **Silent spinner** when the child is offline / denies / times out | Failure statuses are first-class and always written; parent shows a reason, never an endless wait |
| G | **Child path regression.** The service is the enforcement path | Location work is additive: a new command branch and one FGS flag. `shouldLockForKidMode` and the monitor loop are untouched. Verify with `git diff` as in the multi-device work. |
| H | **Battery complaints** | One-shot only, no tracking. Fix cancelled on timeout so no provider is left registered. |

---

## 10. Policy and ethics — read before shipping

This is the most sensitive capability in the app. Three items are **not** engineering details:

1. **Play Data Safety + prominent disclosure.** Collecting and transmitting precise location requires a
   Data Safety declaration and an in-app disclosure before the first request. Verify current policy at
   implementation time; do not rely on this document's summary.
2. **The child should know.** Phase 6 posts a notification on the child device whenever a location is
   shared. Android's own status-bar location indicator helps, but it does not say *who* received it.
   Recommended **on by default**; a parent-hideable option is a deliberate product decision worth
   making explicitly rather than by omission.
3. **Consent is the child device's, not the parent's.** `location_sharing_enabled` defaults off and is
   set on the child. A parent cannot remotely enable it — that would make this a covert tracker rather
   than a family feature, and would be the difference between a Play listing and a removal.

Technical properties that support the above: coordinates are E2E encrypted, the relay holds only
ciphertext, there is no passive tracking, and every stored fix corresponds to an explicit parent
request that is itself visible in the history.

---

## 11. Open decisions

1. **Default tier for `LOCATION_NOW`** — the plan assumes FREE per the brief. Locate-on-demand is
   usually a headline paid feature; consider `DEFAULT_PREMIUM`. It is one line and config-flippable
   either way, so this is a pricing call, not an architectural one.
2. **History retention** — 50 entries, or also an age cap (e.g. 30 days)? Entry-count alone is simpler.
3. **Child notification default** — on (recommended) or parent-configurable.
4. **Accuracy floor** — reject a fix worse than, say, 2 km as `TIMEOUT`, or show it labelled
   "approximate"? Showing it labelled is more useful; rejecting is less likely to mislead.

---

## 12. Implementation log

Build: `assembleDebug` SUCCESSFUL. Tests: **203 passing, 0 failures** (188 before this feature).

| Phase | Status | Notes |
|---|---|---|
| 1 | Done | `LocationFix`, `FixStatus`, `LocationLog`, `LocationFixCodec`. 15 tests. |
| 2 | Done | `LocationRepository`, rules updated, all three cleanup paths wired. |
| 3 | Done | Permissions, `LocationProvider`, `LocationResponder`, `REQUEST_LOCATION`, FGS location type. |
| 4 | Done | `AddressResolver`, per-device location state, `LocationSection` UI. |
| 5 | Done | `LOCATION_NOW` (free) + `LOCATION_HISTORY` (premium), `FeatureCopy`, `PremiumGate`. |
| 6 | Done | Child "Location shared" notification (folded into Phase 3). |

### Regression found and fixed during implementation

**Self-inflicted, severe:** `handleLocationRequest` first called
`updateForegroundService(useCamera = false, useLocation = true)`. Two existing call sites
(`:1076`, `:1150`) set `useCamera = true` for face-unlock overlays — so a location request arriving
*while a face scan was running* would have stripped the CAMERA foreground type and could kill the
camera mid-scan, breaking parent unlock at the worst moment.

Fixed with a `@Volatile fgsCameraActive` flag: the location path preserves whatever camera state is
current, and re-reads it on the way out in case an overlay appeared or vanished while the fix was in
flight.

### Verification

- `git diff` vs HEAD on `AllowedApps`, `RemoteReportSync`, `RemoteReportGatherer` → **empty**. The
  screen-time enforcement and reporting paths are untouched.
- `updateForegroundService` gained a defaulted parameter, so all existing call sites are unchanged.
- The weekly report payload is unchanged: location never enters `RemoteReportPayload`, so its stated
  privacy invariant remains true as written.

### Follow-up — location consent now stands on its own (fixed)

**Was:** the command listener was gated on `remoteControlEnabled`, so a child with location sharing ON
but remote control OFF never received the request. The parent saw "couldn't get a fix" — blaming
signal for what was actually an unrelated switch being off. That is bundled consent through the back
door: the exact thing this feature was designed to avoid.

**Now:** consent is evaluated **per command type**, in a pure `CommandConsent` object.

| | remote control ON | remote control OFF |
|---|---|---|
| **location sharing ON** | everything works | location works; lock/limit commands still refused |
| **location sharing OFF** | location answers `NOT_CONSENTED`; other commands work | nothing is delivered |

Three changes:

- `CommandConsent.isPermitted(type, controlEnabled, locationEnabled)` — pure and tested.
- `RemoteCommandApplier` evaluates consent **after** decode, because the rule is per-type. Safe:
  decryption is authorization, so the command is already trustworthy when its type is read.
- The service listener attaches when **either** consent is present; what may be acted on is still
  decided per command.

`REQUEST_LOCATION` is delivered even when location sharing is OFF, on purpose — `LocationResponder`
then replies `NOT_CONSENTED`, so the parent gets a reason rather than an unexplained timeout.

**The safety property, pinned by test:** location sharing can never unlock a controlling command. A
child who switched off "Allow remote control" cannot have their phone locked, time-limited or
reconfigured, whatever else they enabled. `CommandConsentTest` asserts this for every command type,
and the last test fails if a future command type is ever added to the location-unlockable set.

6 new tests (209 total).

### Still required before this works end to end

**Publish the Firestore rules.** `firestore.rules` now contains the `locations/{pairingId}` block, but
it takes effect only when published — Firebase Console → Firestore → Rules → Publish, or
`firebase deploy --only firestore:rules`. Until then every location write fails `PERMISSION_DENIED`.
