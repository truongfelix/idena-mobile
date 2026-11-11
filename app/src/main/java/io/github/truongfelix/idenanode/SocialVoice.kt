package io.github.truongfelix.idenanode

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/*
 * A voice note recorded in the post editor (B-223): Opus in Ogg from the microphone, mono at 24 kbit/s (clear speech,
 * about 3 KB a second), stopped by Android before the file passes what a post can store, and sent as a sound
 * ("audio/ogg") as it is.
 */

/** The voice note's file size limit: under one IPFS file ([MAX_IPFS_FILE]), with room for the file's last page. */
internal const val MAX_VOICE_BYTES = MAX_IPFS_FILE - 20_000

/** The longest voice note: 5 minutes, about 900 KB at the bit rate. */
internal const val MAX_VOICE_MS = 5 * 60 * 1000

private const val VOICE_BIT_RATE = 24_000

/** The limit a recording stopped at: the size a post can store, or its longest time. */
enum class VoiceLimit { SIZE, TIME }

/** The type of a voice note in its post. */
const val VOICE_TYPE = "audio/ogg"

/**
 * One voice note at a time, in the app's cache. [recording] while the microphone records, since [startedAt]
 * (SystemClock.elapsedRealtime). [onDone] gets the file's bytes when it stops (by [stop], or at its limit with
 * [VoiceLimit]), or the reason it failed.
 */
class VoiceRecorder(private val context: Context, private val onDone: (Result<ByteArray>, limit: VoiceLimit?) -> Unit) {
    var recording by mutableStateOf(false)
        private set
    var startedAt by mutableStateOf(0L)
        private set
    private var recorder: MediaRecorder? = null
    private val file: File
        get() = File(context.cacheDir, "voice-note.ogg")

    /** Starts recording; the microphone permission is asked first by the screen. */
    fun start() {
        if (recording) return
        file.delete()
        @Suppress("DEPRECATION")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        val made = runCatching {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioChannels(1)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(VOICE_BIT_RATE)
                setMaxFileSize(MAX_VOICE_BYTES.toLong())
                setMaxDuration(MAX_VOICE_MS)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> finish(VoiceLimit.SIZE)
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> finish(VoiceLimit.TIME)
                    }
                }
                prepare()
                start()
            }
        }
        made.onSuccess {
            recorder = it
            startedAt = SystemClock.elapsedRealtime()
            recording = true
        }.onFailure {
            created.release()
            onDone(Result.failure(it), null)
        }
    }

    /** Stops recording and hands the note over. */
    fun stop() = finish(null)

    /** Stops recording without handing anything over (the editor closed). */
    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        recording = false
        file.delete()
    }

    private fun finish(limit: VoiceLimit?) {
        val current = recorder ?: return
        recorder = null
        recording = false
        // stop() throws when nothing was recorded yet (a tap right after start); at a limit Android may have stopped
        // already, and the file holds the whole note.
        val stopped = runCatching { current.stop() }
        current.release()
        val result = if (stopped.isSuccess || (limit != null && file.length() > 0)) runCatching { file.readBytes() } else Result.failure(stopped.exceptionOrNull()!!)
        file.delete()
        onDone(result, limit)
    }
}
