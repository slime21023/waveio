# Progress Log

## Session: 2026-09-08

### 0.1 baseline and 0.2 implementation

- **Status:** in_progress
- Actions taken:
  - Established the 0.1 artifact, module boundary, core API, router, middleware pipeline, HTTP/1.1 adapter, and embedded/socket tests.
  - Added 0.2 public context/configuration contracts and runtime primitives.
  - Began wiring the HTTP/1.1 adapter for TLS, limits, timeouts, compression, and virtual-thread invocation.
  - Reworked HTTP/1.1 dispatch so virtual threads run both application handlers and response serialization; the EventLoop owns only decoding, ordering, write submission, and read backpressure.
  - Added per-connection response sequencing plus count/byte accounting, real TLS fixture coverage, and socket-level pipelining coverage.
  - Completed 0.2 with shared-budget shutdown coordination, active-connection ownership, compression/write adapters, slow-peer coverage, and deterministic runtime test support.
- Files created/modified:
  - `_spec/wave-architecture.md`
  - `_spec/wave-roadmap.md`
  - `wave/src/main/java/io/wavejava/wave/**`
  - `wave/src/test/java/io/wavejava/wave/**`

## Test Results

| Test | Input | Expected | Actual | Status |
|---|---|---|---|---|
| 0.1 Maven verification | `mvn verify` | Core/JPMS/route/socket tests pass | Passed before 0.2 concurrent edits | pass |
| 0.1 transport profile | `mvn verify -Ptransport` | PARANOID leak-detection suite passes | Passed before 0.2 concurrent edits | pass |
| 0.2 runtime suite | Invocation runtime tests | Virtual-thread/deadline/cancel/shutdown behavior | Passed after ScopedValue adapter repair | pass |
| 0.2 core verification | `./mvnw.cmd -B -ntp -pl wave verify` | JPMS, ArchUnit, unit, TLS and socket integration tests | 67 tests passed | pass |
| 0.2 transport profile | `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` | Same suite with PARANOID Netty leak detection | 67 tests passed | pass |
| 0.3 transport profile | `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` | JPMS, ArchUnit, web-foundation contract tests, real sockets, PARANOID leak detection | 106 tests passed | pass |
| 0.4 server Flow verification | `./mvnw.cmd -B -ntp -pl wave verify` | Public Flow contracts, aggregate compatibility, real-socket request/response streaming, unit and architecture gates | 149 tests passed | pass |
| 0.4 server Flow transport profile | `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` | Same suite with PARANOID ByteBuf leak detection | 149 tests passed | pass |
| Current full transport regression | `./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` | Compile, JPMS, ArchUnit, unit/contract/deterministic/real-socket suites with PARANOID ByteBuf leak detection | 320 tests; 0 failures/errors/skips | pass |

## Session: 2026-09-09

### 0.3 web foundation

- **Status:** complete
- Actions taken:
  - Added public render/parser contracts, deterministic Accept negotiation, bounded URL-encoded form parsing, and single-read `Body.form()` / `Response.render(Rendered)` integration.
  - Added cookie codec coverage and real HTTP cookie round trip.
  - Added secure static files with canonical-root containment, selected-byte cap, validators, and one-range semantics.
  - Added immutable config/provenance/binding, registry, service graph lifecycle, plus pre-bind startup and bounded reverse shutdown integration.
  - Verified every 0.3 transport-facing gate using real loopback sockets while retaining 0.4 Flow/multipart exclusion.
- Evidence:
  - `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` passed with 106 tests.
  - `_spec/wave-roadmap.md` records completed 0.3 task contracts, budgets, dependencies, acceptance cases, and evidence.

## Error Log

| Timestamp | Error | Attempt | Resolution |
|---|---|---:|---|
| 2026-09-08 | `ScopedValue.Carrier.call` type mismatch | 1 | Use `action::call` to adapt the callable. |
| 2026-09-08 | Netty self-signed certificate unsupported on Java 25 | 1 | Replace generated certificate with test PEM fixture. |
| 2026-09-08 | PowerShell parsed unquoted comma/property Maven arguments | 1 | Quote each `-D...` argument or run a single targeted module command. |
| 2026-09-09 | Cookie codec test treated `Long.MAX_VALUE` seconds as an invalid Java duration | 1 | Remove the invalid expectation; `Duration` legally represents that finite value. |
| 2026-09-09 | First planning-file patch raced with another agent's 0.3 completion update | 1 | Re-read the shared plan and apply only additive 0.4 server-streaming entries. |
| 2026-09-09 | Clean compile blocked by concurrent multipart work: `MultipartParser` calls missing `TemporaryFileManager.create(...)` | 1 | Do not edit the separately owned `api.multipart` line; retry after that work line completes. |

