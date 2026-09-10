# Findings & Decisions

## Requirements

- The 1.0 delivery includes client, Flow streaming, multipart, SSE, WebSocket, session, operations, and HTTP/2.
- Each task must name its release, components, public contract, resource budget, dependencies, and acceptance cases.
- The source of truth is `_spec/wave-architecture.md` plus `_spec/wave-roadmap.md`.
- Groovy, DI containers, template engines, and vendor-specific integrations are out of scope for 1.0.

## Research Findings

- Java 25's `ScopedValue.Carrier.call` accepts `ScopedValue.CallableOp`; a `java.util.concurrent.Callable` needs an adapter lambda.
- Netty's self-signed-certificate path is unsupported by the available JDK without a provider fallback, so TLS integration tests need a committed test fixture rather than runtime certificate generation.
- The existing 0.1 build already uses a single JPMS artifact and non-exported runtime/netty/internal packages.
- `HttpContentCompressor` can remain a transport-pipeline concern once logical response serialization completes before the EventLoop.
- Raw socket tests are required to prove HTTP/1.1 pipelining order; high-level JDK clients do not expose that ordering reliably.

## Technical Decisions

| Decision | Rationale |
|---|---|
| `RequestContext` carries deadline and cancellation | Gives handlers and transport one consistent per-request control plane. |
| `InvocationRuntime` uses virtual threads | Separates application blocking from the EventLoop. |
| `ConnectionSequencer` has count and byte budgets | HTTP/1.1 ordering must not create unbounded completed-response storage. |
| TLS uses explicit `TlsConfig` paths | Keeps production setup deterministic and makes test certificates test-only. |
| Prepared responses contain immutable bytes and header snapshots | Prevents JSON rendering or mutable application response work from returning to the EventLoop. |
| Shutdown phases share one monotonic timeout budget | Listener, runtime, child connections, and EventLoops must not each wait the full configured timeout. |
| `Body.form()` uses a default parser without reading request headers | Keeps `Body` independent of transport metadata; callers requiring declared charset/content-type validation use `UrlEncodedFormParser` directly. |
| A service lifecycle is created fresh for each server run | An immutable `WaveApp` can be reused without reusing a one-shot service state machine. |
| 0.3 static files only materialize a selected finite range | File streaming remains a 0.4 Flow concern; selected bytes stay under the explicit cap. |
| 0.4 server streaming scope excludes client and multipart | Root assigned `BodyPublisher`, response Flow, bridge/controller/budget, Netty HTTP/1.1 wiring, and contract/socket tests only. |
| Existing aggregated response path remains supported | Flow response support must be additive and preserve 0.1–0.3 handlers/tests. |
| HTTP/1.1 streaming is explicit server configuration | `RequestBodyMode.AGGREGATED` remains the default; `STREAMING` removes `HttpObjectAggregator` and exposes only `Request.streamingBody()`. |
| One bridge item is the transport retention bound | `InboundFlowBridge` holds at most one copied request chunk and only issues one manual `read()` per outstanding demand cycle; `FlowBridge` holds one outbound item until its write future completes. |
| Empty outbound Flow buffers are protocol-invalid | Rejecting them prevents a zero-byte item from repeatedly consuming demand without consuming the outbound byte budget. |
| Publisher subscription is application work | `FlowBridge` schedules `Publisher.subscribe(...)` on the existing virtual-thread invocation runtime; only demand, channel writes, and ordering return to the Netty EventLoop. |
| Streaming HTTP/1 decoding is manually pull-driven | A split `HttpRequestDecoder` with `setSingleDecode(true)` leaves coalesced wire chunks in Netty's bounded cumulation; each `BodyPublisher` demand decodes at most one next `HttpObject`, so no adapter queue is needed. |
| Inbound terminal state is generation-guarded | Queued delivery/complete callbacks validate their generation after cancellation or failure; `onSubscribe` runs outside the bridge lock and terminal callbacks cannot precede it. |
| Known Content-Length is rejected at the head | Streaming requests over the configured limit receive ordered `413` before handler dispatch; a limit-aware Expect handler suppresses `100 Continue`. |
| Raw 413 is only safe with no predecessor | A streamed-body byte-budget breach aborts rather than writing a direct 413 whenever its own or an earlier HTTP/1.1 response/invocation can still own wire order. |
| Client cancellation has two distinct lifetimes | A caller's cancellation handle may request an abort immediately, but a pool lease remains owned until the underlying HTTP exchange actually reaches a physical terminal state. |
| Redirect following requires a concrete HTTP authority | Only resolved absolute `http`/`https` locations with a host may be followed; malformed, opaque, or non-HTTP locations deterministically return the original 3xx response. |
| Redirect and retry budgets are independent | A redirect keeps the current retry-attempt count; only a retryable response or transport failure consumes the next retry-budget attempt. |
| `java.net.http` is non-transitive | Test and example modules that use JDK HttpClient declare their own readability; Wave does not re-export it. |
| Response Flow terminal signals require an established subscription | A publisher that completes before `onSubscribe` is a protocol violation; the bridge must fail/cancel rather than strand already-written HTTP/1 headers and their sequencing slot. |

