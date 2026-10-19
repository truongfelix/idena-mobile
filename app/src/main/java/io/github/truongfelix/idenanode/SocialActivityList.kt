package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityList(model: SocialModel, modifier: Modifier, onOpen: (ActivityItem) -> Unit) {
    val items = model.activity
    val newAfter = model.highlightAfter
    LazyColumn(modifier, state = model.activityState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // What to be notified of; the bell says what the chips are for.
            var about by remember { mutableStateOf(false) }
            // Each chip on one line: one that does not fit goes to the next line (French "Pourboires", owner 2026-10-19).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                NotifyKind.entries.forEach { kind ->
                    FilterChip(
                        selected = kind in model.notify,
                        onClick = { model.setNotifyOn(kind, kind !in model.notify) },
                        label = { Text(kind.title, maxLines = 1, softWrap = false) },
                    )
                }
                IconButton(onClick = { about = true }) {
                    Icon(Icons.Filled.Notifications, contentDescription = t(R.string.socialb_notifications), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (about) {
                AlertDialog(
                    onDismissRequest = { about = false },
                    title = { Text(t(R.string.socialb_notifications)) },
                    text = { Text(t(R.string.socialb_notifications_note)) },
                    confirmButton = { TextButton(onClick = { about = false }) { Text(t(R.string.common_close)) } },
                )
            }
        }
        // The vouch offers made to the account wait here for its answer (B-81).
        items(model.offersToMe, key = { "offer:${it.address}" }) { offer -> OfferCard(offer.address, onProfile = model::openProfile) }
        if (items.isEmpty()) item { Text(t(R.string.socialb_no_activity), style = MaterialTheme.typography.bodyMedium) }
        items(items, key = { "${it.kind}:${it.height}:${it.index}:${it.what.hash}" }) { item ->
            ActivityCard(item, isNew = newAfter != null && item.height > newAfter, onProfile = model::openProfile) { onOpen(item) }
        }
    }
}

/** One entry of the Activity list, marked when [isNew]; a tap opens its thread, a tap on the address its profile. */
@Composable
private fun ActivityCard(item: ActivityItem, isNew: Boolean, onProfile: (String) -> Unit, onOpen: () -> Unit) {
    // The line with a mark where the actor's address goes (a link), so that each language orders it as it needs.
    val line = when (item.kind) {
        ActivityKind.Like -> t(R.string.socialb_activity_liked, ADDRESS_MARK, ago(item.time))
        ActivityKind.Reply -> t(R.string.socialb_activity_replied, ADDRESS_MARK, ago(item.time))
        ActivityKind.Comment -> t(R.string.socialb_activity_commented, ADDRESS_MARK, ago(item.time))
        ActivityKind.Tip -> t(R.string.socialb_activity_tipped, ADDRESS_MARK, ago(item.time), "${item.amount?.let(::tipText)}")
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val styles = addressLinkStyles()
            if (isNew) Text(t(R.string.socialb_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(item.actor, 32.dp, Modifier.clickable { onProfile(item.actor) })
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        append(line.substringBefore(ADDRESS_MARK))
                        appendAddress(item.actor, styles, onProfile)
                        append(line.substringAfter(ADDRESS_MARK, ""))
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

/** Where an address (a link) goes in a translated line: the text is split around it. */
const val ADDRESS_MARK = "\u0000"

/** A vouch offer to the account: who offers, what it means, Accept and Decline (each with its fee shown first). */
@Composable
private fun OfferCard(voucher: String, onProfile: (String) -> Unit) {
    val name = displayName(voucher, LocalNames.current)
    val access = LocalProfileAccess.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.clickable { onProfile(voucher) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonAvatar(voucher, 32.dp)
                Text(t(R.string.socialb_offer_vouch, name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Text(
                t(R.string.socialb_offer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (access != null) OfferAnswerButtons(voucher, name, access.canAct, access.onCall)
        }
    }
}
