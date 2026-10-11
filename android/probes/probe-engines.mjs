// DuckDuckGo has started answering HTTP 202 with no links at all, from this IP,
// even 4 seconds apart - so an app that depends on it will intermittently report
// "I don't know". This checks which engines still work and whether a date-anchored
// query improves the pages returned.

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
  const { method = 'GET', headers = {}, body = null } = opts;
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
          resolve(req(new URL(res.headers.location, url).toString(), opts));
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

const decode = (s) =>
  s.replace(/&amp;/g, '&').replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"').replace(/&#x2F;/g, '/');

function links(html, hostFilter) {
  const out = [];
  const seen = new Set();
  for (const m of html.matchAll(/<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    let href = m[1].replace(/^\/\//, 'https://');
    const title = decode(m[2].replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (!href.startsWith('http')) continue;
    if (hostFilter.test(href)) continue;
    if (/\.(css|js|png|jpe?g|svg|gif|ico|woff2?|pdf)(\?|$)/i.test(href)) continue;
    if (title.length < 4) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    out.push({ title, url: href });
  }
  return out;
}

async function bing(q) {
  const r = await req('https://www.bing.com/search?q=' + encodeURIComponent(q) + '&setlang=en');
  const out = [];
  for (const b of r.body.matchAll(/<li class="b_algo"[\s\S]*?(?=<li class="b_algo|<\/ol>)/gi)) {
    const l = /<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i.exec(b[0]);
    if (!l) continue;
    out.push({ title: decode(l[2].replace(/<[^>]+>/g, ' ')).trim(), url: l[1] });
  }
  return { code: r.code, results: out };
}

async function mojeek(q) {
  const r = await req('https://www.mojeek.com/search?q=' + encodeURIComponent(q));
  const out = links(r.body, /mojeek\.com/).filter((x) => /^https?:\/\//.test(x.url));
  return { code: r.code, results: out.slice(0, 6) };
}

async function startpage(q) {
  const r = await req('https://www.startpage.com/sp/search?query=' + encodeURIComponent(q));
  return { code: r.code, results: links(r.body, /startpage\.com/).slice(0, 6) };
}

const queries = [
  "yesterday's epl results",
  `premier league results ${longDate(YESTERDAY)}`,
  `premier league results ${iso(YESTERDAY)}`,
];

console.log(`yesterday = ${iso(YESTERDAY)}\n`);

for (const q of queries) {
  console.log('='.repeat(70));
  console.log(`QUERY: ${q}`);
  for (const [name, fn] of [['Bing', bing], ['Mojeek', mojeek], ['Startpage', startpage]]) {
    try {
      const r = await fn(q);
      console.log(`  ${name.padEnd(10)} HTTP ${String(r.code).padEnd(4)} results ${r.results.length}`);
      r.results.slice(0, 3).forEach((x) => console.log(`      ${x.title.slice(0, 62)} -> ${x.url.slice(0, 62)}`));
    } catch (e) {
      console.log(`  ${name.padEnd(10)} ERROR ${e.message}`);
    }
    await sleep(2000);
  }
  console.log('');
}

console.log('--- fetching the most promising result for date-anchored query ---');
const r = await bing(`premier league results ${longDate(YESTERDAY)}`);
for (const item of r.results.slice(0, 3)) {
  try {
    const page = await req(item.url);
    const text = page.body.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    const hasScore = /\b\d\s*[-–:]\s*\d\b/.test(text);
    console.log(`  ${String(page.code).padEnd(4)} ${String(text.length).padStart(7)} chars score:${hasScore ? 'Y' : 'n'}  ${item.url.slice(0, 60)}`);
    if (hasScore) {
      const i = text.search(/\b\d\s*[-–:]\s*\d\b/);
      console.log(`        ${JSON.stringify(text.slice(Math.max(0, i - 90), i + 90))}`);
    }
  } catch (e) {
    console.log(`  ERR  ${item.url.slice(0, 60)} ${e.message}`);
  }
  await sleep(1200);
}
