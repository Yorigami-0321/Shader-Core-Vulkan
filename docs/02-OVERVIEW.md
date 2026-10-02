# 02 · 项目概览

> **2026-10-02 重写**（用户新指令：「目的依旧是高性能、稳定，以及保持兼容性让 optfine/iris 着色器完整的运行在 vulkan 上」）。
> 旧版写「一条路线、零 mixin、冷路径纯 Java」，本版改为**三支柱 + mixin 松绑 + 原生可实测**。

---

## 1. 一句话

**`vkdisp`（Vulkan Shader Dispatcher）** 是一个 NeoForge **纯客户端**模组：跑在 Minecraft 原版的
Vulkan 渲染后端上，让 **OptiFine / Iris 格式**的着色器包（BSL、Complementary、Sildur's…）
**完整地**运行起来。

> **独立实现。不与任何第三方渲染优化模组或着色器加载器做集成。**
> 不依赖任何第三方前置，也不尝试替代任何第三方前置。

---

## 2. 🔴 三支柱（本项目的目标定义，2026-10-02）

| 支柱 | 具体含义 | 怎么算达成 | 冲突时的优先级 |
|---|---|---|---|
| **① 兼容** | OptiFine/Iris 格式包**完整**运行：全部 program 走通、gbuffer 多附件语义正确、deferred/composite 链完整、选项 GUI 可用 | pack × program 兼容矩阵通过率；与 Iris 同场景对比 | **最高** |
| **② 稳定** | 不崩、不闪、不静默降级、不泄漏、长跑不掉帧 | 崩溃 / validation error = 0；1h 内存增量 ≤ 200MB；切包无空窗 | 次之 |
| **③ 高性能** | 帧时间贴近原版；**切包/加载等待**可接受 | B1 帧时间 ≤ 原版 +2%；B3 冷路径 ≤ 1s；B4 切包 ≤ 2s | 最后 |

**三支柱不是口号，它直接决定条款怎么读**：

- 兼容优先 ⇒ **允许为兼容而开 mixin**（已放开，见 `07-CONSTRAINTS.md` §1.4）
- 稳定优先 ⇒ **panic 跨 FFI 边界一律禁止**（T17）；**注入点必须能逐个关闭**（X29）
- 高性能但达标即停 ⇒ **不许为了跑分而重构**（X12 继续有效）

⚠️ **一条禁止**（X27）：**不许为性能牺牲兼容** —— 不能因为某个 pack 特性「太麻烦」
就砍掉不做。取舍前必须先证明它**无法**实现。

---

## 3. 问题（为什么需要它）

| 事实 | 后果 |
|---|---|
| 过去十年社区的着色器包几乎全是 **OptiFine 格式**，跑在 **OpenGL** 上 | 格式本身与 Vulkan 无关，可以复用 |
| Minecraft 26.3 把渲染后端换成了 **Vulkan** | 存量 pack 全部失效 |
| 既有加载器（Iris 等）与渲染优化模组都构建在 **OpenGL** 时代 | 在 26.3 上无从下手 |
| 已有的原版 Vulkan 着色器模组（VulkanMod / Sulkan 等）**都不支持 OF/Iris 格式** | **没有现成轮子可借**——这是本项目要做的那件事 |

---

## 4. 关键前提：原版已经给了后端插口

26.3 的原版渲染层拆出一个独立的库 **Renderpearl**（`com.mojang.renderpearl.*`），
其中包含**后端抽象层**：

```
com.mojang.renderpearl.backend.api.*
    GpuDeviceBackend / CommandEncoderBackend
    BackendRenderPipeline (+ $CreateInfo)
    SpvModule (+ $Reflection / $Descriptor / $InterfaceVariable)
```

Vulkan 的具体实现就在 `com.mojang.renderpearl.backend.vulkan.*`。

**结论：本项目不写 Vulkan 设备、不写命令缓冲、不写 render pass、不写 SPIR-V 编译器。**
第三方可以通过官方的 `RenderPipeline.builder()` 注册自己的管线，这条路已被 Sulkan 实测证明。

> ⚠️ **但原版插口不等于「够用」**。2026-10-02 源码级核实出三处硬缺口，
> 它们决定了 mixin 必须放开（详见 §5.1）。

---

## 5. 要自研的四件事（真正的活）

```
vkdisp
  ├── ① OF/Iris 格式解析     shaders.properties / gbuffers_* / composite* / block.properties …
  ├── ② pass 编排            shadow → gbuffers → deferred → composite → final 的帧图顺序
  ├── ③ GLSL 转译            OF 方言 → 原版编译通道能吃的形式（#include / const 选项 / 内建 uniform）
  └── ④ 选项 GUI             把 pack 自己声明的选项渲染成可调界面
                ↓
        原版 RenderPipeline / BindGroupLayout / GpuDevice（官方 API）+ 少量管线装配层 mixin
                ↓
        原版 Vulkan 后端（不碰）
```

### 5.1 🔴 为什么必须开 mixin（2026-10-02 源码级核实，已成定案）

零 mixin 路线**做不到**支柱①「完整兼容」。三处硬缺口：

