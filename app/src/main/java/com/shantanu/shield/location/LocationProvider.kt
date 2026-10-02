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
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.shantanu.shield.remote.FixStatus
import com.shantanu.shield.remote.LocationFix
import com.shantanu.shield.util.Diag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Is location switched on, on THIS device?
     *
     * Two independent signals, and an unknown answer means "try anyway". Reporting LOCATION_DISABLED
     * when the query merely failed is worse than attempting and timing out: that status sends the parent
     * to a setting on the child's phone that is already correct, so the real cause never gets found. An
     * enabled provider proves location is on whatever the master flag claims.
     */
    private fun isLocationEnabled(manager: LocationManager): Boolean {
        val master = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else null
        }.getOrNull()
        val anyProvider = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

        val enabled = when {
            master == true || anyProvider == true -> true
            master == false || anyProvider == false -> false
            else -> true   // both queries failed — let the fix attempt report the truth
        }
        Log.i(TAG, "location enabled check: master=$master providers=$anyProvider -> $enabled")
        Diag.event(TAG, "enabled master=$master providers=$anyProvider -> $enabled")
        return enabled
    }

    /**
     * One fix from the first provider that answers, acquired entirely OFF the main thread.
     *
     * Every provider is asked at once rather than in sequence: indoors GPS may never fix while fused or
     * network answers in a second, and trying them one at a time would spend the whole budget waiting
     * for the one that was never going to work.
     *
     * Callbacks are delivered on a private [HandlerThread], never on the main looper. This service
     * saturates the main thread — measuring that stall is the entire reason `Diag` exists — and a
     * location callback queued behind a backed-up main looper simply never arrives inside the budget.
     * The fix would be acquired and then thrown away, surfacing to the parent as "couldn't get a fix"
     * while the GPS was working perfectly.
     *
     * On API 30+ this uses the one-shot `getCurrentLocation`, which is built for exactly this ("where is
     * this phone, once") and will hand back a usable recent fix immediately instead of waiting for the
     * next hardware update. The listener path remains for older devices.
     */
    @Suppress("MissingPermission")   // guarded by hasPermission() above
    private suspend fun awaitFix(manager: LocationManager): Location? {
        val providers = candidateProviders()
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        Log.i(TAG, "requesting fix from providers=$providers")
        Diag.event(TAG, "providers=$providers")
        if (providers.isEmpty()) {
            Log.w(TAG, "no provider enabled despite location being on")
            return null
        }

        val thread = HandlerThread("ShieldLocationFix").apply { start() }
        return try {
            awaitFixOn(manager, providers, Handler(thread.looper))
        } finally {
            // The thread exists only for the duration of one fix; leaving it parked would be a thread
            // leak on a device this app is trying to keep alive for days.
            runCatching { thread.quitSafely() }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun awaitFixOn(
        manager: LocationManager,
        providers: List<String>,
        handler: Handler,
    ): Location? = suspendCancellableCoroutine { cont ->
        val settled = AtomicBoolean(false)
        /** Counts providers that answered with nothing, so "all failed" is distinct from "still trying". */
        val outstanding = AtomicInteger(providers.size)

        fun succeed(location: Location, cleanup: () -> Unit) {
            if (!settled.compareAndSet(false, true)) return
            runCatching(cleanup)
            Log.i(TAG, "fix from ${location.provider} accuracy=${location.accuracy}")
            Diag.event(TAG, "fix provider=${location.provider} acc=${location.accuracy}")
            if (cont.isActive) cont.resume(location)
        }

        fun failOne(cleanup: () -> Unit) {
            if (outstanding.decrementAndGet() > 0) return
            if (!settled.compareAndSet(false, true)) return
            runCatching(cleanup)
            Log.w(TAG, "every provider answered with no location")
            Diag.event(TAG, "all providers returned null")
            if (cont.isActive) cont.resume(null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            val executor = Executor { handler.post(it) }
            val cleanup = { signal.cancel() }
            cont.invokeOnCancellation { runCatching { signal.cancel() } }

            runCatching {
                for (provider in providers) {
                    manager.getCurrentLocation(provider, signal, executor) { location ->
                        if (location != null) succeed(location, cleanup) else failOne(cleanup)
                    }
                }
            }.onFailure {
                Log.w(TAG, "getCurrentLocation failed", it)
                if (settled.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            }
        } else {
            lateinit var listener: LocationListener
            val cleanup = { manager.removeUpdates(listener) }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = succeed(location, cleanup)
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
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, handler.looper)
                }
            }.onFailure {
                Log.w(TAG, "requestLocationUpdates failed", it)
                if (settled.compareAndSet(false, true)) {
                    runCatching(cleanup)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * Providers to ask, best first.
     *
     * FUSED is the one that answers indoors — it blends wifi and cell with no Play Services dependency
     * (platform API since 31). Without it the only indoor hope was NETWORK, which on several recent
     * devices is either absent or never answers, so a request from inside a house spent the whole 45 s
     * budget on a GPS fix that was never coming.
     */
    private fun candidateProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }

    @Suppress("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? = runCatching {
        val all = candidateProviders()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val newest = all.maxByOrNull { it.time }
        // Logged with its age because "no cached fix" and "cached fix just too old" send you to very
        // different places, and from the parent's phone both look identical.
        val age = newest?.let { System.currentTimeMillis() - it.time } ?: -1
        Log.i(TAG, "lastKnown candidates=${all.size} newestAgeMs=$age")
        Diag.event(TAG, "lastKnown n=${all.size} ageMs=$age")
        newest?.takeIf { System.currentTimeMillis() - it.time <= LAST_KNOWN_MAX_AGE_MS }
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
