package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The blocks one bcn_blocksWithAddress call checks (at most api.MaxBlocksWithAddressRange, 100,000): on the
 * phone it reads about 4,000 headers a second (10,000 in 2.3 s, 30,000 in 11.7 s), so a call stays short
 * and "Scan blocks" stops quickly.
 */
private const val FILTER_BATCH = 10_000L

/**
 * The blocks one bcn_contractCalls call reads: the node reads 8 bodies at once, each up to 30 s when it fetches
 * it from its peers, so that one call stays within the node's minute (its HTTP write timeout).
 */
const val BLOCKS_PER_CALL = 8

/**
 * idena.social through the phone's node: bcn_blocksWithAddress picks the blocks with a transaction to a
 * contract version from their headers' transaction filters, bcn_contractCalls reads those calls (sender,
 * method, argument, whether the contract accepted it) from the bodies, which the node fetches from its peers
 * when it does not have them.
 */
class NodeSocialSource(private val rpc: NodeRpc) : SocialSource {
    override fun batchSize(): Long = FILTER_BATCH

    override fun head(): Long = (rpc.call("bcn_lastBlock") as JSONObject).getLong("height")

    override fun calls(from: Long, to: Long, version: SocialVersion): BlockCalls {
        val found = rpc.call(
            "bcn_blocksWithAddress",
            JSONArray().put(JSONObject().put("address", version.address).put("from", from).put("to", to)),
            readTimeoutMs = 70_000,
        ) as? JSONArray ?: JSONArray()
        val heights = (0 until found.length()).map { found.getLong(it) }
        val calls = JSONArray()
        for (chunk in heights.chunked(BLOCKS_PER_CALL)) {
            val page = rpc.call(
                "bcn_contractCalls",
                JSONArray().put(JSONObject().put("contract", version.address).put("heights", JSONArray(chunk))),
                readTimeoutMs = 70_000,
            ) as? JSONArray ?: continue
            for (i in 0 until page.length()) calls.put(page.get(i))
        }
        return callsToActivity(calls, version)
    }

    override fun authors(version: SocialVersion): Map<Long, String> {
        val authors = HashMap<Long, String>()
        val seen = HashSet<String>()
        var token: String? = null
        do {
            val page = rpc.call(
                "contract_iterateMap",
                JSONArray().put(version.address).put("p:").put(token ?: JSONObject.NULL).put("hex").put("hex").put(100),
            ) as? JSONObject ?: break
            page.optJSONArray("items")?.let { authors += postAuthors(it) }
            token = nextPageToken(page.opt("continuationToken"))
            // A token seen before would start the same pages again.
            if (token != null && !seen.add(token)) throw IOException("contract_iterateMap repeats its pages")
        } while (token != null)
        return authors
    }

    /** The identity state of each address (Human, Verified...), in one request. */
    fun identityStates(addresses: List<String>): Map<String, AuthorIdentity> =
        addresses.zip(rpc.batch("dna_identity", addresses.map { JSONArray().put(it) }))
            .associate { (address, identity) ->
                val json = identity as? JSONObject
                address to AuthorIdentity(json?.optString("state") ?: "", json?.optInt("age") ?: 0)
            }

    /** The state, age and stake of [address]'s identity. */
    fun identity(address: String): ProfileIdentity =
        ProfileIdentity.fromJson(rpc.call("dna_identity", JSONArray().put(address)) as JSONObject)
}

/** The fee of a post as the node estimates it, and the most it may cost (the transaction's max fee). */
data class PostFee(val fee: BigDecimal, val maxFee: BigDecimal)

/**
 * A call of the idena.social contract: [method] with its one JSON [argument] and [amount] iDNA sent, as
 * idena.social makes them (a post sends [SocialPosting.POST_AMOUNT], a tip the tipped amount).
 */
data class SocialCall(val method: String, val argument: String, val amount: String)

/** Calling idena.social with the node's key: posts (makePost) and tips (sendTip), then their blocks. */
class SocialPosting(private val rpc: NodeRpc) : TxLookup {
    private fun callArgs(from: String, call: SocialCall, maxFee: BigDecimal?): JSONArray {
        val args = JSONObject()
            .put("from", from)
            .put("contract", SOCIAL_CONTRACT)
            .put("method", call.method)
            .put("amount", call.amount)
            .put("args", JSONArray().put(JSONObject().put("index", 0).put("format", "string").put("value", call.argument)))
        maxFee?.let { args.put("maxFee", it.toPlainString()) }
        return JSONArray().put(args)
    }

