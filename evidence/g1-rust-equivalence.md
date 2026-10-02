# G1 · Rust 等价实现 —— inc 相与 pre 相逐字节一致性

> 对应 `17-NATIVE.md` §5.1 的 **G1 关卡**：「必须通过输出一致性测试 —— 同一输入，
> Rust 与 Java 产物逐字节/逐 token 等价（不一致 ⇒ 谈不上性能对比，先修等价）」。
> 本文件证明 **inc 相**（`#include` 展开）与 **pre 相**（宏与条件编译）两段；
> **转译相（`trans`）与 `pack/` 解析相仍未做 ⇒ G1 未完成，G2 / G3 不得开始。**

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（与 G0 同一台，两侧同机才有可比性） |
| Java 侧 | JDK 25.0.4.1（`vkdisp` 本仓库） |
| Rust 侧 | `rustc 1.99.0 (b940084d7 2026-09-28)` / `cargo 1.99.0 (5f94df478 2026-08-27)` |
| 对照工程 | `~/Minecraft/g1-rust-bench`（**仓库外**，commit `96ec088`） |

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
| `g1-rust-bench/src/define_processor.rs` | `58b289746e2a203dfa54b60ae3b10d84268a32df97680a0d5903f1a207744592` |
| `g1-rust-bench/src/main.rs` | `9c84ab07e0e102cdc12b8b0fa52dd37d29d622bc92d36945de1f8e1d62f6b6f6` |
| `g1-rust-bench/Cargo.toml` | `b0107acff11201fdc54adf1a90157f0a74c4e13bf44f3dbba1a181c97c6c8522` |

## 一行复现

```bash
# ① Java 侧：导出输入契约与 golden（只导出，不计时、不改 evidence）
cd <vkdisp 仓库>
./gradlew compileTestJava
java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
    --inventory run/shaderpacks --pack BSL_v10.1.8 \
    --golden build/bench-golden --dump-input build/g1-input --golden-only

# ② Rust 侧：单测 + 两相的等价性门槛
cd ~/Minecraft/g1-rust-bench
export PATH="$HOME/.cargo/bin:$PATH"
cargo test --release
cargo build --release          # ⚠️ 必须：否则跑的是上一版产物
for phase in inc pre; do
  ./target/release/g1-check --phase "$phase" \
      --input  <vkdisp 仓库>/build/g1-input/BSL_v10.1.8 \
      --golden <vkdisp 仓库>/build/bench-golden/BSL_v10.1.8
done
```

## 等价性结果（原样输出）

### inc 相 · `#include` 展开

```text
G1 · inc 相输出一致性检查
  阶段总数     : 182（本次检查 182 个）
  逐字节一致   : 182
  不一致       : 0
  ERROR 诊断   : 0
  success=false: 0
  非 ASCII 标识: 0
  Rust 输出总量: 17298868 字节
  Rust 展开耗时: 53.3 ms（单次全量，无预热，**非**与 Java 可比的稳态数据）

✅ 全部逐字节一致 —— 该相的 G1 等价性门槛通过。
```

### pre 相 · 宏与条件编译

```text
G1 · pre 相输出一致性检查
  阶段总数     : 182（本次检查 182 个）
  逐字节一致   : 182
  不一致       : 0
  ERROR 诊断   : 0
  success=false: 0
  非 ASCII 标识: 0
  Rust 输出总量: 1877266 字节
  Rust 耗时    : 308.2 ms（单次全量，无预热，**非**与 Java 可比的稳态数据）

✅ 全部逐字节一致 —— 该相的 G1 等价性门槛通过。
```

| 判据 | inc 相 | pre 相 |
|---|---|---|
| Rust 单测 | **32 / 32 通过**（两模块合计），`cargo build` 0 告警 | |
| BSL 182 阶段逐字节比对 | **182 / 182** | **182 / 182** |
| 不一致 | **0** | **0** |
| ERROR 诊断 | **0** | **0** |
| 非 ASCII 标识符（Java/Rust 分类风险面） | **0** | **0** |
| 输出规模 | 17,298,868 字节 | 1,877,266 字节 |

## 移植时踩到的 Java / Rust 语义差异

这些是「两边看起来一样、实际不等价」的坑，逐条按 Java 语义处理并用单测锁定：

