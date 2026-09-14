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

    suspend fun send(type: CommandType, arg: Int = 0, payload: String = ""): Result {
        if (dataStore.remoteRole.first() != "parent") return Result.NOT_PARENT
        val pairingId = dataStore.remotePairingId.first()
        val keyHex = dataStore.remotePairingKey.first()
        if (pairingId.isBlank() || keyHex.isBlank()) return Result.NOT_PAIRED

        val command = RemoteCommand(
            commandId = UUID.randomUUID().toString(),
            issuedAtMs = System.currentTimeMillis(),
            type = type,
            arg = arg,
            payload = payload,
        )
        val keyBytes = PairingManager.Pairing(pairingId, keyHex).keyBytes()
        val sealed = ReportCrypto.encrypt(RemoteCommandCodec.encode(command), keyBytes)
        return if (repository.write(pairingId, sealed)) Result.SUCCESS else Result.FAILED
    }
}
