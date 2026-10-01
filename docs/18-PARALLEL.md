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
   ✅ 接原版 GameRenderer 相机（cameraRenderState 驱动视图 + 菜单显式回退 + 锚点；F5 位移 4 格 →
      几何比值 0.505/0.510 = 理论 0.502/0.526 四位吻合；pose changed 埋点 3 条，vkdisp ERROR=0）
      ⏳ 仍缺：yaw/pitch 连续转动的像素判据（Weston X 焦点 None，XTEST 无法重新聚焦，待可聚焦环境补跑）、
      太阳/月亮方向的光空间（X9 修正：原版 26.3 全 jar 源检索无任何阴影贴图/光空间系统 ——
      「接入原版 LevelRenderer 光空间列表」是基于错误前提的缺口，不存在可接入的原版列表；
      重定义为按 04-SPEC §3.4 自建 ShadowPass 列表，见下方 P3.1 完整块）、PCF 软阴影
   ⏳ 深度测试剔除（真几何）、多颜色附件（colortex0..N）、多目标池复用
   ✅ P3.1 光空间列表（01-DEV-LOOP §10 P3.1 完整交付）（随 `ac83f0b` 提交验收通过）｜验收 = 08-TESTING §5
      「光空间列表非空；阴影贴图内容合理（不是全黑/全白）」。设计：
      ① **X9 前提修正**（本轮实测）：对合并 jar 全部 .java 源检索
      `lightSpace|shadowMatrix|shadowProjection|shadowModelView|cascade` = **0 命中**；
      `shadow*` 类仅实体投影斑（ShadowFeatureRenderer / entityShadow 渲染型）——
      原版 26.3 **没有**阴影贴图与光空间列表（MC 原版本就不渲染世界阴影贴图）。
      故 P3.1 完整交付 = 按 04-SPEC §3.4 自建 ShadowPass 的**光空间列表**（级联条目），
      不是接入不存在的原版列表。
      ② **列表结构**（新增业务类 `shadow/LightSpaceList`）：`Entry = {cascade 序号,
      shadowModelView, shadowProjection, near, far, lightTravelDir}` —— V/P 分矩阵存储
      （04-SPEC §3.2 `shadowModelView`/`shadowProjection` 双矩阵形态的数据层第一步）；
      `build(dir)` 保证非空（零向量方向 → 显式抛出，T11 不静默）；当前单级联
      （far=8、正交 ±1.2/±1、zZeroToOne —— 与已验收光空间矩阵数值逐位等价）。
      ③ **接线**（FrameApi）：每帧 build → 列表空则 ERROR + 跳过本帧影子链（防御分支）
      → `uLight = P₀ × V₀`（数值等价旧链，画面应逐像素不变）→ 首帧打
      `light-space list ready: size=… source=…` 埋点（「列表非空」的日志判据）。
      ④ **方向来源如实登记**：当前 = 固定占位方向（`source=fixed-placeholder`）；
      太阳方向（`EnvironmentAttributes.SUN_ANGLE` 已 javap 核实，度数、经
      SkyRenderer 的 R_YP(−90°)·R_XP(θ)·(0,1,0) 推出方向公式）与多级联 CSM 分割
      **登记为本轮后缺口** —— 未做像素判据前不接（X9，不猜）。
      实测（冷路径 `./gradlew build` exit=0，**424 tests** 全绿（+9 = LightSpaceListTest 9/9）；
      runClient `-PquickPlay` 世界内取证，`run/logs/latest.log` vkdisp ERROR=0）：
      首帧 `light matrix buffer created (content=light-space view-projection from LightSpaceList,
      bytes=64)` → `light-space list ready: size=1 source=fixed-placeholder dir=(0.48000002, -0.8,
      0.35999998) cascade0 near=0.1 far=8.0 ortho=[-1.2, 1.2]x[-1.0, 1.0] zZeroToOne=true`（列表非空
      的日志判据）；`pipeline count check: registered=6, compiled=6 (aligned)`、
      `shadow sample chain executed (854x480)`、进世界后 `camera source=vanilla GameRenderer` +
      `camera anchor captured`。F2 截图（854x480，sha256 `3ea6c38fc5431596…`）：非黑 10598 像素
      （2.6%）、全白 0；色调桶 **48 / 57 / 164** 三分量 = 红四边形受光（0.9×54.2）/ 绿四边形阴影
      （0.35×164.2）/ 绿四边形受光（0.9×182.4）—— 明暗双峰、非全黑非全白，且**桶值与 P2.4 主菜单
      默认轮（48/57/164）逐值相同** = 列表化后 uLight 数值等价的像素级证据。⚠️ 覆盖率与 P2.4 A/B
      不同（10598 vs 29294 非黑）属**取景差异**非回归：P2.4 A/B 在主菜单占位相机取景（其 log
      `vanilla GameRenderer` 0 命中），本轮 quickPlay 进世界、锚点俯视（pitch=90, fov=23.8°），
      同色调桶按透视缩小后覆盖一致可解释。
      未覆盖：空列表防御分支未在运行时触发（固定方向常量结构性不可达，靠
      `LightSpaceListTest` 零向量/非规数用例 + lightSpaceMatrix() 抛错分支静态覆盖）；
      太阳/月亮方向与多级联仍按 ④ 登记缺口。
   ✅ P3.2 gbuffers 接管第一步：地形走自定义渲染目标（01-DEV-LOOP §10 P3.2 完整交付）
      （随本轮提交验收通过）
      ｜验收 = 08-TESTING §5「地形/实体走自定义目标而非原版目标 | 调试视图」。设计
      （①–③ 全部 javap / 合并 jar 源核实，X9）：
      ① **钩子 = NeoForge `FrameGraphSetupEvent`**：LevelRenderer.java:249
         `ClientHooks.fireFrameGraphSetup` 在目标包（`targets` bundle）初始化后、vanilla
         clear/sky/main pass **加入帧图之前**触发；javap API = `getFrameGrapBuilder()` /
         `getTargetBundle()` / `getCameraState()` …。事件携带 `com.mojang.blaze3d` 类型 →
         处理器必须放 **bridge/**（T5 / 18-PARALLEL §7.1 业务禁触 blaze3d），新增
         `bridge/SceneCaptureApi`（@EventBusSubscriber，沿用 FullscreenPassHook 注册形态）。
         （FullscreenPassHook 头注 ② 对 FrameGraphSetupEvent 的否决是针对「帧图画图案」
         —— 当时 vanilla clear 会抹掉图案；本轮用途是**换目标让 vanilla 自己的 pass 写进
         我方纹理**，clear 抹的是我方要清的背景，结论不冲突。）
      ② **接管方式**：事件里懒建 / 按主目标尺寸 resize
         `TextureTarget("vkdisp scene", RGBA8_UNORM, D32_FLOAT)`（与原版 `MainTarget` 格式
         javap 逐位一致：color=RGBA8_UNORM、depth=D32_FLOAT）→
         `targets.replace(LevelTargetBundle.MAIN_TARGET_ID, builder.importExternal("vkdisp_scene", scene))`
         （replace 字节码 = 直接写 `main` 字段，官方突变 API）。地形 pass
         （`addMainPass` 打开的 "Main"/"Solid"）、see-through、always-on-top 全部在**执行期**
         取 `targets.main.get()`（源 453/477/481 行核实）→ 地形颜色+深度落进我方纹理，
         原版主目标不再收到地形 = 「而非原版目标」。
      ③ **自清屏**：vanilla clear pass 的 executes **硬编码**清 `gameRenderer.mainRenderTarget()`
         （源 256-260 行），换目标后**不会**清我方纹理 → 事件内直接
         `clearColorAndDepthTextures(black, depth=0.0)`（帧图执行前、无 pass 打开，P-1f 规则内）。
         深度不清 = 地形深度测试读陈旧数据必坏，此步不可省。**深度清 0.0 不是 1.0**：
         renderpearl 是反向 Z（`DepthStencilState.DEFAULT = GREATER_THAN_OR_EQUAL`，javap 核实，
         vanilla clear pass 同值 0.0）——首轮按 1.0 清 → GEQUAL 全败 → 地形零像素全黑（实测根因，
         已修）。
      ④ **帧链输入**：Pass 3（包 composite）`InSampler` 在**世界内且已捕获**时换成 scene
         纹理（否则回退 offscreen1 fixture —— 菜单/未捕获），来源切换打一次埋点（T11）；
         Pass 1/2 影子链原样保留（P3.1 验收对象 + P3.3 素材）。画面方向不做纸面推断 ——
         首轮截图实测定（P-1f 矩：反了就翻，留证据）。**实测 = 镜像 → 双管线**：
         顶点是管线静态状态，新增 `pipeline/composite_scene`（= composite 片元 +
         不翻转 `fullscreen` 顶点），Pass 3 按输入源选；fixture 路径 flipv 基线不动。
      ⑤ **如实登记缺口**：SkyRenderer 构造期持原版主目标引用（源 377/134 行）→ 天空直写
         原版目标**不进捕获**（存档在末地、天空=虚空黑 → 本轮截图不可见；主世界黑天空
         后续接）；OIT/improved-transparency 路径部分直读原版主目标深度（默认关，开时另测）；
         HUD/手部被 Post 链整体覆盖为 P0.3 起既有语义，非本轮回归；仅世界内生效（菜单
         走 fixture 回退）。
      实测（runClient -PquickPlay 三跑，evidence/p32_run{1,2,3}.log + p32_scene_run{2,3}.png）：
      跑1 `scene capture wired: 854x480 …` / `composite input source: fixture→scene` 切换埋点
      与预期一致，但截图**全黑** —— 根因 = 反向 Z（见 ③ 修正：深度清 1.0 → 0.0）；
      跑2（depth=0.0）地形进画面（末地石头顶视图，mean_luma=136.3）但**上下镜像**：
      世界轴对齐方块边缘实测角度 {−30.7°, +58.3°} vs yaw=−30.75° 推算期望 {+30.7°, −59.3°}
      符号整体翻转 → flipv 不适配 vanilla 帧图目标；
      跑3（scene 输入换**无翻转顶点**双管线）边缘 {+29.7°, −61.5°} ≈ 期望，且
      `mean|run3 − flipV(run2)| = 0.000`（像素级精确镜像）闭环证明方向修复。
      跑3 终态：registered=7, compiled=7 (aligned)、light-space list ready 仍在、
      vkdisp WARN/ERROR=0（08-TESTING §5「地形走自定义目标而非原版目标」画面证据 =
      pass3 采 scene 输出，原版主目标不再收到地形）。
   ✅ P3.3 deferred 链：各步输入输出正确（01-DEV-LOOP §10 P3.3，2026-09-30 三跑取证，
      实测见 ⑤ 下方）｜验收 = 08-TESTING §5「deferred | 每步的输入纹理是上一步的输出」
      +「composite | 链顺序正确，最后一步写入主目标」。设计
      （① 脚手架事实全部仓内源码核实，X9；② 方向推导只合成 P-1f + P3.2 两条**已实测**
      规则，不新增猜测）：
      ① **X9 脚手架核查**：
         - `pack/ProgramStage.DEFERRED("deferred", 4)` 已存在（族序 …→DEFERRED(4)→
           COMPOSITE(5)→FINAL(6)）；`ShaderPackService.collectPrograms` 按
           `shaders/<名>.fsh/.vsh` 文件名配对 → fixture 增加 `deferred.fsh/.vsh`
           即成 program "deferred"，parse 命中枚举不归 UNKNOWN；
         - `ShaderPackCompiler.compile` 遍历 `pack.programs()` **全部程序**（源 169 行，
           两阶段逐个 compileStage，缺阶段 = 显式跳过非失败）→ deferred 自动参与冷路径
           编译，编译器零改动；
         - `PackCompositeSource.generate` 只产出 composite 单资源
           （`firstCompositeFragment` 按 qualifiedName 过滤 FRAGMENT）→ 需扩双源；
         - `VkDispVirtualPack.VirtualPackResources` 单资源 `shaders/composite.fsh`
           （类 javadoc「单资源」）→ 需扩双资源；
         - D 线 `OfGlslTranslator` 对**所有阶段**统一跑（UniformInjector 补内建块无
           程序名门槛，源 126 行）→ deferred.fsh 与 composite.fsh 同等待遇，管线绑定组
           必须同款带 BUILTINS + InSampler（P2.3 终态形态，X9：「片元有块布局没有」
           方向未实测，所以照抄 composite 布局）；
         - fixture 在 **gitignored** `run/shaderpacks/`（.gitignore:68 `run/`）：
           `vkdisp-fixture-dir/` + `vkdisp-fixture-zip.zip`，本轮两处同步加 deferred
           （§7.6 自造包，不入库）。
      ② **方向推导（净翻转守恒）**：P3.2 实测 scene→主目标直连 = **0 翻转**（noflip 正确）；
         composite 固定 flipv 读中间目标 = **+1**（P-1f ③「中间目标 → 主目标 FLIP」）。
         链 scene→deferred→offscreen2→composite→main 要与直连同向，且 composite 一步
         固定贡献 +1 → deferred 步必须也贡献 +1（1+1 ≡ 0 (mod 2) = 直连的 0）→
         **deferred 管线 = flipv 顶点**。等价表述：scene 行序与我方中间目标行序差一次
         翻转，deferred 把 scene 翻成中间目标约定、composite 再翻回 —— 两连翻净零，
         画面不镜像（不引入新猜测，是 P-1f ③ + P3.2 实测的代数合成）。
         ⚠️ **本推导已被 p416 推翻（见 P4.1 记录 ⑩）**：deferred 不改写 colortex0
         （P4.1.2 绑 viewC 首跑全黑实证），链 composite 的彩色主输入一直是**场景色**
         而非 deferred 输出 —— deferred 的 flipv 从不参与彩色净翻转，「1+1」错算了一跳，
         实际链 = scene→[composite flipv]→…→main = **净 +1**，自 P3.3 起画面颠倒
         （p416_run1 强制位姿实测地平线 204 = 翻转）。终态规则 = **顶点翻转跟随彩色
         采样源**（场景源 → noflip），deferred/final/链 composite 均 noflip，见 ⑩。
      ③ **链拓扑**（只在「世界内 && scene 已捕获 && 所选包声明 deferred」时开新步；
         其余路径既有基线**逐字节不动**）：
         - 世界内 + 包有 deferred：Pass 3 deferred（scene → offscreen2，~~flipv~~
           **noflip**，p416 更正）→ Pass 4 composite（链彩色源 = 场景 →
           `composite_scene` **noflip**，p416 更正；~~offscreen2 → main，flipv~~）；
         - 世界内 + 包无 deferred：维持 P3.2 `composite_scene` 直连 scene（基线）；
         - 菜单 / 未捕获：维持 P2.4 fixture → composite flipv（基线，p416 保留不动）；
         - Pass 1/2 影子链不动（P3.1 验收对象）。
      ④ **脚手架改动**：
         - `PackCompositeSource`：`DEFERRED_PROGRAM="deferred"`；`Result` 扩
           `deferredSource`（永不 null：取不到 → 内置 passthrough）+
           `hasDeferredProgram`（真实取到包 deferred 片元才 true）；包声明 deferred 但
           片元阶段编译失败 → WARN + 按无 deferred 处理（T11：不硬开一个喂兜底源的步）；
           整体兜底路径两源均 passthrough、hasDeferred=false；
         - `VkDispVirtualPack`：新增资源 `shaders/deferred.fsh` —— required 管线必须
           总有源可编，**即使总开关关闭也服务 passthrough**；`generateSources()` 一次
           生成双源 + `volatile hasDeferredProgram()` 暴露给 bridge；证据行
           `deferred source ready: present=… pack=… bytes=…`；
         - 第 8 条管线 `pipeline/deferred`（fragment=`vkdisp_pack:deferred`、
           vertex=`vkdisp:fullscreen_flipv`、绑定组 BUILTINS+InSampler 同 composite）——
           顶点是管线静态状态且 deferred 只在世界内跑 → 只需 flipv 一种变体；
           注册器 7 步全量重编号 1/7..7/7 → 1/8..8/8（deferred 插在 4/8）；
         - `FrameApi`：`offscreenTarget` 扩 slot 2（无深度，颜色专用，仅链路执行时
           懒建）；新 deferred pass（世界内 + hasDeferred 才执行，scene → slot2，
           pass 标签 `3 (deferred: scene -> offscreen2, pack deferred)`，composite
           标签相应 `3`/`4` 随链路态）；Pass 4 输入三态（deferred 输出 / scene 直连 /
           fixture offscreen1）+ 管线二选一（composite flipv / composite_scene noflip）
           + 切换埋点沿用 `composite input source`；链路启动一次性埋点
           `deferred chain wired: scene -> offscreen2 -> main`；`isPipelineReady`
           增补 deferred 注册 + 编译校验。
      ⑤ **证据计划**（08-TESTING §5「每步的输入纹理是上一步的输出」）：
         - **日志链（每步输入=上一步输出的书面链）**：
           `deferred source ready: present=true pack=…` → `pipeline registered (4/8)
           … vkdisp:pipeline/deferred` → `pipeline count check: registered=8,
           compiled=8 (aligned)` → 进世界 `deferred chain wired: scene -> offscreen2
           -> main` → `composite input source: deferred output (P3.3)`；
         - **像素变换（变换穿过整条链才可见）**：fixture `deferred.fsh` = 已知色调
           `× vec3(1.0, 0.7, 0.7)`（G/B ×0.7，R 不动）→ 终帧 = P3.2 基线通道值 ×0.7：
           预期 R≈141.0、G≈135.22×0.7≈94.7，**R/G 比 1.043 → ≈1.49**
           （composite 的 0.9 系数在分子分母同乘、抵消）—— deferred 的输出确实是
           composite 的输入（否则色调变换到不了屏幕）；deferred 没进链则 R/G 停在基线
           1.043（判据可检伪，不是循环自证）；
         - **方向不回归**（取证按环境事实调整，见实测末条）：原计划「yaw=−30.75° 顶视
           网格角度复测」因跨会话位姿不可保持而无法复现 → 以 1+1≡0 净翻转代数（②）
           + P3.2 已证方向基线（跑3 像素级镜像闭环）+ 锚定帧边缘取证（竖边/地平线
           近零倾角）组合登记；同位姿镜像逐像素判定 = 未覆盖；
         - vkdisp WARN/ERROR=0、registered=8 compiled=8。
      实测（2026-09-30，/tmp/p33a|p33b|p33c_runclient.log 三次 runClient -PquickPlay）：
      - **日志链 · 链开**（p33a 18:17 / p33b 18:32，fixture 6 条目 3313B）：
        `deferred source ready: present=true pack=vkdisp-fixture-zip bytes=1384` →
        `pipeline registered (4/8): vkdisp:pipeline/deferred` → `pipeline count check:
        registered=8, compiled=8 (aligned)` → `deferred pipeline wired: fragment=
        vkdisp_pack:deferred vertex=vkdisp:fullscreen_flipv (P3.3 chain step; flipv by
        net-parity)` → 进世界 `deferred chain wired: scene -> offscreen2 -> main
        (pack deferred)` → `composite input source: deferred output (P3.3)` —— 每步
        输入=上一步输出的书面链完整；最后一步写入主目标沿用 P3.2 已证 Pass 4 结构；
      - **日志链 · 链关**（p33c 18:43，deferred 改名 .off / zip 4 条目 409B 源）：
        `deferred source ready: present=false … bytes=409` + INFO `包 'vkdisp-fixture-zip'
        不含 deferred 程序，P3.3 deferred 步按未启用处理（链路保持 P3.2 直连）` →
        `composite input source: scene capture (P3.2 terrain)`；两分支 vkdisp
        WARN/ERROR 均 = 0，registered=8 compiled=8 (aligned)；
      - **像素判据 · 同材质 A/B**：链开锚定帧 `/tmp/p33_chain_anchor.png`（sha256
        a4d41356…，854×480，R=69.3602 G=45.6997 B=43.3047）沙岩壁龛内景 R/G=1.5146
        （全帧 1.5177）；链关帧 `/tmp/p33_direct_anchor.png`（sha256 002bf828…，
        R=33.4590 G=32.6650 B=32.3779）露天沙岩地面 R=142.138 G=136.356 B=134.253
        R/G=1.0424（≈P3.2 基线 1.0430，绝对值也吻合 ⑤ 预言 R≈141/G≈135.2，+0.8%/
        +0.9%）→ 同材质 G/R 抑制比 0.6602/0.9593=**0.688**、R/G 抬升比 1.5146/1.0424
        =**1.453**，对 ×vec3(1.0,0.7,0.7) 预言 0.700 / 1.429 偏差 −1.7% / +1.7%（⑤
        R/G≈1.49 预言命中）—— 色调变换必须穿过 deferred→composite 整条链才可能上屏；
        链关全帧中性灰 R/G=1.0243（无变换残留），判据可检伪；
      - **锚定链**（链开帧）：相机日志 `camera anchor captured: … yaw=-109.500046,
        pitch=6.2999973, fov=38.150047deg` eye y=1.6194 == 退出存档 == 取证帧
        （p33b 全程仅 #1 落地沉降，位姿稳定）；帧构图 = 玩家所在 2 格高沙岩壁龛
        （存档区块 NBT 解码：列 (8,−8) y=0..1 空气、y=2 顶板、y=−1 地板 → feet y=0
        与画面自洽），锚定帧近竖墙角边族 87.0°；链关帧地平线族 {0.0°}（≥60px 边
        −0.4°..+0.5°）—— 无倾斜/翻转信号；
      - **环境事实（登记）**：本环境 F2/焦点注入不可用（focus-largest 命中 8192×8192
        假窗口、PointerRoot 焦点回弹致 XTEST 键落空）→ 取证统一改用
        `x11_capture.py --window-id 0x60000f`（XGetImage 非黑帧、零输入副作用）；
        会话存在**非指令位姿移动**（p33c 18:43:44 后相机被外部输入挪走，退出存档
        (12.7,4.0,−13.06)/(−62.1°,−26.1°) ≠ 锚 (8.5,1.62,−7.5)/(−109.5°,6.3°)，
        位姿日志 30 条上限截断）→ A/B 按**同材质锚定**（沙岩→沙岩）而非同位姿；
        同位姿逐像素镜像判定 = 未覆盖（需输入隔离环境重跑）；fixture 取证后已复原
        （deferred 回名 + zip 6 条目 3313B）。
   🟡 P4.1 主流包 BSL（01-DEV-LOOP §10 P4.1「BSL 主要效果可用（与 Iris 对比截图）」，
      转译层 P4.1.1 ✅、驱动层 P4.1.2 ✅（见 ⑥，判据①②达成）、uniform 上传 P4.1.3 ✅
      （见 ⑦）、final 步 P4.1.4 ✅（见 ⑧）、properties 条件编译 P4.1.5 ✅（见 ⑨）、
      画面方向矫正 P4.1.6 ✅（见 ⑩），剩余画面质量子项（141 阶段矩阵）留后续）｜验收 = 08-TESTING §4/§5。
      设计（X9 实测取证，全部非猜测）：
      ① **包结构实测**（临时探针跑真 zip `run/shaderpacks/BSL_v10.1.8.zip`，gitignored）：
      91 program / 284 option / profiles=[]（shaders.properties 解析失败：`#if 表达式含
      非法字符 '>'`，登记项 —— ~~已由 ⑨ 闭环，见下~~ **已闭环（见 ⑨）**）/ dims=[world-1, world0, world1] / 182 个编译阶段；
      扫描序 BSL ZIP 先于 fixture → BSL 转译成功即自动入选。
      ② **P4.1.1 转译层三修复**（随本轮提交）：DefineProcessor 函数宏组号错位
      （`No group 4` IndexOutOfBoundsException 使全部 program 转译失败）+ FUNC_DEFINE
      收紧为 `(` 紧邻名字；UniformInjector 游离非透明 uniform **收编进 VkDispBuiltins 块**
      （文本原样移动、原行抹空保行号、透明类型留原位、重名/跨行/多语句不收、INFO 计数）；
      PackCompositeSource 维度偏好 world0 > 根 > 其它 + deferred 同维度配对（TreeMap 序
      `world-1/…` 先于 `world0/…`，旧首成者会选中下界）。
      ③ **P4.1.2 驱动层工作清单**（按 p41a runClient 错误原文逐条登记，见④；
      **✅ 2026-10-01 全部交付，见 ⑥**）：逗号多名声明全名登记（`uniform float far, near;`
      类只记首名 → 后名被二次注入 → 块内 duplicate，BSL program/composite.glsl L27/32/39
      与驱动报文逐一吻合）；`#version 120 → ≥140` 升级（shaderc 硬门槛，连带
      `location qualifier on output`）；包 varying 显式 `layout(location)`（SPIR-V 硬要求）；
      之后复验驱动矩阵 + VkDispPackScan 全量矩阵（事件在失败重载上未送达，本轮未触达）。
      ④ **本轮实测（P4.1.1，2026-09-30）**：单测 434 全绿；探针
      `compile stages=182 ok=182 fail=0`（修复前 composite 片元 0 成功）、
      `generate → pack=BSL_v10.1.8 fallback=false hasDeferred=true sourceBytes=24505`
      （=world0/composite 片元产出）；runClient（/tmp/p41a_runclient.log exit=0）：
      `composite 程序选中 'world0/composite'` → `composite source ready … bytes=24505`
      → `deferred source ready: present=true … bytes=8271` → 管线注册 1/8..8/8 ——
      选中链全绿；随后 3 条 required 管线 ×2 重载 6 次
      `Couldn't compile pipeline`（原文 6 类：#version<140 / duplicate member×3 +
      nameless block 撞全局名 / location 版本不支持 / SPIR-V requires location）→
      `Failed to load required shader programs` 资源包摘除重载 ——
      **转译通过 ≠ 驱动通过**，此原文即驱动级证据。
      ⑤ **P4.1 完成判据（不变）**：管线 registered==compiled → 进世界 BSL 视觉生效
      （对照 fixture 基线可检伪）→ 与 Iris 对比截图（环境缺 Iris = 已登记限制）；
      ~~uniform 数值仍全零上传（OfUniformManager 缺口）→ sunVec 系效果可能 NaN~~
      **已闭环（见 ⑦，P4.1.3）**；~~profiles 解析失败（`#if` 含 `>`）仍留后续子轮~~
      **已闭环（见 ⑨，P4.1.5）**。
      **2026-10-01 结果**：判据① 三跑均 `registered=8, compiled=8 (aligned)` ✅；
      判据② run2 黑（0.951/0.466%）→ run3 可见（11.901/30.69%）单变量归因 ✅；
      判据③ 环境缺 Iris = 照旧登记限制 ⚠️。
      ⑥ **P4.1.2 驱动层交付（2026-10-01，四修 + 三跑闭环）**：
      转译扩七段 —— **VersionAdapter**（#version 三段式升 410：<140 必升 / 140–409 无
      SSO 扩展升 / ≥410、ES 不动）+ **IoLocationAdapter**（片元 in/out、顶点 out 显式
      layout(location)；顶点属性按 04-SPEC §4 留名字绑定）；**UniformInjector** BLOCK_MEMBER
      扩逗号多声明全名登记（recordMemberNames/declaratorNames → declaredAtLine +
      adoptedNames，后名不再二次注入）；驱动侧四修：**A** 顶点 fullscreen*.vsh 补
      `layout(location=1/2) out sunVec/upVec`（接口链接）、**B**
      `PipelineApi.PACK_FRAGMENT_SAMPLERS` 18 名片元采样器布局超集
      （PipelineBuilder :277 单向 SPIR-V→layout，多项合法）、**C**
      `setPackSamplerUniforms` 两处 draw 前全量 setUniform（validateDraw 遍历
      boundPipeline.uniforms 缺一即抛）、**D** OF 语义视图映射（链内 colortex0→
      sceneColorView、gaux1→viewC=colortex4 身份、其余 16 名同场景 view）。
      实测三跑（`evidence/p412-driver-layer.md` 全文 + sha256 + 关键行原文）：
      run1 Missing uniform 15679 + fullscreen pass failed 15679 → run2 +C 后 0/0
      但纯黑（0.951/0.466%）→ run3 +D 后 0/0 且可见（11.901/30.69%，luma 桶 35–45
      连续谱）；三跑 pack 矩阵逐字节一致 `stages=190 ok=49 failed=141`，
      **0 个 composite/deferred FRAGMENT 失败**（入链双 program 每轮 spvBytes=
      76576/25632 compiled OK），run3 vkdisp ERROR（排除 pack compile）= 0。
      单测 469 全绿（434→469：VersionAdapter 16 + IoLocation 15 + UniformInjector 3 +
      Builtins 1，探针取证后删除）。141 阶段失败分类学（91 VERTEX + 50 FRAGMENT：
      location×36 / gl_MultiTexCoord0×33 / gl_TextureMatrix×20 / Position×2 /
      texture 函数语法×44 / gbufferProjectionInverse 重定义×6，全在 gbuffers/dh/
      final/shadow 系）= **P4.2 切包回归范围**，不阻 P4.1 判据。
      ⑦ **P4.1.3 uniform 上传交付（2026-10-01，两跑闭环）**：
      新增 `glsl/translate/BuiltinsBlockLayout`（std140 布局解析：收编序+目录尾，
      手算 23 项偏移 0..496/512 金样）+ `render/OfUniformManager`（gather 语义 =
      04-SPEC §3.2 上传注记逐条，write 按绝对偏移落字节，纯函数单测）；布局从**转译终稿**
      重解析（F3 冻结契约，VkDispVirtualPack 双槽 volatile），composite/deferred
      **双布局双环**（42 成员/608B、24/512B → 各 1024B 环，FrameApi 绑定/rotate）。
      实测两跑（`evidence/p413-uniform-upload.md` 全文 + sha256）：written 26/24、
      unfilled 16/0（timeBrightness 等 X9 未取证项恒 0 + 一次性列名）、mismatched=0、
      overflow=0、vkdisp ERROR（排除 pack compile）=0、registered=8 compiled=8、
      客户区 9.08/22.45% 与 p412 基线 11.901/30.69% 同量级可见。
      run1 暴露并修复**取值源缺陷**：`SkyRenderState` 字段在 LevelExtractor 提取前
      为默认 0（雨量样本误报 1.0，存档 weather.dat 实为晴）→ 改为直读
      `attributeProbe(SUN_ANGLE/MOON_ANGLE/MOON_PHASE)` + `Level.getRainLevel`
      （与 SkyRenderer:119-125 逐位同源），run2 样本归零。存档取证
      `world_clocks.dat total_ticks=0 + advance_time=0` → `worldTime=0` 为实值。
      单测 487 全绿（469→487：BuiltinsBlockLayout 11 + OfUniformManager 7）。
      ⑧ **P4.1.4 final 步接线（2026-10-01，两跑闭环）**：第 9 管线 final —— 顶点 =
      `fullscreen` **不翻转**（attachment 恒等拷贝**推导**写进
      `PipelineApi.FINAL_PIPELINE_ID` javadoc：composite 换附件不换光栅化 → offscreen3
      texel 逐位 ≡ 旧链 main texel → final 恒等采样拷回 → 显示与旧链一致；flipv 会把
      旧画面垂直镜像，截图方向 = 该推导的实测检验点）；`PackCompositeSource` 一次扫描
      三产出（composite + deferred + final 同包同维度配对，缺/坏 → passthrough +
      INFO/T11 WARN），`VkDispVirtualPack` 三资源三布局，`FrameApi` final 链
      `composite → offscreen3(slot3) → main`（final 是最后且唯一 main 写入者）+ 第三环
      （final 24 成员/512B → 1024B 环；`packAux = deferredChain ? viewC : viewD` 保
      gaux1 身份），`FullscreenPipelineRegistrar` 9 段 try/catch 全量对齐；
      `OfUniformManager.logUploadOnce` **双布尔门缺陷**（slot=final 落 else 分支被
      composite 标志先占吞行）改按槽位名 `UPLOAD_LOGGED_SLOTS` Set 门。实测两跑
      （`evidence/p414-final-step.md` 全文 + sha256 + 关键行原文）：registered=9
      compiled=9 (aligned)、`final source ready: present=true pack=BSL_v10.1.8
      bytes=5616`、三布局 42/24/24 解析、uploaded written 26/24/24 全部
      mismatched=0/overflow=0、run1 门缺陷 `uploaded: slot=final` 0 条 → run2 修复后
      `written=24 unfilled=0` 可见、客户区 9.1930/23.75% 与 p413 基线 9.0767/22.45%
      同量级且截图**未镜像**（树冠朝上 = 恒等拷贝推导实测吻合 —— ⚠️ 该判据基于云团
      构图、分辨不出上下，**已由 p416 强制位姿法取代**：恒等拷贝推导本身在上游
      链顶点修正后才成立，见 ⑩）、vkdisp ERROR
      （排除 pack compile 141）= 0。单测 492 全绿（487→492：PackCompositeSourceTest 5）。
      ⑨ **P4.1.5 properties 条件编译（2026-10-01，两跑闭环）**：`ConditionalPreprocessor`
      扩 OF/Iris `#if` 族 —— **CRLF 归一先于续行判定**（BSL 全文 CRLF，`\` 后 `\r`
      破坏行尾奇数反斜杠判定 → 后半行缺 `=` → 整份解析失败）、**`#elif` 链**
      （Frame 重设计 parentInclude/taken/include/afterElse，嵌套被剔除父级下的
      `#else` 不放行）、**数值比较** `== != < <= > >=`（两侧恒定求值不用 Java 短路；
      标识符已定义→1 未定义→0，口径对齐自有 DefineProcessor.ExprEval；取值环境
      未取证按 X9 登记两态）、指令头/空表达式显式报错（T11）。实测两跑
      （`evidence/p415-properties-conditionals.md`）：run1 解析失败 ×3 +
      `profiles=[]` → 修复后 run2 归零 + `profiles=[ULTRA, MINIMUM, MEDIUM, LOW, HIGH]`，
      WARN 集 diff 只删该文本零新增，链路/上传/阶段矩阵不变量逐字一致。
      单测 501 全绿（492→501：ShaderPropertiesTest 8 + BlockItemPropertiesTest 1）。
      ⑩ **P4.1.6 画面方向矫正（2026-10-01，强制位姿三跑闭环）**：用户报「镜头反了」。
      强制位姿法（NBT Rotation=[0.0,−15.0]，地平线理论正立≈403/翻转≈199）实测：
      原码 run1 地平线 **204 = 翻转**；根因 = **P3.3 链 composite 顶点**
      （`FrameApi:934` 链彩色源 = 场景色 —— deferred 不改写 colortex0，P4.1.2 全黑
      根因实证；P3.3「净翻转守恒」把 deferred 错算成彩色一跳 → 链净 +1 翻转自 P3.3
      起颠倒，P4.1.4 恒等拷贝保住了错；包源无罪 = BSL 三程序 texCoord=gl_MultiTexCoord0
      且 final.fsh 无 gl_FragCoord）。修复 = **顶点翻转跟随彩色采样源**单条规则：
      链 composite 改走 `composite_scene` 不翻转（line 925 `useScene ? …`）+
      deferred 不翻转（顺带修正 depth 配对与 gaux1 行序对齐）+ final 维持恒等；
      fixture/菜单 flipv 路径不动（P-1f 保留）。实测三跑
      （`evidence/p416-orientation.md`）：run2 对冲版 392 正立 → run3 终版 392 正立，
      run3↔run2 地面带 identity=+1.0000、run1↔run3 镜像 +0.9450，WARN 集双向 diff=0，
      链路/上传不变量零回归。单测 501 全绿。⚠️ p414「未镜像（树冠朝上）」云团判据作废，
      以本法为准。
   ✅ P2.1/P2.2 主线接入（A/B/C 线汇合后接启动期扫包钩子，随本轮提交）：
      `VkDispPackScan`（ClientResourceLoadFinishedEvent → gameDir/shaderpacks → ShaderPackService.loadAll）
      实测 latest.log：packs=2（kind=zip + kind=dir 各一）programs=2 options=8 problems=0 diagnostics=0，
      选项逐条可见（name/type/default/values/slider/screen），vkdisp ERROR/WARN=0（01-DEV-LOOP §10 P2.1/P2.2 达标）
   ✅ P2.3 #include 编译接主线（`a51ebd9` 验收通过）：冷路径 = ShaderPackCompiler → GlslPipeline
      （`d6e9bdd`），接主线补上「源交给驱动编译」—— bridge/ShaderCompileApi（经原版
      GlslCompiler.compileToSpv 把阶段源编到 SPIR-V）+ 启动期逐阶段编译日志。
      实测撞出并已修的方言坑（T11 证据链）：D 线注入的 23 条内建 uniform 原为独立
      `uniform <type> <name>;` 行 —— Vulkan GLSL 禁止非透明 uniform 游离在块外
      （shaderc 原文 `'non-opaque uniforms outside a block'`，首跑 4/4 阶段失败）；
      改包块后又撞：匿名块 `layout(std140) uniform {` 报 `syntax error, unexpected LEFT_BRACE`
      （GLSL 语法要求块名；原版 89 个 shader 全为具名无实例名块、成员裸引用）。终态 = 单个
      `layout(std140) uniform VkDispBuiltins { … };`（原版同款形态：无实例名 → 成员仍在全局
      作用域，包源码引用字面不变；04-SPEC §3.2 表的名称/类型/顺序不变，只改发射外壳）。
      实测日志：stages=4 ok=4 failed=0（含 #include 的 composite.fsh 4 阶段全 OK）
   ✅ P2.4 composite 生效（开关能改变画面）（随本轮提交验收通过）｜验收 = 01-DEV-LOOP §10 P2.4
      + 08-TESTING §4「选择开关后画面有对应变化 | 对比截图」。设计（事件时序已 javap 字节码核实，X9）：
      ① **虚拟资源包 `vkdisp_pack`**（04-SPEC §2 命名空间）：AddPackFindersEvent（mod bus）
      注册 required=true 的 RepositorySource → `PackRepository.rebuildSelected` 强制并入选中集
      （字节码：isRequired 分支插到 defaultPosition，不写 options.resourcePacks，04-SPEC §3.1）。
      ② **时序**（Minecraft.<init> 字节码偏移）：1602 setupModResourcePacks（发 AddPackFindersEvent）
      → 1612 repository.reload() → 2850 initClientHooks（发 RegisterRenderPipelinesEvent）
      → 3079 ClientModLoader.finish（此刻 configs 已 loadConfigs）→ 3114 openAllSelected
      （生成包 composite 源）→ 3153 首次资源加载（ShaderManager 编译管线着色器）。
      ③ **源生成**：openResources 时冷路径跑 ShaderPackService.loadAll → ShaderPackCompiler
      （含选项覆盖）→ 取第一个 program=composite 的 FRAGMENT 成功产出；无可用包 → 自造
      passthrough 兜底 + WARN（T11），管线必有源可编（required 管线编译失败会砸启动）。
      ④ **开关**：新增 VkDispConfig.packProfile（P4.3 GUI 前的主线开关）→ PackOptions
      .applyProfile → 与默认值的差分 → config/OptionSourceRewriter 逐行改写源里的
      `#define NAME <值>` / `const NAME = <值>;`（保行号保注释）→ 编译进 SPIR-V。
      ⑤ **帧链接线**：Pass 3 由 blit 换成 composite 管线 —— 片元 id 改指
      `vkdisp_pack:composite`，顶点换自造 `fullscreen_flipv`（把「中间目标 → 主目标」的 1-v
      翻转放进顶点，包片元保持 OF 原语义用原始 vUv，见 18-PARALLEL §10 P-1f）。
      ⑥ **fixture 改造**：composite.fsh 改为采样 InSampler 并以 `1.0 - SHADOW_DARKNESS`
      调制 RGB（原平铺色调 RGB 恒定，开关在画面上不可见）；A/B 对比 = 默认(0.90) vs
      profile HIGH(0.80)，整帧亮度比理论 0.889。
      实测（两轮 runClient，各截 1 张 F2 图 + 整帧亮度统计，stdlib PNG 解码）：
      Run A packProfile='' → `composite source ready: fallback=false pack=vkdisp-fixture-zip
      profile='' bytes=1655 diagnostics=0`，截图 sha256 `c4f7b50aff872af1…` mean_luma 6.3813；
      Run B packProfile='HIGH'（改 `run/config/vkdisp-common.toml` 后重启）→ 同行
      profile='HIGH'，截图 sha256 `af6f22e2e1738bad…` mean_luma 5.6604；**B/A = 0.8870**
      （理论 0.80/0.90 = 0.8889，偏差 0.2% = RGBA8 量化），R/G 通道比 0.8870/0.8871 一致，
      非黑像素 29294 个逐对比值同 0.8870、差异像素 7.15%。两轮共有：`virtual pack finder
      registered` / `composite pipeline wired … fragment=vkdisp_pack:composite
      vertex=vkdisp:fullscreen_flipv` / `pipeline count check: registered=6, compiled=6` /
      vkdisp ERROR=0。
   → P3.1 shadow → P3.2 gbuffers → P3.3 deferred
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

关键路径要求「日志 + 截图」，并行线**没有画面**（冷路径不 import GPU 类，产出里本来
就没有帧可看 —— 这是**分工**事实，非环境限制；各环境的 runClient 能力见 §8.3），
所以证据换成：

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

### 8.3 环境能力现状（2026-10-01 更新，G-07）

历史口径「本环境无 GPU/图形环境、不执行 runClient」**已被实测推翻，作废**。现状两台
验证机均可端到端跑 `runClient` 并出画取证：

| 环境 | GPU / Vulkan | 已实测 |
|---|---|---|
| env-1（WSL2，本仓库 `docs/` 归属） | llvmpipe 软件 Vulkan（Mesa 26.2.3，`tools/vulkan-local/` 本地 ICD） | P0.3→P4.1.2 全部关键路径轮次（build + runClient + X11 截图 + 客户区像素统计） |
| 第二验证机（Windows 11 物理机） | NVIDIA RTX 4060 Laptop（真实独显 Vulkan） | `b15f55e` 基线 build 35s + 434 用例全绿 + runClient 出画，报告见 `review/2026-09-30-本机构建与runClient验证.md` |

对 §7.3 / §9 的影响：并行线证据格式**不变**（那是分工事实，见 §7.3 注），但
「环境跑不了 runClient」不再构成任何跳过关键路径取证的理由；证据入库统一走
根目录 `evidence/` 文本摘要（G-01，索引见 `evidence/README.md`）。

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
| P-1e | F 线开放点：布尔 `#define` 风格（`LITERAL` vs OF 兼容 `IFDEF_TRUE`）与自由文本 STRING 选项的 GLSL 映射 | 布尔半边 **✅ 已定稿 `LITERAL`**（P4.3，2026-10-01，全文见 `OptionBinding` 类 javadoc「P-1e 定稿」段）——证据：① 真实包 BSL 284 个 OF 模型选项**零个** `[true false]` 布尔声明（INTEGER 156 / FLOAT 125 / STRING 3），风格问题在 BSL 上不出现；② BSL 60 个裸开关消费为 ifdef 家族 257:0（`#ifdef` 227 + `#ifndef` 16 + `#if defined` 14），但无值列表不进选项表、到不了 OptionBinding；③ 语料唯一 BOOLEAN（fixture `#define ENABLE_FOG true // [true false]`，运行时 `if (!ENABLE_FOG)` 消费）**要求 LITERAL** —— IFDEF_TRUE 假值出 `#undef` 致未声明标识符；④ 数值选项两风格逐字节相同（真值表单测）。保留 LITERAL 对已观测包零风险、翻默认反引入回归；LITERAL 零风险/翻默认反引入回归的开关保留（一行可切）。**STRING 自由文本半边仍 🟡** —— OptionBinding 侧「只接受单 GLSL 标识符、否则跳过 + WARN」的登记行为不变（改写链 OptionSourceRewriter 按声明行改值是主线路径，binding 未接线；无包实证要求放开，X9 不猜） |
| P-1d | `04-SPEC.md` §4 与 OF 官方属性表的出入复核 | 🟡 **部分完成 + 待改行清单已机器校验**——已核实并写入 §4 复核注记（`mc_Entity` 官方为 **vec3**；`vaUV1`=overlay / `vaUV2`=lightmap；`at_*` 三项存在）。**剩余未定项**：`mc_Entity` 底层元素类型（float32/int16）文档未给 → 决定 stride（E 线现值 47）→ **须 P1.2 构建真实 `VertexFormat` 实测对齐**再走 §3.2 定稿。待改行清单由 `VertexLayoutPendingAlignmentTest`（6 用例）钉死：10 条 `LineRef(file,line,content)` 断言当前行内容，漂移即红；`MC_ENTITY_ELEMENT_TYPE_AFTER_P12=null` 显式标注未实测（X9 不猜值），回填即红强制同轮更新 |
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
