package com.carriez.flutter_hbb

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Fallback when the KernelSU module is not installed: if the device is
// rooted, re-apply the permission settings (accessibility + media
// projection) from inside the app after a reboot.
object RootKeepalive {
    private val logTag = "RootKeepalive"
    private val SERVICE = "com.carriez.flutter_hbb/com.carriez.flutter_hbb.InputService"
    private val PACKAGE = "com.carriez.flutter_hbb"

    // Probe known su locations in order (PATH resolution may fail depending
    // on the root implementation's su_compat behavior).
    private val SU_CANDIDATES = arrayOf(
        "su",
        "/data/adb/ksu/bin/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su"
    )

    fun tryApply() {
        thread {
            try {
                val su = findRootSu() ?: run {
                    FileLog.log(logTag, "no usable su found")
                    return@thread
                }
                FileLog.log(logTag, "root detected via $su, applying keepalive settings")

                // 1. Append RustDesk to the accessibility services list
                //    (do not clobber other accessibility services).
                val getP = ProcessBuilder(su, "-c", "settings get secure enabled_accessibility_services")
                    .redirectErrorStream(true).start()
                val current = if (getP.waitFor(5, TimeUnit.SECONDS)) {
                    getP.inputStream.bufferedReader().use { it.readText() }.trim()
                } else {
                    getP.destroy()
                    ""
                }
                val merged = when {
                    current.isBlank() || current == "null" -> SERVICE
                    current.contains(SERVICE) -> current
                    else -> "$current:$SERVICE"
                }
                exec(su, "settings put secure enabled_accessibility_services \"$merged\"")
                exec(su, "settings put secure accessibility_enabled 1")

                // 2. Allow media projection without confirmation (Android 10+).
                exec(su, "appops set $PACKAGE PROJECT_MEDIA allow")
            } catch (e: Exception) {
                FileLog.log(logTag, "tryApply failed: $e")
            }
        }
    }

    private fun findRootSu(): String? {
        for (candidate in SU_CANDIDATES) {
            try {
                val p = ProcessBuilder(candidate, "-c", "id").redirectErrorStream(true).start()
                if (p.waitFor(5, TimeUnit.SECONDS)) {
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    if (p.exitValue() == 0 && out.contains("uid=0")) {
                        return candidate
                    }
                } else {
                    p.destroy()
                }
            } catch (e: Exception) {
                // candidate not found, try next
            }
        }
        return null
    }

    private fun exec(su: String, cmd: String) {
        try {
            val p = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
                FileLog.log(logTag, "exec: $cmd -> exit ${p.exitValue()}${if (out.isNotEmpty()) ", out=$out" else ""}")
            } else {
                p.destroy()
                FileLog.log(logTag, "exec timeout: $cmd")
            }
        } catch (e: Exception) {
            FileLog.log(logTag, "exec failed: $cmd, $e")
        }
    }
}
