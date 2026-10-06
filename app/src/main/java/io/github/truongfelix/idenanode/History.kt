package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/*
 * The History screen's data ([HistoryActivity.kt]): the address's history as its own node records it
 * (dna_identityHistory), and its transactions (bcn_transactions). Plain functions, tested on the JVM.
 */

enum class CeremonyOutcome { Validated, NotValidated, NoIdentity, Failed, Unknown }

/** One reward kind of a validation summary: earned, missed (null when the node cannot tell) and why. */
data class RewardLine(val kind: String, val earned: BigDecimal, val missed: BigDecimal?, val reason: String)

/** dna_validationSummary of one ceremony, the fields the report shows. */
data class ValidationReport(
    val participated: Boolean,
    val approved: Boolean,
    val missed: Boolean,
    val penalized: Boolean,
    val penaltyReason: String,
    val wrongGrades: Boolean,
    val madeFlips: Int,
    val shortPoints: Double,
    val shortFlips: Int,
    val longPoints: Double,
    val longFlips: Int,
    val shortAnswers: Int,
    val longAnswers: Int,
    val rewards: List<RewardLine>,
    val delegatee: String?,
    val delegated: BigDecimal,
) {
    val earned: BigDecimal get() = rewards.fold(BigDecimal.ZERO) { sum, r -> sum + r.earned }
    val missedRewards: BigDecimal get() = rewards.fold(BigDecimal.ZERO) { sum, r -> sum + (r.missed ?: BigDecimal.ZERO) }
}

data class CeremonyRow(
    val epoch: Int,
    /** The block that ended the epoch (Unix seconds), null when the node did not place it. */
    val time: Long?,
    val outcome: CeremonyOutcome,
    val prevState: String?,
    val state: String?,
    /** Short session points and qualified flips, when known. */
    val score: Pair<Double, Int>?,
    /** Flips made (from the summary) or submitted (from the own transactions). */
    val flips: Int,
    val shortAnswersSent: Boolean,
    val longAnswersSent: Boolean,
    val report: ValidationReport?,
)

data class MiningRow(
    val epoch: Int,
    val proposedBlocks: Int,
    val proposerReward: BigDecimal,
    val committeeBlocks: Int,
    val committeeReward: BigDecimal,
    val penaltyBurnt: BigDecimal,
) {
    val total: BigDecimal get() = proposerReward + committeeReward - penaltyBurnt
}

data class IdentityHistory(
    val address: String,
    val own: Boolean,
    val epoch: Int,
    /** False while the node is still finding its past ceremony blocks (polled again). */
    val ceremoniesComplete: Boolean,
    val ceremonies: List<CeremonyRow>,
    val mining: List<MiningRow>,
)

/** Amounts arrive as decimal strings; anything else counts as zero. */
internal fun decimal(value: Any?): BigDecimal = try {
    if (value == null || value == JSONObject.NULL) BigDecimal.ZERO else BigDecimal(value.toString())
} catch (e: NumberFormatException) {
    BigDecimal.ZERO
}

private val rewardKinds = listOf(
    "staking" to "Staking",
    "candidate" to "Candidate",
    "flips" to "Flips",
    "extraFlips" to "Extra flips",
    "invitations" to "Invitations",
    "invitee" to "Invitee",
    "reports" to "Reports",
    "validation" to "Validation",
)

