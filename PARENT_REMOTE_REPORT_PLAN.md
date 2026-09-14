# Parent Remote Report — Feature Plan

Let a parent see their child's **screen-time report on their own phone, remotely** — without the
surveillance model the cloud competitors use. Only **aggregated** stats leave the device, **end-to-end
encrypted**, no accounts, no PII. This closes the single biggest gap vs. free Google Family Link while
*keeping* the privacy moat (Qustodio/Bark upload readable content; we upload encrypted totals only).

Status: **Phases 1–4 + 6 built** — the end-to-end pipe + premium gating are code-complete: child gathers→encrypts→uploads, parent fetches→decrypts→renders (reusing Stats components); `Feature.REMOTE_REPORT` is default-premium (free during promo), opt-in OFF by default, Data-Safety declaration updated. 102 unit tests green, debug APK assembles. Owner must enable Firestore + deploy `firestore.rules`; the full two-phone round-trip (scan → sync → view) needs device validation. Remaining: only **5** (Cloud Function → FCM nudge — *owner*, optional, needs Blaze). Phase 7 polish (cadence, key rotation, full revoke-with-cloud-delete) is **done**. Post-launch flagship. Natural premium feature.

---

## 0. Why this feature

Per the competitive review, Shield's #1 weakness is "no remote parent visibility — you must pick up the
kid's device." Family Link (free) has a remote dashboard. This feature answers *"why not just use free
Family Link?"* with something Family Link can't match: **private, multi-kid, shared-device, remote report.**

## 1. The privacy invariant (the whole point)

> Only an **aggregate summary** ever leaves the device, **E2E-encrypted** with a key the relay never sees.
> Content, messages, location, face data, keystrokes, and raw events **never leave the device — ever.**

If this invariant is broken, the feature is just spyware and the moat is gone. Every decision serves it.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Aggregate-only payload:** daily/weekly totals, top app names + minutes, budget-hit days. Nothing else. Enforced by the payload *type* (no raw events can be put in it). |
| D2 | **End-to-end encryption (AES-GCM 256).** Key generated on the child device at pairing, shared to the parent via QR, stored only in both devices' DataStore. The relay (Firestore) holds **ciphertext only**. |
| D3 | **Pairing = QR**, carrying a random `pairingId` + the symmetric key. No account, email, phone number, or PII. |
| D4 | **Reuse existing Firebase** — Firestore for the encrypted doc, FCM (already wired) for the "report ready" nudge. No custom backend except an optional Firestore-trigger Cloud Function for the nudge. |
| D5 | **Same app, role-based** — setup asks "This is the child's device" vs "This is a parent's viewer." Parent mode adds the pairing-scan + report screens. No separate companion app (reuses the whole codebase). |
| D6 | **Premium-gated** (`Feature.REMOTE_REPORT`, default-premium). A natural thing to charge for. |
| D7 | **Opt-in, OFF by default.** With it off, nothing syncs and the "nothing leaves the device" claim still holds. Turning it on flips the Data-Safety story to "aggregated screen-time, shared, E2E-encrypted." |
| D8 | **Transparent, not covert** — the child device visibly shows "Weekly report sharing is ON." Required so Play classifies it as legit parental control, not stalkerware. |

## 3. Architecture (components → files)

| File | Role |
|---|---|
| `remote/RemoteReportPayload.kt` | The aggregate data model + pure (de)serialization. Unit-tested. |
| `remote/ReportCrypto.kt` | Pure AES-GCM encrypt/decrypt (javax.crypto). Unit-tested (JVM). |
| `remote/PairingManager.kt` | Generate `pairingId` + key; QR string encode/parse. |
| `remote/RemoteReportRepository.kt` | Firestore read/write of `reports/{pairingId}` (ciphertext only). |
| `remote/RemoteReportWorker.kt` | WorkManager job (child side): build payload from `StatsRepository` → encrypt → write. Weekly. |
| `ui/parent/ParentSetupScreen.kt` | Role choice + QR show (child) / QR scan (parent, via ML Kit barcode). |
| `ui/parent/ParentReportScreen.kt` | Parent: read doc → decrypt → render (reuse `ui/stats` components). |
| `data/DataStoreManager.kt` | `remote_role`, `remote_pairing_id`, `remote_pairing_key`, `remote_share_enabled`. |
| `premium/Feature.kt` | New `REMOTE_REPORT` (default-premium) + its entitlement gate. |
| `firestore.rules` | Restrict the doc to the paired devices (anon-auth uids; see §8). |
| `functions/onReportWritten.js` *(optional)* | Firestore trigger → FCM "report ready" to the parent token. |

