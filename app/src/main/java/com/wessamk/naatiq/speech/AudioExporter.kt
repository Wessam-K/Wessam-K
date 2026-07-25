package com.wessamk.naatiq.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.wessamk.naatiq.data.LanguageMode
import com.wessamk.naatiq.data.SpeechSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders text to a WAV file using the platform speech engine.
 *
 * The export uses a single voice — the one matching the main language of the text — because
 * utterances from different voices can come back at different sample rates, which cannot be
 * concatenated without resampling.
 */
class AudioExporter(private val context: Context) {

    /**
     * @param onProgress called with (rendered sentences, total sentences) on a background thread.
     * @return the finished WAV file.
     */
    suspend fun export(
        source: CharSequence,
        from: Int,
        to: Int,
        settings: SpeechSettings,
        outputDir: File,
        fileName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val script = when (settings.languageMode) {
            LanguageMode.ARABIC -> TextScript.ARABIC
            LanguageMode.ENGLISH -> TextScript.LATIN
            LanguageMode.AUTO -> TextSegmenter.dominantScript(source, from, to)
        }
        val segments = TextSegmenter.segment(source, from, to, script)
        require(segments.isNotEmpty()) { "no speakable text" }

        val engine = awaitEngine(settings.enginePackage)
        val parts = ArrayList<File>(segments.size)
        try {
            engine.setSpeechRate(settings.rate)
            engine.setPitch(settings.pitch)
            applyVoice(engine, script, settings)

            val partsDir = File(outputDir, "parts").apply { mkdirs() }
            segments.forEachIndexed { index, segment ->
                val part = File(partsDir, "part_%04d.wav".format(index))
                synthesize(engine, segment.text, part, "export|$index", settings)
                parts.add(part)
                onProgress(index + 1, segments.size)
            }

            outputDir.mkdirs()
            val output = File(outputDir, fileName)
            mergeWavFiles(parts, output)
            output
        } finally {
            engine.shutdown()
            parts.forEach { it.delete() }
            File(outputDir, "parts").delete()
        }
    }

    /**
     * Created on the main thread so the constructor always returns before the platform delivers
     * `onInit` on that same thread.
     */
    private suspend fun awaitEngine(enginePackage: String?): TextToSpeech =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var engine: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { status ->
                    val instance = engine
                    if (!continuation.isActive) {
                        instance?.shutdown()
                    } else if (status == TextToSpeech.SUCCESS && instance != null) {
                        continuation.resume(instance)
                    } else {
                        instance?.shutdown()
                        continuation.resumeWithException(IOException("speech engine unavailable"))
                    }
                }
                engine = if (enginePackage.isNullOrBlank()) {
                    TextToSpeech(context, listener)
                } else {
                    TextToSpeech(context, listener, enginePackage)
                }
                continuation.invokeOnCancellation { engine?.shutdown() }
            }
        }

    private fun applyVoice(engine: TextToSpeech, script: TextScript, settings: SpeechSettings) {
        val wanted = when (script) {
            TextScript.ARABIC -> settings.arabicVoice
            TextScript.LATIN -> settings.englishVoice
        }
        val voice = wanted?.let { name ->
            try {
                engine.voices?.firstOrNull { it.name == name }
            } catch (_: Exception) {
                null
            }
        }
        if (voice != null) {
            engine.voice = voice
            return
        }
        val locale = if (script == TextScript.ARABIC) Locale.forLanguageTag("ar") else Locale.ENGLISH
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        }
    }

    private suspend fun synthesize(
        engine: TextToSpeech,
        text: String,
        target: File,
        utteranceId: String,
        settings: SpeechSettings,
    ) = suspendCancellableCoroutine { continuation ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
            }

            @Deprecated("Kept because the platform declares it abstract.")
            override fun onError(id: String?) {
                if (id == utteranceId && continuation.isActive) {
                    continuation.resumeWithException(IOException("could not render \"${text.take(30)}…\""))
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && continuation.isActive) {
                    continuation.resumeWithException(IOException("render failed with code $errorCode"))
                }
            }
        })
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume)
        }
        val result = engine.synthesizeToFile(text, params, target, utteranceId)
        if (result != TextToSpeech.SUCCESS && continuation.isActive) {
            continuation.resumeWithException(IOException("the engine refused to render this text"))
        }
    }

    // ------------------------------------------------------------------ WAV

    private data class WavPart(val file: File, val format: WavFormat, val dataOffset: Int, val dataSize: Int)

    private data class WavFormat(val channels: Int, val sampleRate: Int, val bitsPerSample: Int)

    private fun mergeWavFiles(parts: List<File>, output: File) {
        val parsed = parts.mapNotNull { parseWav(it) }
        require(parsed.isNotEmpty()) { "the engine produced no audio" }
        val format = parsed.first().format
        val usable = parsed.filter { it.format == format }
        val totalData = usable.sumOf { it.dataSize }

        output.outputStream().buffered().use { out ->
            out.write(wavHeader(format, totalData))
            val buffer = ByteArray(64 * 1024)
            for (part in usable) {
                RandomAccessFile(part.file, "r").use { input ->
                    input.seek(part.dataOffset.toLong())
                    var remaining = part.dataSize
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }
    }

    /** Walks the RIFF chunks to find the format and the start of the samples. */
    private fun parseWav(file: File): WavPart? {
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        var offset = 12
        var format: WavFormat? = null
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readIntLe(bytes, offset + 4)
            val body = offset + 8
            if (chunkSize < 0) return null
            when (chunkId) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    format = WavFormat(
                        channels = readShortLe(bytes, body + 2),
                        sampleRate = readIntLe(bytes, body + 4),
                        bitsPerSample = readShortLe(bytes, body + 14),
                    )
                }

                "data" -> {
                    val available = (bytes.size - body).coerceAtLeast(0)
                    // Some engines leave the declared size at 0 or -1 until the file is closed.
                    val size = if (chunkSize in 1..available) chunkSize else available
                    val known = format ?: return null
                    return WavPart(file, known, body, size)
                }
            }
            offset = body + chunkSize + (chunkSize % 2)
        }
        return null
    }

    private fun wavHeader(format: WavFormat, dataSize: Int): ByteArray {
        val byteRate = format.sampleRate * format.channels * format.bitsPerSample / 8
        val blockAlign = format.channels * format.bitsPerSample / 8
        val header = ByteArray(44)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        writeIntLe(header, 4, 36 + dataSize)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        writeIntLe(header, 16, 16)
        writeShortLe(header, 20, 1) // PCM
        writeShortLe(header, 22, format.channels)
        writeIntLe(header, 24, format.sampleRate)
        writeIntLe(header, 28, byteRate)
        writeShortLe(header, 32, blockAlign)
        writeShortLe(header, 34, format.bitsPerSample)
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        writeIntLe(header, 40, dataSize)
        return header
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
