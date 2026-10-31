#!/usr/bin/env bash
# Compiles and runs the pure-JVM unit tests (app/src/test) WITHOUT Gradle or the Android SDK.
# CI runs the real thing (./gradlew testReleaseUnitTest in .github/workflows/build.yml); this script is for
# machines where the Android build cannot run (no SDK, no Google Maven).
#
# Prerequisites
#   - JDK 17+ (java, javac on PATH).
#   - A Kotlin 2.x compiler, any of:
#       KOTLINC=/path/to/kotlinc                       (a kotlinc launcher), or
#       KOTLIN_COMPILER_JAR=/path/kotlin-compiler-embeddable-2.x.jar, or
#       a Gradle distribution (its lib/ has the embeddable compiler): /opt/gradle-*/lib or
#       ~/.gradle/wrapper/dists/*/*/gradle-*/lib  (found automatically).
#   - Jars: kotlin-stdlib, kotlinx-coroutines-core-jvm, junit-4.13.2, hamcrest-core-1.3. Taken from the same
#     Gradle lib/ dir, or set LIB_DIR=/dir/with/those/jars.
#   - org.json: ORG_JSON_JAR=/path/json-20240303.jar, else ~/.m2 / Gradle caches, else a download from Maven
#     Central is tried, else a small local stand-in (tools/jvm-stubs/java/org/json) is compiled. CI always uses
#     the real org.json:json:20240303.
#
# What is compiled: the main files the tests use (MAIN_FILES below) plus local-only stubs for the Android
# pieces they reference (tools/jvm-stubs: Prefs, android.util.Xml, XmlPullParser). StreamSource is copied
# out of data/Addons.kt at run time so it never drifts. Tests under data/sync/ use only the pure store
# (SYNC_FILES); set TEST_GLOB to choose tests yourself.
#
# Usage:  tools/run-unit-tests.sh            (from anywhere; exit code 0 = all passed)
set -euo pipefail
shopt -s globstar nullglob

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/com/mcd/tv/data"
TEST_ROOT="$ROOT/app/src/test/java"
STUBS="$ROOT/tools/jvm-stubs"
OUT="$ROOT/app/build/local-unit-tests"
MAIN_FILES=(JarvisReply.kt LiveCatalog.kt LiveHome.kt Live.kt Games.kt StreamInfo.kt Epg.kt Http.kt Taste.kt SourceQueue.kt BabyCountdown.kt RdErrors.kt RdFilter.kt RdLimiter.kt SeekAccumulator.kt StillWatching.kt ContinueRules.kt LiveFavMerge.kt TvLink.kt XtreamVod.kt TvIdentity.kt NowPlaying.kt RecentErrors.kt WallCycle.kt KeepAwake.kt WallWatch.kt PlaybackRescue.kt SourceHealth.kt ProgressMerge.kt HistoryEras.kt TrueCrime.kt QualityFallback.kt Wall.kt WallFeed.kt WallSpotlight.kt PhotoCodec.kt DailyPicks.kt WatchListRow.kt)
# Profile sync's pure store (CRDT, packing, budgets) under data/sync/.
SYNC_FILES=(ProfileStore.kt)
# Pure UI logic (no Compose) under ui/.
UI_FILES=(SettingsModel.kt)
# Live TV's extra guide for free streaming channels (pure parser, tests in GuideExtraTest).
MAIN_FILES+=(GuideExtra.kt GuideDiag.kt)

say() { echo "[unit-tests] $*" >&2; }
die() { say "ERROR: $*"; exit 1; }

# ---------------------------------------------------------------- toolchain
find_gradle_lib() {
  local d
  for d in /opt/gradle-*/lib "$HOME"/.gradle/wrapper/dists/*/*/gradle-*/lib /usr/share/gradle*/lib /usr/local/gradle*/lib; do
    if compgen -G "$d/kotlin-compiler-embeddable-*.jar" >/dev/null; then echo "$d"; return 0; fi
  done
  return 1
}
GRADLE_LIB="$(find_gradle_lib || true)"
LIB_DIR="${LIB_DIR:-$GRADLE_LIB}"

