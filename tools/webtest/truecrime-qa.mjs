// True Crime in the browser: Home's True Crime row and Browse > True Crime, with TMDB answered from the real
// capture in tools/truecrime (fixture.txt: titles and keywords; queries.txt: what each discover call returned;
// posters.txt: poster paths). Posters load from image.tmdb.org when it is reachable; every other host is blocked.
// Checks: the row and the grid show the same titles in the same order as tools/truecrime/expected.json (the TV's
// shelf), Home shows the first 20, Browse shows the tile, Kids gets neither. Screenshots: tools/truecrime/qa/*.png.
// Run: python3 tools/webtest/srv.py & node tools/webtest/truecrime-qa.mjs   (CHROME_PATH: optional Chromium binary)
import { chromium } from 'playwright';
import fs from 'node:fs';

const B = 'http://127.0.0.1:8781/index.html';
const T = (f) => new URL('../truecrime/' + f, import.meta.url);
const lines = (f) => fs.readFileSync(T(f), 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith('#'));
const titles = new Map(lines('fixture.txt').map((l) => {
  const p = l.split('|');
  return [p[0], { key: p[0], name: p[1], year: +p[2], genres: p[3].split(',').filter(Boolean).map(Number), lang: p[4], rating: +p[5], votes: +p[6], kws: p[7].split(';').filter(Boolean) }];
}));
const posters = new Map(lines('posters.txt').join(' ').split(/\s+/).filter(Boolean).map((x) => x.split('=')));
const pages = new Map(lines('queries.txt').map((l) => { const [h, keys] = l.split(': '); return [h, keys.split(' ')]; }));
const expected = JSON.parse(fs.readFileSync(T('expected.json'), 'utf8')).shelf;
const KW_IDS = { 'true crime': 33722, 'serial killer': 10714, 'murder investigation': 161982, 'unsolved murder': 312874, 'unsolved murders': 264433,
  'cold case': 210662, 'wrongful conviction': 171989, 'wrongful imprisonment': 12662, 'murder trial': 207240, 'crime documentary': 307587 };
const DOC_IDS = new Set(Object.entries(KW_IDS).filter(([n]) => n !== 'true crime').map(([, id]) => String(id)));
const normName = (s) => String(s).toLowerCase().replace(/[^\p{L}\p{Nd}]/gu, '');

/** A fixture title as TMDB's list answers carry it. */
function tmdbItem(t) {
  const [type, id] = t.key.split(':');
  const date = t.year + '-01-01';
  return { id: +id, ...(type === 'movie' ? { title: t.name, release_date: date } : { name: t.name, first_air_date: date }),
    genre_ids: t.genres, vote_count: t.votes, vote_average: t.rating, original_language: t.lang, origin_country: [], poster_path: posters.get(t.key) || null, backdrop_path: null, overview: '' };
}
function answer(u) {
  const path = u.pathname.replace(/^\/3/, ''), q = u.searchParams;
  let m;
  if ((m = /^\/discover\/(movie|tv)$/.exec(path))) {
    const type = m[1], page = q.get('page') || '1', g = q.get('with_genres') || '', k = q.get('with_keywords') || '';
    let name = null;
    if (g === '99,80' && !k) name = 'docCrime';
    else if (k === '33722') name = 'trueCrimeKw';
    else if (g === '99' && k && k.split('|').every((x) => DOC_IDS.has(x))) name = 'docKw';
    const keys = name ? pages.get(`${type} ${name} ${page}`) || [] : [];
    return { page: +page, results: keys.map((key) => tmdbItem(titles.get(key))) };
  }
  if (path === '/search/keyword') { const n = (q.get('query') || '').toLowerCase(); return { results: KW_IDS[n] ? [{ id: KW_IDS[n], name: n }] : [] }; }
  if ((m = /^\/(movie|tv)\/(\d+)\/keywords$/.exec(path))) {
    const t = titles.get(m[1] + ':' + m[2]);
    return { id: +m[2], [m[1] === 'movie' ? 'keywords' : 'results']: (t ? t.kws : []).map((name, i) => ({ id: i + 1, name })) };
  }
  if ((m = /^\/search\/(movie|tv)$/.exec(path))) {
    const want = normName(q.get('query') || ''), y = +(q.get('year') || q.get('first_air_date_year') || 0);
    const hits = [...titles.values()].filter((t) => t.key.startsWith(m[1] + ':') && normName(t.name) === want && (!y || t.year === y));
    return { results: hits.map(tmdbItem) };
  }
  return { page: 1, results: [] }; // trending, popular and the rest: nothing (this test is about True Crime)
}

