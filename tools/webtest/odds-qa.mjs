// Odds board on the web Games view (docs/app, ODDS module), against real ESPN answers saved on Nov 16, 2026
// (app/src/test/resources/odds-*.json). The NFL scoreboard is the real Monday night game (LAC at BAL, pre-game) plus
// the same game marked live, whose core odds answer is the real "DraftKings - Live Odds" entry. Every other request is
// mocked or blocked. Run: python3 tools/webtest/srv.py & node tools/webtest/odds-qa.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';
const B = 'http://127.0.0.1:8781/index.html';
const RES = new URL('../../app/src/test/resources/', import.meta.url);
const sbReal = JSON.parse(fs.readFileSync(new URL('odds-scoreboard-nfl.json', RES)));
const coreLive = fs.readFileSync(new URL('odds-core-live-a.json', RES), 'utf8');
const pre = sbReal.events.find((e) => e.id === '401873065');
const live = JSON.parse(JSON.stringify(pre));
live.id = '999';
live.competitions[0].id = '999';
live.status = { type: { state: 'in', shortDetail: 'Q3 8:42' } };
live.competitions[0].status = live.status;
live.competitions[0].competitors.forEach((c, i) => { c.score = i ? '17' : '24'; });
const sb = { week: sbReal.week, season: sbReal.season, events: [pre, live] };
const b = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const fails = [];
const log = (...a) => console.log(...a);
let coreCalls = 0;
for (const [w, profile] of [[1280, 'p1'], [390, 'p1'], [1280, 'p3']]) {
  const ctx = await b.newContext({ viewport: { width: w, height: 900 } });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  await ctx.route('**/*', (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === '127.0.0.1') return r.continue();
    const json = (body) => r.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' }, body });
    if (u.hostname === 'site.api.espn.com') return json(JSON.stringify(u.pathname.includes('/nfl/') ? sb : { events: [] }));
    if (u.hostname === 'sports.core.api.espn.com') { coreCalls++; return json(u.pathname.includes('/events/999/') ? coreLive : '{"count":0,"items":[]}'); }
    return r.fulfill({ status: 404, headers: { 'Access-Control-Allow-Origin': '*' }, body: '' });
  });
  await p.goto(B);
  await p.evaluate((pid) => { localStorage.clear(); localStorage.setItem('mcdtv_web_profile', pid); localStorage.setItem('mcdtv_web_init', JSON.stringify({ m3u_url: 'http://127.0.0.1:8781/get.php?username=u&password=p&type=m3u_plus', tmdb_key: 'x' })); }, profile);
  await p.goto(B + '#/live'); await p.reload(); await p.waitForTimeout(3500);
  const chip = await p.$('#lvChips .chip[data-sec="game:NFL"]');
  if (!chip) { fails.push(`${w} ${profile} NFL chip missing`); await ctx.close(); continue; }
  await chip.click(); await p.waitForTimeout(2500);
  const books = await p.$$eval('.gm-book', (r) => r.map((x) => x.innerText.replace(/\s+/g, ' ')));
  log(w, profile, 'books', books.length, books.join(' || '));
  if (profile === 'p3') {
    if (books.length) fails.push('Kids profile shows odds');
  } else {
    if (books.length !== 2) fails.push(`${w} expected 2 odds boards, got ${books.length}`);
    const preTxt = books.find((t) => !/LIVE/.test(t)) || '';
    for (const want of ['+4.5', '-108', '-4.5', '-112', 'O 39.5', '-105', 'U 39.5', '-115', '+185', '-225', 'LAC 34%', 'BAL 66%', 'DraftKings']) if (!preTxt.toLowerCase().includes(want.toLowerCase())) fails.push(`${w} pre-game board lacks ${want}: ${preTxt}`);
    const liveTxt = books.find((t) => /LIVE/.test(t)) || '';
    for (const want of ['-150', '+500', 'Live odds', 'Draw +235']) if (!liveTxt.toLowerCase().includes(want.toLowerCase())) fails.push(`${w} live board lacks ${want}: ${liveTxt}`);
    const sw = await p.evaluate(() => document.documentElement.scrollWidth);
    if (sw > w) fails.push(`${w} horizontal overflow ${sw}`);
    await p.screenshot({ path: `/tmp/odds-web-${w}.png`, fullPage: false });
  }
  if (errs.length) fails.push(`${w} ${profile} page errors: ${errs.join(' | ')}`);
  await ctx.close();
}
log('core odds calls', coreCalls);
if (coreCalls < 1) fails.push('live odds never requested');
await b.close();
log(fails.length ? 'FAILS ' + fails.length + '\n' + fails.join('\n') : 'FAILS 0');
process.exit(fails.length ? 1 : 0);
