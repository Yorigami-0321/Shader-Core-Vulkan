# 18 · 并行开发路线

> **目的**：把关键路径（P0.2 → P0.3 → …）阻塞期间的等待时间，转成可验证的并行产出。
> **配套**：`01-DEV-LOOP.md`（开发循环）、`07-CONSTRAINTS.md`（红线）、`17-NATIVE.md`（参考先行）、
> `04-SPEC.md`（组件清单）、`08-TESTING.md`（验收）。
> 本文**不修改**上述任何文件；它只回答一个问题：**哪些活可以同时干，以及干的时候不许碰什么。**

---

## 0. 一句话

**冷路径可以并行，热路径只能串行。**
判断标准只有一条：**这份工作能不能在不启动游戏的前提下，用单元测试自证做对了。**

---

## 1. 为什么要拆（现状瓶颈）

| 事实 | 后果 |
|---|---|
| 关键路径上的每一步都要 GPU 证据（截图 / 日志 / 计数） | 只能在**一个**环境串行推进，无法加人加速 |
| `01-DEV-LOOP.md` §10 的 P0.1 → P4.3 是一条**单链** | 上游没出结果，下游全在等 |
| 自研四件事里有三件是 ❄️ **冷路径**（`02-OVERVIEW.md` §4） | 这三件**根本不需要 GPU**，却排在链子后面等 |
| `04-SPEC.md` §7 把「GLSL 转译工作量被低估」标为**高风险** | 最重的活排在最后面，风险最集中 |

**结论**：把三件冷路径工作（① 格式解析 / ③ GLSL 转译 / ④ GUI 数据层）从链子上摘下来，
**提前并行做完，等主线推进到 P2 时一次汇合**。关键路径的总时长不变，但 P2 阶段的串行工期被消掉。

---

## 2. 并行判据（唯一标准）

一份工作**可以并行**，当且仅当**同时**满足下面四条：

```
[ ] 输入输出全是内存数据（字符串 / 字节流 / POJO / 数值）
[ ] 不触碰任何 com.mojang.renderpearl.* 或 com.mojang.blaze3d.* 类型
[ ] 不需要启动 Minecraft（不需要 runClient / runData）
[ ] 能用 JUnit 单测断言「做对了」（不是「看起来对」）
```

**四条缺一不可。任何一条不满足 → 归入关键路径，串行做。**

> 判据的技术依据：业务包**本来就禁止** import 原版渲染类型（`07-CONSTRAINTS.md` T5）。
> 也就是说 —— **凡是合规的业务代码，天然满足前两条**。这是本项目设计红利，不是巧合。

### 2.1 反例（看起来很"独立"，其实不能并行）

| 看着像 | 为什么不行 |
|---|---|
| `PipelineFactory`（从 Program 生成管线） | 要真实调用 `RenderPipeline.builder()` → 需要 GPU 验证 |
| `RenderTargetPool`（colortex 池） | 要真实分配 `GpuTexture` → 需要 GPU 验证 |
| `PackOptionsScreen`（选项界面渲染） | 要真实挂到原版屏幕 → 需要 runClient |
| `FrameComposer`（帧图编排） | 效果只能从画面上看出来 |
| `OfUniformManager`（uniform 上传） | 上传对不对，只能看画面有没有变 |

> **上表这五类，全部只能在关键路径上串行做。** 并行线**不许提前实现它们**（见 §7.1）。

---

## 3. 前置闸门：契约冻结 + 测试基建（必须串行）

**并行会撞车，所以先把接口冻住。这一步由 `env-1` 独占完成，做完才放行 §4 的并行线。**

| # | 冻结物 | 落地物 | 为什么必须先有 |
|---|---|---|---|
| **F1** | `bridge/` 接口签名 | `bridge/` 5 个接口的**方法签名 + Javadoc**（无实现） | `06-MIGRATION.md` §2.1；E 线要用它的类型 |
| **F2** | `pack/` 数据模型 | `ShaderPack` / `Program` / `ProgramStage` / `Option` / `OptionType` 的**字段冻结** | C / D / E / F 四条线**全都**消费它 |
| **F3** | `glsl/` 输入输出契约 | `TranslateResult`（文本 + 诊断列表）的签名 | 让 C 和 D 能背靠背开工 |
| **F4** | **测试基建** | `build.gradle` 接 JUnit 5 + `src/test/java` + `src/test/resources/packs/` 骨架 | 🔴 **当前 `dependencies {}` 块是空的，项目没有任何测试框架** |

