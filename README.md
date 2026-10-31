# Maira — offline Hindi voice assistant (Android)

Kotlin, single module, minSdk 26 / targetSdk 34, arm64-v8a. APK: `Maira.apk`, signed with a private RSA-4096 release key. Only the AES-256 encrypted `signing/maira-release.jks.enc` is in the repo; CI decrypts it with the `MAIRA_SIGNING_PASS` Actions secret. Builds fail if the key is missing, so a wrong-key APK is never published.

* Brain (v1.4): the user's own **Maira Brain v2** (tiny encoder-decoder Transformer + copy head, int8 ONNX in `app/src/main/assets/`) run with `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (`brain/MairaTokenizer.kt`, `brain/MairaBrain.kt`). Routing in `AssistantBrain`: meta rule commands → Maira Brain → `BrainRouter.plan` (existing `Command`s or `BrainActions`) → rule parser fallback (invalid JSON / unknown action / empty slot / confidence < 0.45 with a rule match) → otherwise a Hindi "still learning" line. No third-party LLM (MediaPipe/Qwen removed).
* Voice: Android `SpeechRecognizer` (hi-IN, `EXTRA_PREFER_OFFLINE`), `TextToSpeech` Locale("hi","IN").
* Phone control: `CommandParser` (rules, Hinglish + Devanagari) → `PhoneActions`; `MairaAccessibilityService` for global actions / type / scroll / click; LLM JSON-action fallback in `AssistantBrain`.
* Screen reading: `MairaAccessibilityService.readScreen()` walks all TYPE_APPLICATION windows (skipping Maira's own), `ScreenText.compose` sorts/dedupes/caps (~1500 chars). `Command.ReadScreen(summarize)` → `ActionResult.deferredSay` runs after Maira hides itself; "summary" requests are read aloud (no LLM).
* Notifications: `MairaNotificationListener` (optional special access, Setup button) for current notifications; fallback = accessibility `typeNotificationStateChanged` cache.
* App opening: `AppMatcher` (alias map + Translit fuzzy match, ambiguity → "कौन सा खोलूँ?"). Activities are started via the accessibility-service context so launches from `WakeWordService` are not blocked by background-activity-start limits.
* "Hey Maira": `WakeWordService` (foreground, microphone type) loops the recognizer.

**v1.2 automation**
* `AutoSetupActivity` (first launch, also "सेटअप करो"): one big button per step, spoken in Hindi, auto-advances on `onResume`, skips done steps, auto-opens each settings screen once. Order: Hindi TTS → (Android 13+ "Allow restricted settings" guide when needed) → Accessibility → runtime perms → notification access → battery opt-out → install-unknown-apps → Vivo autostart → DND → offline Hindi speech pack. Ends by enabling the wake service.
* `SetupGuide` (pure, tested): while Auto Setup has sent the user into Settings, the accessibility service reads that screen and speaks what to tap next.
* `AutoClick` (pure, tested): accessibility auto-taps ONLY (a) Install/Update in the package installer while Maira's own update is armed and the dialog names Maira, (b) Allow on runtime-permission dialogs that name Maira while Auto Setup armed them (90 s).
* `ScreenBolo`: "screen bolo mode chalu/band karo" — speaks app name + first lines on each window change (2 s debounce; skips Maira, keyboard, password fields, banking/UPI apps).
* Model download: removed in v1.4 (an unfinished old Qwen download is cancelled on start).
* `Watchdog` (WorkManager, 15 min) + `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED) + accessibility `onServiceConnected`: restart wake service, retry model, daily update check. Android 14+ may refuse to start a microphone FGS from the background; then it starts on next app open.
* `Updater`: GitHub `releases/tags/latest`; CI sets versionCode = 100 + GITHUB_RUN_NUMBER and writes `versionCode=` into the release body + `version.txt` asset. Downloads via DownloadManager, verifies package + signature, installs with a PackageInstaller session (USER_ACTION_NOT_REQUIRED on Android 12+ once Maira is installer of record), FileProvider ACTION_VIEW fallback.

Build (normal PC with Android SDK): `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Unit tests: `./gradlew testDebugUnitTest`.

Sandbox-specific: `build.sh` sources `../work/tools/env.sh` (JDK 17 aarch64, SDK, local proxy, ARM aapt2 override in `GRADLE_USER_HOME/gradle.properties`). `local.properties` points at the sandbox SDK — change `sdk.dir` elsewhere.
