package com.p4ko.dmcgf7exporter.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

/**
 * Abstraction for HTTP communication with the Panasonic LUMIX DMC-GF7 (`cam.cgi`, DLNA SOAP, and Port 50001 media).
 */
interface HttpTransport {
    @Throws(IOException::class)
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResponse

    @Throws(IOException::class)
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResponse

    @Throws(IOException::class)
    fun downloadToStream(
        url: String,
        outputStream: OutputStream,
        timeoutMs: Int = DEFAULT_DOWNLOAD_TIMEOUT_MS
    ): Long

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 60_000
    }
}

/**
 * [HttpTransport] backed by [HttpURLConnection], optionally using a custom connection opener
 * (such as Android's Wi-Fi `Network.openConnection(url)`) so traffic reaches `192.168.54.1`
 * even when the camera's Wi-Fi AP has no internet connectivity.
 */
class UrlConnectionHttpTransport(
    private val connectionOpener: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : HttpTransport {

    override fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResponse {
        val conn = connectionOpener(URL(url))
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            readTextResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResponse {
        val payload = body.toByteArray(Charsets.UTF_8)
        val conn = connectionOpener(URL(url))
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { out ->
                out.write(payload)
                out.flush()
            }
            readTextResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun downloadToStream(
        url: String,
        outputStream: OutputStream,
        timeoutMs: Int
    ): Long {
        val conn = connectionOpener(URL(url))
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code while downloading $url")
            }
            var totalBytes = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    outputStream.write(buffer, 0, read)
                    totalBytes += read
                }
                outputStream.flush()
            }
            totalBytes
        } finally {
            conn.disconnect()
        }
    }

    private fun readTextResponse(conn: HttpURLConnection): HttpResponse {
        val statusCode = conn.responseCode
        val stream = if (statusCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        val bodyBytes = ByteArrayOutputStream()
        stream?.use { input ->
            val buf = ByteArray(4096)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                bodyBytes.write(buf, 0, r)
            }
        }
        val responseHeaders = mutableMapOf<String, String>()
        conn.headerFields?.forEach { (key, values) ->
            if (key != null && !values.isNullOrEmpty()) {
                responseHeaders[key] = values.joinToString(", ")
            }
        }
        return HttpResponse(
            statusCode = statusCode,
            body = bodyBytes.toString(Charsets.UTF_8.name()),
            headers = responseHeaders
        )
    }
}
