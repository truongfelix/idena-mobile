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

    fun hasKey(context: Context): Boolean =
        File(nodeDir(context), "datadir/keystore/nodekey").exists()

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

    /** The write buffer of [database] chosen by the user; the default until one is chosen. */
    fun writeBuffer(context: Context, database: NodeDatabase): WriteBuffer =
        WriteBuffer.parse(runCatching { writeBufferFile(context, database).readText() }.getOrNull())

    /** The write buffers chosen for every database. */
    fun writeBuffers(context: Context): Map<NodeDatabase, WriteBuffer> =
        NodeDatabase.entries.associateWith { writeBuffer(context, it) }

    fun setWriteBuffer(context: Context, database: NodeDatabase, value: WriteBuffer) =
        writeAtomically(writeBufferFile(context, database), value.mib.toString())

    /** The write buffer of [database] at the node's last start, written by the service; null before the first. */
    fun runningWriteBuffer(context: Context, database: NodeDatabase): WriteBuffer? =
        runCatching { runningWriteBufferFile(context, database).readText() }.getOrNull()?.let { WriteBuffer.parse(it) }

    fun setRunningWriteBuffer(context: Context, database: NodeDatabase, value: WriteBuffer) =
        writeAtomically(runningWriteBufferFile(context, database), value.mib.toString())

    /**
     * The node configuration, applied over the mobile defaults: RPC on loopback with the app's key and
     * no browser access (no CORS), and no IPFS "server" profile, whose address filters block peers on
     * the home network. The IPFS port stays fixed: otherwise the node moves to the next port after two
     * minutes without IPFS peers (a Wi-Fi outage), and a port opened for it on the router no longer
     * matches. Peer limits stay the default ones (not the low-power profile). The write buffers of the chain
     * and IPFS databases are the user's choice.
     */
    fun nodeConfig(context: Context, writeBuffers: Map<NodeDatabase, WriteBuffer> = writeBuffers(context)): String =
        nodeConfig(
            apiKey(context),
            writeBuffers[NodeDatabase.Chain] ?: WriteBuffer.DEFAULT,
            writeBuffers[NodeDatabase.Ipfs] ?: WriteBuffer.DEFAULT,
        )

    /** [nodeConfig] from its parts. */
    internal fun nodeConfig(apiKey: String, chainBuffer: WriteBuffer, ipfsBuffer: WriteBuffer): String = JSONObject()
        .put(
            "RPC", JSONObject()
                .put("HTTPHost", "127.0.0.1")
                .put("HTTPPort", RPC_PORT)
                .put("APIKey", apiKey)
                .put("HTTPCors", JSONArray())
        )
        .put(
            "IpfsConf", JSONObject()
                .put("Profile", "")
                .put("StaticPort", true)
                .put("DatastoreWriteBufferMiB", ipfsBuffer.mib)
        )
        .put("Database", JSONObject().put("WriteBufferMiB", chainBuffer.mib))
        .toString()
}
