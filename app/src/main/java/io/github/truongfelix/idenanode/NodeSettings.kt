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

    private fun apiKeyFile(context: Context) = File(nodeDir(context), "rpc_key")
    private fun syncEnabledFile(context: Context) = File(nodeDir(context), "sync_enabled")

    /** A random key for the node RPC, created once. Other apps can reach loopback ports too. */
    @Synchronized
    fun apiKey(context: Context): String {
        val file = apiKeyFile(context)
        if (file.exists()) return file.readText().trim()
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(key)
        tmp.renameTo(file)
        return key
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

    /**
     * The node configuration, applied over the mobile defaults: RPC on loopback with the app's key and
     * no browser access (no CORS), and no IPFS "server" profile, whose address filters block peers on
     * the home network. Peer limits stay the default ones (not the low-power profile).
     */
    fun nodeConfig(context: Context): String = JSONObject()
        .put(
            "RPC", JSONObject()
                .put("HTTPHost", "127.0.0.1")
                .put("HTTPPort", RPC_PORT)
                .put("APIKey", apiKey(context))
                .put("HTTPCors", JSONArray())
        )
        .put("IpfsConf", JSONObject().put("Profile", ""))
        .toString()
}
