# 13 · 特性缺口登记表

> 执行载体：`12-GAP-STRATEGY.md`。
> **规则：先登记，再实现。没登记的实现一律视为越界（违反 `07-CONSTRAINTS.md` T12）。**

---

## 1. 登记表

| ID | 需求来源 | 原版现状（查明结果） | 补充方案 | 影响面 | 开关 | 回退条件 | 状态 |
|---|---|---|---|---|---|---|---|
| *(示例)* GAP-001 | BSL 需要比较采样器 | 原版有 `GpuSampler`，但未提供比较采样器组合（§2 ①~④ 均不成立） | 在 `platform/` 内组合出比较采样器 | 仅 `platform/SamplerSupport` | `supplement.compareSampler` | 官方 `GpuSampler` 提供 compare 选项后删除 | ⏳ 待实现 |
| GAP-002 | BSL `dh_terrain` / `dh_water` 引用一组 Distant Horizons 提供的块类型 / 材质宏：`int blockID = dhMaterialId;`（`dhMaterialId`）+ `DH_BLOCK_WATER` / `DH_BLOCK_LAVA` / `DH_BLOCK_LEAVES` / `DH_BLOCK_ILLUMINATED` / `DH_OVERDRAW`；这些符号由 Distant Horizons 注入定义 | 查明：BSL v10.1.8 `shaders/program/dh_terrain.glsl` / `dh_water.glsl` 引用上述符号；全包 grep 无任何这些符号的声明（Distant Horizons 在注入 DH 渲染时 `#define` / 注入）。本引擎不集成 DH（07-CONSTRAINTS D3/D16），DH 既不依赖也不集成 | 在 `glsl/translate/LegacyBuiltinInjector` 内，当下列符号被使用且未声明时按普通全局声明为 stub：`int dhMaterialId;`（变量）+ `const int DH_BLOCK_WATER = 1;` / `DH_BLOCK_LAVA = 2;` / `DH_BLOCK_LEAVES = 3;` / `DH_BLOCK_ILLUMINATED = 4;` / `DH_OVERDRAW = 5;`（常量）。对应 UniformInjector 的 `TOP_LEVEL_GLOBAL` 形态，Vulkan GLSL 合法；任何阶段都注入（dh 含 vsh/fsh） | `glsl/translate/LegacyBuiltinInjector#INJECTIONS`（非 `platform/`：属转译期 DH 兼容 shim，非原版能力缺口） | 无独立开关；DH 兼容 shim 属转译兜底，BSL 不引用即不触发 | BSL 不再引用这些符号，或本引擎接入 DH 渲染后删除 | ✅ 已实现 |

