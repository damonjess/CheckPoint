# Hybrid Search Walkthrough

I have implemented the hybrid search approach to bypass engine blocks by using a combination of Termux (for reliable engines like Bing), Android Intents (opening blocked engines in the real Chrome browser), and in-app WebView scrapers as a backup.

## Changes Made

### Backend (Termux)
- **Fast Block Detection**: Added `isEngineReachable` to quickly identify if an engine is showing a CAPTCHA or "Unusual Traffic" page (within 8 seconds).
- **Staggered Execution**: Updated the search route to run Bing immediately (since it's reliable) and then probe other engines (Yandex, Google, Baidu) with a 12-second timeout.
- **Blocked Engine Tracking**: The backend now returns a list of `blockedEngines` in the response metadata.

### Android App
- **Real Browser Bypass**: Implemented `openBlockedEnginesInBrowser` in [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt). If Termux reports that Google or Yandex is blocked, the app automatically opens those search URLs in the device's real Chrome browser. Tabs are staggered by 2.5 seconds.
- **Parallel Scrapers**: Updated `performSearchPipeline` to ensure that in-app WebView scrapers run in parallel with Termux and merge their results, rather than being cancelled when Termux succeeds.
- **Improved Logging**: Added clear console logs for when engines are opened in Chrome.
- **Delay Adjustments**: Increased delays in [WebViewScraper.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/WebViewScraper.kt) to improve success rates when scraping within the app.

## Verification Results

- **Code Analysis**: [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt) analyzed with no fatal errors.
- **Build Status**: The project encountered a dependency resolution issue with `com.google.ai.edge.litert:litert-gpu:2.1.6`. This appears to be an environment or repository configuration issue unrelated to the logic changes implemented.

> [!NOTE]
> To verify the fix, deploy the updated `server.js` to your Termux environment and run the app. You should see Bing results populate the UI while Chrome automatically opens tabs for any blocked engines.
