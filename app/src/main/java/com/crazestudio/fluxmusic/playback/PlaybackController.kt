package com.crazestudio.fluxmusic.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.crazestudio.fluxmusic.data.model.EqualizerPreset
import com.crazestudio.fluxmusic.data.model.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val activeTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val sourceLabel: String = "FluxMusic",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val playbackSpeed: Float = 1.0f,
    val sleepRemainingSec: Int = 0,
    val sleepEndOfTrack: Boolean = false,
    val equalizer: EqualizerState = EqualizerState(),
    val playbackError: String? = null
)

class PlaybackController(
    context: Context,
    private val onTrackStarted: (Track) -> Unit
) : Player.Listener, MediaController.Listener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var tickerJob: Job? = null

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(this)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(this)
                _state.update { it.copy(isConnected = true) }
                syncFromPlayer()
                sendCustomCommand(MusicService.CMD_SYNC_EXTRAS, Bundle.EMPTY)
                startTicker()
            } catch (e: Exception) {
                _state.update { it.copy(playbackError = "Could not connect to playback service.") }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                controller?.let { c ->
                    if (c.isPlaying || c.playbackState == Player.STATE_BUFFERING) {
                        val dur = c.duration.takeIf { it > 0L } ?: _state.value.activeTrack?.durationMs ?: 0L
                        val pos = c.currentPosition.coerceAtLeast(0L)
                        _state.update { it.copy(positionMs = pos, durationMs = dur) }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        val queueItems = (0 until c.mediaItemCount).map { idx ->
            Track.fromMediaItem(c.getMediaItemAt(idx))
        }
        val activeIdx = c.currentMediaItemIndex.takeIf { it in queueItems.indices } ?: -1
        val activeTrack = queueItems.getOrNull(activeIdx)
        val duration = c.duration.takeIf { it > 0L } ?: activeTrack?.durationMs ?: 0L
        _state.update {
            it.copy(
                activeTrack = activeTrack,
                queue = queueItems,
                currentIndex = activeIdx,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                playbackSpeed = c.playbackParameters.speed
            )
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, sourceLabel: String) {
        if (tracks.isEmpty()) return
        val c = controller ?: return
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        val mediaItems = tracks.map { it.toMediaItem() }
        _state.update { it.copy(sourceLabel = sourceLabel, playbackError = null) }
        c.setMediaItems(mediaItems, safeIdx, 0L)
        c.prepare()
        c.play()
        syncFromPlayer()
        tracks.getOrNull(safeIdx)?.let(onTrackStarted)
    }

    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), 0, "Queue")
            return
        }
        val insertIdx = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
        c.addMediaItem(insertIdx, track.toMediaItem())
        syncFromPlayer()
    }

    fun addToQueue(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), 0, "Queue")
            return
        }
        c.addMediaItem(track.toMediaItem())
        syncFromPlayer()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (fromIndex !in 0 until count || toIndex !in 0 until count || fromIndex == toIndex) return
        c.moveMediaItem(fromIndex, toIndex)
        syncFromPlayer()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        syncFromPlayer()
    }

    fun clearUpcomingQueue() {
        val c = controller ?: return
        val currentIdx = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (currentIdx + 1 < count) {
            c.removeMediaItems(currentIdx + 1, count)
            syncFromPlayer()
        }
    }

    fun jumpToQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
        syncFromPlayer()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
        syncFromPlayer()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun skipNext() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        }
    }

    fun skipPrevious() {
        val c = controller ?: return
        if (c.currentPosition > 3500L) {
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
            c.play()
        } else {
            c.seekTo(0L)
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        syncFromPlayer()
    }

    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncFromPlayer()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _state.update { it.copy(playbackSpeed = clamped) }
        sendCustomCommand(MusicService.CMD_SET_SPEED, Bundle().apply {
            putFloat(MusicService.ARG_SPEED, clamped)
        })
    }

    fun setEqEnabled(enabled: Boolean) {
        sendCustomCommand(MusicService.CMD_SET_EQ_ENABLED, Bundle().apply {
            putBoolean(MusicService.ARG_EQ_ENABLED, enabled)
        })
    }

    fun setEqBand(index: Int, levelMb: Int) {
        sendCustomCommand(MusicService.CMD_SET_EQ_BAND, Bundle().apply {
            putInt(MusicService.ARG_EQ_BAND_INDEX, index)
            putInt(MusicService.ARG_EQ_BAND_LEVEL, levelMb)
        })
    }

    fun applyEqPreset(preset: EqualizerPreset) {
        sendCustomCommand(MusicService.CMD_SET_EQ_PRESET, Bundle().apply {
            putString(MusicService.ARG_EQ_PRESET, preset.name)
        })
    }

    fun setBassBoost(strength: Int) {
        sendCustomCommand(MusicService.CMD_SET_BASS_BOOST, Bundle().apply {
            putInt(MusicService.ARG_BASS_STRENGTH, strength.coerceIn(0, 1000))
        })
    }

    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false) {
        sendCustomCommand(MusicService.CMD_SET_SLEEP_TIMER, Bundle().apply {
            putInt(MusicService.ARG_SLEEP_MINUTES, minutes)
            putBoolean(MusicService.ARG_SLEEP_END_OF_TRACK, endOfTrack)
        })
    }

    fun clearError() {
        _state.update { it.copy(playbackError = null) }
    }

    private fun sendCustomCommand(action: String, args: Bundle) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        syncFromPlayer()
        mediaItem?.let { onTrackStarted(Track.fromMediaItem(it)) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncFromPlayer()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        syncFromPlayer()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        syncFromPlayer()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        syncFromPlayer()
    }

    override fun onPlayerError(error: PlaybackException) {
        val msg = error.cause?.message ?: error.message ?: "Unable to stream this track right now."
        _state.update { it.copy(playbackError = msg, isBuffering = false) }
    }

    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
        val eq = EqualizerState.fromBundle(extras)
        val sleepSec = extras.getInt(MusicService.EXTRA_SLEEP_REMAINING_SEC, 0)
        val sleepEnd = extras.getBoolean(MusicService.EXTRA_SLEEP_END_OF_TRACK, false)
        val speed = extras.getFloat(MusicService.EXTRA_SPEED, _state.value.playbackSpeed)
        _state.update {
            it.copy(
                equalizer = eq,
                sleepRemainingSec = sleepSec,
                sleepEndOfTrack = sleepEnd,
                playbackSpeed = speed
            )
        }
    }

    fun release() {
        tickerJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        controller?.removeListener(this)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }
}
