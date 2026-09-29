# Shader-Core-Vulkan 开发计划（合规版 v2）

> **v2 变更说明**：v1 只用「GPL 不能抄」一句带过许可证问题，属于严重失职。核实 Sodium 仓库后确认：Sodium 用的是 **PolyForm Shield 1.0.0**，其 **Noncompete（禁止竞争）条款**直接命中本项目最核心的设计思路。v2 把合规分析提升为全文前提，并据此重做了架构决策。

---

## 0. 结论先行（含合规红线）

**技术结论不变**：Shader-Core-Vulkan 现在是个近乎空的骨架，正确路线是「假 Sodium」兼容层 + 自研区块网格化/剔除 + 官方 Renderpearl 出 GPU 调用。

**合规结论是新增的、且优先级更高**：**「假 Sodium」这条路本身有法律风险。** Sodium 的 PolyForm Shield 1.0.0 明确禁止「提供与 Sodium 竞争的产品」——而一个以 `modId="sodium"`、包名 `net.caffeinemc.mods.sodium.*` 完全一致的方式**顶替 Sodium 作为 Vitrail 前置**，正是最典型的「practical substitute（实用替代品）」形态。许可原文甚至写明：

> "If you market a product as a practical substitute for the software or another product, it definitely competes."
> （如果你把一个产品营销成该软件或另一产品的实用替代品，它**一定**构成竞争。）

**因此本计划给出三条路线，并推荐路线 C。** 详见第 2 节。

---

## 1. 现状核查：Shader-Core-Vulkan 现在有什么

### 1.1 文件清单（全部）

```
Shader-Core-Vulkan/
├── build.gradle                     # ModDevGradle 2.0.78 / Java 25 / compileOnly vitrail jar
├── settings.gradle                  # include 'shader-core-vulkan'（但该子目录无 build.gradle）
├── gradle.properties
├── vitrail-0.12.0-beta+mc26.3.jar   # 编译参考用的 Vitrail 成品
├── PLAN.md                          # 上一个 agent 写的自述（未验证，含违规指引）
├── BUGS_AND_FIXES.md                # 同上，声称修了 6 个 bug，实为虚构
├── verify.sh
├── src/main/resources/META-INF/neoforge.mods.toml
├── shader-core-vulkan/
│   ├── common/src/main/java/dev/shadercore/vulkan/
│   │   ├── VulkanCoreApi.java
│   │   ├── mixin/VulkanDeviceMixin.java
│   │   ├── mixin/VulkanRenderPipelineAccessor.java
│   │   └── render/VulkanRenderTarget.java
│   ├── common/src/main/resources/shader-core-vulkan.mixins.json
│   ├── neoforge/src/main/java/dev/shadercore/vulkan/neoforge/ShaderCoreVulkanNeoForge.java
│   └── placeholder.txt
└── build/libs/shader-core-vulkan-1.0.0+mc26.3.jar  ← 只有元数据，零 class
```

**已构建 jar 的实测内容**：
```
META-INF/MANIFEST.MF
shader-core-vulkan.mixins.json
META-INF/neoforge.mods.toml
```
→ **零个 `.class`**。原因：`settings.gradle` 里 `include 'shader-core-vulkan'` 指向的子目录**没有 build.gradle**；真正编译的根工程 sourceSets 里的 Java 全部编译报错，jar 里什么都没剩下。

### 1.2 虚构/错误代码逐条

| # | 位置 | 问题 | 处置 |
|---|---|---|---|
| 1 | `VulkanCoreApi.createShaderSource` | 引用**不存在的** `ShaderSource.CachedIncludeSource`；`getInclude(Identifier)` 签名错（26.3 真 API 收 `ResourceLocation`） | 整个类删掉 |
| 2 | `VulkanCoreApi.compilePipeline` | `device.compilePipeline(...).join().finishCompile()` 参数与返回类型均未验证，从未编译过 | 删掉，不要碰管线编译 |
| 3 | `VulkanRenderPipelineAccessor` | `@Accessor("WEATHER_SNIPPET")` —— `RenderPipelines` 没有这个字段 | 删掉 |
| 4 | `VulkanDeviceMixin` | `@Mixin(RenderSystem.class) @Accessor("DEVICE")` 字段名未验证 | 删掉 |
| 5 | `VulkanRenderTarget` | 用了未验证的 `TextureTarget(GpuFormat...)` 构造器 | 删掉 |
| 6 | `mods.toml` | `[[dependencies.shadercore-vulkan]]` 表名与 `modId="sodium"` 不一致 | 改 `[[dependencies.sodium]]`（若走路线 B） |
| 7 | `mixins.json` | `compatibilityLevel: JAVA_21` | 必须 `JAVA_25`（`JAVA_21` 在 Java 25 下**静默跳过** mixin） |
| 8 | `settings.gradle` | `include` 的子目录无 `build.gradle` | 单工程化或补齐 |
| 9 | `PLAN.md` | 收尾写「Modify Vitrail 的 modId」 | **违规且方向反了**，需删除该段 |

