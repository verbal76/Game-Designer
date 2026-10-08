package com.hotattic.gamedesigner.models

import com.hotattic.gamedesigner.core.models.ModelResponse
import com.hotattic.gamedesigner.core.models.ModelTransport
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ranged HTTPS download. Redirects are followed by hand so the sign-in token is only ever sent to the original host (model hosts
 * redirect to a CDN that must not receive it).
 */
class HttpModelTransport : ModelTransport {
    override fun open(url: String, rangeStart: Long, headers: Map<String, String>): ModelResponse {
        var current = URL(url)
        val originHost = current.host
        repeat(6) {
            require(current.protocol == "https") { "Only https downloads are allowed" }
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "GameDesigner/3 (Hot Attic Games)")
            if (current.host == originHost) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val code = c.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("Redirect without a location")
                c.disconnect()
                current = URL(current, loc)
                return@repeat
            }
            val stream = if (code in 200..299) c.inputStream else null
            return ModelResponse(code, c.contentLengthLong, stream) { try { stream?.close() } finally { c.disconnect() } }
        }
        throw java.io.IOException("Too many redirects")
    }
}
