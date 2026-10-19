package io.github.truongfelix.idenanode

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.semantics.contentDescription
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The Social tab: idena.social read from the phone's node ([SocialModel] scans it), and posting with the
 * node's key. Feed: the search bar and the feed (newest posts or latest activity, with [ads] in it).
 * Messages: the conversations. Notifications: likes, comments and tips on the identity's posts. Profile:
 * the identity's. Over them threads, profiles, conversations and the editor. The bottom bar opens the lists
 * and the editor; the status is in the app's title row ([SocialInfoDialog]).
 */
@Composable
fun SocialScreen(
    model: SocialModel,
    ads: List<ChainAd>,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    myTask: (@Composable () -> Unit)? = null,
    guide: (@Composable () -> Unit)? = null,
) {
    // The post being tipped (the amount dialog), and the post whose likes and tips are listed.
    var tipping by remember { mutableStateOf<SocialPost?>(null) }
    var who by remember { mutableStateOf<SocialPost?>(null) }
    var editingProfile by remember { mutableStateOf(false) }
    // Why + cannot write a post now, while shown.
    var postWait by remember { mutableStateOf<String?>(null) }
    // The person being named (the name dialog).
    var naming by remember { mutableStateOf<Person?>(null) }
    // The feed's filter sheet is open.
    var filtering by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val feed = model.shownFeed
    val states = model.states
    val myAddress = model.myAddress
    val canAct = model.ready && !model.sending.busy

    // Back closes the editor, then the open pages, then goes from the Activity or the profile to the feed.
    BackHandler(enabled = model.sending.draft != null || model.pages.isNotEmpty() || model.view != SocialView.Feed) {
        when {
            model.sending.draft != null -> model.sending.draft = null
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
    CompositionLocalProvider(
        LocalNames provides model.names,
        LocalPictures provides shownPictures(model.profiles.values),
        LocalHiding provides HiddenContent(model.hiding, myAddress),
        LocalPostMenu provides PostMenu(
            me = myAddress,
            person = model::person,
            onHide = model.hidden::hidePost,
            onBlock = model::setPerson,
            onBlockTopic = { model.setTopicBlocked(it, true) },
        ),
        LocalProfileAccess provides ProfileAccess(myAddress, canAct, model.profileAction, model.sending::waiting),
        LocalNow provides now,
        LocalPendingLikes provides model.sending.sent.likes,
        LocalOpenTopic provides model::openTopic,
        LocalOpenChat provides ChatAccess(
            open = { address -> model.openChat(listOf(address.lowercase())) },
            canReceive = { address -> model.messageKeys[address.lowercase()]?.isNotEmpty() },
            lookUp = { address -> model.lookUpKeys(listOf(address)) },
        ),
    ) {
    Column(modifier.fillMaxSize()) {
    val current = model.sending.draft
    val page = model.pages.lastOrNull()
    // The full editor shows a new post; a reply stays in its thread's reply bar (a page opened over the thread, a
    // profile, shows as it is).
    val editing = current?.takeIf { it.target == PostTarget.NEW_POST }
    // Over the feed, the title row floats with the search (see the feed below); elsewhere it stays on top.
    if (current != null || page != null || model.view != SocialView.Feed) topBar()
    // The bottom bar fades out while the content scrolls down and back in when it scrolls up; it is back on
    // each new page or list.
    var barShown by remember { mutableStateOf(true) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -BAR_SCROLL_STEP) barShown = false else if (available.y > BAR_SCROLL_STEP) barShown = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(page, model.view) { barShown = true }
    Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(barScroll)) {
        val historyDone = model.cache?.historyDone == true
        // A like of a post of a list (a reply or comment is liked from its thread).
        val likePost = { post: SocialPost -> model.sending.preparePost(t(R.string.social_what_like), LIKE, PostTarget.onPost(post.id)) }
        val openThread = { threadId: Long, focusId: Long? -> model.open(SocialPage.Thread(threadId, focusId)) }
        when {
            editing != null -> Editor(model, editing, canPost = canAct)
            page is SocialPage.Thread -> key(page) {
                // 2 posts of each 5 in a row, at random, show an ad under the post, above the answers.
                val rank = remember(feed, page.postId) { feed.map { it.id }.sorted().binarySearch(page.postId) }
                ThreadView(
                    post = remember(model.feed, model.hiding, page.postId) { threadPost(model.feed, page.postId, model.hiding, myAddress) },
                    ad = if (threadShowsAd(rank, model.adSalt)) ({ FeedAd(ads, rank, Modifier.padding(vertical = 8.dp)) }) else null,
                    focusId = page.focusId,
                    pageState = model.pageState(page),
                    states = states,
                    myAddress = myAddress,
                    canAct = canAct,
                    header = { BackRow(model::back) },
                    onProfile = model::openProfile,
                    onLike = { target -> model.sending.preparePost(t(R.string.social_what_like), LIKE, target) },
                    onAnswer = { model.sending.draft = it },
                    onTip = { tipping = it },
                    onWho = { who = it },
                    answeringId = current?.target?.replyTo?.takeIf { current.target.channel != null },
                    footer = { if (!it.isOlder) ReplyBar(model, it, canPost = canAct) },
                )
            }
            page is SocialPage.Topic -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    TopicView(
                        name = page.name,
                        feed = feedPosts(feed, model.hiding, myAddress),
                        following = page.name in model.followedTopics,
                        blocked = page.name in model.hidden.topics,
                        listState = model.pageState(page).list,
                        onFollow = { model.setTopicFollowed(page.name, it) },
                        onBlock = { model.setTopicBlocked(page.name, it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        trust = { TopicTrust(page.name, myAddress, canAct, model.profileWrites, model.profileAction, model::openProfile) },
                    ) { post ->
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
                            topicShown = TopicShown.Not,
                        )
                    }
                }
            }
            page is SocialPage.Chat -> key(page) {
                ChatView(model, page.key, model.pageState(page).list, canSend = canAct, onBack = model::back, Modifier.fillMaxSize())
            }
            page is SocialPage.Settings -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    SocialSettingsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.HiddenPosts -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    HiddenPostsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.BlockedTopics -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    BlockedTopicsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.People -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    ProfilePeopleView(page.address, page.list, myAddress, model.profileWrites, model::openProfile, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Muted -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    MutedView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Blocked -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    BlockedView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.HiddenWords -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    HiddenWordsView(model, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Following -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
                    FollowingView(model, page.topics, model.pageState(page).list, onName = { naming = it }, Modifier.weight(1f).padding(horizontal = 16.dp))
                }
            }
            page is SocialPage.Profile -> key(page) {
                Column(Modifier.fillMaxSize()) {
                    BackRow(model::back)
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
                        onBlock = { model.setPerson(it) },
                        listState = model.pageState(page).list,
                        tab = model.profileTabs[page.address] ?: ProfileTab.Posts,
                        onTab = { model.profileTabs[page.address] = it },
                        onOpen = openThread,
                        onProfile = model::openProfile,
                        onLike = likePost,
                        onTip = { tipping = it },
                        onWho = { who = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        contract = model.profiles[page.address.lowercase()],
                        myContract = myAddress?.let { model.profiles[it.lowercase()] },
                        onContract = { model.profiles[it.address] = it },
                        reload = model.profileWrites,
                        onProfileCall = model.profileAction,
                        onPeople = { model.open(SocialPage.People(page.address, it)) },
                    )
                }
            }
            model.view == SocialView.Feed -> {
                val people = model.people
                val epochStart = model.node?.epochStart
                val topics = model.followedTopics
                val filter = model.feedFilter
                val hiding = model.hiding
                val sorted = remember(feed, filter, people, topics, epochStart, hiding) {
                    // Muted accounts and, at Hide, posts with pictures leave the feeds only.
                    filter.apply(feedPosts(feed, hiding, myAddress), people, topics, System.currentTimeMillis() / 1000, epochStart)
                }
                val query = model.searchQuery.trim()
                // "r/…" lists the topics instead of searching the posts.
                val topicSearch = query.startsWith(TOPIC_PREFIX)
                val hits by produceState<List<SearchHit>?>(null, query, feed, people) {
                    if (query.length < MIN_SEARCH || topicSearch) {
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
                // The title row, the search and the options float over the feed and slide with the scroll,
                // as Reddit's: they never push the posts. Back at the top they are all there.
                var headerHeight by remember { mutableIntStateOf(0) }
                var headerOffset by remember { mutableFloatStateOf(0f) }
                val slide = remember(model) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            headerOffset = (headerOffset - available.y).coerceIn(0f, headerHeight.toFloat())
                            return Offset.Zero
                        }
                    }
                }
                LaunchedEffect(model.feedState) {
                    snapshotFlow { model.feedState.firstVisibleItemIndex == 0 && model.feedState.firstVisibleItemScrollOffset == 0 }
                        .collect { atTop -> if (atTop) headerOffset = 0f }
                }
                val headerDp = with(LocalDensity.current) { headerHeight.toDp() }
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(slide).padding(horizontal = 16.dp),
                        state = model.feedState,
                        contentPadding = PaddingValues(top = headerDp, bottom = POST_BUTTON_ROOM),
                    ) {
                        if (topicSearch) {
                            topicItems(feed, query, model.followedTopics, model::openTopic, model::setTopicFollowed)
                            return@LazyColumn
                        }
                        val found = hits
                        if (query.length >= MIN_SEARCH) {
                            when {
                                found == null -> item(key = "searching") { Text(t(R.string.social_searching), style = MaterialTheme.typography.bodySmall) }
                                found.isEmpty() -> item(key = "none") { Text(t(R.string.social_search_nothing), style = MaterialTheme.typography.bodySmall) }
                                else -> itemsIndexed(found, key = { _, hit -> hitKey(hit) }) { i, hit ->
                                    HitCard(
                                        hit,
                                        first = i == 0,
                                        states = states,
                                        myAddress = myAddress,
                                        canAct = canAct,
                                        onProfile = model::openProfile,
                                        onOpen = openThread,
                                        onLike = { target -> model.sending.preparePost(t(R.string.social_what_like), LIKE, target) },
                                        onTip = { tipping = it },
                                        onWho = { who = it },
                                    )
                                }
                            }
                            return@LazyColumn
                        }
                        // Nothing read yet: the way there (B-128); then, while the older posts are read, why to stay (B-130).
                        if (feed.isEmpty()) item(key = "empty") { guide?.invoke() ?: Text(t(R.string.social_no_posts_yet), style = MaterialTheme.typography.bodySmall) }
                        else if (sorted.isEmpty()) item(key = "empty") { Text(emptyFeedText(model), style = MaterialTheme.typography.bodySmall) }
                        if (feed.isNotEmpty()) historyLine(model.cache, model.scanning)?.let { line ->
                            item(key = "history") {
                                Text(line, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // An ad after each POSTS_PER_AD posts.
                        items(
                            feedRows(sorted.size),
                            key = { row -> feedAdAt(row)?.let { "ad:$it" } ?: sorted[feedPostAt(row)].id },
                            contentType = { row -> if (feedAdAt(row) != null) "ad" else "post" },
                        ) { row ->
                            val adNumber = feedAdAt(row)
                            // A thin line between the rows, as Reddit's feed.
                            Column {
                                if (row > 0) HorizontalDivider()
                                if (adNumber != null) {
                                    FeedAd(ads, adNumber, Modifier.padding(vertical = 8.dp))
                                } else {
                                    val post = sorted[feedPostAt(row)]
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
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeight = it.height }
                            .offset { IntOffset(0, -headerOffset.roundToInt()) }
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        topBar()
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.height(2.dp))
                            SearchField(model) { FeedFilterButton(filter) { filtering = true } }
                            FeedFilterLine(filter, onOpen = { filtering = true }, onReset = { model.filterFeed(FeedFilter()) })
                            HorizontalDivider()
                        }
                    }
                }
            }

            else -> {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(2.dp))
                    if (model.view == SocialView.Activity) Text(t(R.string.social_tab_notifications), style = MaterialTheme.typography.titleLarge)
                    when (model.view) {
                        SocialView.Activity -> ActivityList(model, Modifier.weight(1f)) { item -> openThread(item.threadId, item.focusId) }
                        SocialView.Messages -> MessagesList(model, Modifier.weight(1f))
                        SocialView.Me -> {
                            if (myAddress == null) {
                                if (guide != null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { guide() }
                                else Text(t(R.string.social_waiting_address), style = MaterialTheme.typography.bodyMedium)
                            }
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
                                onBlock = { model.setPerson(it) },
                                listState = model.myProfileState,
                                mine = MyProfileLinks(
                                    people = followedPeople(model.people).size,
                                    topics = model.followedTopics.size,
                                    onFollowing = { topics -> model.open(SocialPage.Following(topics)) },
                                    onSettings = { model.open(SocialPage.Settings) },
                                ),
                                tab = model.profileTabs[myAddress] ?: ProfileTab.Posts,
                                onTab = { model.profileTabs[myAddress] = it },
                                onOpen = openThread,
                                onProfile = model::openProfile,
                                onLike = likePost,
                                onTip = { tipping = it },
                                onWho = { who = it },
                                modifier = Modifier.weight(1f),
                                contract = model.profiles[myAddress.lowercase()],
                                onContract = { model.profiles[it.address] = it },
                                onEditProfile = { editingProfile = true },
                                reload = model.profileWrites,
                                onProfileCall = model.profileAction,
                                onPeople = { model.open(SocialPage.People(myAddress, it)) },
                                myTask = myTask,
                            )
                        }
                        SocialView.Feed -> {}
                    }
                }
            }
        }
        // + writes a new post, from the lists posts are read in (in the topic, on a topic's page); it fades with the bar.
        val topic = (page as? SocialPage.Topic)?.name
        val postHere = current == null && (if (page == null) model.view == SocialView.Feed || model.view == SocialView.Me else topic != null)
        androidx.compose.animation.AnimatedVisibility(postHere && barShown, Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
            PostButton(enabled = canAct, onBlocked = { postWait = postWaitText(model) }) { model.sending.whenFunded { newPost(model, topic) } }
        }
        // An action asked with an empty balance: how to get iDNA (B-131).
        if (model.sending.noIdna) NoIdnaDialog(myAddress) { model.sending.noIdna = false }
        // + while posting cannot go: why, instead of a tap that does nothing (owner 2026-10-17).
        postWait?.let { text ->
            AlertDialog(
                onDismissRequest = { postWait = null },
                title = { Text(t(R.string.social_post_wait_title)) },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { postWait = null }) { Text(t(R.string.common_ok)) } },
            )
        }
    }
    // A conversation keeps the room for its messages and the keyboard.
    AnimatedVisibility(current == null && barShown && page !is SocialPage.Chat, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        BottomBar(model)
    }
    }

    model.sending.confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.sending.confirm = null },
            title = { Text(t(R.string.social_confirm_title, pending.what, placeOf(pending))) },
            text = { Text(confirmText(pending)) },
            confirmButton = { TextButton(onClick = { model.sending.confirm = null; model.sending.send(pending) }) { Text(t(R.string.common_send)) } },
            dismissButton = { TextButton(onClick = { model.sending.confirm = null }) { Text(t(R.string.common_cancel)) } },
        )
    }

    if (editingProfile) {
        myAddress?.let { me ->
            ProfileEditDialog(
                address = me,
                profile = model.profiles[me.lowercase()],
                onSave = { editingProfile = false; sendProfileEdit(model.sending, it) },
                onDismiss = { editingProfile = false },
                waitingMethod = model.sending::waitingMethod,
            )
        }
    }

    tipping?.let { post ->
        TipDialog(
            post = post,
            onDismiss = { tipping = null },
            onTip = { amount ->
                tipping = null
                model.sending.prepare(
                    t(R.string.social_what_tip), SocialPosting.tip(post.id, amount),
                    t(R.string.social_tip_detail, amount, displayName(post.call.author, model.names)),
                )
            },
        )
    }

    who?.let { post ->
        WhoDialog(post, states, onProfile = { who = null; model.openProfile(it) }, onDismiss = { who = null })
    }

    if (filtering) {
        FeedFilterSheet(
            filter = model.feedFilter,
            peopleCount = followedPeople(model.people).size,
            topicsCount = model.followedTopics.size,
            onChange = model::filterFeed,
            onList = { topics -> filtering = false; model.open(SocialPage.Following(topics)) },
            onDismiss = { filtering = false },
        )
    }

    naming?.let { person ->
        NameDialog(person, onDismiss = { naming = null }) { name ->
            naming = null
            model.setPerson(person.copy(name = name))
        }
    }
    }
}

