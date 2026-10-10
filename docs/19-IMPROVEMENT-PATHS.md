# 19 · 弱点改进路径（调研 → 方案 → 阶梯 → 验收）

> **性质**：方案书。**本轮不改任何代码**，只做调研、取证式定位与路线设计。
> **对象**：核心代码审查归纳出的四类「实验期原型形态」弱点 —— ①文本级翻译脆弱 ②巨型类 + 静态可变状态
> ③硬编码假设 ④注释信噪比低 + 反射读配置。对应 `QUALITY-DEBT.md` 的 **QD-09 ~ QD-14**。
> **裁决依据**：`07-CONSTRAINTS.md` §〇·〇 三支柱（兼容 > 稳定 > 高性能）与其红线表。
> **调研合规**：按 **L12 / X19** 把许可证核对作为第 0 步做完才读参考（核对表见附录 B）。
> **状态**：🔴 **§7-1 已裁决（2026-10-10）** —— 允许移植 jcpp（Apache-2.0）。裁决原话要点：
> 「100% 自研」是早期文档自己加的限制、**并非 MIT 的要求**；**合规底线只是完全遵守 MIT、不越界**。
> ⇒ 「MIT ⇒ 完全自研」在 `03-DIRECTION.md` §0、`07-CONSTRAINTS.md` §〇/§1.3/§七、`AGENT_CONTEXT.md` §0
>   三处**已于同日更正**；附录 C 那张同步表现在**全部为 ✅**。
>   🔖 本文初版把出处写成「`03-DIRECTION.md` §4」是**写错了** —— 那句在 **§0**（§4 是 LGPL/GPL/ARR 边界表，无需改）。
> ✅ 本文挂着的唯一一条「待查」同日结掉：**26.3 的清单自带 `org.lwjgl:lwjgl-shaderc:3.4.3`**（26.2 才是 3.4.1），
>   且 3.4.3 的 `Shaderc.class` 同样含 `shaderc_compile_into_preprocessed_text`（复算命令见附录 A5）
>   ⇒ 顺带证明「游戏自带坐标随 MC 小版本漂」这条必须进升版核查表，已登记 `06-MIGRATION` §3 **V6** + `07` §5.1 + `05-VERSION` §2。
> §7-2 **已裁决（2026-10-10）**：允许引用游戏自带的 `lwjgl-shaderc`，取方案 (b)「只作差分 oracle」⇒ 落地边界见该节表格。
> 现在只剩 §7-4（C2b 排哪一轮真机取证）**待判**；§7-3 的「级联/阴影」处置**已执行**（暂不做级联，改立 `GAP-034`），只剩 C2b 排期。
> 前提更正两处：§7-2 的「要新增原生分发」不成立、§7-3 的「只差太阳方向」不成立 —— 见各节。
> §2.6-A0 / §3.5-B0 / §5.4-D1 属「不等裁决即可做」。

---

## 0. 怎么读这份文档

每个弱点四段：**现状证据**（全部 `file:line` + 附录 A 可复算）→ **病根**（不是症状列表，是「为什么会反复出现」）
→ **调研结论**（别人怎么做、哪些能用码哪些只能用思路）→ **阶梯**（每阶：改动面 / 判据 / 成本 / 动不动热路径）。

🔴 **三条贯穿全部弱点的判断，先看这三条**：

1. **四类弱点是同一个根**：项目早期以「一行文本 + 正则」为基本操作单位，且**没有一个共享的「源码模型」**。
   于是翻译层各阶段各自扫描、各自重复实现（12 个类各有一套判断逻辑）、各自踩同一类盲区；
   硬编码名单是「没有源码模型 ⇒ 只能靠先验知识」的产物；巨型类是「没有共享值对象 ⇒ 逻辑只能堆在调用点」的产物。
   **所以改进的主线不是逐个补洞，是补那一个缺失的层**（§2.6 的 L1）。
2. **真正紧急的不是「脆弱」，是「静默」**。文本级方案的盲区是既定取舍，代码注释里已如实登记；
   但 `#define A \` 这类输入**不报错、不告警、直接产生错误展开**（§2.2），这是 **X11/T11 违例**，
   与 QD-08 那族「配置被读了、某条链静默走偏、日志无异常」同形。⇒ 这一条不必等方案裁决（§2.6-A0）。
3. **evidence/ 退库让文件头的取证注释变成了悬空引用**（§5.1）。这已经不是「信噪比」的品味问题，
   是**可追溯性断裂** —— 新克隆的读者查不到 `h48`。收缩文件头的优先级应据此上调。

---

## 1. 基线（本轮实测复算，命令见附录 A）

| 指标 | 本轮值 | 口径 | 备注 |
|---|---|---|---|
| `>1000` 行的主源码类 | **6** | `wc -l` | `FrameApi` 1675 / `VkDispVirtualPack` 1573 / `PipelineApi` 1163 / `TerrainPipelineApi` 1131 / `TargetReadback` 1111 / `MrtTerrainPass` 1068 |
| 静态非 final 字段 | **175** | 单行声明（正则口径见 A1） | `bridge` 123 / `render` 20 / `pack` 10 / `glsl/translate` 7。⚠️ `QUALITY-DEBT.md` QD-03 写的「95」已过期 |
| `>60` 行方法 | **21** | QD-04 更正后的口径 | `MethodLengthRatchetTest`：`LIMIT=60`（:51）、`BASELINE=22`（:83），且**只有 `>22` 才红** ⇒ 当前有 1 个余量，拆一个不会自动降基线 |
| `#line` 发射点 | **0** | 全 `src/main/java` | ⇒ 驱动（shaderc）错误原文指向**转译后文本**，不指向包内行（§2.3-c） |
| 续行（`\`）感知的代码 | **1** 处 | — | 仅 `GlslTextScan.preprocessorSkipLines`（`GlslTextScan.java:183`），且**不被 `DefineProcessor` 使用** |
| 靠反射读配置的 Switch 类 | **4** | `Class.forName` 命中 | `PackCapabilityGateSwitch` / `PackChainGatingSwitch` / `PackOptionOverrideSwitch` / `PackPostVertexSwitch`（另 `McVersion`） |
| 硬编码自 BSL 扫描的 sampler 名单 | **18**（16 个绑 `colorView` 占位） | `PipelineApi.java:127-134` | QD-11① 的原文 |
| 光行进方向 | **常量** `(0.6,-1.0,0.45)` | `FrameApi.java:130-131` | `source="fixed-placeholder"`（:134），且列表**懒构建一次即缓存**（:164-174） |

---

## 2. 弱点一 —— 文本级翻译脆弱（QD-09）

### 2.1 现状：12 个类各有自己的「怎么找到要改的东西」（下表 10 行，其中一行合并了三个同族改写器）

| 类 | 机制 | 已知盲区 | 盲区是否可见 |
|---|---|---|---|
| `IncludeProcessor:56,117-119` | 行 trim 后正则 `^#\s*include\s+["']…` | **零注释/字符串感知** ⇒ `// #include "x"` 会真的展开 | 静默 |
| `DefineProcessor:110-132` | 逐行 `trimmed.startsWith("#")` | 不支持 `\` 续行；`/* */` 内的 `#define` 被当指令、其中的代码被展开；函数宏体替换用 `replaceAll("\\b"+参数+"\\b")`（:386-394）无字符串/注释守卫；括号匹配只在单行内（:396） | 🔴 **静默产生错误展开，无 WARN** |
| `ConstEvaluator:96-105,143,198` | 行正则 + `startsWith("const")` 前缀守卫 | 同一逻辑两份实现（A/B 开关 :76-87） | 可见（受开关自报） |
| `GlslDeclaration:82-124` | 单行 token 扫描 | `layout(...)` 前缀的行**直接返回 null** | 静默（下游按「无声明」走） |
| `IoLocationAdapter:156-170` | 正则 + `GlslDeclaration` | 跨行 ⇒ **静默跳过**；同行多语句 ⇒ WARN 跳过 | 一半静默 |
| `LegacyBuiltinInjector:295-355` | **token 级等长替换**（表 :126-136） | 转译跑在预处理**之后** ⇒ 被正常展开的宏体已成代码文本、能被改到；真正残留的旧名只来自 **DefineProcessor 没吃下的写法**（续行宏、`/* */` 内的宏、非管辖指令透传）。裸 `gl_TextureMatrix` 交驱动（:315-326） | 可见 |
| `TextureFunctionRenamer:201-215` / `FtransformExpander:255-260` / `AttributeRewriter:121-124` | token 扫描 + 单行括号匹配 | 跨行 ⇒ 只改名不展开 / 不展开，**均带 WARN** | 可见 |
| `UniformInjector:239-266,408-419` | 正则 + 全仓**唯一**花括号深度跟踪 | 多行声明不采纳 | 可见 |
| `PostVertexLinker:542-580` | 整行正则 + **自建** `splitIoStatements` | 需要自建的原因：上游 `IoLocationAdapter` 把多条语句并到同一行，导致它自己的逐行扫描失效 | 已登记 |
| `GlslDeclarationExtractor:221-307` | **第二套**扫描器（自带 `stripComments`） | 类注释 :27-30 自认与 `glsl/translate` 重复 | 已登记 |

**结论：仓内已经有正确的原语，只是没有被共享。** 值得作为升级地基的三件现成品：
`CommentState:63-96`（跨行块注释状态机，注释**等长**抹平）、
`GlslTextScan.codeViews`（注释+字符串抹平的等长视图 + `identifierAt`/`atTokenStart`/`matchCloseParen`）、
`DefineProcessor.ExprEval:459-694`（真 token 词法 + 递归下降，说明「写对」在本仓不是能力问题）。

### 2.2 病根拆成三条（各自解法不同，别混成一件事）

