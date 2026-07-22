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
    // Aggressive 15-second timeout - forces super fast response
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.json({ success: false, error: "Search timeout", matches: [] });
        }
    }, 15000);

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

        // =============================================
        // ONLY YANDEX - SUPER FAST (2 scrolls)
        // =============================================
        console.log("🔍 Querying Yandex (fast mode)...");
        try {
            const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 5000 });
            await delay(800);

            // Only 2 scrolls
            for (let i = 0; i < 2; i++) {
                await page.evaluate(() => window.scrollBy(0, window.innerHeight));
                await delay(400);
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
                        const title = el.textContent.trim().slice(0, 100) || 'Visual Match';
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

            clearTimeout(timeout);
            isFinished = true;
            res.json({ success: true, matches: results.slice(0, 10) });

        } catch (e) {
            console.log(`⚠️ Yandex error: ${e.message}`);
            clearTimeout(timeout);
            if (!isFinished && !res.headersSent) {
                isFinished = true;
                res.json({ success: false, error: e.message, matches: [] });
            }
        }

    } catch (error) {
        console.log(`⚠️ Error: ${error.message}`);
        clearTimeout(timeout);
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.json({ success: false, error: error.message, matches: [] });
        }
    } finally {
        if (browser) await browser.close().catch(() => {});
    }
});

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`⚡ ULTRA-FAST scraper online on port ${PORT}`);
});