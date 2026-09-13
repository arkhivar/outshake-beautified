package com.outshake.config

/** Local safety policy: retain at least 128 random salt bits, never a fixed per-key salt. */
object PrefixPolicy {
    fun maxBytes(cipher: Cipher): Int = minOf(16, cipher.saltSize - 16)

    fun validate(cipher: Cipher, prefix: ByteArray?) {
        if ((prefix?.size ?: 0) > maxBytes(cipher)) {
            throw ConfigException(
                "Prefix too long for ${cipher.id}: maximum ${maxBytes(cipher)} bytes " +
                    "(16 random salt bytes must remain)"
            )
        }
    }
}
