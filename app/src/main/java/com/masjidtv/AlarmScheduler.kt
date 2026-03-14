package com.masjidtv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_WAKE_UP_TV = "WAKE_UP_TV"
    const val ACTION_SLEEP_TV = "SLEEP_TV"

    private const val TAG = "MasjidTV"

    fun rescheduleAll(context: Context, prefs: SharedPreferences) {
        scheduleAlarm(context, prefs, "WAKE_TIME", ACTION_WAKE_UP_TV)
        scheduleAlarm(context, prefs, "SLEEP_TIME", ACTION_SLEEP_TV)
    }

    fun scheduleAlarm(context: Context, prefs: SharedPreferences, prefKey: String, actionName: String) {
        val timeStr = prefs.getString(prefKey, "00:00") ?: "00:00"
        val parsed = parseTime(timeStr)
        if (parsed == null) {
            Log.w(TAG, "Invalid time for $prefKey: $timeStr")
            return
        }

        val (hour, minute) = parsed
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = actionName
        }
        val requestCode = if (actionName == ACTION_WAKE_UP_TV) 0 else 1

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled $actionName to $timeStr")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule $actionName", e)
        }
    }

    private fun parseTime(timeStr: String): Pair<Int, Int>? {
        val parts = timeStr.split(":")
        if (parts.size < 2) return null

        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        if (hour == null || minute == null) return null
        if (hour !in 0..23 || minute !in 0..59) return null

        return hour to minute
    }
}
