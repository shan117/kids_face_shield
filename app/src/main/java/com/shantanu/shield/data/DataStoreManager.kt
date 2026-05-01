package com.shantanu.shield.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
}
