package com.carriez.flutter_hbb

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLog {
    private var logFile: File? = null
    private val lock = Any()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // @param appDir rust 配置目录(KEY_APP_DIR_CONFIG_PATH 的值)
    fun init(appDir: String) {
        synchronized(lock) {
            try {
                val dir = File(appDir, "RustDesk/Logs")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                logFile = File(dir, "app_android.log")
                Log.d("FileLog", "init: $logFile")
            } catch (e: Exception) {
                Log.e("FileLog", "init failed: $e")
            }
        }
    }

    fun log(tag: String, msg: String) {
        Log.d(tag, msg)
        synchronized(lock) {
            try {
                logFile?.appendText("${ts.format(Date())} [$tag] $msg\n")
            } catch (e: Exception) {
            }
        }
    }
}
