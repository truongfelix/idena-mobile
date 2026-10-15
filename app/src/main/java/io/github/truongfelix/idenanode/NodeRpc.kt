package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.math.BigDecimal
import java.net.URL

/** The mainnet god address (config.DefaultGodAddress); it takes part in the staking reward. */
private const val GOD_ADDRESS = "0x4d60dc6a2cba8c3ef1ba5e1eba5c12c54cee6b61"

/** A JSON-RPC client for the app's own node. Calls block: use them off the main thread. */
class NodeRpc(private val port: Int, private val apiKey: String) {

    fun call(method: String, params: JSONArray = JSONArray(), readTimeoutMs: Int = 10_000): Any? {
        val conn = URL("http://127.0.0.1:$port").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2_000
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", method)
                .put("params", params)
                .put("key", apiKey)
            conn.outputStream.use { it.write(request.toString().toByteArray()) }
            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val error = response.optJSONObject("error")
            if (error != null) throw IOException(error.optString("message", "RPC error"))
            return if (response.isNull("result")) null else response.get("result")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Calls [method] once per entry of [params] in one request (a JSON-RPC batch) and returns the results
     * in the same order. Throws if any call fails.
     */
    fun batch(method: String, params: List<JSONArray>): List<Any?> {
        if (params.isEmpty()) return emptyList()
        val conn = URL("http://127.0.0.1:$port").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2_000
            // Block bodies the node does not have come from its peers.
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val requests = JSONArray()
            params.forEachIndexed { id, p ->
                requests.put(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", p).put("key", apiKey))
            }
            conn.outputStream.use { it.write(requests.toString().toByteArray()) }
            val responses = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val results = arrayOfNulls<Any?>(params.size)
            for (i in 0 until responses.length()) {
                val response = responses.getJSONObject(i)
                val error = response.optJSONObject("error")
                if (error != null) throw IOException(error.optString("message", "RPC error"))
                results[response.getInt("id")] = if (response.isNull("result")) null else response.get("result")
            }
            return results.toList()
        } finally {
            conn.disconnect()
        }
    }

    /** Reads what the screen shows. Throws if the node does not answer. */
    fun status(): NodeStatus {
        val sync = call("bcn_syncing") as JSONObject
        val peerList = call("net_peers") as? JSONArray
        val peers = peerList?.length() ?: 0
        val lastBlock = call("bcn_lastBlock") as JSONObject
        val stateBlock = lastBlock.optLong("height")
        val address = call("dna_getCoinbaseAddr") as String
        val identity = call("dna_identity") as JSONObject
        val epoch = call("dna_epoch") as JSONObject
        val balance = call("dna_getBalance", JSONArray().put(address)) as JSONObject
        return NodeStatus(
            address = address,
            currentBlock = sync.optLong("currentBlock"),
            highestBlock = sync.optLong("highestBlock"),
            stateBlock = stateBlock,
            syncing = sync.optBoolean("syncing"),
            wrongTime = sync.optBoolean("wrongTime"),
            peers = peers,
            identityState = identity.optString("state"),
            age = identity.optInt("age"),
            online = identity.optBoolean("online"),
            delegatee = identity.optString("delegatee").takeUnless { identity.isNull("delegatee") || it.isEmpty() },
            isPool = identity.optBoolean("isPool"),
            stake = balance.optString("stake"),
            balance = balance.optString("balance"),
            nextValidation = epoch.optString("nextValidation"),
            period = epoch.optString("currentPeriod"),
            requiredFlips = identity.optInt("requiredFlips"),
            madeFlips = identity.optInt("madeFlips"),
            availableFlips = identity.optInt("availableFlips"),
            lockedStake = identity.optString("lockedStake"),
            epoch = epoch.optInt("epoch"),
            delegationEpoch = identity.optInt("delegationEpoch"),
            penaltySeconds = identity.optLong("penaltySeconds"),
            invites = identity.optInt("invites"),
            invitees = parseInvitees(identity.optJSONArray("invitees")),
            peerIds = peerIds(peerList),
            peerId = ownPeerId(call("net_ipfsAddress") as? String),
        )
    }

    /** The identity state of [address] (dna_identity): Undefined for an address without one. */
    fun identityState(address: String): String =
        (call("dna_identity", JSONArray().put(address)) as JSONObject).optString("state", "Undefined")

    /**
     * Sends an invitation (dna_sendInvite, no fee): to [to], or without it to a new key the node makes;
     * returns the transaction, the receiver and the code (the new key, null when [to] is given).
     */
    fun sendInvite(to: String?): SentInviteTx {
        val args = JSONObject().put("amount", "0")
        to?.let { args.put("to", it) }
        val result = call("dna_sendInvite", JSONArray().put(args)) as JSONObject
        return SentInviteTx(
            result.getString("hash").lowercase(),
            result.getString("receiver").lowercase(),
            result.optString("key").takeIf { it.isNotEmpty() },
        )
    }

    /** Activates invitation [code] for the node's address (dna_activateInvite, no fee); returns the transaction. */
    fun activateInvite(code: String): String =
        call("dna_activateInvite", JSONArray().put(JSONObject().put("key", code))) as String

    /** Spoils invitation [code]: activated to a new random address (no fee); returns the transaction. */
    fun spoilInvite(code: String): String =
        (call("dna_activateInviteToRandAddr", JSONArray().put(JSONObject().put("key", code))) as JSONObject).getString("hash")

    /** Mining transactions of the node's identity; each returns the transaction hash. */
    fun startMining(): String = call("dna_becomeOnline", JSONArray().put(JSONObject())) as String
    fun stopMining(): String = call("dna_becomeOffline", JSONArray().put(JSONObject())) as String
    fun delegate(pool: String): String = call("dna_delegate", JSONArray().put(JSONObject().put("to", pool))) as String
    fun undelegate(): String = call("dna_undelegate", JSONArray().put(JSONObject())) as String

    /**
     * Accepts the invitation sent to the node's address: an activation transaction from the address to
     * itself (dna_activateInvite without an invitation code). Returns the transaction hash.
     */
    fun acceptInvitation(): String = call("dna_activateInvite", JSONArray().put(JSONObject())) as String

    /**
     * The network data of the APY (see [NetworkRewards]). Reads every identity and the last
     * [sampleBlocks] blocks: call it rarely, and only once the node's state is current.
     */
    fun networkRewards(sampleBlocks: Int = 100): NetworkRewards {
        val validated = setOf("Newbie", "Verified", "Human")
        val identities = call("dna_identities") as? JSONArray ?: JSONArray()
        var totalStakingWeight = 0.0
        val counted = HashSet<String>()
        val onlineWeights = ArrayList<Double>()
        val delegatorWeights = HashMap<String, MutableList<Double>>()
        for (i in 0 until identities.length()) {
            val identity = identities.getJSONObject(i)
            if (identity.optString("state") !in validated) continue
            val address = identity.optString("address").lowercase()
            val weight = stakeWeight(identity.optString("stake").toDoubleOrNull() ?: 0.0)
            totalStakingWeight += weight
            counted += address
            val delegatee = identity.optString("delegatee").takeUnless { identity.isNull("delegatee") || it.isEmpty() }
            when {
                delegatee != null -> delegatorWeights.getOrPut(delegatee.lowercase()) { mutableListOf() } += weight
                identity.optBoolean("online") -> onlineWeights += weight
            }
        }
        // Delegators validate through their pool when it is online (ValidatorsCache: pool.delegators).
        for ((pool, weights) in delegatorWeights) {
            val online = (call("dna_identity", JSONArray().put(pool)) as? JSONObject)?.optBoolean("online") == true
            if (online) onlineWeights += weights
        }
        // The god address shares the staking reward too (addSuccessfulValidationReward).
        if (GOD_ADDRESS !in counted) {
            val god = call("dna_getBalance", JSONArray().put(GOD_ADDRESS)) as? JSONObject
            totalStakingWeight += stakeWeight(god?.optString("stake")?.toDoubleOrNull() ?: 0.0)
        }
        val epoch = call("dna_epoch") as JSONObject
        val start = (call("bcn_blockAt", JSONArray().put(epoch.optLong("startBlock"))) as? JSONObject)?.optLong("timestamp") ?: 0L
        val end = parseValidationTime(epoch.optString("nextValidation"))?.epochSecond ?: 0L
        val head = (call("bcn_lastBlock") as JSONObject).optLong("height")
        var proposed = 0
        var sampled = 0
        for (height in (head - sampleBlocks + 1).coerceAtLeast(1)..head) {
            val block = call("bcn_blockAt", JSONArray().put(height)) as? JSONObject ?: continue
            sampled++
            if (!block.optBoolean("isEmpty")) proposed++
        }
        return NetworkRewards(
            totalStakingWeight = totalStakingWeight,
            validators = onlineWeights.size,
            averageValidatorWeight = if (onlineWeights.isEmpty()) 0.0 else onlineWeights.average(),
            epochDays = if (start > 0 && end > start) (end - start) / 86_400.0 else 0.0,
            proposedBlockShare = if (sampled > 0) proposed.toDouble() / sampled else 1.0,
        )
    }

    /**
     * The fee of a transaction of [type] ([SEND_TX], [REPLENISH_STAKE_TX]) sending [amount] iDNA from
     * [from] to [to], from bcn_estimateTx, which checks it as the mempool would (balance, rules) and sends
     * nothing. Throws with the node's reason.
     */
    fun estimateTx(type: Int, from: String, to: String, amount: BigDecimal): BigDecimal {
        val result = call("bcn_estimateTx", JSONArray().put(txArgs(type, from, to, amount))) as JSONObject
        return BigDecimal(result.optString("txFee", "0"))
    }

    /**
     * Sends a transaction of [type] with dna_sendTransaction; the node signs it and sets its max fee (twice
     * the fee). Returns the transaction hash.
     */
    fun sendTx(type: Int, from: String, to: String, amount: BigDecimal): String =
        call("dna_sendTransaction", JSONArray().put(txArgs(type, from, to, amount))) as String

    private fun txArgs(type: Int, from: String, to: String, amount: BigDecimal) = JSONObject()
        .put("type", type).put("from", from).put("to", to).put("amount", amount.toPlainString())

    /** The stake below which a validated identity is discriminated (dna_globalState); null if unset. */
    fun discriminationThreshold(): BigDecimal? {
        val state = call("dna_globalState") as? JSONObject ?: return null
        return state.optString("discriminationStakeThreshold").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }

    /** Whether the transaction is in a block: the node reports a zero block hash while it is in the mempool. */
    fun isMined(hash: String): Boolean {
        val tx = call("bcn_transaction", JSONArray().put(hash)) as? JSONObject ?: return false
        return tx.optString("blockHash").removePrefix("0x").any { it != '0' }
    }

    /** The epoch, its ceremony period and the validation time (the current one during the ceremony). */
    fun ceremony(): CeremonyState {
        val epoch = call("dna_epoch") as JSONObject
        val intervals = call("dna_ceremonyIntervals") as JSONObject
        return CeremonyState(
            epoch = epoch.optInt("epoch"),
            period = epoch.optString("currentPeriod"),
            validationTime = epoch.optString("nextValidation"),
            shortSessionSeconds = intervals.optDouble("ShortSessionDuration"),
            longSessionSeconds = intervals.optDouble("LongSessionDuration"),
        )
    }

    /** The flips of a session; throws with the node's reason when the identity has none. */
    fun flipHashes(short: Boolean): List<FlipHash> {
        val array = call(if (short) "flip_shortHashes" else "flip_longHashes") as? JSONArray ?: return emptyList()
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            FlipHash(item.getString("hash"), item.optBoolean("ready"), item.optBoolean("extra"))
        }
    }

