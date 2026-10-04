package com.hotattic.gamedesigner.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String) {
    val ok get() = code in 200..299
}

class NetworkUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Minimal HTTP boundary so every network client is testable with a fake. */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun put(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/** HttpURLConnection implementation (works on Android and JVM; no extra dependencies). */
class JavaHttpTransport(private val userAgent: String = "GameDesigner/0.1 (Hot Attic Games)", private val timeoutMs: Int = 20_000) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>) = call("GET", url, null, headers)
    override suspend fun post(url: String, body: String, headers: Map<String, String>) = call("POST", url, body, headers)
    override suspend fun put(url: String, body: String, headers: Map<String, String>) = call("PUT", url, body, headers)

    private suspend fun call(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Only https:// URLs are allowed" }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs * 3
                c.setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                HttpResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally { c.disconnect() }
        } catch (e: java.io.IOException) {
            throw NetworkUnavailableException(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
