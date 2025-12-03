package com.poltys.dcarepager

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.poltys.dcarepager.ui.theme.Check
import com.poltys.dcarepager.ui.theme.DcarePagerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class MainViewModel : ViewModel() {
    var udpListenerService: UdpListenerService? = null

    private val _alarms = MutableStateFlow<Map<Int, AlertData>>(emptyMap())
    val alarms: StateFlow<Map<Int, AlertData>> = _alarms.asStateFlow()

    fun setAlarms(alarms: Map<Int, AlertData>) {
        _alarms.value = alarms
    }

    fun ackAlarm(alarmId: Int) {
        udpListenerService?.ackAlarm(alarmId)
    }
}

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private var udpListenerService: UdpListenerService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UdpListenerService.LocalBinder
            udpListenerService = binder.getService()
            mainViewModel.udpListenerService = udpListenerService
            lifecycle.coroutineScope.launch {
                udpListenerService?.alarms?.collect { alarms ->
                    mainViewModel.setAlarms(alarms)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            udpListenerService = null
            mainViewModel.udpListenerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        val serviceIntent = Intent(this, UdpListenerService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        val settingsDataStore = SettingsDataStore(this)
        setContent {
            DcarePagerTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(navController = navController, viewModel = mainViewModel, settingsDataStore = settingsDataStore)
                    }
                    composable("settings") {
                        SettingsScreen(navController = navController, settingsDataStore = settingsDataStore)
                    }
                    composable("login") {
                        LoginScreen(navController = navController, settingsDataStore = settingsDataStore)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: MainViewModel, settingsDataStore: SettingsDataStore) {
    val alarms by viewModel.alarms.collectAsState()
    val loginName by settingsDataStore.loginNameFlow.collectAsState(initial = "Logged Out")
    val friendlyName by settingsDataStore.friendlyNameFlow.collectAsState(initial = "")

    val sortedAlarms = alarms.toList().sortedWith(compareByDescending<Pair<Int, AlertData>> { it.second.priority }.thenBy { it.second.timestamp })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                            Column {
                                Text(friendlyName)
                                Text(loginName)
                            }
                        },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { navController.navigate("login") }) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "Login")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(sortedAlarms) { (id, alert) ->
                if (alert.isAcknowledged) {
                    return@items
                }
                Card(modifier = Modifier
                    .border(2.dp,priorityToColor(alert.priority))
                    .padding(8.dp)
                    .fillMaxWidth()) {
                    Row (
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = alert.sender, fontWeight = FontWeight.Bold)
                            Text(text = alert.message)
                            ElapsedTimeCounter(timestamp = alert.timestamp)
                        }
                        if (alert.isMaintenance) {
                            IconButton(
                                onClick = {
                                    viewModel.ackAlarm(id)
                                },
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Icon(
                                    Check,
                                    contentDescription = "Check",
                                    tint = Color.Green,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                       }
                    }
                }
            }
        }
    }
}

fun priorityToColor(priority: Int): Color {
    return when (priority) {
        1 -> Color.Red
        2, 3 -> Color.Yellow
        else -> Color.Transparent
    }
}

@Composable
fun ElapsedTimeCounter(timestamp: Instant) {
    var duration by remember { mutableStateOf(Duration.between(timestamp, Instant.now())) }

    LaunchedEffect(timestamp) {
        while (true) {
            duration = Duration.between(timestamp, Instant.now())
            delay(1000)
        }
    }

    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    val seconds = duration.seconds % 60

    Text(text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds))
}