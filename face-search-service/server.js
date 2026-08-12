require('dotenv').config();
const express = require('express');
const fs = require('fs');
const dns = require('dns').promises;
const { URL } = require('url');
const { exec } = require('child_process');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const AdblockerPlugin = require('puppeteer-extra-plugin-adblocker');

puppeteer.use(StealthPlugin());
puppeteer.use(AdblockerPlugin({ blockTrackers: true }));

const app = express();
app.use(express.json({ limit: '10mb' }));

const PORT = process.env.PORT || 3000;
const RESTART_THRESHOLD = 40;
const USER_AGENTS = [
  'Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230805.019) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36',
  'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'
];

const getRandomUA = () => USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];

// ---------- Utils ----------
const delay = (ms, jitter = 0.3) => {
  const variance = ms * jitter;
  const actual = ms + (Math.random() * variance * 2 - variance);
  return new Promise(r => setTimeout(r, Math.max(200, Math.floor(actual))));
};

async function humanMouse(page) {
  try {
    const viewport = page.viewport() || { width: 1280, height: 720 };
    const x = 100 + Math.random() * (viewport.width - 200);
    const y = 100 + Math.random() * (viewport.height - 200);
    await page.mouse.move(x, y, { steps: 12 + Math.floor(Math.random() * 18) });
    if (Math.random() > 0.6) {
      await page.mouse.click(x, y, { delay: 40 + Math.random() * 80 });
    }
  } catch (e) {}
}

async function humanScroll(page) {
  try {
    await page.evaluate(async () => {
      const distance = 80 + Math.random() * 120;
      const maxScroll = Math.min(document.body.scrollHeight, 1200 + Math.random() * 800);
      let scrolled = 0;
      while (scrolled < maxScroll) {
        window.scrollBy(0, distance);
        scrolled += distance;
        await new Promise(r => setTimeout(r, 100 + Math.random() * 150));
      }
    });
  } catch (e) {}
}

async function withTimeout(promise, ms, message = 'Timeout') {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), ms))
  ]);
}

async function checkTermuxConnectivity() {
  const hosts = ['www.google.com', 'www.bing.com', 'yandex.ru'];
  for (const host of hosts) {
    try {
      await withTimeout(dns.lookup(host), 6000, `DNS lookup timeout for ${host}`);
      return { ok: true, host };
    } catch (err) {
      console.log(` ⚠️ Termux DNS check failed for ${host}: ${err.message}`);
      if (err.code === 'ENOTFOUND' || err.code === 'EAI_AGAIN' || err.message.includes('Timeout')) {
        return { ok: false, error: `DNS failure (${host}): ${err.message}` };
      }
    }
  }
  return { ok: false, error: 'DNS lookup failed for all known hosts' };
}

const isTermux = () => {
  try {
    return fs.existsSync('/data/data/com.termux/files/usr/bin/chromium-browser') ||
           fs.existsSync('/usr/bin/chromium-browser');
  } catch (e) {
    return false;
  }
};

const getChromiumPath = () => {
  if (process.env.CHROMIUM_PATH && fs.existsSync(process.env.CHROMIUM_PATH)) {
    return process.env.CHROMIUM_PATH;
  }
  const paths = [
    '/data/data/com.termux/files/usr/bin/chromium-browser',
    '/data/data/com.termux/files/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/usr/bin/chromium',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/google-chrome'
  ];
  for (const p of paths) {
    if (fs.existsSync(p)) {
      console.log(` 🛠️ Found Chromium at: ${p}`);
      return p;
    }
  }
  console.log(' ❌ No Chromium executable found in standard paths!');
  return undefined;
};

function cleanTitle(raw) {
  if (!raw) return 'Visual Match';
  return raw
    .replace(/^\d+\s*[×xX*]\s*\d+\s*/, '')
    .replace(/\.(jpg|jpeg|png|gif|webp|svg)\b/gi, '')
    .trim()
    .slice(0, 150) || 'Visual Match';
}

