package io.github.truongfelix.idenanode

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
    onPeople: (PeopleList) -> Unit = {},
    myTask: (@Composable () -> Unit)? = null,
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
    var contractRead by remember(address) { mutableStateOf(false) }
    LaunchedEffect(address, reload) {
        val source = NodeProfilesSource(rpc(context))
        val read = withContext(Dispatchers.IO) { runCatching { source.profiles(listOf(address)) }.getOrNull() }
        read?.values?.firstOrNull()?.let(onContract)
        if (read != null) contractRead = true
        trust = withContext(Dispatchers.IO) { runCatching { source.trust(address) } }
    }
    val isMe = address.equals(myAddress, ignoreCase = true)
    val contractExtras: @Composable () -> Unit = {
        if (onProfileCall != null && myAddress != null) {
            if (isMe) MyVouching(myAddress, contract, canAct, reload, onProfileCall)
            else VouchPanel(address, contract, myAddress, myContract, canAct, reload, onProfileCall)
        }
    }
    val items = profile.list(tab)
    // The people counts (B-89): who trusts it and whom it trusts, its members and invitees, each opening its list.
    val circle = rememberProfileCircle(address, identity?.getOrNull(), reload, isMe)
    val people: @Composable () -> Unit = { PeopleCounts(trust?.getOrNull(), circle?.getOrNull(), onPeople) }
    val trustChip: @Composable () -> Unit = { TrustChip(address, trust?.getOrNull(), myAddress, canAct, onProfileCall) }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = POST_BUTTON_ROOM)) {
        item { ProfileHeader(address, identity, profile, isMe = isMe, historyDone = historyDone, person, onFollow, onName, onBlock, mine, contract, myAddress, onEditProfile, contractExtras, people, trustChip) }
        // Own profile: the node's task (flips to make), then, for an account without an identity, how to be vouched for.
        if (isMe) {
            myTask?.let { item { it() } }
            // Only once the contract was read: a voucher not read yet is no voucher.
            identity?.getOrNull()?.takeIf { contractRead && wantsVouch(it.state, contract?.voucher) }?.let { item { GetVouchedCard(address) } }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTab.entries.forEach {
                    FilterChip(selected = tab == it, onClick = { onTab(it) }, label = { Text("${it.title} ${profile.list(it).size}") })
                }
            }
        }
        if (items.isEmpty()) item { Text(t(R.string.socialb_nothing_here), style = MaterialTheme.typography.bodyMedium) }
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
@OptIn(ExperimentalLayoutApi::class)
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
    people: @Composable () -> Unit,
    trustChip: @Composable () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The picture (or the robot), the address with copy beside it (B-91), then ⚙ on the identity's own profile, ⋮
        // (or Unblock) on another's.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(address, contract?.shownPicture, 72.dp)
            Spacer(Modifier.width(10.dp))
            Text(address, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = muted)
            CopyAddressButton(address)
            // The account's own edits and settings are in ⚙ (B-79, user 2026-10-17).
            mine?.let { OwnProfileMenu(onEdit = onEditProfile, onName = { onName(person) }, onSettings = it.onSettings) }
            if (!isMe && person.blocked) OutlinedButton(onClick = { onBlock(person.copy(blocked = false)) }) { Text(t(R.string.socialb_unblock)) }
            else if (!isMe) BlockMenu(person, onBlock, onName = { onName(person) })
        }
        // Another's: Message, Trust and Follow on a row of their own (B-89), the width a phone has.
        if (!isMe && !person.blocked) {
            // On two lines when the three do not fit (a large font, "Trust · waiting for a block").
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Text(t(R.string.socialb_message_button))
                    }
                }
                trustChip()
                val follow = { onFollow(person.copy(following = !person.following)) }
                if (person.following) OutlinedButton(onClick = follow) { Text(t(R.string.socialb_following)) } else Button(onClick = follow) { Text(t(R.string.socialb_follow)) }
            }
        }
        // The name on the whole width, on two lines if it needs them.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = person.name.ifEmpty { contract?.shownName ?: shortAddress(address) }
            Text(
                if (isMe) t(R.string.socialb_title_you, title) else title,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ContractProfileLines(contract, LocalNames.current, myAddress, isMe, localName = person.name.isNotEmpty())
        contractExtras()
        if (!isMe && person.blocked) Text(BLOCKED_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        val palette = statusPalette()
        Text(
            identity?.fold(
                {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = palette(it.state), fontWeight = FontWeight.Medium)) {
                            append(t(R.string.socialb_identity_line, it.status, it.age, blockNumber.format(it.stake.setScale(0, RoundingMode.DOWN))))
                        }
                    }
                },
                { AnnotatedString(t(R.string.socialb_identity_no_answer, "${it.message}")) },
            ) ?: AnnotatedString(t(R.string.socialb_identity_asking)),
            style = MaterialTheme.typography.bodyMedium,
        )
        // In this order (B-91, user 2026-10-17): Trust, members and invitees, likes, tips, then what one follows.
        people()
        val counts = MaterialTheme.typography.bodyMedium
        Text(t(R.string.socialb_likes_line, LIKE, blockNumber.format(profile.likesReceived), blockNumber.format(profile.likes.size)), style = counts)
        Text(t(R.string.socialb_tips_line, tipText(profile.tipsReceived), tipText(profile.tipsGiven)), style = counts)
        mine?.let {
            FlowRow {
                Text("⭐ ", style = counts)
                Text(t(R.string.socialb_n_following, blockNumber.format(it.people)), Modifier.clickable { it.onFollowing(false) }, style = counts, fontWeight = FontWeight.Medium)
                Text(" · ", style = counts)
                Text(tp(R.plurals.socialb_n_topics, it.topics, it.topics), Modifier.clickable { it.onFollowing(true) }, style = counts, fontWeight = FontWeight.Medium)
            }
        }
        if (!historyDone) {
            Text(
                t(R.string.socialb_history_note),
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
                                val line = if (tab == ProfileTab.Replies) t(R.string.socialb_reply_to, ADDRESS_MARK) else t(R.string.socialb_comment_on, ADDRESS_MARK)
                                append(line.substringBefore(ADDRESS_MARK))
                                appendAddress(parent.author, styles, onProfile)
                                append(line.substringAfter(ADDRESS_MARK, ""))
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
                            val line = t(R.string.socialb_liked_line, LIKE, ADDRESS_MARK, ago(like.time))
                            append(line.substringBefore(ADDRESS_MARK))
                            appendAddress(call.author, styles, onProfile)
                            append(line.substringAfter(ADDRESS_MARK, ""))
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Tips -> {
                    val tip = item.tip ?: return@Column
                    Text(
                        buildAnnotatedString {
                            val line = t(R.string.socialb_tipped_line, "${tip.validAmount?.let(::tipText)}", ADDRESS_MARK, ago(tip.time))
                            append(line.substringBefore(ADDRESS_MARK))
                            appendAddress(call.author, styles, onProfile)
                            append(line.substringAfter(ADDRESS_MARK, ""))
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
