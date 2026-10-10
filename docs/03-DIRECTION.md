# 03 · 方向变更与参考模组借鉴分析

> 状态：**新方向总纲，以本文为准。** 旧方向（改写第三方渲染器）的文档已于 2026-09-29 全部删除。
> 日期：2026-09-29
> 配套：`02-OVERVIEW.md`（定位）、`04-SPEC.md`（组件）、`05-VERSION.md`（版本权威）
> 结论摘要：**方向已从"改写第三方渲染器"整体切换为"基于原版 Vulkan 后端 + 自研 OptiFine/Iris 格式着色器引擎"，法律风险基本清零，且技术路径已被两个参考模组验证。**

---

## 0. 一句话结论

新方向 **可行，且比旧方向好得多**。两个参考模组里的借鉴价值如下：

| 参考模组 | 许可证 | 借鉴方式 | 价值 |
|---|---|---|---|
| **VulkanMod** | LGPL-3.0 | **只能读，不能抄**（本项目是 MIT，LGPL 不同族，见 `07-CONSTRAINTS.md` §〇） | ⭐⭐⭐⭐⭐ 最高：它验证了"模组自己当渲染后端"整条路的可行性，其挂载模式与格式表是**思路**上的最佳范本 |
| **Sulkan (sulkanShaders)** | GPL-3.0 | **只能读，不能抄**（GPL 传染，抄了你的项目必须整体 GPL 开源） | ⭐⭐⭐⭐ 高：它验证了"在原版 Vulkan 上注册自定义 RenderPipeline"的具体做法，是**思路**上的最佳范本 |
| **Beryl** | ARR（闭源，作者同 xCollateral） | 不可借鉴代码 | ⭐⭐ 参考：它是 VulkanMod 的着色器管线，证明这条路有商业/闭源可行性 |

> ⚠️ **共同结论**：这三个参考模组**没有一个的代码能用**（它们分别是 LGPL / GPL / ARR，与 MIT 不同族）。
> 它们的价值全部是"证明这条路能走通"+"告诉我们该往哪个方法打洞"。
> 🔖 **2026-10-10 更正**：本行旧文案写「本项目 MIT，100% 自研代码」—— 那是早期文档自己加的限制，
> **不是 MIT 的要求**。准确表述：**代码 100% 来自 MIT/Apache-2.0/BSD 族或我们自己**，
> 并保留上游署名与改动声明（`07-CONSTRAINTS.md` §〇 / §1.3；已裁决实例 = `19-IMPROVEMENT-PATHS.md` §7-1 的 jcpp）。
> ⛔ LGPL / GPL / ARR 的边界**没有放宽**。

**关键事实：Sulkan 和 VulkanMod 都完全不支持 OptiFine/Iris 格式着色器包。** 这正是新方向真正的技术空白与价值所在——两个参考模组都只做"自带内嵌着色器/自研管线"，没有人做"兼容 OF/Iris 包的加载器"。这部分必须自研，没有轮子可造。

---

## 1. 最重要的一条技术发现（推翻旧计划的根基）

### 1.1 原版 26.3 自带一个**渲染后端抽象层**

从 `vitrail-0.12.0-beta+mc26.3.jar` 的常量池里提取出原版 26.3 渲染 API 的真实类型清单（共 85 个 `com.mojang.*` 渲染类型，其中 69 个已迁入 Renderpearl）：

```
com.mojang.renderpearl.api.*          ← 前端 API（GpuDevice / CommandEncoder / RenderPipeline …）
com.mojang.renderpearl.backend.api.*  ← ★ 后端 SPI 抽象层
      GpuDeviceBackend
      CommandEncoderBackend
      BackendRenderPipeline / BackendRenderPipeline$CreateInfo
      SpvModule / SpvModule$Reflection / $Descriptor / $InterfaceVariable
com.mojang.renderpearl.backend.vulkan.*  ← Vulkan 实现
      VulkanDevice / VulkanCommandEncoder / VulkanRenderPipeline / VulkanRenderPass
      VulkanFeatureSets / VulkanFeature / VulkanPNextStruct
com.mojang.renderpearl.frontend.*        ← FrontendRenderPipeline / shaders.SPIRVModule
```

**这意味着什么**：Mojang 在 26.3 里已经把"渲染前端"与"后端实现"拆开了，`com.mojang.renderpearl.backend.api` 就是**官方的后端插口**。这正是旧计划里"想去用官方实现但找不到入口"的那个入口——**它一直都在，只是旧计划的搜索范围锁死在第三方渲染模组上，没看原版**。

