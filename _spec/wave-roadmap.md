# wave 1.0 Roadmap

**狀態：** Implementation in progress（0.1–0.7 完成；0.8 進行中；0.9 RC 基礎已落地）
**更新日期：** 2026-09-10
**權威性：** 本文件是版本、任務與驗收狀態的唯一來源；[wave-architecture.md](wave-architecture.md) 定義架構與不變條件。

## 共同規則

wave 為全新重建專案。已刪除的 `io.waveio` 程式、測試、release 結果與 API 均不具相容性或完成證據效力，最多只能作為測試情境參考。Maven 座標固定為 `io.wavejava:wave`，JPMS module 固定為 `io.wavejava.wave`。

每項任務必須在合併前列出：所屬版本、元件、公開契約、前置依賴、count/byte/time budget，以及成功、application failure、deadline/cancellation、超額限制與 shutdown 的驗收案例。不適用的案例必須說明原因；任務不得跨版本交付未完成的下游能力。

## 測試層與命令

| Gate | 觸發時機 | 必要內容 | 命令（bootstrap 後） |
|---|---|---|---|
| PR | 每個變更 | compile、JPMS export、ArchUnit、unit、contract、deterministic fixture | `./mvnw -B -ntp verify` |
| transport | transport/TLS/streaming/HTTP2/WebSocket task | 真實 socket、disconnect、slow peer、limit、shutdown、PARANOID leak detector | `./mvnw -B -ntp verify -Ptransport` |
| reliability（0.9 起） | 排程與 RC | 長時間連線、慢 consumer、取消、heap/direct-memory、load、JMH | `./mvnw -B -ntp verify -Ptransport,reliability`（128-request opt-in smoke；`-Dwave.reliability.waves=600` 的有限 PARANOID soak 已驗證） |
| compatibility（0.8-T3/1.0） | immutable public baseline 產生後 | root、`api.*`、`spi.*` binary compatibility；missing/mutable/unresolvable baseline fail closed | `./mvnw -B -ntp verify -Pcompatibility "-Dwave.api.baseline=io.wavejava:wave:<immutable-version>"` |

Roadmap task rows are schema-checked in CI with
`pwsh ./tools/roadmap/validate-roadmap.ps1`. The check requires non-empty version,
components, public contract, budget, prerequisites, acceptance cases, and status columns;
acceptance text must explicitly cover success, application failure, limits, and shutdown.

測試不得以任意 `sleep` 推測並發結果；deadline、排程與取消必須透過可控 clock、probe 或 latch 驗證。benchmark 僅記錄可重現基線與回歸，不預先承諾吞吐或延遲數字。

## 0.1 — 可用核心

**前置依賴：** 無。  
**狀態：** 完成（2026-09-08）。

**元件：**

- Bootstrap：`module-info.java`、`Wave`、`WaveApp`、`WaveServer`。
- HTTP API：`Request`、`Response`、`Body`、`Headers`、`Cookie`、`MediaType`。
- Pipeline：`Handler`、`Middleware`、`Outcome`、`HttpException`、`ExceptionMapper`。
- Routing：`Routes`、`RouteMatch`、`RouteMetadata`、compiled route tree。
- Testing（test source only）：`RequestFixture`、`EmbeddedApp`、`TestHttpClient`。
- Internal transport：HTTP/1.1 adapter、`RequestDispatchHandler`、`ResponseWriteHandler`。

**完成 gate：** bounded aggregated request body、route/method matching、404/405/`Allow`、middleware unwind、exception mapping、JSON/text/bytes response，以及基本 HTTP/1.1 socket 測試。`Body` 只可讀一次；不包含 streaming、multipart upload、HTTP client 或 TLS。

**證據：** 初始 0.1 baseline 的 `./mvnw -B -ntp -pl wave -am verify` 與 `./mvnw -B -ntp -pl wave -am verify -Ptransport` 均通過（29 tests）；後續版本的測試會增加 aggregate test count，但不回溯成為 0.1 完成證據。Socket suite 覆蓋 Virtual Thread handler dispatch、JSON rendering、HEAD body suppression、405/automatic OPTIONS、body budget 413 與 embedded GET/POST lifecycle；`-Ptransport` 啟用 Netty `PARANOID` leak detector。

## 0.2 — 執行正確性與 transport 安全

**前置依賴：** 0.1。  
**狀態：** 完成（2026-09-09）。

**元件：**

- Execution：`RequestContext`、`CancellationToken`、`Deadline`、`InvocationRuntime`、`RequestInvocation`。
- Limits：`ServerLimits`、`ServerTimeouts`、`PendingResponseBudget`、`ConnectionSequencer`。
- Lifecycle：`ShutdownCoordinator`、connection lifecycle manager。
- Transport：`TlsConfig`、compression adapter、Virtual Thread dispatcher。
- Testing：`ExecutionHarness`、deterministic clock、cancellation probes、leak test fixture。

**完成 gate：** handler/middleware 永不在 Netty EventLoop 執行；deadline、disconnect、cancellation、HTTP/1.1 response ordering、TLS、compression、graceful shutdown、slow peer 與 ByteBuf leak 都有 transport test 與 resource-bound assertion。

