package com.shantanu.shield.remote

import android.content.Context
import com.shantanu.shield.data.DataStoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the child-side sync: gather → encrypt → upload, but ONLY when the user has opted in and a
 * pairing exists. Also owns enabling/disabling the periodic worker so the opt-in switch and the schedule
 * can never drift apart. The privacy gate (D7) lives here: with sharing off, [syncNow] does nothing and
 * nothing leaves the device.
 */
@Singleton
class RemoteReportSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatherer: RemoteReportGatherer,
    private val repository: RemoteReportRepository,
    private val dataStore: DataStoreManager,
) {
    enum class Result { SUCCESS, DISABLED, NOT_CHILD, NOT_PAIRED, FAILED }

    /** Flip the opt-in switch and keep the (daily/weekly) schedule in lockstep with it. */
    suspend fun setSharing(enabled: Boolean) {
        dataStore.setRemoteShareEnabled(enabled)
        if (enabled) RemoteReportWorker.schedule(context, currentRepeatDays()) else RemoteReportWorker.cancel(context)
    }

    /** Change cadence ("daily"/"weekly"); reschedules the worker if sharing is currently on. */
    suspend fun setCadence(cadence: String) {
        dataStore.setRemoteShareCadence(cadence)
        if (dataStore.remoteShareEnabled.first()) RemoteReportWorker.schedule(context, currentRepeatDays())
    }

    private suspend fun currentRepeatDays(): Long =
        if (dataStore.remoteShareCadence.first() == "daily") 1L else 7L

    /** Build → encrypt → upload, respecting the opt-in/role/pairing gates. Never throws. */
    suspend fun syncNow(): Result {
        if (dataStore.remoteRole.first() != "child") return Result.NOT_CHILD
        if (!dataStore.remoteShareEnabled.first()) return Result.DISABLED
        val pairingId = dataStore.remotePairingId.first()
        val keyHex = dataStore.remotePairingKey.first()
        if (pairingId.isBlank() || keyHex.isBlank()) return Result.NOT_PAIRED

        val plaintext = RemoteReportCodec.encode(gatherer.build())
        val keyBytes = PairingManager.Pairing(pairingId, keyHex).keyBytes()
        val sealed = ReportCrypto.encrypt(plaintext, keyBytes)
        return if (repository.write(pairingId, sealed)) Result.SUCCESS else Result.FAILED
    }
}
