$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$productionRoot = Join-Path $repoRoot 'wave\src\main\java'
$sourceFiles = Get-ChildItem -LiteralPath $productionRoot -Recurse -Filter '*.java' -File
$failures = [System.Collections.Generic.List[string]]::new()

if ($sourceFiles.Count -eq 0) {
    throw "No production Java source found: $productionRoot"
}

$forbiddenNames = [ordered]@{
    'ClientPool' = '\bClientPool\b'
    'ClientPoolRejectedException' = '\bClientPoolRejectedException\b'
    'SseResponse' = '\bSseResponse\b'
    'ApplicationDispatch' = '\bApplicationDispatch\b'
    'ApplicationDispatcher' = '\bApplicationDispatcher\b'
    'CompletionStageAwaiter' = '\bCompletionStageAwaiter\b'
    'ResponseBody' = '\bResponseBody\b'
    'api.testing package' = 'io\.wavejava\.wave\.api\.testing'
}

foreach ($file in $sourceFiles) {
    $text = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($entry in $forbiddenNames.GetEnumerator()) {
        if ($text -match $entry.Value) {
            $failures.Add("$($entry.Key): $($file.FullName)")
        }
    }

    # A public signature may use only exported contracts. Private implementation wiring is
    # intentionally allowed in the three small facades; it is still checked by reflection tests.
    if ($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\api\\') {
        $publicLines = ($text -split '\r?\n') | Where-Object { $_ -match '\bpublic\b' }
        foreach ($line in $publicLines) {
            if (($line -match 'io\.wavejava\.wave\.(?:internal|netty|runtime)\.|io\.netty\.') -or (($line -match '\b(?:Netty[A-Za-z]*Transport|ClientTransport|WebSocketClientTransport|CancellationBridge)\b') -and $file.Name -notin @('WaveClient.java', 'WebSocketClient.java'))) {
                $failures.Add("internal/netty type in public signature: $($file.FullName): $line")
            }
        }
    }

    if (($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\api\\') -and ($file.Name -match '^(?:Netty|ClientTransport|CancellationBridge|WebSocketClientSession|WebSocketClientConfig)')) {
        $failures.Add("transport implementation must stay out of api package: $($file.FullName)")
    }
}

$requiredFiles = @(
    'wave\src\main\java\io\wavejava\wave\api\client\ClientRequestPool.java',
    'wave\src\main\java\io\wavejava\wave\api\client\ClientRequestPoolRejectedException.java',
    'wave\src\main\java\io\wavejava\wave\api\sse\SseResponseInfo.java',
    'wave\src\main\java\io\wavejava\wave\runtime\ApplicationResult.java',
    'wave\src\main\java\io\wavejava\wave\runtime\RequestDispatcher.java',
    'wave\src\main\java\io\wavejava\wave\internal\client\BlockingResultWaiter.java',
    'wave\src\main\java\io\wavejava\wave\internal\http\InternalResponse.java',
    'wave\src\main\java\io\wavejava\wave\internal\http\ResponseData.java',
    'wave\src\main\java\io\wavejava\wave\internal\http\ResponseDataReader.java',
    'wave\src\test\java\io\wavejava\wave\testing\RequestFixture.java',
    'wave\src\test\java\io\wavejava\wave\testing\EmbeddedApp.java',
    'wave\src\test\java\io\wavejava\wave\testing\TestHttpClient.java',
    'wave\src\test\java\io\wavejava\wave\testing\MockUpstream.java'
)
$forbiddenFiles = @(
    'wave\src\main\java\io\wavejava\wave\api\client\ClientPool.java',
    'wave\src\main\java\io\wavejava\wave\api\client\ClientPoolRejectedException.java',
    'wave\src\main\java\io\wavejava\wave\api\sse\SseResponse.java',
    'wave\src\main\java\io\wavejava\wave\runtime\ApplicationDispatch.java',
    'wave\src\main\java\io\wavejava\wave\runtime\ApplicationDispatcher.java',
    'wave\src\main\java\io\wavejava\wave\api\client\CompletionStageAwaiter.java',
    'wave\src\main\java\io\wavejava\wave\api\http\ResponseBody.java'
)
foreach ($relative in $forbiddenFiles) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot $relative) -PathType Leaf) {
        $failures.Add("old file still exists: $relative")
    }
}
foreach ($relative in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relative) -PathType Leaf)) {
        $failures.Add("required file missing: $relative")
    }
}

$moduleInfo = Join-Path $productionRoot 'module-info.java'
$moduleText = Get-Content -LiteralPath $moduleInfo -Raw
if ($moduleText -match 'exports\s+io\.wavejava\.wave\.api\.testing') {
    $failures.Add('api.testing must not be exported')
}
if ($moduleText -match 'requires\s+transitive\s+java\.net\.http') {
    $failures.Add('java.net.http must not be a transitive requirement')
}

$poolText = Get-Content -LiteralPath (Join-Path $productionRoot 'io\wavejava\wave\api\client\ClientRequestPool.java') -Raw
if ($poolText -notmatch '(?s)\* Bounded pool that limits simultaneous client requests; it is not a connection pool\.') {
    $failures.Add('ClientRequestPool first Javadoc line must explain request pool versus connection pool')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "naming-and-boundary-check-ok productionFiles=$($sourceFiles.Count)"
