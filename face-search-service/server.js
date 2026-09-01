require('dotenv').config();

const express = require('express');
const http = require('http');
const fs = require('fs');

const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const app = express();
const server = http.createServer(app);
const WebSocket = require('ws');
const wss = new WebSocket.Server({ noServer: true });

// WebSocket progress broadcast
function broadcastProgress(message, progress) {
  const data = JSON.stringify({ type: 'progress', message, progress });
  wss.clients.forEach(client => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(data);
    }
  });
}

server.on('upgrade', (request, socket, head) => {
  if (request.url === '/ws') {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request);
    });
  } else {
    socket.destroy();
  }
});

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

  const engineTimeout = setTimeout(() => {
    console.error(`[${engine.name}] Global engine timeout triggered`);
  }, ENGINE_TIMEOUT_MS + 15000);

  try {
    page = await browser.newPage();
    page.setDefaultTimeout(ENGINE_TIMEOUT_MS);

    await page.evaluateOnNewDocument(() => {
      Object.defineProperty(navigator, 'webdriver', { get: () => false });
      window.chrome = { runtime: {} };
    });

    console.log(`[${engine.name}] Loading...`);
    broadcastProgress(`[${engine.name}] Loading...`, 0.2);

    await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout: ENGINE_TIMEOUT_MS });

    const isChallenged = await page.evaluate(() => {
        const text = document.body.innerText.toLowerCase();
        return text.includes('captcha') || text.includes('verify you are a human') || text.includes('unusual traffic');
    }).catch(() => false);

    if (isChallenged) {
        console.log(`[${engine.name}] Access challenge detected. Skipping.`);
        broadcastProgress(`[${engine.name}] Access challenge detected. Skipping.`, 0.3);
        return [];
    }

    await new Promise(r => setTimeout(r, 4000));

    await page.evaluate(() => {
        const consentBtns = ['#L2AGLb', '#bnp_btn_accept', '#accept-all', 'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]'];
        consentBtns.forEach(c => { const b = document.querySelector(c); if(b) b.click(); });
    }).catch(() => {});

    console.log(`[${engine.name}] Scrolling...`);
    broadcastProgress(`[${engine.name}] Scrolling...`, 0.4);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 3)).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 1.5)).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)).catch(() => {});
    await new Promise(r => setTimeout(r, 2500));

    console.log(`[${engine.name}] Extracting matches...`);
    broadcastProgress(`[${engine.name}] Extracting matches...`, 0.6);
    const matches = await page.evaluate((js) => {
        try {
            return new Function(js)();
        } catch(e) {
            return [];
        }
    }, engine.extractJs);

    console.log(`[${engine.name}] Found ${matches.length} matches`);
    return matches.map(m => ({ ...m, source: engine.name }));

  } catch (err) {
    console.error(`[${engine.name}] Error:`, err.message);
    return [];
  } finally {
    clearTimeout(engineTimeout);
    if (page) {
        try {
            await page.close();
        } catch (e) {
            console.error(`[${engine.name}] Error closing page:`, e.message);
        }
    }
  }
}

app.get('/api/ping', (req, res) => {
  res.json({ status: 'ok', chromium: Boolean(getChromiumPath()) });
});

app.post('/api/search', async (req, res) => {
  const { imageUrl, sceneUrl, keywordHint, searchMode } = req.body;
  const targetImage = sceneUrl || imageUrl;

  if (!targetImage || !targetImage.startsWith('http')) {
    return res.status(400).json({ success: false, error: 'A public URL is required.' });
  }

  if (activeSearchPromise) {
    console.log('[Search] Waiting for previous search to complete...');
    try {
        await Promise.race([
            activeSearchPromise,
            new Promise((_, reject) => setTimeout(() => reject(new Error('Search queue timeout')), 90000))
        ]);
    } catch(e) {
        console.warn('[Search] Proceeding despite previous search status:', e.message);
    }
  }

  let resolveSearch;
  activeSearchPromise = new Promise((resolve) => { resolveSearch = resolve; });

  console.log(`\n[Search] Starting probe for: ${targetImage.slice(0, 45)}...`);
  if (keywordHint) console.log(`[Search] Identity Hint: "${keywordHint}"`);
  if (searchMode) console.log(`[Search] Mode: ${searchMode}`);

  broadcastProgress(`[Search] Starting probe ${keywordHint ? `for "${keywordHint}"` : ''}...`, 0.1);

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
    resolveSearch();
    activeSearchPromise = null;
  }
});

