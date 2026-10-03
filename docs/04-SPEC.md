# 04 · 技术规格书

> 配套：`03-DIRECTION.md`（必读前置）、`07-CONSTRAINTS.md`
> **版本权威：`05-VERSION.md`**（支持范围 = MC 26.3 及之后；当前主线 26.3）
> 版本锁定：MC **26.3** / NeoForge **26.3.0.41-beta** / Java **25** / MDG **2.0.147** / Gradle **9.4.1**
> （工程已是官方 MDK `NeoForgeMDKs/MDK-26.3-ModDevGradle`，实际值以 `gradle.properties` 为准）

---

## 1. 定位（一句话）

**不替代任何前置。基于原版 26.3 已存在的 Vulkan 渲染后端，实现一个能加载 OptiFine / Iris 格式着色器包的引擎。**

> **版本范围**：支持 **MC 26.3 及之后**的新版本，当前主线锁 **26.3**。
> 不支持 26.2 及之前（那代没有 `renderpearl.backend.api`）。详见 `05-VERSION.md`。

```
你的模组
  ├── 解析 OF/Iris 格式包          ← 自研（护城河）
  ├── 编排 pass 顺序（shadow/deferred/composite）  ← 自研
  └── 生成 & 注册 RenderPipeline   ← 用原版官方 API
        ↓
com.mojang.renderpearl.api.pipeline.RenderPipeline
        ↓（原版分发）
com.mojang.renderpearl.backend.vulkan.VulkanDevice  ← 原版实现，不碰
```

---

## 2. 命名与坐标

- Java 包名：`dev.<你的域>.vkdisp`（示例，可换）
- 模组 id：`vkdisp`（**绝不要用任何**第三方渲染模组的名字，如 `vitrail` / `iris` / `optifine`，也不要用 `sodium`）
- 着色器资源命名空间：`vkdisp`（内部管线用），用户包用 `vkdisp_pack`（`ShaderPackRepository` 生成的虚拟资源包）
- mixin 配置 `compatibilityLevel`：**必须 `JAVA_25`**（写 `JAVA_21` 会在 Java 25 下静默跳过全部 mixin）

---

## 3. 核心组件清单

> **每个组件开工前先做参考调研**（`17-NATIVE.md` §1，`07-CONSTRAINTS.md` T13）。
> 下表「参考」列只是**去哪找**，不代表可以搬代码 —— 本项目 MIT，默认只能读思路。
> 「热度」列决定实现语言（`17-NATIVE.md` §7）：❄️ 冷路径默认 Java、**Rust 为实测候选**；
> 🔥 热路径默认 Java，**须先测出瓶颈**才考虑原生。

### 3.0 组件总览（参考 / 热度 / 语言，2026-10-02 重标）

| 组件 | 参考（只读思路） | 热度 | 影响的指标 | 语言 |
|---|---|---|---|---|
| `pack/` 格式解析 | **Iris** `shaderpack/parsing/` | ❄️ 冷 | **B3 / B4** | Java（Rust 实测候选 ②） |
| `glsl/` 预处理器（`#include`/`#define`） | **IrisShaders/glsl-preprocessor**（GPL+例外） | ❄️ 冷 | **B3 / B4** | Java（Rust 实测候选 ①） |
| `glsl/` 转译（OF → M GLSL，8 段） | **IrisShaders/glsl-transformer**（自定义传染） | ❄️ 冷 | **B3 / B4** | Java（Rust 实测候选 ①） |
| `pipeline/` 管线构建 | Sulkan `runtime/ShaderPipelines`（GPL） | ❄️ 冷（构建）+ 🔥 热（键查找） | B4 / **B1** | Java |
| `render/` 帧编排 | Sulkan `LevelRenderer*Mixin`（GPL） | 🔥 **热** | **B1 / B2** | Java |
| `mixin/` 管线装配层注入 | VulkanMod（LGPL）挂载模式 | 🟡 装配期 | **支柱① 兼容** | Java（只转发） |
| `config/` `screen/` | 原版屏幕基类 | ❄️ 冷 | 无 | Java |
| `accel/` 加速层门面 | `17-NATIVE.md` §4 | — | B3 / B4 | ⏸️ **仅 G 系列裁决「采用」后才建** |
| `bridge/` 原版 API 隔离 | 本项目自定 | — | — | Java |

### 3.1 着色器包解析层（`pack/`）

```java
pack/
  ShaderPack.java               // 包的运行时模型：元数据 + 阶段映射 + 选项定义
  ShaderPackScanner.java        // 扫描 shaderpacks/ 目录（.zip 与文件夹）
  ShaderPackRepository.java     // 解包到 resourcepacks/<虚拟包>/ 并让原版资源系统加载
  properties/
    ShaderProperties.java       // shaders.properties 解析（选项、开关、profiles、sliders）
    BlockProperties.java        // block.properties → 方块ID→图层号
    ItemProperties.java         // item.properties
    DimensionProperties.java    // dimension.properties → world0/1/-1/end 分支
  Program.java                  // 一个阶段程序（gbuffers_terrain.vsh/fsh 等）
  ProgramStage.java             // 枚举：SHADOW / GBUFFERS_* / DEFERRED* / COMPOSITE*
  Option.java / OptionType.java // 包自定义选项
```

**关键约束：`ShadersScreen` 只读不写用户的包**（不要学 Sulkan 往 `options.resourcePacks` 反复写，会产生"资源包列表被莫名修改"的投诉）。

### 3.2 GLSL 转译层（`glsl/`）

OF 格式的 GLSL 与 M 原版 GLSL 有差异，需要一层**源码级转译**后交给原版编译：

```java
glsl/
  OfGlslTranslator.java    // OF GLSL → M GLSL 的入口
  AttributeRewriter.java   // OF 老式 attribute/varying → M 语法
  UniformInjector.java     // 注入 OF 内建 uniform 声明
  LegacyBuiltinInjector.java // 141 阶段矩阵修复：GLSL 1.20 旧内建 token 级替换 + 「用而未声明」属性名声明注入
  IncludeProcessor.java    // 处理 #include（绝对 / 前缀与相对路径两种形式）
  DefineProcessor.java     // #define / #undef / 条件编译
  ConstEvaluator.java      // OF 的 const int X = ... 选项常量
```

