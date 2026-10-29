# Backlog

Ranked upgrades from the October 2026 review by the four focus agents (.claude/agents). Effort: S small, M medium, L large. "Relay" marks items that need a Netlify deploy (15 credits each; batch them, see CLAUDE.md). Everything else is free to ship.

## Done October 18 (device identity, JARVIS integration spec)

- Every TV has a name (pref `tv_label`, TV Settings > This TV or Control page > This TV, prefill Living Room TV / Office TV / The Wall) and a platform from the real maker and model (`TvIdentity.kt`; known models only: AFTHY7ABBA = Fire TV, the Office TV). `tv_link` carries label, platform, maker, model and version. The Control page lists every TV in the house (Find my TVs on the house topic) with name, platform, version, on or off and last seen, and can open that TV's page or start and stop its Wall. The web app names the linked TV in the header pill, lists every TV under Settings > Your TV with what it plays, and (with more than one TV) the pill opens a picker; Play on TV and Open on TV carry `to: <device id>`. Now playing per TV (`NowPlaying.kt`, "tv_status" on the house topic on play, pause and stop, at most once per 10 s). Wall start, stop and mode (photos only, or photos and calendar) from the Control page. Open: the Wall stick's real model is not known yet; when it posts `tv_link`, read its `model` from the Control page (shown next to the platform) and add it to `TvIdentity.KNOWN_MODELS` as "Fire TV Stick". Old `mcdtv_tvs` entries with no device id stay until Forget.

## Done October 16

- Real-Debrid name filter: RD refuses names with WEB-DL, WEB.x264, WEB.H264, HDTV.x264 or HDTV.XviD (exact case, rule of October 14, 2026) with error 35. Those sources sort last and show "May be blocked" (`RdFilter`, TV and web). A source RD refused is skipped for 6 hours. Play best source tries 8 and does not stop early: RD refuses more names than the published tags. The rule changes often: update `RdFilter.TAGS` and `RD_TAGS` together.

