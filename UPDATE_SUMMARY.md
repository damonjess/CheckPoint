# Face-Capture and Termux Update Summary

## Scope

This update improves the Android capture and verification pipeline for **self-photos or photos that the user is authorized to search**. It also removes the entire local Gemma `.task` feature.

| Area | Change |
|---|---|
| Capture quality | The app now requires a single, sufficiently large face; it checks pose, lighting, blur, and eye openness before starting a search. |
| Face alignment | Enrollment and verification crops use ML Kit eye landmarks, preserve the forehead and full jawline, and avoid prior chin truncation. |
| Reverse-image probe | Search uses a size-normalized copy of the original photo rather than a mirrored, high-contrast, masked, or composite image. |
| False-result reduction | The source face is a quality-gated, landmark-aligned crop used for local thumbnail verification. |
| Termux | The helper detects Termux correctly, runs engines sequentially there, reports runtime status at `/api/ping`, and binds only to `127.0.0.1`. |
| Desktop | The same helper retains parallel engine execution outside Termux. |
| External challenges | The helper records an access challenge and stops that engine rather than attempting to work around it. |
| Gemma | Removed `GemmaAnalyzer.kt`, all ViewModel/UI state and calls, `tasks-genai`, `.task` packaging configuration, and the results card. |

## Capture criteria

The detector requires a photo of at least **480×360 px** with one visible face. The face must be at least **100×100 px**, occupy at least **7%** of the image, and remain within conservative pose, lighting, focus, and eye-openness limits. These limits are intentionally visible to the user through clear capture feedback, rather than failing silently.

ML Kit documents that face-recognition input should generally be at least 480×360 pixels, with faces generally at least 100×100 pixels; it also notes that focus and orientation affect accuracy.[1]

## Termux operation

The complete Termux and desktop setup instructions are in [`face-search-service/README.md`](face-search-service/README.md). The key Termux sequence is:

```bash
pkg update
pkg install nodejs-lts chromium
cd face-search-service
export PUPPETEER_SKIP_DOWNLOAD=true
npm install
CHROMIUM_PATH="$(command -v chromium || command -v chromium-browser)" npm start
curl http://127.0.0.1:3000/api/ping
```

The Android app first checks `127.0.0.1:3000`. If the helper is not running, the app falls back to its ordinary in-app image-search route.

## Validation

| Check | Result |
|---|---|
| JavaScript syntax | Passed with `node --check face-search-service/server.js`. |
| Removed-source verification | Passed: no production references to Gemma, `.task`, MediaPipe GenAI, image-alteration helpers, or browser-evasion components remain. |
| Android build invocation | Started successfully after restoring the missing Gradle wrapper binary, then stopped before Kotlin compilation because the sandbox has no installed Android SDK or `ANDROID_HOME`. |

> The build limitation is environmental. Run `./gradlew :app:assembleDebug` from Android Studio or from a workstation with Android SDK Platform 36 installed to perform the final APK compilation.

## Files changed

The key implementation files are `vision/FaceDetectorHelper.kt`, `vision/NativeFaceCropper.kt`, `ui/CheckInViewModel.kt`, `ui/EnrollViewModel.kt`, `ui/CheckInUiState.kt`, `ui/components/CheckInContent.kt`, `ui/components/SearchModeSelector.kt`, `network/FaceSearchRepository.kt`, `app/build.gradle.kts`, and `face-search-service/server.js`.

## References

[1] [Google ML Kit — Detect faces with ML Kit on Android](https://developers.google.com/ml-kit/vision/face-detection/android)
