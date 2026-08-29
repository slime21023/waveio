# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0-RC1] - 2026-08-29

### Added
- **AOT-Native HTTP/1.1 Core Runtime**:
  - Full RFC 7230 and RFC 7231 compliance with zero classpath scanning and zero reflection requirements.
  - JPMS modularity with complete encapsulation of Netty transport internals.
- **Dual Execution Engine**:
  - Non-blocking event-loop handlers (`get`, `post`, `put`, `delete`, etc.).
  - Virtual-thread blocking handlers (`blockingGet`, `blockingPost`, etc.) for synchronous I/O.
  - Asynchronous stage handlers (`asyncGet`, `asyncPost`, etc.) returning `CompletionStage<HttpResponse>`.
  - Inbound streaming handlers (`streamingPost`, `streamingPut`, `streamingPatch`) powered by `Flow.Publisher<ByteBuffer>`.
- **Protocol Semantics & Pipelining Correctness**:
  - Strict head-of-line response ordering across asynchronous and virtual-thread handlers via `ExchangeQueue`.
  - Automatic `HEAD` routing fallback to `GET` handlers with body omission.
  - Safe `Expect: 100-continue` interim response scheduling bypassing codec state machines.
- **Overload Defense & Timeouts**:
  - Global socket admission control (`maxConnections`).
  - Per-connection bounded pipelining (`maxPendingRequestsPerConnection`) with `AutoReadGate`.
  - Four-tier timeout architecture (`handlerTimeout` with virtual thread interrupt, `idleTimeout`, `readTimeout`, `writeTimeout`).
- **Security & Hardening**:
  - Strict UTF-8 request target parsing rejecting path traversal (`..`), encoded separators (`%2F`), and control characters.
  - Hardened static file serving verified with `toRealPath()` against Windows prefix escapes and symlinks.
  - Safe Cookie parsing with character validation and `SameSite` enforcement.
  - Native TLS validation for PKCS#8 keys and X.509 certificate chains.
- **Observability & Extensibility**:
  - Unified `RequestObserver` SPI tracking templates, route metadata, elapsed duration, and terminal outcomes.
  - Pluggable `BodyCodec` SPI for generic object serialization without hardcoded JSON dependencies.
- **Multi-Module Architecture**:
  - Restructured project into `waveio-parent` and `waveio-core` for seamless future submodule expansion.
