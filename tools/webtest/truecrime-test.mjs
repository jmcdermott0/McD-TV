// True Crime category rules (TCR in docs/app/index.html) against real TMDB data (tools/truecrime/fixture.txt).
// The shelf must equal tools/truecrime/expected.json, which the TV's TrueCrimeTest reads too, so the phone and the
// TV show the same category in the same order. No browser needed.
// Run: node tools/webtest/truecrime-test.mjs
import fs from 'node:fs';
import assert from 'node:assert/strict';

const html = fs.readFileSync(new URL('../../docs/app/index.html', import.meta.url), 'utf8');
const block = /\/\/ >>> TrueCrime rules[^\n]*\n([\s\S]*?)\/\/ <<< TrueCrime rules/.exec(html);
if (!block) throw new Error('TrueCrime rules block not found in index.html');
const TCR = new Function(block[1] + '\nreturn TCR;')();

const pool = fs.readFileSync(new URL('../truecrime/fixture.txt', import.meta.url), 'utf8').split('\n')
  .filter((l) => l.trim() && !l.startsWith('#')).map((l) => {
    const p = l.split('|');
    return {
      key: p[0], name: p[1], year: +p[2], genres: p[3].split(',').filter(Boolean).map(Number),
      keywords: p[7].split(';').map(TCR.norm).filter(Boolean), votes: +p[6], rating: +p[5], american: p[4] === 'en',
    };
  });
const byKey = new Map(pool.map((t) => [t.key, t]));
const keyOf = (name) => pool.find((t) => t.name === name).key;
const expected = JSON.parse(fs.readFileSync(new URL('../truecrime/expected.json', import.meta.url), 'utf8')).shelf;
const shelf = TCR.shelf(pool);

const fails = [];
const check = (name, fn) => { try { fn(); } catch (e) { fails.push(name + ': ' + e.message.split('\n')[0]); } };

check('fixture is the real capture', () => assert.equal(pool.length, 206));
check('shelf equals expected.json (same as the TV)', () => assert.deepEqual(shelf, expected));
check('category identity', () => { assert.equal(TCR.ID, 'truecrime'); assert.equal(TCR.LABEL, 'True Crime'); });
check('marquee documentaries lead in order', () => {
  const docs = TCR.MARQUEE.filter((m) => m[3]).map((m) => m[1]);
  assert.deepEqual(shelf.slice(0, docs.length).map((k) => byKey.get(k).name), docs);
});
check('Home row (first 20) is documentaries and marquee dramas', () => {
  for (const k of shelf.slice(0, 20)) {
    const t = byKey.get(k);
    assert.ok(t.genres.includes(99) || TCR.marqueeFor(k.split(':')[0], t.name, t.year), t.name);
  }
});
check('crime fiction and off-topic documentaries stay off', () => {
  for (const n of ['Dexter', 'Criminal Minds', 'Breaking Bad', 'The Godfather', 'GoodFellas', 'The Irishman', 'Se7en',
    'The Silence of the Lambs', 'Bodkin', 'Based on a True Story', 'Manhattan', 'Mayday', 'Seaspiracy',
    'Auschwitz: The Nazis and the Final Solution', 'Long Shot']) assert.ok(!shelf.includes(keyOf(n)), n);
});
check('same title twice shows once', () => {
  assert.deepEqual(shelf.filter((k) => byKey.get(k).name === 'O.J.: Made in America'), ['movie:377462']);
  assert.equal(new Set(shelf).size, shelf.length);
});
check('reasons match the TV', () => {
  const r = (n) => TCR.reason(byKey.get(keyOf(n)));
  assert.equal(r('Making a Murderer'), 'marquee'); assert.equal(r('Tiger King'), 'doc'); assert.equal(r('The Vow'), 'doc');
  assert.equal(r('Woman of the Hour'), 'drama'); assert.equal(r('Yara'), 'drama'); assert.equal(r('Dexter'), null); assert.equal(r('Mayday'), null);
});
check('Home and Browse use one source', () => {
  assert.ok(html.includes('${rowShell(TC.ID, TC.LABEL, "#/browse/" + TC.ID)}'), 'Home row shell');
  assert.ok(html.includes('fillRow(TC.ID, TC.homeRow(), posterCard, tok)'), 'Home row fill');
  assert.ok(html.includes('homeRow: () => shelf(20)'), 'Home row is the first 20 of the shelf');
  assert.ok(html.includes('[TC.ID]: { title: TC.LABEL, types: ["all"], fetch: (ty, v, p) => (p === 1 ? TC.shelf() : Promise.resolve([])) }'), 'Browse grid kind');
  assert.ok(html.includes('tile("#/browse/" + TC.ID, TC.LABEL, TC.SUBTITLE)'), 'Browse tile');
});

if (fails.length) { console.error('FAIL\n  ' + fails.join('\n  ')); process.exit(1); }
console.log(`OK  True Crime: ${shelf.length} titles, same order as the TV (expected.json)`);
