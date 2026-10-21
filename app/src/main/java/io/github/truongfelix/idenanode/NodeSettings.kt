package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Where the node keeps its data and how the app configures and reaches it. The switch state and the
 * RPC key are small files rather than SharedPreferences, which each process caches: the screen and
 * the node process must see each other's changes.
 */
object NodeSettings {
    /** The RPC port of the node, on the loopback interface only. */
    const val RPC_PORT = 29109

    /** The directory given to the node; it keeps its data in `datadir` inside it. */
    fun nodeDir(context: Context): File = File(context.filesDir, "node").also { it.mkdirs() }

    fun hasKey(context: Context): Boolean = NodeKeys.files(context).hasKey()

    /** The last error of a node start, written by the node process for the screen. */
    fun startErrorFile(context: Context): File = File(nodeDir(context), "start_error.txt")

    /** When the service last started the node (milliseconds since 1970), written by the service. */
    fun startedAtFile(context: Context): File = File(nodeDir(context), "started_at")

    /** How long ago the service started the node; null when unknown. */
    fun runningForMs(context: Context): Long? =
        runCatching { System.currentTimeMillis() - startedAtFile(context).readText().trim().toLong() }.getOrNull()?.takeIf { it >= 0 }

    private fun apiKeyFile(context: Context) = File(nodeDir(context), "rpc_key")
    private fun syncEnabledFile(context: Context) = File(nodeDir(context), "sync_enabled")
    private fun writeBufferFile(context: Context, database: NodeDatabase) =
        File(nodeDir(context), filePrefix(database) + "write_buffer_mib")
    private fun runningWriteBufferFile(context: Context, database: NodeDatabase) =
        File(nodeDir(context), filePrefix(database) + "write_buffer_running")
    private fun peerLevelFile(context: Context) = File(nodeDir(context), "peer_level")
    private fun runningPeerLevelFile(context: Context) = File(nodeDir(context), "peer_level_running")
    private fun ipfsConnectionsFile(context: Context) = File(nodeDir(context), "ipfs_connections")
    private fun runningIpfsConnectionsFile(context: Context) = File(nodeDir(context), "ipfs_connections_running")
    private fun directPeersFile(context: Context) = File(nodeDir(context), "direct_peers")
    private fun runningDirectPeersFile(context: Context) = File(nodeDir(context), "direct_peers_running")
    private fun ownPeerNameFile(context: Context) = File(nodeDir(context), "own_peer_name")
    private fun nodePortFile(context: Context) = File(nodeDir(context), "node_port")
    private fun runningNodePortFile(context: Context) = File(nodeDir(context), "node_port_running")

    // The chain database's files keep the names they had before the IPFS database got its own setting.
    private fun filePrefix(database: NodeDatabase) = when (database) {
        NodeDatabase.Chain -> ""
        NodeDatabase.Ipfs -> "ipfs_"
    }

