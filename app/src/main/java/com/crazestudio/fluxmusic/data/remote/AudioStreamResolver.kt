package com.crazestudio.fluxmusic.data.remote

import android.content.Context
import android.net.Uri
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
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
    private const val VISIONOS_PLAYER_ENDPOINT = "https://youtubei.googleapis.com/youtubei/v1/player"
    private const val VISIONOS_VISITOR_ENDPOINT = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20261116.01.00"
    private const val VISIONOS_CLIENT_VERSION = "1.04"
    private const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; IN)"
    private const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val VISITOR_DATA_TTL_MS = 3 * 60 * 60 * 1000L
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
    private val secureRandom = SecureRandom()

    @Volatile private var cachedVisionOsVisitorData: String? = null
    @Volatile private var cachedVisionOsVisitorDataExpiry: Long = 0L

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
        var lastMessage = "YouTube did not return playable audio formats."

        // Prefer the current VISIONOS player client. Current extractor client tables
        // list it without a GVS PO-token policy and without a JavaScript player requirement.
        try {
            return fetchVisionOsPlayerStreams(videoId, quality)
        } catch (e: IOException) {
            lastMessage = e.message ?: lastMessage
        }

        // Keep the original clients as compatibility fallbacks in case YouTube changes
        // the VISIONOS response or restricts a specific video.
        val clients = listOf(
            Triple("WEB_REMIX", CLIENT_VERSION, USER_AGENT),
            Triple("ANDROID_MUSIC", "8.10.52", "com.google.android.apps.youtube.music/8.10.52 (Linux; U; Android 12; en_US) gzip")
        )
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
                postPlayer(body.toString(), agent, if (clientName == "WEB_REMIX") "67" else "21", version)
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
            val found = parseDirectAudioFormats(
                response = response,
                userAgent = agent,
                source = "YouTube Innertube ($clientName)",
                origin = "https://music.youtube.com",
                cpn = null
            )
            if (found.isNotEmpty()) return orderCandidates(found, quality)
            lastMessage = "YouTube returned no directly playable audio formats for $clientName."
        }
        throw IOException(lastMessage)
    }

    private fun fetchVisionOsPlayerStreams(
        videoId: String,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        val client = JSONObject()
            .put("clientName", "VISIONOS")
            .put("clientVersion", VISIONOS_CLIENT_VERSION)
            .put("clientScreen", "WATCH")
            .put("platform", "MOBILE")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice17,1")
            .put("osName", "visionOS")
            .put("osVersion", "26.6.0.23O770")
            .put("hl", "en")
            .put("gl", "IN")
            .put("utcOffsetMinutes", 0)

        val visitorData = getVisionOsVisitorData(client)
        val cpn = randomNonce(16)
        val playerClient = JSONObject(client.toString()).put("visitorData", visitorData)
        val payload = buildVisionOsRequestBody(playerClient)
            .put("videoId", videoId)
            .put("cpn", cpn)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val endpoint = "$VISIONOS_PLAYER_ENDPOINT?prettyPrint=false&t=${randomNonce(12)}&id=$videoId"
        val response = postVisionOs(endpoint, payload.toString())
        val status = response.optJSONObject("playabilityStatus")
        val state = status?.optString("status").orEmpty()
        if (state.isNotBlank() && state != "OK") {
            val reason = status?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: "YouTube VISIONOS playback is unavailable ($state)."
            throw IOException(reason)
        }

        val found = parseDirectAudioFormats(
            response = response,
            userAgent = VISIONOS_USER_AGENT,
            source = "YouTube Innertube (VISIONOS)",
            origin = "https://www.youtube.com",
            cpn = cpn
        )
        if (found.isEmpty()) {
            throw IOException("YouTube VISIONOS returned no direct audio formats for this track.")
        }
        return orderCandidates(found, quality)
    }

    internal fun buildVisionOsRequestBody(client: JSONObject): JSONObject {
        val request = JSONObject()
            .put("internalExperimentFlags", JSONArray())
            .put("useSsl", true)
        val user = JSONObject().put("lockedSafetyMode", false)
        val context = JSONObject()
            .put("client", client)
            .put("request", request)
            .put("user", user)
        return JSONObject().put("context", context)
    }

    private fun getVisionOsVisitorData(client: JSONObject): String {
        val now = System.currentTimeMillis()
        cachedVisionOsVisitorData?.takeIf { cachedVisionOsVisitorDataExpiry > now }?.let { return it }

        synchronized(this) {
            cachedVisionOsVisitorData?.takeIf { cachedVisionOsVisitorDataExpiry > System.currentTimeMillis() }
                ?.let { return it }

            val body = buildVisionOsRequestBody(JSONObject(client.toString()))
            val response = postVisionOs(VISIONOS_VISITOR_ENDPOINT, body.toString())
            val visitorData = response.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("YouTube did not issue VISIONOS visitor data.")
            cachedVisionOsVisitorData = visitorData
            cachedVisionOsVisitorDataExpiry = System.currentTimeMillis() + VISITOR_DATA_TTL_MS
            return visitorData
        }
    }

    private fun postVisionOs(endpoint: String, payload: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("User-Agent", VISIONOS_USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Goog-Api-Format-Version", "2")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("YouTube VISIONOS API HTTP $code: ${raw.take(180)}")
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDirectAudioFormats(
        response: JSONObject,
        userAgent: String,
        source: String,
        origin: String,
        cpn: String?
    ): List<ResolvedAudioStream> {
        val streaming = response.optJSONObject("streamingData") ?: return emptyList()
        val formats = streaming.optJSONArray("adaptiveFormats") ?: return emptyList()
        val found = ArrayList<ResolvedAudioStream>()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("audio/")) continue

            // VISIONOS currently returns direct URLs for supported audio tracks.
            // Never pass an unresolved signatureCipher field to Media3.
            val direct = format.optString("url").takeIf { it.startsWith("https://") } ?: continue
            val urlWithCpn = if (cpn == null || Uri.parse(direct).getQueryParameter("cpn") != null) {
                direct
            } else {
                direct + (if (direct.contains("?")) "&" else "?") + "cpn=$cpn"
            }
            found += ResolvedAudioStream(
                url = urlWithCpn,
                userAgent = userAgent,
                source = source,
                mimeType = mime.substringBefore(';'),
                bitrateKbps = format.optInt("averageBitrate", format.optInt("bitrate", 0)) / 1000,
                extraHeaders = mapOf(
                    "Origin" to origin,
                    "Referer" to "$origin/"
                )
            )
        }
        return found.distinctBy { it.url }
    }

    private fun randomNonce(length: Int): String = buildString(length) {
        repeat(length) { append(NONCE_ALPHABET[secureRandom.nextInt(NONCE_ALPHABET.length)]) }
    }

    private fun postPlayer(
        payload: String,
        userAgent: String,
        clientId: String,
        clientVersion: String
    ): JSONObject {
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
            setRequestProperty("X-YouTube-Client-Name", clientId)
            setRequestProperty("X-YouTube-Client-Version", clientVersion)
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
