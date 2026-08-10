package com.carriez.flutter_hbb

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object LogUploader {
    private const val UPLOAD_URL = "http://67.216.217.100:9090/upload"
    private val logTag = "LogUploader"

    // Upload log files from the given time range (hours, in half-hour
    // granularity) to the diagnostic server. Returns a human-readable
    // result for the settings UI, or null on failure.
    fun uploadRange(context: Context, appDir: String, hours: Int, callback: (String?) -> Unit) {
        thread {
            var result: String? = null
            try {
                val logsDir = FileLog.resolveLogDir(context, appDir)
                if (!logsDir.isDirectory) {
                    result = "日志目录不存在:$logsDir"
                    FileLog.log(logTag, "uploadRange: no logs dir")
                    callback(result)
                    return@thread
                }
                val now = System.currentTimeMillis()
                val windowMs = 30 * 60 * 1000L
                val from = now - hours * 3600_000L
                // include a bit of slack so the current (still-being-written)
                // window counts when it overlaps the requested range
                val files = logsDir.listFiles()
                    ?.filter { it.isFile && it.lastModified() >= from - windowMs }
                    ?.sortedBy { it.name }
                if (files.isNullOrEmpty()) {
                    result = "该时间段内没有日志文件"
                    FileLog.log(logTag, "uploadRange: no files in range")
                    callback(result)
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
                conn.disconnect()
                zipFile.delete()
                if (code == 200) {
                    result = "上传成功:${files.size} 个日志文件"
                } else {
                    result = "上传失败:HTTP $code"
                }
                FileLog.log(logTag, "uploadRange result: $code, files=${files.size}")
            } catch (e: Exception) {
                Log.e(logTag, "upload failed: $e")
                FileLog.log(logTag, "uploadRange failed: $e")
                result = "上传失败:${e.message}"
            }
            callback(result)
        }
    }
}