### 1.2 后果：新方向的正确姿势

```
┌─────────────────────────────────────────────────────────┐
│  你的模组：OptiFine/Iris 格式着色器引擎（自研）              │
│  ① 解析 OF 格式包 → ② 生成 RenderPipeline 描述              │
└───────────────────────┬─────────────────────────────────┘
                        │ 用官方前端 API 注册
┌───────────────────────▼─────────────────────────────────┐
│  com.mojang.renderpearl.api.*  （原版前端 API）            │
│  RenderPipeline.builder() / BindGroupLayout / VertexFormat │
└───────────────────────┬─────────────────────────────────┘
                        │ 原版自己分发
┌───────────────────────▼─────────────────────────────────┐
│  com.mojang.renderpearl.backend.vulkan.*  （原版 Vulkan）  │
│  VulkanDevice / CommandEncoder / VulkanRenderPipeline      │
└─────────────────────────────────────────────────────────┘
```

**不碰任何第三方渲染模组，不碰 Vitrail，不自己写 Vulkan 设备。** 只写"OF 格式 → RenderPipeline"这一层的翻译器 + 帧图编排。

---

## 2. 参考模组逐一分析

### 2.1 VulkanMod（LGPL-3.0）— 参考价值最高，但**代码不可用**

**它做了什么**：`build.gradle` 里 `include()` 打包 LWJGL 的 `lwjgl-vulkan` / `lwjgl-vma` / `lwjgl-shaderc` / `lwjgl-spvc`，**自己建了完整的 Vulkan 设备、内存分配器、交换链、命令缓冲、描述符集**，然后通过 mixin 把原版的 `RenderSystem` / `GlStateManager` / `GL11/GL14/GL15/GL30` 全部接管。

**可借鉴的具体资产**（⚠️ **只能读、只能抄"做法"，不能搬代码** —— 本项目 MIT，VulkanMod 是 LGPL-3.0，见 `07-CONSTRAINTS.md` §〇）：

| 资产 | 位置 | 借鉴方式 |
|---|---|---|
| **`gl/` 包** — GL API 的 Vulkan 垫片 | `net/vulkanmod/gl/VkGlBuffer`、`VkGlProgram`、`VkGlTexture`、`VkGlFramebuffer`、`VkGlShader`、`GlUtil` | ❌ **不借鉴**：这是"把 GL 调用翻译成 Vulkan"的完整实现，属于表达性代码；且 26.3 原版已是 Vulkan 后端，大部分工作原版已做完 |
| **`ExtendedRenderPipeline` 模式** | `interfaces/shader/ExtendedRenderPipeline.java` | ⭐⭐⭐⭐⭐ **最高价值的模式**：给原版 `RenderPipeline` 挂 mixin 实现自定义接口，往里塞自己的 pipeline 对象。**照这个"做法"自己从零写**（例如 `ExtendedVkdispPipeline`） |
| **`ShaderManagerM` 注入点** | `mixin/render/shader/ShaderManagerM.java` | ⭐⭐⭐⭐⭐ 它 `@Inject` 到 `ShaderManager.apply(...)` 的 `List.isEmpty()` 调用点，拿 `@Local CompilationCache`，再 `gpuDevice.precompilePipeline(pipeline, cache::getShaderSource)` —— **记住"注入点选在哪"这个事实，代码自己写** |
| **`VkGlProgram` 的 ID 映射表** | `gl/VkGlProgram.java` | ⭐⭐⭐ 思路：用 `Int2ReferenceOpenHashMap` 做整数 id → Pipeline 映射。**"GL program id 需要兼容层"这个结论有用，实现自己写** |
| **`GlUtil.vulkanFormat` 格式映射表** | `gl/GlUtil.java` | ⭐⭐⭐ 思路：GL 格式 → `VK_FORMAT_*` 需要一张表。**表要自己按 Vulkan spec 重建**（`GL_*` 常量是公开规范，不受版权保护） |
| **`SpirvCompiler` + `shader/converter/`** | `vulkan/shader/SpirvCompiler.java`、`converter/SpirvPipeline.java`、`SpirvShader.java` | ⭐⭐ 不做。本项目 **T4：不得自研 SPIR-V 编译器**，走原版编译通道 |
| **`vulkan/shader/layout/`** | `AlignedStruct`、`PushConstants`、`Uniform`、`Mat3`、`Vec1f`、`Vec1i` | ⭐⭐ 思路：std140/std430 对齐需要工具类。**对齐规则是 spec 公开内容，自己按 spec 写** |