| 2026-09-09 | Initial source lookup used the wrong package for `FlowBridgeSlowConsumerTest` | 1 | Located it with `rg --files`; it belongs to `io.wavejava.wave.netty`. |
| 2026-09-09 | A multi-hunk progress patch no longer matched after concurrent plan updates | 1 | Re-read exact local context and apply narrower additive patches. |
| 2026-09-09 | JDK client cancellation future can resolve before the TCP exchange is physically quiescent | 1 | Keep 0.4 open; replace the interim adapter with owned Netty channel lifecycle and a real queued-admission cancellation test. |
| 2026-09-09 | A preparatory 0.5 design subtask could not start because completed review-child capacity was still reserved | 1 | Do not retry while the 0.4 client replacement and review work occupy the team limit. |
| 2026-09-09 | Concurrent WebSocket source temporarily failed compilation in `WebSocketInboundPublisher` because a void statement was used as a switch expression lambda result | 1 | Replaced it with a statement-switch block and added explicit `Runnable` scheduling casts; fresh compile passed before resuming SSE tests. |
| 2026-09-09 | Concurrent `SseClient` reconnect callback captured a loop variable that Java did not treat as effectively final | 1 | Capture a stable reconnect-attempt value before the listener lambda; shared compile passed. |
| 2026-09-09 | In-progress WebSocket endpoint invocation inferred `RequestInvocation<Object>` where the session lifecycle expected `RequestInvocation<Void>` | 1 | Make the endpoint task's `Void` result explicit, then re-run shared compilation before resuming cross-feature tests. |
| 2026-09-09 | Concurrent focused test execution was interrupted by another work line cleaning the shared Maven `target/` directory | 1 | Do not run `clean` while shared feature lines are testing; reserve clean verification for the integration gate. |
| 2026-09-09 | WebSocket inbound publisher extended its listener interface without updating a test listener implementation | 1 | Add `onTerminalDeliveryRejected(Throwable)` to the test fixture before resuming shared test compilation. |
| 2026-09-09 | WebSocket disconnect test closed the transport and signalled cancellation but did not interrupt a blocked endpoint virtual thread | 1 | Keep the gate open; bind token cancellation explicitly to the endpoint `RequestInvocation` and prove interruption with deterministic real-socket disconnect coverage. |
| 2026-09-09 | Upgraded WebSocket raw Ping and peer FIN were not initially observed by the session handler, despite the HTTP codec replacement being structurally valid | 1 | Keep 0.5-T2 open; add explicit half-close handling/read re-arm verification and separately prove raw Ping/Pong, FIN, and true RST lifecycle behavior before accepting the cancellation gate. |

## Session: 2026-09-09

### 0.4 server Flow streaming foundation

- **Status:** in_progress
- Scope: `api.stream.BodyPublisher`, `Response.stream(Flow.Publisher<ByteBuffer>)`, bounded Flow bridge/controller/outbound budget, HTTP/1.1 response streaming, and Flow contract/socket tests.
- Exclusions: `api.client` and `api.multipart` are owned by parallel work lines.
- Completed contract foundation: added public single-use `BodyPublisher`, mutually exclusive `Request.body()`/streaming body accessors, `Response.stream(...)` and `ResponseBody.STREAM`, plus a configurable per-connection outbound stream budget.
- Completed transport-neutral primitives: added `OutboundByteBudget` and one-item `FlowSubscriptionController`; Netty wiring and inbound HTTP/1 request streaming remain in progress.
- Completed HTTP/1.1 wiring: `RequestBodyMode.STREAMING` omits `HttpObjectAggregator`, bridges copied HTTP content under manual Flow demand, and keeps aggregate/stream body APIs mutually exclusive.
- Completed response streaming: header-first chunked writes retain the HTTP/1 sequence slot until the terminal chunk; one-item demand, byte budget, zero-length rejection, disconnect, and shutdown cancellation are wired into `FlowBridge`.
- Current targeted evidence: `FlowStreamingIntegrationTest` covers real-socket response success/pipeline order, HEAD, zero-byte rejection, byte limit, response disconnect/shutdown, request chunk split/demand, request limit (413), unconsumed-body close behavior, and request disconnect; `FlowBridgeSlowConsumerTest` deterministically holds a Netty write to prove no extra demand.
- Response publisher subscription runs on the invocation runtime's virtual threads; Netty retains only event-loop demand, write, and HTTP/1 ordering work.

### 0.4 inbound-streaming hardening

- **Status:** complete for the server Flow work item.
- Added a demand-driven split HTTP/1 codec so multiple chunked body items coalesced in one socket write remain in Netty's bounded decoder cumulation until the subscriber requests each next item.
- Reworked `InboundFlowBridge` callback hand-off: `onSubscribe` never holds the bridge lock; stale queued `onNext`/`onComplete` work is invalidated by terminal generations; shutdown cleanup uses the transport-cleanup executor.
- Added terminal publisher tracking that releases connection maps after handler/callback completion. An empty unclaimed terminal body is safe for keep-alive; a pending or executing terminal callback forces the response connection-final and receives cancellation.
- Added header-time Content-Length rejection, suppressed `100 Continue` for that rejection, and protected raw 413 handling so it cannot overtake active, queued, or merely earlier HTTP/1 invocations.
- Evidence:
  - `./mvnw.cmd -B -ntp -pl wave "-Dtest=InboundFlowBridgeTest,FlowStreamingIntegrationTest" test` — 21 tests passed.
  - `./mvnw.cmd -B -ntp -pl wave test` — 166 tests passed.
  - `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` — 166 tests passed with PARANOID Netty leak detection.

### 0.4 final transport-gate additions

