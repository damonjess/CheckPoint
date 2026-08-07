const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');

puppeteer.use(StealthPlugin());

const app = express();
app.use(express.json({limit: '2mb'}));

const delay = (ms) => new Promise(r => setTimeout(r, ms));

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
    't.me': 'Telegram', 'telegram.me': 'Telegram',
    'twitch.tv': 'Twitch',
    'threads.net': 'Threads',
    'bsky.app': 'Bluesky'
};

const SPAM_DOMAINS = ['znakomstva','dating','sex.','porn','xxx','escort','bride','dosug','sintim','nude','naked'];
const DIM_REGEX = /^\d+\s*[×xX*]\s*\d+/;

function cleanTitle(raw) {
    if (!raw) return 'Visual Match';
    return raw
        .replace(/^\d+\s*[×xX*]\s*\d+\s*/, '')
        .replace(/\.(jpg|jpeg|png|gif|webp|bmp)\b/gi, '')
        .replace(/^\s*-\s*/, '')
        .trim()
        .slice(0, 180) || 'Visual Match';
}

function isSpam(link, title) {
    const c = ((link||'') + ' ' + (title||'')).toLowerCase();
    return SPAM_DOMAINS.some(d => c.includes(d));
}

function detectSource(href) {
    const h = href.toLowerCase();
    for (const [d, n] of Object.entries(SOCIAL_DOMAINS)) if (h.includes(d)) return n;
    return 'Web';
}

// ========== BING DORKING ==========
async function dorkSocialProfiles(page, hint) {
    const results = [];
    if (!hint || hint.length < 3) return results;
    const cleanHint = hint.replace(/[^\w\s\-_.]/g,'').trim();
    const platforms = [
        {site:'instagram.com', q:`"${cleanHint}" instagram`},
        {site:'facebook.com', q:`"${cleanHint}" facebook`},
        {site:'linkedin.com', q:`"${cleanHint}" linkedin`},
        {site:'twitter.com', q:`"${cleanHint}" twitter`},
        {site:'github.com', q:`"${cleanHint}" github`},
        {site:'tiktok.com', q:`"${cleanHint}" tiktok`}
    ];

    for (const plat of platforms) {
        try {
            const url = `https://www.bing.com/search?q=${encodeURIComponent(plat.q)}&count=50`;
            await page.goto(url, {waitUntil:'domcontentloaded', timeout:12000});
            await delay(1500);

            const items = await page.evaluate((site, socialDomains) => {
                const out = [], seen = new Set();
                const socialMap = JSON.parse(socialDomains);
                document.querySelectorAll('li.b_algo, a[href^="http"]').forEach(el => {
                    try {
                        const a = el.tagName === 'A' ? el : el.querySelector('a[href^="http"]');
                        if (!a) return;
                        const href = a.href;
                        if (!href || seen.has(href) || href.includes('bing.com')) return;
                        let isSocial = false, source = 'Web';
                        for (const [d,n] of Object.entries(socialMap)) {
                            if (href.includes(d)) { source = n; isSocial = true; break; }
                        }
                        if (!href.includes(site) && !isSocial) return;
                        const title = (el.querySelector('h2,a')?.textContent || el.textContent || '').trim().slice(0,150);
                        seen.add(href);
                        out.push({title, link:href, thumbnail:'', source, isSocial, score: isSocial?300:80});
                    } catch(e){}
                });
                return out;
            }, plat.site, JSON.stringify(SOCIAL_DOMAINS));

            console.log(`    ✓ Bing (${plat.site}): ${items.length} leads`);
            results.push(...items);
        } catch(e) {
            console.log(`    ⚠ Bing dork (${plat.site}): ${e.message}`);
        }
    }
    return results;
}