### 3.0 现状快照（2026-09-29 19:36 核实，F1–F4 闸门落地后）

> 随仓库推进更新。**闸门未过之前，§4 的并行线一条都不许开。**

| 闸门项 | 现状 | 证据 |
|---|---|---|
| **F1** | ✅ **已落地** | `bridge/` 5 接口齐：`DeviceApi`（P0.2）+ `PipelineApi`/`FrameApi`（P0.3）+ `ContractVersion`/`RenderApi`/`TextureApi`/`MixinTargets`（F1）；`ContractVersion.VERSION=1` 承载 §3.2 版本流程 |
| **F2** | ✅ **已落地** | `pack/` 8 类冻结：`ShaderPack`/`Program`/`ProgramStage`/`Option`/`OptionType`/`Dimension`/`VertexAttribute`/`UniformDecl`（contract-pack 交付，compileJava exit=0、红线 NO MATCH） |
| **F3** | ✅ **已落地** | `glsl/` 三件套：`TranslateResult`/`TranslateDiagnostic`/`SourceLineMap`（contract-glsl 交付，compileJava exit=0、35 条行为断言全绿） |
| **F4** | ✅ **已落地** | `build.gradle` 接 JUnit 5（BOM 5.13.4）+ JUnit Platform；`src/test/` 骨架 + `F4InfraSmokeTest` **2/2 PASSED** |

**闸门状态：F1–F4 全部 ✅ → 并行线已放行**（2026-09-29 本轮落地）。

- **F1/F4**：由 env-1（lead）落地并推送（commit `3106a23`）；F4 是 `build.gradle` 的一次性改动，此后并行线只写各自 `src/test/` 子路径。
- **F2/F3**：纯新增契约，不碰共享文件，已交付。
- **契约变更**：F1–F3 冻结后若要改字段/签名，走 §3.2 流程（由 env-1 统一改 + `ContractVersion.VERSION` +1）。

**已解锁的并行线**（本会话分工：env-1 负责主线 + D/E/F；A/B/C 由外部环境负责）：
**D 线**（`glsl/translate/`）与 **E 线**（`pipeline/model/`）已开工；**F 线**（`config/`）依赖 F2 已满足。
**A/B 线**需 F2（已满足），**C 线**需 F2 + F3（均已满足）——由外部环境认领时可直接开工。

### 3.1 闸门判定

```
F1–F4 全部落地 → 放行并行
任一未落地 → 不许开并行线（否则必然互相改崩）
```

### 3.2 契约冻结后的变更流程（重要）

冻结之后**不是不能改**，但要走流程：

```
1. 提出方说明：哪个字段/方法不够用、为什么、影响哪几条线
2. env-1 统一改契约（其他环境不许自己改）
3. 契约版本号 +1（写入 bridge/ContractVersion.java 的常量）
4. 通知所有环境 rebase + 重跑各自单测
```

**禁止**：并行线在自己的分支上偷偷改 `ShaderPack` 的字段来"顺手满足需求"（违反 `07-CONSTRAINTS.md` X12）。

---

## 4. 并行线清单（A–F）

> 每条线**独占一个包路径**。跨线改文件视为越界。
> 包路径是对 `04-SPEC.md` §3 组件清单的**细化**（同一批类，只做子目录隔离），**不新增组件**。

