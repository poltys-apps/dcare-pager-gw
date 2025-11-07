package com.poltys.dcarepager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController, settingsDataStore: SettingsDataStore) {
    // These collect the latest values from DataStore as they change
    val destinationAddress by settingsDataStore.destinationAddressFlow.collectAsState(initial = "")
    val friendlyName by settingsDataStore.friendlyNameFlow.collectAsState(initial = "")

    // These hold the current state of the TextFields
    var newDestinationAddress by remember { mutableStateOf("") }
    var newFriendlyName by remember { mutableStateOf("") }

    // This is the key fix: It synchronizes the TextField state when the DataStore values first load.
    LaunchedEffect(destinationAddress) {
        newDestinationAddress = destinationAddress
    }
    LaunchedEffect(friendlyName) {
        newFriendlyName = friendlyName
    }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            TextField(
                value = newDestinationAddress,
                onValueChange = { newDestinationAddress = it },
                label = { Text(stringResource(R.string.dcc_proxy_address)) }
            )
            TextField(
                value = newFriendlyName,
                onValueChange = { newFriendlyName = it },
                label = { Text(stringResource(R.string.friendly_name)) }
            )

            Button(onClick = {
                scope.launch {
                    settingsDataStore.saveDestinationAddress(newDestinationAddress)
                    settingsDataStore.saveFriendlyName(newFriendlyName)
                    // Navigate back after saving
                    navController.navigateUp()
                }
            }) {
                Text("Save")
            }
        }
    }
}