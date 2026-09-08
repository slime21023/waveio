# wave 1.0 Roadmap

**狀態：** Architecture Baseline  
**更新日期：** 2026-09-08  
**權威性：** 本文件是版本、任務與驗收狀態的唯一來源；[wave-architecture.md](wave-architecture.md) 定義架構與不變條件。

## 共同規則

wave 為全新重建專案。已刪除的 `io.waveio` 程式、測試、release 結果與 API 均不具相容性或完成證據效力，最多只能作為測試情境參考。Maven 座標固定為 `io.wavejava:wave`，JPMS module 固定為 `io.wavejava.wave`。

每項任務必須在合併前列出：所屬版本、元件、公開契約、前置依賴、count/byte/time budget，以及成功、application failure、deadline/cancellation、超額限制與 shutdown 的驗收案例。不適用的案例必須說明原因；任務不得跨版本交付未完成的下游能力。

## 測試層與命令

| Gate | 觸發時機 | 必要內容 | 命令（bootstrap 後） |
|---|---|---|---|
| PR | 每個變更 | compile、JPMS export、ArchUnit、unit、contract、deterministic fixture | `./mvnw -B -ntp verify` |
| transport | transport/TLS/streaming/HTTP2/WebSocket task | 真實 socket、disconnect、slow peer、limit、shutdown、PARANOID leak detector | `./mvnw -B -ntp verify -Ptransport` |
| reliability | 排程與 RC | 長時間連線、慢 consumer、取消、heap/direct-memory、load、JMH | `./mvnw -B -ntp verify -Preliability` |

測試不得以任意 `sleep` 推測並發結果；deadline、排程與取消必須透過可控 clock、probe 或 latch 驗證。benchmark 僅記錄可重現基線與回歸，不預先承諾吞吐或延遲數字。

## 0.1 — 可用核心

**前置依賴：** 無。  
**狀態：** 未開始。

**元件：**

- Bootstrap：`module-info.java`、`Wave`、`WaveApp`、`WaveServer`。
- HTTP API：`Request`、`Response`、`Body`、`Headers`、`Cookie`、`MediaType`。
- Pipeline：`Handler`、`Middleware`、`Outcome`、`HttpException`、`ExceptionMapper`。
- Routing：`Routes`、`RouteMatch`、`RouteMetadata`、compiled route tree。
- Testing：`RequestFixture`、`EmbeddedApp`、`TestHttpClient`。
- Internal transport：HTTP/1.1 adapter、`RequestDispatchHandler`、`ResponseWriteHandler`。

**完成 gate：** bounded aggregated request body、route/method matching、404/405/`Allow`、middleware unwind、exception mapping、JSON/text/bytes response，以及基本 HTTP/1.1 socket 測試。`Body` 只可讀一次；不包含 streaming、multipart upload、HTTP client 或 TLS。

## 0.2 — 執行正確性與 transport 安全

**前置依賴：** 0.1。  
**狀態：** 未開始。

**元件：**

- Execution：`RequestContext`、`CancellationToken`、`Deadline`、`InvocationRuntime`、`RequestInvocation`。
- Limits：`ServerLimits`、`ServerTimeouts`、`PendingResponseBudget`、`ConnectionSequencer`。
- Lifecycle：`ShutdownCoordinator`、connection lifecycle manager。
- Transport：`TlsConfig`、compression adapter、Virtual Thread dispatcher。
- Testing：`ExecutionHarness`、deterministic clock、cancellation probes、leak test fixture。

**完成 gate：** handler/middleware 永不在 Netty EventLoop 執行；deadline、disconnect、cancellation、HTTP/1.1 response ordering、TLS、compression、graceful shutdown、slow peer 與 ByteBuf leak 都有 transport test 與 resource-bound assertion。

## 0.3 — Web 基礎能力

**前置依賴：** 0.2。  
**狀態：** 未開始。

**元件：**

- Rendering：`Parser<T>`、`Renderer<T>`、`Rendered`、`RenderContext`、content negotiation resolver。
- Forms/files：`FormData`、`UrlEncodedFormParser`、`FileResponse`、`StaticFileHandler`、`Range`、conditional request evaluator。
- Application services：`Config`、`ConfigSource`、`ConfigBinder`、config provenance、`Registry`、`Service`、`ServiceContext`、`ServiceLifecycle`。
- Testing：cookie codec tests、static-file fixture、config/lifecycle fixture。

**完成 gate：** content negotiation、URL-encoded form、cookie、config source/provenance、service startup rollback、static file traversal 防護、ETag/Last-Modified/Range。multipart/file upload 明確不屬於本版。

