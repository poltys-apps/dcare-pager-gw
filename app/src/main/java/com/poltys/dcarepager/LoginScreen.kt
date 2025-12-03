package com.poltys.dcarepager

import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavHostController, settingsDataStore: SettingsDataStore) {
    val loginPin by settingsDataStore.loginPinFlow.collectAsState(initial = "")
    var newLoginPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(loginPin) {
        newLoginPin = loginPin
    }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Login with PIN") })
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            TextField(
                value = newLoginPin,
                onValueChange = { newLoginPin = it },
                label = { Text(stringResource(R.string.dcc_login_pin)) }
            )
            Button(onClick = {
                scope.launch {
                    val destinationAddress = settingsDataStore.destinationAddressFlow.first()
                    if (destinationAddress.isBlank()) {
                        navController.navigate("settings")
                        return@launch
                    }
                    if (newLoginPin.isBlank()) {
                        errorMessage = "Please enter a PIN."
                        return@launch
                    }
                    errorMessage = ""
                    withContext(Dispatchers.IO) {
                        try {
                            val friendlyName = URLEncoder.encode(settingsDataStore.friendlyNameFlow.first(), "UTF-8")
                            val mURL =
                                URL("http://$destinationAddress/config/escalate_config.json?pin=$newLoginPin&name=$friendlyName")
                            with(mURL.openConnection() as HttpURLConnection) {
                                requestMethod = "GET"
                                BufferedReader(InputStreamReader(inputStream)).use {
                                    val response = StringBuffer()

                                    var inputLine = it.readLine()
                                    while (inputLine != null) {
                                        response.append(inputLine)
                                        inputLine = it.readLine()
                                    }
                                    it.close()

                                    val loginResponseJson = JSONObject(response.toString())
                                    val signIns = loginResponseJson.getJSONArray("sign_ins")
                                    if (signIns.length() == 0) {
                                        errorMessage = "Invalid PIN. Please try again."
                                    }
                                    val loginName = (signIns[0] as JSONObject).getString("username")
                                    settingsDataStore.saveLoginName(loginName)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("LoginScreen", "Error getting pin from server.", e)
                            errorMessage =
                                "Error getting pin from server. Please check you are connected to Wi-Fi and connection to server $destinationAddress is available."
                        }
                    }
                    if (errorMessage.isBlank()) {
                        settingsDataStore.saveLoginPin(newLoginPin)
                        navController.navigateUp()
                    }
                }
            }) {
                Text("Login")
            }
            if (errorMessage.isNotBlank()) {
                Text(errorMessage, color = Color.Red)
            }
        }
    }
}