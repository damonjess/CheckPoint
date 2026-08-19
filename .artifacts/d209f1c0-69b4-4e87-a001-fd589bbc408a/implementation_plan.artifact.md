# Upgrade Termux OSINT Backend and Coordination

The Termux backend is currently underutilized, running only two engines sequentially. This plan upgrades the backend to a multi-engine parallel scraper and optimizes the Android app to avoid redundant work, resulting in both faster scans and higher-quality results.

## Proposed Changes

### [Component Name] face-search-service (Termux)

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)
- **Multi-Engine Support**: Add Yandex and TinEye to the `ENGINES` list. Yandex is critical for high-quality face matches.
- **Parallel Execution**: Change the Termux search loop to run up to 2 engines in parallel using `Promise.all` (limited concurrency) instead of sequential execution.
- **Enhanced Scraper**: Add automatic scrolling and better "wait for selector" logic to Puppeteer to extract deeper results and handle lazy loading.
- **Stealth Headers**: Inject more realistic User-Agent and platform headers to reduce the frequency of CAPTCHA challenges.

### [Component Name] network

#### [MODIFY] [FaceSearchRepository.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt)
- **Redundancy Filter**: Update `performFaceSearch` to accept a `skipEngines` parameter.
- **Dynamic Offloading**: Filter the local `WebViewScraper` jobs based on which engines the Termux backend is handling.

### [Component Name] ui

#### [MODIFY] [CheckInViewModel.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt)
- **Smart Orchestration**: When Termux is detected, configure the in-app `performFaceSearch` to skip Google, Bing, and Yandex, leaving them to the more robust Termux Puppeteer backend.
- **Prioritized Harvesting**: Ensure that results from the "Headless" (Termux) engines are processed first for name harvesting, as they tend to have better metadata.

## Verification Plan

### Automated Tests
- Build and start the updated Termux service.
- Verify that the `/api/ping` endpoint now reports the enhanced engine list.
- Run a scan and verify in the logs that `Yandex` and `TinEye` are being reported by the Termux backend.

### Manual Verification
- Perform an "ADULT" scan and confirm that the "Sherlock Console" shows Termux handling the core visual engines while the app concurrently handles the adult platform dorks.
- Confirm that the harvested name hint is more accurate due to better metadata extraction from Yandex/Puppeteer.
