param(
    [string]$Source = (Join-Path $PSScriptRoot "rag/user-site-knowledge.json"),
    [string]$Output = (Join-Path $PSScriptRoot "../src/main/resources/ai/knowledge/user-knowledge.json")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Source)) {
    throw "User feature knowledge source not found: $Source"
}

$parsedDocuments = Get-Content -LiteralPath $Source -Raw -Encoding UTF8 | ConvertFrom-Json
$documents = @()
foreach ($parsedDocument in $parsedDocuments) {
    $documents += $parsedDocument
}
if ($documents.Count -eq 0) {
    throw "User feature knowledge source is empty."
}

$requiredFields = @("id", "title", "tags", "source", "audience", "content")
$ids = @{}

foreach ($document in $documents) {
    foreach ($field in $requiredFields) {
        $value = $document.$field
        if ($null -eq $value -or ($value -is [string] -and [string]::IsNullOrWhiteSpace($value))) {
            throw "Knowledge document '$($document.id)' is missing field '$field'."
        }
    }

    if ($document.audience -ne "user") {
        throw "Knowledge document '$($document.id)' must use audience 'user'."
    }
    if (@($document.tags).Count -eq 0) {
        throw "Knowledge document '$($document.id)' must contain at least one tag."
    }
    if ($ids.ContainsKey($document.id)) {
        throw "Duplicate knowledge document id: $($document.id)"
    }
    $ids[$document.id] = $true
}

$outputDirectory = Split-Path -Parent $Output
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$json = ConvertTo-Json -InputObject $documents -Depth 8
[System.IO.File]::WriteAllText(
    [System.IO.Path]::GetFullPath($Output),
    $json,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Generated $($documents.Count) user feature knowledge documents: $Output"
