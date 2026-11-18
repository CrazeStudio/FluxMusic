package com.crazestudio.fluxmusic

import android.app.Application
import com.crazestudio.fluxmusic.data.local.db.FluxDatabase
import com.crazestudio.fluxmusic.data.local.prefs.UserPreferencesRepository
import com.crazestudio.fluxmusic.data.repository.MusicRepository
import com.crazestudio.fluxmusic.data.remote.AudioStreamResolver

class FluxApplication : Application() {
    val database: FluxDatabase by lazy { FluxDatabase.getInstance(this) }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository(this) }
    val repository: MusicRepository by lazy {
        MusicRepository(
            trackDao = database.trackDao(),
            playlistDao = database.playlistDao(),
            historyDao = database.historyDao(),
            streamCacheDao = database.streamCacheDao(),
            lyricsDao = database.lyricsDao(),
            prefsRepo = preferencesRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Native Innertube resolver requires no bundled runtime.
        AudioStreamResolver.initialize(this)
    }
}
