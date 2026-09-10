# Task Plan: Wave 1.0 implementation

## Goal

Implement the approved Wave architecture incrementally, with each roadmap release backed by its specified public contract, bounded-resource behavior, and repeatable tests.

## Current Phase

0.9 — Release candidate foundations (before 1.0 API stability)

## Phases

### 0.1: Usable core

- [x] Establish the single Maven artifact and JPMS boundary.
- [x] Implement the core HTTP API, routing, middleware, embedded testing, and HTTP/1.1 adapter.
- [x] Verify the 0.1 behavior and transport profile.
- **Status:** complete

### 0.2: Execution correctness and transport safety

- [x] Add request context, cancellation, deadline, limits, timeout, and TLS configuration contracts.
- [x] Add pure runtime primitives for invocation and per-connection ordered response admission.
- [x] Move application execution and response preparation fully off Netty EventLoop threads.
- [x] Wire deadline, disconnect, backpressure, shutdown, TLS, and compression into the HTTP/1.1 adapter.
- [x] Add deterministic and socket-level verification for the 0.2 gates.
- **Status:** complete

### 0.3: Web foundation

- [x] Implement parser/renderer negotiation, URL-encoded form, cookie codec, config, service lifecycle, and secure static files.
- [x] Keep multipart upload and Flow streaming out of scope until 0.4.
- [x] Verify traversal, conditional/range, startup rollback, config provenance, and socket integration.
- **Status:** complete

### 0.4: Data flow and client

- [x] Add mutually exclusive Flow request/response streaming with demand and byte budgets.
- [x] Add bounded reusable client, retry/redirect/proxy policies, and deadline/cancellation bridging.
- [x] Add streaming multipart parsing and bounded temporary-file lifecycle.
- [x] Verify Flow ownership/demand, slow consumer, cancellation, client reuse/timeouts/retry safety, upload cleanup.
- **Status:** complete

#### Active work item: server Flow streaming foundation

- [x] Add `api.stream.BodyPublisher` and `Response.stream(Flow.Publisher<ByteBuffer>)` without changing `api.client` or `api.multipart`.
- [x] Implement bounded Flow bridge/subscription/outbound-byte accounting, opt-in HTTP/1.1 inbound streaming, and chunked-response wiring.
- [x] Cover Flow contract, real-socket success/demand/cancellation/budget/shutdown cases plus deterministic slow-consumer write blocking.
- [x] Harden inbound HTTP/1 streaming: one-object manual decoder pull, stale callback suppression, header-time limits/Expect handling, terminal-body tracking release, and HTTP/1 response-order safety.
- [x] Replace the interim JDK client adapter with an owned Netty HTTP/1.1 connection transport so a cancelled client lease is released only after channel teardown is observable.

### 0.5: Long connections

- [x] Define and implement the bounded SSE event/emitter/client contract with heartbeat and disconnect cleanup.
- [x] Define and implement the WebSocket upgrade/session/message/direct-`ws` client contract with close-handshake and limits.
- [x] Add real-socket long-connection fixtures and verify slow consumer, cancellation, limit, shutdown, and PARANOID leak cases.
- **Status:** complete — clean `verify -Ptransport` passed with 237 tests and no failures/errors/skips.

### 0.6: Microservice operations

- [x] Define bounded session identity/store, signed cookie codec, expiry, and rotation contracts.
- [x] Add lifecycle-aware health checks plus liveness/readiness endpoints.
- [x] Add bounded access-event, metrics, tracing, and Prometheus bridge contracts without vendor dependencies.
- [x] Add finite rate-limit and bulkhead policies, then verify success/failure/cancellation/overload behavior.
- **Status:** complete — clean `verify -Ptransport` passed with 268 tests and no failures/errors/skips.

### 0.7: HTTP/2, security, and public address

