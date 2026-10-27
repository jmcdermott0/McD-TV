# Jarvis configuration map

How every setting is entered, stored, synced and read across the TV app, the Control page and the web app. Checked against the code on October 19, 2026. Read it before changing any setting screen.

- **TV:** SharedPreferences file `mcdtv_settings` (`data/Prefs.kt`). Per-profile keys end in `@p1`, `@p2`, `@p3`.
- **Control page:** `docs/index.html`. It stores only `mcdtv_link` and `mcdtv_tvs` in the browser. Everything else goes to the TV through the relay `set` command.
- **Web app:** `docs/app/index.html`. Browser localStorage, keys `mcdtv_*`.

## How values travel

| Path | What it carries | Code |
|---|---|---|
| Relay `set` (Control page to one TV) | `addon_urls`, `m3u_url`, `custom_stream_url`, `origin_filter`, `us_only`, `slow_connection`, `play_intro`, `wall_url`, `wall_start`, `wall_due`, `wall_baby`, `wall_mode`, `tv_label`. Sent only when typed: `wall_key`, `tmdb_key`, `mdblist_key`, `jarvis_key`. A blank key is ignored, so `set` can never clear a key. | `docs/index.html` `save()`, `data/Relay.kt` `apply()` |
| HouseSync (TV to TV, house topic) | The `SHARED` list: `addon_urls`, `websites`, `m3u_url`, `custom_stream_url`, `tmdb_key`, `mdblist_key`, `jarvis_key`, `jarvis_model`, `wall_url`, `wall_key`, `wall_due`, `wall_baby`, `origin_filter`, `us_only`, `slow_connection`, `play_intro`, `max_movie_gb`, `max_episode_gb`, `rd_client_id`, `rd_client_secret`, `rd_access`, `rd_refresh`, `rd_expires`. "Copy once" sends the same list without `rd_*`. | `data/HouseSync.kt` |
| ProfileSync (TV to TV, house topic) | Per-profile history, My List, favorites, Live TV favorites, taste, profile names, profile photos (in pieces). Merged entry by entry. | `data/sync/ProfileSync.kt` |
| `web_init` (TV to phone, relay) | TMDB and MDBList keys, addons, playlist link, websites, origin, profiles and lists, profile photo versions (`photos`, never the photos), `rd_connected` (never the RD tokens), the house link, the Jarvis key (kept on the phone only while "Use Jarvis on this device" is on). | `data/Relay.kt` |
| Setup link and invite (phone to phone) | `SETUP_KEYS`: `mcdtv_link`, `mcdtv_web_init`, `mcdtv_web_profile`, `mcdtv_web_plists`, `mcdtv_web_jarvis`, `mcdtv_web_jarvis_local`, `mcdtv_web_rdc`, `mcdtv_web_rdt`, `mcdtv_web_addons_bak`, `mcdtv_web_addons_mine`, `mcdtv_web_extplayer`, `mcdtv_web_tmdb`, `mcdtv_web_mdb`, `mcdtv_web_origin`. Invites leave out `mcdtv_web_rdc`, and leave out `mcdtv_web_rdt` unless it is ticked. | `docs/app/index.html` `setupLink()` |

