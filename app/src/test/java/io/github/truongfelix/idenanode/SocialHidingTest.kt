package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialHidingTest {
    private val me = "0xaaaa000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val eve = "0xeeee000000000000000000000000000000000003"

    private fun call(id: Long, author: String, text: String = "hello", replyTo: String = "", channel: String = "") =
        PostCall("0x$id", id, id, 0, author, text, replyTo, channel, hasMedia = false)

    private fun post(id: Long, author: String, text: String = "hello", replyTo: String = "", channel: String = "", replies: List<SocialPost> = emptyList()) =
        SocialPost(id, call(id, author, text, replyTo, channel), emptyList(), emptyList(), replies)

    private fun ids(feed: List<SocialPost>): List<Long> = feed.flatMap { withReplies(it) }.map { it.id }

    @Test
    fun wordsMatchWholeWordsWithoutCaseOrAccents() {
        val filter = WordFilter(listOf("spam", "Café", "free coins"))
        assertTrue(filter.matches("Buy SPAM now"))
        assertTrue(filter.matches("spam!"))
        assertTrue("accents ignored", filter.matches("a cafe here"))
        assertTrue("both ways", WordFilter(listOf("cafe")).matches("au Café"))
        assertTrue("a phrase, spaces collapsed", filter.matches("get  Free\ncoins today"))
        assertFalse("inside a longer word", filter.matches("spammer"))
        assertFalse(filter.matches("free coin"))
        assertFalse(filter.matches(""))
        assertTrue(WordFilter(listOf("  ", "")).isEmpty)
    }

    @Test
    fun aWordIsCleanedAndCut() {
        assertEquals("free coins", cleanHiddenWord("  free \t coins\n"))
        assertNull(cleanHiddenWord("   "))
        assertEquals(MAX_HIDDEN_WORD, cleanHiddenWord("x".repeat(100))!!.length)
    }

    @Test
    fun aBlockedAccountLeavesTheFeedTheThreadsLikesAndTips() {
        val like = call(90, eve, LIKE, replyTo = "1")
        val tip = TipCall("0xt", 91, 91, 0, eve, "1", "1", "1")
        val bobsReply = post(3, bob, replyTo = "1", replies = listOf(
            post(5, eve, channel = discussChannel(3)),
            post(6, bob, replyTo = "5", channel = discussChannel(3)),
            post(7, me, replyTo = "5", channel = discussChannel(3)),
            post(8, bob, replyTo = "6", channel = discussChannel(3)),
            post(9, bob, channel = discussChannel(3)),
        ))
        val feed = listOf(
            SocialPost(1, call(1, bob), listOf(like), listOf(tip), listOf(post(2, eve, replyTo = "1"), bobsReply)),
            post(4, eve),
        )
        val shown = visibleFeed(feed, Hiding(blocked = setOf(eve)), me)
        // 5 is eve's; 6 and 8 answer it (8 through 6) and leave with it; 7 is mine and stays; 9 stays.
        assertEquals(listOf(1L, 3L, 7L, 9L), ids(shown))
        assertEquals(0, shown[0].likes)
        assertTrue(shown[0].tips.isEmpty())
        assertSame("nothing hidden: the same list", feed, visibleFeed(feed, Hiding.None, me))
    }

    @Test
    fun aPostWithAHiddenWordLeavesTheListsButNotMine() {
        val feed = listOf(
            post(1, bob, "Free coins here", replies = listOf(post(3, bob, "free coins", replyTo = "1"))),
            post(2, me, "free coins, says the spammer"),
            post(4, bob, "ipfs://bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy"),
            post(5, eve, "hello", replies = listOf(post(6, bob, "free coins", replyTo = "5"))),
        )
        val shown = visibleFeed(feed, Hiding(words = WordFilter(listOf("free coins"))), me)
        // A reply with the word stays: its thread folds it. A text on IPFS is checked once fetched.
        assertEquals(listOf(2L, 4L, 5L, 6L), ids(shown))
    }

    @Test
    fun theNotificationsLeaveOutBlockedAccountsAndHiddenAnswers() {
        val post = call(1, me)
        val items = listOf(
            ActivityItem(ActivityKind.Like, eve, 10, 0, 10, 1, 1, post),
            ActivityItem(ActivityKind.Reply, bob, 11, 0, 11, 1, 2, post, call(2, bob, "free coins", replyTo = "1")),
            ActivityItem(ActivityKind.Reply, bob, 12, 0, 12, 1, 3, post, call(3, bob, "thanks", replyTo = "1")),
            ActivityItem(ActivityKind.Tip, bob, 13, 0, 13, 1, 1, post),
            ActivityItem(ActivityKind.Reply, bob, 14, 0, 14, 1, 4, post, call(4, bob, "rude", replyTo = "1")),
        )
        val hiding = Hiding(setOf(eve), WordFilter(listOf("free coins")), posts = setOf(call(4, bob).hash))
        assertEquals(listOf(12L, 13L), visibleActivity(items, hiding, me).map { it.height })
    }

    @Test
    fun aPostHiddenOrInABlockedTopicLeavesTheLists() {
        val feed = listOf(
            post(1, bob, replies = listOf(post(3, eve, replyTo = "1"))),
            post(2, bob, channel = "r/flips"),
            post(4, eve, channel = "r/idena"),
            post(5, me, channel = "r/flips"),
            post(6, me),
        )
        val hiding = Hiding(posts = setOf(call(1, bob).hash, call(3, eve).hash, call(6, me).hash), topics = setOf("flips"))
        // 3 is a reply: its thread folds it. Mine stay, hidden or in a blocked topic.
        assertEquals(listOf(4L, 5L, 6L), ids(visibleFeed(feed, hiding, me)))
    }

    @Test
    fun aHiddenPostStillOpensButNotABlockedOne() {
        val feed = listOf(post(1, bob, "free coins", replies = listOf(post(3, eve, replyTo = "1"))), post(2, eve))
        val hiding = Hiding(setOf(eve), WordFilter(listOf("free coins")), posts = setOf(call(1, bob).hash))
        assertEquals(listOf(1L), ids(listOfNotNull(threadPost(feed, 1, hiding, me))))
        assertNull(threadPost(feed, 2, hiding, me))
        assertNull(threadPost(feed, 9, hiding, me))
    }

    @Test
    fun aHashIsCheckedAndLowercase() {
        val hash = "0x" + "Ab".repeat(32)
        assertEquals(hash.lowercase(), txHashOf(" $hash "))
        assertNull(txHashOf("0x1234"))
        assertNull(txHashOf("ab".repeat(32)))
        assertTrue("any case hides", Hiding(posts = setOf(hash.lowercase())).hidesText(call(1, bob).copy(hash = hash), me))
    }

    @Test
    fun aConversationWithABlockedAccountLeaves() {
        fun message(from: String, others: List<String>) = ChatMessage("0x$from", 1, 1, 0, from, listOf(from) + others, "hi")
        val list = listOf(
            Conversation("a", listOf(bob), listOf(message(bob, listOf(me))), false),
            Conversation("b", listOf(eve), listOf(message(eve, listOf(me))), true),
            Conversation("c", listOf(bob, eve), listOf(message(bob, listOf(me, eve))), false),
        )
        assertEquals(listOf("a"), visibleConversations(list, setOf(eve)).map { it.key })
    }

    @Test
    fun theBlockedMarkIsKeptAndUndone() {
        val person = Person(eve, blocked = true)
        assertFalse("a blocked account stays in the list", person.isEmpty)
        assertEquals(person, Person.fromJson(person.toJson()))
        assertEquals(setOf(eve), blockedAddresses(mapOf(eve to person, bob to Person(bob, "Bob"))))
        assertTrue(emptyMap<String, Person>().with(person).with(person.copy(blocked = false)).isEmpty())
    }

    private fun withPicture(post: SocialPost) = post.copy(call = post.call.copy(hasMedia = true))

    @Test
    fun aMutedAuthorsPostsLeaveTheFeedsOnly() {
        val feed = listOf(post(1, bob), post(2, eve), post(3, me))
        val hiding = Hiding(muted = setOf(bob, me))
        assertEquals("never one's own", listOf(2L, 3L), feedPosts(feed, hiding, me).map { it.id })
        // Not a block: the feed as shown everywhere else (profiles, threads, search) keeps them.
        assertEquals(listOf(1L, 2L, 3L), visibleFeed(feed, hiding, me).map { it.id })
        assertTrue(hiding.leavesFeeds(post(4, bob, replyTo = "1"), me))
        assertFalse(hiding.leavesFeeds(post(5, eve, replyTo = "1"), me))
    }

    @Test
    fun postsWithPicturesLeaveTheFeedsOnlyWhenHidden() {
        val feed = listOf(withPicture(post(1, bob)), post(2, eve), withPicture(post(3, me)))
        assertEquals(listOf(1L, 2L, 3L), feedPosts(feed, Hiding(pictures = PicturesMode.Show), me).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), feedPosts(feed, Hiding(pictures = PicturesMode.TapToShow), me).map { it.id })
        assertEquals("never one's own", listOf(2L, 3L), feedPosts(feed, Hiding(pictures = PicturesMode.Hide), me).map { it.id })
        assertSame("nothing to filter: the same list", feed, feedPosts(feed, Hiding(), me))
    }

    @Test
    fun thePicturesSettingIsReadByNameShowByDefault() {
        assertEquals(PicturesMode.TapToShow, PicturesMode.of("TapToShow"))
        assertEquals(PicturesMode.Hide, PicturesMode.of("Hide"))
        assertEquals(PicturesMode.Show, PicturesMode.of(null))
        assertEquals(PicturesMode.Show, PicturesMode.of("tap"))
    }
}
