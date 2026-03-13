package com.masjidtv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvWakeTime: TextView
    private lateinit var tvSleepTime: TextView
    private lateinit var tvWebServerUrl: TextView
    private lateinit var spinnerApps: Spinner

    private var webServer: LocalWebServer? = null
    private val appList = mutableListOf<ResolveInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        tvWakeTime = findViewById(R.id.tvWakeTime)
        tvSleepTime = findViewById(R.id.tvSleepTime)
        tvWebServerUrl = findViewById(R.id.tvWebServerUrl)
        spinnerApps = findViewById(R.id.spinnerApps)

        val btnSetWakeTime = findViewById<Button>(R.id.btnSetWakeTime)
        val btnSetSleepTime = findViewById<Button>(R.id.btnSetSleepTime)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnEnableAdmin = findViewById<Button>(R.id.btnEnableAdmin)

        loadInstalledApps()
        updateUI()
        startWebServer()

        btnSetWakeTime.setOnClickListener { showTimePicker("WAKE_TIME") }
        btnSetSleepTime.setOnClickListener { showTimePicker("SLEEP_TIME") }

        btnEnableAdmin.setOnClickListener {
            val componentName = ComponentName(this, TvDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يُستخدم هذا الإذن لإطفاء الشاشة تلقائياً حسب أوقات المسجد.")
            }
            startActivity(intent)
        }

        btnSave.setOnClickListener {
            scheduleAlarm("WAKE_TIME", "WAKE_UP_TV")
            scheduleAlarm("SLEEP_TIME", "SLEEP_TV") // You must add logic in AlarmReceiver to catch "SLEEP_TV"
            Toast.makeText(this, "تم حفظ الأوقات وتفعيل الجدولة!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startWebServer() {
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        
        if (ipAddress == "0.0.0.0") {
            tvWebServerUrl.text = "يرجى توصيل התلفاز بالواي فاي أولاً للتحكم من الحاسوب"
            return
        }

        tvWebServerUrl.text = "للتحكم من أي جهاز، افتح هذا الرابط: http://$ipAddress:8080"
        
        webServer = LocalWebServer(8080, this, prefs) {
            runOnUiThread {
                updateUI()
                scheduleAlarm("WAKE_TIME", "WAKE_UP_TV")
                scheduleAlarm("SLEEP_TIME", "SLEEP_TV")
                Toast.makeText(this, "تم تحديث الإعدادات من الحاسوب!", Toast.LENGTH_LONG).show()
            }
        }
        try {
            webServer?.start() // Starts NanoHTTPD background server thread
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        // Launchers
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        // Android TV Launchers
        val tvIntent = Intent(Intent.ACTION_MAIN, null)
        tvIntent.addCategory("android.intent.category.LEANBACK_LAUNCHER")

        var activities = pm.queryIntentActivities(intent, 0)
        activities.addAll(pm.queryIntentActivities(tvIntent, 0))
        
        // Remove duplicates by package
        appList.clear()
        val mappedPackages = HashSet<String>()
        for (info in activities) {
            val pkg = info.activityInfo.packageName
            if (!mappedPackages.contains(pkg)) {
                appList.add(info)
                mappedPackages.add(pkg)
            }
        }

        // Create display names
        val displayNames = appList.map { it.loadLabel(pm).toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerApps.adapter = adapter

        // Set Default selection from pref
        val savedPkg = prefs.getString("APP_PACKAGE", "")
        val index = appList.indexOfFirst { it.activityInfo.packageName == savedPkg }
        if (index >= 0) {
            spinnerApps.setSelection(index)
        }

        spinnerApps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPkg = appList[position].activityInfo.packageName
                prefs.edit().putString("APP_PACKAGE", selectedPkg).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateUI() {
        tvWakeTime.text = prefs.getString("WAKE_TIME", "00:00")
        tvSleepTime.text = prefs.getString("SLEEP_TIME", "00:00")
        
        val savedPkg = prefs.getString("APP_PACKAGE", "")
        val index = appList.indexOfFirst { it.activityInfo.packageName == savedPkg }
        if (index >= 0) {
            spinnerApps.setSelection(index)
        }
    }

    private fun showTimePicker(prefKey: String) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            val timeString = String.format("%02d:%02d", selectedHour, selectedMinute)
            prefs.edit().putString(prefKey, timeString).apply()
            updateUI()
        }, hour, minute, true).show()
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
                add(Calendar.DATE, 1) // Next day if time already passed
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
        } catch (e: Exception) {
            // Permission needed on Android 12+ usually
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop() // Stop Local Web server when app is closed to free ports
    }
}