    /** A random key for the node RPC, created once. Other apps can reach loopback ports too. */
    @Synchronized
    fun apiKey(context: Context): String {
        val file = apiKeyFile(context)
        if (file.exists()) return file.readText().trim()
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        writeAtomically(file, key)
        return key
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    /** Marks that the answers of a session of [epoch] reached the node, so the screen does not ask again. */
    fun answersSentFile(context: Context, epoch: Int, short: Boolean): File =
        File(nodeDir(context), "answers_sent_${epoch}_${if (short) "short" else "long"}")

    /** Whether the user switched sync on. The node process checks it when Android restarts it. */
    fun isSyncEnabled(context: Context): Boolean = syncEnabledFile(context).exists()

    fun setSyncEnabled(context: Context, enabled: Boolean) {
        val file = syncEnabledFile(context)
        if (enabled) file.createNewFile() else file.delete()
    }

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    /** The settings chosen by the user ([NodeOptions]); the defaults until chosen. */
    fun options(context: Context): NodeOptions {
        val peerLevel = PeerLevel.parse(read(peerLevelFile(context)))
        return NodeOptions(
            writeBuffers = NodeDatabase.entries.associateWith { WriteBuffer.parse(read(writeBufferFile(context, it))) },
            peerLevel = peerLevel,
            ipfsConnections = IpfsConnections.parse(read(ipfsConnectionsFile(context))).atLeastFor(peerLevel),
            directPeers = parseDirectPeers(read(directPeersFile(context))),
            nodePort = parseNodePort(read(nodePortFile(context))),
        )
    }

    fun setNodePort(context: Context, port: Int) = writeAtomically(nodePortFile(context), port.toString())

    /**
     * The port the router opening forwards: the one the node runs on, else (no start recorded it yet) the one
     * chosen.
     */
    fun routerPort(context: Context): Int =
        read(runningNodePortFile(context))?.let { parseNodePort(it) } ?: parseNodePort(read(nodePortFile(context)))

    fun setDirectPeers(context: Context, peers: List<DirectPeer>) =
        writeAtomically(directPeersFile(context), directPeersJson(peers))

    /** The name the user puts in the node's own peer code; empty until chosen. */
    fun ownPeerName(context: Context): String = cleanPeerName(read(ownPeerNameFile(context)).orEmpty())

    fun setOwnPeerName(context: Context, name: String) = writeAtomically(ownPeerNameFile(context), cleanPeerName(name))

    fun setWriteBuffer(context: Context, database: NodeDatabase, value: WriteBuffer) =
        writeAtomically(writeBufferFile(context, database), value.mib.toString())

    /** Sets the peer level, and raises the IPFS connection limit if the level cannot run with the one chosen. */
    fun setPeerLevel(context: Context, value: PeerLevel) {
        writeAtomically(peerLevelFile(context), value.key)
        val limit = IpfsConnections.parse(read(ipfsConnectionsFile(context)))
        if (!limit.allows(value)) setIpfsConnections(context, limit.atLeastFor(value))
    }

    fun setIpfsConnections(context: Context, value: IpfsConnections) =
        writeAtomically(ipfsConnectionsFile(context), value.high.toString())

    /** The settings of the node's last start, written by the service; a setting is null before a start recorded it. */
    fun runningOptions(context: Context): RunningOptions = RunningOptions(
        writeBuffers = NodeDatabase.entries.mapNotNull { database ->
            read(runningWriteBufferFile(context, database))?.let { database to WriteBuffer.parse(it) }
        }.toMap(),
        peerLevel = read(runningPeerLevelFile(context))?.let { PeerLevel.parse(it) },
        ipfsConnections = read(runningIpfsConnectionsFile(context))?.let { IpfsConnections.parse(it) },
        directPeers = read(runningDirectPeersFile(context))?.let { text -> parseDirectPeers(text).map { it.id } },
        nodePort = read(runningNodePortFile(context))?.let { parseNodePort(it) },
    )

    /** Records [options] as the node's start settings, for the screen to compare with the user's choices. */
    fun setRunningOptions(context: Context, options: NodeOptions) {
        options.writeBuffers.forEach { (database, size) ->
            runCatching { writeAtomically(runningWriteBufferFile(context, database), size.mib.toString()) }
        }
        runCatching { writeAtomically(runningPeerLevelFile(context), options.peerLevel.key) }
        runCatching { writeAtomically(runningIpfsConnectionsFile(context), options.ipfsConnections.high.toString()) }
        runCatching { writeAtomically(runningDirectPeersFile(context), directPeersJson(options.directPeers)) }
        runCatching { writeAtomically(runningNodePortFile(context), options.nodePort.toString()) }
    }

    /**
     * The node configuration, applied over the mobile defaults: RPC on loopback with the app's key and
     * no browser access (no CORS), and no IPFS "server" profile, whose address filters block peers on
     * the home network. The IPFS port stays fixed: otherwise the node moves to the next port after two
     * minutes without IPFS peers (a Wi-Fi outage), and a port opened for it on the router no longer
     * matches. The write buffers of the chain and IPFS databases, the peer level, the IPFS connection
     * limit and the direct peers are the user's choice ([NodeOptions]).
     */
    fun nodeConfig(context: Context, options: NodeOptions = options(context)): String = nodeConfig(apiKey(context), options)

    /** [nodeConfig] from its parts. */
    internal fun nodeConfig(apiKey: String, options: NodeOptions): String {
        val level = options.peerLevel
        val limit = options.ipfsConnections.atLeastFor(level)
        return JSONObject()
            .put(
                "RPC", JSONObject()
                    .put("HTTPHost", "127.0.0.1")
                    .put("HTTPPort", RPC_PORT)
                    .put("APIKey", apiKey)
                    .put("HTTPCors", JSONArray())
            )
            .put(
                "P2P", JSONObject()
                    .put("MaxInboundOwnShardPeers", level.inboundOwnShard)
                    .put("MaxInboundPeers", level.inboundOtherShards)
                    .put("MaxOutboundOwnShardPeers", level.outboundOwnShard)
                    .put("MaxOutboundPeers", level.outboundOtherShards)
                    .put("DirectPeers", JSONArray(configDirectPeers(options.directPeers)))
            )
            .put(
                "IpfsConf", JSONObject()
                    .put("Profile", "")
                    .put("IpfsPort", options.nodePort)
                    .put("StaticPort", true)
                    .put("DatastoreWriteBufferMiB", (options.writeBuffers[NodeDatabase.Ipfs] ?: WriteBuffer.DEFAULT).mib)
                    .put("LowWater", limit.low)
                    .put("HighWater", limit.high)
                    .put("GracePeriod", IPFS_GRACE_PERIOD)
            )
            .put("Database", JSONObject().put("WriteBufferMiB", (options.writeBuffers[NodeDatabase.Chain] ?: WriteBuffer.DEFAULT).mib))
            .toString()
    }
}

/** The settings the node takes at its start, in the Node card's Advanced settings. */
data class NodeOptions(
    val writeBuffers: Map<NodeDatabase, WriteBuffer> = NodeDatabase.entries.associateWith { WriteBuffer.DEFAULT },
    val peerLevel: PeerLevel = PeerLevel.DEFAULT,
    val ipfsConnections: IpfsConnections = IpfsConnections.DEFAULT,
    val directPeers: List<DirectPeer> = emptyList(),
    /** The node's IPFS port, one of [NODE_PORTS]. */
    val nodePort: Int = DEFAULT_NODE_PORT,
)

/** The [NodeOptions] of the node's last start; a setting is null when no start recorded it (an older app). */
data class RunningOptions(
    val writeBuffers: Map<NodeDatabase, WriteBuffer> = emptyMap(),
    val peerLevel: PeerLevel? = null,
    val ipfsConnections: IpfsConnections? = null,
    /** The peer ids of the direct peers. */
    val directPeers: List<String>? = null,
    val nodePort: Int? = null,
)
