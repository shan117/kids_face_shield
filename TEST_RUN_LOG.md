# Device Test Run Log

Append one block per device-test session. Maps to `ACCEPTANCE_AND_TEST_CASES.md` IDs.

---

## Run 1 — 2026-06-15 · Motorola Edge 40 Neo · Android 15 (SDK 35) · debug build · ADB (wireless)

Driver: autonomous raw-ADB (Claude). 1080×2400, non-Pixel OEM (good fragmentation coverage).

### Results
| Case | Result | Notes |
|---|---|---|
| TC-1-01 cold launch + crash check | **PASS** (after fix) | process alive; crash buffer empty post-fix |
| AC-1-2 FGS running + notification | **PASS** | `isForeground=true`, special-use type `0x40000000`, ongoing notification |
| AC-1-3 UI renders | **PASS** | Protect tab, app list, Free Play, nav |
| TC-1-02 navigation → Settings hub | **PASS** | all hub items present (Kid Mode, Multiple Kids, Remote report, Tamper, Appearance) |
| Remote report entry reachable + renders | **PASS** | Beta badge + privacy copy; role-choice screen renders |
| `ParentSetupScreen` role choice | **PASS** | "Set up as child" / "Scan child's QR" both render |
| Child pane + **QR generation** | **PASS** | crisp QR rendered on-device (full `PairingManager→QrEncoder→BitMatrix→Bitmap` path); both opt-ins default **OFF** ✓ |
| Unpair flow | **PASS** | returns to role choice; local state cleared |
| Promo mode | **PASS** | "Kids Shield Plus — Free for now" (promo_active=true) |

### 🐞 Bug found + fixed (P1 stability — crash on launch)
**`ForegroundServiceDidNotStartInTimeException`** — the app crashed (then auto-recovered).
- **Root cause:** `MainActivity.onCreate` always calls `startForegroundService()`, but the service's
  `onStartCommand` did **not** call `startForeground()`. When the app is reopened while the service is
  **already running** (system destroyed the Activity but the FGS survived — a common case), only
  `onStartCommand` runs (not `onCreate`), so `startForeground()` was never called that cycle → Android 12+
  kills the app after ~5s.
- **Fix:** re-assert `startForeground(NOTIFICATION_ID, createNotification(), SPECIAL_USE)` at the top of
  `onStartCommand` (idempotent when already foreground). File: `service/AppLockForegroundService.kt`.
- **Verified:** reproduced the exact path (`am start-foreground-service` while running) → **same pid, 0
  crashes, service still foreground.** Was: crash + pid change.

### Not run this session (need human / camera / 2nd device / Play / Firestore)
Face enrollment & unlock, multi-kid identify, two-phone remote round-trip, remote commands on a child,
real billing, clock-tamper (needs root). See `ACCEPTANCE_AND_TEST_CASES.md` Parts 3 H/I + 5.

