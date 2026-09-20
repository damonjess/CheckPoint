# Local Reverse-Image Helper (Enhanced)

This enhanced Node.js helper runs on the same device as the Android app. It uses **Puppeteer Stealth** and **Incognito Browser Contexts** to query visual-search engines with improved resilience against bot detection. It also provides real-time progress updates via WebSockets.

## New Features

- **Stealth Mode**: Uses `puppeteer-extra-plugin-stealth` to bypass basic bot detection.
- **Incognito Isolation**: Each engine runs in a separate incognito context for better security and efficiency.
- **Real-Time Progress**: The Android app now receives live progress logs (e.g., "Running Google Lens...") via WebSockets.
- **Dynamic Concurrency**: Automatically adjusts the number of parallel engines based on available system memory (RAM).
- **DNS Diagnostics**: Startup check to ensure Termux has valid internet connectivity.
- **Pinterest Support**: Added Pinterest Visual Search to the engine list.

## Runtime behavior

| Environment | Behavior |
|---|---|
| **Termux** | Detects Termux and uses dynamic concurrency (usually 1-2 engines) to save RAM. |
| **Desktop Linux** | Runs up to 4 engines concurrently for maximum speed. |
| **Blocked engine** | Marks engine as blocked, cools down for 15 minutes, and notifies the app. |
| **Network exposure** | Listens only on `127.0.0.1:3000`. |

## Termux setup

Install Node.js, Chromium, and dependencies:

```bash
pkg update
pkg install nodejs-lts chromium
cd face-search-service
export PUPPETEER_SKIP_DOWNLOAD=true
npm install
# Startup check: ensure DNS is working
# If ping google.com fails, run:
# echo "nameserver 8.8.8.8" > /data/data/com.termux/files/usr/etc/resolv.conf

CHROMIUM_PATH="$(command -v chromium || command -v chromium-browser)" npm start
```

## API

`POST /api/search` (Standard OSINT precision flow).
`GET /api/ping` (Diagnostic endpoint).
`WS /` (WebSocket for real-time progress).

The helper returns public result links. The Android app automatically applies local face verification to these candidates.
