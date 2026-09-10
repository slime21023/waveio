# wave 架構 Ponytail review

**日期：** 2026-09-10  
**範圍：** 0.1–0.9，停止於 v1.0 API stability/freeze 之前。  
**方法：** 以目前 source tree、`module-info.java`、ArchUnit/JPMS tests 與 roadmap 對照；只保留已被 roadmap 或安全性需要證明的設計。

型別與元件命名另見 [`wave-naming-review.md`](wave-naming-review.md)；本文件只處理結構、依賴與
責任分層。

## 結論

核心執行模型是清楚且一致的：

```text
Wave → WaveApp → Routes → Middleware → Handler
                         ↓
                 Request / Response
```

Virtual Thread 負責 application invocation，Netty 負責 protocol/connection，所有可累積資源
都有 count、byte 或 time budget。這些是架構的必要骨架，應保留。

目前不符合「容易理解」的地方不是功能缺失，而是邊界和文件把太多 optional capability 放在
同一個心智模型中。現況量測為 203 個 main Java files、26,623 行，其中 `api.*` 有 154 個
files、18,656 行；這不代表每個元件都多餘，但表示使用者不應被要求一次理解整個 public
surface。

## 符合設計的部分

| 區域 | 證據 | 判定 |
|---|---|---|
| Core API | `Wave`、`WaveApp`、`WaveServer`、`Request`、`Response`、`Routes`、`Handler`、`Middleware` | 符合；主路徑只有一種 handler 形態 |
| 執行邊界 | `runtime.*`、`netty.*`、Virtual Thread invocation、EventLoop tests | 符合；application code 不應在 EventLoop 執行 |
| 資源安全 | request/connection/queue/byte/time limits、cancellation、shutdown tests | 符合；不可為了簡單而移除這些限制 |
| JPMS | module 只 export root、`api.*`、`spi.*`；runtime/netty/internal 未 export | 符合公開可見性目標 |
| Roadmap 功能 | client、Flow、multipart、SSE、WebSocket、session、operations、HTTP/2 | 不是多餘；它們是使用者明確核准的 0.1–0.9 範圍 |

## 確認的設計問題

### 1. API package 實際承載 Netty implementation

以下四個 implementation 已移到未 export 的 `io.wavejava.wave.netty`：

- `netty/NettyClientTransport.java`
- `netty/NettyHttp2ClientTransport.java`
- `netty/NettyWebSocketClientTransport.java`
- `netty/WebSocketClientSession.java`

它們維持 internal scope，故一般 consumer 不能直接呼叫；`WaveClient` 與 `WebSocketClient` 的
public signature 也不再出現 Netty type。

**結果：** 0.9-R3 已移到未 export 的 `io.wavejava.wave.netty`；沒有新增公開 transport API。

### 2. `ResponseBody` 把 transport state 放進 public HTTP model

`api.http.ResponseBody` 是公開的五態 union，包含 `STREAM` 與 `WEBSOCKET`；`Response` 的使用者
其實只需要 `text/json/bytes/problem/stream/webSocket` writer。`PreparedResponse` 才需要判斷
body kind。`WEBSOCKET` 也讓一般 HTTP model 直接依賴 WebSocket model。

**結果：** 0.9-R4 已將 payload union 改為未 export 的 `ResponseData`，`Response` 改為 sealed
control surface，只保留明確 writer 與 lifecycle methods；transport 只經 `ResponseDataReader` 讀取。

### 3. Testing fixtures 被當成 production public API

`api.testing` 被 JPMS export，包含 `RequestFixture`、`EmbeddedApp`、`TestHttpClient`、
`MockUpstream`；目前 examples 不依賴它們，主要 consumer 是 tests。這會把測試 helper 永久
帶入 binary compatibility surface。

**結果：** 0.9-R5 已移到 `wave/src/test/java/io/wavejava/wave/testing`，取消 `api.testing`
JPMS export；不建立第二個 test-support artifact，也不把測試工具放入 compatibility surface。

### 4. 文件把 optional capability 和 core 混在同一層

文件目前同時描述 core request path、client、multipart、SSE、WebSocket、session、health、
observability、SPI，造成「四個概念」與實際 20 個 API subpackages 不一致。另有可修正的文件
漂移：tree 仍列 `api/body` 與 `wave/src/.../jmh`，但目前 `Body` 在 `api/http`，benchmark
位於 `tools/wave-benchmarks`；`api/observability` 已 export，卻在分層表列作 internal。

**決策：** 使用下列五層心智模型；詳細契約留在本文件後半與 roadmap，不再把每個 class 當成
一般使用者必學概念：

```text
Core       Wave / App / Server / Request / Response / Routes / Middleware
Web        render / form / file / cookie
Transport  client / Flow / multipart / SSE / WebSocket / HTTP/2
Operations session / health / observability / resilience / config
Extension  SPI + testing support（主要給 framework/provider 作者）
```

## Ponytail 刪除／保留決策

### 保留：有明確需求或安全理由

- feature-specific limits（`ServerLimits`、`Http2Config`、`WebSocketLimits` 等）：不同資源
  具有不同單位與失敗語意，合成一個 global options object 會更難理解。
- `Config` 與 `Registry`：前者是字串設定與 provenance，後者是 typed framework service；
  不合併成 service locator 或 DI container。
- `CompletionStage` 與 JDK `Flow`：不新增 Promise、reactive wrapper 或 async handler hierarchy。
- client retry/redirect policy、SSE/WebSocket session、HTTP/2 stream state：roadmap 明確要求，
  不是可任意刪除的 speculative feature。

### 不新增：YAGNI boundary

- `Resource` base class、annotation route scanner、`AsyncHandler`、自有 Promise、coroutine DSL。
- global `WaveConfig`、magic auto-registration、隱式 DI、通用 event bus。
- 為了「未來拆 module」先建立 facade/factory/interface；只有實際有第二個 implementation
  或 external provider 時才增加 seam。

### 1.0 前應移除或降級的候選

這些項目目前不是 roadmap 的 runtime capability，但會擴大 public surface：

1. `ResponseBody` public union 與其 transport variants（已完成）。
2. `CompletionStageAwaiter`（已改為未 export 的 `BlockingResultWaiter`）。
3. `api.testing` production export（已移除）。
4. exported API package 內的 Netty implementation classes（已移出）。

上述項目由 0.9-R0–R6 的靜態檢查與 contract/transport tests 保護；這仍不是 1.0 API freeze。

## 清晰版使用規則

一般 application author 只需先學 Core；遇到需求再進入一個 capability layer：

```text
建立 API       → Core
解析/輸出資料  → Web
呼叫或傳輸串流 → Transport
部署與營運    → Operations
寫 provider    → Extension
```

這個分組不改變 single Maven artifact，也不把 optional feature 偷拆成未核准的 modules；它只
把學習順序與 compatibility scope 說清楚。

## 審查後的 gate

在 v1.0 API stability/freeze 前，必須完成上述四項 boundary cleanup，並以 JPMS/ArchUnit、
consumer compile、contract tests 與 transport tests 重新驗證。若沒有第二個實作或外部
consumer 的證據，不再新增 abstraction。0.1–0.9 已核准的功能仍照 roadmap 開發；不因
Ponytail review 而刪除明確承諾的能力。
