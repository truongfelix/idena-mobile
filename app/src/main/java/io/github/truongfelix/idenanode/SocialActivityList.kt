package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Notifications

/**
 * The Activity: what others did on the identity's posts, newest first, the new ones marked; above it,
 * which kinds notify (a count on the Social tab and a notice while the app is open).
 */
@Composable
fun ActivityList(model: SocialModel, modifier: Modifier, onOpen: (ActivityItem) -> Unit) {
    val items = model.activity
    val newAfter = model.highlightAfter
    LazyColumn(modifier, state = model.activityState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // What to be notified of; the bell says what the chips are for.
            var about by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NotifyKind.entries.forEach { kind ->
                    FilterChip(selected = kind in model.notify, onClick = { model.setNotifyOn(kind, kind !in model.notify) }, label = { Text(kind.title) })
                }
                IconButton(onClick = { about = true }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (about) {
                AlertDialog(
                    onDismissRequest = { about = false },
                    title = { Text("Notifications") },
                    text = { Text("A notice comes for each new like, comment or tip on your posts of the kinds selected. All of them are listed here.") },
                    confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
                )
            }
        }
        if (items.isEmpty()) item { Text("No likes, answers or tips on your posts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(items, key = { "${it.kind}:${it.height}:${it.index}:${it.what.hash}" }) { item ->
            ActivityCard(item, isNew = newAfter != null && item.height > newAfter, onProfile = model::openProfile) { onOpen(item) }
        }
    }
}

/** One entry of the Activity list, marked when [isNew]; a tap opens its thread, a tap on the address its profile. */
@Composable
private fun ActivityCard(item: ActivityItem, isNew: Boolean, onProfile: (String) -> Unit, onOpen: () -> Unit) {
    val action = when (item.kind) {
        ActivityKind.Like -> "liked"
        ActivityKind.Reply -> "replied to"
        ActivityKind.Comment -> "commented on"
        ActivityKind.Tip -> "tipped you ${item.amount?.let(::tipText)} iDNA for"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val styles = addressLinkStyles()
            if (isNew) Text("● New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.actor, 32.dp, Modifier.clickable { onProfile(item.actor) })
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        appendAddress(item.actor, styles, onProfile)
                        append(" $action your post  ·  ${ago(item.time)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PostContent(item.what, MaterialTheme.typography.bodyMedium, maxLines = 2, showMedia = false)
            item.answer?.let {
                HorizontalDivider()
                PostContent(it, MaterialTheme.typography.bodyLarge, maxLines = 4, showMedia = false)
            }
        }
    }
}
