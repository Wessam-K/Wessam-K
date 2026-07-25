package com.wessamk.naatiq

import android.app.Application
import android.content.Context
import com.wessamk.naatiq.data.SettingsRepository
import com.wessamk.naatiq.speech.SpeechEngine

class NaatiqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.install(this)
    }
}

/**
 * Holds the single speech engine and settings store. The engine has to be process-wide: the
 * screen and the playback notification drive the same playback.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val speechEngine: SpeechEngine by lazy { SpeechEngine(appContext, settings) }

    fun install(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }
}