### A 线 · `pack/properties/` 四件套

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/pack/properties/` + 同名 test 包 |
| **交付物** | `ShaderProperties` / `BlockProperties` / `ItemProperties` / `DimensionProperties` |
| **完成标准** | 四类 `.properties` 的键值能解析成对象；选项（含 `sliders` / `profiles`）能被枚举；`block.properties` 的方块 ID → 图层号映射正确 |
| **证据** | JUnit 单测全绿 + 测试用例清单 |
| **参考（只读思路）** | Iris `shaderpack/parsing/`（LGPL，**格式本身是事实性信息，代码不抄**） |
| **支撑主线** | P2.2 |
| **不许做** | 不许解析 GLSL 源码（那是 C/D 线的活） |

### B 线 · 扫包 + 解包

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/pack/`（`ShaderPackScanner` / `ShaderPackRepository`） |
| **交付物** | `shaderpacks/` 下的 `.zip` 与目录两种形态都能扫描并列全；解包到虚拟资源包的**路径规划** |
| **完成标准** | 空目录 / 无 pack / 损坏 zip / 无 `shaders/` 的包，四种边界都不崩且**显式报错**（T11） |
| **证据** | JUnit 单测（含边界用例） |
| **参考** | 无直接参考，按原版资源包语义自行设计 |
| **支撑主线** | P2.1 |
| **不许做** | 不许真的注册虚拟资源包（那要原版资源系统，属关键路径） |

> ⚠️ `04-SPEC.md` §3.1 的约束照旧有效：**只读不写用户的包**，不许往 `options.resourcePacks` 反复写。

### C 线 · GLSL 预处理器

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/glsl/preprocess/` |
| **交付物** | `IncludeProcessor` / `DefineProcessor` / `ConstEvaluator` |
| **完成标准** | `#include` 按 OF 语义解析（相对路径、可嵌套、循环包含要报错）；`#define` / `#undef` / 条件编译正确；`const int X = 0; // [0 1 2]` 型选项常量能识别；**行号映射保留**（编译报错要能指回原文件） |
| **证据** | JUnit 单测：GLSL 文本进 → 文本出，逐字符比对 |
| **参考（只读思路）** | `IrisShaders/glsl-preprocessor`（⚠️ GPL-3.0 + 例外条款 → **按禁止处理**，L12 §1.3 陷阱 2） |
| **支撑主线** | P2.3 |
| **不许做** | 不许碰 SPIR-V（T4）；不许改 `attribute` / `uniform` 语法（那是 D 线） |

### D 线 · GLSL 转译

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/glsl/translate/` |
| **交付物** | `OfGlslTranslator` / `AttributeRewriter` / `UniformInjector` |
| **完成标准** | OF 老式 `attribute`/`varying` → M 语法；OF 内建 uniform（`04-SPEC.md` §3.2 那张表）声明注入完整；转换是**幂等**的（跑两遍结果一致） |
| **证据** | JUnit 单测：输入 OF 方言样本 → 输出与预期字符串比对 |
| **参考（只读思路）** | `IrisShaders/glsl-transformer`（自定义传染许可 → **按禁止处理**） |
| **支撑主线** | P2.3 / P3 |
| **依赖** | 需 C 线的输出格式先定（F3 冻结） |
| **不许做** | 不许动 `#include` / `#define`（那是 C 线） |

### E 线 · 管线纯计算件

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/pipeline/model/` |
| **交付物** | 三条**纯函数/纯数据**：① OF 顶点属性 → stride/offset 计算表（`04-SPEC.md` §4）② `PipelineCache` 的键计算 ③ `BindGroupLayout` 的**声明中间表示** |
| **完成标准** | stride 计算结果与 `04-SPEC.md` §4 的属性表逐项一致；缓存键对同一 Program 稳定、对不同 Program 不碰撞；中间表示能被序列化后打印比对 |
| **证据** | JUnit 单测：数值断言 + 键唯一性测试 |
| **参考（只读思路）** | VulkanMod 的 `GlUtil.vulkanFormat` **格式表思路**（LGPL，不抄代码） |
| **支撑主线** | P1.2 / P4.2 |
| **不许做** | 🔴 **不许写 `PipelineFactory`**（不能提前调 `RenderPipeline.builder()`）；只产中间表示，真实注册留给主线 P1 |

> E 线是为 T9（stride 断言）与 P4.2（切包回归残影）提前排雷 —— 这两个坑在主线上一旦踩到，
> 症状是「拉伸的彩色尖刺且不报错」，排查成本极高。**在单测里排掉它，比在游戏里排便宜一个数量级。**

### F 线 · 选项模型 + Binding

| 项 | 内容 |
|---|---|
| **独占路径** | `src/main/java/dev/vkdisp/config/` |
| **交付物** | `PackOptions`（选项值的运行时容器）+ `OptionBinding`（选项值 → `#define` 表 / uniform 值） |
| **完成标准** | 选项默认值正确；值越界能钳制并打 WARN（T11）；`#define` 表生成结果可对比；**与 A 线的 `Option` 模型对接通** |
| **证据** | JUnit 单测：值求值 + 边界（越界 / 非法值 / 缺省） |
| **参考** | 原版屏幕基类的选项语义 |
| **支撑主线** | P4.3 |
| **不许做** | 不许写 `PackOptionsScreen`（界面渲染要 runClient） |

