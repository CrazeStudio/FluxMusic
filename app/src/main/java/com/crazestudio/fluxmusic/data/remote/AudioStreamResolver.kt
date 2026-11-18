package com.crazestudio.fluxmusic.data.remote

import android.net.Uri
import android.util.Log
import com.crazestudio.fluxmusic.data.model.AudioQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/** Native YouTube backend: session discovery, InnerTube, format selection and in-memory URL health. */
object AudioStreamResolver {
    private const val TAG = "FluxMusicExtractor"
    const val USER_AGENT = YtMusicCatalog.USER_AGENT
    const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip"

    const val STREAM_CACHE_TAG = "|flux-native-backend-v2"
    const val STREAM_CACHE_TTL_MS = 120_000L

    private const val WATCH_URL = "https://www.youtube.com/watch?v="
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    private const val DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11Gjw"
    private const val DEFAULT_WEB_VERSION = "2.20261118.01.00"
    private const val WATCH_LIMIT = 5 * 1024 * 1024
    private const val PLAYER_LIMIT = 3 * 1024 * 1024
    private const val SCRIPT_LIMIT = 5 * 1024 * 1024
    private const val URL_BACKOFF_MS = 90_000L
    private const val CLIENT_BACKOFF_MS = 45_000L
    private const val SESSION_TTL_MS = 5 * 60 * 1000L
    private const val SCRIPT_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 64

    private data class WatchSession(
        val apiKey: String,
        val visitorData: String,
        val cookieHeader: String,
        val signatureTimestamp: Int?,
        val playerJsUrl: String,
        val fetchedAt: Long
    )

    internal data class ClientProfile(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val client: JSONObject,
        val headers: Map<String, String> = emptyMap()
    )

    private data class CacheEntry(val candidates: List<ResolvedAudioStream>, val expiresAt: Long)
    private data class ScriptEntry(val source: String, val fetchedAt: Long)

    private val streamCache = LinkedHashMap<String, CacheEntry>(32, 0.75f, true)
    private val failedUrls = ConcurrentHashMap<String, Long>()
    private val failedClients = ConcurrentHashMap<String, Long>()
    private val lastClient = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<List<ResolvedAudioStream>>>()
    private val worker: ExecutorService = Executors.newFixedThreadPool(2)
    private val requestCounter = AtomicLong(0L)

    @Volatile private var watchSessionCache: WatchSession? = null
    @Volatile private var scriptCache: ScriptEntry? = null

    /** Compatibility no-op: signed URLs must never be persisted across process restarts. */
    @Deprecated("Playback URLs are held in memory only")
    fun attachPersistentStreamStore(
        reader: (videoId: String, quality: AudioQuality) -> ResolvedAudioStream?,
        writer: (videoId: String, quality: AudioQuality, stream: ResolvedAudioStream, expiresAt: Long) -> Unit,
        invalidator: (videoId: String) -> Unit
    ) = Unit

    fun initializeExtractor() = Unit
    fun warmUp() = Unit

    fun prepareYoutubePlaybackUrl(url: String): String = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        if (!(host == "googlevideo.com" || host.endsWith(".googlevideo.com")) ||
            !uri.path.orEmpty().startsWith("/videoplayback")
        ) return url

