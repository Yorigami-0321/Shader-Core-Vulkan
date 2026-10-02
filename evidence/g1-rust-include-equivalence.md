# G1 · Rust 等价实现 —— `#include` 展开相（inc）逐字节一致性

> 对应 `17-NATIVE.md` §5.1 的 **G1 关卡**：「必须通过输出一致性测试 —— 同一输入，
> Rust 与 Java 产物逐字节/逐 token 等价（不一致 ⇒ 谈不上性能对比，先修等价）」。
> 本文件只证明 **inc 相**，即 G1 的第一段。define 相与转译相未做。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（与 G0 同一台，两侧同机才有可比性） |
| Java 侧 | JDK 25.0.4.1（`vkdisp` 本仓库） |
| Rust 侧 | `rustc 1.99.0 (b940084d7 2026-09-28)` / `cargo 1.99.0 (5f94df478 2026-08-27)` |
| 对照工程 | `~/Minecraft/g1-rust-bench`（**仓库外**，commit `2086d22`） |

## 合规核对（D18 / `07-CONSTRAINTS` L12 第 0 步）

| 项 | 结论 |
|---|---|
| 第三方 crate | **零** —— `Cargo.toml` 的 `[dependencies]` 为空，只用 Rust 标准库 |
| 移植来源 | vkdisp **自研** `dev.vkdisp.glsl.preprocess.IncludeProcessor`（MIT，同一作者的工程） |
| 是否并入他人代码 | **否**。逐行对照移植自家 MIT 代码 |
| 是否接入 vkdisp 构建 | **否**。不碰 `build.gradle`、不建 `accel/`，符合 G 线「裁决『采用』前不配 cargo/CMake」 |

> 零依赖是刻意的：每个 crate 都要单独核许可证（L12），而 include 展开只需要字符串与集合。

## 输入（固定）

| 项 | 值 |
|---|---|
| 包 | `BSL_v10.1.8`（sha256 `36b0a50ff7918bf10e9422c401d93779f9d0088a26acea975b435243f96930b5`） |
| 待测阶段 | **182**（91 program × VERTEX/FRAGMENT） |
| 输入契约 | `build/g1-input/BSL_v10.1.8/`，292 个文件 |
| ↳ `stages.txt` | sha256 `6143581377d4bd445c45024586a78ccb43f676ec315a0dfb44289fe8094abb76` |
| ↳ 文件清单 | sha256 `513ab316829879538cfc01236b268319d92c580eb64e81ab5950752292eb59b7` |
| golden 清单 | `build/bench-golden/BSL_v10.1.8/sha256sums.txt`，**546** 条（182 × 3 相：inc/pre/trans） |
| ↳ 清单 sha256 | `d34c5d02122cf9285c6028315f00d8b7d3ea2b69bee0cf588c386ecc4b45cb62` |

### 源码指纹

| 文件 | sha256 |
|---|---|
| `g1-rust-bench/src/include_processor.rs` | `d85786b96db2d20e7a3a605c0af23c9e973330e1509f650c383626cdc638b25d` |
| `g1-rust-bench/src/main.rs` | `3117aa913afed296e5c3105dbfdc5094710eaebf1e8cd85145a822380cf73b24` |
| `g1-rust-bench/Cargo.toml` | `08396db166439300e168e3d085bfe4bc66a8c535ecaa511c5285b9b1416a6ae4` |

## 一行复现

```bash
# ① Java 侧：导出输入契约与 golden（只导出，不计时、不改 evidence）
cd <vkdisp 仓库>
./gradlew compileTestJava
java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
    --inventory run/shaderpacks --pack BSL_v10.1.8 \
    --golden build/bench-golden --dump-input build/g1-input --golden-only

# ② Rust 侧：单测 + 等价性门槛
cd ~/Minecraft/g1-rust-bench
export PATH="$HOME/.cargo/bin:$PATH"
cargo test --release
./target/release/g1-inc-check \
    --input  <vkdisp 仓库>/build/g1-input/BSL_v10.1.8 \
    --golden <vkdisp 仓库>/build/bench-golden/BSL_v10.1.8
```

## 等价性结果（原样输出）

