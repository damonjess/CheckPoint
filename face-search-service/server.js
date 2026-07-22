const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json());

const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

app.post('/api/search', async (req, res) => {
    const { imageUrl, keywordHint } = req.body;
    console.log(`📸 Search: ${imageUrl} | Hint: ${keywordHint || 'None'}`);

    if (!imageUrl) {
        return res.status(400).json({ error: 'Missing imageUrl' });
    }

    let browser;
    let isFinished = false;
    // Reduced timeout to 25 seconds - forces faster response
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.status(504).json({ success: false, error: "Search timeout", matches: [] });
        }
    }, 25000);

    try {
        console.log("🚀 Launching browser...");
        browser = await puppeteer.launch({
            headless: true,
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser',
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
                '--single-process'
            ]
        });

        const page = await browser.newPage();
        await page.setViewport({ width: 1920, height: 1080 });

        const allResults = [];

        // =============================================
        // ONLY YANDEX (FAST)
        // =============================================
        console.log("🔍 Querying Yandex...");
        try {
            const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 8000 });
            await delay(1000);

            for (let i = 0; i < 3; i++) {
                await page.evaluate(() => window.scrollBy(0, window.innerHeight));
                await delay(500);
            }

            const results = await page.evaluate(() => {
                const items = [];
                const seen = new Set();
                document.querySelectorAll('.serp-item, .CbirSites-Item, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        const href = linkEl ? linkEl.href : '';
                        const imgEl = el.querySelector('img');
                        let imgSrc = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                        const title = el.textContent.trim().slice(0, 150) || 'Visual Match';
                        let source = 'Yandex';
                        let isSocial = false;
                        const domains = ['facebook.com', 'instagram.com', 'linkedin.com', 'twitter.com', 'vk.com'];
                        for (const d of domains) {
                            if (href.includes(d)) { source = d.split('.')[0]; isSocial = true; break; }
                        }
                        if (href && !seen.has(href) && !href.includes('yandex.com')) {
                            seen.add(href);
                            items.push({ title, link: href, thumbnail: imgSrc, source, isSocial });
                        }
                    } catch(e) {}
                });
                return items;
            });

            console.log(`✓ Yandex: ${results.length} results`);
            allResults.push(...results);
        } catch (e) {
            console.log(`⚠️ Yandex error: ${e.message}`);
        }

        // =============================================
        // ONLY TINEYE (FAST)
        // =============================================
        console.log("🔍 Querying TinEye...");
        try {
            await page.goto(`https://tineye.com/search?url=${encodeURIComponent(imageUrl)}`, { waitUntil: 'domcontentloaded', timeout: 8000 });
            await delay(1000);

            for (let i = 0; i < 2; i++) {
                await page.evaluate(() => window.scrollBy(0, window.innerHeight));
                await delay(500);
            }

            const results = await page.evaluate(() => {
                const items = [];
                const seen = new Set();
                document.querySelectorAll('.match, .result, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        const href = linkEl ? linkEl.href : '';
                        const imgEl = el.querySelector('img');
                        let imgSrc = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                        const title = el.textContent.trim().slice(0, 150) || 'TinEye Match';
                        let source = 'TinEye';
                        let isSocial = false;
                        const domains = ['facebook.com', 'instagram.com', 'linkedin.com', 'twitter.com', 'vk.com'];
                        for (const d of domains) {
                            if (href.includes(d)) { source = d.split('.')[0]; isSocial = true; break; }
                        }
                        if (href && !seen.has(href) && !href.includes('tineye.com')) {
                            seen.add(href);
                            items.push({ title, link: href, thumbnail: imgSrc, source, isSocial });
                        }
                    } catch(e) {}
                });
                return items;
            });

            console.log(`✓ TinEye: ${results.length} results`);
            allResults.push(...results);
        } catch (e) {
            console.log(`⚠️ TinEye error: ${e.message}`);
        }

        // Deduplicate
        const seen = new Set();
        const uniqueResults = [];
        for (const item of allResults) {
            const key = item.link || `${item.title}_${item.source}`;
            if (!seen.has(key)) {
                seen.add(key);
                uniqueResults.push(item);
            }
        }

        console.log(`📊 Raw: ${allResults.length} | Unique: ${uniqueResults.length}`);
        console.log(`✅ Final: ${uniqueResults.length} matches`);

        clearTimeout(timeout);
        isFinished = true;
        res.json({ success: true, matches: uniqueResults.slice(0, 30) });

    } catch (error) {
        console.log(`⚠️ Error: ${error.message}`);
        clearTimeout(timeout);
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.status(500).json({ success: false, error: error.message, matches: [] });
        }
    } finally {
        if (browser) await browser.close().catch(() => {});
    }
});

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`🕵️ FAST Stealth scraper online on port ${PORT}`);
});