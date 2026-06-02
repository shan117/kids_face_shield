# Kid Mode + Screen Time — Implementation Status

Companion to `KID_MODE_FEATURE_PLAN.md`. Update this file every time a phase
is started, finished, or rolled back.

---

## Phase tracker

Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `[!]` reverted/blocked

| Phase | Description | Status | Notes |
|---|---|---|---|
| 1 | DataStore keys + flows + setters (`owner_type`, `daily_limit_minutes`, `always_allowed_preset`, `custom_always_allowed`, `screen_time_used_ms`, `extensions_today_ms`, `screen_time_last_reset_date`, `extension_history`) | `[x]` | Build SUCCESSFUL. No behavior change yet. |
| 2 | Screen Time tab skeleton (owner radio, daily limit picker, preset selector) | `[x]` | Build SUCCESSFUL. New "Kid Mode" tab visible; no enforcement. |
| 3 | Custom-app picker + runtime resolution of default phone & SMS | `[x]` | Build SUCCESSFUL. Presets A/B now show resolved app names; preset C shows app picker. |
| 4 | `UsageStatsManager` polling, 07:00 reset logic, today's-usage card | `[x]` | Build SUCCESSFUL. Service polls every 60s; card shows live used/limit + progress bar. |
| 5 | Enforcement in `AppLockForegroundService` (day window + night window + budget) | `[x]` | Build SUCCESSFUL. `shouldLockForKidMode` wired into both `handlePackageChange` and `runWatchdog`. |
| 6 | Dedicated kid-mode lock message (no extension buttons, per user decision) | `[x]` | `KidModeLockView` shown when kid-mode lock fires: "Your daily usage limit is over. Please engage yourself in other activities. More screen time can harm your eyes, brain, and reduce your concentration power." |
| 7 *(later)* | Dashboard tab — 7-day usage + extension history | `[ ]` | Deferred |

---

## Phase 1 — DataStore additions

**Files touched:** `app/src/main/java/com/shantanu/shield/data/DataStoreManager.kt`

**Build status:** _(updated after each build)_
- Last build: SUCCESSFUL (`gradlew :app:assembleDebug --offline`)

**What gets added:**
- 8 new preference keys
- 8 new exposed `Flow<T>` properties
- 8 new suspend setters
- New import for `longPreferencesKey`

**Behavior change:** None. Data layer only — UI doesn't reference these
fields yet. Safe to ship even if Phase 2 is delayed.

---

## Phase-by-phase change log

### Phase 1 — _done_
- Added 8 new preference keys, 8 `Flow<T>` properties, 8 suspend setters (plus
  `appendExtensionHistory` for the future dashboard).
- New import: `androidx.datastore.preferences.core.longPreferencesKey`.
- File touched: `app/src/main/java/com/shantanu/shield/data/DataStoreManager.kt`.
- Build: SUCCESSFUL. No runtime behavior change yet (no consumer of the new
  fields exists outside of `DataStoreManager`).

### Phase 2 — _done_
- Added new bottom-nav tab **"Kid Mode"** at index 3 (existing Setup tab moved to index 4).
- Created `KidModeScreen(viewModel)` composable with:
  - Owner Type radio (Parent / Kid), changes immediately persisted.
  - When Kid: daily-limit slider (15–240 min, step 15), preset selector A/B/C,
    today's-usage placeholder card.
- Added flow exposures & setters to `MainViewModel`: `ownerType`,
  `dailyLimitMinutes`, `alwaysAllowedPreset`, `customAlwaysAllowed`,
  `screenTimeUsedMs`, `extensionsTodayMs`, plus matching setters.
- Files touched: `MainActivity.kt`, `MainViewModel.kt`.
- New import: `androidx.compose.foundation.clickable`.
- Build: SUCCESSFUL. No runtime enforcement yet.
### Phase 3 — _done_
- New file: `app/src/main/java/com/shantanu/shield/util/AllowedApps.kt`
  - `resolveDefaultPhonePackage(context)` — via `Intent.ACTION_DIAL` resolution
  - `resolveDefaultSmsPackage(context)` — via `Telephony.Sms.getDefaultSmsPackage`
  - `computeAlwaysAllowedSet(context, preset, customAllowed)` — the canonical
    set the service will consult in Phase 5. Always includes our own package.
  - Suspending overload `computeAlwaysAllowedSet(context, dataStore)` for
    convenience inside the service.
  - `labelFor(context, packageName)` — human-readable label, falls back to pkg name.
