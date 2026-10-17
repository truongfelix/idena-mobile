package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ProfileCircleTest {
    private val inviter = "0xaaaa000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val carol = "0xcccc000000000000000000000000000000000003"
    private val hash = "0x" + "ab".repeat(32)

    @Test
    fun anIdentityCarriesItsInviterAndInviteesAsTheNodeWritesThem() {
        // dna_identity: "inviter" has JSON names, each invitee is a state.TxAddr without them ("TxHash", "Address").
        val json = JSONObject(
            """{"address":"$bob","state":"Newbie","age":2,"stake":"12.5",
               "inviter":{"txHash":"$hash","address":"${inviter.uppercase().replace("0X", "0x")}","epochHeight":7},
               "invitees":[{"TxHash":"$hash","Address":"$carol"},{"TxHash":"$hash","Address":"0x12"},{"TxHash":"$hash","Address":"$carol"}]}""",
        )
        val identity = ProfileIdentity.fromJson(json)
        assertEquals(ProfileIdentity("Newbie", 2, BigDecimal("12.5"), inviter, listOf(carol)), identity)
    }

    @Test
    fun anIdentityWithoutLinksHasNone() {
        val identity = ProfileIdentity.fromJson(JSONObject("""{"state":"Human","age":12,"stake":"1","inviter":null,"invitees":null}"""))
        assertNull(identity.inviter)
        assertEquals(emptyList<String>(), identity.invitees)
    }

    @Test
    fun aListShowsTheStateAndTheAgeOnceValidated() {
        assertEquals("Newbie · age 2", circleStatus(ProfileIdentity("Newbie", 2, BigDecimal.ZERO)))
        assertEquals("Candidate", circleStatus(ProfileIdentity("Candidate", 0, BigDecimal.ZERO)))
        assertEquals("Not validated", circleStatus(ProfileIdentity("Undefined", 0, BigDecimal.ZERO)))
        assertEquals("Not validated", circleStatus(null))
    }
}