## 4. Data model

**DataStore (both devices):**
| Key | Meaning |
|---|---|
| `remote_role` | `none` / `child` / `parent` |
| `remote_pairing_id` | random 128-bit id (the Firestore doc key) |
| `remote_pairing_key` | base64 AES-256 key (E2E secret) |
| `remote_share_enabled` | Bool, default false (opt-in) |

**Firestore `reports/{pairingId}`** (ciphertext only): `{ iv, ciphertext, updatedAt, schemaVersion }`.

**Encrypted payload** (`RemoteReportPayload`): `{ generatedAt, kids: [{ name, limitMin, dailyAvgMs,
week:[ms×7], topApps:[{name, ms}], budgetHitDays }] }`. **No package-level raw events, no content.**

## 5. Flow

```
CHILD DEVICE                    FIREBASE (ciphertext only)         PARENT DEVICE
────────────                    ──────────────────────────         ─────────────
StatsRepository (have it)
   │ weekly (WorkManager)
   ▼
aggregate → RemoteReportPayload
   │ ReportCrypto.encrypt(key)
   ▼  {iv, ciphertext}
   └────────► Firestore reports/{pairingId} ────────► ParentReportScreen
                          │                            reads doc → decrypt(key)
              (E2E: can't read)        (optional)──FCM "report ready"──► render (reuse Stats UI)

Pairing (one-time): child shows QR{pairingId, key} → parent scans → both store id+key+peer.
```

## 6. Build phases (each compiles + is independently testable)

| Phase | Scope | Verify |
|---|---|---|
| **1** | `RemoteReportPayload` (pure codec) + `ReportCrypto` (AES-GCM). No Firebase/UI. | Unit tests: round-trip encode + encrypt/decrypt; wrong key fails. Builds offline. |
| **2** | `PairingManager` + DataStore keys + `ParentSetupScreen` (role choice, QR show via a QR encoder, QR scan via ML Kit barcode). | Pair two devices; both hold the same id+key. |
| **3** | `firebase-firestore` dep + `RemoteReportRepository` + `RemoteReportWorker` (child writes encrypted weekly) + `firestore.rules`. | Child device writes; Firestore shows ciphertext only. |
| **4** | `ParentReportScreen` — read + decrypt + render (reuse `BudgetRing`/`SparkBars`/etc.). | Parent device shows the child's weekly report. |
| **5** *(owner)* | Firestore-trigger Cloud Function → FCM "report ready" nudge. | Parent gets a push when a new report lands. |
| **6** | `Feature.REMOTE_REPORT` gate + **opt-in toggle** + child-side "sharing ON" indicator + Data-Safety declaration update. | Off by default; gated premium; disclosed. |
| **7** *(later)* | Multi-kid in one report; cadence (daily/weekly); **unpair/revoke**; key rotation. | Revoke wipes access; re-pair works. |

**Order:** 1 → 2 → 3 → 4 → 5 → 6 → (7). Phases 1 is pure-Kotlin/offline; 3+ need the Firebase console + a device.

## 7. Reuse vs. new

| Already have | New |
|---|---|
| On-device stats + weekly aggregation (`StatsRepository`) | QR pairing + key exchange |
| Firebase project + **FCM** (wired) | **E2E crypto** (`ReportCrypto`) |
| ML Kit (for the QR **barcode scanner**) | **Firestore** sync + rules |
| Stats UI components (reuse in the parent view) | A **parent role/mode** + 2 screens |
| Entitlement/gating system | `Feature.REMOTE_REPORT` |

## 8. Edge cases & guardrails

- **Security rules:** give each device a Firebase **Anonymous Auth** uid; the pairing records the two
  allowed uids; rules allow read/write to `reports/{pairingId}` only for those uids. (Even without that,
  the payload is E2E ciphertext — the `pairingId` is a 128-bit secret bearer token — but uid rules are
  the correct production posture.)
