// Probe 2: which keyless search endpoints still work from this host?
//
// Finding that DuckDuckGo Lite returned ZERO external links means the app's
// existing search source may have been silently failing. This checks a range of
// candidates so the rebuilt search layer is based on sources that actually
// respond, rather than on ones that merely used to work.

import https from 'node:https';

const UA =
  'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/124.0.0.0 Mobile Safari/537.36';

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

function externalLinks(html, host) {
  const out = new Set();
  const re = /href\s*=\s*["'](https?:\/\/[^"']+)["']/gi;
  let m;
  while ((m = re.exec(html))) {
    const u = m[1];
    if (u.includes(host)) continue;
    if (/\.(css|js|png|jpg|jpeg|svg|gif|ico|woff2?)(\?|$)/i.test(u)) continue;
    if (/duckduckgo|bing\.com\/|mojeek|startpage|google\.|w3\.org|schema\.org/i.test(u)) continue;
    out.add(u);
  }
  return [...out];
}

const q = 'premier league table standings';
const targets = [
  ['DuckDuckGo HTML GET', `https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`, 'duckduckgo.com'],
  ['DuckDuckGo Lite GET', `https://lite.duckduckgo.com/lite/?q=${encodeURIComponent(q)}`, 'duckduckgo.com'],
  ['Mojeek', `https://www.mojeek.com/search?q=${encodeURIComponent(q)}`, 'mojeek.com'],
  ['Bing', `https://www.bing.com/search?q=${encodeURIComponent(q)}`, 'bing.com'],
  ['Startpage', `https://www.startpage.com/sp/search?query=${encodeURIComponent(q)}`, 'startpage.com'],
  ['Brave', `https://search.brave.com/search?q=${encodeURIComponent(q)}`, 'brave.com'],
  ['Ecosia', `https://www.ecosia.org/search?q=${encodeURIComponent(q)}`, 'ecosia.org'],
  ['SearxNG (searx.be) JSON', `https://searx.be/search?q=${encodeURIComponent(q)}&format=json`, 'searx.be'],
  ['Wikipedia search API', `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(q)}&format=json&srlimit=5`, 'wikipedia.org'],
  ['Google News RSS', `https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=en-US&gl=US&ceid=US:en`, 'news.google.com'],
];

for (const [name, url, host] of targets) {
  try {
    const r = await req(url);
    const links = externalLinks(r.body, host);
    const flag = r.code === 200 && links.length > 0 ? 'USABLE' : 'not usable';
    console.log(
      `${flag.padEnd(11)} ${name.padEnd(24)} HTTP ${String(r.code).padEnd(4)} ` +
        `body ${String(r.body.length).padEnd(8)} external links ${links.length}`
    );
    if (links.length) console.log(`              e.g. ${links[0].slice(0, 100)}`);
  } catch (e) {
    console.log(`ERROR       ${name.padEnd(24)} ${e.message}`);
  }
}

// DuckDuckGo also accepts POST; the GET form is often blocked.
try {
  const body = `q=${encodeURIComponent(q)}`;
  const r = await req('https://lite.duckduckgo.com/lite/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body) },
    body,
  });
  const links = externalLinks(r.body, 'duckduckgo.com');
  console.log(
    `${(r.code === 200 && links.length ? 'USABLE' : 'not usable').padEnd(11)} ` +
      `${'DuckDuckGo Lite POST'.padEnd(24)} HTTP ${String(r.code).padEnd(4)} ` +
      `body ${String(r.body.length).padEnd(8)} external links ${links.length}`
  );
  if (links.length) console.log(`              e.g. ${links[0].slice(0, 100)}`);
} catch (e) {
  console.log(`ERROR       DuckDuckGo Lite POST     ${e.message}`);
}
