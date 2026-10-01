package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class AdsTest {
    /** A protobuf writer for the test messages, as protobuf-js writes them. */
    private class Writer {
        private val out = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun string(number: Int, value: String) = bytes(number, value.toByteArray())
        fun bytes(number: Int, value: ByteArray) = apply {
            varint((number shl 3 or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }
        fun int(number: Int, value: Int) = apply {
            varint((number shl 3).toLong())
            // int32 is sign-extended to 64 bits.
            varint(value.toLong())
        }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val cid = "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"
    private val anyone = AdViewer("fr", "android", age = 0, stake = BigDecimal.ZERO)

    private fun targetHex(language: String = "", age: Int = 0, os: String = "", stake: Int = 0) =
        hex(Writer().string(1, language).int(2, age).string(3, os).int(4, stake).toBytes())

    private fun burnKey(cid: String, target: String) = "0x" + hex(Writer().string(1, cid).string(2, target).toBytes())

    @Test
    fun aBurnKeyGivesTheAdAndItsAudience() {
        val (decodedCid, target) = decodeBurnKey(burnKey(cid, targetHex(language = "fr", age = 5, os = "android", stake = 1000)))!!
        assertEquals(cid, decodedCid)
        assertEquals(AdTarget("fr", 5, "android", 1000), target)
        assertEquals("no audience: anyone", AdTarget(), decodeBurnKey(burnKey(cid, ""))!!.second)
    }

    @Test
    fun aKeyThatIsNoAdIsSkipped() {
        assertNull("not hex", decodeBurnKey("some key"))
        assertNull("cid naming a path", decodeBurnKey(burnKey("../../shared_prefs", "")))
        assertNull("truncated", decodeBurnKey("0x0a40" + hex(cid.toByteArray()).take(20)))
    }

    @Test
    fun theAudienceMustIncludeTheViewer() {
        val veteran = AdViewer("FR", "android", age = 10, stake = BigDecimal("5000.5"))
        assertTrue(isTargeted(AdTarget(), anyone))
        assertTrue(isTargeted(AdTarget("fr", 10, "Android", 5000), veteran))
        assertFalse("language", isTargeted(AdTarget(language = "en"), veteran))
        assertFalse("os", isTargeted(AdTarget(os = "windows"), veteran))
        assertFalse("age", isTargeted(AdTarget(age = 11), veteran))
        assertFalse("stake", isTargeted(AdTarget(stake = 5001), veteran))
    }

    @Test
    fun burnsAreRankedByCoinsWeightedByTheAudience() {
        val burnt = JSONArray()
            .put(JSONObject().put("address", "0xA").put("amount", "100").put("key", burnKey("${cid}a", targetHex())))
            .put(JSONObject().put("address", "0xB").put("amount", "10").put("key", burnKey("${cid}b", targetHex(language = "fr"))))
            .put(JSONObject().put("address", "0xC").put("amount", "1000").put("key", burnKey("${cid}c", targetHex(language = "en"))))
            .put(JSONObject().put("address", "0xD").put("amount", "30").put("key", burnKey("${cid}d", targetHex(os = "android"))))
            .put(JSONObject().put("address", "0xa").put("amount", "50").put("key", burnKey("${cid}a", targetHex(os = "android"))))
            .put(JSONObject().put("address", "0xE").put("amount", "999").put("key", "not an ad"))
        val ranked = rankedBurns(burnt, anyone)
        // b: 10 × 22 = 220, a: 50 × 5 = 250 (its best key), d: 30 × 5 = 150; c is for English speakers.
        assertEquals(listOf("${cid}a", "${cid}b", "${cid}d"), ranked.map { it.cid })
        assertEquals("50", ranked.first().amount)
    }

    @Test
    fun aProfileListsItsAdsAndTheirVotes() {
        val ad = Writer().string(1, cid).string(2, targetHex()).string(3, "0x1111111111111111111111111111111111111111").string(4, "0xA").toBytes()
        val profile = Writer().bytes(1, ad).bytes(1, Writer().string(1, "other").toBytes()).toBytes()
        assertEquals(
            listOf(ProfileAd(cid, "0x1111111111111111111111111111111111111111"), ProfileAd("other", "")),
            decodeProfileAds(profile),
        )
        assertEquals(emptyList<ProfileAd>(), decodeProfileAds(byteArrayOf(0x0a, 0x05)))
    }

    @Test
    fun anAdHasItsTextLinkAndImages() {
        val thumb = byteArrayOf(1, 2, 3)
        val votingParams = Writer().int(1, 4320).int(4, 300).toBytes()
        val bytes = Writer().string(1, " Idena shop ").string(2, "Buy things").string(3, "https://example.org/x")
            .bytes(4, thumb).int(6, 1).bytes(7, votingParams).toBytes()
        val ad = decodeAd(bytes)!!
        assertEquals("Idena shop", ad.title)
        assertEquals("Buy things", ad.desc)
        assertEquals("https://example.org/x", ad.url)
        assertArrayEquals(thumb, ad.thumb)
        assertNull(ad.media)
        assertNull("no title", decodeAd(Writer().string(2, "text only").toBytes()))
    }

    private fun review(state: Any?, title: String = AD_REVIEW_TITLE, adCid: String = cid, result: Int = 0): JSONArray {
        val fact = JSONObject().put("title", title).put("desc", "My ad").put("adCid", adCid)
            .put("options", JSONArray().put(JSONObject().put("id", 0).put("value", "Approve")).put(JSONObject().put("id", 1).put("value", "Reject")))
        return JSONArray()
            .put(if (state == null) JSONObject().put("key", "state").put("error", "data is nil") else JSONObject().put("key", "state").put("value", state))
            .put(JSONObject().put("key", "fact").put("value", "0x" + hex(fact.toString().toByteArray())))
            .put(JSONObject().put("key", "result").put("value", result))
    }

    @Test
    fun anAdShowsOnceItsReviewApprovedIt() {
        assertEquals("archived", AdReview(over = true, approved = true), adReview(review(2), cid))
        assertEquals("terminated", AdReview(over = true, approved = true), adReview(review(null), cid))
        assertEquals("still voting", AdReview(over = false, approved = false), adReview(review(1), cid))
        assertEquals("rejected", AdReview(over = true, approved = false), adReview(review(2, result = 1), cid))
        assertEquals("another question", AdReview(over = true, approved = false), adReview(review(2, title = "Is this OK?"), cid))
        assertEquals("approval of another ad", AdReview(over = true, approved = false), adReview(review(2, adCid = "other"), cid))
        val noVote = JSONArray().put(JSONObject().put("key", "state").put("error", "data is nil"))
            .put(JSONObject().put("key", "fact").put("error", "data is nil"))
            .put(JSONObject().put("key", "result").put("error", "data is nil"))
        assertNull(adReview(noVote, cid))
    }

    @Test
    fun onlyWebLinksOpenInTheBrowser() {
        assertEquals("https://idena.io/x?a=1", adLink(" https://idena.io/x?a=1 "))
        assertEquals("HTTP://idena.io", adLink("HTTP://idena.io"))
        assertNull(adLink("dna://vote/v1?address=0x1"))
        assertNull(adLink("javascript:alert(1)"))
        assertNull(adLink("https://"))
        assertNull(adLink("https://idena.io/a b"))
    }
}
