#!/usr/bin/env bash
# Compiles and runs the :core unit tests with kotlinc + JUnit 4, without Gradle.
# Used on machines where Gradle cannot download its plugins.
# Usage: tools/test-core.sh [fully.qualified.TestClass ...]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_VERSION="2.1.21"
CACHE="${SPECTRASDR_CACHE:-$HOME/.cache/spectrasdr}"
KHOME="$CACHE/kotlinc-$KOTLIN_VERSION/kotlinc"

if [ ! -x "$KHOME/bin/kotlinc" ]; then
  mkdir -p "$CACHE/kotlinc-$KOTLIN_VERSION"
  curl -fsSL -o "$CACHE/kotlinc-$KOTLIN_VERSION.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q -o "$CACHE/kotlinc-$KOTLIN_VERSION.zip" -d "$CACHE/kotlinc-$KOTLIN_VERSION"
  rm -f "$CACHE/kotlinc-$KOTLIN_VERSION.zip"
fi

JUNIT="${JUNIT_JAR:-$( (ls /opt/gradle*/lib/junit-4*.jar 2>/dev/null || true) | head -n1)}"
HAMCREST="${HAMCREST_JAR:-$( (ls /opt/gradle*/lib/hamcrest-core-*.jar 2>/dev/null || true) | head -n1)}"
if [ -z "$JUNIT" ] || [ -z "$HAMCREST" ]; then
  echo "Set JUNIT_JAR and HAMCREST_JAR to junit-4.13.2.jar and hamcrest-core-1.3.jar" >&2
  exit 2
fi

OUT="$ROOT/build/core-local"
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"
STDLIB="$KHOME/lib/kotlin-stdlib.jar"

mapfile -t MAIN_SRC < <(find "$ROOT/core/src/main/kotlin" -name '*.kt' 2>/dev/null | sort)
mapfile -t TEST_SRC < <(find "$ROOT/core/src/test/kotlin" -name '*.kt' 2>/dev/null | sort)

if [ "${#MAIN_SRC[@]}" -gt 0 ]; then
  "$KHOME/bin/kotlinc" -nowarn -jvm-target 17 -d "$OUT/main" "${MAIN_SRC[@]}"
fi
"$KHOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$OUT/main:$JUNIT:$HAMCREST" -d "$OUT/test" "${TEST_SRC[@]}"

if [ "$#" -gt 0 ]; then
  CLASSES=("$@")
else
  mapfile -t CLASSES < <(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
fi
java -Xss8m -cp "$OUT/main:$OUT/test:$JUNIT:$HAMCREST:$STDLIB" org.junit.runner.JUnitCore "${CLASSES[@]}"
