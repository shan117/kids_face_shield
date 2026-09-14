# Parent Remote Control — Feature Plan

Let a parent **act on** the child's device from their own phone — lock it now, grant extra time, change the
daily limit — *remotely*, without the surveillance model the cloud competitors use. This is the **write**
counterpart to the **read**-only Parent Remote Report: same pairing, same E2E key, same relay. Together they
give Shield the headline capability of Google Family Link (remote management) while keeping the one thing none
of the competitors have — **it's all private, commands only, no data harvested.**

Status: **Phases 1–3, 5, 6 built; Phase 4 partial** (LOCK_NOW done, remote UNLOCK deferred). The pipe is code-complete: parent sends bounded E2E commands → child listener applies grant-time / set-limit / lock. 118 unit tests green, debug APK assembles. ⚠ Device-validate the command round-trip + the remote lock. Builds on the shipped Remote Report pairing + crypto + Firestore.

---

## 0. Why this feature

The Remote Report (shipped) answers *"can a parent see usage remotely?"* This answers *"can a parent **do**
something about it remotely?"* — the other half of what makes Family Link useful. Crucially, we already built
the hard parts (QR pairing, AES-GCM E2E, Firestore relay, entitlements, an always-on foreground service), so
the remaining work is small. **The pairing is a platform; this is the second app on it.**

## 1. The privacy invariant (the whole point)

> Only **bounded commands** flow parent → child, **E2E-authenticated** with the shared key. No content, no
> location, no message access — ever. The child device **visibly shows** parent-initiated actions (a remote
> lock is honest, not a fake error). Nothing about the child is uploaded by this feature.

If broken, this becomes remote spyware. Every decision below serves the invariant.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Bounded command set only:** `LOCK_NOW`, `UNLOCK`, `GRANT_EXTRA_TIME(min)`, `SET_DAILY_LIMIT(min)`. No arbitrary/admin commands — limited blast radius by design. |
| D2 | **Commands are E2E-authenticated** (AES-GCM via the existing `ReportCrypto`). A command only applies if it decrypts under the pairing key — so a bearer who knows the `pairingId` but not the key **cannot forge a command**. GCM's auth tag *is* the authorization. |
| D3 | **Delivery = Firestore real-time listener on the child** (a snapshot listener on `commands/{pairingId}`). Near-instant, **works on the free Spark plan — no Cloud Function, no Blaze.** The always-on foreground service hosts the listener. |
| D4 | **Idempotent:** each command carries a `commandId` + `issuedAtMs`; the child records `lastAppliedCommandId` and ignores already-applied or stale commands (no replay, no double-apply). |
| D5 | **Reuse the Remote Report pairing** — same `remote_pairing_id` / `remote_pairing_key`. Pair once, get both report (read) and control (write). |
| D6 | **Transparent (D8 of the report plan):** a remote lock shows an honest "Locked by parent" screen; the child UI indicates remote control is active. Required so Play classifies it as parental control, not stalkerware. |
| D7 | **Premium-gated** (`Feature.REMOTE_CONTROL`, default-premium) + **opt-in, OFF by default** on the child. With it off, the child ignores commands and nothing is acted upon. |
| D8 | **Acks, not telemetry:** the child may write back a tiny `{ lastAppliedCommandId, appliedAtMs }` ack so the parent sees "delivered". That's status, not child data. |

## 3. Architecture (components → files)

