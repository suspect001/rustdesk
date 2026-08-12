package com.carriez.flutter_hbb

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        when (intent.action) {
            ACT_REQUEST_MEDIA_PROJECTION -> {
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQ_REQUEST_MEDIA_PROJECTION)
            }
            ACT_DISMISS_KEYGUARD -> {
                dismissKeyguard()
            }
            ACT_REQUEST_STORAGE -> {
                requestStorage()
            }
            else -> finish()
        }
    }

    // Request storage permission for the gallery (Android 10 needs the
    // runtime READ_EXTERNAL_STORAGE grant; Android 11+ opens All-files).
    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivityForResult(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(android.net.Uri.parse("package:$packageName")),
                    REQ_REQUEST_STORAGE
                )
            } catch (e: Exception) {
                startActivityForResult(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQ_REQUEST_STORAGE
                )
            }
        } else {
            @Suppress("DEPRECATION")
            com.hjq.permissions.XXPermissions.with(this)
                .permission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                .request { _, _ -> finish() }
        }
    }

    // Ask the system to dismiss the keyguard. Without a password the device
    // unlocks straight to the desktop; with a password the lockscreen (with
    // its password pad) is shown. The pin typing itself is scheduled by
    // MainService (not here), so it works even if background activity starts
    // are blocked.
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            FileLog.log("permissionRequest", "keyguard dismiss succeeded")
                            finish()
                        }

                        override fun onDismissCancelled() {
                            FileLog.log("permissionRequest", "keyguard dismiss cancelled")
                            finish()
                        }

                        override fun onDismissError() {
                            FileLog.log("permissionRequest", "keyguard dismiss error")
                            finish()
                        }
                    }
                )
                return
            }
        }
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_STORAGE) {
            finish()
            return
        }
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                launchService(data)
            } else {
                setResult(RES_FAILED)
            }
        }

        finish()
    }

    private fun launchService(mediaProjectionResultIntent: Intent) {
        Log.d(logTag, "Launch MainService")
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
        serviceIntent.putExtra(EXT_MEDIA_PROJECTION_RES_INTENT, mediaProjectionResultIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

}