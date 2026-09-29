package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
        )
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
    /** The flips the identity must make this epoch, and those it made. */
    val requiredFlips: Int,
    val madeFlips: Int,
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
