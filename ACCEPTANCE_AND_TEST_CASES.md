# Shield — Acceptance Criteria & Test Cases

Formal QA companion to `TESTING_PLAN.md`. Organized by the same **6 parts**. Each area has **Acceptance
Criteria (AC)** — the conditions that must hold for the area to be "accepted" — and **Test Cases (TC)** —
concrete, executable steps.

**Legend.** Priority: **P0** (safety/revenue-critical, blocks release) · **P1** (important) · **P2**
(polish/edge). **⚠** = never run on a device yet. IDs: `AC-<area>-<n>`, `TC-<area>-<n>`.
**Result tracking:** mark each TC `PASS / FAIL / BLOCKED / N/A` with device + build + notes.
**Run history:** see `TEST_RUN_LOG.md`. Executed cases are annotated inline below (e.g. `✅ PASS (Run 1)`).
**Run 1** = 2026-06-15, Motorola Edge 40 Neo, Android 15, debug, ADB.

TC format:
> **TC-x-n — Title** · `Pri`
> *Pre:* preconditions. *Steps:* numbered actions. *Expected:* pass condition.

---

# PART 1 — Feature inventory acceptance (reachability & smoke)

**Goal:** every shipped feature exists, is reachable, and does its basic job. AC here are high-level; deep
behavior is Part 3.

### AC
- **AC-1-1** Every feature in the inventory (areas A–K) is reachable from the UI or triggers automatically, with no dead entry points.
- **AC-1-2** Launching the app cold leaves the foreground service running and the notification visible.
- **AC-1-3** No feature crashes the app on first open (empty/initial state renders).
- **AC-1-4** With `promo_active=true`, all premium-gated features are usable (free), and no purchase UI appears.

### TC
> **TC-1-01 — Cold launch smoke** · `P0` · ✅ **PASS (Run 1, after fix)**
> *Pre:* fresh install, permissions granted. *Steps:* 1) Launch app. 2) Open each top-level tab/section. *Expected:* every screen renders without crash; FGS notification present.
> *Result:* launch OK, FGS foreground + notification ✓. **Uncovered the `ForegroundServiceDidNotStartInTimeException` launch crash → fixed in `AppLockForegroundService.onStartCommand` → re-verified 0 crashes.**

> **TC-1-02 — Settings hub completeness** · `P1` · ✅ **PASS (Run 1)**
> *Pre:* app open. *Steps:* open Settings → tap every row (Kid Mode, Multiple Kids, Remote report, Tamper, Appearance, Permissions, Help). *Expected:* each opens its detail screen and back returns to the hub.
> *Result:* all hub items present; Remote report → role screen → child pane (QR generated) all render, no crash.

> **TC-1-03 — Promo-free reachability** · `P0`
> *Pre:* `promo_active=true`. *Steps:* exercise a premium feature (e.g. Multiple Kids, Themes). *Expected:* fully usable, no paywall/Plus lock.

---

# PART 2 — Environment & prerequisites (setup verification)

**Goal:** the test bench is correctly prepared; otherwise later results are invalid.

### AC
- **AC-2-1** Two physical devices available: one **API 24** (minSdk) and one modern (API 34/35); ideally one non-Pixel OEM.
- **AC-2-2** All runtime permissions granted on the test device: Usage Access, Display-over-other-apps (overlay), Camera, Notifications; Device Admin enabled where tamper is tested.
- **AC-2-3** A Play **license-tester** account is configured and can make test purchases without being charged.
- **AC-2-4** **Cloud Firestore is enabled** and the **current `firestore.rules` is published** (reports + commands + delete).
- **AC-2-5** Remote Config reachable; tester can flip `promo_active`, `paywall_enabled`, `feature_tiers_json`.
- **AC-2-6** Tester understands the budget resets at **07:00** and OS usage data is **not** cleared by reinstalling.

### TC
> **TC-2-01 — Permission preflight** · `P0`
> *Steps:* open Permissions dashboard. *Expected:* every required permission shows granted; revoking one reflects immediately.

> **TC-2-02 — Firestore rules live** · `P0` ⚠
> *Pre:* Firestore enabled. *Steps:* from the app (child) tap Sync now with sharing on. *Expected:* "Report uploaded ✓" and a `reports/{id}` doc appears. (Fails → rules not published / DB not created.)

> **TC-2-03 — License tester** · `P1`
> *Steps:* with promo off + paywall on, start a purchase. *Expected:* Play shows the test-purchase (no real charge) flow.

> **TC-2-04 — Config flip propagation** · `P1`
> *Steps:* change `promo_active` in Remote Config, relaunch after fetch window. *Expected:* app reflects the new value (gating changes).

---

# PART 3 — Functional acceptance & test cases (areas A–K)

## A. Core app-lock & face authentication (P0)

### AC
- **AC-A-1** Opening a **protected** app presents a lock surface before the app is usable.
- **AC-A-2** Only the enrolled live face unlocks; wrong face, photo, or closed-eyes does **not** (cosine ≥ 0.6 + liveness/blink).
- **AC-A-3** Settings (and overlay-hiding apps) use the full-screen `LockActivity`, not the dismissible overlay.
- **AC-A-4** There is **no bypass**: Back/Home, recents, app-switch, screen-off/on, or reboot never leave a protected app open un-authenticated.
- **AC-A-5** The lock **fails safe**: if overlay permission is revoked, it falls back to `LockActivity`; the service never crashes a protection path.
- **AC-A-6** Allow-listed/emergency apps (Phone, SMS) are **never** gated.