// FIX: Added SafeSearch bypass cookies, queued execution, and updated selectors
app.post('/api/dork-search', async (req, res) => {
  const { keyword, sites } = req.body;
  if (!keyword) return res.status(400).json({ success: false, error: 'Keyword is required.' });

  const siteList = sites || [];
  console.log(`[Dork] Scanning for "${keyword}" across ${siteList.length} sites...`);
  broadcastProgress(`[Dork] Scanning for "${keyword}"...`, 0.7);

  if (activeSearchPromise) {
    console.log('[Dork] Waiting for previous search to complete...');
    try {
        await Promise.race([
            activeSearchPromise,
            new Promise((_, reject) => setTimeout(() => reject(new Error('Search queue timeout')), 90000))
        ]);
    } catch(e) {}
  }

  let resolveSearch;
  activeSearchPromise = new Promise((resolve) => { resolveSearch = resolve; });

  const browser = await getBrowser();
  let page = null;
  try {
    page = await browser.newPage();
    page.setDefaultTimeout(30000);

    // Bypass Bing SafeSearch explicitly so Adult endpoints trigger
    await page.setCookie({ name: 'SRCHHPGUSR', value: 'ADLT=OFF', domain: '.bing.com', path: '/' });
    await page.setCookie({ name: '_EDGE_V', value: '1', domain: '.bing.com', path: '/' });

    const siteQuery = siteList.length > 0 ? `(site:${siteList.join(' OR site:')})` : '';
    const query = `${siteQuery} "${keyword}"`.trim();
    const url = `https://www.bing.com/search?q=${encodeURIComponent(query)}&adlt=off&safesearch=0`;

    console.log(`[Dork] Querying: ${url}`);
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await new Promise(r => setTimeout(r, 4000));

    const matches = await page.evaluate(() => {
        const items = [];
        const seen = new Set();
        const rows = document.querySelectorAll('li.b_algo, .b_algo, .result, .g, .dg_u, .vr_items');

        rows.forEach(row => {
            let a = row.querySelector('h2 a, .result__a, .b_title a');
            if(!a) a = row.closest('a') || row.querySelector('a');
            if(!a || !a.href) return;

            const href = a.href.split('#')[0];
            if(seen.has(href)) return;

            const lowHref = href.toLowerCase();
            if (lowHref.indexOf('bing.com') >= 0 || lowHref.indexOf('google.') >= 0 || lowHref.indexOf('microsoft.com') >= 0) return;

            seen.add(href);

            const img = row.querySelector('img') || row.closest('div')?.querySelector('img');
            items.push({
                title: (a.innerText || a.textContent || '').trim().slice(0, 150),
                link: href,
                thumbnail: img ? (img.src || img.getAttribute('data-src')) : null,
                source: 'Dork',
                score: 300
            });
        });
        return items;
    });

    console.log(`[Dork] Found ${matches.length} matches`);
    res.json({ success: true, matches });
  } catch (err) {
    console.error('[Dork] Error:', err.message);
    res.status(500).json({ success: false, error: err.message });
  } finally {
    if (page) await page.close().catch(() => {});
    resolveSearch();
    activeSearchPromise = null;
  }
});

app.post('/api/extract-media', async (req, res) => {
    const { url } = req.body;
    if (!url) return res.status(400).json({ success: false, error: 'URL is required.' });

    console.log(`[Extract] Extracting from ${url.slice(0, 50)}...`);
    const browser = await getBrowser();
    let page = null;
    try {
        page = await browser.newPage();
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        const highResUrl = await page.evaluate(() => {
            const og = document.querySelector('meta[property="og:image"]');
            return og ? og.content : null;
        });
        res.json({ success: true, highResUrl });
    } catch (err) {
        res.status(500).json({ success: false, error: err.message });
    } finally {
        if (page) await page.close().catch(() => {});
    }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✓ Sherlock Termux Engine running on http://127.0.0.1:${PORT}`);
});