    /**
     * The fee of posting [argument] from [from], from a dry run of the call by the node. The max fee is
     * twice the estimate, as the node's own margin for plain transactions: the gas price may change
     * before the post is in a block, and only the fee actually used is taken. Throws with the node's
     * reason (e.g. insufficient funds) or the contract's.
     */
    fun estimate(from: String, call: SocialCall): PostFee {
        val receipt = rpc.call("contract_estimateCall", callArgs(from, call, null)) as JSONObject
        if (!receipt.optBoolean("success")) throw IOException(receipt.optString("error").ifEmpty { "the contract refused the post" })
        val fee = BigDecimal(receipt.optString("txFee", "0")) + BigDecimal(receipt.optString("gasCost", "0"))
        return PostFee(fee, (fee * BigDecimal(2)).setScale(6, RoundingMode.UP))
    }

    /** Sends the call; returns the transaction hash. */
    fun send(from: String, call: SocialCall, maxFee: BigDecimal): String =
        rpc.call("contract_call", callArgs(from, call, maxFee)) as String

    /** The spendable balance of [address] in iDNA. */
    fun balance(address: String): BigDecimal =
        BigDecimal((rpc.call("dna_getBalance", JSONArray().put(address)) as JSONObject).optString("balance", "0"))

    /** The network's fee per gas, in the smallest unit (10^-18 iDNA). */
    fun feePerGas(): BigDecimal = BigDecimal(rpc.call("bcn_feePerGas").toString())

    /** The CID [bytes] get once added to IPFS, computed by the node without adding them. */
    fun cid(bytes: ByteArray): String = rpc.call("ipfs_cid", JSONArray().put(bytesToHex(bytes))) as String

    /**
     * The fee of storing [size] bytes under [cid] on IPFS from [from] (a storeToIpfs transaction), from a
     * dry run by the node, which also checks the balance. Throws with the node's reason.
     */
    fun estimateStore(from: String, cid: String, size: Int): BigDecimal {
        val args = JSONObject().put("type", STORE_TO_IPFS_TX).put("from", from).put("payload", bytesToHex(storeToIpfsPayload(cid, size)))
        val result = rpc.call("bcn_estimateTx", JSONArray().put(args)) as JSONObject
        return BigDecimal(result.optString("txFee", "0"))
    }

    /** Adds [bytes] to the node's IPFS, pinned; returns their CID. */
    fun add(bytes: ByteArray): String =
        rpc.call("ipfs_add", JSONArray().put(bytesToHex(bytes)).put(true), readTimeoutMs = 180_000) as String

    /**
     * Sends the storeToIpfs transaction of [cid] (added before): the network's nodes keep the file. The node
     * sets the nonce and a max fee of twice the fee. Returns the transaction hash.
     */
    fun store(cid: String): String = rpc.call("dna_storeToIpfs", JSONArray().put(JSONObject().put("cid", cid))) as String

    override fun transaction(hash: String): JSONObject? = rpc.call("bcn_transaction", JSONArray().put(hash)) as? JSONObject

    override fun blockHeight(blockHash: String): Long =
        (rpc.call("bcn_block", JSONArray().put(blockHash)) as? JSONObject)?.getLong("height") ?: throw IOException("block $blockHash is missing")

    override fun receipt(hash: String): JSONObject? = rpc.call("bcn_txReceipt", JSONArray().put(hash)) as? JSONObject

    companion object {
        /** idena.social sends this amount with each post (the contract passes it on in its event). */
        const val POST_AMOUNT = "0.00001"

        fun post(argument: String) = SocialCall("makePost", argument, POST_AMOUNT)

        fun tip(postId: Long, amount: Long) = SocialCall("sendTip", sendTipArgument(postId, amount), amount.toString())

        /** The type of a storeToIpfs transaction (types.StoreToIpfsTx). */
        private const val STORE_TO_IPFS_TX = 0x15
    }
}

/** The scan kept in the app's files ([SocialCache]) and the "Scan blocks" choice. */
object SocialStore {
    private fun dir(context: Context): File = File(context.filesDir, "social").also { it.mkdirs() }
    private fun cacheFile(context: Context) = File(dir(context), "idena-social.json")
    private fun scanOffFile(context: Context) = File(dir(context), "scan_off")

    fun load(context: Context): SocialCache? = runCatching {
        SocialCache.fromJson(JSONObject(cacheFile(context).readText()))
    }.getOrNull()

