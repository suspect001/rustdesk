package com.carriez.flutter_hbb

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Fallback when the KernelSU module is not installed: if the device is
// rooted, re-apply the permission settings (accessibility + media
// projection) from inside the app after a reboot.
object RootKeepalive {
    private val logTag = "RootKeepalive"

    fun tryApply() {
        thread {
            try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroy()
                    FileLog.log(logTag, "su timeout, not rooted or not granted")
                    return@thread
                }
                val out = p.inputStream.bufferedReader().readText()
                if (p.exitValue() != 0 || !out.contains("uid=0")) {
                    FileLog.log(logTag, "no root: ${out.trim()}")
                    return@thread
                }
                FileLog.log(logTag, "root detected, applying keepalive settings")
                val cmds = listOf(
                    "settings put secure enabled_accessibility_services com.carriez.flutter_hbb/com.carriez.flutter_hbb.InputService",
                    "settings put secure accessibility_enabled 1",
                    "appops set com.carriez.flutter_hbb PROJECT_MEDIA allow"
                )
                for (cmd in cmds) {
                    val cp = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    if (cp.waitFor(10, TimeUnit.SECONDS)) {
                        FileLog.log(logTag, "exec: $cmd -> exit ${cp.exitValue()}")
                    } else {
                        cp.destroy()
                        FileLog.log(logTag, "exec timeout: $cmd")
                    }
                }
            } catch (e: Exception) {
                FileLog.log(logTag, "tryApply failed: $e")
            }
        }
    }
}
