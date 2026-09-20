# Restored structured Google Lens fallback

This build keeps the current Termux, image-safety, provider-status, and TinEye improvements, and restores the old structured SerpApi Google Lens fallback.

## What changed

`CheckInViewModel.kt` now runs an independent SerpApi fallback when Termux is available and `SERP_API_KEY` is configured. It sends the scene probe URL, requests visual matches, optionally requests exact matches, and merges those structured results with the local and WebView results. This means a Termux Chromium access challenge does not suppress the old Google Lens result path.

The existing no-Termux path already invokes `performSerpApiSearch()` when the key is configured; this patch also enables the same structured fallback alongside Termux.

## Configure the key locally

Do not commit or upload a real SerpApi key. In Android Studio, create or edit `local.properties` at the project root and add:

```properties
SERP_API_KEY=YOUR_SERPAPI_KEY_HERE
```

The Gradle file reads this value into `BuildConfig.SERP_API_KEY`. Use a private development key only; an APK can be reverse engineered, so production use should move the provider request to a server-side component.

## Build in Android Studio

Open the extracted project directory in Android Studio, allow Gradle sync to finish, then choose **Build > Clean Project** followed by **Build > Assemble Debug APK**. The APK is produced under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on the device after starting the Termux helper if you want to test the combined Termux + SerpApi path.

## Expected log

With a key configured and a reachable scene URL, the app should log:

```text
Requesting Google Lens visual matches via SerpApi...
✓ SerpApi found N visual candidate(s)
```

With exact matching enabled, it should report both visual and exact counts. If Termux providers request an access check, the SerpApi results should still be available independently.

## Important interpretation

SerpApi visual results are candidate webpages and image evidence. They are not proof of a person's identity. Keep the app's review labels and user-consent flow intact.

## Local compilation note

The sandbox copy cannot compile Android code unless an Android SDK is installed and `ANDROID_HOME` or `local.properties` points to it. Android Studio normally supplies this SDK automatically.
