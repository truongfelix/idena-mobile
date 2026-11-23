package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Community moderation (B-308), the same cases as the desktop app's moderation tests.
class ModerationTest {
    private val a = "0xaaaa000000000000000000000000000000000001"
    private val b = "0xbbbb000000000000000000000000000000000002"
    private val c = "0xcccc000000000000000000000000000000000003"

    private fun le(value: Long, size: Int): String =
        "0x" + (0 until size).joinToString("") { "%02x".format((value shr (8 * it)) and 0xff) }

    private fun hex(text: String) = "0x" + text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun hx(n: Int) = "0x" + n.toString().padStart(64, '0')
    private fun banArgs(address: String, moderator: String, epoch: Long, permanent: Boolean, end: Long, reason: String) =
        listOf(address, moderator, le(epoch, 2), le(if (permanent) 1 else 0, 1), le(end, 4), hex(reason))

    private val order = EventOrder("0x01", 1, 0, 100, 0)
    private var seq = 0

    private fun contractCall(vararg events: Pair<String, List<String>>, success: Boolean = true, time: Long = 100,
                             contract: String = MODERATION_CONTRACT): JSONObject {
        seq += 1
        return JSONObject().put("hash", hx(seq)).put("height", seq).put("index", 0).put("timestamp", time)
            .put("success", success)
            .put("events", JSONArray(events.map { (name, args) ->
                JSONObject().put("contract", contract).put("event", name).put("args", JSONArray(args))
            }))
    }

    private fun stateOf(vararg calls: JSONObject) = replay(callEvents(JSONArray(calls.toList())))

    @Test
    fun eventsAreReadAsTheContractWritesThem() {
        val ban = decodeEvent("ban", banArgs(a, b, 240, false, 243, "Spam"), order)!!
        assertEquals(listOf(a, b, 240L, false, 243L, "Spam"),
            listOf(ban.address, ban.moderator, ban.epoch, ban.permanent, ban.endEpoch, ban.reason))
        val hidden = decodeEvent("banTransaction", listOf(hx(7).uppercase().replace("0X", "0x"), b, le(240, 2), "0x"), order)!!
        assertEquals(hx(7), hidden.hash)
        assertEquals("", hidden.reason)
        assertNull(decodeEvent("other", listOf(a), order))
        assertNull(decodeEvent("ban", banArgs("0x12", b, 1, true, 0, ""), order))
        assertNull("a missing argument", decodeEvent("unban", listOf(a, b), order))
        assertNull(decodeEvent("banTransaction", listOf(hx(1), b, le(1, 3), "0x"), order))
    }

    @Test
    fun eventsComeFromAcceptedCallsOfTheContractOnly() {
        val events = callEvents(JSONArray(listOf(
            contractCall("ban" to banArgs(a, b, 240, true, 0, "Spam")),
            contractCall("ban" to banArgs(c, b, 240, true, 0, ""), success = false),
            contractCall("ban" to banArgs(c, b, 240, true, 0, ""), contract = "0x" + "99".repeat(20)),
        )))
        assertEquals(listOf(a), events.map { it.address })
    }

    @Test(expected = NoEventsException::class)
    fun aNodeWithoutEventsIsAnError() {
        callEvents(JSONArray().put(JSONObject().put("hash", "0x01").put("success", true)))
    }

    @Test
    fun bansLastUntilLiftedOrEnded() {
        val state = stateOf(
            contractCall("ban" to banArgs(a, b, 240, false, 242, "Spam")),
            contractCall("ban" to banArgs(c, b, 240, true, 0, "Scam")),
            contractCall("unban" to listOf(c, b, hex("Appeal"))),
        )
        assertEquals("Spam", state.activeBan(a.uppercase().replace("0X", "0x"), 241)?.reason)
        assertNull(state.activeBan(a, 242))
        assertNotNull("an unknown epoch counts every ban", state.activeBan(a, null))
        assertNull(state.activeBan(c, 241))
    }

    @Test
    fun aBanStartsWhereTheUninterruptedOneBegan() {
        val state = stateOf(
            contractCall("ban" to banArgs(a, b, 240, false, 242, "One"), time = 100),
            contractCall("ban" to banArgs(a, b, 241, false, 250, "Two"), time = 200),
            contractCall("ban" to banArgs(c, b, 240, false, 241, "One"), time = 100),
            contractCall("ban" to banArgs(c, b, 241, true, 0, "Again"), time = 300),
        )
        assertEquals(100L, state.bans.getValue(a).since.time)
        assertEquals(300L, state.bans.getValue(c).since.time)
    }