## 0.4 — Client 與資料流

**前置依賴：** 0.3。  
**狀態：** 未開始。

**元件：**

- Streaming：`BodyPublisher`、`Response.stream(Flow.Publisher<ByteBuffer>)`、`FlowBridge`、`FlowSubscriptionController`、outbound byte budget。
- Async：`CompletionStageAwaiter`、deadline/cancellation bridge。
- Client：`WaveClient`、`ClientRequest`、`ClientResponse`、`ClientPool`、`RetryPolicy`、`BackoffPolicy`、redirect/proxy policy。
- Upload：`MultipartParser`、`MultipartPart`、`Upload`、temporary-file manager。
- Testing：`MockUpstream`、Flow demand/cancellation probes、upload cleanup fixture。

**完成 gate：** Flow demand、`ByteBuffer` ownership、slow consumer、stream cancellation、client connection reuse/timeouts、retry safety、streaming multipart upload 與 temporary-file cleanup 都需 contract 與 integration test。

## 0.5 — 長連線

**前置依賴：** 0.4。  
**狀態：** 未開始。

**元件：**

- SSE：`SseEvent`、`SseEmitter`、`SseClient`。
- WebSocket：`WebSocket`、`WebSocketSession`、`WebSocketMessage`、`WebSocketClient`。
- Runtime：heartbeat、close-handshake、per-subscriber outbound budget controller。
- Testing：`TestSseClient`、`TestWebSocketClient`。

**完成 gate：** SSE event/id/retry，WebSocket text/binary/ping/pong/close、frame/message limits、慢 client、disconnect 與資源回收。

## 0.6 — Microservice Operations

**前置依賴：** 0.5。  
**狀態：** 未開始。

**元件：**

- Session：`Session`、`SessionId`、`SessionStore`、`InMemorySessionStore`、signed-cookie codec、rotation/expiry policy。
- Health：`HealthCheck`、`HealthStatus`、liveness/readiness endpoint。
- Observability：`AccessLogEvent`、`MetricsBridge`、`TracingBridge`、`PrometheusBridge`。
- Resilience：`RateLimitPolicy`、`BulkheadPolicy`。

**完成 gate：** session expiry/rotation、readiness lifecycle、success/failure/cancellation observation、超額流量拒絕與 policy resource budget。

## 0.7 — HTTP/2、安全與公開位址

**前置依賴：** 0.6。  
**狀態：** 未開始。

**元件：**

- HTTP/2 server/client transport adapters、`Http2Config`、stream lifecycle manager、connection/stream flow-control budget。
- ALPN adapter、`PublicAddress`、`ForwardedHeaderPolicy`、trusted-proxy policy。
- Request-smuggling/conformance suite、HTTP/2 protocol fixture。

**完成 gate：** multiplexing fairness、stream reset/cancellation、connection/stream windows、ALPN、trusted forwarded headers、安全與 overload cases。

## 0.8 — 擴充穩定

**前置依賴：** 0.7。  
**狀態：** 未開始。

**元件：**

- parser/renderer/session/service SPI contracts。
- `ServiceLoader` provider discovery、SPI priority/conflict validator。
- external provider contract kit、API compatibility baseline、Revapi gate。

**完成 gate：** 獨立 provider verification、startup priority conflict、公開 API freeze 與二進位相容性檢查。

## 0.9 — Release Candidate

**前置依賴：** 0.8。  
**狀態：** 未開始。

**元件：**

- Examples：`hello-api`、`forms-and-files`、`streaming-api`、`websocket-chat`、`gateway-api`。
- JMH benchmark、load/reliability suite。
- distribution assembler、consumer verification、release automation。
- migration guide、operations guide、security/dependency report。

**完成 gate：** 可重現 benchmark、long-running reliability、consumer verification、dependency/security review 與 release dry-run。

## 1.0 — 穩定發布

**前置依賴：** 0.9。  
**狀態：** 未開始。

**元件：** SemVer/API compatibility policy、production operation guide、release checklist、signed distribution workflow。

**完成 gate：** 0.1–0.9 全數完成；無已知無界 resource path 或未處理 leak；公開 API、SPI、operations guide 與 compatibility suite 均已凍結。

## 不在 1.0 的範圍

Groovy adapter、DI container、template engine、annotation scanning、script hot reload 與 vendor-specific integration 均不屬於 1.0。第三方 observability、session 或 client provider 必須透過 SPI 提供，不得成為 core 必要依賴。