> **141 阶段矩阵修复（`LegacyBuiltinInjector`，转译第 8 段，2026-10-02 运行时闭环）**：
> GLSL 1.20 旧内建在 Vulkan GLSL 下两类失败 —— `(a) undeclared identifier`（属性旧名
> `gl_MultiTexCoord*` / `gl_Color` / `gl_Normal` / `gl_Vertex` 及其展开的 `Position`、
> 矩阵旧名）与 `(b) identifiers starting with "gl_" are reserved`（gl_ 前缀被 GLSL 公开词法
> 保留，用户声明必被驱动拒）。修复双职责（等行替换、使用驱动、幂等）：
> - **属性旧名 → 语义等价合法名**（再注入合法名裸 `in` 行，location 由 `IoLocationAdapter` 补写，
>   仅 VERTEX 阶段）：`gl_MultiTexCoord0→UV0` / `gl_MultiTexCoord1→UV2` / `gl_Color→Color` /
>   `gl_Normal→Normal` / `gl_Vertex→vec4(Position,1.0)`（或包内已声明位置属性名）；
> - **矩阵旧名 → 宿主按本表上传的 gbuffer 矩阵**（不注入声明，天然满足 Vulkan 块约束）：
>   `gl_ProjectionMatrix→gbufferProjection` / `gl_ModelViewMatrix→gbufferModelView` /
>   `gl_ModelViewProjectionMatrix→(gbufferProjection * gbufferModelView)` /
>   `gl_NormalMatrix→(transpose(inverse(mat3(gbufferModelView))))`；
>   `gl_TextureMatrix[n]→mat4(1.0)`（下标随 token 消费；本引擎无固定功能纹理变换，UV 直接按
>   顶点属性采样，单位阵即真实语义；裸名无下标保留交驱动显式报错 T11）；
> - **Distant Horizons 兼容桩（GAP-002，转译期 shim，非原版能力缺口）**：BSL `dh_*` 着色器引用
>   DH 注入的 `dhMaterialId` / `DH_BLOCK_WATER` / `DH_BLOCK_LAVA` / `DH_BLOCK_LEAVES` /
>   `DH_BLOCK_ILLUMINATED` / `DH_OVERDRAW`，本引擎不集成 DH（07-CONSTRAINTS D3/D16），按普通
>   全局 `int` / `const int` 声明为 stub，使用驱动门 + 任何阶段注入 + 已声明跳过 + INFO 显式诊断。

**OF 内建 uniform（必须提供的语义）**：

| uniform | 类型 | 含义 |
|---|---|---|
| `gbufferModelView` | mat4 | 主视图矩阵 |
| `gbufferProjection` | mat4 | 投影矩阵 |
| `gbufferModelViewInverse` | mat4 | 视图逆矩阵 |
| `gbufferProjectionInverse` | mat4 | 投影逆矩阵 |
| `shadowModelView` / `shadowProjection` | mat4 | 光源空间矩阵 |
| `cameraPosition` | vec3 | 摄像机世界坐标 |
| `sunPosition` / `moonPosition` | vec3 | 天体位置 |
| `shadowLightPosition` | vec3 | 光源方向 |
| `frameTimeCounter` | float | 秒级累加（用于动画） |
| `frameCounter` | int | 帧计数 |
| `viewWidth` / `viewHeight` | float | 视口尺寸 |
| `near` / `far` | float | 裁剪面 |
| `wetness` / `rainStrength` | float | 天气 |
| `isEyeInWater` | int | 0/1/2(岩浆)/3(粉雪) |
| `worldTime` / `worldDay` | int | 游戏时间 |
| `atlasSize` | ivec2 | 方块图集尺寸 |
| `eyeBrightnessSmooth` | ivec2 | 亮度 |

**上传语义（P4.1.3 起 `render/OfUniformManager` 实现；出处 = 原版 26.3 反编译实证 + BSL
消费点实测，X9 非猜测；表本身（上表名称/类型/顺序）是冻结契约，见 `UniformCatalogTest` 硬编码对照）**：

| 值 | 语义与出处 |
|---|---|
| `gbufferModelView` | `viewRotationMatrix × translate(−pos)`（`CameraRenderState`，与 FrameApi 相机同链但**不含锚点** —— 包侧必须看真实相机） |
| `gbufferProjection` | `cameraState.projectionMatrix` 原样拷贝；两个逆矩阵 = JOML `.invert()`（奇异时回退单位阵） |
| `sunPosition` / `moonPosition` | **眼空间**单位方向：世界向量 `(−sinθ, cosθ, 0)`，θ = `attributeProbe` 的 `SUN_ANGLE`/`MOON_ANGLE`（**度 × π/180 转弧度**，与 SkyRenderer:119-120 逐位同源；即原版天空渲染链 `Ry(−90°)·Rx(θ)·(0,1,0)`，晨东/午顶/昏西三点校验过）× viewRotation × 归一。**直读 probe 不经 `SkyRenderState`**（其字段仅在 LevelExtractor 跑过后有效，首帧/加载帧为默认 0，p413 run1 实测）。缩放无关性已证：BSL `gbufferProjection * vec4(sunPosition, 1.0)` 透视除法齐次，单位与 ×2000 同像 |
| `shadowLightPosition` | 太阳方向 y>0 取太阳、否则取月亮（BSL 未消费本项，口径如实登记） |
| `shadowModelView` / `shadowProjection` | `LightSpaceList.Entry` 首级联分矩阵（P3.1 同源；列表空 → 单位阵） |
| `cameraPosition` | `CameraRenderState.pos`（float 化） |
| `frameTimeCounter` | wall-clock 秒逐帧累加（换世界重置；单帧增量截断 ≤0.5s 防切窗尖峰） |
| `frameCounter` | 自增 int（跨世界持续） |
| `near` / `far` | 世界内 `Camera.PROJECTION_Z_NEAR=0.05` / `cameraState.depthFar`（与构建投影同参，Camera:43/95/109 实证）；菜单占位 0.1/32（与 `placeholderCamera` 同参） |
| `viewWidth` / `viewHeight` | 本 pass 主目标宽高 |
| `rainStrength` | `Level.getRainLevel(partialTicks)` 直读（= SkyRenderer:122 的 `1 − rainBrightness` 恒等变形，等价但不依赖 `SkyRenderState` 提取态 —— p413 run1 曾因提取前默认值误报 1.0）；`wetness` v1 = rainStrength（OF 级平滑未做，登记） |
| `isEyeInWater` | `cameraState.fogType` 映射：NONE/ATMOSPHERIC→0，WATER→1，LAVA→2，POWDER_SNOW→3 |
| `worldTime` / `worldDay` | `Level.getDefaultClockTime()`（26.3 无 `getDayTime`，ClockManager 实证）：`floorMod(t,24000)` / `floorDiv(t,24000)` |
| `atlasSize` | `TextureManager.getTexture(LOCATION_BLOCKS)` → `GpuTexture.getWidth/Height(0)` |
| `eyeBrightnessSmooth` | **近似 v1（登记）**：`ivec2(block×16, round(clamp(sky,0,15)×clamp(skyLightFactor,0,1)×16))` 各 clamp 到 0..240；block/sky = eye 处 `LightLayer`，factor = `SKY_LIGHT_FACTOR` 属性 probe —— OF 精确曲线与平滑未取证（X9） |
| 非目录填充 | `aspectRatio = viewWidth/viewHeight`（BSL `vec2(aspectRatio,1.0)` 消费）、`timeAngle = (t%24000)/24000`（与 BSL 夜窗 0.5325–0.9675 = 12780/24000–23220/24000 吻合）、`moonPhase = attributeProbe(MOON_PHASE).index()`（SkyRenderer:125 同源，null 回退 `SkyRenderState`；原版序直传；与 OF 相位序一致性未取证，登记） |
| **不填充（恒 0 + 一次性 INFO 列名）** | `timeBrightness`（OF 公式未取证，X9 拒猜）、blindFactor / darknessFactor / nightVision / endFlash* / shadowFade / bedrockLevel / dh* / gbufferPrevious* 等非目录项 |

