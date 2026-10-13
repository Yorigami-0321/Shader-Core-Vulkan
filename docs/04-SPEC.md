# 04 · 技术规格书

> 配套：`03-DIRECTION.md`（必读前置）、`07-CONSTRAINTS.md`
> **版本权威：`05-VERSION.md`**（支持范围 = MC 26.3 及之后；当前主线 26.3）
> 版本锁定：MC **26.3** / NeoForge **26.3.0.51-beta** / Java **25** / MDG **2.0.148** / Gradle **9.4.1**
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

> **转译段数说明**：主流水线 **8 段**（上表）。另有**两个按需启用的适配器段**（默认关、诊断/槽位适配性质，
> 不算「第 9 段」）：**第 ⑦½ 段 `DrawBuffersSlotAdapter`**（`h09`，按包源码 `/* DRAWBUFFERS:… */`
> 兑现槽位 → `gl_FragData[k] → location k`）与**第 7¾ 段 `DerivativeProbeAdapter`**（`h14`，
> `dFdx`/`dFdy` 初值改 0 的单变量诊断探针，`finally` 复位）。

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
| 非目录填充 | `aspectRatio = viewWidth/viewHeight`（BSL `vec2(aspectRatio,1.0)` 消费）、`timeAngle = (t%24000)/24000`（与 BSL 夜窗 0.5325–0.9675 = 12780/24000–23220/24000 吻合）、`moonPhase = attributeProbe(MOON_PHASE).index()`（SkyRenderer:125 同源，null 回退 `SkyRenderState`；原版序直传；与 OF 相位序一致性未取证，登记）、**`frameTime` = 最近一次有效帧间隔（秒，沿用上面 0.5s 尖峰截断；尖峰帧沿用旧值而不是回 0）** —— 🔴 h47 白屏机制位：BSL `composite3:196` / `composite5:371,378` 把 `exp2(−frameTime × SPEED)` 当**逐帧混合系数**用，恒 0 ⇒ 系数恒 1 ⇒ `mix(新,旧,1)` 永远返回旧值，而旧值 = 附件初始 0 ⇒ 自动曝光卡在 0 ⇒ `color /= 2×0 + 0.125` = 固定 ×8 增益 ⇒ 整屏削顶 |
| `gbufferPreviousModelView` / `gbufferPreviousProjection` / `previousCameraPosition` | 上一次 gather 的当帧值。🔴 **「链模式下这就等于上一帧」这句是错的，h48z 接线时核实**：`OfUniformManager.gather` 一帧内最多被调 **4 次**（`FrameApi:801`、`FrameApi:1110`、`TerrainPipelineApi:550`、`TerrainPipelineApi:620`），而历史是**每次 gather 轮一次** ⇒ 同一帧内的第 2..4 次拿到的「上一帧」其实就是**本帧**，于是 `gbufferPreviousProjection == gbufferProjection` ⇒ 运动向量恒 0 ⇒ TAA / 运动模糊 / DOF 的重投影退化成「原地累积」。<br>🔖 与它对面的一条已修好的错同样重要：这三项**此前恒 0** ⇒ 包把「上一帧」读成「相机在原点 + 单位矩阵」⇒ 运动向量是整屏假位移。**两个方向的错都会让时序 pass 坏掉，但症状完全不同**（一个是没动、一个是全在动），别再只盯着「恒 0」那一半。<br>⇒ 修法需要一个**真的帧身份**（不是 gather 计数），换世界时与当帧对齐（跨世界的旧相机当历史没有意义）。登记为独立工作项，别在接别的功能时顺手改。 |
| `cloudHeight` | ✅ 公开路径核实（26.3.0.51-beta）：`LevelRenderState.cloudHeight` 是 **public float**（源码第 28 行），原版自己在 `LevelRenderer:554/563` 把它当云层底高传给云渲染 ⇒ 取法与天空重放同源 `gameRenderer.gameRenderState().levelRenderState`。为什么要补：BSL 的 `DrawCloudVolumetric` 拿它当云层下界，恒 0 ⇒ 体积云压到海平面以下 ⇒ 一个像素都看不见（GAP-007 清单项，h48o 起填） |
| `bedrockLevel` / `nightVision` | ✅ 取值隔离在 `NightVisionSupply`（实体/注册表类在测试运行时里会 `NoClassDefFoundError`，这是它单独立类的原因）。`bedrockLevel` = `Level.dimensionType()`（public）→ `DimensionType.minY()`（record 分量，源码第 34 行）；`nightVision` = `LivingEntity.hasEffect(MobEffects.NIGHT_VISION)`（public，原版 `Camera.java:142` 自己就这么读）为真时取 `GameRenderer.nightVisionScale(...)`。🔴 **不能**直接把 `nightVisionScale` 当值喂包：它对「没有夜视」也返回 1.0（源码第 425-428 行走的就是那条分支），而 BSL 用它做增益（`deferred1.glsl:494 color.rgb *= 1.0 + nightVision`）⇒ 全屏永久 ×2。⚠️ OF 自己的精确曲线未取证（X9），登记为近似 v1 |
| **不填充（恒 0 + 一次性 INFO 列名）** | `timeBrightness` / `shadowFade` / `blindFactor` —— 🔴 这三条**不是「我方没找到来源」**：BSL 在 `shaders.properties` 里用 `uniform.float.<名>=<表达式>` + `variable.float` **自己定义**它们，而我方从不求值那 28+13 行（`pack/properties/ShaderProperties.java:103-104` 把它们扫进通用 directives map）⇒ 归 **GAP-021**，不是 GAP-007 的供值问题。真·未取证的只剩 `darknessFactor` / `endFlash*` / `dh*` |

**布局与缓冲（P4.1.3）**：块成员顺序 = 收编声明在前 + 目录缺失在后（`UniformInjector` 发射序，
本表顺序只决定缺失尾部）；BSL 的 composite / deferred / final 收编集各不相同 → **三套
std140 布局、三条环形缓冲**（P4.1.3 双槽 42/24 成员，P4.1.4 补 final 第三槽 24/512），
按 pass 各绑各的。布局由 `glsl/translate/BuiltinsBlockLayout` 从**转译终稿
文本**重解析（F3 冻结契约：`TranslateResult` 不外传 Injector 内部结果，终稿即驱动编译的
真源）。太阳走**原版路径**（非 BSL `sunPathRotation=-40°` 包天空）—— 与当前画面里 vanilla
渲染的天空一致；启用包天空后复审（登记在 13-GAP-REGISTRY.md GAP-027）。

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
> `VertexAttribute` 与 `VertexLayout`（stride=47）
> 沿用本表旧值，缺口单独跟踪；**不许私自改契约**。

---

## 5. 构建配置

