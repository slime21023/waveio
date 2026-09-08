# WaveIO 最小範例

所有 server 容量與 timeout 都需要由應用程式明確設定。下列 API handler 回傳延遲的 `Task`，並以
`Responses.text` 建立 UTF-8 response：

```java
Handler hello = context -> {
    context.respond(Responses.text("hello"));
    return Task.success(null);
};
```

需要 blocking 工作時，使用明確容量的 `BlockingRuntime`，再由 `Task.blocking` 交回 execution；不可在
handler thread 直接等待。串流 request body 則是標準 `Flow.Publisher<ByteBuffer>`，consumer 必須按 demand
請求資料並可隨時 cancel。測試可透過 `EmbeddedServer.start(spec)` 啟動實際 public facade，並在 try-with-
resources 結束時停止它。
