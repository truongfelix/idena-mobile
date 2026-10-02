package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.RoundingMode
import java.math.BigDecimal
import java.text.NumberFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import androidx.compose.runtime.compositionLocalOf

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The Social tab: idena.social read from the phone's node ([SocialModel] scans it), and posting with the
 * node's key: the feed (newest posts or latest activity), the Activity on the identity's posts with its
 * notifications, the identity's profile, and over them threads, profiles and the editor.
 */
@Composable
fun SocialScreen(model: SocialModel, modifier: Modifier = Modifier) {
    // The post being tipped (the amount dialog), and the post whose likes and tips are listed.
    var tipping by remember { mutableStateOf<SocialPost?>(null) }
    var who by remember { mutableStateOf<SocialPost?>(null) }
    var info by remember { mutableStateOf(false) }
    // The person being named (the name dialog).
    var naming by remember { mutableStateOf<Person?>(null) }
    val context = LocalContext.current
    val feed = model.feed
    val states = model.states
    val myAddress = model.myAddress
    val canAct = model.ready && !model.busy

    // Back closes the editor, then the open pages, then goes from the Activity or the profile to the feed.
    BackHandler(enabled = model.draft != null || model.pages.isNotEmpty() || model.view != SocialView.Feed) {
        when {
            model.draft != null -> model.draft = null
            model.pages.isNotEmpty() -> model.back()
            else -> model.view = SocialView.Feed
        }
    }

    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    CompositionLocalProvider(LocalNames provides model.names, LocalNow provides now) {
    Box(modifier.fillMaxSize()) {
        val current = model.draft
        val page = model.pages.lastOrNull()
        val historyDone = model.cache?.historyDone == true
        // A like of a post of a list (a reply or comment is liked from its thread).
        val likePost = { post: SocialPost -> model.preparePost("Like", LIKE, PostTarget.onPost(post.id)) }
        val openThread = { threadId: Long, focusId: Long? -> model.open(SocialPage.Thread(threadId, focusId)) }
        when {
            current != null -> Editor(model, current, canPost = canAct)
            page is SocialPage.Thread -> key(page) {
                ThreadView(
                    post = feed.firstOrNull { it.id == page.postId },
                    focusId = page.focusId,
                    pageState = model.pageState(page),
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    header = { StatusBar(model, onBack = model::back) { info = true } },
                    onProfile = model::openProfile,
                    onLike = { target -> model.preparePost("Like", LIKE, target) },
                    onAnswer = { model.draft = it },
                    onTip = { tipping = it },
                    onWho = { who = it },
                )
            }
            page is SocialPage.Profile -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    StatusBar(model, onBack = model::back) { info = true }
                    ProfileList(
                        address = page.address,
                        feed = feed,
                        states = states,
                        myAddress = myAddress,
                        canAct = canAct,
                        historyDone = historyDone,
                        person = model.person(page.address),
                        onFollow = { model.setPerson(it) },
                        onName = { naming = it },
                        listState = model.pageState(page).list,
                        tab = model.profileTabs[page.address] ?: ProfileTab.Posts,
                        onTab = { model.profileTabs[page.address] = it },
                        onOpen = openThread,
                        onProfile = model::openProfile,
                        onLike = likePost,
                        onTip = { tipping = it },
                        onWho = { who = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBar(model, onBack = null) { info = true }
                    val unread = model.unread.size
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = model.view == SocialView.Me, onClick = { model.view = SocialView.Me }, label = { Text("Profile") })
                        FilterChip(
                            selected = model.view == SocialView.Activity,
                            onClick = { model.openActivity() },
                            label = { Text(if (unread > 0) "Activity · $unread new" else "Activity") },
                        )
                        FilterChip(selected = model.view == SocialView.People, onClick = { model.view = SocialView.People }, label = { Text("Contacts") })
                    }
                    SearchField(model)
                    FeedOptions(model)
                    HorizontalDivider()
                    when (model.view) {
                        SocialView.Activity -> ActivityList(model, Modifier.weight(1f)) { item -> openThread(item.threadId, item.focusId) }
                        SocialView.People -> PeopleList(model, Modifier.weight(1f), onName = { naming = it })
                        SocialView.Me -> {
                            if (myAddress == null) Text("Waiting for the node's address…", style = MaterialTheme.typography.bodyMedium)
                            else ProfileList(
                                address = myAddress,
                                feed = feed,
                                states = states,
                                myAddress = myAddress,
                                canAct = canAct,
                                historyDone = historyDone,
                                person = model.person(myAddress),
                                onFollow = { model.setPerson(it) },
                                onName = { naming = it },
                                listState = model.myProfileState,
                                tab = model.profileTabs[myAddress] ?: ProfileTab.Posts,
                                onTab = { model.profileTabs[myAddress] = it },
                                onOpen = openThread,
                                onProfile = model::openProfile,
                                onLike = likePost,
                                onTip = { tipping = it },
                                onWho = { who = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SocialView.Feed -> {
                            val people = model.people
                            val epochStart = model.node?.epochStart
                            val sorted = remember(feed, model.feedSort, model.feedPeriod, model.feedFollowing, people, epochStart) {
                                val shown = if (model.feedFollowing) followingFeed(feed, people) else feed
                                sortFeed(shown, model.feedSort, model.feedPeriod, System.currentTimeMillis() / 1000, epochStart)
                            }
                            val query = model.searchQuery.trim()
                            val hits by produceState<List<SearchHit>?>(null, query, feed, people) {
                                if (query.length < MIN_SEARCH) {
                                    value = null
                                    return@produceState
                                }
                                // Typing: search once it pauses.
                                kotlinx.coroutines.delay(250)
                                value = withContext(Dispatchers.IO) {
                                    searchSocial(feed, people, query, ipfsText = { cid ->
                                        IpfsStore.cached(context, cid)?.takeIf { it.size <= MAX_IPFS_FILE }?.toString(Charsets.UTF_8)
                                    })
                                }
                            }
                            LazyColumn(
                                Modifier.weight(1f),
                                state = model.feedState,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                // Room for the write button over the last post.
                                contentPadding = PaddingValues(bottom = 88.dp),
                            ) {
                                val found = hits
                                if (query.length >= MIN_SEARCH) {
                                    when {
                                        found == null -> item(key = "searching") { Text("Searching…", style = MaterialTheme.typography.bodySmall) }
                                        found.isEmpty() -> item(key = "none") { Text("Nothing found in the posts read so far.", style = MaterialTheme.typography.bodySmall) }
                                        else -> items(found, key = { hitKey(it) }) { hit ->
                                            HitCard(hit, states, onProfile = model::openProfile, onOpen = openThread)
                                        }
                                    }
                                    return@LazyColumn
                                }
                                if (feed.isEmpty()) item(key = "empty") { Text("No posts found yet.", style = MaterialTheme.typography.bodySmall) }
                                else if (sorted.isEmpty()) item(key = "empty") { Text(emptyFeedText(model), style = MaterialTheme.typography.bodySmall) }
                                items(sorted, key = { it.id }) { post ->
                                    PostCard(
                                        post = post,
                                        states = states,
                                        myAddress = myAddress,
                                        canAct = canAct,
                                        onOpen = { openThread(post.id, null) },
                                        onProfile = model::openProfile,
                                        onLike = { likePost(post) },
                                        onTip = { tipping = post },
                                        onWho = { who = post },
                                    )
                                }
                            }
                        }
                    }
                }
                if (model.view == SocialView.Feed) {
                    FloatingActionButton(
                        onClick = { model.draft = Draft("Post", "New post", PostTarget.NEW_POST, null) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Text("✎", fontSize = 22.sp) }
                }
            }
        }
    }

    model.confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.confirm = null },
            title = { Text("${pending.what} on idena.social") },
            text = { Text(confirmText(pending)) },
            confirmButton = { TextButton(onClick = { model.confirm = null; model.send(pending) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { model.confirm = null }) { Text("Cancel") } },
        )
    }

    tipping?.let { post ->
        TipDialog(
            post = post,
            onDismiss = { tipping = null },
            onTip = { amount ->
                tipping = null
                model.prepare("Tip", SocialPosting.tip(post.id, amount), "Tip $amount iDNA to ${displayName(post.call.author, model.names)} for this post")
            },
        )
    }

    who?.let { post ->
        WhoDialog(post, states, onProfile = { who = null; model.openProfile(it) }, onDismiss = { who = null })
    }

    naming?.let { person ->
        NameDialog(person, onDismiss = { naming = null }) { name ->
            naming = null
            model.setPerson(person.copy(name = name))
        }
    }

    if (info) ScanInfoDialog(model) { info = false }
    }
}

