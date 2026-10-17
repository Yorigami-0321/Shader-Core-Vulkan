# 19 · 弱点改进路径（调研 → 方案 → 阶梯 → 验收）

> **性质**：方案书。**本轮不改任何代码**，只做调研、取证式定位与路线设计。
> **对象**：核心代码审查归纳出的四类「实验期原型形态」弱点 —— ①文本级翻译脆弱 ②巨型类 + 静态可变状态
> ③硬编码假设 ④注释信噪比低 + 反射读配置。对应 `QUALITY-DEBT.md` 的 **QD-09 ~ QD-14**。
> 🔴 **2026-10-12 增补第五类**：⑤**接入形态**（该注入的地方在用手抄原版 / 反射读 private / 进程级静态标志
> / 图外重放 + 手工同步）—— 见 **§5A**，与 QD-09~14 **不同源**（那四类改「代码怎么写」，本类改「代码挂在哪儿」）。
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
> ✅ **§7-9 已裁决（2026-10-17）**：换证 **LGPL-3.0-or-later** + 光影包特性层**直接移植 Iris / Vitrail**（GLSL 转换层只搬规则、不引 AGPL 库）。
> ✅ **§7-5 已裁决（2026-10-12）**：mixin 的**类别**限制废除（**登记制 / 可关闭制 / 逐个开启三条保留**）、
>   **X24 作废**、**X23 保留**、**M-04 整主 pass 多附件排为下一轮目标**。
>   ⇒ 方案与阶梯见新增的 **§5A**（R0…R7）；逐点登记见 `04-SPEC.md` §5.0 的 M-04 / M-06…M-14；
>   本轮新登记的缺陷是 `GAP-038`…`GAP-043`。
> ✅ **§7-6 同日已判（2026-10-12 第二轮）**：§5A 那条待判（「R1 的幂等收敛怎么做」）**结论是原设想被证伪** ——
>   modifier 的缓存键没有「包代次」这一维、key 又不能运行期增删 ⇒ **M-01 保留**，R1 改形态为 **R1a（新增 M-15）**；
>   M-04 的牵连面清单已交付在 `04-SPEC` §5.0.6（范围缩小到 1 个 GPU pass、量级 ≈58–62 条、真实风险是开放集合）。
>   新登记 `GAP-044`…`GAP-046`。⛔ R7 仍不许开工，直到 §5.0.6 ⑤ 的三项待核结掉。
>   ✅ **该锁已解除并已开工（2026-10-12 同日后续几轮）**：三项待核已结（`04-SPEC` §5.0.6 ⑤）、牵连面执行序已定（§5.0.7），
>   ⑤-a 与 ⑤-b 两步各自**已落地 + 真机签掉**（`MainPassAttachmentMixin` + `MainPassTakeover` / `MainPassPipelineDerivation`）；
>   新登记 `GAP-047`…`GAP-050`。R7 现在停在 **⑤-c**（逐族放开），地形双绘留给 ⑤-d，深度单点化留给 ⑤-e。
> 前提更正两处：§7-2 的「要新增原生分发」不成立、§7-3 的「只差太阳方向」不成立 —— 见各节。
> §2.6-A0 / §3.5-B0 / §5.4-D1 属「不等裁决即可做」。

---

## 0. 怎么读这份文档

每个弱点四段：**现状证据**（全部 `file:line` + 附录 A 可复算）→ **病根**（不是症状列表，是「为什么会反复出现」）
→ **调研结论**（别人怎么做、哪些能用码哪些只能用思路）→ **阶梯**（每阶：改动面 / 判据 / 规模 / 动不动热路径）。

🔴 **本文各阶梯表的「成本」列不填工时**（2026-10-14 起，`07-CONSTRAINTS.md` X56 / `15-ITERATION.md` A18）：
只写**轮次、臂数、牵连面条数**（「单独一轮真机取证」「主 pass 内还剩 6 处」「≈58–62 条管线」），没有规模可写就填 `—`。
排期归用户裁，文档只给**序**与**判据** —— 序见 §6 的依赖链与 §5A.4 的执行序。

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
| **Vitrail** `common/.../glsl/` | LGPL-3.0 | ✅ **可搬**（2026-10-17 换证；台账另记 Iris） | 事实：它**自建无损 token 词法器**，注释原话「保留每个空格、注释、换行，使未改动的流能拼回完全相同的原文」；并有 `TokenStream/IncludeExpander/Macros/SamplerPlan`；SPIR-V 交给原版 `GlslCompiler`（shaderc），随后 **SPIRV-Cross 反射**读它的产物 | 🟢 这两条正是 L1 与 §4.2 的目标形态（**只用结论，代码一行不碰**，且它 NOTICE 自承其 `DRAWBUFFERS/const` 语法派生自 Iris ⇒ 那部分连思路都按 LGPL 处理） |

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

| 阶 | 内容 | 落点（现状 file:line） | 判据（X46：可数的日志/断言） | 规模 | 动热路径？ |
|---|---|---|---|---|---|
| **A0** | ✅ **已执行（2026-10-10）** —— 止血：把静默变可见，**零语义变化** | 实际落点：`DefineProcessor` ①指令行行尾 `\` → WARN（`warnAt` 走与 `errorAt` 同一条包内归因路）②**新增**「指令行位于注释内」→ WARN（复用 `translate/CommentState`，为其上调可见性到 public，**不造第二套扫描器**）；`IncludeProcessor` 注释内 `#include` → WARN；`IoLocationAdapter` 跨行/半截 in-out 按**行首括号深度**分流（0 ⇒ WARN，>0 ⇒ 函数参数折行仍安静） | 5 条负样本单测（`DefineProcessorTest` 2 / `IncludeProcessorTest` 1 / `IoLocationAdapterTest` 2）断言**「必须 WARN，不得静默」**，全绿且全套无回归 | — | ❌ 冷路径 |

🔖 **A0 执行时对原判的三处修正**（下一阶按这些事实走，别按原判的措辞）：
① 「宏体内 `gl_` 名」的可见性**不落在 `LegacyBuiltinInjector`** —— 转译跑在预处理之后，宏体展开后已是代码文本
（§2.1 该行本就标「可见」）；真正的静默根因是 `/* */` 内的 `#define` 被当真指令吃下，故 WARN 落在 `DefineProcessor`。
② `IncludeProcessor` 的注释内 `#include` **本阶照旧展开**（只 WARN）——「不展开」是语义变更，归 A2 由 jcpp 一次做对。
③ `IoLocationAdapter` 不能无条件 WARN：函数参数折行与真跨行声明走同一分支，原判没说怎么区分 ⇒ 用括号深度。
| **A1** | 建 L1 单一 token 流；`translate/` 各阶段改用它；`GlslDeclarationExtractor` 的第二套扫描器并入 | 以 `CommentState.java:63-96` + `GlslTextScan.java:76-219` + `ExprEval` 词法为地基 | 新守卫 `GlslLexerSingleSourceTest`：扫 `glsl/**` 里新出现的「行首锚定正则声明」（X43 口径）并禁止增长；`GlslPipelineTest` 幂等断言必须继续绿 | — | ❌ 冷路径 |
| **A2** | 建 L2 预处理语义：**jcpp 核心移植为实现**（`Lexer/JoinReader/Token/Macro/展开`，留 Apache-2.0 LICENSE/NOTICE + 声明改动；**2026-10-10 已获用户裁决允许**），**shaderc 预处理作差分 oracle**（✅ §7-2 已放行：只进测试/离线工具，原版自带 ⇒ 零分发成本，见 §2.3）；「照 ANGLE 分层全自研」降级为 jcpp 不可用时的备选 | 替 `DefineProcessor`(719) / `ConstEvaluator`(402) 的相应职责 | 先做**三路差分探针**（不承诺替换）：同一批包源跑 ①现自研 ②jcpp ③shaderc，比 **逐字节 diff** + **产物能否被 shaderc 正式编译**；重点验 `#version/#extension/#pragma` 透传、`\u0000`、函数宏、`#include` 相对路径、`\` 续行（含 `\` 后带尾随空格这种包实测写法）；再报 B3/B4 变化（**X32**：不许拿公开基准当选型结论；**X52**：两臂只差一个实现） | 先差分探针、再落地（续行/函数宏/注释语义已由 Apache-2.0 实现做完，省掉的正是自研最易出错那段） | ❌ 冷路径 |
| **A3** | 发射 `#line` + 把 shaderc 原文映射回包行 + 「转译产物不可编译 = 本项目 ERROR」分类 | `ShaderCompileApi.java:121-123`、`VkDispPackScan.java:213-236`（两种日志形状之间加因果归属） | 一条故意写坏的转译产物 → 日志里必须出现**包内**文件与行号；`grep` 计数判据 | — | ❌ 冷路径 |
| **A4** | 声明解析语法级：跨行声明 / `layout(...)` / 同行多语句 / 块作用域 | `GlslDeclaration.java:82-124`（`layout` 返回 null 的分支）、`UniformInjector.java:239-266`、`PostVertexLinker.java:542-580` | 每条各 1 个正样本 + 1 个负样本；X43「一行可有多声明」升为全层共享断言 | — | ❌ 冷路径 |

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

| 阶 | 内容 | 规模 | 动热路径？ |
|---|---|---|---|
| **B0** | 三条棘轮 + `@TestOnly` 约定 + `reset/close` 契约测试（QD-03 首批点名 `FullscreenPassHook`/`OfUniformManager`/`ActivePackUniforms`） | — | ❌ |
| **B1** | `PackEpoch` + 缓存条目带代际 + 解引用校验；`takeSourceMemo` 造键/校键合一 | — | 🟡 取键在冷路径，解引用每帧一次 |
| **B2** | 拆 `TargetReadback` → 诊断对象；`VkDispVirtualPack.VirtualPackResources` 外提 | — | ❌ 纯诊断 / 纯注册 |
| **B3** | `FrameContext`/`PackSession`/`DeviceScopedPool` 三级所有权；`FrameApi` 按 §3.1 逐簇外提（`BuiltinsRingPool`/`MipPyramidPass`/`PostChainExecutor` 单独取证轮） | 分 2 轮 | 🔴 是 ⇒ 每阶各一轮取证（QD-04 先例） |

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

