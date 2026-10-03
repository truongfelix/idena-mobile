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
 * The extra flips that replace the regular flips that did not load, from 35 s into the short session as
 * in the desktop app: each regular flip not loaded and not replaced yet gets the next loaded extra flip
 * not used yet. A replacement is kept for the rest of the session, also if the regular flip loads later:
 * the validator may have answered the extra flip already.
 */
fun replaceWithExtras(hashes: List<FlipHash>, loaded: Set<String>, replaced: Map<String, String>): Map<String, String> {
    val extras = hashes.filter { it.extra && it.hash in loaded && it.hash !in replaced.values }.map { it.hash }.iterator()
    val result = replaced.toMutableMap()
    for (hash in hashes.filter { !it.extra }.map { it.hash }) {
        if (hash !in loaded && hash !in result && extras.hasNext()) result[hash] = extras.next()
    }
    return result
}

/** The short-session flips to show: the regular (not extra) flips in the node's order, as [replaced]. */
fun shortSessionFlips(hashes: List<FlipHash>, replaced: Map<String, String>): List<String> =
    hashes.filter { !it.extra }.map { replaced[it.hash] ?: it.hash }

/** An answer as flip_submitShortAnswers and flip_submitLongAnswers take it. */
data class SubmittedAnswer(val hash: String, val answer: Int, val grade: Int)

/**
 * The short-session answers: the story of every short-session flip, extra flips included (0 if none),
 * as the desktop app sends them: an answer stays even if its flip is no longer shown.
 */
fun shortSessionAnswers(hashes: List<String>, answers: Answers): List<SubmittedAnswer> =
    hashes.map { SubmittedAnswer(it, answers.choice(it).value, 0) }

/** The long-session answers: the story and the keyword check of every long-session flip. */
fun longSessionAnswers(hashes: List<String>, answers: Answers): List<SubmittedAnswer> =
    hashes.map { SubmittedAnswer(it, answers.choice(it).value, answers.grade(it)) }

/** The keywords of a flip from their indexes (flip_words) in the node's keyword list. */
fun keywordsOf(indexes: List<Int>, all: List<Keyword>): List<Keyword> = indexes.mapNotNull { all.getOrNull(it) }

/** An error that means the answers are already with the node (a second submit of the same answers). */
fun isAlreadySent(error: String?): Boolean = error?.contains("already exists") == true

/**
 * Whether the answers are in blocks: the long answers, and the short answers the node reveals once the
 * long ones are sent (their hash went in the short session). Until then the node must run; after, the
 * app may close. [txs] are the identity's transactions in blocks (bcn_transactions): type and epoch.
 */
fun answersInBlocks(txs: List<Pair<String, Int>>, epoch: Int): Boolean =
    listOf("submitShortAnswers", "submitLongAnswers").all { type -> txs.any { it.first == type && it.second == epoch } }
