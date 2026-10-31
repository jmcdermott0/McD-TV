#!/bin/bash
# Waits for setup (first boot), then runs the guide server in the foreground for launchd.
DIR="$HOME/Library/Application Support/JarvisGuide"
until [ -x "$DIR/node/bin/node" ]; do sleep 30; done
exec "$DIR/node/bin/node" "$DIR/server.js"
