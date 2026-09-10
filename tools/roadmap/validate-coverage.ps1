param(
    [string]$CsvPath = (Join-Path $PSScriptRoot '..\..\wave\target\site\jacoco\jacoco.csv')
)

$ErrorActionPreference = 'Stop'

$resolvedCsv = Resolve-Path -LiteralPath $CsvPath -ErrorAction SilentlyContinue
if ($null -eq $resolvedCsv) {
    throw "JaCoCo CSV report is missing: $CsvPath. Run './mvnw.cmd -B -ntp verify -Pcoverage' first."
}

$coverageRows = @(Import-Csv -LiteralPath $resolvedCsv)
if ($coverageRows.Count -eq 0) {
    throw "JaCoCo CSV report has no production classes: $resolvedCsv"
}

$failures = [System.Collections.Generic.List[string]]::new()
$groups = [ordered]@{
    'public' = '^io\.wavejava\.wave(?:$|\.(?:api|spi)(?:\.|$))'
    'runtime' = '^io\.wavejava\.wave\.runtime(?:\.|$)'
    'transport' = '^io\.wavejava\.wave\.netty(?:\.|$)'
    'internal' = '^io\.wavejava\.wave\.internal(?:\.|$)'
}

function Read-Counter([object]$row, [string]$name) {
    $value = $row.$name
    [long]$parsed = 0
    if ($null -eq $value -or -not [long]::TryParse([string]$value, [ref]$parsed) -or $parsed -lt 0) {
        throw "JaCoCo counter '$name' is missing or invalid for $($row.PACKAGE).$($row.CLASS)"
    }
    return $parsed
}

function Test-Coverage([string]$name, [object[]]$rows) {
    if ($rows.Count -eq 0) {
        $failures.Add("coverage group '$name' has no production classes")
        return
    }

    $lineCovered = [long](($rows | ForEach-Object { Read-Counter $_ 'LINE_COVERED' } | Measure-Object -Sum).Sum)
    $lineMissed = [long](($rows | ForEach-Object { Read-Counter $_ 'LINE_MISSED' } | Measure-Object -Sum).Sum)
    $branchCovered = [long](($rows | ForEach-Object { Read-Counter $_ 'BRANCH_COVERED' } | Measure-Object -Sum).Sum)
    $branchMissed = [long](($rows | ForEach-Object { Read-Counter $_ 'BRANCH_MISSED' } | Measure-Object -Sum).Sum)
    $lineTotal = $lineCovered + $lineMissed
    $branchTotal = $branchCovered + $branchMissed
    if ($lineTotal -eq 0 -or $branchTotal -eq 0) {
        $failures.Add("coverage group '$name' is missing line or branch counters")
        return
    }

    $lineRatio = $lineCovered / $lineTotal
    $branchRatio = $branchCovered / $branchTotal
    Write-Output ("coverage group={0} classes={1} line={2:P1} branch={3:P1}" -f $name, $rows.Count, $lineRatio, $branchRatio)
    if ($lineRatio -lt 0.90) {
        $failures.Add(("coverage group '{0}' line coverage {1:P1} is below 90.0%" -f $name, $lineRatio))
    }
    if ($branchRatio -lt 0.80) {
        $failures.Add(("coverage group '{0}' branch coverage {1:P1} is below 80.0%" -f $name, $branchRatio))
    }
}

$classified = @{}
foreach ($name in $groups.Keys) {
    $classified[$name] = [System.Collections.Generic.List[object]]::new()
}
foreach ($row in $coverageRows) {
    $matches = @($groups.Keys | Where-Object { $row.PACKAGE -match $groups[$_] })
    if ($matches.Count -ne 1) {
        $failures.Add("unclassified or ambiguous production package: $($row.PACKAGE)")
        continue
    }
    $classified[$matches[0]].Add($row)
}

Test-Coverage 'bundle' $coverageRows
foreach ($name in $groups.Keys) {
    Test-Coverage $name $classified[$name].ToArray()
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        [Console]::Error.WriteLine($failure)
    }
    exit 1
}

Write-Output "layered-coverage-check-ok classes=$($coverageRows.Count)"
