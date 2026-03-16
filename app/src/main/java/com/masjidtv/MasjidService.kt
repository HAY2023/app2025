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
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

class MasjidService : Service() {

    private val supabaseUrl = "https://dxljqnchxdyhxlppbeip.supabase.co"
    private val apiKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImR4bGpxbmNoeGR5aHhscHBiZWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMzOTIwNzQsImV4cCI6MjA4ODk2ODA3NH0.TmFNsRuWK08kbflxmxAGlbLSmr7bdXopct_ui_Lqku4"
    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private var monitorJob: Job? = null
    private var screenshotJob: Job? = null

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
                delay(5000) // Heartbeat every 5 seconds for better responsiveness
            }
        }
        
        // Start periodic screenshot capture (every 60 seconds)
        screenshotJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
                val deviceId = prefs.getString("DEVICE_ID", null)
                if (deviceId != null) {
                    captureAndUploadScreenshot(deviceId)
                }
                delay(60000) // 1 minute
            }
        }
    }

    private fun performLiveCheck(deviceId: String) {
        try {
            // 1. Update online and awake state
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isAwake = pm.isInteractive
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

    private fun captureAndUploadScreenshot(deviceId: String) {
        try {
            // Aggressive screenshot capture (Root or Shell)
            val screenshotPath = "${cacheDir}/sc.png"
            // Using shell command for silent capture
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p $screenshotPath"))
            process.waitFor()
            
            val file = File(screenshotPath)
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(screenshotPath)
                if (bitmap != null) {
                    // Resize to save bandwidth (TVs are usually 1080p or 4k)
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                    val baos = ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)
                    val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    
                    val updateUrl = "$supabaseUrl/rest/v1/tv_settings?id=eq.$deviceId"
                    val body = """{"last_screenshot": "data:image/jpeg;base64,$base64Image", "last_seen": "${nowUtcIso()}"}""".toRequestBody(JSON_TYPE)
                    val req = Request.Builder()
                        .url(updateUrl).addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Prefer", "return=minimal")
                        .patch(body).build()
                    client.newCall(req).execute()
                }
                file.delete()
            }
        } catch (e: Exception) {
            // Silently fail if no root or permission
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

            // Priority 2: Device Admin Lock
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(this, TvDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.lockNow()
                    return
                }
            } catch (e: Exception) {}

            // Priority 3: Force HDMI-CEC Power Off & System Sleep (Root required for best results)
            val commands = arrayOf(
                "su -c input keyevent 26", // Global Power button
                "su -c echo 'standby 0' | cec-client -s -d 1", // HDMI-CEC Standby
                "su -c svc power stayawake false", // Disable stay awake
                "su -c settings put global stay_on_while_plugged_in 0" // Device can sleep while charging
            )
            
            CoroutineScope(Dispatchers.IO).launch {
                for (cmd in commands) {
                    try { Runtime.getRuntime().exec(cmd).waitFor() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deactivateFakeSleep() {
        // Unmute Audio during Wake
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.3).toInt(), 0)
        } catch (e: Exception) {}

        // Aggressive Wake Commands
        val commands = arrayOf(
            "su -c input keyevent 224", // KEYCODE_WAKEUP
            "su -c input keyevent 82",  // KEYCODE_MENU (to unlock some screens)
            "su -c echo 'on 0' | cec-client -s -d 1", // HDMI-CEC On
            "su -c svc power stayawake true" // Prevent going back to sleep
        )

        CoroutineScope(Dispatchers.IO).launch {
            for (cmd in commands) {
                try { Runtime.getRuntime().exec(cmd).waitFor() } catch (e: Exception) {}
            }
        }

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MasjidTV:RemoteWake"
        )
        wakeLock.acquire(3000)

        // Launch target app
        val prefs = getSharedPreferences("MasjidTVPrefs", Context.MODE_PRIVATE)
        val packageToLaunch = prefs.getString("APP_PACKAGE", "com.google.android.youtube")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageToLaunch!!)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
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
    }
}
