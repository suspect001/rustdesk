package com.carriez.flutter_hbb

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLog {
    private var logFile: File? = null
    private val lock = Any()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Resolve the directory that also holds the rust logs (flexi_logger):
    // on Android that is <external storage root>/RustDesk/Logs
    // (APP_HOME_DIR = ExternalPath.getExternalStorageDirectories()[0]).
    // Fall back to the app-private dir when external storage is unavailable.
    fun resolveLogDir(context: Context, appDir: String): File {
        try {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.canWrite() || root != null && root.exists()) {
                val dir = File(root, "RustDesk/Logs")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                return dir
            }
        } catch (e: Exception) {
            Log.e("FileLog", "external storage unavailable: $e")
        }
        val dir = File(appDir, "RustDesk/Logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // @param appDir rust 配置目录(KEY_APP_DIR_CONFIG_PATH 的值),用作兜底
    fun init(context: Context, appDir: String) {
        synchronized(lock) {
            try {
                logFile = File(resolveLogDir(context, appDir), "app_android.log")
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