| 阶 | 内容 | 规模 | 动热路径？ |
|---|---|---|---|
| **C0** | ✅ **已执行（2026-10-10）** —— `javap -p SpvModule$Reflection` 表面核实 + `bridge/SpvReflectionProbe`（只做事实登记，不改行为；**暂无生产调用点**，调用点属 C1） | 表面核实结论与两条限制见 §4.2 的「✅ C0 已核实并落码」表 |
| **C1** | ✅ **已执行（2026-10-10，见 `CHANGE_LOG` 一百零六）** —— 名单来源换成**由 OF 命名规则生成**的超集（`pipeline/model/PackSamplerSuperset`，旧 18 名全部落在其中并被逐条钉住）；包声明快照在生成期由 `SamplerDimensionPlan.fromFragmentSource` 装进 `AtomicReference`（兜底路径也重装 ⇒ 不留上一张包的）；视图路由改由**声明类型**决定（`bridge/PackSamplerViews`：3D / 中性材质 / 图集按类型，其余照抄后处理链那条已取证的路），`UNSUPPORTED` **不绑 + 每名点名**。🔴 **一条边界**：绑定组随 required 管线在**启动期**注册一次定死 ⇒ 布局不可能按包变，「派生」= 超集按规则生成 + 视图按类型 + 超集之外点名，这条限制登记为 **`GAP-035`** | 含两轮真机取证 | 🟡 绑定每帧，布局注册一次 |
| **C2a** | 光空间侧的**纯数学**部分：真角度 + 包内 `shadowDistance/shadowIntervalSize/sunPathRotation` + ortho 拟合 + texel 对齐 + 每帧重建 —— **可单测**（输入角度/时间 → 断言矩阵），不动 GPU | — | ❌（不碰每帧 GPU 路径，只把 `lightSpaceList` 的懒缓存改成随角度失效） |
| **C2b** | 🔴 **包 shadow pass 装配**：新建阴影目标（D32，尺寸取包 `shadowMapResolution`，**清 `0.0`** 按 X34）+ 用包自己的 `shadow.vsh/fsh` 把可见世界重画一遍 + `shadowtex*` 从桩换成真图（同族必修：`sampler2DShadow` 比较采样器 GAP-001/015、`shadowHardwareFiltering` 分离采样器） | **未估**（这是一个独立大项，量级 ≥ GAP-003 的地形装配） | 🔴 是 ⇒ 单独一轮取证 |
| **C3** | ✅ **已执行（2026-10-10，见 `CHANGE_LOG` 一百零六）** —— `pack/PackConformanceReport` 覆盖 §4.1 **全部 10 张名单**（逐名 `HIT / PACK_ONLY / ENGINE_ONLY / ASSUMPTION`），在 `PackCompositeSource` 生成期与三源+链+纹理绑定表**同时**在手的那一点出报告；矩阵用法与「`PACK_ONLY>0` 不许标 ✅」的规矩写进 **`08-TESTING` §10.2**；验收断言 = `PackConformanceReportTest`（十项必须各有节）。🔖 报告只读各子系统真源、**不建第二份名单**，`private` 表体（UV 名 / 世界向量黑名单）只报「经哪个公开判定」不抄内容 | — | ❌ 只读，不改行为（真机两轮画面无差，已复核） |
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

| 阶 | 内容 | 规模 |
|---|---|---|
| **D0** | 注释规约进 `07-CONSTRAINTS` §3.5 + 修 `VkDispPackScan` 重复 import + 把 QD-03/QD-04 的数字改成「由测试计算」 | — |
| **D1** | 反射开关：核实动机 → 类型化改造或元测试守卫 | — |

---

## 5A · 弱点五 —— 接入形态：该注入的地方在用手抄原版、反射读私有字段、进程级静态标志（2026-10-12 增补）

> **性质**：本节是 2026-10-12「重新调研：**已实现的功能里哪些用 mixin 写更好**」的方案书。
> 它与 QD-09~QD-14 那四类**不同源** —— 那四类是「代码怎么写」，本条是「代码挂在哪儿」。
> **裁决依据**：§7-5（2026-10-12 三条裁决：废 M1 类别白名单 / X24 作废 X23 保留 / M-04 排为下一轮目标）。
> 逐条注入点已登记在 `04-SPEC.md` §5.0 的 **M-04 / M-06…M-14**；本轮新登记的缺陷是
> `GAP-038`（不设雾）`GAP-039`（天空门控）`GAP-040`（空转守卫）`GAP-041`（采样器各向异性）
> `GAP-042`（反射判据）`GAP-043`（M-01 牵连建网格侧）；
> 同日第二轮（§7-6）另登记 **`GAP-044`**（现有 modifier 在 `apply` 里现读配置 ⇒ 首推即永久冻结）、
> **`GAP-045`**（缓存键无包代次 ⇒ 「M-01 → modifier」作废，R1 改判为 R1a / M-15）、
> **`GAP-046`**（M-04 的派生面是开放集合 ⇒ 必须通用派生 + 退路）。
> 🔖 **本节所有原版侧行号都对着 26.3 反编译源逐条核过**，不是引用旧文档。

### 5A.1 现状：本仓「挂在哪儿」的五类机制（全部 `file:line`）

| 机制 | 处数 | 代表位置 | 本轮判定 |
|---|---|---|---|
| **官方事件（正解）** | 6 类 8 处 | `MrtTerrainPass:185` / `SceneCaptureApi:80`（`FrameGraphSetupEvent`）、`MrtTerrainPass:347`（`AfterOpaqueBlocks`）、`GbufferPipelineSwaps:89`（`RegisterPipelineModifiers`）、两处 `RegisterRenderPipelines`、`VkDispVirtualPack:465`（`AddPackFindersEvent`） | ✅ **保留**，一行都不动 |
| **手抄原版逻辑** | 5 处 | `TerrainDerivedPlan:126-140`↔`RenderPipelines:379-405`、`MrtTerrainPass:889-908`↔`LevelRenderer:437-449`、`SceneCaptureApi:106-108`（clear 语义）、`SkyIntoGbuffer:133-134`（自抽 `SkyRenderState`）、`CloudsIntoGbuffer:192-199` | 🔴 每处都是一笔**升版必重核**的债，且已各欠一格 GAP（041 / 039） |
| **反射读 private** | 2 处 5 字段 | `MrtTerrainPass:946,957`、`CloudsIntoGbuffer:314-319` | 🔴 `GAP-042`：判据可以**静默消失** |
| **进程级静态标志当「我在哪个 pass」的证据** | 1 族 | `MrtTerrainPass:119`（`inMrtPass`）+ `:532-539`（`finally` 纪律）+ `:320-322`（**空转守卫**）+ `TerrainPipelineApi:530` | 🔴 `GAP-040`：防护实际为 0 |
| **图外重放 + 手工同步** | 1 族 | `GbufferTarget`（壳 + 每帧 `repoint`）、`ColortexPool:41-52`（双代轮转）、`DepthSnapshots`（三个手写取点）、`FullscreenPassHook:309-319`（AfterLevel 第二档）、`mrt.terrainAfterLevel` A/B 车道 | 🔴 这一族的**存在理由**是 §5.0.4 那条「走 A 不碰原版 pass」的取舍 —— 该取舍已于 2026-10-12 作废 |

> 🔖 **本表是 2026-10-12 早间那份快照，别按 `file:line` 逐行去跳** —— 阶梯 R0…R5 当天已经改掉大半：
> ② **手抄原版逻辑**：`SkyIntoGbuffer` 的自抽 `SkyRenderState` 与 `MrtTerrainPass` 那份采样器手抄已删
> （R3 M-07 / R4 M-11 + 官方天空盒钩子），`TerrainDerivedPlan` / `SceneCaptureApi` / `CloudsIntoGbuffer`
> 三处仍在；③ **反射读 private**：两处 5 字段全部换成 `@Accessor`（R3 ⇒ `GAP-042` 那一族消失）；
> ④ **静态标志当「我在哪个 pass」的证据**：`inMrtPass` + 两处 `finally` + 空转守卫 +
> `MRT_GUARD_FALLBACK` 已删，判据改成 `renderPass` 的**对象同一性**（R1a，M-15）；
> ⑤ **图外重放**：`GbufferTarget` 整类（R4）与 AfterLevel 第二档 + `mrt.terrainAfterLevel` 车道（R5）
> 已删 ⇒ 这一族只剩 `ColortexPool` 的双代轮转与 `DepthSnapshots` 的三个手写取点（都还在债单上）。

### 5A.2 病根（只有一条，别数成五条）

**「不碰原版 pass」被当成永久前提**，于是每个「必须在原版那个时刻/那个位置上做事」的需求都被推成了
**图外重放**：重放就得**抄原版的判定**（四道天空门控、采样器各向异性、clear 口径），
就得**自己有一套附件所有权**（双代轮转、壳对象 `repoint`），就得**替代「我在哪」这个信息**（静态标志、反射读条数）。
⇒ 账单越积越厚，而当初靠它省下的风险**一分钱也没退回来**（地形还多画了一遍，`MrtTerrainPass` 类头自陈「双绘代价未测」）。

### 5A.3 调研结论（本轮核实的公开面与注入面，按可用性排序）