## Movies and shows

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| TMDB key | Built into the APK from the GitHub secret `TMDB_API_KEY`; Control page; TV home Wi-Fi page | `tmdb_key` (a saved key wins over the built-in one) | HouseSync, `web_init` | yes |
| TMDB key, this phone only | Web Settings > Keys | `mcdtv_web_tmdb` (the TV's key wins when the TV answers) | setup link, invite | yes |
| MDBList key | Built in from `MDBLIST_API_KEY`; Control page; web Settings > Keys | `mdblist_key`; web `mcdtv_web_mdb` | HouseSync, `web_init` | yes |
| Addons | Control page, TV home Wi-Fi page | `addon_urls` | HouseSync, `web_init`; the phone keeps a backup in `mcdtv_web_addons_bak` | addon links can hold tokens |
| Addons, this phone only | Web Settings > Addons | `mcdtv_web_addons_mine` | setup link, invite | maybe |
| Websites | TV home Wi-Fi page | `websites` | HouseSync | no |
| Titles, US release dates, slow connection, size limits, intro | TV Settings; Control page (not the size limits) | `origin_filter`, `us_only`, `slow_connection`, `max_movie_gb`, `max_episode_gb`, `play_intro` | HouseSync. The phone can override titles with `mcdtv_web_origin`. | no |
| Quality (Auto, Prefer 4K, Max 1080p) | TV Settings > Playback > Quality | `quality_mode` = `auto` (default: steps down from 4K to 1080p when playback struggles), `4k` (never steps down) or `1080` (never above 1080p; Live TV opens a 4K channel's 1080p version) | this TV only, never synced, not per profile | no |
| Quality on the phone (Auto, Prefer 4K, Max 1080p) | Web app Settings > Playback > Quality | `mcdtv_web_quality` = `auto` (default), `4k` or `1080` | this browser only; not in the setup link or invites | no |
| My Stream link | Control page | `custom_stream_url` | HouseSync | maybe |
| Real-Debrid, TVs | TV Settings > Playback > Connect (device code) | `rd_client_id`, `rd_client_secret`, `rd_access`, `rd_refresh`, `rd_expires`; `rd_last_add` is per TV | HouseSync: one sign-in for the whole house. Phones only see `rd_connected`. | yes |
| Real-Debrid, phone sign-in | Web Settings > Real-Debrid > Connect (device code, through `jarvis-rd.netlify.app`) | `mcdtv_web_rdc` = `{id, secret, t, r, exp}` | setup link only | yes |
| Real-Debrid API token (iPhone playback) | Web Settings > Real-Debrid > Add token | `mcdtv_web_rdt` | setup link; invite only when ticked | yes |
| Play through the TV | none | none: the phone sends `rd_resolve` and the TV makes the RD calls | relay | no |
| RD relay | Netlify site `jarvis-rd` (`rd-proxy/`) | stores nothing; adds CORS headers for `jfortress0.github.io` | none | no |

## Live TV

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Playlist (M3U or Xtream Codes) | Control page, TV home Wi-Fi page | `m3u_url`. Xtream user and password are read from the `get.php?username=&password=` link; there are no separate fields. | HouseSync, `web_init`. The phone loads it through `jarvis-rd.netlify.app/iptv`. | yes (the link holds the login) |
| Guide (XMLTV) | no field | taken from the playlist header (`url-tvg`, `x-tvg-url`) or Xtream `xmltv.php`; kept in memory on the TV; phone cache `mcdtv_web_epg` | none | through the link |
| Live TV favorites | star on the TV or phone | `live_favorites@pN`; phone `mcdtv_web_plists` | ProfileSync, `web_init` | no |
| Recents, last channel, channel stats | automatic | `live_recents@pN`, `live_last_url@pN`, `live_stats` (JSON list; `b` = measured stream bitrate in kbps, 0 in older entries) | this TV only | no |
| Quality memory (up-front 4K or 1080p pick) | automatic | `quality_memory` = JSON `{est, estAt, ch: {guideKey: {br, down, clr}}}`: last throughput (bps) and when, per 4K channel its measured bitrate, last step-down and when a step-down was forgiven | this TV only, never synced, not per profile | no |
| Quality changes log | automatic, shown in TV Settings > Addons and Live TV > Quality changes | `quality_log` = JSON list, newest first, last 40: `{at, t (title), f (from), to, c (reason code), e (estimate bps)}` | this TV only, never synced, not per profile | no |
| Quality memory and log on the phone | automatic; the log shows in Web app Settings > Playback > Recent quality changes | `mcdtv_web_qmem` = JSON `{est, estAt, ch: {guideKey: {br, down, at}}}` (last measured speed, per 4K channel its measured bitrate and last step-down); `mcdtv_web_qlog` = JSON list, newest first, last 40: `{t, w (live or vod), name, from, to, why, est, br, note}` | this browser only; not in the setup link or invites | no |
| My teams (favorite teams) | TV Settings > Addons and Live TV > My teams | `live_fav_teams@pN` (JSON list like `"NFL:Eagles"`). Not set = Dad (p1) has Eagles, Phillies, Flyers and 76ers; other profiles none. | this TV only; `web_init` sends the requested profile's list as `fav_teams` (the web app's My Teams row; without it the web uses the same defaults) | no |
| Leagues in Live Games | TV Settings > Addons and Live TV > Live Games | `live_leagues_hidden@pN` (JSON list of league names). Not set = WNBA, MLS and Premier League hidden. The playlist is never changed. | this TV only | no |

## Profiles and the house

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Profile names | TV Settings > Profiles | `profile_name_p1` to `p3`; ids `p1`, `p2`, `p3` never change | ProfileSync, `web_init` | no |
| Profile photos | web Settings > Profile > Profile photos (Choose photo, Change, Remove) | TV file `filesDir/profile_photos/pN.b64` (a 192 px JPEG as base64, never in SharedPreferences); web `mcdtv_web_photos` = `{pN: {v, d, at, ok}}` | relay `profile_photo` (phone to the linked TV, pieces of 1,600 characters), ProfileSync META `photo` and `photo:N`, `web_init` sends versions only; a phone asks for a missing one with `profile_photo_get`. Never in the repo, the house store or setup links | no |
| Who is watching | TV picker; web Settings > Profile | `active_profile` (this TV); web `mcdtv_web_profile` | none | no |
| Per-profile data | automatic | `lib_*@pN`, `taste@pN` | ProfileSync | no |
| Picked for <name>, today's row | automatic, once a day | `picks_daily@pN` = `{day: yyyymmdd, titles: [...]}` (the day's row, kept so a relaunch shows the same titles; tomorrow it is left out) | this TV only. Every TV draws the same row from the same date and profile | no |
| House | made automatically | `house_id`, `house_key`, `house_since`, `house_stamp` | the house link `id.key` goes to phones in `web_init` | yes |
| Join my other TVs | TV Settings > Network shows a code; phone Settings > House and devices > Add a TV | short-lived ntfy topic from the code | ntfy | short-lived |
| Connect a phone | TV Settings > Network shows a code; phone Settings > House and devices > Connect with a code | `/house` blob, 15 minutes | hands over the house link | short-lived |
| Invites | web Settings > House and devices | `mcdtv_web_invite`, `mcdtv_web_welcome` | invite link | yes |

