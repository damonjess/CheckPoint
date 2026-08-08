# Face Search Service (Scraper v4)

A robust, stealthy image search and social dorking service built with Node.js and Puppeteer.

## Features
- **Multi-Engine Search**: Yandex, Bing Visual, Google Lens, TinEye, Baidu, and PimEyes Leads.
- **Anti-Scraping Measures**: 
  - Randomized Jitter (Delays).
  - Human Interaction Simulation (Mouse moves/Randomized scroll).
  - User-Agent Rotation.
  - Ad/Tracker Blocking.
- **Proxy Support**: Built-in support for rotating residential proxies with authentication.
- **Resilience**: Automatic engine retries and global error handling.

## Setup
1. **Install Dependencies**:
   ```bash
   npm install
   ```
2. **Configure Environment**:
   Create a `.env` file based on `.env.example`:
   ```text
   PROXY_URL=http://user:pass@host:port
   ```
3. **Start Service**:
   ```bash
   npm start
   ```

## API Usage
**Endpoint**: `POST /api/search`
**Body**:
```json
{
  "imageUrl": "https://example.com/image.jpg",
  "keywordHint": "Optional Name",
  "searchMode": "BYPASS" 
}
```
Modes: `PRECISION`, `BYPASS`, `HYPER`, `DEEP_CRAWL`.
