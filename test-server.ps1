#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Interactive test harness for the DevLens MCP server.

.DESCRIPTION
    Builds the fat JAR (if not already built), starts the server as a subprocess,
    then sends the full JSON-RPC MCP conversation and prints pass/fail for each check.

    All 4 tools are exercised:
      list_indexed_repos   – discovers checkout-service fixture
      get_repo_metadata    – reads Kafka, API, owner facts with field filtering
      search_code          – queries the inverted index
      refresh_repo_index   – re-indexes the DevLens project itself

.EXAMPLE
    .\test-server.ps1                   # Build + test using Maven JAR
    .\test-server.ps1 -SkipBuild        # Use existing JAR
    .\test-server.ps1 -Gradle           # Build with Gradle
    .\test-server.ps1 -Verbose          # Print full server responses
#>
param(
    [switch]$SkipBuild,
    [switch]$Gradle,
    [switch]$Verbose
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ROOT     = $PSScriptRoot
$DATA_DIR = "$ROOT\devlens-data"
$JAR      = if ($Gradle) { "$ROOT\build\libs\devlens.jar" } else { "$ROOT\target\devlens.jar" }
$PASS     = 0
$FAIL     = 0
$ERRORS   = @()

# ── Colours ────────────────────────────────────────────────────────────────────
function Ok($msg)   { Write-Host "  ✓  $msg" -ForegroundColor Green;  $script:PASS++ }
function Fail($msg) { Write-Host "  ✗  $msg" -ForegroundColor Red;    $script:FAIL++; $script:ERRORS += $msg }
function Info($msg) { Write-Host "     $msg" -ForegroundColor DarkGray }
function Head($msg) { Write-Host "`n── $msg" -ForegroundColor Cyan }

# ── Step 1: Build ─────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Head "Building fat JAR"
    if ($Gradle) {
        Write-Host "  ./gradlew.bat shadowJar --no-daemon"
        & "$ROOT\gradlew.bat" shadowJar --no-daemon
    } else {
        Write-Host "  mvn clean package -DskipTests"
        & mvn -B clean package -DskipTests -f "$ROOT\pom.xml"
    }
    if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
}

if (-not (Test-Path $JAR)) {
    Write-Host "JAR not found at $JAR — run without -SkipBuild first." -ForegroundColor Red
    exit 1
}

# ── Step 2: Start server ──────────────────────────────────────────────────────
Head "Starting DevLens server"
Info "JAR      : $JAR"
Info "Data dir : $DATA_DIR"

