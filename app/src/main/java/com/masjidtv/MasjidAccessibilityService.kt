package com.masjidtv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class MasjidAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_REMOTE_COMMAND = "com.masjidtv.action.REMOTE_COMMAND"
        const val EXTRA_COMMAND = "command"
        var isServiceActive = false
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cmd = intent?.getStringExtra(EXTRA_COMMAND)
            if (cmd == "SLEEP") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            } else if (cmd == "HOME") {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else if (cmd == "BACK") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT
        this.serviceInfo = info
        isServiceActive = true
        registerReceiver(commandReceiver, IntentFilter(ACTION_REMOTE_COMMAND))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceActive = false
        unregisterReceiver(commandReceiver)
        return super.onUnbind(intent)
    }
}
