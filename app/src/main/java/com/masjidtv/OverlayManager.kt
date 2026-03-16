package com.masjidtv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*

class OverlayManager(private val context: Context) {
    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var messageView: View? = null

    fun showMessage(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // If there's an existing message, remove it first
                removeMessage()

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = 100 // Distance from top

                // Create a simple custom layout for the message
                val textView = TextView(context).apply {
                    this.text = "🕌 $text"
                    textSize = 28f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#CC000000")) // Semi-transparent black
                    setPadding(40, 20, 40, 20)
                    elevation = 10f
                    gravity = Gravity.CENTER
                }

                windowManager.addView(textView, params)
                messageView = textView

                // Auto-remove after 10 seconds
                delay(10000)
                removeMessage()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeMessage() {
        if (messageView != null) {
            try {
                windowManager.removeView(messageView)
                messageView = null
            } catch (e: Exception) {}
        }
    }
}
