# Termux Fallback Update

## Problem shown in the screenshots

The Termux helper was reporting a successful request with **zero usable candidates** after Google Lens or Bing requested an access check. Because the response was marked successful, the Android app treated it as final and did not wait for its more useful built-in visual-search flow.

This update fixes both parts of that behavior.

| Component | Updated behavior |
|---|---|
| **Termux `server.js`** | Disables automated Android Chromium searches for Bing and Google Lens. It no longer repeatedly opens those providers, triggers access challenges, or starts a ten-minute cooldown. It returns an immediate zero-candidate response with a fallback recommendation. |
| **Android `CheckInViewModel.kt`** | Treats a zero-candidate Termux response as a fallback condition, waits for the already-started in-app visual search, and continues through the same candidate filtering and face verification used when Termux is offline. |

## Files to replace

Copy these two files from the latest archive:

```text
app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt
face-search-service/server.js
```

## Update and restart Termux

Copy the new `server.js` to the phone and restart the helper:

```powershell
# Windows / Android Studio terminal
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push face-search-service/server.js /sdcard/Download/server.js
```

```bash
# Termux
cp ~/face-search-service/server.js ~/face-search-service/server.js.bak
cp /sdcard/Download/server.js ~/face-search-service/server.js
cd ~/face-search-service
node --check server.js
# Stop the old service with Ctrl+C if it is running, then:
npm start
```

Then replace `CheckInViewModel.kt` in Android Studio, sync Gradle, rebuild, and install the app.

## Expected next test

With Termux running, the terminal should log:

```text
Termux browser engines are disabled; the Android app will use its in-app visual-search fallback.
```

The app log should contain:

```text
Termux returned no usable candidates; switching to the in-app visual-search flow.
```

This is expected. It means leaving Termux running will no longer make the results worse or repeatedly hit Google Lens. Manual Lens remains available through the app button if a provider requests an access check.
