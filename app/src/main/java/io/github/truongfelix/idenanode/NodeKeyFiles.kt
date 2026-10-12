package io.github.truongfelix.idenanode

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * The node key at rest (NodeKeys.kt gives the phone's files and Keystore key): `nodekey.enc` in the node directory
 * holds the private key (32 bytes) encrypted, and the node gets it in memory at its start (Mobile.start). Up to
 * 0.1.75 the node kept it in plain hex in `datadir/keystore/nodekey`, with a `backup-*` file there for each key an
 * import replaced: the first read encrypts them and deletes them.
 */

/** Encrypts and decrypts the key files; on the phone with a key of the Android Keystore (NodeKeys.kt). */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * The key files in the node directory [dir]. [readPlainKey] reads a plain key file of the node (Mobile.readKeyFile):
 * the 32 bytes of its key. Each read or write holds [dir]'s key lock, which the screen's process and the node's share.
 * A key returned is the caller's to clear (`fill(0)`) after use.
 */
class NodeKeyFiles(private val dir: File, private val wrapper: KeyWrapper, private val readPlainKey: (File) -> ByteArray) {
    /** The node key, encrypted. */
    val encrypted = File(dir, "nodekey.enc")

    /** The keys an import replaced, encrypted, each as the 32 bytes of the key. */
    val backups = File(dir, "key_backups")

    private val plainDir = File(dir, "datadir/keystore")
    private val plain = File(plainDir, "nodekey")
    private val lockFile = File(dir, "nodekey.lock")

    fun hasKey(): Boolean = encrypted.exists() || plain.exists()

    /** The node key, or null without one. Throws when a key file is there but cannot be read. */
    fun read(): ByteArray? = locked {
        convert()
        if (encrypted.exists()) wrapper.unwrap(encrypted.readBytes()) else null
    }

    /** Makes [key] the node key; the key it replaces goes to [backups]. */
    fun write(key: ByteArray) = locked {
        convert()
        if (encrypted.exists() && !holds(encrypted, key)) keep(encrypted)
        writeWrapped(encrypted, key)
    }

    /** Encrypts the plain key files of the versions up to 0.1.75; each is deleted once its copy reads back. */
    private fun convert() {
        plainDir.listFiles { file -> file.name.startsWith("backup-") }?.forEach { file ->
            // A file the node cannot read stays where it is.
            val key = runCatching { readPlainKey(file) }.getOrNull() ?: return@forEach
            try {
                writeWrapped(File(backups, file.name + ".enc"), key)
            } finally {
                key.fill(0)
            }
            file.delete()
        }
        if (!plain.exists()) return
        val key = readPlainKey(plain)
        try {
            // Both: an older version ran after this one and its key, the plain one, is the newer.
            if (encrypted.exists() && !holds(encrypted, key)) keep(encrypted)
            writeWrapped(encrypted, key)
        } finally {
            key.fill(0)
        }
        plain.delete()
    }

    /** Whether [file] opens as [key]; false when it does not open. */
    private fun holds(file: File, key: ByteArray): Boolean {
        val opened = runCatching { wrapper.unwrap(file.readBytes()) }.getOrNull() ?: return false
        return opened.contentEquals(key).also { opened.fill(0) }
    }

    /** Moves [file], an encrypted node key, to [backups]. */
    private fun keep(file: File) {
        backups.mkdirs()
        val stamp = System.currentTimeMillis()
        val target = generateSequence(0) { it + 1 }.map { File(backups, "nodekey-$stamp-$it.enc") }.first { !it.exists() }
        if (!file.renameTo(target)) throw IOException("The replaced key was not kept")
    }

    /** Writes [key] encrypted to [file] in one step, then checks that it reads back. */
    private fun writeWrapped(file: File, key: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write(wrapper.wrap(key))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("The key was not written")
        if (!holds(file, key)) throw IOException("The key written does not read back")
    }

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        dir.mkdirs()
        RandomAccessFile(lockFile, "rw").use { lock ->
            lock.channel.lock()
            block()
        }
    }

    private companion object {
        // The file lock is per process: two threads of one process would get an OverlappingFileLockException.
        val processLock = Any()
    }
}

/** [plain] encrypted with AES-GCM under [key]: a format byte, the nonce's size, the nonce, the sealed data. */
internal fun gcmWrap(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val nonce = cipher.iv
    return byteArrayOf(WRAP_FORMAT, nonce.size.toByte()) + nonce + cipher.doFinal(plain)
}

/** What [gcmWrap] encrypted; throws when [wrapped] was not encrypted under [key] or was changed. */
internal fun gcmUnwrap(key: SecretKey, wrapped: ByteArray): ByteArray {
    if (wrapped.size < 2 || wrapped[0] != WRAP_FORMAT) throw IOException("Not an encrypted key file")
    val nonceEnd = 2 + wrapped[1]
    if (nonceEnd <= 2 || wrapped.size < nonceEnd) throw IOException("Not an encrypted key file")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 2, nonceEnd - 2))
    return cipher.doFinal(wrapped, nonceEnd, wrapped.size - nonceEnd)
}

private const val WRAP_FORMAT: Byte = 1
