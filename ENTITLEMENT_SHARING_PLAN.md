# Entitlement sharing — one payment, every device in the family

Status: **implemented.** Requires one Firebase console step (§6).

---

## 0. The problem

Entitlement is purely local. `Entitlements.isUnlocked` ends in `return isPremium`, and `isPremium` comes
from `queryPurchasesAsync` against the Google account on **that** phone. Nothing in `premium/` or
`billing/` knows anything about pairing.

So once `promo_active=false`:

> A parent with two kid phones pays on their own phone and gets **almost nothing**, because every
> premium feature lives on the devices that didn't pay.

| Feature | What stops on the kid's phone |
|---|---|
| `REMOTE_REPORT` | Child stops uploading — parent sees "no report yet" |
| `REMOTE_CONTROL` | Child ignores Lock now, grant time, limits |
| `LOCATION_NOW` / `_HISTORY` | Child never answers location requests |
| `WEB_FILTER` | Browser containment and filtering stop |
| `PER_APP_LIMITS`, `SCHEDULES`, `NEW_APP_AUTO_BLOCK` | Stop enforcing |

Several fail **silently** — the enforcement-only features have no paywall card, which is what the
`LapseBanner` exists for.

**This is a hard blocker on charging.** Not a nice-to-have.

One accidental exception: if the kid's phone is signed into the **same Google account**, Play reports the
subscription as owned and `isPremium` is true there by itself. Common enough to pass a developer's own
testing and fail for everyone else.

---

## 1. Why a lease, and not an expiry date

The obvious design — "tell the kid the subscription ends on the 4th" — is **impossible on-device**.

- `expiryTime` exists only in the **server-side** Google Play Developer API.
- The client-side `Purchase` object has `purchaseTime`, `purchaseToken`, `isAutoRenewing` — no expiry.
- **`purchaseTime` does NOT update on renewal.** It is the subscription *signup* time and stays fixed
  for the whole life of the subscription; the token is stable too. Only `expiryTime` moves, and it is
  not on the device.

So any attempt to derive a period end client-side is broken. A rejected idea, recorded so nobody
re-invents it:

```
grant = min(now + 7 days, purchaseTime + 31 days)    ← HARMFUL, DO NOT USE
```

A monthly subscriber renewing on 5 Feb still has `purchaseTime` = 5 Jan, so the cap collapses and every
paying family loses their kids' premium permanently after one month. For an **annual** subscriber it
would break after 31 days of a 365-day subscription. The parent's own phone would still show premium, so
the bug reads as "works on mine, not my son's" with no visible cause.

What the device *can* answer, accurately and through refunds, grace periods, holds, pauses and plan
changes: **"is there an active subscription right now?"**

So the parent's phone answers that question daily and issues a short-lived grant.

### Works with any plan

The lease never looks at the billing period. `isPremium` is evaluated at **product** level
(`PREMIUM_PRODUCT_ID = "premium"`), so monthly, annual, trial and any future base plan behave
identically. Nothing in this design needs to know which plan was bought.

---

## 2. Design

```
Parent's phone (paid)                         Kid's phone
  foreground service, daily:                    listens on grants/{pairingId}
  if (isPremium) ──── encrypted grant ────►     caches "premium until <date>"
                                                works offline until then
```

- **Leased, renewed daily.** Each renewal pushes the expiry 7 days out.
- **Revocation needs no delivery.** When the parent stops being premium, renewals stop and the grant
  decays. Nothing has to reach the child's phone to take premium away — which is the only revocation
  mechanism that cannot fail.
- **Encrypted under the pairing key.** Not for secrecy — an expiry timestamp is not a secret — but for
  *authenticity*. A plaintext grant would let anyone who knows a pairing id write themselves free
  premium.

### The lease length is the only dial, and it cuts both ways

| Lease | Kid offline tolerance | Free premium after cancelling |
|---|---|---|
| 7 days | 7 days | ~7 days |
| 30 days | 30 days | ~30 days |

They are the same number. There is no setting with long offline tolerance and prompt revocation.

**7 days.** About ₹14 of grace per cancellation at ₹59/month — noise. A month is not. And erring
slightly generous is the right direction: briefly over-granting costs a little revenue, while wrongly
dropping premium leaves a child's phone unfiltered and unlimited, which the parent would never notice.

---

## 3. Data model

```kotlin
data class PremiumGrant(val premiumUntilMs: Long)
```

One field, its own delimiter codec, matching house convention. Stored at `grants/{pairingId}` as an
AES-GCM `iv` + `ciphertext` pair, exactly like the other three collections.

Child caches the decrypted expiry in `premium_grant_until_ms`. **Enforcement reads the cache, never the
network** — a child's budget and web filter must not depend on connectivity.

---

## 4. The entitlement change

One line, in the one pure function everything funnels through:

```kotlin
fun isUnlocked(feature, config, isPremium, grantedPremium: Boolean = false): Boolean {
    if (config.promoActive) return true
    if (config.tierOf(feature) == Tier.FREE) return true
    return isPremium || grantedPremium
}
```

Defaulted, so every existing caller and test is unaffected.

---

## 5. Must not regress

| Risk | Guard |
|---|---|
| A grant on a **parent** device granting itself premium | The listener attaches only in the `child` role. A parent never receives one. |
| Forged grant from someone holding a pairing id | Encrypted under the pairing key — an undecryptable grant is discarded. |
| **Expired grant still honoured** | Validity is `now < premiumUntilMs`, evaluated on read, never cached as a boolean. |
| Clock moved forward to extend a grant | Accepted. The threat model is a child, not an attacker; and a device-local clock change can already defeat the night lock. |
| Renewal loop drains battery | Once a day, one tiny document per paired device. |
| Grant lost on reinstall | Re-granted within a day. Local enforcement never depended on it. |

---

## 6. Firebase console step

**Publish the updated `firestore.rules`** — it now includes `grants/{pairingId}`. Until then the parent's
write fails `PERMISSION_DENIED` and no grant ever arrives.

The staged `firestore.rules.phase7` has the same collection, so Phase 7 needs no extra work here.

---

## 7. Implementation log

Build green. **286 tests, 0 failures** (267 before).

| Piece | File |
|---|---|
| `PremiumGrant`, codec, pure validity + renewal | `remote/PremiumGrant.kt` |
| Tests | `remote/PremiumGrantTest.kt` |
| Firestore access | `remote/GrantRepository.kt` |
| Cached expiry | `DataStoreManager.premiumGrantUntilMs` |
| Entitlement | `Entitlements`, `EntitlementRepository` |
| Parent daily renewal + child listener | `AppLockForegroundService` |
| Rules | `firestore.rules`, `firestore.rules.phase7` |

### Still client-trusted

`isPremium` is decided on a device the user controls, and so is the grant. Someone rooting a phone can
fake either. For a ₹59/month family app that population is small and wasn't going to pay — but it is the
reason a backend eventually matters, far more than the expiry dates: a Cloud Function with a Play service
account would validate receipts server-side and remove both holes at once.

**Not device-tested.**
