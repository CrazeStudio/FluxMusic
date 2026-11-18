package com.crazestudio.fluxmusic.data.remote

import android.net.Uri
import android.util.Log
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONArray
import org.json.JSONObject
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
 * Native YouTube InnerTube stream resolver.
 *
 * This class owns session discovery, player requests, direct audio-format parsing,
 * short-lived URL caching and retry state. Catalogue/search remains in YtMusicCatalog.
 * No external extractor service or third-party proxy is used.
 */
object AudioStreamResolver {
    private const val TAG = "FluxMusicResolver"

    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip"

    // Retained only so older repository builds remain source-compatible. Signed stream
    // URLs are deliberately no longer written to Room or DataStore.
    const val STREAM_CACHE_TAG = "|flux-native-innertube-v1"
    const val STREAM_CACHE_TTL_MS = 120_000L

    private const val WATCH_URL = "https://www.youtube.com/watch?v="
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    private const val DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11Gjw"
    private const val MAX_WATCH_PAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_PLAYER_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val URL_BACKOFF_MS = 90_000L
    private const val CLIENT_BACKOFF_MS = 60_000L

    private data class WatchSession(
        val apiKey: String = DEFAULT_API_KEY,
        val visitorData: String = "",
        val cookieHeader: String = "",
        val signatureTimestamp: String = "",
        val playerJsUrl: String = ""
    )

    internal data class ClientProfile(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val client: JSONObject,
        val headers: Map<String, String> = emptyMap()
    )

    private data class CacheEntry(
        val candidates: List<ResolvedAudioStream>,
        val expiresAt: Long
    )

    private val streamCache = LinkedHashMap<String, CacheEntry>(48, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<List<ResolvedAudioStream>>>()
    private val failedUrls = ConcurrentHashMap<String, Long>()
    private val failedClients = ConcurrentHashMap<String, Long>()
    private val lastClient = ConcurrentHashMap<String, String>()
    private val requestCounter = AtomicLong(0L)
    private val worker: ExecutorService = Executors.newFixedThreadPool(2)

    /**
     * Compatibility no-op. Persisting googlevideo URLs causes stale/IP-bound URLs to
     * survive process restarts, so the new resolver intentionally ignores Room stores.
     */
    @Deprecated("Signed YouTube URLs are now kept in memory only")
    fun attachPersistentStreamStore(
        reader: (videoId: String, quality: AudioQuality) -> ResolvedAudioStream?,
        writer: (videoId: String, quality: AudioQuality, stream: ResolvedAudioStream, expiresAt: Long) -> Unit,
        invalidator: (videoId: String) -> Unit
    ) = Unit

    /** No extractor singleton needs initialization in the native implementation. */
    fun initializeExtractor() = Unit

    /** No network request is started until a track is actually resolved. */
    fun warmUp() = Unit

    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val googleVideo = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        if (!googleVideo || !uri.path.orEmpty().startsWith("/videoplayback")) return url

        val nextRn = requestCounter.incrementAndGet().toString()
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return "$url?rn=$nextRn"
        val base = url.substring(0, queryAt)
        val kept = url.substring(queryAt + 1).split('&').filter { part ->
            part.isNotEmpty() && !part.substringBefore('=').equals("rn", ignoreCase = true)
        }
        if (kept.isEmpty()) "$base?rn=$nextRn" else "$base?${kept.joinToString("&")}&rn=$nextRn"
    }.getOrDefault(url)