/** Opens the editor for a new post, in topic [topic] when given (else in the topic typed last, if any). */
private fun newPost(model: SocialModel, topic: String?) {
    topic?.let { model.sending.draftTopic = it }
    model.sending.draft = Draft(t(R.string.social_what_post), t(R.string.social_new_post), PostTarget.NEW_POST, null)
}

/** The room a list leaves under its last item for [PostButton]. */
val POST_BUTTON_ROOM = 88.dp

/** Why a post cannot be written now: the node syncing, a validation, or a call being sent. */
private fun postWaitText(model: SocialModel): String = when {
    model.node?.validation == true -> t(R.string.social_post_wait_validation)
    !model.ready -> t(R.string.social_post_wait_sync)
    else -> t(R.string.social_post_wait_sending)
}

/** + at the bottom right: the editor for a new post; [onBlocked] when it cannot be written now. */
@Composable
private fun PostButton(enabled: Boolean, onBlocked: () -> Unit, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() else onBlocked() },
        containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) { Icon(Icons.Filled.Add, contentDescription = t(R.string.social_new_post)) }
}

/** Back, at the top of a thread, a profile or a topic. */
@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, Modifier.height(48.dp), contentPadding = PaddingValues(start = 6.dp, end = 14.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text(t(R.string.common_back), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The scroll (pixels in one step) that hides or shows the bottom bar: not a finger's tremor. */
private const val BAR_SCROLL_STEP = 2f

/** The bottom bar's height: the app keeps its notices over it. */
val SOCIAL_BAR_HEIGHT = 60.dp

/**
 * The bottom bar, as the usual social apps': Home (the feed), Messages, Notifications and You (the identity's profile), four
 * slots of one width, with what is new. The list shown is marked, with the pages it has open or none (each list
 * keeps its own, B-101). A new post is written from [PostButton].
 */
@Composable
private fun BottomBar(model: SocialModel) {
    val unread = model.unread.size
    Surface(Modifier.fillMaxWidth().height(SOCIAL_BAR_HEIGHT), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BarItem(t(R.string.social_tab_feed), Icons.Filled.Home, selected = model.view == SocialView.Feed) { model.showList(SocialView.Feed) }
            BarItem(t(R.string.social_tab_messages), Icons.Filled.Email, selected = model.view == SocialView.Messages, badge = model.unreadConversations) {
                model.showList(SocialView.Messages)
            }
            BarItem(t(R.string.social_tab_notifications), Icons.Filled.Notifications, selected = model.view == SocialView.Activity, badge = unread + model.offersToMe.size) {
                model.showList(SocialView.Activity)
            }
            // You: the account's own picture (or its robot) instead of the generic icon (owner 2026-10-17).
            val me = model.myAddress
            BarItem(
                t(R.string.social_tab_profile), Icons.Filled.AccountCircle, selected = model.view == SocialView.Me,
                picture = me?.let { { PersonAvatar(it, 26.dp) } },
            ) { model.showList(SocialView.Me) }
        }
    }
}

@Composable
private fun RowScope.BarItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, picture: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            if (picture != null) picture() else Icon(icon, contentDescription = null, tint = color)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (selected) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
