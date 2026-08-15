require('dotenv').config();

const dns = require('dns').promises;
const express = require('express');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');

const app = express();
app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const TERMUX_ENGINE_TIMEOUT_MS = 25_000;
const DESKTOP_ENGINE_TIMEOUT_MS = 35_000;
const COOLDOWN_MS = 10 * 60 * 1000;
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
    'bot detection', 'robot check'
  ].some((phrase) => value.includes(phrase));
}

function isSocialUrl(value) {
  try {
    const host = new URL(value).hostname.toLowerCase();
    return [
      'instagram.com', 'facebook.com', 'linkedin.com', 'x.com', 'twitter.com',
      'tiktok.com', 'youtube.com', 'reddit.com'
    ].some((domain) => host === domain || host.endsWith(`.${domain}`));
  } catch (_) {
    return false;
  }
}

const ENGINES = [
  {
    name: 'Bing Visual Search',
    urlFor: (imageUrl) => `https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${encodeURIComponent(imageUrl)}`,
    selectors: '.imgpt a, .iusc, .visual_search_results a, .vsc_link, .vsc_title a, .is-vsc-link'
  },
  {
    name: 'Google Lens',
    urlFor: (imageUrl) => `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(imageUrl)}`,
    selectors: '.Luz2Q, a.Luz2Q, .UA07L a, .G6S96 a, [data-is-vsc] a, [role="listitem"] a'
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
    } catch (_) {
      // A fresh local browser will be started below.
    }
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
      '--disable-sync'
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
    '#accept-all', '#onetrust-accept-btn-handler'
  ];
  for (const selector of selectors) {
    const button = await page.$(selector).catch(() => null);
    if (button) {
      await button.click().catch(() => {});
      await delay(600);
      return;
    }
  }
}

async function extractCandidates(page, engine) {
  return page.evaluate(({ selector, source }) => {
    const candidates = [];
    const seen = new Set();
    for (const node of document.querySelectorAll(selector)) {
      const anchor = node.tagName === 'A' ? node : node.closest('a');
      if (!anchor || !anchor.href || !anchor.href.startsWith('http')) continue;
      let link = anchor.href.split('#')[0];
      if (link.includes('google.com/url?q=')) {
        try { link = new URL(link).searchParams.get('q') || link; } catch (_) {}
      }
      const isEngineLink = /(^|\.)google\.|(^|\.)bing\.com|(^|\.)microsoft\.com|(^|\.)gstatic\.com/.test(new URL(link).hostname);
      if (isEngineLink || seen.has(link)) continue;
      seen.add(link);
      const image = anchor.querySelector('img') || node.querySelector('img');
      const thumbnail = image?.currentSrc || image?.src || null;
      const title = (anchor.innerText || anchor.getAttribute('aria-label') || anchor.title || 'Visual candidate').trim();
      candidates.push({ title: title.slice(0, 150) || 'Visual candidate', link, thumbnail, source });
    }
    return candidates.slice(0, 20);
  }, { selector: engine.selectors, source: engine.name });
}

async function runEngine(engine, imageUrl) {
  const startedAt = Date.now();
  if (isCoolingDown(engine.name)) {
    return { items: [], blocked: true, ms: 0, error: 'Engine is cooling down after an access challenge.' };
  }

  let page = null;
  try {
    const browser = await getBrowser();
    page = await browser.newPage();
    pageCount += 1;
    await page.setViewport({ width: 1280, height: 800 });
    const timeout = isTermux() ? TERMUX_ENGINE_TIMEOUT_MS : DESKTOP_ENGINE_TIMEOUT_MS;
    await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout });
    await delay(1_200);

    const title = await page.title().catch(() => '');
    const content = await page.content().catch(() => '');
    if (isBlockedPage(content, title) || /captcha|verification|sorry\//i.test(page.url())) {
      markBlocked(engine.name);
      return { items: [], blocked: true, ms: Date.now() - startedAt, error: 'Access challenge detected.' };
    }

    await acceptConsent(page);
    const items = await extractCandidates(page, engine);
    return { items, blocked: false, ms: Date.now() - startedAt, error: null };
  } catch (error) {
    const message = error.message || 'Browser request failed.';
    return { items: [], blocked: false, ms: Date.now() - startedAt, error: message };
  } finally {
    if (page) await page.close().catch(() => {});
  }
}

app.get('/api/ping', (_request, response) => {
  response.json({
    status: 'pong',
    runtime: isTermux() ? 'termux' : 'desktop',
    chromiumPath: getChromiumPath() || '',
    sequentialEngines: String(isTermux()),
    cloudBrowser: 'disabled'
  });
});

app.post('/api/search', async (request, response) => {
  const startedAt = Date.now();
  const imageUrl = request.body?.imageUrl || request.body?.localFaceUrl;
  if (!imageUrl || !/^https?:\/\//i.test(imageUrl)) {
    return response.status(400).json({ success: false, error: 'A public HTTP(S) image URL is required.' });
  }
  if (/localhost|127\.0\.0\.1|0\.0\.0\.0/i.test(imageUrl)) {
    return response.status(400).json({ success: false, error: 'The image must be hosted at a public URL before reverse-image search.' });
  }

  const connectivity = await checkConnectivity();
  if (!connectivity.ok) {
    return response.status(503).json({ success: false, error: `Network unavailable: ${connectivity.error}` });
  }

  console.log(`Starting local helper search for ${imageUrl.slice(0, 80)}…`);
  const outcomes = [];
  if (isTermux()) {
    for (const engine of ENGINES) outcomes.push({ engine, result: await runEngine(engine, imageUrl) });
  } else {
    const results = await Promise.all(ENGINES.map((engine) => runEngine(engine, imageUrl)));
    outcomes.push(...ENGINES.map((engine, index) => ({ engine, result: results[index] })));
  }

  const blockedEngines = outcomes.filter(({ result }) => result.blocked).map(({ engine }) => engine.name);
  const engines = Object.fromEntries(outcomes.map(({ engine, result }) => [engine.name, {
    count: result.items.length,
    ms: result.ms,
    error: result.error || undefined
  }]));
  const matches = outcomes.flatMap(({ result }) => result.items).map((item) => ({
    ...item,
    isSocial: isSocialUrl(item.link),
    score: 100
  }));

  response.json({
    success: true,
    matches,
    meta: {
      engines,
      blockedEngines,
      totalMs: Date.now() - startedAt
    }
  });
});

app.listen(PORT, '127.0.0.1', () => {
  console.log(`Local helper running on http://127.0.0.1:${PORT} (${isTermux() ? 'Termux sequential mode' : 'desktop parallel mode'}).`);
});

process.on('SIGINT', async () => {
  await browserInstance?.close().catch(() => {});
  process.exit(0);
});
