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

    @Test
    fun eachPictureKeepsItsOriginalAndEdit() {
        val drafts = FlipDrafts(folder.root)
        val edit = FrameEdit(zoom = 2f, panX = 0.5f, turns = 1, keepProportions = false, brightness = 1.4f)
        val draft = drafts.open(listOf(a))!!.withPicture(0, "frame".toByteArray(), "original".toByteArray(), edit).copy(ownPicture = true)
        drafts.save(draft)
        val back = drafts.load(a)!!
        assertArrayEquals("original".toByteArray(), back.originals[0])
        assertEquals(edit, back.edits[0])
        assertNull(back.originals[1])
        assertEquals(FrameEdit(), back.edits[1])
        assertTrue(back.ownPicture)
        assertEquals("four own pictures: no anti-AI frame", null, back.antiAi)
    }

    @Test
    fun aDraftOfAnOlderBuildStillOpens() {
        val drafts = FlipDrafts(folder.root)
        drafts.save(withFrame(drafts.open(listOf(a))!!, 0, "a"))
        // 0.3.0 wrote draft.json without the pictures' originals and edits.
        val dir = File(folder.root, "drafts/${draftKey(a.words)}")
        File(dir, "draft.json").writeText("""{"adversarial":3,"sources":0}""")
        val back = drafts.load(a)!!
        assertArrayEquals("a".toByteArray(), back.frames[0])
        assertNull("no original: the editor starts from the frame", back.originals[0])
        assertFalse(back.ownPicture)
        assertEquals(3, back.antiAi)
    }

    @Test
    fun anOriginalLeftByAnOlderBuildOverAnotherPictureIsDropped() {
        val drafts = FlipDrafts(folder.root)
        val draft = drafts.open(listOf(a))!!.withPicture(0, "frame".toByteArray(), "original".toByteArray(), FrameEdit(zoom = 2f))
        drafts.save(draft)
        // An older build put another picture in the frame and kept the new keys' file beside it.
        val dir = File(folder.root, "drafts/${draftKey(a.words)}")
        File(dir, "frame0.jpg").writeBytes("other".toByteArray())
        val back = drafts.load(a)!!
        assertArrayEquals("other".toByteArray(), back.frames[0])
        assertNull("the original was of the old picture", back.originals[0])
        assertEquals(FrameEdit(), back.edits[0])
    }

    @Test
    fun anOlderBuildReadsTheNewDraft() {
        val drafts = FlipDrafts(folder.root)
        val draft = drafts.open(listOf(a))!!.withPicture(1, "frame".toByteArray(), "original".toByteArray(), FrameEdit(brightness = 0.5f))
        drafts.save(draft.copy(shuffled = listOf(1, 0, 2, 3)))
        val json = org.json.JSONObject(File(folder.root, "drafts/${draftKey(a.words)}/draft.json").readText())
        // The keys 0.3.0 reads, with their types.
        assertEquals(draft.adversarial, json.getInt("adversarial"))
        assertEquals(0, json.getInt("sources"))
        assertEquals(4, json.getJSONArray("shuffled").length())
    }

    @Test
    fun anOriginalGoesWithItsFrame() {
        val drafts = FlipDrafts(folder.root)
        val draft = drafts.open(listOf(a))!!.withPicture(0, "frame".toByteArray(), "original".toByteArray(), FrameEdit())
        drafts.save(draft.copy(frames = draft.frames.toMutableList().also { it[0] = null }, originals = draft.originals.toMutableList().also { it[0] = null }))
        assertFalse("no original left without its frame", File(folder.root, "drafts/${draftKey(a.words)}/original0.jpg").exists())
    }

    @Test
    fun eachPictureKeepsItsNoiseWhileItStaysThere() {
        val drafts = FlipDrafts(folder.root)
        val e0 = NoiseEffects(mirror = true, grain = 25, hue = 33)
        val e1 = NoiseEffects(mirror = false, grain = 40, hue = 60)
        val draft = withFrame(withFrame(drafts.open(listOf(a))!!, 0, "a"), 1, "b").withNoise(0, e0).withNoise(1, e1)
        drafts.save(draft)
        val back = drafts.load(a)!!
        assertEquals(e0, back.noiseOf(0))
        assertEquals(e1, back.noiseOf(1))
        assertNull("no picture, no noise", back.noiseOf(2))
        // The noise moves with its picture (as moveFrame swaps them).
        val swapped = back.copy(frames = listOf(back.frames[1], back.frames[0], null, null), noise = listOf(back.noise[1], back.noise[0], null, null))
        assertEquals(e1, swapped.noiseOf(0))
        assertEquals(e0, swapped.noiseOf(1))
        // Another picture in the frame: the automatic noise again.
        val changed = withFrame(back, 0, "c")
        assertNull(changed.noiseOf(0))
        drafts.save(changed)
        val json = org.json.JSONObject(File(folder.root, "drafts/${draftKey(a.words)}/draft.json").readText())
        assertTrue("the changed picture's noise is not kept", json.getJSONArray("noise").isNull(0))
        assertEquals(e1, drafts.load(a)!!.noiseOf(1))
    }

    @Test
    fun twoSlotsWithTheSamePictureKeepTheirOwnNoise() {
        val drafts = FlipDrafts(folder.root)
        val e0 = NoiseEffects(mirror = true, grain = 22, hue = 41)
        val e1 = NoiseEffects(mirror = false, grain = 29, hue = 57)
        val draft = withFrame(withFrame(drafts.open(listOf(a))!!, 0, "same"), 1, "same").withNoise(0, e0).withNoise(1, e1)
        assertEquals(e0, draft.noiseOf(0))
        assertEquals(e1, draft.noiseOf(1))
        drafts.save(draft)
        val back = drafts.load(a)!!
        assertEquals(e0, back.noiseOf(0))
        assertEquals(e1, back.noiseOf(1))
    }

    @Test
    fun aNoiseLeftByAnOlderBuildOverAnotherPictureIsDropped() {
        val drafts = FlipDrafts(folder.root)
        drafts.save(withFrame(drafts.open(listOf(a))!!, 0, "a").withNoise(0, NoiseEffects(grain = 31)))
        // A build of 0.4.0 put another picture in the frame; it rewrites draft.json without the noise, or keeps it.
        File(folder.root, "drafts/${draftKey(a.words)}/frame0.jpg").writeBytes("other".toByteArray())
        assertNull(drafts.load(a)!!.noiseOf(0))
        File(folder.root, "drafts/${draftKey(a.words)}/draft.json").writeText("""{"adversarial":3,"sources":0}""")
        assertEquals(List<SlotNoise?>(FLIP_FRAMES) { null }, drafts.load(a)!!.noise)
    }
}