internal fun parseValidationReport(json: JSONObject): ValidationReport {
    val rewards = json.optJSONObject("rewards")
    val short = json.optJSONObject("shortAnswers")
    val long = json.optJSONObject("longAnswers")
    val delegatee = json.optJSONObject("delegateeReward")
    return ValidationReport(
        participated = json.optBoolean("participated"),
        approved = json.optBoolean("approved"),
        missed = json.optBoolean("missed"),
        penalized = json.optBoolean("penalized"),
        penaltyReason = json.optString("penaltyReason"),
        wrongGrades = json.optBoolean("wrongGrades"),
        madeFlips = json.optInt("madeFlips"),
        shortPoints = short?.optDouble("point", 0.0) ?: 0.0,
        shortFlips = short?.optInt("flipsCount") ?: 0,
        longPoints = long?.optDouble("point", 0.0) ?: 0.0,
        longFlips = long?.optInt("flipsCount") ?: 0,
        shortAnswers = json.optInt("shortAnswersCount"),
        longAnswers = json.optInt("longAnswersCount"),
        rewards = rewardKinds.mapNotNull { (key, label) ->
            val reward = rewards?.optJSONObject(key) ?: return@mapNotNull null
            val missed = if (reward.isNull("missed")) null else decimal(reward.opt("missed"))
            RewardLine(label, decimal(reward.opt("earned")), missed, reward.optString("reason"))
        },
        delegatee = delegatee?.optString("address")?.ifEmpty { null },
        delegated = decimal(delegatee?.opt("amount")),
    )
}

private fun outcome(summary: JSONObject?, validated: Boolean?): Triple<CeremonyOutcome, String?, String?> {
    if (summary != null) {
        if (summary.optBoolean("validationFailed")) return Triple(CeremonyOutcome.Failed, null, null)
        val state = summary.optString("state")
        if (!summary.optBoolean("participated") && state == "Undefined") return Triple(CeremonyOutcome.NoIdentity, null, null)
        val result = if (state in validatedStates) CeremonyOutcome.Validated else CeremonyOutcome.NotValidated
        return Triple(result, summary.optString("prevState"), state)
    }
    return when (validated) {
        true -> Triple(CeremonyOutcome.Validated, null, null)
        false -> Triple(CeremonyOutcome.NotValidated, null, null)
        null -> Triple(CeremonyOutcome.Unknown, null, null)
    }
}

private fun CeremonyRow.hasActivity(): Boolean =
    outcome == CeremonyOutcome.Validated || report?.participated == true || score != null || flips > 0 ||
        shortAnswersSent || longAnswersSent

/**
 * The history: one ceremony row per past epoch the node knows, newest first, down to the first ceremony where
 * the address shows any activity (older ones say nothing about it), and the epochs with recorded mining.
 */
fun parseIdentityHistory(json: JSONObject): IdentityHistory {
    val current = json.optInt("epoch")
    val scores = HashMap<Int, Pair<Double, Int>>()
    val scoreArray = json.optJSONArray("scores") ?: JSONArray()
    for (i in 0 until scoreArray.length()) {
        val score = scoreArray.optJSONObject(i) ?: continue
        if (score.isNull("epoch") || !score.has("epoch")) continue
        scores[score.optInt("epoch")] = score.optDouble("shortPoints", 0.0) to score.optInt("shortFlips")
    }
    val ceremonies = ArrayList<CeremonyRow>()
    val mining = ArrayList<MiningRow>()
    val epochs = json.optJSONArray("epochs") ?: JSONArray()
    for (i in 0 until epochs.length()) {
        val item = epochs.optJSONObject(i) ?: continue
        val epoch = item.optInt("epoch")
        item.optJSONObject("mining")?.let {
            mining += MiningRow(
                epoch,
                it.optInt("proposedBlocks"),
                decimal(it.opt("proposerReward")),
                it.optInt("committeeBlocks"),
                decimal(it.opt("committeeReward")),
                decimal(it.opt("penaltyBurnt")),
            )
        }
        val ceremony = item.optJSONObject("ceremony")
        val summary = item.optJSONObject("summary")
        if (epoch >= current || (ceremony == null && summary == null)) continue
        val validated = if (item.isNull("validated") || !item.has("validated")) null else item.optBoolean("validated")
        val (result, prevState, state) = outcome(summary, validated)
        val report = summary?.let { parseValidationReport(it) }
        val txs = item.optJSONObject("transactions")
        val score = when {
            report != null && report.participated && !report.missed -> report.shortPoints to report.shortFlips
            else -> scores[epoch]
        }
        ceremonies += CeremonyRow(
            epoch = epoch,
            time = ceremony?.optLong("time"),
            outcome = result,
            prevState = prevState,
            state = state,
            score = score,
            flips = if (report?.participated == true) report.madeFlips else txs?.optInt("flips") ?: 0,
            shortAnswersSent = txs?.optBoolean("shortAnswers") ?: false,
            longAnswersSent = txs?.optBoolean("longAnswers") ?: false,
            report = report,
        )
    }
    val last = ceremonies.indexOfLast { it.hasActivity() }
    return IdentityHistory(
        address = json.optString("address"),
        own = json.optBoolean("own"),
        epoch = current,
        ceremoniesComplete = json.optBoolean("ceremoniesComplete") || current == 0,
        ceremonies = ceremonies.subList(0, last + 1).toList(),
        mining = mining,
    )
}

