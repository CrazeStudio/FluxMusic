package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.Track
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "youtube"
)

object YtMusicCatalog {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TECNO KI5k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val SEARCH_ENDPOINT =
        "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val PLAYER_ENDPOINT =
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20261116.01.00"
    private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
    const val STREAM_CACHE_TTL_MS = 3 * 60 * 60 * 1000L

    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.leptons.xyz",
        "https://api.piped.yt"
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
    private val inFlightResolutions = ConcurrentHashMap<String, CompletableFuture<ResolvedAudioStream>>()

    private data class SearchCacheEntry(
        val time: Long = System.currentTimeMillis(),
        val tracks: List<Track>
    )

    private data class StreamCacheEntry(
        val time: Long = System.currentTimeMillis(),
        val stream: ResolvedAudioStream
    )

    private val searchCache = LinkedHashMap<String, SearchCacheEntry>(32, 0.75f, true)
    private val streamCache = LinkedHashMap<String, StreamCacheEntry>(96, 0.75f, true)

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
        prefetchExecutor.execute {
            runCatching {
                initializeExtractor()
                NewPipe.getService("YouTube")
            }
        }
    }

    fun invalidateStream(videoId: String) {
        synchronized(streamCache) {
            val prefix = "$videoId:"
            val keys = streamCache.keys.filter { it.startsWith(prefix) }
            keys.forEach { streamCache.remove(it) }
        }
        runCatching { persistentInvalidator?.invoke(videoId) }
    }

    fun prefetchAudioUrls(videoIds: List<String>, quality: AudioQuality = AudioQuality.AUTO) {
        if (videoIds.isEmpty()) return
        videoIds.distinct().take(3).forEach { id ->
            val cacheKey = "$id:${quality.name}"
            val alreadyCached = synchronized(streamCache) {
                val e = streamCache[cacheKey]
                e != null && System.currentTimeMillis() - e.time < STREAM_CACHE_TTL_MS
            }
            if (!alreadyCached && !inFlightResolutions.containsKey("$cacheKey:false")) {
                prefetchExecutor.execute {
                    runCatching { resolveAudioStream(id, quality, forceRefresh = false) }
                }
            }
        }
    }

    fun search(query: String, languageHint: String = ""): List<Track> {
        val normalized = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return emptyList()

        synchronized(searchCache) {
            val cached = searchCache[normalized]
            if (cached != null && System.currentTimeMillis() - cached.time < SEARCH_CACHE_TTL_MS) {
                return cached.tracks.toList()
            }
            searchCache.remove(normalized)
        }

        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "IN")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)

        val conn = (URL(SEARCH_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("Accept", "application/json")
            doOutput = true
        }

        return try {
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val json = readStream(stream)
            if (status !in 200..299) {
                throw IOException("YouTube Music search HTTP $status")
            }
            val parsed = LinkedHashMap<String, Track>()
            collect(JSONObject(json), parsed, languageHint)
            val result = parsed.values.toList()
            synchronized(searchCache) {
                searchCache[normalized] = SearchCacheEntry(tracks = result)
                while (searchCache.size > 80) {
                    val oldestKey = searchCache.keys.firstOrNull() ?: break
                    searchCache.remove(oldestKey)
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    fun initializeExtractor() {
        if (extractorReady) return
        NewPipe.init(FluxDownloader())
        extractorReady = true
    }

    fun resolveAudioUrl(videoId: String, quality: AudioQuality = AudioQuality.AUTO): String =
        resolveAudioStream(videoId, quality, forceRefresh = false).url

    fun resolveAudioStream(
        videoId: String,
        quality: AudioQuality = AudioQuality.AUTO,
        forceRefresh: Boolean = false
    ): ResolvedAudioStream {
        val cacheKey = "$videoId:${quality.name}"
        if (forceRefresh) {
            invalidateStream(videoId)
        } else {
            synchronized(streamCache) {
                val cached = streamCache[cacheKey]
                if (cached != null && System.currentTimeMillis() - cached.time < STREAM_CACHE_TTL_MS) {
                    return cached.stream
                }
                streamCache.remove(cacheKey)
            }

            persistentReader?.invoke(videoId, quality)?.takeIf { it.url.isNotBlank() }?.let { diskStream ->
                synchronized(streamCache) {
                    streamCache[cacheKey] = StreamCacheEntry(stream = diskStream)
                }
                return diskStream
            }
        }

        val flightKey = "$cacheKey:$forceRefresh"
        var isOwner = false
        val future = inFlightResolutions.computeIfAbsent(flightKey) {
            isOwner = true
            CompletableFuture()
        }

        if (!isOwner) {
            return try {
                future.get()
            } catch (e: Exception) {
                throw (e.cause as? Exception) ?: IOException("Failed to resolve stream URL", e)
            }
        }

        return try {
            val resolved = resolveWithFallbacks(videoId, quality)
            val now = System.currentTimeMillis()
            synchronized(streamCache) {
                streamCache[cacheKey] = StreamCacheEntry(time = now, stream = resolved)
                while (streamCache.size > 120) {
                    val oldestKey = streamCache.keys.firstOrNull() ?: break
                    streamCache.remove(oldestKey)
                }
            }
            runCatching {
                persistentWriter?.invoke(videoId, quality, resolved, now + STREAM_CACHE_TTL_MS)
            }
            future.complete(resolved)
            resolved
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw (t as? Exception) ?: IOException(t.message ?: "Stream extraction error", t)
        } finally {
            inFlightResolutions.remove(flightKey, future)
        }
    }

    private fun resolveWithFallbacks(videoId: String, quality: AudioQuality): ResolvedAudioStream {
        // Tier 1: NewPipeExtractor progressive streams, ordered by quality and verified with byte-range probe
        resolveViaNewPipe(videoId, quality)?.let { return it }

        // Tier 2: Direct Innertube player clients (ANDROID_VR, then iOS) with matching User-Agent
        resolveViaInnertubeAndroidVr(videoId, quality)?.let { return it }
        resolveViaInnertubeIos(videoId, quality)?.let { return it }

        // Tier 3: Public Piped API backup instances
        resolveViaPiped(videoId, quality)?.let { return it }

        throw IOException("Unable to load a playable audio stream right now. Tap Retry.")
    }

    private fun resolveViaNewPipe(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        return runCatching {
            initializeExtractor()
            val service = NewPipe.getService("YouTube")
            val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val progressive = extractor.audioStreams
                .filter {
                    it.isUrl &&
                        !it.content.isNullOrBlank() &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                        !it.content.contains(".m3u8", ignoreCase = true)
                }
            if (progressive.isEmpty()) return null
            val ordered = orderNewPipeCandidates(progressive, quality)
            for (candidate in ordered.take(4)) {
                val url = candidate.content ?: continue
                if (probeStreamPlayable(url, USER_AGENT)) {
                    return ResolvedAudioStream(url = url, userAgent = USER_AGENT, source = "newpipe")
                }
            }
            null
        }.getOrNull()
    }

    private fun resolveViaInnertubeAndroidVr(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
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
        return fetchInnertubeAudio(
            videoId = videoId,
            quality = quality,
            clientJson = client,
            clientNameHeader = "28",
            clientVersion = "1.60.19",
            userAgent = ANDROID_VR_UA,
            sourceLabel = "innertube_vr"
        )
    }

    private fun resolveViaInnertubeIos(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        val client = JSONObject()
            .put("clientName", "IOS")
            .put("clientVersion", "19.45.4")
            .put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone")
            .put("osVersion", "18.1.0.22B83")
            .put("hl", "en")
            .put("gl", "US")
        return fetchInnertubeAudio(
            videoId = videoId,
            quality = quality,
            clientJson = client,
            clientNameHeader = "5",
            clientVersion = "19.45.4",
            userAgent = IOS_UA,
            sourceLabel = "innertube_ios"
        )
    }

    private fun fetchInnertubeAudio(
        videoId: String,
        quality: AudioQuality,
        clientJson: JSONObject,
        clientNameHeader: String,
        clientVersion: String,
        userAgent: String,
        sourceLabel: String
    ): ResolvedAudioStream? {
        return runCatching {
            val payload = JSONObject()
                .put("context", JSONObject().put("client", clientJson))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val conn = (URL(PLAYER_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-YouTube-Client-Name", clientNameHeader)
                setRequestProperty("X-YouTube-Client-Version", clientVersion)
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
                if (conn.responseCode !in 200..299) return null
                val root = JSONObject(readStream(conn.inputStream))
                val adaptive = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
                val candidates = ArrayList<Pair<Int, String>>()
                for (i in 0 until adaptive.length()) {
                    val fmt = adaptive.optJSONObject(i) ?: continue
                    val mime = fmt.optString("mimeType", "")
                    val url = fmt.optString("url", "")
                    if (mime.startsWith("audio/", ignoreCase = true) && url.isNotBlank() && !url.contains(".m3u8")) {
                        val bitrateKbps = (fmt.optInt("bitrate", 0) / 1000).coerceAtLeast(1)
                        candidates.add(bitrateKbps to url)
                    }
                }
                val ordered = orderRawCandidates(candidates, quality)
                for ((_, url) in ordered.take(3)) {
                    if (probeStreamPlayable(url, userAgent)) {
                        return ResolvedAudioStream(url = url, userAgent = userAgent, source = sourceLabel)
                    }
                }
                null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun resolveViaPiped(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        for (base in PIPED_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val root = JSONObject(readStream(conn.inputStream))
                    val audioStreams = root.optJSONArray("audioStreams") ?: return@runCatching null
                    val candidates = ArrayList<Pair<Int, String>>()
                    for (i in 0 until audioStreams.length()) {
                        val item = audioStreams.optJSONObject(i) ?: continue
                        if (item.optBoolean("videoOnly", false)) continue
                        val url = item.optString("url", "")
                        if (url.isBlank() || url.contains(".m3u8", ignoreCase = true)) continue
                        val rawBitrate = item.optInt("bitrate", 0)
                        val kbps = if (rawBitrate > 1000) rawBitrate / 1000 else rawBitrate
                        candidates.add(kbps.coerceAtLeast(1) to url)
                    }
                    val ordered = orderRawCandidates(candidates, quality)
                    for ((_, url) in ordered.take(3)) {
                        if (probeStreamPlayable(url, USER_AGENT)) {
                            return@runCatching ResolvedAudioStream(url = url, userAgent = USER_AGENT, source = "piped")
                        }
                    }
                    null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun probeStreamPlayable(urlString: String, userAgent: String): Boolean {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Range", "bytes=0-1")
            }
            try {
                val code = conn.responseCode
                val contentType = conn.contentType.orEmpty()
                if (code !in 200..299) return false
                if (contentType.startsWith("text/html", ignoreCase = true)) return false
                conn.inputStream?.use { input -> input.read() }
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    fun clearStreamCache() {
        synchronized(streamCache) { streamCache.clear() }
        synchronized(searchCache) { searchCache.clear() }
    }

    private fun orderNewPipeCandidates(streams: List<AudioStream>, quality: AudioQuality): List<AudioStream> {
        val sortedDesc = streams.sortedByDescending { it.averageBitrate }
        return when (quality) {
            AudioQuality.HIGH -> sortedDesc
            AudioQuality.DATA_SAVER -> {
                val low = sortedDesc.filter { it.averageBitrate in 32..96 }.sortedBy { it.averageBitrate }
                (low + sortedDesc.reversed()).distinct()
            }
            AudioQuality.AUTO -> {
                val balanced = sortedDesc.filter { it.averageBitrate in 96..160 }
                val fallback = sortedDesc.filter { it.averageBitrate in 64..192 }
                (balanced + fallback + sortedDesc).distinct()
            }
        }
    }

    private fun orderRawCandidates(candidates: List<Pair<Int, String>>, quality: AudioQuality): List<Pair<Int, String>> {
        val sortedDesc = candidates.sortedByDescending { it.first }
        return when (quality) {
            AudioQuality.HIGH -> sortedDesc
            AudioQuality.DATA_SAVER -> {
                val low = sortedDesc.filter { it.first in 32..96 }.sortedBy { it.first }
                (low + sortedDesc.reversed()).distinctBy { it.second }
            }
            AudioQuality.AUTO -> {
                val balanced = sortedDesc.filter { it.first in 96..160 }
                val fallback = sortedDesc.filter { it.first in 64..192 }
                (balanced + fallback + sortedDesc).distinctBy { it.second }
            }
        }
    }

    private fun collect(node: Any?, out: MutableMap<String, Track>, languageHint: String) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                if (renderer != null) {
                    parseTrack(renderer, languageHint)?.let { track ->
                        out[track.id] = track
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collect(node.opt(keys.next()), out, languageHint)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collect(node.opt(i), out, languageHint)
                }
            }
        }
    }

    private fun parseTrack(r: JSONObject, languageHint: String): Track? {
        val id = deepString(r, "videoId")
        if (id.isBlank()) return null
        val flex = r.optJSONArray("flexColumns")
        val title = if (flex != null && flex.length() > 0) extractText(flex.optJSONObject(0)) else ""
        val rawArtist = if (flex != null && flex.length() > 1) extractText(flex.optJSONObject(1)) else ""
        val rawAlbum = if (flex != null && flex.length() > 2) extractText(flex.optJSONObject(2)) else ""
        val fixed = r.optJSONArray("fixedColumns")
        val rawDuration = if (fixed != null && fixed.length() > 0) extractText(fixed.optJSONObject(0)) else ""
        val thumb = upgradeThumbnailUrl(extractThumbnail(r))

        val artistParts = rawArtist.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        val cleanArtist = artistParts.firstOrNull()?.takeIf {
            !it.equals("Song", ignoreCase = true) && !it.equals("Video", ignoreCase = true)
        } ?: artistParts.getOrNull(1) ?: rawArtist
        val cleanAlbum = rawAlbum.ifBlank {
            artistParts.lastOrNull()?.takeIf { it != cleanArtist && !it.contains(":") } ?: "YouTube Music"
        }

        return Track(
            id = id,
            title = title,
            artist = cleanArtist,
            album = cleanAlbum,
            durationMs = durationMillis(rawDuration),
            thumbnailUrl = thumb,
            languageHint = languageHint
        )
    }

    private fun upgradeThumbnailUrl(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w544-h544-l90-rj")
            .replace(Regex("=s\\d+[^&\\s]*"), "=s544")
    }

    private fun deepString(node: Any?, key: String): String {
        when (node) {
            is JSONObject -> {
                val direct = node.opt(key)
                if (direct is String && direct.isNotBlank()) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = deepString(node.opt(keys.next()), key)
                    if (found.isNotEmpty()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = deepString(node.opt(i), key)
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return ""
    }

    private fun extractText(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val direct = node.opt("text")
                if (direct is String && direct.isNotBlank()) return direct
                val runs = node.optJSONArray("runs")
                if (runs != null) {
                    val sb = StringBuilder()
                    for (i in 0 until runs.length()) {
                        runs.optJSONObject(i)?.optString("text", "")?.let { sb.append(it) }
                    }
                    if (sb.isNotEmpty()) return sb.toString()
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = extractText(node.opt(keys.next()))
                    if (v.isNotEmpty()) return v
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = extractText(node.opt(i))
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return ""
    }

    private fun extractThumbnail(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val thumbObj = node.optJSONObject("thumbnail")
                if (thumbObj != null) {
                    val arr = thumbObj.optJSONArray("thumbnails")
                    if (arr != null && arr.length() > 0) {
                        val last = arr.optJSONObject(arr.length() - 1)
                        val url = last?.optString("url", "").orEmpty()
                        if (url.isNotEmpty()) return url
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = extractThumbnail(node.opt(keys.next()))
                    if (v.isNotEmpty()) return v
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = extractThumbnail(node.opt(i))
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return ""
    }

    private fun durationMillis(value: String): Long {
        return try {
            var seconds = 0L
            for (part in value.trim().split(":")) {
                seconds = seconds * 60L + part.toLong()
            }
            seconds * 1000L
        } catch (_: Exception) {
            0L
        }
    }

    private fun readStream(input: InputStream?): String {
        if (input == null) return ""
        return input.use { src ->
            ByteArrayOutputStream().use { out ->
                val buf = ByteArray(8192)
                var read: Int
                while (src.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toString("UTF-8")
            }
        }
    }

    private class FluxDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                val hasCustomUa = request.headers()?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
                if (!hasCustomUa) {
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                request.headers()?.forEach { (key, values) ->
                    val first = values?.firstOrNull()
                    if (first != null) {
                        setRequestProperty(key, first)
                        values.drop(1).forEach { extra -> addRequestProperty(key, extra) }
                    }
                }
            }
            return try {
                request.dataToSend()?.let { data ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(data) }
                }
                val status = conn.responseCode
                val input = if (status in 200..399) conn.inputStream else conn.errorStream
                val body = readStream(input)
                val responseHeaders = HashMap<String, MutableList<String>>()
                conn.headerFields.forEach { (k, v) ->
                    if (k != null && v != null) responseHeaders[k] = v.toMutableList()
                }
                Response(status, conn.responseMessage, responseHeaders, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }
}
