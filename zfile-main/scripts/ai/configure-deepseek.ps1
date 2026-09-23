$ErrorActionPreference = 'Stop'

$secureKey = Read-Host 'Enter DeepSeek API Key (input is hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)

try {
    $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw 'DeepSeek API Key cannot be empty.'
    }

    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $apiKey, 'User')
    Write-Host 'DEEPSEEK_API_KEY was saved to the current Windows user environment.'
    Write-Host 'Restart the backend service to apply the configuration.'
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Variable apiKey -ErrorAction SilentlyContinue
}
