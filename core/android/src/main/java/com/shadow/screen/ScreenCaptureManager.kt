package com.shadow.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

object ScreenCaptureManager {
    
    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshotViaA11y(service: AccessibilityService, callback: (String?) -> Unit) {
        Log.i("ShadowScreen", "Taking screenshot via AccessibilityService")
        
        service.takeScreenshot(
            AccessibilityService.DISPLAY_DEFAULT,
            object : Executor { override fun execute(command: Runnable) { command.run() } },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        
                        if (bitmap != null) {
                            val outputStream = ByteArrayOutputStream()
                            // Compress aggressively to save WebSocket bandwidth
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                            callback(base64)
                            bitmap.recycle()
                        } else {
                            callback(null)
                        }
                        hardwareBuffer.close()
                    } catch (e: Exception) {
                        Log.e("ShadowScreen", "Screenshot processing failed", e)
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e("ShadowScreen", "Screenshot failed with code $errorCode")
                    callback(null)
                }
            }
        )
    }
}