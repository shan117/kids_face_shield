package com.shantanu.shield.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.remote.PairingManager
import com.shantanu.shield.remote.RemoteCommandRepository
import com.shantanu.shield.remote.RemoteReportRepository
import com.shantanu.shield.remote.RemoteReportSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the role choice + QR pairing for the Parent Remote Report. Holds NO secrets itself — the pairing
 * id/key live only in DataStore (and the QR). Sharing stays OFF after pairing; the opt-in toggle that
 * actually starts syncing is a later phase (D7). See PARENT_REMOTE_REPORT_PLAN.md §2.
 */
@HiltViewModel
class ParentSetupViewModel @Inject constructor(
    private val dataStore: DataStoreManager,
    private val sync: RemoteReportSync,
    private val entitlements: EntitlementRepository,
    private val reportRepository: RemoteReportRepository,
    private val commandRepository: RemoteCommandRepository,
) : ViewModel() {

    /** Sync cadence — "daily" or "weekly" (default). */
    val shareCadence: StateFlow<String> =
        dataStore.remoteShareCadence.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "weekly")

    fun setCadence(cadence: String) {
        viewModelScope.launch { sync.setCadence(cadence) }
    }

    /** Mint a fresh pairing (new id + key) so old uploaded ciphertext becomes permanently unreadable.
     *  Stays in the child role; the parent must re-scan the new QR. */
    fun rotateKey() {
        viewModelScope.launch {
            val pairing = PairingManager.newPairing()
            dataStore.setRemotePairing(pairing.pairingIdHex, pairing.keyHex)
        }
    }

    /** Whether Remote Report is unlocked (true during the promo; premium once it ends). Default-permissive
     *  so the toggle never flashes locked while entitlements resolve. */
    val remoteReportUnlocked: StateFlow<Boolean> =
        entitlements.isUnlocked(Feature.REMOTE_REPORT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** "none" / "child" / "parent". */
    val role: StateFlow<String> =
        dataStore.remoteRole.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "none")

    /** Kid Mode (ownerType == "kid") must be ON before a device can be set up as a child — a device only
     *  becomes a managed child once it's actually enforcing a budget. */
    val kidModeOn: StateFlow<Boolean> =
        dataStore.ownerType.map { it == "kid" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The QR string to show on the child device — null until a pairing has been minted. */
    val childQr: StateFlow<String?> =
        combine(dataStore.remotePairingId, dataStore.remotePairingKey) { id, key ->
            if (id.isNotBlank() && key.isNotBlank()) {
                PairingManager.encodeQr(PairingManager.Pairing(id, key))
            } else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Opt-in switch: while false, nothing is uploaded (the privacy invariant). */
    val shareEnabled: StateFlow<Boolean> =
        dataStore.remoteShareEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Last manual-sync outcome, for UI feedback; null until the user taps "Sync now". */
    private val _syncStatus = MutableStateFlow<RemoteReportSync.Result?>(null)
    val syncStatus: StateFlow<RemoteReportSync.Result?> = _syncStatus.asStateFlow()

    /** Turn weekly sharing on/off (also schedules/cancels the background worker). Enabling is gated on
     *  the premium entitlement; the UI also disables the switch when locked, this is the backstop. */
    fun setSharing(enabled: Boolean) {
        _syncStatus.value = null
        viewModelScope.launch {
            if (enabled && !remoteReportUnlocked.value) return@launch
            sync.setSharing(enabled)
        }
    }

    /** Upload a report right now (for verifying the pipe without waiting for the weekly job). */
    fun syncNow() {
        viewModelScope.launch { _syncStatus.value = sync.syncNow() }
    }

    /** Remote Control is a separate premium feature from the report (free during the promo). */
    val remoteControlUnlocked: StateFlow<Boolean> =
        entitlements.isUnlocked(Feature.REMOTE_CONTROL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Child opt-in for accepting parent→child commands; OFF by default (the privacy invariant). */
    val remoteControlEnabled: StateFlow<Boolean> =
        dataStore.remoteControlEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Turn command-acceptance on/off on the child. Enabling is gated on the premium entitlement. */
    fun setRemoteControl(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !remoteControlUnlocked.value) return@launch
            dataStore.setRemoteControlEnabled(enabled)
        }
    }

    // ---- Child-device lockdown (A1) — reuses the existing Tamper-Protection gate ----

    /** A parent face must be enrolled before the device can be caged (can't lock yourself out otherwise). */
    val faceEnrolled: StateFlow<Boolean> =
        dataStore.faceEmbedding.map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The cage = app-lock + system-settings-lock, both behind the parent face. When on, a child can't open
     *  Shield (so can't unpair / disable sharing / change anything) nor reach system Settings to undo it. */
    val deviceLockedDown: StateFlow<Boolean> =
        combine(dataStore.lockOwnApp, dataStore.lockDeviceSettings, dataStore.faceEmbedding) { app, settings, face ->
            app && settings && face != null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Individual cage components, surfaced as a status checklist (A3) so the parent sees exactly what is
     *  sealed. Device Admin isn't here — it's a system state read straight from `TamperProtection` in the UI. */
    val appLocked: StateFlow<Boolean> =
        dataStore.lockOwnApp.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val settingsLocked: StateFlow<Boolean> =
        dataStore.lockDeviceSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Enable/disable the cage. Enabling requires a face enrolled. Reuses the proven `lock_own_app` /
     *  `lock_device_settings` gates — opening Shield + system Settings then demands the parent's face. */
    fun setDeviceLockdown(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && dataStore.faceEmbedding.first() == null) return@launch
            dataStore.setLockOwnApp(enabled)
            dataStore.setLockDeviceSettings(enabled)
        }
    }

    /** Become the child device: mint a pairing once (id + E2E key), then adopt the child role. */
    fun becomeChild() {
        viewModelScope.launch {
            if (dataStore.remotePairingId.first().isBlank()) {
                val pairing = PairingManager.newPairing()
                dataStore.setRemotePairing(pairing.pairingIdHex, pairing.keyHex)
            }
            dataStore.setRemoteRole("child")
        }
    }

    /** Parent scanned the child's QR. Returns false for a foreign/garbled code (caller keeps scanning). */
    fun onParentScanned(qrText: String): Boolean {
        val pairing = PairingManager.parseQr(qrText) ?: return false
        viewModelScope.launch {
            dataStore.setRemotePairing(pairing.pairingIdHex, pairing.keyHex)
            dataStore.setRemoteRole("parent")
        }
        return true
    }

    /** Unpair / revoke: stop syncing, delete the cloud copies (report + commands), then wipe local keys. */
    fun reset() {
        viewModelScope.launch {
            val pairingId = dataStore.remotePairingId.first()
            sync.setSharing(false)              // cancel the worker + flag sharing off
            if (pairingId.isNotBlank()) {
                reportRepository.delete(pairingId)
                commandRepository.delete(pairingId)
            }
            dataStore.clearRemotePairing()
        }
    }
}
