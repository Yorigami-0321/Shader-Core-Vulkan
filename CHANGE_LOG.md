# 变更记录（CHANGE_LOG）

> 格式与流程依据：docs/15-ITERATION.md「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---
---
---

## 2026-10-12（一百一十八）— ✅ R1a 第 4 步落地：**pass 身份由数据给出**（M-15 两支 `@WrapOperation`）；`inMrtPass` + `finally` + 空转守卫 + `MRT_GUARD_FALLBACK` 一并删除，M-01 退化成「只按层名查表」

> **verdict = 把「靠记得清标志位才正确」换成「读手里就有的数据」**：判据从静态布尔换成
> `renderPass` 的**对象身份**，于是那三件为布尔而存在的补丁整体消失（`GAP-040`）。

- **① M-15 落地（两支）**：`mixin/ChunkSectionsToRenderSeparatePipelineMixin`（第 176 行那支，
  `multiDraw=false`）+ `...IndirectPipelineMixin`（第 121 行那支，`true`）。用 MixinExtras 的
  `@WrapOperation` + `@Local(argsOnly=true)`，handler 同时看到**正要编译的管线**与**所在方法的
  `renderPass` 形参** ⇒ 「在不在我方 pass 里」不再是猜的。两支合起来才覆盖
  `usingMultiDrawIndirectForTerrain` 的二选一 —— 少注一支 = 那一支仍走老路（与旧 M-05「两个重载都要注」同一条纪律）。
- **② 删掉的三件**：`MrtTerrainPass.inMrtPass` 与它两处 `finally` 清理、
  `hasExpectedAttachmentCount()`（`GAP-040`：判定口径是「`inMrtPass` 为 true 就返回 true」= 拿标志位证明标志位）、
  `TerrainPipelineApi.MRT_GUARD_FALLBACK`（为那个空转守卫服务的去重告警集）。
  `TerrainDrawCapture` 侧留一个 `ownPass` 引用 + `isOurPass(renderPass)`，`M-01` 则退化成
  **只按层名交出单附件派生版**，多附件升级只在 `PassAwarePipelines.select` 这一处。
- **③ 判据的形状**：`select` 是**纯函数**（`ourPass=false` ⇒ 逐字原样返回入参），
  于是「故意错配一次必须被拒」不需要客户端就能守（`MixinWiringTest` /
  `MrtTerrainPassWiringTest` 换成断言新形状：不再出现 `inMrtPass`、不再出现 `} finally {`、
  `ownPass = renderPass;` 必须**两条 pass 各一次**）。
- **④ 巨类棘轮逼出来的拆分**：判定与观测面本来想写进 `TerrainPipelineApi`，但它已顶在 1131 行上限
  ⇒ 按 `ClassLineRatchetTest` 的口径「新增内容落到独立类」新建 `bridge/PassAwarePipelines`，
  表的所有权仍在 `TerrainPipelineApi`（只交出一个包内可见的 `mrtVariantOrNull`）。
  本轮三个巨类实测**都没长**：`MrtTerrainPass` 1068（上限 1069）、`VkDispConfig` 1042（1047）、
  `TerrainPipelineApi` 1120（1131）。
- **影响的文档**：`04-SPEC` §5.0 M-15 行（候选 → 已实现，含关档代价与 M-01 退化）、
  `13` 的 `GAP-040` 状态（修法已落地、真机未跑）、本文与 `19` §5A.4 的 R1 行。
  mixin 清单：7 → **9** 个 client 类；`MixinTargets` 再加 3 个常量。
- **测试**：`./gradlew test --offline` → **BUILD SUCCESSFUL**（1165 条，1 条既有 skip）。
- **未验证（不许当已收口）**：① 真机单变量 A/B 对 `h08` 基线 **48.22 / 39.03%** 同量级；
  ② `Mixin apply failed = 0`（本轮新增 2 个 `@WrapOperation` 注入点，且 `@Local(argsOnly=true)`
  这条路**本机从未跑过**）⇒ 这两条不跑完，R1a 就只能记「代码完成、验证未结」。
- **提交**：✅ 本地提交并推送（stage 覆盖 main + test + resources + docs，`git status` 干净）。



## 2026-10-12（一百一十七）— ✅ R2 第 3 步落地：**M-06 一点替两点**（`addMainPass` HEAD 一次拿齐地形 draw + 雾切片），M-05 那两个 RETURN 注入点与 `volatile Object`+`instanceof` 一并删除，pass 体第一行补 `setShaderFog`（GAP-038）

> **verdict = 捕获点收敛轮**：注入点从 2 个变 1 个，捕获字段的类型从 `Object` 变回它本来的类型，
> 并且把「我方 pass 从不设雾」这个潜在静默错按原版的口径补上。

- **① M-06 落地**：`mixin/LevelRendererMainPassCaptureMixin` 注 `addMainPass` 的 `@At("HEAD")`，
  五个形参**逐个照抄**（`FrameGraphBuilder, FeatureRenderDispatcher.PreparedFrame, GpuBufferSlice terrainFog,
  ChunkSectionsToRender, boolean`，sources jar 第 396-402 行）—— 原版改签名 ⇒ 注入期硬失败，不静默。
  选择它而不是两个 `prepareChunkRenders*` 的依据（源码级）：第 **277** 行那次调用**无条件**执行，
  而 indirect（272）/常规（274）两支都在它之前汇合 ⇒ 一点顶两点，且这个点**同时**交出了雾切片，
  而旧点只能拿返回值。时序不变：HEAD 在 277，仍早于第 286 行 `frame.execute()`（pass 体执行期）。
- **② M-05 退役**（不是「留着备用」）：`LevelRendererChunkCaptureMixin` 整类删除、
  `MixinTargets` 里 `LEVEL_RENDERER_PREPARE_CHUNK_RENDERS*` 两个常量删除、
  配置键 `mixin.captureTerrainDraws` → `mixin.captureMainPassArgs`。
  `TerrainDrawCapture` 的两个字段类型化为 `ChunkSectionsToRender` + `GpuBufferSlice`
  ⇒ `MrtTerrainPass#drawTerrain` 里那段「`instanceof` 失败就 ERROR」的兜底**物理消失**
  （类型由注入点保证，不再靠运行时猜）。守卫跟着改：`MixinWiringTest` 现在**断言旧类与旧键都不存在**，
  `MrtTerrainPassWiringTest` 断言不再出现 `instanceof ChunkSectionsToRender draws` / `Object captured`。
  🔖 诊断红利：自报从「命中了哪个注入方法」换成**运行期变体名**（`DrawIndirect`/`DrawSeparate`）——
  这是本机走哪条绘制分支的直接证据，旧形式给不出。
- **③ GAP-038 补雾**：pass 体第一行 `RenderSystem.setShaderFog(TerrainDrawCapture.currentFog())`，
  位置口径逐字对齐原版（`LevelRenderer:436` 就在建 pass 与 `bindDefaultUniforms` 之前）。
  此前我方只 `bindDefaultUniforms` 而从没设过雾 ⇒ Fog 那块 UBO 拿的是**上一帧/别处**的残值。
- **影响的文档**：`04-SPEC` §5.0 的 M-05（⚪ 已退役）与 M-06（✅ 已实现）两行；`13` 的 GAP-038 状态；
  本文与 `19` §5A.4 的 R2 行。mixin 清单仍是 7 个 client 类（一增一删）。
- **测试**：`./gradlew test --offline` → **BUILD SUCCESSFUL**（1165 条，1 skip = 既有 `PackUniformBslTest`）。
  两处棘轮上限被这轮**压着没长**：`MrtTerrainPass` 靠压缩注释回到 1069，`VkDispConfig` 回到 1047。
- **未验证（不许当已收口）**：判据「进水那一帧两臂雾端读数都跟着变」是真机 A/B ——
  **现状不变就是 GAP-038 复现**，这一条本轮没签。M-06 这个注入点也还没在真机加载过
  （`Mixin apply failed` 必须实测为 0）。
- **提交**：✅ 本地提交并推送。

---

## 2026-10-12（一百一十六）— ✅ R3 第 2 步落地：**反射清零**（M-08/M-09 访问器）、**自建采样器 42 行删除**（M-07 复用原版实例）、**云的压栈/还原删除**（M-10 `@Redirect` 改实参）、**modifier 现读配置移到调用点**（GAP-044）

> **verdict = 纯删码轮**：四条改动都不新增渲染语义，只把「可静默失效的取值方式」换成「升版即硬失败的取值方式」。
> 判据里两条**要真机的还没签**（见文末「未验证」），代码侧的红线已经划到位置上。

- **① M-08/M-09：反射清零**。新增 `mixin/ChunkSectionsToRenderDrawProbeMixin`（目标
  `ChunkSectionsToRender$DrawIndirect` —— 字段在嵌套变体上，基类没有它）与
  `mixin/CloudRendererMeshProbeMixin`（`quadCount`），配 `bridge/IVkdispTerrainDrawProbe` /
  `bridge/IVkdispCloudMeshProbe`。`MrtTerrainPass#probeDrawCounts` 与 `CloudsIntoGbuffer#meshState`
  里那两处 `getDeclaredField + setAccessible` 连**同一条 `catch` ⇒ WARN 分支**一起物理删除。
  🔖 口径如实：**「零命中」指代码，不指 grep 全文** —— 本轮新写的注释里留了 9 处
  「被否决的反射方案」字样（`2. 备选：① 反射 …` 那一格），`grep getDeclaredField` 会连注释一起数。
  🔖 顺带查实：`drawCount` 那一半**反射本就是多余的** —— `GpuMultiDrawIndexedIndirect` 是 `public record`。
  ⚠️ 两条如实的范围收缩：M-08 只覆盖 indirect 变体（本机走这条，另一变体命中时自报**直说变体名**）；
  M-09 只交出 `quadCount`（判据等价：`quadCount!=0` ⇒ `texture!=null`；`utb` 为 null 会**抛 NPE**，
  不属静默失效那一族 —— 拿它当判据会把响亮失败登记成静默失效）。
- **② M-07：地形图集绑原版那个实例**。`mixin/LevelRendererChunkSamplerMixin` + `bridge/IVkdispChunkLayerSamplerAccess`
  读出 `LevelRenderer#chunkLayerSampler`（private，第 146 行）⇒ `MrtTerrainPass` 的 `atlasSampler` 静态字段与
  `ensureAtlasSampler()` 整块删除（各向异性恒 1 那个偏差 = `GAP-041` 本体）。取法改成**绘制点每帧现取**，
  因为原版会在自己 pass 体内 `close()` 并重建 ⇒ 缓存等于用死对象。首帧为 null ⇒ 判空后本帧不画 + ERROR 一次
  （h33 那条「不许把 null 传给 setUniform」的纪律换形状、没换含义）。
  🔴 **新风险如实登记**：原版实例 maxLod=empty ⇒ `mrt.terrainAtlasLod0` 那条 `GAP-016` 止血不再作用于地形图集；
  若地图采样回到恒 0，`mixin.reuseChunkSampler` 是 A/B 位，结论回 `GAP-016` 而不是回代码里加拐杖。
- **③ M-10：云不再碰全局栈**。`mixin/CloudRendererMatrixRedirectMixin` `@Redirect` 掉
  `CloudRenderer:226` 那次 `RenderSystem.getModelViewMatrixCopy()`（目标写**完整描述符** —— 该类有三个 `render`）
  ⇒ `CloudsIntoGbuffer` 的 `stack.set` + `saved` + `finally` 整块删除。🔖 对原版是**恒等变换**：原版只在
  `LevelRenderer:738`/`:646` 画云，那两处栈顶本就是装配期相机矩阵。
- **④ GAP-044：`apply` 里的现读配置移到调用点**。`GbufferPipelineSwaps` 注册**两个 key**
  （`clouds_no_cull` / `clouds_no_cull_no_depth_write`），两个 modifier 都不读配置；`CloudsIntoGbuffer`
  每帧现读 `mrt.cloudsNoDepthWrite` 后**选 key** ⇒ 「第一次 push 那刻的值被永久冻结」这一族解除。
- **影响的文档**：`04-SPEC` §5.0 的 M-07…M-10 四行（候选 → 已实现 + 范围收缩与关档代价）；
  `13` 的 GAP-041/042/044 三条状态；本文与 `19` §5A.4 的 R3 行（代码完成、验证未结）。
  `MixinTargets` 新增 7 个常量；`vkdisp.mixins.json` 从 3 个 client mixin 到 **7 个**。
- **测试**：`./gradlew test --offline` → **1165 tests, 0 failed, 1 skipped**（那 1 条 skip 是既有的
  `PackUniformBslTest`）。守卫跟着改而不是绕开：`MixinWiringTest` 的「注入首行必须埋点转发」现在
  分两种形状（有体 ⇒ 查转发；`@Accessor` 无体 ⇒ 查 `implements IVkdisp*` + 文件里零反射）；
  `VolumeStubCapabilityTest` 那三条采样器守卫按 M-07 新不变量重写（其中一条此前因方法被删而**静默 skip**，
  现在是真的在断言）。⚠️ 代价如实登记：四个新键把 `VkDispConfig` 顶过 1000 行 ⇒ 它成为**第 7 个巨类**、
  进了 `ClassLineRatchetTest` 的 CAPS（1047，只许降）；按域拆配置类是 `19` §3.4 的下一格。
- **未验证（不许当已收口）**：① 各向异性两档锐度随用户设置变（真机 A/B）；② 同机位同帧读数与今天相同
  （`c0@afterTerrain` / `c0@afterClouds` 探针）；③ 启动日志 `Mixin apply failed` = 0 —— 本轮新增 4 个
  mixin 类、其中两个是 `@Accessor`，**没有真机跑过就意味着 applier 侧还没被证明**。
- **提交**：✅ 本地提交并推送（本轮结束时 `git status` 干净）。



## 2026-10-12（一百一十五）— 🔴 R1 的幂等收敛设想被**证伪**（改判回 M-01 + 新增 M-15）；M-04 牵连面清单交付，范围缩小到 1 个 GPU pass、真实风险改型为「开放集合」

> **verdict = 同日第二轮调研 + 自我改判轮，零 `src/` 改动**：用户要求「确认 R1 幂等收敛方案与 M-04 牵连面清单」。
> 结果一条是**证伪**（推翻的是一百一十四轮我自己写下的结论），一条是**交付 + 范围缩小**。
> 原版/NF 侧每条都逐行核过（`/tmp/mc-src` = 26.3 反编译源，`/tmp/nf-src` = NeoForge 26.3.0.51-beta 源）。

- **① R1 幂等收敛：原设想不成立 ⇒ M-01 改判「保留」**。缓存结构是
  `Map<modifier key, Map<入参管线, 结果管线>>`（`PipelineModifierStack:47-70`，`Reference2ReferenceOpenHashMap`，
  **只有 get/put、没有任何失效入口**），而 modifier key **只能在启动期注册**（nf `ClientHooks:796 / 797-800 / 825-826`
  那个一次性窗口 + `RegisterPipelineModifiersEvent:28-32` 的 `putIfAbsent` + `PipelineModifiers:16` 包内 `static final`）。
  ⇒ **「包代次」既进不了缓存的第二维、也进不了 key**，而我方 MRT 地形管线的身份随切包变
  （片元 = 包的 `gbuffers_terrain`，附件数 = `MrtPlan.freezePackPrograms:169-197`）⇒ modifier 会在切包后继续返回
  **上一代**那条 —— 等于把 `GAP-032`（绑定组不同代 ⇒ `Missing uniform` 崩帧）的脏状态**永久化**。
  🔖 顺带查实：`TerrainPipelineApi` 的派生表 `DERIVED`/`DERIVED_MRT`（定义 `:72/:82`）**只有两处 put（`:157`/`:222`），
  全仓无任何 remove/clear** ⇒ 我方今天也没有「切包重建派生管线」的机制，别把换通道当绕开 `GAP-032` 的路。
- **② R1 的现行形态 = R1a（新增 M-15）**：`@WrapOperation` 包住 `ChunkSectionsToRender` 第 **176 / 121** 行的
  `RenderSystem.getCompiledPipeline(...)`，handler 读外层 `renderPass` 实参 ⇒「在不在我方 pass」由**数据**给出。
  删的仍是原设想想删的三件（`inMrtPass` `MrtTerrainPass:119` + `finally` 纪律 `:532-539` + 空转守卫 `:320-322`
  + `MRT_GUARD_FALLBACK` `TerrainPipelineApi:530`），**但不依赖 modifier、不动注册时机**，成本 2–3 天 → **1–2 天**。
- **③ 新登记 `GAP-044`（既有缺陷，源码级）**：`GbufferPipelineSwaps:112` 就**在 `apply` 里现读** `MRT_CLOUDS_NO_DEPTH_WRITE`
  ⇒ 第一次 push 那刻的开关值被缓存永久冻结；今天靠**调用点闸门**（`CloudsIntoGbuffer:281/289`）掩盖 —— 只要出现第二处
  push 同一 key 就会变成「改了配置不生效」的零报错症状，且会污染同进程内的翻档 A/B（`X52`）。修法 3 行：`apply` 禁现读配置。
- **④ M-04 牵连面清单已交付（`04-SPEC` 新增 §5.0.6）**，四个要点：
  (a) 🔖 **前提纠偏**：`"main"` 是 FramePass，其 `executes` 体内开着 **5 个互相独立的 GPU render pass**
  （`:455-463` 主 / `executeOit :583-673` / `executeOutline :750-761` / `executeSeeThrough :487-496` / `executeAlwaysOnTop :498-522`），
  而 `FrontendRenderPass:107-124` 的附件校验是**逐 GPU pass** ⇒ **M-04 只需改 `:455-463` 那一个**；
  第 405-433 行那堆 `pass.readsAndWrites` 只是帧图资源声明，写入者各在别的 pass ⇒ 不冲突。
  (b) **必派生量级 ≈ 58–62 条**（地形 4 + 半透明 2 + 线框 2、`RenderTypes` 非 OIT ≈52、粒子 2、云 2、天气 1、世界边界 1；
  分母 = `RenderPipelines` 全表 **195** 条）；`prepareTranslucents`（`:451`，实现 `:544-581`）**一条管线都不取** ⇒ 代价 0。
  (c) 🔴 **真实风险不是这个数**：六处阶段事件（`:536/540/723/731/735/745`）把主 `renderPass` **交给在场任意 mod**
  ⇒ 派生面是**开放集合**，只能靠**通用派生公式**（`toBuilder()` + target0 原样 + 补槽 `RGBA8_UNORM`/`WRITE_NONE`/无混合，
  依据 `ColorTargetState:14-22`、`RenderPipeline:190/:288-291/:421-434`、`FrontendRenderPass:111-122`），
  且**格式不匹配时必须有「交回原版路径」的退路 + 计数**（新登记 `GAP-046`）。N 的硬上限 = **8 且无余量**
  （`MAX_COLOR_TARGETS = 8` + `new ColorTargetState[8]` + `Arrays.copyOf`）⇒ 与 `GAP-003` 那条「`DRAWBUFFERS:08367` 需 9 槽 ⇒ 显式拒绝」同口径。
  (d) ⛔ **三项待核未结之前不许给 R7 报成本**：非 OIT 的 `water_mask` 落在哪个 pass（`RenderTypes:716`，决定「一条管线要不要出两个变体」）、
  默认 `renderArea` 尺寸取自哪个附件、后端深度格式是否被硬编码（关系 `GAP-022`/`GAP-037`）。
- **改了什么（五份文档）**：`04-SPEC` §5.0（M-01 改判 + 新增 **M-15** 行 + M-04 行指向清单）、新增 **§5.0.6** 全节；
  `07-CONSTRAINTS` §1.4.2（「M-01 可被 modifier 替掉」标为**已作废的中间结论** + 幂等口径改写为
  「`apply` 必须是入参管线的纯函数，且凡影响返回值的东西都必须能进缓存键」）；
  `13-GAP-REGISTRY` 新增 `GAP-044`/`045`/`046`；`19` §5A.3 新增缓存那一行 + §5A.4 的 R1 行改写 + §6 打分表把 R1b 划掉 +
  新增 **§7-6** + 执行序改为 `… → R6 →（结三项待核）→ R7`；`00-INDEX` 的 19 号索引行同步。
- **测试结果**：`./gradlew test --offline --tests "*VulkanEvidenceDisciplineTest" --tests "*MixinWiringTest"`
  ⇒ 前者 **4 项 / 0 失败**、后者 **9 项 / 0 失败**（本轮只动文档）。零 `src/` 改动 ⇒ 未跑全量。
- **下一步**：执行序不变，但 **R1 换内容**（`R0 → R3 → R2 → R1a → R4 → R5 → R6 →（待核①②③）→ R7`）；
  `GAP-044` 那 3 行随 R3 同轮做（它是「以后复用 modifier 通道」的地基）。



---

## 2026-10-12（一百一十四）— 🔴 mixin 的「类别」红线按用户裁决废除；全仓调研落成 9 个候选注入点 + 6 格新缺陷 + 阶梯 R0…R7

> 🔴 **同日已被一百一十五轮部分改判**：本条结论 ①（「M-01 应被 `PipelineModifier` 替掉」）**已作废** ——
> modifier 的缓存键没有「包代次」这一维、key 又不能运行期增删 ⇒ M-01 保留，R1 改形态为 **R1a（新增 M-15）**。
> 其余结论（废类别白名单 / X24 作废 / M-06…M-14 登记 / `GAP-038`…`043` / M-04 排下一轮）**不变**。详见 §7-6 与 `GAP-045`。
>
> **verdict = 调研 + 定案轮，零 `src/` 改动**：用户指令「重新调研：已经实现的功能里哪些用 mixin 写更好」，
> 随后追加「**不用考虑红线**」⇒ 三条裁决落档（`19-IMPROVEMENT-PATHS.md` §7-5）。
> 取证方式：四域并行（地形链路 / 帧图与回读 / 天空与云 / 包装载与 UI），原版侧每条断言都对着
> **26.3 反编译源逐行核实**（`~/.gradle/caches/neoformruntime/intermediate_results/mergeWithSources_*_output.jar` 解包），
> 不是引用旧文档 —— 顺带揪出两处旧文档与现状冲突（见末段）。

