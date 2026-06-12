# Premium / Multi-Kid / Monetization — Implementation Status

Companion to `PREMIUM_FEATURE_PLAN.md`. **Update this file every time a phase is started,
finished, or rolled back.**

---

## Phase tracker
Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `[!]` reverted/blocked

| Phase | Description | Status | Notes |
|---|---|---|---|
| 0 | *(Owner)* Firebase project (analytics-free) + Remote Config; Play Console app, Data Safety, Families, subscription + discount + 30-day trial + price tiers + license testers | `[ ]` | Owner task. Blocks live Remote Config + real purchases. |
| 1 | Entitlement + feature-flag core; then billing-ktx + firebase-config deps; `BillingManager` dormant; cache keys | `[~]` | Pure-Kotlin core landed first (builds offline). Billing/Firebase deps pending (need network + `google-services.json`). |
| 2 | Route premium-candidate features through `isUnlocked(...)` | `[ ]` | |
| 3 | Early-access screen + Plus badge (paywall mode wired) | `[~]` | Screen + Settings "Plus" entry live; promo mode = "Continue — it's free"; paywall mode renders but purchase CTA is **dormant** until Billing (Phase 1b). |
| 4 | Multi-kid foundation: `KidProfile` + storage + migration; 2-kid enroll; 1:N identify+margin; `ProfileSession` | `[x]` | **Done:** 4a data + 4b-1 identify engine + 4b-2 setup UI (toggle, 2 profile cards, face enrolment, migration). Enforcement is Phase 5. |
| 5 | Multi-kid enforcement (hybrid model) in service | `[~]` | **Code-complete** (5a kernel + 5b-svc + 5b-cam identify camera), all gated behind `multi_kid_enabled`, builds clean. **⚠ DEVICE-VALIDATION PENDING** — camera/timing, can't be proven from a compile. |
| 6 | Multi-kid dashboard (selector + family overview + drill-down) | `[x]` | Additive: shown only when `multi_kid_enabled`. Family/kid selector, per-kid used/limit + budget ring + top apps (per-profile sessions). Builds clean; per-kid numbers depend on 5b-cam attribution → device-validate. |
| 7 | Selected extra premium features (each flagged) | `[x]` | **Done:** Earn screen-time, Schedules, New-app auto-block, Per-app limits, Themes — all gated + flagged. (multi-parent faces = explained, not requested.) |
| 8 | Billing robustness + compliance | `[ ]` | Pre-charge. |
| 9 | *(Owner, no app update)* Set prices; flip `feature_tiers`, `promo_active=false`, `paywall_enabled=true`; optional FCM + early-user reward | `[ ]` | The "go paid" switch. |
| 10 | *(Future)* Server-side verification; Device-Owner Pro; backend/sync | `[ ]` | Out of scope now. |

---

## Phase-by-phase change log

### Phase 1 — _in progress_
**Goal:** the configurable entitlement layer so every premium-candidate feature is gated by a
single `isUnlocked(...)`, with the source swappable (local defaults now → Remote Config + Billing later).

**Step 1a (done) — pure-Kotlin core (no new dependencies, builds offline):**
- `premium/Feature.kt` — `Feature` enum, `Tier`, `CORE_ALWAYS_FREE` + `DEFAULT_PREMIUM` sets.
- `premium/PaywallConfig.kt` — runtime config snapshot + `DEFAULT` (promo on).
- `premium/Entitlements.kt` — pure `isUnlocked(feature, config, isPremium)`.
- `premium/RemoteConfigSource.kt` — interface + `LocalDefaultsConfigSource` (emits `PaywallConfig.DEFAULT`).
- `premium/PremiumSource.kt` — interface + `DormantPremiumSource` (emits `isPremium = false`).
- `premium/EntitlementRepository.kt` — `isPremium`, `config`, `isUnlocked(Feature): Flow<Boolean>`.
- `di/PremiumModule.kt` — binds the two interfaces to the local/dormant impls.
- `data/DataStoreManager.kt` — `cached_is_premium` + `cached_plan` keys/flows/setters.
- Unit test: `premium/EntitlementsTest.kt` — the `isUnlocked` truth table.
- **No behavior change:** `promo_active = true` default → everything unlocked.

