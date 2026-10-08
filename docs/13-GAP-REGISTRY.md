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
⛔ 但 **8 槽画面是剪影全黑**：几何与槽位路由对，像素值不对（GAP-008： h31 曾在 OpenGL 上定位到 PARALLAX，但 h42 在 Vulkan 上复现黑屏且该修法不成立 ⇒ **保持开放**）。
✅🔧 **`h46-dev`（2026-10-05）：包的整条后处理链已接入引擎（开发增量，运行期取证待做）** —— `PackPostChain` 按 OF 族序选出 deferred*→composite*→final 全部全屏步（真实 BSL 单测：10 步成链、final 收尾、其它维度不混链），`location=colortex 槽号 → 附件下标`由 `PostOutputRenumber` 兑现（拒绝形态全抛），16 条**定宽 8 附件**槽位管线 + `FrameApi.drawPostChain` 按名路由视图（colortexN=池 / gaux1=colortex4 / depthtex=池深度 / shadow*=桩 / 自定义纹理与**内置 noisetex** 优先）；未写槽挂 scratch ⇒ 「既作附件又作采样器」构造性不可能（h26 族机制封堵）。用户点名的「packColor 采 scene 改采 colortex」= 本轮的推论而非特例。时序：链模式下**先地形 MRT 写 gbuffer 再跑链**。
⚠️ 能力边界：`DRAWBUFFERS:08367`（MCBL_SS + 高级材质同开）需 9 附件 > Vulkan 上限 8 ⇒ **显式拒绝接线**。⚠️ 不要再按「能力上限 5 槽」建 pass —— 生产实际是 **1 槽**（`h07`/`h08` 双证），混用会崩客户端（X42）。
| GAP-004 | 自定义 uniform 块无处安放：BSL/Iris 的 deferred pass 需要 `gbufferModelViewInverse`、`shadowModelView`、`shadowProjection`、`sunPosition`、`moonPosition` 等 OF 内建矩阵/向量，原版 `Globals` 仅 9 字段且不含这些 | 已源码级核实：bind group 布局在 `RenderPipeline` 构造时固化，无法给原版管线追加 uniform 块（见 GAP-003 ③）。**2026-10-03 实测补充**：派生管线多出的 bind group 条目**必须**在 draw 前 `setUniform`，否则驱动层 STRICT_VALIDATION 抛 `Missing uniform 名`（原版 `renderLayers` 只绑 `TerrainUniform`/`Sampler0`/`Sampler2`，没人会绑我们那条） | 随 GAP-003 一并在**派生管线**上构造独立 uniform bind group 布局，随管线一起注册；绑定由 M-01b 注入点补 | `pipeline/`（派生管线构造）+ `mixin/`（M-01b 绑定） | `mixin.bindTerrainParams`（M-01b，已实现） | 原版管线支持追加 uniform 块 | 🟡 **块已挂上、每帧绑定、且已被消费**（`h08`，2026-10-04）：派生 MRT 管线片元换成包的 `gbuffers_terrain` 后，**42 个块成员 + 5 个 sampler 一条不漏**绑定（未触发 `Missing uniform`），`VkDispBuiltins` 608 字节环由 `OfUniformManager` 按 std140 偏移每帧填值（🔴 原文「实测 0 validation error」已撤回：本机无 validation layer；绑定是否成立的判据是 draw 不抛 `Missing uniform` 且画面正确）|
| GAP-005 | 候选方案（非缺口，登记以免遗忘）：是否用成熟 GLSL 前端 **KhronosGroup/glslang + SPIRV-Tools** 替/辅自研 8 段转译器 | **联网核实（2026-10-02）**：glslang 与 SPIRV-Tools 为 **Apache-2.0 / BSD-3**，**可合法并入本 MIT 工程**（Fedora / openEuler / Arch 官方打包元数据三处一致）；glslang 支持完整 `#include`、`GL_*` 扩展、`-D` 宏定义与预处理开关，正是 OF 方言所需 | 三条路待比：① 维持自研；② 引入 glslang（C++ 依赖、需随 jar 分发或走原版通道）；③ 混合（自研做 OF 方言层，glslang 做 GLSL→SPIR-V） | `glsl/`（可能整体重构） | — | — | ⏳ **已登记，本轮不执行**（先做 G 系列 Rust vs Java 对比，其结论会影响是否值得重构转译链） |
| GAP-006 | Rust 原生路径的 FFI 安全边界：Rust `panic` 穿过 FFI 边界是 UB，会直接 abort 掉整个 JVM ⇒ **游戏崩溃** | 联网核实（2026-10-02）：Rust 官方 Nomicon 明确 —— `extern "C"` 收到 panic 会终止进程；必须 `catch_unwind(AssertUnwindSafe(…))`；且 `panic = "abort"` 时 `catch_unwind` 完全失效 | `17-NATIVE.md` §4.5 的 FFI 安全清单为强制门禁；`08-TESTING.md` §8.3 要求**故意触发一次 panic** 验证 JVM 不 abort | `accel/backend/native/`（仅「采用」裁决后存在） | 与 A/B 开关同键 | — | ⏳ 待实现（仅当 G 系列裁决「采用」） |
| GAP-007 | 地形顶点侧缺三条 per-vertex 数据：**方块 id（mat/recolor）与法线（normal）**。实测（`h08`）：BSL 的 `gbuffers_terrain` 顶点着色器按 `mc_Entity.x / 100` 推方块 id 来决定 `mat`（树叶/自发光/岩浆…）与 `recolor`（草/浆果），并把顶点 `Normal` 属性转成眼空间法线；而原版地形顶点缓冲 `DefaultVertexFormat.BLOCK` 只有 **4 个属性**（Position/Color/UV0/UV2），**既无 `mc_Entity` 也无 `Normal**` ⇒ 适配层只能按常量供值（`mat=0` / `recolor=0` / `normal=(0,1,0)`）| **根因**：地形网格化阶段没有写这两项；补它要改区块网格化产出，属渲染器层改动 | 方案 A：扩地形顶点格式（BLOCK → 加 `Normal` + `EntityId` 两属性，网格化侧逐顶点写入）；方案 B：改用 `DefaultVertexFormat.ENTITY` 作地形格式（已有 `Normal`，仍缺 `mc_Entity`）——**B 只解决一半**。两案都需另立注入点登记，且会改变内存占用 | `pipeline/model`（顶点格式）+ 网格化侧（新增注入点，待登记） | `terrain.vertexExtras`（**未实现**，占位键名以便将来一键关闭） | 原版地形顶点格式提供方块 id 与法线属性 | 🟡 **已定位、已量化、未实现**（`h08` §五逐条标注了三条常量项与各自影响面）|
| **🔖 供值分档更正（h41）** | 此前把 7 条一律记作「原版地形顶点缓冲**无对应属性**」**不准确**。字节码复核：`DefaultVertexFormat.BLOCK` = `Position, Color, UV0, UV2`（**4 条，无 Normal**）⇒ 真实情况分三档：<br>① **真值**：`Position / Color / UV0 / UV2`（外加 MULTIDRAW 时 `ChunkPosition / ChunkVisibility`）<br>② **部分真值**：`vTexCoord` / `vTexCoordAM` —— **`.xy` 就是真实的 `UV0`**，缺的是**图集重映射**（依赖同样缺失的 `mat`）与 `z/w`<br>③ **纯常量**：`mat, recolor, normal, binormal, tangent` —— 属性**确实不存在**<br>运行期摘要现分别报两档：`varyings=15（纯常量供值 5 条：mat, recolor, normal, binormal, tangent；部分真值供值 2 条：vTexCoord, vTexCoordAM）`<br>🔖 **为什么要分**：旧诊断让读者以为 `vTexCoord` **完全没有数据源**，与事实不符 —— 那样会误导后来人把它当成「必须改网格格式才能有」，而实际上它已有真值部分。诊断**不许说假话**（`07` X9）。
| GAP-008 | 高级材质（`ADVANCED_MATERIALS`）路径下 gbuffer 输出**像素全黑**。实测（`h09`）：8 槽接线全对（`colorTargets=8` / `slots=8` / 610 条 SOLID draw），画面却是绿色清屏底上的**纯黑剪影**；而默认配置（1 槽）同一机位画面正确可见 |
两条候选成因（**本轮未逐项二分验证，不假装已坐实**）：① 该路径会用在 GAP-007 里**按常量供值**的 7 条 varying（`normal` / `tangent` / `binormal` / `mat` / `vTexCoord*`）参与光照与材质分支；
② 新增的 `specular` / `normals` 采样器**绑的是方块图集占位视图**。两者相乘把 colortex0 压到 0 |
先做**逐项切分**（每次只放开一条常量项 / 换成真视图）定位压零的那一项；再按定位结果决定是补数据还是降级。
⚠️ **禁止**先猜一个「看起来对」的绑定再截图 —— 那正是本项目反复消灭的失败形态 |
`pipeline/model`（契约）+ `glsl/translate/PackVertexAdapterGenerator`（常量项表）+ 采样器绑定 |
`mrt.packTerrainShader`（已存在） | 默认配置路径画面正确 ⇒ 随时可退回 |
| `h10` 进度（2026-10-04，见 `evidence/h10-gap008-bisection-neutral-material-maps.md`）：
把乘法链**静态列全**（3 个候选：`ao*ao` / `1-metalness*smoothness` / `sceneLighting *= skylightSqr`），
然后**逐项切分**。① 候选 1/2（`ao` 与金属度）**已被实验证伪**：把 `specular`/`normals` 从方块图集
换成**中性材质贴图**（乘法单位元：`(0,0,0,255)` 与 `(128,128,255,255)`）后，**48.41% 的像素确实变了**
（证明两个采样器被读到了）**但画面仍全黑** ⇒ 它们不是主因。
② 候选 3 静态推演成立：`skylightSqr = lightmap.y²`、`lightmap = clamp(lmCoord, 0, 1)`，而原版把**天光与块光
打包进同一个 UV2**（`uv2.y` 恒 0）⇒ `lmCoord.y ≡ 0` ⇒ `sceneLighting ≡ 0`；
OF 语义下 `lmCoord` 应当是 `(块光, 天光)` 两条独立通道 ⇒ 疑似一处真实的映射错误。
🔖 **后续（`h12`）**：候选 3 被**实验证伪** —— `lmCoord` 满光照 `(1,1)`（单变量开关 `mrt.terrainFullLightProbe`）
下画面**仍纯黑**，且按推导本应过曝发白 ⇒ `albedo` 在进入 `GetLighting` **之前**就已是 0。
⛔ 另：本轮顺带揪出并修掉一个**每帧抛**的回归 —— 在 pass 打开期间懒建贴图上传 ⇒
IllegalStateException: Close the existing render pass before performing additional commands；
改为开 pass 之前 `ensureCreated()`（与既有 MappableRingBuffer map/close 纪律同源）。|
🟡 **已有正面证据：只有 `albedo` 为 0**（`h13`）：切到 **colortex3**（高级材质路径确实写的槽）→ 画面是**亮绿地形剪影**（`vec4(smoothness, skyOcclusion, 0, 1)` 的 `.g` 满值）⇒ **片元着色器完整跑完**，同一片元里光照/天光/法线/菲尼尔全部正常，**只有 `albedo` 是 0**。🔍 候选 6 子项①（图集 mip 链）**已排除**：`blockAtlas()` 返回的是**原版** `TextureAtlas.LOCATION_BLOCKS` 视图，mip 由原版生成填充。🔍 另外**画面独立验证了 DRAWBUFFERS 槽位路由**：槽 1 整幅纯清屏色（99.89%）、没有地形，正因为高级材质路径写的是槽 **0/3/6/7**。🟡 剩余疑独：`dFdx(texCoord)` 是否在反向 Z / MRT pass 下退化（**未验证**）|
🔴 `h15` 进度：**候选 6 被否**。探针在客户端确认命中 **2 处**（`dcdx`+`dcdy`，
顶点侧两开关均**关**，严格单变量），画面**仍全黑** ⇒ 候选 6 可定认否定。
🟡 **六个候选至此全部排除** ⇒ 排除法见底，下一步必须换策略（去证明**最上游**那一项）。
🔴 当常见发现：**`h08-B` 可能也是原版画面**（当时并未开 `terrainToMain`）
⇒ **我们从未真正看过自己 pass 输出的 colortex0 内容**。因此候选 4（首行 `texture()*color`）**降级为「未验证」**
（h12 当时当成「结构性排除、无需实验」，依据的是一张**没经 `terrain drawn into` 核验**的截图。
🔖 **教训**：结构性的推论也必须建立在**已验证的前提**上。
🔴 **新首要疑独**：**`color`**。两条路径首行逐字相同：
``vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);``
若 `color.rgb` 为 0 ⇒ `albedo ≡ 0`，**与 texture / textureGrad / 光照全无关**，
且这是**唯一一个还没被任何实验触及的因子**。
🔶 待实测：原版地形网格往 `Color` 里填了什么（`PutColor` 实参在全仓 grep 不到，网格化代码可能不在该 sources jar 内）。
🔍 `h14` 进度：派生导数探针（**转译第 7¾ 段** `DerivativeProbeAdapter`）已落地，
只把 `vec2 dcdx = dFdx(texCoord);` / `vec2 dcdy = dFdy(texCoord);` 的初值换成 `vec2(0.0)`（默认关、等行数、`finally` 复位、X45 自报）。
🔴 单测**当场抓到**探针**自己**一处致命缺陷（正则只匹配 `dFdx`，漏了 `dFdy`，
导致「单变量实验」实际只施加了半个变量，而日志只报 N 处不会提醒）。已修：分别捕获「名字方向」与「函数方向」并核对一致性。
🔴 客户端那趟**作废**：M-01 冷路径基准长时间占住渲染线程（`hit x6250000` 仍在跑），
地形片元源从未生成、**地形 pass 一次都没跑**（`terrain drawn into` = 0），探针连被调用的机会都没有。
🔖 当时的截图看着「完全正常」，但它是 `terrainToMain=false` 下的**纯原版渲染**，
原版永远看着正常，此时**「画面正常」不含任何信息**。🟡 候选 6 **仍未验证**，下一轮需先过两道闸（基准已跑完 + `terrain drawn into` 出现）。
✅ **`h28` 关闭了「`color`」这个疑独**（`h16` 当年因观测面被闪烁污染判为「不可判」；本轮闪烁已不复现，前提才成立）：诊断视图 `viewSlot=0`、采样纯地形带 **57,218 px**，把适配层的 `color` 强制成 `vec4(1.0)` 后 —— 对照臂与探针臂**逐像素都恰好 `RGB(0,0,0)`**（`maxR=0`、非黑 `0.000%`）⇒ **`color` 排除**。
🔶 顺带排除一个**假阴性风险**：包 VSH 里的 `color = gl_Color;` 本会覆盖探针赋值，但 `TerrainPipelineApi:218` 是 `withVertexShader(TERRAIN_PACK_ADAPTER_ID)` ⇒ **包的地形 VSH 根本不执行**（只用于推导 varying 契约）⇒ 探针值得信。
✅🔴 **`h29` 首次正面定位**（零代码改动）：静态确认 `lib/settings.glsl:87` 的 `#define PARALLAX` **未注释 = 开启**（对照相邻的 `//#define SPECULAR_HIGHLIGHT_ROUGH` 是包自己关掉的写法）⇒ `gbuffers_terrain.glsl:179` 的 `texture2DGradARB` 确实被编译进。只把 `BSL_v10.1.8.PARALLAX` 置 `false`（既有机制改写成 `#undef PARALLAX`，`#undef` 已实现且有单测）⇒ **同样的 57,218 px 从「逐像素恰好 0」变成「逐像素非 0」**（非黑 `0.000%→100.000%`、luma `0.0000→12.37`、`maxR` 从 **0** 变 **23/31**、色调 R>G>B 暖砂色）⇒ **压零项在视差分支内**，根因接到 **GAP-009**。
🔶 候选 6（`dcdx/dcdy`）**降级为「分支内的一个子项」**：整条 `texture2DGradARB` 被摘掉后地形正常 ⇒ 默认路径的 `texture2D` 与采样器绑定都没问题。
⚠️ **`h28`/`h29` 的判读对象更正（`h31` §五）**：那两轮截图用的是**诊断视图**（`viewSlot=0` = 原始 albedo 槽），**本来就不是用户看到的画面**；据此写下的「画面仍不正确」是**判读对象搞错了** —— 主目标实测两个落点都渲染出**完整、有光照、可辨认**的地形。🔖 教训：**「诊断视图」与「主目标」是两个观测面，结论必须写明是哪一个**；`albedo ≡ 0` 本身没错，但不能顺延成「用户看到的是黑屏」。
✅ **`h31` 附带一次用户可见修复（主目标，同一 57,218 px 采样区）**：修复前 luma **`0.0000`** → 关掉 `PARALLAX` 后 **`96.1485`**、`maxR` 0→253、非黑 `100.000%` ⇒ 「地形全黑」是**真的**，且**关掉视差即可消除**。
⚠️ **但那目前只是取证配置，尚未作为产品默认** —— 默认是否关、门控形态如何，属**待裁决**项（见 GAP-009 与 `review/2026-10-04-GAP009-素材缺失裁决简报.md`）。
| **🔴🔴 `h42` 推翻收尾结论（Vulkan 上未复现）** | `h31` 的收尾「关掉 `PARALLAX` 后 luma `0.0000`→`96.1485`、关掉视差即可消除」是 **OpenGL 产物**。`h42` 在 **Vulkan 主目标**上重做：<br>① **GAP-008 在 Vulkan 上照样复现**（门控关 = 近乎全黑，仅零星方块可见）；<br>② **门控开后更黑**（地形完全消失），而门控确实已关掉 `PARALLAX`（日志原文 `命中 9/9 [... PARALLAX=false ...]`）⇒ **`h31` 依据的那个具体动作在 Vulkan 上没有得到证实**。<br>🔖 **但两组不是单变量**：门控改写 **9 个**选项并改变派生程序形状（`outputs 8→1`、`samplers 7→5`、`varyings 15→9`）⇒ **不能**据此说「视差无关」，**也不能**说「是另外 8 项导致的」。<br>🔶 **另一个未分辨因素**：`mrt.terrainToMain` 注释原文「**会清掉主目标画面**」⇒ 两组黑屏里至少有一部分可能是「清屏了但地形 draw 没落到主目标」而非「包片元输出全黑」，**本轮未分开**，已登记为下一步。<br>⇒ **本条保持开放**，下一步 = ① 做真正单变量的 `PARALLAX` A/B；② 给主目标一个判别手段把「输出黑」与「没落到主目标」分开。证据 `evidence/h42-…` |
| **状态** | 🟢 **Vulkan 上已收窄到乘法链左侧**（`h45`，2026-10-05）：三臂单变量 A/B（`ADVANCED_MATERIALS=true;PARALLAX=false`，`colortex0` = 包的 albedo）—— 基线 `mean_luma 0.0000 allZero=true`；强制**采样侧**为 `vec4(1,0.5,0.25,1)` ⇒ `15.2644 / nonBlack 99.009%`；强制**乘子侧**为 `vec4(1,1,1,1)` ⇒ **仍 0** ⇒ **`texture(texture_0, texCoord)` 的返回值本身是 0**，而方块图集本身不黑（`mean_luma 87.5068 / nonBlack 74.214% / maxR 255`）。**已排除**：落点/接线、整条 draw 未出片元、附件绑定错、几何/深度全丢、清屏色混入、图集内容为黑、`color` 乘子为 0、**视差分支为成因**。🔴 **仍开放**：`texCoord` 是否落进图集透明填充（图集约 26% 像素为黑）/ LOD 与隐式导数。⚠️ **`h13`/`h29` 的「成因在视差分支内」已被推翻**（`PARALLAX=false` 即分支不存在时 albedo 依然 ≡ 0）；**`h31` 的「关掉 PARALLAX 即可消除」被推翻**（关掉后由 `1.4068` 掉到 `0.0000`） |
| **🔴 `h46` 四臂交叉（2026-10-05，Vulkan 实测）——主因改判为「隐式导数 LOD 选坏 mip」** | F 臂（坐标档·第一处赋值）读 0 → 一度判「texCoord≡0」；**F2 作废**（探针取错坐标名 shadowPosXY —— 探针自身也交了一轮学费）；**F3 臂**（输出直写坐标，跳过全部下游衰减）实测 `texCoord ≈ (0.43,0.26–0.35)` **非零** ⇒ F 臂判读撤回；**G 臂**（`terrainLodZeroProbe` 显式 LOD0）⇒ albedo 立刻非零（`TERRAIN_BAND (8.05,7.54,3.16)`）。四臂交叉 + h45（坐标/图集/乘子均排除）⇒ **唯一变量 = 隐式导数的 LOD 选择** ⇒ 止血 = `mrt.terrainAtlasLod0`（图集采样器 maxLod=0，默认开），根因转登记 **GAP-016**。<br>🔴 新前线（H 臂）：止血后 `main` 从全黑变**近全白（254.8）** —— 链上某级过曝，下一刀逐级二分。证据 `evidence/h46-post-chain-integration.md` E/F2/F3/G/H 逐字 |
| **🔖 `h43`（2026-10-05）：本机 Vulkan 上**没有**复现，且有了判别手段** | ① **这套配置下没复现**：`terrainToMain=false` + `capabilityGate=false`，`colortex0` **有内容**（`mean_luma 5.5936`、非黑 `28.005%`、`maxR 15`）—— 这正是 h42 登记的「主目标 vs colortex 两源对照」该给出的答案，答案落在「都有内容」那一格。<br>② **单变量 A/B（只改 `PARALLAX`，经新增 `pack.optionOverrides`）**：主目标 `mean_luma` **两臂逐位相同（16.6260）**，而 `colortex0` 变了（5.5936 → 2.4961、`maxR` 15 → 62）⇒ **视差确实影响 colortex0 的输出**，但在本机**没有把它压成 0**；因此**不能说「视差无关」**。<br>③ 🔴 **不能说「GAP-008 已修好」**：h42 复现它用的是 `terrainToMain=true`，而那一档**本轮仍未定位** —— 该档附件 0 被换成主目标视图，`colortex0` 不是附件；**本轮实测发现这一档若拿 `colortex0` 当对照会得到假数字**（已修：改测确实被写的槽；只有一个附件时明确不产出两源对照）。<br>④ 🟡 h42 §4.3 的「未分辨因素」现在**有了手段**（`mrt.pixelProbe`：GPU→CPU 回读 + 四分判定），但该手段在 `toMain` 档**需要 ≥2 个附件才可能生效**，而 BSL 默认配置只写槽 0 ⇒ **该档仍是下一步**。证据 `evidence/h43-…` |
| **🟢 `h44`（2026-10-05）：`toMain` 档**首次产出有效两源对照**，GAP-008 在 Vulkan 上完成定位** | ① 🔴⛔ **本条已被 `h48r`（2026-10-08）撤回**：原文称「`MrtPlan` 的注释是错的、`ADVANCED_MATERIALS` 取默认 **true**、⇒ 默认档就是 8 附件 `[0,3,6,7]`」。**两句都错**：(a) 包扫描日志逐字为 `option name=ADVANCED_MATERIALS type=BOOLEAN default=false`，`MrtPlan` 那条注释本来就对；(b) 那一臂的 8 附件不是包默认，而是**本地 store 残留** `run/h27/config/vkdisp-pack-options.properties: BSL_v10.1.8.ADVANCED_MATERIALS=true` 被 `ShaderPackCompiler` 改写进源（日志 `选项覆盖已改写进源: 命中 3/3 [PARALLAX=false, ADVANCED_MATERIALS=true, SHARPEN=3]`）；取证脚本传的 `pack.optionOverrides=""` 清的是**配置档覆盖**、`pack.capabilityGate=false` 让门控**不干预**，两件事叠加 ⇒ 残留一路穿过所有臂。真默认档由 `TerrainProductionOutputCountTest` 实测为 **1 个颜色输出**（`DRAWBUFFERS:0`）。🔖 **这是本项目「用残留状态反推默认值」首例**（与前四例「注释里的前提被当成事实」不同族：这里信息一直在日志里，是**没人读** `effective={…}` 那一行）。⇒ **本行 ②③④ 的数字全部改挂到「`ADVANCED_MATERIALS=on` 档」名下**，它们作为**那一档**的实测仍然成立（尤其 ④「albedo ≡ 0 而 3/6/7 正常」正是 GAP-009 门控要处理的那条依赖），但**不得**再被称为「BSL 默认档」。证据 `evidence/h48-flicker-and-readback.md` §二十二。<br>② 🔴 **推翻上一轮探针的「改测槽 1」**：槽 1 无包输出、只有清屏值 ⇒ 读它必然 `allZero=true` ⇒ 会报出**结论反了**的假证据（与「拿 `colortex0` 当对照」同族）。已改为**契约驱动**（新增 `PackTerrainProgram.declaredOutputSlots()` + `PixelProbePlan`）。<br>③ 🔴🔖 **推翻本条此前那张四分标签表**：它**不认档位**，而同一组布尔量在两档下**含义相反**（`toMain=false` 时主目标是原版画面，`toMain=true` 时主目标就是我方 pass 的**附件 0**）。实测 `main#N allZero=true` + `colortex3#N meanRGB=(0,255,0)` 同时成立时，旧标签读成 `NOT_ON_MAIN`（"draw 没落到主目标"）—— **说反了**：draw 恰恰**落到了**主目标，只是那里 albedo ≡ 0。已改为档位敏感（新增 `PixelProbeVerdict`）。<br>④ 🟢 **实测两档各独立给出同一结论**：`terrainToMain=true` 主目标（=albedo）`mean_luma 0.0000 allZero=true`，`colortex3/6/7` 分别 `(0,255,0)` / `(128,218,255)` / `(197.77,…)` 且 `nonBlack=100%`；`terrainToMain=false` 下 `colortex0` 全黑、3/6/7 同上。三槽取值与源码 `gl_FragData[1..3]` 的语义**逐字对得上**（`smoothness=0 + skyOcclusion=1.0` / 法线编码 / 菲涅尔常量）⇒ 排除清屏色混入。⇒ **albedo ≡ 0 而其余输出正常 = GAP-008 定义形态，在 Vulkan 上首次正面证明**；同时**排除**落点/接线、整条 draw 未出片元、附件绑定错、几何深度全丢。证据 `evidence/h44-…` |
| GAP-011 | 🔴 **（P0）地形渲染出问题时屏幕在「有画面」与全黑之间高频切换**（由用户现场观察确认，`h16`）
❌ **`h37`（2026-10-05，首次在真 Vulkan 上）又排除一个候选**：`mrt.shadowStubs` 开/关两臂各连拍 6 帧，黑色占比 spread 仅 **0.042 / 0.044 pp** ⇒ **闪烁不是「shadowtex 绑读写附件这个 UB」造成的**（至少 lavapipe 上不是）。两条臂都**复现不出闪烁** ⇒ 真实成因**仍未定位**。证据 `evidence/h37-…` |
| 🔶 **症状**：`terrainToMain=false` + 模组总闸开时，屏幕高频地在「有画面」与「全黑」之间切换。取证用**黑色像素占比**判定相位（判据由 `h22` 对照组校准，见下）|
| 🔴 **`h19` 推翻了两条旧归因**：① 「主目标被每帧清黑」（`h16`）——地形**逐像素正常**，主目标没被清；② 「我方 pass 泄漏渲染状态」（`h17`）——把 `captureTerrainDraws` 关掉后我方 pass **一次都没跑**，闪烁**依然在**|
| 🔶 ⇒ 闪烁与**我方 pass 绘制无关**。当前唯一未被排除的：**M-01 的管线替换**（`ChunkSectionLayer#pipeline` 返回值被换掉）|
| 🔍 `h20` 在该路径上找到并修掉**两个真实代码缺陷**（接线管线携带只在我方 pass 里绑定的孤儿绑定组；MRT 变体缺 attachment 数守卫），但 `h21` 实测**修复无效** ⇒ **它们不是闪烁的原因**|
| 🔴 **`h27` 撤回 `h24` 的归因**：它不是单变量对照 —— `h21`(有闪烁) 与 `h24`(无闪烁) 之间落了一个修复 `7206d6d`（GAP-010）。而 `h27` 用开关把该修复单独拉出来做 A/B（`resourceLoad/ERROR` 精确回到 12 条）⇒ **它无因果作用** ⇒ 「闪烁 = M-01 管线替换」**不成立**，闪烁消失的真实原因**至今未知** |
| ❌ **`h27` 证伪候选①**：包片元把 `shadowtex0/1`、`shadowcolor0` 绑到本 pass 的**读写附件**（Vulkan 未定义行为）—— A/B 里**故意**恢复该 UB（`mrt.shadowStubs=false`）仍然**不闪** ⇒ **不是**原因（但缺陷本身已修，见下） |
| ❌ **`h27` 证伪候选②**：`7206d6d`（GAP-010 适配层 memo）—— 精确复现前修复状态（C 组占比与 A 组**完全相同**）⇒ **对画面零影响**，更非闪烁原因 |
| 🔴 **`h27` 现状：当前 HEAD + 当前配置复现不出闪烁**（4 组共 **82 帧**全部单相位）⇒ 🔴 **缺少可观测现象，无法继续归因** |
| 🔶 **`h27` 找到一个从未登记的名义变量：窗口尺寸** —— 所有「有闪烁」证据（`h16`/`h17`/`h19`/`h21`/`h22`）都在 **854×480**（= MC 窗口默认尺寸 ⇒ 当时没人改过窗口），`h25` 之后全在 **930×577**（被改过、**无记录**）。⚠️ **不是充分条件**（`h24` 同为 854×480 却无闪烁）。🔴 **本机不可测**：WSLg/XWayland 无 WM —— `XResizeWindow` 段错误、自研 `ConfigureWindow` 请求无错但尺寸被服务端改回、`F11` 全屏也不生效 ⇒ **已知阻塞** |
| 🔶 **`h27b` 在隔离车道独立复现 + 顺手定位一个真实缺陷**：`build.gradle` 新增 `clientIso`（`gameDirectory = run/h27`，端口 25600）⇒ 同二进制两臂 44.37%×6 vs 44.41%×6（同相位）**独立复现 `h27` 的证伪**；🔶 **两臂都落在 854×480**（即上面那个名义变量的尺寸）**仍不复现** ⇒ 该假设进一步削弱（未严格排除）。🔴 **顺带定位：「绿天空」不是神秘故障** —— `MrtTerrainPass#diagnosticClear` 槽 0 就是纯绿 `(0,1,0,1)` 且 `:350` 处**无条件**应用、**无诊断开关**；我们的 pass **只画地形** ⇒ 天空那片保持纯绿，而 composite 采 `colortex0` ⇒ **绿天空直接进最终画面**（与 `h13` 的 `colortex3` 亮绿剪影同一套配色）。⚠️ **这是要修的产品缺陷**：诊断清屏色泄漏进用户可见画面。⚠️ **只定位未修**（诊断色当初刻意选：槽 0 指纹恰为 `0.0`=黑，会让「没画」与「很暗」在截图上无法区分 ⇒ 改它要同时保留该区分能力，属独立一轮）。 |
| 🔖 **`h27b` 落地两件取证基础设施**：① 判据**工具化** —— `evidence/tools/flicker_ratio.py`（h22 判据此前无工具，`h25`/`h26`/`h27` 的数字都是临时算的、无法复算；采样区 x∈[45%,65%]/y∈[15%,75%] 是用前缀和在 12 张已入库截图上**反解**出 h22 文档那句「64000 px」；`--selfcheck` 复现已公布数字 ±0.25pp）；② **隔离车道** —— 同仓库并发跑客户端会互相覆盖 `run/config` 并抢端口（实测：`sed` 改完读回确认，53 秒后被孤儿客户端重写回旧值 ⇒ **B 臂根本没测到想测的东西而日志看起来完全正常**），换 game directory 时**包选项覆盖与辅助 mod jar 必须显式带过去**（前者漏带会让 `ADVANCED_MATERIALS` 丢失 ⇒ 被测 sampler 压根没被声明 ⇒ A/B **静默变成空转**）。 |
| 🟡 闪烁根因：**仍未定位**（`h27` 已把候选①②排除，③④ 未测）|
| 另有一条**未解释**的现象：全黑相位里**连地形都没有**，而「有画面」相位地形正常（`h17`/`h19`）|
| 🔎 **取证判据（`h22` 校准，务必先读）**：
| ❌ 唯一哈希数 **不可用** —— 模组总闸**关**（纯原版、毫无异常）时同机位连拍 6 帧也是 **6/6 全不同**|
| ✅ 改用**黑色像素占比**：**> 90%** ⇒ 全黑相位；**< 60%** ⇒ 有内容相位。对照组（纯原版）上界 ~20%（天空是蓝的），实验组下界 52.8% ⇒ **区间内无样本，阈值可用**|
| `dev.vkdisp.bridge.TerrainPipelineApi` + `MrtTerrainPass` | `mixin.wireTerrain` + `mrt.terrain*` | 🔴 **根因未定位**（`h21`）|
| 🔶 **`h24` 单变量对照：闪烁根因坐实 = M-01 的管线替换**。只关 `mixin.wireTerrain`（其余全开：我方 pass 照跑，
`terrain drawn into`=1、`M-01 wired`=0）⇒ 6 帧黑色像素占比**全部 51.3%**，**无一进入全黑相位** ⇒ **闪烁消失**|
🔰 **但这不等于「关掉就好」**：M-01 正是 GAP-003 的**通道本身**（`ChunkSectionLayer#pipeline` 的返回值可被替换），
关掉它 = 关掉通道 ⇒ 这是「**用替换管线接管原版地形绘制**」这条路线本身代价过高，而不是一个孤立 bug|
🔴 **`h24` 同时拆出第三个独立缺陷：黑天与闪烁无关**。`wireTerrain=false` 下地形完全正常，但**天空仍是黑的**
⇒ 「闪烁」与「黑天」是**两条不同的因果链**，此前一直被当成一个 ⇒ 混在一起会让两边都定位不到|
🟡 **仍未定位**：① 管线替换**为何**导致闪烁（编译产物替换 / `getCompiledPipeline` 缓存 / 多套管线交替编译，均未验证）；② **黑天**的成因|
❌ `h24` 顺带**排除**两条（纯静态）：① 「派生管线状态不一致」（逐项核对原版 `RenderPipelines`：基底 snippet / color target /
`ALPHA_CUTOUT` / 绑定组 / depth stencil **全部相同**）；② 「某个调用点拿到错东西」（原版 4 处 `layer.pipeline` 调用**全部等价**，
其中 `LevelRenderer:796` 与 `SectionRenderDispatcher:76` 只取顶点格式，而派生管线继承同一 vertex binding）|
| GAP-010 | 🟢 **已修**：地形顶点适配层的 memo 被**片元的 take 顺带抹掉**，首轮资源重载时找不到它的 VERTEX 源（实测 `h11`）|
🔶 根因不是「登记太晚」，而是**多余的一次清空**：`takeTerrainSourceMemo()` 取走片元 memo 时顺手清了适配层 memo，
而调用点恰好先片元后适配层 ⇒ 适配层永远拿到 null ⇒ `PipelineBuilder` 报 12 条 ERROR。
代码注释原本写着「两者要么都给、要么都不给」，实现与注释的意图正好相反：不是同批取走，而是把适配层丢掉了。
**已修**：删掉那行多余的 `terrainAdapterMemo = null;`，并在原地写清为什么不能有它（适配层自己的 take 方法会清自己）。
🟡 当时写的是「第二轮重载成功，不致命」；实际是永久抹掉适配层，自愈仅因为资源又重载了一次。
🟢 **已修**：修复后 `resourceLoad/ERROR` **12 → 0**（`h23`），回归测试 `PackTerrainMemoTakeTest` 4 条 |
🔖 「诊断意图与实现不同」这条比时序本身更值得记：当时按「提前登记」去想，而缺口其实是**取用顺序**。
`VkDispVirtualPack.takeTerrainSourceMemo()` | `mrt.packTerrainShader` | 🟢 **已修**（`h23`）|
| GAP-009 | 高级材质路径需要的**逐方块材质贴图集**（LabPBR 的 `_n` / `_s`，Iris 经 `uniform sampler2D normals` / `specular` 两张**额外 atlas** 暴露）本引擎没有。实测（`h10`）：包片元用 `textureLod(specular, …)` 取光滑度/金属度/孔隙/自发光遮罩，用 `textureGrad(normals, …).z` 取 AO |
它们是**资源包附带的一整套逐方块材质贴图**（LabPBR 格式规范），不是本项目能就地生成的资产；不引入也不假装有 |
缺省绑**乘法单位元**（`specular=(0,0,0,255)` / `normals=(128,128,255,255)`，语义=「没有材质覆盖、没有 AO、法线朝上」），
并一次性 INFO 说明「这是缺省不是材质贴图」；将来接入真资源集时替换这两个绑定即可 |
`bridge/NeutralMaterialMaps`（中性缺省）+ 未来一个 LabPBR atlas 加载器（未建） | `mrt.packTerrainShader`（已存在） | 
默认配置路径不经过 `GetMaterials`，画面正确 ⇒ 随时可退回 | 
🟡 **缺省语义已落地并验证**（`h10`：两个采样器确实被读到 —— 48.41% 像素变化）；**A 方案已裁决（内存覆盖 + 按包依赖关程序），真材质贴图集（B）未实现** |
🔴 **`h29` 追加的新事实（这条改变了本条目的性质）**：缺的不只是「材质贴图」，**还有「材质贴图的 UV 空间」**。
我们给 `vTexCoord` / `vTexCoordAM` 的中性缺省是**方块图集 UV**（`PackVertexAdapterGenerator:185-188`：
`vTexCoord = vec4(UV0, 0.0, 0.0)`），而包的高级材质路径里 `GetParallaxCoord()` **从 `vTexCoord.st` 起步**
并 `ReadNormal(coord)` 去采**材质贴图集**（`lib/surface/parallax.glsl:7,20`）⇒ **两套 UV 空间错配**
⇒ 采样落进透明黑区 ⇒ `albedo ≡ 0`（= GAP-008 的正面定位，见该条目）。
🟢 **`h48s`（2026-10-08）把这条收紧到「绑 `ADVANCED_MATERIALS` 一项，不是门控闭包整体」**：
真默认档 + `pack.capabilityGate=false`（⇒ `PARALLAX`/`SSS`/`REFLECTION_*`/`SELF_SHADOW` 全按包默认为真、
门控不干预）实测地形**照样有画面且更亮**（240 帧 0 空帧，luma `min 62.74 / med 143.21 / max 150.16`）
⇒ 压零的开关是 `ADVANCED_MATERIALS` 那一条 `GetMaterials` 路径，闭包里其余项在这台后端上**不产生黑**。
⇒ 两个实际后果：① 门控的**默认值不是闪屏的成因**（h48r/h48s 两臂都是 0 空帧），故本轮**不动**它；
   ② 登记表此前把「8 附件 + albedo≡0」当成 BSL 默认档，其实是**残留档**（GAP-026），
      默认档只有 1 个输出、不走 `GetMaterials` ⇒ 与 `:132` 那句「默认配置路径不经过 `GetMaterials`」重新对上。
