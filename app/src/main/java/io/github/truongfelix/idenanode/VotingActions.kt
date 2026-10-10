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
    VotingAction.Launch -> "Launch"
    VotingAction.AddFunds -> "Add funds"
    VotingAction.Prolong -> "Prolong"
    VotingAction.Finish -> if (votingFailed(voting, checks)) "Claim refunds" else "Claim rewards"
    VotingAction.Terminate -> "Terminate"
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
fun feeLine(cost: TxCost) = "Fee: about ${fee(cost.fee)} iDNA (at most ${fee(cost.maxFee)})."

/** Where the owner's share goes: the refund address, else the author; "you" when that is [me]. */
private fun ownerShareTo(voting: OracleVoting, me: String): String =
    (voting.refundRecipient ?: voting.author).let { if (it.equals(me, ignoreCase = true)) "you" else shortAddress(it) }

/** What a contract action's confirmation says: what the contract does, what it sends, then the fee. */
fun actionConfirmText(action: VotingAction, voting: OracleVoting, me: String, amount: BigDecimal, cost: TxCost): String {
    val title = "“${votingTitle(voting)}”"
    val held = idna(voting.balance.toPlainString())
    val text = when (action) {
        VotingAction.Launch -> buildString {
            append("Launch $title? ")
            when {
                amount.signum() == 0 -> append("It holds its owner deposit already ($held): nothing more is sent. ")
                voting.balance.signum() == 0 -> append("It sends ${idna(amount.toPlainString())} to the voting: the owner deposit and the rewards fund. ")
                else -> append("It sends ${idna(amount.toPlainString())} to the voting: the owner deposit and the rewards fund, less the $held it holds. ")
            }
            append("Then its committee is drawn and the secret voting starts.")
        }
        VotingAction.Prolong -> "Prolong $title? The contract draws a new committee for it."
        VotingAction.Finish ->
            "Finish $title and pay out? The contract counts the votes and pays the oracles who voted for the result (all " +
                "who voted, when no option wins), sends the owner's share (the deposit back and the owner fee) to " +
                "${ownerShareTo(voting, me)}, and burns what is left."
        VotingAction.Terminate -> "Terminate $title? " + when {
            voting.state == VotingState.PENDING ->
                "It never started: the contract sends the $held it holds to you, and half of its stake back to its author " +
                    "(the other half is burned)."
            voting.balance.signum() == 0 ->
                "The contract removes it and sends half of its stake back to its author (the other half is burned)."
            else ->
                "The contract closes it: the owner's share (the deposit back and the owner fee) goes to " +
                    "${ownerShareTo(voting, me)}, the rest of its $held to the oracles who voted (to you, when nobody " +
                    "voted), and half of its stake back to its author (the other half is burned)."
        }
        VotingAction.AddFunds -> return addFundsConfirmText(voting, amount, cost.fee)
    }
    return "$text\n\n${feeLine(cost)}"
}

/** What Add funds' confirmation says: a plain transfer to the voting, with the node's fee. */
fun addFundsConfirmText(voting: OracleVoting, amount: BigDecimal, txFee: BigDecimal): String =
    "Send ${idna(amount.toPlainString())} to “${votingTitle(voting)}”? It joins the voting's funds, paid out by its " +
        "rules; it cannot be taken back.\n\nFee: about ${fee(txFee)} iDNA."

/** The contract's refusals of these actions, in the screen's words; any other comes as the node gives it. [date] writes a time. */
fun actionErrorText(error: String?, voting: OracleVoting, date: (Instant) -> String): String = when (error) {
    "starting is locked" -> "It cannot be launched before its start" + (voting.startTime?.let { ", ${date(it)}" }.orEmpty()) + "."
    "contract balance is less than minimal deposit", "contract balance is less than minimal oracles reward" ->
        "The voting holds less than its owner deposit: send more."
    "contract is not in pending state" -> "It was launched already."
    "contract is not in running state" -> "The voting is not running."
    "not enough votes to finish voting" -> "Not enough votes to finish it yet."
    "voting can not be prolonged" -> "It cannot be prolonged now."
    "voting can not be prolonged anymore" -> "It cannot be prolonged anymore: three prolongations brought no more votes."
    "voting can not be terminated" -> "It cannot be terminated yet" + (voting.terminationTime?.let { ": from ${date(it)}" }.orEmpty()) + "."
    null, "" -> "The node refused it."
    else -> "The node refused it: $error."
}
