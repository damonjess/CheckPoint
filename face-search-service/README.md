# Local Reverse-Image Helper

This optional Node.js helper runs on the same device as the Android app. It accepts an image URL, queries publicly accessible visual-search pages in a standard browser runtime, and reports any returned links. It does **not** alter face images, attempt to bypass access challenges, or expose a network-facing server. Use it only with your own photograph or one you are authorized to search.

## Runtime behavior

| Environment | Behavior |
|---|---|
| **Termux** | Detects the Termux runtime and runs engines sequentially to reduce memory pressure. |
| **Desktop Linux** | Runs available engines concurrently for faster completion. |
| **Blocked engine** | Records the challenge, cools that engine down, and returns control to the Android share flow rather than retrying around the block. |
| **Network exposure** | Listens only on `127.0.0.1:3000`. |

## Termux setup

Install Node.js and Chromium, then install the service dependencies without downloading an extra Chromium build:

```bash
pkg update
pkg install nodejs-lts chromium
cd face-search-service
export PUPPETEER_SKIP_DOWNLOAD=true
npm install
CHROMIUM_PATH="$(command -v chromium || command -v chromium-browser)" npm start
```

Confirm that the service is reachable from the device:

```bash
curl http://127.0.0.1:3000/api/ping
```

The reply should include `"status":"pong"` and `"runtime":"termux"`. Keep the process running while you use the Android app. The app probes `127.0.0.1:3000` first and automatically falls back to its regular in-app share/search path if the helper is unavailable.

## Desktop Linux setup

Install Node.js 20 or later and Chromium or Google Chrome. Then run:

```bash
cd face-search-service
npm install
CHROMIUM_PATH="$(command -v chromium || command -v google-chrome)" npm start
```

## API

`POST /api/search` accepts a public image URL and an optional identifier that you own or are authorized to use. The supported mode is `PRECISION`.

```json
{
  "imageUrl": "https://example.com/your-photo.jpg",
  "keywordHint": "optional public handle or name",
  "searchMode": "PRECISION"
}
```

The helper returns public result links only. Treat them as leads, not identity confirmation: the Android app applies local face verification to thumbnails when available.
