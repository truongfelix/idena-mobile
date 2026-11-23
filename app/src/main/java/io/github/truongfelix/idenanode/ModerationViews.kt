package io.github.truongfelix.idenanode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * What the Social tab's views need of Community moderation (B-308): whether it is on, the account, the decisions and
 * the epoch, the placeholders of the feed shown, the account's moderator role, and how an action is sent (its fee
 * shown first: [SocialSending.prepareModeration]) or known to wait for its block. Null outside the Social tab.
 */
class ModerationAccess(
    val on: Boolean,
    val me: String?,
    val state: ModerationState,
    val epoch: Long?,
    val placeholders: Map<String, String>,
    val role: ModeratorRole?,
    val canAct: Boolean,
    val stakeOf: (String) -> Double?,
    val onAction: (what: String, detail: String?, call: SocialCall) -> Unit,
    val waiting: (SocialCall) -> Boolean,
    val openPage: () -> Unit,
) {
    fun isPlaceholder(post: SocialPost) = post.call.hash.lowercase() in placeholders
    fun reasonOf(post: SocialPost) = placeholders[post.call.hash.lowercase()].orEmpty()
    fun ban(address: String?) = if (on) state.activeBan(address, epoch) else null

    /** The role of [address] itself (a staker or a vouched moderator cannot be banned), from its known stake. */
    fun targetRole(address: String): ModeratorRole? = when {
        (stakeOf(address) ?: 0.0) >= MODERATOR_STAKE -> ModeratorRole.Staker
        state.vouchersOf(address).isNotEmpty() -> ModeratorRole.Vouched
        else -> null
    }
}

val LocalModeration = compositionLocalOf<ModerationAccess?> { null }

/** The text of a [ModerationRefusal]. */
fun refusalText(refusal: ModerationRefusal): String = t(
    when (refusal) {
        ModerationRefusal.NoAccount -> R.string.mod_refused_no_account
        ModerationRefusal.Banned -> R.string.mod_refused_banned
        ModerationRefusal.Self -> R.string.mod_refused_self
        ModerationRefusal.AlreadyBanned -> R.string.mod_refused_already_banned
        ModerationRefusal.NoReason -> R.string.mod_refused_no_reason
        ModerationRefusal.TooLong -> R.string.mod_refused_too_long
        ModerationRefusal.NotModerator -> R.string.mod_refused_not_moderator
        ModerationRefusal.NotStaker -> R.string.mod_refused_not_staker
        ModerationRefusal.TargetStaker -> R.string.mod_refused_target_staker
        ModerationRefusal.TargetVouched -> R.string.mod_refused_target_vouched
        ModerationRefusal.Epochs -> R.string.mod_refused_epochs
        ModerationRefusal.Batch -> R.string.mod_refused_batch
        ModerationRefusal.VouchLimit -> R.string.mod_refused_vouch_limit
    },
)

