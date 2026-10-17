package io.github.truongfelix.idenanode

import androidx.annotation.StringRes

/**
 * How many Idena peers the node keeps: incoming and outgoing slots, each split into peers of the node's own
 * shard and peers of other shards (idena-go `protocol/connmanager.go`). Eco has idena-go's low-power numbers,
 * Normal its defaults (the PC's), Hub only more incoming slots, which only nodes that reach the phone through
 * a port open on the router can use. The node takes the level at its start.
 */
enum class PeerLevel(
    @StringRes private val labelId: Int,
    val key: String,
    val inboundOwnShard: Int,
    val inboundOtherShards: Int,
    val outboundOwnShard: Int,
    val outboundOtherShards: Int,
) {
    Eco(R.string.node_peer_level_eco, "eco", 3, 1, 2, 1),
    Normal(R.string.node_peer_level_normal, "normal", 8, 4, 4, 2),
    Hub(R.string.node_peer_level_hub, "hub", 16, 8, 4, 2),
    ;

    val label: String get() = t(labelId)

    val inbound get() = inboundOwnShard + inboundOtherShards
    val outbound get() = outboundOwnShard + outboundOtherShards
    val maxPeers get() = inbound + outbound

    companion object {
        val DEFAULT = Normal

        /** The level a settings file holds; the default when it is missing or holds no offered level. */
        fun parse(text: String?): PeerLevel = entries.firstOrNull { it.key == text?.trim() } ?: DEFAULT
    }
}

/**
 * The limit on the node's IPFS connections (Kubo's connection manager): above [high] it closes the least
 * used ones down to [low]. Idena peers ride on these connections and are closed last. The default is the
 * official node's (idena-go's CLI default, the desktop app's too); [NoLimit] (0, 0) closes none, as the app
 * did before the setting.
 */
enum class IpfsConnections(val high: Int, val low: Int) {
    C20(20, 12),
    C50(50, 30),
    C100(100, 50),
    NoLimit(0, 0),
    ;

    /** The limit, or "No limit". */
    val label: String get() = if (this == NoLimit) t(R.string.node_ipfs_no_limit) else high.toString()

    /**
     * Whether [level] can run with this limit: once trimmed, the connections left must hold all its Idena
     * peers and a few more, from which the node picks new peers.
     */
    fun allows(level: PeerLevel): Boolean = this == NoLimit || low >= level.maxPeers + IPFS_CONNECTIONS_MARGIN

    /** This limit if [level] can run with it, else the next higher one it can. */
    fun atLeastFor(level: PeerLevel): IpfsConnections = entries.drop(ordinal).first { it.allows(level) }

    companion object {
        val DEFAULT = C50

        /** The limit a settings file holds; the default when it is missing or holds no offered limit. */
        fun parse(text: String?): IpfsConnections = entries.firstOrNull { it.high.toString() == text?.trim() } ?: DEFAULT
    }
}

/** The connections a limit keeps beyond the level's Idena peers, once trimmed. */
const val IPFS_CONNECTIONS_MARGIN = 5

/** How long a new IPFS connection is kept before the limit may close it: the official node's (idena-go's CLI default). */
const val IPFS_GRACE_PERIOD = "40s"

val PEER_LEVEL_TITLE: String get() = t(R.string.node_peer_level_title)
val IPFS_CONNECTIONS_TITLE: String get() = t(R.string.node_ipfs_connections_title)

/** What the info of the peer level says. */
val PEER_LEVEL_INFO: String
    get() = t(R.string.node_peer_level_info, PeerLevel.Eco.maxPeers, PeerLevel.Normal.maxPeers, PeerLevel.Hub.maxPeers)

/** What the info of the IPFS connection limit says. */
val IPFS_CONNECTIONS_INFO: String
    get() = t(R.string.node_ipfs_connections_info)
