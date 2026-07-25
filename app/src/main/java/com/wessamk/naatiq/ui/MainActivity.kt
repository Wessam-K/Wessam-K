package com.wessamk.naatiq.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.wessamk.naatiq.ServiceLocator
import com.wessamk.naatiq.ui.theme.NaatiqTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* reading works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.install(this)
        enableEdgeToEdge()
        setContent {
            NaatiqTheme {
                ReaderScreen(viewModel = viewModel)
            }
        }
        askForNotificationPermission()
        handleIntent(intent, isColdStart = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isColdStart = true)
    }

    /** Accepts text from the "Read aloud" selection menu entry and from share sheets. */
    private fun handleIntent(intent: Intent?, isColdStart: Boolean) {
        if (intent == null || !isColdStart) return
        val incoming = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (incoming.isNullOrBlank()) return
        viewModel.onIncomingText(incoming, autoPlay = viewModel.settings.value.autoPlayShared)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
