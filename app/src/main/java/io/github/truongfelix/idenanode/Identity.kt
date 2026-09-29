package io.github.truongfelix.idenanode

/*
 * What the main screen tells about the identity: how much stake it keeps if it misses or fails the next
 * validation, its current task, and which mining actions the node would accept now. The rules are the
 * node's (core/ceremony/ceremony.go, blockchain/validation/validation.go, with Upgrades 10 and 12);
 * the task texts follow the Idena desktop app (shared/components/sidebar.js).
 */

/** How the identity would do at the next validation: not taking part, or failing it. */
enum class Outcome { Miss, Fail }

private val validatedStates = setOf("Newbie", "Verified", "Human")

/**
 * Whether the identity ends up Killed after [outcome] (determineNewIdentityState). Verified and Human
 * are suspended instead of killed when they miss, Human also when it fails; Suspended becomes Zombie
 * when it misses.
 */
fun killedAfter(state: String, outcome: Outcome): Boolean = when (outcome) {
    Outcome.Miss -> state in setOf("Invite", "Candidate", "Newbie", "Zombie")
    Outcome.Fail -> state in setOf("Invite", "Candidate", "Newbie", "Verified", "Suspended", "Zombie")
}

/**
 * The share of the stake the identity keeps (0 to 1) if it misses or fails the next validation; null
 * without stake or for an identity that cannot lose any (Undefined, Killed). A killed identity loses
 * its locked stake and a share of the rest (determineStakeShareToBurn): none for Human, all for Suspended
 * and Zombie up to age 4 and then 10 - age percent, all for the other states.
 */
fun stakeProtection(state: String, age: Int, stake: Double, lockedStake: Double, outcome: Outcome): Double? {
    if (stake <= 0 || state == "Undefined" || state == "Killed") return null
    if (!killedAfter(state, outcome)) return 1.0
    val burnPercent = when (state) {
        "Human" -> 0
        "Suspended", "Zombie" -> if (age <= 4) 100 else maxOf(10 - age, 0)
        else -> 100
    }
    val available = (stake - lockedStake).coerceAtLeast(0.0)
    return available * (100 - burnPercent) / 100 / stake
}

/**
 * The identity's current task, as the desktop app says it. [answersSent] is true once the phone sent
 * the answers of the current ceremony.
 */
fun currentTask(
    period: String,
    state: String,
    requiredFlips: Int,
    availableFlips: Int,
    publishedFlips: Int,
    answersSent: Boolean,
): String = when (period) {
    "FlipLottery" -> "Shuffling flips…"
    "ShortSession", "LongSession" -> when {
        state == "Undefined" || state == "Invite" -> "No validation: the invitation was not activated"
        state in validatedStates || state in setOf("Candidate", "Suspended", "Zombie") ->
            if (answersSent) "Wait for the validation end" else "Validate"
        else -> "No validation for this identity"
    }
    "AfterLongSession" -> "Wait for the validation end"
    else -> when {
        state == "Invite" -> "Accept the invitation (on app.idena.io)"
        state == "Undefined" -> "Activate an invitation (on app.idena.io)"
        state in validatedStates -> {
            val requiredLeft = requiredFlips - publishedFlips
            val optional = availableFlips - maxOf(requiredFlips, publishedFlips)
            when {
                requiredLeft > 0 -> "Create $requiredLeft required flip" + if (requiredLeft > 1) "s" else ""
                optional > 0 -> "Wait for the validation or create $optional optional flip" + if (optional > 1) "s" else ""
                else -> "Wait for the validation"
            }
        }
        state in setOf("Candidate", "Suspended", "Zombie") -> "Wait for the validation"
        else -> "No task: this identity cannot take part"
    }
}

/**
 * The mining actions the node would accept now (validateOnlineStatusTx, validateDelegateTx,
 * validateUndelegateTx). None of them during the ceremony. Mining and stopping need a validated identity
 * (or a pool) that is not delegated; delegating needs no delegation yet, not being a pool and no mining
 * penalty; undelegating is possible from the epoch after the delegation.
 */
data class MiningActions(val canStartMining: Boolean, val canStopMining: Boolean, val canDelegate: Boolean, val canUndelegate: Boolean) {
    companion object {
        fun of(
            period: String,
            state: String,
            online: Boolean,
            isPool: Boolean,
            delegated: Boolean,
            delegationEpoch: Int,
            epoch: Int,
            penaltySeconds: Long,
        ): MiningActions {
            val outsideCeremony = period == "None"
            val canMine = outsideCeremony && !delegated && (state in validatedStates || isPool)
            return MiningActions(
                canStartMining = canMine && !online,
                canStopMining = canMine && online,
                canDelegate = outsideCeremony && !delegated && !isPool && penaltySeconds == 0L,
                canUndelegate = outsideCeremony && delegated && epoch > delegationEpoch,
            )
        }
    }
}
