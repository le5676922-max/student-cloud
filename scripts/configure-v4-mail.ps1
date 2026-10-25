#Requires -Version 7.0
<#
.SYNOPSIS
Configures V4 QQ SMTP using a hidden authorization-code prompt and SSH stdin.
.DESCRIPTION
Requires the existing trusted SSH host key and public-key login. No password
fallback, global setting change, credential argument, or local credential file.
Use -SendTest to send one registration-code email to the sender's own mailbox.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$')]
    [string]$Server,
    [ValidateRange(1, 65535)]
    [int]$Port = 22,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$IdentityFile,
    [switch]$SendTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Set-PSDebug -Off

function Invoke-PrivateSsh {
    param([string]$RemoteCommand, [AllowEmptyString()][string]$InputText = '', [int]$TimeoutMs = 45000)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:sshExecutable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @(
        '-T', '-p', $Port.ToString(), '-i', $IdentityFile,
        '-o', 'StrictHostKeyChecking=yes', '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes', '-o', 'PreferredAuthentications=publickey',
        '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
        '-o', 'NumberOfPasswordPrompts=0', '-o', 'ConnectTimeout=15', '-o', 'LogLevel=ERROR',
        "root@$Server", $RemoteCommand
    )) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $stdout = $stderr = $null
    try {
        if (-not $process.Start()) { throw 'SSH process could not start.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        # Only the pipe carries credential JSON; never add it to ArgumentList.
        $process.StandardInput.Write($InputText)
        $process.StandardInput.Close()
        $InputText = $null
        if (-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw 'SSH operation timed out; no automatic retry was made.'
        }
        return [pscustomobject]@{ Code = $process.ExitCode; Output = $stdout.GetAwaiter().GetResult() }
    } finally {
        $InputText = $null
        # Drain stderr only in memory. Its original text must never be displayed.
        if ($stderr -and $stderr.IsCompleted) { $null = $stderr.GetAwaiter().GetResult() }
        if (-not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        $process.Dispose()
        $start = $stdout = $stderr = $null
    }
}

function New-RemotePythonCommand([string]$Code) {
    # These static bootstrap snippets use double quotes only, never user input.
    if ($Code.Contains("'")) { throw 'Unsafe bootstrap source.' }
    return "python3 -I -c '" + $Code + "'"
}

$secureCode = $plainCode = $payload = $email = $helperSource = $result = $null
$bstr = [IntPtr]::Zero
try {
    $sshCommand = Get-Command ssh.exe -CommandType Application -ErrorAction Stop
    $script:sshExecutable = $sshCommand.Source
    if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw 'The configured SSH identity file is missing.' }
    $helperPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker/v4/configure-mail.py'
    if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) { throw 'The V4 mail helper is missing.' }

    Write-Host 'Verifying the existing trusted SSH connection before requesting mail credentials...'
    $probeCode = @'
import os, pathlib, stat
p = pathlib.Path("/opt/student-cloud-v4")
assert os.geteuid() == 0 and p.resolve(strict=True) == p
s = p.lstat()
assert stat.S_ISDIR(s.st_mode) and s.st_uid == 0 and not s.st_mode & 0o022
s = (p / ".env").lstat()
assert stat.S_ISREG(s.st_mode) and s.st_uid == 0 and stat.S_IMODE(s.st_mode) == 0o600
print("V4_MAIL_SSH_READY")
'@
    $probe = Invoke-PrivateSsh -RemoteCommand (New-RemotePythonCommand $probeCode)
    if ($probe.Code -ne 0 -or $probe.Output.Trim() -cne 'V4_MAIL_SSH_READY') {
        throw 'Trusted public-key SSH verification failed. Stop; no password fallback is attempted.'
    }
    $probe = $null

    # Upload only reviewed public source; this operation precedes credential input.
    $helperSource = [IO.File]::ReadAllText($helperPath, [Text.Encoding]::UTF8)
    $uploadCode = @'
