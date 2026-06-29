#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/android/gradle/wrapper/gradle-wrapper.jar"
PROPERTIES="$ROOT/android/gradle/wrapper/gradle-wrapper.properties"
UNIX_LAUNCHER="$ROOT/android/gradlew"
WINDOWS_LAUNCHER="$ROOT/android/gradlew.bat"

EXPECTED_JAR_SHA256="498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
EXPECTED_DISTRIBUTION_SHA256="d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_JAR_SHA256="$(sha256sum "$JAR" | awk '{print $1}')"
else
  ACTUAL_JAR_SHA256="$(shasum -a 256 "$JAR" | awk '{print $1}')"
fi
if [[ "$ACTUAL_JAR_SHA256" != "$EXPECTED_JAR_SHA256" ]]; then
  echo "Gradle wrapper JAR checksum mismatch" >&2
  exit 1
fi
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip' "$PROPERTIES"
grep -Fqx "distributionSha256Sum=$EXPECTED_DISTRIBUTION_SHA256" "$PROPERTIES"
WRAPPER_LISTING="$(mktemp)"
trap 'rm -f "$WRAPPER_LISTING"' EXIT
unzip -l "$JAR" > "$WRAPPER_LISTING"
grep -Fq 'org/gradle/wrapper/GradleWrapperMain.class' "$WRAPPER_LISTING"
grep -Fq -- '-classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar"' "$UNIX_LAUNCHER"
grep -Fq -- '-classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain' "$WINDOWS_LAUNCHER"