/**
 * The status bar at the top of the tab, always one line high so that the list under it never moves: a
 * colored dot ([BarState]), the paid action while it runs or else the status, and ⓘ for the details
 * (also a tap on the bar). [onBack], on a thread or a profile, adds Back before it.
 */
@Composable
private fun StatusBar(model: SocialModel, onBack: (() -> Unit)?, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let {
            TextButton(onClick = it, Modifier.height(48.dp), contentPadding = PaddingValues(start = 6.dp, end = 14.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.size(26.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(if (onBack == null) 2.dp else 4.dp))
        val action = model.sendStatus
        StatusLine(
            model.status,
            Modifier.weight(1f),
            text = action ?: model.status.label,
            textColor = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            onInfo = onInfo,
        )
    }
}

/** The details behind the status bar: the node, the blocks read, the history, the last action, and "Scan blocks". */
@Composable
private fun ScanInfoDialog(model: SocialModel, onDismiss: () -> Unit) {
    val status = model.status
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.health, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(status.label)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val node = model.node
                Text(
                    when {
                        node == null -> "Node: no answer."
                        node.syncing -> "Node: syncing, block ${blockNumber.format(node.currentBlock)} of ${blockNumber.format(node.highestBlock)} · ${peerText(node.peers)}."
                        else -> "Node: block ${model.head?.let { blockNumber.format(it) } ?: "…"} · ${peerText(node.peers)}."
                    },
                )
                Text(newestText(model.cache, model.head) + ".")
                Text(historyText(model.cache, model.feed.size) + ".")
                Text("Scan: ${model.scanStatus}")
                model.lastAction?.let { Text("Last action: $it") }
                HorizontalDivider()
                FilterChip(selected = model.scanning, onClick = { model.setScanningOn(!model.scanning) }, label = { Text("Scan blocks") })
                Text(
                    "On: the new blocks, then the older posts once, then each new block. Off: nothing is read; the posts " +
                        "already read stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DotLegend(
                    Health.Good to "Up to date",
                    Health.Warn to "Behind or reading older posts",
                    Health.Bad to "No node or no peers",
                    Health.Off to "Off, or paused during a validation",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun peerText(peers: Int): String = if (peers == 1) "1 peer" else "$peers peers"

/** What a paid action does and costs: the files stored on IPFS first, each with its fee, then the call. */
private fun confirmText(pending: PendingPost): String = buildString {
    pending.detail?.let { append(it).append(". ") }
    append("It is public and stays on the blockchain for good, signed by your identity ${shortAddress(pending.from)}.")
    for (file in pending.files) {
        append("\n\nStoring the ${file.what} on IPFS (${kilobytes(file.bytes.size)}): about ${fee(file.fee)} iDNA (at most ${fee(file.fee * BigDecimal(2))}).")
    }
    append("\n\n${if (pending.files.isEmpty()) "Fee" else "The post"}: about ${fee(pending.fee.fee)} iDNA (at most ${fee(pending.fee.maxFee)}).")
    if (pending.files.isNotEmpty()) append("\n\nTotal: about ${fee(pending.totalFee)} iDNA.")
}

private fun fee(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UP).toPlainString()

private fun kilobytes(bytes: Int): String = "${(bytes + 999) / 1000} KB"

/**
 * The Activity: what others did on the identity's posts, newest first, the new ones marked; above it,
 * which kinds notify (a count on the Social tab and a notice while the app is open).
 */
@Composable
private fun ActivityList(model: SocialModel, modifier: Modifier, onOpen: (ActivityItem) -> Unit) {
    val items = model.activity
    val newAfter = model.highlightAfter
    LazyColumn(modifier, state = model.activityState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Notify me of", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifyKind.entries.forEach { kind ->
                        FilterChip(selected = kind in model.notify, onClick = { model.setNotifyOn(kind, kind !in model.notify) }, label = { Text(kind.title) })
                    }
                }
            }
        }
        if (items.isEmpty()) item { Text("No likes, answers or tips on your posts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(items, key = { "${it.kind}:${it.height}:${it.index}:${it.what.hash}" }) { item ->
            ActivityCard(item, isNew = newAfter != null && item.height > newAfter, onProfile = model::openProfile) { onOpen(item) }
        }
    }
}

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


/** The time now (Unix seconds), moved on every 30 s while the tab is shown: the posts' ages follow it. */
val LocalNow = compositionLocalOf { System.currentTimeMillis() / 1000 }

/** How long ago [seconds] was ([timeAgo]): 5s, 10min, 7h, 1d, 5mo, 2y. */
@Composable
private fun ago(seconds: Long): String = timeAgo(seconds, LocalNow.current)



/** The names of the contact list by lowercase address, shown instead of the addresses. */
val LocalNames = compositionLocalOf<Map<String, String>> { emptyMap() }

/** "  ·  Human  ·  age 12", the state and its age in the state's color. */
private fun AnnotatedString.Builder.appendStatus(identity: AuthorIdentity, palette: (String) -> Color) {
    append("  ·  ")
    withStyle(SpanStyle(color = palette(identity.state), fontWeight = FontWeight.Medium)) { append(identity.label) }
}

/** How an address shows as a link to its profile: the style, and the names to show. */
class AddressLink(val styles: TextLinkStyles, val names: Map<String, String>)

@Composable
private fun addressLinkStyles() =
    AddressLink(TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)), LocalNames.current)