| 结论 | 出处（本轮核实） | 用途 |
|---|---|---|
| `getCompiledPipelineNullable` **方法体首行**就 `PIPELINE_MODIFIERS.apply(pipeline)`；`push`/`pop`/`renderWithPipelineModifier` 公开；栈空直通零开销；结果按 `(modifier, pipeline)` 缓存但**要求返回值换 location** | `RenderSystem:106-107 / 502 / 507 / 513 / 519`、`PipelineModifierStack:47 / 58 / 59-62` | ⚠️ **通道确实覆盖地形 draw，但换不掉 M-01**（同日改判，理由见下一行与 `GAP-045`） |
| 🔴 **modifier 的结果缓存按 `(modifier key, 入参管线对象)` 建，且没有任何失效路径**；而 modifier key **只能在启动期注册**（`putIfAbsent`，`PipelineModifiers` 是包内 `static final` map） | `PipelineModifierStack:47-70`（`Reference2ReferenceOpenHashMap`，只 get/put）、nf 源 `ClientHooks:796 / 797-800 / 825-826`、`RegisterPipelineModifiersEvent:28-32`、`PipelineModifiers:16` | ⇒ **「M-01 → `PipelineModifier`」作废**：包代次既进不了缓存键、也进不了 key ⇒ 切包后继续返回**上一代**管线（把 `GAP-032` 那类脏状态**永久化**）。R1 的现行形态 = **R1a（新增 M-15，`@WrapOperation` 读 `renderPass` 实参）**；顺带查出既有云 modifier 在 `apply` 里现读配置 ⇒ 新登记 `GAP-044` |
| 地形两条取管线分支都经上面那个入口 | `ChunkSectionsToRender:121`（多重绘制）/ `:176`（单独绘制） | ⇒ modifier **覆盖地形 draw 成立**，不是推定 |
| `RenderPipeline.toBuilder()` / `withColorTargetState(index)` / `withColorTargetStates(start,end,supplier)` | `RenderPipeline:135 / 273 / 279` | ⇒ 派生管线**不必手抄** define 与混合 |
| `addMainPass` 是**两支汇合的必经单点**，形参同时带 `terrainFog` 与 `ChunkSectionsToRender`；官方事件的实参里没有这两个 | `LevelRenderer:272 / 274 / 277 / 396-401`、`FrameGraphSetupEvent` 字段表 | ⇒ M-05 两点收一点（R2）+ 顺手补雾（`GAP-038`） |
| 帧图**插入序不是依赖**；`requires()` / `disableCulling()` 是公开 API | `FrameGraphBuilder#resolvePassOrder`、`FramePass:16` | ⇒ 顺序该显式声明（R5），不该用日志反推 |
| `CustomSkyboxRenderer.renderSky` **就在 `"sky"` pass 体内**被调，且那一刻**没有 render pass 打开**（原版自己的 pass 在 `SkyRenderer:139` 内部才开），回 `true` 天然互斥 | `LevelRenderer:385`、`SkyRenderer:139` | ⇒ **包天空的正解是官方钩子，不是注入**；四道门控由原版判（`GAP-039`） |
| `SkyRenderer.render(GpuBufferSlice, SkyRenderState)` public，其两个视图取自第 134/135 行 | `SkyRenderer:132 / 134 / 135` | ⇒ `@Redirect` 两实参即可让原版天空落进 gbuffer（R4，删 `GbufferTarget`） |
| `setPipeline` 要求 **pass 附件数与管线 color target 数相等且逐条格式相等**，否则 `IllegalStateException` | `com/mojang/renderpearl/frontend/FrontendRenderPass:107-121` | ⇒ R7（M-04）的**硬约束**：主 pass 内每条管线都要派生 |
| 原版 UI 侧：`VideoSettingsScreen#addOptions` 是 protected，`OptionsList.addSmall(List)` / `addHeader` 公开；`ConfigurationScreen` 是 `final`、`ScreenEvent.Init.Post` 不进 `HeaderAndFooterLayout` | `VideoSettingsScreen:108`、`OptionsList:45 / 59`、nf 源 `ConfigurationScreen:112` | ⇒ 包选项进原版页**只有注入这一条路**（R6） |
| ⛔ **反过来，五处判定「不该注入」**：3D/数组纹理（`VulkanGpuTexture` 硬编 `imageType(1)`，是后端分叉不是时序）、回读等待（有公开 `createFence`）、`colortexN`/`depthtexN` 命名语义（包格式，无原版结构可注）、`glsl/**` 翻译器与 `McVersion`（**可单测是资产**，注进去就废）、单 encoder / pass 内不建资源（引擎不变量，注入只会把 UB 换成崩） | 各见 `04-SPEC` §5.0 候选行的备注 | ⇒ 这五处**留在原状**，别把调研结论读成「全都上 mixin」 |

### 5A.4 阶梯 R0…R7

🔴 **R 编号与 `04-SPEC` §5.0 各行里写的「候选（R…）」一一对应，是引用锚点；执行序不等于编号序**，
执行序写在下面。排序原则：先做**零渲染语义变化**的删码，再做**换通道**，最后做**改原版 pass 语义**。

