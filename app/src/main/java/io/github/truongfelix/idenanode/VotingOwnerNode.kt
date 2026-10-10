package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/**
 * Publishing an oracle voting and its page's actions ([VotingAction]) through the app's own node: each is estimated
 * first (the contract's verdict and the fee), then sent with twice the fee as its most. The calls block: use them off
 * the main thread.
 */
class VotingOwnerNode(private val rpc: NodeRpc) {
    /** Plans publishing [form] from [from]: the deploy's estimate, its stake, and what launching it then sends. */
    fun planPublish(from: String, form: NewVotingForm, now: Instant): PublishPlan {
        val args = deployArgs(form, now)
        val stake = votingStake(BigDecimal(rpc.call("bcn_feePerGas").toString()))
        val receipt = rpc.call("contract_estimateDeploy", deployParams(from, args, stake, null)) as JSONObject
        val cost = receiptCost(receipt).getOrElse { throw IOException(it.message) }
        val network = (rpc.call("dna_globalState") as JSONObject).optLong("networkSize")
        return PublishPlan(args, stake, cost, receipt.optString("contract").lowercase(), ownerDeposit(network, form.committeeSize) + form.rewardsFund)
    }

    /** Sends the deploy of [plan]; returns its transaction. */
    fun publish(from: String, plan: PublishPlan): String =
        rpc.call("contract_deploy", deployParams(from, plan.args, plan.stake, plan.cost.maxFee)) as String

    /** The voting a deploy made, from its receipt once in a block; null without one. */
    fun deployed(hash: String): String? =
        (rpc.call("bcn_txReceipt", JSONArray().put(hash)) as? JSONObject)?.optString("contract")?.ifEmpty { null }?.lowercase()

    /** What the node accepts now from [from] on [voting]; an estimate that fails counts as refused. */
    fun checks(from: String, voting: OracleVoting): ActionChecks {
        fun accepts(method: String, params: JSONArray) =
            runCatching { receiptCost(rpc.call(method, params) as JSONObject).isSuccess }.getOrDefault(false)
        return ActionChecks(
            finish = accepts("contract_estimateCall", callParams(from, voting.contract, "finishVoting", BigDecimal.ZERO, null)),
            prolong = accepts("contract_estimateCall", callParams(from, voting.contract, "prolongVoting", BigDecimal.ZERO, null)),
            terminate = accepts("contract_estimateTerminate", terminateParams(from, voting, null)),
        )
    }

    /** The cost of [action] from [from], paying [amount]; throws with the contract's reason (as the node gives it). */
    fun plan(action: VotingAction, from: String, voting: OracleVoting, amount: BigDecimal): TxCost {
        val receipt = when (action) {
            VotingAction.Terminate -> rpc.call("contract_estimateTerminate", terminateParams(from, voting, null))
            VotingAction.AddFunds -> return TxCost(rpc.estimateTx(SEND_TX, from, voting.contract, amount), BigDecimal.ZERO)
            else -> rpc.call("contract_estimateCall", callParams(from, voting.contract, actionMethod(action)!!, amount, null))
        } as JSONObject
        return receiptCost(receipt).getOrElse { throw ContractRefusal(it.message) }
    }

    /** Sends [action] as planned ([cost]); returns its transaction. */
    fun send(action: VotingAction, from: String, voting: OracleVoting, amount: BigDecimal, cost: TxCost): String = when (action) {
        VotingAction.Terminate -> rpc.call("contract_terminate", terminateParams(from, voting, cost.maxFee)) as String
        VotingAction.AddFunds -> rpc.sendTx(SEND_TX, from, voting.contract, amount)
        else -> rpc.call("contract_call", callParams(from, voting.contract, actionMethod(action)!!, amount, cost.maxFee)) as String
    }
}

/** The contract refused an estimated call: [message] is its reason, as [actionErrorText] reads it. */
class ContractRefusal(message: String?) : IOException(message)