### Note
Coordinate-based tapping is brittle (one stray tap toggled an app's protection; reverted). This is exactly
why the **instrumented test layer (L3)** with UIAutomator/Compose semantics is the right way to automate UI
flows — see `TEST_STRATEGY.md` §2.

---

## Run 2 — 2026-06-17 · Realme RMX3750 (**child**) · Android 15 (SDK 35) · debug build · ADB (wireless)

Driver: autonomous raw-ADB (Claude). Validating the new A1–A5 cage UI on the child instance. Device was
already paired (role=child) from a prior session; **no parent face enrolled** on this device.

### Results
| Case | Result | Notes |
|---|---|---|
| Child remote pane renders (QR + cards) | **PASS** | crisp pairing QR; role=child pane intact after reinstall |
| A1 "Lock down this device" card | **PASS** | red lock icon + switch **correctly disabled** with "Enrol your face (Face tab) first" (no face on this device) |
| A3 cage checklist | **PASS** | header "Cage status — **0 of 4 active**"; all 4 rows render with ⚠ + correct fix hints; Device-Admin row shows **Enable** button; force-stop/Safe-Mode note present |
| A5 honesty banner | **PASS** | "**Standard protection**" + full force-stop / Safe-Mode disclosure (device is not Device Owner) |
| Lower controls reachable (share/control/cadence/sync/rotate/unpair) | **PASS (after fix)** | were clipped; now scroll into view — both opt-ins showed **ON** from prior pairing |

### 🐞 Bug found + fixed (P1 UX — content clipped / unreachable)
**Child remote pane was not scrollable.** The shared content `Column` (`ParentSetupScreen.kt:113`) is
`fillMaxSize()` with **no `verticalScroll`**. With the QR taking most of the viewport, everything below it —
the A3 checklist rows, the A5 banner, **and the pre-existing Share / Allow-control / cadence / Sync now /
Rotate key / Unpair controls** — was off-screen and unreachable on the child device. Adding the A1/A3/A5
cards made it worse by pushing those controls further down.
- **Fix:** wrap the `role == "child"` branch in `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))`
  (left the camera-scan + parent-report branches untouched, since they manage their own height/scroll).
- **Verified:** rebuilt + reinstalled → pane scrolls; all previously-clipped controls now reachable; A3 + A5
  render in full (screenshots in `.adb_shots/`).

### Env wrangling (for repeatability)
- Device dozes fast on wireless ADB → set `settings put system screen_off_timeout 600000` and woke with
  `KEYCODE_WAKEUP`. Auto-rotate flipped to landscape mid-test → pinned portrait (`accelerometer_rotation 0`,
  `user_rotation 0`). **Restore accelerometer_rotation to 1 after testing.**

### A4 — remote full-lock round-trip (two-phone: Motorola parent → Realme child)
| Case | Result | Notes |
|---|---|---|
| Parent report renders (decrypted) | **PASS** | "Decrypted on this device", Child 49m avg, 7-day chart, top apps (Facebook 52m…). **Old-format sync (16 Jun)** → Part B cards absent (back-compat); needs a re-sync to validate B on device |
| Full-lock toggle ("Also block phone & messages") | **PASS** | switch flips subtitle "Phone & messages stay usable" → "**Full lock — only emergency calls work**" |
| Lock command delivery E2E | **PASS** | child log: `APPLYING LOCK_NOW arg=1` (arg=1 = full), gate passed; Firestore snapshot → decrypt → decode |
| Idempotency (lock + unlock) | **PASS** | duplicate snapshots `GATE rejected` on identical commandId |
| Honest lock overlay | **PASS** | "**Locked by parent** — A parent locked this device remotely. Ask them to unlock it." over home |
| Remote UNLOCK | **PASS** | `APPLYING UNLOCK` → "Remote UNLOCK applied; lock cleared"; child returns to a clean home |
| **Full-lock blocks Phone** | **FAIL → FIXED ✅** | initially the dialer stayed usable (overlay thrashed); after the fix the "Locked by parent" overlay **covers the dialer** and holds (re-verified on-device) |
| Emergency dialer still works | **NOT TESTED** | avoided live-dialing emergency services; platform emergency UI is above app overlays by design |

### 🐞 Bug found (P1 — full-lock doesn't hold over a real foreground app)
**Persistent remote lock thrashes over the dialer (and is fragile over real apps generally).** Logs show
`showOverlay CREATE pkg=com.google.android.dialer … wasActive=false prevLockTime=0` repeating every ~250ms;
the overlay is created and torn down each poll, so Phone stays usable and home re-lock is inconsistent.
- **Root cause:** the persistent re-assert (`AppLockForegroundService.kt:351`, `remoteLockActive && !isLockActive`)
  collides with `lockedAppExited` (`scanRecentEvents`, line 745). Showing the camera overlay over a *real*
  foregrounded activity (dialer) churns that app's UsageStats FG/BG transitions → `lockedAppExited` flips true
  every poll → line 322 `hideOverlay()` → re-assert recreates → thrash. The launcher emits no such FG/BG
  events, so the initial home lock looked stable.
- **Fix (implemented + verified this session):** (1) gated the line-322 `lockedAppExited` teardown on
  `!remoteLockActive` so the churn can't tear the overlay down during a remote lock; (2) replaced the
  show-only re-assert with a show/hide **manager** — `if (remoteLockActive)`: compute `shouldBlock =
  remoteLockFull || pkg∉{dialer,sms}`; show once when `shouldBlock && !isLockActive`, and (default lock only)
  hide to let Phone/SMS through when `!shouldBlock && isLockActive`. Built (128 tests green) + reinstalled →
  **"Locked by parent" now covers the dialer under full lock and holds**; remote UNLOCK still clears it.
- **Residual nit (minor, deferred):** ~8 overlay recreates / 5 s while parked on the dialer (down from ~20).
  The overlay stays visible throughout (functionally locked), but each recreate restarts the face camera —
  a battery/flicker cost. Likely `handlePackageChange` re-touching the foreground each poll; worth quieting
  later (e.g. skip recreate when `overlayView` is already attached for the same target), not a security hole.

### 🐞🔥 Bug found + fixed (P0 — phone FREEZES after a remote lock/unlock, needs a reboot)
User report: after a remote lock then unlock, the **child phone freezes** (unresponsive) until they reboot
it and relaunch the app.
- **Root cause:** the "Locked by parent" overlay ran the **front camera** (for a local parent-face unlock).
  Bound/unbound CameraX on every overlay recreate while the persistent lock held, and the FGS CAMERA-type
  teardown race (the one the code comments warn about) **SIG-9'd the process** — leaving the full-screen,
  touch-capturing overlay window stuck on screen with no owning process to remove it → frozen phone until
  reboot. `FaceLockOverlayContent` ran the camera regardless of `lockedByParent`.
- **Fix:** the remote parent-lock is cleared by the parent's **remote Unlock** (its own copy says "ask them
  to unlock it"), so it needs no face auth → make it **camera-free**: (1) gate the camera block on
  `!lockedByParent`; (2) show its message immediately (`finalShowWarning || lockedByParent`); (3) service
  shows the parent-lock overlay with `updateForegroundService(useCamera = false)` so it never enters
  CAMERA-FGS mode (no teardown race). **Trade-off:** the child overlay no longer offers a *local* parent-face
  unlock — clear it via the parent's phone (remote Unlock), which is the intended flow.
- **Verified on-device (Realme):** lock → "Locked by parent" shows instantly with **zero `bindToLifecycle`**;
  unlock → lock cleared, **process PID unchanged (19602, no SIG-9)**, no FATAL/ANR, and the phone is fully
  responsive (dialer opens normally, no stuck overlay). Build green (128 tests).
- **Local parent-face unlock re-added, camera-safe (built, pending device test):** the parent-lock overlay
  now shows an **"Unlock with parent's face"** button *only if a parent face is enrolled on the child*. Tap →
  one-shot, time-boxed (20s) scan: service enters CAMERA-FGS + sets `parentFaceScanActive` to **pause the
  monitor loop** so the capture isn't recreated mid-scan; match → unlock; cancel/timeout → overlay dropped so
  CameraX releases (lifecycle destroy) **before** the FGS downgrade, then the monitor re-shows it camera-free.
  No always-on bind → keeps the freeze fix intact. **Needs a face enrolled on the child to exercise** (same
  prerequisite as the cage), so it'll be tested alongside A1/A3.

### 🐞 Bug found (P1 — face won't unlock on first show; "recents+back" then works)
On the child, the cage's settings-lock `LockActivity` ("System Error" disguise) **didn't unlock by parent
face on first presentation** — only after switching to Recents and back. Logcat showed the front camera
**closing one session while another opens** (use cases DETACHED → new Preview/ImageAnalysis ACTIVE), all in
the Shield process: the app's three camera consumers (`AppFaceGate`, the service overlay, `LockActivity`)
**contend for the single front camera**, so CameraX can't deliver frames until the first fully closes.
Recents+back forces a fresh bind → works.

### 🔧 Fix (implemented + built, pending device re-test — phones dropped off ADB)
**Camera self-heal** in both `FaceLockOverlayContent` and `AppFaceGate`: an analyzer-frame timestamp +
watchdog — if no frame arrives within **3.5s** while the camera should be scanning, bump a `key()` counter
that recreates the camera `AndroidView` → a fresh CameraX bind (the automated "recents+back"). **Capped at 4
rebinds** so a genuinely-unavailable camera can never churn (protects the freeze fix). Reuses the existing
bind code unchanged; frame timestamp is a plain holder (no recomposition spam). Build green (128 tests).
**Needs on-device confirmation** that the first-show face unlock now self-heals without the manual workaround.

### Deferred (also found)
- Settings-lock `LockActivity` shows the **deceptive "System Error / Hardware module damaged"** disguise even
  in the parent-cage context (Play-policy deception risk); consider forcing an honest view for the cage.
- Settings-lock **didn't fire** when plain `android.settings.SETTINGS` was opened (stayed unlocked) — needs
  more digging (may be page/class-specific).
- A2 (re-gate weakening actions) not yet device-tested.

### Run 2 (cont.) — Part B + A2 verified, cleanup
| Check | Result |
|---|---|
| **Camera self-heal** (the fix) | **PASS** — `AppFaceGate: No camera frames in 3.5s — rebinding (self-heal #1)` fired, then the face unlocked on its own (no manual recents+back). Clean launches show no spurious rebinds. |
| **Part B richer report** E2E | **PASS** — child "Sync now" (uploaded ✓) → parent "Refresh" decrypted + rendered all 4 new cards: **4-week trend**, **By category**, **Time of day**, **Sessions (pickups) 2062 · longest 34m**. |
| **A2** re-gate weakening actions | **PASS** — Share-off popped the face gate (Cancel + challenge), share **stayed ON** (blocked without face); a matching face then completed it. Restored share to ON afterward. |
| `sessionStats` 2062/wk sanity | **OK (not a bug)** — counts every controlled-app foreground episode; correct, just heavy use. Label "(pickups)" is slightly imprecise (cosmetic). |
| settings-lock "didn't fire" for plain Settings | **By design** — OplusUI opened a `SubSettings` page, which `isSettingsSubPage()` exempts; critical side-doors (force-stop/app-info/Device-Admin) are covered by the separate `challengeCritical` path. |
| **Temporary `RemoteCmd` debug logging** | **REMOVED** — all 11 statements stripped from `RemoteCommandApplier` + `AppLockForegroundService` (control flow unchanged); build green, 128 tests. |

### Still open
- **Local "Unlock with parent's face" button** (tap-to-start) — built, not yet device-exercised (needs a remote lock + the on-screen button).
- **Deceptive "System Error / Hardware module damaged"** on the cage settings-lock `LockActivity` — deliberate disguise feature; making it honest for the cage is a **product decision** (deferred, awaiting your call).

### Env restore
Set `accelerometer_rotation` back to **1** on both devices after the session.

### Still not run (need human / camera)
Enrol parent face **on the child** → enable the cage (Lock-down toggle, A3 → 4/4), tap **Enable** for Device
Admin, A2 face-gate on weakening actions, emergency-dialer behaviour under full lock, Part B richer report
rendering after a fresh child re-sync.

---

## Run 3 — 2026-06-20 · Realme RMX3750 (child) + Motorola Edge 40 Neo (parent) · debug · ADB (wireless)

Focus: device-verify the new **child-device mode** (`ChildDeviceRoot` / `ChildDashboard` / parent face-gate).

| Check | Result |
|---|---|
| Child launches to **ChildDashboard** (role=`child`) | **PASS** — "This device is managed", time-left card ("Daily limit reached / 0m"), "Ask for more time", "Parent settings". Full management UI is hidden. |
| **Parent settings** → face gate | **PASS** — tapping it opens the one-shot scan ("Kids Shield is Locked / Look at the camera to unlock"), front camera (ID 1) binds; a matching parent face → full `MainScreen`. |
| Cancel returns to dashboard | **PASS** — Cancel button (sits high near the status bar; tap the lower half of its bounds) drops back to ChildDashboard. |
| Child lockdown of sensitive surfaces | **PASS (by construction)** — child only ever sees ChildDashboard; Face/Protect/Stats tabs + share/remote-control/unpair/lock-down toggles all live inside `MainScreen`, reachable only after a fresh parent face. |
| **Parent device unchanged** (regression) | **PASS** — Motorola (role=`parent`) launches to the normal `MainScreen`, no dashboard. |

### Bug found + fixed (this run)
- **Camera leak on gate dismiss (P1, freeze-bug class).** `AppFaceGate` (and `FaceEnrollmentScreen`) bound the front
  camera to the **Activity** lifecycle but never unbound it when the composable left composition. Repro: child
  dashboard → Parent settings → Cancel → **camera ID 1 stayed held by `com.appsecure.shield` indefinitely** (polled
  10 s+, always 1) on the plain dashboard — battery drain + risk of the camera/FGS freeze.
  - **Fix:** added a `DisposableEffect` `onDispose { cameraProviderRef[0]?.unbindAll() }` to both gates
    (`AppFaceGate` keyed on Unit, `FaceEnrollmentScreen` keyed on `isEnrolling`), capturing the provider in the
    AndroidView factory.
  - **Verified:** after Cancel → ChildDashboard, active shield camera clients = **0** and stays 0. Build green,
    128 unit tests pass.

### Bug found + fixed (P0 — "Kid Mode not blocking any apps")
- **Symptom:** child phone, Kid Mode on, budget exhausted (and during the night window) — **every app opened, nothing
  locked.**
- **Diagnosis (this Realme/ColorOS suppresses third-party logcat, so used a file-based heartbeat written from the
  monitor loop):** the monitor loop was alive, detected the app, and `shouldLockForKidMode` correctly returned **true**
  (`night=true nightUnlocked=true budgetUnlocked=true limit=59 used=188 over=true`) — but `lockActive` stayed **false**
  and the app stayed foreground. The decision was right; **enforcement never displayed.**
- **Root cause:** the Kid Mode budget/night lock used `enforceLock(forceActivity = kidModeLock=true)` →
  `launchLockActivity` → `startActivity(LockActivity)`. A **background Activity launch is blocked** on Android 12+/
  ColorOS once the ~10 s post-foreground grace window expires — so the lock silently never appeared. (Explains the
  "locks at t≈2 s after Shield is foregrounded, then stops" pattern.) The **remote-lock** path was already fixed to
  use the overlay for this exact reason; the **kid-mode path was missed.**
- **Fix:** route Kid Mode locks (single-kid budget/night + multi-kid identify) through the **overlay**
  (`forceActivity = false`), threading `isKidModeLock`/`identifyMode` through `showOverlay` → `FaceLockOverlayContent`.
  The overlay (`TYPE_APPLICATION_OVERLAY`, `SYSTEM_ALERT_WINDOW` granted) is not BAL-restricted, and the watchdog
  re-asserts it each poll if a gesture dismisses it. Settings still uses the full-screen `LockActivity`
  (overlay-hostile); overlay-revoked still falls back to the Activity.
- **Verified:** Shield backgrounded 18–20 s (grace gone) → open Ludo → overlay lock present + `lockActive=true`, holds.
  Build green, 128 tests pass. Ruled out as causes: entitlements (`promo_active=true`, kid features unlocked),
  process freeze (`freezer:/`, state S), app-standby/doze (bucket 5, in doze whitelist, RUN_ANY_IN_BACKGROUND allow).

### Still open from this thread (not yet done)
- **Budget/night lock still runs a passive face scan that unlocks on the enrolled face** — so it auto-dismisses when a
  parent looks at it, and is bypassable by whoever's face is the primary embedding. Ideal: honest "Time's up", no
  passive unlock; more time only via parent grant / 07:00 reset / deliberate parent-unlock button (mirror
  `ParentLockView`). **Design follow-up.**
- **Hide the Protect tab when `ownerType == "kid"`** + show a "what locks when the budget runs out" summary. **UX follow-up.**

### Acceptance-doc cases executed this run (child / Realme)
Back-annotated into `ACCEPTANCE_AND_TEST_CASES.md`:
- **TC-B-01** budget exhaustion locks — **PASS** (after the overlay fix above).
- **TC-A-06** Back/Home no bypass — **PASS** (Back ×2 holds overlay; Home → launcher; re-open re-locks).
- **TC-A-09** watchdog re-show — **PASS** (overlay re-armed on every re-entry).
- **TC-A-10** emergency dialer never gated — **PASS** (Phone opens over an exhausted budget).
- **TC-A-17** re-lock after leaving — **PARTIAL** (no-permanent-unlock confirmed; unlock-then-relock needs a live face).
- **TC-B-13** clock-forward cheat — **BLOCKED** (clock not settable without root; run manually).

### Couldn't run this session (environment)
- **Parent-device P0s** (TC-A-01 protected-app lock, TC-A-05 Settings→LockActivity, TC-1-03/F-01 promo-free, TC-2-01/K-01
  permission dashboard): the **Motorola dropped off wireless ADB repeatedly** and didn't come back. Need it reconnected
  (USB preferred for stability).
- **TC-F-02** (airplane/offline) and **TC-A-07** (reboot persistence): both would **kill the wireless-ADB link**, so not
  run over wireless — do via USB or manually.

### Bug found + fixed (P1) — default remote lock blocked Phone & Messages too
- **Symptom (user):** remote-lock the child with the **"Also block phone & messages" toggle OFF** → child is locked
  *totally*; can't use Phone or Messages either.
- **Root cause:** the persistent remote-lock overlay is **full-screen and touchable**, so even on a default lock the kid
  can't *navigate* to Phone/Messages, and `ParentLockView` offered **no affordance** to reach them. (The loop's
  `shouldBlock = remoteLockFull || !isPhoneOrSms` would let Phone/SMS through, but only if they're already foreground —
  unreachable behind the overlay.) Parent send-path was correct (`fullLock=false → arg=0`).