**布局与缓冲（P4.1.3）**：块成员顺序 = 收编声明在前 + 目录缺失在后（`UniformInjector` 发射序，
本表顺序只决定缺失尾部）；BSL 的 composite / deferred / final 收编集各不相同 → **三套
std140 布局、三条环形缓冲**（P4.1.3 双槽 42/24 成员，P4.1.4 补 final 第三槽 24/512），
按 pass 各绑各的。布局由 `glsl/translate/BuiltinsBlockLayout` 从**转译终稿
文本**重解析（F3 冻结契约：`TranslateResult` 不外传 Injector 内部结果，终稿即驱动编译的
真源）。太阳走**原版路径**（非 BSL `sunPathRotation=-40°` 包天空）—— 与当前画面里 vanilla
渲染的天空一致；P4.2 启用包天空后复审（18-PARALLEL 未覆盖登记）。

### 3.3 管线构建层（`pipeline/`）

```java
pipeline/
  PipelineFactory.java     // 从 Program 生成 RenderPipeline（用官方 Builder）
  BindGroupFactory.java    // 从 uniform/采样器声明生成 BindGroupLayout
  VertexFormatFactory.java // 顶点格式（含 OF 的 mc_Entity / mc_midTexCoord 扩展属性）
  PipelineCache.java       // 避免重复构建；着色器重载时清理
  PassSnippets.java        // 复用的 Snippet（post-processing / terrain / entities）
```

**必须用的官方 API**（参考 Sulkan 的活样板）：

```java
RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
    .withLocation(Identifier.fromNamespaceAndPath("vkdisp", "pipeline/composite_1"))
    .withVertexShader(coreShader("fullscreen"))
    .withFragmentShader(coreShader("composite_1"))
    .withBindGroupLayout(BindGroupLayout.builder()
        .withSampler("colortex0")
        .withSampler("depthtex0")
        .withUniform("OfSceneParams", UniformType.UNIFORM_BUFFER)
        .build())
    .build();
```

### 3.4 帧编排层（`render/`）

```java
render/
  FrameComposer.java          // 按 OF 语义编排 pass 顺序
  ShadowPass.java             // shadow.fsh/vsh，多级联
  GBufferPass.java            // 接管原版地形/实体/水体的绘制到自定义目标
  DeferredPass.java           // deferred*.fsh 链
  CompositePass.java          // composite*.fsh 链
  FinalPass.java              // final.fsh → 主渲染目标
  RenderTargetPool.java       // colortex0..15 / depthtex0..2 / shadowtex0..1
  OfUniformManager.java       // 上传 §3.2 的内建 uniform
```

**渲染目标需要实现的原版接口**：用 `com.mojang.renderpearl.api.textures.GpuTexture` + `GpuTextureView`，或直接用 `com.mojang.blaze3d.pipeline.RenderTarget` / `TextureTarget`（这两个**没有**迁入 renderpearl，仍在 blaze3d）。

**帧图插入点（参考 Sulkan 的 `LevelRendererShadowMixin` / `LevelRendererPostMixin`）**：
- 影子 pass：`@Inject` 到 `LevelRenderer.addMainPass` HEAD，`builder.addPass("vkdisp:shadow").executes(...)`
- 后处理链：`@Inject` 到 `LevelRenderer.render` 的 RETURN

### 3.5 配置与 GUI（`config/`、`screen/`）

```java
config/
  ModConfig.java          // 本模组自身配置（开关、选中包、质量档、加速开关）
  PackOptions.java        // 用户包声明的选项的运行时值
  OptionBinding.java      // 选项 → 着色器 #define / uniform 的绑定
screen/
  ShaderPacksScreen.java  // 包列表（复用原版屏幕基类）
  PackOptionsScreen.java  // 动态生成 OF 包选项 UI
```

### 3.6 加速层（`accel/`）—— ⏸️ **仅 G 系列裁决「采用」后才建**

> **当前状态：只有 Java 实现，没有任何原生库**（`17-NATIVE.md` §6 登记表为 0 条）。
>
> **2026-10-02 变更**：用户要求测试 Rust vs Java 性能差异。
> 本节从「假设性设计备忘」改为**可执行规范**，但**建包的时点由 G 系列闸门裁决决定**
> （`17-NATIVE.md` §5.2）：
>
> | 裁决 | 动作 |
> |---|---|
> | Rust 端到端快 **≥ 20%** + 等价性测试全绿 | ✅ 采用 → 建 `accel/`，进 §5.1 构建 |
> | 快 5%–20% | 🟡 暂缓 → 在更大包上复测；仍在此区间则不采用 |
> | 差异 < 5% / Rust 更慢 / 等价性不通过 | ❌ 不采用 → 登记结论，`accel/` 永不创建 |

**本轮实测范围（仅两项）**：① `glsl/` 预处理 + 转译；② `pack/` 解析。
**热路径（`render/`、`pipeline/` 键查找）本轮不测。**

