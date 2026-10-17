package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** What another person's profile reads for its vouching: [me]'s offer to them, the epoch, and a challenge of their name. */
class VouchState(val offer: Int?, val epoch: Int?, val challenge: NameChallenge?)

/** [VouchState] of [address] ([profile], from the contract) for [me]: null while read; read again after a call in a block ([reload]). */
@Composable
fun rememberVouchState(address: String, profile: ContractProfile?, me: String?, reload: Int): VouchState? {
    val context = LocalContext.current
    val hiddenName = profile?.name?.takeIf { !profile.inStanding }
    var state by remember(address) { mutableStateOf<VouchState?>(null) }
    LaunchedEffect(address, reload, hiddenName, me, profile != null) {
        if (profile == null || me == null) return@LaunchedEffect
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
    return state
}

/** Whether [me] may offer to vouch for [profile]: no voucher yet, an account without an identity, [mine] validated. */
private fun canOffer(profile: ContractProfile, mine: ContractProfile?) =
    profile.voucher == null && isVouchable(profile.state) && isValidated(mine?.state)

/**
 * Vouch on another person's row of buttons ([profile], from the contract), as the desktop's calls: "Vouching ✓" when [me]
 * vouches for them (a tap stops it); Vouch for an account without an identity, by a validated [mine]; "Offer sent"
 * while the offer waits (a tap withdraws it); none otherwise.
 */
@Composable
fun VouchButton(address: String, profile: ContractProfile?, me: String?, mine: ContractProfile?, state: VouchState?, canAct: Boolean, onCall: ProfileAction) {
    if (profile == null || me == null) return
    when {
        profile.voucher == me.lowercase() ->
            ProfileRowAction(t(R.string.profile_vouching), done = true, t(R.string.profile_end_vouch_what), t(R.string.profile_end_vouch_detail), ProfileCalls.endVouch(address), canAct, onCall)
        canOffer(profile, mine) && state != null && state.offer == null ->
            ProfileRowAction(
                t(R.string.profile_vouch_button), done = false, t(R.string.profile_vouch_for_them),
                t(R.string.profile_vouch_detail, vouchCap(mine?.state)), ProfileCalls.vouch(address), canAct, onCall,
            )
        canOffer(profile, mine) && state?.offer != null ->
            ProfileRowAction(t(R.string.profile_offer_sent), done = true, t(R.string.profile_withdraw_what), t(R.string.profile_withdraw_detail), ProfileCalls.cancelOffer(address), canAct, onCall)
    }
}

/**
 * Under another person's profile ([profile]): what its Vouch button leaves to say (they have an identity now; the offer
 * waits), and the challenge of a name whose holder is neither validated nor vouched for (3 calls, one an epoch, in a
 * row: the third gives the name to the challenger). [mine] is [me]'s own profile.
 */
@Composable
fun VouchPanel(address: String, profile: ContractProfile?, me: String?, mine: ContractProfile?, state: VouchState?, canAct: Boolean, onCall: ProfileAction) {
    if (profile == null || me == null) return
    val hiddenName = profile.name?.takeIf { !profile.inStanding }
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The contract ends the vouch at the member's next call; Stop vouching frees the slot now.
        if (profile.voucher == me.lowercase() && profile.shownVoucher == null) Text(t(R.string.profile_they_have_identity), style = small, color = muted)
        if (canOffer(profile, mine) && state?.offer != null) Text(t(R.string.profile_offer_waits), style = small, color = muted)
        if (hiddenName != null && state?.epoch != null) {
            ChallengeRow(hiddenName, challengeStep(state.challenge, state.epoch, address), mayClaim = mine?.inStanding == true, canAct, onCall)
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
        t(R.string.profile_challenge_note, count),
        style = small,
        color = muted,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val detail = if (step.frees) t(R.string.profile_challenge_third, name) else t(R.string.profile_challenge_call, step.step, count, name)
        ActionButton(t(R.string.profile_challenge, name), t(R.string.profile_challenge, name), detail, ProfileCalls.challengeName(name), canAct, onCall, enabled = step.canCall && mayClaim)
        Text(
            if (step.canCall) t(R.string.profile_challenge_next, step.step, count) else t(R.string.profile_challenge_called, step.step, count),
            style = small,
            color = muted,
        )
    }
    if (!mayClaim) Text(t(R.string.profile_only_validated_take_name), style = small, color = muted)
}

/** What the account's own profile reads for its vouching: the offers to it. */
private class MyVouches(val offers: List<VouchEntry>)

/**
 * On the account's own profile ([profile], from the contract): its voucher (End) and the offers to it (Accept,
 * Decline). The members it vouches for, with End and the cap, are on its Members page (B-91).
 */
@Composable
fun MyVouching(me: String, profile: ContractProfile?, canAct: Boolean, reload: Int, onCall: ProfileAction) {
    val context = LocalContext.current
    var vouches by remember(me) { mutableStateOf<MyVouches?>(null) }
    LaunchedEffect(me, reload) {
        vouches = withContext(Dispatchers.IO) {
            runCatching {
                MyVouches(NodeProfilesSource(rpc(context)).offersTo(me))
            }.getOrNull()
        }
    }
    val names = LocalNames.current
    val body = MaterialTheme.typography.bodyMedium
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        profile?.voucher?.let { voucher ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isActingMember(profile)) t(R.string.profile_vouched_for_by, displayName(voucher, names)) else t(R.string.profile_vouched_for_by_inactive, displayName(voucher, names)), style = body)
                ActionButton(t(R.string.profile_end), t(R.string.profile_leave_voucher), t(R.string.profile_end_own_vouch_detail), ProfileCalls.endVouch(), canAct, onCall)
            }
        }
        if (isActingMember(profile)) {
            Text(t(R.string.profile_member_fee_note), style = small, color = muted)
        }
        vouches?.offers?.forEach { offer ->
            val name = displayName(offer.address, names)
            Text(t(R.string.socialb_offer_vouch, name), style = body)
            OfferAnswerButtons(offer.address, name, canAct, onCall)
        }
    }
}

