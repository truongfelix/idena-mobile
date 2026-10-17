package io.github.truongfelix.idenanode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Muting an account (⋮ on its profile, ⋯ on its posts) and the Social setting "Pictures in posts": what leaves the
 * feeds only ([Hiding.leavesFeeds]). Decided with the user (2026-10-17, B-56): a mute keeps the muted account's likes,
 * replies and tips on your posts notifying you and their messages arriving, and their profile still lists their posts.
 */

/** The accounts muted, each with Unmute; a tap on one opens its profile. */
@Composable
fun MutedView(model: SocialModel, modifier: Modifier = Modifier) {
    val muted = sortedPeople(model.people).filter { it.muted }
    val names = model.names
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Muted accounts", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "Their posts leave Home, Following and the topics, and their replies show folded in threads, on this phone " +
                    "only. Their likes, replies and tips on your posts still notify you, their messages still arrive, and " +
                    "their profile still lists their posts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (muted.isEmpty()) item { Text("Nobody is muted. To mute an account: ⋮ on its profile, or ⋯ on its posts.", style = MaterialTheme.typography.bodyMedium) }
        items(muted, key = { it.address }) { person ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName(person.address, names),
                    Modifier.weight(1f).clickable { model.openProfile(person.address) }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = { model.setPerson(person.copy(muted = false)) }) { Text("Unmute") }
            }
        }
    }
}

/** "Pictures in posts": Show, Tap to show, or Hide posts with pictures ([PicturesMode]); one's own posts never change. */
@Composable
fun PicturesSetting(mode: PicturesMode, onChange: (PicturesMode) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Pictures in posts", style = MaterialTheme.typography.bodyLarge)
        PicturesMode.entries.forEach { choice ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == choice, role = Role.RadioButton) { onChange(choice) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == choice, onClick = null)
                Text(choice.title, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            when (mode) {
                PicturesMode.Show -> "Pictures show in the posts."
                PicturesMode.TapToShow -> "A post's picture is one line until you tap it in its thread: nothing is downloaded before."
                PicturesMode.Hide -> "Posts with a picture leave Home, Following and the topics; a reply with one shows folded in its thread."
            } + " Your own posts never change.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}
