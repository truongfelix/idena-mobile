package io.github.truongfelix.idenanode

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* The Node tab's Profile card: the identity, its validation, its invitation and the screens behind it. */

/**
 * The Profile card, first on the Node tab: the avatar (drawn from the address), the identity and its age, the address
 * with copy; once the node has the account, the validation ([ValidationSection]) and the invitation that applies (a
 * code to activate, an invitation to accept, or the invitations to send); then the links to My flips, Practice
 * validation, History and Oracle votings.
 */
@Composable
fun ProfileCard(state: ScreenState, people: Map<String, Person>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val status = state.status
    val ready = status?.takeUnless { it.accountPending }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            val address = status?.address ?: state.keyAddress
            if (address == null) {
                Text("No key yet: import one below.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(address, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            status == null -> Text("Switch sync on to read the identity from the node.", style = MaterialTheme.typography.bodyMedium)
                            ready == null -> Text("Identity and age: after sync", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                val color = statusPalette()(ready.identityState)
                                Field("Identity", identityStatus(ready.identityState), color)
                                Field("Age", if (ready.age == 1) "1 epoch" else "${ready.age} epochs", color)
                            }
                        }
                    }
                }
                AddressLine(address)
                if (ready != null && ready.syncing) {
                    Text(
                        "Values are those of the synced block; they are current once the sync is done.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (ready != null) {
                SectionDivider()
                ValidationSection(ready)
                if (ready.identityState == "Undefined") {
                    SectionDivider()
                    ActivateCodeSection(ready, onChanged)
                }
                if (ready.identityState == "Invite") {
                    SectionDivider()
                    AcceptInvitationSection(ready, onChanged)
                }
                if (showInvitations(ready)) {
                    SectionDivider()
                    InvitationsSection(ready, people, onChanged)
                }
            }
            SectionDivider()
            // During the validation, only its own button: no practice next to it.
            val validating = ready != null && periodText(ready.period) != null
            if (ready != null && !validating) ProfileLink("My flips") { context.startActivity(Intent(context, FlipActivity::class.java)) }
            if (!validating) ProfileLink("Practice validation") { context.startActivity(Intent(context, CeremonyActivity::class.java)) }
            if (ready != null) ProfileLink("History") { context.startActivity(Intent(context, HistoryActivity::class.java)) }
            if (ready != null && !validating) OracleVotingsLink(ready)
            if (ready != null && !validating) ProfileLink("My ads") { context.startActivity(Intent(context, MyAdsActivity::class.java)) }
        }
    }
}

/**
 * The validation: its date once, with the time left (to the minute within a day of it or while it runs,
 * [validationIsClose]), the period and the button that opens it while it runs, the identity's task, and the flips,
 * with "Make a flip" while some are missing.
 */
@Composable
fun ValidationSection(status: NodeStatus) {
    val context = LocalContext.current
    val now = Instant.now()
    val start = parseValidationTime(status.nextValidation)
    val date = start?.let {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        val left = if (validationIsClose(now, it, status.period)) countdown(now, it) else timeUntil(now, it)
        "${format.format(it)} · $left"
    } ?: status.nextValidation
    val running = periodText(status.period)
    Text(if (running != null) "Validation · now" else "Next validation", style = MaterialTheme.typography.titleSmall)
    Field("Date", date)
    running?.let { Field("Now", it) }
    if (status.period in listOf("FlipLottery", "ShortSession", "LongSession")) {
        Button(
            onClick = { context.startActivity(Intent(context, ValidationActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open the validation") }
    }
    val answersSent = NodeSettings.answersSentFile(context, status.epoch, short = false).exists()
    Field(
        "My task",
        currentTask(status.period, status.identityState, status.requiredFlips, status.availableFlips, status.madeFlips, answersSent),
    )
    if (status.requiredFlips > 0 || status.availableFlips > 0) {
        val quota = FlipQuota(status.identityState, status.requiredFlips, status.availableFlips, status.madeFlips)
        val extra = if (quota.available > quota.required) " (up to ${quota.available})" else ""
        Field("Flips", "${quota.made} of ${quota.required} made$extra" + if (quota.missing == 0) " ✓" else "")
        if (quota.missing > 0) {
            Text(
                "Make ${quota.missing} more flip${if (quota.missing > 1) "s" else ""} before the flip lottery, 5 minutes " +
                    "before the validation; without them the identity becomes ${quota.missingOutcome}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (quota.canMake && status.period == "None") {
                Button(
                    onClick = { context.startActivity(Intent(context, FlipActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Make a flip") }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/**
 * The link to the oracle votings, with how many open votings of the identity's committees it has not voted in
 * yet: read from the node once it is synced, each time the app comes back and every 10 minutes.
 */
@Composable
private fun OracleVotingsLink(status: NodeStatus) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var toVote by remember(status.address) { mutableStateOf<String?>(null) }
    val synced = !status.syncing
    LaunchedEffect(status.address, synced) {
        if (!synced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withContext(Dispatchers.IO) { runCatching { toVoteText(votingNode(context).page(VotingTab.ToVote, status.address)) } }
                    .onSuccess { toVote = it }
                delay(10 * 60_000L)
            }
        }
    }
    ProfileLink("Oracle votings", toVote) { context.startActivity(Intent(context, OracleVotingsActivity::class.java)) }
}

/** A screen behind the profile: its title, what waits there ([detail]), and a chevron. */
@Composable
private fun ProfileLink(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
