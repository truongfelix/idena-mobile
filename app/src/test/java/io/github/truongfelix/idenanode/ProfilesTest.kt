package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProfilesTest {
    private val alice = "0xaaaa000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val carol = "0xcccc000000000000000000000000000000000003"

    private fun profile(state: String?, voucher: String? = null, voucherState: String? = null, name: String? = "alice", bio: String? = "hi", picture: String? = null) =
        ContractProfile(alice, name, bio, picture, 0, 0, voucher, state, voucherState)

    @Test
    fun goodStandingIsTheContractsRule() {
        assertTrue(inStanding("Newbie", null, null))
        assertTrue(inStanding("Human", bob, "Suspended"))
        // A member without an identity, while its voucher is validated.
        assertTrue(inStanding("Undefined", bob, "Verified"))
        assertTrue(inStanding("Candidate", bob, "Human"))
        assertFalse(inStanding("Undefined", bob, "Suspended"))
        assertFalse(inStanding("Undefined", null, null))
        // A member who got an identity and lost it is not vouched for any more: the contract ends such a vouch.
        assertFalse(inStanding("Suspended", bob, "Human"))
        assertFalse(inStanding("Zombie", bob, "Human"))
    }

    @Test
    fun outOfStandingTheNameBioAndPictureAreHidden() {
        val shown = profile("Verified", picture = "ipfs://bafkreibsalgnurd7rx3nvkawbj7k7bfktr43ct5sw4fpr5vb7w4qm5h4ym")
        assertEquals("@alice", shown.shownName)
        assertEquals("hi", shown.shownBio)
        assertEquals("bafkreibsalgnurd7rx3nvkawbj7k7bfktr43ct5sw4fpr5vb7w4qm5h4ym", shown.shownPicture)
        val hidden = shown.copy(state = "Suspended")
        assertNull(hidden.shownName)
        assertNull(hidden.shownBio)
        assertNull(hidden.shownPicture)
        // A picture that is not an "ipfs://" reference is never shown.
        assertNull(profile("Human", picture = "https://example.com/a.webp").shownPicture)
    }

    @Test
    fun aVoucherShowsOnlyWhileTheAccountHasNoIdentity() {
        assertEquals(bob, profile("Undefined", bob, "Human").shownVoucher)
        assertNull(profile("Newbie", bob, "Human").shownVoucher)
        assertNull(profile("Undefined").shownVoucher)
    }

    @Test
    fun aProfileIsReadFromItsMaps() {
        val states = mapOf(alice to "Undefined", bob to "Human")
        val p = contractProfile(alice, listOf("alice", "my bio", "ipfs://x", "3", "1", bob.uppercase().replace("0X", "0x")), states)
        assertEquals(ContractProfile(alice, "alice", "my bio", "ipfs://x", 3, 1, bob, "Undefined", "Human"), p)
        val empty = contractProfile(carol, List(PROFILE_MAPS.size) { null }, mapOf(carol to "Newbie"))
        assertEquals(ContractProfile(carol, null, null, null, 0, 0, null, "Newbie", null), empty)
        assertThrows(IllegalArgumentException::class.java) { contractProfile(carol, listOf("a"), emptyMap()) }
    }

    @Test
    fun aKeyNeverWrittenIsNullAndAnyOtherErrorThrows() {
        assertEquals("felixflx", readMapValue(Result.success("felixflx")))
        assertNull(readMapValue(Result.failure(IOException("data is nil"))))
        assertThrows(IOException::class.java) { readMapValue(Result.failure(IOException("the provided API key is invalid"))) }
    }

    @Test
    fun readMapTakesTheKeyAsUtf8Hex() {
        assertEquals("0x66656c6978666c78", utf8Hex("felixflx"))
        val params = readMapParams(PROFILE_CONTRACT, "n:", "felixflx")
        assertEquals("""["$PROFILE_CONTRACT","n:","0x66656c6978666c78","string"]""", params.toString())
    }

    @Test
    fun theNamesGivenOnThePhoneComeBeforeTheClaimedOnes() {
        val claimed = shownNames(listOf(profile("Human"), ContractProfile(bob, "bob", null, null, 0, 0, null, "Suspended", null)))
        // Bob is out of good standing: his claimed name is not shown.
        assertEquals(mapOf(alice to "@alice"), claimed)
        assertEquals(mapOf(alice to "Al", carol to "Carol"), mergedNames(mapOf(alice to "Al", carol to "Carol"), claimed))
        assertEquals(mapOf(alice to "@alice"), mergedNames(emptyMap(), claimed))
    }

    @Test
    fun trustCountsEachGiverByItsStandingNow() {
        val givers = listOf(trustGiver(alice, "230"), trustGiver(bob, "231:v"), trustGiver(carol, "232"))
        assertEquals(TrustGiver(bob, 231, true), givers[1])
        // Alice lost her identity; Bob, given as a member, validated since; Carol is a member whose voucher is validated.
        val states = mapOf(alice to "Killed", bob to "Newbie", carol to "Undefined", "0xv" to "Human")
        val count = countTrust(givers, states, mapOf(carol to "0xv"))
        assertEquals(listOf(TrustGiver(bob, 231, false), TrustGiver(carol, 232, true)), count.counted)
        assertEquals("the sum", "Trusted by 2", trustText(count))
        assertEquals("1 identity · 1 vouched member", trustSplit(count))
        assertEquals(mapOf(carol to "0xv"), count.vouchers)
        assertEquals("Trusted by 1", trustText(countTrust(givers.take(2), states, emptyMap())))
        assertNull(trustText(countTrust(emptyList(), emptyMap(), emptyMap())))
        // A Suspended or Zombie identity still counts: it can come back at the next validation.
        assertEquals(2, countTrust(givers.take(2), mapOf(alice to "Zombie", bob to "Suspended"), emptyMap()).identities)
    }

    @Test
    fun anAccountsOwnTrustStaysGivenWhileItDoesNotCount() {
        val givers = listOf(trustGiver(alice, "230"), trustGiver(bob, "231"))
        // Alice lost her identity: her Trust no longer counts, and she can still take it back.
        val count = countTrust(givers, mapOf(alice to "Killed", bob to "Human"), emptyMap())
        assertEquals(1, count.identities)
        assertTrue(count.givenBy(alice.uppercase().replace("0X", "0x")))
        assertTrue(count.givenBy(bob))
        assertFalse(count.givenBy(carol))
        assertFalse(count.givenBy(null))
    }

    @Test
    fun aBatchKeepsEachCallsResultInItsOrder() {
        val responses = JSONArray()
            .put(JSONObject().put("id", 2).put("result", "c"))
            .put(JSONObject().put("id", 0).put("result", "a"))
            .put(JSONObject().put("id", 1).put("error", JSONObject().put("message", "data is nil")))
        val results = settledResults(responses, 4)
        assertEquals("a", results[0].getOrNull())
        assertEquals("data is nil", results[1].exceptionOrNull()?.message)
        assertEquals("c", results[2].getOrNull())
        // A call the node did not answer is an error, never a null result.
        assertEquals("no answer", results[3].exceptionOrNull()?.message)
        assertNull(settledResults(JSONArray().put(JSONObject().put("id", 0).put("result", JSONObject.NULL)), 1)[0].getOrThrow())
    }
}
