package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationsTest {
    private val code = "3f9a" + "0".repeat(56) + "0c21"

    @Test
    fun codesAreTheInvitationKey() {
        assertEquals(code, parseInvitationCode(code))
        assertEquals("pasted with 0x, spaces and upper case", code, parseInvitationCode("  0x" + code.uppercase().chunked(16).joinToString(" ") + "\n"))
        assertNull("too short", parseInvitationCode(code.drop(2)))
        assertNull("not hex", parseInvitationCode(code.dropLast(1) + "g"))
        assertNull("the zero key", parseInvitationCode("0".repeat(64)))
        assertNull(parseInvitationCode(""))
    }

    @Test
    fun theNodesRules() {
        assertTrue(canSendInvite(2, "None"))
        assertFalse("none left", canSendInvite(0, "None"))
        assertFalse("from the flip lottery on", canSendInvite(2, "FlipLottery"))
        assertTrue(canActivateCode("Undefined", "None"))
        assertFalse("already an identity", canActivateCode("Candidate", "None"))
        assertFalse("a killed address cannot be activated", canActivateCode("Killed", "None"))
        assertFalse(canActivateCode("Undefined", "ShortSession"))
        assertTrue(canCancelInvite("Invite", "None"))
        assertTrue(canCancelInvite("Candidate", "None"))
        assertFalse("validated: too late", canCancelInvite("Newbie", "None"))
        assertFalse(canCancelInvite("Invite", "FlipLottery"))
        assertFalse(canCancelInvite(null, "None"))
    }

    @Test
    fun invitationsAreFollowedThroughTheChain() {
        val toCode = SentInvite("Alice", "0xaaaa000000000000000000000000000000000001", code, "0xt1", 230, sentAt = 1)
        val toAddress = SentInvite("Bob", "0xbbbb000000000000000000000000000000000002", null, "0xt2", 230, sentAt = 2)
        val waiting = SentInvite("", "0xcccc000000000000000000000000000000000003", code, "0xt3", 230, sentAt = 3)
        val activated = "0xdddd000000000000000000000000000000000004"
        val desktop = Invitee("0xt9", "0xeeee000000000000000000000000000000000005")
        // Alice's code was activated: the invitee points to the new address; the code's address is Killed.
        val states = mapOf(
            toCode.receiver to "Killed",
            activated to "Candidate",
            toAddress.receiver to "Invite",
            desktop.address to "Newbie",
        )
        val rows = inviteRows(listOf(toCode, toAddress, waiting), listOf(Invitee("0xt1", activated), desktop), states, mined = setOf("0xt1", "0xt2"))
        assertEquals("newest first, then those of another app", listOf("0xt3", "0xt2", "0xt1", null), rows.map { it.sent?.txHash })
        val (third, bob, alice, other) = rows
        assertTrue(third.pending)
        assertEquals("Sent, waiting for a block", inviteStatus(third))
        assertEquals(toAddress.receiver, bob.address)
        assertEquals("Not accepted yet", inviteStatus(bob))
        assertEquals("the activated address, to follow and cancel", activated, alice.address)
        assertEquals("Activated: candidate for the next validation", inviteStatus(alice))
        assertEquals(desktop.address, other.address)
        assertEquals("Validated: Newbie", inviteStatus(other))
        val unused = inviteRows(listOf(toCode), emptyList(), mapOf(toCode.receiver to "Invite"), setOf("0xt1")).single()
        assertEquals("Not activated yet: give the code", inviteStatus(unused))
        val cancelled = inviteRows(listOf(toCode), emptyList(), mapOf(toCode.receiver to "Killed"), setOf("0xt1")).single()
        assertEquals("Cancelled or spoiled", inviteStatus(cancelled))
    }

    @Test
    fun onlyThisEpochsInvitationsAreKept() {
        val now = SentInvite("Alice", "0xaaaa000000000000000000000000000000000001", code, "0xt1", 230, 1)
        val old = now.copy(txHash = "0xt0", epoch = 229)
        val json = sentInvitesJson(listOf(old, now))
        assertEquals(listOf(now), parseSentInvites(json, 230))
        assertEquals(emptyList<SentInvite>(), parseSentInvites("not json", 230))
        val toAddress = now.copy(code = null)
        assertEquals(listOf(toAddress), parseSentInvites(sentInvitesJson(listOf(toAddress)), 230))
    }

    @Test
    fun inviteesAsTheNodeGivesThem() {
        val array = JSONArray()
            .put(JSONObject().put("TxHash", "0xAB").put("Address", "0xDDDD000000000000000000000000000000000004"))
            .put(JSONObject().put("TxHash", "0xcd").put("Address", "not an address"))
        assertEquals(listOf(Invitee("0xab", "0xdddd000000000000000000000000000000000004")), parseInvitees(array))
        assertEquals(emptyList<Invitee>(), parseInvitees(null))
    }
}
