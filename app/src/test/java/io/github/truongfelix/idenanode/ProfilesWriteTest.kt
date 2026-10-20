package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class ProfilesWriteTest {
    private fun action(method: String, gasUsed: Long, gasLimit: Long, success: Boolean = true, error: String = "", actionType: Int = 1) =
        JSONObject()
            .put("inputAction", JSONObject().put("actionType", actionType).put("method", method).put("gasLimit", gasLimit))
            .put("success", success).put("error", error).put("gasUsed", gasUsed)

    /** A dry run's receipt as the node gives it: the call, then its promises and callbacks. */
    private fun receipt(vararg subs: JSONObject, gasUsed: Long = 11_486, txFee: String = "0.130088495575164", gasCost: String = "1.0151327433623852") =
        JSONObject().put("success", true).put("gasUsed", gasUsed).put("txFee", txFee).put("gasCost", gasCost)
            .put("actionResult", action("claimName", 710_122, Long.MAX_VALUE).put("subActionResults", JSONArray(subs.toList())))

    // The claim of mainnet's dry run (2026-10-16): the identity promise, then the callback that acts.
    private val claim = receipt(action("", 1_000, 20_000, actionType = 5), action("_claimName", 291_246, 10_000_000))

    @Test
    fun callsAreWrittenAsTheDesktopWritesThem() {
        assertEquals(SocialCall("claimName", "{\"name\":\"felixflx\"}", "0", PROFILE_CONTRACT), ProfileCalls.claimName("felixflx"))
        assertEquals(SocialCall("dropName", null, "0", PROFILE_CONTRACT), ProfileCalls.dropName())
        // "/" stays as it is: the contract's parser reads JSON.stringify's output.
        assertEquals(
            "{\"bio\":\"Line 1\\n\\\"quoted\\\"\",\"picture\":\"ipfs://bafkreiabc\"}",
            ProfileCalls.setProfile("Line 1\n\"quoted\"", "ipfs://bafkreiabc").argument,
        )
    }

    @Test
    fun trustVouchAndChallengeCallsAreWrittenAsTheDesktopWritesThem() {
        val bob = "0xBBBB000000000000000000000000000000000002"
        val low = bob.lowercase()
        assertEquals(SocialCall("trust", "{\"target\":\"$low\"}", "0", PROFILE_CONTRACT), ProfileCalls.trust(bob))
        assertEquals("{\"target\":\"r/idena\"}", ProfileCalls.untrust("r/idena").argument)
        assertEquals("{\"name\":\"felixflx\"}", ProfileCalls.challengeName("felixflx").argument)
        assertEquals(SocialCall("vouch", "{\"member\":\"$low\"}", "0", PROFILE_CONTRACT), ProfileCalls.vouch(bob))
        assertEquals("{\"voucher\":\"$low\"}", ProfileCalls.acceptVouch(bob).argument)
        assertEquals("{\"voucher\":\"$low\"}", ProfileCalls.declineOffer(bob).argument)
        assertEquals("{\"member\":\"$low\"}", ProfileCalls.cancelOffer(bob).argument)
        assertEquals("{\"member\":\"$low\"}", ProfileCalls.endVouch(bob).argument)
        // The member ends its own vouch with an empty member.
        assertEquals("{\"member\":\"\"}", ProfileCalls.endVouch().argument)
    }

    @Test
    fun onlyTheCallsTheContractChargesSendAMembersExtra() {
        val bob = "0xbbbb000000000000000000000000000000000002"
        val paying = listOf(ProfileCalls.claimName("felixflx"), ProfileCalls.setProfile("", ""), ProfileCalls.trust(bob), ProfileCalls.challengeName("felixflx"))
        val free = listOf(
            ProfileCalls.dropName(), ProfileCalls.untrust(bob), ProfileCalls.vouch(bob), ProfileCalls.acceptVouch(bob),
            ProfileCalls.declineOffer(bob), ProfileCalls.cancelOffer(bob), ProfileCalls.endVouch(),
        )
        assertTrue(paying.all(::memberPays))
        assertTrue(free.none(::memberPays))
    }

    @Test
    fun theCallbackGasIsThreeTimesWhatTheDryRunUsedWithinBounds() {
        // Mainnet's claim used 291,246: three times that is below the least the app asks.
        assertEquals(1_000_000L, callbackGas(claim))
        assertNull(keepStateGas(claim))
        assertEquals(1_500_000L, callbackGas(receipt(action("_setProfile", 500_000, 10_000_000))))
        // At least 1,000,000; at most the contract's default.
        assertEquals(1_000_000L, callbackGas(receipt(action("_trust", 100, 10_000_000))))
        assertEquals(10_000_000L, callbackGas(receipt(action("_trust", 9_000_000, 10_000_000))))
        assertNull(callbackGas(receipt(action("", 1_000, 20_000, actionType = 5))))
        val accept = receipt(
            action("", 1_000, 20_000, actionType = 5), action("_keepState", 259_361, 10_000_000),
            action("", 1_000, 20_000, actionType = 5), action("_acceptVouch", 400_000, 10_000_000),
        )
        assertEquals(1_200_000L, callbackGas(accept))
        assertEquals(1_000_000L, keepStateGas(accept))
    }

    @Test
    fun theCallbackGasGoesInTheArgument() {
        val call = ProfileCalls.claimName("felixflx")
        assertEquals("{\"name\":\"felixflx\",\"_gas\":873738}", withCallbackGas(call, 873_738, null).argument)
        assertEquals("{\"name\":\"felixflx\",\"_gas\":1000000,\"_keepGas\":2000000}", withCallbackGas(call, 1_000_000, 2_000_000).argument)
        assertEquals(call, withCallbackGas(call, null, null))
        // A call without argument takes none.
        assertEquals(ProfileCalls.dropName(), withCallbackGas(ProfileCalls.dropName(), 1_000_000, null))
    }

    @Test
    fun theMaxFeeCoversWhatTheCallbacksReserve() {
        // 20,000 + 10,000,000 WASM gas = 100,200 gas reserved, against 11,486 used.
        assertEquals(BigInteger.valueOf(100_200), reservedGas(claim))
        val fee = profileFee(claim)
        assertEquals(BigDecimal("0.130088495575164") + BigDecimal("1.0151327433623852"), fee.fee)
        // (fee + gasCost * 100,200 / 11,486) * 2, rounded up.
        val reserved = BigDecimal("1.0151327433623852") * BigDecimal(100_200) / BigDecimal(11_486)
        assertEquals(((fee.fee + reserved) * BigDecimal(2)).setScale(6, java.math.RoundingMode.UP), fee.maxFee)
        assertTrue(fee.maxFee > fee.fee * BigDecimal(10))
    }

    @Test
    fun aRefusalInACallbackIsARefusal() {
        val refused = receipt(action("", 1_000, 20_000, actionType = 5), action("_claimName", 291_246, 10_000_000, false, "neither validated nor vouched for"))
        assertEquals(listOf("neither validated nor vouched for"), receiptErrors(refused))
        assertEquals(emptyList<String>(), receiptErrors(claim))
        val failed = JSONObject().put("success", false).put("error", "insufficient funds")
        assertEquals(listOf("insufficient funds"), receiptErrors(failed))
    }

    @Test
    fun theExtraAmountIsSentWhenTheContractReadsIt() {
        // Never read, or read in another epoch, or read without an identity (Candidate, 2): the contract reads it.
        assertTrue(readsMemberExtra(null, 232))
        assertTrue(readsMemberExtra("231:7", 232))
        assertTrue(readsMemberExtra("232:2", 232))
        // Read in this epoch as a Newbie (7): it does not.
        assertFalse(readsMemberExtra("232:7", 232))
        // 25,000 gas at the fee per gas, a tenth more, rounded up.
        assertEquals("275", memberExtraAmount(BigInteger("10000000000000000")))
        assertEquals("2.43375", memberExtraAmount(BigInteger("88500000000000")))
    }

    @Test
    fun thePictureIsTheCenteredSquare() {
        assertEquals(Triple(80, 0, 480), squareCrop(640, 480))
        assertEquals(Triple(0, 100, 300), squareCrop(300, 500))
        assertEquals(Triple(0, 0, 256), squareCrop(256, 256))
    }

    private fun webp(chunk: String, flags: Int = 0, size: Int = 64): ByteArray {
        val bytes = ByteArray(size)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        chunk.toByteArray().copyInto(bytes, 12)
        bytes[20] = flags.toByte()
        return bytes
    }

    @Test
    fun onlyAStillWebpOfAtMost32000BytesShows() {
        assertTrue(isShowablePicture(webp("VP8 ")))
        assertTrue(isShowablePicture(webp("VP8X", flags = 0x10)))
        assertFalse(isShowablePicture(webp("VP8X", flags = 0x02)))
        assertTrue(isShowablePicture(webp("VP8 ", size = MAX_PICTURE_BYTES)))
        assertFalse(isShowablePicture(webp("VP8 ", size = MAX_PICTURE_BYTES + 1)))
        assertFalse(isShowablePicture(ByteArray(64) { 0x89.toByte() }))
        assertFalse(isShowablePicture(ByteArray(10)))
    }

    @Test
    fun theConfirmationTellsAMembersExtraAmount() {
        val call = ProfileCalls.claimName("felixflx")
        val pending = PendingPost("Name @felixflx", call, "0xaaaa000000000000000000000000000000000001", PostFee(BigDecimal("1.15"), BigDecimal("20")), null)
        assertFalse("vouched" in confirmText(pending))
        assertEquals("in your public profile", placeOf(pending))
        val member = PendingPost("Name @felixflx", call, pending.from, pending.fee, null, memberExtra = MemberExtra("2.43375", lapsed = false))
        assertTrue("As a vouched member, you also send 2.43375 iDNA" in confirmText(member))
        val lapsed = PendingPost("Name @felixflx", call, pending.from, pending.fee, null, memberExtra = MemberExtra("2.43375", lapsed = true))
        assertTrue("Ending the vouch first avoids it" in confirmText(lapsed))
        assertEquals("on Idena Social", placeOf(PendingPost("Post", SocialPosting.post("{}"), pending.from, pending.fee, null)))
    }

    @Test
    fun anActionIsTheSameWhateverGasItAsks() {
        val bob = "0xbbbb000000000000000000000000000000000002"
        val trust = ProfileCalls.trust(bob)
        assertEquals(actionKey(trust), actionKey(withCallbackGas(trust, 1_000_000, 2_000_000)))
        assertEquals(actionKey(trust), actionKey(withCallbackGas(trust, 3_000_000, null)))
        assertTrue(actionKey(trust) != actionKey(ProfileCalls.untrust(bob)))
        assertTrue(actionKey(trust) != actionKey(ProfileCalls.trust("r/idena")))
        // A call without argument, or whose argument held only the gas.
        assertEquals(actionKey(ProfileCalls.dropName()), actionKey(ProfileCalls.dropName()))
        val empty = SocialCall("x", "{}", "0", PROFILE_CONTRACT)
        assertEquals(actionKey(empty), actionKey(withCallbackGas(empty, 1_000_000, 1_000_000)))
    }
}
