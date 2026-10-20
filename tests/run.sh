#!/usr/bin/env bash
# Runs the plain-Java tests (no TV needed). Optional: a saved RSS file to also parse a real feed, and a folder of
# saved article pages to see what the reader keeps from each.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
javac -nowarn -encoding UTF-8 -d "$out" app/src/io/freemytv/Rss.java app/src/io/freemytv/Reader.java tests/RssTest.java tests/ReaderTest.java
java -cp "$out" io.freemytv.RssTest "${1:-}"
java -cp "$out" io.freemytv.ReaderTest "$@"