### 0.2 任務狀態

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.2-T1 | `RequestContext`、`CancellationToken`、`Deadline`、`InvocationRuntime`、`RequestInvocation` | `Request.context()`、deadline/cancellation accessors；內部 runtime 不 export | 每 request 一個 deadline；取消為一次性 signal | 0.1 `Request`/`WaveApp` | success、application failure、deadline cancellation、limit：deadline state 一次性、runtime shutdown | 完成：deterministic clock/scheduler + cancellation probe tests |
| 0.2-T2 | `PendingResponseBudget`、`ConnectionSequencer`、`PreparedResponse`、HTTP/1.1 dispatcher、`ResponseWriteHandler` | 無新增 transport public API；HTTP/1.1 回應保持 ingress order | per-connection pending response count + retained-byte cap；global in-flight semaphore | 0.2-T1 | success、out-of-order completion、count/byte cap、write failure/disconnect、deadline/cancellation：disconnect abort、shutdown：queued responses drain or fail deterministically | 完成：raw-socket pipeline/limit/disconnect tests |
| 0.2-T3 | `ServerLimits`、`ServerTimeouts`、`TlsConfig`、`CompressionAdapter`、connection lifecycle | `WaveServer.limits/timeouts/tls/compression` | connections、in-flight、request line/header/body、timeout、shutdown timeout | 0.2-T1 | TLS success/failure、gzip、over-limit、deadline/cancellation、shutdown | 完成：real TLS、gzip、in-flight/connection/byte limits tests |
| 0.2-T4 | `ShutdownCoordinator`、`ConnectionLifecycleManager`、`ExecutionHarness`、leak fixture | internal only | active connection/request ownership；single shared bounded shutdown wait | 0.2-T2/T3 | success：owned request drains；application failure：transport failure is isolated；deadline/cancellation：interrupt on disconnect；slow peer、disconnect、graceful shutdown、limit：bounded wait、PARANOID leak test | 完成：slow inbound peer、shutdown interrupt、`-Ptransport` leak profile |

**可查驗結果：** `./mvnw -B -ntp -pl wave verify -Ptransport` 於 2026-09-09 通過（67 tests）。`-Ptransport` 開啟 Netty `PARANOID` leak detector；socket suite 覆蓋 Virtual Thread dispatch、HTTP/1.1 ingress order、deadline 504、client disconnect、slow inbound peer、graceful shutdown、connection/byte/in-flight limits，另含 real TLS 與 gzip integration tests。

## 0.3 — Web 基礎能力

**前置依賴：** 0.2。  
**狀態：** 完成（2026-09-09）。

**元件：**

- Rendering：`Parser<T>`、`Renderer<T>`、`Rendered`、`RenderContext`、content negotiation resolver。
- Forms/files：`FormData`、`UrlEncodedFormParser`、`FileResponse`、`StaticFileHandler`、`Range`、conditional request evaluator。
- Application services：`Config`、`ConfigSource`、`ConfigBinder`、config provenance、`Registry`、`Service`、`ServiceContext`、`ServiceLifecycle`。
- Testing：cookie codec tests、static-file fixture、config/lifecycle fixture。

**完成 gate：** content negotiation、URL-encoded form、cookie、config source/provenance、service startup rollback、static file traversal 防護、ETag/Last-Modified/Range。multipart/file upload 明確不屬於本版。

### 0.3 任務狀態

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.3-T1 | `Parser<T>`、`Renderer<T>`、`ParseTarget<T>`、`Rendered`、`RenderContext`、`ContentNegotiationResolver`、`FormData`、`UrlEncodedFormParser` | `api.render.*`、`api.form.*`、`Body.form()`、`Response.render(Rendered)` | 既有 aggregate request body 上限；預設 form 為 64 KiB、128 fields、name 256／value 8 Ki Unicode code points；rendered bytes 由 application materialize，transport queue 仍受 per-connection pending-response byte cap | 0.2 request context／HTTP/1.1 dispatch | success：Accept quality/specificity、socket rendering、URL-encoded form；application failure：malformed percent/charset；limit：body/field/name/value cap；deadline/cancellation/shutdown：無 background parser，沿用 0.2 invocation cancellation | 完成：contract + real-socket tests |
| 0.3-T2 | `CookieCodec`、`Cookie`、request/response cookie adapter | `CookieCodec`、`Request.cookies()`、`Response.cookie(Cookie)` | inbound header size 受 `ServerLimits.maximumRequestHeaderBytes`；cookie codec 不建立 queue 或 background resource | 0.1 HTTP model | success：Cookie/Set-Cookie socket round trip；application failure：malformed security attribute rejected；limit：invalid pair isolation/header decoder cap；deadline/cancellation/shutdown：純同步 codec，不適用 | 完成：codec + real-socket tests |
| 0.3-T3 | `FileResponse`、`StaticFileHandler`、`Range`、conditional evaluator | `api.file.*`；root-contained handler、single-byte-range response | default selected-file materialization 8 MiB；single range only；header/request budgets inherited from `ServerLimits`；no multipart/streaming | 0.2 bounded HTTP/1.1 response dispatch | success：ETag/Last-Modified/304/206；application failure：missing/invalid input maps to 404/403/416；limit：file cap；shutdown/cancellation：synchronous finite read, inherited transport cancellation | 完成：unit + real-socket traversal/range tests |
| 0.3-T4 | `Config`、`ConfigSource`、`ConfigBinder`、provenance、`Registry`、`Service`、`ServiceContext`、`ServiceLifecycle` | `WaveApp.config/registry/service(s)`、immutable config/registry API、service lifecycle API | immutable assembly snapshots；no request queue; service stop uses the shared `ServerTimeouts.shutdownTimeout`; start completes before a listener binds | 0.2 server lifecycle | success：deterministic precedence/binding/context/start order；application failure：binding/unknown/cyclic dependency/start failure; limit：immutable snapshot/resource count；shutdown：reverse stop with aggregate failure; deadline/cancellation：pre-bind service startup has no request context | 完成：contract + real-socket startup rollback/port-reuse tests |