**关键限制（务必注意）**：
- 它编译目标是 **MC 1.21.11 / Java 21 / yarn mappings / `com.mojang.blaze3d.*`**，新方向是 **26.3 / Java 25 / 官方 mappings / `com.mojang.renderpearl.*`**。**类名和包名全变了**（`blaze3d.* → renderpearl.*`）—— 但**因为不移植代码，任何映射表只作理解用，不是移植依据**。
- 它 **完全不支持 OptiFine 格式**。它的着色器是自己写的、放在 `assets/vulkanmod/shaders/` 里的固定管线（`PipelineManager` 里硬编码 `terrain` / `terrain_earlyZ` / `blit` / `clouds` 四条），走的是自己的 JSON 配置格式，跟 OF 的 `shaders.properties`/`gbuffers_*` 毫无关系。
- **它的 `gl/` 包的意义在 26.3 已经大幅下降**：26.3 的原版已经是 Vulkan 后端，`RenderSystem` 下面接的是 `renderpearl.backend.vulkan`。所以 VulkanMod 那套"用 Vulkan 假装 GL"的工作，**在新方向上大部分不需要做了** —— 原版已经替你做完了。真正有参考价值的只是它那两三个"挂载模式"（**做法**，不是代码）。
- 🔴 **license 边界（本项目的红线）**：VulkanMod 是 **LGPL-3.0**，本项目是 **MIT**，两者不同族。**不得复制它的任何一行代码**（`07-CONSTRAINTS.md` §〇 P1 / L7）。可以带走的是"给 `RenderPipeline` 挂 mixin 扩展接口""`ShaderManager.apply` 里有个可注入的编译点"这类**事实性结论**。

### 2.2 Sulkan（GPL-3.0）— 思路范本，但代码不能用

**它做了什么**：Fabric 客户端模组，48 个 Java 文件 / 约 6229 行。构建在原版 Vulkan 渲染器之上，提供自带的着色效果（影子、AO、水、体积云、大气、Bloom、FXAA），并支持**自带格式**的"着色器包"。

**它的贡献（纯思路，因为 GPL 不能抄）**：

| 技术点 | 位置 | 说明 |
|---|---|---|
| **在原版 Vulkan 设备上插入自定义 pipeline 编译** | `mixin/VulkanDeviceShaderCompilerMixin.java` | ⭐⭐⭐⭐⭐ 最核心的借鉴点。它 `@Inject` 到 `VulkanDevice.getOrCompilePipeline` 的 HEAD，命名空间是 `sulkan` 的就自己接管：用 shaderc 编 SPIR-V → 走原版 `GlslCompiler` 做反射 → `VulkanRenderPipeline.compile(...)`。**这段证明了"第三方可以往原版 Vulkan 管线里塞自己的着色器"，是整条新方向成立的技术证据** |
| **用官方 Builder 注册 pipeline** | `runtime/ShaderPipelines.java` | ⭐⭐⭐⭐⭐ 全部用 `RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET).withBindGroupLayout(BindGroupLayout.builder()...)` 构建。**这就是新方向要用 API 的活样板** |
| **帧图插入** | `mixin/LevelRendererPostMixin.java`、`LevelRendererShadowMixin.java` | ⭐⭐⭐⭐ `builder.addPass("sulkan:directional_shadow_maps")` 往 `FrameGraphBuilder` 里插自己的 pass；`@Inject` 到 `LevelRenderer.render` 的 `submitFeatures` 调用点与 RETURN。**OF 格式的 shadow/composite/deferred 阶段就要这么挂** |
| **原版类路径着色器加载 + `#include` 预处理** | `VulkanDeviceShaderCompilerMixin.sulkan$classpathShader()` | ⭐⭐⭐⭐ 自己实现 `GlslPreprocessor.applyImport` 解 `namespace:path` 形式的 include。**OF 的 `#include` 指令要的就是这个** |
| **资源包打包技巧** | `pack/ShaderPackRepository.java` | ⭐⭐⭐⭐ 把用户的 zip 解包到 `resourcepacks/sulkan_shaderpack_active/`，自动写 `pack.mcmeta`，再把该包 ID 塞进 `options.resourcePacks`。**这是"让原版资源系统加载我的着色器"的干净做法** |
| **`@Mixin(value=..., remap=false)` 注入第三方区块渲染器内部** | `mixin/sodium/*.java` | ⭐ 已判定**不用**：本项目**不与任何第三方渲染模组做集成**（`07-CONSTRAINTS.md` L11）。仅作为"第三方能注入到什么深度"的事实记录 |