> `PLAN.md` / `BUGS_AND_FIXES.md` 里「改 Vitrail 的 `modId`」这条指引本身就是错的：那会直接修改 Vitrail 文件，违反用户约束。

### 1.3 已实现的部分（正面清单，仅 3 条）

| 项目 | 状态 |
|---|---|
| `mods.toml` 的 `displayTest="IGNORE_ALL_VERSION"` | ✅ 有用 |
| `build.gradle` 有 Java 25 toolchain | ✅ |
| 目录骨架 `{common,neoforge}` | ✅ 可复用 |

**其余没有任何一行可用或正确的代码。**

---

## 2. ⚖️ 合规分析（本版新增，优先级最高）

### 2.1 Sodium 的许可证原文（实测，2026-09-29 核对）

来源：`https://github.com/CaffeineMC/sodium` → `LICENSE.md`，README「📜 License」段：

> Except where otherwise stated (see third-party license notices), the content of this repository is provided under the **Polyform Shield 1.0.0** license by **JellySquid**.

MOD 页面（Modrinth / CurseForge）一致标注：**Licensed PolyForm Shield 1.0.0**。

### 2.2 PolyForm Shield 1.0.0 的关键条款

| 条款 | 原文要点 | 对本项目的含义 |
|---|---|---|
| **Noncompete（禁止竞争）** | "Any purpose is a permitted purpose, **except for providing any product that competes with the software** or any product the licensor provides using the software." | 不能做一个与 Sodium 竞争的产品 |
| **Competition（竞争的定义）** | "Goods and services compete even when they provide functionality through different kinds of interfaces or for different technical platforms… **even when provided free of charge**. If you **market a product as a practical substitute** for the software…, it definitely competes." | 「换接口/换平台/免费」都不豁免；**营销为实用替代品 = 一定竞争** |
| **Changes and New Works** | 允许改和衍生，**但仅限 permitted purpose** | 改 Sodium 源码同样受 Noncompete 限制 |
| **Distribution** | 允许分发，但须附许可全文与 `Required Notice` | 分发要带许可 |
| **New Products** | 若你已在不竞争地使用，之后上游推出竞争产品，你可继续用**当时已有版本**，不得用更晚版本 | 时序条款，不适用于本项目 |
| **Violations** | 收到书面通知后 32 天内完全合规可补救，否则许可立即终止 | 补救窗口有限 |

### 2.3 本项目命中的风险点（逐条对照）

| 本项目行为 | 是否命中 Noncompete | 说明 |
|---|---|---|
| 用 `modId="sodium"` 冒充，让 Vitrail 认它为前置 | ⚠️ **高风险** | 它在功能上**顶替** Sodium 的位置 |
| 复制 Sodium 的包名 `net.caffeinemc.mods.sodium.*` 与类名 | ⚠️ **中高风险** | 制造「同一产品」的观感；且属于对 Sodium 代码/接口的实质借用 |
| 照抄 Sodium 内部私有方法签名（`rotate`/`prepare`/`compileProgram` 等） | ⚠️ **中风险** | 签名本身更接近事实信息，但批量复刻内部结构可能被视为衍生 |
| 只做区块渲染，不做 Sodium 的全部优化特性 | ✅ 降低风险 | "does not compete" 的空间在于**定位与营销**，不在于功能多少 |
| 不营销为「Sodium 替代品」，而是「Vitrail 的 Vulkan 后端」 | ✅ 降低风险 | Competition 条款把「营销为替代品」列为决定性因素 |
| 什么都不做（保持现状） | ✅ 无风险 | —— |

> **关键判读**：PolyForm Shield 的 Noncompete 不是「不能看代码」，而是「不能提供竞争产品」。**「冒充 modId + 复刻包名」这个组合，正是最容易被认定为竞争的形态**——它同时满足「实用替代品」和「同一产品的另一份」。

### 2.4 三条路线（必须三选一，不能含糊）

#### 路线 A：继续「假 Sodium」全兼容（原 v1 方案）

| 项 | 评估 |
|---|---|
| 技术可行性 | 高（v1 已把契约摸清） |
| 合规风险 | **高**。Noncompete 很可能被认定违反 |
| 需要什么才能做 | 要么取得 CaffeineMC/JellySquid 的**书面许可**，要么改走路线 B/C |
| 结论 | ❌ **不推荐**，除非法务/作者明确放行 |

#### 路线 B：路径走不通的「官方 API 插件」