- **(a) 没有单一词法源** ⇒ 每加一个阶段就新增一套判断逻辑，且互相不一致
  （证据：`PostVertexLinker` 被迫自建 `splitIoStatements`；`GlslDeclarationExtractor` 自认重复实现）。
  `07` 已立法两次同类事故（**X43** 行首锚定 ⇒ 静默少认 4 条 varying；**X44** 改写后不可回解析 ⇒ 丢 182 个编译阶段），
  但这两条现在是**逐适配器**遵守的纪律，没有结构性保证。
- **(b) 预处理语义 ≠ C 预处理** ⇒ 续行、注释内指令、字符串内宏名、函数宏参数替换这四类是**规范语义**问题，
  靠逐条打补丁永远补不完（GLSL 规范：行延续符在换行前先被删除，`#define/#undef` 行为同 C++ 预处理器）。
- **(c) 错误不可归因** ⇒ `OfGlslTranslator.locate:342-351` 的 `SourceLineMap` 只映射**本项目自己的**诊断；
  `ShaderCompileApi:121-123` 把 shaderc 原文**逐字**返回，`VkDispPackScan:232-235` 原样打日志
  ⇒ 驱动报的 `file:line` 是转译后文本的行。并且没有任何地方把「转译产物不可编译」判定为
  **本项目的 bug**（`VkDispPackScan:213-218` 与 :230-236 是两种日志形状，无因果归属）。
  ⇒ 「shaderc 兜底」这句话目前**只兜住「不崩」，兜不住「定位」**。

### 2.3 调研：GLSL 前端的可选件

🔴 每一步都先过许可证闸门（**L12**），判不过就不读它的代码。

| 候选 | 许可证（仓库 LICENSE 文件） | 能否并码 | 提供什么 | 判定 |
|---|---|---|---|---|
| **jcpp** `org.anarres:jcpp:1.4.14` | **Apache-2.0**（POM 与 LICENSE 一致） | ✅ 可并（留署名 + NOTICE，**含改动需声明**） | 纯 Java **token 级** C 预处理器：`JoinReader` 实现 `\`+换行拼接、`Feature.KEEPCOMMENTS/KEEPALLCOMMENTS/LINEMARKERS`、`__LINE__/__FILE__`、函数宏 `MacroTokenSource`、`VirtualFileSystem` 接 `#include` | 🟢 **本弱点的最短路径**。⚠️ 最后提交 2021；且 jcpp 扩展性不足导致 Iris 用 `#warning` 标记 hack 才能透传 `#version/#extension`（Iris 自称「absolutely awful hack … 应该自己写一个」——此结论是**事实**，代码不许看）⇒ 必须先做 §2.6-A2 的可行性探针 |
| **shaderc `shaderc_compile_into_preprocessed_text`** + include 回调 | **Apache-2.0**；LWJGL 绑定 `org.lwjgl:lwjgl-shaderc` = **BSD-3** | ✅ **不需要我们分发原生库** —— 原版客户端**自带**该坐标。🔖 **2026-10-10 按本项目锁的版本重新核实**：主线是 **26.3**，其清单声明的是 **`org.lwjgl:lwjgl-shaderc:3.4.3`**（不是先前引用的 26.2 = **3.4.1**），且 **3.4.3** 的 `org/lwjgl/util/shaderc/Shaderc.class` **确有** `shaderc_compile_into_preprocessed_text` 符号（两条命令见附录 A5） | 与现编译路径**同一实现**做预处理 ⇒ 天然零语义分歧；`#line` 由 glslang 产生 | 🟢 **成本判定已在 2026-10-10 更正**（原判「要新增原生分发」是错的）⇒ 定位改为 **jcpp 为实现 + shaderc 为差分 oracle**（见 §2.6-A2 与 §7-2）。⚠️ 仍需：**「测试/离线工具可引用游戏自带的 LWJGL 坐标」这句待 §7-2 裁决**；放行后版本号**入 `gradle.properties`**（与 `05-VERSION` 的 MC 锁同源），并按 26.2→26.3 这次**实测漂移**把该坐标写进 `06-MIGRATION` 升版核查表；另按 **X31** 加 A/B 开关、**T15** 保持 Java 路完整可用 |
| **ANGLE `src/compiler/preprocessor`** | **BSD-3-Clause** | 可并码，但 C++ | `Lexer/Tokenizer/Token/Macro/MacroExpander/DirectiveParser/Diagnostics` 的**分层是这类引擎的范本** | 🟢 作为**架构**参照（自研时的目录切分照它）；不并 C++ |
| **Mesa glcpp** | **MIT**（逐文件头） | 可 | GLSL 1.30 预处理；文档明写 `#line/#pragma/#extension` **透传**策略 | 🟢 当**语义对照表**（写单测时的 oracle），移植 C+bison 不值 |
| **Khronos glslang** | 混合：核心 BSD-3/BSD-2/MIT/Apache-2.0；**预处理那几个文件是 BSD-3 + `AML-glslang`**（NVIDIA 非标文本） | 🟡 需逐文授权确认 | 完整 parser + AST + SPIR-V；`TShader::Includer` 处理 `#include` | 🔴 **两条硬保留**：① 头文件原话「只做预处理以取正确的预处理串**不是官方支持或完全可用的路**」；② `Pp*` 文件带非标许可证文本 ⇒ **见 §2.4 对 GAP-005 的更正** |
| ANTLR `grammars-v4/glsl` | MIT | 可 | `GLSLLexer.g4/GLSLParser.g4/GLSLPreParser.g4` | 🔴 目标 **GLSL 4.60**（非 ES），无展开引擎（只有语法），已知注释/指令内空白处理有 open issue，2023-11 后基本未动 ⇒ 不足以当地基 |
| `tree-sitter-glsl` | MIT | 可 | 158 行 `grammar.js`，是 tree-sitter-**c** 的扩展 | 🔴 不是 GLSL ES 语法；且 tree-sitter 不打印 AST，只能 range splice ⇒ 不如自建 token 流 |
| `glsl-transformer` (Douira) | **AGPL-3.0**（README 还要求使用方也 AGPL） | ❌ | — | ⛔ **禁止**（X20/X21）——它恰好是搜「java glsl parser」最容易命中的那个，务必拦住 |
| `glsl-preprocessor` (IrisShaders) | **GPL-3.0 + 例外条款** | ❌ | — | ⛔ **禁止**（X21，`07` §1.3 陷阱 2 点名的正是这类） |
| **Iris** `glsl-transformer` 路 + `IncludeProcessor` 逐行 | LGPL-3.0 | ❌ 思路 | 事实：**Iris 的 include 也是逐行的**，`gl_FragData` 改写走 AST 但 `LineTransform/StringTransformations` 仍是行/正则；uniform 位置靠运行时 `glGetUniformLocation`（GL 能问，Vulkan 不能） | 🟢 两点可用结论：**「我们并不比 Iris 更脆弱」**（别为脆弱本身焦虑，为静默焦虑）；**GL 的反射式查询在 Vulkan 上没有对等物**（⇒ §4.2 必须走 SPIR-V 反射或声明派生） |
| **Vitrail** `common/.../glsl/` | LGPL-3.0 | ❌ 思路 | 事实：它**自建无损 token 词法器**，注释原话「保留每个空格、注释、换行，使未改动的流能拼回完全相同的原文」；并有 `TokenStream/IncludeExpander/Macros/SamplerPlan`；SPIR-V 交给原版 `GlslCompiler`（shaderc），随后 **SPIRV-Cross 反射**读它的产物 | 🟢 这两条正是 L1 与 §4.2 的目标形态（**只用结论，代码一行不碰**，且它 NOTICE 自承其 `DRAWBUFFERS/const` 语法派生自 Iris ⇒ 那部分连思路都按 LGPL 处理） |

### 2.4 对 GAP-005 的更正（登记在 `13-GAP-REGISTRY.md:32`）

GAP-005 现记「glslang 与 SPIRV-Tools 为 Apache-2.0/BSD-3，可合法并入，glslang 支持完整 `#include` 与预处理开关」，
并列为「⏳ 已登记，本轮不执行」。本轮调研要求两处收窄：

1. **「预处理开关」这条用途不成立**：glslang 头文件对「只要预处理结果」明写不是官方支持/完全可用的路；
   C ABI 虽有 `glslang_shader_preprocess()`，继承同一保留。⇒ glslang 的合适槽位是「**全量 parser + SPIR-V**」，不是「预处理槽」。
2. **许可证不是「Apache-2.0/BSD-3」这么粗**：`REUSE.toml` 把 `MachineIndependent/preprocessor/Pp*` 钉为
   **BSD-3-Clause + `AML-glslang`**（NVIDIA 非标文本，无 copyleft 但不在 `07` 许可表点名的三族里）
   ⇒ 按 §1.3 判定表应落「拿不准 = 当作不能用，只读思路」，若要并码需先逐条读完该文本并写进调研结论。

🔴 **已执行（2026-10-10）**：GAP-005 已拆成 **GAP-005a（预处理槽 → jcpp 实现 + shaderc 差分 oracle）** 与 **GAP-005b（解析槽 → glslang 全量，需 `AML-glslang` 核实）**，
并把「先做 G 系列 Rust vs Java 对比」这个前置条件从**预处理槽**上摘下 —— jcpp 是 **Java**，不触发 T16/X17 的原生闸门（X15 作废条款只约束原生路线）。

### 2.5 目标形态：一个源码模型 + 三层改写

```
L0  输入与行图        PackLines + SourceLineMap（已有）——一切下标以此为准
 └─ L1  无损 token 流   GlslTokens：单一词法源，保留空白/注释/换行，未改动区可 byte 级拼回原文
     │                （合并现有 CommentState + GlslTextScan + ExprEval 词法 + :183 的续行处理）
     ├─ L2  预处理语义   CppPreprocessor：真 `\` 拼接、注释/字符串内的指令与宏名不误触、
     │                  函数宏按 token 展开、`#version/#extension/#pragma/#line` 透传、**发射 #line**
     ├─ L3  OF 方言改写  全部阶段改为「在 L1 上按 offset splice」；
     │                  声明解析归一个共享实现（跨行 / layout(...) / 同行多语句）
     └─ L4  自检与归因   ①每阶段断言「输出仍能被同一套语法解析回去」(X44)
                        ②shaderc 错误原文经 #line + SourceLineMap 映射回包行
                        ③「本项目转译后不可编译」⇒ 记本项目 ERROR（不是包的错）
