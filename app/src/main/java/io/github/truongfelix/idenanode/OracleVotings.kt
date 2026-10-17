package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/*
 * Oracle votings through the phone's own node: its index (contract_oracleVotings, with the fields of the indexer's
 * OracleVotingContracts that the desktop reads too) and one voting (contract_oracleVoting), both relative to the
 * identity. Voting is two calls of the voting's contract: sendVoteProof now, with a hash of the option and a salt,
 * and sendVote, which the node keeps and sends itself once the secret voting is over (broadcastBlock).
 */

/** The voting states as the node names them; Voted is Open once the identity sent its secret vote. */
object VotingState {
    const val PENDING = "Pending"
    const val OPEN = "Open"
    const val VOTED = "Voted"
    const val COUNTING = "Counting"
    const val CAN_BE_PROLONGED = "CanBeProlonged"
    const val ARCHIVE = "Archive"
    const val TERMINATED = "Terminated"
    val ALL = listOf(PENDING, OPEN, VOTED, COUNTING, CAN_BE_PROLONGED, ARCHIVE, TERMINATED)
}

/**
 * The lists of the screen, as the desktop's: the votings of the identity's committees still to vote in, those
 * voted in or being counted, those closed ([all] false: committees only); then every voting, and the votings the
 * identity created or voted in ([mine]: the node's `address` filter).
 */
enum class VotingTab(@StringRes val titleId: Int, val all: Boolean, val states: List<String>, val mine: Boolean = false) {
    ToVote(R.string.voting_tab_to_vote, false, listOf(VotingState.OPEN)),
    Ongoing(R.string.voting_tab_ongoing, false, listOf(VotingState.VOTED, VotingState.COUNTING, VotingState.CAN_BE_PROLONGED)),
    Closed(R.string.voting_tab_closed, false, listOf(VotingState.ARCHIVE, VotingState.TERMINATED)),
    All(R.string.voting_tab_all, true, VotingState.ALL),
    Mine(R.string.voting_tab_mine, false, VotingState.ALL, mine = true);

    val title: String get() = t(titleId)
}

/** The states in which a voting takes no more funds or actions but termination. */
val CLOSED_STATES = listOf(VotingState.ARCHIVE, VotingState.TERMINATED)

/** The most votings one contract_oracleVotings call returns (the node's limit). */
const val VOTINGS_PER_PAGE = 100

/** An option of a voting: the byte sent as the vote, and its text. */
data class VotingOption(val id: Int, val text: String)

/** What the voting's author wrote in it: the question, its description and the options. */
data class VotingFact(val title: String, val desc: String, val options: List<VotingOption>)

data class OracleVoting(
    val contract: String,
    val author: String,
    val fact: VotingFact,
    val state: String,
    /** Whether the identity is in the voting's committee. */
    val isOracle: Boolean,
    val startTime: Instant?,
    /** The end of the secret voting, when the node sends the vote itself: estimated until then. */
    val votingEnd: Instant?,
    /** The end of the open voting, when the votes are counted: estimated until then. */
    val countingEnd: Instant?,
    /** The payment a vote sends to the voting. */
    val minPayment: BigDecimal,
    /** An oracle's estimated reward, and the most it can be. */
    val oracleReward: BigDecimal?,
    val maxOracleReward: BigDecimal?,
    /** The rewards paid out: estimated while the voting runs, the amount once it is closed (when the node saw it). */
    val totalReward: BigDecimal?,
    val balance: BigDecimal,
    val committeeSize: Long,
    /** Secret votes sent, and votes opened. */
    val votesSent: Long,
    val votes: Long,
    /** Opened votes by option. */
    val optionVotes: Map<Int, Long>,
    /** Percentages of the committee: votes needed to count, and the winner's share of the votes. */
    val quorum: Int,
    val winnerThreshold: Int,
    val ownerFee: Int,
    /** The winning option of a closed voting. */
    val result: Int?,
    /** What the author deposits to launch it (the contract's figure), and the rewards fund it set. */
    val ownerDeposit: BigDecimal? = null,
    val rewardsFund: BigDecimal? = null,
    /** Where the owner's share goes, when not to the author. */
    val refundRecipient: String? = null,
    /** From when it can be terminated: estimated until then. */
    val terminationTime: Instant? = null,
    /** Prolongations without more votes (the contract refuses a fourth). */
    val epochWithoutGrowth: Int = 0,
) {
    /** Whether the identity can send its vote now. */
    val canVote: Boolean
        get() = isOracle && state == VotingState.OPEN && fact.options.isNotEmpty()

    /** The votes needed for a result: the quorum's share of the committee, at least one. */
    val quorumVotes: Long
        get() = maxOf(1L, (committeeSize * quorum + 99) / 100)
}

