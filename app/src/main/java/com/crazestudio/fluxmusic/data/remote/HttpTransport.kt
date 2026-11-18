package com.crazestudio.fluxmusic.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Shared bounded HTTPS transport. Callers must invoke it from an IO/worker thread. */
internal object HttpTransport {
    data class Response(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299

        fun requireSuccess(service: String): Response {
            if (!isSuccessful) {
                val retryAfter = headers.entries
                    .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                    ?.value?.firstOrNull()
                val suffix = retryAfter?.let { " (retry after $it)" }.orEmpty()
                val detail = body.take(240).replace(Regex("\\s+"), " ").trim()
                throw IOException(
                    "$service returned HTTP $statusCode$suffix" +
                        if (detail.isBlank()) "" else ": $detail"
                )
            }
            return this
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 10_000,
        maxBytes: Int = 2 * 1024 * 1024
    ): Response = request(
        url, "GET", headers, null, connectTimeoutMs, readTimeoutMs, maxBytes
    )

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 10_000,
        maxBytes: Int = 3 * 1024 * 1024
    ): Response = request(
        url = url,
        method = "POST",
        headers = headers + mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json"
        ),
        body = json,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        maxBytes = maxBytes
    )

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Int
    ): Response {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Only HTTPS remote requests are allowed"
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            useCaches = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(
                statusCode = status,
                body = readBounded(stream, maxBytes),
                headers = connection.headerFields
                    .filterKeys { !it.isNullOrBlank() }
                    .mapKeys { it.key.orEmpty() }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream?, maxBytes: Int): String {
        if (input == null) return ""
        return input.use { source ->
            ByteArrayOutputStream(minOf(maxBytes, 32 * 1024)).use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw IOException("Remote response exceeded the $maxBytes byte safety limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
    }
}
