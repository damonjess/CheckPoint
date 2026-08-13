# Hybrid Search Implementation Plan

Implement a hybrid search approach where Termux handles reliable engines (Bing), detects blocks in others (Google, Yandex, Baidu) quickly, and suggests opening those blocked engines in the user's real Chrome browser. Also, run in-app WebView scrapers in parallel as a backup.

## User Review Required

> [!IMPORTANT]
> The app will now automatically open Chrome tabs if engines are blocked on the Termux side. This provides a better success rate since the real browser has cookies and a legitimate fingerprint.

## Proposed Changes

### Backend (Termux)

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)
- Implement `isEngineReachable` for fast block detection.
- Update the `/api/search` route to run Bing with full time and others with fast-fail detection (12s timeout).
- Track and return `blockedEngines` in the metadata.

---

### Android App

#### [MODIFY] [ServerSearchResponse.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/model/ServerSearchResponse.kt)
- Add `blockedEngines: List<String>?` to the `SearchMeta` data class to capture blocked engine names from the backend.

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- Add `openBlockedEnginesInBrowser` to launch Chrome intents for blocked search engines.
- Update `performSearchPipeline` to:
    - Call `openBlockedEnginesInBrowser` when Termux reports blocked engines.
    - Ensure `webDeferred` (WebView backup) always runs in parallel.
    - Stagger Chrome tab opening to avoid overwhelming the device.

#### [MODIFY] [WebViewScraper.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/WebViewScraper.kt)
- Increase `delayMs` for Yandex, Bing, Google, and Baidu to improve success rates in the in-app WebView.

## Verification Plan

### Automated Tests
- N/A (Manual verification is preferred for UI/Intent interactions and Puppeteer behavior).

### Manual Verification
- Deploy the updated `server.js` to Termux.
- Build and run the Android app.
- Perform a search.
- Verify that Bing results appear in the app.
- Verify that Chrome opens automatically for Yandex/Google/Baidu if they are blocked on the server.
- Verify that WebView backup results are merged if any are found.
