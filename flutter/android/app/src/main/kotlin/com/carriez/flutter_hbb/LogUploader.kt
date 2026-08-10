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
    private val HALF_HOUR_MS = 30 * 60 * 1000L

    // Upload log files from the given time range to the diagnostic server.
    // @param units time range in half-hour units (1 = 30 minutes, 2 = 1 hour, ...)
    // Returns a human-readable result via callback (exactly once), or null
    // on failure.
    fun uploadRange(context: Context, appDir: String, units: Int, callback: (String?) -> Unit) {
        thread {
            var result: String? = null
            var zipFile: File? = null
            var conn: HttpURLConnection? = null
            try {
                val logsDir = FileLog.resolveLogDir(context, appDir)
                if (!logsDir.isDirectory) {
                    result = "日志目录不存在:$logsDir"
                    FileLog.log(logTag, "uploadRange: no logs dir")
                } else {
                    val now = System.currentTimeMillis()
                    val from = now - units * HALF_HOUR_MS
                    // Include the current (still-being-written) window even
                    // when its window start predates the range: its mtime is
                    // recent.
                    val files = logsDir.listFiles()
                        ?.filter { it.isFile && it.lastModified() >= from - HALF_HOUR_MS }
                        ?.sortedBy { it.name }
                    if (files.isNullOrEmpty()) {
                        result = "该时间段内没有日志文件"
                        FileLog.log(logTag, "uploadRange: no files in range")
                    } else {
                        val device = Settings.Secure.getString(
                            context.contentResolver, Settings.Secure.ANDROID_ID
                        ) ?: "unknown-device"
                        zipFile = File(context.cacheDir, "logs_${System.currentTimeMillis()}.zip")
                        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                            for (f in files) {
                                zos.putNextEntry(ZipEntry(f.name))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        conn = URL("$UPLOAD_URL?device=$device").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/octet-stream")
                        conn.connectTimeout = 15_000
                        conn.readTimeout = 30_000
                        zipFile.inputStream().use { input ->
                            conn.outputStream.use { out -> input.copyTo(out) }
                        }
                        val code = conn.responseCode
                        if (code == 200) {
                            result = "上传成功:${files.size} 个日志文件"
                        } else {
                            result = "上传失败:HTTP $code"
                        }
                        FileLog.log(logTag, "uploadRange result: $code, files=${files.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "upload failed: $e")
                FileLog.log(logTag, "uploadRange failed: $e")
                result = "上传失败:${e.message}"
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {
                }
                zipFile?.delete()
            }
            callback(result)
        }
    }
}
