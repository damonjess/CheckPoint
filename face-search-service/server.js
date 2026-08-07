const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const http = require('http');
const fs = require('fs');
const path = require('path');
const fetch = require('node-fetch');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json({ limit: '10mb' }));

const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const SOCIAL_DOMAINS = {
    'facebook.com': 'Facebook', 'fb.com': 'Facebook',
    'instagram.com': 'Instagram', 'instagr.am': 'Instagram',
    'linkedin.com': 'LinkedIn',
    'twitter.com': 'Twitter', 'x.com': 'Twitter',
    'vk.com': 'VKontakte',
    'tiktok.com': 'TikTok',
    'youtube.com': 'YouTube', 'youtu.be': 'YouTube',
    'pinterest.com': 'Pinterest',
    'reddit.com': 'Reddit',
    'github.com': 'GitHub',
    'medium.com': 'Medium',
    'quora.com': 'Quora',
    't.me': 'Telegram',
    'twitch.tv': 'Twitch',
    'threads.net': 'Threads',
    'bsky.app': 'Bluesky'
};

const SPAM_DOMAINS = [
    'znakomstva', 'znaomstva', 'dating', 'sex.', 'porn', 'xxx',
    'escort', 'bride', 'mail-order', 'russian-brides', 'dosug',
    ' intim ', 'sintim', 'erotica', 'nude', 'naked'
];

const DIMENSION_REGEX = /^\d+\s*[×xX*]\s*\d+/;

// Temporary local image server (no external hosting needed)
let tempServer = null;
let tempPort = 3001;

function startTempServer(imagePath) {
    if (tempServer) tempServer.close();
    tempServer = http.createServer((req, res) => {
        if (req.url === '/probe.jpg') {
            const data = fs.readFileSync(imagePath);
            res.writeHead(200, { 'Content-Type': 'image/jpeg' });
            res.end(data);
        } else {
            res.writeHead(404);
            res.end();
        }
    }).listen(tempPort, '0.0.0.0'); // Listen on all interfaces
    return `http://127.0.0.1:${tempPort}/probe.jpg`;
}

function cleanTitle(raw) {
    if (!raw) return 'Visual Match';
    let t = raw
        .replace(/^\d+\s*[×xX*]\s*\d+[A-Za-zА-Яа-я]?\s*/, '')
        .replace(/^\d+\s*[×xX*]\s*\d+\s*/, '')
        .replace(/\.(jpg|jpeg|png|gif|webp|bmp)\b/gi, '')
        .replace(/^\s*-\s*/, '')
        .trim();
    return t.slice(0, 180) || 'Visual Match';
}

function isSpam(link, title) {
    const combined = ((link || '') + ' ' + (title || '')).toLowerCase();
    return SPAM_DOMAINS.some(d => combined.includes(d));
}

function detectSource(href) {
    const h = href.toLowerCase();
    for (const [domain, name] of Object.entries(SOCIAL_DOMAINS)) {
        if (h.includes(domain)) return name;
    }
    return 'Web';
}

// ===== USERNAME PIVOT =====
function extractUsernames(results) {
    const users = new Set();
    const patterns = [
        /instagram\.com\/([A-Za-z0-9_.]+)/,
        /twitter\.com\/([A-Za-z0-9_]+)/,
        /x\.com\/([A-Za-z0-9_]+)/,
        /github\.com\/([A-Za-z0-9-]+)/,
        /tiktok\.com\/@([A-Za-z0-9_.]+)/,
        /t\.me\/([A-Za-z0-9_]+)/,
    ];

    results.forEach(r => {
        if (!r.link) return;
        patterns.forEach(p => {
            const m = r.link.match(p);
            if (m && m[1] && m[1].length > 2) users.add(m[1]);
        });
    });
    return Array.from(users);
}

