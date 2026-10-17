package io.github.truongfelix.idenanode

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/*
 * A new oracle voting, as the desktop's New voting page makes it: the kinds (the desktop's presets), the form, and
 * the deploy of the oracle voting contract (code 0x02) with the form as its arguments. Launching it is a second
 * call, startVoting ([VotingAction.Launch]).
 */

/** Blocks per hour: a block every 20 s. */
const val BLOCKS_PER_HOUR = 180L

/** The durations the form offers, in blocks, as the desktop's: 12 h, 1, 2 and 5 days, a week. */
val VOTING_DURATIONS = listOf(12L, 24L, 48L, 120L, 168L).map { it * BLOCKS_PER_HOUR }

/** The shortest counting the contract keeps (a shorter one is raised to it). */
const val MIN_COUNTING_BLOCKS = 100L

/** "12 h", "1 day", "5 days", "1 week" for a duration of [blocks]. */
fun durationText(blocks: Long): String {
    val hours = blocks / BLOCKS_PER_HOUR
    return when {
        blocks % BLOCKS_PER_HOUR != 0L || hours == 0L -> t(R.string.voting_duration_minutes, blocks * 20 / 60)
        hours % 168 == 0L -> (hours / 168).let { tp(R.plurals.voting_duration_weeks, it.toInt(), it) }
        hours % 24 == 0L -> (hours / 24).let { tp(R.plurals.voting_duration_days, it.toInt(), it) }
        else -> t(R.string.voting_duration_hours, hours)
    }
}

/** The kinds the form starts from, as the desktop's presets; each sets the settings ([withKind]). */
enum class VotingKind(@StringRes val titleId: Int, @StringRes val noteId: Int) {
    Fact(R.string.voting_kind_fact, R.string.voting_kind_fact_note),
    Poll(R.string.voting_kind_poll, R.string.voting_kind_poll_note),
    Decision(R.string.voting_kind_decision, R.string.voting_kind_decision_note),
    Custom(R.string.voting_kind_custom, R.string.voting_kind_custom_note);

    val title: String get() = t(titleId)
    val note: String get() = t(noteId)
}

/** The majority thresholds the form offers, as the desktop's. */
enum class Majority(val percent: Int, @StringRes val titleId: Int) {
    Simple(51, R.string.voting_majority_simple),
    Super(66, R.string.voting_majority_super),
    None(100, R.string.voting_majority_none);

    val title: String get() = t(titleId)
}

/**
 * The New voting form. Amounts in iDNA; durations in blocks. [refundAddress]: where the owner's share goes at the end
 * (the deposit back and the owner fee), empty for the author.
 */
data class NewVotingForm(
    val kind: VotingKind = VotingKind.Custom,
    val title: String = "",
    val desc: String = "",
    val options: List<String> = listOf("", ""),
    val startNow: Boolean = true,
    val startTime: Instant? = null,
    val votingDuration: Long = 24 * BLOCKS_PER_HOUR,
    val countingDuration: Long = 12 * BLOCKS_PER_HOUR,
    val committeeSize: Long = 100,
    val quorum: Int = 1,
    val winnerThreshold: Int = Majority.Super.percent,
    val freeVoting: Boolean = false,
    val minPayment: BigDecimal = BigDecimal.ZERO,
    val ownerFee: Int = 0,
    val rewardsFund: BigDecimal = BigDecimal.ZERO,
    val refundAddress: String = "",
    /** The ad an ad review votes on (its IPFS cid), empty for any other voting. */
    val adCid: String = "",
) {
    /** The options the voting gets: the written ones, numbered in order. */
    val writtenOptions: List<VotingOption>
        get() = options.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { id, text -> VotingOption(id, text) }

    /**
     * The payment a vote sends, as the deploy gives it: none for a free voting. Always sent: a payment left out reads as
     * 0 too, the later arguments being there (devnet 2026-10-10), as the desktop's empty field.
     */
    val paymentArg: BigDecimal
        get() = if (freeVoting) BigDecimal.ZERO else minPayment
}

/** A new form of [kind] keeping the texts of [this]: the kind's settings on top of the defaults, as the desktop's presets. */
fun NewVotingForm.withKind(kind: VotingKind, now: Instant): NewVotingForm {
    val texts = NewVotingForm(kind = kind, title = title, desc = desc, options = options)
    return when (kind) {
        VotingKind.Fact -> texts.copy(startNow = false, startTime = now + Duration.ofDays(7), winnerThreshold = 51, quorum = 5)
        VotingKind.Poll -> texts.copy(startNow = true, winnerThreshold = 100, quorum = 1, freeVoting = true)
        VotingKind.Decision -> texts.copy(startNow = false, winnerThreshold = 51, quorum = 5, freeVoting = true)
        VotingKind.Custom -> texts
    }
}

private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

/** A link in an option: the desktop refuses options with links. */
private val LINK = Regex("(?i)(https?://|www\\.)\\S")

/** What keeps the form from being published, in the order of the form; empty when it can be. */
fun NewVotingForm.problems(): List<String> = buildList {
    if (title.isBlank()) add(t(R.string.voting_problem_title))
    if (desc.isBlank()) add(t(R.string.voting_problem_desc))
    if (writtenOptions.size < 2) add(t(R.string.voting_problem_options))
    if (writtenOptions.any { LINK.containsMatchIn(it.text) }) add(t(R.string.voting_problem_links))
    if (!startNow && startTime == null) add(t(R.string.voting_problem_start))
    if (committeeSize < 1) add(t(R.string.voting_problem_committee))
    if (quorum !in 1..100) add(t(R.string.voting_problem_quorum))
    if (ownerFee !in 0..100) add(t(R.string.voting_problem_owner_fee))
    if (refundAddress.isNotEmpty() && !ADDRESS.matches(refundAddress)) add(t(R.string.voting_problem_refund_address))
}

