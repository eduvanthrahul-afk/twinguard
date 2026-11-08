# Builds TwinGuard if needed, then starts it at http://127.0.0.1:8787
# Usage: powershell -ExecutionPolicy Bypass -File .\start.ps1 [-Rebuild]
param([switch]$Rebuild)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$jar = Join-Path $root "backend\target\twinguard.jar"

if ($Rebuild -or -not (Test-Path $jar)) {
    Push-Location (Join-Path $root "frontend")
    if (-not (Test-Path "node_modules")) { npm.cmd install --no-audit --no-fund; if ($LASTEXITCODE) { throw "npm install failed" } }
    npm.cmd run build; if ($LASTEXITCODE) { throw "Frontend build failed" }
    Pop-Location
    Push-Location (Join-Path $root "backend")
    mvn.cmd -q -B package -DskipTests; if ($LASTEXITCODE) { throw "Backend build failed" }
    Pop-Location
}

$env:TWINGUARD_FMU_DIR = Join-Path $root "fmus"
# Run history, imported release packages and saved scores are kept here.
$env:TWINGUARD_DATA_DIR = (Join-Path $root "data\store") -replace "\\", "/"
Write-Host "Starting TwinGuard at http://127.0.0.1:8787 (press Ctrl+C to stop)"
java -jar $jar