const out = new URL('../truecrime/qa/', import.meta.url);
fs.mkdirSync(out, { recursive: true });
const b = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const fails = [];
const check = (name, ok, info) => { console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (ok || info === undefined ? '' : ': ' + JSON.stringify(info).slice(0, 400))); if (!ok) fails.push(name); };
const name = (key) => titles.get(key).name;

for (const w of [390, 1280]) {
  const ctx = await b.newContext({ viewport: { width: w, height: 844 }, deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  let images = 0;
  await ctx.route('**/*', async (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === '127.0.0.1') return r.continue();
    if (u.hostname === 'api.themoviedb.org') return r.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' }, body: JSON.stringify(answer(u)) });
    if (u.hostname === 'image.tmdb.org') { images++; return r.continue().catch(() => r.abort()); }
    return r.fulfill({ status: 404, headers: { 'Access-Control-Allow-Origin': '*' }, body: '' });
  });
  await p.goto(B);
  await p.evaluate(() => { localStorage.clear(); localStorage.setItem('mcdtv_web_profile', 'p1'); localStorage.setItem('mcdtv_web_init', JSON.stringify({ tmdb_key: 'x', origin_filter: 'all' })); });

  // Home: the True Crime row is the first 20 of the shelf.
  await p.goto(B + '#/'); await p.reload();
  await p.waitForSelector('#row-truecrime .scroller a, #row-truecrime .empty', { timeout: 30000 }).catch(() => {});
  await p.waitForTimeout(2500);
  const row = await p.$$eval('#row-truecrime .scroller [data-id], #row-truecrime .scroller a', (as) => as.map((a) => a.getAttribute('aria-label') || a.querySelector('img')?.alt || a.textContent.trim()).filter(Boolean));
  const head = await p.$eval('#row-truecrime h2', (h) => h.textContent).catch(() => '');
  check(`${w}px Home: "True Crime" row heading`, head === 'True Crime', head);
  check(`${w}px Home: the row is the shelf's first 20`, row.length === 20 && expected.slice(0, 20).every((k, i) => row[i] && row[i].includes(name(k))), row.slice(0, 6));
  await p.$eval('#row-truecrime', (el) => el.scrollIntoView({ block: 'center' }));
  await p.waitForTimeout(1500);
  await p.screenshot({ path: new URL(`home-truecrime-${w}.png`, out).pathname });

  // Browse: the tile, then the grid.
  await p.goto(B + '#/browse'); await p.waitForTimeout(800);
  const tile = await p.$eval('a.tile[href="#/browse/truecrime"]', (a) => a.innerText).catch(() => '');
  check(`${w}px Browse: True Crime tile`, /True Crime/i.test(tile), tile);
  await p.screenshot({ path: new URL(`browse-truecrime-${w}.png`, out).pathname });
  await p.click('a.tile[href="#/browse/truecrime"]');
  await p.waitForSelector('#grid a, #grid [data-id]', { timeout: 30000 }).catch(() => {});
  await p.waitForTimeout(2500);
  const grid = await p.$$eval('#grid > *', (as) => as.map((a) => a.getAttribute('aria-label') || a.querySelector('img')?.alt || a.textContent.trim()));
  const h1 = await p.$eval('.page h1', (h) => h.textContent).catch(() => '');
  check(`${w}px Browse > True Crime: heading`, h1 === 'True Crime', h1);
  check(`${w}px Browse > True Crime: 60 titles, the shelf's order (same as the TV)`, grid.length === 60 && expected.slice(0, 60).every((k, i) => grid[i] && grid[i].includes(name(k))), { n: grid.length, first: grid.slice(0, 5) });
  check(`${w}px Browse > True Crime: no crime fiction`, !grid.some((g) => /^(Dexter|The Godfather|GoodFellas|Se7en|Criminal Minds)\b/.test(g)));
  await p.screenshot({ path: new URL(`grid-truecrime-${w}.png`, out).pathname });
  const sw = await p.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  check(`${w}px no sideways scroll`, sw <= 0, sw);

  // Kids: no row, no tile.
  await p.evaluate(() => localStorage.setItem('mcdtv_web_profile', 'p3'));
  await p.goto(B + '#/browse'); await p.reload(); await p.waitForTimeout(800);
  check(`${w}px Kids: no True Crime tile`, !(await p.$('a.tile[href="#/browse/truecrime"]')));
  check(`${w}px no page errors`, errs.length === 0, errs);
  console.log(`      ${w}px posters requested from image.tmdb.org: ${images}`);
  await ctx.close();
}
await b.close();
if (fails.length) { console.error(`FAIL ${fails.length}`); process.exit(1); }
console.log('OK  True Crime in the browser');
