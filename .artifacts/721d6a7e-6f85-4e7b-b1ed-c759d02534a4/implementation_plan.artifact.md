# Implementation Plan - Fix App Results When Termux is Not Running

The user reports that the app fails to show results when Termux is not running. Analysis shows that the app's current filtering logic is very aggressive, especially when relying on in-app WebView scrapers which often return lower-quality thumbnails compared to the Termux backend. Additionally, some logic skips visual engines if the image probe is hosted locally (e.g., when free hosting fails).

## User Review Required

> [!IMPORTANT]
> This change will make the app more permissive in showing "Review Leads" and "Visual Candidates" when Termux is not available. This may increase the number of false positives shown to the user, but ensures the app actually provides results to investigate.

## Proposed Changes

### [Component] Core Search Logic & Filtering

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Lower similarity thresholds to be more permissive, especially when Termux is absent.
- Ensure that even "unverified" leads are shown if they contain a face.
- Improve logging to inform the user about the status of the Termux backend.
- Adjust `performSearchPipeline` to handle cases where Termux is missing but network is available.

#### [MODIFY] [FaceSearchRepository.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt)
- Refine the condition for skipping visual engines. If Termux is not running, we should still attempt dorking and other keyword-based searches even if the visual probe is local.
- Add better error handling/logging for when Termux is expected but not found.

---

### [Component] UI & Feedback

#### [MODIFY] [CheckInUiState.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInUiState.kt)
- Add a `termuxAvailable` flag or similar to `Success` and `NoMatch` states to provide contextual tips to the user.

#### [MODIFY] [CheckInScreen.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInScreen.kt)
- Display a small warning or tip if Termux is not running, suggesting it for better results.

## Verification Plan

### Automated Tests
- N/A (Project lacks a robust testing harness for network/vision components, will rely on manual verification).

### Manual Verification
1.  **Termux Running**: Verify the app still works correctly with Termux and shows high-confidence matches.
2.  **Termux Stopped**:
    *   Perform a search with a clear face.
    *   Verify that "Review Leads" or "Visual Candidates" are shown even if their similarity score is lower than the previous strict threshold.
    *   Verify that the "Sherlock OSINT Console" logs clearly state that Termux is missing and it's falling back to in-app scanning.
3.  **No Network + Termux Stopped**: Verify it shows the "No network" local analysis message correctly.
