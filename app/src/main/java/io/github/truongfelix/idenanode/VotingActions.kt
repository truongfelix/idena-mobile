package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/*
 * What a voting's page offers besides the vote, as the desktop's: launch it, add funds, prolong it, finish it and pay
 * out (Finish), terminate it. Anyone may send these; the contract decides, so the page shows only what the node
 * accepts now ([ActionChecks], from its estimates), and each confirmation names the amount and the fee.
 */

enum class VotingAction { Launch, AddFunds, Prolong, Finish, Terminate }

/** Whether the node accepts now, from the identity: finishVoting, prolongVoting, the termination. */
data class ActionChecks(val finish: Boolean, val prolong: Boolean, val terminate: Boolean)

private val RUNNING_STATES = listOf(VotingState.OPEN, VotingState.VOTED, VotingState.COUNTING, VotingState.CAN_BE_PROLONGED)

/**
 * The actions the page shows for [voting], as the desktop's: Launch while not started, Finish and Prolong when the
 * node accepts them (Prolong not when Finish is), Add funds until the voting closes, Terminate when the node accepts
 * it. [checks] null: not known yet (Launch and Add funds only).
 */
fun votingActions(voting: OracleVoting, checks: ActionChecks?): List<VotingAction> = buildList {
    if (voting.state == VotingState.PENDING) add(VotingAction.Launch)
    if (checks?.finish == true && voting.state in listOf(VotingState.COUNTING, VotingState.CAN_BE_PROLONGED)) add(VotingAction.Finish)
    if (checks?.prolong == true && !checks.finish && voting.state in RUNNING_STATES) add(VotingAction.Prolong)
    if (voting.state !in CLOSED_STATES) add(VotingAction.AddFunds)
    if (checks?.terminate == true && voting.state != VotingState.TERMINATED) add(VotingAction.Terminate)
}

/**
 * Whether the voting failed, as the desktop labels Finish then (refunds): no quorum of secret votes, three prolongations
 * without more votes, and no other prolongation.
 */
fun votingFailed(voting: OracleVoting, checks: ActionChecks?): Boolean =
    voting.votesSent < voting.quorumVotes && voting.epochWithoutGrowth >= 3 && checks?.prolong != true

fun actionLabel(action: VotingAction, voting: OracleVoting, checks: ActionChecks?): String = when (action) {
    VotingAction.Launch -> t(R.string.voting_action_launch)
    VotingAction.AddFunds -> t(R.string.voting_action_add_funds)
    VotingAction.Prolong -> t(R.string.voting_action_prolong)
    VotingAction.Finish -> t(if (votingFailed(voting, checks)) R.string.voting_action_claim_refunds else R.string.voting_action_claim_rewards)
    VotingAction.Terminate -> t(R.string.voting_action_terminate)
}

/** The contract method behind an action that is a contract call. */
fun actionMethod(action: VotingAction): String? = when (action) {
    VotingAction.Launch -> "startVoting"
    VotingAction.Prolong -> "prolongVoting"
    VotingAction.Finish -> "finishVoting"
    VotingAction.AddFunds, VotingAction.Terminate -> null
}

/** What Launch sends, as the desktop's: the owner deposit and the rewards fund, less what the voting holds. */
fun launchAmount(voting: OracleVoting): BigDecimal =
    ((voting.ownerDeposit ?: BigDecimal.ZERO) + (voting.rewardsFund ?: BigDecimal.ZERO) - voting.balance).max(BigDecimal.ZERO)

/** The parameters of contract_estimateCall (no [maxFee]) and contract_call of [method], paying [amount]. */
fun callParams(from: String, contract: String, method: String, amount: BigDecimal, maxFee: BigDecimal?): JSONArray {
    val params = JSONObject()
        .put("from", from)
        .put("contract", contract)
        .put("method", method)
        .put("amount", amount.toPlainString())
        .put("args", JSONArray())
    maxFee?.let { params.put("maxFee", it.toPlainString()) }
    return JSONArray().put(params)
}

