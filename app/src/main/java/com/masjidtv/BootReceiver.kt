package com.masjidtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("DEVICE_ID", null)

            if (deviceId != null) {
                // Reschedule Wake/Sleep timers
                AlarmScheduler.rescheduleAll(context, prefs)
                
                // 🚀 Start MainActivity automatically if Paired
                val launchIntent = Intent(context, MainActivity::class.java)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
            }
        }
    }
}
