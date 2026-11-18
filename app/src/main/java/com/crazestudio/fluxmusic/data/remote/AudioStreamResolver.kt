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
    const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; GB)"
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.45.38 (Linux; U; Android 14) gzip"

    const val STREAM_CACHE_TAG = "|flux-resolver-v7"
    const val STREAM_CACHE_TTL_MS = 3 * 60 * 1000L

    private const val INNERTUBE_PLAYER_ENDPOINT =
        "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"

    private data class CandidateListEntry(
        val expiresAt: Long,
        val candidates: List<ResolvedAudioStream>
    )

    private val PIPED_INSTANCES = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.nosebs.ru",
        "https://piped-api.privacy.com.de",
        "https://pipedapi.drgns.space"
    )

    // Public Invidious instances; local=true proxy endpoints avoid IP-locked googlevideo 403s.
    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.tiekoetter.com",
        "https://yt.chocolatemoo53.com"
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
    private const val PROVIDER_BACKOFF_MS = 90 * 1000L
    private const val URL_BACKOFF_MS = 5 * 60 * 1000L
    private val streamCache = LinkedHashMap<String, CandidateListEntry>(96, 0.75f, true)

    /**
     * YouTube videoplayback URLs need a fresh request number (rn) on each open.
     * Operate directly on raw encoded query parameters so signed tokens (+, %2B, %3D, %2C)
     * are never altered by Uri.Builder decoding/re-encoding.
     */
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

    fun resolveIdentityForUrl(url: String, fallbackUa: String = USER_AGENT): Pair<String, Map<String, String>> {
        val client = extractQueryParam(url, "c")?.uppercase().orEmpty()
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

    private fun extractQueryParam(url: String, targetName: String): String? {
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return null
        val rawQuery = url.substring(qIndex + 1)
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (key.equals(targetName, ignoreCase = true)) {
                return if (eq >= 0) part.substring(eq + 1) else ""
            }
        }
        return null
    }

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
                val (ua, headers) = resolveIdentityForUrl(cached.url, cached.userAgent)
                val normalized = cached.copy(
                    userAgent = ua,
                    extraHeaders = if (cached.extraHeaders.isNotEmpty()) cached.extraHeaders else headers
                )
                val list = listOf(normalized)
                synchronized(streamCache) {
                    streamCache[key] = CandidateListEntry(expiry, list)
                }
                lastResolvedProvider[videoId] = normalized.source
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

    /**
     * Promotes a stream that actually returned audio bytes to the front of the cache
     * so seeking or re-buffering immediately reuses the verified stream.
     */
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

    /**
     * Records failure for a single candidate URL without immediately banning the whole provider
     * while other candidate streams from the same provider remain untested.
     */
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

    /**
     * Called after an ExoPlayer-level failure (e.g. decoder/parser failure or exhausted retries).
     * Penalizes the last active provider so the next retry escalates to another extractor/provider.
     */
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
            extractQueryParam(stream.url, "expire")?.toLongOrNull()?.times(1000L)
        }.getOrNull()
        // Match the previous Flutter implementation's three-minute manifest cache.
        val fallback = now + 3 * 60 * 1000L
        val safeExpiry = upstreamExpiry?.minus(60_000L) ?: fallback
        return minOf(safeExpiry, now + STREAM_CACHE_TTL_MS)
    }

    private fun resolveWithFallbacks(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        // Restore the lightweight native extraction chain used before yt-dlp was introduced.
        // Each provider is attempted in order; failures are isolated so one dead endpoint
        // cannot prevent the remaining extractors from being tried.
        val providers: List<Pair<String, () -> List<ResolvedAudioStream>>> = listOf(
            // Primary path mirrors the old Flutter YoutubeExplode call:
            // fetch the YouTube watch page, parse its player response and
            // collect audio-only formats before trying alternate providers.
            "newpipe" to { resolveViaNewPipe(videoId, quality) },
            "innertube_vr" to { resolveViaInnerTubeVr(videoId, quality) },
            "innertube_ios" to { resolveViaInnerTubeIos(videoId, quality) },
            "innertube_android" to { resolveViaInnerTubeAndroid(videoId, quality) },
            "innertube_tv" to { resolveViaInnerTubeTvEmbedded(videoId, quality) },
            "piped" to { resolveViaPiped(videoId, quality) },
            "invidious" to { resolveViaInvidious(videoId, quality) }
        )

        for ((source, resolver) in providers) {
            if (!providerAvailable(videoId, source)) continue
            val candidates = runCatching { resolver() }
                .onFailure { Log.w(TAG, "$source extraction failed for $videoId: ${it.message}") }
                .getOrDefault(emptyList())
            if (candidates.isNotEmpty()) {
                Log.i(TAG, "Resolved ${candidates.size} stream candidates via $source for $videoId")
                return orderCandidates(candidates, quality)
            }
            failedProviders.computeIfAbsent(videoId) { ConcurrentHashMap() }[source] =
                System.currentTimeMillis() + PROVIDER_BACKOFF_MS
        }
        throw IOException("No audio stream is currently available. All extraction providers failed.")
    }

    private fun resolveViaInnerTubeVr(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val client = JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", "1.60.19")
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("osName", "Android")
            .put("osVersion", "12L")
            .put("androidSdkVersion", 32)
            .put("hl", "en")
            .put("gl", "US")
            .put("utcOffsetMinutes", 0)
        return fetchInnerTubePlayerStreams(
            videoId = videoId,
            quality = quality,
            source = "innertube_vr",
            clientJson = client,
            userAgent = ANDROID_VR_USER_AGENT,
            clientIdHeader = "28",
            clientVersionHeader = "1.60.19"
        )
    }

    private fun resolveViaInnerTubeIos(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val client = JSONObject()
            .put("clientName", "IOS")
            .put("clientVersion", "20.10.4")
            .put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone")
            .put("osVersion", "18.3.2.22D82")
            .put("hl", "en")
            .put("gl", "US")
            .put("utcOffsetMinutes", 0)
        return fetchInnerTubePlayerStreams(
            videoId = videoId,
            quality = quality,
            source = "innertube_ios",
            clientJson = client,
            userAgent = IOS_USER_AGENT,
            clientIdHeader = "5",
            clientVersionHeader = "20.10.4"
        )
    }

    private fun resolveViaInnerTubeAndroid(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val client = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "19.45.38")
            .put("androidSdkVersion", 34)
            .put("hl", "en")
            .put("gl", "US")
            .put("utcOffsetMinutes", 0)
        return fetchInnerTubePlayerStreams(
            videoId = videoId,
            quality = quality,
            source = "innertube_android",
            clientJson = client,
            userAgent = ANDROID_USER_AGENT,
            clientIdHeader = "3",
            clientVersionHeader = "19.45.38"
        )
    }

    private fun resolveViaInnerTubeTvEmbedded(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        val client = JSONObject()
            .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
            .put("clientVersion", "2.0")
            .put("hl", "en")
            .put("gl", "US")
        val thirdParty = JSONObject().put("embedUrl", "https://www.youtube.com/")
        return fetchInnerTubePlayerStreams(
            videoId = videoId,
            quality = quality,
            source = "innertube_tv",
            clientJson = client,
            userAgent = USER_AGENT,
            clientIdHeader = "85",
            clientVersionHeader = "2.0",
            thirdPartyJson = thirdParty,
            extraPlaybackHeaders = mapOf(
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
        )
    }

    private fun fetchInnerTubePlayerStreams(
        videoId: String,
        quality: AudioQuality,
        source: String,
        clientJson: JSONObject,
        userAgent: String,
        clientIdHeader: String,
        clientVersionHeader: String,
        thirdPartyJson: JSONObject? = null,
        extraPlaybackHeaders: Map<String, String> = emptyMap()
    ): List<ResolvedAudioStream> = runCatching {
        val contextObj = JSONObject().put("client", clientJson)
        if (thirdPartyJson != null) {
            contextObj.put("thirdParty", thirdPartyJson)
        }
        val body = JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", contextObj)

        val conn = (URL(INNERTUBE_PLAYER_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-YouTube-Client-Name", clientIdHeader)
            setRequestProperty("X-YouTube-Client-Version", clientVersionHeader)
            if (extraPlaybackHeaders.containsKey("Origin")) {
                setRequestProperty("Origin", extraPlaybackHeaders["Origin"])
            }
        }
        try {
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            if (conn.responseCode !in 200..299) return@runCatching emptyList()
            val root = JSONObject(readStream(decodeInputStream(conn)))
            val status = root.optJSONObject("playabilityStatus")?.optString("status", "").orEmpty()
            if (status.isNotEmpty() && !status.equals("OK", ignoreCase = true)) {
                Log.w(TAG, "$source playability=$status for $videoId")
                return@runCatching emptyList()
            }
            val adaptive = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: return@runCatching emptyList()
            val candidates = ArrayList<ResolvedAudioStream>()
            for (i in 0 until adaptive.length()) {
                val item = adaptive.optJSONObject(i) ?: continue
                val mime = item.optString("mimeType", "")
                if (!mime.startsWith("audio/", ignoreCase = true)) continue
                val audioTrack = item.optJSONObject("audioTrack")
                if (audioTrack != null && audioTrack.has("audioIsDefault") && !audioTrack.optBoolean("audioIsDefault", true)) {
                    // Skip non-default dubbed tracks when multiple audio tracks are present.
                    continue
                }
                val url = item.optString("url", "")
                if (!url.startsWith("https://") || url.contains(".m3u8", ignoreCase = true)) continue
                val rawBitrate = item.optInt("averageBitrate", item.optInt("bitrate", 0))
                val kbps = (if (rawBitrate > 1000) rawBitrate / 1000 else rawBitrate).coerceAtLeast(1)
                val (resolvedUa, urlHeaders) = resolveIdentityForUrl(url, userAgent)
                candidates.add(
                    ResolvedAudioStream(
                        url = url,
                        userAgent = resolvedUa,
                        source = source,
                        mimeType = mime,
                        bitrateKbps = kbps,
                        extraHeaders = if (urlHeaders.isNotEmpty()) urlHeaders else extraPlaybackHeaders
                    )
                )
            }
            orderCandidates(candidates, quality)
        } finally {
            conn.disconnect()
        }
    }.onFailure { err ->
        Log.w(TAG, "$source failed for $videoId: ${err.message}")
    }.getOrDefault(emptyList())

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
            val (ua, headers) = resolveIdentityForUrl(url, VISIONOS_USER_AGENT)
            ResolvedAudioStream(
                url = url,
                userAgent = ua,
                source = "newpipe",
                mimeType = candidate.format?.mimeType.orEmpty(),
                bitrateKbps = candidate.averageBitrate.coerceAtLeast(1),
                extraHeaders = headers
            )
        }
        orderCandidates(mapped, quality)
    } catch (e: Exception) {
        Log.w(TAG, "NewPipe extraction failed: ${e.message}", e)
        emptyList()
    }

    private fun resolveViaPiped(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        for (base in PIPED_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 3500
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
                        val (ua, headers) = resolveIdentityForUrl(url, USER_AGENT)
                        candidates.add(
                            ResolvedAudioStream(
                                url = url,
                                userAgent = ua,
                                source = "piped",
                                mimeType = mime,
                                bitrateKbps = kbps,
                                extraHeaders = headers
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

    private fun resolveViaInvidious(videoId: String, quality: AudioQuality): List<ResolvedAudioStream> {
        for (base in INVIDIOUS_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/api/v1/videos/$videoId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 3500
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching emptyList()
                    val formats = JSONObject(readStream(decodeInputStream(conn))).optJSONArray("adaptiveFormats")
                        ?: return@runCatching emptyList()
                    val candidates = ArrayList<ResolvedAudioStream>()
                    for (i in 0 until formats.length()) {
                        val item = formats.optJSONObject(i) ?: continue
                        val mime = item.optString("type", "")
                        if (!mime.startsWith("audio/", true)) continue
                        val itag = item.optString("itag", "")
                        val rawBitrate = item.optString("bitrate", "0").toIntOrNull() ?: 0
                        val kbps = (if (rawBitrate > 1000) rawBitrate / 1000 else rawBitrate).coerceAtLeast(1)
                        // Prefer Invidious proxied stream endpoint (?local=true) because direct googlevideo URLs
                        // are often IP-bound to the Invidious server and return 403 to the device.
                        if (itag.isNotBlank()) {
                            candidates.add(
                                ResolvedAudioStream(
                                    url = "$base/latest_version?id=$videoId&itag=$itag&local=true",
                                    userAgent = USER_AGENT,
                                    source = "invidious",
                                    mimeType = mime,
                                    bitrateKbps = kbps
                                )
                            )
                        }
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

    internal fun orderCandidates(
        candidates: List<ResolvedAudioStream>,
        quality: AudioQuality
    ): List<ResolvedAudioStream> {
        if (candidates.isEmpty()) return emptyList()
        // Match the previous Flutter app's format selection:
        // highest bitrate for High; closest to 96 kbps or 128 kbps otherwise.
        // Prefer AAC/MP4 when two formats have the same score.
        val targetBitrate = when (quality) {
            AudioQuality.DATA_SAVER -> 96
            AudioQuality.AUTO -> 128
            AudioQuality.HIGH -> Int.MAX_VALUE
        }
        return candidates
            .distinctBy { it.url }
            .sortedWith(
                compareBy<ResolvedAudioStream> {
                    if (quality == AudioQuality.HIGH) -it.bitrateKbps
                    else kotlin.math.abs(it.bitrateKbps - targetBitrate)
                }.thenByDescending { it.mimeType.contains("mp4", ignoreCase = true) }
                    .thenByDescending { it.bitrateKbps }
            )
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
                connectTimeout = 12000
                readTimeout = 25000
                instanceFollowRedirects = true
                val hasCustomUa = request.headers()?.keys?.any { it.equals("User-Agent", true) } == true
                if (!hasCustomUa) setRequestProperty("User-Agent", USER_AGENT)
                request.headers()?.forEach { (key, values) ->
                    // Let HttpURLConnection handle Accept-Encoding transparently unless we decode gzip ourselves.
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
