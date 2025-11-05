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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsDataStore: SettingsDataStore) {
    val destinationAddress by settingsDataStore.destinationAddressFlow.collectAsState(initial = "")
    var newDestinationAddress by remember { mutableStateOf(destinationAddress) }
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
            Button(onClick = {
                scope.launch {
                    settingsDataStore.saveDestinationAddress(newDestinationAddress)
                }
            }) {
                Text("Save")
            }
        }
    }
}