// ========== MAIN SEARCH ==========
app.post('/api/search', async (req, res) => {
    const { imageUrl, keywordHint, deepCrawl, searchMode } = req.body;
    const requestId = Math.random().toString(36).slice(2, 8);
    console.log(`\n[${requestId}] 📸 Search: ${imageUrl?.slice(0,60)}... | Mode: ${searchMode||'PRECISION'} | Hint: ${keywordHint||'None'}`);

    // REJECT LOCAL URLS IMMEDIATELY
    if (!imageUrl || !imageUrl.startsWith('http')) {
        return res.status(400).json({success:false, error:'imageUrl must be a public http(s) URL. Local 127.0.0.1 URLs are invisible to search engines.', matches:[]});
    }
    if (imageUrl.includes('127.0.0.1') || imageUrl.includes('localhost') || imageUrl.includes('::1')) {
        return res.status(400).json({success:false, error:'Localhost URLs cannot be searched. Use a public image host (Catbox/Telegra.ph).', matches:[]});
    }

    let browser;
    let isFinished = false;
    const timeout = setTimeout(() => {
        if (!isFinished && !res.headersSent) {
            isFinished = true;
            res.status(504).json({success:false, error:'Search timeout', matches:[]});
        }
    }, 90000);

    const allRaw = [];

    try {
        browser = await puppeteer.launch({
            headless: true,
            executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser',
            args: ['--no-sandbox','--disable-setuid-sandbox','--disable-dev-shm-usage','--single-process','--disable-gpu','--disable-software-rasterizer']
        });

        // Run engines sequentially to avoid single-process Chromium imploding
        async function runEngine(name, scraperFn) {
            const page = await browser.newPage();
            await page.setViewport({width:1920, height:1080});
            await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
            try {
                console.log(`  🔍 ${name}...`);
                const items = await scraperFn(page);
                console.log(`     ✓ ${name}: ${items.length} raw`);
                allRaw.push(...items);
            } catch(e) {
                console.log(`     ⚠ ${name} error: ${e.message}`);
            } finally {
                await page.close().catch(()=>{});
            }
        }

        // ENGINE 1: YANDEX
        await runEngine('Yandex', async (page) => {
            const url = `https://yandex.com/images/search?rpt=imageview&url=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, {waitUntil:'domcontentloaded', timeout:15000});
            await delay(3000);
            for(let i=0;i<5;i++){ await page.evaluate(()=>window.scrollBy(0,window.innerHeight)); await delay(700); }

            return page.evaluate(() => {
                const out=[], seen=new Set();
                document.querySelectorAll('.serp-item, .CbirSites-Item, a[href^="http"]').forEach(el => {
                    try {
                        const a = el.tagName==='A'?el:el.querySelector('a[href^="http"]');
                        if(!a) return;
                        const href = a.href;
                        if(!href || seen.has(href) || href.includes('yandex.com')) return;
                        const img = el.querySelector('img');
                        const thumb = img ? (img.src || img.getAttribute('data-src') || '') : '';
                        const title = el.textContent.trim().slice(0,100) || 'Visual Match';
                        seen.add(href);
                        out.push({title, link:href, thumbnail:thumb, source:'Yandex', isSocial:false, score:50});
                    } catch(e){}
                });
                return out;
            });
        });

        // ENGINE 1.5: GOOGLE LENS (BYPASS / HYPER)
        if (searchMode === 'BYPASS' || searchMode === 'HYPER' || searchMode === 'DEEP_CRAWL' || searchMode === 'AGGRESSIVE') {
            await runEngine('Google Lens', async (page) => {
                const url = `https://www.google.com/searchbyimage?sbisrc=404&image_url=${encodeURIComponent(imageUrl)}`;
                await page.goto(url, {waitUntil:'domcontentloaded', timeout:15000});
                await delay(3000);
                return page.evaluate(() => {
                    const out = [], seen = new Set();
                    document.querySelectorAll('a[href^="http"]').forEach(el => {
                        const href = el.href;
                        if (!href || seen.has(href) || href.includes('google.com')) return;
                        const title = el.textContent.trim().slice(0, 120) || 'Google Match';
                        seen.add(href);
                        out.push({title, link:href, thumbnail:'', source:'Google', isSocial:false, score:65});
                    });
                    return out;
                });
            });
        }

        // ENGINE 2: BING VISUAL
        await runEngine('Bing Visual', async (page) => {
            const url = `https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&q=imgurl:${encodeURIComponent(imageUrl)}&imgurl=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, {waitUntil:'networkidle2', timeout:15000});
            await delay(2500);

            return page.evaluate(() => {
                const out=[], seen=new Set();
                document.querySelectorAll('.imgpt, .richImage').forEach(el => {
                    try {
                        const a = el.querySelector('a[href^="http"]');
                        if(!a) return;
                        const href = a.href;
                        if(!href || seen.has(href) || href.includes('bing.com')) return;
                        const img = el.querySelector('img');
                        const thumb = img ? (img.src || img.getAttribute('data-src') || '') : '';
                        const title = el.getAttribute('aria-label') || el.textContent.trim().slice(0,100) || 'Visual Match';
                        seen.add(href);
                        out.push({title, link:href, thumbnail:thumb, source:'Bing', isSocial:false, score:45});
                    } catch(e){}
                });
                return out;
            });
        });

        // ENGINE 3: TINEYE
        await runEngine('TinEye', async (page) => {
            const url = `https://tineye.com/search?url=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, {waitUntil:'domcontentloaded', timeout:15000});
            await delay(2000);

            return page.evaluate(() => {
                const out=[], seen=new Set();
                document.querySelectorAll('.match, .search-result').forEach(el => {
                    try {
                        const a = el.querySelector('a[href^="http"]');
                        if(!a) return;
                        const href = a.href;
                        if(!href || seen.has(href) || href.includes('tineye.com')) return;
                        const img = el.querySelector('img');
                        const thumb = img ? (img.src || '') : '';
                        const title = el.textContent.trim().slice(0,100) || 'TinEye Match';
                        seen.add(href);
                        out.push({title, link:href, thumbnail:thumb, source:'TinEye', isSocial:false, score:40});
                    } catch(e){}
                });
                return out;
            });
        });

        // ENGINE 4: BAIDU
        await runEngine('Baidu', async (page) => {
            const url = `https://graph.baidu.com/s?sign=&wd=&f=general&tn=wise&image=${encodeURIComponent(imageUrl)}`;
            await page.goto(url, {waitUntil:'domcontentloaded', timeout:15000});
            await delay(2500);

            return page.evaluate(() => {
                const out=[], seen=new Set();
                document.querySelectorAll('a[href^="http"]').forEach(el => {
                    try {
                        const href = el.href;
                        if(!href || seen.has(href) || href.includes('baidu.com')) return;
                        const title = el.textContent.trim().slice(0,100) || 'Baidu Match';
                        seen.add(href);
                        out.push({title, link:href, thumbnail:'', source:'Baidu', isSocial:false, score:35});
                    } catch(e){}
                });
                return out;
            });
        });

        // ENGINE 4.5: PIMEYES LEADS (BYPASS / DEEP)
        if (searchMode === 'BYPASS' || searchMode === 'DEEP_CRAWL' || searchMode === 'HYPER') {
            await runEngine('PimEyes Leads', async (page) => {
                const url = `https://pimeyes.com/en/contact-search?url=${encodeURIComponent(imageUrl)}`;
                await page.goto(url, {waitUntil:'domcontentloaded', timeout:15000});
                await delay(4000);
                return page.evaluate(() => {
                    const out = [];
                    document.querySelectorAll('.result-item-info, .domain-name').forEach(el => {
                        const site = el.textContent.trim().split(' ')[0];
                        if (site && site.includes('.')) {
                            out.push({title: `Match lead: ${site}`, link: `https://www.google.com/search?q=site:${site}`, thumbnail: '', source: 'PimEyes Lead', isSocial: false, score: 70});
                        }
                    });
                    return out;
                });
            });
        }

        // ENGINE 5: BING DORKING
        if (keywordHint && keywordHint.length >= 3) {
            await runEngine('Bing Dorking', async (page) => {
                return dorkSocialProfiles(page, keywordHint);
            });
        }

        // ===== CLEAN & SCORE =====
        console.log(`🧹 Cleaning ${allRaw.length} raw results...`);
        const cleaned = [];
        const seenLinks = new Set();

        for (const raw of allRaw) {
            const title = cleanTitle(raw.title);
            const link = raw.link || '';
            const source = raw.source === 'Web' ? detectSource(link) : raw.source;

            if (isSpam(link, title)) continue;
            if (DIM_REGEX.test(title) && title.length < 20) continue;
            if (seenLinks.has(link)) continue;
            seenLinks.add(link);

            let score = raw.score || 50;
            const lowerLink = link.toLowerCase();
            if (Object.keys(SOCIAL_DOMAINS).some(d => lowerLink.includes(d))) {
                score += 200;
                if (lowerLink.includes('/in/') || lowerLink.includes('/profile') || lowerLink.includes('/@') || lowerLink.includes('/user/')) score += 100;
            }
            if (keywordHint) {
                const hint = keywordHint.toLowerCase();
                const t = title.toLowerCase();
                if (t.includes(hint)) score += 300;
                if (hint.split(' ').filter(w=>w.length>2).every(w=>t.includes(w))) score += 200;
            }

            cleaned.push({title, link, thumbnail: raw.thumbnail||'', source, isSocial: Object.keys(SOCIAL_DOMAINS).some(d=>lowerLink.includes(d)), score});
        }

        cleaned.sort((a,b) => b.score - a.score);
        console.log(`🎯 Clean results: ${cleaned.length} (spam filtered: ${allRaw.length - cleaned.length})`);

        clearTimeout(timeout);
        isFinished = true;
        res.json({success:true, matches:cleaned.slice(0,40)});

    } catch(err) {
        console.log(`⚠ Critical: ${err.message}`);
        clearTimeout(timeout);
        if (!isFinished) res.status(500).json({success:false, error:err.message, matches:[]});
    } finally {
        if (browser) await browser.close().catch(()=>{});
    }
});

app.get('/ping', (req,res) => res.json({status:'ok', service:'free-scraper-v4'}));

const PORT = 3000;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`⚡ FREE SCRAPER v4 on port ${PORT}`);
    console.log(`   NO local server. Public URLs only.`);
    console.log(`   Sequential: Yandex → Bing → TinEye → Baidu → Dorking\n`);
});