- **Fix:** on a **default** (non-full) remote lock, `ParentLockView` now shows **📞 Phone** and **💬 Messages** buttons that
  launch the resolved default dialer / SMS app (`openCommsApp()` in the service, BAL-exempt via SYSTEM_ALERT_WINDOW). The
  monitor loop then drops the overlay while they're foreground and re-asserts it on exit. Full lock hides the buttons.
- **Verified E2E (Moto parent → Realme child, Firestore):**
  - Default lock → child shows "Locked by parent" + **Phone + Messages** buttons (screenshot `.adb_shots/child_lock`).
  - Tap **Phone** → dialer opens, overlay drops; **HOME** → overlay re-asserts; tap **Messages** → Google Messages opens.
  - **Full lock** (toggle ON) → **no** Phone/Messages buttons, only "Unlock with parent's face" (screenshot
    `.adb_shots/child_fulllock`).
  - Remote **UNLOCK** from parent cleared the overlay. Build green, 128 tests pass.

### Feature added — remote Kid Mode config (Phase 1)
Parent can now push Kid Mode settings to the child from their own phone (extends the existing remote-command
channel). All **additive** — existing commands untouched.
- New `CommandType`s **`SET_ALLOWED_PRESET`** (arg 0/1) and **`SET_AUTO_BLOCK`** (arg 0/1), appended (codec is
  name-based, so an older child decodes an unknown name → null → ignores it). Daily budget already shipped
  (`SET_DAILY_LIMIT`).
