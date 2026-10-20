package io.github.truongfelix.idenanode

/*
 * The message code: an account's public key, shared by its owner, so that anyone can write to it before it signed a
 * transaction (an address alone does not give the key: it is a hash of it). "idena-key:" then the 65 bytes in hex,
 * uncompressed (04, X, Y), as the node gives an identity's key. A code is used only for the address it hashes to.
 */

/** What a message code starts with. */
const val MESSAGE_CODE_PREFIX = "idena-key:"

/** The message code of the public key [publicKey] (hex of its 65 uncompressed bytes, "0x" optional). */
fun messageCode(publicKey: String): String = MESSAGE_CODE_PREFIX + publicKey.removePrefix("0x").lowercase()

/**
 * The public key a message code [text] holds (any case, spaces and line breaks left out, with or without its prefix
 * and "0x"), as 130 lowercase hex characters starting with 04; null when it holds none. 64 bytes without the 04 are
 * taken too.
 */
fun parseMessageCode(text: String): String? {
    val hex = text.filterNot { it.isWhitespace() }.lowercase().removePrefix(MESSAGE_CODE_PREFIX).removePrefix("0x")
    if (!hex.all { it in "0123456789abcdef" }) return null
    return when {
        hex.length == 130 && hex.startsWith("04") -> hex
        hex.length == 128 -> "04$hex"
        else -> null
    }
}

/** The address (lowercase, "0x") of the uncompressed public key [publicKey] (130 hex): the last 20 bytes of the Keccak-256 of X and Y. */
fun addressOfPublicKey(publicKey: String): String {
    val bytes = publicKey.drop(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return "0x" + keccak256(bytes).takeLast(20).joinToString("") { "%02x".format(it) }
}

/** The public key in the message code [text] when it is [address]'s (any case); null for another account's or none. */
fun messageCodeFor(text: String, address: String): String? =
    parseMessageCode(text)?.takeIf { addressOfPublicKey(it) == address.lowercase() }
