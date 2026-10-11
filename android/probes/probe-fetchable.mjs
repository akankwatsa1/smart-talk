// Which sources can actually be FETCHED for "yesterday's football results"?
//
// The previous probe showed the search itself works - it returns sportytrader,
// footlive and BBC Sport for this query - but sportytrader answered HTTP 403, so
// there was no content to analyse and the model said it did not know. Searching
// is not the bottleneck; fetching is.
//
// Delays are inserted between requests because DuckDuckGo throttles rapid
// successive queries, which made an earlier probe report false "0 results".

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
          'Accept-Encoding': 'identity',
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

function readable(html) {
  let s = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ');
  s = s.replace(/<[^>]+>/g, ' ');
  return s.replace(/&nbsp;|&#160;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
}

const candidates = [
  ['BBC scores & fixtures (current)', 'https://www.bbc.co.uk/sport/football/premier-league/scores-fixtures'],
  ['BBC scores by date', `https://www.bbc.co.uk/sport/football/scores-fixtures/${iso(YESTERDAY)}`],
  ['BBC football scores', 'https://www.bbc.co.uk/sport/football/scores-fixtures'],
  ['Sky Sports results', 'https://www.skysports.com/premier-league-results'],
  ['Footlive yesterday', 'https://www.footlive.com/yesterday/'],
  ['SportyTrader (blocked last time)', 'https://www.sportytrader.com/en/results/football/england/yesterday/'],
  ['Wikipedia 2026-27 PL', 'https://en.wikipedia.org/wiki/2026%E2%80%9327_Premier_League'],
];

console.log(`yesterday = ${iso(YESTERDAY)} (${longDate(YESTERDAY)})\n`);

for (const [name, url] of candidates) {
  try {
    const r = await req(url);
    const text = readable(r.body);
    const hasScore = /\b\d\s*[-–:]\s*\d\b/.test(text);
    const mentions = text.includes(iso(YESTERDAY)) || text.includes(longDate(YESTERDAY));
    const shortDate = YESTERDAY.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });
    const mentionsShort = text.includes(shortDate);
    console.log(`${String(r.code).padEnd(4)} ${name.padEnd(38)} ${String(text.length).padStart(7)} chars  score:${hasScore ? 'Y' : 'n'}  yday-date:${mentions ? 'Y' : (mentionsShort ? 'short' : 'n')}`);
    if (hasScore) {
      const i = text.search(/\b\d\s*[-–:]\s*\d\b/);
      console.log(`       sample: ${JSON.stringify(text.slice(Math.max(0, i - 100), i + 100))}`);
    }
  } catch (e) {
    console.log(`ERR  ${name.padEnd(38)} ${e.message}`);
  }
  await sleep(1500);
}

console.log('\n--- DuckDuckGo throttle check (same query three times, spaced out) ---');
for (let i = 1; i <= 3; i++) {
  const body = `q=${encodeURIComponent('premier league results')}`;
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
  const n = [...r.body.matchAll(/<a[^>]+href="(https?:\/\/[^"]+)"/gi)].filter(
    (m) => !/duckduckgo\.com/.test(m[1])
  ).length;
  console.log(`  attempt ${i}: HTTP ${r.code}, ${r.body.length} bytes, external links ${n}`);
  await sleep(4000);
}
