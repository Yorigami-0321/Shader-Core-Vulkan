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
  ⑦ ✅ **通道已通**：M-01/M-01b 派生地形管线接上了地形 draw（`h01`）；MRT 原语验通（`h02`）；M-05 只读捕获（`h03`）；地形真的画进我方 3 附件 pass、含帧图内插 pass 的生产形态（`h04`/`h05`）。 | 用派生 `RenderPipeline`（多附件 `ColorTargetState` + 自定义 uniform 块）+ **管线装配层 mixin** 把派生管线接到地形 draw 上。M1 已于 2026-02 起松绑允许 | `pipeline/`（派生管线）+ `mixin/`（装配层）+ `render/`（pass 图）+ `glsl/translate/`（包片元改写） | `mixin.wireTerrain`（M-01，已实现）/ `mixin.bindTerrainParams`（M-01b，已实现）/ `mrt.terrain`（地形多附件 pass，默认关）/ `mixin.captureTerrainDraws`（M-05，已实现）/ `mixin.ownTerrainPass`（M-04 方案 B，⏸️ 已登记未实现） | 原版开放多附件地形管线通道，或官方提供可修改原版 `RenderPipeline` 的公开 API | ✅ **包的地形片元已真正接上派生 MRT 地形管线**（2026-10-04 `h08`，MCP 取证）：派生 MRT 管线的片元从原版 `core/terrain` 换成包自己的 `gbuffers_terrain`，顶点侧由我方适配层 `terrain_pack_adapter.vsh` 按原版顶点格式逐位置产出包的 9 条 OF varying；**附件数改为跟随包的输出数**（配置 3 → 实测 1，BSL 默认配置 `ADVANCED_MATERIALS` 关）。决定性证据：同一存档/时刻/机位两张 MCP 截图，地形像素平均绝对差 **48.22**、**39.03%** 像素变化，而 HUD/准星一致；日志 0 条 vkdisp ERROR、0 崩。⛔ **仍未解除**：① 地形只画进**我方 pass**，主目标仍由原版绘制 ⇒ **本轮不产出用户可见画面改进**（M-04 未做）；② `mat`/`recolor`/`normal` 三条 varying 按常量供值（**GAP-007**）；③ `shadowtex0/1` 绑的是本 pass 深度占位 ⇒ 包阴影结果不成立；④ 只覆盖 OPAQUE 组；⑤ 只覆盖 BSL 默认配置（开 `ADVANCED_MATERIALS` 为 5 槽，附件顺序须服从 `DRAWBUFFERS`）；⑥ 只验了一个包（X39）。🔖 **⚠️ 时序铁律（新立）**：`RegisterRenderPipelinesEvent` 在**启动期只触发一次**，且**早于**虚拟包 `openResources` 生成包源约 **4.5 秒** ⇒ 「等包源好了再注册管线」这条路不存在，必须**提前**算契约（`VkDispVirtualPack#ensureTerrainProgram`）。实测提前生成耗时 3620ms。✅ **2026-10-04 追加（`h09`）**：`gl_FragData[k] → location k` 这条**静默绑错槽**已被消灭 ——
