# 📱 Code Editor — Android (VS Code style)

A lightweight, mobile-first code editor for Android phones, inspired by the look
and feel of VS Code. Built with Kotlin and the high-performance
[Sora Editor](https://github.com/Rosemoe/sora-editor) widget.

## ✨ Features

- **Syntax highlighting** for common languages (Java, Kotlin, JS/TS, JSON, C/C++, Go, Rust, and more)
- **File explorer** in a slide-out drawer (open any folder via the Storage Access Framework)
- **Multiple tabs** with unsaved-changes indicators
- **Mobile symbol toolbar** — quick access to `{ } ( ) ; → " '` etc. above the keyboard
- **Pinch to zoom** the editor text
- **Word wrap** toggle
- **Open / Save / Save As** using Android's native document picker (no storage permissions needed)
- **Dark "VS Code Dark+" theme**

## 📦 Download

Pre-built APKs are published automatically to the
[**Releases**](../../releases) page on every push.

> The build produces a signed APK that goes straight into a GitHub Release
> (the CI keeps storage usage low by not retaining large artifacts long-term).

Download the latest `CodeEditor-vX.Y.Z.apk`, copy it to your phone, and install
it (you may need to enable *Install from unknown sources*).

## 🛠 Building locally

Requirements: JDK 17 and the Android SDK (API 34).

```bash
# Debug build (installable, debug-signed)
./gradlew assembleDebug

# Release build (debug-signed unless you provide a keystore via env vars)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/`.

To sign a release build with your own key, set these environment variables
before running `assembleRelease`:

| Variable | Description |
|---|---|
| `KEYSTORE_FILE` | Path to your `.jks`/`.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

## 🤖 CI / Release workflow

`.github/workflows/build.yml` runs on GitHub Actions and:

1. Sets up JDK 17, the Android SDK and Gradle 8.7
2. Generates a signing keystore on the fly
3. Builds a signed **release** APK
4. Publishes it to a new **GitHub Release** (tagged `v1.0.<run_number>`)

You can also trigger it manually from the **Actions** tab (*Run workflow*).

## 🧱 Tech stack

- Kotlin · Android Gradle Plugin 8.5 · Gradle 8.7
- `minSdk 26`, `targetSdk 34`
- Material 3 components
- Sora Editor `0.23.4`

## 📂 Project structure

```
app/src/main/
├── java/com/mobile/codeeditor/
│   ├── MainActivity.kt       # Editor, tabs, file IO, menus
│   ├── FileTreeAdapter.kt    # Collapsible file explorer
│   └── OpenFile.kt           # Open-tab model
├── res/                      # Layouts, drawables, theme
└── AndroidManifest.xml
```
