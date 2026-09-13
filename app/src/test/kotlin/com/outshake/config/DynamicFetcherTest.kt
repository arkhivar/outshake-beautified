package com.outshake.config

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

/** Offline HTTP policy tests with fake connections: not proof of a live TLS handshake. */
class DynamicFetcherTest {
    private class Reply(
        val status: Int = 200,
        val body: ByteArray = "config".toByteArray(),
        val location: String? = null,
        val declaredSize: Long = -1,
    ) : HttpsURLConnection(URL("https://example.com")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = declaredSize
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getCipherSuite() = "FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun `invalid scheme user info host and port never reach network`() {
        for (url in listOf("http://example.com", "https://user:secret@example.com", "file:///tmp/config",
            "https://", "https://example.com:0", "https://example.com:65536")) {
            assertThrows(ConfigException::class.java) {
                DynamicFetcher.fetch(url, { error("must not open") })
            }
        }
    }

    @Test fun `successful response disables cache and automatic redirects and closes`() {
        val reply = Reply()
        assertEquals("config", DynamicFetcher.fetch("https://example.com", { reply }))
        assertFalse(reply.useCaches)
        assertFalse(reply.instanceFollowRedirects)
        assertEquals("identity", reply.getRequestProperty("Accept-Encoding"))
        assertEquals("no-cache, no-store", reply.getRequestProperty("Cache-Control"))
        assertTrue(reply.disconnected)
    }

    @Test fun `relative and cross host HTTPS redirects are validated`() {
        val replies = listOf(Reply(302, location = "/next"), Reply(307, location = "https://other.example/path"), Reply())
        val urls = mutableListOf<String>()
        assertEquals("config", DynamicFetcher.fetch("https://example.com/start", {
            urls.add(it.toString()); replies[urls.size - 1]
        }))
        assertEquals(listOf("https://example.com/start", "https://example.com/next", "https://other.example/path"), urls)
        assertTrue(replies.all { it.disconnected })
    }

    @Test fun `HTTPS redirect downgrade and user info fail before second request`() {
        for (target in listOf("http://example.com", "https://user:secret@example.com")) {
            val reply = Reply(302, location = target)
            var opens = 0
            assertThrows(ConfigException::class.java) {
                DynamicFetcher.fetch("https://example.com", { opens++; reply })
            }
            assertEquals(1, opens)
            assertTrue(reply.disconnected)
        }
    }

    @Test fun `redirect loops are bounded`() {
        var opens = 0
        assertThrows(ConfigException::class.java) {
            DynamicFetcher.fetch("https://example.com", { opens++; Reply(302, location = "/again") })
        }
        assertEquals(4, opens)
    }

    @Test fun `declared and streamed overlarge responses are bounded`() {
        for (reply in listOf(Reply(declaredSize = DynamicFetcher.MAX_BODY_BYTES + 1L),
            Reply(body = ByteArray(DynamicFetcher.MAX_BODY_BYTES + 1)))) {
            assertThrows(ConfigException::class.java) { DynamicFetcher.fetch("https://example.com", { reply }) }
            assertTrue(reply.disconnected)
        }
    }

    @Test fun `exact byte limit is accepted`() {
        val reply = Reply(body = ByteArray(DynamicFetcher.MAX_BODY_BYTES) { 'a'.code.toByte() })
        assertEquals(DynamicFetcher.MAX_BODY_BYTES, DynamicFetcher.fetch("https://example.com", { reply }).length)
    }

    @Test fun `errors empty responses and missing redirects fail without raw diagnostics`() {
        for (reply in listOf(Reply(500), Reply(body = byteArrayOf()), Reply(302))) {
            val ex = assertThrows(ConfigException::class.java) {
                DynamicFetcher.fetch("https://example.com/SECRET", { reply })
            }
            assertFalse(ex.message!!.contains("SECRET"))
            assertTrue(reply.disconnected)
        }
        val ex = assertThrows(ConfigException::class.java) {
            DynamicFetcher.fetch("https://example.com/SECRET", { throw SSLHandshakeException("SECRET") })
        }
        assertEquals("TLS error fetching dynamic config", ex.message)
    }

    @Test fun `elapsed budget is checked during response handling`() {
        var time = 0L
        val reply = Reply()
        assertThrows(ConfigException::class.java) {
            DynamicFetcher.fetch("https://example.com", { time = 31_000_000_000L; reply }, { time })
        }
        assertTrue(reply.disconnected)
    }
}
