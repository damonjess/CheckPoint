# Fix Bot Blockage in Face Search Scraper

The goal is to reduce "bot challenge" blockages from Bing, DuckDuckGo, and Google when running dorking searches in the Termux environment.

## User Review Required

> [!IMPORTANT]
> Scraping search engines is inherently fragile. While these changes will improve stealth, search engines may still block requests if they originate from an IP address with a low reputation (e.g., a shared mobile data IP) or if the volume of searches is too high.

## Proposed Changes

### Scraper Service (`face-search-service/server.js`)

#### [MODIFY] [server.js](file:///C:/Users/Damon/AndroidStudioProjects/CheckPoint/face-search-service/server.js)

1.  **Enhance Stealth Arguments**: Add more Chromium flags to the `puppeteer.launch` call to disable features that search engines use for bot detection (e.g., `IsolateOrigins`, `site-per-process`).
2.  **Per-Request User-Agent**: Randomize the User-Agent for every search engine attempt rather than just once at browser launch.
3.  **Referrer Spoofing**: Set the `Referer` header to the search engine's home page to make the search look like a natural navigation from the home page.
4.  **Human Behavior Jitter**:
    *   Increase the randomness of delays between searches.
    *   Add a "pre-check" delay to allow search engines to run their bot-detection scripts before we verify if we are blocked.
    *   Add more varied mouse movements and scrolling behavior.
5.  **Improved Block Detection**: Update the "blocked by bot challenge" check to include Cloudflare-specific patterns.

## Verification Plan

### Manual Verification
1.  Run the search service in Termux.
2.  Initiate a "Deep Search" from the CheckPoint app.
3.  Monitor the Termux console to see if the "blocked by bot challenge" messages decrease and if valid results are found for Bing and DuckDuckGo.
