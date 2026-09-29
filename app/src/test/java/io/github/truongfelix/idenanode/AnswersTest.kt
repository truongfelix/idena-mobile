package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswersTest {
    private val flips = (1..9).map { "flip$it" }

    @Test
    fun reportsAreLimitedToAThirdOfTheFlips() {
        var answers = Answers(flips.size)
        assertEquals(3, answers.reportsAllowed)
        flips.forEach { answers = answers.report(it) }
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0, 0, 0), flips.map { answers.grade(it) })
        assertEquals(0, answers.reportsLeft)
        // Approving a reported flip frees its report.
        answers = answers.approve("flip1").report("flip9")
        assertEquals(2, answers.grade("flip1"))
        assertEquals(1, answers.grade("flip9"))
    }

    @Test
    fun reportLimitIsTheMostThatStaysUnderTheNodes34Percent() {
        // core/ceremony/qualification.go: reported / flips >= 0.34 voids all grades and reports.
        for (count in 1..30) {
            val allowed = Answers(count).reportsAllowed
            assertTrue("$allowed of $count", allowed < 0.34 * count)
            assertTrue("${allowed + 1} of $count", allowed + 1 >= 0.34 * count)
        }
    }

    @Test
    fun bestApprovesTheFlipAndOnlyOneFlipIsTheBest() {
        var answers = Answers(flips.size).toggleBest("flip1")
        assertEquals("flip1", answers.best)
        assertEquals("the best flip is approved", 5, answers.grade("flip1"))
        answers = answers.toggleBest("flip2")
        assertEquals("flip2", answers.best)
        assertEquals("the mark moves, the flip stays approved", listOf(2, 5), listOf(answers.grade("flip1"), answers.grade("flip2")))
        assertEquals(1, flips.count { answers.grade(it) == 5 })
        assertEquals(2, answers.toggleBest("flip2").grade("flip2"))
        assertNull(answers.toggleBest("flip2").best)
        assertNull("reporting the best flip removes the mark", answers.report("flip2").best)
        // Best on a reported flip approves it and frees the report.
        answers = answers.report("flip3")
        assertEquals(1, answers.reportsUsed)
        answers = answers.toggleBest("flip3")
        assertEquals(listOf(0, 5), listOf(answers.reportsUsed, answers.grade("flip3")))
    }

    @Test
    fun hasApprovedOnlyWithAnApprovedFlip() {
        assertFalse(Answers(flips.size).hasApproved)
        assertFalse(Answers(flips.size).report("flip1").hasApproved)
        assertTrue(Answers(flips.size).approve("flip1").hasApproved)
        assertTrue(Answers(flips.size).toggleBest("flip1").hasApproved)
    }

    @Test
    fun unansweredFlipsSendNothing() {
        val answers = Answers(flips.size).choose("flip1", Choice.Right)
        assertEquals(Choice.Right, answers.choice("flip1"))
        assertEquals(Choice.None, answers.choice("flip2"))
        assertEquals(listOf(2, 0), listOf(answers.choice("flip1").value, answers.choice("flip2").value))
        assertEquals(0, answers.grade("flip1"))
    }
}
