package com.poltys.dcarepager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class RegistrationAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val REQUEST_CODE = 0
        private const val INTERVAL_MS = 60_000L // 1 minute

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RegistrationAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            Log.v("RegistrationAlarmReceiver", "Scheduling next alarm.")

            // On modern Android, we need to check if we can schedule exact alarms.
            // The SCHEDULE_EXACT_ALARM permission in the manifest is required.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w("RegistrationAlarmReceiver", "Cannot schedule exact alarms. Using inexact alarm.")
                // Fallback to a less precise alarm if permission is not granted.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pendingIntent
                )
            } else {
                // This will wake the device from Doze mode.
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pendingIntent
                )
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RegistrationAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d("RegistrationAlarmReceiver", "Cancelled alarm.")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.v("RegistrationAlarmReceiver", "Alarm received, starting service.")
        // Start the service to perform the action
        val serviceIntent = Intent(context, UdpListenerService::class.java).apply {
            action = UdpListenerService.ACTION_SEND_REGISTRATION
        }
        context.startService(serviceIntent)

        // IMPORTANT: Schedule the next alarm to create a repeating effect.
        schedule(context)
    }
}