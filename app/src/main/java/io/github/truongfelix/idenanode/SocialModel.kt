package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** The node's peers, sync and validation period. */
private fun readNode(rpc: NodeRpc): NodeView {
    val sync = rpc.call("bcn_syncing") as JSONObject
    val peers = (rpc.call("net_peers") as? JSONArray)?.length() ?: 0
    return NodeView(
        peers, sync.optBoolean("syncing"), sync.optLong("currentBlock"), sync.optLong("highestBlock"),
        validation = rpc.ceremony().period != "None", wrongTime = sync.optBoolean("wrongTime"),
        epochStart = (rpc.call("dna_epoch") as JSONObject).optLong("startBlock").takeIf { it > 0 },
    )
}

/** A page opened over the tab's lists; Back closes the last one. */
sealed interface SocialPage {
    /** The thread of post [postId], shown at [focusId] (a reply or comment of it) when given. */
    data class Thread(val postId: Long, val focusId: Long? = null) : SocialPage

    /** The profile of [address] (lowercase). */
    data class Profile(val address: String) : SocialPage
}

/** What an open page keeps while another page is opened over it: its place in its list. */
class PageState {
    val list = LazyListState()

    /** Whether a thread was already scrolled to its focus (not again when coming back to it). */
    var focused = false

    /** The replies and comments of a thread folded to one line (with everything under them). */
    val collapsed = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
}

/** The tab's lists: the feed, the activity on the identity's posts, its own profile, and the People list. */
enum class SocialView { Feed, Me, Activity, People }

/** The lists of a profile, as idena.social's profile page has them. */
enum class ProfileTab(val title: String) {
    Posts("Posts"), Replies("Replies"), Comments("Comments"), Likes("Likes"), Tips("Tips"), Media("Media"),
}

/**
 * The Social tab's state while the app runs, kept when the Node tab is shown: the scan of idena.social
 * from the node, the feed, the activity on the identity's posts and what is new there, the open pages,
 * and through [sending] the editor's draft and the paid actions.
 *
 * While "Scan blocks" is on, [run] scans the blocks after the newest scanned one up to the node's head,
 * then the history back to the contract's first block (once), then follows the head. On the Node tab it
 * only follows the head, and only when a kind of notification is on. Nothing is scanned while the app is
 * hidden, the node syncs or a validation runs.
 */
class SocialModel(private val context: Context, private val scope: CoroutineScope) {
    var cache by mutableStateOf<SocialCache?>(null)
        private set
    var feed by mutableStateOf<List<SocialPost>>(emptyList())
        private set

    /** The identity state and age of the authors (Human, Verified...), read again at each new epoch. */
    val states = mutableStateMapOf<String, AuthorIdentity>()
    private var statesEpochStart: Long? = null
    var head by mutableStateOf<Long?>(null)
        private set

    /** What the scan is doing, shown at the top of the tab. */
    var scanStatus by mutableStateOf("Loading…")
        private set

    /** Whether the node is synced and outside a validation: posting needs it. */
    var ready by mutableStateOf(false)
        private set

    /** The node at the last scan step; null when it did not answer ([nodeFailed]) or before it was asked. */
    var node by mutableStateOf<NodeView?>(null)
        private set
    var nodeFailed by mutableStateOf(false)
        private set

    /** The status bar's dot and line. */
    val status: BarState
        get() = if (node == null && !nodeFailed) BarState(Health.Off, "Connecting to the node…") else scanState(node, scanning, head, cache)
    var scanning by mutableStateOf(SocialStore.isScanning(context))
        private set
    /** The node's account (lowercase), read at each scan step; null before the first. */
    var myAddress by mutableStateOf<String?>(null)
        private set

    /** Whether the Social tab is shown: the whole scan runs only then. */
    var visible by mutableStateOf(false)

    // The scan runs on an IO thread and reads these between two batches.
    private val scanningFlag = AtomicBoolean(scanning)

