require('dotenv').config();

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');

const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const ENGINE_TIMEOUT_MS = 35_000;

// Forced Desktop User-Agent
const DEFAULT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36';

const getChromiumPath = () => {
  if (process.env.CHROMIUM_PATH && fs.existsSync(process.env.CHROMIUM_PATH)) {
    return process.env.CHROMIUM_PATH;
  }
  const candidates = [
    '/data/data/com.termux/files/usr/bin/chromium-browser',
    '/data/data/com.termux/files/usr/bin/chromium',
    '/usr/bin/chromium-browser'
  ];
  return candidates.find(fs.existsSync);
};

let browserInstance = null;
let isSearching = false;

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
    '--disable-software-rasterizer',
    '--disable-blink-features=AutomationControlled',
    '--hide-scrollbars',
    '--mute-audio',
    '--window-size=1920,1080',
    `--user-agent=${DEFAULT_UA}`,
    '--single-process'
  ];

  browserInstance = await puppeteer.launch({
    headless: true,
    executablePath: chromiumPath,
    args: args,
    ignoreHTTPSErrors: true,
    // Force a large desktop viewport to guarantee desktop HTML
    defaultViewport: { width: 1920, height: 1080, isMobile: false, hasTouch: false }
  });

  return browserInstance;
}

// Universal extractor script that ignores CSS classes and just looks for image links
const UNIVERSAL_EXTRACT_JS = `
    var items = [], seen = new Set();
    var badThumb = ['logo', 'icon', 'favicon', 'avatar', 'default', 'shutterstock', 'istock', 'data:image/gif'];

    document.querySelectorAll('a[href^="http"]').forEach(function(a){
        try {
            var href = a.href;
            if (href.indexOf('google.com/url?') >= 0) {
                var match = href.match(/url\\?q=([^&]+)/);
                if (match) href = decodeURIComponent(match[1]);
            }

            href = href.split('#')[0];
            if(seen.has(href) || href.indexOf('google.') >= 0 || href.indexOf('bing.com') >= 0 || href.indexOf('yandex.') >= 0) return;

            var img = a.querySelector('img');
            if (!img) {
                var div = a.closest('div');
                if (div) img = div.querySelector('img');
            }

            var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
            if(!imgSrc || imgSrc.length < 15) return;

            var lowSrc = imgSrc.toLowerCase();
            var isBad = false;
            for(var j = 0; j < badThumb.length; j++) {
                if(lowSrc.indexOf(badThumb[j]) >= 0) { isBad = true; break; }
            }
            if(isBad) return;

            var title = (a.innerText || a.title || 'Visual Candidate').replace(/\\s+/g,' ').trim().slice(0, 100);
            if (title.toLowerCase().indexOf('sign in') >= 0 || title.length < 3) return;

            seen.add(href);
            items.push({
                title: title,
                link: href,
                thumbnail: imgSrc,
                source: 'Web',
                score: 100
            });
        } catch(e){}
    });
    return items;
`;

const ENGINES = [
  {
    name: 'Google Lens',
    urlFor: (url) => `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(url)}`,
    extractJs: UNIVERSAL_EXTRACT_JS
  },
  {
    name: 'Bing Visual',
    urlFor: (url) => `https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=${encodeURIComponent(url)}`,
    extractJs: UNIVERSAL_EXTRACT_JS
  },
  {
    name: 'Yandex',
    urlFor: (url) => `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(url)}`,
    extractJs: UNIVERSAL_EXTRACT_JS
  }
];

async function scrapeEngine(engine, imageUrl) {
  const browser = await getBrowser();
  let page = null;

  try {
    page = await browser.newPage();

    // Evasions
    await page.evaluateOnNewDocument(() => {
      Object.defineProperty(navigator, 'webdriver', { get: () => false });
      Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
      window.chrome = { runtime: {} };
    });

    console.log(`[${engine.name}] Loading...`);

    await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout: ENGINE_TIMEOUT_MS });

    // Handle Consent Banners
    await page.evaluate(() => {
        const consentBtns = ['#L2AGLb', '#bnp_btn_accept', '#accept-all', 'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]'];
        consentBtns.forEach(c => { const b = document.querySelector(c); if(b) b.click(); });
    });

    // Scroll to trigger lazy loading
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 3));
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 1.5));
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await new Promise(r => setTimeout(r, 2000));

    // Extract matches
    const matches = await page.evaluate((js) => {
        try {
            return new Function(js)();
        } catch(e) {
            return [];
        }
    }, engine.extractJs);

    console.log(`[${engine.name}] Found ${matches.length} matches`);

    // Re-map the source name so the Android app knows where it came from
    return matches.map(m => ({ ...m, source: engine.name }));

  } catch (err) {
    console.error(`[${engine.name}] Error:`, err.message);
    return [];
  } finally {
    if (page) await page.close().catch(() => {});
  }
}

app.get('/api/ping', (req, res) => {
  res.json({ status: 'ok', chromium: Boolean(getChromiumPath()) });
});

app.post('/api/search', async (req, res) => {
  const { imageUrl, sceneUrl } = req.body;
  const targetImage = sceneUrl || imageUrl;

  if (!targetImage || !targetImage.startsWith('http')) {
    return res.status(400).json({ success: false, error: 'A public URL is required.' });
  }

  if (isSearching) {
    console.log('[Search] Rejected overlapping request to prevent OOM crash.');
    return res.status(429).json({ success: false, error: 'Search already in progress.' });
  }

  isSearching = true;
  console.log(`\n[Search] Starting probe for: ${targetImage.slice(0, 45)}...`);

  try {
    const allMatches = [];

    for (const engine of ENGINES) {
        const matches = await scrapeEngine(engine, targetImage);
        allMatches.push(...matches);
    }

    const uniqueMatches = [];
    const seenUrls = new Set();

    for (const match of allMatches) {
      if (!seenUrls.has(match.link)) {
        seenUrls.add(match.link);
        const isSocial = /(instagram|facebook|twitter|tiktok|linkedin|reddit|youtube|x\.com)/i.test(match.link);
        uniqueMatches.push({ ...match, isSocial });
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
  } finally {
    isSearching = false;
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✓ Sherlock Termux Engine running on http://127.0.0.1:${PORT}`);
});