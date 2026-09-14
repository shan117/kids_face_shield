# Play Store Release & Runtime-Config Plan

Operational source of truth for shipping **Shield** (`com.appsecure.shield`) to Google Play and for
the **runtime on/off switch** (free promo ↔ paid). Complements `PREMIUM_FEATURE_PLAN.md` (§0 owner
setup, §9 go-paid flip) with the concrete store steps. Update when rules change.

> This app trips almost every sensitive-permission review flag. **Most launch risk is policy review,
> not engineering.** Read §3 first.

---

## Live progress (where we actually are)

- **applicationId:** `com.appsecure.shield` — set & locked in (`app/build.gradle.kts`).
- **Firebase:** ✅ **DONE** — project `app-shield-1e78f` (Analytics off), Android app `com.appsecure.shield`
  registered, `google-services.json` in `app/` (gitignored), `FirebaseRemoteConfigSource` live (Hilt
  binding swapped), **6 Remote Config params seeded + published** (`promo_active=true` → app fully free).
  Network build green. See `FIREBASE_SETUP.md`.
- **Play Developer account:** ⏳ **identity verification in progress** (console limited until it clears).
- **Upload keystore:** ⏳ in progress. `keytool` is at
  `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe` (not on PATH — call via `& "…keytool.exe"`).
  Release build auto-signs once `keystore.properties` + `upload-keystore.jks` exist at the project root.
- **Next:** finish keystore → build first signed AAB → (after verification) create the Play app → internal testing.

---

## 1. Two consoles — know which does what

People conflate "Google console." There are **two**, and they control different things:

| Console | URL | Controls |
|---|---|---|
| **Google Play Console** | play.google.com/console | Store listing, AAB upload, test tracks & rollout, content rating, **Data Safety**, **permission declarations**, **subscriptions/billing**, license testers |
| **Firebase Console** | console.firebase.google.com | **Remote Config** = the `promo_active` / `paywall_enabled` **on/off flags** (the runtime free↔paid switch), `google-services.json` |

**The "turn paid on/off without an app update" magic is Firebase Remote Config, not Play Console.**
And it only works **after** Firebase is wired into the app (✅ **now wired** — see §9).

## 2. Where the app stands today (facts from the repo)

