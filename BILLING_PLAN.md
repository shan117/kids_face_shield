# Billing & Config-Driven Monetization — Plan

How Shield goes from **free** to **paid** with **no app update** — only Firebase Remote Config + Play
Console changes. Detailed companion to `PREMIUM_FEATURE_PLAN.md` (§5 billing, §8 paywall, §9 go-paid).

---

## 0. The one rule that makes "no update" possible

> **The billing engine + real paywall must already be inside the installed app *before* you flip.**
> You can't add billing *at* the moment you go paid — that needs an update. So billing is **built and
> shipped dormant (under the promo)** first; after that, free↔paid is **pure configuration.**

Two honest consequences:
1. Billing ships in **v1, or in a normal free update during the promo** — your choice (see §7). Either
   way it's in the app well before the flip.
2. After that, **extending the free period** or **going paid** is Remote Config only — no update, no re-review.

## 1. Two different "free" — don't conflate them

| "Free" | What it is | Controlled by |
|---|---|---|
| **Promo period** | The whole app is free for everyone, no purchase possible | **Remote Config** `promo_active=true` |
| **Subscription free trial** | When *paid* mode is on, a new subscriber gets 30 days free before first charge | **Play Console** offer on the subscription |

Your "increase the free period" = keep the **promo** on. Your "activate paid / end free trial" = turn
the promo **off** (then new users get the Play **trial**, then billing). Same flip, two layers.

## 2. Control surface — what knob does what (all no-update)

**Firebase Remote Config:**
| Key | Effect |
|---|---|
| `promo_active` | `true` → everything free for all (master gate). `false` → premium features gate. |
| `paywall_enabled` | `true` → show the purchase UI (only matters when promo off). |
| `free_until_epoch_ms` | In-app countdown ("free for N more days"). Display only — does **not** auto-cut. |
| `price_tier` | Which offer to surface (e.g. `default` / `sale`). |
| `feature_tiers_json` | Per-feature free/premium override of the shipped default map. |

**Play Console** (also no app update — the app reads these at runtime):
- The `premium` subscription's **prices**, **base plans** (monthly/annual), **offers** (trial/intro).

## 3. Architecture (what gets built)

- `billing/BillingManager.kt` *(new)* — wraps `BillingClient`; **replaces** `DormantPremiumSource` as the
  `PremiumSource`. Connect+retry → query products → launch purchase → verify+acknowledge → write
  `cached_is_premium` → reconcile on every launch. **The service reads only the cached flag** and never
  blocks core protection if Play/billing is unavailable (`PREMIUM` §4).
- `premium/EntitlementRepository` — **unchanged.** It already combines `config.promoActive` + `isPremium`
  via `Entitlements.isUnlocked`. Swapping `DormantPremiumSource → BillingManager` is a one-line Hilt change.
- `ui/paywall/PaywallScreen` — the dormant CTA becomes a **real monthly/annual selector** driven by
  `ProductDetails` (real Play prices), with trial, Restore, and Terms/Privacy.
- `service/AppLockForegroundService` — premium **enforcement** features must consult the cached
  entitlement (see B3) so paid-mode revokes premium for non-subscribers — **core safety stays free.**

## 4. Play Console product model (modern subscriptions)

```
Subscription product:  premium
├── Base plan: monthly   (₹X / month)   ── offer: 30-day free trial  [+ optional intro price]
└── Base plan: annual    (₹Y / year)    ── offer: 30-day free trial  [+ optional intro price]
```
The app calls `queryProductDetailsAsync("premium")` → gets both base plans + their offer tokens →
the paywall shows monthly vs annual + trial → purchase = `launchBillingFlow(offerToken)`.

## 5. Build phases (each compiles; all dormant-safe under the promo)

| Phase | Deliverable | Ships dormant? |
|---|---|---|
| **B0** *(owner)* | Play Console: create `premium` subscription + monthly & annual base plans + 30-day trial offer + price tiers + **license testers** | n/a (console) |
| **B1** | `BillingManager` (`billing-ktx`): connect/retry, `queryProductDetails`, `launchBillingFlow`, `onPurchasesUpdated` → **verify signature → acknowledge** → cache `isPremium`; `queryPurchases` on launch → reconcile (revoke if lapsed). Swap `DormantPremiumSource → BillingManager` in `PremiumModule`. Unit-test pure parts. | ✅ inert while `promo_active=true` |
| **B2** | Real paywall: monthly/annual selector from `ProductDetails`, honest prices + trial badge, **Restore**, manage-subscription deep link, Terms/Privacy. CTA → `launchBillingFlow`. | ✅ promo mode unchanged |
| **B3** | **Close the service-side entitlement gap:** `AppLockForegroundService` premium enforcement (multi-kid, per-app limits, new-app block, schedules) consults the cached entitlement before applying. Core safety (app lock, single-kid budget, night lock, basic stats) stays hardcoded-free. | ✅ no-op under promo |
| **B4** | Robustness/compliance: PENDING purchases, expiry/lapse, **Restore**, account-hold/grace, "billing unavailable" → treat as free + never crash + never block protection. Verify trial→bill→cancel→restore on the internal track. | ✅ |
| **B5** *(owner)* | **The flip** — see §6. | — |

