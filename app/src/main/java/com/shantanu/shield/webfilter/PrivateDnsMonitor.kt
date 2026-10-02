package com.shantanu.shield.webfilter

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads — never writes — the device's Private DNS setting.
 *
 * Writing is impossible for a normal app (`WRITE_SECURE_SETTINGS` is privileged, and the
 * DevicePolicyManager route is Device Owner only), so this exists to do the three things that ARE
 * possible: verify the parent's setup took effect, keep that status live, and let the service notice
 * when filtering is switched off.
 */
@Singleton
class PrivateDnsMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** True when this Android version can report Private DNS at all (API 29+). */
    val verificationSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** One-shot read of the current state. Never throws. */
    fun current(): PrivateDnsState {
        if (!verificationSupported) return PrivateDnsState.Unsupported
        return runCatching {
            val cm = connectivity ?: return PrivateDnsState.Unsupported
            val network = cm.activeNetwork ?: return PrivateDnsState.Off
            read(cm.getLinkProperties(network))
        }.getOrElse {
            Log.w(TAG, "couldn't read Private DNS state", it)
            PrivateDnsState.Unsupported
        }
    }

    private fun read(lp: LinkProperties?): PrivateDnsState {
        if (lp == null) return PrivateDnsState.Off
        // Guarded by verificationSupported; both members are API 29+.
        return PrivateDnsStates.of(
            supported = true,
            active = lp.isPrivateDnsActive,
            hostname = lp.privateDnsServerName,
        )
    }

    /**
     * Live state, so the setup card updates the instant the parent saves the hostname — no "pull to
     * refresh", no stale "not set up" while it's plainly working.
     *
     * Emits once on collection, then on every network/link change. `distinctUntilChanged` keeps the
     * chatty link updates Android delivers from waking collectors for nothing.
     */
    val state: Flow<PrivateDnsState> = callbackFlow {
        if (!verificationSupported) {
            trySend(PrivateDnsState.Unsupported)
            awaitClose { }
            return@callbackFlow
        }
        val cm = connectivity
        if (cm == null) {
            trySend(PrivateDnsState.Unsupported)
            awaitClose { }
            return@callbackFlow
        }

        trySend(current())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(read(linkProperties))
            }
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
        }

        val registered = runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }.isSuccess
        if (!registered) Log.w(TAG, "registerNetworkCallback failed; status will not update live")

        awaitClose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private companion object { const val TAG = "ShieldWebFilter" }
}
