package com.zapperiptv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.zapperiptv.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object ImageLoader {
    private const val TAG = "ImageLoader"
    private const val TARGET_SIZE_PX = 150
    private const val DISK_CACHE_SIZE = 20 * 1024 * 1024L // 20 MB
    private const val MAX_DOWNLOADS = 4

    // ---- State ----
    private var diskCacheDir: File? = null
    private val downloadSemaphore = Semaphore(MAX_DOWNLOADS)
    private var isPaused = false
    private val pendingLoads = ConcurrentLinkedQueue<PendingLoad>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- Memory cache ----
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memoryCache = object : LruCache<String, Bitmap>(maxMemory / 8) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    // Track running jobs per ImageView to cancel on rebind
    private val currentJobs = ConcurrentHashMap<ImageView, Job>()

    /** Call once in your Application.onCreate() with context.cacheDir */
    fun init(cacheDir: File) {
        diskCacheDir = File(cacheDir, "channel_logos").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /** Pause network loading while flinging – requests are queued. */
    fun pause() {
        isPaused = true
    }

    /** Resume and process all queued requests. */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        processPending()
    }

    // ----------------------------------------------------------------
    // Public load method – same signature you already use
    // ----------------------------------------------------------------
    fun load(url: String?, imageView: ImageView, placeholderResId: Int) {
        // Skip if already loading this same URL
        if (imageView.getTag(R.id.image_url_tag) == url) return
        imageView.setTag(R.id.image_url_tag, url)

        // Cancel any previous job for this view
        currentJobs[imageView]?.cancel()

        if (url.isNullOrEmpty()) {
            imageView.setImageResource(placeholderResId)
            return
        }

        // Memory cache hit – instant display
        val cached = memoryCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Show placeholder immediately
        imageView.setImageResource(placeholderResId)

        // If paused, queue the request; otherwise start loading right away
        if (isPaused) {
            // Remove any older pending load for the same ImageView to avoid duplicates
            pendingLoads.removeAll { it.imageView == imageView }
            pendingLoads.offer(PendingLoad(url, imageView, placeholderResId))
        } else {
            performLoad(url, imageView, placeholderResId)
        }
    }

    // ----------------------------------------------------------------
    // Internal load execution
    // ----------------------------------------------------------------
    private fun performLoad(url: String, imageView: ImageView, placeholderResId: Int) {
        val job = scope.launch {
            val bitmap = loadFromDiskOrDownload(url)
            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                if (imageView.getTag(R.id.image_url_tag) == url) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
        currentJobs[imageView] = job
    }

    private fun processPending() {
        // Drain queue, but only keep the latest request per ImageView
        val unique = LinkedHashMap<ImageView, PendingLoad>()
        while (true) {
            val pending = pendingLoads.poll() ?: break
            unique[pending.imageView] = pending
        }
        // Now start loads for the remaining unique entries
        unique.values.forEach { (url, imageView, placeholderResId) ->
            performLoad(url, imageView, placeholderResId)
        }
    }

    // ----------------------------------------------------------------
    // Disk cache + network download
    // ----------------------------------------------------------------
    private suspend fun loadFromDiskOrDownload(url: String): Bitmap? {
        // 1. Try disk cache
        val diskBitmap = loadFromDiskCache(url)
        if (diskBitmap != null) return diskBitmap

        // 2. Download with concurrency limit
        return downloadSemaphore.withPermit {
            downloadAndCache(url)
        }
    }

    private suspend fun downloadAndCache(url: String): Bitmap? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val urlObj = URL(url)
                connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = decodeSampledBitmap(bytes, TARGET_SIZE_PX, TARGET_SIZE_PX)
                    if (bitmap != null) {
                        saveToDiskCache(url, bytes)
                    }
                    return@withContext bitmap
                } else {
                    Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: $url - ${e.message}")
            } finally {
                connection?.disconnect()
            }
            null
        }

    // ---------- Disk cache helpers ----------
    private fun loadFromDiskCache(url: String): Bitmap? {
        val file = getDiskCacheFile(url) ?: return null
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            decodeSampledBitmap(bytes, TARGET_SIZE_PX, TARGET_SIZE_PX)
        } catch (e: Exception) {
            file.delete() // corrupted
            null
        }
    }

    private fun saveToDiskCache(url: String, data: ByteArray) {
        val file = getDiskCacheFile(url) ?: return
        try {
            file.writeBytes(data)
            trimDiskCache()
        } catch (e: Exception) {
            Log.e(TAG, "Disk write failed", e)
        }
    }

    private fun getDiskCacheFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .fold("") { s, b -> s + "%02x".format(b) }
        return File(dir, hash)
    }

    private fun trimDiskCache() {
        val dir = diskCacheDir ?: return
        var total = dir.listFiles()?.sumOf { it.length() } ?: return
        if (total > DISK_CACHE_SIZE) {
            dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
                if (total <= DISK_CACHE_SIZE) return
                total -= file.length()
                file.delete()
            }
        }
    }

    // ---------- Bitmap decoding ----------
    private fun decodeSampledBitmap(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565   // save 50% memory
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        val h = options.outHeight
        val w = options.outWidth
        if (h > reqHeight || w > reqWidth) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ---------- Data class ----------
    private data class PendingLoad(
        val url: String,
        val imageView: ImageView,
        val placeholderResId: Int
    )
}