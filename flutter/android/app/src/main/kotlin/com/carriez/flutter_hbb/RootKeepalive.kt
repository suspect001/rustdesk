package com.carriez.flutter_hbb

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Fallback when the KernelSU module is not installed: if the device is
// rooted, re-apply the permission settings (accessibility + media
// projection) from inside the app after a reboot.
object RootKeepalive {
    private val logTag = "RootKeepalive"
    private var appContext: android.content.Context? = null
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

    fun tryApply(context: android.content.Context) {
        appContext = context
        thread {
            try {
                val su = findRootSu() ?: run {
                    FileLog.log(logTag, "no usable su found")
                    // root manager (KernelSU/Magisk) may be denying the app;
                    // guide the user to whitelist it
                    sendGuideNotification(
                        appContext!!,
                        "检测到 root 但未获授权:请在 KernelSU/超级用户中允许 RustDesk 后重启 app",
                        null,
                        notifyId = 2037
                    )
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
                exec(su, "appops get $PACKAGE PROJECT_MEDIA")
            } catch (e: Exception) {
                FileLog.log(logTag, "tryApply failed: $e")
            }
        }
    }

    private fun findRootSu(): String? {
        // also try resolving `su` through the shell PATH (KernelSU/Magisk
        // compatible builds may place it in various locations)
        try {
            val which = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
            if (which.waitFor(5, TimeUnit.SECONDS)) {
                val path = which.inputStream.bufferedReader().use { it.readText() }.trim()
                if (path.isNotEmpty() && !SU_CANDIDATES.contains(path)) {
                    return testSu(path)
                }
            }
        } catch (e: Exception) {
        }
        for (candidate in SU_CANDIDATES) {
            try {
                val su = testSu(candidate)
                if (su != null) return su
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun testSu(candidate: String): String? {
        try {
            val p = ProcessBuilder(candidate, "-c", "id").redirectErrorStream(true).start()
            if (p.waitFor(5, TimeUnit.SECONDS)) {
                val out = p.inputStream.bufferedReader().use { it.readText() }
                if (p.exitValue() == 0 && out.contains("uid=0")) {
                    return candidate
                } else {
                    // su exists but was denied (e.g. not whitelisted in the
                    // root manager): log the reason for diagnostics
                    FileLog.log(logTag, "su '$candidate' rejected: exit=${p.exitValue()}, out=${out.trim()}")
                }
            } else {
                p.destroy()
            }
        } catch (e: Exception) {
            // candidate not found, try next
        }
        return null
    }

    // Periodic check: read system state via root, compare, restore if
    // missing, and log everything for diagnostics.
    fun checkAndRestore(context: android.content.Context) {
        appContext = context
        thread {
            try {
                val su = findRootSu() ?: run {
                    FileLog.log(logTag, "checkAndRestore: no su")
                    return@thread
                }
                // read current system state
                val services = suOutput(su, "settings get secure enabled_accessibility_services")
                val enabled = suOutput(su, "settings get secure accessibility_enabled")
                val mode = suOutput(su, "appops get $PACKAGE PROJECT_MEDIA")
                FileLog.log(logTag, "selfcheck sys: services=$services, enabled=$enabled, projectMedia=$mode")
                val expected = "com.carriez.flutter_hbb/com.carriez.flutter_hbb.InputService"
                if (!services.contains(expected)) {
                    FileLog.log(logTag, "selfcheck: accessibility missing in system list, restoring")
                    val merged = if (services.isBlank() || services == "null") expected
                        else "$services:$expected"
                    exec(su, "settings put secure enabled_accessibility_services \"$merged\"")
                    exec(su, "settings put secure accessibility_enabled 1")
                }
                if (!mode.contains("allow")) {
                    FileLog.log(logTag, "selfcheck: PROJECT_MEDIA not allowed, restoring")
                    exec(su, "appops set $PACKAGE PROJECT_MEDIA allow")
                }
                if (enabled.trim() != "1") {
                    FileLog.log(logTag, "selfcheck: accessibility_enabled=$enabled, restoring to 1")
                    exec(su, "settings put secure accessibility_enabled 1")
                }
            } catch (e: Exception) {
                FileLog.log(logTag, "checkAndRestore failed: $e")
            }
        }
    }

    private fun suOutput(su: String, cmd: String): String {
        return try {
            val p = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                p.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                p.destroy()
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // Synchronous, single-command path for the boot race: must be set
    // BEFORE MainService requests media projection, otherwise the system
    // dialog appears and requires a manual tap.
    fun setProjectMediaSync(): Boolean {
        try {
            val su = findRootSu() ?: return false
            val p = ProcessBuilder(su, "-c", "appops set $PACKAGE PROJECT_MEDIA allow")
                .redirectErrorStream(true).start()
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
                FileLog.log(logTag, "setProjectMediaSync: exit=${p.exitValue()}${
                    if (out.isNotEmpty()) ", out=$out" else ""
                }")
                return p.exitValue() == 0
            }
            p.destroy()
        } catch (e: Exception) {
            FileLog.log(logTag, "setProjectMediaSync failed: $e")
        }
        return false
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
