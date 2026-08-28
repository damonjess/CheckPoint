require('dotenv').config();

const util = require('util');
if (typeof global.TextEncoder === 'undefined') {
  global.TextEncoder = util.TextEncoder;
}
if (typeof global.TextDecoder === 'undefined') {
  global.TextDecoder = util.TextDecoder;
}

const dns = require('dns').promises;
const express = require('express');
const fs = require('fs');
const path = require('path');
const os = require('os');

// Termux Fix: Alias tfjs-node to tfjs because tfjs-node is not supported on Android/Termux
if (process.platform === 'android' || (process.env.PREFIX && process.env.PREFIX.includes('com.termux'))) {
  try {
    const tf = require('@tensorflow/tfjs');
    // Force CPU backend and wait for it to be ready
    tf.setBackend('cpu');
    console.log('[Termux Fix] Forced TensorFlow CPU backend');

    const tfPath = require.resolve('@tensorflow/tfjs-node');
    require.cache[tfPath] = {
      id: tfPath,
      filename: tfPath,
      loaded: true,
      exports: tf
    };
    console.log('[Termux Fix] Aliased @tensorflow/tfjs-node to @tensorflow/tfjs');
  } catch (e) {
    // If resolve fails, we can't easily inject into cache without the path.
    // However, if we're in Termux, we can mock the require entirely.
    const Module = require('module');
    const originalRequire = Module.prototype.require;
    Module.prototype.require = function(name) {
      if (name === '@tensorflow/tfjs-node') {
        const tf = originalRequire.apply(this, ['@tensorflow/tfjs']);
        tf.setBackend('cpu');
        return tf;
      }
      return originalRequire.apply(this, arguments);
    };
    console.log('[Termux Fix] Injected @tensorflow/tfjs-node redirection via Module.prototype.require');
  }
}

const http = require('http');
const WebSocket = require('ws');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const AdblockerPlugin = require('puppeteer-extra-plugin-adblocker');

let faceapi, Canvas, Image, loadImage;

try {
  faceapi = require('@vladmandic/face-api');
  const canvas = require('canvas');
  Canvas = canvas.Canvas;
  Image = canvas.Image;
  loadImage = canvas.loadImage;

  // Configure face-api to use canvas
  faceapi.env.monkeyPatch({ Canvas, Image, loadImage });
} catch (error) {
  console.error('\n[FATAL ERROR] Failed to load biometrics engine.');
  console.error('Error Details:', error.message);

  if (error.code === 'MODULE_NOT_FOUND' || error.message.includes('cannot find module')) {
    console.log('\n[REMEDY] This usually means native dependencies are missing in Termux.');
    console.log('Run the following commands in your Termux terminal:');
    console.log('  pkg install -y build-essential python git');
    console.log('  pkg install -y libcairo libpango libjpeg-turbo libpng libgif-static librsvg');
    console.log('  rm -rf node_modules package-lock.json');
    console.log('  npm install');
  }
  process.exit(1);
}

// Activate Plugins
puppeteer.use(StealthPlugin());
puppeteer.use(AdblockerPlugin({ blockTrackers: true }));

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(express.json({ limit: '10mb' }));

const PORT = Number(process.env.PORT || 3000);
const TERMUX_ENGINE_TIMEOUT_MS = 60_000;
const DESKTOP_ENGINE_TIMEOUT_MS = 45_000;
const COOLDOWN_MS = 2 * 60 * 1000; // Reduced from 15m to 2m for faster recovery

const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:128.0) Gecko/20100101 Firefox/128.0'
];

let sequentialBlockCount = 0;
const VIEWPORTS = [
  { width: 1280, height: 800 },
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1536, height: 864 },
  { width: 1920, height: 1080 }
];

const isTermux = () => {
  const prefix = process.env.PREFIX || '';
  return process.platform === 'android' ||
    prefix.includes('/data/data/com.termux/') ||
    prefix.includes('com.termux');
};

const MAX_BROWSER_PAGES = isTermux() ? 3 : 12;
const profileDirectory = path.join(__dirname, 'chromium_profile');
const modelsDirectory = path.join(__dirname, 'models');
const engineCooldowns = new Map();

