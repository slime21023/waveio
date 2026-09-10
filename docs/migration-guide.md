# Migration guide: WaveIO to Wave

Wave is a clean rebuild. There is no `io.waveio` compatibility layer, adapter, or migration shim.
Consumers must move to the single coordinate `io.wavejava:wave` and module `io.wavejava.wave`.

## Minimal shape

Replace the old callback model with `Wave.app().routes(...)` and the fixed handler contract
`void handle(Request, Response)`. Commit one bounded response with `text`, `bytes`, `json`,
`render`, `problem`, or `empty`.

## Body and transport changes

Aggregated request bodies are single-read and bounded through `ServerLimits`. Flow request/response
streaming is opt-in and must obey explicit demand and byte budgets. Multipart uploads use the
bounded temporary-file manager; callers must close the returned form/upload resources.

## Boundary changes

Only the root package, `api.*`, and `spi.*` are exported. Code importing Netty or `runtime.*` is an
implementation dependency and must be replaced with a public contract or an SPI provider.

The 0.9 RC cleanup removes old names without aliases: use `ClientRequestPool` with
`maximumConcurrentRequests`, `leasedRequests`, and `requestPool(...)`; use `SseResponseInfo`,
`ApplicationResult`, and `RequestDispatcher`. `ResponseBody` and `Response.body()` are no longer
public; applications select a response writer and do not inspect transport payload state. Fixtures
(`RequestFixture`, `EmbeddedApp`, `TestHttpClient`, and `MockUpstream`) are test-source utilities,
not production classes.

Before 1.0, any public contract change must update the architecture decision, API manifest, and
contract tests together. Binary compatibility is not promised until an immutable release baseline
has been published and the compatibility profile is enabled in CI.