    /** A flip decrypted by the node, without its keywords. */
    fun flip(hash: String): Flip {
        val result = call("flip_get", JSONArray().put(hash)) as JSONObject
        return decodeFlip(hash, result.getString("hex"), result.optString("privateHex"))
    }

    /** The keywords of a long-session flip, from [all] the node's keywords (assets/keywords.json). */
    fun flipKeywords(hash: String, all: List<Keyword>): List<Keyword> {
        val indexes = (call("flip_words", JSONArray().put(hash)) as JSONObject).getJSONArray("words")
        return keywordsOf((0 until indexes.length()).map { indexes.getInt(it) }, all)
    }

    /** The identity of the node's address (dna_identity): its flip keyword pairs and flip counts. */
    fun identity(): JSONObject = call("dna_identity") as JSONObject

    /** When flips stop: the start of the flip lottery before the next validation; null when unknown. */
    fun flipDeadline(): java.time.Instant? {
        val epoch = call("dna_epoch") as JSONObject
        val intervals = call("dna_ceremonyIntervals") as JSONObject
        val validation = parseValidationTime(epoch.optString("nextValidation")) ?: return null
        return flipDeadline(validation, intervals.optDouble("FlipLotteryDuration", 0.0))
    }

    /** Submits a flip: the node encrypts it, keeps it on IPFS and sends the transaction. */
    fun submitFlip(flip: FlipHex, pairId: Int, epoch: Int): SentFlip {
        val result = call("flip_submit", flipSubmitArgs(flip, pairId), readTimeoutMs = 60_000) as JSONObject
        return SentFlip(result.getString("txHash"), result.optString("hash"), pairId, System.currentTimeMillis(), epoch)
    }