- **Status:** complete.
- Replaced the interim JDK adapter with a package-private `ClientTransport` / owned Netty HTTP/1.1 transport: one owned EventLoop, route-keyed bounded LRU idle cache, direct TLS/SNI/hostname verification, and physical completion only after idle return or channel close. Client policy continuations leave the EventLoop for a virtual-thread orchestration executor.
- Added a real loopback response-stream deadline test: a committed but non-completing publisher is cancelled at the request deadline and its HTTP/1.1 connection closes.
- Added a real loopback slow-peer test with a 16 MiB first chunk and constrained receive window. It proves a blocked TCP write holds Flow demand at one item, then disconnect cleanup cancels the source.
- Evidence:
  - `./mvnw.cmd -B -ntp -pl wave -am -Ptransport "-Dtest=FlowStreamingIntegrationTest,FlowBridgeSlowConsumerTest" test` — 19 tests passed (18 socket Flow tests plus deterministic slow-consumer test).
  - `./mvnw.cmd -B -ntp -pl wave -am -Ptransport "-Dtest=FlowBridgeSlowConsumerTest" test` — 2 tests passed after rejecting an invalid completion-before-subscription publisher.
  - `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` — 174 tests passed with PARANOID Netty leak detection.
  - Independent Flow and client transport audits both reported no remaining 0.4 blocker.
- Deliberate client scope: finite byte HTTP/1.1 request/response, direct TLS, and ordinary HTTP proxy absolute-form are complete. HTTPS-via-proxy CONNECT, HTTP/2 client, and client Flow API remain 0.7 work; direct TLS has implementation coverage but no dedicated client TLS integration fixture yet.

### 0.5 long-connection kickoff

- **Status:** superseded by the completed 0.5 release gate below.
- Next bounded work items are SSE emitter/client and WebSocket upgrade/session/client, each with per-subscriber/session outbound budgets, heartbeat/close lifecycle, and real-socket slow-client/disconnect/shutdown verification.

### 0.5-T2 WebSocket server lifecycle hardening

- **Status:** completed with the direct-`ws` client and fixtures; final evidence is recorded below.
- The HTTP-to-WebSocket handoff now installs a dormant session frame owner before the `101` write completes, arms reads before the peer can observe the upgrade, and re-arms once the settled WebSocket pipeline owns the channel. This closes the first-frame race without a timer or an application-data workaround.
- `WebSocketLimits` now requires the outbound budget to include a maximum frame plus its finite wire-accounting overhead, so an otherwise legal maximum-size frame is not rejected by a hidden `+14` estimate.
- Real-socket coverage separates a WebSocket protocol close, TCP FIN, and true TCP RST (`SO_LINGER=0`). It proves raw first Ping/Pong, session cancellation, and virtual-thread interruption for FIN and RST; it also verifies an unmasked client data frame receives close code `1002`.
- Evidence: `./mvnw.cmd -q -B -ntp -pl wave -am "-Dtest=WebSocketContractTest,WebSocketSessionHandlerTest,WebSocketInboundPublisherTest,WebSocketIntegrationTest,WebSocketUpgradeValidationIntegrationTest" test` passed after the handoff safety repair.

### 0.5-T1 SSE event stream

- **Status:** complete; `TestSseClient` was subsequently completed as the 0.5-T3 fixture.
- Added `api.sse.SseEvent`, a single-subscriber bounded `SseEmitter`, and a direct HTTP/1.1 `SseClient` with pre-retention response-head limits, bounded connection admission/reconnect, absolute response-head timeout, idle timeout, chunked/close-delimited framing, UTF-8 BOM handling, and persisted resume state.
- The client deliberately has no public JDK/Netty type. Its direct HTTP-only transport is a narrow 0.5 boundary; TLS, proxy tunnelling, and HTTP/2 client support remain 0.7 work.
- Evidence:
  - `./mvnw.cmd -B -ntp -pl wave -Dtest=SseEventTest,SseEmitterTest,SseIntegrationTest,SseClientIntegrationTest test` — 22 tests passed.
  - The same focused suite with `-Ptransport` passed with PARANOID ByteBuf leak detection.
  - Independent SSE audit: clear; it specifically verified cancellation-before-subscribe, bounded raw response head parsing, slowloris total deadline, idle timeout, chunked/BOM parsing, resume state after RST, and finite reconnect/connection admission.

### 0.5-T2 direct WebSocket client and wire-boundary verification

- **Status:** complete; final release evidence is recorded below.
- Added a public, direct-`ws`-only `WebSocketClient`, immutable `WebSocketClientRequest`, and `WebSocketConnection`. The owned Netty transport has a finite no-queue admission limit that remains occupied through the physical `closeFuture`; connect/handshake/idle timeouts, response-head count/byte limits, cancellation, heartbeat, close handshake, and client masking are explicit.
- All client application callbacks are dispatched on virtual threads; Netty's EventLoop retains the wire protocol, demand, and write lifecycle only. Public API signatures expose neither Netty nor JDK transport types.
- Added test-only raw `TestWebSocketUpstream` and extended `TestWebSocketClient`, then replaced scattered raw frame code with finite handshake/frame operations. `WebSocketTransportFixture` gives deterministic held-write coverage without timing sleeps.
- Added real-socket verification for direct subprotocol negotiation, client data/control masking, text/ping/pong/close, non-upgrade failure, admission release only after physical close, establishment deadline, cancellation, bounded response headers, protocol-invalid masked peer data, and a zero-demand subscriber retaining one message then closing `1009` on overflow.
- Evidence: `./mvnw.cmd -q -B -ntp -pl wave -am -Ptransport "-Dtest=WebSocketContractTest,WebSocketClientContractTest,WebSocketClientApiBoundaryTest,WebSocketClientIntegrationTest,WebSocketWireIntegrationTest,WebSocketSessionHandlerTest,WebSocketInboundPublisherTest,WebSocketIntegrationTest,WebSocketUpgradeValidationIntegrationTest" test` passed with PARANOID leak detection.

### 0.5 final long-connection gate

