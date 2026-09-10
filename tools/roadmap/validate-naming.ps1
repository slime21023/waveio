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

    # Public API and SPI never reference implementation packages, including in a signature.
    if ($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\api\\') {
        if ($text -match 'io\.wavejava\.wave\.(?:internal|netty|runtime)\.|io\.netty\.') {
            $failures.Add("api implementation dependency: $($file.FullName)")
        }
    }
    if ($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\spi\\') {
        if ($text -match 'io\.wavejava\.wave\.(?:internal|netty|runtime)\.|io\.netty\.') {
            $failures.Add("spi implementation dependency: $($file.FullName)")
        }
    }

    if (($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\api\\') -and ($file.Name -match '^(?:Netty|ClientTransport|CancellationBridge|WebSocketClientSession|WebSocketClientConfig)')) {
        $failures.Add("transport implementation must stay out of api package: $($file.FullName)")
    }

    if (($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\runtime\\') -and $text -match 'io\.wavejava\.wave\.netty\.|io\.netty\.') {
        $failures.Add("runtime must not depend on Netty: $($file.FullName)")
    }
    if (($file.FullName -match '\\wave\\src\\main\\java\\io\\wavejava\\wave\\netty\\') -and $text -match 'io\.wavejava\.wave\.Wave\b') {
        $failures.Add("Netty must not depend on root Wave: $($file.FullName)")
    }
    if (($file.FullName -notmatch '\\wave\\src\\main\\java\\io\\wavejava\\wave\\Wave\.java$|\\BuiltInWaveFactory\.java$') -and $text -match 'io\.wavejava\.wave\.internal\.bootstrap') {
        $failures.Add("only Wave may reference the built-in factory: $($file.FullName)")
    }
}

$requiredFiles = @(
    'wave\src\main\java\io\wavejava\wave\api\client\ClientRequestPool.java',
    'wave\src\main\java\io\wavejava\wave\api\client\ClientRequestPoolRejectedException.java',
    'wave\src\main\java\io\wavejava\wave\api\sse\SseResponseInfo.java',
    'wave\src\main\java\io\wavejava\wave\api\sse\SseClientOptions.java',
    'wave\src\main\java\io\wavejava\wave\api\application\WaveApp.java',
    'wave\src\main\java\io\wavejava\wave\api\server\WaveServer.java',
    'wave\src\main\java\io\wavejava\wave\api\server\RunningServer.java',
    'wave\src\main\java\io\wavejava\wave\api\http\BodyPublisher.java',
    'wave\src\main\java\io\wavejava\wave\api\http\Http2Config.java',
    'wave\src\main\java\io\wavejava\wave\api\http\PublicAddress.java',
    'wave\src\main\java\io\wavejava\wave\api\http\ResponseUpgrade.java',
    'wave\src\main\java\io\wavejava\wave\runtime\ApplicationResult.java',
    'wave\src\main\java\io\wavejava\wave\runtime\RequestDispatcher.java',
    'wave\src\main\java\io\wavejava\wave\runtime\client\BlockingResultWaiter.java',
    'wave\src\main\java\io\wavejava\wave\runtime\client\ClientTransport.java',
    'wave\src\main\java\io\wavejava\wave\runtime\server\ServerTransport.java',
    'wave\src\main\java\io\wavejava\wave\runtime\server\ServerRuntime.java',
    'wave\src\main\java\io\wavejava\wave\internal\bootstrap\BuiltInWaveFactory.java',
    'wave\src\main\java\io\wavejava\wave\netty\NettyServerTransport.java',
    'wave\src\main\java\io\wavejava\wave\netty\NettyHttp1ClientTransport.java',
    'wave\src\main\java\io\wavejava\wave\netty\NettySseClient.java',
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
    'wave\src\main\java\io\wavejava\wave\api\http\ResponseBody.java',
    'wave\src\main\java\io\wavejava\wave\api\stream\BodyPublisher.java',
    'wave\src\main\java\io\wavejava\wave\netty\Http1Server.java',
    'wave\src\main\java\io\wavejava\wave\netty\NettyClientTransport.java',
    'wave\src\main\java\io\wavejava\wave\netty\WebSocketClientTransport.java',
    'wave\src\main\java\io\wavejava\wave\netty\WebSocketClientConfig.java'
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
