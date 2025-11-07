package com.poltys.dcarepager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class AlertData {
    var sender: String = ""
    var message: String = ""
    var priority: Int = 1 // 1..5, 1 being highest
    var timestamp: Instant = Instant.now()
}

class UdpListenerService : Service() {

    companion object {
        const val ACTION_SEND_REGISTRATION = "com.poltys.dcarepager.ACTION_SEND_REGISTRATION"
        private const val SILENT_CHANNEL_ID = "dcarepager_silent_channel"
        private const val EMERGENCY_CHANNEL_ID = "dcarepager_emergency_channel"
    }

    inner class LocalBinder : Binder() {
        fun getService(): UdpListenerService = this@UdpListenerService
    }

    private val binder = LocalBinder()

    private lateinit var settingsDataStore: SettingsDataStore
    private var destinationAddress: String = ""
    private var localSeqNo: Int = 0

    private var datagramSocket: DatagramSocket? = null
    private val buffer = ByteArray(10240)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connectionJob: Job? = null

    private val _alarmIds = MutableStateFlow<Map<Int, AlertData>>(emptyMap())
    val alarms: StateFlow<Map<Int, AlertData>> = _alarmIds.asStateFlow()
    private var registerTime: Instant? = null
    var lastSocketOsError: Int? = null
    private var friendlyName: String = ""


    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            AppLog.add("Network available. Is Wi-Fi: $isWifi")
            if (connectionJob?.isActive != true && destinationAddress.isNotBlank()) {
                Log.i("UdpListenerService", "Network became available. Ensuring connection is active.")
                connectionJob?.cancel()
                connectionJob = launchSocketConnection()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            AppLog.add("Network lost.")
        }
    }

    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIdle = powerManager.isDeviceIdleMode
                Log.i("UdpListenerService", "Doze mode changed. Idle: $isIdle")
                AppLog.add("Doze mode changed. Idle: $isIdle")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        settingsDataStore = SettingsDataStore(this)
        RegistrationAlarmReceiver.schedule(this)

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        registerReceiver(dozeReceiver, dozeFilter)

        // Launch a separate coroutine for each flow to ensure they are collected concurrently
        serviceScope.launch {
            settingsDataStore.destinationAddressFlow.collect { address ->
                if (address.isNotBlank() && address != destinationAddress) {
                    destinationAddress = address
                    connectionJob?.cancel()
                    connectionJob = launchSocketConnection()
                } else if (address.isBlank()) {
                    connectionJob?.cancel()
                }
            }
        }

        serviceScope.launch {
            settingsDataStore.friendlyNameFlow.collect { newFriendlyName ->
                if (newFriendlyName.isNotBlank() && newFriendlyName != friendlyName) {
                    AppLog.add("Friendly name changed to: $newFriendlyName")
                    friendlyName = newFriendlyName
                }
            }
        }
    }

    private fun launchSocketConnection() = serviceScope.launch(Dispatchers.IO) {
        Log.d("UdpListenerService", "launchSocketConnection called.")

        while (isActive) {
            try {
                if (datagramSocket == null || datagramSocket!!.isClosed) {
                    datagramSocket = DatagramSocket()
                    datagramSocket?.soTimeout = 15000
                    datagramSocket!!.connect(InetSocketAddress(destinationAddress, 18806))
                    Log.i("UdpListenerService", "Socket connected to $destinationAddress")
                }

                if (registerTime == null || registerTime!!.isBefore(Instant.now().minusSeconds(30))) {
                    registerTime = Instant.now()
                    Log.i("UdpListenerService", "Sending register and logs")
                    localSeqNo += 1
                    sendMessage("""{"Register":{"seq_no":$localSeqNo,"FriendlyName":"$friendlyName"}}""")

                    val logs = AppLog.getAndClear()
                    if (logs.isNotEmpty()) {
                        val logJson = JSONObject()
                        logJson.put("Logs", JSONArray(logs))
                        sendMessage(logJson.toString())
                    }
                }

                val packet = DatagramPacket(buffer, buffer.size)
                datagramSocket?.receive(packet)
                processPacket(packet)
            } catch (e: SocketTimeoutException) {
                Log.d("UdpListenerService", "SocketTimeoutException: ${e.message}")
                // ignore
            } catch (e: SocketException) {
                val cause = e.cause
                var osError = "N/A"
                if (cause is ErrnoException) {
                    if (cause.errno != lastSocketOsError) {
                        lastSocketOsError = cause.errno
                        osError = cause.errno.toString()
                        val logMsg = "SocketException: ${e.message} OsError:$osError. Reconnecting in 10s."
                        AppLog.add(logMsg)
                        Log.w("UdpListenerService", logMsg)
                    }
                }
                else {
                    val logMsg = "SocketException: ${e.message}. Reconnecting in 10s."
                    AppLog.add(logMsg)
                    Log.w("UdpListenerService", logMsg)
                }
                datagramSocket?.close()
                datagramSocket = null
                delay(10_000)
            } catch (e: IOException) {
                AppLog.add("IOException: ${e.message}. Reconnecting in 10s.")
                Log.w("UdpListenerService", "IOException: ${e.message}. Reconnecting in 10s.")
                datagramSocket?.close()
                datagramSocket = null
                delay(10_000)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    AppLog.add("Coroutine loop failed: ${e.message}. Retrying in 10s.")
                    Log.e("UdpListenerService", "Connection coroutine failed", e)
                    datagramSocket?.close()
                    datagramSocket = null
                    delay(10_000)
                }
            }
        }
    }

    private suspend fun sendMessage(message: String) {
        if (datagramSocket?.isConnected == true) {
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, InetSocketAddress(destinationAddress, 18806))
            datagramSocket?.send(packet)
        } else {
            Log.w("UdpListenerService", "SendMessage called but socket is not connected.")
            throw SocketException("Socket is not connected.") // This will trigger the reconnect logic
        }
    }

    private fun parseTimestamp(timestampStr: String): Instant {
        return if (timestampStr.isNotBlank()) {
            try {
                OffsetDateTime.parse(timestampStr).toInstant()
            } catch (e: DateTimeParseException) {
                Log.w("UdpListenerService", "Could not parse timestamp: $timestampStr", e)
                Instant.now()
            }
        } else {
            Instant.now()
        }
    }

    private suspend fun processPacket(packet: DatagramPacket) {
        val jsonString = String(packet.data, 0, packet.length)
        try {
            val jsonObject = JSONObject(jsonString)
            Log.d("UdpListenerService", "Received JSON: $jsonObject")
            if (jsonObject.has("Alert")) {
                val alertData = jsonObject.getJSONObject("Alert")
                val seqNo = alertData.optInt("seq_no", 0)
                sendMessage("""{"Ack":$seqNo}""" )

                val armed = alertData.optBoolean("armed", false)
                val alarmIdStr = alertData.optString("id", "0")
                val alarmId = (alarmIdStr.toULongOrNull() ?: 0UL).toInt()
                val hasSubId = alertData.optBoolean("has_sub_id", false)


                val notificationManager =
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (armed) {
                    val sender = alertData.optString("device_name", "") + " " + jsonObject.optString("resident", "")
                    val message = alertData.optString("subject", "")
                    val priority = alertData.optInt("priority", 4) // 4 = Normal
                    val timestampStr = alertData.optString("timestamp")
                    val timestamp = parseTimestamp(timestampStr)

                    Log.i("UdpListenerService", "[$alarmIdStr:$alarmId] Sender: $sender, Message: $message, seqNo: $seqNo")

                    val notification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
                        .setContentTitle(sender)
                        .setContentText(message)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .build()
                    val newAlarms = _alarmIds.value.toMutableMap()
                    newAlarms[alarmId] = AlertData().apply {
                        this.sender = sender
                        this.message = message
                        this.priority = priority
                        this.timestamp = timestamp
                    }
                    _alarmIds.value = newAlarms

                    notificationManager.notify(alarmId, notification)
                } else {
                    val reset = alertData.optBoolean("reset", false)
                    Log.i("UdpListenerService", "[$alarmIdStr:$alarmId] CLEARED RESET:$reset seqNo: $seqNo")
                    if (reset && hasSubId) {
                        val newAlarms = _alarmIds.value.toMutableMap()
                        val iterator = newAlarms.iterator()
                        while (iterator.hasNext()) {
                            val (notifiedId, _) = iterator.next()
                            if (notifiedId / 10 == alarmId / 10) {
                                notificationManager.cancel(notifiedId)
                                iterator.remove()
                            }
                        }
                        _alarmIds.value = newAlarms
                    } else {
                        val newAlarms = _alarmIds.value.toMutableMap()
                        newAlarms.remove(alarmId)
                        _alarmIds.value = newAlarms
                        notificationManager.cancel(alarmId)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.e("UdpListenerService", "Error parsing JSON", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.udp_listener_content_title))
            .setContentText(getString(R.string.udp_listener_content_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)

        if (intent?.action == ACTION_SEND_REGISTRATION) {
            if (destinationAddress.isNotBlank()) {
                // Cancel any existing job and start a new one. This will re-bind the socket and send a new registration message.
                Log.d("UdpListenerService", "Alarm triggered. Restarting UDP coroutine.")
                connectionJob?.cancel()
                connectionJob = launchSocketConnection()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(dozeReceiver)
        RegistrationAlarmReceiver.cancel(this)
        serviceScope.cancel()
        Log.d("UdpListenerService", "UdpListenerService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Silent Channel for Foreground Service
        val silentChannel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "Foreground Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Used for the persistent foreground service notification."
        }
        notificationManager.createNotificationChannel(silentChannel)

        // Emergency Channel for Alerts
        val emergencySoundUri = "android.resource://$packageName/${R.raw.eventually}".toUri()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val emergencyChannel = NotificationChannel(
            EMERGENCY_CHANNEL_ID,
            "Emergency Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "For critical alerts that require immediate attention."
            setSound(emergencySoundUri, audioAttributes)
        }
        notificationManager.createNotificationChannel(emergencyChannel)
    }
}