---

## 5. 关键路径（**不可并行**）

```
✅ P0.1 空模组能构建能跑        （cfbc2b4 验收通过）
✅ P0.2 确认跑在 Vulkan 后端   （83a704d 验收通过；bridge/DeviceApi 落地）
✅ P0.3 首个可见产物           （主菜单截图 + 日志证据齐，run3 ERROR 清零）
✅ P0.4 bridge 包隔离落地       （业务包 `^import com\.mojang\.renderpearl` = 0 命中，仅 bridge/ 3 文件允许）
✅ P1.1 uniform 传递           （自定义 UBO VkDispParams；三张间隔截图 30.6%/25.8%/56.3% 像素不同）
✅ P1.2 管线计数对齐           （日志 registered=1, compiled=1 aligned）
🟡 P3 前置能力（关键路径上按需推进）：
   ✅ 中间目标 + 采样器绑定 + 多级 ping-pong（双 pass / 三 pass 链，实测标定）
   ✅ 深度附件 + 深度写入 + 深度采样（D32_FLOAT，灰度梯度量化验证）
   ✅ 真实几何 + 深度剔除（顶点缓冲、layout(location)、重叠区像素判定）
   ✅ 光空间矩阵上传链路（mat4 UBO → 顶点变换，位移像素量化吻合）
   ✅ P3.1 影子 pass 基础：自建光空间 view-projection + 阴影贴图渲染与可视化（非全黑非全白达标）
   ✅ P3.3 阴影采样：世界视图渲染 + 阴影深度比较（暗/亮绿像素级判定，0.35 调暗系数精确吻合）
   ✅ 真实透视相机矩阵（fov60/zZeroToOne/lookAt，高度比 1.1429 = 理论四位小数吻合；固定占位相机）
      ⏳ 仍缺：接原版 GameRenderer 相机、与原版 LevelRenderer 光空间列表 / CSM 集成、PCF 软阴影
   ⏳ 深度测试剔除（真几何）、多颜色附件（colortex0..N）、多目标池复用
   ▶️ P3.1 影子 pass：光空间矩阵 + 阴影贴图（深度链路已就绪）
   ▶️ P2.x 解析链：等 A/B/C 线汇合（外部环境）
   → P2.4 composite 生效 → P3.1 shadow → P3.2 gbuffers → P3.3 deferred
   → P4.1 主流包 → P4.2 切包回归 → P4.3 选项 GUI
```

**为什么不可并行**：每一步的验收都是「画面/日志里出现了某个东西」，
必须真实启动 + 真实 GPU + 真实截图。**没有 GPU 证据的进度不算进度**（`01-DEV-LOOP.md` §0）。

**关键路径仍受 §10「一次只做一个」约束** —— 单环境内不许并行。

---

## 6. 汇合点与并入顺序

| 汇合时机 | 并入哪几条线 | 立刻解锁的主线任务 |
|---|---|---|
| **P0.4 落地之后** | A + B | P2.1 扫包、P2.2 解析 `shaders.properties` |
| **P0.4 落地之后** | C + D | P2.3 `#include` 编译通过 |
| **P1.2 之后** | E | P1.2 管线计数（键碰撞排查）、P4.2 回归 |
| **P3 之后** | F | P4.3 选项 GUI |

**并入前必须**：单测全绿 → 主线 rebase → 跑 `08-TESTING.md` §9 回归清单（至少跑到「启动到主菜单」）。

> 汇合是**一次性动作**，不是持续集成。不要边写边并 —— 那会让主线的回归清单天天变。

