package io.github.truongfelix.idenanode

import java.time.Duration
import java.time.Instant

/*
 * The real validation ceremony, as the Idena desktop app runs it (renderer/screens/validation), without
 * the screens: when each session ends and when answers are sent, which short-session flips are shown,
 * and the answers the node expects.
 */

/** A flip hash of the node (flip_shortHashes, flip_longHashes). */
data class FlipHash(val hash: String, val ready: Boolean, val extra: Boolean)

/** The times of a ceremony: it starts at [start]; the long session follows the short one. */
data class CeremonyTimes(val start: Instant, val shortSessionSeconds: Double, val longSessionSeconds: Double) {
    val shortEnd: Instant = start + seconds(shortSessionSeconds)
    val longEnd: Instant = shortEnd + seconds(longSessionSeconds)

    /** Answers go 10 s before a session ends, as in the desktop app: the node needs them in time. */
    val shortSubmit: Instant = shortEnd - SUBMIT_MARGIN
    val longSubmit: Instant = longEnd - SUBMIT_MARGIN

    /** From 35 s into the short session, extra flips replace the flips that did not load. */
    val extraFlipsFrom: Instant = start + Duration.ofSeconds(35)

    companion object {
        val SUBMIT_MARGIN: Duration = Duration.ofSeconds(10)
        private fun seconds(value: Double): Duration = Duration.ofMillis((value * 1000).toLong())
    }
}

/**
 * The short-session flips to show: the regular (not extra) flips in the node's order. Once [useExtras],
 * each regular flip that has not loaded is replaced by the next extra flip that has, as the desktop app
 * does 35 s into the session.
 */
fun shortSessionFlips(hashes: List<FlipHash>, loaded: Set<String>, useExtras: Boolean): List<String> {
    val regular = hashes.filter { !it.extra }.map { it.hash }
    if (!useExtras) return regular
    val extras = hashes.filter { it.extra && it.hash in loaded }.map { it.hash }.iterator()
    return regular.map { hash -> if (hash in loaded || !extras.hasNext()) hash else extras.next() }
}

/** An answer as flip_submitShortAnswers and flip_submitLongAnswers take it. */
data class SubmittedAnswer(val hash: String, val answer: Int, val grade: Int)

/** The short-session answers: the story of each flip shown (0 if none). */
fun shortSessionAnswers(shown: List<String>, answers: Answers): List<SubmittedAnswer> =
    shown.map { SubmittedAnswer(it, answers.choice(it).value, 0) }

/** The long-session answers: the story and the keyword check of every long-session flip. */
fun longSessionAnswers(hashes: List<String>, answers: Answers): List<SubmittedAnswer> =
    hashes.map { SubmittedAnswer(it, answers.choice(it).value, answers.grade(it)) }

/** The keywords of a flip from their indexes (flip_words) in the node's keyword list. */
fun keywordsOf(indexes: List<Int>, all: List<Keyword>): List<Keyword> = indexes.mapNotNull { all.getOrNull(it) }

/** An error that means the answers are already with the node (a second submit of the same answers). */
fun isAlreadySent(error: String?): Boolean = error?.contains("already exists") == true
