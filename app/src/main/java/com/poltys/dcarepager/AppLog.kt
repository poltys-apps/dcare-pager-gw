package com.poltys.dcarepager

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val logs = mutableListOf<String>()

    fun add(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(logs) {
            logs.add("$timestamp: $message")
            // Keep the log size manageable
            if (logs.size > 100) {
                logs.removeAt(0)
            }
        }
    }

    fun getAndClear(): List<String> {
        return synchronized(logs) {
            val currentLogs = logs.toList()
            logs.clear()
            currentLogs
        }
    }
}