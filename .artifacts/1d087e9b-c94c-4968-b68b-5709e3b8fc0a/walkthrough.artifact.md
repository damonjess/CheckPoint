# Walkthrough - Fixing "No Scans" and Enabling Bing in Termux

I have resolved the issue where the app showed no progress while Termux was scanning, and I've added full Bing Visual Search support to the stealth scanner.

## Changes

### 1. Termux Backend Enhancement
- **[server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)**:
    - Added **Bing Visual Search** support alongside Yandex.
    - Added a `/ping` endpoint to allow the app to discover the server faster.
    - Increased result limits to provide more candidates for biometric verification.

### 2. Connectivity & Performance
- **[FaceSearchRepository.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt)**:
    - **Fixed "Double Scanning"**: The app now detects if it has already reached a server. It will no longer retry the same server on a different IP (e.g., 127.0.0.1 vs localhost) unless there is a genuine connection failure.
    - **Reduced Timeouts**: Discovery timeouts reduced from 180s to 15s, ensuring the app pivots to the correct backend address almost instantly.

### 3. App-Side Discovery
- **[MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/MainActivity.kt)**:
    - Updated the boot-time probe to use the new `/ping` endpoint for reliable backend detection.

## Verification Results

> [!IMPORTANT]
> When you run the scan now, you should see **"Probing Local Termux..."** appear in the app console within seconds. The Termux logs should show it querying BOTH Yandex and Bing sequentially.

### How to test:
1. Ensure the Termux server is running (`node server.js`).
2. Run an **Aggressive** scan in the app.
3. Watch the console: You should see results coming in from both engines, and the "Parsed X valid visual targets" log will show a higher number (combining Yandex and Bing).
