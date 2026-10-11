# Sari Subtitle — Stage 8

Stage 8 is an in-place extension of the supplied Stage 7 Android source project. The existing
applicationId (`com.sari.subtitle`), namespace, Kotlin/Gradle versions, Room database, capture
service, overlay service and settings architecture were preserved.

## What was implemented

- Internal HTTPS WebView browser with URL bar, back/forward/refresh, progress/error states and external-browser fallback.
- Secure WebView defaults: no file/content access, no mixed content, no JavaScript native bridge, and no automatic permission grants.
- Platform URL resolver for YouTube, TikTok, Instagram/Reels, Facebook/Watch, X, Vimeo, Twitch and direct media URLs.
- Android `ACTION_SEND` text/URL and `ACTION_VIEW` HTTP(S) link handling with `singleTop`/`onNewIntent` duplicate protection.
- Direct-media playback through the existing Media3/ExoPlayer screen, including HLS/DASH modules.
- Optional remote SRT/WebVTT subtitle track support for direct media URLs.
- Official Widevine DRM playback configuration: the user may supply an HTTPS license URL from an authorized content provider; no DRM bypass, key extraction, or protected-media scraping is performed.
- A live subtitle state bus connected to the existing capture/translation pipeline so translated results can appear inside the video player as well as the existing overlay.
- MediaProjection stop callback and existing coroutine cancellation/resource cleanup retained.
- SRT/WebVTT parsing utility with automated JVM tests.
- No API keys are included.

## Platform policy / capability boundary

A normal webpage URL is not treated as a direct media file. Known social/video platforms are opened in the internal browser rather than scraped for protected media. The application does not bypass login, DRM, geographic restrictions, signed URLs, anti-bot controls or platform access rules. DRM playback uses Media3's official Widevine path only when an authorized license endpoint is supplied.

Playback capture remains subject to Android's `AudioPlaybackCapture` rules and the source application's capture policy. If the source does not permit capture, Sari Subtitle reports failure rather than attempting a bypass.

## Groq

Stage 8 keeps Groq's official speech-to-text endpoint and `whisper-large-v3-turbo`. Current Groq documentation lists it as multilingual and supports `json`, `text`, and `verbose_json`; segment/word timestamps require `verbose_json`. The current live path remains low-latency chunk transcription, while precise player synchronization is only available for subtitle sources that provide actual timestamps.

## Configuration

1. Open the project in Android Studio with JDK 17 and Android SDK 35.
2. Add the user's Groq API key in Settings. Never commit it.
3. Select the translation provider and configure its key/email only when required.
4. Grant MediaProjection consent when starting live playback capture.
5. Grant overlay permission only if floating captions are wanted.

## External services

- Groq Speech-to-Text: required for live transcription.
- Google Cloud Translation: optional.
- MyMemory: optional.
- Network access is HTTPS only.

## Verification

The source tree and XML were inspected after modification. Automated JVM tests were added for subtitle parsing and platform URL resolution. This environment did not contain Gradle/Android SDK tooling and could not resolve external Gradle downloads, so `assembleDebug`, Gradle Sync, and device/end-to-end tests were **not claimed as executed successfully**.

## Known limitations

- Social platform webpage playback depends on the platform's own WebView compatibility and policies; a page URL is not converted into a protected media URL.
- Some platforms may require their official application or external browser.
- Playback capture can return no audio when the source application disallows capture or uses protected playback.
- The live transcription path does not invent frame-accurate timestamps. It uses arrival/capture timing for live display; exact synchronization is only guaranteed when a real subtitle track supplies timestamps.
- Microphone fallback is not silently enabled; the current live path intentionally remains playback-capture based.

## DRM الرسمي

للمحتوى المحمي يمكن إدخال رابط ترخيص Widevine الرسمي عبر HTTPS في شاشة المشغل. التطبيق لا يستخرج مفاتيح DRM ولا يتجاوز الحماية. نجاح تشغيل DRM لا يعني أن `AudioPlaybackCapture` مسموح به؛ إذا منع المصدر التقاط الصوت، تبقى الترجمة الحية غير متاحة لهذا المصدر.
