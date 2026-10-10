package io.github.truongfelix.idenanode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/** The folded line under Advanced settings: what the settings are now. */
fun advancedSummary(form: NewVotingForm): String = listOf(
    "counting ${durationText(form.countingDuration)}",
    "committee ${form.committeeSize}",
    "quorum ${form.quorum}%",
    "majority ${form.winnerThreshold}%",
    if (form.paymentArg.signum() == 0) "free to vote" else "${idna(form.paymentArg.toPlainString())} per vote",
    "owner fee ${form.ownerFee}%",
    "rewards fund ${idna(form.rewardsFund.toPlainString())}",
).joinToString(" · ")

/**
 * Advanced settings, folded under their summary: the counting, the committee, the quorum, the majority, the payment
 * per vote, the owner fee, the rewards fund, the owner deposit ([network] identities: the contract's figure) and the
 * refund address. A number that does not read stays red, and its label goes to [onBadNumbers].
 */
@Composable
fun NewVotingAdvanced(form: NewVotingForm, network: Long?, onChange: (NewVotingForm) -> Unit, onBadNumbers: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The texts start again from the form when the kind changes its settings.
    val texts = remember(form.kind) {
        mutableStateOf(
            mapOf(
                COMMITTEE to form.committeeSize.toString(),
                QUORUM to form.quorum.toString(),
                PAYMENT to form.minPayment.toPlainString(),
                OWNER_FEE to form.ownerFee.toString(),
                FUND to form.rewardsFund.toPlainString(),
            ),
        )
    }
    val bad = texts.value.filter { (label, text) -> parse(label, text) == null }.keys
    LaunchedEffect(bad) { onBadNumbers(bad) }

    fun edit(label: String, text: String) {
        texts.value = texts.value + (label to text)
        val value = parse(label, text) ?: return
        onChange(
            when (label) {
                COMMITTEE -> form.copy(committeeSize = (value as Long))
                QUORUM -> form.copy(quorum = (value as Long).toInt())
                PAYMENT -> form.copy(minPayment = value as BigDecimal)
                OWNER_FEE -> form.copy(ownerFee = (value as Long).toInt())
                else -> form.copy(rewardsFund = value as BigDecimal)
            },
        )
    }

    @Composable
    fun Number(label: String, enabled: Boolean = true, help: String? = null) {
        OutlinedTextField(
            texts.value.getValue(label),
            { edit(label, it) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            isError = label in bad,
            supportingText = help?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (label == PAYMENT || label == FUND) KeyboardType.Decimal else KeyboardType.Number),
        )
    }

    TextButton(onClick = { open = !open }) { Text((if (open) "▾" else "▸") + " Advanced settings") }
    if (!open) {
        Text(advancedSummary(form), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Counting", style = MaterialTheme.typography.titleSmall)
        DurationPills(form.countingDuration) { onChange(form.copy(countingDuration = it)) }
        Number(COMMITTEE)
        val needed = maxOf(1L, (form.committeeSize * form.quorum + 99) / 100)
        Number(QUORUM, help = "$needed vote${if (needed == 1L) "" else "s"} needed")
        Text("Majority", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Majority.entries.forEach {
                FilterChip(selected = form.winnerThreshold == it.percent, onClick = { onChange(form.copy(winnerThreshold = it.percent)) }, label = { Text(it.title) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.freeVoting, onCheckedChange = { onChange(form.copy(freeVoting = it)) })
            Text("No payment per vote")
        }
        Number(PAYMENT, enabled = !form.freeVoting, help = "Each vote sends it to the voting; 0: free to vote")
        Number(OWNER_FEE, help = "Your share of what is added to the voting beyond the deposit, the rewards fund and the votes' payments")
        val perOracle = form.rewardsFund.divide(BigDecimal(maxOf(1L, form.committeeSize)), 4, RoundingMode.DOWN)
        Number(FUND, help = "About ${idna(perOracle.toPlainString())} per oracle")
        val deposit = network?.let { idna(ownerDeposit(it, form.committeeSize).toPlainString()) } ?: "…"
        Field("Owner deposit", deposit)
        Text(
            "Sent at launch with the rewards fund: 5000 iDNA shared by the network, times the committee. It comes back " +
                "at the end, with the owner fee.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            form.refundAddress,
            { onChange(form.copy(refundAddress = it.trim())) },
            Modifier.fillMaxWidth(),
            label = { Text("Refund address") },
            singleLine = true,
            supportingText = { Text("Where the deposit and the owner fee go back. Empty: to you.") },
        )
    }
}

private const val COMMITTEE = "Committee (oracles)"
private const val QUORUM = "Quorum (%)"
private const val PAYMENT = "Payment per vote (iDNA)"
private const val OWNER_FEE = "Owner fee (%)"
private const val FUND = "Rewards fund (iDNA)"

/** A number field's value: a whole number for the counts and percentages, an amount of iDNA for the others; null when it does not read. */
private fun parse(label: String, text: String): Any? = when (label) {
    PAYMENT, FUND -> text.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
    else -> text.trim().toLongOrNull()?.takeIf { it >= 0 }
}