### TC
> **TC-A-01 — Protected app locks** · `P0`
> *Pre:* App X protected; face enrolled; service running. *Steps:* open App X. *Expected:* lock surface within ~1s; App X content not interactable.

> **TC-A-02 — Correct face unlocks** · `P0`
> *Steps:* at the lock, present enrolled face, eyes open, blink. *Expected:* unlocks; App X usable.

> **TC-A-03 — Wrong face rejected** · `P0`
> *Steps:* present a different person's face. *Expected:* stays locked; no unlock after repeated attempts.

> **TC-A-04 — Photo/liveness rejected** · `P0`
> *Steps:* hold a printed/phone photo of the enrolled face. *Expected:* does not unlock (no blink/liveness).

> **TC-A-05 — Settings uses LockActivity** · `P0`
> *Pre:* Settings-lock on. *Steps:* open system Settings. *Expected:* full-screen lock (not overlay); covers Settings reliably.

> **TC-A-06 — Back/Home no bypass** · `P0` · ✅ **PASS (Run 3)**
> *Steps:* at the lock press Back, then Home, then re-open the app. *Expected:* sent Home; app re-locks on re-entry; never shows un-authenticated content.
> *Result:* Realme, Kid-Mode budget lock over Ludo. **BACK ×2** → overlay stays up, game not revealed. **HOME** → launcher (overlay cleared, game not usable). **Re-open** → watchdog re-asserts the lock overlay. No un-authenticated content at any step.

> **TC-A-07 — Reboot persistence** · `P0`
> *Steps:* reboot device; open a protected app. *Expected:* service auto-started; lock works.

> **TC-A-08 — Overlay revoked fallback** · `P1`
> *Steps:* revoke "display over other apps"; open a protected app. *Expected:* `LockActivity` path used; no crash.

> **TC-A-09 — Watchdog re-show** · `P1` · ✅ **PASS (Run 3)**
> *Steps:* via fast app-switch try to land on a protected app without the lock. *Expected:* watchdog forces the lock to appear.
> *Result:* Realme — after dismissing the lock to Home, re-opening Ludo re-armed the overlay every time (the 250 ms monitor-loop watchdog re-asserts it). overlay count flipped 0→1 on each re-entry.

> **TC-A-10 — Emergency app never gated** · `P0` · ✅ **PASS (Run 3)**
> *Steps:* open Phone/Dialer while budget is exhausted / app-lock active. *Expected:* opens immediately, never locked.
> *Result:* Realme, budget exhausted (used 188 / limit 59) — Phone (`com.google.android.dialer`) opened immediately, no overlay, never gated. (Allow-listed preset A = Phone + Messages.)

> **TC-A-11 — Screen off/on during lock** · `P1`
> *Steps:* at the lock, turn screen off then on. *Expected:* still locked; auth still required.

## B. Kid Mode — single kid (P1, budget=P0)

### AC
- **AC-B-1** When the daily budget is exhausted, controlled apps lock; the budget **resets at 07:00**.
- **AC-B-2** Night-lock window locks controlled apps during configured night hours.
- **AC-B-3** Allowed presets + custom allow-list keep exactly the chosen apps open.
- **AC-B-4** Free Play grants a temporary unlock for the chosen minutes, then re-locks; recorded in history.
- **AC-B-5** Budget extension adds minutes to *today only* and writes an audit entry atomically.
- **AC-B-6** Earned time, schedules, per-app limits, and auto-block-new-apps each enforce exactly as configured.

### TC
> **TC-B-01 — Budget exhaustion locks** · `P0` · ✅ **PASS (Run 3, after fix)**
> *Pre:* daily limit set (e.g. 30 min), Kid Mode on. *Steps:* use controlled apps until the budget hits 0. *Expected:* controlled apps lock; allow-listed apps still open.
> *Result:* Realme, used 188 / limit 59 — Ludo (controlled) locks via overlay even with Shield backgrounded; Phone/Messages (allow-listed) stay open. **Uncovered + fixed a P0:** the kid-mode lock used a background Activity launch (BAL-blocked on ColorOS after the ~10 s grace window) so nothing displayed; switched to the overlay path. Verified holds. See TEST_RUN_LOG Run 3.

> **TC-B-02 — 07:00 reset** · `P1`
> *Steps:* exhaust budget; cross 07:00 (or set clock). *Expected:* budget resets; usage before/after attributed to the correct budget-day.

> **TC-B-03 — Night lock** · `P1`
> *Pre:* night window configured. *Steps:* open a controlled app during night hours. *Expected:* locked; outside the window it isn't (budget permitting).

> **TC-B-04 — Allowed preset** · `P1`
> *Steps:* select a preset; open an allowed app and a non-allowed one. *Expected:* allowed opens, non-allowed gated.

> **TC-B-05 — Custom picker** · `P1`
> *Steps:* choose Custom, pick specific apps. *Expected:* only those stay open. (If `ALLOWED_PRESETS` flipped premium off-promo, Custom is gated.)

> **TC-B-06 — Free Play session** · `P1`
> *Steps:* start Free Play for N min. *Expected:* controlled apps open for N min, then re-lock; a Free Play record is logged; start/end notifications post.

> **TC-B-07 — Budget extension** · `P1`
> *Steps:* grant +15 min. *Expected:* today's allowance +15; history shows the grant; next 07:00 zeroes extensions but keeps history.

> **TC-B-08 — Earned time** · `P1`
> *Steps:* complete an earned task. *Expected:* the task's minutes are added to the budget.

