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

/** Home's Topics list: each topic of the posts read, the latest activity first; [query] (the search bar) filters them. */
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
                if (query.isBlank()) "No posts in a topic yet. Write one: the editor's Topic field puts a post in a topic."
                else "No topic holds \"${query.trim()}\" in the posts read so far.",
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(topicChannel(topic.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${topic.posts} ${if (topic.posts == 1) "post" else "posts"}  ·  last activity ${ago(topic.lastTime)}",
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
    if (following) OutlinedButton(onClick = { onFollow(false) }) { Text("Following") }
    else Button(onClick = { onFollow(true) }) { Text("Follow") }
}

/**
 * The page of topic [name]: Follow (its posts join the Following feed), "Post in r/name", then its posts, newest
 * first, each drawn by [postCard].
 */
@Composable
fun TopicView(
    name: String,
    feed: List<SocialPost>,
    following: Boolean,
    canPost: Boolean,
    listState: LazyListState,
    onFollow: (Boolean) -> Unit,
    onPost: () -> Unit,
    modifier: Modifier = Modifier,
    postCard: @Composable (SocialPost) -> Unit,
) {
    val posts = remember(feed, name) { topicFeed(feed, name) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(topicChannel(name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${posts.size} ${if (posts.size == 1) "post" else "posts"} in the posts read so far. Anyone can write in " +
                        "any topic; idena.social does not show topic posts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FollowTopicButton(following, onFollow)
                    OutlinedButton(onClick = onPost, enabled = canPost) { Text("Post in ${topicChannel(name)}") }
                }
                Spacer(Modifier.padding(2.dp))
            }
        }
        if (posts.isEmpty()) item(key = "empty") { Text("No posts in this topic yet.", style = MaterialTheme.typography.bodySmall) }
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
        label = { Text("Topic (optional)") },
        prefix = { Text(TOPIC_PREFIX) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        supportingText = {
            Text(
                if (valid) "Shown with the topic in our apps; idena.social does not show posts in a topic."
                else "a-z, 0-9 and _, 3 to 21 characters",
            )
        },
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
