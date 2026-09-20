# Unverified Visual Leads Update

## Why candidates were not displayed

When Termux was not running, the app received visual-search candidates but then applied the local face-verification filter. In the previous build, **every candidate was hidden** if none of its thumbnails passed that comparison. That protected against false identification, but it also made useful visual-search leads disappear.

This update keeps the two concepts separate.

| Result type | Display behavior |
|---|---|
| **Face-verified match** | Displayed under **Face-verified matches**, with the normal confidence score and verified source marker. |
| **Unverified visual lead** | Displayed under **Unverified visual leads**, with an amber `LEAD` badge and an explicit statement that it did not pass local face verification. It is not given a face-match percentage. |
| **No candidates at all** | Displays the no-match or access-check screen as before. |

The app now ranks and checks up to 24 candidates. If none verify, it still displays the returned candidates as leads rather than silently hiding them.

> A visual-search lead is a link or image returned by a search provider. It is **not** a confirmed identity match. Only the local face-verification section is presented as a verified match.

## Files to copy for this update

Copy these files from the latest archive into the same paths in your project:

```text
app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt
app/src/main/java/com/yourcompany/facesearch/ui/components/CheckInContent.kt
app/src/main/java/com/yourcompany/facesearch/ui/components/MatchCard.kt
app/src/main/java/com/yourcompany/facesearch/ui/models/WebMatchDisplay.kt
```

This update builds on the earlier project revisions. Keep the latest versions of these files from the same archive as well:

```text
app/src/main/java/com/yourcompany/facesearch/ui/CheckInUiState.kt
app/src/main/java/com/yourcompany/facesearch/ui/CheckInScreen.kt
app/src/main/java/com/yourcompany/facesearch/vision/FaceDetectorHelper.kt
app/src/main/java/com/yourcompany/facesearch/vision/NativeFaceCropper.kt
app/src/main/java/com/yourcompany/facesearch/vision/FaceVerifier.kt
app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt
face-search-service/server.js
face-search-service/package.json
face-search-service/.env.example
app/build.gradle.kts
```

Delete the obsolete `vision/GemmaAnalyzer.kt` and `vision/ImageEnhancer.kt` files if they remain in your project, then rebuild in Android Studio.

## Expected result without Termux

When a visual provider returns candidates but none verify, the app should display a results page headed **visual leads**, each card carrying an amber `LEAD` tag. The cards can be opened manually, while the UI continues to make clear that they are not identity-confirmed face matches.
