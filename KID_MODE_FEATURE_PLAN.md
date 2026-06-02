# Kid Mode + Screen Time — Feature Plan

This document is the source of truth for the Kid Mode / Screen Time feature.
Update it whenever rules change. Implementation progress is tracked in
`KID_MODE_IMPLEMENTATION_STATUS.md`.

---

## 1. Goal

Let a parent flag a device as "primarily used by my kid", set a **daily screen
time budget**, define which apps are **always allowed** (phone, messaging, etc.),
and ensure that when the budget is exhausted — or during a **fixed night-time
window** — any non-allowed app requires a parent's face to open. The parent can
**extend** the budget on demand.

Long term: collect daily usage and extension data for a dashboard.

---

## 2. Concepts

### Owner Type
| Value | Meaning |
|---|---|
| `parent` (default) | No screen-time enforcement. Existing per-app face-lock works as before. |
| `kid` | Daily budget + night-window enforcement applies (see below). |

Stored as a normal setting — **no forced first-run dialog**. New installs default
to `parent`, so behavior is unchanged unless the parent opts in.

### Always-allowed apps (the "white list")
Apps the kid can use **without face auth** at any time of day, regardless of the
budget. Selected via one of three presets:

| Preset | Always allowed |
|---|---|
| **A** (default) | Default phone + default SMS app |
| **B** | Default phone + default SMS + WhatsApp (`com.whatsapp`) |
| **C** | Custom — parent picks from a list of installed **Play-Store apps only** (no system apps shown). The list initially has every Play-Store app marked as "controlled"; un-checking an app moves it to "always allowed". |

Default phone & SMS package names are resolved at runtime via
`Intent.ACTION_DIAL` and `Intent.ACTION_SENDTO` resolution — works on any OEM.
Our own package is implicitly always allowed (so the parent can always open the
app to extend time).

### Day / Night windows
| Window | Time (device local time) | Behavior in Kid mode |
|---|---|---|
| Day window | **07:00 – 21:59** | Budget applies. Below budget: free use of all Play-Store apps. At/over budget: face auth required for all non-allowed Play-Store apps. |
| Night window | **22:00 – 06:59** | Hard lock. All non-allowed Play-Store apps require face auth, regardless of budget. |
| Budget reset | At **07:00** every day | `usedMsToday` and `extensionsTodayMs` reset to 0. `lastResetDate` updates. |

(System apps — Settings, Camera, Calculator, Clock, the launcher, our own app, etc.
— are **always** freely usable in Kid mode.)

### Parent override
- Parent encounters the lock overlay, authenticates with face.
- Inside the unlocked app a **floating "+15 min" / "+30 min" / "+60 min" button**
  is shown (handled by our overlay system). Tapping it requires face re-auth
  and adds the chosen number of minutes to the budget for today.
- **No "Reset to 0" button.** Extension is additive only — easier to audit.
- Every extension is recorded with timestamp + duration for the future dashboard.

---

## 3. Data model — additions to `DataStoreManager`

| Key | Type | Default | Purpose |
|---|---|---|---|
| `owner_type` | String | `"parent"` | `"parent"` or `"kid"` |
| `daily_limit_minutes` | Int | `60` | Daily screen-time budget |
| `always_allowed_preset` | Int | `0` | 0=A, 1=B, 2=C |
| `custom_always_allowed` | StringSet | `emptySet()` | Used only when preset == 2 |
| `screen_time_used_ms` | Long | `0` | Cumulative ms on controlled apps today |
| `extensions_today_ms` | Long | `0` | Cumulative ms granted via parent extensions today |
| `screen_time_last_reset_date` | String | `""` | `"YYYY-MM-DD"`, last 07:00 reset day |
| `extension_history` | String | `""` | CSV log: `epochMs,minutes;epochMs,minutes;…` (for dashboard) |

All exposed as `Flow<T>` + a suspend setter, matching the existing style in
`DataStoreManager`.

---

## 4. Time-tracking algorithm

We use **`UsageStatsManager`** (already granted) rather than our own counter:
- It's the source of truth; survives crashes/reboots/force-stop.
- We just query "today's total foreground time per package" once per minute.

Pseudocode (runs in `AppLockForegroundService`, on a 60 s timer):
```
val nowMs = System.currentTimeMillis()
val windowStartMs = mostRecentSevenAM(nowMs)    // 07:00 today, or 07:00 yesterday if it's before 07:00 now
val stats = usageStatsManager.queryAndAggregateUsageStats(windowStartMs, nowMs)
val controlledMs = stats
    .filterKeys { pkg ->
        pkg != selfPackage &&
        !alwaysAllowedSet.contains(pkg) &&
        isPlayStoreInstalledOrUserApp(pkg)        // excludes system apps
    }
    .values.sumOf { it.totalTimeInForeground }
dataStore.setUsedMsToday(controlledMs)
```
- The set `alwaysAllowedSet` is cached and only re-computed when the preset
  / custom list changes.
