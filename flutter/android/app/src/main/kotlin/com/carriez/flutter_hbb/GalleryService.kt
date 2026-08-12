package com.carriez.flutter_hbb

import android.content.Context
import ffi.FFI
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Gallery support on the controlled side: scan media directories, generate
// thumbnails (image downscale / video frame), report results back through
// FFI.sendGalleryData.
object GalleryService {
    private val logTag = "GalleryService"

    private val dirs = listOf("DCIM", "Pictures", "Screenshots", "Download")

    private val imageExt = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")
    private val videoExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp")

    // cap concurrent thumbnail generation (bitmap memory)
    private val thumbSemaphore = java.util.concurrent.Semaphore(4)

    fun listMedia(context: Context, dirName: String) {
        thread {
            try {
                val result = JSONArray()
                val wanted = if (dirName.isBlank() || dirName == "All") dirs else listOf(dirName)
                // MediaStore is the standard way on Android 10+ (scoped
                // storage blocks direct File access to media subdirs).
                queryMediaStore(context, wanted, result)
                // sort by mtime desc
                val arr = ArrayList<JSONObject>()
                for (i in 0 until result.length()) arr.add(result.getJSONObject(i))
                arr.sortByDescending { it.optLong("mtime") }
                val json = JSONArray().apply { arr.forEach { put(it) } }.toString()
                FFI.sendGalleryData("media_list", dirName, json)
                FileLog.log(logTag, "listMedia: ${arr.size} files via MediaStore")
            } catch (e: Exception) {
                Log.e(logTag, "listMedia failed: $e")
                FFI.sendGalleryData("media_list", dirName, "[]")
            }
        }
    }

    // Query MediaStore for images and videos. On Android 10+ media under
    // subdirs like DCIM/Camera is only reachable through MediaStore.
    private fun queryMediaStore(context: Context, wanted: List<String>, result: JSONArray) {
        try {
            var totalRows = 0
            var nullData = 0
            var filtered = 0
            for (uri in arrayOf(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )) {
                val isVideo = uri == android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    android.provider.MediaStore.MediaColumns.DATA,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    val colData = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    val colName = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    val colSize = c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    val colMtime = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    totalRows += c.count
                    while (c.moveToNext()) {
                        val path = c.getString(colData)
                        if (path == null) {
                            nullData++
                            continue
                        }
                        val dirOk = wanted.any { path.startsWith("/storage/emulated/0/$it") }
                        if (!dirOk) {
                            filtered++
                            continue
                        }
                        result.put(JSONObject().apply {
                            put("path", path)
                            put("name", c.getString(colName) ?: "")
                            put("type", if (isVideo) "video" else "image")
                            put("size", c.getLong(colSize))
                            put("mtime", c.getLong(colMtime) * 1000L)
                        })
                    }
                }
            }
            FileLog.log(logTag, "queryMediaStore: totalRows=$totalRows, nullData=$nullData, filtered=$filtered, kept=${result.length()}")
        } catch (e: Exception) {
            Log.e(logTag, "queryMediaStore failed: $e")
            FileLog.log(logTag, "queryMediaStore failed: $e")
        }
    }

    // Recursive scan (media files usually live in subdirs like DCIM/Camera).
    private fun scanDir(dir: File, result: JSONArray, depth: Int) {
        if (depth > 3) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                val name = f.name.lowercase()
                val type = when {
                    imageExt.any { name.endsWith(it) } -> "image"
                    videoExt.any { name.endsWith(it) } -> "video"
                    else -> null
                }
                if (type != null) {
                    result.put(JSONObject().apply {
                        put("path", f.absolutePath)
                        put("name", f.name)
                        put("type", type)
                        put("size", f.length())
                        put("mtime", f.lastModified())
                    })
                }
            } else if (f.isDirectory) {
                scanDir(f, result, depth + 1)
            }
        }
    }

    fun thumbFor(context: Context, path: String) {
        thread {
            thumbSemaphore.acquire()
            try {
                val f = File(path)
                if (!f.isFile) {
                    FFI.sendGalleryData("thumb", path, "")
                    return@thread
                }
                val cache = File(context.filesDir, "thumbs")
                cache.mkdirs()
                val key = Integer.toHexString(path.hashCode()) + "_" + f.length() + "_" + f.lastModified()
                val cacheFile = File(cache, "$key.jpg")
                if (cacheFile.isFile) {
                    FFI.sendGalleryData("thumb", path, android.util.Base64.encodeToString(cacheFile.readBytes(), android.util.Base64.NO_WRAP))
                    return@thread
                }
                val bmp = if (isVideo(path)) videoFrame(f) else imageThumb(f)
                if (bmp == null) {
                    FFI.sendGalleryData("thumb", path, "")
                    return@thread
                }
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                bmp.recycle()
                cacheFile.writeBytes(bos.toByteArray())
                FFI.sendGalleryData("thumb", path, android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP))
            } catch (e: Exception) {
                Log.e(logTag, "thumbFor failed: $e")
                FFI.sendGalleryData("thumb", path, "")
            } finally {
                thumbSemaphore.release()
            }
        }
    }

    private fun isVideo(path: String): Boolean {
        val name = path.lowercase()
        return videoExt.any { name.endsWith(it) }
    }

    private fun imageThumb(f: File): android.graphics.Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
            val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o2)
        } catch (e: Exception) {
            null
        }
    }

    private fun videoFrame(f: File): android.graphics.Bitmap? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(f.absolutePath)
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
            } else {
                retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            retriever.release()
            frame
        } catch (e: Exception) {
            null
        }
    }
}
