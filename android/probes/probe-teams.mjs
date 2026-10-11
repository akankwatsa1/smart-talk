// Why did the extracted BBC standings rows lose the TEAM NAMES?
//
// The e2e probe returned rows like "5 | 5 | 0 | 0 | 13 | 5 | 8 | 15" - real
// numbers with no club attached. Numbers without names are worse than no answer,
// because a model will map them to the wrong teams. This inspects the actual
// row markup to find where the club name lives.

import https from 'node:https';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function get(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'User-Agent': UA, Accept: 'text/html' } }, (r) => {
        let d = '';
        r.on('data', (c) => (d += c));
        r.on('end', () => resolve(d));
      })
      .on('error', reject);
  });
}

const html = await get('https://www.bbc.co.uk/sport/football/premier-league/table');

// Find the first standings table body row.
const tableMatch = /<table[\s\S]*?<\/table>/i.exec(html);
if (!tableMatch) {
  console.log('no <table> found at all');
  process.exit(1);
}
const table = tableMatch[0];
const rows = table.match(/<tr[\s\S]*?<\/tr>/gi) || [];
console.log(`table has ${rows.length} rows; inspecting the first three with content\n`);

let shown = 0;
for (const row of rows) {
  const stripped = row.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
  if (stripped.length < 3) continue;
  console.log('--- RAW ROW HTML (first 900 chars) ---');
  console.log(row.slice(0, 900));
  console.log('');
  console.log('--- alt / title / aria-label attributes in this row ---');
  const attrs = [...row.matchAll(/(alt|title|aria-label|data-[a-z-]*)="([^"]{1,60})"/gi)]
    .map((m) => `${m[1]}="${m[2]}"`);
  console.log(attrs.length ? '   ' + attrs.join('\n   ') : '   (none)');
  console.log('');
  if (++shown >= 2) break;
}
