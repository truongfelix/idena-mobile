package io.github.truongfelix.idenanode

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The Oracle votings screen, opened from the Profile card: the votings of the identity's committees (to vote in,
 * ongoing, closed), every voting, and the identity's own (created or voted in), from the app's own node; a voting
 * opens on its own screen, where the identity votes, and + New opens the New voting form.
 */
class OracleVotingsActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { padding ->
                    OracleVotingsScreen(onClose = { finish() }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

internal fun votingNode(context: Context) = OracleVotingNode(NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)))

@Composable
fun OracleVotingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(VotingTab.ToVote) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Back from a voting, the list is read again: a vote moves it to Ongoing.
    var reloads by rememberSaveable { mutableIntStateOf(0) }
    var reads by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(reads) {
        address = withContext(Dispatchers.IO) {
            runCatching { (NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context)).call("dna_getCoinbaseAddr") as String).lowercase() }
        }
    }
    val me = address?.getOrNull()
    val contract = opened
    if (contract != null && me != null) {
        val back: () -> Unit = {
            opened = null
            reloads++
        }
        BackHandler(onBack = back)
        OracleVotingScreen(contract, me, onClose = back, modifier = modifier)
        return
    }
    if (creating && me != null) {
        BackHandler { creating = false }
        NewVotingScreen(
            me,
            onClose = { creating = false },
            onPublished = {
                creating = false
                opened = it
                tab = VotingTab.Mine
            },
            modifier = modifier,
        )
        return
    }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle(t(R.string.voting_title_list), onClose) {
            TextButton(onClick = { creating = true }, enabled = me != null) { Text(t(R.string.voting_new_button)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VotingTab.entries.forEach {
                FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it.title) })
            }
        }
        val current = address
        when {
            current == null -> CircularProgressIndicator()
            me == null -> {
                Text(t(R.string.voting_node_no_answer, current.exceptionOrNull()?.message.toString()))
                Button(onClick = { reads++ }) { Text(t(R.string.common_try_again)) }
            }
            else -> VotingList(tab, me, reloads, onOpen = { opened = it }, Modifier.weight(1f))
        }
    }
}

/** The title row of the votings' screens: Close, the title in the middle, and [action] at the right. */
@Composable
internal fun ScreenTitle(title: String, onClose: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text(t(R.string.common_close)) }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) { action?.invoke() }
    }
}

/** The pages of a list read so far, and the token of the next one. */
private class LoadedVotings {
    val votings = mutableStateListOf<OracleVoting>()
    var next by mutableStateOf<String?>(null)
    var pages by mutableIntStateOf(1)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
}

private val TAB_EMPTY = mapOf(
    VotingTab.ToVote to R.string.voting_empty_to_vote,
    VotingTab.Ongoing to R.string.voting_empty_ongoing,
    VotingTab.Closed to R.string.voting_empty_closed,
    VotingTab.All to R.string.voting_empty_all,
    VotingTab.Mine to R.string.voting_empty_mine,
)

@Composable
private fun VotingList(tab: VotingTab, oracle: String, reloads: Int, onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val list = remember(tab, oracle, reloads) { LoadedVotings() }
    LaunchedEffect(list, list.pages) {
        list.loading = true
        val token = list.next
        val page = withContext(Dispatchers.IO) { runCatching { votingNode(context).page(tab, oracle, token) } }
        page.onSuccess { read ->
            // A voting that changed between two pages comes again: listed once (the list's keys).
            val listed = list.votings.mapTo(HashSet()) { it.contract }
            list.votings += read.votings.filter { listed.add(it.contract) }
            list.next = read.next
        }
        list.error = page.exceptionOrNull()?.let { t(R.string.voting_node_no_answer, it.message.toString()) }
        list.loading = false
    }
    val now = Instant.now()
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list.votings, key = { it.contract }) { voting -> VotingRow(voting, now) { onOpen(voting.contract) } }
        item {
            when {
                list.loading -> CircularProgressIndicator()
                list.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(list.error!!)
                    // The page that failed is read again.
                    Button(onClick = { list.pages++ }) { Text(t(R.string.common_try_again)) }
                }
                list.next != null -> OutlinedButton(onClick = { list.pages++ }) { Text(t(R.string.common_more)) }
                list.votings.isEmpty() -> Text(t(TAB_EMPTY.getValue(tab)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun VotingRow(voting: OracleVoting, now: Instant, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(votingTitle(voting), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(votingSummary(voting, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