| 缺口 | 事实 | 零 mixin 能否解决 |
|---|---|---|
| **改不了原版管线的实际使用** | `RenderPipelines.registerCustomPipelines` 用 `putIfAbsent`，重复注册抛 `IllegalStateException` ⇒ 只能**新增** location，改不了 `pipeline/solid_terrain` | ❌ |
| **零成本增不了附件** | `SOLID_TERRAIN` 等全部写死 `ColorTargetState.DEFAULT`（单附件 RGBA8）；派生管线能改附件数，但要**被地形用到**必须走 draw 侧注入点 | ❌ |
| **加不了自定义 uniform 块** | bind group 布局在 `RenderPipeline` 构造时固化；`BindGroupLayouts.Globals` 仅 9 字段（无相机矩阵/太阳方向） | ❌ |

⇒ **完整 gbuffer 管线（多附件 + 自定义 uniform）必须放开 mixin。这是技术结论，
而用户 2026-10-02 已拍板「略放开 mixin 限制」** ⇒ `07-CONSTRAINTS.md` M1 已松绑到
「管线装配层 + 登记制 + 可关闭制」，仍然禁止注入 Sodium / 底层 GL 状态类 / 第三方渲染器。

### 5.2 零 mixin 仍然可用的两条路（继续保留，成本低收益高）

1. **资源包覆盖机制**（已验证成立，2026-10-02）：
   原版 program 的源文本 = 资源包文件（`ShaderManager.loadConfigs` 走
   `ResourceManager.listResources("shaders", …)`）。覆盖同名资源即可换掉任意原版 program 源码，
   零 mixin、零插 pass、零换 RenderPipeline。
2. **官方事件帧注入**：`RenderLevelStageEvent.AfterLevel`（菜单态不触发，适合全屏 pass）。

⇒ **两条与 mixin 并存，不是二选一。** 能用它们的地方就用，它们是零风险选项。

### 5.3 每一部分都要先找参考

**任何模块开工前先调研，写下「参考了什么 / 为什么不直接用 / 我们的差异点 / 许可证核对」**
（`17-NATIVE.md` §1）。默认参考清单：

| 模块 | 首选参考 |
|---|---|
| GLSL 预处理器 | **IrisShaders/glsl-preprocessor**（⚠️ GPL+例外 → 只读思路） |
| GLSL 转译 / AST | **IrisShaders/glsl-transformer**（⚠️ 自定义传染 → 只读思路） |
| OF 格式语义 | **Iris** 的 `shaderpack/parsing/`（格式规范是事实性信息） |
| OF pass 顺序 / 缓冲语义 | shaderlabs wiki「Rendering Pipeline」+ Iris 官方 docs |
| 帧图 / 注入点 | Sulkan（GPL）/ Vitrail（LGPL）→ 只读思路 |
| 管线挂载模式 | VulkanMod（LGPL）→ 只读思路 |

> **Iris 是全世界唯一成熟的 OF 格式实现，它的解析器就是格式的事实标准。**

---

## 6. 版本基线

| 项 | 值 |
|---|---|
| 支持范围 | Minecraft **26.3 及之后**发布的版本 |
| 当前主线 | **26.3** |
| 不支持 | **26.2 及之前**（那代没有 `renderpearl.backend.api`） |
| 锁定 | MC 26.3 / NeoForge 26.3.0.23-beta / Java 25 / MDG 2.0.147 |

> **权威文档：`05-VERSION.md`。** 任何版本相关表述与它冲突时以它为准。

---

## 7. 路径热度与实现语言（2026-10-02 重标）

| 模块 | 热度 | 影响的指标 | 实现语言 |
|---|---|---|---|
| ① 格式解析 | ❄️ 冷 | **B3 冷路径 / B4 切包** | 纯 Java（Rust 为**实测候选**） |
| ② pass 编排（规划） | ❄️ 冷 | B4 | 纯 Java |
| ② pass 编排（每帧执行） | 🔥 **热** | **B1/B2 帧时间** | 纯 Java（原生需先证明是瓶颈） |
| ③ GLSL 预处理 + 转译 | ❄️ 冷 | **B3 冷路径 / B4 切包** | 纯 Java（Rust 为**实测候选**） |
| ④ 选项 GUI | ❄️ 冷 | 无 | 纯 Java |
| 顶点/UBO/管线键等**热路径工具** | 🔥 **热** | **B1/B2** | 纯 Java |

> 🔴 **本版修正了旧版的推理错误**。旧版写「解析/预处理/转译是冷路径，
> 用 Rust 重写帧率收益为零 ⇒ 禁止上原生」。这个结论**只对了一半**：
>
> - 帧率收益确实为零 —— 冷路径在切包时一次跑完，与每帧无关。
> - **但冷路径决定的是「切包等多久」** —— 这是用户唯一能明确感知的性能指标。
> - 现状：BSL 冷路径已到 **1861ms**（P4.5 优化后），更大包必然超标。
>
> ⇒ 正确表述：**「Rust 不能改善帧率；Rust 可能改善切包等待。是否值得做，由实测决定。」**
> 详见 `17-NATIVE.md` §5 的 G 系列闸门。

---

## 8. 边界

