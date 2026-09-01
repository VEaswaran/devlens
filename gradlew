#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Licensed under the Apache License, Version 2.0.
#
# Gradle wrapper shell script (UNIX / macOS / Linux / WSL).
# On Windows use gradlew.bat.
#

# Resolve script directory
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"

# ─── Default JVM options ──────────────────────────────────────────────────────
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# ─── Locate java ──────────────────────────────────────────────────────────────
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
  echo "ERROR: JAVA_HOME is not set and no 'java' command found in PATH." >&2
  echo "       Please set the JAVA_HOME variable or add java to PATH." >&2
  exit 1
fi

# ─── Gradle wrapper jar ───────────────────────────────────────────────────────
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# ─── Classpath ────────────────────────────────────────────────────────────────
CLASSPATH="$WRAPPER_JAR"

# ─── Execute Gradle ───────────────────────────────────────────────────────────
exec "$JAVACMD" \
  $DEFAULT_JVM_OPTS \
  $JAVA_OPTS \
  $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"

