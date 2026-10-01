#!/usr/bin/env bash
set -euo pipefail
if command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version 9.6.0
  echo "Gradle wrapper generated."
else
  echo "No system Gradle found. Open this project in Android Studio Quail 4 or later and let Android Studio configure Gradle, or install Gradle 9.6 and rerun this script." >&2
  exit 1
fi