/** A post hidden by the moderators, where answers under it are shown: one line, no author, text or actions. */
@Composable
fun ModeratedLine(reason: String, modifier: Modifier = Modifier) {
    Text(
        if (reason.isEmpty()) t(R.string.mod_post_hidden) else t(R.string.mod_post_hidden_reason, reason),
        modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Banned", after a banned account's identity, while Community moderation is on; nothing otherwise. */
@Composable
fun BannedLabel(address: String) {
    if (LocalModeration.current?.ban(address) == null) return
    Text(
        "  ·  " + t(R.string.mod_banned_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
    )
}

/** The banner of a banned account's profile: until when, why, and the moderator to write to (a tap opens them). */
@Composable
fun BanBanner(address: String, onProfile: (String) -> Unit) {
    val ban = LocalModeration.current?.ban(address) ?: return
    val until = if (ban.permanent) t(R.string.mod_banned_permanently) else t(R.string.mod_banned_until, ban.endEpoch)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (ban.reason.isEmpty()) until else "$until · ${ban.reason}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            t(R.string.mod_banned_by, displayName(ban.moderator, LocalNames.current)),
            Modifier.clickable { onProfile(ban.moderator) },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The reasons offered first, as the website's: written on chain in English, shown translated; then Other. */
private val REASONS = listOf(
    "Spam" to R.string.mod_reason_spam,
    "Scam or phishing" to R.string.mod_reason_scam,
    "Illegal" to R.string.mod_reason_illegal,
    "Harassment" to R.string.mod_reason_harassment,
)

/** A reason: a chip (or Other) and its text, with its byte count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasonField(reason: String, onReason: (String) -> Unit, required: Boolean) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    val bytes = reasonBytes(reason)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (REASONS + ("" to R.string.mod_reason_other)).forEach { (value, label) ->
                FilterChip(selected = chosen == label, onClick = {
                    chosen = label
                    onReason(value)
                }, label = { Text(t(label)) })
            }
        }
        OutlinedTextField(
            reason, onReason, Modifier.fillMaxWidth(),
            placeholder = { Text(t(if (required) R.string.mod_reason_required else R.string.mod_reason_optional)) },
            minLines = 2,
        )
        Text(
            t(R.string.mod_reason_bytes, bytes, MAX_REASON_BYTES),
            style = MaterialTheme.typography.bodySmall,
            color = if (bytes > MAX_REASON_BYTES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A dialog Community moderation opens: a report, or a moderator's action. */
sealed interface ModerationDialog {
    /** Reports a post ([hash], by [address]) or, without [hash], the account [address]. */
    data class Report(val address: String, val hash: String? = null) : ModerationDialog

    /** A moderator's action ([action]: ban, unban, hidePost, restorePost, hidePosts, vouch, withdrawVouch, revokeVouches). */
    data class Act(
        val action: String,
        val address: String? = null,
        val hash: String? = null,
        val hashes: List<String> = emptyList(),
        val onSent: () -> Unit = {},
    ) : ModerationDialog
}

/** Shows [dialog]; [onDismiss] closes it. */
@Composable
fun ModerationDialogView(dialog: ModerationDialog?, onDismiss: () -> Unit) {
    when (dialog) {
        null -> {}
        is ModerationDialog.Report -> ReportDialog(dialog, onDismiss)
        is ModerationDialog.Act -> ModeratorDialog(dialog, onDismiss)
    }
}

/** Dialog actions one per line: two side by side are cut in French (L-65). */
@Composable
private fun DialogActions(onDismiss: () -> Unit, label: String, enabled: Boolean, onConfirm: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        TextButton(onClick = onConfirm, enabled = enabled) { Text(label) }
        TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) }
    }
}

@Composable
private fun ReportDialog(dialog: ModerationDialog.Report, onDismiss: () -> Unit) {
    val access = LocalModeration.current ?: return
    val name = displayName(dialog.address, LocalNames.current)
    var reason by remember { mutableStateOf("") }
    val target = dialog.hash ?: dialog.address
    val previous = access.state.ownReport(access.me, target)
    val refusal = access.state.reportRefusal(access.epoch, access.me, dialog.address.takeIf { dialog.hash == null }, reason)
    val call = if (dialog.hash != null) ModerationCalls.reportPost(dialog.hash, reason.trim()) else ModerationCalls.report(dialog.address, reason.trim())
    val waiting = access.waiting(call)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dialog.hash != null) t(R.string.mod_report_post_title, name) else t(R.string.mod_report_account_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previous != null) {
                    Text(t(R.string.mod_reported_before, DateFormat.getDateInstance().format(Date(previous.time * 1000)), previous.reason))
                } else {
                    ReasonField(reason, { reason = it }, required = true)
                    Text(t(R.string.mod_report_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (refusal != null && refusal != ModerationRefusal.NoReason)
                        Text(refusalText(refusal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (waiting) Text(t(R.string.mod_waits_block), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (previous == null) {
                DialogActions(onDismiss, t(R.string.mod_report), refusal == null && !waiting && access.canAct) {
                    onDismiss()
                    access.onAction(t(R.string.mod_report_what), reason.trim(), call)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(t(R.string.common_close)) }
            }
        },
    )
}

/** The ban lengths offered: a number of epochs, 0 for a permanent ban. */
private val BAN_EPOCHS = listOf(1, 4, 12, 0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeratorDialog(dialog: ModerationDialog.Act, onDismiss: () -> Unit) {
    val access = LocalModeration.current ?: return
    val names = LocalNames.current
    val name = dialog.address?.let { displayName(it, names) }.orEmpty()
    var reason by remember { mutableStateOf("") }
    // The ban's length as typed: an empty or broken entry is refused, never read as 0 (a permanent ban).
    var epochsText by remember { mutableStateOf("1") }
    val epochs = epochsText.takeIf { Regex("^[0-9]{1,5}$").matches(it) }?.toInt() ?: -1
    val action = dialog.action
    val withReason = action in setOf("ban", "unban", "hidePost", "restorePost", "hidePosts")
    var refusal = access.state.moderatorRefusal(
        access.epoch, access.me, access.role, dialog.address,
        if (action == "ban" && dialog.address != null) access.targetRole(dialog.address) else null,
        if (action == "ban") epochs else 0, reason, if (action == "hidePosts") dialog.hashes.size else 1,
    )
    if (action == "withdrawVouch") refusal = if (access.me == null) ModerationRefusal.NoAccount else null
    else if (refusal == null && action in setOf("vouch", "revokeVouches") && access.role != ModeratorRole.Staker) refusal = ModerationRefusal.NotStaker
    else if (refusal == null && action == "vouch") {
        if (access.ban(dialog.address) != null) refusal = ModerationRefusal.AlreadyBanned
        else if (access.state.vouchesGiven(access.me).size >= MAX_VOUCHES) refusal = ModerationRefusal.VouchLimit
    }
    val address = dialog.address.orEmpty()
    val hash = dialog.hash.orEmpty()
    val call = when (action) {
        "ban" -> ModerationCalls.ban(address, epochs.coerceAtLeast(0), reason.trim())
        "unban" -> ModerationCalls.unban(address, reason.trim())
        "hidePost" -> ModerationCalls.hidePost(hash, reason.trim())
        "restorePost" -> ModerationCalls.restorePost(hash, reason.trim())
        "hidePosts" -> ModerationCalls.hidePosts(dialog.hashes, reason.trim())
        "vouch" -> ModerationCalls.vouch(address)
        "withdrawVouch" -> ModerationCalls.withdrawVouch(address)
        else -> ModerationCalls.revokeVouches(address)
    }
    val title = when (action) {
        "ban" -> t(R.string.mod_ban_title, name)
        "unban" -> t(R.string.mod_unban_title, name)
        "hidePost" -> t(R.string.mod_hide_title)
        "restorePost" -> t(R.string.mod_restore_title)
        "hidePosts" -> tp(R.plurals.mod_hide_many_title, dialog.hashes.size, dialog.hashes.size)
        "vouch" -> t(R.string.mod_vouch_title, name)
        "withdrawVouch" -> t(R.string.mod_withdraw_title, name)
        else -> t(R.string.mod_revoke_title, name)
    }
    val note = when (action) {
        "ban" -> t(R.string.mod_ban_note)
        "hidePost" -> t(R.string.mod_hide_note)
        "hidePosts" -> t(R.string.mod_hide_many_note)
        "vouch" -> t(R.string.mod_vouch_note)
        "revokeVouches" -> t(R.string.mod_revoke_note)
        else -> null
    }
    val waiting = access.waiting(call)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it) }
                if (action == "ban") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BAN_EPOCHS.forEach { value ->
                            FilterChip(selected = epochs == value, onClick = { epochsText = value.toString() }, label = {
                                Text(if (value == 0) t(R.string.mod_permanently) else tp(R.plurals.mod_epochs, value, value))
                            })
                        }
                    }
                    OutlinedTextField(
                        epochsText, { epochsText = it.take(6) }, Modifier.fillMaxWidth(),
                        label = { Text(t(R.string.mod_ban_epochs_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (epochs >= 0) Text(
                        if (epochs == 0) t(R.string.mod_until_unbanned) else tp(R.plurals.mod_ends_after, epochs, epochs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (withReason) ReasonField(reason, { reason = it }, required = false)
                Text(t(R.string.mod_transaction_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                refusal?.let { Text(refusalText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (waiting) Text(t(R.string.mod_waits_block), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            DialogActions(onDismiss, t(R.string.mod_continue), refusal == null && !waiting && access.canAct) {
                onDismiss()
                access.onAction(title, reason.trim().ifEmpty { null }, call)
                dialog.onSent()
            }
        },
    )
}

/**
 * Community moderation's items of a ⋯ menu for [address] (and a post's [hash]), while it is on and the account not
 * banned: for a moderator, Hide this post and Ban (or Unban); for everyone, Report post and Report {name}, while the
 * author is not banned already. [open] opens a dialog (the menu closes).
 */
@Composable
fun ModerationMenuItems(address: String, hash: String?, open: (ModerationDialog) -> Unit) {
    val access = LocalModeration.current ?: return
    if (!access.on || access.me == null || access.ban(access.me) != null) return
    val name = displayName(address, LocalNames.current)
    val authorBanned = access.ban(address) != null
    if (access.role != null) {
        HorizontalDivider()
        if (hash != null) DropdownMenuItem(text = { Text(t(R.string.mod_hide_post_item)) }, onClick = { open(ModerationDialog.Act("hidePost", hash = hash)) })
        DropdownMenuItem(
            text = { Text(if (authorBanned) t(R.string.mod_unban_item, name) else t(R.string.mod_ban_item, name), maxLines = 1) },
            onClick = { open(ModerationDialog.Act(if (authorBanned) "unban" else "ban", address = address)) },
        )
    }
    if (!authorBanned) {
        HorizontalDivider()
        if (hash != null) DropdownMenuItem(text = { Text(t(R.string.mod_report_post_item)) }, onClick = { open(ModerationDialog.Report(address, hash)) })
        DropdownMenuItem(text = { Text(t(R.string.mod_report_account_item, name), maxLines = 1) }, onClick = { open(ModerationDialog.Report(address)) })
    }
}

/**
 * Moderate, on another account's profile, for a moderator: whether it is a staker or a vouched moderator (and who
 * vouches), the reports on it; Ban or Unban, and for a staker Vouch, Withdraw the vouch and Revoke.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeratePanel(address: String) {
    val access = LocalModeration.current ?: return
    if (access.role == null || address == access.me) return
    var dialog by remember { mutableStateOf<ModerationDialog?>(null) }
    val names = LocalNames.current
    val banned = access.ban(address) != null
    val vouchers = access.state.vouchersOf(address)
    val target = access.targetRole(address)
    val reporters = access.state.reports
        .filter { it.kind == "address" && it.target == address && access.ban(it.reporter) == null }
        .map { it.reporter }.toSet()
    val iVouch = access.me in vouchers
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(t(R.string.mod_moderate), style = MaterialTheme.typography.titleSmall)
        if (target == ModeratorRole.Staker) Text(t(R.string.mod_is_staker), style = MaterialTheme.typography.bodySmall)
        if (vouchers.isNotEmpty())
            Text(t(R.string.mod_vouched_by, vouchers.joinToString(", ") { displayName(it, names) }), style = MaterialTheme.typography.bodySmall)
        if (reporters.isNotEmpty()) Text(tp(R.plurals.mod_reported_by, reporters.size, reporters.size), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (banned) OutlinedButton(onClick = { dialog = ModerationDialog.Act("unban", address = address) }) { Text(t(R.string.mod_unban_button)) }
            else OutlinedButton(onClick = { dialog = ModerationDialog.Act("ban", address = address) }, enabled = target == null) { Text(t(R.string.mod_ban_button)) }
            if (access.role == ModeratorRole.Staker && !iVouch && !banned && target != ModeratorRole.Staker)
                OutlinedButton(onClick = { dialog = ModerationDialog.Act("vouch", address = address) }) { Text(t(R.string.mod_vouch_button)) }
            if (iVouch) OutlinedButton(onClick = { dialog = ModerationDialog.Act("withdrawVouch", address = address) }) { Text(t(R.string.mod_withdraw_button)) }
            if (access.role == ModeratorRole.Staker && vouchers.isNotEmpty())
                OutlinedButton(onClick = { dialog = ModerationDialog.Act("revokeVouches", address = address) }) { Text(t(R.string.mod_revoke_button)) }
        }
        if (target != null && !banned)
            Text(refusalText(if (target == ModeratorRole.Staker) ModerationRefusal.TargetStaker else ModerationRefusal.TargetVouched),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ModerationDialogView(dialog) { dialog = null }
}

/**
 * The Social setting "Community moderation", off unless turned on: with it on, the app follows the community
 * moderators' decisions recorded by the idena.social moderation contract, as the website does.
 */
@Composable
fun CommunityModerationSetting(moderation: SocialModeration, isModerator: Boolean, openPage: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t(R.string.mod_setting_title), style = MaterialTheme.typography.bodyLarge)
        listOf(false to R.string.mod_setting_off, true to R.string.mod_setting_on).forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = moderation.on == value, role = Role.RadioButton) { moderation.change(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = moderation.on == value, onClick = null)
                Text(t(label), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(t(R.string.mod_setting_note_who), style = MaterialTheme.typography.bodySmall, color = muted)
        Text(t(R.string.mod_setting_note_what), style = MaterialTheme.typography.bodySmall, color = muted)
        if (moderation.on) {
            moderation.error?.let { Text(t(R.string.mod_setting_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                ?: moderation.high?.let { Text(t(R.string.mod_setting_read_through, "%,d".format(it)), style = MaterialTheme.typography.bodySmall, color = muted) }
            if (isModerator) OutlinedButton(onClick = openPage, Modifier.padding(top = 4.dp)) { Text(t(R.string.mod_open_page)) }
        }
    }
}
