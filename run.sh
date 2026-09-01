#!/usr/bin/env bash
# run.sh — Build and run DevLens in one step (Linux / macOS / WSL).
#
# Usage:
#   ./run.sh              # Build with Maven (default) and start the server
#   ./run.sh --gradle     # Build with Gradle and start the server
#   ./run.sh --test-only  # Run unit tests only
#   ./run.sh --verify     # Full verify (unit + fat jar + integration tests)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_MAVEN="$ROOT/target/devlens.jar"
JAR_GRADLE="$ROOT/build/libs/devlens.jar"
DATA_DIR="$ROOT/devlens-data"
USE_GRADLE=false
TEST_ONLY=false
FULL_VERIFY=false

# Parse args
for arg in "$@"; do
  case $arg in
    --gradle)     USE_GRADLE=true ;;
    --test-only)  TEST_ONLY=true ;;
    --verify)     FULL_VERIFY=true ;;
    --help|-h)
      grep '^#' "$0" | head -12 | sed 's/^# \?//'
      exit 0 ;;
  esac
done

header() { echo; echo "══════════════════════════════════════════"; echo "  $1"; echo "══════════════════════════════════════════"; echo; }

# Verify java
if ! command -v java &>/dev/null; then
  echo "ERROR: 'java' not found on PATH. Install JDK 17+ and retry." >&2; exit 1
fi
echo "Java: $(java -version 2>&1 | head -1)"

export DEVLENS_DATA_DIR="$DATA_DIR"

if $TEST_ONLY; then
  header "Running unit tests"
  if $USE_GRADLE; then ./gradlew test --no-daemon
  else mvn -B test -f "$ROOT/pom.xml"; fi
  echo; echo "✓ Unit tests complete."; exit 0
fi

if $FULL_VERIFY; then
  header "Full verify (unit + fat JAR + integration tests)"
  if $USE_GRADLE; then ./gradlew check --no-daemon
  else mvn -B clean verify -f "$ROOT/pom.xml"; fi
  echo; echo "✓ Verify complete."; exit 0
fi

# Build
if $USE_GRADLE; then
  header "Building fat JAR with Gradle"
  ./gradlew shadowJar --no-daemon
  JAR="$JAR_GRADLE"
else
  header "Building fat JAR with Maven"
  mvn -B clean package -DskipTests -f "$ROOT/pom.xml"
  JAR="$JAR_MAVEN"
fi

[ -f "$JAR" ] || { echo "ERROR: JAR not found at $JAR"; exit 1; }

# Run
header "Starting DevLens MCP Server"
echo "  JAR      : $JAR"
echo "  Data dir : $DATA_DIR"
echo
echo "  stdout = JSON-RPC channel. Connect via Claude Desktop or any MCP client."
echo "  Press Ctrl+C to stop."
echo

exec java -jar "$JAR"