/** The contract's fact: hex of JSON {title, desc, options: [{id, value}]} and an ad review's adCid, as the desktop writes it. */
fun factHex(form: NewVotingForm): String {
    val options = JSONArray(form.writtenOptions.map { JSONObject().put("id", it.id).put("value", it.text) })
    val fact = JSONObject().put("title", form.title.trim()).put("desc", form.desc.trim()).put("options", options)
    if (form.adCid.isNotEmpty()) fact.put("adCid", form.adCid)
    return bytesToHex(fact.toString().toByteArray(Charsets.UTF_8))
}

/** The deploy's arguments, as the desktop's: the fact, the start (unix s), durations, thresholds, payment, fee, fund, refund address. */
fun deployArgs(form: NewVotingForm, now: Instant): JSONArray {
    val start = if (form.startNow) now else form.startTime ?: now
    val args = mutableListOf(
        contractArg(0, "hex", factHex(form)),
        contractArg(1, "uint64", start.epochSecond.toString()),
        contractArg(2, "uint64", form.votingDuration.toString()),
        contractArg(3, "uint64", form.countingDuration.toString()),
        contractArg(4, "byte", form.winnerThreshold.toString()),
        contractArg(5, "byte", form.quorum.toString()),
        contractArg(6, "uint64", form.committeeSize.toString()),
        contractArg(7, "dna", form.paymentArg.toPlainString()),
        contractArg(8, "byte", form.ownerFee.toString()),
        contractArg(9, "dna", form.rewardsFund.toPlainString()),
    )
    if (form.refundAddress.isNotEmpty()) args += contractArg(10, "hex", form.refundAddress)
    return JSONArray(args)
}

/** The parameters of contract_estimateDeploy (no [maxFee]) and contract_deploy of the voting, staking [stake]. */
fun deployParams(from: String, args: JSONArray, stake: BigDecimal, maxFee: BigDecimal?): JSONArray {
    val params = JSONObject()
        .put("from", from)
        .put("codeHash", "0x02")
        .put("amount", stake.toPlainString())
        .put("args", args)
    maxFee?.let { params.put("maxFee", it.toPlainString()) }
    return JSONArray().put(params)
}

/** The least stake a contract's deploy takes: 3,000,000 gas at [feePerGas] (bcn_feePerGas, in the smallest unit). */
fun votingStake(feePerGas: BigDecimal): BigDecimal =
    (feePerGas * BigDecimal(3_000_000)).movePointLeft(18).setScale(18, RoundingMode.UP).stripTrailingZeros()

/**
 * The owner deposit the contract asks before launch, as its calculateOwnerDeposit: 5000 iDNA shared by the
 * network ([networkSize] identities), in whole units of the smallest unit, times the committee (at most the network).
 */
fun ownerDeposit(networkSize: Long, committeeSize: Long): BigDecimal {
    val network = maxOf(1L, networkSize)
    val perOracle = BigDecimal(5000).movePointRight(18).divide(BigDecimal(network), 0, RoundingMode.DOWN)
    return (perOracle * BigDecimal(minOf(committeeSize, network))).movePointLeft(18).stripTrailingZeros()
}

/** A cost the node estimated: the fee, and the most the transaction may pay (twice that). */
data class TxCost(val fee: BigDecimal, val maxFee: BigDecimal)

/** The cost in an estimate's receipt (contract_estimate*), or its error. */
fun receiptCost(receipt: JSONObject): Result<TxCost> {
    if (!receipt.optBoolean("success")) return Result.failure(IllegalStateException(receipt.optString("error").ifEmpty { t(R.string.voting_refused) }))
    val fee = BigDecimal(receipt.optString("txFee", "0")) + BigDecimal(receipt.optString("gasCost", "0"))
    return Result.success(TxCost(fee, (fee * BigDecimal(2)).setScale(6, RoundingMode.UP)))
}

/** A publish ready to send: the deploy's arguments and stake, its cost, and what launching it then sends. */
data class PublishPlan(val args: JSONArray, val stake: BigDecimal, val cost: TxCost, val contract: String, val launchAmount: BigDecimal)

/** What the confirmation says before publishing [form] as [plan]. [date] writes a time. */
fun publishConfirmText(form: NewVotingForm, plan: PublishPlan, date: (Instant) -> String): String = buildString {
    val launch = idna(plan.launchAmount.toPlainString())
    append(t(R.string.voting_publish_confirm, form.title.trim(), idna(plan.stake.toPlainString())))
    append("\n\n")
    val start = form.startTime
    when {
        launchesAtOnce(form) -> append(t(R.string.voting_publish_launches_at_once, launch))
        start != null -> append(t(R.string.voting_publish_waits_from, date(start), launch))
        else -> append(t(R.string.voting_publish_waits, launch))
    }
    append("\n\n")
    append(t(R.string.voting_publish_durations, durationText(form.votingDuration), durationText(maxOf(form.countingDuration, MIN_COUNTING_BLOCKS))))
    append("\n\n").append(feeLine(plan.cost))
}

/** Whether publishing also launches the voting, as the desktop: when it starts now, without another refund address. */
fun launchesAtOnce(form: NewVotingForm): Boolean = form.startNow && form.refundAddress.isEmpty()