/** A page of a list, with the token of the next page (null on the last one). */
data class VotingPage(val votings: List<OracleVoting>, val next: String?)

/**
 * The parameters of contract_oracleVotings for [tab], relative to [oracle] (also the address of [VotingTab.Mine]), from
 * [token] (null: the first page).
 */
fun votingListParams(tab: VotingTab, oracle: String, token: String?, limit: Int = VOTINGS_PER_PAGE): JSONArray {
    val args = JSONObject()
        .put("oracle", oracle)
        .put("all", tab.all)
        .put("states", JSONArray(tab.states))
        .put("limit", limit)
    if (tab.mine) args.put("address", oracle)
    token?.let { args.put("continuationToken", it) }
    return JSONArray().put(args)
}

/** A list page as the node answers it: without `result` when nothing matches. */
fun parseVotingPage(json: JSONObject?): VotingPage {
    val list = json?.optJSONArray("result") ?: JSONArray()
    val votings = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::parseVoting) }
    val next = json?.takeUnless { it.isNull("continuationToken") }?.optString("continuationToken")?.takeIf { it.isNotEmpty() }
    return VotingPage(votings, next)
}

/** "3 to vote" for the identity's open votings ([page] of [VotingTab.ToVote]), or null with none. */
fun toVoteText(page: VotingPage): String? = when {
    page.votings.isEmpty() -> null
    page.next != null -> t(R.string.voting_to_vote_more, page.votings.size)
    else -> t(R.string.voting_to_vote, page.votings.size)
}

/** One voting as the node gives it (contract_oracleVoting, or an entry of a list). */
fun parseVoting(json: JSONObject): OracleVoting {
    val optionVotes = HashMap<Int, Long>()
    json.optJSONArray("votes")?.let { votes ->
        for (i in 0 until votes.length()) {
            val item = votes.optJSONObject(i) ?: continue
            optionVotes[item.optInt("option")] = item.optLong("count")
        }
    }
    return OracleVoting(
        contract = json.optString("contractAddress").lowercase(),
        author = json.optString("author").lowercase(),
        fact = votingFact(json.optString("fact")),
        state = json.optString("state"),
        isOracle = json.optBoolean("isOracle"),
        startTime = time(json, "startTime"),
        votingEnd = time(json, "estimatedVotingFinishTime") ?: time(json, "votingFinishTime"),
        countingEnd = time(json, "estimatedPublicVotingFinishTime") ?: time(json, "publicVotingFinishTime"),
        minPayment = amount(json, "minPayment") ?: BigDecimal.ZERO,
        oracleReward = amount(json, "estimatedOracleReward"),
        maxOracleReward = amount(json, "estimatedMaxOracleReward"),
        totalReward = amount(json, "estimatedTotalReward") ?: amount(json, "totalReward"),
        balance = amount(json, "balance") ?: BigDecimal.ZERO,
        committeeSize = json.optLong("committeeSize"),
        // The indexer's name, which the desktop reads; nodes up to pin 30eafe2 name it ballotsCount.
        votesSent = if (json.has("voteProofsCount")) json.optLong("voteProofsCount") else json.optLong("ballotsCount"),
        votes = json.optLong("votesCount"),
        optionVotes = optionVotes,
        quorum = json.optInt("quorum"),
        winnerThreshold = json.optInt("winnerThreshold"),
        ownerFee = json.optInt("ownerFee"),
        result = if (json.isNull("result") || !json.has("result")) null else json.optInt("result"),
        ownerDeposit = amount(json, "ownerDeposit"),
        rewardsFund = amount(json, "oracleRewardFund"),
        refundRecipient = json.takeUnless { it.isNull("refundRecipient") }?.optString("refundRecipient")?.ifEmpty { null }?.lowercase(),
        terminationTime = time(json, "estimatedTerminationTime") ?: time(json, "terminationTime"),
        epochWithoutGrowth = json.optInt("epochWithoutGrowth"),
    )
}