function isBlockedContent(html, title = '') {
  const lower = (html || '').toLowerCase();
  const lowerTitle = (title || '').toLowerCase();

  const strongBlockPhrases = [
    'access denied', 'forbidden', 'unusual traffic', 'verify you are human',
    'just a moment', 'validate your browser', 'security check',
    'attention required', 'checking your browser', 'please stand by while we verify',
    'one more step'
  ];

  const challengeSignals = [
    'cf-browser-verification', 'cf-challenge-body', 'cf-challenge-form',
    'cf-spinner-allow', 'g-recaptcha', 'data-sitekey=', 'name="captcha"',
    'id="challenge-form"', 'window._cf_chl_ctx', 'cf-error-code', 'captcha'
  ];

  const weakBlockPhrases = [
    'captcha', 'checkbox-captcha', 'smart-captcha', 'shield-container',
    'bot challenge', 'robot check', 'challenge', 'verify you are human'
  ];

  const falsePositives = [
    'protects you from', 'anti-bot', 'privacy policy', 'cookie policy',
    'terms of service', 'privacy settings', 'ads by', 'learn more about',
    'offering protection', 'security & privacy'
  ];

  const hasStrongBlock = strongBlockPhrases.some(phrase => lower.includes(phrase) || lowerTitle.includes(phrase));
  const hasChallengeSignal = challengeSignals.some(signal => lower.includes(signal));
  const hasWeakBlock = weakBlockPhrases.some(phrase => lower.includes(phrase) || lowerTitle.includes(phrase));

  if (hasStrongBlock) {
    return true;
  }

  if (hasWeakBlock && hasChallengeSignal) {
    return true;
  }

  if (falsePositives.some(fp => lower.includes(fp))) {
    return false;
  }

  return false;
}

// ---------- Browser lifecycle ----------
let browserInstance = null;
let launchPromise = null;
let pageCount = 0;

async function getBrowser(proxy = null) {
  if (browserInstance) {
    if (pageCount < RESTART_THRESHOLD) {
      try {
        await browserInstance.version();
        return browserInstance;
      } catch {
        console.log(' ⚠ Existing browser instance is dead, recreating...');
        browserInstance = null;
      }
    } else {
      console.log(' ♻️ Restarting browser to keep it fresh...');
      await browserInstance.close().catch(() => {});
      browserInstance = null;
      pageCount = 0;
    }
  }

  if (launchPromise) {
    console.log(' ⏳ Browser launch already in progress, waiting...');
    return launchPromise;
  }

  launchPromise = (async () => {
    const execPath = getChromiumPath();
    console.log(`🚀 Launching browser via Puppeteer... (Path: ${execPath || 'bundled'})`);

    const args = [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--disable-blink-features=AutomationControlled',
      '--window-size=1920,1080',
      '--no-zygote',
      '--disable-infobars',
      '--lang=en-US,en;q=0.9',
      '--disable-features=IsolateOrigins,site-per-process',
      '--disable-web-security',
      '--allow-running-insecure-content',
      '--disable-background-networking',
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-breakpad',
      '--disable-client-side-phishing-detection',
      '--disable-default-apps',
      '--disable-extensions',
      '--disable-hang-monitor',
      '--disable-ipc-flooding-protection',
      '--disable-popup-blocking',
      '--disable-prompt-on-repost',
      '--disable-renderer-backgrounding',
      '--disable-sync',
      '--force-color-profile=srgb',
      '--metrics-recording-only',
      '--safebrowsing-disable-auto-update',
      '--password-store=basic',
      '--use-mock-keychain'
    ];

    if (isTermux()) {
      console.log(' 📱 Termux environment detected, applying optimizations...');
      args.push('--single-process');
    }

    if (proxy?.server) args.push(`--proxy-server=${proxy.server}`);

    try {
      const browser = await puppeteer.launch({
        headless: "new",
        executablePath: execPath,
        args,
        ignoreHTTPSErrors: true,
        timeout: 90000 // High timeout for slow phone hardware
      });

      console.log(' ✅ Puppeteer Initialized Successfully');
      browserInstance = browser;
      browser.on('disconnected', () => {
        console.log('🛑 Browser disconnected');
        browserInstance = null;
        launchPromise = null;
        pageCount = 0;
      });
      return browser;
    } catch (err) {
      console.log(`❌ Browser launch failed: ${err.message}`);
      browserInstance = null;
      launchPromise = null;
      throw err;
    } finally {
      launchPromise = null;
    }
  })();

  return launchPromise;
}

