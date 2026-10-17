package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.annotation.StringRes
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

// A profile's people (B-79, B-89, user 2026-10-17): who trusts it and whom it trusts, the members it vouches for in
// the profiles contract, and whom it invited and the node still links to it (until each is Verified or Human, or
// killed). Counted in the profile's header, each count opening its list on a page of its own.

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** The lists of a profile's people, each a page of its own ([ProfilePeopleView]). */
enum class PeopleList(@StringRes private val titleId: Int) {
    TrustedBy(R.string.profile_list_trusted_by),
    TrustsGiven(R.string.profile_list_trusts_given),
    Members(R.string.profile_list_members),
    Invitees(R.string.profile_list_invitees),
    ;

    val title: String
        get() = t(titleId)
}

/**
 * The invitees and members of a profile (lowercase addresses), the Trusts it gives ([trustsGiven]: addresses and
 * "r/<topic>"), and the identity of each person, read together; on one's own profile also the vouch offers it made
 * that wait ([offersSent], B-81).
 */
data class ProfileCircle(
    val invitees: List<String>,
    val members: List<String>,
    val identities: Map<String, ProfileIdentity>,
    val offersSent: List<VouchEntry> = emptyList(),
    val trustsGiven: List<VouchEntry> = emptyList(),
)

/** Reads [address]'s circle: its members, the Trusts it gives, and on one's own profile ([isMe]) its offers waiting. */
private fun loadCircle(context: Context, address: String, identity: ProfileIdentity, isMe: Boolean, more: List<String> = emptyList()): ProfileCircle {
    val source = NodeProfilesSource(rpc(context))
    val members = source.members(address).map { it.address }
    val offers = if (isMe) source.offersBy(address) else emptyList()
    val trusts = source.trustsBy(address)
    val people = identity.invitees + members + offers.map { it.address } + trusts.map { it.address }.filter(::isAddress) + more
    return ProfileCircle(identity.invitees, members, NodeSocialSource(rpc(context)).identities(people), offers, trusts)
}

/**
 * The circle of [address], once its [identity] is read (the invitees come with it), and again after each profile call
 * in a block ([reload]); null while it is read.
 */
@Composable
fun rememberProfileCircle(address: String, identity: ProfileIdentity?, reload: Int, isMe: Boolean = false): Result<ProfileCircle>? {
    val context = LocalContext.current
    var circle by remember(address) { mutableStateOf<Result<ProfileCircle>?>(null) }
    LaunchedEffect(address, identity, reload) {
        if (identity == null) return@LaunchedEffect
        circle = withContext(Dispatchers.IO) { runCatching { loadCircle(context, address, identity, isMe) } }
    }
    return circle
}

/**
 * The counts in a profile's header, as followers and following on other apps: "🤝 15 trusted by · 4 trusts given" and
 * "👥 3 members · 2 invitees"; a tap on a count opens its list ([onOpen]). "…" while read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleCounts(trust: TrustCount?, circle: ProfileCircle?, onOpen: (PeopleList) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    @Composable
    fun Count(text: String, list: PeopleList) = Text(text, Modifier.clickable { onOpen(list) }, style = style, fontWeight = FontWeight.Medium)
    fun n(value: Int?) = value?.let { blockNumber.format(it) } ?: "…"
    // Each count goes on the next line when the line is full (a large font), never squeezed beside the other.
    FlowRow {
        Text("🤝 ", style = style)
        Count(t(R.string.profile_count_trusted_by, n(trust?.total)), PeopleList.TrustedBy)
        Text(" · ", style = style)
        Count(t(R.string.profile_count_trusts_given, n(circle?.trustsGiven?.size)), PeopleList.TrustsGiven)
    }
    FlowRow {
        Text("👥 ", style = style)
        Count(tp(R.plurals.profile_count_members, circle?.members?.size ?: 0, n(circle?.members?.size)), PeopleList.Members)
        Text(" · ", style = style)
        Count(tp(R.plurals.profile_count_invitees, circle?.invitees?.size ?: 0, n(circle?.invitees?.size)), PeopleList.Invitees)
    }
}

/** "Newbie · age 2", "Candidate": a person's state as a list shows it. */
fun circleStatus(identity: ProfileIdentity?): String {
    if (identity == null) return t(R.string.profile_not_validated)
    return if (identity.age > 0) t(R.string.profile_status_age, identity.status, identity.age) else identity.status
}

/** "12 identities · 3 vouched members": the split of a "Trusted by" sum. */
fun trustSplit(trust: TrustCount): String =
    tp(R.plurals.profile_n_identities, trust.identities, trust.identities) + " · " + tp(R.plurals.profile_n_vouched_members, trust.vouched, trust.vouched)

