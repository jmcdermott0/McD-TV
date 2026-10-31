#!/bin/bash
# Jarvis house guide: install and start on McD-Server in one go (Route 2: run from Terminal, no restart).
# Copies this folder to ~/Library/Application Support/JarvisGuide, runs setup and a first grab, then loads the
# two launchd agents (grab every 4 hours, guide server on port 8787). Touches nothing else.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
DIR="$HOME/Library/Application Support/JarvisGuide"
mkdir -p "$DIR" "$HOME/Library/LaunchAgents"
for f in setup.sh grab.sh serve.sh check.js server.js huntsville.channels.xml; do cp -f "$SRC/$f" "$DIR/$f"; done
chmod +x "$DIR"/*.sh
bash "$DIR/setup.sh"
bash "$DIR/grab.sh" || true
tail -n 3 "$DIR/logs/grab.log"
for n in grab serve; do
  cp -f "$SRC/launchd/com.mcd.jarvisguide.$n.plist" "$HOME/Library/LaunchAgents/"
  launchctl bootout "gui/$(id -u)/com.mcd.jarvisguide.$n" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/com.mcd.jarvisguide.$n.plist"
done
launchctl list | grep jarvisguide || true
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS http://127.0.0.1:8787/status.json; then echo; echo "Jarvis house guide: running."; exit 0; fi
  sleep 3
done
echo "Jarvis house guide: installed, but the server did not answer yet. See /tmp/com.mcd.jarvisguide.serve.log"