| 项 | 评估 |
|---|---|
| 思路 | 不冒充 modId，而是**作为 Sodium 的一个扩展**存在：依赖真 Sodium，通过 Sodium 公开的 `api` 包 + entrypoint 机制集成 |
| 合规风险 | **低**。官方 wiki 明确欢迎这种方式：「Sodium Config API lets mods add their own pages…」并发布了 `net.caffeinemc:sodium-neoforge-api` 制品 |
| 技术可行性 | **低到不可能**——因为 Vitrail 的 10 个注入目标**100% 落在 `client/` 内部**（`DefaultChunkRenderer.rotate` 等），而 API 包只提供 `blockentity/config/internal/math/memory/texture/util/vertex`，**不暴露这些内部** |
| 结论 | ⚠️ 合规但技术上**不可能满足 Vitrail 的零改动约束** |

> 这就是本项目真正的两难：**Vitrail 需要的是 Sodium 的内部，而 Sodium 的内部既不在公开 API 里，也不在许可允许的范围里。**

#### 路线 C（推荐）：改用「非竞争定位」的独立渲染后端 + 向 Vitrail 上游提案

| 项 | 评估 |
|---|---|
| 思路 | **不做 Sodium 的替代品**，而是做一个**独立的、面向 Vitrail 的 Vulkan 区块渲染核心**，用**自己的** modId（如 `shadercore-vulkan`）、**自己的**包名、**自己的**类名。不冒充、不复刻 Sodium 的 FQN |
| 合规风险 | **低**。不构成对 Sodium 的竞争——它不是 Sodium 的替代品，而是「Vulkan 渲染器的着色器支持层」 |
| 技术可行性 | 中——但**必须让 Vitrail 上游配合**（见下） |
| 与「不改 Vitrail」约束的冲突 | ⚠️ **这是路线 C 的代价**：既然不复刻 Sodium FQN，Vitrail 的 `@Mixin(value = DefaultChunkRenderer.class)` 就找不到目标，**必须改 Vitrail** |
| 结论 | ✅ **推荐**，但需要用户重新权衡「零改动」约束 |

### 2.5 三条路线的决策矩阵

| 维度 | A 假 Sodium | B 官方 API 插件 | C 独立后端（推荐） |
|---|---|---|---|
| 合规风险 | 🔴 高 | 🟢 低 | 🟢 低 |
| 满足「不改 Vitrail」 | ✅ 满足 | ✅ 满足 | ❌ **不满足** |
| 技术可行性 | 🟢 高 | 🔴 低 | 🟡 中（需上游配合） |
| 需要 Sodium 授权 | ❌ 很可能需要 | ✅ 不需要 | ✅ 不需要 |
| 能达成「Vitrail 在高版本跑起来」 | ✅ | ❌ | ✅（需改 Vitrail） |
| 工作量 | 大（复刻全部内部） | 小但走不通 | 中 |

> **建议**：先向 Vitrail 作者**提案路线 C**。Vitrail 是 LGPL-3.0 开源项目，它的 `why.md` 明确了「保持 OptiFine 格式、服务存量 pack」的定位——它**需要一个 Vulkan 后端**，这件事对上游是有价值的。做一个「Vitrail 的 Vulkan 后端」不构成对 Sodium 的竞争，是干净的。

### 2.6 无论走哪条路，都必须遵守的合规底线

1. **不复制 Sodium 的任何源代码**（任何一种路线下都应如此；即便 PolyForm 允许衍生，Noncompete 也不允许用它造竞争品）。
2. **不把「Sodium 替代品」写进任何宣传语、README、mod 描述、Discord 发言**。Competition 条款把营销定性列为决定性证据。
3. **需要参考 Sodium 时，只参考「事实性接口信息」**（方法名/签名/参数顺序），不搬运方法体、注释、注释风格、算法实现。
   - 更稳妥：**只参考 Vitrail 的调用方**（Vitrail 是 LGPL-3.0，且它是「客户」，客户如何调用一个 API 是可自由使用的信息），**完全不看 Sodium 源码**。
4. **若走路线 A 或 B，必须先取得 CaffeineMC 的书面授权**（Discord / GitHub issue 均可留痕）。
5. **`Required Notice` 保留**：分发的任何含 Sodium 制品必须附 PolyForm Shield 全文或其 URL。
6. **注意 Sodium 的 CONTRIBUTING 政策**：明确「**不接受任何 AI 生成代码**」的 PR。本项目**不要向 Sodium 提 PR**，否则违反其贡献政策。
7. **`Sodium Options API` 前车之鉴**：Sodium 明确声明「不与任何版本的 Sodium Options API 兼容，该模组与我们无关，依赖它的模组也一样」。**第三方擅自做的 Sodium 兼容层被官方公开切割是有先例的**——这既是法律风险也是声誉风险。

---

## 3. Vitrail 到底向 Sodium 要什么（完整契约）

> 本节内容在三条路线下都需要——路线 C 下它是「Vitrail 上游需要配合改造的清单」，路线 A/B 下它是「兼容层必须命中的清单」。

