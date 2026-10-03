# 13 · 特性缺口登记表

> 执行载体：`12-GAP-STRATEGY.md`。
> **规则：先登记，再实现。没登记的实现一律视为越界（违反 `07-CONSTRAINTS.md` T12）。**

---

## 1. 登记表

| ID | 需求来源 | 原版现状（查明结果） | 补充方案 | 影响面 | 开关 | 回退条件 | 状态 |
|---|---|---|---|---|---|---|---|
| *(示例)* GAP-001 | BSL 需要比较采样器 | 原版有 `GpuSampler`，但未提供比较采样器组合（§2 ①~④ 均不成立） | 在 `platform/` 内组合出比较采样器 | 仅 `platform/SamplerSupport` | `supplement.compareSampler` | 官方 `GpuSampler` 提供 compare 选项后删除 | ⏳ 待实现 |
| GAP-002 | BSL `dh_terrain` / `dh_water` 引用一组 Distant Horizons 提供的块类型 / 材质宏：`int blockID = dhMaterialId;`（`dhMaterialId`）+ `DH_BLOCK_WATER` / `DH_BLOCK_LAVA` / `DH_BLOCK_LEAVES` / `DH_BLOCK_ILLUMINATED` / `DH_OVERDRAW`；这些符号由 Distant Horizons 注入定义 | 查明：BSL v10.1.8 `shaders/program/dh_terrain.glsl` / `dh_water.glsl` 引用上述符号；全包 grep 无任何这些符号的声明（Distant Horizons 在注入 DH 渲染时 `#define` / 注入）。本引擎不集成 DH（07-CONSTRAINTS D3/D16），DH 既不依赖也不集成 | 在 `glsl/translate/LegacyBuiltinInjector` 内，当下列符号被使用且未声明时按普通全局声明为 stub：`int dhMaterialId;`（变量）+ `const int DH_BLOCK_WATER = 1;` / `DH_BLOCK_LAVA = 2;` / `DH_BLOCK_LEAVES = 3;` / `DH_BLOCK_ILLUMINATED = 4;` / `DH_OVERDRAW = 5;`（常量）。对应 UniformInjector 的 `TOP_LEVEL_GLOBAL` 形态，Vulkan GLSL 合法；任何阶段都注入（dh 含 vsh/fsh） | `glsl/translate/LegacyBuiltinInjector#INJECTIONS`（非 `platform/`：属转译期 DH 兼容 shim，非原版能力缺口） | 无独立开关；DH 兼容 shim 属转译兜底，BSL 不引用即不触发 | BSL 不再引用这些符号，或本引擎接入 DH 渲染后删除 | ✅ 已实现 |

