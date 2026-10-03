package io.github.truongfelix.idenanode

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/**
 * The sizes of the chain database's write buffer the app offers. The database writes the buffer to disk
 * when it is full, and each of these writes makes it rewrite older data: with idena-go's 4 MiB a node
 * at the top of the chain writes about 16 times the new data (3 MB a block, 13 GB a day on a phone).
 * A bigger buffer cuts these writes (flash wear, battery) for more memory. The figures are from mainnet
 * blocks replayed at the chain's pace on a PC (2026-10-01).
 */
enum class WriteBuffer(val mib: Int, val label: String, val detail: String) {
    MiB4(4, "Idena default", "Most disk writes, least memory"),
    MiB16(16, "Optimal", "About 65% fewer disk writes, 15-30 MB more memory"),
    MiB32(32, "Phone with 4 GB+ RAM", "About 75% fewer disk writes, 60-90 MB more memory"),
    MiB64(64, "Computer", "About 85% fewer disk writes, 200-270 MB more memory"),
    ;

    companion object {
        val DEFAULT = MiB16

        /** The size a settings file holds; the default when it is missing or holds no offered size. */
        fun parse(text: String?): WriteBuffer = entries.firstOrNull { it.mib.toString() == text?.trim() } ?: DEFAULT
    }
}

/**
 * Whether the node runs with another write buffer than [chosen]: the database takes its size when it
 * opens, so the choice applies from the node's next start.
 */
fun writeBufferPending(syncEnabled: Boolean, running: WriteBuffer?, chosen: WriteBuffer): Boolean =
    syncEnabled && running != null && running != chosen

/**
 * Why a node restart now is a risk, or null: after a restart the node looks for peers again, which can
 * take minutes. During the validation [canRestart] is false.
 */
data class RestartRisk(val message: String, val canRestart: Boolean)

/** The [RestartRisk] of a restart at [now], for the validation at [start] and the ceremony [period]. */
fun restartRisk(now: Instant, start: Instant?, period: String): RestartRisk? = when {
    period.isNotEmpty() && period != "None" -> RestartRisk("The validation is running: restart the node after it.", canRestart = false)
    start != null && now.isBefore(start) && Duration.between(now, start) < RESTART_MARGIN ->
        RestartRisk(
            "The validation starts ${countdown(now, start)}: the node may still be looking for peers then.",
            canRestart = true,
        )
    else -> null
}

/** How long before a validation a restart gets a warning. */
private val RESTART_MARGIN: Duration = Duration.ofHours(3)

/** The phone's RAM as sold (3, 4, 6 GB...) from the memory Android reports, which is a bit less. */
fun ramGb(totalBytes: Long): Long = (totalBytes / 1e9).roundToLong()