/**
 * The fact of a voting, as the desktop reads it: hex of a JSON {title, desc, options: [{id, value}]} the author wrote.
 * Only text fields are read; an option whose id is not a byte cannot be voted for and is left out. Anything else
 * gives an untitled voting without options.
 */
fun votingFact(hex: String): VotingFact {
    val json = runCatching { JSONObject(String(hexToBytes(hex), Charsets.UTF_8)) }.getOrNull()
        ?: return VotingFact("", "", emptyList())
    val options = json.optJSONArray("options")?.let { list ->
        (0 until list.length()).mapNotNull { i ->
            val option = list.optJSONObject(i) ?: return@mapNotNull null
            val text = option.opt("value") as? String ?: return@mapNotNull null
            val id = when (val raw = option.opt("id")) {
                is Number -> raw.toDouble().takeIf { it == Math.floor(it) && it in 0.0..255.0 }?.toInt()
                is String -> raw.toIntOrNull()?.takeIf { it in 0..255 }
                else -> null
            } ?: return@mapNotNull null
            VotingOption(id, text)
        }
    }.orEmpty()
    return VotingFact(json.opt("title") as? String ?: "", json.opt("desc") as? String ?: "", options)
}

/** A time the node gives (RFC 3339); null when missing or Go's zero time. */
private fun time(json: JSONObject, name: String): Instant? =
    json.optString(name).takeIf { it.isNotEmpty() }?.let(::parseValidationTime)?.takeIf { it.epochSecond > 0 }

private fun amount(json: JSONObject, name: String): BigDecimal? =
    json.takeUnless { it.isNull(name) }?.optString(name)?.toBigDecimalOrNull()

/** How the screen names a voting's state. */
fun votingStateText(state: String): String = when (state) {
    VotingState.PENDING -> t(R.string.voting_state_pending)
    VotingState.OPEN -> t(R.string.voting_state_open)
    VotingState.VOTED -> t(R.string.voting_state_voted)
    VotingState.COUNTING -> t(R.string.voting_state_counting)
    VotingState.CAN_BE_PROLONGED -> t(R.string.voting_state_can_be_prolonged)
    VotingState.ARCHIVE -> t(R.string.voting_state_archive)
    VotingState.TERMINATED -> t(R.string.voting_state_terminated)
    else -> state
}

fun votingTitle(voting: OracleVoting): String = voting.fact.title.ifBlank { t(R.string.voting_untitled) }

/** The text of option [id] of [voting], or "option N" when the voting has no such option. */
fun optionText(voting: OracleVoting, id: Int): String = voting.fact.options.firstOrNull { it.id == id }?.text ?: t(R.string.voting_option_n, id)

/**
 * A voting's line in a list: its state, what comes next and when, and an open voting's reward; a closed one's
 * result.
 */
fun votingSummary(voting: OracleVoting, now: Instant): String = buildList {
    add(votingStateText(voting.state))
    when (voting.state) {
        VotingState.OPEN -> voting.votingEnd?.let { add(t(R.string.voting_summary_secret_ends, timeUntil(now, it))) }
        VotingState.VOTED -> voting.votingEnd?.let { add(t(R.string.voting_summary_votes_open, timeUntil(now, it))) }
        VotingState.COUNTING, VotingState.CAN_BE_PROLONGED -> voting.countingEnd?.let { add(t(R.string.voting_summary_ends, timeUntil(now, it))) }
        VotingState.ARCHIVE -> voting.result?.let { add(t(R.string.voting_summary_result, optionText(voting, it))) }
    }
    if (voting.state == VotingState.OPEN) voting.oracleReward?.takeIf { it.signum() > 0 }?.let { add(t(R.string.voting_about_per_oracle, idna(it.toPlainString()))) }
}.joinToString(" · ")