**为什么不能用它的代码**：GPL-3.0 是强传染许可证。你的项目若包含任何 Sulkan 代码，整个项目必须以 GPL-3.0 开源分发。本项目已是 **MIT**，**一行都不能抄**（`07-CONSTRAINTS.md` §〇 P1 / L5）。可以带走的只有"它验证过这条路能走通"这个事实和它的架构分层。

### 2.3 Beryl（ARR）— 纯参考

`xCollateral` 给自己的 VulkanMod 写的着色器管线，**闭源（All Rights Reserved）**。README 明确写着"currently beryl has an integrated shader pack, as that allows to have full control over the rendering pipeline"——**它也是内嵌管线，不接受外部 OF/Iris 包**。只用于确认"VulkanMod 路线上做着色器是有人付费支持的"。

---

## 3. 新方向的完整技术地图

### 3.1 三件事要做

**A. 后端不自己写。** 用原版 `com.mojang.renderpearl.backend.vulkan`。26.3 的原版 Vulkan 后端就是你的后端。

**B. OF/Iris 格式解析器要自己写。** 这是**全项目最大的工作量，也是唯一真正的护城河**。没有任何参考模组做过：
- `shaders.properties` 解析（选项、开关、profiles）
- `gbuffers_*.vsh/.fsh` 阶段识别（basic/terrain/water/entities/…）
- `composite*.fsh` / `deferred*.fsh` 后处理链编排
- `dimension.properties` / `world0/1/-1/end/` 维度分支
- `block.properties` / `item.properties` 的 ID→图层映射
- `#include` / `#define` 宏系统、`const` 常量、`uniform` 语义
- OF 特有的内建 uniform（`gbufferModelView`、`sunPosition`、`frameTimeCounter`、`cameraPosition`…）

**C. 帧图插入要自己编排。** 用 `FrameGraphBuilder.addPass` + `@Inject` 到 `LevelRenderer` 的各阶段。Sulkan 提供了模板，但要按 OF 的 pass 语义重写。

### 3.2 复用原版 GLSL→SPIR-V 通道（关键判断）

原版 26.3 已经能编译 GLSL 到 SPIR-V（否则原版自己没法跑）。两条路：

| 方案 | 做法 | 评价 |
|---|---|---|
| **首选**：走原版编译通道 | 让着色器位于 `assets/<ns>/shaders/core/...`，用 `Identifier` 引用，让原版 `VulkanDevice.getOrCompilePipeline` 自己编 | ✅ 不额外打包 shaderc，跨平台（含 MoltenVK）由原版保证，维护成本最低 |
| **备选**：自带 shaderc | 像 Sulkan 一样 `Shaderc.shaderc_compile_into_spv` | ⚠️ 需要 `lwjgl-shaderc` + 三平台 natives，还要自己处理原版的 geometry 接口重写（Sulkan 的注释里明确提到 MoltenVK 上的坑） |

**结论：优先走原版通道。** OF 的 GLSL 与 M 原版 GLSL 不完全一致（OF 有 `attribute`/`varying` 老式语法、`gl_` 内建差异），需要一层**源码转译**，但转译出来的结果仍交给原版编译器。

### 3.3 区块地形性能：走原版，不接任何第三方渲染模组

地形渲染**只走原版 `SectionRenderDispatcher`**。本项目**不与任何第三方渲染模组做集成**，
不检测、不兼容、不复用任何外部区块渲染器。

**没有"可选集成"这条路**（曾经的 Phase 4 可选项已取消，见 `07-CONSTRAINTS.md` L11）。
地形性能若不足，走 `17-NATIVE.md` 的流程定位，而不是引外部依赖。

---

## 4. 许可证策略（对比旧方向）