- `RemoteCommandApplier` applies them straight to DataStore (`setAlwaysAllowedPreset` / `setAutoBlockNewApps`) —
  the same setters the on-device Kid Mode UI uses; the child's UI + enforcement observe the flows.
- Parent UI: a **"Kid Mode settings"** block in the Remote-controls card (Allowed-apps preset: Phone+Messages /
  +WhatsApp; Auto-block new apps: On/Off). Custom app picks + per-app limits need the child's app list → Tier 2.
- **Verified:** build green; 128 unit tests pass incl. the codec round-trip (now auto-covers the 2 new types).
  DataStore decoder (`tools/decode_prefs.py`) reads the child baseline: `always_allowed_preset=0`, `owner_type=kid`.
- **NOT yet device-verified E2E** (parent→child apply): both phones dropped off wireless ADB before the cross-device
  run. Low integration risk (same channel as the verified remote-lock E2E; same setters as the local UI), but the
  parent→child apply still needs a live two-device pass.
- **Known Phase-1 limits:** one setting per tap (relay is a single overwritten doc — rapid changes can coalesce);
  a config command older than 6 h is dropped (re-send if the child was long-offline); the parent edits without
  seeing the child's current values (config sync child→parent is the next phase).

### Feature added — config sync child→parent (Phase 2)
So the parent edits *real* values, not blind. Reuses the report pipeline (no new Firestore doc/rules).
- Appended **two optional fields** to `KidReport` — `allowedPreset` and `autoBlockNewApps` (`-1` = not reported).
  The codec is positional + tolerant (`getOrNull` + default), so old docs decode with `-1` and new docs round-trip
  fully — same back-compat pattern as the B1 fields. (Daily limit was already in the report.)
