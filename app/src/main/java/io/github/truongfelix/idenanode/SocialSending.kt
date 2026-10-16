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
import java.util.concurrent.ConcurrentHashMap

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/** What the editor writes: the action's name, the editor's title, what it answers and that text. */
data class Draft(val what: String, val title: String, val target: PostTarget, val answering: PostCall?)

/** What the editor holds: its text, its image, whether the text goes on IPFS, and the topic typed. */
data class EditorContent(val text: String, val image: ImageDraft?, val textOnIpfs: Boolean, val topic: String = "")

/**
 * What the editor gets back when the post it sent ([sent]; null for a like or a tip) is dropped by the node or
 * refused by the contract: the post, if the editor holds no text and no image by then ([text], [image]); null when
 * it holds something, which stays. The editor does not open: as after Cancel, the next post starts from it.
 */
fun contentToPutBack(sent: EditorContent?, text: String, image: ImageDraft?): EditorContent? =
    sent?.takeIf { text.isBlank() && image == null }

/** What the report of a failed send says of the editor's post ([sent]), put back or not; null for a like or a tip. */
fun putBackNote(sent: EditorContent?, putBack: Boolean): String? = when {
    sent == null -> null
    putBack -> "The draft is back in the editor."
    else -> "The editor holds another draft, so this one was not put back."
}

/** An image chosen for a post: its encodings at the offered sizes (longer side in pixels), and the chosen size. */
data class ImageDraft(val sizes: Map<Int, ByteArray>, val chosen: Int) {
    val bytes: ByteArray
        get() = sizes.getValue(chosen)
}

/**
 * A file stored on IPFS before a post: the text or the image, its CID and the node's fee for storing it;
 * no fee when the app [stored] it already (a try whose post failed).
 */
