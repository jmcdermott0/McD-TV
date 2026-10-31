#!/bin/bash
# Jarvis house guide: one-time setup on McD-Server (macOS). Safe to run again.
# Puts a private Node.js and iptv-org's EPG grabber under ~/Library/Application Support/JarvisGuide.
# Needs only what macOS ships with (curl, shasum, tar). No Homebrew, no sudo.
set -euo pipefail
DIR="$HOME/Library/Application Support/JarvisGuide"
EPG_COMMIT="ae9cc64f9497c602ec2f6e17857ae38b3aa1ff05"   # iptv-org/epg, Oct 28, 2026
NODE_LINE="latest-v22.x"
mkdir -p "$DIR/www" "$DIR/logs"
cd "$DIR"

if [ ! -x "$DIR/node/bin/node" ]; then
  arch="$(uname -m)"; [ "$arch" = "x86_64" ] && arch="x64"
  base="https://nodejs.org/dist/$NODE_LINE"
  sums="$(curl -fsSL "$base/SHASUMS256.txt")"
  file="$(printf '%s\n' "$sums" | awk -v a="darwin-$arch.tar.gz" '$2 ~ a"$" {print $2; exit}')"
  want="$(printf '%s\n' "$sums" | awk -v f="$file" '$2 == f {print $1}')"
  [ -n "$file" ] && [ -n "$want" ] || { echo "setup: no Node build for darwin-$arch"; exit 1; }
  curl -fsSL -o node.tar.gz "$base/$file"
  got="$(shasum -a 256 node.tar.gz | awk '{print $1}')"
  [ "$got" = "$want" ] || { echo "setup: Node download hash mismatch"; rm -f node.tar.gz; exit 1; }
  rm -rf node node-tmp && mkdir node-tmp && tar -xzf node.tar.gz -C node-tmp --strip-components 1 && mv node-tmp node && rm node.tar.gz
fi
export PATH="$DIR/node/bin:/usr/bin:/bin"

if [ ! -f "$DIR/epg/.commit" ] || [ "$(cat "$DIR/epg/.commit")" != "$EPG_COMMIT" ]; then
  rm -rf epg epg-tmp && mkdir epg-tmp
  curl -fsSL "https://codeload.github.com/iptv-org/epg/tar.gz/$EPG_COMMIT" | tar -xz -C epg-tmp --strip-components 1
  (cd epg-tmp && npm ci --no-audit --no-fund --ignore-scripts >/dev/null && (npm run api:load >/dev/null 2>&1 || true))
  echo "$EPG_COMMIT" > epg-tmp/.commit && mv epg-tmp epg
fi
echo "setup: ok (node $(node -v), iptv-org/epg ${EPG_COMMIT:0:7})"
