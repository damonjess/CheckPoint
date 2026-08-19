require('dotenv').config();

const dns = require('dns').promises;
const express = require('express');
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const WebSocket = require('ws');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

// Activate Stealth Plugin
puppeteer.use(StealthPlugin());

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const TERMUX_ENGINE_TIMEOUT_MS = 35_000;
const DESKTOP_ENGINE_TIMEOUT_MS = 45_000;
const COOLDOWN_MS = 15 * 60 * 1000;
const MAX_BROWSER_PAGES = 8;
const profileDirectory = path.join(__dirname, 'chromium_profile');
const engineCooldowns = new Map();

if (!fs.existsSync(profileDirectory)) {
  fs.mkdirSync(profileDirectory, { recursive: true });
}

const isTermux = () => {
  const prefix = process.env.PREFIX || '';
  return process.platform === 'android' ||
    prefix.includes('/data/data/com.termux/') ||
    fs.existsSync('/data/data/com.termux/files/usr/bin/pkg');
};

const getChromiumPath = () => {
  if (process.env.CHROMIUM_PATH && fs.existsSync(process.env.CHROMIUM_PATH)) {
    return process.env.CHROMIUM_PATH;
  }
  const candidates = [
    '/data/data/com.termux/files/usr/bin/chromium-browser',
    '/data/data/com.termux/files/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable'
  ];
  return candidates.find(fs.existsSync);
};

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function checkConnectivity() {
  try {
    await Promise.race([
      dns.lookup('www.google.com'),
      new Promise((_, reject) => setTimeout(() => reject(new Error('DNS timed out')), 5_000))
    ]);
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.message || 'DNS check failed' };
  }
}

function broadcastProgress(message, progress = 0) {
    const data = JSON.stringify({ type: 'progress', message, progress });
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(data);
        }
    });
    console.log(`[Progress ${Math.round(progress * 100)}%] ${message}`);
}

function isCoolingDown(engineName) {
  const until = engineCooldowns.get(engineName);
  return typeof until === 'number' && Date.now() < until;
}

function markBlocked(engineName) {
  engineCooldowns.set(engineName, Date.now() + COOLDOWN_MS);
  console.log(`[${engineName}] marked unavailable for ${COOLDOWN_MS / 60_000} minutes.`);
}

function isBlockedPage(content, title = '') {
  const value = `${title}\n${content}`.toLowerCase();
  return [
    'access denied', 'unusual traffic', 'verify you are human', 'security check',
    'captcha', 'hcaptcha', 'recaptcha', 'cloudflare', 'automated access',
    'bot detection', 'robot check', 'blocked by your organization'
  ].some((phrase) => value.includes(phrase));
}

function isSocialUrl(value) {
  try {
    const host = new URL(value).hostname.toLowerCase();
    return [
      'instagram.com', 'facebook.com', 'linkedin.com', 'x.com', 'twitter.com',
      'tiktok.com', 'youtube.com', 'reddit.com', 'onlyfans.com', 'fansly.com',
      't.me', 'vk.com', 'ok.ru', 'pinterest.com'
    ].some((domain) => host === domain || host.endsWith(`.${domain}`));
  } catch (_) {
    return false;
  }
}

const ENGINES = [
  {
    name: 'Google Lens',
    urlFor: (imageUrl) => `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(imageUrl)}`,
    selectors: '.Luz2Q, a.Luz2Q, .UA07L a, .G6S96 a, [data-is-vsc] a, [role="listitem"] a'
  },
  {
    name: 'Yandex',
    urlFor: (imageUrl) => `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`,
    selectors: '.CbirItem-Title a, .serp-item__link, .iusc, a.mimg'
  },
  {
    name: 'Bing Visual Search',
    urlFor: (imageUrl) => `https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${encodeURIComponent(imageUrl)}`,
    selectors: '.imgpt a, .iusc, .visual_search_results a, .vsc_link, .vsc_title a, .is-vsc-link'
  },
  {
    name: 'TinEye',
    urlFor: (imageUrl) => `https://tineye.com/search?url=${encodeURIComponent(imageUrl)}`,
    selectors: '.match a[href^="http"], .match-details a'
  },
  {
    name: 'Pinterest',
    urlFor: (imageUrl) => `https://www.pinterest.com/search/visual/?image_url=${encodeURIComponent(imageUrl)}`,
    selectors: '[data-test-id="pin"] a, .GrowthUnauthVisualSearch__pin a'
  }
];

