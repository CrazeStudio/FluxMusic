package com.crazestudio.fluxmusic.data.remote

import android.content.Context
import android.net.Uri
import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "yt-dlp",
    val expiresAt: Long = 0L,
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * The only YouTube playback extractor in FluxMusic.
 *
 * yt-dlp and its bundled QuickJS runtime perform extraction on worker threads.
 * Signed media URLs are kept in memory only and are never written to Room.
 */
object AudioStreamResolver {
    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val STREAM_CACHE_TTL_MS = 4 * 60 * 1000L

    private const val MAX_CACHE_ENTRIES = 80
    private const val FAILED_URL_BACKOFF_MS = 60_000L
    private const val INIT_TIMEOUT_SECONDS = 120L

    private data class CacheEntry(
        val expiresAt: Long,
        val candidates: List<ResolvedAudioStream>
    )

    private val initExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FluxMusic-yt-dlp-init").apply { isDaemon = true }
    }
    private val workerExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "FluxMusic-yt-dlp-worker").apply { isDaemon = true }
    }
    private val requestNumber = AtomicLong(0L)
    private val inFlight = ConcurrentHashMap<String, FutureTask<List<ResolvedAudioStream>>>()
    private val failedUrls = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val streamCache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var initialization: FutureTask<Unit>? = null

    /** Starts extraction runtime initialization without blocking Application.onCreate(). */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (initialization != null) return

        synchronized(this) {
            if (initialization != null) return
            val app = context.applicationContext
            val task = FutureTask(Callable {
                YoutubeDL.getInstance().init(app)
                Unit
            })
            initialization = task
            initExecutor.execute(task)
        }
    }

    /** Compatibility with the playback service: initialization is already asynchronous. */
    fun warmUp() {
        applicationContext?.let(::initialize)
    }

    private fun awaitInitialized() {
        val task = initialization
            ?: throw IOException("yt-dlp is not initialized. Restart FluxMusic and try again.")
        try {
            task.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("yt-dlp initialization timed out. Please retry.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("yt-dlp initialization was interrupted.", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            synchronized(this) {
                if (initialization === task) initialization = null
            }
            applicationContext?.let(::initialize)
            throw IOException("yt-dlp could not initialize: ${cause.message ?: "unknown error"}", cause)
        }
    }

    /**
     * YouTube accepts the rn request counter on googlevideo media URLs.
     * Keep the original signed query intact and replace only rn.
     */
    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val isGoogleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        if (!isGoogleVideo || !uri.path.orEmpty().startsWith("/videoplayback")) return url

        val rn = requestNumber.incrementAndGet().toString()
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return "$url?rn=$rn"

        val base = url.substring(0, queryIndex)
        val query = url.substring(queryIndex + 1)
            .split('&')
            .filter { it.isNotEmpty() && !it.substringBefore('=').equals("rn", true) }
        "$base?${(query + "rn=$rn").joinToString("&")}"
    }.getOrDefault(url)

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
        }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        videoIds.asSequence().filter { it.matches(VIDEO_ID) }.distinct().take(2).forEach { videoId ->
            val key = cacheKey(videoId, quality)
            val cached = synchronized(streamCache) {
                streamCache[key]?.let { it.expiresAt > System.currentTimeMillis() } == true
            }
            if (!cached && !inFlight.containsKey(key)) {
                workerExecutor.execute {
                    runCatching { resolveCandidateStreams(videoId, quality) }
                }
            }
        }
    }

    fun resolveAudioUrl(videoId: String, quality: AudioQuality = AudioQuality.AUTO): String =
        resolveAudioStream(videoId, quality).url

    fun resolveAudioStream(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): ResolvedAudioStream {
        val candidates = resolveCandidateStreams(videoId, quality, forceRefresh)
        return candidates.firstOrNull { !isUrlFailed(videoId, it.url) }
            ?: candidates.firstOrNull()
            ?: throw IOException("yt-dlp returned no playable audio stream.")
    }

    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(VIDEO_ID.matches(videoId)) { "Invalid YouTube video ID" }
        val key = cacheKey(videoId, quality)
        val now = System.currentTimeMillis()

        if (forceRefresh) {
            invalidateStream(videoId)
        } else {
            synchronized(streamCache) {
                streamCache[key]?.let { entry ->
                    if (entry.expiresAt > now) {
                        val available = entry.candidates.filterNot { isUrlFailed(videoId, it.url) }
                        if (available.isNotEmpty()) return available
                    }
                    streamCache.remove(key)
                }
            }
        }

        val flightKey = if (forceRefresh) "$key:refresh" else key
        val mine = FutureTask(Callable { extractWithYtDlp(videoId, quality) })
        val existing = inFlight.putIfAbsent(flightKey, mine)
        val active = existing ?: mine

        try {
            if (existing == null) active.run()
            val resolved = active.get()
            val resolvedAt = System.currentTimeMillis()
            val stamped = resolved.map { stream ->
                stream.copy(expiresAt = calculateExpiry(stream.url, resolvedAt))
            }
            // Never hand Media3 a URL that has already crossed its signed expiry
            // (or the safety margin immediately before it).
            val playable = stamped.filter { it.expiresAt > resolvedAt }
            if (playable.isEmpty()) {
                throw IOException("yt-dlp returned stream URLs that are already expired. Please retry.")
            }
            val expiry = playable.minOf { it.expiresAt }

            synchronized(streamCache) {
                streamCache[key] = CacheEntry(expiry, playable)
                while (streamCache.size > MAX_CACHE_ENTRIES) {
                    streamCache.keys.firstOrNull()?.let(streamCache::remove) ?: break
                }
            }
            return playable.filterNot { isUrlFailed(videoId, it.url) }.ifEmpty { playable }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("yt-dlp extraction was interrupted.", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is IOException) throw cause
            throw IOException("yt-dlp extraction failed: ${cause.message ?: "unknown error"}", cause)
        } finally {
            inFlight.remove(flightKey, active)
        }
    }

    private fun extractWithYtDlp(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        awaitInitialized()

        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--quiet")
            .addOption("--no-progress")
            .addOption("--socket-timeout", "12")
            .addOption("--retries", "1")
            .addOption("--extractor-retries", "1")
            .addOption("-f", "bestaudio/best")

        val info = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: Exception) {
            throw IOException("yt-dlp could not extract this YouTube audio: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val candidates = LinkedHashMap<String, ResolvedAudioStream>()
        info.formats.orEmpty().forEach { format ->
            toAudioStream(format, info)?.let { candidates[it.url] = it }
        }

        // yt-dlp's selected direct URL is included even when the format mapper
        // does not expose that exact format in its formats array.
        info.url?.takeIf(::isDirectHttpsMedia)?.let { selectedUrl ->
            if (!candidates.containsKey(selectedUrl)) {
                val selectedFormat = info.formats.orEmpty().firstOrNull { it.url == selectedUrl }
                candidates[selectedUrl] = ResolvedAudioStream(
                    url = selectedUrl,
                    userAgent = userAgent(info.httpHeaders),
                    source = "yt-dlp",
                    mimeType = selectedFormat?.let(::mimeType).orEmpty(),
                    bitrateKbps = selectedFormat?.let(::bitrateKbps) ?: 0,
                    extraHeaders = safeHeaders(info.httpHeaders)
                )
            }
        }

        val ordered = orderCandidates(candidates.values.toList(), quality)
        if (ordered.isEmpty()) {
            throw IOException("yt-dlp found no direct audio-only URL for this video.")
        }
        return ordered
    }

    private fun toAudioStream(format: VideoFormat, info: VideoInfo): ResolvedAudioStream? {
        val url = format.url?.takeIf(::isDirectHttpsMedia) ?: return null
        val audioCodec = format.acodec.orEmpty()
        val videoCodec = format.vcodec.orEmpty()
        if (audioCodec.isBlank() || audioCodec.equals("none", ignoreCase = true)) return null
        if (videoCodec.isNotBlank() && !videoCodec.equals("none", ignoreCase = true)) return null

        val headers = format.httpHeaders?.takeIf { it.isNotEmpty() } ?: info.httpHeaders
        return ResolvedAudioStream(
            url = url,
            userAgent = userAgent(headers),
            source = "yt-dlp",
            mimeType = mimeType(format),
            bitrateKbps = bitrateKbps(format),
            extraHeaders = safeHeaders(headers)
        )
    }

    fun orderCandidates(
        candidates: List<ResolvedAudioStream>,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        val indexed = candidates.withIndex()
        val sorted = when (quality) {
            AudioQuality.DATA_SAVER -> {
                val sorted = indexed.sortedWith(
                    compareByDescending<IndexedValue<ResolvedAudioStream>> { it.value.bitrateKbps }
                        .thenBy { mp4Preference(it.value.mimeType) }
                        .thenBy { it.index }
                )
                sorted.filter { it.value.bitrateKbps in 32..96 } +
                    sorted.asReversed()
            }
            AudioQuality.AUTO -> {
                val sorted = indexed.sortedWith(
                    compareByDescending<IndexedValue<ResolvedAudioStream>> { it.value.bitrateKbps }
                        .thenBy { mp4Preference(it.value.mimeType) }
                        .thenBy { it.index }
                )
                val balanced = sorted.filter { it.value.bitrateKbps in 96..160 }
                val fallback = sorted.filter { it.value.bitrateKbps in 64..192 }
                (balanced + fallback + sorted).distinctBy { it.value.url }
            }
            AudioQuality.HIGH -> indexed.sortedWith(
                compareByDescending<IndexedValue<ResolvedAudioStream>> { it.value.bitrateKbps }
                    .thenBy { mp4Preference(it.value.mimeType) }
                    .thenBy { it.index }
            )
        }
        return sorted.map { it.value }
    }

    fun markCandidateSucceeded(
        videoId: String,
        quality: AudioQuality,
        stream: ResolvedAudioStream
    ) {
        synchronized(streamCache) {
            val key = cacheKey(videoId, quality)
            streamCache[key]?.let { entry ->
                streamCache[key] = entry.copy(
                    candidates = listOf(stream) + entry.candidates.filterNot { it.url == stream.url }
                )
            }
        }
    }

    fun markCandidateFailed(videoId: String, stream: ResolvedAudioStream) {
        failedUrls.computeIfAbsent(videoId) { ConcurrentHashMap() }[stream.url] =
            System.currentTimeMillis() + FAILED_URL_BACKOFF_MS
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach { key ->
                val entry = streamCache[key] ?: return@forEach
                val remaining = entry.candidates.filterNot { it.url == stream.url }
                if (remaining.isEmpty()) streamCache.remove(key)
                else streamCache[key] = entry.copy(candidates = remaining)
            }
        }
    }

    fun markPlaybackFailed(videoId: String) {
        invalidateStream(videoId)
    }

    fun clearFailuresFor(videoId: String) {
        failedUrls.remove(videoId)
        invalidateStream(videoId)
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
        failedUrls.clear()
    }

    private fun isUrlFailed(videoId: String, url: String): Boolean {
        val map = failedUrls[videoId] ?: return false
        val until = map[url] ?: return false
        if (until <= System.currentTimeMillis()) {
            map.remove(url, until)
            if (map.isEmpty()) failedUrls.remove(videoId, map)
            return false
        }
        return true
    }

    internal fun calculateExpiry(url: String, now: Long): Long {
        val upstreamExpiry = runCatching {
            Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L)
        }.getOrNull()
        // A signed URL must expire from our cache before the provider's deadline.
        // Do not extend an almost-expired URL with a minimum TTL.
        return minOf(upstreamExpiry?.minus(45_000L) ?: (now + STREAM_CACHE_TTL_MS), now + STREAM_CACHE_TTL_MS)
    }

    private fun isDirectHttpsMedia(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) &&
            !url.contains(".m3u8", ignoreCase = true) &&
            !url.contains("manifest", ignoreCase = true)

    private fun bitrateKbps(format: VideoFormat): Int =
        maxOf(format.abr, format.tbr).coerceAtLeast(0)

    private fun mimeType(format: VideoFormat): String = when (format.ext.orEmpty().lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "opus" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> ""
    }

    private fun userAgent(headers: Map<String, String>?): String =
        headers?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() } ?: USER_AGENT

    private fun safeHeaders(headers: Map<String, String>?): Map<String, String> =
        headers.orEmpty().filterKeys { key ->
            !key.equals("User-Agent", true) &&
                !key.equals("Host", true) &&
                !key.equals("Content-Length", true) &&
                !key.equals("Connection", true) &&
                !key.equals("Accept-Encoding", true)
        }

    private fun mp4Preference(mimeType: String): Int =
        if (mimeType.equals("audio/mp4", ignoreCase = true)) 0 else 1

    private fun cacheKey(videoId: String, quality: AudioQuality) = "$videoId:${quality.name}"

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")
}