**可查驗結果：** `./mvnw.cmd -B -ntp -pl wave verify -Ptransport` 於 2026-09-09 通過（106 tests）。JPMS／ArchUnit、unit、contract 與 deterministic fixture tests 均包含在該 command；transport profile 啟用 Netty `PARANOID` leak detection。新增 socket coverage 驗證 renderer negotiation/406、aggregate form、Cookie/Set-Cookie、static ETag/304/range、service startup ordering、rollback、port reuse 與 reverse shutdown。未加入 `Flow.Publisher`、multipart upload 或任何 0.4 public type。

## 0.4 — Client 與資料流

**前置依賴：** 0.3。  
**狀態：** 完成（2026-09-09）。

**元件：**

- Streaming：`BodyPublisher`、`Response.stream(Flow.Publisher<ByteBuffer>)`、`FlowBridge`、`InboundFlowBridge`、`FlowSubscriptionController`、outbound byte budget、demand-driven HTTP/1.1 codec。
- Async：`BlockingResultWaiter`、deadline/cancellation bridge。
- Client：`WaveClient`、`ClientRequest`、`ClientResponse`、`ClientRequestPool`、`RetryPolicy`、`BackoffPolicy`、redirect/proxy policy，以及內部 `ClientTransport`／`NettyClientTransport`。
- Upload：`MultipartParser`、`MultipartPart`、`Upload`、temporary-file manager。
- Testing（test source only）：`MockUpstream`、Flow demand/cancellation probes、upload cleanup fixture。

**完成 gate：** Flow demand、`ByteBuffer` ownership、slow consumer、stream cancellation、client connection reuse/timeouts、retry safety、streaming multipart upload 與 temporary-file cleanup 都需 contract 與 integration test。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.4-T1 | `BodyPublisher`、`FlowBridge`、`InboundFlowBridge`、`FlowSubscriptionController`、demand-driven HTTP/1.1 codec | `Request.streamingBody()`、`Response.stream(Flow.Publisher<ByteBuffer>)`；aggregate/streaming 互斥 | 每 connection 一個 outbound byte budget；inbound/outbound 均最多一個 transport-owned Flow item；decoder cumulation 受 Netty 上限 | 0.2 invocation/cancellation/sequencer；0.3 aggregate compatibility | success：真實 socket request/response stream、HTTP/1.1 order；application failure：empty item／terminal-before-subscribe；deadline/cancellation：disconnect、deadline、shutdown；limit：demand、byte cap、slow peer | 完成：contract、deterministic slow-consumer 與 real-socket tests |
| 0.4-T2 | `BlockingResultWaiter`、`WaveClient`、`ClientRequest`、`ClientResponse`、`ClientRequestPool`、`RetryPolicy`、`BackoffPolicy`、`ProxyPolicy`、內部 Netty client transport | bounded-byte public client；public signatures 不暴露 Netty/JDK transport；HTTP direct/proxy policy | shared request budget；每 `WaveClient` 的 live+idle HTTP/1.1 channel cache 不超過 `ClientRequestPool.maximumConcurrentRequests()`；request/response/header caps；redirect/retry budgets | 0.2 deadline/cancellation；0.4-T1 transport lifecycle conventions | success：reuse、cross-origin LRU、redirect/retry；application failure：malformed redirect／CONNECT rejected；deadline/cancellation：queued request only after physical close；limit：pool/request/response/header cap、shutdown | 完成：contract + raw/real-socket tests；ordinary HTTP proxy absolute-form 支援，HTTPS-via-proxy CONNECT 延至 0.7 |
| 0.4-T3 | `MultipartParser`、`MultipartPart`、`Upload`、temporary-file manager、`MockUpstream`、upload cleanup fixture | `api.multipart.*` streaming upload contract；temporary-file lifecycle | part/total/disk/file-count limits；part data 逐 chunk 處理；temporary files are bounded and removed | 0.4-T1 inbound body publisher | success：boundary split、file/form parts；application failure：malformed boundary/headers；deadline/cancellation：abort/error/shutdown cleanup；limit：part/total/disk/slow upload | 完成：contract + integration cleanup tests |

**可查驗結果：** `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` 於 2026-09-09 通過（174 tests）。該 clean verification 包含 compile、JPMS、ArchUnit、unit、contract、deterministic fixture、real socket 與 Netty `PARANOID` ByteBuf leak detection。獨立 Flow audit 與 client transport audit 均無 blocker。client 的 0.4 邊界為有限 byte request/response、direct TLS 與 HTTP proxy absolute-form；HTTPS-via-HTTP-proxy CONNECT、HTTP/2 client 與 client Flow API 保留至 0.7，沒有被誤列為本版已交付能力。

## 0.5 — 長連線

**前置依賴：** 0.4。  
**狀態：** 完成（2026-09-09）。

**元件：**

- SSE：`SseEvent`、`SseEmitter`、`SseClient`。
- WebSocket：`WebSocket`、`WebSocketSession`、`WebSocketMessage`、`WebSocketClient`。
- Runtime：heartbeat、close-handshake、per-subscriber outbound budget controller。
- Testing：`TestSseClient`、`TestWebSocketClient`。

