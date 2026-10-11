// End-to-end proof that the rebuilt search can answer the exact question that
// failed before: "premier league table standings".
//
// This mirrors the Kotlin implementation in WebSearch.kt (POST to DuckDuckGo
// Lite, fetch the top pages, keep table structure, condense to a budget) so the
// approach is proven before it is trusted on the phone.

import https from 'node:https';

const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function req(url, { method = 'GET', headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request(
      {
        hostname: u.hostname,
        path: u.pathname + u.search,
        method,
        headers: {
          'User-Agent': DESKTOP_UA,
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

const decodeEntities = (s) =>
  s
    .replace(/&nbsp;|&#160;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;|&#34;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&mdash;|&ndash;|&minus;/g, '-');

/** Same rules as WebSearch.extractReadableText. */
function extractReadableText(html) {
  let s = html;
  s = s.replace(/<script[\s\S]*?<\/script>/gi, ' ');
  s = s.replace(/<style[\s\S]*?<\/style>/gi, ' ');
  s = s.replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ');
  s = s.replace(/<svg[\s\S]*?<\/svg>/gi, ' ');
  s = s.replace(/<head[\s\S]*?<\/head>/gi, ' ');
  s = s.replace(/<!--[\s\S]*?-->/g, ' ');
  s = s.replace(/<\/t[dh]\s*>/gi, ' | ');
  s = s.replace(/<\/tr\s*>/gi, '\n');
  s = s.replace(/<br\s*\/?>/gi, '\n');
  s = s.replace(/<\/(p|div|li|h[1-6]|section|article|header|footer|nav|blockquote)\s*>/gi, '\n');
  s = s.replace(/<[^>]+>/g, ' ');
  s = decodeEntities(s);
  const lines = s
    .split('\n')
    .map((l) =>
      l
        .replace(/[\u00A0\t]+/g, ' ')
        .replace(/ {2,}/g, ' ')
        .replace(/\s*\|\s*/g, ' | ')
        .replace(/^(\s*\|\s*)+/, '')
        .replace(/(\s*\|\s*)+$/, '')
        .trim()
    )
    .filter((l) => l.length > 1);
  const out = [];
  let prev = '';
  for (const l of lines) {
    if (l !== prev) out.push(l);
    prev = l;
  }
  return out.join('\n');
}

/** Same rules as WebSearch.condense. */
function condense(text, budget) {
  if (text.length <= budget) return text;
  const lines = text.split('\n');
  const isDataRow = (l) => (l.match(/\|/g) || []).length >= 2 && /\d/.test(l) && l.length < 400;
  const keep = new Set();
  let used = 0;
  const rowBudget = Math.floor(budget * 0.7);
  lines.forEach((l, i) => {
    if (!isDataRow(l)) return;
    if (used + l.length > rowBudget) return;
    keep.add(i);
    used += l.length;
  });
  lines.forEach((l, i) => {
    if (keep.has(i)) return;
    if (used + l.length > budget) return;
    keep.add(i);
    used += l.length;
  });
  return [...keep].sort((a, b) => a - b).map((i) => lines[i]).join('\n');
}

async function main() {
  const query = 'premier league table standings';
  console.log(`QUERY: ${query}`);
  console.log(`TODAY: ${new Date().toISOString().slice(0, 10)}\n`);

  console.log('--- step 1: DuckDuckGo Lite by POST ---');
  const body = `q=${encodeURIComponent(query)}`;
  const ddg = await req('https://lite.duckduckgo.com/lite/', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Content-Length': Buffer.byteLength(body),
      Origin: 'https://lite.duckduckgo.com',
      Referer: 'https://lite.duckduckgo.com/',
    },
    body,
  });
  console.log(`    HTTP ${ddg.code}, ${ddg.body.length} bytes`);

  const results = [];
  const seen = new Set();
  for (const m of ddg.body.matchAll(/<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    let href = m[1].replace(/^\/\//, 'https://');
    const title = decodeEntities(m[2].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (!href.startsWith('http')) continue;
    if (/duckduckgo\.com|\.(css|js|png|jpe?g|svg|gif|ico|woff2?|pdf)(\?|$)/i.test(href)) continue;
    if (title.length < 3) continue;
    if (href.includes('uddg=')) {
      const u = /uddg=([^&]+)/.exec(href);
      if (u) href = decodeURIComponent(u[1]);
    }
    if (seen.has(href)) continue;
    seen.add(href);
    results.push({ title, url: href });
    if (results.length >= 6) break;
  }
  console.log(`    usable results: ${results.length}`);
  results.slice(0, 5).forEach((r, i) => console.log(`      [${i + 1}] ${r.title} -> ${r.url.slice(0, 80)}`));

  console.log('\n--- step 2: fetch the top pages and keep table structure ---');
  let best = null;
  for (const r of results.slice(0, 3)) {
    const page = await req(r.url).catch(() => null);
    if (!page || page.code !== 200) {
      console.log(`    SKIP ${r.url.slice(0, 60)} (HTTP ${page ? page.code : 'error'})`);
      continue;
    }
    const text = extractReadableText(page.body);
    const condensed = condense(text, 7000);
    const dataRows = condensed.split('\n').filter((l) => (l.match(/\|/g) || []).length >= 2 && /\d/.test(l));
    console.log(`    ${r.url.slice(0, 60)}`);
    console.log(`       readable ${text.length} chars -> condensed ${condensed.length}, data rows kept: ${dataRows.length}`);
    if (!best || dataRows.length > best.rows) best = { url: r.url, condensed, rows: dataRows.length };
  }

  if (!best || best.rows === 0) {
    console.log('\nRESULT: no table rows extracted - the approach did NOT work.');
    process.exit(1);
  }

  console.log(`\n--- step 3: the standings the model would actually receive ---`);
  console.log(`source: ${best.url}`);
  const rows = best.condensed
    .split('\n')
    .filter((l) => (l.match(/\|/g) || []).length >= 2 && /\d/.test(l))
    .slice(0, 10);
  rows.forEach((r) => console.log('   ' + r.replace(/\s*\|\s*/g, ' | ').slice(0, 130)));

  console.log(`\nRESULT: extracted ${best.rows} table rows. The answer is present in the context.`);
}

main().catch((e) => {
  console.error('probe failed:', e.message);
  process.exit(1);
});
