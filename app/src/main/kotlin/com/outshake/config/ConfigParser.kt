package com.outshake.config

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.bouncycastle.util.encoders.Base64

/**
 * Parses Outline-compatible access keys into a normalized [ParsedConfig].
 *
 * Everything here is pure (no Android / no network) so it is fully unit-testable.
 * The network fetch for ssconf:// lives in the caller; use [ssConfToUrl] to resolve the
 * URL and [parseDynamicBody] to parse the fetched body.
 */
object ConfigParser {

    fun isSsConf(key: String): Boolean = key.trim().startsWith("ssconf://", ignoreCase = true)
    fun isStatic(key: String): Boolean = key.trim().startsWith("ss://", ignoreCase = true)

    // ---------------------------------------------------------------------
    // Static ss:// keys
    // ---------------------------------------------------------------------

    /** Parse a static `ss://` key (both SIP002 and legacy base64 blob forms). */
    fun parseStatic(rawKey: String): ParsedConfig {
        val key = rawKey.trim()
        if (!isStatic(key)) throw ConfigException("Not an ss:// key")
        var body = key.substring("ss://".length)

        // Fragment = human name (URL-encoded).
        var name = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = urlDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        if (body.isEmpty()) throw ConfigException("Empty ss:// key")

        return if (body.contains('@')) {
            parseSip002(body, name)
        } else {
            parseLegacy(body, name)
        }
    }

    /** SIP002: ss://base64(method:pass)@host:port/?prefix=... or ss://method:pass@host:port. */
    private fun parseSip002(body: String, name: String): ParsedConfig {
        val at = body.lastIndexOf('@')
        val userInfoRaw = body.substring(0, at)
        var hostPart = body.substring(at + 1)

        // Strip and capture query (may hold the prefix).
        var query = ""
        val q = hostPart.indexOf('?')
        if (q >= 0) {
            query = hostPart.substring(q + 1)
            hostPart = hostPart.substring(0, q)
        }
        // SIP002 permits a trailing slash, not an arbitrary ignored transport path.
        val slash = hostPart.indexOf('/')
        if (slash >= 0) {
            if (hostPart.substring(slash) != "/") throw ConfigException("Unsupported access key path")
            hostPart = hostPart.substring(0, slash)
        }

        val methodPassword = decodeUserInfo(userInfoRaw)
        val colon = methodPassword.indexOf(':')
        if (colon < 0) throw ConfigException("Malformed ss:// user info (expected method:password)")
        val method = methodPassword.substring(0, colon)
        val password = methodPassword.substring(colon + 1)

        val (host, port) = splitHostPort(hostPart)
        val cipher = resolveCipher(method)
        val prefix = extractPrefixFromQuery(query)
        PrefixPolicy.validate(cipher, prefix)
        return ParsedConfig(displayName(name, host, port), TransportConfig(host, port, cipher, password, prefix))
    }

