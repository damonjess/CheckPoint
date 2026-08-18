# Outcome and Verification Flow Update

## What changed after the two test runs

The screenshots confirm that the helper itself is reachable in the Termux run and that the non-Termux route can retrieve visual candidates. The remaining issue was not a crash: it was that the app presented both outcomes as the same generic **No Results Found** screen, while only checking the first 12 raw candidates.

This update makes the result flow clearer and more useful.

| Test condition | Previous behavior | Updated behavior |
|---|---|---|
| **Termux running; provider asks for an access check** | The UI showed an ordinary no-results message after logging the challenge. | The UI says **Search Needs Your Action**, explains that the provider requested an access check, and offers **Open Photo in Google Lens** for the user to complete that step manually. |
| **Termux not running** | The UI showed “no visual matches” even after the app retrieved broad, unverified candidates. | The app ranks up to 24 candidates with usable profile/thumbnail signals, runs local face verification, and explains that no candidate passed the face match. |
| **Unrelated thumbnail or generic silhouette** | It could be shown as a low-confidence result before verification. | It is never displayed as a match unless a face is detected and its embedding passes the local verification threshold. |
| **No network** | A fake local match could appear. | The UI explains that local photo quality was checked but reverse-image search needs a network connection. |

## Files to replace for this update

Replace these project files in addition to the files from the previous runtime patch:

```text
app/src/main/java/com/yourcompany/facesearch/ui/CheckInUiState.kt
app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt
app/src/main/java/com/yourcompany/facesearch/ui/CheckInScreen.kt
app/src/main/java/com/yourcompany/facesearch/ui/components/CheckInContent.kt
```

Keep the previous replacements for `FaceDetectorHelper.kt`, `NativeFaceCropper.kt`, `FaceVerifier.kt`, `FaceSearchRepository.kt`, and the files under `face-search-service/`.

## Expected behavior after rebuilding

After installing this build, a blocked Google Lens request should **not** say that there are simply no results. It should name the situation as an access check and show the manual Lens action. When no Termux helper is present, the app should log that it is verifying the **best visual candidates**, usually up to 24 rather than the previous 12. It will then show only locally verified face matches.

> A web-result ranking is a lead, not an identity confirmation. The local face check intentionally rejects candidates that do not contain a usable matching face, even when the search provider returns a visually related image.

For the clearest results, use a recent, well-lit, front-facing photo with one visible face. ML Kit notes that focus and face orientation affect detection reliability and recommends input of at least 480×360 pixels with a face at least 100×100 pixels.[1]

## Reference

[1] [Google ML Kit — Detect faces with ML Kit on Android](https://developers.google.com/ml-kit/vision/face-detection/android)