🔧 **`h46-dev` 素材线第一步**：`texture.<sampler>=path` **两段键**已能按名加载真纹理
（`bridge/PackTextures`：冷路径记绑定、渲染线程开 pass 前上传、失败逐条可见）；
`noisetex` 补上**内置 64×64 确定性噪声**（OptiFine 公开 API 事实：该采样器由引擎供给 ——
BSL 的 blue-noise 抖动/胶片颗粒自此有真值）；**三段键**（`texture.composite.colortex7=tex/dirt.png`）
的「按程序限定 sampler 绑定」语义**未核实 ⇒ 不收、点名拒绝**（X9），核实后再接。
🔖 所以「补了中性缺省」**并不足以**让这条路径正确 —— 缺的是**逐方块材质 UV**（或让路径别走）。
⚠️ **因此本条目已升级为一个待裁决项**：素材缺失时走 **(A) 按能力关掉依赖缺失素材的特性** 还是
**(B) 补最小材质集语义**？决策输入与两个落点的实测对比见
**`review/2026-10-04-GAP009-素材缺失裁决简报.md`**（`h31`：两个落点的主目标画面**逐像素同值**，
内部规模 `8/7/15` vs `1/5/9` ⇒ **建议落 `PARALLAX` 层**）。
🟢 **A 已实现（2026-10-05，`h32`）** —— 类 `config/PackCapabilityGate`（纯逻辑，16 条单测），
判据来源 `pack/properties/PackLangFile`（读 `shaders/lang/*.lang`），接线在 `pack/PackTerrainSource`，
开关 `pack.capabilityGate`（**默认关**）。设计要点：
- 🔖 **读 lang 是必需的**：BSL 声明「本选项依赖资源包提供的材质贴图」的**唯一**机制是
  **显示名末尾的 `*`**（实测 `en_US.lang` 19 条）⇒ 不读就只能硬编码 `PARALLAX`，
  而 Complementary 同样有视差却**零外部依赖**（实测零星号）⇒ 硬编码会砍可用特性（X27）。