```gradle
// build.gradle 要点（官方 MDK 结构，ModDevGradle）
plugins {
    id 'net.neoforged.moddev' version '2.0.148'
}

neoForge {
    version = project.neo_version          // = 26.3.0.51-beta，取自 gradle.properties
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

`vkdisp.mixins.json`（**管线装配层 mixin 已启用，登记表见 §5.0**）：

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

### 5.0 🔴 mixin 注入点登记表（2026-10-12 起：类别不限，仍是强制登记制）

> `07-CONSTRAINTS.md` M1 现行口径（2026-10-12 用户裁决）：**注入哪个原版类都行**，
> 保留的是**登记制 + 可关闭制 + 逐个开启**三条（X28 / X29）。X23（禁注入 Sodium / 第三方）未放开。
> 未登记的注入点一律禁止；登记即生效。
>
> 目标类名/方法名只许引用 `bridge/MixinTargets` 常量，禁止散落字面量（M1 编码约束 ①）
> —— 白名单废除后这条**更重要**：升版排查入口只剩这一张表 + `07` §1.4.1。
>
> 🔖 **M-06…M-14 的出处**：2026-10-12 全仓调研（地形/帧图/天空云/包装载四域并行取证），
> 每条的**行号都对着 26.3 反编译源核过**。改造阶梯见 `19-IMPROVEMENT-PATHS.md` §5A.4。

| # | 目标类 | 目标方法（签名） | 注入类型 | 用途 | 兼容性判定 | 可关闭键 | 状态 |
|---|---|---|---|---|---|---|---|
| M-01 | `net.minecraft.client.renderer.chunk.ChunkSectionLayer` | `pipeline(boolean)`（public，1 参 `multiDraw`） | 装配层 | 把**派生管线**（多附件 / 自定义 uniform）按**层**接到地形 draw 上 —— 零 mixin 无法达成（`02` §5.1） | 源码级核实（26.3.0.41-beta sources jar 第 39-41 行 + `ChunkSectionsToRender` 第 121/176 行）：`layer.pipeline(multiDraw)` 是派生管线被地形 draw 用上的**唯一必经点**，且按层解析 | `mixin.wireTerrain` | 🟡 **已实现（2026-10-03）**：通道已通 + GAP-004 块已挂上、每帧绑定、且已被包片元消费（`h08`）。**2026-10-04 更新**：GAP-003 的包片元接线已打通（`h08`/`h09`）—— 派生 MRT 管线片元 = 包 `gbuffers_terrain`，槽位服从 `DRAWBUFFERS` 映射（转译第 ⑦½ 段）。⛔ M-04（整 pass 多附件，方案 B）仍未做；`h24` 坐实：管线替换本身引发闪烁（GAP-011）。🔴 **2026-10-12 更新（同日两次改判，以最后一次为准）**：本条 **不下线，保留**；下线的是
「靠 `inMrtPass` 静态标志判『在不在我方 pass』」那一族。理由：`PipelineModifierStack` 的结果缓存
按 `(modifier key, 入参管线对象)` 建，**没有任何失效路径**，而 modifier key 只能在启动期注册
（`ClientHooks:826` 的 `PipelineModifiers.init()` + `RegisterPipelineModifiersEvent` 的 `putIfAbsent`）
⇒ **包代次进不了缓存键** ⇒ 我方 MRT 地形管线的身份（片元 = 包的 `gbuffers_terrain`）随切包变，
modifier 会在切包后继续返回**上一代**那条 —— 等于把 `GAP-032` 那类「派生管线与绑定组不同代」
**永久化**。详见 `GAP-045`。
✅ 替代方案已定：**R1a** = `@WrapOperation` 包住 `ChunkSectionsToRender` 第 176 / 121 行的
`RenderSystem.getCompiledPipeline(...)`，handler 顺带拿到外层 `renderPass` 实参 ⇒ pass 身份由**数据**给出，
`inMrtPass`（`MrtTerrainPass:119` + `finally` 纪律 `:532-539`）、空转守卫（`:320-322`，`GAP-040`）、
`MRT_GUARD_FALLBACK`（`TerrainPipelineApi:530`）三件一起删，防护反而变真。
🔖 本条仍然成立的两半：① modifier 通道**确实覆盖**地形 draw（`RenderSystem:106-107` + `ChunkSectionsToRender:121/176`）——
只是它换不掉 M-01；② M-01 牵连**建网格侧**（`SectionRenderDispatcher:76` / `LevelRenderer:796`）这条债仍在（`GAP-043`），
它的前置是 `GAP-007`，与本条改判无关 |
| M-01b | `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` | `renderLayers(ChunkSectionLayer[], GpuSampler, RenderPass, GpuTextureView, GpuTextureView, @Nullable RenderPipeline, @Nullable RenderPipeline)`（private，7 参，末两个是 override） | 装配层 | 把 M-01 派生管线新增的**自定义 uniform 块**绑到地形 draw 的 RenderPass（GAP-004） | 源码级核实：原版 `renderLayers` 只绑 `TerrainUniform` / `Sampler0` / `Sampler2`；驱动层 STRICT_VALIDATION 按**布局**逐条校验，布局多出的条目无人绑即抛 `Missing uniform` | `mixin.bindTerrainParams` | ✅ **已实现（2026-10-03）**：块挂上并每帧绑定；**2026-10-04 更新（`h08`）**：包片元接上后块**已被消费** —— **42 个块成员 + 5 个 sampler 一条不漏绑定，`VkDispBuiltins` 608 字节环由 `OfUniformManager` 按 std140 偏移每帧填值**。🔴 **2026-10-12 更新：登记为「应删」** —— 三条理由：① 它注入的是 **private `renderLayers`（7 参）**，是全表里升版最脆的一个签名（`required: true` ⇒ 改名即崩客户端）；② 它要绑的 `VkDispTerrainParams` **只存在于 MRT 变体布局**（`TerrainPipelineApi` 第 251-253 行），而 MRT pass 内本就能在 `renderGroup` **之前**自绑（同文件第 513 行已是这个形态）；③ 原版路径上那 6 条单附件派生管线**刻意不带**这个绑定组（第 139-143 行）⇒ 注入在原版路径上等于往不认它的布局里塞值。下线即消灭本表唯一的 private-7-参 风险面 |
| M-02 | （待定） | （待定） | 装配层 | 实体 / 天空 draw 走派生管线 | 待源码核实 | `mixin.wireEntity` | ⏳ 未开始 |
| M-03 | （待定） | （待定） | 装配层 | | | | ⏳ 未开始 |
| M-04 | `net.minecraft.client.renderer.LevelRenderer` | `addMainPass(FrameGraphBuilder, FeatureRenderDispatcher$PreparedFrame, GpuBufferSlice, ChunkSectionsToRender, boolean)`（private，5 参） | 装配层 | **GAP-003 多附件的方案 B**：拿到地形 render pass 的所有权，把**整个**主 pass 改成多附件 | 源码级核实（同 jar 第 396-404、455-463 行）：原版地形 pass 由 `createRenderPass(name, mainTarget.getColorTextureView(), Optional.empty(), depthView, OptionalDouble.empty())` 建出，**颜色附件恰好 1 个** | `mixin.ownTerrainPass` | 🎯 **已登记，排为下一轮目标**（2026-10-12 用户裁决：**直接排为下一轮目标**，推翻 §5.0.4 那张 2026-10-03「走 A」的取舍结论 —— 见该表的作废注记）。落地形态：`@WrapOperation` 包住 `addMainPass` 内第 **456-463** 行的 `createRenderPass(...)`，把 main 的 color/depth view 换成我方 colortex 阵列，再配 `PipelineModifier` 给主 pass 内**每一条**管线补 `ColorTargetState`。硬约束（本轮核实）：`com/mojang/renderpearl/frontend/FrontendRenderPass.java` 第 **107-121** 行 —— `setPipeline` 要求 **pass 附件数与管线 color target 数相等、且逐条格式相等**，否则 `IllegalStateException`（X42 同源）。收益：地形不再画两遍（`MrtTerrainPass` 类头自陈「双绘代价未测」），并删掉 `GbufferTarget` 薄壳、`ColortexPool` 双代轮转、`DepthSnapshots` 三个手写取点、AfterLevel 第二档；GAP-022/023/037 的「z 有两个所有者」收敛成单点。**开工第一步是交牵连面清单**（主 pass 内实体/特性/云/天气/描边/OIT 各用哪条管线、要派生成几条 N 附件），不是先写注入。✅ **该清单已于同日交付 ⇒ 见 §5.0.6**，范围据此收敛三条：① 只改 `:455-463` **那一个** GPU pass（`"main"` 这个 FramePass 里其实开着 5 个互相独立的 pass，附件校验是逐 GPU pass 判的）；② 必派生量级 **≈58–62 条**（`RenderPipelines` 全表 195 条里约 30% 可达）；③ 🔴 真实风险不是这个数，而是**六处阶段事件把主 `renderPass` 交给在场任意 mod**（`:536/540/723/731/735/745`）⇒ 必须有「未知管线也补到 N 槽」的**通用派生公式**（§5.0.6 ④：`toBuilder()` + target0 原样 + 补槽用 `RGBA8_UNORM`/`WRITE_NONE`/无混合）。✅ **待核三项已结（2026-10-12，见 §5.0.6 ⑤ 的新表）**：① 不存在「一条 `water_mask` 跨两个 pass」（两支是不同 location 的管线，且所在路径互斥）⇒ 不按 pass 分档；② 默认 renderArea 取「最后一个非 null 颜色附件」的尺寸、**静默**不报错 ⇒ 补槽必须与 target0 同尺寸；③ 后端把深度格式**硬编码**成 `126 = VK_FORMAT_D32_SFLOAT`（`VulkanRenderPipeline:294`）⇒ 我方 gbuffer 深度只能保持 `D32_FLOAT`，GAP-046 的「格式不合」分支因此改成**装配期断言**而不是运行时逐管线比对。**R7 的成本锁解除**。✅ **执行序已交付 ⇒ 见 §5.0.7**：本轮新核出一条决定选型的事实 —— 那次 `createRenderPass` 在 `pass.executes(() -> {…})` 的 **lambda 内**（`LevelRenderer:434-435`），按调用点注入只能瞄合成 lambda（`lambda$addMainPass$N`，GAP-042 那一族的形状）⇒ 注入点改取 backend 无关的那个具体方法 `FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)`，判据全部来自描述符自身的数据 + 一条「接管次数 = 0 就是红灯」的自报。本轮**不写代码**：⑤-a 那步（故意让 `setPipeline` 抛，用它证明真命中）要在真机上看才算成立 —— ✅ **同日下一轮已落 ⑤-a 的代码并真机签掉**（`mixin/MainPassAttachmentMixin.java` + `bridge/MainPassTakeover.java` + `MainPassTakeoverTest`），落地时改的三处、那条 javac 坑、以及**判据从三条收成两条**都记在 **§5.0.7 末注**；⑤-a 的状态：✅ **已落地 + 真机已签**（证据见 §5.0.7 ⑤ 行 a 的那两行原文） —— ⇒ **同日下一轮已落 ⑤-a 的代码**（`mixin/MainPassAttachmentMixin.java` + `bridge/MainPassTakeover.java`，`MainPassTakeoverTest` 7 条），🔴 但**那句抛仍然没在真机看过**：代码落地 ≠ 判据签字，⑤-a 的完成状态是「已落地待取证」（**该句已被同日下一轮的真机取证作废**，见本节上面那行与 §5.0.7 ⑤ 行 a）。落地时改了三处，都记在 **§5.0.7 末注**。✅ **⑤-b 也已落地 + 真机已签（同日再下一轮，2026-10-12）**：通用派生公式 = `bridge/MainPassPipelineDerivation.java`（target0 原样、补槽 `RGBA8_UNORM`/`WRITE_NONE`/无混合、退路是三条纯整数判据 ⇒ 交回原版并计数），挂点从 `PipelineModifier` 改成 M-15 那次 `getCompiledPipeline` 包裹（`select` 收成三支，理由记在 §5.0.7 表 ④ 的落地注）；证据 = `首条补槽管线：SOLID（1 条声明 → 8 槽）` + 那个 `setPipeline` 抛的栈**按预测挪了位置**（`PreparedRenderType.draw:43` ← `executeClassicTransparency:721`）。⛔ **R7 现在停在 ⑤-c**：逐族放开实体 → 粒子 → 云 → 天气 → 边界 → 描边/OIT，第一族的确切位置真机已经给出；⑤-d（关双绘，R7 真正省下的那笔）之前必须先结 `GAP-049` 留下的那件活 —— `MrtTerrainPass#ensureColortex` 的池尺寸现在锚在「另一张主目标」上。此步起的已知代价 = **地形双绘**（主 pass 一遍 + 我方 pass 一遍），默认档 `mixin.ownTerrainPass=false` 时以上全部不进入： |
| **M-05** | `net.minecraft.client.renderer.LevelRenderer` | `prepareChunkRenders(Matrix4fc, boolean)` 与 `prepareChunkRendersIndirect(Matrix4fc, boolean)`（**均 public**，各返回 `ChunkSectionsToRender`） | **只读捕获** | **GAP-003 方案 A 的入口**：拿到地形 draw 数据对象的引用，使我方能在**自己的**多附件 pass 里画地形 —— **不改原版任何渲染行为** | 源码级核实（同 jar 第 849、937 行签名；第 249 行 `fireFrameGraphSetup` **早于**第 271-275 行的 `prepareChunkRenders*` ⇒ 官方事件里**拿不到**该对象，但 pass 体在第 286 行 `frame.execute()` 执行，**晚于**捕获点 ⇒ 时序可行） | `mixin.captureTerrainDraws` | ⚪ **已退役（2026-10-12 R2 第 3 步）**：`LevelRendererChunkCaptureMixin` 整类删除、两个 `@At("RETURN")` 收成 M-06 的一个 `@At("HEAD")`、旧键 `mixin.captureTerrainDraws` 由 `mixin.captureMainPassArgs` 取代（`MixinWiringTest` 现在**断言旧键与旧类都不存在**）。2026-10-03 的取证结论（捕获可用 + 时序成立）**随本条一并迁入 M-06** —— 时序关系没变：HEAD 点在第 277 行，仍早于第 286 行 `frame.execute()` |
| **M-06** | `net.minecraft.client.renderer.LevelRenderer` | `addMainPass(FrameGraphBuilder, FeatureRenderDispatcher$PreparedFrame, GpuBufferSlice, ChunkSectionsToRender, boolean)`（**private，5 参**，第 396-401 行） | **只读捕获 `@At("HEAD")`** | **M-05 的合并替代**：一个注入点同时拿到 `terrainFog` 与 `ChunkSectionsToRender` ⇒ 两点收一点、捕获字段可类型化（去掉 `volatile Object` + `instanceof`）；顺带解掉「我方 pass 从不设雾」（`GAP-038`） | 源码级核实（26.3 反编译源）：第 **277** 行**无条件**调 `addMainPass`，而 indirect 分支在第 272 行、常规分支在第 274 行 ⇒ 两支汇成一点；官方 `FrameGraphSetupEvent`（第 249 行触发）实参只有 builder / targets / cameraState / modelViewMatrix / profiler，**不含**这两个 ⇒ 事件里确实拿不到 | `mixin.captureMainPassArgs` | ✅ **已实现（R2 第 3 步，2026-10-12）**：`mixin/LevelRendererMainPassCaptureMixin`（一个 `@At("HEAD")`，五形参逐个照抄 ⇒ 原版改签名即注入期硬失败）+ `bridge/TerrainDrawCapture` 的两处类型化字段（`ChunkSectionsToRender` + `GpuBufferSlice`）。M-05 的两个注入点、`volatile Object`、调用点的 `instanceof` 与「捕获到别的东西」那条 ERROR **一并删除**；旧键 `mixin.captureTerrainDraws` 由 `mixin.captureMainPassArgs` 取代（`MixinWiringTest` 现在断言旧键不存在）。诊断红利：自报里交出**运行期变体名**（`DrawIndirect`/`DrawSeparate`），比旧 `capturedFrom` 那句「命中了哪个注入方法」更硬 |
| **M-07** | `LevelRenderer` | `chunkLayerSampler`（private 字段，第 **146** 行；其按需重建在第 437-449 行的 pass 体内） | `@Shadow` 读 + `@Accessor` | 复用**原版那个采样器实例**：各向异性跟随用户纹理过滤设置、`shouldResetChunkLayerSampler` 门控一并继承 ⇒ 删 `MrtTerrainPass` 第 889-908 行的自建同款（`GAP-041`：现硬编码 `maxAnisotropy = 1`，画面与原版不可比） | 源码级核实：原版 `createSampler(...)` 的各向异性取 `optionsRenderState.maxAnisotropyValue`（仅当 `textureFiltering == ANISOTROPIC`，否则 1）。🔖 旧文档把「不用原版那个实例」记成设计取舍（§5.0.4 表第 2 行），本轮据实改判 | `mixin.reuseChunkSampler` | ✅ **已实现（R3 第 2 步，2026-10-12）**：`mixin/LevelRendererChunkSamplerMixin` + `bridge/IVkdispChunkLayerSamplerAccess`（访问器返回类型逐字 = 字段类型 `GpuSampler`，不做放宽）。`MrtTerrainPass` 的 `ensureAtlasSampler`/`atlasSampler` 静态字段整块删除，改成**绘制点每帧现取**（原版会 `close()` 并重建 ⇒ 缓存=用死对象）。⚠️ 首帧可为 null ⇒ 判空后本帧不画并 ERROR 一次。🔴 **maxLod=0 那条止血不再作用于地形图集**（原版实例的 maxLod 是 empty）⇒ 若 `GAP-016` 复现，本键是它的 A/B 位，结论回登记表 |
| **M-08** | `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` | draw 变体内的 `drawGroupsPerLayer`（private，第 **97** 行）与其 `drawCount` | `@Mixin` + `@Accessor`（**替反射**） | 把「地形这帧画了几条 draw」从**可静默失效**的判据变成**升版即硬失败**的判据：现状 `MrtTerrainPass` 第 946 / 957 行 `getDeclaredField` + `setAccessible`，失败只 WARN（`GAP-042`） | 源码级核实字段存在；口径与 `07` T10「每处注入点必须能证明被调用」一致 —— 反射那条不满足 | `mixin.terrainDrawProbe` | ✅ **已实现（R3 第 2 步，2026-10-12）**：`mixin/ChunkSectionsToRenderDrawProbeMixin`（目标写 `ChunkSectionsToRender$DrawIndirect`，字段在嵌套变体上、不在基类）+ `bridge/IVkdispTerrainDrawProbe`。`drawCount` 那一半**本来就不必反射** —— `GpuMultiDrawIndexedIndirect` 是 `public record`。⚠️ 只覆盖 indirect 变体（本机走这条）；`DrawSeparate.drawsPerLayer`（第 153 行）未登记未注入，命中它时自报**直说变体名**，不再伪装成「探针坏了」 |
| **M-09** | `net.minecraft.client.renderer.CloudRenderer` | `quadCount`（第 **58** 行）/ `texture`（第 57 行）/ `utb`（第 60 行），均 private | `@Mixin` + `@Accessor`（**替反射**） | 同上：`CloudsIntoGbuffer` 第 314-319 行反射读这三个字段当云侧判据，改名即哑（`GAP-042` 同族） | 源码级核实三字段存在且 private | `mixin.cloudProbe` | ✅ **已实现（R3 第 2 步，2026-10-12）**：`mixin/CloudRendererMeshProbeMixin` + `bridge/IVkdispCloudMeshProbe`，**只交出 `quadCount`**（int，类型逐字相同）。🔴 不再交出 `texture`/`utb`，理由不是省事而是判据等价：① `quadCount != 0` 蕴含 `texture != null`（网格只能在纹理就位后算出），而原版第 198 行早退条件是两者的合取；② `utb` 为 null 时原版第 234 行**抛 NPE**（响亮失败），拿它当判据会把响亮失败误登记成静默失效。另：两者类型都是 `CloudRenderer` 的 private 嵌套类，源码里不可命名 ⇒ 交出只能放宽成 `Object`，本轮不引入这个不确定性 |
| **M-10** | `CloudRenderer` | `render(...)` 内第 **226** 行 `getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy())` 的**实参** | `@Redirect` | 云不再压/还原**全局** modelview 栈 ⇒ 删 `CloudsIntoGbuffer` 第 276-296 行的 `stack.set` + `saved` + `finally` 那一族；「漏还原即污染同帧后续原版 pass」这个事故类别整个消失（现注释自陈「不压 = 云网格锚错 = 放射长条」） | 源码级核实：原版云矩阵确实只从 `getModelViewMatrixCopy()` 取 ⇒ 改实参即可，不动全局栈 | `mixin.cloudMatrixRedirect` | ✅ **已实现（R3 第 2 步，2026-10-12）**：`mixin/CloudRendererMatrixRedirectMixin`，`@Redirect` 目标写**完整描述符**（`CloudRenderer` 上有三个 `render` 重载，只写方法名会命中多个 ⇒ 注入失败）。`CloudsIntoGbuffer` 那次 `stack.set` + `saved` + `finally` 整块删除。🔖 **对原版是恒等变换**（源码级核实：原版只在 `LevelRenderer:738`（main pass 体内）与 `:646`（OIT）画云，两处栈顶本就是装配期相机矩阵 `cameraRenderState.viewRotationMatrix`）⇒ 交出同一个矩阵，原版落点逐字不变。⚠️ 关档 = 交回原版那句：原版不受影响，但我方重放读**裸栈** ⇒ 放射长条复现（旧症状），bridge 侧 WARN 一次说明代价 —— 与 `mrt.terrainAtlasLod0` 同族的「关掉即回到坏行为」臂 |
| **M-11** | `net.minecraft.client.renderer.SkyRenderer` | `render(GpuBufferSlice, SkyRenderState)`（**public**，第 **132** 行）内第 **134 / 135** 行的 `renderTarget.getColorTextureView()` 与 `getDepthTextureView()` | `@Redirect`（改两实参） | 原版天空**直接**落进我方 colortex0 / gbuffer 深度 ⇒ 删 `GbufferTarget`（`RenderTarget` 薄壳 + 每帧 `repoint`）、删 `SkyIntoGbuffer` 里第二个 `SkyRenderer` 实例与自抽 `SkyRenderState`；天空深度从「写一次 + 翻两次」回到「写一次 + 翻一次」（`GAP-037` 根因之一） | 源码级核实：`SkyRenderer` 由 `LevelRenderer` 第 377 行用 `gameRenderer.mainRenderTarget()` 构造，第 139 行**在方法内部**才开自己的 render pass ⇒ 视图取用点就是这两行 | `mixin.skyViews` | ✅ **已实现（R4 第 5 步，2026-10-12）**：`mixin/SkyRendererViewRedirectMixin` 两条 `@Redirect`（各自 `require=1 / expect=1`）把原版第 134/135 行两次取视图换成 `MrtTerrainPass.poolWriteView(0)` + `MrtTerrainPass.depthView()`。✅ 一并删除：`bridge/GbufferTarget`（整类）、`SkyIntoGbuffer` 自持的第二个 `SkyRenderer` 实例与自抽的 `SkyRenderState`（含 `extractRenderState` 调用）、私有天空深度（`ensureSkyDepth` / `D32_FLOAT` 那张）。源码级依据：`SkyRenderer` 的 `renderTarget` 字段**只**在这两行被读（第 66 行声明、全文再无引用）⇒ 换两实参 = 换落点，其余逐字不变。⚠️ **判据未签**：`Mixin apply failed = 0`、`c0@afterSky` 不掉回 0.0611、星点判据不回退都要真机 |
| **M-12** | `LevelRenderer` | `addSkyPass(FrameGraphBuilder, CameraRenderState, GpuBufferSlice)`（private，第 **369** 行） | `@Inject HEAD`（**兜底位，首选不注入**） | 补回原版四道门控：第 370-371 行 `FogType.POWDER_SNOW` / `LAVA` / `doesMobEffectBlockSky`、第 380 行 `state.skybox != Skybox.NONE` ⇒ 现状我方重放天空**一条都不看**，症状是「岩浆里能看到天空」（`GAP-039`） | 🔖 **优先走官方钩子，不是注入**：`CustomSkyboxRenderer.renderSky` 在第 **385** 行、**就在 `"sky"` pass 体内**被调，而该时刻**没有 render pass 打开**（原版 pass 在 `SkyRenderer:139` 内部才开）⇒ 钩子里可直接自开我方 pass，回 `true` 天然与原版互斥（现手工互斥在 `SkyIntoGbuffer` 第 116-119 行）。⚠️ `LevelRenderState.reset` 每帧清 `customSkyboxRenderer` 字段 ⇒ 挂点仍要在装配期做 | —（未启用） | ✅ **首选那条已落地，本兜底位不需要**（R4 第 5 步，2026-10-12）：包天空搬进官方 `CustomSkyboxRenderer.renderSky` —— `SkyIntoGbuffer implements CustomSkyboxRenderer`，在装配期（`MrtTerrainPass.onFrameGraphSetup` 里 `SkyIntoGbuffer.installHook()`，**早于** `afterLevel` 的早退）把单例写进 `LevelRenderState#customSkyboxRenderer`（该字段 public、每帧被 `reset()` 清 ⇒ 每帧重装），回 `true` 即由原版第 385 行的短路给出互斥 ⇒ 四道门控**一条都不抄**（`GAP-039`）。⚠️ **未收口（诚实标注）**：「NeoForge 是否在装配期之后覆写该字段」未真机验证 ⇒ 判据 = 自报行里的 `钩子被调#`（停在 0 就说明安装点要搬到 `addSkyPass`，即启用本条 M-12）。取点读数 `c0@afterSky` 改挂官方 `RenderLevelStageEvent.AfterSky`（两路天空共用同一个「刚画完」时刻） |
| **M-13** | `com.mojang.blaze3d.framegraph.FrameGraphBuilder`（调用点在 `LevelRenderer#addMainPass` 第 **403** 行 `frame.addPass("main")`） | `addPass(String)` 的返回值 | `@WrapOperation` | 把「天空必须排在地形之前」从**实测反推**变成**显式声明**：拿回 `FramePass` 后与原版 `"sky"`（第 383 行）建 `requires` 边 ⇒ 删 ORDER-MARK 顺序日志判据与 `mrt.terrainAfterLevel` 这条 A/B 车道（`MrtTerrainPass` 第 205-215 行的 `disableCulling` + `requires` 猜测式装配） | 源码级核实 `FrameGraphBuilder#resolvePassOrder`：先递归解析 `requiredPassIds` 再 `output.add(pass)` ⇒ **插入序不是依赖**（`h48g` 实测天空被排到地形之后，`c0@afterSky` 只剩 0.0611） | `mixin.pinPassOrder` | 🟡 **半边已实现（2026-10-12 第 5 步前置）**：`mixin/FrameGraphPassOrderMixin` 两个 `@WrapOperation`（`addSkyPass:382` 与 `addMainPass:403` 的 `addPass`）已接住原版 pass 句柄，`bridge/PassOrder` 做**双向登记**补出「terrain requires vanilla-sky」这条边（我方 pass 在第 249 行创建、sky 在 382 ⇒ 只能后到的一方补；`resolvePassOrder` 在 `execute` 才解析所以来得及）。ORDER-MARK 那两条反推日志已删（自报改成「边连上没有」）。⚠️ **本条未收口**：`mrt.terrainAfterLevel` 那条 A/B 车道还在（第 6 步删），且边的效果必须真机看 `c0@afterSky`（72.85~84.50 才对，0.0611 = 没生效）。🔴 R4（第 5 步）落地后这条边变成**承重的**：天空现在就在原版 `"sky"` pass 体内，我方 `vkdisp_gbuffer_sky` 那个 pass 已删 ⇒ 没有这条边就没有「天空先、地形后」，只剩 `h48g` 那个形状。<br>✅ **本条收口（R5，2026-10-12）**：`mrt.terrainAfterLevel` 那条 A/B 车道已删（键 + `afterLevel()` + `drawAfterLevel()` + `FullscreenPassHook` 里的 `paintGbufferAndTerrain` helper 全部移除 ⇒ 钩子里不再有任何地形绘制调用），`mrt.terrainToMain` 保留但注释改成「屏幕不再是证据，剩下的用途是回读侧的 toMain 判据表」。⚠️ 仍待真机：`c0@afterSky` 要落在 72.85~84.50 那一排（`0.0611` = 边没生效）。 |
| **M-14** | `net.minecraft.client.gui.screens.options.VideoSettingsScreen` | `addOptions()`（**protected**，第 **108** 行） | `@Inject TAIL` | 用原版 `OptionsList.addSmall(List)`（第 45 行）/ `addHeader`（第 59 行）把包选项嵌进原版视频设置页 ⇒ 删 `screen/PackPickerScreen` 整类、`PackOptionsScreen` 第 89-291 行的自研双列分页与 `fit()`、`ScreenDriveCommand.Page` 那套页码 | 源码级核实两类与方法均存在。🔴 **官方事件路已核实不通**：`ConfigurationScreen` 是 `final`（nf 源第 112 行）⇒ 加不了选项卡；`ScreenEvent.Init.Post` 只 `addListener`、不进 `HeaderAndFooterLayout` ⇒ 控件重叠 | `mixin.packOptionsInVanilla` | ✅ **已落地（R6 第 6 步后半，2026-10-12，两个提交）**：`mixin/VideoSettingsPackOptionsMixin`（`@Inject addOptions` TAIL，`require=1/expect=1`，`@Shadow` 交出 `OptionsSubScreen#list`）→ `screen/VanillaPackOptionsPage.onAddOptions(list)`。全段只走原版三个装配方法（`addHeader` / `addBig` / `addSmall`），滚动、每行两列、标签绘制、关屏 `applyUnsavedChanges` 都由原版做 ⇒ 自研分页（`turnPage`/`layoutPageSize`/`fitLabel`）不进新页。行内容：光影包选择（`OptionInstance<String>`，候选仍走 `PackPickerCandidates`）+ 枚举型选项成循环行 + 非枚举型保留 `EditBox` 行（能力平移，不静默降级）+「应用并重载资源包」「重置本包」两颗按钮。⚠️ 一条真实的语义差已在页头与日志点名：**换包后本页那些选项行仍属于开屏那一刻的包**，要重开本页（原版没有给我们「重建页」的入口）。✅ **旧通路已删**（第二提交）：`PackPickerScreen` / `PackOptionsScreen` / `PackOptionsDrive` / `ScreenDriveCommand` 四件 + `packOptionsScreen` 键 + `VkDispConfigHotReload` 的 drive 边沿 + `VkDispClient` 那个 `IConfigScreenFactory`（配置屏交回 NeoForge 自动生成的 `ConfigurationScreen`）；🔴 代价 = **无头驱动臂退役**（替身与损失写在 `08-TESTING` 那条引用块里，不当已解决）。⚠️ **判据未签**：原版页内可滚动、可键盘操作、切包后选项仍在，都要真机；`Mixin apply failed=0` 同 |
| **M-15** | `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` | 第 **176** 行（`DrawSeparate`）与第 **121** 行（`DrawIndirect`）里的 `RenderSystem.getCompiledPipeline(...)` **调用** | `@WrapOperation`（handler 读外层 `renderPass` 实参） | 🔴 **R1 的现行形态**：「此刻在不在我方 MRT pass 里」由**数据**（`renderPass` 身份）给出，不再由进程级静态布尔猜 ⇒ 删 `inMrtPass`（`MrtTerrainPass:119` + `finally` 纪律 `:532-539`）、空转守卫（`:320-322`，`GAP-040`）、`MRT_GUARD_FALLBACK`（`TerrainPipelineApi:530`）三件；M-01 保留但**退化成「只按层名查表」**，不再判 `active()` | 源码级核实：两处调用所在的方法都持有 `renderPass`（`renderGroup(ChunkSectionLayerGroup, RenderPass, GpuSampler, GpuTextureView, boolean)` 传进来）；🔖 之所以不走 modifier：`PipelineModifierStack` 的结果缓存按 `(key, 入参管线)` 建且**无失效路径**，而 key 只能启动期注册 ⇒ 包代次进不了缓存（`GAP-045`） | `mixin.passAwarePipeline` | ✅ **已实现（R1a 第 4 步，2026-10-12）**：`mixin/ChunkSectionsToRenderSeparatePipelineMixin`（第 176 行那支，`multiDraw=false`）+ `mixin/ChunkSectionsToRenderIndirectPipelineMixin`（第 121 行那支，`true`）—— 两支合起来才覆盖 `usingMultiDrawIndirectForTerrain` 的二选一（同「两个重载都要注」那条纪律）。handler 用 MixinExtras 的 `@WrapOperation` + `@Local(argsOnly=true)` 同时拿到**正要编译的管线**与**所在方法的 `renderPass` 形参**；判定本体是纯函数 `bridge/PassAwarePipelines.select(layer, multiDraw, ourPass, requested)` ⇒ 「错配必须被拒」不启动客户端就能单测。✅ 一并删除：`inMrtPass` 静态布尔 + 两处 `finally`、`hasExpectedAttachmentCount()`（`GAP-040` 的空转守卫）、`MRT_GUARD_FALLBACK` —— **M-01 因此退化成「只按层名查表」**，只交单附件版。⚠️ 关档 = 我方 8 附件 pass 拿单附件管线 ⇒ `setPipeline` 当场抛（响亮失败，A/B 位）。**判据未签**：真机 A/B（对 `h08` 基线 48.22 / 39.03%）与 `Mixin apply failed = 0` 都要跑一趟 |

