# Reliability suite

執行：`scripts/run-reliability.ps1 -Iterations 3`。此 runner 每輪重新執行下列受測項目，而非以 sleep
猜測非同步結果：

| 場景 | 證據 |
|---|---|
| 反覆啟停、真實 socket、慢速對端、chunked body、keep-alive | `PlaintextServerTest` |
| leak profile | Netty paranoid leak detection |
| cancellation/timeout、execution capacity | `ExecutionTerminationTest`、`ExecutionRuntimeGateTest` |
| blocking saturation、large/bounded Flow collection | `TaskTest`、`ByteBufferCollectorTest` |

本 suite 的結果僅代表執行當時所用的 JDK、OS、硬體與 iteration count；release 前必須重新執行並把
命令輸出納入 release dry-run evidence。
