package io.github.truongfelix.idenanode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The screens of a group with a shared key: making one or changing its name and members (its admin), what it shows
 * of its members, and its key messages and leaves as lines of the conversation.
 */

/** Material's "group" icon (two people), not in the core icons. */
val GroupIcon: ImageVector by lazy {
    ImageVector.Builder("Filled.Group", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5" +
                "C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5L1,19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13" +
                "c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45L17,19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** A group's picture: the group icon on a round background. */
@Composable
fun GroupAvatar(size: Dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(GroupIcon, contentDescription = null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** A group's title: the name given on this phone ([local]), else its admin's, else its members' names. */
fun groupTitle(group: GroupState, me: String, names: Map<String, String>, local: String? = null): String =
    local ?: group.key.name.ifBlank { group.members.filter { it != me }.joinToString(", ") { displayName(it, names) } }

/**
 * A group's key message as a line of its conversation: who made the group, who was added or removed, a new name or a
 * new key alone, against [previous], the key message before it; or its admin's removal notice.
 */
fun groupEventText(line: ChatMessage, previous: ChatMessage?, me: String, names: Map<String, String>): String {
    val who = { address: String -> if (address == me) t(R.string.group_you) else displayName(address, names) }
    if (line.kind == ChatMessage.REMOVED) return t(R.string.group_removed_you, who(line.from))
    // The first key message [me] holds: the group made, or [me] added later (the older keys never reach a new member).
    if (previous == null) return if (line.from == me) t(R.string.group_created, line.text) else t(R.string.group_added_you, who(line.from), line.text)
    val parts = ArrayList<String>()
    val added = line.participants.filter { it !in previous.participants }
    val removed = previous.participants.filter { it !in line.participants }
    if (added.isNotEmpty()) parts += t(R.string.group_added, who(line.from), added.joinToString(", ") { who(it) })
    if (removed.isNotEmpty()) parts += t(R.string.group_removed, who(line.from), removed.joinToString(", ") { who(it) })
    if (line.text != previous.text) parts += t(R.string.group_renamed, who(line.from), line.text)
    return parts.ifEmpty { listOf(t(R.string.group_new_key, who(line.from))) }.joinToString("\n")
}

/** The key message before [line] in [conversation], for [groupEventText]. */
fun previousKeyLine(conversation: Conversation, line: ChatMessage): ChatMessage? = conversation.previousKeys[line.hash]

/** A key message, centred between the messages. */
@Composable
fun GroupEventLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * A group's members ([group]: its admin first), with the names shown ([names]); a tap on the title or a member names
 * it on this phone only, starting from the name given here before ([localTitle], [localNames]), never from the shown
 * one: saved unchanged, the admin's name and the members' own keep following their changes. Its admin changes the
 * group; anyone hides it on this phone ([onHide]).
 */
@Composable
fun GroupInfoDialog(
    title: String,
    group: GroupState,
    me: String,
    names: Map<String, String>,
    localTitle: String?,
    localNames: Map<String, String>,
    onRenameGroup: (String) -> Unit,
    onRenameMember: (String, String) -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The group ("") or the member named on this phone.
    var renaming by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, Modifier.clickable { renaming = "" }) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text(t(R.string.group_privacy_note), style = MaterialTheme.typography.bodySmall) }
                // One action a line: side by side, the longer texts (French) were cut.
                item {
                    Column {
                        TextButton(onClick = { renaming = "" }, contentPadding = PaddingValues(0.dp)) { Text(t(R.string.group_rename_here)) }
                        TextButton(onClick = { onDismiss(); onHide() }, contentPadding = PaddingValues(0.dp)) { Text(t(R.string.chat_hide)) }
                    }
                }
                items(group.key.members) { address ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = address != me) { renaming = address }, verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(address, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (address == me) t(R.string.group_you) else displayName(address, names),
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (address == group.key.admin) {
                            Text(t(R.string.group_admin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (group.key.admin == me) TextButton(onClick = { onDismiss(); onEdit() }) { Text(t(R.string.group_change)) }
            else TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) }
        },
        dismissButton = { if (group.key.admin == me) TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) } },
    )
    renaming?.let { who ->
        NameHereDialog(
            initial = if (who.isEmpty()) localTitle.orEmpty() else localNames[who].orEmpty(),
            shown = if (who.isEmpty()) title else displayName(who, names),
            note = t(if (who.isEmpty()) R.string.group_rename_note else R.string.group_rename_member_note),
            onSave = { if (who.isEmpty()) onRenameGroup(it) else onRenameMember(who, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

/**
 * A name given on this phone: [initial] (the one given before, or empty), [shown] the name shown meanwhile, [note]
 * telling who sees it; empty takes the name back.
 */
@Composable
private fun NameHereDialog(initial: String, shown: String, note: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.group_rename_here)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(note, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_GROUP_NAME) },
                    singleLine = true,
                    placeholder = { Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(t(R.string.group_save_name)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}

/**
 * A new group ([key] null), or the name and members of the group of [key], for its admin: a name, the people chosen
 * (the People list, those already written with, the accounts known by name, an address or a claimed name typed), at
 * most [MAX_GROUP_MEMBERS]; sent once each one's public key is known, with its fee shown first. With nothing changed,
 * it hands out a new key alone.
 */
@Composable
fun GroupEditView(model: SocialModel, key: String?, canSend: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val me = model.myAddress.orEmpty()
    val group = key?.let { k -> model.conversations.firstOrNull { it.key == k }?.group }
    val before = group?.members?.filter { it != me }.orEmpty()
    var name by rememberSaveable { mutableStateOf(group?.key?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    // Kept when the phone turns or the app is recreated: saving with another choice would send the wrong members.
    val chosen = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) { before.toMutableStateList() }
    LaunchedEffect(chosen.toList()) { model.lookUpKeys(chosen.toList()) }
    val noKey = chosen.filter { model.messageKeys[it] == "" }
    val looking = chosen.any { it !in model.messageKeys }
    // A name typed is also looked up in the profiles contract, and a message code pasted gives its account (its key
    // kept): anyone can be added, followed or not.
    val coded = rememberMessageCodeAddress(model, query)
    val holder = rememberNameHolder(query)
    val candidates = chosen.toList() + chatCandidates(query, model.people, model.conversations, me, model.names, coded ?: holder).filter { it !in chosen }
    val changed = group == null || name.trim() != group.key.name || chosen.toSet() != before.toSet()
    // Nothing changed in a group: the button hands out a new key alone.
    val ready = canSend && name.isNotBlank() && chosen.isNotEmpty() && chosen.size <= MAX_GROUP_MEMBERS && noKey.isEmpty() && !looking
    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t(R.string.common_back)) }
            Text(t(if (group == null) R.string.group_new else R.string.group_change), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_GROUP_NAME) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t(R.string.group_name)) },
                singleLine = true,
            )
            Text(
                t(if (group == null) R.string.group_new_note else R.string.group_change_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RoundSearchField(query, { query = it }, t(R.string.socialb_search_name_or_address))
            Text(t(R.string.group_chosen, chosen.size, MAX_GROUP_MEMBERS), style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(candidates, key = { it }) { address ->
                    val checked = address in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { if (checked) chosen.remove(address) else chosen.add(address) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonAvatar(address, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            model.names[address]?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Text(shortAddress(address), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Checkbox(checked = checked, onCheckedChange = { if (it) chosen.add(address) else chosen.remove(address) })
                    }
                }
            }
            when {
                noKey.isNotEmpty() -> Text(noKeyText(noKey, model.names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                looking -> Text(t(R.string.socialb_looking_up_keys), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                chosen.size > MAX_GROUP_MEMBERS -> Text(t(R.string.group_error_size, MAX_GROUP_MEMBERS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { model.saveGroup(group?.key, name, chosen.toList()) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { Text(t(if (group == null) R.string.group_create else if (changed) R.string.group_save else R.string.group_renew)) }
        }
    }
}
