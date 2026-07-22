# Implementation Plan - Optimize FREE Search Mode

The user wants to optimize the `SearchMode.FREE` flow to skip any "upload" steps and just open browser tabs for manual search. While the current `searchMyPhoto` function only saves images locally, the user specifically requested a `searchMyPhotoDirect` method that likely avoids even the local preparation and sharing intent, focusing solely on opening the search engine URLs.

## Proposed Changes

### [Component Name] Search Helpers

#### [NEW] `searchMyPhotoDirect` in [FreeFaceSearchHelper.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/vision/FreeFaceSearchHelper.kt)
- Add a new function `searchMyPhotoDirect(bitmap: Bitmap, nameHint: String?)`.
- This function will open the search engine URLs (Google Lens, PimEyes, Bing, Yandex) sequentially in the browser without attempting to share the local image URI.

### [Component Name] View Model

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Update `onConfirmFreeSearch` to explicitly handle `SearchMode.FREE`.
- Maintain the `isSearching` flag protection to prevent double triggers.
- Call `freeSearch.searchMyPhotoDirect` when in `FREE` mode.

## Verification Plan

### Manual Verification
- Deploy the app.
- Select `FREE` mode.
- Click the search button.
- Verify that the browser opens the search engine tabs sequentially.
- Verify that NO "Share" dialog appears (unlike the standard `FREE` mode).
- Verify that `isSearching` flag correctly prevents double clicks.
