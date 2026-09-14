# Premium, Multi-Kid & Monetization — Feature Plan

Source of truth for the premium transition. Update this file whenever rules change.
Implementation progress is tracked in `PREMIUM_IMPLEMENTATION_STATUS.md`.

---

## 0. Outcome
Ship a **free, fully-working app** with **single-kid (unchanged) + opt-in 2-kid profiles**,
**Firebase Remote Config (local defaults + cloud override)**, and **Google Play Billing built
but dormant**. After a manually-controlled free promo, flip to paid **entirely at runtime** —
no app update.

## 1. Final decisions
| # | Decision |
|---|---|
| D1 | **Everything free at launch** via a promo; nobody is charged. |
| D2 | **Multi-kid ships in v1**, free now; parent toggles **single (default) or 2-kid**. |
| D3 | **Profiles cap = 2.** |
| D4 | **Remote Config from v1** with **shipped local defaults**; cloud overrides, no update. |
| D5 | **Every feature** is config-driven (Remote Config) free↔premium. Defaults keep the core set free, but **any feature can be converted to premium with no app update** (each default-free feature needs its enforcement gate added in code to take effect). _Updated: the original "core hardcoded always-free, never flippable" guarantee was **removed** at the owner's request for full monetization flexibility._ |
| D6 | **Promo ends manually** (`promo_active=false`), not on a timer. Free/premium split decided **after** the promo from real usage. |
| D7 | **Subscription + discount (intro offer) + 30-day trial configured in Play Console *before* launch**; billing dormant; app reads real prices. |
| D8 | **Price amounts live in Play Console**; Remote Config only picks which tier/offer to show. |
| D9 | **Client-side verification**, no backend; **Play account = identity**, no login. |
| D10 | **Multi-kid lock = hybrid model** (identify on controlled-app open; attribute session; grace; strict fallback; emergency bypass). |
| D11 | **Face roles:** kid face = **identity** (no bypass); parent face = **authority** (override/settings/extensions). |
| D12 | **Tamper** = Device Admin + Settings-lock + face. **Device Owner = future Pro edition** (out of scope now). |
| D13 | **Firebase analytics-free** + Data Safety/Families declarations. |

## 2. Architecture (components → files)
- `premium/Feature.kt` — `Feature` enum + `Tier` + `CORE_ALWAYS_FREE` / `DEFAULT_PREMIUM` sets.
- `premium/PaywallConfig.kt` — the runtime config snapshot + defaults.
- `premium/Entitlements.kt` — pure `isUnlocked(feature, config, isPremium)` (unit-tested).
- `premium/RemoteConfigSource.kt` — interface + `LocalDefaultsConfigSource` (Firebase impl added at Phase 0).
- `premium/PremiumSource.kt` — interface + `DormantPremiumSource` (real Billing impl added later).
- `premium/EntitlementRepository.kt` — single source of truth: `isPremium`, `config`, `isUnlocked(Feature)`.
- `billing/BillingManager.kt` — wraps `BillingClient` (dormant until promo off). *(added with billing dep)*
- `data/DataStoreManager.kt` — cache keys + `KidProfile` / `ProfileSession` storage.
- `face/FaceRecognitionManager.kt` — extend to **1:N identify** (+margin).
- `service/AppLockForegroundService.kt` — multi-kid identity-session + hybrid enforcement.
- `ui/paywall/PaywallScreen.kt` — early-access / paywall (two modes).
- `ui/profiles/…` — enroll/manage 2 kids; profile selector.
- `ui/stats/…` — multi-kid dashboard (reuse existing components).

## 3. Data model
**New DataStore keys (cache + config snapshot):**
| Key | Type | Default |
|---|---|---|
| `cached_is_premium` | Bool | false |
| `cached_plan` | String | "" |
| `multi_kid_enabled` | Bool | false |
| `kid_profiles_json` | String | "" (→ migrated to Profile 1) |
| `active_profile_id` | String | "p1" |
| `profile_sessions` | String | "" (log) |

