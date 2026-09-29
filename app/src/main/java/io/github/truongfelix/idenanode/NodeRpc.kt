package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The mainnet god address (config.DefaultGodAddress); it takes part in the staking reward. */
private const val GOD_ADDRESS = "0x4d60dc6a2cba8c3ef1ba5e1eba5c12c54cee6b61"

/** A JSON-RPC client for the app's own node. Calls block: use them off the main thread. */
class NodeRpc(private val port: Int, private val apiKey: String) {

    fun call(method: String, params: JSONArray = JSONArray()): Any? {
        val conn = URL("http://127.0.0.1:$port").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 10_000
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

    /** Reads what the screen shows. Throws if the node does not answer. */
    fun status(): NodeStatus {
        val sync = call("bcn_syncing") as JSONObject
        val peers = (call("net_peers") as? JSONArray)?.length() ?: 0
        val stateBlock = (call("bcn_lastBlock") as JSONObject).optLong("height")
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
        )
    }

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
) {
    /** Whether the identity, stake and balance are still those of an old state (fast sync running). */
    val accountPending: Boolean
        get() = syncing && stateBlock < currentBlock

    /** Mining as the node sees it: delegated to a pool, online itself, or not mining. */
    val miningStatus: String
        get() = when {
            delegatee != null -> "Delegated to pool $delegatee"
            online -> "Mining (online)"
            else -> "Not mining (offline)"
        }
}
