package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun onlyOneApprovedFlipIsTheBest() {
        var answers = Answers(flips.size).toggleBest("flip1")
        assertNull("a flip must be approved to be the best", answers.best)
        answers = answers.approve("flip1").approve("flip2").toggleBest("flip1").toggleBest("flip2")
        assertEquals("flip2", answers.best)
        assertEquals(listOf(2, 5), listOf(answers.grade("flip1"), answers.grade("flip2")))
        assertNull(answers.toggleBest("flip2").best)
        assertNull("reporting the best flip removes the mark", answers.report("flip2").best)
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