    /** Legacy: ss://base64(method:password@host:port) */
    private fun parseLegacy(body: String, name: String): ParsedConfig {
        val decoded = try {
            String(base64Decode(body), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ConfigException("Malformed ss:// key (invalid base64)")
        }
        val at = decoded.lastIndexOf('@')
        if (at < 0) throw ConfigException("Malformed ss:// key (missing '@')")
        val methodPassword = decoded.substring(0, at)
        val hostPart = decoded.substring(at + 1)
        val colon = methodPassword.indexOf(':')
        if (colon < 0) throw ConfigException("Malformed ss:// key (expected method:password)")
        val method = methodPassword.substring(0, colon)
        val password = methodPassword.substring(colon + 1)
        val (host, port) = splitHostPort(hostPart)
        val cipher = resolveCipher(method)
        return ParsedConfig(displayName(name, host, port), TransportConfig(host, port, cipher, password, null))
    }

    private fun decodeUserInfo(userInfo: String): String {
        // SIP002 mandates base64(method:password); tolerate a literal method:password too.
        return try {
            val decoded = String(base64Decode(userInfo), Charsets.UTF_8)
            if (decoded.contains(':')) decoded else urlDecode(userInfo)
        } catch (e: Exception) {
            urlDecode(userInfo)
        }
    }

    // ---------------------------------------------------------------------
    // Dynamic ssconf:// keys
    // ---------------------------------------------------------------------

    /** Resolve an `ssconf://` key to the https URL that must be fetched. */
    fun ssConfToUrl(rawKey: String): String {
        val key = rawKey.trim()
        if (!isSsConf(key)) throw ConfigException("Not an ssconf:// key")
        var rest = key.substring("ssconf://".length)
        // Drop a fragment if present (name hint) — not part of the URL.
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        if (rest.isEmpty()) throw ConfigException("Empty ssconf:// key")
        return DynamicFetcher.validateUrl("https://$rest").toExternalForm()
    }

    /** Optional display-name hint from the ssconf fragment. */
    fun ssConfName(rawKey: String): String {
        val hash = rawKey.indexOf('#')
        return if (hash >= 0) urlDecode(rawKey.substring(hash + 1)) else ""
    }

    /**
     * Parse the body fetched from an ssconf URL. Supports: an `ss://` line, a JSON object,
     * or a YAML document (including the newer Outline transport graph).
     */
    fun parseDynamicBody(body: String, nameHint: String = ""): ParsedConfig {
        if (body.length > DynamicFetcher.MAX_BODY_BYTES) throw ConfigException("Dynamic config is too large")
        val text = body.trim()
        if (text.isEmpty()) throw ConfigException("Dynamic config was empty")

        if (text.startsWith("ss://", ignoreCase = true)) {
            if (text.lineSequence().count { it.isNotBlank() } != 1) {
                throw ConfigException("Dynamic config must contain exactly one ss:// key")
            }
            val line = text.lineSequence().first { it.isNotBlank() }.trim()
            val parsed = parseStatic(line)
            return if (nameHint.isNotBlank()) parsed.copy(name = nameHint) else parsed
        }

        val loaded: Any = try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit = 32
                codePointLimit = DynamicFetcher.MAX_BODY_BYTES
            }
            Yaml(SafeConstructor(options)).load<Any>(text)
        } catch (e: Exception) {
            // Parser diagnostics can contain whole source lines, including passwords.
            throw ConfigException("Dynamic config is not valid or supported JSON/YAML")
        } ?: throw ConfigException("Dynamic config was empty")

