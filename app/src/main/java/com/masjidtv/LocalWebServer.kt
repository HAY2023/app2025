package com.masjidtv

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.text.format.Formatter
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status

class LocalWebServer(
    private val port: Int, 
    private val context: Context, 
    private val prefs: SharedPreferences,
    private val onSettingsUpdated: () -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        if (Method.POST == method) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
                val params = session.parameters
                
                val newWake = params["wake_time"]?.get(0)
                val newSleep = params["sleep_time"]?.get(0)
                val newApp = params["app_package"]?.get(0)

                if (newWake != null) prefs.edit().putString("WAKE_TIME", newWake).apply()
                if (newSleep != null) prefs.edit().putString("SLEEP_TIME", newSleep).apply()
                if (newApp != null) prefs.edit().putString("APP_PACKAGE", newApp).apply()

                // Notify UI
                onSettingsUpdated()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Generate simple HTML UI for the Computer
        val currentWake = prefs.getString("WAKE_TIME", "00:00")
        val currentSleep = prefs.getString("SLEEP_TIME", "00:00")
        val currentApp = prefs.getString("APP_PACKAGE", "com.google.android.youtube")

        val html = """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <title>إعدادات تفاز المسجد</title>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f9; padding: 50px; text-align: center; }
                    .container { background-color: white; padding: 30px; border-radius: 10px; box-shadow: 0 4px 8px rgba(0,0,0,0.1); display: inline-block; }
                    input[type=time], input[type=text] { padding: 10px; margin: 10px 0; width: 100%; box-sizing: border-box; }
                    input[type=submit] { background-color: #4CAF50; color: white; padding: 15px 20px; border: none; border-radius: 5px; cursor: pointer; font-size: 16px; width: 100%;}
                    input[type=submit]:hover { background-color: #45a049; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2>لوحة تحكم شاشة المسجد</h2>
                    <p>يمكنك تعديل أوقات التشغيل والإيقاف من هنا</p>
                    <form method="POST">
                        <label>وقت التشغيل (إيقاظ):</label><br>
                        <input type="time" name="wake_time" value="$currentWake" required><br><br>
                        
                        <label>وقت السكون (إيقاف):</label><br>
                        <input type="time" name="sleep_time" value="$currentSleep" required><br><br>
                        
                        <label>حزمة التطبيق المُراد تشغيله:</label><br>
                        <input type="text" name="app_package" value="$currentApp" required><br><br>
                        
                        <input type="submit" value="حفظ الإعدادات">
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()

        return Response.newFixedLengthResponse(Status.OK, "text/html", html)
    }
}
