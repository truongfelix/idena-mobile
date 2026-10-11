package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialBadgeTest {
    private val me = "0xaaaa000000000000000000000000000000000001"
    private val other = "0xbbbb000000000000000000000000000000000002"
    private val start = 11_400_000L

    private fun post(hash: String, author: String, message: String, height: Long, replyTo: String = "") =
        PostCall(hash, height, height, 0, author, message, replyTo, "", false)

    /** A node at [head] whose blocks hold [calls], with the contract's post authors [authors]. */
    private class Node(private val head: Long, private val calls: List<PostCall>, private val authors: Map<Long, String>) : SocialSource {
        val ranges = ArrayList<LongRange>()

        override fun batchSize() = 10_000L

        override fun head() = head

        override fun calls(from: Long, to: Long, version: SocialVersion): BlockCalls {
            ranges += from..to
            return BlockCalls(calls.filter { it.height in from..to }, emptyList())
        }

        override fun authors(version: SocialVersion) = authors
    }

    // The app's scan: my post (id 1), read through block [start].
    private val scan = SocialCache(SOCIAL_FIRST_BLOCK, start, listOf(post("p1", me, "hello", start - 10)), mapOf(1L to me), start)

    @Test
    fun aLikeInANewBlockIsNews() {
        val like = post("l1", other, LIKE, start + 50, replyTo = "1")
        val node = Node(start + 90, listOf(like), mapOf(1L to me, 2L to other))
        val (next, news) = backgroundNews(scan, node, me, seenThrough = start, notify = NotifyKind.entries.toSet())
        assertEquals("only the new blocks are read", listOf(start + 1..start + 90), node.ranges)
        assertEquals(start + 90, next.authorsHeight)
        assertEquals(listOf(ActivityKind.Like), news.map { it.kind })
        assertEquals(other, news.single().actor)
        assertTrue("seen in the app", backgroundNews(scan, node, me, seenThrough = start + 50, notify = NotifyKind.entries.toSet()).second.isEmpty())
        assertTrue("likes turned off", backgroundNews(scan, node, me, start, setOf(NotifyKind.Comments, NotifyKind.Tips)).second.isEmpty())
        assertTrue("my own like is no news", backgroundNews(scan, Node(start + 90, listOf(like.copy(author = me)), mapOf(1L to me, 2L to me)), me, start, NotifyKind.entries.toSet()).second.isEmpty())
    }

    private fun item(height: Long) = ActivityItem(ActivityKind.Like, other, height, 0, height, 1, 1, post("p1", me, "hello", 1))

    @Test
    fun theNotificationSoundsOnlyForWhatItDidNotAnnounce() {
        assertNull("no news, no notification", badgeUpdate(emptyList(), 100))
        assertEquals(BadgeUpdate(2, alert = true, alertedThrough = 120), badgeUpdate(listOf(item(120), item(110)), null))
        assertEquals(BadgeUpdate(2, alert = true, alertedThrough = 120), badgeUpdate(listOf(item(120), item(110)), 110))
        val again = badgeUpdate(listOf(item(120), item(110)), 120)!!
        assertFalse("the same news again: silent", again.alert)
        assertEquals(2, again.count)
        assertEquals(130, badgeUpdate(listOf(item(110)), 130)!!.alertedThrough)
    }
}
