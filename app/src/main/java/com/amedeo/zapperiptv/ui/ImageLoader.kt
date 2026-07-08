package com.amedeo.zapperiptv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.amedeo.zapperiptv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object ImageLoader {
    private const val TAG = "ImageLoader"
    private const val TARGET_SIZE_PX = 150
    private const val DISK_CACHE_SIZE = 20 * 1024 * 1024L // 20 MB
    private const val MAX_DOWNLOADS = 4
    private const val TIMEOUT_MS = 5000

    private var diskCacheDir: File? = null
    private val downloadSemaphore = Semaphore(MAX_DOWNLOADS)
    private var isPaused = false
    private val pendingLoads = ConcurrentLinkedQueue<PendingLoad>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val memoryCache =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8) {
            override fun sizeOf(
                key: String,
                bitmap: Bitmap,
            ): Int = bitmap.byteCount / 1024
        }
    private val currentJobs = ConcurrentHashMap<ImageView, Job>()

    fun init(cacheDir: File) {
        diskCacheDir = File(cacheDir, "channel_logos").apply { if (!exists()) mkdirs() }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        val unique = LinkedHashMap<ImageView, PendingLoad>()
        while (true) {
            val pending = pendingLoads.poll() ?: break
            unique[pending.imageView] = pending
        }
        unique.values.forEach { performLoad(it.url, it.imageView, it.placeholderResId) }
    }

    fun load(
        url: String?,
        imageView: ImageView,
        placeholderResId: Int,
    ) {
        if (imageView.getTag(R.id.image_url_tag) == url) return
        imageView.setTag(R.id.image_url_tag, url)
        currentJobs[imageView]?.cancel()

        if (url.isNullOrEmpty()) {
            imageView.setImageResource(placeholderResId)
        } else {
            val cached = memoryCache.get(url)
            if (cached != null) {
                imageView.setImageBitmap(cached)
            } else {
                imageView.setImageResource(placeholderResId)
                if (isPaused) {
                    pendingLoads.removeAll { it.imageView == imageView }
                    pendingLoads.offer(PendingLoad(url, imageView, placeholderResId))
                } else {
                    performLoad(url, imageView, placeholderResId)
                }
            }
        }
    }

    private fun performLoad(
        url: String,
        imageView: ImageView,
        placeholderResId: Int,
    ) {
        currentJobs[imageView] =
            scope.launch {
                val bitmap = loadFromDiskOrDownload(url)
                if (imageView.getTag(R.id.image_url_tag) == url) {
                    if (bitmap != null) {
                        memoryCache.put(url, bitmap)
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(placeholderResId)
                    }
                }
            }
    }

    private suspend fun loadFromDiskOrDownload(url: String): Bitmap? {
        loadFromDiskCache(url)?.let { return it }
        return downloadSemaphore.withPermit { downloadAndCache(url) }
    }

    private suspend fun downloadAndCache(url: String): Bitmap? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection =
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                    }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bm = decodeSampledBitmap(bytes)
                    if (bm != null) saveToDiskCache(url, bytes)
                    return@withContext bm
                }
            } catch (e: IOException) {
                Log.e(TAG, "Download error: $url - ${e.message}")
            } finally {
                connection?.disconnect()
            }
            null
        }

    private fun loadFromDiskCache(url: String): Bitmap? {
        val file = getDiskCacheFile(url)
        if (file == null || !file.exists()) return null
        return try {
            decodeSampledBitmap(file.readBytes())
        } catch (e: IOException) {
            Log.w(TAG, "Disk read failed, deleting file", e)
            file.delete()
            null
        }
    }

    private fun saveToDiskCache(
        url: String,
        data: ByteArray,
    ) {
        val file = getDiskCacheFile(url) ?: return
        try {
            file.writeBytes(data)
            trimDiskCache()
        } catch (e: IOException) {
            Log.e(TAG, "Disk write failed", e)
        }
    }

    private fun getDiskCacheFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        return try {
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            File(dir, hash)
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "SHA-256 not found", e)
            null
        }
    }

    private fun trimDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.sortedBy { it.lastModified() }
        if (files == null || files.isEmpty()) return
        var total = files.sumOf { it.length() }
        if (total > DISK_CACHE_SIZE) {
            for (file in files) {
                total -= file.length()
                file.delete()
                if (total <= DISK_CACHE_SIZE) break
            }
        }
    }

    private fun decodeSampledBitmap(data: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        options.inSampleSize = calculateInSampleSize(options, TARGET_SIZE_PX, TARGET_SIZE_PX)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfH = options.outHeight / 2
            val halfW = options.outWidth / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private data class PendingLoad(
        val url: String,
        val imageView: ImageView,
        val placeholderResId: Int,
    )
}