**Step 1b (pending) — dependency-backed sources** *(needs network for deps + `google-services.json` from Phase 0):*
- Add `com.android.billingclient:billing-ktx` → real `BillingManager` implementing `PremiumSource`.
- Add `firebase-config` (analytics-free) → `FirebaseRemoteConfigSource` implementing `RemoteConfigSource`.
- Swap the two `@Provides` in `di/PremiumModule.kt` from local/dormant to the real impls.

**Build:** Step 1a — `gradlew :app:assembleDebug :app:testDebugUnitTest --offline` **SUCCESSFUL**;
`EntitlementsTest` **7/7 pass**; Hilt graph valid (PremiumModule bindings resolve). No behavior
change — `promo_active=true` default keeps the app fully free, billing dormant.

---

### Phase 3 — _in progress_  (early-access screen)
- `ui/paywall/PaywallViewModel.kt` — exposes the runtime `PaywallConfig` from `EntitlementRepository`.
- `ui/paywall/PaywallScreen.kt`:
  - `PaywallScreen(onClose)` + stateless `PaywallContent` + `@Preview`.
  - **Promo mode** (`promoActive`, the current default): teal hero with the Lock-Buddy (launcher
    foreground), **"★ FREE EARLY ACCESS"** ribbon, value list, **"Continue — it's free"** CTA, and
    "free for N more days" line when `free_until_epoch_ms` is set.
  - **Paywall mode** (`!promoActive && paywallEnabled`): same layout, plan CTA **disabled/dormant**
    until Billing lands (Phase 1b).
  - `PlusEntryCard(onClick)` — the Settings entry.
- `MainActivity.kt`: `SettingsScreen` now toggles a third sub-screen (`showPaywall`); `SettingsList`
  shows `PlusEntryCard` at the top → opens `PaywallScreen`. Same boolean sub-screen pattern as Kid Mode.
- **Build:** `assembleDebug` + `testDebugUnitTest` **SUCCESSFUL** (offline). No behavior change for
  existing flows; the new screen is purely additive and reads the promo config.
- **Remaining for Phase 3:** wire the real purchase CTA once `BillingManager` exists (Phase 1b).

### Phase 4 — _in progress_  (multi-kid foundation)
**Slice 4a (done) — data foundation (no behavior change; nothing reads profiles yet):**
- `data/KidProfile.kt` — `KidProfile` + `ProfileSession` data classes + `KidProfileCodec`
  (pure, dependency-free serialization: profiles = newline/pipe-delimited, sessions = `;`-joined).
- `data/DataStoreManager.kt` — `multi_kid_enabled` (default false), `kid_profiles`,
  `active_profile_id` (default "p1"), `profile_sessions` keys + flows + setters
  (`appendProfileSession` prunes via the existing 90-day window).
- Unit test `data/KidProfileCodecTest.kt` — round-trip (incl. custom allow-list), name
  sanitization, malformed-line skipping, sessions round-trip. **5/5 pass.**
- **Build:** `assembleDebug` + `testDebugUnitTest` **SUCCESSFUL** (offline).

**Slice 4b-1 (done) — pure identify engine + embedding storage (no UI/camera; existing 1:1 unlock untouched):**
- `face/FaceMatcher.kt` — pure 1:N `identify(probe, gallery, threshold, margin)`: returns a profile id
  only if the best score clears threshold AND beats the runner-up by `margin` (sibling safety),
  else null → "unknown". Unit-tested (incl. the ambiguous-siblings → unknown case). 5/5.
- `data/FaceGalleryCodec.kt` — per-profile embedding serialization (`p1:f,f,…;p2:…`). 3/3.
- `data/DataStoreManager.kt` — `kid_face_embeddings` key + flow + `setKidFaceEmbedding` /
  `removeKidFaceEmbedding` (separate from the parent `FACE_EMBEDDING_KEY`).
