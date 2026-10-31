# Jarvis house guide (McD-Server)

Fills My Guide for channels no public source in the app carries: WAFF 48 (NBC), WHNT 19 (CBS) and WAAY 31 (ABC)
in Huntsville, plus ESPN2, ESPNU and NFL RedZone. WZDX 54 is left out on purpose: the provider's guide has it.

- Grabber: iptv-org/epg (pinned commit in setup.sh), site tvpassport.com, channels in `huntsville.channels.xml`.
  The display names match J's playlist names after GuideExtra drops lineup tags and brackets.
- Where: `~/Library/Application Support/JarvisGuide` on McD-Server (private Node.js, the grabber, `www/`, `logs/`).
- Schedule: launchd agent `com.mcd.jarvisguide.grab`, at load and every 4 hours (14400 s); today and tomorrow in
  Central Time. A grab that loses WAFF, WHNT or WAAY is refused and the last good guide stays.
- Served: launchd agent `com.mcd.jarvisguide.serve`, `http://johns-macbook-air-2.local:8787/huntsville.xml.gz` (also
  `/huntsville.xml`, `/status.json`), home network only. Never published anywhere else: the listings come from a
  third-party site, so they stay private to the house (same reason epgshare01 was declined).
- The TV finds McD-Server by its Bonjour name `johns-macbook-air-2.local` (multicast DNS, `HouseHost`), so the
  router may change its address freely. Renaming the Mac's Local hostname would need an app change.
- The TV reads it as the third extra guide source (`GuideExtra.HOUSE`), after Roku and Plex, under the same
  "Guide for free channels" switch. The web app does not read it (an https page can't read a plain-http LAN file).
- Install, Route 2 (Terminal, no restart): `bash install.sh` from this folder.
- Install, Route 1 (no Terminal): copy the five files and `huntsville.channels.xml` to the folder above, the two
  plists to `~/Library/LaunchAgents`, then restart McD-Server once. The grab agent runs `setup.sh` on first start.
- Check: `curl http://johns-macbook-air-2.local:8787/status.json` (grab time and programme counts per channel), logs in `logs/`.
- Remove: `launchctl bootout gui/$(id -u)/com.mcd.jarvisguide.grab` (and `.serve`), delete the two plists and the folder.