## Jarvis Wall

| Setting | Entered on | Stored as | Synced by | Secret |
|---|---|---|---|---|
| Wall address | Control page | `wall_url` | HouseSync | no |
| Wall key (read only) | Control page; equals Netlify `WALL_KEY` | `wall_key` | HouseSync | yes |
| Baby due date, baby's first name | Control page | `wall_due`, `wall_baby` | HouseSync | the name is private |
| Open in Wall on start, Wall shows | TV Settings > Jarvis Wall; Control page | `wall_start`, `wall_mode` | this TV only | no |
| How often the full calendar shows | TV Settings > Jarvis Wall; Control page (Show the full calendar, The calendar stays for); `set`, `wall_start`, `wall_mode` (`cycle`) | `wall_cycle` = "every/stays" minutes, default "30/1" (photos 97% of the time; "0/0" = never) | this TV only | no |
| Calendars | Netlify env `WALL_CALENDARS`, or the wall phone page | Netlify env and Blobs `wall-config` | never leaves Netlify | yes |
| Upload code | Netlify env `UPLOAD_CODE`; the wall phone page keeps it in `wall_code` | Netlify | none | yes |

## Links and identity

| Setting | Stored as | Notes |
|---|---|---|
| TV link | `relay_id`, `relay_key`, `relay_since` | QR link `https://jfortress0.github.io/McD-TV/#id.key`; topics `mcdtv-<id>-in` and `-out`. "New link" disconnects every phone. |
| Linked TV on a phone | `mcdtv_link` | shared by the Control page and the web app |
| Known TVs | `mcdtv_tvs` | `{id, key, name, house, dev, label, platform, model, version, seen}` |
| This TV's name | `tv_label` | TV Settings > This TV or the Control page; never shared |
| Device id | `device_id` | targets `to` and lets a TV ignore its own messages |
| Ask Jarvis key | `jarvis_key`; phone `mcdtv_web_jarvis` while `mcdtv_web_jarvis_local` is not "0" | Control page; HouseSync |