**完成 gate：** SSE event/id/retry，WebSocket text/binary/ping/pong/close、frame/message limits、慢 client、disconnect 與資源回收。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.5-T1 | `SseEvent`、`SseEmitter`、`SseClient`、heartbeat、per-subscriber outbound budget | `api.sse.*` event/id/retry/reconnect contract；handler remains `void handle(Request, Response)` | each subscriber has explicit queued-event/byte cap and heartbeat timer; client has bounded concurrent admissions, bounded response head/line/event bytes, absolute response-head deadline, idle timeout, and reconnect delay/attempt caps | 0.4 Flow response streaming/cancellation | success：event/id/retry/reconnect、BOM/chunked parsing、resume ID；application failure：invalid event/protocol response；deadline/cancellation：disconnect/shutdown/open+idle timeout；limit：slow subscriber/budget/header/line/event/reconnect cap | 完成：22 focused unit/real-socket tests + independent audit clear；private client transport is direct HTTP/1.1 only, with TLS/proxy/HTTP2 deferred to 0.7 |
| 0.5-T2 | `WebSocket`、`WebSocketSession`、`WebSocketMessage`、`WebSocketClient`、close-handshake controller | `api.websocket.*`：server upgrade/session、direct-`ws` client request/connection；`inbound()` 僅發布 TEXT/BINARY 與可選 terminal CLOSE，Ping/Pong 屬 transport control | frame/message cap；one-item inbound data retention；data/application-control/mandatory-control/close reserves；client admission、response-head、connect/handshake/idle budgets | 0.4 Flow/backpressure; 0.5-T1 shared long-connection lifecycle conventions | success：text/binary/ping/pong/close/subprotocol；application failure：invalid upgrade/frame/handshake; deadline/cancellation：FIN/RST/disconnect/shutdown; limit：frame/message/slow client/control reserve；client physical-close admission release | 完成：0.5 僅交付 direct `ws`；client callbacks 不在 EventLoop 執行，physical-close admission、close/FIN/RST、masking、bounded headers、slow subscriber 與 protocol-close 均由 contract/real-socket tests 驗證。redirect/retry/proxy/`wss` 與 HTTP/2 client scope 保留至 0.7 |
| 0.5-T3 | `TestSseClient`、test-only `TestWebSocketClient`、`WebSocketTransportFixture`、long-connection transport fixtures | fixture 不屬於 exported API；raw direct-`ws` peer 可檢查 handshake/frame/masking/fragmentation，並分別驅動 WS close、TCP FIN、TCP RST | fixture has finite connect/read/handshake/frame caps and no background reader; deterministic held-write fixture owns byte-budget assertions | 0.5-T1/T2 | success：event/frame/fragmentation；application failure：invalid mask/RSV/opcode；deadline/cancellation：close/FIN/RST/shutdown；limit：frame/message/slow peer/control reserve plus PARANOID leak checks | 完成：`TestSseClient` 與 `TestWebSocketClient` 均在 test source、package-private；SSE fixture 有 finite header/line/event caps，WebSocket fixture 有 finite handshake/frame caps，皆無背景 reader；不新增公開 API |

**可查驗結果：** `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` 於 2026-09-09 通過（237 tests，0 failures/errors/skips）。此 clean verification 包含 compile、JPMS、ArchUnit、unit、contract、deterministic fixture、SSE/WebSocket real socket、FIN/RST/disconnect/shutdown、slow consumer 與 Netty `PARANOID` ByteBuf leak detection。

## 0.6 — Microservice Operations

**前置依賴：** 0.5。  
**狀態：** 完成（2026-09-09）。

**元件：**

- Session：`Session`、`SessionId`、`SessionStore`、`InMemorySessionStore`、signed-cookie codec、rotation/expiry policy。
- Health：`HealthCheck`、`HealthStatus`、liveness/readiness endpoint。
- Observability：`AccessLogEvent`、`MetricsBridge`、`TracingBridge`、`PrometheusBridge`。
- Resilience：`RateLimitPolicy`、`BulkheadPolicy`。

**完成 gate：** session expiry/rotation、readiness lifecycle、success/failure/cancellation observation、超額流量拒絕與 policy resource budget。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.6-T1 | `Session`、`SessionId`、`SessionStore`、`InMemorySessionStore`、`SessionManager`、`SessionScope`、`SignedSessionCookieCodec` | `api.session.*`；顯式 `open(request)`／`scope.commit(response)`；opaque signed cookie、expiry、rotation、invalidate | 256-bit ID；HMAC secret 至少 32 bytes；cookie ≤ 4 KiB；attribute count/bytes、idle/absolute lifetime；in-memory store 預設最多 10,000 live sessions | 0.3 cookie、0.5 lifecycle | success：signed cookie socket round trip；application failure：tampered cookie／capacity／committed response；deadline/cancellation：no hidden worker、expired/invalidated state removed；limit：attribute/session/cookie cap；shutdown：store is synchronous and request scope must finish before response commit | 完成：session core + real-socket tests，rotation race 清除所有 known IDs |
| 0.6-T2 | `HealthCheck`、`HealthStatus`、`HealthLimits`、`HealthRegistry`、`HealthEndpoints` | `api.health.*`；explicit liveness/readiness `Handler` factories | defaults: 32 checks、4 concurrent evaluations、2 s/check；no evaluation queue | 0.3 service lifecycle、0.2 deadline/cancellation | success：READY + healthy checks；application failure：throw/null/failed check → safe DOWN；deadline/cancellation：check timeout/interruption cancels stage；limit：concurrency exhaustion → DOWN；shutdown：STARTING/STOPPING/STOPPED/FAILED readiness state | 完成：contract + socket lifecycle endpoint tests |
| 0.6-T3 | `AccessLogEvent`、`Observability`、`ObservabilityLimits`、`MetricsBridge`、`TracingBridge`、`PrometheusBridge`、internal `ObservabilityDispatcher` | `api.observability.*`；completion event has application + transport outcome; Prometheus handler is explicit route registration | one daemon worker only when enabled; default queue 1,024 (max 1,000,000); max 32 callbacks/kind; offer/drop, no caller-run | 0.2 transport terminal lifecycle、0.4 Flow write completion、0.5 upgrades | success：200 event; application failure：500; deadline/cancellation：504, raw disconnect, shutdown; limit：slow sink/full queue drop; shutdown：bounded drain; stream bytes count only acknowledged chunks | 完成：unit, deterministic queue and real-socket tests; callbacks off EventLoop and bridge failures isolated |
| 0.6-T4 | `RateLimitPolicy`、`BulkheadPolicy` | `api.resilience.*` `Middleware`; 429/`Retry-After` and 503 rejection semantics | finite token buckets/key bytes/idle sweep; fair semaphore maximum concurrent; both zero request queue | 0.1 middleware unwind、0.2 cancellation | success：token refill/permit release; application failure：handler error still unwinds permit; deadline/cancellation：cancelled handler retains permit until unwind; limit：bucket/key table and bulkhead reject; shutdown：no background resource | 完成：deterministic policy + real-socket overload/shutdown tests |

