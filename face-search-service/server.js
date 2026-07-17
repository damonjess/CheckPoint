const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json());

app.post('/api/search', async (req, res) => {
    const { imageUrl, keywordHint } = req.body;
    console.log(`Received search request for URL: ${imageUrl}${keywordHint ? ` with hint: ${keywordHint}` : ''}`);

    if (!imageUrl) {
        return res.status(400).json({ error: 'Missing imageUrl parameter' });
    }

    let browser;
    // Set up a master safety timeout to kill the request if it stalls longer than 15 seconds
    const safetyTimeout = setTimeout(() => {
        console.log("⚠ SYSTEM CRITICAL: Request reached safety execution limit. Forcing termination.");
        if (!res.headersSent) {
            res.status(504).json({ success: false, error: "Upstream engine timeout. Connection stalled." });
        }
    }, 15000);

    try {
        console.log("→ Launching mobile browser wrapper...");
        browser = await puppeteer.launch({
            headless: true, // Termux must run headless
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser', // Direct path for Termux
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
            ]
        });

        const page = await browser.newPage();

        // Enforce basic network constraints directly on the page context
        await page.setDefaultNavigationTimeout(8000);
        await page.setDefaultTimeout(8000);

        const socialDomains = "site:instagram.com OR site:facebook.com OR site:linkedin.com OR site:twitter.com OR site:vk.com OR site:ok.ru OR site:tiktok.com";

        // Build a targeted query: Name + Social Networks + Reverse Image
        let finalQuery = socialDomains;
        if (keywordHint && keywordHint.trim() !== "") {
            finalQuery = `"${keywordHint.trim()}" (${socialDomains})`;
        }

        const targetUrl = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}&text=${encodeURIComponent(finalQuery)}`;
        console.log(`→ Navigating to engine portal...`);

        // Use 'domcontentloaded' instead of 'networkidle2' so it doesn't hang on hidden tracking scripts
        await page.goto(targetUrl, { waitUntil: 'domcontentloaded' });
        console.log("→ Page layout received. Analyzing source structures...");

        const bodyHTML = await page.content();

        if (bodyHTML.includes("captcha") || bodyHTML.includes("captcha-wrapper") || bodyHTML.length < 5000) {
            console.log("⚠ Request flagged or dropped by automated bot firewall.");
            clearTimeout(safetyTimeout);
            return res.json({ success: false, error: "Bot detection wall encountered", matches: [] });
        }

        // Robust, layout-agnostic DOM Extraction Layer
        const results = await page.evaluate(() => {
            const items = [];
            const allLinks = Array.from(document.querySelectorAll('a[href^="http"]'));

            // List of target platforms we want to prioritize
            const socialDomains = ['vk.com', 'ok.ru', 'facebook.com', 'instagram.com', 'linkedin.com', 'twitter.com', 't.me'];

            allLinks.forEach(linkEl => {
                const href = linkEl.href;
                if (href.includes('yandex.com') || href.includes('google.com') || href.includes('bing.com')) {
                    return;
                }

                const imgEl = linkEl.querySelector('img') ||
                              linkEl.parentElement?.querySelector('img') ||
                              document.querySelector(`img[src*="${linkEl.hostname}"]`);

                let titleText = linkEl.innerText?.replace(/\n/g, ' ')?.trim() || linkEl.title?.trim();

                // Pull deep lazy-loaded image sources if standard src is missing or a tiny spacer 1x1 gif
                let finalThumb = imgEl.src || '';
                if (finalThumb.includes('data:image/gif;base64') || finalThumb.length < 100) {
                    finalThumb = imgEl.getAttribute('data-src') || imgEl.getAttribute('lazy-src') || imgEl.src || '';
                }

                // If still empty, try to find the largest image in the parent container
                if (!finalThumb || finalThumb.length < 50) {
                    const siblingImgs = Array.from(linkEl.parentElement?.querySelectorAll('img') || []);
                    for (const sImg of siblingImgs) {
                        const sSrc = sImg.getAttribute('data-src') || sImg.src;
                        if (sSrc && sSrc.length > 50) {
                            finalThumb = sSrc;
                            break;
                        }
                    }
                }

                // Check if this specific link belongs to a social media network
                const isSocial = socialDomains.some(domain => href.toLowerCase().includes(domain));

                let sourceLabel = 'Web Index';
                if (isSocial) {
                    // Label it perfectly based on the platform found
                    if (href.includes('vk.com')) sourceLabel = 'VKontakte';
                    else if (href.includes('instagram.com')) sourceLabel = 'Instagram';
                    else if (href.includes('facebook.com')) sourceLabel = 'Facebook';
                    else sourceLabel = 'Social Profile';
                }

                // Emergency clean-up: If it's a social profile link but title failed, make it clear
                if (isSocial && (!titleText || titleText.length < 3)) {
                    titleText = `View ${sourceLabel} Profile`;
                }

                if (href && !items.some(item => item.link === href)) {
                    // Filter out standard resolution strings (e.g., 480×640) from being used as titles
                    if (titleText && /^\d+\s*[×x]\s*\d+$/i.test(titleText.trim())) {
                        titleText = "Visual Match Profile";
                    }

                    items.push({
                        title: titleText || 'Visual Match Connection',
                        link: href,
                        thumbnail: finalThumb,
                        source: sourceLabel,
                        isPriority: isSocial // Flag it to sort later
                    });
                }
            });

            // Strategy 2: If standard anchors yield nothing, fall back to capturing broad page components
            if (items.length === 0) {
                const genericNodes = document.querySelectorAll('.CbirSites-Item, .cbir-similar__item, [class*="similar"], [class*="item"]');
                genericNodes.forEach(node => {
                    const a = node.querySelector('a');
                    const img = node.querySelector('img');
                    if (a && a.href && !a.href.includes(window.location.hostname)) {
                        let titleText = node.innerText?.split('\n')[0]?.trim();

                        if (titleText && /^\d+\s*[×x]\s*\d+$/i.test(titleText.trim())) {
                            titleText = "Visual Lead";
                        }

                        items.push({
                            title: titleText || 'Visual Lead',
                            link: a.href,
                            thumbnail: img ? img.src : '',
                            source: 'Web Index',
                            isPriority: false
                        });
                    }
                });
            }

            // Sort results: place confirmed social media profiles at the top of the list
            return items
                .sort((a, b) => (b.isPriority ? 1 : 0) - (a.isPriority ? 1 : 0))
                .slice(0, 30);
        });

        console.log(`✓ Process complete. Extracted [${results.length}] matching records.`);
        clearTimeout(safetyTimeout);

        if (!res.headersSent) {
            res.json({ success: true, matches: results });
        }

    } catch (error) {
        console.log(`⚠ Operational Exception: ${error.message}`);
        clearTimeout(safetyTimeout);
        if (!res.headersSent) {
            res.status(500).json({ success: false, error: error.message, matches: [] });
        }
    } finally {
        if (browser) {
            console.log("→ Disposing browser allocation state.");
            await browser.close().catch(() => {});
        }
    }
});

const PORT = 3000;
app.listen(PORT, () => console.log(`Stealth scraper backend online on port ${PORT}`));