- **Status:** complete.
- Added package-private test-only `TestSseClient` with finite HTTP response-head/line/event budgets and synchronous chunked/fixed/close-delimited parsing. The SSE wire tests now exercise heartbeat, event/id/retry/data, clean chunked completion, TCP FIN, TCP RST, and server shutdown cleanup without a hidden background reader.
- The final combined SSE/WebSocket focused transport suite passed, followed by a clean full project transport verification.
- Evidence: `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` — 237 tests passed, 0 failures/errors/skips, including JPMS, ArchUnit, real sockets, deterministic fixtures, and PARANOID ByteBuf leak detection.

### 0.6 microservice operations

- **Status:** complete.
- Added bounded, server-side sessions: 256-bit opaque identifiers, HMAC-SHA-256 signed cookies, finite attribute/lifetime policy, atomic ID rotation, explicit request scope commit before response commitment, and a finite in-memory store without a sweeper thread.
- Added lifecycle-aware health registry/endpoints. Readiness is admitted through a finite semaphore and safe-DOWNs on a timeout, interruption, exception, capacity exhaustion, or a null health result; liveness remains separate from dependency work.
- Added completion-only observability with application and transport outcome axes. Events are delivered via a dedicated finite queue/worker, never the EventLoop; full queues drop newest events, bridge failures are isolated, and stream bytes are accumulated only after each successful Netty write acknowledgement.
- Added no-queue resilience middleware: finite token-bucket rate limiting with `429`/`Retry-After`, plus a fair semaphore bulkhead that holds admission through middleware reverse unwind.
- Evidence: `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` — 268 tests passed, 0 failures/errors/skips, including JPMS, ArchUnit, deterministic fixtures, real sockets, shutdown/disconnect cases, and PARANOID ByteBuf leak detection.

### 0.7 HTTP/2, forwarding, and framing final gate

- **Status:** complete.
- Added TLS/ALPN-only HTTP/2 negotiation (`PREFER` and `REQUIRE`), bounded per-parent stream admission, HTTP/2 request/response Flow handling, strict h2c exclusion, and `Http2Config` validation. The HTTP/2 connection receive window is explicitly rejected below RFC 7540's fixed 65,535-byte default rather than silently accepting an unenforceable setting.
- Added conservative static connection-to-stream byte partitions on both server and owned HTTP/2 client paths. This bounds aggregate/Flow body retention and finite byte client request/response aggregation even when all negotiated streams are active.
- Added HTTP/2 direct client reuse/multiplexing, TLS origin verification, HTTPS-via-HTTP-proxy CONNECT tunnel support, explicit `RST_STREAM(CANCEL)` cancellation, and parent reuse verification.
- Added `PublicAddress`/`ForwardedHeaderPolicy`, strict trusted-CIDR behavior, HTTP/1.1 request-framing defenses, and test-scope raw `TestHttp2Peer` conformance coverage for forbidden connection headers and HTTP/2 forwarded-address behavior.
- Added graceful server GOAWAY drain: listener shutdown stops HTTP/1.x peers, sends `GOAWAY(NO_ERROR)` to negotiated H2 parents, prevents new admission, then lets accepted streams consume the shared shutdown budget. Client parents retire after GOAWAY and never open a new stream on that connection.
- Added direct H2 coverage for application `500`, deadline `504`, static aggregate-body partition rejection, invalid `TE`/header-list rejection, and a raw slow-reader stream proving a fast independent stream completes.
- Added raw duplicate pseudo-header and conflicting `content-length` cases; both are rejected before application dispatch. A pseudo-header-order test was deliberately not retained because Netty's high-level header object normalizes that order before encoding.
- Added direct protocol-abuse evidence: a raw RST flood and a direct-wire sequence of nine empty DATA frames both receive `GOAWAY(ENHANCE_YOUR_CALM)`, close the parent, and never dispatch the application. A protocol-exception sink after the H2 multiplex handler prevents already-handled codec errors from leaking to Netty's pipeline tail or being overwritten by `NO_ERROR`.
- Added server overload evidence for the advertised concurrent stream setting and an exhausted shared invocation budget (`503` without handler dispatch).
- Added client GOAWAY evidence for both an already completed stream and an accepted stream that remains draining while the next exchange switches to a second parent. Added deadline-driven RST/reuse and held-peer/fast-stream multiplex tests. The client now tracks selectable route parents separately from all live parents so a draining parent continues to consume the physical-cap budget through `closeFuture`.
- Focused evidence passed:
  - `./mvnw.cmd -q -B -ntp -pl wave -am "-Dtest=Http2ServerIntegrationTest,Http2ProtocolConformanceIntegrationTest" test`
  - `./mvnw.cmd -q -B -ntp -pl wave -am "-Dtest=Http2WaveClientIntegrationTest" test`
- Full evidence: `./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` passed with 320 tests, 0 failures/errors/skips, including JPMS, ArchUnit, real sockets, and PARANOID ByteBuf leak detection.
- 0.7 release gate closed; the remaining active work is the immutable compatibility baseline in 0.8.

### 0.8 SPI baseline

