package com.masjidtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("MasjidTV", "BootReceiver: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("DEVICE_ID", null)

            if (deviceId != null) {
                // Reschedule Wake/Sleep timers
                AlarmScheduler.rescheduleAll(context, prefs)
                
                // Start service directly (don't open MainActivity on boot)
                val serviceIntent = Intent(context, MasjidService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("MasjidTV", "Service started on boot")
            }
        }
    }
}
