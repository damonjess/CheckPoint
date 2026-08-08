# Fix Termux 0 Results and Performance Lag

The user is experiencing a "0 results" issue in Termux while the WebView fallback works. Logcat analysis shows a 60-second delay before Termux returns 0 results, which corresponds to sequential timeouts of multiple scraping engines in `server.js`. A `ui_state` inspection of Termux reveals DNS lookup failures (`connection refused` on `[::1]:53`), preventing the scraper from reaching search engines.

## Proposed Changes

### 1. [face-search-service](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service)

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)
- **Parallelize Engines**: Run Yandex, Bing, Baidu, and Google Master in parallel using `Promise.all` to reduce total timeout duration from ~60s to ~30s.
- **Diagnostic Logging**: Log specific navigation and extraction errors to the Termux console so the user can see *why* an engine failed (e.g., DNS error, Captcha).
- **DNS Check**: Add a startup check to verify internet connectivity and DNS resolution in Termux.

### 2. [app](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app)

#### [MODIFY] [FaceSearchRepository.kt](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt)
- **Improve Error Reporting**: Log more details about Termux connectivity to help debug future issues.

## User Review Required

> [!IMPORTANT]
> **DNS Configuration in Termux:** The `ui_state` shows that Termux is trying to use a local IPv6 DNS resolver (`[::1]:53`) which is refusing connections. You likely need to configure Termux to use a reliable DNS (like Google 8.8.8.8) or fix the system DNS passthrough.
>
> **Run this command in Termux to fix DNS:**
> ```bash
> echo "nameserver 8.8.8.8" > /data/data/com.termux/files/usr/etc/resolv.conf
> ```

## Verification Plan

### Automated Tests
- I will verify the `server.js` logic by checking the `Promise.all` implementation.
- I will check the `server.js` startup to ensure the diagnostic logs are added.

### Manual Verification
- Ask the user to run the DNS fix command in Termux.
- Ask the user to restart the server and try a search again.
- Verify that Termux logs show "🔍 Engine..." and any errors if they occur.
