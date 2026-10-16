package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The feed's filter, behind the icon at the end of the search bar: whose posts (all, or those of the people and the
 * topics followed), the order, and the period of a ranked order. A line under the search names a filter that is not
 * the default, with ✕ to reset it.
 */

/** What the feed shows: all posts, or those of the people ([people]) and topics ([topics]) followed; in [sort]'s order. */
data class FeedFilter(
    val people: Boolean = false,
    val topics: Boolean = false,
    val sort: FeedSort = FeedSort.Activity,
    val period: FeedPeriod = FeedPeriod.Epoch,
) {
    /** Whether only followed posts show. */
    val followed: Boolean
        get() = people || topics

    /** The feed as it opens: all posts, the latest activity first (the period counts only for a ranked order). */
    val isDefault: Boolean
        get() = !followed && sort == FeedSort.Activity

    /** One line naming the filter, or null for the default. */
    val summary: String?
        get() {
            if (isDefault) return null
            val parts = listOfNotNull(
                when {
                    people && topics -> "People and topics I follow"
                    people -> "People I follow"
                    topics -> "Topics I follow"
                    else -> null
                },
                sort.title.takeIf { sort != FeedSort.Activity },
                period.title.takeIf { sort.ranked },
            )
            return parts.joinToString(" · ")
        }

    /** [feed] (newest first) filtered and ordered ([sortFeed]); [people] (the People list) and [followedTopics] as followed now. */
    fun apply(
        feed: List<SocialPost>,
        people: Map<String, Person>,
        followedTopics: Set<String>,
        now: Long,
        epochStart: Long?,
    ): List<SocialPost> {
        val shown = if (followed) followingFeed(feed, if (this.people) people else emptyMap(), if (topics) followedTopics else emptySet()) else feed
        return sortFeed(shown, sort, period, now, epochStart)
    }
}

/** Material's "filter list" icon (three lines, shorter each), not in the core icons. */
val FilterIcon: ImageVector by lazy {
    materialIcon(name = "Filled.FilterList") {
        materialPath {
            moveTo(10f, 18f); horizontalLineToRelative(4f); verticalLineToRelative(-2f); horizontalLineToRelative(-4f); close()
            moveTo(3f, 6f); verticalLineToRelative(2f); horizontalLineToRelative(18f); verticalLineTo(6f); close()
            moveTo(6f, 13f); horizontalLineToRelative(12f); verticalLineToRelative(-2f); horizontalLineTo(6f); close()
        }
    }
}

/** The filter's button at the end of the search bar, with a dot while the filter is not the default. */
@Composable
fun FeedFilterButton(filter: FeedFilter, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, Modifier.size(40.dp)) {
        Box {
            Icon(FilterIcon, contentDescription = "Filter the feed", tint = if (filter.isDefault) colors.onSurfaceVariant else colors.primary)
            if (!filter.isDefault) Box(Modifier.size(8.dp).align(Alignment.TopEnd).clip(CircleShape).background(colors.primary))
        }
    }
}

/** The line under the search naming a filter that is not the default; ✕ resets it. */
@Composable
fun FeedFilterLine(filter: FeedFilter, onOpen: () -> Unit, onReset: () -> Unit) {
    val summary = filter.summary ?: return
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Text(
            summary,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onReset, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = "Show all posts", Modifier.size(18.dp)) }
    }
}

/**
 * The filter's sheet: each choice applies at once. Show: All, People I follow, Topics I follow (the two together
 * show both); › opens that list. Sort, then the period of a ranked order.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedFilterSheet(
    filter: FeedFilter,
    peopleCount: Int,
    topicsCount: Int,
    onChange: (FeedFilter) -> Unit,
    onList: (topics: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetTitle("Show")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !filter.followed, onClick = { onChange(filter.copy(people = false, topics = false)) }, label = { Text("All") })
                FollowChip("People I follow $peopleCount", filter.people, { onChange(filter.copy(people = !filter.people)) }) { onList(false) }
                FollowChip("Topics I follow $topicsCount", filter.topics, { onChange(filter.copy(topics = !filter.topics)) }) { onList(true) }
            }
            SheetTitle("Sort")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedSort.entries.forEach { sort ->
                    FilterChip(selected = filter.sort == sort, onClick = { onChange(filter.copy(sort = sort)) }, label = { Text(sort.title) })
                }
            }
            if (filter.sort.ranked) {
                SheetTitle("Period")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedPeriod.entries.forEach { period ->
                        FilterChip(selected = filter.period == period, onClick = { onChange(filter.copy(period = period)) }, label = { Text(period.title) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** A chip to filter by what is followed, and › beside it to open that list. */
@Composable
private fun FollowChip(label: String, selected: Boolean, onToggle: () -> Unit, onList: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = selected, onClick = onToggle, label = { Text(label) })
        IconButton(onClick = onList, Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open the list")
        }
    }
}

/** Why the feed shows nothing although posts were read. */
fun emptyFeedText(model: SocialModel): String {
    val filter = model.feedFilter
    val from = if (filter.followed) " from what you follow" else ""
    return when {
        filter.followed && (!filter.people || model.people.values.none { it.following }) && (!filter.topics || model.followedTopics.isEmpty()) ->
            "You follow nobody and no topic yet: open a profile, or a topic (type r/ in the search), and select Follow."
        filter.sort == FeedSort.Likes -> "No liked post in this period$from."
        filter.sort == FeedSort.Comments -> "No post with answers in this period$from."
        filter.sort == FeedSort.Tips -> "No tipped post in this period$from."
        else -> "No posts$from in the posts read so far."
    }
}
