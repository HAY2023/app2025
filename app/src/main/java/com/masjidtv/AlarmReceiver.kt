package com.masjidtv

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("MasjidTV", "Alarm Received: $action")

        // Make sure service is running
        val serviceIntent = Intent(context, MasjidService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        val prefs = context.getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        if (action == AlarmScheduler.ACTION_WAKE_UP_TV) {
            Log.d("MasjidTV", "WAKE alarm triggered!")
            
            // Wake up screen
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "MasjidTV:AlarmWake"
            )
            wakeLock.acquire(10000)

            // Send WAKE command to the service (which handles overlay removal)
            val wakeIntent = Intent(context, MasjidService::class.java)
            wakeIntent.action = "WAKE"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(wakeIntent)
            } else {
                context.startService(wakeIntent)
            }

            // Root wake commands
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 224"))
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 82"))
            } catch (e: Exception) {}

            // Reschedule for tomorrow
            AlarmScheduler.rescheduleAll(context, prefs)
            
        } else if (action == AlarmScheduler.ACTION_SLEEP_TV) {
            Log.d("MasjidTV", "SLEEP alarm triggered!")
            
            // Send SLEEP command to the service (which handles overlay + shutdown)
            val sleepIntent = Intent(context, MasjidService::class.java)
            sleepIntent.action = "SLEEP"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(sleepIntent)
            } else {
                context.startService(sleepIntent)
            }
            
            // Also lock via Device Admin as backup
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = ComponentName(context, TvDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(componentName)) {
                    dpm.lockNow()
                }
            } catch (e: Exception) {}

            // Reschedule for tomorrow
            AlarmScheduler.rescheduleAll(context, prefs)
        } else if (action == "REFRESH_SCHEDULE") {
            Log.d("MasjidTV", "Midnight Refresh triggered!")
            AlarmScheduler.rescheduleAll(context, prefs)
        }
    }
}
