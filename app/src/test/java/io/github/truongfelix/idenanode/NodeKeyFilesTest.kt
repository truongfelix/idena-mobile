package io.github.truongfelix.idenanode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class NodeKeyFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** [gcmWrap] under a software key, as the Keystore's key does on the phone. */
    private class SoftwareWrapper(val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) :
        KeyWrapper {
        override fun wrap(plain: ByteArray) = gcmWrap(key, plain)

        override fun unwrap(wrapped: ByteArray) = gcmUnwrap(key, wrapped)
    }

    // The node's key files: the hex of the key, maybe with a line break (Mobile.readKeyFile).
    private fun readHex(file: File): ByteArray {
        val text = file.readText().trim()
        require(text.length == 64) { "not a key file" }
        return ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun newKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun hex(key: ByteArray) = key.joinToString("") { "%02x".format(it) }

    private fun files(dir: File, wrapper: KeyWrapper = SoftwareWrapper()) = NodeKeyFiles(dir, wrapper, ::readHex)

    private fun plainFile(dir: File, name: String, key: ByteArray) =
        File(dir, "datadir/keystore/$name").also {
            it.parentFile!!.mkdirs()
            it.writeText(hex(key))
        }

    @Test
    fun noKeyFileIsNoKey() {
        val keys = files(folder.root)
        assertFalse(keys.hasKey())
        assertNull(keys.read())
        assertFalse(keys.encrypted.exists())
    }

    @Test
    fun thePlainKeyAndItsBackupsAreEncryptedThenDeleted() {
        val dir = folder.root
        val wrapper = SoftwareWrapper()
        val key = newKey()
        val replaced = listOf(newKey(), newKey())
        val plain = plainFile(dir, "nodekey", key)
        val plainBackups = replaced.mapIndexed { i, old -> plainFile(dir, "backup-12345$i", old) }
        val keys = files(dir, wrapper)
        assertTrue(keys.hasKey())

        assertArrayEquals(key, keys.read())

        assertFalse(plain.exists())
        plainBackups.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(emptyList<String>(), File(dir, "datadir/keystore").list()!!.toList())
        assertFalse(hex(keys.encrypted.readBytes()).contains(hex(key)))
        assertArrayEquals(key, wrapper.unwrap(keys.encrypted.readBytes()))
        val backups = keys.backups.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("backup-123450.enc", "backup-123451.enc"), backups.map { it.name })
        backups.zip(replaced).forEach { (file, old) -> assertArrayEquals(old, wrapper.unwrap(file.readBytes())) }
        // Read again: nothing left to convert.
        assertArrayEquals(key, keys.read())
        assertEquals(2, keys.backups.listFiles()!!.size)
    }

    @Test
    fun anImportKeepsTheKeyItReplaces() {
        val wrapper = SoftwareWrapper()
        val keys = files(folder.root, wrapper)
        val first = newKey()
        val second = newKey()
        keys.write(first)
        assertTrue(keys.hasKey())
        assertNull(keys.backups.listFiles())
        keys.write(second)
        assertArrayEquals(second, keys.read())
        val backups = keys.backups.listFiles()!!
        assertEquals(1, backups.size)
        assertTrue(backups[0].name, backups[0].name.matches(Regex("nodekey-\\d+-0\\.enc")))
        assertArrayEquals(first, wrapper.unwrap(backups[0].readBytes()))
        // The same key again: no backup of it.
        keys.write(second)
        assertEquals(1, keys.backups.listFiles()!!.size)
    }

    @Test
    fun aPlainKeyNextToAnEncryptedOneWins() {
        // 0.1.76 encrypted A, then an older version imported B in plain.
        val dir = folder.root
        val wrapper = SoftwareWrapper()
        val keys = files(dir, wrapper)
        val a = newKey()
        val b = newKey()
        keys.write(a)
        plainFile(dir, "nodekey", b)

        assertArrayEquals(b, keys.read())

        val backups = keys.backups.listFiles()!!
        assertEquals(1, backups.size)
        assertArrayEquals(a, wrapper.unwrap(backups[0].readBytes()))
        // The older version still had A: nothing is kept twice.
        plainFile(dir, "nodekey", b)
        assertArrayEquals(b, keys.read())
        assertEquals(1, keys.backups.listFiles()!!.size)
    }

    @Test
    fun anUnreadablePlainKeyStaysWhereItIs() {
        val dir = folder.root
        val plain = File(dir, "datadir/keystore/nodekey").also {
            it.parentFile!!.mkdirs()
            it.writeText("not-a-private-key")
        }
        val keys = files(dir)
        assertTrue(keys.hasKey())
        try {
            keys.read()
            fail("read an unreadable key")
        } catch (e: IllegalArgumentException) {
            assertEquals("not a key file", e.message)
        }
        assertEquals("not-a-private-key", plain.readText())
        assertFalse(keys.encrypted.exists())
    }

    @Test
    fun aKeyThatDoesNotReadBackKeepsThePlainFile() {
        val dir = folder.root
        val key = newKey()
        val plain = plainFile(dir, "nodekey", key)
        // A wrapper whose output opens to something else.
        val broken = object : KeyWrapper {
            val inner = SoftwareWrapper()

            override fun wrap(plain: ByteArray) = inner.wrap(plain.copyOf().also { it[0] = (it[0] + 1).toByte() })

            override fun unwrap(wrapped: ByteArray) = inner.unwrap(wrapped)
        }
        try {
            files(dir, broken).read()
            fail("a key that does not read back was taken")
        } catch (e: java.io.IOException) {
            assertEquals("The key written does not read back", e.message)
        }
        assertEquals(hex(key), plain.readText())
    }

    @Test
    fun aKeyEncryptedUnderAnotherKeystoreKeyIsKeptAtTheNextImport() {
        // The Keystore's key gone (a new one made): the file no longer opens; an import keeps it.
        val dir = folder.root
        val old = newKey()
        files(dir).write(old)
        val unreadable = files(dir).encrypted.readBytes()
        val wrapper = SoftwareWrapper()
        val keys = files(dir, wrapper)
        try {
            keys.read()
            fail("opened under another key")
        } catch (e: Exception) {
            // AEADBadTagException
        }
        val next = newKey()
        keys.write(next)
        assertArrayEquals(next, keys.read())
        assertArrayEquals(unreadable, keys.backups.listFiles()!!.single().readBytes())
    }

    @Test
    fun theFormatTakesARandomNonceAndRefusesAChange() {
        val wrapper = SoftwareWrapper()
        val key = newKey()
        val one = wrapper.wrap(key)
        val two = wrapper.wrap(key)
        assertNotEquals(hex(one), hex(two))
        assertEquals(2 + 12 + 32 + 16, one.size)
        assertArrayEquals(key, wrapper.unwrap(two))
        for (i in one.indices) {
            val changed = one.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try {
                wrapper.unwrap(changed)
                fail("byte $i changed and the key opened")
            } catch (e: Exception) {
                // refused
            }
        }
        for (bad in listOf(ByteArray(0), byteArrayOf(1), byteArrayOf(1, 0, 5), byteArrayOf(1, -1, 5))) {
            try {
                wrapper.unwrap(bad)
                fail("${hex(bad)} opened")
            } catch (e: Exception) {
                // refused
            }
        }
    }
}
