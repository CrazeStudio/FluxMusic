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
 * Media3 adapter. UI continues to submit Flux track IDs as before; this layer
 * resolves each ID to a fresh native audio URL only when Media3 opens the item.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(context: Context) {
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
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity"
                )
            )

        val sourceFactory = DataSource.Factory {
            NativeAudioDataSource(
                delegate = httpFactory.createDataSource(),
                qualityProvider = { quality },
                offlineProvider = { offlineMode }
            )
        }

        val errorPolicy = object : DefaultLoadErrorHandlingPolicy(1) {
            override fun getRetryDelayMsFor(
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                if (loadErrorInfo.exception is AllAudioCandidatesFailedException) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(
            sourceFactory,
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        ).setLoadErrorHandlingPolicy(errorPolicy)

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

    private class AllAudioCandidatesFailedException(
        message: String,
        cause: Throwable?
    ) : IOException(message, cause)

    private class NativeAudioDataSource(
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
                throw AllAudioCandidatesFailedException(
                    "Offline Cache Mode is enabled. Disable it to stream.",
                    null
                )
            }

            var lastError: Exception? = null
            val attemptedUrls = HashSet<String>()

            for (round in 0..1) {
                val activeQuality = qualityProvider()
                val candidates = try {
                    AudioStreamResolver.resolveCandidateStreams(
                        videoId = videoId,
                        quality = activeQuality,
                        forceRefresh = round > 0
                    )
                } catch (error: Exception) {
                    lastError = error
                    break
                }

                for (candidate in candidates) {
                    if (!attemptedUrls.add(candidate.url)) continue
                    try {
                        delegate.close()
                        val resolvedSpec = buildCandidateSpec(dataSpec, candidate)
                        val openedLength = delegate.open(resolvedSpec)
                        AudioStreamResolver.markCandidateSucceeded(
                            videoId,
                            activeQuality,
                            candidate
                        )
                        Log.i(
                            "FluxPlayback",
                            "Opened ${candidate.mimeType} stream (${candidate.bitrateKbps} kbps)"
                        )
                        return openedLength
                    } catch (error: Exception) {
                        lastError = error
                        Log.w(
                            "FluxPlayback",
                            "Stream open rejected by upstream (${candidate.mimeType}): ${error.message}"
                        )
                        runCatching { delegate.close() }
                        AudioStreamResolver.markCandidateFailed(videoId, candidate)
                    }
                }
            }

            val detail = lastError?.message?.takeIf { it.isNotBlank() }
                ?: "No usable audio format was returned."
            throw AllAudioCandidatesFailedException(
                "Could not open an audio stream for this track: $detail",
                lastError
            )
        }

        private fun buildCandidateSpec(
            original: DataSpec,
            candidate: ResolvedAudioStream
        ): DataSpec {
            val headers = HashMap(original.httpRequestHeaders).apply {
                remove("TE")
                put("User-Agent", candidate.userAgent)
                put("Accept-Encoding", "identity")
                candidate.extraHeaders.forEach { (name, value) -> put(name, value) }
            }
            return original.buildUpon()
                .setUri(Uri.parse(AudioStreamResolver.prepareYoutubePlaybackUrl(candidate.url)))
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
