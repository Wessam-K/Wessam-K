package com.wessamk.naatiq.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wessamk.naatiq.ServiceLocator
import com.wessamk.naatiq.data.LanguageMode
import com.wessamk.naatiq.data.SettingsRepository
import com.wessamk.naatiq.data.SpeechSettings
import com.wessamk.naatiq.playback.SpeechService
import com.wessamk.naatiq.speech.AudioExporter
import com.wessamk.naatiq.speech.PlaybackStatus
import com.wessamk.naatiq.speech.SpeechEngine
import com.wessamk.naatiq.speech.TextScript
import com.wessamk.naatiq.speech.TextSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** What the Play button will do next, given the cursor or highlight in the editor. */
enum class PlayTarget { EMPTY, SELECTION, FROM_CURSOR, EVERYTHING }

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val done: Int, val total: Int) : ExportState
    data class Ready(val file: File) : ExportState
    data class Failed(val reason: String) : ExportState
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: SpeechEngine = ServiceLocator.speechEngine
    private val settingsRepository = ServiceLocator.settings
    private val exporter = AudioExporter(application)

    val speech = engine.state

    val settings: StateFlow<SpeechSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SpeechSettings())

    /** The editor content. Kept here so it survives rotation and matches what is being read. */
    var text by mutableStateOf(TextFieldValue(""))
        private set

    var editing by mutableStateOf(false)
        private set

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private var exportJob: Job? = null

    /** True while a whole-text run is playing, when following the reader with the cursor is useful. */
    private var followEnabled = false

    init {
        // Mirror playback into the foreground service so reading survives leaving the app.
        viewModelScope.launch {
            engine.state.map { it.status }.distinctUntilChanged().collect { status ->
                when (status) {
                    PlaybackStatus.SPEAKING -> SpeechService.start(getApplication())
                    PlaybackStatus.IDLE -> SpeechService.stop(getApplication())
                    PlaybackStatus.PAUSED -> Unit // keep the notification so the user can resume
                }
            }
        }
        // Move the cursor to the sentence being read, which also scrolls it into view.
        viewModelScope.launch {
            engine.state.map { it.currentIndex }.distinctUntilChanged().collect { index ->
                if (!followEnabled || editing || index < 0) return@collect
                if (!settings.value.followWhileReading) return@collect
                if (engine.state.value.status != PlaybackStatus.SPEAKING) return@collect
                val segment = engine.state.value.segments.getOrNull(index) ?: return@collect
                if (segment.start <= text.text.length) {
                    text = text.copy(selection = TextRange(segment.start))
                }
            }
        }
    }

    // ------------------------------------------------------------------ text

    fun onTextChanged(value: TextFieldValue) {
        val contentChanged = value.text != text.text
        text = value
        if (contentChanged) engine.stop()
    }

    fun setEditing(value: Boolean) {
        editing = value
    }

    fun clearText() {
        engine.stop()
        text = TextFieldValue("")
        _export.value = ExportState.Idle
    }

    /** Text arriving from the selection menu or a share sheet. */
    fun onIncomingText(incoming: String, autoPlay: Boolean) {
        engine.stop()
        text = TextFieldValue(incoming, selection = TextRange(0))
        editing = false
        if (autoPlay && incoming.isNotBlank()) {
            playFromTarget()
        }
    }

    fun appendText(extra: String) {
        val separator = if (text.text.isBlank() || text.text.endsWith("\n")) "" else "\n\n"
        val combined = text.text + separator + extra
        text = TextFieldValue(combined, selection = TextRange(combined.length))
        engine.stop()
    }

    // -------------------------------------------------------------- transport

    fun playTarget(): PlayTarget = when {
        text.text.isBlank() -> PlayTarget.EMPTY
        !text.selection.collapsed -> PlayTarget.SELECTION
        text.selection.start > 0 -> PlayTarget.FROM_CURSOR
        else -> PlayTarget.EVERYTHING
    }

    fun onPlayPause() {
        when (speech.value.status) {
            PlaybackStatus.SPEAKING -> engine.pause()
            PlaybackStatus.PAUSED -> engine.resume()
            PlaybackStatus.IDLE -> playFromTarget()
        }
    }

    /** Reads the highlight if there is one, otherwise everything from the cursor onwards. */
    private fun playFromTarget() {
        val content = text.text
        if (content.isBlank()) return
        when (playTarget()) {
            PlayTarget.SELECTION -> {
                followEnabled = false
                engine.load(content, text.selection.min, text.selection.max)
                engine.play(0)
            }

            PlayTarget.FROM_CURSOR -> {
                followEnabled = true
                engine.load(content)
                val index = TextSegmenter.indexOfOffset(speech.value.segments, text.selection.start)
                engine.play(index.coerceAtLeast(0))
            }

            else -> {
                followEnabled = true
                engine.load(content)
                engine.play(0)
            }
        }
    }

    fun stop() = engine.stop()

    fun skipNext() = engine.skipToNext()

    fun skipPrevious() = engine.skipToPrevious()

    fun seekToSentence(index: Int) = engine.seekTo(index)

    fun clearProblem() = engine.clearProblem()

    // -------------------------------------------------------------- settings

    fun setLanguageMode(mode: LanguageMode) = update { setLanguageMode(mode) }

    fun setRate(value: Float) = updateAndApply { setRate(value) }

    fun setPitch(value: Float) = updateAndApply { setPitch(value) }

    fun setVolume(value: Float) = updateAndApply { setVolume(value) }

    fun setSentenceGap(ms: Int) = updateAndApply { setSentenceGapMs(ms) }

    fun setVoice(script: TextScript, voiceName: String?) = updateAndApply { setVoice(script, voiceName) }

    fun setEnginePackage(packageName: String?) = update { setEnginePackage(packageName) }

    fun setHighlightWords(value: Boolean) = update { setHighlightWords(value) }

    fun setFollowWhileReading(value: Boolean) = update { setFollowWhileReading(value) }

    fun setKeepScreenOn(value: Boolean) = update { setKeepScreenOn(value) }

    fun setAutoPlayShared(value: Boolean) = update { setAutoPlayShared(value) }

    fun setFontSize(value: Float) = update { setFontSizeSp(value) }

    fun resetSettings() = update { resetToDefaults() }

    private fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.block() }
    }

    /**
     * Writes a voice setting and re-reads the current sentence with it, so a changed speed or
     * voice is heard immediately instead of at the next sentence.
     */
    private fun updateAndApply(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            settingsRepository.block()
            engine.applyAndRestart(settingsRepository.settings.first())
        }
    }

    // ---------------------------------------------------------------- export

    fun startExport() {
        if (exportJob?.isActive == true) return
        val content = text.text
        val from = if (text.selection.collapsed) 0 else text.selection.min
        val to = if (text.selection.collapsed) content.length else text.selection.max
        if (content.isBlank()) {
            _export.value = ExportState.Failed(EMPTY_TEXT)
            return
        }
        _export.value = ExportState.Running(0, 1)
        exportJob = viewModelScope.launch {
            try {
                val file = exporter.export(
                    source = content,
                    from = from,
                    to = to,
                    settings = settings.value,
                    outputDir = File(getApplication<Application>().cacheDir, "exports"),
                    fileName = "naatiq-reading.wav",
                ) { done, total ->
                    _export.value = ExportState.Running(done, total)
                }
                _export.value = ExportState.Ready(file)
            } catch (cancellation: CancellationException) {
                _export.value = ExportState.Idle
                throw cancellation
            } catch (_: IllegalArgumentException) {
                // Nothing speakable in the requested range.
                _export.value = ExportState.Failed(EMPTY_TEXT)
            } catch (error: Exception) {
                _export.value = ExportState.Failed(error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _export.value = ExportState.Idle
    }

    fun dismissExport() {
        _export.value = ExportState.Idle
    }

    companion object {
        /** Marker the screen turns into a localised message. */
        const val EMPTY_TEXT = "empty"
    }
}
