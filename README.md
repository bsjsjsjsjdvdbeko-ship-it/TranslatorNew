# Sari Subtitle — Stage 4 (Groq + Google Translation / MyMemory)

This ZIP is a source-code update based on the supplied Stage 3 archive.

## Stage 4 changes
- Groq transcription endpoint `https://api.groq.com/openai/v1/audio/transcriptions`, model `whisper-large-v3-turbo`, JSON response and configurable speech language (default `ar`).
- Google Cloud Translation v2 and MyMemory translation client, with configurable source/target languages and optional MyMemory email.
- HTTP request retries up to three attempts and Arabic-facing error messages.
- 3-second chunk timing with 500 ms overlap primitives and transcript deduplication (`AudioChunker.kt`).
- Overlay can show original + translated text, is draggable, and hides its text after 3 seconds without an update.
- Settings screen for Groq key, Google key, MyMemory email, languages, font size and opacity values; Groq key signup shortcut.

## Important limitation — not yet a complete live-captions APK
Stage 3's `AudioCaptureService` is still a permission-gated scaffold. It does **not** yet capture another app's playback through a complete MediaProjection/AudioPlaybackCapture pipeline, create actual audio chunk files, or continuously feed those chunks into `TranslationRepository`. Consequently, this archive is a Stage 4 source update, not a verified ready-to-install real-time captioning app. `AudioChunker` currently provides chunk timing and transcript deduplication primitives; a device-side playback-capture pipeline must call them and write audio chunks. Android requires user consent for playback capture and some apps prohibit it.

## Setup
1. Open `SariSubtitle` in Android Studio with JDK 17 and Android SDK 35.
2. Add a Groq key in Settings; get one at https://console.groq.com/keys.
3. Select Google and add a Google Cloud Translation API key, or choose MyMemory without a key.
4. Grant overlay permission. API usage limits and provider policies apply; MyMemory is not an unlimited service.
5. Keep API keys private and do not commit them to a public repository.

Build status: source files were packaged; no successful Android build or on-device end-to-end test is claimed here.