/** A transaction of the own address (bcn_transactions). */
data class HistoryTx(
    val hash: String,
    val type: String,
    val payload: String,
    val from: String,
    val to: String?,
    val amount: BigDecimal,
    val fee: BigDecimal,
    val epoch: Int,
    val timestamp: Long,
)

/** One page of bcn_transactions: the transactions and the token of the next page (null: the last one). */
fun parseTransactions(json: JSONObject?): Pair<List<HistoryTx>, String?> {
    if (json == null) return emptyList<HistoryTx>() to null
    val list = json.optJSONArray("transactions") ?: JSONArray()
    val txs = (0 until list.length()).mapNotNull { i ->
        val tx = list.optJSONObject(i) ?: return@mapNotNull null
        HistoryTx(
            hash = tx.optString("hash"),
            type = tx.optString("type"),
            payload = tx.optString("payload"),
            from = tx.optString("from"),
            to = if (tx.isNull("to")) null else tx.optString("to").ifEmpty { null },
            amount = decimal(tx.opt("amount")),
            fee = decimal(tx.opt("usedFee")),
            epoch = tx.optInt("epoch"),
            timestamp = tx.optLong("timestamp"),
        )
    }
    val token = if (json.isNull("token")) null else json.optString("token").ifEmpty { null }
    return txs to token
}

private val txTypeNames = mapOf(
    "send" to "Transfer",
    "activation" to "Invitation activated",
    "invite" to "Invitation issued",
    "killInvitee" to "Invitation terminated",
    "kill" to "Identity terminated",
    "killDelegator" to "Delegator removed",
    "submitFlip" to "Flip submitted",
    "deleteFlip" to "Flip deleted",
    "submitAnswersHash" to "Short answers (hash)",
    "submitShortAnswers" to "Short answers",
    "submitLongAnswers" to "Long answers",
    "evidence" to "Validation evidence",
    "changeProfile" to "Profile changed",
    "deployContract" to "Contract deployed",
    "callContract" to "Contract call",
    "terminateContract" to "Contract terminated",
    "delegate" to "Delegation",
    "undelegate" to "Undelegation",
    "replenishStake" to "Stake replenished",
    "burn" to "Burn",
    "storeToIpfs" to "Stored to IPFS",
    "changeGodAddress" to "God address changed",
)

fun txTypeName(type: String, payload: String): String =
    if (type == "online") {
        if (payload == "0x") "Mining status Off" else "Mining status On"
    } else {
        txTypeNames[type] ?: type
    }

/** The amount seen from the address: negative when it sent the coins. */
fun HistoryTx.signedAmount(address: String): BigDecimal =
    if (from.equals(address, ignoreCase = true)) amount.negate() else amount

/** The other side of the transaction, seen from the address. */
fun HistoryTx.counterParty(address: String): String? = if (from.equals(address, ignoreCase = true)) to else from

/** Why a reward was lost, as the summary says it. */
fun missedReason(reason: String): String = when (reason) {
    "penalty" -> "penalty"
    "not_validated" -> "not validated"
    "missed" -> "validation missed"
    "grades_ignored" -> "grades ignored"
    else -> reason
}
