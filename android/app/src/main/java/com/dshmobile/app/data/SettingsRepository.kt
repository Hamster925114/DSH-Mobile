package com.dshmobile.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "dsh_settings")

data class AppSettings(
    val profiles: List<ConnectionProfile> = defaultProfiles(),
    val activeProfileId: String? = null,
    val notificationsEnabled: Boolean = false,
) {
    val activeProfile: ConnectionProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }
}

class SettingsRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val legacyBase = preferences[LEGACY_BASE_URL].orEmpty()
        val stored = decodeProfiles(preferences[PROFILES_JSON])
        val profiles = when {
            stored != null -> stored
            legacyBase.isNotBlank() -> listOf(
                ConnectionProfile(
                    id = LEGACY_PROFILE_ID,
                    label = "已配对连接",
                    kind = ConnectionKind.CUSTOM,
                    baseUrl = legacyBase,
                ),
            )
            else -> defaultProfiles()
        }
        val storedActive = preferences[ACTIVE_PROFILE_ID]
        val active = when {
            storedActive == CLEARED_ACTIVE -> null
            storedActive != null && profiles.any { it.id == storedActive } -> storedActive
            legacyBase.isNotBlank() && profiles.any { it.id == LEGACY_PROFILE_ID } -> LEGACY_PROFILE_ID
            else -> profiles.firstOrNull { it.baseUrl.isNotBlank() }?.id
        }
        AppSettings(
            profiles = profiles,
            activeProfileId = active,
            notificationsEnabled = preferences[NOTIFICATIONS_ENABLED] ?: false,
        )
    }

    suspend fun saveProfile(profile: ConnectionProfile) {
        context.dataStore.edit { preferences ->
            val profiles = decodeProfiles(preferences[PROFILES_JSON])
                ?: legacyProfiles(preferences[LEGACY_BASE_URL])
                ?: defaultProfiles()
            val updated = profiles
                .filterNot { it.id == profile.id }
                .plus(profile)
                .sortedBy { it.kind.ordinal }
            preferences[PROFILES_JSON] = json.encodeToString(
                ListSerializer(ConnectionProfile.serializer()),
                updated,
            )
            preferences.remove(LEGACY_BASE_URL)
        }
    }

    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { preferences ->
            val profiles = decodeProfiles(preferences[PROFILES_JSON])
                ?: legacyProfiles(preferences[LEGACY_BASE_URL])
                ?: defaultProfiles()
            val remaining = profiles.filterNot { it.id == id }
            preferences[PROFILES_JSON] = json.encodeToString(
                ListSerializer(ConnectionProfile.serializer()),
                remaining,
            )
            preferences.remove(LEGACY_BASE_URL)
            if (preferences[ACTIVE_PROFILE_ID] == id) {
                preferences.remove(ACTIVE_PROFILE_ID)
                remaining.firstOrNull { it.baseUrl.isNotBlank() }?.let {
                    preferences[ACTIVE_PROFILE_ID] = it.id
                }
            }
        }
    }

    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit {
            it[ACTIVE_PROFILE_ID] = id
            it.remove(LEGACY_BASE_URL)
        }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS_ENABLED] = value }
    }

    /** Drop the active pairing but keep the saved endpoints for later. */
    suspend fun clearActivePairing() {
        context.dataStore.edit {
            // A sentinel, not remove(): the fallback below would otherwise
            // re-select the first configured profile on the next launch.
            it[ACTIVE_PROFILE_ID] = CLEARED_ACTIVE
            it.remove(LEGACY_BASE_URL)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private fun decodeProfiles(raw: String?): List<ConnectionProfile>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(ListSerializer(ConnectionProfile.serializer()), raw)
        }.getOrNull()
    }

    private fun legacyProfiles(base: String?): List<ConnectionProfile>? {
        if (base.isNullOrBlank()) return null
        return listOf(
            ConnectionProfile(
                id = LEGACY_PROFILE_ID,
                label = "已配对连接",
                kind = ConnectionKind.CUSTOM,
                baseUrl = base,
            ),
        )
    }

    private companion object {
        const val LEGACY_PROFILE_ID = "legacy"
        const val CLEARED_ACTIVE = ""
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val LEGACY_BASE_URL = stringPreferencesKey("base_url")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }
}