- [x] Define HTTP/2 server/client capability and strict h2c/TLS/ALPN boundaries.
- [x] Implement the bounded HTTP/2 server/client baseline: ALPN, stream admission, static body partitions, request/response Flow, CONNECT tunnel, and explicit reset.
- [x] Implement public-address/trusted-proxy policy and HTTP/1.1 framing defenses with socket-level evidence.
- [x] Add a bounded, test-only raw HTTP/2 peer plus baseline conformance coverage.
- [x] Add graceful GOAWAY drain, static aggregate partition enforcement, and multiplex fairness/slow-peer proof.
- [x] Add raw RST/empty-DATA frame-abuse and stream/application-overload coverage.
- [x] Prove client GOAWAY handoff both after completion and while an accepted stream drains; prove deadline/reset parent reuse.
- **Status:** complete — clean `verify -Ptransport` passed with 320 tests and no failures/errors/skips.

### 0.8: Extension stability

- [x] Add bounded parser/renderer/session/service SPI contracts, catalog, deterministic priority/conflict validation, and `ServiceLoader` discovery.
- [x] Add an isolated external-provider contract kit and fresh provider-service lifecycle verification.
- [x] Configure a fail-closed Revapi compatibility profile limited to root/`api.*`/`spi.*`, rejecting SNAPSHOT/non-Wave baselines.
- [x] Freeze the pre-release JPMS public-surface manifest with exact unqualified exports, empty `opens`/`provides`, `uses`, and transitive-requires tests; add CI safety checks for missing/mutable/unresolvable baselines.
- [x] Add an isolated old/new Revapi fixture proving a deliberate public-member removal fails.
- [ ] Create an immutable release baseline and enable the fixed-coordinate compatibility gate in CI.
- **Status:** in_progress — source-level boundary, fail-closed profile, and isolated breaking-removal proof are verified; release publication and the real fixed-coordinate CI gate remain.

### 0.9: Release candidate foundations

- [x] Add five named-module consumer examples using only the frozen root/api/spi surface.
- [x] Add CI consumer compilation and bounded smoke mains.
- [x] Add opt-in finite reliability socket smoke and JMH route benchmark with reproducible commands.
- [x] Add migration, operations, and security/dependency reports.
- [x] Add a distribution assembler, finite archive manifest verification, and CI consumer/reliability plumbing.
- [ ] Complete signed/fixed-coordinate release dry-run after an immutable compatibility baseline exists.
- **Status:** in_progress — T1 and T4 complete; T2 has a verified 600-wave PARANOID finite soak, forked JMH baseline, a 120-second local server workload with 30-second NMT sampling, and matching scheduled sampling configured with six-sample/heap/NMT assertions; T3 adds local RC consumer verification, SHA-256 sidecar helpers, and a fail-closed detached-signature/digest verifier, while the signed release dry-run remains gated by 0.8 immutable baseline.

### 1.0: Stable release (explicitly out of current scope)

- [ ] Freeze public contracts only after every 0.7–0.9 gate passes and an immutable compatibility baseline exists.
- **Status:** not entered, per user scope.

## Decisions Made