## Never rename or clear

1. `mcdtv_settings` and every pref key. Each `SHARED` name is also the HouseSync wire name, so a rename breaks sync with older TVs.
2. `house_id`, `house_key`: clearing them makes a new house and cuts the TV off from the others and from phones.
3. `relay_id`, `relay_key`: clearing them breaks every Control page and phone link.
4. `rd_*`: clearing them signs Real-Debrid out on every TV in the house.
5. Profile ids `p1` to `p3` and the per-profile keys.
6. `device_id`.
7. Web keys `mcdtv_link`, `mcdtv_tvs`, `mcdtv_web_init` (holds the house link), `mcdtv_web_rdc`, `mcdtv_web_rdt`, `mcdtv_web_addons_mine` and every `SETUP_KEYS` name: old setup links and invites depend on them.
8. ntfy topic formats and the key derivation strings (`jarvis-resume|`, `jarvis-pair|`, `jarvis-join|` and their `-key` forms).
9. Netlify env vars `WALL_KEY`, `UPLOAD_CODE`, `WALL_CALENDARS` and the Blobs stores.

## What the Settings redesign (October 2026) changed in storage

Nothing was renamed or removed. Added, all per device and never synced: TV `update_check` (last update check) and `recent_errors`; web `mcdtv_web_verified` (when each key last worked). Later on October 19: TV `wall_cycle`, TV `quality_mode` and web `mcdtv_web_quality`. Then TV `quality_memory`, `quality_log` and the `b` field inside `live_stats`; web `mcdtv_web_qmem` and `mcdtv_web_qlog`.

## Incomplete sources (October 19, 2026)

Added: TV pref `bad_sources` and web key `mcdtv_badsrc`, a map from title key (`movie:603`, `tv:1399:1:2`) to flagged files. A copy for the whole house sits in the relay's resume store with the house key: profile tag `badsrc`, key tag `title|<title key>`, value `{f:[{k,s,at,n}],at}` (k = file key, s = where it stopped in ms, n = score: times a device in the house reported it; missing reads as 1, never drops in a merge). File key: `h:<torrent hash>`, else `u:` or `n:` and the first 16 hex characters of a SHA-256 hash, so a link with a login is never stored. Rules: `data/SourceHealth.kt` and `SH` in docs/app/index.html. No relay code changed.

## Live TV What's On (October 21, 2026)

Added: `fav_teams` in `web_init` (read only; nothing new stored on the TV). The web guide cache `mcdtv_web_epg` now keeps each programme's description (first 120 characters) so the rows can classify it; the key and format are otherwise unchanged. No storage key was renamed or removed. Favorites, recents, teams, hidden leagues, the playlist link and every key and token stay as they were.

## Home: My List and daily picks (October 27, 2026)

Added: `picks_daily@p1` to `@p3` on the TV (device only, not in HouseSync's `SHARED` list, not in ProfileSync). Home's My List row reads the existing `lib_watchlist@pN`; nothing about it is stored. No storage key was renamed or removed. Keys, Real-Debrid, playlists and addons are untouched.