- 🔖 **星号只有一处表示**：`PackLangFile` 剥掉星号、单独收进 `starMarkedOptions`，
  门控只认那一份。⚠️ 首版按「标签里带星号」判定而解析器已剥星号 ⇒ **门控恒空转且看起来完全正常**
  （最该消灭的静默失效）。🔖 教训：**同一个语义不要有两处表示**。
- 🔖 **只关布尔**、**不关本来就是关的**（否则日志会把「它本来就关着」说成「我们关了它」，
  下次取证误判归因）、**不写用户文件**（裁决：不采用改写用户配置，Iris 亦无先例）。
- 🔖 **作用域**：只在「包地形片元被接到派生 MRT 地形管线」时生效 ——
  首版错接在 `PackCompositeSource`（composite/deferred/final 三个**全屏**步），
  会在「地形没接线」时**白白砍包特性**（X27）⇒ 已改接 `PackTerrainSource`。
- ⏳ **runClient 未跑**（`.java` 改动按 `07` 规须取证，用户协助）⇒ 实现已落地但**未客户端验证**。

🟢 **已裁决（用户 2026-10-04）：采纳 A 的形态，B 留作后续正解。** 落地口径：
- **A（立即止血）** = **只在内存里覆盖选项 + 按包自己声明的依赖关程序，不写任何用户文件**。
  形态取自 Iris 的 `program.<name> = <表达式>`（`ShaderPack.java:257-265` 分派 / `:290-295` 执行，
  禁用 = 返回空源码而非替换成 fallback）。**不采用「改写用户 `optionsv2.txt`」** ——
  Iris 自己从不因能力缺失改写用户配置（只写用户改过的值，且等于默认值的项被 `remove()` 掉），
  无先例，属本项目自担信任成本的设计。
  🔖 门控**不必硬编码 `PARALLAX` 一个名字**：BSL 用**显示名末尾加 `*`** 标记依赖
  `ADVANCED_MATERIALS` 的选项，共 **18/362 项**（`PARALLAX*` / `SSS` / `EMISSIVE` / `ALBEDO_METAL` /
  `SELF_SHADOW*` / `REFLECTION_*` / `DIRECTIONAL_LIGHTMAP*` / `NORMAL_DAMPENING`）⇒ 读这个依赖闭包即可。
  ⚠️ `*` 是**本地化显示名**，非 ASCII 资源包或改过 lang 的包上不可靠 ⇒ 判定须落到
  `optionsv2.txt` / `shaders.properties` 的选项定义，不能只靠 lang 文件。
- **B（后续正解，重新定义）** = **实现 LabPBR atlas 加载 + 中性回退**，**不是**「打包一套材质资源」。
  理由（2026-10-04 核实）：`_n`/`_s` 两张图是 **LabPBR 格式规范**，由**资源包**提供
  （Iris 文档 `shaders.properties/current/how-to/pbr_standards`：法线图同名加 `_n` 后缀、
  高光图加 `_s`），靠 `uniform sampler2D normals` / `specular` 两张**额外 atlas** 暴露
  ⇒ **本引擎内部永远补不出来**，它依赖用户是否装了 PBR 资源包。
  ⇒ 打包材质资源既有许可风险（LabPBR 规范页无 license 声明），也与生态分工不符。
  🔖 本条根因因此**不是「我们缺资源」，而是「我们缺 loader」**，`bridge/NeutralMaterialMaps`
  已经是这个 loader 的中性缺省半成品。
  📌 **通道语义（解释实测现象）**：`normals` 的 **A 通道 = 视差高度**；A=0 ⇒ POM 把 albedo
  乘成 0 ⇒ 精确对应实测的 `luma 恰好 0.0000`。（Iris 的回退 `NORMAL=0x7F7FFFFF` A=255，
  而 vkdisp 若绑全零纹理则 A=0 ⇒ **须实测 vkdisp 绑的是哪个**，这决定 A 与 B 谁更合适。）
- 🟢 **一个重要的范围澄清（2026-10-04 核实）**：Complementary Reimagined **同样有视差
  （`PARALLAX*` / `SELF_SHADOW*` / `PARALLAX_SLOPE_NORMALS`）但零外部依赖** ——
  实测 `shaders/lib/surface/parallax.glsl` 与 `materialGbuffers.glsl` 里 `texture()`/`sampler`
  引用**均为 0 处**，它复用原版 `terrain` atlas 的亮度/高度。
  ⇒ **本条目是 BSL 选择 LabPBR 路线的特有问题，不是 shader 生态的普遍约束。**
  ⇒ 门控**必须逐包判定**，不得写成「视差一律关闭」。


🔶 **顺带查出的相邻风险（已登记，下一轮处理）**：我们只显式处理 5 个 sampler，
`noisetex` / `colortex9` / **`lighttex0`** / **`lighttex1`** 落到 `default -> atlas`；
其中 `lighttex0/1` 是 **`sampler3D`**（OF 体积光照贴图），拿 **2D** 图集视图去喂
= 描述符类型不匹配 = UB，且**本机无 validation layer ⇒ 不报错**（同 `h27` 的别名问题）。

> 🔖 **`h37`（2026-10-05，首次在真 Vulkan 上做受控 A/B）的否定式结论**：
> 本项目第一次在 Vulkan 后端上把「`mrt.shadowStubs=true`（绑专用 1×1 桩）」
> 与「`mrt.shadowStubs=false`（**故意**绑本 pass 的读写附件 = UB）」各跑一遍，
> 同存档 / 同机位 / 同 `dayTime` / 同天气：
> **HUD、hotbar、地形三带逐像素差均为 `0.000`**，只有天空那点差来自两次采图之间云层在动。
> ⇒ **该修复在本机（lavapipe）测不出任何画面差别**。
> 🔖 **但不应据此撤掉**：Vulkan 规范明文禁止「同一 image 既作读写附件又作采样器」，
> 这是 UB —— 规范允许驱动做任何事，lavapipe 恰好处理了不等于别的驱动也会。
> ⇒ 本条**只有规范层面的依据，没有本机复现证据**；此前若写过「修复后闪烁消失」之类因果结论，
> 那是没有证据的（与 `h27` 撤回 `h24` 归因同一纪律）。证据：`evidence/h37-…`

### GAP-012 · 🔴 sampler 维度不匹配（**2026-10-05 已修**）

| 项 | 内容 |
|---|---|
| **需求来源** | GAP-009 顺带查出的相邻风险（上一条） |
| **原版现状（实测）** | 本引擎对**未识别名字**的 sampler 一律 `default -> atlas`（2D 图集视图）。实测扫 BSL v10.1.8 全包：`sampler2D` 34 个名字 / `sampler2DShadow` 3 个 / **`sampler3D` 4 个**（`lighttex` / `lighttex0` / `lighttex1` / `voxeltex`）⇒ 这 4 个**拿到了 2D 视图** |
| **为什么是 UB** | `sampler3D` 在 Vulkan 里要求描述符类型是 **3D 图像视图**；喂 2D 视图 = 描述符类型不匹配 = **未定义行为**（驱动可丢 draw / 给垃圾 / 无事发生），且本机**无 validation layer ⇒ 一层都不报错** |
| **补充方案** | ① 新增 `pipeline/model/SamplerDimensionPlan`：**从片元声明的 sampler 类型**读维度（不是从名字），产出「名字 → 视图类别 + 理由」的**可单测纯数据**；② 新增 `bridge/VolumeStubs`：4×4×4 `RGBA8` 全 0 的 **3D 桩**（语义 = 「无体积光照/无体素数据」），用 `clearColorTexture` 一次清成（`writeToTexture` 只写单层）；③ `TerrainPipelineApi#bindPackTerrainUniforms` 改为按 `Binding.kind` 分派 |
| **新增的「响亮失败」** | cube 采样器与**任何不认识**的 sampler 类型 ⇒ **不绑** + ERROR。宁可让 draw 抛 `Missing uniform`（可定位），也不拿 2D 视图冒充。🔖 这是与旧 `default -> atlas` 的根本区别：旧实现在这里**总能**绑出「看起来能用」的视图 |
| **影响面** | `pipeline/model/SamplerDimensionPlan`（新）+ `bridge/VolumeStubs`（新）+ `bridge/TerrainPipelineApi` |
| **开关** | 无独立开关（正确路径即默认；A/B 逃生舱是 `mrt.shadowStubs`，本条不受它影响） |
| **回退条件** | 原版提供类型正确的 3D 纹理视图（如真正的体积光照贴图）⇒ 换绑定源，决策层不动 |
| **状态** | 🟢 **已修**（`h32`，2026-10-05：纯逻辑单测 15 条）。⚠️ **`h33` 实测发现该修法的前提不成立** —— 原版 26.3 **建不出 3D 纹理**，3D 桩不可达；本条已由 **GAP-014** 接管，并连带更正本条目「4 个 `sampler3D`」（实为 **3** 个）与 `h32` §6.1 的 `VOLUME_3D=4` 判据（实为 **0**，且不可达） |
| **⚠️ 明确不承诺** | 包基于体积光照的**体积光 / 体积 AO 效果在本引擎上不成立**（无真资源，且按 GAP-009 裁决不打包第三方光照/材质资产）。这比「喂 2D 图集」诚实 —— 后者可能碰巧「看起来有东西」，换驱动就变 |

### GAP-013 · 🔴 诊断清屏色泄漏进用户画面（**2026-10-05 已修**）