    // After a post, the new blocks are scanned once even without "Scan blocks".
    private val scanNewOnce = AtomicBoolean(false)
    private var wake by mutableIntStateOf(0)

    /** The likes, answers and tips of others on the identity's posts, newest first. */
    var activity by mutableStateOf<List<ActivityItem>>(emptyList())
        private set

    /** The kinds of activity that notify. */
    var notify by mutableStateOf(SocialStore.notifyKinds(context))
        private set

    /** The block through which the Activity was seen (the scan's authors block then); null before the first scan. */
    var seenThrough by mutableStateOf(SocialStore.seenThrough(context))
        private set

    /** The block the Activity was seen through when it was opened: what came after shows as new. */
    var highlightAfter by mutableStateOf<Long?>(null)
        private set

    // The block through which news were announced, so that each is announced once.
    private var announcedThrough = seenThrough ?: Long.MAX_VALUE

    /** The in-app notices of new activity, shown by the main screen. */
    val notices = Channel<String>(Channel.CONFLATED)

    /** The activity of the kinds that notify, not seen yet. */
    val unread: List<ActivityItem>
        get() = newActivity(activity, seenThrough ?: Long.MAX_VALUE, notify)

    // Navigation: the list shown, the open threads and profiles (Back closes the last), each list's place.
    var view by mutableStateOf(SocialView.Feed)
    var feedSort by mutableStateOf(FeedSort.Newest)
        private set

    /** The period of a ranked sort, and whether the feed shows the posts of the people followed only. */
    var feedPeriod by mutableStateOf(FeedPeriod.Epoch)
    var feedFollowing by mutableStateOf(false)

    /** The random picks of the posts whose thread shows an ad ([threadShowsAd]), new at each app start. */
    val adSalt = kotlin.random.Random.nextLong()

    /** What the feed's search bar has; the feed shows what it finds while it has [MIN_SEARCH] characters. */
    var searchQuery by mutableStateOf("")

    /** The People list: names and marks kept on the phone ([Person]). */
    var people by mutableStateOf(SocialStore.people(context))
        private set
    val names: Map<String, String>
        get() = peopleNames(people)

    /** Puts [person] in the People list (or takes it out once it has no name and no mark) and saves the list. */
    fun setPerson(person: Person) {
        people = people.with(person)
        savePeople()
    }

    /** Merges an exported People list into this one ([mergePeople]) and saves it. */
    fun importPeople(imported: Map<String, Person>): PeopleImport {
        val result = mergePeople(people, imported)
        people = result.people
        savePeople()
        return result
    }

    private fun savePeople() {
        // One save at a time, each of the newest list.
        scope.launch(Dispatchers.IO) { peopleSave.withLock { runCatching { SocialStore.setPeople(context, people) } } }
    }
    private val peopleSave = Mutex()

    /** [address]'s entry of the People list, or an empty one. */
    fun person(address: String): Person = people[address.lowercase()] ?: Person(address.lowercase())
    val pages = mutableStateListOf<SocialPage>()
    private val pageStates = HashMap<SocialPage, PageState>()
    val profileTabs = mutableStateMapOf<String, ProfileTab>()
    val feedState = LazyListState()
    val activityState = LazyListState()
    val myProfileState = LazyListState()

    /** The editor and the paid actions. */
    val sending = SocialSending(context, scope) {
        scanNewOnce.set(true)
        wake++
    }

    fun setScanningOn(on: Boolean) {
        scanning = on
        scanningFlag.set(on)
        SocialStore.setScanning(context, on)
    }

    fun setNotifyOn(kind: NotifyKind, on: Boolean) {
        notify = if (on) notify + kind else notify - kind
        SocialStore.setNotifyKinds(context, notify)
    }

    /** The Inbox's list last shown: the notifications ([SocialView.Activity]) or the chats ([SocialView.People]). */
    var inboxView by mutableStateOf(SocialView.Activity)