#### 5.0.3 ✅ 多附件**原语**已验证（2026-10-03，MRT 能力验证件）

> 证据：`evidence/h02-mrt-primitive.md`。**这一节只证明原语可用，不等于 GAP-003 完成。**

| 项 | 状态 |
|---|---|
| 多附件 render pass（`RenderPassDescriptor` + N × `withColorAttachment`） | ✅ 可用 |
| 多附件管线（`withColorTargetStates(0, N-1, …)`，本项目**第一条**多附件管线） | ✅ 可用 |
| 片元多路输出（`layout(location=0/1/2) out`） | ✅ 可用 |
| 「三槽拿到可区分内容」 | ✅ 逐槽 R 指纹（0 / ⅓ / ⅔）可量化判读 |
| 设备能力收敛（`maxColorAttachments`） | ✅ 降档 + 显式 WARN |
| 🔴 地形接入多附件 pass | ✅ **方案 A 已通**（2026-10-04 `h04`/`h05`：M-05 只读捕获 + 帧图内插 pass 生产形态）；⛔ M-04（方案 B，整 pass 多附件）仍未做 |
| 🔴 包的自研 `gbuffers_*` 片元 | ✅ **已接上**（2026-10-04 `h08`：派生 MRT 管线片元 = 包 `gbuffers_terrain`）；⚠️ 附件数必须**跟随包的输出数**（BSL 默认配置 1 槽、`ADVANCED_MATERIALS` 8 槽，`h09`），**不要按「能力上限 5 槽」建 pass**（X42） |

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
所以 M-04 落地前必须先做**牵连面清单**（🔴 2026-10-12 更新：取舍分析本身已由用户裁决定为「走 B」，
剩下未做的正是这份清单 —— 主 pass 内实体 / 特性 / 云 / 天气 / 描边 / OIT 各用哪条管线、
要派生成几条 N 附件版本、`FrontendRenderPass` 第 107-121 行的「附件数与逐条格式恒等」各自怎么满足。**它是 R7 的第一步，不是先写注入**）：

