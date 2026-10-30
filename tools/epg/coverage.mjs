// Guide coverage audit: for every channel of a playlist, does the guide give it a show now or next, and if not, why.
// Uses the apps' own code (guideMatch from docs/app/index.html, the TV's GuideMatch twin) and the XMLTV guide as the
// TV reads it. Prints counts and channel names only, never a link or a login.
//
//   node tools/epg/coverage.mjs                              the Live TV stand-in (tools/livehome/fixture)
//   node tools/epg/coverage.mjs playlist.m3u guide.xml       your own files (download them yourself; plain or .gz)
//   AT=2026-10-29T08:05:00-05:00 node tools/epg/coverage.mjs ...   another moment (default: the fixture's snapshot A, or now)
//   LIST=b_us_network node tools/epg/coverage.mjs ...       also print the channels of one bucket
//
// Buckets for channels with nothing now or next:
//   a_id_no_programmes   a guide id, but the guide has no programme for it at all
//   e_outside_window     programmes exist, none from now to 12 hours ahead (or their times did not parse)
//   b_*                  no guide id and no copy of the same network carries one (split by kind): the guide feed does
//                        not carry it. More guide sources are the only fix (hand these to the guide-sources work).
import fs from 'node:fs';
import { loadEngine, readText, parseM3u, parseXmltv } from '../livehome/load.mjs';

const fx = (f) => new URL('../livehome/fixture/' + f, import.meta.url);
const [m3uPath, xmlPath] = process.argv.slice(2);
const E = loadEngine();
const raw = parseM3u(readText(m3uPath || fx('playlist.m3u.gz')));
const xmlText = readText(xmlPath || (m3uPath ? '' : fx('guide.xml.gz')));
const guide = parseXmltv(xmlText);
const now = Date.parse(process.env.AT || (m3uPath ? new Date().toISOString() : '2026-10-25T12:40:00-05:00'));
const WINDOW = 12 * 3600000;
const badTimes = (xmlText.match(/<programme\b[^>]*>/g) || []).filter((t) => !/start="\d{12}/.test(t) || !/stop="\d{12}/.test(t)).length;

const lower = new Map([...guide.keys()].map((k) => [String(k).toLowerCase(), k]));
const listOf = (id) => (id && lower.has(id) ? guide.get(lower.get(id)) : null);
const status = (id, sh) => {
  const l = listOf(id);
  if (!l || !l.length) return 'none';
  const at = (p) => (p.live ? 0 : sh);
  const cur = l.some((p) => p.start + at(p) <= now && p.end + at(p) > now);
  const next = l.some((p) => p.start + at(p) > now && p.start + at(p) < now + WINDOW);
  return cur && next ? 'now+next' : cur ? 'now only' : next ? 'next only' : 'outside';
};

// Before: Epg.parse up to v0.2.215 (own tvg-id, else the first id of a copy with the same guide key).
const donor = new Map();
for (const c of raw) { const id = (c.tvgId || '').toLowerCase(); const k = E.P.gkey(c.name); if (id && k && !donor.has(k)) donor.set(k, id); }
const gm = E.P.guideMatch(raw.map((c) => ({ name: c.name, group: c.group, id: c.tvgId })));

const seen = new Set(), before = {}, after = {}, buckets = {}, rules = {}, lists = {};
const add = (o, k) => { o[k] = (o[k] || 0) + 1; };
let total = 0;
raw.forEach((c, i) => {
  if (E.LO.isSeparator(c.name) || seen.has(c.url)) return;
  seen.add(c.url); total++;
  const old = (c.tvgId || '').toLowerCase() || donor.get(E.P.gkey(c.name)) || '';
  add(before, old ? status(old, 0) : 'no id');
  const id = gm.idOf[i], st = id ? status(id, gm.shift[i]) : 'no id';
  add(after, st);
  let b;
  if (st === 'now+next' || st === 'now only' || st === 'next only') { b = 'listed'; add(rules, gm.how[i]); }
  else if (st === 'none') b = 'a_id_no_programmes';
  else if (st === 'outside') b = 'e_outside_window';
  else {
    const country = E.LO.country(c.group, c.name);
    const r = E.LH.identify(c.name, c.group, country, E.LO.sectionTV(c.group, c.name));
    const fold = r.fold ? r.fold.key : '';
    if (r.kind === 'intl') b = 'b_international';
    else if (/^ev:|^ppv/.test(r.key) || r.kind === 'events') b = 'b_events_ppv';
    else if (/^svc:/.test(fold) || /^\s*(?:PRIME|AMAZON|TUBI|RK|ROKU|PLUTO|PLEX|XUMO|SAMSUNG|VIZIO|LG|DISTRO|STIRR|FREEVEE)\s*[:|]/i.test(c.name)) b = 'b_free_lineup';
    else if (r.kind === '247' || /24/.test(fold)) b = 'b_24_7';
    else if (r.kind === 'local' || r.region === 'market' || /stations/.test(r.key + fold)) b = 'b_local_station';
    else if (r.net) b = 'b_us_network';
    else b = 'b_other';
  }
  add(buckets, b);
  if (process.env.LIST === b) (lists[b] = lists[b] || []).push(c.name + (id ? ' -> ' + id : ''));
});

const n = (o, ks) => ks.reduce((s, k) => s + (o[k] || 0), 0);
const L = ['now+next', 'now only', 'next only'];
console.log(`Playlist: ${raw.length} entries, ${total} channels after separators and repeated links.`);
console.log(`Guide: ${guide.size} guide channels; ${badTimes} programmes with start or stop times that do not parse.`);
console.log(`At ${new Date(now).toISOString()}:`);
console.log(`  channels with a show now or next: before ${n(before, L)}, after ${n(after, L)} (of ${total})`);
for (const k of [...L, 'outside', 'none', 'no id']) console.log(`    ${k.padEnd(10)} before ${String(before[k] || 0).padStart(6)}  after ${String(after[k] || 0).padStart(6)}`);
console.log('  listed, by how the guide id was found: ' + Object.entries(rules).map(([k, v]) => `${k} ${v}`).join(', '));
console.log('  buckets:');
for (const [k, v] of Object.entries(buckets).sort((a, b) => b[1] - a[1])) console.log(`    ${k.padEnd(20)} ${String(v).padStart(6)}`);
for (const [k, v] of Object.entries(lists)) { console.log(`\n${k}:`); v.forEach((s) => console.log('  ' + s)); }