```java
accel/                    // ← 裁决「采用」后才创建
  GlslPassCompiler.java   // 冷：预处理+转译整批（FFM 边界，按批不按条）
  PackScanFinalizer.java  // 冷：properties/options 定批 + program 清单
  AccelBackend.java       // 选择器：探测原生库 → 选实现 → 打印所选后端
  backend/java/           // ✅ 永远存在，默认
  backend/native/         // ⚠️ 仅「采用」裁决后存在；缺失 → 自动降级 + WARN
```

**硬要求**（细则见 `17-NATIVE.md` §4.2）

- 接口签名只用纯 Java 类型（**不暴露 `MemorySegment` 到业务层**）
- **接口按批设计，不按条**（T18：逐条调用会把 FFI 收益吃光）
- 原生库缺失 / 平台不匹配 → **自动降级到 Java 并打 WARN**，不许崩、不许静默（N2）
- 启动时打印所选后端：`vkdisp: accel backend = java | native(<lib>)`
- 每个原生模块必须有 A/B 开关（N4）
- 🔴 `extern "C"` 必须 `catch_unwind`；禁止 `panic = "abort"`（T17/N5/N6，崩游戏）

**接线路线（Java 25，FFM 正式版）**：
Rust `cdylib` → `cbindgen` 出 C 头 → **`jextract`（JDK 自带）** 生成 Java 绑定（构建期，不入 jar）
→ 运行期 `System.load` + `SymbolLookup.loaderLookup()` + `linker.downcallHandle()`。
**不用 JNI**（T19：JNI 要手写 C 胶水，是额外维护负担）。

---

## 4. 顶点格式扩展（OF 兼容关键）

OF/Iris 包依赖额外的顶点属性。原版 `VertexFormat` 需要通过 `VertexFormat.Builder` 构建：

| 属性 | 类型 | 用途 |
|---|---|---|
| `Position` | vec3f | 位置 |
| `Color` | vec4ub | 颜色 |
| `UV0` | vec2f | 主纹理 |
| `UV1` | vec2s | 光照贴图 |
| `UV2` | vec2s | 法线 |
| **`Normal`** | vec3b | OF 法线（PBR 必备） |
| **`mc_Entity`** | vec2s | 方块/实体 ID（block.properties 用） |
| **`mc_midTexCoord`** | vec2f | 方块中心 UV |

**注意**：字段名必须与着色器里的 `attribute` 声明**完全一致**——参考你的 `SKILL.md`（`neoforge-262-mod-port`）里"字段名必须字面一致"的坑。

> ⚠️ **复核注记（2026-09-29，env-1 实测复核）**：上表与 OF 官方属性表有 **两处出入**，已核实——
> 来源：OptiFine 规范文档 `OptiFineDoc/doc/shaders.txt`「Attributes」节（仅取格式事实，零文本搬用；
> sp614x/optifine 无 LICENSE = ARR，按 07-CONSTRAINTS X20 不并入其文本表达）：
> 1. `mc_Entity` 官方声明为 **`in vec3 mc_Entity`**（xy = blockId / renderType），**不是 vec2s**；
> 2. `vaUV1` = **overlay**、`vaUV2` = **lightmap**（上表把 UV1 记作"光照贴图"、UV2 记作"法线"，两处用途均有误）；
>    法线是独立属性 `Normal`，不占 `UV2`。
> 3. 官方 Attributes 表另有 `at_tangent` / `at_velocity` / `at_midBlock` 三项（上表未列；F2 契约已按事实预留）。
>
> 🔴 **未定项（禁止猜值，07 X9）**：官方文档给的是**着色器侧**分量类型，
> **底层元素类型（float32 / int16）文档未给**，而它直接决定 `mc_Entity` 的字节数与 stride。
> 该值必须等 **P1.2 构建真实 `VertexFormat` 时与原版实测对齐**后再走
> `18-PARALLEL.md` §3.2 定稿；在此之前 F2 的 `VertexAttribute` 与 E 线 `VertexLayout`（stride=47）
> 沿用本表旧值，缺口由 `18-PARALLEL.md` §10 的 **P-1d** 跟踪，**不许在并行线里私自改契约**。

---

## 5. 构建配置

```gradle
// build.gradle 要点（官方 MDK 结构，ModDevGradle）
plugins {
    id 'net.neoforged.moddev' version '2.0.147'
}

neoForge {
    version = project.neo_version          // = 26.3.0.41-beta，取自 gradle.properties
}

dependencies {
    // 🔴 本项目零第三方渲染模组依赖（`07-CONSTRAINTS.md` L11）。
    //    不得出现任何第三方渲染器/着色器加载器的 compileOnly / implementation / runtimeOnly。
    //    地形走原版 SectionRenderDispatcher，着色器走原版编译通道。

    // 若走自带 shaderc 的 fallback 方案（不推荐，优先原版通道）
    // implementation 'org.lwjgl:lwjgl-shaderc:3.3.3'
    // runtimeOnly  'org.lwjgl:lwjgl-shaderc:3.3.3:natives-windows'
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType(JavaCompile) { options.release = 25 }
```

`neoforge.mods.toml`（**实际由 `src/main/templates/META-INF/neoforge.mods.toml` 经 `${}` 展开生成**）：

```toml
modId = "${mod_id}"                 # → vkdisp，见 §2 命名
version = "${mod_version}"
displayName = "${mod_name}"         # → Vulkan Shader Dispatcher

[[dependencies.${mod_id}]]          # ← 表名必须等于 modId
    modId = "neoforge"
    type = "required"
    versionRange = "[${neo_version},)"
    side = "BOTH"

[[dependencies.${mod_id}]]
    modId = "minecraft"
    type = "required"
    versionRange = "${minecraft_version_range}"   # → [26.3,)
    side = "BOTH"
```

> `displayTest` 用默认值（**不要** `IGNORE_ALL_VERSION`）。本模组为纯客户端模组，
> 不需要额外声明服务端依赖；若将来要在服务端做硬拒绝，再加 `side = "CLIENT"` 的依赖表。

`vkdisp.mixins.json`（**Phase 0 还没有 mixin，等第一个 mixin 落地时再建这个文件**）：

```json
{
  "required": true,
  "package": "dev.vkdisp.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": [],
  "client": [ "...每加一个 mixin 必须登记..." ],
  "injectors": { "defaultRequire": 1 }
}
```