- **改了什么（四份文档 + 一行索引）**：
  ① `07-CONSTRAINTS.md`：`M1` 行重写 + §1.4 背景表新增 2026-10-12 行 + 现行规则表三行替换 ——
     **类别白名单废除、登记制 / 可关闭制 / 逐个开启三条保留**；**X24 作废**（`GL11/GL14/GL15` 那半条的理由
     降级为「工程事实」而非禁令）、**X23 保留**（改判清楚：它属 L11 零集成支柱，不随 M1 放开）；
     §1.4.1 升版清单第 ① 行加「新注入点开工当天必须把签名补进本行」；§九 自检清单加一条。
  ② `04-SPEC.md` §5.0：登记表引言改口径；**M-01 改判「候选下线」**、**M-01b 改判「应删」**、**M-04 改判「🎯 下一轮目标」**；
     **新增 M-06…M-14 九个候选注入点**（每条带签名 + 原版行号 + 可关闭键 + 未实现状态）；
     §5.0.4 那条「取舍结论：走 A」加**作废注记**，并把方案 A 自己的账单记进去（地形双绘 + 图外同步一族）。
  ③ `13-GAP-REGISTRY.md`：新登记 **GAP-038 … GAP-043** 六格 —— 不设雾 / 天空四道门控缺失 / h20 守卫空转 /
     自建采样器硬编码各向异性 / 反射判据静默失效 / M-01 牵连建网格侧。
  ④ `19-IMPROVEMENT-PATHS.md`：新增 **§5A「弱点五 · 接入形态」**（现状五类机制分布 → 病根一条 → 调研结论表 →
     阶梯 **R0…R7** 带改动面/判据/成本/热路径 → 验收纪律）+ §6 打分表补 4 行 + **§7-5 裁决记录** +
     文档头部「对象」与状态块同步；`00-INDEX.md` 的 19 号索引行同步。
- **三条主要结论**（逐条都能跳到行号）：
  ① **M-01 那个 mixin 应该被删掉，不是加固。** `RenderSystem.getCompiledPipelineNullable` **方法体首行**就
     `PIPELINE_MODIFIERS.apply`（`:106-107`），公开 push/pop 在 `:502/:507/:513`，而地形两条取管线分支
     （`ChunkSectionsToRender:121` / `:176`）都经它 ⇒ 官方通道覆盖地形 draw **成立**。附带把「我在哪个 pass」那一族
     hack 替成 RAII —— 其中 h20 守卫**实测是空转**（`MrtTerrainPass:320-322` 函数体 = `return inMrtPass`，
     与它声称要防的 `active()` 同一个变量），已立 `GAP-040`。⏳ R1 的真实工作量是**幂等收敛**，已记为待判。
  ② **M-05 两个注入点能收成一个，且顺带查出一格静默缺陷。** `LevelRenderer:272/274` 两支在第 **277** 行
     无条件汇合到 `addMainPass`，其形参同时带 `terrainFog` 与 `ChunkSectionsToRender`；而官方
     `FrameGraphSetupEvent`（`:249` 触发）实参里这两个**都没有** ⇒ M-06。同时 `grep setShaderFog src/main/java/dev/vkdisp/`
     **命中数 = 0**（本轮实测），原版是在 main pass 体第一行设的（`:436`）⇒ 我方那次地形 draw 的雾是**残值**，立 `GAP-038`。
  ③ **包天空的正解不是注入。** 官方 `CustomSkyboxRenderer.renderSky` 就在 `"sky"` **pass 体内**被调（`:385`），
     而那一刻**没有 render pass 打开**（原版自己的 pass 在 `SkyRenderer:139` 才开）⇒ 搬进钩子免费拿到与原版互斥，
     并由原版替我们判那四道门控（岩浆/粉雪/遮挡/`Skybox.NONE`，`:370-371` 与 `:380`）—— 我方现状**一条都不看**，立 `GAP-039`。
- **同时明确判定「不该上 mixin」的 5 处**（免得本轮结论被读成「全都改成注入」）：
  3D/数组纹理（卡在 `VulkanGpuTexture` 硬编 `imageType(1)`，是后端分叉不是时序）、回读落地等待（有公开 `createFence`）、
  `colortexN`/`depthtexN`/`shadowtexN` 按名分派（OF 包语义，无原版结构可注）、`glsl/**` 翻译器与 `McVersion`
  编码（**可离线单测是资产**，注进去就废）、单 encoder / pass 内不建资源（引擎不变量，注入只会把 UB 换成崩）。
- **顺带结掉的两处文档债**：`02-OVERVIEW.md:101`「资源包覆盖机制已验证成立」与同日 `evidence/p4x4a` 的
  `verdict=REJECTED` 冲突 ⇒ 以 REJECTED 为准；`04-SPEC` M-01 行「零 mixin 无法达成」滞后于 `07:84` 已撤回的推论
  ⇒ 本轮改为「候选下线」。
- **测试结果**：`./gradlew test --offline --tests "*VulkanEvidenceDisciplineTest" --tests "*MixinWiringTest"`
  ⇒ `VulkanEvidenceDisciplineTest` **4 项 / 0 失败**、`MixinWiringTest` **9 项 / 0 失败**（本轮只动文档，
  两份文档守卫是唯一的措辞耦合点）。零 `src/` 改动 ⇒ 未跑全量。
- **下一步**：按 `19` §5A.4 的执行序起跑 **R0（注释口径同步）→ R3（删反射族 M-07…M-10，纯删码）**；
  🎯 M-04（R7）已按用户裁决排为下一轮目标，但**开工第一步是交牵连面清单**，不是写注入。



---

## 2026-10-12（一百一十三）— ✅ CI 连红 6 次的因拔掉（`libs/` 被 ignore 掉）；🔴 顺带撤回 GAP-033「条形云新臂」（那四张是 Vitrail 参考图，不是本仓症状图）

> **verdict = 复查 + 构建修复轮，零 `src/` 改动**：用户问「这六次提交做了什么、为什么构建全红」。
> 红是**一个**根因，与这六次的业务内容无关（连只改文档的 #153 也红在同一处）；
> 核对图证时另撞出一格结论站不住，按用户裁决「降级：图证不成立、待重判」处理。
> `./gradlew build --offline` 退出码 **0**，`build/test-results` 实测 **1165 项 / 0 失败 / 1 skip**；未抬任何棘轮基线。

- **CI 从 #148 红到 #153 的因（一条，代码级）**：`2a8ceb8` 把 A2 差分探针的依赖写成
  `build.gradle:327` `testImplementation files('libs/jcpp-1.4.14.jar', 'libs/slf4j-api-1.7.12.jar', 'libs/java-semver-0.8.0.jar')`，
  而**同一个提交**在 `.gitignore` 末尾加了 `/libs/`（`git ls-files libs/` = 0）。
  `files()` 不查任何仓库、不做解析 ⇒ 干净检出没有 `libs/`，入库的
  `JcppDifferentialProbeTest.java:11-16` 那 7 行 `import org.anarres.cpp.*` 就编不过
  ⇒ **红在 `compileTestJava`，`test` 任务根本没跑到**（旁证：6 次运行总时长 48~59s，而本机全量测试单跑就要 1 分钟以上）。
- **实测复现（不是推理）**：把本机 `libs/` 改名模拟干净检出，`compileTestJava` 报
  「程序包 org.anarres.cpp 不存在」×7 + 符号找不到 ×12，退出码 1；改回即绿。
  另试了 `git worktree` 走完整冷构建，但本机出网拦 neoforged maven ⇒ 卡在 `createMinecraftArtifacts`，到不了编译测试源码那步（所以用前一种办法）。
- **修法（用户选方案 1：入库）**：`.gitignore` 删掉 `/libs/`、在「必须入库」清单里补一条说明；
  三个 jar 连同新增的 `libs/README.md` 一起入库；`build.gradle` 那条注释补上「为什么必须入库 + 不入库的代价」。
  `libs/README.md` 记：坐标 / 许可证（jcpp **Apache-2.0**、slf4j-api 与 java-semver **MIT**，
  前两个从 jar 内嵌 POM 核出、jcpp 依 `07-CONSTRAINTS` §423 的既有核实）/ SHA-256 / 上游 / 重新获取命令，
  并写明**未修改上游构件** ⇒ `07-CONSTRAINTS` §1.3 的「声明改动」义务不适用；
  作用域是 `testImplementation` ⇒ 不进发布 jar、运行期零依赖（§480 那条自检针对分发产物）。
  🔖 顺带核实了"为什么要三个"：jcpp 的 `Preprocessor`/`MacroTokenSource` 里有 `org.slf4j.Logger` 字段、
  `BuildMetadata` 引用 `com.github.zafarkhaja.semver` ⇒ 少一个就 `NoClassDefFoundError`。
- **为什么本机九十多轮看不出来（同族第二次，和上一轮「代码默认档没跑过」一个形状）**：
  增量构建命中 `compileTestJava UP-TO-DATE` + jar 就在本地磁盘上。这六次的提交信息每条都写
  「`./gradlew build` 退出码 0，1164/1165 项 0 失败」——**在本机口径下句句为真，换台干净机器不成立**。
  🔖 纪律补一条：「build 绿」要在**干净检出**上取，本机增量不算。
- **🔴 GAP-033「条形云新臂」撤回（用户核对后）**：上一轮据以判「夜里复现」的四张图，
  实为 **Vitrail 上跑 BSL 的正常画面**，不是本仓 runClient 的症状图。⇒ 那格的图证不成立，
  「新臂三项写全」降级为**参数写全、画面未证**；连带作废的还有「月亮是亮方块 ⇒ `gbuffers_skytextured` 未接」
  这条推断（那颗方块月出自 Vitrail）。**不依赖那四张、仍然保留**的两格：① `depthGlProxy=true` 把天空碎成
  三块硬边楔形（本轮在 vkdisp 上单变量翻档当场看到，图未落盘）；② **GAP-037 是代码级结论**
  （`SkyPackGbuffer#skyVertexBuffer` 的 `z=1.0` × `DepthGlProxy:71-72`/`:129` 的 `1−z_en` = 天空被翻第二次），不靠画面。
- **改名**：`docs/gap033-{day-clouds-ok-102859,sunset-clouds-ok-102622,strip-clouds-night-102712,strip-clouds-midnight-102759}.png`
  → `docs/Vitrail Shaders下运行的BSL-{正午,日落,夜里,午夜}.png`（与既有金标准 `Vitrail Shaders下运行的BSL.png` 同族），
  并登记进 `docs/08-TESTING.md` §10.1 作**参考面**。🔴 方法论约束写进那张表：Vitrail 的夜里/午夜**本来就是**一排贴地平线的带状云
  ⇒ 别把这种形态当症状追根因，要重开 GAP-033 先做本仓夜里实拍、固定机位两两对照。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-033 那格拆成「图证更正」+「仍成立的两格」两行、GAP-037 溯源行加一句不受撤回影响）、
  `docs/08-TESTING.md` §10.1（参考图 4 行 + 用法 1 行）、`.gitignore`、`build.gradle`、新增 `libs/README.md`、本文件。
- 🔖 **同轮补记（用户处置）**：`docs/vkdisp对照Vitrail-BSL-h48.png`（h48 那张左右分带对照图）**特意删掉、不再入库**。
  `08-TESTING.md` §10.1 那行改标为已删 + 留 `tools/vulkan-local/compare_images.py` 的重出入口；
  🔴 但它读出来的**五条差距表**（天空撕裂白条 / 无阴影层次 / 水未接 / 树叶糊成贴片 / 整体偏亮 `mean_luma 137.6`）
  与那条「对照图只回答有没有做、不回答像不像」的判据边界**保留** —— 图没了不等于结论没了，别连表一起删。
- **测试结果**：`./gradlew build --offline` 退出码 0；1165 项 / 0 失败 / 1 skip（从 `build/test-results` 数的，不是手抄）。
  🔴 **CI 是否转绿只能推一次看**：本机跑不出干净检出构建（网络），所以本轮的「绿」在本机、不在 CI 上取证。
- **是否已提交**：✅ 提交并推送（本轮结束即推，用户口径；推送同时就是 CI 的那次实测）。

---

## 2026-10-12（一百一十二）— 🟡 半开档定性完成；**翻默认试了、被自家取证挡回来**（坏包 + 包路线 = 崩帧）

> **verdict = 取证轮（Windows / RTX 4060 / Vulkan，四档冷启动）**：用户裁决「先补默认档取证再翻」+「顺手判半开档是有意的 A/B 档还是缺配对守卫」。
> 两件事都办了；翻默认**没翻成**，而且是被本轮自己的取证挡的。
> `./gradlew build` 退出码 **0**（1165 项 0 失败）；未抬任何棘轮基线。

- **半开档（`mrt.terrain=true` 而 `packTerrainShader=false`）判完了，答案是「注释在撒谎」**：
  探针读数 = `main` **allZero=true**，同一帧 `c0@writeGen` `mean_luma 59~71 / nonBlack=100%`
  ⇒ 本仓探针自己定义的 **NOT_ON_MAIN**；且 `fullscreen pass failed` **0 条** = **不是崩，是没人写主目标**。
  而 `VkDispConfig` 那条注释写的是「开启后地形被画两遍，主目标不受影响，**屏幕画面不变**」——
  与这组读数直接矛盾。⇒ 它不是「有意的 A/B 档」，是**过期的诊断档 + 缺一道点名**。
- **处置（保留可用 + 不许静默，本仓 X11 口径）**：`FullscreenPassHook` 在 `[route]` 自报同一处加
  `[GAP-036/半开档]` WARN 一次；`VkDispConfig` 两条键的注释改成实测口径（成对开、成对关，半开=全黑）。
  守卫 `MrtTerrainPassWiringTest#diagnosticIsOffByDefault`（前提就是那句错注释）重写为
  `terrainKeysShareOneDefault`：钉**两条键同默认值** + **半开必须点名**，而不是钉「默认必须 false」。
- **翻默认的资格证明（三档，全部删空 `vkdisp-client.toml` 冷启动，不再只删单个键）**：
  ① **旧默认（两条都关）** = 地形在、**天空整片黑**（白天 `dayTime 3317` 实测），
  且整份日志 `[GAP-027/sky]` 自报 **0 行** ⇒ 一百一十轮交付的包天空**一次都没跑过**。
  ② **两条都开 + BSL** = `[route] chain=true`、包天空自报在场、`fullscreen pass failed` 0 条，
  同机位白天 = 蓝天 + 日晕 + 地形与积雪 + AO ⇒ **好包档合格**，这一步本可以翻。
  ③ 🔴 **两条都开 + Complementary**（其 `world1/gbuffers_*` 全部编译失败）= **客户端直接死**：
  `MrtTerrainPass.drawTerrain:518` 的 `renderGroup` 抛
  `IllegalStateException: Missing uniform VkDispBuiltins (should be UNIFORM_BUFFER)`，
  而它在**帧图 pass 体内**执行、无人接 ⇒ `ReportedException: Render Frame`。
  旧默认档下同一个包只是「没地形没光影」，**不崩** ⇒ 翻默认等于给坏包加一条崩溃路径。
  **两条默认已退回 `false`**，本轮不改产品默认。
- **顺带把 GAP-036 ③ 那两次 `nvoglv64` 原生崩溃读正了**：`hs_err_pid37420.log` 的栈里**有我方帧**
  （`SkyPackGbuffer.render ← SkyIntoGbuffer.render ← MrtTerrainPass$$Lambda`）
  ⇒ ③ 与本轮 ③ 可能同源，真正缺的是**帧图 pass 体内那道异常兜底**；上一轮我把它记成「驱动问题、非本轮引入」是**低估**了它。
- **影响的文档**：`docs/13-GAP-REGISTRY.md` GAP-036 追加「半开档定性 + 翻默认资格证明 + 守卫」三行、本文件。
- **下一步（顺序即优先级）**：① 给帧图 pass 体加异常兜底 + 坏包时**不接管**（回到原版地形），
  这是翻默认的唯一阻塞项；② 解开后重跑本轮这三档再翻；③ `packWater` 档单跑复现那两次原生崩溃。
- **是否已提交**：✅ 提交并推送（本轮结束即推，用户口径）。

---

## 2026-10-11（一百一十一）— 🔴 用户自跑 runClient 暴露：**代码默认档每帧抛在旧三步链 Pass 3** ⇒ 地形不见 + 光影不生效（已修 + 真机复判）

> **verdict = 修根因轮（Windows / RTX 4060 / Vulkan，真机两档复判）**：上一轮的「天空实现完成」在**用户自己的 runClient 里不成立**——
> 画面只有天空与日月方块、**地形一整层不见**、光影没生效。本轮把这一条判到代码级并修掉。
> `./gradlew build` 退出码 **0**（**1165 项 0 失败**，新增 1 项守卫）；未抬任何棘轮基线。

- 🔑 **根因（四步，全部可跳至代码）**：`mrt.terrain` 与 `mrt.packTerrainShader` 的**代码默认都是 `false`**
  （`VkDispConfig:184` / `:226`）⇒ `FrameApi.isPostChainActive()` 恒 `false`（`FrameApi:1134-1141`）
  ⇒ `FullscreenPassHook:232` 走**旧三步链** `FrameApi.drawFullscreen`；其 Pass 3 经 `FrameApi.chainResolver`
  （`FrameApi:1408-1410`）**无条件**解析 `shadowtex*` → `ShadowStubs.depthView()`，而该视图**刻意不懒建**
  （懒建会在 pass 内新建 encoder）；`ShadowStubs.init()` 原先**只有一个调用点** =
  `MrtTerrainPass#ensureShadowStubs`（`MrtTerrainPass:868`）= `mrt.terrain=true` 那一档。
  ⇒ 默认档每帧 `IllegalStateException` 抛在 Pass 3，而**把 scene 落到主目标的是它后面的 Pass 4** ⇒
  「抛在半路」不是无害，它等价于「这一帧什么都没画」。栈顶实证：
  `ShadowStubs.depthView(106) ← FrameApi.chainResolver(1409) ← PackSamplerViews.resolve(90) ← setPackSamplerUniforms(171) ← FrameApi.drawFullscreen(963) ← FullscreenPassHook.onAfterLevel(232)`。
- **后果的可数部分**：用户那一次约 5 分钟 runClient = **64 959 条同形栈 / 154 MB `latest.log`**。
- **归因是逐档排出来的，不是推的**（同一份配置、真机热改、每档截图）：
  `mixin.wireTerrain=false` ⇒ 画面不变（排除「派生地形管线把地形画没了」这一族）；
  `skyPass=false` + `skyPackShader=false` ⇒ 画面不变（排除天空那一族）；
  `enabled=false` ⇒ **地形立刻正常** ⇒ 责任在本 mod，收敛到上面那四步。
- **修法**：`FullscreenPassHook.onAfterLevel` 帧首（`try {` 第一句、任何 pass 打开之前）调 `ShadowStubs.init()`，
  `ShadowStubs` 类与 `init()` 改 `public`。幂等（`ensure` 早退）⇒ 默认档每帧只多一次引用判断。
  🔖 **没有动 `FrameApi`**：那一版把 init 放进 `drawFullscreen` 会撞 QD-10 棘轮（上限 1675 行、正压着），
  且语义上「帧首备好资源」本就属于 hook 而不是某一条链。
- **真机复判（两档）**：① **用户的原配置原样冷启动**（`terrain=false` / `packTerrainShader=false` / BSL）⇒
  `fullscreen pass failed` **0 条**（原 64 959）、`[GAP-003] shadow stubs ready (1x1 D32@1.0 + RGBA8@0)` 1 条、
  `[route] chain=false ⇒ 旧三步链`，**地形回到画面**（夜里的山谷/水面/树影可见）。
  ② 同一次运行里热改 `mrt.terrain=true` + `mrt.packTerrainShader=true` ⇒ `[route] chain=true`、
  `[GAP-027/sky] 天空改由包的 gbuffers_skybasic 画` + `天空全屏缓冲已建: stride=192 attributes=12 z=1.0`，
  画面是**真正的 BSL 夜景**（月晕 + 深蓝绿天空渐变 + 树上积雪剪影）。
- **本轮最不好看的一条（写下来，不是遮）**：上一轮的「冷启动走代码默认值」只删了 `skyPackShader` **一个键**，
  其余 `terrain` / `packTerrainShader` / `packWater` / `depthGlProxy` 仍是手写取证档 ⇒
  **代码默认档这条路径九十多轮从未被执行过**。实证：本轮默认档整份日志里 `[GAP-027/sky]` 自报 **0 行**，
  即上一轮交付的包天空在默认档**一次都不跑**。以后凡宣称「默认如何」，必须把相关键**整批删掉**再冷启动。
- **影响的文档**：`docs/13-GAP-REGISTRY.md` 新增 **GAP-036**（含本轮没修的三格）、本文件。
- **仍未收口（如实记，本条不关）**：① 两条 gbuffer 路线开关的**默认值**是否翻成开 —— 待用户裁决（翻完才是「开箱即光影」）；
  ② `mrt.terrain=true` 而 `packTerrainShader=false` 的**半开组合 = 全黑**，是有意 A/B 档还是缺配对守卫，未裁决；
  ③ 重载期 `nvoglv64.dll` 原生崩溃：`hs_err_pid36000.log`（20:46，**本轮改动前**）与 `hs_err_pid37420.log`
  （23:24，紧随打开 `mrt.packWater`）同形 ⇒ 非本轮引入，但 `packWater` 档要单独复跑。
- **是否已提交**：✅ 提交并推送（本轮结束即推，用户口径）。

---

## 2026-10-11（一百一十）— ✅ 天空实现完成：包的 `gbuffers_skybasic` 成为 gbuffer 天空的**默认档**（白天/夜里两档真机复判通过）

> **verdict = 代码轮 + 真机复判（Windows / RTX 4060）**：上一轮「接线通了但夜里不对」的那一格判掉了。
> 冷启动**走代码默认值**（`run/config/vkdisp-client.toml` 里 `skyPackShader` 这一行**删掉**）⇒
> 自报「天空改由包的 gbuffers_skybasic 画」+「天空全屏缓冲已建: stride=192 attributes=12 z=1.0(远平面)」；
> `Missing uniform` / `Couldn't compile pipeline` / 「回到原版重放」**各 0 条**，
> `c0@afterSky` SKY_BAND `nonBlack=100.000%`，`main` 的 `allZero` 在 6650 次读数里 4 次（0.06%，首帧族）。
> `./gradlew build` 退出码 **0**（**1164 项 0 失败**）。

- 🔑 **夜里那两格（无星 + 天顶环）的真因 = `gl_FragCoord.z`**：链那一族的全屏缓冲把 `Position` 写成
  `(x, y, 0.0, 1.0)`（`FrameApi:661`），恒等 MVP 下 ⇒ 设备深度 `gl_FragCoord.z = 0`。
  链各级**不读**它 ⇒ 一直无害；但包的 skybasic **读**它当屏幕深度
  （`gbuffers_skybasic.glsl:84-87` 过 `gbufferProjectionInverse` 反解 `viewPos`），
  GL 口径下 `z=0` 是**近平面** ⇒ `viewPos` 长度只有 ~near ⇒
  `DrawStars` 的噪声坐标 `viewPos.xz/256 + viewPos.y/128` 对**所有**像素塌成同一个纹素 ⇒ 整片无星。
  **修法**：`SkyPackGbuffer` 自带一份 `Position.z = 1.0`（远平面）的全屏缓冲，
  **不改 `FrameApi`、不改链那一族的任何一档**（那条缓冲是链各级的既成判据，动它等于跨档改判据）。
