param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "../src/main/resources/ai/knowledge")
)

$ErrorActionPreference = "Stop"
$builder = Join-Path $PSScriptRoot "build-site-knowledge.ps1"
& $builder -OutputDirectory $OutputDirectory
