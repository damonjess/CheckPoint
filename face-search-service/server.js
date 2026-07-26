const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json());

const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const SOCIAL_DOMAINS = {
    'facebook.com': 'Facebook',
    'instagram.com': 'Instagram',
    'linkedin.com': 'LinkedIn',
    'twitter.com': 'Twitter',
    'x.com': 'Twitter',
    'vk.com': 'VKontakte',
    'tiktok.com': 'TikTok',
    'youtube.com': 'YouTube',
    'pinterest.com': 'Pinterest',
    'reddit.com': 'Reddit',
    'github.com': 'GitHub',
    'medium.com': 'Medium',
    'quora.com': 'Quora',
    't.me': 'Telegram'
};

// ============================================================
// DORKING: Search GOOGLE for social profiles
// ============================================================
async function dorkSocialProfiles(page, keywordHint) {
    const results = [];
    if (!keywordHint || keywordHint.length < 3) return results;

    console.log(`  🔍 Dorking (Google Master) for: ${keywordHint}`);

    // Master Dork Query - Combined to avoid multiple hits to Google
    const masterQuery = `(site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:twitter.com OR site:github.com OR site:medium.com OR site:quora.com OR site:t.me) ${keywordHint}`;

    try {
        const url = `https://www.google.com/search?q=${encodeURIComponent(masterQuery)}&num=100`;

        // Set realistic User Agent
        await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');

        await page.goto(url, { waitUntil: 'networkidle2', timeout: 15000 });

        // Handle Google Consent Screen
        const consentSelectors = ['button[aria-label="Accept all"]', 'button:contains("Accept all")', '#L2AGLb'];
        for (const selector of consentSelectors) {
            try {
                const btn = await page.$(selector);
                if (btn) {
                    console.log("    🛡️ Handling Google Consent Screen...");
                    await btn.click();
                    await page.waitForNavigation({ waitUntil: 'networkidle2' });
                    break;
                }
            } catch (e) {}
        }

        // Wait for results
        await page.waitForSelector('a[href^="http"]', { timeout: 5000 }).catch(() => {});

        const pageResults = await page.evaluate((socialDomains) => {
            const items = [];
            const seen = new Set();
            const socialMap = JSON.parse(socialDomains);

            document.querySelectorAll('div.g, a[href^="http"]').forEach(el => {
                try {
                    const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                    if (!linkEl) return;

                    const href = linkEl.href;
                    const title = el.textContent.trim().slice(0, 150);

                    if (href.includes('google.com')) return;

                    let source = 'Web';
                    let isSocial = false;
                    for (const [domain, name] of Object.entries(socialMap)) {
                        if (href.includes(domain)) {
                            source = name;
                            isSocial = true;
                            break;
                        }
                    }

                    if (!isSocial) return;

                    if (href && !seen.has(href)) {
                        seen.add(href);
                        items.push({
                            title: title || `${source} Profile`,
                            link: href,
                            thumbnail: '',
                            source: source,
                            isSocial: true,
                            score: 100
                        });
                    }
                } catch(e) {}
            });
            return items;
        }, JSON.stringify(SOCIAL_DOMAINS));

        console.log(`    ✓ Google Master results: ${pageResults.length} leads found`);

        if (pageResults.length === 0) {
            await page.screenshot({ path: 'debug_google.png' });
        }

        results.push(...pageResults);
    } catch (e) {
        console.log(`    ⚠️ Google dork error: ${e.message}`);
    }

    return results;
}

app.post('/api/search', async (req, res) => {
    const { imageUrl, keywordHint } = req.body;
    console.log(`📸 Search: ${imageUrl} | Hint: ${keywordHint || 'None'}`);

    if (!imageUrl) {
        return res.status(400).json({ error: 'Missing imageUrl' });
    }

    let browser;
    let isFinished = false;
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.status(504).json({ success: false, error: "Search timeout", matches: [] });
        }
    }, 45000);

    try {
        browser = await puppeteer.launch({
            headless: true,
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser',
            args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--single-process']
        });

        const page = await browser.newPage();
        await page.setViewport({ width: 1920, height: 1080 });

        const allResults = [];

        // 1. YANDEX IMAGE SEARCH
        console.log("🔍 Querying Yandex...");
        try {
            const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 10000 });
            await delay(1000);
            for (let i = 0; i < 4; i++) {
                await page.evaluate(() => window.scrollBy(0, window.innerHeight));
                await delay(400);
            }

            const yandexResults = await page.evaluate((socialDomains) => {
                const items = [];
                const seen = new Set();
                const socialMap = JSON.parse(socialDomains);
                document.querySelectorAll('.serp-item, .CbirSites-Item, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        const href = linkEl ? linkEl.href : '';
                        if (!href || seen.has(href) || href.includes('yandex.com')) return;

                        const imgEl = el.querySelector('img');
                        let imgSrc = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                        const title = el.textContent.trim().slice(0, 100) || 'Visual Match';

                        let source = 'Yandex';
                        let isSocial = false;
                        for (const [domain, name] of Object.entries(socialMap)) {
                            if (href.includes(domain)) { source = name; isSocial = true; break; }
                        }

                        seen.add(href);
                        items.push({ title, link: href, thumbnail: imgSrc, source, isSocial, score: isSocial ? 100 : 50 });
                    } catch(e) {}
                });
                return items;
            }, JSON.stringify(SOCIAL_DOMAINS));
            allResults.push(...yandexResults);
        } catch (e) { console.log(`⚠️ Yandex error: ${e.message}`); }

        // 2. GOOGLE DORKING
        if (keywordHint && keywordHint.length >= 3) {
            const dorkResults = await dorkSocialProfiles(page, keywordHint);
            allResults.push(...dorkResults);
        }

        // Deduplicate and return
        const unique = Array.from(new Map(allResults.map(item => [item.link, item])).values())
            .sort((a, b) => b.score - a.score);

        clearTimeout(timeout);
        isFinished = true;
        res.json({ success: true, matches: unique.slice(0, 40) });

    } catch (error) {
        console.log(`⚠️ Error: ${error.message}`);
        clearTimeout(timeout);
        if (!isFinished) res.status(500).json({ success: false, error: error.message });
    } finally {
        if (browser) await browser.close().catch(() => {});
    }
});

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`⚡ SCRAPER online on port ${PORT} (Dorking Enabled)`);
});
