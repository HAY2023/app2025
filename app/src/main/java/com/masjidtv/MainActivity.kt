package com.masjidtv

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // UI Elements
    private lateinit var tvWakeTime: TextView
    private lateinit var tvSleepTime: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var layoutPairing: LinearLayout
    private lateinit var layoutPaired: LinearLayout
    private lateinit var etPairingCode: EditText

    // Supabase
    private val supabaseUrl = "https://dxljqnchxdyhxlppbeip.supabase.co"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA4ODk2ODA3NH0.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"

    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        // طلب صلاحية الظهور فوق التطبيقات للإطفاء الوهمي (Fake Sleep)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            Toast.makeText(this, "أرجوك وافق على صلاحية 'الظهور فوق التطبيقات' لإطفاء التلفاز", Toast.LENGTH_LONG).show()
            startActivityForResult(intent, 1234)
        }

        // Initialize UI
        tvWakeTime = findViewById(R.id.tvWakeTime)
        tvSleepTime = findViewById(R.id.tvSleepTime)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        layoutPairing = findViewById(R.id.layoutPairing)
        layoutPaired = findViewById(R.id.layoutPaired)
        etPairingCode = findViewById(R.id.etPairingCode)

        val btnSyncCloud = findViewById<Button>(R.id.btnSyncCloud)
        val btnSelectApp = findViewById<Button>(R.id.btnSelectApp)
        val btnPair = findViewById<Button>(R.id.btnPair)
        val btnUnpair = findViewById<Button>(R.id.btnUnpair)

        // Check pairing state
        updatePairingUI()
        updateUI()
        updateConnectionStatus()

        // Pair device button
        btnPair.setOnClickListener {
            val code = etPairingCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "أدخل كود من 6 أرقام", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "جاري الربط...", Toast.LENGTH_SHORT).show()
            pairDevice(code)
        }

        // Unpair device button
        btnUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("إلغاء الربط")
                .setMessage("هل أنت متأكد من إلغاء ربط هذا الجهاز؟")
                .setPositiveButton("نعم") { _, _ -> unpairDevice() }
                .setNegativeButton("لا", null)
                .show()
        }

        // Sync button
        btnSyncCloud.setOnClickListener {
            if (!isPaired()) {
                Toast.makeText(this, "يجب ربط الجهاز أولاً بكود من الموقع", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "☁️ جاري المزامنة...", Toast.LENGTH_SHORT).show()
            syncWithSupabase()
        }

        // Select Target App and Check Accessibility
        updateAppBtnText(btnSelectApp)
        btnSelectApp.setOnClickListener {
            showAppSelectionDialog(btnSelectApp)
        }
        btnSelectApp.setOnLongClickListener {
            showTestRemoteDialog()
            true
        }

        // Auto-launch the TV App after exactly 5 seconds if already paired
        if (isPaired()) {
            AlarmScheduler.rescheduleAll(this, prefs)
            syncWithSupabase()
            
            // بدء الخدمة المخفية للتواصل الدائم مع الموقع
            val serviceIntent = Intent(this, MasjidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // تأخير 5 ثواني ثم الدخول المباشر
            tvConnectionStatus.text = "سيتم تشغيل تطبيق القناة بعد 5 ثواني..."
            tvConnectionStatus.textSize = 22f
            tvConnectionStatus.setTextColor(android.graphics.Color.YELLOW)
            
            CoroutineScope(Dispatchers.Main).launch {
                delay(5000)
                val targetApp = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
                val launchIntent = packageManager.getLaunchIntentForPackage(targetApp!!)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@MainActivity, "التطبيق غير مثبت", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ====== Pairing System ======

    private fun isPaired(): Boolean {
        return prefs.getString("DEVICE_ID", null) != null
    }

    private fun updatePairingUI() {
        if (isPaired()) {
            layoutPairing.visibility = View.GONE
            layoutPaired.visibility = View.VISIBLE
            tvDeviceName.text = prefs.getString("DEVICE_NAME", "جهاز المسجد")
        } else {
            layoutPairing.visibility = View.VISIBLE
            layoutPaired.visibility = View.GONE
        }
    }

    private fun pairDevice(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Check if code exists and is valid
                val checkUrl = "$supabaseUrl/rest/v1/pairing_codes?code=eq.$code&used=eq.false&select=*"
                val checkRequest = Request.Builder()
                    .url(checkUrl)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                val checkResponse = client.newCall(checkRequest).execute()
                val checkData = checkResponse.body?.string()

                if (!checkResponse.isSuccessful || checkData == null) {
                    showToast("خطأ في الاتصال بالخادم")
                    return@launch
                }

                val codes = JSONArray(checkData)
                if (codes.length() == 0) {
                    showToast("❌ الكود غير صحيح أو منتهي الصلاحية")
                    return@launch
                }

                val codeObj = codes.getJSONObject(0)
                val userId = codeObj.getString("user_id")
                val codeId = codeObj.getString("id")

                // Check expiry
                val expiresAt = codeObj.getString("expires_at")
                // Simple check - if we got here, the code exists

                // 2. Mark code as used
                val markUsedUrl = "$supabaseUrl/rest/v1/pairing_codes?id=eq.$codeId"
                val markBody = """{"used": true}""".toRequestBody(JSON_TYPE)
                val markRequest = Request.Builder()
                    .url(markUsedUrl)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Prefer", "return=minimal")
                    .patch(markBody)
                    .build()
                client.newCall(markRequest).execute()

                // 3. Create device entry in tv_settings
                val deviceId = UUID.randomUUID().toString()
                val deviceJson = JSONObject().apply {
                    put("id", deviceId)
                    put("user_id", userId)
                    put("device_name", "جهاز المسجد")
                    put("device_code", code)
                    put("is_online", true)
                }

                val createUrl = "$supabaseUrl/rest/v1/tv_settings"
                val createBody = deviceJson.toString().toRequestBody(JSON_TYPE)
                val createRequest = Request.Builder()
                    .url(createUrl)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Prefer", "return=representation")
                    .post(createBody)
                    .build()

                val createResponse = client.newCall(createRequest).execute()

                if (createResponse.isSuccessful) {
                    // Save pairing info locally
                    prefs.edit().apply {
                        putString("DEVICE_ID", deviceId)
                        putString("USER_ID", userId)
                        putString("DEVICE_NAME", "جهاز المسجد")
                        putString("PAIRING_CODE", code)
                    }.apply()

                    withContext(Dispatchers.Main) {
                        updatePairingUI()
                        Toast.makeText(this@MainActivity, "✅ تم ربط الجهاز بنجاح!", Toast.LENGTH_LONG).show()
                        syncWithSupabase()
                        
                        // تفعيل ميزة "جهاز التحكم التجريبي" عند النقر الطويل على زر الإعدادات
            btnSelectApp.setOnLongClickListener {
                showTestRemoteDialog()
                true
            }
            
            // إظهار نافذة العد التنازلي
                        val serviceIntent = Intent(this@MainActivity, MasjidService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                } else {
                    showToast("خطأ في إنشاء الجهاز")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showToast("خطأ: ${e.message}")
            }
        }
    }

    private fun unpairDevice() {
        val deviceId = prefs.getString("DEVICE_ID", null)

        // Remove from server
        if (deviceId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val deleteUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
                    val deleteRequest = Request.Builder()
                        .url(deleteUrl)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .delete()
                        .build()
                    client.newCall(deleteRequest).execute()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Clear local data
        prefs.edit().apply {
            remove("DEVICE_ID")
            remove("USER_ID")
            remove("DEVICE_NAME")
            remove("PAIRING_CODE")
        }.apply()

        updatePairingUI()
        Toast.makeText(this, "تم إلغاء الربط", Toast.LENGTH_SHORT).show()
    }

    private fun unpairDeviceLocalOnly() {
        prefs.edit().apply {
            remove("DEVICE_ID")
            remove("USER_ID")
            remove("DEVICE_NAME")
            remove("PAIRING_CODE")
        }.apply()
        
        stopService(Intent(this, MasjidService::class.java))
        updatePairingUI()
        Toast.makeText(this, "تم إلغاء الربط لحذف الجهاز من الموقع", Toast.LENGTH_LONG).show()
    }

    // ====== Sync System ======

    private fun syncWithSupabase() {
        val deviceId = prefs.getString("DEVICE_ID", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId&select=*"
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
                        val deviceName = setting.optString("device_name", "جهاز المسجد")
                        val schedulesJsonStr = setting.optString("schedules_json", "")

                        val formattedWake = formatTime(newWakeTime)
                        val formattedSleep = formatTime(newSleepTime)

                        prefs.edit().apply {
                            putString("WAKE_TIME", formattedWake)
                            putString("SLEEP_TIME", formattedSleep)
                            putString("APP_PACKAGE", newAppPkg)
                            putString("DEVICE_NAME", deviceName)
                            putString("SCHEDULES_JSON", schedulesJsonStr)
                        }.apply()

                        withContext(Dispatchers.Main) {
                            updateUI()
                            updatePairingUI()
                            AlarmScheduler.rescheduleAll(this@MainActivity, prefs)
                            Toast.makeText(this@MainActivity, "✅ تمت المزامنة بنجاح!", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    showToast("فشل في قراءة البيانات")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("خطأ في الاتصال: ${e.message}")
            }
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        val deviceId = prefs.getString("DEVICE_ID", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
                val body = """{"is_online": $isOnline, "last_seen": "${nowUtcIso()}"}"""
                    .toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build()

                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ====== Helpers ======

    private fun formatTime(time: String): String {
        val parts = time.split(":")
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else time
    }

    private fun updateUI() {
        tvWakeTime.text = prefs.getString("WAKE_TIME", "00:00")
        tvSleepTime.text = prefs.getString("SLEEP_TIME", "00:00")
        val btnSelectApp = findViewById<Button>(R.id.btnSelectApp)
        if (btnSelectApp != null) {
            updateAppBtnText(btnSelectApp)
        }
    }

    private fun updateConnectionStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        tvConnectionStatus.text = if (isConnected) "🟢 متصل بالإنترنت" else "🔴 غير متصل بالإنترنت"
        tvConnectionStatus.setTextColor(
            if (isConnected) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#F44336")
        )
    }

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nowUtcIso(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    override fun onResume() {
        super.onResume()
        val btnSelectApp = findViewById<Button>(R.id.btnSelectApp)
        if (btnSelectApp != null) updateAppBtnText(btnSelectApp)
    }

    private fun updateAppBtnText(btn: Button) {
        val currentApp = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
        var appName = "يوتيوب (افتراضي)"
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(currentApp!!, 0)
            appName = pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {}

        if (MasjidAccessibilityService.isServiceActive) {
            btn.text = "صلاحية الإطفاء مفعلة ✅ - تطبيق: $appName"
        } else {
            btn.text = "التطبيق الحالي: $appName - اضغط لاختيار التطبيق أو تفعيل الإطفاء"
        }
    }

    private fun showAppSelectionDialog(btn: Button) {
        val i = Intent(Intent.ACTION_MAIN, null)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, 0)
        
        val appNames = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        val appPackages = apps.map { it.activityInfo.packageName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("اختر التطبيق المفضل")
            .setItems(appNames) { _, which ->
                val selectedPkg = appPackages[which]
                prefs.edit().putString("APP_PACKAGE", selectedPkg).apply()
                updateAppBtnText(btn)
                Toast.makeText(this, "تم اختيار التطبيق بنجاح", Toast.LENGTH_SHORT).show()
                
                // Prompt Accessibility if missing
                if (!MasjidAccessibilityService.isServiceActive) {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(this, "⚠️ من فضلك فعّل خدمة مساجد (Masjid TV) من الإعدادات لإطفاء الشاشة!", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("تفعيل صلاحية الإطفاء فقط") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTestRemoteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_remote, null)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.btnUp).setOnClickListener { sendTestCmd("UP") }
        dialogView.findViewById<Button>(R.id.btnDown).setOnClickListener { sendTestCmd("DOWN") }
        dialogView.findViewById<Button>(R.id.btnLeft).setOnClickListener { sendTestCmd("LEFT") }
        dialogView.findViewById<Button>(R.id.btnRight).setOnClickListener { sendTestCmd("RIGHT") }
        dialogView.findViewById<Button>(R.id.btnEnter).setOnClickListener { sendTestCmd("ENTER") }
        dialogView.findViewById<Button>(R.id.btnBack).setOnClickListener { sendTestCmd("BACK") }
        dialogView.findViewById<Button>(R.id.btnHome).setOnClickListener { sendTestCmd("HOME") }
        dialogView.findViewById<Button>(R.id.btnCloseRemote).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun sendTestCmd(cmd: String) {
        val deviceId = prefs.getString("DEVICE_ID", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
                val body = """{"pending_command": "$cmd"}""".toRequestBody(JSON_TYPE)
                val req = Request.Builder()
                    .url(url)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .patch(body).build()
                client.newCall(req).execute()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "تم إرسال أمر: $cmd", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoLaunchJob?.cancel()
    }
}
