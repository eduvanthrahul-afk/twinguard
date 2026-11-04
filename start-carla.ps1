# Starts the CARLA server if needed, then the TwinGuard bridge. Press Ctrl+C to stop the bridge.
# Usage: powershell -ExecutionPolicy Bypass -File .\start-carla.ps1 [-CarlaDir C:\Users\ASUS\CARLA_0.9.16]
param([string]$CarlaDir = "$HOME\CARLA_0.9.16")

$ErrorActionPreference = "Stop"
$python = Join-Path $PSScriptRoot "carla_bridge\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) { throw "The Python environment is missing. See carla_bridge\README.md." }

if (-not (Get-Process CarlaUE4-Win64-Shipping -ErrorAction SilentlyContinue)) {
    $exe = Join-Path $CarlaDir "CarlaUE4.exe"
    if (-not (Test-Path $exe)) { throw "CARLA isn't at $exe. Pass its folder with -CarlaDir." }
    Write-Host "Starting CARLA in low-quality mode..."
    Start-Process $exe -ArgumentList "-quality-level=Low", "-RenderOffScreen", "-nosound"
}

Write-Host "Waiting for CARLA to accept connections..."
$deadline = (Get-Date).AddMinutes(4)
while (-not (Test-NetConnection 127.0.0.1 -Port 2000 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    if ((Get-Date) -gt $deadline) { throw "CARLA didn't start within 4 minutes." }
    Start-Sleep -Seconds 3
}

& $python -B -u (Join-Path $PSScriptRoot "carla_bridge\bridge.py")
