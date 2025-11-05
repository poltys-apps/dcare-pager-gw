package com.poltys.dcarepager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
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


    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        settingsDataStore = SettingsDataStore(this)
        RegistrationAlarmReceiver.schedule(this)
        serviceScope.launch {
            settingsDataStore.destinationAddressFlow.collect { address ->
                if (address.isNotBlank() && address != destinationAddress) {
                    destinationAddress = address
                    connectionJob?.cancel() // Cancel previous job
                    connectionJob = launchSocketConnection(true)
                } else if (address.isBlank()) {
                    connectionJob?.cancel()
                }
            }
        }
    }

    private fun launchSocketConnection(addressChanged: Boolean = false) = serviceScope.launch(Dispatchers.IO) {
        // This coroutine owns the socket. It will be cancelled when the address changes.
        try {
            // Ensure any old socket is closed before creating a new one.
            if (addressChanged || datagramSocket == null || !datagramSocket!!.isConnected) {
                datagramSocket?.close()
                datagramSocket = DatagramSocket()
                datagramSocket!!.connect(InetSocketAddress(destinationAddress, 18806))
                Log.d("UdpListenerService", "Started new socket connection to $destinationAddress")
            }

            // Send an initial registration message
            localSeqNo += 1
            sendMessage("{\"Register\":$localSeqNo}")
            Log.d("UdpListenerService", "Sent registration message.")

            // Loop to receive packets. This will suspend the coroutine until a packet arrives.
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                datagramSocket?.receive(packet) // This is a blocking (suspending) call
                withContext(Dispatchers.Main) {
                    processPacket(packet)
                }
            }
        } catch (e: SocketException) {
            // A SocketException is expected when the socket is closed, e.g., during cancellation.
            if (isActive) {
                Log.e("UdpListenerService", "Socket error", e)
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.e("UdpListenerService", "Connection coroutine failed", e)
            }
        }
    }

    private fun sendMessage(message: String) {
        // Launch in a separate coroutine to avoid blocking the caller
        serviceScope.launch(Dispatchers.IO) {
            if (datagramSocket?.isConnected == true) {
                try {
                    val data = message.toByteArray()
                    val packet = DatagramPacket(data, data.size, InetSocketAddress(destinationAddress, 18806))
                    datagramSocket?.send(packet)
                } catch (e: Exception) {
                    Log.e("UdpListenerService", "Error sending message", e)
                }
            } else {
                Log.w("UdpListenerService", "SendMessage called but socket is not connected.")
            }
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

    fun processPacket(packet: DatagramPacket) {
        val jsonString = String(packet.data, 0, packet.length)
        try {
            val jsonObject = JSONObject(jsonString)
            Log.d("UdpListenerService", "Received JSON: $jsonObject")
            if (jsonObject.has("Alert")) {
                val alertData = jsonObject.getJSONObject("Alert")
                val seqNo = alertData.optInt("seq_no", 0)
                sendMessage("{\"Ack\":$seqNo}")

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

        // If the service is started by the alarm, restart the connection.
        // This acts as a watchdog to ensure the connection is always active.
        if (intent?.action == ACTION_SEND_REGISTRATION) {
            if (destinationAddress.isNotBlank()) {
                // Cancel any existing job and start a new one. This will re-bind the socket and send a new registration message.
                Log.d("UdpListenerService", "Alarm triggered. Restarting UDP coroutine.")
                connectionJob?.cancel()
                connectionJob = launchSocketConnection()
            } else {
                Log.w("UdpListenerService", "Alarm triggered but destination address is blank. Cannot connect.")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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