- **Status:** in_progress.
- Added exported `spi.*` provider contracts for parser, renderer, session store, and lifecycle services; bounded `SpiCatalog`/`SpiProviders` discovery; deterministic priority order; fail-fast duplicate/conflict validation; and a fresh provider-service lifecycle per server run.
- Added `spi.testing.ProviderContract` and an isolated dynamically compiled external provider test, proving ServiceLoader discovery without exposing implementation packages.
- Focused evidence: `./mvnw.cmd -q -B -ntp -pl wave -am "-Dtest=SpiProvidersTest,SpiCatalogIntegrationTest" test` passed.
- Added `_spec/wave-api-compatibility.md` plus an exact `ModuleDescriptorTest` manifest for unqualified exports, empty `opens`/`provides`, service `uses`, and transitive `requires`. This is a source-level pre-release boundary, not an artifact baseline.
- Strengthened the opt-in `compatibility` Maven profile: it rejects missing, any SNAPSHOT/`LATEST`/`RELEASE` version token, and non-Wave baseline coordinates at validate; an immutable-looking but unresolved coordinate fails Revapi at verify. CI now has a safety job proving those fail-closed cases.
- Verification: missing, SNAPSHOT, bare/version-token `LATEST`/`RELEASE`, non-Wave, non-SemVer, and unresolvable-baseline invocations all failed in their intended phase; `./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` passed with 320 tests, 0 failures/errors/skips after the compatibility-boundary changes.
- Added `tools/revapi-break-proof`, an old/new two-module fixture whose expected-failure `clean install` reports `java.method.removed` and exits non-zero. This proves the configured binary-breaking diagnostic independently of any Wave release artifact.
- The actual immutable baseline and fixed-coordinate compatibility CI gate remain open; the isolated breaking-change proof is complete. No local JAR or historical WaveIO release is accepted as evidence.

## 5-Question Reboot Check

| Question | Answer |
|---|---|
| Where am I? | 0.7 HTTP/2/security is complete; 0.8 SPI compatibility is in progress. |
| Where am I going? | Establish the immutable compatibility baseline and CI gate, then execute the 0.9 release evidence. |
| What's the goal? | Implement the approved Wave 1.0 roadmap with evidence-backed gates. |
| What have I learned? | See `findings.md`. |
| What have I done? | See the session log above. |

## Session: 2026-09-09

### 0.9 release-candidate foundations

- **Status:** in_progress; work intentionally stops before 1.0 API stability/freeze.
- Added five ordinary named-module consumer examples under `examples/`: `hello-api`,
  `forms-and-files`, `streaming-api`, `websocket-chat`, and `gateway-api`. They use only the
  root/`api.*` public surface and are built against the published-style local Wave artifact.
- Added the consumer verification CI job, bounded smoke mains, and commands in
  `tools/consumer-verification/README.md`.
- Added an opt-in finite real-socket reliability smoke (`128` concurrent HTTP/1.1 requests plus
  application-failure and shutdown assertions) and an executable JMH route-lookup benchmark under
  `tools/wave-benchmarks/`. Added bounded memory-observation instructions without a daemon or
  unbounded collector.
- Added `docs/migration-guide.md`, `docs/operations-guide.md`, and
  `docs/security-dependency-report.md` with commands matching the Maven profiles.
- Evidence:
  - `./mvnw.cmd -q -B -ntp -f examples/pom.xml package -DskipTests` — five consumers compiled.
  - All five `exec:java` smoke mains completed locally (hello, forms/files, streaming, WebSocket,
    gateway).
  - `./mvnw.cmd -q -B -ntp -pl wave -am verify -Preliability` — reliability smoke passed.
  - `./mvnw.cmd -q -B -ntp -f tools/wave-benchmarks/pom.xml clean package` — JMH compiled; a
    one-warmup/one-measurement run completed on JDK 25 at about 2.06M route lookups/s.
- Remaining by design: 0.8 immutable release artifact/fixed-coordinate Revapi gate and 0.9
  distribution/release dry-run require an owner-selected, externally published immutable version;
  no 1.0 API freeze, release tag, signing, or distribution was created.

### Final verification adjustment

- Reliability smoke is tagged `reliability` and excluded from the default Surefire group; the
  explicit `-Preliability` profile clears that exclusion. This keeps the ordinary transport gate
  at 320 tests with zero skips while the reliability profile adds the 128-request smoke.
- Distribution CI now assembles `tools/distribution/target/wave-0.9.0-rc-distribution.zip` and checks
  required jar/docs/spec entries with `jar tf`; the PowerShell verifier rejects missing entries and
  private-key/build-product paths.
- The Wave jar and RC archive were each built twice from the same source inputs and produced equal
  SHA-256 values (`wave` `BAB18ABF920D348066646151437BD2EEFA21B5231BBC88661D7DFC1D69F35065`,
  archive `CB55F05FA7A03F90A7E08D813C28E9A1112C75133491BEA7C7C8E892DF850C88`).
- Final local command chain completed: transport `320` tests with zero failures/errors/skips;
  reliability profile `321` tests with zero failures/errors/skips; five consumer modules,
  benchmark package, and 15-entry archive verification all passed.
- Follow-up `ModuleDescriptorTest` passed, and a current `-Pcompatibility` invocation without a
  baseline still fails closed with the required diagnostic.
- The first metadata-capturing forked JMH runner completed successfully after replacing the
  misleading Maven `exec:java -f 1` launcher; `baseline-metadata.txt` records commit/worktree,
  JDK 25.0.4, Windows, 20 CPUs, and 1/1/1 settings, with a real score of about 2.89M ops/s.
- Reliability smoke now accepts the bounded `wave.reliability.waves` property (1–600); ordinary
  PR runs use one wave while scheduled CI runs 600 waves with the transport PARANOID profile,
  preserving a finite request set per wave.

## Session: 2026-09-10

