package com.amedeo.zapperiptv.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

@androidx.media3.common.util.UnstableApi
object MediaSourceHelper {
    private const val TIMEOUT_MS = 20000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun createDataSourceFactory(): DataSource.Factory {
        val defaultRequestProperties =
            mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
            )

        return DefaultHttpDataSource
            .Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(defaultRequestProperties)
            .setConnectTimeoutMs(TIMEOUT_MS)
            .setReadTimeoutMs(TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
    }

    fun createExtractorsFactory(): DefaultExtractorsFactory =
        DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
            ).setAdtsExtractorFlags(
                androidx.media3.extractor.ts.AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING,
            ).setConstantBitrateSeekingEnabled(true)

    fun createMediaSource(
        url: String,
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: DefaultExtractorsFactory,
    ): MediaSource {
        val cleanUrl = url.substringBefore("|")
        val mediaItem = createMediaItem(cleanUrl)
        val mimeType = mediaItem.localConfiguration?.mimeType

        val factory =
            when (mimeType) {
                MimeTypes.APPLICATION_M3U8 ->
                    HlsMediaSource
                        .Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)

                MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
                MimeTypes.APPLICATION_RTSP -> RtspMediaSource.Factory()
                else -> ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            }
        return factory.createMediaSource(mediaItem)
    }

    private fun createMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val lowerUrl = url.lowercase()
        val mimeType =
            when {
                isHls(lowerUrl) -> MimeTypes.APPLICATION_M3U8
                isDash(lowerUrl) -> MimeTypes.APPLICATION_MPD
                isTs(lowerUrl) -> MimeTypes.VIDEO_MP2T
                lowerUrl.startsWith("rtsp://") -> MimeTypes.APPLICATION_RTSP
                else -> null
            }
        mimeType?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun isHls(url: String): Boolean =
        url.contains(".m3u8") ||
            url.contains("m3u8") ||
            url.contains("/hls/") ||
            url.contains("stvp-") ||
            url.contains("playlist") ||
            url.contains(".m3u")

    private fun isDash(url: String): Boolean = url.contains(".mpd") || url.contains("dash")

    private fun isTs(url: String): Boolean =
        url.contains(".ts") ||
            url.contains("format=ts") ||
            url.contains("output=ts") ||
            url.contains("mpegts") ||
            url.contains(":25461") ||
            url.contains("/live/")
}