## 0.4 Server Streaming Discovery

- The architecture requires the transport to be the `Flow.Publisher<ByteBuffer>` subscriber and to request data only when the channel is writable and its byte budget permits it (`_spec/wave-architecture.md`, Flow section).
- `RequestDispatchHandler` currently sends an application result through `PreparedResponse` and `ConnectionSequencer`; aggregated responses therefore need an explicit alternate stream payload/path rather than a queue hidden inside `Response`.
- The public `Response` now exposes only writers and lifecycle. Internal `ResponseData` carries the representation; `ServerLimits` contains per-connection pending-response bytes and stream budgets.
- `RequestDispatchHandler` already owns connection cancellation and all EventLoop state transitions, making it the correct owner for cancellation of an active stream subscription.
- HTTP/1.1 response ordering is implemented by `ConnectionSequencer`: a streaming response must keep its ingress ticket acknowledged until its terminal chunk write succeeds, otherwise a later pipelined response can overtake it.
- `ConnectionLifecycleManager.closeActiveConnections()` makes channel shutdown the transport cancellation point, so an active Flow bridge must cancel its subscription from `RequestDispatchHandler.closeOutstanding(...)`.
- `ResponseData` carries `BYTES`, `JSON`, `PROBLEM`, `STREAM`, and `WEBSOCKET`; `PreparedResponse` uses a header-only form for chunked content.

## 0.4 Final-Gate Review Notes

- `RequestDispatchHandler` keeps the original request deadline alive for a committed response stream through a distinct Netty deadline task; this needs direct socket-level expiry coverage, not only invocation-level deadline tests.
- The deterministic embedded-channel slow-peer test proves the Flow write/demand invariant. A complementary loopback test should prove the same invariant when TCP backpressure holds a large first response item.
- Redirect hops and retry attempts are independent policy budgets. A redirect must not consume `RetryPolicy.maximumAttempts`; the client work line is correcting and testing that distinction before the 0.4 gate is closed.
- A JDK `HttpClient.sendAsync` future becoming cancelled is not an observable proof that its TCP channel/body teardown has completed. Therefore it cannot be the release signal for a pool advertised as a physical connection bound; an owned Netty client channel must supply that lifecycle signal.
- The current 0.4 public client has repeatable finite byte request/response bodies, so an HTTP/1.1 Netty adapter may safely write one full request and aggregate one bounded response per channel; client streaming remains a later extension of this API.
- A Netty client must support direct HTTP persistent reuse and the existing ordinary HTTP-proxy absolute-form request behavior, while keeping total live/idle channels finite across target origins.
- Current ArchUnit rules forbid `api.*` dependencies on `netty.*`; an owned Netty client must therefore keep its Netty reference behind a package-private abstraction or deliberately narrow the rule to actual public contracts, with a regression that public signatures remain Netty-free.
- The Netty client milestone uses a package-private `ClientTransport` seam so public `WaveClient` signatures remain transport-neutral. A physical exchange completes only after a reusable HTTP/1.1 channel is returned idle or its `closeFuture` has fired; policy continuations run on a virtual-thread orchestration executor.
- Remaining client transport review points are close-delimited response completion, cancellation after loop shutdown, retirement of the unused JDK adapter, and real-socket proof of queued admission versus teardown order.
- Netty 4.1.137 completes a channel's `closeFuture` before it fires `channelInactive`; close-delimited response aggregation must therefore establish its terminal snapshot during pipeline inactivity before deferred physical-close finalization releases the client lease.
- A local `closeFuture` is the transport's observable resource-release boundary. Tests may independently observe peer FIN/RST, but must not claim a strict ordering between distinct TCP connections merely from accept timing.
- The completed 0.4 client uses an internal Netty transport seam. A shared `ClientRequestPool` limits requests; each `WaveClient` retains its own independently bounded physical channel cache, so documentation and tests must not claim a cross-client physical-cache cap.
- 0.4 deliberately stops at finite byte HTTP/1.1 client bodies. Direct TLS and ordinary HTTP proxy absolute-form are implemented; HTTPS-via-proxy CONNECT, HTTP/2 client, and client-side Flow are deferred to 0.7 rather than being represented by an unsafe partial tunnel.
- A clean `verify -Ptransport` is the release evidence, not a stale Surefire report directory: 0.4 closed with 174 tests and PARANOID ByteBuf leak detection.

