package io.github.truongfelix.idenanode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlipDraftsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = KeywordPair(0, listOf(1, 2), false)
    private val b = KeywordPair(1, listOf(3, 4), false)

    private fun withFrame(draft: FlipDraft, slot: Int, bytes: String) =
        draft.copy(frames = draft.frames.toMutableList().also { it[slot] = bytes.toByteArray() })

    @Test
    fun eachPairKeepsItsOwnPictures() {
        val drafts = FlipDrafts(folder.root)
        val first = drafts.open(listOf(a, b))!!
        assertEquals(a.words, first.words)
        drafts.save(withFrame(first, 0, "a"))
        val other = drafts.switchTo(b)
        assertEquals(b.id, other.pairId)
        assertTrue("the other pair has no pictures", other.frames.all { it == null })
        drafts.save(withFrame(other, 0, "b"))
        assertArrayEquals("a".toByteArray(), drafts.switchTo(a).frames[0])
        assertArrayEquals("b".toByteArray(), drafts.load(b)!!.frames[0])
    }

    @Test
    fun theScreenOpensOnThePairWorkedOnLast() {
        val drafts = FlipDrafts(folder.root)
        drafts.save(withFrame(drafts.open(listOf(a, b))!!, 1, "a"))
        drafts.save(withFrame(drafts.switchTo(b), 1, "b"))
        assertEquals(b.words, drafts.open(listOf(a, b))!!.words)
        // b used (its flip sent elsewhere): its draft goes, the screen opens on a.
        val next = drafts.open(listOf(a))!!
        assertArrayEquals("a".toByteArray(), next.frames[1])
        assertNull(drafts.load(b))
    }

    @Test
    fun anotherEpochsPairsStartEmpty() {
        val drafts = FlipDrafts(folder.root)
        drafts.save(withFrame(drafts.open(listOf(a))!!, 0, "a"))
        // The next epoch: pair 0 has other words.
        val newPair = KeywordPair(0, listOf(7, 8), false)
        val draft = drafts.open(listOf(newPair))!!
        assertEquals(newPair.words, draft.words)
        assertTrue(draft.frames.all { it == null })
        assertNull("the old pair's draft is deleted", drafts.load(a))
    }

    @Test
    fun withoutAFreePairNothingIsDeleted() {
        val drafts = FlipDrafts(folder.root)
        drafts.save(withFrame(drafts.open(listOf(a))!!, 0, "a"))
        assertNull(drafts.open(emptyList()))
        assertArrayEquals("a".toByteArray(), drafts.load(a)!!.frames[0])
    }

    /** The single draft of 0.1.74 and earlier, as it saved it, made for pair [pairId]. */
    private fun oldDraft(pairId: Int) {
        val dir = File(folder.root, "draft").also { it.mkdirs() }
        File(dir, "frame0.jpg").writeBytes("old".toByteArray())
        File(dir, "draft.json").writeText("{\"pairId\":$pairId,\"adversarial\":2,\"sources\":0}")
    }

    @Test
    fun theOldDraftGoesToItsPair() {
        oldDraft(b.id)
        val drafts = FlipDrafts(folder.root)
        val draft = drafts.open(listOf(a, b))!!
        assertEquals(b.words, draft.words)
        assertEquals(2, draft.adversarial)
        assertArrayEquals("old".toByteArray(), draft.frames[0])
        assertFalse(File(folder.root, "draft").exists())
        assertTrue("not under the other pair", drafts.switchTo(a).frames.all { it == null })
    }

    @Test
    fun theOldDraftOfAUsedPairIsDropped() {
        oldDraft(5)
        val drafts = FlipDrafts(folder.root)
        assertTrue(drafts.open(listOf(a, b))!!.frames.all { it == null })
        assertFalse(File(folder.root, "draft").exists())
    }

    @Test
    fun draftPairPrefersTheLastFreeOne() {
        assertEquals(b, draftPair(listOf(a, b), b.words))
        assertEquals(a, draftPair(listOf(a, b), listOf(9, 9)))
        assertEquals(a, draftPair(listOf(a, b), null))
        assertNull(draftPair(emptyList(), b.words))
        assertEquals("3-4", draftKey(b.words))
    }
}
