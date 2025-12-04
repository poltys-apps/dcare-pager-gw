package com.poltys.dcarepager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Generates a unique ID string using UUID.
 * Example output: "player_550e8400-e29b-41d4-a716-446655440000"
 */
fun generateUniqueNameId(prefix: String = "phone"): String {
    val uuid = UUID.randomUUID().toString()
    return "${prefix}_$uuid"
}

class SettingsDataStore(context: Context) {

    private val dataStore = context.dataStore

    object PreferencesKeys {
        val DESTINATION_ADDRESS = stringPreferencesKey("destination_address")
        val FRIENDLY_NAME = stringPreferencesKey("friendly_name")
        val SILENT_NOTIFICATION = booleanPreferencesKey("silent_notification")
        val LOGIN_PIN = stringPreferencesKey("login_pin")
        val LOGIN_NAME = stringPreferencesKey("login_name")
        val LOGIN_ENABLED = booleanPreferencesKey("login_enabled")
    }

    val destinationAddressFlow: Flow<String> = dataStore.data
        .map {
            it[PreferencesKeys.DESTINATION_ADDRESS] ?: ""
        }

    suspend fun saveDestinationAddress(destinationAddress: String) {
        dataStore.edit {
            it[PreferencesKeys.DESTINATION_ADDRESS] = destinationAddress
        }
    }

    val friendlyNameFlow: Flow<String> = dataStore.data
        .map {
            it[PreferencesKeys.FRIENDLY_NAME] ?: generateUniqueNameId()
        }

    suspend fun saveFriendlyName(friendlyName: String) {
        dataStore.edit {
            it[PreferencesKeys.FRIENDLY_NAME] = friendlyName
        }
    }

    val silentNotificationFlow: Flow<Boolean> = dataStore.data
        .map {
            it[PreferencesKeys.SILENT_NOTIFICATION] ?: false
        }

    suspend fun saveSilentNotification(silentNotification: Boolean) {
        dataStore.edit {
            it[PreferencesKeys.SILENT_NOTIFICATION] = silentNotification
        }
    }

    val loginPinFlow: Flow<String> = dataStore.data
        .map {
            it[PreferencesKeys.LOGIN_PIN] ?: ""
        }

    suspend fun saveLoginPin(loginPin: String) {
        dataStore.edit {
            it[PreferencesKeys.LOGIN_PIN] = loginPin
        }
    }

    val loginNameFlow: Flow<String> = dataStore.data
        .map {
            it[PreferencesKeys.LOGIN_NAME] ?: "Logged Out"
        }

    suspend fun saveLoginName(loginName: String) {
        dataStore.edit {
            it[PreferencesKeys.LOGIN_NAME] = loginName
        }
    }

    val loginEnabledFlow: Flow<Boolean> = dataStore.data
        .map {
            it[PreferencesKeys.LOGIN_ENABLED] ?: false
        }

    suspend fun saveLoginEnabled(loginEnabled: Boolean) {
        dataStore.edit {
            it[PreferencesKeys.LOGIN_ENABLED] = loginEnabled
        }
    }
}