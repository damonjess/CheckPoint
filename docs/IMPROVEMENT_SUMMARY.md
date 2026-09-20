# CheckPoint - Improvements Summary

## Overview

This document summarizes all improvements made to the CheckPoint face-search/OSINT app to enhance its ability to find results and overall code quality.

---

## 1. Result-Finding Improvements

### 1.1 UsernameScanner - Expanded Platform Coverage (10 → 35 platforms)

**File:** `UsernameScanner.kt`

The direct username handle scanner previously checked only 10 social platforms. It now covers 35:

**Added platforms:**
- Threads, Bluesky, Mastodon, Snapchat, Quora
- Behance, Dev.to, SoundCloud, Spotify, Keybase
- YouTube, Flickr, Tumblr, VK, VSCO
- Patreon, Substack, GitLab, Stack Overflow
- HackerNews, Product Hunt, Dribbble
- OnlyFans, Fansly

**Impact:** Each new platform is checked via HEAD request for an active profile at the expected URL pattern. This dramatically increases the chance of finding a person's social media presence when a name or username hint is available.

### 1.2 DuckDuckGo OSINT Dorking - Expanded Domains (6 → 28 domains)

**File:** `FaceSearchRepository.kt`

The DuckDuckGo dorking query previously targeted only 6 social domains. It now covers 28:

**Added domains:**
- threads.net, bsky.app, mastodon.social, vk.com, tumblr.com
- flickr.com, pinterest.com, youtube.com, twitch.tv
- onlyfans.com, fansly.com, patreon.com, soundcloud.com
- spotify.com, behance.net, dribbble.com, keybase.io
- linktr.ee, vsco.co, substack.com, medium.com

**Impact:** Each domain generates a `site:domain "keyword"` DuckDuckGo search, so the app now casts a much wider net for social profile discovery.

### 1.3 Social Dorking - Expanded Sites (15 → 40+ sites)

**File:** `FaceSearchRepository.kt`

The Bing-based social dorking (`scrapeSocialDork`) previously covered ~15 sites. It now covers 40+:

**Added UK/regional directories:**
- yell.com, companieshouse.gov.uk, findmypast.co.uk

**Added social/creator platforms:**
- bsky.app, mastodon.social, youtube.com/@, soundcloud.com
- behance.net, dribbble.com, patreon.com, substack.com
- medium.com/@, keybase.io

**Added professional/tech platforms:**
- github.com, gitlab.com, stackoverflow.com/users, producthunt.com/@

### 1.4 Google Social Dorking Fallback

**File:** `WebViewScraper.kt`

Previously, `scrapeSocialDork` only used Bing for dork queries. If Bing returned zero results, the search stopped. Now, when Bing returns nothing, the app automatically falls back to Google search with the same query, significantly improving coverage when Bing doesn't index a particular profile.

### 1.5 WebView Scraping - Retry Logic

**File:** `WebViewScraper.kt`

Added a `scrapeEngineWithRetry` wrapper that retries each visual search engine (Sogou, Google, Bing, TinEye, Yandex) once if the first attempt returns zero results. This addresses intermittent failures from:
- Interstitial/consent pages blocking the first load
- Rate limiting from the search engine
- Network timeouts

### 1.6 WebView Extraction JS - Updated Selectors

**File:** `WebViewScraper.kt`

Updated the `VISUAL_EXTRACT_JS` and `DORK_EXTRACT_JS` with:

- **Google:** Added selectors for Google Lens visual match containers (`.vis-label`, `.yuRUbf`, `.G19kAf`, `.rQMQod`, `.IsZvec`, `.EF2BQc`, `.Vd970b`, `.R5H3j`, `.GQrDse`)
- **Bing:** Added `.newsitem` and `.b_algo` for web result extraction
- **Yandex:** Added `.Sites-Title a` and `.Other-Sites-Image a` for the newer Yandex layout. Also added `yandex.ru` hostname matching alongside `yandex.com`
- **Sogou:** Added `.img-result a` and `.pic-result a` selectors
- **TinEye:** Added `.match-item` and `.results-container a` selectors
- **Dork extraction:** Added Google result selectors (`.tF2Cxc`, `.yuRUbf`, `.MjjYud`, `h3 a`) and DuckDuckGo domain exclusion

### 1.7 DuckDuckGo Dorker - JSON API Fallback

**File:** `DuckDuckGoDorker.kt`

Rewrote the DuckDuckGo dorker with:
- Better HTML entity decoding (`&lt;`, `&gt;`)
- A JSON API fallback when HTML parsing returns zero results
- Improved User-Agent header

### 1.8 Image Hosting - More Reliable Options

**File:** `FreeImageHost.kt`

Added two additional image hosting providers:

- **Catbox.moe** - Very reliable, no API key required, permanent URLs
- **0x0.st** - Minimalist, reliable, no API key required

The upload chain is now: ImgBB → FreeImage.host → File.coffee → Catbox.moe → 0x0.st

**Impact:** If the primary hosts are down or rate-limited, the app has more fallbacks to ensure the probe image gets uploaded for reverse image search.

### 1.9 harvestSearchHints - Less Aggressive Filtering

**File:** `FaceSearchRepository.kt`

Improved the name extraction from visual match titles:
- Now strips "profile", "page", "account", "user", "official" from titles before name extraction
- Allows one stop word in 3+ word titles (previously rejected any title with a stop word)
- This means names like "John Image" or "Sarah Photo" are now properly extracted instead of being filtered out

