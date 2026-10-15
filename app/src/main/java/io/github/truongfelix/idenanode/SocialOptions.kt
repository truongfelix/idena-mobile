package io.github.truongfelix.idenanode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

/**
 * Home's lists: Feed and Following (all posts, or those of the people and topics followed), then the order and the
 * period of a ranked order as menus. The topics are listed by the search bar (r/).
 */
@Composable
fun FeedOptions(model: SocialModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = !model.feedFollowing, onClick = { model.showFeed(following = false) }, label = { Text("Feed") })
        FilterChip(selected = model.feedFollowing, onClick = { model.showFeed(following = true) }, label = { Text("Following") })
        if (model.searchQuery.trim().length < MIN_SEARCH) {
            MenuChip(model.feedSort.title, FeedSort.entries, { it.title }, model::sortFeedBy)
            if (model.feedSort.ranked) MenuChip(model.feedPeriod.title, FeedPeriod.entries, { it.title }) { model.feedPeriod = it }
        }
    }
}

/** The Inbox's lists: Notifications (likes, comments and tips on the identity's posts, with what is new) and Contacts. */
@Composable
fun InboxOptions(model: SocialModel) {
    val unread = model.unread.size
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = model.view == SocialView.Activity,
            onClick = { model.showList(SocialView.Activity) },
            label = { Text(if (unread > 0) "Notifications · $unread new" else "Notifications") },
        )
        FilterChip(selected = model.view == SocialView.People, onClick = { model.showList(SocialView.People) }, label = { Text("Contacts") })
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
fun emptyFeedText(model: SocialModel): String {
    val from = if (model.feedFollowing) " from the people and topics you follow" else ""
    return when {
        model.feedFollowing && model.people.values.none { it.following } && model.followedTopics.isEmpty() ->
            "You follow nobody and no topic yet: open a profile, or a topic (type r/ in the search), and select Follow."
        model.feedSort == FeedSort.Likes -> "No liked post in this period$from."
        model.feedSort == FeedSort.Comments -> "No post with answers in this period$from."
        model.feedSort == FeedSort.Tips -> "No tipped post in this period$from."
        else -> "No posts$from in the posts read so far."
    }
}
