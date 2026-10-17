package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Opens a topic's page from its tag on a post; null where tags do not open anything. */
val LocalOpenTopic = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The tag of a post in a topic: "r/name", a tap opens the topic. */
@Composable
fun TopicTag(name: String) {
    val open = LocalOpenTopic.current
    Text(
        topicChannel(name),
        Modifier.then(if (open != null) Modifier.clickable { open(name) } else Modifier).padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The picture of topic [name]: the robot of "r/name" in a circle (a person's robot is a rounded square). */
@Composable
fun TopicAvatar(name: String, size: Dp) {
    Avatar(topicChannel(name), size, shape = CircleShape)
}

/**
 * A post's topic in the author's place: its picture, "r/name" and the post's age ([time]; null for none); a tap
 * opens the topic. [compact]: the feed's smaller line.
 */
@Composable
fun TopicLine(name: String, time: Long?, compact: Boolean = false) {
    val open = LocalOpenTopic.current
    Row(
        Modifier.then(if (open != null) Modifier.clickable { open(name) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopicAvatar(name, if (compact) 24.dp else 36.dp)
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        Text(
            topicChannel(name),
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        time?.let {
            Text("  ·  " + ago(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The topics the search bar lists for "r/…" ([query]): each topic of the posts read holding it, the latest activity first. */
fun LazyListScope.topicItems(
    feed: List<SocialPost>,
    query: String,
    followed: Set<String>,
    onOpen: (String) -> Unit,
    onFollow: (String, Boolean) -> Unit,
) {
    val topics = topicList(feed, query)
    if (topics.isEmpty()) {
        item(key = "no-topics") {
            Text(
                if (query.trim().removePrefix(TOPIC_PREFIX).isBlank()) t(R.string.social_topics_none)
                else t(R.string.social_topics_no_match, query.trim().removePrefix(TOPIC_PREFIX)),
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    items(topics, key = { "topic:${it.name}" }) { topic ->
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(topic.name) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopicAvatar(topic.name, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(topicChannel(topic.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tp(R.plurals.social_topic_summary, topic.posts, topic.posts, ago(topic.lastTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FollowTopicButton(topic.name in followed) { onFollow(topic.name, it) }
            }
        }
    }
}

@Composable
private fun FollowTopicButton(following: Boolean, onFollow: (Boolean) -> Unit) {
    if (following) OutlinedButton(onClick = { onFollow(false) }) { Text(t(R.string.social_following)) }
    else Button(onClick = { onFollow(true) }) { Text(t(R.string.social_follow)) }
}

/**
 * The page of topic [name]: Follow (its posts join the Following feed) and Block (its posts hidden on this phone, the
 * page then offers Unblock), its public [trust], then its posts (+ writes in the topic), newest first, each drawn by [postCard].
 */
@Composable
fun TopicView(
    name: String,
    feed: List<SocialPost>,
    following: Boolean,
    blocked: Boolean,
    listState: LazyListState,
    onFollow: (Boolean) -> Unit,
    onBlock: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trust: @Composable () -> Unit = {},
    postCard: @Composable (SocialPost) -> Unit,
) {
    val posts = remember(feed, name) { topicFeed(feed, name) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = POST_BUTTON_ROOM)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TopicAvatar(name, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(topicChannel(name), style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    tp(R.plurals.social_topic_header, posts.size, posts.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (blocked) {
                    Text(
                        t(R.string.social_topic_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { onBlock(false) }) { Text(t(R.string.social_unblock)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FollowTopicButton(following, onFollow)
                        OutlinedButton(onClick = { onBlock(true) }) { Text(t(R.string.social_block)) }
                    }
                    trust()
                }
                Spacer(Modifier.padding(2.dp))
            }
        }
        if (posts.isEmpty()) item(key = "empty") { Text(t(R.string.social_topic_empty), style = MaterialTheme.typography.bodySmall) }
        items(posts, key = { it.id }) { post ->
            Column {
                HorizontalDivider()
                postCard(post)
            }
        }
    }
}

/** The editor's Topic field, for a new post: blank for none; the topics of [feed] that match are offered. */
@Composable
fun TopicField(text: String, feed: List<SocialPost>, onChange: (String) -> Unit) {
    val typed = text.trim()
    val valid = typed.isEmpty() || typedTopic(typed) != null
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(t(R.string.social_topic_field)) },
        prefix = { Text(TOPIC_PREFIX) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        supportingText = if (valid) null else ({ Text(t(R.string.social_topic_rule)) }),
    )
    val offered = remember(feed, typed) {
        topicList(feed, typed).map { it.name }.filter { it != typedTopic(typed) }.take(MAX_OFFERED_TOPICS)
    }
    if (offered.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offered.forEach { name -> FilterChip(selected = false, onClick = { onChange(name) }, label = { Text(topicChannel(name)) }) }
        }
    }
}

private const val MAX_OFFERED_TOPICS = 8