**Order:** B0 ∥ B1 → B2 → B3 → B4 → (later) B5. B1–B4 are shipped during the **free** period.

## 6. Operating runbook (the no-update procedures)

**A. Extend the free period** (response is good, stay free longer):
1. Leave `promo_active = true`. (Optional: bump `free_until_epoch_ms` so the in-app countdown reflects it.)
2. Publish Remote Config. Done — nothing else changes.

**B. Go paid** (end the promo, start charging):
1. Confirm the `premium` products + prices + trial are **live** in Play Console (B0).
2. Confirm the installed app already contains billing (B1–B4 shipped).
3. Remote Config: set `paywall_enabled = true`, then `promo_active = false` (+ `feature_tiers_json` if customizing the free/premium split).
4. **Publish.** Propagates within the fetch window (~1h, the `minimumFetchIntervalInSeconds` we set) — **not instant**. New & existing installs flip on next fetch. **No app update, no re-review.**

**C. Roll back to free** (paid isn't converting):
1. Set `promo_active = true` → Publish. Back to free-for-all within the fetch window.

## 7. When does billing have to ship? (your launch choice)

- **Option A — billing in v1:** build B1–B4 now, launch with it dormant. The flip is *forever* update-free.
  Cost: delays v1 until billing is built + device-tested.
- **Option B — launch free first, add billing via an update during the promo *(recommended)*:** ship v1
  free now (no billing), gather response, then ship a **routine free update** that adds B1–B4 (still
  dormant), then flip via config. The **flip itself is still update-free** — you just did one normal
  update earlier, during the free months, which is expected anyway.

Either satisfies your rule. **Recommended: Option B** — don't block launch on billing; add it well before
you intend to flip.

## 8. Edge cases & guardrails

- **Never block protection:** if billing/Play is unavailable, the service uses the **cached** entitlement;
  if uncertain, it must **not** disable child-safety. Core safety is hardcoded free regardless.
- **Lapse/refund/cancel:** `queryPurchases` on every launch reconciles; cache flips to non-premium; the
  next config read re-gates. Premium features lock, **core safety never does.**
- **Spoofing (rooted devices):** client-side entitlement is spoofable (accepted; server-side is future,
  `PREMIUM` §13). Don't gate *safety* on it — only premium extras.
- **First price is clean:** set prices correctly at B0; raising later needs Play's notify-and-consent.
- **Propagation lag (~1h):** the flip is not instant. Communicate "free for N more days" generously.
- **Face-model license (separate gate):** swap to SFace **before** charging (`FACE_MODEL_MIGRATION_PLAN.md`).

---

## Phase tracker

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

| Phase | Description | Status | Notes |
|---|---|---|---|
| B0 | Play Console: `premium` subscription + monthly/annual + 30-day trial + testers | `[ ]` | Owner. Needed to test + before the flip. |
| B1 | `BillingManager` + swap `DormantPremiumSource` | `[x]` | **Done** — `billing/BillingManager.kt` (connect/retry, query products, reconcile, acknowledge, cache, restore, `launchPurchase` for B2) + pure `BillingLogic` (6 tests) + `Security` (verify, disabled until `PUBLIC_KEY` set). DI swapped; `start()` called from `AppLockApplication`. Build green, dormant under promo. |
| B2 | Real monthly/annual paywall (ProductDetails prices, trial, Restore) | `[x]` | **Done** — `SubscriptionFormat` (pure, tested) + `PlanUi` mapping + plan selector UI (price+trial cards, CTA→`launchPurchase`, Restore, auto-renew disclaimer, legal links). `@Preview PaywallPaidPreview` shows it with sample prices. Build green. |
| B3 | Service-side entitlement gating (revoke premium enforcement when paid) | `[x]` | **Done** — service injects `EntitlementRepository`; `multiKidUnlocked`/`perAppLimitsUnlocked`/`newAppUnlocked` gates (default true = promo-safe) gate `isMultiKidActive`, both per-app-limit checks, and new-app auto-block. Core safety (app lock, single-kid budget, night lock) never gated. Build green. |
| B4 | Billing robustness + compliance | `[ ]` | Device + test-track validation. |
| B5 | *(owner)* The config flip — go paid, no update | `[ ]` | §6.B. Gated on SFace license. |

## Cross-references
- `PREMIUM_FEATURE_PLAN.md` — §4 entitlement logic, §5 billing flow, §8 paywall modes, §9 go-paid, §11 free/premium map.
- `FIREBASE_SETUP.md` — the Remote Config keys (already live) + §6 flip procedure.
- `PLAYSTORE_RELEASE_PLAN.md` — R6 (billing), R8 (go-paid).
- `FACE_MODEL_MIGRATION_PLAN.md` — D8: SFace before charging.
