package com.shantanu.shield.remote

import com.shantanu.shield.data.DataStoreManager
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parent side: build → encrypt → upload a single [RemoteCommand], gated on being the paired parent. Each
 * command gets a fresh id (idempotency) + timestamp (freshness). The key never leaves the device — the
 * relay only ever sees ciphertext. See PARENT_REMOTE_CONTROL_PLAN.md §5.
 */
@Singleton
class RemoteCommandSender @Inject constructor(
    private val repository: RemoteCommandRepository,
    private val dataStore: DataStoreManager,
) {
    enum class Result { SUCCESS, NOT_PARENT, NOT_PAIRED, FAILED }

    /**
     * Send to a specific linked child device.
     *
     * The target is an explicit parameter rather than a DataStore read inside this function: with more
     * than one child device linked, "the pairing" is no longer a property of the app — it is a property
     * of what the parent is looking at. Making the caller name it removes any chance of a command
     * reaching the wrong child (MULTI_DEVICE_PAIRING_PLAN.md §5, Phase 2).
     */
    suspend fun send(
        type: CommandType,
        arg: Int = 0,
        payload: String = "",
        target: PairedDevice?,
    ): Result {
        if (dataStore.remoteRole.first() != "parent") return Result.NOT_PARENT
        if (target == null || target.pairingId.isBlank() || target.keyHex.isBlank()) return Result.NOT_PAIRED

        val command = RemoteCommand(
            commandId = UUID.randomUUID().toString(),
            issuedAtMs = System.currentTimeMillis(),
            type = type,
            arg = arg,
            payload = payload,
        )
        val sealed = ReportCrypto.encrypt(RemoteCommandCodec.encode(command), target.pairing().keyBytes())
        return if (repository.write(target.pairingId, sealed)) Result.SUCCESS else Result.FAILED
    }
}
