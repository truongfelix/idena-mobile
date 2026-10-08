package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Direct peers: up to [MAX_DIRECT_PEERS] nodes the user names, which the node keeps connected on top of its peer
 * slots (idena-go `P2P.DirectPeers`: accepted with no free slot, never dropped to make room, redialed when the
 * connection drops). A node is named by its IPFS peer id; the node finds its address by a DHT lookup.
 *
 * Users exchange them as a peer code, one line: `idena-peer:<peer id>#<name>`, the name optional.
 */
data class DirectPeer(val id: String, val name: String)

const val MAX_DIRECT_PEERS = 3
const val DIRECT_PEERS_TITLE = "Direct peers"
private const val CODE_PREFIX = "idena-peer:"
private const val MAX_NAME_LENGTH = 40

val DIRECT_PEERS_INFO =
    "Nodes this node keeps as peers on top of its peer level, up to $MAX_DIRECT_PEERS: a friend's desktop, your " +
        "own computer. The node accepts them even when its slots are full, never drops them to make room, and " +
        "connects again when the connection drops.\n\n" +
        "Share your node's code with the other person, and add theirs here. Both sides should add each other: the " +
        "side that adds the other always accepts it. On mobile data no node can reach this phone, so the other node " +
        "must be reachable (its router port open) for the two to connect.\n\n" +
        "The node takes the list at its start."

/** The code that names a node: [id] and, when not blank, [name]. */
fun peerCode(id: String, name: String): String =
    CODE_PREFIX + id + cleanPeerName(name).let { if (it.isEmpty()) "" else "#$it" }

/**
 * The node a pasted text names: a peer code, or a bare peer id. Null when it names none (the node refuses a list
 * with an invalid entry at its start, so only a valid peer id gets through).
 */
fun parsePeerCode(text: String): DirectPeer? {
    val line = text.trim()
    val body = if (line.startsWith(CODE_PREFIX, ignoreCase = true)) line.substring(CODE_PREFIX.length) else line
    val id = body.substringBefore('#').trim()
    val name = if ('#' in body) cleanPeerName(body.substringAfter('#')) else ""
    return if (isPeerId(id)) DirectPeer(id, name) else null
}

/** A name for a code or the list: one line (a line break or tab reads as a space), at most [MAX_NAME_LENGTH] characters. */
fun cleanPeerName(name: String): String =
    name.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_NAME_LENGTH).trim()

/** Why [candidate] cannot join [list] next to [ownId], or null when it can. */
fun directPeerProblem(candidate: DirectPeer, list: List<DirectPeer>, ownId: String?): String? = when {
    candidate.id == ownId -> "This is your own node's code."
    list.any { it.id == candidate.id } -> "This node is already in the list."
    list.size >= MAX_DIRECT_PEERS -> "The list holds at most $MAX_DIRECT_PEERS nodes."
    else -> null
}

/** The node's own peer id from `net_ipfsAddress` (`/ip4/0.0.0.0/tcp/40405/ipfs/<peer id>`); null if absent. */
fun ownPeerId(ipfsAddress: String?): String? {
    val last = ipfsAddress?.trim()?.substringAfterLast('/') ?: return null
    return last.takeIf { isPeerId(it) }
}

/** The peer ids in `net_peers`' answer (`[{"id", "addr", "direct"}]`). */
fun peerIds(peers: JSONArray?): Set<String> =
    if (peers == null) emptySet() else (0 until peers.length()).mapNotNull { peers.optJSONObject(it)?.optString("id")?.ifEmpty { null } }.toSet()

/** A peer id shortened for a list row: its start and its end. */
fun shortPeerId(id: String): String = if (id.length <= 16) id else id.take(6) + "…" + id.takeLast(6)

/**
 * Whether [id] is a libp2p peer id as idena-go's nodes have them: base58 of a sha2-256 multihash (`Qm…`, RSA keys)
 * or of an identity multihash holding a short key (`12D3Koo…`, ed25519). Stricter than idena-go's check, which
 * takes any consistent multihash, so a list the app writes never stops the node's start.
 */
fun isPeerId(id: String): Boolean {
    val bytes = decodeBase58(id) ?: return false
    if (bytes.size < 2) return false
    val code = bytes[0].toInt() and 0xff
    val length = bytes[1].toInt() and 0xff
    return when (code) {
        0x12 -> length == 32 && bytes.size == 34
        0x00 -> length in 1..42 && bytes.size == 2 + length
        else -> false
    }
}

/** The ids for the node's `P2P.DirectPeers`: valid ones only, each once, at most [MAX_DIRECT_PEERS]. */
fun configDirectPeers(peers: List<DirectPeer>): List<String> =
    peers.map { it.id }.filter { isPeerId(it) }.distinct().take(MAX_DIRECT_PEERS)

/** The list as the app stores it: a JSON array of `{"id", "name"}`. */
fun directPeersJson(peers: List<DirectPeer>): String =
    JSONArray(peers.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()

/** The stored list; entries that are not a valid peer id are dropped, and an unreadable file reads as empty. */
fun parseDirectPeers(text: String?): List<DirectPeer> {
    val array = runCatching { JSONArray(text ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let { DirectPeer(it.optString("id"), cleanPeerName(it.optString("name"))) }
    }.filter { isPeerId(it.id) }.distinctBy { it.id }.take(MAX_DIRECT_PEERS)
}

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** Base58 (Bitcoin alphabet) to bytes; null for an empty text or a character outside the alphabet. */
internal fun decodeBase58(text: String): ByteArray? {
    if (text.isEmpty()) return null
    var value = BigInteger.ZERO
    val base = BigInteger.valueOf(58)
    for (c in text) {
        val digit = BASE58_ALPHABET.indexOf(c)
        if (digit < 0) return null
        value = value.multiply(base).add(BigInteger.valueOf(digit.toLong()))
    }
    val magnitude = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    val leadingZeros = text.takeWhile { it == '1' }.length
    val body = if (value == BigInteger.ZERO) ByteArray(0) else magnitude
    return ByteArray(leadingZeros) + body
}