新增转译第 ⑦½ 段 `DrawBuffersSlotAdapter` 按包源码里的 `/* DRAWBUFFERS:… */` 兑现槽位（累积语义：
索引 k 取最后一条长度 > k 的标记）。实测 BSL 开 `ADVANCED_MATERIALS` ⇒ 槽位 **0/3/6/7**、
**8 个附件**（配置 `mrt.attachments=3` 故意不改，两侧都被改成 8）。
✅ 同时修掉更靠前的阻塞：**BSL 的布尔选项此前既不可见也不可改**（284 个枚举选项里没有
`ADVANCED_MATERIALS`，布尔数 = 0）⇒ 多槽路径无法被触发；现为 386 个选项 / 102 个布尔。
⛔ 但 **8 槽画面是剪影全黑**：几何与槽位路由对，像素值不对（GAP-008，未坐实根因）。
⚠️ 能力边界：`DRAWBUFFERS:08367`（MCBL_SS + 高级材质同开）需 9 附件 > Vulkan 上限 8 ⇒ **显式拒绝接线**。⚠️ 不要再按「能力上限 5 槽」建 pass —— 生产实际是 **1 槽**（`h07`/`h08` 双证），混用会崩客户端（X42）。
| GAP-004 | 自定义 uniform 块无处安放：BSL/Iris 的 deferred pass 需要 `gbufferModelViewInverse`、`shadowModelView`、`shadowProjection`、`sunPosition`、`moonPosition` 等 OF 内建矩阵/向量，原版 `Globals` 仅 9 字段且不含这些 | 已源码级核实：bind group 布局在 `RenderPipeline` 构造时固化，无法给原版管线追加 uniform 块（见 GAP-003 ③）。**2026-10-03 实测补充**：派生管线多出的 bind group 条目**必须**在 draw 前 `setUniform`，否则驱动层 STRICT_VALIDATION 抛 `Missing uniform 名`（原版 `renderLayers` 只绑 `TerrainUniform`/`Sampler0`/`Sampler2`，没人会绑我们那条） | 随 GAP-003 一并在**派生管线**上构造独立 uniform bind group 布局，随管线一起注册；绑定由 M-01b 注入点补 | `pipeline/`（派生管线构造）+ `mixin/`（M-01b 绑定） | `mixin.bindTerrainParams`（M-01b，已实现） | 原版管线支持追加 uniform 块 | 🟡 **块已挂上并每帧绑定**（🔴 原文「实测 0 validation error」已撤回：本机无 validation layer；绑定是否成立的判据是 draw 不抛 `Missing uniform` 且画面正确），但 ⛔ **块尚无消费者** —— 本轮地形片元仍是原版 `core/terrain`，不读这个块；要真正消费需 GAP-003 那轮换自研 gbuffer 片元 |
| GAP-005 | 候选方案（非缺口，登记以免遗忘）：是否用成熟 GLSL 前端 **KhronosGroup/glslang + SPIRV-Tools** 替/辅自研 8 段转译器 | **联网核实（2026-10-02）**：glslang 与 SPIRV-Tools 为 **Apache-2.0 / BSD-3**，**可合法并入本 MIT 工程**（Fedora / openEuler / Arch 官方打包元数据三处一致）；glslang 支持完整 `#include`、`GL_*` 扩展、`-D` 宏定义与预处理开关，正是 OF 方言所需 | 三条路待比：① 维持自研；② 引入 glslang（C++ 依赖、需随 jar 分发或走原版通道）；③ 混合（自研做 OF 方言层，glslang 做 GLSL→SPIR-V） | `glsl/`（可能整体重构） | — | — | ⏳ **已登记，本轮不执行**（先做 G 系列 Rust vs Java 对比，其结论会影响是否值得重构转译链） |
| GAP-006 | Rust 原生路径的 FFI 安全边界：Rust `panic` 穿过 FFI 边界是 UB，会直接 abort 掉整个 JVM ⇒ **游戏崩溃** | 联网核实（2026-10-02）：Rust 官方 Nomicon 明确 —— `extern "C"` 收到 panic 会终止进程；必须 `catch_unwind(AssertUnwindSafe(…))`；且 `panic = "abort"` 时 `catch_unwind` 完全失效 | `17-NATIVE.md` §4.5 的 FFI 安全清单为强制门禁；`08-TESTING.md` §8.3 要求**故意触发一次 panic** 验证 JVM 不 abort | `accel/backend/native/`（仅「采用」裁决后存在） | 与 A/B 开关同键 | — | ⏳ 待实现（仅当 G 系列裁决「采用」） |
| GAP-007 | 地形顶点侧缺三条 per-vertex 数据：**方块 id（mat/recolor）与法线（normal）**。实测（`h08`）：BSL 的 `gbuffers_terrain` 顶点着色器按 `mc_Entity.x / 100` 推方块 id 来决定 `mat`（树叶/自发光/岩浆…）与 `recolor`（草/浆果），并把顶点 `Normal` 属性转成眼空间法线；而原版地形顶点缓冲 `DefaultVertexFormat.BLOCK` 只有 **4 个属性**（Position/Color/UV0/UV2），**既无 `mc_Entity` 也无 `Normal**` ⇒ 适配层只能按常量供值（`mat=0` / `recolor=0` / `normal=(0,1,0)`）| **根因**：地形网格化阶段没有写这两项；补它要改区块网格化产出，属渲染器层改动 | 方案 A：扩地形顶点格式（BLOCK → 加 `Normal` + `EntityId` 两属性，网格化侧逐顶点写入）；方案 B：改用 `DefaultVertexFormat.ENTITY` 作地形格式（已有 `Normal`，仍缺 `mc_Entity`）——**B 只解决一半**。两案都需另立注入点登记，且会改变内存占用 | `pipeline/model`（顶点格式）+ 网格化侧（新增注入点，待登记） | `terrain.vertexExtras`（**未实现**，占位键名以便将来一键关闭） | 原版地形顶点格式提供方块 id 与法线属性 | 🟡 **已定位、已量化、未实现**（`h08` §五逐条标注了三条常量项与各自影响面）|
| GAP-008 | 高级材质（`ADVANCED_MATERIALS`）路径下 gbuffer 输出**像素全黑**。实测（`h09`）：8 槽接线全对（`colorTargets=8` / `slots=8` / 610 条 SOLID draw），画面却是绿色清屏底上的**纯黑剪影**；而默认配置（1 槽）同一机位画面正确可见 |
两条候选成因（**本轮未逐项二分验证，不假装已坐实**）：① 该路径会用在 GAP-007 里**按常量供值**的 7 条 varying（`normal` / `tangent` / `binormal` / `mat` / `vTexCoord*`）参与光照与材质分支；
② 新增的 `specular` / `normals` 采样器**绑的是方块图集占位视图**。两者相乘把 colortex0 压到 0 |
先做**逐项切分**（每次只放开一条常量项 / 换成真视图）定位压零的那一项；再按定位结果决定是补数据还是降级。
⚠️ **禁止**先猜一个「看起来对」的绑定再截图 —— 那正是本项目反复消灭的失败形态 |
`pipeline/model`（契约）+ `glsl/translate/PackVertexAdapterGenerator`（常量项表）+ 采样器绑定 |
`mrt.packTerrainShader`（已存在） | 默认配置路径画面正确 ⇒ 随时可退回 |
🟡 **已有正面证据：只有 `albedo` 为 0**（`h13`）：切到 **colortex3**（高级材质路径确实写的槽）→ 画面是**亮绿地形剪影**（`vec4(smoothness, skyOcclusion, 0, 1)` 的 `.g` 满值）⇒ **片元着色器完整跑完**，同一片元里光照/天光/法线/菲尼尔全部正常，**只有 `albedo` 是 0**。🔍 候选 6 子项①（图集 mip 链）**已排除**：`blockAtlas()` 返回的是**原版** `TextureAtlas.LOCATION_BLOCKS` 视图，mip 由原版生成填充。🔍 另外**画面独立验证了 DRAWBUFFERS 槽位路由**：槽 1 整幅纯清屏色（99.89%）、没有地形，正因为高级材质路径写的是槽 **0/3/6/7**。🟡 剩余疑独：`dFdx(texCoord)` 是否在反向 Z / MRT pass 下退化（**未验证**）|
🔍 `h14` 进度：派生导数探针（**转译第 7¾ 段** `DerivativeProbeAdapter`）已落地，
只把 `vec2 dcdx = dFdx(texCoord);` / `vec2 dcdy = dFdy(texCoord);` 的初值换成 `vec2(0.0)`（默认关、等行数、`finally` 复位、X45 自报）。
🔴 单测**当场抓到**探针**自己**一处致命缺陷（正则只匹配 `dFdx`，漏了 `dFdy`，
导致「单变量实验」实际只施加了半个变量，而日志只报 N 处不会提醒）。已修：分别捕获「名字方向」与「函数方向」并核对一致性。
🔴 客户端那趟**作废**：M-01 冷路径基准长时间占住渲染线程（`hit x6250000` 仍在跑），
地形片元源从未生成、**地形 pass 一次都没跑**（`terrain drawn into` = 0），探针连被调用的机会都没有。
🔖 当时的截图看着「完全正常」，但它是 `terrainToMain=false` 下的**纯原版渲染**，
原版永远看着正常，此时**「画面正常」不含任何信息**。🟡 候选 6 **仍未验证**，下一轮需先过两道闸（基准已跑完 + `terrain drawn into` 出现）。
| GAP-010 | 生成式地形顶点适配层**资源登记晚于管线注册**：首轮资源重载时 `PipelineBuilder`
  报 12 条 `Couldn't find source for VERTEX shader (vkdisp_pack:terrain_pack_adapter)`；
