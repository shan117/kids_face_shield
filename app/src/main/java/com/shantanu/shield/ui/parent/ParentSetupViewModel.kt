package com.shantanu.shield.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.remote.PairedDevice
import com.shantanu.shield.remote.PairedDevices
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
    private val locationRepository: com.shantanu.shield.remote.LocationRepository,
    private val grantRepository: com.shantanu.shield.remote.GrantRepository,
    private val pairingRepository: com.shantanu.shield.remote.PairingRepository,
    private val requestRepository: com.shantanu.shield.remote.RequestRepository,
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
            // Retire the OLD documents before minting new ones.
            //
            // Rotation does not invalidate the old report: the child simply starts uploading to a new
            // document id, leaving the previous one intact and still decryptable with the key the parent
            // already holds. Without this the parent keeps showing a real, valid, permanently-frozen
            // report and is never told the pairing died. Revoking replaces that ciphertext with a marker
            // the parent can detect; the stale command doc goes too, since the child no longer listens
            // to it and anything sent there would silently vanish.
            val oldId = dataStore.remotePairingId.first()
            if (oldId.isNotBlank()) {
                reportRepository.revoke(oldId)
                commandRepository.delete(oldId)
                // Deleted, not revoked: a revocation marker is there to TELL the parent the link died,
                // and the report already carries that message. Coordinates have no reason to linger
                // once the key that authorised them is gone.
                locationRepository.delete(oldId)
                grantRepository.delete(oldId)
                pairingRepository.delete(oldId)
                requestRepository.delete(oldId)
            }
            val pairing = PairingManager.newPairing()
            dataStore.setRemotePairing(pairing.pairingIdHex, pairing.keyHex)
            // A new pairing id needs its own membership record, with a fresh claim window — the parent
            // has to re-scan after a rotation, and without this there would be nothing for them to
            // claim, so the new pairing would be unusable under the strict rules.
            pairingRepository.ensureMembership(pairing.pairingIdHex)
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

    /**
     * The QR string to show on the child device — null until a pairing has been minted.
     *
     * Explicitly null in the `parent` role. On a parent phone the legacy keys now mirror the FIRST
     * linked child's secrets, so without this guard a parent screen could render a QR carrying another
     * device's pairing key — and anyone who scanned it would be able to read that child's reports. The
     * UI only shows this in the child role today; the guard makes that a property of the data, not of
     * where the composable happens to be called from.
     */
    val childQr: StateFlow<String?> =
        combine(
            dataStore.remotePairingId, dataStore.remotePairingKey, dataStore.remoteRole
        ) { id, key, role ->
            if (role != "parent" && id.isNotBlank() && key.isNotBlank()) {
                PairingManager.encodeQr(PairingManager.Pairing(id, key))
            } else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Child: whether this device answers location requests. Separate from report sharing and from
     * remote control on purpose — one consent must not silently cover a far more sensitive one.
     *
     * Settable only here, on the child device. There is deliberately no command that lets a parent turn
     * this on remotely: that is the line between a family feature and a covert tracker.
     */
    val locationSharingEnabled: StateFlow<Boolean> =
        dataStore.locationSharingEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLocationSharing(enabled: Boolean) {
        viewModelScope.launch { dataStore.setLocationSharingEnabled(enabled) }
    }

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

    /**
     * Become the child device: mint a pairing once (id + E2E key), then adopt the child role.
     *
     * Mints fresh whenever linked child devices exist, because on a parent phone the legacy keys hold a
     * MIRROR of the first linked child's secrets — reusing them would make this device impersonate that
     * child. Unreachable today (dropping to zero devices clears the mirror, and this is only offered in
     * the "none" role), but the cost of being explicit is one condition and the failure mode is severe.
     */
    fun becomeChild() {
        viewModelScope.launch {
            val mirrorsAnotherChild = dataStore.pairedDevices.first().isNotEmpty()
            if (mirrorsAnotherChild || dataStore.remotePairingId.first().isBlank()) {
                val pairing = PairingManager.newPairing()
                dataStore.setRemotePairing(pairing.pairingIdHex, pairing.keyHex)
            }
            dataStore.setRemoteRole("child")
            // Record this device as the pairing's first member so the parent has something to claim.
            ensurePairingMembership()
        }
    }

    /**
     * Child: create or refresh the pairing's membership record.
     *
     * Called when a pairing is minted and again whenever the QR is on screen, so the claim window is
     * open exactly while someone is actually pairing. A child can mint a pairing, put the phone down,
     * and be scanned an hour later — a window fixed at creation would have closed by then and left the
     * pairing permanently unclaimable. Idempotent; stops extending once two devices are recorded.
     */
    fun ensurePairingMembership() {
        viewModelScope.launch {
            val pairingId = dataStore.remotePairingId.first()
            if (pairingId.isNotBlank()) pairingRepository.ensureMembership(pairingId)
        }
    }

    /** Outcome of scanning a child's QR, so the UI can report what actually happened. */
    sealed interface ScanResult {
        /** A new child device was linked. */
        data class Added(val label: String) : ScanResult
        /** This child was already linked; its code was refreshed in place. */
        data class Updated(val label: String) : ScanResult
        /** Already at [PairedDevices.MAX_DEVICES] — nothing was changed. */
        data object AtCapacity : ScanResult
    }

    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    fun consumeScanResult() { _scanResult.value = null }

    /**
     * One-shot latch for a scanning session.
     *
     * The camera analyzer has no debounce of its own — it calls back on EVERY frame it can decode, so
     * holding the phone over a QR delivers the same code ~30 times a second, on a background executor.
     * That was survivable when a scan only overwrote two keys with identical values, but linking now
     * mutates a list and can delete the device being replaced: a second pass would run after the first
     * had already removed the old entry, lose the label it was supposed to inherit, and race on the
     * capacity check. The first decodable frame wins; the rest are dropped until [beginScan].
     */
    private val scanHandled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Arm a fresh scanning session. Call whenever the camera is opened. */
    fun beginScan() {
        scanHandled.set(false)
        _scanResult.value = null
    }

    /** Every child device this parent phone is linked to. */
    val pairedDevices: StateFlow<List<PairedDevice>> =
        dataStore.pairedDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Parent scanned a child's QR. Returns false for a foreign/garbled code so the caller keeps scanning.
     *
     * Adds to the linked-device list rather than overwriting it. Before multi-device this silently
     * replaced the previous child, orphaning their reports with no warning — see
     * MULTI_DEVICE_PAIRING_PLAN.md §0. Re-scanning a child already linked refreshes their key in place
     * (invariant 2), which is what makes recovery from a key rotation work.
     */
    fun onParentScanned(qrText: String, label: String = "", replacing: String? = null): Boolean {
        // A frame that isn't a Shield code is the normal state while hunting for one — stay silent and
        // keep the latch open so scanning continues.
        val pairing = PairingManager.parseQr(qrText) ?: return false
        // Everything past here mutates state, so only the first decodable frame may proceed.
        if (!scanHandled.compareAndSet(false, true)) return true
        viewModelScope.launch {
            // Re-linking a child whose device minted a NEW pairing (the "rotate key" path): the incoming
            // id is genuinely different, so dedupe-by-id can't catch it and the parent would end up with
            // two rows for one phone (plan trap G). Retire the old entry — and its now-unreadable cloud
            // documents — and let the replacement inherit its name.
            val replacedLabel = if (replacing != null && replacing != pairing.pairingIdHex) {
                val old = dataStore.pairedDevices.first().firstOrNull { it.pairingId == replacing }
                if (old != null) {
                    // All five collections, not just these two. Location logs and grants outliving the
                    // pairing that authorised them is exactly the leak the other teardown paths were
                    // fixed for; this path was written before those collections existed.
                    reportRepository.delete(replacing)
                    commandRepository.delete(replacing)
                    locationRepository.delete(replacing)
                    grantRepository.delete(replacing)
                    pairingRepository.delete(replacing)
                    requestRepository.delete(replacing)
                    dataStore.removePairedDevice(replacing)
                }
                old?.label
            } else null

            val existing = dataStore.pairedDevices.first()
            val alreadyLinked = existing.any { it.pairingId == pairing.pairingIdHex }
            if (!alreadyLinked && existing.size >= PairedDevices.MAX_DEVICES) {
                _scanResult.value = ScanResult.AtCapacity
                return@launch
            }
            val resolvedLabel = label.ifBlank {
                replacedLabel ?: if (alreadyLinked) "" else "Child device ${existing.size + 1}"
            }
            dataStore.addPairedDevice(
                PairedDevice(
                    pairingId = pairing.pairingIdHex,
                    keyHex = pairing.keyHex,
                    label = resolvedLabel,
                    addedAtMs = System.currentTimeMillis(),
                )
            )
            // Claim membership of the pairing, so the strict rules recognise this device on the child's
            // documents (reports, locations) as well as its own. Done here because this is the one
            // moment both devices are demonstrably present — the child is holding up its QR.
            pairingRepository.claim(pairing.pairingIdHex)

            // Show the newly-linked child straight away.
            dataStore.setActivePairingId(pairing.pairingIdHex)
            dataStore.setRemoteRole("parent")
            val shownLabel = dataStore.pairedDevices.first()
                .firstOrNull { it.pairingId == pairing.pairingIdHex }?.label.orEmpty()
            _scanResult.value = when {
                alreadyLinked || replacedLabel != null -> ScanResult.Updated(shownLabel)
                else -> ScanResult.Added(shownLabel)
            }
        }
        return true
    }

    /**
     * Unpair / revoke EVERYTHING: stop syncing, delete every cloud copy (report + commands), then wipe
     * local keys.
     *
     * Deletes the docs for every linked device, not just the legacy mirror — otherwise a parent with
     * three children would leave two ciphertext documents behind after "unpair". To drop a single child
     * use `ParentReportViewModel.unpairDevice` instead (plan trap A).
     */
    fun reset() {
        viewModelScope.launch {
            sync.setSharing(false)              // cancel the worker + flag sharing off
            // Union of the device list (parent) and the legacy single pairing (child, or a parent whose
            // migration hasn't run yet) — so nothing is missed in either role.
            val ids = buildSet {
                dataStore.pairedDevices.first().forEach { add(it.pairingId) }
                dataStore.remotePairingId.first().takeIf { it.isNotBlank() }?.let { add(it) }
            }
            for (id in ids) {
                reportRepository.delete(id)
                commandRepository.delete(id)
                locationRepository.delete(id)
                grantRepository.delete(id)
                pairingRepository.delete(id)
                requestRepository.delete(id)
            }
            dataStore.clearRemotePairing()
        }
    }
}