- Real-Debrid probe (real data, see the project doc rd-probe-2026-10-16.md): since October 14, RD refuses most torrents at addMagnet (HTTP 451, error 35), clean names and tagged names alike, with the device sign-in and with a personal API token, through the relay. Some hashes are accepted (Tears of Steel). Torrents already in the account still play. The error 35 text no longer says "by its name" (TV and web).
- Real-Debrid add throttle (real data, October 17): an addMagnet a few seconds after another add gets HTTP 451 error 35, the same answer as a blocked file, even for files RD accepts with a 26 s gap. After one fast add, allowed files stay refused for more than 30 s; 2 minutes of quiet cleared it. So many October 16 refusals were the throttle, not blocks; Paw Patrol and The Odyssey hashes are still refused with full gaps. Fix (TV `RdLimiter.kt`, web `rdSlot`/add gate): one add per 25 s per device (kept across restarts), all RD calls at most 1 per 250 ms and 120 per minute, 429 / error 34 pauses all calls (Retry-After, else 5, 15, 30 s) and Play best source stops, the torrent list is cached 60 s, sources already in the account are tried first, and only refusals after a full gap are remembered. Background Noise tries account sources plus at most 2 adds per round. Open: devices do not share the add gap (two devices adding within 25 s can still trip the throttle); the relay does not forward Retry-After (next relay deploy: forward it and expose it with Access-Control-Expose-Headers).
- IPTV provider sources (October 18, real data): J's provider's app API has 28,795 movies and 8,602 shows, 98% with TMDB ids (string or number). Every Sources screen (TV `XtreamVod.kt` / `IptvVod.kt`, web `IV` module) now lists matching copies as "Your IPTV provider", first, and Play best source plays them before any Real-Debrid call. Index refreshes every 24 h; episode lists 6 h. Verified on J's Mac: PAW Patrol S1E1 from the provider played in IINA. The Odyssey (2026) is not in the provider. The account plays one stream at a time (max_connections 1). Live TV on the web: player apps get the provider's .ts link when it allows .ts; IINA stopped at 1:20 and replayed on .m3u8, and played on with .ts (verified on J's Mac). Not yet verified: IPTV playback and list download time on a Fire stick.
- TV link: TVs announce their current link on the house topic ("tv_link", answer to a phone's "tv_who", at most once per 20 s; at start only when the link changed or 11 h passed). The web app finds TVs through the house (Settings > Find my TVs, and once per app open when the TV does not answer) and relinks to the same TV (device id, or a unique name). The web app also takes the house link the Control page saved in `mcdtv_tvs`, so a phone syncs with the house with no TV answering. Data behind it: the saved Fire TV link got no answer for 12 h while two TVs were active on the house topic.

## Done in the October 12 overnight run

- Live TV: Huntsville call signs go to Local even with "News" in the name (TV and web); a failed guide fetch retries after 90 s (the TV kept an empty guide for 6 hours); the web guide cache survives app launches (15 minutes, per playlist, no login stored); My Guide and For You use the event and game fallbacks; "Tap to play" in the web Live TV player.
- Web app: updates wait during playback, source lookups, sign-in and the hand-off question ("Update ready. Tap to update."); service worker cache tied to BUILD with a 3 s shell timeout; Settings > Recent errors; watchlist edits queue while the TV is off; the dead Websites route is gone; shared b64u and sha helpers.
- TV app: "Still watching?" after 3 automatic episodes; the Home hero holds still while focused; LEFT/RIGHT presses add up into one seek; HouseSync compares settings when they change (60 s fallback, 10 s after a failure) instead of every 4 s; the screen may sleep after 10 minutes paused; the update screen says leaving stops the download; one app-wide image loader (RGB565 JPEGs, bounded caches).
- Real-Debrid: plain messages for refusals; a refused token refresh asks to reconnect and no longer clears the sign-in on every linked TV.
- Web QA: TMDB and ntfy mocked as 200; checks for the update prompt, error log, watchlist queue, guide cache and Live TV tap to play.

## Done in the October 10 agent run (commits 269f649 to e1634b4)

- Live TV: West feeds keep their own guide (`gkey` / `LiveFill.guideKey`); event channels show their game as the current program; ESPN, FOX, CBS, SECN, ACCN and BTN rows with no guide show the live or next game from loaded schedules; Huntsville stations first in Local and Games. TV and web.
- Web app: a TV that is off fails in about 6 s (`tvAlive`); "Tap to play" when the iPhone blocks autoplay; play position saved on background (hidden, pagehide, freeze); Settings > Check network.
- TV app: R8 and resource shrinking on release; baseline profile with profileinstaller; "Try next source" when playback fails; Back returns to the row and tile on Home.
- Follow-ups: call-sign-only stations ("WHNT News 19") land in News, not Local (change `LO.section` and `sectionFor` together); give `LiveGuide.kt` and `LiveForYou` the `LiveFill.nowNext` fallbacks; add "Tap to play" to the web Live TV player; check Live TV load time on a Fire stick; mock TMDB and ntfy as 200 in Web QA so the "OK" path of Check network is tested.

## Done in the October 11 cleanup

- Removed the unused account server (`server/`) and the app's Account page and its 2-minute background loop.
- Web app: "TV online" now turns to "TV offline" when the TV stops answering; the live link to the TV reconnects after the app comes back from the background; Live TV makes 8 ESPN requests on open instead of 36; the search page no longer scrolls sideways on phones.
- TV app: "Join my other TVs" waits 10 s between checks instead of 3 s (it could trip ntfy.sh's rate limit for the whole house).
- Web QA in CI (`.github/workflows/web-qa.yml`, tests in `tools/webtest/`).
- CLAUDE.md with the Netlify budget rule, and four focus agents.

## Live TV (agent: live-tv)

1. Games matching parity with the TV: abbreviation pairs, one team plus "vs", guide titles near kickoff. M
2. TV guide: use the playlist's accepted user agent, keep a trimmed guide on disk for cold starts. M
3. "On now" strip on Favorites and Sports, plus a Comedy shortcut. S/M
4. Check My Guide and For You speed on a Fire stick now that they use the LiveFill fallbacks. S
- Relay (next batched deploy): block IPv6 private addresses and redirects to internal hosts in iptv.mts.
- Relay (next batched deploy): in iptv.mts `window=1`, keep listings that ended up to 3 hours ago (stop > now - 3 h), so West feeds on the web show the show on now (they read the East table 3 hours later; see HANDOFF, guide matching).

## Web and phone app (agent: web-app)

1. Apply a waiting update by itself when a busy state ends without a page change (closing the Live TV player). S
2. Recent errors: skip known harmless rejections (a TMDB 404 nobody waits for) so the list stays useful. S

## TV app (agent: tv-app)

1. Move watch history out of SharedPreferences into files (it is rewritten every 15 s on the main thread). M
2. Buffer settings for 1 GB sticks (Media3 LoadControl). S
3. Jarvis Wall photos now decode as RGB565 (app-wide image loader): if gradients band, add `allowRgb565(false)` to the Wall's image request. S
4. Start Jarvis by itself when the stick boots, for the Wall TV (may need an extra Fire OS permission). M
- Risks: every launch sends a house sync request that every other TV answers; "TV started" goes to ntfy on every launch.

## QA

- Web QA now runs on docs/ changes. Next: run the Kotlin unit tests as a fast job before the 40-minute emulator job; mock TMDB and ESPN in the emulator QA so a vendor outage can't fail it.