> **TC-B-09 — Schedule enforcement** · `P1`
> *Steps:* configure a schedule block. *Expected:* controlled apps locked during the block.

> **TC-B-10 — Per-app limit** · `P1`
> *Steps:* set a per-app limit (e.g. YouTube 10 min). *Expected:* that app locks at 10 min even if total budget remains.

> **TC-B-11 — Auto-block new app** · `P1`
> *Pre:* auto-block on. *Steps:* install a new app. *Expected:* it's auto-added to protected/blocked.

## C. Multi-kid — 2 profiles (P1)

### AC
- **AC-C-1** Exactly 2 profiles supported; each can enrol a face.
- **AC-C-2** On a controlled-app open, the **correct kid is identified** (1:N + margin) and that kid's budget/night rules apply.
- **AC-C-3** Ambiguous/sibling match never hard-locks — it falls back to tap-to-confirm or the stricter profile.
- **AC-C-4** Parent face overrides any kid lock.
- **AC-C-5** Per-kid stats and the family comparison reflect each kid's actual attributed usage.

### TC
> **TC-C-01 — Enrol two kids** · `P1`
> *Steps:* enable Multiple Kids; enrol Kid A and Kid B faces. *Expected:* both stored; selector shows both.

> **TC-C-02 — Correct identification** · `P1`
> *Steps:* Kid A opens a controlled app. *Expected:* identified as A; A's budget applies; session attributed to A.

> **TC-C-03 — Sibling ambiguity** · `P1`
> *Steps:* present a borderline/sibling face. *Expected:* tap-to-confirm or stricter profile; **never** a hard lockout.

> **TC-C-04 — Parent override** · `P1`
> *Steps:* at a kid lock, present the parent face. *Expected:* unlocks/authorizes.

> **TC-C-05 — Per-kid budget isolation** · `P1`
> *Steps:* exhaust Kid A's budget. *Expected:* A is locked; Kid B (under budget) is not.

> **TC-C-06 — Family dashboard accuracy** · `P1`
> *Steps:* open Family comparison. *Expected:* each kid's numbers match their actual usage; comparison is neutral/correct.

## D. Stats & dashboards (P1; screen-off=P0)

### AC
- **AC-D-1** No phantom/inflated usage — an app left foregrounded with the screen off does **not** accrue time.
- **AC-D-2** Today / 7-day / 4-week / month totals are self-consistent and match the budget basis.
- **AC-D-3** With `BASIC_STATS` flipped premium (off-promo, non-premium), Stats shows the locked empty state.

### TC
> **TC-D-01 — No screen-off phantom usage** · `P0`
> *Steps:* open a controlled app, turn the screen off, wait 15+ min, turn on. *Expected:* the app's recorded time does not include the screen-off period (the "Clock ~1h59m" bug). **Validate on the user's OEM.**

> **TC-D-02 — Window consistency** · `P1`
> *Steps:* compare today vs 7-day vs month. *Expected:* numbers are plausible and consistent; budget-day boundaries at 07:00.

> **TC-D-03 — Stats gate** · `P1`
> *Pre:* promo off, `BASIC_STATS` premium, non-premium. *Steps:* open Stats. *Expected:* locked empty state with upsell, not the dashboard.

## E. Tamper protection (P1)

### AC
- **AC-E-1** With Device Admin enabled, the app cannot be uninstalled until admin is removed (which itself is gated).
- **AC-E-2** Settings-lock requires the parent face to open Settings.

### TC
> **TC-E-01 — Anti-uninstall** · `P1`
> *Pre:* Device Admin on. *Steps:* attempt uninstall. *Expected:* blocked; requires removing admin first.

> **TC-E-02 — Settings lock** · `P1`
> *Pre:* settings-lock on. *Steps:* open system Settings. *Expected:* parent-face lock required.

## F. Monetization (P0)

### AC
- **AC-F-1** With `promo_active=true`, the app is **fully free**, no purchase prompts, every feature usable.
- **AC-F-2** Billing/Play unavailable or offline **never crashes** the app and **never blocks protection**; treated as free.
- **AC-F-3** Off-promo + paywall on + non-premium shows **real** ProductDetails prices (monthly/annual), trial, Restore, legal links.
- **AC-F-4** A verified, acknowledged `premium` subscription unlocks premium features; lapse/cancel reverts to free on next launch.
- **AC-F-5** Flipping `feature_tiers_json` converts exactly the named feature(s) free↔premium with no app update; others unaffected.
- **AC-F-6** Entitlement is re-queried from Play every launch; the cached flag is never authoritative.

### TC
> **TC-F-01 — Promo = free, no prompts** · `P0`
> *Pre:* promo on. *Steps:* use the app broadly. *Expected:* no paywall; all features free.

> **TC-F-02 — Billing-unavailable safety** · `P0`
> *Steps:* disable Play / go offline; launch. *Expected:* no crash; protection works; treated as free.

> **TC-F-03 — Paywall real prices** · `P1`
> *Pre:* promo off, paywall on, non-premium. *Steps:* open paywall. *Expected:* real monthly+annual prices, trial timeline, Restore, Terms/Privacy.

> **TC-F-04 — Purchase unlocks** · `P1`
> *Steps:* buy via license tester. *Expected:* premium granted, acknowledged, cached; premium features unlock.

> **TC-F-05 — Restore** · `P1`
> *Steps:* reinstall / new launch; tap Restore. *Expected:* premium restored without re-purchase.

> **TC-F-06 — Lapse reconciles** · `P1`
> *Steps:* cancel/expire the sub; relaunch. *Expected:* reverts to free; premium features re-gate.