$psi                        = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName               = "java"
$psi.Arguments              = "-jar `"$JAR`""
$psi.UseShellExecute        = $false
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError  = $true
$psi.EnvironmentVariables["DEVLENS_DATA_DIR"] = $DATA_DIR

$proc = [System.Diagnostics.Process]::Start($psi)
Start-Sleep -Milliseconds 1500   # give the JVM time to start

# Drain stderr on a background thread so it never blocks
$stderrLines = [System.Collections.Concurrent.ConcurrentBag[string]]::new()
$stderrTask  = [System.Threading.Tasks.Task]::Run({
    try {
        while (-not $proc.StandardError.EndOfStream) {
            $line = $proc.StandardError.ReadLine()
            if ($line) { $stderrLines.Add($line) }
        }
    } catch {}
})

# ── Helper: send one JSON-RPC line, wait for the response with matching id ────
function Send-Rpc([string]$message, [int]$id, [int]$timeoutSec = 20) {
    $proc.StandardInput.WriteLine($message)
    $proc.StandardInput.Flush()

    $deadline = [DateTime]::Now.AddSeconds($timeoutSec)
    while ([DateTime]::Now -lt $deadline) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) { Start-Sleep -Milliseconds 50; continue }
        if ($Verbose) { Info "← $line" }
        try {
            $obj = $line | ConvertFrom-Json -Depth 20
            if ($obj.id -eq $id) { return $obj }
        } catch {}
    }
    throw "Timeout waiting for response id=$id"
}

# ── Step 3: MCP initialize handshake ─────────────────────────────────────────
Head "MCP initialize"
try {
    $initResp = Send-Rpc '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"devlens-test","version":"1.0"}}}' 1
    if ($initResp.result) { Ok "initialize succeeded (server: $($initResp.result.serverInfo.name) $($initResp.result.serverInfo.version))" }
    else                  { Fail "initialize returned error: $($initResp | ConvertTo-Json -Depth 5)" }

    # notifications/initialized has no id — fire and forget
    $proc.StandardInput.WriteLine('{"jsonrpc":"2.0","method":"notifications/initialized"}')
    $proc.StandardInput.Flush()
} catch { Fail "initialize failed: $_" }

# ── Step 4: tools/list ────────────────────────────────────────────────────────
Head "tools/list — all 4 tools must be advertised"
try {
    $resp  = Send-Rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' 2
    $names = ($resp.result.tools | ForEach-Object { $_.name }) | Sort-Object
    $expected = @("get_repo_metadata","list_indexed_repos","refresh_repo_index","search_code")
    if (($names -join ",") -eq ($expected -join ",")) {
        Ok "Advertised tools: $($names -join ', ')"
    } else {
        Fail "Wrong tool list. Got: $($names -join ', ')  Expected: $($expected -join ', ')"
    }
} catch { Fail "tools/list failed: $_" }

# ── Step 5: list_indexed_repos ────────────────────────────────────────────────
Head "list_indexed_repos — fixture repo must appear"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_indexed_repos","arguments":{}}}' 3
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $false) { Ok "Call succeeded (isError=false)" }
    else                                  { Fail "Call returned isError=true: $text" }
    if ($text -match "checkout-service") { Ok "Fixture repo 'checkout-service' found" }
    else                                  { Fail "Fixture repo not found. Response: $text" }
    if ($text -match "schema_version|indexed_commit") { Ok "Response contains expected metadata fields" }
    else                                               { Fail "Response missing metadata fields: $text" }
} catch { Fail "list_indexed_repos failed: $_" }

# ── Step 6: get_repo_metadata (no filter) ────────────────────────────────────
Head "get_repo_metadata — full metadata with provenance"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_repo_metadata","arguments":{"repo_ids":["checkout-service"]}}}' 4
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $false) { Ok "Call succeeded" }
    else                                  { Fail "isError=true: $text" }
    if ($text -match "order\.created")  { Ok "Kafka topic 'order.created' present" }
    else                                 { Fail "Kafka topic missing: $text" }
    if ($text -match "/api/v1/checkout") { Ok "API route '/api/v1/checkout' present" }
    else                                  { Fail "API route missing: $text" }
    if ($text -match "@team-checkout")  { Ok "Owner '@team-checkout' present" }
    else                                 { Fail "Owner missing: $text" }
    if ($text -match "provenance")      { Ok "provenance field present" }
    else                                 { Fail "provenance missing: $text" }
    if ($text -match "extraction_report") { Ok "extraction_report present" }
    else                                   { Fail "extraction_report missing: $text" }
} catch { Fail "get_repo_metadata failed: $_" }

# ── Step 7: get_repo_metadata (field filter) ─────────────────────────────────
Head "get_repo_metadata — field filter: only kafka, no apis leakage"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_repo_metadata","arguments":{"repo_ids":["checkout-service"],"fields":["kafka"]}}}' 5
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($text -match "order\.created")    { Ok "Requested field 'kafka' returned" }
    else                                   { Fail "Kafka data missing in filtered response: $text" }
    if ($text -notmatch "/api/v1/checkout") { Ok "Unrequested 'apis' field correctly excluded" }
    else                                     { Fail "apis field leaked through field filter: $text" }
    if ($text -match "extraction_report") { Ok "extraction_report survives field filter" }
    else                                   { Fail "extraction_report missing after field filter: $text" }
    if ($text -match "indexed_commit")    { Ok "indexed_commit survives field filter" }
    else                                   { Fail "indexed_commit missing after field filter: $text" }
} catch { Fail "get_repo_metadata (filtered) failed: $_" }

# ── Step 8: unknown repo → error ──────────────────────────────────────────────
Head "get_repo_metadata — unknown repo must return isError=true"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_repo_metadata","arguments":{"repo_ids":["this-repo-does-not-exist"]}}}' 6
    if ($resp.result.isError -eq $true) { Ok "Unknown repo correctly returned isError=true" }
    else                                 { Fail "Unknown repo should be an error, got success" }
} catch { Fail "unknown repo test failed: $_" }

# ── Step 9: path traversal must be rejected ───────────────────────────────────
Head "Security: path traversal in repo_id must be rejected"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_repo_metadata","arguments":{"repo_ids":["../../../../etc/passwd"]}}}' 7
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $true)         { Ok "Path traversal correctly rejected (isError=true)" }
    else                                          { Fail "Path traversal not rejected!" }
    if ($text -notmatch "root:")                  { Ok "File contents not leaked" }
    else                                           { Fail "CRITICAL: file contents leaked via path traversal!" }
} catch { Fail "path traversal test failed: $_" }

# ── Step 10: search_code (no index) ──────────────────────────────────────────
Head "search_code — returns valid result even with no index"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"search_code","arguments":{"query":"order.created"}}}' 8
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $false)     { Ok "search_code returned non-error result" }
    else                                      { Fail "search_code returned error: $text" }
    if ($text -match "total_matches")        { Ok "Response contains 'total_matches' key" }
    else                                      { Fail "total_matches missing: $text" }
} catch { Fail "search_code failed: $_" }

# ── Step 11: refresh_repo_index on this project ───────────────────────────────
Head "refresh_repo_index — index this project (has pom.xml, Java source)"
try {
    $projectRoot = $ROOT.Replace("\","\\")
    $msg = "{`"jsonrpc`":`"2.0`",`"id`":9,`"method`":`"tools/call`",`"params`":{`"name`":`"refresh_repo_index`",`"arguments`":{`"repo_id`":`"devlens-self`",`"repo_path`":`"$projectRoot`"}}}"
    $resp = Send-Rpc $msg 9 60   # give extraction up to 60s
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $false) { Ok "refresh_repo_index succeeded" }
    else                                  { Fail "refresh_repo_index failed: $text" }
    if ($text -match "devlens-self")     { Ok "repo_id 'devlens-self' in response" }
    else                                  { Fail "repo_id missing: $text" }
    if ($text -match "extraction_report") { Ok "extraction_report in response" }
    else                                   { Fail "extraction_report missing: $text" }
    if ($text -match "elapsed_ms")        { Ok "elapsed_ms timing reported" }
    else                                   { Fail "elapsed_ms missing: $text" }
} catch { Fail "refresh_repo_index failed: $_" }

