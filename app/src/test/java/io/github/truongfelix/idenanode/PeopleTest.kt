package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"

    private fun call(hash: String, author: String, message: String, time: Long, replyTo: String = "", channel: String = "") =
        PostCall(hash, time, time, 0, author, message, replyTo, channel, hasMedia = false)

    private fun node(id: Long, call: PostCall, likes: Int = 0, tips: Long = 0, replies: List<SocialPost> = emptyList()) = SocialPost(
        id, call,
        likeCalls = List(likes) { call("like$id-$it", c, LIKE, call.time + 1, replyTo = id.toString()) },
        tips = if (tips > 0) listOf(TipCall("tip$id", call.height + 1, call.time + 1, 0, c, id.toString(), tips.toString(), tips.toString())) else emptyList(),
        replies = replies,
    )

    @Test
    fun aPersonWithoutNameOrMarkLeavesTheList() {
        var people = emptyMap<String, Person>()
        people = people.with(Person(a, name = "Alice"))
        people = people.with(Person(b, following = true))
        assertEquals(setOf(a, b), people.keys)
        people = people.with(Person(a))
        assertEquals(setOf(b), people.keys)
    }

    @Test
    fun namesAreOneTrimmedLine() {
        assertEquals("Alice Smith", cleanName("  Alice\n\tSmith  "))
        assertEquals(Person.MAX_NAME, cleanName("x".repeat(100)).length)
        // An emoji (2 UTF-16 units) across the limit is dropped whole, never cut in half.
        assertEquals("x".repeat(Person.MAX_NAME - 1), cleanName("x".repeat(Person.MAX_NAME - 1) + "😀"))
        assertEquals("ok 😀", cleanName("ok 😀"))
        assertEquals("", cleanName(" \n "))
    }

    @Test
    fun jsonKeepsTheListAndDropsBadEntries() {
        val people = mapOf(a to Person(a, "Alice", following = true), b to Person(b, message = true))
        val json = peopleToJson(people)
        json.getJSONArray("people").put(JSONObject().put("address", "not an address").put("name", "x"))
        json.getJSONArray("people").put(JSONObject().put("address", c))
        assertEquals(people, peopleFromJson(JSONObject(json.toString())))
    }

    @Test
    fun namedPeopleComeFirstInNameOrder() {
        val people = mapOf(a to Person(a, "zed"), b to Person(b, "Bob"), c to Person(c, following = true))
        assertEquals(listOf(b, a, c), sortedPeople(people).map { it.address })
    }

    @Test
    fun displayNameFallsBackToTheShortAddress() {
        val names = peopleNames(mapOf(a to Person(a, "Alice"), b to Person(b, following = true)))
        assertEquals("Alice", displayName(a.uppercase().replace("0X", "0x"), names))
        assertEquals(shortAddress(b), displayName(b, names))
    }

    @Test
    fun followingKeepsTheFollowedAuthors() {
        val feed = listOf(node(2, call("p2", b, "two", 20)), node(1, call("p1", a, "one", 10)))
        assertEquals(listOf(1L), followingFeed(feed, mapOf(a to Person(a, following = true), b to Person(b, "Bob"))).map { it.id })
    }

    @Test
    fun rankedSortsKeepThePeriodsPostsWithSomething() {
        val day = 24 * 3600L
        val now = 100 * day
        val comment = node(5, call("c5", a, "c", now - 2 * day + 2))
        val feed = listOf(
            node(4, call("p4", a, "new, liked twice", now - day), likes = 2),
            node(3, call("p3", b, "new, liked once, tipped", now - 2 * day), likes = 1, tips = 7,
                replies = listOf(node(6, call("r6", c, "r", now - 2 * day + 1), replies = listOf(comment)))),
            node(2, call("p2", a, "nothing", now - 3 * day)),
            node(1, call("p1", b, "old, liked 5 times", now - 30 * day), likes = 5, tips = 1),
        )
        assertEquals(listOf(1L, 4L, 3L), sortFeed(feed, FeedSort.Likes, FeedPeriod.All, now).map { it.id })
        assertEquals("the 7 days leave the old post out", listOf(4L, 3L), sortFeed(feed, FeedSort.Likes, FeedPeriod.Week, now).map { it.id })
        assertEquals("replies and comments count", listOf(3L), sortFeed(feed, FeedSort.Comments, FeedPeriod.All, now).map { it.id })
        assertEquals(listOf(3L, 1L), sortFeed(feed, FeedSort.Tips, FeedPeriod.All, now).map { it.id })
        // Heights are the times here: the epoch from the block of post 3.
        assertEquals(listOf(4L, 3L), sortFeed(feed, FeedSort.Likes, FeedPeriod.Epoch, now, epochStart = now - 2 * day).map { it.id })
        assertEquals("epoch unknown: all posts", 3, sortFeed(feed, FeedSort.Likes, FeedPeriod.Epoch, now).size)
        assertTrue(sortFeed(emptyList(), FeedSort.Tips).isEmpty())
    }

    @Test
    fun searchFindsTheAddressPeopleAndTexts() {
        val comment = node(5, call("c5", c, "Hello from a comment", 30))
        val feed = listOf(
            node(3, call("p3", b, "ipfs://bafkreitext1234", 25)),
            node(1, call("p1", a, "hello world", 10), replies = listOf(node(2, call("r2", b, "no match", 20), replies = listOf(comment)))),
        )
        val people = mapOf(a to Person(a, "Helloise"), b to Person(b, "Bob"))
        val ipfs = mapOf("bafkreitext1234" to "hello on IPFS")

        val hits = searchSocial(feed, people, "hello", ipfsText = { ipfs[it] })
        assertEquals(SearchHit.Named(people.getValue(a)), hits[0])
        assertEquals("newest first, the IPFS text included", listOf(5L, 3L, 1L), hits.drop(1).map { (it as SearchHit.Text).node.id })
        assertEquals("the comment opens in its post's thread", 1L, (hits[1] as SearchHit.Text).threadId)
        // A like goes where the thread screen sends it: the post, or the reply's discussion channel.
        assertEquals(PostTarget.onComment(2, 5), (hits[1] as SearchHit.Text).target)
        assertEquals(PostTarget.onPost(1), (hits[3] as SearchHit.Text).target)
        assertEquals(PostTarget.onReply(2), (searchSocial(feed, people, "no match").single() as SearchHit.Text).target)

        assertEquals("an IPFS text not fetched is not searched", listOf(5L, 1L),
            searchSocial(feed, emptyMap(), "hello").map { (it as SearchHit.Text).node.id })
        assertEquals(SearchHit.Address(a), searchSocial(feed, people, a.uppercase().replace("0X", "0x")).first())
        assertEquals("an address start finds the person", listOf(SearchHit.Named(people.getValue(b))), searchSocial(feed, people, "0xbbbb"))
        assertTrue("too short", searchSocial(feed, people, "h").isEmpty())
    }

    @Test
    fun importMergesWithoutLosingAnything() {
        val current = mapOf(a to Person(a, "Alice", message = true), b to Person(b, "Bob"))
        val file = mapOf(a to Person(a, following = true), b to Person(b, "Robert"), c to Person(c, "Carol"))
        val result = mergePeople(current, file)
        assertEquals(1, result.added)
        assertEquals(2, result.changed)
        assertEquals(Person(a, "Alice", following = true, message = true), result.people[a])
        assertEquals("the file's name wins", "Robert", result.people.getValue(b).name)
        assertEquals("the same file again changes nothing", 0, mergePeople(result.people, file).let { it.added + it.changed })
    }

    @Test
    fun theConfirmationNamesTheRecipient() {
        val names = mapOf(a to "Alice")
        assertEquals("Alice ($a)", recipientText(a, names))
        assertEquals("Alice (${a.uppercase().replace("0X", "0x")})", recipientText(a.uppercase().replace("0X", "0x"), names))
        assertEquals(b, recipientText(b, names))
    }

    @Test
    fun sendOffersThePeopleWithANameGivenOrClaimed() {
        val c = "0xcccc000000000000000000000000000000000003"
        val people = mapOf(a to Person(a, "zed"), b to Person(b, ""), c to Person(c, ""))
        // b claimed a name in the profiles contract, c has none; the account itself is left out.
        val names = mapOf(a to "zed", b to "@alice")
        assertEquals(listOf(b to "@alice", a to "zed"), pickablePeople(people, names, "0xdddd000000000000000000000000000000000004"))
        assertEquals(listOf(a to "zed"), pickablePeople(people, names, b.uppercase().replace("0X", "0x")))
    }

    @Test
    fun timeAgoCountsAsReddit() {
        val now = 1_000_000_000L
        assertEquals("5s", timeAgo(now - 5, now))
        assertEquals("10min", timeAgo(now - 10 * 60 - 59, now))
        assertEquals("7h", timeAgo(now - 7 * 3600, now))
        assertEquals("1d", timeAgo(now - 86_400 - 5, now))
        assertEquals("29d", timeAgo(now - 29 * 86_400, now))
        assertEquals("5mo", timeAgo(now - 150 * 86_400, now))
        assertEquals("2y", timeAgo(now - 2 * 365 * 86_400, now))
        assertEquals("a block time ahead of the phone's clock", "now", timeAgo(now + 3, now))
    }

    @Test
    fun theAuthorShowsItsAge() {
        assertEquals("Human  ·  age 12", AuthorIdentity("Human", 12).label)
        assertEquals("Candidate", AuthorIdentity("Candidate", 0).label)
        assertEquals("Not validated", AuthorIdentity("Undefined", 3).label)
    }
}
