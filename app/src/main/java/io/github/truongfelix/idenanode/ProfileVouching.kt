package io.github.truongfelix.idenanode

// Vouched members and name challenges in the profiles contract (idena-profile, rulings Q-46 and Q-47): a validated
// identity vouches for accounts without an identity, which may then hold a name, write a profile and give Trust; a
// name whose holder lost its good standing is freed by a challenger calling challengeName in 3 epochs in a row. As
// the desktop (renderer/screens/social/vouching.js).

/** The epochs after the first call of a challenge: its third call, in a row, frees the name. */
const val GRACE_EPOCHS = 2

/** The members an identity vouches for at once, and as many new vouches per epoch. */
fun vouchCap(state: String?) = when (state) {
    "Human" -> 5
    "Verified" -> 3
    "Newbie" -> 1
    else -> 0
}

/** Whether [state] (the identity's, read) and no [voucher] leave the account to be vouched for: the "Get vouched for" card. */
fun wantsVouch(state: String?, voucher: String?) = voucher == null && isVouchable(state)

/** Whether [profile] acts as a vouched member: no identity, a validated voucher. */
fun isActingMember(profile: ContractProfile?) =
    profile?.voucher != null && isVouchable(profile.state) && isValidated(profile.voucherState)

/** A vouch offered to a member, not accepted yet ([epoch] it was offered), or a member accepted ([epoch] accepted). */
data class VouchEntry(val address: String, val epoch: Int)

/** A saved challenge, "<since>:<last>:<holder>": the epoch it started, its last call, the holder it was made on. */
data class NameChallenge(val since: Int, val last: Int, val holder: String)

fun parseChallenge(value: String?): NameChallenge? {
    val parts = value?.split(":")?.takeIf { it.size == 3 } ?: return null
    val since = parts[0].toIntOrNull() ?: return null
    val last = parts[1].toIntOrNull() ?: return null
    return NameChallenge(since, last, parts[2].lowercase())
}

/**
 * Where a challenge stands at an epoch: which call of the three the next one is ([step], 1 to 3), whether it can be
 * made now (one call an epoch), whether it frees the name, and the epoch the challenge started.
 */
data class ChallengeStep(val step: Int, val canCall: Boolean, val frees: Boolean, val since: Int)

/**
 * Where [challenge] on [holder]'s name stands at [epoch]. A challenge of another holder, or one whose challenger
 * skipped an epoch, starts again.
 */
fun challengeStep(challenge: NameChallenge?, epoch: Int, holder: String): ChallengeStep {
    val goesOn = challenge != null && challenge.holder == holder.lowercase() && challenge.last + 1 >= epoch
    if (!goesOn || challenge == null) return ChallengeStep(1, canCall = true, frees = false, since = epoch)
    if (challenge.last >= epoch) {
        return ChallengeStep(minOf(epoch - challenge.since + 2, GRACE_EPOCHS + 1), canCall = false, frees = false, since = challenge.since)
    }
    return ChallengeStep(
        minOf(epoch - challenge.since + 1, GRACE_EPOCHS + 1),
        canCall = true,
        frees = epoch >= challenge.since + GRACE_EPOCHS,
        since = challenge.since,
    )
}

/** What a "To" field holds: nothing, an address (lowercase), "@name" (lowercase, a valid name), or neither. */
sealed interface Recipient {
    data object Empty : Recipient
    data class Address(val address: String) : Recipient
    data class Name(val name: String) : Recipient
    data object Invalid : Recipient
}

fun parseRecipient(text: String): Recipient {
    val value = text.trim()
    if (value.isEmpty()) return Recipient.Empty
    if (isAddress(value)) return Recipient.Address(value.lowercase())
    if (value.startsWith("@")) {
        val name = value.substring(1).lowercase()
        if (PROFILE_NAME.matches(name)) return Recipient.Name(name)
    }
    return Recipient.Invalid
}

/** A name's holder ([holder] null when nobody holds it) and the holder's profile. */
data class ResolvedName(val holder: String?, val profile: ContractProfile?)

/**
 * Whom [recipient] sends to: an address as typed; a name's holder only while it is in good standing (a hidden name
 * can change hands); null otherwise. [resolved] is the name's lookup.
 */
fun recipientAddress(recipient: Recipient, resolved: ResolvedName?): String? = when (recipient) {
    is Recipient.Address -> recipient.address
    is Recipient.Name -> resolved?.holder?.takeIf { resolved.profile?.inStanding == true }
    else -> null
}

/** An open offer's entry ("o:" map: key "<member>:<voucher>", value the epoch) when [voucher] made it: the member and the epoch. */
fun offerBy(key: String, value: String, voucher: String): VouchEntry? {
    val parts = key.lowercase().split(":")
    if (parts.size != 2 || parts[1] != voucher.lowercase()) return null
    return VouchEntry(parts[0], value.toIntOrNull() ?: 0)
}

/** A Trust's entry ("t:" map: key "<target>:<giver>", value "<epoch>" or "<epoch>:v") when [giver] gave it: the target and the epoch. */
fun trustGivenBy(key: String, value: String, giver: String): VouchEntry? {
    val at = key.lastIndexOf(':')
    if (at <= 0 || key.substring(at + 1).lowercase() != giver.lowercase()) return null
    return VouchEntry(key.substring(0, at).lowercase(), value.substringBefore(':').toIntOrNull() ?: 0)
}
