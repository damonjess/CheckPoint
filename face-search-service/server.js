const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json());

// ========== UTILS ==========
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function safePageGoto(page, url, options = { waitUntil: 'load', timeout: 20000 }) {
    let retries = 2;
    while (retries > 0) {
        try {
            await page.goto(url, options);
            return true;
        } catch (e) {
            if (e.message.includes('detached') || e.message.includes('Navigation timeout')) {
                console.log(`   🔄 Retrying navigation (${retries} left)...`);
                await delay(2000);
                retries--;
            } else {
                throw e;
            }
        }
    }
    return false;
}

const SOCIAL_DOMAINS = {
    'facebook.com': 'Facebook', 'fb.com': 'Facebook',
    'instagram.com': 'Instagram', 'instagr.am': 'Instagram',
    'linkedin.com': 'LinkedIn',
    'twitter.com': 'Twitter', 'x.com': 'Twitter',
    'vk.com': 'VKontakte', 'vkontakte.ru': 'VKontakte',
    'tiktok.com': 'TikTok',
    'youtube.com': 'YouTube', 'youtu.be': 'YouTube',
    'pinterest.com': 'Pinterest',
    'reddit.com': 'Reddit',
    'github.com': 'GitHub',
    'medium.com': 'Medium',
    'quora.com': 'Quora',
    't.me': 'Telegram', 'telegram.me': 'Telegram',
    'twitch.tv': 'Twitch',
    'snapchat.com': 'Snapchat',
    'discord.com': 'Discord', 'discord.gg': 'Discord',
    'gitlab.com': 'GitLab',
    'stackoverflow.com': 'Stack Overflow',
    'dev.to': 'Dev.to',
    'ok.ru': 'Odnoklassniki',
    'weibo.com': 'Weibo',
    'douyin.com': 'Douyin',
    'xiaohongshu.com': 'Xiaohongshu',
    'threads.net': 'Threads',
    'bsky.app': 'Bluesky'
};

// ========== GOOGLE DORKING ==========
async function dorkSocialProfiles(page, keywordHint) {
    const results = [];
    if (!keywordHint || keywordHint.length < 3) return results;

    const lowerHint = keywordHint.toLowerCase();
    let targetSites = Object.keys(SOCIAL_DOMAINS);
    let queryModifiers = '';

    // If hint points to a specific platform, narrow down
    if (lowerHint.includes('instagram')) {
        targetSites = ['instagram.com'];
        queryModifiers = '"@"';
    } else if (lowerHint.includes('facebook')) {
        targetSites = ['facebook.com'];
        queryModifiers = '"profile" OR "people"';
    } else if (lowerHint.includes('linkedin')) {
        targetSites = ['linkedin.com/in'];
        queryModifiers = '"linkedin.com/in/"';
    } else if (lowerHint.includes('twitter') || lowerHint.includes('x.com')) {
        targetSites = ['twitter.com', 'x.com'];
        queryModifiers = '"@"';
    } else if (lowerHint.includes('github')) {
        targetSites = ['github.com'];
        queryModifiers = '"github.com/"';
    } else if (lowerHint.includes('tiktok')) {
        targetSites = ['tiktok.com'];
        queryModifiers = '"@"';
    } else if (lowerHint.includes('telegram')) {
        targetSites = ['t.me'];
        queryModifiers = '"t.me/"';
    }

    // Split into chunks of 6 sites to avoid Google query length limits
    const chunkSize = 6;
    for (let i = 0; i < targetSites.length; i += chunkSize) {
        const chunk = targetSites.slice(i, i + chunkSize);
        const siteClause = chunk.map(s => `site:${s}`).join(' OR ');
        const masterQuery = `(${siteClause}) "${keywordHint}" ${queryModifiers}`;

        console.log(`  🔍 Dorking chunk ${Math.floor(i/chunkSize) + 1} for: ${keywordHint}`);

        try {
            const url = `https://www.google.com/search?q=${encodeURIComponent(masterQuery)}&num=100`;
            await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');

            const success = await safePageGoto(page, url, { waitUntil: 'domcontentloaded', timeout: 15000 });
            if (!success) continue;

            // Fix the selector error: use standard CSS selectors or evaluate
            const consentBtn = await page.evaluateHandle(() => {
                const buttons = Array.from(document.querySelectorAll('button'));
                return buttons.find(b => b.innerText.includes('Accept all') || b.innerText.includes('I agree')) || null;
            });

            if (consentBtn.asElement()) {
                await consentBtn.asElement().click();
                await delay(1000);
            }

            const pageResults = await page.evaluate((socialDomains) => {
                const items = [];
                const seen = new Set();
                const socialMap = JSON.parse(socialDomains);

                document.querySelectorAll('div.g, a[href^="http"]').forEach(el => {
                    try {
                        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        if (!linkEl) return;
                        const href = linkEl.href;
                        const titleEl = el.querySelector('h3') || el;
                        const title = titleEl.textContent.trim().slice(0, 150);

                        if (!href || href.includes('google.com') || seen.has(href)) return;

                        let source = 'Web';
                        let isSocial = false;
                        for (const [domain, name] of Object.entries(socialMap)) {
                            if (href.includes(domain)) { source = name; isSocial = true; break; }
                        }
                        if (!isSocial) return;

                        let score = 100;
                        if (href.includes('/in/') || href.includes('/profile') || href.includes('/people/') || href.includes('/@')) score += 50;

                        seen.add(href);
                        items.push({ title: title || `${source} Profile`, link: href, thumbnail: '', source, isSocial, score });
                    } catch(e) {}
                });
                return items;
            }, JSON.stringify(SOCIAL_DOMAINS));

            results.push(...pageResults);
            await delay(1000); // Small pause between chunks
        } catch (e) {
            console.log(`    ⚠️ Google chunk error: ${e.message}`);
        }
    }

    console.log(`    ✓ Total Google Master results: ${results.length} leads found`);
    return results;
}

