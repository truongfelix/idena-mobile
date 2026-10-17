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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Blocking or muting an account (⋮ on its profile, ⋯ on its posts), hiding a post, blocking a topic (⋯, or its page),
 * and the hidden words: the lists of Social settings, each entry undone there. What they hide: [visibleFeed],
 * [Hiding.leavesFeeds].
 */

/** Under a blocked account's name. */
val BLOCKED_TEXT: String
    get() = t(R.string.socialb_blocked_text)

/** ⋮ on another's profile: Name on this phone ([onName]), Mute or Unmute, Block (asked first). */
@Composable
fun BlockMenu(person: Person, onBlock: (Person) -> Unit, onName: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = t(R.string.common_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onName?.let {
                DropdownMenuItem(text = { Text(t(R.string.socialb_name_on_phone)) }, onClick = {
                    open = false
                    it()
                })
            }
            // Mute: their posts leave the feeds, their replies fold; the profile still lists them.
            DropdownMenuItem(text = { Text(if (person.muted) t(R.string.socialb_unmute) else t(R.string.socialb_mute)) }, onClick = {
                open = false
                onBlock(person.copy(muted = !person.muted))
            })
            DropdownMenuItem(text = { Text(t(R.string.socialb_block)) }, onClick = {
                open = false
                asking = true
            })
        }
    }
    if (asking) BlockDialog(person, onBlock) { asking = false }
}

/**
 * Who sends calls to the profiles contract in the Social tab: the account, whether it can act now, the call, and
 * whether the same call waits for its block ([SocialSending.waiting]).
 */
class ProfileAccess(val me: String?, val canAct: Boolean, val onCall: ProfileAction, val waiting: (SocialCall) -> Boolean = { false })

/** The Social tab's [ProfileAccess]; none outside it. */
val LocalProfileAccess = staticCompositionLocalOf<ProfileAccess?> { null }

/**
 * Asks before blocking [person], as the desktop's. When the account gives them Trust, it asks whether to take the
 * Trust back too: a transaction, its fee shown next (never silently).
 */
@Composable
private fun BlockDialog(person: Person, onBlock: (Person) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = displayName(person.address, LocalNames.current)
    val access = LocalProfileAccess.current
    val giver by produceState(false, person.address, access?.me) {
        val me = access?.me ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { NodeProfilesSource(NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))).trust(person.address).givenBy(me) }
                .getOrDefault(false)
        }
    }
    val block = {
        onDismiss()
        onBlock(person.copy(blocked = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.socialb_block_ask, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.socialb_block_note))
                if (giver) Text(t(R.string.socialb_block_trust_note, name))
            }
        },
        confirmButton = {
            if (giver && access != null) {
                TextButton(onClick = {
                    block()
                    access.onCall(t(R.string.profile_untrust_what), t(R.string.profile_untrust_detail), ProfileCalls.untrust(person.address))
                }, enabled = access.canAct) { Text(t(R.string.socialb_block_and_untrust)) }
            } else {
                TextButton(onClick = block) { Text(t(R.string.socialb_block)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) }
                if (giver) TextButton(onClick = block) { Text(t(R.string.socialb_block_only)) }
            }
        },
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

/**
 * ⋯ at the top right of [call] (a post, reply or comment, not one's own): Hide (or Show again), Mute (or Unmute) its
 * author, Block its author (asked first), Block its topic.
 */
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
            Icon(Icons.Filled.MoreVert, contentDescription = t(R.string.common_more), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if (hidden) t(R.string.socialb_show_again) else t(R.string.socialb_hide)) }, onClick = {
                open = false
                menu.onHide(call.hash, !hidden)
            })
            val author = menu.person(call.author)
            DropdownMenuItem(text = { Text(if (author.muted) t(R.string.socialb_unmute_name, name) else t(R.string.socialb_mute_name, name), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                open = false
                menu.onBlock(author.copy(muted = !author.muted))
            })
            DropdownMenuItem(text = { Text(t(R.string.socialb_block_name, name), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                open = false
                asking = true
            })
            topicOf(call.channel)?.let { topic ->
                DropdownMenuItem(text = { Text(t(R.string.socialb_block_topic, topicChannel(topic))) }, onClick = {
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
        item { Text(t(R.string.socialb_blocked_accounts), style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                t(R.string.socialb_blocked_accounts_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (blocked.isEmpty()) item { Text(t(R.string.socialb_nobody_blocked), style = MaterialTheme.typography.bodyMedium) }
        items(blocked, key = { it.address }) { person ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person.name.ifEmpty { shortAddress(person.address) },
                    Modifier.weight(1f).clickable { model.openProfile(person.address) }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = { model.setPerson(person.copy(blocked = false)) }) { Text(t(R.string.socialb_unblock)) }
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
        item { Text(t(R.string.socialb_hidden_words), style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                t(R.string.socialb_hidden_words_note),
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
                    label = { Text(t(R.string.socialb_word_or_phrase)) },
                    singleLine = true,
                    enabled = !full,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    trailingIcon = { TextButton(onClick = add, enabled = cleanHiddenWord(text) != null && !full) { Text(t(R.string.socialb_add)) } },
                )
                if (full) Text(t(R.string.socialb_list_full, MAX_HIDDEN_WORDS), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(words, key = { it }) { word ->
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { model.hidden.changeWords(model.hidden.words - word) }) {
                        Icon(Icons.Filled.Close, contentDescription = t(R.string.socialb_remove_item, word))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
