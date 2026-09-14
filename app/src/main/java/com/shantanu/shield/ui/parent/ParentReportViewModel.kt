package com.shantanu.shield.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.google.firebase.firestore.ListenerRegistration
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.remote.CommandType
import com.shantanu.shield.remote.PairingManager
import com.shantanu.shield.remote.RemoteCommand
import com.shantanu.shield.remote.RemoteCommandSender
import com.shantanu.shield.remote.RemoteReportCodec
import com.shantanu.shield.remote.RemoteReportPayload
import com.shantanu.shield.remote.RemoteReportRepository
import com.shantanu.shield.remote.ReportCrypto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val commandSender: RemoteCommandSender,
    private val entitlements: EntitlementRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object NotPaired : State
        data object Empty : State                                   // paired, but no report uploaded yet
        data object Error : State                                   // fetch ok but decrypt/decode failed
        data class Loaded(val payload: RemoteReportPayload) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Remote Control unlocked? (free during promo, premium after). Default-permissive while it resolves. */
    val controlUnlocked: StateFlow<Boolean> =
        entitlements.isUnlocked(Feature.REMOTE_CONTROL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Last command send outcome, for UI feedback; null until the parent taps a control. */
    private val _commandStatus = MutableStateFlow<RemoteCommandSender.Result?>(null)
    val commandStatus: StateFlow<RemoteCommandSender.Result?> = _commandStatus.asStateFlow()

    /** One-shot per-action event for a snackbar (fires every send, even repeats — unlike the deduped status). */
    private val _commandEvent = MutableSharedFlow<RemoteCommandSender.Result>(extraBufferCapacity = 8)
    val commandEvent: SharedFlow<RemoteCommandSender.Result> = _commandEvent.asSharedFlow()

    private var reportListener: ListenerRegistration? = null

    init { startListening() }

    /**
     * Live auto-refresh: subscribe to the report doc so the parent updates the instant the child uploads —
     * no manual "Check again" needed. Fires once on attach with the current value (replacing the old one-shot
     * load), then on every change. [refresh] stays for the explicit button + offline one-shot fallback.
     */
    private fun startListening() {
        viewModelScope.launch {
            val pairingId = dataStore.remotePairingId.first()
            val keyHex = dataStore.remotePairingKey.first()
            if (pairingId.isBlank() || keyHex.isBlank()) {
                _state.value = State.NotPaired
                return@launch
            }
            val keyBytes = PairingManager.Pairing(pairingId, keyHex).keyBytes()
            reportListener?.remove()
            reportListener = repository.listen(pairingId) { sealed ->
                if (sealed == null) {
                    _state.value = State.Empty
                } else {
                    val payload = ReportCrypto.decrypt(sealed, keyBytes)?.let { RemoteReportCodec.decode(it) }
                    _state.value = if (payload != null) State.Loaded(payload) else State.Error
                }
            }
        }
    }

    override fun onCleared() {
        reportListener?.remove()
        super.onCleared()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = State.Loading
            val pairingId = dataStore.remotePairingId.first()
            val keyHex = dataStore.remotePairingKey.first()
            if (pairingId.isBlank() || keyHex.isBlank()) {
                _state.value = State.NotPaired
                return@launch
            }
            val sealed = repository.read(pairingId)
            if (sealed == null) {
                _state.value = State.Empty
                return@launch
            }
            val keyBytes = PairingManager.Pairing(pairingId, keyHex).keyBytes()
            val plaintext = ReportCrypto.decrypt(sealed, keyBytes)
            val payload = plaintext?.let { RemoteReportCodec.decode(it) }
            _state.value = if (payload != null) State.Loaded(payload) else State.Error
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

    private fun send(type: CommandType, arg: Int = 0, payload: String = "") {
        if (!controlUnlocked.value) return
        viewModelScope.launch {
            val result = commandSender.send(type, arg, payload)
            _commandStatus.value = result
            _commandEvent.emit(result)
        }
    }
}
