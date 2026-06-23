# Converse — Design Document

## 1. Overview

Converse is an Android speech-to-speech translation app. A user speaks in one
language; the app recognizes the speech, translates it, and speaks the
translation aloud, keeping a running transcript of the conversation. It also
manages downloadable per-language models for offline use.

The app runs a **real, on-device, offline pipeline**: Google's on-device speech
recognition (SODA), ML Kit on-device translation, and the platform
text-to-speech engine. Every stage is defined behind an interface so a mock
implementation can be substituted for development, testing, or offline demos.

This document describes the architecture, the key design decisions and their
rationale, and the extension points.

## 2. Goals and non-goals

### Goals
- Clean, layered architecture where the ML stages are swappable without touching
  callers.
- Real-time, streaming UX: live audio level, partial transcripts, then spoken
  output.
- Fully on-device / offline once models and voices are installed.
- Robust against device variation (missing voices, flaky TTS callbacks, mic
  contention) — degrade gracefully, never hang.
- Testable core (pipeline, translation, model lifecycle) as plain JVM unit tests.

### Non-goals
- Cloud/server translation or recognition (on-device only by design).
- Account systems, conversation persistence/history across launches.
- Simultaneous (full-duplex) interpretation — the design is turn-based.
- Custom ASR/MT/TTS models — it uses platform/ML Kit engines.

## 3. High-level architecture

Single Gradle module (`:app`), package root `com.example.aitranslator`,
Jetpack Compose UI, Hilt for dependency injection.

```
            ┌─────────────────────────── UI (Compose) ───────────────────────────┐
            │  ConversationScreen            ModelManagementScreen                 │
            │       │  StateFlow<UiState>          │  StateFlow<List<Model>>       │
            │  ConversationViewModel         ModelManagementViewModel              │
            └───────┼──────────────────────────────┼──────────────────────────────┘
                    │ collects Flow<PipelineState>  │
            ┌───────▼───────────────────────────────────────────────────────────┐
            │                     TranslationPipeline                             │
            │     SpeechRecognizer ─► Translator ─► SpeechSynthesizer             │
            │              (gated by ModelManager.isReady)                        │
            └───────┼───────────────┼───────────────┼───────────────────────────┘
                    │               │               │
        AndroidSpeechRecognizer  MLKitTranslator  AndroidTextToSpeech
        (or MockSpeechRecognizer)(or MockTranslator)(real TextToSpeech)
                    │
           AudioCapture (AudioRecordCapture) — used by the mock recognizer
```

### Layer responsibilities
- **UI** renders state and forwards intents; holds no business logic.
- **ViewModels** own a `StateFlow` of UI state and translate pipeline events into
  it; they own the start/stop lifecycle of a listening session.
- **Pipeline** orchestrates the stages and emits a single typed event stream.
- **Stages** are interface-bound and individually replaceable.

## 4. Component design

### 4.1 Audio capture — `audio/`
- `AudioChunk`: a frame of 16-bit PCM plus a lazily computed normalized RMS
  `amplitude` (0..1) for the VU meter.
- `AudioCapture`: interface exposing `audioFrames(): Flow<AudioChunk>`.
- `AudioRecordCapture`: real `AudioRecord` (16 kHz mono, `VOICE_RECOGNITION`
  source) as a cold `callbackFlow` that releases the recorder on cancellation.

Used by the **mock** recognizer. The real recognizer owns its own capture (see
4.2), so production audio does not flow through this class.

### 4.2 Speech recognition — `asr/`
- `RecognitionEvent`: `Rms(amplitude)`, `Partial(text)`, `Final(text)`.
- `SpeechRecognizer`: `recognize(source): Flow<RecognitionEvent>`. **The
  recognizer owns its audio source.** This is the contract that lets the platform
  recognizer (which fuses capture + recognition) sit behind the same interface as
  the mock.
- `AndroidSpeechRecognizer`: wraps `android.speech.SpeechRecognizer`
  (`createOnDeviceSpeechRecognizer` when available), as a `callbackFlow`.
  Marshals all engine calls to the main thread. On a **final** result it emits
  `Final` and stops; on no-match/timeout it restarts within the same session to
  keep waiting for speech.
- `MockSpeechRecognizer`: consumes an injected `AudioCapture`, emits `Rms` per
  frame, and uses naive VAD to reveal scripted phrases word-by-word then finalize
  — exercising the streaming contract with real audio cadence but no ML.

