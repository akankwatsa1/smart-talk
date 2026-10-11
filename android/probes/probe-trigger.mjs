// Does the automatic search trigger fire when it should - and NOT when it
// shouldn't?
//
// The prompt here is matched with `keyword in promptText`, which is a substring
// test. That is suspicious for short words: "now" is a substring of "know" and
// of "knowledge", so a question like "I don't know, explain recursion" would
// trigger a pointless web search and slow every such answer down.

const TIME_SENSITIVE = [
  'current', 'currently', 'today', 'tonight', 'yesterday', 'tomorrow',
  'latest', 'recent', 'recently', 'now', 'this week', 'this month', 'this year',
  'standings', 'table', 'league', 'score', 'scores', 'result', 'results',
  'fixture', 'fixtures', 'match', 'matches', 'who won', 'winner',
  'news', 'update', 'up to date', 'up-to-date', 'live',
  'price', 'prices', 'cost', 'costs', 'rate', 'rates', 'exchange',
  'weather', 'forecast', 'stock', 'share price', 'market',
  'release', 'released', 'launch', 'launched', 'version', 'election',
  'president', 'prime minister', 'ceo', 'ceo of', 'how many', 'population',
  'as of', 'in 2025', 'in 2026', 'in 2027',
];

function current(prompt) {
  const p = ' ' + prompt.toLowerCase() + ' ';
  if (TIME_SENSITIVE.some((k) => p.includes(k))) return true;
  return /\b20(2[4-9]|[3-9]\d)\b/.test(p);
}

function fixed(prompt) {
  const p = prompt.toLowerCase();
  // Match whole words only, and allow a trailing s for plurals.
  const patterns = TIME_SENSITIVE.map(
    (k) => new RegExp('(?<![a-z])' + k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(?![a-z])')
  );
  if (patterns.some((re) => re.test(p))) return true;
  return /\b20(2[4-9]|[3-9]\d)\b/.test(p);
}

const shouldSearch = [
  'what are the current premier league standings',
  'who won the match last night',
  'latest news on the election',
  'what is the price of petrol in Uganda',
  'premier league table',
  'what happened in 2026',
  'weather forecast for Kasese',
];

const shouldNotSearch = [
  'hi',
  'explain recursion in simple terms',
  "i don't know, help me understand",
  'summarise my knowledge of algebra',
  'write a python function to sort a list',
  'translate this sentence into Kiswahili',
  'what is 2 + 2',
];

console.log('=== SHOULD search ===');
let bad = 0;
for (const p of shouldSearch) {
  const c = current(p);
  const f = fixed(p);
  const flag = f ? 'ok  ' : 'MISS';
  if (!f) bad++;
  console.log(`  [${flag}] current=${String(c).padEnd(5)} fixed=${String(f).padEnd(5)}  ${p}`);
}

console.log('\n=== SHOULD NOT search (a false positive here wastes time and tokens) ===');
for (const p of shouldNotSearch) {
  const c = current(p);
  const f = fixed(p);
  const flag = f ? 'FALSE POSITIVE' : 'ok  ';
  if (f) bad++;
  console.log(`  [${flag}] current=${String(c).padEnd(5)} fixed=${String(f).padEnd(5)}  ${p}`);
}

console.log(`\nproblems remaining with the fixed version: ${bad}`);