    /** The fee of deleting flip [cid] (bcn_estimateTx of a DeleteFlipTx); throws with the node's reason. */
    fun estimateDeleteFlip(from: String, cid: String): java.math.BigDecimal {
        val args = JSONObject().put("type", DELETE_FLIP_TX).put("from", from).put("amount", "0")
            .put("payload", bytesToHex(deleteFlipPayload(cid)))
        val result = call("bcn_estimateTx", JSONArray().put(args)) as JSONObject
        return java.math.BigDecimal(result.optString("txFee", "0"))
    }

    /** Deletes flip [cid] (flip_delete); returns the transaction hash. */
    fun deleteFlip(cid: String): String = call("flip_delete", JSONArray().put(cid)) as String

    /**
     * Whether the node is synced: a flip made on a node behind the chain uses the epoch, the keys and the
     * nonce of the past, and never counts.
     */
    fun synced(): Boolean = !(call("bcn_syncing") as JSONObject).optBoolean("syncing", true)

    /** The identity's last transactions in blocks (bcn_transactions): their type and epoch. */
    fun ownTransactions(count: Int = 30): List<Pair<String, Int>> {
        val address = call("dna_getCoinbaseAddr") as String
        val result = call("bcn_transactions", JSONArray().put(JSONObject().put("address", address).put("count", count))) as? JSONObject
        val list = result?.optJSONArray("transactions") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it).let { tx -> tx.optString("type") to tx.optInt("epoch", -1) } }
    }

    /** Sends the answers of a session; returns the transaction hash. */
    fun submitAnswers(short: Boolean, answers: List<SubmittedAnswer>): String {
        val list = JSONArray()
        answers.forEach { list.put(JSONObject().put("hash", it.hash).put("answer", it.answer).put("grade", it.grade)) }
        val result = call(
            if (short) "flip_submitShortAnswers" else "flip_submitLongAnswers",
            JSONArray().put(JSONObject().put("answers", list)),
        ) as JSONObject
        return result.optString("txHash")
    }
}