| File | Role |
|---|---|
| `remote/RemoteCommand.kt` | The command model (`type`, `arg`, `commandId`, `issuedAtMs`) + pure (de)serialization. Unit-tested. |
| `remote/RemoteCommandCodec.kt` | Pure, dependency-free encode/decode (mirrors `RemoteReportCodec`). Unit-tested. |
| `remote/RemoteCommandRepository.kt` | Firestore write (parent) + **snapshot listener** (child) of `commands/{pairingId}` (ciphertext only). |
| `remote/RemoteCommandSender.kt` | Parent side: build → encrypt → write a command. Opt-in/role/pairing gated. |
| `remote/RemoteCommandApplier.kt` | Child side: decrypt → verify (auth + idempotency) → apply via existing `DataStoreManager` setters / lock trigger. |
| `service/AppLockForegroundService.kt` | Hosts the child-side listener (it's already always-on); routes applied commands into the lock/budget engine. |
| `ui/parent/ParentReportScreen.kt` | Parent: add **Lock now / Grant +30 min / Change limit** controls to the existing report view. |
| `data/DataStoreManager.kt` | `remote_control_enabled` (child opt-in), `last_applied_command_id` (idempotency). Reuse `addExtensionMinutes`, `setDailyLimitMinutes`. |
| `premium/Feature.kt` | New `REMOTE_CONTROL` (default-premium) + its gate. |
| `firestore.rules` | Extend with `commands/{pairingId}` — same bearer-token + no-enumeration model as `reports`. |

## 4. Data model

**Command (encrypted payload, AES-GCM under the pairing key):**
`{ commandId: String, issuedAtMs: Long, type: LOCK_NOW|UNLOCK|GRANT_EXTRA_TIME|SET_DAILY_LIMIT, arg: Int }`
— `arg` is minutes for grant/limit, ignored for lock/unlock.

**Firestore `commands/{pairingId}`** (ciphertext only): `{ iv, ciphertext, updatedAt, schemaVersion }` — identical
shape to `reports`, holding the encrypted latest command. Optional ack: `{ lastAppliedCommandId, appliedAtMs }`.

**DataStore (child):**
| Key | Meaning |
|---|---|
| `remote_control_enabled` | Bool, default false (opt-in; mirrors `remote_share_enabled`) |
| `last_applied_command_id` | String — the last command applied, for idempotency |

## 5. Flow

```
PARENT DEVICE                  FIRESTORE (ciphertext only)        CHILD DEVICE (service always-on)
─────────────                  ──────────────────────────        ────────────────────────────────
tap "Lock now" / "+30 min"
  build RemoteCommand
  ReportCrypto.encrypt(key)
  └──────────► commands/{pairingId} ──(snapshot listener)──► onCommand(ciphertext)
                       │                                      decrypt(key)  ← auth = authorization (D2)
          (relay can't read it)                               if new id (D4) + opt-in (D7):
                                                                 apply: lock / addExtensionMinutes / setDailyLimit
  ◄──────────── ack {lastAppliedCommandId} ◄──────────────────  write ack (D8)
```

No Cloud Function: the child's Firestore **listener** is the delivery mechanism (D3), free on Spark.

## 6. Build phases (each compiles + is independently testable)

| Phase | Scope | Verify |
|---|---|---|
| **1** | `RemoteCommand` + `RemoteCommandCodec` (pure) + reuse `ReportCrypto`; idempotency/auth logic. No Firebase/UI. | Unit tests: command round-trip; forged (wrong-key) command rejected; stale/duplicate id ignored. Builds offline. |
| **2** | `RemoteCommandRepository` (parent write + child snapshot listener) + extend `firestore.rules`. | Parent writes; child listener fires; Firestore shows ciphertext only. |
| **3** | `RemoteCommandApplier` + wire the listener into `AppLockForegroundService`; apply `GRANT_EXTRA_TIME` + `SET_DAILY_LIMIT` via existing setters. | On-device: parent grants time → child budget bumps. |
| **4** | `LOCK_NOW` / `UNLOCK` → honest "Locked by parent" screen (reuse the lock overlay). | Parent taps Lock → child locks visibly; Unlock clears it. |
| **5** | Parent UI controls in `ParentReportScreen` + ack/status ("delivered ✓"). | Parent sees command delivered + applied. |
| **6** | `Feature.REMOTE_CONTROL` gate + **child opt-in toggle** + transparency indicator + Data-Safety note. | Off by default; gated premium; disclosed. |
| **7** *(later)* | Command history/audit; more commands (bedtime now, pause internet-app category); per-kid targeting in multi-kid. | Each new command type gates + applies. |

**Order:** 1 → 2 → 3 → 4 → 5 → 6 → (7). Phase 1 is pure/offline; 2+ need the Firebase console + a device.

## 7. Reuse vs. new

| Already have | New |
|---|---|
| QR pairing + `remote_pairing_id`/`key` | The bounded **command model** + codec |
| **`ReportCrypto`** (AES-GCM) — reused verbatim for command auth | **Firestore snapshot listener** (child) |
| Firestore relay pattern (`RemoteReportRepository`) | `RemoteCommandApplier` + service wiring |
| Always-on foreground service (hosts the listener) | Parent **control UI** |
| `addExtensionMinutes`, `setDailyLimitMinutes`, lock overlay | `Feature.REMOTE_CONTROL` + opt-in key |
| Entitlement/gating system | — |

## 8. Edge cases & guardrails

- **Forged commands:** rejected by D2 — a command that doesn't decrypt under the key never applies. The bearer
  `pairingId` lets you *write* the doc but not *authorize* an action. (Production hardening: Anonymous-Auth uids,
  same as the report's §8.)
- **Replay / double-apply:** D4 idempotency — `lastAppliedCommandId` gates re-application; `issuedAtMs` rejects
  stale commands.
- **Never weakens protection:** the command set **cannot** disable tamper protection, app-lock, or device admin.
  `UNLOCK` only clears a *remote* lock, never the kid-budget/night locks. Bounded blast radius (D1).
- **Offline child:** Firestore caches the command; the listener fires on reconnect. Lock isn't real-time-guaranteed
  — it's "as soon as the child is online," clearly communicated to the parent (ack shows delivery).
- **Stalkerware compliance (D6):** remote lock is an **honest** "Locked by parent" screen (not the deceptive
  hardware-error overlay); the child shows that remote control is enabled. Disclosed in-app + listing.
- **Opt-in default OFF (D7):** until the child enables remote control, commands are ignored.
- **Battery/cost:** one Firestore listener on a doc that changes rarely → negligible reads, comfortably free-tier.

---

## Phase tracker

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

| Phase | Description | Status | Notes |
|---|---|---|---|
| 1 | Command model + codec + crypto/idempotency (pure) + tests | `[x]` | Done — `RemoteCommand`/`CommandType`, `RemoteCommandCodec`, `RemoteCommandGate`; 8 tests (round-trip, bad-type/number→null, idempotency, stale, blank-id, authentic round-trip, forged-key rejected). Builds offline. |
| 2 | Firestore command repo (parent write + child listener) + rules | `[x]` | Done — `RemoteCommandRepository` (Blob ciphertext, `addSnapshotListener`), `commands/{pairingId}` rule added. |
| 3 | Applier + service wiring; grant-time + set-limit | `[x]` | Done — `RemoteCommandApplier` (decrypt→gate→apply), listener wired into `AppLockForegroundService` (re-attaches on role/pairing/opt-in change). Grant time → `addExtensionMinutes`; set limit → `setDailyLimitMinutes`. ⚠ Device-validate. |
| 4 | `LOCK_NOW` / `UNLOCK` → honest "Locked by parent" | `[~]` | **LOCK_NOW done** (reuses `enforceLock` kid-mode lock, cleared by parent face). **UNLOCK remote-dismiss deferred** — `LockActivity` has no inbound dismiss hook; needs device-tested plumbing. UI exposes Lock but not Unlock. |
| 5 | Parent control UI + ack/status | `[x]` | Done — `RemoteControls` in `ParentReportScreen` (Lock now / +15·30·60 min / set limit 30·60·90·120) + status. |
| 6 | `Feature.REMOTE_CONTROL` gate + opt-in + transparency + Data-Safety | `[x]` | Done — `Feature.REMOTE_CONTROL` (default-premium) gates both the child opt-in and the parent controls; separate "Allow remote control" child switch (OFF default). No new child-data sharing (commands carry only bounded codes), so Data-Safety unchanged. Entitlement test pins the default. |
| 7 | History / more commands / per-kid targeting | `[ ]` | Polish. |

## How to resume
1. Read §1 (the invariant — bounded, E2E-authenticated, transparent) and §2 (decisions).
2. Build Phase 1 first (pure command codec + crypto auth + idempotency) — fully testable offline.
3. Phase 2+ need the Firebase console (Firestore already enabled for the report) + a device.
4. Keep it **bounded commands, E2E-authenticated, opt-in, transparent** at every step, or the privacy
   positioning collapses into spyware.

## Cross-references
- `PARENT_REMOTE_REPORT_PLAN.md` — the **read** half; this reuses its pairing, crypto, relay, and entitlement model.
- `PREMIUM_FEATURE_PLAN.md` — gating model (`Feature.REMOTE_CONTROL` joins DEFAULT_PREMIUM).
- `firestore.rules` — extend with `commands/{pairingId}` (same bearer-token rules as `reports`).
- Competitive rationale: matches Family Link's **remote management** without becoming cloud spyware — the moat holds.
