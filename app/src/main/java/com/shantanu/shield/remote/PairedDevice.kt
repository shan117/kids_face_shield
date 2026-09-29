package com.shantanu.shield.remote

/**
 * One child device this PARENT phone is linked to. See MULTI_DEVICE_PAIRING_PLAN.md §3.
 *
 * A child device never holds a list of these — it mints exactly one pairing (its own) and keeps using
 * the single-valued `remote_pairing_id` / `remote_pairing_key` keys. All multiplicity is parent-side,
 * which is what keeps the enforcement path untouched by this feature (plan §2).
 *
 * [pairingId] and [keyHex] are exactly the two secrets [PairingManager.Pairing] carries: the 128-bit
 * bearer id that is also the Firestore doc key, and the AES-256 key the relay never sees.
 */
data class PairedDevice(
    val pairingId: String,
    val keyHex: String,
    val label: String,
    val addedAtMs: Long,
) {
    /** The crypto pairing for this device, for [ReportCrypto] encrypt/decrypt. */
    fun pairing(): PairingManager.Pairing = PairingManager.Pairing(pairingId, keyHex)
}

/**
 * Pure list arithmetic for the paired-device list. Every rule that could silently corrupt a parent's
 * links lives here rather than in a ViewModel, so it is unit-testable with no Android, DataStore or
 * Firestore. Enforces invariants 2, 3 and 4 of the plan.
 */
object PairedDevices {

    /** Hard cap on linked devices — each one holds an open Firestore listener (plan trap I). */
    const val MAX_DEVICES = 5

    /**
     * Add [device], or REPLACE the existing entry with the same [PairedDevice.pairingId] in place.
     * Never produces two rows for one pairing id (invariant 2). Replacement keeps the original
     * position and, when the incoming label is blank, the original label.
     *
     * Returns the list unchanged when it is already at [MAX_DEVICES] and this is a new id.
     */
    fun add(list: List<PairedDevice>, device: PairedDevice): List<PairedDevice> {
        val existingIndex = list.indexOfFirst { it.pairingId == device.pairingId }
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            val merged = device.copy(
                label = device.label.ifBlank { existing.label },
                addedAtMs = existing.addedAtMs,
            )
            return list.toMutableList().also { it[existingIndex] = merged }
        }
        if (list.size >= MAX_DEVICES) return list
        return list + device
    }

    /** Remove by id. A missing id is a no-op, never an error. */
    fun remove(list: List<PairedDevice>, pairingId: String): List<PairedDevice> =
        list.filterNot { it.pairingId == pairingId }

    /** Rename by id. A blank label or a missing id is a no-op. */
    fun rename(list: List<PairedDevice>, pairingId: String, label: String): List<PairedDevice> {
        if (label.isBlank()) return list
        return list.map { if (it.pairingId == pairingId) it.copy(label = label.trim()) else it }
    }

    /**
     * Which device the parent is looking at (invariants 3 + 4): the stored id when it is still a
     * member, otherwise the first device, otherwise null. The ONLY place the stored active id is
     * interpreted — so a stale id can never point outside the list.
     */
    fun resolveActive(list: List<PairedDevice>, storedId: String): PairedDevice? {
        if (list.isEmpty()) return null
        return list.firstOrNull { it.pairingId == storedId } ?: list.first()
    }

    /**
     * One-time upgrade of a parent who paired before this feature existed: fold the legacy
     * single-valued keys into a one-element list.
     *
     * IDEMPOTENT (plan §4.3): a no-op when the list already holds anything, or when the legacy keys
     * are blank. Safe to call on every startup and safe to be killed halfway through — the caller
     * commits it in a single atomic DataStore edit.
     */
    fun migrate(
        legacyId: String,
        legacyKey: String,
        existing: List<PairedDevice>,
        label: String = DEFAULT_LABEL,
        nowMs: Long = System.currentTimeMillis(),
    ): List<PairedDevice> {
        if (existing.isNotEmpty()) return existing
        if (legacyId.isBlank() || legacyKey.isBlank()) return existing
        return listOf(PairedDevice(legacyId, legacyKey, label, nowMs))
    }

    const val DEFAULT_LABEL = "Child device"

    /**
     * Everything a write of [devices] implies for the stored keys, as data.
     *
     * Extracted from the DataStore write so the rules can be tested without Android: the legacy mirror
     * (invariant 9) is what keeps a pre-multi-device parent working across the migration, and an
     * untested load-bearing rule is a liability. The DataStore mutator's only job is to apply this.
     *
     * A null field means "remove this key", never "write an empty string" — a blank pairing id would
     * read as a real-but-broken pairing to the legacy call sites, where absent correctly reads as
     * unpaired.
     */
    fun keyStateFor(devices: List<PairedDevice>, storedActiveId: String): PairingKeyState {
        val first = devices.firstOrNull()
        return PairingKeyState(
            devices = devices,
            legacyId = first?.pairingId,
            legacyKey = first?.keyHex,
            activeId = resolveActive(devices, storedActiveId)?.pairingId,
        )
    }
}

/** The resolved key-by-key outcome of writing a device list. See [PairedDevices.keyStateFor]. */
data class PairingKeyState(
    val devices: List<PairedDevice>,
    /** Legacy `remote_pairing_id` mirror, or null to remove it. */
    val legacyId: String?,
    /** Legacy `remote_pairing_key` mirror, or null to remove it. */
    val legacyKey: String?,
    /** Resolved `remote_active_pairing`, or null to remove it. */
    val activeId: String?,
)

/**
 * Delimiter-based codec, matching the convention already used by [RemoteReportCodec],
 * `PerAppLimitCodec` and `KidProfileCodec` — no JSON dependency, pure, fully unit-testable.
 *
 * Positional and tolerant: a record with extra trailing fields still decodes (forward compatible),
 * and any record that cannot be parsed is dropped rather than failing the whole list. A corrupt
 * preference must never cost the parent every link.
 */
object PairedDeviceCodec {
    // Built from integer codes so no literal control character appears in source.
    private val US = Char(31)  // field separator within a device
    private val RS = Char(30)  // record separator between devices

    /** Labels are free user text; stripping the delimiters is what stops one label corrupting every
     *  record after it (plan trap J). Mirrors RemoteReportCodec.clean(). */
    private fun clean(s: String) = s.filterNot { it == US || it == RS }

    fun encode(devices: List<PairedDevice>): String =
        devices.joinToString(RS.toString()) { d ->
            listOf(
                clean(d.pairingId),
                clean(d.keyHex),
                clean(d.label),
                d.addedAtMs.toString(),
            ).joinToString(US.toString())
        }

    fun decode(raw: String): List<PairedDevice> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RS).mapNotNull { record ->
            if (record.isBlank()) return@mapNotNull null
            val f = record.split(US)
            if (f.size < 4) return@mapNotNull null
            val pairingId = f[0]
            val keyHex = f[1]
            if (pairingId.isBlank() || keyHex.isBlank()) return@mapNotNull null
            PairedDevice(
                pairingId = pairingId,
                keyHex = keyHex,
                label = f[2].ifBlank { PairedDevices.DEFAULT_LABEL },
                addedAtMs = f[3].toLongOrNull() ?: 0L,
            )
        }
    }
}
