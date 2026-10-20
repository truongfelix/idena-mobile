package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageCodeTest {
    // From the node library (mobile.PrivateKeyPublicKey and crypto.PubkeyToAddress) for one private key.
    private val key = "044e3b81af9c2234cad09d679ce6035ed1392347ce64ce405f5dcd36228a25de6e47fd35c4215d1edf53e6f83de344615ce719bdb0fd878f6ed76f06dd277956de"
    private val address = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

    @Test
    fun aKeyHashesToItsAddress() {
        assertEquals(address.lowercase(), addressOfPublicKey(key))
    }

    @Test
    fun aCodeReadsBackWithOrWithoutItsPrefixSpacesOrCase() {
        val code = messageCode(key)
        assertEquals("idena-key:$key", code)
        assertEquals(key, parseMessageCode(code))
        assertEquals(key, parseMessageCode("  " + code.uppercase().replace("IDENA-KEY:", "idena-key:").chunked(20).joinToString("\n") + " "))
        assertEquals(key, parseMessageCode("0x$key"))
        assertEquals(key, parseMessageCode(key.drop(2)))
        assertNull(parseMessageCode(address))
        // 128 characters are a key without its 04 (taken, then checked against the address); 126 are none.
        assertNull(parseMessageCode(key.dropLast(4)))
        assertNull(parseMessageCode("05" + key.drop(2)))
        assertNull(parseMessageCode(key.dropLast(1) + "g"))
    }

    @Test
    fun theStoreKeepsTheSharedKeysAndReadsAnEarlierBuildsFile() {
        val store = ChatStore(sharedKeys = mapOf(address.lowercase() to key))
        assertEquals(store.sharedKeys, ChatStore.fromJson(org.json.JSONObject(store.toJson().toString())).sharedKeys)
        // A file of the builds before the message code: no such field, no key.
        val earlier = org.json.JSONObject(store.toJson().toString()).apply { remove("sharedKeys") }
        assertEquals(emptyMap<String, String>(), ChatStore.fromJson(earlier).sharedKeys)
    }

    @Test
    fun aCodeIsTakenOnlyForItsOwnAddress() {
        assertEquals(key, messageCodeFor(messageCode(key), address.uppercase().replace("0X", "0x")))
        val other = "0x" + "1".repeat(40)
        assertNull(messageCodeFor(messageCode(key), other))
        // One digit changed in the key: another key, another address.
        val changed = key.dropLast(1) + (if (key.last() == '0') "1" else "0")
        assertNull(messageCodeFor(messageCode(changed), address))
    }
}
