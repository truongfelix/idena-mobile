package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialTopicsTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"

    private fun post(hash: String, author: String, message: String, time: Long, replyTo: String = "", channel: String = "") =
        PostCall(hash, time, time, 0, author, message, replyTo, channel, hasMedia = false)

    /** The feed of [posts] in chain order: post n gets id n. */
    private fun feed(vararg posts: PostCall) = socialFeed(posts.toList(), posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap())

    @Test
    fun topicFromTheChannel() {
        assertEquals("idena", topicOf("r/idena"))
        assertEquals("a_b_9", topicOf("r/a_b_9"))
        assertEquals("21 characters", "a".repeat(21), topicOf("r/" + "a".repeat(21)))
        assertNull("22 characters", topicOf("r/" + "a".repeat(22)))
        assertNull("2 characters", topicOf("r/ab"))
        assertNull("upper case is never written", topicOf("r/Idena"))
        assertNull("a colon: the website reads channels ending in discuss:<id> as comments", topicOf("r/x:discuss:4"))
        assertNull(topicOf("idena"))
        assertNull(topicOf(""))
        assertNull(topicOf("discuss:4"))
    }

    @Test
    fun topicTypedInTheEditor() {
        assertEquals("idena", typedTopic("Idena"))
        assertEquals("idena", typedTopic(" r/idena "))
        assertNull(typedTopic("id"))
        assertNull(typedTopic("idena social"))
        assertNull(typedTopic("idéna"))
        assertNull(typedTopic(""))
    }

    @Test
    fun topicPostsAreInTheFeedWithTheirThreads() {
        val feed = feed(
            post("p1", a, "no topic", 100),
            post("p2", b, "in a topic", 110, channel = "r/idena"),
            post("p3", a, "a reply", 120, replyTo = "2"),
            post("p4", b, "a comment", 130, replyTo = "3", channel = "discuss:3"),
            post("p5", a, LIKE, 140, replyTo = "2"),
            post("p6", b, "an invalid topic", 150, channel = "r/X"),
            post("p7", a, "an answer may not name a topic", 160, replyTo = "1", channel = "r/idena"),
        )
        assertEquals("newest first; other channels left out", listOf(2L, 1L), feed.map { it.id })
        val inTopic = feed.first { it.id == 2L }
        assertEquals("idena", inTopic.topic)
        assertEquals("its reply", listOf(3L), inTopic.replies.map { it.id })
        assertEquals("the reply's comment", listOf(4L), inTopic.replies[0].replies.map { it.id })
        assertEquals(1, inTopic.likes)
        assertNull(feed.first { it.id == 1L }.topic)
        assertEquals("no answer under post 1", 0, feed.first { it.id == 1L }.replies.size)
        assertEquals(listOf(2L), topicFeed(feed, "idena").map { it.id })
    }

    @Test
    fun topicsListedByLatestActivity() {
        val feed = feed(
            post("p1", a, "first", 100, channel = "r/idena"),
            post("p2", b, "second", 110, channel = "r/news"),
            post("p3", a, "third", 120, channel = "r/idena"),
            post("p4", b, "late reply in news", 300, replyTo = "2"),
            post("p5", a, "no topic", 400),
        )
        assertEquals(
            listOf(TopicSummary("news", 1, 300, 300), TopicSummary("idena", 2, 120, 120)),
            topicList(feed),
        )
        assertEquals("the search keeps the names holding it", listOf("idena"), topicList(feed, "r/DEN").map { it.name })
        assertEquals(emptyList<TopicSummary>(), topicList(feed, "sport"))
    }

    @Test
    fun followingHoldsFollowedPeopleAndTopics() {
        val feed = feed(
            post("p1", a, "a, no topic", 100),
            post("p2", b, "b in idena", 110, channel = "r/idena"),
            post("p3", b, "b in news", 120, channel = "r/news"),
        )
        val people = mapOf(a to Person(a, following = true))
        assertEquals(listOf(1L), followingFeed(feed, people).map { it.id })
        assertEquals(listOf(2L, 1L), followingFeed(feed, people, setOf("idena")).map { it.id })
        assertEquals(listOf(3L, 2L), followingFeed(feed, emptyMap(), setOf("idena", "news")).map { it.id })
    }
}