| 方案 | 做法 | 代价 | 入口状态（2026-10-03 实测） |
|---|---|---|---|
| **A. 地形单独一个 pass** | 我方在帧图里另建一个多附件 pass 只画地形，原版主 pass 保持单附件 | 地形 pass 的深度/可见性/顺序语义要自己处理；地形与实体之间没有共享附件。🔴 **实际付出的代价（2026-10-12 补记）**：地形**被画两遍** + 一整套图外同步（`GbufferTarget` repoint / `ColortexPool` 双代 / `DepthSnapshots` 手写取点 / AfterLevel 第二档） | ✅ **已实现即现状**：M-05 只读捕获（`evidence/h03-…`）+ 帧图内插 pass（`h04`/`h05`）。🔖 M-04 落地后本方案**退役** |
| **B. 整 pass 多附件** | 主 pass 加附件，所有走它的原版管线也得跟着派生 | 要为实体/特性/云/描边各派生一份 N 附件管线，改动面与回归面都大得多 | 🎯 **M-04 已排为下一轮目标**（2026-10-12 用户裁决；先交牵连面清单，见 R7） |

🔴 **取舍结论（2026-10-03）「走 A」已于 2026-10-12 作废 ⇒ 改走 B（M-04 排为下一轮目标，用户裁决）**。
下面两张表**保留作历史依据**，别当现状读。作废理由两条：
① 当时「走 A」的前提是 M1 白名单把注入范围锁在装配层，**该闸门本轮已废**（`07` §1.4）；
② 当时没有把**方案 A 自己的代价**记账 —— 地形被画两遍（`MrtTerrainPass` 类头第 25-28 / 77-78 行自陈「双绘代价未测」）、
`GbufferTarget` 每帧 `repoint`、`ColortexPool` 双代轮转、`DepthSnapshots` 三个手写取点、AfterLevel 第二档 A/B 车道，
全都是为了「不碰原版 pass」而付的账。⇒ 开工第一步：交**牵连面清单**（主 pass 内每条管线 × 附件数），见 M-04 行的落地形态。

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
| **让包自己的 `gbuffers_terrain` 跑起来** | ✅ **已接上**（2026-10-04 `h08`）：派生 MRT 管线片元从原版 `core/terrain` 换成包 `gbuffers_terrain`，顶点侧由适配层 `terrain_pack_adapter.vsh` 按原版顶点格式逐位置产出 9 条 OF varying；像素差 48.22 / 39.03% 证明真正生效。🔖 语义前提（`h06` 核实，仍有效）：BSL 用 OF 式 `gl_FragData[N]` + `/* DRAWBUFFERS:… */` 映射，法线进 **colortex6**、材质进 **colortex3**，且 `ADVANCED_MATERIALS`/`MCBL_SS` **默认关闭** ⇒ **BSL 默认地形只写 colortex0** |
| ~~翻译结果能否编译成 SPIR-V~~ | ✅ **已验证**（`h07` 更正）：项目既有「整包逐阶段 SPIR-V」通路，BSL 地形片元**三个维度目录全部编译成功**（片元 46.7/63.7/79.9KB），整包 `stages=190 ok=190 failed=0` |
| **把包的地形 SPIR-V 接进派生管线** | ✅ **已完成**（`h08`，2026-10-04）：地形 draw 用上包片元，日志 0 条 vkdisp ERROR、0 崩 |
| 附件槽位数与顺序 | ✅ **已解决**（2026-10-04 `h09`）：转译第 ⑦½ 段 `DrawBuffersSlotAdapter` 按包源码 `/* DRAWBUFFERS:… */` 兑现槽位；附件数**跟随包的输出数**（默认 1 槽；`ADVANCED_MATERIALS` 8 槽，两侧都被改成 8）。⚠️ `DRAWBUFFERS:08367`（MCBL_SS + 高级材质同开）需 9 附件 > Vulkan 上限 8 ⇒ **显式拒绝接线**。🔖 教训仍有效：**「能力上限 5 槽」≠「生产实际」**，混用会崩客户端（X42） |
| `sampler3D lighttex0/1` vs 原版 **2D** lightmap | ⚠️ 整包编译 190/190 通过 ⇒ **编译层面不构成阻塞**；只会在**渲染期绑采样器**时暴露 |
| 44 条 OF uniform 的取值供给 | ✅ **已供给**（2026-10-04 `h08`）：42 个块成员真值 + 5 个 sampler 绑定，`VkDispBuiltins` 608 字节环每帧由 `OfUniformManager` 按 std140 偏移填 |
| 半透明地形（TRANSLUCENT 组） | ❌ 未覆盖（实测该组有 249 个 draw） |
| 画面改进 / 性能 | ❌ 无（写自己的 colortex ⇒ 地形被画两遍；代价未测；主目标仍由原版绘制 ⇒ 本轮不产出用户可见画面改进，M-04 未做） |