### 1.10 SocialMediaDetector - More Platforms

**File:** `SocialMediaDetector.kt`

Added detection for:
- Threads, Bluesky (with `bluesky` keyword), Mastodon instances (fosstodon.org, mstdn.social, hachyderm.io)
- Dribbble, SoundCloud, Spotify, Keybase, Product Hunt
- Snapchat (with `snap.com`), Discord (with `discord.gg`, `discordapp.com`)
- Updated `isProfileUrl` to recognize `/add/`, `/channel/`, `/c/` URL patterns
- Updated `extractUsername` to handle 25+ platforms

### 1.11 FreeSocialFinder - More Platform URLs

**File:** `FreeSocialFinder.kt`

Expanded from 19 URL patterns to 40+, adding Threads, Bluesky, Mastodon, Snapchat, Quora, Behance, Dev.to, SoundCloud, Keybase, VSCO, Patreon, Substack, GitLab, Stack Overflow, Product Hunt, Dribbble, OnlyFans, Fansly.

### 1.12 CheckInViewModel - Expanded Social Hosts

**File:** `CheckInViewModel.kt`

The `prioritizeCandidates` social hosts list expanded from 14 to 36 entries, ensuring results from new platforms get proper priority scoring.

---

## 2. Server.js (Termux Backend) Improvements

### 2.1 Added Yandex RU Engine

Added `yandex.ru` as a separate engine alongside `yandex.com`, since the Russian Yandex domain sometimes returns different/more results.

### 2.2 Improved Scrolling for Lazy Loading

Replaced the single scroll pass with multiple progressive scroll passes:
- Scroll 400px → wait 1.5s
- Scroll 600px more → wait 1.5s
- Scroll to 50% of page → wait 1.5s
- Scroll to bottom → wait 2s
- Scroll back to top → wait 1s

This ensures lazy-loaded thumbnails on long result pages are captured.

### 2.3 Retry on Empty Results

If the first extraction pass returns zero results, the server now:
1. Reloads the page
2. Dismisses consent banners again
3. Scrolls through the page
4. Attempts extraction again

### 2.4 Better Consent Banner Dismissal

Added more consent banner selectors: `.js-accept`, `.consent-accept`, `.cookie-consent-accept`, `[data-testid="cookie-policy-dialog-accept-button"]`, `div[role="dialog"] button`

### 2.5 Better Image Source Extraction

Updated the UNIVERSAL_EXTRACT_JS to:
- Unwrap Google `imgres?imgurl=` redirects
- Exclude `duckduckgo.com` from internal links
- Search `div, li, article, figure` for images (not just `div`)
- Check `data-lazy-src` attribute
- Filter out "placeholder" thumbnails
- Exclude "log in" link text
- Increased title truncation from 100 to 120 chars

### 2.6 Expanded Social Platform Detection

Updated the `isSocial` regex to include 24+ social platforms (was 11).

---

## 3. Files Modified

| File | Changes |
|------|---------|
| `UsernameScanner.kt` | 10 → 35 platforms |
| `DuckDuckGoDorker.kt` | Rewritten with JSON API fallback |
| `FaceSearchRepository.kt` | 28 DDG domains, 40+ social dork sites, improved harvestSearchHints |
| `WebViewScraper.kt` | Retry logic, Google fallback, updated selectors |
| `FreeImageHost.kt` | Added Catbox.moe and 0x0.st |
| `SocialMediaDetector.kt` | 15+ new platforms, expanded extractUsername |
| `FreeSocialFinder.kt` | 40+ URL patterns |
| `CheckInViewModel.kt` | 36 social hosts in prioritizeCandidates |
| `face-search-service/server.js` | Yandex RU, retry, better scrolling, better extraction |

---

## 4. Additional Recommendations (Not Yet Implemented)

These are further improvements that could be made:

### 4.1 PimEyes Integration
PimEyes is a dedicated face search engine that could significantly improve face matching results. Consider adding it as an optional engine.

### 4.2 Search4faces Integration
Search4faces is another face search service that searches VK, OK.ru, TikTok, and ClubHouse.

### 4.3 Result Caching
Cache search results to avoid redundant searches when the same image is searched again.

### 4.4 Parallel Social Dorking
The social dorking in FaceSearchRepository currently runs sequentially via `scrapeSocialDork`. Converting these to parallel `async` calls would significantly speed up the OSINT phase.

### 4.5 User-Agent Rotation
Rotate User-Agent strings to reduce the chance of being blocked by search engines.

### 4.6 Proxy Support
Add optional proxy support for the WebView scrapers to avoid IP-based rate limiting.

### 4.7 Better Thumbnail Loading
The current `loadThumbnailBitmap` has no timeout. Adding a timeout would prevent the app from hanging on slow-loading thumbnails.

### 4.8 Export Results
Add the ability to export search results to a file (PDF, CSV, or JSON) for later reference.

### 4.9 Search History
Persist search history across sessions so users can review past searches.

### 4.10 Code Cleanup
- Remove unused files (many `.md` documentation files, `.artifacts/` directory)
- The `FreeReverseImageSearch.kt` class appears to be unused - it opens external browser intents but is not called from the main pipeline
- The `FreeSocialFinder` class appears to be unused in the main pipeline
- `FreeFaceSearchHelper` is essentially a stub that only saves to cache
