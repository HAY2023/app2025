package com.masjidtv

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private lateinit var tvAppPackage: TextView
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

        // Initialize UI
        tvWakeTime = findViewById(R.id.tvWakeTime)
        tvSleepTime = findViewById(R.id.tvSleepTime)
        tvAppPackage = findViewById(R.id.tvAppPackage)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        layoutPairing = findViewById(R.id.layoutPairing)
        layoutPaired = findViewById(R.id.layoutPaired)
        etPairingCode = findViewById(R.id.etPairingCode)

        val btnSyncCloud = findViewById<Button>(R.id.btnSyncCloud)
        val btnEnableAdmin = findViewById<Button>(R.id.btnEnableAdmin)
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

        // Admin button
        btnEnableAdmin.setOnClickListener {
            val componentName = ComponentName(this, TvDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يُستخدم هذا الإذن لإطفاء الشاشة تلقائياً.")
            }
            startActivity(intent)
        }

        // Auto-sync if paired
        if (isPaired()) {
            AlarmScheduler.rescheduleAll(this, prefs)
            syncWithSupabase()
            updateOnlineStatus(true)
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

                        val formattedWake = formatTime(newWakeTime)
                        val formattedSleep = formatTime(newSleepTime)

                        prefs.edit().apply {
                            putString("WAKE_TIME", formattedWake)
                            putString("SLEEP_TIME", formattedSleep)
                            putString("APP_PACKAGE", newAppPkg)
                            putString("DEVICE_NAME", deviceName)
                        }.apply()

                        withContext(Dispatchers.Main) {
                            updateUI()
                            updatePairingUI()
                            AlarmScheduler.scheduleAlarm(
                                this@MainActivity,
                                prefs,
                                "WAKE_TIME",
                                AlarmScheduler.ACTION_WAKE_UP_TV
                            )
                            AlarmScheduler.scheduleAlarm(
                                this@MainActivity,
                                prefs,
                                "SLEEP_TIME",
                                AlarmScheduler.ACTION_SLEEP_TV
                            )
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
        tvAppPackage.text = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
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

    override fun onDestroy() {
        super.onDestroy()
        updateOnlineStatus(false)
    }
}