/**
 * A profile action's button: [label], sending [call] ([what] and [detail] for its confirmation); while the same call
 * waits for its block ([ProfileAccess.waiting]) it says so and cannot be sent again (B-81).
 */
@Composable
fun ActionButton(label: String, what: String, detail: String?, call: SocialCall, canAct: Boolean, onCall: ProfileAction, enabled: Boolean = true) {
    val waiting = LocalProfileAccess.current?.waiting?.invoke(call) == true
    OutlinedButton(onClick = { onCall(what, detail, call) }, enabled = canAct && enabled && !waiting) {
        Text(if (waiting) t(R.string.profile_action_waiting, label) else label)
    }
}

/**
 * A button of another person's row (Message, Trust, Follow, Vouch, owner 2026-10-17): filled when [done] (Trusted,
 * Following, Vouching), outlined otherwise; narrow paddings so that the four fit on a phone's width.
 */
@Composable
fun ProfileRowButton(label: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    if (done) Button(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label, maxLines = 1) }
    else OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label, maxLines = 1) }
}

/** [ProfileRowButton] sending [call] ([what] and [detail] for its confirmation), as [ActionButton]. */
@Composable
private fun ProfileRowAction(label: String, done: Boolean, what: String, detail: String?, call: SocialCall, canAct: Boolean, onCall: ProfileAction) {
    val waiting = LocalProfileAccess.current?.waiting?.invoke(call) == true
    ProfileRowButton(if (waiting) t(R.string.profile_action_waiting, label) else label, done, enabled = canAct && !waiting) { onCall(what, detail, call) }
}

/** Accept and Decline of a vouch offer from [voucher] ([name] as shown). */
@Composable
fun OfferAnswerButtons(voucher: String, name: String, canAct: Boolean, onCall: ProfileAction) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            t(R.string.socialb_accept), t(R.string.profile_accept_vouch), t(R.string.profile_accept_vouch_detail, name),
            ProfileCalls.acceptVouch(voucher), canAct, onCall,
        )
        ActionButton(t(R.string.profile_decline), t(R.string.profile_decline_offer), t(R.string.profile_decline_detail, name), ProfileCalls.declineOffer(voucher), canAct, onCall)
    }
}
