package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ValidationTest {
    @Test
    fun sessionTimesFollowTheNode() {
        val times = CeremonyTimes(Instant.parse("2026-09-29T15:00:00Z"), 120.0, 1800.5)
        assertEquals(Instant.parse("2026-09-29T15:02:00Z"), times.shortEnd)
        assertEquals(Instant.parse("2026-09-29T15:32:00.500Z"), times.longEnd)
        // Answers go 10 s before each session ends, as in the desktop app.
        assertEquals(Instant.parse("2026-09-29T15:01:50Z"), times.shortSubmit)
        assertEquals(Instant.parse("2026-09-29T15:31:50.500Z"), times.longSubmit)
        assertEquals(Instant.parse("2026-09-29T15:00:35Z"), times.extraFlipsFrom)
    }

    @Test
    fun extraFlipsReplaceTheFlipsThatDidNotLoad() {
        val hashes = (1..6).map { FlipHash("r$it", ready = true, extra = false) } +
            (1..3).map { FlipHash("e$it", ready = true, extra = true) }
        val loaded = setOf("r1", "r2", "r4", "r5", "e2", "e3")
        // Before 35 s: the regular flips only, loaded or not.
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r6"), shortSessionFlips(hashes, loaded, useExtras = false))
        // Then each missing regular flip takes the next extra flip that loaded (e1 did not).
        assertEquals(listOf("r1", "r2", "e2", "r4", "r5", "e3"), shortSessionFlips(hashes, loaded, useExtras = true))
        // Not enough extra flips: the missing flip stays.
        assertEquals(
            listOf("r1", "r2", "e3", "r4", "r5", "r6"),
            shortSessionFlips(hashes, loaded - "e2", useExtras = true),
        )
        assertEquals(emptyList<String>(), shortSessionFlips(emptyList(), emptySet(), useExtras = true))
    }

    @Test
    fun shortAnswersAreTheStoriesOfTheFlipsShown() {
        val answers = Answers(3).choose("a", Choice.Left).choose("c", Choice.Right)
        assertEquals(
            listOf(SubmittedAnswer("a", 1, 0), SubmittedAnswer("b", 0, 0), SubmittedAnswer("c", 2, 0)),
            shortSessionAnswers(listOf("a", "b", "c"), answers),
        )
    }

    @Test
    fun longAnswersCarryTheKeywordCheck() {
        val answers = Answers(6)
            .choose("approved", Choice.Left).approve("approved")
            .choose("best", Choice.Right).approve("best").toggleBest("best")
            .choose("reported", Choice.Left).report("reported")
            .choose("unchecked", Choice.Right)
        assertEquals(
            listOf(
                SubmittedAnswer("approved", 1, 2),
                SubmittedAnswer("best", 2, 5),
                SubmittedAnswer("reported", 1, 1),
                SubmittedAnswer("unchecked", 2, 0),
                SubmittedAnswer("unsolved", 0, 0),
            ),
            longSessionAnswers(listOf("approved", "best", "reported", "unchecked", "unsolved"), answers),
        )
    }

    @Test
    fun keywordsComeFromTheNodeList() {
        val all = listOf(Keyword("time", "clock time"), Keyword("year", ""), Keyword("people", ""))
        assertEquals(listOf("people", "time"), keywordsOf(listOf(2, 0), all).map { it.name })
        assertEquals(listOf("year"), keywordsOf(listOf(1, 3), all).map { it.name })
    }

    @Test
    fun aRepeatedSubmitIsNotAnError() {
        assertTrue(isAlreadySent("tx with same hash already exists"))
        assertFalse(isAlreadySent("coinbase address is not a ceremony candidate"))
        assertFalse(isAlreadySent(null))
    }
}
