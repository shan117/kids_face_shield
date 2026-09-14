# Firebase Remote Config — setup (the runtime on/off switch)

> ✅ **STATUS: DONE / APPLIED.** Project `app-shield-1e78f`, app `com.appsecure.shield`. Steps 1–5
> below are complete and live (`FirebaseRemoteConfigSource` bound, 6 params seeded, build green). This
> doc now serves as the **record of how it's wired** + the **go-paid operating procedure (§6)**.

This wires **Firebase Remote Config** so you can flip free↔paid **without an app update**
(`promo_active` etc., per `PREMIUM_FEATURE_PLAN.md` §3). _(Original note, now satisfied — kept for
context.)_ It was deferred until the build was ready because:

1. `google-services.json` is **keyed to your final `applicationId`** — finalize that first.
2. You don't have a Firebase project / account yet.
3. The current free build doesn't need it (Remote Config matters at **monetization**, which is deferred).

Everything below is ready to apply in ~10 minutes once you have a Firebase project. The app already
has the entitlement layer (`RemoteConfigSource` + `LocalDefaultsConfigSource`); this just adds a
Firebase-backed implementation and swaps one Hilt binding.

> **Prerequisite:** decide your final `applicationId` (`app/build.gradle.kts`). It's immutable on Play
> after publish and is what the Firebase Android app is registered against.

---

## Step 1 — Firebase Console

1. Create a Firebase project (console.firebase.google.com).
2. **Add an Android app** using your **final `applicationId`** as the package name.
3. **Disable Google Analytics / ad-id** for the project (PREMIUM D13 — analytics-free).
4. Download **`google-services.json`** → place in `app/` (it's gitignored; add it locally).
5. Repos already allow the plugin/libs (`settings.gradle.kts` has `google()`), so no repo changes.

## Step 2 — Gradle (3 small edits)

**`gradle/libs.versions.toml`** — add:
```toml
[versions]
firebaseBom = "33.7.0"          # or latest
googleServices = "4.4.2"

[libraries]
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-config = { group = "com.google.firebase", name = "firebase-config" }

[plugins]
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

**`build.gradle.kts`** (root) — add to the `plugins { }` block:
```kotlin
alias(libs.plugins.google.services) apply false
```

**`app/build.gradle.kts`** — apply the plugin (in `plugins { }`) and add the deps:
```kotlin
plugins {
    // …existing…
    alias(libs.plugins.google.services)
}

dependencies {
    // …existing…
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
}
```

> ⚠️ Applying `google-services` makes the build **require `google-services.json`** — add the file
> (Step 1) before syncing, or the build fails by design.

## Step 3 — Add the Firebase source

Create `app/src/main/java/com/shantanu/shield/premium/FirebaseRemoteConfigSource.kt`:

```kotlin
package com.shantanu.shield.premium

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Remote Config implementation of [RemoteConfigSource]. Ships the same in-app defaults
 * (promo on → everything free) so the app is fully functional before the first fetch, then updates
 * the flow when a fetch activates. The "go paid" flip is a console change, no app update.
 */
@Singleton
class FirebaseRemoteConfigSource @Inject constructor() : RemoteConfigSource {

    private val rc: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance().apply {
        setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600) // ~1h; Remote Config isn't instant by design
                .build()
        )
        setDefaultsAsync(
            mapOf(
                "promo_active" to true,
                "free_until_epoch_ms" to 0L,
                "paywall_enabled" to false,
                "price_tier" to "default",
                "feature_tiers_json" to "",
                "paywall_variant" to "a",
            )
        )
    }

    private val state = MutableStateFlow(read())
    override val config: Flow<PaywallConfig> = state.asStateFlow()

    init { rc.fetchAndActivate().addOnCompleteListener { state.value = read() } }

    override suspend fun refresh() {
        rc.fetchAndActivate().addOnCompleteListener { state.value = read() }
    }

    private fun read(): PaywallConfig = PaywallConfig(
        promoActive = rc.getBoolean("promo_active"),
        freeUntilEpochMs = rc.getLong("free_until_epoch_ms"),
        paywallEnabled = rc.getBoolean("paywall_enabled"),
        priceTier = rc.getString("price_tier").ifBlank { "default" },
        premiumFeatures = parseFeatureTiers(rc.getString("feature_tiers_json")) ?: Feature.DEFAULT_PREMIUM,
        paywallVariant = rc.getString("paywall_variant").ifBlank { "a" },
    )

    // feature_tiers_json e.g. {"THEMES":"free","MULTI_KID_PROFILES":"premium"}. Unlisted → default map.
    private fun parseFeatureTiers(json: String): Set<Feature>? {
        if (json.isBlank()) return null
        return runCatching {
            val obj = org.json.JSONObject(json)
            val out = Feature.DEFAULT_PREMIUM.toMutableSet()
            for (f in Feature.entries) {
                if (obj.has(f.name)) {
                    if (obj.getString(f.name).equals("premium", true)) out.add(f) else out.remove(f)
                }
            }
            out
        }.getOrNull()
    }
}
```

## Step 4 — Swap the Hilt binding (one line)

In `app/src/main/java/com/shantanu/shield/di/PremiumModule.kt`:
```kotlin
// from:
abstract fun bindConfigSource(impl: LocalDefaultsConfigSource): RemoteConfigSource
// to:
abstract fun bindConfigSource(impl: FirebaseRemoteConfigSource): RemoteConfigSource
```
Nothing else changes — `EntitlementRepository` and every consumer keep working. (Keep
`LocalDefaultsConfigSource` in the codebase as the offline fallback / quick revert.)

## Step 5 — Seed the Remote Config keys (Firebase Console → Remote Config)

| Parameter | Type | Launch value |
|---|---|---|
| `promo_active` | Boolean | `true` |
| `free_until_epoch_ms` | Number | `0` |
| `paywall_enabled` | Boolean | `false` |
| `price_tier` | String | `default` |
| `feature_tiers_json` | String | `{}` (uses the shipped default map) |
| `paywall_variant` | String | `a` |

**Publish** the config. With these values the app stays **fully free** — identical to today.

## Step 6 — The go-paid flip (later, no app update)

When billing + the SFace license are done (`PLAYSTORE_RELEASE_PLAN.md` R6–R7):
1. `paywall_enabled = true`, then `promo_active = false` (+ `feature_tiers_json` if customizing).
2. **Publish.** Propagates within the fetch window (~1h here) — not instant. No app update, no re-review.
3. Revert anytime: `promo_active = true`.

## Verify
- App builds + runs with `google-services.json` present.
- With launch values, behavior is unchanged (everything free).
- Temporarily set `promo_active=false` + `paywall_enabled=true` in the console, force-fetch, and
  confirm the paywall surfaces — then set it back.
