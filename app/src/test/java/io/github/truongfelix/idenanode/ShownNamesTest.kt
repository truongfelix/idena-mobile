package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Test

class ShownNamesTest {
    private val alice = "0xaaaa000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val carol = "0xcccc000000000000000000000000000000000003"

    private fun profile(address: String, name: String, state: String) = ContractProfile(address, name, null, null, 0, 0, null, state, null)

    @Test
    fun aNameGivenOnThePhoneComesBeforeTheClaimedOneWhichShowsOnlyInGoodStanding() {
        val people = mapOf(alice to Person(alice, "Mum"))
        val profiles = listOf(profile(alice, "alice", "Human"), profile(bob, "bob", "Verified"), profile(carol, "carol", "Suspended"))
        assertEquals(
            mapOf(alice to "Mum", bob to "@bob"),
            namesFor(listOf(alice, bob.uppercase().replace("0X", "0x"), carol), people, profiles),
        )
    }

    @Test
    fun aLineShowsTheNameBesideTheShortAddress() {
        val names = mapOf(alice to "@alice")
        assertEquals("@alice · 0xaaaa…0001", namedAddress(alice, names, bob))
        assertEquals("0xbbbb…0002 (you)", namedAddress(bob, names, bob.uppercase().replace("0X", "0x")))
        assertEquals("0xcccc…0003", namedAddress(carol, names, null))
    }
}
