package io.github.truongfelix.idenanode

/*
 * The comments under a reply as a tree, as Reddit nests them: idena.social keeps them in one channel per
 * reply ("discuss:<reply id>"), each one naming what it answers (replyTo: the reply or another comment).
 */

/** A comment with the comments that answer it. */
data class CommentNode(val comment: SocialPost, val children: List<CommentNode>) {
    /** Every comment under this one, at any depth. */
    val descendants: Int
        get() = children.sumOf { 1 + it.descendants }
}

/**
 * The comments of reply [replyId] as a tree, in their order: a comment answering the reply or a comment
 * not among them (unknown, or not read yet) is at the top; the others under the comment they answer.
 */
fun commentTree(replyId: Long, comments: List<SocialPost>): List<CommentNode> {
    val ids = comments.map { it.id }.toSet()
    fun parentOf(comment: SocialPost): Long? =
        comment.call.replyTo.toLongOrNull()?.takeIf { it != replyId && it != comment.id && it in ids }
    val byParent = comments.groupBy(::parentOf)
    // A comment is placed once: no loop even if two comments named each other.
    val placed = HashSet<Long>()
    fun build(parent: Long?): List<CommentNode> = byParent[parent].orEmpty().filter { placed.add(it.id) }.map { CommentNode(it, build(it.id)) }
    val roots = build(null)
    // Comments in a loop (none reachable from the top) still show, at the top.
    val rest = comments.filter { it.id !in placed }.map { placed.add(it.id); CommentNode(it, emptyList()) }
    return roots + rest
}

/** Whether [id] is [node] or one of its comments, at any depth. */
fun CommentNode.contains(id: Long): Boolean = comment.id == id || children.any { it.contains(id) }
