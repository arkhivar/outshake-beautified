package com.outshake.config

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Fetches the remote body referenced by an ssconf:// key. Network errors become ConfigException. */
object DynamicFetcher {
    const val MAX_BODY_BYTES = 256 * 1024
    private const val MAX_REDIRECTS = 3

    internal fun validateUrl(value: String): URL = try {
        val url = URL(value)
        val uri = url.toURI()
        if (!url.protocol.equals("https", true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || (url.port != -1 && url.port !in 1..65535)) {
            throw IllegalArgumentException()
        }
        url
    } catch (_: Exception) {
        throw ConfigException("Dynamic config URL must be HTTPS with a host and no user info")
    }

    fun fetch(url: String): String = fetch(url, { it.openConnection() as HttpsURLConnection })

    /** Injection is for offline policy tests, not a replacement TLS implementation. */
    internal fun fetch(
        url: String,
        open: (URL) -> HttpsURLConnection,
        nowNanos: () -> Long = System::nanoTime,
    ): String {
        var current = validateUrl(url)
        val start = nowNanos()
        fun checkDeadline() {
            if (nowNanos() - start > 30_000_000_000L) throw ConfigException("Dynamic config fetch timed out")
        }
        try {
            for (redirects in 0..MAX_REDIRECTS) {
                checkDeadline()
                val connection = open(current)
                try {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Accept", "application/json, text/yaml, text/plain")
                    connection.setRequestProperty("User-Agent", "Outshake/1.0")
                    val code = connection.responseCode
                    checkDeadline()
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        if (redirects == MAX_REDIRECTS) throw ConfigException("Too many dynamic config redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw ConfigException("Dynamic config redirect has no location")
                        current = validateUrl(URL(current, location).toExternalForm())
                        continue
                    }
                    if (code !in 200..299) throw ConfigException("Dynamic config server returned HTTP $code")
                    if (connection.contentLengthLong > MAX_BODY_BYTES) throw ConfigException("Dynamic config is too large")
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            checkDeadline()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > MAX_BODY_BYTES) throw ConfigException("Dynamic config is too large")
                            out.write(buf, 0, n)
                        }
                    }
                    checkDeadline()
                    val body = out.toString("UTF-8")
                    if (body.isBlank()) throw ConfigException("Dynamic config response was empty")
                    return body
                } finally {
                    connection.disconnect()
                }
            }
            throw ConfigException("Too many dynamic config redirects")
        } catch (e: ConfigException) {
            throw e
        } catch (_: javax.net.ssl.SSLException) {
            throw ConfigException("TLS error fetching dynamic config")
        } catch (_: Exception) {
            // URLs and server diagnostics can embed access tokens. Never echo them.
            throw ConfigException("Network error fetching dynamic config")
        }
    }
}
