package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Blocking an account (⋮ on its profile, ⋯ on its posts), hiding a post, blocking a topic (⋯, or its page), and the
 * hidden words: the lists of Social settings, each entry undone there. What they hide: [visibleFeed].
 */

/** Under a blocked account's name. */
const val BLOCKED_TEXT = "Blocked: its posts, replies, comments and messages are hidden on this phone."

/** ⋮ on another's profile, with Block; Block asks first. */
@Composable
fun BlockMenu(person: Person, onBlock: (Person) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Block") }, onClick = {
                open = false
                asking = true
            })
        }
    }
    if (asking) BlockDialog(person, onBlock) { asking = false }
}

/** Asks before blocking [person]. */
@Composable
private fun BlockDialog(person: Person, onBlock: (Person) -> Unit, onDismiss: () -> Unit) {
    val name = displayName(person.address, LocalNames.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $name?") },
        text = {
            Text(
                "Their posts, replies, comments, likes, tips and messages are hidden on this phone. The posts stay public " +
                    "on the chain: they can still see and answer yours. Unblock in Social settings, or on their profile.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onBlock(person.copy(blocked = true))
            }) { Text("Block") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What ⋯ of a post, reply or comment of another can do: hide it, block its author, block its topic. */
class PostMenu(
    val me: String?,
    val person: (String) -> Person,
    val onHide: (hash: String, hide: Boolean) -> Unit,
    val onBlock: (Person) -> Unit,
    val onBlockTopic: (String) -> Unit,
)

/** The post menu of the Social tab; none: no ⋯ (as in the editor). */
val LocalPostMenu = staticCompositionLocalOf<PostMenu?> { null }

/** ⋯ at the top right of [call] (a post, reply or comment, not one's own): Hide (or Show again), Block its author (asked first), Block its topic. */
@Composable
fun PostMenuButton(call: PostCall) {
    val menu = LocalPostMenu.current ?: return
    if (call.author == menu.me) return
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val hidden = call.hash.lowercase() in LocalHiding.current.hiding.posts
    val name = displayName(call.author, LocalNames.current)
    Box {
        IconButton(onClick = { open = true }, Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if (hidden) "Show again" else "Hide") }, onClick = {
                open = false
                menu.onHide(call.hash, !hidden)
            })
            DropdownMenuItem(text = { Text("Block $name", maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                open = false
                asking = true
            })
            topicOf(call.channel)?.let { topic ->
                DropdownMenuItem(text = { Text("Block the topic ${topicChannel(topic)}") }, onClick = {
                    open = false
                    menu.onBlockTopic(topic)
                })
            }
        }
    }
    if (asking) BlockDialog(menu.person(call.author), menu.onBlock) { asking = false }
}

/** The accounts blocked, each with Unblock; a tap on one opens its profile. */
@Composable
fun BlockedView(model: SocialModel, modifier: Modifier = Modifier) {
    val blocked = sortedPeople(model.people).filter { it.blocked }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Blocked accounts", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "Their posts, replies, comments and messages are hidden on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (blocked.isEmpty()) item { Text("Nobody is blocked. To block an account: ⋮ on its profile.", style = MaterialTheme.typography.bodyMedium) }
        items(blocked, key = { it.address }) { person ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person.name.ifEmpty { shortAddress(person.address) },
                    Modifier.weight(1f).clickable { model.openProfile(person.address) }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = { model.setPerson(person.copy(blocked = false)) }) { Text("Unblock") }
            }
        }
    }
}

/** The hidden words: a field to add one, and the list, each with ✕. */
@Composable
fun HiddenWordsView(model: SocialModel, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val words = model.hidden.words.sortedWith(String.CASE_INSENSITIVE_ORDER)
    val full = words.size >= MAX_HIDDEN_WORDS
    val add = {
        val word = cleanHiddenWord(text)
        if (word != null && !full && words.none { matchForm(it) == matchForm(word) }) model.hidden.changeWords(model.hidden.words + word)
        text = ""
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Hidden words", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "A post with one of these words or phrases leaves the lists; a reply or comment with one shows folded in its " +
                    "thread. Whole words, capitals and accents ignored. Your own posts are never hidden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.takeWhole(MAX_HIDDEN_WORD) },
                    Modifier.fillMaxWidth(),
                    label = { Text("A word or phrase") },
                    singleLine = true,
                    enabled = !full,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = { TextButton(onClick = add, enabled = cleanHiddenWord(text) != null && !full) { Text("Add") } },
                )
                if (full) Text("The list is full ($MAX_HIDDEN_WORDS).", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(words, key = { it }) { word ->
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { model.hidden.changeWords(model.hidden.words - word) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $word")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