> **TC-F-07 — Single-feature flip** · `P1`
> *Steps:* set `feature_tiers_json` to make only `THEMES` premium (promo off, non-premium). *Expected:* only Themes gates; others unchanged.

> **TC-F-08 — APP_LOCK flip (deliberate)** · `P0`
> *Steps:* flip `APP_LOCK` premium for a non-subscriber (promo off). *Expected:* the documented behavior — confirm this is intended, since it can disable protection. **Validate consciously.**

## G. Push notifications — FCM (P2)

### AC
- **AC-G-1** Every install subscribes to topic `all` and creates the `announcements` channel.
- **AC-G-2** A console broadcast displays as a notification in foreground and background.

### TC
> **TC-G-01 — Broadcast received** · `P2`
> *Steps:* send a Firebase console message to topic `all`. *Expected:* notification appears (app foreground and backgrounded).

## H. Remote Report ⚠ (P1 — never device-tested)

### AC
- **AC-H-1** Pairing: the child shows a QR; the parent scans it; both end up with the same pairing id + key.
- **AC-H-2** Only **aggregate** data is uploaded, **E2E-encrypted**; Firestore holds **ciphertext only** (no plaintext, no content).
- **AC-H-3** The parent reads, **decrypts on-device**, and renders a report matching the child's actual stats.
- **AC-H-4** Sharing is **OFF by default**; with it off, nothing leaves the device.
- **AC-H-5** Cadence (daily/weekly) controls sync frequency; **key rotation** invalidates old ciphertext; **revoke** deletes the cloud docs.
- **AC-H-6** Wrong/rotated key → the parent sees a clean "can't decrypt" state, never garbage.
- **AC-H-7** Off-promo + non-premium → the share toggle is gated (disabled + Plus badge).

### TC
> **TC-H-01 — Pairing round-trip** · `P1` ⚠ · 🟡 **PARTIAL (Run 1)**
> *Pre:* Firestore live. *Steps:* child → Set up as child (QR shows); parent → Scan child's QR. *Expected:* parent shows "paired"; both hold the same pairing.
> *Result:* child-side done — "Set up as child" mints a pairing and renders a valid QR on-device. **Parent scan + both-hold-same-pairing still needs the 2nd phone.**

> **TC-H-02 — Encrypted upload** · `P1` ⚠
> *Steps:* child enable Share → Sync now. *Expected:* "Report uploaded ✓"; Firestore `reports/{id}` has `iv`+`ciphertext` blobs only (unreadable), no plaintext fields.

> **TC-H-03 — Parent renders decrypted** · `P1` ⚠
> *Steps:* parent opens Remote report after a sync. *Expected:* per-kid cards (avg, weekly bars, top apps, budget-hit) match the child's stats.

> **TC-H-04 — Opt-in OFF = silent** · `P1` ⚠
> *Steps:* leave Share off. *Expected:* no `reports` doc is created; nothing uploads.

> **TC-H-05 — Cadence reschedule** · `P1` ⚠
> *Steps:* toggle Daily vs Weekly. *Expected:* the periodic worker's interval updates (inspect via WorkManager/`adb`).

> **TC-H-06 — Key rotation** · `P1` ⚠
> *Steps:* child Rotate key → new QR; old parent tries to view. *Expected:* old parent can no longer decrypt; re-scanning the new QR works.

> **TC-H-07 — Revoke deletes cloud** · `P1` ⚠
> *Steps:* child/parent Stop sharing / unpair. *Expected:* `reports/{id}` and `commands/{id}` deleted from Firestore; local keys wiped; worker cancelled.

> **TC-H-08 — Wrong-key state** · `P1` ⚠
> *Steps:* induce a key mismatch (rotate without re-scan). *Expected:* parent shows "couldn't decrypt", not crash/garbage.

> **TC-H-09 — Premium gate** · `P2` ⚠
> *Pre:* promo off, `REMOTE_REPORT` premium, non-premium. *Steps:* open child pane. *Expected:* Share toggle disabled + Plus badge.

## I. Remote Control ⚠ (P1 — never device-tested)

### AC
- **AC-I-1** A command applies **only if it decrypts under the pairing key** (forged commands rejected).
- **AC-I-2** Commands are **idempotent** (no double-apply) and **stale-rejecting** (old commands ignored on reconnect).
- **AC-I-3** Lock now / grant time / set limit each take effect on the child within seconds (Firestore listener), when the child has **Allow remote control** on.
- **AC-I-4** With the child opt-in OFF, commands are ignored.
- **AC-I-5** A remote lock is an **honest** "Locked by parent" screen, cleared by the parent face in person (no remote unlock).

### TC
> **TC-I-01 — Remote lock** · `P1` ⚠
> *Pre:* paired; child Allow remote control on. *Steps:* parent → Lock now. *Expected:* child locks within a few seconds with an honest lock screen.

> **TC-I-02 — Grant extra time** · `P1` ⚠
> *Steps:* parent → +30 min. *Expected:* child's today allowance increases by 30.

> **TC-I-03 — Set daily limit** · `P1` ⚠
> *Steps:* parent → Set limit 60. *Expected:* child's daily limit becomes 60 min.

> **TC-I-04 — Forged command rejected** · `P1` ⚠
> *Steps:* write a `commands/{id}` doc with ciphertext from a different key. *Expected:* child ignores it (decrypt fails); no effect.

> **TC-I-05 — Idempotency** · `P1` ⚠
> *Steps:* deliver a command; restart the child (listener re-attaches and re-reads the doc). *Expected:* the command applies **once**, not again.