| # | 相 | 坑 | 现象 | 修法 |
|---|---|---|---|---|
| 1 | inc | `String.strip()` ≠ `str::trim()` | Java 的 `Character.isWhitespace` **不含** U+00A0 / U+2007 / U+202F，Rust 的 `trim()` **含** → 含 NBSP 前导的 `#include` 行判定会分叉 | 自建 `java_strip()`，按 Java 白名单逐码点裁剪 |
| 2 | inc | 正则 `\s` ≠ Unicode 空白 | Java 正则 `\s` 只有 ASCII 六种 `[ \t\n\x0B\f\r]` | 自建 `is_java_regex_ws()` |
| 3 | inc | 换行归一两步叠加 | `normalize()` 只吃**一个**结尾换行，`split("\n", -1)` 再丢掉**一个**末尾空串 ⇒ `"a\n\n"` 最终只剩一行 `"a"` | 照抄两步，不合并 |
| 4 | inc | 正则回溯的假象 | `^#\s*include\s+["']([^"']*)["']` 看起来要回溯，其实 `\s*` 吃掉的个数由 `"include"` 起始位唯一确定 | 手写扫描等价实现 |
| 5 | pre | `replaceAll("\\b" + Pattern.quote(p) + "\\b", arg)` | Java 的 `\w` / `\b` 默认**只认 ASCII**；且替换串里 `\` 与 `$` 有**转义语义**（`\` 吃掉后一个字符，`$` 后不是数字或 `{` 直接抛） | 手写 `replace_all_word_bounded()` + `expand_replacement()`，逐条复刻 `\b` 断言与转义规则 |
| 6 | pre | `Character.isLetterOrDigit` ≠ `is_alphanumeric` | 后者含 Nl / No（`½` 这类），Java 的 `isDigit` 只含 Nd | `java_is_digit()` 对非 ASCII 取**保守 false**；且任何非 ASCII 字符都被记进 `non_ascii_ident` —— **分叉不可见才是等价性最大的敌人** |

> 第 1 条单测 `java_strip_excludes_nbsp_unlike_rust_trim` 专门断言「Java 不裁、Rust 裁」，
> 防止后人「顺手改成 trim()」把等价性弄坏。

## 🔴 pre 相第一次跑出的 133/182：两个坑叠在一起

第一次跑 pre 相只有 **133/182** 一致，49 个阶段「参数一个都没替换上」。根因与排查过程：

**① 真 bug —— 尾切片下标空间搞混（已修）**
`match_func_define` 里 `take_ascii_ident` 返回的 `after` 是 `chars` 的**尾切片**，起点
通常不在 0，而扫描 `(`…`)` 时却按绝对下标从 1 开始 ⇒ **参数表与宏体整体错位**。

它难发现的原因值得记：

- **症状伪装成「部分正确」** —— 多参宏里第一个参数看起来「没生效」，第二个却是对的；
- **片段级单测全绿** —— 手写的 `#define ADD(a,b) ((a)+(b))` 这类宏，
  `after` 的起点恰好让偏移错误「看起来正常」；
- 只有**拿真实包跑**才暴露：BSL 的 `#define projMAD(m, v) (diagonal3(m) * (v) + ...)`
  这种「宏体里再调另一个函数宏」的形态。

回归测试 `func_define_with_tail_slice_offsets` 就是拿这条真实数据锁的。

**② 假线索 —— 跑的是旧二进制**
修完代码后我没重新 `cargo build --release` 就跑检查器，拿到的仍是上一版产物，
于是对着一个**已经不存在的 bug** 又查了一轮。教训写进了对照工程的 README：
**任何「跑出来不对劲」的第一反应，都该先确认产物是最新的**，再去看代码。

## 🔴 本文件**没有**证明的事（不许外推）

| 未覆盖 | 原因 / 何时补 |
|---|---|
| **性能结论** | 检查器打印的 53.3ms / 308.2ms 是**单次冷跑**（无预热、样本 1，重复跑也不稳定）。Java 侧**连 inc / pre 单独计时都没有**（G0 的预处理段是 include+define+const 合并）。G3 才裁决，且必须走 `17-NATIVE.md` §7.3 的红线 |
| **转译相**（8 段流水线） | 未移植。靶子是 golden 的 `trans`；最重的一段（`LegacyBuiltinInjector` 等单类 20–30KB），需分多轮 |
| **`pack/` 解析相** | 未移植。**且尚无 golden** —— G0 里该段没有中间产物导出，需先在 vkdisp 侧补 |
| 行号映射 `SourceLineMap` | **未移植**（只认文本等价）。Java 侧 `inputLineMap` 只喂诊断、不影响文本，所以本轮成立；映射结构另开一轮，避免两件事混在一起时定位不了 |
| 诊断文本等价 | 只比对输出文本 + ERROR **计数**，未逐条比对诊断文案 |
| 其它包 | 只跑过 BSL。Complementary / Sildur 等未覆盖 —— 不同包会触发不同的边角（深层嵌套、`../` 越界、缺文件） |
| 非 ASCII 标识符路径 | 本轮 BSL 上计数为 0，等价性风险面未被实际触发过；分叉防护靠 `non_ascii_ident` 计数告警 |

## 结论

- ✅ **G1 的 inc 相与 pre 相在 BSL 上通过等价性门槛**（两相各 182/182 逐字节一致）。
- ⏭️ G1 **尚未完成**：转译相与 `pack/` 解析相未做 ⇒ G2（FFM demo）与 G3（性能裁决）**不得开始**。
- ⏭️ 进 G3 前必须先补：**Java 侧 inc / pre 单独计时**（否则两侧连同一个口径的数字都没有）。