| 项 | 内容 |
|---|---|
| **需求来源** | `h27b` §六 定位（当时**只定位未修**，理由：改它要同时保留「没画 vs 很暗」的区分能力，属独立一轮的取舍） |
| **原版现状** | 旧 `MrtTerrainPass#diagnosticClear` **无条件**把槽 0 清成**纯绿** `RGB(0,255,0)`。因果链：我方 pass **只画地形** ⇒ 天空那片区域**从不被画进 gbuffer** ⇒ 保持纯绿 ⇒ 包的 composite 采 `colortex0` ⇒ **绿天空直接进最终画面** |
| **为什么不只是「把绿改成黑」** | `MrtPlan` 给槽 0 的**指纹恰好是 `0.0`（黑）** ⇒ 一旦「什么都没画」与「画了但很暗」同时发生，两者在截图上**无法区分**（旧实现的注释记录了这个踩坑）。直接改黑 = **删掉一项可诊断性** |
| **补充方案** | 新增 `pipeline/model/TerrainSlotClear`（纯逻辑、可单测）分两种模式：**诊断模式**保留高对比逐槽色（绿/蓝/品红）；**生产模式**零值清屏。🔖 判据是**调试视图是否激活**（`mrt.enabled`）而不是「`mrt.terrain` 是否开着」—— 取证时两者常同时开，但用户看到的画面必须是生产语义 |
| **保留可诊断性的另一半** | 生产模式打**一次** INFO 明说「天空黑是**预期行为，不是故障**」（此前是纯绿 = 诊断色泄漏，见 `h27b §六`）。不这么做，取证者会把「设计如此」误读成「又坏了」 |
| **影响面** | `pipeline/model/TerrainSlotClear`（新）+ `bridge/MrtTerrainPass`（`diagnosticClear` 移除，改为模式决策） |
| **开关** | `mrt.slotDiagnosticClear`（A/B 逃生舱，**默认关**；开启即 WARN 自报「若本帧进了用户画面会呈现假色天空」） |
| **状态** | 🟢 **已修**（`h32`，2026-10-05：单测 10 条）。✅ **`h33` 已补齐 runClient 取证**：A 组（`mrt.enabled=true`）天空带平均 RGB `(35.9, 236.5, 35.7)`、偏绿像素 **79.01%**；B 组（`mrt.enabled=false`）`(44.0, 43.2, 44.2)`、偏绿 **0.00%** ⇒ 判据「默认打 NEUTRAL 一次、天空黑不是绿」**达成** |
| **⚠️ 仍不承诺** | **黑天空本身仍未修**，且**不是**本条能修的：正确的天空要由包的 gbuffer 程序去画 ⇒ 属 **M-04（未做）**。零值只是「不含假信息」（黑不骗人；纯绿会让用户以为本项目画了绿天） |

### GAP-014 · 🔴 原版 26.3 不支持 3D / 数组纹理（**2026-10-05 已登记并绕开**）

| 项 | 内容 |
|---|---|
| **需求来源** | GAP-012 的**修法被原版能力直接否掉**：那条的方案是「给 `sampler3D` 绑一个类型匹配的 3D 桩」，而桩本身建不出来 |
| **原版现状（字节码级核实）** | `com.mojang.renderpearl.frontend.FrontendGpuDevice#verifyTextureCreationArgs`：`depthOrLayers > 1` 且非 cube 数组 ⇒ **无条件** `throw UnsupportedOperationException("Array or 3D textures are not yet supported")`；cube 数组 `depthOrLayers > 6` ⇒ `"Array textures are not yet supported"`。🔖 该类在 `frontend`（**前后端共用层**）⇒ **与 OpenGL/Vulkan 后端无关**，不是环境现象 |
| **实测来源** | `h33`（2026-10-05，MCP 驱动 runClient）：`VolumeStubs.ensure` 首行即抛，整条地形 MRT pass 每帧失败 1940 次 |
| **补充方案** | **不做补充**（无法补）。改为：`VolumeStubs.init()` 探测并**记录**失败（不再抛、不再每帧重试）；`view()` 返回 `null` ⇒ 沿用 GAP-012 已定的「**不绑 + ERROR** ⇒ draw 抛 `Missing uniform`」响亮失败路径 |
| **为什么不喂 2D 图集** | 那正是 GAP-012 要消灭的 UB（描述符类型不匹配，且无 validation layer ⇒ 不报错） |
| **为什么不删掉这些 sampler** | 布局多于 SPIR-V 无害；删掉会让「包声明了它」不可见（可诊断性损失） |
| **影响面** | `bridge/VolumeStubs`（探测 + 按需报错）+ `bridge/MrtTerrainPass`（`ensureTargets` 去级联，见 `h33` §三） |
| **开关** | 无独立开关（能力缺失无法开关；A/B 逃生舱仍是 `mrt.shadowStubs`） |
| **回退条件** | 原版放开 3D / 数组纹理创建（`FrontendGpuDevice` 对 `depthOrLayers > 1` 不再抛）⇒ 改回绑定真 3D 桩，决策层不动 |
| **状态** | 🟡 **已登记并按「不绑定 + 响亮失败」处理**（`h33`；runClient 三组取证，C 组该分支未被触发 ⇒ 因包未声明） |
| **⚠️ 明确不承诺** | 依赖体积光照 / 体素数据的**包特效在本引擎上不成立**。BSL v10.1.8 全包实测有 **3 个** `sampler3D`（`lighttex0` / `lighttex1` / `voxeltex`，**没有**无下标的 `lighttex` —— 更正 GAP-012 条目里的「4 个」），且它们**不在 `gbuffers_terrain` 的采样器里**（该文件自身 `uniform samplerXX` 数为 0）⇒ 本条在 BSL 地形路径上**不会**被触发 |
| **🔖 连带更正** | GAP-012 `h32` §6.1 写的验收判据「应出现 `VOLUME_3D=4`」**不可达且前提错误**（假定原版能建 3D 纹理）。实际 `by dimension` 里 `VOLUME_3D` 为 **0** —— 这本身就是本条的结论，不是「没跑到」 |
| **⚠️ 同族第三例（✅ `h40` 已修）** | `sampler2DArray` / `sampler2DArrayShadow` 曾落进普通 2D 占位分支 ⇒ **拿一张 2D 图冒充数组纹理**（Vulkan 要求 Arrayed=1），与本条、GAP-014 **完全同族**且同样不报错。`h40` 已改为 `UNSUPPORTED` + 可见告警。**判据不是「类型像不像 UB」，而是「它在本包地形程序里出现几次」**：BSL 全部 **274 个着色器源里 0 次** ⇒ 零代价；`shadowtex0/1` 每种配置都在 ⇒ 只能保留绑定 + 明示（见 GAP-015）。证据 `evidence/h40-…` |

---

### GAP-015 · 🔴 原版**没有「比较采样器」能力** ⇒ `sampler2DShadow` 无法类型匹配绑定（**2026-10-05 新登记**）

| 项 | 内容 |
|---|---|
| **需求来源** | GAP-012 的**同类问题再次出现**：这次不是「3D 视图喂给 sampler3D」，而是「**非比较**采样器喂给 `sampler2DShadow`」 |
| **原版现状（源码级核实，`h38`）** | 从 `minecraft-patched-26.3.0.41-beta.jar` 逐类反汇编：<br>① `GpuDevice` 只有**一个**工厂 `createSampler(AddressMode, AddressMode, FilterMode, FilterMode, int, OptionalDouble)` —— **签名里没有 `CompareOp`**；<br>② `SamplerCache.getClampToEdge(FilterMode, boolean)` 的那个 `boolean`，经 `LocalVariableTable` 核实是 **`useMipmaps`**，**不是** `compare`。<br>⇒ **拿不到 `VkCompareOp != NONE` 的采样器** |
| **⚠️ 顺带发现**（✅ **h39 已修**） | `SamplerDimensionPlan` 曾对 `shadowtex*` **按名字无条件优先**，可让 `sampler3D shadowtex0` 拿到一张 **2D 深度图** = GAP-012 同类 bug。h39 已改为**名字只选来源、声明类型约束维度**（非 2D 声明一律走类型路径）；`decide()` 同时从 63 行拆到 **36 行**（被 QD-04 棘轮拦下后重构，**未上调基线**）。证据 `evidence/h39-…` |
| **为什么是 UB** | Vulkan 里 `sampler2DShadow` 要求描述符带**比较**采样器；喂非比较采样器 = 描述符类型不匹配 = **未定义行为**（与 GAP-012 同族）。且本机**无 validation layer**（`h37` §八：全盘搜到的三个 `VkLayer_khronos_validation` **全是 Windows `.dll`**）⇒ **不报错** |
| **当前做法与取舍** | **照样绑**（深度视图 + 非比较采样器），并打**一次性** WARN 说清「阴影项结果不可信」。<br>🔖 **为什么不学 GAP-012/014 那样「不绑 + 报错」**：那两条的对象（`sampler3D` / cube）在本包**地形程序里是 0 条**，不绑不影响渲染；而 `shadowtex0/1` **每种配置都在**，不绑 ⇒ 每个用阴影的包 draw 抛 `Missing uniform` ⇒ **地形整条不渲染**。按支柱①（兼容优先），「画面里阴影不可信」优于「地形完全不画」 |
| **影响面** | `bridge/TerrainPipelineApi`（一次性说明）+ `pipeline/model/SamplerDimensionPlan`（`shadowtex*` 目前**按名字**而非按类型分类，见下） |
| **开关** | 无独立开关（**做不出**正确的绑定；做成可关只会把「阴影不可信」换成「地形不渲染」） |
| **回退条件** | 原版 `GpuDevice` 增加带 `CompareOp` 的采样器工厂 ⇒ 改绑比较采样器，决策层不动 |
| **状态** | 🟡 **已登记并明示**（`h38`：一次性 WARN + 守卫测试） |
| **⚠️ 顺带发现** | `SamplerDimensionPlan` 对 `shadowtex*` 是**按名字**分类（注释原文「它们全都是 2D ⇒ 先按名字定」），**没有**走它自己那条「按声明类型定维度」的规则。这与 GAP-012 修复的初衷不一致，已登记为待改项（改它会让「声明成 `sampler2D` 的 shadowtex」拿到 RGBA 桩而不是深度桩，属独立一轮） |

---

---

## 2. 字段说明

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

### GAP-016 · 🔴 包片元对方块图集的**隐式导数 LOD 选了坏 mip**（2026-10-05 登记，h45/h46 交叉判出）

| 字段 | 内容 |
|---|---|
| **症状（正面定位）** | 包地形片元 `texture(texture_0, texCoord)` ≡ 0（h45 左档），而 **同一行**换 `textureLod(texture_0, texCoord, 0.0)` ⇒ albedo 立刻非零（h46 G 臂：TERRAIN_BAND `meanRGB=(8.05,7.54,3.16)`，`colortex3/6/7` 形态照旧）；h46 F3 臂又证明 **texCoord 数值本身正常**（≈(0.43,0.26)，非零）⇒ 三臂交叉：坐标正常、图集非黑（h45 87.5）、乘子正常（h45 D 档） ⇒ 唯一变量 = **隐式导数的 LOD 选择**（`textureGrad` 显式梯度路径同 mip 也非零，h45 线索自洽） |
| **本机 workaround** | 图集采样器 `createSampler(..., maxLod=OptionalDouble.of(0.0))`（`mrt.terrainAtlasLod0`，默认开、关即回隐式行为并 WARN）⇒ 包地形链的地图采样钉在 mip0 |
| **代价（明写）** | 远地块失去 mip 过滤（_aliasing_ 与闪噪风险）；这是**止血**不是修根。GAP-011（闪烁）与它可能同根，未验证前不并案 |
| **回退/修根条件** | 查明坏 mip 事实（图集纹理 mip 链内容 or lavapipe 导数路径）⇒ 撤 workaround 或改为按根因修。回读图集 mip1 统计 = 现成判据（`TargetReadback` 已支持按 mip 拷贝） |
| **证据** | `evidence/h46-post-chain-integration.md`（E/F3/G 三臂逐字） |

### GAP-017 · 🔴 后处理目标（colortex 池）**没有真实 mip 链** ⇒ 包按 mip 采样时行为由驱动决定（h46 I/J 定位；2026-10-05 登记）

| 字段 | 内容 |
|---|---|
| **需求来源** | OF/Iris 语义：`const bool colortexNMipmapEnabled=true`。BSL v10.1.8 实测 **6 个后处理程序**声明它（deferred1×3、composite3/4/5/6/7）——bloom/TAA/DOF 都按 mip 层级采样 |
| **原版现状（字节码级核实）** | `GpuDevice#createTexture(label, usage, format, w, h, depthOrLayers, **mipLevels**)` 可建多级纹理，但 `TextureTarget` 内部只建单级；`CommandEncoder` **没有 `generateMips`**（逐类核过，只有 writeToTexture/copyTexture* 按单 mip 操作）⇒ 引擎侧没有现成的 mip 链生成能力 |
| **实测症状（h46 I/J 臂逐字）** | `colortex0=(45.4,36.0,19.2)` 正常而 `colortex1=(251.7,250.8,248.4)` 帧尾即白 = composite4 的 `BloomTile` 对无 mip 的 colortex0 做高 LOD 采样，驱动层饱和 ⇒ main 全白。黑前线（GAP-008）修住后浮出的同族新前线 |
| **止血尝试（h46 K 臂，已证伪）** | 链侧采样统一 `maxLod=0`（钳到高 LOD ⇒ mip0）。K 臂实测 **无效**：`colortex1` 仍 `(251.96, 251.07, 249.0)` —— 白不是驱动未定义行为，而是 **BloomTile 的 8 个 mip taps 全部落到同一张全分辨率图** ⇒ `Σ≈8×avg`，`pow(Σ/32, 0.25)` 把暗源必然抬到 ~110/255，再被 composite5/6/7 叠加 ⇒ 饱和。钳制保留（有界性优于未定义），但**白症状归 GAP-017 本体** |
| **⚠️ `h46` M 臂更正（L 臂判读已撤回）** | `ColortexPool`（每槽多级纹理 + 每级视图；原版 `RenderTarget` 逐类核实只有 mipLevels=1）+ `FrameApi` 降采样金字塔（复用 blit 管线逐级采上一级；脏集机制：写后标脏、读前重建，只按包声明的槽）。L 臂的「白退场」被 M 臂证伪（当时金字塔根本没跑：`levelCount` 差一 ⇒ createTexture 每帧抛、链停摆，数字来自陈旧画面）。修差一后 M3 臂：金字塔真跑（`mip pyramid generating: slot=0 levels=10`）而 **bloom 仍全白** ⇒ 白因收窄到 `BloomTile` 采样/表达式侧（texture2DLod 转译的 lod 处理是头号候选），不再是缺 mip 数据。本条保持开放；元纪律：**症状消失必须与机制自报互相印证** |
| **修根条件（回退判据）** | 实现显式 mip 生成（降采样 blit 链或 compute），且能按 `colortexNMipmapEnabled` 精确只对声明的槽生成 ⇒ 撤 maxLod=0 钳制。在此之前不许说「bloom 生效」 |
| **h48 机制数字（金字塔内容已被直接量到）** | 新增 `mrt.pixelProbeMipLevels`（`copyTextureToBuffer` 的第 5 参就是 mipLevel，源码核实）⇒ 直接读 `colortex0@m8`：实测 `(33,25,13)/255`，而同帧 mip0 全屏均值 `mean_luma=34.5` ⇒ **顶部确实是降采样平均值**（不是黑、也不等于 mip0）。GAP-017 的「修根」这一半成立；剩下的白/黑归 GAP-018 |
| **证据** | `evidence/h46-post-chain-integration.md` §I/§J；`evidence/h48-flicker-and-readback.md` |

### GAP-018 · 🔴 后处理链里同一 colortex 槽**同时是采样器和颜色附件** = Vulkan 未定义行为（h48 定位；2026-10-06 登记）

| 字段 | 内容 |
|---|---|
| **需求来源** | OF/Iris 语义允许一个程序「读自己上一步写进同一张图的内容」（BSL 的 bloom 反馈、TAA/曝光的时序缓冲都靠它）。GL 侧的实际行为 = 读到**本次 draw 之前**的内容 |
| **代码级事实（不是猜）** | `FrameApi#runPostPass` 的写槽视图与 `chainResolver` 的采样视图**同为** `MrtTerrainPass.poolView(slot)`。BSL 实测重叠：`composite`（读 c0 写 c0）、`composite1/2/3`（读 c0 写 c0）、`composite5/7`（读 c2 写 c2）、`composite6`（读 c1 写 c1）⇒ **11 步里 8 步在自己的读写集里重叠** |
| **症状（h48 逐字）** | 画面按帧**黑白交替**并逐步收敛全黑。判据用**游戏自己的 F2 截图**（`Util`→`Window` 帧缓冲截图，绕开我方回读）：探针全关时仍 `200.0 / 6.2 / 199.3 / 6.2 / 199.7 …`，周期 3 帧；`AUTO_EXPOSURE=false` 时仍 `199.5×3 / 6.2 / 199.6 / 6.2 / 199.9×3` ⇒ **不是时序选项、不是自动曝光除数**，是结构性的每帧状态翻转 |
| **为什么不能继续用现状** | 无 validation layer（X35）⇒ 不报错、不崩，只产出「看起来偶尔对」的画面；这正是本项目反复踩过的「静默错画」形状（h26/h33 同族） |
| **修法（本轮实现）** | **每槽双代轮转**：`ColortexPool` 每槽两张纹理（各带完整 mip 链），采样器绑「当前代」，写附件绑「另一代」，pass 结束后翻代 ⇒ 读到的就是本次 draw 之前的内容（与 GL 实际语义一致），且**结构上不可能**同子通道读写同图 |
| **代价（明写）** | colortex 池显存 ×2（854×480 RGBA8×10 级×6 槽×2 ≈ 9.5 MB，可忽略）；金字塔要按「被读的那一代」重建（脏集机制已支持） |
| **回退/收口条件** | 双代轮转后重跑 `h48_flicker_capture.sh`：截图亮度序列不再出现 6.2 级黑帧 ⇒ 本条关闭；若仍交替 ⇒ 本条证伪，回到「链输入太亮/缺 gbuffer 程序」那条线（GAP-015 + 非地形 gbuffers） |
| **h48 实测（部分成立）** | 修完（每槽双代 + `poolWriteView`/`advanceWrittenSlots`）后亮帧 **199.7 → 45.3**，且画面内容可辨认（雪面/树/阴影/手）⇒ 别名确实在破坏链，修根有效。但黑帧仍在（10 张里 2 张 ≈9.2）⇒ **本条不是闪屏的全部原因**，剩下的归 GAP-019 |
| **证据** | `evidence/h48-flicker-and-readback.md` §四 |

