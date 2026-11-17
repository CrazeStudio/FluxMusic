package com.crazestudio.fluxmusic.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.crazestudio.fluxmusic.FluxApplication
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.remote.YtMusicCatalog
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    companion object {
        const val CMD_SET_SPEED = "com.crazestudio.fluxmusic.SET_SPEED"
        const val CMD_SET_EQ_ENABLED = "com.crazestudio.fluxmusic.SET_EQ_ENABLED"
        const val CMD_SET_EQ_BAND = "com.crazestudio.fluxmusic.SET_EQ_BAND"
        const val CMD_SET_EQ_PRESET = "com.crazestudio.fluxmusic.SET_EQ_PRESET"
        const val CMD_SET_BASS_BOOST = "com.crazestudio.fluxmusic.SET_BASS_BOOST"
        const val CMD_SET_SLEEP_TIMER = "com.crazestudio.fluxmusic.SET_SLEEP_TIMER"
        const val CMD_SET_QUALITY = "com.crazestudio.fluxmusic.SET_QUALITY"
        const val CMD_SYNC_EXTRAS = "com.crazestudio.fluxmusic.SYNC_EXTRAS"

        const val ARG_SPEED = "arg_speed"
        const val ARG_EQ_ENABLED = "arg_eq_enabled"
        const val ARG_EQ_BAND_INDEX = "arg_eq_band_index"
        const val ARG_EQ_BAND_LEVEL = "arg_eq_band_level"
        const val ARG_EQ_PRESET = "arg_eq_preset"
        const val ARG_BASS_STRENGTH = "arg_bass_strength"
        const val ARG_SLEEP_MINUTES = "arg_sleep_minutes"
        const val ARG_SLEEP_END_OF_TRACK = "arg_sleep_end_of_track"
        const val ARG_QUALITY = "arg_quality"

        const val EXTRA_SLEEP_REMAINING_SEC = "extra_sleep_remaining_sec"
        const val EXTRA_SLEEP_END_OF_TRACK = "extra_sleep_end_of_track"
        const val EXTRA_SPEED = "extra_speed"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val audioEffects = AudioEffectsManager()

    @Volatile
    private var currentQuality: AudioQuality = AudioQuality.AUTO

    @Volatile
    private var offlineCacheMode: Boolean = false

    private var sleepJob: Job? = null
    private var sleepRemainingSec: Int = 0
    private var sleepEndOfTrack: Boolean = false
    private var currentSpeed: Float = 1.0f

    override fun onCreate() {
        super.onCreate()
        val app = application as FluxApplication

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YtMusicCatalog.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec: DataSpec ->
            val videoId = Track.extractVideoId(dataSpec.uri) ?: return@Factory dataSpec
            if (offlineCacheMode) {
                throw IOException("Offline Cache Mode is enabled. Disable it in Settings to stream online.")
            }
            val streamUrl = YtMusicCatalog.resolveAudioUrl(videoId, currentQuality)
            dataSpec.withUri(Uri.parse(streamUrl))
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEffectsForSession(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.pause()
                    sleepEndOfTrack = false
                    sleepRemainingSec = 0
                    broadcastExtras()
                }
            }
        })

        serviceScope.launch {
            app.preferencesRepository.preferencesFlow.collect { prefs ->
                currentQuality = prefs.audioQuality
                offlineCacheMode = prefs.offlineCacheMode
                currentSpeed = prefs.playbackSpeed
                if (player.playbackParameters.speed != currentSpeed) {
                    player.playbackParameters = PlaybackParameters(currentSpeed)
                }
                if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    audioEffects.attachToSession(
                        audioSessionId = player.audioSessionId,
                        savedEnabled = prefs.eqEnabled,
                        savedPreset = prefs.eqPreset,
                        savedBands = prefs.eqBands,
                        savedBass = prefs.bassBoostStrength
                    )
                    broadcastExtras()
                }
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(FluxSessionCallback())
            .build()
    }

    private fun attachEffectsForSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        val app = application as FluxApplication
        serviceScope.launch {
            val prefs = app.preferencesRepository.preferencesFlow.first()
            audioEffects.attachToSession(
                audioSessionId = sessionId,
                savedEnabled = prefs.eqEnabled,
                savedPreset = prefs.eqPreset,
                savedBands = prefs.eqBands,
                savedBass = prefs.bassBoostStrength
            )
            broadcastExtras()
        }
    }

    private fun broadcastExtras() {
        val extras = Bundle().apply {
            putAll(audioEffects.state.toBundle())
            putInt(EXTRA_SLEEP_REMAINING_SEC, sleepRemainingSec)
            putBoolean(EXTRA_SLEEP_END_OF_TRACK, sleepEndOfTrack)
            putFloat(EXTRA_SPEED, currentSpeed)
        }
        mediaSession?.setSessionExtras(extras)
    }

    private fun updateSleepTimer(minutes: Int, endOfTrack: Boolean) {
        sleepJob?.cancel()
        sleepJob = null
        player.volume = 1.0f
        if (endOfTrack) {
            sleepEndOfTrack = true
            sleepRemainingSec = 0
            broadcastExtras()
            return
        }
        sleepEndOfTrack = false
        if (minutes <= 0) {
            sleepRemainingSec = 0
            broadcastExtras()
            return
        }
        sleepRemainingSec = minutes * 60
        broadcastExtras()
        sleepJob = serviceScope.launch {
            while (sleepRemainingSec > 0) {
                delay(1000L)
                sleepRemainingSec -= 1
                if (sleepRemainingSec in 1..3 && player.isPlaying) {
                    player.volume = sleepRemainingSec / 3f
                }
                broadcastExtras()
            }
            player.pause()
            player.volume = 1.0f
            sleepRemainingSec = 0
            broadcastExtras()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        sleepJob?.cancel()
        serviceScope.cancel()
        audioEffects.release()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private inner class FluxSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val builder = baseResult.availableSessionCommands.buildUpon()
            listOf(
                CMD_SET_SPEED,
                CMD_SET_EQ_ENABLED,
                CMD_SET_EQ_BAND,
                CMD_SET_EQ_PRESET,
                CMD_SET_BASS_BOOST,
                CMD_SET_SLEEP_TIMER,
                CMD_SET_QUALITY,
                CMD_SYNC_EXTRAS
            ).forEach { action ->
                builder.add(SessionCommand(action, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(builder.build())
                .setAvailablePlayerCommands(baseResult.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val app = application as FluxApplication
            when (customCommand.customAction) {
                CMD_SET_SPEED -> {
                    val speed = args.getFloat(ARG_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
                    currentSpeed = speed
                    player.playbackParameters = PlaybackParameters(speed)
                    serviceScope.launch { app.preferencesRepository.setPlaybackSpeed(speed) }
                    broadcastExtras()
                }
                CMD_SET_EQ_ENABLED -> {
                    val enabled = args.getBoolean(ARG_EQ_ENABLED, false)
                    val s = audioEffects.state
                    audioEffects.applyConfig(enabled, s.preset, s.bandLevelsMb, s.bassBoostStrength)
                    persistEq(app)
                    broadcastExtras()
                }
                CMD_SET_EQ_BAND -> {
                    val index = args.getInt(ARG_EQ_BAND_INDEX, -1)
                    val level = args.getInt(ARG_EQ_BAND_LEVEL, 0)
                    audioEffects.setBandLevel(index, level)
                    persistEq(app)
                    broadcastExtras()
                }
                CMD_SET_EQ_PRESET -> {
                    val name = args.getString(ARG_EQ_PRESET) ?: EqualizerPreset.FLAT.name
                    val preset = runCatching { EqualizerPreset.valueOf(name) }.getOrDefault(EqualizerPreset.FLAT)
                    audioEffects.applyPreset(preset)
                    persistEq(app)
                    broadcastExtras()
                }
                CMD_SET_BASS_BOOST -> {
                    val strength = args.getInt(ARG_BASS_STRENGTH, 0).coerceIn(0, 1000)
                    val s = audioEffects.state
                    audioEffects.applyConfig(s.enabled, s.preset, s.bandLevelsMb, strength)
                    persistEq(app)
                    broadcastExtras()
                }
                CMD_SET_SLEEP_TIMER -> {
                    val mins = args.getInt(ARG_SLEEP_MINUTES, 0)
                    val endOfTrack = args.getBoolean(ARG_SLEEP_END_OF_TRACK, false)
                    updateSleepTimer(mins, endOfTrack)
                }
                CMD_SET_QUALITY -> {
                    val name = args.getString(ARG_QUALITY) ?: AudioQuality.AUTO.name
                    currentQuality = runCatching { AudioQuality.valueOf(name) }.getOrDefault(AudioQuality.AUTO)
                }
                CMD_SYNC_EXTRAS -> {
                    if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        attachEffectsForSession(player.audioSessionId)
                    } else {
                        broadcastExtras()
                    }
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        private fun persistEq(app: FluxApplication) {
            val s = audioEffects.state
            serviceScope.launch {
                app.preferencesRepository.setEqualizer(
                    enabled = s.enabled,
                    preset = s.preset,
                    bands = s.bandLevelsMb,
                    bassBoost = s.bassBoostStrength
                )
            }
        }
    }
}
