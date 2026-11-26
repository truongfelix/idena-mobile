package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileVouchingTest {
    private val alice = "0xaaaa000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val holder = "0xcccc000000000000000000000000000000000003"

    private fun profile(state: String, voucher: String? = null, voucherState: String? = null) =
        ContractProfile(holder, "carol", null, null, 0, 0, voucher, state, voucherState)

    @Test
    fun aVouchedMemberReadsVouchedAndAnyOtherAccountItsOwnStatus() {
        assertEquals("Vouched", memberStatus("Undefined", profile("Undefined", alice, "Human")))
        assertEquals("Vouched", memberStatus("Killed", profile("Killed", alice, "Newbie")))
        // Invite and Candidate keep their status; the state read now decides over an older profile.
        assertEquals("Candidate", memberStatus("Candidate", profile("Candidate", alice, "Newbie")))
        assertEquals("Invite", memberStatus("Invite", profile("Undefined", alice, "Human")))
        assertEquals("Newbie", memberStatus("Newbie", profile("Undefined", alice, "Human")))
        // A voucher out of its validation, a member who validated, no profile read: the identity's own status.
        assertEquals("Not validated", memberStatus("Undefined", profile("Undefined", alice, "Suspended")))
        assertEquals("Newbie", memberStatus("Newbie", profile("Newbie", alice, "Human")))
        assertEquals("Not validated", memberStatus("Undefined", null))
        assertEquals(setOf(holder), vouchedMembers(listOf(profile("Undefined", alice, "Human"))))
        assertEquals(emptySet<String>(), vouchedMembers(listOf(profile("Undefined", alice, "Zombie"))))
    }

    @Test
    fun aChallengeStartsWithItsFirstCall() {
        assertEquals(ChallengeStep(1, canCall = true, frees = false, since = 10), challengeStep(null, 10, holder))
    }

    @Test
    fun aChallengeGoesOnInTheNextEpochsAndFreesTheNameAtTheThirdCall() {
        assertEquals(ChallengeStep(2, canCall = true, frees = false, since = 10), challengeStep(parseChallenge("10:10:$holder"), 11, holder))
        assertEquals(ChallengeStep(3, canCall = true, frees = true, since = 10), challengeStep(parseChallenge("10:11:$holder"), 12, holder))
    }

    @Test
    fun aChallengeWaitsForTheNextEpochAfterACallInThisOne() {
        assertEquals(ChallengeStep(2, canCall = false, frees = false, since = 10), challengeStep(parseChallenge("10:10:$holder"), 10, holder))
        assertEquals(ChallengeStep(3, canCall = false, frees = false, since = 10), challengeStep(parseChallenge("10:11:$holder"), 11, holder))
    }

    @Test
    fun aChallengeStartsAgainAfterASkippedEpochOrForAnotherHolder() {
        val first = parseChallenge("10:10:$holder")
        assertEquals(ChallengeStep(1, canCall = true, frees = false, since = 12), challengeStep(first, 12, holder))
        assertEquals(ChallengeStep(1, canCall = true, frees = false, since = 11), challengeStep(first, 11, alice))
    }

    @Test
    fun aSavedChallengeIsReadOrNull() {
        assertEquals(NameChallenge(10, 11, holder), parseChallenge("10:11:${holder.uppercase().replace("0X", "0x")}"))
        assertNull(parseChallenge(null))
        assertNull(parseChallenge(""))
        assertNull(parseChallenge("10:11"))
        assertNull(parseChallenge("x:11:$holder"))
    }

    @Test
    fun theMembersAreCappedByTheVouchersState() {
        assertEquals(listOf(5, 3, 1, 0, 0), listOf("Human", "Verified", "Newbie", "Suspended", null).map(::vouchCap))
    }

    @Test
    fun aMemberActsOnlyWithoutAnIdentityAndWithAValidatedVoucher() {
        assertTrue(isActingMember(profile("Undefined", bob, "Human")))
        assertTrue(isActingMember(profile("Killed", bob, "Newbie")))
        assertFalse(isActingMember(profile("Undefined", bob, "Suspended")))
        // A member who validated, or got an identity and lost it, no longer acts as one: the contract ends the vouch.
        assertFalse(isActingMember(profile("Newbie", bob, "Human")))
        assertFalse(isActingMember(profile("Suspended", bob, "Human")))
        assertFalse(isActingMember(profile("Undefined")))
        assertFalse(isActingMember(null))
    }

    @Test
    fun aToFieldHoldsAnAddressANameOrNeither() {
        assertEquals(Recipient.Empty, parseRecipient("  "))
        assertEquals(Recipient.Address(alice), parseRecipient(" ${alice.uppercase().replace("0X", "0x")} "))
        assertEquals(Recipient.Name("felix_flx"), parseRecipient("@Felix_Flx"))
        assertEquals(Recipient.Invalid, parseRecipient("@ab"))
        assertEquals(Recipient.Invalid, parseRecipient("felixflx"))
        assertEquals(Recipient.Invalid, parseRecipient("@felix-flx"))
        assertEquals(Recipient.Invalid, parseRecipient("0x1234"))
    }

    @Test
    fun aNameSendsToItsHolderOnlyInGoodStanding() {
        val name = Recipient.Name("carol")
        assertEquals(alice, recipientAddress(Recipient.Address(alice), null))
        assertEquals(holder, recipientAddress(name, ResolvedName(holder, profile("Human"))))
        assertEquals(holder, recipientAddress(name, ResolvedName(holder, profile("Undefined", bob, "Verified"))))
        // Out of good standing the name is hidden and may change hands.
        assertNull(recipientAddress(name, ResolvedName(holder, profile("Suspended", bob, "Human"))))
        assertNull(recipientAddress(name, ResolvedName(holder, null)))
        assertNull(recipientAddress(name, ResolvedName(null, null)))
        assertNull(recipientAddress(name, null))
        assertNull(recipientAddress(Recipient.Invalid, null))
    }

    @Test
    fun theOffersAccountMadeAreReadFromAllOpenOffers() {
        assertEquals(VouchEntry(alice, 230), offerBy("$alice:$bob", "230", bob.uppercase().replace("0X", "0x")))
        assertNull("another voucher's", offerBy("$alice:$holder", "230", bob))
        assertNull("not an offer's key", offerBy(alice, "230", bob))
    }

    @Test
    fun theTrustsAnAccountGivesAreReadFromAllTrusts() {
        assertEquals(VouchEntry(alice, 231), trustGivenBy("$alice:$bob", "231", bob))
        assertEquals("a topic, given as a member", VouchEntry("r/idena", 232), trustGivenBy("r/idena:$bob", "232:v", bob))
        assertNull("another giver's", trustGivenBy("$alice:$holder", "231", bob))
        assertNull(trustGivenBy("nothing", "1", bob))
    }

    @Test
    fun theGetVouchedCardShowsToAnAccountWithoutIdentityNorVoucher() {
        assertTrue("no identity", wantsVouch("Undefined", null))
        assertTrue("an empty state is Undefined", wantsVouch("", null))
        assertTrue("killed", wantsVouch("Killed", null))
        assertFalse("already vouched for", wantsVouch("Undefined", bob))
        assertFalse("validated", wantsVouch("Verified", null))
        assertFalse("suspended comes back at the next validation", wantsVouch("Suspended", null))
    }

    @Test
    fun theProfileCardTakesOnlyItsOwnAccountsProfile() {
        // B-324: after a key import the card may still hold the previous account's profile; it says nothing of the new one.
        val vouched = profile("Undefined", alice, "Human")
        assertNull(profileOf(bob, vouched))
        assertEquals("Not validated", memberStatus("Undefined", profileOf(bob, vouched)))
        assertEquals(vouched, profileOf(holder.uppercase().replace("0X", "0x"), vouched))
        assertNull(profileOf(holder, null))
    }
}