- **默认值翻成开**：`mrt.skyPackShader` `false → true`，字段注释补两档复判证据与回退条件。
  🔖 翻默认前先把「退回是不是静默」量了一遍：故意用探针包把天空片元写坏 ⇒ 日志一行
  「派生天空管线建立失败 ⇒ 回到原版重放」、画面回到上一轮定案的原版档、**不是黑屏**
  ⇒ T11 的「不静默换绑」成立，这一条是翻默认的资格证明而不是注释里的一句话。
- **画面判据（同机位 `x −66.36 / y 107.7 / z −74.81`、`yaw −33.75`）**：
  `pitch −25 / 正午` = 蓝色渐变天空 + 体积云 + 雪原/水面正常，**没有** GAP-033 的放射指状条带；
  `pitch −80 / 夜里` = 深蓝绿夜空 + **星点在场** + 地形剪影；`pitch −85 / 正午天顶` = 包的蓝色渐变（原版档是平坦天顶色）。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-027 天空行：状态从「画面未达成」改成本轮收口）、本文件。
- **仍未收口的（如实记，不并入本条）**：① `gbuffers_skytextured`（日月盘）仍没接 —— 它的顶点要 `UV0`+`Color`，
  而 `RenderPipelines.CELESTIAL` 的几何是 `POSITION_TEX`（无 Color）⇒ 属性欠供，另案；
  ② `renderStage` 逐 draw 未接（GAP-028）⇒ 天空只有「天盘一次」的语义，包按阶段分批评星/月的写法在这一档不存在；
  ③ 夜里天顶仍有一道**软带**（比修前轻，且不再是硬环）—— 是否 BSL 自身的夜空渐变未判，别当已修读。
- **是否已提交**：✅ 提交并推送（本轮结束即推，用户口径）。

---
## 2026-10-11（一百零九）— 🧱 包的 `gbuffers_skybasic` 真的在画了（零错误、零 mixin），但当前写进 colortex0 的是**黑** ⇒ 默认仍关，本条不关

> **verdict = 代码轮 + 真机取证（Windows / RTX 4060），🔴 功能未达成**：接线全通、`Missing uniform`
> 与 `Couldn't compile pipeline` **各 0 条**，画面也确实换成了包算的那一版（不再是原版平面蓝），
> 但判据数字说清楚它还不对：`c0@afterSky` 的 SKY_BAND = **`(0,0,0) allZero=true`**
> （对照：上一轮原版重放档同一取点 = `(128,160,255) nonBlack=100%`）。
> ⇒ **`mrt.skyPackShader` 的代码默认保持 `false`**，产品画面仍是一百零七定案的那一版。

- **落了多少**（5 个文件 + 1 个新类，全部 `./gradlew build` 退出码 0 / **1164 项 0 失败**）：
  1. **`pack/GbufferVertexSources`（新类）** —— 「一条 gbuffer 程序的顶点源从哪来」的唯一出口。
     建它的直接原因是 `ClassLineRatchetTest`：`VkDispVirtualPack` 正好压在 1573 上限，
     把天空的接线直加进去 = 1590 被拦 ⇒ 按棘轮的裁决落到独立类（QD-10 方向）。
     顺手把 `generateGbufferAdapter` 从虚拟包搬进来（虚拟包回到 1551）。
  2. **`PackTerrainSource`**：`SKY_PROGRAM` + post 顶点路线（复用 `PostVertexLinker`，
     配不上对就**整条不接线**，不留「片元是包的、顶点不是」的半接线）。
  3. **`VkDispConfig`**：新闸门 `mrt.skyPackShader`（默认**关**）+ 把上一条 `mrt.skyPass` 的
     默认翻成开（一百零七的定案落地；本轮一度被 `git checkout` 连带回退，已恢复并复验）。
  4. **`VkDispConfigHotReload`**：`skyPackShader` 进**边沿判定**与自报行 —— 生成期只读一次的开关
     不进这里就是个死开关（QD-08「写了但没生效」那一族，与 `packWater` 同例）。
  5. **`bridge/SkyPackGbuffer`（新类）** + `SkyIntoGbuffer` 的互斥挂点 + `TerrainPipelineApi`
     按程序分族供矩阵（见下）。零 mixin。
- **三条实测掉出来的事实**（都是「不量就会猜错」的那一类）：
  1. 🔴 **`getCompiledPipeline` 抛的「Failed to find or load pipeline」是措辞陷阱**：它内部走
     `PipelineCache.get` = **未命中就地编译**，所以回 null 的真实含义是**编译失败**，不是「没注册」。
     照字面读会去做「注册管线」那件根本不需要的事。真因在 renderpearl 自己的 ERROR 行里：
     `vertex shader ... attrib (Position) does not have a matching vertex buffer element`
     ⇒ 缺 `.withVertexBinding(0, POST_VERTEX_FORMAT)`（链各级同一条事实，见其注释）。
  2. 🔴 **`noisetex` 少绑一条**：`PackTextures.ensureReady()` 此前只在**地形 pass 内部**调，
     而天空跑在地形**之前** ⇒ 首帧回落 `builtinNoiseView()`、而它在 pass 打开期间建 encoder ⇒ 抛
     （h48y 同一条实测的复刻）。修法照抄地形那条：开 pass 前 `PackTextures.ensureReady()` + `VolumeStubs.init()`。
  3. 🔴 **深度口径的分族判据是「程序拿矩阵干什么」，不是程序名前缀**：`gbuffers_skybasic` 名字在
     gbuffer 族，但它的片元从 `gl_FragCoord` 反解 `viewPos` ⇒ 与链同形。按 `gbuffers_*` 一律喂引擎口径
     时它算出来是黑的 ⇒ `GbufferVertexSources.builtinsFamily()` 让屏幕空间那几条走 `Family.CHAIN`。
     **改完仍然黑**（见上面 verdict 的数字）⇒ 这一条是必要条件、不是充分条件。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-027 天空行新增「已接线但画面未达成」一条）、本文件。
- 🔴 **本轮把「包天空写 0」判到了底，并且推翻了自己上一版的结论**（三步，每步都有数）：
  1. **先否证「颜色数学坏」**：把探针包的 skybasic 片元改成**常数色** `albedo = vec3(1.0, 0.0, 0.5)`
     （`alpha` 保持包原值 = 单变量）。真机 `c0@afterSky` SKY_BAND 仍是 **`(0,0,0) allZero=true`**
     ⇒ 坏在**写入通路**，不在数学。🔖 顺带否证了我上一版写进本文件的「因 = `skyColor` 没供」——
     那条**作废**：`skyColor` 只在 `#ifdef SKY_VANILLA` 分支里被读，而 BSL 把它注释掉了
     （`lib/settings.glsl:411`），默认档走的是 `#else` 的 `SKY_R/G/B` 常量。
     **教训形态**：从「包里有这个 uniform + 我方没供」直接跳到「所以天空是黑的」，中间没量过。
  2. **再否证「深度附件挡了片元」**：先加显式 `DepthStencilState(ALWAYS_PASS, write=false)` ⇒ 仍 `(0,0,0)`；
     再把那条 pass 的深度附件改成 **null** ⇒ 客户端**以 exit 1 直接死掉**（日志最后一行就是本类的
     builtins 上传）。⇒ 深度附件既不是因、也不能省（记进 `SkyPackGbuffer` 的 `@param depth`）。
  3. **真因 = `ftransform()` 的展开档位按「程序名前缀」选，而不是按「程序拿矩阵干什么」**：
     `ShaderPackCompiler:195` 原来是 `postIdentity = program.stage().isPostChain()`
     ⇒ `gbuffers_skybasic` 落进**地形族**，顶点被展开成
     `gl_Position = gbufferProjection * gbufferModelView * vec4(Position, 1.0)`
     —— 而我方给它的是 **NDC 全屏三角形** ⇒ 再乘一次相机矩阵 = 退化到零面积 ⇒ **一个片元都不落**。
     这条 GAP-030 的注释自己就写着「与地形族同一条展开式会把全屏 quad 按相机矩阵拧掉」，
     只是判据没覆盖到「名字在 gbuffer 族、几何却是屏幕空间」的这一条。
- ✅ **修法 + 复判**：`postIdentity = isPostChain() || GbufferVertexSources.postVertexRoute(program)`
  （与 `gbufferProjection` 的供值分族**同一个判据**，两处不再各说各话）。
  离线守卫：`PackTerrainSourceTest` 新增断言「配对后的天空顶点里不许出现 `gbufferProjection *` 与
  `gbufferModelView *`」—— 它先**红**（抓到真因）再绿，是这条修法的验收仪器。
  真机复判（常数色档）：`c0@afterSky` SKY_BAND = **`(255.0000, 0.0000, 127.0000)`**
  = 探针常数 ×255 **逐位吻合**、`nonBlack=100.000%` ⇒ **写入通路打通**。
  换回真包 BSL：`c0@afterSky` SKY_BAND = `(58.12, 73.71, 83.78)`（蓝主导、非平坦）
  ⇒ colortex0 里的天空已经是**包算的那一版**；正午画面 = 包的天空渐变 + 日 glare，
  与原版档的平面 `(128,160,255)` 明显不同。
- **未解决 / 下一刀**：① 🔴 **夜里那一档还不对**（`time set 18000`、`pitch −85`）：天顶出现一圈
  **明显的环状不连续**，且**看不到星**（BSL 的 `STARS` 默认开、`skybasic.glsl:100` 的 `DrawStars`
  应当画）。候选按信息量排：(a) `gl_FragCoord.z` 在恒等 MVP 下 = **0.5**（顶点写 `vec4(Position,1)`、
  `Position.z=0`），而包把它当屏幕深度用（`screenPos.z`）⇒ 反解出的 `viewPos` 距离固定，
  `GetSkyColor`/`DrawStars` 里任何依赖 `length(viewPos)` 或未归一化 `viewPos` 的分支都会走错；
  (b) `moonVisibility` 依赖的 `sunVec` 是否真从顶点供到（本轮 `PostVertexLinker` 的
  `synthesizedVaryings` 要读出来）；(c) 环 = 地平线处的分支边界 ⇒ 与 (a) 同源。
  **判据 = 还是那件探针仪器**（常数色已证明它一票能判）：把 `albedo` 换成
  `vec3(float(moonVisibility), length(viewPos)/1000.0, nViewPos.y)` 一跑就能同时判 (a)(b)(c)。
  ② `renderStage` 逐 draw 未接（GAP-028）⇒ 本档只有「天盘一次」的语义；
  ③ `gbuffers_skytextured`（日月盘）走不通：`RenderPipelines.CELESTIAL` 的几何是 `POSITION_TEX`，
  该程序顶点要 `UV0`+`Color` ⇒ 属性欠供，另案。
  🔖 **因此 `mrt.skyPackShader` 的代码默认仍是 `false`** —— 白天已经对了，但夜里不能比原版档差。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径：每轮开发结束都提交推送，不再等点头）。
  （`PipelineApi`/`TerrainPipelineApi` 各只动了 1 行，且**没抬任何一条棘轮基线**：
  PipelineApi 1163/1163、TerrainPipelineApi 1131/1131、VkDispVirtualPack 1551/1573）。

---
## 2026-10-11（一百零八）— 🧱 开始接包的 `gbuffers_skybasic`：先证明「全屏能替掉天空穹顶」，再被巨类棘轮拦下（未抬基线，已回退）

> **verdict = 代码轮，🔴 零行为改动**：生产路径今天**没有**任何调用点走 `SKY_PROGRAM` ⇒ 画面与
> 上一轮逐字相同。`./gradlew build` 退出码 **0**（**1164 项 0 失败**，1 skip）。
> 这一轮买到的东西是**一条被实测的证明**，不是「天空接好了」。

- **为什么先做证明而不是先画**：登记在 GAP-027 的可行性评估里，「天空穹顶几何是私有的、
  `SkyRenderer.render` 内部自建 render pass ⇒ 我方绑不进 uniform」这两条决定了
  **零 mixin 的路线只有一条**：把天空当成**一次全屏 draw**，用包的 skybasic 片元。
  而它成立的前提是可证伪的：包的天空片元必须是**屏幕空间**的、且顶点不读几何属性。
- ✅ **证明（逐行取自包 + 一条离线测试）**：`program/gbuffers_skybasic.glsl:84-87` 用
  `gl_FragCoord.xy / vec2(viewWidth, viewHeight)` + `gl_FragCoord.z` 过 `gbufferProjectionInverse`
  反解 `viewPos`（方向随后被归一化 ⇒ 穹顶与全屏对同一像素算出同一方向）；该程序的顶点
  （`:148-181`）**不声明任何 attribute**，只有 `ftransform()` + 三条 `out`（`sunVec`/`upVec`/`alpha`）
  ⇒ 它与后处理链那一族**同形**。新测试
  `PackTerrainSourceTest#skybasicUsesPostVertexRouteAndNeedsOnlyOneAttachment` 跑真实 BSL 库存钉住：
  契约 = **1 个输出、槽 [0]**（决定天空 pass 的附件数，与地形 1 / 水 2 各自独立）、
  post 顶点配对**成功**、片元要的每条 varying 在配对后的顶点终稿里都有同名 `out`、
  统一后的块名仍是 `VkDispBuiltins`。
- **本次改了什么**（2 个文件，都不在渲染热路径上）：
  1. `pack/PackTerrainSource`：新增 `SKY_PROGRAM` 常量 + `postVertexProgram()` 判定 +
     `linkPostVertexRoute()`（取**同一条程序**的顶点终稿交给 `glsl/translate/PostVertexLinker`，
     与链那一族同一份实现、同一顺序：**契约从未改写的片元解析、落进资源的是改写后的那一份**）。
     `Result` 因此多两个字段（`linkedFragment` / `vertexSource`）+ `postVertexRoute()`。
     🔴 **配不上对就整条不接线**，不退回 chunk 适配层 —— 那正是本项目反复付学费的「半接线」
     （片元是包的、顶点不是 = 链接期才炸）。顶点终稿**不许跨维度凑**（照 `PackPostChain.selectVertex` 的纪律）。
  2. 上面那条测试。
- ⛔ **卡点（如实记，别读成「做完了」）**：把 `gbuffers_skybasic` 落进
  `VkDispVirtualPack.GBUFFER_PROGRAMS` 那一步（表项 + `currentTerrainMemoKey()` 进键 +
  `realizeGbufferProgram` 取 post 顶点对）我先按直白写法实现过一版，被
  `ClassLineRatchetTest` 当场拦下：**`VkDispVirtualPack.java` 1590 > 上限 1573**
  （该类本来就压在棘轮上限上）。棘轮的裁决是「新增内容请落到独立类里（QD-10 方向）」，
  ⇒ **已整段回退，未抬基线**（`git checkout` 后该文件回到 1573）。
  下一步的第一刀因此不是天空，而是**把 gbuffer 程序注册表从虚拟包里拆出去**
  （`GbufferArtifacts` + `buildGbufferPrograms` + `memoKeyFor` 落一个独立类），拆完才有地方放天空。
- **顺带钉住的一条设计约束（写在这里以免下一轮又走弯路）**：天空那条 pass 只有 **1 个颜色目标**
  ⇒ 它**不进** `MrtPlan` 的冻结集（`packProgramNames` 保持 `[terrain, water]`）。
  把它并进去会把地形 pass 的附件数从 1 抬到 2，而 `MrtTerrainPass` 的附件数与派生管线必须逐位相等
  （不等是 `setPipeline` 抛 = 崩客户端）。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-027 天空行新增「可行性已证 + 卡点」一条）。
- **未解决 / 存疑**：① 绘制那一半（管线 + pass + 绑定 + 与原版重放互斥）未做；
  ② `alpha` 恒 1.0 是因为 `renderStage` 逐 draw 供值未接（GAP-028），本档只画一次所以正好是
  天空盘该用的值，但**原版那种「天盘/星/暗盘分三次 draw」的语义在这一档不存在**；
  ③ `gbuffers_skytextured`（日月盘）走不通：`RenderPipelines.CELESTIAL` 的几何是
  `POSITION_TEX`，而该程序的顶点要 `UV0`+`Color` ⇒ 属性欠供，另案。
- **是否已提交**：⏳ 未提交（等用户点头）。改动面 = `pack/PackTerrainSource.java`、
  `PackTerrainSourceTest.java`、`CHANGE_LOG.md`、`docs/13-GAP-REGISTRY.md`。

---
## 2026-10-11（一百零七）— 🎯 真机定案「着色器云渲染错误」：主因是**天空没进 gbuffer**（`mrt.skyPass` 默认关）⇒ 翻成开；并撤回「`cloudHeight` 供 0 = 长条云根因」

> **verdict = 代码轮 + 真机复判（Windows / RTX 4060）**：用户报的那一格**不是云的画法坏**。
> BSL v10.1.8 把 `SKY_DEFERRED` **注释掉了**（`lib/settings.glsl:163`）⇒ 链里 `deferred1.glsl:473`
> 那支 `#if defined OVERWORLD && defined SKY_DEFERRED` 根本不参与编译 ⇒ **天空只能来自 gbuffer**，
> 而我方 `mrt.skyPass` 一直默认关 ⇒ 包的体积云画在**纯黑底**上 = 「黑天空 + 一团长条白」。
> 同一进程内热翻这一个开关：关 = `c0@afterTerrain` 的 SKY_BAND 逐字 `(0,0,0) allZero=true`；
> 开 = `c0@afterSky` SKY_BAND `(128.0000, 160.0000, 255.0000) mean_luma=160.0558 nonBlack=100.000%`，
> 且 `[GAP-003/sky]` 自报的 `skyColor=(0.502, 0.628, 1.000)` ×255 与它**逐位吻合**（两个独立仪器同一数）。
> 画面侧 `pitch −5 / −45 / −85` 三档：蓝天 + 连贯白云、正上方有云层、地形边缘无重影。
> `./gradlew build` 退出码 **0**（**1163 项 0 失败**，1 项 skip）。

- **本次改了什么**（3 个文件，全是行为面一处 + 两处措辞更正）：
  1. **`VkDispConfig`：`mrt.skyPass` 默认 `false` → `true`**，字段注释重写（为什么翻、判据数字、
     买到什么/没买到什么、回退条件）。🔴 顺带删掉注释里那句失效的「**需 `terrainAfterLevel=true`**」——
     两档挂点都接（帧图档 `MrtTerrainPass:205-215` 用 `FramePass#requires`，AfterLevel 档
     `FullscreenPassHook:296`），本轮取证跑的就是帧图档（`terrainAfterLevel=false`）。
     当年留默认关的唯一理由是 h48e 那批「开天空 ⇒ 链输出恒 0」，**h48k（2026-10-07）已撤销**
     （数字取自第 180~300 帧，没过预热闸门）；本轮把它留的「还开着的那一条」判据补齐，并在真机第二次否证：
     `main`（area=FULL）**1289** 次读数里 `allZero` **0 次**、`mean_luma=158.1`。
  2. **`OfUniformManager` 的 `cloudHeight` 供值注释 + `OfUniformManagerTest` 的守卫措辞**：撤回
     「恒 0 ⇒ 云层被压到 y=0 ⇒ 真机长条云」那条因果。**取法改对仍然对**（`LevelRenderState.cloudHeight`
     在 `LevelExtractor:223` 之前恒 0，与 `probeAngle` 同一条教训），但因果不成立：包里那两支消费者
     （`atmospherics/clouds.glsl:329` 的 `lowerY`、`lighting/shadows.glsl:308` 的 `cloudLowerY`）
     都坐在 `#ifdef IS_IRIS` 里，而我方 `grep -rn "IS_IRIS" src/main` **零命中** ⇒ 走的是 `#else`
     的硬编码 `192.0` ⇒ 这个 uniform 对包的云几何**当前一个像素都不参与**。守卫保留，但钉的是取法不是症状。
  3. **`GAP-033` 收口为「当前构建在登记机位不复现」**（不是「已修」）：本轮没有任何针对条带的改动，
     只是把两个能解释「看着像条带」的因判掉 —— 边缘副本 = GAP-031 的金字塔 V 翻转、纯黑底 = 本轮这一格。
- **为什么这么改**：这一格是「换包即漂移的硬编码假设」那一族的又一个样本 —— 我方一直按「包会自己算天空」
  留着 skyPass 关，而 BSL 的默认档恰恰**不**自己算天空（`SKY_DEFERRED` 注释掉）。开关的默认值
  当时挂在一个**已被自己文档撤销的**结论上，属于「撤回没落到默认值」的漏网。
- **取证方法（两条会咬人的，都记进了登记表）**：
  1. 🔑 **探针包**：判「包在自己片元里算到的标量」不必改引擎 —— 拷一份 BSL 到
     `run/shaderpacks/<探针包名>/`（本仓的包扫描器认目录形态），在 `deferred1.glsl` 的云混合之后加
     4 行 `color.rgb = vec3(...)` 把标量画成色带（温度计用 `step()` 编码，绕开后续 tonemap 的歧义），
     冷启动跑。本轮一票判掉了「`sunVisibility`/`sunSkyVisibility` 塌了」这条假设（实测都饱和在 1、
     `timeAngle` 落在 [0.2,0.5) 的正确档、`darknessFactor`/`blindFactor` 都是 0）。用完已删。
  2. 🔴 **日时钟每真实分钟走 ~0.1 天** ⇒ 同一机位隔两三分钟的两张图，太阳高度角能差几十度。
     本轮**第一次**判「云是黑的」就是在 `dayTime≈8400~10400`（太阳只差地平线十几度）上做的，
     于是把傍晚的正常暗云读成了 bug。⇒ 判云/天空之前 `time set 6000` 后**立刻**截，并当场读
     `get_time_and_weather` 把 `dayTime` 记进判据。`gamerule doDaylightCycle false` 经 mcpfabric **不可用**（参数被拒）。
