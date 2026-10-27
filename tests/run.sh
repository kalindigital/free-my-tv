#!/usr/bin/env bash
# Runs the plain-Java tests (no TV needed). Optional: a saved RSS file to also parse a real feed, and a folder of
# saved article pages to see what the reader keeps from each.
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
ANDROID_JAR="${ANDROID_JAR:-$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/platforms/android-*/android.jar | sort -V | tail -n 1)}"
javac -nowarn -encoding UTF-8 -d "$out" app/src/io/freemytv/Rss.java app/src/io/freemytv/Reader.java tests/RssTest.java tests/ReaderTest.java
javac -nowarn -encoding UTF-8 -cp "$ANDROID_JAR" -sourcepath app/src:out/gen -d "$out" tests/CinemoraRefreshTest.java tests/NetworkTest.java tests/DreamTest.java
java -cp "$out" io.freemytv.RssTest "${1:-}"
java -cp "$out" io.freemytv.ReaderTest "$@"
java -cp "$out" io.freemytv.CinemoraRefreshTest
java -cp "$out" io.freemytv.NetworkTest
java -cp "$out" io.freemytv.DreamTest