### GAP-019 · 🔴 帧尾 colortex 为 0 的**责任侧**：地形输出每帧都在，是链把它打没的（h48 定位；2026-10-06 登记）

| 字段 | 内容 |
|---|---|
| **症状（逐帧数字，非画面推断）** | 探针 every=1（T4 臂）：黑帧上 `colortex0` 自身 `mean_luma=0 / allZero=true`、`colortex0@m8=0`，亮帧 `colortex0 mean_luma=34.5`、`@m8=(67,53,32)` ⇒ **黑起源于链的输入**，不是链的着色 |
| **已排除** | ① 回读缓冲在途复用（T2 双槽 + T3 延迟一个节拍收割 ⇒ 交替比例不变，那些 0 是真黑帧）；② 自动曝光/TAA 等时序选项（`AUTO_EXPOSURE=false`、`AUTO_EXPOSURE_RADIUS=0.002` 两臂仍交替）；③ GAP-018 别名（修完仍剩 2/10 黑帧） |
| **第一个已确认的独立变量** | `mrt.terrainAfterLevel=true`（**帧图外重放**）时黑帧占 2/3；改 `false` 后 `colortex0 mean_luma=91.7 nonBlack=99.99%` 每帧都有 ⇒ 捕获得到的 `ChunkSectionsToRender` 是帧图**瞬态资源**，隔在帧图外重放会拿到被回收/清空的内容。取证车道长期开着这一档，是此前所有「输入时有时无」结论的来源 |
| **仍未解** | 即使 `terrainAfterLevel=false`，仍有约 1/5 帧地形内容为空。缺的是**逐帧自报**：本帧地形 pass 实际重放了几个 section / 有没有产生片元。不测这一条就只能继续在「顺序 vs 生命周期」之间猜 |
| **h48 收口尝试 → 🔴 撤回（判据臂其实没在测链）** | 上面那条「不同 draw 对象=120 个, 代次错配=0」仍然成立：**「捕获对象被回收复用」这条假设证伪**。但同臂另两条结论**作废**：`FullscreenPassHook` 的链 gate 里带了 `MrtTerrainPass.afterLevel()` ⇒ `mrt.terrainAfterLevel=false` 会**连带把整条链关掉**、退回旧三步链。于是「45 luma 稳定画面」「闪屏消失」两张判据都来自**旧三步链**，不是链。gate 已拆开（链是否跑只取决于链；afterLevel 只决定地形何时画），并加 `[route]` 自报把每帧走哪条路打在日志里。 |
| **h48 更正后的链内实测（route 自报 = chain=true afterLevel=false）** | 帧尾 `colortex0`（链里最后写它的是 composite3）逐轮：`28.53 → 5.64 → 0.00`，`colortex1`（链输出）`110.45 → 45.96 → 0.00`，`main` 跟着走 ⇒ ** intermittency 在链内部**，不是地形没画（此前我把「帧尾 colortex0=0」读成「地形没进池」也是错的：帧尾那一槽早被链覆写了，地形内容要看链**开跑前**的槽）。⇒ 本条重新开放，判据换成「链级二分」（`mrt.postChainMaxPasses` + `mrt.pixelProbeChainSlots`） |
| **修根条件（回退判据）** | 用链级二分定位到「从哪一级开始把内容打没」，修到：连续 6 个探针轮 `colortex1` 与 `main` 都稳定非零且亮度不塌。判据必须带 `[route] chain=true` 自报行，否则该臂无效 |
| **剩余未收口的画面问题（另案）** | 天空仍是黑的 = 非地形 gbuffers 程序（skybasic/skytextured/water/entities/clouds）未接 ⇒ 归 GAP-003/GAP-015 那条线，与闪屏不是同一件事 |
| **h48 入口验证（黑天空的修法，不需要 M-04）** | 源码级核实（26.3.0.51-beta）：① `SkyRenderer` 的构造器与 render **都是 public** —— `public SkyRenderer(TextureManager, AtlasManager, RenderTarget)`、`public void render(GpuBufferSlice skyFog, SkyRenderState state)`；② 它**自己建 pass**：`createRenderPass("Sky", renderTarget.getColorTextureView(), Optional.empty(), renderTarget.getDepthTextureView(), OptionalDouble.empty())`，颜色是 **LOAD** 语义 ⇒ 只要给它一个「颜色视图 = 我方 colortex0、深度视图 = 我方 gbuffer 深度」的 `RenderTarget`，天空就画进 gbuffer —— 正是 OF 里 `gbuffers_skybasic` 的落点；③ `RenderTarget(@Nullable String, @Nullable GpuFormat, @Nullable GpuFormat)` 这个公开构造器**不建纹理**，且 `colorTextureView`/`depthTextureView` 是 **protected** 字段 ⇒ 一个薄子类即可 ⇒ **零 mixin、不动原版 pass 所有权 ⇒ 不需要 M-04** |
| **该路径仍需实测的三点** | ✅ ① **已在源码级解决（2026-10-06，h48e）**：`OptionalDouble.empty()` = **不清深度**。证据 = `com/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder.java` 第 307-321 行：深度附件 `clearValue` **present** 才 `loadOp(1)`（`VK_ATTACHMENT_LOAD_OP_CLEAR`），empty 走 `loadOp(0)`（`LOAD`）；且 `storeOp(0)`（`STORE`）恒置 ⇒ 天空 pass 不会抹掉地形深度，但**会把它自己那批片元的深度值写进去**（是否写、写什么值取决于 `RenderPipelines.SKY` 的深度状态与 `core/sky.vsh` 的 `gl_Position`，未逐字核实）。⚠️ `skyFog` 的等价来源（原版从雾设置取；我方取 `RenderSystem.getShaderFog()`）与 ③ `SkyRenderer` 的 close/重建时机（原版在 `shouldResetSkyRenderer` 时 close ⇒ 我方实例必须跟着资源重载走，否则换世界后持旧纹理视图）仍未实测 |
| **h48 实测（`mrt.skyPass` 已实现，默认仍关）** | 机制侧**通了**：① 首臂逐字 `NullPointerException: Cannot invoke "Vector3fc.x()" because "v" is null` at `SkyRenderer.renderSkyDisc:165` ⇒ 原因是**借用了原版共享的 `skyRenderState`**（我们在 AfterLevel 才跑，那份状态没被填）；改法 = 自己持 `SkyRenderState` 并调公开的 `SkyRenderer.extractRenderState(level, partialTicks, camera, state)`；② 修后天空**确实写进了 colortex0**：`c0@afterTerrain = 71.0910` → `c0@afterSky = 64.1701`（同帧两个取点不同 ⇒ 天空 pass 有产出）。**但暗帧回来了**（S2 臂 round #12：`colortex0 = colortex1 = main = 0.0000`，而 #10/#11 正常）。🔴 **当时给它安的机制解释已撤回（h48e）**：原文写「深度附件清/覆盖了 gbuffer 深度 ⇒ 已确认有害」，但那是**从症状倒推**、深度本身一次都没测过；而源码级核实（见上一行）表明天空 pass 的深度是 **LOAD**，不会清。撤回后**暗帧成因未知**，并且 #12 那一轮的 `c0@afterTerrain` 读数**当时没记** ⇒ 无法区分「天空把内容打没了」与「地形本来就没画进去」。判据（h48e 臂：`skyPass=true` + `pixelProbeEvery=20` + `postChainTrace`）= 同一 round 的 `c0@afterTerrain` / `c0@afterSky` / 链级 trace / 帧尾 `main` **同帧四点对照**，塌陷发生在哪一段就把责任定在哪一段。在此之前 `mrt.skyPass` **保持默认关**。
| **h48e 四臂交叉（2026-10-06，数字全部来自探针取点，画面侧不参与）** | ① **天空确实写进了 colortex0**：`c0@afterTerrain` 在 `14.4170/39.0164/5.9607/25.8884` 之间轮转，而 `c0@afterSky` **恒 81.3139**、meanRGB=(73.3, 81.4, **104.6**)（蓝主导）⇒ 有产出；② **链塌成 0**：帧尾 `colortex0`/`colortex1`/`main` 全部 `0.0000 allZero=true` 每一轮都是，而**同一份代码**把 `skyPass` 关掉就是 `2.3169 / 34.5901 / 34.5630` ⇒ 「天空开 = 链输出为 0」，且**不是闪屏**（不是间歇，是每帧）；③ **不是深度**：`mrt.skyOwnDepth` 臂（天空挂私有空白深度，gbuffer 深度一个 bit 不碰）读数与挂 gbuffer 深度臂**完全一致** ⇒ 「给天空只读/独立深度」这个原计划方案**作废**；④ **不是链看不到天空**：新增链首取点 `c0@chainStart` = **81.3139**（与 `afterSky` 同值）⇒ 天空内容在链开跑前确实还在图上，那个 0 是**包的第一级 `deferred1` 自己算出来的**；⑤ 两臂 `ERROR` 各 0 条、「拷贝回调没回来」WARN 各 0 条 ⇒ 探针有资格说话（`collectReady` 只在 `copyReturned` 后才 `finish`）。 |
| **h48e 掉出来的更要紧的事实** | ②+③ 合起来只有一种读法：**私有空白深度下天空必然铺满全屏，而两臂逐位相同 ⇒ 挂 gbuffer 深度的那一臂也铺满了全屏 ⇒ 原版天空没有被地形遮挡**，「地形后补天空」等于把刚画好的地形整片盖掉。机制方向（未核实）：`DepthStencilState.DEFAULT = GREATER_THAN_OR_EQUAL, writeDepth=true`（源码逐字）+ 本引擎反向 Z，而原版自己的序列是**天空先画、地形后盖**（`LevelRenderer`），OF/Iris 的 gbuffer 顺序同样是 `skybasic → terrain` ⇒ 依赖「天空被地形深度裁开」从设计上就是错的 |
| **h48e/h48j 改的架构（两档都接，顺序靠声明、不靠插入序）** | 顺序 = **清待写代 → 天空铺 → 地形 LOAD 盖上去 → 链**。地形 pass 在 `skyPass && !toMain` 时把**槽 0 的颜色附件从 CLEAR 改成 LOAD**（其余附件与深度照旧清），天空用**自己的一张私有深度**（不碰 gbuffer 深度）。挂点两条：① 帧图档 = `onFrameGraphSetup` 插 `vkdisp_gbuffer_sky` 并 `terrainPass.requires(skyPass)` —— 🔴 **必须显式 requires**：h48g 实测「先插 sky 再插 terrain」不保证执行序（`resolvePassOrder` 只认资源依赖，插入序不是依赖）⇒ 天空被排到地形之后又被盖掉（`c0@afterSky` 只剩 0.0611）；`FramePass#requires(FramePass)` 已源码级核实是公开接口方法（见 `06-MIGRATION.md` V5）。② AfterLevel 档 = `FullscreenPassHook#paintGbufferAndTerrain` 先天空后地形。两档顺序由 `RenderRouteWiringTest#skyIsDispatchedBeforeTerrain` 做构建期守卫 |
| **h48i/j 修的结构 bug：天空的目标视图把代次缓存了（本轮真正的修法）** | `GbufferTarget` 原来只在 rebuild 那次设 `colorTextureView`，而 GAP-018 的双代轮转**每帧翻 `cur`** ⇒ 从第二帧起天空画进「第一次看到的那一代」，地形写另一代并翻代 ⇒ 链只读到地形、天空被丢在没人读的纹理里。修法 = 新增 `GbufferTarget#repoint(color, depth)`，`SkyIntoGbuffer.render()` **每帧**调用（`SkyRenderer` 每帧现取 `getColorTextureView()`，源码第 134 行 ⇒ 改视图就够，不必重建渲染器）。🔖 通用形式：**任何包住双代池的适配器都必须跟着代次走**，一次性设视图 = 静默写到过期纹理 |
| **h48g 换档对照（判据边界见 `08-TESTING.md` 2026-10-06 那条）** | 「`terrainAfterLevel=true` ⇒ 链帧尾恒 0」这条**与天空无关**（对照臂 `skyPass=false` 同样恒 0），它是 GAP-019 行第 351 条量过的重放失效形状。开天空只是给 colortex0 补回了内容（帧尾 `main` 0 → **107.9**），地形那部分仍未可靠落池 ⇒ **天空线的成绩是「gbuffer 里有天空了」，不是「天空+地形同框」**。同框还差 GAP-019 那条：帧图外重放拿到的 `ChunkSectionsToRender` 内容时有时无 |
| **h48k 更正（2026-10-07）：上面那批「链塌成 0」全部撤销 —— 是测量窗，不是渲染** | 过**预热闸门**（`pixelProbeEvery=10`，等到探针自己报的 round≥60 ⇒ ≥600 帧）后重测同一档位（帧图档）、只差 `skyPass`：`main` = **137.0773（开）/ 123.5968（关）**，`colortex0/1` 同步非零、六轮稳定 ⇒ **「天空开 ⇒ 链输出为 0」不成立**。15.2 / 15.4 / 16.1 / 17.1 里的「恒 0」全都取自第 180~300 帧，而探针自己写过「< 600 帧不产出对照结论」。🔖 通用形式：**判据窗口要用被测系统自己声明的「预热结束」信号来界定，不能用脚本里的一个定长 `sleep`**（`tools/vulkan-local/h48_flicker_capture.sh` 已加闸门）。同理 15.4 那条「天空铺满全屏」也**降回未证**（同一批数字）。 |
| **h48k 还开着的一条** | 开天空臂 `c0@chainStart=71.2899` ≈ 对照臂 73.2414，而 `c0@afterSky` 只有 **0.5270~0.9066** ⇒ 天空铺上去的是**近黑**、随后被地形盖掉 ⇒ **天空还没真正进画面**。两种可能未切开：① 该臂聊天注入没落地、世界是**夜**（两臂都没有截图 ⇒ 画面侧无判据）；② 写代/时序仍不对。 |
| **判据（下一轮，必须满足才算这条收口）** | ① 白天注入**落地**（自检行打「白天注入生效」+ F2 截图存在），`c0@afterSky` 显著高于 0.5 且与 `c0@afterTerrain` 的差可读；② `every=1` 连续 ≥200 帧两臂对照，开天空臂不再有 `colortex0=colortex1=main` 同时为 0 的轮次（h48k 观测到 5 轮里 1 轮，对照臂 6 轮无 —— 样本不足，**暂不定责**）；③ 判据必须带 `[route] chain=true` + 天空自报行 + 已过预热窗的 round 号，缺一项该臂无效 |
| **证据** | `evidence/h48-flicker-and-readback.md` §二/§五/§六/§七/**§十五（h48e 四臂：15.1 深度语义源码级、15.2 同码 A/B、15.3 探针可信性、15.4 深度否证 + 全屏覆盖）** |

### GAP-020 · 🔴 链内隐式导数选到坏 mip ⇒ **整帧黑**（与 GAP-016 同族；h48 Y1 臂定位；2026-10-06 登记）

| 字段 | 内容 |
|---|---|
| **症状** | 视野里世界**整帧变黑**再变正常（用户 2026-10-06 描述：「黑一下正常一下，但物品栏和手一直正常」——手/HUD 由原版在链之后画，所以不受链影响，这条描述本身就是判据） |
| **两个观测点钉住责任侧** | 新增 `mrt.pixelProbeAfterTerrain`（标签 `c0@afterTerrain`，链跑之前就取）：T8 臂 `40.8 / 95.5 / 113.2` **每帧都有内容**，而同轮帧尾 `colortex0 = 0.0000`、`main = 0.0000` ⇒ **地形没问题，是链把内容打没了**（此前 GAP-019 的「地形没进池」表述作废） |
| **单变量判据（Y1 臂）** | `mrt.chainSamplerLod0=true`（链采样器钉 `maxLod=0`）⇒ `main = 163.9 / 78.2 / 135.6 / 186.2`，**没有一帧是 0**；对照 T8/X1（完整 mip 范围）`main = 0 / 122.5 / 0 / 122.5 / 122.5 / 0` ⇒ 黑帧由**mip 选择**决定 |
| **为什么不能拿「钉 mip0」当修根** | BSL 的 `BloomTile` 是**故意**用导数取级的：`coord = (coord - offset) * exp2(lod)` ⇒ 坐标梯度 ×2^lod ⇒ 隐式 LOD 自动变成 `lod`。把链采样器钉死 mip0 会连带把这套 tap 全压到 0 级 ⇒ 就是 GAP-017 K 臂量到的「八 tap 同图 ⇒ 必然过曝」。所以钉 mip0 只是**判据档**，产品档必须让导数本身正确 |
| **待查的两条候选机制** | ① 我方池纹理的**高 LOD 未初始化**（blit 金字塔只在被声明的槽上跑；未跑的那些级在 Vulkan 里是未定义内容，采到就是黑）；② 全屏三角形的 `texCoord` 梯度在本后端被放大（导数 × 视口比例算错 ⇒ LOD 落到 8~9）。二者在画面上的形状相同，需要「链级 + 槽级」双探针才能分开 |
| **回退/修根条件** | 关掉 `mrt.chainSamplerLod0` 仍**连续 6 轮**无 0 值帧，且 bloom 的高 LOD tap 仍按级采样（`colortex0@m8` 与 mip0 均值不同）⇒ 本条关闭 |
| **🟢 h48 收口（Z6 臂 = 修根，判据达成）** | 真因不是「导数选错级」本身，而是**金字塔与 mip0 不同源**：GAP-018 的双代轮转下，惰性重建（「下一个读者之前才建」）永远滞后一次写 ⇒ 读者采到**上一代**的金字塔。修法 = `FrameApi` 改成**每级 pass 写完立刻重建**（`regeneratePyramidsForWritten`），并删掉 `refreshMipPyramids`/脏集。判据实测（`chainSamplerLod0` 保持默认 **false** = 完整 mip 范围）：逐帧探针 190 帧里只有开局 3 帧（未进世界）为 0，其余 **185 帧恒 93.1338**；`[route] chain=true afterLevel=false` 自证在场。独立通道（游戏 F2 截图，探针全关）10 张 = `59.6 / 59.3 / 58.9 / 58.4 / 58.2 / 58.2 / 58.1`（另有 3 张 23~27 是注入按键误开**成就界面**，不是渲染态）⇒ **黑白闪屏消失**。 |
| **元纪律（本轮新增）** | 亮度表单独看会把「GUI 打开」读成「画面变暗」——判据必须**看图**，不能只看数字（本项目第 N 次踩「判读对象没自报自己是什么」）。 |
| **🔴 h48o 重新打开（2026-10-08）：收口判据的测量窗有问题，且黑帧以**严格 3 帧周期**回来** | 逐帧回读（`pixelProbeEvery=1`，过预热窗后 160 个**连续**样本）序列 = `0 N N 0 N N 0 N N …` ⇒ **每第 3 帧整帧为空、每次只空 1 帧**，间隔恒 3（53/53 次），连续零长度恒 1；非黑帧数值逐位相同（`main=115.5682`、`c0=17.0616`、`c1=115.5657`）。双代同取（h48n）证明**不是取点打到空代**（黑帧轮两代同时为 0 —— 槽 0 一帧内被 6 个 pass 写 ⇒ 翻代 6 次 ⇒ 两代都被本帧写满，故单帧空即两代同空）。⚠️ 同时撤回 Z6 那条收口读数的强度：Z6 的「185/190 帧恒 93.1338」并**没有**声明它覆盖了几个 3 帧周期，而 `every=20` 的采样相位恰好可能整段错过黑帧（`0NN` 模式下每 3 帧一个 0，采 20 帧一跳只会命中约 1/3 的轮次 —— h48n 实测就是「3 轮一个 0」）。⇒ **Z6 的判据不足以关闭本条**，本条重开。 |
| **重开后的三条候选（必须先切；h48p 已把第三条做成可调 A/B）** | ① <b>mip/金字塔与代次错配</b>回来了（GAP-020 原机制）。切法：`every=1` 下只差 `mrt.chainSamplerLod0` 跑两臂。 ② <b>深度为 2~3 的环形资源被按相位用错</b>：`CrossFrameResourcePool(3)`（`GameRenderer:127`）把一张物理纹理在 3 帧内重新发给别的 pass，而带 `ZERO_CLEAR_COLOR` 的 descriptor （`RenderTargetDescriptor.java:24-33`）会先清它 ⇒ 最贴合「周期 3、只空 1 帧、自愈、不报错」。 ③ 🔴 <b>回读仪器自己造零</b>（h48p 新证，优先级最高）：`copyTextureToBuffer` 的回调走 `queueForDestroy`（CPU 侧销毁队列，`VulkanCommandEncoder:60` 深度 2 + `:229` 每 submit 轮一次），而 GPU 完成最早要到 `+2` 次 submit 才被等到（`:219-223` `awaitSubmitCompletion(currentSubmitIndex - 2)`）⇒ 余量 = 1 拍时，映射到的缓冲**可能还没被 GPU 写过**，其初始内容就是零。周期与「2~3」同量级，形状与真实黑帧逐字同形。 **A/B 已做成开关**：`mrt.pixelProbeReadDelay`（默认 3）—— 调 1 复现「每第 3 帧为 0」即证明是仪器；调 3/4 黑帧消失即把它从渲染缺陷清单划掉。这一条不必再猜，一臂就能判。
| **🟢 h48q：候选 ③（仪器）判掉了「多余的那一个零」，但**没**判掉周期 3 本身** | 同一条链只差 `mrt.pixelProbeReadDelay`（过预热窗后各取末尾 240 个逐帧样本）：`delay=1` ⇒ 空帧 **160/240=66.7%**、间隔分布 `{1:80, 2:79}`（形态 `00N`）；`delay=3` ⇒ 空帧 **80/240=33.3%**、间隔分布 **`{3:79}`**（形态 `N0N`）。⇒ **余量不足确实每个周期多造一个零**（§十八机制被定量证实），但**加够余量仍剩一个严格周期 3 的空帧** ⇒ 候选 ③ **不是**本症状的成因，只是叠加层。⚠️ 两臂观测面其实不同（自报：d1=当地时 21206 **夜 + rain=1.000**，d3=2352 **拂晓 + rain=0**）⇒ **luma 不许跨臂比**（54.7 vs 82.9 是昼夜差，不是「部分完成」）；反过来也正说明**周期 3 与画面内容无关**。证据 `evidence/h48-flicker-and-readback.md` §二十一 |
| **🟢 h48r：换了被测对象 ⇒ 空帧**归零**（本条因此收窄，不关闭）** | 清掉 §二十二那条 store 残留、并把 `pack.capabilityGate` 打开后重跑（其余配置与 d3 臂**逐字相同**，`readDelay=3`）：进程内自报 `选项覆盖已改写进源: 命中 8/8 [PARALLAX=false, REFLECTION_RAIN=false, REFLECTION_SPECULAR=false, REFLECTION_ROUGH=false, SELF_SHADOW=false, SSS=false, NORMAL_DAMPENING=false, NORMAL_PLANTS=false]`（**列表里没有 `ADVANCED_MATERIALS` ⇒ 它本来就是包默认 false**）+ 契约 `colorTargets=1 declaredOutputSlots=[0] samplers=5 varyings=9 unwrittenAttachments=[]`（与 `TerrainProductionOutputCountTest` 的生产值**逐项相同**）⇒ **240 个逐帧样本 0 个空帧**。⇒ 结论：**周期 3 的空帧是「8 附件 / 多槽 MRT」那一档的性质**（写 `[0,3,6,7]`、`1/2/4/5` 无输出、双代 ping-pong 的槽数 1→8），**不是链在有内容时把内容打没**（那一档 luma 中位 82.88，画面是有的）。🔴 **本条不关闭**：`ADVANCED_MATERIALS` 是包内合法开关、且我方 `pack.capabilityGate` 的**代码默认是 `false`**（`VkDispConfig.java:356`）⇒ 用户**能**进到这档。剩下的机制候选收窄为 GAP-018（同槽既附件又采样器）在多槽下的兑现，与「未写槽的清屏/LOAD 语义 × 双代轮转」的组合。证据 `evidence/h48-flicker-and-readback.md` **§二十三** |
| **🟢 h48s：把「门控」这个变量也切掉 ⇒ 唯一自变量是包配置** | `pack.capabilityGate` 从 `true` 改回 **`false`**（= 代码默认值，也正是**用户客户端那一档**），其余与 h48r 逐字相同：契约自报 `colorTargets=1 declaredOutputSlots=[0] samplers=5 varyings=9 unwrittenAttachments=[]`，**空帧 0/240**，非空帧 luma `min 62.74 / med 143.21 / max 150.16`。⇒ ① **门控开与关都是 0 空帧**，本症状的唯一自变量是「哪一档包配置」，**与门控默认值无关** ⇒ 本轮**不改** `pack.capabilityGate` 的默认值（无依据不动产品行为）；② 🔖 **GAP-009 的「albedo ≡ 0」绑的是 `ADVANCED_MATERIALS` 这一项，不是门控闭包整体** —— 门控关着（`PARALLAX`/`SSS`/`REFLECTION_*` 按包默认为真）地形照样有画面且更亮（143.21 > 残留档 82.88）；③ 用户客户端走 `run/config` 车道、其 store+toml 与被清掉的残留逐字相同 ⇒ **黑白闪屏的头号解释就是这条残留**（但仍是同一台仪器给的结论 ⇒ 须由人眼/F2 再判一次才算收口，本轮 F2 第三次没落地）。证据 `evidence/h48-flicker-and-readback.md` **§二十四** |
| **🟢 h48t：F2 独立通道与回读第一次给出同一个答案** | 同一档位（真默认）纯加载臂 10 张 F2 **全部落地**（已入库 `evidence/h48-images/h48t-default-1..10.png`），`mean_luma` = `97.33 / 96.22 / 99.74 / 108.85 / 98.47 / 98.55 / 98.59 / 98.63 / 80.87 / 80.62` ⇒ **10/10 有画面**（§20.2 那批黑帧是 `≈ 4.5`）。⇒ 「真默认档不闪屏」从单通道升级为**双通道一致**。🔖 同时纠正「F2 连续第三轮没落地」：那句只对 h48q 的 d1 臂成立；**同一套脚本一次全中一次全不中的原因本轮未查** ⇒ 算未定的工具层事实，下次全不时**不许**把「没图」读成「没画面」。画面判读（按图不按数字）：🟢 天空渐变蓝 + 太阳本体 + 树叶镂空轮廓 + HUD 正常、无绿天空、无白屏；🔴 **云完全没有**（`gbuffers_clouds/water/entities` 未接 = GAP-015）、树叶暗部无层次（方向与 `shadowtex0/1` 仍是本 pass 深度占位一致，但**无判据**区分「该暗」与「缺阴影层」）、**地面水平视角仍未看过**（本臂抬了头）、太阳是硬边方块（`sun.png`/customImages 是否生效未核实）。证据 `evidence/h48-flicker-and-readback.md` **§二十五** |
| **本条关闭条件（重写，比 Z6 严）** | 在**过预热窗**、`every=1`、连续 ≥180 帧（≥60 个 3 帧周期）的逐帧回读里 **0 个空帧**，且 F2 独立通道截图亮度与之一致。🔖 **并追加一条前提**（h48r 教训）：关闭证据必须**先自报被测的是哪一档**（`命中 N/N` 那行 + `declaredOutputSlots`），否则「0 个空帧」可能只是换了一档。🔖 **再加一条**（h48t）：**「档位」必须显式声明**——本条在默认档已满足，但 8 附件档仍不满足 ⇒ 本条**只收窄不关闭** |
| **证据** | `evidence/h48-flicker-and-readback.md` §十～§十二、**§十七（h48m/h48n/h48o 逐帧与双代对照）**、**§二十一（h48q readDelay A/B）**、**§二十二/§二十三（store 残留 ⇒ h48r 空帧归零）** |

---

### GAP-021 · 🔴 包的「作者写死 uniform 表达式」这条能力整块缺失（`uniform.*` / `variable.*` 无人求值；h48p 审计发现；2026-10-08 登记）

| 字段 | 内容 |
|---|---|
| **OF/Iris 能力** | `shaders.properties` 里 `uniform.float.<名>=<表达式>` 与 `variable.float.<名>=<表达式>`：包**自己定义**的逐帧标量，可互相引用、可引用原版内建（`sunAngle`、`cameraPosition`、`biome`…），支持函数 `if() frac() clamp() abs() max() min() smooth() in() sin() floor()` 与比较/四则 |
| **我方现状（核实到行）** | ✅ `pack/properties/ShaderProperties.java` 第 85-104 行的分派里，`screen./profile./program.*.enabled` 之外的**所有** key 落进第 103-104 行的通用 `directives` map ⇒ `uniform.` / `variable.` **没有任何解析与求值者**；`ShaderPackService` 里 `uniform.` 零出现（grep 核实） |
| **BSL 实测依赖面** | `run/h27/shaderpacks/BSL_v10.1.8.zip` 的 `shaders/shaders.properties`：**28 行 `uniform.*` + 13 行 `variable.*`**。其中直接决定画面的：`uniform.float.shadowFade=clamp(1−(abs(abs(sunAngle−0.5)−0.25)−0.23)×100,0,1)`（`lightShafts.glsl:162` ⇒ **光柱整条 ×0**）；`uniform.float.timeBrightness=max(sin(timeAngle×6.28318),0)`（`fog.glsl:33` ⇒ 雾/日照色调）；`uniform.float.timeAngle=…`（**包自己覆盖 timeAngle**，我方现在给的是朴素 `t%24000/24000`）；`uniform.float.blindFactor=blindFactorSqrt²`（`blindness` 链）；`isCold/isDesert/…`（生物群集旗帜，依赖 `in(biome, …)`） |
| **与既有 GAP 的关系** | 不是 GAP-007 的「同一个内建没填」：GAP-007 是**我方供**原值；这条是**包自己供**派生值 ⇒ 新能力项。`shadowFade/timeBrightness` 在 GAP-007 清单里恒 0 的**真正原因**就是本条 |
| **要做什么（顺序）** | ① 词法/语法求值器（纯 Java、可单测）；② 依赖图拓扑求值（`variable` → `uniform`，允许后定义引用先定义）；③ 求值输入的**核实来源**清单：`sunAngle`、`blindness`、`biome`/`BIOME_*`、`cameraPosition`（已有）；④ 结果并入 `OfUniformManager.gather()` 的 values（**包的覆盖优先于我方同名内建**，OF 语义如此，需在 04-SPEC 记一句）；⑤ 未识别的函数/标识符 ⇒ **不猜值**：整条求值跳过 + 一次性 WARN 列名（X9/X11） |
| **🔴 h48x 补充：本条还牵着「整包的编译分支」—— `MC_VERSION` 从未被我方定义（2026-10-08 修法落地，运行侧未验收）** | 取证：BSL 包里 **53 处 / 32 个文件**在用 `#if MC_VERSION >= 1xxxxx` 做版本门限；我方 `glsl/preprocess/DefineProcessor.process()` 的宏集合**只有包自己 `#define` 出来的那些**，引擎侧一个都不塞 ⇒ `ExprEval` 按「未定义标识符 = 0」求值 ⇒ **整包被当成跑在 MC 1.7 之前编译**（`#if MC_VERSION >= 11800` 恒走老数字那一支）。编码由包内门限值反推自洽并另有出处：`26*10000 + 3*100 = 260300`，`version.json` 逐字 `"id":"26.3"`（取自 `build/moddev/artifacts/minecraft-patched-26.3.0.51-beta*.jar`）。🔖 取不到值时**什么都不塞** —— 保持「未定义」，不许喂一个猜的数（X9） |
| **✅ 本轮落地的修法（离线可证的部分）** | ① 新增 `dev/vkdisp/McVersion.java`：`encode(String)→OptionalInt` 纯函数 + `current()` 反射取原版版本号（🔴 **本类不打日志**：单测 classpath 没有 FML/`SharedConstants`，一处日志会把「取不到」升级成 `NoClassDefFoundError` 新故障）；② `DefineProcessor.engineMacros()` 把 `MC_VERSION` 塞进引擎宏集合（取不到就整体缺席）；③ `pack/properties/ConditionalPreprocessor.java` 的标识符分支先查 `McVersion.numericOf(tok)` 再落 1/0（此前 `shaders.properties` 里的比较式一律按 0/1 判）；④ 自报：`OfUniformManager.reportConventions()` 在 `gather()` 里**不受 `debugLog` 门**打 `[MC_VERSION] = 260300` —— 与 GAP-022 的成对自报同一族：「日志里没有这行」不许被读成「开关是关的」（`evidence/h48 §二十二`）；⑤ 单测 `McVersionTest` + `ConditionalPreprocessorMcVersionTest` |
| **🔴 本条尚未验收的那一半（不许当已闭）** | **运行侧一次都没看过**：`[MC_VERSION] = 260300` 要在真客户端日志里出现才算「引擎真的定义上了」；53 处门限的实际影响要按**分支翻转面**判（此前 53 处全部恒假 ⇒ 转译终稿应当逐字节变化，变化文件数/行数就是判据，不是「看起来更好」）。下一次运行臂就是本项的验收闸，不过就把住。证据 `evidence/h48x-mcversion-branch.md` |
| **判据** | 单测：BSL 那 28+13 行**全部**求出不为 0 的结果（正午 `sunAngle` 下 `shadowFade=1`、`timeBrightness>0`）；运行期：`[uniforms]` 自报行里出现 `shadowFade=`/`timeBrightness=` 的非 0 值；画面：光柱与雾色进画面（F2 对照） |

### GAP-022 · 🔴 包按 OpenGL 深度约定写分支，我方是反向 Z —— 全链系统性走错分支（h48p 审计；2026-10-08 登记）

| 字段 | 内容 |
|---|---|
| **引擎事实** | 我方 gbuffer 深度：`0.0 = 远平面`、`1.0 = 近平面`、比较 `GREATER`（`MrtTerrainPass` 里那段为 0.0/1.0 绕了 6 趟客户端的注释；`docs/07-CONSTRAINTS.md` X34） |
| **包事实（逐行核实）** | BSL 全部按「1.0 = 天空」写：`deferred1.glsl:337 isSky = z == 1.0`、`:358 if (z < 1.0)`；`deferred.glsl:73 if (z<1.0)`；`ambientOcclusion.glsl:55 z>=1.0 return 1.0`、`:59 hand = z<0.56`；`composite.glsl:366 hand = z0<0.56` → `lightShafts.glsl:39 falloff *= 1−hand`；`taa.glsl:55 pos.z>0.56`；`composite5.glsl:347 depthtex0 >= 1.0`（镜斑）；`clouds.glsl:141/424` |
| **推出的系统性错误** | 天空永远**不被识别为天空**（`z==1.0` 在反向 Z 里是「贴脸」）；天空像素反而进几何分支（SSR/AO/雾拿垃圾 `viewPos`）；中远景被当「手」⇒ **光柱被 `1−hand` 抹掉**；镜斑永不出现在正确像素；体积云当作被遮挡而淡出；TAA 对近处几何做运动补偿（拖影） |
| **✅ 深度约定已核实到行（h48p，本条从「未证」升级为「已证」）** | `net/minecraft/client/renderer/Projection.java#getMatrix`：源码逐字 `float near = this.zFar; float far = this.zNear;` ⇒ **原版把 near/far 互换后**调 `setPerspective(..., zZeroToOne)`；而 `com/mojang/renderpearl/backend/vulkan/VulkanDevice.java:91-95` 给 `DeviceInfo` 第 4 个分量（`isZZeroToOne`，见 `DeviceInfo.java:12`）传 **true** ⇒ 窗口深度落在 [0,1]。两条合起来 ⇒ **近平面 = 1.0、远平面 = 0.0**，X34 由经验规律升级为源码事实（也解释了为什么地形 pass 必须清 0.0：比较是 GREATER）|  
| **由上面推出的正确换算（可证，不用猜）** | 对同一透视参数 (n, f)：GL 口径 `z_gl(d) = (f/(f−n))(1 − n/d)`，我方 `z_en(d) = (n/(f−n))(f/d − 1)` ⇒ **`z_gl = 1 − z_en` 恒等**（代数验证：`1 − z_gl = (fn/d − n)/(f−n) = z_en`）。🔴 但**只翻深度不够**：包里 `GetLinearDepth` 用 `depth×2−1` 反解 NDC，再乘 `gbufferProjectionInverse` —— 我方现在给的是**反向矩阵本身**（`GameRenderer:646/669` 取的 `cameraState.projectionMatrix`，`Camera:137` 由 `Projection.getMatrix` 填）。⇒ 必须**成对**给：深度 `1−z_en`，且投影矩阵同做`P_gl = M · P_en`，其中 `M` 为第三行 `= row3(P_en) 取负 + row4(P_en)` 的翻转矩阵（等价 `z←1−z` 且保持 w），`gbufferProjectionInverse` 用 `P_gl` 的逆。给错一半比给错全部更糟（会「看起来有阴影但位置全歪」）|  
  ✅ **已数值验证**（h48p，独立于 Java：按 joml `setPerspective(..., zZeroToOne=true)` 的 A=−b/(b−a)、B=−ab/(b−a) 直接算）：取 zNear=0.05 / zFar=512，在 d∈{near,1,16,128,far} 上 `1 − z_engine` 与标准 GL 口径 `z_gl` **逐点相等**（差 < 1e−12），且 `d=near ⇒ z_gl=0.0`、`d=far ⇒ z_gl=1.0`；`M` 作用两次回到原值（对合）⇒ `P_gl⁻¹ = P_en⁻¹ · M`，不需要额外求逆。
| **实现取向（🔴 h48w 改写：矩阵那一半**已经推出来了**，上一轮的「否证」本身是错的）** | ✅ 深度：`GlDepthConvention.glWindowDepth(z) = 1 − z` 已在主干 + 5 条单测。🔴 **本轮撤回**上一轮那句「`P_gl = M·P_engine` 被自己的单测否证、差 1.0、因为 joml 的 `m32` 是 ±0.05 而不是 ±1」—— 那是**两个读数错误叠成的假否证**：① 比较基准用了 `zFar=512`，而由游戏真实矩阵反解出的**真 far = 1024.001**（near=0.05；那个 `0.05000244` 恰恰就是 near，本来就该在那儿）；② 从日志读矩阵时把「第 N 列」当成了「第 N 行」（用同一条日志里的 `windowDepth@…` 三个读数才能判出来）。✅ **正确的翻法是左乘 `D2`（第 2 行 = (0,0,−2,1)，即 `row_z ← row_w − 2·row_z`）**，四项数值判据全过（`D2·Q` 与标准 GL **`[-1,1]`** 投影逐元素差 **3.0e−9**；`D2⁻¹` 第 2 行 = (0,0,−½,½)；`(D2·Q)⁻¹ = Q⁻¹·D2⁻¹` 差 5.4e−8 ⇒ **不需要重新求逆**；包自己 `depth*2−1 → P⁻¹` 的往返逐位回到 −1/−16/−128/−1024）。🔖 **上一轮那个 `D`（(0,0,−1,1)）也没错，只是口径不同**：它产出的是 **`[0,1]`（zZeroToOne）** 投影（与标准 GL [0,1] 差 5.0e−11、深度翻成 `1−z_en` 差 1.1e−16、x/y NDC 完全不变），而包要 `[-1,1]` 那份 —— 喂错那份的症状被数值抓到：**反解距离恰好是真值的一半**（d=1→−0.5002、16→−8.063、128→−68.27）。⇒ 待做的那一半是**接线 + 画面判据**，不是推导。证据 `evidence/h48w-gap022-real-matrices.md` |
| **🔴 仍成立的旧结论（别跟着上面一起撤）** | 「**只翻深度不翻矩阵 = 比不翻更坏**」这条**没有被否证**，它讲的是半翻状态的危害，与上面那条错推断无关。现状正是半翻：`mrt.depthGlProxy`（默认 **false**）只把 `depthtex` 写成 `1 − z`，投影矩阵一个都没动 ⇒ **不许**在接线时漏掉矩阵那一半，也**不许**先开 `depthGlProxy` 再补矩阵 |
| **判据（先做核实那一半）** | 第一步只做取证不做修法：把 `depthtex0` 在**已知像素**（远天空 / 近地形 / 手）上的实际读数打出来（注意：回读深度这条通道本机不可信，见 `evidence/h48 §十四` ⇒ 必须走「包侧行为差分」或 `GetLinearDepth` 的输出反推，不是直接读 D32）。✅ 这一半已做：`[GAP-022/matrix]` 自报行 + `evidence/h48w-gap022-real-matrices.md` |
| **🔴 h48z 接线时发现：矩阵那一半**不能**按 h48w §3.1 那样直接换（这条更正我自己的结论）** | 接线本身做完了（`DepthConventionPair` 成对出口 + 13 条离线单测钉住双翻；开关仍默认 **false**），但一接上就暴露出 h48w 漏掉的一环：**`gbufferProjection` 不只被片元用，它同时是顶点阶段的投影矩阵** —— 转译终稿逐字 `gl_Position = gbufferProjection * gbufferModelView * position;`（`build/bench-golden/BSL_v10.1.8/world0_gbuffers_terrain.vsh.trans.glsl:450`，本轮核实）。而本前端的设备深度值域是 **[0,1]**（`DeviceInfo.isZZeroToOne=true`），**没有 GL 那一步 `(ndc+1)/2` 视口映射** ⇒ 喂 `D2·P` 会让顶点产出 `[-1,1]` 的 clip.z、光栅化进 gbuffer 的深度越界、深度测试连带失真。<br>🔖 **h48w §3.1 那句「window = (ndc+1)/2」是 GL 视口的事实，不是这条 Vulkan 路径的事实** —— 我在推导时把两者当成一件事。数学部分（`D2·Q` 与标准 GL `[-1,1]` 投影逐元素差 3.0e−9、逆配对、包口径往返）**仍然全对**，错的是「所以可以换这一个 uniform」这一步。<br>⇒ 正确的修法不是换值，而是**按程序族分别供值**：`gbuffers_*`（有顶点阶段、要写 `gl_Position`）留**引擎口径**；`composite*`/`deferred*` 那批全屏步（顶点是我方 passthrough，不用这个 uniform）给 **GL 口径**。已核实这些片元确实读 `gbufferProjection`（`composite.glsl`/`composite3`/`composite5`/`deferred.glsl`/`deferred1.glsl` 五个文件命中）⇒ 两条需求同时存在、且互相冲突，**这就是本条剩下的真正工作量**。另核实：包里**没有** `gbufferPreviousProjectionInverse` 的声明（子代理曾按我的简报假设它存在，实测不存在 ⇒ 未新增，避免改动 OFF 态字节）。 |
| **本条关闭条件** | 深度与矩阵**按族分别**供值后，画面侧判据成立：`isSky = z == 1.0` 认对天空、光柱/镜斑/体积云出现在正确像素、SSR 不再拿垃圾 `viewPos`；且 `mrt.depthGlProxy=false` 时**逐字节**与今天一致（OFF 态不变是回归基线）。🔖 在此之前，**开关开着只算取证档，不算正确**。 |

### GAP-023 · 🔴 `depthtex0/1/2` 三个名字绑到**同一张**深度视图（h48p 审计，grep 核实）

`FrameApi` 第 1263-1266 行：`if (name.startsWith("depthtex"))` 一律回 `MrtTerrainPass.depthView()` ⇒ 包里所有「比较两个深度层」的逻辑恒等失效。已知直接受害者：`composite.glsl:333 z1 > z0`（半透明/水体识别）恒假。修法 = 按 OF 语义给 depthtex1/2 提供**各自**的缓冲（gbuffer 绘制顺序里 0=不透明后、1=半透明后、2=常驻顶层后 —— 我方目前只有一张），登记为独立缺陷而非顺手改。

🔴 **h48x 新增：这条不再是「以后再说」—— 它是 GAP-027 接水的**前置**。**
实测 `gbuffers_water` 的自由 sampler 清单里就有 **`depthtex1`**（真 BSL 默认档逐字：
`[texture_0, gaux2, depthtex1, noisetex, gaux1, shadowtex0, shadowtex1, shadowcolor0]`）
⇒ 水自己就要读那一层；现在接水只会让它的 `z1 > z0` 恒假（不崩，但错）。

🔧 **可行的机制（本轮把形状定下来，未实现）**：我方现在只有**一张** gbuffer 深度
（`MrtTerrainPass.depthView()`，D32_FLOAT；另有一张天空私有深度，与这三层无关）。
OF 那三个名字的语义是**同一张深度在三个时刻的快照** ⇒ 不需要三套渲染，只需要两次拷贝：
① `renderGroup(OPAQUE)` 之后把深度 blit 到 `depthCopy0` ⇒ 它就是 `depthtex0`；
② `renderGroup(TRANSLUCENT)` 之后 blit 到 `depthCopy1` ⇒ `depthtex1`；
③ 手/天气/实体全画完之后再 blit 一次 ⇒ `depthtex2`（在那之前 `depthtex2` 与 `depthtex1` 同源，
   这是**如实的**「还没有第三个时刻」，必须自报，不许静默同源 —— 就是现在这行的毛病）。
⇒ 三条硬约束：a) 拷贝要落在**同一代**上，别和 GAP-018 的双代轮转打架；
b) `depthTex` 是**采样器**，被拷的那张必须 `TRANSFER_SRC`、目标 `TRANSFER_DST` + `DEPTH_READ_STENCIL`
   （本机**没有 validation layer**，X35：用法错是静默的）；
c) 三条名字各自绑**各自的 view**，`startsWith("depthtex")` 那个一把抓的分支必须改成按后缀分派。

### GAP-024 · 🟡 `program.*.enabled` 解析了但**没有用来门控链**（h48p 审计；2026-10-08 补全核实）

| 字段 | 内容 |
|---|---|
| **现状** | `ShaderProperties.java:100-102` 把 `program.<名>.enabled` 收进 `programSwitches`，`ShaderPackService.deriveSettings`（第 519-522 行）把它落到 `Program#settings()` 的 `enabled` 键 —— 但 `pack/PackPostChain.java` 的装配循环（第 106-190 行）**从不读它** ⇒ 关闭的特性级照跑 |
| **BSL 实际开关表（逐行取自包）** | `deferred=AO`、`composite1=LIGHT_SHAFT`、`composite2=MOTION_BLUR`、`composite3=DOF`、`composite6=FXAA && !RETRO_FILTER`、`composite7=TAA && !RETRO_FILTER`、`shadow=SHADOW`（world-1 还多 `&& MULTICOLORED_BLOCKLIGHT`）、`shadowcomp=MULTICOLORED_BLOCKLIGHT`；默认态（`shaders/lib/settings.glsl` 逐字）：`SHADOW`/`AO`/`LIGHT_SHAFT`/`FXAA`/`TAA` **开**，`DOF`/`MOTION_BLUR`/`AUTO_EXPOSURE`/`ADVANCED_MATERIALS`/`RETRO_FILTER` **关**（写成 `//#define`） |
| **门控表达式能不能求值？✅ 能，来源已核实** | 包里**没有** `option.`/`type.`/`const boolean`（grep 核实：`option.` 0 行、`const boolean` 0 行）—— 特性开关是 `#define NAME` / `//#define NAME` 配 `// [候选值]` 列表；而我方 `glsl/preprocess/ConstEvaluator.java` 明确识别这两种形态（其第 36-43 行：带 `[候选值]` 的 `#define` 才算选项；第 100-101 行：放行 `//#define` 裸前缀 ⇒ 关着的宏也进模型）。⇒ `PackOptions.value("MOTION_BLUR")` 这条路是通的，**不需要新造机制** |
| **为什么不只是「白烧两级」**（这条决定了它值不值得做在黑帧之前） | 我方 GAP-018 是**双代轮转**：每个 pass 写过的槽都会 `advanceWritten` 翻代，而 `FrameApi` 每级写完还立刻重建金字塔。被禁用的 `composite2/composite3` 是「读 colortex0 再写回 colortex0」的透传 ⇒ 它们**照样翻代、照样触发金字塔重建** ⇒ 增加代次奇偶抖动。GAP-020 重开里「① mip/金字塔与代次错配」这条候选，正好可以被本条的门控**当作一次 A/B 来测**（门控后 3 帧周期空帧若变化，就指向这条） |
| **修法** | 装配期按 `PackOptions` 求值 `program.<名>.enabled`（布尔：`&&`/`\|`/`!`/括号/`true`/`false`），false ⇒ 该级**不进链**，并**自报跳过名单**（X11）；表达式里出现无法识别的名字 ⇒ **保留该级 + 一次性 WARN**（X9 不猜） |
| **判据** | `[chain] post chain ready` 自报行里 passes 从 11 减到 9（DOF/MOTION_BLUR 两级消失）且**打出跳过原因**；随后跑 `every=1` 对照臂看 3 帧周期空帧是否变化 |
| **✅ h48u/h48v 落地（2026-10-08）：判据达成** | 接线 = `ChainEnableGating`（纯决策 + 自报）复用 `ProgramEnableGate`（三值求值，不重造），门控点在 `PackPostChain.build` 的候选循环里、**先于**槽位上限检查；开关 `pack.chainEnableGating` **默认开**（与 `capabilityGate` 默认关相反且有意：本刀执行的是**包自己**写下的声明，不执行才是违约）。运行侧逐字（h48v）：`[GAP-024] post chain enable-gating: pack=BSL_v10.1.8 gating=on considered=11 switches=6 kept=9 skipped=2 skippedNames=[composite2, composite3] unresolved=[]` + `[chain] post chain executed: passes=9 first=deferred last=final` ⇒ **11→9 且点名跳过原因**，判据达成。单测 12 条（`ChainEnableGatingTest` 8 + 真包端到端 `BslChainGatingEvidenceTest` 4），全仓 1020 条绿 |
| 🔴 **本条顺手挖出并修掉一条老 bug（比门控本身更重要）** | h48u 首跑自报的是 `skipped=[composite1, composite2, composite3]` —— **光柱被砍**，而我方选项表同时逐字写着 `option name=LIGHT_SHAFT … default=true`、包里 `settings.glsl:205` 是 `#define LIGHT_SHAFT`。两条不可能同时成立 ⇒ 查前提，查出 `PackPostChain` 的**按名去重是先到先得**，而 BSL 枚举顺序是 `world-1 → world0 → world1` ⇒ 候选里留的是 **world-1 那条 Program**，可它的片元源**永远不会被选中**（`selectFragment` 给非偏好维度打 `MAX_VALUE`）。BSL 恰好写着 `program.world0/composite1.enabled=LIGHT_SHAFT`（真）与 `program.world-1/composite1.enabled=LIGHT_SHAFT && MULTICOLORED_BLOCKLIGHT`（假）⇒ 门控一接上就按一个**根本不进链的维度**做了决定。🔖 **在此之前它已经是「settings 里 blend/alphaTest 取错维度」的潜在坑**，只是没有可观察后果而已 ⇒ 修法 = 去重改用与取源**同一套** `chainDimensionRank` 优先级（单点真源，不留第二份口径）。教训形式：**「数据取错了维度」这种 bug，只在有人真读那个字段那天才暴露** |


### GAP-025 · 🟡 `noisetex` 用的是我方内置 64×64，包声明的 512×512 取不到（h48p 审计）

`bridge/PackTextures.java:121` 恒返回内建 64×64，而包的 `texture.noise=tex/noise.png`（`shaders.properties:141`）+ `noiseTextureResolution=512`（`final.glsl:41` 依赖它做 dither 尺度）⇒ 抖动/噪声频率与包设计不符。修法 = 优先用包里的 `tex/noise.png`（走既有 customImages 的加载路径），拿不到再回退内建并 WARN 一次。


| **状态（h48p 后续）** | 🟡 **代码已实现，但只在单测层面成立**：`bridge/NoiseSamplerSource.java`（纯判定，四态 PACK / BUILTIN_NOT_DECLARED / BUILTIN_DECLARED_BUT_MISSING / NO_PACK）+ `PackTextures.view()` 不再短路、`ensureReady()` 拆分；测试 `NoiseSamplerSourceTest`（8 条，含「旧短路」红灯回归与真 BSL 链路：properties → `PackTextureBindings.fromDirectives` → 判定，并核对 PNG IHDR=512×512）。🔴 **未在运行客户端里观察过**（写码期间客户端被别的取证占着）⇒ 本条**不关**：判据 = 一次 runClient 里 `noisetex` 绑到 512×512 那张（自报行 + dither 尺度可读），且**没声明 noise 的包**仍走内建并打一次性 WARN |
---

### GAP-026 · 🔴 **取证期的状态会泄漏进产品路径**：持久化包选项 store 没有任何闸门（h48r 发现；2026-10-08 登记）

| 字段 | 内容 |
|---|---|
| **缺陷形态** | `config/vkdisp-pack-options.properties`（`PackOptionStore.pathFor(gameDir)` ⇒ **每条车道一份**，`build.gradle:90` 把 iso 车道的 `gameDirectory` 指到 `run/h27`）里残留的键，会被 `ShaderPackCompiler` **正常改写进源**，与用户主动改的选项**走同一条路**、**没有任何标记区分**。⇒ 一臂「测的是哪一档」由**上一次跑过什么**决定 |
| **为什么现有两道防线都没拦住** | ① `PackCapabilityGate` 按裁决**只在内存里**改值、明确「`PackOptionStore` 一个字节都不碰」⇒ 不会替取证清残留；② 取证脚本传的 `pack.optionOverrides=""` 清的是**配置档覆盖**那条通道，**读不到 store**；③ `capabilityGate=false`（为了让臂间单变量）⇒ 连内存里的纠正也停了。三条叠加 ⇒ `ADVANCED_MATERIALS=true` 从 h45 起**一路穿过所有 BSL 臂** |
| **代价（本轮实测）** | ① 登记表 GAP-008 `h44` 那一行把「8 附件 `[0,3,6,7]`」写成了「BSL **默认**配置」，并据此**推翻了一条本来正确的注释**（`MrtPlan`），错判了整整一轮；② §二十一/二十三 证明它同时**改变了被测量的症状本身**（8 附件档每 3 帧空一次；真默认档 240 帧 0 空帧）⇒ 用那一档推出来的「空帧机制」**全部作废** |
| **信息一直在，缺的是把它变成判据** | `PackOptionEvidence` / `PixelProbePlan` **每条探针行**都打了 `effective={…}`，转译期也打了 `选项覆盖已改写进源: 命中 3/3 [ADVANCED_MATERIALS=true, …]` ——本轮是靠**读这两行**才发现的。⇒ 修法不是再加一行日志，而是**让「档」成为臂的通过条件之一** |
| **候选修法（未实现，先登记；T12：不自作主张补产品行为）** | ① 包加载时若 store 里存在**与该包默认值不同**的键 ⇒ 打**一条 WARN 列出键名 + 与默认的差异**（把「我测的是哪一档」变成每次运行都必须读的行）；② 车道具（`tools/vulkan-local/`，注意 `.gitignore:80` 不收它 ⇒ 只能靠文档存形）在起臂**前**检查 store 是否含被测包的键，含且本轮没显式声明 ⇒ **直接判该臂无效并退出**（现在的 `h45_arm.sh:44-51` 只是「警告」，不拦）；③ 给取证用覆盖加**一次性**语义（跑完即失效），从机制上取消「残留」这个概念 —— 这条会改产品行为，需要单独裁决 |
| **状态（h48t 追加，2026-10-08）** | 🟡 **修法 ① 已落地（单测 + 运行侧各半）**：`PackOptionsSession.residueReport(...)` 在生效链的 store 回放之后做**工作值 vs 基线**差分 ⇒ 有差异出 `WARN STORE_RESIDUE`（**点名每个键 + 给出默认值**），无差异出 `INFO STORE_RESIDUE_NONE`（**计数 0 也要打**，否则「没打这行」与「这行说没有」不可区分）。`store == null` 那条路也照打。单测：`PackOptionsSessionTest` 两条（残留分支逐字含 `ENABLE_FOG=false` 与 `默认 true`；干净分支两种入参都要有 `NONE`）。
✅ **运行侧已在产品链路里看到这一行**（`h48t`，`mrt.pixelProbe=false` 的纯加载臂，逐字）：
`composite source diagnostic: INFO: BSL_v10.1.8: 选项 [STORE_RESIDUE_NONE] 包 'BSL_v10.1.8' 的持久化选项与基线完全一致（残留 0 项） ⇒ 本次生效的就是包默认档`，
同臂契约行 `colorTargets=1 declaredOutputSlots=[0] samplers=5 varyings=9 unwrittenAttachments=[]`
⇒ 「档」从此是**每次运行都会自报的一行**，不再需要事后 grep `effective={…}`。🔖 **覆盖范围要划清**：本行只管**store 这一路**；配置档 `pack.optionOverrides` 由既有的 `选项覆盖已改写进源: 命中 N/N` 自报 —— **两条通道合起来才完整**，任缺一条都会「以为有闸门其实没有」。修法 ②（车具起臂前判无效）与 ③（一次性覆盖）未做 |
| **✅ h48y 补上修法 ②（车具侧闸门）** | `tools/vulkan-local/h48_flicker_capture.sh` 在「进世界信号」之后**立刻读产品那两条自报行**判档：`STORE_RESIDUE` ⇒ **本臂判无效并 exit 1**（除非显式 `H48_ALLOW_STORE_RESIDUE=1`，届时结论必须标注「非默认档」）；`STORE_RESIDUE_NONE` ⇒ 打「档位核验通过」；**两行都没有也 exit 1** 并写明「按判据缺口对待，不许当成无残留」。🔖 **车具不自己解析 store 文件**：那会造出第二份「加载的是哪个包 / 哪些值算残留」的口径，迟早与产品侧不一致（本项目为「造键用 A、校验用 B」付过学费）⇒ 只读产品自报。修法 ③（一次性覆盖，改产品行为）仍未做。证据 `evidence/h48-flicker-and-readback.md` §二十六 |
| **🔖 顺带掉出的一条工具层事实** | **别改正在被运行的脚本**：本轮在 h48y 跑着的时候给该脚本插了 28 行，该臂输出直接从「链已跑起来」跳到「预热等待」——**新插的闸门整段被安静跳过**（bash 按字节偏移续读已打开的脚本）。⇒ ① 那次运行**没经过**闸门，它的「无残留」是**产品打的**、不是**闸门判的**（两件事不许混）；② 改车具前先确认没臂在跑，或改完重跑一臂才算验证；③ 又是同一个形状：**没报错不等于执行了**。 |
| **关闭条件** | ①②落地，且一次真实臂的运行日志里出现「store 含 N 个非默认键」这条自报（N=0 时也要打出来，否则「没打」与「没有」不可区分 —— 本项目老坑）|
| **证据** | `evidence/h48-flicker-and-readback.md` §二十二（含逐字日志与 mtime）、§二十三（换档后空帧归零的对照） |

---

### GAP-027 · 🔴 非地形的 `gbuffers_*` 程序**一条都没接**（云/水/实体/手/天气；h48t 画面判读直接看见）

| 字段 | 内容 |
|---|---|
| **症状（按图，不按数字）** | `evidence/h48-images/h48t-default-9.png`：抬头这片天上**没有任何云**，而 BSL 默认档 `LIGHT_SHAFT`/云都是开的 ⇒ 不是选项问题，是这些 draw 根本没走包的着色器 |
| **现状** | 只有 `gbuffers_terrain`（经 `ChunkSectionLayer#pipeline(boolean)` 的派生管线）与 `gbuffers_skybasic/skytextured`（经 `SkyIntoGbuffer` 借 `SkyRenderer` 自建的 pass）落地。`water / clouds / entities / entities_glowing / textured / weather / hand / beaconbeam / spidereye / shadow` **全部未接** ⇒ 包的 deferred/composite 读到的那些槽只有清屏值或上一代残留 |
| 🔑 **本轮新查到的结构性事实（这条改变了本条的可行性评估）** | 原版**有一个官方、非 mixin 的单点换管线入口**：`com/mojang/blaze3d/systems/RenderSystem.java:106` 的 `getCompiledPipelineNullable(RenderPipeline)`，其**第一条语句**就是 `pipeline = PIPELINE_MODIFIERS.apply(pipeline);`（:107，**本轮从 sources jar 逐字复核过这两行**）；配套公开 API 是 NeoForge 的 `RegisterPipelineModifiersEvent`（`net/neoforged/neoforge/client/pipeline/RegisterPipelineModifiersEvent.java:28`）+ `RenderSystem.pushPipelineModifier/popPipelineModifier/renderWithPipelineModifier`（:502/:507/:513）。⇒ 实体/手/天气/云这些**不走 `ChunkSectionLayer`** 的 draw，第一次有了「不逐个点加 mixin」的接法。<br>⚠️ **口径分开写**：「**全游戏 30 处取管线都过它**」这个数字来自子代理统计，**本轮未独立复核** ⇒ 用它当依据前自己数一遍；本轮独立核实的只有「`getCompiledPipelineNullable` 首条语句是 `PIPELINE_MODIFIERS.apply`」这一条。 |
| **用它的三个硬约束（源码级，别踩）** | ① 栈必须配平 —— `ClientHooks.java:863` 在 `RenderFrameEvent.Post` 之后立刻 `ensurePipelineModifiersEmpty()`，漏 pop 会**抛**；② modifier 必须**幂等**且必须真的改变 `location`（`PipelineModifierStack.java:57-66`）；③ 未知管线是**按需编译**的（`PipelineCache.java:36`）⇒ 我方派生的多附件变体不需要预先注册 |
| **包的输出落点（逐行取自包，决定优先级）** | `gbuffers_water` → `01`/`018`/`0186`/`016`；`gbuffers_entities` → `0`/`08`/`083`/`08367`/`03`/`0367`；`gbuffers_clouds` → `0`/`0367`；`gbuffers_weather` → `0`。**这些槽全被链读回去**：colortex1 被 `composite.glsl:45`/`composite5:31`/`final:16` 读，3/6/7 被 `deferred1.glsl:41,53,54` 读，6/8 被 `deferred1.glsl:53,58` 读 ⇒ 不是「接了也没人看」 |
| **建议的第一刀（由上述判据选出，不是由好恶选出）** | **`gbuffers_water`**：① 它和地形是**同一个已证过的收口点**（`ChunkSectionLayer#pipeline(boolean)`，我方 mixin 今天已经覆盖 TRANSLUCENT 组，只是对它返回「不换」）；② 几何走的是同一个公开 `ChunkSectionsToRender.renderGroup(TRANSLUCENT, pass, …)`（`ChunkSectionsToRender.java:45`），用的就是已捕获的那份 `ChunkSectionsToRender`；③ 需要做的只是「再加一次 renderGroup + 一条派生 TRANSLUCENT 多附件管线 + `pass.requires(terrainPass)` 定序」，且它产出 colortex1（TAA/反射要用）。**第二刀 = clouds**（`CloudRenderer.render(CloudStatus, RenderPass)` 是 public 且**自己收 RenderPass**，与天空同形） |
| ✅ **h48x 已落的第一步 + 实测出来的水契约（这条改变了工作量估计）** | 已落：`PackTerrainSource` 的选程序**参数化**（新增 5 参 `generate(…, programName)`，4 参版委托到它 ⇒ 选包/门控/选项覆盖/编译/槽位兑现**复用同一条链**，不另写一份），`MrtPlan` 的冻结契约从「单程序」改成「多程序取 max/并集」（`freezePackPrograms`，仍是一次 volatile 写入 ⇒ 附件数与槽集不会分两次漂），并加 4 条单测。**实测（真 BSL 默认档，逐字）**：`gbuffers_water` = **2 个输出 / 声明槽 [0,1]**、**8 个自由 sampler** `[texture_0, gaux2, depthtex1, noisetex, gaux1, shadowtex0, shadowtex1, shadowcolor0]`、**14 条 varying**（首 `mat`、末 `vTexCoordAM`）。对照地形默认档 = 1 输出 / 5 sampler / 9 varying。<br>🔴 三条直接后果：① **pass 附件数不能再按地形定**（1 → 必须 ≥2，否则 `setPipeline` 抛）—— 这就是 `freezePackPrograms` 取 max 的理由；② 水要 **`depthtex1`** ⇒ 本条与 **GAP-023**（depthtex0/1/2 同一张视图）**正面相撞**，接水之前或同时必须处理它，否则水的 `z1 > z0` 那类判据恒假；③ 水要 **14 条 varying**（比地形多 5 条，含 `vTexCoordAM`）⇒ 顶点适配层要按**每条程序各自的** in 签名生成（`PackVertexAdapterGenerator.generate(inputs, …)` 本就按清单参数化），而其中 `normal`/`tangent`/`mc_Entity` 一类仍受 **GAP-007** 的顶点格式上限约束 ⇒ 接上水**不等于**水的效果全对，这条要在判据里分开写。 |
| **顺序约束（已实测过一次的坑）** | 帧图的执行序**只由 `FramePass#requires` 决定**，插入序不算依赖（`evidence/h48` §十六/§十七：没声明 `requires` 时天空被排到地形之后，又被地形盖回去）。⇒ 每加一条 gbuffer pass 都必须显式定序，且**必须**在 `04-SPEC`/本表里留一条自报，否则又变成「接了但看不见」 |
| **未定项（不许当已知用）** | ① `shadow` 的原版绘制路径与可换点**未查**；② `featureRenderDispatcher` 字段私有且无 accessor（`LevelRenderer.java:123`）、`PreparedFrame` 是单实例复用且重复使用会抛（`FeatureRenderDispatcher.java:190`）⇒ 「整帧重放」这条路**能不能走通未证**；③ 手（`GameRenderer.java:411-412`）的 pass 目标是**写死主目标**的 ⇒ 没有公开换点，要么 mixin 要么我方重画；④ BSL **不带** `gbuffers_textured_lit` / `gbuffers_terrain_translucent` / `gbuffers_block_translucent`（目录列举核实） |
| **判据（每条程序各自收口）** | 该程序的 `DRAWBUFFERS` 声明槽在**链跑之前**就有非清屏内容（`mrt.pixelProbe` 逐槽取点 + `declaredOutputSlots` 同源挑槽），且画面侧能看见对应物体（云/水面/实体各自的可辨认特征），且 `[route]`/新 pass 自报行报出该程序名 |

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