| 阶 | 内容 | 对应注入点 / GAP | 改动面 | 判据 | 规模 | 动热路径？ |
|---|---|---|---|---|---|---|
| **R0** | 注释与常量口径同步（M1 旧「装配层」措辞、`MixinTargets` 里「永久禁止注入 `RenderSystem`」那句、`FrameApi`/`PipelineApi` 头部「`FrameGraphSetupEvent` 插 pass 调研否决」的过期记载） | — | 只改注释 | 注释与 `07` §1.4 现行口径逐条对得上 | — | ❌ |
| **R1** | ✅ **已落地（2026-10-12 第 4 步，代码级）**：两支 `@WrapOperation` 已建、`inMrtPass`+`finally`+空转守卫+`MRT_GUARD_FALLBACK` 全删、M-01 退化成只按层名查表。🔴 判据**错配被拒**：2026-10-13 起可由单测跑（`routeOf` 真值表 + 反漂移针），形状针降为辅助；「注入失败标记 = 0」（针 = `07` §1.4.1bis 六条合成）已真机签掉 —— ⚠️ 当时用的是 `Mixin apply failed` 那条假绿针，10-14 换针重读 10-13 全天 36 份日志 = 35 趟 0 命中 / 1 趟命中那次已修 FATAL；仍**待真机**：`h08` 同量级 A/B。🔴 **改判（同日两次，以本次为准）：不做「M-01 → `PipelineModifier`」，改做 R1a = 新增 M-15** —— `@WrapOperation` 包住 `ChunkSectionsToRender:176 / :121` 的 `getCompiledPipeline(...)`，handler 读外层 `renderPass` 实参；**M-01 保留**但退化成「只按层名查表，不再判 `active()`」 | **M-15**、M-01（保留）、`GAP-040`、`GAP-043`、`GAP-045`（改判依据） | `TerrainPipelineApi`（去掉 `active()` 依赖）+ `MrtTerrainPass`（删 `inMrtPass:119` / `finally:532-539` / 空转守卫 `:320-322`）+ `TerrainPipelineApi:530`（删 `MRT_GUARD_FALLBACK`） | ① MRT 变体只在 `renderPass` 身份命中我方 pass 时才交出（故意错配一次必须**拒绝**）；② 单变量 A/B 对 `h08` 基线 **48.22 / 39.03%**；③ 注入失败标记 = 0（针见 `07` §1.4.1bis）；④ M-01 的代码里不再出现 `active()` | **比原设想省**：不需要幂等收敛，也不需要动注册时机 | 🔴 每帧 2–6 次取管线 |
| **R2** | ✅ **已落地（2026-10-12 第 3 步，代码级）**：M-06 的 HEAD 点已建、M-05 的类/两个注入点/旧键/`volatile Object`+instanceof 全部删除、pass 体第一行已补 `setShaderFog`。🔴 判据（进水那一帧雾端读数跟着变）**待真机**。**M-06 一点替两点** + 补 `setShaderFog` | M-06（替 M-05）、`GAP-038` | 新增 1 注入点；删 `TerrainDrawCapture` 的 `volatile Object` + `instanceof` | 原版那次 draw 与注入点里 `terrainFog` 的 buffer hash 一致；进水那一帧雾端读数**跟着变** | — | 🟡 每帧一次赋值 |
| **R3** | ✅ **已落地（2026-10-12 第 2 步，代码级）**：`src/main/java` 里 `getDeclaredField/setAccessible` 已零命中、自建采样器 42 行已删、云那次压栈/还原已删、`apply` 里的现读配置已移到调用点。🔴 **判据仍未取证**：「各向异性两档锐度随用户设置变」与「同机位同帧读数与今天相同」都要真机 A/B 才能签 ⇒ 本条状态 = 代码完成、验证未结。**删反射族**：M-07 复用原版采样器 / M-08 / M-09 访问器 / M-10 云矩阵 `@Redirect` | M-07…M-10、`GAP-041`、`GAP-042` | 删两处反射块 + 42 行自建采样器 + 云的栈压还原 | 反射的 WARN 分支**物理删除**；各向异性两档锐度随用户设置变；云与今天同帧读数相同 | — | ❌（判据侧）/ 🟡（采样器） |
| **R4** | **天空**：包天空搬进官方 `CustomSkyboxRenderer` 钩子 + M-11 视图 `@Redirect`（M-12 仅兜底） | M-11、M-12、`GAP-037`、`GAP-039` | 删 `GbufferTarget` **整类** + `SkyIntoGbuffer` 的第二个 `SkyRenderer` 实例 / 自抽 state / 手工互斥 | 岩浆里**没有**天空；天空深度**只被翻一次**；一百一十轮的星点判据**不回退** | — | 🟡 **代码已落地（2026-10-12 第 5 步）**：M-11 两条 `@Redirect`（键 `mixin.skyViews`）+ 包天空进官方 `CustomSkyboxRenderer`（`SkyIntoGbuffer.installHook()` 在装配期写字段，回 true 即互斥）；`GbufferTarget` 整类已删、第二个 `SkyRenderer` 与自抽 state/私有深度都删。**未签**：三条判据 + 「注入失败标记 = 0」（针见 `07` §1.4.1bis） + 自报行 `钩子被调#` 是否非零 |
| **R5** | ✅ **已收口（2026-10-12）**：`@WrapOperation` 两个 addPass 调用点接住原版 sky/main 句柄、`PassOrder` 双向补出 requires 边、ORDER-MARK 两条反推日志已删、`mrt.terrainAfterLevel` 那条 A/B 车道连同 `drawAfterLevel()` / `paintGbufferAndTerrain()` 一起删除 | M-13 | 删 `MrtTerrainPass:205-215` 的猜测式装配 + ORDER-MARK 日志 + `mrt.terrainAfterLevel` A/B 车道 | `h48g` 那个「天空排在地形之后」的 0.0611 读数**不再可能复现** | — | ✅ 代码收口，⚠️ 判据 `c0@afterSky` 要真机 |
| **R6** | ✅ **M-14 包选项进原版视频设置页（2026-10-12，两个提交）**：`@Inject VideoSettingsScreen#addOptions` TAIL + `VanillaPackOptionsPage` 只走原版 `OptionsList` 装配方法 | M-14 | 删 `PackPickerScreen` 整类 + `PackOptionsScreen`/`PackOptionsDrive`/`ScreenDriveCommand` + `packOptionsScreen` 驱动键 + `VkDispClient` 的 `IConfigScreenFactory` | 原版页内可滚动、可键盘操作、切包后选项仍在 | — | ✅ 代码收口，⚠️ 三条画面判据 + 「注入失败标记 = 0」（针见 `07` §1.4.1bis） 要真机；🔴 无头驱动臂退役（替身见 08-TESTING） |
| **R7** | 🎯 **M-04 整主 pass 多附件**（**下一轮目标**，用户 2026-10-12 裁决） | M-04、`GAP-003` 的 M-04 未解除项 | `@WrapOperation` `LevelRenderer:456-463` + 主 pass 内每条管线派生 | 地形**只画一遍**（用 R3 的访问器判据，不再用反射）；`FrontendRenderPass:107-121` 零抛；实体/云/天气/描边**不回退** | ✅ **待核三项已结**：代码面只有三处（1 个注入点 + 1 处通用派生公式 + 1 处退路计数），**成本全在回归面** —— 主 pass 同帧路径含实体/方块实体/物品/文本/粒子/云/天气/边界/描边/OIT 交替，且 `N ≤ 8` 无余量（§5.0.6 ⑤ 第 2、3 条还要求「补槽同尺寸」与「深度必须就是 `D32_FLOAT`」两条装配期前提）⇒ 需一轮完整真机对照。✅ **⑤-a 已落地（2026-10-12 同日下一轮）**：注入点 = `MainPassAttachmentMixin`（包 `FrontendCommandEncoder` 6 参重载**内部**第 95 行那次调用，不是我原先写的外层 `createRenderPass(RenderPassDescriptor)`），判定 = `MainPassTakeover.matchesMainPass` 纯函数 + `slotsUsable` 尺寸闸门，开关 `mixin.ownTerrainPass` 默认**关**（开着必崩 = 本步的证据）。⛔ ⑤-b（通用派生公式）没接 ⇒ R7 整体仍是未开工。✅ **⑤-a 的判据已于同日真机签掉**：日志「主 pass 的颜色附件已换成我方 colortex 阵列：8 个（label='Main'）」+ 一秒后 `FrontendRenderPass#setPipeline:113` 抛「Render pass color attachment count must match pipeline color target state count.」（栈顶 `ChunkSectionsToRender$DrawIndirect.render:120` ← `executeSolid:532`）—— 与预测逐字一致；签它需要 ≥2 槽的包档（BSL `ADVANCED_MATERIALS` ⇒ `slots=8`），单槽档下按设计**拒绝接管并计数**。🔴 同时削掉一条判据腿：主 pass 的颜色视图与 `gameRenderer.mainRenderTarget()` **不是同一对象**（`GAP-049`，⑤-b 的前置口径）。✅ **⑤-b 已落地 + 真机已签（同日再下一轮）**：公式 = `bridge/MainPassPipelineDerivation`（target0 逐字原样 + 补槽 `RGBA8_UNORM`/`WRITE_NONE`/无混合 + 三条**纯整数**退路 ⇒ 交回原版并计数），挂点从 `PipelineModifier` **改成** M-15 那次 `getCompiledPipeline`（modifier 的 key 只能在启动期注册，而槽数 N 是运行期的数 —— 与 `GAP-045` 同一个根因），`PassAwarePipelines.select` 因此收成三支（我方 MRT pass / 被接管的主 pass / 都不是 ⇒ 原样返回）；证据 = `首条补槽管线：SOLID（1 条声明 → 8 槽）` + 那个 `setPipeline` 抛**按预测挪了位置**（`PreparedRenderType.draw:43` ← `executeClassicTransparency:721` = 经典透明那笔）⇒ **R7 现在停在 ⑤-c**，逐族放开的顺序由「真机在哪条调用点响」给出；此步起的已知代价 = **地形双绘**（⑤-d 才关），`GAP-049` 留的那件活（池尺寸锚在另一张主目标）排在 ⑤-d 之前。✅ **⑤-c 第一族已落地 + 真机已签（同日再下一轮）**：注入点 = `mixin/PreparedRenderTypePipelineMixin`（包 `PreparedRenderType#draw:43` 那次 `getCompiledPipeline`）—— 🔖 这一处是**全部 `RenderType` 的公共通路**（实体 / 粒子 / 方块层 / 水 / 玻璃 / 文本…），所以「逐族放开」的第一族一处就顶十几族；判据形状沿用 a/b 那条「崩点必须挪位置」：日志 `[M-04/⑤-c] PreparedRenderType 取管线 hits=1` 之后，那个 `setPipeline` 抛从 `executeClassicTransparency:721` **挪进了 `executeSolid`**（`QuadParticleFeatureRenderer.drawLayers:89` ← `FeatureRenderDispatcher$PreparedGroup.execute:394` ← `LevelRenderer.executeSolid:538`）⇒ 前者一族过了，下一族按真机指的位置做。⚠️ 代价与连带修复：这一处**每次 draw** 都会走 ⇒ 补槽跑在原版编译缓存之前会把「每条管线一次」变成「每次 draw 建一条新管线」，已加按对象身份的记忆层并登记 `GAP-051`；🔖 同时把取管线点**逐处数清 = 34 处**（原「30 处未复核」作废），主 pass 内还剩 8 处 + OIT 4 处，清单在 `04-SPEC` §5.0.7 末。✅ **⑤-c 第二族已落地 + 真机已签（同日再下一轮）**：`QuadParticleFeatureRendererPipelineMixin`（包 `drawLayers:89`，就是第一族签掉后真机指名的那处）⇒ 机制可复制，形状逐字相同；🔴 新撞出一条注入形状规则（目标方法是 `static` ⇒ handler 必须 `static`，否则**类加载期**失败且**与开关无关** = 默认档也起不来，`GAP-047` ④）；观测面改成**分站点计数** `onMainPassSiteHit(site)`（一趟真机同时给出每族各自活没活：`{PreparedRenderType#draw=215070, QuadParticle#drawLayers=8130}`）；🏁 **第一次出现「主 pass 被换成 8 附件之后不崩的一帧」**（`主 pass 接管=28288` 帧、0 ERROR、0 apply 失败、截图正常）—— ⚠️ 但这一帧没碰到云/天气/边界/立方图（天空无云就是证据），所以「没崩」只等于「已放开的族过了」，⑤-c 还剩 6 处 + OIT 4 处。✅ **⑤-c 第三~五族已落地（同日再下一轮，2026-10-13 记录）**：云 / 天气 / 边界三处各一个注入类（云与天气各自有**收敛点**：私有 `render(…, RenderPass, RenderPipeline)` 同时覆盖经典与 OIT 两条路径；边界没有 ⇒ 只注经典那条）。真机证据分三等写清：**天气已过**（`/weather rain` ⇒ `WeatherEffectRenderer#render=9637`、`派生=10 交回原版=0`、0 崩）；**云 / 边界=已放开未取证**（`renderClouds` 关着、镜头离边界远 ⇒ 那两句没执行，只有 apply 成功 + 不崩这一半）；🔖 同时把上一轮两处误分类改掉：`CubeMap:69` 与 `LevelRenderer:518` **自己开 pass**，不在主 pass 内 ⇒ **主 pass 内的非 OIT 取管线点已全部接上**，剩下的是 OIT 那 6 处（`CloudRenderer:215`、`WorldBorderRenderer:170`、`LevelRenderer:670/690/694/706`）。⚠️ 一条自己造的启动期崩记进 `GAP-047` ⑤：描述符里 `Vec3` 的包名写错（`org.joml` 应为 `net.minecraft.world.phys`）⇒ `require = 1` 让它**启动期点名硬失败**而不是静默不生效，纪律第一次以正面样本入台账 | 🔴 整帧 |

**R7 的第一步不是写注入**：先交**牵连面清单**（`executeSolid` / `executeClassicTransparency` / `executeOit` /
`executeOutline` / `executeSeeThrough` / `executeAlwaysOnTop` 六支各用哪些管线、要派生成几条 N 附件版本），
✅ **清单已交付（同日第二轮，见 §7-6 / `04-SPEC` §5.0.6）** ⇒ 但 R7 仍**不许开工**，直到 §5.0.6 ⑤ 那三项待核结掉
（尤其待核 1：非 OIT 的 `water_mask` 落在哪个 pass —— 它决定「一条管线要不要出两个变体」，从而决定 R7 的真实成本）。
🔴 在此之前不许引用本节任何数字做判断。（工时口径整体废止 ⇒ 见 `07-CONSTRAINTS.md` X56）

**执行序**：`R0 → R3 → R2 → R1 → R4 → R5 → R6 →（结三项待核）→ R7`（R3 最先是因为它**零渲染语义变化**、纯删码；R1 排在 R2 之后是因为 R2 先把「pass 身份从哪来」定了，R1 才好归因）。

### 5A.5 验收纪律（本节特有，别漏）

1. 🔴 **每阶一个注入点、一个可关闭键**（`X29`），开工当天把签名补进 `07` §1.4.1 第 ① 行 —— 类别白名单废除后，那张表是**升版唯一入口**。
2. 🔴 **R1 与 R2 不得同轮**：R2 改「pass 身份从哪来」，R1 改「管线从哪来」，一起做就无法归因（`X52`）。
3. ⚠️ **R7 是唯一一条「关掉也回不去」的**：它改原版 pass 的附件语义 ⇒ 取证档必须留一条「M-04 全关 ⇒ 逐字节等于今天」的对照臂。
4. ⚠️ 本节**不主张任何性能结论**：双绘、每帧 `repoint` 这些代价在 llvmpipe 上不可量化（`00` 铁律 ①）⇒ 排序只按「兼容 / 稳定」。