- **补测：新默认值是端到端量过的，不是「改了 define 的第二参数就算数」**：把 `run/config/vkdisp-client.toml`
  里 `skyPass` 这一行**整行删掉**再冷启动 ⇒ ① 客户端退出时把这一项按**新默认**写回文件（回读 = `skyPass = true`）；
  ② 正午（`time set 6000` 后立刻截，`dayTime` 当场读）画面 = 蓝天 + 连贯白云；③ 夜里（`time set 18000`）
  不回归 —— 天空按夜色黑、**星空看得见**、月晕在，`main` 的 `allZero` 在 1451 次读数里 1 次（首帧）。
  🔑 夜里这一臂同时是 `[GAP-003/sky]` 那条**观测面自报**的正面样本：`clockTime=18341, skyColor=(0.000, 0.000, 0.000)`
  ⇒ 「`c0@afterSky` 近黑」在这一档是**夜空本来就该黑**，而不是「天空没画进去」—— 这正是 h48k 给这条自报
  立这条判据时想要区分的那两件事。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-019 天空行收口 + GAP-027 **补登记「天空」这一条程序**
  + GAP-029 加更正行并改状态 + GAP-033 收口与撤回）、本文件。代码侧：`VkDispConfig`、
  `OfUniformManager`、`OfUniformManagerTest`。
- **测试结果**：`./gradlew build` **退出码 0**，**1163 项 / 0 失败 / 1 skip**。
  棘轮未抬基线（`VkDispConfig` 与 `OfUniformManager` 都只改注释与一个默认值）。
  真机臂：BSL_v10.1.8、冷启动（避开 GAP-032 的热重载崩）、`optionOverrides=""`、854×480。
- **未解决 / 存疑**：① 🔴 **本条买到的天空是原版 `SkyRenderer` 的颜色，不是包的 `gbuffers_skybasic` 片元**
  ⇒ 包的天空渐变 / 大气 / 日月盘 / 星空仍缺（`GbufferPipelineSwaps` 今天只替换 `RenderPipelines.CLOUDS`），
  已补进 GAP-027 的名单，**不许按本轮已修读**；② 天空仍偏**淡**（原版天顶色 + 包的大气没叠上去），
  与 `docs/Vitrail Shaders下运行的BSL.png` 那张参考图的差距主要就在这；③ **要不要定义 `IS_IRIS`** =
  一次翻掉 `clouds.glsl:269/463/503/585` + `shadows.glsl:307` 多支的兼容性裁决，登记待判，别顺手开；
  ④ `mrt.depthGlProxy` 本轮跑的是 `true` 档（链拿 GL 口径、`Family.GBUFFER` 留引擎口径），
  产品默认该定在哪一档仍未判（GAP-022）。
- **是否已提交**：⏳ 未提交（等用户点头）。改动面 = `src/main/java/dev/vkdisp/VkDispConfig.java`、
  `src/main/java/dev/vkdisp/render/OfUniformManager.java`、
  `src/test/java/dev/vkdisp/render/OfUniformManagerTest.java`、`CHANGE_LOG.md`、`docs/13-GAP-REGISTRY.md`。
  ⚠️ 取证用的 `run/shaderpacks/vkdisp-cloud-probe/` 已删；`run/config/vkdisp-client.toml` 留在
  `skyPass = true` + `shaderPack = "BSL_v10.1.8"`（run/ 不入库）。

---
## 2026-10-10（一百零六）— 🎯 阶梯 **C1**（全屏步 sampler 改由包声明派生 + 按类型路由视图）+ **C3**（`PackConformanceReport` 覆盖 §4.1 全部 10 张名单）；真机两轮取证

> **verdict = 代码轮 + 真机取证**：`./gradlew build` 退出码 0（全套测试绿，新增 10 条）；
> Windows / NVIDIA 真机跑了两轮（C1 一轮、C1+C3 一轮），**编译计数与改前逐位相同**
> （`stages=654 ok=182 failed=472`，与 19:29 / 19:57 / 20:22 / 20:30 / 20:35 / 20:48 六份归档日志同数）
> ⇒ **472 条失败是既态**（来自库存里被扫描的 Complementary/Euphoria，不是本轮引入），
> 且 `Missing uniform` / `Unable to find shader defined uniform` **各 0 条**。
> **本轮走 C 轨**（C0→C1→C3）；A 轨（A0 已由另一条会话线落地，见一百零四）本轮未碰。

- **C1 做了什么**（`19` §4.2 / §4.5，QD-11①）：
  1. **名单来源换根**：新增 `pipeline/model/PackSamplerSuperset` —— 全屏步（composite / deferred / final）
     绑定组的名单由 **OF 命名规则生成**（colortex0..15 / depthtex0..2 / shadowtex0..1 / shadowcolor0 /
     gaux1..4 / noisetex + 已接线的 DH/Voxy 通道），取代「扫 BSL 得到的 18 名」；
     🔖 旧 18 名**全部**仍在新表里（`PackSamplerSupersetTest` 逐条钉），新表另补同族里「这个包恰好没用」的 15 条。
  2. **包声明快照**：`PackCompositeSource` 在生成期对三条全屏步的**转译终稿**跑
     `SamplerDimensionPlan.fromFragmentSource`，装成 `AtomicReference` 快照（兜底路径也重装 ⇒ 不留上一张包的），
     并逐名点名「包声明了超集之外的名字」（`InSampler` 按链侧同口径豁免 —— 这条是单测抓出来的，
     否则每张包都吃一条假告警）。
  3. **视图按类型路由**：新增 `bridge/PackSamplerViews` —— 旧实现只认 `gaux1`，其余 17 名一律 `colorView` 占位，
     于是 `lighttex0/1`（`sampler3D`）被喂 **2D 视图 = Vulkan 静默 UB**、`depthtex*` 被当颜色读；
     现在 3D / 中性材质 / 图集三类按声明类型取对应维度视图，其余**照抄后处理链那条已取证的路**
     （`FrameApi.chainResolver` 由 private 放宽到包内可见，同一个名字在两条链上不会给不同答案）。
     `UNSUPPORTED`（原版 26.3 没有的维度）⇒ **不绑 + 每名点名一次**，不拿错维度凑（与 gbuffer 那条同判据）。
  4. 🔴 **一条如实的边界**：绑定组在**启动期**随 required 管线注册一次定死（`registered≠compiled` 计数断言要求
     无条件注册）⇒ **布局不可能按包变**。所以 C1 的「派生」= 布局用规则生成的超集 + 视图按声明类型选 +
     超集之外的名字点名，**不是**「布局跟着包变」。这条限制已登记为 **`GAP-035`**。
- **C3 做了什么**（`19` §4.4 / §4.5，QD-11④）：新增 `pack/PackConformanceReport` ——
  包生成期把 §4.1 那张表的 **10 张名单逐一**与「包实际声明/引用了什么」对差，产出逐名
  `HIT / PACK_ONLY / ENGINE_ONLY / ASSUMPTION` 判定 + 每单一行的矩阵文本 + 「包有引擎无」的 WARN。
  报告**不改任何行为**，只读各子系统已有真源（超集表 / 快照 / 链 / `UniformCatalog` /
  `BuiltinsBlockLayout` / `LightSpaceList` 公开常量 / `PostVertexLinker.servableAttributes()`），
  **不新建第二份名单**；🔖 表体是 `private` 的（UV 名 / 世界向量黑名单）只报「经哪个公开判定」，不抄内容
  （`19` §5.1 的「可被脚本算出的数字不许手抄」）。为它放宽了两处可见性：`AtmosphereBuiltins` 类改 `public`、
  `GlslDeclarationExtractor.attributeAliasNames()` 新增只读访问器。
  矩阵的用法与判据写进 **`08-TESTING.md` §10.2**（含「`PACK_ONLY > 0` 的格子不许标 ✅」这条规矩）。
- **真机取证（两轮，Windows / NVIDIA RTX 40 系 / Vulkan 1.4.325 / 驱动 591.86 / BSL_v10.1.8）**：
  - `[C1] 全屏步 sampler 快照: declared=6 outside=0 unused=27 超集条目=33 derived=true`；
  - `[C3] 包一致性报告 pack=BSL_v10.1.8 | fragment-sampler 条目=33 命中=6 包有引擎无=0 引擎有包未用=27 ;;
    post-sampler 条目=24 命中=9 包有引擎无=0 ;; builtin-uniform-catalog 条目=47 命中=23 **包有引擎无=24** ;;
    declared-unsupplied 条目=16 命中=0 引擎有包未用=16 ;; …`（10 段全在）；
  - 唯一一条 `[C3]` WARN = 内建目录那 24 个包自写 uniform（GAP-021 那一族，正是报告该暴露的东西）；
  - 画面：同机位连拍（白天两帧 + 夜间一帧 + `time set 6000` 后一帧）地形/手持物/物品栏与 C1 前一致，
    **天空与云之间的黑带仍在** ⇒ 属既态（GAP-029 的 `isSky`/大气支 + GAP-027 的/cloud 糊），
    本轮不关也不邀功；C3 是只读 ⇒ 两轮画面无像素差（预期成立）。
- **为什么这么改**：`19` §6 的排序图把 C1/C0 放在 A 轨之前，C3 是「换个包就漂移」第一次变成可数的事；
  C2a/C2b（阴影族）按 §7-4 排在 C3 之后且要单独一轮取证。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md`（§4.1 若干项的状态、§4.4 落地注记、§4.5 的 C1/C3 行）、
  `docs/08-TESTING.md`（新 §10.2）、`docs/QUALITY-DEBT.md`（QD-11 状态）、`docs/13-GAP-REGISTRY.md`（新 `GAP-035`）、本文件。
- **测试结果**：`./gradlew build` **退出码 0**；新增测试 `PackSamplerSupersetTest` 5 条、
  `PackConformanceReportTest` 5 条（含「§4.1 十项必须各有节」这条**验收断言**）。
  棘轮：`ClassLineRatchetTest`（本轮把 `PipelineApi` 的行数**净降**到上限内，未抬基线）、
  `StaticFieldRatchetTest`（基线 175 未动 —— 新快照用 `static final AtomicReference` 承载，
  类注释里写明「这是同一份全局状态的两种写法，真正去处是 B2/B3 的 `PackSession`」，不假装债还掉了）。
- **未解决 / 存疑**：① `GAP-035`（全屏步布局启动期定死 ⇒ 包自造 sampler 名绑不上）本轮只做到「点名」，
  修法（按包派生 required 管线 / 放宽注册计数断言）未选型；② 报告目前覆盖三条全屏步 + 链各级，
  **gbuffer 各条程序**的内建/sampler 对差仍走绑定期日志（`[GAP-027] pack gbuffer uniforms bound`），
  要不要并进同一份报告待判；③ B0 的第三条棘轮（`StaticHolderResetTest` + 三个 reset 钩子）未做 ——
  `OfUniformManager` 正被另一条线改，撞车风险高，故意留后。
- **是否已提交**：⏳ 未提交（等用户点头）。⚠️ 工作区仍混着**另一条会话线**的 A 轨改动与
  非本轮在制品（`OfUniformManager` 及其测试、两条已 staged 的测试删除）⇒ 提交时按文件名逐个 `git add`。

---
## 2026-10-10（一百零五）— ✅ 阶梯 **C0**（原版 SPIR-V 反射面核实 + 只读探针）+ **B0 的两条棘轮**落码

> **verdict = 代码轮，但零行为改动**：新增 `bridge/SpvReflectionProbe`（**无生产调用点**，调用点属 C1）
> 与两条棘轮测试；`src/main` 里原有的任何一类输出/绑定路径**一字未动**。
> **本轮走的是 C 轨（C0 → C1 → C3）而不是 A 轨**：另一条会话线正在同一批文件上落 A0/A1
> （实测 `DefineProcessor`/`IncludeProcessor`/`CommentState` 在 21:36–21:42 被人改过、
> `CHANGE_LOG` 的一百零四条目与 `QUALITY-DEBT` QD-09 行也在同时更新）⇒ 为避免互相覆盖，
> 本轮只碰**对方没碰的文件**（`bridge/` 新类 + 两条新测试 + `19` 的 §4.2/§4.5 C0 两处）。

- **本次改了什么**：
  1. **C0 · `javap -p` 核实反射面**（`build/moddev/artifacts/minecraft-patched-26.3.0.51-beta-merged.jar`）：
     `SpvModule.reflect() / getReflectionInfoIfAvailable()`、`Reflection.{inputs,outputs,descriptors(),descriptors(int),pushConstants}`、
     `Descriptor.{name,type,resourceType,descriptorSetIndex(+setter),binding(+setter)}`、
     `Type.{baseType,dimensions,vectorSize,arrayDimensions,arrayLength}`。
     `resourceType` 数值用**两处独立一致的来源**钉死：lwjgl-spvc 3.4.1 的 `Spvc.SPVC_RESOURCE_TYPE_*`
     常量（`sampled_image=7 / separate_image=10 / separate_sampler=11 / storage_image=6 / uniform_buffer=1`）
     与原版 `frontend/shaders/SpvUtil.resourceType(UniformType)`；`baseType` 数值出处 = 原版 `SpvUtil.baseTypeString`。
  2. **C0 · 两条如实的限制**（都改变后续做法，不是脚注）：
     🔴 ① `Type.dimensions()` **拿不到任何公开常量表**（lwjgl-spvc 的 `Spvc`/`Spv` 里搜不到 `*DIM*`，
     原版也没暴露枚举）⇒ **不许当 3D/Cube 的唯一判据**（X9），维度仍以包声明的 `sampler3D/samplerCube` 为准；
     🔴 ② `testCompileClasspath` **取不到二级嵌套类型**（`SpvModule.Reflection.Descriptor` 在 `src/main` 编译通过、
     在 `src/test` 报「程序包SpvModule.Reflection不存在」，实测 45 个错只出现在测试源集）
     ⇒ 探针的「原版对象→快照」适配层**不能**用假实现单测 ⇒ **注册期日志自证列入 C1 取证项**。
  3. **C0 · 新增 `bridge/SpvReflectionProbe.java`**（只读快照 `Descriptor` record + `samplers()` 过滤 +
     `describe()` 逐行摘要 + 两张名称表；未知数值**保留数字不猜语义**）与 `SpvReflectionProbeTest`（4 条）。
  4. **B0 · 两条棘轮**（`19` §3.4 方案 3，照 `MethodLengthRatchetTest` 的先例）：
     `ClassLineRatchetTest` —— 基线**不是总数而是每个巨类一个上限**（6 个：1675/1573/1163/1131/1112/1069），
     超上限红灯、比上限少超 `SLACK=40` 也红灯（拆小了就强制把上限降下来 = 把进步锁住）；
     `StaticFieldRatchetTest` —— 静态非 final 字段基线 **175**，只许降，失败信息按**包分布**排序。
     两条各带「扫描器自身必须能找到东西」的元测试（`19` §3.4 点名的两种失败模式之一）。
  5. 🔖 本轮**实测抓到两处口径坑并当场改正**（都是「数字看起来对但错」的那一族）：
     ① `split("\n",-1).length` 对以换行收尾的文件**多算一行**，首跑把 6 个巨类全误报成「长了 1 行」
     ⇒ 改成数换行符；② `MrtTerrainPass`/`TargetReadback` **文件末尾没有换行符**（`tail -c 1` 是 `}`）
     ⇒ 本口径的真实行数比 `wc -l`（`19` 附录 A-A2）多 1，已在测试里写明差别与补齐办法。
- **为什么这么改**：`19` §4.2 明文「未核实的 API 表面不许当设计地基」（X41/X9），而 C1 的整个「按类型路由视图」
  都压在反射面上；B0 的两条棘轮是「之后任何拆分都在不许变差的护栏内」的前提（§6 排序图）。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md`（§4.2 新增「✅ C0 已核实并落码」表 + 两条限制；§4.5 的 C0 行改 ✅）、
  `docs/QUALITY-DEBT.md`（QD-10 → 棘轮已落地；QD-11① 的「先核实反射表面」前置已结）、本文件。
- **测试结果**：`./gradlew build` **退出码 0**（全套测试含新增 11 条全绿）。
  分测试类：`SpvReflectionProbeTest` 4/4、`ClassLineRatchetTest` 4/4、`StaticFieldRatchetTest` 3/3。
  ⚠️ **本轮未跑 `runClient`** —— 理由是**本轮零行为改动**（新类无调用点、棘轮只在测试源集），
  不构成任何功能证据；C1 动到注册期与每帧绑定后**必须**跑真机（`01` §1.2 的 Windows 车道）。
- **是否已提交**：⏳ 未提交（等用户点头）。⚠️ 工作区仍另有**非本轮**的在制品：
  `OfUniformManager.java` 及其测试、两条已 staged 的测试删除、以及另一条会话线的 A 轨改动
  ⇒ 提交时按文件名逐个 `git add`，别 `git add -A`。
- **下一步**：**C1**（`PipelineApi.PACK_FRAGMENT_SAMPLERS` 的 18 名硬编码改为「包 composite/deferred/final
  转译终稿声明派生」，硬编码表降为 fallback + 不符必 WARN，视图按 `SamplerDimensionPlan.ViewKind` 路由）；
  落点后是 **C3**（`PackConformanceReport` 覆盖 §4.1 全部名单并接 `08` §10 兼容矩阵）。

---

## 2026-10-10（一百零四）— ✅ 裁决 §7-2（允许引用游戏自带 `lwjgl-shaderc`，只作差分 oracle）+ **阶梯 A0 落地**（三处静默盲区变可见，零语义变化）

> **verdict = 代码轮（只动冷路径的诊断，不改任何输出）**：`./gradlew test` 全套绿，新增 5 条负样本单测全绿。
> **用户裁决**：「引用游戏自带 lwjgl-shaderc。然后按照 A0 到 C3 开始改进」
> ⇒ `19` §7-2 取**方案 (b)**：生产路仍是 jcpp，shaderc **只当差分 oracle**；同时开出执行序列 A0→B0→C1/C0→A1→A3→A2→C2a→C3。

- **裁决登记（四处文档，都是「把放行边界写死」）**：`19` §7-2 改为 ✅ 并补一张边界表（只进 `testImplementation`/`compileOnly`、
  🔴 不得进 `implementation`/发布 jar、运行期代码不得 `import`、版本入 `gradle.properties` 的 `lwjgl_shaderc_version`、
  拿不到 natives 时**必须显式跳过并自证**、对表时忽略行标记差异）；`19` §2.7 的「不引入任何原生依赖」原判同步改写为
  「生产路不变，放行的是测试期 oracle」；`07-CONSTRAINTS` §5.1 第二行 ⏳→✅；`13-GAP-REGISTRY` GAP-005 的
  「待 §7-2 确认」→ 已确认，并把它那处 **3.4.1 的过期版本号更正为 26.3 = 3.4.3**。
- **A0 改了什么（4 个主源码 + 3 个测试类）**：
  1. `glsl/preprocess/DefineProcessor.java` —— 指令行行尾 `\` → WARN（`warnAt` 与既有 `errorAt` 共用同一条
     「输入行 → 包内文件/行号」归因路）；**新增**「原文是指令行、无注释视图里不是」→ WARN。
  2. `glsl/translate/CommentState.java` —— 类与 `inBlockComment()/blockCommentStartLine()/stripComments()`
     可见性由包私有上调为 **public**，让 C 线复用**同一套**注释状态机。🔴 这是本阶唯一的结构让步：
     A1 建 L1 单一词法源后本类并入 `GlslTokens`，这条跨包引用随之消失（已写进类注释）。
  3. `glsl/preprocess/IncludeProcessor.java` —— 每行先取无注释视图；注释内的 `#include` → WARN，
     **展开行为逐字照旧**（改成不展开是语义变更，留给 A2）。
  4. `glsl/translate/IoLocationAdapter.java` —— 跨行/半截 in-out 那支旧代码**两类一起静默**；现按**行首括号深度**分流：
     深度 0 ⇒ WARN，>0（函数参数折行 `out vec3 x)`）⇒ 保持安静。深度只数无注释视图上的 `(`/`)`（新增 `netParenDelta`）。
  5. 测试：`DefineProcessorTest`（续行、注释内指令）、`IncludeProcessorTest`（注释内 include，且**断言行为未变**）、
     `IoLocationAdapterTest`（跨行必 WARN + 参数折行不得误报）—— 共 5 条，判据统一为「必须 WARN，不得静默」。
- **为什么这么改**：QD-09 的病不是「文本级方案有盲区」（那是既定取舍），而是**盲区静默产出错误结果** ——
  和 QD-08 那族「配置被读了、某条链静默走偏、日志无异常」同形。A0 之后的 A1/A2/A4 才有地方挂守卫。
