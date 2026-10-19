package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.compositionLocalOf

/** The time now (Unix seconds), moved on every 30 s while the tab is shown: the posts' ages follow it. */
val LocalNow = compositionLocalOf { System.currentTimeMillis() / 1000 }

/** How long ago [seconds] was ([timeAgo]): 5s, 10min, 7h, 1d, 5mo, 2y. */
@Composable
fun ago(seconds: Long): String = timeAgo(seconds, LocalNow.current)

/** The names of the contact list by lowercase address, shown instead of the addresses. */
val LocalNames = compositionLocalOf<Map<String, String>> { emptyMap() }

/** The posts, replies and comments liked by a like sent but not read by the scan yet ([SentList]). */
val LocalPendingLikes = compositionLocalOf<Set<Long>> { emptySet() }

/** "  ·  Human  ·  age 12", the state and its age in the state's color. */
private fun AnnotatedString.Builder.appendStatus(identity: AuthorIdentity, palette: (String) -> Color) {
    append("  ·  ")
    withStyle(SpanStyle(color = palette(identity.state), fontWeight = FontWeight.Medium)) { append(identity.label) }
}

/** How an address shows as a link to its profile: the style, and the names to show. */
class AddressLink(val styles: TextLinkStyles, val names: Map<String, String>)

@Composable
fun addressLinkStyles() =
    AddressLink(TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)), LocalNames.current)

/** [address], by its name or shortened ([displayName]), as a link that opens its profile. */
fun AnnotatedString.Builder.appendAddress(address: String, styles: AddressLink, onProfile: (String) -> Unit) {
    withLink(LinkAnnotation.Clickable(address, styles.styles) { onProfile(address) }) { append(displayName(address, styles.names)) }
}

/**
 * The author of a post, reply or comment on two lines next to their avatar ([avatar] high): the name (or
 * the short address) with how long ago at the top right, then the state and age in the state's color. A tap on the avatar
 * or the name opens the profile.
 */
@Composable
fun PostHeader(
    call: PostCall,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    avatar: Dp = 42.dp,
    compact: Boolean = false,
    op: Boolean = false,
) {
    // No state while the node is asked for it.
    val identity = states[call.author]
    val styles = addressLinkStyles()
    val palette = statusPalette()
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) {
        // The feed's one line, as Reddit's: avatar, name, state, time, and ⋯ on the right; the name is cut first.
        Row(verticalAlignment = Alignment.CenterVertically) {
          Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(call.author, 32.dp, Modifier.clickable { onProfile(call.author) })
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString { appendAddress(call.author, styles, onProfile) },
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            identity?.let {
                Text(
                    "  ·  " + identityStatus(it.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette(it.state),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text("  ·  " + ago(call.time), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, softWrap = false)
          }
          PostMenuButton(call)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(call.author, avatar, Modifier.clickable { onProfile(call.author) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // The time stays at the top right; the name is cut first.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString { appendAddress(call.author, styles, onProfile) },
                    Modifier.weight(1f, fill = !op),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (op) {
                    Text("  OP", style = MaterialTheme.typography.labelLarge, color = OP_BLUE, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                Text(ago(call.time), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, softWrap = false)
                PostMenuButton(call)
            }
            identity?.let {
                Text(
                    it.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette(it.state),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A post of the feed: its start and its action pills; a tap on the text or on 💬 opens the thread, a tap
 * on the address the author's profile.
 */
@Composable
fun PostCard(
    post: SocialPost,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    onOpen: () -> Unit,
    onProfile: (String) -> Unit,
    onLike: () -> Unit,
    onTip: () -> Unit,
    onWho: () -> Unit,
    topicShown: TopicShown = TopicShown.AsTag,
) {
    val topic = post.topic?.takeIf { topicShown != TopicShown.Not }
    // Flat, as Reddit's feed: the list draws a thin line between posts.
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PostHeader(post.call, states, onProfile, compact = true)
            if (topic != null) TopicTag(topic)
            PostContent(post.call, MaterialTheme.typography.bodyLarge, maxLines = FEED_TEXT_LINES, mediaMaxHeight = 220.dp)
        }
        ActionBar(post, myAddress, canAct, "💬 ${replyCount(post)}", answerEnabled = true, onLike = onLike, onAnswer = onOpen, onTip = onTip, onWho = onWho)
    }
}

/** How a post card shows the post's topic: as a tag under the author, or not (the topic's page). */
enum class TopicShown { AsTag, Not }

/** The color of "OP", Reddit's blue. */
private val OP_BLUE = androidx.compose.ui.graphics.Color(0xFF0079D3)

/** The lines of a post's text in the feed; the thread shows all of it. */
const val FEED_TEXT_LINES = 8

/** A small action button, as Reddit's pills under a post (lower than Material's chips). */
@Composable
fun Pill(text: String, enabled: Boolean, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.45f))
    }
}

/** One part of a [SplitPill]: its text, and its own tap. */
class PillPart(val text: String, val enabled: Boolean, val description: String? = null, val onClick: () -> Unit)

/** A pill of parts side by side, each its own tap (the heart and the likes' count). */
@Composable
fun SplitPill(parts: List<PillPart>) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).border(1.dp, colors.outlineVariant, shape).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) VerticalDivider(color = colors.outlineVariant)
            Box(
                Modifier
                    .clickable(enabled = part.enabled, onClick = part.onClick)
                    .then(if (part.description != null) Modifier.semantics { contentDescription = part.description } else Modifier)
                    .padding(horizontal = if (parts.size > 1) 10.dp else 12.dp, vertical = 7.dp),
            ) {
                Text(part.text, style = MaterialTheme.typography.labelLarge, color = if (part.enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.45f))
            }
        }
    }
}