**`KidProfile`** = `{ id, name, avatarColor, dailyLimitMinutes, allowedPreset, customAllowed:Set, usedMs, extensionsMs, lastResetDate }`.
**`ProfileSession`** = `{ profileId, startMs, endMs }` (attribution log, pruned like Free-Play history).
**Migration:** existing single config → `KidProfile("p1", "Kid 1", …)`. Single-kid mode = one profile, no identity scans.

**Remote Config keys (cloud, with shipped defaults):**
| Key | Type | Default | Meaning |
|---|---|---|---|
| `promo_active` | Bool | `true` | **Master gate** — true = everything free |
| `free_until_epoch_ms` | Long | 0 | **Display/countdown only** (gating is `promo_active`) |
| `paywall_enabled` | Bool | `false` | Show purchase UI when promo off |
| `price_tier` | String | `"default"` | Which Play offer to surface |
| `feature_tiers_json` | String | §11 default | Free/premium per feature |
| `paywall_variant` | String | `"a"` | Copy/layout |

## 4. Entitlement logic (exact)
```
isUnlocked(f):
   if config.promo_active          -> true      // free-for-all period
   if feature_tiers[f] == FREE     -> true      // free by config (default or override); any feature
   else                            -> isPremium  // any feature can be premium — incl. default-free ones
```
`isPremium` = an **active, acknowledged** `premium` SUBS purchase, **signature-verified**, cached in
DataStore, **re-queried from Play every launch** (cache never authoritative). **Service reads the
cached flag only** and never blocks protection if billing/Play is unavailable.

## 5. Billing flow (exact, dormant until promo off)
> **Detailed build + config-driven-flip plan (no app update): `BILLING_PLAN.md`.**
1. Connect `BillingClient`; retry on disconnect.
2. `queryProductDetailsAsync` for `premium` (SUBS) → offers (base + intro + trial).
3. Paywall CTA → `launchBillingFlow` with the offer matching `price_tier`.
4. `onPurchasesUpdated` → verify signature → **acknowledge** → set `isPremium=true` + cache.
5. On every app start → `queryPurchasesAsync` → reconcile (revoke if lapsed).
6. Handle `PENDING`, expiry, **Restore**, **manage-subscription** deep link, "billing unavailable" (treat as free, never crash).

## 6. Multi-kid behavior (only when `multi_kid_enabled = true`)
When a **controlled** app is opened:
1. Within **grace window** (≤2 min since last identify) → keep active profile. Else →
2. **Face scan:**
   - **Kid** (≥ threshold AND beats runner-up by margin) → set active profile. Then **under budget & not night** → allow + extend that kid's `ProfileSession`; **over/night** → kid-mode lock (needs **parent** face).
   - **Parent** → authorize/override.
   - **No confident match** → tap-to-confirm; still none → **stricter profile** (never a hard lockout).
3. **Allowed/emergency apps (Phone/SMS/allow-list) never gated** — no scan.
4. Per-profile counters drive each kid's budget; 07:00 reset per existing logic, per profile.

Single-kid & parent-phone modes: **no identity scans, behavior unchanged.**

## 7. Multi-kid dashboard
- `Parent|Kid` switcher → **profile selector** `[ Family ] Aarav Diya` (only when multi-kid on).
- **Family overview** (default): each kid's today (mini ring used/limit, ±% vs yesterday) + weekly compare.
- **Per-kid drill-down:** existing `BudgetRing`, top-apps, `SparkBars`, `WeeklyTrendCard`, scoped by **`ProfileSession` ∩ UsageStats**.
- Neutral comparison; avatars/colors; default to overview.

## 8. Early-access / paywall (one screen, two modes)
- **Promo mode** (`promo_active=true`): ribbon **"FREE EARLY ACCESS"**, value list, CTA **"Continue — free"**, optional countdown, Settings **"Plus · free"** badge. No purchase.
- **Paywall mode** (`promo_active=false` & `paywall_enabled=true` & not premium): real `ProductDetails` prices, **honest strikethrough** (base vs intro/annual-monthly-equiv), 30-day trial timeline, **Restore**, Terms/Privacy.

## 9. What the OWNER configures (before launch)
> **Detailed step-by-step store/release/runtime-flip playbook: `PLAYSTORE_RELEASE_PLAN.md`.** This section is the summary.

