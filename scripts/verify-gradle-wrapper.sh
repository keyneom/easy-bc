#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/android/gradle/wrapper/gradle-wrapper.jar"
PROPERTIES="$ROOT/android/gradle/wrapper/gradle-wrapper.properties"
UNIX_LAUNCHER="$ROOT/android/gradlew"
WINDOWS_LAUNCHER="$ROOT/android/gradlew.bat"

EXPECTED_JAR_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
EXPECTED_DISTRIBUTION_SHA256="9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_JAR_SHA256="$(sha256sum "$JAR" | awk '{print $1}')"
else
  ACTUAL_JAR_SHA256="$(shasum -a 256 "$JAR" | awk '{print $1}')"
fi
if [[ "$ACTUAL_JAR_SHA256" != "$EXPECTED_JAR_SHA256" ]]; then
  echo "Gradle wrapper JAR checksum mismatch" >&2
  exit 1
fi
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip' "$PROPERTIES"
grep -Fqx "distributionSha256Sum=$EXPECTED_DISTRIBUTION_SHA256" "$PROPERTIES"
WRAPPER_LISTING="$(mktemp)"
trap 'rm -f "$WRAPPER_LISTING"' EXIT
unzip -l "$JAR" > "$WRAPPER_LISTING"
grep -Fq 'org/gradle/wrapper/GradleWrapperMain.class' "$WRAPPER_LISTING"
grep -Fq -- '-jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar"' "$UNIX_LAUNCHER"
grep -Fq -- '-jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"' "$WINDOWS_LAUNCHER"
