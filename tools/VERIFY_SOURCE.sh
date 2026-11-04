#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
if command -v kotlinc >/dev/null; then
  kotlinc "$HERE/app/src/main/java/com/mcs/android/EditorLogic.kt" "$HERE/tools/test_editorlogic.kt" -include-runtime -d /tmp/mcs_logic.jar
  java -jar /tmp/mcs_logic.jar
else
  echo "Install Kotlin CLI for the non-Android logic test, or run GitHub CI for Gradle tests."
fi
