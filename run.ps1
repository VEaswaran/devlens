#!/usr/bin/env pwsh
# run.ps1 — Build and run DevLens in one step.
#
# Usage:
#   .\run.ps1              # Build with Maven  (default) and start the server
#   .\run.ps1 -gradle      # Build with Gradle and start the server
#   .\run.ps1 -testOnly    # Run unit tests only (no server start)
#   .\run.ps1 -verify      # Full verify (unit + fat jar + integration tests)
#   .\run.ps1 -help        # Show this help
#
# The server reads DEVLENS_DATA_DIR from the environment. This script sets it
# to <repo-root>/devlens-data so the fixture repo (checkout-service) is visible
# immediately after startup.

param(
    [switch]$gradle,
    [switch]$testOnly,
    [switch]$verify,
    [switch]$help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ROOT  = $PSScriptRoot
$JAR_MAVEN  = Join-Path $ROOT "target\devlens.jar"
$JAR_GRADLE = Join-Path $ROOT "build\libs\devlens.jar"
$DATA_DIR   = Join-Path $ROOT "devlens-data"

function Write-Header($msg) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host ""
}

if ($help) {
    Get-Help $PSCommandPath
    exit 0
}

# ─── Verify java is on PATH ────────────────────────────────────────────────────
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: 'java' not found on PATH. Install JDK 17+ and try again." -ForegroundColor Red
    exit 1
}
$javaVersion = (java -version 2>&1 | Select-String "version").ToString()
Write-Host "Java: $javaVersion" -ForegroundColor DarkGray

# ─── Test-only mode ────────────────────────────────────────────────────────────
if ($testOnly) {
    Write-Header "Running unit tests"
    if ($gradle) {
        & "$ROOT\gradlew.bat" test --no-daemon
    } else {
        & mvn -B test -f "$ROOT\pom.xml"
    }
    Write-Host "`n✓ Unit tests complete." -ForegroundColor Green
    exit 0
}

# ─── Full verify mode ──────────────────────────────────────────────────────────
if ($verify) {
    Write-Header "Full verify (unit tests + fat JAR + integration tests)"
    $env:DEVLENS_DATA_DIR = $DATA_DIR
    if ($gradle) {
        & "$ROOT\gradlew.bat" check --no-daemon
    } else {
        & mvn -B clean verify -f "$ROOT\pom.xml"
    }
    Write-Host "`n✓ Verify complete." -ForegroundColor Green
    exit 0
}

# ─── Build fat JAR ─────────────────────────────────────────────────────────────
if ($gradle) {
    Write-Header "Building fat JAR with Gradle"
    & "$ROOT\gradlew.bat" shadowJar --no-daemon
    if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
    $JAR = $JAR_GRADLE
} else {
    Write-Header "Building fat JAR with Maven"
    & mvn -B clean package -DskipTests -f "$ROOT\pom.xml"
    if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
    $JAR = $JAR_MAVEN
}

if (-not (Test-Path $JAR)) {
    Write-Host "ERROR: JAR not found at $JAR" -ForegroundColor Red
    exit 1
}

# ─── Start server ──────────────────────────────────────────────────────────────
Write-Header "Starting DevLens MCP Server"
Write-Host "  JAR       : $JAR" -ForegroundColor White
Write-Host "  Data dir  : $DATA_DIR" -ForegroundColor White
Write-Host ""
Write-Host "  The server speaks MCP over STDIO. stdout is the JSON-RPC channel." -ForegroundColor Yellow
Write-Host "  Connect with Claude Desktop or any MCP client." -ForegroundColor Yellow
Write-Host "  Press Ctrl+C to stop." -ForegroundColor Yellow
Write-Host ""

$env:DEVLENS_DATA_DIR = $DATA_DIR
& java -jar $JAR