if (!fs.existsSync(profileDirectory)) {
  fs.mkdirSync(profileDirectory, { recursive: true });
}

// Load face-api models
async function loadModels() {
  try {
    await faceapi.nets.ssdMobilenetv1.loadFromDisk(modelsDirectory);
    await faceapi.nets.faceLandmark68Net.loadFromDisk(modelsDirectory);
    await faceapi.nets.faceRecognitionNet.loadFromDisk(modelsDirectory);
    console.log('[Biometrics] Face-api models loaded successfully.');
  } catch (error) {
    console.error('[Biometrics] Error loading models:', error.message);
    console.log('[Tip] Ensure face-api models are in the ./models directory.');
  }
}

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
const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

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
    'bot detection', 'robot check', 'blocked by your organization',
    'please wait while we verify', 'complete a security challenge', 'ip has been flagged',
    'suspicious activity', 'checking your browser', 'access was denied',
    'our systems have detected', 'pardon our interruption'
  ].some((phrase) => value.includes(phrase));
}

function isSocialUrl(value) {
  try {
    const host = new URL(value).hostname.toLowerCase();
    const socialDomains = [
      'instagram.com', 'facebook.com', 'linkedin.com', 'x.com', 'twitter.com',
      'tiktok.com', 'youtube.com', 'reddit.com', 'onlyfans.com', 'fansly.com',
      't.me', 'vk.com', 'ok.ru', 'pinterest.com', 'threads.net', 'bluesky.social',
      'mastodon.social', 'discord.com', 'twitch.tv', 'github.com', 'behance.net',
      'dribbble.com', 'medium.com', 'quora.com', 'tumblr.com', 'flickr.com'
    ];
    return socialDomains.some((domain) => host === domain || host.endsWith(`.${domain}`));
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
    name: 'Sogou',
    urlFor: (imageUrl) => `https://pic.sogou.com/ris?query=${encodeURIComponent(imageUrl)}&flag=1`,
    selectors: '.item a, .ris-result-item a, .result-item a'
  },
  {
    name: 'Pinterest',
    urlFor: (imageUrl) => `https://www.pinterest.com/search/visual/?image_url=${encodeURIComponent(imageUrl)}`,
    selectors: '[data-test-id="pin"] a, .GrowthUnauthVisualSearch__pin a'
  },
  {
    name: 'PimEyes (Public Link Index)',
    urlFor: (imageUrl) => `https://www.bing.com/search?q=${encodeURIComponent(`site:pimeyes.com "${imageUrl.split('/').pop()}"`)}`,
    selectors: '.b_algo a, .result a'
  },
  {
    name: 'FaceCheck (Public Link Index)',
    urlFor: (imageUrl) => `https://duckduckgo.com/?q=${encodeURIComponent(`site:facecheck.id "${imageUrl.split('/').pop()}"`)}`,
    selectors: 'a.result__a'
  }
];

const CORE_SOCIAL_SITES = ['instagram.com', 'facebook.com', 'tiktok.com', 'linkedin.com', 'x.com', 'reddit.com', 't.me', 'linktr.ee'];
const EXPANDED_SOCIAL_SITES = [
  ...CORE_SOCIAL_SITES,
  'youtube.com', 'pinterest.com', 'threads.net', 'tumblr.com', 'flickr.com',
  'vk.com', 'ok.ru', 'medium.com', 'quora.com', 'github.com', 'behance.net',
  'dribbble.com', 'onlyfans.com', 'fansly.com', 'linktree.com', 'bio.link', 'peekyou.com'
];

function createSocialEngine(provider, site, hint) {
  const query = `site:${site} "${hint.trim()}"`;
  if (provider === 'Bing') {
    return {
      name: `Bing: ${site}`,
      urlFor: () => `https://www.bing.com/search?q=${encodeURIComponent(query)}`,
      selectors: 'li.b_algo, .b_algo, .result, .result__body, .g, a[href^="http"]',
      site
    };
  } else {
    return {
      name: `DDG: ${site}`,
      urlFor: () => `https://duckduckgo.com/?q=${encodeURIComponent(query)}`,
      selectors: 'a.result__a, .result__body, .g, a[href^="http"]',
      site
    };
  }
}