/**
 * The pills under a post, reply or comment: its likes (Like; "❤️" once the identity liked it or sent a like), the
 * answer pill ([answer]), and its tips (Tip; not on one's own posts, which show only their tipped total). The likes'
 * count and the tipped total open who liked and tipped. An older contract's post is read only: the answer pill then
 * only opens its thread ([answerEnabled] in the feed).
 */
@Composable
fun ActionBar(
    post: SocialPost,
    myAddress: String?,
    canAct: Boolean,
    answer: String,
    answerEnabled: Boolean = canAct && !post.isOlder,
    onLike: () -> Unit,
    onAnswer: () -> Unit,
    onTip: () -> Unit,
    onWho: () -> Unit,
) {
    val act = canAct && !post.isOlder
    // A like sent counts at once: the pill stays off until the scan reads it.
    val liked = myAddress != null && myAddress in post.likedBy || post.id in LocalPendingLikes.current
    val who = t(R.string.social_who_description)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SplitPill(
            listOf(
                PillPart(if (liked) LIKE else "♡", act && !liked, onClick = onLike),
                PillPart("${post.likes}", post.likes > 0, who, onWho),
            ),
        )
        Pill(answer, answerEnabled, onClick = onAnswer)
        val tipped = if (post.tips.isNotEmpty()) PillPart("🪙 ${tipText(post.tipTotal)}", true, who, onWho) else null
        val tip = if (post.call.author != myAddress) PillPart(t(R.string.social_tip_pill), act, onClick = onTip) else null
        listOfNotNull(tip, tipped).takeIf { it.isNotEmpty() }?.let { SplitPill(it) }
    }
}

/** Who liked and who tipped [post], newest first, as idena.social's likes and tips lists; a tap on an address opens its profile. */
@Composable
fun WhoDialog(post: SocialPost, states: Map<String, AuthorIdentity>, onProfile: (String) -> Unit, onDismiss: () -> Unit) {
    val styles = addressLinkStyles()
    val palette = statusPalette()
    fun AnnotatedString.Builder.who(address: String) {
        appendAddress(address, styles, onProfile)
        states[address]?.let { appendStatus(it, palette) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.social_who_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (post.likeCalls.isNotEmpty()) {
                    item { Text(tp(R.plurals.social_who_likes, post.likes, LIKE, post.likes), style = MaterialTheme.typography.titleSmall) }
                    items(post.likeCalls.sortedByDescending { it.height }, key = { it.hash }) { like ->
                        Text(buildAnnotatedString { who(like.author); append("  ·  ${ago(like.time)}") }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (post.tips.isNotEmpty()) {
                    item {
                        Text(tp(R.plurals.social_who_tips, post.tips.size, tipText(post.tipTotal), post.tips.size), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    items(post.tips.sortedByDescending { it.height }, key = { it.hash }) { tip ->
                        Text(
                            buildAnnotatedString { who(tip.from); append("  ·  ${tip.validAmount?.let(::tipText)} iDNA  ·  ${ago(tip.time)}") },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
}