### 0.9-T2 finite reliability soak and benchmark recheck

- Re-ran the scheduled finite soak with `-Dwave.reliability.waves=30`.
- Result: exit code `0`; 81 Surefire XML reports, 321 tests, 0 failures, 0 errors, 0 skipped.
- The test remains bounded (30 waves × 128 requests) and asserts application failures, successful
  responses, and server shutdown; this is RC evidence, not an unbounded soak or a 1.0 freeze.
- Re-ran `tools/wave-benchmarks/run-baseline.ps1 -Warmups 1 -Iterations 1 -Forks 1` with the
  actual forked JVM; it completed successfully at about 3.38M route lookups/s and refreshed
  `target/baseline-metadata.txt` (JDK 25.0.4, Windows, 20 CPUs, 1/1/1 settings).
- Full long-duration and heap/direct-memory observation evidence remains open under 0.9-T2;
  immutable compatibility and signed/fixed-coordinate release gates remain intentionally open.

### 0.9-T2 PARANOID bounded soak

- Ran `./mvnw.cmd -q -B -ntp -pl wave -am verify -Ptransport,reliability -Dwave.reliability.waves=600`.
- Result: exit code `0`; 76,800 real-socket requests (600 × 128), 321 tests, 0 failures,
  0 errors, 0 skipped, with Netty `PARANOID` leak detection enabled.
- Repeated as a clean build (`clean verify`), with the same result: 321 tests and zero
  failures/errors/skips.
- This strengthens bounded pressure/leak evidence but does not replace the remaining
  long-duration heap/direct-memory observation or immutable release gates.
- Scheduled reliability CI now launches a finite Wave server target with Native Memory
  Tracking, runs `observe-memory.sh` for 30 seconds, checks the workload completion marker, and
  uploads metadata/result/memory artifacts. The first local Windows attempt used the wrong shell
  PID and WSL shim; the explicit MSYS2 rerun completed and is recorded below.

### 0.9-T2 local NMT observation

- Rebuilt the benchmark and ran a non-forked JMH target with
  `-XX:NativeMemoryTracking=summary`; MSYS2 PID discovery was used because Windows `$!` is not
  the attachable JVM PID.
- `observe-memory.sh <pid> 20` completed four samples and the JMH target completed normally with
  two measurement iterations at `3,458,502.811 ops/s`.
- Heap committed moved from `1,048,576 KB` to `1,515,520 KB`; NMT total committed moved from
  `1,231,264 KB` to `1,648,697 KB`, with heap used returning from an `815,391 KB` warmup peak
  to `131,373 KB` at the final sample.
- This is a finite route-benchmark observation proving the sampler/attachment path. It is not a
  claim of server long-run leak freedom; scheduled CI still owns the reproducible RC artifact.

### 0.9-T2 server workload observation

- Added `WaveServerReliabilityTarget`, a bounded benchmark utility that drives a real Wave
  HTTP/1.1 server with 32-request batches for a configurable finite duration (default 20 s,
  max 600 s), including both JSON success and application-failure routes. The target now sets
  explicit connection/in-flight/pending-byte limits and 5-second request/read/write plus
  10-second idle and 5-second shutdown timeouts.
- Tightened `ReliabilitySmokeTest` so its 200 count also requires the exact `ok` response body;
  a two-wave targeted run passed with one test and zero failures/errors/skips.
- Local run used 30 seconds and completed `2,136,640` requests with `2,003,100` HTTP 200
  successes and `133,540` application failures; the server and client both closed normally.
- The 20-second NMT observation captured four samples while the server workload was active:
  NMT total committed moved from `500,028 KB` to `2,519,452 KB`; heap committed moved from
  `1,048,576 KB` to `2,187,264 KB`. This is workload evidence, not a claim that a 20-second
  sample proves long-run leak freedom.
- Scheduled CI now launches this server target (rather than a route-only JMH process), samples
  it, checks its completion marker, and uploads the server result plus memory observations.

### 0.9-T2 extended bounded server workload

- Ran the same target for 60 seconds at concurrency 32; it completed `4,060,000` requests,
  including `3,806,250` HTTP 200 successes and `253,750` application failures, and exited with
  code `0` after normal shutdown.
- The 20-second NMT window captured four samples during that workload: total committed moved
  from `966,929 KB` to `2,178,970 KB`, while heap committed moved from `614,400 KB` to
  `1,851,392 KB`. This remains finite RC pressure evidence, not a claim of indefinite leak
  freedom.
- Scheduled reliability CI now uses the same 120-second workload and 30-second sampler.

### 0.9-T2 120-second server workload

- Rebuilt the benchmark and ran `WaveServerReliabilityTarget` for 120 seconds at concurrency 32
  with `-XX:NativeMemoryTracking=summary`; it completed `8,389,504` requests, including
  `7,865,160` HTTP 200 successes and `524,344` application failures, then exited normally.
- A 30-second sampler captured six points while the workload was active. NMT total committed
  moved from `2,129,263 KB` to `2,127,127 KB`; G1 heap committed remained `1,802,240 KB`
  across the sampled points. This strengthens bounded RC evidence but remains finite observation,
  not proof of indefinite leak freedom.

### 0.9-T3 fail-closed release verifier

- Added `tools/distribution/verify-release.ps1`; it requires an archive, SHA-256 sidecar,
  detached signature, and owner-controlled public key, then verifies digest and `openssl dgst`
  signature before accepting release inputs.