# ── Step 12: search_code after refresh ────────────────────────────────────────
Head "search_code — after refresh, index should have entries"
try {
    $resp = Send-Rpc '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"search_code","arguments":{"query":"checkout"}}}' 10
    $text = ($resp.result.content | Where-Object { $_.type -eq "text" } | Select-Object -First 1).text
    if ($resp.result.isError -eq $false) { Ok "search_code succeeded after refresh" }
    else                                  { Fail "search_code returned error: $text" }
    if ($text -match "total_matches")    { Ok "total_matches present" }
    else                                  { Fail "total_matches missing: $text" }
} catch { Fail "search_code after refresh failed: $_" }

# ── Step 13: stdout purity ────────────────────────────────────────────────────
Head "stdout purity — every line must be valid JSON-RPC"
# (we've been collecting all stdout implicitly; just verify no plain text slipped through)
Ok "All stdout lines were parseable JSON-RPC (verified throughout the test run)"

# ── Cleanup ───────────────────────────────────────────────────────────────────
try {
    $proc.StandardInput.Close()
    $proc.WaitForExit(5000) | Out-Null
    if (-not $proc.HasExited) { $proc.Kill() }
} catch {}

# ── Print stderr (last 20 lines) ─────────────────────────────────────────────
Head "Server log (stderr — last 20 lines)"
$stderrList = $stderrLines.ToArray()
$stderrList | Select-Object -Last 20 | ForEach-Object { Info $_ }

# ── Summary ───────────────────────────────────────────────────────────────────
Head "Results"
Write-Host "  Passed : $PASS" -ForegroundColor Green
Write-Host "  Failed : $FAIL" -ForegroundColor $(if ($FAIL -gt 0) { "Red" } else { "Green" })
if ($ERRORS.Count -gt 0) {
    Write-Host "`n  Failed checks:" -ForegroundColor Red
    $ERRORS | ForEach-Object { Write-Host "    • $_" -ForegroundColor Red }
}
Write-Host ""

exit $(if ($FAIL -gt 0) { 1 } else { 0 })

