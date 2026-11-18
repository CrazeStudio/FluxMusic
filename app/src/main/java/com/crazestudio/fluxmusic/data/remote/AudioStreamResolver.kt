package com.crazestudio.fluxmusic.data.remote

import android.net.Uri
import android.util.Log
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "youtube",
    val expiresAt: Long = 0L,
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * Owns multi-client stream extraction, candidate rotation, source fallback,
 * short-lived URL caching and prefetch.
 * No catalogue/UI code is allowed to perform stream resolution.
 */
object AudioStreamResolver {
    private const val TAG = "FluxMusicResolver"

    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip"

    const val STREAM_CACHE_TAG = "|flux-resolver-v7"
    const val STREAM_CACHE_TTL_MS = 30 * 60 * 1000L

    private data class CandidateListEntry(
        val expiresAt: Long,
        val candidates: List<ResolvedAudioStream>
    )

    // Robust Piped instances known for stable unauthenticated JSON stream extraction
    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.adminforge.de",
        "https://api.piped.yt",
        "https://pipedapi.drgns.space"
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

    private val inFlightResolutions = ConcurrentHashMap<String, CompletableFuture<List<ResolvedAudioStream>>>()
    private val failedProviders = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val failedUrls = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val lastResolvedProvider = ConcurrentHashMap<String, String>()
    private const val PROVIDER_BACKOFF_MS = 60 * 1000L
    private const val URL_BACKOFF_MS = 3 * 60 * 1000L
    private val streamCache = LinkedHashMap<String, CandidateListEntry>(96, 0.75f, true)

    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val isGoogleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        if (!isGoogleVideo || !uri.path.orEmpty().startsWith("/videoplayback")) return url

        val rnValue = playbackRequestNumber.incrementAndGet().toString()
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return "$url?rn=$rnValue"

