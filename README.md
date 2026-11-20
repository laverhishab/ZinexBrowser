# Zinex Browser

A custom-styled Android browser built with Kotlin and Android WebView.

## Features
- Multiple tabs
- URL navigation and Google search
- Bookmarks and browsing history
- Downloads through Android DownloadManager
- Private tabs (in-memory WebView session)
- Dark/light appearance toggle
- Desktop site toggle
- File chooser support
- Back, forward, reload and home controls

## Build with GitHub Actions
1. Create a GitHub repository named `ZinexBrowser`.
2. Upload all files and folders from this project to the repository root.
3. Commit to `main` (or `master`).
4. Open **Actions → Build Zinex Browser APK**.
5. Download the `Zinex-Browser-APK` artifact from the completed workflow run.

The workflow creates a debug APK and an unsigned release APK. The debug APK is suitable for testing. For public distribution, configure signing secrets and sign the release APK before publishing it.

## Local build
Requires JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Notes
This project uses Android System WebView, not the full Google Chrome application. Website compatibility depends on the WebView version installed on the device. Some websites may restrict embedded browsers or require additional platform-specific integrations.

### Privacy note
Private tabs do not write visited URLs to the app's history and disable WebView cache/form saving. Android WebView uses a shared cookie store, so this is not a fully isolated incognito profile. Do not use it as a guarantee of anonymity.
