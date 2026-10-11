# Sari Subtitle — Stage 1: Playback-audio capture test

This is a new Android/Kotlin project. The first milestone is deliberately narrow:
- Build a debug APK.
- Request Android's MediaProjection consent.
- Attempt playback-audio capture with `AudioPlaybackCaptureConfiguration`.
- Show a floating diagnostic overlay with sample activity.
- Do **not** claim that X/Twitter audio is capturable until it has been tested on-device.

No speech-recognition or translation engine is included yet.

## Important capture limitations

Android playback capture is not universal. The source app must allow capture, the audio usage must match the capture configuration, and device/OS policies can affect results. The system's MediaProjection consent dialog is not proof that the source app allows its audio to be captured. If X produces silence, test a known capture-permitting media app and compare.

The `RECORD_AUDIO` permission is requested because Android requires it for playback capture; this prototype does not intentionally record microphone input. The service processes captured samples in memory and does not save audio.

## Build APK using GitHub Actions

1. Extract this ZIP.
2. Create a new empty repository on GitHub.
3. Upload the **contents** of this folder to the repository root (the `.github/workflows/build.yml` file must be included).
4. Open the repository's **Actions** tab.
5. Select **Build Sari Subtitle APK**, then **Run workflow** (or push to `main`/`master`).
6. When the run succeeds, open it and download the `sari-subtitle-debug-apk` artifact.
7. Extract the artifact ZIP on the phone and install `app-debug.apk`.

The workflow installs Java 17, Android SDK platform 35, build-tools 35.0.0, and Gradle 8.9. The project uses AGP 8.7.3 and Kotlin 2.0.21.

## Test on Android

1. Install the debug APK and open Sari Subtitle.
2. Grant the overlay permission when prompted.
3. Grant the audio permission.
4. Tap **Request playback audio capture permission** and approve Android's MediaProjection dialog.
5. Start audio playback in another app. Try a known capture-permitting test source first, then try X.
6. Watch the floating status:
   - **Non-silent samples detected**: captured playback audio is reaching the app.
   - **No clear samples**: verify playback volume and try a known allowed source. This may mean the source app blocks capture; it is not definitive by itself.
7. Tap **Stop test** to end the capture session.

## Current scope and known limitations

- This is a diagnostic prototype, not the finished subtitle app.
- The overlay is not yet draggable.
- The audio level detector is only a rough indicator; it does not identify speech.
- The current code uses a single MediaProjection session per user approval, as required by modern Android.
- No subtitle translation is implemented.
- The app has not been built or tested on a physical device in this environment. The first GitHub Actions run is the build verification step.

## Project layout

- `MainActivity.kt`: permissions and test controls.
- `AudioCaptureService.kt`: foreground MediaProjection service, playback AudioRecord, and floating status.
- `.github/workflows/build.yml`: cloud build and APK artifact publishing.
