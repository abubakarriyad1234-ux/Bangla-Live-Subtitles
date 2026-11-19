# Bangla Live Subtitles

Free Android app (original code, written from scratch) that shows **live Japanese
speech as Bangla subtitles floating over any app** — made for following a Voov
video call in Japanese.

- **Phone-audio mode (default):** captures the phone's internal playback audio
  (the Voov call) with Android's MediaProjection + AudioPlaybackCapture — the
  microphone is not used, so there is no mic fight with the call.
- **Microphone mode (fallback):** listens with the mic, for when an app opts
  out of audio capture or the call is on a speaker.
- **Offline Japanese speech recognition** in phone-audio mode with Vosk
  (open source, Apache 2.0). The small Japanese model (~48 MB) is downloaded
  once, inside the app, on first use.
- **Microphone mode** uses Android's built-in SpeechRecognizer (Google, free).
- **Translation Japanese → Bangla** with free endpoints (Google gtx first,
  MyMemory as fallback). No API key, no account.
- **Floating subtitle box** over any app: Japanese line + Bangla line together,
  draggable, adjustable text size.
- No ads, no paywall, no account, no tracking.

## Install (from GitHub Releases)

1. Download the APK from
   [Releases](https://github.com/abubakarriyad1234-ux/Bangla-Live-Subtitles/releases)
   (`app-debug.apk` under v1.0).
2. On the phone, allow "Install unknown apps" for the browser/Chrome when asked.
3. Open the app and grant:
   - **Display over other apps** (for the floating subtitles),
   - **Microphone** (only for mic mode),
   - **Notifications** (for the foreground-service notice).
4. Phone-audio mode: tap **"জাপানি মডেল ডাউনলোড করো (48 MB)"** once.
5. Start the Voov call first, then open the app, pick **ফোন অডিও**, and tap
   **শুরু করো**. Allow the screen-capture prompt. The subtitle box appears
   over the call — drag it anywhere. Tap ✕ on the box to stop.

## Honest limits (read before expecting magic)

- **Untested on a real phone.** The APK is built by GitHub Actions and has not
  been run on any device yet. Test it and report what happens.
- **Voov may block audio capture.** Android lets apps opt out of playback
  capture. Whether Voov allows it is unknown until tested on a real phone.
  If the phone-audio mode hears nothing, use **microphone mode** (call on
  speaker) instead.
- **Translation uses unofficial free endpoints.** Google's gtx endpoint and
  MyMemory are free and keyless, but they can be slow or rate-limited. The app
  shows "অনুবাদ হয়নি" instead of crashing when translation fails.
- **Android's SpeechRecognizer cannot hear captured audio.** There is no public
  Android API to feed playback-captured sound into it — that is exactly why
  phone-audio mode uses offline Vosk instead. Mic mode uses SpeechRecognizer.
- **Vosk model quality:** the small Japanese model is compact; recognition of
  fast or accented speech will be imperfect.
- Phone-audio mode v1 only supports **Japanese** as the source language.

## Build it yourself

Needs JDK 17 and the Android SDK (API 34, build-tools 34.0.0):

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

CI builds every push to `main` with GitHub Actions (JDK 17 + Gradle 8.7).
Pushing to the `release` branch builds and attaches the APK to the v1.0
GitHub Release.

## Privacy

- Phone audio / mic audio is processed on the device for recognition (Vosk is
  fully offline; SpeechRecognizer uses Google's on-device/system service).
- Recognised text is sent to the free translation endpoints above — nothing
  else leaves the phone. No account, no analytics.

## License

MIT — see [LICENSE](LICENSE). Vosk (org.vosk) is Apache 2.0.
