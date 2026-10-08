package io.github.truongfelix.idenanode

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A sent transaction in a block: the block's [height], and the contract's [error] when it refused the call. */
data class InBlock(val height: Long, val error: String?)

/** What [waitForBlock] asks the node about a sent transaction. */
interface TxLookup {
    /** The transaction (bcn_transaction), or null when the node does not know it. */
    fun transaction(hash: String): JSONObject?

    /** The height of block [blockHash]. */
    fun blockHeight(blockHash: String): Long

    /** The receipt of a contract call (bcn_txReceipt), or null without one. */
    fun receipt(hash: String): JSONObject?
}

/** How often a sent transaction is looked up until it is in a block. */
const val BLOCK_POLL_MS = 10_000L

/** Lookups in a row that find nothing before a sent transaction counts as dropped by the node. */
const val UNKNOWN_LOOKUPS = 3

/**
 * Waits until the node has the transaction [hash] in a block, looking it up every [BLOCK_POLL_MS]: its block
 * and the contract's verdict, or null once the node no longer knows it ([UNKNOWN_LOOKUPS] lookups in a row
 * find nothing). A lookup that fails (the node restarting) is tried again. The lookups block: run it on an
 * IO thread.
 */
suspend fun waitForBlock(lookup: TxLookup, hash: String, pause: suspend (Long) -> Unit = { delay(it) }): InBlock? {
    var unknown = 0
    while (true) {
        try {
            val tx = lookup.transaction(hash)
            if (tx == null) {
                unknown++
                if (unknown >= UNKNOWN_LOOKUPS) return null
            } else {
                unknown = 0
                val blockHash = tx.optString("blockHash")
                if (blockHash.removePrefix("0x").any { it != '0' }) {
                    val height = lookup.blockHeight(blockHash)
                    val receipt = runCatching { lookup.receipt(hash) }.getOrNull()
                    val error = receipt?.takeUnless { it.optBoolean("success", true) }?.let { it.optString("error").ifEmpty { "the contract refused it" } }
                    return InBlock(height, error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The node restarting, say: asked again.
            unknown = 0
        }
        pause(BLOCK_POLL_MS)
    }
}

/** [TxLookup] through the node's RPC. */
class NodeTxLookup(private val rpc: NodeRpc) : TxLookup {
    override fun transaction(hash: String): JSONObject? = rpc.call("bcn_transaction", JSONArray().put(hash)) as? JSONObject

    override fun blockHeight(blockHash: String): Long =
        (rpc.call("bcn_block", JSONArray().put(blockHash)) as? JSONObject)?.getLong("height") ?: throw IOException("block $blockHash is missing")

    override fun receipt(hash: String): JSONObject? = rpc.call("bcn_txReceipt", JSONArray().put(hash)) as? JSONObject
}

/** How a screen ends its message when the node no longer has a sent transaction ([waitUntilMined] false). */
const val NOT_SENT = "the node no longer has the transaction. Try again."

/**
 * Waits until the node has the transaction [hash] in a block ([waitForBlock]): true then, false once the node
 * no longer has it (it never came in a block).
 */
internal suspend fun waitUntilMined(context: Context, hash: String): Boolean = withContext(Dispatchers.IO) {
    waitForBlock(NodeTxLookup(NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))), hash) != null
}
