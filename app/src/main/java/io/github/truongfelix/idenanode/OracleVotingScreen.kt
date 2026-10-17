package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/** A voting as its screen shows it: the node's view relative to the identity, the vote sent from this phone, the opened vote. */
private data class VotingView(val voting: OracleVoting, val sent: SentVote?, val opened: Int?)

private fun readVotingView(context: Context, contract: String, me: String): VotingView {
    val node = votingNode(context)
    val voting = node.voting(contract, me)
    val opened = if (voting.state == VotingState.PENDING || voting.state == VotingState.OPEN) null else node.openedVote(contract, me)
    return VotingView(voting, SentVotes.of(context, me, contract), opened)
}

/** "8 Oct 2026, 18:30 (in 2 days)" ([dateFormat], as My flips). */
private fun dateText(time: Instant): String = "${dateFormat.format(time)} (${timeUntil(Instant.now(), time)})"

/**
 * One voting: its question, times, committee and rewards, the options, and the identity's vote. While the identity
 * may vote, an option and Vote, then a confirmation with the cost, send it ([OracleVotingNode.plan], [OracleVotingNode.send]).
 */
@Composable
fun OracleVotingScreen(contract: String, me: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reads by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<Result<VotingView>?>(null) }
    var option by rememberSaveable { mutableStateOf<Int?>(null) }
    var plan by remember { mutableStateOf<VotePlan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reads) { loaded = withContext(Dispatchers.IO) { runCatching { readVotingView(context, contract, me) } } }
    val view = loaded?.getOrNull()

    // A secret vote sent from this phone and not in a block yet: followed until it is, or the node drops it.
    val pending = view?.sent?.takeIf { view.voting.state == VotingState.OPEN }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val inBlock = withContext(Dispatchers.IO) { waitForBlock(NodeTxLookup(NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))), pending.secretVoteTx) }
        when {
            inBlock == null -> message = t(R.string.voting_your_vote_not_sent, NOT_SENT)
            inBlock.error != null -> message = t(R.string.voting_refused_your_vote, inBlock.error.toString())
        }
        if (inBlock == null || inBlock.error != null) withContext(Dispatchers.IO) { runCatching { SentVotes.remove(context, pending) } }
        reads++
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle(t(R.string.voting_title_one), onClose)
        val current = loaded
        when {
            current == null -> CircularProgressIndicator()
            view == null -> {
                Text(t(R.string.voting_node_no_answer, current.exceptionOrNull()?.message.toString()))
                Button(onClick = { reads++ }) { Text(t(R.string.common_try_again)) }
            }
            else -> {
                VotingDetails(view.voting, me)
                VotingActionPills(view.voting, me) { reads++ }
                HorizontalDivider()
                val voting = view.voting
                val choosing = voting.canVote && pending == null
                val canVote = choosing && busy == null
                Options(voting, selected = option.takeIf { choosing }, choosing = choosing, enabled = canVote) { option = it }
                yourVoteText(voting, view.sent, view.opened, ::dateText)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    voting.state == VotingState.PENDING -> Note(t(R.string.voting_not_started_yet))
                    voting.state == VotingState.OPEN && !voting.isOracle -> Note(t(R.string.voting_not_in_committee))
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (voting.canVote && pending == null) {
                    Button(
                        onClick = {
                            val chosen = option ?: return@Button
                            message = null
                            busy = t(R.string.voting_checking_vote)
                            scope.launch {
                                val made = withContext(Dispatchers.IO) { runCatching { planVote(context, me, contract, chosen) } }
                                busy = null
                                made.onSuccess { plan = it }.onFailure { message = t(R.string.voting_vote_cannot_be_sent, it.message.toString()) }
                            }
                        },
                        enabled = canVote && option != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(t(R.string.voting_vote_button)) }
                }
                plan?.let { ready ->
                    VoteConfirmDialog(voting, ready, onDismiss = { plan = null }) {
                        plan = null
                        busy = t(R.string.voting_sending_vote)
                        scope.launch {
                            val sent = withContext(Dispatchers.IO) {
                                runCatching { votingNode(context).send(ready) }.onSuccess { hash ->
                                    // Without the record the screen still shows the vote once the node has it in a block.
                                    runCatching {
                                        SentVotes.save(context, SentVote(me, contract, ready.option, hash, ready.voteBlock, System.currentTimeMillis()))
                                    }
                                }
                            }
                            busy = null
                            sent.onFailure { message = t(R.string.voting_vote_was_not_sent, it.message.toString()) }
                            reads++
                        }
                    }
                }
            }
        }
    }
}