        val nextRn = requestCounter.incrementAndGet().toString()
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return "$url?rn=$nextRn"
        val base = url.substring(0, queryAt)
        val kept = url.substring(queryAt + 1).split('&').filter { part ->
            part.isNotEmpty() && !part.substringBefore('=').equals("rn", ignoreCase = true)
        }
        if (kept.isEmpty()) "$base?rn=$nextRn"
        else base + "?" + kept.joinToString("&") + "&rn=" + nextRn
    }.getOrDefault(url)

    fun resolveIdentityForUrl(
        url: String,
        fallbackUa: String = USER_AGENT
    ): Pair<String, Map<String, String>> {
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

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith(videoId + ":") }.toList()
                .forEach(streamCache::remove)
        }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        videoIds.asSequence().filter { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
            .distinct().take(2).forEach { videoId ->
                val key = cacheKey(videoId, quality)
                if (!hasFreshCache(videoId, quality) && !inFlight.containsKey(key)) {
                    runCatching {
                        worker.execute { runCatching { resolveCandidateStreams(videoId, quality) } }
                    }
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
    ): ResolvedAudioStream =
        resolveCandidateStreams(videoId, quality, forceRefresh)
            .firstOrNull { !isUrlFailed(videoId, it.url) }
            ?: throw IOException("YouTube did not return a usable audio stream for this track.")

    fun resolveCandidateStreams(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): List<ResolvedAudioStream> {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) { "Invalid YouTube video ID" }
        val key = cacheKey(videoId, quality)
        if (forceRefresh) invalidateStream(videoId) else {
            synchronized(streamCache) {
                streamCache[key]?.let { entry ->
                    if (entry.expiresAt > System.currentTimeMillis()) {
                        val usable = entry.candidates.filterNot { isUrlFailed(videoId, it.url) }
                        if (usable.isNotEmpty()) return usable
                    }
                    streamCache.remove(key)
                }
            }
        }

        val task = FutureTask<List<ResolvedAudioStream>> {
            resolveWithClients(videoId, quality)
        }
        val pending = inFlight.putIfAbsent(key, task)
        val active = pending ?: task

        try {
            if (pending == null) task.run()
            val streams = try {
                active.get(120, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is Exception) throw cause
                throw IOException("Stream extraction failed", error)
            } catch (error: TimeoutException) {
                throw IOException("Timed out while extracting this track", error)
            }.filterNot { isUrlFailed(videoId, it.url) }

            if (streams.isEmpty()) {
                throw IOException(
                    "YouTube returned no usable direct audio formats. The track may be unavailable, " +
                        "region-restricted, age-restricted, or require a player token this client cannot obtain."
                )
            }
            val now = System.currentTimeMillis()
            val stamped = streams.map { stream ->
                stream.copy(expiresAt = streamExpiry(stream.url, now))
            }
            val expiry = stamped.minOfOrNull { it.expiresAt } ?: (now + STREAM_CACHE_TTL_MS)
            synchronized(streamCache) {
                streamCache[key] = CacheEntry(stamped, expiry)
                while (streamCache.size > MAX_CACHE_ENTRIES) {
                    streamCache.remove(streamCache.keys.firstOrNull() ?: break)
                }
            }
            stamped.firstOrNull()?.let { lastClient[videoId] = it.source }
            return stamped
        } finally {
            if (pending == null) inFlight.remove(key, task)
        }
    }

    private fun resolveWithClients(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val session = discoverWatchSession(videoId)
        val profiles = buildClientProfiles()
        val attempted = mutableListOf<String>()
        val script = session.playerJsUrl.takeIf { it.isNotBlank() }?.let(::loadPlayerScript).orEmpty()

        for (profile in profiles) {
            val failureKey = videoId + "|" + profile.name
            if ((failedClients[failureKey] ?: 0L) > System.currentTimeMillis()) continue
            if (profile.name == "VISIONOS" && session.visitorData.isBlank()) {
                attempted += "VISIONOS (visitor data unavailable)"
                continue
            }
            val candidates = runCatching {
                requestPlayer(videoId, quality, profile, session, script)
            }.onFailure {
                Log.w(TAG, profile.name + " player request failed: " + it.message)
            }.getOrDefault(emptyList()).filterNot { isUrlFailed(videoId, it.url) }

            if (candidates.isNotEmpty()) {
                failedClients.remove(failureKey)
                Log.i(TAG, "Resolved " + candidates.size + " stream candidates via " + profile.name)
                return orderCandidates(candidates, quality)
            }
            failedClients[failureKey] = System.currentTimeMillis() + CLIENT_BACKOFF_MS
            attempted += profile.name
        }
        Log.w(TAG, "No audio formats for " + videoId + "; clients=" + attempted.joinToString())
        return emptyList()
    }

    private fun discoverWatchSession(videoId: String): WatchSession {
        val now = System.currentTimeMillis()
        watchSessionCache?.let { cached ->
            if (now - cached.fetchedAt < SESSION_TTL_MS) return cached
        }
        val found = runCatching {
            val response = HttpTransport.get(
                WATCH_URL + videoId + "&hl=en&gl=IN",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-IN,en;q=0.9",
                    "Cache-Control" to "no-cache"
                ),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 9_000,
                maxBytes = WATCH_LIMIT
            ).requireSuccess("YouTube watch page")
            val html = response.body
            val cookies = response.headers.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value.orEmpty()
                .mapNotNull { it.substringBefore(';').takeIf { part -> part.contains('=') } }
                .distinct().joinToString("; ")
            val apiKey = extract(html, Regex("""["']?INNERTUBE_API_KEY["']?\s*:\s*["']([^"']+)["']"""))
                ?: DEFAULT_API_KEY
            val visitor = extract(html, Regex(""""visitorData"\s*:\s*"([^"]+)""""))
                .orEmpty().replace("\\u003d", "=")
            val timestamp = extract(html, Regex(""""signatureTimestamp"\s*:\s*(\d+)"""))
                ?: extract(html, Regex(""""STS"\s*:\s*(\d+)"""))
            val playerJs = extract(html, Regex(""""jsUrl"\s*:\s*"([^"]+base\.js[^"]*)""""))
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.let { if (it.startsWith("http")) it else "https://www.youtube.com" + it }
                .orEmpty()
            WatchSession(apiKey, visitor, cookies, timestamp?.toIntOrNull(), playerJs, now)
        }.getOrElse { error ->
            Log.w(TAG, "Could not read YouTube watch session: " + error.message)
            WatchSession(DEFAULT_API_KEY, "", "", null, "", now)
        }
        watchSessionCache = found
        return found
    }

    private fun loadPlayerScript(url: String): String {
        val now = System.currentTimeMillis()
        scriptCache?.let { cached ->
            if (cached.source.isNotBlank() && now - cached.fetchedAt < SCRIPT_TTL_MS) {
                return cached.source
            }
        }
        val source = runCatching {
            HttpTransport.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.youtube.com/"
                ),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 10_000,
                maxBytes = SCRIPT_LIMIT
            ).requireSuccess("YouTube player script").body
        }.onFailure {
            Log.w(TAG, "Player script unavailable: " + it.message)
        }.getOrDefault("")
        if (source.isNotBlank()) scriptCache = ScriptEntry(source, now)
        return source
    }

    private fun buildClientProfiles(): List<ClientProfile> {
        val web = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", DEFAULT_WEB_VERSION)
            .put("hl", "en")
            .put("gl", "IN")
        val android = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "19.45.38")
            .put("androidSdkVersion", 34)
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
        val vision = JSONObject()
            .put("clientName", "VISIONOS")
            .put("clientVersion", "1.02")
            .put("deviceMake", "Apple")
            .put("deviceModel", "RealityDevice17,1")
            .put("osName", "visionOS")
            .put("osVersion", "26.5.23O471")
            .put("hl", "en")
            .put("gl", "US")
        val tv = JSONObject()
            .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
            .put("clientVersion", "2.0")
            .put("hl", "en")
            .put("gl", "IN")

        val webHeaders = mapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
        )
        return listOf(
            ClientProfile("ANDROID", "19.45.38", "3", ANDROID_USER_AGENT, android),
            ClientProfile("IOS", "20.10.4", "5", IOS_USER_AGENT, ios),
            ClientProfile("ANDROID_VR", "1.60.19", "28", ANDROID_VR_USER_AGENT, androidVr),
            ClientProfile("WEB", DEFAULT_WEB_VERSION, "1", USER_AGENT, web, webHeaders),
            ClientProfile("VISIONOS", "1.02", "101", VISIONOS_USER_AGENT, vision, webHeaders),
            ClientProfile("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85", USER_AGENT, tv, webHeaders)
        )
    }

    private fun requestPlayer(
        videoId: String,
        quality: AudioQuality,
        profile: ClientProfile,
        session: WatchSession,
        playerScript: String
    ): List<ResolvedAudioStream> {
        val client = JSONObject(profile.client.toString())
        if (session.visitorData.isNotBlank()) client.put("visitorData", session.visitorData)
        val context = JSONObject().put("client", client)
        if (profile.name.startsWith("TVHTML5")) {
            context.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
        }
        val playbackContent = JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
        session.signatureTimestamp?.let { playbackContent.put("signatureTimestamp", it) }
        val request = JSONObject()
            .put("videoId", videoId)
            .put("context", context)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", playbackContent))

        val headers = linkedMapOf(
            "User-Agent" to profile.userAgent,
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to profile.version,
            "Origin" to (profile.headers["Origin"] ?: "https://www.youtube.com"),
            "Referer" to (profile.headers["Referer"] ?: "https://www.youtube.com/")
        )
        headers.putAll(profile.headers)
        if (session.visitorData.isNotBlank()) headers["X-Goog-Visitor-Id"] = session.visitorData
        if (session.cookieHeader.isNotBlank()) headers["Cookie"] = session.cookieHeader

        val endpoint = PLAYER_ENDPOINT + "?key=" + session.apiKey + "&prettyPrint=false"
        val response = HttpTransport.postJson(
            endpoint, request.toString(), headers = headers,
            connectTimeoutMs = 6_000, readTimeoutMs = 10_000, maxBytes = PLAYER_LIMIT
        ).requireSuccess(profile.name + " InnerTube player")
        val root = JSONObject(response.body)
        val status = root.optJSONObject("playabilityStatus")
        val playability = status?.optString("status", "").orEmpty()
        if (playability.isNotBlank() && !playability.equals("OK", ignoreCase = true)) {
            val reason = status?.optString("reason", "").orEmpty()
            throw IOException("$playability: $reason".trim())
        }
        return parseAudioCandidates(root, profile, quality, playerScript)
    }

    internal fun parseAudioCandidates(
        root: JSONObject,
        profile: ClientProfile,
        quality: AudioQuality,
        playerScript: String = ""
    ): List<ResolvedAudioStream> {
        val streaming = root.optJSONObject("streamingData") ?: return emptyList()
        val formats = ArrayList<JSONObject>()
        listOf("adaptiveFormats", "formats").forEach { name ->
            val array = streaming.optJSONArray(name) ?: JSONArray()
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(formats::add)
        }

        val parsed = formats.mapNotNull { format ->
            val mime = format.optString("mimeType", "")
            if (!mime.startsWith("audio/", ignoreCase = true)) return@mapNotNull null
            var url = format.optString("url", "")
            if (url.isBlank()) {
                val cipher = format.optString("signatureCipher", format.optString("cipher", ""))
                if (cipher.isBlank()) return@mapNotNull null
                url = decodeCipherUrl(cipher, playerScript) ?: return@mapNotNull null
            }
            if (!url.startsWith("https://", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true)
            ) return@mapNotNull null
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host.orEmpty().lowercase()
            if (!(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) {
                return@mapNotNull null
            }

            val rawBitrate = format.optInt("averageBitrate", format.optInt("bitrate", 0))
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

        return orderCandidates(parsed, quality)
    }

    private fun decodeCipherUrl(cipher: String, playerScript: String): String? {
        val values = parseFormEncoded(cipher)
        val rawUrl = values["url"]?.takeIf { it.isNotBlank() } ?: return null
        val encrypted = values["s"].orEmpty()
        if (encrypted.isBlank()) return rawUrl
        if (playerScript.isBlank()) return null
        val signature = PlayerScriptDecipher.decipher(encrypted, playerScript) ?: return null
        val parameter = values["sp"]?.takeIf { it.isNotBlank() } ?: "signature"
        return runCatching {
            Uri.parse(rawUrl).buildUpon().appendQueryParameter(parameter, signature).build().toString()
        }.getOrNull()
    }

    private fun parseFormEncoded(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = decodeFormComponent(part.substringBefore('='))
            val value = decodeFormComponent(part.substringAfter('=', ""))
            if (key.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun decodeFormComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)

    fun markCandidateSucceeded(videoId: String, quality: AudioQuality, stream: ResolvedAudioStream) {
        lastClient[videoId] = stream.source
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
        failedUrls[videoId + "|" + stream.url] = System.currentTimeMillis() + URL_BACKOFF_MS
        invalidateStream(videoId)
    }

    fun markPlaybackFailed(videoId: String) {
        lastClient.remove(videoId)?.let { profile ->
            failedClients[videoId + "|" + profile] = System.currentTimeMillis() + CLIENT_BACKOFF_MS
        }
        invalidateStream(videoId)
        watchSessionCache = null
    }

    fun clearFailuresFor(videoId: String) {
        failedUrls.keys.filter { it.startsWith(videoId + "|") }.forEach(failedUrls::remove)
        failedClients.keys.filter { it.startsWith(videoId + "|") }.forEach(failedClients::remove)
        lastClient.remove(videoId)
        invalidateStream(videoId)
    }

    private fun hasFreshCache(videoId: String, quality: AudioQuality): Boolean {
        synchronized(streamCache) {
            val entry = streamCache[cacheKey(videoId, quality)] ?: return false
            return entry.expiresAt > System.currentTimeMillis() &&
                entry.candidates.any { !isUrlFailed(videoId, it.url) }
        }
    }

    private fun isUrlFailed(videoId: String, url: String): Boolean {
        val key = videoId + "|" + url
        val until = failedUrls[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedUrls.remove(key, until)
            return false
        }
        return true
    }

    private fun cacheKey(videoId: String, quality: AudioQuality): String = videoId + ":" + quality.name

    private fun streamExpiry(url: String, now: Long): Long {
        val upstream = queryParameter(url, "expire")?.toLongOrNull()?.times(1000L)
        val fallback = now + STREAM_CACHE_TTL_MS
        val safe = upstream?.minus(45_000L) ?: fallback
        return minOf(safe, fallback)
    }

    private fun queryParameter(url: String, name: String): String? =
        runCatching { Uri.parse(url).getQueryParameter(name) }.getOrNull()

    private fun extract(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)

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
        watchSessionCache = null
        scriptCache = null
    }
}
