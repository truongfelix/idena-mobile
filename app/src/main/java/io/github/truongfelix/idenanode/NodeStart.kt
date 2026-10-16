package io.github.truongfelix.idenanode

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/*
 * What a starting node does, before it serves its whole RPC. The node opens a small RPC first, which answers
 * bcn_syncing only, with "Initializing database..." while it opens its chain database: that includes the full
 * compaction a start runs after much syncing (idena-go node.OpenDatabaseWithWriteBuffer, minutes), which only the
 * node's log tells (output.log in its folder).
 */

/** The step of a start, as the status bar, the Node card and the notification name it. */
enum class StartPhase(val label: String) {
    Starting("Starting the node…"),
    Opening("Opening the database…"),
    Compacting("Compacting the database"),
}

/** The message of the node's first RPC while it opens its chain database. */
private const val OPENING_MESSAGE = "Initializing database"

/** The log lines of a start (idena-go node.go): the first RPC is open, a compaction starts, it is done. */
private const val START_LINE = "initial HTTP endpoint opened"
private const val COMPACTION_START_LINE = "Start compacting DB"
private const val COMPACTION_END_LINE = "DB compacted"

/** How much of the end of the node's log the app reads: a start writes a few lines. */
private const val LOG_TAIL_BYTES = 32 * 1024

/**
 * The step of a start from the first RPC's [message] (bcn_syncing) and the end of the node's log ([logTail]): the
 * compaction while the log's last start has begun one and not ended it, the opening while the message says so,
 * else the rest of the start (IPFS, the peers' network).
 */
fun startPhase(message: String, logTail: String?): StartPhase = when {
    logTail != null && compacting(logTail) -> StartPhase.Compacting
    message.startsWith(OPENING_MESSAGE) -> StartPhase.Opening
    else -> StartPhase.Starting
}

/** Whether the last start in [logTail] runs a compaction that has not ended. */
fun compacting(logTail: String): Boolean {
    val start = logTail.lastIndexOf(START_LINE)
    if (start < 0) return false
    val begun = logTail.indexOf(COMPACTION_START_LINE, start)
    return begun >= 0 && logTail.indexOf(COMPACTION_END_LINE, begun + COMPACTION_START_LINE.length) < 0
}

/** The bar's words for a start at [phase], with the minutes since the start for a compaction. */
fun startLabel(phase: StartPhase, runningForMs: Long?): String =
    if (phase == StartPhase.Compacting && runningForMs != null) "${phase.label} · ${runningForMs / 60_000} min" else phase.label

/** The node's log, output.log in its folder (idena-go node.StartMobileNodeWithKey). */
fun nodeLogFile(context: Context): File = File(NodeSettings.nodeDir(context), "output.log")

/**
 * The node's log above which a start cuts it, and what it keeps: the node only appends to it (idena-go
 * log.FileHandler), ~4.5 MB a day synced. Its readers read its end only: the start's step, the node report, the
 * router's peers (RouterNet.kt, 512 KiB).
 */
const val LOG_LIMIT_BYTES = 5L * 1024 * 1024
const val LOG_KEPT_BYTES = 1L * 1024 * 1024

/**
 * Cuts [file] above [limit] bytes to its last [keep] bytes, from the first whole line in them; nothing below the
 * limit, or when it cannot be read. Run while the node does not write it: before its start.
 */
fun trimLog(file: File, limit: Long = LOG_LIMIT_BYTES, keep: Long = LOG_KEPT_BYTES) {
    if (file.length() <= limit) return
    runCatching {
        val tail = RandomAccessFile(file, "r").use { f ->
            val buffer = ByteArray(keep.toInt())
            f.seek(f.length() - keep)
            f.readFully(buffer)
            buffer
        }
        val lineStart = tail.indexOf('\n'.code.toByte()) + 1
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(tail, lineStart, tail.size - lineStart) }
        if (!tmp.renameTo(file)) tmp.delete()
    }
}

/** The last [bytes] of [file] as text; null when it cannot be read. */
fun readTail(file: File, bytes: Int = LOG_TAIL_BYTES): String? = runCatching {
    RandomAccessFile(file, "r").use { f ->
        val from = maxOf(0L, f.length() - bytes)
        val buffer = ByteArray((f.length() - from).toInt())
        f.seek(from)
        f.readFully(buffer)
        String(buffer, Charsets.UTF_8)
    }
}.getOrNull()

/**
 * The step of a start of a node that does not serve its whole RPC: null when it does not answer at all, not even
 * its first RPC.
 */
fun readStartPhase(context: Context, rpc: NodeRpc): StartPhase? {
    val message = runCatching { (rpc.call("bcn_syncing", readTimeoutMs = 5_000) as JSONObject).optString("message") }
        .getOrNull() ?: return null
    return startPhase(message, readTail(nodeLogFile(context)))
}
