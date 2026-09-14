# Monetization → Go-to-Market Plan

Two streams: **A. Harden gating (code)** · **B. Play Console billing (ops)**.
Status: RC flip works (`promo_active=false` fetched on device). Billing wired (`BillingManager` bound). Gaps =
soft gating (badge-only, no hard block, no revoke-on-lapse) + no Play Console product yet.

**Every feature is config-toggleable free↔paid** via `feature_tiers_json` (Firebase RC), no app update — any
`Feature` → `"free"`/`"premium"`. Effective only where an enforcement gate exists in code (all current features
have one).

---

## Stream A — Harden gating (code, ~3–4 days)

- **A1. Central gate + paywall-on-tap** `M` — one `Gate(feature){ content }` composable: locked → lock card →
  paywall; unlocked → content. Wrap every premium section so the feature is **unusable until subscribed**, not just
  badged. Premium Settings row tap → paywall.
- **A2. Revoke-on-lapse** `M` — on entitlement lost, actively turn the feature OFF (sharing off, multi-kid off,
  theme→default, schedules paused), not just block re-enable. Re-check each launch (+ RTDN later).
- **A3. Consistent Plus badges** `S` — badge on every gated control, tap → paywall. Audit all `isUnlocked` sites.
- **A4. Paywall completeness** `M` (Play policy) — price + period + **auto-renew/cancel-anytime** text, trial terms,
  feature list, **Terms/Privacy links**, **Restore** (wire `BillingManager.restore()` + result toast),
  **Manage-subscription** deep link. States: loading / no-network / billing-unavailable (never block protection).
- **A5. Entitlement resilience** `S` — paywall/gates collect `config` as **live** Compose state (kills stale
  "Free for now" on first open); lower `minimumFetchIntervalInSeconds` for launch; safe initial values.
- **A6. Billing robustness** `M` (overlaps B) — handle PENDING (don't grant), ack ≤3d (have), obfuscated account id,
  sub states active/grace/on-hold/paused/expired (keep premium thru grace+hold, revoke after).

## Stream B — Play Console billing (ops, ~1 day + review lead)

- **B1. Subscription product** `S` — ID `premium`, base plan `monthly` P1M auto-renew, prices IN ₹59 / US $2
  (manual), offer free trial P7D new-customer, **Activate** all.
- **B2. Licensing key** `S` — Monetization setup → Licensing → Base64 RSA → paste into `BillingManager.PUBLIC_KEY`.
- **B3. License testers** `S` — Play Console → Settings → License testing → tester Gmails, RESPOND_NORMALLY.
- **B4. Upload track** `S` — `bundleRelease` AAB → Internal testing → testers → opt-in; install **from Play**.
- **B5. Tester-only RC condition** `S` — keep `promo_active` default **true** (prod free); Condition targeting
  testers → `promo_active=false`,`paywall_enabled=true`.
- **B6. E2E test** `M` — trial→bill→cancel→restore→grace→hold→lapse→billing-unavailable, on stable/USB device.

## Prereqs / blockers (parallel)
- **SFace face-model license** — hard gate before charging (`FACE_MODEL_MIGRATION_PLAN.md`).
- **Data Safety** form — app-list now shared (update; `PLAYSTORE_RELEASE_PLAN.md`).
- **Server-side verification** (Play Developer API + RTDN/Pub-Sub) `L` — defer for soft-launch (root-spoofable), do
  before scale.

## Decisions needed
1. Final **free vs premium** feature split → sets `feature_tiers_json`. Is Kid Mode core paid or free?
2. **Price/trial** confirm (₹59 / $2 / 7-day).
3. Soft-launch **without** server-verify (faster, root-spoofable) or wait?

## Sequence
1. **A5 + A1** (gates real) → A3 → A4 → A2 (so the flip actually locks/blocks).
2. B1–B4 (product + track + tester) in parallel.
3. A6 + B5 → B6 E2E.
4. SFace + Data Safety → flip prod.

Effort: A ≈ 3–4 d, B ≈ 1 d + review. Server-verify +2–3 d (deferrable).
