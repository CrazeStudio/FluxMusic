package com.crazestudio.fluxmusic.data.remote

import com.crazestudio.fluxmusic.data.model.AudioQuality
import com.crazestudio.fluxmusic.data.model.Track
import android.net.Uri
import android.util.Log
import org.json.JSONArray
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
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val source: String = "youtube"
)

object YtMusicCatalog {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TECNO KI5k) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    // NewPipeExtractor v0.27.1 resolves YouTube streams with the VISIONOS player client.
    // Keep this identity when probing and playing those signed stream URLs.
    const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; GB)"

    // Changing this tag invalidates persisted stream URLs from older resolver versions.
    const val STREAM_CACHE_TAG = "|flux-resolver-v4"
    private const val SEARCH_ENDPOINT =
        "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20261116.01.00"
    private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
    const val STREAM_CACHE_TTL_MS = 3 * 60 * 60 * 1000L

    // Avoid the long-unavailable official instance and retired mirrors.
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
    fun prepareYoutubePlaybackUrl(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            if (!uri.path.orEmpty().startsWith("/videoplayback") ||
                uri.getQueryParameter("rn") != null
            ) {
                return url
            }
            uri.buildUpon()
                .appendQueryParameter("rn", playbackRequestNumber.getAndIncrement().toString())
                .build()
                .toString()
        }.getOrDefault(url)
    }
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
            connectTimeout = 5000
            readTimeout = 7000
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
            // Empty results can be transient when the upstream response changes.
            // Avoid caching them so a subsequent request can recover immediately.
            if (result.isNotEmpty()) {
                synchronized(searchCache) {
                    searchCache[normalized] = SearchCacheEntry(tracks = result)
                    while (searchCache.size > 80) {
                        val oldestKey = searchCache.keys.firstOrNull() ?: break
                        searchCache.remove(oldestKey)
                    }
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
        // Primary: current NewPipe VISIONOS extraction with its matching User-Agent.
        resolveViaNewPipe(videoId, quality)?.let { return it }

        // Public APIs are fallbacks for IPs where direct YouTube extraction is rate-limited.
        resolveViaPiped(videoId, quality)?.let { return it }
        resolveViaInvidious(videoId, quality)?.let { return it }

        throw IOException(
            "No playable stream was returned by YouTube, Piped, or Invidious. " +
                "The upstream services may be temporarily blocking playback. Tap Retry."
        )
    }

    private fun resolveViaNewPipe(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        return try {
            initializeExtractor()
            val extractor = NewPipe.getService("YouTube")
                .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            // Adaptive DASH audio entries contain direct, signed media-file URLs.
            // Pass those files to Media3's progressive extractors; do not pass an MPD
            // URL here (NewPipe's YouTube extractor does not expose one).
            val candidates = extractor.audioStreams.filter { stream ->
                stream.isUrl &&
                    !stream.content.isNullOrBlank() &&
                    !stream.content.contains(".m3u8", ignoreCase = true) &&
                    !stream.content.startsWith("manifest", ignoreCase = true)
            }
            Log.i(
                "FluxMusicPlayback",
                "NewPipe audio candidates=${candidates.size}; deliveryMethods=${extractor.audioStreams.groupingBy { it.deliveryMethod }.eachCount()}"
            )
            for (candidate in orderNewPipeCandidates(candidates, quality).take(5)) {
                val url = candidate.content ?: continue
                if (probeStreamPlayable(url, VISIONOS_USER_AGENT, useYoutubePlaybackProtocol = true)) {
                    return ResolvedAudioStream(url, VISIONOS_USER_AGENT, "newpipe-visionos")
                }
            }
            null
        } catch (e: Exception) {
            Log.w("FluxMusicPlayback", "NewPipe extraction failed: ${e.message}", e)
            null
        }
    }

    private fun resolveViaPiped(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        for (base in PIPED_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val audio = JSONObject(readStream(conn.inputStream)).optJSONArray("audioStreams")
                        ?: return@runCatching null
                    val candidates = ArrayList<Pair<Int, String>>()
                    for (i in 0 until audio.length()) {
                        val item = audio.optJSONObject(i) ?: continue
                        if (item.optBoolean("videoOnly", false)) continue
                        val url = item.optString("url", "")
                        if (!url.startsWith("https://") || url.contains(".m3u8", true)) continue
                        val raw = item.optInt("bitrate", 0)
                        candidates.add((if (raw > 1000) raw / 1000 else raw).coerceAtLeast(1) to url)
                    }
                    for ((_, url) in orderRawCandidates(candidates, quality).take(4)) {
                        if (probeStreamPlayable(url, USER_AGENT)) {
                            return@runCatching ResolvedAudioStream(url, USER_AGENT, "piped")
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

    private fun resolveViaInvidious(videoId: String, quality: AudioQuality): ResolvedAudioStream? {
        for (base in INVIDIOUS_INSTANCES) {
            val resolved = runCatching {
                val conn = (URL("$base/api/v1/videos/$videoId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val formats = JSONObject(readStream(conn.inputStream)).optJSONArray("adaptiveFormats")
                        ?: return@runCatching null
                    val candidates = ArrayList<Pair<Int, String>>()
                    for (i in 0 until formats.length()) {
                        val item = formats.optJSONObject(i) ?: continue
                        if (!item.optString("type", "").startsWith("audio/", true)) continue
                        val url = item.optString("url", "")
                        if (!url.startsWith("https://") || url.contains(".m3u8", true)) continue
                        val raw = item.optString("bitrate", "0").toIntOrNull() ?: 0
                        candidates.add((if (raw > 1000) raw / 1000 else raw).coerceAtLeast(1) to url)
                    }
                    for ((_, url) in orderRawCandidates(candidates, quality).take(4)) {
                        if (probeStreamPlayable(url, USER_AGENT)) {
                            return@runCatching ResolvedAudioStream(url, USER_AGENT, "invidious")
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

    private fun probeStreamPlayable(
        urlString: String,
        userAgent: String,
        useYoutubePlaybackProtocol: Boolean = false
    ): Boolean {
        return try {
            val requestUrl = if (useYoutubePlaybackProtocol) {
                prepareYoutubePlaybackUrl(urlString)
            } else {
                urlString
            }
            val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                // Match the media datasource: a ranged GET with a fresh rn parameter.
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-1")
            }
            try {
                val code = conn.responseCode
                val contentType = conn.contentType.orEmpty()
                if (code !in 200..299) {
                    Log.w("FluxMusicPlayback", "Stream probe rejected candidate: HTTP $code")
                    return false
                }
                if (contentType.startsWith("text/html", ignoreCase = true)) {
                    Log.w("FluxMusicPlayback", "Stream probe returned HTML instead of audio")
                    return false
                }
                conn.inputStream?.use { input -> input.read() }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w("FluxMusicPlayback", "Stream probe failed: ${e.message}", e)
            false
        }
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
