package io.github.truongfelix.idenanode

import android.content.Context
import android.net.Uri
import io.github.truongfelix.idenanode.go.mobile.Mobile
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/*
 * Node reports: what the node did when it stopped answering (NodeWatch.kt), or when the user asked for one, in a
 * text file the user can save from Advanced settings and send. A release build is not debuggable, so nothing else
 * reads the node's files, and Android keeps only minutes of its log.
 */

/** How long the service waits for the node's goroutines: a runtime stopped as a whole never writes them. */
private const val GOROUTINES_WAIT_MS = 15_000L

/** The reports kept: the oldest goes. */
private const val KEPT_REPORTS = 5

/** How much of the end of the node's log and of its crash file a report holds. */
private const val REPORT_TAIL_BYTES = 64 * 1024

/** The restarts by the app kept, for [restartAllowed]. */
private const val KEPT_RESTARTS = 20

object NodeReports {
    private fun dir(context: Context): File = File(NodeSettings.nodeDir(context), "reports").also { it.mkdirs() }
    private fun restartsFile(context: Context): File = File(NodeSettings.nodeDir(context), "watchdog_restarts")
    private fun crashFile(context: Context): File = File(NodeSettings.nodeDir(context), "crash.txt")

    /** The reports, the newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("node-report-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }.orEmpty()

    fun latest(context: Context): File? = list(context).firstOrNull()

    /** When the app restarted the node (wall clock, ms), the oldest first. */
    fun restarts(context: Context): List<Long> =
        runCatching { restartsFile(context).readLines().mapNotNull { it.trim().toLongOrNull() } }.getOrDefault(emptyList())

    fun recordRestart(context: Context, at: Long) {
        val lines = (restarts(context) + at).takeLast(KEPT_RESTARTS)
        restartsFile(context).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * The stacks of the node's goroutines (mobile.WriteGoroutines), or null when the node's Go runtime does not write
     * them within [GOROUTINES_WAIT_MS]: then it is stopped as a whole, and the thread stays blocked until the process
     * ends. Only in the node's process: the app's other process runs a Go runtime of its own.
     */
    fun collectGoroutines(context: Context): String? {
        val file = File(dir(context), "goroutines.tmp")
        file.delete()
        var error: String? = null
        val writer = thread(name = "node-goroutines", isDaemon = true) { error = Mobile.writeGoroutines(file.absolutePath) }
        writer.join(GOROUTINES_WAIT_MS)
        if (writer.isAlive) return null
        return try {
            if (error.isNullOrEmpty()) file.readText() else "The goroutines were not written: $error"
        } finally {
            file.delete()
        }
    }

    /** Writes a report on [why], with the node's [goroutines] (null: not written in time); returns its file. */
    fun write(context: Context, why: String, goroutines: String?, now: Long = System.currentTimeMillis()): File {
        val text = reportText(
            madeAt = now,
            app = appVersion(context),
            why = why,
            goroutines = goroutines,
            crash = readTail(crashFile(context), REPORT_TAIL_BYTES)?.takeIf { it.isNotBlank() },
            log = readTail(nodeLogFile(context), REPORT_TAIL_BYTES),
        )
        val file = File(dir(context), reportName(now))
        file.writeText(text)
        list(context).drop(KEPT_REPORTS).forEach { it.delete() }
        return file
    }

    /** Copies [report] to [uri], a file the user chose. */
    fun save(context: Context, report: File, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri) ?: error("the file cannot be written")
        out.use { report.inputStream().use { input -> input.copyTo(it) } }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}

/** "node-report-2026-10-16-114410.txt", in UTC: the names sort by time. */
fun reportName(at: Long): String =
    "node-report-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Instant.ofEpochMilli(at).atZone(ZoneId.of("UTC"))) + ".txt"

/** A report's text: what it is about, then the goroutines, the Go crash output and the end of the node's log. */
fun reportText(madeAt: Long, app: String, why: String, goroutines: String?, crash: String?, log: String?): String = buildString {
    appendLine("Idena node report")
    appendLine("Made: ${Instant.ofEpochMilli(madeAt)}")
    appendLine("App: $app")
    appendLine("Why: $why")
    appendLine()
    appendLine("== Goroutines")
    appendLine(goroutines ?: "Not written within ${GOROUTINES_WAIT_MS / 1000} s: the node's Go runtime was stopped as a whole.")
    crash?.let {
        appendLine()
        appendLine("== Go crash output (crash.txt, its end)")
        appendLine(it)
    }
    appendLine()
    appendLine("== The node's log (output.log, its end; times in UTC)")
    appendLine(log ?: "Not read.")
}