- **Build:** SUCCESSFUL (offline); total **36 unit tests pass**.

**Slice 4b-2 (done) — enrollment UI + toggle + migration (the visible part):**
- `FaceEnrollmentScreen` (MainActivity) parameterized — `title` / `isEnrolled` / `onEmbedding`
  (backward-compatible; parent enrolment unchanged). Reused for kid enrolment.
- `MainViewModel` — injects `EntitlementRepository`; exposes `multiKidEnabled`, `kidProfiles`,
  `kidFaceEmbeddings`, `multiKidUnlocked = isUnlocked(MULTI_KID_PROFILES)`; actions
  `setMultiKidEnabled` (migrates current single-kid settings → "Kid 1" + seeds "Kid 2"),
  `setKidProfileName`, `setKidProfileLimit`, `saveKidFaceEmbedding`.
- `ui/profiles/MultiKidSection.kt` — shown in Kid Mode: a "Multiple kids" toggle + (when on) a card
  per kid with editable name, per-kid daily-limit slider, and **"Enrol face"** → full-screen dialog
  wrapping `FaceEnrollmentScreen`, saving to that profile's embedding.
- **Build:** SUCCESSFUL (offline). First genuinely on-screen multi-kid functionality (parent can
  enable, name, set limits, and enrol two faces). **Not yet enforced** — Phase 5 makes the active
  profile actually drive budgets/locks.
- _Note:_ when multi-kid is on, the single-kid budget/preset controls above the section still show;
  Phase 5 decides precedence (active profile wins). Per-profile preset/allow-list editing is deferred.

### Phase 5 — _in progress_  (multi-kid enforcement)
**Slice 5a (done) — pure decision kernel:**
- `kid/MultiKidEnforcement.kt` — `activeProfileId(sessions, now, grace)` (latest session within the
  2-min grace, else null → re-identify) + `shouldLock(usedMs, limit, extMs, isNight)` (per-profile
  budget/night rule). Unit-tested `kid/MultiKidEnforcementTest` (6 cases). **42 tests pass.**

**Slice 5b (next, DEVICE-DEPENDENT, all gated behind `multi_kid_enabled` so single-kid/parent paths
are untouched):**
1. **Identify overlay** — when a controlled app opens and `activeProfileId` is null (stale), capture
   a face → `FaceMatcher.identify(kidFaceEmbeddings)` → set `active_profile_id` + append a
   `ProfileSession`; parent face → override; no confident match → strict (stay locked).
2. **Per-profile usage** — `pollScreenTime`: attribute controlled usage during each profile's
   sessions to that profile's `usedMs` (UsageMeasure ∩ ProfileSession).
3. **Lock decision** — `shouldLockForKidMode`: when multi-kid, use the active profile's
   `MultiKidEnforcement.shouldLock(...)`.
> ⚠️ 5b reworks the core face-lock flow + service timing → compile-pass ≠ works. Must be validated
> on a real device. Build it gated + carefully; the user tests identification + per-kid locking on a phone.

**Slice 5b-svc (done) — gated service enforcement (additive, fallback-safe; existing flow untouched):**
- `shouldLockForKidMode` — new branch (only when `multi_kid_enabled`): if a kid is active
  (`MultiKidEnforcement.activeProfileId` within grace) enforce that profile's
  `MultiKidEnforcement.shouldLock(...)`; otherwise fall through to the single-kid logic, so multi-kid
  behaves like single-kid until a kid is identified. Toggle off → block skipped entirely.
- `pollScreenTime` + `updatePerProfileUsage` — when multi-kid, recompute each profile's `usedMs` from
  its attributed sessions (UsageMeasure ∩ ProfileSession, controlled apps only), idempotently.
- **Build:** SUCCESSFUL (offline), 42 tests pass.
- _Functional gap:_ no kid is ever "active" yet because the identify camera (5b-cam) isn't wired — so
  today multi-kid mode behaves like single-kid. 5b-cam is what makes per-kid actually kick in.

