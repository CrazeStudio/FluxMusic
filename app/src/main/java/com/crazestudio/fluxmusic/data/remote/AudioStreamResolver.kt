package com.crazestudio.fluxmusic.data.remote

import android.util.Log
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Native stream backend for FluxMusic.
 *
 * YouTube Music is used for catalogue metadata. NewPipe Extractor is the single
 * stream-resolution implementation. Signed media URLs are network/session scoped,
 * so they are held in memory only and refreshed after a rejected request.
 */
data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "newpipe",
    val expiresAt: Long = 0L,
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val extraHeaders: Map<String, String> = emptyMap()
)

object AudioStreamResolver {
    private const val TAG = "FluxMusicBackend"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TECNO KI5k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private const val CACHE_TTL_MS = 45_000L
    private const val MAX_CACHE_ENTRIES = 64
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    private data class CacheEntry(val streams: List<ResolvedAudioStream>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val activeResolutions = ConcurrentHashMap<String, Boolean>()
    private val prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile
    private var extractorInitialized = false

    @Synchronized
    fun initializeExtractor() {
        if (extractorInitialized) return
        NewPipe.init(FluxDownloader())
        extractorInitialized = true
        Log.i(TAG, "NewPipe extractor initialized")
    }

    fun warmUp() {
        prefetchExecutor.execute {
            runCatching { initializeExtractor() }
                .onFailure { Log.e(TAG, "Extractor initialization failed", it) }
        }
    }

    fun prefetchAudioUrls(
        videoIds: List<String>,
        quality: AudioQuality = AudioQuality.AUTO
    ) {
        videoIds.asSequence()
            .filter { it.matches(VIDEO_ID_REGEX) }
            .distinct()
            .take(2)
            .forEach { videoId ->
                prefetchExecutor.execute {
                    runCatching { resolveCandidateStreams(videoId, quality) }
                        .onFailure { Log.w(TAG, "Prefetch failed for $videoId: ${it.message}") }
                }
            }
    }

    fun isResolving(videoId: String, quality: AudioQuality): Boolean =
        activeResolutions.containsKey(cacheKey(videoId, quality))

    fun resolveAudioUrl(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO
    ): String = resolveAudioStream(videoId, quality).url

    fun resolveAudioStream(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): ResolvedAudioStream {
        return resolveCandidateStreams(videoId, quality, forceRefresh)
            .firstOrNull()
            ?: throw IOException("The extractor returned no playable audio stream for this track.")
    }

    /**
     * Resolves audio-only URLs using NewPipe's YouTube extractor, which also
     * handles YouTube's signature/cipher transformations. No scraped public
     * proxy instances or persisted googlevideo URLs are used.
     */
    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(videoId.matches(VIDEO_ID_REGEX)) { "Invalid YouTube video ID." }

        val key = cacheKey(videoId, quality)
        val lock = locks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                cache[key]?.streams
                    ?.filter { it.expiresAt > now }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            cache.remove(key)

            activeResolutions[key] = true
            try {
                initializeExtractor()
                val extractor = NewPipe.getService("YouTube")
                    .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()

                val extracted = extractor.audioStreams.orEmpty()
                    .asSequence()
                    .filter { it.isUrl }
                    .map { stream ->
                        val streamUrl = stream.content
                        val mime = stream.format?.mimeType.orEmpty()
                        val expiry = calculateExpiry(streamUrl, System.currentTimeMillis())
                        val (agent, headers) = resolveIdentityForUrl(streamUrl, USER_AGENT)
                        ResolvedAudioStream(
                            url = streamUrl,
                            userAgent = agent,
                            source = "newpipe",
                            expiresAt = expiry,
                            mimeType = mime.substringBefore(';'),
                            bitrateKbps = stream.averageBitrate.coerceAtLeast(1),
                            extraHeaders = headers
                        )
                    }
                    .filter { candidate ->
                        candidate.url.startsWith("https://") &&
                            candidate.mimeType.startsWith("audio/", ignoreCase = true) &&
                            candidate.expiresAt > System.currentTimeMillis() &&
                            !candidate.url.contains(".m3u8", ignoreCase = true)
                    }
                    .distinctBy { it.url }
                    .toList()

                val ordered = orderCandidates(extracted, quality)
                if (ordered.isEmpty()) {
                    throw IOException(
                        "YouTube returned no direct audio-only formats for this track. " +
                            "The extractor could not produce a playable URL."
                    )
                }

                cache[key] = CacheEntry(ordered)
                trimCache()
                Log.i(TAG, "Resolved ${ordered.size} audio formats for $videoId via NewPipe")
                return ordered
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
                Log.e(TAG, "Audio extraction failed for $videoId: $detail", error)
                throw IOException("Audio extraction failed: $detail", error)
            } finally {
                activeResolutions.remove(key)
            }
        }
    }

    fun markCandidateSucceeded(
        videoId: String,
        quality: AudioQuality,
        stream: ResolvedAudioStream
    ) {
        val key = cacheKey(videoId, quality)
        cache.computeIfPresent(key) { _, entry ->
            CacheEntry(
                listOf(stream) + entry.streams.filterNot { it.url == stream.url }
            )
        }
    }

    fun markCandidateFailed(videoId: String, stream: ResolvedAudioStream) {
        cache.entries.forEach { entry ->
            if (entry.key.startsWith("$videoId:")) {
                cache.computeIfPresent(entry.key) { _, value ->
                    val remaining = value.streams.filterNot { it.url == stream.url }
                    if (remaining.isEmpty()) null else CacheEntry(remaining)
                }
            }
        }
        Log.w(TAG, "Discarded failed stream URL for $videoId (${stream.mimeType})")
    }

    fun markPlaybackFailed(videoId: String) {
        invalidateStream(videoId)
    }

    fun clearFailuresFor(videoId: String) {
        invalidateStream(videoId)
    }

    fun invalidateStream(videoId: String) {
        cache.keys.removeIf { it.startsWith("$videoId:") }
    }

    fun clearStreamCache() {
        cache.clear()
    }

    /**
     * Do not rewrite signed YouTube URL query parameters. Even harmless-looking
     * additions can invalidate a signature or change an upstream request.
     */
    fun prepareYoutubePlaybackUrl(url: String): String = url

    fun resolveIdentityForUrl(
        url: String,
        fallbackUa: String = USER_AGENT
    ): Pair<String, Map<String, String>> {
        val client = queryParameter(url, "c")?.uppercase().orEmpty()
        return when {
            client == "ANDROID_VR" -> ANDROID_VR_USER_AGENT to emptyMap()
            client == "IOS" -> IOS_USER_AGENT to emptyMap()
            client.startsWith("ANDROID") -> ANDROID_USER_AGENT to emptyMap()
            client == "VISIONOS" -> VISIONOS_USER_AGENT to emptyMap()
            client.startsWith("WEB") || client == "MWEB" || client.startsWith("TVHTML5") ->
                USER_AGENT to mapOf(
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/"
                )
            else -> fallbackUa to emptyMap()
        }
    }

    internal fun orderCandidates(
        candidates: List<ResolvedAudioStream>,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        if (candidates.isEmpty()) return emptyList()
        val targetBitrate = when (quality) {
            AudioQuality.DATA_SAVER -> 96
            AudioQuality.AUTO -> 128
            AudioQuality.HIGH -> Int.MAX_VALUE
        }
        return candidates.distinctBy { it.url }.sortedWith(
            compareBy<ResolvedAudioStream> {
                if (quality == AudioQuality.HIGH) -it.bitrateKbps
                else kotlin.math.abs(it.bitrateKbps - targetBitrate)
            }.thenByDescending { it.mimeType.contains("mp4", ignoreCase = true) }
                .thenByDescending { it.bitrateKbps }
        )
    }

    private fun trimCache() {
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            cache.remove(oldestKey)
        }
    }

    private fun cacheKey(videoId: String, quality: AudioQuality) =
        "$videoId:${quality.name}"

    private fun calculateExpiry(url: String, now: Long): Long {
        val hardLimit = now + CACHE_TTL_MS
        val upstreamExpiry = queryParameter(url, "expire")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.times(1000L)
            ?.minus(30_000L)
        return upstreamExpiry?.let { minOf(hardLimit, it) } ?: hardLimit
    }

    private fun queryParameter(url: String, name: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key.equals(name, ignoreCase = true)) {
                part.substringAfter('=', missingDelimiterValue = "")
            } else null
        }
    }

    private fun decodeInputStream(connection: HttpURLConnection, status: Int): InputStream? {
        val input = if (status in 200..399) connection.inputStream else connection.errorStream
            ?: return null
        return if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(input)
        } else input
    }

    private fun readBody(input: InputStream?): String {
        if (input == null) return ""
        return input.use { source ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
    }

    private class FluxDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val requestHeaders = request.headers().orEmpty()
            val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true

                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                requestHeaders.forEach { (name, values) ->
                    if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                        values.orEmpty().forEachIndexed { index, value ->
                            if (index == 0) setRequestProperty(name, value)
                            else addRequestProperty(name, value)
                        }
                    }
                }
            }

            return try {
                request.dataToSend()?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }

                val status = connection.responseCode
                val responseBody = readBody(decodeInputStream(connection, status))
                val responseHeaders = HashMap<String, MutableList<String>>()
                connection.headerFields.forEach { (name, values) ->
                    if (name != null && values != null) {
                        responseHeaders[name] = values.toMutableList()
                    }
                }
                Response(
                    status,
                    connection.responseMessage,
                    responseHeaders,
                    responseBody,
                    connection.url.toString()
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; Quest Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip"
    private const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{6,20}")
}