/** A sent invitation: its transaction, its receiver and its code (null for an invitation to an address). */
data class SentInviteTx(val hash: String, val receiver: String, val code: String?)

/** The identity's activated invitees (dna_identity invitees: TxHash, Address). */
internal fun parseInvitees(array: JSONArray?): List<Invitee> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val tx = item.optString("TxHash").ifEmpty { item.optString("txHash") }
        val address = item.optString("Address").ifEmpty { item.optString("address") }
        if (tx.isEmpty() || !isAddress(address)) null else Invitee(tx.lowercase(), address.lowercase())
    }
}

data class CeremonyState(
    val epoch: Int,
    /** None, FlipLottery, ShortSession, LongSession or AfterLongSession. */
    val period: String,
    /** The validation start (RFC 3339): the next one outside the ceremony, the current one during it. */
    val validationTime: String,
    val shortSessionSeconds: Double,
    val longSessionSeconds: Double,
)

data class NodeStatus(
    val address: String,
    val currentBlock: Long,
    val highestBlock: Long,
    /** The block of the node's state. Fast sync downloads headers first and the state only at the end. */
    val stateBlock: Long,
    val syncing: Boolean,
    val wrongTime: Boolean,
    val peers: Int,
    val identityState: String,
    val age: Int,
    val online: Boolean,
    val delegatee: String?,
    val isPool: Boolean,
    val stake: String,
    val balance: String,
    /** The next validation (RFC 3339) and the ceremony period now (None, FlipLottery, ShortSession...). */
    val nextValidation: String,
    val period: String,
    /** The flips the identity must make this epoch, those it published and the most it may make. */
    val requiredFlips: Int,
    val madeFlips: Int,
    val availableFlips: Int,
    /** Stake locked by a replenishment, burnt first if the identity is killed (iDNA, as text). */
    val lockedStake: String,
    val epoch: Int,
    /** The epoch of the delegation to a pool; undelegating is possible from the next one. */
    val delegationEpoch: Int,
    /** A mining penalty still running; no delegation until it ends. */
    val penaltySeconds: Long,
    /** The invitations the identity may still send this epoch, and its activated invitees. */
    val invites: Int = 0,
    val invitees: List<Invitee> = emptyList(),
    /** The peer ids of the node's peers (`net_peers`), for the direct peers' connected mark. */
    val peerIds: Set<String> = emptySet(),
    /** The node's own peer id, for its peer code; null when the node did not give it. */
    val peerId: String? = null,
) {
    /** Whether the identity, stake and balance are still those of an old state (fast sync running). */
    val accountPending: Boolean
        get() = syncing && stateBlock < currentBlock

    /**
     * Mining as the node sees it: delegated to a pool, online itself, or not mining ("offline" only while
     * the node syncs, when the state may still change).
     */
    val miningStatus: String
        get() = when {
            delegatee != null -> "Delegated to pool $delegatee"
            online -> "Mining (online)"
            syncing -> "Not mining (offline)"
            else -> "Not mining"
        }
}
