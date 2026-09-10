# wave 命名 Code Review

**日期：** 2026-09-10  
**範圍：** 0.1–0.9 production API、internal runtime/transport 與 test fixture。  
**結論：** 0.9-R0–R6 已完成；本文件記錄已採用的名稱，不再保留舊名 alias。

## 已定案的名稱

| 名稱 | 責任 | 備註 |
|---|---|---|
| `ClientRequestPool` | 限制同時進行的 client request | 不是 connection pool；使用 `maximumConcurrentRequests`、`leasedRequests`、`requestPool(...)` |
| `SseResponseInfo` | 已開啟 SSE response 的 status/header/URI 資訊 | 不代表完整 response body |
| `ApplicationResult` | application dispatch 的不可變結果 | `Response`、`Outcome`、route pattern |
| `RequestDispatcher` | 執行一次 request dispatch 的 internal contract | 與 `ApplicationResult` 分開表達動作與結果 |
| `BlockingResultWaiter` | client blocking API 的 internal 等待工具 | 位於未 export 的 `internal.client` |
| `InternalResponse` | `Response` 的唯一 framework implementation | 位於未 export 的 `internal.http` |
| `ResponseData` | transport 讀取的內部 payload representation | 不屬於 public HTTP model |

## 保留的常用名稱

- `WebSocket`、`WebSocketSession`、`WebSocketConnection`：分別代表 endpoint、邏輯連線與實體連線。
- `FormData`、`MultipartForm`、`Upload`：分別代表 URL-encoded 表單、multipart 容器與檔案 part。
- `Config`、`Registry`：設定來源與 typed service lookup，責任不同，不合併。
- `ServerLimits`、`Http2Config`、`WebSocketLimits`、`HealthLimits`：各自限制不同資源，不合併成一個總設定。

## 命名規則

1. 先說明 domain，再說明角色，例如 `WebSocketConnection` 優於 `Connection`。
2. `Pool` 只用在資源池；若實際限制的是 request，名稱必須包含 `Request`。
3. `Body` 只代表 application payload；transport 的狀態與 upgrade 資訊留在 internal type。
4. endpoint 只使用固定的 `Handler` 形態，不新增 `AsyncHandler`、`Resource` 或 `Controller` facade。
5. 新 public type 必須同時列入 roadmap 與 compatibility surface；只供實作使用的 type 放在未 export package。
6. 新名稱使用一般 Java 開發者熟悉的字詞，不為了抽象一致而加入 `Manager`、`Coordinator`、`Controller` 等泛稱。

## 檢查方式

`pwsh ./tools/roadmap/validate-naming.ps1` 會檢查：舊名稱不存在於 production source、必要檔案存在、
`api.testing` 未 export、`java.net.http` 非 transitive、public signature 不引用 internal/netty type，
以及 `ClientRequestPool` 的第一行 Javadoc 是否清楚說明 request pool 與 connection pool 的差異。
