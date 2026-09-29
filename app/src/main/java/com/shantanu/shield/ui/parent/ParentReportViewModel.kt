package com.shantanu.shield.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.remote.CommandType
import com.shantanu.shield.remote.ListenerBag
import com.shantanu.shield.remote.PairedDevice
import com.shantanu.shield.remote.PairedDevices
import com.shantanu.shield.remote.RemoteCommand
import com.shantanu.shield.remote.RemoteCommandRepository
import com.shantanu.shield.remote.RemoteCommandSender
import com.shantanu.shield.remote.RemoteReportCodec
import com.shantanu.shield.remote.RemoteReportPayload
import com.shantanu.shield.remote.RemoteReportRepository
import com.shantanu.shield.remote.ReportCrypto
import com.shantanu.shield.remote.ReportDoc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Parent side of the Remote Report + Remote Control. Fetches the ciphertext, decrypts it **on this device**
 * with the paired key, and decodes the aggregate payload (read); and sends bounded commands back to the
 * child (write). The relay never holds the key. See PARENT_REMOTE_REPORT_PLAN.md / PARENT_REMOTE_CONTROL_PLAN.md.
 */
@HiltViewModel
class ParentReportViewModel @Inject constructor(
    private val dataStore: DataStoreManager,
    private val repository: RemoteReportRepository,
    private val commandRepository: RemoteCommandRepository,
    private val commandSender: RemoteCommandSender,
    private val entitlements: EntitlementRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object NotPaired : State
        data object Empty : State                                   // paired, but no report uploaded yet
        data object Error : State                                   // fetch ok but decrypt/decode failed
        /** The child rotated its key — this pairing is dead and the parent must re-scan. */
        data object Revoked : State
        data class Loaded(val payload: RemoteReportPayload) : State
    }

    // ---- Multi-device (MULTI_DEVICE_PAIRING_PLAN.md) ----
    // Per-device report state, keyed by pairingId. Every entry starts at Loading, never Empty: a device
    // whose listener has not delivered its first snapshot yet has NOT told us it has no report, and
    // rendering "no report yet" in that gap is plan trap E.
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Every linked child device, in link order. Empty when this phone isn't a paired parent. */
    val devices: StateFlow<List<PairedDevice>> =
        dataStore.pairedDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The device the parent is currently viewing — resolved, so it is always a member or null. */
    val activeDevice: StateFlow<PairedDevice?> =
        combine(dataStore.pairedDevices, dataStore.activePairingId) { list, storedId ->
            PairedDevices.resolveActive(list, storedId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Report state for the active device. Drives the existing single-device UI unchanged.
     *
     * Built from the RAW DataStore flows rather than [devices] / [activeDevice], deliberately: those are
     * `stateIn` with an `emptyList()` / `null` seed, and an unresolved seed is indistinguishable from a
     * genuinely unpaired phone — a paired parent would flash "Not paired" on every open. Combining the
     * cold flows emits nothing until the real value lands, so `Loading` holds until we actually know.
     */
    val state: StateFlow<State> =
        combine(_states, dataStore.pairedDevices, dataStore.activePairingId) { states, list, storedId ->
            val active = PairedDevices.resolveActive(list, storedId)
            when {
                active == null -> State.NotPaired
                else -> states[active.pairingId] ?: State.Loading
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    fun selectDevice(pairingId: String) {
        viewModelScope.launch { dataStore.setActivePairingId(pairingId) }
    }

    fun renameDevice(pairingId: String, label: String) {
        viewModelScope.launch { dataStore.renamePairedDevice(pairingId, label) }
    }

    /**
     * Unlink ONE child device: delete its cloud documents, then drop it from the list.
     *
     * Deliberately does not call `clearRemotePairing()` — that wipes the role and the share/control
     * flags, which on a multi-device parent would silently take Remote Report down for the children
     * that remain (plan trap A). The role is only surrendered when the last device goes (invariant 8).
     */
    fun unpairDevice(pairingId: String) {
        viewModelScope.launch {
            if (pairingId.isBlank()) return@launch
            repository.delete(pairingId)
            commandRepository.delete(pairingId)
            dataStore.removePairedDevice(pairingId)
            _states.value = _states.value - pairingId
            if (dataStore.pairedDevices.first().isEmpty()) dataStore.setRemoteRole("none")
        }
    }

    /** Remote Control unlocked? (free during promo, premium after). Default-permissive while it resolves. */
    val controlUnlocked: StateFlow<Boolean> =
        entitlements.isUnlocked(Feature.REMOTE_CONTROL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** A send result together with the device it belongs to, so it is never shown against another child. */
    data class CommandOutcome(val pairingId: String, val result: RemoteCommandSender.Result)

    private val _commandStatus = MutableStateFlow<CommandOutcome?>(null)

    /** Last send outcome **for the device currently being viewed**; null until the parent taps a control
     *  on this device. Switching device clears it rather than carrying it across (plan trap C). */
    val commandStatus: StateFlow<RemoteCommandSender.Result?> =
        combine(_commandStatus, activeDevice) { outcome, active ->
            if (outcome != null && active != null && outcome.pairingId == active.pairingId) outcome.result else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One-shot per-action event for a snackbar (fires every send, even repeats — unlike the deduped status). */
    private val _commandEvent = MutableSharedFlow<RemoteCommandSender.Result>(extraBufferCapacity = 8)
    val commandEvent: SharedFlow<RemoteCommandSender.Result> = _commandEvent.asSharedFlow()

    private val listeners = ListenerBag()

    init {
        // One collector owns every subscription. It keys on the SET OF IDS rather than the device list
        // itself, so a rename or a reorder — which changes the list's identity but not which documents
        // we must watch — does not tear down and rebuild live listeners (plan trap D).
        viewModelScope.launch {
            dataStore.pairedDevices
                .map { devices -> devices.associateBy { it.pairingId } }
                .distinctUntilChanged { old, new -> old.keys == new.keys }
                .collect { byId ->
                    // Seed Loading for genuinely new devices before attaching, so the UI never shows
                    // "no report yet" for a device we simply haven't heard from (plan trap E).
                    _states.value = _states.value
                        .filterKeys { it in byId.keys }
                        .let { kept -> kept + byId.keys.filterNot { it in kept }.associateWith { State.Loading } }

                    listeners.sync(byId.keys) { id ->
                        val device = byId.getValue(id)
                        repository.listen(id) { sealed -> onSnapshot(device, sealed) }
                    }
                }
        }
    }

    /** Decrypt + decode one device's snapshot on this device. The relay never holds the key. */
    private fun onSnapshot(device: PairedDevice, snapshot: ReportDoc) {
        val next = when (snapshot) {
            is ReportDoc.Missing -> State.Empty
            is ReportDoc.Revoked -> State.Revoked
            is ReportDoc.Data -> {
                val payload = ReportCrypto.decrypt(snapshot.sealed, device.pairing().keyBytes())
                    ?.let { RemoteReportCodec.decode(it) }
                if (payload != null) State.Loaded(payload) else State.Error
            }
        }
        _states.value = _states.value + (device.pairingId to next)
    }

    override fun onCleared() {
        listeners.clear()
        super.onCleared()
    }

    /** Explicit one-shot fetch for the "Check again" button and the offline fallback. */
    fun refresh() {
        viewModelScope.launch {
            val device = activeDevice.value ?: PairedDevices.resolveActive(
                dataStore.pairedDevices.first(), dataStore.activePairingId.first()
            ) ?: return@launch
            _states.value = _states.value + (device.pairingId to State.Loading)
            onSnapshot(device, repository.read(device.pairingId))
        }
    }

    // ---- Remote Control: send bounded commands to the child ----
    fun lockNow(full: Boolean = false) =
        send(CommandType.LOCK_NOW, if (full) RemoteCommand.LOCK_FLAG_FULL else 0)
    fun unlock() = send(CommandType.UNLOCK)
    fun grantExtraTime(minutes: Int) = send(CommandType.GRANT_EXTRA_TIME, minutes)
    fun setDailyLimit(minutes: Int) = send(CommandType.SET_DAILY_LIMIT, minutes)
    // Phase-1 remote Kid Mode config (applied to the child's DataStore by RemoteCommandApplier). One discrete
    // command per setting (the relay is a single overwritten doc), so the parent changes one at a time.
    fun setChildAllowedPreset(preset: Int) = send(CommandType.SET_ALLOWED_PRESET, preset)
    fun setChildAutoBlockNewApps(on: Boolean) = send(CommandType.SET_AUTO_BLOCK, if (on) 1 else 0)
    // Tier-2: pick specific apps from the child's synced list.
    fun setChildCustomAllowed(packages: Set<String>) =
        send(CommandType.SET_CUSTOM_ALLOWED, payload = packages.joinToString(","))
    fun setChildPerAppLimit(pkg: String, minutes: Int) =
        send(CommandType.SET_PER_APP_LIMIT, arg = minutes, payload = pkg)

    /**
     * Every command is addressed to the device the parent is currently viewing — never to an implicit
     * "the pairing". The status is stamped with that device's id so a result can't be shown against a
     * different child after a switch (plan trap C).
     */
    private fun send(type: CommandType, arg: Int = 0, payload: String = "") {
        if (!controlUnlocked.value) return
        val target = activeDevice.value ?: return
        viewModelScope.launch {
            val result = commandSender.send(type, arg, payload, target = target)
            _commandStatus.value = CommandOutcome(target.pairingId, result)
            _commandEvent.emit(result)
        }
    }
}
