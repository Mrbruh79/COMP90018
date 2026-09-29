package com.example.blap.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.Base64

class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
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

    fun stop(): Pair<Int, ByteArray>? {
        val duration = (System.currentTimeMillis() - startedAt).toInt()
        val file = outputFile
        releaseRecorder()
        outputFile = null
        if (file == null || !file.exists()) return null
        val bytes = file.readBytes()
        file.delete()
        if (duration < ChatViewModel.MIN_VOICE_DURATION_MS || bytes.isEmpty()) return null
        return duration.coerceAtMost(ChatViewModel.MAX_VOICE_DURATION_MS) to bytes
    }

    fun cancel() {
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

object VoiceNotePlayback {
    fun writeCacheFile(context: Context, messageId: String, audioBase64: String): File? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(audioBase64) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val file = File(context.cacheDir, "voice-$messageId.amr")
        file.writeBytes(bytes)
        return file
    }
}
