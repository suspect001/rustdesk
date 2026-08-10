package com.carriez.flutter_hbb

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLog {
    private val lock = Any()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val windowTs = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    private val fileTs = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    private var logsDir: File? = null
    private var currentFile: File? = null
    private var currentWindowStart = -1L

    // Resolve the directory that also holds the rust logs (flexi_logger):
    // on Android that is <external storage root>/RustDesk/Logs
    // (APP_HOME_DIR = ExternalPath.getExternalStorageDirectories()[0]).
    // Fall back to the app-private dir when external storage is unavailable.
    fun resolveLogDir(context: Context, appDir: String): File {
        try {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) {
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
                logsDir = resolveLogDir(context, appDir)
                currentFile = null
                currentWindowStart = -1L
                Log.d("FileLog", "init: $logsDir")
                cleanupOldFiles()
            } catch (e: Exception) {
                Log.e("FileLog", "init failed: $e")
            }
        }
    }

    // One log file per half-hour window: app_android_YYYYMMDD-HHmm.log
    // where HHmm is the window start. Uploading a time range is then exact
    // per file.
    private fun windowFileFor(now: Long): File {
        val dir = logsDir ?: return File(File("."), "app_android.log")
        val windowStart = (now / (30 * 60 * 1000L)) * (30 * 60 * 1000L)
        if (currentFile == null || windowStart != currentWindowStart) {
            currentWindowStart = windowStart
            currentFile = File(dir, "app_android_${fileTs.format(Date(windowStart))}.log")
        }
        return currentFile!!
    }

    fun log(tag: String, msg: String) {
        Log.d(tag, msg)
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                windowFileFor(now).appendText("${ts.format(Date(now))} [$tag] $msg\n")
            } catch (e: Exception) {
            }
        }
    }

    // Delete log files older than 7 days (both our app_android_*.log files).
    fun cleanupOldFiles() {
        try {
            val dir = logsDir ?: return
            val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
            dir.listFiles()?.filter {
                it.isFile && it.name.startsWith("app_android_") && it.lastModified() < cutoff
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e("FileLog", "cleanup failed: $e")
        }
    }
}
