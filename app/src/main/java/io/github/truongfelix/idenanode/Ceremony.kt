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

    /** False while the node has not delivered the flip: the screen shows it as loading. */
    val loaded: Boolean get() = images.isNotEmpty()

    companion object {
        /** A flip the node has not delivered yet. */
        fun loading(hash: String) = Flip(hash, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** The story the validator picked; [value] is the answer the node expects. */
enum class Choice(val value: Int) { None(0), Left(1), Right(2) }

/** The keyword check of a flip in the long session. */
enum class Relevance { None, Approved, Reported }

/**
 * The answers of one session. The node ignores all grades and reports of a validator who reports 34% or
 * more of the long-session flips, grades more than one flip above "approved", or approves none
 * (core/ceremony/qualification.go, Upgrade 11). So reports are limited to a third of the flips (the most
 * that stays under 34% for any long session, at most 30 flips), one flip at most is the best, and the
 * screen warns while no flip is approved.
 */
data class Answers(
    val flipCount: Int,
    val choiceByHash: Map<String, Choice> = emptyMap(),
    val relevanceByHash: Map<String, Relevance> = emptyMap(),
    val best: String? = null,
) {
    val reportsAllowed: Int get() = flipCount / 3
    val reportsUsed: Int get() = relevanceByHash.count { it.value == Relevance.Reported }
    val reportsLeft: Int get() = reportsAllowed - reportsUsed
    val hasApproved: Boolean get() = relevanceByHash.any { it.value == Relevance.Approved }

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

    /**
     * Marks the flip as the best: approves it (a reported flip frees its report) and takes the mark from
     * any other flip. On the best flip, removes the mark; the flip stays approved.
     */
    fun toggleBest(hash: String): Answers =
        if (best == hash) copy(best = null)
        else copy(relevanceByHash = relevanceByHash + (hash to Relevance.Approved), best = hash)

    /** The grade the node expects (types.Grade): 0 none, 1 reported, 2 approved, 5 approved and the best. */
    fun grade(hash: String): Int = when (relevance(hash)) {
        Relevance.Reported -> 1
        Relevance.Approved -> if (best == hash) 5 else 2
        Relevance.None -> 0
    }

    /*
     * What the screens ask for before they offer Submit (the 10-04 ceremony was lost to a Submit tapped with
     * 2 of 6 flips answered). The answers still go by themselves at the end of the session, whatever is done.
     */

    /** The flips that can be answered: those the node delivered. */
    fun solvable(flips: List<Flip>): List<Flip> = flips.filter { it.loaded }

    fun solvedCount(flips: List<Flip>): Int = solvable(flips).count { choice(it.hash) != Choice.None }

    /** True once a story is picked for every delivered flip; flips still loading cannot be answered. */
    fun storiesDone(flips: List<Flip>): Boolean = solvable(flips).let { it.isNotEmpty() && solvedCount(flips) == it.size }

    /** The flips the keyword check asks about: those whose keywords are on screen. */
    fun gradable(flips: List<Flip>): List<Flip> = flips.filter { it.loaded && it.keywords.isNotEmpty() }

    fun gradedCount(flips: List<Flip>): Int = gradable(flips).count { relevance(it.hash) != Relevance.None }

    /** True once every flip with keywords on screen is approved or reported. */
    fun gradesDone(flips: List<Flip>): Boolean = gradedCount(flips) == gradable(flips).size

    /** A best flip is due as soon as a flip is approved: only an approved flip can be the best. */
    val bestDone: Boolean get() = best != null || !hasApproved

    fun keywordCheckDone(flips: List<Flip>): Boolean = gradesDone(flips) && bestDone
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