- `applicationId = com.appsecure.shield` (⚠ **immutable after first publish** — confirm before upload). `namespace` stays `com.shantanu.shield`.
- `versionCode = 1`, `versionName = "1.0"`; `minSdk 24`, `targetSdk 35` (meets Play's current new-app target-SDK bar; Play raises this yearly).
- `release { isMinifyEnabled = false }` — **no R8/shrinking** yet (see §6).
- **Signing config wired** (guarded by `keystore.properties`), **Firebase wired** (Remote Config + Messaging + Firestore), **Play Billing dep present** (`billing.ktx`; runtime flow dormant by design until §8).
- Sensitive surface already declared: `CAMERA`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_CAMERA`,
  `FOREGROUND_SERVICE_SPECIAL_USE` (subtype "App locking and security service"), `PACKAGE_USAGE_STATS`,
  `QUERY_ALL_PACKAGES`, Device Admin (`AdminReceiver` / `BIND_DEVICE_ADMIN`), `RECEIVE_BOOT_COMPLETED`,
  `POST_NOTIFICATIONS`.

## 3. Pre-flight policy blockers (the stuff that gets you rejected) ⚠️

Address each **before** uploading; each maps to a Play Console declaration or a policy.

| Risk | Why it's flagged | What to do |
|---|---|---|
| **`QUERY_ALL_PACKAGES`** | Forces a Permissions Declaration form; parental-control is a gray-zone-but-plausible use case. | **Try first** — declare it with a parental-control justification and submit (Internal testing reviews lightly). **Only if production review bounces it**, swap to a launcher `queryIntentActivities(MAIN/LAUNCHER)` + `<queries>` block (already audited — the code tolerates reduced visibility; the foreground app a kid opens is always launchable, and time-sinks are string-matched). |
| **`SYSTEM_ALERT_WINDOW`** (overlay) | Restricted; abused by malware. | Justify: the lock overlay is the core app-lock UX. Keep usage minimal + user-granted. |
| **`FOREGROUND_SERVICE_SPECIAL_USE`** | Android 14+ requires a Play **FGS declaration**; `specialUse` is the catch-all Google scrutinizes hardest. | Submit the FGS declaration with a clear justification + a demo video. Confirm `camera` FGS type is genuinely needed (it is, for silent face check). |
| **Device Admin (`BIND_DEVICE_ADMIN`)** | Anti-uninstall/tamper draws stalkerware scrutiny; many DeviceAdmin policies are deprecated. | Justify as parental-control tamper protection. Make it **optional** + clearly disclosed. Verify you only use still-allowed admin APIs. |
| **Monitoring / "stalkerware" policy** | Apps that monitor device use must prove they're legit parental control. | Persistent notification while running (you have the FGS notification ✅), **no hiding the app icon** (you don't ✅), honest store listing as parental control, EULA discloses monitoring. |
| **Deceptive "Hardware Error" overlay** | `TECHNICAL_DOC §5` shows **fake error screens** to deter bypass. Play prohibits **deceptive behavior**. | Review risk. Consider making parent-mode locks honest (like the kid-mode message) for the Play build, or be ready to justify. |
| **Camera + face data** | Sensitive personal/biometric data. | **Privacy Policy URL (required)** + Data Safety form. Your advantage: face data is **on-device only, never uploaded** — declare exactly that (see §5). |
| **Face-model license** | Shipped weights aren't confirmed commercial-licensed. | Fine for the **free** launch; **must resolve (swap to SFace) before charging** — see `FACE_MODEL_MIGRATION_PLAN.md` D8 + memory. |

## 4. One-time setup

- [ ] **Finalize `applicationId`** in `app/build.gradle.kts` — it's **permanent on Play** and is what `google-services.json` is keyed to. Decide **before** creating the Play app or Firebase project. (Changing it does not require touching `namespace`.)
- [ ] **Google Play Developer account** ($25 one-time), identity + (for paid) **bank/tax/Payments profile** verified — verification can take days, do it early.
- [ ] **Privacy Policy** hosted at a public URL (covers camera/face on-device, usage access, children's data, no off-device collection).
- [ ] **Upload keystore** — build wiring is **done** (`signingConfigs` reads `keystore.properties`, gitignored; template at `keystore.properties.example`). You just: run the `keytool` command in the template, create `keystore.properties`, back up the `.jks` + passwords. Enroll in **Play App Signing**.

## 5. Play Console app setup checklist

- [ ] **Create app** → name, default language, **App** (not Game), **Free** (you launch free; can't switch Paid→Free later, but Free + in-app subs is the right model anyway).
- [ ] **Store listing**: title, short + full description (frame as **parental control / screen-time**), icon, feature graphic, phone screenshots (+ tablet if supported).
- [ ] **Content rating** (IARC questionnaire) → likely Everyone/PEGI 3.
- [ ] **Target audience & content**: choose age groups. Because it's **parent-configured**, target adults; declare children's-data handling honestly. Decide Designed-for-Families participation deliberately (probably **not** child-directed).
- [ ] **Data safety**: declare **Camera/face = collected, processed on-device, NOT shared, NOT sent off device**. **App activity (screen-time)** = processed on-device; **and — only when the optional Remote Report is turned on — aggregate screen-time totals (no content, messages, location, or raw events) AND the list of installed app names (no usage tied to them beyond top-apps) are shared with the paired parent device, end-to-end encrypted in transit & at rest** (the relay holds ciphertext only and can't read it). Declare **"App activity → Installed apps"** as a shared type for the opt-in case, gated behind the in-app consent dialog. With Remote Report off (the default), nothing leaves the device. Mark "Data is encrypted in transit" and declare the app-activity *shared* category for the opt-in case.
- [ ] **App access**: face setup isn't a login, but give reviewers **instructions** (how to enrol, grant Usage Access + overlay) or they can't test it → rejection. Include a demo video.
- [ ] **Permissions declarations**: the §3 forms (QUERY_ALL_PACKAGES if kept, FGS special-use).
- [ ] **Ads**: declare none.

## 6. Build & sign the release AAB

- [ ] Decide **R8**: set `isMinifyEnabled = true` + `isShrinkResources = true` for size/obfuscation. **Test thoroughly** — add keep rules if TFLite/ML Kit/Hilt reflection breaks. (Optional but recommended; if risky near launch, ship un-minified first.)
- [x] `signingConfigs` wired (guarded) — release auto-signs when `keystore.properties` exists; otherwise use Android Studio → **Generate Signed Bundle → AAB**.
- [ ] Build **AAB** (`.aab`, not APK) — Play requires App Bundles.
- [ ] **Bump `versionCode`** on every upload (immutable rule).
- [ ] `noCompress += "tflite"` already set ✅ (model loads via mmap).

## 7. Release tracks & rollout (the safe ladder)

```
Internal testing  →  Closed testing  →  (Open testing)  →  Production
(you + testers)      (small group)       (optional beta)     (staged %)
```

- [ ] **Internal testing** first — instant, up to 100 testers; verify install + permissions flow + the **Pre-launch report** (Google runs it on real devices; catches crashes/policy issues).
- [ ] **Closed testing** — a real cohort; **Google now often requires a testing period before a personal account can publish to production** (e.g., N testers for N days). Plan for this lead time.
- [ ] **Production** — **staged rollout** (start ~10–20%), watch crash rate / ANR / reviews, then ramp.

## 8. Subscriptions & billing (for when you monetize)

Do this in Play Console **before** flipping paid, so the first price is clean (raising later needs Play's notify-and-consent flow):

- [x] Add **Play Billing Library** to the app (`billing.ktx`) + a real `BillingManager` — done, bound as `PremiumSource`, paywall wired (PREMIUM Phase 1b/8). ⚠ paste `PUBLIC_KEY` before charging.
- [ ] Create the **`premium` subscription** product → base plan(s) (monthly + annual) → **intro/discount offer** → **free trial** (your plan: 30-day).
- [ ] **Per-country pricing** — set **India = ₹59/mo** and **US = $2.00/mo** as **explicit, manual** prices on the base plan. ⚠️ **Do not rely on auto-convert for the US:** ₹59 ≈ $0.70, so auto-conversion from an INR base would under-price the US to ~$0.70, not $2 — override it (and any other key markets) by hand; auto-convert is only acceptable for markets where the exact figure doesn't matter. (The app needs **no code change** — the paywall already shows Play's localized `formattedPrice` per the user's Play-account country; see `PaywallViewModel.kt`.)
- [ ] **Price tiers** set; **license testers** added (test purchases without being charged).
- [ ] Verify the full flow on the internal track: trial → bill → cancel → **restore** → "billing unavailable" degrades gracefully.

## 9. Firebase + Remote Config (the on/off engine)

> **Full ready-to-apply steps + the `FirebaseRemoteConfigSource.kt` source: `FIREBASE_SETUP.md`.**
> ✅ **Wired** — project `app-shield-1e78f`, `google-services.json` in `app/`.

The runtime switch is **live** (PREMIUM Phase 0 owner + 1b code):

- [x] Create a **Firebase project**, add the Android app, download **`google-services.json`** into `app/`.
- [x] **Disable Google Analytics / ad-id** (your D13 — analytics-free).
- [x] Add `firebase-config` dep + the `google-services` Gradle plugin; implement `FirebaseRemoteConfigSource` and swap it in `di/PremiumModule.kt` (replaces `LocalDefaultsConfigSource`).
- [x] **Seed Remote Config keys** (PREMIUM §3): `promo_active=true`, `paywall_enabled=false`, `price_tier`, `feature_tiers_json`, `paywall_variant`, `free_until_epoch_ms` (6 params seeded + published).
- [x] Ship local defaults so the app works even if a fetch fails (you already do).

## 10. The "go-paid" runtime flip (no app update) — runbook

Once §8 + §9 are live, flipping the business model is **config only** (PREMIUM §9):

1. ✅ Confirm **face-model license resolved** (SFace shipped) — gate for charging.
2. In **Play Console**: confirm subscription prices/offers are live.
3. In **Firebase Remote Config**: set `paywall_enabled = true`, then `promo_active = false`, optionally `feature_tiers_json`.
4. **Publish** the Remote Config change. Propagates within the fetch window (~12h, tunable) — **not instant** (known limitation). Existing installs flip to paywall on next fetch; **no app update, no re-review.**
5. To roll back: set `promo_active = true`. Instant-ish, same fetch window.

> This is why the whole app is built around `isUnlocked(...)` + `promo_active`: the launch is free,
> and "going paid" is one console toggle — provided §8/§9 shipped first.

---

## Phase tracker (release milestones)

Legend: `[ ]` pending · `[~]` in progress · `[x]` done

| Phase | Milestone | Status | Notes |
|---|---|---|---|
| R0 | Pre-flight: account, privacy policy, keystore, resolve §3 declarations | `[~]` | `applicationId` = **`com.appsecure.shield`** (set); signing **wired** (`keystore.properties.example`). Remaining: account (verify early — slow), privacy policy, run keytool, §3 declarations. |
| R1 | Play Console app created; listing + content rating + Data Safety + declarations filled | `[ ]` | §5. |
| R2 | First AAB to **Internal testing**; pass Pre-launch report; reviewer instructions + demo video | `[ ]` | §6, §7. |
| R3 | **Closed testing** cohort (satisfy Play's testing-period requirement) | `[ ]` | Plan lead time. |
| R4 | **Production — FREE launch** (promo on, billing dormant) | `[ ]` | PREMIUM Phases 0–6. |
| R5 | Firebase + Remote Config wired (runtime on/off now possible) | `[x]` | **Done** — project `app-shield-1e78f`, `FirebaseRemoteConfigSource` live, 6 params seeded. Done early (out of order) since the user set it up now. |
| R6 | Billing + subscription products + trial + testers | `[~]` | **Code done** — `BillingManager` (full Play Billing flow) bound as `PremiumSource`, paywall wired. Remaining: Play Console `premium` product/plans/offers/trial, license testers, paste `PUBLIC_KEY` before charging. §8 / PREMIUM 8. |
| R7 | **Face-model license resolved (SFace swap)** — gate before charging | `[ ]` | `FACE_MODEL_MIGRATION_PLAN.md`. |
| R8 | **Go paid** via Remote Config flip (no app update) | `[ ]` | §10 / PREMIUM 9. |

## Likely rejection reasons (rank-ordered) + mitigations
1. **FGS special-use** weak justification → clear declaration + demo video. *(a hard form you can't skip)*
2. **QUERY_ALL_PACKAGES** — **try declaring it first** (parental-control justification); swap to a launcher query only if **production** review bounces it. Not an auto-reject.
3. **Reviewer can't exercise the app** (needs Usage Access + overlay grants) → step-by-step access instructions + video.
4. **Deceptive "Hardware Error" overlay** → make parent locks honest for the Play build or justify.
5. **Device Admin** scrutiny → make optional, disclosed, use only allowed APIs.
6. **Data Safety mismatch** → ensure the form matches reality: on-device only by default; the opt-in Remote Report shares **E2E-encrypted aggregate screen-time totals AND the list of installed app names** (declare both as shared + encrypted-in-transit; the app list is "App activity → Installed apps", gated by the in-app consent dialog).

## Cross-references
- `PREMIUM_FEATURE_PLAN.md` — §0 (Firebase/Play owner setup), §9 (go-paid), §11 (free/premium map).
- `FACE_MODEL_MIGRATION_PLAN.md` — D8: swap to SFace **before** charging (gate for R7/R8).
- Memory: *face-model-license-blocks-monetization*.
