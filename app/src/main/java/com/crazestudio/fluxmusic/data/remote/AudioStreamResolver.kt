package com.crazestudio.fluxmusic.data.remote

import android.net.Uri
import android.util.Log
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "youtube",
    val expiresAt: Long = 0L
)

/**
 * Owns stream extraction, source fallback, short-lived URL caching and prefetch.
 * No catalogue/UI code is allowed to perform stream resolution.
 */
object AudioStreamResolver {
    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; GB)"
    const val STREAM_CACHE_TAG = "|flux-resolver-v5"
    const val STREAM_CACHE_TTL_MS = 30 * 60 * 1000L

    private data class StreamCacheEntry(val expiresAt: Long, val stream: ResolvedAudioStream)

        private val PIPED_INSTANCES = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.nosebs.ru",
            "https://piped-api.privacy.com.de",
            "https://pipedapi.owo.si",
            "https://pipedapi.drgns.space"
        )
    
        // Public instances listed by the Invidious project; availability can vary by region.

        private val INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yt.chocolatemoo53.com",
            "https://invidious.tiekoetter.com"
        )
    
        @Volatile

        private var extractorReady = false
    
        @Volatile

        private var persistentReader: ((videoId: String, quality: AudioQuality) -> ResolvedAudioStream?)? = null
    
        @Volatile

        private var persistentWriter: ((videoId: String, quality: AudioQuality, stream: ResolvedAudioStream, expiresAt: Long) -> Unit)? = null
    
        @Volatile

        private var persistentInvalidator: ((videoId: String) -> Unit)? = null

        private val prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(2)

        private val playbackRequestNumber = AtomicLong(0L)
    
        /**
         * YouTube video playback endpoints require a request-number parameter on each
         * videoplayback request. NewPipe's own YoutubeHttpDataSource adds this parameter.
         */

    private val inFlightResolutions = ConcurrentHashMap<String, CompletableFuture<ResolvedAudioStream>>()
    private val failedProviders = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val lastResolvedProvider = ConcurrentHashMap<String, String>()
    private const val PROVIDER_BACKOFF_MS = 2 * 60 * 1000L
    private val streamCache = LinkedHashMap<String, StreamCacheEntry>(96, 0.75f, true)

    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("googlevideo.com") || !uri.path.orEmpty().startsWith("/videoplayback") || uri.getQueryParameter("rn") != null) return url
        uri.buildUpon()
            .appendQueryParameter("rn", playbackRequestNumber.getAndIncrement().toString())
            .build()
            .toString()
    }.getOrDefault(url)

    fun attachPersistentStreamStore(
        reader: (videoId: String, quality: AudioQuality) -> ResolvedAudioStream?,
        writer: (videoId: String, quality: AudioQuality, stream: ResolvedAudioStream, expiresAt: Long) -> Unit,
        invalidator: (videoId: String) -> Unit
    ) {
        persistentReader = reader
        persistentWriter = writer
        persistentInvalidator = invalidator
    }

    fun warmUp() {
        prefetchExecutor.execute { runCatching { initializeExtractor(); NewPipe.getService("YouTube") } }
    }

    @Synchronized
    fun initializeExtractor() {
        if (extractorReady) return
        NewPipe.init(FluxDownloader())
        extractorReady = true
    }

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
        }
        runCatching { persistentInvalidator?.invoke(videoId) }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        videoIds.distinct().take(3).forEach { id ->
            val key = "$id:${quality.name}"
            val cached = synchronized(streamCache) { streamCache[key]?.expiresAt?.let { it > System.currentTimeMillis() } == true }
            if (!cached && !inFlightResolutions.containsKey(key)) {
                prefetchExecutor.execute { runCatching { resolveAudioStream(id, quality) } }
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
        require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) { "Invalid YouTube video ID" }
        val key = "$videoId:${quality.name}"
        val now = System.currentTimeMillis()
        if (forceRefresh) invalidateStream(videoId) else {
            synchronized(streamCache) {
                streamCache[key]?.let { entry ->
                    if (entry.expiresAt > now) return entry.stream
                    streamCache.remove(key)
                }
            }
            persistentReader?.invoke(videoId, quality)?.takeIf {
                it.url.startsWith("https://") && (it.expiresAt == 0L || it.expiresAt > now)
            }?.let { cached ->
                val expiry = cached.expiresAt.takeIf { it > now } ?: (now + 5 * 60 * 1000L)
                synchronized(streamCache) {
                    streamCache[key] = StreamCacheEntry(expiry, cached)
                }
                lastResolvedProvider[videoId] = cached.source
                return cached
            }
        }

        val flightKey = key + if (forceRefresh) ":refresh" else ""
        val mine = CompletableFuture<ResolvedAudioStream>()
        val existing = inFlightResolutions.putIfAbsent(flightKey, mine)
        if (existing != null) {
            return try { existing.get() }
            catch (e: Exception) { throw (e.cause as? Exception) ?: IOException("Stream resolution failed", e) }
        }

        try {
            val resolved = resolveWithFallbacks(videoId, quality)
            val expiresAt = cacheExpiry(resolved, System.currentTimeMillis())
            val cachedStream = resolved.copy(expiresAt = expiresAt)
            synchronized(streamCache) {
                streamCache[key] = StreamCacheEntry(expiresAt, cachedStream)
                while (streamCache.size > 100) streamCache.remove(streamCache.keys.firstOrNull())
            }
            lastResolvedProvider[videoId] = cachedStream.source
            runCatching { persistentWriter?.invoke(videoId, quality, cachedStream, expiresAt) }
            mine.complete(cachedStream)
            return cachedStream
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlightResolutions.remove(flightKey, mine)
        }
    }

    /**
     * Called after an actual Media3 playback failure. Penalize the provider that
     * supplied the failed URL so the next resolution moves to another source.
     */
    fun markPlaybackFailed(videoId: String) {
        val failed = synchronized(streamCache) {
            val entries = streamCache.filterKeys { it.startsWith("$videoId:") }.values.toList()
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
            entries.map { it.stream.source }.distinct()
        }.ifEmpty { listOfNotNull(lastResolvedProvider[videoId]) }
        val until = System.currentTimeMillis() + PROVIDER_BACKOFF_MS
        failedProviders.computeIfAbsent(videoId) { ConcurrentHashMap() }.also { map ->
            failed.forEach { source -> map[source] = until }
        }
        lastResolvedProvider.remove(videoId)
        runCatching { persistentInvalidator?.invoke(videoId) }
    }

    private fun providerAvailable(videoId: String, source: String): Boolean {
        val providers = failedProviders[videoId] ?: return true
        val until = providers[source] ?: return true
        if (until <= System.currentTimeMillis()) {
            providers.remove(source, until)
            if (providers.isEmpty()) failedProviders.remove(videoId, providers)
            return true
        }
        return false
    }

    private fun cacheExpiry(stream: ResolvedAudioStream, now: Long): Long {
        val upstreamExpiry = runCatching {
            Uri.parse(stream.url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L)
        }.getOrNull()
        val fallback = now + 8 * 60 * 1000L
        val safeExpiry = upstreamExpiry?.minus(60_000L) ?: fallback
        return minOf(safeExpiry, now + STREAM_CACHE_TTL_MS)
    }

    private fun resolveWithFallbacks(videoId: String, quality: AudioQuality): ResolvedAudioStream {
        if (providerAvailable(videoId, "newpipe")) {
            resolveViaNewPipe(videoId, quality)?.let { return it }
        }
        if (providerAvailable(videoId, "piped")) {
            resolveViaPiped(videoId, quality)?.let { return it }
        }
        if (providerAvailable(videoId, "invidious")) {
            resolveViaInvidious(videoId, quality)?.let { return it }
        }
        throw IOException("No audio stream is currently available from YouTube or fallback providers. Please retry.")
    }

    /** Extract direct media-file URLs. No HEAD/range probe is sent before actual playback. */
    private fun resolveViaNewPipe(videoId: String, quality: AudioQuality): ResolvedAudioStream? = try {
        initializeExtractor()
        val extractor = NewPipe.getService("YouTube")
            .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streams = extractor.audioStreams.filter { stream ->
            stream.isUrl && !stream.content.isNullOrBlank() &&
                !stream.content.contains(".m3u8", ignoreCase = true) &&
                !stream.content.startsWith("manifest", ignoreCase = true)
        }
        Log.i("FluxMusicResolver", "NewPipe audio stream candidates=${streams.size}")
        orderNewPipeCandidates(streams, quality).firstNotNullOfOrNull { candidate ->
            candidate.content?.takeIf { it.startsWith("https://") }?.let {
                ResolvedAudioStream(it, VISIONOS_USER_AGENT, "newpipe")
            }
        }
    } catch (e: Exception) {
        Log.w("FluxMusicResolver", "NewPipe extraction failed: ${e.message}", e)
        null
    }

    private fun resolveViaPiped(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        for (base in PIPED_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val audio = JSONObject(readStream(conn.inputStream)).optJSONArray("audioStreams") ?: return@runCatching null
                    val candidates = ArrayList<Pair<Int, String>>()
                    for (i in 0 until audio.length()) {
                        val item = audio.optJSONObject(i) ?: continue
                        if (item.optBoolean("videoOnly", false)) continue
                        val url = item.optString("url", "")
                        if (!url.startsWith("https://") || url.contains(".m3u8", true)) continue
                        val bitrate = item.optInt("bitrate", 0).let { if (it > 1000) it / 1000 else it }
                        candidates.add(bitrate.coerceAtLeast(1) to url)
                    }
                    orderRawCandidates(candidates, quality).firstOrNull()?.second?.let {
                        ResolvedAudioStream(it, USER_AGENT, "piped")
                    }
                } finally { conn.disconnect() }
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun resolveViaInvidious(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        for (base in INVIDIOUS_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/api/v1/videos/$videoId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val formats = JSONObject(readStream(conn.inputStream)).optJSONArray("adaptiveFormats") ?: return@runCatching null
                    val candidates = ArrayList<Pair<Int, String>>()
                    for (i in 0 until formats.length()) {
                        val item = formats.optJSONObject(i) ?: continue
                        if (!item.optString("type", "").startsWith("audio/", true)) continue
                        val url = item.optString("url", "")
                        if (!url.startsWith("https://") || url.contains(".m3u8", true)) continue
                        val raw = item.optString("bitrate", "0").toIntOrNull() ?: 0
                        candidates.add((if (raw > 1000) raw / 1000 else raw).coerceAtLeast(1) to url)
                    }
                    orderRawCandidates(candidates, quality).firstOrNull()?.second?.let {
                        ResolvedAudioStream(it, USER_AGENT, "invidious")
                    }
                } finally { conn.disconnect() }
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun orderNewPipeCandidates(streams: List<AudioStream>, quality: AudioQuality): List<AudioStream> {
        val sorted = streams.sortedByDescending { it.averageBitrate }
        return when (quality) {
            AudioQuality.HIGH -> sorted
            AudioQuality.DATA_SAVER -> {
                val low = sorted.filter { it.averageBitrate in 32..96 }.sortedBy { it.averageBitrate }
                (low + sorted.reversed()).distinct()
            }
            AudioQuality.AUTO -> {
                val balanced = sorted.filter { it.averageBitrate in 96..160 }
                val fallback = sorted.filter { it.averageBitrate in 64..192 }
                (balanced + fallback + sorted).distinct()
            }
        }
    }

    private fun orderRawCandidates(candidates: List<Pair<Int, String>>, quality: AudioQuality): List<Pair<Int, String>> {
        val sorted = candidates.sortedByDescending { it.first }
        return when (quality) {
            AudioQuality.HIGH -> sorted
            AudioQuality.DATA_SAVER -> {
                val low = sorted.filter { it.first in 32..96 }.sortedBy { it.first }
                (low + sorted.reversed()).distinctBy { it.second }
            }
            AudioQuality.AUTO -> {
                val balanced = sorted.filter { it.first in 96..160 }
                val fallback = sorted.filter { it.first in 64..192 }
                (balanced + fallback + sorted).distinctBy { it.second }
            }
        }
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
    }

    private fun readStream(input: InputStream?): String {
        if (input == null) return ""
        return input.use { src ->
            ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = src.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        }
    }

    private class FluxDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                val hasCustomUa = request.headers()?.keys?.any { it.equals("User-Agent", true) } == true
                if (!hasCustomUa) setRequestProperty("User-Agent", USER_AGENT)
                request.headers()?.forEach { (key, values) ->
                    values?.forEachIndexed { index, value ->
                        if (index == 0) setRequestProperty(key, value) else addRequestProperty(key, value)
                    }
                }
            }
            return try {
                request.dataToSend()?.let { bytes ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val input = if (status in 200..399) connection.inputStream else connection.errorStream
                val body = readStream(input)
                val headers = HashMap<String, MutableList<String>>()
                connection.headerFields.forEach { (name, values) ->
                    if (name != null && values != null) headers[name] = values.toMutableList()
                }
                Response(status, connection.responseMessage, headers, body, connection.url.toString())
            } finally {
                connection.disconnect()
            }
        }
    }
}
