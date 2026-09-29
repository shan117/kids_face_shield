package com.shantanu.shield.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shantanu.shield.face.FaceModelConfig
import com.shantanu.shield.remote.PairedDevice
import com.shantanu.shield.remote.PairedDeviceCodec
import com.shantanu.shield.remote.PairedDevices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_lock_settings")

@Singleton
class DataStoreManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val PROTECTED_APPS_KEY = stringSetPreferencesKey("protected_apps")
    private val FACE_EMBEDDING_KEY = stringPreferencesKey("face_embedding")
    // SFace migration (Phase 1): which model the stored embeddings belong to. Stamped on every enrol.
    private val FACE_MODEL_VERSION_KEY = stringPreferencesKey("face_model_version")
    private val LOCK_MESSAGE_TYPE_KEY = intPreferencesKey("lock_message_type")
    private val LOCK_DEVICE_SETTINGS_KEY = booleanPreferencesKey("lock_device_settings")
    private val LOCK_OWN_APP_KEY = booleanPreferencesKey("lock_own_app")

    // ---- Kid Mode + Screen Time keys (Phase 1) ----
    // See KID_MODE_FEATURE_PLAN.md for the full design.
    private val OWNER_TYPE_KEY = stringPreferencesKey("owner_type")
    private val DAILY_LIMIT_MINUTES_KEY = intPreferencesKey("daily_limit_minutes")
    private val ALWAYS_ALLOWED_PRESET_KEY = intPreferencesKey("always_allowed_preset")
    private val CUSTOM_ALWAYS_ALLOWED_KEY = stringSetPreferencesKey("custom_always_allowed")
    private val SCREEN_TIME_USED_MS_KEY = longPreferencesKey("screen_time_used_ms")
    private val EXTENSIONS_TODAY_MS_KEY = longPreferencesKey("extensions_today_ms")
    private val SCREEN_TIME_LAST_RESET_DATE_KEY = stringPreferencesKey("screen_time_last_reset_date")
    private val EXTENSION_HISTORY_KEY = stringPreferencesKey("extension_history")

    // Free-Play (Temp-Kid-Mode) session — wall-clock epoch ms when the current session
    // ends. 0 (or any value < now) means no session is active.
    private val KID_SESSION_END_AT_KEY = longPreferencesKey("kid_session_end_at")

    // First-run welcome / setup wizard. True once the user has completed or skipped it.
    private val FIRST_RUN_COMPLETED_KEY = booleanPreferencesKey("first_run_completed")

    // Coach-marks / in-app guide toggle + per-tour "already seen" set.
    private val COACH_MARKS_ENABLED_KEY = booleanPreferencesKey("coach_marks_enabled")
    private val SEEN_TOURS_KEY = stringSetPreferencesKey("seen_tours")

    // Free-Play session history. Serialized as "startMs,endMs,grantedMs;..." with a
    // 90-day TTL pruned at write time. Used by the Stats tab to attribute usage to
    // Free Play windows and to render per-day Free Play minutes granted.
    private val FREE_PLAY_HISTORY_KEY = stringPreferencesKey("free_play_history")
    private val FREE_PLAY_HISTORY_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    // ---- Premium entitlement cache (Phase 1) ----
    // Mirror of the user's premium state for offline / startup before Billing re-verifies.
    // Never authoritative — the BillingManager re-queries Play on every launch and overwrites this.
    private val CACHED_IS_PREMIUM_KEY = booleanPreferencesKey("cached_is_premium")
    private val CACHED_PLAN_KEY = stringPreferencesKey("cached_plan")

    // ---- Multiple-kids profiles (Phase 4) ----
    private val MULTI_KID_ENABLED_KEY = booleanPreferencesKey("multi_kid_enabled")
    private val KID_PROFILES_KEY = stringPreferencesKey("kid_profiles")
    private val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
    private val PROFILE_SESSIONS_KEY = stringPreferencesKey("profile_sessions")
    // Per-profile face embeddings used to identify which kid is using the device (separate from the
    // parent's FACE_EMBEDDING_KEY, which keeps driving the existing 1:1 unlock).
    private val KID_FACE_EMBEDDINGS_KEY = stringPreferencesKey("kid_face_embeddings")

    // ---- Earn screen-time (Phase 7) ----
    private val EARNED_TASKS_KEY = stringPreferencesKey("earned_tasks")

    // ---- Schedules (Phase 7) ----
    private val SCHEDULES_KEY = stringPreferencesKey("schedules")

    // ---- Auto-lock new apps (Phase 7) ----
    private val AUTO_BLOCK_NEW_APPS_KEY = booleanPreferencesKey("auto_block_new_apps")

    // ---- Per-app limits (Phase 7) ----
    private val PER_APP_LIMITS_KEY = stringPreferencesKey("per_app_limits")

    // ---- Per-kid per-app limits (Multiple-kids; kept out of KidProfile so a revert is safe) ----
    private val KID_PER_APP_LIMITS_KEY = stringPreferencesKey("kid_per_app_limits")

    // ---- Theme accent (Phase 7) ----
    private val THEME_ACCENT_KEY = stringPreferencesKey("theme_accent")

    // ---- Parent "not screen time" overrides ----
    // Packages the PARENT has marked as not counting as screen time, from the parent dashboard.
    // The final fallback for OEM utilities (wallpaper carousels, clocks) our rules didn't catch.
    private val STATS_EXCLUDED_KEY = stringSetPreferencesKey("stats_excluded_packages")

    // ---- Parent Remote Report (opt-in, E2E) — see PARENT_REMOTE_REPORT_PLAN.md ----
    // role: "none" (default) / "child" (shares an encrypted weekly report) / "parent" (views it).
    private val REMOTE_ROLE_KEY = stringPreferencesKey("remote_role")
    // Hex bearer id (Firestore doc key) + hex AES-256 E2E key, both minted at pairing (PairingManager).
    private val REMOTE_PAIRING_ID_KEY = stringPreferencesKey("remote_pairing_id")
    private val REMOTE_PAIRING_KEY_KEY = stringPreferencesKey("remote_pairing_key")
    // Opt-in master switch; while false, NOTHING leaves the device (the privacy invariant).
    private val REMOTE_SHARE_ENABLED_KEY = booleanPreferencesKey("remote_share_enabled")
    // How often the encrypted report syncs: "daily" or "weekly" (default).
    private val REMOTE_SHARE_CADENCE_KEY = stringPreferencesKey("remote_share_cadence")
    // Remote Control (parent→child commands): child opt-in + the last command id applied (idempotency).
    private val REMOTE_CONTROL_ENABLED_KEY = booleanPreferencesKey("remote_control_enabled")

    // ---- Multi-device pairing (PARENT role only) — see MULTI_DEVICE_PAIRING_PLAN.md ----
    // The encoded List<PairedDevice> this parent phone is linked to. A CHILD device never reads these;
    // it keeps using the single-valued REMOTE_PAIRING_* keys above, which is what keeps the enforcement
    // path untouched by this feature.
    private val PAIRED_DEVICES_KEY = stringPreferencesKey("remote_paired_devices")
    // Which linked device the parent is currently viewing. May go stale (e.g. that device was removed),
    // so it is only ever interpreted through PairedDevices.resolveActive.
    private val ACTIVE_PAIRING_KEY = stringPreferencesKey("remote_active_pairing")
    private val LAST_APPLIED_COMMAND_ID_KEY = stringPreferencesKey("last_applied_command_id")

    val protectedApps: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[PROTECTED_APPS_KEY] ?: emptySet()
    }

    val faceEmbedding: Flow<FloatArray?> = context.dataStore.data.map { preferences ->
        preferences[FACE_EMBEDDING_KEY]?.let { string ->
            string.split(",").map { it.toFloat() }.toFloatArray()
        }
    }

    /** Model tag the stored embeddings belong to ("" = legacy, pre-versioning). */
    val faceModelVersion: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[FACE_MODEL_VERSION_KEY] ?: ""
    }

    /**
     * Stale-aware parent enrolment: enrolled AND the stored embedding belongs to the current model.
     * On a model change (Phase 2c) old embeddings stop counting → re-enrol prompt. While on FaceNet,
     * legacy unstamped embeddings still count (no behavior change in Phase 1).
     */
    val parentFaceEnrolled: Flow<Boolean> =
        combine(faceEmbedding, faceModelVersion) { emb, ver ->
            emb != null && FaceModelConfig.isVersionCurrent(ver)
        }

    val lockMessageType: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[LOCK_MESSAGE_TYPE_KEY] ?: 0 // 0 for hardware, 1 for health
    }

    val lockDeviceSettings: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOCK_DEVICE_SETTINGS_KEY] ?: false
    }

    val lockOwnApp: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOCK_OWN_APP_KEY] ?: false
    }

    // ---- Kid Mode + Screen Time flows (Phase 1) ----
    val ownerType: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[OWNER_TYPE_KEY] ?: "parent"
    }

    val dailyLimitMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[DAILY_LIMIT_MINUTES_KEY] ?: 60
    }

    val alwaysAllowedPreset: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[ALWAYS_ALLOWED_PRESET_KEY] ?: 0
    }

    val customAlwaysAllowed: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[CUSTOM_ALWAYS_ALLOWED_KEY] ?: emptySet()
    }

    val screenTimeUsedMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[SCREEN_TIME_USED_MS_KEY] ?: 0L
    }

    val extensionsTodayMs: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[EXTENSIONS_TODAY_MS_KEY] ?: 0L
    }

    val screenTimeLastResetDate: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SCREEN_TIME_LAST_RESET_DATE_KEY] ?: ""
    }

    val extensionHistory: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[EXTENSION_HISTORY_KEY] ?: ""
    }

    val kidSessionEndAt: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KID_SESSION_END_AT_KEY] ?: 0L
    }

    val firstRunCompleted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FIRST_RUN_COMPLETED_KEY] ?: false
    }

    val coachMarksEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[COACH_MARKS_ENABLED_KEY] ?: true
    }

    val seenTours: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SEEN_TOURS_KEY] ?: emptySet()
    }

    val freePlayHistory: Flow<List<FreePlayRecord>> = context.dataStore.data.map { preferences ->
        parseFreePlayHistory(preferences[FREE_PLAY_HISTORY_KEY] ?: "")
    }

    // ---- Premium entitlement cache (Phase 1) ----
    val cachedIsPremium: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[CACHED_IS_PREMIUM_KEY] ?: false
    }

    val cachedPlan: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CACHED_PLAN_KEY] ?: ""
    }

    // ---- Multiple-kids profiles (Phase 4) ----
    val multiKidEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MULTI_KID_ENABLED_KEY] ?: false
    }

    val kidProfiles: Flow<List<KidProfile>> = context.dataStore.data.map { preferences ->
        KidProfileCodec.decode(preferences[KID_PROFILES_KEY] ?: "")
    }

    val activeProfileId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ACTIVE_PROFILE_ID_KEY] ?: "p1"
    }

    val profileSessions: Flow<List<ProfileSession>> = context.dataStore.data.map { preferences ->
        KidProfileCodec.decodeSessions(preferences[PROFILE_SESSIONS_KEY] ?: "")
    }

    val kidFaceEmbeddings: Flow<Map<String, FloatArray>> = context.dataStore.data.map { preferences ->
        FaceGalleryCodec.decode(preferences[KID_FACE_EMBEDDINGS_KEY] ?: "")
    }

    /** Stale-aware count of kid faces enrolled on the CURRENT model (0 if embeddings belong to an old model). */
    val kidFacesEnrolledCount: Flow<Int> =
        combine(kidFaceEmbeddings, faceModelVersion) { gallery, ver ->
            if (FaceModelConfig.isVersionCurrent(ver)) gallery.size else 0
        }

    /** Stale-aware: at least [n] kid faces enrolled on the current model. */
    fun kidFacesEnrolled(n: Int): Flow<Boolean> = kidFacesEnrolledCount.map { it >= n }

    val earnedTasks: Flow<List<EarnedTask>> = context.dataStore.data.map { preferences ->
        EarnedTaskCodec.decode(preferences[EARNED_TASKS_KEY] ?: "")
    }

    suspend fun setEarnedTasks(tasks: List<EarnedTask>) {
        context.dataStore.edit { preferences -> preferences[EARNED_TASKS_KEY] = EarnedTaskCodec.encode(tasks) }
    }

    val schedules: Flow<List<Schedule>> = context.dataStore.data.map { preferences ->
        ScheduleCodec.decode(preferences[SCHEDULES_KEY] ?: "")
    }

    suspend fun setSchedules(schedules: List<Schedule>) {
        context.dataStore.edit { preferences -> preferences[SCHEDULES_KEY] = ScheduleCodec.encode(schedules) }
    }

    val autoBlockNewApps: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_BLOCK_NEW_APPS_KEY] ?: false
    }

    suspend fun setAutoBlockNewApps(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[AUTO_BLOCK_NEW_APPS_KEY] = enabled }
    }

    val perAppLimits: Flow<Map<String, Int>> = context.dataStore.data.map { preferences ->
        PerAppLimitCodec.decode(preferences[PER_APP_LIMITS_KEY] ?: "")
    }

    /** Per-kid per-app daily limits: profileId -> (package -> minutes). */
    val kidPerAppLimits: Flow<Map<String, Map<String, Int>>> = context.dataStore.data.map { preferences ->
        KidPerAppLimitCodec.decode(preferences[KID_PER_APP_LIMITS_KEY] ?: "")
    }

    // Set a per-app daily limit in minutes; minutes <= 0 removes the limit.
    suspend fun setPerAppLimit(pkg: String, minutes: Int) {
        context.dataStore.edit { preferences ->
            val current = PerAppLimitCodec.decode(preferences[PER_APP_LIMITS_KEY] ?: "").toMutableMap()
            if (minutes <= 0) current.remove(pkg) else current[pkg] = minutes
            preferences[PER_APP_LIMITS_KEY] = PerAppLimitCodec.encode(current)
        }
    }

    val themeAccent: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_ACCENT_KEY] ?: "teal"
    }

    suspend fun setThemeAccent(key: String) {
        context.dataStore.edit { preferences -> preferences[THEME_ACCENT_KEY] = key }
    }

    // ---- Parent "not screen time" overrides ----

    val statsExcludedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[STATS_EXCLUDED_KEY] ?: emptySet()
    }

    /** Mark [pkg] as not screen time ([excluded] true) or restore it to normal counting. */
    suspend fun setStatsExcluded(pkg: String, excluded: Boolean) {
        context.dataStore.edit { preferences ->
            val current = (preferences[STATS_EXCLUDED_KEY] ?: emptySet()).toMutableSet()
            if (excluded) current.add(pkg) else current.remove(pkg)
            preferences[STATS_EXCLUDED_KEY] = current
        }
    }

    // ---- Parent Remote Report flows + setters (opt-in, E2E) ----
    val remoteRole: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_ROLE_KEY] ?: "none"
    }

    val remotePairingId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_PAIRING_ID_KEY] ?: ""
    }

    val remotePairingKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_PAIRING_KEY_KEY] ?: ""
    }

    val remoteShareEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_SHARE_ENABLED_KEY] ?: false
    }

    val remoteShareCadence: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_SHARE_CADENCE_KEY] ?: "weekly"
    }

    suspend fun setRemoteShareCadence(cadence: String) {
        context.dataStore.edit { preferences -> preferences[REMOTE_SHARE_CADENCE_KEY] = cadence }
    }

    suspend fun setRemoteRole(role: String) {
        context.dataStore.edit { preferences -> preferences[REMOTE_ROLE_KEY] = role }
    }

    /** Store the paired id + key together — they are minted and exchanged as a single unit. */
    suspend fun setRemotePairing(pairingIdHex: String, pairingKeyHex: String) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_PAIRING_ID_KEY] = pairingIdHex
            preferences[REMOTE_PAIRING_KEY_KEY] = pairingKeyHex
        }
    }

    suspend fun setRemoteShareEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REMOTE_SHARE_ENABLED_KEY] = enabled }
    }

    /** Unpair / revoke EVERYTHING: wipe the pairing secrets, role, share + control flags in one atomic
     *  edit. To drop a single linked device on a multi-device parent use [removePairedDevice] instead —
     *  this would take Remote Report down for the remaining children too (plan trap A). */
    suspend fun clearRemotePairing() {
        context.dataStore.edit { preferences ->
            preferences.remove(REMOTE_PAIRING_ID_KEY)
            preferences.remove(REMOTE_PAIRING_KEY_KEY)
            preferences.remove(REMOTE_ROLE_KEY)
            preferences.remove(REMOTE_SHARE_ENABLED_KEY)
            preferences.remove(REMOTE_CONTROL_ENABLED_KEY)
            preferences.remove(LAST_APPLIED_COMMAND_ID_KEY)
            // Invariant 6 — a full reset must not leave the device list or the active pointer behind.
            preferences.remove(PAIRED_DEVICES_KEY)
            preferences.remove(ACTIVE_PAIRING_KEY)
        }
    }

    // ---- Multi-device pairing (parent side) ----
    //
    // Every mutator below follows the same shape, and the shape is the safety property:
    //   1. read + decode the current list inside the edit block
    //   2. apply a PURE PairedDevices.* transform
    //   3. write the list AND mirror element 0 into the legacy keys — in the SAME atomic edit
    //
    // Doing all three in one `edit {}` means the list and the legacy mirror can never disagree, even
    // if the process dies mid-write (plan §4.1). The mirror is what lets the older read sites
    // (RemoteCommandSender, ParentReportViewModel) keep working untouched, so the data layer can ship
    // before any UI depends on it. It is removed in Phase 5.

    val pairedDevices: Flow<List<PairedDevice>> = context.dataStore.data.map { preferences ->
        PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
    }

    /** The stored "currently viewing" id. Raw — resolve it with [PairedDevices.resolveActive]. */
    val activePairingId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ACTIVE_PAIRING_KEY] ?: ""
    }

    /**
     * Apply a device-list write to every key it affects, atomically.
     *
     * All the decisions — the legacy mirror (invariant 9) and keeping the active pointer inside the
     * list (invariants 3 + 4) — live in the pure [PairedDevices.keyStateFor], which is unit-tested.
     * This function only applies them, so there is no rule here that tests cannot reach.
     */
    private fun MutablePreferences.writeDevices(devices: List<PairedDevice>) {
        val next = PairedDevices.keyStateFor(devices, this[ACTIVE_PAIRING_KEY] ?: "")
        this[PAIRED_DEVICES_KEY] = PairedDeviceCodec.encode(next.devices)
        next.legacyId?.let { this[REMOTE_PAIRING_ID_KEY] = it } ?: remove(REMOTE_PAIRING_ID_KEY)
        next.legacyKey?.let { this[REMOTE_PAIRING_KEY_KEY] = it } ?: remove(REMOTE_PAIRING_KEY_KEY)
        next.activeId?.let { this[ACTIVE_PAIRING_KEY] = it } ?: remove(ACTIVE_PAIRING_KEY)
    }

    /** Link a child device, or update the entry that already holds this pairing id. */
    suspend fun addPairedDevice(device: PairedDevice) {
        context.dataStore.edit { preferences ->
            val current = PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
            preferences.writeDevices(PairedDevices.add(current, device))
        }
    }

    /** Drop one linked device. Does NOT touch role / share / control flags — see [clearRemotePairing]. */
    suspend fun removePairedDevice(pairingId: String) {
        context.dataStore.edit { preferences ->
            val current = PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
            preferences.writeDevices(PairedDevices.remove(current, pairingId))
        }
    }

    suspend fun renamePairedDevice(pairingId: String, label: String) {
        context.dataStore.edit { preferences ->
            val current = PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
            preferences.writeDevices(PairedDevices.rename(current, pairingId, label))
        }
    }

    /** Switch which linked device the parent is viewing. Ignored when the id isn't linked. */
    suspend fun setActivePairingId(pairingId: String) {
        context.dataStore.edit { preferences ->
            val current = PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
            if (current.any { it.pairingId == pairingId }) preferences[ACTIVE_PAIRING_KEY] = pairingId
        }
    }

    /**
     * Fold a pre-multi-device parent's single pairing into the device list. Idempotent and safe to
     * call on every startup (plan §4.3): a no-op once the list is non-empty, or when there are no
     * legacy keys to fold. One atomic edit, so a mid-write kill leaves either the old or the new
     * state, never a half-migrated one.
     */
    suspend fun migratePairedDevicesIfNeeded() {
        context.dataStore.edit { preferences ->
            if (preferences[REMOTE_ROLE_KEY] != "parent") return@edit
            val current = PairedDeviceCodec.decode(preferences[PAIRED_DEVICES_KEY] ?: "")
            val migrated = PairedDevices.migrate(
                legacyId = preferences[REMOTE_PAIRING_ID_KEY] ?: "",
                legacyKey = preferences[REMOTE_PAIRING_KEY_KEY] ?: "",
                existing = current,
            )
            if (migrated !== current) preferences.writeDevices(migrated)
        }
    }

    // ---- Remote Control (parent→child commands) flows + setters ----
    val remoteControlEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_CONTROL_ENABLED_KEY] ?: false
    }

    val lastAppliedCommandId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LAST_APPLIED_COMMAND_ID_KEY] ?: ""
    }

    suspend fun setRemoteControlEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REMOTE_CONTROL_ENABLED_KEY] = enabled }
    }

    suspend fun setLastAppliedCommandId(id: String) {
        context.dataStore.edit { preferences -> preferences[LAST_APPLIED_COMMAND_ID_KEY] = id }
    }

    suspend fun saveFaceEmbedding(embedding: FloatArray) {
        context.dataStore.edit { preferences ->
            preferences[FACE_EMBEDDING_KEY] = embedding.joinToString(",")
            // Stamp the model this embedding was produced with, so a future model change invalidates it.
            preferences[FACE_MODEL_VERSION_KEY] = FaceModelConfig.CURRENT_FACE_MODEL_VERSION
        }
    }

    /** Set/clear the model tag for the stored embeddings (used by the migration to force re-enrol). */
    suspend fun setFaceModelVersion(version: String) {
        context.dataStore.edit { preferences -> preferences[FACE_MODEL_VERSION_KEY] = version }
    }

    suspend fun setLockMessageType(type: Int) {
        context.dataStore.edit { preferences ->
            preferences[LOCK_MESSAGE_TYPE_KEY] = type
        }
    }

    suspend fun setLockDeviceSettings(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCK_DEVICE_SETTINGS_KEY] = enabled
        }
    }

    suspend fun setLockOwnApp(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCK_OWN_APP_KEY] = enabled
        }
    }

    suspend fun toggleProtectedApp(packageName: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PROTECTED_APPS_KEY] ?: emptySet()
            if (current.contains(packageName)) {
                preferences[PROTECTED_APPS_KEY] = current - packageName
            } else {
                preferences[PROTECTED_APPS_KEY] = current + packageName
            }
        }
    }

    suspend fun setProtectedApps(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PROTECTED_APPS_KEY] = packages
        }
    }

    // ---- Kid Mode + Screen Time setters (Phase 1) ----
    suspend fun setOwnerType(type: String) {
        context.dataStore.edit { preferences ->
            preferences[OWNER_TYPE_KEY] = type
        }
    }

    suspend fun setDailyLimitMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[DAILY_LIMIT_MINUTES_KEY] = minutes
        }
    }

    suspend fun setAlwaysAllowedPreset(preset: Int) {
        context.dataStore.edit { preferences ->
            preferences[ALWAYS_ALLOWED_PRESET_KEY] = preset
        }
    }

    suspend fun toggleCustomAlwaysAllowed(packageName: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[CUSTOM_ALWAYS_ALLOWED_KEY] ?: emptySet()
            preferences[CUSTOM_ALWAYS_ALLOWED_KEY] =
                if (current.contains(packageName)) current - packageName else current + packageName
        }
    }

    suspend fun setCustomAlwaysAllowed(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[CUSTOM_ALWAYS_ALLOWED_KEY] = packages
        }
    }

    suspend fun setScreenTimeUsedMs(ms: Long) {
        context.dataStore.edit { preferences ->
            preferences[SCREEN_TIME_USED_MS_KEY] = ms
        }
    }

    suspend fun setExtensionsTodayMs(ms: Long) {
        context.dataStore.edit { preferences ->
            preferences[EXTENSIONS_TODAY_MS_KEY] = ms
        }
    }

    /** Zero every kid profile's granted extension — called at the daily 07:00 boundary so a per-profile
     *  grant (multi-kid) doesn't carry into the next day. Per-profile usedMs isn't reset here: it's
     *  recomputed from windowed sessions each poll, so it's already day-scoped. */
    suspend fun resetAllProfileExtensions() {
        context.dataStore.edit { preferences ->
            val current = KidProfileCodec.decode(preferences[KID_PROFILES_KEY] ?: "")
            if (current.isEmpty()) return@edit
            val updated = current.map { if (it.extensionsMs != 0L) it.copy(extensionsMs = 0L) else it }
            if (updated != current) preferences[KID_PROFILES_KEY] = KidProfileCodec.encode(updated)
        }
    }

    suspend fun setScreenTimeLastResetDate(date: String) {
        context.dataStore.edit { preferences ->
            preferences[SCREEN_TIME_LAST_RESET_DATE_KEY] = date
        }
    }

    suspend fun appendExtensionHistory(epochMs: Long, minutes: Int) {
        context.dataStore.edit { preferences ->
            val existing = preferences[EXTENSION_HISTORY_KEY] ?: ""
            val entry = "$epochMs,$minutes"
            preferences[EXTENSION_HISTORY_KEY] =
                if (existing.isEmpty()) entry else "$existing;$entry"
        }
    }

    // Grant a budget extension for today. Bumps today's extension allowance and logs the
    // grant to history in a SINGLE atomic edit so the budget bump and the audit entry can
    // never disagree. The daily 07:00 poll zeroes extensions_today_ms (not the history).
    suspend fun addExtensionMinutes(minutes: Int) {
        if (minutes <= 0) return
        context.dataStore.edit { preferences ->
            val addMs = minutes * 60_000L
            val current = preferences[EXTENSIONS_TODAY_MS_KEY] ?: 0L
            preferences[EXTENSIONS_TODAY_MS_KEY] = current + addMs
            val existing = preferences[EXTENSION_HISTORY_KEY] ?: ""
            val entry = "${System.currentTimeMillis()},$minutes"
            preferences[EXTENSION_HISTORY_KEY] =
                if (existing.isEmpty()) entry else "$existing;$entry"

            // Multi-kid: enforcement reads the PER-PROFILE extension (KidProfile.extensionsMs), not the
            // global counter above — so a grant must also bump the locked kid's profile or it stays locked.
            // Target the most-recent session's profile (the kid who was last using the phone = the locked
            // one); the global bump alone only frees single-kid mode.
            if (preferences[MULTI_KID_ENABLED_KEY] == true) {
                val sessions = KidProfileCodec.decodeSessions(preferences[PROFILE_SESSIONS_KEY] ?: "")
                val targetId = sessions.maxByOrNull { it.endMs }?.profileId
                if (targetId != null) {
                    val profiles = KidProfileCodec.decode(preferences[KID_PROFILES_KEY] ?: "")
                    if (profiles.any { it.id == targetId }) {
                        preferences[KID_PROFILES_KEY] = KidProfileCodec.encode(
                            profiles.map { if (it.id == targetId) it.copy(extensionsMs = it.extensionsMs + addMs) else it }
                        )
                    }
                }
            }
        }
    }

    suspend fun setKidSessionEndAt(endAtMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[KID_SESSION_END_AT_KEY] = endAtMs
        }
    }

    suspend fun setFirstRunCompleted(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN_COMPLETED_KEY] = value
        }
    }

    suspend fun setCoachMarksEnabled(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[COACH_MARKS_ENABLED_KEY] = value
        }
    }

    suspend fun markTourSeen(tourId: String) {
        context.dataStore.edit { preferences ->
            preferences[SEEN_TOURS_KEY] = (preferences[SEEN_TOURS_KEY] ?: emptySet()) + tourId
        }
    }

    /** Re-arm a single tour, leaving the others marked seen. */
    suspend fun unmarkTourSeen(tourId: String) {
        context.dataStore.edit { preferences ->
            preferences[SEEN_TOURS_KEY] = (preferences[SEEN_TOURS_KEY] ?: emptySet()) - tourId
        }
    }

    suspend fun resetSeenTours() {
        context.dataStore.edit { preferences ->
            preferences[SEEN_TOURS_KEY] = emptySet()
        }
    }

    suspend fun appendFreePlayRecord(startMs: Long, endMs: Long, grantedMs: Long) {
        context.dataStore.edit { preferences ->
            val cutoff = System.currentTimeMillis() - FREE_PLAY_HISTORY_MAX_AGE_MS
            val pruned = parseFreePlayHistory(preferences[FREE_PLAY_HISTORY_KEY] ?: "")
                .filter { it.endMs >= cutoff }
            val updated = pruned + FreePlayRecord(startMs, endMs, grantedMs)
            preferences[FREE_PLAY_HISTORY_KEY] = serializeFreePlayHistory(updated)
        }
    }

    // ---- Premium entitlement cache setters (Phase 1) ----
    suspend fun setCachedIsPremium(value: Boolean) {
        context.dataStore.edit { preferences -> preferences[CACHED_IS_PREMIUM_KEY] = value }
    }

    suspend fun setCachedPlan(plan: String) {
        context.dataStore.edit { preferences -> preferences[CACHED_PLAN_KEY] = plan }
    }

    // ---- Multiple-kids profiles setters (Phase 4) ----
    suspend fun setMultiKidEnabled(value: Boolean) {
        context.dataStore.edit { preferences -> preferences[MULTI_KID_ENABLED_KEY] = value }
    }

    suspend fun setKidProfiles(profiles: List<KidProfile>) {
        context.dataStore.edit { preferences -> preferences[KID_PROFILES_KEY] = KidProfileCodec.encode(profiles) }
    }

    /** Set (minutes>0) or clear (minutes<=0) a single app's daily limit for one kid profile. */
    suspend fun setKidProfilePerAppLimit(profileId: String, pkg: String, minutes: Int) {
        context.dataStore.edit { preferences ->
            val current = KidPerAppLimitCodec.decode(preferences[KID_PER_APP_LIMITS_KEY] ?: "")
            val forKid = LinkedHashMap(current[profileId] ?: emptyMap())
            if (minutes > 0) forKid[pkg] = minutes else forKid.remove(pkg)
            val updated = LinkedHashMap(current)
            if (forKid.isEmpty()) updated.remove(profileId) else updated[profileId] = forKid
            preferences[KID_PER_APP_LIMITS_KEY] = KidPerAppLimitCodec.encode(updated)
        }
    }

    suspend fun setActiveProfileId(id: String) {
        context.dataStore.edit { preferences -> preferences[ACTIVE_PROFILE_ID_KEY] = id }
    }

    /**
     * Atomically update ONLY each profile's usedMs (by id), re-reading the current profile list inside
     * the edit. Used by the per-profile usage poll so it can't clobber a concurrent extension grant
     * (extensionsMs) / limit change — it touches usedMs and nothing else.
     */
    suspend fun updateProfileUsedMs(usedById: Map<String, Long>) {
        context.dataStore.edit { preferences ->
            val current = KidProfileCodec.decode(preferences[KID_PROFILES_KEY] ?: "")
            if (current.isEmpty()) return@edit
            val updated = current.map { p -> usedById[p.id]?.let { p.copy(usedMs = it) } ?: p }
            if (updated != current) preferences[KID_PROFILES_KEY] = KidProfileCodec.encode(updated)
        }
    }

    // Append a profile session for usage attribution, pruning entries older than the history window.
    suspend fun appendProfileSession(session: ProfileSession) {
        context.dataStore.edit { preferences ->
            val cutoff = System.currentTimeMillis() - FREE_PLAY_HISTORY_MAX_AGE_MS
            val pruned = KidProfileCodec.decodeSessions(preferences[PROFILE_SESSIONS_KEY] ?: "")
                .filter { it.endMs >= cutoff }
            preferences[PROFILE_SESSIONS_KEY] = KidProfileCodec.encodeSessions(pruned + session)
        }
    }

    // Extend the active kid's latest session up to [nowMs] so it spans their usage window (used for
    // per-profile attribution). Only the most-recent session is extended, and only if it belongs to
    // [profileId] and is still within [graceMs] — otherwise it's a stale session and we leave it.
    suspend fun extendLatestSession(profileId: String, nowMs: Long, graceMs: Long) {
        context.dataStore.edit { preferences ->
            val sessions = KidProfileCodec.decodeSessions(preferences[PROFILE_SESSIONS_KEY] ?: "").toMutableList()
            val idx = sessions.indices.maxByOrNull { sessions[it].endMs } ?: return@edit
            val latest = sessions[idx]
            if (latest.profileId == profileId && nowMs - latest.endMs <= graceMs) {
                sessions[idx] = latest.copy(endMs = nowMs)
                preferences[PROFILE_SESSIONS_KEY] = KidProfileCodec.encodeSessions(sessions)
            }
        }
    }

    suspend fun setKidFaceEmbedding(profileId: String, embedding: FloatArray) {
        context.dataStore.edit { preferences ->
            val current = FaceGalleryCodec.decode(preferences[KID_FACE_EMBEDDINGS_KEY] ?: "").toMutableMap()
            current[profileId] = embedding
            preferences[KID_FACE_EMBEDDINGS_KEY] = FaceGalleryCodec.encode(current)
            // Same model stamp as the parent — a single global version covers all embeddings.
            preferences[FACE_MODEL_VERSION_KEY] = FaceModelConfig.CURRENT_FACE_MODEL_VERSION
        }
    }

    suspend fun removeKidFaceEmbedding(profileId: String) {
        context.dataStore.edit { preferences ->
            val current = FaceGalleryCodec.decode(preferences[KID_FACE_EMBEDDINGS_KEY] ?: "").toMutableMap()
            current.remove(profileId)
            preferences[KID_FACE_EMBEDDINGS_KEY] = FaceGalleryCodec.encode(current)
        }
    }

    private fun parseFreePlayHistory(raw: String): List<FreePlayRecord> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                FreePlayRecord(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
            }.getOrNull()
        }
    }

    private fun serializeFreePlayHistory(records: List<FreePlayRecord>): String =
        records.joinToString(";") { "${it.startMs},${it.endMs},${it.grantedMs}" }
}

data class FreePlayRecord(val startMs: Long, val endMs: Long, val grantedMs: Long)
