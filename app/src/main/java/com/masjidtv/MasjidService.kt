package com.masjidtv

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class MasjidService : Service() {

    private val supabaseUrl = "https://dxljqnchxdyhxlppbeip.supabase.co"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA1Njc3ODc0N30.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"
    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private var monitorJob: Job? = null
    private var screenshotJob: Job? = null
    private var powerEnforcerJob: Job? = null
    private var windowManager: WindowManager? = null
    private var fakeSleepView: View? = null
    private var isSleeping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            startLiveMonitoring()
        }
        return START_STICKY
    }

    private fun startLiveMonitoring() {
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                val deviceId = prefs.getString("DEVICE_ID", null)
                if (deviceId != null) {
                    performLiveCheck(deviceId)
                }
                delay(10000)
            }
        }
    }

    private fun performLiveCheck(deviceId: String) {
        try {
            // 1. Update online and awake state
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isAwake = pm.isInteractive && fakeSleepView == null
            val updateUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
            val body = """{"is_online": true, "is_awake": $isAwake, "last_seen": "${nowUtcIso()}"}""".toRequestBody(JSON_TYPE)
            val updateReq = Request.Builder()
                .url(updateUrl).addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Prefer", "return=minimal")
                .patch(body).build()
            client.newCall(updateReq).execute()

            // 2. Poll for pending_command
            val fetchUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId&select=pending_command"
            val fetchReq = Request.Builder()
                .url(fetchUrl).addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey").build()
            
            val response = client.newCall(fetchReq).execute()
            val responseData = response.body?.string()

            if (response.isSuccessful && responseData != null) {
                val jsonArray = JSONArray(responseData)
                if (jsonArray.length() > 0) {
                    val setting = jsonArray.getJSONObject(0)
                    val cmd = setting.optString("pending_command", "")
                    
                    if (cmd.isNotEmpty() && cmd != "null") {
                        executeRemoteCommand(cmd)
                        clearRemoteCommand(deviceId)
                    }
                } else {
                    // Device deleted from web -> unpair locally
                    val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                    prefs.edit().clear().apply()
                    monitorJob?.cancel()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun executeRemoteCommand(cmd: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                when {
                    cmd == "SLEEP" -> activateFakeSleep()
                    cmd == "WAKE" -> deactivateFakeSleep()
                    cmd == "SYNC" -> {
                        val intent = Intent(this@MasjidService, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    cmd == "UP" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    cmd == "DOWN" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                    cmd == "LEFT" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    cmd == "RIGHT" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    cmd == "ENTER" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    cmd == "BACK" -> {
                        if (MasjidAccessibilityService.isServiceActive) {
                            val i = Intent(MasjidAccessibilityService.ACTION_REMOTE_COMMAND)
                            i.putExtra(MasjidAccessibilityService.EXTRA_COMMAND, "BACK")
                            sendBroadcast(i)
                        } else simulateKeyEvent(android.view.KeyEvent.KEYCODE_BACK)
                    }
                    cmd == "HOME" -> {
                        if (MasjidAccessibilityService.isServiceActive) {
                            val i = Intent(MasjidAccessibilityService.ACTION_REMOTE_COMMAND)
                            i.putExtra(MasjidAccessibilityService.EXTRA_COMMAND, "HOME")
                            sendBroadcast(i)
                        } else {
                            val startMain = Intent(Intent.ACTION_MAIN)
                            startMain.addCategory(Intent.CATEGORY_HOME)
                            startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(startMain)
                        }
                    }
                    cmd == "VOL_UP" -> changeVolume(1)
                    cmd == "VOL_DOWN" -> changeVolume(-1)
                    cmd == "MUTE" -> changeVolume(0, mute = true)
                    cmd == "UNMUTE" -> changeVolume(0, unmute = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun activateFakeSleep() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        
        // Mute Audio during Sleep
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {}

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Aggressive System Settings (Root) - Disable Stay Awake
                Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power stayawake false"))
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global stay_on_while_plugged_in 0"))
                
                // 2. HDMI-CEC Standby (Root)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 'standby 0' | cec-client -s -d 1"))
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd hdmi_control standby"))
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                if (powerManager.isInteractive) {
                    try {
                        // 3. Device Admin Lock (Highly effective)
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        val componentName = android.content.ComponentName(this@MasjidService, TvDeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(componentName)) {
                            dpm.lockNow()
                        }

                        // 4. Accessibility Service (System Lock)
                        if (MasjidAccessibilityService.isServiceActive) {
                            val intent = Intent(MasjidAccessibilityService.ACTION_REMOTE_COMMAND)
                            intent.putExtra(MasjidAccessibilityService.EXTRA_COMMAND, "SLEEP")
                            sendBroadcast(intent)
                        }

                        // 5. Root Keyevent Sleep (223 is strictly SLEEP, unlike 26 which is a POWER toggle)
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223"))
                    } catch (e: Exception) {}
                }

                // 6. Final Fallback: Fake Sleep Overlay (Black screen + Min Brightness)
                if (fakeSleepView == null) {
                    if (android.provider.Settings.canDrawOverlays(this@MasjidService)) {
                        val params = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv(), // DO NOT KEEP SCREEN ON
                            PixelFormat.TRANSLUCENT
                        )
                        params.screenBrightness = 0.001f 
                        
                        val layout = FrameLayout(this@MasjidService)
                        layout.setBackgroundColor(Color.BLACK) 

                        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        windowManager?.addView(layout, params)
                        fakeSleepView = layout
                    }
                }
                
                // 7. Start "Insane Mode" Enforcement Loop
                isSleeping = true
                startPowerEnforcement(powerManager)
            }
        }
    }

    private fun startPowerEnforcement(powerManager: android.os.PowerManager) {
        powerEnforcerJob?.cancel()
        powerEnforcerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isSleeping) {
                if (powerManager.isInteractive) {
                    try {
                        // Constant Re-locking and CEC Standby every 10 seconds if screen refuses to sleep
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223")) // KEYCODE_SLEEP
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd hdmi_control standby"))
                        
                        // Kill foreground tasks that might stay awake (like some players)
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "am kill-all"))
                        
                        // Force Screen Off via System Service
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "service call power 1 i32 0 i32 0"))
                    } catch (e: Exception) {}
                }
                delay(10000)
            }
        }
    }

    private fun deactivateFakeSleep() {
        isSleeping = false
        powerEnforcerJob?.cancel()
        
        // Remove Fake Sleep overlay
        if (fakeSleepView != null && windowManager != null) {
            try { windowManager?.removeView(fakeSleepView) } catch (e: Exception) {}
            fakeSleepView = null
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Aggressive Power Force (Root)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power stayawake true"))
                
                // 2. HDMI-CEC Wakeup (Root)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 'on 0' | cec-client -s -d 1"))
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd hdmi_control one_touch_play"))

                // 3. Wake Keyevents
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 224")) 
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 82")) // Unlock
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                // 4. WakeLock Acquire
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "MasjidTV:RemoteWake"
                )
                wakeLock.acquire(10000)

                // 5. Restore Audio
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.5).toInt(), 0)
                } catch (e: Exception) {}

                // Target app launching removed based on user request to stay on pairing/dashboard page.
            }
        }
    }

    private fun simulateKeyEvent(keyCode: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inst = android.app.Instrumentation()
                inst.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode"))
                    process.waitFor()
                } catch (e2: Exception) {
                    try { Runtime.getRuntime().exec("input keyevent $keyCode") } catch (e3: Exception) { }
                }
            }
        }
    }

    private fun changeVolume(direction: Int, mute: Boolean = false, unmute: Boolean = false) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (mute) {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            } else if (unmute) {
                val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.3).toInt(), 0)
            } else {
                if (direction > 0) {
                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                } else {
                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Exception) {}
    }

    private fun clearRemoteCommand(deviceId: String) {
        try {
            val url = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
            val body = """{"pending_command": null}""".toRequestBody(JSON_TYPE)
            val req = Request.Builder()
                .url(url).addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Prefer", "return=minimal")
                .patch(body).build()
            client.newCall(req).execute()
        } catch (e: Exception) {}
    }

    private fun nowUtcIso(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        screenshotJob?.cancel()
        powerEnforcerJob?.cancel()
        
        if (fakeSleepView != null) {
            windowManager?.removeView(fakeSleepView)
        }
    }
}
