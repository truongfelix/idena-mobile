package io.github.truongfelix.idenanode

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/*
 * The validation ceremony as the screens see it: flips, the validator's answers and the rules of the
 * keyword check. The practice mode uses it now, the real sessions later.
 */

/** A keyword of a flip: the word and its description. */
class Keyword(val name: String, val description: String)

/** A flip: four images and the two stories (orders of the images) the validator chooses between. */
class Flip(
    val hash: String,
    /** The four images, encoded (PNG or JPEG) as the node returns them. */
    val images: List<ByteArray>,
    /** The left and the right story, as indexes into [images]. */
    val leftOrder: List<Int>,
    val rightOrder: List<Int>,
    /** The flip's two keywords, checked in the long session. */
    val keywords: List<Keyword>,
) {
    fun order(choice: Choice): List<Int> = if (choice == Choice.Right) rightOrder else leftOrder
}

/** The story the validator picked; [value] is the answer the node expects. */
enum class Choice(val value: Int) { None(0), Left(1), Right(2) }

/** The keyword check of a flip in the long session. */
enum class Relevance { None, Approved, Reported }

/**
 * The answers of one session. As in the desktop app, reports are limited to a third of the flips and
 * one approved flip can be marked as the best.
 */
data class Answers(
    val flipCount: Int,
    val choiceByHash: Map<String, Choice> = emptyMap(),
    val relevanceByHash: Map<String, Relevance> = emptyMap(),
    val best: String? = null,
) {
    val reportsAllowed: Int get() = flipCount / 3
    val reportsLeft: Int get() = reportsAllowed - relevanceByHash.count { it.value == Relevance.Reported }

    fun choice(hash: String): Choice = choiceByHash[hash] ?: Choice.None
    fun relevance(hash: String): Relevance = relevanceByHash[hash] ?: Relevance.None

    fun choose(hash: String, choice: Choice) = copy(choiceByHash = choiceByHash + (hash to choice))

    fun approve(hash: String) = copy(relevanceByHash = relevanceByHash + (hash to Relevance.Approved))

    /** Reports the flip; unchanged when no report is left. A reported flip loses the best mark. */
    fun report(hash: String): Answers {
        if (relevance(hash) != Relevance.Reported && reportsLeft <= 0) return this
        return copy(
            relevanceByHash = relevanceByHash + (hash to Relevance.Reported),
            best = best.takeUnless { it == hash },
        )
    }

    /** Marks an approved flip as the best (taking the mark from another flip), or removes the mark. */
    fun toggleBest(hash: String): Answers = when {
        best == hash -> copy(best = null)
        relevance(hash) == Relevance.Approved -> copy(best = hash)
        else -> this
    }

    /** The grade the node expects (types.Grade): 0 none, 1 reported, 2 approved, 5 approved and the best. */
    fun grade(hash: String): Int = when (relevance(hash)) {
        Relevance.Reported -> 1
        Relevance.Approved -> if (best == hash) 5 else 2
        Relevance.None -> 0
    }
}

/** The validation time the node reports (RFC 3339), or null if it cannot be read. */
fun parseValidationTime(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

/** "in 8 days", "in 5 h", "in 12 min", or "now" once the time is reached. */
fun timeUntil(now: Instant, then: Instant): String {
    val left = Duration.between(now, then)
    return when {
        left.isNegative || left.isZero -> "now"
        left.toDays() >= 2 -> "in ${left.toDays()} days"
        left.toHours() >= 1 -> "in ${left.toHours()} h"
        else -> "in ${maxOf(1, left.toMinutes())} min"
    }
}

/** The ceremony period the node reports, as the screen says it; null outside the ceremony. */
fun periodText(period: String): String? = when (period) {
    "FlipLottery" -> "Flip lottery: the validation starts soon"
    "ShortSession" -> "Short session"
    "LongSession" -> "Long session"
    "AfterLongSession" -> "Long session over: results soon"
    else -> null
}
