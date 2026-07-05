package com.zapperiptv

import android.app.Application
import com.zapperiptv.network.PlaylistDownloader
import com.zapperiptv.parser.M3uParser
import com.zapperiptv.repository.PlaylistRepository
import com.zapperiptv.storage.PreferencesManager
import com.zapperiptv.ui.ImageLoader
import com.zapperiptv.viewmodel.MainViewModelFactory

class ZapperApp : Application() {
    
    lateinit var preferencesManager: PreferencesManager
    lateinit var playlistDownloader: PlaylistDownloader
    lateinit var m3uParser: M3uParser
    lateinit var playlistRepository: PlaylistRepository
    lateinit var mainViewModelFactory: MainViewModelFactory

    override fun onCreate() {
        super.onCreate()
        ImageLoader.init(cacheDir)   // uses internal cache

        // Manual dependency injection
        preferencesManager = PreferencesManager(this)
        playlistDownloader = PlaylistDownloader(this)
        m3uParser = M3uParser()
        
        playlistRepository = PlaylistRepository(
            this,
            preferencesManager,
            playlistDownloader,
            m3uParser
        )
        
        mainViewModelFactory = MainViewModelFactory(
            playlistRepository,
            preferencesManager
        )
    }
}