- Child gatherer fills them from DataStore (single-kid branch; multi-kid left `-1` since remote config is global).
- Parent UI: each remote Kid Mode control now shows **"Currently: …"** and **highlights the active option** (filled
  vs outlined button) from the last decrypted report.
- **Verified:** build green; **130 unit tests pass** incl. 2 new codec tests (config round-trip + old-doc → `-1`).
- **NOT yet device-verified E2E** (child report carries config → parent displays it): parent (Motorola) still off
  wireless ADB. Child has the latest build.
- **Known limit:** the parent's "Currently" display lags by one report sync — after pushing a change, the parent
  must wait for the child to re-sync + tap Refresh to see the new value reflected (the "Command sent ✓" confirms the
  push immediately). Auto-resync-on-config-change is a later refinement.

### Two-device E2E results (Moto parent tid47 → Realme child tid48)
1. **Phase 1 — PASS (bidirectional).** Baseline child `always_allowed_preset=0`, auto-block unset. Parent tapped
   **+ WhatsApp** + **Turn on** → child prefs flipped to `always_allowed_preset=1`, `auto_block_new_apps=true`
   (`tools/decode_prefs.py`). Then parent tapped **Phone + Messages** + **Turn off** → child reverted to `0` /
   `false`. The remote Kid Mode config push works end to end.
