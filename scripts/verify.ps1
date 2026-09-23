[CmdletBinding()]
param(
    [switch]$InstallDependencies,
    [switch]$SkipBuild,
    [switch]$IncludeLint
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $projectRoot 'backend'
$frontendPath = Join-Path $projectRoot 'frontend'

foreach ($command in @('java', 'mvn', 'node', 'pnpm')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Missing command: $command. See the run and test documentation."
    }
}

function Invoke-CheckedStep {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "`n[$Name]" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed (exit code $LASTEXITCODE)."
    }
}

Push-Location $backendPath
try {
    if ($SkipBuild) {
        Invoke-CheckedStep 'Backend tests (isolated test database)' { mvn -B test -DskipTests=false }
    } else {
        Invoke-CheckedStep 'Backend tests and package' { mvn -B package -DskipTests=false }
    }
} finally {
    Pop-Location
}

Push-Location $frontendPath
try {
    if ($InstallDependencies -or -not (Test-Path -LiteralPath 'node_modules')) {
        Invoke-CheckedStep 'Install locked frontend dependencies' { pnpm install --frozen-lockfile }
    }
    Invoke-CheckedStep 'Frontend type checking' { pnpm run typecheck }
    Invoke-CheckedStep 'Frontend unit tests' { pnpm run test:unit }
    if (-not $SkipBuild) {
        Invoke-CheckedStep 'Frontend production build' { pnpm run build }
    }
    # Historical formatting/lint debt is deliberately not hidden or auto-fixed.
    if ($IncludeLint) {
        Invoke-CheckedStep 'Full frontend lint (known historical findings)' { pnpm run lint }
    }
} finally {
    Pop-Location
}

Write-Host "`nVerification completed successfully." -ForegroundColor Green
