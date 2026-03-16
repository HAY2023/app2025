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
    private lateinit var layoutLogin: View
    private lateinit var layoutPairing: View
    private lateinit var layoutPaired: View
    private lateinit var etPairingCode: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private var autoLaunchJob: kotlinx.coroutines.Job? = null

    // Supabase
    private val supabaseUrl = "https://dxljqnchxdyhxlppbeip.supabase.co"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA4ODk2ODA3NH0.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"

    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)

        // Try auto-granting permissions using root on startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "appops set com.masjidtv SYSTEM_ALERT_WINDOW allow")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure enabled_accessibility_services com.masjidtv/com.masjidtv.MasjidAccessibilityService")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure accessibility_enabled 1")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "dpm set-active-admin com.masjidtv/com.masjidtv.TvDeviceAdminReceiver")).waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Initialize UI
        tvWakeTime = findViewById(R.id.tvWakeTime)
        tvSleepTime = findViewById(R.id.tvSleepTime)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        layoutLogin = findViewById(R.id.layoutLogin)
        layoutPairing = findViewById(R.id.layoutPairing)
        layoutPaired = findViewById(R.id.layoutPaired)
        etPairingCode = findViewById(R.id.etPairingCode)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)

        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnToggleLoginMode = findViewById<TextView>(R.id.btnToggleLoginMode)
        val btnBackToLogin = findViewById<TextView>(R.id.btnBackToLogin)
        val btnSyncCloud = findViewById<Button>(R.id.btnSyncCloud)
        val btnPair = findViewById<Button>(R.id.btnPair)
        val btnUnpair = findViewById<Button>(R.id.btnUnpair)

        // Login Logic
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال البريد وكلمة المرور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performLogin(email, password)
        }

        btnToggleLoginMode.setOnClickListener {
            layoutLogin.visibility = View.GONE
            layoutPairing.visibility = View.VISIBLE
        }

        btnBackToLogin.setOnClickListener {
            layoutPairing.visibility = View.GONE
            layoutLogin.visibility = View.VISIBLE
        }

        // Pair device button
        btnPair.setOnClickListener {
            val code = etPairingCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "أدخل كود من 6 أرقام", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairDevice(code)
        }

        // Check pairing state
        updatePairingUI()
        updateUI()
        updateConnectionStatus()

        btnUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("إلغاء الربط")
                .setMessage("هل أنت متأكد من إلغاء ربط هذا الجهاز؟")
                .setPositiveButton("نعم") { _, _ -> unpairDevice() }
                .setNegativeButton("لا", null)
                .show()
        }

        btnSyncCloud.setOnClickListener {
            if (!isPaired()) {
                Toast.makeText(this, "يجب ربط الجهاز أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            syncWithSupabase()
        }



        // Start service and sync if already paired
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
        }
    }

    // ====== Pairing System ======

    private fun isPaired(): Boolean {
        return prefs.getString("DEVICE_ID", null) != null
    }

    private fun updatePairingUI() {
        if (isPaired()) {
            layoutLogin.visibility = View.GONE
            layoutPairing.visibility = View.GONE
            layoutPaired.visibility = View.VISIBLE
            val deviceName = prefs.getString("DEVICE_NAME", "الجهاز مربوط ✅")
            val userEmail = prefs.getString("USER_EMAIL", "بدون حساب")
            tvDeviceName.text = deviceName
            findViewById<TextView>(R.id.tvUserInfo).text = "الحساب: $userEmail"
        } else {
            layoutLogin.visibility = View.VISIBLE
            layoutPairing.visibility = View.GONE
            layoutPaired.visibility = View.GONE
        }
    }

    private fun performLogin(email: String, pass: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Supabase Auth (simplified check for demo - in prod use proper Auth SDK)
                // Here we fetch if a user exists with this email (assuming password is handled or for a simplified mock)
                // For a real implementation, you would call Supabase Auth API
                
                val url = "$supabaseUrl/auth/v1/token?grant_type=password"
                val json = """{"email":"$email","password":"$pass"}""".toRequestBody(JSON_TYPE)
                val req = Request.Builder().url(url)
                    .addHeader("apikey", apiKey)
                    .post(json).build()
                
                val res = client.newCall(req).execute()
                val data = res.body?.string()
                
                if (res.isSuccessful && data != null) {
                    val userObj = JSONObject(data).getJSONObject("user")
                    val userId = userObj.getString("id")
                    
                    // After login, we automatically "pair" or find the device assigned to this user
                    // For now, let's create/find a default device for this user
                    val deviceId = UUID.randomUUID().toString()
                    
                    // Create device entry
                    val deviceJson = JSONObject().apply {
                        put("id", deviceId)
                        put("user_id", userId)
                        put("device_name", "جهاز المسجد - دخول مباشر")
                    }
                    
                    val createReq = Request.Builder()
                        .url("$supabaseUrl/rest/v1/tv_settings")
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(deviceJson.toString().toRequestBody(JSON_TYPE))
                        .build()
                    
                    client.newCall(createReq).execute()

                    prefs.edit().apply {
                        putString("DEVICE_ID", deviceId)
                        putString("USER_ID", userId)
                        putString("USER_EMAIL", email)
                        putString("DEVICE_NAME", "جهاز المسجد (دخول)")
                    }.apply()

                    withContext(Dispatchers.Main) {
                        updatePairingUI()
                        startBackgroundService()
                        syncWithSupabase()
                    }
                } else {
                    showToast("❌ فشل تسجيل الدخول: تحقق من البيانات")
                }
            } catch (e: Exception) {
                showToast("خطأ: ${e.message}")
            }
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, MasjidService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
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
                        putString("USER_EMAIL", "مرتبط عبر الكود 🔗")
                        putString("DEVICE_NAME", "جهاز المسجد")
                        putString("PAIRING_CODE", code)
                    }.apply()

                    withContext(Dispatchers.Main) {
                        updatePairingUI()
                        Toast.makeText(this@MainActivity, "✅ تم ربط الجهاز بنجاح!", Toast.LENGTH_LONG).show()
                        syncWithSupabase()
                        
            
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
            remove("USER_EMAIL")
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
            remove("USER_EMAIL")
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
    }

    private fun checkAndRequestPermissions() {
        CoroutineScope(Dispatchers.IO).launch {
            // المحاولة التلقائية لإعطاء الصلاحيات عبر الروت (Root)
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "appops set com.masjidtv SYSTEM_ALERT_WINDOW allow")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure enabled_accessibility_services com.masjidtv/com.masjidtv.MasjidAccessibilityService")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure accessibility_enabled 1")).waitFor()
                Runtime.getRuntime().exec(arrayOf("su", "-c", "dpm set-active-admin com.masjidtv/com.masjidtv.TvDeviceAdminReceiver")).waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                checkPermissionsFallback()
            }
        }
    }

    private fun checkPermissionsFallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            Toast.makeText(this, "⚠️ يُرجى تفعيل 'الظهور فوق التطبيقات'", Toast.LENGTH_LONG).show()
            startActivityForResult(intent, 1234)
            return
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminName = android.content.ComponentName(this, TvDeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(adminName)) {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName)
            intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يُستخدم لإطفاء الشاشة تلقائياً")
            startActivity(intent)
            Toast.makeText(this, "⚠️ يُرجى تفعيل 'مسؤول الجهاز' أولاً", Toast.LENGTH_LONG).show()
            return
        }

        if (!MasjidAccessibilityService.isServiceActive) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "⚠️ يُرجى تفعيل خدمة 'مساجد' ثانياً", Toast.LENGTH_LONG).show()
        }
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
