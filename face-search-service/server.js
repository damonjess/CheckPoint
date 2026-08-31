require('dotenv').config();

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const AdblockerPlugin = require('puppeteer-extra-plugin-adblocker');

puppeteer.use(StealthPlugin());
puppeteer.use(AdblockerPlugin({ blockTrackers: true }));

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const ENGINE_TIMEOUT_MS = 25_000;

const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36'
];

const getChromiumPath = () => {
  if (process.env.CHROMIUM_PATH && fs.existsSync(process.env.CHROMIUM_PATH)) {
    return process.env.CHROMIUM_PATH;
  }
  const candidates = [
    '/data/data/com.termux/files/usr/bin/chromium-browser',
    '/data/data/com.termux/files/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/usr/bin/google-chrome'
  ];
  return candidates.find(fs.existsSync);
};

let browserInstance = null;

async function getBrowser() {
  if (browserInstance) {
    try {
      await browserInstance.version();
      return browserInstance;
    } catch (_) {
      browserInstance = null;
    }
  }

  const chromiumPath = getChromiumPath();
  const args = [
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--disable-extensions',
    '--no-first-run',
    '--window-size=1280,800'
  ];

  browserInstance = await puppeteer.launch({
    headless: 'new',
    executablePath: chromiumPath,
    args,
    ignoreHTTPSErrors: true
  });

  return browserInstance;
}

const ENGINES = [
  {
    name: 'Google Lens',
    urlFor: (url) => `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(url)}`,
    selectors: 'a.Luz2Q, a.G714Sc, a.iJ41Ze, .V6bBh a, [data-action-url] a, div.g a'
  },
  {
    name: 'Bing Visual',
    urlFor: (url) => `https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${encodeURIComponent(url)}`,
    selectors: '.imgpt a, .iusc, .visual_search_results a, a.mimg'
  },
  {
    name: 'Yandex',
    urlFor: (url) => `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(url)}`,
    selectors: '.CbirItem-Title a, .serp-item__link, .iusc'
  },
  {
    name: 'DuckDuckGo',
    urlFor: (url, hint) => `https://duckduckgo.com/?q=${encodeURIComponent(hint || 'person')}&ia=images&iax=images`,
    selectors: 'a.result__a, .result__body a, .tile--img a'
  }
];

async function scrapeEngine(engine, imageUrl, keywordHint) {
  const browser = await getBrowser();
  let page = null;
  const targetUrl = engine.name === 'DuckDuckGo' ? engine.urlFor(imageUrl, keywordHint) : engine.urlFor(imageUrl);

  try {
    page = await browser.newPage();
    await page.setUserAgent(USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)]);
    await page.setExtraHTTPHeaders({ 'Accept-Language': 'en-US,en;q=0.9' });

    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: ENGINE_TIMEOUT_MS });
    await new Promise((r) => setTimeout(r, 2500));

    // Scroll down once to trigger lazy loading
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 2));
    await new Promise((r) => setTimeout(r, 1500));

    const matches = await page.evaluate(({ selector, engineName }) => {
      const results = [];
      const seen = new Set();
      const elements = document.querySelectorAll(selector);

      elements.forEach((el) => {
        const anchor = el.tagName === 'A' ? el : el.closest('a');
        if (!anchor || !anchor.href || !anchor.href.startsWith('http')) return;

        const link = anchor.href.split('#')[0];
        if (seen.has(link) || link.includes('google.com/search') || link.includes('bing.com/search')) return;

        const img = anchor.querySelector('img') || anchor.closest('div')?.querySelector('img');
        const thumb = img?.src || img?.getAttribute('data-src') || null;
        let title = (anchor.innerText || anchor.getAttribute('aria-label') || anchor.title || '').trim();

        if (title.length < 3 && img?.alt) title = img.alt.trim();
        if (title.length < 3) title = 'Visual Candidate';

        seen.add(link);
        results.push({
          title: title.slice(0, 140),
          link,
          thumbnail: thumb,
          source: engineName,
          isSocial: /(instagram|facebook|twitter|tiktok|linkedin|reddit|youtube|x\.com)/i.test(link),
          score: 85
        });
      });

      return results.slice(0, 25);
    }, { selector: engine.selectors, engineName: engine.name });

    return matches;
  } catch (err) {
    console.error(`[${engine.name}] Error:`, err.message);
    return [];
  } finally {
    if (page) await page.close().catch(() => {});
  }
}

// REST API Endpoints
app.get('/api/ping', (req, res) => {
  res.json({ status: 'ok', chromium: Boolean(getChromiumPath()) });
});

app.post('/api/search', async (req, res) => {
  const { imageUrl, sceneUrl, keywordHint } = req.body;
  const targetImage = sceneUrl || imageUrl;

  if (!targetImage || !targetImage.startsWith('http')) {
    return res.status(400).json({ success: false, error: 'A public http/https image URL is required.' });
  }

  console.log(`[Search] Starting probe for: ${targetImage.slice(0, 40)}... (Hint: ${keywordHint || 'None'})`);

  try {
    const searchPromises = ENGINES.map((engine) => scrapeEngine(engine, targetImage, keywordHint));
    const resultsArray = await Promise.all(searchPromises);
    const flattened = resultsArray.flat();

    // Deduplicate by URL
    const uniqueMatches = [];
    const seenUrls = new Set();

    for (const match of flattened) {
      if (!seenUrls.has(match.link)) {
        seenUrls.add(match.link);
        uniqueMatches.push(match);
      }
    }

    console.log(`[Search] Success. Total candidates found: ${uniqueMatches.length}`);
    res.json({
      success: true,
      matches: uniqueMatches,
      meta: { count: uniqueMatches.length }
    });
  } catch (err) {
    console.error('[Search] Fatal error:', err);
    res.status(500).json({ success: false, error: err.message });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✓ Sherlock Termux Engine running on http://127.0.0.1:${PORT}`);
});
