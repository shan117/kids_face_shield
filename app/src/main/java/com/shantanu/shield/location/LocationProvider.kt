package com.shantanu.shield.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.shantanu.shield.remote.FixStatus
import com.shantanu.shield.remote.LocationFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Acquires ONE location fix, on demand, in answer to a parent request.
 *
 * Built on the platform [LocationManager] rather than the fused provider: `play-services-location` is
 * not a dependency of this project, and staying on the platform API keeps it that way. Accuracy and
 * time-to-fix are a little worse than fused; for "roughly where is my child" that is an acceptable
 * trade against adding a Play Services dependency.
 *
 * There is no continuous tracking, no geofencing and no background polling anywhere in this class.
 * Every fix costs one provider registration that is always removed — on success, on timeout and on
 * cancellation. See LOCATION_FEATURE_PLAN.md §5.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Try to get a fix, returning a [LocationFix] whatever happens.
     *
     * Never throws and never returns null: a failure is a recorded outcome with a reason, because the
     * parent must be able to tell "location is switched off" from "no signal" from "app is broken".
     */
    suspend fun acquire(requestedAtMs: Long): LocationFix {
        val battery = batteryPct()

        if (!hasPermission()) {
            return LocationFix.failure(FixStatus.PERMISSION_DENIED, requestedAtMs, battery)
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationFix.failure(FixStatus.LOCATION_DISABLED, requestedAtMs, battery)
        if (!isLocationEnabled(manager)) {
            return LocationFix.failure(FixStatus.LOCATION_DISABLED, requestedAtMs, battery)
        }

        val location = runCatching {
            withTimeout(FIX_TIMEOUT_MS) { awaitFix(manager) }
        }.getOrElse { error ->
            if (error !is TimeoutCancellationException) Log.w(TAG, "location request failed", error)
            null
        } ?: lastKnown(manager)   // a stale-but-real answer beats nothing at all

        return if (location == null) {
            LocationFix.failure(FixStatus.TIMEOUT, requestedAtMs, battery)
        } else {
            LocationFix(
                requestedAtMs = requestedAtMs,
                fixedAtMs = System.currentTimeMillis(),
                status = FixStatus.OK,
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
                batteryPct = battery,
            )
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(manager: LocationManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    /**
     * One fix from the first provider that answers.
     *
     * GPS and network are requested together rather than in sequence: indoors GPS may never fix while
     * network answers in a second, and trying them one at a time would spend the whole budget waiting
     * for the one that was never going to work.
     */
    @Suppress("MissingPermission")   // guarded by hasPermission() above
    private suspend fun awaitFix(manager: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

            if (providers.isEmpty()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            // Guarded so the first provider to answer wins and the rest are torn down exactly once.
            var settled = false
            lateinit var listener: LocationListener

            fun finish(result: Location?) {
                if (settled) return
                settled = true
                runCatching { manager.removeUpdates(listener) }
                if (cont.isActive) cont.resume(result)
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = finish(location)
                override fun onProviderDisabled(provider: String) { /* another may still answer */ }
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Required on API < 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            // Cancellation (timeout, or the service tearing down) must release the provider too —
            // a listener left registered is exactly the battery drain this design avoids.
            cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }

            runCatching {
                for (provider in providers) {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }.onFailure {
                Log.w(TAG, "requestLocationUpdates failed", it)
                finish(null)
            }
        }

    @Suppress("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? = runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun batteryPct(): Int = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
    }.getOrDefault(-1)

    private companion object {
        const val TAG = "ShieldLocation"
        /** Long enough for a cold GPS fix outdoors, short enough that a parent doesn't give up first. */
        const val FIX_TIMEOUT_MS = 45_000L
        /** A cached fix older than this is not worth showing as "current". */
        const val LAST_KNOWN_MAX_AGE_MS = 10 * 60 * 1000L
    }
}