```text
G1 · inc 相输出一致性检查
  阶段总数     : 182（本次检查 182 个）
  逐字节一致   : 182
  不一致       : 0
  ERROR 诊断   : 0（Java 侧同样会记，需与 evidence 对账）
  Rust 输出总量: 17298868 字节
  Rust 展开耗时: 52.7 ms（单次冷跑，非与 Java 可比的稳态数据）

✅ 全部逐字节一致 —— G1 的 inc 相等价性门槛通过。
```

| 判据 | 结果 |
|---|---|
| Rust 单测 | **11 / 11 通过** |
| BSL 182 阶段 `.inc.glsl` 逐字节比对 | **182 / 182 一致** |
| 不一致 | **0** |
| ERROR 诊断 | **0**（与 Java 侧 `IncludeProcessor.Result.success` 口径一致） |
| 展开输出规模 | 17,298,868 字节 |

## 移植时踩到的四个 Java / Rust 语义差异

这些是「两边看起来一样、实际不等价」的坑，逐条按 Java 语义处理并用单测锁定：

| # | 坑 | 现象 | 修法 |
|---|---|---|---|
| 1 | `String.strip()` ≠ `str::trim()` | Java 的 `Character.isWhitespace` **不含** U+00A0 / U+2007 / U+202F，Rust 的 `trim()` **含** → 含 NBSP 前导的 `#include` 行判定会分叉 | 自建 `java_strip()`，按 Java 白名单逐码点裁剪 |
| 2 | 正则 `\s` ≠ Unicode 空白 | Java 正则 `\s` 只有 ASCII 六种 `[ \t\n\x0B\f\r]` | 自建 `is_java_regex_ws()` |
| 3 | 换行归一两步叠加 | `normalize()` 只吃**一个**结尾换行，`split("\n", -1)` 再丢掉**一个**末尾空串 ⇒ `"a\n\n"` 最终只剩一行 `"a"` | 照抄两步，不合并 |
| 4 | 正则回溯的假象 | `^#\s*include\s+["']([^"']*)["']` 看起来要回溯，其实 `\s*` 吃掉的个数由 `"include"` 起始位唯一确定，不可能回溯成功 | 手写扫描等价实现，行为与正则一致 |

> 第 1 条单测 `java_strip_excludes_nbsp_unlike_rust_trim` 专门断言「Java 不裁、Rust 裁」，
> 防止后人「顺手改成 trim()」把等价性弄坏。

## 🔴 本文件**没有**证明的事（不许外推）

| 未覆盖 | 原因 / 何时补 |
|---|---|
| **性能结论** | 检查器打印的 52.7ms 是**单次冷跑**（无预热、样本 1，两次跑出 54.0 / 52.7ms）。Java 侧**连 inc 单独计时都没有**（G0 的预处理段是 include+define+const 合并）。G3 才裁决，且必须走 `17-NATIVE.md` §7.3 的红线 |
| define 相 | 未移植。靶子已就绪（golden 的 `pre`），下一轮 |
| 转译相（8 段流水线） | 未移植。靶子是 golden 的 `trans`；最重的一段，可能要分多轮 |
| `pack/` 解析相 | 未移植。**且尚无 golden** —— G0 里该段没有中间产物导出，需先补 |
| 行号映射 `SourceLineMap` | **未移植**（本轮只认文本等价）。映射结构另开一轮，避免两件事混在一起时定位不了 |
| 诊断文本等价 | 本轮只比对输出文本 + ERROR 计数，未逐条比对诊断文案 |
| 其它包 | 只跑过 BSL。Complementary / Sildur 等未覆盖 —— 不同包会触发不同的 include 边角（深层嵌套、`../` 越界、缺文件） |

## 结论

- ✅ **G1 的 inc 相在 BSL 上通过等价性门槛**（182/182 逐字节一致）。
- ⏭️ G1 **尚未完成**：define 相与转译相未做 ⇒ G2（FFM demo）与 G3（性能裁决）**不得开始**。
- ⏭️ 进 G3 前必须先补：**Java 侧 inc 单独计时**（否则两侧连同一个口径的数字都没有）。