**Slice 5b-cam (done — CODE-COMPLETE, device-validation pending):**
- `FaceLockOverlayContent` — additive `identifyMode` param (default false = existing parent-unlock
  unchanged). In identify mode the analyzer: parent face → override; else `FaceMatcher.identify`
  against `kidFaceEmbeddings` with a consecutive-match streak + liveness → on a confident kid:
  `setActiveProfileId` + `appendProfileSession(now,now)` → `MultiKidEnforcement.shouldLock` → under
  budget = `onAuthenticated()` (allow), over/night = `isBlocked` (kid message). Auto-warning suppressed
  in identify mode so it doesn't flash "budget over" while scanning.
- `LockActivity` — `EXTRA_IDENTIFY_MODE` threaded to the overlay.
- `AppLockForegroundService` — `shouldLockForKidMode`: stale identity + faces enrolled → lock (so the
  identify camera runs); `isIdentifyLock()` helper; `enforceLock`/`launchLockActivity` thread
  `identifyMode`; both call sites (handlePackageChange + watchdog) pass it. `pollScreenTime` extends the
  active kid's session (`DataStoreManager.extendLatestSession`) before per-profile attribution.
- **Build:** SUCCESSFUL (offline), 42 tests pass. Everything gated → single-kid/parent flow untouched.

**⚠ DEVICE TESTING REQUIRED for Phase 5** — verify on a phone: each kid is identified correctly,
the right kid's budget locks, parent override works, attribution looks right, and the camera behaves
in the lock. Known rough edges to watch / iterate: identify "scanning" screen is bare (dark) — could
add a "Who's using?" indicator; per-profile `usedMs` can lag up to one poll (~60s) right after an
identify; siblings that look alike rely on the match margin (ambiguous → stays scanning).

### Phase 6.1 — _done_  (multi-kid refinements: enrolment guard + per-kid apps)
**Enrolment guard:** multi-kid now ENFORCES only once BOTH kids' faces are enrolled —
`AppLockForegroundService.isMultiKidActive()` = toggle on AND `kidFaceEmbeddings.size >= 2`. Used to
gate `shouldLockForKidMode`, `isIdentifyLock`, and the per-profile poll, and mirrored by
`StatsViewModel.multiKidActive` (dashboard switches only when active). If the toggle is on but < 2
faces, the device runs as single-kid and `MultiKidSection` shows an "enrol both kids" prompt.