```

三条设计约束（违反任一条就退化回现状）：

- **绝不打印 AST**。未改动区域一律原文拼接；改写只允许「替换一段 offset 区间」。理由：注释里承载包语义
  （`DRAWBUFFERS:`、`const` 指令、`/*! … */`），打印即丢；glsl-transformer 的 README 自己就承认非代码部分不会出现在输出里。
- **单一词法源**。新增阶段不许自带正则找声明；靠测试钉（§2.6-A1 的守卫）。
- **`#line` 是让驱动变成 oracle 的开关**。没有它，「shaderc 兜底」永远只是「不崩」；有了它，驱动报错第一次能落到包内行。
  ⚠️ 与现 `SourceLineMap` 的分工要写清：前者映射「转译前 ↔ 转译后」，`#line` 映射「转译后 ↔ 包内」。

### 2.6 阶梯

| 阶 | 内容 | 落点（现状 file:line） | 判据（X46：可数的日志/断言） | 成本 | 动热路径？ |
|---|---|---|---|---|---|
| **A0** | ✅ **已执行（2026-10-10）** —— 止血：把静默变可见，**零语义变化** | 实际落点：`DefineProcessor` ①指令行行尾 `\` → WARN（`warnAt` 走与 `errorAt` 同一条包内归因路）②**新增**「指令行位于注释内」→ WARN（复用 `translate/CommentState`，为其上调可见性到 public，**不造第二套扫描器**）；`IncludeProcessor` 注释内 `#include` → WARN；`IoLocationAdapter` 跨行/半截 in-out 按**行首括号深度**分流（0 ⇒ WARN，>0 ⇒ 函数参数折行仍安静） | 5 条负样本单测（`DefineProcessorTest` 2 / `IncludeProcessorTest` 1 / `IoLocationAdapterTest` 2）断言**「必须 WARN，不得静默」**，全绿且全套无回归 | 1 h（实测含文档登记） | ❌ 冷路径 |

🔖 **A0 执行时对原判的三处修正**（下一阶按这些事实走，别按原判的措辞）：
① 「宏体内 `gl_` 名」的可见性**不落在 `LegacyBuiltinInjector`** —— 转译跑在预处理之后，宏体展开后已是代码文本
（§2.1 该行本就标「可见」）；真正的静默根因是 `/* */` 内的 `#define` 被当真指令吃下，故 WARN 落在 `DefineProcessor`。
② `IncludeProcessor` 的注释内 `#include` **本阶照旧展开**（只 WARN）——「不展开」是语义变更，归 A2 由 jcpp 一次做对。
③ `IoLocationAdapter` 不能无条件 WARN：函数参数折行与真跨行声明走同一分支，原判没说怎么区分 ⇒ 用括号深度。
| **A1** | 建 L1 单一 token 流；`translate/` 各阶段改用它；`GlslDeclarationExtractor` 的第二套扫描器并入 | 以 `CommentState.java:63-96` + `GlslTextScan.java:76-219` + `ExprEval` 词法为地基 | 新守卫 `GlslLexerSingleSourceTest`：扫 `glsl/**` 里新出现的「行首锚定正则声明」（X43 口径）并禁止增长；`GlslPipelineTest` 幂等断言必须继续绿 | 1.5 天 | ❌ 冷路径 |
| **A2** | 建 L2 预处理语义：**jcpp 核心移植为实现**（`Lexer/JoinReader/Token/Macro/展开`，留 Apache-2.0 LICENSE/NOTICE + 声明改动；**2026-10-10 已获用户裁决允许**），**shaderc 预处理作差分 oracle**（✅ §7-2 已放行：只进测试/离线工具，原版自带 ⇒ 零分发成本，见 §2.3）；「照 ANGLE 分层全自研」降级为 jcpp 不可用时的备选 | 替 `DefineProcessor`(719) / `ConstEvaluator`(402) 的相应职责 | 先做**三路差分探针**（不承诺替换）：同一批包源跑 ①现自研 ②jcpp ③shaderc，比 **逐字节 diff** + **产物能否被 shaderc 正式编译**；重点验 `#version/#extension/#pragma` 透传、`\u0000`、函数宏、`#include` 相对路径、`\` 续行（含 `\` 后带尾随空格这种包实测写法）；再报 B3/B4 变化（**X32**：不许拿公开基准当选型结论；**X52**：两臂只差一个实现） | 探针 0.5 天；落地 2–3 天（续行/函数宏/注释语义已由 Apache-2.0 实现做完，省掉的正是自研最易出错那段） | ❌ 冷路径 |
| **A3** | 发射 `#line` + 把 shaderc 原文映射回包行 + 「转译产物不可编译 = 本项目 ERROR」分类 | `ShaderCompileApi.java:121-123`、`VkDispPackScan.java:213-236`（两种日志形状之间加因果归属） | 一条故意写坏的转译产物 → 日志里必须出现**包内**文件与行号；`grep` 计数判据 | 1 天 | ❌ 冷路径 |
| **A4** | 声明解析语法级：跨行声明 / `layout(...)` / 同行多语句 / 块作用域 | `GlslDeclaration.java:82-124`（`layout` 返回 null 的分支）、`UniformInjector.java:239-266`、`PostVertexLinker.java:542-580` | 每条各 1 个正样本 + 1 个负样本；X43「一行可有多声明」升为全层共享断言 | 2 天 | ❌ 冷路径 |

🔖 **A1→A4 顺序不可换**：A2/A4 若先做，等于又造两套扫描器，病根 (a) 原样保留。

### 2.7 验收与风险

- **兼容（支柱①）**：BSL 之外至少 2 个包做预处理产物 diff 与编译通过矩阵（`08-TESTING.md` §10）。
  A0 之外**任何一阶都不许改变已有正确输出** ⇒ 逐字节 diff 是硬门。
- **稳定（支柱②）**：X44 断言 + 幂等测试；A0 单独一轮（改动小、收益是「可见性」，可立即验证）。
- **性能（支柱③）**：预处理/转译是**冷路径**，受 **B3 ≤ 1 s / B4 ≤ 2 s** 约束；
  已知现状 BSL 冷路径 **1861 ms**（`17-NATIVE.md:163` 附近）。token 级预处理通常比逐行正则慢，
  但换来的是「不必为盲区再打补丁」。**换前端必须同轮报 B3/B4 变化**，且按 **X52** 做单变量对照
  （两臂只差「用哪个预处理实现」，其余逐项列出并证明未变）。
- 🔖 **边界已按 §7-2 的裁决更新（2026-10-10）**：**生产路径仍不得引入原生依赖**（T15/T16/G 闸门一字未动）；
  放行的是**测试/离线差分 oracle** 那一条 —— 不进发布 jar、运行期代码不许 `import`。
  ⚠️ `evidence/` 已退库 ⇒ 三路对表的产物与判据落**测试报告**（`08-TESTING.md`），不要再写 `evidence/` 路径。

---

## 3. 弱点二 —— 巨型类 + 静态可变状态（QD-10 / QD-03 / QD-12）

### 3.1 现状：`FrameApi` 的九个职责簇（其余五类同形，细节在源码）

`bridge/FrameApi.java` 1675 行里可分出的簇：
旧三步链执行 `drawFullscreen:727-1093` 🔥 / 后处理链执行 `drawPostChain:1182`、`runPostPass:1288` 🔥 /
**8 个静态 uniform 环形缓冲**（`lightMatrixRing:101`、`cameraRing:215`、`paramsRing:361`、`builtins*Ring:394-401`、`postBuiltinsRings:1112`）🔥 /
相机数学与锚点 `cameraMatrix:264-340` / 光空间列表 `:164-174` / 离屏目标注册表 `offscreenTarget:519-546` /
静态顶点缓冲 `geometryBuffer:564` / mip 金字塔 blit `generateMipPyramids:1514-1563` 🔥 / 一次性诊断日志 `reportChainGenerations:1250`。

可拆缝（按「冷 → 热」排序，热路径改动须单独取证轮 —— QD-04 已立此规）：
`CameraAnchorState`(冷) → `MipPyramidPass`(热但形状封闭) → `BuiltinsRingPool`(热，键为程序名) → `PostChainExecutor`(热)。
`VkDispVirtualPack` 的 `VirtualPackResources` 内部类（`:1332-1572`）**已经是**一个可原样搬出去的对象；
`TargetReadback`（1111 行）**整类是配置门控的诊断** ⇒ 最低风险的第一刀。

### 3.2 病根三条

- **静态即全局单例**：175 个静态非 final 字段中 `bridge` 占 123；`PipelineApi` 13 个 `RenderPipeline` 静态字段（`:527-563`）
  永不复位；`TerrainPipelineApi.BUILTINS_RINGS`（`:112`）不随换包清；离屏目标与顶点缓冲**从不 close**。
- **缓存没有代际**：`ActivePackUniforms.current`（`pack/uniform/ActivePackUniforms.java:24`）整集 volatile 换入 ⇒ 无撕裂，
  但**没有 epoch**，取到的是「上一个包完整的一份」；`PackCompileCache.invalidate()`（`pack/PackCompileCache.java:163`）**零调用者**。
- **复位无约定**：全仓 `@TestOnly/@VisibleForTesting` **0 处**（故 QD-03/QD-12 无法闭环），已有先例只是零散命名：
  `resetGbufferPrograms:890`、`PackUniformSupply.resetFor:149-156`、`release()`、`invalidate()`。

### 3.3 诚实地界定风险等级（不夸大）

