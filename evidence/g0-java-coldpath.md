# G0 · Java 冷路径分段基准（BSL_v10.1.8）

> 由 `ColdPathBenchmark` 生成（G 系列第 0 关，`17-NATIVE.md` §5.1 / §7.2 口径）。

## 环境

- 机器/标签：G0-r9-caliber-fixed
- JDK：25.0.4.1
- OS：Linux amd64
- 备注：three-segment caliber: preprocessing folded into load segment; cross-check added
- 预热 3 次，样本 21 次（§7.1：预热 ≥3、样本 ≥5，取中位数而非最好一次）

## 输入（固定）

- 包：`BSL_v10.1.8`，kind=ZIP，源 `/home/yorigami/Minecraft/Shader-Core-Vulkan/run/shaderpacks/BSL_v10.1.8.zip`
- sha256：36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5
- program 数：91；待测阶段数：182
- ⚠️ 本表是**单包**口径。runClient 日志里的 `pack compile done: stages=N` 统计的是**整个库存目录**下的所有包，两者不可直接相减。
- golden 清单：`/home/yorigami/Minecraft/Shader-Core-Vulkan/build/bench-golden/BSL_v10.1.8/sha256sums.txt`，共 546 条，自身 sha256 `d34c5d02122cf9285c6028315f00d8b7d3ea2b69bee0cf588c386ecc4b45cb62`

## 一行复现

```bash
./gradlew compileTestJava
java -cp build/classes/java/test:build/classes/java/main \
    dev.vkdisp.pack.ColdPathBenchmark \
    --inventory /home/yorigami/Minecraft/Shader-Core-Vulkan/run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 21 \
    --out /home/yorigami/Minecraft/Shader-Core-Vulkan/evidence/g0-java-coldpath.md \
    --golden /home/yorigami/Minecraft/Shader-Core-Vulkan/build/bench-golden \
    --label G0-r9-caliber-fixed \
    --notes "three-segment caliber: preprocessing folded into load segment; cross-check added"
```

> **口径交叉校验**：分段三段合计 927.5ms vs 生产入口 932.2ms，差 +4.7ms（+0.5%）—— ✅ 口径自洽
## 分段数据

| 环节 | 中位数(ms) | p95(ms) | 最小(ms) | 最大(ms) | 样本 | 占合计 |
|---|---:|---:|---:|---:|---:|---:|
| 包扫描 | 0.6 | 0.8 | 0.4 | 0.9 | 21 | 0.1% |
| 加载与预处理 | 650.3 | 776.6 | 586.8 | 803.8 | 21 | 70.1% |
| 转译（8 段流水线） | 251.4 | 377.5 | 222.7 | 394.3 | 21 | 27.1% |
| 合计（分段三段） | 927.5 | 1032.5 | 818.9 | 1075.4 | 21 | 100.0% |
| 合计（生产入口） | 932.2 | 1097.7 | 837.0 | 1164.1 | 21 | — |

> 🔴 **口径（2026-10-02 修正）**：「加载与预处理」**已包含完整的 `GlslPreprocessor`（`#include` 展开 + 宏与条件编译 + 选项常量扫描）** —— 旧表另有一行「`#include` 预处理」把同一件事**又加了一遍**，并把加载段错叫成「properties/options 解析」，掩盖了重复计算。现在预处理不再单列，转译段的输入直接取自 `load` 交出的预处理产物。
> 预处理内部分解（①#include 展开 / ②宏与条件编译 / ③选项常量扫描）见 `--phase-timing`；它们是**「加载与预处理」的子集，不可与上表相加**。
> 生产入口含 zip I/O 与挂载规划，与分段三段的差值应接近这些额外开销；若差值很大，说明分段口径又漂了，应当复核。
> p95 取排序后下标 `ceil(0.95×N)−1`；样本 21 偏小时它就等于最大值，读作「尾延迟上界」而非稳定估计。
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