建好之后必须同时在 `neoforge.mods.toml` 里声明，否则不会被加载：

```toml
[[mixins]]
config = "${mod_id}.mixins.json"
```

`gradlew` / shell 脚本：**仓库内必须放 `.gitattributes`**（`* text=auto eol=lf`）+ 仓库级 `core.autocrlf=false`，并手工 `git update-index --chmod=+x gradlew`（Windows 下 git 不跟踪可执行位）。

### 5.0 🔴 mixin 注入点登记表（M1 松绑后的强制登记制）

> `07-CONSTRAINTS.md` M1 已从「全局只许 1 个注入点」松绑为「**管线装配层 + 登记制 +
> 可关闭制 + 逐个开启**」。**不限数量，但每个注入点必须在此登记**（X28）。
> 登记即生效；未登记的注入点一律禁止。
>
> 目标类名/方法名只许引用 `bridge/MixinTargets` 常量，禁止散落字面量（M1 编码约束 ①）。

| # | 目标类 | 目标方法（签名） | 注入类型 | 用途 | 兼容性判定 | 可关闭键 | 状态 |
|---|---|---|---|---|---|---|---|
| M-01 | `net.minecraft.client.renderer.chunk.ChunkSectionLayer` | `pipeline(boolean)`（public，1 参 `multiDraw`） | 装配层 | 把**派生管线**（多附件 / 自定义 uniform）按**层**接到地形 draw 上 —— 零 mixin 无法达成（`02` §5.1） | 源码级核实（26.3.0.41-beta sources jar 第 39-41 行 + `ChunkSectionsToRender` 第 121/176 行）：`layer.pipeline(multiDraw)` 是派生管线被地形 draw 用上的**唯一必经点**，且按层解析 | `mixin.wireTerrain` | 🟡 **已实现（2026-10-03）**：通道已通 + GAP-004 块已挂上并每帧绑定；GAP-003 多附件 ⛔ 未做（见 §5.0.1） |
| M-01b | `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` | `renderLayers(ChunkSectionLayer[], GpuSampler, RenderPass, GpuTextureView, GpuTextureView, @Nullable RenderPipeline, @Nullable RenderPipeline)`（private，7 参，末两个是 override） | 装配层 | 把 M-01 派生管线新增的**自定义 uniform 块**绑到地形 draw 的 RenderPass（GAP-004） | 源码级核实：原版 `renderLayers` 只绑 `TerrainUniform` / `Sampler0` / `Sampler2`；驱动层 STRICT_VALIDATION 按**布局**逐条校验，布局多出的条目无人绑即抛 `Missing uniform` | `mixin.bindTerrainParams` | 🟡 **已实现（2026-10-03）**：块能挂上并每帧绑定；**但本轮片元仍是原版 `core/terrain`，它不读这个块 ⇒ 被绑定但未被消费** |
| M-02 | （待定） | （待定） | 装配层 | 实体 / 天空 draw 走派生管线 | 待源码核实 | `mixin.wireEntity` | ⏳ 未开始 |
| M-03 | （待定） | （待定） | 装配层 | | | | ⏳ 未开始 |
| M-04 | `net.minecraft.client.renderer.LevelRenderer` | `addMainPass(FrameGraphBuilder, FeatureRenderDispatcher$PreparedFrame, GpuBufferSlice, ChunkSectionsToRender, boolean)`（private，5 参） | 装配层 | **GAP-003 多附件的方案 B**：拿到地形 render pass 的所有权，把**整个**主 pass 改成多附件 | 源码级核实（同 jar 第 396-404、455-463 行）：原版地形 pass 由 `createRenderPass(name, mainTarget.getColorTextureView(), Optional.empty(), depthView, OptionalDouble.empty())` 建出，**颜色附件恰好 1 个** | `mixin.ownTerrainPass` | ⏸️ **已登记，未实现**（方案 B；§5.0.4 取舍表） |
| **M-05** | `net.minecraft.client.renderer.LevelRenderer` | `prepareChunkRenders(Matrix4fc, boolean)` 与 `prepareChunkRendersIndirect(Matrix4fc, boolean)`（**均 public**，各返回 `ChunkSectionsToRender`） | **只读捕获** | **GAP-003 方案 A 的入口**：拿到地形 draw 数据对象的引用，使我方能在**自己的**多附件 pass 里画地形 —— **不改原版任何渲染行为** | 源码级核实（同 jar 第 849、937 行签名；第 249 行 `fireFrameGraphSetup` **早于**第 271-275 行的 `prepareChunkRenders*` ⇒ 官方事件里**拿不到**该对象，但 pass 体在第 286 行 `frame.execute()` 执行，**晚于**捕获点 ⇒ 时序可行） | `mixin.captureTerrainDraws` | 🟡 **已登记，2026-10-03 实现并取证**（`evidence/h03-…`）：捕获可用 + 时序成立 |

#### 5.0.3 ✅ 多附件**原语**已验证（2026-10-03，MRT 能力验证件）

> 证据：`evidence/h02-mrt-primitive.md`。**这一节只证明原语可用，不等于 GAP-003 完成。**

| 项 | 状态 |
|---|---|
| 多附件 render pass（`RenderPassDescriptor` + N × `withColorAttachment`） | ✅ 可用 |
| 多附件管线（`withColorTargetStates(0, N-1, …)`，本项目**第一条**多附件管线） | ✅ 可用 |
| 片元多路输出（`layout(location=0/1/2) out`） | ✅ 可用 |
| 「三槽拿到可区分内容」 | ✅ 逐槽 R 指纹（0 / ⅓ / ⅔）可量化判读 |
| 设备能力收敛（`maxColorAttachments`） | ✅ 降档 + 显式 WARN |
| 🔴 地形接入多附件 pass | ⛔ **未做** = M-04 |
| 🔴 包的自研 `gbuffers_*` 片元 | ⛔ **未做** |

🔖 **为什么先验原语再谈 M-04**（先测后优，`17-NATIVE` §2–§3）：
源码级核实表明原版主 pass 把**地形、实体、特性、云、描边画在同一个 pass、同一个单附件里**
⇒ 直接把那个 pass 改成多附件，会让所有**原版管线**（都声明 1 个附件）与 pass 不匹配而全部
validation error。**先在我方自己的 pass 里跑通原语**，才知道卡点是真在「后端不支持多附件」
还是在「pass 所有权」—— 这两者的下一步完全不同。

