package com.masjidtv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvWakeTime: TextView
    private lateinit var tvSleepTime: TextView
    private lateinit var tvAppPackage: TextView

    // Supabase API details
    private val apiUrl = "https://dxljqnchxdyhxlppbeip.supabase.co/rest/v1/tv_settings?id=eq.1&select=*"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA4ODk2ODA3NH0.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"
    
    // OkHttp Client
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        tvWakeTime = findViewById(R.id.tvWakeTime)
        tvSleepTime = findViewById(R.id.tvSleepTime)
        tvAppPackage = findViewById(R.id.tvAppPackage)

        val btnSyncCloud = findViewById<Button>(R.id.btnSyncCloud)
        val btnEnableAdmin = findViewById<Button>(R.id.btnEnableAdmin)

        updateUI()

        btnSyncCloud.setOnClickListener {
            Toast.makeText(this, "جاري الاتصال بالسحابة...", Toast.LENGTH_SHORT).show()
            syncWithSupabase()
        }

        btnEnableAdmin.setOnClickListener {
            val componentName = ComponentName(this, TvDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يُستخدم هذا الإذن لإطفاء الشاشة تلقائياً.")
            }
            startActivity(intent)
        }
    }

    private fun syncWithSupabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val responseData = response.body?.string()

                if (response.isSuccessful && responseData != null) {
                    val jsonArray = JSONArray(responseData)
                    if (jsonArray.length() > 0) {
                        val setting = jsonArray.getJSONObject(0)
                        
                        val newWakeTime = setting.optString("wake_time", "00:00")
                        val newSleepTime = setting.optString("sleep_time", "00:00")
                        val newAppPkg = setting.optString("app_package", "com.google.android.youtube")

                        // Remove seconds if present (e.g. 05:30:00 -> 05:30)
                        val formattedWake = formatTime(newWakeTime)
                        val formattedSleep = formatTime(newSleepTime)

                        // Save locally
                        prefs.edit().apply {
                            putString("WAKE_TIME", formattedWake)
                            putString("SLEEP_TIME", formattedSleep)
                            putString("APP_PACKAGE", newAppPkg)
                        }.apply()

                        withContext(Dispatchers.Main) {
                            updateUI()
                            scheduleAlarm("WAKE_TIME", "WAKE_UP_TV")
                            scheduleAlarm("SLEEP_TIME", "SLEEP_TV")
                            Toast.makeText(this@MainActivity, "تمت المزامنة وتفعيل الجدولة بنجاح!", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "فشل في قراءة البيانات، تأكد من الإنترنت", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ في الاتصال: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatTime(time: String): String {
        val parts = time.split(":")
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else time
    }

    private fun updateUI() {
        tvWakeTime.text = prefs.getString("WAKE_TIME", "00:00")
        tvSleepTime.text = prefs.getString("SLEEP_TIME", "00:00")
        tvAppPackage.text = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
    }

    private fun scheduleAlarm(prefKey: String, actionName: String) {
        val timeStr = prefs.getString(prefKey, "00:00") ?: "00:00"
        val parts = timeStr.split(":")
        if (parts.size != 2) return

        val hour = parts[0].toInt()
        val minute = parts[1].toInt()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = actionName
        }
        val requestCode = if (actionName == "WAKE_UP_TV") 0 else 1
        
        val pendingIntent = PendingIntent.getBroadcast(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            Log.d("MasjidTV", "Scheduled $actionName to $timeStr")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