---

## 7. 边界与限制（本文重点）

### 7.1 硬边界 —— 并行线**不许**做的事

```
❌ 不许写任何 import com.mojang.renderpearl.* / com.mojang.blaze3d.* 的代码（T5 / X10）
❌ 不许写需要 runClient 才能验证的功能（§2.1 那五类）
❌ 不许建 mixin（mixin 只归关键路径，且每个注入点必须打日志 T10）
❌ 不许改 build.gradle / gradle.properties / settings.gradle（除 F4 由 env-1 做的那一次）
❌ 不许改 docs/ 下任何文件（文档同步归 env-1）
❌ 不许改另一个并行线的包路径（X12）
❌ 不许改 F1–F3 冻结的契约（要走 §3.2 流程，由 env-1 统一改）
❌ 不许提前上 C++/Rust（X17，可行性未验证）
❌ 不许把第三方 shaderpack 提交进仓库（见 §7.6）
❌ 不许因为「是冷路径」就跳过参考调研（T13 / X13）
```

### 7.2 共享文件清单（**只有 env-1 在冻结阶段能改**）

| 文件 | 谁改 | 什么时候 |
|---|---|---|
| `build.gradle` | env-1 | F4 阶段一次 |
| `settings.gradle` / `gradle.properties` / `gradle/` | env-1 | 版本变更时 |
| `docs/**` | env-1 | 文档同步时 |
| `CHANGE_LOG.md` | env-1 | 每次汇合后 |
| `src/main/java/dev/vkdisp/VkDisp*.java` | env-1 | 主类归关键路径 |
| `bridge/**` | env-1 | 契约变更走 §3.2 |

> **判据**：如果一个文件的路径**不在你这条线的独占路径里**，就不要打开它。

### 7.3 证据规范（并行线 ≠ 关键路径，证据格式不同）

关键路径要求「日志 + 截图」，并行线**没有画面**，所以证据换成：

```
[ ] JUnit 单测全绿（贴测试类名 + 用例数 + 通过数）
[ ] 边界用例清单（空值 / 空集合 / 异常输入 / 损坏文件）
[ ] grep 自证无 GPU 依赖：grep -rn "com\.mojang\.\(renderpearl\|blaze3d\)" <本线包路径> → 必须 NO MATCH
[ ] 【参考调研】注释块（含第 0 条合规结论，T13 / L12）
[ ] 本次改动的文件清单（必须全部落在本线独占路径内）
```

**以下措辞一律拒收**（沿用 `01-DEV-LOOP.md` §7）：
「已完成」「应该好了」「看起来正常」「理论上没问题」「单测没写但代码很简单」。

> ⚠️ **静默失败第二定律**：单测全绿**不等于**逻辑对 —— 只等于**你想到的情况**都对。
> 所以并行线必须显式列出**没覆盖的情况**，而不是假装都覆盖了。

### 7.4 红线映射（并行线同样全量适用）

| 条款 | 在并行线上的具体含义 |
|---|---|
| **L12 / X19** | 调研**第 0 步是核许可证**，只信仓库里的 `LICENSE` 文件；判不过就换参考、**不再读它的代码** |
| **X20 / X21** | 无 LICENSE = ARR = 不可用；「GPL + 例外条款」一律按禁止处理 |
| **T5 / X10** | 业务包不许 import 原版渲染类型 —— 并行线的**存在前提**就是守住这条 |
| **T11** | 降级必须显式报错或 WARN，不许「失败得像没发生过」 |
| **T12** | 要自行补充原版缺的特性？**先登记** `13-GAP-REGISTRY.md`（登记归 env-1） |
| **T13 / X13** | 没调研不许写实现；【参考调研】注释块缺第 0 条 = 不许动手 |
| **X12** | 没点名的东西不顺手一起改 |
| **X17** | 不许提前动原生（C++/Rust） |
| **T14 / X14** | 不许凭「感觉慢」下性能结论（并行线本来也不该做性能优化） |

### 7.5 与「一次只做一个」的适用口径

`01-DEV-LOOP.md` §10 写着「**一次只做一个**」。本文不修改它，只明确它的适用边界：

