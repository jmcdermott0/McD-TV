#!/bin/bash
# Jarvis house guide: grab today and tomorrow (Central Time) for the channels in huntsville.channels.xml from
# tvpassport.com with iptv-org's grabber, check it, then swap it into www/ for the TVs. Run by launchd every
# 4 hours (com.mcd.jarvisguide.grab). A failed grab keeps the last good guide.
set -uo pipefail
DIR="$HOME/Library/Application Support/JarvisGuide"
LOG="$DIR/logs/grab.log"
mkdir -p "$DIR/logs" "$DIR/www"
exec >>"$LOG" 2>&1
[ "$(wc -c <"$LOG")" -gt 2000000 ] && tail -n 2000 "$LOG" >"$LOG.1" && mv "$LOG.1" "$LOG"
echo "=== $(date '+%Y-%m-%d %H:%M:%S %Z') grab"
[ -x "$DIR/node/bin/node" ] && [ -f "$DIR/epg/.commit" ] || bash "$DIR/setup.sh" || { echo "setup failed"; exit 1; }
export PATH="$DIR/node/bin:/usr/bin:/bin" TZ="America/Chicago"
cd "$DIR/epg" || exit 1
rm -f "$DIR/www/next.xml" "$DIR/www/next.xml.gz"
npm run grab --silent -- --channels="$DIR/huntsville.channels.xml" --output="$DIR/www/next.xml" --days=2 --delay=1500 --gzip \
  | grep -E "programs\)|done|rror" || true
node "$DIR/check.js" "$DIR/www/next.xml" "$DIR/www/status.json" || { echo "check failed: keeping the last good guide"; exit 1; }
mv -f "$DIR/www/next.xml" "$DIR/www/huntsville.xml"
mv -f "$DIR/www/next.xml.gz" "$DIR/www/huntsville.xml.gz"
echo "ok: $(cat "$DIR/www/status.json")"