## 0.5 Early Long-Connection Review

- A WebSocket inbound publisher must invalidate any already scheduled `onNext` when its subscription, peer connection, or server is terminal. Otherwise cancellation can still deliver stale application data after a close.
- WebSocket outbound byte reservation is not complete until EventLoop admission has succeeded. A rejected executor submission must fail the write future and return its reservation exactly once.
- A WebSocket endpoint's virtual-thread task and its HTTP request cancellation context must be owned by the session lifecycle, not merely the initial HTTP invocation. Disconnect and shutdown must interrupt/cancel it.
- Application data backpressure cannot suppress control-frame processing: ping, pong, and close need a protocol-priority path while an inbound data subscriber is slow.
- A standalone SSE client still needs a finite concurrent-connection admission bound and a bounded/clamped reconnect delay even though it intentionally streams rather than aggregates response bytes.
- Post-hoc header checks over `HttpURLConnection` cannot prove a retained-header resource bound because the JDK parser has already accepted the peer's full header block. The 0.5 SSE client must use a transport that enforces decoder limits before retention, or remain explicitly incomplete rather than claiming that gate.
- The completed SSE client therefore uses a private direct HTTP/1.1 raw-socket parser with response-head limits enforced before storage. Its header deadline is absolute (each read receives only the remaining deadline), while a separately bounded idle timeout applies after the stream opens; this avoids both slowloris and stalled-stream retention.
- Netty 4.1.137's `WebSocketServerHandshaker` installs its WebSocket decoder/encoder around the HTTP codec, writes the `101`, then removes the former `HttpServerCodec` when that write succeeds. The post-upgrade pipeline observed here is therefore structurally valid; a missing raw Ping/Pong or FIN/RST terminal signal must be treated as a read/half-close lifecycle failure rather than solved by reordering those codecs.
- A raw `shutdownOutput()` plus close is a TCP FIN, not an RST. The long-connection gate needs separate tests for graceful peer EOF and an actual RST (`setSoLinger(true, 0)`), each proving channel/session termination, cancellation-token propagation, and virtual-thread interruption.
- The HTTP-to-WebSocket codec swap has a concrete first-frame window: a dormant session handler must be installed and reads armed before `101` completion, then one EventLoop re-arm must occur after the write listener settles the pipeline. This is transport sequencing, not an application timing problem; the regression sends Ping as the first post-`101` frame.
- Server WebSocket frame accounting reserves up to 14 bytes beyond payload for the largest legal unmasked wire header. The public outbound limit validation must include this overhead, otherwise a configured maximum frame can be rejected by its own accounting path.

## 0.5 Direct WebSocket Client Discovery

- Netty's low-level `WebSocketClientHandshaker` installs an inbound decoder but does not install the matching outbound frame encoder. The owned client must add `WebSocket13FrameEncoder(true)` before publishing the connection; publication is deferred one EventLoop turn so the handshaker's queued legacy HTTP codec removal has settled.
- A direct client connection admission is a physical resource, not a connect future. Retain its semaphore permit through `Channel.closeFuture`, including cancellation, deadline, malformed response, and client shutdown paths.
- A strict Netty decoder answers a peer mask-direction violation with `1002` and then immediately closes the transport. That is a correct malformed-wire path, but it cannot be asserted as a successful peer close handshake; test normal close and protocol-close cleanup separately.
- Client inbound application data has one retained message. Its overflow is a resource exhaustion and must initiate `1009`, not enter the generic subscriber-failure path (`1011`); the integration test caught this close-code race and the publisher now returns the bounded-overflow signal directly to the session owner.
- Raw direct-`ws` test peers are sufficient to verify client masking and invalid server frames while staying package-private and bounded. They must not grow a background reader or become a production API.

## Issues Encountered

| Issue | Resolution |
|---|---|
| Concurrent edits temporarily made runtime tests fail to compile | Inspect the actual compiler output before changing test calls; scope adapter was repaired. |

## 0.9 RC Naming and Boundary Decisions

- `ClientRequestPool` is the request concurrency limiter; `maximumConcurrentRequests` and
  `leasedRequests` must not be confused with server, SSE, or WebSocket connection limits.
- `Response` is a sealed public control surface. Its payload representation is internal
  `ResponseData`, and transport reads it only through `ResponseDataReader`.
