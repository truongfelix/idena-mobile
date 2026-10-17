package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** What another person's profile reads for the vouching panel: [me]'s offer to them, and a challenge of their name. */
private class VouchState(val offer: Int?, val epoch: Int?, val challenge: NameChallenge?)

/**
 * On another person's profile ([profile], from the contract), as the desktop's: End the vouch when [me] vouches for
 * them; Vouch for them (an account without an identity, by a validated one), or the offer waiting with Withdraw; and
 * the challenge of a name whose holder is neither validated nor vouched for (3 calls, one an epoch, in a row: the
 * third gives the name to the challenger). [mine] is [me]'s own profile; [reload] reads again after a call in a block.
 */
@Composable
fun VouchPanel(address: String, profile: ContractProfile?, me: String?, mine: ContractProfile?, canAct: Boolean, reload: Int, onCall: ProfileAction) {
    val context = LocalContext.current
    if (profile == null || me == null) return
    val hiddenName = profile.name?.takeIf { !profile.inStanding }
    var state by remember(address) { mutableStateOf<VouchState?>(null) }
    LaunchedEffect(address, reload, hiddenName) {
        state = withContext(Dispatchers.IO) {
            runCatching {
                val source = NodeProfilesSource(rpc(context))
                VouchState(
                    offer = source.offer(address, me),
                    epoch = (rpc(context).call("dna_epoch") as JSONObject).optInt("epoch"),
                    challenge = hiddenName?.let { source.challenge(it, me) },
                )
            }.getOrNull()
        }
    }
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val iVouch = profile.voucher == me.lowercase()
    val canOffer = profile.voucher == null && isVouchable(profile.state) && isValidated(mine?.state)
    val loaded = state
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The contract ends the vouch at the member's next call; End frees the slot now.
        if (iVouch && profile.shownVoucher == null) Text("They have an identity now: End frees your slot.", style = small, color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (iVouch) {
                OutlinedButton(onClick = { onCall("End the vouch", "You no longer vouch for them", ProfileCalls.endVouch(address)) }, enabled = canAct) {
                    Text("End the vouch")
                }
            }
            if (canOffer && loaded != null && loaded.offer == null) {
                OutlinedButton(
                    onClick = {
                        onCall(
                            "Vouch for them",
                            "They become a member once they accept; you vouch for at most ${vouchCap(mine?.state)} members at a time",
                            ProfileCalls.vouch(address),
                        )
                    },
                    enabled = canAct,
                ) { Text("Vouch for them") }
            }
            if (canOffer && loaded?.offer != null) {
                OutlinedButton(onClick = { onCall("Withdraw the offer", "Your offer to vouch for them ends", ProfileCalls.cancelOffer(address)) }, enabled = canAct) {
                    Text("Withdraw the offer")
                }
            }
        }
        if (canOffer && loaded?.offer != null) Text("Your offer waits for them to accept it.", style = small, color = muted)
        if (hiddenName != null && loaded?.epoch != null) {
            ChallengeRow(hiddenName, challengeStep(loaded.challenge, loaded.epoch, address), mayClaim = mine?.inStanding == true, canAct, onCall)
        }
    }
}

/** The challenge of a hidden name: what it does, the button, and which call of the three comes next. */
@Composable
private fun ChallengeRow(name: String, step: ChallengeStep, mayClaim: Boolean, canAct: Boolean, onCall: ProfileAction) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val count = GRACE_EPOCHS + 1
    Text(
        "Its holder is neither validated nor vouched for. Challenging the name in $count epochs in a row gives it to you; " +
            "the holder keeps it by validating again meanwhile.",
        style = small,
        color = muted,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val detail = if (step.frees) "The third call: it gives you @$name" else "Call ${step.step} of $count on @$name"
                onCall("Challenge @$name", detail, ProfileCalls.challengeName(name))
            },
            enabled = canAct && step.canCall && mayClaim,
        ) { Text("Challenge @$name") }
        Text(
            if (step.canCall) "Next: call ${step.step} of $count" else "Called in this epoch: call ${step.step} of $count from the next one",
            style = small,
            color = muted,
        )
    }
    if (!mayClaim) Text("Only a validated or vouched account can take a name.", style = small, color = muted)
}

/** What the account's own profile reads for its vouching: the offers to it, the members it vouches for and theirs. */
private class MyVouches(val offers: List<VouchEntry>, val members: List<VouchEntry>, val memberProfiles: Map<String, ContractProfile>)

/**
 * On the account's own profile ([profile], from the contract), as the desktop's: its voucher (End), the offers to it
 * (Accept, Decline), and the members it vouches for (End each), with how many it may have.
 */
@Composable
fun MyVouching(me: String, profile: ContractProfile?, canAct: Boolean, reload: Int, onCall: ProfileAction) {
    val context = LocalContext.current
    var vouches by remember(me) { mutableStateOf<MyVouches?>(null) }
    LaunchedEffect(me, reload) {
        vouches = withContext(Dispatchers.IO) {
            runCatching {
                val source = NodeProfilesSource(rpc(context))
                val members = source.members(me)
                MyVouches(source.offersTo(me), members, source.profiles(members.map { it.address }))
            }.getOrNull()
        }
    }
    val names = LocalNames.current
    val body = MaterialTheme.typography.bodyMedium
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        profile?.voucher?.let { voucher ->
            val note = if (isActingMember(profile)) "" else " (not active now)"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vouched for by ${displayName(voucher, names)}$note", style = body)
                OutlinedButton(onClick = { onCall("End the vouch", "You are no longer a vouched member", ProfileCalls.endVouch()) }, enabled = canAct) {
                    Text("End")
                }
            }
        }
        if (isActingMember(profile)) {
            Text("As a vouched member, a name, a profile or a Trust costs about one more fee, burned.", style = small, color = muted)
        }
        vouches?.offers?.forEach { offer ->
            val name = displayName(offer.address, names)
            Text("$name offers to vouch for you.", style = body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onCall(
                            "Accept the vouch",
                            "You may then hold a name, write a profile and give Trust while $name is validated",
                            ProfileCalls.acceptVouch(offer.address),
                        )
                    },
                    enabled = canAct,
                ) { Text("Accept") }
                OutlinedButton(onClick = { onCall("Decline the offer", "The offer of $name ends", ProfileCalls.declineOffer(offer.address)) }, enabled = canAct) {
                    Text("Decline")
                }
            }
        }
        val members = vouches?.members.orEmpty()
        if (isValidated(profile?.state) && members.isNotEmpty()) {
            Text("You vouch for ${members.size} of at most ${vouchCap(profile?.state)}", style = body, fontWeight = FontWeight.Medium)
            members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayName(member.address, names), style = body)
                    // The contract ends the vouch at the member's next call; End frees the slot now.
                    vouches?.memberProfiles?.get(member.address)?.takeIf { !isVouchable(it.state) }?.let {
                        Text("has an identity now: End frees your slot", style = small, color = muted)
                    }
                    OutlinedButton(onClick = { onCall("End the vouch", "You no longer vouch for them", ProfileCalls.endVouch(member.address)) }, enabled = canAct) {
                        Text("End")
                    }
                }
            }
        }
    }
}
