# G0 · Java 冷路径分段基准（BSL_v10.1.8）

> 由 `ColdPathBenchmark` 生成（G 系列第 0 关，`17-NATIVE.md` §5.1 / §7.2 口径）。

## 环境

- 机器/标签：AMD Ryzen 7 8745H  w/ Radeon 780M Graphics / Linux amd64
- JDK：25.0.4.1
- OS：Linux amd64
- 备注：pure-CPU compute, no GPU involved; laptop, power mode/freq not pinned (thermal noise possible); zip in OS page cache (warm I/O)
- 预热 3 次，样本 9 次（§7.1：预热 ≥3、样本 ≥5，取中位数而非最好一次）

## 输入（固定）

- 包：`BSL_v10.1.8`，kind=ZIP，源 `run/shaderpacks/BSL_v10.1.8.zip`
- sha256：36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5
- program 数：91；待测阶段数：182
- ⚠️ 本表是**单包**口径。runClient 日志里的 `pack compile done: stages=N` 统计的是**整个库存目录**下的所有包，两者不可直接相减。
- golden 清单：`build/bench-golden/BSL_v10.1.8/sha256sums.txt`，共 546 条，自身 sha256 `d34c5d02122cf9285c6028315f00d8b7d3ea2b69bee0cf588c386ecc4b45cb62`

## 一行复现

```bash
./gradlew compileTestJava
java -cp build/classes/java/test:build/classes/java/main \
    dev.vkdisp.pack.ColdPathBenchmark \
    --inventory run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 9 \
    --out evidence/g0-java-coldpath.md \
    --golden build/bench-golden \
    --notes "pure-CPU compute, no GPU involved; laptop, power mode/freq not pinned (thermal noise possible); zip in OS page cache (warm I/O)"
```

## 分段数据

| 环节 | 中位数(ms) | p95(ms) | 最小(ms) | 最大(ms) | 样本 | 占合计 |
|---|---:|---:|---:|---:|---:|---:|
| 包扫描 | 0.4 | 0.6 | 0.3 | 0.6 | 9 | 0.0% |
| properties/options 解析 | 681.3 | 843.1 | 582.7 | 843.1 | 9 | 43.2% |
| #include 预处理 | 549.7 | 664.0 | 509.8 | 664.0 | 9 | 34.9% |
| 转译（8 段流水线） | 236.8 | 275.0 | 224.9 | 275.0 | 9 | 15.0% |
| 合计（分段四段） | 1576.0 | 1604.5 | 1320.0 | 1604.5 | 9 | 100.0% |
| 合计（生产入口） | 1494.9 | 1646.3 | 1374.2 | 1646.3 | 9 | — |

> 生产入口含 zip I/O 与挂载规划，**与分段四段不可相加**。
> p95 取排序后下标 `ceil(0.95×N)−1`；样本 9 偏小时它就等于最大值，读作「尾延迟上界」而非稳定估计。
> 分段与生产路径的语义等价性已自检：逐阶段 `预处理→转译` 的产物与 `GlslPipeline.analyze` **逐字节相等**（不等则基准失真并终止）。

## 口径与判读（固定说明，随每次运行重写）

- **本机是笔记本且未锁电源/频率**，跨次运行的中位数漂移已达 **≈±9%**
  （多趟实测记录见 `CHANGE_LOG.md` 与 `evidence/` 的历次数据，**此处不写死具体数字** ——
  写死会让下一次运行把方法论说明和它自己的数据混在一起）。
  该噪声与 §5.2 的 **20% 裁决阈值同量级**，所以 G1/G3 必须：
  ① Rust 与 Java 两侧在**同一台机器上交替**测量；② 样本 ≥9；③ 同时报 p95；
  ④ **禁止用单次运行的最好值比值下结论**。
- 真正被替换的对象是**分段四段**（只含读入之后的计算）。生产入口那一趟含 zip I/O 与
  挂载规划，它给出的才是 B3/B4 关心的真实等待时长，两者**不可相加**。