    @Test
    fun hiddenPostsVouchesAndReportsAreKept() {
        val state = stateOf(
            contractCall("banTransaction" to listOf(hx(1), b, le(240, 2), hex("Spam")), "banTransaction" to listOf(hx(2), b, le(240, 2), "0x")),
            contractCall("unbanTransaction" to listOf(hx(2), b, "0x")),
            contractCall("vouch" to listOf(a, b, le(1, 4))),
            contractCall("vouch" to listOf(a, c, le(2, 4))),
            contractCall("vouchWithdrawn" to listOf(a, b, le(1, 4))),
            contractCall("vouch" to listOf(c, b, le(1, 4))),
            contractCall("vouchesRevoked" to listOf(c, a, le(1, 4))),
            contractCall("report" to listOf(a, c, hex("Spam"))),
        )
        assertEquals(setOf(hx(1)), state.hidden.keys)
        assertEquals(setOf(c), state.vouchersOf(a))
        assertTrue(state.vouchersOf(c).isEmpty())
        assertEquals(listOf(Report("address", a, c, "Spam", 100)), state.reports)
    }

    // The feed: a post b made (height = time), its reply, a comment; ids follow the authors in chain order.
    private fun post(hash: String, author: String, message: String, time: Long, replyTo: String = "", channel: String = "",
                     hasMedia: Boolean = false) = PostCall(hash, time, time, 0, author, message, replyTo, channel, hasMedia)

    private fun feedOf(vararg posts: PostCall) =
        socialFeed(posts.toList(), posts.mapIndexed { i, p -> (i + 1).toLong() to p.author }.toMap())

    private fun banned(address: String, since: Long, permanent: Boolean = true, end: Long = 0) = ModerationState(
        bans = mapOf(address to Ban(b, 240, permanent, end, "Spam", since, BanStart(since, since, 0))),
    )

    @Test
    fun theFeedIsTheSameWhenNothingIsModerated() {
        val feed = feedOf(post(hx(1), a, "one", 10))
        assertSame(feed, moderatedFeed(feed, ModerationState.None, 240).feed)
    }

    @Test
    fun aPostMadeDuringItsAuthorBanIsNotShown() {
        val feed = feedOf(post(hx(1), b, "during the ban", 20), post(hx(2), a, "a reply", 30, replyTo = "1"))
        assertTrue(moderatedFeed(feed, banned(b, 19), 240).feed.isEmpty())
    }

    @Test
    fun anOlderPostStaysAPlaceholderWhileAnAnswerIsShown() {
        val feed = feedOf(
            post(hx(1), b, "before the ban", 10, hasMedia = true),
            post(hx(2), b, "alone", 11),
            post(hx(3), a, "a reply", 30, replyTo = "1"),
        )
        val shown = moderatedFeed(feed, banned(b, 20), 240)
        assertEquals(1, shown.feed.size)
        val placeholder = shown.feed[0]
        assertTrue(shown.isPlaceholder(placeholder))
        assertEquals("Spam", shown.placeholders[hx(1)])
        assertEquals("", placeholder.call.message)
        assertFalse(placeholder.call.hasMedia)
        assertEquals(listOf("a reply"), placeholder.replies.map { it.call.message })
    }

    @Test
    fun everythingComesBackOnceTheBanEnded() {
        val feed = feedOf(post(hx(1), b, "during the ban", 21))
        val state = banned(b, 20, permanent = false, end = 241)
        assertEquals(1, moderatedFeed(feed, state, 241).feed.size)
        assertTrue(moderatedFeed(feed, state, 240).feed.isEmpty())
    }

    @Test
    fun aHiddenCommentStaysWhileItsAnswerIsShown() {
        val feed = feedOf(
            post(hx(1), a, "post", 10),
            post(hx(2), b, "reply", 20, replyTo = "1"),
            post(hx(3), c, "hidden comment", 30, replyTo = "2", channel = "discuss:2"),
            post(hx(4), a, "answer", 40, replyTo = "3", channel = "discuss:2"),
            post(hx(5), c, "hidden alone", 50, replyTo = "2", channel = "discuss:2"),
            post(hx(6), c, LIKE, 60, replyTo = "1"),
        )
        val state = ModerationState(hidden = listOf(3, 5, 6).associate { hx(it) to HiddenPost(b, 240, "", 0) })
        val shown = moderatedFeed(feed, state, 240)
        assertEquals("a like is never hidden", 1, shown.feed[0].likes)
        assertEquals(listOf("", "answer"), shown.feed[0].replies[0].replies.map { it.call.message })
    }

    @Test
    fun aCommentAnsweredWithAPaddedIdKeepsItsPlaceholder() {
        val feed = feedOf(
            post(hx(1), a, "post", 10),
            post(hx(2), b, "reply", 20, replyTo = "1"),
            post(hx(3), c, "hidden comment", 30, replyTo = "2", channel = "discuss:2"),
            post(hx(4), a, "answer", 40, replyTo = "03", channel = "discuss:2"),
        )
        val state = ModerationState(hidden = mapOf(hx(3) to HiddenPost(b, 240, "", 0)))
        assertEquals(listOf("", "answer"), moderatedFeed(feed, state, 240).feed[0].replies[0].replies.map { it.call.message })
    }

