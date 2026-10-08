package io.github.truongfelix.idenanode

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* The Node tab's Account and Validation cards. */

/** The account: its avatar (drawn from the address), the address with copy, the identity and its age, and History. */
@Composable
fun AccountCard(state: ScreenState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            val address = status?.address ?: state.keyAddress
            if (address == null) {
                Text("No key yet: import one below.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(address, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        status == null -> Text("Switch sync on to read the identity from the node.", style = MaterialTheme.typography.bodyMedium)
                        status.accountPending -> Text("Identity and age: after sync", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            val color = statusPalette()(status.identityState)
                            Field("Identity", identityStatus(status.identityState), color)
                            Field("Age", if (status.age == 1) "1 epoch" else "${status.age} epochs", color)
                        }
                    }
                }
            }
            AddressLine(address)
            if (status != null && !status.accountPending) {
                OutlinedButton(onClick = { context.startActivity(Intent(context, HistoryActivity::class.java)) }) {
                    Text("History")
                }
            }
            if (status != null && !status.accountPending && status.identityState == "Undefined") ActivateCodeSection(status, onChanged)
            if (status != null && !status.accountPending && status.syncing) {
                Text(
                    "Values are those of the synced block; they are current once the sync is done.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The next validation, and the flips still to make with a link to the web app to make them; with
 * [showCountdown] (the card comes first, within a day of it), the time left in its title.
 */
@Composable
fun ValidationCard(status: NodeStatus, showCountdown: Boolean) {
    val context = LocalContext.current
    val start = parseValidationTime(status.nextValidation)
    val date = start?.let {
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        "${format.format(it)} (${timeUntil(Instant.now(), it)})"
    } ?: status.nextValidation
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when {
                periodText(status.period) != null -> "Validation · now"
                showCountdown && start != null -> "Next validation · ${countdown(Instant.now(), start)}"
                else -> "Next validation"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            val answersSent = NodeSettings.answersSentFile(context, status.epoch, short = false).exists()
            Field(
                "My task",
                currentTask(status.period, status.identityState, status.requiredFlips, status.availableFlips, status.madeFlips, answersSent),
            )
            Field("Date", date)
            periodText(status.period)?.let { Field("Now", it) }
            if (status.period in listOf("FlipLottery", "ShortSession", "LongSession")) {
                Button(
                    onClick = { context.startActivity(Intent(context, ValidationActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open the validation") }
            }
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
                }
                if ((quota.canMake || quota.made > 0) && status.period == "None") {
                    Button(
                        onClick = { context.startActivity(Intent(context, FlipActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (quota.made > 0) "My flips" else "Make a flip") }
                }
            }
        }
    }
}
