# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

"Converse" is a single-module (`:app`) Android speech-to-speech translation app:
capture speech → recognize → translate → speak the translation, plus offline
per-language model management. It runs a real, on-device, offline pipeline.

## Architecture

The spine is `pipeline/TranslationPipeline`, which composes three swappable,
interface-defined stages and exposes one `Flow<PipelineState>`:

```
SpeechRecognizer → Translator → SpeechSynthesizer
```

- **`asr/SpeechRecognizer`** — owns audio capture and emits a continuous
  `Flow<RecognitionEvent>` (`Rms` for the VU meter, `Partial`, `Final`).
  - `AndroidSpeechRecognizer` (real): drives `android.speech.SpeechRecognizer`
    (on-device when available). It does **not** self-restart after a final result
    — the pipeline restarts it per turn (see half-duplex below).
  - `MockSpeechRecognizer`: scripted phrases revealed via VAD over an injected
    `audio/AudioCapture` (`AudioRecordCapture` = real 16 kHz PCM mic).
- **`translation/Translator`** — `MLKitTranslator` (real, on-device ML Kit; models
  download per language pair on first use) or `MockTranslator` (canned phrasebook).
- **`tts/SpeechSynthesizer`** — `AndroidTextToSpeech` wraps `TextToSpeech`,
  forcing the Google engine and selecting a **local (`-local`) voice** so it never
  falls back to a failing server voice.

`PipelineState` (`pipeline/PipelineState.kt`) drives the UI: `Listening`,
`Transcribing`, `Translating`, `Turn` (committed to history), `Speaking`,
`Error`, and `SpeechFailed(language, message)` (translation succeeded but no voice
to speak it — the UI offers an "Install voice" button).

`ui/conversation/ConversationViewModel` collects the pipeline into a single
`StateFlow<ConversationUiState>`; `ui/models/` manages offline models via
`model/ModelManager`. `MainActivity` hosts a Navigation-Compose `NavHost`
(conversation ↔ models). DI is Hilt; everything is assembled in `di/AppModule`.

### Two load-bearing design points (don't regress these)

- **Half-duplex turn-taking.** `TranslationPipeline.run` listens for ONE utterance
  (`listenForUtterance` collects until `Final`), which cancels the recognizer flow
  and **releases the mic**, then translates and speaks, then loops. Recording and
  speaking at the same time stalls audio on most devices — keep them mutually
  exclusive.
- **Mock ↔ real is a single switch.** `di/AppModule` selects implementations by
  the `BuildConfig.OFFLINE_DEMO` flag (`buildConfigField` in `app/build.gradle.kts`,
  default `false`). `true` = offline demo mode: `MockSpeechRecognizer` +
  `MockTranslator` (no speech models, no ML Kit downloads, deterministic — good for
  emulators/demos). `false` = real on-device engines. Either way the pipeline,
  viewmodels, and UI are unchanged. The mock recognizer still reads the real
  `AudioCapture` (mic), so the offline path exercises real audio capture.

## Device requirements (for real audio output)

TTS speaks only if the device's TTS engine has the **target language's on-device
voice** installed. Without it the engine redirects to a server voice that fails
(synthesis error -4); the app surfaces this as `SpeechFailed` with an "Install
voice" button (fires `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`). On-device
speech recognition similarly needs the language pack. `RECORD_AUDIO` is requested
at runtime in `ConversationScreen`.

## Commands

Run from the repo root. On Windows use `gradlew.bat`; examples use `./gradlew`.

- Build debug APK: `./gradlew assembleDebug`
- Install on a connected device/emulator: `./gradlew installDebug`
- JVM unit tests (`app/src/test`): `./gradlew testDebugUnitTest`
- Run one test class: `./gradlew test --tests "com.example.aitranslator.TranslationPipelineTest"`
- Run one test method: `./gradlew test --tests "com.example.aitranslator.ModelManagerTest.download transitions through downloading to ready"`
- Instrumented tests (needs a device): `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint` (report under `app/build/reports/lint-results-debug.html`)
- Clean: `./gradlew clean`

No separate formatter is configured; rely on Android Studio / Kotlin defaults.

## Toolchain / versions

- Kotlin `2.0.21`, AGP `8.13.2`, KSP (Hilt uses KSP), JDK 11 (`jvmTarget = 11`)
- `compileSdk`/`targetSdk` 36, `minSdk` 34
- Jetpack Compose only (no XML layouts). Compose versions come from the BOM —
  don't pin individual Compose artifact versions.

## Conventions

- **Dependencies go through the version catalog** at `gradle/libs.versions.toml`:
  add a `[versions]` entry + a `[libraries]`/`[plugins]` alias, then reference it
  as `libs.<alias>` in `app/build.gradle.kts` — don't hardcode coordinates.
- Package root `com.example.aitranslator`; Compose theme in `ui/theme/`
  (wrap screens in `AITranslatorTheme { ... }`).
- Bridging platform callback APIs (TextToSpeech, SpeechRecognizer) into coroutines:
  resume with `resumeWith(result)` (not `resume(getOrThrow())`, which throws on the
  callback thread and leaks the continuation), and bound calls with timeouts so a
  stuck engine can't hang the pipeline — see `AndroidTextToSpeech`.
