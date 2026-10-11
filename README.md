# Sari Subtitle — Stage 3

Android Kotlin starter implementation for speech transcription, translation-provider clients,
secure settings, a floating overlay, and a Room translation history.

## Build
- Android Studio / GitHub Actions: JDK 17, Android SDK 35, Gradle 8.9, AGP 8.7.3.
- GitHub Actions workflow runs `gradle assembleDebug`.

## Important implementation status
This stage includes working HTTP client methods for audio files and translation text, encrypted
settings, history persistence, and an overlay shell. It is **not yet an end-to-end live captioning
product**: AudioCaptureService deliberately does not yet implement the MediaProjection consent
flow, playback-capture AudioRecord pipeline, speech-segment buffering, or automatically connect
captured segments to TranslationRepository. The overlay is currently a panel and is not wired to
live repository events. Android does not permit silent capture of arbitrary app audio; users must
approve playback capture, and individual apps may disallow capture.

## Configure
1. Install the app and grant “Display over other apps” permission.
2. Open Settings and add an OpenAI key for Whisper transcription.
3. Select a translation provider and add its API key. Google Cloud Translation and DeepL require
   enabled/billable provider accounts; OpenAI usage is also billed according to current pricing.
4. Keep API keys private. Do not commit them to GitHub.

Source language uses ISO language codes (e.g. `en`); `auto` is supported where provider APIs allow.
Target defaults to `ar`.

## Notes
- Network traffic uses HTTPS only.
- API calls retry up to three attempts.
- VAD currently provides an RMS energy detector helper, not a complete adaptive streaming VAD.
- EncryptedSharedPreferences is from AndroidX Security Crypto and may be deprecated in future
  library versions; it is retained here to match the requested API.
