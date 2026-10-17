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
        item { Text(t(R.string.socialb_muted_accounts), style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                t(R.string.socialb_muted_accounts_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (muted.isEmpty()) item { Text(t(R.string.socialb_nobody_muted), style = MaterialTheme.typography.bodyMedium) }
        items(muted, key = { it.address }) { person ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName(person.address, names),
                    Modifier.weight(1f).clickable { model.openProfile(person.address) }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = { model.setPerson(person.copy(muted = false)) }) { Text(t(R.string.socialb_unmute)) }
            }
        }
    }
}

/** "Pictures in posts": Show, Tap to show, or Hide posts with pictures ([PicturesMode]); one's own posts never change. */
@Composable
fun PicturesSetting(mode: PicturesMode, onChange: (PicturesMode) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t(R.string.socialb_pictures_in_posts), style = MaterialTheme.typography.bodyLarge)
        PicturesMode.entries.forEach { choice ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = mode == choice, role = Role.RadioButton) { onChange(choice) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == choice, onClick = null)
                Text(t(choice.title), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            when (mode) {
                PicturesMode.Show -> t(R.string.socialb_pictures_show_note)
                PicturesMode.TapToShow -> t(R.string.socialb_pictures_tap_note)
                PicturesMode.Hide -> t(R.string.socialb_pictures_hide_note)
            },
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}
