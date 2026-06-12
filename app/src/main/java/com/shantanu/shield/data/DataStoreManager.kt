package com.shantanu.shield.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_lock_settings")

@Singleton
class DataStoreManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val PROTECTED_APPS_KEY = stringSetPreferencesKey("protected_apps")
    private val FACE_EMBEDDING_KEY = stringPreferencesKey("face_embedding")
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

    val protectedApps: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[PROTECTED_APPS_KEY] ?: emptySet()
    }

    val faceEmbedding: Flow<FloatArray?> = context.dataStore.data.map { preferences ->
        preferences[FACE_EMBEDDING_KEY]?.let { string ->
            string.split(",").map { it.toFloat() }.toFloatArray()
        }
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

    suspend fun saveFaceEmbedding(embedding: FloatArray) {
        context.dataStore.edit { preferences ->
            preferences[FACE_EMBEDDING_KEY] = embedding.joinToString(",")
        }
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
            val current = preferences[EXTENSIONS_TODAY_MS_KEY] ?: 0L
            preferences[EXTENSIONS_TODAY_MS_KEY] = current + minutes * 60_000L
            val existing = preferences[EXTENSION_HISTORY_KEY] ?: ""
            val entry = "${System.currentTimeMillis()},$minutes"
            preferences[EXTENSION_HISTORY_KEY] =
                if (existing.isEmpty()) entry else "$existing;$entry"
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