| Decision | Rationale |
|---|---|
| New `io.wavejava:wave` artifact with no old API compatibility | Explicit architecture decision; old WaveIO evidence does not count. |
| 0.1–0.3 use bounded aggregated bodies only | Keeps streaming/multipart out of the initial transport model. |
| 0.2 response sequencing stays transport-neutral | `ConnectionSequencer` can be deterministic-tested without Netty. |
| Virtual-thread runtime owns application work | The Netty EventLoop must be restricted to transport work and preprepared writes. |
| 0.4 server streaming is a separate work item | Do not modify `api.client` or `api.multipart`, which are owned by parallel work lines. |
| Stream queues must be budgeted | Demand, outbound bytes, and cancellation must be explicit; no implicit unbounded buffering is allowed. |
| Client admission must bind to owned transport lifecycle | JDK `HttpClient` cancellation exposes a logical future but not a reliable socket-teardown completion signal; the architecture's Netty client transport is required for a physical connection/lease bound. |
| 0.5 WebSocket client is an owned direct-`ws` transport | It cannot safely reuse the finite-byte HTTP pool or opaque JDK WebSocket lifecycle; redirect/retry/proxy/`wss` wait for the 0.7 security/client scope. |
| 0.5 raw WebSocket fixture remains test-only | Protocol-invalid frames, fragmentation, FIN/RST, and deterministic held writes require a package-private raw peer; exporting it would turn test control into a production contract. |
| HTTP/2 is TLS/ALPN only | h2c prior knowledge and Upgrade are deliberately unsupported so one authenticated transport path carries all HTTP/2 semantics. |
| HTTP/2 body budgets use static stream partitions | Dividing the connection body budget by admitted streams leaves unused capacity in exchange for a direct proof that active streams cannot exceed the parent cap. |
| HTTP/2 connection receive window cannot be configured below 65,535 bytes | RFC 7540 has no setting to reduce the initial connection window; reject such a value rather than accepting a limit Netty cannot enforce. |
| HTTP/2 graceful shutdown sends GOAWAY before invocation shutdown | Existing accepted streams retain the shared shutdown budget; new streams are rejected without force-closing unrelated work. |
| HTTP/2 route selection and physical-parent accounting are separate | A GOAWAY-draining parent remains in the physical-cap set until its channel closes, even after a replacement becomes the selectable route parent. |
| Pre-release public surface is not a Revapi baseline | Exact unqualified JPMS exports, empty opens/provides, uses/requires are source-tested now; only an immutable published artifact can establish binary compatibility, and mutable coordinates are rejected. |
| SPI discovery is bounded at application assembly | `ServiceLoader` is never invoked on a request path; id/selection-key conflicts fail before the listener binds. |

## Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| Java 25 `ScopedValue.Carrier.call` rejected `Callable` | 1 | Adapt the callable with a compatible lambda/method reference. |
| JDK 25 cannot generate a Netty self-signed certificate | 1 | Use a checked-in test-only PEM fixture for real TLS integration coverage. |
| JDK client cancellation completion can precede socket teardown | 1 | Do not treat its future as a physical lifecycle signal; replace the interim adapter with an owned Netty HTTP/1.1 transport and verify queued admission against real channel closure. |
| PowerShell array slice expression was malformed while inspecting architecture sections | 1 | Bind start/end line values before applying the array range. |
| One inspection wrapper called `JSONstringify` instead of `JSON.stringify` | 1 | Correct the wrapper and repeat the read-only inspection. |
| Initial Revapi configuration used unsupported `failCriticality=breaking` | 1 | Use Revapi 0.15's supported `error` criticality, then validate unresolved baselines fail closed. |
| Full suite exposed a timing-dependent SSE heartbeat test and two stale architecture assumptions | 1 | Replace the real scheduler with a manual fixture; permit the exported `spi` root package; update the H2 test for the protocol minimum 65,535-byte connection window. |
| A raw H2 pseudo-header ordering test unexpectedly reached the handler | 1 | Netty's `DefaultHttp2Headers` canonicalizes pseudo headers before regular headers; retain a real duplicate-pseudo test and leave ordering/frame validation for a lower-level fixture. |
| PowerShell parsed an unquoted `-Dwave.api.baseline=...` as Maven plugin syntax | 1 | Quote the complete `-D...` argument; the historical self-SNAPSHOT smoke only proved wiring and is now intentionally rejected. |
| High-level H2 frame writes did not reproduce the empty-DATA decoder guard | 1 | Add a test-only TLS/ALPN wire peer that serializes the HTTP/2 preface, HPACK headers, and empty DATA frames directly. |
| Closing an H2 parent exception sink replaced a fatal protocol GOAWAY with `NO_ERROR` | 1 | Consume embedded `Http2Exception` after the multiplex handler; the frame codec owns GOAWAY and close sequencing. |
