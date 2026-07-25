@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.wessamk.naatiq.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.wessamk.naatiq.R
import com.wessamk.naatiq.data.LanguageMode
import com.wessamk.naatiq.data.SpeechSettings
import com.wessamk.naatiq.speech.PlaybackStatus
import com.wessamk.naatiq.speech.SpeechProblem
import com.wessamk.naatiq.speech.SpeechState
import com.wessamk.naatiq.ui.theme.rememberHighlightColors
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_WORD_HIGHLIGHT_CHARS = 20_000

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val context = LocalContext.current
    val speech by viewModel.speech.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val exportState by viewModel.export.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    DisposableEffect(settings.keepScreenOn, speech.status) {
        view.keepScreenOn = settings.keepScreenOn && speech.status == PlaybackStatus.SPEAKING
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(speech.problem) {
        val problem = speech.problem ?: return@LaunchedEffect
        val message = when (problem) {
            SpeechProblem.ENGINE_UNAVAILABLE -> context.getString(R.string.error_engine_init)
            SpeechProblem.SPEAK_FAILED -> context.getString(R.string.error_speak_failed)
        }
        snackbarHostState.showSnackbar(message)
        viewModel.clearProblem()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { viewModel.setEditing(!viewModel.editing) }) {
                        if (viewModel.editing) {
                            Icon(Icons.Filled.Done, stringResource(R.string.cd_done_editing))
                        } else {
                            Icon(Icons.Filled.Edit, stringResource(R.string.cd_edit))
                        }
                    }
                    IconButton(
                        onClick = {
                            val pasted = clipboard.getText()?.text
                            if (pasted.isNullOrBlank()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.error_clipboard_empty))
                                }
                            } else {
                                viewModel.appendText(pasted)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentPaste, stringResource(R.string.cd_paste))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, stringResource(R.string.cd_settings))
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_export)) },
                            leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                            onClick = {
                                showMenu = false
                                viewModel.startExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_clear)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = {
                                showMenu = false
                                viewModel.clearText()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_system_voices)) },
                            onClick = {
                                showMenu = false
                                if (!openSystemVoiceSettings(context)) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.error_no_tts_settings),
                                        )
                                    }
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp),
        ) {
            HintLine(viewModel = viewModel, speech = speech)
            TextArea(viewModel = viewModel, speech = speech, settings = settings, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
            ControlPanel(viewModel = viewModel, speech = speech, settings = settings)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(
            viewModel = viewModel,
            speech = speech,
            settings = settings,
            onDismiss = { showSettings = false },
        )
    }

    ExportDialog(state = exportState, onDismiss = viewModel::dismissExport, onCancel = viewModel::cancelExport)
}

@Composable
private fun HintLine(viewModel: ReaderViewModel, speech: SpeechState) {
    val target = viewModel.playTarget()
    val selectionLength = viewModel.text.selection.length
    val hint = when {
        speech.isActive && speech.segments.isNotEmpty() -> stringResource(
            R.string.progress_sentence,
            (speech.currentIndex + 1).coerceAtLeast(1),
            speech.segments.size,
        )

        target == PlayTarget.EMPTY -> stringResource(R.string.hint_empty)
        target == PlayTarget.SELECTION -> stringResource(R.string.hint_selection, selectionLength)
        target == PlayTarget.FROM_CURSOR -> stringResource(R.string.hint_cursor)
        else -> stringResource(R.string.hint_full)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun TextArea(
    viewModel: ReaderViewModel,
    speech: SpeechState,
    settings: SpeechSettings,
    modifier: Modifier = Modifier,
) {
    val colors = rememberHighlightColors()
    val sentence = speech.currentSegment
    // Highlighting re-lays out the whole field, so word-level marking is dropped for very long
    // texts where that would stutter. Sentence marking stays.
    val word = if (viewModel.text.text.length <= MAX_WORD_HIGHLIGHT_CHARS) speech.word else null
    val transformation = remember(sentence, word, colors) {
        SpokenHighlight(
            sentenceStart = sentence?.start,
            sentenceEnd = sentence?.end,
            wordStart = word?.start,
            wordEnd = word?.end,
            sentenceColor = colors.sentence,
            wordColor = colors.word,
        )
    }
    OutlinedTextField(
        value = viewModel.text,
        onValueChange = viewModel::onTextChanged,
        readOnly = !viewModel.editing,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.hint_empty)) },
        visualTransformation = transformation,
        textStyle = TextStyle(
            fontSize = settings.fontSizeSp.sp,
            lineHeight = (settings.fontSizeSp * 1.6f).sp,
            textDirection = TextDirection.Content,
        ),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun ControlPanel(viewModel: ReaderViewModel, speech: SpeechState, settings: SpeechSettings) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            LanguageChips(settings = settings, onSelect = viewModel::setLanguageMode)
            Spacer(Modifier.height(4.dp))
            LabelledSlider(
                label = stringResource(R.string.speed_label),
                value = settings.rate,
                range = SpeechSettings.RATE_RANGE,
                format = { "%.2f×".format(it) },
                onCommit = viewModel::setRate,
            )
            SentenceScrubber(speech = speech, onSeek = viewModel::seekToSentence)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::skipPrevious, enabled = speech.segments.isNotEmpty()) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.cd_previous))
                }
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(onClick = viewModel::onPlayPause) {
                    if (speech.status == PlaybackStatus.SPEAKING) {
                        Icon(Icons.Filled.Pause, stringResource(R.string.cd_pause), Modifier.size(28.dp))
                    } else {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.cd_play), Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = viewModel::stop, enabled = speech.isActive) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.cd_stop))
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = viewModel::skipNext, enabled = speech.segments.isNotEmpty()) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.cd_next))
                }
            }
        }
    }
}

