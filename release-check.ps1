param(
    [switch]$DockerSmoke
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-CheckedStep {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "`n==> $Name" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
    Write-Host "[PASS] $Name" -ForegroundColor Green
}

function Invoke-InDirectory {
    param([string]$Path, [scriptblock]$Action)
    Push-Location $Path
    try { & $Action } finally { Pop-Location }
}

Set-Location $projectRoot
Write-Host "MusicHub release verification" -ForegroundColor Magenta

Invoke-CheckedStep 'Repository credential guard' {
    $trackedCredentials = @(git ls-files | Where-Object {
        $_ -match '(^|/)(\.env|\.env\..+)$' -and $_ -notmatch '\.example$'
    })
    if ($trackedCredentials.Count -gt 0) {
        throw "Tracked local environment files found: $($trackedCredentials -join ', ')"
    }
    $knownSecrets = @(git grep -n -I -E 'sk-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' -- . ':!README.md' ':!.env.docker.example' 2>$null)
    if ($LASTEXITCODE -gt 1) { throw 'Git secret scan could not run.' }
    if ($knownSecrets.Count -gt 0) { throw "Possible committed credentials found:`n$($knownSecrets -join "`n")" }
    $global:LASTEXITCODE = 0
}

Invoke-CheckedStep 'Docker Compose configuration' {
    docker compose --env-file .env.docker.example config --quiet
}

Invoke-CheckedStep 'Spring Boot tests' {
    Invoke-InDirectory (Join-Path $projectRoot 'springboot-web-demo') { mvn test }
}

foreach ($service in @('agent-service', 'rag-service')) {
    Invoke-CheckedStep "$service tests" {
        $serviceDirectory = Join-Path $projectRoot $service
        $python = Join-Path $serviceDirectory '.venv\Scripts\python.exe'
        if (-not (Test-Path $python)) {
            throw "$service virtual environment is missing. Run: python -m venv $service\.venv"
        }
        & $python -c 'import pytest' 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "pytest is missing. Run: $python -m pip install -r $serviceDirectory\requirements-dev.txt"
        }
        Invoke-InDirectory $serviceDirectory { & $python -m pytest -q }
    }
}

Invoke-CheckedStep 'Vue lint and production build' {
    Invoke-InDirectory (Join-Path $projectRoot 'vue-login') {
        npm run lint
        if ($LASTEXITCODE -eq 0) { npm run build }
    }
}

if ($DockerSmoke) {
    Invoke-CheckedStep 'Docker engine availability' { docker info --format '{{.ServerVersion}}' }
    Invoke-CheckedStep 'Docker build and startup' { docker compose --env-file .env.docker.example up -d --build }
    Write-Host 'Waiting for container health checks...' -ForegroundColor Cyan
    $deadline = (Get-Date).AddMinutes(3)
    $urls = @('http://localhost:8081', 'http://localhost:8082/api/admin/public/audioList', 'http://localhost:8090/health', 'http://localhost:8100/health')
    do {
        $ready = $true
        foreach ($url in $urls) {
            try { Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 5 | Out-Null }
            catch { $ready = $false; break }
        }
        if (-not $ready) { Start-Sleep -Seconds 5 }
    } while (-not $ready -and (Get-Date) -lt $deadline)
    if (-not $ready) { throw 'Docker services did not become healthy within three minutes.' }
    Write-Host '[PASS] Docker HTTP smoke test' -ForegroundColor Green
}

Write-Host "`nAll requested release checks passed." -ForegroundColor Green
if (-not $DockerSmoke) {
    Write-Host 'Run .\release-check.ps1 -DockerSmoke after starting Docker Desktop for the full container smoke test.' -ForegroundColor Yellow
}