**可关闭**：`mrt.enabled`（默认**关** ⇒ 常规帧零开销，支柱③ B1 ≤ +2%）+
`mrt.viewSlot`（0..2，越界**显式报错**不静默夹取）。

#### 5.0.1 🔴 为什么 M-01 的目标类从 `renderLayers` 改成了 `ChunkSectionLayer#pipeline`

上一版登记表把注入点写成 `ChunkSectionsToRender#renderLayers`。**源码级核实后改判**（2026-10-03）：

| 事实（26.3.0.41-beta sources jar） | 后果 |
|---|---|
| `renderLayers` 的两个 override 形参是**整组共用**的单个值 | 在那里设 override ⇒ 该组**所有层**用同一条管线 |
| `ChunkSectionLayerGroup.OPAQUE = {SOLID, CUTOUT}`（第 9 行），一次 `renderGroup` 就把两层送进同一个 `renderLayers` | CUTOUT 会套用 SOLID 的管线状态 |
| `SOLID_TERRAIN` 无 `ALPHA_CUTOUT` define；`CUTOUT_TERRAIN` = 0.5F；`TRANSLUCENT_TERRAIN` = 0.1F + `BlendFunction.TRANSLUCENT`（`RenderPipelines` 第 349/379/393 行） | 套错的直接后果 = **cutout 树叶/草方块失去 alpha 剔除**，且**半透明地形变成不透明** —— 且不抛任何异常 |

⇒ 按层解析管线的唯一收口点是 `ChunkSectionLayer#pipeline(boolean)`
（`DrawSeparate` 第 176 行、`DrawIndirect` 第 121 行都走它）。M-01 因此落在那里，
`renderLayers` 保留为 M-01b（它的价值不在换管线，而在**往 pass 上绑 uniform** —— 那是 `pipeline()` 拿不到的能力）。

⚠️ **连带核实（容易漏）**：`pipeline(false)` **不只在 draw 时被调**，建网格时也被调
（`SectionRenderDispatcher` 第 76 行、`LevelRenderer` 第 796 行取 `getVertexFormatBinding(0)`）。
派生管线沿用同一 snippet ⇒ 顶点绑定逐项相同 ⇒ 该处行为不变（已随 `evidence/h01-…` 实测）。

#### 5.0.2 🔴 GAP-003（多附件）为什么不能在 M-01 上做

- **管线侧**：✅ **已验**（2026-10-03）—— `withColorTargetStates(0, N-1, …)` 是原版公开 API，
  本项目第一条多附件管线 `vkdisp:pipeline/mrt` 已注册并编译成功。
- **pass 侧**：🔴 原版地形 pass 只有**一个**颜色附件（同 jar 第 455-463 行的 `createRenderPass` 实参）。
  管线声明 N 个附件而 pass 只绑 1 个 ⇒ 驱动层必然报附件不匹配。
- **多附件能力本身**：✅ **已验**（`evidence/h02-mrt-primitive.md`）—— 3 附件 pass + 3 目标管线
  + 3 路片元输出，逐槽 R 指纹量化判读通过。（🔴 原文「0 validation error」已撤回：本机无 validation layer，见 `AGENT_CONTEXT.md` §9.4.15）
- ⇒ 多附件的瓶颈**不在能力、也不在管线，在 pass 所有权**。必须先拿 pass 的所有权 ⇒ **M-04**。

#### 5.0.4 🔴 M-04 的难点被具体化了：原版主 pass 里混着四类 draw

⚠️ 读到这里时容易以为「给地形 pass 加两个附件」就完事了。**不是**（源码级核实，
`LevelRenderer.addMainPass`，同 jar 第 396-404 行起）：

```
FramePass pass = frame.addPass("main");
  → executeSolid(...)        地形 OPAQUE + CUTOUT
  → executeClassicTransparency / executeOit   半透明地形
  → executeOutline(...)      实体描边
  → executeSeeThrough(...)   穿透特性
  → executeAlwaysOnTop(...)  常驻顶层 gizmo
```

⇒ 把这个 pass 改成 N 附件，**所有原版管线**（各自声明 1 个 `ColorTargetState`）都会与 pass 不匹配。
所以 M-04 落地前必须先做**取舍分析**（未做）：

| 方案 | 做法 | 代价 | 入口状态（2026-10-03 实测） |
|---|---|---|---|
| **A. 地形单独一个 pass** | 我方在帧图里另建一个多附件 pass 只画地形，原版主 pass 保持单附件 | 地形 pass 的深度/可见性/顺序语义要自己处理；地形与实体之间没有共享附件 | ✅ **入口已通**：M-05 只读捕获（`evidence/h03-…`）。不改任何渲染行为，ON/OFF 截图逐字节相同 |
| **B. 整 pass 多附件** | 主 pass 加附件，所有走它的原版管线也得跟着派生 | 要为实体/特性/云/描边各派生一份 N 附件管线，改动面与回归面都大得多 | ⏸️ M-04 未开工 |

✅ **取舍结论（2026-10-03）：走 A。** 依据不是偏好，是两条入口的**实测风险差**：

| | 方案 A（M-05） | 方案 B（M-04） |
|---|---|---|
| 需要改原版渲染行为吗 | **否**（只读捕获引用） | **是**（改 pass 的附件语义） |
| 可一键关闭且零视觉影响 | ✅ 实测截图逐字节相同 | 未验 |
| 已取证的硬证据 | 捕获非 null + 时序成立（`h03`） | 无 |
| 猜错的后果 | 无「猜」这一步 —— 附件语义只涉及地形 | 实体/云/天气/世界边界的附件语义**未核实**，猜错即**静默画面错误** |

✅ **上轮的卡点已核实解除**（2026-10-03，方案 A 第 2 步的前置）：
`renderGroup` 需要的两样东西**都能用公开 API 拿到**，不需要 M-04：

| 需要的东西 | 原版怎么拿（`LevelRenderer`） | 我方怎么办 | 核实 |
|---|---|---|---|
| `blockAtlas`（方块图集视图） | 第 531 行 `textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()` | **同一条公开路径**：`Minecraft.getTextureManager()`（public，第 2692 行）→ `getTexture`（public，第 91 行）→ `getTextureView`（public，`AbstractTexture` 第 45 行）。`TextureAtlas.LOCATION_BLOCKS` 是 public static | ✅ 逐个签名核实 |
| `sampler`（图集采样器） | 第 442-447 行**自建**（带各向异性 + `shouldResetChunkLayerSampler` 门控） | **自建同款**：`RenderSystem.getDevice().createSampler(CLAMP_TO_EDGE, CLAMP_TO_EDGE, LINEAR, LINEAR, maxAniso, OptionalDouble.empty())`（public，第 32-34 行）。🔖 **不用原版那个实例**（它是 `LevelRenderer` 的 private 字段，第 146 行） | ✅ 签名核实 |

