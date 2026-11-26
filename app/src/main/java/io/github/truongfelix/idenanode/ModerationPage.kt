package io.github.truongfelix.idenanode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * The Moderation page (B-308), for the community moderators only, as the desktop's (views/moderation.js): reports
 * grouped by target, banned accounts (with their posts to review and hide up to 100 at once), posts hidden one by one
 * (Show on this screen only, Restore), the log, the moderators vouched for and one's own vouches.
 */

/** Every post, reply and comment of [feed] by lowercase transaction hash. */
fun postsByHash(feed: List<SocialPost>): Map<String, SocialPost> {
    val byHash = HashMap<String, SocialPost>()
    fun visit(post: SocialPost) {
        byHash[normalHex(post.call.hash)] = post
        post.replies.forEach { visit(it) }
    }
    feed.forEach { visit(it) }
    return byHash
}

/** The id of the post whose thread holds each post, reply and comment of [feed], by lowercase transaction hash. */
fun threadRoots(feed: List<SocialPost>): Map<String, Long> {
    val roots = HashMap<String, Long>()
    fun visit(post: SocialPost, root: Long) {
        roots[normalHex(post.call.hash)] = root
        post.replies.forEach { visit(it, root) }
    }
    feed.forEach { visit(it, it.id) }
    return roots
}

/** The reports on one target, each reporter's last once, newest first. */
data class ReportGroup(val kind: String, val target: String, val reports: List<Report>) {
    val newest: Long
        get() = reports.first().time
}

/**
 * The reports grouped by target, the reports of an account banned now left out (banning one that floods reports
 * clears them); most reporters first, then the newest.
 */
fun ModerationState.reportGroups(epoch: Long?): List<ReportGroup> =
    reports.filter { activeBan(it.reporter, epoch) == null }
        .groupBy { "${it.kind}:${it.target}" }
        .values.map { list ->
            val last = list.associateBy { it.reporter }.values.sortedByDescending { it.time }
            ReportGroup(last.first().kind, last.first().target, last)
        }
        .sortedWith(compareByDescending<ReportGroup> { it.reports.size }.thenByDescending { it.newest })

/** Why a report group needs nothing more (a text), or null while it is open; [author]: the reported post's. */
fun ModerationState.reportResolution(group: ReportGroup, author: String?, epoch: Long?): Int? = when {
    group.kind == "address" -> if (activeBan(group.target, epoch) != null) R.string.mod_resolved_account else null
    group.target in hidden -> R.string.mod_resolved_post
    author != null && activeBan(author, epoch) != null -> R.string.mod_resolved_author
    else -> null
}

/** An account's posts, replies and comments not hidden one by one yet, newest first. */
fun postsToReview(feed: List<SocialPost>, state: ModerationState, address: String): List<SocialPost> =
    postsByHash(feed).values
        .filter { it.call.author == normalHex(address) && normalHex(it.call.hash) !in state.hidden }
        .sortedWith(compareByDescending<SocialPost> { it.call.height }.thenByDescending { it.call.index })

enum class ModerationTab(val title: Int) {
    Reports(R.string.mod_tab_reports), Banned(R.string.mod_tab_banned), Hidden(R.string.mod_tab_hidden),
    Log(R.string.mod_tab_log), Moderators(R.string.mod_tab_moderators), Vouches(R.string.mod_tab_vouches),
}

