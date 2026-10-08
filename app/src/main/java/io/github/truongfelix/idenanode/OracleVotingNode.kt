package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/** A vote ready to send: the option, its salt and hash, the block at which the node sends the vote, and its cost. */
data class VotePlan(
    val from: String,
    val contract: String,
    val option: Int,
    val salt: String,
    val voteHash: String,
    val voteBlock: Long,
    /** The voting's minimum payment, sent with the secret vote. */
    val payment: BigDecimal,
    /** The secret vote's fee as the node estimates it, and the most it may cost (twice that). */
    val fee: BigDecimal,
    val maxFee: BigDecimal,
)

/** Oracle votings through the app's own node. The calls block: use them off the main thread. */
class OracleVotingNode(private val rpc: NodeRpc) {
    /** A page of [tab] relative to [oracle]; the node may first scan its state for the live votings (after a fast sync). */
    fun page(tab: VotingTab, oracle: String, token: String? = null): VotingPage =
        parseVotingPage(rpc.call("contract_oracleVotings", votingListParams(tab, oracle, token), readTimeoutMs = 60_000) as? JSONObject)

    fun voting(contract: String, oracle: String): OracleVoting =
        parseVoting(rpc.call("contract_oracleVoting", JSONArray().put(contract).put(oracle), readTimeoutMs = 60_000) as JSONObject)

    /** The option [address] opened in [contract]; null while its vote is still secret, or without one. */
    fun openedVote(contract: String, address: String): Int? = try {
        (rpc.call("contract_readMap", JSONArray().put(contract).put("votes").put(address).put("byte")) as? Number)?.toInt()
    } catch (e: IOException) {
        if (e.message == NO_DATA) null else throw e
    }

    /**
     * Prepares the vote for [option] as the desktop does: the contract checks that the identity may send a secret
     * vote now (its committee, the time), the node signs the salt, the contract hashes the vote, and the node
     * estimates the secret vote. Throws with the node's or the contract's reason.
     */
    fun plan(from: String, contract: String, option: Int, epoch: Int): VotePlan {
        rpc.call("contract_readonlyCall", readonlyCallParams(contract, "proof", "hex", contractArg(0, "hex", from)))
        val salt = rpc.call("dna_sign", JSONArray().put(saltMessage(contract, epoch))) as String
        val voteHash = rpc.call(
            "contract_readonlyCall",
            readonlyCallParams(contract, "voteHash", "hex", contractArg(0, "byte", option.toString()), contractArg(1, "hex", salt)),
        ) as String
        // A free voting stores no minimum payment.
        val payment = try {
            BigDecimal(rpc.call("contract_readData", JSONArray().put(contract).put("votingMinPayment").put("dna")).toString())
        } catch (e: IOException) {
            if (e.message == NO_DATA) BigDecimal.ZERO else throw e
        }
        val voteBlock = (rpc.call("contract_readonlyCall", readonlyCallParams(contract, "voteBlock", "uint64")) as Number).toLong()
        val receipt = rpc.call("contract_estimateCall", secretVoteParams(from, contract, voteHash, payment, null)) as JSONObject
        if (!receipt.optBoolean("success")) throw IOException(receipt.optString("error").ifEmpty { "the voting refused the vote" })
        val fee = BigDecimal(receipt.optString("txFee", "0")) + BigDecimal(receipt.optString("gasCost", "0"))
        return VotePlan(from, contract, option, salt, voteHash, voteBlock, payment, fee, (fee * BigDecimal(2)).setScale(6, RoundingMode.UP))
    }

    /**
     * Sends the vote of [plan]: first the vote itself to the node, which keeps it and sends it at the plan's block,
     * then the secret vote. In that order a secret vote that fails leaves only a kept vote the contract refuses at
     * that block, which the node then drops without sending it. Returns the secret vote's transaction.
     */
    fun send(plan: VotePlan): String {
        rpc.call("contract_call", voteParams(plan.from, plan.contract, plan.option, plan.salt, plan.voteBlock, plan.maxFee))
        return rpc.call("contract_call", secretVoteParams(plan.from, plan.contract, plan.voteHash, plan.payment, plan.maxFee)) as String
    }

    private companion object {
        /** The node's answer for a contract value that is not stored. */
        const val NO_DATA = "data is nil"
    }
}

/** The votes sent from this phone (oracle_votes.json in the node's folder), so the screen can show them. */
object SentVotes {
    private fun file(context: Context) = File(NodeSettings.nodeDir(context), "oracle_votes.json")

    fun all(context: Context): List<SentVote> = runCatching { parseSentVotes(file(context).readText()) }.getOrDefault(emptyList())

    fun of(context: Context, from: String, contract: String): SentVote? =
        all(context).lastOrNull { it.from == from.lowercase() && it.contract == contract.lowercase() }

    fun save(context: Context, vote: SentVote) = write(context, withSentVote(all(context), vote))

    fun remove(context: Context, vote: SentVote) = write(context, all(context).filterNot { it == vote })

    private fun write(context: Context, votes: List<SentVote>) {
        val target = file(context)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(JSONArray(votes.map { it.toJson() }).toString())
        if (!tmp.renameTo(target)) throw IOException("cannot save the vote")
    }
}