```
§10 约束的是「单个环境内的任务序列」—— 一个环境任何时刻仍然只做一个任务。
§10 不约束「跨环境的分工」—— 多个环境各做一个不同的任务，不违反 §10。

换句话说：
  ✅ env-1 做 P0.3，同时 env-2 做 C 线 —— 合规
  ❌ env-1 同时做 P0.3 和 P0.4 —— 违规
  ❌ env-2 同时做 C 线和 D 线 —— 违规（D 依赖 C 的冻结产物）
```

**每个环境在任何时刻，只允许有一个 `in_progress` 的任务。** 这条不放松。

### 7.6 fixture 与第三方素材限制

| 做法 | 判定 |
|---|---|
| 自造最小 pack（1 个 composite）提进 `src/test/resources/packs/minimal/` | ✅ 允许，这是首选 |
| 把 BSL / Complementary 的包提进仓库当测试样本 | ❌ **禁止** —— 那是第三方作品，会污染本项目的 MIT 授权链 |
| 本地放第三方包做**手工**验证 | ✅ 允许（不进仓库、不进 jar） |
| 把第三方 pack 的某个 `.glsl` 片段复制进单测预期值 | ❌ 禁止（等于并入第三方代码） |

### 7.7 并行线的性能纪律

并行线全部是 ❄️ 冷路径（`17-NATIVE.md` §3.2）：**清晰优先，一律不做性能优化。**

- 不许为了「快一点」引入复杂数据结构或缓存（`08-TESTING.md` §8.1：达标即停）
- 不许上原生（X15：冷路径上原生收益为零）
- 解析 / 转译的性能线是主线 P2 的验收项（`08-TESTING.md` §8：解析+转译 ≤ 1 秒），
  **但有实测数据前不许优化**（T14）

---

## 8. 环境分配

### 8.1 四个环境

| 环境 | 职责 | 独占路径 |
|---|---|---|
| **env-1（主）** | F1–F4 冻结 → 关键路径 P0.3 → P0.4 → P1.x | `bridge/` `VkDisp*` `docs/` 构建脚本 |
| **env-2** | C + D（glsl 预处理 + 转译）—— **最重、风险最高，优先扔出去** | `glsl/preprocess/` `glsl/translate/` |
| **env-3** | A + B（pack 解析） | `pack/` `pack/properties/` |
| **env-4** | E + F（管线纯计算件 + 选项模型）+ fixture | `pipeline/model/` `config/` `src/test/resources/` |

### 8.2 只有两个环境时

| 环境 | 职责 |
|---|---|
| env-1 | F1–F4 冻结 → 关键路径 |
| env-2 | C + D 优先，做完接 A + B |

> 优先级判据：**先做「做错了代价最大」的**。C/D 是 `04-SPEC.md` §7 里的高风险项，
> 且 D 依赖 C 的冻结产物 —— 这条链越长越该早开工。

---

## 9. 每环境自检清单（提交前跑）

```
[ ] 本次改动全部落在本线独占路径内（列文件清单自证）
[ ] 没有 import com.mojang.renderpearl.* / com.mojang.blaze3d.*
[ ] JUnit 单测全绿
[ ] 边界用例已覆盖（空值 / 空集合 / 损坏输入）
[ ] 降级路径显式报错或 WARN（T11）
[ ] 【参考调研】注释块存在，且第 0 条写了合规结论（T13 / L12）
[ ] 所有参考项目都查过仓库的 LICENSE 文件（不是平台页面）
[ ] 没碰共享文件（§7.2 清单）
[ ] 没改冻结契约（改了就走 §3.2）
[ ] 没提前写 GPU 相关代码（§2.1 反例清单）
[ ] 没提交第三方 shaderpack（§7.6）
[ ] 没做性能优化（§7.7）
```

---

## 10. 待办与未决

