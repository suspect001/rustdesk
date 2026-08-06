package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity

const val DEBUG_BOOT_COMPLETED = "com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED"

class BootReceiver : BroadcastReceiver() {
    private val logTag = "tagBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(logTag, "onReceive ${intent.action}")

        if (Intent.ACTION_BOOT_COMPLETED == intent.action || DEBUG_BOOT_COMPLETED == intent.action) {
            // check SharedPreferences config, default on
            val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_START_ON_BOOT_OPT, true)) {
                Log.d(logTag, "KEY_START_ON_BOOT_OPT is false")
                return
            }

            // Do not require pre-granted permissions here: start the service
            // anyway. Missing media projection / accessibility permissions
            // are handled with a guidance notification afterwards, so the
            // controlled service comes up right after reboot.
            val it = Intent(context, MainService::class.java).apply {
                action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                putExtra(EXT_INIT_FROM_BOOT, true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(it)
                } else {
                    context.startService(it)
                }
                Toast.makeText(context, "RustDesk is Open", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(logTag, "start service failed: $e")
            }

            // The accessibility service (remote input) cannot be enabled from
            // code; some OEM ROMs disable it after reboot. Guide the user.
            if (!InputService.isOpen) {
                sendGuideNotification(
                    context,
                    "请点击开启无障碍服务(远程输入需要)",
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            }
        }
    }
}