    fun resolveIdentityForUrl(url: String, fallbackUa: String = USER_AGENT): Pair<String, Map<String, String>> {
        val client = queryParameter(url, "c").orEmpty().uppercase()
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

    private fun queryParameter(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key.equals(name, ignoreCase = true)) part.substringAfter('=', "") else null
        }
    }

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$videoId:") }.toList().forEach(streamCache::remove)
        }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        videoIds.distinct().take(3).forEach { id ->
            if (!hasFreshCache(id, quality) && !inFlight.containsKey(cacheKey(id, quality))) {
                worker.execute { runCatching { resolveCandidateStreams(id, quality) } }
            }
        }
    }

    fun isResolving(videoId: String, quality: AudioQuality): Boolean =
        inFlight.containsKey(cacheKey(videoId, quality))

    fun resolveAudioUrl(videoId: String, quality: AudioQuality = AudioQuality.AUTO): String =
        resolveAudioStream(videoId, quality).url

    fun resolveAudioStream(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): ResolvedAudioStream {
        return resolveCandidateStreams(videoId, quality, forceRefresh)
            .firstOrNull { !isUrlFailed(videoId, it.url) }
            ?: throw IOException("YouTube did not provide a playable direct audio stream for this track.")
    }

    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) { "Invalid YouTube video ID" }
        val key = cacheKey(videoId, quality)
        if (forceRefresh) invalidateStream(videoId) else {
            synchronized(streamCache) {
                streamCache[key]?.let { cached ->
                    if (cached.expiresAt > System.currentTimeMillis()) {
                        val usable = cached.candidates.filterNot { isUrlFailed(videoId, it.url) }
                        if (usable.isNotEmpty()) return usable
                    }
                    streamCache.remove(key)
                }
            }
        }

        val mine = CompletableFuture<List<ResolvedAudioStream>>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            return try {
                existing.get()
            } catch (error: Exception) {
                throw (error.cause as? Exception) ?: IOException("InnerTube resolution failed", error)
            }
        }

        try {
            val resolved = resolveUsingInnerTube(videoId, quality)
            if (resolved.isEmpty()) {
                throw IOException(
                    "YouTube returned no direct audio URL for this track. It may be region-restricted, " +
                        "unavailable, or only expose a signature-protected format."
                )
            }
            val now = System.currentTimeMillis()
            val stamped = resolved.map { stream ->
                val expiry = streamExpiry(stream.url, now)
                stream.copy(expiresAt = expiry)
            }
            val cacheUntil = stamped.minOfOrNull { it.expiresAt } ?: (now + STREAM_CACHE_TTL_MS)
            synchronized(streamCache) {
                streamCache[key] = CacheEntry(stamped, cacheUntil)
                while (streamCache.size > 80) {
                    streamCache.remove(streamCache.keys.firstOrNull())
                }
            }
            stamped.firstOrNull()?.let { lastClient[videoId] = it.source }
            mine.complete(stamped)
            return stamped
        } catch (error: Exception) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun cacheKey(videoId: String, quality: AudioQuality) = "$videoId:${quality.name}"

    private fun hasFreshCache(videoId: String, quality: AudioQuality): Boolean {
        synchronized(streamCache) {
            return streamCache[cacheKey(videoId, quality)]?.let {
                it.expiresAt > System.currentTimeMillis() && it.candidates.any { candidate ->
                    !isUrlFailed(videoId, candidate.url)
                }
            } == true
        }
    }

    private fun resolveUsingInnerTube(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val session = fetchWatchSession(videoId)
        val profiles = buildClientProfiles()
        val failures = ArrayList<String>()

        for (profile in profiles) {
            val failedUntil = failedClients[profile.name] ?: 0L
            if (failedUntil > System.currentTimeMillis()) continue
            val candidates = runCatching {
                requestPlayer(videoId, quality, profile, session)
            }.onFailure {
                Log.w(TAG, "${profile.name} player request failed: ${it.message}")
            }.getOrDefault(emptyList())

            if (candidates.isNotEmpty()) {
                failedClients.remove(profile.name)
                Log.i(TAG, "Resolved ${candidates.size} direct audio candidates using ${profile.name}")
                return orderCandidates(candidates, quality)
            }
            failedClients[profile.name] = System.currentTimeMillis() + CLIENT_BACKOFF_MS
            failures.add(profile.name)
        }

        Log.w(TAG, "No direct audio format for $videoId; clients attempted=${failures.joinToString()}")
        return emptyList()
    }

    private fun fetchWatchSession(videoId: String): WatchSession {
        val connection = (URL(WATCH_URL + videoId + "&hl=en&gl=IN").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            setRequestProperty("Cache-Control", "no-cache")
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "Watch page returned HTTP $status; continuing with the public InnerTube key")
                return WatchSession()
            }
            val html = readText(connection.inputStream, MAX_WATCH_PAGE_BYTES)
            val cookies = connection.headerFields.entries
                .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                ?.value.orEmpty()
                .mapNotNull { cookie ->
                    cookie.substringBefore(';').takeIf { it.contains('=') && !it.startsWith("=") }
                }
                .distinct()
                .joinToString("; ")

            val apiKey = extractFirst(
                html,
                Regex("""["']?INNERTUBE_API_KEY["']?\s*:\s*["']([^"']+)["']""")
            ) ?: DEFAULT_API_KEY
            val visitorData = extractFirst(
                html,
                Regex(""""visitorData"\s*:\s*"([^"]+)"""")
            ).orEmpty()
            val signatureTimestamp = extractFirst(
                html,
                Regex(""""signatureTimestamp"\s*:\s*(\d+)""")
            ) ?: extractFirst(html, Regex(""""STS"\s*:\s*(\d+)""")).orEmpty()
            val playerJs = extractFirst(
                html,
                Regex(""""jsUrl"\s*:\s*"([^"]+base\.js[^"]*)"""")
            )?.replace("\\/", "/")?.let { raw ->
                if (raw.startsWith("http")) raw else "https://www.youtube.com$raw"
            }.orEmpty()

            WatchSession(apiKey, visitorData, cookies, signatureTimestamp, playerJs)
        } catch (error: Exception) {
            Log.w(TAG, "Watch session discovery failed: ${error.message}")
            WatchSession()
        } finally {
            connection.disconnect()
        }
    }

    private fun buildClientProfiles(): List<ClientProfile> {
        val androidVr = JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", "1.60.19")
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("osName", "Android")
            .put("osVersion", "12L")
            .put("androidSdkVersion", 32)
            .put("hl", "en")
            .put("gl", "IN")
        val ios = JSONObject()
            .put("clientName", "IOS")
            .put("clientVersion", "20.10.4")
            .put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone")
            .put("osVersion", "18.3.2.22D82")
            .put("hl", "en")
            .put("gl", "IN")
        val android = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "19.45.38")
            .put("androidSdkVersion", 34)
            .put("hl", "en")
            .put("gl", "IN")
        val web = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", "2.20251118.01.00")
            .put("hl", "en")
            .put("gl", "IN")
        val tv = JSONObject()
            .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
            .put("clientVersion", "2.0")
            .put("hl", "en")
            .put("gl", "IN")

        return listOf(
            ClientProfile(
                "ANDROID_VR", "1.60.19", "28", ANDROID_VR_USER_AGENT, androidVr
            ),
            ClientProfile(
                "IOS", "20.10.4", "5", IOS_USER_AGENT, ios
            ),
            ClientProfile(
                "ANDROID", "19.45.38", "3", ANDROID_USER_AGENT, android
            ),
            ClientProfile(
                "WEB", "2.20251118.01.00", "1", USER_AGENT, web,
                mapOf("Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
            ),
            ClientProfile(
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85", USER_AGENT, tv,
                mapOf("Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
            )
        )
    }

    private fun requestPlayer(
        videoId: String,
        quality: AudioQuality,
        profile: ClientProfile,
        session: WatchSession
    ): List<ResolvedAudioStream> {
        val client = JSONObject(profile.client.toString())
        if (session.visitorData.isNotBlank()) client.put("visitorData", session.visitorData)
        val context = JSONObject().put("client", client)
        val body = JSONObject()
            .put("videoId", videoId)
            .put("context", context)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject()
                        .put("html5Preference", "HTML5_PREF_WANTS")
                        .apply {
                            session.signatureTimestamp.toIntOrNull()?.let { put("signatureTimestamp", it) }
                        }
                )
            )

        val endpoint = PLAYER_ENDPOINT + "?key=" + session.apiKey + "&prettyPrint=false"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 9_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", profile.userAgent)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-YouTube-Client-Name", profile.clientId)
            setRequestProperty("X-YouTube-Client-Version", profile.version)
            setRequestProperty("Origin", profile.headers["Origin"] ?: "https://www.youtube.com")
            setRequestProperty("Referer", profile.headers["Referer"] ?: "https://www.youtube.com/")
            if (session.visitorData.isNotBlank()) {
                setRequestProperty("X-Goog-Visitor-Id", session.visitorData)
            }
            if (session.cookieHeader.isNotBlank()) setRequestProperty("Cookie", session.cookieHeader)
        }

        return try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = readText(responseStream, MAX_PLAYER_RESPONSE_BYTES)
            if (status !in 200..299) {
                throw IOException("${profile.name} InnerTube returned HTTP $status")
            }
            val root = JSONObject(responseText)
            val playability = root.optJSONObject("playabilityStatus")
            val playabilityStatus = playability?.optString("status", "").orEmpty()
            if (playabilityStatus.isNotBlank() && !playabilityStatus.equals("OK", ignoreCase = true)) {
                val reason = playability.optString("reason", "")
                throw IOException("${profile.name} playback status $playabilityStatus: $reason".trim())
            }
            parseAudioCandidates(root, profile, quality)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseAudioCandidates(
        root: JSONObject,
        profile: ClientProfile,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        val muxed = streamingData.optJSONArray("formats") ?: JSONArray()
        val formats = ArrayList<JSONObject>(adaptive.length() + muxed.length())
        for (index in 0 until adaptive.length()) adaptive.optJSONObject(index)?.let(formats::add)
        for (index in 0 until muxed.length()) muxed.optJSONObject(index)?.let(formats::add)

        val parsed = formats.mapNotNull { item ->
            val mime = item.optString("mimeType", "")
            if (!mime.startsWith("audio/", ignoreCase = true)) return@mapNotNull null
            val url = item.optString("url", "")
            // A cipher-only response is not a playable URL. Never pass its encoded
            // signatureCipher string to ExoPlayer as if it were a media resource.
            if (!url.startsWith("https://", ignoreCase = true)) return@mapNotNull null
            if (url.contains(".m3u8", ignoreCase = true)) return@mapNotNull null
            val parsedUri = runCatching { Uri.parse(url) }.getOrNull() ?: return@mapNotNull null
            if (parsedUri.host.isNullOrBlank()) return@mapNotNull null

            val rawBitrate = item.optInt("averageBitrate", item.optInt("bitrate", 0))
            val bitrate = (if (rawBitrate > 10_000) rawBitrate / 1000 else rawBitrate).coerceAtLeast(1)
            val (ua, identityHeaders) = resolveIdentityForUrl(url, profile.userAgent)
            val headers = LinkedHashMap<String, String>().apply {
                putAll(profile.headers)
                putAll(identityHeaders)
            }
            ResolvedAudioStream(
                url = url,
                userAgent = ua,
                source = profile.name,
                mimeType = mime.substringBefore(';').trim(),
                bitrateKbps = bitrate,
                extraHeaders = headers
            )
        }
        val defaults = parsed.filter { stream ->
            val source = formats.firstOrNull { it.optString("url", "") == stream.url }
            val track = source?.optJSONObject("audioTrack")
            track == null || !track.has("audioIsDefault") || track.optBoolean("audioIsDefault", true)
        }
        return orderCandidates(if (defaults.isNotEmpty()) defaults else parsed, quality)
    }

    fun markCandidateSucceeded(videoId: String, quality: AudioQuality, stream: ResolvedAudioStream) {
        lastClient[videoId] = stream.source
        synchronized(streamCache) {
            val key = cacheKey(videoId, quality)
            streamCache[key]?.let { entry ->
                val promoted = listOf(stream) + entry.candidates.filterNot { it.url == stream.url }
                streamCache[key] = entry.copy(candidates = promoted)
            }
        }
    }

    fun markCandidateFailed(videoId: String, stream: ResolvedAudioStream) {
        failedUrls["$videoId|${stream.url}"] = System.currentTimeMillis() + URL_BACKOFF_MS
        invalidateStream(videoId)
        lastClient[videoId]?.takeIf { it == stream.source }?.let {
            failedClients[it] = System.currentTimeMillis() + CLIENT_BACKOFF_MS
            lastClient.remove(videoId)
        }
    }

    fun markPlaybackFailed(videoId: String) {
        lastClient.remove(videoId)?.let { client ->
            failedClients[client] = System.currentTimeMillis() + CLIENT_BACKOFF_MS
        }
        invalidateStream(videoId)
    }

    fun clearFailuresFor(videoId: String) {
        failedUrls.keys.filter { it.startsWith("$videoId|") }.forEach(failedUrls::remove)
        failedClients.clear()
        lastClient.remove(videoId)
        invalidateStream(videoId)
    }

    private fun isUrlFailed(videoId: String, url: String): Boolean {
        val key = "$videoId|$url"
        val until = failedUrls[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedUrls.remove(key, until)
            return false
        }
        return true
    }

    private fun streamExpiry(url: String, now: Long): Long {
        val upstream = queryParameter(url, "expire")?.toLongOrNull()?.times(1000L)
        val fallback = now + STREAM_CACHE_TTL_MS
        val safe = upstream?.minus(45_000L) ?: fallback
        return minOf(safe, fallback)
    }

    internal fun orderCandidates(
        candidates: List<ResolvedAudioStream>,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        if (candidates.isEmpty()) return emptyList()
        val target = when (quality) {
            AudioQuality.DATA_SAVER -> 64
            AudioQuality.AUTO -> 128
            AudioQuality.HIGH -> Int.MAX_VALUE
        }
        return candidates.distinctBy { it.url }.sortedWith(
            compareBy<ResolvedAudioStream> {
                if (quality == AudioQuality.HIGH) -it.bitrateKbps
                else kotlin.math.abs(it.bitrateKbps - target)
            }.thenByDescending { it.mimeType.contains("mp4", ignoreCase = true) }
                .thenByDescending { it.bitrateKbps }
        )
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
        failedUrls.clear()
        failedClients.clear()
        lastClient.clear()
    }

    private fun extractFirst(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)

    private fun readText(input: InputStream?, limit: Int): String {
        if (input == null) return ""
        return input.use { source ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw IOException("YouTube response exceeded the size limit")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
    }
}
