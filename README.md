# Maira — offline Hindi voice assistant (Android)

Kotlin, single module, minSdk 26 / targetSdk 34, arm64-v8a. Debug-signed APK: `Maira.apk`.

* Brain: MediaPipe LLM Inference `com.google.mediapipe:tasks-genai:0.10.24` + Qwen2.5 `.task` model (downloaded in-app via DownloadManager, or picked from storage). Prompt uses ChatML; session prompt templates are set to empty so the prompt is passed verbatim.
* Voice: Android `SpeechRecognizer` (hi-IN, `EXTRA_PREFER_OFFLINE`), `TextToSpeech` Locale("hi","IN").
* Phone control: `CommandParser` (rules, Hinglish + Devanagari) → `PhoneActions`; `MairaAccessibilityService` for global actions / type / scroll / click; LLM JSON-action fallback in `AssistantBrain`.
* "Hey Maira": `WakeWordService` (foreground, microphone type) loops the recognizer.

Build (normal PC with Android SDK): `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Unit tests: `./gradlew testDebugUnitTest`.

Sandbox-specific: `build.sh` sources `../work/tools/env.sh` (JDK 17 aarch64, SDK, local proxy, ARM aapt2 override in `GRADLE_USER_HOME/gradle.properties`). `local.properties` points at the sandbox SDK — change `sdk.dir` elsewhere.
