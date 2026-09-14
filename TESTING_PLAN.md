# Shield — Feature Inventory & Comprehensive Test Plan

The complete feature list, then a prioritized test plan. **Priorities:** P0 = must pass before any release
(safety/core), P1 = important features, P2 = polish/edge. **⚠ = never run on a real device yet** (the remote
features + billing + some lock paths) — these are the highest-risk items.

---

## Part 1 — Full feature inventory

### A. Core app-lock & authentication
- **Foreground service** (`AppLockForegroundService`, special-use + camera FGS) watches the foreground app and locks protected apps.
- **Face authentication** — CameraX + ML Kit face detection + TFLite MobileFaceNet, cosine ≥ 0.6, with **liveness** (blink) detection.
- **Two lock surfaces** — lightweight overlay (most apps) + full-screen `LockActivity` (Settings / overlay-hiding apps).
- **Ghost authentication / intruder feedback**; **lock message types** (hardware-error vs health overlay).
- **Boot persistence** (`BootReceiver` restarts the service); **watchdog** re-shows the lock if bypassed.

### B. Kid Mode (single kid)
- Owner type (parent / kid); **daily screen-time budget** with **07:00 reset**; **night lock** window.
- **Allowed-app presets** + custom picker; **Free Play** (temp-unlock session); **budget extension** (grant minutes).
- **Earned time** (tasks → minutes); **schedules**; **per-app limits**; **auto-block new apps**.

### C. Multi-kid (2 profiles)
- Enable multi-kid; enrol 2 kids' faces; **1:N face identify** (+ margin + tap-to-confirm fallback).
- Per-kid budgets / sessions / usage attribution; **parent face = authority/override**.
- **Family dashboard + comparison** between the two kids.

### D. Stats & dashboards
- Event-based **usage measurement** (`UsageMeasure`) with screen-off handling; today / 7-day / 4-week / month windows.
- Charts: `BudgetRing`, `SparkBars`, `WeeklyTrendCard`, top apps; multi-kid family comparison. `BASIC_STATS` gate.

### E. Tamper protection
- **Device Admin** (`AdminReceiver`) anti-uninstall; **Settings lock**; lock-own-app.

### F. Monetization (config-driven, dormant under promo)
- **Entitlements** (`Feature` flags → `isUnlocked`); **Firebase Remote Config** (`promo_active`, `paywall_enabled`, `feature_tiers_json`).
- **Play Billing** (subscription, monthly/annual, intro offer, 30-day trial, restore); **paywall** screen (promo vs paywall mode).
- Every feature flippable free↔premium with **no app update**.

### G. Push notifications (FCM)
- Channel `announcements`, topic `all` subscription, console broadcast push.

### H. Remote Report ⚠ (parent sees the child's stats remotely)
- **QR pairing** (child shows / parent scans, ZXing); **AES-GCM E2E**; **Firestore** sync (child write, ciphertext only).
- Parent **read → decrypt → render** (reuses Stats components); **opt-in** (off by default); **cadence** (daily/weekly).
- **Premium gate** (`REMOTE_REPORT`); **key rotation**; **full revoke** (deletes cloud docs).

### I. Remote Control ⚠ (parent → child commands)
- **Lock now / grant extra time / set daily limit**; Firestore **real-time listener** delivery (no Blaze).
- **Auth = decryption** (forged commands rejected); **idempotent** + stale-rejecting; child **opt-in**; **premium gate** (`REMOTE_CONTROL`).
- (Remote **UNLOCK** dismiss = NOT built; parent face clears the lock in person.)

### J. Onboarding & UX
- First-run wizard; **coach marks / tours**; **theming** (accent picker); **permissions dashboard**.

### K. Permissions & system integration
- Usage Access, Overlay (SYSTEM_ALERT_WINDOW), Camera, Notifications, Device Admin, boot, QUERY_ALL_PACKAGES.

---

## Part 2 — Test environment & prerequisites

