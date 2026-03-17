package com.masjidtv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_WAKE_UP_TV = "WAKE_UP_TV"
    const val ACTION_SLEEP_TV = "SLEEP_TV"

    private const val TAG = "MasjidTV"

    fun rescheduleAll(context: Context, prefs: SharedPreferences) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel existing alarms
        for (i in 0..200) {
            cancelAlarm(context, alarmManager, ACTION_WAKE_UP_TV, i)
            cancelAlarm(context, alarmManager, ACTION_SLEEP_TV, i + 1000)
        }

        val schedulesJson = prefs.getString("SCHEDULES_JSON", null)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        var foundToday = false

        if (!schedulesJson.isNullOrBlank() && schedulesJson.length > 5) {
            try {
                val array = JSONArray(schedulesJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val dateStr = obj.optString("date", "")
                    
                    if (dateStr == todayStr) {
                        val wakeStr = obj.optString("wake", "")
                        val sleepStr = obj.optString("sleep", "")
                        
                        if (wakeStr.isNotEmpty()) {
                            scheduleExactTime(context, alarmManager, wakeStr, ACTION_WAKE_UP_TV, 0)
                        }
                        if (sleepStr.isNotEmpty()) {
                            scheduleExactTime(context, alarmManager, sleepStr, ACTION_SLEEP_TV, 1000)
                        }
                        foundToday = true
                        Log.d(TAG, "Found specific schedule for today ($todayStr): $wakeStr - $sleepStr")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing yearly schedule JSON", e)
            }
        }

        if (!foundToday) {
            Log.d(TAG, "No specific schedule found for $todayStr, using defaults.")
            fallbackSingleSchedule(context, alarmManager, prefs)
        }
        
        // Also schedule a refresh at midnight to handle date transition
        scheduleMidnightRefresh(context, alarmManager)
    }

    private fun scheduleMidnightRefresh(context: Context, alarmManager: AlarmManager) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = "REFRESH_SCHEDULE" }
        val pendingIntent = PendingIntent.getBroadcast(context, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun fallbackSingleSchedule(context: Context, alarmManager: AlarmManager, prefs: SharedPreferences) {
        scheduleExactTime(context, alarmManager, prefs.getString("WAKE_TIME", "00:00") ?: "00:00", ACTION_WAKE_UP_TV, 0)
        scheduleExactTime(context, alarmManager, prefs.getString("SLEEP_TIME", "00:00") ?: "00:00", ACTION_SLEEP_TV, 100)
    }

    private fun cancelAlarm(context: Context, alarmManager: AlarmManager, actionName: String, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = actionName }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun scheduleExactTime(context: Context, alarmManager: AlarmManager, timeStr: String, actionName: String, requestCode: Int) {
        val parsed = parseTime(timeStr)
        if (parsed == null) {
            Log.w(TAG, "Invalid time: $timeStr")
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

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = actionName
        }

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
            Log.d(TAG, "Scheduled $actionName to $timeStr (ID: $requestCode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule $actionName", e)
        }
    }

    private fun parseTime(timeStr: String): Pair<Int, Int>? {
        val parts = timeStr.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
