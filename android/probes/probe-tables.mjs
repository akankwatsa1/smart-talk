// Prove a better table conversion before porting it to Kotlin.
//
// Diagnosis: BBC puts the club name inside <span class="visually-hidden">, but
// the cell also contains nested <div>s. Treating </div> as a line break splits
// one table cell across several lines, so rows arrive as bare numbers.
//
// Fix: handle tables FIRST. Each <tr> becomes exactly one pipe-separated line,
// with all inner markup collapsed to spaces, so a cell never wraps. The table is
// then lifted out of the flowing prose and appended as clean rows.

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

const decodeEntities = (s) =>
  s
    .replace(/&nbsp;|&#160;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;|&#34;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&mdash;|&ndash;|&minus;/g, '-');

/** One cell, flattened onto a single line. */
function cellText(cell) {
  const withoutTags = cell.replace(/<[^>]+>/g, ' ');
  return decodeEntities(withoutTags).replace(/\s+/g, ' ').trim();
}

/** Every <tr> becomes exactly one line. */
function convertTable(tableHtml) {
  const lines = [];
  for (const row of tableHtml.match(/<tr[\s\S]*?<\/tr>/gi) || []) {
    const cells = [];
    for (const cell of row.match(/<t[dh][^>]*>[\s\S]*?<\/t[dh]>/gi) || []) {
      cells.push(cellText(cell));
    }
    // Drop cells that are entirely empty, but keep the rest aligned.
    const meaningful = cells.filter((c, i) => c !== '' || i >= 1);
    if (meaningful.join('').trim() === '') continue;
    lines.push(meaningful.join(' | '));
  }
  return lines;
}

function extractReadableText(html) {
  // 1. Tables first, as clean rows.
  const tableRows = [];
  for (const table of html.match(/<table[\s\S]*?<\/table>/gi) || []) {
    tableRows.push(...convertTable(table));
  }

  // 2. Remaining prose, tables removed.
  let s = html.replace(/<table[\s\S]*?<\/table>/gi, '\n');
  s = s.replace(/<script[\s\S]*?<\/script>/gi, ' ');
  s = s.replace(/<style[\s\S]*?<\/style>/gi, ' ');
  s = s.replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ');
  s = s.replace(/<svg[\s\S]*?<\/svg>/gi, ' ');
  s = s.replace(/<head[\s\S]*?<\/head>/gi, ' ');
  s = s.replace(/<!--[\s\S]*?-->/g, ' ');
  s = s.replace(/<br\s*\/?>/gi, '\n');
  s = s.replace(/<\/(p|div|li|h[1-6]|section|article|header|footer|nav|blockquote)\s*>/gi, '\n');
  s = s.replace(/<[^>]+>/g, ' ');
  s = decodeEntities(s);

  const prose = [];
  let prev = '';
  for (const raw of s.split('\n')) {
    const line = raw.replace(/[\u00A0\t]+/g, ' ').replace(/ {2,}/g, ' ').trim();
    if (line.length < 2) continue;
    if (line === prev) continue;
    prose.push(line);
    prev = line;
  }

  return { prose, tableRows };
}

async function main() {
  for (const url of [
    'https://www.bbc.co.uk/sport/football/premier-league/table',
    'https://en.wikipedia.org/wiki/2026%E2%80%9327_Premier_League',
  ]) {
    console.log('='.repeat(70));
    console.log(url);
    console.log('='.repeat(70));
    const html = await get(url);
    const { prose, tableRows } = extractReadableText(html);
    console.log(`prose lines: ${prose.length}, table rows: ${tableRows.length}`);

    // Rows that look like standings: a position number and several numeric cells.
    const standings = tableRows.filter((r) => {
      const cells = r.split(' | ');
      const nums = cells.filter((c) => /^-?\d+$/.test(c.trim())).length;
      return nums >= 4 && /[A-Za-z]{3}/.test(r);
    });
    console.log(`standings-like rows (numbers AND a name): ${standings.length}`);
    standings.slice(0, 8).forEach((r) => console.log('   ' + r.slice(0, 135)));
    console.log('');
  }
}

main().catch((e) => {
  console.error('failed:', e.message);
  process.exit(1);
});