/** A name that opens the profile. */
@Composable
private fun Who(address: String, onProfile: (String) -> Unit) {
    Text(
        displayName(address, LocalNames.current),
        Modifier.clickable { onProfile(address) },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** A post the moderators review: its text and media only after Show (it may be what they hid), on this screen only. */
@Composable
private fun ReviewedPost(post: SocialPost?, onProfile: (String) -> Unit, onThread: ((SocialPost) -> Unit)?) {
    if (post == null) {
        Text(t(R.string.mod_post_not_read), style = MaterialTheme.typography.bodySmall)
        return
    }
    var shown by remember(post.call.hash) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Who(post.call.author, onProfile)
        // Only a thread the feed shows opens (a post made during a ban has none: Show is the way to read it).
        if (onThread != null) Text(t(R.string.mod_open_thread), Modifier.clickable { onThread(post) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (shown) PostContent(post.call, MaterialTheme.typography.bodyMedium, maxLines = 6, mediaMaxHeight = 200.dp)
    else Text(t(R.string.mod_show), Modifier.clickable { shown = true }, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModerationPage(model: SocialModel, onProfile: (String) -> Unit, onThread: (root: Long, focus: Long) -> Unit, modifier: Modifier = Modifier) {
    val access = LocalModeration.current
    if (access?.role == null) {
        Text(t(R.string.mod_page_only), modifier.padding(16.dp))
        return
    }
    var tab by rememberSaveable { mutableStateOf(ModerationTab.Reports) }
    var reviewing by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<ModerationDialog?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val state = access.state
    val epoch = access.epoch
    val byHash = remember(model.scannedFeed) { postsByHash(model.scannedFeed) }
    val roots = remember(model.scannedFeed) { threadRoots(model.scannedFeed) }
    val shownRoots = model.feed.map { it.id }.toSet()
    // A post's thread, opened at it, when the feed shows that thread.
    val openThread: (SocialPost) -> Unit = { post -> roots[normalHex(post.call.hash)]?.let { onThread(it, post.id) } }
    val threadOf = { post: SocialPost? -> openThread.takeIf { post != null && roots[normalHex(post.call.hash)] in shownRoots } }
    val now = System.currentTimeMillis() / 1000
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t(R.string.mod_setting_title), style = MaterialTheme.typography.titleMedium)
                Text(t(if (access.role == ModeratorRole.Staker) R.string.mod_role_staker else R.string.mod_role_vouched),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModerationTab.entries.forEach { FilterChip(selected = tab == it, onClick = { tab = it; reviewing = null }, label = { Text(t(it.title)) }) }
                }
            }
        }
        when {
            tab == ModerationTab.Banned && reviewing != null -> {
                val address = reviewing!!
                val posts = postsToReview(model.scannedFeed, state, address)
                val listed = posts.map { normalHex(it.call.hash) }.toSet()
                val picked = chosen.filter { it in listed }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Who(address, onProfile)
                            Text("  ·  " + tp(R.plurals.mod_not_hidden_yet, posts.size, posts.size), style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { reviewing = null }) { Text(t(R.string.common_back)) }
                            OutlinedButton(onClick = { chosen = posts.take(MAX_TRANSACTION_BATCH).map { normalHex(it.call.hash) }.toSet() }) {
                                Text(t(R.string.mod_choose_newest, minOf(posts.size, MAX_TRANSACTION_BATCH)))
                            }
                            OutlinedButton(onClick = {
                                dialog = ModerationDialog.Act("hidePosts", hashes = picked, onSent = { chosen = emptySet() })
                            }, enabled = picked.isNotEmpty()) { Text(tp(R.plurals.mod_hide_many_button, picked.size, picked.size)) }
                        }
                    }
                }
                items(posts, key = { it.call.hash }) { post ->
                    val hash = normalHex(post.call.hash)
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(hash in chosen, { on -> chosen = if (on && chosen.size < MAX_TRANSACTION_BATCH) chosen + hash else chosen - hash })
                            Text(timeAgo(post.call.time, now), style = MaterialTheme.typography.bodySmall)
                        }
                        ReviewedPost(post, onProfile, threadOf(post))
                    }
                }
            }
            tab == ModerationTab.Reports -> {
                val groups = state.reportGroups(epoch)
                if (groups.isEmpty()) item { Text(t(R.string.mod_no_reports), style = MaterialTheme.typography.bodySmall) }
                items(groups, key = { "${it.kind}:${it.target}" }) { group ->
                    val post = if (group.kind == "post") byHash[group.target] else null
                    val author = if (group.kind == "post") post?.call?.author else group.target
                    val resolution = state.reportResolution(group, author, epoch)
                    Card {
                        Text(tp(if (group.kind == "post") R.plurals.mod_post_reported else R.plurals.mod_account_reported,
                            group.reports.size, group.reports.size) + "  ·  " + timeAgo(group.newest, now),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (group.kind == "post") ReviewedPost(post, onProfile, threadOf(post)) else Who(group.target, onProfile)
                        group.reports.take(3).forEach { report ->
                            Row { Text("“${report.reason}” · ", style = MaterialTheme.typography.bodySmall); Who(report.reporter, onProfile) }
                        }
                        if (resolution != null) Text(t(resolution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (group.kind == "post") OutlinedButton(onClick = { dialog = ModerationDialog.Act("hidePost", hash = group.target) }) {
                                Text(t(R.string.mod_hide_post_item))
                            }
                            if (author != null && author != access.me) OutlinedButton(onClick = { dialog = ModerationDialog.Act("ban", address = author) }) {
                                Text(t(R.string.mod_ban_account))
                            }
                        }
                    }
                }
            }
            tab == ModerationTab.Banned -> {
                val banned = state.bans.filter { (address, ban) -> banActive(ban, epoch) && address.isNotEmpty() }.toList()
                    .sortedByDescending { it.second.bannedAt }
                if (banned.isEmpty()) item { Text(t(R.string.mod_none_banned), style = MaterialTheme.typography.bodySmall) }
                items(banned, key = { it.first }) { (address, ban) ->
                    Card {
                        Who(address, onProfile)
                        Text(listOf(if (ban.permanent) t(R.string.mod_permanently) else t(R.string.mod_until_epoch, ban.endEpoch), ban.reason,
                            timeAgo(ban.bannedAt, now)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t(R.string.mod_banned_by_short) + " ", style = MaterialTheme.typography.bodySmall)
                            Who(ban.moderator, onProfile)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { reviewing = address; chosen = emptySet() }) { Text(t(R.string.mod_review_posts)) }
                            OutlinedButton(onClick = { dialog = ModerationDialog.Act("unban", address = address) }) { Text(t(R.string.mod_unban_button)) }
                        }
                    }
                }
            }
            tab == ModerationTab.Hidden -> {
                val hidden = state.hidden.toList().sortedByDescending { it.second.hiddenAt }
                if (hidden.isEmpty()) item { Text(t(R.string.mod_none_hidden), style = MaterialTheme.typography.bodySmall) }
                items(hidden, key = { it.first }) { (hash, info) ->
                    Card {
                        Text(listOf(info.reason, timeAgo(info.hiddenAt, now)).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                        ReviewedPost(byHash[hash], onProfile, threadOf(byHash[hash]))
                        OutlinedButton(onClick = { dialog = ModerationDialog.Act("restorePost", hash = hash) }) { Text(t(R.string.mod_restore_button)) }
                    }
                }
            }
            tab == ModerationTab.Log -> {
                val log = model.moderation.events.filter { it.name != "report" && it.name != "reportTransaction" }.reversed()
                if (log.isEmpty()) item { Text(t(R.string.mod_nothing_yet), style = MaterialTheme.typography.bodySmall) }
                items(log, key = { "${it.order.height}:${it.order.index}:${it.order.eventIndex}" }) { event ->
                    val actor = event.moderator.ifEmpty { event.voucher.ifEmpty { event.staker } }
                    val what = t(when (event.name) {
                        "ban" -> R.string.mod_log_banned
                        "unban" -> R.string.mod_log_unbanned
                        "banTransaction" -> R.string.mod_log_hid
                        "unbanTransaction" -> R.string.mod_log_restored
                        "vouch" -> R.string.mod_log_vouched
                        "vouchWithdrawn" -> R.string.mod_log_withdrew
                        else -> R.string.mod_log_revoked
                    })
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        Who(actor, onProfile)
                        Text(" $what ", style = MaterialTheme.typography.bodySmall)
                        if (event.address.isNotEmpty()) Who(event.address, onProfile)
                        Text(listOf(event.reason, timeAgo(event.order.time, now)).filter { it.isNotEmpty() }.joinToString(" · ", prefix = " · "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            tab == ModerationTab.Moderators -> {
                item { Text(t(R.string.mod_stakers_note), style = MaterialTheme.typography.bodySmall) }
                val list = state.vouchers.toList()
                if (list.isEmpty()) item { Text(t(R.string.mod_none_vouched), style = MaterialTheme.typography.bodySmall) }
                items(list, key = { it.first }) { (address, vouchers) ->
                    FlowRow {
                        Who(address, onProfile)
                        Text(" · " + t(R.string.mod_vouched_by_short) + " ", style = MaterialTheme.typography.bodySmall)
                        vouchers.forEachIndexed { i, voucher ->
                            if (i > 0) Text(", ", style = MaterialTheme.typography.bodySmall)
                            Who(voucher, onProfile)
                        }
                    }
                }
            }
            else -> {
                item { Text(t(R.string.mod_vouches_note), style = MaterialTheme.typography.bodySmall) }
                val mine = state.vouchesGiven(access.me)
                if (mine.isEmpty()) item { Text(t(R.string.mod_vouch_nobody), style = MaterialTheme.typography.bodySmall) }
                items(mine, key = { it }) { address ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Who(address, onProfile)
                        OutlinedButton(onClick = { dialog = ModerationDialog.Act("withdrawVouch", address = address) }) { Text(t(R.string.mod_withdraw_button)) }
                    }
                }
            }
        }
    }
    ModerationDialogView(dialog) { dialog = null }
}
