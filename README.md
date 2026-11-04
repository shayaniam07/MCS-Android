# Mastery of Clips with Shayan — Android (independent development branch)

A separate Android-native MCS project, **without modifying the Windows EXE or the Windows source**.

**This package contains Android Studio/Gradle source code, not a prebuilt APK.** The environment used to generate it has Java/Kotlin, but no Android SDK, Gradle, or access to download SDK dependencies. The core Kotlin logic is tested; the complete Android build/UI is not.

## Start here

Read [FEATURE_MATRIX.md](FEATURE_MATRIX.md). It lists exactly what is implemented and missing. In particular, on-device Whisper and custom Chatterbox cloning are NOT finished; Android system narration is NOT voice cloning.

## Build debug APK (Windows Android Studio)

1. Extract this directory somewhere **outside** your existing `SyedShorts` Windows folder. For example, `D:\MCS-Android`.
2. Install Android Studio (Android SDK 35, Gradle/JDK configuration managed by Android Studio; JDK 17+).
3. Choose **Open** and select `MCS_Android_Independent`. Sync Gradle dependencies using an internet connection.
4. Select the `app` configuration, then **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Android Studio writes `app\build\outputs\apk\debug\app-debug.apk` when the build succeeds.
6. Install on Android 10+ (API 29+) device for testing. Use videos you own or are authorized to edit.

An optional GitHub workflow exists at `.github/workflows/build-android.yml`. Push **only this separate Android project**, then GitHub > Actions > Build MCS Android debug APK > Run workflow. After a successful run, download artifact `MCS-Android-debug-apk`. This does not require Android Studio on your Windows PC, but it does require a GitHub repository and the workflow must actually complete successfully.

**Do not upload your Windows `D:\MCS_DIST` or the huge `SyedShorts/dist` folder to this project.**

## License / device ID

This project reads the SAME Ed25519 signed key **format** as your Windows `licensing.py`: version byte, 8-byte device ID, expiry and issue timestamps, 64-byte Ed25519 signature. On Android the 8-byte device hash is SHA-256 of `(Settings.Secure.ANDROID_ID + "|MasteryOfClipsWithShayan")` truncated to 8 bytes and presented as XXXX-XXXX-XXXX-XXXX. **The device ID and signed key will differ from the PC.**

- DEBUG APK with missing public key: enters a clearly labeled test mode. Don't distribute this as paid software.
- For activated production build, copy **only** the public key `assets/license_public.key` from the Windows source into `app/src/main/assets/license_public.key`.
- Issue a separate legitimate signed key for the Android device ID using your existing private signing key/admin panel. The **private key stays on your admin laptop**.
- This does not yet query your Cloudflare Worker/D1 to enforce remote revocation. That requires the deployed client's public verify endpoint contract; do not embed the admin token or private key in the APK.
- Android ID may vary by app signing key, user or device reset. Decide the final package ID and release signing configuration before issuing long-lived production keys.

## What works in the source

- Import your main video and a secondary video using SAF (files stay on phone).
- Whole-frame 9:16 / 16:9 / 1:1 preview; main above/below, separate Fit/Fill, safe-area overlay and enlarged caption editing.
- Time-based subtitles via `.srt`, manual captions, drag position, basic colors, system emoji stickers as Unicode characters.
- Select in/out and configure MP4 output with Media3 Transformer. Main audio retained, second video's audio muted.
- Silence-based AutoCut for single-video input: audio decode/energy analysis and exporting non-silent ranges.
- System text-to-speech narration WAV output, import existing audio, add narration as separate audio sequence.
- Script splitter/storyboard that keeps all words, at most 30 scenes.

## Why not a one-click `.exe` to `.apk` conversion?

The Windows application is Python/PySide6/PyInstaller with Windows-specific libraries and a frozen Chatterbox worker. Android uses a different runtime, media decoding path, codec availability, storage model and license ID. Reusing that EXE would produce a nonworking APK.

## Verified and not verified

Verified here: pure Kotlin editing logic (`EditorLogic.kt`) with a 2,500-word test, SRT parse and fit/fill math. Not verified here: actual Gradle/Android compilation, real dual-video export with device codecs, Android license activation with a real public key, Android TTS on a device, or full feature parity. The first CI/Android Studio build may reveal integration errors requiring corrections. **Do not use the debug APK as a production release.**

## Source separation / backup

Your entire Windows project remains where it is, including `.venv`, `assets`, `voice_runtime`, licensing secrets and existing compiled EXE. This project has no commands that delete or modify those files.