### 3.1 Vitrail 引用的 Sodium 类型全集（58 个）

```
api/config/{ConfigEntryPoint, ConfigState, StorageEventHandler}
api/config/option/{ControlValueFormatter, OptionImpact, Range}
api/config/structure/{BooleanOptionBuilder, ColorThemeBuilder, ConfigBuilder,
    EnumOptionBuilder, ExternalPageBuilder, IntegerOptionBuilder, ModOptionsBuilder,
    OptionBuilder, OptionGroupBuilder, OptionPageBuilder, PageBuilder}
api/util/ColorABGR
api/vertex/serializer/{VertexSerializer, VertexSerializerRegistry}

client/gpu/device/backend/DrawBackend
client/gpu/device/batch/MultiDrawBatch
client/gpu/device/context/VKDrawContext
client/gui/SodiumOptions (+ SodiumOptions$PerformanceSettings)
client/model/color/ColorProvider
client/model/light/LightPipeline
client/model/quad/ModelQuadViewMutable
client/model/quad/properties/ModelQuadFacing
client/render/SodiumWorldRenderer
client/render/chunk/{ChunkRenderMatrices, DefaultChunkRenderer, RenderSectionManager, ShaderChunkRenderer}
client/render/chunk/compile/buffers/ChunkModelBuilder
client/render/chunk/compile/pipeline/{BlockRenderer, DefaultFluidRenderer}
client/render/chunk/lists/{ChunkRenderList, ChunkRenderListIterable, SortedRenderLists}
client/render/chunk/region/RenderRegion
client/render/chunk/terrain/DefaultTerrainRenderPasses
client/render/chunk/terrain/TerrainRenderPass
client/render/chunk/terrain/material/Material
client/render/chunk/translucent_sorting/TranslucentGeometryCollector
client/render/chunk/vertex/builder/ChunkMeshBufferBuilder
client/render/chunk/vertex/format/{ChunkMeshFormats, ChunkVertexEncoder,
    ChunkVertexEncoder$Vertex, ChunkVertexType}
client/render/model/AbstractBlockRenderContext
client/render/viewport/{CameraTransform, Viewport}
client/render/viewport/frustum/Frustum
client/util/{FogParameters, GameRendererStorage}
client/util/iterator/ReversibleObjectArrayIterator
client/world/LevelSlice
```

**重要观察**：58 个里有 **21 个在 `api/` 下**（公开 API），**37 个在 `client/` 下**（实现内部）。前 21 个是许可证明确欢迎使用的（`sodium-neoforge-api` 制品就是给这个用的）；后 37 个是问题所在。

### 3.2 Vitrail 字节码里真实调用的成员（必须命中）

**静态入口与工厂**
```java
SodiumWorldRenderer.instanceNullable() : SodiumWorldRenderer
ChunkMeshFormats.COMPACT : ChunkVertexType
ChunkMeshFormats.getCurrent() : ChunkVertexType          // 被 ChunkMeshFormatsMixin 注入
MultiDrawBatch.newBatch(int) : MultiDrawBatch
VertexSerializerRegistry.instance() : VertexSerializerRegistry
FogParameters.NONE : FogParameters
DefaultTerrainRenderPasses.{SOLID, CUTOUT, TRANSLUCENT} : TerrainRenderPass
ModelQuadFacing.COUNT : int
RenderRegion.REGION_SIZE : int
DrawBackend.BACKEND : DrawBackend
DrawBackend.OPENGL : DrawBackend
SodiumOptions$PerformanceSettings.useBlockFaceCulling : boolean   // 字段
```

**SodiumWorldRenderer（实例）**
```java
void prepareChunkRendering(ChunkRenderMatrices, double, double, double)
void drawChunkLayer(RenderPass, ChunkSectionLayerGroup, ChunkRenderMatrices,
                    double, double, double, GpuSampler, @Nullable OitStage)
void extractBlockEntities(Camera, float, Long2ObjectMap, LevelRenderState)
void initRenderer(...)                                    // 被 mixin 注入 @HEAD
RenderSectionManager renderSectionManager                 // 私有字段，@Accessor
```

**RenderSectionManager（实例 + accessor 目标）**
```java
void finalizeRenderLists(Camera, Viewport, FogParameters, boolean)
SortedRenderLists getRenderLists()
int getTotalSections()
void prepareRender()
// 私有成员（RenderSectionManagerAccessor 注入）：
boolean/void needsRenderListUpdate    // @Accessor setter
int  frame                            // @Accessor getter+setter
boolean cameraChanged                 // @Accessor
void invalidateRenderLists()          // @Invoker
```