**可查驗結果：** `./mvnw.cmd -q -B -ntp -pl wave clean verify -Ptransport` 於 2026-09-09 通過（268 tests，0 failures/errors/skips）。此 clean verification 包含 compile、JPMS、ArchUnit、unit、contract、deterministic fixture 與 real-socket tests；`-Ptransport` 啟用 Netty `PARANOID` ByteBuf leak detection，並覆蓋 success／application failure／deadline／disconnect／slow observer／stream terminal bytes／over-limit／shutdown。

## 0.7 — HTTP/2、安全與公開位址

**前置依賴：** 0.6。  
**狀態：** 完成（2026-09-09）。

**元件：**

- HTTP/2 server/client transport adapters、`Http2Config`、stream lifecycle manager、connection/stream flow-control budget。
- ALPN adapter、`PublicAddress`、`ForwardedHeaderPolicy`、trusted-proxy policy。
- Request-smuggling/conformance suite、HTTP/2 protocol fixture。

**完成 gate：** multiplexing fairness、stream reset/cancellation、connection/stream windows、ALPN、trusted forwarded headers、安全與 overload cases。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.7-T1 | `Http2Config`、HTTP/2 server adapter、`Http2ConnectionState`、`Http2StreamDispatchHandler`、ALPN adapter | `WaveServer.http2(Http2Config)`；只接受 TLS/ALPN `h2`，不支援 h2c 或 Upgrade；`Request.version()==HTTP_2` | concurrent stream semaphore；aggregate/Flow inbound static partition；connection/stream window；per-stream outbound partition | 0.2 lifecycle／cancel、0.4 Flow | success：TLS/ALPN、H2 request/response、multiplex；application failure：handler 500；deadline/cancellation：RST/shutdown；limit：window/header/body/stream cap；shutdown：bounded cancellation | 完成：ALPN/PREFER、Flow、multiplex、RST、GOAWAY drain、window invariant、static aggregate partition、slow-stream/fast-stream fairness、advertised stream cap 與 shared invocation-budget 503 均有 real-socket coverage |
| 0.7-T2 | owned HTTP/2 client、`ClientTlsConfig`、CONNECT tunnel、H2 connection cache | `WaveClient.http2/tls/proxyPolicy`；公開 client 不暴露 Netty；HTTPS proxy tunnel 仍維持 origin hostname verification | physical parent cache ≤ `ClientRequestPool.maximumConcurrentRequests()`；stream cap；inbound/outbound static connection partitions；pool header/body/time budgets | 0.4 client lifecycle、0.7-T1 | success：direct/CONNECT H2、reuse/multiplex；application failure：ALPN REQUIRE downgrade；deadline/cancellation：RST then parent reuse；limit：H2 partition; shutdown：close all parents | 完成：direct/CONNECT、PREFER fallback、RST/reuse、static partition；raw GOAWAY handoff同時覆蓋已接受 stream drain；held-peer/fast-stream fairness、deadline reset/reuse 皆有 real-socket coverage，且 draining parent 仍計入 physical-cap accounting |
| 0.7-T3 | `PublicAddress`、`ForwardedHeaderPolicy`、trusted CIDR policy | `Request.publicAddress()`；wire scheme/authority 永不被改寫；僅可信 immediate peer 的單一完整 `Forwarded`，legacy X-forwarded 必須 opt-in | trusted CIDR list最多128；不保留 untrusted header state | 0.1 Request、0.2 socket metadata | success：trusted H1/H2 header；application failure：malformed/multi-hop ignored；deadline/cancellation：純同步 policy，不適用；limit：CIDR cap；shutdown：無背景資源 | 完成：unit、H1 real socket、raw H2 valid/ambiguous conformance tests |
| 0.7-T4 | `Http1RequestFraming`、request-smuggling/conformance suite、`TestHttp2Peer`、`TestHttp2WirePeer` | internal framing guard；test-scope raw H2 peers 不進 public API | request-line/header/body caps；fixture one connection/event loop、finite waits | 0.1 HTTP parser、0.7-T1 | success：valid framing；application failure：CL+TE/duplicate CL/missing Host/forbidden H2 header；cancellation：RST; limit/shutdown：fixture close及 PARANOID leak | 完成：H1 raw socket；H2 forbidden connection header/`TE: gzip`／oversized header-list／duplicate pseudo-header／conflicting content length／trusted forwarded；raw RST flood 與 raw consecutive empty-DATA flood 都收到 `ENHANCE_YOUR_CALM` 並關閉 parent；stream-cap 和 application overload 均已驗證 |

