# Testing purchases without paying — Play license testing

Reference for exercising subscriptions, premium gating and entitlement sharing before charging anyone.

Product: one subscription `premium` with **monthly** and **annual** base plans.

---

## 1. Why this matters more than it looks

Premium code only runs when `promo_active=false` **and** someone has actually subscribed. Today neither
is true, so the entire paid path — paywall purchase flow, `isPremium`, revocation, and the whole
entitlement-sharing lease — has **never executed on a device**.

Code that first runs on the day you start charging is code that breaks on the day you start charging.
License testing is how that gets exercised beforehand, for free.

---

## 2. One-time setup

### a. The app must be on a Play track

Billing does not work with a sideloaded APK. The app has to be uploaded to at least **Internal testing**,
and the `applicationId` and signing must match what Play has.

Internal testing is enough — no review wait, up to 100 testers.

### b. Add license testers

**Play Console → Setup → License testing**

Add the Gmail accounts that should get free test purchases. These are account-level, so they apply across
every track.

### c. The device must be signed into that account

The Google account on the phone must be one of the license testers. If the device has several accounts,
the one Play Store is signed into is the one that counts.

### d. Create the subscription products

**Play Console → Monetize → Subscriptions** → product id `premium`, with two base plans (monthly,
annual). Must be **active**, or `queryProductDetails` returns nothing and the paywall shows no plans.

---

## 3. Accelerated test timings

Test subscriptions renew far faster than real ones, which is what makes the lease testable at all:

| Real period | Test renewal |
|---|---|
| 1 week | 5 minutes |
| **1 month** | **5 minutes** |
| 3 months | 10 minutes |
| 6 months | 15 minutes |
| **1 year** | **30 minutes** |

Other states are compressed too:

| Feature | Test duration |
|---|---|
| Free trial | 3 minutes |
| Grace period | 5 minutes |
| Account hold | 10 minutes |
| Pause (1 month) | 5 minutes |

A test subscription auto-renews a limited number of times and then expires on its own — useful for
watching the lapse path without waiting.

⚠️ **The 7-day grant lease is NOT accelerated.** Only Play's own timers compress; `PremiumGrants.LEASE_MS`
is our constant. To test expiry, either temporarily shorten it in a debug build or move the device clock
forward — see §5.

---

## 4. Test matrix

### A. Purchase works at all

1. Remote Config: `promo_active=false`, `paywall_enabled=true`, publish
2. Force-stop and reopen the app (config refetches on launch)
3. Settings → any premium section → **Unlock with Plus**
4. Paywall should show real monthly/annual prices from Play — **not** the promo screen
5. Buy. The dialog should say it's a test purchase and charge nothing

**Expect:** premium features unlock immediately on that device.

### B. Restore works

Clear app data, reopen, Paywall → **Restore**.
**Expect:** *"Purchases restored — Plus is active."* Not *"No previous purchases found."*

### C. Premium gating is honest

With `promo_active=false` and **no** purchase, check each premium surface shows its paywall card:
earned time, schedules, per-app limits, auto-block, themes, remote report, remote control, location
history.

Then check the four **enforcement-only** features show a red `LapseBanner` rather than failing silently
— set `feature_tiers_json` to `{"KID_BUDGET":"premium","APP_LOCK":"premium","TAMPER":"premium"}` and
confirm Kid Mode, Protect and Tamper Protection each warn. Revert afterwards.

### D. Entitlement sharing — the one that has never run

This is the point of the exercise. Needs a parent device and at least one paired kid device.

1. `promo_active=false`, published
2. Buy the subscription **on the parent device only**
3. On the parent: `adb logcat -s AppLock | grep -i grant`
   → expect `premium grants renewed for N device(s)`
4. Firebase console → a **`grants`** collection appears, one doc per linked child, each with `iv` +
   `ciphertext`
5. On the **kid's** phone: `adb logcat -s AppLock | grep -i grant`
   → expect `premium grant adopted, valid until …`
6. **On the kid's phone, confirm a premium feature actually works** — per-app limits or schedules
   enforcing, with no purchase on that device

**Step 6 is the whole test.** If it fails, a paying parent with two kid phones gets almost nothing, which
is the failure this feature exists to prevent.

### E. Revocation

1. Cancel the test subscription (Play Store → Subscriptions, or let it auto-expire)
2. Parent's `isPremium` → false; renewals stop
3. The kid keeps premium until the lease runs out — **~7 days in real time**, so use §5 to see it

**Expect:** premium features lock on the kid's phone, and the enforcement ones show the lapse banner
rather than going quiet.

---

## 5. Testing the 7-day lease without waiting 7 days

Pick one:

**Shorten the constant** in a debug build — `PremiumGrants.LEASE_MS` to a few minutes. Cleanest, since it
exercises the real code path. Revert before shipping; a test pins the value at 7 days, so it fails loudly
if the change is forgotten.

**Move the device clock forward** past the grant expiry on the kid's phone. No code change, but also
moves the night-lock window and the budget-day boundary, so expect side effects.

---

## 6. Gotchas

| Symptom | Cause |
|---|---|
| Paywall shows no plans | Subscription not active in Console, or app not on a track, or `applicationId`/signing mismatch |
| "Item not available" | Device's Play account isn't a license tester |
| Still shows the promo screen | `promo_active` is still true, or the app hasn't refetched — force-stop and reopen |
| Purchase succeeds, premium stays locked | Purchase not acknowledged; check `acknowledgeIfNeeded` in `BillingManager` |
| No `grants` collection | `firestore.rules` not published with the `grants` block, or parent isn't premium, or no linked devices |
| Grant written but kid unaffected | Kid's role isn't `child`, or the grant won't decrypt — check `premium grant won't decrypt` in its log |

**Don't test billing on the Realme child device** — app logcat is suppressed there, so failures are
invisible. Use it as the paired child and read the logs on the other phone.

---

## 7. Before flipping to paid for real

- [ ] Purchase, restore, and cancel all work (A, B, E)
- [ ] Every premium surface shows a paywall card, not a dead control (C)
- [ ] The four enforcement features show the lapse banner (C)
- [ ] **A kid device works on the parent's purchase alone** (D step 6)
- [ ] Revocation reaches the kid device after the lease (E)
- [ ] `promo_active` back to `true` and `feature_tiers_json` reverted after testing

---

## Sources

- [Test your Google Play Billing Library integration](https://developer.android.com/google/play/billing/test)
- [Faster Renewals for Test Subscriptions](https://android-developers.googleblog.com/2018/01/faster-renewals-for-test-subscriptions.html)