⚠️ **仍需在实现时注意**（不是阻塞，是语义）：
① 采样器的**各向异性**取决于用户的纹理过滤设置（`optionsRenderState.textureFiltering`），
   我方若硬编码 `maxAnisotropy = 1`，画面质量会低于原版 ⇒ **取值必须对齐用户设置**；
② 图集采样器在原版是**按需重建**的（`shouldResetChunkLayerSampler`），我方若每帧新建会**泄漏 GPU 对象**。

#### 5.0.5 ✅ 方案 A 第 2 步已落地（2026-10-03；**帧图内插 pass 的生产形态亦已通**，见 `h05`）

**已完成并有像素证据**（`evidence/h04-gbuffer-terrain-pass.md`）：

- `bridge.MrtTerrainPass`（新）：我方自己的 pass，调 **public** 的
  `ChunkSectionsToRender#renderGroup(OPAQUE, …)` 把地形画进 **3 个 colortex 附件**；
- `TerrainPipelineApi` 新增 **MRT 变体表**（6 条，与 M-01 的 6 条单附件版同键不同表）；
- M-01 依「活动标记」在两种 pass 里选对应变体；
- 像素三连：`colortex0` = 真地形 / `colortex1` = 纯清屏色（原版 `core/terrain.fsh`
  只有 `layout(location=0) out vec4 fragColor` ⇒ 槽 1/2 理应为空）/ 主目标仍是正常原版渲染。

🔖 **本轮登记的最重要一条技术事实**（踩坑换来的）：
**本引擎是反向 Z —— 自建深度目标必须清到 `0.0`**（原版 clear pass：
`clearColorAndDepthTextures(…, depth, 0.0)`，`LevelRenderer:255`，即 0.0 = 远平面）。
按 Vulkan 惯例清 `1.0`（= 近平面）会让**每个**地形片元被深度测试掉，
症状是「pass 跑通、零报错、画面只剩清屏色」——与「什么都没执行」像素上完全同形。
⚠️ **本机没有 validation layer** ⇒ 这类错误**永远不会有日志**，只有像素能揭穿它。

✅ **2026-10-03 晚更正**（`evidence/h05-readback-flip-and-framegraph-works.md`）：

| 项 | 状态 |
|---|---|
| **帧图内插 pass**（方案 A 的生产形态） | ✅ **已通**。🔖 h04 §9 那句「从未成功」**作废** —— 那是**深度修复之前**的旧观察 + 回读 blit 的 V 翻转 bug 共同造成的误判。教训：**改了共享状态（深度清屏值）后，所有旧的「某路径不通」结论必须重测** |
| 回读 blit 的 V 翻转 | ✅ **已修**：新增 `vkdisp:pipeline/mrtview_noflip`。`fullscreen_flipv` 补偿的是**包 composite 的 OF vUv 语义**，不是引擎取向；采样引擎渲染出的 colortex 时**不能**再翻 |
| 我方 pass 在帧图里的位置 | 🔖 实测：**排在原版主 pass 之前**（`ORDER-MARK` 行号 1161 < 1163，不声明资源依赖所致）。**不是问题** —— 独立 colortex + 独立深度，不与主 pass 共享附件 |

🔴 **仍未完成，不得当已完成引用**：

| 项 | 状态 |
|---|---|
| **让包自己的 `gbuffers_terrain` 跑起来** | ❌ 这才是真正的阻塞。🔴 **原写的「补齐 colortex1/2 的 gbuffer 语义」已被 `h06` 核实推翻** —— 那是 **_Iris_** 的语义；BSL 用 OF 式 `gl_FragData[N]` + `/* DRAWBUFFERS:… */` 映射，法线进 **colortex6**、材质进 **colortex3**，且 `ADVANCED_MATERIALS`/`MCBL_SS` **默认关闭** ⇒ **BSL 默认地形只写 colortex0** |
| 翻译结果能否编译成 SPIR-V | ❌ **未验证**。文本层已通（438 行真实 FSH ⇒ **0 ERROR**、自动合成 **5 个** `layout(location=0..4) out`、收编 **29 条**游离 uniform，`h06`）⇒ 「多附件」与「gl_FragData 改写」**都不是阻塞** |
| 附件槽位数与顺序 | ❌ `MrtPlan.SLOT_COUNT = 3`（按 Iris 定的）**对 BSL 不够**（最多 5 槽），且顺序要**服从 DRAWBUFFERS 而非下标**，否则**静默绑错槽** |
| `sampler3D lighttex0/1` vs 原版 **2D** lightmap | ❌ 结构性不匹配，未处理 |
| 44 条 OF uniform 的取值供给 | ❌ GAP-004 那个块目前**只收编了声明** |
| 半透明地形（TRANSLUCENT 组） | ❌ 未覆盖（实测该组有 249 个 draw） |
| 画面改进 / 性能 | ❌ 无（写自己的 colortex ⇒ 地形被画两遍；代价未测） |

⇒ **方案 A 已通（生产形态）**。下一轮从「帧图内插 pass」转向
**「让 BSL 自己的 `gbuffers_terrain` 跑起来」**（先攻：翻译结果能否编译成 SPIR-V）。
**这也是本轮把 GAP-003 与 GAP-004 分开做的原因**（`18-PARALLEL` H 线「顺序纪律」原本要求同批）：
GAP-004 单独做**并非没有意义**（它的前提正是「派生管线」，没有派生管线就无处挂块），
本轮把「通道是否真的通」「块能否挂上并每帧绑定」变成可验证事实，
而 GAP-003 需要连同 M-04 + 自研 gbuffer 片元一起做，放到下一轮。**未完成项照旧登记，不许当已完成引用。**

**永久禁止登记**（M1 / L11 / X23 / X24）：Sodium / caffeinemc 任何类；第三方区块渲染器；
`RenderSystem` / `GlStateManager` / `GL11`·`GL14`·`GL15` 等底层状态类。

