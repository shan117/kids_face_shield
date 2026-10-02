package com.shantanu.shield.location

import android.util.Log
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.remote.FixStatus
import com.shantanu.shield.remote.LocationDoc
import com.shantanu.shield.remote.LocationFix
import com.shantanu.shield.remote.LocationFixCodec
import com.shantanu.shield.remote.LocationLog
import com.shantanu.shield.remote.LocationRepository
import com.shantanu.shield.remote.PairingManager
import com.shantanu.shield.remote.ReportCrypto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Child side of a location request: decide → acquire → append → upload.
 *
 * Every path ends in an uploaded [LocationFix], including every refusal. A parent who taps "Locate"
 * and gets silence cannot tell a switched-off setting from a dead battery from a broken app, so
 * "no answer" is not an outcome this class is allowed to produce.
 *
 * The consent check is deliberately FIRST and lives on this device. It is not derived from the
 * parent's entitlement or from anything the parent can set — see LOCATION_FEATURE_PLAN.md §10.
 */
@Singleton
class LocationResponder @Inject constructor(
    private val dataStore: DataStoreManager,
    private val provider: LocationProvider,
    private val repository: LocationRepository,
) {
    /**
     * Answer one request. [onFixShared] fires only when coordinates actually left the device, so the
     * caller can tell the child their location was shared — a refusal is not something to notify about.
     *
     * Returns the recorded fix, or null when there was no pairing to answer on. Never throws.
     */
    suspend fun respond(requestedAtMs: Long, onFixShared: () -> Unit = {}): LocationFix? {
        val pairingId = dataStore.remotePairingId.first()
        val keyHex = dataStore.remotePairingKey.first()
        if (pairingId.isBlank() || keyHex.isBlank()) return null

        // Consent first, and never a silent drop: the parent is told "not consented" rather than left
        // waiting, and no location work happens at all.
        val fix = if (!dataStore.locationSharingEnabled.first()) {
            LocationFix.failure(FixStatus.NOT_CONSENTED, requestedAtMs)
        } else {
            provider.acquire(requestedAtMs)
        }

        val uploaded = upload(pairingId, keyHex, fix)
        if (uploaded && fix.isUsable) onFixShared()
        return fix
    }

    /** Read → append → cap → encrypt → write. Read-modify-write is safe: only the child ever writes. */
    private suspend fun upload(pairingId: String, keyHex: String, fix: LocationFix): Boolean {
        val pairing = PairingManager.Pairing(pairingId, keyHex)
        val keyBytes = pairing.keyBytes()

        val existing = when (val doc = repository.read(pairingId)) {
            is LocationDoc.Missing -> emptyList()
            is LocationDoc.Data ->
                ReportCrypto.decrypt(doc.sealed, keyBytes)?.let { LocationFixCodec.decode(it) }
                    ?: emptyList()   // undecryptable (e.g. left over from an old key) — start fresh
        }

        val next = LocationLog.append(existing, fix)
        val sealed = ReportCrypto.encrypt(LocationFixCodec.encode(next), keyBytes)
        return repository.write(pairingId, sealed).also {
            if (!it) Log.w(TAG, "location upload failed (offline?) status=${fix.status}")
        }
    }

    private companion object { const val TAG = "ShieldLocation" }
}
