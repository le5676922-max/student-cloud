#Requires -Version 5.1
<#
.SYNOPSIS
Builds and publishes versioned images. Does not deploy or change the server.
.DESCRIPTION
The legacy release-v4.ps1 filename is retained for compatibility; the default
release label is v5.0.0. Supply your own registry, namespace, and registry user.
Uses the existing Docker credential store. Never asks for or stores credentials.
Resume accepts only matching local image IDs and remote digests recorded by this
script. An unrelated existing remote tag is never overwritten.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$')]
    [string]$Version = 'v5.0.0',
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9.-]+(?::[0-9]+)?$')]
    [string]$Registry,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$Namespace,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.@-]+$')]
    [string]$RegistryUser,
    [switch]$Resume,
    [switch]$Rebuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$frontendRoot = Join-Path $projectRoot 'frontend'
$releaseRoot = Join-Path $projectRoot ".codex-tmp/releases/$Version"
$statePath = Join-Path $releaseRoot 'checkpoint.json'
$manifestPath = Join-Path $releaseRoot 'manifest.json'
$backendLocal = "student-cloud-backend:$Version"
$frontendLocal = "student-cloud-frontend:$Version"
$backendRemote = "$Registry/$Namespace/backend`:$Version"
$frontendRemote = "$Registry/$Namespace/frontend`:$Version"

function Write-Step([int]$Number, [string]$Message) {
    Write-Host "`n[$Number] $Message" -ForegroundColor Cyan
}

function Invoke-NativeResult {
    param([string]$Command, [string[]]$Arguments, [switch]$Stream)
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Command @Arguments 2>&1 | ForEach-Object {
            $line = $_.ToString()
            $lines.Add($line)
            if ($Stream) { Write-Host $line }
        }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    return [pscustomobject]@{ Code = $code; Output = ($lines -join "`n") }
}

function Stop-ForRegistryLogin {
    throw ("ACR authentication is required. Stop here and manually run:`n" +
        "docker login --username=$RegistryUser $Registry`n" +
        'Enter the registry password in Docker''s hidden prompt. ' +
        'After Login Succeeded, rerun this script with -Resume if a checkpoint exists. ' +
        'Do not paste the password into chat, scripts, or command arguments.')
}

function Assert-NativeSuccess($Result, [string]$Step) {
    if ($Result.Code -eq 0) { return }
    if ($Result.Output -match '(?i)unauthorized|authentication required|access denied|authorization failed|failed to authorize|insufficient_scope|requested access.*denied') {
        Stop-ForRegistryLogin
    }
    throw "$Step failed (exit $($Result.Code)). Stop and inspect the preceding command output."
}

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments, [string]$Step)
    $result = Invoke-NativeResult -Command $Command -Arguments $Arguments -Stream
    Assert-NativeSuccess $result $Step
}

function Get-TextSha256([string]$Value) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
}

function Get-SourceInfo {
    $head = Invoke-NativeResult git @('-C', $projectRoot, 'rev-parse', 'HEAD')
    Assert-NativeSuccess $head 'Read source commit'
    $status = Invoke-NativeResult git @('-C', $projectRoot, '-c', 'core.quotePath=false', 'status', '--porcelain=v1', '--untracked-files=all')
    Assert-NativeSuccess $status 'Read source status'
    # Hash only build inputs, never local secret files or runtime directories.
    $listing = Invoke-NativeResult git @('-C', $projectRoot, '-c', 'core.quotePath=false', 'ls-files', '--cached', '--others', '--exclude-standard', '--', 'backend', 'frontend')
    Assert-NativeSuccess $listing 'List source inputs'
    $entries = foreach ($relative in ($listing.Output -split "`n" | Sort-Object -Unique)) {
        if ([string]::IsNullOrWhiteSpace($relative)) { continue }
        if ($relative -match '(?i)(^|/)(target|dist|node_modules|\.git|\.codex-run|\.codex-tmp|\.local-data|\.cache)(/|$)|(^|/)\.env(?:\.|$)|application-local[^/]*\.properties$|(?:\.secret|\.secrets|\.pem|\.key|\.p12|\.pfx)$|(^|/)secrets?[^/]*$|(^|/)data/upload-staging(/|$)') { continue }
        $file = Join-Path $projectRoot $relative
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            "$relative`0$((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())"
        } else { "$relative`0<deleted>" }
    }
    $dirtyLines = @($status.Output -split "`n" | Where-Object { $_ })
    return [ordered]@{
        commit = $head.Output.Trim()
        dirty = ($dirtyLines.Count -gt 0)
        dirtyFileCount = $dirtyLines.Count
        dirtyStatusSha256 = Get-TextSha256 $status.Output
        buildInputsSha256 = Get-TextSha256 ($entries -join "`n")
    }
}

