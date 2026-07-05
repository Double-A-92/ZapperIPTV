package com.zapperiptv

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.zapperiptv.databinding.ActivityPlayerBinding
import com.zapperiptv.model.PlaybackState
import com.zapperiptv.ui.ChannelListAdapter
import com.zapperiptv.ui.SettingsDialogFragment
import com.zapperiptv.viewmodel.MainViewModel
import androidx.recyclerview.widget.RecyclerView
import com.zapperiptv.ui.ImageLoader

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
        // Swipe distance / velocity thresholds. Tuned for TV-sized screens so
        // accidental small drags don't trigger channel changes.
        private const val SWIPE_THRESHOLD_DP = 80
        private const val SWIPE_VELOCITY_THRESHOLD_DP = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupGestureDetector()
        observeViewModel()

        binding.settingsButton.setOnClickListener {
            showSettings()
        }
    }

    private fun setupGestureDetector() {
        val density = resources.displayMetrics.density
        val swipeThreshold = SWIPE_THRESHOLD_DP * density
        val swipeVelocityThreshold = SWIPE_VELOCITY_THRESHOLD_DP * density

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Don't intercept taps on the channel list or settings dialog
                if (binding.channelListContainer.isVisible ||
                    supportFragmentManager.findFragmentByTag("Settings") != null) {
                    return false
                }

                // Show/hide playback overlay (channel name, number, status)
                viewModel.toggleOverlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press to open settings directly
                if (!binding.channelListContainer.isVisible &&
                    supportFragmentManager.findFragmentByTag("Settings") == null) {
                    showSettings()
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                // Don't intercept swipes when menus are open.
                if (binding.channelListContainer.isVisible ||
                    supportFragmentManager.findFragmentByTag("Settings") != null) {
                    return false
                }

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)

                // Determine if this is a horizontal or vertical fling.
                return if (absDx > absDy) {
                    if (absDx < swipeThreshold || kotlin.math.abs(velocityX) < swipeVelocityThreshold) {
                        false
                    } else if (dx < 0) {
                        // Swipe left → open channel list (matches DPAD_LEFT)
                        viewModel.setChannelListVisible(true)
                        true
                    } else {
                        // Swipe right → close channel list if open, else no-op
                        // (matches DPAD_RIGHT behavior)
                        if (binding.channelListContainer.isVisible) {
                            viewModel.setChannelListVisible(false)
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    if (absDy < swipeThreshold || kotlin.math.abs(velocityY) < swipeVelocityThreshold) {
                        false
                    } else if (dy < 0) {
                        // Swipe up → channel up (matches DPAD_UP)
                        viewModel.channelUp()
                        true
                    } else {
                        // Swipe down → channel down (matches DPAD_DOWN)
                        viewModel.channelDown()
                        true
                    }
                }
            }
        })
    }

    private fun setupRecyclerView() {
        channelListAdapter = ChannelListAdapter { position ->
            viewModel.selectChannel(position)
            viewModel.setChannelListVisible(false)
        }

        binding.channelRecyclerView.apply {
            // 1. Layout manager with aggressive pre-fetching
            layoutManager = LinearLayoutManager(this@PlayerActivity).apply {
                initialPrefetchItemCount = 20          // build 20 items ahead
                isItemPrefetchEnabled = true
            }

            // 2. Adapter
            adapter = channelListAdapter

            // 3. Fixed size – the RecyclerView’s height is fixed (not wrap_content)
            setHasFixedSize(true)

            // 4. View cache – keep a reasonable number of off-screen views
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(0, 20)   // limit recycled pool

            // 5. Disable default item animator to avoid fade-in overhead on initial load
            itemAnimator = null
        }

        // 6. Pause image loading while flinging, resume only when the list stops
        binding.channelRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING,
                    RecyclerView.SCROLL_STATE_SETTLING -> ImageLoader.pause()
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // Only resume if we were actually paused (idempotent call)
                        ImageLoader.resume()
                    }
                }
            }
        })
    }

    private fun observeViewModel() {
        viewModel.channels.observe(this) { channels ->
            channelListAdapter.submitList(channels)
            updateWelcomeScreen(channels.isEmpty())
        }

        viewModel.currentChannel.observe(this) { channel ->
            if (channel != null) {
                playStream(channel.streamUrl)
                binding.overlayChannelName.text = channel.name
                binding.overlayChannelNumber.text = channel.displayNumber.toString()

                val playlists = viewModel.playlists.value ?: emptyList()
                val sourcePlaylist = playlists.find { it.id == channel.sourceId }
                binding.overlayPlaylistName.text = sourcePlaylist?.name ?: ""
            } else {
                stopStream()
            }
        }

        viewModel.playbackState.observe(this) { state ->
            // Keep screen on during playback or loading
            binding.playerView.keepScreenOn = state is PlaybackState.Playing || state is PlaybackState.Loading

            when (state) {
                is PlaybackState.Loading -> binding.overlayStatus.text = "Loading..."
                is PlaybackState.Playing -> binding.overlayStatus.text = ""
                is PlaybackState.Error -> binding.overlayStatus.text = "Error: ${state.message}"
                is PlaybackState.Idle -> binding.overlayStatus.text = ""
            }
        }

        viewModel.showOverlay.observe(this) { show ->
            binding.overlayContainer.isVisible = show
        }

        viewModel.showChannelList.observe(this) { show ->
            binding.channelListContainer.isVisible = show
            if (show) {
                binding.channelRecyclerView.requestFocus()
                // Scroll to current channel
                val currentIdx = viewModel.currentIndex.value ?: -1
                if (currentIdx != -1) {
                    binding.channelRecyclerView.scrollToPosition(currentIdx)
                }
            }
        }

        viewModel.errorMessage.observe(this) { msg ->
            if (msg != null) {
                binding.errorPersistent.text = msg
                binding.errorPersistent.isVisible = true
            } else {
                binding.errorPersistent.isVisible = false
            }
        }
    }

    private fun updateWelcomeScreen(isEmpty: Boolean) {
        binding.welcomeContainer.isVisible = isEmpty
        if (isEmpty) {
            val playlists = viewModel.playlists.value ?: emptyList()
            if (playlists.isEmpty()) {
                binding.welcomeInstructionText.text = getString(R.string.welcome_instruction)
            } else {
                binding.welcomeInstructionText.text = getString(R.string.check_playlist_configuration)
            }
        }
    }

    private fun playStream(url: String) {
        initializePlayerIfNeeded()

        Log.d(TAG, "Playing stream: $url")
        viewModel.setPlaybackState(PlaybackState.Loading)

        // Handle common IPTV URL pipe suffix for headers (e.g., "|User-Agent=VLC")
        var cleanUrl = url
        val extraHeaders = mutableMapOf<String, String>()

        if (url.contains("|")) {
            val parts = url.split("|")
            cleanUrl = parts[0]
            for (i in 1 until parts.size) {
                val headerPart = parts[i]
                val kv = headerPart.split("=", limit = 2)
                if (kv.size == 2) {
                    extraHeaders[kv[0].trim()] = kv[1].trim()
                }
            }
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(cleanUrl)

        // Apply extra headers if present (Media3 1.2.0+)
        if (extraHeaders.isNotEmpty()) {
            // Some versions use setHttpRequestHeaders, others might need a custom data source.
            // For now, we at least clean the URL which is essential.
            Log.d(TAG, "Extra headers: $extraHeaders")
        }

        // IPTV streams are often HLS, DASH, or TS.
        // Hinting at the content type helps ExoPlayer select the right extractor/factory.
        val lowerUrl = cleanUrl.lowercase()
        when {
            lowerUrl.contains(".m3u8") || lowerUrl.contains("m3u8") ||
            lowerUrl.contains("/hls/") || lowerUrl.contains("stvp-") ||
            lowerUrl.contains("playlist") || lowerUrl.contains(".m3u") -> {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            lowerUrl.contains(".mpd") || lowerUrl.contains("dash") -> {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            lowerUrl.contains(".ts") || lowerUrl.contains("format=ts") ||
            lowerUrl.contains("output=ts") || lowerUrl.contains("mpegts") ||
            lowerUrl.contains(":25461") || lowerUrl.contains("/live/") -> {
                // Some providers use specific ports or keywords for TS streams
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            lowerUrl.startsWith("rtsp://") -> {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_RTSP)
            }
        }

        exoPlayer?.let { player ->
            val mediaItem = mediaItemBuilder.build()

            // Explicitly create the correct MediaSource based on MIME type.
            // This is more robust for IPTV than relying on DefaultMediaSourceFactory.
            val mediaSource = when (mediaItem.localConfiguration?.mimeType) {
                MimeTypes.APPLICATION_M3U8 -> {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem)
                }
                MimeTypes.APPLICATION_MPD -> {
                    DashMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                }
                MimeTypes.APPLICATION_RTSP -> {
                    RtspMediaSource.Factory()
                        .createMediaSource(mediaItem)
                }
                else -> {
                    ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItem)
                }
            }

            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }
    }

    private fun stopStream() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        viewModel.setPlaybackState(PlaybackState.Idle)
    }

    private fun initializePlayerIfNeeded() {
        if (exoPlayer == null) {
            // Use a common browser User-Agent as many IPTV providers block the default ExoPlayer one.
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            val defaultRequestProperties = mutableMapOf<String, String>()
            defaultRequestProperties["User-Agent"] = userAgent
            defaultRequestProperties["Accept"] = "*/*"
            defaultRequestProperties["Accept-Language"] = "en-US,en;q=0.9"
            defaultRequestProperties["Connection"] = "keep-alive"

            dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(defaultRequestProperties)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)

            // Explicitly configure extractors for better IPTV compatibility
            extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
                )
                .setAdtsExtractorFlags(androidx.media3.extractor.ts.AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
                .setConstantBitrateSeekingEnabled(true)

            exoPlayer = ExoPlayer.Builder(this)
                .build().apply {
                binding.playerView.player = this
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> viewModel.setPlaybackState(PlaybackState.Loading)
                            Player.STATE_READY -> viewModel.setPlaybackState(PlaybackState.Playing)
                            Player.STATE_ENDED -> viewModel.setPlaybackState(PlaybackState.Idle)
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        viewModel.setPlaybackState(PlaybackState.Error(error.message ?: "Unknown Error"))
                    }
                })
            }
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
        // Try to resume if we have a current channel
        viewModel.currentChannel.value?.let { channel ->
            playStream(channel.streamUrl)
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Delegate touch events to the gesture detector so swipes and taps
        // work on touch-enabled devices (tablets, phones, some TV remotes).
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If menus are open, let system handle navigation (focus)
        if (binding.channelListContainer.isVisible || supportFragmentManager.findFragmentByTag("Settings") != null) {

            // Allow BACK to close channel list
            if (keyCode == KeyEvent.KEYCODE_BACK && binding.channelListContainer.isVisible) {
                viewModel.setChannelListVisible(false)
                return true
            }
            // Allow RIGHT to close channel list
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && binding.channelListContainer.isVisible) {
                viewModel.setChannelListVisible(false)
                return true
            }

            return super.onKeyDown(keyCode, event)
        }

        // When menus are closed, handle playback controls
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
                if (!binding.channelListContainer.isVisible) {
                    viewModel.setChannelListVisible(true)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.channelListContainer.isVisible) {
                    viewModel.setChannelListVisible(false)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                showSettings()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showSettings() {
        Log.d(TAG, "showSettings() called")
        try {
            SettingsDialogFragment().show(supportFragmentManager, "Settings")
            Log.d(TAG, "SettingsDialogFragment shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing settings", e)
        }
    }
}