        val base = url.substring(0, qIndex)
        val rawQuery = url.substring(qIndex + 1)
        val preserved = rawQuery.split('&').filter { part ->
            part.isNotEmpty() && !part.substringBefore('=').equals("rn", ignoreCase = true)
        }
        if (preserved.isEmpty()) {
            "$base?rn=$rnValue"
        } else {
            "$base?${preserved.joinToString("&")}&rn=$rnValue"
        }
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
            val cached = synchronized(streamCache) {
                streamCache[key]?.let { it.expiresAt > System.currentTimeMillis() && it.candidates.isNotEmpty() } == true
            }
            if (!cached && !inFlightResolutions.containsKey(key)) {
                prefetchExecutor.execute { runCatching { resolveCandidateStreams(id, quality) } }
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
            ?: throw IOException("No audio stream is currently available for this track.")
    }

    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) { "Invalid YouTube video ID" }
        val key = "$videoId:${quality.name}"
        val now = System.currentTimeMillis()

        if (forceRefresh) {
            invalidateStream(videoId)
        } else {
            synchronized(streamCache) {
                streamCache[key]?.let { entry ->
                    if (entry.expiresAt > now) {
                        val valid = entry.candidates.filter { !isUrlFailed(videoId, it.url) }
                        if (valid.isNotEmpty()) return valid
                    }
                    streamCache.remove(key)
                }
            }
            persistentReader?.invoke(videoId, quality)?.takeIf {
                it.url.startsWith("https://") &&
                    (it.expiresAt == 0L || it.expiresAt > now) &&
                    !isUrlFailed(videoId, it.url)
            }?.let { cached ->
                val expiry = cached.expiresAt.takeIf { it > now } ?: (now + 5 * 60 * 1000L)
                val list = listOf(cached)
                synchronized(streamCache) {
                    streamCache[key] = CandidateListEntry(expiry, list)
                }
                lastResolvedProvider[videoId] = cached.source
                return list
            }
        }

        val flightKey = key + if (forceRefresh) ":refresh" else ""
        val mine = CompletableFuture<List<ResolvedAudioStream>>()
        val existing = inFlightResolutions.putIfAbsent(flightKey, mine)
        if (existing != null) {
            return try {
                existing.get()
            } catch (e: Exception) {
                throw (e.cause as? Exception) ?: IOException("Stream resolution failed", e)
            }
        }

        try {
            val resolvedList = resolveWithFallbacks(videoId, quality)
            val earliestExpiry = resolvedList.minOfOrNull { cacheExpiry(it, System.currentTimeMillis()) }
                ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
            val stamped = resolvedList.map { it.copy(expiresAt = cacheExpiry(it, System.currentTimeMillis())) }
            synchronized(streamCache) {
                streamCache[key] = CandidateListEntry(earliestExpiry, stamped)
                while (streamCache.size > 100) streamCache.remove(streamCache.keys.firstOrNull())
            }
            stamped.firstOrNull()?.let { primary ->
                lastResolvedProvider[videoId] = primary.source
                runCatching { persistentWriter?.invoke(videoId, quality, primary, primary.expiresAt) }
            }
            mine.complete(stamped)
            return stamped
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlightResolutions.remove(flightKey, mine)
        }
    }

    fun markCandidateSucceeded(
        videoId: String,
        quality: AudioQuality,
        stream: ResolvedAudioStream
    ) {
        lastResolvedProvider[videoId] = stream.source
        synchronized(streamCache) {
            streamCache.entries.forEach { (key, entry) ->
                if (key.startsWith("$videoId:")) {
                    val reordered = listOf(stream) + entry.candidates.filterNot { it.url == stream.url }
                    streamCache[key] = entry.copy(candidates = reordered)
                }
            }
        }
        runCatching {
            val expiry = stream.expiresAt.takeIf { it > System.currentTimeMillis() }
                ?: cacheExpiry(stream, System.currentTimeMillis())
            persistentWriter?.invoke(videoId, quality, stream, expiry)
        }
    }

    fun clearFailuresFor(videoId: String) {
        failedProviders.remove(videoId)
        failedUrls.remove(videoId)
        lastResolvedProvider.remove(videoId)
        invalidateStream(videoId)
    }

    fun markCandidateFailed(videoId: String, stream: ResolvedAudioStream) {
        val now = System.currentTimeMillis()
        failedUrls.computeIfAbsent(videoId) { ConcurrentHashMap() }[stream.url] = now + URL_BACKOFF_MS
        var remainingInProvider = false
        synchronized(streamCache) {
            val toRemove = ArrayList<String>()
            streamCache.forEach { (key, entry) ->
                if (key.startsWith("$videoId:")) {
                    val remaining = entry.candidates.filterNot { it.url == stream.url || isUrlFailed(videoId, it.url) }
                    if (remaining.any { it.source == stream.source }) {
                        remainingInProvider = true
                    }
                    if (remaining.isEmpty()) {
                        toRemove.add(key)
                    } else {
                        streamCache[key] = entry.copy(candidates = remaining)
                    }
                }
            }
            toRemove.forEach(streamCache::remove)
        }
        if (!remainingInProvider) {
            failedProviders.computeIfAbsent(videoId) { ConcurrentHashMap() }[stream.source] = now + PROVIDER_BACKOFF_MS
        }
        runCatching { persistentInvalidator?.invoke(videoId) }
    }

    fun markPlaybackFailed(videoId: String) {
        val failedSources = synchronized(streamCache) {
            val entries = streamCache.filterKeys { it.startsWith("$videoId:") }.values.toList()
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
            entries.flatMap { it.candidates.map { c -> c.source } }.distinct()
        }.ifEmpty { listOfNotNull(lastResolvedProvider[videoId]) }

        val until = System.currentTimeMillis() + PROVIDER_BACKOFF_MS
        val providerMap = failedProviders.computeIfAbsent(videoId) { ConcurrentHashMap() }
        failedSources.forEach { source -> providerMap[source] = until }
        lastResolvedProvider.remove(videoId)
        runCatching { persistentInvalidator?.invoke(videoId) }
    }

    private fun isUrlFailed(videoId: String, url: String): Boolean {
        val map = failedUrls[videoId] ?: return false
        val until = map[url] ?: return false
        if (until <= System.currentTimeMillis()) {
            map.remove(url, until)
            return false
        }
        return true
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
            val qIndex = stream.url.indexOf('?')
            if (qIndex < 0) return@runCatching null
            val rawQuery = stream.url.substring(qIndex + 1)
            for (part in rawQuery.split('&')) {
                val eq = part.indexOf('=')
                if (eq >= 0 && part.substring(0, eq).equals("expire", ignoreCase = true)) {
                    return@runCatching part.substring(eq + 1).toLongOrNull()?.times(1000L)
                }
            }
            null
        }.getOrNull()
        val fallback = now + 8 * 60 * 1000L
        val safeExpiry = upstreamExpiry?.minus(60_000L) ?: fallback
        return minOf(safeExpiry, now + STREAM_CACHE_TTL_MS)
    }

    private fun resolveWithFallbacks(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val collected = ArrayList<ResolvedAudioStream>()

        fun collectFrom(source: String, block: () -> List<ResolvedAudioStream>) {
            if (!providerAvailable(videoId, source)) return
            val list = block().filter { !isUrlFailed(videoId, it.url) }
            if (list.isNotEmpty()) {
                collected.addAll(list)
            }
        }

        // 1. Piped API instances (unauthenticated robust JSON stream extraction)
        collectFrom("piped") { resolveViaPiped(videoId, quality) }
        if (collected.isNotEmpty()) return collected.distinctBy { it.url }

        // 2. NewPipeExtractor multi-client extraction
        collectFrom("newpipe") { resolveViaNewPipe(videoId, quality) }
        if (collected.isNotEmpty()) return collected.distinctBy { it.url }

        // If every provider is in backoff, clear and retry Piped + NewPipe once
        val hadBackoff = failedProviders.remove(videoId) != null
        failedUrls.remove(videoId)
        if (hadBackoff) {
            val recovery = (resolveViaPiped(videoId, quality) + resolveViaNewPipe(videoId, quality))
                .distinctBy { it.url }
            if (recovery.isNotEmpty()) return recovery
        }

        throw IOException("No audio stream is currently available from YouTube or fallback providers. Please retry.")
    }

    private fun resolveViaPiped(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        for (base in PIPED_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching emptyList()
                    val audio = JSONObject(readStream(decodeInputStream(conn))).optJSONArray("audioStreams")
                        ?: return@runCatching emptyList()
                    val candidates = ArrayList<ResolvedAudioStream>()
                    for (i in 0 until audio.length()) {
                        val item = audio.optJSONObject(i) ?: continue
                        if (item.optBoolean("videoOnly", false)) continue
                        val url = item.optString("url", "")
                        if (!url.startsWith("https://") || url.contains(".m3u8", true)) continue
                        val rawBitrate = item.optInt("bitrate", 0)
                        val kbps = (if (rawBitrate > 1000) rawBitrate / 1000 else rawBitrate).coerceAtLeast(1)
                        val mime = item.optString("mimeType", "")
                        candidates.add(
                            ResolvedAudioStream(
                                url = url,
                                userAgent = USER_AGENT,
                                source = "piped",
                                mimeType = mime,
                                bitrateKbps = kbps
                            )
                        )
                    }
                    orderCandidates(candidates, quality)
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(emptyList())
            if (resolved.isNotEmpty()) return resolved
        }
        return emptyList()
    }

    private fun resolveViaNewPipe(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> = try {
        initializeExtractor()
        val extractor = NewPipe.getService("YouTube")
            .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streams = extractor.audioStreams.filter { stream ->
            stream.isUrl && !stream.content.isNullOrBlank() &&
                stream.content.startsWith("https://") &&
                !stream.content.contains(".m3u8", ignoreCase = true) &&
                !stream.content.startsWith("manifest", ignoreCase = true)
        }
        Log.i(TAG, "NewPipe audio stream candidates=${streams.size}")
        val mapped = streams.mapNotNull { candidate ->
            val url = candidate.content?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            ResolvedAudioStream(
                url = url,
                userAgent = USER_AGENT,
                source = "newpipe",
                mimeType = candidate.format?.mimeType.orEmpty(),
                bitrateKbps = candidate.averageBitrate.coerceAtLeast(1)
            )
        }
        orderCandidates(mapped, quality)
    } catch (e: Exception) {
        Log.w(TAG, "NewPipe extraction failed: ${e.message}", e)
        emptyList()
    }

    internal fun orderCandidates(
        candidates: List<ResolvedAudioStream>,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<ResolvedAudioStream> { it.bitrateKbps }
                .thenByDescending { it.mimeType.contains("mp4", ignoreCase = true) }
        )
        val ordered = when (quality) {
            AudioQuality.HIGH -> sorted
            AudioQuality.DATA_SAVER -> {
                val low = sorted.filter { it.bitrateKbps in 32..96 }.sortedBy { it.bitrateKbps }
                low + sorted.reversed()
            }
            AudioQuality.AUTO -> {
                val balanced = sorted.filter { it.bitrateKbps in 96..160 }
                val fallback = sorted.filter { it.bitrateKbps in 64..192 }
                balanced + fallback + sorted
            }
        }
        return ordered.distinctBy { it.url }
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
        failedProviders.clear()
        failedUrls.clear()
    }

    private fun decodeInputStream(conn: HttpURLConnection, isError: Boolean = false): InputStream? {
        val raw = if (!isError) conn.inputStream else conn.errorStream
        if (raw == null) return null
        return if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw)
        } else {
            raw
        }
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
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                val hasCustomUa = request.headers()?.keys?.any { it.equals("User-Agent", true) } == true
                if (!hasCustomUa) setRequestProperty("User-Agent", USER_AGENT)
                request.headers()?.forEach { (key, values) ->
                    if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                        values?.forEachIndexed { index, value ->
                            if (index == 0) setRequestProperty(key, value) else addRequestProperty(key, value)
                        }
                    }
                }
            }
            return try {
                request.dataToSend()?.let { bytes ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val input = decodeInputStream(connection, isError = status !in 200..399)
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