class IpfsFile(val what: String, val bytes: ByteArray, val cid: String, val fee: BigDecimal, val stored: Boolean = false)

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
    /** Whether sending it ends the editor's draft (kept until the block, put back if the send fails). */
    val fromEditor: Boolean = false,
    /** For a like: the post, reply or comment it likes. */
    val likeOf: Long? = null,
    /** For a profile call from a vouched member: the extra amount it sends, burned by the contract. */
    val memberExtra: MemberExtra? = null,
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

    /** The topic typed for a new post ([typedTopic]); blank for none. */
    var draftTopic by mutableStateOf("")

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

    /** The actions sent and not read by the scan yet: a like's pill stays off until the scan reads it. */
    val sent = SentList()

    /** The CIDs of the files this app stored on IPFS since it started: a retry does not store and pay them again. */
    private val stored: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
    fun preparePost(what: String, message: String, target: PostTarget) {
        val call = SocialPosting.post(makePostArgument(message, target.replyTo, target.channel))
        val likeOf = target.replyTo.takeIf { message == LIKE }
        prepareWith(what) { posting, from -> PendingPost(what, call, from, posting.estimate(from, call), null, likeOf = likeOf) }
    }

    /**
     * A private message [text] to [recipients] (lowercase, the account left out), with their public [keys] in the same
     * order ([findPublicKey]): the plain message in idena.social's format ([plainMessage]), encrypted by the node for the
     * account and each recipient (social_encrypt), then sendMessage with the website's 0.00002 iDNA.
     */
    fun prepareMessage(recipients: List<String>, keys: List<String>, text: String) = prepareWith("Message") { posting, from ->
        val me = from.lowercase()
        val others = recipients.filter { it != me }.ifEmpty { listOf(me) }
        if (keys.size != others.size) throw IOException("no public key for every person of the conversation")
        val plain = plainMessage(me, others, text)
        val parts = posting.encrypt(keys, plain)
        if (parts.size != others.size + 1) throw IOException("the node encrypted ${parts.size} parts for ${others.size + 1} participants")
        val call = SocialPosting.message(sendMessageArgument(parts, keccak256Hex(plain)))
        PendingPost("Message", call, from, posting.estimate(from, call), "A private message to ${others.size} ${if (others.size == 1) "person" else "people"}")
    }

    /**
     * The editor's post: its text, on IPFS when [textOnIpfs], and its image, stored on IPFS. Each file is
     * a storeToIpfs transaction before the post, with its own fee; all fees are shown before sending.
     */
    fun prepareDraft() {
        val current = draft ?: return
        val text = draftText.trim()
        val image = draftImage?.bytes
        val textFile = textOnIpfs && text.isNotEmpty()
        // A new post may go in a topic: its channel.
        val channel = if (current.target != PostTarget.NEW_POST || draftTopic.isBlank()) current.target.channel
        else typedTopic(draftTopic)?.let(::topicChannel)
        prepareWith(current.what) { posting, from ->
            if (current.target == PostTarget.NEW_POST && draftTopic.isNotBlank() && channel == null) {
                throw IOException("\"${draftTopic.trim()}\" is not a topic name (a-z, 0-9 and _, 3 to 21 characters)")
            }
            val files = ArrayList<IpfsFile>()
            fun file(what: String, bytes: ByteArray) = ipfsFile(posting, from, what, bytes).also { files += it }
            val message = if (textFile) "ipfs://" + file("text", text.toByteArray(Charsets.UTF_8)).cid else text
            val media = image?.let { "ipfs://" + file("image", it).cid }
            val call = SocialPosting.post(
                makePostArgument(message, current.target.replyTo, channel, media, media?.let { IMAGE_TYPE }),
            )
            val fee = posting.estimate(from, call)
            // Every transaction may take up to twice its fee: the balance must cover them all.
            val most = fee.maxFee + files.sumOf { it.fee * BigDecimal(2) } + BigDecimal(call.amount)
            val balance = posting.balance(from)
            if (balance < most) throw IOException("up to ${most.setScale(2, java.math.RoundingMode.UP)} iDNA needed, the balance is ${balance.setScale(2, java.math.RoundingMode.DOWN)}")
            PendingPost(current.what, call, from, fee, null, files, fromEditor = true)
        }
    }

    /** A file to store on IPFS from [from] before a call: its CID and fee; no fee when this app stored it already. */
    private fun ipfsFile(posting: SocialPosting, from: String, what: String, bytes: ByteArray): IpfsFile {
        if (bytes.size > MAX_IPFS_FILE) throw IOException("the $what is too large (${bytes.size / 1000} KB, at most ${MAX_IPFS_FILE / 1000} KB)")
        val cid = posting.cid(bytes)
        return if (cid in stored) IpfsFile(what, bytes, cid, BigDecimal.ZERO, stored = true)
        else IpfsFile(what, bytes, cid, posting.estimateStore(from, cid, bytes.size))
    }

    /**
     * A call to the profiles contract ([ProfileCalls]) made by [makeCall] with the "ipfs://" reference of [picture]
     * (a WebP from [encodePicture]), stored on IPFS first with its own fee, as a post's image. A vouched member's call
     * sends the extra amount the contract reads ([NodeProfilesSource.memberExtra]). A first dry run sizes the
     * callbacks' gas ([callbackGas], [keepStateGas]), a second gives the fee; the max fee covers what the callbacks
     * reserve ([profileFee]). [detail] tells what it does.
     */
    fun prepareProfile(what: String, detail: String?, picture: ByteArray? = null, makeCall: (picture: String?) -> SocialCall) =
        prepareWith(what) { posting, from ->
            val files = listOfNotNull(picture?.let { ipfsFile(posting, from, "picture", it) })
            var call = makeCall(files.firstOrNull()?.let { "ipfs://${it.cid}" })
            val extra = NodeProfilesSource(rpc(context)).memberExtra(from, posting.feePerGas().toBigInteger())
            extra?.let { call = call.copy(amount = it.amount) }
            var receipt = posting.estimateReceipt(from, call)
            val gas = callbackGas(receipt)
            val keepGas = keepStateGas(receipt)
            if (gas != null || keepGas != null) {
                call = withCallbackGas(call, gas, keepGas)
                receipt = posting.estimateReceipt(from, call)
            }
            val fee = profileFee(receipt)
            // Every transaction may take up to twice its fee: the balance must cover them all, and the extra amount.
            val most = fee.maxFee + files.sumOf { it.fee * BigDecimal(2) } + BigDecimal(call.amount)
            val balance = posting.balance(from)
            if (balance < most) throw IOException("up to ${most.setScale(2, java.math.RoundingMode.UP)} iDNA needed, the balance is ${balance.setScale(2, java.math.RoundingMode.DOWN)}")
            PendingPost(what, call, from, fee, detail, files, memberExtra = extra)
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

    /**
     * Sends a confirmed action: its files to IPFS first (those not stored yet), then the call; then waits for
     * its block ([waitForBlock]) and has the new blocks scanned. A send the node drops, or the contract
     * refuses, is reported.
     */
    fun send(pending: PendingPost) {
        busy = true
        scope.launch {
            val hash = withContext(Dispatchers.IO) {
                runCatching {
                    val posting = SocialPosting(rpc(context))
                    for (file in pending.files) {
                        if (file.stored) continue
                        withContext(Dispatchers.Main) { report("${pending.what}: storing the ${file.what} on IPFS…") }
                        val cid = posting.add(file.bytes)
                        if (cid != file.cid) throw IOException("the node stored the ${file.what} under another CID ($cid)")
                        IpfsStore.put(context, cid, file.bytes)
                        posting.store(cid)
                        stored += cid
                    }
                    posting.send(pending.from, pending.call, pending.fee.maxFee)
                }
            }.getOrElse {
                busy = false
                report("${pending.what}: the node refused: ${it.message}", ERROR_KEEP_MS)
                return@launch
            }
            busy = false
            val sentContent = if (pending.fromEditor) EditorContent(draftText, draftImage, textOnIpfs, draftTopic) else null
            if (pending.fromEditor) {
                draft = null
                draftText = ""
                draftImage = null
                textOnIpfs = false
                draftTopic = ""
            }
            sent.add(hash, pending.likeOf)
            report("${pending.what} sent, waiting for a block (${hash.take(10)}…)")
            val block = withContext(Dispatchers.IO) { waitForBlock(NodeTxLookup(rpc(context)), hash) }
            val note = if (block == null || block.error != null) putBack(sentContent) else null
            when {
                block == null -> {
                    sent.remove(hash)
                    report("${pending.what} was not sent: the node no longer has it. ${note ?: "Try again."}", ERROR_KEEP_MS)
                }
                block.error != null -> {
                    sent.remove(hash)
                    val refused = "${pending.what} is in a block, but the contract refused it (${block.error}): the fee is paid, nothing is posted."
                    report(listOfNotNull(refused, note).joinToString(" "), ERROR_KEEP_MS)
                }
                else -> {
                    sent.inBlock(hash, block.height)
                    report("${pending.what} is in a block.", DONE_KEEP_MS)
                    onInBlock()
                }
            }
        }
    }

    /** Puts the editor's post that failed ([sent]) back in an empty editor; what the report says of it. */
    private fun putBack(sent: EditorContent?): String? {
        val back = contentToPutBack(sent, draftText, draftImage)
        if (back != null) {
            draftText = back.text
            draftImage = back.image
            textOnIpfs = back.textOnIpfs
            draftTopic = back.topic
        }
        return putBackNote(sent, back != null)
    }

    companion object {
        /** The type of the images the app posts. */
        const val IMAGE_TYPE = "image/webp"

        /** How long the status bar shows a refusal, and a done action. */
        private const val ERROR_KEEP_MS = 120_000L
        private const val DONE_KEEP_MS = 30_000L
    }
}

