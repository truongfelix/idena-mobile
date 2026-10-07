package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** What the editor writes: the action's name, the editor's title, what it answers and that text. */
data class Draft(val what: String, val title: String, val target: PostTarget, val answering: PostCall?)

/** An image chosen for a post: its encodings at the offered sizes (longer side in pixels), and the chosen size. */
data class ImageDraft(val sizes: Map<Int, ByteArray>, val chosen: Int) {
    val bytes: ByteArray
        get() = sizes.getValue(chosen)
}

/** A file stored on IPFS before a post: the text or the image, its CID and the node's fee for storing it. */
class IpfsFile(val what: String, val bytes: ByteArray, val cid: String, val fee: BigDecimal)

/**
 * A paid action waiting for the user's confirmation, with the node's fee estimates: the files to store on
 * IPFS first ([files]), then the call.
 */
class PendingPost(
    val what: String,
    val call: SocialCall,
    val from: String,
    val fee: PostFee,
    val detail: String?,
    val files: List<IpfsFile> = emptyList(),
    /** Whether sending it ends the editor's draft. */
    val fromEditor: Boolean = false,
) {
    val totalFee: BigDecimal
        get() = fee.fee + files.sumOf { it.fee }
}

/**
 * Writing to idena.social for the Social tab ([SocialModel.sending]): the editor's draft and the paid
 * actions, one at a time, each shown with the node's fees before it is sent. [onInBlock] runs once a sent
 * action is in a block: the model then reads the new blocks.
 */
class SocialSending(private val context: Context, private val scope: CoroutineScope, private val onInBlock: () -> Unit) {
    // The editor: its draft, text, image and whether the text goes on IPFS.
    var draft by mutableStateOf<Draft?>(null)
    var draftText by mutableStateOf("")
    var draftImage by mutableStateOf<ImageDraft?>(null)
    var textOnIpfs by mutableStateOf(false)

    // Paid actions: one at a time, a confirmation, and what happened to the last one.
    var busy by mutableStateOf(false)
        private set
    var confirm by mutableStateOf<PendingPost?>(null)

    /** The last paid action while it runs, and for a while after (the status bar shows it). */
    var sendStatus by mutableStateOf<String?>(null)
        private set

    /** What happened to the last paid action, kept for the details. */
    var lastAction by mutableStateOf<String?>(null)
        private set
    private var clearStatus: Job? = null

    /** Shows [text] as the action's status; once the action is over, for [keepMs] only. */
    private fun report(text: String?, keepMs: Long? = null) {
        clearStatus?.cancel()
        sendStatus = text
        if (text != null) lastAction = text
        if (text != null && keepMs != null) {
            clearStatus = scope.launch {
                delay(keepMs)
                if (sendStatus == text) sendStatus = null
            }
        }
    }

    /** Asks the node for the fee of [call], then for the user's confirmation; [detail] tells what it does. */
    fun prepare(what: String, call: SocialCall, detail: String? = null) = prepareWith(what) { posting, from ->
        PendingPost(what, call, from, posting.estimate(from, call), detail)
    }

    /** A like (or a post without files): [message] answering [target]. */
    fun preparePost(what: String, message: String, target: PostTarget) =
        prepare(what, SocialPosting.post(makePostArgument(message, target.replyTo, target.channel)))

    /**
     * The editor's post: its text, on IPFS when [textOnIpfs], and its image, stored on IPFS. Each file is
     * a storeToIpfs transaction before the post, with its own fee; all fees are shown before sending.
     */
    fun prepareDraft() {
        val current = draft ?: return
        val text = draftText.trim()
        val image = draftImage?.bytes
        val textFile = textOnIpfs && text.isNotEmpty()
        prepareWith(current.what) { posting, from ->
            val files = ArrayList<IpfsFile>()
            fun file(what: String, bytes: ByteArray): IpfsFile {
                if (bytes.size > MAX_IPFS_FILE) throw IOException("the $what is too large (${bytes.size / 1000} KB, at most ${MAX_IPFS_FILE / 1000} KB)")
                val cid = posting.cid(bytes)
                return IpfsFile(what, bytes, cid, posting.estimateStore(from, cid, bytes.size)).also { files += it }
            }
            val message = if (textFile) "ipfs://" + file("text", text.toByteArray(Charsets.UTF_8)).cid else text
            val media = image?.let { "ipfs://" + file("image", it).cid }
            val call = SocialPosting.post(
                makePostArgument(message, current.target.replyTo, current.target.channel, media, media?.let { IMAGE_TYPE }),
            )
            val fee = posting.estimate(from, call)
            // Every transaction may take up to twice its fee: the balance must cover them all.
            val most = fee.maxFee + files.sumOf { it.fee * BigDecimal(2) } + BigDecimal(call.amount)
            val balance = posting.balance(from)
            if (balance < most) throw IOException("up to ${most.setScale(2, java.math.RoundingMode.UP)} iDNA needed, the balance is ${balance.setScale(2, java.math.RoundingMode.DOWN)}")
            PendingPost(current.what, call, from, fee, null, files, fromEditor = true)
        }
    }

    private fun prepareWith(what: String, build: (SocialPosting, String) -> PendingPost) {
        busy = true
        report(null)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rpc = rpc(context)
                    build(SocialPosting(rpc), rpc.call("dna_getCoinbaseAddr") as String)
                }
            }
            busy = false
            result.onSuccess { confirm = it }.onFailure { report("$what: the node refused: ${it.message}", ERROR_KEEP_MS) }
        }
    }

    /** Sends a confirmed action: its files to IPFS first, then the call; then scans the new blocks once it is in one. */
    fun send(pending: PendingPost) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching {
                    val posting = SocialPosting(rpc(context))
                    for (file in pending.files) {
                        withContext(Dispatchers.Main) { report("${pending.what}: storing the ${file.what} on IPFS…") }
                        val cid = posting.add(file.bytes)
                        if (cid != file.cid) throw IOException("the node stored the ${file.what} under another CID ($cid)")
                        IpfsStore.put(context, cid, file.bytes)
                        posting.store(cid)
                    }
                    posting.send(pending.from, pending.call, pending.fee.maxFee)
                }
            }.getOrElse {
                busy = false
                report("${pending.what}: the node refused: ${it.message}", ERROR_KEEP_MS)
                return@launch
            }
            busy = false
            if (pending.fromEditor) {
                draft = null
                draftText = ""
                draftImage = null
                textOnIpfs = false
            }
            report("${pending.what} sent, waiting for a block (${hash.take(10)}…)")
            while (!withContext(Dispatchers.IO) { runCatching { rpc(context).isMined(hash) }.getOrDefault(false) }) delay(10_000)
            report("${pending.what} is in a block.", DONE_KEEP_MS)
            onInBlock()
        }
    }

    companion object {
        /** The type of the images the app posts. */
        const val IMAGE_TYPE = "image/webp"

        /** How long the status bar shows a refusal, and a done action. */
        private const val ERROR_KEEP_MS = 120_000L
        private const val DONE_KEEP_MS = 30_000L
    }
}