let browserInstance = null;
let browserLaunch = null;
let pageCount = 0;

async function getBrowser() {
  if (browserInstance) {
    try {
      await browserInstance.version();
      if (pageCount < MAX_BROWSER_PAGES) return browserInstance;
      await browserInstance.close();
    } catch (_) { }
    browserInstance = null;
    pageCount = 0;
  }

  if (browserLaunch) return browserLaunch;
  browserLaunch = (async () => {
    const chromiumPath = getChromiumPath();
    if (isTermux() && !chromiumPath) {
      throw new Error('Chromium is unavailable. Install it with: pkg install chromium');
    }

    const args = [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-extensions',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-background-networking',
      '--disable-sync',
      '--window-size=1280,800',
      '--disable-blink-features=AutomationControlled'
    ];
    if (isTermux()) {
      args.push('--disable-gpu', '--single-process', '--no-zygote', '--disable-software-rasterizer');
    }

    const browser = await puppeteer.launch({
      headless: 'new',
      executablePath: chromiumPath,
      args,
      ignoreHTTPSErrors: true,
      userDataDir: profileDirectory
    });
    browser.on('disconnected', () => {
      browserInstance = null;
      browserLaunch = null;
      pageCount = 0;
    });
    browserInstance = browser;
    return browser;
  })();

  try {
    return await browserLaunch;
  } finally {
    browserLaunch = null;
  }
}

async function acceptConsent(page) {
  const selectors = [
    'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]', '#L2AGLb',
    '#accept-all', '#onetrust-accept-btn-handler', '.close-button', '.t-close'
  ];
  for (const selector of selectors) {
    try {
        const button = await page.$(selector);
        if (button) {
            await button.click();
            await delay(800);
        }
    } catch (_) {}
  }
}

async function extractCandidates(page, engine) {
  return page.evaluate(({ selector, source }) => {
    const candidates = [];
    const seen = new Set();
    const rows = document.querySelectorAll(selector);

    for (const node of rows) {
      const anchor = node.tagName === 'A' ? node : node.closest('a');
      if (!anchor || !anchor.href || !anchor.href.startsWith('http')) continue;

      let link = anchor.href.split('#')[0];
      if (link.includes('google.com/url?q=')) {
        try { link = new URL(link).searchParams.get('q') || link; } catch (_) {}
      }

      const isEngineLink = /(^|\.)google\.|(^|\.)bing\.com|(^|\.)microsoft\.com|(^|\.)gstatic\.com|(^|\.)yandex\./.test(new URL(link).hostname);
      if (isEngineLink || seen.has(link)) continue;
      seen.add(link);

      const image = anchor.querySelector('img') || node.querySelector('img');
      const thumbnail = image?.currentSrc || image?.src || null;
      const title = (anchor.innerText || anchor.getAttribute('aria-label') || anchor.title || 'Visual candidate').trim();

      candidates.push({
          title: title.slice(0, 150) || 'Visual candidate',
          link,
          thumbnail,
          source
      });
    }
    return candidates.slice(0, 30);
  }, { selector: engine.selectors, source: engine.name });
}

async function runEngine(engine, imageUrl) {
  const startedAt = Date.now();
  if (isCoolingDown(engine.name)) {
    return { items: [], blocked: true, ms: 0, error: 'Engine cooling down.' };
  }

  let context = null;
  let page = null;
  try {
    const browser = await getBrowser();
    // Incognito isolation
    context = await browser.createIncognitoBrowserContext();
    page = await context.newPage();
    pageCount += 1;

    // Randomize User-Agent
    const uas = [
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36'
    ];
    await page.setUserAgent(uas[Math.floor(Math.random() * uas.length)]);
    await page.setViewport({ width: 1280, height: 900 });

    const timeout = isTermux() ? TERMUX_ENGINE_TIMEOUT_MS : DESKTOP_ENGINE_TIMEOUT_MS;
    await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout });

    // Scroll to trigger lazy loading
    await delay(2000);
    await page.evaluate(() => window.scrollBy(0, 800));
    await delay(1200);
    await page.evaluate(() => window.scrollBy(0, 800));
    await delay(1200);

    const title = await page.title().catch(() => '');
    const content = await page.content().catch(() => '');
    if (isBlockedPage(content, title) || /captcha|verification|sorry\//i.test(page.url())) {
      markBlocked(engine.name);
      return { items: [], blocked: true, ms: Date.now() - startedAt, error: 'Access challenge.' };
    }

    await acceptConsent(page);
    const items = await extractCandidates(page, engine);
    return { items, blocked: false, ms: Date.now() - startedAt, error: null };
  } catch (error) {
    return { items: [], blocked: false, ms: Date.now() - startedAt, error: error.message };
  } finally {
    if (page) await page.close().catch(() => {});
    if (context) await context.close().catch(() => {});
  }
}

