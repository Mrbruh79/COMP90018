package com.example.blap.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.Base64

object VoiceNoteLimits {
    const val MIN_DURATION_MS = 400
    const val MAX_DURATION_MS = 10_000
    const val MAX_ENCODED_LENGTH = 28_000
}

class VoiceRecording(
    val durationMs: Int,
    val audio: ByteArray,
)

interface VoiceNoteService {
    fun start(): Boolean
    fun stop(): VoiceRecording?
    fun cancel()
}

interface VoiceNoteCache {
    fun writeCacheFile(messageId: String, audioBase64: String): File?
}

class VoiceNoteRecorder(private val context: Context) : VoiceNoteService {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        cancel()
        val file = File(context.cacheDir, "voice-record.amr")
        file.delete()
        val mediaRecorder = createRecorder()
        return try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            mediaRecorder.release()
            false
        }
    }

    override fun stop(): VoiceRecording? {
        val duration = (System.currentTimeMillis() - startedAt).toInt()
        val file = outputFile
        releaseRecorder()
        outputFile = null
        if (file == null || !file.exists()) return null
        val bytes = file.readBytes()
        file.delete()
        if (duration < VoiceNoteLimits.MIN_DURATION_MS || bytes.isEmpty()) return null
        return VoiceRecording(duration.coerceAtMost(VoiceNoteLimits.MAX_DURATION_MS), bytes)
    }

    override fun cancel() {
        releaseRecorder()
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseRecorder() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
}

class AndroidVoiceNoteCache(private val context: Context) : VoiceNoteCache {
    override fun writeCacheFile(messageId: String, audioBase64: String): File? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(audioBase64) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val file = File(context.cacheDir, "voice-$messageId.amr")
        file.writeBytes(bytes)
        return file
    }
}

object VoiceNotePlayback {
    fun writeCacheFile(context: Context, messageId: String, audioBase64: String): File? =
        AndroidVoiceNoteCache(context).writeCacheFile(messageId, audioBase64)
}