/** [address], by its name or shortened ([displayName]), as a link that opens its profile. */
private fun AnnotatedString.Builder.appendAddress(address: String, styles: AddressLink, onProfile: (String) -> Unit) {
    withLink(LinkAnnotation.Clickable(address, styles.styles) { onProfile(address) }) { append(displayName(address, styles.names)) }
}

/**
 * "0x1234…abcd · Human · 30 Sep 2026, 10:05" on one line: the time never goes to the next line, the
 * address and state are cut first. A tap on the address opens its profile.
 */
@Composable
private fun PostHeader(call: PostCall, states: Map<String, AuthorIdentity>, onProfile: (String) -> Unit) {
    // Nothing while the node is asked for the state.
    val identity = states[call.author]
    val styles = addressLinkStyles()
    val palette = statusPalette()
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row {
        Text(
            buildAnnotatedString {
                appendAddress(call.author, styles, onProfile)
                identity?.let { appendStatus(it, palette) }
            },
            Modifier.weight(1f),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(ago(call.time), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * A post of the feed: its start and its action pills; a tap on the text or on 💬 opens the thread, a tap
 * on the address the author's profile.
 */
@Composable
private fun PostCard(
    post: SocialPost,
    states: Map<String, AuthorIdentity>,
    myAddress: String?,
    canAct: Boolean,
    onOpen: () -> Unit,
    onProfile: (String) -> Unit,
    onLike: () -> Unit,
    onTip: () -> Unit,
    onWho: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostHeader(post.call, states, onProfile)
                PostContent(post.call, MaterialTheme.typography.bodyLarge, maxLines = 8)
            }
            ActionBar(post, myAddress, canAct, "💬 ${replyCount(post)}", answerEnabled = true, onLike = onLike, onAnswer = onOpen, onTip = onTip)
            WhoLine(post, onWho)
        }
    }
}

/** A small action button, as Reddit's pills under a post. */
@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(text, style = MaterialTheme.typography.labelLarge) })
}