**Firebase:** create project → add `google-services.json` → **disable Analytics / ad-id** → seed Remote Config keys (§3).
**Play Console:** listing → **Data Safety** + **Designed-for-Families** → **Privacy + Terms URLs** → subscription `premium` (monthly + annual) → **intro/discount offer + 30-day trial** → **price tiers** → **license testers** → internal testing track.

## 10. Build phases
| Phase | Deliverable | Verify |
|---|---|---|
| 0 *(owner)* | Firebase + Play Console per §9 | Products + config exist |
| 1 | Entitlement + flag core; (then) billing-ktx + firebase-config deps; `BillingManager` dormant; cache keys | Unit test `isUnlocked`; app unchanged |
| 2 | Route premium-candidate features through `isUnlocked(...)` | No visible change under promo |
| 3 | Early-access screen + Plus badge (paywall mode wired) | Screen shows; purchase works on test track |
| 4 | Multi-kid **foundation**: `KidProfile` + storage + migration; 2-kid enroll; 1:N identify+margin; `ProfileSession` | Identify picks right kid |
| 5 | Multi-kid **enforcement** (hybrid, §6) | Per-kid budget locks; strict fallback; emergency works |
| 6 | Multi-kid **dashboard** (§7) | Family overview + per-kid detail correct |
| 7 | Selected extra premium features (each flagged) | Each gates via flag |
| 8 | Billing robustness + compliance (§5) | Trial→bill, cancel, restore, degrade verified on test track |
| 9 *(owner, no update)* | Create/confirm prices; set `feature_tiers`, `promo_active=false`, `paywall_enabled=true`; optional FCM + early-user reward | Paywall appears live |
| 10 *(future)* | Server-side verification; Device-Owner Pro; backend/sync | — |

**Launch free:** Phases 0–6. **Monetize later:** 8 → 9 (runtime). **Order:** 0→1→2→3→4→5→6→8→7→9.

## 11. Default free/premium map (shipped default — flippable after promo)
- **DEFAULT_FREE (free at launch; convertible to premium via config + a per-feature gate):** `APP_LOCK`, `KID_BUDGET`, `NIGHT_LOCK`, `ALLOWED_PRESETS`, `FREE_PLAY`, `TAMPER`, `BASIC_STATS`. **All gates built** — every feature is convertible to premium via config + enforced:
`NIGHT_LOCK`, `KID_BUDGET`, `FREE_PLAY`, `TAMPER`, `APP_LOCK` (service gates in `AppLockForegroundService`) +
`BASIC_STATS` (Stats tab) and `ALLOWED_PRESETS` (Custom-picker only — preset C, Kid Mode UI). Defaults
unchanged (all free today). ⚠ Device-validate the lock-path gates, `APP_LOCK` especially (it can disable
all protection when flipped premium for a non-subscriber).
- **DEFAULT_PREMIUM (premium by default; flip anytime):** `MULTI_KID_PROFILES`, `SCHEDULES`, `PER_APP_LIMITS`, `FULL_STATS`, `EARNED_TIME`, `MULTI_PARENT`, `NEW_APP_AUTO_BLOCK`, `THEMES`, `REMOTE_REPORT` (gate enforced on the child's "Share weekly report" toggle; see `PARENT_REMOTE_REPORT_PLAN.md`), `REMOTE_CONTROL` (parent→child commands — gate on the child's "Allow remote control" opt-in + the parent's control buttons; see `PARENT_REMOTE_CONTROL_PLAN.md`).
- During the promo all are free regardless; this map only takes effect when `promo_active=false`.

## 12. Known & accepted limitations
- Remote Config propagates within the fetch window (~12h, tunable) — not instant.
- Raising price on existing subscribers needs Play's notify-and-consent flow (first price = clean).
- Face can cross-match siblings → margin + tap-to-confirm; cap 2.
- Hand-off without re-identify → per-session granularity (accepted).
- Safe Mode + the kid's *own* phone → only Device Owner fully closes it (deferred, Pro).
- Client-side entitlement is spoofable on rooted devices (server-side is future).

## 13. Out of scope (now)
Server-side verification, Device-Owner provisioning, backend/cross-device sync, web dashboard, >2 profiles.
