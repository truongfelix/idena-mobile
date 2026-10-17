package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Email

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

private fun SocialProfile.list(tab: ProfileTab): List<ProfileItem> = when (tab) {
    ProfileTab.Posts -> posts
    ProfileTab.Replies -> replies
    ProfileTab.Comments -> comments
    ProfileTab.Likes -> likes
    ProfileTab.Tips -> tips
    ProfileTab.Media -> media
}

/**
 * The profile of [address], as idena.social's: the identity (state, age and stake from the node), what
 * it received and gave, then one list at a time ([tab]): its posts with their pills as in the feed;
 * its replies, comments, likes, tips and media as entries that open their thread at them.
 */
@Composable
fun ProfileList(
    address: String,
    feed: List<SocialPost>,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    historyDone: Boolean,
    person: Person,
    onFollow: (Person) -> Unit,
    onName: (Person) -> Unit,
    onBlock: (Person) -> Unit,
    listState: LazyListState,
    tab: ProfileTab,
    onTab: (ProfileTab) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
    onProfile: (String) -> Unit,
    onLike: (SocialPost) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
    modifier: Modifier = Modifier,
    mine: MyProfileLinks? = null,
    contract: ContractProfile? = null,
    myContract: ContractProfile? = null,
    onContract: (ContractProfile) -> Unit = {},
    onEditProfile: (() -> Unit)? = null,
    reload: Int = 0,
    onProfileCall: ProfileAction? = null,
) {
    val context = LocalContext.current
    val profile = remember(feed, address) { socialProfile(feed, address) }
    // Null while the node is asked.
    var identity by remember(address) { mutableStateOf<Result<ProfileIdentity>?>(null) }
    var trust by remember(address) { mutableStateOf<Result<TrustCount>?>(null) }
    LaunchedEffect(address) {
        identity = withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identity(address) } }
    }
    // The profile and its Trust read again at each opening and after each profile call in a block ([reload]): a name,
    // a bio, a vouch or a Trust may have changed since.
    LaunchedEffect(address, reload) {
        val source = NodeProfilesSource(rpc(context))
        withContext(Dispatchers.IO) { runCatching { source.profiles(listOf(address)) }.getOrNull() }?.values?.firstOrNull()?.let(onContract)
        trust = withContext(Dispatchers.IO) { runCatching { source.trust(address) } }
    }
    val isMe = address.equals(myAddress, ignoreCase = true)
    val contractExtras: @Composable () -> Unit = {
        TrustRow(address, trust?.getOrNull(), trust?.isFailure == true, myAddress, canAct, onProfileCall, onProfile)
        if (onProfileCall != null && myAddress != null) {
            if (isMe) MyVouching(myAddress, contract, canAct, reload, onProfileCall)
            else VouchPanel(address, contract, myAddress, myContract, canAct, reload, onProfileCall)
        }
    }
    val items = profile.list(tab)
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = POST_BUTTON_ROOM)) {
        item { ProfileHeader(address, identity, profile, isMe = isMe, historyDone = historyDone, person, onFollow, onName, onBlock, mine, contract, myAddress, onEditProfile, contractExtras) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTab.entries.forEach {
                    FilterChip(selected = tab == it, onClick = { onTab(it) }, label = { Text("${it.title} ${profile.list(it).size}") })
                }
            }
        }
        if (items.isEmpty()) item { Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium) }
        if (tab == ProfileTab.Posts) {
            items(items, key = { it.node.id }) { item ->
                PostCard(
                    post = item.node,
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    onOpen = { onOpen(item.threadId, null) },
                    onProfile = onProfile,
                    onLike = { onLike(item.node) },
                    onTip = { onTip(item.node) },
                    onWho = { onWho(item.node) },
                )
            }
        } else {
            items(items, key = { "${it.node.id}:${it.like?.hash ?: it.tip?.hash ?: ""}" }) { item ->
                ProfileItemCard(tab, item, states, onProfile) { onOpen(item.threadId, item.node.id) }
            }
        }
    }
}

/**
 * What the identity's own profile adds: how many people ([people]) and topics ([topics]) it follows, a tap opening the
 * list ([onFollowing], true for the topics), and ⚙ for Social settings.
 */
class MyProfileLinks(val people: Int, val topics: Int, val onFollowing: (topics: Boolean) -> Unit, val onSettings: () -> Unit)

/**
 * The identity of a profile, on the whole width as the usual social apps': the avatar with ⚙ (the identity's own
 * profile, [mine]) or Message and Follow; the name (or the short address) with ✎, the full address with copy, the
 * state, age and stake in the state's color; then what the identity's own profile follows, the likes and the tips,
 * received and given.
 */