| GAP-003 | **支柱①「完整兼容」的核心前提**：OF/Iris 的 gbuffer 语义天然是多附件 —— `gbuffers_*` 通过 `RENDERTARGETS` + `layout(location=N)` 同时写 `colortex0`（albedo）/ `colortex1`（normal+lightmap）/ `colortex2`（material/matID），deferred 再逐级读回 | **已源码级核实（2026-10-02）**：① `RenderPipelines.registerCustomPipelines` 用 `putIfAbsent`，改不了 `pipeline/solid_terrain` 的 location，只能新增；② `SOLID_TERRAIN` 等全部写死 `ColorTargetState.DEFAULT`（单附件 RGBA8），派生管线可改附件数但要被地形 draw 用到必须走注入点；③ bind group 布局在 `RenderPipeline` 构造时固化，`BindGroupLayouts.Globals` 仅 9 字段（无相机矩阵/太阳方向）⇒ 零 mixin 三条路全断。**2026-10-03 追加核实（第 4、5 条）**：④ 原版地形 render pass 由 `LevelRenderer.addMainPass` 里的 `createRenderPass(name, colorView, Optional.empty(), depthView, OptionalDouble.empty())` 建出，**颜色附件恰好 1 个** ⇒ 光在管线侧加附件必然与 pass 不匹配，**必须先拿 pass 的所有权**；⑤ **原版主 pass 把地形、实体、特性、云、描边画在同一个 pass 同一个单附件里** ⇒ 直接把该 pass 改成多附件会让**所有原版管线**（都声明 1 个附件）全部 validation error ⇒ M-04 不能只改附件数。**2026-10-03 方案 A 前提实测**（`evidence/h03-terrain-draw-capture.md`）：⑦ 官方 `FrameGraphSetupEvent`（`render` 第 249 行）**早于** `prepareChunkRenders*`（第 271-275 行）⇒ 事件里拿不到地形 draw 数据；⑧ 但帧图 pass 体在第 286 行才执行 ⇒ **只读捕获引用**即可，实测捕获命中 `prepareChunkRendersIndirect` 且非 null，AfterLevel 处可见本帧捕获（时序成立）⇒ **方案 A 的入口已通**，且该注入点**不改任何渲染行为**（ON/OFF 截图逐字节相同）。**2026-10-03 方案 A 第 2 步实测**（`evidence/h04-gbuffer-terrain-pass.md`）：⑩ **地形真的画进了我方自己的 3 附件 pass** —— 像素级判读：`colortex0` = 真地形、`colortex1` = 纯清屏色（原版 `core/terrain.fsh` 只有 `layout(location=0) out` ⇒ 槽 1/2 理应为空）、主目标仍是正常原版渲染。⑪ `renderGroup` 的 `blockAtlas`/`sampler` **都能用公开 API 拿到** ⇒ **方案 A 不需要 M-04**。⑫ 🔴 **本引擎是反向 Z**（字节码级三条：① `Projection#getMatrix` 把 `setPerspective` 的 near/far **实参对调**；② `DepthStencilState.DEFAULT` = **`GREATER_THAN_OR_EQUAL`**；③ `MainTarget` 深度 **`D32_FLOAT`** 且 clear 传 **0.0**；⚠️ `isZZeroToOne()` 与反向无关）：自建深度目标必须清 **0.0**（原版 clear pass `clearColorAndDepthTextures(…, 0.0)`，`LevelRenderer:255`），清 1.0（=近平面）会让每个地形片元被深度测试掉，**零报错、只剩清屏色**。⑬ 🔴 **本机没有 validation layer** ⇒ 「日志里没有 validation error」在本机**不是任何证据**（`h02`/`h03` 里的相关表述已撤回）。**2026-10-03 原语实测**：⑨ 多附件通道本身**完全可用**（3 附件 pass + 3 目标管线 + 3 路片元输出，逐槽 R 指纹量化判读通过，0 validation error）⇒ 卡点确定在 pass 所有权，不在能力 | 用派生 `RenderPipeline`（多附件 `ColorTargetState` + 自定义 uniform 块）+ **管线装配层 mixin** 把派生管线接到地形/实体/天空 draw 上。M1 已于 2026-10-02 松绑允许 | `pipeline/`（派生管线）+ `mixin/`（装配层）+ `render/`（pass 图） | `mixin.wireTerrain`（M-01，已实现）/ `mrt.enabled`（原语诊断视图，默认关）/ `mixin.captureTerrainDraws`（M-05 方案 A 入口，已实现）/ `mixin.ownTerrainPass`（M-04 方案 B，⏸️ 已登记未实现） | 原版开放多附件地形管线通道，或官方提供可修改原版 `RenderPipeline` 的公开 API | 🟡 **能力已验、通道已通、地形已进多附件 pass（含帧图内插 pass 的生产形态）**（M-01/M-01b 见 `evidence/h01-…`；MRT 原语见 `h02`；地形接入见 `h04`；回读翻转修正与帧图内重测见 `h05`）。⛔ **仍未解除的阻塞**：① colortex1/2 没有 gbuffer 语义（要等包的自研 `gbuffers_terrain` 接入）—— **当前唯一的主要阻塞**；② 半透明地形未覆盖；③ 无画面改进（地形被画两遍）、无性能数据。🔖 附带修正：回读 blit 曾把画面上下颠倒（`fullscreen_flipv` 误用于「中间目标→中间目标」），已新增 `vkdisp:pipeline/mrtview_noflip` 修掉；⚠️ 因此 `h04` 的 `colortex0` 截图**整体颠倒** —— 「地形在里面」成立、**朝向不成立**。🔖 h04 §9「帧图内插 pass 从未成功」**已作废**（那是深度修复前的旧观察 + 上面这个显示 bug 的合并误判）
| GAP-004 | 自定义 uniform 块无处安放：BSL/Iris 的 deferred pass 需要 `gbufferModelViewInverse`、`shadowModelView`、`shadowProjection`、`sunPosition`、`moonPosition` 等 OF 内建矩阵/向量，原版 `Globals` 仅 9 字段且不含这些 | 已源码级核实：bind group 布局在 `RenderPipeline` 构造时固化，无法给原版管线追加 uniform 块（见 GAP-003 ③）。**2026-10-03 实测补充**：派生管线多出的 bind group 条目**必须**在 draw 前 `setUniform`，否则驱动层 STRICT_VALIDATION 抛 `Missing uniform 名`（原版 `renderLayers` 只绑 `TerrainUniform`/`Sampler0`/`Sampler2`，没人会绑我们那条） | 随 GAP-003 一并在**派生管线**上构造独立 uniform bind group 布局，随管线一起注册；绑定由 M-01b 注入点补 | `pipeline/`（派生管线构造）+ `mixin/`（M-01b 绑定） | `mixin.bindTerrainParams`（M-01b，已实现） | 原版管线支持追加 uniform 块 | 🟡 **块已挂上并每帧绑定**（🔴 原文「实测 0 validation error」已撤回：本机无 validation layer；绑定是否成立的判据是 draw 不抛 `Missing uniform` 且画面正确），但 ⛔ **块尚无消费者** —— 本轮地形片元仍是原版 `core/terrain`，不读这个块；要真正消费需 GAP-003 那轮换自研 gbuffer 片元 |
| GAP-005 | 候选方案（非缺口，登记以免遗忘）：是否用成熟 GLSL 前端 **KhronosGroup/glslang + SPIRV-Tools** 替/辅自研 8 段转译器 | **联网核实（2026-10-02）**：glslang 与 SPIRV-Tools 为 **Apache-2.0 / BSD-3**，**可合法并入本 MIT 工程**（Fedora / openEuler / Arch 官方打包元数据三处一致）；glslang 支持完整 `#include`、`GL_*` 扩展、`-D` 宏定义与预处理开关，正是 OF 方言所需 | 三条路待比：① 维持自研；② 引入 glslang（C++ 依赖、需随 jar 分发或走原版通道）；③ 混合（自研做 OF 方言层，glslang 做 GLSL→SPIR-V） | `glsl/`（可能整体重构） | — | — | ⏳ **已登记，本轮不执行**（先做 G 系列 Rust vs Java 对比，其结论会影响是否值得重构转译链） |
| GAP-006 | Rust 原生路径的 FFI 安全边界：Rust `panic` 穿过 FFI 边界是 UB，会直接 abort 掉整个 JVM ⇒ **游戏崩溃** | 联网核实（2026-10-02）：Rust 官方 Nomicon 明确 —— `extern "C"` 收到 panic 会终止进程；必须 `catch_unwind(AssertUnwindSafe(…))`；且 `panic = "abort"` 时 `catch_unwind` 完全失效 | `17-NATIVE.md` §4.5 的 FFI 安全清单为强制门禁；`08-TESTING.md` §8.3 要求**故意触发一次 panic** 验证 JVM 不 abort | `accel/backend/native/`（仅「采用」裁决后存在） | 与 A/B 开关同键 | — | ⏳ 待实现（仅当 G 系列裁决「采用」） |

