# Runtime Fix Notes: Termux Challenges and False Matches

## What the screenshots show

The screenshots show two independent problems. First, the Termux helper was connecting to a configured Browserless cloud browser and then encountering access challenges or unstable frames in external visual-search pages. The resulting timeouts caused the Android app to fall back to other raw search results.

Second, those raw engine candidates were displayed before local facial verification had completed. This is why the app could show a generic silhouette at **7%** or an unrelated photo at **40%**. Those percentages reflected a search-engine/UI score, not a confirmed face match.

## What this patch changes

| Problem | Fix |
|---|---|
| `Connecting to Browserless.io…` | Browserless and proxy configuration are removed from the helper. It now uses only the Chromium installed on the same Termux device. |
| Long retries and detached-frame loops | Each engine has one bounded request. It reports a challenge or error promptly, records the engine status, and returns control to the app. |
| App treats a blocked helper as a generic timeout | The helper returns `meta.blockedEngines`; the Android repository preserves that status. |
| Unrelated thumbnails appear in the results list | Engine candidates are now held back. A result is shown only when its thumbnail contains a detectable face that passes the local `0.68` embedding-similarity threshold. |
| Placeholder “Local Offline Match” | Removed. Offline image-quality analysis now correctly reports that web search needs internet access. |

> No automated client can reliably or appropriately bypass an external service’s access challenge. When an engine asks for a challenge, use the app’s **Open in Lens** action yourself or choose another permitted visual-search service. The patch makes this outcome explicit instead of letting stalled requests create misleading results.

## Files to replace

Replace these files in the project:

```text
face-search-service/server.js
face-search-service/package.json
face-search-service/.env.example
app/src/main/java/com/yourcompany/facesearch/vision/FaceVerifier.kt
app/src/main/java/com/yourcompany/facesearch/ui/CheckInViewModel.kt
app/src/main/java/com/yourcompany/facesearch/network/FaceSearchRepository.kt
```

This patch builds on the prior face-detection update. Keep the previously supplied `FaceDetectorHelper.kt` and `NativeFaceCropper.kt` replacements, which provide the capture quality checks and landmark-aligned face crop.

## Termux redeployment

If you see a `MODULE_NOT_FOUND` error, your Termux environment is missing the system libraries required for image processing. Follow these steps exactly:

1. **Install System Dependencies:**
   ```bash
   pkg update && pkg upgrade -y
   pkg install -y build-essential python git
   pkg install -y libcairo libpango libjpeg-turbo libpng libgif-static librsvg
   pkg install -y chromium
   ```

2. **Clean and Reinstall Node Modules:**
   ```bash
   cd ~/face-search-service
   rm -rf node_modules package-lock.json
   export PUPPETEER_SKIP_DOWNLOAD=true
   npm install
   ```

3. **Configure and Start:**
   ```bash
   cat > .env <<'EOF'
   CHROMIUM_PATH=/data/data/com.termux/files/usr/bin/chromium-browser
   PORT=3000
   EOF
   npm start
   ```

Use the actual Chromium path printed by `command -v chromium-browser` if it differs. A successful startup reports:

```text
Local helper running on http://0.0.0.0:3000
[Biometrics] Face-api models loaded successfully.
```

## Expected app behavior

The app should now either show a small set of results marked with a check from local verification, or say that **no locally verified face match** was found. That is intentional: no result is better than presenting unrelated people as a match.

The initial photo also continues to be checked for one face, adequate face size, lighting, focus, head pose, and eye openness before any external search begins. Google’s ML Kit documentation notes that face recognition input should generally be at least 480×360 pixels with a face of at least 100×100 pixels, and that focus and orientation affect detection reliability.[1]

## References

[1] [Google ML Kit — Detect faces with ML Kit on Android](https://developers.google.com/ml-kit/vision/face-detection/android)