> **TC-I-06 — Opt-in OFF** · `P1` ⚠
> *Steps:* child Allow remote control off; parent sends a command. *Expected:* child ignores it.

> **TC-I-07 — Parent-face clears remote lock** · `P1` ⚠
> *Steps:* after a remote lock, present the parent face on the child. *Expected:* lock clears (remote unlock is intentionally not available).

> **TC-I-08 — Premium gate** · `P2` ⚠
> *Pre:* promo off, `REMOTE_CONTROL` premium, non-premium. *Steps:* child pane + parent controls. *Expected:* child "Allow remote control" disabled + Plus badge; parent control buttons disabled + Plus badge.

## J. Onboarding, theming & permissions UI (P2)

### AC
- **AC-J-1** Fresh install runs the first-run wizard; coach-mark tours play and can be replayed.
- **AC-J-2** Changing the theme accent applies app-wide and persists.
- **AC-J-3** The permissions dashboard accurately reflects real grant states and routes to the system grant flows.

### TC
> **TC-J-01 — First-run wizard** · `P2`
> *Steps:* fresh install → launch. *Expected:* wizard appears; completing/skipping marks first-run done.

> **TC-J-02 — Tour replay** · `P2`
> *Steps:* Help → replay welcome/settings tour. *Expected:* coach marks restart on the right targets.

> **TC-J-03 — Theme accent** · `P2`
> *Steps:* Appearance → change accent. *Expected:* applied immediately + persists across relaunch.

> **TC-J-04 — Permissions dashboard truth** · `P1`
> *Steps:* grant/revoke a permission outside the app, return. *Expected:* dashboard reflects the change; tapping routes to the correct system screen.

## K. Permissions & system integration (P1)

### AC
- **AC-K-1** Each declared permission is requested with rationale and the feature degrades gracefully if denied.
- **AC-K-2** The special-use FGS runs with its notification and survives backgrounding/Doze.
- **AC-K-3** Camera FGS type is used only while authenticating.

### TC
> **TC-K-01 — Usage Access required** · `P0`
> *Steps:* deny/revoke Usage Access. *Expected:* app prompts for it; budget/stats clearly need it; no crash.

> **TC-K-02 — Notifications permission** · `P1`
> *Steps:* deny POST_NOTIFICATIONS (API 33+). *Expected:* FGS still runs; app explains the impact.

> **TC-K-03 — Doze survival** · `P1`
> *Steps:* leave idle in Doze; trigger a lock later. *Expected:* service alive; lock works; remote listener reconnects.

---

# PART 3B — Review-pass additions (gaps found on second review)

Extra cases the first pass missed — **enrollment, clock-manipulation security, subscription edge states,
remote offline/edge paths, and resilience.** Same ID scheme, continuing each area's numbering.

## A. Core lock & **enrollment** (was under-covered)
> **TC-A-12 — Face enrollment success** · `P0`
> *Pre:* no face enrolled. *Steps:* run enrollment in good lighting. *Expected:* face captured + stored; subsequently unlocks (TC-A-02).

> **TC-A-13 — Enrollment with no/poor face** · `P1`
> *Steps:* enroll in darkness / no face in frame. *Expected:* clear "no face detected" feedback; nothing saved; no crash.

> **TC-A-14 — Protected app opened with NO face enrolled** · `P1`
> *Pre:* protected app set, but no face enrolled. *Steps:* open it. *Expected:* defined behavior (guides to enroll / safe state) — never an unguarded open or a dead lock.

> **TC-A-15 — Re-enroll overwrites** · `P1`
> *Steps:* enroll a new face over an existing one. *Expected:* old embedding replaced; old face no longer unlocks; new one does.

> **TC-A-16 — Unlock grace (no immediate re-lock)** · `P1`
> *Steps:* unlock App X; stay in it / re-open immediately. *Expected:* not re-prompted while it stays the active unlocked package.

> **TC-A-17 — Re-lock after leaving** · `P0` · 🟡 **PARTIAL (Run 3)**
> *Steps:* unlock App X, go Home, re-open App X later. *Expected:* locks again (no permanent unlock).
> *Result:* **no-permanent-unlock confirmed** — every re-entry of Ludo re-asserts the lock (Home/Back never leave it open). The *unlock-then-relock* half needs a live face to first authenticate; covered behaviourally by the watchdog re-show (TC-A-09).

> **TC-A-18 — Camera busy at lock time** · `P1`
> *Steps:* hold the camera open in another app, trigger a lock. *Expected:* lock still shows; auth recovers when the camera frees; no crash.

> **TC-A-19 — Auth give-up / retry** · `P2`
> *Steps:* present nothing for an extended time. *Expected:* sensible behavior (keeps trying / stays locked); no battery/heat runaway.

> **TC-A-20 — Split-screen / multi-window** · `P2`
> *Steps:* open a protected app in split-screen. *Expected:* lock still enforced.

> **TC-A-21 — Clear app data → re-enroll** · `P1`
> *Steps:* clear app data. *Expected:* face embedding gone; protected apps require re-setup + re-enroll; no crash, no silent open.

## B. Kid Mode — **clock & boundary security**
> **TC-B-12 — Toggle Kid Mode on/off** · `P1`
> *Steps:* switch owner type kid→parent→kid. *Expected:* enforcement turns off/on correctly; no stuck lock.

