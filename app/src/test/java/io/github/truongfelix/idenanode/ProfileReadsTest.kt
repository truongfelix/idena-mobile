package io.github.truongfelix.idenanode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ProfileReadsTest {
    private fun identity(age: Int) = ProfileIdentity("Human", age, BigDecimal.TEN)

    @After
    fun forget() = ProfileReads.clear()

    @Test
    fun aProfileOpensWithItsLastAnswer() {
        ProfileReads.keep("0xAbC", identity(5))
        assertEquals(identity(5), ProfileReads.identity("0xabc"))
        assertNull(ProfileReads.identity("0xdef"))
    }

    @Test
    fun keepsTheLastOpenedProfiles() {
        ProfileReads.keep("0x0", identity(0))
        for (n in 1..ProfileReads.MAX) {
            ProfileReads.identity("0x0")
            ProfileReads.keep("0x$n", identity(n))
        }
        // 0x0 was read the most recently of the first ones: 0x1 went instead.
        assertEquals(identity(0), ProfileReads.identity("0x0"))
        assertNull(ProfileReads.identity("0x1"))
    }

    @Test
    fun aFailedReadKeepsTheLineShown() {
        val shown = Result.success(identity(3))
        val failed = Result.failure<ProfileIdentity>(RuntimeException("no answer"))
        assertEquals(shown, afterRead(failed, shown))
        assertEquals(failed, afterRead(failed, null))
        val read = Result.success(identity(4))
        assertEquals(read, afterRead(read, shown))
    }

    @Test
    fun anotherAccountForgetsThem() {
        ProfileReads.keep("0xabc", identity(5))
        ProfileReads.clear()
        assertNull(ProfileReads.identity("0xabc"))
    }
}
