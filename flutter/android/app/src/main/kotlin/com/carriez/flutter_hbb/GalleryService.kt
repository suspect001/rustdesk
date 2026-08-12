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

    fun listMedia(context: Context, dirName: String) {
        thread {
            try {
                val root = android.os.Environment.getExternalStorageDirectory()
                FileLog.log(logTag, "listMedia: root=$root, root.exists=${root.exists()}")
                val result = JSONArray()
                val wanted = if (dirName.isBlank() || dirName == "All") dirs else listOf(dirName)
                for (d in wanted) {
                    val dir = File(root, d)
                    FileLog.log(logTag, "listMedia: scan $d")
                    if (dir.isDirectory) {
                        scanDir(dir, result, 0)
                    }
                }
                // sort by mtime desc
                val arr = ArrayList<JSONObject>()
                for (i in 0 until result.length()) arr.add(result.getJSONObject(i))
                arr.sortByDescending { it.optLong("mtime") }
                val json = JSONArray().apply { arr.forEach { put(it) } }.toString()
                FFI.sendGalleryData("media_list", dirName, json)
                FileLog.log(logTag, "listMedia: ${arr.size} files in $wanted")
            } catch (e: Exception) {
                Log.e(logTag, "listMedia failed: $e")
                FFI.sendGalleryData("media_list", dirName, "[]")
            }
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
            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            frame
        } catch (e: Exception) {
            null
        }
    }
}
