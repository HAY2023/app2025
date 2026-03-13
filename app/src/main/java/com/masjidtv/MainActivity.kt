package com.masjidtv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvWakeTime: TextView
    private lateinit var etAppPackage: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        tvWakeTime = findViewById(R.id.tvWakeTime)
        etAppPackage = findViewById(R.id.etAppPackage)

        val btnSetWakeTime = findViewById<Button>(R.id.btnSetWakeTime)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Load saved preferences
        tvWakeTime.text = prefs.getString("WAKE_TIME", "00:00")
        etAppPackage.setText(prefs.getString("APP_PACKAGE", "com.google.android.youtube"))

        btnSetWakeTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            TimePickerDialog(this, { _, selectedHour, selectedMinute ->
                val timeString = String.format("%02d:%02d", selectedHour, selectedMinute)
                tvWakeTime.text = timeString
                prefs.edit().putString("WAKE_TIME", timeString).apply()
            }, hour, minute, true).show()
        }

        btnSave.setOnClickListener {
            val packageToLaunch = etAppPackage.text.toString()
            prefs.edit().putString("APP_PACKAGE", packageToLaunch).apply()
            scheduleWakeAlarm()
            Toast.makeText(this, "تم الحفظ بنجاح وتفعيل المؤقت!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleWakeAlarm() {
        val wakeTime = prefs.getString("WAKE_TIME", "00:00") ?: "00:00"
        val parts = wakeTime.split(":")
        if (parts.size != 2) return

        val hour = parts[0].toInt()
        val minute = parts[1].toInt()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1) // Schedule for tomorrow if time passed
            }
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "WAKE_UP_TV"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "الرجاء إعطاء صلاحية الجدولة الدقيقة من الإعدادات", Toast.LENGTH_LONG).show()
        }
    }
}
