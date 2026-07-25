# Naatiq · ناطق — highlight text, hear it read

An Android app that reads Arabic and English text aloud, with the sentence and the word being
spoken highlighted as it goes. Highlight a part of the text and only that part is read.

> The repository root README is the GitHub profile page and was left untouched — this file is the
> app's documentation.

## What it does

**Two ways in**

- Open the app, paste or type text, highlight a passage, press Play.
- Highlight text in *any* app (browser, chat, PDF reader), tap **Read aloud** in the selection
  menu, and it starts reading straight away. Sharing text to the app works the same way.

**Reading**

- Highlight a passage → Play reads exactly that. No highlight → Play reads from the cursor
  onwards. Cursor at the start → the whole text.
- The sentence being read is tinted; the word being spoken is tinted more strongly
  (`onRangeStart` from the speech engine, so it tracks the real audio, not a timer).
- Skip forward and back by sentence, or drag the sentence scrubber to jump anywhere.
- Reading continues when you leave the app, with play / pause / skip / stop in a notification.
- Playback ducks music instead of talking over it, and pauses if something else takes over audio.

**Arabic and English together**

- Language is detected per run of text, not per document: `هذا تطبيق Android جديد` is read with
  the Arabic voice, then the English voice for `Android`, then the Arabic voice again.
- Detection covers the Arabic, Arabic Supplement, Extended-A and both Presentation Forms blocks,
  so presentation-form text and Arabic-Indic digits are recognised too.
- Auto detection can be overridden with the **Auto / العربية / English** chips.
- Sentence splitting understands Arabic punctuation (`؟ ؛ ۔`), keeps `3.5` and `example.com`
  in one piece, and does not pause after short abbreviations like `Dr.` or `قال:`.
- The interface itself is fully translated into Arabic and lays out right-to-left.

**Controls the user asked for**

| Setting | Range |
| --- | --- |
| Speed | 0.25× – 3.00× |
| Voice style | any installed voice per language, listed with locale, quality and whether it needs internet |
| Speech engine | any installed engine (Google, Samsung, …) when more than one is present |
| Pitch | 0.5 – 2.0 |
| Volume | 10 % – 100 % |
| Pause between sentences | 0 – 1500 ms |
| Text size | 14 – 30 sp |
| Word-level highlighting, scroll-along, keep screen on, auto-play shared text | on / off |

Speed, pitch, volume and voice changes are applied to the sentence being read, not only to the
next one. Everything is stored with DataStore and survives restarts.

**Save as audio** — renders the text (or just the highlight) to a WAV file and hands it to the
share sheet. A single voice is used for the whole file, since utterances from different voices can
come back at different sample rates.

## Requirements

- Android 8.0 (API 26) or newer — `onRangeStart` word tracking needs API 26.
- A text-to-speech engine with the voices you want installed. For Arabic, Google
  Text-to-Speech's Arabic voice usually has to be downloaded once; the overflow menu has a
  shortcut to the system voice settings.

## Building

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # to a connected device
./gradlew test               # unit tests for the text segmenter
```

Requires JDK 17+ and the Android SDK (compileSdk 35). Gradle 8.9 and AGP 8.7.3 are pinned by the
wrapper and version catalog.

## How it is put together

```
app/src/main/java/com/wessamk/naatiq/
├── NaatiqApp.kt                 Application + ServiceLocator (one engine per process)
├── data/
│   ├── SpeechSettings.kt        every user-tunable value, with its allowed range
│   └── SettingsRepository.kt    DataStore persistence
├── speech/
│   ├── TextSegmenter.kt         sentence + script-run splitting, offsets into the source text
│   ├── SpeechEngine.kt          TextToSpeech wrapper: queueing, pause/resume, highlighting, focus
│   └── AudioExporter.kt         synthesizeToFile per sentence, WAV concatenation
├── playback/SpeechService.kt    foreground service and playback notification
└── ui/
    ├── MainActivity.kt          PROCESS_TEXT / SEND intent handling
    ├── ReaderViewModel.kt       editor state, play targets, export
    ├── ReaderScreen.kt          editor, highlight painting, transport
    ├── SettingsDialog.kt        voices, engine, sliders, switches
    └── theme/Theme.kt           Material 3 theme, highlight colours
```

Design notes worth knowing before changing things:

- **Every sentence is its own utterance.** That is what makes per-sentence skipping, per-language
  voices and reliable progress reporting possible. Voice, rate and pitch are read by the platform
  when `speak()` is called, so they can differ between queued sentences.
- **The platform has no pause.** Pausing stops the engine and remembers the sentence index;
  resuming re-queues from there. A `generation` counter is bumped on every stop so callbacks from
  an abandoned run are ignored.
- **Highlighting does not touch the text.** A `VisualTransformation` paints backgrounds over the
  editor content with an identity offset mapping, so the caret, selection and editing stay
  correct. Word painting is skipped past 20 000 characters, where re-laying out the field on every
  word would stutter.
- **Segment offsets are absolute.** They point into the full editor text even when only a
  selection is being read, so highlighting works for a selection without a second coordinate
  system.

## Tests

`app/src/test/java/.../TextSegmenterTest.kt` covers the segmenter: English and Arabic sentence
splitting, mixed-script runs, decimals and domains, abbreviations, slices, long-sentence chunking,
offset round-tripping and offset lookup. Run with `./gradlew test`.