@Composable
private fun LanguageChips(settings: SpeechSettings, onSelect: (LanguageMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LanguageMode.entries.forEach { mode ->
            val label = when (mode) {
                LanguageMode.AUTO -> stringResource(R.string.language_auto)
                LanguageMode.ARABIC -> stringResource(R.string.language_arabic)
                LanguageMode.ENGLISH -> stringResource(R.string.language_english)
            }
            FilterChip(
                selected = settings.languageMode == mode,
                onClick = { onSelect(mode) },
                label = { Text(label) },
            )
        }
    }
}

/** A slider that only writes the setting once the user lets go. */
@Composable
fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    steps: Int = 0,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft ?: value
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(format(shown), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = shown,
            onValueChange = { draft = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = {
                draft?.let(onCommit)
                draft = null
            },
        )
    }
}

@Composable
private fun SentenceScrubber(speech: SpeechState, onSeek: (Int) -> Unit) {
    if (speech.segments.size < 2) return
    var draft by remember { mutableStateOf<Float?>(null) }
    val current = speech.currentIndex.coerceAtLeast(0).toFloat()
    Slider(
        value = draft ?: current,
        onValueChange = { draft = it },
        valueRange = 0f..(speech.segments.size - 1).toFloat(),
        steps = (speech.segments.size - 2).coerceAtLeast(0),
        onValueChangeFinished = {
            draft?.let { onSeek(it.toInt()) }
            draft = null
        },
    )
}

@Composable
private fun ExportDialog(state: ExportState, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        ExportState.Idle -> Unit

        is ExportState.Running -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.export_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.export_running, state.done, state.total.coerceAtLeast(1)))
                }
            },
            confirmButton = {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.dismiss)) }
            },
        )

        is ExportState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_title)) },
            text = { Text(stringResource(R.string.export_single_voice)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        shareAudio(context, state.file)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.export_share)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            },
        )

        is ExportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_title)) },
            text = {
                Text(
                    if (state.reason == ReaderViewModel.EMPTY_TEXT) {
                        stringResource(R.string.export_empty)
                    } else {
                        stringResource(R.string.export_failed, state.reason)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            },
        )
    }
}

/** Paints the sentence being read, and the word inside it, without changing the text itself. */
private class SpokenHighlight(
    private val sentenceStart: Int?,
    private val sentenceEnd: Int?,
    private val wordStart: Int?,
    private val wordEnd: Int?,
    private val sentenceColor: Color,
    private val wordColor: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (sentenceStart == null && wordStart == null) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text)
        addSpan(builder, text.length, sentenceStart, sentenceEnd, SpanStyle(background = sentenceColor))
        addSpan(
            builder,
            text.length,
            wordStart,
            wordEnd,
            SpanStyle(background = wordColor, fontWeight = FontWeight.Medium),
        )
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun addSpan(
        builder: AnnotatedString.Builder,
        length: Int,
        start: Int?,
        end: Int?,
        style: SpanStyle,
    ) {
        if (start == null || end == null) return
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        if (from == to) return
        builder.addStyle(style, from, to)
    }
}

private fun openSystemVoiceSettings(context: Context): Boolean {
    val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun shareAudio(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share)))
}
