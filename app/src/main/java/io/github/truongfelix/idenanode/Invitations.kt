package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject

/*
 * Invitations, with the node's rules (blockchain/validation/validation.go, blockchain.go):
 * - sending (dna_sendInvite, InviteTx, no fee): a Verified or Human identity with invites left, before the
 *   flip lottery, to an address without an identity. Without an address the node makes a new key and
 *   returns it: its private key is the invitation code, which the node does not keep.
 * - activating a code (dna_activateInvite with the key, ActivationTx, no fee): the code's address is
 *   Invite; the node's address, without an identity, becomes a Candidate and the code's address Killed.
 * - cancelling (KillInviteeTx, a fee): the inviter takes back an invitee still Invite or Candidate, before
 *   the flip lottery. The invite is used up either way.
 * - spoiling a public code (dna_activateInviteToRandAddr, no fee): the code is activated to a new random
 *   address, so that nobody else can use it.
 * The inviter's invitees (dna_identity) list an invitee only once activated, with the invite transaction
 * and the activated address; before that the invitation is followed through its receiver's state.
 */

/**
 * An invitation this phone sent in [epoch]: to [receiver], a new address whose private key is [code], or
 * an address given by the user ([code] null). [name] is a private label, never published. [inviter] is the
 * identity that sent it (lowercase), null for one saved by an older app version.
 */
data class SentInvite(
    val name: String,
    val receiver: String,
    val code: String?,
    val txHash: String,
    val epoch: Int,
    val sentAt: Long,
    val inviter: String? = null,
) {
    /** Whether [address] sent it; one saved without its inviter counts for any address. */
    fun sentBy(address: String): Boolean = inviter == null || inviter == address.lowercase()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("receiver", receiver).put("code", code ?: "").put("txHash", txHash)
        .put("epoch", epoch).put("sentAt", sentAt).put("inviter", inviter ?: "")

    companion object {
        fun fromJson(json: JSONObject): SentInvite? = runCatching {
            SentInvite(
                cleanName(json.optString("name")),
                json.getString("receiver").lowercase(),
                json.optString("code").takeIf { it.isNotEmpty() },
                json.getString("txHash").lowercase(),
                json.getInt("epoch"),
                json.getLong("sentAt"),
                json.optString("inviter").lowercase().takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }
}

/** The invitations of [json] (files/invites.json) sent in [epoch]: older ones are over, their codes useless. */
fun parseSentInvites(json: String, epoch: Int): List<SentInvite> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(SentInvite::fromJson) }.filter { it.epoch == epoch }
}.getOrDefault(emptyList())

fun sentInvitesJson(invites: List<SentInvite>): String = JSONArray().apply { invites.forEach { put(it.toJson()) } }.toString()

/** An invitee of the identity (dna_identity invitees): the invite transaction and the activated address. */
data class Invitee(val txHash: String, val address: String)

/**
 * An invitation code as the node takes it (the invitation's private key, 64 hex digits), from what the
 * user pasted (spaces and a "0x" allowed); null when it is not one.
 */
fun parseInvitationCode(text: String): String? {
    val hex = text.filter { !it.isWhitespace() }.removePrefix("0x").lowercase()
    if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' } || hex.all { it == '0' }) return null
    return hex
}

/** Whether the node would send an invitation now: invites left, outside the validation. */
fun canSendInvite(invites: Int, period: String): Boolean = invites > 0 && period == "None"

/** Whether the node would activate a code for an identity in [state] now: no identity yet, outside the validation. */
fun canActivateCode(state: String, period: String): Boolean = state == "Undefined" && period == "None"

/**
 * Whether the Node tab shows the Invitations card: for identities that receive invitations (Verified,
 * Human, and those that may become so), or with invites or invitees.
 */
fun showInvitations(status: NodeStatus): Boolean =
    status.invites > 0 || status.invitees.isNotEmpty() || status.identityState in validatedStates + setOf("Suspended", "Zombie")

/** Whether the inviter may cancel an invitee in [state] now. */
fun canCancelInvite(state: String?, period: String): Boolean = state in setOf("Invite", "Candidate") && period == "None"

/**
 * An invitation as the list shows it: [address] the one to follow and cancel (the receiver until it is
 * activated, then the activated address), [state] its identity state, [pending] while its transaction
 * is not in a block. [sent] is null for an invitation sent from another app.
 */
data class InviteRow(val sent: SentInvite?, val address: String, val state: String?, val pending: Boolean)

/**
 * The invitations list: the ones this phone sent this epoch, newest first, then the invitees sent from
 * another app. [states] gives the identity state per address (lowercase), [mined] the transactions in a
 * block.
 */
fun inviteRows(sent: List<SentInvite>, invitees: List<Invitee>, states: Map<String, String>, mined: Set<String>): List<InviteRow> {
    val byTx = invitees.associateBy { it.txHash.lowercase() }
    val own = sent.sortedByDescending { it.sentAt }.map { invite ->
        val activated = byTx[invite.txHash]?.address?.lowercase()
        val address = activated ?: invite.receiver
        InviteRow(invite, address, states[address], pending = activated == null && invite.txHash !in mined)
    }
    val ownTx = sent.map { it.txHash }.toSet()
    val others = invitees.filter { it.txHash.lowercase() !in ownTx }.map {
        val address = it.address.lowercase()
        InviteRow(null, address, states[address], pending = false)
    }
    return own + others
}

/** What became of an invitation, in words. */
fun inviteStatus(row: InviteRow): String = when {
    row.pending -> "Sent, waiting for a block"
    row.state == "Invite" ->
        if (row.sent?.code != null) "Not activated yet: give the code" else "Not accepted yet"
    row.state == "Candidate" -> "Activated: candidate for the next validation"
    row.state in validatedStates -> "Validated: ${row.state}"
    row.state == null || row.state == "Undefined" || row.state == "Killed" -> "Cancelled or spoiled"
    else -> row.state
}