- `java.net.http` remains a non-transitive module requirement for the current test and example
  modules; it is not part of Wave's re-exported public readability contract.
- Test fixtures live in test source and `api.testing` is not exported. No second support artifact or
  compatibility alias was added.
- Netty client/WebSocket implementations live in unexported `io.wavejava.wave.netty`; the three
  public facades retain only private framework wiring, while reflection and ArchUnit checks prevent
  transport types from entering public signatures.

## 0.6 Operations Discovery

- Access observation cannot be implemented solely in `Middleware.onResponse`: that callback has application completion but not the terminal Netty write result. A correct event combines `ApplicationResult` metadata with the per-ticket transport terminal state and emits exactly once.
- `WRITTEN` is a write-completion guarantee, not client receipt. The event deliberately uses route patterns or fixed labels rather than raw request targets, and never retains payload/header/exception objects; this keeps metrics labels and the queue bounded.
- An observability bridge must have its own finite worker/queue. Running vendor callbacks on the invocation runtime can exhaust application capacity; running them on the EventLoop violates the transport boundary. `offer` plus drop is the overload behavior, and shutdown drains only within the existing monotonic shutdown budget.
- Response-stream byte accounting belongs after successful chunk write completion, not in the publisher `onNext`; chunks can be cancelled or fail before they reach the peer-facing transport.
- Session persistence cannot be a hidden post-response middleware hook because `Response` is already immutable after commitment. `SessionScope.commit(response)` is therefore deliberately explicit, and an unsuccessful rotation deletes both old and proposed IDs before clearing the cookie.
- Health check stages need defensive handling for a legal `CompletionStage` that completes with null: readiness maps it to a safe DOWN result rather than letting an endpoint escape with an NPE.
- Rate limits and bulkheads should reject at admission rather than queue. For per-key buckets, key cardinality/bytes and sweep work must be bounded; for bulkheads, release only at reverse unwind so a cancelled-but-still-running handler cannot leak capacity.

## 0.7 HTTP/2 Discovery

- RFC 7540 fixes the initial connection receive window at 65,535 bytes and provides no SETTINGS parameter to reduce it. Netty's decoder flow controller can increase it, but cannot make a lower configured value real; `Http2Config` therefore rejects a lower value.
- HTTP/2 stream body accounting uses a static equal partition of the connection budget. This is intentionally conservative: it provides a simple proof that all admitted aggregate or Flow stream bodies cannot exceed the parent budget without relying on an unbounded shared queue.
- An HTTP/2 child channel's ordinary close is not a reliable `RST_STREAM(CANCEL)` wire signal after END_STREAM semantics enter the pipeline. The client cancellation path must emit a reset explicitly before closing the child, then prove that the parent can serve another stream.
- A test-only raw Netty H2 peer is necessary for connection-specific header and forwarding conformance because high-level JDK clients cannot emit invalid HTTP/2 headers or observe reset/GOAWAY details. It must have finite waits and remain outside exported packages.
- The H2 stream codec exposes HTTP/1-shaped objects after conversion. `Http2StreamDispatchHandler` must set Wave's logical `Request.version()` to HTTP/2 explicitly, not infer it from the converted Netty object.
- HTTPS through an HTTP proxy requires CONNECT before TLS/ALPN; origin hostname verification remains on the tunneled TLS handler. A `PREFER` fallback cannot safely reuse the H2-only tunnel path for arbitrary HTTP/1.1 proxy behavior without its own tested implementation.
- A graceful H2 server shutdown must write `GOAWAY(NO_ERROR)` on the parent before cancelling invocations. Per-parent admission then rejects new streams while a drain future reaches terminal only after every admitted child stream releases its slot.
- A child stream with reads paused is a deterministic HTTP/2 slow-consumer fixture: it withholds only that stream's flow-control credit, so a concurrent fast stream can prove parent-level fairness without pausing the whole connection.
- Netty's `DefaultHttp2Headers` canonicalizes pseudo headers before ordinary fields while encoding. A pseudo-header ordering test built with that high-level object is therefore not a wire-level conformance test; duplicate pseudo headers and conflicting content lengths still exercise decoder rejection, while frame/order abuse needs a lower-level fixture.
- Netty's high-level H2 stream writer is not a sufficient proof for a consecutive empty-DATA decoder guard: a test-only TLS/ALPN wire peer must serialize the preface, HPACK request head, and zero-length DATA frames itself. Nine non-terminal frames deterministically trigger the configured guard and `GOAWAY(ENHANCE_YOUR_CALM)`.
- `Http2FrameCodec` propagates a fatal protocol exception before it finishes its own GOAWAY/close sequence. A downstream handler must consume only embedded HTTP/2 exceptions and must not close the parent; closing there races the codec and can substitute an unintended `GOAWAY(NO_ERROR)`.
- A GOAWAY-draining H2 parent must remain in a live-physical-parent set through `closeFuture`, separately from the route-keyed selectable-parent map. Replacing the route entry alone would make a draining parent invisible to the physical connection cap and shutdown accounting.
- Client GOAWAY correctness has two independent cases: an exchange after a completed stream must switch parent, and an exchange while an accepted stream is still draining must switch parent without aborting the accepted stream. Both require latch-controlled real socket fixtures rather than timing sleeps.