        if (loaded !is Map<*, *>) {
            throw ConfigException("Dynamic config must be a JSON/YAML object")
        }
        return normalizeMap(loaded, nameHint)
    }

    private fun normalizeMap(root: Map<*, *>, nameHint: String): ParsedConfig {
        // SIP008-style { "servers": [ {...} ] } — use the first server.
        if (root.containsKey("servers")) {
            checkFields(root, setOf("servers", "version", "bytes_used", "bytes_remaining"))
            val servers = root["servers"] as? List<*>
                ?: throw ConfigException("Dynamic config 'servers' must be a list")
            val first = servers.firstOrNull() as? Map<*, *>
                ?: throw ConfigException("Dynamic config 'servers' list is empty")
            return parseLeaf(first, nameHint)
        }

        // Newer Outline transport graph: { transport: { $type: tcpudp, tcp: {...}, udp: {...} } }
        val transport = root["transport"]
        if (root.containsKey("transport")) {
            checkFields(root, setOf("transport", "name", "description"))
            if (transport !is Map<*, *>) throw ConfigException("Transport must be an object")
            return resolveTransportGraph(transport, nameHint)
        }
        return parseLeaf(root, nameHint)
    }

    private fun parseLeaf(node: Map<*, *>, nameHint: String): ParsedConfig {
        checkFields(node, setOf(
            "\$type", "type", "method", "cipher", "password", "secret", "server", "host",
            "server_port", "port", "endpoint", "prefix", "id", "remarks", "name"
        ))
        // If the node itself declares a transport type, it must be shadowsocks.
        val type = firstString(node, "\$type", "type")
        if (type != null && !type.equals("shadowsocks", ignoreCase = true)) {
            throw ConfigException("Unsupported transport type (only shadowsocks is supported)")
        }

        val method = firstString(node, "method", "cipher")
            ?: throw ConfigException("Dynamic config missing 'method'/'cipher'")
        val password = firstString(node, "password", "secret")
            ?: throw ConfigException("Dynamic config missing 'password'/'secret'")

        var host = firstString(node, "server", "host")
        var port = firstInt(node, "server_port", "port")
        val endpoint = firstString(node, "endpoint")
        if (endpoint != null) {
            if (host != null || port != null) throw ConfigException("Use endpoint OR server and port, not both")
            val (h, p) = splitHostPort(endpoint)
            host = h; port = p
        }
        if (host.isNullOrBlank()) throw ConfigException("Dynamic config missing 'server'/'endpoint'")
        if (port == null) throw ConfigException("Dynamic config missing 'server_port'/'port'")
        if (port !in 1..65535) throw ConfigException("Invalid port: $port")

        val cipher = resolveCipher(method)
        val prefix = firstString(node, "prefix")?.let { prefixStringToBytes(it) }
        PrefixPolicy.validate(cipher, prefix)
        val name = displayName(nameHint, host, port)
        return ParsedConfig(name, TransportConfig(host, port, cipher, password, prefix))
    }

    /** Only flatten a graph when both branches are representable by our single endpoint model. */
    private fun resolveTransportGraph(transport: Map<*, *>, nameHint: String): ParsedConfig {
        val type = firstString(transport, "\$type", "type")
        when {
            type.equals("tcpudp", ignoreCase = true) -> {
                checkFields(transport, setOf("\$type", "type", "tcp", "udp"))
                val tcp = transport["tcp"] as? Map<*, *>
                    ?: throw ConfigException("Transport 'tcpudp' missing a 'tcp' branch")
                val udp = transport["udp"] as? Map<*, *>
                    ?: throw ConfigException("Transport 'tcpudp' missing a 'udp' branch")
                val parsedTcp = parseLeaf(tcp, nameHint)
                val parsedUdp = parseLeaf(udp, nameHint)
                if ((parsedUdp.transport.prefix?.size ?: 0) != 0) {
                    throw ConfigException("UDP prefixes are not supported by Outshake")
                }
                if (parsedTcp.transport.copy(prefix = null) != parsedUdp.transport.copy(prefix = null)) {
                    throw ConfigException("Distinct TCP/UDP endpoints, ciphers or secrets are not supported")
                }
                return parsedTcp
            }
            type == null || type.equals("shadowsocks", ignoreCase = true) -> {
                val parsed = parseLeaf(transport, nameHint)
                if ((parsed.transport.prefix?.size ?: 0) != 0) {
                    throw ConfigException("Use tcpudp with a TCP-only prefix; shared UDP prefixes are unsupported")
                }
                return parsed
            }
            else -> throw ConfigException("Unsupported transport type (only shadowsocks is supported)")
        }
    }

    private fun checkFields(map: Map<*, *>, allowed: Set<String>) {
        if (map.keys.any { it !is String || it !in allowed }) {
            throw ConfigException("Unsupported config field; only the documented Shadowsocks subset is accepted")
        }
    }

    // ---------------------------------------------------------------------
    // Prefix handling
    // ---------------------------------------------------------------------

    /** Decode Outline's URL-escaped Latin-1 prefix string, with a legacy raw-byte fallback. */
    fun extractPrefixFromQuery(query: String): ByteArray? {
        if (query.isBlank()) return null
        var prefix: ByteArray? = null
        val seen = HashSet<String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val k = urlDecode(if (eq < 0) pair else pair.substring(0, eq)).lowercase()
            if (!seen.add(k)) throw ConfigException("Duplicate access key query parameter")
            when (k) {
                "prefix" -> {
                    if (eq < 0) throw ConfigException("Prefix query parameter needs a value")
                    val bytes = percentDecodeToBytes(pair.substring(eq + 1))
                    // Outline URL prefixes encode a Latin-1 string through encodeURIComponent.
                    // Retain legacy raw-byte keys only when the byte sequence is not valid UTF-8.
                    val decoded = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    } catch (_: java.nio.charset.CharacterCodingException) { null }
                    prefix = if (decoded == null) bytes else prefixStringToBytes(decoded)
                }
                "outline" -> { /* Outline access-key marker; no transport behavior. */ }
                else -> throw ConfigException("Unsupported access key query parameter (plugins are not supported)")
            }
        }
        return prefix
    }

    /** JSON/YAML prefix string: each character is one byte (ISO-8859-1 / Latin-1). */
    fun prefixStringToBytes(s: String): ByteArray {
        if (s.any { it.code > 255 }) throw ConfigException("Prefix characters must fit in one Latin-1 byte")
        val out = ByteArray(s.length)
        for (i in s.indices) out[i] = (s[i].code and 0xFF).toByte()
        return out
    }

    /** Percent-decode a URL query value into raw bytes (literal chars kept as Latin-1). */
    fun percentDecodeToBytes(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) throw ConfigException("Malformed percent-encoded prefix")
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16) ?: throw ConfigException("Malformed percent-encoded prefix")
                    out.add(byte.toByte())
                    i += 3
                }
                c == '+' -> { out.add(' '.code.toByte()); i++ }
                else -> {
                    if (c.code > 255) throw ConfigException("Prefix characters must fit in one Latin-1 byte")
                    out.add(c.code.toByte()); i++
                }
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun resolveCipher(method: String): Cipher =
        Cipher.fromId(method.trim())
            ?: throw ConfigException("Unsupported cipher (supported: chacha20-ietf-poly1305, aes-256-gcm, aes-128-gcm)")

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        val hp = hostPort.trim()
        // IPv6 literal in brackets: [::1]:8388
        if (hp.startsWith("[")) {
            val close = hp.indexOf(']')
            if (close < 0) throw ConfigException("Malformed IPv6 host: $hp")
            val host = hp.substring(1, close)
            val rest = hp.substring(close + 1)
            if (!rest.startsWith(":")) throw ConfigException("Missing port for host $host")
            return host to parsePort(rest.substring(1))
        }
        val colon = hp.lastIndexOf(':')
        if (colon < 0) throw ConfigException("Missing port in '$hp'")
        val host = hp.substring(0, colon)
        if (host.isBlank()) throw ConfigException("Missing host in '$hp'")
        return host to parsePort(hp.substring(colon + 1))
    }

    private fun parsePort(s: String): Int {
        val p = s.trim().toIntOrNull() ?: throw ConfigException("Invalid port: '$s'")
        if (p !in 1..65535) throw ConfigException("Port out of range: $p")
        return p
    }

    private fun displayName(name: String, host: String, port: Int): String =
        if (name.isNotBlank()) name else "$host:$port"

    private fun firstString(map: Map<*, *>, vararg keys: String): String? {
        val present = keys.filter { map.containsKey(it) }
        if (present.size > 1) throw ConfigException("Ambiguous config field aliases")
        val key = present.singleOrNull() ?: return null
        val value = map[key] as? String ?: throw ConfigException("Config field '$key' must be a string")
        if (value.isBlank() && key != "prefix") {
            throw ConfigException("Config field '$key' must not be empty")
        }
        return value
    }

    private fun firstInt(map: Map<*, *>, vararg keys: String): Int? {
        val present = keys.filter { map.containsKey(it) }
        if (present.size > 1) throw ConfigException("Ambiguous port aliases")
        val key = present.singleOrNull() ?: return null
        val value = map[key]
        val parsed = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in 1..65535 }?.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it in 1..65535 }
            ?: throw ConfigException("Port must be an integer from 1 to 65535")
    }

    private fun urlDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun base64Decode(s: String): ByteArray {
        val cleaned = s.trim().replace("\n", "").replace("\r", "")
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        // Bouncy Castle is already bundled for AEAD and also works on API 24/25.
        return Base64.decode(padded.replace('-', '+').replace('_', '/'))
    }
}
