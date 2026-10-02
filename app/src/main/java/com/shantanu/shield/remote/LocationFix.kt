package com.shantanu.shield.remote

/**
 * Why a location request ended the way it did. See LOCATION_FEATURE_PLAN.md §3.
 *
 * Failures are first-class values, not an absence of data: every request the child receives produces
 * exactly one entry, success or not. Without that the parent watches a spinner forever and cannot tell
 * "my child turned this off" from "the phone is in a basement" from "the app is broken".
 */
enum class FixStatus {
    /** A usable fix. Only this status carries coordinates. */
    OK,
    /** The child device has location sharing switched off. An explicit refusal, not silence. */
    NOT_CONSENTED,
    /** The runtime location permission is not granted on the child device. */
    PERMISSION_DENIED,
    /** Device location services are off entirely. */
    LOCATION_DISABLED,
    /** No fix within the time budget — indoors, no signal, airplane mode. */
    TIMEOUT;

    companion object {
        /** Unknown names decode to TIMEOUT: a newer child may send a status this parent build predates,
         *  and "couldn't get a fix" is the honest, non-alarming reading of an outcome we can't name. */
        fun fromName(name: String): FixStatus =
            entries.firstOrNull { it.name == name } ?: TIMEOUT
    }
}

/**
 * One answer to one parent request. Never produced by passive tracking — there is none. The entry count
 * equals the request count, which is what makes the same list serve as both "where are they now"
 * (newest entry) and "location history" (all of it).
 */
data class LocationFix(
    /** When the parent asked. Present for every status, so failures still sort correctly. */
    val requestedAtMs: Long,
    /** When the device actually got the fix. 0 unless [status] is OK. */
    val fixedAtMs: Long,
    val status: FixStatus,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy in metres — drives "±12 m" vs "±1.2 km". 0 unless OK. */
    val accuracyM: Float,
    /** Child battery level 0..100, or -1 when unknown. Costs nothing and pre-answers "why no fix?". */
    val batteryPct: Int,
) {
    val isUsable: Boolean get() = status == FixStatus.OK

    companion object {
        /** A failure answer for [status], carrying no coordinates. */
        fun failure(
            status: FixStatus,
            requestedAtMs: Long,
            batteryPct: Int = -1,
        ): LocationFix = LocationFix(
            requestedAtMs = requestedAtMs,
            fixedAtMs = 0L,
            status = status,
            lat = 0.0,
            lon = 0.0,
            accuracyM = 0f,
            batteryPct = batteryPct,
        )
    }
}

/**
 * The stored log: newest first, capped.
 *
 * Pure so the retention rule — the part that silently loses a parent's data if wrong — is unit-testable
 * with no Android, Firestore or clock.
 */
object LocationLog {

    /** Kept entries. ~60 bytes each, so the whole log is ~3 KB — far inside Firestore's 1 MB document. */
    const val MAX_ENTRIES = 50

    /**
     * Put [fix] at the head and drop anything past [MAX_ENTRIES].
     *
     * Newest-first ordering is the storage order, not a view concern: the parent's most common question
     * is "where are they now", and that must not depend on scanning the whole list.
     */
    fun append(log: List<LocationFix>, fix: LocationFix): List<LocationFix> =
        (listOf(fix) + log).take(MAX_ENTRIES)

    /** The answer to "where are they now" — the newest entry, whatever its status. */
    fun latest(log: List<LocationFix>): LocationFix? = log.firstOrNull()

    /** Newest-first, defensively re-sorted: a log assembled from a corrupted document may not be. */
    fun ordered(log: List<LocationFix>): List<LocationFix> =
        log.sortedByDescending { it.requestedAtMs }
}

/**
 * Delimiter codec, matching [RemoteReportCodec] / [PairedDeviceCodec] convention — no JSON dependency,
 * pure, fully unit-testable.
 *
 * Positional and tolerant: extra trailing fields decode (forward compatible, so an older parent survives
 * a newer child), and an unparseable record is dropped rather than failing the whole log. One corrupt
 * entry must never cost a parent every location they have.
 */
object LocationFixCodec {
    private val US = Char(31)  // field separator within a fix
    private val RS = Char(30)  // record separator between fixes

    fun encode(log: List<LocationFix>): String =
        log.joinToString(RS.toString()) { f ->
            listOf(
                f.requestedAtMs.toString(),
                f.fixedAtMs.toString(),
                f.status.name,
                f.lat.toString(),
                f.lon.toString(),
                f.accuracyM.toString(),
                f.batteryPct.toString(),
            ).joinToString(US.toString())
        }

    fun decode(raw: String): List<LocationFix> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RS).mapNotNull { record ->
            if (record.isBlank()) return@mapNotNull null
            val f = record.split(US)
            if (f.size < 7) return@mapNotNull null
            val requestedAt = f[0].toLongOrNull() ?: return@mapNotNull null
            LocationFix(
                requestedAtMs = requestedAt,
                fixedAtMs = f[1].toLongOrNull() ?: 0L,
                status = FixStatus.fromName(f[2]),
                lat = f[3].toDoubleOrNull() ?: 0.0,
                lon = f[4].toDoubleOrNull() ?: 0.0,
                accuracyM = f[5].toFloatOrNull() ?: 0f,
                batteryPct = f[6].toIntOrNull() ?: -1,
            )
        }
    }
}