/**
 * The pills under a post, reply or comment: its likes (Like; "❤️" once the identity liked it), the
 * answer pill ([answer]), and its tips (Tip; not on one's own posts).
 */
@Composable
private fun ActionBar(
    post: SocialPost,
    myAddress: String?,
    canAct: Boolean,
    answer: String,
    answerEnabled: Boolean = canAct,
    onLike: () -> Unit,
    onAnswer: () -> Unit,
    onTip: () -> Unit,
) {
    val liked = myAddress != null && myAddress in post.likedBy
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(if (liked) "$LIKE ${post.likes}" else "♡ ${post.likes}", canAct && !liked, onLike)
        Pill(answer, answerEnabled, onAnswer)
        Pill(if (post.tipTotal > 0) "🪙 ${post.tipTotal}" else "🪙 Tip", canAct && post.call.author != myAddress, onTip)
    }
}

/** "Liked by 3 · tipped by 2 ›" under a post with likes or tips; a tap lists who. */
@Composable
private fun WhoLine(post: SocialPost, onWho: () -> Unit) {
    val tippers = post.tips.map { it.from }.distinct().size
    if (post.likes == 0 && tippers == 0) return
    val parts = listOfNotNull(post.likes.takeIf { it > 0 }?.let { "liked by $it" }, tippers.takeIf { it > 0 }?.let { "tipped by $it" })
    Text(
        parts.joinToString(" · ").replaceFirstChar { it.uppercase() } + "  ›",
        Modifier.clickable(onClick = onWho).padding(top = 2.dp, bottom = 6.dp, end = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Who liked and who tipped [post], newest first, as idena.social's likes and tips lists; a tap on an address opens its profile. */
@Composable
private fun WhoDialog(post: SocialPost, states: Map<String, AuthorIdentity>, onProfile: (String) -> Unit, onDismiss: () -> Unit) {
    val styles = addressLinkStyles()
    val palette = statusPalette()
    fun AnnotatedString.Builder.who(address: String) {
        appendAddress(address, styles, onProfile)
        states[address]?.let { appendStatus(it, palette) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Likes and tips") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (post.likeCalls.isNotEmpty()) {
                    item { Text(if (post.likes == 1) "$LIKE 1 like" else "$LIKE ${post.likes} likes", style = MaterialTheme.typography.titleSmall) }
                    items(post.likeCalls.sortedByDescending { it.height }, key = { it.hash }) { like ->
                        Text(buildAnnotatedString { who(like.author); append("  ·  ${ago(like.time)}") }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (post.tips.isNotEmpty()) {
                    item {
                        val count = if (post.tips.size == 1) "1 tip" else "${post.tips.size} tips"
                        Text("🪙 ${post.tipTotal} iDNA in $count", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    items(post.tips.sortedByDescending { it.height }, key = { it.hash }) { tip ->
                        Text(
                            buildAnnotatedString { who(tip.from); append("  ·  ${tip.validAmount} iDNA  ·  ${ago(tip.time)}") },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
        title = { Text("Tip ${displayName(post.call.author, LocalNames.current)}") },
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

/** One entry of the Activity list, marked when [isNew]; a tap opens its thread, a tap on the address its profile. */
@Composable
private fun ActivityCard(item: ActivityItem, isNew: Boolean, onProfile: (String) -> Unit, onOpen: () -> Unit) {
    val action = when (item.kind) {
        ActivityKind.Like -> "liked"
        ActivityKind.Reply -> "replied to"
        ActivityKind.Comment -> "commented on"
        ActivityKind.Tip -> "tipped you ${item.amount} iDNA for"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val styles = addressLinkStyles()
            if (isNew) Text("● New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                buildAnnotatedString {
                    appendAddress(item.actor, styles, onProfile)
                    append(" $action your post  ·  ${ago(item.time)}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            PostContent(item.what, MaterialTheme.typography.bodyMedium, maxLines = 2, showMedia = false)
            item.answer?.let {
                HorizontalDivider()
                PostContent(it, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
            }
        }
    }
}

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
private fun ProfileList(
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
 * The identity of a profile: its name (contact list) or address, the address in full (Copy), Name and
 * Follow, state, age, stake, and its likes and tips.
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
    val clipboard = LocalClipboardManager.current
    var copied by remember(address) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title = person.name.ifEmpty { shortAddress(address) }
        Text(if (isMe) "$title (you)" else title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (copied) "Copied" else "Copy", enabled = true) {
                clipboard.setText(AnnotatedString(address))
                copied = true
            }
            Pill(if (person.name.isEmpty()) "Name" else "Rename", enabled = true) { onName(person) }
            if (!isMe) {
                FilterChip(
                    selected = person.following,
                    onClick = { onFollow(person.copy(following = !person.following)) },
                    label = { Text(if (person.following) "Following" else "Follow") },
                )
            }
        }
        Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val palette = statusPalette()
        Text(
            identity?.fold(
                {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = palette(it.state), fontWeight = FontWeight.Medium)) { append("${it.status}  ·  age ${it.age}") }
                        append("  ·  stake ${blockNumber.format(it.stake.setScale(0, RoundingMode.DOWN))} iDNA")
                    }
                },
                { AnnotatedString("Identity: the node did not answer (${it.message}).") },
            ) ?: AnnotatedString("Identity: asking the node…"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "$LIKE ${profile.likesReceived} received  ·  🪙 ${profile.tipsReceived} iDNA received, ${profile.tipsGiven} given",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!historyDone) {
            Text(
                "From the blocks scanned so far: older posts appear as the history scan goes on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * A post with its answers, as Reddit shows a post and its comments: the replies, and under each reply
 * its comments, indented behind a thread line (idena.social's two levels). Every item has its pills.
 * Opened at [focusId] (from a profile or the Activity), the thread scrolls to it once and marks it.
 */
@Composable
private fun ThreadView(
    post: SocialPost?,
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
) {
    Column(Modifier.fillMaxSize()) {
        header()
        if (post == null) {
            Text("This post is not in the scanned blocks.", Modifier.padding(16.dp))
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
                    PostHeader(post.call, states, onProfile)
                    PostContent(post.call, MaterialTheme.typography.bodyLarge)
                    ActionBar(post, myAddress, canAct, "💬 Reply", onLike = { onLike(PostTarget.onPost(post.id)) }, onAnswer = {
                        onAnswer(Draft("Reply", "Reply", PostTarget.onPost(post.id), post.call))
                    }) { onTip(post) }
                    WhoLine(post) { onWho(post) }
                    HorizontalDivider()
                    val answers = replyCount(post)
                    Text(if (answers == 1) "1 answer" else "$answers answers", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(post.replies, key = { it.id }) { reply ->
                Column(Modifier.padding(top = 8.dp).focused(reply.id == focusId), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PostHeader(reply.call, states, onProfile)
                    PostContent(reply.call, MaterialTheme.typography.bodyLarge)
                    ActionBar(reply, myAddress, canAct, "💬 Reply", onLike = { onLike(PostTarget.onReply(reply.id)) }, onAnswer = {
                        onAnswer(Draft("Comment", "Comment", PostTarget.onReply(reply.id), reply.call))
                    }) { onTip(reply) }
                    WhoLine(reply) { onWho(reply) }
                    for (comment in reply.replies) {
                        ThreadLine(focused = comment.id == focusId) {
                            PostHeader(comment.call, states, onProfile)
                            PostContent(comment.call, MaterialTheme.typography.bodyLarge)
                            ActionBar(comment, myAddress, canAct, "💬 Reply", onLike = { onLike(PostTarget.onComment(reply.id, comment.id)) }, onAnswer = {
                                onAnswer(Draft("Comment", "Comment", PostTarget.onComment(reply.id, comment.id), comment.call))
                            }) { onTip(comment) }
                            WhoLine(comment) { onWho(comment) }
                        }
                    }
                }
            }
        }
    }
}

/** A comment indented behind a thin vertical line, as Reddit's nested comments. */
@Composable
private fun ThreadLine(focused: Boolean = false, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 6.dp, top = 4.dp)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.padding(start = 12.dp).focused(focused), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** The reply or comment a thread was opened at: a light background behind it. */
@Composable
private fun Modifier.focused(focused: Boolean): Modifier =
    if (!focused) this
    else background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)

/**
 * The full-screen editor of a post, reply or comment: the answered message in full above a text box of
 * a few lines, then an image (stored on IPFS, at a size chosen with its cost) and whether the text goes
 * on IPFS; the screen scrolls. The draft stays in [model] when the tab changes.
 */
@Composable
private fun Editor(model: SocialModel, draft: Draft, canPost: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The image being prepared or why it was refused, and the fee per gas for the size choice.
    var imageNote by remember { mutableStateOf<String?>(null) }
    var feePerGas by remember { mutableStateOf<BigDecimal?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageNote = "Preparing the image…"
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { encodeImageSizes(context, uri) } }
                .onSuccess { sizes ->
                    model.draftImage = ImageDraft(sizes, if (DEFAULT_IMAGE_SIZE in sizes) DEFAULT_IMAGE_SIZE else sizes.keys.max())
                    imageNote = null
                }
                .onFailure { imageNote = "This image cannot be used: ${it.message}" }
            if (feePerGas == null) feePerGas = withContext(Dispatchers.IO) { runCatching { SocialPosting(rpc(context)).feePerGas() }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val image = model.draftImage
    val text = model.draftText
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.draft = null }) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = model::prepareDraft, enabled = canPost && (text.isNotBlank() || image != null) && imageNote == null) { Text("Post") }
        }
        draft.answering?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${displayName(it.author, model.names)}  ·  ${ago(it.time)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PostContent(it, MaterialTheme.typography.bodyLarge)
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { model.draftText = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(if (draft.answering == null) "What's new?" else "Your answer") },
            minLines = 4,
            maxLines = 10,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (image == null) "🖼 Image" else "🖼 Other image", enabled = imageNote != "Preparing the image…") {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            FilterChip(selected = model.textOnIpfs, onClick = { model.textOnIpfs = !model.textOnIpfs }, label = { Text("Text on IPFS") })
        }
        model.sendStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        imageNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (image != null) {
            DraftImage(image.bytes)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                image.sizes.forEach { (side, bytes) ->
                    FilterChip(
                        selected = side == image.chosen,
                        onClick = { model.draftImage = image.copy(chosen = side) },
                        label = { Text("$side px · ${kilobytes(bytes.size)}") },
                    )
                }
            }
            val cost = feePerGas?.let { "about ${fee(storeToIpfsFeeEstimate(it, image.bytes.size))} iDNA" } ?: "a fee that grows with its size"
            Text(
                "The image is stored on IPFS (a storeToIpfs transaction): $cost, on top of the post's fee. " +
                    "It is sent as WebP, without the photo's details (place, camera).",
                style = MaterialTheme.typography.bodySmall,
            )
            Pill("✕ Remove the image", enabled = true) { model.draftImage = null }
        }
        if (model.textOnIpfs) {
            Text(
                "The text is stored on IPFS (a storeToIpfs transaction, cheaper than the post for long texts) and the " +
                    "post holds its address. It stays readable while IPFS nodes keep it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Posts are public and stay on the blockchain for good. The fees are shown before sending.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The image of the draft as it will be posted (decoded from the encoded bytes, so its quality shows). */
@Composable
private fun DraftImage(bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "The image of the post",
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

/** The feed's search bar, a rounded bar: texts of the posts read, contact names, or a pasted address. */
@Composable
private fun SearchField(model: SocialModel) {
    TextField(
        value = model.searchQuery,
        onValueChange = {
            model.searchQuery = it.take(200)
            // The results show in the feed.
            if (model.view != SocialView.Feed) model.showFeed(following = model.feedFollowing)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        placeholder = { Text("Search Chats") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (model.searchQuery.isEmpty()) null else {
            { TextButton(onClick = { model.searchQuery = "" }) { Text("✕") } }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Feed and Following (all posts, or the people followed), then, on the feed, its order and the period of a ranked order as menus. */
@Composable
private fun FeedOptions(model: SocialModel) {
    val inFeed = model.view == SocialView.Feed
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = inFeed && !model.feedFollowing, onClick = { model.showFeed(following = false) }, label = { Text("Feed") })
        FilterChip(selected = inFeed && model.feedFollowing, onClick = { model.showFeed(following = true) }, label = { Text("Following") })
        if (inFeed && model.searchQuery.trim().length < MIN_SEARCH) {
            MenuChip(model.feedSort.title, FeedSort.entries, { it.title }, model::sortFeedBy)
            if (model.feedSort.ranked) MenuChip(model.feedPeriod.title, FeedPeriod.entries, { it.title }) { model.feedPeriod = it }
        }
    }
}

/** A chip showing the choice made ([label] ▾); a tap opens the menu of [options]. */
@Composable
private fun <T> MenuChip(label: String, options: List<T>, title: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label  ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(title(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** Why the feed shows nothing although posts were read. */
private fun emptyFeedText(model: SocialModel): String {
    val from = if (model.feedFollowing) " from the people you follow" else ""
    return when {
        model.feedFollowing && model.people.values.none { it.following } -> "You follow nobody yet: open a profile and select Follow."
        model.feedSort == FeedSort.Likes -> "No liked post in this period$from."
        model.feedSort == FeedSort.Comments -> "No post with answers in this period$from."
        model.feedSort == FeedSort.Tips -> "No tipped post in this period$from."
        else -> "No posts$from in the posts read so far."
    }
}

private fun hitKey(hit: SearchHit): String = when (hit) {
    is SearchHit.Address -> "address:${hit.address}"
    is SearchHit.Named -> "person:${hit.person.address}"
    is SearchHit.Text -> "text:${hit.node.id}"
}

/** One search result: a profile to open (an address, a person), or a text opening its thread at it. */
@Composable
private fun HitCard(
    hit: SearchHit,
    states: Map<String, AuthorIdentity>,
    onProfile: (String) -> Unit,
    onOpen: (threadId: Long, focusId: Long?) -> Unit,
) {
    when (hit) {
        is SearchHit.Address -> Card(Modifier.fillMaxWidth().clickable { onProfile(hit.address) }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Open the profile of ${displayName(hit.address, LocalNames.current)}  ›", style = MaterialTheme.typography.titleSmall)
                Text(hit.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        is SearchHit.Named -> PersonRow(hit.person) { onProfile(hit.person.address) }
        is SearchHit.Text -> Card(Modifier.fillMaxWidth().clickable { onOpen(hit.threadId, hit.node.id.takeIf { it != hit.threadId }) }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PostHeader(hit.node.call, states, onProfile)
                PostContent(hit.node.call, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
            }
        }
    }
}

/** A contact: the name (or the address), the address, and the marks; a tap opens the profile. */
@Composable
private fun PersonRow(person: Person, trailing: @Composable () -> Unit = {}, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name.ifEmpty { shortAddress(person.address) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val marks = listOfNotNull(shortAddress(person.address).takeIf { person.name.isNotEmpty() }, "Following".takeIf { person.following })
                if (marks.isNotEmpty()) {
                    Text(marks.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing()
        }
    }
}

/**
 * The Contacts: the names given and the people followed, kept on the phone only. A tap opens the
 * profile; Rename and Follow there or here. Contacts are added from their profile.
 */
@Composable
private fun PeopleList(model: SocialModel, modifier: Modifier, onName: (Person) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people = remember(model.people) { sortedPeople(model.people) }
    // What the last export or import did, and whether the explanations (ⓘ) are open.
    var note by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Contacts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Names and follows are private: they stay on this phone and are never published. To add someone, open " +
                            "their profile (a tap on an address, or paste the address in the feed's search) and select Name or Follow.",
                    )
                    Text(
                        "Export writes the list to a file of your choice (to keep it, or to move it to another phone); whoever " +
                            "gets the file sees your names. Import adds a file's contacts to this list: nothing here is removed.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        )
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val list = model.people
        scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("the file cannot be written")
                    out.use { it.write(peopleToJson(list).toString(2).toByteArray(Charsets.UTF_8)) }
                }.fold({ "Exported ${peopleCount(list.size)}." }, { "Export failed: ${it.message}" })
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
                    val bytes = input.use { stream ->
                        // At most one byte more than allowed (InputStream.readNBytes needs Android 13).
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= MAX_PEOPLE_FILE) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size > MAX_PEOPLE_FILE) throw IOException("the file is too large")
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json?.optJSONArray("people") == null) throw IOException("this is not an exported contact list")
                    peopleFromJson(json)
                }
            }
            note = read.fold(
                { imported ->
                    val result = model.importPeople(imported)
                    "Imported: ${peopleCount(result.added)} added, ${result.changed} changed."
                },
                { "Import failed: ${it.message}" },
            )
        }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "about") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Export", enabled = people.isNotEmpty()) { exporter.launch("idena-contacts.json") }
                    Pill("Import", enabled = true) { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { about = true }) { Text("ⓘ", fontSize = 20.sp) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (people.isEmpty()) item(key = "empty") { Text("No contacts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(people, key = { it.address }) { person ->
            PersonRow(person, trailing = {
                TextButton(onClick = { onName(person) }) { Text("Rename") }
                FilterChip(
                    selected = person.following,
                    onClick = { model.setPerson(person.copy(following = !person.following)) },
                    label = { Text(if (person.following) "Following" else "Follow") },
                )
            }) { model.openProfile(person.address) }
        }
    }
}

/** The largest contact file imported (about 10,000 contacts). */
private const val MAX_PEOPLE_FILE = 2_000_000

private fun peopleCount(n: Int): String = if (n == 1) "1 contact" else "$n contacts"

/** A private name for [person]'s address; an empty name removes it. */
@Composable
private fun NameDialog(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(person.address) { mutableStateOf(person.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person.name.isEmpty()) "Name ${shortAddress(person.address)}" else "Rename ${person.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shown instead of the address in the Social tab. It stays on this phone: nobody else sees it.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Person.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cleanName(name)) }) { Text(if (cleanName(name).isEmpty() && person.name.isNotEmpty()) "Remove the name" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