**可查驗結果：** 0.7 focused suites `Http2ServerIntegrationTest`（含 application failure、deadline、GOAWAY drain、static partition、slow-stream fairness、stream cap、503 overload）、`Http2WaveClientIntegrationTest`、`Http2GoAwayClientIntegrationTest`、`Http2ProtocolConformanceIntegrationTest`、`Http1RequestFramingIntegrationTest`、`ForwardedHeaderIntegrationTest` 均通過。全量 `./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` 於 2026-09-09 通過（320 tests，0 failures/errors/skips）；含 JPMS、ArchUnit、real socket 與 Netty `PARANOID` ByteBuf leak detection，為 T1–T4 release evidence。

## 0.8 — 擴充穩定

**前置依賴：** 0.7。  
**狀態：** 進行中（0.7 release gate 已關閉；等待 immutable compatibility baseline）。

**元件：**

- parser/renderer/session/service SPI contracts。
- `ServiceLoader` provider discovery、SPI priority/conflict validator。
- external provider contract kit、API compatibility baseline、Revapi gate。

**完成 gate：** 獨立 provider verification、startup priority conflict、公開 API freeze 與二進位相容性檢查。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.8-T1 | `WaveProvider`、`SpiCatalog`、parser/renderer/session/service provider contracts、`ProviderContext` | exported `spi.*` only；`WaveApp.providers()`；service provider 每次 server run 建立 fresh service | each SPI family default最多128 providers；無 request-path discovery；priority ordering deterministic | 0.3 parser/service/session，0.7 module boundary | success：manual/discovered catalog、fresh service；application failure：factory/null metadata；cancellation：assembly/start failure；limit：provider cap；shutdown：service lifecycle reverse stop | 完成：unit + assembly tests |
| 0.8-T2 | `SpiProviders`、`SpiConfigurationException`、`spi.testing.ProviderContract` | ServiceLoader discovery，stable id/selection key/priority；external provider 可用 dependency-free kit 驗證 | one finite cap per contract；duplicate id或同 key/priority fail fast | 0.8-T1 | success：isolated external loader；application failure：bad resource/duplicate/conflict；deadline/cancellation：不適用，assembly-only；limit：cap；shutdown：無 worker | 完成：isolated dynamically compiled external-provider test與 conflict tests |
| 0.8-T3 | compatibility baseline、Revapi Maven gate、public API manifest | root `Wave`/`WaveApp`/`WaveServer`/`RunningServer`、`api.*`/`spi.*` compatibility only；runtime/netty/internal excluded | build-time only；baseline artifact/version must be immutable | 0.7 public contracts freeze | success：unchanged API；application failure：breaking removal；cancellation/limit/shutdown：不適用 | 進行中：`_spec/wave-api-compatibility.md` 與 exact、unqualified JPMS export/`uses`/transitive-requires test 已固定預發布邊界，並禁止 module `opens`/`provides` drift；profile 拒絕缺失、包含 SNAPSHOT/`LATEST`/`RELEASE` 的版本、非 Wave 或無法解析 baseline，CI safety job 驗證此 fail-closed 行為；隔離 old/new fixture 已驗證 `java.method.removed` 會阻擋 build。仍待不可變 release artifact 與固定座標的真正 CI gate |

**目前可查驗結果：** `ModuleDescriptorTest` 精確比對 JPMS public surface；`-Pcompatibility` 在沒有 baseline、`0.1.0-SNAPSHOT`、bare/版本 token 的 `LATEST`/`RELEASE`、非 Wave、非 SemVer 時於 `validate` 失敗，而 `io.wavejava:wave:9999.9999.9999` 則在 Revapi artifact resolution 失敗。其後 `./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` 通過（320 tests，0 failures/errors/skips）。這些證據只確認 boundary 與 fail-closed wiring，不構成已發布 binary baseline。

隔離 breaking-change proof：`./mvnw.cmd -B -ntp -f tools/revapi-break-proof/pom.xml clean install` 預期以非零結束，並輸出 `java.method.removed`；它只驗證 Revapi 對刻意移除 member 的反應，不取代 Wave 正式 baseline。

## 0.9 — Release Candidate

**前置依賴：** 0.8。  
**狀態：** 進行中（RC 基礎與 0.9-R0–R6 架構整理已完成；0.9-T2/T3 的長時間與簽署 release evidence 仍待 owner inputs）。

**元件：**

- Examples：`hello-api`、`forms-and-files`、`streaming-api`、`websocket-chat`、`gateway-api`。
- JMH benchmark、load/reliability suite。
- distribution assembler、consumer verification、release automation。
- migration guide、operations guide、security/dependency report。

