package io.github.truongfelix.idenanode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.truongfelix.idenanode.go.mobile.Mobile
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The phone's node key files ([NodeKeyFiles]), encrypted with the app's key in the Android Keystore. */
object NodeKeys {
    fun files(context: Context): NodeKeyFiles =
        NodeKeyFiles(NodeSettings.nodeDir(context), KeystoreWrapper(KEY_ALIAS)) { Mobile.readKeyFile(it.absolutePath) }

    private const val KEY_ALIAS = "node_key"
}

/**
 * AES-256-GCM under the key [alias] of the Android Keystore, made at the first [wrap]; the key never leaves the
 * Keystore. It works while the phone is locked: Android may start the node then. [NodeKeyFiles] calls it under its
 * lock, so two processes never make the key at once.
 */
class KeystoreWrapper(private val alias: String) : KeyWrapper {
    override fun wrap(plain: ByteArray): ByteArray = gcmWrap(key(create = true), plain)

    override fun unwrap(wrapped: ByteArray): ByteArray = gcmUnwrap(key(create = false), wrapped)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("the app's Keystore key is gone")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
