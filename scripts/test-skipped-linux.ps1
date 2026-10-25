# Runs the original nine Redis and four symlink tests in disposable Linux containers.
# No business container, database, application secrets, host port or backend/target is used.
param(
    [string]$MavenImage = 'maven:3.9.9-eclipse-temurin-21-alpine',
    [string]$RedisImage = 'redis:7-alpine',
    [string]$MavenRepository = 'D:\develop\MAVEN—local repository'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $repo 'backend'
$runId = [Guid]::NewGuid().ToString('N').Substring(0,12)
$redisName = "student-cloud-skipped-redis-$runId"
$mavenName = "student-cloud-skipped-java-$runId"
$runRoot = Join-Path (Split-Path $repo -Parent) ".codex-tmp\skipped-linux-$runId"
$control = Join-Path $runRoot 'runner'
$reports = Join-Path $runRoot 'reports'
$log = Join-Path $runRoot 'maven.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$exitCode = 1

if (!(Test-Path -LiteralPath $MavenRepository -PathType Container)) { throw 'The read-only Maven dependency cache does not exist.' }
foreach ($image in @($MavenImage, $RedisImage)) {
    & docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { throw "Required image is not installed: $image. This offline runner does not pull images." }
}
New-Item -ItemType Directory -Path $control,$reports | Out-Null

# Match the repository identity recorded by the existing cached Maven artifacts.
# This contains no server credentials, and offline mode prohibits downloads.
$settings = @'
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors><mirror><id>aliyun-public</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors>
</settings>
'@
[IO.File]::WriteAllText((Join-Path $control 'settings.xml'), $settings, $utf8)
$selector = 'RedisStorageMetadataIntegrationTest,RedisRateLimiterIntegrationTest,LocalResumablePublicationTest#symbolicLinkTargetIsRejectedWithoutTouchingItsDestination,LocalResumableUploadAccessTest#symlinkAliasesCannotExposeStagingOrRedirectOwnedTargets,LocalUploadStagingPolicyTest#symlinkAliasToPrivateRootOrIndependentDirectoryIsRejected,WorkspaceStorageProvisioningServiceTest#symbolicLinkCannotTurnAWorkspaceDriveIntoAnotherDirectory'
$shell = @'
#!/bin/sh
set -u
mkdir -p /work
cd /work || exit 2
cp /input/pom.xml ./pom.xml || exit 2
cp -R /input/src ./src || exit 2
# All test-created links and data are on Linux temporary storage, never a host mount.
mkdir -p /tmp/symlink-capability
printf synthetic > /tmp/symlink-capability/target
ln -s /tmp/symlink-capability/target /tmp/symlink-capability/link || exit 2
test -L /tmp/symlink-capability/link || exit 2
mvn --batch-mode --no-transfer-progress --offline --settings /runner/settings.xml \
  -Dmaven.repo.local=/m2 -Daether.artifactResolver.simpleLrmInterop=true \
  -Dstudent.cloud.redis.integration=true -Dstudent.cloud.redis.port=16379 \
  "-Dtest=__SELECTOR__" '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m' test
status=$?
if [ -d /work/target/surefire-reports ]; then
  cp -R /work/target/surefire-reports/. /reports/ || exit 2
fi
exit "$status"
'@
$shell = $shell.Replace('__SELECTOR__',$selector).Replace("`r`n","`n")
[IO.File]::WriteAllText((Join-Path $control 'run.sh'), $shell, $utf8)

function Remove-OwnedTestContainer([string]$Name) {
    $present = & docker container ls --all --filter "name=^/$Name`$" --filter "label=student-cloud.skipped-test=$runId" --format '{{.Names}}'
    if ($LASTEXITCODE -ne 0 -or ($present -join '').Trim() -ne $Name) { return }
    $ownership = & docker inspect --format '{{ index .Config.Labels "student-cloud.skipped-test" }}' $Name 2>$null
    if ($LASTEXITCODE -eq 0 -and ($ownership -join '').Trim() -eq $runId) {
        & docker rm --force --volumes $Name | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove owned test container $Name." }
    }
}

try {
    Write-Output "Offline Linux test reports: $runRoot"
    & docker run --detach --pull never --name $redisName --label "student-cloud.skipped-test=$runId" `
        --network none --memory 128m --tmpfs '/data:rw,nosuid,size=67108864' `
        $RedisImage sh -c 'exec redis-server --bind 127.0.0.1 --protected-mode yes --port 16379 --save "" --appendonly no --maxmemory 64mb --maxmemory-policy noeviction' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated test Redis container.' }
    $ready = $false
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        $ping = & docker exec $redisName redis-cli -h 127.0.0.1 -p 16379 PING 2>$null
        if ($LASTEXITCODE -eq 0 -and ($ping -join '').Trim() -eq 'PONG') { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Isolated test Redis did not become ready.' }
    # Share ONLY the private Redis network namespace. No host networking or published ports.
    # Sources and dependency cache are read-only. Compilation happens in a private /work tmpfs.
    & docker run --pull never --name $mavenName --label "student-cloud.skipped-test=$runId" `
        --network "container:$redisName" --memory 2g --cpus 2 `
        --tmpfs '/work:rw,nosuid,size=536870912' `
        --env 'MAVEN_OPTS=-Xmx768m -XX:MaxMetaspaceSize=256m' --env STUDENT_CLOUD_LIVE_AI_TEST=false `
        --mount "type=bind,source=$backend\pom.xml,target=/input/pom.xml,readonly" `
        --mount "type=bind,source=$backend\src,target=/input/src,readonly" `
        --mount "type=bind,source=$MavenRepository,target=/m2,readonly" `
        --mount "type=bind,source=$control,target=/runner,readonly" `
        --mount "type=bind,source=$reports,target=/reports" `
        $MavenImage sh /runner/run.sh *> $log
    $mavenExit = $LASTEXITCODE
    Get-Content -LiteralPath $log -Tail 50
    if ($mavenExit -ne 0) { throw "Linux Maven failed with exit code $mavenExit. See $log" }
    $cases = @()
    foreach ($file in (Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File)) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($case in $report.SelectNodes('/testsuite/testcase')) { $cases += $case }
    }
    $skipped = @($cases | Where-Object { $_.SelectNodes('skipped').Count -gt 0 }).Count
    $failed = @($cases | Where-Object { $_.SelectNodes('failure|error').Count -gt 0 }).Count
    $summary = [ordered]@{ Tests=$cases.Count; Passed=$cases.Count-$skipped-$failed; Skipped=$skipped; Failed=$failed; Reports=$reports }
    $summary | ConvertTo-Json | Write-Output
    $summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding UTF8
    if ($cases.Count -ne 13 -or $skipped -ne 0 -or $failed -ne 0) { throw 'Expected exactly 13 original tests, zero skips and zero failures.' }
    $exitCode=0
} finally {
    Remove-OwnedTestContainer $mavenName
    Remove-OwnedTestContainer $redisName
}
exit $exitCode