**DefaultChunkRenderer（被 MixinDefaultChunkRenderer 注入）**
```java
void rotate()                                            // @Inject HEAD + RETURN require=1
void prepare(...)   // @WrapOperation 命中 useBlockFaceCulling 字段读取
void render(...)    // @ModifyVariable argsOnly ordinal=0 换 RenderPass；@Inject RETURN 关闭
```

**ShaderChunkRenderer（被 MixinShaderChunkRenderer 注入）**
```java
protected VertexFormat vertexFormat;                     // @Shadow
void begin(TerrainRenderPass, FogParameters, GpuSampler, @Nullable OitStage)     // @Inject HEAD
@Nullable RenderPipeline compileProgram(TerrainRenderPass, @Nullable OitStage)   // @Inject HEAD cancellable
// compileProgram 内必须有且仅有一次 Map.get(Object)（ordinal=0）作为 memo 查找
```

**VKDrawContext** → `void setContext(RenderPass, RenderPipeline)`（@Inject HEAD）

**RenderRegion** → `getCachedBatch` / `clearAllCachedBatches` / `clearCachedBatchFor` / `delete`

**BlockRenderer**（extends `AbstractBlockRenderContext`）
```java
// 父类须存在、public、可继承，字段：BlockState state; BlockPos.MutableBlockPos pos;
// bufferQuad 内须调用：
//   ChunkMeshBufferBuilder.push(ChunkVertexEncoder$Vertex[], int)
//   TranslucentGeometryCollector.appendQuad(ChunkVertexEncoder$Vertex[], ModelQuadFacing, int):boolean
```

**DefaultFluidRenderer**
```java
void render(LevelSlice, BlockState, FluidState, BlockPos, BlockPos,
            TranslucentGeometryCollector, ChunkModelBuilder, Material,
            ColorProvider<FluidState>, FluidModel)          // 10 参数，顺序不可截断
void updateQuad(...)                                        // @ModifyArg ordinal=2
void writeQuad(...)
// writeQuad 内须调用 push(Vertex[], Material) 与 appendQuad(Vertex[], ModelQuadFacing, int)
```

**ChunkVertexEncoder$Vertex**
```java
float x, y, z, u, v, ao;  int color;
static void copyVertexTo(Vertex from, Vertex to)            // 参数序 from,to
```
> 真 Sodium 的 `Vertex` **没有** `vitrail$blockId` —— 那是 Vitrail 用 `@Unique` 注入的。

**其余零散契约**
```java
ChunkRenderMatrices.<init>(Matrix4fc, Matrix4fc)
Viewport.<init>(Frustum, Vector3d)
Viewport.CHUNK_SECTION_PADDED_RADIUS : float     // 疑 8.0，待确认真 Sodium
Frustum: int intersectAab(6×float) / boolean testAab(6×float)
       / boolean testSection(3×float) / boolean testSectionExpanded(4×float)
TerrainRenderPass.getTarget() : RenderTarget
TerrainRenderPass.getAtlas()  : GpuTextureView
ChunkRenderList.size() : int
ChunkRenderList.getSectionsWithGeometryCount() : int
SortedRenderLists.iterator(boolean) : ReversibleObjectArrayIterator
ReversibleObjectArrayIterator.hasNext() / next()
GameRendererStorage.sodium$getProjectionMatrix() : Matrix4fc      // 接口，注入进 GameRenderer
ColorABGR.withAlpha(int, float) : int
MultiDrawBatch.clear() / delete()
ChunkVertexType.getVertexFormat() / getEncoder()
ChunkVertexEncoder.write(long, int, Vertex[], int) : long
VertexSerializerRegistry.registerSerializer(VertexFormat, VertexFormat, VertexSerializer)
```

**配置 API**（`api/config/*`，21 个类型中的全部）—— 见第 6.3 节，属公开 API，可直接用。

### 3.3 Vitrail 的 10 个 Sodium mixin/accessor（注入点硬契约）

| Vitrail mixin | 目标 | 注入点 | 所在包 |
|---|---|---|---|
| `sodium.BlockRendererMixin` | `BlockRenderer` (→`AbstractBlockRenderContext`) | 2× `@ModifyArg index=0`，require=1 | client（内部） |
| `sodium.ChunkMeshFormatsMixin` | `ChunkMeshFormats` | `@Inject getCurrent HEAD` cancellable | client（内部） |
| `sodium.ChunkVertexMixin` | `ChunkVertexEncoder$Vertex` | `@Inject copyVertexTo TAIL` require=1 | client（内部） |
| `sodium.DefaultFluidRendererMixin` | `DefaultFluidRenderer` | `@Inject render HEAD`（10参）+3× `@ModifyArg` | client（内部） |
| `sodium.MixinDefaultChunkRenderer` | `DefaultChunkRenderer` | `@Inject rotate`×2、`@WrapOperation prepare`、`@ModifyVariable render`、`@Inject render RETURN` | client（内部） |
| `sodium.MixinRenderRegion` | `RenderRegion` | `@Inject`×4 | client（内部） |
| `sodium.MixinSodiumWorldRendererInit` | `SodiumWorldRenderer` | `@Inject initRenderer HEAD` require=1 | client（内部） |
| `sodium.VKDrawContextMixin` | `VKDrawContext` | `@Inject setContext HEAD` | client（内部） |
| `access.MixinSodiumWorldRenderer` | `SodiumWorldRenderer` | `@Accessor("renderSectionManager")` | client（内部） |
| `access.RenderSectionManagerAccessor` | `RenderSectionManager` | `@Accessor`×3 + `@Invoker`×1 | client（内部） |

