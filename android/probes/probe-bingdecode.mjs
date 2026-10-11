// Bing wraps every result URL in a click-tracking redirect:
//   https://www.bing.com/ck/a?!&&p=...&u=a1aHR0cHM6Ly93d3cucHJlbWllcmxlYWd1ZS5jb20v...
// Fetching that redirect returns HTTP 204 with no body, which is useless.
//
// The real destination is the `u=` parameter: strip the leading "a1" and
// base64-decode the rest. This proves the decode works and that the decoded
// pages actually contain yesterday's results.

import https from 'node:https';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const TODAY = new Date();
const YESTERDAY = new Date(TODAY.getTime() - 86400000);
const iso = (d) => d.toISOString().slice(0, 10);
const longDate = (d) =>
  d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });

function req(url, opts = {}) {
  const { headers = {} } = opts;
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request(
      {
        hostname: u.hostname,
        path: u.pathname + u.search,
        method: 'GET',
        headers: {
          'User-Agent': UA,
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          'Accept-Language': 'en-US,en;q=0.9',
          ...headers,
        },
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          resolve(req(new URL(res.headers.location, url).toString(), opts));
          return;
        }
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => resolve({ code: res.statusCode, body: d }));
      }
    );
    r.on('error', reject);
    r.end();
  });
}

/** Bing's u= parameter: "a1" + base64url of the real URL. */
function decodeBingUrl(href) {
  try {
    const u = new URL(href);
    if (!u.hostname.endsWith('bing.com')) return href;
    const raw = u.searchParams.get('u');
    if (!raw) return null;
    let b64 = raw.startsWith('a1') ? raw.slice(2) : raw;
    b64 = b64.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    const decoded = Buffer.from(b64, 'base64').toString('utf8');
    return /^https?:\/\//.test(decoded) ? decoded : null;
  } catch {
    return null;
  }
}

async function bingSearch(q) {
  const r = await req('https://www.bing.com/search?q=' + encodeURIComponent(q) + '&setlang=en');
  const out = [];
  for (const b of r.body.matchAll(/<li class="b_algo"[\s\S]*?(?=<li class="b_algo|<\/ol>)/gi)) {
    const l = /<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i.exec(b[0]);
    if (!l) continue;
    const title = l[2].replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').trim();
    const href = l[1].replace(/&amp;/g, '&');
    const real = decodeBingUrl(href);
    if (!real) continue;
    out.push({ title, url: real, wasWrapped: real !== href });
  }
  return out;
}

function readable(html) {
  let s = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ');
  s = s.replace(/<[^>]+>/g, ' ');
  return s.replace(/&nbsp;|&#160;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
}

const q = `premier league results ${longDate(YESTERDAY)}`;
console.log(`QUERY: ${q}`);
console.log(`yesterday = ${iso(YESTERDAY)}\n`);

const results = await bingSearch(q);
console.log(`decoded results: ${results.length} (wrapped: ${results.filter((r) => r.wasWrapped).length})`);
results.slice(0, 6).forEach((r, i) => console.log(`  [${i + 1}] ${r.title.slice(0, 55)} -> ${r.url.slice(0, 75)}`));

console.log('\n--- fetching decoded URLs ---');
for (const item of results.slice(0, 4)) {
  try {
    const page = await req(item.url);
    const text = readable(page.body);
    const scoreMatches = [...text.matchAll(/\b\d\s*[-–:]\s*\d\b/g)].length;
    const hasDate = text.includes(iso(YESTERDAY)) || text.includes(longDate(YESTERDAY));
    console.log(`  ${String(page.code).padEnd(4)} ${String(text.length).padStart(7)} chars  scores:${String(scoreMatches).padStart(3)}  yday-date:${hasDate ? 'Y' : 'n'}  ${item.url.slice(0, 55)}`);
    if (scoreMatches > 0 && hasDate) {
      const i = text.search(/[A-Z][A-Za-z .']{3,25}\s\d\s*[-–:]\s*\d\s*[A-Z]/);
      if (i > -1) console.log(`        ${JSON.stringify(text.slice(Math.max(0, i - 60), i + 140))}`);
    }
  } catch (e) {
    console.log(`  ERR  ${item.url.slice(0, 55)} ${e.message}`);
  }
  await sleep(1200);
}
