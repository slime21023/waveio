# Benchmark 方法

執行 `scripts/run-benchmarks.ps1 -Iterations 5`。runner 將 execution/Task 的微型 workload 與 Netty
real-socket HTTP workload 分開量測，並把 JDK、OS、CPU、邏輯處理器、明確 resource config、iteration
count 與毫秒結果輸出到 `target/benchmark-report.md`。

這些結果僅供同一環境的趨勢比較；它們不是跨機器的吞吐或延遲宣稱。任何 release note 的數字必須附帶
該次產生的 report。