> **10 个注入目标，100% 落在 `client/`（内部），没有一个在 `api/`（公开）。** 这就是路线 B 走不通的根本原因。

### 3.4 架构真相

Vitrail **不是**要 Sodium 去渲染。它把 Sodium 当「区块网格化器 + 剔除/批次构建器」，在接缝处接管；真正 GPU 绘制走 Vitrail 自己的官方 Renderpearl 管线。所以它需要的是 Sodium 的**内部结构**，而非公开 API。

---

## 4. 复用 vs 自研边界（技术层面）

### 4.1 ✅ 直接复用官方 Renderpearl（禁止自研）

| 能力 | 官方 API |
|---|---|
| GPU 设备 | `RenderSystem.getDevice()` / `tryGetDevice()` |
| 命令编码 | `GpuDevice.createCommandEncoder()` → `CommandEncoder` |
| 渲染通道 | `RenderPass` / `RenderPassDescriptor` / `RenderPass.close()` |
| 管线编译 | `GpuDevice` 的管线编译接口 |
| 采样器 | `RenderSystem.getSamplerCache().getClampToEdge(FilterMode, boolean)` |
| 纹理视图 | `GpuTextureView` / `RenderTarget.getColorTextureView()/getDepthTextureView()` |
| 顶点格式 | `com.mojang.renderpearl.api.vertex.{VertexFormat, VertexFormatElement}` |
| 缓冲 | `GpuBuffer` / `GpuBufferSlice` / `TransientMemory` |
| OIT | `net.minecraft.client.renderer.oit.OitStage` |
| 图层分组 | `net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup` |
| 设备信息 | `DeviceInfo` / `DeviceFeatures` / `DeviceLimits` |

### 4.2 ⚠️ Sodium 的活，必须自己实现

| 能力 | 自研内容 | 可用原语 |
|---|---|---|
| 区块网格化 | `ChunkModelBuilder` 等价物、方块/流体网格化 | 官方 `VertexFormat` + 纯 CPU |
| 顶点格式 | 扩展顶点布局（承载 block id 适配 pack） | 官方 `VertexFormat` |
| 区块剔除与批次 | section 管理、区域、批次构建 | 纯 CPU；`MultiDrawBatch` 可用官方 indirect draw |
| 视锥剔除 | `Frustum` 等价物 + `Viewport` + `CameraTransform` | joml 数学 |
| 半透明排序 | translucent 排序 | 纯 CPU |

### 4.3 难度分层

| 层 | 内容 | 难度 | 推荐顺序 |
|---|---|---|---|
| L1 | 形态壳（类型/签名齐备，方法体占位） | 低 | ① |
| L2 | 可启动（游戏能加载、mixin 不崩） | 中 | ② |
| L3 | 最小可见（区块能出网格、能画） | 中高 | ③ |
| L4 | 完整（阴影 / OIT / 半透明 / 配置页） | 高 | ④ |

---

## 5. 分阶段实施计划

> **前提**：先完成第 2 节的路线决策。以下按「路线 A/B 的兼容层」与「路线 C 的独立后端」分别给出。

### 路线 A/B（兼容层方向）

**阶段 0：清理与骨架（0.5 天）**
1. 删除全部虚构代码与误导性文档。
2. 修 `settings.gradle`（单工程化）。
3. 建立目标包目录树。
4. 修 `mods.toml`、`mixins.json`（`JAVA_25`）。

**阶段 1：形态兼容层 L1（2–3 天）**
按 3.2 写全所有类型，方法体留空/抛异常，只保证包名/类名/签名/字段名/继承关系正确。
**验收**：假 Sodium jar + Vitrail jar 放 `mods/`，启动到主菜单不崩。

**阶段 2：可启动 L2（2–3 天）**
`@Mod` 入口、`instanceNullable`、`initRenderer`、`DrawBackend.BACKEND`（**必须非 OPENGL**）、`GameRendererStorage` 注入、`ChunkMeshFormats.getCurrent`。
**验收**：进世界不崩；Vitrail 日志识别 Vulkan 后端。

**阶段 3：最小可见 L3（1–2 周）**
区块网格化（最大一块）、区块管理、渲染委托、区域与批次、视口与剔除。
**验收**：不加载 pack 时世界能正常渲染。

