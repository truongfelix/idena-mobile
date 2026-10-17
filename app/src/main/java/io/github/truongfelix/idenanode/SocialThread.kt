package io.github.truongfelix.idenanode

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

/**
 * A post with its answers, as Reddit shows a post and its comments: the replies, and under each reply
 * its comments nested by what they answer ([commentTree]), each level foldable. Every item has its pills.
 * Opened at [focusId] (from a profile or the Activity), the thread scrolls to it once and marks it. The item a
 * reply is being written to ([answeringId]) is marked too; [footer] (the reply bar) stays under the list, over the
 * keyboard.
 */
@Composable
fun ThreadView(
    post: SocialPost?,
    ad: (@Composable () -> Unit)?,
    focusId: Long?,
    pageState: PageState,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    header: @Composable () -> Unit,
    onProfile: (String) -> Unit,
    onLike: (PostTarget) -> Unit,
    onAnswer: (Draft) -> Unit,
    onTip: (SocialPost) -> Unit,
    onWho: (SocialPost) -> Unit,
    answeringId: Long? = null,
    footer: @Composable (SocialPost) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        header()
        if (post == null) {
            Text(t(R.string.social_thread_missing), Modifier.padding(16.dp))
            return
        }
        LaunchedEffect(post.id) {
            if (pageState.focused || focusId == null) return@LaunchedEffect
            pageState.focused = true
            // Item 0 is the post, then one item per reply with its comments.
            val index = post.replies.indexOfFirst { reply -> reply.id == focusId || reply.replies.any { it.id == focusId } }
            if (index >= 0) pageState.list.scrollToItem(index + 1)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = pageState.list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The topic, then its author (who shows the time).
                    post.topic?.let { TopicLine(it, time = null) }
                    PostHeader(post.call, states, onProfile)
                    PostContent(post.call, MaterialTheme.typography.bodyLarge, selectable = true, inThread = true)
                    if (post.isOlder) {
                        Text(
                            t(R.string.social_thread_older, post.call.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ActionBar(post, myAddress, canAct, t(R.string.social_reply_pill), onLike = { onLike(PostTarget.onPost(post.id)) }, onAnswer = {
                        onAnswer(replyDraft(post))
                    }, onTip = { onTip(post) }, onWho = { onWho(post) })
                    ad?.invoke()
                    HorizontalDivider()
                    val answers = replyCount(post)
                    Text(tp(R.plurals.social_thread_answers, answers, answers), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(post.replies, key = { it.id }) { reply ->
                val tree = remember(reply) { commentTree(reply.id, reply.replies) }
                val thread = ThreadContext(states, myAddress, canAct, focusId, answeringId, pageState.collapsed, onProfile, onLike, onAnswer, onTip, onWho, post.call.author)
                ThreadItem(reply, tree, depth = 0, replyId = reply.id, thread = thread)
            }
        }
        footer(post)
    }
}

/** What every reply and comment of a thread shares: who is shown how, what is folded, and the actions. */
private class ThreadContext(
    val states: Map<String, AuthorIdentity>,
    val myAddress: String?,
    val canAct: Boolean,
    val focusId: Long?,
    /** The reply or comment the reply bar answers. */
    val answeringId: Long?,
    val collapsed: MutableMap<Long, Boolean>,
    val onProfile: (String) -> Unit,
    val onLike: (PostTarget) -> Unit,
    val onAnswer: (Draft) -> Unit,
    val onTip: (SocialPost) -> Unit,
    val onWho: (SocialPost) -> Unit,
    /** The post's author: "OP" on their replies and comments. */
    val opAuthor: String,
)

/** The width of the rail left of a reply or comment; a tap on it folds it. */
private val RAIL = 18.dp

/** Deeper answers are not indented further (the screen is narrow). */
private const val MAX_INDENT = 6

/**
 * A reply ([depth] 0) or a comment with its answers, behind its own rail, as Reddit nests comments: a
 * comment at depth n has n + 1 rails on its left. A tap on its rail (or its header) folds it and everything
 * under it to one line ("name · 4d · text…"); a tap on that line unfolds it. Each level folds on its own,
 * like the rungs of a ladder.
 */
@Composable
private fun ThreadItem(item: SocialPost, children: List<CommentNode>, depth: Int, replyId: Long, thread: ThreadContext) {
    // A muted author's reply, or one with a picture while pictures are hidden, starts folded ([Hiding.leavesFeeds]).
    val hiddenContent = LocalHiding.current
    val folded = thread.collapsed[item.id] ?: hiddenContent.hiding.leavesFeeds(item, hiddenContent.me)
    val toggle = { thread.collapsed[item.id] = !folded }
    val isReply = depth == 0
    val line = MaterialTheme.colorScheme.outlineVariant
    val railPx = with(LocalDensity.current) { RAIL.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (isReply) 8.dp else 4.dp)
            .then(
                if (depth >= MAX_INDENT) Modifier
                else Modifier
                    // Its rail: a thin line along it and its answers; a tap on it folds or unfolds it.
                    .drawBehind { drawLine(line, Offset(railPx / 2, 0f), Offset(railPx / 2, size.height), strokeWidth = 2.dp.toPx()) }
                    .pointerInput(item.id, folded) { detectTapGestures { if (it.x < railPx) toggle() } }
                    .padding(start = RAIL),
            ),
    ) {
        if (folded) {
            FoldedLine(item, hidden = children.sumOf { 1 + it.descendants }, onProfile = thread.onProfile, onUnfold = { toggle() })
            return@Column
        }
        Column(Modifier.focused(item.id == thread.focusId || item.id == thread.answeringId), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().clickable { toggle() }) {
                PostHeader(item.call, thread.states, thread.onProfile, avatar = if (isReply) 36.dp else 28.dp, op = item.call.author == thread.opAuthor)
            }
            PostContent(item.call, MaterialTheme.typography.bodyLarge, selectable = true, inThread = true)
            val target = if (isReply) PostTarget.onReply(item.id) else PostTarget.onComment(replyId, item.id)
            ActionBar(
                item, thread.myAddress, thread.canAct, t(R.string.social_reply_pill),
                onLike = { thread.onLike(target) },
                onAnswer = { thread.onAnswer(Draft(t(R.string.social_what_comment), t(R.string.social_what_comment), target, item.call)) },
                onTip = { thread.onTip(item) },
                onWho = { thread.onWho(item) },
            )
        }
        children.forEach { node -> key(node.comment.id) { ThreadItem(node.comment, node.children, depth + 1, replyId, thread) } }
    }
}

/** A folded reply or comment: its author, its time, the start of its text and how many answers it hides. */
@Composable
private fun FoldedLine(item: SocialPost, hidden: Int, onProfile: (String) -> Unit, onUnfold: () -> Unit) {
    val styles = addressLinkStyles()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val content = LocalHiding.current
    val preview = when {
        content.hiding.hidesText(item.call, content.me) -> t(R.string.social_folded_hidden)
        item.call.author != content.me && item.call.author in content.hiding.muted -> t(R.string.social_folded_muted)
        content.hiding.leavesFeeds(item, content.me) -> t(R.string.social_folded_picture)
        ipfsCid(item.call.message) != null -> t(R.string.social_text_on_ipfs)
        item.call.message.isEmpty() && item.call.hasMedia -> t(R.string.social_media)
        else -> item.call.message.replace(Regex("\\s+"), " ").trim()
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onUnfold).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonAvatar(item.call.author, 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString { appendAddress(item.call.author, styles, onProfile) },
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("  ·  ${ago(item.call.time)}  ·  ", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, softWrap = false)
        Text(preview, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hidden > 0) Text("  +$hidden", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The reply or comment a thread was opened at: a light background behind it. */
@Composable
private fun Modifier.focused(focused: Boolean): Modifier =
    if (!focused) this
    else background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
