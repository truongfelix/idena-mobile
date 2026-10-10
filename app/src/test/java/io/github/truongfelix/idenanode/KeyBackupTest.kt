package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyBackupTest {
    @Test
    fun aBackupPasswordHasTwelveCharactersTypedTheSameTwice() {
        assertEquals("At least 12 characters.", passwordProblem("elevenchars", "elevenchars"))
        assertNull(passwordProblem("twelve chars", "twelve chars"))
        assertEquals("The two passwords differ.", passwordProblem("twelve chars", "twelve charz"))
        // Characters, not UTF-16 units: 11 emoji are 22 units but 11 characters.
        val emoji = "😀".repeat(11)
        assertEquals("At least 12 characters.", passwordProblem(emoji, emoji))
        assertNull(passwordProblem("apple-river-cloud-tiger-lemon-stone", "apple-river-cloud-tiger-lemon-stone"))
    }

    @Test
    fun theBackupFileIsNamedAfterTheAccount() {
        assertEquals("idena-key-0x3f2a91c4.txt", backupFileName("0x3F2A91C4bB0e5c1D2a7F8e9b0C1d2E3f4A5b6C7d"))
    }

    @Test
    fun theCheckSaysWhetherTheBackupOpensAsTheSameAccount() {
        val account = "0x3F2A91C4bB0e5c1D2a7F8e9b0C1d2E3f4A5b6C7d"
        // Addresses compare without their letter case (the checksum's capitals).
        assertEquals("✓ It opens: 0x3f2a…6c7d", backupCheckText(account, account.lowercase(), null))
        assertEquals("✓ It opens: 0x3F2A…6C7d", backupCheckText(account, account, null))
        assertEquals(
            "This backup is of another account (0x1111…2222).",
            backupCheckText(account, "0x1111000000000000000000000000000000002222", null),
        )
        assertEquals(
            "It does not open: the password does not open this key.",
            backupCheckText(account, null, "the password does not open this key"),
        )
    }
}