> **TC-B-13 — Clock-forward budget cheat** · `P0` 🔒 · ⏸ **BLOCKED (Run 3 — needs manual clock)**
> *Steps:* exhaust budget; the kid sets the device clock past 07:00 to force a reset, then back. *Expected:* the app does not hand out a free extra day from clock tampering (define + verify the resist/detection behavior). **Key parental-control attack vector.**
> *Note:* not ADB-automatable — the device clock can't be set without root (`su` absent; `date` silently reverts under SELinux). Run manually: Settings → Date & time → off auto → jump past 07:00, then open a controlled app and check it stays locked. (Suspect FAIL: reset keys off `DAY_OF_YEAR`, no monotonic/anti-tamper guard — worth confirming.)

> **TC-B-14 — Clock-backward** · `P1` 🔒
> *Steps:* set the clock backward several hours. *Expected:* budget/usage stay sane; no negative time, no infinite budget.

> **TC-B-15 — DST / 07:00 boundary** · `P2`
> *Steps:* cross a DST change near 07:00. *Expected:* budget-day boundary stays correct (DAY_OF_YEAR math).

> **TC-B-16 — Grant time while already locked (over budget)** · `P1`
> *Steps:* over-budget lock active; grant +15. *Expected:* lock releases / app becomes usable for the new allowance.

> **TC-B-17 — Free Play while over budget** · `P1`
> *Steps:* start Free Play after budget exhausted. *Expected:* temporary unlock for the session, then re-locks to the over-budget state.

> **TC-B-18 — Schedule + night-lock overlap** · `P2`
> *Steps:* configure overlapping schedule + night windows. *Expected:* locked in the union; no flicker/conflict.

## C. Multi-kid — **migration, grace, hand-off**
> **TC-C-07 — Single→multi migration** · `P1`
> *Steps:* enable multi-kid from an existing single-kid setup. *Expected:* existing config migrates to Profile 1; nothing lost.

> **TC-C-08 — Revert multi→single** · `P1`
> *Steps:* turn multi-kid off. *Expected:* returns to single-kid behavior; no identity scans; data intact.

> **TC-C-09 — Identify grace window** · `P1`
> *Steps:* identify Kid A, then re-open a controlled app within ~2 min. *Expected:* keeps A active without a fresh scan.

> **TC-C-10 — Hand-off A→B** · `P1`
> *Steps:* Kid A active, Kid B picks up and opens a controlled app after the grace window. *Expected:* re-identifies as B; B's budget applies.

> **TC-C-11 — No confident match** · `P1`
> *Steps:* present an unknown face (not either kid, not parent). *Expected:* tap-to-confirm / stricter profile; never a hard lockout.

> **TC-C-12 — Remove a kid / face** · `P1`
> *Steps:* delete a profile or its face. *Expected:* that kid no longer identified; remaining kid unaffected.

## D. Stats — **empty & uninstalled-app paths**
> **TC-D-04 — Empty stats** · `P2`
> *Steps:* fresh install, open Stats before any usage. *Expected:* clean empty state, no crash, no NaN/blank charts.

> **TC-D-05 — Uninstalled app in window** · `P2`
> *Steps:* use an app, uninstall it, open Stats covering that period. *Expected:* it's omitted gracefully (no crash from unresolved package).

> **TC-D-06 — Kid with zero sessions** · `P2`
> *Steps:* open a kid's stats who has no recorded sessions. *Expected:* zeroed/empty view, no crash.

## E. Tamper — **resilience**
> **TC-E-03 — Disable Device Admin is gated** · `P1`
> *Steps:* attempt to disable admin. *Expected:* requires the parent gate (not freely removable by the kid).

> **TC-E-04 — Force-stop recovery** · `P1`
> *Steps:* force-stop the app from system Settings. *Expected:* service restarts (boot/JobScheduler) and locking resumes within a reasonable time.

> **TC-E-05 — Clear app data** · `P1`
> *Steps:* clear data. *Expected:* full reset (settings, face, profiles); app re-onboards; no half-state.

> **TC-E-06 — Lock own app** · `P1`
> *Pre:* lock-own-app on. *Steps:* open Shield. *Expected:* Shield itself requires the parent face.

> **TC-E-07 — Safe Mode (known limitation)** · `P2`
> *Steps:* boot into Safe Mode. *Expected:* documented partial — third-party FGS may be suspended; verify it recovers on normal boot (full closure needs Device Owner — out of scope).

## F. Monetization — **subscription edge states**
> **TC-F-09 — PENDING purchase** · `P1`
> *Steps:* trigger a pending (e.g. slow-payment) purchase. *Expected:* not premium until it completes; no premium granted on PENDING.

> **TC-F-10 — Invalid signature** · `P1`
> *Steps:* simulate a tampered/invalid purchase signature. *Expected:* entitlement denied (not premium).

> **TC-F-11 — Lapsed-but-cached reconcile** · `P1`
> *Steps:* cache shows premium; sub has lapsed on Play. *Steps:* relaunch. *Expected:* re-query revokes; reverts to free.

> **TC-F-12 — Promo countdown** · `P2`
> *Pre:* `free_until_epoch_ms` set. *Steps:* open the early-access screen. *Expected:* countdown displays (display-only; gating still by `promo_active`).

> **TC-F-13 — Manage-subscription deep link** · `P2`
> *Steps:* tap manage subscription. *Expected:* opens Play's subscription management.

> **TC-F-14 — Upgrade/downgrade** · `P2`
> *Steps:* switch monthly↔annual. *Expected:* Play proration flow; entitlement remains.

> **TC-F-15 — Entitlement follows the account** · `P2`
> *Steps:* sign the premium account into a second device. *Expected:* premium recognized there too.

