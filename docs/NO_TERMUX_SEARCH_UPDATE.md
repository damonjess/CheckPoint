# No-Termux Face Search (Hardened In-App Path)

## Goal

Let a user run a face search of **their own photo** with Termux **not running**
and still see their image (or similar images) found on the open web, displayed
inside the app.

## What was broken

When Termux was offline the app fell back to its in-app `WebViewScraper`, which
loads Google Lens / Bing Visual / Yandex / TinEye reverse-image pages inside an
Android System `WebView` and scrapes the result links. This returned **0
candidates** for three reasons:

1. **Google wraps Lens result links in `google.com/goto?url=…` redirects.** The
   old extractor skipped any URL containing `google.`, so every Lens result was
   discarded.
2. The per-engine CSS selectors (`a.V6bBh`, `.imgpt a`, `.CbirItem-Title a`,
   …) were stale — when providers renamed their classes, extraction silently
   returned nothing.
3. Consent / cookie interstitials (Google's "Before you continue", Bing's
   cookie banner, Yandex's region dialog) were only dismissed after the page
   finished loading, by which time the results DOM often had not rendered.

## What changed

### `network/WebViewScraper.kt`

- **Redirect unwrapping.** A new `unwrap()` helper decodes the real target
  URL from Google `/url?q=`, `/goto?url=`, `/imgres?imgurl=`, Bing `imgurl=`,
  and Yandex `url=` / `rurl=` wrappers. Unwrapped links are kept instead of
  being thrown away as "internal".
- **Selector-resilient extraction.** Engine-specific selectors run first, then
  — if fewer than 3 candidates were found — a **generic sweep of every
  image-bearing anchor** on the page runs as a fallback. This means a provider
  renaming its CSS classes no longer produces zero results.
- **Consent dismissal on page start.** `onPageStarted` now runs a `CONSENT_JS`
  snippet that clicks common EU/cookie/age-gate banners before results render,
  with another click after `onPageFinished`.
- **One reload-retry.** If an engine returns no candidates after its four
  extraction passes, the page is reloaded once (handles one-time interstitials).
- **Longer timeout** (16 s → 30 s) with clearer per-engine logging.

### `network/FaceSearchRepository.kt`

- The in-app visual engines now **always run in no-Termux mode**
  (`allResults.size < 5 || skipTermux`), instead of being skipped once another
  source had 5 hits.

### `ui/CheckInViewModel.kt`

- The zero-candidate message for the no-Termux path now explains that
  Google/Bing/Yandex sometimes block automated lookups and suggests a clearer
  photo or the optional Termux/cloud helper.

## How results are shown

Candidates flow through the **existing** pipeline unchanged: each candidate's
thumbnail is downloaded, ML Kit checks it contains a single visible face,
`FaceVerifier` compares the face embedding to the source photo, and results are
displayed in-app as Verified / Possible / Review-lead / Visual-candidate cards
(see `CheckInViewModel.performSearchPipeline` → `reviewCandidates`).

## Honest limits

A phone app alone cannot reliably search the public web's image corpus — it
needs either an API key or a small backend. The in-app WebView path is now
**best-effort and free**. Google Lens, Bing, and Yandex periodically show
anti-bot / consent challenges to automated browsers; when they do, an engine may
still return 0 candidates. For guaranteed-reliable no-Termux search, either:

- set a `SERP_API_KEY` (Google Lens via SerpApi — already wired in
  `performSerpApiSearch`), or
- deploy `face-search-service` to a cloud host and point the app at it
  (planned `FACE_SEARCH_BACKEND_URL`).

## Build & test

The sandbox has no Android SDK, so the APK cannot be compiled here. From a
machine with Android Studio / Platform 36 installed:

```bash
cd CheckPoint
./gradlew :app:assembleDebug
```

Then install on a device, take a photo, and with Termux **not** running, observe
the console logs: each engine should now report either `N candidate(s)` or
`no candidates (provider may have shown a verification page)`.