| 项目 | 旧方向（改写别人的渲染器） | 新方向 |
|---|---|---|
| PolyForm Shield 类非竞争许可 | 🔴 直接踩线 | 🟢 **零接触**，合规风险清零（本项目对外只有一句"独立实现"） |
| 与 Vitrail 的关系 | 需要上游配合改代码 | 🟢 **完全不碰**，两个模组可共存 |
| 借鉴 VulkanMod (LGPL) | — | 🔴 **只能读思路，不能移植代码**（MIT 与 LGPL 不同族） |
| 借鉴 Sulkan (GPL) | — | 🔴 **只能读思路，不能抄代码** |
| 借鉴 Beryl (ARR) | — | 🔴 完全不可 |
| 本项目许可证 | 被迫纠结 | ✅ **已定：MIT**（`LICENSE` + `gradle.properties` 的 `mod_license=MIT`） |

**红线**：不要抄 Sulkan 的 `.java`、不要抄它的 `.glsl`、不要抄它的资源文件。**VulkanMod 同理**（LGPL 也不能并入 MIT 工程）。只借鉴"架构分层"和"注入点位置"这两个不构成表达的事实。

---

## 5. 新方向的分阶段计划

> **版本基线（已明确）**：支持范围 = **MC 26.3 及之后发布的新版本**；**当前主线锁 26.3**。
> 不支持 26.2 及之前。一切开发、验证、验收以 26.3 为准。详见 `05-VERSION.md`。

### Phase 0 — 验证（1 个最小 E2E）

- 空模组 + mixin 配置（**`compatibilityLevel` 必须 `JAVA_25`**）
- 用 `RenderPipeline.builder()` 注册一个全屏 pass，跑通"自定义着色器出现在屏幕上"
- 验收：屏幕上出现一张自定义纯色/棋盘图
- **这一步决定了整条路是否成立，务必最先做**
- **前置动作**：先落实 `06-MIGRATION.md` §2 的 `bridge` 包隔离设计——
  虽然现在只有一个 pass，但要让"原版 API 访问集中"从第一天就成立

### Phase 1 — 后处理链

- 拦截主渲染目标，插一个 composite pass
- 自己实现一个可配置的 tonemap，验证 uniform 传递
- 验收：能开关、能传参数

### Phase 2 — OF 格式最小解析器

- 只支持 `shaders.properties` 的 `screen`/`sliders` 子集 + `composite.fsh` + `#include`
- 加载一个真实 OF 包的 composite 阶段（不要求全功能）
- 验收：一个真包能加载不崩、composite 有效果
- **注意**：解析层不得依赖任何原版类型（否则版本升级会波及解析器）

### Phase 3 — 完整 pass 编排

- 影子贴图（`shadow.vsh/fsh`，方向光 + 级联）
- gbuffers 阶段（terrain/water/entities）
- deferred 链
- 验收：跑通一个中等复杂度 OF 包

### Phase 4 — 兼容性与打磨

- dimension 分支、block/item properties、选项 GUI、profiles
- 验收：主流包（Complementary / BSL / Sildur）基本可用

### Phase 5 — 版本跟进（持续）

- 26.4 / 26.5 … 发布后按 `06-MIGRATION.md` §4 的流程升级
- 每次升级跑 §5 回归清单，回填 §6 迁移日志

---

## 6. 需要你拍板的

1. ~~**许可证选 LGPL-3.0 还是 MIT？**~~ ✅ **已定（2026-09-29）：MIT。**
   已写入工程根 `LICENSE` 与 `gradle.properties` 的 `mod_license=MIT`。
   后果：**不得并入任何 LGPL / GPL / ARR 代码**，VulkanMod 只能读思路不能搬代码。
   三条硬约束见 `07-CONSTRAINTS.md` §〇（P1 / P2 / P3）。

2. ~~是否仍要在 26.3 上继续？~~ **已定（2026-09-29）**：
   支持 **26.3 及之后**的新版本，当前主线锁 **26.3**，不支持 26.2 及之前。见 `05-VERSION.md`。

3. ~~**是否保留对 Sodium 的可选增强？**~~ ✅ **已定（2026-09-29）：不保留，彻底隔绝。**
   不与任何第三方渲染模组做集成（见 `07-CONSTRAINTS.md` L11）。

4. ~~**旧 `Shader-Core-Vulkan/` 目录怎么处理？**~~ ✅ **已决并执行**：
   旧内容已清空，换成官方 NeoForge 26.3 MDK。

---

## 附录 A：证据清单

