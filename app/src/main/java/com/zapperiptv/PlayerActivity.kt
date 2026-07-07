package com.zapperiptv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zapperiptv.databinding.ActivityPlayerBinding
import com.zapperiptv.model.PlaybackState
import com.zapperiptv.player.MediaSourceHelper
import com.zapperiptv.ui.ChannelListAdapter
import com.zapperiptv.ui.ImageLoader
import com.zapperiptv.ui.SettingsDialogFragment
import com.zapperiptv.ui.gesture.SwipeGestureHandler
import com.zapperiptv.viewmodel.MainViewModel

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
    private lateinit var swipeGestureHandler: SwipeGestureHandler

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PREFETCH_COUNT = 20
        private const val CACHE_SIZE = 20
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupGestureHandler()
        observeViewModel()
    }

    private fun setupGestureHandler() {
        swipeGestureHandler = SwipeGestureHandler(
            context = this,
            onSwipeUp = { if (!isMenuVisible()) viewModel.channelUp() },
            onSwipeDown = { if (!isMenuVisible()) viewModel.channelDown() },
            onSwipeLeft = { if (!isMenuVisible()) viewModel.setChannelListVisible(true) },
            onSwipeRight = {
                if (binding.channelListContainer.isVisible) {
                    viewModel.setChannelListVisible(false)
                }
            },
            onSingleTap = {
                if (!isMenuVisible()) {
                    viewModel.setChannelListVisible(true)
                }
            },
            onLongPress = {
                if (!isMenuVisible()) showSettings()
            }
        )
    }

    private fun isMenuVisible(): Boolean =
        binding.channelListContainer.isVisible ||
            supportFragmentManager.findFragmentByTag("Settings") != null

    private fun setupRecyclerView() {
        channelListAdapter = ChannelListAdapter { position ->
            viewModel.selectChannel(position)
            viewModel.setChannelListVisible(false)
        }

        binding.channelRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity).apply {
                initialPrefetchItemCount = PREFETCH_COUNT
                isItemPrefetchEnabled = true
            }
            adapter = channelListAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(CACHE_SIZE)
            recycledViewPool.setMaxRecycledViews(0, CACHE_SIZE)
            itemAnimator = null

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) ImageLoader.resume()
                    else ImageLoader.pause()
                }
            })
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

        viewModel.errorMessage.observe(this) { msgId ->
            if (msgId != null) {
                binding.errorPersistent.setText(msgId)
                binding.errorPersistent.isVisible = true
            } else {
                binding.errorPersistent.isVisible = false
            }
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
            binding.welcomeInstructionText.text = if (playlists.isEmpty()) {
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

        val mediaSource = MediaSourceHelper.createMediaSource(url, dataSourceFactory, extractorsFactory)

        exoPlayer?.let { player ->
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
        if (exoPlayer != null) return

        dataSourceFactory = MediaSourceHelper.createDataSourceFactory()
        extractorsFactory = MediaSourceHelper.createExtractorsFactory()

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(pState: Int) {
                    val state = when (pState) {
                        Player.STATE_BUFFERING -> PlaybackState.Loading
                        Player.STATE_READY -> PlaybackState.Playing
                        Player.STATE_ENDED -> PlaybackState.Idle
                        else -> null
                    }
                    state?.let { viewModel.setPlaybackState(it) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    viewModel.setPlaybackState(PlaybackState.Error(error.message ?: getString(R.string.error_unknown)))
                }
            })
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            event?.startTracking()
        }

        if (binding.channelListContainer.isVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    viewModel.setChannelListVisible(false)
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        if (supportFragmentManager.findFragmentByTag("Settings") != null) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
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
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showSettings()
                true
            }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.isTracking == true && !event.isCanceled) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    handleBackPress()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isMenuVisible()) {
                        viewModel.setChannelListVisible(true)
                        return true
                    }
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleBackPress() {
        when {
            binding.channelListContainer.isVisible -> viewModel.setChannelListVisible(false)
            supportFragmentManager.findFragmentByTag("Settings") != null -> {
                // Fragment handles itself usually
            }
            binding.overlayContainer.isVisible -> viewModel.toggleOverlay()
            else -> onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun showSettings() {
        try {
            SettingsDialogFragment().show(supportFragmentManager, "Settings")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error showing settings", e)
        }
    }
}