---

## 6. 跨弱点排序：为什么是这个顺序

```
A0 (止血/可见性)  ──┐                     ← 零风险，立刻消除 X11 违例
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

| 项 | 兼容收益 | 稳定收益 | 性能影响 | 规模 | 需真机取证 |
|---|---|---|---|---|---|
| A0 | ★（防错展开） | ★★★（消静默） | 0 | — | ❌ |
| B0/B1 | ★ | ★★★（消 QD-08 族） | 0（取键冷路径） | — | ❌ |
| C1 | ★★★（换包不漂） | ★★ | 微（注册期） | — | ❌ |
| A1/A3/A4 | ★★（少打补丁） | ★★★（可定位） | 冷路径，须报 B3/B4 | — | ❌ |
| C2a | ★（为 C2b 备好正确矩阵） | ★★（消灭「常量当真相」） | 0（不碰 GPU） | — | ❌ |
| C2b | ★★★（阴影第一次真实） | ★★ | 每帧多一遍世界绘制 ⇒ **必测 B1/B2** | 未估 | 🔴 单独一轮 |
| A2-shaderc 臂 | ★★ | ★★★（差分 oracle ⇒ 盲区变可见） | 冷路径，可只在测试/诊断里跑 | 探针先行（原版已带该原生库 ⇒ 无分发成本） | ❌（oracle 只进单测/离线工具） |

🔴 **2026-10-12 增补：§5A 那一族不进上面这条链。**
它改的是「代码挂在哪儿」，与 A/B/C 三条链（改「代码怎么写」）**并列**，各自独立可排期。
按同一张打分表量，§5A 的三格是：

| 项 | 兼容收益 | 稳定收益 | 性能影响 | 规模 | 需真机取证 |
|---|---|---|---|---|---|
| **R3**（删反射族 M-07…M-10） | ★（判据不会静默消失） | ★★★（升版硬失败替静默降级） | 0 | — | ❌ |
| **R2**（M-06 一点替两点 + 补雾） | ★（雾口径与原版同源） | ★★★（消「残值当本帧值」那类静默错） | 0（每帧一次赋值） | — | 🟡 一帧判据即可 |
| **R1**（R1a：新增 M-15，用 `renderPass` 实参定 pass 身份） | ★★（少一层「档不一致」风险） | ★★★（`GAP-040` 的假守卫被真判据替掉） | 🔴 热路径，须报 B1/B3 | — | 🔴 单变量 A/B 一轮 |
| ~~R1b（M-01 → `PipelineModifier`）~~ | — | — | — | — | 🔴 **已作废**（`GAP-045`：缓存键无包代次、key 不能运行期增删） |
| **R7**（M-04 整主 pass 多附件） | ★★★（gbuffer 第一次**就是**主目标 ⇒ 双绘与图外同步一族消失） | ★★（少一套手工同步，但改的是原版 pass 语义） | 少一遍地形绘制 —— ⚠️ 本机 llvmpipe **不可量化**（`00` 铁律 ①） | 完整回归一轮（三项待核已结，见 §5A.4 的 R7 行） | 🔴 牵连整帧，需完整回归 |

---

## 7. 裁决记录（7-1 … 7-6）与待判点

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
- 建议排序：**C2a（数学，零风险）→ C2b（装配，大项，单独一轮）→ 比较采样器（GAP-001/015，与 C2b 同轮必修）→ C4 级联（可选，`platform/` + T12 登记）**。
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

### 7-5 ✅ 已裁决（2026-10-12）：**mixin 的类别限制废除**；X24 作废；**M-04 排为下一轮目标**

调研载体：四域并行调研（地形链路 / 帧图与回读 / 天空与云 / 包装载与 UI），结论已落
`04-SPEC.md` §5.0 的 **M-04 / M-06…M-14** 与本文件 **§5A**，新登记缺陷 **`GAP-038…GAP-043`**。

用户判（原话要点）：**「不用考虑红线」** ⇒ 评估只看工程收益（时序精度 / 能删掉多少复制自原版的逻辑 /
升版改动面），**不再以「是不是官方 API」一票否决**。随之落定四条：

| # | 裁决 | 落在哪 | 🔖 未放开的部分 |
|---|---|---|---|
| ① | **M1 类别白名单废除**：注入范围放开到任意原版类 | `07` 的 `M1` 行 + §1.4 背景表末行 + §1.4 现行规则表 | **登记制 / 可关闭制 / 逐个开启三条保留**（X28 / X29 继续有效）。旧白名单实际挡住的就是三处：M-14（UI 屏幕）、M-08/M-09（private 字段访问器）、M-10（`CloudRenderer` 矩阵实参） |
| ② | **X24 作废**（原禁注入 `RenderSystem` / `GlStateManager` / `GL11`/`GL14`/`GL15`） | `07` X24 行改作废 + §1.4.2 措辞更正 | ⚠️ `GL*` 那半条的**理由**降级为工程事实保留：Vulkan 路上没有那些调用点，注入即绑死后端（X7）。🔖 **实际影响 = 0**：本轮结论**一个都没注 `RenderSystem`** —— 它在第 106-107 行给了公开通道 |
| ③ | **X23 保留**（禁注入 Sodium / caffeinemc / 第三方区块渲染器） | `07` X23 行加注 | 理由改判清楚：它属 **L11 零集成支柱**，与「注入哪个**原版**类」不同源，因此**不随 ① 一起放开** |
| ④ | **M-04（整主 pass 多附件）直接排为下一轮目标** | `04-SPEC` §5.0 M-04 行 + §5.0.4「走 A」取舍结论的**作废注记** + 本文件 R7 | ⚠️ 前置是 **R7 牵连面清单**（依据 `FrontendRenderPass:107-121` 的「附件数与逐条格式恒等」）；清单没交出来就不许开工。同轮确认：**方案 A 退役**、地形双绘到此为止 |

**同轮顺带结掉的两处文档债**（旧口径与现状冲突，不是新决策）：

- `docs/02-OVERVIEW.md:101` 写「资源包覆盖机制已验证成立」，与同日 `evidence/p4x4a` 的 `verdict=REJECTED`
  直接冲突 ⇒ **以 REJECTED 为准**，该句已在 M-01 行的更正里作废；
- `04-SPEC` M-01 行仍写「零 mixin 无法达成（`02` §5.1）」，而 `07:84` 早已撤回该推论 ⇒ 本轮改判为
  **「候选下线（R1 由 `PipelineModifier` 替）」**。

⏳ ~~**本节新增一条待判（不阻塞 R0 / R2 / R3）**：R1 的**幂等收敛怎么做**~~
🔴 **已于同日结案 ⇒ 见 §7-6**（结论：原设想被证伪，R1 改形态；M-04 清单已交付）。

### 7-6 ✅ 已判（2026-10-12 同日第二轮）：**R1 的幂等收敛方案** 与 **M-04 牵连面清单**

用户要求「确认 R1 幂等收敛方案与 M-04 牵连面清单」。两条都查到底了，其中 R1 是**证伪**（我自己的上一轮结论被自己推翻），M-04 是**范围缩小 + 风险改型**。

**① R1 幂等收敛：原设想作废，改做 R1a**

| 项 | 结论 |
|---|---|
| 原设想 | 「把配置读挪到注册期 ⇒ modifier 就幂等 ⇒ M-01 下线」 |
| 证伪依据 | 缓存是 `Map<ResourceKey<PipelineModifier>, Map<RenderPipeline, RenderPipeline>>`，`Reference2Reference`，**只有 get/put、没有任何失效入口**（`PipelineModifierStack:47-70`）；而 modifier key **只能在启动期注册**（`ClientHooks:796 / 797-800 / 825-826` 那个一次性窗口 + `RegisterPipelineModifiersEvent:28-32` 的 `putIfAbsent`）⇒ **包代次既进不了缓存的第二维，也进不了 key** |
| 为什么致命 | 我方 MRT 地形管线的**身份**随包变（片元 = 包的 `gbuffers_terrain`，附件数 = 冻结契约 `MrtPlan.freezePackPrograms:169-197`）⇒ 切包后 modifier 继续返回**上一代**那条，等于把 `GAP-032`（切包后绑定组不同代 ⇒ `Missing uniform` 崩帧）的脏状态**由缓存永久记住**，比现状更难退 |
| 顺带查出的既有债 | `GbufferPipelineSwaps:112` 就在 `apply` 里现读 `mrt.cloudsNoDepthWrite` ⇒ 首推那刻的值被永久冻结，今天靠**调用点闸门**（`CloudsIntoGbuffer:281/289`）掩盖 ⇒ 新登记 `GAP-044` |
| 现行形态 **R1a** | 新增 **M-15**：`@WrapOperation` 包住 `ChunkSectionsToRender:176 / :121` 的 `getCompiledPipeline(...)`，handler 读外层 `renderPass` 实参 ⇒ pass 身份由**数据**给出。删的是 `inMrtPass` + `finally` 纪律 + 空转守卫（`GAP-040`）+ `MRT_GUARD_FALLBACK` —— **这三件原设想想删的东西照样删得掉，且不依赖 modifier**；M-01 保留但退化成「只按层名查表」。 |
| 登记的落点 | `04-SPEC` §5.0 M-01 行（改判）、新增 M-15 行、`GAP-045`（本条结论）、`GAP-044`（既有云 modifier）；本文 §5A.3 表新增一行、§5A.4 的 R1 行改写、§6 打分表把 R1b 划掉 |

**② M-04 牵连面清单：已交付在 `04-SPEC` §5.0.6**，四个要点：

1. 🔖 **前提纠偏**：`"main"` 是 FramePass，其 `executes` 体内开着 **5 个互相独立的 GPU render pass**
   （`:455-463` 主 / `executeOit :583-673` / `executeOutline :750-761` / `executeSeeThrough :487-496` / `executeAlwaysOnTop :498-522`）。
   附件校验是**逐 GPU pass** 判的 ⇒ **M-04 只要改 `:455-463` 那一个**，范围比旧记载小；
   第 405-433 行 `pass.readsAndWrites` 那一堆只是帧图声明，各自的写入者在别的 pass 里 ⇒ 不冲突。
2. **必派生量级 ≈ 58–62 条**（地形 4 + 半透明 2 + 线框 2、`RenderTypes` 非 OIT ≈52、粒子 2、云 2、天气 1、世界边界 1；
   分母是 `RenderPipelines` 全表 **195** 条）；`prepareTranslucents`（`:451`，实现 `:544-581`）**一条管线都不取** ⇒ 代价 0。
3. 🔴 **真实风险不是这个数**：六处阶段事件（`:536/540/723/731/735/745`）把主 `renderPass` **交给在场任意 mod**
   ⇒ 派生面是**开放集合**，必须有一条「未知管线也补到 N 槽」的**通用派生公式**
   （`toBuilder()` + target0 原样 + 补槽 `RGBA8_UNORM` / `WRITE_NONE` / 无混合），
   并且**格式不匹配时必须有「交回原版路径」的退路 + 计数**（已登记 `GAP-046`）。
4. ⛔ **三条待核，未结之前不许给 R7 报成本**：① 非 OIT 的 `water_mask` 到底在哪个 pass（`RenderTypes:716`）——
   若同一条管线跨两个 pass，就必须复用 M-15 的 pass 身份判据；② 默认 `renderArea` 尺寸取自哪个附件；
   ③ 后端深度格式是否被硬编码（关系到 `GAP-022` / `GAP-037` 的深度口径单点化）。

**执行序更新**：`R0 → R3 → R2 → **R1a** → R4 → R5 → R6 →（待核 ①②③）→ R7`。
`GAP-044`（`apply` 内禁现读配置）排在 **R3 同轮**做，因为它只有 3 行、且是「以后复用 modifier 通道」的地基。

---

### 7-7 ✅ 已核（2026-10-13）：**第 2~6 步「必须物理消失」的那一栏，逐条 grep 核过账**

> 为什么专门记一格：这五步的判据里有一半是「**某段代码不在了**」，而「不在了」最容易被后来的会话拿注释里的旧名词当成「还在」。
> 本轮把每一条**逐字 grep 过一遍**，结论：**代码侧全部已删**，剩下的只有画面判据没签 —— 两者不该混在一张账上。

| 步 | 必须消失的东西 | grep 结果 | 证据形状 |
|---|---|---|---|
| 2 | `getDeclaredField` / `setAccessible` / `getDeclaredMethod`（`bridge/` 全域） | ✅ **代码里 0 处** | 只剩三处**注释**在讲「反射已否决」：`IVkdispChunkLayerSamplerAccess:19`、`IVkdispCloudMeshProbe:15`、`IVkdispTerrainDrawProbe:21,24`（后一条还记下「那次 `setAccessible` 本来就是多余的」） |
| 2 | 云那次 `stack.set` + `saved` + `finally` | ✅ **0 处** | `CloudsIntoGbuffer` 里两个符号都搜不到 ⇒ M-10 的 `@Redirect` 是唯一路径 |
| 3 | `prepareChunkRenders*` 的 RETURN 注入、`TerrainDrawCapture` 的 `volatile Object` + `instanceof` | ✅ **0 处** | 命中的两行是 `TerrainDrawCapture:24,25` 的 javadoc（「字段从 `volatile Object` 变成**类型化**」——它在解释为什么没有了） |
| 4 | `inMrtPass` 静态布尔 + `finally` 纪律、空转守卫、`MRT_GUARD_FALLBACK` | ✅ **0 处代码** | 四处命中全在注释（`MainPassTakeover:59`、`MrtTerrainPass:118,320`、`VkDispConfig:167`），且 `MrtTerrainPass:320` 那条正是 `GAP-040`「拿标志位证明标志位」的墓碑 |
| 5 | `GbufferTarget` **整类** | ✅ **类文件不存在** | 五个文件引用这个名字，全是注释/「备选（都否决）」段（`CloudsIntoGbuffer:21`、`SkyIntoGbuffer:24,30`、`SkyRendererViewRedirectMixin:17,19`） |
| 6 | `ORDER-MARK` 日志与 `mrt.terrainAfterLevel` 车道 | ✅ **日志与键都没了** | `MrtTerrainPass:565` 写着「这里原来是 ORDER-MARK…」；`VkDispConfig` 里**没有** `terrainAfterLevel` 的 `define`（只有 `:240` 一句「随车道退役一起失效」） |
| 6 | 自研 `PackPickerScreen` + 自研分页 | ✅ **类文件不存在** | 唯一引用是 `VkDispClient:38` 的注释（「旧做法是【临时 · 测试用】…」） |

**⇒ 这一栏之后不必再核**；要重开只能拿**新的 grep 证据**来，不许拿「注释里提到它」来。
**⚠️ 别读成「第 2~6 步已完成」**：这五步的**画面级判据**（各向异性两档锐度、进水那一帧雾端读数、岩浆里没有天空、天空深度只翻一次、星点不回退、A/B 对 h08 基线、原版视频设置页那一屏）**仍未逐条签掉** —— 已签的只有第 4 步那条 `Mixin apply failed = 0`（2026-10-13，全量 `latest.log` 六种注入失败标记全为 0）。🔴 **2026-10-14 两处更正**：① 那句话里引用的「六种标记」当时**在仓里没有定义** —— 现在定义在 `07-CONSTRAINTS.md` §1.4.1bis（六条针逐条给出发言的 jar 格式串与本仓真样本）；② 针本身 `Mixin apply failed` 匹配不到任何真实措辞（sponge-mixin 打的是 `Mixin apply for mod <id> failed …`），在 10-13 全天 36 份日志里 **0 命中**，含那趟启动期 FATAL ⇒ 典型的假绿针。⇒ 换成 §1.4.1bis 的合成针**重读同一批日志**：35 趟 0 命中、1 趟命中 1 次（就是 17:58 那次已修的 FATAL 本身，`1c4fe08`）—— 这条判据现在才是有定义的读数。

---

### 7-9 ✅ 已裁决（2026-10-17）：**换证 LGPL-3.0-or-later** + **光影包特性层直接移植 Iris / Vitrail**

用户原话：「**更改协议**……**现在直接把 Vitrail 和 Iris 对光影包特性的处理移植过来，不要自己实现了**」。
追问定下两点：① 项目许可证 = **LGPL-3.0-or-later**（不是 GPL，也不是 AGPL）；
② Iris 的 GLSL 转换层链 **AGPL-3.0 的 glsl-transformer** ⇒ **只搬规则、不引库**，项目不升 AGPL。

一手核过的许可（本机检出 + `cmp`，不是回忆）：

| 上游 | 许可证 | 判定 | 本机钉版 |
|---|---|---|---|
| Iris | LGPL-3.0（`LICENSE` + README §License） | ✅ 可搬 | `bff1e69…`，分支 `26.1`，工作树干净 |
| Vitrail | LGPL-3.0 + 84 KB `NOTICE` | ✅ 可搬（**主源**） | `4a45f44…`，分支 `fix/review-of-26-3-support`，🔴 本机工作树 12 处改动 + `CHANGELOG.md` 冲突未解 ⇒ 只按 commit 记 |
| glsl-transformer | AGPL-3.0 | ❌ 不引库，只取规则 | —— |
| Sodium | PolyForm Shield 1.0.0 | ❌ 不可搬；Iris 的 `compat/sodium/**` 面外 | —— |
| Sulkan / OptiFine / Beryl | GPL-3.0 / ARR / ARR | ❌ 照旧 | —— |

**为什么换证这件事必须先做**：MIT 与 LGPL 不同族 ⇒ 搬一行就违约一行。条款（`07` §〇 P1–P4、L13；台账与判定表 = `NOTICE.md`）
先落地，代码才允许动。

**下一轮的移植面**（按 Vitrail `NOTICE` 已点名「转录自 Iris」的文件排，全为 LGPL 干净件）：
`pack/model/{ProgramFallbacks,TargetFormat,BufferObject,ImageInformation}`、
`pack/target/{ConstDirectives,DrawBuffers,TargetDirectives}`、`pack/source/{IncludeExpander,PropertiesFile}`。
🔴 这些文件**同时欠两条账**（Vitrail + Iris 都要记，Vitrail 自己的 `NOTICE` 就是这么写的）。
面外：`compat/sodium/**`（PolyForm）、`pipeline/transform/**`（AGPL 依赖）、Sulkan（GPL）、OptiFine 本体（ARR）。

### 移植进度（逐轮更新，判据口径 = 「读入模型」≠「已生效」）

| 轮 | 搬了什么 | 生效到哪一步 |
|---|---|---|
| 一百八十六 | 只换证与改条款 | 零行并入，台账为空 |
| 一百八十七 | `pack/model` 十个叶子文件（`BlendMode`·`ProgramFallbacks`·`ProgramNames`·`TextureStage`·`PackTexture`·`PixelFormat`·`PixelType`·`TargetFormat`·`BufferObject`·`ImageInformation`）+ `ShaderProperties` 两族分派 + `ShaderPackService` 的 `[pack特性]` 自报 | 🔴 **只到「按 Iris 文法与上限读入模型」**：SSBO 与自定义图像的设备侧（分配/绑定/barrier 时机）一行未接 ⇒ 特性**尚未生效**；判据 16 条（`PortedPackFeatureModelTest`） |

#### 🔴 闸门事实（2026-10-17 源码级核实）：SSBO 与自定义图像**官方 API 表达不了**

`bufferObject.N`（SSBO）与 `image.NAME`（storage image）这两族**不可能**只靠官方渲染 API 生效。逐字证据
（取自本机 `mergeWithSources_*_output.jar`，非回忆）：

| 事实 | 出处 |
|---|---|
| 缓冲的 usage 位止于 `USAGE_INDIRECT_PARAMETERS = 512`，**没有任何 STORAGE 位** | `com/mojang/renderpearl/api/buffers/GpuBuffer.java:13-22` |
| 管线能绑的描述符种类只有三种：`COMBINED_IMAGE_SAMPLER` / `UNIFORM_BUFFER` / `TEXEL_BUFFER` | `com/mojang/renderpearl/api/pipeline/UniformType.java:8-11` |
| 后端建 `descriptorType` 时是对上面这个枚举做 switch ⇒ 没有第四个分支可走 | `com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline.java:92-98` |

上游的绕法已经看清（Vitrail `render/storage/StorageBuffers.java:3-27`）：**绕过游戏的门面**——
`lwjgl-vma` + `VK12.vkCreateBuffer` 自己建缓冲，再用三个 accessor mixin
（`mixin/access/{CommandEncoder,GpuDevice,VulkanCommandEncoder}Accessor`）在 push descriptor 里
**顶替 `VkBuffer` 句柄与 range**。这条路能走通，但它同时踩我方的两条现行约束：
**T2「所有新增 GPU 操作走 `com.mojang.renderpearl.*`」** 与 **X7「不许绑死后端」**
（`VK12`/`Vma` 是 Vulkan 专属），且属于 `17-NATIVE.md` §5.2 的 **G 系列闸门**（需裁决 + 取证才建）。

⇒ 这是一个**只能由用户裁决**的分岔，不是工程上可以自行绕过的：

| 选项 | 代价 |
|---|---|
| **A. 开原生闸门**：按 Vitrail 的路子接 VMA + 裸 `vkCreateBuffer` + 三个 accessor mixin（登记制开启） | 绑死 Vulkan 后端（X7 破例）、G 系列要真机取证、原生构件进分发 jar；换来 SSBO/自定义图像真正生效 |
| **B. 明确登记为不承诺**：这两族保持「已解析、拒接并点名」，画面按包的兜底走 | 依赖 SSBO/图像的现代包（BSL/Complementary 高级材质线、大量 compute 包）永远不兼容，支柱①「完整兼容」在这两族上有确定缺口 |
| **C. 用 `UNIFORM_BUFFER` 伪接** | 写不回（shader 对 SSBO 的写入是这族特性的核心），只会把「不支持」变成「看起来支持而语义错」⇒ **不建议** |

🔴 **下一批的先后次序（按「离生效近」排，不按好写排）**：① `pack/target/{ConstDirectives,DrawBuffers,PackDirectives,TargetDirectives}` —— `const` / `DRAWBUFFERS` / `program.*` / `shadow.*` / `albedo.*` 这些**已经在喂我方管线**的指令，把我方自研表换成上游表并与 `pipeline/model` 定一个真源；**并把 `alphaTest` 的 `discard(...)` 接进片元末尾**（注入点 = `pack/PackTerrainSource` / `pack/PackCompositeSource` 造源码处 —— 移植件已解析、设置键已存但**没有消费者**，这条是「按官方 API 就能生效」的那一族）（`DRAWBUFFERS` 现有 14 个文件在用、`gcolor` 5 个，属「已有自研、待定真源」而非零支持）；② `pack/texture/{CustomStorage,CustomImages}` + 设备侧分配，让第一批搬进来的两张表**真的产生一次 draw**；③ `pack/target/{SamplerPlan,TargetPlan,ChainPlan}`（821 / 1644 / 1356 行，含派生管线绑包 sampler 那段——本仓已两次在 `noisetex` 这类漏绑上翻闸即崩）。面外照旧：`compat/sodium/**`（PolyForm）、`pipeline/transform/**`（AGPL 依赖，只取规则）、Sulkan（GPL）、OptiFine 本体（ARR）。

（编号说明：§7-8 是 2026-10-13 收尾时另起的一个 H2 台账，不属本节序列，故本条从 7-7 直接跳到 7-9。）

---

## §7-8 · 阶梯剩余判据台账（2026-10-13 收尾交接；每格都写「最省的一刀」与臂数）

> 用途：下一轮不必翻 `CHANGE_LOG` 找「还欠什么」。代码侧（第 1~6 步的注入点与「必须物理消失」那一栏）
> 已逐条核过（见 §7-7 与 `CHANGE_LOG` 一百五十），**剩下的全是判据**，除最后一行外都不欠代码。

| # | 判据（阶梯原文口径） | 现状 | 最省的一刀 | 臂数 |
|---|---|---|---|---|
| 1 | 第 5 步 `GAP-039`「岩浆里没有天空」 | ✅ **已签**（`4b2ad93`：头浸岩浆 天空蓝 0.09% vs 空气 36.66%，可逆） | —— | 0 |
| 2 | 第 5 步 `GAP-039` 另两格（粉雪 / `Skybox.NONE` 维度） | 🔴 **前置缺失**（2026-10-13：头+脚在粉雪里 30 秒，`[GAP-038/雾]` 恒 `fogType=NONE`、画面无白化 ⇒ 原版那道门控没进入 ⇒ 这一格**既没签成也没否证**；下一刀先读原版 `FogRenderer` 的粉雪分支，见 `GAP-039` 那行） | 粉雪：与岩浆那格**逐字同形**（`fill 5×4×5 minecraft:powder_snow replace minecraft:air` → 读天空蓝占比 → 换回）；`Skybox.NONE` 要换维度 ⇒ 撞上 `GAP-052`（OIT 的对外接口仍钉在主目标），**别在末地那一臂顺手判** | 粉雪 1 臂；末地 1 臂但先等 `GAP-052` |
| 3 | 第 5 步 `GAP-037`「天空深度只被翻一次」 | 🟡 **仪器已落地 + 第一次读数 = 1 次**（`e687c6a`，`DepthFlipLedger`），⚠️ 但「1 次」的原因是第二个所有者被 `GAP-054` 的几何守卫拒掉了 ⇒ **没复发也没被证伪** | 等 `GAP-028` ③ 把包天空接回去之后**重读同一行**：读数必须仍是 1 次，变成 2 次即 `GAP-037` 复发 | 0（读日志） |
| 4 | 第 5 步「一百一十轮的星点判据不得回退」 | 🔴 **已取证、判据为红**（夜里天空带排掉十字准星后**一个星点都没有**；`docs/13` `GAP-037` 关系行） | 收口条件 = `GAP-037` 的修法落地（深度单点化，M-04 ⑤-e 那一轮）；取证口径已定：`time set 18000` + `weather clear` + **当场 `time query day` 钉住日时钟** + `pitch≈13` 而非朝天 + **报数必须带 x/y 分位** | 1 臂 + 一次修法 |
| 5 | 第 2 步「各向异性两档锐度随用户设置变」+「同机位同帧读数与今天相同」 | ⬜ 未签（后者的「今天」基线已随删码过去，**只能改成「与关掉复用键的那一臂相同」**） | 两臂**冷启动**（`mixin.reuseChunkSampler` 真/假），同一机位同一 `time set 6000`，取斜视地面的锐度带（梯度能量）比 | 2 臂 |
| 6 | 第 4 步「A/B 对 `h08` 基线 48.22 / 39.03% 同量级」 | ⬜ 未签（「注入失败标记 = 0」（`07` §1.4.1bis 六条针）与「错配被拒」两格已签：前者 10-13 真机、10-14 换针重读 10-13 全天 36 份日志 = 35 趟 0 命中 / 1 趟命中那次已修 FATAL（当时用的 `Mixin apply failed` 是假绿针）、后者 `PassAwarePipelineRouteTest` 真值表） | 需 `slots=8` 取证档（`run/config/vkdisp-pack-options.properties` 加 `BSL_v10.1.8.ADVANCED_MATERIALS=true`）+ **冷启动**（包选项别用热重载，`gaux1` 那条老坑） | 2 臂 |
| 7 | 第 6 步 M-14「包选项进原版视频设置页」 | ⬜ 未签（注入点 `VideoSettingsPackOptionsMixin` + 键 `packOptionsInVanilla` 都在，旧 `PackPickerScreen` 已物理消失） | 一次真机：`control` 打开视频设置 → 截图 → 看条目在不在原版页里（`close_container` 能关暂停菜单那条经验可用） | 1 臂 |
| 8 | `GAP-054` 那块切过半个屏幕的巨型贴图平面 | 🔴 **未修；已定性为**我方的**（臂 4：`enabled=false` ⇒ 画面干净原版、平面消失）；**八臂下来：`[mixin]` 整块 11 键全关 ⇒ 平面照旧；`shaderPack="none"` ⇒ 平面消失 ⇒ 生产者出在包的加载/生成侧，不在那 11 个注入点里**（`9be7d40` 冷启动 A/B 三趟逐字相同 ⇒ 与 `mrt.skyPackShader` 无关；`a0d16c1` 探针：四个产物出口里没人问过 `gbuffers_skybasic`）；🔴 **2026-10-14：已排除清单里 `mrt.packTerrainShader` 那一格撤回** —— 它唯一的依据是臂 3 那行 `[GAP-036/半开档]` WARN，而那行不读这个键（`FullscreenPassHook:220`），同趟真读该键的日志反而证明它当时是 `true` ⇒ **该键从未被任何一臂测过，回到候选位**（逐字见 `docs/13` GAP-054 的「八臂之后」行与 GAP-056 表首行）；✅ **10-14 已补测 ⇒ 这一对正式排除**：`mrt.terrain` + `mrt.packTerrainShader` **成对关**（单关会落进半开档 = 全黑，判不了）冷启动 A-B-A，基线两趟与成对关那一趟里，右下角那块巨型树叶/图集贴片**逐字同形同位** | **一律冷启动**，一次一臂：① `mixin.wireTerrain=false` + `mrt.terrain=false`（探针链里 `MrtTerrainPass#drawTerrain:382` 证明这份 toml 档我方在画地形 ⇒ 地形回到候选位。**⚠️ 2026-10-13 收尾跑过：两把一起关会落进「半开档 = 整屏全黑」（实测 `全片 mean=5.8`）⇒ 那一臂不可判，改成只关 `mrt.terrain=false` 单键**）② `mrt.postChain=false` ③ **原版对照**（`run/mods/` 只留 mcpfabric）—— ③ 若还在就直接摘账 | 3 臂 🔴 **臂 2/3 已跑（2026-10-13，冷启动）**：单键 `mrt.terrain=false` ⇒ 平面照旧（**地形那一笔排除**，连带暴露 `GAP-055` 每帧异常）；`postChain=false` ⇒ 平面照旧（**链排除**；那臂被 `GAP-056` 的回写污染成两键，但「链关着还在」只依赖 postChain 那一半）。⇒ **只剩天空那一族**，**下一刀进包侧**：① 保留包但一条派生程序都不注册 ⇒ 切「包的资源 vs 包的程序」；② 先补 §7-8 早写着的那条生成侧自报（`FullscreenPipelineRegistrar` / `GeneratedSources.adapters`）；③ `[pack]` 逐键二分；④ ✅ **已跑（10-14）：那一格补完了** —— 臂形改成 `mrt.terrain` + `mrt.packTerrainShader` **成对关**（单关 = 半开档 = 全黑，不可判），三趟冷启动 A-B-A + 打开看图 ⇒ **平面照旧，这一对排除**；`[生效档]` 三档读数与文件逐字一致（非默认 4 → 2 → 4）、`is not correct` 0 命中、六条针 0 命中，起跑后先回读 `[生效档]` 全量自报块、停客户端后再做一次**逐键值 diff**（`GAP-056` 2026-10-14 换的新口径 —— `md5sum` 只当「变过没有」的哨兵，不当「改了哪个键」的证据）。💡 原版对照不必拆 `run/mods/`：`enabled=false` 就是干净兜底臂。⚠️ 数值签名今天取不了：直连 `/rpc` 的 `vision.screenshot {path}` 被本机权限层拦 ⇒ 要数值的格（`GAP-053`/星点/`h08` A-B）先解决**截图落盘通道**。 ✅ **本行三件事 2026-10-14 全变了（逐字见 `13` GAP-054 的臂 F / 签名纠正 / 下一刀三行）**：① **落盘通道已打通** —— `vision.screenshot` 实测不认 `path`、只回 base64 ⇒ 落盘自己做（`tools/vkdisp-shot/grab.py` + `Shots.java`，口径移植 `evidence/tools/read_shot.py`，零依赖），流程写进 `01-DEV-LOOP.md` **§1.4** ⇒ `GAP-053` / 星点 / `h08` A-B 这几格**不再有仪器阻塞**；② **生成侧自报 + 总闸都落地**（`PackDerivedProgramGate` 的 `[GAP-054/生成侧]` 一行 + 新键 `pack.derivedPrograms`），臂 F 读数 = **派生程序零注册 ⇒ 那块照旧** ⇒ 「包的程序」这一侧整支排除；③ 🔴 **签名要改口**：右下角那块是**屏幕锚定**的（抬镜头它不动），与「tp 130 格就没了」那堵**世界**图集墙是两个元素，且不是手持方块（`enabled=false` 同机位对照否掉）⇒ 本行上面所有以「右下角那块」为判据的格（含 10-14 的 `packTerrainShader` 成对关那一格）**要按图集墙重签**，下一刀改成 **`enabled=true` + `shaderPack="none"` 冷启动**（第一次把「包在不在」与「我方全屏 pass 在不在」切开）。 ✅ **那一刀 10-15 跑完（臂 H）：那块消失** ⇒ 我方全屏 pass 也排除，必要条件收窄到「包被加载这件事」；但包 zip 没进原版资源栈（全仓只有一个 `createPack()`，挂的是我方虚拟包）、两条资源腿（`PackTextures` 一条噪声图 + 27 条包自写 uniform）在臂 F 里无人消费 ⇒ **机制还没有落点** ⇒ 下一刀从「改配置」换成「**加 draw 站点探针**」（形状照 `PackArtifactProbe`，先回答「这一笔是谁画的」）。逐字见 `13` GAP-054 的臂 H / 三臂形状 / 下一刀三行。 |
| 9 | 闸门 M-04（R7）⑤-d 后半 + ⑤-e | ⬜ 未做（⑤-a~c 已落地并真机签；⑤-d 第一刀已落但 `GAP-053` 的**代次归属**未结；⑤-e 第一格「深度附件换成 gbuffer 深度」已落） | 先结 `GAP-053`（候选 (a) 单绘档整条不打开 / (b) 接管取「链要读的那一代」/ (c) 接管档退化成单代），再删死码（自绘路径 + `ColortexPool` 一侧 + M-01 退化件）并把池锚点换成主 pass 自己的 target0 | 代码 + 每步一臂取证 |

🔖 **四条已经付过学费的取证纪律（① ② 本轮新写进记忆，别再用热重载下结论；③ ④ 是 2026-10-14 补的）**：
① 「键 → 画面」的归因实验**一律冷启动** —— 这一族键参与生成指纹（`VkDispVirtualPack:1031` + `VkDispConfigHotReload:329`），换臂顺带触发整包重生成，「关掉就没了」会跟着重载走（`GAP-054` 就是这么白跑了一次修复）；
② 一次只切**一个**键、每次等满一次完整重载 —— 17 键「全关再全开」的热重载风暴把 `nvoglv64.dll` 打出过 `EXCEPTION_ACCESS_VIOLATION`。
③ **一行日志只能为它自己读过的值作证** —— `GAP-056` 把「文件里 `mrt.packTerrainShader` 自己变成了 `false`」挂在一行 `[GAP-036/半开档]` WARN 上，而那行的条件不读这个键、句里键名是写死的字符串 ⇒ 一臂的档被误判、一格「已排除」被误签。从今天起每臂起跑后第一件事 = 回读 `[生效档]` 全量自报块（启动期把**每个键的实际生效值 + 是否等于代码默认**打出来），停客户端后再做一次逐键值 diff；`md5sum` 只当「变过没有」的哨兵。判据针同理：`Mixin apply failed` 那条针匹配不到真实措辞（`Mixin apply for mod … failed`），假绿了一整轮 —— 见 `07-CONSTRAINTS` §1.4.1 的六条标记。
④ **配置文件不是只读台账**：FML 载入期发现文件「不是 spec-correct」时会**整份重写**（缺失键按代码默认补齐、不在 spec 里的孤儿键删除），信号是日志那句 `Configuration file … is not correct. Correcting`。10-13 全天 35 份归档里命中 **2 次**（13:40、16:04），而 `run/config/vkdisp-client-{1..5}.toml.bak` 那条链上能看到它留下的形状：`terrainSingleDraw` 从 `true` 变 `false` 且行位移动、`packOptionsScreen` 整行消失。⇒ 命中过的那两趟「起跑前那份 ≠ 生效档」；这一族键的代码默认多是 `false`，所以「文件里它自己变 false」有真机制，不需要假设我方有守卫在改它。

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
| **glsl-transformer** (Douira) | [LICENSE](https://github.com/Douira/glsl-transformer/blob/master/LICENSE) = **AGPL-3.0**（README 还要求使用方同许可） | ⛔ **不引库**（2026-10-17 裁决；原 X20/X21 判定继续有效）——「java glsl parser」搜索首位命中，务必挡住 |
| **glsl-preprocessor** (IrisShaders) | POM = **GPL-3.0 + 例外条款** | ⛔ **禁止**（X21，`07` §1.3 陷阱 2 点名此类） |
| Iris（LGPL-3.0）/ Vitrail（LGPL-3.0）/ VulkanMod（LGPL-3.0）/ Sulkan（GPL-3.0）/ OptiFine（ARR） | 见 `03-DIRECTION.md` 附录 A + 各仓库 LICENSE | 🔖 **2026-10-17 分族改判**：前三者（LGPL-3.0）**可搬**，逐文件进 `NOTICE.md` 台账；Sulkan（GPL-3.0）与 OptiFine（ARR）**仍一行不抄** |
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
| `QUALITY-DEBT.md` QD-11 | ② 「接方向即可」原判**不完整**（阴影图根本不存在）；① 原判**偏贵**（派生机制已在仓） | ✅ 已就地更正两级拆分，并指向 `GAP-034`；另记 `SamplerDimensionPlan:28-32` 早按 **X39** 否决过按名字硬编码 |
| `QUALITY-DEBT.md` QD-14 | 旧方案给反射找的解耦理由若含「保持 100% 自研」，该前提已被撤销 | ✅ 已就地标注前提消失 + 指向本文 §5.3；🔖 原注释只提「`#ifndef` 求值链」，**未证反射是必须的** ⇒ 核实动机这步保留 |
| `03-DIRECTION.md` §4 + `07-CONSTRAINTS.md` §〇 | 「MIT ⇒ 100% 自研」**不是 MIT 的要求**（用户 2026-10-10 裁决，§7-1） | ✅ 两处已改写为「只并入 MIT/Apache-2.0/BSD 族，保留 `LICENSE`/`NOTICE` 并声明改动」；`07` §1.3 判定表补 BSD 行、§七 自检清单新增一条署名核对项；**L5–L8（LGPL/GPL/ARR）与 P1/P2/P3 未动** |
| `07-CONSTRAINTS.md` §五 依赖版本锁定 | 引入 `org.anarres:jcpp`（移植）或 `compileOnly org.lwjgl:lwjgl-shaderc` | ✅ **已登记锁法**（`07` 新增 **§5.1**：两个坐标各自的版本键 + 并码署名义务 + 「游戏自带坐标会漂」这条）。🔴 配套实测：**26.2 = 3.4.1 / 26.3 = 3.4.3** ⇒ 已写进 `06-MIGRATION` §3 **V6**（升 MC 必查）与 `05-VERSION` §2 的事实行。⚠️ **依赖本身仍未引入** —— §7-2 那句「测试/离线工具可否引用」还待判，本节只登记「放行后怎么锁」 |
| `16-READING.md` §3 / `00-INDEX.md` §1 | 新增本文 | ✅ 已登记 |