async function dorkSocialProfiles(page, keywordHint) {
    const results = [];
    if (!keywordHint || keywordHint.length < 3) return results;

    console.log(`  🔍 Dorking (Bing Master) for: ${keywordHint}`);
    const cleanHint = keywordHint.replace(/[^\w\s\-_.]/g, '').trim();

    const platforms = [
        { site: 'instagram.com', q: `"${cleanHint}" instagram` },
        { site: 'facebook.com', q: `"${cleanHint}" facebook` },
        { site: 'linkedin.com', q: `"${cleanHint}" linkedin` },
        { site: 'twitter.com', q: `"${cleanHint}" twitter` },
        { site: 'github.com', q: `"${cleanHint}" github` },
        { site: 'tiktok.com', q: `"${cleanHint}" tiktok` }
    ];

    for (const plat of platforms) {
        try {
            const url = `https://www.bing.com/search?q=${encodeURIComponent(plat.q)}&count=50`;
            await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 12000 });
            await delay(1500);

            const items = await page.evaluate((site, socialDomains) => {
                const out = [];
                const seen = new Set();
                const socialMap = JSON.parse(socialDomains);

                document.querySelectorAll('li.b_algo, a[href^="http"]').forEach(el => {
                    try {
                        const a = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        if (!a) return;
                        const href = a.href;
                        if (!href || seen.has(href) || href.includes('bing.com')) return;

                        const isTarget = href.includes(site);
                        let isSocial = false;
                        let source = 'Web';
                        for (const [d, n] of Object.entries(socialMap)) {
                            if (href.includes(d)) { source = n; isSocial = true; break; }
                        }
                        if (!isTarget && !isSocial) return;

                        const title = (el.querySelector('h2, a')?.textContent || el.textContent || '').trim().slice(0, 150);
                        seen.add(href);
                        out.push({ title, link: href, thumbnail: '', source, isSocial, score: isSocial ? 300 : 80 });
                    } catch (e) {}
                });
                return out;
            }, plat.site, JSON.stringify(SOCIAL_DOMAINS));

            console.log(`    ✓ Bing (${plat.site}): ${items.length} leads`);
            results.push(...items);
        } catch (e) {
            console.log(`    ⚠️ Bing dork error (${plat.site}): ${e.message}`);
        }
    }
    return results;
}

// ========== MICROFORMAT / OPEN GRAPH EXTRACTOR ==========
async function extractPublicImagesFromProfile(page, profileUrl, onLog) {
    const images = [];
    try {
        onLog(`  🔬 Deep crawling: ${profileUrl.slice(0, 60)}...`);

        await page.goto(profileUrl, { waitUntil: 'domcontentloaded', timeout: 10000 });
        await delay(1500);

        const metaImages = await page.evaluate(() => {
            const out = [];
            const selectors = [
                'meta[property="og:image"]',
                'meta[name="twitter:image"]',
                'meta[property="twitter:image"]',
                'meta[itemprop="image"]',
                'meta[property="og:image:secure_url"]',
                'link[rel="image_src"]',
                'meta[property="instapp:owner_user_id"]',
            ];

            selectors.forEach(sel => {
                document.querySelectorAll(sel).forEach(el => {
                    const url = el.getAttribute('content') || el.getAttribute('href');
                    if (url && url.startsWith('http')) out.push(url);
                });
            });

            document.querySelectorAll('img[src*="fbcdn.net"], img[src*="instagram.com"], img[src*="cdninstagram"]').forEach(img => {
                if (img.src.startsWith('http')) out.push(img.src);
            });

            return [...new Set(out)];
        });

        for (const imgUrl of metaImages) {
            if (imgUrl.includes('emoji') || imgUrl.includes('pixel') || imgUrl.includes('1x1')) continue;
            if (imgUrl.endsWith('.ico') || imgUrl.endsWith('.svg')) continue;
            images.push(imgUrl);
            onLog(`    📸 Extracted public CDN: ${imgUrl.slice(0, 80)}...`);
        }

        if (images.length === 0) {
            onLog(`    ℹ No public images found (profile may use JS rendering)`);
        }
    } catch (e) {
        onLog(`    ⚠️ Deep crawl failed: ${e.message}`);
    }
    return images;
}

