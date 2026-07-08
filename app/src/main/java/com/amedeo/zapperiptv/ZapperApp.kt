package com.amedeo.zapperiptv

import android.app.Application
import com.amedeo.zapperiptv.network.PlaylistDownloader
import com.amedeo.zapperiptv.parser.M3uParser
import com.amedeo.zapperiptv.repository.PlaylistRepository
import com.amedeo.zapperiptv.storage.PreferencesManager
import com.amedeo.zapperiptv.ui.ImageLoader
import com.amedeo.zapperiptv.viewmodel.MainViewModelFactory

class ZapperApp : Application() {
    lateinit var preferencesManager: PreferencesManager
    lateinit var playlistDownloader: PlaylistDownloader
    lateinit var m3uParser: M3uParser
    lateinit var playlistRepository: PlaylistRepository
    lateinit var mainViewModelFactory: MainViewModelFactory

    override fun onCreate() {
        super.onCreate()
        ImageLoader.init(cacheDir)

        preferencesManager = PreferencesManager(this)
        playlistDownloader = PlaylistDownloader(this)
        m3uParser = M3uParser()

        playlistRepository =
            PlaylistRepository(
                this,
                preferencesManager,
                playlistDownloader,
                m3uParser,
            )

        mainViewModelFactory =
            MainViewModelFactory(
                playlistRepository,
                preferencesManager,
            )
    }
}