### 4.3 Translation — `translation/`
- `Translator`: `suspend translate(text, from, to): String`.
- `MLKitTranslator`: ML Kit on-device translation. Caches one client per
  source→target pair; downloads the pair's model on first use; bridges ML Kit
  `Task`s to suspend functions.
- `MockTranslator`: small canned phrasebook with a tagged `[xx] …` passthrough
  fallback; simulated latency.

### 4.4 Text-to-speech — `tts/`
- `SpeechSynthesizer`: `suspend speak(text, language)` + `shutdown()`. `speak`
  suspends until playback finishes so the pipeline can sequence turns.
- `AndroidTextToSpeech`: wraps `TextToSpeech`, forcing the Google engine, and
  **selects an installed local (`-local`) voice** for the language so it doesn't
  fall back to a server voice that fails offline. Bridges
  `UtteranceProgressListener` to a suspend call with timeouts and resilient
  completion (see §6).

### 4.5 Offline model management — `model/`
- `Language`, `TranslationModel`, `ModelState` (`NotDownloaded` /
  `Downloading(progress)` / `Ready`).
- `ModelManager`: in-memory `StateFlow<List<TranslationModel>>` with
  `download`/`delete` and `isReady`. English ships Ready; others are downloaded
  on demand. The pipeline gates on `isReady(target)`.

### 4.6 Pipeline — `pipeline/`
- `TranslationPipeline.run(source, target): Flow<PipelineState>` composes the
  stages and is the only place the stages are wired together at runtime.
- `PipelineState`: `Idle`, `Listening(amplitude)`, `Transcribing(partial)`,
  `Translating(sourceText)`, `Turn(sourceText, translatedText)`,
  `Speaking(...)`, `Error(message)`, `SpeechFailed(language, message)`.

### 4.7 UI — `ui/`
- `ConversationViewModel`/`ConversationScreen`: language pickers (with swap;
  source and target can never be equal), VU meter, live partial transcript,
  conversation history, mic start/stop, runtime `RECORD_AUDIO` request, and the
  error card with an **Install voice** action on `SpeechFailed`.
- `ModelManagementViewModel`/`ModelManagementScreen`: download/delete with
  progress.
- `MainActivity`: `@AndroidEntryPoint`, hosts a Navigation-Compose `NavHost`
  (conversation ↔ models).

## 5. Data flow for one turn

1. `Listening(amplitude)` — recognizer emits RMS; UI shows the VU meter.
2. `Transcribing(partial)` — partial hypotheses stream in.
3. On final transcript the recognizer session ends and the **mic is released**.
4. `Translating(sourceText)` → translator produces target text.
5. `Turn(sourceText, translatedText)` — committed to history **before** speaking.
6. `Speaking(...)` → synthesizer plays the translation.
7. Loop back to (1) for the next turn.

If the target model isn't ready, the pipeline emits `Error` up front and stops.
If playback fails, it emits `SpeechFailed` (translation already shown) and
continues.

## 6. Key design decisions

### D1. Stages behind interfaces; assembled only in DI
Each stage is an interface; concrete impls are bound in `di/AppModule`
(`provideSpeechRecognizer`, `provideTranslator`, `provideSpeechSynthesizer`).
Moving mock → real (or swapping ML engines later) is a one-line change there,
with no impact on the pipeline, viewmodels, or UI. **Rationale:** the primary
architectural goal; also enables fast JVM tests with fakes.

### D2. The recognizer owns audio
Android's `SpeechRecognizer` captures the mic itself and won't accept external
PCM. Rather than special-case it, the `SpeechRecognizer` interface was defined as
"owns its audio and emits events," so both the platform recognizer and the
mock (reading `AudioCapture`) fit. **Rationale:** avoids two mic clients fighting
over the device; keeps one clean abstraction.

### D3. Half-duplex turn-taking
The pipeline listens for exactly one utterance, which cancels the recognizer flow
and releases the mic, then translates and speaks, then resumes. The platform
recognizer is also prevented from self-restarting after a result. **Rationale:**
recording (`VOICE_RECOGNITION`) while TTS plays stalls audio on most devices
(observed: synthesis never completes). Mutual exclusion is required for audio to
play at all.

