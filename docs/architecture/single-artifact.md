# 單一 WaveIO artifact

WaveIO 對外只發布 `io.waveio:waveio`，JPMS module 為 `io.waveio`。過去以 Maven reactor 表示的
foundation、execution、task、http、Netty、server 與 testkit 現在只是同一 artifact 內的 package 分層。

公開 package 為 `io.waveio.registry`、`io.waveio.execution`、`io.waveio.task`、`io.waveio.http`、
`io.waveio.server` 與 `io.waveio.testkit`。`io.waveio.netty` 不 export，屬於 internal transport
implementation；application 不可直接依賴其 API 或 Netty 型別。

此整併降低 consumer dependency、JPMS `requires`、版本與 release artifact 的數量；編譯期依賴方向
改由 package visibility、non-exported internal package 與 `scripts/verify-release-gates.ps1` 維護。