/** A sent action: its transaction, and for a like the post it likes; [height] is its block once known. */
data class Sent(val hash: String, val likeOf: Long?, val height: Long? = null)

/**
 * The actions sent and not read by the scan yet ([SocialSending.sent]). A like stays here from its send until
 * the scan has read its block, so that its pill stays off and it is not sent twice; a send dropped or refused
 * leaves at once.
 */
class SentList {
    var items by mutableStateOf<List<Sent>>(emptyList())
        private set

    // The block the scan has read through.
    private var scannedThrough = Long.MIN_VALUE

    /** The posts, replies and comments liked by a like in this list. */
    val likes: Set<Long>
        get() = items.mapNotNullTo(HashSet()) { it.likeOf }

    fun add(hash: String, likeOf: Long?) {
        items = items + Sent(hash, likeOf)
    }

    fun remove(hash: String) {
        items = items.filter { it.hash != hash }
    }

    /** [hash] is in block [height]: it leaves once the scan has read that block (now, if it has). */
    fun inBlock(hash: String, height: Long) {
        items = items.map { if (it.hash == hash) it.copy(height = height) else it }
        scanned(scannedThrough)
    }

    /** The scan has read the blocks through [height]. */
    fun scanned(height: Long) {
        scannedThrough = height
        if (items.any { it.height != null && it.height <= height }) items = items.filter { it.height == null || it.height > height }
    }
}