| 结论 | 证据来源 |
|---|---|
| 原版有后端 SPI 抽象层 | `vitrail-0.12.0-beta+mc26.3.jar` 常量池含 `com.mojang.renderpearl.backend.api.{GpuDeviceBackend,CommandEncoderBackend,BackendRenderPipeline,SpvModule}` |
| 原版 Vulkan 后端类名 | 同上，`com.mojang.renderpearl.backend.vulkan.{VulkanDevice,VulkanCommandEncoder,VulkanRenderPipeline,VulkanRenderPass,VulkanFeatureSets}` |
| 第三方可插入 pipeline 编译 | Sulkan `VulkanDeviceShaderCompilerMixin`（`@Inject` `.getOrCompilePipeline` HEAD 成功） |
| 可用官方 Builder 注册 pipeline | Sulkan `runtime/ShaderPipelines.java` 全部走 `RenderPipeline.builder(...)` |
| 可往帧图插 pass | Sulkan `LevelRendererShadowMixin`：`builder.addPass("sulkan:directional_shadow_maps")` |
| Sulkan 无 OF 格式支持 | 全仓库 grep `optifine\|gbuffers\|shaders.properties\|block.properties` 结果为空 |
| VulkanMod 无 OF 格式支持 | 全仓库 grep 同上，仅命中 3 个无关文件 |
| Sulkan = GPL-3.0 | `LICENSE` 头 `GNU GENERAL PUBLIC LICENSE Version 3`；`fabric.mod.json` `"license": "GPL-3.0-only"` |
| VulkanMod = LGPL-3.0 | `LICENSE` 头 `GNU LESSER GENERAL PUBLIC LICENSE Version 3` |
| Beryl = ARR | Modrinth 页面 `Licensed ARR` |
| VulkanMod 自带设备层 | `build.gradle` `include(implementation("org.lwjgl:lwjgl-vulkan"))` + `lwjgl-vma`/`lwjgl-shaderc`/`lwjgl-spvc` 三平台 natives |
| VulkanMod 目标版本旧 | `gradle.properties` `minecraft_version = 1.21.11`, `options.release = 21` |
| Sulkan 目标版本新 | `gradle.properties` `minecraft_version=26.2`, `options.release = 25` |
| Vitrail 重度依赖第三方区块渲染器 | 770 个类中 23 个引用 `caffeinemc`（3%），集中在 `mixin/sodium/*` 与 `sodium/*` —— **旧方向不可行，新方向绕开它是对的** |

## 附录 B：类型对应速查（VulkanMod 1.21.11 → 本项目 26.3）

> ⚠️ **用途仅限"理解与定位"**。本项目 MIT，**不得移植 VulkanMod 代码**（`07-CONSTRAINTS.md` §〇）。
> 此表用来回答"它的这个东西相当于我们这边的什么"，不是移植依据。
> 重映射表按需自建，不在本项目仓库内维护。

| VulkanMod 类 | 1.21.11 类型 | 26.3 对应类型 | 说明 |
|---|---|---|---|
| `interfaces/shader/ExtendedRenderPipeline` | `com.mojang.blaze3d.pipeline.RenderPipeline` | `com.mojang.renderpearl.api.pipeline.RenderPipeline` | **模式可学**：给原版 pipeline 挂 mixin 扩展接口，自行实现 |
| `mixin/render/shader/ShaderManagerM` | `ShaderManager.apply(Configs,ResourceManager,ProfilerFiller)` | 需重新定位签名 | **注入点可学**：26.x 签名已变，注入点要自己重找 |
| `gl/VkGlProgram` | — | — | ❌ 不搬；26.3 原版已有对应物 |
| `gl/GlUtil.vulkanFormat` | `GL11/GL30` 常量 | — | ❌ 不搬；表的**必要性**可学，内容按 Vulkan spec 自建 |
| `gl/VkGlBuffer/Texture/Framebuffer` | — | 原版已有 `renderpearl` 对应物 | ❌ 不搬；原版已覆盖 |
| `vulkan/shader/layout/*` | — | — | ❌ 不搬；对齐规则按 spec 自写 |
| `vulkan/shader/SpirvCompiler` | lwjgl-shaderc | 原版自带编译 | ❌ 不搬；本项目 T4 禁止自研编译通道 |
| `vulkan/Vulkan`（设备创建） | — | 原版 `VulkanDevice` | ❌ 完全不需要，这正是新方向的收益 |
