param(
    [string]$UserSource = (Join-Path $PSScriptRoot "rag/user-site-knowledge.json"),
    [string]$AdminSource = (Join-Path $PSScriptRoot "rag/admin-site-knowledge.json"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "../src/main/resources/ai/knowledge")
)

$ErrorActionPreference = "Stop"

function Read-KnowledgeDocuments {
    param(
        [string]$Path,
        [string]$Audience
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Knowledge source not found: $Path"
    }

    $parsedDocuments = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    $documents = @()
    foreach ($parsedDocument in $parsedDocuments) {
        $documents += $parsedDocument
    }
    if ($documents.Count -eq 0) {
        throw "$Audience knowledge source is empty."
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
        if ($document.audience -ne $Audience) {
            throw "Knowledge document '$($document.id)' must use audience '$Audience'."
        }
        if (@($document.tags).Count -eq 0) {
            throw "Knowledge document '$($document.id)' must contain at least one tag."
        }
        if ($ids.ContainsKey($document.id)) {
            throw "Duplicate knowledge document id: $($document.id)"
        }
        $ids[$document.id] = $true
    }
    return $documents
}

function Write-KnowledgeDocuments {
    param(
        [array]$Documents,
        [string]$Path
    )

    $json = ConvertTo-Json -InputObject $Documents -Depth 8
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($Path),
        $json,
        [System.Text.UTF8Encoding]::new($false)
    )
}

$userDocuments = @(Read-KnowledgeDocuments -Path $UserSource -Audience "user")
$adminDocuments = @(Read-KnowledgeDocuments -Path $AdminSource -Audience "admin")

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$userOutput = Join-Path $OutputDirectory "user-knowledge.json"
$adminOutput = Join-Path $OutputDirectory "admin-knowledge.json"

Write-KnowledgeDocuments -Documents $userDocuments -Path $userOutput
Write-KnowledgeDocuments -Documents $adminDocuments -Path $adminOutput

Write-Host "Generated $($userDocuments.Count) user website knowledge documents: $userOutput"
Write-Host "Generated $($adminDocuments.Count) admin website knowledge documents: $adminOutput"
