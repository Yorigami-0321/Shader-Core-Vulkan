# 本机验证证据 — sha256 清单

生成时间: 2026-09-30 21:57:53
代码基线: b15f55e

> 本清单的哈希**按仓库内存储字节（LF 行尾）计算** —— clone 后直接复算即可比对一致。

## 单元测试执行结果（本机独立复现）

| 指标 | 值 |
|---|---|
| 测试类 | 39 |
| 用例总数 | 434 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 0 |

数据来源: `build/test-results/test/*.xml`（Gradle 原生 XML 结果）

## runClient 会话

| 项 | 值 |
|---|---|
| 启动 | 2026-09-30 20:52 |
| 结束 | 2026-09-30 21:00:59（`Stopping!` 正常退出）|
| 总时长 | 9m 11s |
| 退出码 | GRADLE_EXIT=0 / BUILD SUCCESSFUL |
| Vulkan 设备 | NVIDIA GeForce RTX 4060 Laptop GPU |
| 管线 | registered=8, compiled=8 (aligned) |
| vkdisp ERROR | 0 |

## 证据文件 sha256

| 文件 | 字节 | sha256 |
|---|---|---|
| ColorStats.java | 2995 | `14cfe9817a80078d14a473b36152539db810a6c30d753fd701eb204a424d8ee2` |
| SendKeyCombo.java | 4475 | `7afb079d6bc8a5e862b0e450f9761fc96c149f1907d2675814ca107a2e10018f` |
| WinShot.java | 2233 | `9bdf18d31dcf25eafce3dd70c124074bf5565c4ebacad7f6f94257ca0002a8f7` |
| mc-window-render.png | 82540 | `96c00e19d45dfd77a48426b1a7915c9f1101baa7225e1d504e9d38915f162247` |
| runclient-full.log | 68290 | `97e1a66a9c9e89d41419110a039cd1c1e46655dc30f5d68b85858d29634b9fee` |
