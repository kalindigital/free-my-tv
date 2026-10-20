#!/usr/bin/env bash
# Runs the plain-Java tests (no TV needed). Optional: a saved RSS file to also parse a real feed.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
javac -nowarn -d "$out" app/src/io/freemytv/Rss.java tests/RssTest.java
java -cp "$out" io.freemytv.RssTest "$@"
