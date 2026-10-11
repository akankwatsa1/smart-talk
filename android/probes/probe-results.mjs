// Why did "yesterday's EPL results" fail when "premier league table" worked?
//
// Two hypotheses:
//   H1  The search query is sent as the user's literal words. "yesterday" is a
//       word a search engine cannot resolve, so it returns generic pages that do
//       not contain the matches - and the model correctly says it does not know.
//   H2  The pages are right but the results are not extractable.
//
// This tests several query formulations against the same date to find out which
// one actually surfaces the matches.

import https from 'node:https';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const TODAY = new Date();
const YESTERDAY = new Date(TODAY.getTime() - 86400000);

const iso = (d) => d.toISOString().slice(0, 10);
const longDate = (d) =>
  d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });

console.log(`today     : ${iso(TODAY)} (${longDate(TODAY)})`);
console.log(`yesterday : ${iso(YESTERDAY)} (${longDate(YESTERDAY)})\n`);

function req(url, { method = 'GET', headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request(
      {
        hostname: u.hostname,
        path: u.pathname + u.search,
        method,
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
          resolve(req(new URL(res.headers.location, url).toString(), { method, headers, body }));
          return;
        }
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => resolve({ code: res.statusCode, body: d }));
      }
    );
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}

async function ddg(query) {
  const body = `q=${encodeURIComponent(query)}`;
  const r = await req('https://lite.duckduckgo.com/lite/', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Content-Length': Buffer.byteLength(body),
      Origin: 'https://lite.duckduckgo.com',
      Referer: 'https://lite.duckduckgo.com/',
    },
    body,
  });
  const decode = (s) => s.replace(/&amp;/g, '&').replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"');
  const out = [];
  const seen = new Set();
  for (const m of r.body.matchAll(/<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    let href = m[1].replace(/^\/\//, 'https://');
    const title = decode(m[2].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (!href.startsWith('http')) continue;
    if (/duckduckgo\.com|\.(css|js|png|jpe?g|svg|gif|ico|woff2?|pdf)(\?|$)/i.test(href)) continue;
    if (title.length < 3) continue;
    if (href.includes('uddg=')) {
      const u = /uddg=([^&]+)/.exec(href);
      if (u) href = decodeURIComponent(u[1]);
    }
    if (seen.has(href)) continue;
    seen.add(href);
    out.push({ title, url: href });
    if (out.length >= 5) break;
  }
  return out;
}

function readable(html) {
  let s = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ');
  s = s.replace(/<[^>]+>/g, ' ');
  return s.replace(/&nbsp;|&#160;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
}

const queries = [
  "yesterday's epl results",
  'premier league results yesterday',
  `premier league results ${longDate(YESTERDAY)}`,
  `premier league results ${iso(YESTERDAY)}`,
  `premier league scores ${longDate(YESTERDAY)}`,
  'premier league results',
];

for (const q of queries) {
  console.log('='.repeat(72));
  console.log(`QUERY: ${q}`);
  console.log('='.repeat(72));
  let results;
  try {
    results = await ddg(q);
  } catch (e) {
    console.log('  search failed:', e.message);
    continue;
  }
  console.log(`  results: ${results.length}`);
  results.slice(0, 3).forEach((r, i) => console.log(`    [${i + 1}] ${r.title.slice(0, 80)} -> ${r.url.slice(0, 70)}`));

  // Does the top page actually contain a scoreline and yesterday's date?
  if (results.length) {
    try {
      const page = await req(results[0].url);
      const text = readable(page.body);
      const hasScore = /\b\d\s*[-–]\s*\d\b/.test(text);
      const dates = [iso(YESTERDAY), longDate(YESTERDAY), YESTERDAY.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })];
      const found = dates.filter((d) => text.includes(d));
      console.log(`  top page: HTTP ${page.code}, ${text.length} chars, scoreline present: ${hasScore}`);
      console.log(`  mentions yesterday's date (${dates[0]}): ${found.length > 0 ? 'YES -> ' + found.join(', ') : 'no'}`);
      const i = text.search(/\b\d\s*[-–]\s*\d\b/);
      if (i > -1) console.log(`  sample: ${JSON.stringify(text.slice(Math.max(0, i - 120), i + 120))}`);
    } catch (e) {
      console.log('  fetch failed:', e.message);
    }
  }
  console.log('');
}
