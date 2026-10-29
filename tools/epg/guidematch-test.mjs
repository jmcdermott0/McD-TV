// Guide matching parity: the web app's guideMatch (docs/app/index.html) on tools/epg/vectors-input.json (real names
// from J's playlist) and on the Live TV stand-in playlist (tools/livehome/fixture). The TV's GuideMatch must give the
// same answers: app/src/test/java/com/mcd/tv/data/GuideMatchTest.kt reads the same expected file.
//   node tools/epg/guidematch-test.mjs            check
//   UPDATE=1 node tools/epg/guidematch-test.mjs   rewrite tools/epg/guidematch-expected.json (review the diff)
import fs from 'node:fs';
import crypto from 'node:crypto';
import { loadEngine, readText, parseM3u } from '../livehome/load.mjs';

const here = (f) => new URL('./' + f, import.meta.url);
const E = loadEngine();
const lines = (chs, r) => chs.map((c, i) => `${i}\t${r.idOf[i]}\t${r.how[i]}\t${r.shift[i]}`).join('\n');

const v = JSON.parse(fs.readFileSync(here('vectors-input.json'), 'utf8'));
const vch = v.channels.map(([name, group, id]) => ({ name, group, id }));
const vr = E.P.guideMatch(vch);
const vectors = vch.map((c, i) => ({ name: c.name, id: vr.idOf[i], how: vr.how[i], shift: vr.shift[i] }));

const raw = parseM3u(readText(here('../livehome/fixture/playlist.m3u.gz')));
const fch = raw.map((c) => ({ name: c.name, group: c.group, id: c.tvgId }));
const fr = E.P.guideMatch(fch);
const how = {};
fr.how.forEach((h) => { how[h || 'none'] = (how[h || 'none'] || 0) + 1; });
const fixture = { channels: fch.length, how, sha256: crypto.createHash('sha256').update(lines(fch, fr)).digest('hex') };

const out = { vectors, fixture };
const file = here('guidematch-expected.json');
if (process.env.UPDATE) { fs.writeFileSync(file, JSON.stringify(out, null, 1) + '\n'); console.log('wrote', file.pathname); }
const exp = JSON.parse(fs.readFileSync(file, 'utf8'));
let bad = 0;
exp.vectors.forEach((e, i) => {
  const g = vectors[i];
  if (!g || g.name !== e.name || g.id !== e.id || g.how !== e.how || g.shift !== e.shift) { bad++; console.log('DIFF', JSON.stringify(e), '->', JSON.stringify(g)); }
});
if (JSON.stringify(exp.fixture) !== JSON.stringify(fixture)) { bad++; console.log('DIFF fixture', JSON.stringify(exp.fixture), '->', JSON.stringify(fixture)); }
console.log(`fixture: ${fixture.channels} channels, ${JSON.stringify(how)}`);
if (bad) { console.log(`guidematch FAILS ${bad}`); process.exit(1); }
console.log('guidematch ok');