// ========== MAIN SEARCH ENDPOINT ==========
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
    }, 60000);

    try {
        browser = await puppeteer.launch({
            headless: true,
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser',
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--single-process',
                '--disable-gpu',
                '--no-zygote',
                '--disable-extensions'
            ]
        });

        const allResults = [];
        const pages = [];

        async function getPage() {
            const page = await browser.newPage();
            await page.setViewport({ width: 1280, height: 720 });
            await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
            pages.push(page);
            return page;
        }

        // ========== ENGINE 1: YANDEX ==========
        const yandexTask = async () => {
            const page = await getPage();
            try {
                console.log("🔍 Querying Yandex...");
                const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
                await safePageGoto(page, url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                await delay(2000);
                for (let i = 0; i < 3; i++) {
                    await page.evaluate(() => window.scrollBy(0, 500));
                    await delay(500);
                }

                const results = await page.evaluate((socialDomains) => {
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
                            items.push({ title, link: href, thumbnail: imgSrc, source, isSocial, score: isSocial ? 150 : 50 });
                        } catch(e) {}
                    });
                    return items;
                }, JSON.stringify(SOCIAL_DOMAINS));

                console.log(`   ✓ Yandex: ${results.length} results`);
                return results;
            } catch (e) {
                console.log(`   ⚠️ Yandex error: ${e.message}`);
                return [];
            }
        };

        // ========== ENGINE 2: BING ==========
        const bingTask = async () => {
            const page = await getPage();
            try {
                console.log("🔍 Querying Bing Visual...");
                const url = `https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&sbisrc=UrlPaste&q=imgurl:${encodeURIComponent(imageUrl)}&imgurl=${encodeURIComponent(imageUrl)}`;
                await safePageGoto(page, url, { waitUntil: 'load', timeout: 15000 });
                await delay(2500);

                const results = await page.evaluate((socialDomains) => {
                    const items = [];
                    const seen = new Set();
                    const socialMap = JSON.parse(socialDomains);

                    document.querySelectorAll('.imgpt, a[href^="http"]').forEach(el => {
                        try {
                            const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                            const href = linkEl ? linkEl.href : '';
                            if (!href || seen.has(href) || href.includes('bing.com')) return;

                            const imgEl = el.querySelector('img');
                            let imgSrc = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                            const title = el.getAttribute('aria-label') || el.textContent.trim().slice(0, 100) || 'Visual Match';

                            let source = 'Bing';
                            let isSocial = false;
                            for (const [domain, name] of Object.entries(socialMap)) {
                                if (href.includes(domain)) { source = name; isSocial = true; break; }
                            }

                            seen.add(href);
                            items.push({ title, link: href, thumbnail: imgSrc, source, isSocial, score: isSocial ? 140 : 45 });
                        } catch(e) {}
                    });
                    return items;
                }, JSON.stringify(SOCIAL_DOMAINS));

                console.log(`   ✓ Bing: ${results.length} results`);
                return results;
            } catch (e) {
                console.log(`   ⚠️ Bing error: ${e.message}`);
                return [];
            }
        };

        // ========== ENGINE 3: TINEYE ==========
        const tineyeTask = async () => {
            const page = await getPage();
            try {
                console.log("🔍 Querying TinEye...");
                const url = `https://tineye.com/search?url=${encodeURIComponent(imageUrl)}`;
                await safePageGoto(page, url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                await delay(2000);

                const results = await page.evaluate((socialDomains) => {
                    const items = [];
                    const seen = new Set();
                    const socialMap = JSON.parse(socialDomains);

                    document.querySelectorAll('.match, a[href^="http"]').forEach(el => {
                        try {
                            const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                            const href = linkEl ? linkEl.href : '';
                            if (!href || seen.has(href) || href.includes('tineye.com')) return;

                            const imgEl = el.querySelector('img');
                            let imgSrc = imgEl ? (imgEl.src || '') : '';
                            const title = el.textContent.trim().slice(0, 100) || 'TinEye Match';

                            let source = 'TinEye';
                            let isSocial = false;
                            for (const [domain, name] of Object.entries(socialMap)) {
                                if (href.includes(domain)) { source = name; isSocial = true; break; }
                            }

                            seen.add(href);
                            items.push({ title, link: href, thumbnail: imgSrc, source, isSocial, score: isSocial ? 130 : 40 });
                        } catch(e) {}
                    });
                    return items;
                }, JSON.stringify(SOCIAL_DOMAINS));

                console.log(`   ✓ TinEye: ${results.length} results`);
                return results;
            } catch (e) {
                console.log(`   ⚠️ TinEye error: ${e.message}`);
                return [];
            }
        };

        // ========== ENGINE 4: BAIDU ==========
        const baiduTask = async () => {
            const page = await getPage();
            try {
                console.log("🔍 Querying Baidu...");
                const url = `https://graph.baidu.com/s?sign=&wd=&f=general&tn=wise&from=index&word=&rn=60&pageFrom=graph_upload_bdbox&page=&range=&sort=&query=&extUiData%5BisLogoShow%5D=1&isLogoShow=1&showType=showNormal&ua=&image=&image=${encodeURIComponent(imageUrl)}&filename=&simid=&cs=&os=&`;
                await safePageGoto(page, url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                await delay(2500);

                const results = await page.evaluate((socialDomains) => {
                    const items = [];
                    const seen = new Set();
                    const socialMap = JSON.parse(socialDomains);

                    document.querySelectorAll('a[href^="http"]').forEach(el => {
                        try {
                            const href = el.href;
                            if (!href || seen.has(href) || href.includes('baidu.com')) return;

                            const title = el.textContent.trim().slice(0, 100) || 'Baidu Match';
                            let source = 'Baidu';
                            let isSocial = false;
                            for (const [domain, name] of Object.entries(socialMap)) {
                                if (href.includes(domain)) { source = name; isSocial = true; break; }
                            }

                            seen.add(href);
                            items.push({ title, link: href, thumbnail: '', source, isSocial, score: isSocial ? 120 : 35 });
                        } catch(e) {}
                    });
                    return items;
                }, JSON.stringify(SOCIAL_DOMAINS));

                console.log(`   ✓ Baidu: ${results.length} results`);
                return results;
            } catch (e) {
                console.log(`   ⚠️ Baidu error: ${e.message}`);
                return [];
            }
        };

        // ========== ENGINE 5: GOOGLE DORKING ==========
        const dorkTask = async () => {
            if (!keywordHint || keywordHint.length < 3) return [];
            const page = await getPage();
            return dorkSocialProfiles(page, keywordHint);
        };

        // Run in batches to save memory in Termux
        const batch1 = await Promise.allSettled([yandexTask(), bingTask()]);
        const batch2 = await Promise.allSettled([tineyeTask(), baiduTask()]);
        const batch3 = await Promise.allSettled([dorkTask()]);

        [...batch1, ...batch2, ...batch3].forEach(result => {
            if (result.status === 'fulfilled') allResults.push(...result.value);
        });

        const unique = Array.from(new Map(allResults.map(item => [item.link, item])).values())
            .sort((a, b) => b.score - a.score);

        clearTimeout(timeout);
        isFinished = true;

        console.log(`🎯 Total unique results: ${unique.length}`);
        res.json({ success: true, matches: unique.slice(0, 50) });

    } catch (error) {
        console.log(`⚠️ Critical Error: ${error.message}`);
        clearTimeout(timeout);
        if (!isFinished) res.status(500).json({ success: false, error: error.message });
    } finally {
        if (browser) await browser.close().catch(() => {});
    }
});

app.get('/ping', (req, res) => res.json({ status: 'ok', service: 'face-search-scraper' }));

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`⚡ FREE SCRAPER online on port ${PORT} (Yandex | Bing | TinEye | Baidu | Google Dorking)`);
});