    @Test
    fun aProfileListsNoPlaceholderButCountsItsLikes() {
        val feed = feedOf(
            post(hx(1), b, "before the ban", 10),
            post(hx(2), a, LIKE, 20, replyTo = "1"),
            post(hx(3), a, "a reply", 30, replyTo = "1"),
        )
        val shown = moderatedFeed(feed, banned(b, 15), 240).feed
        val profile = socialProfile(shown, b)
        assertTrue(profile.posts.isEmpty())
        assertEquals(1, profile.likesReceived)
        assertTrue(shown[0].call.isModeratedPlaceholder)
        assertFalse(feed[0].call.isModeratedPlaceholder)
    }

    @Test
    fun everyPostKnowsTheThreadThatHoldsIt() {
        val feed = feedOf(
            post(hx(1), a, "post", 10),
            post(hx(2), b, "reply", 20, replyTo = "1"),
            post(hx(3), c, "comment", 30, replyTo = "2", channel = "discuss:2"),
        )
        assertEquals(mapOf(hx(1) to 1L, hx(2) to 1L, hx(3) to 1L), threadRoots(feed))
    }

    @Test
    fun aPostEarlierInTheBanBlockWasMadeBeforeIt() {
        val ban = banned(b, 20).bans.getValue(b).copy(since = BanStart(20, 20, 3))
        val state = ModerationState(bans = mapOf(b to ban))
        val at = { index: Int -> post(hx(1), b, "text", 20).copy(index = index) }
        assertEquals(HiddenStatus.Hidden("Spam"), hiddenStatus(at(2), state, 240))
        assertEquals(HiddenStatus.DuringBan, hiddenStatus(at(4), state, 240))
        assertNull("a like", hiddenStatus(post(hx(9), b, LIKE, 30, replyTo = "1"), banned(b, 0), 240))
    }

    // The sync, against a node holding one report per height of [reports].
    private class FakeNode(val head: Long, val reports: List<Long>, val events: Boolean = true) : ModerationSource {
        val ranges = mutableListOf<Pair<Long, Long>>()
        override fun head() = head
        override fun blocksWithContract(from: Long, to: Long): List<Long> {
            ranges += from to to
            return reports.filter { it in from..to }
        }

        override fun calls(heights: List<Long>) = JSONArray(heights.map { h ->
            val call = JSONObject().put("hash", "0x" + h.toString().padStart(64, '0')).put("height", h).put("index", 0)
                .put("timestamp", h).put("success", true)
            if (events) call.put("events", JSONArray().put(JSONObject().put("contract", MODERATION_CONTRACT).put("event", "report")
                .put("args", JSONArray(listOf("0x" + "aa".repeat(20), "0x" + "bb".repeat(20), "0x" + "at $h".toByteArray().joinToString("") { "%02x".format(it) })))))
            call
        })
    }

    @Test
    fun theSyncReadsEveryBlockInRanges() {
        val first = MODERATION_FIRST_BLOCK
        val head = first + MODERATION_RANGE + 20
        val node = FakeNode(head, listOf(first, first + 9, first + MODERATION_RANGE + 3))
        val saved = mutableListOf<Long>()
        val cache = syncModeration(ModerationCache.empty(), node, { saved += it.high })
        assertEquals(head, cache.high)
        assertEquals(listOf("at $first", "at ${first + 9}", "at ${first + MODERATION_RANGE + 3}"), cache.events.map { it.reason })
        assertEquals(listOf(first + MODERATION_RANGE - 1, head), saved)
        assertEquals(listOf(first to first + MODERATION_RANGE - 1, first + MODERATION_RANGE to head), node.ranges)
        val again = syncModeration(cache, node)
        assertSame("up to date: nothing read", cache, again)
    }

    @Test
    fun theSyncStopsOnANodeWithoutEventsAndWhenStopped() {
        val node = FakeNode(MODERATION_FIRST_BLOCK + 5, listOf(MODERATION_FIRST_BLOCK + 1), events = false)
        val saved = mutableListOf<ModerationCache>()
        assertTrue(runCatching { syncModeration(ModerationCache.empty(), node, { saved += it }) }.exceptionOrNull() is NoEventsException)
        assertTrue(saved.isEmpty())
        val stopped = FakeNode(MODERATION_FIRST_BLOCK + 5, listOf(MODERATION_FIRST_BLOCK + 1))
        val start = ModerationCache.empty()
        assertSame(start, syncModeration(start, stopped, stopped = { true }))
        assertTrue(stopped.ranges.isEmpty())
    }

