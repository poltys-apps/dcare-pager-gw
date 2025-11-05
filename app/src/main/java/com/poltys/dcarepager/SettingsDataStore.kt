package com.poltys.dcarepager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
}