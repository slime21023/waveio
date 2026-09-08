# 架構與 API 方向

本文件定義 WaveIO 的目前架構；實作進度只記錄在 [ROADMAP.md](ROADMAP.md)，public boundary
記錄在 [API compatibility baseline](docs/API_COMPATIBILITY.md)。

## 產品邊界與設計來源

WaveIO 是 Java 25+ 輕量 HTTP 應用框架。底層 execution 與 Task 可以獨立使用和測試，
但主要產品仍是 HTTP 應用開發體驗。

承接 Ratpack 的三項核心理念：

- 用 execution 表達跨越多個非同步步驟的邏輯操作。
- 用 handler 的委派與子鏈組合 request 處理流程。
- 用型別化 registry 提供依賴及局部擴充，讓應用自行選擇業務程式碼的組織方式。

參考 [Ratpack 架構](https://ratpack.io/manual/current/architecture.html) 與
[Execution 契約](https://ratpack.io/manual/current/api/ratpack/exec/Execution.html)。
WaveIO 不依賴 Ratpack 套件，也不沿用其完整 Promise API。

## 分層與依賴

以下是單一 `io.waveio` artifact 內的責任 package，不是各自發布的 Maven 模組。

| 責任層 | 負責內容 | 可依賴的內部契約 |
|---|---|---|
| 基礎契約 | 型別化 key、registry、必要的共用值型別 | 無 |
| Execution runtime | 排程、segment、context、deadline、取消、清理 | 基礎契約 |
| Task 與串流 | 單一結果組合、blocking／CompletionStage 橋接、Flow demand | 基礎契約、execution |
| HTTP 與 handler | Request／Response／Body、Handler／Context／Chain、routing | 基礎契約、execution、Task／串流 |
| Netty server 整合 | HTTP codec 接線、連線狀態、TLS、body 傳輸、server lifecycle | 前述各層 |
| 應用組裝與擴充 | 啟動 API、設定、service lifecycle、rendering、觀測 hooks | 已完成的下層公開契約 |

依賴只能由上層功能指向其下層契約，不形成循環。Execution、Task 與基礎契約只依賴 JDK；
HTTP／handler 契約不依賴 Netty。Netty 依賴限制在 server 整合層，核心不用 channel 或
event loop 型別描述 execution。

測試工具逐層建立：execution harness、Task／Flow probes、handler fixture、server
integration fixture。低層測試工具不得為了方便而依賴 server。

## Execution 與 context

Execution 擁有一次邏輯操作的生命週期。HTTP request 會建立 execution；非 HTTP 工作
也可由 runtime 明確啟動 execution。

- 同一 execution 的 segment 循序執行，不同 execution 可以並行。
- 非同步等待期間讓出執行緒；completion 由 runtime 排程成新的 segment。
- 使用有界的排程資源；排隊容量、拒絕行為、deadline 與清理責任必須在實作前寫成契約。
- 正常完成、失敗、取消與 timeout 競爭同一終止決策；晚到結果不能重新啟動已終止的操作。
- Execution 統一觸發資源清理；每個已登記的清理動作只執行一次，清理失敗也必須可觀測。

`ScopedValue` 用於綁定當前 segment 的 execution context。它的 binding 是 per-thread，
runtime 必須在每次 segment 及受管理的 blocking 工作入口明確綁定，出口離開作用域。
不假設一般 executor 或 CompletionStage 自動傳播 binding。傳入 blocking 工作的 context
只提供安全的讀取視圖，不能從其他執行緒直接修改 handler 或 response 狀態。
參見 [Java 25 ScopedValue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html)。

## Task、blocking 與串流

### 單一結果：Task

`Task<T>` 是延遲啟動的計算描述。建立及組合 Task 不執行工作；由受管理的終端入口啟動，
每次啟動建立獨立的執行狀態，不隱含快取或共享結果。

公開能力涵蓋值轉換、Task 串接、錯誤恢復、timeout、清理與 CompletionStage 互通。`Task.start(runtime)`
會回傳 `TaskHandle`；其 completion 可供觀察，且取消只影響該次啟動所建立的 execution。
WaveIO 管理的 continuation 必須回到所屬 execution，不直接在任意外部 completion
執行緒上存取 request 狀態。

匯入既有 CompletionStage 時，它可能早已啟動；延遲語意只涵蓋 WaveIO 自己建立的工作。
匯出為 CompletionStage 後，呼叫端自行附加的 callback 遵循 JDK 語意，不保證保留
WaveIO context；必須重新橋接才能恢復受管理的 execution。
參見 [CompletionStage 契約](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletionStage.html)。

### Blocking 邊界

Blocking API 將工作送到虛擬執行緒，結果重新排程回 execution。Netty event loop 與
execution compute segment 不執行 blocking I/O。CPU 密集工作仍需受限制的排程資源，
虛擬執行緒不是 CPU 平行度或背壓的替代品。

限制同時進行的 blocking 工作及等候數量，容量不足時回報明確失敗。取消採協作方式：
嘗試中斷 runtime 擁有的工作，停止交付失效結果；不承諾強制中止不理會中斷的程式碼，
也不承諾取消任意第三方 CompletionStage 的底層操作。

### 多筆資料：Flow

以 JDK `Flow` 表達 demand、訂閱與取消，HTTP body 使用相同串流基礎。
Task 表示單一結果，Flow 表示多筆資料；不建立完整 reactive operator 框架。

每個累積資料的邊界都必須明確指出 owner、容量及超額行為。取消或斷線會停止需求並釋放
已持有的 buffer。Buffered body 是有上限的串流收集操作，不能形成第二套 request runtime。

## HTTP 組合與網路整合

### Application facade 與受控擴充

`WaveApplication` 是一般使用者的組裝入口。其 builder 只開放五個受控方向：registry services、
routes（含 middleware）、server lifecycle services、observers 與 sole error handler。`WaveExtension`
可依註冊順序套用同一組 builder API，因此擴充不必取得 Netty、execution dispatcher 或 response
transaction 的存取權。

高階 `Endpoint` 接收唯讀 `EndpointContext` 並回傳 `Task<HttpResponse>`；它不能提交第二個 response，
也不需呼叫 chain。`Routes` 會在 Task 成功後一次性提交 response，並在提交前交由 `ErrorHandler` 轉換
endpoint 失敗。低階 `Handler`／`Context`／`Chain` 保留給 middleware adapter、測試工具與需要委派或
插入子鏈的進階情境。

`WaveServer.start(port, application)` 使用 DEVELOPMENT profile。`TESTING` 與 `PRODUCTION` profile
同樣是具名、可檢視、固定有界的 `ServerOptions`；部署方可改以完整 options 明確覆寫，沒有隱性無界配置。

`Handler` 使用 `Context` 存取 request、response、registry 與 execution；`Chain` 組合
handler。流程可以產生回應、委派下一個 handler，或插入局部子鏈。非同步工作納入 Task
生命週期，避免由未受管理的背景 callback 繼續修改 response。

Registry 提供型別化查找。Server registry 是應用根作用域；request 與插入的子鏈可以建立
局部覆蓋。子鏈結束後恢復外層查找視圖，不能污染其他 request 或後續不屬於子鏈的 handler。
Registry 不等同於完整 DI 容器，不自動掃描應用類別。

Routing 建立在 handler 組合之上；先驗證 method、path、參數、未匹配與錯誤流程，再接上網路。
同一 request 只有一條 response lifecycle，必須防止重複提交。Response 已送出後發生錯誤，
由 server 終止傳輸或連線並記錄原因，不能再寫第二個錯誤回應。

```text
Socket / TLS
    → Netty HTTP codec
    → WaveIO request + execution
    → Handler / Chain / Routing
    → Task / Flow response
    → Netty write / flush
```

Netty server 整合層管理 connection state、read/write 背壓及 buffer 釋放；向 execution
交付資料與向 Netty 回寫時，明確經過各自的排程邊界。應用不接觸 `ByteBuf`、channel 或
reference counting。使用 Netty 既有 codec 與 TLS 能力，不重新實作 HTTP parser 或 TLS。
參見 [Netty 使用指南](https://netty.io/wiki/user-guide-for-4.x.html)。

首版支援 HTTP/1.1 與 TLS。連線中止、body 消費失敗、request timeout 與 server shutdown
必須連動到 execution 取消及資源釋放。Server 啟動完成必要 service 初始化後才接受流量；
關閉時先停止接收新工作，再等待既有工作，期限到達後取消剩餘工作並清理資源。

## 現代 Java 編程方向

- Records 表達不可變資料；對 array、buffer 等可變內容仍需明確的 ownership 契約。
- Sealed types 與 pattern matching 用於封閉的內部狀態／結果；使用者需要實作的擴充介面
  保持開放。
- 使用 JDK functional interfaces；只有 checked exception 等具體需求才新增介面。
- Java 25 為最低編譯基線，不要求 preview。Java 25 的 `StructuredTaskScope` 仍屬 preview，
  因此不納入核心依賴。[API 狀態](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html)
- 後續 JDK 的支援以 CI 實測為準，不以「25+」宣稱所有未來版本已驗證。

以下僅示意應用層的 Task 組合風格；名稱與簽章尚未實作，不能直接編譯執行：

```java
record Greeting(String message) {}

Task<Greeting> greeting(String userId) {
    return Task.blocking(() -> directory.lookup(userId))
        .map(user -> new Greeting("Hello, " + user.name()));
}
```

此計算由 handler 交給受管理的回應流程啟動；lookup 在虛擬執行緒執行，map 回到 execution。
`directory` 代表應用提供的依賴；record 的自動 JSON rendering 不屬於首版內建承諾。

## 固定決策與後續細化

本階段固定：Java 25 正式特性、Netty、受管理的非同步 execution、精簡 Task、虛擬執行緒
blocking 橋接、JDK Flow、型別化 registry、HTTP/1.1 首版及不保留舊 API 相容層。

各里程碑在實作前補齊該層的公開簽章、容量預設與錯誤契約，透過其測試工具驗證後再讓
上層依賴。這些是後續里程碑的交付工作；本次不預建空介面、不選定套件發布版本，
也不把未經驗證的草案視為穩定 API。