- **Unpair / lost parent phone:** delete the doc + clear keys → access revoked; re-pair issues a new key,
  old ciphertext is unreadable.
- **Never blocks the child:** sync is a background WorkManager job; Firestore offline just makes the report
  stale. The lock/budget engine is completely independent and unaffected.
- **Opt-in default OFF:** until a parent enables sharing, nothing leaves the device.
- **Stalkerware compliance (D8):** the child device shows a visible "report sharing ON" state; the feature
  is framed as parent-viewing-own-family aggregates, disclosed in-app + in the listing.
- **Cost:** tiny aggregate docs → comfortably inside Firestore's free tier.
- **Data Safety honesty:** when enabled, declare "App activity (aggregate screen time) — shared, encrypted
  in transit & at rest (E2E)." Keep it precise so the privacy claim stays true.

---

## Phase tracker

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

| Phase | Description | Status | Notes |
|---|---|---|---|
| 1 | Pure payload codec + `ReportCrypto` (AES-GCM) + tests | `[x]` | Done — `remote/RemoteReportPayload.kt` + `remote/ReportCrypto.kt`; 12 tests (codec round-trip/strip/null + crypto round-trip/wrong-key/tamper/fresh-IV). Builds offline. |
| 2 | Pairing (QR) + DataStore + setup/role UI | `[x]` | Done — `remote/PairingManager.kt` + `QrCodec.kt` (ZXing-core, **not** ML Kit — smaller, offline), DataStore keys, `ui/parent/` setup screen, wired into Settings → "Remote report". 10 tests. ⚠ Two-phone scan needs device validation. |
| 3 | Firestore sync (child write) + rules + WorkManager | `[x]` | Done — `RemoteReportRepository` (Blob ciphertext), `RemoteReportGatherer`/`Builder`/`Sync`, `RemoteReportWorker` (CoroutineWorker + Hilt EntryPoint, weekly), `firestore.rules`, opt-in switch + "Sync now". Adds `firebase-firestore`, `work-runtime`, real Guava (CameraX clash fix). ⚠ Owner: enable Firestore + deploy rules; device-validate the write. |
| 4 | Parent read + decrypt + render | `[x]` | Done — `ParentReportViewModel` (read→decrypt→decode, Loading/Empty/Error/Loaded states) + `ParentReportScreen` (`ParentReportPane`), reusing `SparkBars`/`DailyAverageLine`/`TopAppLine`. Shown when role=parent. ⚠ Needs a real synced doc to device-validate the render. |
| 5 | *(owner)* Cloud Function → FCM nudge | `[ ]` **deferred by choice** | Optional. Requires the **Blaze** plan (card on file). The feature works without it — the parent screen's "Refresh" button covers it. Build only if users ask for a proactive ping. |
| 6 | Premium gate + opt-in + transparency + Data-Safety | `[x]` | Done — `Feature.REMOTE_REPORT` (default-premium) + entitlement gate on the share toggle (disabled + Plus badge when locked, free during promo), opt-in switch, transparency copy, Data-Safety declaration updated in `PLAYSTORE_RELEASE_PLAN.md` §5. Entitlement test pins the default. |
| 7 | Multi-kid / cadence / unpair / rotation | `[x]` | Done — multi-kid (gatherer); daily/weekly cadence picker → worker period; unpair/revoke deletes cloud report+command docs + cancels worker + wipes keys; key rotation re-mints the pairing (new QR). |

## How to resume
1. Read §1 (the invariant — never break it) and §2 (decisions).
2. Build Phase 1 first (pure crypto + payload, fully testable) — it's the privacy core.
3. Each later phase needs the Firebase console + a device; validate on real phones.
4. Keep it **opt-in, aggregate-only, E2E** at every step, or the privacy positioning collapses.

## Cross-references
- `PREMIUM_FEATURE_PLAN.md` — gating model (`Feature.REMOTE_REPORT` joins DEFAULT_PREMIUM).
- `FIREBASE_SETUP.md` / `MESSAGING_SETUP.md` — the Firebase project + FCM this builds on.
- `PARENT_REMOTE_CONTROL_PLAN.md` — the **write** counterpart (parent→child commands) built on this same pairing/crypto/relay.
- Competitive rationale: closes the Family-Link "remote dashboard" gap without becoming cloud spyware.
