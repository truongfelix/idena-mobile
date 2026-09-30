package io.github.truongfelix.idenanode

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicBoolean

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** Why the scan waits, or null when the node is ready: synced and outside the validation. */
private fun notReadyReason(rpc: NodeRpc): String? {
    val syncing = rpc.call("bcn_syncing") as JSONObject
    if (syncing.optBoolean("syncing")) return "Waiting: the node is syncing."
    if (rpc.ceremony().period != "None") return "Paused during the validation."
    return null
}

/**
 * The Social tab: idena.social read from the phone's node, and posting with the node's key. While the
 * "Scan blocks" select button is on (remembered, on at first), the tab scans the blocks after the newest
 * scanned one up to the node's head, then the history back to the contract's first block (once), then
 * follows the head; unselected, it stops at its next batch. Nothing is scanned while the tab is hidden,
 * the node syncs or a validation runs.
 */
@Composable
fun SocialScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cache by remember { mutableStateOf<SocialCache?>(null) }
    var feed by remember { mutableStateOf<List<SocialPost>>(emptyList()) }
    val states = remember { mutableStateMapOf<String, String>() }
    var head by remember { mutableStateOf<Long?>(null) }
    var activity by remember { mutableStateOf("Loading…") }
    var ready by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(SocialStore.isScanning(context)) }
    // The scan runs on an IO thread and reads these between two batches.
    val scanningFlag = remember { AtomicBoolean(scanning) }
    // After a post, the new blocks are scanned once even without "Scan blocks".
    val scanNewOnce = remember { AtomicBoolean(false) }
    var wake by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // The open thread (a post id), the editor's draft, and a paid action waiting for confirmation.
    var threadId by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var draftText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<PendingPost?>(null) }
    var busy by remember { mutableStateOf(false) }
    // What happened to the last paid action, shown at the top of the tab.
    var sendStatus by remember { mutableStateOf<String?>(null) }
    var myAddress by remember { mutableStateOf<String?>(null) }
    // The post being tipped (the amount dialog), and whether the Activity list is shown instead of the feed.
    var tipping by remember { mutableStateOf<SocialPost?>(null) }
    var showActivity by remember { mutableStateOf(false) }

    suspend fun show(next: SocialCache) {
        cache = next
        feed = withContext(Dispatchers.Default) { next.feed() }
        val unknown = feed.flatMap { withReplies(it) }.map { it.call.author }.distinct().filter { it !in states }
        if (unknown.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identityStates(unknown) }.getOrNull() }
                ?.let { states.putAll(it) }
        }
    }

    /** Waits [ms], or less when a button changes or a post was made. */
    suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { snapshotFlow { scanning to wake }.drop(1).first() }
    }

    /** Asks the node for the fee of [call], then for the user's confirmation; [detail] tells what it does. */
    fun prepare(what: String, call: SocialCall, detail: String? = null) {
        busy = true
        sendStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = rpc(context)
                    val from = rpc.call("dna_getCoinbaseAddr") as String
                    from to SocialPosting(rpc).estimate(from, call)
                }
            }
            busy = false
            result.onSuccess { (from, fee) -> confirm = PendingPost(what, call, from, fee, detail) }
                .onFailure { sendStatus = "$what: the node refused: ${it.message}" }
        }
    }

    /** A post, reply, comment or like: [message] answering [target]. */
    fun preparePost(what: String, message: String, target: PostTarget) =
        prepare(what, SocialPosting.post(makePostArgument(message, target.replyTo, target.channel)))

    /** Sends a confirmed action, then scans the new blocks once it is in one. */
    fun send(pending: PendingPost) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching { SocialPosting(rpc(context)).send(pending.from, pending.call, pending.fee.maxFee) }
            }.getOrElse {
                busy = false
                sendStatus = "${pending.what}: the node refused: ${it.message}"
                return@launch
            }
            busy = false
            if (draft != null) {
                draft = null
                draftText = ""
            }
            sendStatus = "${pending.what} sent, waiting for a block (${hash.take(10)}…)"
            while (!withContext(Dispatchers.IO) { runCatching { rpc(context).isMined(hash) }.getOrDefault(false) }) delay(10_000)
            sendStatus = "${pending.what} is in a block."
            scanNewOnce.set(true)
            wake++
        }
    }

    LaunchedEffect(Unit) {
        myAddress = withContext(Dispatchers.IO) { runCatching { rpc(context).call("dna_getCoinbaseAddr") as String }.getOrNull() }?.lowercase()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SocialStore.load(context) }?.let { show(it) }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val source = NodeSocialSource(rpc(context))
                    val reason = withContext(Dispatchers.IO) { notReadyReason(rpc(context)) }
                    ready = reason == null
                    if (reason != null) {
                        activity = reason
                        pause(30_000)
                        continue
                    }
                    val nodeHead = withContext(Dispatchers.IO) { source.head() }
                    head = nodeHead
                    val current = cache
                    val wantsNew = scanningFlag.get() || scanNewOnce.get()
                    when {
                        // The first time, the scan starts at the head: nothing to read yet.
                        current == null && wantsNew -> {
                            activity = "Starting at the node's block…"
                            show(withContext(Dispatchers.IO) { checkNewBlocks(null, source) { SocialStore.save(context, it) } })
                        }
                        current == null -> {
                            activity = "Select \"Scan blocks\" to read the posts from the node."
                            pause(20_000)
                        }
                        wantsNew && (current.high < nodeHead || current.authorsHeight < current.high) -> {
                            activity = "Scanning new blocks…"
                            val next = withContext(Dispatchers.IO) {
                                checkNewBlocks(current, source, keepGoing = { scanningFlag.get() || scanNewOnce.get() }) { SocialStore.save(context, it) }
                            }
                            if (next.authorsHeight >= next.high) scanNewOnce.set(false)
                            show(next)
                        }
                        !current.historyDone && scanningFlag.get() -> {
                            activity = "Scanning the history…"
                            show(withContext(Dispatchers.IO) { scanOlderBlocks(current, source) { SocialStore.save(context, it) } })
                        }
                        else -> {
                            scanNewOnce.set(false)
                            activity = when {
                                !scanningFlag.get() -> "Stopped. Select \"Scan blocks\" to read the newest posts."
                                else -> "Up to date."
                            }
                            // A new block comes about every 20 seconds.
                            pause(20_000)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    activity = "The node did not answer: ${e.message}"
                    pause(10_000)
                }
            }
        }
    }

    BackHandler(enabled = draft != null || threadId != null) {
        if (draft != null) draft = null else threadId = null
    }

    Box(modifier.fillMaxSize()) {
        val current = draft
        val thread = threadId?.let { id -> feed.firstOrNull { it.id == id } }
        when {
            current != null -> Editor(
                draft = current,
                text = draftText,
                onText = { draftText = it },
                canPost = ready && !busy,
                onCancel = { draft = null },
                onPost = { preparePost(current.what, draftText.trim(), current.target) },
            )
            threadId != null -> ThreadView(
                post = thread,
                states = states,
                myAddress = myAddress,
                canAct = ready && !busy,
                waitReason = activity.takeUnless { ready },
                sendStatus = sendStatus,
                onBack = { threadId = null },
                onLike = { target -> preparePost("Like", LIKE, target) },
                onAnswer = { draft = it },
                onTip = { tipping = it },
            )
            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.width(0.dp))
                    Text("Posts of idena.social", style = MaterialTheme.typography.titleMedium)
                    Text(newestText(cache, head), style = MaterialTheme.typography.bodySmall)
                    Text(historyText(cache, feed.size), style = MaterialTheme.typography.bodySmall)
                    Text(activity, style = MaterialTheme.typography.bodySmall)
                    sendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    FilterChip(
                        selected = scanning,
                        onClick = {
                            scanning = !scanning
                            scanningFlag.set(scanning)
                            SocialStore.setScanning(context, scanning)
                        },
                        label = { Text("Scan blocks") },
                    )
                    val items = remember(feed, myAddress) { myAddress?.let { socialActivity(feed, it) }.orEmpty() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showActivity, onClick = { showActivity = false }, label = { Text("Feed") })
                        FilterChip(selected = showActivity, onClick = { showActivity = true }, label = { Text("Activity (${items.size})") })
                    }
                    HorizontalDivider()
                    if (showActivity) {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (items.isEmpty()) item { Text("No likes, answers or tips on your posts yet.", style = MaterialTheme.typography.bodyMedium) }
                            items(items, key = { "${it.kind}:${it.height}:${it.index}:${it.what.hash}" }) { item ->
                                ActivityCard(item) { threadId = item.threadId }
                            }
                        }
                    } else LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Room for the write button over the last post.
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (feed.isEmpty()) item { Text("No posts found yet.", style = MaterialTheme.typography.bodySmall) }
                        if (!ready) item { WaitNote(activity) }
                        items(feed, key = { it.id }) { post ->
                            PostCard(
                                post = post,
                                states = states,
                                myAddress = myAddress,
                                canAct = ready && !busy,
                                onOpen = { threadId = post.id },
                                onLike = { preparePost("Like", LIKE, PostTarget.onPost(post.id)) },
                                onTip = { tipping = post },
                                onReply = { draft = Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call) },
                            )
                        }
                    }
                }
                if (!showActivity) {
                    FloatingActionButton(
                        onClick = { draft = Draft("Post", "New post", PostTarget.NEW_POST, null) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Text("✎", fontSize = 22.sp) }
                }
            }
        }
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${pending.what} on idena.social") },
            text = {
                Text(
                    (pending.detail?.let { "$it. " } ?: "") +
                        "It is public and stays on the blockchain for good, signed by your identity " +
                        "${shortAddress(pending.from)}. Fee: about ${pending.fee.fee.setScale(2, RoundingMode.UP).toPlainString()} iDNA " +
                        "(at most ${pending.fee.maxFee.setScale(2, RoundingMode.UP).toPlainString()} iDNA).",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; send(pending) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    tipping?.let { post ->
        TipDialog(
            post = post,
            onDismiss = { tipping = null },
            onTip = { amount ->
                tipping = null
                prepare("Tip", SocialPosting.tip(post.id, amount), "Tip $amount iDNA to ${shortAddress(post.call.author)} for this post")
            },
        )
    }
}

/** A post, reply, comment or like waiting for the user's confirmation, with the node's fee estimate. */
private data class PendingPost(val what: String, val call: SocialCall, val from: String, val fee: PostFee, val detail: String?)

/** What the editor writes: the action's name, the editor's title, what it answers and that text. */
private data class Draft(val what: String, val title: String, val target: PostTarget, val answering: PostCall?)

private val blockNumber: NumberFormat = NumberFormat.getIntegerInstance()

/** The newest block scanned against the node's block, and how far behind it is (a block every ~20 s). */
private fun newestText(cache: SocialCache?, head: Long?): String {
    val node = head?.let { "node: ${blockNumber.format(it)}" } ?: "node: …"
    if (cache == null || cache.high < cache.low) return "Newest block scanned: none yet · $node"
    val scanned = "Newest block scanned: ${blockNumber.format(cache.high)} · $node"
    val behind = (head ?: return scanned) - cache.high
    if (behind <= 0) return scanned
    val minutes = behind * 20 / 60
    val time = if (minutes >= 120) "about ${minutes / 60} h" else "about $minutes min"
    return "$scanned (${blockNumber.format(behind)} behind, $time)"
}

/** How far back the history scan has come, and the posts found. */
private fun historyText(cache: SocialCache?, posts: Int): String {
    if (cache == null || cache.high < cache.low) return "History: not scanned yet"
    val found = if (posts == 1) "1 post" else "$posts posts"
    if (cache.historyDone) return "History: complete since block ${blockNumber.format(SOCIAL_FIRST_BLOCK)} · $found"
    val percent = (cache.scannedShare * 100).toInt()
    return "History: $percent% scanned, back to block ${blockNumber.format(cache.low)} · $found"
}

/** The post and all its replies. */
private fun withReplies(post: SocialPost): List<SocialPost> = listOf(post) + post.replies.flatMap { withReplies(it) }

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTime(seconds: Long): String =
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(timeFormat)

/** "0x1234…abcd". */
private fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"

/** The text of a post; idena.social can keep it (or its media) on IPFS, which this screen does not read yet. */
private fun postText(call: PostCall): String = buildString {
    if (call.message.startsWith("ipfs://")) append("(text stored on IPFS, not shown here)") else append(call.message)
    if (call.hasMedia) append(if (isEmpty()) "(image or video, not shown here)" else "\n(image or video, not shown here)")
}

/** "0x1234…abcd · Human · 30 Sep 2026, 10:05". */
@Composable
private fun PostHeader(call: PostCall, states: Map<String, String>) {
    val state = states[call.author]?.takeIf { it.isNotEmpty() && it != "Undefined" }
    Text(
        listOfNotNull(shortAddress(call.author), state, formatTime(call.time)).joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Why Like and the answers are off: the node must be synced and outside a validation. */
@Composable
private fun WaitNote(reason: String) {
    Text("Like and reply need the node synced. $reason", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** A post of the feed: its start, its answer count, Like and Reply; a tap on the text opens the thread. */
@Composable
private fun PostCard(
    post: SocialPost,
    states: Map<String, String>,
    myAddress: String?,
    canAct: Boolean,
    onOpen: () -> Unit,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onTip: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostHeader(post.call, states)
                Text(postText(post.call), style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                val replies = replyCount(post)
                val counts = listOfNotNull(
                    replies.takeIf { it > 0 }?.let { if (it == 1) "💬 1 answer" else "💬 $it answers" },
                    post.tipTotal.takeIf { it > 0 }?.let { "🪙 $it iDNA tipped" },
                )
                if (counts.isNotEmpty()) Text(counts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
            Actions(post, myAddress, canAct, "Reply", onLike, onReply, onTip)
        }
    }
}

/** Like, answer and tip buttons; "Liked" once the identity liked it, no tip for its own posts. */
@Composable
private fun Actions(
    post: SocialPost,
    myAddress: String?,
    canAct: Boolean,
    answer: String,
    onLike: () -> Unit,
    onAnswer: () -> Unit,
    onTip: () -> Unit,
) {
    val liked = myAddress != null && myAddress in post.likedBy
    val padding = PaddingValues(horizontal = 12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onLike, enabled = canAct && !liked, modifier = Modifier.height(48.dp), contentPadding = padding) {
            Text(if (liked) "$LIKE ${post.likes}" else "♡ ${post.likes}", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onAnswer, enabled = canAct, modifier = Modifier.height(48.dp), contentPadding = padding) {
            Text("💬 $answer", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onTip, enabled = canAct && post.call.author != myAddress, modifier = Modifier.height(48.dp), contentPadding = padding) {
            Text("🪙 Tip", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The tips a post received, when it has some. */
@Composable
private fun TipsLine(post: SocialPost) {
    if (post.tipTotal > 0) Text("🪙 ${post.tipTotal} iDNA tipped", style = MaterialTheme.typography.bodyMedium)
}

/** The amount of a tip: whole iDNA (the contract refuses fractions), with the balance for reference. */
@Composable
private fun TipDialog(post: SocialPost, onDismiss: () -> Unit, onTip: (Long) -> Unit) {
    val context = LocalContext.current
    var amount by remember { mutableStateOf("1") }
    var balance by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        balance = withContext(Dispatchers.IO) {
            runCatching {
                val rpc = rpc(context)
                SocialPosting(rpc).balance(rpc.call("dna_getCoinbaseAddr") as String).setScale(2, RoundingMode.DOWN).toPlainString()
            }.getOrNull()
        }
    }
    val value = amount.toLongOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tip ${shortAddress(post.call.author)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The tip goes to the author of this post, through the idena.social contract. Whole iDNA only.")
                balance?.let { Text("Your balance: $it iDNA", style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() }.take(9) },
                    label = { Text("iDNA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onTip) }, enabled = value != null) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One entry of the Activity list; a tap opens its thread. */
@Composable
private fun ActivityCard(item: ActivityItem, onOpen: () -> Unit) {
    val action = when (item.kind) {
        ActivityKind.Like -> "liked"
        ActivityKind.Reply -> "replied to"
        ActivityKind.Comment -> "commented on"
        ActivityKind.Tip -> "tipped you ${item.amount} iDNA for"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${shortAddress(item.actor)} $action your post  ·  ${formatTime(item.time)}", style = MaterialTheme.typography.bodyMedium)
            Text("“${postText(item.what)}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.answer?.let { Text(postText(it), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * A post with its replies and, under each reply, its comments (idena.social's levels), each with Like
 * and answer buttons.
 */
@Composable
private fun ThreadView(
    post: SocialPost?,
    states: Map<String, String>,
    myAddress: String?,
    canAct: Boolean,
    waitReason: String?,
    sendStatus: String?,
    onBack: () -> Unit,
    onLike: (PostTarget) -> Unit,
    onAnswer: (Draft) -> Unit,
    onTip: (SocialPost) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        sendStatus?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        waitReason?.let { Column(Modifier.padding(horizontal = 16.dp)) { WaitNote(it) } }
        if (post == null) {
            Text("This post is not in the scanned blocks.", Modifier.padding(16.dp))
            return
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PostHeader(post.call, states)
                        Text(postText(post.call), style = MaterialTheme.typography.bodyLarge)
                        TipsLine(post)
                        Actions(post, myAddress, canAct, "Reply", { onLike(PostTarget.onPost(post.id)) }, {
                            onAnswer(Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call))
                        }) { onTip(post) }
                    }
                }
            }
            if (post.replies.isEmpty()) item { Text("No replies yet.", style = MaterialTheme.typography.bodySmall) }
            items(post.replies, key = { it.id }) { reply ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider()
                    PostHeader(reply.call, states)
                    Text(postText(reply.call), style = MaterialTheme.typography.bodyLarge)
                    TipsLine(reply)
                    Actions(reply, myAddress, canAct, "Comment", { onLike(PostTarget.onReply(reply.id)) }, {
                        onAnswer(Draft("Comment", "Comment", PostTarget.onReply(reply.id), reply.call))
                    }) { onTip(reply) }
                    for (comment in reply.replies) {
                        Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            PostHeader(comment.call, states)
                            Text(postText(comment.call), style = MaterialTheme.typography.bodyLarge)
                            TipsLine(comment)
                            Actions(comment, myAddress, canAct, "Answer", { onLike(PostTarget.onComment(reply.id, comment.id)) }, {
                                onAnswer(Draft("Comment", "Comment", PostTarget.onComment(reply.id, comment.id), comment.call))
                            }) { onTip(comment) }
                        }
                    }
                }
            }
        }
    }
}

/** The full-screen editor of a post, reply or comment. */
@Composable
private fun Editor(draft: Draft, text: String, onText: (String) -> Unit, canPost: Boolean, onCancel: () -> Unit, onPost: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onPost, enabled = canPost && text.isNotBlank()) { Text("Post") }
        }
        draft.answering?.let {
            Text(
                "Answering ${shortAddress(it.author)}: ${postText(it)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
            placeholder = { Text("What's new?") },
        )
        Text(
            "Posts are public and stay on the blockchain for good. The fee is shown before sending.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