**Per-kid allowed apps:** each kid uses their OWN allow-list.
- Enforcement: `shouldLockForKidMode` multi-kid branch checks the active profile's
  `computeAlwaysAllowedSet(preset, customAllowed)` (global allow-list is now single-kid only);
  `updatePerProfileUsage` excludes each profile's own allowed apps; identify-stale bypass uses
  `multiKidUnionAllowed()` (any kid's allowed app + emergency comms open without a scan).
- UI: `MultiKidSection` per-kid card adds a preset selector (Phone&SMS / +WhatsApp / Custom) +
  a "Choose apps" dialog (reuses `AllowedAppRow`) **with a local search field** (matches the Protect
  tab; doesn't touch the shared `searchQuery`); `MainViewModel.setKidProfilePreset` /
  `toggleKidProfileCustomAllowed`. `PresetOption`/`AllowedAppRow` made `internal` for reuse.
- **Build:** SUCCESSFUL (offline), 42 tests pass. Still gated → single-kid/parent untouched; still
  needs the same device validation as Phase 5.

### Phase 6 — _done_  (multi-kid dashboard; additive, gated)
- `StatsRepository.profileUsageToday(profileId)` — per-app usage within that kid's ProfileSessions
  today (07:00 window), mirroring `freePlayUsageToday`.
- `StatsViewModel` — exposes `kidProfiles` + per-kid top apps. _(Phase 6.2 replaced the top-apps-only
  `profileTopApps`/`loadProfileTopApps` with a full per-kid `profileSnapshot`/`loadProfileSnapshot`.)_
- `StatsScreen` — when `multi_kid_enabled`, renders `MultiKidDashboard` instead of the Parent/Kid
  switcher (default off → existing dashboard untouched): a Family/▾kids `FilterChip` selector;
  **Family** = per-kid used/limit cards with a progress bar (from each profile's `usedMs`/limit);
  **per-kid** = `BudgetRing` + "where the time went" top apps (reuses `BudgetRing`/`AppBarRow`).
- **Build:** SUCCESSFUL (offline), 42 tests pass.
- _Note:_ the per-kid numbers read the same per-profile `usedMs` + sessions the enforcement writes, so
  their correctness rides on the 5b-cam attribution being validated on a device. Per-kid weekly/trends
  were deferred here and **landed in Phase 6.2** (below).

### Phase 6.2 — _done_  (per-kid drill-down = the full dashboard)
**Decision (user):** when Multiple-kids is active, each kid's view in the dashboard should show the
**same charts and data as the regular (parent/single-kid) phone** — not the stripped-down ring + top
apps. So the per-kid drill-down now reuses the existing rich `KidDashboard` end-to-end.

- **Reuse over rebuild:** `MultiKidDashboard` now renders the existing `KidDashboard(snapshot)` for the
  selected kid (budget ring + extension pill, top apps, 7-day `SparkBars` w/ budget threshold + budget-hit
  count, `WeeklyTrendCard` 4-week + monthly average, insights). No new chart code — guaranteed parity with
  the single-kid device.
- **Per-kid snapshot** (`StatsViewModel.computeProfileSnapshot` + `loadProfileSnapshot` →
  `profileSnapshot: StateFlow<StatsSnapshot>`, replacing `profileTopApps`/`loadProfileTopApps`): same shape
  as the single-kid snapshot, but every figure is scoped to that kid's `ProfileSession`s. Budget ring +
  extension pill read the persisted per-profile `usedMs`/`extensionsMs`; charts/insights are event-scanned
  ∩ sessions. Reuses the shared `computeTrendDelta` + `buildInsights` (KID mode).
- **Repository** (`StatsRepository`): generalized the one-window `profileUsageToday` into a per-profile
  window family over the existing 07:00 budget-day math — `profileSessionsFor(id)` (one DataStore read,
  reused across windows), private `profileUsageInWindow(sessions, s, e, include)`, plus
  `profileTotalForDay` / `profileDailyTotals(7)` / `profileWeeklyDailyAverages(4)` / `profileMonthToDateAverage`.
  Extracted a shared `monthStartMs()` (also used by the device-wide `monthToDateAverageMs`).
- **Family overview** stays the default comparison view; its kid cards are now tappable → drill into that
  kid's full dashboard (a small name header is shown above it).
- **Build:** SUCCESSFUL (offline), `:app:assembleDebug :app:testDebugUnitTest` pass.
- ⚠ Same device-validation caveat as Phase 5/6: per-kid figures are only as right as the 5b-cam identify
  attribution (sessions). The charts are correct by construction over whatever sessions exist.

**Family tab = real comparison dashboards (user: "show useful data … charts, rich UIs"):** the Family
view is no longer just two used/limit cards. It now compares the two kids head-to-head:
- **Today** — color-coded per-kid cards (used/limit, % of budget, kid's avatar color), tappable → drill-in.
- **This week** — a `ShareSplitBar` (share-of-screen-time split, "balance at a glance") + a grouped
  7-day `TwoKidWeekBars` chart (two bars per day, scaled to the shared max) + a legend with each kid's
  weekly total.
- **Week at a glance** — a compare matrix (`GlanceCompareCard`): daily average, budget-hit days
  (N/7), and busiest weekday, per kid in aligned columns.
- **Top app today** — each kid's #1 controlled app (`TopAppLine`).
- New reusable components in `StatsComponents.kt`: `TwoKidWeekBars`, `ShareSplitBar` (+`SplitSegment`),
  `TopAppLine`. New VM model `KidWeek`/`FamilyComparison` + `loadFamilyComparison()` (per-kid 7-day +
  today's top app, computed on IO when the Family tab is shown). Kids are color-coded by their seeded
  `avatarColor` (teal / coral). Cross-kid sections render only when both profiles exist (cap = 2).

### Phase 7 — _in progress_  (premium extras, each behind its flag)
**Earn screen-time (done):**
- `data/EarnedTask.kt` — `EarnedTask` + pure `EarnedTaskCodec` (unit-tested, 4 cases).
- `DataStoreManager` — `earned_tasks` key + flow + `setEarnedTasks`.
- `MainViewModel` — `earnedTasks`, `earnedUnlocked = isUnlocked(EARNED_TIME)`, `addEarnedTask` /
  `removeEarnedTask` (grant reuses the existing `grantExtension`).
- `ui/earn/EarnedTimeSection.kt` — shown in Kid Mode (hidden when the flag is locked): a task list
  (title + minutes + **Give** + delete) and an add-task row (title + 10/15/30-min chips). "Give"
  adds the minutes to today via `grantExtension`.
- **Build:** SUCCESSFUL (offline), **46 tests pass**. v1 grants to the single-kid budget;
  multi-kid per-profile earned-time is a later add.
**Schedules (done):**
- `data/Schedule.kt` — `Schedule` + pure `ScheduleCodec` (unit-tested, 4 cases).
- `DataStoreManager.schedules` + `setSchedules`; `MainViewModel` `schedules`,
  `schedulesUnlocked = isUnlocked(SCHEDULES)`, `addSchedule` (captures the CURRENT config),
  `applySchedule` (writes limit + preset + customAllowed into the live config), `deleteSchedule`.
- `ui/schedules/SchedulesSection.kt` — list of saved rule-sets (name + "N min · preset" + **Apply**
  + delete) and a "Save current settings" row. **Low-risk: no enforcement change** — Apply just
  writes the existing flat kid-mode keys.
- **Build:** SUCCESSFUL (offline), **50 tests pass**.

**New-app auto-block (done — DEVICE-DEPENDENT):**
- `DataStoreManager.autoBlockNewApps` + setter; `MainViewModel.autoBlockNewApps` /
  `autoBlockUnlocked = isUnlocked(NEW_APP_AUTO_BLOCK)` / `setAutoBlockNewApps`.
- `ui/newapp/NewAppBlockSection.kt` — a gated toggle in Kid Mode.
- `AppLockForegroundService` — a **runtime** `ACTION_PACKAGE_ADDED` receiver (registered via
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`; skips `EXTRA_REPLACING` updates):
  when on, a new user app is added to `protectedApps` (locked by the existing engine) + a "New app
  locked" notification with an **Allow** action (`ACTION_APPROVE_NEW_APP` → removes from protected,
  cancels the notification). New `NEW_APP_CHANNEL_ID` channel; receiver unregistered in onDestroy.
- **Build:** SUCCESSFUL (offline), 50 tests pass. ⚠ Device-test: install detection + notification +
  Allow action can only be verified on a phone.

**Per-app limits (done — enforcement-touching, DEVICE-DEPENDENT):**
- `data/PerAppLimitCodec.kt` (`package:minutes`) + `kid/PerAppLimits.kt` pure `isOver(...)` —
  unit-tested (`PerAppLimitsTest`, 4 cases).
- `DataStoreManager.perAppLimits` + `setPerAppLimit(pkg, minutes)`; `MainViewModel.perAppLimits` /
  `perAppLimitsUnlocked = isUnlocked(PER_APP_LIMITS)` / `setPerAppLimit`.
- Service: `pollScreenTime` caches the per-package usage map (`@Volatile perAppUsageMs`);
  `shouldLockForKidMode` (single-kid path) locks an app when `PerAppLimits.isOver(...)` — *before* the
  allowed-set bypass, so a parent can cap even an otherwise-allowed app. Multi-kid per-app limits deferred.
- `ui/perapp/PerAppLimitsSection.kt` — gated card + "Manage" dialog (searchable app list, allowed/
  limited apps on top, a per-app limit dropdown: Off/15/30/45/60/90/120).
- **Build:** SUCCESSFUL (offline), **54 tests pass**. ⚠ Device-test the lock timing (per-app usage
  refreshes on the 60s poll, like the overall budget).

**Themes (done):**
- `ui/theme/Theme.kt` — `AppAccent` enum (Teal default / Purple / Coral / Green); `AppShieldTheme`
  gains an `accent` param (TEAL = the exact brand palette, others recolor the primary surfaces).
- `DataStoreManager.themeAccent` + setter; `MainViewModel.themeAccent` /
  `themesUnlocked = isUnlocked(THEMES)` / `setThemeAccent`.
- `MainActivity` root reads the accent and passes it to `AppShieldTheme`.
- `ui/theme/ThemePickerSection.kt` — gated swatch picker in Settings.
- **Build:** SUCCESSFUL (offline), 54 tests pass.

**Phase 7 complete** (Earn time, Schedules, New-app auto-block, Per-app limits, Themes — all gated).
multi-parent faces = explained to the user, not requested. Per-app/earned/new-app features are
device-dependent (camera/notifications/install detection/per-app lock timing) → validate on a phone.

**Known gating note for Phase 8:** premium *enforcement* features (multi-kid, and the upcoming
per-app limits) currently gate on their own toggle/data, NOT the entitlement flag — fine during the
promo (everything unlocked). When billing flips on, the service must also consult the cached
entitlement to revoke premium enforcement for non-premium/lapsed users.

---

## Phase 7.2 — Multiple Kids split out + per-kid per-app limits (done)

**Decision (user):** Multiple Kids is its **own** feature, enforced **independently** of the single
Kid Mode toggle. Per-kid per-app limits stored in a **separate key** (undo-safe), not in `KidProfile`.

- **UX:** "Multiple kids" is now its own **Settings hub entry** (Protection group), no longer nested
  inside Kid Mode. Kid Mode = one child's *own* phone; Multiple Kids = a *shared* phone for two kids.
  `MainActivity`: `SettingsRoute.MultiKid` → `SettingsDetailScreen` hosting `MultiKidSection`; removed
  from `KidProtectBody`. Hub badges/subtitles reflect own-phone vs shared-phone.
- **Independent enforcement (Phase 2 / device-dependent):** `shouldLockForKidMode` enforces when
  `ownerType=="kid"` **OR** `isMultiKidActive()`. The 60s poll already accrued per-profile usage under
  `isMultiKidActive()` regardless of `ownerType`, so this was a one-line gate change.
- **Per-kid per-app limits:** separate DataStore key `kid_per_app_limits` (`KidPerAppLimitCodec`,
  `profileId -> pkg:min`) — deliberately NOT in `KidProfile`, so a revert is non-destructive (no codec
  migration; reverting just orphans the key). `MainViewModel.kidPerAppLimits` + `setKidProfilePerAppLimit`.
  UI: each kid card opens the shared `PerAppLimitsManageDialog` (extracted from the global per-app
  section). Enforcement: multi-kid branch locks an app when the active kid is over THAT kid's cap,
  checked *before* the per-kid allowed bypass (mirrors single-kid ordering). New per-(kid,app) usage
  cache `perProfileAppUsageMs`, filled in `updatePerProfileUsage`.
- **Build:** SUCCESSFUL (offline), **59 tests pass** (+`KidPerAppLimitCodecTest`, 5).
- ⚠ **Device-validate:** the independence gate + per-kid per-app lock touch the live lock path —
  confirm on a phone (identify → right kid → per-kid budget + per-app cap timing). Compile ≠ works.

---

## How to resume in a future session
1. Read `PREMIUM_FEATURE_PLAN.md` for the rules.
2. Read this file's **Phase tracker** for the next unchecked phase.
3. Implement that phase only; build & verify; **update this file** (tracker + change log).
4. Keep the free-launch invariant: `promo_active=true` default → app fully free, billing dormant.