- `MainViewModel`: exposed `installedApps: StateFlow<List<AppInfo>>`.
- `KidModeScreen`:
  - Presets A & B subtitles now show the actual resolved names ("Allowed:
    Google Phone, Google Messages").
  - Preset C now reveals a Custom-app picker card with one row per
    installed Play-Store app. Each row uses the same Switch / Surface style as
    `AppShieldItem` for consistency.
- Build: SUCCESSFUL. No runtime enforcement yet — still UI only.
### Phase 4 — _done_
- `AppLockForegroundService`:
  - New job `screenTimePollJob` started in `onCreate` via `startScreenTimePolling()`.
  - Polls every 60s (`SCREEN_TIME_POLL_INTERVAL_MS = 60_000L`).
  - Per-poll:
    1. Computes `dayKeyForBudget(now)` (07:00 boundary). If different from
       the persisted `screen_time_last_reset_date`, zeros
       `extensions_today_ms` and stamps the new key.
    2. Calls `usm.queryAndAggregateUsageStats(mostRecentSevenAm(now), now)`.
    3. For every package: skips it if it's in `AllowedApps.computeAlwaysAllowedSet`,
       is our own package, or is a non-updated system app
       (`FLAG_SYSTEM && !FLAG_UPDATED_SYSTEM_APP`).
    4. Sums `totalTimeInForeground` for the remaining packages and writes to
       `dataStore.setScreenTimeUsedMs(controlledMs)`.
  - Cleanup: `screenTimePollJob?.cancel()` added to `onDestroy`.
- `KidModeScreen` Today card upgraded:
  - Shows `used / effectiveLimit min used` (red when over).
  - `LinearProgressIndicator` with `progress = used / effectiveLimit`.
  - Subtitle: `N min remaining  •  +X min extensions` (extensions only if >0).
  - Tagline: "Updates every minute. Resets at 07:00 each day."
- Files touched: `service/AppLockForegroundService.kt`, `MainActivity.kt`.
- New imports: `android.content.pm.ApplicationInfo` in the service.
- Build: SUCCESSFUL. Still no enforcement — Phase 5.

### Phase 5 — _done_  (+ overlay-bypass fix)
**Bypass hardening (after initial Phase 5):**
- The face-lock **overlay** can be partially bypassed on some OEMs — Home
  → tap the app again in recents gives a ~250 ms window where the app is
  interactive before the watchdog re-shows the overlay. The overlay also
  lets system gestures pass through, so the kid can swipe through it.
- Fix: kid-mode locks now route through **LockActivity** (the same
  full-screen activity that Settings sub-pages use). LockActivity:
  - Is a real Android Activity → fully blocks the app underneath.
  - Back-press → sends the user Home, doesn't fall back into the app.
  - Home-press → `onStop` reports failure → service re-locks on next entry.
  - `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NO_ANIMATION`
    means no animation gap to exploit.
- `enforceLock(pkg, eventTime, forceActivity = false)` — new param. True
  routes through `launchLockActivity`. Threaded through both call sites
  (`handlePackageChange` and `runWatchdog`).
- Parent-mode protected-apps still use the overlay (no behavior change).

- New helpers in `AppLockForegroundService`:
  - `shouldLockForKidMode(pkg, nowMs)` — fast-fails when owner is Parent;
    rejects system apps, self, and always-allowed apps; returns true if
    inside the 22:00-06:59 night window OR `usedMin >= dailyLimit + extensionMin`.
  - `isNightWindow(nowMs)` — `hour >= 22 || hour < 7`.
- Hook points:
  - `handlePackageChange()` — added `kidModeLock` to the `shouldLock` when-chain.
  - `runWatchdog()` (250 ms cadence) — also consults `shouldLockForKidMode`,
    so a kid who is already inside YouTube when the 60-second poll tips
    `usedMin` past the limit gets locked within ≤ 250 ms.
- Re-uses the existing face-lock overlay & 60-second re-auth grace, so Parent
  can face-auth to grant a short emergency window. Phase 6 will add explicit
  +15 / +30 / +60 min extension buttons to that overlay so face-auth grants
  *budget*, not just a 60 s bypass.
- Files touched: `service/AppLockForegroundService.kt`.
- Build: SUCCESSFUL. Enforcement is now LIVE.

### Phase 6 — _scoped down (kid-mode message only, no extension buttons)_
- User decided extension buttons add too much surface area and that a hard limit
  is preferable. Instead, kid-mode locks show a dedicated `KidModeLockView`
  composable with the message:
  > "Your daily usage limit is over"
  > "Please engage yourself in other activities."
  > "More screen time can harm your eyes, brain, and reduce your concentration power."
- The existing 3 message types (HardwareError / HealthWarning / KidSafeAlert) are
  no longer shown when the kid-mode lock fires; they're reserved for parent-mode
  protected-app locks. TTS is also skipped on kid-mode locks (the new message
  is English).
- Parent can still face-auth for the standard 60 s grace. Budget cannot be
  extended from the lock screen.
- `extensions_today_ms` / `extension_history` keys remain in `DataStoreManager`
  for any future use, but nothing currently writes to them. The daily 07:00
  reset still zeros `extensions_today_ms` (Phase 4 logic, unchanged).
- Files touched: `FaceLockOverlayContent.kt` (new `KidModeLockView`,
  `isKidModeLock` param), `LockActivity.kt` (new `EXTRA_KID_MODE_LOCK`),
  `AppLockForegroundService.kt` (thread `isKidModeLock` through
  `enforceLock` → `launchLockActivity`).
- Build: SUCCESSFUL.
### Phase 7 — _not started_

---

## How to resume in a future session

1. Read `KID_MODE_FEATURE_PLAN.md` for the rules.
2. Read this file's **Phase tracker** to find the next unchecked phase.
3. Implement that phase **only**. Build & ask the user to verify before
   moving to the next phase.
4. Mark the phase done in the tracker and append an entry under the
   matching heading in the **Phase-by-phase change log**.
