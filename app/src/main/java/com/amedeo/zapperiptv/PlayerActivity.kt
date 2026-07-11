package com.amedeo.zapperiptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amedeo.zapperiptv.databinding.ActivityPlayerBinding
import com.amedeo.zapperiptv.model.CurrentProgrammeState
import com.amedeo.zapperiptv.model.PlaybackState
import com.amedeo.zapperiptv.player.MediaSourceHelper
import com.amedeo.zapperiptv.ui.ChannelListAdapter
import com.amedeo.zapperiptv.ui.ImageLoader
import com.amedeo.zapperiptv.ui.SettingsDialogFragment
import com.amedeo.zapperiptv.ui.gesture.SwipeGestureHandler
import com.amedeo.zapperiptv.util.TvLauncherHelper
import com.amedeo.zapperiptv.viewmodel.MainViewModel

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null

    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var extractorsFactory: DefaultExtractorsFactory

    private val viewModel: MainViewModel by viewModels {
        (application as ZapperApp).mainViewModelFactory
    }

    private lateinit var channelListAdapter: ChannelListAdapter
    private lateinit var swipeGestureHandler: SwipeGestureHandler
    private var lastBackPressTime: Long = 0

    private val watchNextHandler = Handler(Looper.getMainLooper())
    private var watchNextRunnable: Runnable? = null

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PREFETCH_COUNT = 20
        private const val CACHE_SIZE = 20
        private const val WATCH_NEXT_DELAY_MS = 15 * 60 * 1000L // 15 minutes requirement

        private const val BACK_PRESS_EXIT_DELAY = 2000L
        private const val ANIM_ALPHA_IDLE = 0.9f
        private const val ANIM_ALPHA_ACTIVE = 1.0f
        private const val ANIM_SCALE_IDLE = 1.0f
        private const val ANIM_SCALE_PRESSED = 0.8f
        private const val ANIM_DURATION_PRESS = 1200L
        private const val ANIM_DURATION_RELEASE = 600L
        private const val ANIM_START_DELAY = 400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupGestureHandler()
        observeViewModel()
        handleIntent(intent)

        // Ensure the Preview Channel exists so the app appears in launcher settings
        TvLauncherHelper.ensureDefaultChannelExists(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null) {
            if (data.scheme == "zapperiptv" && data.host == "play") {
                data.getQueryParameter("url")?.let { url ->
                    viewModel.setPendingChannelUrl(url)
                }
            } else {
                viewModel.setPendingChannelUrl(data.toString())
            }
        }
    }

    private fun setupGestureHandler() {
        val listener =
            object : SwipeGestureHandler.SwipeListener {
                override fun onSwipeUp() {
                    if (!isMenuVisible()) viewModel.channelUp()
                }

                override fun onSwipeDown() {
                    if (!isMenuVisible()) viewModel.channelDown()
                }

                override fun onSwipeLeft() {
                    if (!isMenuVisible() && hasChannels()) viewModel.setChannelListVisible(true)
                }

                override fun onSwipeRight() {
                    if (binding.channelListContainer.isVisible) {
                        viewModel.setChannelListVisible(false)
                    }
                }

                override fun onSingleTap() {
                    if (!isMenuVisible() && hasChannels()) {
                        viewModel.setChannelListVisible(true)
                    }
                }

                override fun onLongPress() {
                    if (!isMenuVisible()) showSettings()
                }
            }
        swipeGestureHandler = SwipeGestureHandler(this, listener)
    }

    private fun hasChannels(): Boolean = viewModel.channels.value?.isNotEmpty() == true

    private fun isMenuVisible(): Boolean =
        binding.channelListContainer.isVisible ||
            supportFragmentManager.findFragmentByTag("Settings") != null

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

            addOnScrollListener(
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
    }

    private fun observeViewModel() {
        viewModel.channels.observe(this) { channels ->
            channelListAdapter.submitList(channels)
            updateWelcomeScreen(channels.isEmpty())
        }

        viewModel.currentChannel.observe(this) { channel ->
            if (channel != null) {
                playStream(channel.streamUrl)
                ImageLoader.load(
                    channel.logoUrl,
                    binding.overlayChannelLogo,
                    R.drawable.ic_placeholder_logo,
                )
                binding.overlayChannelName.text = channel.name
                binding.overlayChannelNumber.text = channel.displayNumber.toString()
            } else {
                stopStream()
            }
        }

        viewModel.playbackState.observe(this) { state ->
            binding.playerView.keepScreenOn = state is PlaybackState.Playing || state is PlaybackState.Loading
            binding.overlayLoading.visibility =
                if (state is PlaybackState.Loading) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        viewModel.showOverlay.observe(this) { show ->
            binding.overlayContainer.isVisible = show
        }

        viewModel.showChannelList.observe(this) { show ->
            binding.channelListContainer.isVisible = show
            if (show) focusCurrentChannel()
        }

        viewModel.errorMessage.observe(this) { msgId ->
            if (msgId != null) {
                binding.errorPersistent.setText(msgId)
                binding.errorPersistent.isVisible = true
            } else {
                binding.errorPersistent.isVisible = false
            }
        }

        viewModel.currentProgrammeState.observe(this) { state ->
            updateOverlayProgramme(state)
        }
    }

    private fun focusCurrentChannel() {
        val currentIdx = viewModel.currentIndex.value ?: 0
        val targetPos = channelListAdapter.getStartOffset(currentIdx)
        binding.channelRecyclerView.scrollToPosition(targetPos)
        binding.channelRecyclerView.post {
            val view = binding.channelRecyclerView.layoutManager?.findViewByPosition(targetPos)
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
            startWelcomeAnimation()
        } else {
            stopWelcomeAnimation()
        }
    }

    private fun updateOverlayProgramme(state: CurrentProgrammeState) {
        val programme = state.programme
        if (programme != null) {
            binding.overlayProgrammeTitle.text = programme.title
            binding.overlayProgrammeTitle.isVisible = true
            val now = System.currentTimeMillis()
            val duration = (programme.endMillis - programme.startMillis).coerceAtLeast(1L)
            val progress =
                ((now - programme.startMillis).toFloat() / duration * 1000)
                    .toInt()
                    .coerceIn(0, 1000)
            binding.overlayTimeBar.progress = progress
            binding.overlayTimeBar.isVisible = true
        } else {
            binding.overlayProgrammeTitle.isVisible = false
            binding.overlayTimeBar.isVisible = false
        }
    }

    private fun playStream(url: String) {
        initializePlayerIfNeeded()
        Log.d(TAG, "Playing stream: $url")
        viewModel.setPlaybackState(PlaybackState.Loading)

        val mediaSource =
            MediaSourceHelper.createMediaSource(
                url,
                dataSourceFactory,
                extractorsFactory,
            )

        exoPlayer?.let { player ->
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }

        // Update Google TV Watch Next with delay
        scheduleWatchNextUpdate()
    }

    private fun scheduleWatchNextUpdate() {
        watchNextRunnable?.let { watchNextHandler.removeCallbacks(it) }
        val channel = viewModel.currentChannel.value ?: return

        watchNextRunnable =
            Runnable {
                TvLauncherHelper.updateWatchNext(this, channel)
            }.also {
                watchNextHandler.postDelayed(it, WATCH_NEXT_DELAY_MS)
            }
    }

    private fun stopStream() {
        watchNextRunnable?.let { watchNextHandler.removeCallbacks(it) }
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        viewModel.setPlaybackState(PlaybackState.Idle)
    }

    private fun initializePlayerIfNeeded() {
        if (exoPlayer != null) return

        dataSourceFactory = MediaSourceHelper.createDataSourceFactory()
        extractorsFactory = MediaSourceHelper.createExtractorsFactory()

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
                            viewModel.setPlaybackState(
                                PlaybackState.Error(
                                    error.message ?: getString(R.string.error_unknown),
                                ),
                            )
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
        swipeGestureHandler.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (isKeyTracked(keyCode)) {
            event?.startTracking()
        }

        return when {
            binding.channelListContainer.isVisible -> handleChannelListKeyDown(keyCode, event)
            supportFragmentManager.findFragmentByTag("Settings") != null -> super.onKeyDown(keyCode, event)
            else -> handlePlaybackKeyDown(keyCode, event)
        }
    }

    private fun isKeyTracked(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER

    private fun handleChannelListKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                viewModel.setChannelListVisible(false)
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }

    private fun handlePlaybackKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                viewModel.channelUp()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                viewModel.channelDown()
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                showSettings()
                true
            }

            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }

    override fun onKeyLongPress(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showSettings()
                true
            }

            else -> super.onKeyLongPress(keyCode, event)
        }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (event?.isTracking == true && !event.isCanceled) {
            return handleTrackedKeyUp(keyCode)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleTrackedKeyUp(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val shouldShow = !isMenuVisible() && hasChannels()
                if (shouldShow) {
                    viewModel.setChannelListVisible(true)
                }
                shouldShow
            }

            else -> false
        }

    private fun handleBackPress() {
        when {
            binding.channelListContainer.isVisible -> viewModel.setChannelListVisible(false)
            supportFragmentManager.findFragmentByTag("Settings") != null -> {
                // Fragment handles itself usually
            }

            binding.overlayContainer.isVisible -> viewModel.toggleOverlay()
            else -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < BACK_PRESS_EXIT_DELAY) {
                    finish()
                } else {
                    lastBackPressTime = currentTime
                    Toast
                        .makeText(
                            this,
                            R.string.press_back_again_to_exit,
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    private fun showSettings() {
        try {
            SettingsDialogFragment().show(supportFragmentManager, "Settings")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error showing settings", e)
        }
    }

    private fun startWelcomeAnimation() {
        binding.welcomeDpadInner.apply {
            animate().cancel()
            alpha = ANIM_ALPHA_IDLE
            scaleX = ANIM_SCALE_IDLE
            scaleY = ANIM_SCALE_IDLE

            animate()
                .scaleX(ANIM_SCALE_PRESSED)
                .scaleY(ANIM_SCALE_PRESSED)
                .alpha(ANIM_ALPHA_ACTIVE)
                .setDuration(ANIM_DURATION_PRESS) // Slower, more deliberate press
                .withEndAction {
                    animate()
                        .scaleX(ANIM_SCALE_IDLE)
                        .scaleY(ANIM_SCALE_IDLE)
                        .alpha(ANIM_ALPHA_IDLE)
                        .setDuration(ANIM_DURATION_RELEASE)
                        .setStartDelay(ANIM_START_DELAY) // Longer pause at the bottom
                        .withEndAction { if (binding.welcomeContainer.isVisible) startWelcomeAnimation() }
                        .start()
                }.start()
        }
    }

    private fun stopWelcomeAnimation() {
        binding.welcomeDpadInner.animate().cancel()
    }
}
