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

    suspend fun setKidSessionEndAt(endAtMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[KID_SESSION_END_AT_KEY] = endAtMs
        }
    }
}
