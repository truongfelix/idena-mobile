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
    append("Vote “${optionText(voting, plan.option)}”? The app cannot change it once sent.")
    if (plan.payment.signum() > 0) {
        append("\n\nThe secret vote sends ${idna(plan.payment.toPlainString())} to the voting, plus a fee of about ")
    } else {
        append("\n\nThe secret vote costs only its fee: about ")
    }
    append("${fee(plan.fee)} iDNA (at most ${fee(plan.maxFee)}).")
    append("\n\nYour node then opens the vote itself at block ${plan.voteBlock}")
    voting.votingEnd?.let { append(", ${date(it)}") }
    append(". Keep sync on from then")
    voting.countingEnd?.let { append(" until ${date(it)}") }
    append(": a vote that is not opened does not count.")
}

/**
 * What the voting's screen says of the identity's vote: [sent] from this phone (null without one, or when it came
 * from another app), [opened] the option of its opened vote (null while secret). Null when there is nothing to say.
 */
fun yourVoteText(voting: OracleVoting, sent: SentVote?, opened: Int?, date: (Instant) -> String): String? = when {
    opened != null -> "You voted “${optionText(voting, opened)}”: your vote is open ✓"
    sent != null && voting.state == VotingState.OPEN -> "Your secret vote for “${optionText(voting, sent.option)}” is waiting for a block…"
    sent != null && voting.state == VotingState.VOTED -> buildString {
        append("You voted “${optionText(voting, sent.option)}”. It stays secret until block ${sent.voteBlock}")
        voting.votingEnd?.let { append(", ${date(it)}") }
        append("; then your node opens it. Keep sync on from then")
        voting.countingEnd?.let { append(" until ${date(it)}") }
        append(".")
    }
    sent != null && voting.state in listOf(VotingState.COUNTING, VotingState.CAN_BE_PROLONGED) -> buildString {
        append("You voted “${optionText(voting, sent.option)}”. Your node opens the vote now")
        voting.countingEnd?.let { append(" (it can until ${date(it)})") }
        append(": keep sync on.")
    }
    voting.state == VotingState.VOTED -> "You voted from another app: the node that sent the vote opens it."
    else -> null
}
