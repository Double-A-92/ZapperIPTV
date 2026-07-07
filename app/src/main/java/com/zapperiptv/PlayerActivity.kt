package com.zapperiptv

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zapperiptv.databinding.ActivityPlayerBinding
import com.zapperiptv.model.PlaybackState
import com.zapperiptv.ui.ChannelListAdapter
import com.zapperiptv.ui.ImageLoader
import com.zapperiptv.ui.SettingsDialogFragment
import com.zapperiptv.viewmodel.MainViewModel
import kotlin.math.abs

@androidx.media3.common.util.UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null

    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var extractorsFactory: DefaultExtractorsFactory

    private val viewModel: MainViewModel by viewModels {
        (application as ZapperApp).mainViewModelFactory
    }

    private lateinit var channelListAdapter: ChannelListAdapter

    private lateinit var gestureDetector: GestureDetector

    companion object {
        private const val TAG = "PlayerActivity"
        private const val SWIPE_THRESHOLD_DP = 80
        private const val SWIPE_VELOCITY_THRESHOLD_DP = 120
        private const val PREFETCH_COUNT = 20
        private const val CACHE_SIZE = 20
        private const val TIMEOUT_MS = 20000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private data class FlingParams(
        val dx: Float,
        val dy: Float,
        val vx: Float,
        val vy: Float,
        val threshold: Float,
        val vThreshold: Float,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupGestureDetector()
        observeViewModel()
    }

    private fun setupGestureDetector() {
        val density = resources.displayMetrics.density
        val swipeThreshold = SWIPE_THRESHOLD_DP * density
        val swipeVelocityThreshold = SWIPE_VELOCITY_THRESHOLD_DP * density

        gestureDetector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (isMenuVisible()) return false
                        viewModel.toggleOverlay()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (!isMenuVisible()) showSettings()
                    }

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        vx: Float,
                        vy: Float,
                    ): Boolean {
                        if (e1 == null || isMenuVisible()) return false
                        val params =
                            FlingParams(
                                e2.x - e1.x,
                                e2.y - e1.y,
                                vx,
                                vy,
                                swipeThreshold,
                                swipeVelocityThreshold,
                            )
                        return handleFling(params)
                    }
                },
            )
    }

    private fun isMenuVisible(): Boolean =
        binding.channelListContainer.isVisible ||
            supportFragmentManager.findFragmentByTag("Settings") != null

    private fun handleFling(p: FlingParams): Boolean {
        val absDx = abs(p.dx)
        val absDy = abs(p.dy)
        return if (absDx > absDy) {
            handleHorizontalFling(p.dx, absDx, p.vx, p.threshold, p.vThreshold)
        } else {
            handleVerticalFling(p.dy, absDy, p.vy, p.threshold, p.vThreshold)
        }
    }

    private fun handleHorizontalFling(
        dx: Float,
        absDx: Float,
        vx: Float,
        threshold: Float,
        vThreshold: Float,
    ): Boolean {
        if (absDx < threshold || abs(vx) < vThreshold) return false
        var handled = true
        if (dx < 0) {
            viewModel.setChannelListVisible(true)
        } else {
            handled = binding.channelListContainer.isVisible
            if (handled) viewModel.setChannelListVisible(false)
        }
        return handled
    }

    private fun handleVerticalFling(
        dy: Float,
        absDy: Float,
        vy: Float,
        threshold: Float,
        vThreshold: Float,
    ): Boolean {
        if (absDy < threshold || abs(vy) < vThreshold) return false
        if (dy < 0) {
            viewModel.channelUp()
        } else {
            viewModel.channelDown()
        }
        return true
    }

    private fun setupRecyclerView() {
        channelListAdapter =
            ChannelListAdapter { position ->
                viewModel.selectChannel(position)
                viewModel.setChannelListVisible(false)
            }

        binding.channelRecyclerView.apply {
            layoutManager =
                LinearLayoutManager(this@PlayerActivity).apply {
                    initialPrefetchItemCount = PREFETCH_COUNT
                    isItemPrefetchEnabled = true
                }
            adapter = channelListAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(CACHE_SIZE)
            recycledViewPool.setMaxRecycledViews(0, CACHE_SIZE)
            itemAnimator = null
        }

        binding.channelRecyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        ImageLoader.resume()
                    } else {
                        ImageLoader.pause()
                    }
                }
            },
        )
    }

    private fun observeViewModel() {
        viewModel.channels.observe(this) { channels ->
            channelListAdapter.submitList(channels)
            updateWelcomeScreen(channels.isEmpty())
        }

        viewModel.currentChannel.observe(this) { channel ->
            if (channel != null) {
                playStream(channel.streamUrl)
                ImageLoader.load(channel.logoUrl, binding.overlayChannelLogo, R.drawable.ic_placeholder_logo)
                binding.overlayChannelName.text = channel.name
                binding.overlayChannelNumber.text = channel.displayNumber.toString()
            } else {
                stopStream()
            }
        }

        viewModel.playbackState.observe(this) { state ->
            binding.playerView.keepScreenOn = state is PlaybackState.Playing || state is PlaybackState.Loading
            binding.overlayLoading.visibility = if (state is PlaybackState.Loading) View.VISIBLE else View.GONE
        }

        viewModel.showOverlay.observe(this) { show -> binding.overlayContainer.isVisible = show }

        viewModel.showChannelList.observe(this) { show ->
            binding.channelListContainer.isVisible = show
            if (show) focusCurrentChannel()
        }

        viewModel.errorMessage.observe(this) { msg ->
            binding.errorPersistent.text = msg
            binding.errorPersistent.isVisible = msg != null
        }
    }

    private fun focusCurrentChannel() {
        val currentIdx = viewModel.currentIndex.value ?: 0
        binding.channelRecyclerView.scrollToPosition(currentIdx)
        binding.channelRecyclerView.post {
            val view = binding.channelRecyclerView.layoutManager?.findViewByPosition(currentIdx)
            view?.requestFocus() ?: binding.channelRecyclerView.requestFocus()
        }
    }

    private fun updateWelcomeScreen(isEmpty: Boolean) {
        binding.welcomeContainer.isVisible = isEmpty
        if (isEmpty) {
            val playlists = viewModel.playlists.value ?: emptyList()
            binding.welcomeInstructionText.text =
                if (playlists.isEmpty()) {
                    getString(R.string.welcome_instruction)
                } else {
                    getString(R.string.check_playlist_configuration)
                }
        }
    }

    private fun playStream(url: String) {
        initializePlayerIfNeeded()
        Log.d(TAG, "Playing stream: $url")
        viewModel.setPlaybackState(PlaybackState.Loading)

        val cleanUrl = url.substringBefore("|")
        val mediaItem = createMediaItem(cleanUrl)
        val mediaSource = createMediaSource(mediaItem)

        exoPlayer?.let { player ->
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }
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

    private fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val mType = mediaItem.localConfiguration?.mimeType
        val factory =
            when (mType) {
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

    private fun stopStream() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        viewModel.setPlaybackState(PlaybackState.Idle)
    }

    private fun initializePlayerIfNeeded() {
        if (exoPlayer != null) return
        val defaultRequestProperties =
            mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
            )

        dataSourceFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent(USER_AGENT)
                .setDefaultRequestProperties(defaultRequestProperties)
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)

        extractorsFactory =
            DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                        DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
                ).setAdtsExtractorFlags(
                    androidx.media3.extractor.ts.AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING,
                ).setConstantBitrateSeekingEnabled(true)

        exoPlayer =
            ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(pState: Int) {
                            val state =
                                when (pState) {
                                    Player.STATE_BUFFERING -> PlaybackState.Loading
                                    Player.STATE_READY -> PlaybackState.Playing
                                    Player.STATE_ENDED -> PlaybackState.Idle
                                    else -> null
                                }
                            state?.let { viewModel.setPlaybackState(it) }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            viewModel.setPlaybackState(PlaybackState.Error(error.message ?: "Unknown Error"))
                        }
                    },
                )
            }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        binding.playerView.player = null
    }

    override fun onStart() {
        super.onStart()
        initializePlayerIfNeeded()
        viewModel.currentChannel.value?.let { playStream(it.streamUrl) }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (isMenuVisible()) return handleMenuKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                viewModel.channelUp()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                viewModel.channelDown()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                viewModel.toggleOverlay()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                viewModel.setChannelListVisible(true)
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                showSettings()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleMenuKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (binding.channelListContainer.isVisible) {
                viewModel.setChannelListVisible(false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showSettings() {
        try {
            SettingsDialogFragment().show(supportFragmentManager, "Settings")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error showing settings", e)
        }
    }
}