- `isPlayStoreInstalledOrUserApp` = `(ApplicationInfo.flags & FLAG_SYSTEM) == 0`
  OR `(ApplicationInfo.flags & FLAG_UPDATED_SYSTEM_APP) != 0` (covers e.g.
  Chrome that's pre-installed but updated from the Play Store).

**Reset logic** (checked on each poll): if `now.toLocalDate()` and the current
clock is past 07:00, and `lastResetDate != today's date`, then:
- `usedMsToday = 0`
- `extensionsTodayMs = 0`
- `lastResetDate = today's date`

(We also push yesterday's usage into the dashboard history at reset time — a
later phase.)

### Mid-session limit hit
The kid is using a controlled app and crosses the limit at minute 61:
- Next 60-second poll detects `usedMsToday >= limit`.
- The service inspects the current foreground package. If it's a controlled
  Play-Store app, the service fires `enforceLock(currentPkg, reason = SCREEN_TIME_OVER)`
  immediately (without waiting for a package change).
- A system notification "Screen time over — tap to ask parent" is posted.

---

## 5. Enforcement (in `AppLockForegroundService.handlePackageChange`)

Pseudocode (new logic inserted **above** the existing per-app protection check):
```
val ownerKid = dataStore.ownerType.first() == "kid"
if (ownerKid) {
    val nowMs = System.currentTimeMillis()
    val inNightWindow = isNightWindow(nowMs)                 // 22:00 – 06:59
    val budgetOver  = dataStore.usedMsToday.first() >=
                      (dataStore.dailyLimitMinutes.first() + extensionMinutes.first()) * 60_000L
    val needsAuth   = (inNightWindow || budgetOver) &&
                      pkg !in alwaysAllowedSet &&
                      !isSystemPackage(pkg) &&
                      pkg != selfPackage
    if (needsAuth) {
        enforceLock(pkg, eventTime, reason = SCREEN_TIME_OVER)
        return
    }
    // Within budget AND day window → kid mode does NOT enforce.
    // (Existing per-app protections are deliberately suppressed in kid mode.)
    return
}
// Parent mode: existing protected-apps logic continues unchanged.
```

`reason` parameter is just an enum that picks which message the overlay shows
("Screen time over. Ask a parent." vs. the existing health/hardware warnings).

---

## 6. UI

### New tab in `MainScreen`: "Screen Time"
Contents (top → bottom):
1. **Owner Type**: radio: `Parent` / `Kid`. Tapping requires face auth.
2. **Daily limit**: slider / picker for minutes (15 to 240, step 15). Disabled unless `Kid`.
3. **Always-allowed preset**: radio A / B / C. Disabled unless `Kid`.
4. **Custom app list** (only when preset == C): scrollable list of installed
   Play-Store apps. Initially everything is marked "controlled"; checkbox un-tick
   moves an app to "always allowed".
5. **Today's usage card** (read-only): `used / (limit + extensions)` in min.
6. **Reset window info** (read-only): "Resets at 07:00 every day. Night lock 22:00–07:00."
7. **(Later) Dashboard link**: history of last 7 days usage + extensions.

All write actions (toggle owner, change limit, change preset, edit custom list)
sit behind face auth so the kid cannot quietly bump the limit themselves.

### Lock overlay copy
When the lock fires for `reason = SCREEN_TIME_OVER`, the existing
`FaceLockOverlayContent` shows a different message:
> **"Screen time is over."**
> **"Please ask a parent to unlock."**

A small bottom-of-overlay row "**+15 min   +30 min   +60 min**" is rendered.
Tapping any of those re-prompts the camera, and on a successful face match
the chosen minutes are added to `extensionsTodayMs` and the overlay closes,
letting the kid continue.

### First-launch behavior
No forced dialog. The Screen Time tab simply exists and defaults to Parent mode.

---

## 7. Build phases (each compiles + is independently testable)

| Phase | Scope | Visible effect |
|---|---|---|
| **1** | DataStore keys + flows + setters for the new fields | Nothing yet; data layer only |
| **2** | "Screen Time" tab skeleton (owner radio, limit picker, preset selector) | UI exists, can configure; nothing enforces |
| **3** | Custom-app picker (preset C); resolve default phone & SMS for presets A/B | Custom list works; alwaysAllowedSet computed |
| **4** | `UsageStatsManager` polling → `usedMsToday`; reset at 07:00 logic; today's-usage card | Today's usage updates live |
| **5** | Enforcement in `AppLockForegroundService` (day window + night window + budget) | Lock kicks in when needed |
| **6** | `+15 / +30 / +60 min` extension buttons on the lock overlay; persist to `extensionsTodayMs` & `extension_history` | Parent can extend; history accumulates |
| **7** *(later)* | Dashboard tab — last 7 days usage chart + extensions table | Read-only visualisation |

We build, install, and test after each phase. If anything regresses, that phase
is reverted in isolation. Same protocol as the threshold/eyes/blink work.

---

## 8. Edge cases & guardrails

- **Self-protection**: our own package is always always-allowed, so the parent
  can always open the app to manage the budget.
- **Service crashed mid-day**: no data is lost — `usedMsToday` is re-derived
  from `UsageStatsManager` on the next poll.
- **Clock change / DST**: handled correctly because `Calendar` is queried at
  every poll; we never trust a single anchor timestamp.
- **Reboot in the middle of the day**: `lastResetDate` survives in DataStore, so
  no double-reset; on first poll after reboot the cumulative time picks up where
  it left off.
- **Toggling Parent → Kid mid-day**: budget starts counting from "now" against
  the existing day's total — this is fine because UsageStatsManager already
  reports the full day's usage.
- **Toggling Kid → Parent**: all enforcement is bypassed instantly.
