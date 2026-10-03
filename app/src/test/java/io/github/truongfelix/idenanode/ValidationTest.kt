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
        // Before 35 s (nothing replaced): the regular flips only, loaded or not.
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r6"), shortSessionFlips(hashes, emptyMap()))
        // Then each missing regular flip takes the next extra flip that loaded (e1 did not).
        val replaced = replaceWithExtras(hashes, loaded, emptyMap())
        assertEquals(mapOf("r3" to "e2", "r6" to "e3"), replaced)
        assertEquals(listOf("r1", "r2", "e2", "r4", "r5", "e3"), shortSessionFlips(hashes, replaced))
        // Not enough extra flips: the missing flip stays.
        val fewer = replaceWithExtras(hashes, loaded - "e2", emptyMap())
        assertEquals(listOf("r1", "r2", "e3", "r4", "r5", "r6"), shortSessionFlips(hashes, fewer))
        assertEquals(emptyList<String>(), shortSessionFlips(emptyList(), replaceWithExtras(emptyList(), emptySet(), emptyMap())))
    }

    @Test
    fun aReplacementStaysWhenTheFlipLoadsLater() {
        val hashes = (1..6).map { FlipHash("r$it", ready = true, extra = false) } +
            (1..3).map { FlipHash("e$it", ready = true, extra = true) }
        val first = replaceWithExtras(hashes, setOf("r1", "r2", "r4", "r5", "r6", "e1"), emptyMap())
        assertEquals(mapOf("r3" to "e1"), first)
        // r3 loads later: e1 stays, it may be answered already.
        val later = replaceWithExtras(hashes, setOf("r1", "r2", "r3", "r4", "r5", "r6", "e1", "e2"), first)
        assertEquals(first, later)
        assertEquals(listOf("r1", "r2", "e1", "r4", "r5", "r6"), shortSessionFlips(hashes, later))
        // An extra flip already used is not given twice.
        val again = replaceWithExtras(hashes, setOf("r1", "r2", "r4", "r5", "e1", "e2"), first)
        assertEquals(mapOf("r3" to "e1", "r6" to "e2"), again)
    }

    @Test
    fun shortAnswersCoverEveryShortFlip() {
        // e1 was answered while shown; the answer goes even if the flip is no longer on screen.
        val answers = Answers(3).choose("a", Choice.Left).choose("e1", Choice.Right)
        assertEquals(
            listOf(SubmittedAnswer("a", 1, 0), SubmittedAnswer("b", 0, 0), SubmittedAnswer("e1", 2, 0)),
            shortSessionAnswers(listOf("a", "b", "e1"), answers),
        )
    }

    @Test
    fun theAppMayCloseOnceBothAnswersAreInBlocks() {
        assertFalse(answersInBlocks(emptyList(), 230))
        assertFalse("the short answers come after the long ones", answersInBlocks(listOf("submitLongAnswers" to 230, "submitAnswersHash" to 230), 230))
        assertFalse("last epoch's", answersInBlocks(listOf("submitShortAnswers" to 229, "submitLongAnswers" to 229), 230))
        assertTrue(answersInBlocks(listOf("send" to 230, "submitShortAnswers" to 230, "submitLongAnswers" to 230), 230))
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
