package com.masjidtv

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MasjidTV", "Alarm Received: $action")

        val prefs: SharedPreferences = context.getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        if (action == "WAKE_UP_TV") {
            // Wake up screen
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MasjidTV:WakeUpScreen"
            )
            wakeLock.acquire(3000)

            // Launch the selected application
            val packageToLaunch = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageToLaunch!!)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                }
            } catch (e: Exception) { e.printStackTrace() }

        } else if (action == "SLEEP_TV") {
            // Force screen off
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, TvDeviceAdminReceiver::class.java)

            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow() // Locks and turns off the screen
                Log.d("MasjidTV", "Screen Locked/Turned off")
            } else {
                Log.e("MasjidTV", "Cannot turn off screen, Admin not active!")
            }
        }
    }
}