- Missing inputs, malformed digests, unavailable OpenSSL, digest mismatch, and invalid signatures
  all fail closed. No key or signature is generated or committed, so this does not claim an
  immutable release or complete the Revapi baseline prerequisite.
- Local verifier fixture evidence: a generated ephemeral RSA signature passed with the current
  archive SHA-256 (`4ad9ba0831de0afdc5fd9b77b4a52eb84064cef4c08d8ea6ba932445958a9536`), then a zeroed sidecar failed with exit code `1` and the
  expected `SHA-256 mismatch`; the fixture was removed. CI now checks missing unsigned inputs
  fail closed without requiring a repository key, and also generates an ephemeral runner-only key
  to prove valid signature acceptance, invalid-signature rejection, and digest-tamper rejection.
- Invalid OpenSSL stderr is captured into the verifier's deterministic
  `Detached release signature verification failed` diagnostic; current-archive valid-signature
  and invalid-signature local checks both pass.

### 0.9-T2 reproducibility documentation

- Added the exact local build/run command for `WaveServerReliabilityTarget` to the benchmark
  README, including NMT attachment and the `1..600` duration / `1..128` concurrency bounds.
- Verified an out-of-range duration (`601`) exits with the documented bounded-property error.
- Tightened the target's success accounting to require the exact `{"ok":true}` JSON body in
  addition to HTTP 200; after rebuilding, a one-second/eight-concurrency real-socket smoke
  completed `7,240` requests (`6,335` successes and `905` application failures).
- Promoted the scheduled reliability workflow to the same bounded 120-second workload with a
  30-second NMT sampler; the job remains capped by its existing 45-minute timeout and uploads
  the result/memory artifacts.
- Added fail-closed CI checks for exactly six five-second sample headers plus both G1 heap and
  Native Memory Tracking sections, so a missing or early-terminated sampler cannot pass on the
  workload completion marker alone.
- The same gate now requires the final result line to contain positive request, success, and
  application-failure counts, rejecting truncated or empty workload summaries.

### 0.9-T4 operations command alignment

- Updated `docs/operations-guide.md` so its reliability command enables both transport
  `PARANOID` leak detection and the bounded 600-wave profile; PowerShell users receive quoted
  profile/property arguments.
- Verified the documented command shape with `./mvnw.cmd -q -B -ntp -pl wave -am verify
  "-Ptransport,reliability" "-Dwave.reliability.waves=1"`: 321 tests, zero
  failures/errors/skips.

### 0.9-T3 SHA-256 sidecar automation

- Added `create-sha256.ps1` and `create-sha256.sh` to generate the verifier's GNU-style
  `<digest>  <archive-name>` sidecar without creating signing material.
- The PowerShell helper generated the current RC archive sidecar with hash
  `4ad9ba0831de0afdc5fd9b77b4a52eb84064cef4c08d8ea6ba932445958a9536`; archive hash comparison
  passed, and an archive-overwrite attempt failed closed.
- Consumer CI now generates and independently compares the sidecar before checking archive
  reproducibility.
- Consumer CI also exercises the helper's archive-overwrite rejection path.
- After the distribution documentation update, the archive rebuilt twice with hash
  `CB55F05FA7A03F90A7E08D813C28E9A1112C75133491BEA7C7C8E892DF850C88`; the sidecar helper was
  rerun from `tools/distribution` and produced the same digest.

### 0.8-T3 compatibility fail-closed recheck

- Re-ran `./mvnw.cmd -q -B -ntp -pl wave -am validate "-Pcompatibility"
  "-Dwave.api.baseline=io.wavejava:wave:0.1.0-SNAPSHOT"`; it failed in Enforcer with the
  expected immutable-baseline diagnostic. This confirms the new local RC consumer coordinate
  does not weaken the compatibility gate.

### 0.9-T4 dependency/security gate

- Ran the documented `dependency:tree -Dverbose` and direct `enforcer:enforce` commands for
  `wave`; both now exit `0`.
- Fixed the Maven Enforcer configuration by moving the baseline rules to plugin-level
  configuration, so lifecycle validation and direct CLI invocation share the same Java 25,
  Maven 3.9+, dependency-convergence, and duplicate-POM rules.
- Re-ran the clean transport gate after that POM change: `./mvnw.cmd -q -B -ntp -pl wave -am
  clean verify -Ptransport` completed with 320 tests and zero failures/errors/skips. The root
  `./mvnw.cmd -q -B -ntp verify` also completed with exit `0`.
- Repeated the same clean transport gate on the current worktree after the RC tooling and CI
  updates: 320 tests, zero failures/errors/skips, with JPMS/ArchUnit, real sockets, and PARANOID
  ByteBuf leak detection still passing.

### RC consumer/distribution regression

- Rebuilt the five named-module examples and assembled the 15-entry distribution archive.
- `verify-archive.ps1` passed; rebuilding the archive twice produced the same SHA-256
  `CB55F05FA7A03F90A7E08D813C28E9A1112C75133491BEA7C7C8E892DF850C88`.
- The Wave JAR remains `BAB18ABF920D348066646151437BD2EEFA21B5231BBC88661D7DFC1D69F35065`.

### 0.9-T1 local RC consumer verification

- Installed the current Wave build under local coordinate `io.wavejava:wave:0.9.0-rc.1` using
  `maven-install-plugin:3.1.4:install-file`, preserving the Wave POM dependency metadata.
