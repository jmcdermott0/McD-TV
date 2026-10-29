// Loads the web app's Live TV code (LO, LV's pure helpers, LH) out of docs/app/index.html, and reads playlists and
// XMLTV guides the way the apps do. Shared by tools/webtest/livehome-test.mjs and tools/livehome/audit.mjs.
import fs from 'node:fs';
import zlib from 'node:zlib';

export function loadEngine(htmlPath = new URL('../../docs/app/index.html', import.meta.url)) {
  const html = fs.readFileSync(htmlPath, 'utf8');
  const lo = /const LO = \(\(\) => \{[\s\S]*?\n\}\)\(\);/.exec(html);
  const lh = /\/\/ >>> LiveHome[^\n]*\n([\s\S]*?)\/\/ <<< LiveHome/.exec(html);
  const pure = /\/\/ ---- pure:[^\n]*\n([\s\S]*?)\/\/ ---- end pure ----/.exec(html);
  if (!lo || !lh || !pure) throw new Error('Live TV code blocks not found in index.html');
  // LV's pure helpers read SCHED-free inputs only; castNet and friends come along.
  return new Function(lo[0] + '\n' + lh[1] + '\nconst P = (() => {\n' + pure[1] + '\nreturn { gkey, eventProg, homeRank, netOf, guideMatch, westTwin, callSign };\n})();\nreturn { LO, LH, P };')();
}

export function readText(path) {
  const b = fs.readFileSync(path);
  return (b[0] === 0x1f && b[1] === 0x8b ? zlib.gunzipSync(b) : b).toString('utf8');
}

/** #EXTINF lines: name, group, tvg-id, url (movie and series entries skipped, like the apps). */
export function parseM3u(text) {
  const out = []; let pend = null;
  const attr = /([a-zA-Z-]+)="([^"]*)"/g;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (/^#EXTINF/i.test(line)) {
      const a = {}; let m; attr.lastIndex = 0;
      while ((m = attr.exec(line))) a[m[1].toLowerCase()] = m[2];
      let q = false, cut = -1;
      for (let i = 0; i < line.length; i++) { if (line[i] === '"') q = !q; else if (line[i] === ',' && !q) { cut = i; break; } }
      pend = { name: (cut >= 0 ? line.slice(cut + 1).trim() : '') || a['tvg-name'] || 'Channel', group: (a['group-title'] || '').trim() || 'Other', tvgId: (a['tvg-id'] || '').trim() };
    } else if (line && !line.startsWith('#') && pend) {
      if (!line.includes('/movie/') && !line.includes('/series/')) out.push({ ...pend, url: line });
      pend = null;
    }
  }
  return out;
}

const unesc = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
function xmltvTime(s) {
  const m = /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-]\d{4})?/.exec(String(s || '').trim()); if (!m) return 0;
  const off = m[7] ? (m[7][0] === '-' ? -1 : 1) * (+m[7].slice(1, 3) * 60 + +m[7].slice(3)) : 0;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)) - off * 60000;
}
/** XMLTV: channel id -> programmes [{ title, sub, desc, cats, start, end, live, rerun }], sorted. */
export function parseXmltv(text) {
  const out = new Map();
  const re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g; let m;
  while ((m = re.exec(text))) {
    const a = {}; m[1].replace(/([a-z-]+)="([^"]*)"/g, (_, k, v) => { a[k] = v; });
    const body = m[2];
    const tag = (t) => { const x = new RegExp('<' + t + '\\b[^>]*>([\\s\\S]*?)</' + t + '>').exec(body); return x ? unesc(x[1]).trim() : ''; };
    const cats = [...body.matchAll(/<category\b[^>]*>([\s\S]*?)<\/category>/g)].map((x) => unesc(x[1]).trim());
    const p = { title: tag('title'), sub: tag('sub-title'), desc: tag('desc'), cats, start: xmltvTime(a.start), end: xmltvTime(a.stop), live: /<live\s*\/?>/.test(body), rerun: /<previously-shown\b/.test(body) };
    if (!out.has(a.channel)) out.set(a.channel, []);
    out.get(a.channel).push(p);
  }
  for (const l of out.values()) l.sort((x, y) => x.start - y.start);
  return out;
}

/**
 * The web app's playlist clean-up (separators and repeated links dropped) and guide match (guideMatch, the same rules
 * as the TV's GuideMatch): a channel's own guide id, else the id another copy of the same network carries. West feeds
 * that read their East twin get its listings 3 hours later.
 */
export function prepare(E, raw, guide) {
  const lower = new Map([...guide.keys()].map((k) => [String(k).toLowerCase(), k]));
  const gm = E.P.guideMatch(raw.map((c) => ({ name: c.name, group: c.group, id: c.tvgId })));
  const seen = new Set(), chs = [], at = [];
  raw.forEach((c, i) => { if (E.LO.isSeparator(c.name) || seen.has(c.url)) return; seen.add(c.url); chs.push(c); at.push(i); });
  const gid = at.map((i) => (gm.idOf[i] && lower.get(gm.idOf[i])) || '');
  const shift = at.map((i) => gm.shift[i]);
  const country = chs.map((c) => E.LO.country(c.group, c.name));
  const section = chs.map((c) => E.LO.sectionTV(c.group, c.name));
  // Like Epg.parse: live events stay at their real time on a West feed and replace the delayed shows they overlap.
  const progs = (i) => {
    const l = (gid[i] ? guide.get(gid[i]) : null) || [];
    if (!shift[i]) return l;
    const live = l.filter((p) => p.live);
    return l.filter((p) => p.live || !live.some((x) => p.start + shift[i] < x.end && x.start < p.end + shift[i]))
      .map((p) => (p.live ? p : { ...p, start: p.start + shift[i], end: p.end + shift[i] })).sort((a, b) => a.start - b.start);
  };
  return { chs, gid, country, section, progs };
}