@Composable
private fun ProfileHeader(
    address: String,
    identity: Result<ProfileIdentity>?,
    profile: SocialProfile,
    isMe: Boolean,
    historyDone: Boolean,
    person: Person,
    onFollow: (Person) -> Unit,
    onName: (Person) -> Unit,
    onBlock: (Person) -> Unit,
    mine: MyProfileLinks?,
    contract: ContractProfile?,
    myAddress: String?,
    onEditProfile: (() -> Unit)?,
    contractExtras: @Composable () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The picture (or the robot), and on its right what can be done: ⚙ on the identity's own profile, Message and Follow on another's.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(address, contract?.shownPicture, 72.dp)
            Spacer(Modifier.weight(1f))
            mine?.let { SmallIconButton(Icons.Filled.Settings, "Social settings", muted, size = 40.dp, onClick = it.onSettings) }
            if (!isMe && person.blocked) {
                OutlinedButton(onClick = { onBlock(person.copy(blocked = false)) }) { Text("Unblock") }
            } else if (!isMe) {
                LocalOpenChat.current?.let { chat ->
                    LaunchedEffect(address) { chat.lookUp(address) }
                    // Greyed when no key is known: the conversation tells why.
                    val tint = if (chat.canReceive(address) == false) muted.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary
                    OutlinedButton(
                        onClick = { chat.open(address) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Message")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                val follow = { onFollow(person.copy(following = !person.following)) }
                if (person.following) OutlinedButton(onClick = follow) { Text("Following") } else Button(onClick = follow) { Text("Follow") }
                BlockMenu(person, onBlock)
            }
        }
        // The name on the whole width, on two lines if it needs them.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = person.name.ifEmpty { contract?.shownName ?: shortAddress(address) }
            Text(
                if (isMe) "$title (you)" else title,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SmallIconButton(Icons.Filled.Edit, if (person.name.isEmpty()) "Name" else "Rename", muted) { onName(person) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(address, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = muted)
            CopyAddressButton(address)
        }
        ContractProfileLines(contract, LocalNames.current, myAddress, isMe, localName = person.name.isNotEmpty())
        contractExtras()
        onEditProfile?.let { OutlinedButton(onClick = it) { Text("Edit your public profile") } }
        if (!isMe && person.blocked) Text(BLOCKED_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        val palette = statusPalette()
        Text(
            identity?.fold(
                {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = palette(it.state), fontWeight = FontWeight.Medium)) {
                            append("${it.status} · age ${it.age} · stake ${blockNumber.format(it.stake.setScale(0, RoundingMode.DOWN))} iDNA")
                        }
                    }
                },
                { AnnotatedString("Identity: the node did not answer (${it.message}).") },
            ) ?: AnnotatedString("Identity: asking the node…"),
            style = MaterialTheme.typography.bodyMedium,
        )
        val counts = MaterialTheme.typography.bodyMedium
        mine?.let {
            Row {
                Text("${blockNumber.format(it.people)} following", Modifier.clickable { it.onFollowing(false) }, style = counts, fontWeight = FontWeight.Medium)
                Text(" · ", style = counts)
                Text("${count(it.topics, "topic", "topics")}", Modifier.clickable { it.onFollowing(true) }, style = counts, fontWeight = FontWeight.Medium)
            }
        }
        Text("$LIKE ${blockNumber.format(profile.likesReceived)} received · ${blockNumber.format(profile.likes.size)} given", style = counts)
        Text("🪙 ${tipText(profile.tipsReceived)} received · ${tipText(profile.tipsGiven)} given iDNA", style = counts)
        if (!historyDone) {
            Text(
                "From the blocks scanned so far: older posts appear as the history scan goes on.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}

/**
 * An entry of a profile's list other than its posts: a reply or comment with what it answers, a like or
 * a tip with the post concerned, or a post with media. A tap opens the thread at it.
 */
@Composable
private fun ProfileItemCard(
    tab: ProfileTab,
    item: ProfileItem,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val call = item.node.call
    val styles = addressLinkStyles()
    val muted = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tab) {
                ProfileTab.Replies, ProfileTab.Comments -> {
                    item.parent?.let { parent ->
                        Text(
                            buildAnnotatedString {
                                append(if (tab == ProfileTab.Replies) "Reply to " else "Comment on ")
                                appendAddress(parent.author, styles, onProfile)
                            },
                            style = muted,
                        )
                        PostContent(parent, muted, maxLines = 2, showMedia = false)
                        HorizontalDivider()
                    }
                    PostHeader(call, states, onProfile)
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = FEED_TEXT_LINES)
                }
                ProfileTab.Likes -> {
                    val like = item.like ?: return@Column
                    Text(
                        buildAnnotatedString {
                            append("$LIKE Liked ")
                            appendAddress(call.author, styles, onProfile)
                            append("  ·  ${ago(like.time)}")
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Tips -> {
                    val tip = item.tip ?: return@Column
                    Text(
                        buildAnnotatedString {
                            append("🪙 Tipped ${tip.validAmount?.let(::tipText)} iDNA to ")
                            appendAddress(call.author, styles, onProfile)
                            append("  ·  ${ago(tip.time)}")
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Posts, ProfileTab.Media -> {
                    PostHeader(call, states, onProfile)
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = FEED_TEXT_LINES)
                }
            }
            Text(
                "♡ ${item.node.likes}  ·  💬 ${replyCount(item.node)}  ·  🪙 ${tipText(item.node.tipTotal)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