/** Plans the vote once the node is synced: a node behind the chain has old committees and an old epoch. */
private fun planVote(context: Context, me: String, contract: String, option: Int): VotePlan {
    val rpc = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))
    if (!rpc.synced()) throw IOException(t(R.string.voting_syncing_vote_later))
    val epoch = (rpc.call("dna_epoch") as JSONObject).getInt("epoch")
    return votingNode(context).plan(me, contract, option, epoch)
}

@Composable
private fun VotingDetails(voting: OracleVoting, me: String) {
    Text(votingTitle(voting), style = MaterialTheme.typography.titleMedium)
    if (voting.fact.desc.isNotBlank()) SelectionContainer { Text(voting.fact.desc, style = MaterialTheme.typography.bodyMedium) }
    Field(t(R.string.voting_field_state), votingStateText(voting.state))
    // The author's name (given in Social, else claimed) and robot, as Social shows a person.
    val names = rememberShownNames(listOf(voting.author))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(t(R.string.voting_field_author), namedAddress(voting.author, names, me))
        Spacer(Modifier.width(8.dp))
        Avatar(voting.author, 24.dp)
    }
    val running = voting.state in listOf(VotingState.OPEN, VotingState.VOTED)
    if (running) voting.votingEnd?.let { Field(t(R.string.voting_field_secret_vote), t(R.string.voting_until, dateText(it))) }
    if (running || voting.state == VotingState.COUNTING || voting.state == VotingState.CAN_BE_PROLONGED) {
        voting.countingEnd?.let { Field(t(R.string.voting_field_counting), t(R.string.voting_until, dateText(it))) }
    }
    Field(t(R.string.voting_field_committee), t(R.string.voting_committee_value, voting.committeeSize, voting.quorumVotes))
    Field(t(R.string.voting_field_votes), t(R.string.voting_votes_value, voting.votesSent, voting.votes))
    if (voting.state == VotingState.PENDING) {
        val needed = (voting.ownerDeposit ?: BigDecimal.ZERO) + (voting.rewardsFund ?: BigDecimal.ZERO)
        Field(t(R.string.voting_field_funds), t(R.string.voting_funds_to_launch, idna(voting.balance.toPlainString()), idna(needed.toPlainString())))
    } else if (voting.state !in CLOSED_STATES) {
        Field(t(R.string.voting_field_funds), idna(voting.balance.toPlainString()))
    }
    if (voting.minPayment.signum() > 0) Field(t(R.string.voting_field_payment), t(R.string.voting_per_vote, idna(voting.minPayment.toPlainString())))
    if (running) voting.oracleReward?.takeIf { it.signum() > 0 }?.let { reward ->
        val most = voting.maxOracleReward?.takeIf { it > reward }
        Field(
            t(R.string.voting_field_reward),
            if (most != null) t(R.string.voting_about_per_oracle_up_to, idna(reward.toPlainString()), idna(most.toPlainString()))
            else t(R.string.voting_about_per_oracle, idna(reward.toPlainString())),
        )
    }
    voting.totalReward?.takeIf { it.signum() > 0 }?.let { Field(t(R.string.voting_field_rewards), idna(it.toPlainString())) }
}

/** The options: to choose one while [choosing] (a tap counts while [enabled]), else each with its opened votes and the result's mark. */
@Composable
private fun Options(voting: OracleVoting, selected: Int?, choosing: Boolean, enabled: Boolean, onSelect: (Int) -> Unit) {
    Text(t(R.string.voting_options), style = MaterialTheme.typography.titleSmall)
    if (voting.fact.options.isEmpty()) Note(t(R.string.voting_no_readable_option))
    voting.fact.options.forEach { option ->
        val votes = voting.optionVotes[option.id] ?: 0
        val counts = if (voting.votes > 0) tp(R.plurals.voting_option_votes, votes.toInt(), votes) else ""
        val result = if (voting.result == option.id) " ✓" else ""
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected == option.id, enabled = enabled, role = Role.RadioButton) { onSelect(option.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (choosing) RadioButton(selected = selected == option.id, onClick = null, enabled = enabled)
            Text(option.text + counts + result, Modifier.padding(vertical = 8.dp, horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun VoteConfirmDialog(voting: OracleVoting, plan: VotePlan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.voting_send_your_vote)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(voteConfirmText(voting, plan, ::dateText)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t(R.string.voting_vote_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
