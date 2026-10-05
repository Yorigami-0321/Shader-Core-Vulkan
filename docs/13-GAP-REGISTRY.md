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
| GAP-004 | 自定义 uniform 块无处安放：BSL/Iris 的 deferred pass 需要 `gbufferModelViewInverse`、`shadowModelView`、`shadowProjection`、`sunPosition`、`moonPosition` 等 OF 内建矩阵/向量，原版 `Globals` 仅 9 字段且不含这些 | 已源码级核实：bind group 布局在 `RenderPipeline` 构造时固化，无法给原版管线追加 uniform 块（见 GAP-003 ③）。**2026-10-03 实测补充**：派生管线多出的 bind group 条目**必须**在 draw 前 `setUniform`，否则驱动层 STRICT_VALIDATION 抛 `Missing uniform 名`（原版 `renderLayers` 只绑 `TerrainUniform`/`Sampler0`/`Sampler2`，没人会绑我们那条） | 随 GAP-003 一并在**派生管线**上构造独立 uniform bind group 布局，随管线一起注册；绑定由 M-01b 注入点补 | `pipeline/`（派生管线构造）+ `mixin/`（M-01b 绑定） | `mixin.bindTerrainParams`（M-01b，已实现） | 原版管线支持追加 uniform 块 | 🟡 **块已挂上、每帧绑定、且已被消费**（`h08`，2026-10-04）：派生 MRT 管线片元换成包的 `gbuffers_terrain` 后，**42 个块成员 + 5 个 sampler 一条不漏**绑定（未触发 `Missing uniform`），`VkDispBuiltins` 608 字节环由 `OfUniformManager` 按 std140 偏移每帧填值（🔴 原文「实测 0 validation error」已撤回：本机无 validation layer；绑定是否成立的判据是 draw 不抛 `Missing uniform` 且画面正确）|
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

---

### GAP-015 · 🔴 原版**没有「比较采样器」能力** ⇒ `sampler2DShadow` 无法类型匹配绑定（**2026-10-05 新登记**）

| 项 | 内容 |
|---|---|
| **需求来源** | GAP-012 的**同类问题再次出现**：这次不是「3D 视图喂给 sampler3D」，而是「**非比较**采样器喂给 `sampler2DShadow`」 |
| **原版现状（源码级核实，`h38`）** | 从 `minecraft-patched-26.3.0.41-beta.jar` 逐类反汇编：<br>① `GpuDevice` 只有**一个**工厂 `createSampler(AddressMode, AddressMode, FilterMode, FilterMode, int, OptionalDouble)` —— **签名里没有 `CompareOp`**；<br>② `SamplerCache.getClampToEdge(FilterMode, boolean)` 的那个 `boolean`，经 `LocalVariableTable` 核实是 **`useMipmaps`**，**不是** `compare`。<br>⇒ **拿不到 `VkCompareOp != NONE` 的采样器** |
| **触发条件** | BSL v10.1.8 把 `shadowtex0` / `shadowtex1` 声明为 `sampler2DShadow`（全包扫出 `sampler2DShadow` 3 个名字）；实测本包地形程序的绑定摘要是 `SHADOW_DEPTH_2D=2` ⇒ **每种配置都在** |
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
