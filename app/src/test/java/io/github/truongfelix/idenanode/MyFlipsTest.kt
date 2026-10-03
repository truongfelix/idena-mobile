package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyFlipsTest {
    private val cid = "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"

    @Test
    fun cidsDecodeToTheirBytes() {
        // Python's base64.b32decode of the same CID: CIDv1, raw, sha2-256 (of nothing).
        val expected = hexToBytes("0x01551220e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertArrayEquals(expected, cidBytes(cid))
    }

    @Test
    fun theDeletePayloadIsTheProtobufAttachment() {
        // ProtoDeleteFlipAttachment { bytes cid = 1 }: field 1, length-delimited (0x0A), length 36, the bytes.
        val payload = deleteFlipPayload(cid)
        assertEquals(0x0A, payload[0].toInt())
        assertEquals(36, payload[1].toInt())
        assertArrayEquals(cidBytes(cid), payload.copyOfRange(2, payload.size))
    }

    @Test
    fun madeFlipsKeepTheirStoryAndShuffle() {
        val made = MadeFlip(cid, "0xtx", pairId = 2, words = listOf(5, 9), shuffled = listOf(2, 0, 3, 1), epoch = 230, sentAt = 1_000)
        assertEquals(made, MadeFlip.fromJson(made.toJson()))
        assertNull("a broken shuffle is not kept", MadeFlip.fromJson(made.toJson().put("shuffled", JSONArray(listOf(0, 0, 1, 2)))))
        val keywords = (0..9).map { Keyword("w$it", "") }
        val frames = (0 until 4).map { byteArrayOf(it.toByte()) }
        val flip = made.asFlip(frames, keywords)
        assertEquals(listOf(0, 1, 2, 3), flip.leftOrder)
        assertEquals(listOf(2, 0, 3, 1), flip.rightOrder)
        assertEquals(listOf("w5", "w9"), flip.keywords.map { it.name })
    }

    @Test
    fun theListJoinsTheNodeAndThePhone() {
        val published = MadeFlip("bafkreia", "0x1", 0, listOf(1, 2), listOf(1, 0, 3, 2), 230, sentAt = 1_000)
        val waiting = MadeFlip("bafkreib", "0x2", 1, listOf(3, 4), listOf(1, 0, 3, 2), 230, sentAt = 50_000)
        val gone = MadeFlip("bafkreic", "0x3", 2, listOf(5, 6), listOf(1, 0, 3, 2), 230, sentAt = 2_000)
        val now = 2_000 + SENT_FLIP_TIMEOUT_MS + 1
        val rows = myFlipRows(listOf("bafkreia", "bafkreix"), listOf(published, waiting, gone), deleting = emptySet(), now = now)
        assertEquals(listOf("bafkreia", "bafkreix", "bafkreib"), rows.map { it.cid })
        assertEquals(published, rows[0].made)
        assertNull("made in another app", rows[1].made)
        assertEquals(listOf(MyFlipState.Published, MyFlipState.Published, MyFlipState.Waiting), rows.map { it.state })
        val deleting = myFlipRows(listOf("bafkreia"), listOf(published), deleting = setOf("bafkreia"), now = now)
        assertEquals(MyFlipState.Deleting, deleting.single().state)
        assertTrue(canDeleteFlip(rows[0], "None"))
        assertTrue("a flip of another app can be deleted too", canDeleteFlip(rows[1], "None"))
        assertFalse("not in a block yet", canDeleteFlip(rows[2], "None"))
        assertFalse("from the flip lottery on", canDeleteFlip(rows[0], "FlipLottery"))
        assertFalse(canDeleteFlip(deleting.single(), "None"))
    }

    @Test
    fun theIdentityListsItsFlips() {
        assertEquals(listOf("bafkreia", "bafkreib"), identityFlips(JSONObject().put("flips", JSONArray(listOf("bafkreia", "bafkreib")))))
        assertEquals(emptyList<String>(), identityFlips(JSONObject().put("flips", JSONObject.NULL)))
    }
}