    /** Shows the Activity: what is new now stays marked while it is open, and counts as seen. */
    fun openActivity() {
        view = SocialView.Activity
        inboxView = SocialView.Activity
        highlightAfter = seenThrough
        markSeen()
    }

    /** Shows the Activity from a notice: the lists, not a page or the editor (the draft's text stays). */
    fun showActivity() {
        pages.clear()
        pageStates.clear()
        sending.draft = null
        openActivity()
    }

    /** Shows the list [list] from the bottom bar, closing the open pages; the Activity counts as seen. */
    fun showList(list: SocialView) {
        pages.clear()
        pageStates.clear()
        if (list == SocialView.People) inboxView = list
        if (list == SocialView.Activity) openActivity() else view = list
    }

    private val viewingActivity: Boolean
        get() = visible && view == SocialView.Activity && pages.isEmpty() && sending.draft == null

    private fun markSeen() {
        val through = cache?.authorsHeight ?: return
        if (through > (seenThrough ?: Long.MIN_VALUE)) {
            seenThrough = through
            SocialStore.setSeenThrough(context, through)
        }
        announcedThrough = maxOf(announcedThrough, through)
    }

    /** Shows the feed: all posts, or those of the people followed ([following]), from its top when that changes. */
    fun showFeed(following: Boolean) {
        view = SocialView.Feed
        if (following == feedFollowing) return
        feedFollowing = following
        scope.launch { feedState.scrollToItem(0) }
    }

    /** Orders the feed by [sort], from its top (the place in the other order means nothing here). */
    fun sortFeedBy(sort: FeedSort) {
        if (sort == feedSort) return
        feedSort = sort
        scope.launch { feedState.scrollToItem(0) }
    }

    fun open(page: SocialPage) {
        if (pages.lastOrNull() != page) pages += page
    }

    fun openProfile(address: String) {
        val lower = address.lowercase()
        // Already shown: the identity's own profile.
        if (pages.isEmpty() && view == SocialView.Me && lower == myAddress) return
        open(SocialPage.Profile(lower))
    }

    fun back() {
        // A second tap on Back may come before the page is gone.
        if (pages.isEmpty()) return
        val page = pages.removeAt(pages.lastIndex)
        if (page !in pages) pageStates.remove(page)
    }

    fun pageState(page: SocialPage) = pageStates.getOrPut(page) { PageState() }

    private suspend fun show(next: SocialCache) {
        cache = next
        feed = withContext(Dispatchers.Default) { next.feed() }
        // What was sent is in the feed now when its block was read.
        sending.sent.scanned(next.authorsHeight)
        // The ages and states change at the validation: ask again in a new epoch.
        node?.epochStart?.let { start ->
            if (statesEpochStart != null && statesEpochStart != start) states.clear()
            statesEpochStart = start
        }
        val unknown = feed.flatMap { withReplies(it) }.map { it.call.author }.distinct().filter { it !in states }
        if (unknown.isNotEmpty()) {
            withContext(Dispatchers.IO) { runCatching { NodeSocialSource(rpc(context)).identityStates(unknown) }.getOrNull() }
                ?.let { states.putAll(it) }
        }
        updateActivity()
    }

    /**
     * The node's account is [address] (lowercase): at the first scan step, or another one after a key import.
     * For another one, what is on its posts already is not news, and its own profile shows from the top.
     */
    private suspend fun setAccount(address: String) {
        val switched = myAddress != null
        myAddress = address
        if (switched) {
            markSeen()
            highlightAfter = seenThrough
            scope.launch { myProfileState.scrollToItem(0) }
        }
        updateActivity()
    }

