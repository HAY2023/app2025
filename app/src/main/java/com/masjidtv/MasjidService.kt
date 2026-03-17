package com.masjidtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import java.util.concurrent.TimeUnit

class MasjidService : Service() {

    companion object {
        private const val TAG = "MasjidTV"
        private const val CHANNEL_ID = "masjid_tv_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val supabaseUrl = "https://dxljqnchxdyhxlppbeip.supabase.co"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA1Njc3ODc0N30.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private var monitorJob: Job? = null
    private var powerEnforcerJob: Job? = null
    private var windowManager: WindowManager? = null
    private var fakeSleepView: View? = null
    private var isSleeping = false
    private var emptyResponseCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "Service created with foreground notification")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            startLiveMonitoring()
        }
        
        // Handle commands from AlarmReceiver
        when (intent?.action) {
            "SLEEP" -> activateFakeSleep()
            "WAKE" -> deactivateFakeSleep()
        }
        
        return START_STICKY
    }

    // ==================== NOTIFICATION ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "نظام مساجد TV",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة التحكم عن بُعد"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("مساجد TV")
            .setContentText("الخدمة تعمل في الخلفية")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    // ==================== LIVE MONITORING ====================

    private fun startLiveMonitoring() {
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                    val deviceId = prefs.getString("DEVICE_ID", null)
                    if (deviceId != null) {
                        performLiveCheck(deviceId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                }
                delay(5000)
            }
        }
    }

    private fun performLiveCheck(deviceId: String) {
        try {
            // 1. Update online status
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isAwake = pm.isInteractive && fakeSleepView == null
            val updateUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
            val body = """{"is_online": true, "is_awake": $isAwake, "last_seen": "${nowUtcIso()}"}""".toRequestBody(JSON_TYPE)
            val updateReq = Request.Builder()
                .url(updateUrl).addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Prefer", "return=minimal")
                .patch(body).build()
            client.newCall(updateReq).execute().close()

            // 2. Poll for pending_command
            val fetchUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId&select=pending_command,wake_time,sleep_time,schedules_json"
            val fetchReq = Request.Builder()
                .url(fetchUrl).addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey").build()
            
            val response = client.newCall(fetchReq).execute()
            val responseData = response.body?.string()
            response.close()

            if (response.isSuccessful && responseData != null) {
                val jsonArray = JSONArray(responseData)
                if (jsonArray.length() > 0) {
                    emptyResponseCount = 0
                    val setting = jsonArray.getJSONObject(0)
                    
                    // Save wake/sleep times locally for AlarmScheduler
                    val wakeTime = setting.optString("wake_time", "")
                    val sleepTime = setting.optString("sleep_time", "")
                    val schedulesJson = setting.optString("schedules_json", "")
                    
                    if (wakeTime.isNotEmpty() && wakeTime != "null") {
                        val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                        val currentWake = prefs.getString("WAKE_TIME", "")
                        val currentSleep = prefs.getString("SLEEP_TIME", "")
                        val currentSchedules = prefs.getString("SCHEDULES_JSON", "")
                        
                        // Only reschedule if times actually changed
                        if (wakeTime != currentWake || sleepTime != currentSleep || schedulesJson != currentSchedules) {
                            prefs.edit()
                                .putString("WAKE_TIME", extractTime(wakeTime))
                                .putString("SLEEP_TIME", extractTime(sleepTime))
                                .putString("SCHEDULES_JSON", schedulesJson)
                                .apply()
                            AlarmScheduler.rescheduleAll(this, prefs)
                            Log.d(TAG, "Rescheduled alarms: wake=$wakeTime sleep=$sleepTime")
                        }
                    }
                    
                    // Execute pending command
                    val cmd = setting.optString("pending_command", "")
                    if (cmd.isNotEmpty() && cmd != "null") {
                        Log.d(TAG, "Executing command: $cmd")
                        executeRemoteCommand(cmd)
                        clearRemoteCommand(deviceId)
                    }
                } else {
                    emptyResponseCount++
                    if (emptyResponseCount >= 20) {
                        Log.d(TAG, "Device deleted from server, unpairing locally")
                        val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                        prefs.edit().clear().apply()
                        monitorJob?.cancel()
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiveCheck error", e)
        }
    }

    private fun extractTime(time: String): String {
        if (time.isEmpty() || time == "null") return "00:00"
        val parts = time.split(":")
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else time
    }

    // ==================== COMMAND EXECUTION ====================

    private fun executeRemoteCommand(cmd: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                when (cmd) {
                    "SLEEP" -> activateFakeSleep()
                    "WAKE" -> deactivateFakeSleep()
                    "SYNC" -> {
                        val intent = Intent(this@MasjidService, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    "UP" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    "DOWN" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                    "LEFT" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    "RIGHT" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    "ENTER" -> simulateKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    "BACK" -> {
                        if (MasjidAccessibilityService.isServiceActive) {
                            val i = Intent(MasjidAccessibilityService.ACTION_REMOTE_COMMAND)
                            i.putExtra(MasjidAccessibilityService.EXTRA_COMMAND, "BACK")
                            sendBroadcast(i)
                        } else simulateKeyEvent(android.view.KeyEvent.KEYCODE_BACK)
                    }
                    "HOME" -> {
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
                    "VOL_UP" -> changeVolume(1)
                    "VOL_DOWN" -> changeVolume(-1)
                    "MUTE" -> changeVolume(0, mute = true)
                    "UNMUTE" -> changeVolume(0, unmute = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error", e)
            }
        }
    }

    // ==================== SLEEP / WAKE ====================

    private fun activateFakeSleep() {
        if (isSleeping) return
        isSleeping = true
        Log.d(TAG, "SLEEP activated")

        // 1. Mute Audio
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {}

        // 2. Black Overlay (works 100% on ALL devices)
        CoroutineScope(Dispatchers.Main).launch {
            showBlackOverlay()
        }

        // 3. Hardware power off attempts (Root - optional, fails gracefully)
        CoroutineScope(Dispatchers.IO).launch {
            tryRootCommand("settings put global stay_on_while_plugged_in 0")
            tryRootCommand("input keyevent 26")
            tryRootCommand("input keyevent 223")
            tryRootCommand("cmd hdmi_control standby")
            
            // Device Admin lock
            withContext(Dispatchers.Main) {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val cn = android.content.ComponentName(this@MasjidService, TvDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(cn)) dpm.lockNow()
                } catch (e: Exception) {}
            }

            // Enforcement loop
            powerEnforcerJob?.cancel()
            powerEnforcerJob = launch {
                while (isActive && isSleeping) {
                    delay(30000)
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (pm.isInteractive) {
                        tryRootCommand("input keyevent 26")
                    }
                    // Re-show overlay if somehow removed
                    withContext(Dispatchers.Main) {
                        if (isSleeping && fakeSleepView == null) {
                            showBlackOverlay()
                        }
                    }
                }
            }
        }
    }

    private fun deactivateFakeSleep() {
        if (!isSleeping) return
        isSleeping = false
        powerEnforcerJob?.cancel()
        Log.d(TAG, "WAKE activated")

        // 1. Remove Overlay
        CoroutineScope(Dispatchers.Main).launch {
            removeBlackOverlay()
        }

        // 2. Hardware wake (Root - optional)
        CoroutineScope(Dispatchers.IO).launch {
            tryRootCommand("input keyevent 224")
            tryRootCommand("input keyevent 82")
            tryRootCommand("cmd hdmi_control one_touch_play")
        }

        // 3. Restore Audio
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.5).toInt(), 0)
        } catch (e: Exception) {}
    }

    // ==================== OVERLAY MANAGEMENT ====================

    private fun showBlackOverlay() {
        if (fakeSleepView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission!")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        params.screenBrightness = 0.0f

        val layout = FrameLayout(this)
        layout.setBackgroundColor(Color.BLACK)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(layout, params)
        fakeSleepView = layout
        Log.d(TAG, "Black overlay shown")
    }

    private fun removeBlackOverlay() {
        if (fakeSleepView != null && windowManager != null) {
            try {
                windowManager?.removeView(fakeSleepView)
            } catch (e: Exception) {}
            fakeSleepView = null
            Log.d(TAG, "Black overlay removed")
        }
    }

    // ==================== UTILITIES ====================

    private fun tryRootCommand(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (e: Exception) {
            // Root not available - that's OK, overlay still works
        }
    }

    private fun simulateKeyEvent(keyCode: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inst = android.app.Instrumentation()
                inst.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode")).waitFor()
                } catch (e2: Exception) {
                    try { Runtime.getRuntime().exec("input keyevent $keyCode") } catch (e3: Exception) {}
                }
            }
        }
    }

    private fun changeVolume(direction: Int, mute: Boolean = false, unmute: Boolean = false) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            when {
                mute -> audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                unmute -> {
                    val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.3).toInt(), 0)
                }
                direction > 0 -> audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                else -> audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
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
            client.newCall(req).execute().close()
        } catch (e: Exception) {}
    }

    private fun nowUtcIso(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    // ==================== LIFECYCLE ====================

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        powerEnforcerJob?.cancel()
        removeBlackOverlay()
        Log.d(TAG, "Service destroyed")

        // Restart service automatically if paired
        val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
        if (prefs.getString("DEVICE_ID", null) != null) {
            val restartIntent = Intent(this, MasjidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When user swipes away the app, restart service
        val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
        if (prefs.getString("DEVICE_ID", null) != null) {
            val restartIntent = Intent(this, MasjidService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }
}
