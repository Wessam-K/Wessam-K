@file:OptIn(ExperimentalMaterial3Api::class)

package com.wessamk.naatiq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wessamk.naatiq.R
import com.wessamk.naatiq.data.SpeechSettings
import com.wessamk.naatiq.speech.SpeechState
import com.wessamk.naatiq.speech.TextScript
import com.wessamk.naatiq.speech.VoiceOption
import java.util.Locale

@Composable
fun SettingsDialog(
    viewModel: ReaderViewModel,
    speech: SpeechState,
    settings: SpeechSettings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.cd_close))
                    }
                }

                SectionTitle(stringResource(R.string.settings_voices))

                VoicePicker(
                    label = stringResource(R.string.settings_arabic_voice),
                    voices = speech.arabicVoices,
                    selected = settings.arabicVoice,
                    onSelect = { viewModel.setVoice(TextScript.ARABIC, it) },
                )
                VoicePicker(
                    label = stringResource(R.string.settings_english_voice),
                    voices = speech.latinVoices,
                    selected = settings.englishVoice,
                    onSelect = { viewModel.setVoice(TextScript.LATIN, it) },
                )
                EnginePicker(viewModel = viewModel, speech = speech, settings = settings)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionTitle(stringResource(R.string.settings_reading))

                LabelledSlider(
                    label = stringResource(R.string.speed_label),
                    value = settings.rate,
                    range = SpeechSettings.RATE_RANGE,
                    format = { "%.2f×".format(it) },
                    onCommit = viewModel::setRate,
                )
                LabelledSlider(
                    label = stringResource(R.string.pitch_label),
                    value = settings.pitch,
                    range = SpeechSettings.PITCH_RANGE,
                    format = { "%.2f".format(it) },
                    onCommit = viewModel::setPitch,
                )
                LabelledSlider(
                    label = stringResource(R.string.volume_label),
                    value = settings.volume,
                    range = SpeechSettings.VOLUME_RANGE,
                    format = { "${(it * 100).toInt()}%" },
                    onCommit = viewModel::setVolume,
                )
                LabelledSlider(
                    label = stringResource(R.string.gap_label),
                    value = settings.sentenceGapMs.toFloat(),
                    range = SpeechSettings.GAP_RANGE,
                    steps = 5,
                    format = { context.getString(R.string.gap_value, it.toInt()) },
                    onCommit = { viewModel.setSentenceGap(it.toInt()) },
                )
                LabelledSlider(
                    label = stringResource(R.string.font_size_label),
                    value = settings.fontSizeSp,
                    range = SpeechSettings.FONT_SIZE_RANGE,
                    format = { "${it.toInt()} sp" },
                    onCommit = viewModel::setFontSize,
                )

                SwitchRow(
                    label = stringResource(R.string.settings_highlight_words),
                    checked = settings.highlightWords,
                    onChange = viewModel::setHighlightWords,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_follow),
                    checked = settings.followWhileReading,
                    onChange = viewModel::setFollowWhileReading,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_keep_screen_on),
                    checked = settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_autoplay),
                    checked = settings.autoPlayShared,
                    onChange = viewModel::setAutoPlayShared,
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.resetSettings() }) {
                        Text(stringResource(R.string.settings_reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VoicePicker(
    label: String,
    voices: List<VoiceOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedVoice = voices.firstOrNull { it.name == selected }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when {
                        voices.isEmpty() -> stringResource(R.string.voice_none)
                        selectedVoice == null -> stringResource(R.string.voice_default)
                        else -> voiceLabel(selectedVoice)
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.voice_default)) },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voiceLabel(voice)) },
                        onClick = {
                            expanded = false
                            onSelect(voice.name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnginePicker(viewModel: ReaderViewModel, speech: SpeechState, settings: SpeechSettings) {
    if (speech.engines.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val current = speech.engines.firstOrNull { it.packageName == settings.enginePackage }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = current?.label ?: stringResource(R.string.voice_default),
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.voice_default)) },
                    onClick = {
                        expanded = false
                        viewModel.setEnginePackage(null)
                    },
                )
                speech.engines.forEach { engine ->
                    DropdownMenuItem(
                        text = { Text(engine.label) },
                        onClick = {
                            expanded = false
                            viewModel.setEnginePackage(engine.packageName)
                        },
                    )
                }
            }
        }
    }
}

/** "Arabic (Egypt) · high quality · needs internet" */
@Composable
private fun voiceLabel(voice: VoiceOption): String {
    val quality = when {
        voice.quality >= 400 -> stringResource(R.string.quality_high)
        voice.quality >= 300 -> stringResource(R.string.quality_normal)
        else -> stringResource(R.string.quality_low)
    }
    val parts = buildList {
        add(voice.locale.getDisplayName(Locale.getDefault()).ifBlank { voice.locale.toString() })
        add(quality)
        add(voice.name.substringAfterLast('#').takeLast(24))
        if (voice.requiresNetwork) add(stringResource(R.string.voice_network))
    }
    return parts.joinToString(" · ")
}