    @Test
    fun theCacheIsReadBackAndStartsAgainForAnotherContract() {
        val cache = ModerationCache(MODERATION_FIRST_BLOCK + 10, listOf(decodeEvent("ban", banArgs(a, b, 240, true, 0, "é"), order)!!))
        assertEquals(cache, ModerationCache.fromJson(cache.toJson()))
        assertEquals(ModerationCache.empty(), ModerationCache.fromJson(cache.toJson().put("contract", a)))
        assertEquals(ModerationCache.empty(), ModerationCache.fromJson(cache.toJson().put("version", 9)))
    }

    @Test
    fun aReportIsRefusedBeforeItIsSigned() {
        val none = ModerationState.None
        assertNull(none.reportRefusal(240, a, b, "Spam"))
        assertEquals(ModerationRefusal.NoAccount, none.reportRefusal(240, null, b, "Spam"))
        assertEquals(ModerationRefusal.Self, none.reportRefusal(240, a, a, "x"))
        assertEquals(ModerationRefusal.NoReason, none.reportRefusal(240, a, b, "  "))
        assertEquals(ModerationRefusal.TooLong, none.reportRefusal(240, a, b, "x".repeat(281)))
        assertEquals(ModerationRefusal.AlreadyBanned, banned(b, 0).reportRefusal(240, a, b, "Spam"))
        assertEquals(ModerationRefusal.Banned, banned(a, 0).reportRefusal(240, a, b, "Spam"))
        assertEquals(6, reasonBytes("é🙂"))
    }

    @Test
    fun aModeratorIsAStakerOrVouchedForNeverWhileBanned() {
        val state = ModerationState(vouchers = mapOf(b to setOf(a)))
        assertEquals(ModeratorRole.Staker, state.moderatorRole(240, a, 1_000_000.0))
        assertNull(state.moderatorRole(240, a, 999_999.9))
        assertEquals(ModeratorRole.Vouched, state.moderatorRole(240, b, 0.0))
        assertNull(state.copy(bans = banned(b, 0).bans).moderatorRole(240, b, 0.0))
        val refusal = { address: String?, target: ModeratorRole?, epochs: Int, count: Int ->
            state.moderatorRefusal(240, a, ModeratorRole.Staker, address, target, epochs, "", count)
        }
        assertNull(refusal(b, null, 0, 1))
        assertEquals(ModerationRefusal.Self, refusal(a, null, 0, 1))
        assertEquals(ModerationRefusal.TargetStaker, refusal(b, ModeratorRole.Staker, 0, 1))
        assertEquals(ModerationRefusal.TargetVouched, refusal(b, ModeratorRole.Vouched, 0, 1))
        assertEquals(ModerationRefusal.Epochs, refusal(b, null, -1, 1))
        assertEquals(ModerationRefusal.Epochs, refusal(b, null, 65_536, 1))
        assertEquals(ModerationRefusal.Batch, refusal(null, null, 0, 101))
        assertEquals(ModerationRefusal.NotModerator, state.moderatorRefusal(240, c, null))
    }

    @Test
    fun anActionWaitingIsKeyedByItsTargetWhateverItsReason() {
        val ban = ModerationCalls.ban(a, 1, "Spam")
        assertEquals(actionKey(ban), actionKey(ModerationCalls.ban(a, 4, "Scam")))
        assertNotEquals(actionKey(ban), actionKey(ModerationCalls.ban(b, 1, "Spam")))
        assertNotEquals(actionKey(ban), actionKey(ModerationCalls.unban(a, "")))
        assertEquals(actionKey(ModerationCalls.hidePosts(listOf(hx(2), hx(1)), "x")), actionKey(ModerationCalls.hidePosts(listOf(hx(1), hx(2)), "y")))
        assertEquals("0", ban.amount)
        assertEquals(MODERATION_CONTRACT, ban.contract)
    }

    @Test
    fun theCallbacksGasIsSizedFromTheDryRun() {
        val call = ModerationCalls.ban(a, 0, "")
        fun receipt(vararg used: Long) = JSONObject().put("actionResult", JSONObject().put("subActionResults",
            JSONArray(used.map { JSONObject().put("inputAction", JSONObject().put("actionType", 1).put("method", "_banCheckTarget")).put("gasUsed", it) }.toList())))
        assertSame(call, withSizedCallbacks(call, JSONObject()))
        assertEquals(1_500_000L, JSONObject(withSizedCallbacks(call, receipt(500_000, 400_000)).argument!!).getLong("_gasLimit"))
        assertEquals(MODERATION_MIN_CALLBACK_GAS, JSONObject(withSizedCallbacks(call, receipt(10)).argument!!).getLong("_gasLimit"))
        assertEquals(MODERATION_CALLBACK_GAS, JSONObject(withSizedCallbacks(call, receipt(9_000_000)).argument!!).getLong("_gasLimit"))
    }
}
