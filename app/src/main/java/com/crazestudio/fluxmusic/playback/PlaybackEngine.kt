package com.crazestudio.fluxmusic.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.remote.AudioStreamResolver
import java.io.IOException

/**
 * Owns ExoPlayer construction and translates internal Flux track URIs into
 * real audio requests. Service and UI layers never build data sources.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    context: Context,
    private val resolver: AudioStreamResolver
) {
    private val appContext = context.applicationContext

    @Volatile private var quality: AudioQuality = AudioQuality.AUTO
    @Volatile private var offlineMode: Boolean = false

    fun updateConfiguration(quality: AudioQuality, offlineMode: Boolean) {
        this.quality = quality
        this.offlineMode = offlineMode
    }

    fun createPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AudioStreamResolver.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(25_000)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec: DataSpec ->
            val videoId = Track.extractVideoId(dataSpec.uri)
            if (videoId == null) {
                dataSpec
            } else {
                if (offlineMode) throw IOException("Offline Cache Mode is enabled. Disable it to stream.")
                val stream = resolver.resolveAudioStream(videoId, quality)
                // YouTube requires an rn parameter on actual media requests, not just probes.
                val requestUrl = resolver.prepareYoutubePlaybackUrl(stream.url)
                val isVisionOs = stream.userAgent == AudioStreamResolver.VISIONOS_USER_AGENT
                val headers = HashMap(dataSpec.httpRequestHeaders).apply {
                    put("User-Agent", stream.userAgent)
                    put("Accept-Encoding", "identity")
                    if (isVisionOs) put("TE", "trailers") else remove("TE")
                }
                dataSpec.buildUpon()
                    .setUri(Uri.parse(requestUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
        }

        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(resolvingFactory, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
        val selector = DefaultTrackSelector(appContext).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build())
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 45_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