2. **Phase 2 — PARTIAL.** The parent's read/display path is confirmed live — the Remote-controls card renders
   **"Currently 60 min"** + **"Currently: —"** (the "—" proves an old pre-Phase-2 report decodes the new fields to
   `-1`). NOT fully closed: getting a **fresh** report (new build + current config) needs the child's **"Sync now"**,
   which is behind the parent face-gate, and the periodic worker (weekly) couldn't be force-run from ADB
   (`cmd jobscheduler run` consumed/!found the job). So the parent still shows the stale report. To finish: tap
   **Sync now** on the child once → parent Refresh → expect `Currently 240 min` etc. Codec/back-compat for these
   fields is unit-tested (2 tests), and the display path is proven, so residual risk is low.

Child config left at **baseline** (preset 0, auto-block off) after the test.

### Feature added — auto-sync (child) + auto-refresh (parent), on top of on-demand
- **Parent auto-refresh:** `RemoteReportRepository.listen()` (Firestore snapshot listener, mirrors the command
  listener); `ParentReportViewModel` attaches it in `init` (fires on attach + every change → decrypt → state),
  detaches in `onCleared`. Manual "Check again" (`refresh()`) kept.
- **Child auto-sync:** (1) after applying a remote command the service calls `remoteReportSync.syncNow()` so the
  parent's view updates at once; (2) a rate-limited (~30 min) background upload piggybacked on the screen-time
  poll keeps usage fresh while the service runs. `syncNow()` stays gated (child + share-on + paired). Manual
  "Sync now" kept; periodic worker kept.