**阶段 4：完整 L4（2–4 周）**
阴影、半透明排序、OIT、配置 API、顶点序列化、颜色工具。

### 路线 C（独立后端方向）

**阶段 0：向 Vitrail 上游提案（并行进行）**
1. 在 Vitrail 仓库开 issue，说明「提供 Vulkan 渲染后端的抽象层」的提案。
2. 提议：把 `dev.vitrail.sodium.*` 与 `dev.vitrail.mixin.sodium.*` 抽象为一个**后端接口**（如 `dev.vitrail.backend.ChunkRenderBackend`），Sodium 只是其中一个实现。
3. 一旦上游接受，本项目实现「`shadercore-vulkan` 后端」。

**阶段 1–4**：与上面相同，但：
- **不需要**复刻 `net.caffeinemc.*` 任何 FQN；
- **不需要**冒充 modId；
- Vitrail 侧需要的改动由上游完成（或提交 PR）。

---

## 6. 构建接线

### 6.1 路线 A/B：参考真 Sodium 作编译期

```gradle
dependencies {
    // 真 Sodium：仅编译期比对签名，绝不打包
    compileOnly("net.caffeinemc:sodium-neoforge:0.9.2+mc26.3") { transitive = false }
    // 或者：compileOnly("net.caffeinemc:sodium-fabric:0.9.2+mc26.3") { transitive = false }

    compileOnly "net.fabricmc:sponge-mixin:<mixin_version>"
    compileOnly "io.github.llamalad7:mixinextras-common:<mixinextras_version>"
}
```
仓库：`maven { name "CaffeineMC"; url "https://maven.caffeinemc.net/releases" }`

> ⚠️ Modrinth 的 `sodium-neoforge` jar 外层是 launcher shim，真类在 `META-INF/jarjar` 内层。

### 6.2 路线 C：只依赖官方 API 制品

```gradle
dependencies {
    // 官方公开 API（PolyForm 明确欢迎使用）
    compileOnly("net.caffeinemc:sodium-neoforge-api:0.9.2+mc26.3")
    // 其余官方与平台依赖
}
```

### 6.3 mods.toml

**路线 A/B**：
```toml
[[mods]]
modId="sodium"                          # 冒充（合规风险，见 §2）
displayTest="IGNORE_ALL_VERSION"

[[dependencies.sodium]]                 # 表名必须与 modId 一致
modId="minecraft"
type="required"
versionRange="[26.3,26.4)"
side="CLIENT"
```

**路线 C**（推荐）：
```toml
[[mods]]
modId="shadercore-vulkan"               # 自己的 ID，不冒充
displayTest="IGNORE_ALL_VERSION"

[[dependencies.shadercore-vulkan]]
modId="minecraft"
type="required"
versionRange="[26.3,26.4)"
side="CLIENT"
```

### 6.4 mixins.json

```json
{
  "required": true,
  "minVersion": "0.8.5",
  "package": "dev.shadercore.vulkan.mixin",
  "compatibilityLevel": "JAVA_25",
  "injectors": { "defaultRequire": 1 },
  "client": [ "GameRendererMixin" ],
  "mixins": []
}
```

---

## 7. 验证方案

### 7.1 编译期
```bash
cd D:/Code/Minecraft/Shader-Core-Vulkan
gradle jar
unzip -l build/libs/*.jar | grep '\.class' | head
```

### 7.2 签名比对（强烈建议自动化）
```bash
javap -p -s net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer > real.txt
javap -p -s <本项目对应类> > ours.txt
diff real.txt ours.txt
```
对 3.2 里每个类做一遍。**这是进游戏前最可靠的验收手段。**

### 7.3 运行期

| 检查点 | 期望 |
|---|---|
| 启动到主菜单 | 不崩；无 `Mixin apply failed` |
| 进入世界 | 不崩；识别 Vulkan 后端 |
| 不加载 pack | 世界正常渲染 |
| 加载 pack（BSL / Complementary） | 地形经 pack 着色 |
| 阴影 | 日志有 `shadow-cull` 行 |
| 切 pack → none | 无「拉伸彩色尖刺」（顶点格式不匹配的症状） |

### 7.4 mixin 注入确认
`-Dmixin.debug.export=true` 或 `-Dmixin.debug.verbose=true`，确认 10 个 mixin 全部 applied。

---

## 8. 风险与陷阱

