package com.shantanu.shield.remote

import com.shantanu.shield.data.DataStoreManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Child side: turn a received ciphertext into an applied command. Decrypt (= authenticate, D2) → decode →
 * idempotency/freshness gate (D4) → apply. Budget-affecting commands are applied here via DataStore; lock
 * commands are returned to the caller (the foreground service, which owns the lock UI). Gated on the child
 * opt-in — with Remote Control off, nothing applies. Never throws.
 */
@Singleton
class RemoteCommandApplier @Inject constructor(
    private val dataStore: DataStoreManager,
) {
    /**
     * Apply a received command. Returns the [CommandType] that needs a UI lock action (LOCK_NOW / UNLOCK)
     * for the caller to perform, or null when there's nothing for the caller to do — i.e. the command was
     * rejected (opt-out, forged, stale, duplicate) or was budget-only and already handled here.
     */
    suspend fun apply(sealed: Sealed): RemoteCommand? {
        if (!dataStore.remoteControlEnabled.first()) return null
        val pairingId = dataStore.remotePairingId.first()
        val keyHex = dataStore.remotePairingKey.first()
        if (pairingId.isBlank() || keyHex.isBlank()) return null

        val keyBytes = PairingManager.Pairing(pairingId, keyHex).keyBytes()
        val plaintext = ReportCrypto.decrypt(sealed, keyBytes) ?: return null
        val command = RemoteCommandCodec.decode(plaintext) ?: return null
        val lastId = dataStore.lastAppliedCommandId.first()
        if (!RemoteCommandGate.shouldApply(command, lastId, System.currentTimeMillis(), MAX_AGE_MS)) {
            return null
        }

        when (command.type) {
            CommandType.GRANT_EXTRA_TIME -> if (command.arg > 0) dataStore.addExtensionMinutes(command.arg)
            CommandType.SET_DAILY_LIMIT -> if (command.arg > 0) dataStore.setDailyLimitMinutes(command.arg)
            // Remote Kid Mode config — applied straight to DataStore (the child's UI + enforcement observe it).
            CommandType.SET_ALLOWED_PRESET -> if (command.arg in 0..2) dataStore.setAlwaysAllowedPreset(command.arg)
            CommandType.SET_AUTO_BLOCK -> dataStore.setAutoBlockNewApps(command.arg == 1)
            // Tier-2: the parent picked specific packages from the child's synced app list.
            CommandType.SET_CUSTOM_ALLOWED -> {
                val pkgs = command.payload.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                dataStore.setCustomAlwaysAllowed(pkgs)
                dataStore.setAlwaysAllowedPreset(2)   // switch to Custom so the picked set takes effect
            }
            CommandType.SET_PER_APP_LIMIT ->
                if (command.payload.isNotBlank()) dataStore.setPerAppLimit(command.payload.trim(), command.arg)
            CommandType.LOCK_NOW, CommandType.UNLOCK -> { /* lock UI handled by the caller */ }
        }
        dataStore.setLastAppliedCommandId(command.commandId)
        return command
    }

    companion object {
        // Don't act on a command older than this when a long-offline child finally reconnects.
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