| # | 事项 | 状态 |
|---|---|---|
| P-1 | **F2 + F3 契约冻结** | ✅ **已完成**（`pack/` 8 类、`glsl/` 三件套；compileJava exit=0、红线 NO MATCH、F3 另 35 条断言全绿） |
| P-1b | F1 剩余 `bridge/` 接口：`RenderApi` / `TextureApi` / `MixinTargets` + `ContractVersion` | ✅ **已完成**（`FrameApi`/`PipelineApi` 随 P0.3 落地；5 接口齐） |
| P-1c | F4 测试基建（JUnit 5 + `src/test/` 骨架） | ✅ **已完成**（`F4InfraSmokeTest` 2/2 PASSED） |
| P-1f | **renderpearl 实测约定（A/B/C/D/E/F 通用，避免重复踩坑）** | ✅ **已实测登记** ① `CommandEncoder.writeToTexture` **不能在 render pass 打开期间调用**（异常原文 `Close the existing render pass before performing additional commands`）→ 资源上传必须在 `createRenderPass` 之前；② 自定义 uniform 块与 sampler **放同一绑定组**（顺序与 GLSL 声明一致）才稳定采样，分属两组实测采到近似常量色；③ 方向约定（**2026-09-29 两轮实测标定**）：`vUv.y=0` → NDC `y=-1` → 屏幕**顶部**（Vulkan NDC y 向下）。采样翻转规则**分两类**：**中间目标 → 主目标** 用 `vec2(uv.x, 1.0-uv.y)`（`blit.fsh`），**中间目标 → 中间目标** 用原始 `vUv`（`composite.fsh`）——两级同时翻转会使整链上下颠倒。判据：图案底部方向参考带必须仍在底部 + 四角标颜色（通道交换后应为青色）。详见 CHANGE_LOG 的量化记录 |
| P-1e | F 线开放点：布尔 `#define` 风格（`LITERAL` vs OF 兼容 `IFDEF_TRUE`）与自由文本 STRING 选项的 GLSL 映射 | ⏳ **待实证** —— 两种风格均已实现且有单测，缺真实包证据按 07 X9 未猜死；建议 P4.2/P4.3 用真实包定稿（换默认一行改动） |
| P-1d | `04-SPEC.md` §4 与 OF 官方属性表的出入复核 | 🟡 **部分完成**——已核实并写入 §4 复核注记（`mc_Entity` 官方为 **vec3**；`vaUV1`=overlay / `vaUV2`=lightmap；`at_*` 三项存在）。**剩余未定项**：`mc_Entity` 的底层元素类型（float32 / int16）文档未给，直接决定字节数与 stride（E 线现值 47）→ **须在 P1.2 构建真实 `VertexFormat` 时实测对齐**，再走 §3.2 定稿；此前 F2/E 沿用旧值，任何线不许私改（07 X9） |
| P-2 | 把本文登记进 `00-INDEX.md` 文档清单 | ✅ 已同步 |
| P-3 | 是否要在 `01-DEV-LOOP.md` §10 加一句指向本文的交叉引用 | ⏳ 待拍板 |
| P-4 | `AGENT_CONTEXT.md` 的 Q5「P0 是否开工」口径 | 🟡 P0.1 / P0.2 均已验收通过 → **Q5 已作废，应更新该文档** |
| P-5 | ~~P0.1 构建 / 运行证据缺失~~ | ✅ **已解决**（`cfbc2b4` 回填实测证据；`83a704d` 补 P0.2 证据） |
| P-6 | `AGENT_CONTEXT.md` 决策表缺 D19+（并行路线 / F1–F4 闸门） | ⏳ 待拍板是否登记 |

---

## 11. 相关文档

| 文档 | 关系 |
|---|---|
| `01-DEV-LOOP.md` | 单环境的开发循环；本文是它的**跨环境补充**（§7.5 明确适用口径） |
| `04-SPEC.md` §3 | 组件清单；本文的包路径是它的**细化**，不新增组件 |
| `06-MIGRATION.md` §2.1 | `bridge` 隔离；本文 F1 是它的落地前置 |
| `07-CONSTRAINTS.md` | 红线全量适用（§7.4 是映射表） |
| `08-TESTING.md` | 关键路径的验收细则；并行线证据格式见本文 §7.3 |
| `13-GAP-REGISTRY.md` | 需要自行补充特性时先登记（登记归 env-1） |
| `17-NATIVE.md` §1 | 参考先行的强制要求（并行线同样适用） |