    /** Writes a new file and renames it over the old one, so a stop mid-write keeps the previous scan. */
    fun save(context: Context, cache: SocialCache) {
        val file = cacheFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(cache.toJson().toString())
        if (!tmp.renameTo(file)) throw IOException("cannot save the scan")
    }

    /** "Scan blocks": on until unselected. */
    fun isScanning(context: Context): Boolean = !scanOffFile(context).exists()

    fun setScanning(context: Context, enabled: Boolean) {
        val file = scanOffFile(context)
        if (enabled) file.delete() else file.createNewFile()
    }

    private fun notifyOffFile(context: Context) = File(dir(context), "notify_off")
    private fun seenFile(context: Context) = File(dir(context), "activity_seen")

    /** The kinds of activity that notify: all at first; the file lists those turned off. */
    fun notifyKinds(context: Context): Set<NotifyKind> {
        val off = runCatching { notifyOffFile(context).readLines() }.getOrDefault(emptyList()).toSet()
        return NotifyKind.entries.filter { it.name !in off }.toSet()
    }

    fun setNotifyKinds(context: Context, kinds: Set<NotifyKind>) {
        notifyOffFile(context).writeText(NotifyKind.entries.filter { it !in kinds }.joinToString("\n") { it.name })
    }

    /** The block through which the Activity was seen; null before the first scan. */
    fun seenThrough(context: Context): Long? = runCatching { seenFile(context).readText().trim().toLong() }.getOrNull()

    fun setSeenThrough(context: Context, height: Long) {
        seenFile(context).writeText(height.toString())
    }

    private fun peopleFile(context: Context) = File(dir(context), "people.json")

    /** The People list ([Person]); empty when there is none yet. */
    fun people(context: Context): Map<String, Person> =
        runCatching { peopleFromJson(JSONObject(peopleFile(context).readText())) }.getOrDefault(emptyMap())

    /** Writes a new file and renames it over the old one, as [save]. */
    fun setPeople(context: Context, people: Map<String, Person>) {
        val file = peopleFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(peopleToJson(people).toString())
        if (!tmp.renameTo(file)) throw IOException("cannot save the People list")
    }
}

/**
 * Texts and media idena.social keeps on IPFS, fetched only through the phone's own node (ipfs_get, which
 * asks the node's IPFS peers) and kept in the app's files. Content no node could give is marked
 * unavailable until asked again ([retry]); a node that does not answer marks nothing.
 */
object IpfsStore {
    private fun dir(context: Context): File = File(context.filesDir, "social/ipfs").also { it.mkdirs() }

    /** The content of [cid] kept on the phone, or null. [cid] comes from [ipfsCid]: letters and digits. */
    fun cached(context: Context, cid: String): ByteArray? = File(dir(context), cid).takeIf { it.exists() }?.readBytes()

    fun isUnavailable(context: Context, cid: String): Boolean = File(dir(context), "$cid.unavailable").exists()

    /** Keeps [bytes], just added to IPFS as [cid], so that the post shows them without asking the node. */
    fun put(context: Context, cid: String, bytes: ByteArray) {
        if (ipfsCid("ipfs://$cid") == null) return
        val file = File(dir(context), cid)
        val tmp = File(file.parentFile, "$cid.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    /** Forgets that [cid] was unavailable, so that it is fetched again. */
    fun retry(context: Context, cid: String) {
        File(dir(context), "$cid.unavailable").delete()
    }

    /**
     * Fetches [cid] through the node: the content, or null when the node could not find it on IPFS (then
     * marked unavailable). Throws when the node does not answer.
     */
    fun fetch(context: Context, cid: String): ByteArray? {
        val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
        val hex = try {
            // The node gives up by itself when no IPFS peer has the content.
            rpc.call("ipfs_get", JSONArray().put(cid), readTimeoutMs = 120_000) as? String
        } catch (e: IOException) {
            // An RPC error names IPFS ("timeout while reading data from ipfs"); a closed port does not.
            if (e.message?.contains("ipfs", ignoreCase = true) != true) throw e
            null
        }
        if (hex.isNullOrEmpty() || hex == "0x") {
            File(dir(context), "$cid.unavailable").createNewFile()
            return null
        }
        val bytes = hexToBytes(hex)
        val file = File(dir(context), cid)
        val tmp = File(file.parentFile, "$cid.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        return bytes
    }
}
