[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,
    [string]$DataDirectory,
    [switch]$Build,
    [switch]$DisableAi
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $projectRoot 'zfile-main'
$frontendIndex = Join-Path $projectRoot 'zfile-vue-main/dist/index.html'

if ($Build) {
    & (Join-Path $PSScriptRoot 'verify.ps1')
}

[xml]$projectPom = Get-Content -LiteralPath (Join-Path $backendPath 'pom.xml') -Raw
$jarPath = Join-Path $backendPath ("target/{0}-{1}.jar" -f $projectPom.project.artifactId, $projectPom.project.version)
if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $frontendIndex)) {
    throw 'Build output is missing. Run scripts/verify.ps1 or use -Build first.'
}
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
} elseif ($javaCommand) {
    $javaExecutable = $javaCommand.Source
} else {
    throw 'Java is missing. Install JDK 21 and add it to PATH.'
}

if (-not $DataDirectory) {
    $DataDirectory = Join-Path $backendPath '.local-data'
}
$localDataPath = [IO.Path]::GetFullPath($DataDirectory)
New-Item -ItemType Directory -Path $localDataPath -Force | Out-Null

$previousDataPath = $env:TLGXY_CLOUD_LOCAL_DATA
$env:TLGXY_CLOUD_LOCAL_DATA = $localDataPath.Replace('\', '/')
$javaArguments = @('-jar', $jarPath, '--spring.profiles.active=local', "--server.port=$Port")
if ($DisableAi) {
    $javaArguments += '--tongligong-student-cloud.ai.enabled=false'
}

Write-Host "Local cloud: http://127.0.0.1:$Port"
Write-Host "SQLite data: $localDataPath"
Write-Host 'This local profile binds to loopback only and does not connect to MySQL. Press Ctrl+C to stop.'
Push-Location $backendPath
try {
    & $javaExecutable @javaArguments
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
    $env:TLGXY_CLOUD_LOCAL_DATA = $previousDataPath
}
