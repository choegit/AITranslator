# Converse

An Android **speech-to-speech translation** app. Speak in one language; Converse
recognizes your speech, translates it, and speaks the translation aloud — running
a **real, on-device, offline pipeline**.

It uses Google's on-device speech recognition (SODA), ML Kit on-device
translation, and the platform text-to-speech engine. Every stage sits behind an
interface, so a mock implementation can be swapped in for development, testing,
or fully offline demos.

> Built as a focused study in clean Android architecture for a real-time ML
> pipeline. See [`DESIGN.md`](DESIGN.md) for the full design rationale.

## Features

- 🎙️ **Streaming recognition** — live audio level (VU meter) and partial transcripts
- 🌐 **On-device translation** — ML Kit; per-language-pair models download on demand
- 🔊 **Spoken output** — platform TTS with automatic local-voice selection
- 🔁 **Turn-based conversation** — half-duplex so the mic and speaker don't fight
- 📦 **Offline model management** — download/delete language models in-app
- 🧩 **Swappable stages** — real ↔ mock implementations via a one-line DI change
- 🛟 **Graceful degradation** — if a voice isn't installed, offer to install it

## Architecture

The spine is `TranslationPipeline`, composing three interface-defined, swappable
stages into a single `Flow<PipelineState>`:

```
SpeechRecognizer  ──►  Translator  ──►  SpeechSynthesizer
   (ASR, owns mic)      (text→text)        (TTS playback)
```

| Stage | Real implementation | Mock implementation |
|-------|---------------------|---------------------|
| Recognition | `AndroidSpeechRecognizer` (on-device SODA) | `MockSpeechRecognizer` (scripted, VAD over real mic) |
| Translation | `MLKitTranslator` (ML Kit on-device) | `MockTranslator` (canned phrasebook) |
| Speech output | `AndroidTextToSpeech` (platform TTS) | — |

A `ConversationViewModel` collects the pipeline into one
`StateFlow<ConversationUiState>` that Compose renders. Dependency injection is
Hilt; implementations are bound in `di/AppModule` — that's the single place to
swap mock ↔ real.

Two load-bearing design points: **half-duplex turn-taking** (release the mic
before speaking — recording + playback together stalls audio on most devices),
and **the recognizer owns its audio source** (so the platform recognizer, which
fuses capture and recognition, fits the same interface as the mock).

## Tech stack

- Kotlin · Coroutines & Flow
- Jetpack Compose · Material 3 · Navigation Compose
- Hilt (DI) · KSP
- ML Kit Translation · Android `SpeechRecognizer` · `TextToSpeech`
- `minSdk` 34 · `targetSdk`/`compileSdk` 36 · JDK 11

## Build & run

```bash
# Build a debug APK
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug
```

Open in Android Studio and run the `app` configuration, or use the Gradle
commands above (`gradlew.bat` on Windows).

### Device requirements

A physical device is recommended (real mic + TTS). For **audio output**, the
device's TTS engine needs the **target language's on-device voice** installed —
the app surfaces an **Install voice** button when it's missing
(Settings → Text-to-speech → install voice data). On-device recognition likewise
needs the language pack. `RECORD_AUDIO` is requested at runtime.

## Tests

```bash
./gradlew testDebugUnitTest
```

JVM unit tests cover the pipeline's state sequencing (including the
playback-failure path), the translator, and the offline model lifecycle, using
fakes that implement the same interfaces as the real stages.

## Project structure

```
app/src/main/java/com/example/aitranslator/
├─ audio/         AudioCapture / AudioRecordCapture, AudioChunk
├─ asr/           SpeechRecognizer + Android/Mock impls, RecognitionEvent
├─ translation/   Translator + MLKit/Mock impls
├─ tts/           SpeechSynthesizer + AndroidTextToSpeech
├─ model/         Language, ModelManager, ModelState
├─ pipeline/      TranslationPipeline, PipelineState
├─ di/            AppModule (Hilt) — the swap point
└─ ui/            conversation/ + models/ screens, theme/
```

## Documentation

- [`DESIGN.md`](DESIGN.md) — architecture, design decisions and rationale, tradeoffs
- [`CLAUDE.md`](CLAUDE.md) — concise guidance for working in the codebase

---

_Status: prototype — the full on-device ASR → translation → TTS pipeline works end to end._
