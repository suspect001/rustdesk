package com.carriez.flutter_hbb

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            else -> finish()
        }
    }

    // Ask the system to dismiss the keyguard. Without a password the device
    // unlocks straight to the desktop; with a password the lockscreen (with
    // its password pad) is shown, which MediaProjection can then capture on
    // most devices.
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            finish()
                        }

                        override fun onDismissCancelled() {
                            finish()
                        }

                        override fun onDismissError() {
                            finish()
                        }
                    }
                )
                // The keypad is only visible after the lockscreen has been
                // shown; wait for it, then auto-type the configured pin.
                scheduleAutoUnlock(2000)
                return
            }
        }
        finish()
    }

    // Wait until the lockscreen keypad is on screen, then let the
    // accessibility service tap in the configured pin.
    private fun scheduleAutoUnlock(delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager.isKeyguardLocked) {
                    val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    val pin = prefs.getString(KEY_LOCKSCREEN_PIN, "") ?: ""
                    if (pin.isNotEmpty()) {
                        if (InputService.isOpen) {
                            Log.d(logTag, "auto unlock: typing pin, accessibility open=true")
                            InputService.ctx?.autoUnlockWithPin(pin)
                        } else {
                            Log.e(logTag, "auto unlock: accessibility service is NOT enabled")
                            sendGuideNotification(
                                applicationContext,
                                "无法自动输入锁屏密码:请先开启无障碍服务",
                                Settings.ACTION_ACCESSIBILITY_SETTINGS,
                                notifyId = 2034
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "scheduleAutoUnlock failed:$e")
            }
        }, delayMs)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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