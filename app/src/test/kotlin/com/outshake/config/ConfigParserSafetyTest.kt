package com.outshake.config

import org.junit.Assert.*
import org.junit.Test

class ConfigParserSafetyTest {
    private val leaf = """{"server":"example.com","server_port":443,"method":"aes-256-gcm","password":"test-secret"}"""
    private fun rejects(body: String) = assertThrows(ConfigException::class.java) { ConfigParser.parseDynamicBody(body) }
    private fun static(prefix: String, method: String = "aes-256-gcm") =
        ConfigParser.parseStatic("ss://$method:test@example.com:443/?prefix=$prefix")
    private fun graph(tcp: String = leaf, udp: String = leaf) =
        """{"transport":{"${'$'}type":"tcpudp","tcp":$tcp,"udp":$udp}}"""

    @Test fun `canonical Outline UTF8 escaped Latin1 prefix becomes one byte`() {
        assertArrayEquals(byteArrayOf(0x16, 3, 1, 0, 0xA8.toByte(), 1, 1),
            static("%16%03%01%00%C2%A8%01%01").transport.prefix)
    }

    @Test fun `legacy non UTF8 raw prefix remains accepted`() {
        assertArrayEquals(byteArrayOf(0xC2.toByte()), static("%C2").transport.prefix)
    }

    @Test fun `invalid escapes and non Latin1 characters fail`() {
        for (p in listOf("%", "%0", "%GG", "%E2%82%AC")) {
            assertThrows(ConfigException::class.java) { static(p) }
        }
        assertThrows(ConfigException::class.java) { ConfigParser.prefixStringToBytes("\u0100") }
    }

    @Test fun `prefix safety preserves 128 random salt bits`() {
        assertEquals(16, static("a".repeat(16)).transport.prefix!!.size)
        assertThrows(ConfigException::class.java) { static("a".repeat(17)) }
        assertThrows(ConfigException::class.java) { static("a", "aes-128-gcm") }
        assertEquals(0, static("", "aes-128-gcm").transport.prefix!!.size)
    }

    @Test fun `plugins and duplicate prefix parameters fail`() {
        for (query in listOf("plugin=v2ray", "prefix=a&prefix=b", "prefix=a&udp=off")) {
            assertThrows(ConfigException::class.java) {
                ConfigParser.parseStatic("ss://aes-256-gcm:test@example.com:443/?$query")
            }
        }
    }

    @Test fun `Outline query marker remains accepted`() {
        assertNotNull(ConfigParser.parseStatic("ss://aes-256-gcm:test@example.com:443/?outline=1"))
    }

    @Test fun `standard URL safe padded and unpadded Base64 variants decode identically`() {
        for (password in listOf("pw", "longer", "\u00bf\u00ff?")) {
            val value = "aes-256-gcm:$password".toByteArray()
            for (encoder in listOf(java.util.Base64.getEncoder(), java.util.Base64.getUrlEncoder())) {
                for (e in listOf(encoder, encoder.withoutPadding())) {
                    val key = "ss://${e.encodeToString(value)}@example.com:443"
                    assertEquals(password, ConfigParser.parseStatic(key).transport.password)
                }
            }
        }
    }

    @Test fun `arbitrary access key paths cannot be silently ignored`() {
        assertThrows(ConfigException::class.java) {
            ConfigParser.parseStatic("ss://aes-256-gcm:test@example.com:443/tunnel")
        }
    }

    @Test fun `duplicate YAML keys and diagnostics never echo secrets`() {
        for (body in listOf("password: TOP_SECRET\npassword: other", "password: [TOP_SECRET")) {
            val ex = rejects(body)
            assertFalse(ex.message!!.contains("TOP_SECRET"))
        }
    }

    @Test fun `object booleans and numeric secrets are not coerced into strings`() {
        for (value in listOf("123", "true", "{}", "[]", "null")) {
            rejects(leaf.replace("\"test-secret\"", value))
        }
    }

    @Test fun `fractional overflow and invalid ports are not truncated`() {
        for (value in listOf("443.5", "4294967739", "0", "65536", "true")) {
            rejects(leaf.replace(":443", ":$value"))
        }
        assertEquals(443, ConfigParser.parseDynamicBody(leaf.replace(":443", ":\"443\"")).transport.port)
    }

    @Test fun `ambiguous aliases and endpoint override fail`() {
        rejects(leaf.dropLast(1) + ""","host":"other"}""")
        rejects(leaf.dropLast(1) + ""","endpoint":"other:123"}""")
        rejects(leaf.dropLast(1) + ""","secret":"different"}""")
    }

    @Test fun `unsupported fields are not silently ignored`() {
        for (extra in listOf("\"plugin\":\"x\"", "\"dial\":{}", "\"udp\":{}", "\"first-supported\":[]")) {
            rejects(leaf.dropLast(1) + ",$extra}")
        }
    }

    @Test fun `matching branches and TCP only prefix are representable`() {
        val tcp = leaf.dropLast(1) + ""","prefix":"GET " }"""
        assertEquals("example.com", ConfigParser.parseDynamicBody(graph(tcp)).transport.host)
        assertArrayEquals("GET ".toByteArray(), ConfigParser.parseDynamicBody(graph(tcp)).transport.prefix)
    }

    @Test fun `distinct UDP endpoint secret or cipher is rejected`() {
        for (udp in listOf(leaf.replace("example.com", "udp.example.com"), leaf.replace(":443", ":8443"),
            leaf.replace("test-secret", "different"), leaf.replace("aes-256-gcm", "chacha20-ietf-poly1305"))) {
            rejects(graph(udp = udp))
        }
    }

    @Test fun `UDP prefix and missing branches are rejected`() {
        rejects(graph(udp = leaf.dropLast(1) + ""","prefix":"GET "}"""))
        rejects("""{"transport":{"${'$'}type":"tcpudp","tcp":$leaf}}""")
        rejects("""{"transport":{"${'$'}type":"first-supported","options":[$leaf]}}""")
        rejects("""{"transport":${leaf.dropLast(1)},"prefix":"GET "}}""")
    }

    @Test fun `SIP008 chooses first entry and never skips unsupported first entry`() {
        assertEquals("example.com", ConfigParser.parseDynamicBody("""{"version":1,"servers":[$leaf,$leaf]}""").transport.host)
        rejects("""{"servers":[${leaf.replace("aes-256-gcm", "unsupported")},$leaf]}""")
        rejects("""{"servers":[$leaf],"transport":$leaf}""")
    }

    @Test fun `multiple keys and excessive documents fail`() {
        rejects("ss://aes-256-gcm:test@a:443\nss://aes-256-gcm:test@b:443")
        rejects("x".repeat(DynamicFetcher.MAX_BODY_BYTES + 1))
        rejects("a: &a [*a]")
    }
}
