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
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class AlertData {
    var delayedJob: Job? = null
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
        private const val HAS_ALARMS_CHANNEL_ID = "dcarepager_has_alarms_channel"

        private const val SILENT_NOTIFICATION_ID = 1
        private const val HAS_ALARMS_NOTIFICATION_ID = 2

        private const val LOGIN_SYNC_TIME_SECONDS = 1200L
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
    private var loginPIN: String? = null
    private var syncLoginTime: Instant? = null
    private val _loginName = MutableStateFlow("Logged Out")
    val loginName: StateFlow<String> = _loginName.asStateFlow()
    private var syncAlarmList = true

    private var delayedAlarms: MutableMap<Int, AlertData> = mutableMapOf()
    private val _alarmIds = MutableStateFlow<Map<Int, AlertData>>(emptyMap())
    val alarms: StateFlow<Map<Int, AlertData>> = _alarmIds.asStateFlow()
    private var alarmStrMap: MutableMap<String, Int> = mutableMapOf()
    private var alarmIntMap: MutableMap<Int, String> = mutableMapOf()
    private var lastAlarmId = 1000
    private var registerTime: Instant? = null
    var lastSocketOsError: Int? = null
    private var friendlyName: String = ""
    private var initializing = true
    private var lastNotificationTime = System.currentTimeMillis()
    private var lastServerReceiveTime: ULong? = null
    private var lastSyncTimestamp: Instant? = null
    private var clockOffset: Long = 0
    private var silentNotification: Boolean = false
    private var escalateProfile: Map<String, String> = emptyMap()


    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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
                val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
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

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        val dozeFilter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        registerReceiver(dozeReceiver, dozeFilter)

        // Launch a separate coroutine for each flow to ensure they are collected concurrently
        serviceScope.launch {
            settingsDataStore.destinationAddressFlow.collect { address ->
                if (address.isNotBlank() && address != destinationAddress) {
                    Log.i("UdpListenerService", "Destination address changed to: $address")
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

        serviceScope.launch {
            settingsDataStore.silentNotificationFlow.collect { newSilentNotification ->
                if (newSilentNotification != silentNotification) {
                    silentNotification = newSilentNotification
                    if (silentNotification) {
                        AppLog.add("Silent notification enabled.")
                    } else {
                        AppLog.add("Silent notification disabled.")
                    }
                }
            }
        }

        serviceScope.launch {
            settingsDataStore.loginPinFlow.collect { newLoginPin ->
                if (newLoginPin.isNotBlank() && newLoginPin != loginPIN) {
                    AppLog.add("Login PIN changed to: $newLoginPin")
                    loginPIN = newLoginPin
                    syncLoginTime = null
                }
            }
        }
    }

    private fun getAlarmId(alarmIdStr: String): Int {
        alarmStrMap[alarmIdStr]?.let {
            return it
        }
        var alarmId = alarmIdStr.toIntOrNull() ?: 0
        if (alarmId < 1000000)  {
            lastAlarmId += 1
            alarmId = lastAlarmId
        }
        alarmStrMap[alarmIdStr] = alarmId
        alarmIntMap[alarmId] = alarmIdStr
        Log.d("UdpListenerService", "AlarmStrMap: New $alarmIdStr:$alarmId")
        return alarmId
    }

    private fun syncLoginData() {
        if (loginPIN.isNullOrBlank() || (syncLoginTime != null && syncLoginTime!!.isAfter(Instant.now().minusSeconds(LOGIN_SYNC_TIME_SECONDS)))) {
            return
        }
        try {
            val urlEncodedName = URLEncoder.encode(friendlyName, "UTF-8")
            val mURL = URL("http://$destinationAddress/config/escalate_config.json?pin=$loginPIN&name=$urlEncodedName")
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

                    syncLoginTime = Instant.now()
                    val loginResponseJson = JSONObject(response.toString())
                    val signIns = loginResponseJson.getJSONArray("sign_ins")
                    if (signIns.length() == 0) {
                        Log.e("UdpListenerService", "Invalid PIN.")
                        return@with
                    }
                    val loginName = (signIns[0] as JSONObject).getString("username")
                    val profiles = loginResponseJson.getJSONObject("profiles")
                    val profilesMap = mutableMapOf<String, String>()
                    for (key in profiles.keys()) {
                        profilesMap[key] = profiles.getString(key)
                    }
                    _loginName.value = loginName
                    escalateProfile = profilesMap
                    Log.i("UdpListenerService", "Login profile data: $escalateProfile for $loginPIN ($loginName)")
                }
            }
        } catch (e: Exception) {
            Log.e("UdpListenerService", "Error getting login profiles", e)
        }

    }

    // return null if alarm is invalid for current login, 0 if alarm need to be processed immediately, or delay in seconds
    private fun manageProfiles(alarmJson: JSONObject) : Int? {
        var delay: Int? = 0
        if (!escalateProfile.isEmpty()) {
            val profileDelays = alarmJson.optJSONObject("profile_delays")
            if (profileDelays != null) {
                delay = null
                for (key in profileDelays.keys()) {
                    if (escalateProfile.keys.contains(key)) {
                        val pDelay = profileDelays.getInt(key)
                        if (delay == null || pDelay < delay) {
                            delay = pDelay
                        }
                        Log.d("UdpListenerService", "Profile delay: $key -> $pDelay => $delay")
                    }
                }
            }
        }
        return delay
    }

    private fun syncServerAlarmList() {
        if (!syncAlarmList) {
            return
        }
        try {
            val mURL = URL("http://$destinationAddress/alarms.json")
            with(mURL.openConnection() as HttpURLConnection) {
                requestMethod = "GET"
                BufferedReader(InputStreamReader(inputStream)).use {
                    val localUtcTime = Instant.now()
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    it.close()
                    val alarmDataJson = JSONObject(response.toString())
                    val timestampStr = alarmDataJson.getString("timestamp")
                    lastSyncTimestamp = parseTimestamp(timestampStr)
                    clockOffset = localUtcTime.toEpochMilli() - lastSyncTimestamp!!.toEpochMilli()
                    val jsonList = alarmDataJson.getJSONArray("alarms")
                    Log.d("UdpListenerService", "AlarmList: $lastSyncTimestamp count: ${jsonList.length()} Clock Offset: $clockOffset")
                    val newAlarms = mutableMapOf<Int, AlertData>()
                    delayedAlarms.clear()
                    for (i in 0 until jsonList.length()) {
                        val alarmJson = jsonList.getJSONObject(i)
                        val alarmIdStr = alarmJson.getString("id")
                        val alarmId = getAlarmId(alarmIdStr)
                        // check if alarmId is already in the list

                        val sender = alarmJson.getString("device_name")
                        val message = alarmJson.getString("subject")
                        val timestampStr = alarmJson.getString("timestamp")
                        val timestamp = parseTimestamp(timestampStr).plusMillis(clockOffset)
                        val priority = alarmJson.optInt("priority", 4)
                        val delay = manageProfiles(alarmJson) ?: continue

                        if (delay > 0) {
                            val delayedTimestamp =
                                timestamp.plusSeconds(delay.toLong())

                            val delayToTrigger = Duration.between(Instant.now(), delayedTimestamp).toMillis()
                            addDelayedAlarm(alarmId, AlertData().apply {
                                this.sender = sender
                                this.message = message
                                this.priority = priority
                            }, delayToTrigger)
                            Log.i(
                                "UdpListenerService",
                                "[$alarmIdStr:$alarmId] AlarmList delayed $delay Sender: $sender, Message: $message $timestamp +$delayToTrigger ms"
                            )
                        } else {
                            Log.i(
                                "UdpListenerService",
                                "[$alarmIdStr:$alarmId] AlarmList Sender: $sender, Message: $message $timestamp"
                            )
                            newAlarms[alarmId] = AlertData().apply {
                                this.sender = sender
                                this.message = message
                                this.timestamp = timestamp
                                this.priority = priority
                            }
                        }
                    }
                    _alarmIds.value = newAlarms
                    syncAlarmList = false
                }

            }
        } catch (e: Exception) {
            Log.e("UdpListenerService", "Error getting alarm list ${e.message}")
        }
    }

    private fun addDelayedAlarm(id: Int, alertData: AlertData, delay: Long) {
        alertData.delayedJob = launchDelayedAlarms(id, delay)
        delayedAlarms[id] = alertData
    }

    private fun removeDelayedAlarm(id: Int, resetSubId: Boolean) {
        if (resetSubId) {
            val iterator = delayedAlarms.iterator()
            while (iterator.hasNext()) {
                val (notifiedId, data) = iterator.next()
                if (notifiedId / 10 == id / 10) {
                    Log.i("UdpListenerService", "[$id:$notifiedId] CLEARED delayed by RESET")
                    data.delayedJob?.cancel()
                    iterator.remove()
                }
            }
        } else {
            val data = delayedAlarms.remove(id)
            data?.delayedJob?.cancel()
        }
    }

    private fun notifyAlarm(alarmId: Int, alertData: AlertData) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(
            this,
            if (silentNotification) SILENT_CHANNEL_ID else EMERGENCY_CHANNEL_ID
        )
            .setContentTitle(alertData.sender)
            .setContentText(alertData.message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        val newAlarms = _alarmIds.value.toMutableMap()
        newAlarms[alarmId] = AlertData().apply {
            this.sender = alertData.sender
            this.message = alertData.message
            this.priority = alertData.priority
            this.timestamp = alertData.timestamp
        }
        _alarmIds.value = newAlarms

        notificationManager.notify(alarmId, notification)
        if (!silentNotification)
            lastNotificationTime = System.currentTimeMillis()
    }

    private fun launchDelayedAlarms(alarmId: Int, delay: Long) = serviceScope.launch(Dispatchers.IO) {
        delay(delay)
        delayedAlarms.remove(alarmId)?.let {
            Log.i("UdpListenerService", "[$alarmId] Delayed Alarm triggered.")
            notifyAlarm(alarmId, it)
        }
    }

    private fun launchSocketConnection() = serviceScope.launch(Dispatchers.IO) {
        while (isActive) {
            var received = false
            try {
                if (datagramSocket == null || datagramSocket!!.isClosed) {
                    datagramSocket = DatagramSocket()
                    datagramSocket?.soTimeout = 15000
                    datagramSocket!!.connect(InetSocketAddress(destinationAddress, 18806))
                    Log.i("UdpListenerService", "Socket connected to $destinationAddress")
                    syncAlarmList = true
                }
                syncServerAlarmList()
                syncLoginData()

                if (registerTime == null || registerTime!!.isBefore(Instant.now().minusSeconds(30))) {
                    registerTime = Instant.now()
                    Log.v("UdpListenerService", "Sending register and logs")
                    localSeqNo += 1
                    sendMessage("""{"Register":{"seq_no":$localSeqNo,"friendly_name":"$friendlyName"}}""")

                    val logs = AppLog.getAndClear()
                    if (!initializing && logs.isNotEmpty()) {
                        val logJson = JSONObject()
                        logJson.put("Logs", JSONArray(logs))
                        sendMessage(logJson.toString())
                    }
                    initializing = false
                }

                val packet = DatagramPacket(buffer, buffer.size)
                datagramSocket?.receive(packet)
                processPacket(packet)
                received = true
            } catch (e: SocketTimeoutException) {
                Log.d("UdpListenerService", "SocketTimeoutException: ${e.message}")
                // ignore
            } catch (e: SocketException) {
                val cause = e.cause
                var osError: String
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
                if (e !is CancellationException) {
                    AppLog.add("Coroutine loop failed: ${e.message}. Retrying in 10s.")
                    Log.e("UdpListenerService", "Connection coroutine failed", e)
                    datagramSocket?.close()
                    datagramSocket = null
                    delay(10_000)
                }
            }
            if (!received && lastServerReceiveTime != null && (System.currentTimeMillis().toULong() - lastServerReceiveTime!!) > 90_000UL) {
                notifyStatus(false)
                lastServerReceiveTime = null
            }
        }
    }

    private fun notifyStatus(connected: Boolean) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val contentTextId = if (connected) R.string.udp_listener_connected else R.string.udp_listener_not_connected
        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.udp_listener_content_title))
            .setContentText(getString(contentTextId))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        notificationManager.notify(SILENT_NOTIFICATION_ID, notification)
        Log.d("UdpListenerService", "Status notification sent. Connected: $connected")
    }

    private fun sendMessage(message: String) {
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

    private fun processPacket(packet: DatagramPacket) {
        val jsonString = String(packet.data, 0, packet.length)
        try {
            val jsonObject = JSONObject(jsonString)
            if (jsonObject.has("Alert")) {
                val alertData = jsonObject.getJSONObject("Alert")
                Log.d("UdpListenerService", "Received Alert JSON: $alertData")
                val seqNo = alertData.optInt("seq_no", 0)
                sendMessage("""{"Ack":$seqNo}""" )
                processAlertData(alertData)
            } else if (jsonObject.has("Notifies")) {
                val notifies = jsonObject.getJSONArray("Notifies")
                Log.d("UdpListenerService", "Received Notifies count: ${notifies.length()}")
                val armedAlerts: MutableMap<Int, JSONObject> = mutableMapOf()
                for (i in 0 until notifies.length()) {
                    val notify = notifies.getJSONObject(i)
                    if (notify.has("Alert")) {
                        val alertData = notify.getJSONObject("Alert")
                        val seqNo = alertData.optInt("seq_no", 0)
                        sendMessage("""{"Ack":$seqNo}""")
                        val timestamp = parseTimestamp(alertData.optString("timestamp"))
                        if (lastSyncTimestamp != null && timestamp.isBefore(lastSyncTimestamp)) {
                            Log.v("UdpListenerService", "Ignore Retransmit Alert timestamp: $timestamp")
                            continue
                        }
                        Log.d("UdpListenerService", "Received Retransmit Alert JSON: $alertData")

                        val armed = alertData.optBoolean("armed", false)
                        val alarmIdStr = alertData.optString("id", "0")
                        val alarmId = (alarmIdStr.toULongOrNull() ?: 0UL).toInt()

                        if (armed) {
                            armedAlerts[alarmId] = alertData
                        } else {
                            val hasSubId = alertData.optBoolean("has_sub_id", false)
                            val reset = alertData.optBoolean("reset", false)
                            val iterator = armedAlerts.iterator()
                            var found = false

                            while (iterator.hasNext()) {
                                val (oldAlarmId, _) = iterator.next()
                                if (reset && hasSubId) {
                                    if (oldAlarmId / 10 == alarmId / 10) {
                                        Log.d(
                                            "UdpListenerService",
                                            "Retransmit: Ignore reset alarm $oldAlarmId"
                                        )
                                        iterator.remove()
                                        found = true
                                    }
                                } else {
                                    val extraIds = alertData.optJSONArray("extra_ids")
                                    if (extraIds != null) {
                                        for (j in 0 until extraIds.length()) {
                                            if (extraIds.getInt(j) == oldAlarmId) {
                                                Log.d(
                                                    "UdpListenerService",
                                                    "Retransmit: Ignore extra_id alarm $oldAlarmId"
                                                )
                                                iterator.remove()
                                                found = true
                                                break
                                            }
                                        }
                                    }
                                    if (oldAlarmId == alarmId) {
                                        Log.d(
                                            "UdpListenerService",
                                            "Retransmit: Ignore cleared alarm $oldAlarmId"
                                        )
                                        iterator.remove()
                                        found = true
                                    }
                                }
                            }
                            if (!found) {
                                processAlertData(alertData)
                            }
                        }
                    }
                }
                // process active alarms
                for ((_, alarmData) in armedAlerts) {
                    processAlertData(alarmData)
                }
            } else {
                Log.v("UdpListenerService", "Received JSON: $jsonString")
            }
        } catch (e: JSONException) {
            Log.e("UdpListenerService", "Error parsing JSON", e)
        }
        if (!alarms.value.isEmpty() && System.currentTimeMillis() - lastNotificationTime > 60_000 ) {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, HAS_ALARMS_CHANNEL_ID)
                .setContentTitle(getString(R.string.has_alarms_content_title))
                .setContentText(getString(R.string.has_alarms_content_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            notificationManager.notify(HAS_ALARMS_NOTIFICATION_ID, notification)
            lastNotificationTime = System.currentTimeMillis()
            Log.d("UdpListenerService", "Has alarms notification sent.")
        } else if (alarms.value.isEmpty()) {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(HAS_ALARMS_NOTIFICATION_ID)
        }
        if (lastServerReceiveTime == null) {
            notifyStatus(true)
        }
        lastServerReceiveTime = System.currentTimeMillis().toULong()
    }

    private fun processAlertData(
        alertData: JSONObject
    ) {
        val seqNo = alertData.optInt("seq_no", 0)
        val armed = alertData.optBoolean("armed", false)
        val alarmIdStr = alertData.optString("id", "0")
        val alarmId = getAlarmId(alarmIdStr)
        val hasSubId = alertData.optBoolean("has_sub_id", false)

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (armed) {
            val sender =
                alertData.optString("device_name", "") + " " + alertData.optString("resident", "")
            val message = alertData.optString("subject", "")
            val priority = alertData.optInt("priority", 4) // 4 = Normal
            val timestampStr = alertData.optString("timestamp")
            val timestamp = parseTimestamp(timestampStr).plusMillis(clockOffset)
            val delay = manageProfiles(alertData) ?: return
            if (delay > 0) {
                val delayedTimestamp = timestamp.plusSeconds(delay.toLong())
                val delayToTrigger = Duration.between(Instant.now(), delayedTimestamp).toMillis()
                addDelayedAlarm(alarmId, AlertData().apply {
                    this.sender = sender
                    this.message = message
                    this.priority = priority
                }, delayToTrigger)
                Log.i(
                    "UdpListenerService",
                    "[$alarmIdStr:$alarmId] Notify delayed $delay Sender: $sender, Message: $message, seqNo: $seqNo $timestamp +$delayToTrigger ms"
                )
            } else {
                Log.i(
                    "UdpListenerService",
                    "[$alarmIdStr:$alarmId] Notify Sender: $sender, Message: $message, seqNo: $seqNo $timestamp"
                )
                notifyAlarm(alarmId, AlertData().apply {
                    this.sender = sender
                    this.message = message
                    this.priority = priority
                    this.timestamp = timestamp
                })
            }

        } else {
            val reset = alertData.optBoolean("reset", false)
            if (reset && hasSubId) {
                Log.i("UdpListenerService", "[$alarmIdStr:$alarmId] CLEARED RESET & sub_id seqNo: $seqNo")
                val newAlarms = _alarmIds.value.toMutableMap()
                val iterator = newAlarms.iterator()
                while (iterator.hasNext()) {
                    val (notifiedId, _) = iterator.next()
                    if (notifiedId / 10 == alarmId / 10) {
                        Log.i("UdpListenerService", "[$alarmId:$notifiedId] CLEARED by RESET")
                        notificationManager.cancel(notifiedId)
                        iterator.remove()
                    }
                }
                _alarmIds.value = newAlarms
                removeDelayedAlarm(alarmId, true)
            } else {
                Log.i("UdpListenerService", "[$alarmIdStr:$alarmId] CLEARED seqNo: $seqNo")
                val newAlarms = _alarmIds.value.toMutableMap()
                removeDelayedAlarm(alarmId, false)
                newAlarms.remove(alarmId)
                notificationManager.cancel(alarmId)

                val extraIds = alertData.optJSONArray("extra_ids")
                if (extraIds != null) {
                    for (j in 0 until extraIds.length()) {
                        val extraId = extraIds.getInt(j)
                        val alarmId = getAlarmId(extraId.toString())
                        Log.i("UdpListenerService", "[$extraId:$alarmId] CLEARED by extraIds")
                        newAlarms.remove(alarmId)
                        notificationManager.cancel(alarmId)
                        removeDelayedAlarm(alarmId, false)
                    }
                }
                _alarmIds.value = newAlarms
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contentTextId = if (lastServerReceiveTime != null) R.string.udp_listener_connected else R.string.udp_listener_not_connected
        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setContentTitle(getString(R.string.udp_listener_content_title))
            .setContentText(getString(contentTextId))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(SILENT_NOTIFICATION_ID, notification)

        if (intent?.action == ACTION_SEND_REGISTRATION) {
            if (destinationAddress.isNotBlank()) {
                // Cancel any existing job and start a new one. This will re-bind the socket and send a new registration message.
                Log.v("UdpListenerService", "Alarm triggered. Restarting UDP coroutine.")
                connectionJob?.cancel()
                connectionJob = launchSocketConnection()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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

        // Has Alarms Channel to notify if there are active alarms every minute
        val hasAlarmsSoundUri = "android.resource://$packageName/${R.raw.beep}".toUri()
        val hasAlarmsChannel = NotificationChannel(
            HAS_ALARMS_CHANNEL_ID,
            "Active Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "For alarms that are currently active."
            setSound(hasAlarmsSoundUri, audioAttributes)
        }
        notificationManager.createNotificationChannel(hasAlarmsChannel)

    }
}
