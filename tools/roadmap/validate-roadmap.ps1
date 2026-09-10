$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$roadmap = Join-Path $repoRoot '_spec\wave-roadmap.md'
if (-not (Test-Path -LiteralPath $roadmap -PathType Leaf)) {
    throw "Roadmap not found: $roadmap"
}

$taskRows = Get-Content -LiteralPath $roadmap |
    Where-Object { $_ -match '^\| 0\.\d+-(?:T|R)\d+ \|' }
if ($taskRows.Count -eq 0) {
    throw 'No versioned roadmap task rows found.'
}

$requiredHeaders = @('元件', '公開契約', '前置依賴', '完成 gate')
$roadmapText = Get-Content -LiteralPath $roadmap -Raw
foreach ($header in $requiredHeaders) {
    if ($roadmapText -notmatch [regex]::Escape($header)) {
        throw "Roadmap is missing required column/header: $header"
    }
}

$failures = [System.Collections.Generic.List[string]]::new()
foreach ($row in $taskRows) {
    $parts = $row.Trim('|').Split('|') | ForEach-Object { $_.Trim() }
    if ($parts.Count -lt 7) {
        $failures.Add("$row`n  expected at least 7 table columns")
        continue
    }
    $taskId = $parts[0]
    foreach ($index in 1..6) {
        if ([string]::IsNullOrWhiteSpace($parts[$index])) {
            $failures.Add("${taskId}: column $($index + 1) is empty")
        }
    }
    $acceptance = $parts[5]
    $acceptanceChecks = @{
        'success' = 'success'
        'application failure' = 'application failure|failure'
        'deadline/cancellation' = 'deadline|cancellation|不適用'
        'limit' = 'limit|cap|budget|不適用'
        'shutdown' = 'shutdown|不適用'
    }
    foreach ($check in $acceptanceChecks.GetEnumerator()) {
        if ($acceptance -notmatch $check.Value) {
            $failures.Add("${taskId}: acceptance cases missing $($check.Key)")
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "roadmap-task-schema-ok tasks=$($taskRows.Count)"
