# 04 · 技术规格书

> 配套：`03-DIRECTION.md`（必读前置）、`07-CONSTRAINTS.md`
> **版本权威：`05-VERSION.md`**（支持范围 = MC 26.3 及之后；当前主线 26.3）
> 版本锁定：MC **26.3** / NeoForge **26.3.0.23-beta** / Java **25** / MDG **2.0.147** / Gradle **9.2.1**
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
> 「热度」列决定实现语言：❄️ 冷路径一律纯 Java；🔥 热路径先 Java + 测量，超预算才考虑原生。

### 3.0 组件总览（参考 / 热度 / 语言）

| 组件 | 参考（只读思路） | 热度 | 语言 |
|---|---|---|---|
| `pack/` 格式解析 | **Iris** `shaderpack/parsing/` | ❄️ 冷 | 纯 Java |
| `glsl/` 预处理器（`#include`/`#define`） | **IrisShaders/glsl-preprocessor**（GPL+例外） | ❄️ 冷 | 纯 Java |
| `glsl/` 转译（OF → M GLSL） | **IrisShaders/glsl-transformer**（自定义传染） | ❄️ 冷 | 纯 Java |
| `pipeline/` 管线构建 | Sulkan `runtime/ShaderPipelines`（GPL） | ❄️ 冷（构建）+ 🔥 热（键查找） | 纯 Java |
| `render/` 帧编排 | Sulkan `LevelRenderer*Mixin`（GPL） | 🔥 热 | 纯 Java（原版 API 为主） |
| `config/` `screen/` | 原版屏幕基类 | ❄️ 冷 | 纯 Java |
| `accel/` 加速层门面 | 见 `17-NATIVE.md` §4 | — | ⏸️ 计划预留，非纯 Java（未验证） |
| `bridge/` 原版 API 隔离 | 本项目自定 | — | 纯 Java |

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
  IncludeProcessor.java    // 处理 #include（OF: 相对路径；Iris: namespace:path）
  DefineProcessor.java     // #define / #undef / 条件编译
  ConstEvaluator.java      // OF 的 const int X = ... 选项常量
```

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

### 3.6 加速层（`accel/`）—— ⏸️ **计划预留，当前不建**

> ⚠️ **现在不要建这个包，也不要配 CMake / cargo。**
> C++/Rust 的可行性**尚未验证**（`17-NATIVE.md` 开头状态声明）。
> 本节只记录「如果将来真要上原生，大概是这么个形状」。
>
> **纪律**：先测后优，只做热路径，Java 保底必须始终可用。
> **当前状态：只有 Java 实现，没有任何原生库**（`17-NATIVE.md` §6.1 登记为 0 条）。

```java
accel/                    // ← ⏸️ 计划预留，暂不创建
  VecMathOps.java         // 矩阵 / 视锥运算（热）     ← 先试 Java Vector API
  UboPacker.java          // uniform 块打包（热）       ← 先试直接 ByteBuffer
  PipelineKeyHasher.java  // 管线缓存键计算（热）       ← 先试预计算 / 缓存
  AccelBackend.java       // 选择器（若将来有原生才需要）
  backend/java/           // ✅ 永远存在，默认
  backend/native/         // ⚠️ 可选，可行性未验证
```

**若将来真要启用，硬要求**
- 接口签名只用纯 Java 类型（不暴露 `MemorySegment` 到业务层）
- 原生库缺失 / 平台不匹配 → **自动降级到 Java 并打 WARN**，不许崩、不许静默
- 启动时打印所选后端：`vkdisp: accel backend = java | native(<lib>)`
- 每个原生模块必须有 A/B 开关（`17-NATIVE.md` N4）
- **先过 `17-NATIVE.md` §6.2 第 0 关的可行性验证**

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

---

## 5. 构建配置

```gradle
// build.gradle 要点（官方 MDK 结构，ModDevGradle）
plugins {
    id 'net.neoforged.moddev' version '2.0.147'
}

neoForge {
    version = project.neo_version          // = 26.3.0.23-beta，取自 gradle.properties
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

### 5.1 原生工具链（⏸️ **当前不需要，也不要配**）

> 当前项目**没有**任何原生模块，且**可行性尚未验证**（`17-NATIVE.md` 状态声明）。
> **不要在还没有原生模块时就去配 CMake / cargo** —— 那是超前设计（`05-VERSION.md` §4.3）。
>
> 本节只是记录「万一将来要用」的形状。**真要启用时，先过 `17-NATIVE.md` §6.2 的第 0 关可行性验证。**

```gradle
// 仅当引入原生模块且可行性验证通过后，才考虑下面这些
tasks.register('buildNative') {
    // cargo / cmake 调用
}
// 关键：原生构建失败不得让 build 失败（N1 —— Java 路径必须始终能构建）
// 用单独 task，并在 CI 上按平台矩阵跑
```

打包位置与产物校验见 `17-NATIVE.md` §6.3（同样是假设性设计）。

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
