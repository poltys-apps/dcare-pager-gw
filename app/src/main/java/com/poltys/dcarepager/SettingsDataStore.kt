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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(context: Context) {

    private val dataStore = context.dataStore

    object PreferencesKeys {
        val DESTINATION_ADDRESS = stringPreferencesKey("destination_address")
        val FRIENDLY_NAME = stringPreferencesKey("friendly_name")
        val SILENT_NOTIFICATION = booleanPreferencesKey("silent_notification")
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
            it[PreferencesKeys.FRIENDLY_NAME] ?: ""
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
}