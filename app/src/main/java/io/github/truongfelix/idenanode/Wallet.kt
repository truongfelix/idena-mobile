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
