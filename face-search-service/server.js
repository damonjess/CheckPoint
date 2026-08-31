require('dotenv').config();

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const fs = require('fs');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Remove AdBlocker - It was blocking search engine image CDNs!
puppeteer.use(StealthPlugin());

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
    '--disable-blink-features=AutomationControlled',
    '--window-size=1920,1080'
  ];

  browserInstance = await puppeteer.launch({
    headless: true, // Revert to standard headless to avoid 'new' headless detection
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
    extractJs: `
      var items = [], seen = new Set();
      document.querySelectorAll('a.V6bBh, a.Luz2Q, a.G714Sc, .uaqyqd a, .G6S96 a, a.cspn0c').forEach(function(a) {
          var href = a.href;
          if (!href || href.indexOf('http') !== 0 || href.indexOf('google.') >= 0 || seen.has(href)) return;
          var img = a.querySelector('img') || a.closest('div')?.querySelector('img');
          var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
          if (!imgSrc || imgSrc.length < 15) return;
          seen.add(href);
          items.push({
              title: (a.innerText || a.getAttribute('aria-label') || 'Google Lens Match').replace(/\\s+/g,' ').trim().slice(0, 100),
              link: href,
              thumbnail: imgSrc,
              source: 'Google Lens',
              score: 100
          });
      });
      return items;
    `
  },
  {
    name: 'Bing Visual',
    urlFor: (url) => `https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=${encodeURIComponent(url)}`,
    extractJs: `
      var items = [], seen = new Set();
      document.querySelectorAll('.imgpt a, .iusc, .visual_search_results a, .richImgLnk, .infopt a').forEach(function(a) {
          var href = a.href || a.getAttribute('m');
          if (href && href.indexOf('{') === 0) {
              try { var m = JSON.parse(href); href = m.purl || m.murl; } catch(e){}
          }
          if (!href || href.indexOf('http') !== 0 || href.indexOf('bing.com') >= 0 || seen.has(href)) return;
          var img = a.querySelector('img') || a.closest('.imgpt, .img_cont, .dg_u, div')?.querySelector('img');
          var imgSrc = img ? (img.src || img.getAttribute('data-src')) : null;
          if (!imgSrc || imgSrc.length < 15) return;
          seen.add(href);
          items.push({
              title: (a.innerText || a.getAttribute('aria-label') || 'Bing Match').replace(/\\s+/g,' ').trim().slice(0, 100),
              link: href,
              thumbnail: imgSrc,
              source: 'Bing Visual',
              score: 100
          });
      });
      return items;
    `
  },
  {
    name: 'Yandex',
    urlFor: (url) => `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(url)}`,
    extractJs: `
      var items = [], seen = new Set();
      document.querySelectorAll('.CbirItem-Title a, .serp-item__link, .CbirSites-ItemTitle a, .CbirItem-TitleLink').forEach(function(a) {
          var href = a.href;
          if (!href || href.indexOf('http') !== 0 || href.indexOf('yandex.') >= 0 || seen.has(href)) return;
          var img = a.closest('.CbirItem, .serp-item, .CbirSites-Item, div')?.querySelector('img');
          var imgSrc = img ? (img.src || img.getAttribute('data-src') || img.getAttribute('src')) : null;
          if (!imgSrc || imgSrc.length < 15) return;
          seen.add(href);
          items.push({
              title: (a.innerText || 'Yandex Match').replace(/\\s+/g,' ').trim().slice(0, 100),
              link: href,
              thumbnail: imgSrc,
              source: 'Yandex',
              score: 100
          });
      });
      return items;
    `
  },
  {
    name: 'TinEye',
    urlFor: (url) => `https://tineye.com/search?url=${encodeURIComponent(url)}`,
    extractJs: `
      var items = [], seen = new Set();
      document.querySelectorAll('.match-row, .match, .result-row').forEach(function(row) {
          var linkEl = row.querySelector('h4 a, p a, .match-details a, a[href^="http"]');
          var imgEl = row.querySelector('.match-thumb img, .image img, img');
          if (linkEl && imgEl && linkEl.href && imgEl.src) {
              var href = linkEl.href.split('#')[0];
              if (seen.has(href)) return;
              seen.add(href);
              items.push({
                  title: (linkEl.innerText || 'TinEye Match').replace(/\\s+/g,' ').trim().slice(0, 100),
                  link: href,
                  thumbnail: imgEl.src,
                  source: 'TinEye',
                  score: 800
              });
          }
      });
      return items;
    `
  }
];

async function scrapeEngine(engine, imageUrl) {
  const browser = await getBrowser();
  let page = null;

  try {
    page = await browser.newPage();
    await page.setUserAgent(USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)]);
    await page.setExtraHTTPHeaders({ 'Accept-Language': 'en-US,en;q=0.9' });

    console.log(`[${engine.name}] Loading...`);

    await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout: ENGINE_TIMEOUT_MS });

    // Bypass consent
    await page.evaluate(() => {
        const consentBtns = ['#L2AGLb', '#bnp_btn_accept', '#accept-all', 'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]'];
        consentBtns.forEach(c => { const b = document.querySelector(c); if(b) b.click(); });
    });

    // Scroll to trigger lazy loading
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 3));
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await new Promise(r => setTimeout(r, 2000));

    // Extract matches using the engine-specific JS
    const matches = await page.evaluate((js) => {
        try {
            return new Function(js)();
        } catch(e) {
            return [];
        }
    }, engine.extractJs);

    console.log(`[${engine.name}] Found ${matches.length} matches`);
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
  const { imageUrl, sceneUrl } = req.body;
  const targetImage = sceneUrl || imageUrl;

  if (!targetImage || !targetImage.startsWith('http')) {
    return res.status(400).json({ success: false, error: 'A public http/https image URL is required.' });
  }

  console.log(`[Search] Starting probe for: ${targetImage.slice(0, 40)}...`);

  try {
    // Run engines sequentially in Termux to save RAM
    const allMatches = [];
    for (const engine of ENGINES) {
        const matches = await scrapeEngine(engine, targetImage);
        allMatches.push(...matches);
    }

    // Deduplicate by URL
    const uniqueMatches = [];
    const seenUrls = new Set();

    for (const match of allMatches) {
      if (!seenUrls.has(match.link)) {
        seenUrls.add(match.link);

        // Enhance source detection
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
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✓ Sherlock Termux Engine running on http://127.0.0.1:${PORT}`);
});