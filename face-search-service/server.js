const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json());

const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

app.get('/ping', (req, res) => {
    res.json({ status: "online", engines: ["yandex", "bing"] });
});

app.post('/api/search', async (req, res) => {
    const { imageUrl, keywordHint } = req.body;
    console.log(`📸 Search: ${imageUrl} | Hint: ${keywordHint || 'None'}`);

    if (!imageUrl) {
        return res.status(400).json({ error: 'Missing imageUrl' });
    }

    let browser;
    let isFinished = false;
    // 40-second timeout - Bing/Yandex together take more time
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.json({ success: false, error: "Search timeout", matches: [] });
        }
    }, 40000);

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

        const allMatches = [];

        // =============================================
        // 1. YANDEX (fast mode)
        // =============================================
        console.log("🔍 Querying Yandex...");
        try {
            const yandexUrl = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
            await page.goto(yandexUrl, { waitUntil: 'domcontentloaded', timeout: 8000 });
            await delay(500);

            const yandexResults = await page.evaluate(() => {
                const items = [];
                const seen = new Set();
                document.querySelectorAll('.serp-item, .CbirSites-Item, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        const href = linkEl ? linkEl.href : '';
                        if (!href || seen.has(href) || href.includes('yandex.')) return;

                        seen.add(href);
                        const imgEl = el.querySelector('img');
                        const imgSrc = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                        const title = el.textContent.trim().slice(0, 100) || 'Visual Match';
                        items.push({ title, link: href, thumbnail: imgSrc, source: 'Yandex' });
                    } catch(e) {}
                });
                return items;
            });
            console.log(`✓ Yandex: ${yandexResults.length} found`);
            allMatches.push(...yandexResults.slice(0, 15));
        } catch (e) {
            console.log(`⚠️ Yandex error: ${e.message}`);
        }

        // =============================================
        // 2. BING (fast mode)
        // =============================================
        console.log("🔍 Querying Bing Visual Search...");
        try {
            // Bing often works better with detailv2 URL for image uploads
            const bingUrl = `https://www.bing.com/images/search?view=detailv2&iss=sbi&FORM=IRSBIQ&redirecturl=${encodeURIComponent(imageUrl)}`;
            await page.goto(bingUrl, { waitUntil: 'domcontentloaded', timeout: 10000 });
            await delay(1000);

            const bingResults = await page.evaluate(() => {
                const items = [];
                const seen = new Set();
                // Bing results are often in .rich_img_grid or similar
                document.querySelectorAll('.is_m, .is_v, .ovr, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        const href = linkEl ? linkEl.href : '';
                        if (!href || seen.has(href) || href.includes('bing.') || href.includes('microsoft.')) return;

                        seen.add(href);
                        const title = el.textContent.trim().slice(0, 80) || 'Bing Match';
                        const imgEl = el.querySelector('img');
                        const imgSrc = imgEl ? (imgEl.src || '') : '';
                        items.push({ title, link: href, thumbnail: imgSrc, source: 'Bing' });
                    } catch(e) {}
                });
                return items;
            });
            console.log(`✓ Bing: ${bingResults.length} found`);
            allMatches.push(...bingResults.slice(0, 15));
        } catch (e) {
            console.log(`⚠️ Bing error: ${e.message}`);
        }

        clearTimeout(timeout);
        isFinished = true;

        // Final response
        res.json({
            success: true,
            matches: allMatches.sort((a, b) => b.isSocial - a.isSocial).slice(0, 40)
        });

    } catch (error) {
        console.log(`⚠️ Scraper Error: ${error.message}`);
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
    console.log(`⚡ MULTI-ENGINE scraper online on port ${PORT}`);
});