**完成 gate：** 可重現 benchmark、long-running reliability、consumer verification、dependency/security review 與 release dry-run。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.9-T1 | `hello-api`、`forms-and-files`、`streaming-api`、`websocket-chat`、`gateway-api` examples | examples 只使用 frozen root／`api.*`／`spi.*`；不可引用 `runtime.*` 或 `netty.*` | 每個 example 使用顯式 server/client limits、timeouts、shutdown；不可加入 unbounded demo queue | 0.1–0.8 frozen API | success：各 example 可 build/run；application failure：documented error route；deadline/cancellation、limit、shutdown：各自有可執行 smoke scenario | 完成：五個 named-module consumer、local non-SNAPSHOT RC coordinate `0.9.0-rc.1` consumer verification helpers、CI build 與五個 bounded smoke main |
| 0.9-T2 | JMH benchmark、load/reliability suite、memory observation scripts | benchmark API 只讀；baseline metadata 記錄 JDK、OS、CPU、commit、JVM options | finite duration/concurrency/connection/heap/direct-memory observation；benchmark 不開 PARANOID leak detector | 0.4 Flow、0.5 long connections、0.7 H2 | success：small JSON、wait handler、upload/download、H2 multiplex、SSE/WS slow consumer；application failure、deadline/cancellation：request failure/cancel；limit/shutdown：reliability profile 可重現 | 進行中：`ReliabilitySmokeTest` 支援 `-Dwave.reliability.waves=1..600`；600-wave real-socket soak + `transport` PARANOID leak profile 已通過（322 tests，0 failures/errors/skips）；JMH metadata/memory scripts 完成；`WaveServerReliabilityTarget` 已完成 60 秒與 120 秒／32-concurrency real-socket workload（120 秒：8,389,504 requests，7,865,160 successes，524,344 failures），並明確設定 connection/in-flight/pending-byte/time budgets；排程 CI 已同步為 120 秒 workload + 30 秒 heap/native-memory sampling，且 fail-closed 要求六個取樣點、heap/NMT sections、正整數 requests/successes/failures；仍待完整長時間 RC evidence |
| 0.9-T3 | distribution assembler、consumer verification、release automation | consumer sample 只能依 published-style artifact；reproducible archive manifest；detached signature + SHA-256 verifier requires owner inputs | finite build/retry timeout；no secret in artifact/log | 0.8 immutable compatibility baseline | success：clean consumer build、SBOM/dependency report、release dry-run；application failure：missing/signature-invalid artifact must fail closed；deadline/cancellation：CI timeout abort；limit：finite build/retry/signature inputs；shutdown：CI cleanup | 進行中：assembler、local RC consumer verification、archive manifest/hash 驗證、SHA-256 sidecar helpers、consumer CI、unsigned rejection、invalid-signature/tamper CI proof、fail-closed `verify-release.ps1` 完成；signed/fixed-coordinate release dry-run 待 immutable baseline 與 owner key |
| 0.9-T4 | migration guide、operations guide、security/dependency report | versioned markdown docs；operation commands match actual Maven profiles | no runtime resource; report scope/version/date explicit | 0.1–0.8 completed behavior | success：reviewable install/operate/upgrade flow；application failure、deadline/cancellation：不適用，文件驗證任務；failure/limit/shutdown paths documented; security report has reproducible command | 完成：`docs/` 三份文件與 dependency/enforcer/transport commands；dependency tree 與 direct `enforcer:enforce` 於本機均 exit 0 |

### 0.9 RC 架構與命名修正任務

這組任務只整理 1.0 前的 public boundary 與名稱，不新增功能，也不建立相容 alias。每項任務
都必須在同一個 PR 內更新 production source、契約測試與文件；測試工具留在 test source。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 0.9-R0 | public type inventory、JPMS exports、naming map、dependency rules | 1.0 前最後一次可變更的 public type/export 清單 | build-time static checks only；不新增 runtime resource | 0.9 source tree | success：產生固定清單；application failure：重複或未登錄型別使檢查失敗；deadline/cancellation：不適用；limit：export/public type 受清單限制；shutdown：不適用 | 完成 |
| 0.9-R1 | `ClientRequestPool`、`ClientRequestPoolRejectedException`、`WaveClient.requestPool` | request concurrency pool 名稱與 builder/accessor 固定；Javadoc 明確說明不是 connection pool | concurrent request、waiting queue、request/response body、header budgets 沿用既有上限 | 0.9-R0 | success：請求可重用；application failure：closed/full pool rejects；deadline/cancellation：waiting request cancels and releases；limit：request/queue/body/header cap；shutdown：waiting work terminates clearly | 完成 |
| 0.9-R2 | `SseResponseInfo`、`ApplicationResult`、`RequestDispatcher`、`BlockingResultWaiter` | SSE open info 與 runtime names 不含舊名；waiter 位於 unexported `internal.client` | SSE header/line/event/reconnect caps；existing dispatcher lifecycle budgets | 0.9-R0 | success：取得 SSE opening info；application failure：error response/route result propagates；deadline/cancellation：open/idle/cancel checks；limit：header/line/event/reconnect；shutdown：SSE client/emitter/dispatcher stop | 完成 |
| 0.9-R3 | unexported `netty` client/WebSocket transport types、`WebSocketClientConfig` | `WaveClient`/`WebSocketClient` public signatures 不引用 Netty implementation | existing connection、stream、header、body、queue、shutdown caps | 0.9-R0、0.9-R1 | success：HTTP/1.1、HTTP/2、WebSocket；application failure：ALPN/handshake/protocol error；deadline/cancellation：connect/disconnect/RST/FIN；limit：admission/header/body/stream；shutdown：physical close、lease release、PARANOID leak | 完成 |
| 0.9-R4 | sealed `Response`、internal `InternalResponse`、`ResponseData`、`ResponseDataReader` | `Response` 只保留 status、headers、writers、lifecycle；移除 `Response.body()` 與 public `ResponseBody` | aggregate body、pending response、outbound byte budgets 沿用既有上限 | 0.9-R0、0.9-R3 | success：text/bytes/JSON/problem/stream/WebSocket；application failure：serialization/invalid upgrade；deadline/cancellation：stream cancel/disconnect；limit：aggregate/pending/outbound bytes；shutdown：stream/upgrade/abort | 完成 |
| 0.9-R5 | test-source `RequestFixture`、`EmbeddedApp`、`TestHttpClient`、`MockUpstream` | 不 export `api.testing`；不建立第二個 test-support artifact | fixture body/header/read/socket/shutdown limits 沿用既有上限 | 0.9-R0、0.9-R4 | success：fixture 可重現 request/response；application failure：錯誤可重現；deadline/cancellation：socket fixture 可驗證；limit：body/header/read cap；shutdown：embedded server/upstream closes；consumer：舊 `api.testing` 不可讀 | 完成 |
| 0.9-R6 | architecture/roadmap/compatibility/migration docs、naming/boundary validators | `api.*`/`spi.*` boundary、舊名稱不存在、public signature 不引用 internal/netty、任務欄位完整 | static validation only；不新增 runtime resource | 0.9-R0–R5 | success：四份文件與檢查工具通過；application failure：舊名/export/signature drift fail closed；deadline/cancellation：不適用；limit：task schema/export/type limits；shutdown：不適用 | 完成 |