🔴 换包时 GPU 侧确实**不重建**任何静态资源，正确性目前靠三件事维持：
包状态写在被重写的 volatile 字段上（`VkDispVirtualPack:287-444`）+ `ActivePackUniforms.install` + memo 键校验（`:940-948`）。
⇒ 所以「多包切换有隐患」的实际形态是：**(i) 单测间无法隔离（阻断回归面扩大）；(ii) 设备重建/resize 时静态资源泄漏；
(iii) 键面每扩一项就可能漏一项**（QD-08 已发生四次）。
不是「切换必坏」。`QUALITY-DEBT` 的措辞应按此收窄（X9）。
另：绑定「生成期读的配置项必须进记忆键」的机制**已存在** —— `GenerationTimeSwitchInventoryTest`
（正则枚举生成窗口内读到的 `VkDispConfig.X.get()` 并断言 ⊆ 记忆键字段），
它的缺口是**作用域只覆盖两个文件**，不是「没有机制」。

### 3.4 方案

1. **代际号（generational index）**：`PackEpoch` 单调计数，凡缓存条目携带 epoch，**解引用处比对**不符即弃用并 WARN。
   这把「静默复用旧条目」从「靠人记得把字段加进键」变成结构性不变量。比身份键缓存更适合本仓（QD-08 的四例全是身份键漏项）。
   配套：把 `takeSourceMemo` 的造键/校键合并为单一函数（现 `:873` 又内联重拼了一次 `key + "|" + program`）。
2. **所有权图**：三个显式对象 —— `FrameContext`（每帧，栈式/try-with-resources）、
   `PackSession`（每包，reload 即整体丢弃）、`DeviceScopedPool`（resize/设备失效才重建），
   每个 `AutoCloseable` 且 close 幂等（`07` §3.3 已把这条写成规范，只是没落到对象上）。
   对齐 NeoForge 的重载契约：`PreparableReloadListener` 的语义就是「内容存活到下次 reload，届时整体丢弃」——
   **换包走整体替换，而不是逐字段修补**。
3. **Strangler + 棘轮**（仓内已有同类成功先例：`MethodLengthRatchetTest` 基线 22、`GapRegistryStatusFieldTest` 基线 9）：
   - `ClassLineRatchetTest`（QD-10 原话「按 QD-04 同款做法」，基线=当前 6 个文件行数）；
   - `StaticFieldRatchetTest`（基线 **175**，只许降）；
   - `StaticHolderResetTest`：凡持有静态可变资源的类必须提供 `reset/close`，且测试 `@BeforeEach` 调用
     （同时补上仓内缺失的 `@TestOnly` 约定 —— 建议就借用 NeoForge 已在依赖里的 jspecify，与 QD-01 同源）。
   先例的失败模式要预先挡住（detekt baseline / ESLint bulk-suppressions 都踩过）：**基线随手抬高** ⇒ 抬基线必须同 PR 内净下降；
   **扫描器漂移** ⇒ 每个棘轮都要有「扫描器自身能找到东西」的元测试（`MethodLengthRatchetTest:222` 已有，照抄）。

### 3.5 阶梯

| 阶 | 内容 | 成本 | 动热路径？ |
|---|---|---|---|
| **B0** | 三条棘轮 + `@TestOnly` 约定 + `reset/close` 契约测试（QD-03 首批点名 `FullscreenPassHook`/`OfUniformManager`/`ActivePackUniforms`） | 半天 | ❌ |
| **B1** | `PackEpoch` + 缓存条目带代际 + 解引用校验；`takeSourceMemo` 造键/校键合一 | 1 天 | 🟡 取键在冷路径，解引用每帧一次 |
| **B2** | 拆 `TargetReadback` → 诊断对象；`VkDispVirtualPack.VirtualPackResources` 外提 | 1 天 | ❌ 纯诊断 / 纯注册 |
| **B3** | `FrameContext`/`PackSession`/`DeviceScopedPool` 三级所有权；`FrameApi` 按 §3.1 逐簇外提（`BuiltinsRingPool`/`MipPyramidPass`/`PostChainExecutor` 单独取证轮） | 3–4 天，分 2 轮 | 🔴 是 ⇒ 每阶各一轮取证（QD-04 先例） |

---

## 4. 弱点三 —— 硬编码假设（QD-11）

### 4.1 名单（按风险取前 10，均无「与当前包实际声明不符」的检测，除非注明）

| # | 假设 | 位置 | 有无校验 |
|---|---|---|---|
| 1 | 18 个片元 sampler 名（BSL 扫描），16 个绑 `colorView` 占位 | `PipelineApi.java:127-134`，绑定 `:167-172` | ❌ |
| 2 | post sampler 超集 24 名 | `PostSamplerSuperset.java:19-26` | ✅ 有：`PackPostChain.java:223-232` 不符 ⇒ **WARN + 踢出链 + 打印缺的名字** |
| 3 | 光行进方向常量 | `FrameApi.java:130-131` | ❌ |
| 4 | 单级联 + 固定正交范围 | `shadow/LightSpaceList.java:51-61`（`CASCADE_FAR_SPLITS={8.0F}`） | ❌ |
| 5 | 23 条内建 uniform 表 | `UniformCatalog.java:43-66` | ❌ 不与包实际引用对差 |
| 6 | 声明但不供值的名字（`dh*/vx*/blindFactor`） | `AtmosphereBuiltins.java:86-92` | ❌（假定 OF/DH/Voxy 命名） |
| 7 | 属性别名表 | `GlslDeclarationExtractor.java:171-188` | 🟡 未命中 ⇒ INFO 丢弃 |
| 8 | 按名字路由视图（`texture_0/noisetex/shadowtex*`） | `SamplerDimensionPlan.java:258-281` | ✅ 有类型路径兜底；**且该类注释 :28-32 已引 X39 明确否决「按名字硬编码」** |
| 9 | `SERVABLE_ATTRIBUTES/UV_NAMES/BLACKLIST` | `PostVertexLinker.java:85-109` | ❌ |
| 10 | `MAX_POST_PASSES=16`（「BSL 10 < 16」）、`FRAME_WIDTH=8` | `PipelineApi.java:209`、`PackPostChain.java:50,53` | ❌ |

🔖 **第 3/4 项的实际影响被一个更大的缺口吃掉**：包的 `shadow` pass 从未被渲染、`shadowtex*` 绑 1×1 桩（§4.3）。
⇒ 只修「方向常量」在像素上是**零变化**，别把它单独当成一项可交付的兼容改进。

### 4.2 sampler 名单：派生机制**已在仓内**，缺的只是接上

事实链（本轮核实）：
`PostPassContract.java:51,97` 与 `SamplerDimensionPlan.java:186,206` 都用 `SAMPLER_DECL` **从片元源码解析 `uniform samplerX` 声明**；
`SamplerDimensionPlan` 从**声明的类型**决定视图种类（ATLAS/SHADOW/VOLUME_3D/DEPTH_SNAPSHOT/GAUX/NOISE/PLACEHOLDER），与名字无关；
`PackTextureBindings.java:41-65` 解析包自己的 `texture.<sampler>=路径`；
`PackPostChain.java:223-232` 已经把「派生 + 与超集对差 + WARN + 排除」跑在 post 链上。

⇒ **这不是一项新组件的工作，是把同一条已经跑通的派生+校验路接到 gbuffer/composite/deferred 上**，
并把 `PACK_FRAGMENT_SAMPLERS` 降级为「包没声明时的 fallback，且不符必 WARN」。
🔖 值得写进结论的一点：**本仓其实早就定了正确的原则**（`SamplerDimensionPlan:28-32` 引 X39 否决按名字硬编码），
QD-11① 的实质是「原则没贯彻到 `PipelineApi`」，不是「缺一条原则」。

**权威臂（更强的那条）**：原版自己就有 SPIR-V 反射面 ——
`com.mojang.renderpearl.backend.api.SpvModule$Reflection / $Descriptor / $InterfaceVariable`
（`02-OVERVIEW.md:58`、`03-DIRECTION.md:38`；证据来源是**常量池里的类型名** ⇒ 证明类存在，**方法表面未验**）。
本仓已在 `bridge/ShaderCompileApi.java:33,110` 用 `GlslCompiler.compileToSpv(...) → SpvModule`，
⇒ 拿反射面是**零新增依赖**的路，且天然满足 T5（在 `bridge/` 里访问）。
🔴 按 **X41/X9**：动手前先 `javap -p` 把表面核实并登记（一个 `bridge/SpvReflectionProbe` 即可），
未核实前**不许把 §4.2 的方案建筑在它上面**；核实通过后，第 8 项的按名路由与第 1 项一起降级为 fallback。
（Vitrail 的事实佐证这条路可行：它把 GLSL 交给原版 `GlslCompiler`（shaderc）后读 **SPIRV-Cross 反射**，再补 bind group layout。）

✅ **C0 已核实并落码（2026-10-10）** —— `javap -p` 打在 `build/moddev/artifacts/minecraft-patched-26.3.0.51-beta-merged.jar`，
并落了 `bridge/SpvReflectionProbe`（只读快照，**零行为改动**，暂无生产调用点）+ `SpvReflectionProbeTest`。
**结论：反射面足以按类型路由 sampler，但带两条必须先知道的限制。**