| 字段 | 要求 |
|---|---|
| **ID** | `GAP-001` 起递增，**永不复用** |
| **需求来源** | 具体到 pack 名 + stage + 哪条 OF 语义，不要写"某个包要" |
| **原版现状** | **必须写明查证过程**：查了哪些类/包、为什么 §2 的①~④ 都不成立 |
| **补充方案** | 实现形态一句话；复杂的话指向对应类 |
| **影响面** | 涉及哪些类、是否需要 mixin、是否需要改设备创建路径 |
| **开关** | 配置项/常量名，必须可一键关闭 |
| **回退条件** | **可判定的**条件（"官方 X 类新增 Y 方法"），不要写"以后再说" |
| **状态** | ⏳ 待实现 / 🚧 实现中 / ✅ 已实现 / 🔁 已回退为原版 |

---

## 3. 官方更新复查记录

每次跟随官方版本升级后填写。对应 `12-GAP-STRATEGY.md` §5。

| 复查日期 | 官方版本 | GAP ID | 复查结论 | 动作 |
|---|---|---|---|---|
| *(留空待填)* | | | | |

**复查结论选项**
- `官方已补上` → 打开开关 A/B 对比一致 → 删补充实现 → 状态改 🔁
- `官方未补上，API 无变化` → 保持
- `官方未补上，但 API 变了` → 同步改造补充实现
- `补充所依赖的原版能力被移除` → **停止升级、回滚**（见 `06-MIGRATION.md` §7）

---

## 4. 快速自检

```
[ ] 每一项补充都有 GAP ID
[ ] 每一项补充的代码头部有【自行补充】注释块
[ ] 每一项补充都在 platform/ 包内
[ ] 每一项补充都有独立开关
[ ] 每一项补充都有日志证明被走到
[ ] 回退条件是可判定的，不是"以后再说"
[ ] 业务包没有 import platform/ 的内部实现
```