### D4. Commit the translation before speaking
`Turn` (added to history) is emitted before `Speaking`. **Rationale:** TTS is the
most failure-prone stage (missing voices); the user must still see the
translation even when it can't be spoken. Playback is best-effort.

### D5. Bridging callback APIs to coroutines safely
Platform callbacks (TextToSpeech utterance progress, recognizer results) are
bridged with `suspendCancellableCoroutine`/`callbackFlow`. Two hard-won rules,
encoded in `AndroidTextToSpeech`:
- Resume with `continuation.resumeWith(result)`, **not**
  `resume(result.getOrThrow())` — the latter throws on the binder callback thread
  and leaks the continuation (the symptom was a permanent "Speaking…" hang).
- The completion handler tolerates a null/mismatched utterance id (some engines
  report one on error), and `speak` is bounded by init/playback **timeouts** so a
  stuck engine can never hang the pipeline.

### D6. Explicit local-voice selection
TTS enumerates voices and picks a non-network `-local` voice for the target
language (preferring the matching country), ignoring the unreliable
`notInstalled` feature flag (advertised on every voice on the test device).
**Rationale:** passing a generic locale made the engine choose a `-server` voice
that fails offline with synthesis error -4.

### D7. Graceful missing-voice UX
When playback fails, `SpeechFailed(language, …)` drives an **Install voice**
button that opens the system TTS installer
(`ACTION_INSTALL_TTS_DATA`). **Rationale:** the most common real-world failure is
a device simply lacking the target voice; make the fix one tap.

### D8. Source ≠ target
Selecting a language equal to the other slot swaps the two. **Rationale:**
translating a language into itself is a no-op that confuses users.

## 7. Concurrency model

- The pipeline is a cold `channelFlow`; the ViewModel collects it in a
  `viewModelScope` job started/stopped by the mic button. `onCompletion` resets
  UI listening state for both manual stop and self-terminating runs (e.g. the
  model-not-ready gate).
- Audio I/O runs on `Dispatchers.IO`; platform TTS/recognizer calls are posted to
  the main thread as those APIs require.
- Cancellation propagates cleanly: cancelling the collection cancels the
  recognizer flow, whose `awaitClose` releases the mic.

## 8. Dependency injection

Hilt with a single `SingletonComponent` module (`di/AppModule`). Stages and the
pipeline are `@Provides @Singleton`. ViewModels are `@HiltViewModel`;
`MainActivity` is `@AndroidEntryPoint`; screens obtain VMs via `hiltViewModel()`.
The swap points are the three `provide*` functions for the stages.

## 9. Permissions and device requirements

- `RECORD_AUDIO` declared in the manifest and requested at runtime in
  `ConversationScreen`.
- `<queries>` entries for the speech-recognition and TTS services, plus the Google
  TTS package, for Android 11+ package visibility.
- Real audio output requires the **target language's on-device TTS voice**;
  on-device recognition requires the language pack. Both are surfaced/installable
  from the app (Install voice) or system settings.

## 10. Testing strategy

JVM unit tests (`app/src/test`) with fakes — no instrumentation needed:
- `TranslationPipelineTest`: maps recognition events to the expected
  `PipelineState` sequence; verifies the turn is committed even when playback
  fails (`SpeechFailed`); verifies the model-not-ready gate.
- `MockTranslatorTest`: phrasebook hits, normalization, fallback.
- `ModelManagerTest`: `NotDownloaded → Downloading → Ready → deleted`.

The fakes implement the same interfaces as the real stages, so tests exercise the
real pipeline logic. Device-only behavior (real ASR/TTS, mic contention) is
validated manually.

## 11. Known limitations and future work

- **In-memory model state.** `ModelManager` resets on process death; back it with
  DataStore for persistence, and reconcile with ML Kit's own model store.
- **Translation model downloads aren't surfaced in the model UI.** ML Kit
  downloads happen lazily inside `MLKitTranslator`; the `ModelManager` UI is
  currently illustrative. Unify them and show progress/gate on translation models
  too.
- **Turn-based only.** No barge-in or streaming translation of partials.
- **Mock translator** fallback is a tagged passthrough; only relevant when wired
  in instead of ML Kit.
- Languages are a small fixed set (`Language.ALL`); extend as needed (codes must
  be supported by ASR, ML Kit, and TTS).