| 已验事实（javap 原文口径） | 对 C1 的意义 |
|---|---|
| `SpvModule`：`spv() / type() / reflect()`（抛 `ShaderCompileException`）/ `getReflectionInfoIfAvailable()`（可为 null） | 探针取**后者**：null ⇒ 空表 + 由调用方出诊断，不抛不静默 |
| `Reflection`：`inputs() / outputs() / descriptors() / descriptors(int) / pushConstants()` | sampler 清单走 `descriptors()`，按 `resourceType` 过滤 |
| `Descriptor`：`name() / type() / resourceType() / descriptorSetIndex()`**（另有 setter）** `/ binding()`**（另有 setter）** | 🔴 原版这套对象**可变** ⇒ 探针一律只读；写回 binding/set 会撞原版 `PipelineBuilder` 的布局生成 |
| `Type`：`baseType() / dimensions() / vectorSize() / arrayDimensions() / arrayLength(int)` | 见限制 ① |
| `resourceType` 数值 = SPIRV-Cross `spvc_resource_type`：`uniform_buffer=1 / storage_image=6 / sampled_image=7 / separate_image=10 / separate_sampler=11`。**两处独立一致**：lwjgl-spvc 3.4.1 的 `org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_*` 常量，与原版 `frontend/shaders/SpvUtil.resourceType(UniformType)`（`COMBINED_IMAGE_SAMPLER→7`、`UNIFORM_BUFFER→1`）；原版 `SpvUtil.DESCRIPTOR_TYPES=[1,2,6,7,10,11]` 就是「被当描述符处理」的集合 | `sampled_image=7` 与 `BindGroupLayout.withUniform(name, COMBINED_IMAGE_SAMPLER)` 同源 ⇒ 「包声明类型 ↔ 反射类型」第一次可对差，这正是 C3 的输入 |
| `baseType` 数值出处 = 原版 `SpvUtil.baseTypeString(int)`：`15=struct / 16=image / 17=sampled_image / 18=sampler` | 区分「图像类 uniform」与 UBO，不需要任何名字知识 |

🔴 **限制 ①：`Type.dimensions()` 的取值拿不到任何公开常量表** —— lwjgl-spvc 3.4.1 的 `Spvc` 与 `Spv`
两个类里都搜不到 `*DIM*` 常量，原版侧也没有暴露对应枚举（`SpvUtil` 只给了 `baseType` 的字符串表）。
⇒ **不许把 `dimensions()` 当 3D / Cube 的唯一判据**（X9：不猜数值）。体积/立方仍以包自己声明的
`uniform sampler3D / samplerCube` 为准（`SamplerDimensionPlan.decide` 已经是这条路），反射在这里的用途是
「声明 ↔ 反射」对差与「反射里有、包声明里没有」的漂移告警。

🔴 **限制 ②：`testCompileClasspath` 取不到二级嵌套类型** —— 同一句 `SpvModule.Reflection.Descriptor`
在 `src/main` 编译通过、在 `src/test` 下 javac 报「程序包SpvModule.Reflection不存在」（2026-10-10 实测：
`./gradlew test` 一次性报 45 个错，形状只出现在测试源集）。
⇒ 探针的「原版对象 → 我方快照」适配层**无法用假实现做单测**，只能在真机注册期用日志自证
⇒ **列入 C1 取证项**：注册期必须打一条可数的 `sampler <名> set/binding/resource` 日志（用 `SpvReflectionProbe.describe`）。
`SpvReflectionProbeTest` 因此只覆盖**决策面**：sampler 过滤、`describe()` 行形状、未验数值保留原值、数值口径钉死。

### 4.3 太阳方向：真值**已经在算**，只差接线（但接线比看上去多一步）

- 已有：`OfUniformManager.java:179-190` 每帧读 `EnvironmentAttributes.SUN_ANGLE/MOON_ANGLE` 并算出
  `sunWorld = (-sin θ, cos θ, 0)`、眼空间 `sunPosition/moonPosition/shadowLightPosition`；
  `probeAngle:738-742` **已把「度」转成弧度**（与原版天空链同链，注释登记了三点位校验）。⇒ 单位不是隐患。
- 缺口：`FrameApi.java:164-174` 用常量构建 `lightSpaceList` 且**懒构建一次即缓存** ⇒ 即使把角度接进去，
  也要连带把「缓存一次」改成「每帧/相机变化时重建」。产出经 `putShadowMatrices`（`OfUniformManager:432-442`）
  进 `shadowModelView/shadowProjection`。

- 🔴🔴 **本文档原判「接完之后阴影才真正跟太阳」是错的 —— 2026-10-10 二次取证更正**。接线只修**矩阵**，
  而**阴影图本身根本没有内容**：
  ① 包的 `shadow` 阶段被 `ProgramStage.SHADOW("shadow",0)`（:35,:108）识别，但**全仓无任何消费者**
  （`grep -rn "ProgramStage.SHADOW" src/main/java` 只命中枚举自身）⇒ 包的 `shadow.vsh/fsh` 从未被装配/绘制；
  ② 包片元声明的 `shadowtex0/1`、`shadowcolor0` 绑的是 **`ShadowStubs` 的 1×1 桩**
  （`bridge/ShadowStubs.java` 类注释自述「桩纹理里**没有真阴影贴图** ⇒ 阴影项不承诺」；绑定见
  `FrameApi.java:1408-1413`、`MrtTerrainPass.java:868`、`TerrainPipelineApi.java:837,973`）；
  ③ 全仓**不存在阴影目标贴图**（`grep shadowTarget|shadowMap|SHADOW_RES` 只命中 `ConstEvaluator:53` 的
  **const 名字白名单**，即「认得 `shadowMapResolution` 这个标识符」，不等于有这张图）；
  ④ `vkdisp:pipeline/shadowed`（`PipelineApi:377,939-953`）是**我方三步演示链**自用的管线
  （原版 `geometry.vsh` + `SHADOW_MAP_PASS` define），与包的 shadow 程序无关，别把它当「阴影 pass 已存在」。
  ⇒ **所以「阴影」这一族的真实缺口是「包的 shadow pass 从未被渲染」，级联与角度都排在它后面**；
  单接太阳方向的取证结果**必然是零像素变化**（采样一张 1×1 桩），若按原判去做会白跑一轮真机取证。
  🔖 该 GAP 目前**未在 `13-GAP-REGISTRY` 单独立项**（GAP-003 的 ③ 与 GAP-015 只记了「绑占位/比较采样器」这半），
  ⇒ **已新开 `GAP-034`**（2026-10-10），并把 QD-11② 的措辞从「太阳方向是常量」升级为
  「包 shadow pass 未渲染（C2b）+ 方向是常量（C2a）」两级。

- 参照的最小正确形态（Iris `ShadowMatrices`/`ShadowRenderer`，**只用事实**）：
  光 modelView = `Rx(90°)·Rz(−skyAngle·360°)·Rx(sunPathRotation)`；按 `shadowIntervalSize`（默认 2 m）
  **texel 对齐**消除游移；`setOrthoSymmetric(2h, 2h, near, far, zZeroToOne)`，半径 = `shadowDistance`（默认 160）。
  ⇒ 我们该从包读取这三项（`shadowDistance`/`shadowIntervalSize`/`sunPathRotation`），而不是自己定常量。
  这三项 + 一张真深度图 + 把可见世界用包的 shadow 程序重画一遍，才是「阴影出现」的最小闭环；
  比较采样器（`sampler2DShadow`，**GAP-001/015**）与 `shadowHardwareFiltering`（`shadowtex0HW` 分离采样器）
  是它的**同族必修项**，不是可选项 —— 否则真图有了、采样仍拿不到比较结果。
- 🔴 一条重要澄清：**OF/Iris 格式根本没有 loader 级 CSM/frustumSplit API** —— 多「级联」是包自己在一张深度图里切区域的惯例。
  ⇒ 「补多级联」不是兼容缺口而是**引擎侧增强**：按 **T12** 收进 `platform/` 并登记 GAP，
  优先级排在「真 shadow pass + 真角度 + 包内半径 + texel 对齐」之后。
- 深度约定沿用既有结论（**X34** 反向 Z、清 `0.0`；`zZeroToOne` 只影响 NDC 带，与反向无关）。
  ⚠️ 阴影图是**我们自己新建的附件** ⇒ 清屏值必须按 X34 取远平面 `0.0`（反向 Z），这是本族最容易静默全黑的点。

### 4.4 统一的修法：包一致性报告（`PackConformanceReport`）

第 4.1 节里 ❌ 的那些项**不该各自加 if**，应当合成一个动作：包加载期把每张硬编码名单与
「包实际声明 + SPIR-V 反射 + `shaders.properties`」对差，产出一张**逐名报告**（命中 / 缺失 / 多余），
缺失与多余一律 WARN（T11），并把这张报告接进 `08-TESTING.md` §10 的兼容矩阵 —— 让「换个包就漂移」第一次变成**可数的事**。
这同时给 `PackCapabilityGate`（`:103-136`，现仅 1 条能力项 + 别名表）提供了数据源，
也顺带回答 `GAP-009` 素材缺失裁决缺的那半个输入（哪个包哪个名字缺哪张贴图）。

✅ **已按此形态落地（C3，2026-10-10）**：`pack/PackConformanceReport`（10 张名单逐一与包对差 + 每单一行的
矩阵文本 + 「包有引擎无」的 WARN），落点在 `PackCompositeSource` 生成期 —— 那里是三源 + 已建好的链 +
包的 `texture.<sampler>=路径` 表**同时**在手的唯一位置（绕到静态视图就会拿到上一张包的数据，QD-12 那一族）。
🔖 两处与原判的**收窄**（如实登记）：① 「接进兼容矩阵」落成 `08-TESTING` **§10.2** 的一列 + 一条日志判据
（`grep '\[C3\] 包一致性报告'`），不是把矩阵重画；② `PackCapabilityGate` 的**数据源已可用但尚未接线**
（本阶只出报告，改能力门的判定要单独一轮，避免与 C2a 混因）。

### 4.5 阶梯