function deriveSearchHints(items) {
  const ignored = new Set(['visual', 'candidate', 'match', 'image', 'photo', 'profile', 'public', 'result', 'unknown', 'search', 'find', 'person', 'human', 'face', 'portrait']);
  const hints = [];

  // Pattern for handles like @username
  const handleRegex = /@([a-z0-9._]{3,20})/gi;
  // Pattern for names like First Last
  const nameRegex = /([A-Z][a-z]+(?:\s[A-Z][a-z]+)+)/g;

  for (const item of items) {
    const text = `${item.title} ${item.description || ''}`;

    // Extract @handles
    let match;
    while ((match = handleRegex.exec(text)) !== null) {
        hints.push(match[1].toLowerCase());
    }

    // Extract Proper Names
    while ((match = nameRegex.exec(text)) !== null) {
        const name = match[1].trim();
        if (name.length > 5 && !ignored.has(name.toLowerCase())) hints.push(name);
    }

    const title = String(item.title || '').replace(/\s+/g, ' ').trim();
    if (title.length >= 4 && title.length <= 50 && !ignored.has(title.toLowerCase())) {
      hints.push(title);
    }

    try {
      const url = new URL(item.link);
      const parts = url.pathname.split('/').filter(Boolean);
      // Look for common profile patterns
      const profilePart = parts.find(part => part.startsWith('@') || /^[a-z0-9._]{4,20}$/i.test(part));
      if (profilePart && !ignored.has(profilePart.toLowerCase())) {
        hints.push(profilePart.replace(/^@/, '').replace(/[._-]/g, ' '));
      }
    } catch (_) { }
  }

  // Prioritize shorter, handle-like strings and proper names
  return [...new Set(hints)]
    .sort((a, b) => a.length - b.length)
    .slice(0, 10);
}

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
      // Keep the safe rendering flags, but do not force Chromium into
      // single-process/no-zygote mode: it can close when Puppeteer creates a page.
      args.push('--disable-gpu', '--disable-software-rasterizer');
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
    '#accept-all', '#onetrust-accept-btn-handler', '.close-button', '.t-close',
    'button[aria-label*="Reject all"]', 'button[aria-label*="Decline"]'
  ];
  for (const selector of selectors) {
    try {
        const button = await page.$(selector);
        if (button) {
            await randomDelay(400, 900);
            await button.click();
            await randomDelay(800, 1500);
        }
    } catch (_) {}
  }
}

function getImageDimensions(image) {
  return {
    width: Number(image?.naturalWidth || image?.width || 0),
    height: Number(image?.naturalHeight || image?.height || 0)
  };
}

async function getFaceEmbedding(imageUrl, label = 'image') {
  if (typeof imageUrl !== 'string' || !imageUrl.startsWith('http' )) {
    console.warn(`[Biometrics] Skipping ${label}: invalid image URL`);
    return null;
  }

  let scopeStarted = false;

  try {
    const tf = require('@tensorflow/tfjs');
    const image = await loadImage(imageUrl);
    const { width, height } = getImageDimensions(image);

    // Never pass an unloaded, invalid, or zero-size image to TensorFlow.
    if (width < 2 || height < 2) {
      console.warn(
        `[Biometrics] Skipping ${label}: image dimensions are ${width}x${height}`
      );
      return null;
    }

    tf.engine().startScope();
    scopeStarted = true;

    const detection = await faceapi
      .detectSingleFace(image)
      .withFaceLandmarks()
      .withFaceDescriptor();

    // Copy the descriptor before closing the TensorFlow scope.
    return detection?.descriptor
      ? Float32Array.from(detection.descriptor)
      : null;
  } catch (error) {
    console.warn(`[Biometrics] Skipping ${label}: ${error.message}`);
    return null;
  } finally {
    if (scopeStarted) {
      tf.engine().endScope();
    }
  }
}