/** One person of a profile's lists: picture or robot, name, state ([note] after it); a tap opens its profile. */
@Composable
fun CirclePersonRow(address: String, identity: ProfileIdentity?, onProfile: (String) -> Unit, note: String? = null) {
    val names = LocalNames.current
    Row(
        Modifier.fillMaxWidth().clickable { onProfile(address) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonAvatar(address, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(displayName(address, names), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(circleStatus(identity) + (note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What an empty list says; [isMe]: one's own profile. */
fun emptyPeopleText(list: PeopleList, isMe: Boolean): String = when (list) {
    PeopleList.TrustedBy -> t(R.string.profile_empty_trusted_by)
    PeopleList.TrustsGiven -> if (isMe) t(R.string.profile_empty_trusts_given_me) else t(R.string.profile_empty_trusts_given)
    PeopleList.Members -> t(R.string.profile_empty_members)
    PeopleList.Invitees -> t(R.string.profile_empty_invitees)
}

/**
 * What a people page reads: the profile's circle, and for "Trusted by" its Trust; its [identity] (the cap of its
 * members, its inviter) and the epoch's [period] (an invitation is terminated outside the validation only).
 */
private class PeoplePage(val circle: ProfileCircle, val trust: TrustCount?, val identity: ProfileIdentity, val period: String)

/**
 * One people list of [address]'s profile ([list]), on a page of its own: "Trusted by" with its split, then the
 * identities, then the vouched members (each with its voucher); the Trusts given (Take back on one's own); the
 * members (and one's own offers waiting); the invitees. Read again after each profile call in a block ([reload]).
 */
@Composable
fun ProfilePeopleView(address: String, list: PeopleList, myAddress: String?, reload: Int, onProfile: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isMe = address.equals(myAddress, ignoreCase = true)
    var page by remember(address, list) { mutableStateOf<Result<PeoplePage>?>(null) }
    // Read again after a termination sent from this page is in a block.
    var terminated by remember { mutableIntStateOf(0) }
    LaunchedEffect(address, list, reload, terminated) {
        page = withContext(Dispatchers.IO) {
            runCatching {
                val identity = NodeSocialSource(rpc(context)).identity(address)
                val trust = if (list == PeopleList.TrustedBy) NodeProfilesSource(rpc(context)).trust(address) else null
                val givers = trust?.let { it.counted.map { g -> g.address } + it.vouchers.values }.orEmpty() + listOfNotNull(identity.inviter)
                val period = (rpc(context).call("dna_epoch") as JSONObject).optString("currentPeriod")
                PeoplePage(loadCircle(context, address, identity, isMe, givers), trust, identity, period)
            }
        }
    }
    val names = LocalNames.current
    val body = MaterialTheme.typography.bodyMedium
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("${displayName(address, names)} · ${list.title}", style = MaterialTheme.typography.titleLarge) }
        val loaded = page
        when {
            loaded == null -> item { Text(t(R.string.profile_asking_node), style = body) }
            loaded.isFailure -> item { Text(t(R.string.profile_node_no_answer, "${loaded.exceptionOrNull()?.message}"), style = body) }
            else -> {
                val p = loaded.getOrThrow()
                val c = p.circle
                when (list) {
                    PeopleList.TrustedBy -> {
                        val trust = p.trust ?: return@LazyColumn
                        item { Text(trustSplit(trust), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (trust.counted.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        val identities = trust.counted.filter { !it.vouched }
                        val vouched = trust.counted.filter { it.vouched }
                        if (identities.isNotEmpty()) item { Heading(t(R.string.profile_heading_identities)) }
                        items(identities, key = { "id:${it.address}" }) { CirclePersonRow(it.address, c.identities[it.address], onProfile) }
                        if (vouched.isNotEmpty()) item { Heading(t(R.string.profile_heading_vouched_members)) }
                        items(vouched, key = { "vouched:${it.address}" }) { giver ->
                            val voucher = trust.vouchers[giver.address]
                            CirclePersonRow(giver.address, c.identities[giver.address], onProfile, note = voucher?.let { t(R.string.profile_vouched_by, displayName(it, names)) })
                        }
                    }
                    PeopleList.TrustsGiven -> {
                        if (c.trustsGiven.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.trustsGiven, key = { "trust:${it.address}" }) { TrustGivenRow(it.address, c.identities[it.address], onProfile, mine = isMe) }
                    }
                    PeopleList.Members -> {
                        // One's own: the cap, and End for each (B-91); the contract ends a member's vouch at its next
                        // call once it has an identity, End frees the slot now.
                        if (isMe && c.members.isNotEmpty()) {
                            item { Text(t(R.string.profile_vouch_cap, c.members.size, vouchCap(p.identity.state)), style = body, fontWeight = FontWeight.Medium) }
                        }
                        if (c.members.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.members, key = { "member:$it" }) { member ->
                            val identity = c.identities[member]
                            val note = if (isMe && identity != null && !isVouchable(identity.state)) t(R.string.profile_member_has_identity) else null
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { CirclePersonRow(member, identity, onProfile, note) }
                                val access = LocalProfileAccess.current
                                if (isMe && access != null) {
                                    ActionButton(t(R.string.profile_end), t(R.string.profile_end_vouch_what), t(R.string.profile_end_vouch_detail), ProfileCalls.endVouch(member), access.canAct, access.onCall)
                                }
                            }
                        }
                        // On one's own profile, the offers made that wait for an answer (B-81).
                        item(key = "offers") { Column { OffersSentSection(c.offersSent, c.identities, onProfile) } }
                    }
                    PeopleList.Invitees -> {
                        // Who invited this profile first, while the node keeps the link (B-91).
                        p.identity.inviter?.let { inviter ->
                            item {
                                Heading(t(R.string.profile_heading_invited_by))
                                CirclePersonRow(inviter, c.identities[inviter], onProfile)
                            }
                            item { Heading(t(R.string.profile_list_invitees)) }
                        }
                        if (c.invitees.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.invitees, key = { "invitee:$it" }) { invitee ->
                            val identity = c.identities[invitee]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { CirclePersonRow(invitee, identity, onProfile) }
                                // One's own invitee not validated yet can be terminated, outside the validation.
                                if (isMe && canCancelInvite(identity?.state, p.period)) {
                                    TerminateButton(myAddress!!, invitee, displayName(invitee, names)) { terminated++ }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Terminate an invitation ([invitee], invited by [me]): KillInviteeTx, its fee asked of the node and shown first, then
 * sent; "· waiting for a block" until it is in one, then [onDone]. The invite is used up either way.
 */
@Composable
private fun TerminateButton(me: String, invitee: String, name: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fee by remember(invitee) { mutableStateOf<BigDecimal?>(null) }
    var busy by remember(invitee) { mutableStateOf(false) }
    var waiting by remember(invitee) { mutableStateOf(false) }
    var note by remember(invitee) { mutableStateOf<String?>(null) }
    Column {
        OutlinedButton(
            onClick = {
                busy = true
                note = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { rpc(context).estimateTx(KILL_INVITEE_TX, me, invitee, BigDecimal.ZERO) } }
                        .onSuccess { fee = it }
                        .onFailure { note = t(R.string.profile_node_refuses, "${it.message}") }
                    busy = false
                }
            },
            enabled = !busy && !waiting,
        ) { Text(if (waiting) t(R.string.profile_terminate_waiting) else t(R.string.profile_terminate)) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    fee?.let { f ->
        AlertDialog(
            onDismissRequest = { fee = null },
            title = { Text(t(R.string.profile_terminate_ask, name)) },
            text = {
                Text(t(R.string.profile_terminate_note, f.setScale(4, RoundingMode.UP).stripTrailingZeros().toPlainString()))
            },
            confirmButton = {
                TextButton(onClick = {
                    fee = null
                    waiting = true
                    scope.launch {
                        val hash = withContext(Dispatchers.IO) { runCatching { rpc(context).sendTx(KILL_INVITEE_TX, me, invitee, BigDecimal.ZERO) } }
                            .getOrElse { waiting = false; note = t(R.string.profile_node_refused, "${it.message}"); return@launch }
                        val mined = waitUntilMined(context, hash)
                        waiting = false
                        if (mined) onDone() else note = t(R.string.profile_not_terminated, NOT_SENT)
                    }
                }) { Text(t(R.string.profile_terminate)) }
            },
            dismissButton = { TextButton(onClick = { fee = null }) { Text(t(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
}

/** The vouch offers the account made that wait for an answer: each person, the epoch offered, and Withdraw. */
@Composable
fun OffersSentSection(offers: List<VouchEntry>, identities: Map<String, ProfileIdentity>, onProfile: (String) -> Unit) {
    if (offers.isEmpty()) return
    val access = LocalProfileAccess.current
    Heading(t(R.string.profile_offers_waiting))
    offers.forEach { offer ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CirclePersonRow(offer.address, identities[offer.address], onProfile)
                Text(t(R.string.profile_offered_in_epoch, "${offer.epoch}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (access != null) {
                ActionButton(t(R.string.profile_withdraw), t(R.string.profile_withdraw_what), t(R.string.profile_withdraw_detail), ProfileCalls.cancelOffer(offer.address), access.canAct, access.onCall)
            }
        }
    }
}

/** A Trust given: the person (or the topic, "r/<name>", opening its page), and Take back when it is one's own ([mine]). */
@Composable
fun TrustGivenRow(target: String, identity: ProfileIdentity?, onProfile: (String) -> Unit, mine: Boolean) {
    val access = LocalProfileAccess.current
    val openTopic = LocalOpenTopic.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val topic = topicOf(target)
            if (topic != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { openTopic?.invoke(topic) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TopicAvatar(topic, 40.dp)
                    Text(topicChannel(topic), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            } else {
                CirclePersonRow(target, identity, onProfile)
            }
        }
        if (mine && access != null) {
            ActionButton(t(R.string.profile_take_back), t(R.string.profile_untrust_what), t(R.string.profile_untrust_detail_short), ProfileCalls.untrust(target), access.canAct, access.onCall)
        }
    }
}
