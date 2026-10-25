# Runs the real HTTP workflows against a NEW loopback-only MySQL container.
# No existing database or remote deployment is read or modified.
param(
    [string]$JavaHome = 'D:\develop\Java\jdk-21.0.11',
    [string]$Maven = 'D:\develop\apache-maven-3.9.12\bin\mvn.cmd',
    [ValidateRange(1025,65535)][int]$Port = 33326,
    [string]$Image = 'mysql:8.4.11'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runId = [Guid]::NewGuid().ToString('N').Substring(0,12)
$container = "student-cloud-test-$runId"
$log = Join-Path (Split-Path $repo -Parent) ".codex-tmp\functional-mysql-$runId.log"
$oldPassword = $env:MYSQL_ROOT_PASSWORD
$originalEnvironment = @{}
foreach ($name in @('JAVA_HOME','PATH','STUDENT_CLOUD_LIVE_AI_TEST')) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
foreach ($prefix in @('CLOUD_TEST','CLOUD_REGISTRATION_TEST','CLOUD_OVERVIEW_TEST','CLOUD_RESUMABLE_TEST')) {
    foreach ($suffix in @('URL','USER','PASSWORD')) { $name = "${prefix}_MYSQL_$suffix"; $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
}
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$created = $false
$exitCode = 1
try {
    $env:MYSQL_ROOT_PASSWORD = $password
    # Docker receives the value from this process environment; it never appears in an argument or log.
    $result = & docker run --detach --name $container --label "student-cloud.test-run=$runId" `
        --publish "127.0.0.1:${Port}:3306" --memory 768m --tmpfs '/var/lib/mysql:rw,nosuid,size=1073741824' `
        --env MYSQL_ROOT_PASSWORD $Image --innodb-buffer-pool-size=128M --max-connections=60
    if ($LASTEXITCODE -ne 0) { throw 'Disposable MySQL container could not start.' }
    $created = $true
    Write-Output "Disposable MySQL started: $container; test log: $log"
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        & docker exec $container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h127.0.0.1 --batch --skip-column-names -e "SELECT 1" >/dev/null 2>&1'
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Disposable MySQL readiness timed out.' }
    $names = @("student_cloud_role_test_cloud_$runId","student_cloud_role_test_registration_$runId","student_cloud_role_test_overview_$runId","student_cloud_role_test_resumable_$runId")
    foreach ($name in $names) {
        $sql = "CREATE DATABASE $name CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        $sql | & docker exec --interactive $container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h127.0.0.1'
        if ($LASTEXITCODE -ne 0) { throw 'Disposable test schema creation failed.' }
    }
    $prefixes = @('CLOUD_TEST','CLOUD_REGISTRATION_TEST','CLOUD_OVERVIEW_TEST','CLOUD_RESUMABLE_TEST')
    for ($index=0; $index -lt 4; $index++) {
        [Environment]::SetEnvironmentVariable("$($prefixes[$index])_MYSQL_URL", "jdbc:mysql://127.0.0.1:$Port/$($names[$index])?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",'Process')
        [Environment]::SetEnvironmentVariable("$($prefixes[$index])_MYSQL_USER", 'root','Process')
        [Environment]::SetEnvironmentVariable("$($prefixes[$index])_MYSQL_PASSWORD", $password,'Process')
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    $env:STUDENT_CLOUD_LIVE_AI_TEST = 'false'
    Push-Location (Join-Path $repo 'backend')
    try {
        & $Maven '-Dtest=CloudWorkflowTest,RegistrationWorkflowTest,GlobalOverviewWorkflowTest,ResumableUploadWorkflowTest,GlobalBusinessMetricsTest' test *> $log
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    Get-Content -LiteralPath $log -Tail 45
} finally {
    foreach ($name in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process') }
    $env:MYSQL_ROOT_PASSWORD = $oldPassword
    $password = $null
    if ($created) {
        # Delete ONLY the uniquely named container created above, after checking its ownership label.
        $label = & docker inspect --format '{{ index .Config.Labels "student-cloud.test-run" }}' $container
        if ($LASTEXITCODE -eq 0 -and $label.Trim() -eq $runId) {
            & docker rm --force --volumes $container | Out-Null
            Write-Output 'Disposable test container and its temporary database removed.'
        } else { Write-Warning 'Container ownership could not be verified; no cleanup attempted.' }
    }
}
exit $exitCode