function Save-Json([string]$Path, $Value) {
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Save-Checkpoint {
    $script:state.updatedAtUtc = [DateTime]::UtcNow.ToString('o')
    Save-Json $statePath $script:state
}

function Get-RemoteDigest([string]$Image) {
    $result = Invoke-NativeResult docker @('buildx', 'imagetools', 'inspect', $Image)
    if ($result.Code -ne 0) {
        if ($result.Output -match '(?i)unauthorized|authentication required|denied|failed to authorize|insufficient_scope|403 Forbidden|401 Unauthorized') { Stop-ForRegistryLogin }
        if ($result.Output -match '(?i)manifest unknown|no such manifest|not found') { return $null }
        throw "Cannot verify remote image $Image. No build or push may proceed. Diagnostic: $($result.Output)"
    }
    $match = [regex]::Match($result.Output, '(?im)^\s*Digest:\s*(sha256:[a-f0-9]{64})\s*$')
    if (-not $match.Success) { throw "Registry returned no verifiable manifest digest for $Image." }
    return $match.Groups[1].Value.ToLowerInvariant()
}

function Get-LocalImageId([string]$Image) {
    $result = Invoke-NativeResult docker @('image', 'inspect', '--format', '{{.Id}}|{{.Os}}|{{.Architecture}}', $Image)
    if ($result.Code -ne 0) { return $null }
    $parts = $result.Output.Trim().Split('|')
    if ($parts.Count -ne 3 -or $parts[1] -ne 'linux' -or $parts[2] -ne 'amd64') {
        throw "$Image must be linux/amd64. Stop; no image is published."
    }
    if ($parts[0] -notmatch '^sha256:[a-f0-9]{64}$') { throw "Invalid local image ID: $Image" }
    return $parts[0]
}

function Assert-RemoteTagSafe([string]$Image, $Record) {
    $digest = Get-RemoteDigest $Image
    if ($digest -and (-not $Resume -or -not $Record.digest -or $digest -ne $Record.digest)) {
        throw "Remote tag already exists: $Image ($digest). It will not be overwritten. Use another approved release version, or investigate the existing release."
    }
    if ($Record.digest -and -not $digest) { throw "Previously recorded remote image is missing: $Image. Stop and investigate." }
    return $digest
}

function Publish-Image([string]$Local, [string]$Remote, $Record) {
    $existing = Assert-RemoteTagSafe $Remote $Record
    if ($existing) {
        Write-Host "Resume: verified existing remote digest $Remote@$existing"
        return
    }
    # Recheck immediately before pushing; Docker/ACR has no conditional push lock.
    Invoke-Checked docker @('tag', $Local, $Remote) 'Tag release image'
    if (Get-RemoteDigest $Remote) { throw "Remote tag appeared before push: $Remote. Refusing to overwrite it." }
    $push = Invoke-NativeResult docker @('push', $Remote) -Stream
    Assert-NativeSuccess $push 'Push release image'
    $matches = [regex]::Matches($push.Output, '(?i)digest:\s*(sha256:[a-f0-9]{64})')
    if ($matches.Count -eq 0) { throw "Push output has no digest for $Remote. Stop and investigate; do not push again blindly." }
    $pushedDigest = $matches[$matches.Count - 1].Groups[1].Value.ToLowerInvariant()
    $remoteDigest = Get-RemoteDigest $Remote
    if ($remoteDigest -ne $pushedDigest) { throw "Pushed and registry digests differ for $Remote. Stop." }
    $Record.digest = $remoteDigest
    Save-Checkpoint
    Write-Host "Verified digest: $Remote@$remoteDigest"
}

if ($Resume -and $Rebuild) { throw 'Choose either -Resume or -Rebuild.' }
foreach ($tool in @('docker', 'git', 'node', 'pnpm.cmd')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Required command is missing: $tool" }
}
foreach ($path in @($backendRoot, $frontendRoot, (Join-Path $backendRoot 'Dockerfile'), (Join-Path $frontendRoot 'Dockerfile.acr'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required build input is missing: $path" }
}

Push-Location $projectRoot
try {
    Write-Step 1 'Check Docker Client/Server, buildx, source, and production baseUrl'
    $dockerVersion = Invoke-NativeResult docker @('version', '--format', '{{json .}}')
    Assert-NativeSuccess $dockerVersion 'Docker Client/Server check (start Docker Desktop if unavailable)'
    $versionInfo = $dockerVersion.Output | ConvertFrom-Json
    if (-not $versionInfo.Client -or -not $versionInfo.Server) { throw 'Please start Docker Desktop and confirm Docker Engine is running before continuing.' }
    Invoke-Checked docker @('buildx', 'version') 'Docker buildx check'
    Invoke-Checked git @('-c', 'core.quotePath=false', 'status', '--short') 'Git status'
    $config = Get-Content -LiteralPath (Join-Path $frontendRoot 'public/student-cloud.config.json') -Raw | ConvertFrom-Json
    if ($null -eq $config.baseUrl -or $config.baseUrl -cne '') { throw 'Production student-cloud.config.json must have baseUrl exactly "". Stop; do not publish a development address.' }
    $package = Get-Content -LiteralPath (Join-Path $frontendRoot 'package.json') -Raw | ConvertFrom-Json
    $nodeVersion = Invoke-NativeResult node @('--version')
    Assert-NativeSuccess $nodeVersion 'Node version check'
    if ([version]($nodeVersion.Output.Trim().TrimStart('v')) -lt [version]'22.13.0') { throw 'Node >=22.13.0 is required.' }
    # pnpm selects packageManager from the current project, not its parent folder.
    Push-Location $frontendRoot
    try { $pnpmVersion = Invoke-NativeResult pnpm.cmd @('--version') }
    finally { Pop-Location }
    Assert-NativeSuccess $pnpmVersion 'pnpm version check'
    if ("pnpm@$($pnpmVersion.Output.Trim())" -ne $package.packageManager) { throw "Use the repository's packageManager: $($package.packageManager)." }
    $source = Get-SourceInfo
    if ($Resume) {
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No release checkpoint exists. Run without -Resume to start a new release.' }
        $script:state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($state.version -ne $Version -or $state.backend.image -ne $backendRemote -or $state.frontend.image -ne $frontendRemote) { throw 'Checkpoint does not match the requested version or registry.' }
        if ($state.source.commit -ne $source.commit -or $state.source.buildInputsSha256 -ne $source.buildInputsSha256) { throw 'Build inputs changed since the checkpoint. Resume is unsafe; investigate, then use -Rebuild only if remote tags are still absent.' }
    } else {
        if ((Test-Path -LiteralPath $statePath) -and -not $Rebuild) { throw 'A release checkpoint already exists. Use -Resume, or -Rebuild to rebuild only while both remote tags are absent.' }
        $script:state = [ordered]@{
            version = $Version
            source = $source
            backend = [ordered]@{ image = $backendRemote; localImageId = ''; digest = '' }
            frontend = [ordered]@{ image = $frontendRemote; localImageId = ''; digest = ''; indexSha256 = '' }
            updatedAtUtc = [DateTime]::UtcNow.ToString('o')
        }
    }

    Write-Step 2 'Check ACR through the existing Docker credential store; refuse unrelated existing tags'
    $null = Assert-RemoteTagSafe $backendRemote $state.backend
    $null = Assert-RemoteTagSafe $frontendRemote $state.frontend
    $null = New-Item -ItemType Directory -Path $releaseRoot -Force
    Save-Checkpoint

    Write-Step 3 'Build and inspect backend (linux/amd64)'
    if ($Resume -and $state.backend.localImageId) {
        if ((Get-LocalImageId $backendLocal) -ne $state.backend.localImageId) { throw 'Recorded backend local image is missing or changed. Refusing to resume.' }
        Write-Host "Resume: verified backend image ID $($state.backend.localImageId)"
    } else {
        $buildArguments = @('buildx', 'build', '--platform', 'linux/amd64', '--provenance=false', '--sbom=false', '--load', '-t', $backendLocal)
        if ($Rebuild) { $buildArguments += '--no-cache' }
        $buildArguments += $backendRoot
        Invoke-Checked docker $buildArguments 'Build backend'
        $state.backend.localImageId = Get-LocalImageId $backendLocal
        if (-not $state.backend.localImageId) { throw 'Backend image inspection failed.' }
        Save-Checkpoint
    }
    Write-Step 4 'Tag, push, and verify backend digest'
    Publish-Image $backendLocal $backendRemote $state.backend

    Write-Step 5 'Install frozen frontend dependencies, typecheck, build, and hash index.html'
    $indexPath = Join-Path $frontendRoot 'dist/index.html'
    if ($Resume -and $state.frontend.localImageId) {
        if ((Get-LocalImageId $frontendLocal) -ne $state.frontend.localImageId) { throw 'Recorded frontend local image is missing or changed. Refusing to resume.' }
        if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) { throw 'Recorded frontend dist/index.html is missing. Refusing to resume.' }
        if ((Get-FileHash -LiteralPath $indexPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $state.frontend.indexSha256) { throw 'Local frontend index.html changed after the checkpoint.' }
        Write-Host "Resume: verified frontend image ID and index SHA256 $($state.frontend.indexSha256)"
    } else {
        Push-Location $frontendRoot
        try {
            Invoke-Checked pnpm.cmd @('install', '--frozen-lockfile') 'Install frozen frontend dependencies'
            Invoke-Checked pnpm.cmd @('run', 'typecheck') 'Frontend typecheck'
            Invoke-Checked pnpm.cmd @('run', 'build') 'Frontend build'
        } finally { Pop-Location }
        if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) { throw 'Frontend build did not create dist/index.html.' }
        $state.frontend.indexSha256 = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA256).Hash.ToLowerInvariant()
        # pnpm/Vite may regenerate declarations; checkpoint the final build inputs.
        $state.source = Get-SourceInfo
        Save-Checkpoint
        Write-Host "Frontend index SHA256: $($state.frontend.indexSha256)"

        Write-Step 6 'Build frontend from the validated local dist and current Nginx files'
        $context = Join-Path $releaseRoot "frontend-context-$([Guid]::NewGuid().ToString('N'))"
        $null = New-Item -ItemType Directory -Path $context
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'dist') -Destination (Join-Path $context 'dist') -Recurse
        $null = New-Item -ItemType Directory -Path (Join-Path $context 'nginx')
        foreach ($name in @('default.conf', 'proxy.conf', 'routes.conf')) {
            Copy-Item -LiteralPath (Join-Path $frontendRoot "nginx/$name") -Destination (Join-Path $context "nginx/$name")
        }
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'Dockerfile.acr') -Destination (Join-Path $context 'Dockerfile.acr')
        Invoke-Checked docker @('buildx', 'build', '--no-cache', '--platform', 'linux/amd64', '--provenance=false', '--sbom=false', '--load', '-f', (Join-Path $context 'Dockerfile.acr'), '-t', $frontendLocal, $context) 'Build frontend release image'
        $state.frontend.localImageId = Get-LocalImageId $frontendLocal
        if (-not $state.frontend.localImageId) { throw 'Frontend image inspection failed.' }
        Save-Checkpoint
        Write-Host "Retained generated build context for review: $context"
    }

    Write-Step 7 'Tag, push, and verify frontend digest'
    Publish-Image $frontendLocal $frontendRemote $state.frontend
    foreach ($record in @($state.backend, $state.frontend)) {
        if ((Get-RemoteDigest $record.image) -ne $record.digest) { throw "Final remote digest changed: $($record.image)" }
    }
    $manifest = [ordered]@{
        version = $Version
        source = $state.source
        images = [ordered]@{
            backend = [ordered]@{ image = $backendRemote; digest = $state.backend.digest }
            frontend = [ordered]@{ image = $frontendRemote; digest = $state.frontend.digest }
        }
        frontendIndexSha256 = $state.frontend.indexSha256
        publishedAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    Save-Json $manifestPath $manifest
    Write-Host "`nLocal build/publication complete. Manifest: $manifestPath" -ForegroundColor Green
    Write-Host '[8-17] Server deployment is performed separately by the current agent in stages.'
    Write-Host 'The server must only pull/run these images. Manual /install and explicit production cutover confirmation are still required.'
} finally { Pop-Location }
