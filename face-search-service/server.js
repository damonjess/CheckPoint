require('dotenv').config();
const express = require('express');
const fs = require('fs');
const path = require('path');
const dns = require('dns').promises;
const { URL } = require('url');
const { exec } = require('child_process');
const puppeteerExtra = require('puppeteer-extra');
const puppeteerBase = require('puppeteer');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const AdblockerPlugin = require('puppeteer-extra-plugin-adblocker');

puppeteerExtra.use(AdblockerPlugin({ blockTrackers: true }));
const stealth = StealthPlugin();

const app = express();
app.use(express.json({ limit: '10mb' }));

const PORT = process.env.PORT || 3000;
const RESTART_THRESHOLD = 15; // Lowered for Termux stability

// ---------- Proxy Parsing ----------
let proxy = null;
if (process.env.PROXY_URL && !process.env.PROXY_URL.includes('proxy-host')) {
  try {
    const u = new URL(process.env.PROXY_URL);
    proxy = {
      server: `${u.protocol}//${u.host}`,
      username: decodeURIComponent(u.username),
      password: decodeURIComponent(u.password)
    };
    console.log(` 🌐 Proxy configured: ${u.protocol}//${u.host}`);
  } catch (e) {
    console.log(' ⚠ Invalid PROXY_URL in .env');
  }
}

// ---------- Persistent Profile ----------
const userDataDir = path.join(__dirname, 'chromium_profile');
if (!fs.existsSync(userDataDir)) {
  fs.mkdirSync(userDataDir, { recursive: true });
}

// ---------- Engine Cooldowns ----------
const engineCooldowns = new Map();
const COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes

function isEngineBlocked(name) {
  const until = engineCooldowns.get(name);
  return until && Date.now() < until;
}
function markEngineBlocked(name) {
  engineCooldowns.set(name, Date.now() + COOLDOWN_MS);
  console.log(` 🚫 [${name}] cooling down for 10 minutes`);
}

const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  'Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0'
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
  const blocks = [
    'access denied', 'forbidden', 'unusual traffic', 'verify you are human',
    'security check', 'captcha-form', 'checkbox-captcha', 'hcaptcha',
    'recaptcha', 'cloudflare', 'ddos protection', 'automated access',
    'bot detection', 'robot'
  ];
  return blocks.some(b => lower.includes(b) || lowerTitle.includes(b));
}

async function handleConsents(page) {
  try {
    const selectors = [
      'button[aria-label*="Accept"]', 'button[aria-label*="Agree"]',
      '#L2AGLb', '.v139.WpHeLc', '#accept-all', 'button.close',
      '[id*="consent"] button', '[class*="consent"] button',
      'a.consent-give', '#onetrust-accept-btn-handler'
    ];
    for (const sel of selectors) {
      const btn = await page.$(sel);
      if (btn) {
        await Promise.all([
          page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 8000 }).catch(() => {}),
          btn.click()
        ]);
        await delay(1000);
      }
    }
  } catch (e) {}
}

let browserInstance = null;
let launchPromise = null;
let pageCount = 0;

