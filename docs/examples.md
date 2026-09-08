# WaveIO 最小範例

一般應用先以具名 profile 啟動；每個 profile 的容量與 timeout 都是可查閱且有界的不可變
`ServerOptions`。需要部署級調校時，再將完整 options 傳給 `WaveServer.start`。下列 endpoint 回傳延遲
的 `Task<HttpResponse>`，並以 `Responses.text` 建立 UTF-8 response：

```java
WaveApplication application = WaveApplication.builder()
    .routes(routes -> routes.get("/hello", context -> Task.success(Responses.text("hello"))))
    .build();

try (RunningServer server = WaveServer.start(5050, application)) {
    // server.address() contains the bound address
}
```

需要 blocking 工作時，使用明確容量的 `BlockingRuntime`，再由 `Task.blocking` 交回 execution；不可在
handler thread 直接等待。串流 request body 則是標準 `Flow.Publisher<ByteBuffer>`，consumer 必須按 demand
請求資料並可隨時 cancel。測試可透過 `EmbeddedServer.start(application)` 啟動使用 `TESTING` profile 的實際
public facade，並在 try-with-resources 結束時停止它。