- **Verified E2E live (Moto parent ↔ Realme child), no manual taps:** parent on the report screen showed real
  config ("Currently 240 min", preset, auto-block) via the listener — replacing the stale "60 / —". Pushed
  **Phone + Messages** → within ~3 s the parent's "Currently" flipped from **+WhatsApp → Phone + Messages** with
  **no Refresh tap** (child after-command auto-sync → parent listener). Child DataStore decoded to `preset=0,
  auto_block=false, limit=240`, matching the display. This also closes the previously-pending **Phase 2 E2E**.
  Build green; **130 unit tests pass**. Child left at baseline.

### Feature added — Tier-2: remote custom allowed apps + per-app limits (user okayed the privacy trade)
The child's installed-app **list** (package + label only) now rides the encrypted report so the parent can pick
specific apps remotely. All additive.
- **Transport:** `RemoteCommand` gained an optional `payload` string; the codec appends it as a 5th field ONLY
  when present (int-only commands stay 4-field → an older child still decodes them; decode accepts 4 or 5). New
  `CommandType`s **`SET_CUSTOM_ALLOWED`** (payload = comma-joined packages → sets custom set + preset=Custom) and
  **`SET_PER_APP_LIMIT`** (payload = package, arg = minutes, 0 clears).
- **Report:** `KidReport` gained `installedApps` (AppEntry pkg+label), `customAllowed`, `perAppLimits` — all
  optional trailing fields (old docs → empty). Gatherer fills them (PackageManager query, same filter as the
  on-device list).
