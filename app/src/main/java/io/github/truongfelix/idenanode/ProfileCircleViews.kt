package io.github.truongfelix.idenanode

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
import kotlinx.coroutines.withContext

// A profile's people (B-79, B-89, user 2026-10-17): who trusts it and whom it trusts, the members it vouches for in
// the profiles contract, and whom it invited and the node still links to it (until each is Verified or Human, or
// killed). Counted in the profile's header, each count opening its list on a page of its own.

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** The lists of a profile's people, each a page of its own ([ProfilePeopleView]). */
enum class PeopleList(val title: String) {
    TrustedBy("Trusted by"),
    TrustsGiven("Trusts given"),
    Members("Members"),
    Invitees("Invitees"),
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
@Composable
fun PeopleCounts(trust: TrustCount?, circle: ProfileCircle?, onOpen: (PeopleList) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    @Composable
    fun Count(text: String, list: PeopleList) = Text(text, Modifier.clickable { onOpen(list) }, style = style, fontWeight = FontWeight.Medium)
    fun n(value: Int?) = value?.let { blockNumber.format(it) } ?: "…"
    Row {
        Text("🤝 ", style = style)
        Count("${n(trust?.total)} trusted by", PeopleList.TrustedBy)
        Text(" · ", style = style)
        Count("${n(circle?.trustsGiven?.size)} trusts given", PeopleList.TrustsGiven)
    }
    Row {
        Text("👥 ", style = style)
        Count("${n(circle?.members?.size)} ${if (circle?.members?.size == 1) "member" else "members"}", PeopleList.Members)
        Text(" · ", style = style)
        Count("${n(circle?.invitees?.size)} ${if (circle?.invitees?.size == 1) "invitee" else "invitees"}", PeopleList.Invitees)
    }
}

/** "Newbie · age 2", "Candidate": a person's state as a list shows it. */
fun circleStatus(identity: ProfileIdentity?): String {
    if (identity == null) return "Not validated"
    val age = if (identity.age > 0) " · age ${identity.age}" else ""
    return identity.status + age
}

/** "12 identities · 3 vouched members": the split of a "Trusted by" sum. */
fun trustSplit(trust: TrustCount): String =
    "${count(trust.identities, "identity", "identities")} · ${count(trust.vouched, "vouched member", "vouched members")}"

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
    PeopleList.TrustedBy -> "Nobody's Trust counts yet."
    PeopleList.TrustsGiven -> if (isMe) "You give no Trust yet: Trust on a profile or a topic's page." else "No Trust given."
    PeopleList.Members -> "No vouched member."
    PeopleList.Invitees -> "No invitee on the way: an invitee shows here from its invitation until it is Verified."
}

/** What a people page reads: the profile's circle, and for "Trusted by" its Trust. */
private class PeoplePage(val circle: ProfileCircle, val trust: TrustCount?)

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
    LaunchedEffect(address, list, reload) {
        page = withContext(Dispatchers.IO) {
            runCatching {
                val identity = NodeSocialSource(rpc(context)).identity(address)
                val trust = if (list == PeopleList.TrustedBy) NodeProfilesSource(rpc(context)).trust(address) else null
                val givers = trust?.let { it.counted.map { g -> g.address } + it.vouchers.values }.orEmpty()
                PeoplePage(loadCircle(context, address, identity, isMe, givers), trust)
            }
        }
    }
    val names = LocalNames.current
    val body = MaterialTheme.typography.bodyMedium
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("${displayName(address, names)} · ${list.title}", style = MaterialTheme.typography.titleLarge) }
        val loaded = page
        when {
            loaded == null -> item { Text("Asking the node…", style = body) }
            loaded.isFailure -> item { Text("The node did not answer: ${loaded.exceptionOrNull()?.message}", style = body) }
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
                        if (identities.isNotEmpty()) item { Heading("Identities") }
                        items(identities, key = { "id:${it.address}" }) { CirclePersonRow(it.address, c.identities[it.address], onProfile) }
                        if (vouched.isNotEmpty()) item { Heading("Vouched members") }
                        items(vouched, key = { "vouched:${it.address}" }) { giver ->
                            val voucher = trust.vouchers[giver.address]
                            CirclePersonRow(giver.address, c.identities[giver.address], onProfile, note = voucher?.let { "vouched by ${displayName(it, names)}" })
                        }
                    }
                    PeopleList.TrustsGiven -> {
                        if (c.trustsGiven.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.trustsGiven, key = { "trust:${it.address}" }) { TrustGivenRow(it.address, c.identities[it.address], onProfile, mine = isMe) }
                    }
                    PeopleList.Members -> {
                        if (c.members.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.members, key = { "member:$it" }) { CirclePersonRow(it, c.identities[it], onProfile) }
                        // On one's own profile, the offers made that wait for an answer (B-81).
                        item(key = "offers") { Column { OffersSentSection(c.offersSent, c.identities, onProfile) } }
                    }
                    PeopleList.Invitees -> {
                        if (c.invitees.isEmpty()) item { Text(emptyPeopleText(list, isMe), style = body) }
                        items(c.invitees, key = { "invitee:$it" }) { CirclePersonRow(it, c.identities[it], onProfile) }
                    }
                }
            }
        }
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
    Heading("Offers waiting")
    offers.forEach { offer ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CirclePersonRow(offer.address, identities[offer.address], onProfile)
                Text("Offered in epoch ${offer.epoch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (access != null) {
                ActionButton("Withdraw", "Withdraw the offer", "Your offer to vouch for them ends", ProfileCalls.cancelOffer(offer.address), access.canAct, access.onCall)
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
            ActionButton("Take back", "Take your Trust back", "Your Trust no longer counts", ProfileCalls.untrust(target), access.canAct, access.onCall)
        }
    }
}