/** The parameters of contract_estimateTerminate (no [maxFee]) and contract_terminate, naming the author as the desktop. */
fun terminateParams(from: String, voting: OracleVoting, maxFee: BigDecimal?): JSONArray {
    val params = JSONObject()
        .put("from", from)
        .put("contract", voting.contract)
        .put("args", JSONArray().put(contractArg(0, "hex", voting.author)))
    maxFee?.let { params.put("maxFee", it.toPlainString()) }
    return JSONArray().put(params)
}

/** The last line of a confirmation: the node's fee estimate, and the most the transaction may pay. */
fun feeLine(cost: TxCost) = t(R.string.voting_fee_line, fee(cost.fee), fee(cost.maxFee))

/** Where the owner's share goes: the refund address, else the author; "you" when that is [me]. */
private fun ownerShareTo(voting: OracleVoting, me: String): String =
    (voting.refundRecipient ?: voting.author).let { if (it.equals(me, ignoreCase = true)) t(R.string.voting_owner_share_you) else shortAddress(it) }

/** What a contract action's confirmation says: what the contract does, what it sends, then the fee. */
fun actionConfirmText(action: VotingAction, voting: OracleVoting, me: String, amount: BigDecimal, cost: TxCost): String {
    val title = votingTitle(voting)
    val held = idna(voting.balance.toPlainString())
    val text = when (action) {
        VotingAction.Launch -> when {
            amount.signum() == 0 -> t(R.string.voting_confirm_launch_held, title, held)
            voting.balance.signum() == 0 -> t(R.string.voting_confirm_launch, title, idna(amount.toPlainString()))
            else -> t(R.string.voting_confirm_launch_less, title, idna(amount.toPlainString()), held)
        }
        VotingAction.Prolong -> t(R.string.voting_confirm_prolong, title)
        VotingAction.Finish -> t(R.string.voting_confirm_finish, title, ownerShareTo(voting, me))
        VotingAction.Terminate -> when {
            voting.state == VotingState.PENDING -> t(R.string.voting_confirm_terminate_pending, title, held)
            voting.balance.signum() == 0 -> t(R.string.voting_confirm_terminate_empty, title)
            else -> t(R.string.voting_confirm_terminate, title, ownerShareTo(voting, me), held)
        }
        VotingAction.AddFunds -> return addFundsConfirmText(voting, amount, cost.fee)
    }
    return "$text\n\n${feeLine(cost)}"
}

/** What Add funds' confirmation says: a plain transfer to the voting, with the node's fee. */
fun addFundsConfirmText(voting: OracleVoting, amount: BigDecimal, txFee: BigDecimal): String =
    t(R.string.voting_confirm_add_funds, idna(amount.toPlainString()), votingTitle(voting), fee(txFee))

/** The contract's refusals of these actions, in the screen's words; any other comes as the node gives it. [date] writes a time. */
fun actionErrorText(error: String?, voting: OracleVoting, date: (Instant) -> String): String = when (error) {
    "starting is locked" -> voting.startTime?.let { t(R.string.voting_error_start_locked_date, date(it)) } ?: t(R.string.voting_error_start_locked)
    "contract balance is less than minimal deposit", "contract balance is less than minimal oracles reward" ->
        t(R.string.voting_error_low_balance)
    "contract is not in pending state" -> t(R.string.voting_error_launched_already)
    "contract is not in running state" -> t(R.string.voting_error_not_running)
    "not enough votes to finish voting" -> t(R.string.voting_error_not_enough_votes)
    "voting can not be prolonged" -> t(R.string.voting_error_cannot_prolong)
    "voting can not be prolonged anymore" -> t(R.string.voting_error_cannot_prolong_anymore)
    "voting can not be terminated" -> voting.terminationTime?.let { t(R.string.voting_error_cannot_terminate_from, date(it)) } ?: t(R.string.voting_error_cannot_terminate)
    null, "" -> t(R.string.voting_error_node_refused)
    else -> t(R.string.voting_error_node_refused_reason, error.toString())
}
