package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The hidden words, the posts hidden one by one and the topics blocked, kept on the phone ([SocialStore]); each change
 * saved at once, one save at a time ([lock], shared with the People list's). The blocked accounts are marks of the
 * People list ([Person.blocked]).
 */
class SocialHiddenLists(private val context: Context, private val scope: CoroutineScope, private val lock: Mutex) {
    var words by mutableStateOf(SocialStore.hiddenWords(context))
        private set

    /** [words] as a filter. */
    val wordFilter: WordFilter by derivedStateOf { WordFilter(words) }

    /** The transaction hashes of the posts, replies and comments hidden, lowercase. */
    var posts by mutableStateOf(SocialStore.hiddenPosts(context))
        private set

    var topics by mutableStateOf(SocialStore.blockedTopics(context))
        private set

    fun changeWords(next: Set<String>) {
        if (next == words) return
        words = next
        save { SocialStore.setHiddenWords(context, next) }
    }

    /** Hides the post, reply or comment of [hash], or shows it again ([hide] false). */
    fun hidePost(hash: String, hide: Boolean) {
        val lower = hash.lowercase()
        changePosts(if (hide) posts + lower else posts - lower)
    }

    fun changePosts(next: Set<String>) {
        if (next == posts) return
        posts = next
        save { SocialStore.setHiddenPosts(context, next) }
    }

    fun changeTopics(next: Set<String>) {
        if (next == topics) return
        topics = next
        save { SocialStore.setBlockedTopics(context, next) }
    }

    private fun save(write: () -> Unit) {
        scope.launch(Dispatchers.IO) { lock.withLock { runCatching(write) } }
    }
}