| 阶 | 内容 | 成本 | 动热路径？ |
|---|---|---|---|
| **C0** | ✅ **已执行（2026-10-10）** —— `javap -p SpvModule$Reflection` 表面核实 + `bridge/SpvReflectionProbe`（只做事实登记，不改行为；**暂无生产调用点**，调用点属 C1） | 表面核实结论与两条限制见 §4.2 的「✅ C0 已核实并落码」表 |
| **C1** | ✅ **已执行（2026-10-10，见 `CHANGE_LOG` 一百零六）** —— 名单来源换成**由 OF 命名规则生成**的超集（`pipeline/model/PackSamplerSuperset`，旧 18 名全部落在其中并被逐条钉住）；包声明快照在生成期由 `SamplerDimensionPlan.fromFragmentSource` 装进 `AtomicReference`（兜底路径也重装 ⇒ 不留上一张包的）；视图路由改由**声明类型**决定（`bridge/PackSamplerViews`：3D / 中性材质 / 图集按类型，其余照抄后处理链那条已取证的路），`UNSUPPORTED` **不绑 + 每名点名**。🔴 **一条边界**：绑定组随 required 管线在**启动期**注册一次定死 ⇒ 布局不可能按包变，「派生」= 超集按规则生成 + 视图按类型 + 超集之外点名，这条限制登记为 **`GAP-035`** | 实测 ≈ 1 天（含两轮真机取证） | 🟡 绑定每帧，布局注册一次 |
| **C2a** | 光空间侧的**纯数学**部分：真角度 + 包内 `shadowDistance/shadowIntervalSize/sunPathRotation` + ortho 拟合 + texel 对齐 + 每帧重建 —— **可单测**（输入角度/时间 → 断言矩阵），不动 GPU | 1 天 | ❌（不碰每帧 GPU 路径，只把 `lightSpaceList` 的懒缓存改成随角度失效） |
| **C2b** | 🔴 **包 shadow pass 装配**：新建阴影目标（D32，尺寸取包 `shadowMapResolution`，**清 `0.0`** 按 X34）+ 用包自己的 `shadow.vsh/fsh` 把可见世界重画一遍 + `shadowtex*` 从桩换成真图（同族必修：`sampler2DShadow` 比较采样器 GAP-001/015、`shadowHardwareFiltering` 分离采样器） | **未估**（这是一个独立大项，量级 ≥ GAP-003 的地形装配） | 🔴 是 ⇒ 单独一轮取证 |
| **C3** | ✅ **已执行（2026-10-10，见 `CHANGE_LOG` 一百零六）** —— `pack/PackConformanceReport` 覆盖 §4.1 **全部 10 张名单**（逐名 `HIT / PACK_ONLY / ENGINE_ONLY / ASSUMPTION`），在 `PackCompositeSource` 生成期与三源+链+纹理绑定表**同时**在手的那一点出报告；矩阵用法与「`PACK_ONLY>0` 不许标 ✅」的规矩写进 **`08-TESTING` §10.2**；验收断言 = `PackConformanceReportTest`（十项必须各有节）。🔖 报告只读各子系统真源、**不建第二份名单**，`private` 表体（UV 名 / 世界向量黑名单）只报「经哪个公开判定」不抄内容 | 实测 ≈ 半天 | ❌ 只读，不改行为（真机两轮画面无差，已复核） |
| **C4** | （可选，T12 登记 `platform/`）多帧级联 shadow | 待 C2b 稳定后评估 | 🔴 是 |

**C1 先于 C2a、C2a 先于 C2b** 的理由：C1 与 C2a 都不需要真机取证（纯注册期/生成期逻辑 + 守卫单测 + 矩阵单测），
C2b 要动每帧路径，按 QD-04 的教训必须单独一轮；
🔴 **绝对不要把 C2a 单独包装成「阴影跟太阳了」去取证** —— C2b 未做时它对像素无影响（§4.3 的桩证据）。

---

## 5. 弱点四 —— 注释信噪比 + 反射开关（QD-13 / QD-14）

### 5.1 一条本轮才成立的新论据

`evidence/` 已退库（见项目记忆 `project-evidence-retired.md`，且 `.gitignore` 覆盖）。
⇒ 文件头里那些 `h48 / h49 / h13 / §10.22` 型引用，对**任何新克隆的人（含下一个 AI 会话）都是查不到的悬空指针**。
所以收缩文件头不是品味问题，而是**可追溯性已断**。同理，`QUALITY-DEBT` 里手抄的度量已经开始漂
（QD-03 的「95」vs 实测 175；QD-04 曾经把 21 记成 3）⇒ **凡能被脚本算出来的数字，不许手抄进文档**，
一律由棘轮测试计算并在文档里注明「数值出处 = 该测试的 `BASELINE`」。

### 5.2 注释规约（保持 T13/X19 合规为前提）

- **必须留**：模块的【参考调研】块 —— 因为 `07` §七 自检清单硬要求「新增模块有【参考调研】注释块，且第 0 条写合规结论」。
  规约应是**收缩而非删除**：压成固定 5 行结构（① 合规结论 ② 参考对象 ③ 我们的差异点 ④ 本文件职责 ⑤ 非显然约束/不变量）。
- **该移出**：逐轮取证叙事（哪个日志第几节、哪一轮试错）。去处 = commit message + `review/` 记录；
  行内只留单行 `// 证据: h48（本地 evidence/，不入库）`。
- **该改写的范例**：`OfUniformManager.java:200-214` 那段（`frameTime` 白屏机制 + `frameCounter` 每帧一次）
  是**好注释的样板** —— 它写的是「为什么这个值必须这么算，否则会怎样」，而反面例子是「本轮我查了什么」。
  规约里直接引这一处当正例，比写抽象定义好用。
- 度量：只对**新增文件**硬约束（`HeaderSizeRatchetTest` 易被钻空子，收益不抵成本）；存量按 QD-13 原话「只在被改动时顺手收缩」。

### 5.3 反射开关：有官方编译期替代

现状：4 个 Switch 类 `Class.forName("dev.vkdisp.VkDispConfig")` + `getField("CAPABILITY_GATE")`
（`PackCapabilityGateSwitch.java:94-97` 等），键名与字段名分离的注释（:42 vs :59）就是 h33 事故的疤；
失败已可见（`reflectionFailure()` :150-152，被 `PackPostChain:297,394` 读出）。
但「字段改名 ⇒ 开关静默回默认」这条路径仍在（现在是「可见降级」，QD-14 判 🟡 合理）。

替代：`ModConfigSpec` 的类型化路 —— `Builder.configure(...)` / `define` 返回 `ConfigValue<T>`，
读值是编译期方法引用，**改名 = 编译不过**，反射与字符串都消失。Switch 类与 `VkDispConfig` 本就同 jar，
`Class.forName` 的解耦动机（类加载顺序？）没有成文依据。
⇒ **先核实动机**（1 步，别跳过）：无硬约束 ⇒ 改直接引用；确有硬约束 ⇒ 保留反射但加元测试
断言 `FIELD_NAME` 常量与 `VkDispConfig` 实际字段一一对应（与 §3.4 棘轮同族）。

### 5.4 阶梯

| 阶 | 内容 | 成本 |
|---|---|---|
| **D0** | 注释规约进 `07-CONSTRAINTS` §3.5 + 修 `VkDispPackScan` 重复 import + 把 QD-03/QD-04 的数字改成「由测试计算」 | 25 min |
| **D1** | 反射开关：核实动机 → 类型化改造或元测试守卫 | 1 h |

---

## 6. 跨弱点排序：为什么是这个顺序

```
A0 (止血/可见性)  ──┐                     ← 零风险，1 h，立刻消除 X11 违例
B0 (棘轮+reset契约) ┘                    ← 之后任何拆分都在「不许变差」的护栏内进行
   ↓
C1 (sampler 派生) ── C0 (SpvModule 表面核实) ← 机制已在仓，纯接线；不动热路径
   ↓
A1 (单一 token 流) → A3 (#line+归因) → A2 (jcpp 实现 + shaderc oracle) → A4 (声明语法级)
   ↓                                       ↓
C3 (一致性报告)                      B1 (PackEpoch) → B2 (诊断/注册外提) → B3 (所有权三级)
   ↓
C2a (光空间数学，可单测、不碰 GPU) → C2b (包 shadow pass 装配) ← 单独一轮真机取证
                                       └→ C4 (级联，可选，T12/platform)
```

**三条依赖是硬的**：`A1` 挡在 `A2/A4` 前（否则再造两套扫描器）；`B0` 挡在 `B3` 前（否则拆到一半没有护栏）；
`C0` 挡在「把 §4.2 权威臂当方案」前（X41：**没核实过的 API 表面不许当设计地基**）。

按三支柱打分的取舍（🔴 高分 = 优先）：

| 项 | 兼容收益 | 稳定收益 | 性能影响 | 成本 | 需真机取证 |
|---|---|---|---|---|---|
| A0 | ★（防错展开） | ★★★（消静默） | 0 | 1 h | ❌ |
| B0/B1 | ★ | ★★★（消 QD-08 族） | 0（取键冷路径） | 半天+1天 | ❌ |
| C1 | ★★★（换包不漂） | ★★ | 微（注册期） | 2h+1d | ❌ |
| A1/A3/A4 | ★★（少打补丁） | ★★★（可定位） | 冷路径，须报 B3/B4 | 4–5 天 | ❌ |
| C2a | ★（为 C2b 备好正确矩阵） | ★★（消灭「常量当真相」） | 0（不碰 GPU） | 1 天 | ❌ |
| C2b | ★★★（阴影第一次真实） | ★★ | 每帧多一遍世界绘制 ⇒ **必测 B1/B2** | 未估 | 🔴 单独一轮 |
| A2-shaderc 臂 | ★★ | ★★★（差分 oracle ⇒ 盲区变可见） | 冷路径，可只在测试/诊断里跑 | **探针 0.5 天**（原版已带该原生库 ⇒ 无分发成本） | ❌（oracle 只进单测/离线工具） |

---

## 7. 裁决记录与三个待判点

### 7-1 ✅ 已裁决（2026-10-10）：允许移植第三方代码

用户判：**「100% 自研」是早期文档自己加的限制，不是 MIT 的要求；合规底线只是完全遵守 MIT、不越界。**
⇒ **移植 jcpp（Apache-2.0）核心获准**，A2 的实现臂定为 jcpp，「照 ANGLE 全自研」降为备选。
✅ **随之而来的文档债已同步（2026-10-10）** —— 三处「MIT ⇒ 完全自研」的旧表述已改为
「只并入 MIT/Apache-2.0/BSD 族，且保留 `LICENSE`/`NOTICE` + 声明改动」：
`03-DIRECTION.md` §0 共同结论（🔖 更正注记）、`07-CONSTRAINTS.md` §〇 正文 + §1.3 判定表
（新增 BSD 行与「不可带走」两列）+ §七 自检清单（新增「并码须留 LICENSE/NOTICE 且声明改动」一条）、
`AGENT_CONTEXT.md` §0 新定位与硬约束条目。
🔖 本文初版把出处写成「`03-DIRECTION.md` §4」，实际那句在 **§0**（§4 是许可证策略表，讲的是 LGPL/GPL/ARR 边界，无需改）。
`07` §〇 P1/P2/P3 与 L5–L8 **不受影响**（那是 LGPL/GPL/ARR 的边界，仍然有效）。