第二轮重载成功 ⇒ **不致命**，但会在日志里留 12 条 ERROR（实测 `h11`）|
这与 §10.14 记的「管线注册早于包源生成」是**同一时序约束的另一面**；适配层是生成物，
比静态资产更晚就绪 |
把适配层资源登记**提前**到管线注册之前（或让它在缺源时给出可读诊断而不是 ERROR）；
目标是首轮重载 0 条该 ERROR |
`VkDispVirtualPack`（资源登记时序）+ `bridge/TerrainPipelineApi`（注册时机） | `mrt.packTerrainShader` |
第二次重载会自愈 ⇒ 画面不受影响 | 
🟢 **已定位、未修**（`h11`）|
| GAP-009 | 高级材质路径需要的**逐方块材质贴图集**（OF 的 `specular` / `normals`）本引擎没有。实测（`h10`）：包片元用 `textureLod(specular, …)` 取光滑度/金属度/孔隙/自发光遮罩，用 `textureGrad(normals, …).z` 取 AO |
它们是**资源包附带的一整套逐方块材质贴图**，不是本项目能就地生成的资产；不引入也不假装有 |
缺省绑**乘法单位元**（`specular=(0,0,0,255)` / `normals=(128,128,255,255)`，语义=「没有材质覆盖、没有 AO、法线朝上」），
并一次性 INFO 说明「这是缺省不是材质贴图」；将来接入真资源集时替换这两个绑定即可 |
`bridge/NeutralMaterialMaps`（中性缺省）+ 未来一个资源集加载器（未建） | `mrt.packTerrainShader`（已存在） | 
默认配置路径不经过 `GetMaterials`，画面正确 ⇒ 随时可退回 | 
🟡 **缺省语义已落地并验证**（`h10`：两个采样器确实被读到 —— 48.41% 像素变化）；**真材质贴图集未实现** |

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

