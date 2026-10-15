package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialFeedFilterTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"
    private val day = 24 * 3600L
    private val now = 1000 * day

    private fun call(hash: String, author: String, time: Long, channel: String = "", replyTo: String = "") =
        PostCall(hash, time, time, 0, author, hash, replyTo, channel, hasMedia = false)

    private fun post(id: Long, author: String, daysAgo: Long, channel: String = "", likes: Int = 0): SocialPost {
        val call = call("p$id", author, now - daysAgo * day, channel)
        return SocialPost(id, call, likeCalls = List(likes) { call("like$id-$it", c, call.time + 1, replyTo = id.toString()) }, tips = emptyList(), replies = emptyList())
    }

    // Newest first, as the feed: a's post, b's post in r/idena, b's post with no topic, a year and a half old.
    private val feed = listOf(
        post(3, a, daysAgo = 1, likes = 1),
        post(2, b, daysAgo = 20, channel = "r/idena", likes = 2),
        post(1, b, daysAgo = 500, likes = 3),
    )
    private val people = mapOf(a to Person(a, following = true), b to Person(b, name = "Bob"))
    private val topics = setOf("idena")

    private fun ids(filter: FeedFilter) = filter.apply(feed, people, topics, now, epochStart = null).map { it.id }

    @Test
    fun peopleAndTopicsFilterApartOrTogether() {
        assertEquals(listOf(3L, 2L, 1L), ids(FeedFilter(sort = FeedSort.Newest)))
        assertEquals(listOf(3L), ids(FeedFilter(people = true, sort = FeedSort.Newest)))
        assertEquals(listOf(2L), ids(FeedFilter(topics = true, sort = FeedSort.Newest)))
        assertEquals(listOf(3L, 2L), ids(FeedFilter(people = true, topics = true, sort = FeedSort.Newest)))
    }

    @Test
    fun theMonthAndTheYearBoundARankedSort() {
        assertEquals(listOf(3L), ids(FeedFilter(sort = FeedSort.Likes, period = FeedPeriod.Week)))
        assertEquals(listOf(2L, 3L), ids(FeedFilter(sort = FeedSort.Likes, period = FeedPeriod.Month)))
        assertEquals(listOf(2L, 3L), ids(FeedFilter(sort = FeedSort.Likes, period = FeedPeriod.Year)))
        assertEquals(listOf(1L, 2L, 3L), ids(FeedFilter(sort = FeedSort.Likes, period = FeedPeriod.All)))
    }

    @Test
    fun theLineNamesOnlyWhatIsNotTheDefault() {
        assertNull(FeedFilter().summary)
        assertNull("the period alone changes nothing shown", FeedFilter(period = FeedPeriod.Year).summary)
        assertEquals("People I follow", FeedFilter(people = true).summary)
        assertEquals("People and topics I follow · Newest posts", FeedFilter(people = true, topics = true, sort = FeedSort.Newest).summary)
        assertEquals("Topics I follow · Most liked · 1 month", FeedFilter(topics = true, sort = FeedSort.Likes, period = FeedPeriod.Month).summary)
    }
}