**0.9 目前可查驗結果：** Wave build 已安裝至 local Maven cache，並以 `io.wavejava:wave:0.9.0-rc.1`
（由目前 source build 產生，非 immutable baseline）編譯五個 named-module consumer；`./mvnw.cmd -q -B -ntp
-f examples/pom.xml clean package "-Dwave.version=0.9.0-rc.1"` 與五個 smoke main（hello、forms/files、streaming、
WebSocket、gateway）均已在本機執行；
`./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Ptransport` 通過 322 tests（0 failures/errors/skips），
且在 Enforcer plugin-level configuration 修正後再次通過；根專案 `./mvnw.cmd -q -B -ntp verify` 亦 exit 0；
`./mvnw.cmd -q -B -ntp -pl wave -am clean verify -Preliability` 通過 322 tests（0 failures/errors/skips）；
同一 reliability profile 以 `-Dwave.reliability.waves=30` 執行的有限 30-wave soak 亦通過 322 tests（0 failures/errors/skips）；
`-Ptransport,reliability -Dwave.reliability.waves=600` 的 600-wave soak（76,800 requests）亦通過 322 tests（0 failures/errors/skips），並使用 PARANOID leak detection；
以 NMT 啟動非 forked JMH target 並取樣 20 秒（4 點）成功；heap committed 由 1,048,576 KB 至 1,515,520 KB，NMT total committed 由 1,231,264 KB 至 1,648,697 KB，JMH 兩次 measurement 為 3,458,502.811 ops/s；此為觀測管線證據，不等同 server 長時間 leak 結論；
`./mvnw.cmd -q -B -ntp -f tools/wave-benchmarks/pom.xml clean package` 與 JMH 一 warmup/一 measurement
執行成功（JDK 25，route lookup 約 3.38M ops/s；數值僅作同環境回歸基線）。`./mvnw.cmd -q -B -ntp -f tools/distribution/pom.xml clean package`
及 `tools/distribution/verify-archive.ps1` 亦通過（15 entries）。這些是 RC 基礎證據，不是 immutable
release 或 1.0 freeze。

0.9-R0–R6 收尾證據：`pwsh ./tools/roadmap/validate-roadmap.ps1`（36 筆任務）與
`pwsh ./tools/roadmap/validate-naming.ps1` 均通過；`Response` sealed/public boundary、
`api.testing` 移除、Netty client/WebSocket implementation 移至未 export package，以及五個
named-module examples 均以目前 source tree 驗證。這裡停止於 1.0 API stability 之前，不建立
正式 compatibility baseline。

External release inputs are documented in [`docs/release-owner-handoff.md`](../docs/release-owner-handoff.md);
until those inputs are supplied, 0.8-T3/0.9-T3 remain explicitly in progress.

跨平台 `run-baseline.ps1`／`run-baseline.sh` 會先建立 dependency classpath，再以 forked JVM 執行 JMH，
避免 Maven exec classloader 吞掉 fork failure；目前 Windows JDK 25.0.4 的 metadata-captured run
完成（約 3.38M ops/s；硬體與 JVM 變動時不得直接比較絕對值）。

固定 `wave.distribution.outputTimestamp` 後，Wave JAR 與 RC archive 在相同 source inputs 下各建置兩次
SHA-256 相同（JAR `BAB18ABF920D348066646151437BD2EEFA21B5231BBC88661D7DFC1D69F35065`；archive
`CB55F05FA7A03F90A7E08D813C28E9A1112C75133491BEA7C7C8E892DF850C88`）。這證明 repository-local
reproducible packaging，不等於已簽署或已發布的 immutable compatibility baseline。

## 1.0 — 穩定發布

**前置依賴：** 0.9。  
**狀態：** 未開始。

**元件：** SemVer/API compatibility policy、production operation guide、release checklist、signed distribution workflow。

**完成 gate：** 0.1–0.9 全數完成；無已知無界 resource path 或未處理 leak；公開 API、SPI、operations guide 與 compatibility suite 均已凍結。

| ID | 元件 | 公開契約 | resource budget | 前置依賴 | 驗收案例 | 狀態 |
|---|---|---|---|---|---|---|
| 1.0-T1 | SemVer policy、public API manifest、Revapi baseline/CI gate | root、`api.*`、`spi.*` 為唯一 compatibility surface；internal packages excluded | build-time only；baseline artifact immutable and checksummed | 0.8-T3、0.9 consumer verification | success：unchanged public API；application failure：breaking binary removal fails CI；cancellation/limit/shutdown：不適用 | 未開始 |
| 1.0-T2 | production operations guide、release checklist、signed distribution workflow | documented install/configure/TLS/limits/upgrade/rollback procedures | release timeout/retry/signing key handling explicit；no credential in repository | 0.9-T3/T4 | success：signed release dry-run；failure：bad signature/dependency policy fails closed；shutdown：operations guide has drain/force-stop steps | 未開始 |

## 不在 1.0 的範圍

Groovy adapter、DI container、template engine、annotation scanning、script hot reload 與 vendor-specific integration 均不屬於 1.0。第三方 observability、session 或 client provider 必須透過 SPI 提供，不得成為 core 必要依賴。

