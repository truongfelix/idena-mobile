package io.github.truongfelix.idenanode

import java.math.BigDecimal

/*
 * Sending iDNA and adding stake: the checks before asking the node, and what adding stake changes for
 * the identity. The rules are the node's (blockchain.go ReplenishStakeTx and KillTx,
 * core/ceremony/ceremony.go stake burning, core/state/state_object.go IsDiscriminatedStake).
 */

/** The node's transaction types used here (blockchain/types/types.go). */
const val SEND_TX = 0
const val REPLENISH_STAKE_TX = 0x16

private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

private val PLAIN_DECIMAL = Regex("^([0-9]+(\\.[0-9]*)?|\\.[0-9]+)$")

/** Whether [text] is an Idena address: 0x and 40 hexadecimal digits. */
fun isAddress(text: String): Boolean = ADDRESS.matches(text.trim())

/**
 * An iDNA amount typed by the user: positive, at most 18 decimals (the coin's smallest unit), a comma
 * accepted as the decimal separator; null otherwise.
 */
fun parseIdna(text: String): BigDecimal? {
    val normalized = text.trim().replace(',', '.')
    // Digits with an optional decimal part only: no sign, no exponent ("1e3").
    if (!PLAIN_DECIMAL.matches(normalized)) return null
    val amount = normalized.toBigDecimalOrNull() ?: return null
    if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 18) return null
    return amount
}

/**
 * The identity after adding [added] iDNA of stake: its stake, the share it keeps if it misses or fails
 * the next validation (null when it cannot lose any), and whether it stays below the discrimination
 * threshold. Added stake is not locked (only invitation rewards are), so the burn rule of
 * [stakeProtection] applies to the new total.
 */
data class StakePreview(
    val newStake: BigDecimal,
    val keptIfMissed: Double?,
    val keptIfFailed: Double?,
    val belowThreshold: Boolean,
) {
    companion object {
        private val VALIDATED = setOf("Newbie", "Verified", "Human")

        fun of(state: String, age: Int, stake: BigDecimal, lockedStake: BigDecimal, added: BigDecimal, threshold: BigDecimal?): StakePreview {
            val newStake = stake + added
            return StakePreview(
                newStake = newStake,
                keptIfMissed = stakeProtection(state, age, newStake.toDouble(), lockedStake.toDouble(), Outcome.Miss),
                keptIfFailed = stakeProtection(state, age, newStake.toDouble(), lockedStake.toDouble(), Outcome.Fail),
                // IsDiscriminatedStake: only validated identities, only with a threshold set.
                belowThreshold = state in VALIDATED && threshold != null && threshold.signum() > 0 && newStake < threshold,
            )
        }
    }
}

/** Whether the identity can receive stake: the node refuses Undefined and Killed identities. */
fun canReceiveStake(state: String): Boolean = state != "Undefined" && state != "Killed" && state.isNotEmpty()

/**
 * The recipient as the confirmation shows it: the address in full, after its name if any ([names]: given in Social,
 * else claimed in the profiles contract, by lowercase address).
 */
fun recipientText(to: String, names: Map<String, String>): String =
    names[to.trim().lowercase()]?.let { "$it ($to)" } ?: to

/**
 * The people Send offers to pick: those of the People list ([people]) with a name ([names]: given in Social, else
 * claimed), but [me], sorted by name.
 */
fun pickablePeople(people: Map<String, Person>, names: Map<String, String>, me: String): List<Pair<String, String>> =
    people.keys.map { it.lowercase() }.distinct()
        .filter { it != me.lowercase() }
        .mapNotNull { address -> names[address]?.let { address to it } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

/**
 * A transfer's comment travels in its payload as UTF-8 bytes, as the desktop writes it (its send form and its
 * dna://send link): a reference for the recipient (an invoice id), public on the chain. The node takes far more; the
 * fee grows with the size.
 */
const val MAX_COMMENT_BYTES = 256

/** The comment's bytes, trimmed; null when there is nothing to send. */
fun commentBytes(comment: String): ByteArray? = comment.trim().takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

/** The payload for dna_sendTransaction and bcn_estimateTx, or null to send none. */
fun commentPayload(bytes: ByteArray?): String? = bytes?.let { bytesToHex(it) }
