package com.wessamk.naatiq.data

/** Which language the reader should assume for the text. */
enum class LanguageMode { AUTO, ARABIC, ENGLISH }

/**
 * Everything the user can tune about the reading voice. Persisted by [SettingsRepository].
 */
data class SpeechSettings(
    /** Package name of the text-to-speech engine, or `null` for the system default. */
    val enginePackage: String? = null,
    /** Voice name for Arabic text, or `null` for the engine default. */
    val arabicVoice: String? = null,
    /** Voice name for Latin-script text, or `null` for the engine default. */
    val englishVoice: String? = null,
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    /** Silence inserted after every sentence, in milliseconds. */
    val sentenceGapMs: Int = 0,
    val highlightWords: Boolean = true,
    val followWhileReading: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoPlayShared: Boolean = true,
    /** Editor font size in scaled pixels. */
    val fontSizeSp: Float = 18f,
) {
    companion object {
        val RATE_RANGE = 0.25f..3.0f
        val PITCH_RANGE = 0.5f..2.0f
        val VOLUME_RANGE = 0.1f..1.0f
        val GAP_RANGE = 0f..1500f
        val FONT_SIZE_RANGE = 14f..30f
    }
}