    /** The activity on the identity's posts after a scan, and a notice of what is new. */
    private suspend fun updateActivity() {
        val me = myAddress ?: return
        val current = feed
        activity = withContext(Dispatchers.Default) { socialActivity(current, me) }
        val through = cache?.authorsHeight ?: return
        if (seenThrough == null) {
            // The first scan: what is there already is not news.
            seenThrough = through
            SocialStore.setSeenThrough(context, through)
            announcedThrough = through
            return
        }
        if (viewingActivity) {
            markSeen()
            return
        }
        val news = newActivity(activity, maxOf(seenThrough!!, announcedThrough), notify)
        announcedThrough = maxOf(announcedThrough, through)
        if (news.isNotEmpty()) notices.trySend(noticeText(news) { displayName(it, names) })
    }

    /** Waits [ms], or less when a button changes, the tab is shown or hidden, or a post was made. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { snapshotFlow { listOf(scanning, wake, visible, notify) }.drop(1).first() }
    }

    /** Scans while the app is shown (see the class); returns when [lifecycle] ends. */
    suspend fun run(lifecycle: Lifecycle) {
        withContext(Dispatchers.IO) { SocialStore.load(context) }?.let { show(it) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    // On the Node tab, only the new blocks for the notifications; else nothing is asked.
                    if (!visible && !(scanning && notify.isNotEmpty() && cache != null)) {
                        pause(60_000)
                        continue
                    }
                    val view = try {
                        withContext(Dispatchers.IO) { readNode(rpc(context)) }
                    } catch (e: IOException) {
                        node = null
                        nodeFailed = true
                        ready = false
                        scanStatus = "The node did not answer: ${e.message}"
                        pause(10_000)
                        continue
                    }
                    node = view
                    nodeFailed = false
                    // The node's account, at each step: another one after a key import (the node restarts with it).
                    val address = withContext(Dispatchers.IO) { rpc(context).call("dna_getCoinbaseAddr") as String }.lowercase()
                    if (address != myAddress) setAccount(address)
                    ready = !view.syncing && !view.validation
                    if (!ready) {
                        scanStatus = if (view.syncing) "Waiting: the node is syncing." else "Paused during the validation."
                        pause(30_000)
                        continue
                    }
                    val source = NodeSocialSource(rpc(context))
                    val nodeHead = withContext(Dispatchers.IO) { source.head() }
                    head = nodeHead
                    val current = cache
                    val wantsNew = scanningFlag.get() || scanNewOnce.get()
                    when {
                        // The first time, the scan starts at the head: nothing to read yet.
                        current == null && wantsNew -> {
                            scanStatus = "Starting at the node's block…"
                            show(withContext(Dispatchers.IO) { checkNewBlocks(null, source) { SocialStore.save(context, it) } })
                        }
                        current == null -> {
                            scanStatus = "Select \"Scan blocks\" to read the posts from the node."
                            pause(20_000)
                        }
                        wantsNew && (current.high < nodeHead || current.authorsHeight < current.high) -> {
                            scanStatus = "Scanning new blocks…"
                            val next = withContext(Dispatchers.IO) {
                                checkNewBlocks(current, source, keepGoing = { scanningFlag.get() || scanNewOnce.get() }) { SocialStore.save(context, it) }
                            }
                            if (next.authorsHeight >= next.high) scanNewOnce.set(false)
                            show(next)
                        }
                        visible && !current.historyDone && scanningFlag.get() -> {
                            scanStatus = "Scanning the history…"
                            show(withContext(Dispatchers.IO) { scanOlderBlocks(current, source) { SocialStore.save(context, it) } })
                        }
                        else -> {
                            scanNewOnce.set(false)
                            scanStatus = when {
                                !scanningFlag.get() -> "Stopped. Select \"Scan blocks\" to read the newest posts."
                                else -> "Up to date."
                            }
                            // A new block comes about every 20 seconds.
                            pause(20_000)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    scanStatus = "The node did not answer: ${e.message}"
                    pause(10_000)
                }
            }
        }
    }

}

/** The post and all its replies. */
fun withReplies(post: SocialPost): List<SocialPost> = listOf(post) + post.replies.flatMap { withReplies(it) }

/** "0x1234…abcd". */
fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"