⇒ **方案 A 已通（生产形态）**；「把包自己的地形 SPIR-V 接进派生 MRT 管线」也已于
2026-10-04 落地（`h08`/`h09`：片元接线 + `DRAWBUFFERS` 槽位兑现 + 布尔选项可见可改）。
**历史注（2026-10-03 当时）**：GAP-003 与 GAP-004 曾按当时的并行规划要求同批做，
后拆开 —— GAP-004 单独做并非没有意义（其前提正是「派生管线」，没有派生管线就无处挂块），
该轮把「通道是否真的通」「块能否挂上并每帧绑定」变成可验证事实。
**未完成项照旧登记，不许当已完成引用。**
当前剩余缺口（2026-10-04，逐条见 `13-GAP-REGISTRY` GAP-003 条目）：M-04 未做 ⇒ 主目标仍由原版
绘制；GAP-007 三条 varying 常量供值；`shadowtex0/1` 绑本 pass 深度占位；仅覆盖 OPAQUE 组；仅验 BSL（X39）。
另：`h24` 坐实 M-01 的管线替换本身引发闪烁（GAP-011）⇒ 这条路线的整体代价问题已浮出。

**永久禁止登记**（M1 / L11 / X23 / X24）：Sodium / caffeinemc 任何类；第三方区块渲染器；
`RenderSystem` / `GlStateManager` / `GL11`·`GL14`·`GL15` 等底层状态类。

**纪律**：
1. **逐个开启** —— 新增注入点不许一次性全开，否则崩溃无法二分定位（X29）。
2. **每个注入点必须能一键关闭** —— 出问题时能立刻定位到是哪一个（M1 编码约束 ⑤）。
3. **每个注入方法体首行**打 `vkdisp: [注入点名] hit`（静默失败是本类工程头号坑，T10）。
4. **只转发不写业务**（X25）—— 升级时只需改 `bridge/` 一处。

#### 5.0.6 🎯 M-04 牵连面清单（2026-10-12 交付；R7 的前置，用户裁决要求的那张表）

> 取证方式：四域并行调研 + 全部结论对着 **26.3 反编译源**（`net/minecraft/**`、`com/mojang/renderpearl/**`）与
> **NeoForge 26.3.0.51-beta 源**逐行核过。行号即证据，未在源码里找到的写在「待核」里，不推断成事实。

**① 前提纠偏（最重要的一条，它把 M-04 的范围缩小了 5 倍）**

`"main"` 是一个 **FramePass**（`LevelRenderer:403`），但它的 `executes` 体内**开了 5 个互相独立的 GPU render pass**：

| GPU pass | 建点 | 颜色附件 | M-04 要不要动 |
|---|---|---|---|
| `"Solid"` / `"Main"` | `LevelRenderer:455-463` | **1**（`mainTarget.getColorTextureView()`） | ✅ **只有这一个** |
| OIT 各 stage | `executeOit`（`:583-673`） | 各自 1~2（`transmittance` 那组已经是 2） | ❌ 不动（`OitRenderPassProvider` 已证明多颜色 pass 可行） |
| `"Outline"` | `executeOutline`（`:750-761`） | 1，且写的是 **`entityOutlineTarget`**（不是 main 的颜色） | ❌ 不动 |
| `"See through features"` | `executeSeeThrough`（`:487-496`） | 1，写 `mainTarget` 颜色视图 | ❌ 不动（但见下方「隐患」） |
| `"Always on top"` + 纯 depth 那次 | `executeAlwaysOnTop`（`:498-522`） | 1 / **0**（`INTEGRATE_DEPTH` 无颜色） | ❌ 不动 |

⇒ `FrontendRenderPass:107-124` 那条「附件数与逐条格式恒等」的校验是**逐 GPU pass** 判定的，
不是逐 FramePass。所以第 405-433 行 `pass.readsAndWrites(...)` 那一堆
（`depthBounds` / `depthBoundsCulled` / `transmittance` / `accumulate` / `oitCloudDepth` / `entityOutline`）
**只是帧图资源声明**，各自的写入者在上面那几个独立 pass 里 ⇒ **与主 pass 加颜色附件不冲突**。

**② 必须派生清单（都发生在 `:455-463` 那一个 GPU pass 内）**

