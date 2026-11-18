package com.crazestudio.fluxmusic.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.Track
import com.crazestudio.fluxmusic.data.remote.AudioStreamResolver
import com.crazestudio.fluxmusic.data.remote.ResolvedAudioStream
import java.io.IOException

/**
 * Owns ExoPlayer construction and translates internal Flux track URIs into
 * real audio requests with seamless candidate-stream fallback on open failures.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    context: Context
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
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(12_000)

        val fluxDataSourceFactory = DataSource.Factory {
            FluxAudioDataSource(
                delegate = httpFactory.createDataSource(),
                qualityProvider = { quality },
                offlineProvider = { offlineMode }
            )
        }

        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val errorPolicy = object : DefaultLoadErrorHandlingPolicy(1) {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                // If FluxAudioDataSource already exhausted every provider & candidate, fail immediately
                // rather than stalling the UI with exponential backoff retries on dead streams.
                if (loadErrorInfo.exception is AllCandidatesFailedException) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(fluxDataSourceFactory, extractors)
            .setLoadErrorHandlingPolicy(errorPolicy)
        val selector = DefaultTrackSelector(appContext).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build())
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 500, 1_500)
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

    private class AllCandidatesFailedException(
        message: String,
        cause: Throwable?
    ) : IOException(message, cause)

    private class FluxAudioDataSource(
        private val delegate: HttpDataSource,
        private val qualityProvider: () -> AudioQuality,
        private val offlineProvider: () -> Boolean
    ) : DataSource {

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val videoId = Track.extractVideoId(dataSpec.uri)
                ?: return delegate.open(dataSpec)

            if (offlineProvider()) {
                throw AllCandidatesFailedException(
                    "Offline Cache Mode is enabled. Disable it to stream.",
                    null
                )
            }

            val attemptedUrls = HashSet<String>()
            var lastError: Throwable? = null
            var round = 0
            val maxRounds = 3

            while (round < maxRounds) {
                val forceRefresh = round > 0
                val activeQuality = qualityProvider()
                val candidates = try {
                    AudioStreamResolver.resolveCandidateStreams(
                        videoId = videoId,
                        quality = activeQuality,
                        forceRefresh = forceRefresh
                    )
                } catch (e: Exception) {
                    lastError = e
                    break
                }

                val freshCandidates = candidates.filter { attemptedUrls.add(it.url) }
                if (freshCandidates.isEmpty()) {
                    round++
                    continue
                }

                // Limit per-round opens so a single track fails cleanly rather than looping forever.
                for (candidate in freshCandidates.take(6)) {
                    val resolvedSpec = buildCandidateSpec(dataSpec, candidate)
                    try {
                        val length = delegate.open(resolvedSpec)
                        AudioStreamResolver.markCandidateSucceeded(videoId, activeQuality, candidate)
                        return length
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(
                            "FluxPlaybackEngine",
                            "Stream candidate failed (${candidate.source}, ${candidate.mimeType}): ${e.message}"
                        )
                        runCatching { delegate.close() }
                        AudioStreamResolver.markCandidateFailed(videoId, candidate)
                    }
                }
                round++
            }

            throw AllCandidatesFailedException(
                lastError?.message ?: "Unable to stream this track right now. Please retry.",
                lastError
            )
        }

        private fun buildCandidateSpec(
            original: DataSpec,
            candidate: ResolvedAudioStream
        ): DataSpec {
            val requestUrl = AudioStreamResolver.prepareYoutubePlaybackUrl(candidate.url)
            val headers = HashMap(original.httpRequestHeaders).apply {
                remove("TE")
                put("User-Agent", candidate.userAgent)
                put("Accept-Encoding", "identity")
                candidate.extraHeaders.forEach { (k, v) -> put(k, v) }
            }
            return original.buildUpon()
                .setUri(Uri.parse(requestUrl))
                .setHttpRequestHeaders(headers)
                .build()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate.responseHeaders

        override fun close() {
            delegate.close()
        }
    }
}