- **Parent UI:** a **"Manage child's apps…"** dialog (searchable list; checkbox = always-allowed, applied as a set
  on Save; tap a per-app limit to cycle None→15→30→60). Disabled until a report with the app list has synced.
- **Privacy:** only package + label leave the child (E2E-encrypted) — no usage tied to them beyond the existing
  top-apps. User explicitly opted in.
- **Verified:** build green; **134 unit tests pass** incl. new ones (command payload round-trip + 4-field
  back-compat; Tier-2 report fields round-trip + old-doc → empty).
- **NOT yet device-verified E2E:** both phones dropped off wireless ADB before the run. Pending: child syncs (app
  list appears) → parent opens picker → allow an app + set a per-app cap → decode child prefs shows
  `custom_always_allowed` contains it, `always_allowed_preset=2`, per-app-limit set. Residual risk low (same
  command channel + report pipeline already verified; codec unit-tested).

### Tier-2 follow-ups — consent on enable + re-upload on app change
- **Consent dialog:** turning **Share** on now pops a dialog first ("…uploads screen-time totals AND the list of
  app names… E2E-encrypted… refreshes when apps change… no messages/content/location") — Turn on / Cancel. Sharing
  only enables on confirm. Share subtitle updated to "encrypted totals + app names sync automatically".
- **Re-upload on app install/uninstall:** the package receiver now also listens for `PACKAGE_REMOVED` and triggers
  a report upload on any add/remove (gated → no-op off-share), so the parent's app picker stays current; the
  parent's live listener auto-refreshes it. **Debounced 10 s** (`scheduleAppChangeSync`) so a burst (restore / bulk
  update) collapses to a single upload instead of one-per-app.
- **Verified:** build green; 134 unit tests pass. Device E2E still pending (phones off wireless ADB).

### Tier-2 device E2E — PASS (Moto parent tid74 ↔ Realme RMX5313 child tid69, paired dcd9eb9c…)
Child was set up by the user (paired, Kid Mode on, sharing on — so the **consent popup was seen + accepted** during
that setup). Then, driven over ADB:
- Parent **Remote report → "Manage child's apps…"** opened and listed the child's **synced app list** (Amazon,
  Android Auto, …) — proves the child→parent app-list sync over the encrypted report.
- Allowed **Amazon** + set a **15 min** per-app cap → Save. Decoding the child's DataStore confirmed the push:
  `always_allowed_preset=2`, `custom_always_allowed=['in.amazon.mShop.android.shopping']`,
  `per_app_limits='in.amazon.mShop.android.shopping:15'`.
- **Restore (also re-proves bidirectional):** reset preset → `always_allowed_preset=0`; cleared the cap →
  `per_app_limits=''`. Child left functionally at baseline.
- **Not exercised on-device:** the install/uninstall→re-upload trigger (no spare APK to install; uninstalling the
  user's apps is destructive). Mechanism is built (PACKAGE_ADDED/REMOVED → debounced syncNow) and the underlying
  sync+listener path is the same one verified here, so residual risk is low.
- MIUI note: the Redmi child couldn't be used — MIUI blocks ADB installs (`INSTALL_FAILED_USER_RESTRICTED`) until
  "Install via USB" is enabled (needs Mi account + SIM).

### Still not run (need human / camera)
Same as Run 2: cage enable on child, Device-Admin Enable, A2 weakening-action gate, emergency dialer under full
lock; plus the real child→parent "Ask for more time" request channel (still informational — deferred follow-up).