|`h10` 进度（2026-10-04，见 `evidence/h10-gap008-bisection-neutral-material-maps.md`）：
把乘法链**静态列全**（3 个候选：`ao*ao` / `1-metalness*smoothness` / `sceneLighting *= skylightSqr`），
然后**逐项切分**。① 候选 1/2（`ao` 与金属度）**已被实验证伪**：把 `specular`/`normals` 从方块图集
换成**中性材质贴图**（乘法单位元：`(0,0,0,255)` 与 `(128,128,255,255)`）后，**48.41% 的像素确实变了**
（证明两个采样器被读到了）**但画面仍全黑** ⇒ 它们不是主因。
② 候选 3 静态成立：`skylightSqr = lightmap.y²`、`lightmap = clamp(lmCoord, 0, 1)`，而原版把**天光与块光
打包进同一个 UV2**（`uv2.y` 恒 0）⇒ `lmCoord.y ≡ 0` ⇒ `sceneLighting ≡ 0`；
OF 语义下 `lmCoord` 应当是 `(块光, 天光)` 两条独立通道 ⇒ **这是一处真实的映射错误**。
⚠️ **但尚未用实验坐实**：单变量开关 `mrt.terrainFullLightProbe`（打开时只把 `lmCoord` 改成 `vec2(1.0)`）
已实现并通过无头测试，**本轮客户端没进世界、实验未执行**（quickPlay 有随机不生效的现象）。
⛔ 另：本轮顺带揪出并修掉一个**每帧抛**的回归 —— 在 pass 打开期间懒建贴图上传 ⇒
IllegalStateException: Close the existing render pass before performing additional commands；
改为开 pass 之前 `ensureCreated()`（与既有 MappableRingBuffer map/close 纪律同源）。|