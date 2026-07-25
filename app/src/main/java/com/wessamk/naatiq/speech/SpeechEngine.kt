package com.wessamk.naatiq.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.wessamk.naatiq.data.LanguageMode
import com.wessamk.naatiq.data.SettingsRepository
import com.wessamk.naatiq.data.SpeechSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class PlaybackStatus { IDLE, SPEAKING, PAUSED }

/** Inclusive [start], exclusive [end] offsets into the text being read. */
data class Highlight(val start: Int, val end: Int)

data class VoiceOption(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val requiresNetwork: Boolean,
)

data class EngineOption(val packageName: String, val label: String)

/** Reasons the engine needs to tell the user something. */
enum class SpeechProblem { ENGINE_UNAVAILABLE, SPEAK_FAILED }

data class SpeechState(
    val ready: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val segments: List<Segment> = emptyList(),
    val currentIndex: Int = -1,
    val word: Highlight? = null,
    val arabicVoices: List<VoiceOption> = emptyList(),
    val latinVoices: List<VoiceOption> = emptyList(),
    val engines: List<EngineOption> = emptyList(),
    val problem: SpeechProblem? = null,
) {
    val currentSegment: Segment? get() = segments.getOrNull(currentIndex)
    val isActive: Boolean get() = status != PlaybackStatus.IDLE
}

/**
 * Wraps the platform [TextToSpeech] engine.
 *
 * One process-wide instance drives both the UI and the playback notification. Every sentence is
 * queued as its own utterance, which is what makes per-sentence highlighting, sentence skipping
 * and per-language voices possible. The platform has no pause, so pausing stops the engine and
 * remembers the sentence to resume from.
 */
class SpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    @Volatile
    private var settings = SpeechSettings()

    private var tts: TextToSpeech? = null
    private var settingsLoaded = false

    /** Voices of the current engine, by name, so the speaking loop does not query them per sentence. */
    @Volatile
    private var voicesByName: Map<String, Voice> = emptyMap()
    private var focusRequest: AudioFocusRequest? = null

    /** Bumped on every stop, so callbacks from the previous run can be ignored. */
    @Volatile
    private var generation = 0

    /** Set when playback is requested before the engine has finished starting up. */
    private var pendingStartIndex: Int? = null

    init {
        scope.launch {
            settingsRepository.settings.collect { updated ->
                val previous = settings
                settings = updated
                if (!settingsLoaded) {
                    settingsLoaded = true
                    createEngine(updated.enginePackage)
                } else if (updated.enginePackage != previous.enginePackage) {
                    createEngine(updated.enginePackage)
                }
            }
        }
    }

    // ---------------------------------------------------------------- loading

    /** Prepares [source] (or the `from`..`to` slice of it) for reading, without starting. */
    fun load(source: CharSequence, from: Int = 0, to: Int = source.length) {
        val forced = when (settings.languageMode) {
            LanguageMode.AUTO -> null
            LanguageMode.ARABIC -> TextScript.ARABIC
            LanguageMode.ENGLISH -> TextScript.LATIN
        }
        val segments = TextSegmenter.segment(source, from, to, forced)
        generation++
        pendingStartIndex = null
        tts?.stop()
        abandonAudioFocus()
        _state.update {
            it.copy(
                segments = segments,
                currentIndex = -1,
                word = null,
                status = PlaybackStatus.IDLE,
                problem = null,
            )
        }
    }

    // -------------------------------------------------------------- transport

    fun play(startIndex: Int = 0) {
        val segments = _state.value.segments
        if (segments.isEmpty()) return
        speakFrom(startIndex.coerceIn(0, segments.lastIndex))
    }

    fun resume() {
        val index = _state.value.currentIndex
        play(if (index < 0) 0 else index)
    }

    fun pause() {
        if (_state.value.status != PlaybackStatus.SPEAKING) return
        generation++
        pendingStartIndex = null
        tts?.stop()
        abandonAudioFocus()
        _state.update { it.copy(status = PlaybackStatus.PAUSED, word = null) }
    }

    fun togglePlayPause() {
        when (_state.value.status) {
            PlaybackStatus.SPEAKING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.IDLE -> play(0)
        }
    }

    fun stop() {
        generation++
        pendingStartIndex = null
        tts?.stop()
        abandonAudioFocus()
        _state.update { it.copy(status = PlaybackStatus.IDLE, currentIndex = -1, word = null) }
    }

    fun skipToNext() = skipBy(1)

    fun skipToPrevious() = skipBy(-1)

    private fun skipBy(delta: Int) {
        val current = _state.value
        if (current.segments.isEmpty()) return
        val base = if (current.currentIndex < 0) 0 else current.currentIndex
        val target = (base + delta).coerceIn(0, current.segments.lastIndex)
        if (current.status == PlaybackStatus.SPEAKING) {
            speakFrom(target)
        } else {
            _state.update { it.copy(currentIndex = target, word = null) }
        }
    }

    fun seekTo(index: Int) {
        val current = _state.value
        if (index !in current.segments.indices) return
        if (current.status == PlaybackStatus.SPEAKING) {
            speakFrom(index)
        } else {
            _state.update { it.copy(currentIndex = index, word = null) }
        }
    }

    /**
     * Adopts [newSettings] immediately and re-reads the current sentence with them, without
     * waiting for the stored settings to make their way back through the settings flow.
     */
    fun applyAndRestart(newSettings: SpeechSettings) {
        val enginePackageChanged = newSettings.enginePackage != settings.enginePackage
        settings = newSettings
        if (enginePackageChanged) {
            createEngine(newSettings.enginePackage)
        } else {
            restartCurrentSentence()
        }
    }

    /** Re-reads the current sentence so a changed speed, pitch or voice takes effect at once. */
    fun restartCurrentSentence() {
        val current = _state.value
        if (current.status == PlaybackStatus.SPEAKING) {
            speakFrom(current.currentIndex.coerceAtLeast(0))
        }
    }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    fun shutdown() {
        generation++
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
    }

    // ---------------------------------------------------------------- engine

    private fun createEngine(enginePackage: String?) {
        generation++
        tts?.stop()
        tts?.shutdown()
        tts = null
        voicesByName = emptyMap()
        _state.update { it.copy(ready = false, status = PlaybackStatus.IDLE, word = null) }

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                refreshVoices()
                _state.update { it.copy(ready = true, problem = null) }
                pendingStartIndex?.let { index ->
                    pendingStartIndex = null
                    speakFrom(index)
                }
            } else {
                pendingStartIndex = null
                _state.update { it.copy(ready = false, problem = SpeechProblem.ENGINE_UNAVAILABLE) }
            }
        }
        tts = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(context, listener)
        } else {
            TextToSpeech(context, listener, enginePackage)
        }
    }

    private fun refreshVoices() {
        val engine = tts ?: return
        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (_: Exception) {
            // Some engines throw instead of returning an empty set before their data is unpacked.
            emptySet()
        }
        voicesByName = voices.associateBy { it.name }
        val options = voices
            .map {
                VoiceOption(
                    name = it.name,
                    locale = it.locale,
                    quality = it.quality,
                    requiresNetwork = it.isNetworkConnectionRequired,
                )
            }
        val arabic = options.filter { it.locale.language.startsWith("ar") }
        val latin = options.filter { !it.locale.language.startsWith("ar") }
        val installedEngines = try {
            engine.engines.map { EngineOption(it.name, it.label) }
        } catch (_: Exception) {
            emptyList()
        }
        _state.update {
            it.copy(
                arabicVoices = arabic.sortedWith(voiceOrder),
                latinVoices = latin.sortedWith(voiceOrder),
                engines = installedEngines.sortedBy { option -> option.label },
            )
        }
    }

    private val voiceOrder = compareByDescending<VoiceOption> { it.quality }
        .thenBy { it.requiresNetwork }
        .thenBy { it.locale.toString() }
        .thenBy { it.name }

    // -------------------------------------------------------------- speaking

    private fun speakFrom(index: Int) {
        val segments = _state.value.segments
        if (segments.isEmpty()) return
        val start = index.coerceIn(0, segments.lastIndex)
        val engine = tts
        if (engine == null || !_state.value.ready) {
            // Remember the request; onInit will pick it up.
            pendingStartIndex = start
            _state.update { it.copy(currentIndex = start, status = PlaybackStatus.PAUSED) }
            return
        }

        generation++
        val gen = generation
        engine.stop()
        val active = settings
        requestAudioFocus()

        _state.update {
            it.copy(status = PlaybackStatus.SPEAKING, currentIndex = start, word = null, problem = null)
        }

        var queued = false
        for (i in start until segments.size) {
            val segment = segments[i]
            applyVoice(engine, segment.script, active)
            val queueMode = if (i == start) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(segment.text, queueMode, paramsFor(active), utteranceId(gen, i, KIND_SEGMENT))
            if (result == TextToSpeech.SUCCESS) {
                queued = true
            }
            if (active.sentenceGapMs > 0 && i < segments.lastIndex) {
                engine.playSilentUtterance(
                    active.sentenceGapMs.toLong(),
                    TextToSpeech.QUEUE_ADD,
                    utteranceId(gen, i, KIND_SILENCE),
                )
            }
        }
        if (!queued) {
            abandonAudioFocus()
            _state.update {
                it.copy(status = PlaybackStatus.IDLE, word = null, problem = SpeechProblem.SPEAK_FAILED)
            }
        }
    }

    /**
     * Voice, speed and pitch are captured when [TextToSpeech.speak] is called, so setting them
     * per sentence is what lets one text be read by two different voices.
     */
    private fun applyVoice(engine: TextToSpeech, script: TextScript, active: SpeechSettings) {
        engine.setSpeechRate(active.rate)
        engine.setPitch(active.pitch)
        val wanted = when (script) {
            TextScript.ARABIC -> active.arabicVoice
            TextScript.LATIN -> active.englishVoice
        }
        val voice = wanted?.let { voicesByName[it] }
        if (voice != null) {
            engine.voice = voice
            return
        }
        // No explicit choice: fall back to a locale so the engine picks its own default voice.
        val locale = when (script) {
            TextScript.ARABIC -> Locale.forLanguageTag("ar")
            TextScript.LATIN -> Locale.ENGLISH
        }
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        }
    }

    private fun paramsFor(active: SpeechSettings): Bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, active.volume)
    }

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            val id = parseId(utteranceId) ?: return
            if (id.generation != generation || id.kind != KIND_SEGMENT) return
            _state.update { it.copy(status = PlaybackStatus.SPEAKING, currentIndex = id.index, word = null) }
        }

        override fun onDone(utteranceId: String?) {
            val id = parseId(utteranceId) ?: return
            if (id.generation != generation) return
            val segments = _state.value.segments
            if (id.kind == KIND_SEGMENT && id.index >= segments.lastIndex) {
                abandonAudioFocus()
                _state.update {
                    it.copy(status = PlaybackStatus.IDLE, currentIndex = -1, word = null)
                }
            }
        }

        @Deprecated("Kept because the platform declares it abstract; the newer overload is used.")
        override fun onError(utteranceId: String?) {
            reportError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            reportError(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Handled by pause()/stop(); nothing to do here.
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (!settings.highlightWords) return
            val id = parseId(utteranceId) ?: return
            if (id.generation != generation || id.kind != KIND_SEGMENT) return
            val segment = _state.value.segments.getOrNull(id.index) ?: return
            val globalStart = segment.start + start
            val globalEnd = (segment.start + end).coerceAtMost(segment.end)
            if (globalStart < segment.start || globalEnd <= globalStart) return
            _state.update { it.copy(word = Highlight(globalStart, globalEnd)) }
        }
    }

    private fun reportError(utteranceId: String?) {
        val id = parseId(utteranceId)
        if (id != null && id.generation != generation) return
        abandonAudioFocus()
        _state.update {
            it.copy(status = PlaybackStatus.IDLE, word = null, problem = SpeechProblem.SPEAK_FAILED)
        }
    }

    // ------------------------------------------------------------ audiofocus

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) pause()
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ------------------------------------------------------------- utterance ids

    private data class UtteranceId(val generation: Int, val index: Int, val kind: String)

    private fun utteranceId(generation: Int, index: Int, kind: String): String = "$generation|$index|$kind"

    private fun parseId(raw: String?): UtteranceId? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 3) return null
        val generation = parts[0].toIntOrNull() ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        return UtteranceId(generation, index, parts[2])
    }

    private companion object {
        const val KIND_SEGMENT = "s"
        const val KIND_SILENCE = "q"
    }
}
