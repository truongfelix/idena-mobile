package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMessagesTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"

    /** social-messages-website.json: what idena.social's code writes (JSON.stringify, keccak_256, WebCrypto AES-GCM). */
    private val website: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("social-messages-website.json").bufferedReader().use { it.readText() })
    }

    private fun hex(text: String) = "0x" + text.toByteArray().joinToString("") { "%02x".format(it) }

    private fun call(from: String, parts: Int, plain: String, height: Long = 10, index: Int = 0) =
        MessageCall("0x$height$index", height, height * 20, index, from, List(parts) { "part$it" }, keccak256Hex(plain))

    @Test
    fun weWriteWhatTheWebsiteWrites() {
        val m = website.getJSONObject("message")
        val recipients = m.getJSONArray("recipients").let { r -> (0 until r.length()).map { r.getString(it) } }
        val plain = plainMessage(m.getString("sender"), recipients, m.getString("text"))
        assertEquals(m.getString("plain"), plain)
        assertEquals(m.getString("messageHash"), keccak256Hex(plain))
        val parts = m.getJSONArray("parts").let { p -> (0 until p.length()).map { p.getString(it) } }
        assertEquals(m.getString("argument"), sendMessageArgument(parts, m.getString("messageHash")))
    }

    @Test
    fun aCallIsReadFromItsArgument() {
        val m = website.getJSONObject("message")
        val call = MessageCall.parse("0x1", 7, 140, 2, a.uppercase().replace("0X", "0x"), hex(m.getString("argument")))!!
        assertEquals(listOf("BAAA+/=", "BBBB/+=="), call.parts)
        assertEquals(m.getString("messageHash"), call.messageHash)
        assertEquals(a, call.from)
        assertEquals(call, MessageCall.fromJson(JSONObject(call.toJson().toString())))
        assertNull("not encrypted", MessageCall.parse("0x1", 7, 140, 2, a, hex("""{"message":["x","y"],"messageHash":"${m.getString("messageHash")}","encrypted":false}""")))
        assertNull("one part", MessageCall.parse("0x1", 7, 140, 2, a, hex("""{"message":["x"],"messageHash":"${m.getString("messageHash")}","encrypted":true}""")))
        assertNull("no hash", MessageCall.parse("0x1", 7, 140, 2, a, hex("""{"message":["x","y"],"encrypted":true}""")))
    }

    @Test
    fun theReaderKeepsTheWebsitesRules() {
        val plain = plainMessage(a, listOf(b), "hi")
        val message = acceptMessage(call(a, 2, plain), plain, listOf(1), b)
        assertNotNull(message)
        assertEquals("hi", message!!.text)
        assertEquals(listOf(a, b), message.participants)
        assertEquals("$a-$b", message.conversation)
        assertNotNull("the sender's own copy", acceptMessage(call(a, 2, plain), plain, listOf(0), a))

        assertNull("hash does not match", acceptMessage(call(a, 2, plain + " "), plain, listOf(1), b))
        assertNull("parts and participants differ", acceptMessage(call(a, 3, plain), plain, listOf(1), b))
        assertNull("the sender is not first", acceptMessage(call(c, 2, plain), plain, listOf(1), b))
        assertNull("not my part", acceptMessage(call(a, 2, plain), plain, listOf(0), b))
        assertNull("not in the participants", acceptMessage(call(a, 2, plain), plain, listOf(1), c))
        val twice = plainMessage(a, listOf(b, b), "x")
        assertNull("a group with a duplicate", acceptMessage(call(a, 3, twice), twice, listOf(1, 2), b))
        val upper = plainMessage(a, listOf(b), "x").replace(b, b.uppercase().replace("0X", "0x"))
        assertNull("an address not lowercase", acceptMessage(call(a, 2, upper), upper, listOf(1), b))
        val toSelf = plainMessage(a, listOf(a), "note")
        assertNotNull("a message to oneself opens both parts", acceptMessage(call(a, 2, toSelf), toSelf, listOf(0, 1), a))
    }

    @Test
    fun aWebsiteMessageWithMoreFieldsShowsItsText() {
        val plain = JSONArray(listOf(JSONArray(listOf(a, b, c)), "", "ipfs://bafkreiabc", "a2V5", "12", JSONArray(listOf("AAAA")), JSONArray(listOf("image/png")), "", JSONArray())).toString()
        val message = acceptMessage(call(a, 3, plain), plain, listOf(2), c)!!
        assertEquals("ipfs://bafkreiabc", message.text)
        assertEquals("a2V5", message.textPassword)
        assertTrue(message.hasMedia)
        assertTrue(message.isReply)
        assertEquals(message, ChatMessage.fromJson(JSONObject(message.toJson().toString())))
    }

    @Test
    fun anIpfsTextOpensWithItsKey() {
        val v = website.getJSONObject("ipfsText")
        val blob = java.util.Base64.getDecoder().decode(v.getString("blob"))
        assertEquals(v.getString("text"), decryptIpfsText(blob, v.getString("key")))
        val other = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        assertNull("another key", decryptIpfsText(blob, other))
    }

    private fun msg(from: String, participants: List<String>, height: Long, text: String = "x") =
        ChatMessage("0x$height", height, height * 20, 0, from, participants, text)

    @Test
    fun conversationsAndRequests() {
        val messages = listOf(
            msg(b, listOf(b, a), 10),
            msg(a, listOf(a, b), 12),
            msg(c, listOf(c, a), 11),
            msg(b, listOf(b, a, c), 13),
        )
        val list = conversations(messages, a, emptyMap())
        assertEquals("the latest first", listOf(listOf(b, c), listOf(b), listOf(c)), list.map { it.others })
        assertTrue("a group I never wrote in", list[0].isRequest)
        assertTrue(list[0].isGroup)
        assertFalse("I answered", list[1].isRequest)
        assertTrue("a stranger", list[2].isRequest)
        assertEquals(listOf(10L, 12L), list[1].messages.map { it.height })

        val followed = conversations(messages, a, mapOf(c to Person(c, following = true)))
        assertFalse("someone I follow", followed.first { it.others == listOf(c) }.isRequest)
        val accepted = conversations(messages, a, mapOf(c to Person(c, message = true)))
        assertFalse("accepted", accepted.first { it.others == listOf(c) }.isRequest)

        val withB = list[1]
        assertEquals(1, unreadCount(withB, a, null))
        assertEquals(0, unreadCount(withB, a, 10L to 0))
    }
}
