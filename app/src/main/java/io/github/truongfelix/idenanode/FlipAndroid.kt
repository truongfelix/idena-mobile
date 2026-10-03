package io.github.truongfelix.idenanode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * The Android side of making a flip: frames from photos (cut to 4:3), JPEG, the draft kept in the app's
 * files, and the opt-in image search (DuckDuckGo only, see FlipMaking.kt).
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

/** DuckDuckGo's image search, asked only when the user turned it on (see FlipMaking.kt). */
object DuckSearch {
    private const val USER_AGENT = "Mozilla/5.0 (Idena image search)"
    private val HOSTS = setOf("duckduckgo.com", "external-content.duckduckgo.com")
    private const val MAX_PAGE = 512 * 1024
    private const val MAX_RESULTS = 2 * 1024 * 1024
    private const val MAX_THUMBNAIL = 1024 * 1024

    /** The thumbnails (DuckDuckGo proxy addresses) for [query]; throws when DuckDuckGo does not answer. */
    fun search(query: String): List<String> {
        val q = query.trim().take(160)
        if (q.isEmpty()) return emptyList()
        val landing = duckLandingUrl(q)
        val token = duckToken(String(get(landing, MAX_PAGE), Charsets.UTF_8)) ?: throw IOException("DuckDuckGo gave no search token")
        return duckThumbnails(String(get(duckResultsUrl(q, token), MAX_RESULTS, referer = landing), Charsets.UTF_8))
    }

    /** A thumbnail found by [search]. */
    fun thumbnail(url: String): ByteArray = get(url, MAX_THUMBNAIL)

    /** GET [url], only over HTTPS and only from DuckDuckGo, at most [max] bytes. */
    private fun get(url: String, max: Int, referer: String? = null): ByteArray {
        val parsed = URL(url)
        if (parsed.protocol != "https" || parsed.host !in HOSTS) throw IOException("not a DuckDuckGo address")
        val connection = parsed.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        referer?.let { connection.setRequestProperty("Referer", it) }
        try {
            val code = connection.responseCode
            // DuckDuckGo refuses an address for a while after a few quick searches.
            if (code == 403 || code == 429) throw IOException("DuckDuckGo refuses searches from this connection for now; try again in a few minutes, or use the camera or the gallery")
            if (code !in 200..299) throw IOException("DuckDuckGo answered $code")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > max) throw IOException("DuckDuckGo's answer is too large")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The flip being made, kept in files/flips/draft so that it survives the app: the keyword pair, the
 * frames in story order (JPEG at [WORK_WIDTH] x [WORK_HEIGHT]; the anti-AI one made from [sources]),
 * which frame is the anti-AI one, and the shuffled order.
 */
data class FlipDraft(
    val pairId: Int,
    val frames: List<ByteArray?> = List(FLIP_FRAMES) { null },
    val adversarial: Int = randomAdversarialSlot(),
    /** The pictures the anti-AI frame is made from (JPEG, work size), at most 8. */
    val sources: List<ByteArray> = emptyList(),
    val shuffled: List<Int>? = null,
) {
    /** Whether every story frame is chosen and the anti-AI frame is made. */
    val complete: Boolean
        get() = frames.all { it != null }
}

object FlipDraftStore {
    private fun dir(context: Context) = File(context.filesDir, "flips/draft")

    /** The order of the writes ([DraftWriteOrder]); a write and a clear never run at the same time. */
    val order = DraftWriteOrder()

    /** Saves [draft] when [ticket] is still the newest write (see [DraftWriteOrder]). */
    fun save(context: Context, draft: FlipDraft, ticket: DraftWriteOrder.Ticket) = synchronized(this) {
        if (order.claim(ticket)) save(context, draft)
    }

    fun load(context: Context): FlipDraft? = runCatching {
        val dir = dir(context)
        val json = JSONObject(File(dir, "draft.json").readText())
        val shuffled = json.optJSONArray("shuffled")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        FlipDraft(
            pairId = json.getInt("pairId"),
            frames = (0 until FLIP_FRAMES).map { File(dir, "frame$it.jpg").takeIf { f -> f.exists() }?.readBytes() },
            adversarial = json.getInt("adversarial"),
            sources = (0 until json.optInt("sources")).mapNotNull { File(dir, "source$it.jpg").takeIf { f -> f.exists() }?.readBytes() },
            shuffled = shuffled?.takeIf { it.sorted() == (0 until FLIP_FRAMES).toList() },
        )
    }.getOrNull()

    private fun save(context: Context, draft: FlipDraft) {
        val dir = dir(context).also { it.mkdirs() }
        draft.frames.forEachIndexed { i, bytes ->
            val file = File(dir, "frame$i.jpg")
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
        dir.listFiles { f -> f.name.startsWith("source") }?.forEach { it.delete() }
        draft.sources.forEachIndexed { i, bytes -> File(dir, "source$i.jpg").writeBytes(bytes) }
        val json = JSONObject().put("pairId", draft.pairId).put("adversarial", draft.adversarial).put("sources", draft.sources.size)
        draft.shuffled?.let { json.put("shuffled", JSONArray(it)) }
        File(dir, "draft.json").writeText(json.toString())
    }

    /** Forgets the draft, and every write of it still on its way. */
    fun clear(context: Context) = synchronized(this) {
        order.clear()
        dir(context).deleteRecursively()
    }

    private fun sentFile(context: Context) = File(context.filesDir, "flips/sent.json")

    /** The flip sent last while it may not be in a block yet ([SentFlip]). */
    fun sent(context: Context): SentFlip? = runCatching { SentFlip.fromJson(JSONObject(sentFile(context).readText())) }.getOrNull()

    fun setSent(context: Context, sent: SentFlip?) {
        val file = sentFile(context)
        if (sent == null) file.delete() else { file.parentFile?.mkdirs(); file.writeText(sent.toJson().toString()) }
    }

    private const val SEARCH_ON = "search_on"

    /** Whether the user turned on the image search (DuckDuckGo); off until then. */
    fun searchOn(context: Context): Boolean = File(context.filesDir, "flips/$SEARCH_ON").exists()

    fun setSearchOn(context: Context, on: Boolean) {
        val file = File(context.filesDir, "flips/$SEARCH_ON")
        file.parentFile?.mkdirs()
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

