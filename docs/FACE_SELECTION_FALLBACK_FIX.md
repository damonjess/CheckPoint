# Face Selection Fallback Fix

## Issue addressed

When the Termux helper was unavailable, the app detected and cropped the selected face locally but then uploaded the original full photo for the in-app visual-search fallback. Providers could therefore see a multi-subject or distant scene instead of the selected face, causing their face-selection step to report that no face was detected.

## Changes included

| File | Change |
|---|---|
| `CheckInViewModel.kt` | The visual probe passed to both Termux and non-Termux search paths is now `detection.croppedFace`. The original normalized photo is still retained for local quality checks and EXIF hint extraction. |
| `FaceDetectorHelper.kt` | The reverse-image candidate check now retries with a detector configured for smaller faces when the main detector finds none. Its thumbnail eligibility gates remain in place, but they are calibrated for provider thumbnails. |
| `FaceDetectorHelper.kt` | The capture fallback accepts selected images with an edge of at least 160 pixels, while the normal capture/enrollment path retains its stricter 480×360 requirement. It now also performs one final low-threshold single-face recovery pass only when the other detectors find nothing. |
| `CheckInContent.kt` | Changes the generic error heading to **Face could not be used**, so an image rejected for quality is not inaccurately presented as containing no face. |
| `FaceDetectorHelper.kt` | For the scan-input path, preserves group-photo protection but accepts one dominant face when any extra ML Kit detections are much smaller false positives. |
| `FaceSearchRepository.kt` | Adds TinEye to the non-Termux visual search and expands name-hint search coverage to LinkedIn, X, TikTok, Reddit, Pinterest, Threads, and YouTube in Social, Hyper, Aggressive, and Deep Crawl modes. |
| `CheckInViewModel.kt` | Merges repeated profile URLs and repeated image leads returned by multiple providers, retaining the best confidence state and alternate image URLs. |
| `MatchCard.kt` | Shows when multiple duplicate leads have been merged into one result card. |
| `CheckInViewModel.kt` | Adds a minimum 50% local embedding-similarity gate for manual-review leads and filters stock-image, illustration, vector, avatar, and generic-image sources before face review. |
| `CheckInContent.kt` | Collapses low-confidence review leads by default and explains that they are not identity matches. |
| `MatchCard.kt` | Replaces fabricated score-derived account labels with **Unnamed visual lead** and shows each displayed review lead's local similarity percentage. |
| `FreeFaceSearchHelper.kt` | Replaces the automatic Google Lens share handoff with a TinEye exact-image URL search. |
| `CheckInViewModel.kt` | Hosts the selected probe for the TinEye action and opens a direct TinEye search; opens TinEye's upload page if hosting is unavailable. |
| `MainActivity.kt`, `CheckInScreen.kt`, `FaceSearchConfirmScreen.kt`, `CheckInContent.kt` | Renames the fallback action and user-facing buttons from Google Lens to **TinEye Exact-Image Search**. |
| `CheckInViewModel.kt` | Adds a bounded fallback tier for real-face in-app candidates scoring 30–44% local similarity, while keeping stronger review, possible, and verified tiers separate. |
| `CheckInContent.kt`, `MatchCard.kt` | Shows ranked visual candidates automatically when no stronger tier exists and labels their local similarity clearly as non-identity-verified. |

## Test on the device

1. Stop or do not start the Termux helper.
2. Open the app and choose a clear single-face image from the gallery or camera.
3. Start the scan and confirm the log says that the app is using the in-app fallback.
4. Verify that visual results are based on the isolated face rather than the full source photo.
5. Test a clear face that is somewhat distant or small in the frame. It should reach the low-threshold recovery detector instead of immediately returning a false no-face result.
6. Test a clear, dominant single-face image that had previously shown the **Use a photo with only one visible face** error. The scan should continue when any extra detection is substantially smaller than the dominant face.
7. Open a result with a small but clear face thumbnail and confirm it is retained as a visual lead rather than rejected immediately as having no face.
8. Use a Social, Hyper, Aggressive, or Deep Crawl mode with an optional name/handle hint. Verify the scan log reports expanded social coverage and that repeated provider results appear as one card with a duplicate count rather than repeated cards.
9. Confirm that stock-image, illustration, vector, and generic-image results are omitted. Review leads that remain should be hidden until **Show low-confidence leads** is selected and should state their local similarity percentage.
10. Trigger a provider access challenge and select **Open TinEye Exact-Image Check**. Confirm that the app opens TinEye with the hosted probe URL rather than launching Google Lens.
11. With Termux stopped, run the same scan that previously produced no cards. Confirm the app displays up to five **Ranked visual candidates** when real face thumbnails fall below the stronger review threshold; these should remain clearly marked as not identity verified.

## Build note

Source-level validation completed successfully. A full Android build was not run in the packaging environment because no Android SDK is installed there; build and install the project in Android Studio or on a machine with a configured Android SDK.
