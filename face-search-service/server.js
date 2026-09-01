require('dotenv').config();

const express = require('express');
const http = require('http');
const fs = require('fs');

const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const app = express();
const server = http.createServer(app);

app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const ENGINE_TIMEOUT_MS = 60_000;

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
let activeSearchPromise = null;

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
    '--disable-webgl',
    '--disable-animations',
    '--disable-blink-features=AutomationControlled',
    '--hide-scrollbars',
    '--mute-audio',
    '--window-size=1280,800',
    `--user-agent=${DEFAULT_UA}`,
    '--single-process',
    '--no-zygote'
  ];

  browserInstance = await puppeteer.launch({
    headless: true,
    executablePath: chromiumPath,
    args: args,
    ignoreHTTPSErrors: true,
    defaultViewport: { width: 1280, height: 800, isMobile: false, hasTouch: false }
  });

  return browserInstance;
}

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
                score: 100
            });
        } catch(e){}
    });
    return items;
`;

const ADULT_EXTRACT_JS = `
    var items = [], seen = new Set();

    // Select both Bing Video tiles and standard web result items
    var nodes = document.querySelectorAll('.mc_vtvc, .vr_items, .dg_u, .b_algo, .b_ans, .inline_video');

    nodes.forEach(function(el) {
        try {
            var linkEl = el.querySelector('a[href^="http"]') || (el.tagName === 'A' ? el : null);
            if (!linkEl) return;

            var href = linkEl.href.split('#')[0];
            if (!href || href.indexOf('http') !== 0 || seen.has(href)) return;

            var lowHref = href.toLowerCase();
            if (lowHref.indexOf('bing.com') >= 0 || lowHref.indexOf('microsoft.com') >= 0) return;

            var title = (el.querySelector('.b_title a, .mc_vtvc_title, h2, strong')?.innerText || linkEl.innerText || el.getAttribute('aria-label') || 'Adult Match').replace(/\\s+/g, ' ').trim();
            if (title.length < 3) return;

            var img = el.querySelector('img');
            var thumb = img ? (img.src || img.getAttribute('data-src') || img.getAttribute('src')) : null;

            seen.add(href);
            items.push({
                title: title.slice(0, 120),
                link: href,
                thumbnail: thumb,
                source: 'Adult',
                score: thumb ? 350 : 200
            });
        } catch(e){}
    });

    return items;
`;

const ENGINES = [
  {
    name: 'Sogou Visual',
    urlFor: (url) => `https://pic.sogou.com/ris?query=${encodeURIComponent(url)}&flag=1`,
    extractJs: UNIVERSAL_EXTRACT_JS
  },
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

    await page.evaluateOnNewDocument(() => {
      Object.defineProperty(navigator, 'webdriver', { get: () => false });
      window.chrome = { runtime: {} };
    });

    console.log(`[${engine.name}] Navigating to probe URL...`);

    await page.goto(engine.urlFor(imageUrl), {
      waitUntil: 'networkidle2',
      timeout: ENGINE_TIMEOUT_MS
    });

    // Dismiss common cookie/consent dialogues
    await page.evaluate(() => {
      const consentSelectors = [
        '#L2AGLb', '#bnp_btn_accept', '#accept-all',
        'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]',
        'a#adlt_set_off', '.bnp_btn_accept'
      ];
      consentSelectors.forEach(s => {
        const el = document.querySelector(s);
        if (el) el.click();
      });
    }).catch(() => {});

    // Allow dynamic results to settle in the DOM
    await new Promise(r => setTimeout(r, 4500));

    // Scroll to trigger lazy loading of thumbnail grids
    await page.evaluate(() => window.scrollBy(0, 600)).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));

    const pageTitle = await page.title();
    console.log(`[${engine.name}] Page loaded: "${pageTitle.slice(0, 50)}"`);

    const matches = await page.evaluate((js) => {
      try { return new Function(js)(); } catch(e) { return []; }
    }, engine.extractJs);

    console.log(`[${engine.name}] Extracted ${matches.length} candidate(s)`);
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

  console.log(`\n[Search] Probe target: ${targetImage.slice(0, 50)}...`);

  try {
    const allMatches = [];

    // Run sequentially to prevent CPU throttling on device
    for (const engine of ENGINES) {
      const matches = await scrapeEngine(engine, targetImage);
      allMatches.push(...matches);
    }

    const uniqueMatches = [];
    const seenUrls = new Set();

    for (const match of allMatches) {
      if (match.link && !seenUrls.has(match.link)) {
        seenUrls.add(match.link);
        const isSocial = /(instagram|facebook|twitter|tiktok|linkedin|reddit|youtube|x\.com|threads|pinterest|vk\.com)/i.test(match.link);
        uniqueMatches.push({ ...match, isSocial });
      }
    }

    console.log(`[Search] Completed. Aggregated ${uniqueMatches.length} candidate(s).`);
    res.json({
      success: true,
      matches: uniqueMatches,
      meta: { count: uniqueMatches.length }
    });
  } catch (err) {
    console.error('[Search] Server error:', err);
    res.status(500).json({ success: false, error: err.message });
  }
});

app.post('/api/dork-search', async (req, res) => {
  const { keyword, sites } = req.body;
  const cleanKeyword = (keyword || '').trim();

  if (!cleanKeyword || !sites || !sites.length) {
    return res.status(400).json({ success: false, error: 'Missing keyword or sites' });
  }

  if (activeSearchPromise) {
    console.log('[Dork] Waiting for previous search to complete...');
    try { await activeSearchPromise; } catch(e) {}
  }

  let resolveSearch;
  activeSearchPromise = new Promise((resolve) => { resolveSearch = resolve; });

  try {
    const browser = await getBrowser();
    const allMatches = [];

    // Query Bing Videos vertical for tube platforms
    const siteQuery = sites.map(s => `site:${s}`).join(' OR ');
    const query = `${siteQuery} "${cleanKeyword}"`;
    const videoUrl = `https://www.bing.com/videos/search?q=${encodeURIComponent(query)}&adlt=off&safesearch=0`;

    console.log(`[Dork] Querying Bing Video index for "${cleanKeyword}" across ${sites.length} sites...`);

    let page = null;
    try {
      page = await browser.newPage();

      await page.setCookie({
        name: 'SRCHHPGUSR',
        value: 'ADLT=OFF',
        domain: '.bing.com',
        path: '/'
      });

      await page.goto(videoUrl, { waitUntil: 'domcontentloaded', timeout: ENGINE_TIMEOUT_MS });
      await new Promise(r => setTimeout(r, 4000));

      await page.evaluate(() => {
        const btns = document.querySelectorAll('a#adlt_set_off, #adult_warning_safesearch, a.b_check, #bnp_btn_accept');
        btns.forEach(b => b.click());
      }).catch(() => {});

      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 2)).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));

      const matches = await page.evaluate((js) => {
        try { return new Function(js)(); } catch(e) { return []; }
      }, ADULT_EXTRACT_JS);

      console.log(`[Dork] Found ${matches.length} matches`);
      allMatches.push(...matches);

    } catch (err) {
      console.error(`[Dork] Error during extraction:`, err.message);
    } finally {
      if (page) await page.close().catch(() => {});
    }

    res.json({
      success: true,
      matches: allMatches,
      meta: { count: allMatches.length }
    });

  } catch (err) {
    console.error('[Dork] Fatal error:', err);
    res.status(500).json({ success: false, error: err.message });
  } finally {
    resolveSearch();
    activeSearchPromise = null;
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✓ Sherlock Termux Engine running on http://127.0.0.1:${PORT}`);
});
