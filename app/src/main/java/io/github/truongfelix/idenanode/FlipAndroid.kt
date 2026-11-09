package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * The Android side of making a flip: frames from photos (cut to 4:3), JPEG, the drafts kept in the app's
 * files, and the choice of the opt-in picture search ([PictureSearch]).
 */

/** The size of a frame while the flip is made (4:3, cut down to [FLIP_WIDTH] x [FLIP_HEIGHT] at the end). */
const val WORK_WIDTH = 480
const val WORK_HEIGHT = 360

/** The picture [bytes] cut to 4:3 around its middle and scaled to [width] x [height], as ARGB pixels; null when Android cannot read it. */
fun framePixels(bytes: ByteArray, width: Int = WORK_WIDTH, height: Int = WORK_HEIGHT): IntArray? {
    val source = decodeUpright(bytes, maxOf(width, height)) ?: return null
    val ratio = width.toFloat() / height
    val crop = if (source.width.toFloat() / source.height > ratio) {
        val w = (source.height * ratio).toInt()
        Rect((source.width - w) / 2, 0, (source.width - w) / 2 + w, source.height)
    } else {
        val h = (source.width / ratio).toInt()
        Rect(0, (source.height - h) / 2, source.width, (source.height - h) / 2 + h)
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(source, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    return IntArray(width * height).also { out.getPixels(it, 0, width, 0, 0, width, height) }
}

/** [pixels] ([width] x [height]) as a bitmap. */
fun pixelBitmap(pixels: IntArray, width: Int, height: Int): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/** [pixels] ([width] x [height]) scaled to [toWidth] x [toHeight] and encoded as JPEG at [quality]. */
fun jpeg(pixels: IntArray, width: Int, height: Int, toWidth: Int = width, toHeight: Int = height, quality: Int = 90): ByteArray {
    var bitmap = pixelBitmap(pixels, width, height)
    if (toWidth != width || toHeight != height) bitmap = Bitmap.createScaledBitmap(bitmap, toWidth, toHeight, true)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** A frame of the flip as protected and sent: [FLIP_WIDTH] x [FLIP_HEIGHT], JPEG at [FLIP_JPEG_QUALITY]. */
fun flipFrameJpeg(protectedPixels: IntArray): ByteArray =
    jpeg(protectedPixels, WORK_WIDTH, WORK_HEIGHT, FLIP_WIDTH, FLIP_HEIGHT, FLIP_JPEG_QUALITY)

/**
 * The flip being made for a keyword pair, kept in the app's files ([FlipDrafts]) so that it survives the app: the
 * pair, the frames in story order (JPEG at [WORK_WIDTH] x [WORK_HEIGHT]; the anti-AI one made from [sources]),
 * which frame is the anti-AI one, and the shuffled order. Each own picture keeps its original ([originals], at most
 * [ORIGINAL_MAX_SIDE]) and how it fills its frame ([edits]), so that it can be edited again.
 */
data class FlipDraft(
    val pairId: Int,
    /** The pair's two words: the draft belongs to them. */
    val words: List<Int>,
    val frames: List<ByteArray?> = List(FLIP_FRAMES) { null },
    val adversarial: Int = randomAdversarialSlot(),
    /** The pictures the anti-AI frame is made from (JPEG, work size), at most 8. */
    val sources: List<ByteArray> = emptyList(),
    val shuffled: List<Int>? = null,
    /** The picture each frame was made from (JPEG); null where there is none, or for a draft of 0.3.0 and earlier. */
    val originals: List<ByteArray?> = List(FLIP_FRAMES) { null },
    val edits: List<FrameEdit> = List(FLIP_FRAMES) { FrameEdit() },
    /** The anti-AI frame's place holds an own picture instead (B-163): the flip has four own pictures. */
    val ownPicture: Boolean = false,
) {
    /** Whether every story frame is chosen and the anti-AI frame is made. */
    val complete: Boolean
        get() = frames.all { it != null }

    /** The anti-AI frame's place, or null when the flip has four own pictures. */
    val antiAi: Int?
        get() = if (ownPicture) null else adversarial

    /** Whether [slot] holds an own picture (one the user chose), not the anti-AI picture. */
    fun isOwn(slot: Int): Boolean = slot != antiAi

    /** The pictures of the story, the anti-AI frame left out. */
    val storyFrames: List<ByteArray>
        get() = frames.filterIndexed { i, f -> isOwn(i) && f != null }.map { it!! }

    /** Whether the anti-AI picture made from [source] takes story pictures: it is made again when one changes. */
    fun antiAiUsesStory(source: AdversarialSource): Boolean = source != AdversarialSource.Search || sources.size < FLIP_FRAMES

    /** This draft with no anti-AI picture yet (made again from its pictures); unchanged with four own pictures. */
    fun withoutAntiAi(): FlipDraft = if (ownPicture) this else copy(frames = frames.toMutableList().also { it[adversarial] = null })

    /** This draft with [frame] at [slot], made from [original] with [edit]. */
    fun withPicture(slot: Int, frame: ByteArray, original: ByteArray, edit: FrameEdit): FlipDraft = copy(
        frames = frames.toMutableList().also { it[slot] = frame },
        originals = originals.toMutableList().also { it[slot] = original },
        edits = edits.toMutableList().also { it[slot] = edit },
    )
}

/** The longer side of a picture kept as a frame's original. */
const val ORIGINAL_MAX_SIDE = 1280

/** The flips being made ([FlipDrafts], one per keyword pair), the flip sent last, and the image search choice. */
object FlipDraftStore {
    private fun drafts(context: Context) = FlipDrafts(File(context.filesDir, "flips"))

    // The order of the writes of each pair's draft ([DraftWriteOrder]); a write and a clear never run at the same time.
    private val orders = HashMap<String, DraftWriteOrder>()

    fun order(words: List<Int>): DraftWriteOrder = synchronized(orders) { orders.getOrPut(draftKey(words)) { DraftWriteOrder() } }

    /** Saves [draft] when [ticket] is still the newest write of its pair (see [DraftWriteOrder]). */
    fun save(context: Context, draft: FlipDraft, ticket: DraftWriteOrder.Ticket) = synchronized(this) {
        if (order(draft.words).claim(ticket)) drafts(context).save(draft)
    }

    /** See [FlipDrafts.open]. */
    fun open(context: Context, free: List<KeywordPair>): FlipDraft? = synchronized(this) { drafts(context).open(free) }

    /** See [FlipDrafts.switchTo]. */
    fun switchTo(context: Context, pair: KeywordPair): FlipDraft = synchronized(this) { drafts(context).switchTo(pair) }

    /** Forgets the draft of the pair [words], and every write of it still on its way. */
    fun clear(context: Context, words: List<Int>) = synchronized(this) {
        order(words).clear()
        drafts(context).clear(words)
    }

    private fun sentFile(context: Context) = File(context.filesDir, "flips/sent.json")

    /** The flip sent last while it may not be in a block yet ([SentFlip]). */
    fun sent(context: Context): SentFlip? = runCatching { SentFlip.fromJson(JSONObject(sentFile(context).readText())) }.getOrNull()

    fun setSent(context: Context, sent: SentFlip?) {
        val file = sentFile(context)
        if (sent == null) file.delete() else { file.parentFile?.mkdirs(); file.writeText(sent.toJson().toString()) }
    }

    /** What the anti-AI picture is made from (B-200); [AdversarialSource.Both] until chosen. */
    fun adversarialSource(context: Context): AdversarialSource =
        AdversarialSource.of(runCatching { File(context.filesDir, "flips/adversarial_source").readText().trim() }.getOrNull())

    fun setAdversarialSource(context: Context, source: AdversarialSource) {
        val file = File(context.filesDir, "flips/adversarial_source")
        file.parentFile?.mkdirs()
        file.writeText(source.key)
    }

    private const val SEARCH_ON = "picture_search_on"

    // 0.1.74 and earlier: the search asked DuckDuckGo only (dropped since); that agreement does not cover today's sources.
    private const val DUCK_SEARCH_ON = "search_on"

    /** Whether the user agreed to the picture search ([PictureSource]: its sources); off until then. */
    fun searchOn(context: Context): Boolean = File(context.filesDir, "flips/$SEARCH_ON").exists()

    fun setSearchOn(context: Context, on: Boolean) {
        val file = File(context.filesDir, "flips/$SEARCH_ON")
        file.parentFile?.mkdirs()
        File(context.filesDir, "flips/$DUCK_SEARCH_ON").delete()
        if (on) file.createNewFile() else file.delete()
    }
}

/**
 * The flips this phone sent ([MadeFlip]), one folder each under files/flips/made/: meta.json and the four
 * protected frames in story order. Only the current epoch's are kept.
 */
object MadeFlipStore {
    private fun root(context: Context) = File(context.filesDir, "flips/made")

    /** Keeps [flip] with its [frames]; written to a new folder, then renamed. */
    @Synchronized
    fun add(context: Context, flip: MadeFlip, frames: List<ByteArray>) {
        val root = root(context).also { it.mkdirs() }
        val temp = File(root, "${flip.cid}.new").also { it.deleteRecursively(); it.mkdirs() }
        frames.forEachIndexed { i, bytes -> File(temp, "frame$i.jpg").writeBytes(bytes) }
        File(temp, "meta.json").writeText(flip.toJson().toString())
        val target = File(root, flip.cid)
        target.deleteRecursively()
        check(temp.renameTo(target)) { "cannot keep the flip" }
    }

    /** The flips of [epoch]; the folders of other epochs are deleted. */
    @Synchronized
    fun list(context: Context, epoch: Int): List<MadeFlip> {
        val dirs = root(context).listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val flip = runCatching { MadeFlip.fromJson(JSONObject(File(dir, "meta.json").readText())) }.getOrNull()
            if (flip == null || flip.epoch != epoch) {
                dir.deleteRecursively()
                null
            } else {
                flip
            }
        }
    }

    /** The frames of flip [cid] in story order, or null when they are not all there. */
    fun frames(context: Context, cid: String): List<ByteArray>? = runCatching {
        (0 until FLIP_FRAMES).map { File(root(context), "$cid/frame$it.jpg").readBytes() }
    }.getOrNull()

    @Synchronized
    fun remove(context: Context, cid: String) {
        File(root(context), cid).deleteRecursively()
    }
}

