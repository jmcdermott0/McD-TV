# Working on Jarvis

Read HANDOFF.md first. It explains the parts and how they fit. This file holds the rules every session and every agent follows.

## Budget rule: Netlify deploys

The relay (rd-proxy/, Netlify site jarvis-rd) is on the free plan: 300 credits a month. A production deploy costs 15 credits. Runtime traffic costs almost nothing (about 2 credits a month). In September 2026, 13 deploys used 195 credits.

- Change rd-proxy/ only when nothing else will do. Prefer fixes in the web app (docs/, GitHub Pages, free) or the TV app.
- Batch relay changes: collect them and ship them in one commit. Aim for no more than 2 relay deploys a month.
- Never commit to rd-proxy/ to test something. Test the function logic locally first.
- Commits outside rd-proxy/ do not deploy the relay and cost nothing.

## Other costs

- GitHub Actions and GitHub Pages are free here. Build APK and QA run on app changes; Web QA runs on docs/ changes.
- ntfy.sh (the TV link) has per-IP rate limits. Do not add polling loops faster than every 10 seconds.
- ESPN's public scoreboard is free but be polite: counts use one request per league; the full week loads only when a league is opened.

## House rules

1. J adds addons, playlists and websites himself. Never hardcode third-party addons, IPTV providers or torrent indexers.
2. Video never passes through the relay. Only small metadata (channel lists, guide rows, links) does.
3. Never put passwords, API keys, tokens or IPTV credentials in code, commits, logs or replies. J pastes them himself.
4. User-facing text: short plain sentences, no em dashes. Never name the model provider in the app.
5. Do not label content politically. J controls "Topics to skip".

## State machines

Any flow with phases (loading, retrying, waiting, failed) follows STATE-MACHINES.md: one state value (enum, sealed type, or a web `{status}` object replaced whole), transitions in one pure place, one way into a terminal state that also cancels timers, async answers checked against the current request (generation, key or token), and a test. Add or update its row in STATE-MACHINES.md in the same change.

## How to check work

- Kotlin unit tests: `bash tools/run-unit-tests.sh`
- Kotlin syntax (no Android SDK here): tree-sitter-kotlin parse of the changed files. CI builds the APK.
- Web app: extract the `<script>` blocks of docs/app/index.html and run `node --check`, then `python3 tools/webtest/srv.py &` and `node tools/webtest/qa.mjs` (needs `npm install` in tools/webtest).
- Bump `const BUILD` in docs/app/index.html on every web change, so phones pick up the new version.

## Focus agents

.claude/agents/ holds four specialists: live-tv, web-app, tv-app and qa. Give each one a single area. They report findings and changes; the main session reviews, tests and commits.
