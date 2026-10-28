// Live TV default view (LH in docs/app/index.html) against the stand-in playlist and guide snapshot
// (tools/livehome/fixture, made by make_fixture.py). No browser needed.
// The outputs are compared with tools/livehome/expected.json, which the TV's LiveHomeTest reads too, so both apps
// must build the same rows from the same playlist and guide. UPDATE=1 rewrites the file (review the diff).
// Run: node tools/webtest/livehome-test.mjs
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { loadEngine, readText, parseM3u, parseXmltv, prepare } from '../livehome/load.mjs';

const fx = (f) => new URL('../livehome/fixture/' + f, import.meta.url);
const EXPECTED = new URL('../livehome/expected.json', import.meta.url);
const E = loadEngine();
const { LH } = E;
const fails = [];
const check = (name, ok, info) => { if (!ok) fails.push(name + (info !== undefined ? ': ' + JSON.stringify(info) : '')); };

// ---------------------------------------------------------------- unit cases (both apps run these)
const IDENT = [
  ['US| AMC FHD', 'US| ENTERTAINMENT'], ['US| AMC West', 'US| ENTERTAINMENT'], ['US| AMC (backup)', 'US| ENTERTAINMENT'], ['AT&T: AMC RAW', 'US| AT&T RAW 60fps'],
  ['SLING: AMC', 'US| SLING'], ['DTV: AMC HD', 'US| DIRECTV'], ['AMC (Hulu)', 'US| HULU LIVE'], ['AMC [Philo]', 'US| PHILO'], ['US| AMC+', 'US| ENTERTAINMENT'],
  ['US| FX HD', 'US| ENTERTAINMENT'], ['US| FXX', 'US| ENTERTAINMENT'], ['US| A&E HD', 'US| ENTERTAINMENT'], ['US| USA Network FHD', 'US| ENTERTAINMENT'],
  ['US| Travel Channel', 'US| ENTERTAINMENT'], ['US| Discovery Science', 'US| ENTERTAINMENT'], ['US| National Geographic HD', 'US| ENTERTAINMENT'], ['US| Nat Geo Wild', 'US| ENTERTAINMENT'],
  ['US| History HD', 'US| ENTERTAINMENT'], ['US| American Heroes Channel', 'US| ENTERTAINMENT'], ['US| Military History Channel SD', 'US| ENTERTAINMENT'],
  ['US| ESPN FHD', 'US| SPORTS'], ['US| ESPN 2 FHD', 'US| SPORTS'], ['US| ESPN2 HD', 'US| SPORTS'], ['US| ESPNU', 'US| SPORTS'], ['US| ESPN 4K', 'US| SPORTS 4K'],
  ['US| NFL Network HD', 'US| SPORTS'], ['US| NFL RedZone HD', 'US| SPORTS'], ['US| NBA TV', 'US| SPORTS'], ['US| MLB Network', 'US| SPORTS'], ['US| NHL Network', 'US| SPORTS'],
  ['US| FOX SPORTS 1', 'US| SPORTS'], ['US| FS1 HD', 'US| SPORTS'], ['US| SEC Network', 'US| SPORTS'], ['US| CBS SPORTS NETWORK', 'US| SPORTS'],
  ['US| NBC Sports Philadelphia HD', 'US| SPORTS'], ['US| NBC Sports Philadelphia Plus', 'US| SPORTS'], ['US| NBC Sports Boston', 'US| SPORTS'], ['US| FanDuel Sports South', 'US| SPORTS'],
  ['US| FOX 54 WZDX Huntsville', 'US| LOCALS'], ['US| CBS 19 WHNT Huntsville HD', 'US| LOCALS'], ['US| WHNT News 19', 'US| LOCALS'], ['US| FOX 29 WTXF Philadelphia', 'US| LOCALS'],
  ['US| 6ABC WPVI Philadelphia', 'US| LOCALS'], ['US| CBS 3 KYW Philadelphia', 'US| LOCALS'], ['US| NBC 10 WCAU Philadelphia', 'US| LOCALS'], ['US| CBS 2 New York', 'US| LOCALS'],
  ['US| ABC 7 WABC New York', 'US| LOCALS'], ['US| FOX East HD', 'US| LOCALS'], ['US| PBS HD', 'US| LOCALS'], ['US| PBS 12 Denver', 'US| LOCALS'], ['US| PBS Kids', 'US| KIDS'],
  ['US| FOX NEWS HD', 'US| NEWS'], ['US| FOX BUSINESS', 'US| NEWS'], ['US| CNBC', 'US| NEWS'], ['US| MSNBC', 'US| NEWS'], ['US| CBS News 24/7', 'US| NEWS'], ['US| ABC News Live', 'US| NEWS'],
  ['US| Bloomberg TV HD', 'US| NEWS'], ['US| HBO 2 FHD', 'US| PREMIUM'], ['US| HBO East', 'US| PREMIUM'], ['US| Showtime West', 'US| PREMIUM'],
  ['NFL 01: Philadelphia Eagles vs New York Giants 1:00PM ET', 'US| NFL SUNDAY TICKET'], ['NFL 07 | No Event Streaming', 'US| NFL SUNDAY TICKET'], ['ESPN+ 001', 'US| ESPN+'],
  ['NCAAF 05: Alabama vs Tennessee 3:30PM ET', 'USA | NCAAF'], ['NBA 03', 'US| NBA LEAGUE PASS'], ['PPV 12', 'PPV EVENTS'], ['Peacock 04', 'US| PEACOCK EVENTS'],
  ['Pluto TV: Westerns', 'US| PLUTO TV'], ['Samsung TV Plus: Bob Ross', 'US| SAMSUNG TV PLUS'], ['Tubi: Top Gear', 'US| TUBI'], ['24/7 Friends', '24/7 CHANNELS'],
  ['Music Choice 07', 'US| MUSIC'], ['UK| Sky Sports Main Event FHD', 'UK| SPORTS'], ['UK| BBC One HD', 'UK| GENERAL'], ['CA| TSN 1 HD', 'CA| SPORTS'], ['LATINO| Univision', 'LATINO| GENERAL'],
  ['|UK| SKY SPORTS F1 ᴴᴰ', '|UK| SPORTS'], ['US| Cartoon Network HD', 'US| KIDS'], ['US| Hallmark Channel', 'US| ENTERTAINMENT'], ['US| Hallmark Movies', 'US| PREMIUM'],
  // Real-format names from a large provider playlist (Oct 2026 accuracy pass).
  ['US: COMEDY CENTRAL HD', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: COMEDY TV HD', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: E! ENTERTAINMENT WEST HD', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: Magnolia Network West (Y) HD', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: FASHION ONE ³⁸⁴⁰ᴾ 3840P', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: THE WEATHER CHANNEL HD', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: : NATIONAL GEOGRAPHIC WILD HD', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: GAME SHOW CENTRAL', 'US| ENTERTAINMENT ᴿᴬᵂ'], ["US: REAL AMERICA'S VOICE HD", 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: C-SPAN 2 HD', 'US| NEWS ᴿᴬᵂ'], ['US: NBC NEWS NOW (D) ᴿᴬᵂ', 'US| NBC ᴿᴬᵂ'], ['US: NBC MS NOW ᴿᴬᵂ', 'US| NBC ᴿᴬᵂ'], ['US: CBS SPORTS USA', 'US| CBS ᴿᴬᵂ'],
  ['US: STADIUM 2 HD', 'US| SPORT ᴿᴬᵂ'], ['US: ESPN U HD', 'US| SPORT ᴿᴬᵂ'], ['US: CBS SPORTS GALAZO NETWORK 4K', 'US| SPORT ᴿᴬᵂ'],
  ['US: NBC SPORTS PHILADELPHIA (D) ᴿᴬᵂ', 'US| NBC ᴿᴬᵂ'], ['US: CSN PHILADELPHIA HD', 'US| NEWS ᴿᴬᵂ'], ['US: NBC SPORTS PHILADELFIA', 'US| SPORT ᴿᴬᵂ'],
  ['US: (GT) MSG (Zone1) ᴿᴬᵂ', 'US| SPORT ᴿᴬᵂ'], ['US: (GT) Yes Network Latin ᴿᴬᵂ', 'US| SPORT ᴿᴬᵂ'], ['US: FOX SPORTS DETROIT HD', 'US| FOX ᴿᴬᵂ'], ['US: PAC 12 OREGON HD', 'US| SPORT ᴿᴬᵂ'],
  ['US: HBO ZONE HD', 'US| MOVIES ᴿᴬᵂ'], ['US: MOREMAX HD', 'US| MOVIES ᴿᴬᵂ'], ['US: EPIX 2', 'US| MOVIES ᴿᴬᵂ'], ['US: STARZ ENCORE WESTERNS HD', 'US| MOVIES ᴿᴬᵂ'],
  ['US: MUSIC CHOICE POP HITS', 'US| ENTERTAINMENT ᴿᴬᵂ'], ['US: DAZN COMBAT', 'US| SPORT ᴿᴬᵂ'], ['US: PETER GRIFFIN 24H', 'US| MOVIES ᴿᴬᵂ'],
  ['US: TELEMUNDO 51 (WSCV) MIAMI ᴿᴬᵂ', 'US| TELEMUNDO NETWORK ᴿᴬᵂ'], ['US: Univision23 (WLTV) Miami (A) HS HD', 'US| MIAMI ᴿᴬᵂ'], ['US: MY 9 WWOR NEW YORK', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: NBC BOSTON NEWS (PC) ᴿᴬᵂ', 'US| NBC ᴿᴬᵂ'], ['US: FOX 5 PLUS HD [WASHINGTON]', 'US| FOX ᴿᴬᵂ'], ['US: KIRO 7 NEWS', 'US| NEWS ᴿᴬᵂ'], ['US: WGN9', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: ABC TAMPA NEWS (WFTS)', 'US| ABC ᴿᴬᵂ'], ['US: NEWS 12 BROOKLYN', 'US| NEWS ᴿᴬᵂ'], ['US: IT| 20THCENTURYFOX 4K', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['BG: DIEMA SPORT 1 ᴴᴰ', '8K| SPORT ON AIR'], ['HR: MAX SPORT 1 ᴴᴰ', '8K| SPORT ON AIR'], ['FL: V SPORT GOLF ᴴᴰ', '8K| SPORT ON AIR'], ['PPV: UFC MAIN EVENT', '8K| SPORT ON AIR'],
  ['HR: ATG Live 4K', 'HR| HORSE RACING HD/4K'], ['HR: FOX SPORTS RACING', 'HR| HORSE RACING HD/4K'], ['4K: BEIN 4K ³⁸⁴⁰ᴾ', '4K| ³⁸⁴⁰ᴾ'], ['4K: TF1 4K ³⁸⁴⁰ᴾ', '4K| ³⁸⁴⁰ᴾ'],
  ['4K: QVC 4K ³⁸⁴⁰ᴾ', '4K| ³⁸⁴⁰ᴾ'], ['US: HULU ORIGINALS INFO ᴿᴬᵂ', 'US| \uFE0FHULU NETWORK ᴿᴬᵂ'], ['US: NEW JERSEY 12 NEWS', 'US| NEWS ᴿᴬᵂ'], ['US: TVG NETWORK 2', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['US: FORMULA 1 CHANNEL (FM1TV) ᴿᴬᵂ', 'US| SPORT ᴿᴬᵂ'], ['US: BLAZE TV', 'US| ENTERTAINMENT ᴿᴬᵂ'],
  ['TUBI: NFL CHANNEL ᴿᴬᵂ', 'US| TUBI ᴿᴬᵂ'], ['US: C HOLLYWOOD PREMIERE 10 4K', 'US| CINEMANIA HOLLYWOOD ᴿᴬᵂ'],
];
const PROGS = [
  { title: 'NFL Football: Philadelphia Eagles at New York Giants', cats: ['Sports event', 'Football', 'NFL'] },
  { title: 'NFL Live', cats: ['Sports talk'] },
  { title: 'College Football: Alabama at Tennessee', cats: ['Football'], rerun: true },
  { title: 'College Football: (5) Georgia at Florida', cats: ['Football'] },
  { title: 'WNBA Finals: Liberty vs. Aces', cats: ['Basketball'] },
  { title: 'MLS Cup Playoffs: Inter Miami CF vs. Atlanta United', cats: ['Soccer'] },
  { title: 'Premier League Soccer: Arsenal vs. Liverpool' },
  { title: 'NBA Basketball: Boston Celtics at Philadelphia 76ers', cats: ['Basketball'] },
  { title: 'NHL Hockey: New York Rangers at Philadelphia Flyers' },
  { title: 'MLB Baseball: World Series Game 3', cats: ['Sports event', 'Baseball'], live: true },
  { title: 'MLB Baseball', sub: 'Phillies at Mets', cats: ['Baseball'] },
  { title: 'Sunday Night Football: Chiefs at Bills' },
  { title: '76ers Pregame Live' },
  { title: 'NFL Football: Cowboys at Commanders', desc: 'From last season (replay).' },
  { title: 'Boston Celtics at Philadelphia 76ers', cats: ['Basketball'] },
  { title: 'NCAA Basketball: Duke at North Carolina' },
  { title: 'WWII in Color: Road to Victory', desc: 'Restored footage of the Allied push across Europe.', cats: ['Documentary', 'History', 'War'] },
  { title: 'Air Warriors: F-15E Strike Eagle', desc: "The U.S. Air Force's long-range strike fighter.", cats: ['Documentary', 'Military', 'Aviation'] },
  { title: 'Pawn Stars', cats: ['Reality'] },
  { title: 'Star Trek', cats: ['Science fiction'] },
  { title: 'Storage Wars', cats: ['Reality'] },
  { title: 'Star Wars: A New Hope', cats: ['Movie'] },
  { title: 'The Rise of AI', desc: 'How machine learning moved from labs to daily life.', cats: ['Documentary', 'Science', 'Technology'] },
  { title: 'Hackers: Inside the Cyber Wars', desc: 'Ethical hackers test the power grid.', cats: ['Documentary', 'Technology'] },
  { title: 'Bloomberg Technology', desc: 'Artificial intelligence, chips and the companies behind them.', cats: ['Business', 'Technology'] },
  { title: 'The Big Bang Theory', desc: 'Sheldon builds a robot.', cats: ['Sitcom'] },
  { title: 'Ken Burns: The Vietnam War' },
  { title: 'Top Gun: Maverick', desc: 'A Navy fighter pilot returns.', cats: ['Movie', 'Action'] },
  { title: 'Air Disasters', desc: 'Investigators piece together a crash.', cats: ['Documentary', 'Aviation'] },
  { title: 'Gold Rush', cats: ['Reality'] },
  { title: 'Battlebots', cats: ['Competition'] },
];

const out = { catalog: LH.CATALOG.trim().split('\n'), identify: [], programmes: [], snapshots: {} };
// Organizer rules the TV mirrors in LiveOrganizerTest (separators, name-only country codes).
assert.ok(E.LO.isSeparator('## ENTERTAINMENT ᴿᴬᵂ/⁶⁰ᶠᵖˢ ##') && E.LO.isSeparator('## 24/7 COMEDY ᴿᴬᵂ ##') && !E.LO.isSeparator('#1 Hits'));
assert.equal(E.LO.country('8K| SPORT ON AIR', 'BG: DIEMA SPORT 1'), 'Bulgaria');
assert.equal(E.LO.country('8K| SPORT ON AIR', 'HR: MAX SPORT 1'), 'Balkans');
assert.equal(E.LO.country('8K| SPORT ON AIR', 'LV: GO3 SPORT 1'), 'Baltics');
assert.equal(E.LO.country('HR| HORSE RACING HD/4K', 'HR: ATG Live 4K'), '');
// Every catalog key is already a network key, so a lookup can find it ("A AND E" is an older alias netKey never produces).
for (const line of out.catalog) for (const key of line.split('|')[4].split(',')) if (key !== 'A AND E') assert.equal(LH.netKey(key), key, line);
for (const [n, g] of IDENT) {
  const id = LH.identify(n, g, E.LO.country(g, n), E.LO.sectionTV(g, n));
  out.identify.push([n, g, id.key + (id.region && id.region !== 'national' ? ' (' + id.region + ')' : '') + (id.fold ? ' -> ' + id.fold.key : '')]);
}
for (const p of PROGS) {
  const g = LH.gameOf(p), t = LH.topics(p);
  out.programmes.push({ p, game: g ? `${g.league}: ${g.away}${g.home ? ' | ' + g.home : ''}` : null, topics: ['docs', 'military', 'airforce', 'tech'].filter((k) => t[k]).join(',') });
}

// ---------------------------------------------------------------- the fixture
const raw = parseM3u(readText(fx('playlist.m3u.gz')));
const guide = parseXmltv(readText(fx('guide.xml.gz')));
const games = JSON.parse(fs.readFileSync(fx('games.json'), 'utf8'));
const D = prepare(E, raw, guide);
const idx = LH.groups(D.chs, (i) => D.country[i], (i) => D.section[i]);
const DAD = [{ league: 'NFL', name: 'Eagles' }, { league: 'MLB', name: 'Phillies' }, { league: 'NHL', name: 'Flyers' }, { league: 'NBA', name: '76ers' }];
const RUNS = {
  'A guide only': { now: games.A.now, games: [], teams: DAD },
  'A with ESPN': { now: games.A.now, games: games.A.games, teams: DAD },
  'B with ESPN': { now: games.B.now, games: games.B.games, teams: DAD },
  'A fresh profile (no teams)': { now: games.A.now, games: games.A.games, teams: [] },
};
const runs = {};
for (const [name, r] of Object.entries(RUNS)) {
  const H = LH.home({ idx, chs: D.chs, progs: D.progs, games: r.games, teams: r.teams, now: r.now, eventOf: (i) => E.P.eventProg(D.chs[i].name, D.chs[i].group, r.now) });
  runs[name] = H;
  out.snapshots[name] = H.rows.map((row) => ({ id: row.id, tiles: row.tiles.map((t) => [t.title, t.sub, t.badge, t.feeds.length ? D.chs[t.feeds[0]].name : '', t.feeds.length].join(' | ')) }));
}
out.groups = { count: idx.list.length, channels: D.chs.length, labels: idx.list.map((g) => `${g.label} [${g.kind}, ${g.feeds.length}]`) };

// ---------------------------------------------------------------- what must hold, whatever the expected file says
const A = runs['A guide only'], tile = (H, row) => H.rows.find((r) => r.id === row).tiles;
const eag = tile(A, 'myteams')[0];
check('A: the live Eagles game is pinned first in My Teams (guide only)', eag && eag.live && eag.mine && /Eagles/.test(eag.title) && /^LIVE · ★ MY TEAM/.test(eag.badge), eag);
check('A: the Eagles tile plays the Philadelphia FOX station first', eag && /WTXF Philadelphia/.test(D.chs[eag.feeds[0]].name), eag && D.chs[eag.feeds[0]].name);
const lgA = tile(runs['A with ESPN'], 'games');
check('A: Live Games has only NFL, college football, MLB, NHL and NBA', lgA.every((t) => ['NFL', 'NCAAF', 'MLB', 'NHL', 'NBA'].includes(t.league)), lgA.map((t) => t.league));
check('A: no WNBA, MLS, soccer or replays in Live Games', !lgA.some((t) => /WNBA|MLS|Premier|Inter Miami|Arsenal|Alabama/.test(t.title)), lgA.map((t) => t.title));
check('A: Live Games lists the three live NFL games and the live NHL game', lgA.filter((t) => t.live).length === 4, lgA.map((t) => t.title + ' ' + t.badge));
const fl = tile(runs['A with ESPN'], 'myteams').find((t) => t.team === 'Flyers');
check('A: the Flyers tile shows the next start in Central Time', fl && fl.badge === 'Today 4:00 PM CT', fl);
const B = runs['B with ESPN'];
const myB = tile(B, 'myteams');
check('B: Flyers and 76ers both live, pinned before the Eagles', myB[0].live && myB[1].live && !myB[2].live && /Eagles/.test(myB[2].title), myB.map((t) => t.title + ' ' + t.badge));
check('B: next Eagles game in Central Time', myB[2].badge === 'Sun 12:00 PM CT', myB[2].badge);
const fox = tile(A, 'prime').find((t) => t.title === 'FOX');
check('Primetime FOX plays the Huntsville station first', fox && /WZDX Huntsville/.test(D.chs[fox.feeds[0]].name), fox && D.chs[fox.feeds[0]].name);
check('History & Docs: no science fiction', !tile(A, 'docs').some((t) => t.title === 'Syfy'));
check('Military: the Air Force programme first', /Air Force|F-15E/.test(tile(A, 'military')[0].sub), tile(A, 'military')[0]);
const fresh = runs['A fresh profile (no teams)'];
check('Fresh profile: the same six rows in the same order', fresh.rows.map((r) => r.id).join() === A.rows.map((r) => r.id).join(), fresh.rows.map((r) => r.id));
check('Fresh profile: no team tiles', tile(fresh, 'myteams').length === 0);
// Nothing unreachable: every channel sits in exactly one A-Z entry, and search (name contains) finds every one.
const placed = new Map();
idx.list.forEach((g, gi) => g.feeds.forEach((i) => placed.set(i, (placed.get(i) || 0) + 1)));
check('Every channel is in exactly one A-Z entry', placed.size === D.chs.length && [...placed.values()].every((v) => v === 1), { placed: placed.size, channels: D.chs.length });
const miss = D.chs.filter((c) => !D.chs.some((d) => d.name.toLowerCase().includes(c.name.toLowerCase().slice(0, 40))));
check('Search finds every channel by its own name', !miss.length, miss.slice(0, 3));
const presented = (H) => H.rows.reduce((n, r) => n + r.tiles.length, 0);
console.log(`channels ${D.chs.length}, A-Z entries ${idx.list.length}; tiles presented: A ${presented(A)}, B ${presented(B)}`);
for (const r of A.rows) console.log(`  A ${r.title}: ${r.tiles.map((t) => t.title).join(' · ')}`);

// ---------------------------------------------------------------- the shared expected file
if (process.env.UPDATE) { fs.writeFileSync(EXPECTED, JSON.stringify(out, null, 1) + '\n'); console.log('wrote expected.json'); }
else {
  const want = JSON.parse(fs.readFileSync(EXPECTED, 'utf8'));
  const diff = (a, b, path) => {
    if (JSON.stringify(a) === JSON.stringify(b)) return;
    if (a && b && typeof a === 'object' && typeof b === 'object') { for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) diff(a[k], b[k], path + '.' + k); return; }
    fails.push(`expected.json${path}: got ${JSON.stringify(a)}, want ${JSON.stringify(b)}`);
  };
  diff(out, want, '');
}
console.log(fails.length ? 'FAILS ' + fails.length + '\n' + fails.slice(0, 40).join('\n') : 'livehome ok');
process.exit(fails.length ? 1 : 0);