| GAP-003 | **支柱①「完整兼容」的核心前提**：包的 gbuffer 语义天然要多附件输出 —— OF/Iris 系的 `gbuffers_*` 把 albedo / 法线 / 材质写进多个 `colortex`，deferred 再逐级读回 | **2026-10-03 晚重大更正（用 BSL v10.1.8 真实源码核实，原条目把两个包的语义混为一谈）**：
  ① 🔴 **「colortex1=法线+lightmap、colortex2=材质」是 _Iris_ 的默认语义，不是 BSL 的**。BSL 的 `shaders/program/gbuffers_terrain.glsl` 用 OF 式 **`gl_FragData[N]`**（不是 `layout(location=N)`），且**槽位→colortex 的映射由 `/* DRAWBUFFERS:... */` 注释给出**，实测 BSL 全部 gbuffer 程序的 DRAWBUFFERS 集合都是 `{0, 0367, 08, 08367}` ⇒ 启用高级材质时 **`gl_FragData[1]`→colortex3（材质）、`[2]`→colortex6（法线）、`[3]`→colortex7**，**不是 colortex1/2**。
  ② 🔴 **BSL 默认配置下地形只写 colortex0**：`lib/settings.glsl` 里 `ADVANCED_MATERIALS` 与 `MCBL_SS` **都是注释掉的**（`//#define`），`REFLECTION_SPECULAR` 虽开启但只在 `defined ADVANCED_MATERIALS` 块内生效 ⇒ 默认走 `DRAWBUFFERS:0` 分支，`newNormal` 算完**被丢弃**（`EncodeNormal` 只出现在被 `#if` 包住的分支里）。
  ③ 但 **composite 读 colortex0 与 colortex1**（`composite.glsl` 的 `DRAWBUFFERS:01`）⇒ 帧里至少要有 2 个 colortex。
  ④ ✅ **文本层翻译链已经通了**（实测，见 `evidence/h06-bsl-terrain-semantics-and-translate-baseline.md`）：把 BSL 真实 FSH 段（438 行）喂进 `OfGlslTranslator`，**0 个 ERROR**，`FragmentOutputAdapter` 自动合成 **5 个** `layout(location=0..4) out vec4`，并把 **29 条**游离 OF uniform 收编进 `VkDispBuiltins` 块 ⇒ 「多附件」与「gl_FragData 改写」这两件事**都不是阻塞**。
  ⑤ ⚠️ 因此 `MrtPlan.SLOT_COUNT = 3`（按 Iris 语义定的）对 BSL **不够**：BSL 最多要 5 槽，且附件顺序要服从 DRAWBUFFERS 映射而非下标。
  ⑥ 早期核实仍成立：原版主 pass 颜色附件恰好 1 个（`LevelRenderer.addMainPass`）、`Globals` 仅 9 字段、bind group 布局构造时固化 ⇒ 零 mixin 三条路全断。
  ⑦ ✅ **通道已通**：M-01/M-01b 派生地形管线接上了地形 draw（`h01`）；MRT 原语验通（`h02`）；M-05 只读捕获（`h03`）；地形真的画进我方 3 附件 pass、含帧图内插 pass 的生产形态（`h04`/`h05`）。 | 用派生 `RenderPipeline`（多附件 `ColorTargetState` + 自定义 uniform 块）+ **管线装配层 mixin** 把派生管线接到地形 draw 上。M1 已于 2026-02 起松绑允许 | `pipeline/`（派生管线）+ `mixin/`（装配层）+ `render/`（pass 图）+ `glsl/translate/`（包片元改写） | `mixin.wireTerrain`（M-01，已实现）/ `mixin.bindTerrainParams`（M-01b，已实现）/ `mrt.terrain`（地形多附件 pass，默认关）/ `mixin.captureTerrainDraws`（M-05，已实现）/ `mixin.ownTerrainPass`（M-04 方案 B，⏸️ 已登记未实现） | 原版开放多附件地形管线通道，或官方提供可修改原版 `RenderPipeline` 的公开 API | 🟡 **通道已通、地形已进多附件 pass（含帧图内插 pass 的生产形态）、包地形片元早已编译成 SPIR-V**（`h01` M-01/M-01b；`h02` MRT 原语；`h04` 地形接入；`h05` 回读翻转 + 帧图内重测；`h06` BSL 语义核实 + 翻译基线；**`h07` SPIR-V 已验证 + 输出数陷阱**）。⛔ **仍未解除的阻塞**：**把包的地形 SPIR-V 接进派生 MRT 管线** —— 整包产物里**已有** 46.7–79.9KB 的地形 SPIR-V（三个维度目录全部成功），但**地形 draw 用的仍是原版 `core/terrain`** ⇒ 这一步是**接线**；配套顶点着色器与属性布局对齐；44 条 OF uniform 的取值供给（GAP-004 那个块只收编了声明）；`sampler3D lighttex0/1` 与原版 2D lightmap 的不匹配（🔴 编译层面已不阻塞，只在渲染期绑采样器时暴露）。⚠️ **不要再按「补齐 colortex1/2 的法线/材质」描述缺口** —— 那是 Iris 语义；且「**能力上限 5 槽**」与「**生产实际 1 槽**」是两个数，混用会让附件数与颜色目标数不匹配 ⇒ `setPipeline` 抛异常**崩客户端**。⚠️ `h04` 的 `colortex0` 截图**整体颠倒**（回读 bug）：「地形在里面」成立、**朝向不成立**；`h04` §9「帧图内插 pass 从未成功」**已作废**。|
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