async function withPage(fn) {
  let page = null;
  try {
    const browser = await getBrowser();
    page = await browser.newPage();
    pageCount++;

    const ua = getRandomUA();
    await page.setUserAgent(ua);

    await page.setViewport({
      width: 1280 + Math.floor(Math.random() * 200),
      height: 720 + Math.floor(Math.random() * 150),
      deviceScaleFactor: 1
    });

    // Advanced Evasions
    await page.evaluateOnNewDocument((userAgent) => {
      // 1. Mask WebDriver
      Object.defineProperty(navigator, 'webdriver', { get: () => undefined });

      // 2. Fix Chrome properties
      window.chrome = {
        runtime: {},
        loadTimes: function() {},
        csi: function() {},
        app: {}
      };

    // 3. Mask Languages
      Object.defineProperty(navigator, 'languages', { get: () => userAgent.includes('Android') ? ['en-US', 'en'] : ['en-US', 'en', 'en-GB'] });

      // 4. Mask Plugins
      Object.defineProperty(navigator, 'plugins', { get: () => userAgent.includes('Android') ? [] : [1, 2, 3, 4, 5] });

      // 5. Canvas Masking (Return slightly randomized data to defeat fingerprinting)
      const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
      CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
        const imageData = originalGetImageData.apply(this, arguments);
        for (let i = 0; i < imageData.data.length; i += 4) {
          imageData.data[i] = imageData.data[i] + (Math.random() > 0.5 ? 1 : -1);
        }
        return imageData;
      };

      // 6. Permissions Masking
      const originalQuery = window.navigator.permissions.query;
      window.navigator.permissions.query = (parameters) => (
        parameters.name === 'notifications' ?
          Promise.resolve({ state: Notification.permission }) :
          originalQuery(parameters)
      );
    }, ua);

    await page.setExtraHTTPHeaders({
      'Accept-Language': 'en-US,en;q=0.9',
      'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
      'Sec-CH-UA': ua.includes('Android') ?
        '"Chromium";v="128", "Not;A=Brand";v="24", "Android Chrome";v="128"' :
        '"Chromium";v="128", "Not;A=Brand";v="24", "Google Chrome";v="128"',
      'Sec-CH-UA-Mobile': ua.includes('Mobile') ? '?1' : '?0',
      'Sec-CH-UA-Platform': ua.includes('Android') ? '"Android"' : '"Windows"'
    });

    return await fn(page);
  } catch (err) {
    console.error(` ❌ [withPage] error: ${err.message}`);
    throw err;
  } finally {
    if (page) {
      await page.close().catch(() => {});
    }
  }
}

