// Extra guide for free streaming channels (GX in docs/app, data/GuideExtra.kt on the TV), in a real browser.
// The Roku guide file is the real i.mjh.nz excerpt in app/src/test/resources (the four channels from J's photo of
// Oct 29, 2026), served gzipped where the app asks for it; the Plex file answers 404. The playlist is an Xtream
// account (username gx) with those four channels named as in J's playlist, plus channels the provider's guide has.
// Checks: the four show real titles, a channel with the provider's guide keeps it, NBC 13 keeps its guide.
// Run: python3 tools/webtest/srv.py & node tools/webtest/guide-extra-qa.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';
import zlib from 'node:zlib';

const B = 'http://127.0.0.1:' + (process.env.PORT || '8781') + '/index.html';
const AT = Date.parse('2026-10-29T23:30:00Z');
const roku = zlib.gzipSync(fs.readFileSync(new URL('../../app/src/test/resources/guide-extra-roku-fixture.xml', import.meta.url)));
const fails = [];
const log = (...a) => console.log(...a);
const b64 = (t) => Buffer.from(t).toString('base64');
const S = AT / 1000;
const streams = [
  ['RK: LAW & CRIME', ''], ['RK: REELZ FAMOUS AND INFAMOUS', ''], ['PRIME: CHAOS ON CAM BY A&E ᴿᴬᵂ', ''], ['RK: UNIVERSAL CRIME', ''],
  ['US: NBC 13 BIRMINGHAM AL (WVTM) HD', 'WVTM.us'], ['US: ESPN HD', 'ESPN.us'], ['US: UNIVERSAL CRIME', 'UniversalCrime.us'], ['US: KEY TV HD', ''],
].map(([name, epg], i) => ({ stream_id: i + 1, name, category_id: '1', stream_icon: '', epg_channel_id: epg }));
const provider = {
  'WVTM.us': ['WVTM 13 News at 6', 'Wheel of Fortune'], 'ESPN.us': ['SportsCenter', 'NFL Live'], 'UniversalCrime.us': ['Provider Show', 'Provider Next'],
};

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
for (const width of [390, 1280]) {
  const ctx = await browser.newContext({ viewport: { width, height: 900 }, serviceWorkers: 'block', timezoneId: 'America/Chicago' });
  const p = await ctx.newPage();
  const errs = [], gx = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  await ctx.route('**/*', (r) => {
    const u = new URL(r.request().url());
    if (u.hostname === '127.0.0.1' && u.pathname.endsWith('player_api.php') && u.searchParams.get('username') === 'gx') {
      const a = u.searchParams.get('action') || '';
      let d = {};
      if (a === '') d = { user_info: { auth: 1, status: 'Active', allowed_output_formats: ['m3u8', 'ts'] } };
      else if (a === 'get_live_categories') d = [{ category_id: '1', category_name: 'US| ROKU' }];
      else if (a === 'get_live_streams') d = streams;
      else if (a === 'get_simple_data_table') {
        const s = streams.find((x) => String(x.stream_id) === u.searchParams.get('stream_id'));
        const t = (s && provider[s.epg_channel_id]) || [];
        d = { epg_listings: t.map((title, i) => ({ title: b64(title), description: '', start_timestamp: String(S - 1800 + i * 3600), stop_timestamp: String(S + 1800 + i * 3600) })) };
      }
      return r.fulfill({ status: 200, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(d) });
    }
    if (u.hostname === '127.0.0.1') return r.continue();
    if (u.hostname === 'raw.githubusercontent.com' && u.pathname.startsWith('/matthuisman/i.mjh.nz/')) {
      gx.push(u.pathname);
      if (u.pathname.endsWith('/Roku/all.xml.gz')) return r.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/octet-stream' }, body: roku });
    }
    if (u.hostname === 'site.api.espn.com') return r.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' }, body: '{"events":[]}' });
    return r.fulfill({ status: 404, headers: { 'Access-Control-Allow-Origin': '*' }, body: '' });
  });
  await p.clock.setFixedTime(new Date(AT));
  await p.goto(B);
  await p.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('mcdtv_web_profile', 'p1');
    localStorage.setItem('mcdtv_web_init', JSON.stringify({ m3u_url: 'http://127.0.0.1:8781/get.php?username=gx&password=p&type=m3u_plus', tmdb_key: 'x' }));
  });
  await p.goto(B + '#/live'); await p.reload();
  await p.waitForSelector('#lvQ', { timeout: 60000 }).catch(() => fails.push(`${width}: Live TV never showed`));
  const rowText = async (q) => {
    await p.fill('#lvQ', q); await p.waitForTimeout(1200);
    return p.$$eval('.lv-row', (rs) => rs.map((r) => r.innerText.replace(/\s+/g, ' ').trim()));
  };
  // Wait for the extra guide to land on a row.
  for (let i = 0; i < 20; i++) { const t = (await rowText('law')).join(' '); if (/Police Pursuits/.test(t)) break; await p.waitForTimeout(500); }
  const want = [['law', 'LAW & CRIME', 'Police Pursuits by Law&Crime'], ['reelz', 'REELZ', "Sherri Papini's Abduction"], ['chaos', 'CHAOS ON CAM', 'Parking Wars'],
    ['universal', 'RK: UNIVERSAL CRIME', 'Columbo']];
  for (const [q, name, title] of want) {
    const rows = await rowText(q);
    const row = rows.find((r) => r.toUpperCase().includes(name.toUpperCase()) && !/^US: /.test(r)) || rows.find((r) => r.toUpperCase().includes(name.toUpperCase())) || '';
    log(width, q, '|', row.slice(0, 140));
    if (!row.includes(title)) fails.push(`${width} ${name}: expected "${title}", got "${row.slice(0, 120)}"`);
  }
  const uni = (await rowText('universal')).find((r) => /Provider Show/.test(r));
  log(width, 'provider keeps its guide |', (uni || 'missing').slice(0, 120));
  if (!uni) fails.push(`${width} US: UNIVERSAL CRIME lost its provider guide`);
  for (const [q, title] of [['nbc 13', 'WVTM 13 News at 6'], ['espn', 'SportsCenter']]) {
    const rows = await rowText(q); log(width, q, '|', (rows[0] || '').slice(0, 120));
    if (!rows.some((r) => r.includes(title))) fails.push(`${width} ${q}: provider guide "${title}" missing`);
  }
  const key = (await rowText('key tv'))[0] || '';
  log(width, 'no guide anywhere |', key.slice(0, 100));
  if (!/No guide/.test(key)) fails.push(`${width} KEY TV should still say No guide: "${key}"`);
  log(width, 'guide files asked:', gx.join(', '));
  // Settings > Playback > Live TV guide: off drops the extra rows at once and keeps the provider's; on brings them back.
  await p.goto(B + '#/settings/playback'); await p.waitForSelector('#gxOn', { timeout: 15000 }).catch(() => fails.push(`${width}: no Live TV guide switch`));
  if (!(await p.isChecked('#gxOn').catch(() => false))) fails.push(`${width}: the switch should start on`);
  await p.click('#gxOn'); await p.waitForTimeout(300);
  const offKey = await p.evaluate(() => localStorage.getItem('mcdtv_web_guide_extra_off'));
  await p.goto(B + '#/live'); await p.waitForSelector('#lvQ', { timeout: 30000 }).catch(() => {});
  const lawOff = (await rowText('law')).find((r) => /RK: LAW/.test(r)) || '';
  const nbcOff = (await rowText('nbc 13'))[0] || '';
  log(width, 'switch off | stored', offKey, '|', lawOff.slice(0, 80), '|', nbcOff.slice(0, 60));
  if (offKey !== '1' || /Police Pursuits/.test(lawOff) || !/No guide/.test(lawOff)) fails.push(`${width} switch off: "${lawOff}" stored ${offKey}`);
  if (!/WVTM 13 News at 6/.test(nbcOff)) fails.push(`${width} switch off: NBC 13 lost its provider guide`);
  await p.reload(); await p.waitForSelector('#lvQ', { timeout: 30000 }).catch(() => {}); await p.waitForTimeout(1500);
  const lawReload = (await rowText('law')).find((r) => /RK: LAW/.test(r)) || '';
  if (/Police Pursuits/.test(lawReload)) fails.push(`${width} switch off: came back after a reload`);
  await p.goto(B + '#/settings/playback'); await p.waitForSelector('#gxOn'); await p.click('#gxOn'); await p.waitForTimeout(300);
  await p.goto(B + '#/live'); await p.waitForSelector('#lvQ', { timeout: 30000 }).catch(() => {});
  let lawOn = '';
  for (let i = 0; i < 20; i++) { lawOn = (await rowText('law')).find((r) => /RK: LAW/.test(r)) || ''; if (/Police Pursuits/.test(lawOn)) break; await p.waitForTimeout(500); }
  log(width, 'switch on again |', lawOn.slice(0, 80));
  if (!/Police Pursuits/.test(lawOn)) fails.push(`${width} switch on again: "${lawOn}"`);
  const kept = await p.evaluate(() => ['mcdtv_web_init', 'mcdtv_web_profile'].every((k) => localStorage.getItem(k)));
  if (!kept) fails.push(`${width}: the switch touched the setup keys`);
  if (errs.length) fails.push(`${width} page errors: ${errs.join(' | ')}`);
  await ctx.close();
}
await browser.close();
if (fails.length) { console.log('FAILS', fails.length); fails.forEach((f) => console.log(' - ' + f)); process.exit(1); }
console.log('guide extra qa ok');