**做**
- OF/Iris 格式包的解析、编排、转译、GUI。
- 所有 GPU 操作走原版 `com.mojang.renderpearl.*`（经 `bridge/`）。
- **管线装配层 mixin**：为把派生管线（多附件 / 自定义 uniform）接到地形、实体、天空的 draw 上。
- 原版 Vulkan 暂不支持、但 OF/Iris 语义必需的特性，**可以自行补充**（`12-GAP-STRATEGY.md`）。
- **性能**：以 `17-NATIVE.md` §2.2 的 B1–B7 为准。
- **Rust vs Java 实测**：本轮范围 = GLSL 预处理与转译 + pack 解析，走 G 系列闸门。

**不做**
- ❌ 不写 Vulkan 设备 / 命令缓冲 / render pass（官方有）
- ❌ 不写 SPIR-V 编译器（走原版通道）
- ❌ **不与任何第三方渲染模组做集成**（不依赖、不替代、不容忍「假 XX」方案，`07` L11）
- ❌ 不碰 Vitrail（两者可共存，互不干扰）
- ❌ 不抄 GPL-3.0（Sulkan）与 ARR（Beryl）的任何代码
- ❌ **不注入 Sodium / 底层 GL 状态类 / 第三方区块渲染器**（M1 永久禁止项）
- ❌ 不因为「原生更快」就跳过 G 系列实测（X17）
- ❌ 不因为「公开基准快 30×」就直接选型（X32）
- ❌ 不因为「性能」而砍掉 pack 特性（X27）

---

## 9. 两条前置纪律（每一部分都适用）

| 纪律 | 含义 | 落地 |
|---|---|---|
| **参考先行** | 任何模块开工前先调研社区成熟实现，写下「参考了什么/为什么不直接用」 | `17-NATIVE.md` §1；实现文件头部【参考调研】注释块 |
| **先测后优** | 先跑通、先测量，超预算才优化；优化只做超预算的环节 | `17-NATIVE.md` §2–§3、§5 |

> 🔴 **「参考先行」的第 0 步是核许可证，不是读代码。**
> 先判「这份参考能不能合法用在我的 MIT 工程里」，判不过就换参考、**不再看它的代码**。
> 一旦先读了实现，就无法自证"没受影响"（`07-CONSTRAINTS.md` L12 / `17-NATIVE.md` §1.1.1）。

> **顺序不可颠倒：合规 → 参考 → 测量 → 优化。**

---

## 10. 成功标准（分级，2026-10-02 修订）

| 级别 | 标准 | 验证 |
|---|---|---|
| **最小可用** | 空模组能在屏幕上画出由自定义 `RenderPipeline` 产出的图案 | 截图 |
| **链路通** | 能识别并加载一个真实 pack，`#include` 能解，composite 有效果 | 截图 + 日志 |
| **编译全通** | 主流包全部 program 编译成功（BSL 190/190 已达成） | 日志 `stages=190 ok=190 failed=0` |
| **🔴 渲染全通**（新） | **主流包主要效果真实可见**：gbuffer 多附件语义正确、deferred/composite 链按序生效、水/天空/实体/阴影各走对的 program | 与 Iris 同场景对比截图 + 逐 pass 调试视图 |
| **完整** | 主流 pack 全部 program 可用 + 选项 GUI 可用 + 切包无闪烁无残留 | 多包回归表 |
| **性能达标** | B1 ≤ +2%；B3 ≤ 1s；B4 ≤ 2s；开包 ≤ Iris+OF 的 110% | `17-NATIVE.md` §7.3 基线表 |

> ⚠️ **「渲染全通」是本版新增的一级**，因为旧版把「编译全通」误当成了「渲染达意」。
> 2026-10-02 复核推翻了旧结论：**编译通过 ≠ 渲染正确** —— 182 个 program 只接线 3 个、
> 18 个 sampler 有 17 个绑到同一张图，这些都不影响 `ok=190`，但画面是错的。
> **教训：luma 量化只能证明「画出来了」，证明不了「画对了」。**

---

## 11. 一页流程图

```
Minecraft 26.3
  └── Renderpearl（原版渲染层）
        ├── renderpearl.api.*           ← 前端 API（管线的公开门面）
        ├── renderpearl.backend.api.*   ← ★ 后端 SPI（官方插口）
        └── renderpearl.backend.vulkan.*← 原版 Vulkan 实现（不碰）

vkdisp（本项目）
  ├── pack/     解析 OF/Iris 格式        ❄️ 冷（B3/B4）· Java，Rust 实测候选
  ├── glsl/     8 段转译流水线           ❄️ 冷（B3/B4）· Java，Rust 实测候选
  ├── pipeline/ 用原版 Builder 注册管线    ❄️构建 + 🔥键查找
  ├── render/   编排帧图（🔥 热，B1/B2）
  ├── mixin/    管线装配层注入（登记制）   🟡 受限开放
  ├── accel/    加速层门面（仅 G 系列裁决「采用」后才建）
  ├── config/ screen/  选项与 GUI         ❄️ 冷
  └── bridge/   ← 所有原版渲染 API 的调用都收在这里（升级时只改这一处）
```