// ---------- Visual scrapers ----------
async function scrapeGeneric(page, url, name) {
  const domain = new URL(url).hostname;
  console.log(` 🌐 [${name}] → ${domain}`);

  try {
    // Try multiple attempts with UA rotation and small evasions to reduce challenge blocks
    let attempts = 0;
    let content = null;
    let title = '';
    let blockedDetected = false;

    while (attempts < 3) {
      attempts++;
      const attemptUA = getRandomUA();
      try {
        await page.setUserAgent(attemptUA);
        await page.setExtraHTTPHeaders({
          'Accept-Language': 'en-US,en;q=0.9',
          'Referer': `https://${domain}/`,
          'Sec-CH-UA': attemptUA.includes('Android') ? '"Chromium";v="128", "Not;A=Brand";v="24", "Android Chrome";v="128"' : '"Chromium";v="128", "Not;A=Brand";v="24", "Google Chrome";v="128"'
        });

        // Use a longer timeout and wait for load, but allow retries if timeouts happen
        await page.goto(url, { waitUntil: 'load', timeout: 60000 }).catch((e) => {
          console.log(` ⚠ [${name}] goto attempt ${attempts} failed: ${e.message.split('\n')[0]}`);
        });

        // small human-like waits and interactions before reading content
        await delay(2000 + Math.random() * 2000);
        await humanMouse(page);
        await delay(500 + Math.random() * 800);

        content = await page.content();
        title = await page.title();

        if (isBlockedContent(content, title)) {
          blockedDetected = true;
          console.log(` ⚠ [${name}] Attempt ${attempts} detected challenge ("${title}")`);
          if (attempts < 3) {
            console.log(`    → Retrying ${name} with different UA and delay...`);
            await delay(3000 + Math.random() * 3000);
            try { await page.reload({ waitUntil: 'load', timeout: 40000 }); } catch(_) {}
            continue;
          } else {
            console.log(` ⚠️ ${name} blocked by challenge after ${attempts} attempts`);
            return { items: [], blocked: true };
          }
        }

        // If we reached here without block, break attempts
        break;
      } catch (e) {
        console.log(` ⚠ [${name}] Attempt ${attempts} error: ${e.message.split('\n')[0]}`);
        if (attempts >= 3) {
          return { items: [], blocked: blockedDetected, error: e.message };
        }
        await delay(1500 + Math.random() * 1500);
      }
    }

    // Engine-specific waits
    const selectorTimeout = 25000;
    if (name === 'Yandex') {
      await page.waitForSelector('.cbir-item, .CbirItem, .serp-item, .cbir-section, .cbir-page', { timeout: selectorTimeout }).catch(() => {});
    } else if (name === 'Google Master') {
      await page.waitForSelector('.Luz2Q, .V6bBh, [data-is-search-result], a[href^="http"]', { timeout: selectorTimeout }).catch(() => {});
    } else if (name === 'Bing') {
      await page.waitForSelector('.imgpt, .iusc, .mimg, a[href^="http"]', { timeout: selectorTimeout }).catch(() => {});
    } else if (name === 'Baidu') {
      await page.waitForSelector('.graph-samilar-list-item, .graph-similar-list-item, a[href^="http"]', { timeout: selectorTimeout }).catch(() => {});
    }

    // Additional human behavior emulation
    await humanMouse(page);
    await delay(500 + Math.random() * 500);

    // Perform multiple small scrolls
    for(let i=0; i<4; i++) {
      await page.evaluate(() => window.scrollBy(0, 350 + Math.random() * 300));
      await delay(600 + Math.random() * 900);
    }

    const items = await page.evaluate((engineName) => {
      const out = [];
      const seen = new Set();
      const blockedHosts = ['yandex.', 'google.', 'bing.com', 'baidu.com', 'gstatic.', 'yastatic.'];

      const getImg = (el) => {
        if (!el) return null;
        const search = (node) => {
            if (!node) return null;
            if (node.tagName === 'IMG') return node.src || node.getAttribute('data-src') || node.getAttribute('data-original') || node.getAttribute('data-lazy-src');
            const img = node.querySelector('img');
            if (img) return img.src || img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src');
            return null;
        };
        return search(el) || search(el.parentElement) || search(el.closest('div')) || search(el.closest('li'));
      };

      const selectors = [
        '.cbir-item__title a', '.cbir-item a', '.serp-item__link',
        '.V6bBh', 'a.Luz2Q', '.Luz2Q a', '.iJ41Ze a',
        '.graph-samilar-list-item a', '.graph-similar-list-item a',
        '.imgpt a', '.iusc', 'a.mimg', '.mimg',
        '.G714Sc a', '.WpHeLc', 'a[href*="imgurl"]',
        'a[href^="http"]:not([href*="google.com"]):not([href*="yandex.com"])'
      ];

      document.querySelectorAll(selectors.join(',')).forEach((el) => {
        try {
            const a = el.tagName === 'A' ? el : el.closest('a');
            if (!a || !a.href) return;

            let link = a.href.split('#')[0];
            if (link.startsWith('/')) link = window.location.origin + link;

            if (seen.has(link)) return;
            if (blockedHosts.some(h => link.includes(h))) return;

            let imgSrc = getImg(el) || getImg(a);

            // Special handling for data-m or data-bem
            if (!imgSrc) {
              const m = a.getAttribute('m') || el.getAttribute('m') || el.getAttribute('data-bem') || a.getAttribute('data-bem');
              if (m) {
                const turl = m.match(/turl":"([^"]+)"/) || m.match(/img_href":"([^"]+)"/) || m.match(/thumbUrl":"([^"]+)"/);
                if (turl) imgSrc = turl[1].replace(/\\u0026/g, '&');
              }
            }

            const title = (a.innerText || a.textContent || a.title || a.getAttribute('aria-label') || '')
              .replace(/\s+/g, ' ')
              .trim()
              .slice(0, 180);

            if (title.length > 5 || imgSrc) {
              seen.add(link);
              out.push({
                title: title || 'Visual Match',
                link,
                thumbnail: imgSrc,
                source: engineName,
                score: 50
              });
            }
        } catch(err) {}
      });

      return out;
    }, name);

    return { items, blocked: false };
  } catch (e) {
    console.log(` ⚠ [${name}] ${e.message.split('\n')[0]}`);
    return { items: [], blocked: false, error: e.message };
  }
}

// ---------- Social dorking (fixed) ----------
async function scrapeSocialDorks(keyword, sites) {
  const results = [];
  const noise = ['privacy', 'terms', 'cookie', 'help', 'feedback', 'legal', 'ads', 'support', 'about', 'protection', 'policy'];

  const engines = [
    {
      name: 'Bing',
      url: (q) => `https://www.bing.com/search?q=${encodeURIComponent(q)}`,
      selector: 'li.b_algo h2 a, h2 a'
    },
    {
      name: 'DuckDuckGo',
      url: (q) => `https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`,
      selector: 'h2 a, .result__a, a.result-link'
    }
  ];

  for (const site of sites) {
    console.log(` 🕵️ Dorking: ${site}`);
    let found = false;

    for (const engine of engines) {
      if (found) break;

      try {
        const items = await withPage(async (page) => {
          const query = `site:${site} "${keyword}"`;
          const searchUrl = engine.url(query);

          await page.setExtraHTTPHeaders({
            'Accept-Language': 'en-US,en;q=0.9',
            'Referer': `https://${new URL(searchUrl).hostname}/`
          });

          await delay(4000 + Math.random() * 4000); // cooldown between dorks
          await page.goto(searchUrl, { waitUntil: 'networkidle2', timeout: 45000 });
          await delay(2000 + Math.random() * 2000);

          const html = await page.content();
          const title = await page.title();
          if (isBlockedContent(html, title)) {
            console.log(` ⚠️ ${engine.name} blocked for ${site} ("${title}")`);
            return [];
          }

          await page.waitForSelector(engine.selector, { timeout: 10000 }).catch(() => {});
          await humanMouse(page);
          await delay(600 + Math.random() * 800);
          await humanScroll(page);
          await delay(1500 + Math.random() * 1500);

          return page.evaluate((sel, source, noiseList) => {
            const items = [];
            const seen = new Set();
            document.querySelectorAll(sel).forEach((a) => {
              const title = (a.innerText || a.textContent || '').trim();
              const link = a.href;
              if (!link || seen.has(link) || title.length < 4) return;
              const low = title.toLowerCase();
              if (noiseList.some(p => low.includes(p))) return;
              seen.add(link);
              items.push({ title, link, source, score: 250 });
            });
            return items;
          }, engine.selector, engine.name, noise);
        });

        if (items.length > 0) {
          results.push(...items);
          found = true;
          console.log(` ✅ ${site} → ${items.length} via ${engine.name}`);
        }
      } catch (e) {
        console.log(` ⚠ ${engine.name}/${site}: ${e.message.split('\n')[0]}`);
      }
    }
  }

  return results;
}

// ---------- Main search route ----------
app.post('/api/search', async (req, res) => {
  const { imageUrl, localBypassUrl, localFaceUrl, searchMode, deepCrawl, keywordHint } = req.body;

  // Map internal app modes to server-side deep crawl logic
  const isDeep = deepCrawl === true ||
                 ['PRECISION', 'HYPER', 'AGGRESSIVE', 'DEEP', 'SOCIAL', 'DEEP_CRAWL'].includes(searchMode);

  // CRITICAL: Visual search engines (Yandex, Bing, Google) REQUIRE a public URL.
  // Local bypass URLs (127.0.0.1) will ONLY work for local WebView scraping,
  // not for server-side scraping where the engine must fetch the image.
  const visualProbeUrl = imageUrl || localFaceUrl || localBypassUrl;

  if (!visualProbeUrl || (!visualProbeUrl.startsWith('http') && !visualProbeUrl.startsWith('https'))) {
    return res.status(400).json({ success: false, error: 'No valid public image URL provided' });
  }

  // Pre-check browser health
  try {
    const browser = await getBrowser();
    await browser.version();
  } catch (err) {
    console.log(' ❌ Browser health check failed, forcing restart...');
    browserInstance = null;
    launchPromise = null;
  }

  if (isTermux()) {
    const connectivity = await checkTermuxConnectivity();
    if (!connectivity.ok) {
      console.log(` ⚠️ Termux network issue detected: ${connectivity.error}`);
      return res.status(503).json({ success: false, error: `Termux network failure: ${connectivity.error}` });
    }
    console.log(` ✅ Termux DNS check passed via ${connectivity.host}`);
  }

  console.log(`\n📸 Search: ${visualProbeUrl.slice(0, 55)}... | Deep: ${isDeep}`);

  const matches = [];
  const meta = { engines: {}, started: Date.now() };
  let browserDead = false;

  const runEngine = async (name, url) => {
    if (browserDead) return;
    const start = Date.now();

    try {
      const { items, blocked, error } = await withPage((page) => scrapeGeneric(page, url, name));

      if (blocked) {
        console.log(` ⚠️ ${name} BLOCKED (Challenge)`);
        meta.engines[name] = { count: 0, blocked: true, ms: Date.now() - start };
        return;
      }

      if (error) {
        console.log(` ❌ ${name} ERROR: ${error}`);
        meta.engines[name] = { count: 0, error, ms: Date.now() - start };
        return;
      }

      matches.push(...(items || []));
      meta.engines[name] = { count: items?.length || 0, ms: Date.now() - start };
      console.log(` ${items?.length ? '✅' : '⚠'} ${name}: ${items?.length || 0}`);
    } catch (e) {
      console.log(` ❌ ${name} FATAL: ${e.message.split('\n')[0]}`);
      meta.engines[name] = { count: 0, error: e.message.split('\n')[0], ms: Date.now() - start };
      if (e.message.includes('closed') || e.message.includes('Target closed')) {
        browserDead = true;
        browserInstance = null;
      }
    }
  };

  try {
    // Switch to .ru for Yandex, it's generally more stable for visual search
    const yandexUrl = `https://yandex.ru/images/search?rpt=imageview&url=${encodeURIComponent(visualProbeUrl)}`;
    const bingUrl = `https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${encodeURIComponent(visualProbeUrl)}`;
    const baiduUrl = `https://graph.baidu.com/pcpage/index?tpl_from=pc&image=${encodeURIComponent(visualProbeUrl)}`;

    // 1. Pre-warm Yandex (The hardest to bypass)
    if (!browserDead) {
      await withPage(async (page) => {
        try {
          await page.goto('https://yandex.ru/images', { waitUntil: 'domcontentloaded', timeout: 20000 });
          await delay(1500);
        } catch (e) { console.log(' ⚠ Yandex pre-warm failed'); }
      });
    }

    await runEngine('Yandex', yandexUrl);
    await delay(4000 + Math.random() * 3000);

    // 2. Bing - Using a slightly different URL format for better results
    await runEngine('Bing', bingUrl);
    await delay(4000 + Math.random() * 3000);

    // 3. Baidu
    await runEngine('Baidu', baiduUrl);

    if (isDeep && !browserDead) {
      await delay(6000 + Math.random() * 4000);
      const googleUrl = `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(visualProbeUrl)}`;

      // Pre-warm Google
      await withPage(async (page) => {
        try {
          await page.goto('https://www.google.com', { waitUntil: 'domcontentloaded', timeout: 15000 });
          await delay(1000);
        } catch (e) {}
      });

      await runEngine('Google Master', googleUrl);

      if (keywordHint) {
        console.log(` 🕵️ Deep dorking for "${keywordHint}"`);
        const deepSites = [
          'instagram.com', 'facebook.com', 'twitter.com', 'x.com',
          'linkedin.com', 'vk.com', 'tiktok.com'
        ];
        const start = Date.now();
        try {
          const dorkResults = await scrapeSocialDorks(keywordHint, deepSites);
          matches.push(...dorkResults);
          meta.engines['Deep Dork'] = { count: dorkResults.length, ms: Date.now() - start };
          console.log(` ✅ Deep Dork: ${dorkResults.length}`);
        } catch (e) {
          console.log(` ❌ Deep Dork: ${e.message}`);
        }
      }
    }

    const filtered = matches
      .filter(r => r.link && r.title && r.title.length > 3)
      .filter(r => {
        const t = r.title.toLowerCase();
        return !['legal', 'privacy', 'advertise', 'help', 'feedback', 'cookie', 'terms', 'policy'].some(w => t.includes(w));
      })
      .map(r => ({ ...r, title: cleanTitle(r.title) }));

    const finalUnique = filtered.filter((v, i, a) => a.findIndex(t => t.link === v.link) === i);

    meta.totalMs = Date.now() - meta.started;
    console.log(`🎯 Done → ${finalUnique.length} unique results (${meta.totalMs}ms)`);

    if (!res.headersSent) {
      res.json({ success: true, matches: finalUnique.slice(0, 50), meta });
    }
  } catch (err) {
    console.error(' ❌ FATAL ERROR IN SEARCH ROUTE:');
    console.error(err.stack);
    if (!res.headersSent) {
      res.status(500).json({ success: false, error: err.message, stack: err.stack });
    }
  }
});

// ---------- Extra endpoints ----------
app.get('/api/ping', (req, res) => res.json({ status: 'pong', version: '6.8' }));

app.post('/api/extract', async (req, res) => {
  const { url } = req.body;
  if (!url) return res.status(400).json({ success: false, error: 'No URL provided' });

  console.log(`\n🔍 Extract: ${url}`);

  const galleryDlCmd = `gallery-dl --no-check-certificate --no-warnings -j "${url}"`;
  const ytdlpCmd = `yt-dlp --no-check-certificate --no-warnings -j "${url}"`;

  const useGallery = /instagram\.com|reddit\.com|twitter\.com|x\.com|facebook\.com|onlyfans\.com/i.test(url);
  const cmd = useGallery ? galleryDlCmd : ytdlpCmd;

  exec(cmd, { timeout: 45000 }, (error, stdout) => {
    if (error && useGallery) {
      return exec(ytdlpCmd, { timeout: 45000 }, (e2, s2) => handleExtractionResult(e2, s2, res));
    }
    handleExtractionResult(error, stdout, res);
  });
});

function handleExtractionResult(error, stdout, res) {
  if (error) {
    return res.json({ success: false, error: error.message });
  }
  try {
    const data = JSON.parse(stdout);
    let highResUrl = null;

    if (Array.isArray(data)) {
      for (const entry of data) {
        if (Array.isArray(entry) && typeof entry[1] === 'string' && entry[1].startsWith('http')) {
          highResUrl = entry[1];
          break;
        }
      }
    } else if (data?.url) {
      highResUrl = data.url;
    } else if (data?.thumbnails?.length) {
      highResUrl = data.thumbnails.sort((a, b) => (b.width || 0) - (a.width || 0))[0].url;
    }

    if (highResUrl) {
      res.json({ success: true, highResUrl });
    } else {
      res.json({ success: false, error: 'No media found' });
    }
  } catch {
    res.json({ success: false, error: 'Parse failure' });
  }
}

app.post('/api/dork-search', async (req, res) => {
  const { keyword, sites } = req.body;
  if (!keyword) return res.status(400).json({ success: false, error: 'No keyword provided' });

  const targetSites = sites || ['instagram.com', 'facebook.com', 'twitter.com', 'linkedin.com'];
  console.log(`\n🕵️ Dork: "${keyword}" → ${targetSites.join(', ')}`);

  try {
    const matches = await scrapeSocialDorks(keyword, targetSites);
    res.json({ success: true, matches });
  } catch (e) {
    res.status(500).json({ success: false, error: e.message });
  }
});

// Graceful shutdown
async function shutdown() {
  console.log('\n🛑 Shutting down...');
  if (browserInstance) {
    await browserInstance.close().catch(() => {});
  }
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

app.listen(PORT, '0.0.0.0', () => {
  console.log(`⚡ BACKEND v6.8 (Robust) running on port ${PORT}`);
});