- Compiled all five named-module examples with `-Dwave.version=0.9.0-rc.1` and ran each bounded
  smoke main successfully. This is published-style consumer evidence only; the coordinate is
  locally generated and is not an immutable compatibility baseline.
- Added `tools/consumer-verification/verify-consumer.sh` and `verify-consumer.ps1`, both rejecting
  SNAPSHOT/LATEST/RELEASE selectors before invoking Maven. The PowerShell helper completed the
  full install, RC-coordinate compile, and five-smoke flow successfully; both shell helpers pass
  `bash -n` syntax validation where applicable.
- Re-ran the PowerShell helper from the `wave` subdirectory to verify script-root resolution; the
  same five-example compile/smoke flow completed with exit code `0`.
- Git Bash syntax validation passed; its first execution exposed that `bash ./mvnw` is not the
  correct Windows wrapper path. The helper now detects MINGW/MSYS/CYGWIN and uses `mvnw.cmd`,
  while Linux CI continues to use `bash ./mvnw`.
- Re-ran the Bash helper through Git Bash after that detection change; local RC installation,
  five-module compilation, and all five smoke mains completed with exit code `0`.
- Parsed `.github/workflows/verify.yml` with PyYAML; the workflow is structurally valid and
  contains the expected `verify`, `transport`, `compatibility-profile-safety`,
  `consumer-examples`, and `reliability` jobs.
- Final static recheck passed: both Bash helpers (`verify-consumer.sh`, `create-sha256.sh`)
  pass `bash -n`, workflow YAML parses with the expected five jobs, and `git diff --check`
  reports no whitespace errors (only normal LF/CRLF conversion notices).

### Roadmap task-schema gate

- Added `tools/roadmap/validate-roadmap.ps1` and wired it into the PR verification job.
- The validator checks all 29 versioned task rows for non-empty component, public-contract,
  budget, prerequisite, acceptance, and status columns; acceptance text must include success,
  application failure, limits, and shutdown handling.
- Local execution passed: `roadmap-task-schema-ok tasks=29`.
- Tightened the acceptance rule to require explicit deadline/cancellation coverage (or an
  explicit 不適用 declaration); all existing 0.2–0.9 rows now satisfy the rule.
- Re-ran the root PR gate after wiring the validator: `./mvnw.cmd -q -B -ntp verify` exited `0`.

### Release-owner handoff

- Added `docs/release-owner-handoff.md` with exact immutable-baseline requirements, signed
  distribution commands, fail-closed acceptance conditions, and the required release record.
- The handoff explicitly preserves the boundary before 1.0 API stability/freeze and prevents a
  locally rebuilt or mutable artifact from being treated as compatibility evidence.

### Ponytail architecture review

- Added `_spec/wave-architecture-review.md` with an evidence-based review of the current source
  tree: 203 main Java files / 26,623 lines, including 154 files / 18,656 lines under exported
  `api.*` packages.
- Confirmed the core Wave → App → Routes → Middleware → Handler model, Virtual Thread execution,
  bounded resources, and JPMS visibility are aligned with the architecture decisions.
- Recorded four pre-freeze cleanups instead of deleting roadmap-required features: relocate four
  Netty implementation files currently under exported `api.*`, internalize the public
  `ResponseBody` transport union, remove production export of `api.testing`, and hide
  `BlockingResultWaiter` if no external consumer requires it.
- Reframed the architecture document into Core/Web/Transport/Operations/Extension layers and
  corrected stale package/tree references. No v1.0 API stability or freeze was entered.

### Naming review

- Added `_spec/wave-naming-review.md`, defining suffix semantics and reviewing ambiguous names such
  as `ClientRequestPool`, `ResponseBody`, `ApplicationResult`/`RequestDispatcher`,
  `MultipartForm`/`FormData`/`Upload`, and `SseResponseInfo`.
- Confirmed no roadmap-required feature should be deleted for naming alone. Candidate breaking
  renames/internalization are explicitly deferred until before an immutable baseline, with no alias
  or facade added speculatively.
- Added naming-focused Javadoc clarifications for exchange-admission `ClientRequestPool`, transport-facing
  `ResponseBody`, WebSocket endpoint terminology, and application-dispatch result/operation roles;
  the root PR gate still passes (`./mvnw.cmd -q -B -ntp verify`, exit `0`).

## Session: 2026-09-10 — 0.9 RC architecture and naming cleanup

- **Status:** complete through R6; intentionally stopped before 1.0 API stability/freeze.
- Renamed client request pool, SSE response info, application result/dispatcher, and blocking waiter
  types without aliases; client pool fields now describe concurrent requests and leased requests.
- Moved client/WebSocket Netty implementations to unexported `io.wavejava.wave.netty`; public client
  signatures remain transport-neutral. Added `maximumPhysicalChannels` for internal channel accounting.
- Replaced public `ResponseBody` and `Response.body()` with sealed `Response`, internal `InternalResponse`,
  `ResponseData`, and the single `ResponseDataReader` transport path.
- Moved `RequestFixture`, `EmbeddedApp`, `TestHttpClient`, and `MockUpstream` to test source and removed
  `api.testing` export. Examples that use JDK HttpClient now declare their own module requirement.
- Added `tools/roadmap/validate-naming.ps1`; roadmap validation now includes R0–R6 rows. Both static
  validators pass (`roadmap-task-schema-ok tasks=36`, `naming-and-boundary-check-ok productionFiles=203`).
- Clean root, transport, and transport+reliability verification passed; five named-module examples
  compile against the current artifact. No immutable compatibility baseline was created.

