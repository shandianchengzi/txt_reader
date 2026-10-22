#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
javac -d "$build" \
  "$root/app/src/main/java/io/github/shandianchengzi/txtreader/ReadingSleepTimer.java" \
  "$root/tools/ReadingSleepTimerTest.java"
java -cp "$build" io.github.shandianchengzi.txtreader.ReadingSleepTimerTest
