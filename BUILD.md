# GameLens — Build & Install Instructions

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 34 (install via SDK Manager) |
| Build Tools | 34.x (install via SDK Manager) |

You do **not** need a Google account or any API keys — all translation and OCR runs on-device via ML Kit.

---

## 1. Open the project

1. Launch **Android Studio**.
2. Choose **File → Open** and select the `translate_app` folder (the one containing `settings.gradle.kts`).
3. Wait for Gradle sync to finish (first run downloads ~500 MB of dependencies + Gradle itself).

---

## 2. Download the Gradle wrapper (first time only)

Android Studio will download the wrapper automatically.
If you see a "Gradle sync failed" error mentioning the wrapper JAR:

```
# In the Android Studio Terminal (bottom of screen):
gradle wrapper --gradle-version 8.6
```

---

## 3. Build the debug APK

### Option A — Android Studio UI

1. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Click "locate" when the build finishes.
3. The APK is at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B — Command line (Windows)

```batch
cd translate_app
gradlew.bat assembleDebug
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`

---

## 4. Install on the Ayn Thor

### Via ADB (recommended)

1. Enable **Developer Options** on the Ayn Thor:
   Settings → About Device → tap *Build Number* 7 times.
2. Enable **USB Debugging** in Developer Options.
3. Connect via USB. On your PC:

```batch
adb install app\build\outputs\apk\debug\app-debug.apk
```

### Manual sideload

1. Copy `app-debug.apk` to the Ayn Thor (USB file transfer or SD card).
2. On the device: Settings → Security → enable **Install unknown apps** for your file manager.
3. Open the APK from the file manager and tap Install.

---

## 5. First-time app setup

1. **Open GameLens** on the **bottom screen**.
2. Grant the **notification permission** (required by Android for the screen-capture foreground service).
3. Select your **source language** (e.g. Japanese) and **target language** (e.g. English).
4. Tap **Translate Screen** (Manual mode) — a system dialog asks:
   *"GameLens would like to capture everything displayed on your screen."*
   Tap **Start now**.
5. The app downloads the ML Kit translation model (~30 MB, one-time, Wi-Fi recommended).
   You will see *"Downloading translation model…"* in the status bar.
6. Once ready, OCR + translation results appear automatically.

---

## 6. Dual-screen display setup (important for Ayn Thor)

The Ayn Thor has two displays:

| Display | Typical ID | Used for |
|---------|-----------|---------|
| Top (6" main screen) | 0 | Gaming |
| Bottom (3.92" screen) | 1 | GameLens |

GameLens defaults to capturing **Display 0** (the top/game screen).
If the wrong screen is being captured:

1. Tap the **⚙ settings** icon in GameLens.
2. Under **Capture display**, select the display where your game is running.
3. Tap **Save**.

> **Note:** On some Android 13 builds, both screens may appear as a single
> logical display. If you see your own GameLens UI being translated instead
> of the game, try toggling the display ID in settings.

---

## 7. How to use

### Manual mode (default)
- Switch to the game on the top screen.
- Tap **Translate Screen** in GameLens on the bottom screen.
- The app captures → OCR → translates and shows results.
- Tap any **highlighted word** in the original text to open a Jisho dictionary popup with readings and meanings.

### Live mode
- Tap **Live** in the mode toggle.
- Tap **Start Live** — captures run automatically on the interval set in Settings (default: 3 s).
- Tap **Stop Live** to stop.

---

## 8. Permissions explained

| Permission | Why |
|-----------|-----|
| `FOREGROUND_SERVICE` | Required to hold the MediaProjection token while the app is in use |
| `POST_NOTIFICATIONS` | Android 13 requires this to show the "GameLens is active" notification |
| `INTERNET` | Used only for Jisho dictionary lookups when you tap a word |
| Screen capture (MediaProjection) | Granted via system dialog; captures the game screen for OCR |

GameLens does **not** send screenshots or OCR text to any server.
Translation is fully on-device (ML Kit). Dictionary lookups go to jisho.org over HTTPS.

---

## 9. Troubleshooting

| Symptom | Fix |
|---------|-----|
| "No text detected" | The game may use bitmapped font rendering — OCR works best on standard fonts |
| Translation quality is poor | ML Kit's on-device model is optimised for correctness over fluency; results improve with more context |
| App captures its own screen | Change the Capture Display in Settings to the other display ID |
| Jisho lookup fails | Check internet connection; Jisho has no official SLA |
| Gradle sync fails | Make sure Android SDK 34 is installed via SDK Manager |

---

## 10. Future features (not yet implemented)

- Anki flashcard integration — tap a word and export it directly to Anki
- Offline dictionary (bundled JMdict SQLite) — for word lookup without internet
- History / log of translated screens
- Overlay mode — show translated text overlaid on the captured image
