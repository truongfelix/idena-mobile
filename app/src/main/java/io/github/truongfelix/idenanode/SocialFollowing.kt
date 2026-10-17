package io.github.truongfelix.idenanode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * The Following page (from the feed's filter, or the profile's counts): the people and the topics followed, kept on the phone
 * only. Its rows stay while the page is open, an unfollowed one with Follow, so a tap by mistake is undone at once.
 * Export and import of these lists are in Social's settings ([ListsBackup]).
 */

/** The page's two tabs. */
private enum class FollowingTab { People, Topics }

@Composable
fun FollowingView(
    model: SocialModel,
    topicsFirst: Boolean,
    listState: LazyListState,
    onName: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(if (topicsFirst) FollowingTab.Topics else FollowingTab.People) }
    // The rows shown: those followed when the page opened, and those followed now.
    val openedPeople = remember { followedPeople(model.people).map { it.address }.toSet() }
    val openedTopics = remember { model.followedTopics }
    val people = sortedPeople((openedPeople + followedPeople(model.people).map { it.address }).associateWith { model.person(it) })
    val topics = remember(model.feed) { topicList(model.feed).associateBy { it.name } }
    val topicNames = (openedTopics + model.followedTopics).sorted()
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t(R.string.socialb_following_title), style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == FollowingTab.People,
                        onClick = { tab = FollowingTab.People },
                        label = { Text(t(R.string.socialb_people_count, followedPeople(model.people).size)) },
                    )
                    FilterChip(
                        selected = tab == FollowingTab.Topics,
                        onClick = { tab = FollowingTab.Topics },
                        label = { Text(t(R.string.socialb_topics_count, model.followedTopics.size)) },
                    )
                }
                Text(
                    t(R.string.socialb_following_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (tab) {
            FollowingTab.People -> {
                if (people.isEmpty()) item(key = "no-people") { Empty(t(R.string.socialb_follow_nobody)) }
                items(people, key = { "p:${it.address}" }) { person ->
                    PersonRow(person, trailing = {
                        IconButton(onClick = { onName(person) }) { Icon(Icons.Filled.Edit, contentDescription = t(R.string.socialb_rename_short)) }
                        FilterChip(
                            selected = person.following,
                            onClick = { model.setPerson(person.copy(following = !person.following)) },
                            label = { Text(if (person.following) t(R.string.socialb_following) else t(R.string.socialb_follow)) },
                        )
                    }) { model.openProfile(person.address) }
                }
            }
            FollowingTab.Topics -> {
                if (topicNames.isEmpty()) item(key = "no-topics") { Empty(t(R.string.socialb_follow_no_topic)) }
                items(topicNames, key = { "t:$it" }) { name ->
                    val following = name in model.followedTopics
                    TopicRow(name, topics[name]) {
                        FilterChip(
                            selected = following,
                            onClick = { model.setTopicFollowed(name, !following) },
                            label = { Text(if (following) t(R.string.socialb_following) else t(R.string.socialb_follow)) },
                        )
                    }
                }
            }
        }
    }
}

/** A topic followed: its picture, r/name, its posts read and last activity; a tap opens it. */
@Composable
private fun TopicRow(name: String, summary: TopicSummary?, trailing: @Composable () -> Unit) {
    val open = LocalOpenTopic.current
    Card(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable { open(name) } else Modifier)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TopicAvatar(name, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(topicChannel(name), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    summary?.let { tp(R.plurals.socialb_topic_summary, it.posts, it.posts, ago(it.lastTime)) }
                        ?: t(R.string.socialb_topic_no_post),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
}

/**
 * Export and import of the names, the people and the topics followed (one file, [socialListsToJson]), in Social's
 * settings: to keep them, or to move them to another phone. An import adds; nothing here is removed.
 */
@Composable
fun ListsBackup(model: SocialModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val lists = model.lists
        scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(t(R.string.socialb_file_not_written))
                    out.use { it.write(socialListsToJson(lists).toString(2).toByteArray(Charsets.UTF_8)) }
                }.fold(
                    {
                        t(
                            R.string.socialb_exported,
                            tp(R.plurals.socialb_n_people, lists.people.size, lists.people.size),
                            tp(R.plurals.socialb_n_topics, lists.topics.size, lists.topics.size),
                            tp(R.plurals.socialb_n_hidden_words, lists.words.size, lists.words.size),
                            tp(R.plurals.socialb_n_hidden_posts, lists.hiddenPosts.size, lists.hiddenPosts.size),
                            tp(R.plurals.socialb_n_blocked_topics, lists.blockedTopics.size, lists.blockedTopics.size),
                        )
                    },
                    { t(R.string.socialb_export_failed, "${it.message}") },
                )
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException(t(R.string.socialb_file_not_read))
                    val bytes = input.use { stream ->
                        // At most one byte more than allowed (InputStream.readNBytes needs Android 13).
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= MAX_LISTS_FILE) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size > MAX_LISTS_FILE) throw IOException(t(R.string.socialb_file_too_large))
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    json?.let(::socialListsFromJson) ?: throw IOException(t(R.string.socialb_not_a_list))
                }
            }
            note = read.fold(
                { imported ->
                    val result = model.importLists(imported)
                    t(
                        R.string.socialb_imported,
                        tp(R.plurals.socialb_n_people_added, result.people.added, result.people.added),
                        result.people.changed,
                        tp(R.plurals.socialb_n_topics, result.topicsAdded, result.topicsAdded),
                        tp(R.plurals.socialb_n_hidden_words, result.wordsAdded, result.wordsAdded),
                        tp(R.plurals.socialb_n_hidden_posts_or_topics, result.othersAdded, result.othersAdded),
                    )
                },
                { t(R.string.socialb_import_failed, "${it.message}") },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t(R.string.socialb_lists_title), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t(R.string.socialb_export), enabled = true) { exporter.launch("idena-social-lists.json") }
            Pill(t(R.string.socialb_import), enabled = true) { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Text(
            t(R.string.socialb_lists_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The largest list file imported (about 10,000 people). */
private const val MAX_LISTS_FILE = 2_000_000

/** "1 [one]", or [n] [many]. */
fun count(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"