- **Devices:** at least **2 physical Android phones** (remote features need two). Cover **minSdk 24** and a modern (API 34/35) device; ideally one non-Pixel OEM (for screen-off usage-event quirks).
- **Grants required:** Usage Access, Display-over-other-apps (overlay), Camera, Notifications, and (for tamper) Device Admin.
- **Accounts:** a Play **license-tester** account for billing; a Google account on the test device.
- **Backend:** **Cloud Firestore enabled** + the current `firestore.rules` **published** (remote features fail without this).
- **Config matrix:** test under **`promo_active=true`** (everything free — launch state) AND **`promo_active=false`** + `paywall_enabled=true` (paid mode), flipping `feature_tiers_json`.
- **Reset note:** screen-time budget resets at **07:00** and usage data is OS-level — uninstalling does **not** reset it.

---

## Part 3 — Test cases by area

Format: **[Priority] Action → Expected.**

### A. Core lock & face auth (P0 — safety-critical)
- [P0] Add an app to protected, open it → lock surface appears.
- [P0] Authenticate with the enrolled face → unlocks; wrong face → stays locked.
- [P0] Closed-eyes / photo of face → does **not** unlock (liveness).
- [P0] Lock **Settings** → full-screen `LockActivity` (not the overlay).
- [P0] Back / Home from the lock → user sent Home, app re-locks on re-entry (no bypass).
- [P0] Reboot the phone → service auto-restarts, locking still works.
- [P1] Revoke overlay permission at runtime → falls back to `LockActivity`, no crash.
- [P1] Watchdog: force-foreground a protected app without the lock → lock re-appears.
- [P2] Lock message type toggle (hardware-error vs health) shows the right screen.

### B. Kid Mode (P1)
- [P0] Set a daily budget, consume it → controlled apps lock when budget hits 0; allow-listed apps (Phone/SMS) never gated.
- [P1] Cross the **07:00** boundary → budget resets; usage before/after attributed to the right budget-day.
- [P1] Night lock window → controlled apps locked during night hours.
- [P1] Allowed presets + custom picker → only chosen apps stay open.
- [P1] Free Play session → temporary unlock for the granted minutes, then re-locks; logged in history.
- [P1] Budget extension (+N min) → today's allowance bumps; audit entry written.
- [P1] Earned time: complete a task → minutes added.
- [P1] Schedules / per-app limits / auto-block new app → each enforces as configured.

### C. Multi-kid (P1)
- [P1] Enable multi-kid, enrol 2 faces → both stored.
- [P1] Each kid opens a controlled app → **correct kid identified**; their budget applies.
- [P1] Sibling cross-match → tap-to-confirm / stricter fallback (never a hard lockout).
- [P1] Parent face on a kid lock → override/unlock.
- [P1] Family dashboard → per-kid + comparison numbers match each kid's actual usage.

### D. Stats (P1)
- [P0] **Screen-off / phantom usage** — leave a protected app, turn screen off; verify no phantom hours (the Clock ~1h59m bug). Validate on the user's OEM.
- [P1] Today / 7-day / 4-week / month totals are plausible and consistent across views.
- [P1] `BASIC_STATS` flipped premium (promo off, non-premium) → Stats shows the locked empty state.

### E. Tamper (P1)
- [P1] Enable Device Admin → uninstall is blocked until admin is removed.
- [P1] Settings-lock on → opening Settings requires the parent face.

### F. Monetization (P0 — revenue + safety)
- [P0] **Promo on (default):** everything unlocked, **no purchase prompts**, app fully free.
- [P0] Billing unavailable / offline → app **never crashes**, treats as free, protection unaffected.
- [P1] Promo off + paywall on + not premium → paywall shows **real** ProductDetails prices, monthly/annual, trial.
- [P1] Purchase on the license-tester track → entitlement granted, acknowledged, cached; premium features unlock.
- [P1] Restore purchases; cancel/lapse → reconciled to free on next launch.
- [P1] Flip a single feature via `feature_tiers_json` → only that feature gates; others unchanged.
- [P0] Flip **APP_LOCK** premium for a non-subscriber → confirm intended behavior (this can disable protection — validate deliberately).