### 7-2 ✅ 已裁决（2026-10-10）：允许引用游戏自带的 `lwjgl-shaderc`，采用方案 (b)「只作差分 oracle」

**原判已失效**：本文初版把它写成「要新增原生分发、撞 T15/G 闸门」。二次取证发现
**原版客户端自带 `org.lwjgl:lwjgl-shaderc`（含 natives）**，且其 `Shaderc.class` **确有**
`shaderc_compile_into_preprocessed_text` 符号（证据见 §2.3 与附录 A5）⇒ 成本不是「打包原生库」，只是
「`compileOnly` 引一个已在客户端类路径上的 LWJGL 坐标 + 版本入 `gradle.properties`」。

**那它该扮演什么角色？** 三个选项：

| 方案 | 好处 | 代价/风险 | 我的建议 |
|---|---|---|---|
| (a) 完全不碰 shaderc，只自研/jcpp | 依赖面最小 | 差分对表无从做起 —— **jcpp 是否真按 GLSL 语义处理 `#version/#extension` 仍是未知** | ❌ 浪费一个免费的正确性来源 |
| (b) **shaderc 只作 oracle**：生产路 = jcpp；单测/离线工具里把同一份源喂 shaderc 预处理，逐字节对 diff | 语义分歧**在提交前**暴露（而不是在真机上以画面异常暴露）；不进热路径 ⇒ 无 T15/B1 风险 | 需处理行标记差异（`#line` 格式不同）⇒ 对表时按「忽略行标记后的有效行」比较；版本随 MC 升级漂移 ⇒ 入 `06-MIGRATION` 核查清单 | ✅ **推荐** |
| (c) shaderc 作生产实现 | 与真正编译器零分歧，最省心 | 预处理从此依赖一条 FFI 路，**T15 的「Java 路必须完整可用」要额外维护**；跨平台差异由我们承担；且把冷路径绑到游戏自带的原生库上，升版风险直接进生产 | 🟡 除非 (b) 对表后发现 jcpp 语义不达要求，否则不上生产 |

**用户裁决（2026-10-10）**：「引用游戏自带 lwjgl-shaderc」⇒ 采纳 **(b)**：生产路仍是 jcpp，
shaderc **只当差分 oracle**。落地边界（写死，越界就回来重判）：

| 约束 | 具体口径 |
|---|---|
| 只进**测试/离线工具** | `build.gradle` 里只能是 `testImplementation` / `compileOnly`；🔴 **不得进 `implementation`、不得进发布 jar、运行期代码不得 `import` 它** |
| 版本单一数据源 | 新键 `lwjgl_shaderc_version`（当前 = **3.4.3**，与 26.3 清单逐字一致）入 `gradle.properties`；`07` §5.1 已登记锁法 |
| 升版必查 | `06-MIGRATION` §3 **V6**（26.2=3.4.1 / 26.3=3.4.3 是实测漂移样本） |
| 拿不到原生库时 | oracle **必须显式跳过并自证为什么跳**（`ASSUMPTION`/打印缺失原因），🔴 不许让「oracle 没跑」伪装成「oracle 通过」 |
| 对表口径 | 忽略行标记差异（`#line` 两家格式不同）⇒ 比「去掉行标记后的有效行」，逐字节 diff 只在同一段上做 |

✅ **原先挂着的「26.3 版本号待查」已于同日结掉**：26.3 版本清单声明的是 **3.4.3**
（本机可复算，命令见附录 A5），3.4.3 的 `Shaderc.class` 含预处理入口。
🔴 而这条核实顺带给出一个**必须入核查表的理由**：同一坐标在 **26.2 = 3.4.1 / 26.3 = 3.4.3**，
**版本号随 MC 小版本漂移** ⇒ 若引用它，版本入 `gradle.properties` 之外**必须**同时进 `06-MIGRATION`
升版核查表（否则会得到一个「编译期版本与游戏实际自带版本不一致」的静默分歧，正是 X31 那族病）。

### 7-3 ✅🟡 CSM 级联：已按「暂不做」执行（改立 `GAP-034`）；**剩「C2b 排不排下轮真机」待判**

原问法是「级联做不做」。二次取证后真正该判的是**「包的 shadow pass 装配（C2b）在不在近期目标内」**：

- 事实：`shadowtex*` 现在是 1×1 桩、包 `shadow` 阶段零消费者、没有阴影目标贴图（§4.3）⇒
  **阴影这一族目前不是「精度差」，是「整个不存在」**。
- OF/Iris 格式**没有** loader 级 CSM/frustumSplit API，级联是包在**一张深度图内部**自己切区域的惯例
  ⇒ 所以「做 4 级级联」**既不是兼容必要条件**，反而可能误导（包以为拿到的是自己那张单图）。
- 建议排序：**C2a（数学，1 天，零风险）→ C2b（装配，大项，单独一轮）→ 比较采样器（GAP-001/015，与 C2b 同轮必修）→ C4 级联（可选，`platform/` + T12 登记）**。
  ✅ **已执行（2026-10-10）**：级联**暂不做**，改为登记真正的大项 —— 新立 **`GAP-034`「包的 `shadow` 程序从未被装配/渲染」**
  （此前该事实只在 GAP-003③/GAP-015 里各记了半个因）。
- 需要你判：**(ii) C2b 是否排进下一轮真机取证**（量级不小，建议先做 C1/C3）。(i) 已按上述执行，无需再判。

### 7-4 ⏳ 真机取证轮次：建议**不要在 C2a 之后立刻跑**

原判（「C2 排哪一轮取证」）现在应改为：**取证轮留给 C2b**，理由：
C2a 不碰 GPU ⇒ 它的判据是**单测矩阵断言**，跑真机只会得到一个「零像素变化」的空结果并**污染归因**
（正是 X49/X46 反复警告的形态）。C2b 的取证轮设计要点（先写进方案，省得临场补）：

- 判据必须能区分「阴影有没有」：X38 ⇒ 用**有空间结构**的内容（地形轮廓 + 太阳低角度时的长影），不用渐变/常量指纹；
- 固定时间：`set_time`（自检清单已列）+ **连拍而非单帧**（X48）；阴影本身随时段移动，单帧不可比；
- 登记窗口尺寸/GPU/驱动（X53），并与 `review/2026-10-04-GAP009` 待裁决项同轮起跑（省一次启动，且两者不互为前提）；
- B1/B2 影响必须报（多一遍世界绘制），但按取证铁律①：**llvmpipe 上的数字不下性能结论**。

---

## 附录 A · 复算命令（本轮数字全部由此而来）

```bash
# A1 静态非 final 字段总数与分布（口径：单行声明；不含 static final）
grep -rhoP "static\s+(?!final)[\w<>?\[\], .]+\s+\w+\s*(=|;)" --include=*.java src/main/java | wc -l
grep -rcP "static\s+(?!final)[\w<>?\[\], .]+\s+\w+\s*(=|;)" --include=*.java src/main/java \
  | awk -F: '{d=$1; sub("src/main/java/dev/vkdisp/","",d); sub("/[^/]*$","",d); if(d=="")d="(root)"; s[d]+=$2}
             END{for(k in s) print s[k], k}' | sort -rn

# A2 >1000 行的类
find src/main/java -name '*.java' | xargs wc -l | awk '$1>1000 && $2!="total"'

# A3 #line 发射点（应为 0）/ 反射开关类 / 续行唯一处理点
grep -rn '"#line\|#line ' src/main/java --include=*.java | wc -l
grep -rln "Class.forName" src/main/java --include=*.java
sed -n '178,186p' src/main/java/dev/vkdisp/glsl/translate/GlslTextScan.java
sed -n '105,135p' src/main/java/dev/vkdisp/glsl/preprocess/DefineProcessor.java   # 逐行、无续行状态

# A4 「包的 shadow pass 其实不存在」四条证据（§4.3 / §7-3）
grep -rn "ProgramStage.SHADOW" src/main/java --include=*.java                 # 只命中枚举自身 ⇒ 零消费者
grep -rn "ShadowStubs\." src/main/java --include=*.java                        # 绑的是 1×1 桩
grep -rn "shadowTarget\|shadowMapResolution" src/main/java --include=*.java    # 只命中 ConstEvaluator 的 const 名字白名单
grep -n "SHADOWED_LOCATION" src/main/java/dev/vkdisp/bridge/PipelineApi.java   # 我方三步演示链，非包的 shadow

# A5 「shaderc 预处理入口游戏自带」证据（§2.3 / §7-2）—— 2026-10-10 已按**主线 26.3**复核
M=~/.gradle/caches/neoformruntime/artifacts/minecraft_26.3_version_manifest.json
grep -o '"org.lwjgl:lwjgl-shaderc:[0-9.]*"' "$M" | sort -u   # ⇒ "org.lwjgl:lwjgl-shaderc:3.4.3"（+ 各平台 natives）
grep -o '"org.lwjgl:lwjgl-opengl:[0-9.]*"'  "$M" | sort -u   # ⇒ 3.4.3 —— 同一 LWJGL 线，佐证不是个例
unzip -p ~/.gradle/caches/modules-2/files-2.1/org.lwjgl/lwjgl-shaderc/3.4.3/*/lwjgl-shaderc-3.4.3.jar \
       org/lwjgl/util/shaderc/Shaderc.class | grep -ac shaderc_compile_into_preprocessed_text   # ⇒ 1
# 对照：**26.2** 的清单（~/.gradle/caches/minecraft/versions/26.2/metadata.json）里该坐标是 **3.4.1**
# ⇒ 同一坐标随 MC 小版本漂移（3.4.1 → 3.4.3）⇒ 若引用它，除版本入 gradle.properties 外
#   **必须**进 `06-MIGRATION` 升版核查表（§7-2 的结论）
```

