package com.dshmobile.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dsh_settings")

data class AppSettings(
    val baseUrl: String = "",
    val notificationsEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            baseUrl = preferences[BASE_URL].orEmpty(),
            notificationsEnabled = preferences[NOTIFICATIONS_ENABLED] ?: false,
        )
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS_ENABLED] = value }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }
}