- **对原判的三处事实修正**（已回写进 `19` §2.6 的 A0 行，下一轮别按旧措辞做）：
  ① 「宏体内 `gl_` 名」的可见性**不在** `LegacyBuiltinInjector`（转译跑在预处理之后，宏体展开后已是代码文本），
     真正的静默根因是 `/* */` 内的 `#define` ⇒ WARN 落在 `DefineProcessor`；
  ② `IncludeProcessor` 本阶照旧展开；③ `IoLocationAdapter` 必须按括号深度分流，否则函数参数折行全是误报。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md`、`docs/07-CONSTRAINTS.md`、`docs/13-GAP-REGISTRY.md`、
  `docs/QUALITY-DEBT.md`（QD-09 → 🔁 部分闭环）、本文件。
- **测试结果**：`./gradlew --offline compileJava compileTestJava` 通过；`./gradlew --offline test --tests "dev.vkdisp.glsl.*"` 全绿；
  `./gradlew --offline test`（全套）**BUILD SUCCESSFUL**，无 FAILED ⇒ A0 的「零语义变化」由既有幂等/输出断言共同守住。
  ⚠️ 未跑 `runClient`（本阶不碰 GPU、不碰每帧路径 ⇒ 真机不作为本阶判据；取证轮按 §7-4 留给 C2b）。
- **下一阶（已建待办）**：**B0**（三条棘轮 + `@TestOnly resetState` 契约，闭环 QD-03/QD-12）→ **C1/C0** → **A1**。
- **是否已提交**：⏳ **未提交**（等用户点头）。工作区另有**非本轮**在制品（`render/OfUniformManager.java` 与其测试、
  两条已 staged 的测试删除）⇒ 提交时按文件名逐个 `git add`，别把两条线混进同一个 commit。

---

## 2026-10-10（一百零三）— 📄 接上一轮把 `19` 附录 C 的剩余同步做完 + 一条「待查」实测结掉（26.3 自带 shaderc = 3.4.3）

> **verdict = 纯文档轮：零代码改动、零行为改动**（`src/**` 一个字节未动）。
> **起点**：用户对 `弱点改进方案调研` 那轮说「继续上一条消息」⇒ 接着把 `19-IMPROVEMENT-PATHS.md` 附录 C
> 那张同步表里**上一轮没覆盖的最后一行**（依赖版本锁定）落到可执行约束上，并回写同步状态。
> 🔴 **必须如实登记一件事**：本轮与 **一百零二** 是同一裁决在**两条会话线上并发写同一批文件**。
> 实测表现在：`03`/`07`/`13`/`QUALITY-DEBT`/本文件的改动落地时间落在本轮工作过程中（文件在两次读取之间变了内容）。
> ⇒ 本轮的处置 = **每次 Edit 前先重读目标文件的当前内容**，只做对方没覆盖的三处（`AGENT_CONTEXT`、
> `07` §五、`06`/`05` 的版本登记），并把自己改过的段落写成「就地更正」而非整段重写，避免互相覆盖。

- **新事实（本机可复算，命令见 `19` 附录 A-A5）**：`19` §7-2 挂着的「26.3 清单里的 shaderc 版本号待查」结掉了 ——
  26.3 版本清单声明的是 **`org.lwjgl:lwjgl-shaderc:3.4.3`**（本机缓存的 26.2 清单是 **3.4.1**），
  且 3.4.3 的 `org/lwjgl/util/shaderc/Shaderc.class` **确实含** `shaderc_compile_into_preprocessed_text`。
  🔖 顺带用这条实测**推翻了「按 26.2 数字写文档」的做法**：同一坐标随 MC 小版本漂移 ⇒ 必须进升版核查表。
- **本次改了什么（五个文档，全部是补上一轮的同步欠账，不改任何结论）**：
  1. `docs/AGENT_CONTEXT.md` §0 三处（**上一轮没动这个文件，而它是跨会话自动加载的上下文源** —— 留着旧表述，
     下一个会话会按「100% 自研」这条伪约束做取舍）：新定位那句改为「代码 100% 来自 MIT/Apache-2.0/BSD 族或我们自己，
     并保留上游署名与改动声明」；硬约束那条改为「**MIT 的红线是不越界，不是完全自研**」并写明并码三条件；
     glslang 那行加两条收窄注记（预处理槽不成立 / `Pp*` 带非标 `AML-glslang`）指向 `19` §2.3–§2.4。
  2. `docs/07-CONSTRAINTS.md` 新增 **§5.1 引入第三方坐标时的附加规则**：`org.anarres:jcpp`（版本入
     `gradle.properties` + 传递依赖含 guava/slf4j/ant/logback ⇒ 只移植核心或写排除表 + 留 `LICENSE`/`NOTICE` 并声明改动）、
     `org.lwjgl:lwjgl-shaderc`（**游戏自带 ⇒ 只 `compileOnly`、不新增分发** + 版本必须与 MC 清单逐字一致）。
     ⚠️ **两个依赖都还没引入**，本节只登记「放行后怎么锁」；§7-2 那句待判**仍然待判**。
  3. `docs/06-MIGRATION.md` §3 新增易变点 **V6 = 游戏自带的 LWJGL 坐标版本**（含复算命令与「本项目依赖表里零
     LWJGL 坐标 ⇒ 这条今天为空、一旦 `compileOnly` 就立刻生效」的如实说明），引言「5 类」改「6 类」。
     🔴 并修掉一处**陈旧引用**：§4 第 5 步原写「复查 §3 的 V1–V4 四类」，而表里早有 V5 ⇒ 改 V1–V6。
  4. `docs/05-VERSION.md` §2 版本锁定表加一行**标成「非依赖」的事实登记**（原版自带 LWJGL 线 = 3.4.3，26.2 = 3.4.1），
     并把引用去处指向 `07` §5.1 与 `06` V6 —— 版本权威文档只登记事实，不代表项目已依赖它。
  5. `docs/19-IMPROVEMENT-PATHS.md` 按上面的事实回写：§2.3 shaderc 行、§7-2、附录 A-A5、附录 B shaderc 行
     全部从「3.4.1 / 26.3 待查」改为「26.3 = 3.4.3，且这是漂移证据」；§7-1 与状态头的「文档债未动」改为已同步并
     **更正一处出处错误**（本文原写 `03-DIRECTION.md` §4，那句实际在 **§0**）；附录 C 第七行 ⏳→✅。
     🔖 本文**结论未变**：`lwjgl-shaderc` 仍定位为差分 oracle，不进生产、不进热路径。
- **为什么这么改**：附录 C 那行不做完，「允许并码」就只停在结论上 —— 没有一条写得出的约束规定
  「第三方坐标进 `gradle.properties` 的哪个键、升 MC 时查什么」。而 26.2→26.3 这次**实测漂移**恰好是漏登记的后果样本。
- **影响的文档**：`docs/AGENT_CONTEXT.md`、`docs/07-CONSTRAINTS.md`、`docs/06-MIGRATION.md`、`docs/05-VERSION.md`、
  `docs/19-IMPROVEMENT-PATHS.md`、本文件。
- **测试结果**：**未跑 `gradlew build`**（纯文档轮，`src/**` 未动 ⇒ 不构成功能证据，也不借它自证）。
  文档内数字与结论的可复算依据：`19` 附录 A-A1（静态非 final 字段 = **175**，本轮重跑确认）、A-A5（shaderc 版本与符号）。
- **待判（不要自行决定）**：`19` §7-2（测试/离线工具**可否**引用游戏自带的 `org.lwjgl:lwjgl-shaderc`）、
  §7-4（C2b 阴影 pass 装配排哪一轮真机取证）。本轮只是把「若放行怎么锁版本」写清楚，**没有替用户决定**。
- **是否已提交**：⏳ **未提交**（等用户点头）。⚠️ 工作区另有**非本轮**的在制品：
  `src/main/java/dev/vkdisp/render/OfUniformManager.java` 与其测试的改动、两条已 staged 的测试文件删除
  （`F4InfraSmokeTest` / `FfmBoundaryProbe`）⇒ 提交时**按文件名逐个 `git add`**，别 `git add -A`，
  也别把这两条线的产物混进同一个 commit。

---

## 2026-10-10（一百零二）— 📄 弱点改进方案落地成文（新 `docs/19-IMPROVEMENT-PATHS.md`）+ 按用户裁决更正四处许可/缺口文档

> **verdict = 纯文档轮：零代码改动、零行为改动**（`src/**` 一个字节未动）。四条结论性更正 + 一份方案书。
> **裁决入口**：用户判「`100% 自研` 是早期文档自己加的限制，**不是 MIT 的要求**；合规底线只是完全遵守 MIT、不越界」
> ⇒ **移植 jcpp（Apache-2.0）获准**。
> 证据：本文所有数字/行号由 `19` 附录 A 的命令复算得出（本机可跑）；外部结论逐条带仓库 `LICENSE` 文件 URL（附录 B）。

- **新增 `docs/19-IMPROVEMENT-PATHS.md`**（441→531 行，方案书，**未执行**）：四类原型弱点（= `QUALITY-DEBT` QD-09~14）
  各自的「现状取证 → 病根 → 调研 → 阶梯 → 验收」，并给出跨弱点的执行顺序与三条硬依赖
  （A1 单一词法源挡在 A2/A4 前；B0 棘轮挡在 B3 前；C0 `javap` 核实挡在「把原版反射面当设计地基」前）。
- **本次改了什么（五个文档，逐处都是「原判不成立所以更正」，不是补充说明）**：
  1. `07-CONSTRAINTS.md` §〇：删掉「MIT 选了就得完全自研」这句**不是 MIT 要求**的自我限制，改为 §1.3 判定表的真实规则
     （MIT/Apache-2.0/BSD 族**可并入代码**，条件是保留其 `LICENSE`/`NOTICE` 并**声明改动**）；
     §1.3 判定表补 **BSD-2/BSD-3** 一行并写清各自的「不能带走」；§七 自检清单新增一条署名核对。
     🔴 **P1/P2/P3 与 L5–L8（LGPL / GPL / ARR 一律不并入）未动** —— `L7` 作废 VulkanMod 移植的理由是它是 LGPL-3.0，与「能否用第三方库」无关。
  2. `03-DIRECTION.md` §0 共同结论：「本项目 MIT，100% 自研代码」改写为上述准确表述。
  3. `13-GAP-REGISTRY.md` **GAP-005 原地更正 + 拆两槽**：005a 预处理槽（jcpp 实现 / shaderc 差分 oracle）、
     005b 解析槽（glslang 全量）。🔴 两条推翻原判的新事实：① glslang 头文件自述「只做预处理以取预处理串」
     **不是官方支持或完全可用的路**；② 其 `REUSE.toml` 把 `preprocessor/Pp*` 钉为 **BSD-3 + NVIDIA `AML-glslang`（非标文本）**
     ⇒ 按 §1.3「拿不准 = 只读思路」，并码前须逐条读完。另加 **jcpp = Apache-2.0（纯 Java、token 级、已实现 `\` 续行拼接）**
     与 **shaderc 预处理入口游戏自带**两条低成本臂。
  4. `13-GAP-REGISTRY.md` **新增 `GAP-034`「包的 `shadow` 程序从未被装配 / 渲染」**（四条 grep 可复算的事实：
     `ProgramStage.SHADOW` 零消费者 / `shadowtex0/1`+`shadowcolor0` 绑 `ShadowStubs` 1×1 桩 / 全仓无阴影目标贴图 /
     `vkdisp:pipeline/shadowed` 是我方三步演示链而非包的 shadow）。
     ⇒ 这同时**推翻了 QD-11② 的「接太阳方向即可」**：角度接上去也不会有像素变化。已把该族拆成
     **C2a（光空间数学，可单测、不碰 GPU，1 天）**与 **C2b（pass 装配，独立大项）**，并写明
     **C2a 不得单独上真机**（会得到一个不可归因的空结果，X46/X49 形态）；级联（4 级）**暂不做** ——
     OF/Iris 格式**没有** loader 级 CSM/`frustumSplit` API，级联是包在一张深度图内部自切的惯例。
  5. `QUALITY-DEBT.md`：QD-03 的「95」→ **175**（口径 = 静态非 final 单行声明；分布 `bridge` 123 / `render` 20 / `pack` 10 / `glsl/translate` 7），
     并立规「脚本能算的数字不再手抄」；QD-11 就地更正两条（② 不完整、① 偏贵 —— 派生机制**已在仓**：
     `PostPassContract.SAMPLER_DECL:51,97` + `SamplerDimensionPlan.SAMPLER_DECL:186,206` + `PackPostChain:223-232` 已跑通「派生+对差+WARN+排除」；
     且 `SamplerDimensionPlan:28-32` 早已按 **X39** 明文否决过按名字硬编码 ⇒ 本条实质是「原则没贯彻」）；
     §0 新增 QD-09~14 ↔ `19` 各小节映射表。
- **为什么这么改**：①「100% 自研」这条**伪约束**正在实际挡住最省力的正确解（Apache-2.0 的现成 token 级预处理器），
  且让反射开关等 workaround 有了不该有的借口；②QD-11② 的错判会把一个「几周量级的 pass 装配」
  登记成「1 小时可闭环」，属本项目反复消灭的那类**会把下一轮引错的记录**。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md`（新建）、`docs/00-INDEX.md`、`docs/16-READING.md`、
  `docs/07-CONSTRAINTS.md`、`docs/03-DIRECTION.md`、`docs/13-GAP-REGISTRY.md`、`docs/QUALITY-DEBT.md`、本文件。
- **测试结果**：✅ 跑了 `./gradlew.bat test --offline` ⇒ **BUILD SUCCESSFUL**，`build/test-results/test` 共 **115 个套件 / 1109 条 / 0 失败 / 0 错误**。
  🔖 诚实口径：本轮**实跑**的是三条扫文档的守卫 —— `GapRegistryStatusFieldTest`(3) / `VulkanEvidenceDisciplineTest`(4) /
  `MethodLengthRatchetTest`(4)，改完 `13-GAP-REGISTRY` 与 `00/07/16` 之后跑的，验证「新增 GAP-034 没把状态棘轮撑红、
  文档措辞守卫没被改松」；其余套件由 Gradle 判定 up-to-date（**本轮零代码改动，这是合理的**，但不等于我逐条重跑过）。
  ⛔ 纯文档轮**不构成功能证据**，也不据此自证任何画面结论。
  🔴 **工作区另有非本轮改动**（`src/main/java/dev/vkdisp/render/OfUniformManager.java`、`src/test/.../OfUniformManagerTest.java`，
  以及**已 staged 的两个删除** `F4InfraSmokeTest.java` / `glsl/FfmBoundaryProbe.java`）⇒ 提交时**按文件名逐个 `git add`**，
  绝不用 `git add -A`（X12：别把别人的活混进这条 commit）。
- **待判（不要自行决定）**：`19` §7-2（测试/离线工具可否引用游戏自带的 `org.lwjgl:lwjgl-shaderc`，
  🔖 本条原挂的「**26.3** 清单里的版本号待查，本机只核到 26.2」**已于同日结掉**：26.3 = **3.4.3**、26.2 = 3.4.1，
  见 `19` 附录 A5 的复算命令与下一轮条目 **一百零三**）、§7-4（C2b 排哪一轮真机取证）。
- **是否已提交**：⏳ **未提交**（等用户点头；本轮工作区另有他物，提交时按文件名逐个 `git add`，别 `git add -A`）。

---

## 2026-10-10（一百零一）— 🎯 GAP-031：「倒影/虚影」定案为 **mip 金字塔的 blit 带了不该带的那次 V 翻转** ⇒ 奇数级上下镜像，被包的 bloom 加回画面

> **verdict = 真机分带读数证明金字塔「只有奇数级是镜像的」（mip0/m2/m4 的 SKY<TERRAIN，而 m1 反着来），去掉那次翻转后四级一致、同机位截图里云的边缘副本消失**
> 证据：`run/logs/latest.log`（修前 18:55 臂 `CLOUDS=0`、修后 19:0x 臂无覆盖）；`colortex0` 分带 m1 `0.9351/0.6166`（反）→ 修后 m1 `0.6921/0.1316`（与 mip0 `0.7227/0.1318` 同向）
> 登记：`docs/13-GAP-REGISTRY.md` 新增 **GAP-031**（含热重载竞态那条副产物）；本文件

- **本次改了什么**（一个行为文件 + 一条守卫）：
  1. **`assets/vkdisp/shaders/blit.fsh`**：`texture(InSampler, vec2(vUv.x, 1.0 - vUv.y))` → `texture(InSampler, vUv)`。
     那次翻转是给「中间目标 → 主目标」标定的（口径写在 `fullscreen_flipv.vsh` 头注 P-1f：包片元保持 OF 原始 vUv 语义，
     所以翻转上移到顶点），而本文件全仓**唯一**消费者是 `FrameApi.generateMipPyramids`（`:1517`，grep 核实）
     —— 那是**同一张图的相邻两个 mip 之间**的拷贝，取向必须逐字相同 ⇒ 每生成一级就镜像一次。
     注释同步改写成「这里不得加 V 翻转 + 为什么 + 判据」，防止下一个人按旧注释「修回去」。
  2. **守卫 `MrtTerrainPassWiringTest.mipPyramidBlitMustNotFlipV`**：四条断言 —— blit 片元不含 `1.0 - vUv`、
     必须直接以 `vUv` 采样、`generateMipPyramids` 仍是它的消费者（消费者换了就要重判取向口径，守卫不许跟着悄悄失效）、
     `fullscreen_flipv` 的翻转能力必须还在（不能为修这一格把另一格改回上下颠倒）。
- **为什么改**：用户报「重点排查这个『倒影』『虚影』问题」并给两张真机截图（放射状扇形涂抹 + 屏幕顶部挂着倒置地形）。
  这条不在 GAP-030 的账上 —— 那一轮判掉的是「太阳方向退化」，本轮画面已经是**修后**状态（日志 `pack=9 adapter=0`、9/9 槽跑包顶点程序）。
  定位路径：① `CLOUDS=0` 臂证明那些白团是包的体积云、而**地形边缘的重影与云无关**；② `TAA=false` 臂无变化 ⇒ 排除时域历史；
  ③ 读 `composite4/5` 的 bloom —— 它把 **mip 1..7 七个 LOD 求和**，还过一道 `pow(blur/32, 0.25)`（四次方根把暗部抬一个量级，
  所以副本只出现在**暗区=天空**，高频高亮的地形上看不出来，这正是「地形清晰、天空挂倒影」那个反直觉组合）；
  ④ 把 `mrt.pixelProbeMipLevels` 打开成**分带**读数，取向立刻变成一个可数的量 —— 只有奇数级反 ⇒ 交替翻转，指向 blit 的 `1.0 - vUv.y`。
  🔖 **与 h05 的 `readbackMustUseNoFlipPipeline` 是同一族事故**（那次是回读用了翻转版），也是 GAP-017/020 的下一格：
  金字塔修成真跑之后，镜像内容才第一次真的被包读到。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（新增 GAP-031 全条）。源码侧只有那个 `.fsh` 与一条测试，`VkDispConfig` 未动。
- **测试结果**：全仓 **1110 项 0 失败**（新增 1 条守卫）。真机两臂见上「证据」行；⚠️ 画面判据只判到「镜像副本消失」这一格。
- **是否已提交**：否（改动留在工作区，等用户确认）。

### ⛔ 遗留 / 下一步

1. 🔴 **天空与云整体仍不对，但账不在本条**：云与云之间是**黑**而不是蓝天（GAP-029 的 `isSky`/大气那一支）、
   云形状糊且偏暗绿（GAP-027）。下一刀别从 bloom 走。
2. 🔴 **本轮量到一格新的、此前只在注释里出现过的怀疑：colortex 池是 `RGBA8_UNORM`**
   （`ColortexPool.create`）。同帧 `c0@chainStart` FULL luma **0.54** 而 `main` **19.17** ⇒ 包的**线性** gbuffer 值被压进
   8 bit，暗部整段塌掉，再被 `pow(x,0.25)` 放大成可见结构。这解释「为什么 bloom 的残值能长成形状」，
   但**本轮没有判它** ⇒ 属新格，登记前不许顺手改格式（会牵连 `mrt.attachments` 与全部包的输出契约）。
3. 🔴 **热重载竞态（本轮真炸一次）**：`pack.optionOverrides` 改 `CLOUDS` 触发资源重载后，水的包管线声明 `gaux1`
   而绑定组没给 ⇒ `FrontendRenderPass.validateDraw:553` 抛 `Missing uniform gaux1` **炸整帧**
   （崩溃报告 `run/crash-reports/crash-2026-10-10_18.46.29-client.txt`）。同一选项**冷启动不炸**（18:52 那一跑 0 ERROR）
   ⇒ 是重载期的装配不一致，不是静态缺项。已记在 GAP-031 的「不关」行，**另开一条再修**。
   🔖 取证纪律补一条：改**包选项**的臂要冷启动，别用热重载（本轮因此丢了一跑）。
4. `mrt.pixelProbeMipLevels` 我留在了 `"1,2,4"`（取向判据就靠它，且它是只读探针、不改画面）；
   `optionOverrides` 已清回 `""`。下一轮若要别的臂请显式说明，别继承这两个值。
5. 🔖 **本轮踩到两次「把不可比的读数当对照」**：① 第一次切 `LIGHT_SHAFT=false` 的臂同时改了 `time`/`weather` ⇒ 那格作废，
   后面所有臂都在钉死的 `time 6000 + clear + 同一机位` 上重取；② `CLOUDS=false` 被包**拒绝**（它是整数选项，原值 2），
   日志 `OPTION_OVERRIDE_SET_REJECTED` 说得很清楚 —— 覆盖「写了」不等于「生效」，每条臂起跑后必须回读这一行。
6. ⚠️ 工作区里仍有一份**不是我产出的未跟踪目录 `architecture/`**（上一轮已登记）⇒ 提交时按文件名点加，别卷进来。

---
## 2026-10-10（一百）— 🎯 GAP-030 方案 2 落地：后处理链各级开始跑**包自己的** post 顶点程序；真机 A/B 判掉「太阳方向退化」这一格

> **verdict = 链 9 级全部改跑包的 post VSH（`pack=9 adapter=0`，9 个源逐槽过驱动编译、整份日志 0 ERROR）；真机单变量对照证明天空第一次随太阳位置变化（日出朝东天区 26.89 > 朝西 16.43，而适配层臂是 8.61 < 50.29 反的）**
> 证据：`run/logs/latest.log`（ON 臂 18:0x 与 19:0x 两跑、OFF 臂一跑）；`build/vkdump/{on,off}-{horizon,zenith,sunrise-east,sunrise-west}.png` + `bandstats.py` 的分带数
> 登记：`docs/13-GAP-REGISTRY.md` 新增 **GAP-030**（含三条引擎硬约束的取证行号）；本文件

- **本次改了什么**（12 个源码文件 + 3 个测试文件 + 2 个新类）：
  1. **`glsl/translate/FtransformExpander`**：新增 **post 恒等档** —— `ftransform()` → `vec4(Position, 1.0)`（OF 语义：post 顶点数据本来就是 NDC，固定功能 MVP 在这一族是单位阵）。
     档位是**参数**不是静态开关，由 `ShaderPackCompiler` 按 `ProgramStage#isPostChain()`（新增）传入并一路穿到 `OfGlslTranslator` / `GlslPipeline.run|analyze|runPreprocessed`（旧签名全部保留为委托，地形档逐字节不变，有测试钉）。
  2. **新增 `glsl/translate/PostVertexLinker`**（本轮的地基）：把包的 post VSH 对齐到 required 管线的三条硬规则上 ——
     ① **属性归一**：VS 的 `in` 必须落在冻结名单内且基类型为浮点，名单外/整型 ⇒ 整行改 `const` 零值 + **ERROR**（引擎 `PipelineBuilder:139` 对查不到同名缓冲元素的属性直接抛，代价是整次重载）；
     ② **varying 按名字对齐 location**：引擎跨阶段**只看 location 不看名字**（`PipelineBuilder:197-256`），所以把 VS 每个 `out` 的 location 改成片元契约里同名 `in` 那号；片元要而顶点没产的 ⇒ 逐条合成（uv 名取注入属性、世界向量走**声明为我方口径**的公式、其余零值 + ERROR）；没被消费的顶点输出**搬到空闲号**（不删，删声明要连带删赋值）；
     ③ **`VkDispBuiltins` 块统一**：顶点的块整体换成片元那份，只有顶点声明的成员**追加**到块尾并同步进片元源 —— 引擎对同名块只比 `resourceType`/`dimensions`（`PipelineBuilder:286-292`），不统一就是**静默喂垃圾**。统一后复核两阶段成员表必须逐条相等。
  3. **`pack/PackPostChain`**：`Pass` 增 `vertexSource` + `hasPackVertexSource()`；顶点选源只认**限定名逐字相等**那条（跨维度凑 = 一个维度的顶点算法配另一个维度的片元）；自报行逐槽带 `vertex=pack|adapter`，另加一行可数的 `[GAP-030] post 顶点程序来源: pack=N adapter=M`。
  4. **`bridge/PipelineApi` + `bridge/FrameApi`**：post 管线声明 `withVertexBinding(0, POST_VERTEX_FORMAT)`（元素名取自链接器冻结名单，全 `RGBA32_FLOAT`）；新增 3 顶点全屏缓冲（大三角形，逐字复刻适配层已验证的 p416 屏幕 uv 取向）；`runPostPass` 每级绑缓冲后再 `draw(3)`（`FrontendRenderPass:537-547`：声明了 format 就必须绑）。
  5. **`VkDispVirtualPack`**：包顶点源**逐槽过一次 `ShaderCompileApi`** 再落地（静态对齐挡不住「包自己用了未声明的名字」那一族），失败 ⇒ 整槽回落适配层 + ERROR 原文；适配层的 `worldVectorZeros()` 命中即 `LOGGER.error`。
  6. **防复发闸**：`PackPostVertexAdapter.Result` 增 `worldVectorZeros()` —— 该类注释一直写着「每条零值都出一行 WARN」，而 `zeroSupplied` **在生产代码里从没被读过**（grep 核实），这正是 X11 定义的「静默占位」事故形态；现在它既是数据也是启动 ERROR。
  7. **新增开关 `pack.postVertexProgram`（默认开）** + `pack/PackPostVertexSwitch`（沿用 `PackChainGatingSwitch` 的「配置键名 ≠ Java 字段名」双常量形状，h33 那一族）。
- **为什么改**：上一轮真机把深度族修好后暴露的下一格 —— 用户报「不镜像天空镜像地面了…一侧体积云正常，是长条状的云，间隔大致固定长度」。彻查结案到 `PackPostVertexAdapter` 把 `sunVec/upVec/eastVec` 按**零向量**供（退化轴 ⇒ 体积光径向抹成镜像虚影、云噪声沿退化轴采样成长条）。用户拍板「做 2，把地基一次性打好」，不做逐名兜值的方案 1（逐名永远慢包一步，X27）。
- **影响的文档**：`docs/13-GAP-REGISTRY.md`（新增 GAP-030 全条）、本文件。`VkDispConfig` 的开关注释即口径说明，`04-SPEC`/`07-CONSTRAINTS` 本轮无改动。
- **测试结果**：全仓 **1109 项 0 失败**（含新增 `PostVertexLinkerTest` 9 条、`PostVertexWiringTest` 7 条源码接线守卫、真包端到端 `PostChainPackVertexTest` 5 条、`FtransformExpanderTest` 恒等档 1 条）。
  真机三臂见上「证据」行；⚠️ **画面判据只判到「太阳方向不再退化」这一格**，天空/云整体观感仍不对 ⇒ GAP-030 **不关**，剩余面归 GAP-029/022/027。
- **是否已提交**：否（改动全部留在工作区，等用户确认）。

### ⛔ 遗留 / 下一步

1. 🔴 **天空与云整体仍然不对**（正午抬头是灰白涂抹、日出高空仍是暗带）⇒ 下一刀按 GAP-029 的「不关」清单走，不要把它记成本轮已修。
2. `gl_ModelViewProjectionMatrix` 在 post 档的恒等语义本轮**只做了 `ftransform()`**；矩阵旧名仍映射成 `gbufferProjection*gbufferModelView` ⇒ 真遇到再补（X9 不猜）。
3. 包的 post 顶点程序若引用冻结名单外的属性，本轮按 `const` 零值降级 + ERROR ⇒ 那条包路径仍不成立（GAP-030「不关的部分」①）。
4. 🔖 **本轮踩到两个「看起来对」的实现缺陷并被自家闸门拦下**（逗号多成员只取一个名字、同行两条 `out` 看不见）—— 记在 GAP-030 的落地行里；教训是：**按行认声明的层，输入前提必须由自己保证**，不能假设上游的拆语句会换行。
5. ⚠️ **工作区里有一份不是我产出的未跟踪目录 `architecture/`**（4 个文件，17:05–17:13 生成：`system-model.*.md` / `vkdisp-dependency.dot` / `vkdisp.structurizr.dsl`）⇒ 提交时**按文件名点加**，别把它卷进来；要不要留由用户定。

---
## 2026-10-10（九十九）— 🧹 文档梳理收尾：CHANGE_LOG 压缩修好（93–97 恢复 + 摘要层归位 + 断句补完），版本 / mixin 镜像全仓同步

> **verdict = CHANGE_LOG 恢复「最新条目在最上方」且不再留下无指引的空洞；`26.3.0.51-beta` / MDG `2.0.148` / `MIXIN_CONFIG_COUNT = 1` 三组事实在 `docs/*`、根目录手册与源码注释里对齐；三个会读文档正文的守卫 11 项 0 失败**
> 证据：`git diff`（本文件 + 8 份文档 + 1 处源码注释）；`VulkanEvidenceDisciplineTest` 4/0、`GapRegistryStatusFieldTest` 3/0、`ComparisonSamplerGapTest` 4/0
> 登记：本文件；`16-READING.md` 的 CHANGE_LOG 行按新结构改写；`06-MIGRATION.md` §6 补 2026-10-06 升版行

- **本次改了什么**：
  1. **`CHANGE_LOG.md` 的压缩收尾**（上一轮断在半路，留下四个缺陷）：
     - 从 `f126d1a` 恢复被整段删除的**第九十三~九十七**轮全文 —— 最新五轮此前既无全文也无摘要；
     - `阶段摘要（第 84-87 轮）` 从文件顶部挪到第八十八轮条目之后 ⇒ 恢复「最新条目在最上方」；
     - 新增 `阶段摘要（第 77-83 轮）`，覆盖此前被删除又没有任何指引的七轮；
     - 补完断在半行的 `为什么压缩` 段落；`归档说明` 改写成与文件实际内容一致的三层表
       （全文 八十八 ~ 九十九 / 摘要 八十四~八十七 / 摘要 七十七~八十三 / 第 七十六 轮及以前 → git + `evidence/`）。
  2. **版本与 mixin 的镜像同步**（口径权威 = `05-VERSION.md`；实测值取自 `gradle.properties`、`build.gradle`、`MixinTargets.java`）：
     `AGENT_CONTEXT.md` 2 处、`07-CONSTRAINTS.md` 3 处、`05-VERSION.md` 2 处举例、`VKDISP-接入手册.md` 2 处
     从 `26.3.0.41-beta` / MDG `2.0.147` 抬到当前值；`PackPickerScreen` 类注释仍写
     `MIXIN_CONFIG_COUNT = 0`（零 mixin 红线）⇒ 改为「mixin 预算全部留给管线装配层，登记见 `04-SPEC` §5.0」（**仅注释，无逻辑改动**）。
  3. **`06-MIGRATION.md` §6 迁移日志补 2026-10-06 一行**：`cbb33a3` 把 NeoForge 41→51、MDG 147→148 夹在 h48
     功能提交里一并升，当时**没回填迁移日志**，也没同步 `00`/`06`/`07`/`AGENT_CONTEXT` 的版本镜像 —— 正是本条第 2 项在补的债。
- **为什么改**：那四个压缩缺陷合起来会让下一个读者以为「九十七轮之后没有进展」，或把顶部那条 84-87 摘要当成当前状态
  —— 而 `00-INDEX` 明确规定「本轮做了什么 / 下一步 = `CHANGE_LOG.md` 顶部条目」。版本与 mixin 的漂移同源：
  升版提交只改了 `04`/`05`/`13`，其余文档与一处源码注释停在 `.41-beta` / 零 mixin 的旧口径上。
- **影响的文档**：`CHANGE_LOG.md`、`docs/AGENT_CONTEXT.md`、`docs/07-CONSTRAINTS.md`、`docs/05-VERSION.md`、
  `docs/06-MIGRATION.md`、`docs/16-READING.md`、`docs/00-INDEX.md`（取证铁律②/③ 改为按车道，见下方追加）、
  `docs/01-DEV-LOOP.md` §1.2（`/tools/` 不入库的口径）、`VKDISP-接入手册.md`、
  `src/.../screen/PackPickerScreen.java`（注释）。
  上一轮遗留的同批未提交改动一并在此登记：`docs/02`/`04`/`13`
  （版本 51 / MDG 148 / 3 个装配层 mixin / Windows 车道免参数化 / `2026-02` 笔误 → `2026-10-02`）。
- **测试结果**：三个会读文档正文的守卫 **11 项 0 失败**（见上）。本轮无功能改动，未跑完整套件、未做真机取证。
- **是否已提交**：否（改动全部留在工作区，等用户确认）。

### ⛔ 遗留 / 下一步

1. 🔴 **工作区里有与本仓文档轮无关的、正在进行中的源码改动**（本轮起始快照里还没有它们）：
   `bridge/MrtTerrainPass.java` +27（① 全屏探针在 `actualSlots ≠ 3` 时跳过并 WARN；② 新增 `prepareGbufferViews()`，
   只调 `ensureColortex` 把池建在地形 pass 之前）+ `bridge/SkyIntoGbuffer.java` +3（首帧先调它再取视图，
   修天空 `gbuffer-view-null` 整帧跳过）。这套是**天空首帧**那条线的在途工作，不是文档轮产物 ⇒
   本轮未验证、未提交、未回退；⚠️ **提交文档时别把它们一起 `git add`**（按文件名点加）。
2. **源码级核实用的 jar 仍是 `26.3.0.41-beta`**（`04` §5.0 的 M-01 逐行引用、`13` GAP-015 的反汇编）。
   编译基线已在 `.51-beta` ⇒ 涉及原版签名的结论**尚未在 .51 上重核**，已在 `06-MIGRATION` §6 新行标注；
   下次碰原版签名前先按 `06` §4 重跑一遍。
3. `CHANGE_LOG.md` 的压缩是**手工**动作、没有守卫：下一轮继续压缩时，请同步改 `归档说明` 的三层表，别只删条目。
4. 🔴 **本轮编号由「九十八」让位为「九十九」**：本轮起草时基线是 `f126d1a`，而 `c855eee`（X43–X49 抢救轮）
   已抢先占了九十八并已进远端。三方合并把抢救轮独有的一切重新并回工作区 ——
   `07-CONSTRAINTS.md` 的 X43–X49 七条 + 七条自检项、`AGENT_CONTEXT.md` 的判读三条硬规则（均纯增量、14 / 5 行），
   连同该轮的 CHANGE_LOG 条目本身；本轮自身的改动零删除。

### 追加（同一轮）：拿到原始会话日志后的复核

上一轮的压缩是断在半路的，而它当时的**范围裁决**只存在于那条会话里。日志（`session.v4.jsonl`，
`session-f6b14318`，675 条）到手后逐条对账：

- **用户的三条裁决**（该会话 `ask_user_question` 的回执）：① 范围 = **仅 `docs/` 修订**；
  ② CHANGE_LOG = **压缩为阶段摘要 + 保留最近 10 条全文**；③ `evidence/`、`review/` = **不动，只更新引用关系**。
  ⇒ 本条上文「从 git 恢复 93–97 全文」正是裁决②的口径，不是另一种选择。窗口现在 12 条全文
  （同一天并行了两轮：本条登记为九十九，`c855eee` 的 X43–X49 抢救轮占了九十八），规则已写进 `归档说明`。
- **它自己列的十条计划**逐项核实**都已落地**，包括两处它自陈写错后回改的：`04-SPEC` 头部的
  `9.userVersion`（现已无残留，Gradle 版本号 = `9.4.1`，与 `gradle-wrapper.properties` 实测一致）、
  `13` 的 `2026-02` 笔误（已为 `2026-10-02`）；`08-TESTING.md:379` 的 MCP 口径更正更早就进了 `3c8fee8`。
  它在 seq 603/622/640 已经发现「最新条目 93–97 丢了」并正从 git blob 里恢复，被沙箱限制打断 —— 本轮接着做完。
- **裁决③（引用关系）它没做，本轮补做**：全仓 116 份 md 里的 `docs|evidence|review|tools|src` 路径引用逐条查存在性，
  结果只有一处实质问题 —— 🔴 **`/tools/` 整目录是 gitignored**（`.gitignore:80`），
  而 `00-INDEX` 取证铁律②把 Linux 取证脚本写成唯一入口：本工作区 `tools/vulkan-local/` 实测只剩
  `env.sh`、`vk_smoke.py`、`pkg/`、`prefix/`，`run-client.sh` / `preflight.sh` / `mcp-drive.py` / `x11_input.py` 等
  **都不在**（`find` 全仓无命中）。⇒ 铁律②改为**按车道取入口**并标明脚本不入库，`01-DEV-LOOP` §1.2 同步；
  守卫 `VulkanEvidenceDisciplineTest` 只查文档措辞、不查文件存在，这条边界也写进了铁律③。
  另有 `docs/22-版本基线.md` 的残留提及，但都在 `QUALITY-DEBT` F-06 与 `review/` 的历史记录里
  （那两条记的正是「已修」），属如实历史，不动。
- **复跑守卫**（`00`/`01` 措辞动了）：`VulkanEvidenceDisciplineTest` 4/0、`GapRegistryStatusFieldTest` 3/0、
  `ComparisonSamplerGapTest` 4/0 ⇒ **11 项 0 失败**（时间戳 2026-10-10T05:45Z）。

---
## 2026-10-10（九十九）— 🎯 Windows/NVIDIA 真机首测：黑屏/涂抹/双云三族根因定案并落地；深度判据改立「float copy 回读」唯一标准

> **verdict = 真机上「视野全黑」根因＝同 encoder「刚当过附件就采样」读 0（金字塔级联 + 代理翻转两处），独立 encoder 隔离后 `chainSamplerLod0=false` 下 main 全程 100% nonBlack；lod0 拐杖已撤**
> 证据：run/logs/latest.log（14:30 A/B 臂、15:23 depthcopy 臂、15:43 根修臂）；`depthcopyProxy float mean=0.9999 fracOne=0.795 fracZero=0`
> 登记：本文件；GAP-017/022/027 的登记表行随方案 2（接包 post 顶点程序）一并补

- **本次改了什么**（9 个代码文件，全部真机实测驱动）：
  1. **`FrameApi`**：mip 金字塔**每级独立 encoder**、深度代理翻转 pass**独立 encoder** —— 真机上
     「同一 encoder 内刚作为附件写完的纹理立刻被采样」读 0（lavapipe 不校验 ⇒ 此前全盲）。
     两处退化为「跨 encoder 全视图颜色采样」= 真机唯一被证明可用的模式（对齐 Vitrail 用
     vkCmdBlitImage 转移操作生成金字塔/深度镜像的**思路**，LGPL-3.0 零代码搬运）。
  2. **`ShadowStubs`**：桩深度 0.0 → **1.0**（GL 口径 = 无遮挡）。旧值出自 lavapipe「黑帧率」臂
     （节奏指标 X55），且当时深度代理恒零、体积分支根本不执行 ⇒ 判据作废；真机 0.0 被体积云
     读成全遮挡 = 黑纱幔。
  3. **`CloudsIntoGbuffer` + `MrtTerrainPass`**：云双画与长条修复 —— ① 实现 NeoForge
     `CustomCloudsRenderer` 钩子（装配期挂 `levelRenderState.customCloudsRenderer`，
     `renderClouds` 回 true 抑制原版那笔）；② 画前把 `cameraRenderState.viewRotationMatrix`
     压进 `RenderSystem.getModelViewStack()`、finally 还原（原版 clouds pass 在 executes 里
     自己压装配期矩阵，我方重放点此前读裸栈 ⇒ 云网格锚错 = 放射长条，h49l「位姿一半未查」结案）。
  4. **`TargetReadback` + `DepthSnapshots`/`DepthGlProxy` 访问器**：新增 `depthcopyLive/Snap0/Proxy`
     三条 **copy 路 float 回读**判据源。**判据纪律改立**：8-bit 灰度可视化（depthviz）对反向 Z
     （量级 1e-4）恒读 0，是无效仪器 ——「depthviz=0 ⇒ 内容零」的旧推断全部作废，
     深度内容一律以 float copy 回读为准。
  5. **`SkyIntoGbuffer`/`MrtTerrainPass.prepareGbufferViews`**（在途天空首帧修复，`mrt.skyPass`
     默认关 ⇒ 运行时不激活）+ `PackPickerScreen` 注释同步。
- **为什么改**：用户报「构建后视野全黑」。真机单变量对照（同构建同存档，仅 `chainSamplerLod0`
  开/关）锁定黑屏 = 链采样 mip 读到零金字塔 ⇒ 顺藤摸出同 encoder 附件→采样级联族。
- **遗留（下一刀，用户已拍板方案 2）**：链各级顶点仍用 `PackPostVertexAdapter` 合成，
  `sunVec/upVec/eastVec` 按零值供 ⇒ BSL 体积光/体积云沿退化轴渲染 = 屏幕双向镜像虚影 +
  固定间隔长条（2026-10-10 截图归因结案）。正路 = 像 Iris 一样跑**包自己的 post 顶点程序**
  + 标准全屏顶点缓冲；并加防复发闸（世界向量零值占位 = 启动 ERROR + 守卫测试）。
- **影响的文档**：仅本文件（GAP 表行随方案 2 提交）。
- **测试结果**：`compileJava` 通过；真机三臂取证（见上证据行）；完整套件跑于提交前（见下条追加）。
- **是否已提交**：是（与第 98 轮文档批量分两个逻辑提交）。

---

## 2026-10-10（九十八）— 🩹 从遗留 stash 抢救 X43–X49：纪律**有引用无定义**的断档补回

> **verdict = 工作区本无待提交改动（`origin/master..HEAD` 为空），本轮唯一实产出 = stash 里那份 X42–X49 正文的抢救**
> 证据：抢救前 `docs/07-CONSTRAINTS.md` 的编号从 `X41` 直接跳到 `X50`，而 `X51` 条目与 `CHANGE_LOG` 都在引用 X46/X49 ⇒ 引用悬空
> 登记：本文件 + `docs/07-CONSTRAINTS.md` + `docs/AGENT_CONTEXT.md`

- **为什么改**：`stash@{0}`（消息标「勿丢」，基线 `3548b08`，2026-10-04）里存着 X43–X49 的条文正文。2026-10-04 那次「删除 §9/§10 约 2187 行」的清理（`99e1904`）连带把这份定义抹掉了，只留下对它的引用 ⇒ 纪律表出现 `X42→X50` 空档，`X46`/`X49` 变成**只有别名、没有定义**的规矩。
- **本次改了什么**（纯增量，`git diff --numstat` = 19 插 0 删）：
  1. `docs/07-CONSTRAINTS.md`：从 stash 的 blob 里**逐字节取回** X43–X49 七条（含 `~~X47~~` 作废条）插回纪律表，并补回对应的七条自检项 —— 未手抄、未改写。
  2. `docs/AGENT_CONTEXT.md`：把原 §10.6 里 HEAD 已丢失、且**至今仍在用**的三条判读规则补进「取证纪律（持续有效）」块：X46 的可数日志行判据、黑色像素占比阈值（`>90%` 全黑 / `<60%` 有内容 / 对照组上界 ~20%）+「唯一哈希」尺子作废、`viewSlot` 观测面分工与 X49 单变量对照。
- **刻意**没有**做的**：不 `git stash apply`。stash 的 `AGENT_CONTEXT.md` 是 10-04 的**整份旧快照**，直接 apply 会把 10-05～10-09（h43–h52、GAP-023/027 收口、MCP 通道更正）的新内容改回旧写法 —— 与「只抢救独有内容」相反。被删的逐轮正文也没复活：它完整存在于历史 `3548b08:docs/AGENT_CONTEXT.md`，已在文档里留指针。
- **影响的文档**：`07-CONSTRAINTS.md`（纪律表 + 自检清单）、`AGENT_CONTEXT.md`（取证纪律块）、本文件。`13-GAP-REGISTRY.md` 未动（本轮不涉及任何 GAP 状态）。
- **测试结果**：编号连续性核对 `X41 → X43…X49 → X50…X55`（`X47` 以作废形式保留）；两文件 diff 全为新增；`git stash list` 确认 **stash 原样未动**（等用户复核后再决定 drop）；顺带把本地 `master` 从 `3c8fee8` 快进到 `f126d1a`（无分叉，`merge --ff-only`）。纯文档改动，未跑构建与客户端。
- **是否已提交**：见本次提交。


## 2026-10-09（九十七）— 🔧 Windows 车道 `runclient` 免参数化：`vulkanPreflight` 加 OS 分流 + `PrepareRun` 声明不兼容配置缓存

> **verdict = `.\gradlew.bat runclient` 无需再带 `-PvulkanSkipCheck=true` / `--no-configuration-cache`**
> 证据：`:vulkanPreflight` → 「Windows 车道：系统 loader 就绪 C:\Windows\System32\vulkan-1.dll」BUILD SUCCESSFUL；`runClient --dry-run` 任务图完整、配置缓存自动丢弃
> 登记：本文件；⚠️ `build.gradle` 属 §7.2 共享文件，本次为**用户点名的一次性改动**（非并行线越界，同 P0.2 守卫那段先例）

- **本次改了什么**（仅 `build.gradle`，两处）：
  1. `vulkanPreflight` 增加 OS 分流——新增 `vkIsWindows`（读 `os.name`）；守卫在 Windows 下改判 `System32\vulkan-1.dll`（路径配置期固化为 `vkWinLoader`，不在任务动作里读环境变量）。**Linux 分支一字未改。**
  2. 新增 `tasks.configureEach`，把 `prepare*Run`（NeoForge moddev `PrepareRun`）标 `notCompatibleWithConfigurationCache` ⇒ Gradle 只对本构建丢弃配置缓存，不再抛序列化错；`build/test/datagen` 仍照常用缓存。
- **为什么改**：① `vulkanPreflight` 是 env-1（Linux）专属守卫，判定键全是 Linux 路径且**无 OS 判定** ⇒ Windows 上恒假、误中止 `runClient`；② `PrepareRun` 无法被配置缓存序列化 ⇒ 每次 `runClient` 都得手写 `--no-configuration-cache`。
- **影响的文档**：仅本文件。`docs/18 §7.2` 的共享文件清单本条**未改**（留 env-1 同步）；旧记载「Windows 车道 runClient 必须带 `-PvulkanSkipCheck=true`」**作废**（旁路本身仍有效）。
- **测试结果**：`:vulkanPreflight` BUILD SUCCESSFUL（Windows 分支命中）；`prepareClientRun --dry-run` → `Configuration cache entry discarded because incompatible task was found`；`runClient --dry-run` 任务图完整。**未启动完整客户端**（本机显卡侧待用户自跑确认）。
- **是否已提交**：否。
- **对 env-1 的影响**：零行为变化——Linux 下 `vkIsWindows=false`，OS 分支不进，其下逻辑逐字保留；配置缓存声明对 Linux 是等价声明（不再需要那条 flag，非破坏）。

## 2026-10-09（九十六）— 🔬 GAP-027 云：用 MCP 复测黑帧率 ⇒ 云开/关都是 ~33%，**云不是黑帧源**（否证旧「67%」，归因到 GAP-026 残留档）

> **verdict = 三臂都在 [STORE_RESIDUE_NONE]（包默认档）下测：云开(A)=188/562=33.5%、云关(C)=12/36=33.3%，误差内相同 ⇒ 云不是黑帧源；周期 3 按 X55 不作正确性结论**
> 证据：`evidence/h52-clouds-blackframe-recheck.md` + `evidence/h52-images/`
> 登记：GAP-027 加 h52 行

### 为什么重测

h51 把 MCP 取证通道打通之后，顺手把 GAP-027「云进 gbuffer」此前靠 X11/残留档测出来的
「一开云黑帧 1/3→2/3」重测一遍。本轮**无源码改动**，纯取证。

### 测到了什么（三臂，MCP 钉观测面，机位 teleport_player）

| 臂 | cloudsPass | depthGlProxy | main 黑帧 |
|---|---|---|---|
| A | on | off | 188/562 = **33.5%**（周期 3） |
| B | on | on | 0/34（小样本，X55 不采信） |
| C | off | on | 12/36 = **33.3%** |

云开（A）与云关（C）在误差内相同 ⇒ **云不是黑帧源**，**否证** h49x 的 67%。
那条 67% 几乎肯定来自 **GAP-026 残留档**（`ADVANCED_MATERIALS=true` 的 8 附件档穿过 h45 起所有臂、
且改变了被测量本身）；本轮三臂都 `[STORE_RESIDUE_NONE]`，残留排除后云的影响消失。
A 臂周期严格 3，与 GAP-020 同形，按 **X55**（本机 lavapipe，黑帧率测节奏不测内容）不作云的正确性结论。

云确实进了 gbuffer（独立于黑帧率）：`c0@afterClouds > c0@afterTerrain` 426 帧里 371 帧为正，
`[GAP-027] 云管线替换生效`、`quadCount=10143 textureReady=true` 都在场。

### 改了什么

仅文档 + 证据（无 src 改动）：新增 `evidence/h52-clouds-blackframe-recheck.md` 与
`evidence/h52-images/`（glproxyON/OFF × up/horizon 四张）；`docs/13-GAP-REGISTRY.md` GAP-027 加 h52 行。

### 测试结果

`./gradlew test -PquickPlay` → **1088 项 0 失败**（无代码改动）。真机（lavapipe，隔离车道 run/h27）：
三臂经 MCP 驱动，`[STORE_RESIDUE_NONE]`、后端 Vulkan。

### ⛔ 仍未完成

1. **云的观感不对**（抬头天顶近黑、天上无可辨认云块）⇒ 换成包的 `gbuffers_clouds` 是 GAP-027 下一刀；`mrt.cloudsPass` 不翻默认（视觉未闭环 + 黑帧本机不可判）。
2. GAP-027 其余未接程序（实体/手/天气/阴影）；GAP-028 的 `renderStage` 逐 draw 供值。
3. GAP-023 的「像不像」仍待 Iris 同世界同机位对照帧。

## 2026-10-09（九十五）— ✅ GAP-023 看图判据达成：改用 **MCP 驱动游戏**，截图通道一次就通；并更正「本机 MCP 被权限拦」这条被反复抄的错误前提

> **verdict = 看得见水的两个朝向（yaw 270/315）截图里真有 vkdisp 画出来的水面与反射，同臂探针 `depthtex0 ≠ depthtex1` 每帧成立（16/16、21/21）；对照朝向（yaw 0）两者逐帧相同（0/18）且无水**
> 证据：`evidence/h51-gap023-visual-via-mcp.md` + `evidence/h51-images/`
> 登记：GAP-023 加 h51 行；`08-TESTING.md` 更正「MCP 被权限拦」口径

### 为什么能一次通

h50o/h50p/h50r 三臂的 F2 截图通道**静默失效**：`x11_input.py` 一路打印 `injected F2 OK`，
而 `run/h27/screenshots/` 是空的。根因是本机 `GetInputFocus` 恒回 `PointerRoot`（焦点由合成器持有）
⇒ F2 落不进 Minecraft 表面。X11 键注入这条路**依赖**合成器把键盘焦点留给 MC，而本机不满足。

🔴 **更正一条被反复抄进文档/脚本的错误前提**：`08-TESTING.md:379`、`x11_input.py:550`、
`h48_flicker_capture.sh:7` 都写着「本机 mcpfabric 驱动被权限层拦，所以走 X11 键注入」。
**实测不成立**：隔离车道客户端在跑时端口 `25600` 在听，`tools/mcp-drive.py`（stdio 直连 + 车道 token）
调 `get_self`/`run_command`/`set_time`/`set_weather`/`teleport_player`/`screenshot`/`describe_scene`
**全部拿到正确回执**。被 `evidence/mcp-fabric-integration.md` §6.1 真正拦的是「在**仓库外**第三方目录
跑 `./gradlew` 构建 mcpfabric」与「配置不热加载进当前 AI 会话」——两者都不等于「不能驱动游戏」。
⇒ `screenshot` 直读渲染目标、`teleport_player` 精确钉机位（yaw/pitch 可复核），都不依赖窗口焦点。

### 测到了什么（同臂，`time=6000`+`weather clear`，MCP teleport_player 定三机位）

| 机位 | describe_scene 水 ray | `c1@afterTerrain` 非0 | `depthviz0` | `depthviz1` | `0≠1` | 看图 |
|---|---|---|---|---|---|---|
| yaw 270 / pitch 0 | — | 16/16 | 3.269 | **3.635** | **16/16** | 右侧深色反光水面 |
| yaw 315 / pitch 15 | 23/48 | 21/21 | 4.022 | **4.588** | **21/21** | 前景水面 + 反射 |
| yaw 0 / pitch 0（对照）| 7/48 | 0/18 | 6.697 | 6.697 | **0/18** | 无水（正确退化）|

⇒ 机制（h50q）+ 看图两条判据都达成：BSL `composite.glsl:333 z1 > z0` 在真实渲染帧里成立。

### 改了什么

仅文档 + 证据（无 src 改动）：新增 `evidence/h51-gap023-visual-via-mcp.md` 与
`evidence/h51-images/{h51-water-yaw270,h51-water-yaw315,h51-nowater-yaw0}.png`；
`docs/13-GAP-REGISTRY.md` GAP-023 加 h51 行；`docs/08-TESTING.md` 更正「MCP 被权限拦」那段。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL / UP-TO-DATE，1088 项 0 失败**（无代码改动）。
真机（lavapipe，隔离车道 run/h27）：本轮三机位经 MCP 驱动取证，后端自报 `Using graphics backend Vulkan`、
档位 `[STORE_RESIDUE_NONE]`（包默认档）。未提交（待用户确认）。

### ⛔ 仍未完成（下一步入口）

1. **GAP-023 的「像不像」**：需要 Iris 侧同世界同机位对照帧才能逐像素判；本轮只证「水经我方管线进画面 + depthtex1 独立且被用上」。
2. GAP-027 其余未接程序（云/实体/手/天气/阴影）；GAP-028 的 `renderStage` 逐 draw 供值。
3. GAP-020 周期 3（X55：本机 lavapipe 不可判）。
4. 把 `x11_input.py` / `h48_flicker_capture.sh` 里「MCP 被拦 ⇒ 走 X11」的注释按本轮结论改过来（取证主通道改用 MCP）。

## 2026-10-09（九十四）— 🎯 GAP-023 机制端到端成立：真因是 GAP-027 接水时**把半透明层的写深度关掉了**（原版只有 WEATHER 关），修好之后 `depthtex0 ≠ depthtex1` 每帧成立

> **verdict = 看得见水的三个朝向里两个深度时刻每帧不同、看不见的五个朝向逐帧相同（同臂白送对照）**
> 证据：`evidence/h50n-pass-split.md` §六（h50q）
> 登记：GAP-023 加 h50q 行、GAP-027 加更正行

### 为什么之前 h50p 八朝向全等

不是机位问题。GAP-027 接水时给 `TRANSLUCENT` 层设了 `DepthStencilState(GREATER_THAN_OR_EQUAL, false)`
—— 水画了但**不写深度** ⇒ `depthtex1`（= 半透明之后的深度）在结构上永远等于 `depthtex0`
⇒ GAP-023 的三个时刻怎么拍都拍不出差别。核实原版 `RenderPipelines.java`：

| 管线 | 行 | 写深度 |
|---|---|---|
| `TRANSLUCENT_TERRAIN` | 393-399 | 没有 `withDepthStencilState` ⇒ DEFAULT = **开** |
| `TRANSLUCENT_TERRAIN_MULTIDRAW` | 400-406 | 同上 = **开** |
| `TRANSLUCENT_BLOCK` | 434-441 | 显式 `DEFAULT` = **开** |
| `WEATHER` | 1028-1032 | `(GREATER_THAN_OR_EQUAL, false)` = **唯一关的那条** |

⇒ 我们把 WEATHER 的状态安到了 TRANSLUCENT 上。两处错：① 破坏与原版等价（支柱①）；
② 让 GAP-023 永远关不掉。**这是本项目第三次把信念当判据写进测试**
（旧断言逐字：「水面本身不写深度」，没有任何出处）。

### 改了什么

1. **`pipeline/model/GbufferProgramPlan.java`** —— 决策表改成 `DEPTH_WRITE_OFF_LAYERS`（今天**空集**，
   注释写明原版四条管线的行号与「将来接 WEATHER 时把它加进来，而不是再给半透明加特例」）。
2. **`bridge/MrtTerrainPass.java`** —— `reportWaterGroup` 那句写死的「depth=测试开/写入关」
   改成由决策表算出来（自报不许比代码更自信）。
3. **`GbufferProgramPlanTest`** —— 断言换成原版事实 + 出处行号；顺手删掉我自己写的一条**恒真**断言
   （`writesDepth("WEATHER") && !contains("WEATHER")` —— 测试第一次跑就把它打红了，是我写错了而不是它错）。

### 测到了什么（h50q；配置与 h50p 逐字相同，只差这一处代码）

| 机位 | 样本 | `c1@afterTerrain` 非 0 帧 | `depthviz0` | `depthviz1` | 0≠1 帧数 |
|---|---|---|---|---|---|
| yaw 0 / 45 / 90 / 135 | 60/66/67/56 | **0** | 16.136 | 16.136 | **0** |
| yaw 180 | 27 | 0 | 11.370 | 11.370 | 0 |
| **yaw 225** | 32 | **32** | 4.931 | **4.975** | **32** |
| **yaw 270** | 34 | **34** | 3.269 | **3.635** | **34** |
| **yaw 315** | 37 | **37** | 2.657 | **3.073** | **37** |

⇒ 看得见水的三段里**每一帧**两个时刻都不同；看不见水的五段里**逐帧相同**
⇒ 对照是白送的：同臂、同码、同配置，差别只有「画面里有没有水」。
⇒ 顺带解掉 h50p 的悬案：`c1@afterTerrain` 全 0 不是「水的第二个输出没落」，是当时那几个朝向根本没有水。
⇒ **GAP-023 的机制至此端到端成立**：BSL `composite.glsl:333 z1 > z0` 第一次拿到两个可能不同的数。

### 影响的文档

`docs/13-GAP-REGISTRY.md`：GAP-023 加 h50q 行、GAP-027 加这条更正行；
`evidence/h50n-pass-split.md` 加 §六。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1088 项 0 失败**。
真机（A11）：h50q 一臂（闸门 A ✓ 14 键一致）。已提交（本地 master，未 push）。

### ⛔ 仍未完成

1. **GAP-023 的看图判据**：`packWater=true` + yaw≈270（看得见水）下画面是否更接近 BSL 语义
   —— 需要先修 **F2 截图通道**（h50o 整臂截图为空，注入器却报成功）。
2. 水接进来之后 `depthGlProxy` / GAP-022 那三条看图判据要重跑（水的画面内容变了）。
3. GAP-020 周期 3（唯一嫌疑 = uniform 块环相位；X55 已写明它本机不可判）。
4. GAP-027 其余未接程序（云/实体/手/天气/阴影）；GAP-028 的 `renderStage` 逐 draw 供值。


## 2026-10-09（九十三）— 🔬 给 GAP-023 加「两个时刻直接比」的探针，跑出来**三张深度图逐位相同** ⇒ 「时刻真的分开了」至今未被证明；顺手抓出自己仪器里的一个覆盖 bug

> **verdict = 机制按语义正确退化（本帧没画出半透明几何），关闭条件仍未达成；另记一条 F2 截图整臂没落地**
> 证据：`evidence/h50n-pass-split.md` §五
> 登记：GAP-023 加 h50o 行

### 改了什么

1. **`bridge/TargetReadback.java`** —— 新增 `depthviz0` / `depthviz1` 两个探针：把
   `DepthSnapshots` 的 0 号、1 号快照各画成一张 RGBA8 灰度图，与 `depthviz`（活深度）共用
   `vkdisp:pipeline/depthviz`。绘制本体抽成 `drawDepthAsGray(...)`。
2. 🔴 **自己复查抓出的 bug**：第一版两个标签**共用一张目标** ⇒ 后一次绘制在收数之前把前一次覆盖掉
   ⇒ 两个标签会**永远**读出同一个数，而「读数相同」正是本臂想问的那件事 ——
   仪器会自己造出假答案。改成**每标签一张**（`DEPTH_VIZ_TARGETS` 按标签建），
   与本文件头部那条「两个源同时回读进同一个缓冲会互相覆盖」同源。
   顺带把 `probeDepthAsGray` 也并进同一套按标签管理，删掉旧的单字段。

### 测到了什么（`packWater=true` + 扫四个朝向，闸门 A ✓ 16 键）

`depthviz {16.1362}` = `depthviz0 {16.1362}` = `depthviz1 {16.1362}`；同帧可比 325 帧里
**0≠1 的有 0 帧**；`c1@afterTerrain` 325 帧全 0。
⇒ 两条互相印证：**这一臂根本没画出半透明几何**（玩家 `y=96`、海平面 ≈63，pitch +20 看不到水）
⇒ 读数是「机制按 OF 语义正确退化」，**不是** GAP-023 的关闭条件达成。
`z1 > z0` 要真拿到两个不同的数，还差一个**看得见水面**的机位（h49k 用俯视有水的机位曾测到
`c1` 非黑 18.267% ⇒ 水能写，只是这里没拍到）。

✅ 新探针跑 325 帧不炸：日志里只有三条**已知**的音频/合成器异常（`text2speech` 的 flite、
`Failed to open OpenAL device`），没有一条来自 `vkdisp`。

🔴 另记一条仪器失败：这一臂 **F2 截图一张都没落地**（注入器逐字 `injected F2 (keycode 68) OK`，
而 `run/h27/screenshots/` 是空的）⇒ 探针通道有效、画面通道无效，是「注入报成功其实没生效」又一例。
**不许**把「截图为空」读成「画面黑」。修法候选：`chat` 之后补一次 `Return` 并等一拍
（**不能**补 `Escape` —— 会开暂停菜单，h48 踩过）。

### 影响的文档

`docs/13-GAP-REGISTRY.md` GAP-023 加 h50o 行；`evidence/h50n-pass-split.md` 加 §五。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1088 项 0 失败**。
真机（A11）：h50o 一臂。已提交（本地 master，未 push）。

### ⛔ 下一步（GAP-023 收尾的具体动作）

1. 先把**画面通道**修回来（`chat` 之后补 `Return`），再跑一臂**看得见水面**的
   `packWater=true`（机位要么走近水面、要么加大俯角），判据两条：
   `depthviz0 ≠ depthviz1` 与 `c1@afterTerrain` 非全 0。
2. 达成之后才谈 GAP-023 的画面侧关闭条件：BSL `composite.glsl:333 z1 > z0` 那支真的走到。


## 2026-10-09（九十二）— ✅ GAP-023 第二格：地形 pass 拆成两段 ⇒ `depthtex0/1/2` 三个时刻**各有一张真快照**；顺带立了 X55（黑帧率本机不可当正确性判据）

> **verdict = `packWater=false ⇒ taken=[0,2]/3`、`packWater=true ⇒ taken=[0,1,2]/3`，且三组深度/链读数与拆段前逐位相同；但本条仍不关（包有没有用上还没判）**
> 证据：`evidence/h50n-pass-split.md`
> 登记：GAP-023 第二格行、GAP-020 节奏行、`07-CONSTRAINTS.md` 新增 **X55**

### 改了什么

1. **`bridge/MrtTerrainPass.java`** —— 水段从 pass A 里抽出来，新增 `drawWaterSegment(...)` = **pass B**：
   颜色附件与深度**全部 LOAD**，段间发 blit。取点变成
   `pass A 关 → take(OPAQUE) →（水接进来）pass B 关 → take(TRANSLUCENT) → 云 → take(TOP_LAYER)`。
   为什么必须拆：**blit 是 encoder 命令，render pass 打开期间不能发**（h10 实测规则），
   而「不透明之后」这一刻正好处在原 pass 里面。
2. **`MrtTerrainPassWiringTest`** —— 守卫「不得对自建深度用 `OptionalDouble.empty()`」**收窄而不是删掉**：
   第一段仍必须 `of(0.0)`，续接段恰好允许一处 `empty()`（多于一次 = 又开了一段却忘了清屏语义）。
3. **`docs/07-CONSTRAINTS.md`** —— 新增 **X55**：本机是 lavapipe，黑帧率/闪烁率随「每帧 CPU 侧录制工作量」
   移动，**不许**用它自证好坏；§九 自检清单同步加一条。

### 测到了什么（h50n 两臂，闸门 A 各 ✓ 15 键一致）

- ✅ **机制**：`packWater=false ⇒ taken=[0, 2]/3`（没有半透明几何就**不声称** 1 号存在 ——
  正是 `DepthSnapshotsTest` 钉的那条性质）；`packWater=true ⇒ taken=[0, 1, 2]/3`
  ⇒ `depthtex1` 第一次成为与 0 号**不同来源**的图。
- ✅ **没弄坏别的**：`gbuffers_water outputs=2 declaredSlots=[0,1] samplers=8` 仍在（X39 的重绑约束还满足）；
  `depthviz {16.1362}`、`c0@chainStart {18.0286, 27.0276}`、`trace1deferred1:c4 {54.213}` 与拆段前**逐位相同**。
- 🔴 **黑帧率这一臂 0/173 与 0/174 —— 但明确不许读成「修好了」**。四臂并排：
  h50k 33.5% → h50l 66.5% → h50m 66.5% → h50n 0%，差异只有「每帧多做一点录制工作」，
  而**周期 3 从头到尾没动**。⇒ 被测量对**节奏**敏感、对**内容**不敏感，
  这是 GAP-020「环深 3 + 3 个 submit 在飞」那条嫌疑目前最强的一次正向支持；
  也是 X55 的由来。真要判 GAP-020，得换有真 ICD 的机器，或把「CPU 领先几帧」变成可读量。

### 影响的文档

`docs/13-GAP-REGISTRY.md`（GAP-023 第二格行、GAP-020 节奏行）、`docs/07-CONSTRAINTS.md`（X55 + §九 一条）、
新增 `evidence/h50n-pass-split.md`。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1088 项 0 失败**
（含被收窄的 `MrtTerrainPassWiringTest`；QD-04 棘轮仍绿 —— 拆段把大方法缩短了）。
真机（A11）：h50n 两臂。已提交（本地 master，未 push）。

### ⛔ 下一步

1. **GAP-023 的关闭条件**：`packWater=true` + **机位里有水** + 看图，判 BSL `composite.glsl:333 z1 > z0`
   现在是否真能读到两个不同的数（本臂机位 pitch=-60 没有水面，`c1@afterTerrain` 全 0 只是
   「没人写 ⇒ 停在清屏值」—— 这是解释不是判据）。
2. `cloudsPass=true` 时 2 号快照会含云深度（OF 语义下是对的），没测过。
3. GAP-020：换真 ICD 复测黑帧是否还在（X55 写明的两条出路之一）。


## 2026-10-09（九十一）— ✅ GAP-023 第一格落地：`depthtex0` 从「活深度」变成**不可变快照**，并且证明这一换逐位无损

> **verdict = 机制到位 + 忠实拷贝（h50l vs h50m 三个值集逐位相同）；`depthtex1/2` 仍同源 ⇒ 本条不关**
> 证据：`evidence/h50m-depthsnapshots.md`
> 登记：GAP-023 加 h50m 行与「下一刀」的准确形状

### 改了什么

1. **`bridge/DepthSnapshots.java`（新）** —— 三张 D32 快照（`depthtex0/1/2` 的三个时刻）、
   `slotOf(name)` 名字→时刻的**纯映射**、`has(int)` 纯谓词、`take()` 走原版
   `RenderTarget#copyDepthFrom` 同一条 `copyTextureToTexture` API。
   🔴 GPU 资源放进**嵌套类** `Resources`：数组字段 `GpuTexture[]` 的创建就要加载 GpuTexture，
   留在外层类的话测试连 `slotOf` 都调不动（类初始化先炸）。
2. **`bridge/MrtTerrainPass.java`** —— `ensureTargets` 里 `ensure()+beginFrame()`
   （必须在开任何 pass 之前：建纹理要新 encoder，h10 规则）；pass 关闭、云画完、翻代之前
   `take(OPAQUE, depthTexture())`；新增 `depthTexture()` 访问器（blit 的源要纹理本体，视图给不了）。
3. **`bridge/FrameApi.java`** —— `chainResolver` 的 `depthtex*` 分支改成**先要快照、拿不到才回退活深度**，
   加 `[GAP-023]` 一次性自报（说清 `taken=[0]/3`，即 1/2 号还没就位 —— 不说就会被读成已就位）。
4. **测试** —— 新增 `DepthSnapshotsTest` 6 条。

### 为什么，以及测到了什么

- 两条自报都在且说真话：`深度快照已建: 854x480 format=D32_FLOAT usage=7（3 张 = 三个时刻）`、
  `depthtex* 绑定源: depthtex0 ⇒ 快照=有（该时刻） | taken=[0]/3`。
- ✅ **无损**：h50l（链绑活深度）vs h50m（链绑快照）—— 两臂都带 depthviz 探针 ⇒ 仪器形状相同，
  才谈得上归因 —— `main {0.0, 57.7745}`、`depthviz {16.1362}`、`c0@chainStart {18.0286, 27.0276}`
  **三个值集逐位相同** ⇒ blit 忠实。
  （两臂黑帧率都是 ~66%，与不带探针的 h50k 33.5% 不同 —— 那是已登记的**探针扰动**，不是本条改坏的。）
- 这一格买到的：`depthtex0` 不可变 ⇒ 之后任何写深度的 pass（云开着 `cloudsNoDepthWrite=false`
  时就会写深度）都改不了链看到的深度。这条性质今天没有。
- 没买到的：`depthtex1/2` 仍同源 ⇒ `z1 > z0` 仍恒假、水接进来仍读不到真正的「半透明之后」。
  缺的机制说清楚了：**blit 是 encoder 命令，render pass 打开期间不能发**，而「不透明之后」
  这一刻正好处在地形 pass 里面 ⇒ 下一刀必须先把地形 pass 拆成两段。

### 影响的文档

`docs/13-GAP-REGISTRY.md` GAP-023（h50m 行 + 「下一刀」的牵连面清单）；新增
`evidence/h50m-depthsnapshots.md`。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1088 项 0 失败**（1082 + 6）。
真机（A11）：h50m 一臂（配置闸门 ✓ 15 键一致、进世界 30 秒）。已提交（本地 master，未 push）。

### ⛔ 下一步（GAP-023 的第二格）

拆 `MrtTerrainPass` 那个方法为「不透明一段 + 半透明一段」，中间两次 blit。牵连面逐条列在
GAP-023 的新行里（附件 LOAD/CLEAR 语义、`skyPrePainted`、水那段的重绑 X39、GAP-018 翻代时机、
探针取点）⇒ 单独一臂做、单独一臂判。

## 2026-10-09（九十）— 🔬 深度可视化探针上线：「黑帧那帧 z 处处 1.0」**也被否证**；但探针自身把黑帧率从 33.5% 推到 66.3% ⇒ 周期 3 的嫌疑收窄到 uniform 块环

> **verdict = 又排除一条（深度整体出局）+ 拿到一条正向信号（每帧工作量改变「3 个相位里几个坏」，改变不了 3）**
> 证据：`evidence/h50h-gap029-rerun.md` §十一（h50l）
> 登记：GAP-020 加 h50l 行

### 改了什么

**`bridge/TargetReadback.java`** —— 新增 `probeDepthAsGray()`，挂在既有的 `probeChainStart()` 末尾
（**`drawPostChain` 一行没动**，QD-04 棘轮仍绿）：探针开着时，把 `MrtTerrainPass.depthView()` 用
**已注册但从未执行过**的 `vkdisp:pipeline/depthviz`（`PipelineApi:193/499-503`、
`depthviz.fsh:13` 逐字 `texture(InSampler, vUv).r`）画进一张自建 **RGBA8** 目标，逐帧以
`depthviz` 标签回读。🔴 刻意不拿 `DepthGlProxy` 那张 R32F 喂探针 —— 探针字节布局按 RGBA8 算，
格式错会产出「看起来像数字」的垃圾（X37）。

### 测出两件事

1. **§九 的假设被否证**：169 帧里 `depthviz` **逐帧逐位相同 = 16.1362**（黑帧 112 帧与非黑帧 57 帧
   同一个数）⇒ 链每帧采到的深度是同一张、同一个内容 ⇒ `deferred1.glsl:358 if (z < 1.0)` 不是那个开关。
   顺带证明这张深度图**真的在被写**（16.136/255 ≈ 0.063 是合理的反向 Z 均值）⇒ **深度这条线整体排除**。
2. **仪器给出一条正向信号**：h50l 与 h50k 的唯一差异是「每帧多一个全屏 pass + 多一次
   `createCommandEncoder()`」⇒ 黑帧率 **33.5% → 66.3%**、间隔 `[3,3,3,…] → [1,2,1,2,…]`，
   **周期仍是 3**。⇒ 这是「**环深 3 + 3 个 submit 在飞**」该有的行为：每帧工作量决定
   「3 个相位里几个坏」，决定不了「3」这个数。

⇒ 排除清单到此：`frameCounter`、colortex 代次、深度资源、`isSky` 分支、云自身的 `cloudViewLength`。
**剩下唯一嫌疑 = uniform 块环的读写相位**（`MappableRingBuffer.BUFFER_COUNT=3` vs
`VulkanCommandEncoder:222-223` 允许 3 个 submit 在飞 —— 环深恰好等于在飞帧数，是「CPU 覆写 GPU
还在读的槽」的临界值）。

### 🔖 同时立一条仪器纪律

`depthviz` 这一格**会扰动被测量**。它只能用于「同臂内部：某个量在两帧之间变不变」这类问题，
**不能**用来比黑帧率的绝对值 —— 拿 h50l 的 66.3% 去和 h50f/h50i 的 33.8%/32.9% 比大小就是错用。
（这条与 h49u 的「仪器只解释多余的那一个零」、h48 §二十二 的「在场的信息没人读」同一族。）

### 影响的文档 / 测试 / 提交

`docs/13-GAP-REGISTRY.md` GAP-020 加 h50l 行；`evidence/h50h-gap029-rerun.md` 加 §十一（并把
「仓库现在的状态」挪到 §十二）。
`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1082 项 0 失败**。
真机（A11）：h50l 一臂（闸门 ✓ 15 键一致、进世界 30 秒）。已提交（本地 master，未 push）。

### ⛔ 下一步（唯一嫌疑的正面进攻）

量「CPU 领先 GPU 几帧」这件事本机没有直接通道，但**可以量它的后果**：给每条 builtins 环
在**写入时**与**绑定时**各记一次 `(ringId, slotIndex, frameCounter 值)`，同帧对比 ⇒
若绑到的槽不是本帧写的那个，就是环深不够/相位错。⇒ 这是一条**新取证臂**，不改产品行为。

## 2026-10-09（八十九）— 🔬 新加的「每帧代次索引」**否证了我自己上一条的推论**：黑帧与 colortex 代次无关（槽 0 恒不翻代），深度的「3 环」候选经源码核实也不存在

> **verdict = 两条候选被排除、周期 3 的嫌疑收窄到一处；下一刀已想清楚（depthviz 作为额外一级）**
> 证据：`evidence/h50h-gap029-rerun.md` §八/§九（h50k）
> 登记：GAP-020 加 h50k 行

### 改了什么

1. **`bridge/FrameApi.java`** —— 新观测面 `[GAP-020/gen]`：`requireChainReady`（每帧恰好一次、
   且正是「链开跑前」那一刻）抄一份各槽被读代，`reportChainGenerations()` 在链尾把
   「开跑前 / 链尾」两份打在**同一行**；**探针开着时每帧一行**，探针关着时保持原来 120 帧一行。
   🔖 打点在 `requireChainReady` 而不是 `drawPostChain` 里加一行：那一格已被 QD-04 棘轮顶满（>60 行）。
2. 文档：GAP-020 加 h50k 行；证据文件加 §八/§九。

### 为什么，以及测出了什么

h50j 说「链采到的图是 0，而探针读同一槽有内容」，我据此推「绑定的视图不是被写的那一张 ⇒ 去查代次」。
h50k 就是去量这个。结果**推论被否**：

```
chainFrame=283 开跑前 c0=1 c1=0 c2=0 c4=0 | 链尾 c0=0 c1=1 c2=0 c4=0
chainFrame=284 开跑前 c0=1 c1=1 c2=0 c4=0 | 链尾 c0=0 c1=0 c2=0 c4=0
```

⇒ 槽 0 的被读代**逐帧恒等于 1**（与「一帧内被写 6 次 = 偶数 ⇒ 不翻代」一致），槽 1 按预测周期 2 翻。
**代次连 2 相位都不动，谈不上 3 相位。** 同一臂复现黑帧 57/170 = 33.5%、间隔 `[3,3,3,…]`。

再排除一条（读源码，不花机时）：`RenderTarget.depthTexture` / `depthTextureView` 是**单个字段**
（`RenderTarget.java:27-28`，`resize` 整体重建 `:98-100`，`getDepthTextureView()` 直接返回 `:137-139`）
⇒ **「深度是 3 的环形资源」这条候选根本不存在**。

⇒ 现在唯一还站得住的「3」是 `MappableRingBuffer` 的 `BUFFER_COUNT=3`（uniform 块环，
与「3 个 submit 在飞」正好临界），但它解释不了 h50j 的形状：uniform 被覆写只会让值变旧，
不会让 `c0` 与 `c5` **同时精确为 0**。

### 下一刀（已想清楚，不再猜）

能同时解释「c0=0、c5=0、而 c4 逐位不变」的只有分支形状：`deferred1.glsl:358 if (z < 1.0)` ——
反射（`gl_FragData[2]` → colortex5）整个写在 if 里，`cloudViewLength`（`[1]` → colortex4）写在外面。
⇒ **黑帧 = 那一帧 `depthtex0` 处处读到 1.0**。
证它：把**已注册但不在本帧执行**的 `vkdisp:pipeline/depthviz`（`PipelineApi:193/499-503`，
片元 `depthviz.fsh:13` 逐字 `texture(InSampler, vUv).r`）在探针开着时作为额外一级画进一个
**RGBA8** 池槽逐帧取数，判据一句话：**黑帧那一帧的 depth 读数是不是恒等 1.0**。
🔴 不许拿 `DepthGlProxy` 那张 R32F 直接喂现有探针 —— 探针字节布局按 RGBA8 算，格式错会产出
「看起来像数字」的垃圾（X37 那一族）。

### 影响的文档 / 测试 / 提交

`docs/13-GAP-REGISTRY.md` GAP-020；`evidence/h50h-gap029-rerun.md` §八/§九。
`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1082 项 0 失败**（含 QD-04 棘轮）。
真机（A11）：h50k 一臂，配置闸门 ✓ 15 键一致、进世界 30 秒。已提交（本地 master，未 push）。

### ⛔ 仍未完成

上一条（八十八）的 ⛔ 1/2 已被 h50j/h50k 改写；仍然开着的是：**周期 3 的来处**、
**天空为什么是黑的 / 那片暗绿的云为什么照旧**、GAP-029 剩余未填名（7 个生物群系旗标 +
`darknessFactor` 一族 + `centerDepthSmooth`）、GAP-023 的 depthtex 三时刻快照、
以及「云是闸门但触发不在云自己的量上」这一格还没往下推。

## 2026-10-09（八十八）— 🔴 上一条里「阴影桩改 1.0」被自己的测量否掉：拆成两臂重测 ⇒ 桩**回到 0.0**（1.0 把黑帧从 32.9% 翻到 66.7%）；GAP-029 供值证明「进了 uniform 块且对黑帧中性」；h50g 两跑整臂作废

> **verdict = 一次提交里混了两个改动 ⇒ 无法归因；补两臂拆开之后：GAP-029 留、桩改回 0.0**
> 证据：`evidence/h50h-gap029-rerun.md`（新）、`evidence/h50g-shadowstub-ab.md`（§三 标记作废 + §四bis 记原因）
> 登记：GAP-029（三条新行：桩口径被否 / 供值到位且中性 / 取证闸门）

### 改了什么

1. **`bridge/ShadowStubs.java`** —— 深度桩清屏值 **1.0 → 回到 0.0**；注释重写为「这一格是测量选出来的，
   口径问题**仍未判**」。守卫测试改名 `stubDepthStaysAtMeasuredBest`，钉的是两臂数字，
   并额外断言源码里必须留着「未判」那句（不许下一轮把 0.0 读成「已确认无遮挡」）。
2. **取证脚本硬闸门**（`/tmp/opencode/h50h_gap029_rerun.sh`，本轮起为取证臂标准形状）：
   **A** 起跑前逐键回读 `vkdisp-client.toml` 与期望值表比对，任一键不符 ⇒ `exit 1`；
   **B** 日志出现 `Failed to load config vkdisp-client` 或采集窗 `样本=0` ⇒ `exit 1`。
3. **文档**：GAP-029 三行新增/改写、`evidence/h50g-shadowstub-ab.md` §三 作废 + §四bis、
   `evidence/h50h-gap029-rerun.md` 新建。主源码本轮**只有第 1 条**。

### 为什么

- **h50g 两跑整臂作废**：`lane_cfg.py "pack.optionOverrides="`（空值不带引号）落盘成
  `optionOverrides =` ⇒ night-config 抛 `ParsingException: Invalid value containing only whitespaces`
  ⇒ **FML 把整份配置按默认值重建**（那一跑实际 `shaderPack=""`、`mrt.terrain=false`、
  `pixelProbe=false`）。⇒ 上一条目里「阴影桩 A/B：0.0 ⇒ 云看不见；1.0 ⇒ 云出现形状」那句
  **不成立**，它读的是近乎纯原版的画面。
- **归因必须先拆臂**：上一条提交同时改了「GAP-029 供值」与「桩清屏值」，之后测到黑帧
  33.8% → 66.7%，两笔账混在一起。拆成 h50i（供值 + 桩 0.0）与 h50h（供值 + 桩 1.0）之后：
  `33.8%（都没改）→ 32.9%（只改供值）→ 66.7%（再改桩）` ⇒ **翻倍的是桩那一格**。
- **供值确实到位（不是只进 Java 的 Map）**：`[uniforms]` 逐 pass 自报里
  `shadowFade`/`timeBrightness`/`screenBrightness` 都不在 `unfilled` 名单里，
  而 `darknessFactor` 在 —— 有对照才有证明。
- 🔖 **本条第二次把信念当判据**：守卫测试的前身钉「0.0 = 反向 Z 远平面 = 无遮挡」，
  我改成钉「1.0 = 包按 GL 口径读 = 无遮挡」—— 两句都是推理，两句都被测量打回。
  现在钉的是数字，并把「口径未判」留在代码里。

### 顺带量出的新未填面（归 GAP-029 下一批）

`isDesert / isMesa / isCold / isSwamp / isMushroom / isSavanna / isJungle` 七个生物群系旗标
在 post1/post2/post5 上恒未填（BSL 用它们选天气色与植被色）；`darknessFactor` 一族仍未填（那族
按包设计 0 是安全缺省，已登记）。

### 影响的文档

`docs/13-GAP-REGISTRY.md` GAP-029 三行；`evidence/h50h-gap029-rerun.md`（新）、
`evidence/h50g-shadowstub-ab.md`（作废标记 + 原因）。

### 测试结果

`./gradlew test -PquickPlay` → **BUILD SUCCESSFUL，1082 项 0 失败**。
真机（A11）：h50h、h50i 两臂，配置闸门均 ✓ 15 键一致。

### ⛔ 仍未完成

1. ~~云混合为什么把整帧算成 0~~ —— **h50j 把这一条的优先级降了**：把 `postChainTraceSlots` 打开到
   `0,4,5` 之后，`deferred1` 自己算的 `cloudViewLength`（colortex4）在黑帧与非黑帧**逐位相同**
   （54.213 / 54.213），而它的两个**依赖输入**的输出同时精确为 0（c0 与反射 c5）
   ⇒ 云是闸门（`CLOUDS=0 ⇒ 0/171` 仍成立）但**触发不在云自己的量上**；
   同一批黑帧上，链开跑前的探针读 colortex0 是 **27.028 有内容**（且等于上一帧）。
   ⇒ 「链采到的图」与「探针读到的图」不是同一张 —— 下一步见第 2 条。
2. **下一刀 = 每帧代次索引**（h50j 直接指向它）：在「地形翻代后 / 链开跑前 / 每个链 pass 绑定
   `colortexN` 时」三处打同一口径的代次号，与探针帧号对齐。必须在**绑定处**打点 ——
   探针读 `POOL` 当场返回的纹理、绑定用 `poolView(slot)` 当场返回的视图，不同时刻取不可比。
   拿到这个数，周期 3 要么归零要么被排除，不用再在 AO / 云 / 曝光之间猜。
   （`frameCounter` 已排除；`depthGlProxy`、`AO_STRENGTH`、`TAA_MODE`、桩清屏值都只改变
   「3 个相位里几个落黑」，不改变周期。）
3. **天空为什么是黑的、那片暗绿等高线状的云为什么照旧**（h50i 截图：三名供上之后没救回来）。
4. GAP-029 剩余未填名（7 个生物群系旗标 + `centerDepthSmooth`）。
5. **阴影桩「哪一侧才是包眼里的无遮挡」口径未判**（三种可能，见 GAP-029 那行）。


### 追加（同一轮）：h50j —— 黑帧那一帧，链采到的**所有** colortex 输入都是 0

只把 `mrt.postChainTraceSlots` 从 `0` 改成 `0,4,5`（BSL `deferred1.glsl:616/621` 逐字
`/*DRAWBUFFERS:045*/`），复现逐字对上 h50i（`56/170 = 32.9%`、间隔 `[3,3,3,…]`）。
黑帧 vs 非黑帧：AO pass 的输出 `52.498 / 52.498`（恒等）、`cloudViewLength` `54.213 / 54.213`
（恒等）、颜色 `0.000 / 21.389`、反射 `0.000 / 50.355`、`c0@chainStart` `27.028（= 上一帧）/ 18.029↔27.028`。
⇒ 见上一条 ⛔ 1/2 的改写。证据 `evidence/h50h-gap029-rerun.md` §六。

🔴 同一轮记下一个**流程错**：派生臂脚本时用了 `sed -i` 改母本 ⇒ h50j 的 `DIR` 替换匹配不到，
整跑覆盖了 h50i 的原始日志（数字与截图已落库，结论不受影响）。纪律：**只能 `cp` 之后改副本**。


---

## 阶段摘要（第 84-87 轮，2026-10-09）

> **主题**：黑帧定位与云管线通道打通（GAP-022 / GAP-027）

| 轮次 | 主题 | 关键结果 | 状态 |
|---|---|---|---|
| 八十四 | GAP-022「按程序族分别供值」落地 | 周期性整帧黑归零：62/185 → 1/177 | ✅ |
| 八十五 | 撤回「云与链互相干扰」错误归因 | 云不是黑帧源，周期 3 仍未判 | 🔴 |
| 八十六 | frameCounter 修法修掉 AO 黑帧 | deferred1 黑帧从 67% → 0% | 🎯 |
| 八十七 | 撤回「根因已找到」→ 闸门是云混合 | GAP-029 三名已供 + 阴影桩 0→1；周期 3 仍未判 | 🔴 |

**核心进展**：
- **GAP-022** 按程序族分别供值（gbuffers 留引擎口径、composite*/deferred* 给 GL 口径）压住了周期性黑帧
- **GAP-027** 云管线替换通道第一次走通（PipelineModifier 官方路径），但云覆盖仅 ~1-2% 画面量级
- **GAP-029** 三个 OF 内建（shadowFade/timeBrightness/screenBrightness）首次供值
- **周期 3 黑帧**仍未定位（候选：MappableRingBuffer 深度相位 / frameCounter 奇偶抖动 / 金字塔节奏）


---

## 阶段摘要（第 77-83 轮，2026-10-09）

> **主题**：水的半透明 draw 落地、云进 gbuffer 的通道与裁剪根因、整帧黑钉到 AO（GAP-027 / GAP-028 / GAP-020）

| 轮次 | 主题 | 关键结果 | 状态 |
|---|---|---|---|
| 七十七 | `MC_RENDER_STAGE_*` 宏表进引擎（GAP-028 第一步） | `gbuffers_skybasic` 从「编译失败」变成真的产出 SPIR-V；供值那一半如实标未做 | ✅ |
| 七十八 | 撤回一条拦了我一整轮的旧结论（聊天注入其实是通的） | 「没有观测面」从来不是事实，是一条没人重测的旧判据；水的判据拿到，拿到的是缺陷 | 🔴 |
| 七十九 | 水终于写进 `colortex1` | 「水不落地」是假的，「读数断续」是真的 —— 后者就是 GAP-020 的周期性空帧 | ✅ |
| 八十 | GAP-027 第二刀落地一半：云的 pass 接上、draw 真的发了（9865 quad） | 对 colortex0 零写入 ⇒ 问题从「没发 draw」切成「发了没落地」 | 🟡 |
| 八十一 | 云零写入的根因判到底：**背面裁剪**（`CLOUDS` 裁、`FLAT_CLOUDS` 不裁） | 同一份云几何同一条 shader，只差 `withCull(false)` 一项就从零写入变落地 | 🔑 |
| 八十二 | 云「被剔光」修好：管线替换那条官方通道**第一次走通** | 云在生产档真的写进 colortex0；顺带修掉自己刚造的判据污染 | ✅ |
| 八十三 | 追了十几轮的「整帧黑」钉死在链的**第一级** `deferred1`，凶手是 **AO** | 周期严格 3；`AO=false` 一关，62/185 → 1/179 | 🎯 |

**核心进展**：
- **GAP-027** 走完三步：pass 接上 → 发了不落地 → 根因是背面裁剪 → 官方管线替换通道走通，云第一次进 gbuffer
- **GAP-028** 宏表进引擎，`skybasic` 产出 SPIR-V（逐 draw 供值仍在 GAP-028 名下未做）
- **GAP-020** 的「读数断续」第一次被正面刻画成**周期性空帧**，并钉到 `deferred1` + AO ⇒ 直接引出第 84 轮 GAP-022 的按程序族供值修法
- 🔖 **判据纪律**：一条没人重测的旧判据（「观测面拿不到」）拦住了一整轮；判据失效要重测，不许续抄


---

## 📚 归档说明

**本文件当前保留**：

| 层 | 覆盖轮次 | 日期 |
|---|---|---|
| 完整条目 | 九十九 ~ 八十八 | 2026-10-10 ~ 2026-10-09 |
| 阶段摘要 | 八十四 ~ 八十七 | 2026-10-09 |
| 阶段摘要 | 七十七 ~ 八十三 | 2026-10-09 |

**第 七十六 轮及更早（2026-09-29 ~ 2026-10-08）**：不在本文件内。去两处取——
`git show f126d1a:CHANGE_LOG.md`（压缩前的最后一份完整 97 条），以及 `evidence/h*.md`
逐轮取证报告（原始读数、截图、双臂数字都在那里）。

**为什么压缩**：97 条的日常取证日志对**当前开发主线**（着色器在 Vulkan 下的完整适配）
是噪音 —— 每一轮的「当前状态」已由三处唯一真源承接：`docs/13-GAP-REGISTRY.md`（缺口状态与裁决）、
本文件顶部条目（本轮做了什么 / 下一步）、`evidence/`（原始读数）。

**窗口规则**（2026-10-10 用户裁决：**阶段摘要 + 保留最近 10 条全文**）：最近约 10 轮留全文，
更老的按 4~7 轮压成一张阶段摘要表。登记新一轮会让全文临时超出 10 条（本次同一天并了两轮 ⇒ 现在 12 条），下一次压缩把最旧那条并进摘要层。

⚠️ **摘要表的用途边界**：只承担「这一串轮次在追什么、结论翻了几次」的线索。
**数字、判据、双臂对照一律回 `evidence/` 取**（`07-CONSTRAINTS` X37 禁止拿旧实测数字当现状）。
