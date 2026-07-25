package com.wessamk.naatiq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wessamk.naatiq.speech.TextScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "naatiq_settings")

/** Reads and writes [SpeechSettings]. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<SpeechSettings> = store.data
        .catch { cause ->
            // A corrupt or unreadable file should not stop the app from reading text aloud.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }

    suspend fun setEnginePackage(value: String?) = write { prefs ->
        if (value == null) prefs.remove(Keys.ENGINE) else prefs[Keys.ENGINE] = value
    }

    suspend fun setVoice(script: TextScript, voiceName: String?) = write { prefs ->
        val key = when (script) {
            TextScript.ARABIC -> Keys.ARABIC_VOICE
            TextScript.LATIN -> Keys.ENGLISH_VOICE
        }
        if (voiceName == null) prefs.remove(key) else prefs[key] = voiceName
    }

    suspend fun setLanguageMode(mode: LanguageMode) = write { it[Keys.LANGUAGE_MODE] = mode.name }

    suspend fun setRate(value: Float) = write { it[Keys.RATE] = value.coerceIn(SpeechSettings.RATE_RANGE) }

    suspend fun setPitch(value: Float) = write { it[Keys.PITCH] = value.coerceIn(SpeechSettings.PITCH_RANGE) }

    suspend fun setVolume(value: Float) = write { it[Keys.VOLUME] = value.coerceIn(SpeechSettings.VOLUME_RANGE) }

    suspend fun setSentenceGapMs(value: Int) = write { it[Keys.GAP] = value.coerceIn(0, 1500) }

    suspend fun setHighlightWords(value: Boolean) = write { it[Keys.HIGHLIGHT_WORDS] = value }

    suspend fun setFollowWhileReading(value: Boolean) = write { it[Keys.FOLLOW] = value }

    suspend fun setKeepScreenOn(value: Boolean) = write { it[Keys.KEEP_SCREEN_ON] = value }

    suspend fun setAutoPlayShared(value: Boolean) = write { it[Keys.AUTO_PLAY] = value }

    suspend fun setFontSizeSp(value: Float) = write {
        it[Keys.FONT_SIZE] = value.coerceIn(SpeechSettings.FONT_SIZE_RANGE)
    }

    suspend fun resetToDefaults() = write { it.clear() }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun Preferences.toSettings(): SpeechSettings {
        val defaults = SpeechSettings()
        return SpeechSettings(
            enginePackage = this[Keys.ENGINE],
            arabicVoice = this[Keys.ARABIC_VOICE],
            englishVoice = this[Keys.ENGLISH_VOICE],
            languageMode = this[Keys.LANGUAGE_MODE]?.let { name ->
                LanguageMode.entries.firstOrNull { it.name == name }
            } ?: defaults.languageMode,
            rate = this[Keys.RATE] ?: defaults.rate,
            pitch = this[Keys.PITCH] ?: defaults.pitch,
            volume = this[Keys.VOLUME] ?: defaults.volume,
            sentenceGapMs = this[Keys.GAP] ?: defaults.sentenceGapMs,
            highlightWords = this[Keys.HIGHLIGHT_WORDS] ?: defaults.highlightWords,
            followWhileReading = this[Keys.FOLLOW] ?: defaults.followWhileReading,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            autoPlayShared = this[Keys.AUTO_PLAY] ?: defaults.autoPlayShared,
            fontSizeSp = this[Keys.FONT_SIZE] ?: defaults.fontSizeSp,
        )
    }

    private object Keys {
        val ENGINE = stringPreferencesKey("engine_package")
        val ARABIC_VOICE = stringPreferencesKey("arabic_voice")
        val ENGLISH_VOICE = stringPreferencesKey("english_voice")
        val LANGUAGE_MODE = stringPreferencesKey("language_mode")
        val RATE = floatPreferencesKey("rate")
        val PITCH = floatPreferencesKey("pitch")
        val VOLUME = floatPreferencesKey("volume")
        val GAP = intPreferencesKey("sentence_gap_ms")
        val HIGHLIGHT_WORDS = booleanPreferencesKey("highlight_words")
        val FOLLOW = booleanPreferencesKey("follow_while_reading")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_PLAY = booleanPreferencesKey("auto_play_shared")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
    }
}
