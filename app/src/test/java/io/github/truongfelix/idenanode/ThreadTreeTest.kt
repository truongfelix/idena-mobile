package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTreeTest {
    private fun comment(id: Long, replyTo: Long) = SocialPost(
        id,
        PostCall("0x$id", id, id, 0, "0xa", "text $id", replyTo.toString(), discussChannel(10), hasMedia = false),
        emptyList(),
        emptyList(),
        emptyList(),
    )

    @Test
    fun commentsNestUnderWhatTheyAnswer() {
        // Reply 10: 11 and 14 answer it, 12 answers 11, 13 answers 12, 15 answers an unknown comment.
        val tree = commentTree(10, listOf(comment(11, 10), comment(12, 11), comment(13, 12), comment(14, 10), comment(15, 99)))
        assertEquals(listOf(11L, 14L, 15L), tree.map { it.comment.id })
        val first = tree[0]
        assertEquals(listOf(12L), first.children.map { it.comment.id })
        assertEquals(listOf(13L), first.children[0].children.map { it.comment.id })
        assertEquals(2, first.descendants)
        assertEquals(0, tree[1].descendants)
        assertTrue(first.contains(13))
        assertFalse(first.contains(14))
    }

    @Test
    fun aLoopStillShowsEveryComment() {
        val tree = commentTree(10, listOf(comment(11, 12), comment(12, 11), comment(13, 13)))
        assertEquals(setOf(11L, 12L, 13L), tree.flatMap { listOf(it.comment.id) + it.children.map { c -> c.comment.id } }.toSet())
        assertEquals(3, tree.size + tree.sumOf { it.descendants })
    }
}
