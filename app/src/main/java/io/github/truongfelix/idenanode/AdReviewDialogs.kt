package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal

/** A review checked with the node: the plan, its costs and the balance; [problems] keep it from going. */
private class ReviewCheck(val plan: ReviewPlan?, val costs: ReviewCosts?, val balance: BigDecimal, val problems: List<String>)

/**
 * "Send to review…": the review fee, then the node's check and every cost, then the send: the ad on IPFS (and its
 * storage), the voting's deploy and its launch with the deposit and the fee. The draft keeps the ad's cid and the
 * voting as soon as each exists.
 */
@Composable
fun ReviewDialog(ad: MyAd, me: String, onClose: () -> Unit, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeText by remember { mutableStateOf(DEFAULT_REVIEW_FEE.toPlainString()) }
    var check by remember { mutableStateOf<ReviewCheck?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = ad.title.ifBlank { "Untitled ad" }
    val ready = check
    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Send to review") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    ready?.costs != null && ready.plan != null -> Text(reviewConfirmText(title, ready.costs, ready.balance, ready.plan.form))
                    ready != null -> ready.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    else -> {
                        OutlinedTextField(
                            value = feeText,
                            onValueChange = { feeText = it.trim(); error = null },
                            label = { Text("Review fee, iDNA") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Paid to the oracles who vote on the ad: the higher the fee, the sooner they review it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                busy?.let { Text(it); CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val draft = ad.draft
            when {
                busy != null || draft == null -> {}
                ready == null -> TextButton(onClick = {
                    val fee = feeText.toBigDecimalOrNull()
                    if (fee == null || fee.signum() < 0) {
                        error = "The fee is an amount of iDNA."
                        return@TextButton
                    }
                    busy = "Checking the review with the node…"
                    scope.launch {
                        val checked = withContext(Dispatchers.IO) { runCatching { checkReview(context, me, draft, fee) } }
                        busy = null
                        checked.onSuccess { check = it }.onFailure { error = "The review cannot be sent: ${it.message}" }
                    }
                }) { Text("Check") }
                ready.plan != null && ready.costs != null && ready.costs.total <= ready.balance -> TextButton(onClick = {
                    scope.launch {
                        val message = sendReview(adStore(context), myAdsNode(context), rpc(context), me, draft, ready.plan) { busy = it }
                        busy = null
                        onDone(message)
                    }
                }) { Text("Send") }
            }
        },
        dismissButton = { if (busy == null) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Checks [draft] for review with [fee]: its problems, else the node's plan and the costs. */
private fun checkReview(context: Context, me: String, draft: AdDraft, fee: BigDecimal): ReviewCheck {
    val store = adStore(context)
    val thumb = store.thumb(draft.id)
    val media = store.media(draft.id)
    val node = myAdsNode(context)
    val balance = SocialPosting(rpc(context)).balance(me)
    val problems = adProblems(draft, thumb != null && media != null)
    if (problems.isNotEmpty() || thumb == null || media == null) return ReviewCheck(null, null, balance, problems)
    val plan = node.planReview(me, draft, thumb, media, fee)
    val publish = plan.publish
    val costs = ReviewCosts(fee, publish.launchAmount - fee, publish.stake, plan.storeFee, publish.cost.fee)
    return ReviewCheck(plan, costs, balance, emptyList())
}

/**
 * Sends the review of [plan]: the ad on IPFS and its storage, then the voting's deploy and launch; [store] keeps the
 * ad's cid, then its voting, as soon as each exists. Returns what the list says after it.
 */
internal suspend fun sendReview(
    store: AdDraftStore,
    node: MyAdsNode,
    rpc: NodeRpc,
    me: String,
    draft: AdDraft,
    plan: ReviewPlan,
    status: (String) -> Unit,
): String {
    status("Storing the ad on IPFS…")
    val stored = withContext(Dispatchers.IO) {
        runCatching {
            node.storeAd(plan)
            store.save(draft.copy(cid = plan.cid))
        }
    }
    stored.exceptionOrNull()?.let { return "Not sent to review: ${it.message}" }
    val outcome = publishAndLaunch(
        rpc, me, plan.form, plan.publish,
        onDeployed = { contract -> runCatching { store.save(draft.copy(cid = plan.cid, contract = contract)) } },
    ) { status(if (it == "Publishing…") "Publishing the review voting…" else "Launching the review…") }
    return when {
        outcome.contract == null -> outcome.message ?: "Not sent to review."
        outcome.message != null -> outcome.message.replace("Launch it from its page.", "Start the review from My ads.")
        else -> "“${draft.title}” is in review: the oracles vote for ${durationText(plan.form.votingDuration)}, " +
            "then the votes are counted for ${durationText(maxOf(plan.form.countingDuration, MIN_COUNTING_BLOCKS))}."
    }
}

/** A launch checked with the node: the voting, what it sends (deposit and review fee) and the cost. */
private class LaunchCheck(val voting: OracleVoting, val amount: BigDecimal, val deposit: BigDecimal, val cost: TxCost)

/** "Start the review…": the voting is deployed, not launched; the launch sends the deposit and the review fee. */
@Composable
fun StartReviewDialog(ad: MyAd, me: String, onClose: () -> Unit, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeText by remember { mutableStateOf(DEFAULT_REVIEW_FEE.toPlainString()) }
    var check by remember { mutableStateOf<LaunchCheck?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = check
    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Start the review") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready == null) {
                    Text("The review voting of “${ad.title}” exists but did not start. Starting it sends the deposit and the review fee.")
                    OutlinedTextField(
                        value = feeText,
                        onValueChange = { feeText = it.trim(); error = null },
                        label = { Text("Review fee, iDNA") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Start the review of “${ad.title}”? It sends ${idna(ready.amount.toPlainString())} to the voting: the deposit " +
                            "(${idna(ready.deposit.toPlainString())}, back when the voting ends) and the review fee for the oracles.\n\n" +
                            feeLine(ready.cost),
                    )
                }
                busy?.let { Text(it); CircularProgressIndicator() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                busy != null -> {}
                ready == null -> TextButton(onClick = {
                    val fee = feeText.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: run { error = "The fee is an amount of iDNA."; return@TextButton }
                    busy = "Checking with the node…"
                    scope.launch {
                        val checked = withContext(Dispatchers.IO) { runCatching { checkLaunch(context, me, ad.contract, fee) } }
                        busy = null
                        checked.onSuccess { check = it }.onFailure { error = "It cannot start: ${it.message}" }
                    }
                }) { Text("Check") }
                else -> TextButton(onClick = {
                    busy = "Starting the review…"
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) {
                            runCatching {
                                val rpc = rpc(context)
                                val hash = VotingOwnerNode(rpc).send(VotingAction.Launch, me, ready.voting, ready.amount, ready.cost)
                                waitForBlock(NodeTxLookup(rpc), hash)
                            }
                        }
                        busy = null
                        onDone(
                            sent.fold(
                                onSuccess = { block ->
                                    when {
                                        block == null -> "Not started: $NOT_SENT"
                                        block.error != null -> "The contract refused it: ${block.error}."
                                        else -> "The review of “${ad.title}” started."
                                    }
                                },
                                onFailure = { "Not started: ${it.message}" },
                            ),
                        )
                    }
                }) { Text("Start") }
            }
        },
        dismissButton = { if (busy == null) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Plans the launch of the review [contract] with the deposit and [fee]. */
private fun checkLaunch(context: Context, me: String, contract: String, fee: BigDecimal): LaunchCheck {
    val rpc = rpc(context)
    if (!rpc.synced()) throw IOException("the node is still syncing; try once it is synced")
    val voting = votingNode(context).voting(contract, me)
    val deposit = voting.ownerDeposit ?: BigDecimal.ZERO
    val amount = maxOf(deposit + fee - voting.balance, launchAmount(voting))
    return LaunchCheck(voting, amount, deposit, VotingOwnerNode(rpc).plan(VotingAction.Launch, me, voting, amount))
}

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