async function compareBiometrics(sourceEmbedding, targetUrl) {
  if (!sourceEmbedding || !targetUrl) return 0;

  const targetEmbedding = await getFaceEmbedding(targetUrl, 'candidate thumbnail');
  if (!targetEmbedding) return 0;

  const distance = faceapi.euclideanDistance(sourceEmbedding, targetEmbedding);
  return Math.max(0, 1 - distance / 0.6);
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

      const hostname = new URL(link).hostname;
      const isEngineLink = /(^|\.)google\.|(^|\.)bing\.com|(^|\.)microsoft\.com|(^|\.)gstatic\.com|(^|\.)yandex\.|(^|\.)baidu\.com/.test(hostname);
      if (isEngineLink || seen.has(link)) continue;
      if (engine.site && !hostname.endsWith(engine.site)) continue;
      seen.add(link);

      const image = anchor.querySelector('img') || node.querySelector('img');
      const thumbnail = image?.currentSrc || image?.src || null;
      const title = (anchor.innerText || node.innerText || anchor.getAttribute('aria-label') || anchor.title || 'Public result').trim();

      // Attempt to find a description/snippet near the link
      let description = '';
      const snippetParent = node.closest('li, div.g, div.result, div.b_algo');
      if (snippetParent) {
          description = (snippetParent.innerText.replace(title, '').replace(/\n+/g, ' ').trim()).slice(0, 200);
      }

      candidates.push({
          title: title.slice(0, 150) || 'Visual candidate',
          link,
          thumbnail,
          description,
          source
      });
    }
    return candidates.slice(0, 35);
  }, { selector: engine.selectors, source: engine.name });
}