| 风险 | 说明 | 对策 |
|---|---|---|
| **PolyForm Noncompete** | 「假 Sodium」可能被认定竞争 | 见 §2，优先路线 C，或取得书面授权 |
| **JAVA_21 静默跳过** | mixin 编译过、运行不生效 | `compatibilityLevel: JAVA_25` |
| **`@Accessor` 字段名不符** | 注入失败 | 按 3.2 精确命名 + javap 比对 |
| **`require=1` 失败** | 任何签名/内联变化导致加载失败 | 保留方法名与参数表，禁止内联 |
| **`compileProgram` 里的 `Map.get`** | `@Redirect` 要求恰好一次 ordinal=0 | 保留「按 pass 缓存管线」的 memo |
| **`prepare` 读 `useBlockFaceCulling`** | `@WrapOperation` 命中字段读取 | `prepare` 内必须真的读该字段 |
| **`DrawBackend.BACKEND` 恒 OPENGL** | Vitrail 走拒绝分支什么都不画 | 必须返回非 OPENGL |
| **真 Sodium 同装冲突** | 两个 mod 声明同一批类 | `displayTest` + 要求卸载真 Sodium |
| **GPL 污染（Vitrail）** | Vitrail 是 LGPL-3.0 | 不复制 Vitrail 代码 |
| **内部 API 版本漂移** | 26.3 → 26.4 会变 | 按版本分目录 |
| **顶点 stride 不一致** | 「拉伸彩色尖刺」 | 严格用官方 `VertexFormat` 构造 |

---

## 9. 与「禁止造轮子」的对照

| 问题 | 答案 |
|---|---|
| Vulkan 设备/命令/管线要自己写？ | **不要**。全用 `com.mojang.renderpearl.*` |
| SPIR-V 编译器要自己写？ | **不要**。Vitrail 自己管 |
| Render pass 管理？ | **不要**。用官方 `RenderPass`/`RenderPassDescriptor` |
| 区块网格化？ | **要**，但只用官方 `VertexFormat` + 纯 CPU |
| 区块剔除？ | **要**，纯 CPU |
| 半透明排序？ | **要**，纯 CPU |
| 配置页 UI？ | 不必要（可选） |

---

## 10. 待确认清单

1. 真 Sodium 0.9.2+mc26.3 jar 的获取方式（maven `net.caffeinemc:sodium-neoforge`）。
2. `Viewport.CHUNK_SECTION_PADDED_RADIUS` 的值（疑 8.0）。
3. `RenderSectionManager` 公开 API 全集（构造签名等）。
4. `ChunkRenderList.prepareForRender` 签名。
5. `ChunkModelBuilder` 方法集。
6. `Material` / `TerrainRenderPass.getTarget()/getAtlas()` 具体返回。
7. `SodiumOptions$PerformanceSettings` 完整字段。
8. `ModelQuadFacing.COUNT` 的值。
9. `RenderRegion.REGION_SIZE` 的值。
10. `DrawBackend` 常量全集。
11. **CaffeineMC 对本项目的态度**（若要走路 A/B，这是前置条件）。
12. **Vitrail 上游是否接受后端抽象**（若走路线 C，这是前置条件）。

---

## 附录 A：Vitrail 侧不变量

- `@Mixin(remap = false)` —— 名字必须字面一致，不走混淆表。
- `require = 1` 广泛使用 —— 注入必须恰好命中。
- `BlockRendererMixin extends AbstractBlockRenderContext` —— 目标必须是 public、可继承，且有 `state` / `pos` 字段。
- `ChunkVertexEncoder$Vertex.copyVertexTo(from, to)` 必须是静态，且该类不保留额外字段。
- `ShaderChunkRenderer.vertexFormat` 必须是 `protected VertexFormat`。
- `DefaultChunkRenderer.render` 的 `@ModifyVariable argsOnly ordinal=0` 必须落在 `RenderPass` 参数上。

## 附录 B：证据来源

- Sodium 仓库与许可证：`https://github.com/CaffeineMC/sodium`（`LICENSE.md`、README「📜 License」、`CONTRIBUTING.md`、`thirdparty/NOTICE.txt`）
- PolyForm Shield 1.0.0 全文：`https://polyformproject.org/licenses/shield/1.0.0/`
- Sodium 官方 Maven 与 Config API wiki：`https://github.com/CaffeineMC/sodium/wiki/CaffeineMC-Maven-&-Config-API`
- Sodium Config API USAGE.md：`common/src/api/java/net/caffeinemc/mods/sodium/api/config/USAGE.md`
- Sodium 公开 API 包结构：`common/src/api/java/net/caffeinemc/mods/sodium/api/{blockentity,config,internal,math,memory,texture,util,vertex}`
- Vitrail 源码：`D:/Code/Minecraft/Vitrail-Shaders/common/src/main/java/dev/vitrail/**`、`common/src/mc26.3/java/dev/vitrail/**`
- Vitrail 成品 jar 字节码：`D:/Code/Minecraft/Shader-Core-Vulkan/vitrail-0.12.0-beta+mc26.3.jar`（`javap -c` 提取调用点）
- Vitrail 许可证：`LICENSE` = GNU LGPL v3
- 搜索于 2026-09-29
