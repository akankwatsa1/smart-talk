// Probe: can a no-API-key search layer actually return LIVE structured data?
//
// The app previously sent the model only search-result SNIPPETS. Asking for
// current Premier League standings failed, and this probe exists to find out
// exactly why before any Kotlin is written.
//
// Run:  node probe-search.mjs

import https from 'node:https';

const UA =
  'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Mobile Safari/537.36';

function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'User-Agent': UA, Accept: '*/*', ...headers } }, (r) => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
          r.resume();
          resolve(get(new URL(r.headers.location, url).toString(), headers));
          return;
        }
        let d = '';
        r.on('data', (c) => (d += c));
        r.on('end', () => resolve({ code: r.statusCode, body: d }));
      })
      .on('error', reject);
  });
}

const stripTags = (s) =>
  s
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;|&#160;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\s+/g, ' ')
    .trim();

const wiki = (params) =>
  `https://en.wikipedia.org/w/api.php?format=json&${params}`;

/** Pull every HTML table that looks like a league standings table. */
function extractStandingsTables(html) {
  const tables = html.match(/<table[\s\S]*?<\/table>/gi) || [];
  const found = [];
  for (const tb of tables) {
    const hasPos = /(^|>)\s*Pos/i.test(tb);
    const hasPlayed = /Pld|Played|MP/i.test(tb);
    const hasPoints = /Pts|Points/i.test(tb);
    if (!(hasPos && hasPlayed && hasPoints)) continue;
    const rows = tb.match(/<tr[\s\S]*?<\/tr>/gi) || [];
    const lines = rows
      .map((row) =>
        row
          .replace(/<[^>]+>/g, '|')
          .replace(/&nbsp;|&#160;/g, ' ')
          .replace(/&amp;/g, '&')
          .replace(/\|{2,}/g, '|')
          .replace(/^\s*\|/, '')
          .replace(/\|\s*$/, '')
          .replace(/\s*\|\s*/g, ' | ')
          .replace(/\s+/g, ' ')
          .trim()
      )
      .filter((l) => l.length > 6);
    if (lines.length >= 5) found.push(lines);
  }
  return found;
}

async function main() {
  console.log('host clock today :', new Date().toISOString().slice(0, 10));
  console.log('');

  console.log('=== 1. Wikipedia search ranking ===');
  for (const q of ['2026-27 Premier League', 'Premier League table', 'Premier League']) {
    const r = await get(wiki(`action=query&list=search&srsearch=${encodeURIComponent(q)}&srlimit=4`));
    let titles = '(parse error)';
    try {
      titles = (JSON.parse(r.body).query?.search || []).map((x) => x.title).join(' | ') || '(none)';
    } catch {}
    console.log(`  search "${q}"`);
    console.log(`     -> ${titles}`);
  }
  console.log('');

  console.log('=== 2. Does explaintext keep the table? (this is what the app did) ===');
  const ex = await get(
    wiki(`action=query&prop=extracts&explaintext=1&titles=${encodeURIComponent('Premier League')}`)
  );
  const exPages = JSON.parse(ex.body).query?.pages || {};
  const exText = Object.values(exPages)[0]?.extract || '';
  const hasTableText = /Pos\s+Team|Pos\s*\|/.test(exText);
  console.log(`  extract length ${exText.length} chars, contains a standings table: ${hasTableText}`);
  console.log('');

  console.log('=== 3. HTML parse + table extraction (the proposed fix) ===');
  for (const title of ['2026–27 Premier League', '2025–26 Premier League', 'Premier League']) {
    const p = await get(wiki(`action=parse&prop=text&page=${encodeURIComponent(title)}`));
    let j = null;
    try {
      j = JSON.parse(p.body);
    } catch {}
    if (!j || !j.parse) {
      console.log(`  ${title} -> NO ARTICLE (${j?.error?.info || 'missing'})`);
      continue;
    }
    const html = j.parse.text['*'];
    const tables = extractStandingsTables(html);
    console.log(`  ${title} -> html ${html.length} chars, standings tables found: ${tables.length}`);
    if (tables.length) {
      tables[0].slice(0, 8).forEach((l) => console.log('       ' + l.slice(0, 150)));
    }
  }
  console.log('');

  console.log('=== 4. Generic page fetch from a search result ===');
  const ddg = await get('https://lite.duckduckgo.com/lite/?q=' + encodeURIComponent('Premier League table standings'));
  const links = [...ddg.body.matchAll(/href="(https?:\/\/[^"]+)"/g)]
    .map((m) => m[1])
    .filter((u) => !u.includes('duckduckgo.com'));
  console.log(`  duckduckgo lite returned ${links.length} external links`);
  links.slice(0, 3).forEach((u) => console.log('     -', u.slice(0, 110)));
  if (links.length) {
    const page = await get(links[0]);
    const text = stripTags(page.body);
    console.log(`  fetched ${links[0].slice(0, 70)} -> ${page.code}, ${text.length} chars of readable text`);
    console.log(`  sample: ${JSON.stringify(text.slice(0, 220))}`);
  }
}

main().catch((e) => {
  console.error('probe failed:', e.message);
  process.exit(1);
});