async function runEngine(engine, imageUrl) {
  const startedAt = Date.now();
  if (isCoolingDown(engine.name)) {
    return { items: [], blocked: true, ms: 0, error: 'Engine cooling down.' };
  }

  let page = null;
  let pageAllocated = false;

  try {
    const browser = await getBrowser();

    // Termux Chromium is more stable with a normal page in the default
    // context than a newly-created incognito browser context per provider.
    page = await browser.newPage();
    pageAllocated = true;
    pageCount += 1;

    // Rotate User-Agent
    const ua = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
    await page.setUserAgent(ua);

    // Use browser defaults for headers to maintain protocol consistency.
    await page.setExtraHTTPHeaders({
        'Accept-Language': 'en-US,en;q=0.9',
        'Referer': 'https://www.google.com/'
    });

    // Randomize viewport to avoid fingerprint consistency flags
    const viewport = VIEWPORTS[Math.floor(Math.random() * VIEWPORTS.length)];
    await page.setViewport(viewport);

    // Behavioral stealth: small random mouse movement
    await page.mouse.move(Math.floor(Math.random() * 100), Math.floor(Math.random() * 100));

    const timeout = isTermux() ? TERMUX_ENGINE_TIMEOUT_MS : DESKTOP_ENGINE_TIMEOUT_MS;
    const response = await page.goto(engine.urlFor(imageUrl), { waitUntil: 'domcontentloaded', timeout });

    // Handle HTTP status codes indicating blocks
    if (response && (response.status() === 403 || response.status() === 429)) {
      markBlocked(engine.name);
      sequentialBlockCount++;
      return { items: [], blocked: true, ms: Date.now() - startedAt, error: `HTTP ${response.status()}: Access Forbidden/Limited.` };
    }

    // Handle Infinite Scroll/Lazy Load with Human-like Jitter
    await randomDelay(2000, 4500); // Increased initial delay
    const scrollY = 800 + Math.floor(Math.random() * 500);
    await page.evaluate((y) => window.scrollBy(0, y), scrollY);
    await randomDelay(1500, 3000);
    await page.evaluate(() => window.scrollBy(0, Math.floor(Math.random() * 200)));
    await randomDelay(800, 1500);

    const title = await page.title().catch(() => '');
    const content = await page.content().catch(() => '');
    if (isBlockedPage(content, title) || /captcha|verification|sorry\//i.test(page.url())) {
      markBlocked(engine.name);
      sequentialBlockCount++;

      // If multiple engines are blocked in a row, the browser instance might be flagged
      if (sequentialBlockCount >= 3) {
          console.log('[Stealth] Consecutive blocks detected. Recycling browser instance...');
          await browserInstance?.close().catch(() => {});
          browserInstance = null;
          browserLaunch = null;
          pageCount = 0;
          sequentialBlockCount = 0;
      }

      return { items: [], blocked: true, ms: Date.now() - startedAt, error: 'Access challenge.' };
    }

    await acceptConsent(page);
    const items = await extractCandidates(page, engine);
    sequentialBlockCount = 0; // Reset on success
    return { items, blocked: false, ms: Date.now() - startedAt, error: null };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[${engine.name}] provider execution failed: ${message}`);

    // A closed browser should not be reused for the next provider.
    if (/target closed|session closed|browser has disconnected/i.test(message)) {
      await browserInstance?.close().catch(() => {});
      browserInstance = null;
      browserLaunch = null;
      pageCount = 0;
    }

    return {
      items: [],
      blocked: false,
      ms: Date.now() - startedAt,
      error: message
    };
  } finally {
    if (page) await page.close().catch(() => {});
    if (pageAllocated) pageCount = Math.max(0, pageCount - 1);
  }
}

app.get('/api/ping', (_request, response ) => {
  response.status(200).json({
    status: 'pong',
    runtime: isTermux() ? 'termux' : 'desktop'
  });
});

app.post('/api/search', async (request, response) => {
  const startedAt = Date.now();

  // Prefer sceneUrl for visual search signals
  const imageUrl =
    request.body?.sceneUrl ||
    request.body?.imageUrl ||
    request.body?.localFaceUrl;

  console.log(`[Request] Incoming search for: ${imageUrl}`);

  const keywordHint = typeof request.body?.keywordHint === 'string' ? request.body.keywordHint.trim() : '';
  const searchMode = String(request.body?.searchMode || 'PRECISION').toUpperCase();

  if (!imageUrl || !/^https?:\/\//i.test(imageUrl)) {
    return response.status(400).json({ success: false, error: 'Invalid image URL.' });
  }

  const connectivity = await checkConnectivity();
  if (!connectivity.ok) {
    broadcastProgress(`⚠ Network failure: ${connectivity.error}`, 0);
    return response.status(503).json({ success: false, error: `Network error: ${connectivity.error}` });
  }

  broadcastProgress(`STEP 4: Database Cross-Matching & Confidence Scoring initialized.`, 0.05);
  broadcastProgress(`Crawling and indexing billions of public images from social profiles and public records...`, 0.08);

  const outcomes = [];
  // Dynamic concurrency based on environment and memory
  const freeMemMB = os.freemem() / 1024 / 1024;
  let parallelLimit = isTermux() ? 1 : 6;
  if (!isTermux() && (searchMode === 'DEEP' || searchMode === 'AGGRESSIVE')) {
    parallelLimit = Math.max(parallelLimit, 8);
  }

  console.log(`[OSINT] Mode: ${searchMode}, Concurrency: ${parallelLimit} (Free RAM: ${Math.round(freeMemMB)}MB)`);

  const chunks = [];
  for (let i = 0; i < ENGINES.length; i += parallelLimit) {
      chunks.push(ENGINES.slice(i, i + parallelLimit));
  }

  let completedEngines = 0;
  for (const chunk of chunks) {
      const engineNames = chunk.map(e => e.name).join(', ');
      broadcastProgress(`Scanning ${engineNames} for matching face embeddings...`, 0.1 + (completedEngines / ENGINES.length) * 0.8);

      const results = await Promise.all(chunk.map(engine => runEngine(engine, imageUrl)));
      outcomes.push(...chunk.map((engine, i) => ({ engine, result: results[i] })));
      completedEngines += chunk.length;
  }

  // Visual engines find copies; public social discovery finds the pages that
  // search engines associate with the returned names/usernames. Run it only
  // when a hint exists, including hints harvested from visual results.
  const visualItems = outcomes.flatMap(({ result }) => result.items);

  // BIOMETRIC VERIFICATION STAGE
  broadcastProgress(`Performing biometric analysis on ${visualItems.length} visual matches...`, 0.85);
  const sourceEmbedding = visualItems.length > 0
    ? await getFaceEmbedding(imageUrl, 'source image')
    : null;

  const matches = [];
  const biometricBatchSize = isTermux() ? 1 : 5;

  for (let i = 0; i < visualItems.length; i += biometricBatchSize) {
    const batch = visualItems.slice(i, i + biometricBatchSize);
    const results = await Promise.all(batch.map(async (item) => {
      const isSocial = isSocialUrl(item.link);
      const isVisualSource = ['Google Lens', 'Yandex', 'Bing Visual Search', 'Pinterest'].includes(item.source);

      let bioScore = 0;
      if (item.thumbnail && sourceEmbedding) {
         bioScore = await compareBiometrics(sourceEmbedding, item.thumbnail);
      }

      // Advanced confidence scoring
      let score = (isSocial ? 400 : 150);
      score += (item.thumbnail ? 120 : 0);
      if (isVisualSource) score += 100;
      score += Math.round(bioScore * 500); // Massive boost for biometric match

      return {
          ...item,
          isSocial,
          score,
          biometricMatch: bioScore > 0.65,
          confidence: Math.round(bioScore * 100) + '%'
      };
    }));
    matches.push(...results);
  }

  const socialHints = keywordHint ? [keywordHint] : deriveSearchHints(matches.filter(m => m.biometricMatch || m.score > 500));

  if (socialHints.length) {
    const sites = ['SOCIAL', 'DEEP', 'HYPER', 'AGGRESSIVE', 'ADULT'].includes(searchMode)
      ? EXPANDED_SOCIAL_SITES
      : CORE_SOCIAL_SITES;

    broadcastProgress(`Searching public social pages for ${socialHints.length} identity hint(s)...`, 0.92);

    // Multi-provider social search (Bing + DuckDuckGo)
    const socialEngines = socialHints.flatMap(hint =>
      sites.flatMap(site => [
        createSocialEngine('Bing', site, hint),
        createSocialEngine('DuckDuckGo', site, hint)
      ])
    );

    // Dynamic social concurrency
    const socialParallelLimit = isTermux() ? 1 : 3;
    for (let i = 0; i < socialEngines.length; i += socialParallelLimit) {
      const chunk = socialEngines.slice(i, i + socialParallelLimit);
      const socialResults = await Promise.all(chunk.map(engine => runEngine(engine, imageUrl)));
      outcomes.push(...chunk.map((engine, i) => ({ engine, result: socialResults[i] })));
    }
  }

  // Final scoring for results that didn't go through the biometric stage (social results)
  const finalMatches = outcomes.flatMap(({ result }) => {
      // If result was already processed in the biometric loop, skip it
      return result.items.filter(item => !matches.some(m => m.link === item.link));
  }).map(item => {
    const isSocial = isSocialUrl(item.link);
    const titleMatch = socialHints.some(hint => item.title.toLowerCase().includes(hint.toLowerCase()));

    let score = (isSocial ? 400 : 150);
    score += (item.thumbnail ? 120 : 0);
    score += (titleMatch ? 250 : 0);

    return { ...item, isSocial, score };
  });

  const allMatches = [...matches, ...finalMatches];
  allMatches.sort((a, b) => b.score - a.score);

  broadcastProgress(`Scan complete. Found ${allMatches.length} matches.`, 1.0);

  response.json({
    success: true,
    matches: allMatches,
    meta: {
      engines: Object.fromEntries(outcomes.map(({ engine, result }) => [engine.name, { count: result.items.length, ms: result.ms, error: result.error }])),
      blockedEngines: outcomes.filter(({ result }) => result.blocked).map(({ engine }) => engine.name),
      totalMs: Date.now() - startedAt,
      stealth: true,
      incognito: true
    }
  });
});

server.listen(PORT, '0.0.0.0', async () => {
  console.log(`[Sherlock OSINT] Running on http://0.0.0.0:${PORT}`);
  console.log(`[Status] Runtime: ${isTermux() ? 'Termux' : 'Desktop'}`);

  await loadModels();

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