// ========== RECURSIVE AVATAR SEARCH ==========
async function recursiveAvatarSearch(browser, seedImageUrl, keywordHint, onLog) {
    const allMatches = [];
    const seenImageUrls = new Set([seedImageUrl]);
    const seenProfileUrls = new Set();
    const crawlDepth = 2;

    let currentImages = [seedImageUrl];

    for (let depth = 0; depth < crawlDepth; depth++) {
        onLog(`\n🔄 RECURSIVE DEPTH ${depth + 1}: ${currentImages.length} image(s) to probe`);

        const nextImages = [];

        for (const imgUrl of currentImages) {
            const page = await browser.newPage();
            await page.setViewport({ width: 1920, height: 1080 });
            await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36');

            try {
                const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imgUrl)}`;
                await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 12000 });
                await delay(2000);

                const items = await page.evaluate((socialDomains) => {
                    const out = [], seen = new Set();
                    const socialMap = JSON.parse(socialDomains);

                    document.querySelectorAll('.serp-item, .CbirSites-Item, a[href^="http"]').forEach(el => {
                        try {
                            const a = el.querySelector('a[href^="http"]');
                            if (!a) return;
                            const href = a.href;
                            if (!href || seen.has(href) || href.includes('yandex.com')) return;

                            const imgEl = el.querySelector('img');
                            const thumb = imgEl ? (imgEl.src || imgEl.getAttribute('data-src') || '') : '';
                            const title = el.textContent.trim().slice(0, 100) || 'Visual Match';

                            let source = 'Yandex';
                            let isSocial = false;
                            for (const [d, n] of Object.entries(socialMap)) {
                                if (href.includes(d)) { source = n; isSocial = true; break; }
                            }

                            seen.add(href);
                            out.push({ title, link: href, thumbnail: thumb, source, isSocial, score: isSocial ? 200 : 60 });
                        } catch(e) {}
                    });
                    return out;
                }, JSON.stringify(SOCIAL_DOMAINS));

                items.forEach(item => {
                    if (!seenProfileUrls.has(item.link)) {
                        seenProfileUrls.add(item.link);
                        allMatches.push(item);
                    }
                });

                onLog(`   ✓ Depth ${depth + 1}: ${items.length} profiles from image probe`);

                const socialProfiles = items.filter(i => i.isSocial).slice(0, 5);
                for (const profile of socialProfiles) {
                    if (seenProfileUrls.has(profile.link + '_crawled')) continue;
                    seenProfileUrls.add(profile.link + '_crawled');

                    const publicImages = await extractPublicImagesFromProfile(page, profile.link, onLog);

                    for (const pubImg of publicImages) {
                        if (!seenImageUrls.has(pubImg)) {
                            seenImageUrls.add(pubImg);
                            nextImages.push(pubImg);
                            onLog(`    ⬇️ Queued new avatar for next depth: ${pubImg.slice(0, 50)}...`);
                        }
                    }
                }

            } catch (e) {
                onLog(`   ⚠️ Depth ${depth + 1} error: ${e.message}`);
            }

            await page.close();
        }

        currentImages = nextImages;
        if (currentImages.length === 0) {
            onLog(`🛑 No new avatars discovered. Halting recursion.`);
            break;
        }
    }

    return allMatches;
}

app.post('/api/search', async (req, res) => {
    const { imageUrl: clientUrl, imageBase64, keywordHint, deepCrawl } = req.body;
    let imageUrl = clientUrl;
    let localFilePath = null;

    if (imageBase64) {
        const buffer = Buffer.from(imageBase64, 'base64');
        const tmpDir = '/data/data/com.termux/files/usr/tmp';
        if (!fs.existsSync(tmpDir)) fs.mkdirSync(tmpDir, { recursive: true });
        localFilePath = path.join(tmpDir, 'probe.jpg');
        fs.writeFileSync(localFilePath, buffer);
        imageUrl = startTempServer(localFilePath);
        console.log(`📸 Local probe serving at ${imageUrl}`);
    }

    console.log(`\n📸 Search: ${imageUrl?.slice(0, 60)}... | Hint: ${keywordHint || 'None'} | DeepCrawl: ${!!deepCrawl}`);

    if (!imageUrl) return res.status(400).json({ error: 'Missing image' });

    let browser;
    let isFinished = false;
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.status(504).json({ success: false, error: 'Search timeout', matches: [] });
        }
    }, deepCrawl ? 120000 : 85000); // More time for deep crawl

    try {
        browser = await puppeteer.launch({
            headless: true,
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser',
            args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--single-process']
        });

        const allRaw = [];
        const pages = [];
        async function getPage() {
            const p = await browser.newPage();
            await p.setViewport({ width: 1920, height: 1080 });
            await p.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
            pages.push(p);
            return p;
        }

        if (deepCrawl) {
            console.log('🕸️ Activating Deep Recursive Crawl...');
            const recursiveResults = await recursiveAvatarSearch(browser, imageUrl, keywordHint, (msg) => console.log(msg));
            allRaw.push(...recursiveResults);
        } else {
            // Standard Parallel Engines
            const yandexJob = (async () => {
                const page = await getPage();
                try {
                    console.log('🔍 Querying Yandex...');
                    if (localFilePath) {
                        await page.goto('https://yandex.com/images/search', { waitUntil: 'domcontentloaded' });
                        const uploadBtn = await page.waitForSelector('.input__icon_type_camera, .CbirHeader-Camera', { timeout: 5000 });
                        await uploadBtn.click();
                        const fileInput = await page.waitForSelector('input[type="file"]', { timeout: 5000 });
                        await fileInput.uploadFile(localFilePath);
                    } else {
                        const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
                        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                    }
                    await delay(3500);
                    for (let i = 0; i < 5; i++) {
                        await page.evaluate(() => window.scrollBy(0, window.innerHeight));
                        await delay(700);
                    }

                    const items = await page.evaluate(() => {
                        const out = [], seen = new Set();
                        document.querySelectorAll('.serp-item, .CbirSites-Item').forEach(el => {
                            try {
                                const a = el.querySelector('a[href^="http"]');
                                if (!a) return;
                                const href = a.href;
                                if (!href || seen.has(href) || href.includes('yandex.com')) return;
                                const img = el.querySelector('img');
                                let thumb = img ? (img.src || img.getAttribute('data-src') || '') : '';
                                const title = el.querySelector('.CbirSites-ItemTitle, .serp-item__title, a')?.textContent || '';
                                seen.add(href);
                                out.push({ title, link: href, thumbnail: thumb, source: 'Yandex', isSocial: false, score: 50 });
                            } catch (e) {}
                        });
                        return out;
                    });
                    return items;
                } catch (e) { console.log(`   ⚠️ Yandex error: ${e.message}`); return []; }
            })();

            const bingJob = (async () => {
                const page = await getPage();
                try {
                    console.log('🔍 Querying Bing Visual...');
                    if (localFilePath) {
                        await page.goto('https://www.bing.com/visualsearch', { waitUntil: 'domcontentloaded' });
                        const fileInput = await page.waitForSelector('input[type="file"]', { timeout: 5000 });
                        await fileInput.uploadFile(localFilePath);
                    } else {
                        const url = `https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&q=imgurl:${encodeURIComponent(imageUrl)}&imgurl=${encodeURIComponent(imageUrl)}`;
                        await page.goto(url, { waitUntil: 'networkidle2', timeout: 15000 });
                    }
                    await delay(3500);

                    const items = await page.evaluate(() => {
                        const out = [], seen = new Set();
                        document.querySelectorAll('.imgpt, .richImage, .vsc_match').forEach(el => {
                            try {
                                const a = el.querySelector('a[href^="http"]');
                                if (!a) return;
                                const href = a.href;
                                if (!href || seen.has(href) || href.includes('bing.com')) return;
                                const img = el.querySelector('img');
                                let thumb = img ? (img.src || img.getAttribute('data-src') || '') : '';
                                const title = el.getAttribute('aria-label') || el.textContent.trim() || 'Visual Match';
                                seen.add(href);
                                out.push({ title, link: href, thumbnail: thumb, source: 'Bing', isSocial: false, score: 45 });
                            } catch (e) {}
                        });
                        return out;
                    });
                    return items;
                } catch (e) { console.log(`   ⚠️ Bing error: ${e.message}`); return []; }
            })();

            const tineyeJob = (async () => {
                const page = await getPage();
                try {
                    console.log('🔍 Querying TinEye...');
                    if (localFilePath) {
                        await page.goto('https://tineye.com/', { waitUntil: 'domcontentloaded' });
                        const fileInput = await page.waitForSelector('input[type="file"]', { timeout: 5000 });
                        await fileInput.uploadFile(localFilePath);
                    } else {
                        const url = `https://tineye.com/search?url=${encodeURIComponent(imageUrl)}`;
                        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                    }
                    await delay(3000);
                    const items = await page.evaluate(() => {
                        const out = [], seen = new Set();
                        document.querySelectorAll('.match, .search-result').forEach(el => {
                            try {
                                const a = el.querySelector('a[href^="http"]');
                                if (!a) return;
                                const href = a.href;
                                if (!href || seen.has(href) || href.includes('tineye.com')) return;
                                const img = el.querySelector('img');
                                const thumb = img ? (img.src || '') : '';
                                const title = el.textContent.trim().slice(0, 100) || 'TinEye Match';
                                seen.add(href);
                                out.push({ title, link: href, thumbnail: thumb, source: 'TinEye', isSocial: false, score: 40 });
                            } catch (e) {}
                        });
                        return out;
                    });
                    return items;
                } catch (e) { console.log(`   ⚠️ TinEye error: ${e.message}`); return []; }
            })();

            const baiduJob = (async () => {
                const page = await getPage();
                try {
                    console.log('🔍 Querying Baidu...');
                    const url = `https://graph.baidu.com/s?sign=&wd=&f=general&tn=wise&image=${encodeURIComponent(imageUrl)}`;
                    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                    await delay(2500);
                    const items = await page.evaluate(() => {
                        const out = [], seen = new Set();
                        document.querySelectorAll('a[href^="http"]').forEach(el => {
                            try {
                                const href = el.href;
                                if (!href || seen.has(href) || href.includes('baidu.com')) return;
                                const title = el.textContent.trim().slice(0, 100) || 'Baidu Match';
                                seen.add(href);
                                out.push({ title, link: href, thumbnail: '', source: 'Baidu', isSocial: false, score: 35 });
                            } catch (e) {}
                        });
                        return out;
                    });
                    return items;
                } catch (e) { console.log(`   ⚠️ Baidu error: ${e.message}`); return []; }
            })();

            const sogouJob = (async () => {
                const page = await getPage();
                try {
                    console.log('🔍 Querying Sogou...');
                    const url = `https://pic.sogou.com/ris?query=${encodeURIComponent(imageUrl)}&flag=1&drag=0`;
                    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15000 });
                    await delay(2500);

                    const items = await page.evaluate(() => {
                        const out = [], seen = new Set();
                        document.querySelectorAll('a[href^="http"]').forEach(el => {
                            try {
                                const href = el.href;
                                if (!href || seen.has(href) || href.includes('sogou.com')) return;
                                const title = el.textContent.trim().slice(0, 100) || 'Sogou Match';
                                seen.add(href);
                                out.push({ title, link: href, thumbnail: '', source: 'Sogou', isSocial: false, score: 40 });
                            } catch(e) {}
                        });
                        return out;
                    });
                    return items;
                } catch (e) { console.log(`   ⚠️ Sogou error: ${e.message}`); return []; }
            })();

            const dorkJob = (async () => {
                if (!keywordHint || keywordHint.length < 3) return [];
                const page = await getPage();
                return dorkSocialProfiles(page, keywordHint);
            })();

            const settled = await Promise.allSettled([yandexJob, bingJob, tineyeJob, baiduJob, sogouJob, dorkJob]);
            settled.forEach(r => { if (r.status === 'fulfilled') allRaw.push(...r.value); });
        }

        console.log(`🧹 Cleaning ${allRaw.length} raw results...`);
        const cleaned = [];
        const seenLinks = new Set();

        for (const raw of allRaw) {
            const title = cleanTitle(raw.title);
            const link = raw.link || '';
            const source = raw.source === 'Web' ? detectSource(link) : raw.source;
            if (isSpam(link, title)) continue;
            if (DIMENSION_REGEX.test(title) && title.length < 15) continue;
            if (seenLinks.has(link)) continue;
            seenLinks.add(link);
            let score = raw.score || 50;
            const lowerLink = link.toLowerCase();
            if (Object.keys(SOCIAL_DOMAINS).some(d => lowerLink.includes(d))) {
                score += 200;
                if (lowerLink.includes('/profile') || lowerLink.includes('/in/') || lowerLink.includes('/@')) score += 100;
            }
            if (keywordHint) {
                const hint = keywordHint.toLowerCase();
                const t = title.toLowerCase();
                if (t.includes(hint)) score += 300;
                if (hint.split(' ').filter(w => w.length > 2).every(w => t.includes(w))) score += 200;
            }
            cleaned.push({
                title, link, thumbnail: raw.thumbnail || '', source,
                isSocial: Object.keys(SOCIAL_DOMAINS).some(d => lowerLink.includes(d)),
                score
            });
        }

        cleaned.sort((a, b) => b.score - a.score);

        // ===== USERNAME PIVOT =====
        const usernames = extractUsernames(cleaned);
        if (usernames.length > 0) {
            console.log(`🎯 Pivoting on usernames: ${usernames.join(', ')}`);
            const pivotPage = await getPage();
            for (const user of usernames.slice(0, 3)) {
                try {
                    const pivotResults = await dorkSocialProfiles(pivotPage, user);
                    pivotResults.forEach(pr => {
                        if (!seenLinks.has(pr.link)) {
                            seenLinks.add(pr.link);
                            cleaned.push(pr);
                        }
                    });
                } catch (e) {
                    console.log(`⚠️ Pivot error for ${user}: ${e.message}`);
                }
            }
            cleaned.sort((a, b) => b.score - a.score);
        }

        console.log(`🎯 Clean results: ${cleaned.length}`);
        clearTimeout(timeout);
        isFinished = true;
        res.json({ success: true, matches: cleaned.slice(0, 50), deepCrawl: !!deepCrawl });

    } catch (err) {
        console.log(`⚠️ Critical: ${err.message}`);
        clearTimeout(timeout);
        if (!isFinished) res.status(500).json({ success: false, error: err.message, matches: [] });
    } finally {
        if (browser) await browser.close().catch(() => {});
        if (tempServer) tempServer.close();
    }
});

app.get('/ping', (req, res) => res.json({ status: 'ok', service: 'free-scraper-v2' }));

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`⚡ FREE SCRAPER v2 on port ${PORT}`);
    console.log(`   Direct Base64 Transfer Enabled`);
    console.log(`   Engines: Yandex | Bing | TinEye | Baidu | Sogou | Bing Dorking\n`);
});