async function getBrowser() {
  if (browserInstance) {
    if (pageCount < RESTART_THRESHOLD) {
      try {
        await browserInstance.version();
        return browserInstance;
      } catch (e) {
        browserInstance = null;
      }
    } else {
      await browserInstance.close().catch(() => {});
      browserInstance = null;
      pageCount = 0;
    }
  }

  if (launchPromise) return launchPromise;

  launchPromise = (async () => {
    const token = process.env.BROWSERLESS_TOKEN;

    try {
      let browser;
      if (token && token !== 'your_token_here') {
        console.log(' ☁️ Connecting to Browserless.io...');
        browser = await puppeteerBase.connect({
          browserWSEndpoint: `wss://chrome.browserless.io?token=${token}&--disable-notifications&--stealth&timeout=60000`
        });
      } else {
        const execPath = getChromiumPath();
        console.log(`🚀 Launching local browser${proxy ? ' (with proxy)' : ''}`);

        if (!puppeteerExtra.plugins.find(p => p.name === 'stealth')) {
          puppeteerExtra.use(stealth);
        }

        const args = [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage',
          '--disable-gpu',
          '--single-process',
          '--no-zygote',
          '--disable-namespace-sandbox',
          '--disable-blink-features=AutomationControlled',
          '--disable-infobars',
          '--window-position=0,0',
          '--ignore-certifcate-errors',
          '--ignore-certifcate-errors-spki-list',
          '--disable-extensions',
          '--no-first-run',
          '--no-default-browser-check',
          '--disable-software-rasterizer',
          '--disable-background-networking',
          '--disable-default-apps',
          '--disable-sync'
        ];

        if (proxy && proxy.server) {
          args.push(`--proxy-server=${proxy.server}`);
        }

        browser = await puppeteerExtra.launch({
          headless: "new",
          executablePath: execPath,
          args: args,
          ignoreHTTPSErrors: true,
          userDataDir: userDataDir
        });
      }

      browserInstance = browser;
      browser.on('disconnected', () => {
        browserInstance = null;
        launchPromise = null;
        pageCount = 0;
      });
      return browser;
    } catch (err) {
      console.log(`❌ Browser start failed: ${err.message}`);
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

    if (proxy && proxy.username) {
      await page.authenticate({ username: proxy.username, password: proxy.password });
    }

    const isBrowserless = !!process.env.BROWSERLESS_TOKEN;
    if (!isBrowserless) {
      await page.setUserAgent(getRandomUA());
      await page.setViewport({ width: 1280, height: 800 });
      await page.evaluateOnNewDocument(() => {
        Object.defineProperty(navigator, 'webdriver', { get: () => false });
        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
        const getParameter = WebGLRenderingContext.prototype.getParameter;
        WebGLRenderingContext.prototype.getParameter = function(parameter) {
          if (parameter === 37445) return 'Intel Open Source Technology Center';
          if (parameter === 37446) return 'Mesa DRI Intel(R) HD Graphics 520 (Skylake GT2)';
          return getParameter.apply(this, arguments);
        };
      });
    }
    return await fn(page);
  } catch (err) {
    console.error(` ❌ [withPage] error: ${err.message}`);
    throw err;
  } finally {
    if (page) await page.close().catch(() => {});
  }
}

async function scrapeGeneric(page, url, name) {
  console.log(` 🌐 [${name}] searching...`);
  let retries = 3; // Increased retries for Termux
  while (retries > 0) {
    try {
      if (page.isClosed()) throw new Error('Target closed');

      // Randomized delay before start to avoid concurrent bursts
      await delay(2000 + Math.random() * 3000);

      // Set consistent headers
      await page.setExtraHTTPHeaders({
        'Accept-Language': 'en-US,en;q=0.9',
        'Cache-Control': 'no-cache',
        'Upgrade-Insecure-Requests': '1'
      });

      // Primary navigation
      const waitCondition = (name === 'Google Master' || name === 'Baidu') ? 'networkidle2' : 'domcontentloaded';
      await page.goto(url, { waitUntil: waitCondition, timeout: 60000 }).catch(async (e) => {
          console.log(` ℹ️ [${name}] primary wait timed out, attempting extraction anyway`);
      });
      await delay(6000);

      if (page.isClosed()) throw new Error('Target closed');

      let title = await page.title().catch(() => '');
      let content = await page.content().catch(() => '');
      let finalUrl = page.url();

      if (isBlockedContent(content, title) || finalUrl.includes('sorry/index') || finalUrl.includes('checkcaptcha') || finalUrl.includes('verification') || finalUrl.includes('showcaptcha')) {
        console.log(` ⚠️ [${name}] block/challenge detected. Retrying with alternate strategy...`);

        let retryUrl = url;
        if (name === 'Google Master') {
          const imgUrl = new URL(url).searchParams.get('url');
          retryUrl = `https://www.google.com/searchbyimage?image_url=${encodeURIComponent(imgUrl)}&encoded_image=&image_content=&filename=&hl=en&authuser=0`;
        } else if (name === 'Yandex') {
          retryUrl = url.replace('yandex.com', 'yandex.ru').replace('rpt=imageview', 'rpt=imageview&lr=213');
        } else if (name === 'Bing') {
          retryUrl = url + '&cc=US';
        }

        if (page.isClosed()) throw new Error('Target closed');
        await page.setUserAgent(getRandomUA());
        await page.goto(retryUrl, { waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {});
        await delay(8000);

        content = await page.content().catch(() => '');
        finalUrl = await page.url();
        if (isBlockedContent(content) || finalUrl.includes('sorry/index')) {
          console.log(` ❌ [${name}] hard block detected. Switching to passive scan.`);
          return { items: [], blocked: true };
        }
      }

      await handleConsents(page);
      await humanScroll(page);
      if (Math.random() > 0.3) await humanMouse(page);
      await delay(4000);

      const selectorTimeout = 20000;
      try {
        if (page.isClosed()) throw new Error('Target closed');
        if (name === 'Yandex') await page.waitForSelector('.cbir-item, .serp-item, .CbirItem, .serp-list', { timeout: selectorTimeout });
        else if (name === 'Google Master') await page.waitForSelector('.Luz2Q, .V6bBh, [data-is-vsc], .UA07L, .G6S96, [role="listitem"]', { timeout: selectorTimeout });
        else if (name === 'Bing') await page.waitForSelector('.imgpt, .iusc, .visual_search_results, .vsc_link', { timeout: selectorTimeout });
        else if (name === 'Baidu') await page.waitForSelector('.image-content, .general-item, .item-container', { timeout: selectorTimeout });
      } catch (e) {}

      if (page.isClosed()) throw new Error('Target closed');

      // Robust extraction loop to handle "Detached Frame"
      let items = [];
      try {
        items = await page.evaluate((engineName) => {
          const out = [];
          const seen = new Set();
          const selectors = [
            '.cbir-item__title a', '.cbir-item a', '.serp-item__link', '.CbirItem a', '.CbirSection-Items a',
            '.V6bBh', 'a.Luz2Q', '.UA07L a', '.G6S96 a', '[role="listitem"] a', 'a[data-visual-matches]',
            '.imgpt a', '.iusc', '.visual_search_results a', '.vsc_link', '.vsc_title a', '.is-vsc-link', '.richImgLnk a',
            '.b_visualSearch a', 'a[aria-label*="Result"]', 'a[href*="/imgres"]', '.mitem a', 'a[href*="google.com/url?q="]',
            '.general-item a', '.item-container a', '.image-content a'
          ];

          document.querySelectorAll(selectors.join(',')).forEach((el) => {
            const a = el.tagName === 'A' ? el : el.closest('a');
            if (!a || !a.href) return;
            try {
              let href = a.href.split('#')[0];
              if (href.includes('google.com/url?q=')) {
                  const u = new URL(href);
                  href = u.searchParams.get('q') || href;
              }
              if (seen.has(href) || !href.startsWith('http')) return;
              const isEngine = href.includes('google.com/search') || href.includes('yandex.ru') || href.includes('yandex.com') || href.includes('bing.com') || href.includes('baidu.com') || href.includes('microsoft.com') || href.includes('gstatic.com');
              if (isEngine) return;
              seen.add(href);
              let text = (a.innerText || a.getAttribute('aria-label') || a.title || 'Visual Match').trim();
              if (text.length < 2) text = 'Visual Match';
              out.push({ title: text, link: href, source: engineName, score: 400 });
            } catch(e) {}
          });
          return out;
        }, name);
      } catch (evalError) {
        if (evalError.message.includes('detached Frame') || evalError.message.includes('Execution context was destroyed')) throw evalError;
        console.log(` ⚠ [${name}] extraction error: ${evalError.message}`);
      }

      return { items: items || [], blocked: false };
    } catch (e) {
      if (e.message.includes('detached Frame') || e.message.includes('navigating') || e.message.includes('Target closed') || e.message.includes('Execution context was destroyed')) {
        console.log(` ⚠ [${name}] session error: ${e.message}. Retrying (${retries - 1} left)...`);
        retries--;
        await delay(5000);
        continue;
      }
      console.log(` ⚠ [${name}] error: ${e.message}`);
      return { items: [], blocked: false, error: e.message };
    }
  }
  return { items: [], blocked: false, error: 'Engine failed after retries' };
}

async function scrapeSocialDorks(keyword, sites) {
  const results = [];
  for (const site of sites) {
    try {
      const items = await withPage(async (page) => {
        const query = `site:${site} "${keyword}"`;
        const url = `https://www.bing.com/search?q=${encodeURIComponent(query)}`;
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        return page.evaluate((source) => {
          const out = [];
          document.querySelectorAll('li.b_algo h2 a').forEach((a) => {
            out.push({ title: a.innerText, link: a.href, source, score: 250 });
          });
          return out;
        }, 'Bing');
      });
      results.push(...items);
    } catch (e) {}
  }
  return results;
}

app.post('/api/search', async (req, res) => {
  const { imageUrl, localFaceUrl, searchMode, keywordHint } = req.body;
  const visualProbeUrl = imageUrl || localFaceUrl;
  if (!visualProbeUrl) return res.status(400).json({ error: 'No image URL' });

  console.log(`🚀 Starting search for: ${visualProbeUrl.slice(0, 50)}...`);
  const matches = [];

  const runEngine = async (name, url) => {
    try {
      const { items, blocked } = await withPage((page) => scrapeGeneric(page, url, name));
      if (items && items.length > 0) {
        console.log(`✅ [${name}] found ${items.length} matches`);
        matches.push(...items);
      } else if (blocked) {
        console.log(`❌ [${name}] blocked by bot detection`);
      } else {
        console.log(`ℹ️ [${name}] no matches found`);
      }
    } catch (e) {
      console.log(`⚠️ [${name}] execution failed: ${e.message}`);
    }
  };

  const yandexUrl = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(visualProbeUrl)}`;
  const bingUrl = `https://www.bing.com/visualsearch/Microsoft/Result?imgurl=${encodeURIComponent(visualProbeUrl)}`;
  const googleUrl = `https://lens.google.com/uploadbyurl?url=${encodeURIComponent(visualProbeUrl)}`;
  const baiduUrl = `https://graph.baidu.com/pcpage/index?tpl_from=pc&image=${encodeURIComponent(visualProbeUrl)}`;
  const tineyeUrl = `https://tineye.com/search?url=${encodeURIComponent(visualProbeUrl)}`;

  const engines = [
    { name: 'Bing', url: bingUrl },
    { name: 'Yandex', url: yandexUrl },
    { name: 'Google Master', url: googleUrl },
    { name: 'Baidu', url: baiduUrl },
    { name: 'TinEye', url: tineyeUrl }
  ];

  if (isTermux()) {
    console.log(' 📱 Termux detected: Running engines sequentially to save memory');
    for (const engine of engines) {
      await runEngine(engine.name, engine.url);
      // Extra delay between engines on Termux
      await delay(3000);
    }
  } else {
    // Run all engines in parallel for maximum speed on desktop/server
    await Promise.allSettled(engines.map(e => runEngine(e.name, e.url)));
  }

  if (keywordHint) {
    try {
      const dorks = await scrapeSocialDorks(keywordHint, ['instagram.com', 'facebook.com', 'twitter.com']);
      if (dorks) matches.push(...dorks);
    } catch (e) {
      console.log(`⚠️ [Social Dorks] failed: ${e.message}`);
    }
  }

  console.log(`🏁 Search completed. Total matches: ${matches.length}`);
  res.json({ success: true, matches: matches.slice(0, 50) });
});

app.get('/api/ping', (req, res) => res.json({ status: 'pong' }));

app.listen(PORT, '0.0.0.0', () => {
  console.log(`⚡ BACKEND running on port ${PORT}`);
});