### G. FCM (P2)
- [P2] Send a console broadcast to topic `all` → notification appears (foreground + background).

### H. Remote Report ⚠ (P1 — never device-tested)
- [P0-for-this-feature] Firestore enabled + rules published (else everything below fails).
- [P1] Child: Set up as child → QR shows. Enable **Share weekly report** → **Sync now** → "Report uploaded ✓".
- [P1] Firestore console → `reports/{id}` doc holds **iv + ciphertext blobs only** (unreadable).
- [P1] Parent: scan child's QR → pairs → report **renders decrypted** and matches the child's stats.
- [P1] **Wrong/rotated key** → parent shows the decrypt-failed state (not garbage).
- [P1] Cadence Daily/Weekly → worker reschedules (inspect WorkManager).
- [P1] **Rotate key** → new QR; old parent can no longer decrypt; re-scan works.
- [P1] **Unpair/revoke** → `reports` + `commands` docs deleted from Firestore; local keys wiped.
- [P1] Opt-in OFF → nothing uploads (verify no doc appears).
- [P2] Promo off + non-premium → share toggle disabled with Plus badge.

### I. Remote Control ⚠ (P1 — never device-tested)
- [P1] Child enables **Allow remote control**; parent taps **Lock now** → child locks within a few seconds (listener).
- [P1] Parent **+30 min** → child's budget allowance increases.
- [P1] Parent **Set limit 60** → child's daily limit becomes 60.
- [P1] Child clears the remote lock with the **parent face** (remote unlock not built).
- [P1] **Forged command** (simulate a Firestore doc written with the wrong key) → child ignores it (decrypt fails).
- [P1] Re-deliver the same command (restart child) → applied **once** (idempotency).
- [P1] Opt-in OFF on child → commands ignored.

### J. Onboarding & theming (P2)
- [P2] Fresh install → first-run wizard; coach-mark tours play; replay works.
- [P2] Theme accent change → applied app-wide.
- [P2] Permissions dashboard reflects real grant states.

---

## Part 4 — Cross-cutting & negative tests
- **Permission revocation mid-use** (camera/overlay/usage) → graceful degrade, no crash, protection preserved where possible.
- **Doze / battery optimization** → service survives; remote listener reconnects.
- **Airplane mode** during a remote sync/command → queues/recovers when back online (Firestore offline cache).
- **Locale / RTL** (app supports RTL) → layouts intact.
- **minSdk 24 device** — `java.util.Base64` is avoided; QR (hex) + crypto still work; verify install + core flows.
- **Two kids + remote features together** → no interference between identify-session and the command listener.

## Part 5 — Pre-release gates (must be green)
1. All **P0** pass on ≥2 devices (incl. one non-Pixel OEM).
2. Remote Report + Remote Control validated end-to-end on **two phones**.
3. Billing: trial → bill → cancel → restore → "billing unavailable" all verified on a test track.
4. Screen-off usage measurement correct on the target OEM (no phantom usage).
5. **Face-model licence resolved (SFace)** before enabling any paid tier — legal gate, not a test.
6. Play **Pre-launch report** clean; Data Safety form matches reality (see `PLAYSTORE_RELEASE_PLAN.md`).

## Part 6 — Known not-built (don't test; out of scope)
- Remote **UNLOCK** dismiss (parent face clears in person).
- Report **push nudge** + instant **SOS** (need Blaze).
- Remote Control **history / per-kid command targeting / extra commands**.

## Cross-references
- `PARENT_REMOTE_REPORT_PLAN.md`, `PARENT_REMOTE_CONTROL_PLAN.md` — the two remote features + their phases.
- `PREMIUM_FEATURE_PLAN.md` / `PREMIUM_IMPLEMENTATION_STATUS.md` — entitlement/billing behavior.
- `PLAYSTORE_RELEASE_PLAN.md` — Data Safety, permission declarations, release ladder.
- `FACE_MODEL_MIGRATION_PLAN.md` — the SFace licence gate before monetizing.
