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
    listState: LazyListState,
    tab: ProfileTab,
    onTab: (ProfileTab) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
    onProfile: (String) -> Unit,
    onLike: (SocialPost) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profile = remember(feed, address) { socialProfile(feed, address) }
    // Null while the node is asked.
    var identity by remember(address) { mutableStateOf<Result<ProfileIdentity>?>(null) }
    LaunchedEffect(address) {
        identity = withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identity(address) } }
    }
    val items = profile.list(tab)
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        item { ProfileHeader(address, identity, profile, isMe = address == myAddress, historyDone = historyDone, person, onFollow, onName) }
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
 * The identity of a profile, compact: the avatar; then the name (or the short address) with ✎ and Follow,
 * the short address with copy, the state, age and stake in the state's color; under them the likes and
 * the tips, received and given.
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
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(address, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val title = person.name.ifEmpty { shortAddress(address) }
                    Text(
                        if (isMe) "$title (you)" else title,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Without a name the title is the short address: copy it there.
                    if (person.name.isEmpty()) CopyAddressButton(address)
                    SmallIconButton(Icons.Filled.Edit, if (person.name.isEmpty()) "Name" else "Rename", muted) { onName(person) }
                    Spacer(Modifier.weight(1f))
                    if (!isMe) {
                        FilterChip(
                            selected = person.following,
                            onClick = { onFollow(person.copy(following = !person.following)) },
                            label = { Text(if (person.following) "Following" else "Follow") },
                        )
                    }
                }
                if (person.name.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shortAddress(address), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = muted)
                        CopyAddressButton(address)
                    }
                }
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
            }
        }
        val counts = MaterialTheme.typography.bodyMedium
        Text("$LIKE ${blockNumber.format(profile.likesReceived)} received · ${blockNumber.format(profile.likes.size)} given", style = counts)
        Text("🪙 ${blockNumber.format(profile.tipsReceived)} received · ${blockNumber.format(profile.tipsGiven)} given iDNA", style = counts)
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
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 6)
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
                            append("🪙 Tipped ${tip.validAmount} iDNA to ")
                            appendAddress(call.author, styles, onProfile)
                            append("  ·  ${ago(tip.time)}")
                        },
                        style = muted,
                    )
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
                }
                ProfileTab.Posts, ProfileTab.Media -> {
                    PostHeader(call, states, onProfile)
                    PostContent(call, MaterialTheme.typography.bodyLarge, maxLines = 6)
                }
            }
            Text(
                "♡ ${item.node.likes}  ·  💬 ${replyCount(item.node)}  ·  🪙 ${item.node.tipTotal}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