**纪律**：
1. **逐个开启** —— 新增注入点不许一次性全开，否则崩溃无法二分定位（X29）。
2. **每个注入点必须能一键关闭** —— 出问题时能立刻定位到是哪一个（M1 编码约束 ⑤）。
3. **每个注入方法体首行**打 `vkdisp: [注入点名] hit`（静默失败是本类工程头号坑，T10）。
4. **只转发不写业务**（X25）—— 升级时只需改 `bridge/` 一处。

### 5.1 原生工具链（⏸️ **仅 G 系列裁决「采用」后才配**）

> 当前项目**没有**任何原生模块。**在 G 系列裁决为「采用」之前不要配 CMake / cargo**
> —— 那是超前设计（`05-VERSION.md` §4.3）。
>
> **2026-10-02 变更**：用户要求测试 Rust vs Java 差异，故本节从「假设性」改为
> 「**可执行，但时点受闸门约束**」。闸门见 `17-NATIVE.md` §5，裁决阈值见 §5.2。

```gradle
// 仅当 G 系列裁决「采用」后才启用
tasks.register('buildNative') {
    // cargo build --release
}
tasks.register('bindings') {
    // cbindgen → C 头 → jextract → Java 绑定（构建期产物，不入 jar）
}
// 关键：原生构建失败不得让 build 失败（N1 —— Java 路径必须始终能构建）
// 用单独 task，CI 上按平台矩阵跑；缺工具链时跳过而非报错
```

**接线上线顺序（照做，别跳步）**：

```
cargo → cbindgen → jextract → jar 内 /vkdisp/native/<platform>-<arch>/
  → 运行期 System.load → SymbolLookup.loaderLookup() → linker.downcallHandle()
  → 缺符号/缺库 → catch 住 → WARN + 回落 backend-java（N2）
```

打包位置与产物校验见 `17-NATIVE.md` §6。
**不用 JNI**，用 FFM（`java.lang.foreign`，Java 25 正式版）+ `jextract`（T19）。

---

## 6. 验收标准（分阶段）

| 阶段 | 验收动作 | 通过标准 |
|---|---|---|
| **Phase 0** | 启动游戏，注册一个自定义全屏 pass | 屏幕上出现自定义图案（非黑屏、非崩）；**不开包帧时间 ≤ +2%** |
| **Phase 1** | 后处理链 + uniform 传递 | 能实时改参数看到画面变化；开关 pass 生效 |
| **Phase 2** | 加载真实 OF 包的最小阶段 | 包能被识别、`#include` 能解、composite 有效果、不崩；**加载 ≤ 3 秒** |
| **Phase 3** | 影子 + gbuffers + deferred | 中等复杂度包（如 Sildur's Enhanced）基本正确；**无 > 200ms 单帧尖刺** |
| **Phase 4** | 主流包兼容 + 选项 GUI | Complementary / BSL / Sildur 主要效果可用；**开包帧时间 ≤ Iris+OF 的 110%** |

> 性能线的口径与测量规范见 `17-NATIVE.md` §2 与 §7，验收细则见 `08-TESTING.md` §8。

**每阶段必做的静默失败自检**：
1. mixin 有没有真的生效？（在注入点打日志，不要只看"没报错"）
2. 计数是否对得上？（注册的 pipeline 数 / 实际编译成功的数）
3. 着色器是否真的编译了？（打开原版 shader debug 或用 `--debug-shaders`）
4. **失败时不静默降级到"什么都不做"**——必须显式报错或明显降级

---

## 7. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| 原版 Vulkan 后端不给第三方插入 pipeline | 低 | Sulkan 已证明可行；Phase 0 最先验证 |
| OF GLSL 转译工作量被低估 | **高** | 分阶段，Phase 2 只做 composite；UBO 语义先硬编码一组常见 uniform；**先读 Iris 的解析器**（`17-NATIVE.md` §1.3） |
| 原版对 render target 数量/格式有限制 | 中 | 复用 `colortex` 语义时按需降级；给足诊断日志 |
| 老版本 MC 没有 `renderpearl.backend.api` | — | 本方案**锁定 26.3+**，不支持更早版本 |
| 被误认为"又一个 Iris" | 低 | README 明确写"独立实现，与任何第三方着色器加载器/渲染优化模组均无关联" |
| 误抄 GPL / LGPL 代码 | 中 | 本项目 MIT，见 `03-DIRECTION.md` §8 与 `07-CONSTRAINTS.md` §〇；VulkanMod/Sulkan 都只读思路不抄代码 |
| **不装包也掉帧**（着色器模组最不可接受的失败） | **中** | `17-NATIVE.md` §2 把它列为 P0 必过；每阶段测帧时间 |
| **误把冷路径当瓶颈，白写原生库** | **中** | `17-NATIVE.md` §3.2 热度分级 + §5 六问决策树 |
| **原生库导致平台崩 / 缺库即挂** | 中 | `17-NATIVE.md` N1/N2：Java 保底必须始终可用，缺库自动降级 |

---

## 8. 参考模组使用边界（MIT 下：全部只能读，不能抄）

**前提**：本项目许可证 = **MIT**（`07-CONSTRAINTS.md` §〇）。MIT 与 LGPL / GPL 不同族，
因此**三个参考模组的代码一个都不能用**。

1. **VulkanMod（LGPL-3.0）**：❌ **不得复制任何代码**。
   只允许带走「**做法**」这类事实性信息：给 `RenderPipeline` 挂 mixin 扩展接口、
   `ShaderManager.apply` 里有可注入的编译点、GL program id 需要一张映射表。
   拿不准时的判断法：**它告诉你的"该往哪打洞"可以用，它的洞怎么挖的不能抄。**
2. **Sulkan（GPL-3.0）**：❌ **只读架构思路，一行代码都不抄**（含 `.java` / `.glsl` / 资源文件）
3. **Beryl（ARR）**：❌ 完全不可用

**判断标准**：可以借鉴"往哪个方法注入"「用什么 API」这类**事实性信息**（不受版权保护）；
不可以复制"具体怎么写的代码"。前者写进笔记，后者一个字都不落盘。

> ⚠️ **MIT 下的实操尺度比 LGPL 更严**：没有"逐文件标注 + 衍生部分同许可"这条退路。
> 一旦并入就是全项目换证，所以宁可重写一遍，也不要"先抄再改"。

**自检**：提交前对照 `07-CONSTRAINTS.md` §七 —— 有 `[ ] 无复制来的第三方代码` 一项。
