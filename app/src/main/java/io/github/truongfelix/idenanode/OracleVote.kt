package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/* The vote in an oracle voting: the calls that send it, what the screen says of it, and the votes sent from this phone. */

/** The message the identity signs for its salt in a voting, as the desktop: one salt per voting and epoch. */
fun saltMessage(contract: String, epoch: Int): String = "salt-$contract-$epoch"

/** An argument of a contract method (the node's DynamicArg). */
fun contractArg(index: Int, format: String, value: String): JSONObject =
    JSONObject().put("index", index).put("format", format).put("value", value)

/** The parameters of contract_readonlyCall: [method] of [contract], its result in [format]. */
fun readonlyCallParams(contract: String, method: String, format: String, vararg args: JSONObject): JSONArray =
    JSONArray().put(JSONObject().put("contract", contract).put("method", method).put("format", format).put("args", JSONArray(args.toList())))

/**
 * The parameters of the secret vote, sendVoteProof with the vote's hash, paying [amount] (the voting's minimum
 * payment); without [maxFee] for the estimate.
 */
fun secretVoteParams(from: String, contract: String, voteHash: String, amount: BigDecimal, maxFee: BigDecimal?): JSONArray {
    val args = JSONObject()
        .put("from", from)
        .put("contract", contract)
        .put("method", "sendVoteProof")
        .put("amount", amount.toPlainString())
        .put("args", JSONArray().put(contractArg(0, "hex", voteHash)))
    maxFee?.let { args.put("maxFee", it.toPlainString()) }
    return JSONArray().put(args)
}

/** The parameters of the vote itself, sendVote with the option and the salt, which the node sends at [broadcastBlock]. */
fun voteParams(from: String, contract: String, option: Int, salt: String, broadcastBlock: Long, maxFee: BigDecimal): JSONArray =
    JSONArray().put(
        JSONObject()
            .put("from", from)
            .put("contract", contract)
            .put("method", "sendVote")
            .put("amount", "0")
            .put("args", JSONArray().put(contractArg(0, "byte", option.toString())).put(contractArg(1, "hex", salt)))
            .put("maxFee", maxFee.toPlainString())
            .put("broadcastBlock", broadcastBlock),
    )

/**
 * A vote the app sent, kept so the screen shows it: the option, the secret vote's transaction and the block at which
 * the node sends the vote itself.
 */
data class SentVote(val from: String, val contract: String, val option: Int, val secretVoteTx: String, val voteBlock: Long, val sentAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("from", from).put("contract", contract).put("option", option)
        .put("secretVoteTx", secretVoteTx).put("voteBlock", voteBlock).put("sentAt", sentAt)

    companion object {
        fun fromJson(json: JSONObject): SentVote? = runCatching {
            SentVote(
                json.getString("from").lowercase(), json.getString("contract").lowercase(), json.getInt("option"),
                json.getString("secretVoteTx"), json.getLong("voteBlock"), json.getLong("sentAt"),
            )
        }.getOrNull()
    }
}

/** The votes in a saved list (a JSON array), skipping entries that cannot be read. */
fun parseSentVotes(text: String): List<SentVote> {
    val list = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(SentVote::fromJson) }
}

/** [votes] with [vote] in place of an earlier one of the same identity and voting. */
fun withSentVote(votes: List<SentVote>, vote: SentVote): List<SentVote> =
    votes.filterNot { it.from == vote.from && it.contract == vote.contract } + vote

/**
 * What the confirmation says before a vote: the option, that the app sends no other, what it pays, and that the node opens it
 * itself at the end of the secret voting, so the phone's node must sync then. [date] writes a time.
 */
fun voteConfirmText(voting: OracleVoting, plan: VotePlan, date: (Instant) -> String): String = buildString {
    append(t(R.string.voting_confirm_vote, optionText(voting, plan.option)))
    append("\n\n")
    if (plan.payment.signum() > 0) {
        append(t(R.string.voting_confirm_paid, idna(plan.payment.toPlainString()), fee(plan.fee), fee(plan.maxFee)))
    } else {
        append(t(R.string.voting_confirm_free, fee(plan.fee), fee(plan.maxFee)))
    }
    append("\n\n")
    val end = voting.votingEnd
    append(if (end != null) t(R.string.voting_confirm_opens_at_date, plan.voteBlock, date(end)) else t(R.string.voting_confirm_opens_at, plan.voteBlock))
    append(" ")
    val counting = voting.countingEnd
    append(if (counting != null) t(R.string.voting_confirm_keep_sync_until, date(counting)) else t(R.string.voting_confirm_keep_sync))
}

/**
 * What the voting's screen says of the identity's vote: [sent] from this phone (null without one, or when it came
 * from another app), [opened] the option of its opened vote (null while secret). Null when there is nothing to say.
 */
fun yourVoteText(voting: OracleVoting, sent: SentVote?, opened: Int?, date: (Instant) -> String): String? = when {
    opened != null -> t(R.string.voting_you_voted_open, optionText(voting, opened))
    sent != null && voting.state == VotingState.OPEN -> t(R.string.voting_secret_vote_waiting, optionText(voting, sent.option))
    sent != null && voting.state == VotingState.VOTED -> buildString {
        append(t(R.string.voting_you_voted, optionText(voting, sent.option)))
        append(" ")
        val end = voting.votingEnd
        append(if (end != null) t(R.string.voting_secret_until_block_date, sent.voteBlock, date(end)) else t(R.string.voting_secret_until_block, sent.voteBlock))
        append(" ")
        val counting = voting.countingEnd
        append(if (counting != null) t(R.string.voting_keep_sync_until, date(counting)) else t(R.string.voting_keep_sync))
    }
    sent != null && voting.state in listOf(VotingState.COUNTING, VotingState.CAN_BE_PROLONGED) -> buildString {
        append(t(R.string.voting_you_voted, optionText(voting, sent.option)))
        append(" ")
        val counting = voting.countingEnd
        append(if (counting != null) t(R.string.voting_node_opens_now_until, date(counting)) else t(R.string.voting_node_opens_now))
    }
    voting.state == VotingState.VOTED -> t(R.string.voting_voted_from_another_app)
    else -> null
}