app.get('/api/ping', async (_request, response) => {
  const connectivity = await checkConnectivity();
  response.json({
    status: 'pong',
    runtime: isTermux() ? 'termux' : 'desktop',
    engines: ENGINES.map(e => e.name),
    capabilities: ['parallel', 'scrolling', 'stealth', 'incognito', 'websockets'],
    network: connectivity.ok ? 'online' : `offline (${connectivity.error})`,
    memory: {
        free: Math.round(os.freemem() / 1024 / 1024) + 'MB',
        total: Math.round(os.totalmem() / 1024 / 1024) + 'MB'
    }
  });
});

app.post('/api/search', async (request, response) => {
  const startedAt = Date.now();
  const imageUrl = request.body?.imageUrl || request.body?.localFaceUrl;

  if (!imageUrl || !/^https?:\/\//i.test(imageUrl)) {
    return response.status(400).json({ success: false, error: 'Invalid image URL.' });
  }

  const connectivity = await checkConnectivity();
  if (!connectivity.ok) {
    broadcastProgress(`⚠ Network failure: ${connectivity.error}`, 0);
    return response.status(503).json({ success: false, error: `Network error: ${connectivity.error}` });
  }

  broadcastProgress(`Starting OSINT scan for image...`, 0.05);

  const outcomes = [];
  // Dynamic concurrency based on free memory
  const freeMemMB = os.freemem() / 1024 / 1024;
  const parallelLimit = isTermux() ? (freeMemMB > 800 ? 2 : 1) : 4;

  console.log(`[OSINT] Concurrency limit: ${parallelLimit} (Free RAM: ${Math.round(freeMemMB)}MB)`);

  const chunks = [];
  for (let i = 0; i < ENGINES.length; i += parallelLimit) {
      chunks.push(ENGINES.slice(i, i + parallelLimit));
  }

  let completedEngines = 0;
  for (const chunk of chunks) {
      broadcastProgress(`Running engines: ${chunk.map(e => e.name).join(', ')}...`, 0.1 + (completedEngines / ENGINES.length) * 0.8);

      const results = await Promise.all(chunk.map(engine => runEngine(engine, imageUrl)));
      outcomes.push(...chunk.map((engine, i) => ({ engine, result: results[i] })));
      completedEngines += chunk.length;
  }

  const matches = outcomes.flatMap(({ result }) => result.items).map(item => ({
    ...item,
    isSocial: isSocialUrl(item.link),
    score: 100
  }));

  broadcastProgress(`Scan complete. Found ${matches.length} matches.`, 1.0);

  response.json({
    success: true,
    matches,
    meta: {
      engines: Object.fromEntries(outcomes.map(({ engine, result }) => [engine.name, { count: result.items.length, ms: result.ms, error: result.error }])),
      blockedEngines: outcomes.filter(({ result }) => result.blocked).map(({ engine }) => engine.name),
      totalMs: Date.now() - startedAt,
      stealth: true,
      incognito: true
    }
  });
});

server.listen(PORT, '127.0.0.1', async () => {
  console.log(`[Sherlock OSINT] Running on http://127.0.0.1:${PORT}`);
  console.log(`[Status] Runtime: ${isTermux() ? 'Termux' : 'Desktop'}`);

  const connectivity = await checkConnectivity();
  if (!connectivity.ok) {
      console.error(`[Warning] No internet connectivity: ${connectivity.error}`);
      console.log(`[Tip] If in Termux, try: echo "nameserver 8.8.8.8" > /data/data/com.termux/files/usr/etc/resolv.conf`);
  } else {
      console.log(`[Status] Network: Online`);
  }
});

process.on('SIGINT', async () => {
  await browserInstance?.close().catch(() => {});
  process.exit(0);
});
