package com.crazestudio.fluxmusic.data.remote

import android.content.Context
import android.net.Uri
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "YouTube Music native",
    val expiresAt: Long = 0L,
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * Native YouTube Innertube player resolver. No Python, yt-dlp, QuickJS,
 * subprocess, or third-party extraction runtime is used.
 *
 * YouTube may withhold direct URLs, require a PO token, or return ciphered
 * formats. Those cases are reported explicitly rather than pretending a
 * non-playable URL is usable.
 */
object AudioStreamResolver {
    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val STREAM_CACHE_TTL_MS = 4 * 60 * 1000L

    private const val PLAYER_ENDPOINT = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20261116.01.00"
    private const val MAX_CACHE_ENTRIES = 80
    private const val FAILED_URL_BACKOFF_MS = 60_000L

    private data class CacheEntry(val expiresAt: Long, val candidates: List<ResolvedAudioStream>)
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "FluxMusic-native-player").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, FutureTask<List<ResolvedAudioStream>>>()
    private val failedUrls = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val streamCache = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val requestNumber = AtomicLong(0L)

    fun initialize(context: Context) {
        // Native resolver has no runtime to unpack or initialize.
    }

    fun warmUp() {
        // Kept for compatibility with the service lifecycle.
    }

    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val googleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        if (!googleVideo || !uri.path.orEmpty().startsWith("/videoplayback")) return url
        val rn = requestNumber.incrementAndGet().toString()
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return "$url?rn=$rn"
        val base = url.substring(0, queryIndex)
        val query = url.substring(queryIndex + 1).split('&')
            .filter { it.isNotEmpty() && !it.substringBefore('=').equals("rn", true) }
        "$base?${(query + "rn=$rn").joinToString("&")}"
    }.getOrDefault(url)

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
        }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        videoIds.asSequence().filter { VIDEO_ID.matches(it) }.distinct().take(2).forEach { id ->
            val key = cacheKey(id, quality)
            val valid = synchronized(streamCache) { streamCache[key]?.expiresAt?.let { it > System.currentTimeMillis() } == true }
            if (!valid && !inFlight.containsKey(key)) executor.execute {
                runCatching { resolveCandidateStreams(id, quality) }
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
        val streams = resolveCandidateStreams(videoId, quality, forceRefresh)
        return streams.firstOrNull { !isUrlFailed(videoId, it.url) }
            ?: throw IOException("No playable YouTube audio URL is currently available.")
    }

    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(VIDEO_ID.matches(videoId)) { "Invalid YouTube video ID" }
        val key = cacheKey(videoId, quality)
        val now = System.currentTimeMillis()
        if (forceRefresh) invalidateStream(videoId) else synchronized(streamCache) {
            streamCache[key]?.let { entry ->
                if (entry.expiresAt > now) {
                    val usable = entry.candidates.filterNot { isUrlFailed(videoId, it.url) }
                    if (usable.isNotEmpty()) return usable
                }
                streamCache.remove(key)
            }
        }

        val task = FutureTask(Callable { fetchPlayerStreams(videoId, quality) })
        val active = inFlight.putIfAbsent(key, task) ?: task
        try {
            if (active === task) active.run()
            val streams = active.get()
            val resolvedAt = System.currentTimeMillis()
            val usable = streams.map { it.copy(expiresAt = calculateExpiry(it.url, resolvedAt)) }
                .filter { it.expiresAt > resolvedAt }
            if (usable.isEmpty()) throw IOException("YouTube returned expired or unusable audio URLs. Retry playback.")
            synchronized(streamCache) {
                streamCache[key] = CacheEntry(usable.minOf { it.expiresAt }, usable)
                while (streamCache.size > MAX_CACHE_ENTRIES) streamCache.keys.firstOrNull()?.let(streamCache::remove)
            }
            return usable.filterNot { isUrlFailed(videoId, it.url) }.ifEmpty { usable }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Native YouTube stream resolution was interrupted.", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is IOException) throw cause
            throw IOException("Native YouTube stream resolution failed: ${cause.message}", cause)
        } finally {
            inFlight.remove(key, active)
        }
    }

    private fun fetchPlayerStreams(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val clients = listOf(
            Triple("WEB_REMIX", CLIENT_VERSION, USER_AGENT),
            Triple("ANDROID_MUSIC", "8.10.52", "com.google.android.apps.youtube.music/8.10.52 (Linux; U; Android 12; en_US) gzip")
        )
        var lastMessage = "YouTube did not return playable audio formats."
        for ((clientName, version, agent) in clients) {
            val client = JSONObject()
                .put("clientName", clientName)
                .put("clientVersion", version)
                .put("hl", "en")
                .put("gl", "IN")
            if (clientName == "ANDROID_MUSIC") client.put("androidSdkVersion", 31)
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val response = try {
                postPlayer(body.toString(), agent)
            } catch (e: IOException) {
                lastMessage = e.message ?: "YouTube player request failed."
                continue
            }
            val status = response.optJSONObject("playabilityStatus")
            val state = status?.optString("status").orEmpty()
            if (state.isNotBlank() && state != "OK") {
                lastMessage = status?.optString("reason")?.takeIf { it.isNotBlank() }
                    ?: "YouTube playback is unavailable for this video ($state)."
                continue
            }

            val streaming = response.optJSONObject("streamingData")
            if (streaming == null) {
                lastMessage = "YouTube did not provide streaming data. It may require a PO token or sign-in."
                continue
            }
            val formats = streaming.optJSONArray("adaptiveFormats")
            val found = ArrayList<ResolvedAudioStream>()
            if (formats != null) for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType")
                if (!mime.startsWith("audio/")) continue
                // Only direct URLs are safe to hand to Media3. Ciphered URLs need
                // YouTube's changing JavaScript signature algorithm and are skipped.
                val direct = f.optString("url").takeIf { it.startsWith("https://") } ?: continue
                val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.getOrNull(1).orEmpty()
                found += ResolvedAudioStream(
                    url = direct,
                    userAgent = agent,
                    source = "YouTube Innertube ($clientName)",
                    mimeType = mime.substringBefore(';'),
                    bitrateKbps = (f.optInt("averageBitrate", f.optInt("bitrate", 0)) / 1000),
                    extraHeaders = mapOf(
                        "Origin" to "https://music.youtube.com",
                        "Referer" to "https://music.youtube.com/"
                    )
                )
            }
            if (found.isNotEmpty()) return orderCandidates(found.distinctBy { it.url }, quality)
            lastMessage = "YouTube returned only ciphered or token-protected formats; no direct audio URL was exposed."
        }
        throw IOException(lastMessage)
    }

    private fun postPlayer(payload: String, userAgent: String): JSONObject {
        val connection = (URL(PLAYER_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("YouTube player API HTTP $code: ${raw.take(180)}")
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    fun orderCandidates(candidates: List<ResolvedAudioStream>, quality: AudioQuality): List<ResolvedAudioStream> {
        val sorted = candidates.sortedWith(
            compareByDescending<ResolvedAudioStream> { it.bitrateKbps }
                .thenBy { if (it.mimeType.contains("mp4", true)) 0 else 1 }
        )
        return when (quality) {
            AudioQuality.DATA_SAVER -> sorted.filter { it.bitrateKbps in 32..96 } + sorted.filterNot { it.bitrateKbps in 32..96 }
            AudioQuality.AUTO -> {
                val preferred = sorted.filter { it.bitrateKbps in 96..160 }
                (preferred + sorted).distinctBy { it.url }
            }
            AudioQuality.HIGH -> sorted
        }
    }

    fun markCandidateSucceeded(videoId: String, quality: AudioQuality, stream: ResolvedAudioStream) {
        synchronized(streamCache) {
            val key = cacheKey(videoId, quality)
            streamCache[key]?.let { entry ->
                streamCache[key] = entry.copy(candidates = listOf(stream) + entry.candidates.filterNot { it.url == stream.url })
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
                if (remaining.isEmpty()) streamCache.remove(key) else streamCache[key] = entry.copy(candidates = remaining)
            }
        }
    }

    fun markPlaybackFailed(videoId: String) = invalidateStream(videoId)
    fun clearFailuresFor(videoId: String) { failedUrls.remove(videoId); invalidateStream(videoId) }
    fun clearStreamCache() { synchronized(streamCache) { streamCache.clear() }; failedUrls.clear() }

    private fun isUrlFailed(videoId: String, url: String): Boolean {
        val map = failedUrls[videoId] ?: return false
        val until = map[url] ?: return false
        if (until <= System.currentTimeMillis()) { map.remove(url, until); return false }
        return true
    }

    internal fun calculateExpiry(url: String, now: Long): Long {
        val upstream = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000L) }.getOrNull()
        return minOf(upstream?.minus(45_000L) ?: (now + STREAM_CACHE_TTL_MS), now + STREAM_CACHE_TTL_MS)
    }

    private fun cacheKey(id: String, quality: AudioQuality) = "$id:${quality.name}"
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")
}
