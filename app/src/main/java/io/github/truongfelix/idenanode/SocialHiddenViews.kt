package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/* The lists of Social settings for the posts hidden one by one and the topics blocked, each entry undone there. */

/** A post, reply or comment hidden: its thread ([threadId]), its id ([id]) and its call, when the scanned blocks hold it. */
private class HiddenEntry(val hash: String, val threadId: Long? = null, val id: Long? = null, val call: PostCall? = null)

/** The posts, replies and comments hidden, newest first, each with Show again; a tap opens it in its thread. */
@Composable
fun HiddenPostsView(model: SocialModel, modifier: Modifier = Modifier) {
    val hashes = model.hidden.posts
    val feed = model.feed
    val entries = remember(hashes, feed) {
        val found = HashMap<String, HiddenEntry>()
        for (post in feed) for (node in withReplies(post)) {
            val hash = node.call.hash.lowercase()
            if (hash in hashes) found[hash] = HiddenEntry(hash, post.id, node.id.takeIf { it != post.id }, node.call)
        }
        hashes.map { found[it] ?: HiddenEntry(it) }.sortedByDescending { it.call?.height ?: Long.MAX_VALUE }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Hidden posts", style = MaterialTheme.typography.titleLarge) }
        item { Hint("Posts, replies and comments you hid: they leave the lists, and show folded in their thread.") }
        if (entries.isEmpty()) item { Text("Nothing is hidden. To hide a post: ⋯ under it.", style = MaterialTheme.typography.bodyMedium) }
        items(entries, key = { it.hash }) { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val call = entry.call
                Column(
                    Modifier.weight(1f).clickable(enabled = entry.threadId != null) {
                        entry.threadId?.let { model.open(SocialPage.Thread(it, entry.id)) }
                    }.padding(vertical = 8.dp),
                ) {
                    Text(
                        if (call == null) shortHash(entry.hash) else "${displayName(call.author, model.names)} · ${ago(call.time)}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            call == null -> "Not in the posts read so far."
                            ipfsCid(call.message) != null -> "Text on IPFS"
                            call.message.isEmpty() -> "🖼 Media"
                            else -> call.message.replace(Regex("\\s+"), " ").trim()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = { model.hidden.hidePost(entry.hash, false) }) { Text("Show again") }
            }
        }
    }
}

/** The topics blocked, each with Unblock; a tap opens the topic's page. */
@Composable
fun BlockedTopicsView(model: SocialModel, modifier: Modifier = Modifier) {
    val topics = model.hidden.topics.sorted()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Blocked topics", style = MaterialTheme.typography.titleLarge) }
        item { Hint("Their posts are hidden on this phone only.") }
        if (topics.isEmpty()) item { Text("No topic is blocked. To block one: Block on its page, or ⋯ under one of its posts.", style = MaterialTheme.typography.bodyMedium) }
        items(topics, key = { it }) { topic ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    topicChannel(topic),
                    Modifier.weight(1f).clickable { model.openTopic(topic) }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { model.setTopicBlocked(topic, false) }) { Text("Unblock") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A transaction hash, shortened. */
private fun shortHash(hash: String): String = hash.take(10) + "…" + hash.takeLast(6)