import os, pathlib, stat, sys, tempfile
p = pathlib.Path("/opt/student-cloud-v4")
assert os.geteuid() == 0 and p.resolve(strict=True) == p
s = p.lstat()
assert stat.S_ISDIR(s.st_mode) and s.st_uid == 0 and not s.st_mode & 0o022
source = sys.stdin.buffer.read(65537)
assert 0 < len(source) <= 65536
compile(source, "configure-mail.py", "exec")
fd, temporary = tempfile.mkstemp(prefix=".mail-helper-", dir=p)
try:
    os.fchmod(fd, 0o700)
    with os.fdopen(fd, "wb") as stream:
        stream.write(source)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, p / "configure-mail.py")
    temporary = None
finally:
    if temporary is not None:
        os.unlink(temporary)
print("V4_MAIL_HELPER_READY")
'@
    $upload = Invoke-PrivateSsh -RemoteCommand (New-RemotePythonCommand $uploadCode) -InputText $helperSource
    $helperSource = $null
    if ($upload.Code -ne 0 -or $upload.Output.Trim() -cne 'V4_MAIL_HELPER_READY') { throw 'Mail helper upload failed; no credentials were requested.' }
    $upload = $null

    $email = (Read-Host 'Sender QQ email address (@qq.com)').Trim().ToLowerInvariant()
    if ($email -cnotmatch '^[a-z0-9][a-z0-9_.-]{0,63}@qq\.com$') { throw 'Enter a valid QQ sender address.' }
    $secureCode = Read-Host 'QQ SMTP authorization code (16 letters; hidden input)' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureCode)
    $plainCode = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ($plainCode -cnotmatch '^[A-Za-z]{16}$') { throw 'The QQ SMTP authorization code must contain exactly 16 English letters.' }
    $payload = @{ email = $email; authorization_code = $plainCode; send_test = [bool]$SendTest } | ConvertTo-Json -Compress
    Write-Host 'Validating SMTP over TLS and applying only the V4 mail configuration...'
    $result = Invoke-PrivateSsh -RemoteCommand 'python3 -I /opt/student-cloud-v4/configure-mail.py' -InputText $payload -TimeoutMs 360000
    $plainCode = $payload = $email = $null

    # Display a strict allowlist of status tokens, never arbitrary remote output.
    foreach ($line in ($result.Output -split '\r?\n')) {
        if ($line -cmatch '^V4_MAIL_(SMTP_OK|ENV_OK|BACKEND_OK|WEB_OK|TEST_SKIPPED|DONE)$' -or
            $line -ceq 'V4_MAIL_TEST_OK business_code=0' -or
            $line -cmatch '^V4_MAIL_FAILED stage=(input|preflight|smtp-auth|env-write|compose-check|backend|web-reload|registration-test|internal)( business_code=([0-9]{1,8}|UNKNOWN))?$') {
            Write-Host $line
        }
    }
    if ($result.Code -ne 0 -or $result.Output -cnotmatch '(?m)^V4_MAIL_DONE\r?$') {
        throw 'V4 mail configuration or its optional test stopped. The last safe stage token identifies the failure; do not retry blindly.'
    }
    Write-Host 'V4 mail setup finished. A successful email-code send still requires checking the mailbox and separately testing new-account registration.'
} catch {
    # Do not print $_, ErrorRecord, invocation details, or raw SSH diagnostics.
    Write-Host 'V4 mail setup stopped. No password fallback or automatic retry was attempted.' -ForegroundColor Red
    Write-Host 'If SSH verification failed, resolve the trusted host key/public-key login before rerunning. If ENV_OK was shown, the dedicated V4 mail settings were saved; later failure does not roll them back.'
    exit 1
} finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($null -ne $secureCode) { $secureCode.Dispose() }
    $bstr = [IntPtr]::Zero
    $secureCode = $plainCode = $payload = $email = $helperSource = $result = $null
}