| 分支 | 管线（location） | 取用链 | 现 target 数 / 格式 | 派生条数 |
|---|---|---|---|---|
| 地形 SOLID / CUTOUT | `pipeline/solid_terrain`(+`_multidraw`)、`pipeline/cutout_terrain`(+`_multidraw`) | `LevelRenderer:532` → `ChunkSectionsToRender:176`（或 `:121`）→ `ChunkSectionLayer:39-41` → `RenderPipelines:349-357 / 379-392` | 1 / `RGBA8_UNORM`（`ColorTargetState:21` DEFAULT） | **4**（plain+multidraw ×2；同帧按 `usingMultiDrawIndirectForTerrain`（`:180-183,269`）只用一套） |
| 地形 TRANSLUCENT（非 OIT 才有） | `pipeline/translucent_terrain`(+`_multidraw`) | `LevelRenderer:727-729` → 同上 → `RenderPipelines:393-406` | 1 / RGBA8_UNORM + `TRANSLUCENT` | **2** |
| 地形线框（F3） | `pipeline/wireframe`(+`_multidraw`) | `ChunkSectionsToRender:54-55` → `RenderPipelines:358-371` | 1 / RGBA8_UNORM | **2**（可低优先） |
| 实体 / 方块实体 / 物品 / 文本 / 影子 / 火焰 / 拴绳 / 信标… | `RenderTypes` 工厂给的**非 OIT 常量集**（entity_*、item_*、armor_*、text*、entity_shadow、crumbling、water_mask、beacon_beam_*、end_portal、lightning…） | `LevelRenderer:538`（solid）与 `:721 / :733`（translucent / afterTerrain）→ `FeatureRenderDispatcher:198-265` → `RenderTypeFeatureRenderer:51-67,96-109` → `PreparedRenderType:28-43` | 各 **1** / RGBA8_UNORM（**全部主 pass 管线的 target0 都是这一个格式**） | **≈52** |
| 粒子 | `pipeline/opaque_particle`、`pipeline/translucent_particle` | `QuadParticleFeatureRenderer:89`、`SingleQuadParticle:166-184`，phase 在 `SubmitNodeCollection:448-449` | 1 / RGBA8_UNORM | **2** |
| **云** | `pipeline/clouds`、`pipeline/flat_clouds` | `LevelRenderer:737-739` → `CloudRenderer:197-203,229`，**用的就是 `:455-463` 那个 renderPass** | 1 / RGBA8_UNORM + `TRANSLUCENT` | **2**（OIT 分支 `CloudRenderer:207-223` 不在主 pass） |
| **天气** | `pipeline/weather` | `LevelRenderer:742-743` → `WeatherEffectRenderer:147-151` | 1 / RGBA8_UNORM + `TRANSLUCENT` | **1** |
| 世界边界 | `pipeline/world_border` | `LevelRenderer:747` → `WorldBorderRenderer:152-155` | 1 / RGBA8_UNORM | **1** |
| `prepareTranslucents`（`:451`，实现 `:544-581`） | **不取任何管线**（那一刻 `RenderPass` 还不存在，`:455` 才建） | — | — | **0** |

**量级合计 ≈ 58–62 条**。分母参照：`RenderPipelines` 全表注册 **195** 条（102 条独立 `withLocation` + 31 组 `OitPipelineSet × 3`）⇒ 主 pass 可达约 **30%**。

**③ 🔴 真实风险不是「60 条」，是这一格是**开放集合****

`LevelRenderer` 把**主 `renderPass` 直接交给在场任意 mod**的阶段事件有六处：第 **536 / 540 / 723 / 731 / 735 / 745** 行
（`AfterOpaqueBlocks` / `AfterOpaqueFeatures` / `AfterTranslucentFeatures` / `AfterTranslucentBlocks` /
`AfterTranslucentParticles` / `AfterWeather`）。⇒ 只要装了别的渲染 mod，它的管线就会在**我方那个 N 附件 pass** 里被 `setPipeline` ⇒ 没派生即抛（`FrontendRenderPass:113`）。
**结论：M-04 不能靠「把 60 条列全」收口，必须有一条「未知管线也补到 N 槽」的通用派生。**

**④ 通用派生配方（本清单的产出，四条约束逐条给行号）**

| 约束 | 出处 | 配方 |
|---|---|---|
| 数量必须**相等**（不是「至少」） | `FrontendRenderPass:111-114` | 派生管线必须声明满 N 个槽位 |
| 逐 index **格式必须相等**；但 `attachment == null` 时**跳过**该校验，此时管线槽**必须为 null** | `FrontendRenderPass:116-122`、`RenderPassDescriptor:54-55`（`withUnusedColorAttachment()` 塞 `null`）、`RenderPipeline:288-291`（`withUnusedColorTargetState(i)` 塞 `null`） | 🔖 **两侧必须同时用「真视图」或同时用「unused」** ⇒ 包要写 colortex1..7 的那些帧只能全用真视图 |
| N 的硬上限 **= 8** | `ColorTargetState:22`（`MAX_COLOR_TARGETS = 8`）、`RenderPipeline:190`（`new ColorTargetState[8]`）、`:420`（`Arrays.copyOf`） | 🔴 **N=8 无余量**（正好等于 colortex0..7）；>8 直接越界。这也解释了 `GAP-003` 里 `DRAWBUFFERS:08367`（需 9 槽）为何只能**显式拒绝** |
| 所有「present」的 blend 必须**同一个值**（`Optional.empty()` 可与任何值共存） | `RenderPipeline:421-434`（否则 `IllegalStateException`） | ✅ 补出来的 1..N-1 槽用 `Optional.empty()`；target0 保留原混合（`TRANSLUCENT` 那几条） |
| 补槽不改写 | `ColorTargetState:14-19`（`WRITE_NONE = 0`） | `new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, WRITE_NONE)` —— 与主 pass 里唯一的格式 `RGBA8_UNORM` 对齐 ⇒ **一条公式通吃上表全部分支** |

**配方一句话**：`pipeline.toBuilder()`（`RenderPipeline:135`）→ target0 原样 → `withColorTargetState(i, RGBA8_UNORM + WRITE_NONE + 无混合)` 补到 N。
⚠️ 前提：colortex1..7 必须是 **`RGBA8_UNORM` 且与 main 同分辨率**；⚠️ 隐患两处待核（见 ⑤）。

**⑤ 待核三项 —— ✅ 全部已结（2026-10-12，逐行核 26.3 源 + renderpearl vulkan 后端源，无一条推断）**

| # | 结论（不再是待核） | 证据（行号即事实） | 对 R7 的影响 |
|---|---|---|---|
| 1 | **不存在「同一条 water_mask 管线跨两个 pass」**。`RenderTypes.waterMask()` 按 `useImprovedTransparency()` 二选一给出**两条不同 location 的管线**（`pipeline/water_mask` / `pipeline/oit_water_mask`，`RenderTypes:715-716`），而这两支所在的路径本身互斥：`LevelRenderer:466` 的 `if (!useImprovedTransparency) executeClassicTransparency(…renderPass)`（它内部 `:721` → `FeatureRenderDispatcher:210-228` 执行 `collection.waterMask` 相）跑在**主 GPU pass `:455-463` 内**；`useImprovedTransparency` 那支在 `:471-472 → executeOit → :591-592/:649-650 → executeOitWaterMask(:699-711)` 的**独立 pass** 里。⇒ 同一帧只会走其中一条 | `net/minecraft/client/renderer/rendertype/RenderTypes.java:378-380,715-716`；`LevelRenderer:455-472,591-592,649-650,699-711,721`；`feature/FeatureRenderDispatcher.java:198-238,311-315`；`RenderPipelines:100-108,792-798`（两条都由 `WATER_MASK_SNIPPET` 建、各 1 个颜色目标，差别在 location 与 blend） | 🔴 **不需要**「一条管线两个变体 + 按 pass 选」⇒ 也**不需要**复用 M-15 的 `renderPass` 身份判据。`pipeline/water_mask` 只派生**一条** N 附件变体（已含在 ②的 ≈52 条实体/特性格里）；`pipeline/oit_water_mask` 所在 pass 不动 ⇒ **不派生** |
| 2 | 默认 `renderArea` = **最后一个非 null 颜色附件**的 `getWidth(0)/getHeight(0)`；没有任何颜色附件时才退到深度附件。**不取 min、不求交、不校验** ⇒ 附件尺寸不一致时是**静默改尺寸**，不会报错（本机也没有 validation layer，X35） | `com/mojang/renderpearl/api/commands/RenderPassDescriptor.java:75,79-98`（同一算法在 `backend/vulkan/VulkanCommandEncoder.java:255-268` 再算一遍，那次算出的 `width/height` 传给 `VulkanRenderPass` 当视口/尺寸，`:337`）⇒ 两处口径一致，都是「最后一个非 null」 | 派生公式**必须**让补上去的每个槽与 target0 **同尺寸**（我方 `ColortexPool` 本来就按同一 `main.width/height` 建 ⇒ 成立）。任何「某槽用小尺寸」的想法都会把整个主 pass 的视口改掉，而症状是画面被裁/被拉伸，不是异常 |
| 3 | 后端把**深度格式硬编码**：有深度写 `126`（= `VK_FORMAT_D32_SFLOAT`）、有模板写 `VK_FORMAT_D32_SFLOAT_S8_UINT`、无深度写 `0` —— **从不读附件的真实格式** | `com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline.java:294`（126，硬编码字面量）、`:318`（无深度 = 0）、`:330`（模板 = `VK_FORMAT_D32_SFLOAT_S8_UINT`） | 🔴 我方 gbuffer 深度**只能保持 `D32_FLOAT`**（今天 `MrtTerrainPass` / `SkyIntoGbuffer` 建 `TextureTarget` 时就是 `GpuFormat.D32_FLOAT`）⇒ 换深度格式属「不要动：后端分叉」那一格。连带修正 GAP-046：所谓「格式不合时交回原版路径并计数」的**可比对象不存在**（管线侧恒为 `D32_SFLOAT`）⇒ 那条判据要改成**装配期断言**「我方池深度格式 == `D32_FLOAT`」，不是运行时逐管线比对。GAP-022/037 的「深度口径单点化」因此只能在同一格式内做（谁清、谁翻），不能靠换格式解决 |

**⑥ 对排期的影响**：三项已结 ⇒ **允许给 R7 报成本**（此前「待核 1 未结不许报」的那道锁解除）。M-04（R7）的**清单已交付** ⇒ 按用户裁决它仍是下一轮目标，但范围已从「改一个 pass 的附件」
收敛为「① 只改 `:455-463` 那一个 GPU pass + ② 通用派生公式覆盖开放集合 + ③ 待核 1 决定要不要按 pass 分档」。
🔴 待核 1 未结之前，**不许**给 R7 报成本。

#### 5.0.7 🎯 M-04（R7）的**执行序**（2026-10-12 交付；§5.0.6 那张清单的下一步，用户裁决要求的「先交清单再写注入」里的后半）

