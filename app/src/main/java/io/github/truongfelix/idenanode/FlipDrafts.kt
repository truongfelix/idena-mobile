package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The folder name of the draft of the keyword pair [words]. */
fun draftKey(words: List<Int>): String = words.joinToString("-")

/** The pair the flip screen opens on: the one worked on last ([last], its words) while it is free, else the first free one. */
fun draftPair(free: List<KeywordPair>, last: List<Int>?): KeywordPair? = free.firstOrNull { it.words == last } ?: free.firstOrNull()

/**
 * The flips being made, one draft per keyword pair under [flipsDir]/drafts/<words>, so that the pictures made for
 * a pair only show with its words: the frames in story order (JPEG at [WORK_WIDTH] x [WORK_HEIGHT]; the anti-AI
 * one made from the sources), which frame is the anti-AI one, and the shuffled order. The pair worked on last is
 * kept too: the flip screen opens on it.
 */
class FlipDrafts(private val flipsDir: File) {
    private val draftsDir = File(flipsDir, "drafts")
    private val lastFile = File(draftsDir, "last")

    // 0.1.74 and earlier: a single draft, whatever pair it showed under; moved to its pair by [open].
    private val oldDir = File(flipsDir, "draft")

    private fun dir(words: List<Int>) = File(draftsDir, draftKey(words))

    /** The draft of [pair], or null when it has none. */
    fun load(pair: KeywordPair): FlipDraft? = runCatching {
        val dir = dir(pair.words)
        val json = JSONObject(File(dir, "draft.json").readText())
        val shuffled = json.optJSONArray("shuffled")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        FlipDraft(
            pairId = pair.id,
            words = pair.words,
            frames = (0 until FLIP_FRAMES).map { File(dir, "frame$it.jpg").takeIf { f -> f.exists() }?.readBytes() },
            adversarial = json.getInt("adversarial"),
            sources = (0 until json.optInt("sources")).mapNotNull { File(dir, "source$it.jpg").takeIf { f -> f.exists() }?.readBytes() },
            shuffled = shuffled?.takeIf { it.sorted() == (0 until FLIP_FRAMES).toList() },
        )
    }.getOrNull()

    fun save(draft: FlipDraft) {
        val dir = dir(draft.words).also { it.mkdirs() }
        draft.frames.forEachIndexed { i, bytes ->
            val file = File(dir, "frame$i.jpg")
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
        dir.listFiles { f -> f.name.startsWith("source") }?.forEach { it.delete() }
        draft.sources.forEachIndexed { i, bytes -> File(dir, "source$i.jpg").writeBytes(bytes) }
        val json = JSONObject().put("adversarial", draft.adversarial).put("sources", draft.sources.size)
        draft.shuffled?.let { json.put("shuffled", JSONArray(it)) }
        File(dir, "draft.json").writeText(json.toString())
    }

    /** Forgets the draft of the pair [words]. */
    fun clear(words: List<Int>) {
        dir(words).deleteRecursively()
    }

    /** The words of the pair worked on last; null before the first. */
    fun last(): List<Int>? = runCatching { lastFile.readText().trim().split("-").map { it.toInt() } }.getOrNull()

    fun setLast(words: List<Int>) {
        draftsDir.mkdirs()
        lastFile.writeText(draftKey(words))
    }

    /**
     * The draft the flip screen shows with the [free] pairs: the one of the pair worked on last, else of the first
     * free pair (a new one when it has none). The drafts of the pairs that are no longer free (used, or of another
     * epoch) are deleted. Null without a free pair: then nothing is deleted.
     */
    fun open(free: List<KeywordPair>): FlipDraft? {
        if (free.isEmpty()) return null
        moveOldDraft(free)
        val keep = free.map { draftKey(it.words) }.toSet()
        draftsDir.listFiles { f -> f.isDirectory && f.name !in keep }?.forEach { it.deleteRecursively() }
        val pair = draftPair(free, last()) ?: return null
        return load(pair) ?: FlipDraft(pair.id, pair.words)
    }

    /** The draft of [pair], now the pair worked on (a new one when it has none). */
    fun switchTo(pair: KeywordPair): FlipDraft {
        setLast(pair.words)
        return load(pair) ?: FlipDraft(pair.id, pair.words)
    }

    /** The draft of 0.1.74 and earlier goes to the pair it was made for while that pair is free; else it is dropped. */
    private fun moveOldDraft(free: List<KeywordPair>) {
        if (!oldDir.exists()) return
        runCatching {
            val pairId = JSONObject(File(oldDir, "draft.json").readText()).getInt("pairId")
            val pair = free.firstOrNull { it.id == pairId } ?: return@runCatching
            if (dir(pair.words).exists()) return@runCatching
            draftsDir.mkdirs()
            if (oldDir.renameTo(dir(pair.words))) setLast(pair.words)
        }
        oldDir.deleteRecursively()
    }
}
