package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The expected bytes come from the desktop's own encoder (renderer/shared/models/proto/models_pb.js, 2026-10-10). */
class AdFormatsTest {
    private fun hex(bytes: ByteArray) = bytesToHex(bytes).removePrefix("0x")

    @Test
    fun anAdIsWrittenAsTheDesktopWritesIt() {
        val ad = encodeAd("Fresh bread", "Warm loaves é", "https://example.com", byteArrayOf(1, 2, 3), byteArrayOf(-1, 0, 7, 8))
        assertEquals(
            "0a0b4672657368206272656164120e5761726d206c6f6176657320c3a91a1368747470733a2f2f6578616d706c652e636f6d" +
                "22030102032a04ff0007083a0b08e02110e021180120ac02",
            hex(ad),
        )
        // And the phone's own reader takes it back.
        val read = decodeAd(ad)!!
        assertEquals("Warm loaves é", read.desc)
        assertEquals("https://example.com", read.url)
    }

    @Test
    fun audiencesLeaveOutWhatIsUnset() {
        assertEquals("0a02656e10051a07616e64726f69642064", targetHex(AdTarget("en", 5, "android", 100)))
        assertEquals("", targetHex(AdTarget()))
        assertEquals("0a026672", targetHex(AdTarget(language = "fr")))
        assertEquals(AdTarget("en", 5, "android", 100), decodeTarget(encodeTarget(AdTarget("en", 5, "android", 100))))
    }

    @Test
    fun aBurnNamesTheAdAndItsAudience() {
        val key = adBurnKey("bafkreiexample", "0a02656e10051a07616e64726f69642064")
        assertEquals("0a0e6261666b7265696578616d706c65122230613032363536653130303531613037363136653634373236663639363432303634", key)
        assertEquals(
            "0a683061306536323631363636623732363536393635373836313664373036633635313232323330363133303332333633353336363533" +
                "313330333033353331363133303337333633313336363533363334333733323336363633363339333633343332333033363334",
            hex(burnPayload(key)),
        )
        // The banner's reader finds the ad and the audience in it.
        assertEquals("bafkreiexample" to AdTarget("en", 5, "android", 100), decodeBurnKey(key))
    }

    private val profileHex =
        "0a88010a0a6261666b7265696f6e651222306130323635366531303035316130373631366536343732366636393634323036" +
            "341a2a307831313131313131313131313131313131313131313131313131313131313131313131313131313131222a307861" +
            "6161616161616161616161616161616161616161616161616161616161616161616161616161610a640a0a6261666b726569" +
            "74776f1a2a307832323232323232323232323232323232323232323232323232323232323232323232323232323232222a30" +
            "7861616161616161616161616161616161616161616161616161616161616161616161616161616161"
    private val author = "0x" + "a".repeat(40)
    private val entries = listOf(
        ProfileEntry("bafkreione", "0a02656e10051a07616e64726f69642064", "0x" + "1".repeat(40), author),
        ProfileEntry("bafkreitwo", "", "0x" + "2".repeat(40), author),
    )

    @Test
    fun theProfileListIsReadAndWrittenWholeAsTheDesktopDoes() {
        assertEquals(profileHex, hex(encodeProfile(entries)))
        assertEquals(entries, decodeProfile(hexToBytes(profileHex)))
        assertEquals(emptyList<ProfileEntry>(), decodeProfile(ByteArray(0)))
    }

    @Test
    fun aProfileThatIsNoListOfAdsIsRefused() {
        // Field 1 as a number, an entry without a cid, and bytes that are no message: never read as an empty list.
        assertThrows(IllegalArgumentException::class.java) { decodeProfile(byteArrayOf(0x08, 0x01)) }
        assertThrows(IllegalArgumentException::class.java) { decodeProfile(ProtoWriter().message(1, ProtoWriter().string(3, "0x1").toByteArray()).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { decodeProfile(byteArrayOf(0x0a, 0x05, 0x01)) }
    }

    @Test
    fun aProfileChangeCarriesTheCidBytes() {
        assertEquals(
            "0a2401551220c7d01489080858c500065836c658f847a6ca67c4864619212be4f8200e4bbace",
            hex(changeProfilePayload("bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy")),
        )
    }
}