## 0.8 SPI Discovery

- `ServiceLoader` provider lookup belongs to immutable application assembly, not the request path. A finite cap, normalized id/selection key, and deterministic priority tie handling make extension failure observable before binding a port.
- Testing a third-party provider from this named module is most direct with a dynamically compiled unnamed-module provider and a temporary `META-INF/services` resource. This proves the public SPI independently of Wave's own module descriptor.
- A Revapi comparison is meaningful only against an immutable released artifact. Generating the comparison JAR from the current tree would make a passing result self-referential and must not be presented as an API compatibility gate.
- Revapi 0.15 accepts `error` as the fail criticality; the build profile must resolve an explicit old artifact and fail rather than silently skipping compatibility analysis.
- A source-level JPMS boundary can be frozen before publication, but it must be explicitly named a pre-release manifest. Exact exports, `uses`, and transitive `requires` catch accidental public-surface drift; they do not replace a binary comparison.
- Compatibility input must reject mutable selectors before analysis. A stable-looking but nonexistent coordinate should still reach Revapi and fail closed, so CI can distinguish property validation from artifact-resolution safety.
- JPMS export names alone do not prove public accessibility: a qualified export has the same source package name. The pre-release manifest therefore also forbids export targets, open modules, `opens`, and `provides` directives.
- The isolated `tools/revapi-break-proof` fixture installs an old API, compares a candidate with `removed()` deleted, and fails with Revapi `java.method.removed` (`SOURCE: BREAKING, BINARY: BREAKING`). This is the strongest repository-local proof available before a Wave release artifact exists.
- The current public contracts already provide enough primitives for 0.9 examples (`Wave.server`, bounded `ServerLimits`/`ServerTimeouts`, `Response.stream`, `SseEmitter`, `WebSocket`, and `WaveClient`); no example-specific runtime abstraction is needed. Examples should remain plain consumer modules and wait for the 0.8 release gate before being called published-consumer verification.
- A real immutable Wave artifact is still unavailable, so the breaking-change proof must be isolated from the main module. A two-module old/new fixture can install the old API locally, run Revapi against the new JAR, and assert the deliberate public-member removal fails; it proves the gate behavior without pretending to be the Wave baseline.

## 0.9 Release Candidate Foundations

- Consumer examples compile as named modules against `io.wavejava:wave:0.1.0-SNAPSHOT` after a
  local `install`; this verifies module resolution and catches imports outside root/`api.*`/`spi.*`.
- `exec-maven-plugin` provides a small, repeatable smoke path for each example without adding a
  framework-specific launcher. Each main owns its server/client lifecycle with try-with-resources
  or explicit close.
- A reliability test remains opt-in (`-Preliability`) so ordinary PR verification stays short;
  the test uses a finite 128-request set and a 10-second per-response ceiling, while the transport
  profile remains the authoritative leak/disconnect/slow-peer gate.
- JMH annotation processing works on Java 25 with `jmh-core:1.37`; benchmark output records VM
  metadata automatically. Only deterministic route lookup is benchmarked here; protocol behavior
  remains in socket/integration suites.
- Maven `exec:java` can report BUILD SUCCESS when a forked JMH VM cannot load `ForkedMain`; the RC
  runner must build a dependency classpath and invoke `java` directly, with `pipefail`/exit-code
  checks. The new PowerShell and Bash runners do that and persist metadata plus output.
- Reliability load must be parameterized with a hard upper bound. A finite 128-request wave is easy
  to reason about; repeating at most 600 waves gives scheduled soak coverage without accidentally
  turning a CI property into an unbounded stress test.
- 0.9-T3 cannot honestly be called a release dry-run until 0.8 has an immutable published
  coordinate. The repository now has consumer/build/report plumbing, but no fake local artifact is
  promoted as a compatibility baseline.

## Resources

- `_spec/wave-architecture.md`
- `_spec/wave-roadmap.md`
- `_spec/wave-api-compatibility.md`
- `wave/src/main/java/io/wavejava/wave/runtime/`