pick() { # pick <glob> : first jar matching in LIB_DIR, ~/.m2 or Gradle caches
  local f
  for f in ${LIB_DIR:+$LIB_DIR/$1} "$HOME"/.m2/repository/**/$1 "$HOME"/.gradle/caches/modules-2/files-2.1/*/*/*/*/$1; do
    [ -f "$f" ] && { echo "$f"; return 0; }
  done
  return 1
}

STDLIB="$(pick 'kotlin-stdlib-[0-9]*.jar')" || die "kotlin-stdlib jar not found (set LIB_DIR)"
COROUTINES="$(pick 'kotlinx-coroutines-core-jvm-*.jar')" || die "kotlinx-coroutines-core-jvm jar not found (set LIB_DIR)"
JUNIT="$(pick 'junit-4.13*.jar')" || die "junit 4.13 jar not found (set LIB_DIR)"
HAMCREST="$(pick 'hamcrest-core-1.3.jar')" || die "hamcrest-core-1.3 jar not found (set LIB_DIR)"

kotlinc_run() {
  if [ -n "${KOTLINC:-}" ]; then "$KOTLINC" "$@"; return; fi
  if command -v kotlinc >/dev/null 2>&1 && [ -z "${KOTLIN_COMPILER_JAR:-}" ]; then kotlinc "$@"; return; fi
  local jar="${KOTLIN_COMPILER_JAR:-}"
  [ -n "$jar" ] || jar="$(pick 'kotlin-compiler-embeddable-*.jar')" || die "no Kotlin compiler found (set KOTLINC or KOTLIN_COMPILER_JAR)"
  local dir cp
  dir="$(dirname "$jar")"
  cp="$jar:$STDLIB:$COROUTINES"
  for extra in kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable annotations trove4j; do
    for f in "$dir"/$extra-*.jar; do [ -f "$f" ] && cp="$cp:$f"; done
  done
  java -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"
}

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/gen"

# ---------------------------------------------------------------- org.json
JSON_JAR="${ORG_JSON_JAR:-}"
[ -n "$JSON_JAR" ] || JSON_JAR="$(pick 'json-2024*.jar' || true)"
if [ -z "$JSON_JAR" ]; then
  if curl -fsSL -m 20 -o "$OUT/json-20240303.jar" \
      https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar 2>/dev/null; then
    JSON_JAR="$OUT/json-20240303.jar"
  fi
fi
JAVA_STUB_SRC=("$STUBS"/java/android/util/*.java "$STUBS"/java/org/xmlpull/v1/*.java)
if [ -n "$JSON_JAR" ]; then
  say "org.json: $JSON_JAR"
else
  say "org.json: real jar unavailable, using the local stand-in (tools/jvm-stubs/java/org/json)"
  JAVA_STUB_SRC+=("$STUBS"/java/org/json/*.java)
fi
# run_filtered <cmd...>: runs the command, hides the JVM's "Picked up JAVA_TOOL_OPTIONS" noise, keeps its exit code.
run_filtered() {
  local rc
  set +e
  "$@" 2>&1 | grep -v '^Picked up'
  rc=${PIPESTATUS[0]}
  set -e
  return "$rc"
}

run_filtered javac -nowarn -d "$OUT/stubs" "${JAVA_STUB_SRC[@]}" || die "stub compile failed"

# ---------------------------------------------------------------- sources
# StreamSource lives in Addons.kt (which needs half the app); copy just the data class.
{
  echo "package com.mcd.tv.data"
  sed -n '/^data class StreamSource(/,/^)/p' "$MAIN/Addons.kt"
} > "$OUT/gen/StreamSource.kt"
grep -q "data class StreamSource" "$OUT/gen/StreamSource.kt" || die "could not extract StreamSource from Addons.kt"

SRC=()
for f in "${MAIN_FILES[@]}"; do SRC+=("$MAIN/$f"); done
for f in "${UI_FILES[@]}"; do SRC+=("$ROOT/app/src/main/java/com/mcd/tv/ui/$f"); done
for f in "${SYNC_FILES[@]}"; do SRC+=("$MAIN/sync/$f"); done
SRC+=("$OUT/gen/StreamSource.kt" "$STUBS/kotlin/PrefsStub.kt")
if [ -n "${TEST_GLOB:-}" ]; then
  mapfile -t TESTS < <(cd "$TEST_ROOT" && compgen -G "$TEST_GLOB" | sed "s|^|$TEST_ROOT/|")
else
  mapfile -t TESTS < <(find "$TEST_ROOT" -name '*.kt' | sort)
fi
[ "${#TESTS[@]}" -gt 0 ] || die "no test sources found"

CP="$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$OUT/stubs${JSON_JAR:+:$JSON_JAR}"
say "compiling ${#SRC[@]} main + ${#TESTS[@]} test files"
run_filtered kotlinc_run -no-stdlib -no-reflect -jvm-target 17 -nowarn -cp "$CP" -d "$OUT/classes" "${SRC[@]}" "${TESTS[@]}" \
  || die "Kotlin compile failed"

# ---------------------------------------------------------------- run
mapfile -t CLASSES < <(cd "$OUT/classes" && find . -name '*Test.class' -not -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
[ "${#CLASSES[@]}" -gt 0 ] || die "no test classes compiled"
say "running: ${CLASSES[*]}"
cd "$ROOT"
run_filtered java -Djarvis.mainSrc="$ROOT/app/src/main/java" -cp "$OUT/classes:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
