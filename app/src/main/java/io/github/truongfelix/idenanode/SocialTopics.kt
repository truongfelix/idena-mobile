package io.github.truongfelix.idenanode

/*
 * Topics: a post whose channelId is "r/<name>" is in topic <name>. Anyone writes in any topic, there is no
 * moderation and no contract: the name is only the post's channel. Its replies, comments, likes and tips keep
 * today's format and follow it. idena.social shows no post in such a channel; our apps show them in the feed
 * with their topic, and in the topic's own feed.
 */

/** The channel prefix of a topic. */
const val TOPIC_PREFIX = "r/"

/** A topic's name: lower case letters, digits and "_", 3 to 21 characters. */
private val TOPIC_NAME = Regex("^[a-z0-9_]{3,21}$")

/** The topic of a post in [channel], or null when the channel is not a topic's. */
fun topicOf(channel: String): String? =
    channel.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)?.takeIf { TOPIC_NAME.matches(it) }

/** The channel of the posts in topic [name]. */
fun topicChannel(name: String): String = TOPIC_PREFIX + name

/** The topic of a post: null when it is in none. */
val SocialPost.topic: String?
    get() = topicOf(call.channel)

/**
 * The topic name typed in the editor: "Idena", "r/idena" and " idena " are "idena"; null when what is left is not
 * a topic name. Blank means no topic: the caller checks that first.
 */
fun typedTopic(text: String): String? =
    text.trim().removePrefix(TOPIC_PREFIX).lowercase().takeIf { TOPIC_NAME.matches(it) }

/** A topic in the posts read: its posts, and the block and time of the newest thing in one of its threads. */
data class TopicSummary(val name: String, val posts: Int, val lastHeight: Long, val lastTime: Long)

/** The topics of [feed], the latest activity first; [query] keeps those whose name holds it. */
fun topicList(feed: List<SocialPost>, query: String = ""): List<TopicSummary> {
    val wanted = query.trim().removePrefix(TOPIC_PREFIX).lowercase()
    return feed.mapNotNull { post -> post.topic?.let { it to post } }
        .groupBy({ it.first }, { it.second })
        .filterKeys { wanted.isEmpty() || wanted in it }
        .map { (name, posts) ->
            val last = posts.maxBy { lastActivityHeight(it) }
            TopicSummary(name, posts.size, lastActivityHeight(last), lastActivityTime(last))
        }
        .sortedWith(compareByDescending<TopicSummary> { it.lastHeight }.thenBy { it.name })
}

/** The posts of topic [name], as the feed has them. */
fun topicFeed(feed: List<SocialPost>, name: String): List<SocialPost> = feed.filter { it.topic == name }

/** The time of the newest thing in [post]'s thread, as [lastActivityHeight]. */
private fun lastActivityTime(post: SocialPost): Long = maxOf(
    post.call.time,
    post.likeCalls.maxOfOrNull { it.time } ?: 0,
    post.tips.maxOfOrNull { it.time } ?: 0,
    post.replies.maxOfOrNull { lastActivityTime(it) } ?: 0,
)
