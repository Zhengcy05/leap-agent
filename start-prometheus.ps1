$ErrorActionPreference = "Stop"

$composeFile = Join-Path $PSScriptRoot "prometheus-docker.yml"

Write-Host "Starting Prometheus with compose file: $composeFile"
docker compose -f $composeFile up -d

Write-Host ""
Write-Host "Prometheus status:"
docker compose -f $composeFile ps

Write-Host ""
Write-Host "Prometheus UI: http://localhost:9090"