🔴 **本轮新核出来的一条事实，它决定机制选型**：`LevelRenderer:455-463` 那次 `createRenderPass(...)`
**不在 `addMainPass` 的方法体里，而在 `pass.executes(() -> { ... })` 那个 lambda 内**
（`pass.executes(` 在第 **434** 行、lambda 体从第 435 行开始，`createRenderPass` 在 455）。
⇒ 任何「按调用点注入」的写法（`@Redirect` / `@WrapOperation` + `@At("INVOKE")`）能瞄准的**方法**
只有编译期的合成 lambda（`lambda$addMainPass$N`，N 随方法排布变号）—— 那就是 `GAP-042`
那一族「改名即哑」的形状，只是这次会硬失败而不是静默。**不选它。**

| # | 项 | 结论（源码级） |
|---|---|---|
| ① | 注入点 | **`com.mojang.renderpearl.frontend.FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)`** —— `CommandEncoder` 上那几个便利重载都是 `default`（`api/commands/CommandEncoder.java:24,28,36,50`），最终一律收敛到这个 abstract 方法（`:53`）；`FrontendCommandEncoder` 是 backend 无关的那层实现（Vulkan/GL 各自再往下）⇒ **一个具体方法、不绑后端**（对照「不要动：后端分叉」那条：不注 `VulkanCommandEncoder`）。 |
| ② | 判据（必须来自调用本身的数据） | 描述符自带：`colorAttachments().size() == 1` + 那个附件的 `textureView()` **就是** `mainRenderTarget` 的颜色视图（对象身份）+ `label()` ∈ `{"Main","Solid"}`（`:458` 那行给的就是这个串）。三条一起用：视图身份排掉「别的单附件 pass 也写 main」（Sky / SeeThrough / AlwaysOnTop / 后处理链 / 原版 clear）里地址相同但语义不同的那些，label 排掉同图的其余 pass。⚠️ label 是**字符串**，Mojang 改了就让接管静默不生效 ⇒ 配一条自报计数（口径同本轮新加的 `钩子被调#`）：**`main pass 接管次数 = 0` 就是红灯**，不许当成「今天的画面」。 |
| ③ | 命中后做什么 | **重建描述符**再转发：target0 原样（含它的清屏语义与 `ColorTargetState`）、补 1..N-1 槽 `RGBA8_UNORM` + `WRITE_NONE` + 无混合、深度沿用原那条、`renderArea` 沿用原那条（**不**重算 —— §5.0.6 ⑤ 第 2 条：默认尺寸取「最后一个非 null 附件」，所以补槽尺寸必须与 target0 一致，这条做成装配期断言）。 |
| ④ | 管线侧 | 主 pass 内每条管线都要有 N 个颜色目标 ⇒ 走 `RenderSystem` 的 `PipelineModifier` 家族（`GbufferPipelineSwaps` 那条既有通路）+ §5.0.6 ④ 的**通用公式**（`toBuilder()` + target0 原样 + 补槽）。`GAP-045` 那条「缓存键无失效」在这里**不构成阻塞**：主 pass 里取的全是**原版**管线（位置与 target 数固定），逐代变化的包管线不在这条 pass 里。未知 mod 管线由通用公式覆盖；派生失败（`N > 8` 无余量、target0 格式非 `RGBA8_UNORM`）⇒ **整条交回原版并计数**（`GAP-046` 收窄后的写法；深度那一维不比，改成装配期断言 `== D32_FLOAT`，见 ⑤ 第 3 条）。🔖 **⑤-b 落地时挂点改了（不是省事，是被数卡住）**：这一行原本写「走 `PipelineModifier` 家族 / `GbufferPipelineSwaps` 那条既有通路」，实际接进的是 **M-15 已有的那次 `getCompiledPipeline` 包裹**（`PassAwarePipelines.select` 的第三支）。根因与 `GAP-045` 同一条：modifier 的 key **只能在启动期注册**，而槽数 N 来自「接管之后那条描述符」= 运行期的数 ⇒ 启动期注册不了；能同时拿到「管线本体 + N + pass 身份」的一刻只有 M-15 那一处。副作用（正面）：两套管线不再混用的收口点与补槽点是**同一个纯函数**，单测里三条分支都能各断一次。 |
| ⑤ | 分步（每步一提交，红树不提交） | **a** 只换描述符、**故意不加**派生 ⇒ 预期 `FrontendRenderPass#setPipeline` 当场抛附件数不匹配 —— 那是「注入确实命中了那个 pass」的最便宜证据（响亮失败，不是画面猜）。✅ **已签（2026-10-12 真机）**：日志 `主 pass 的颜色附件已换成我方 colortex 阵列：8 个（label='Main'，尺寸=854x480）`，一秒后崩在 `FrontendRenderPass#setPipeline:113` —— `IllegalStateException: Render pass color attachment count must match pipeline color target state count.`，栈顶是 `ChunkSectionsToRender$DrawIndirect.render:120` ← `LevelRenderer.executeSolid:532`（= 主 pass 体内那一笔，与预测逐字一致）。⚠️ 签这一步**需要 ≥2 槽的包档**（BSL 开 `ADVANCED_MATERIALS` ⇒ `slots=8`）：单槽档下 `slotsUsable` 会**拒绝接管并计数**（那是设计 —— 1 槽的交换是恒等变换，换不到证据只白担风险）。<br>**b** 通用派生公式接进 modifier，先让**地形两条**过（`solid/cutout` × plain/multidraw），此步起地形仍是双绘。✅ **已落地 + 真机已签（2026-10-12 同日下一轮）**：公式在 `bridge/MainPassPipelineDerivation.java`（target0 逐字原样、补槽 `RGBA8_UNORM`+`WRITE_NONE`+无混合、`refusalReason` 三条纯整数退路 ⇒ **整条交回原版并计数**），挂点**不是** `PipelineModifier` 而是 M-15 已有的那次 `getCompiledPipeline` 包裹（理由见表 ④ 的落地注）；`PassAwarePipelines.select` 收成三支 —— 我方 MRT pass 查表 / 被接管的主 pass 补槽 / **都不是 ⇒ 逐字原样返回**。证据两行：`[M-04/⑤-b] 首条补槽管线：SOLID（1 条声明 → 8 槽）`，随后同一个 `setPipeline` 抛的栈**挪了位置** —— `PreparedRenderType.draw:43` ← `drawFromBuffer:29` ← `LevelRenderer.executeClassicTransparency:721`（= 经典透明那笔，⑤-c 的第一族按预测在它自己的调用点响）。⚠️ 这一步的已知代价= **地形双绘**（主 pass 一遍 + `MrtTerrainPass` 自绘一遍），⑤-d 才关掉；`mixin.ownTerrainPass=false`（默认）时第三支永不进入。<br>**c** 逐族放开：实体 → 粒子 → 云 → 天气 → 边界 → 描边/OIT 交替；每族一条独立判据（谁没派生到就会以 `setPipeline` 抛或 validateDraw 缺 uniform 的形式响）。✅ **第一族已落地 + 真机签掉（2026-10-12 同日再下一轮）**：注入点 = `mixin/PreparedRenderTypePipelineMixin.java`（包 `PreparedRenderType#draw` 第 **43** 行那次 `getCompiledPipeline`，`@Local(argsOnly = true) RenderPass` 给身份），判定入口 = `PassAwarePipelines.selectForMainPass` —— 🔖 **不复用 `select(layer, …)`**：渲染类型的名字里有 `solid`/`translucent` 这种**与 `ChunkSectionLayer` 层名同字**的串，拿名字去查地形多附件表就会在有非地形 draw 落进我方 gbuffer pass 的那天把地形管线塞给不相干的一族（`MainPassPipelineDerivationTest` 里有一条守卫专门钉这件事）。<br>⚠️ **顺带改掉 ⑤-b 的一处性能口径（登记为 `GAP-051`）**：`derive` 跑在原版的编译缓存<b>之前</b>（`RenderSystem:106-120` 才是查缓存的地方）⇒ 不记忆就是**每次 draw 建一条新管线**（分配 + 把 `PipelineCache` 变成每帧新增记录）；现在按**入参对象身份**记一层，槽数一变或条数过 256 就整表作废。类头那句「每条管线一次」是上一轮写错的，已就地更正。<br>✅ **判据（崩点按预测挪到下一族）已过**：日志 `[M-04/⑤-c] PreparedRenderType 取管线 hits=1`、`首条补槽管线：vkdisp:pipeline/terrain_solid_multidraw（1 条声明 → 8 槽）`，而那个 `setPipeline` 抛的栈从 `executeClassicTransparency:721`（上一族）**挪进了 `executeSolid`**：`QuadParticleFeatureRenderer.drawLayers:89` ← `executeGroup:79` ← `FeatureRenderDispatcher$PreparedGroup.execute:394` ← `LevelRenderer.executeSolid:538` ← `lambda$addMainPass$0:465` ⇒ **`PreparedRenderType` 这一族（全部 RenderType：实体/粒子/方块层/水/玻璃…）在主 pass 里已经能过附件校验**，下一族就是 feature 渲染器那一条（它不走 `PreparedRenderType`）。<br>🔖 **本轮还把取管线点数清了**（`GAP-045` 那条一直挂着「30 处未独立复核」）：`grep -rn "getCompiledPipeline" net/minecraft` = **34 处**，全集与「哪些在主 pass 里」见本节末的新表。<br>**d** 关掉双绘：`MrtTerrainPass` 不再自开 pass、不再 `renderGroup(OPAQUE)` ⇒ 删 `MrtTerrainPass` 的自绘路径与 `ColortexPool` 双代轮转里对应的一侧（**这才是 R7 真正省下的那笔**），并删 `M-01` 的层名查表退化件。<br>**e** 深度口径单点化：`GAP-022`/`023`/`037` 同轮做（谁清、谁翻、`depthtex0/1/2` 各在哪一刻取）。 |
| ⑥ | 为什么这五步不能压成一步 | a 与 b 之间必须留一个「已知会抛」的提交 —— 因为「接管有没有生效」在这条路上**没有别的证据**；c 的六族各自有判据，压在一起出错分不开（本仓 h48/h50 那一串教训的形状）。 |

⚠️ 本轮**不写这段代码**：⑤-a 要在客户端上看那个 `setPipeline` 异常才算完成，而真机这一趟还没排上 ——
先有执行序，后有注入（这是 §5.0 那条裁决原文的顺序要求）。