## G. FCM — **token & tap**
> **TC-G-02 — Token refresh** · `P2`
> *Steps:* force a token refresh (clear/reinstall). *Expected:* `onNewToken` re-subscribes to `all`.

> **TC-G-03 — Notification tap** · `P2`
> *Steps:* tap a received notification. *Expected:* opens the app (no crash).

> **TC-G-04 — Delivery while app killed** · `P2`
> *Steps:* swipe-kill the app; send a broadcast. *Expected:* notification still shows.

## H. Remote Report ⚠ — **parent states & QR/edge**
> **TC-H-10 — Parent Empty state** · `P1` ⚠
> *Steps:* parent opens the report when paired but no doc has synced yet. *Expected:* "Paired — no report yet" (not error/crash).

> **TC-H-11 — Parent Refresh** · `P1` ⚠
> *Steps:* child syncs; parent taps Refresh. *Expected:* re-fetches and updates the render.

> **TC-H-12 — Multi-kid report** · `P1` ⚠
> *Pre:* child in multi-kid mode. *Steps:* sync + view. *Expected:* both kids appear as separate cards.

> **TC-H-13 — Zero-usage report** · `P2` ⚠
> *Steps:* sync with no usage. *Expected:* report renders with zeros, no crash.

> **TC-H-14 — Foreign/blurry QR** · `P1` ⚠
> *Steps:* point the parent scanner at a non-Shield QR / blurry code. *Expected:* ignored; keeps scanning until a valid pairing QR.

> **TC-H-15 — Camera denied on parent scan** · `P1` ⚠
> *Steps:* deny camera on the parent. *Expected:* "grant camera" prompt; no crash; scanning resumes after grant.

> **TC-H-16 — Re-pair overwrites** · `P2` ⚠
> *Steps:* parent scans a different child's QR. *Expected:* pairing replaced cleanly.

> **TC-H-17 — Parent offline opening report** · `P2` ⚠
> *Steps:* parent offline, open report. *Expected:* cached value or graceful "couldn't reach" — no crash.

> **TC-H-18 — Key rotation mid-view** · `P2` ⚠
> *Steps:* child rotates key while the parent has an old report open; parent Refreshes. *Expected:* decrypt-failed state until the parent re-scans the new QR.

## I. Remote Control ⚠ — **offline, stale, listener lifecycle**
> **TC-I-09 — Delivery after reconnect** · `P1` ⚠
> *Steps:* child offline; parent sends Lock now; bring child online. *Expected:* the listener fires on reconnect and applies it.

> **TC-I-10 — Stale command ignored** · `P1` ⚠
> *Steps:* deliver a command older than the max age (~6h). *Expected:* ignored (not applied on a late reconnect).

> **TC-I-11 — Invalid arg (limit ≤ 0)** · `P1` ⚠
> *Steps:* craft Set-limit 0 / Grant 0. *Expected:* ignored (no zeroed/negative limit applied).

> **TC-I-12 — Listener re-attaches on state change** · `P1` ⚠
> *Steps:* toggle child Allow-remote-control off→on, or rotate the key. *Expected:* listener detaches/re-attaches to the right doc; commands still arrive.

> **TC-I-13 — Rapid multiple commands** · `P1` ⚠
> *Steps:* parent fires several commands quickly. *Expected:* latest applies; idempotency prevents double-apply; no race crash.

> **TC-I-14 — Multi-kid set-limit (global, documented)** · `P2` ⚠
> *Steps:* in multi-kid mode, parent sets a limit. *Expected:* it sets the **global/single** limit (per-kid targeting is not built) — confirm this matches the documented limitation.

> **TC-I-15 — Lock when already locked** · `P2` ⚠
> *Steps:* send Lock now while the child is already locked. *Expected:* no-op/refresh, no double overlay.

## J. Onboarding — **skip & persistence**
> **TC-J-05 — Skip wizard** · `P2`
> *Steps:* skip the first-run wizard. *Expected:* marked done; app usable; not shown again.

> **TC-J-06 — Theme persists across reboot** · `P2`
> *Steps:* set accent, reboot. *Expected:* accent retained.

## K. Permissions — **denial paths**
> **TC-K-04 — All-denied cold start** · `P1`
> *Steps:* fresh install, deny everything. *Expected:* app guides through each grant; never crashes; clearly blocks the features that need them.

