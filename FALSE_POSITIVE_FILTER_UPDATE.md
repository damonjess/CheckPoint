# Product and Body-Only Result Filter Update

## Issue confirmed by the test screenshots

The visual-search provider returned pictures of navy shirts, clothing listings, and people whose faces were cropped out. These were not face matches. They were likely returned because the provider weighted the shirt colour and photo composition in addition to the uploaded face.

The app already labelled them as unverified, but they were still not useful face-search leads. This update removes them before the results page is built.

| Filter stage | What is now excluded |
|---|---|
| **Metadata screening** | Results whose title, URL, or source contains an explicit clothing or shopping term such as `shirt`, `clothing`, `apparel`, `shop`, `product`, or known marketplace names. The matching uses word boundaries to avoid accidental exclusions. |
| **On-device face-presence gate** | A thumbnail must contain exactly one face with a visible bounding box. Product images, body-only photos, silhouettes, icons, group photographs, and no-face thumbnails are discarded. |
| **Local face verification** | A face-bearing thumbnail is shown as a lead only when it does not match; it is elevated to a face-verified match only if the embedding comparison passes the existing conservative threshold. |

## Files to replace

Copy only these two files from the latest archive, overwriting the matching files in Android Studio:

```text
app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt
app/src/main/java/com/yourcompany/facesearch/vision/FaceDetectorHelper.kt
```

No Termux changes are required for this update. Do not replace `server.js`.

## Expected next result

After rebuilding, the product cards shown in the screenshots should not appear as visual leads. The diagnostic log will report how many product, body-only, group, or no-face thumbnails were excluded. If no usable faces remain, the app will explain why instead of displaying clothing or generic product cards.

> The app can reduce irrelevant visual-search candidates, but it cannot guarantee that a public reverse-image service will have indexed a particular face. A no-match result therefore means that no locally verified or face-bearing candidate was available from that search response, not that the person has no online photos.
