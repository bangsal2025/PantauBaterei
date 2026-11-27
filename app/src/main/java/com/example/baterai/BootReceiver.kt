package com.example.baterai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (prefs.getBoolean("running", false)) {
            context.startForegroundService(Intent(context, BatteryService::class.java))
        }
    }
}