> **TC-K-05 — "Don't ask again" routing** · `P1`
> *Steps:* permanently deny a permission. *Expected:* app routes to system Settings (can't re-prompt directly); explains why.

# PART 4 — Cross-cutting & negative tests (P1)

### AC
- **AC-X-1** Revoking any runtime permission mid-use degrades gracefully (no crash; protection preserved where possible).
- **AC-X-2** Network loss during a remote sync/command queues and recovers (Firestore offline cache).
- **AC-X-3** RTL locales render correctly.
- **AC-X-4** On a **minSdk 24** device, QR (hex) + crypto + all core flows work (no `java.util.Base64`/API-26 crash).
- **AC-X-5** Multi-kid + remote features run together without interference.

### TC
> **TC-X-01 — Mid-use permission revoke** · `P1`
> *Steps:* revoke overlay/camera/usage while a lock or scan is active. *Expected:* graceful fallback; no crash.

> **TC-X-02 — Airplane mode during remote op** · `P1` ⚠
> *Steps:* toggle airplane mode mid sync/command. *Expected:* operation queues; completes on reconnect.

> **TC-X-03 — RTL layout** · `P2`
> *Steps:* set device to an RTL locale. *Expected:* screens mirror correctly; no clipped text.

> **TC-X-04 — minSdk 24 full pass** · `P0`
> *Steps:* install on API 24; run core lock + Kid Mode + a remote pair. *Expected:* installs and works; no crash on hex/crypto/QR paths.

> **TC-X-05 — Combined load** · `P1` ⚠
> *Steps:* 2 kids enrolled + remote report + remote control all active. *Expected:* identify-session and command listener don't interfere.

> **TC-X-06 — App upgrade preserves data** · `P1`
> *Steps:* install build N, configure, install build N+1 (versionCode bump). *Expected:* settings/profiles/face/pairing survive; no re-onboarding.

> **TC-X-07 — Process death restoration** · `P1`
> *Steps:* force low-memory / kill the process; reopen. *Expected:* ViewModel/UI state restores sanely; the service is alive (or restarts); no crash.

> **TC-X-08 — Accessibility (TalkBack)** · `P2`
> *Steps:* enable TalkBack; navigate lock + main screens. *Expected:* labels/liveRegions read; the lock state is announced; interactive controls reachable.

> **TC-X-09 — Battery saver / OEM killer** · `P1`
> *Steps:* enable battery saver + an aggressive OEM task-killer; leave idle, then trigger a lock and a remote command. *Expected:* service survives or auto-restarts; protection + listener recover.

> **TC-X-10 — Rooted device (known limitation)** · `P2` 🔒
> *Steps:* on a rooted device, attempt to spoof the cached premium flag. *Expected:* documented limitation — client-side entitlement is spoofable on root (server-side is future); confirm it doesn't crash and behaves as designed.

> **TC-X-11 — Clock tamper across features** · `P1` 🔒
> *Steps:* change the device clock and exercise budget reset, report `generatedAt`, and command freshness together. *Expected:* no feature is trivially defeated; timestamps/ordering stay coherent.

---

# PART 5 — Pre-release exit gates (acceptance for release)

These are **release acceptance criteria** — every one must be GREEN to ship.

- **GATE-1 (P0)** All Part-3 **P0** test cases PASS on ≥2 devices, including one non-Pixel OEM.
- **GATE-2 (P0)** ⚠ Remote Report + Remote Control validated **end-to-end on two phones** (pair → sync → render; lock/grant/limit).
- **GATE-3 (P0)** Billing path fully verified on a test track: trial → bill → cancel → restore → "billing unavailable" degrades safely.
- **GATE-4 (P0)** Screen-off usage measurement correct on the **target OEM** (no phantom usage).
- **GATE-5 (P0, legal)** Face-model licence resolved (**SFace**) before any paid tier is enabled. *(Not a test — a gate.)*
- **GATE-6 (P0)** Play **Pre-launch report** clean; **Data Safety** form matches reality (on-device default; opt-in E2E aggregate sharing).
- **GATE-7 (P1)** No P0/P1 crashes in a 48-hour soak on each device; FGS survives Doze + reboot.
- **GATE-8 (P0, security) 🔒** Anti-cheat sign-off: clock-manipulation (TC-B-13/14, TC-X-11) doesn't hand out free budget; no lock bypass (TC-A-06/07/17); forged remote commands rejected (TC-I-04). Document any accepted residual risk (e.g. root).

### Verification TC
> **TC-GATE-01 — Two-phone E2E** · `P0` ⚠
> *Steps:* run TC-H-01..03 + TC-I-01..03 on two real phones. *Expected:* all PASS.

> **TC-GATE-02 — Billing lifecycle** · `P0`
> *Steps:* run TC-F-03..06 on the internal track. *Expected:* all PASS, no real charge.

> **TC-GATE-03 — Soak** · `P1`
> *Steps:* use normally for 48h. *Expected:* no crashes; service persistent; lock reliable.

---

# PART 6 — Known-not-built (confirm absent / graceful)

**Goal:** confirm these are correctly *absent* or *degraded* — not half-exposed.

### AC
- **AC-6-1** No "remote unlock" control is shown to the parent (only Lock / grant / limit).
- **AC-6-2** No "report ready"/SOS push is claimed in-app (those need Blaze; not built).
- **AC-6-3** No command-history / per-kid command targeting UI is exposed.

### TC
> **TC-6-01 — No remote-unlock affordance** · `P2`
> *Steps:* inspect parent controls. *Expected:* no Unlock button; help text says the parent face clears the lock.

> **TC-6-02 — No phantom push claims** · `P2`
> *Steps:* inspect remote UI/copy. *Expected:* no promise of automatic "report ready" alerts.

---

## How to run
1. Prepare Part 2 (environment) — do not start functional testing until AC-2-* hold.
2. Run Part 3 area-by-area, **P0 first**, on each device; record PASS/FAIL/notes per TC.
3. Run Part 4 cross-cutting.
4. Treat Part 5 gates as the **definition of done**; ship only when all are green.
5. Spot-check Part 6 so nothing half-built leaks into the UI.

## Cross-references
- `TESTING_PLAN.md` — the overview this expands.
- `PARENT_REMOTE_REPORT_PLAN.md` / `PARENT_REMOTE_CONTROL_PLAN.md` — remote feature internals.
- `PREMIUM_FEATURE_PLAN.md` / `PREMIUM_IMPLEMENTATION_STATUS.md` — entitlement/billing behavior.
- `PLAYSTORE_RELEASE_PLAN.md` — Data Safety, declarations, release ladder.
- `FACE_MODEL_MIGRATION_PLAN.md` — the SFace licence gate (GATE-5).