## 附录 B · 调研来源与许可证核对（L12 第 0 步产物）

| 参考 | 许可证判定来源（**只信仓库 LICENSE 文件**） | 结论 |
|---|---|---|
| jcpp `org.anarres:jcpp:1.4.14` | [LICENSE](https://github.com/shevek/jcpp)/LICENSE + [POM](https://repo1.maven.org/maven2/org/anarres/jcpp/1.4.14/jcpp-1.4.14.pom) = **Apache-2.0** | 可并码（留署名+NOTICE，改动需声明）；⚠️ 传递依赖含 guava/slf4j/ant/logback ⇒ 移植核心或排除表**待实测** |
| shaderc | [LICENSE](https://github.com/google/shaderc/blob/main/LICENSE) = **Apache-2.0**；LWJGL 绑定 [lwjgl-shaderc](https://repo1.maven.org/maven2/org/lwjgl/lwjgl-shaderc/) = **BSD-3** | ✅ **不需要我们分发原生库** —— 原版客户端自带该坐标**且含预处理入口**（证据 A5；**26.3 = 3.4.3**，26.2 = 3.4.1 ⇒ 版本随 MC 漂移）⇒ 只需 `compileOnly` 引用；定位建议见 §7-2 |
| glslang | [LICENSE.txt](https://github.com/KhronosGroup/glslang/blob/main/LICENSE.txt) + [REUSE.toml](https://github.com/KhronosGroup/glslang/blob/main/REUSE.toml)：核心 BSD 族/MIT/Apache；**`Pp*` = BSD-3 + `AML-glslang`**（[文本](https://github.com/KhronosGroup/glslang/blob/main/LICENSES/AML-glslang.txt)） | 🟡 预处理槽**不成立**（[ShaderLang.h](https://github.com/KhronosGroup/glslang/blob/main/glslang/Public/ShaderLang.h) 原话「非官方支持」）；并码需逐条核实非标文本 |
| ANGLE `src/compiler` | [LICENSE](https://github.com/google/angle/blob/main/LICENSE) = **BSD-3-Clause**（无 Mesa/LGPL 例外） | 架构可用；分层照它 |
| Mesa glcpp | [glcpp.h](https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/compiler/glsl/glcpp/glcpp.h) 逐文件 = **MIT** | 语义 oracle；不移植 flex/bison |
| SPIRV-Reflect / SPIRV-Cross | [LICENSE](https://github.com/KhronosGroup/SPIRV-Reflect/blob/main/LICENSE) / [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross/blob/master/LICENSE) = **Apache-2.0** | 可（但本项目优先用原版已有反射面，见 §4.2） |
| ANTLR `grammars-v4/glsl` | [GLSLLexer.g4](https://github.com/antlr/grammars-v4/tree/master/glsl) 逐文件 = **MIT** | 🔴 目标 GLSL 4.60、无展开引擎、维护弱 ⇒ 不作地基 |
| tree-sitter-glsl | [LICENSE](https://github.com/tree-sitter-grammars/tree-sitter-glsl/blob/master/LICENSE) = **MIT** | 🔴 158 行、tree-sitter-c 派生 ⇒ 不覆盖 GLSL ES |
| **glsl-transformer** (Douira) | [LICENSE](https://github.com/Douira/glsl-transformer/blob/master/LICENSE) = **AGPL-3.0**（README 还要求使用方同许可） | ⛔ **禁止**（X20/X21）——「java glsl parser」搜索首位命中，务必挡住 |
| **glsl-preprocessor** (IrisShaders) | POM = **GPL-3.0 + 例外条款** | ⛔ **禁止**（X21，`07` §1.3 陷阱 2 点名此类） |
| Iris（LGPL-3.0）/ Vitrail（LGPL-3.0）/ VulkanMod（LGPL-3.0）/ Sulkan（GPL-3.0）/ OptiFine（ARR） | 见 `03-DIRECTION.md` 附录 A + 各仓库 LICENSE | ❌ 一行不抄；只取本文标注为「事实/思路」的结论 |
| **GLSL 规范（续行与 `#define` 语义）** | [GLSLangSpec.4.60](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html) | 规范内容不受版权保护 ⇒ 可作 A2 的行为依据 |
| `EnvironmentAttributes.SUN_ANGLE` 语义 | [minecraft.net 1.21.11 发布文](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)：**度数**、自东向西顺时针、随相机位置插值 | 与 `probeAngle:738-742` 的度→弧度转换一致 ⇒ §4.3 判定「单位不是隐患」 |
| OF 内建 uniform 语义（`sunAngle` 0..1 / `sunPosition` 视空间 ×100 / `shadowLightPosition` 择日或月） | [OptiFineDoc shaders.txt](https://github.com/sp614x/optifine/blob/master/OptiFineDoc/doc/shaders.txt)（原文即 OF 文档） | 用于校 `OfUniformManager:187-190` 的供值口径 |
| CSM（级联切分 + texel snap） | [Microsoft CSM](https://learn.microsoft.com/en-us/windows/win32/dxtecharts/cascaded-shadow-maps) / [NVIDIA CSM 论文](https://developer.download.nvidia.com/SDK/10.5/opengl/src/cascaded_shadow_maps/doc/cascaded_shadow_maps.pdf) | 通用图形学知识，非本项目代码依据 |
| 棘轮先例与失败模式 | [detekt baseline](https://detekt.dev/docs/introduction/baseline/) / [ESLint bulk-suppressions](https://eslint.org/docs/latest/use/configure/migration-guide) / [Strangler Fig](https://martinfowler.com/bliki/StranglerFigApplication.html) / [Feathers characterization](https://industriallogic.com/x/efChars.html) | 支撑 §3.4 的护栏设计（含「基线爬升」「扫描器漂移」两个已知坑） |
| NeoForge 类型化配置 / 重载契约 | [neoforged/documentation config.md](https://github.com/neoforged/documentation/blob/HEAD/docs/misc/config.md) · [reloadlisteners.md](https://github.com/neoforged/documentation/blob/HEAD/docs/resources/reloadlisteners.md) | 支撑 §5.3 与 §3.4(2) |
| 所有权/代际句柄 | [AutoCloseable Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/AutoCloseable.html) · [slotmap（generational index）](https://docs.rs/slotmap/latest/slotmap/) | 支撑 §3.4(1)(2) |

## 附录 C · 本文对既有文档的影响（✅ = 2026-10-10 已同步；未勾 = 仍待办）

| 文档 | 影响 | 处置 |
|---|---|---|
| `13-GAP-REGISTRY.md:32`（GAP-005） | 「glslang 可作预处理槽」不成立；许可描述过粗 | ✅ **已拆 005a/005b 并原地更正**（含 jcpp / shaderc 两条低成本臂与「先过 A1」的顺序约束） |
| `13-GAP-REGISTRY.md`（新立） | 🔴 包的 `shadow` 程序从未被装配/渲染、无阴影目标贴图 —— 现只在 GAP-003③/GAP-015 记了半个因 | ✅ **已新增 `GAP-034`**（含 C2a/C2b 两级、比较采样器列为同轮必修、判据与 X38/X48 取证纪律）；本文 §4.3 的「原判错」更正与之对齐 |
| `QUALITY-DEBT.md` QD-03 / QD-04 | 95 → **175**；「6 个渲染编排方法待拆」仍待办 | ✅ QD-03 已就地标注复算值与口径，并规定今后由棘轮计算；QD-04 的 21 不变；§0 新增 QD↔本文小节映射表 |
| `QUALITY-DEBT.md` QD-11 | ② 「接方向即可」原判**不完整**（阴影图根本不存在）；① 原判**偏贵**（派生机制已在仓） | ✅ 已就地更正两级拆分 + 「1 天可闭环」估计作废，并指向 `GAP-034`；另记 `SamplerDimensionPlan:28-32` 早按 **X39** 否决过按名字硬编码 |
| `QUALITY-DEBT.md` QD-14 | 旧方案给反射找的解耦理由若含「保持 100% 自研」，该前提已被撤销 | ✅ 已就地标注前提消失 + 指向本文 §5.3；🔖 原注释只提「`#ifndef` 求值链」，**未证反射是必须的** ⇒ 核实动机这步保留 |
| `03-DIRECTION.md` §4 + `07-CONSTRAINTS.md` §〇 | 「MIT ⇒ 100% 自研」**不是 MIT 的要求**（用户 2026-10-10 裁决，§7-1） | ✅ 两处已改写为「只并入 MIT/Apache-2.0/BSD 族，保留 `LICENSE`/`NOTICE` 并声明改动」；`07` §1.3 判定表补 BSD 行、§七 自检清单新增一条署名核对项；**L5–L8（LGPL/GPL/ARR）与 P1/P2/P3 未动** |
| `07-CONSTRAINTS.md` §五 依赖版本锁定 | 引入 `org.anarres:jcpp`（移植）或 `compileOnly org.lwjgl:lwjgl-shaderc` | ✅ **已登记锁法**（`07` 新增 **§5.1**：两个坐标各自的版本键 + 并码署名义务 + 「游戏自带坐标会漂」这条）。🔴 配套实测：**26.2 = 3.4.1 / 26.3 = 3.4.3** ⇒ 已写进 `06-MIGRATION` §3 **V6**（升 MC 必查）与 `05-VERSION` §2 的事实行。⚠️ **依赖本身仍未引入** —— §7-2 那句「测试/离线工具可否引用」还待判，本节只登记「放行后怎么锁」 |
| `16-READING.md` §3 / `00-INDEX.md` §1 | 新增本文 | ✅ 已登记 |