> 🔖 **⑤-a 落地时的三处改动与一条 javac 坑（2026-10-12 同日下一轮）**：
> ① 挂点**收窄**：表 ① 那行写的是「包 `createRenderPass(RenderPassDescriptor)`」，落地包的是**6 参重载内部第 95 行**对它的调用 ⇒ 我方直接递描述符的那三处（`MrtTerrainPass:439/637`、`FrameApi:1317`）从这个点**物理看不见**，判据因此不需要再长一层「这是不是原版」。
> ② 开关用**登记过的键名** `mixin.ownTerrainPass`，🔴 默认**关**（开着=崩是设计，绝不能进默认档；取证档也不建议在 ⑤-b 之前开）。
> ③ §5.0.6 ⑤ 第 2 条那条「补槽必须与 target0 同尺寸」做成了可执行闸门 `MainPassTakeover.slotsUsable`（不一致 ⇒ **拒绝接管 + 计数**，不是硬塞给后端）；深度这条 ⑤-a **不动**（沿用原那条），留给 ⑤-e。
> ④ 🔴 **判据从三条收成两条（真机逼的，登记为 `GAP-049`）**：第三条「那个附件就是 `gameRenderer.mainRenderTarget()` 的颜色视图」在实机上**永不成立** —— 一次自报点名过 `附件=0x11cf0e8a 主目标=0xc9c725d`（identityHashCode 不等）。原因在 `LevelRenderer:210`：主 pass 用的是 `targets.main.get()`，而那条 handle 从 `importExternal("main", …)` 起又被 253/383/404 行的 `readsAndWrites` 换过手 ⇒ **不能假定它等于 `gameRenderer.mainRenderTarget()`**。这一腿降级成**交叉核对**（自报里 `视图同源=` 一直是 0，看得见），身份由「单附件 + label」两腿担；`GAP-049` 要求 ⑤-b 之前把「我方到底该拿哪张主目标」定清（池的尺寸/格式现在全按后者建，恰好同尺寸才没出事）。
> ⚠️ 踩到一条 javac 25 的自爆：把 `RenderPassDescriptor` 的**嵌套类型**写进声明再挂 `@Nullable`（`Outer.@Nullable Inner`）会让 `TransTypes` 抛 `AssertionError: Incorrect number of arguments; expected 2, found 0` —— 症状是**测试编译**崩而不是这一行的语法错。写法改 `var` 即避开（`MainPassTakeover` 里那条注释是出处）。

> 🔖 **⑤-c 的取管线点全集（2026-10-12 本轮逐处数出来，`grep -rn "getCompiledPipeline" net/minecraft` = 34 处）**
> —— 替掉 `GAP-045` 那条一直挂着「30 处，来自子代理统计、未独立复核」的口径。形状统一是
> `renderPass.setPipeline(RenderSystem.getCompiledPipeline(X))` ⇒ **每一处都能看到 `renderPass`**（形参或字段），
> 于是「pass 身份由数据给出」这条路可以原样复制到任何一族。
>
> | 族 | 位置（sources jar 行号） | 在主 pass 里？ | ⑤-c 的状态 |
> |---|---|---|---|
> | 地形两条 | `chunk/ChunkSectionsToRender:121, :176` | ✅ | 已过（M-15 + ⑤-b 补槽） |
> | **全部 RenderType**（实体/粒子/方块层/水/玻璃/文本…） | `rendertype/PreparedRenderType:43` | ✅ | ✅ **本轮已过**（一处顶十几族） |
> | 特性渲染器（四边形粒子） | `feature/QuadParticleFeatureRenderer:89` | ✅（`executeSolid`） | ✅ **已过**（同日第二族，崩点从 `executeClassicTransparency` 挪进 `executeSolid`） |
> | 云 | `CloudRenderer:229`（收敛点：私有 `render(RenderPass, RenderPipeline)`，经典与 OIT 都走它） | ✅ | ✅ **已过**（第二趟把 `renderClouds` 打开 ⇒ `CloudRenderer#render=4174` 次取管线、0 崩 ⇒ 云的管线补槽后过了 `setPipeline`）；`:215` 那句 `BLIT_DEPTH_DURING_DEPTH_BOUNDS` 在 `renderOit` 内 ⇒ 归 OIT 一族 |
> | 天气 | `WeatherEffectRenderer:165`（同一个收敛点形状：私有 `render(state, RenderPass, RenderPipeline)`） | ✅ | ✅ **已过**（`/weather rain` 之后 `WeatherEffectRenderer#render=9637` 次取管线、0 崩 ⇒ 雨的管线补槽后过了 `setPipeline`） |
> | 世界边界 | `WorldBorderRenderer:158`（`render`） | ✅ | 🟡 **已放开，本趟未走到**（镜头离边界远 ⇒ `state.alpha == 0`，那句根本不执行）；`:170` 是 `renderOit` 里另一份逐字相似的代码 ⇒ 归 OIT 一族 |
> | 方块色立方图 | `CubeMap:69` | ❌ **自己开 pass**（`createRenderPass(() -> "Cubemap", …)`） | **不该补**（🔖 本行原来误标成「在主 pass 里」，本轮按 javap + 源码逐字改掉） |
> | 深度合成 / OIT / 描边 | `LevelRenderer:518`（`Integrate always on top depth`，**自己开 pass**）、`:670/:690/:694/:706`（OIT 那四条）、`DebugCrosshairRenderer:72/:78` | ❌ 各自开 pass / 只在开改进透明时 | **不该补**（同上：`518` 那条本行也误标过）；OIT 四条要等「用户开 OIT」那一臂 |
> | 天空 | `SkyRenderer:167, :180, :256, :274, :298, :314` | ❌（自己的 sky pass） | **不该补**（M-11 那两条判据担） |
> | GUI / 后处理 / 动画图集 / 光照图 | `GuiRenderer:388`、`GameRenderer:283, :284, :286, :742`、`PostChain:114`、`PostPass:121`、`Lightmap:84`、`texture/SpriteContents:388, :392`、`texture/TextureAtlas:189` | ❌ | **不该补**（补了就是「modifier 看不到 pass ⇒ 全帧一起坏」那个坑） |
>
> ⇒ **⑤-c 的净结论（同日第三~五族放开之后重算）**：主 pass 内的**非 OIT** 取管线点已经**全部**接上补槽
> （地形两条 + `PreparedRenderType` + 特性渲染器 + 云 + 天气 + 边界 = 7 处、5 个注入类），
> 剩下的全在「要用户开改进透明才会走到」那一侧：`CloudRenderer:215`、`WorldBorderRenderer:170`、
> `LevelRenderer:670/690/694/706`（4 条 OIT 合成/裁剪），以及 `DebugCrosshairRenderer`（调试十字准星，自己开 pass）。
> 🔖 「不该补」那一半同样是判据：判据不只是「补到了」，还要能证明**没补错地方** ——
> 立方图与 `LevelRenderer:518` 两条本轮从「在主 pass 里」改成「自己开 pass」，就是这一半在起作用。
>
> ✅ **第三~五族已落地（同日再下一轮，2026-10-12）+ 真机状态分三等，不含糊**：
> `CloudRendererPipelineMixin` / `WeatherEffectRendererPipelineMixin` / `WorldBorderRendererPipelineMixin`
> （各包一处私有/公开 `render`，形参给 pass 身份）。**天气=已过**（`/weather rain` 之后
> `WeatherEffectRenderer#render=9637`、0 崩 ⇒ 雨的管线补槽后真的过了 `setPipeline`，`派生=10 交回原版=0`）；
> **云 / 边界=已放开但未取证**（`renderClouds` 关着、镜头离边界远 ⇒ 那两句根本没执行，只有「apply 成功 +
> 不崩」这一半）。⚠️ 这一等**不许写成「已过」**：判据要求的是「取管线那一刻真的被补过」。
> 🔖 顺带一条纪律的正面样本：边界的描述符我第一版把 `Vec3` 写成 `org.joml.Vec3`（正确的是
> `net.minecraft.world.phys.Vec3`），`require = 1` 让它在**启动期**就报 `could not find any targets matching`
> 而不是静默不生效（`GAP-047` ⑤）。
>
> ✅ **同日第二趟补证（2026-10-13）**：把 `run/options.txt` 的 `renderClouds` 从 `"false"` 打开 ⇒
> `分站点={CloudRenderer#render=4174, WeatherEffectRenderer#render=4174, PreparedRenderType#draw=67774, QuadParticle#drawLayers=4278}`、
> 0 崩 ⇒ **云那一族也从「已放开未取证」升到「已过」**。🔴 **边界仍是第三等**：要让 `WorldBorderRenderer#render`
> 执行需要把玩家带到边界力场附近（`/worldborder set` + 传送），而这一趟 mcpfabric 连接器**掉线**
> （`mcp_list` 里已无 minecraft 服务器 ⇒ 桥在，但游戏没在跑时连接器不出现；见 memory 的 Windows 车道条）
> ⇒ 边界这一族留到下一趟能驱动游戏时补。⚠️ 取证档跑完全部回退：`ownTerrainPass=false`、
> `ADVANCED_MATERIALS` 行删除、`renderClouds` 改回 `"false"`（游戏退出时会把它写成 `"true"`，必须手动改回）。
>
> ✅ **第二族已落地 + 真机已签（同日再下一轮，2026-10-12）**：`QuadParticleFeatureRenderer#drawLayers`
> 那处（`mixin/QuadParticleFeatureRendererPipelineMixin`）—— 就是上一段说的「真机指出来的下一处」。
> 三条新事实：
> ① **目标方法是 `private static` ⇒ handler 也必须 `static`**，否则类加载期就 `InvalidInjectionException`
> （`non-static callback method … targets a static method which is not supported`），且**与开关无关**
> ⇒ 默认档也起不来（`GAP-047` ④ 已记，这是那一族的第五条形状，第一次撞是在启动期而不是渲染期）。
> ② **观测面改成「分站点计数」**（`PassAwarePipelines.onMainPassSiteHit(site)`）：一趟真机就能同时给出
> 每族各自活没活 —— 本机读数 `{PreparedRenderType#draw=215070, QuadParticle#drawLayers=8130}`
> （地形那两处的计数在 M-15 自己的 `hits` 里，两个口径不合并）。
> ③ 🔴 **第一次「不崩」的帧**：两族放开后客户端进世界并稳定跑（`主 pass 接管=28288` 帧、0 ERROR、
> 0 mixin apply 失败），截图是一张正常的白天画面 —— ⚠️ **但这一条不能读成「主 pass 全族都过了」**：
> 这一帧里根本没走到云 / 天气 / 边界 / 立方图那几处（截图天空无云），它们仍会在自己的调用点抛。
> 「没崩」在这一步只等于「已放开的族过了 + 场景没碰到未放开的族」，判据要分开写。

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
