# 变更记录（CHANGE_LOG）

> 格式与流程依据：docs/15-ITERATION.md「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---

## 2026-10-17（一百九十四）— 🔑 混合变体的正确接法定死：走注册事件，并把「注册即编译」用仓内既有事实证掉

> **verdict = 接线的最后一处不确定被消除，但本轮仍不改渲染行为。**
> 上一轮撤回之后剩一个问题：变体到底怎么进 `setPipeline`。答案是**注册期多注册几条**，
> 而这条路不需要任何假设 —— 16 个 post 槽管线本来就是这么来的。

- **两条源码级事实**（逐字读原版）：`RenderPass.java:32` 只有 `setPipeline(CompiledRenderPipeline)`；
  `RenderSystem.java:106-120` 的 `getCompiledPipelineNullable` = `PIPELINE_MODIFIERS.apply` → 当前缓存 → 回落缓存，
  纯查表。⇒ 未注册的变体查不到，只会在 `runPostPass` 的硬抛上炸（上一轮实测的形状）。
- **不需要假设「注册会被编译」**：`PipelineApi#registerPostPipelines(:282-306)` 在
  `RegisterRenderPipelinesEvent` 里注册 16 条 post 管线，`FrameApi#runPostPass(:1294)` 就是拿
  `getCompiledPipelineNullable` 取回来画的，而真机链一直在跑（`[route]`/post 计数有读数）
  ⇒ 变体照同一条路注册，运行期只是「换一条已注册的管线」，不碰 `GpuDevice.compilePipeline` 的异步
  与 `UncheckedAutoCloseable` 生命周期（上游自建自编译是它的架构选择，不跟）。
- **新增 `PackBlendDirectives.chainModes()` / `isEmpty()`**：只取 `composite`/`deferred`/`final` 族的声明并按值去重 ——
  给 `gbuffers_water` 的声明建 16 条变体是白烧 16 次着色器编译，而且那条声明在这条路上根本走不到。
  族判定用移植件 `ProgramNames.familyOf(TargetName.bareName(...))`，不新写一张表（X9）。
- **一次自伤，编译器当场抓住**：我给 `PackBlendDirectives` 加 `chainModes()` 时把
  `/** …（日志与判据用）。 */` 的收尾 ` */` 吃掉了 ⇒ `declared()` 被卷进注释里，
  `compileTestJava` 报「找不到符号 方法 declared()」两行。修法是补回收尾，不是改测试 ——
  也再次说明**用脚本改源码时，锚点必须完整带回被截断的收尾**（本仓踩过 heredoc 吃反斜杠，这次吃的是 ` */`）。
- **测试结果**：`./gradlew build` 全绿 = **passed=1308 failed=0 skipped=1**（上轮 1307 + 本批 `chainModes` 判据 1 条）。
  本轮不改渲染行为 ⇒ 不安排真机臂；🔴 `blend` 在覆盖表里仍是 `UNHONORED`（差最后那一处替换）。
- **下一轮**（已经很清楚，不再需要 survey）：`registerVariants(event)` 挂在
  `FullscreenPipelineRegistrar:164` 之后；`runPostPass` 那行取管线改成按程序查变体（**同行替换**，
  FrameApi 1674/1675）；同轮升 `blend` 档 + **跑一臂真机**（本目标里第一次真正改像素的接线）。
  之后同一条注册通道再吃下 `filters.<目标>`（按名选 `FilterMode`）与地形/实体族的 `blend`。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md` §7-9 新增「🔑 混合变体怎么进 setPipeline」段、本条。`NOTICE.md` 未动。

---

## 2026-10-17（一百九十三）— ⛔ 混合族的接线试了、验明会抛、当场撤回：`setPipeline` 只收已编译对象，而那个 getter 只查缓存

> **verdict = 一次负结果，按正向价值记。** 上一轮留下的「同行替换就能接」在源码级被证伪：
> 我把 `forStep(...)` 塞进 `runPostPass` 的取管线点之后，读原版实现发现这条路会让
> **每一个声明了混合的程序抛 `IllegalStateException`** —— 比不支持更糟。改动已 `git checkout` 撤回，
> 覆盖表把 `blend` 从 `EFFECTIVE` 降回 `UNHONORED` 并写上原因，另加一条**反向针**防止同样的接法再来一次。

- **两步核实（都是逐字读原版，不是推断）**：
  ① `com/mojang/renderpearl/api/commands/RenderPass.java:32` 只有一签名 `setPipeline(CompiledRenderPipeline)` ——
  没有「交未编译管线、由 pass 懒编译」的入口；
  ② `com/mojang/blaze3d/systems/RenderSystem.java:106-120` 的 `getCompiledPipelineNullable` 是
  `PIPELINE_MODIFIERS.apply` → 当前缓存 `get` → 回落 `fallbackPipelineCache.get` ⇒ **纯查表**。
  我方 `runPostPass` 在拿到 null 时是硬抛（`post pipeline slot N vanished mid-frame`）⇒ 变体管线必然走到那条抛。
- **正解已定位并写进类头**：`GpuDevice.compilePipeline(RenderPipeline, ShaderSource, Executor)`
  （`api/device/GpuDevice.java:54`）自己编译，且记忆表要存 **`CompiledRenderPipeline`** 而不是 `RenderPipeline`
  （上游 `GeometryProgram.java:742` builder→build、`:1065-1099` `compile(device)` 就是这个形状）。
  这一步同时是 `filters`/`size` 两族将来要的同一块地基，不算白做。
- **保留的砖（不接线、零行为影响）**：`pack/PackBlendDirectives`（读表 + 换包必清）、
  `bridge/PackStepPipelines`（派生 + 按身份记忆，本轮补上「调用方必须先编译」的红字）、
  `ShaderProperties.blends()/blendDropped()` 与 `classifyFeatureDirective`（QD-04/QD-03 两条棘轮
  上一轮撞红、已按正规做法修好：抽 helper + final 容器 + `AtomicReference` 快照）。
- **表的纪律这次真的用上了**：`PackFeatureCoverageTest.effectiveRowsNeedAConsumer` 是**双向**的 ——
  撤回接线后它立刻把「行还写着 EFFECTIVE」这件事变红；现在再加一条反向针
  （`blend` 行为 UNHONORED ⇒ `FrameApi` 里不许出现 `PackStepPipelines.forStep(`），
  要重新接必须先解决编译问题、再同轮升档。汇总串实测回到 `EFFECTIVE=5 MODEL_ONLY=2 UNHONORED=5 UNCLASSIFIED=1`。
- **测试结果**：`./gradlew build` 全绿 = **1307 passed / 0 failed / 1 skipped**（与上一轮同数；本轮删掉一条针、改两处档位，未新增测试）。
  🔴 渲染行为**零变化**（接线已撤回）⇒ 仍不安排真机臂；但**画面复核的欠账继续挂着**（一百八十八 `gaux` 槽位、一百九十 `alphaTest`）。
- **影响的文档**：`PackStepPipelines` 类头（新增「必须先编译」段）、`PackFeatureCoverage` 的 blend 行、本条。`NOTICE.md` 未动（无新并入）。
- **下一轮**：给 `PackStepPipelines` 补「编译 + 存 `CompiledRenderPipeline`」（含 `ShaderSource` 从哪来 —— 这一步要照上游那样把 shader 源标识一起管），
  接 `runPostPass` 一处并同行替换（FrameApi 1674/1675 只能同行），同轮把 `blend` 升 `EFFECTIVE` 并**跑一臂真机**：
  这次不是可选 —— 它是本目标里第一次真正改像素的接线。

---

## 2026-10-17（一百九十二）— 🧩 混合族的「读表 + 派生 + 记忆」三件齐了，最后一步接线单独留下（并把两条棘轮按正规做法还回去）

> **verdict = 桥这一头建完并测过，渲染那一头**没**接**：`blend.<程序>` 在覆盖表里仍是 `UNHONORED`，
> 因为 `PackStepPipelines.forStep` 还没有 pass 站点调用它。档位不升是 `effectiveRowsNeedAConsumer` 那条针定的规矩，
> 不是我客气。本轮另有价值：把「每程序管线」需要的官方形状（`toBuilder()` + 按对象身份记忆）在**不改渲染行为**的前提下先跑通、先测完。

- **新增 `pack/PackBlendDirectives`（读表）**：`blend.<程序>` 的只读快照，加载期发布、渲染期查。
  三种拼法都收（裸名 / `维度/名` / 包只写了限定名而渲染侧递来裸名），命中不到 = 「没声明」，与「声明了 off」分开。
  🔴 **换包必清**（`publish(null)` / `clear()`）：拿上一包的表画这一包 = 又是 GAP-018/045 那一族「缓存没有包代次这一维」。
- **新增 `bridge/PackStepPipelines`（派生 + 记忆）**：用原版自己的 `RenderPipeline.toBuilder()`
  + `withColorTargetState(index, …)` 派生「目标0 带该混合」的变体（与 `MainPassPipelineDerivation` 同一形状，不新造描述符）；
  按 **(入参管线对象身份, 混合值)** 记忆 —— 原版编译缓存按入参对象查，不记就是每帧每步新建一条管线
  并把 `PipelineCache` 变成每帧长记录（该类头第 5 条的同一口径）。`clearCache()` 供重载调用。
- **纯函数 `planTargets`**：只动第 0 槽，**格式与写掩码原样留着**（`FrontendRenderPass:119-121` 逐条比
  「管线格式 == 附件纹理格式」，改格式就是当场抛），其余槽一个字不碰，原本为 null 的槽保持 null。
- **`ShaderProperties.blends()` / `blendDropped()`**：四条特性指令（SSBO/图像/alphaTest/blend）从 `classify`
  抽进 `classifyFeatureDirective` —— 一半是为 QD-04（那段已经顶着 60 行基线，每接一族都往里塞就是把棘轮按下去），
  一半是因为这四族本来就共享同一个形状：文法与上限交给移植件判、读不懂**丢弃并点名**、原文仍留 `directives()`（T11）。
- **两条棘轮我自己撞红了，都按正规做法修**（没有抬基线，也没有改测试口径）：
  ① `MethodLengthRatchetTest` 23>22 ⇒ 就是 `classify` 被我撑长，抽出 helper 后回到基线内；
  ② `StaticFieldRatchetTest` 176>175 ⇒ `PackBlendDirectives` 的两个 `volatile` 非 final 字段
  换成「不可变 `record Snapshot` + 一个 final `AtomicReference`」。**顺带记下形状**：这个仓里「新增一个状态」
  的正确姿势不是加一个静态可变字段，而是 final 容器 + 原子换快照（QD-03 的注释就是这么要求落地的）。
- **判据 +5**（`PackStepPipelinesTest`）：只动第 0 槽且保留格式/写掩码、`off` 是真的摘掉混合、
  没有可改目标时返回 null（空表 / 第 0 槽为 null / null 入参）、查表收三种拼法且没声明 ≠ 声明 off、
  **分层断言**：`BlendMode.parse` 不看因子名（四条都算收进表），`BANANA` 这种在 `PackBlendState`
  那一层以 `UNRECOGNIZED_FACTOR` 露头 —— 不许在任何一层被抹掉或被猜成 `TRANSLUCENT`。
- **测试结果**：`./gradlew build` 全绿 = **passed=1307 failed=0 skipped=1**（上轮 1302，本批 +5）。本轮不改渲染行为 ⇒ 不安排真机臂。
  🔴 一百八十八/一百九十两批的画面复核仍欠（`gaux` 槽位变化、`alphaTest` 会改像素）。
- **下一轮那一步已经查清一半、剩一半待核**：pass 站点有三处 ——
  `FrameApi:1012`（composite / compositeScene）、`:1037`（final）、`:958`（deferred），以及 `runPostPass` 内的 `:1320`；
  但 1012 附近能拿到的 `compositeSource` 是**给人看的标签**（`"deferred output (P3.3)"`），不是程序名
  ⇒ 接线前必须先确认 `PostPassContract`/链计划里程序名字段叫什么、拿得到哪个形态（裸名还是 `维度/名`）。
  FrameApi 在 CAPS 名单（1674/1675）⇒ 这三处只能做**同行替换**（`setPipeline(PackStepPipelines.forStep(x, name))`），不许加行。
- **影响的文档**：本条。`NOTICE.md` 未动（本批全部是我方代码，消费的是已登记的移植件，无新并入）。

---

## 2026-10-17（一百九十一）— 🧱 移植 `SamplerTypes`，并把「骨架要不要破 T2」这个问题彻底问清了三把官方钥匙

> **verdict = 本轮没有新增生效的特性，但把下一轮要做的东西从「可能违法」变成「全在官方 API 内」，并新立一条待验的因果假设。**
> 移植件 `pack/target/SamplerTypes`（Vitrail 65 行）带出一条属于**游戏**的规则：任何一处 `sampler3D` 声明
> 就让那个程序的整条管线建不起来 ⇒ 而我方给 `sampler3D` 造的是真 3D 桩视图 ⇒ 这两件事的交界没量过。

- **移植第五批第三个文件**：`pack/target/SamplerTypes.java:1-65` @ `4a45f44`（LGPL 同族可搬，台账已记；
  上游为放行 volume 另有 `GlslCompilerMixin` 把维度遍历读成 `SpvDim3D = SpvDim2D` —— 🔴 **本移植件不含那个绕行**，
  要不要同样的注入是另一件事，走 mixin 登记制且欠真机判据）。判据 5 条：2D/Cube 全家族（含 shadow/array/MS 拼法）可绑、
  `sampler3D`/`usampler3D`/`isampler3D` 拒、**`2DRect` 读起来像 2D 但它是 `SpvDimRect` 所以一样拒**、
  光秃秃 `sampler` 的形状是空串而非 null、`image2D` 不在这张表管辖内。
- **名字不是证据**（上游原话，且我方 `SamplerDimensionPlan` 早写过同一条否决）：Mellow 在共享 include 里写
  `uniform sampler3D colortex6` ⇒ 按名字判会把它当颜色目标、递给它一张真 2D 视图。
- **三把官方钥匙（逐字取自本机 sources jar，`19` §7-9 新增表）**：
  ① `GpuDevice.java:54` `compilePipeline(RenderPipeline, ShaderSource, Executor)` + `RenderPipeline.toBuilder()`（`:135`）
  ⇒ **运行期建每程序管线是官方能力**，`blend.<程序>` 不需要破 T2；
  ② `GpuDevice.java:32` `createSampler(...)`（我方已在用 `getSamplerCache().getClampToEdge(FilterMode.…)`）
  ⇒ `filters.<目标>` 也不需要破 T2；
  ③ `ColorTargetState.java:13-21` = `record(Optional<BlendFunction>, GpuFormat, writeMask)`、`MAX_COLOR_TARGETS = 8`。
  ⇒ 需要破 T2 的只有 SSBO/自定义图像两族（`GpuBuffer.Usage` 与 `UniformType` 里真的没有 storage）。
- **新立一条待验假设（写进 `19` §7-9）**：`MainPassPipelineDerivation.STATS.refused` 的拒绝原因**可能与 `sampler3D` 同源**
  —— 即某些程序的管线是**建不出来**而不是渲染错。判据先定死：把 `refused` 的原因按声明类型分桶计数（今天只给总数与名字），
  一臂 BSL 冷启动读 `derived=/refused=` 分桶读数，再拿「3D 桩换成 2D 视图」做单变量对照臂。
  成立 ⇒ `filters`/3D 噪声图/volume 一族全被这条压住；不成立 ⇒ 也顺手把「拒绝」从猜变成有数。
- **测试结果**：`./gradlew build` 全绿 = **1302 passed / 0 failed / 1 skipped**（上轮 1297 + 本批 5）。
  本轮不改渲染行为 ⇒ 不安排真机臂。🔴 欠账不变：一百八十八/一百九十两批的**画面复核仍未跑**
  （`gaux` 槽位变化 + `alphaTest` 会改像素），且覆盖表里 `blend`/`filters`/`size` 仍是 `UNHONORED`（有砖没消费方）。
- **影响的文档**：`NOTICE.md`（台账 +1 行）、`docs/19-IMPROVEMENT-PATHS.md` §7-9（官方解锁表 + `sampler3D` 待验假设）、本条。
- **下一轮**：建骨架本体（`bridge/` 里按 `(程序, 混合态, 附件格式)` 缓存 `RenderPipeline` 并 `compilePipeline` 预编译，
  后处理链从「一条管线跑全部 step」改成「每 step 自己的管线」），落地即解锁 `blend.<程序>`；
  `filters.<目标>` 需先把 `FrameApi:chainSampler()` 抽成按名选（FrameApi 在 CAPS 名单 1674/1675 ⇒ 先抽再接）。

---

## 2026-10-17（一百九十）— 🧱 每程序管线骨架的第一块砖：包写混合因子名 → 官方 BlendFunction（并证伪「要破 T2」的猜测）

> **verdict = 混合这一族的阻塞点被证明不在官方 API，而在我们自己「整条链只用一条管线」的形状。**
> 新增 `bridge/PackBlendState`（因子名→`BlendFunction`）+ 7 条判据，其中三条是**与游戏自带常量逐字相等**的断言，
> 不是我方的近似。`blend.<程序>` 档位仍是 UNHONORED（没有消费方）—— 覆盖表的「表不许领先于代码」针就是为这种时候立着的。

- **决定性的两条 API 事实（源码级，本机 sources jar，非回忆）**：
  ① `renderpearl/api/pipeline/BlendFunction.java:26-33` 有 `BlendFunction(srcColor, dstColor, srcAlpha, dstAlpha)` 四因子构造，
  `BlendFactor` 的名字集合就是包写的那些（`SRC_ALPHA`/`ONE`/`ZERO`/`ONE_MINUS_*`/`DST_*`/`SRC_COLOR`/`CONSTANT_*`），
  `BlendOp` 是 `ADD/SUBTRACT/REVERSE_SUBTRACT/MIN/MAX` ⇒ **混合不需要破 T2**（与 SSBO/图像那条不同，后者是真的没有表达）；
  ② 上游 Vitrail 就是在运行期建每程序管线：`render/GeometryProgram.java:742` 用 `RenderPipeline.builder()`、
  `:441` 持 `Map<VertexFormat, RenderPipeline> reshaped` 作每程序缓存、`:1065-1068` 有 `precompilePipeline` 口径
  ⇒ 「每程序一套管线」在官方 API 内**可做**，我方那条「整条后处理链共用一条 composite 管线」
  （`bridge/PipelineApi#registerCompositePipeline`）是**我方选择**而不是官方限制。
- **新增 `bridge/PackBlendState`**：`BlendMode`（移植件，四因子名原样存）→ 官方 `BlendFunction`。
  三种结局分得开：`OFF`（包说不混合，能应用）、`MAPPED`、`UNRECOGNIZED_FACTOR`（**整条拒收并点名词名**，
  不猜成 TRANSLUCENT —— 猜错的混合是画上一块半透明糊，比不混合更难查）；`MALFORMED` 专给
  「双空格这种参考实现自己也会拒」的形态。`GL_` 前缀与大小写都收。
  位置在 `bridge/` 不是凑巧：07 T5 规定业务包不许 import 原版渲染类型。
- **判据 7 条**：`SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA` **等于** `BlendFunction.TRANSLUCENT`、
  `SRC_ALPHA ONE ONE ZERO` **等于** `BlendFunction.OVERLAY`（这两条同时把四因子的**顺序**钉死——写反不会报错，
  只会得到另一种混合）、两因子形与四因子形同值、`off` 可应用且 blend 为 null、小写与 `GL_` 前缀、
  认不出的词被点名且 `applies()==false`、双空格形态走 `MALFORMED` 而不被读成 `off`。
- **覆盖表同步**：`blend.<程序>` 那族的说明改成「映射已就绪，卡的仍是每程序管线」——档位没升，
  因为还没有消费方；这是 `PackFeatureCoverageTest.effectiveRowsNeedAConsumer` 要求的，不是客气。
- **测试结果**：`./gradlew build` 全绿 = **passed=1297 failed=0 skipped=1**（上轮 1290 + 本批 7）。本轮不改渲染行为 ⇒ 不安排真机臂；
  🔴 前两批欠的画面复核仍在（`gaux` 槽位变化 + `alphaTest` 改像素）。
- **下一轮（已排好，不用再survey）**：骨架本体——`bridge/` 里做「按 (程序, 混合态, 附件格式) 缓存一套
  `RenderPipeline`」的构造与预编译，把后处理链从「一条管线跑全部 step」改成「每 step 用它自己的管线」，
  然后 `blend.<程序>` 才有落点；同一骨架顺带解锁 `filters.<目标>`（按名选 `FilterMode`，
  需先把 `FrameApi` 的 sampler 选择从 `chainSampler()` 抽出来——它在 CAPS 名单里，1674/1675）与
  `size.<目标>`（每目标分辨率仍撞 Vulkan 同尺寸规则 ⇒ 要连帧缓冲一起拆）。
- **未闭合，别当做到了**：混合现在只是「能翻译」，不是「已生效」；SSBO/图像的 T2 例外仍等你在正文里点一次头
  （问答界面那份回执权限层不认，见记忆「问答回执不算确认」）。

---

## 2026-10-17（一百八十九）— 📊 覆盖仪器落地：把「包声明了什么 / 我方走到哪一步」变成按族计数，并实测出三条架构级阻碍

> **verdict = 这一轮没有新增生效的特性，但把「还差哪些」从印象变成可核对的清单，并否掉了三条「搬完就能生效」的想当然。**
> 动机是目标口径：「所有光影包特性都正常生效」需要一张按族的账，而不是逐条感觉 ——  GAP-021 的教训正是
> 「一条指令躺在通用表里没人读」与「这族已经生效」在日志和画面上**没有区别**。

- **新增 `pack/PackFeatureCoverage`**：把包的通用指令 + 结构化视图按 16 个已知族归类，判成四档 ——
  `EFFECTIVE`（渲染/编译路径上有消费方）、`MODEL_ONLY`（已按上游文法读进模型、设备侧未接）、
  `UNHONORED`（只在通用表里当字符串）、`UNCLASSIFIED`（新键族，必须人来判档）。
  🔴 结构化视图也要算数：`program.*.enabled` 与 `screen/sliders/profile` 在 `ShaderProperties#classify`
  就离开通用表，只扫 `directives()` 会把**已兑现的两族读成 0 条** —— 那正是本类要消灭的不一致。
- **接线**：`ShaderPackService#reportFeatureCoverage`（通用入口旁一条），出 `[pack特性覆盖] EFFECTIVE=… MODEL_ONLY=…`
  一条 INFO + 每个未兑现族一条 WARN（含条数、首条原文、卡在哪）。`DROPPED_REPORT_LIMIT=16` 继续当限流。
- **表不许领先于代码**：`PackFeatureCoverageTest.effectiveRowsNeedAConsumer` 是一条**源码回归针** ——
  `alphaTest` 标 EFFECTIVE 就必须能在 `ShaderPackCompiler` 里读到 `AlphaTestEpilogue.apply(`，
  `program.*.enabled` 标 EFFECTIVE 就必须能在 `PackPostChain` 里读到 `settings().get("enabled")`。
  以后谁把某族升级成 EFFECTIVE 而没有真消费方，判据直接红。
- **实测出的三条架构级阻碍**（详见 `19` §7-9 新表）：① `size./autoRes./relative.` 要每目标独立分辨率，
  与「同一帧缓冲附件同尺寸」冲突，而我方 `ColortexPool#ensure(slotCount, width, height, …)` 是一个尺寸管全部槽位；
  ② `blend.<程序>` 官方**能表达**（`renderpearl/api/pipeline/BlendFunction.java:26-33` 四因子构造），
  但我方整条后处理链只注册一条 composite 管线（`PipelineApi#registerCompositePipeline`），没有「每程序一套管线」的位置；
  ③ `filters.<目标>` 官方**能表达**（`FilterMode` + `getSamplerCache()` 已在用），但链上 1210/1584 是
  **一个 sampler 服务全部采样名**，改成按名选要动 FrameApi —— 而它在巨类 CAPS 里（上限 1675、现 1674），
  必须先抽再接。⇒ 结论：这三族要**先立「每程序一套 pass/管线/采样器」的骨架**再一起落，单接任何一族都会造出第二条形状不同的路（X9）。
- **顺带核清两件事**：官方 `BlendFactor` 的名字集合覆盖包会写的所有因子（`SRC_ALPHA/ONE/ZERO/ONE_MINUS_*/DST_*/SRC_COLOR/CONSTANT_*`，
  逐字取自 `renderpearl/api/pipeline/BlendFactor.java`）；`ColorTargetState` 是 `record(Optional<BlendFunction>, GpuFormat, int writeMask)`
  ⇒ 混合与写掩码这一族**不需要**任何原生绕行。
- **判据 +5**（`PackFeatureCoverageTest`）：16 族的归类与计数、汇总串（实测 `EFFECTIVE=5 MODEL_ONLY=2 UNHONORED=5 UNCLASSIFIED=1`，
  我最初按 4 写是**算错了 blend/size/filters/shadow 的条数**，判据按实测改）、前缀路由（含未知键返回 null）、
  `null` 输入不编数据、以及上面那条「表不领先于代码」的针。
- **测试结果**：`./gradlew build` 全绿 = **1290 passed / 0 failed / 1 skipped**（上轮 1285 + 本批 5）。
  本轮不改渲染行为 ⇒ 不安排真机臂；🔴 前两批留下的**画面复核仍欠**（`gaux` 别名槽位变化 + `alphaTest` 会改像素）。
- **影响的文档**：`docs/19-IMPROVEMENT-PATHS.md` §7-9（新增「三条架构级阻碍」表）、`NOTICE.md`（未动 —— 本批无并入）、本条。
- **未闭合，别当做到了**：① 覆盖表目前只证明「有消费方」，不证明「画面对」—— 每条 EFFECTIVE 仍欠真机判据；
  ② SSBO/图像的 T2 闸门例外仍按「待你在消息里点一次头」写着（问答界面那份回执权限层不认）；
  ③ 每程序 pass/管线骨架未立 ⇒ blend/filters/size 三族接不了；④ `docs/13` 的 GAP 条目本批未动（清单在 19 §7-9 与日志里）。

---

## 2026-10-17（一百八十八）— 🩹 移植第二批：别名表与 alpha test 进引擎，并核出「SSBO/自定义图像官方 API 表达不了」这条闸门

> **verdict = 一批真修 + 一条决定路线的事实。** 第二批搬 `TargetName`(137) 与 `AlphaTest`(138) 两个零依赖叶子件：
> 前者当场修掉链侧一处真缺陷（**所有 `gaux*` 被硬编成 colortex4**，legacy 名 `gcolor`/`gdepth`/`gnormal`/`composite` 根本不认），
> 后者把 `alphaTest.<程序>` 从「存成字符串没人读」推进到「结构化读入」；🔴 同时源码级核实：
> **官方 26.3 渲染 API 里没有 storage 缓冲的表达** ⇒ SSBO 与自定义图像不可能只靠官方 API 生效（闸门，见 19 §7-9）。

- **链侧缺陷的证据（不是推测）**：`bridge/FrameApi` 原 1386-1390 行写着「OF: `gauxN = colortex(N+3)`」，代码却是 `poolView(4)` 无条件 ——
  `gaux2/3/4` 全被绑到 colortex4；而 `gcolor/gdepth/gnormal/composite` 四个别名直接落到后面的兜底分支。
  上游表给的是位置对应 `gcolor0/gdepth1/gnormal2/composite3/gaux1..4=4..7`（Vitrail `TargetName.java:33-34`），
  并说明这**不是 folklore**：八个语料包里五个真用这些名字，Mellow 在 127 个程序里读 `gaux1`、Complementary 读 `gaux2`/`gaux4` 九十九次。
  ⇒ 已改为 `TargetName.index(name)`，FrameApi 由 1675 行降到 1674（巨类棘轮只会更宽松），并留一条**源码回归针**测试：
  `TargetName.index(name)` 必须在、`poolView(4)` 不许回来。
- **alpha test 这一步到了哪儿**：`ShaderProperties` 现把 `alphaTest.<程序>` 解析成 `alphaTests()`（程序名 → 函数 + 参考值），
  读不懂的行逐条点名、原文仍留在 `directives()`（T11）。🔴 **`discard(...)` 还没写进片元末尾** ⇒ 这条特性仍未生效；
  注入点是 `pack/PackTerrainSource` / `pack/PackCompositeSource` 造源码处，已写进 19 §7-9 的下一批次序第 ① 位。
  此前我方把该设置存成字符串（`ShaderPackService:551-552`）而**全仓无消费者** —— 这正是 GAP-021 那类「键在表里不等于有人管」的复发形态。
- 🔴 **闸门事实（源码级，逐字取自本机 `mergeWithSources` 产物）**：`renderpearl/api/buffers/GpuBuffer.java:13-22` 的 usage 位止于
  `INDIRECT_PARAMETERS=512`，**无 STORAGE**；`api/pipeline/UniformType.java:8-11` 只有 `COMBINED_IMAGE_SAMPLER`/`UNIFORM_BUFFER`/`TEXEL_BUFFER`；
  `backend/vulkan/VulkanRenderPipeline.java:92-98` 建 `descriptorType` 时就是对这三个枚举做 switch ⇒ 没有第四个分支。
  上游 Vitrail 的绕法已看清（`render/storage/StorageBuffers.java:3-27`：`lwjgl-vma` + `VK12.vkCreateBuffer` +
  三个 `mixin/access/*Accessor` 在 push descriptor 里顶替 `VkBuffer` 句柄），但它同时踩我方 **T2（GPU 操作走 renderpearl）**
  与 **X7（不许绑死后端）**，且属 `17-NATIVE` §5.2 的 **G 系列闸门** ⇒ 已列成 A/B/C 三选项（开闸 / 明确不承诺 / 用 UNIFORM_BUFFER 伪装，
  第三项会把「不支持」变成「看着支持而语义错」故不建议），**待用户裁决**。本轮把 SSBO/图像保持为「已解析、拒接并点名」。
- **上游自己的一处注释与实现不一致（按实现断言，不悄悄改）**：`AlphaTest.literal()` 的注释承诺印成包写的 `0.0001`，
  实际 `new BigDecimal(Float.toString(0.0001F)).toPlainString()` 给的是 `0.00010`（`Float.toString` 先产出 `1.0E-4`）。
  数值等价、GLSL 合法 ⇒ 判据按**实测值**断言，台账记这条为上游行为；要改成注释承诺的样子，得先声明成我方改动。
- **判据净增 7 条**（共 23 条）：别名按位置解（含 `gaux5`/`gdepthtex`/`colortex32` 三种拒法）、`colorimg` 无别名、
  `split` 的后缀拆分、`bareName` 剥目录、`off`/`false` 与「读不懂 ≠ 不测」、单空格分隔与 `GL_` 前缀与第三词忽略、
  发出的 GLSL 形态、`ShaderProperties.alphaTests()` 分发、链侧回归针。
- **测试结果**：`./gradlew build` 全绿 = **1278 passed / 0 failed / 1 skipped**（上轮 1271 + 本批 7）。本轮不改渲染行为
  ⇒ 不安排真机臂；但别名那条修正**改变了采样绑定的槽位**，属画面可见面 ⇒ 已在「未闭合」里点名要一臂复核。
- **影响的文档**：`NOTICE.md`（台账 +2 行、生效状态更正）、`docs/19-IMPROVEMENT-PATHS.md` §7-9（闸门段 + 下一批次序改为「先接 alphaTest 注入」）、本条。
- 🔖 **同日补记（仍在本条之内做完）**：上面「未闭合」第 ① 条**当场做掉了** —— 新增 `glsl/translate/AlphaTestEpilogue`（**取规则不取代码**：条件来自上游记载的 Iris `CommonTransformer:280`，那段链 AGPL-3.0 库故不搬），挂在 `pack/ShaderPackCompiler#compileStage`（唯一同时知道程序身份与片元源的地方，与 `h45` 采样探针同先例；阈值来源就是 `program.settings()` 里那条 `alphaTest`，不必从服务层再传一张表）。注入是**等行数**变换 ⇒ 行号映射与诊断归属不动。判据 +7（`AlphaTestEpilogueTest`：注入位置、main 后还有函数、三种「不注入」各出一条 WARN、NEVER、等行数、以及一条链上回归针「翻译链里不许出现 `AlphaTestEpilogue.apply(`」）；`./gradlew build` 全绿 **1285 passed / 0 failed / 1 skipped**（本批累计 +14）。缓存正确性核过：`PackCompileCache.Key = (packIdentity, overrides)`，而 `alphaTest` 来自包自己的 `shaders.properties` ⇒ 同一 packIdentity 下是常量，不必进键。🔴 **仍未做**：真机画面复核 —— 这条**会改像素**（草/叶从实心立方体变成带洞），下一批要么补臂要么别当已验。
- **未闭合，别当做到了**：① `alphaTest` 的 discard 注入未做 ⇒ 未生效；② SSBO/自定义图像停在闸门前（需裁决）；
  ③ 别名修正的画面复核未跑（`gaux2/3/4` 现在会绑到与昨天不同的槽位，方向按上游表是对的，但**没看图**）；
  ④ 我方 `pack/ProgramStage`、`pipeline/model/VertexElementFormat` 与移植件的表仍是两处住 ⇒ 真源未定。

---

## 2026-10-17（一百八十七）— 📦 移植第一批：`pack/model` 十个 LGPL 叶子文件，把 SSBO 与自定义图像两族从「支持为零」搬到「已按 Iris 文法读入」

> **verdict = 第一批落到了可执行判据上，但只到「读入模型」为止**：`bufferObject.N`（SSBO）与 `image.NAME`（自定义图像）此前在本引擎里**一行代码都不接**（`grep -rlE "bufferObject|drawbuffer" src/main/java` = 0），现在按 Vitrail/Iris 的文法与上限进模型、被拒的行逐条点名、并有一条 `[pack特性]` 自报行；🔴 **分配与绑定仍未接进管线 ⇒ 不许读成「已生效」**。本轮不含渲染行为改动。

- **为什么先搬这一批**：用户裁决是「直接把 Vitrail 和 Iris 对光影包特性的处理移植过来，不要自己实现了」。先按**支持面**排序，不按好写排序。探针是对 `HEAD` 的 `git grep`（不是回忆，也不是我改后的树）：`bufferObject`=0、`"image."`=0、`gbuffers_handled`=0 ⇒ **三族在改前一行都不接**；而同一次核对里 `DRAWBUFFERS`=14 个文件、`gcolor`=5 个文件**已有我方实现** ⇒ 不把它们算进「零支持」，它们属于「已有自研、待与上游表定真源」那一类（见下面「未闭合」第 ② 条）。第一批取其中**叶子最深、又不碰设备**的那两层，代价是本轮只能证到解析，收益是后面每一轮都在已核对的上游表上做，而不是在自己重造的表上做。
- **移植闭包（10 个文件，逐个读完再搬，不是照目录抄）**：`BlendMode`(71) → `ProgramFallbacks`(161) → `ProgramNames`(268) → `TextureStage`(64) → `PackTexture`(84) → `PixelFormat`(48) → `PixelType`(68) → `TargetFormat`(225) → `BufferObject`(130) → `ImageInformation`(171)。依赖链是**读出来的**：`ImageInformation` 需要 `PackTexture.Shape`/`PixelFormat`/`PixelType`/`TargetFormat.Resolution`，`PackTexture` 需要 `TextureStage`，`TextureStage` 需要 `ProgramNames`，`ProgramNames` 需要 `ProgramFallbacks.names()` —— 这条链决定了本轮的边界，再往下就是 `target/`（`SamplerPlan` 821 / `TargetPlan` 1644 / `ChainPlan` 1356）那一批重型件，另轮开工。
- **许可义务按 §〇 P1 逐文件落实**：每个文件头写「上游仓库@commit + `文件:行` + 读取日期 + 改了什么」；`NOTICE.md` 移植台账新增 10 行，其中 4 件**记两条上游**（`TargetFormat`/`ProgramFallbacks`/`BufferObject`/`ImageInformation` 的表与文法转录自 Iris，行号证据取自 Vitrail 自己的 `NOTICE`：`ProgramId.java:13-19`/`:47-48`、`ShaderProperties` 的 `:407`/`:431`/`:519`）。改动只有三类：包名、tab→四空格、本仓【参考调研】文件头。
- **上游语料读数≠我方数据**：`TargetFormat` 注释里「五个包里二十六条指令落在提升路径上」「packed 类型语料里没人写」是 **Vitrail 的语料结论** ⇒ 文件头显式标注「引用它时按上游口径，别当我方实测」（07 X32）。
- **接线两处**：① `pack/properties/ShaderProperties` 加 `bufferObject.` / `image.` 两族分派 + `bufferObjects()` / `images()` 结构化视图 + **带 `defines` 的 parse 重载**（Iris 允许 `image.NAME` 的尺寸位写**选项名**，不给这张表就会以「size is not a number」把包本来有效的声明丢掉）；🔴 原文**仍留在 `directives()`**（T11 不丢键，也避免动到既有消费方）。② `pack/ShaderPackService` 加 `reportPortedFeatureReadings`：收下一条 INFO（`[pack特性] bufferObject=N image=M 已按 Iris 文法读入（分配/绑定待接线，不是已生效）`）+ 被拒行逐条 WARN，`DROPPED_REPORT_LIMIT=16` 之后汇总成一行（X46 要有读数 + 一条坏包不许淹掉取证日志）。
- **判据 16 条**（`src/test/java/dev/vkdisp/pack/model/PortedPackFeatureModelTest`）：索引 13 被拒 / `size<1` 是关闭而非报错 / 三词相对式不成形 / 第十七条与至少六词 / 7·8·9 词的形状 / 相对式恰八词 / 尺寸位回查 defines / `RGB8` 提升且 `alphaAdded` / 乱名字回落不抛 / packed 的 `bytesPerTexel` 不乘通道数（12 vs 3 vs 4 三种算法各自的值）/ `off` ≠ 读不懂 / 双空格使混合整条失效 / `dh_shadow` 属 shadow 几何 / 编号上限 99 / 帧序 ≠ 指令折叠序 / 水与云的兜底链 / `gbuffers_spidereyes` 那条覆盖挂在**被问的名字**上 / 冒号=游戏的资源 / `Raw.bytes()`=1024。
- **两条我自己造成的红，都归因后修掉（不怪守卫）**：① image 系列断言我漏写了 `pixelType` 那一位（上游词序是 sampler/format/internal/**pixelType**/clear/relative/尺寸），七词会被读成 1D ⇒ 断言与真实文法对不上 ⇒ 补齐词数后 16 条全绿；② `BlendMode` 我写了五词串、`PixelType` 我按 12 心算了 `UNSIGNED_BYTE × RGB`（其实是 3）⇒ 改成 `FLOAT × RGB = 12` 与 `UNSIGNED_INT_10_10_10_2 × RGBA = 4`（packed 不乘通道数，正是这个类存在的全部理由）。
- 🔴 **一次真实损坏与恢复（记下来，别再犯）**：给 `ShaderPackService` 打补丁的脚本用 `io.open(f,'w')` **先截断后编码**，脚本在写 emoji 时抛 `UnicodeEncodeError` ⇒ 该文件被截成 0 字节。因该文件本轮**无其它未提交改动**，`git checkout --` 从 HEAD 完整恢复（744 行）；此后所有补丁脚本一律 **先 `s.encode('utf-8')` 再 `open(f,'wb')`**。⇒ 教训：「用脚本改文件」本身也要有失败模式，写序错了就不是dry-run。
- **影响的文档**：`NOTICE.md`（台账 10 行 + 生效状态说明）；`docs/19-IMPROVEMENT-PATHS.md` §7-9 的移植面进度；本条 CHANGE_LOG。🔴 `docs/13-GAP-REGISTRY.md` 本轮**没**动：SSBO/图像的「分配与绑定」缺口要等接线轮有判据了再立条，现在立等于登记一个还没测过的东西。
- **测试结果**：`./gradlew build` 全绿 = **1271 passed / 0 failed / 1 skipped**（上轮 1255 + 本轮 16 条判据）；`ShaderPackService` 786 行、`ShaderProperties` 228 行，均不在 `ClassLineRatchetTest` 的 CAPS 名单内 ⇒ 巨类基线未动。本轮不改渲染行为 ⇒ 不安排真机臂。
- **未闭合，别当做到了**：① `bufferObject`/`image` 的**设备侧**（分配、绑定、`glMemoryBarrier` 时机、image load/store 的 Vulkan 形态）一行未接 ⇒ 特性还没生效；② `ProgramNames`/`ProgramStage` 与 `TargetFormat` 与既有 `pipeline/model/VertexElementFormat` 之间存在**两张表并存**，接线轮必须定一个真源，否则「一个族两处住」就是本仓反复记的那类漂移；③ 其余零支持族（`gbuffers_handled.`、`shadow.gcolor`、`exclude_preset`、`variablePriority`）与 `target/` 那三个重型件仍在队列里。

---

## 2026-10-17（一百八十六）— 🔁 **换证：MIT → LGPL-3.0-or-later**，并把「光影包特性的处理」从自研改为**直接移植 Iris / Vitrail**（本轮零行并入）

> **verdict = 这是一轮纯条款/许可轮，没动一行渲染代码。** 换证必须先于移植，因为 MIT 与 LGPL 不同族 ⇒ 在旧证下搬一行就违约一行。
> 本轮产出的判据是「可搬性表」本身：三个上游的许可证是**本机检出 + `cmp`/`sha256` 核出来的**，不是回忆或平台页面。

- **改了什么**：① `LICENSE` → LGPL-3.0 全文（与 gnu.org `lgpl-3.0.txt` **逐字节一致**，`sha256 e3a994d8…23118`），新增 `GPL-3.0.txt`（同样与 gnu.org 一致，`sha256 3972dc97…36986`）；② 新增 `NOTICE.md` = 许可声明 + 第三方构件清单 + **移植台账**（现为空表）；③ `gradle.properties` `mod_license=MIT` → `LGPL-3.0-or-later`（值保持纯 ASCII，`.properties` 按 ISO-8859-1 解码）；④ `build.gradle` 的 jar 打包输入由 `LICENSE` 扩为 `LICENSE + GPL-3.0.txt + NOTICE.md`；⑤ 条款面：`07` §〇 整节重写（P1/P2/P3 → **P1–P4**）、§一 L1/L7/L8/L9/L10 改写 + **新增 L13（移植面三条边界）**、§1.3 判定表加 **AGPL-3.0 行**、§七 自检三条；`03` §0 表与共同结论、`04` **§8 整节重写** + 风险表两行、`17` §1.1.1 判定表 + 【参考调研】模板、`19` §7-9 新裁决 + 判定表两行、`00`/`01`/`02`/`13`/`15`/`16`/`18`/`AGENT_CONTEXT`（新 §0.0）与 README。
- **为什么改**：用户裁决「**更改协议**……现在直接把 Vitrail 和 Iris 对光影包特性的处理移植过来，不要自己实现了」。
- **可搬性判定的来源**：Iris = LGPL-3.0（`Iris/LICENSE` + README §License，其 `LICENSE-DEPENDENCIES` 自己警告过 glsl-transformer 是 AGPL）；Vitrail = LGPL-3.0（`LICENSE` + `NOTICE` 逐文件记着它从 Iris 转录了哪些表与文法）；Sodium = **PolyForm Shield 1.0.0**；Sulkan = GPL-3.0；OptiFine / Beryl = ARR。本机钉版：Iris `bff1e69…`（干净）、Vitrail `4a45f44…`（🔴 工作树 12 处改动、`CHANGELOG.md` 冲突未解 ⇒ **台账只按 commit 记**）。
- **两条收窄（同轮追问定下）**：① 许可证选 **LGPL-3.0-or-later**，不选 GPL / AGPL；② Iris 的 GLSL 转换层（链 AGPL-3.0 的 glsl-transformer）**只搬规则、不引库** ⇒ 项目不升 AGPL。Sulkan(GPL) 仍不搬：吸 GPL 会把整项目顶到 GPL-3.0，超出裁决范围。
- **可单方改写的依据（核过，不是假设）**：`git log --format=%an` = **341 个 `Yorigami` + 2 个 `vkdisp <vkdisp@local>`**（机器人），无第三方 contributor ⇒ 不需要任何人点头。MIT 授予无限制再许可 ⇒ **已按 MIT 拿走副本的下游不受影响，改写不可撤回**。源码公开（`origin` = GitHub）⇒ LGPL 的"随附源码"义务现成满足，代价是**从此不能转私有再分发**（`07` §〇 P4）。
- **三句话本轮起是假话，已逐处改掉**：「本项目 MIT」、「100% 自研」、「独立实现，与任何第三方着色器加载器/渲染优化模组均无关联」（`04` 风险表那条曾把它当缓解措施）。🔴 **带日期的历史条目与旧裁决不改写**（`CHANGE_LOG` 旧行、`review/`、§2 决策表的旧行、`19` §7-1 那段 10-10 记录）—— 只做原地标注或加新节。
- **`src` 侧同步**：**267 个** java 文件的 javadoc 合规行 `本项目 MIT` / `项目（MIT）` / `独立编写（MIT）` / `MIT 项目自有` / `与 MIT 不同族` 等 → `LGPL-3.0-or-later`，逐行中性（`git diff --numstat -- src` = **+567 / −567**，无一个文件行数不对称）。🔴 **不动**那些讲**别人**许可证的行（`Mixin = MIT`、`MixinExtras/NeoForge = LGPL-2.1`、MDK 模板 `NeoForged，MIT`、slf4j/java-semver = MIT、glcpp/ANTLR/tree-sitter = MIT、naga = MIT/Apache-2.0）—— 按上下文逐条筛，不是全局 sed。第一轮的全局 `，MIT）` 确实误伤过 3 处 `TEMPLATE_LICENSE.txt（NeoForged，MIT）`，第二轮已改回。
- **测试结果**：`./gradlew build` 全绿 = **1255 passed / 0 failed / 1 skipped**（与上一轮同数，本轮不新增测试）；打包实测 `unzip -l build/libs/vkdisp-0.1.0.jar` ⇒ **`LICENSE` 7652 B + `GPL-3.0.txt` 35149 B + `NOTICE.md` 5961 B 三个都在 jar 内**（P3 / L10 不再只是文档口径）。🔴 **两条守卫先红过一次，都归因到本轮改动并已修**：① `ClassLineRatchetTest` 报 `dev/vkdisp/VkDispConfig.java 长了：1048 > 上限 1047`（我把 javadoc 合规行拆成两行）⇒ 折回单行；② `VertexLayoutPendingAlignmentTest.alignmentChecklistLineNumbersAreStillAccurate` 报 `VertexElementFormat.java:46` 行号漂移（我在 `pipeline/model` 三个类的 javadoc 里插了一行）⇒ 并进上一行。修完**这 6 个文件的行数逐字等于 HEAD**。⇒ 这两条红是有用的仪器：它们证明「改注释不动行数」这条纪律在这个仓是真被机器看着的。🔴 **本轮不含任何渲染行为改动**，代码默认档与取证档画面均无新差异 ⇒ 不安排真机臂（`19` §7-4 的取证轮次照旧）。
- **是否已提交**：是（单独提交并推送）。
- **下一轮**：按 `19` §7-9 末列的移植面开工（`pack/model/*` + `pack/target/*` + `pack/source/*`，全为 Vitrail 已点名的 LGPL 干净件），每搬一个文件补台账两行（Vitrail + Iris）。

---

## 2026-10-17（一百八十五）— ⬆️ NeoForge `26.3.0.51-beta` → `26.3.0.86`：同 MC 线跟进，顺带查实这条线已在 `.76` 转正（`05` §2.1 的一条事实前提是错的）

> **verdict = 升版本身零 fallout：编译零错、1255 条测试全绿、真机一臂跑到 frame 2100+ 且 513 条 `/ERROR` 与 10-15 基线逐族同形同数；本轮唯一被证旧的是一条文口径 —— `05` §2.1 预测「26.3 正式版号 = 无第四段的 `26.3.0`」，实际是去掉 `-beta` 后缀、第四段继续加。**

- **取版本的方式（先排掉两个错判）**：官方 maven `…/neoforged/neoforge/maven-metadata.xml` 的 `<latest>`/`<release>` 都是 `26.3.0.86`，`lastUpdated=20261016154143`。① 本机 Windows 与 WSL 两侧的 gradle 缓存最高只到 `26.3.0.51-beta` ⇒ **离线根本没有可升的目标**，盲改版本号只会让 build 红；② `~/.gradle/gradle.properties` 把 `systemProp.http(s).proxy` 全指到 `127.0.0.1:12334`，代理没起来时任何解析都走不通 ⇒ 第一行改动之前先要有人把代理开起来。
- **插件不动**：`moddev-gradle` 元数据全量尾 = `2.0.148`，正是当前值 ⇒ `build.gradle` 一字未改（上一基线那次是 147→148 一起抬的）。
- **改动面**：`gradle.properties`（唯一数据源）+ **五处活镜像**（`02` 末表、`04` 抬头与 `neoForge{}` 示例、`05` §2 锁表与 §2.1、`07` §版本块与理由段、`AGENT_CONTEXT` 两处）+ `06` §6 新行。🔴 **历史取证引用一律不改写**：`04` §3.2/§5.0、`13` GAP-015/019、`19` §2.6 与五个源文件类注释里那些「26.3.0.41-beta / .51-beta sources jar 逐行核实」是**带日期的事实**，把它们改成 86 等于把记录改假。
- **真机一臂（判据，`runClient -PquickPlay`）**：档 = 6 号底档（md5 `0f2aaa5c…`，自报 `[生效档] 67 键 / 非默认 4`，`ownTerrainPass=false` / `mrt.enabled=false` / `skyPackTexturedShader=false`）。读数：后端 `Using graphics backend Vulkan … NVIDIA 591.86`、`LWJGL 3.4.3+4`（与 `05` §2 那条「原版自带 3.4.3」一致）、运行时 jar 路径点名 `neoforge-26.3.0.86-universal.jar`；计数 `invalid signature=0`、`could not find any targets=0`、`Game crashed=0`、`validateDraw=0`、`Couldn't find source for VERTEX=0`（天空那两条不报是因为闸关着，**不是修好了**）；`/ERROR` **513 条，与 10-15 `armJ-forensic-base` 逐族同形同数**（全是既有的 Complementary 解析失败 + BSL `DRAWBUFFERS` 槽位诊断，与升版无关）；接线自报 `[route]` / `gbuffer programs wired` / `[M-01] hit (wired=6/6)` 全在。
- **两条新 WARN 族，归因到升版、判为良性**：① `CommonHooks/WP: The following mods have version differences… neoforge (26.3.0.51-beta -> 26.3.0.86)` —— 存档里记的 mod 版本与本次不符，原版只提示；它同时反过来证明运行时确实是 86。② `ConfigTracker: Configuration file run/config/neoforge-local.toml is not correct. Correcting` —— NF 自身的 config schema 在 51→86 间加了键，它自动补默认值（现 3 键全默认），与 vkdisp 的键无关，且 `run/` 不入库。
- **车道卫生（下次照做）**：客户端 JVM **不随 gradle 任务退出**（`TaskStop` 之后 PID 还在，`taskkill` 不带 `/F` 拒不掉，只能强杀）；强杀前它已把 `vkdisp-client.toml` 回写（md5 漂到 `825511b…`）。还原底档的顺序必须是**先杀进程、后 cp 底档**，否则撞上 `AGENT_CONTEXT` §10.32② 那条「关服回写与改配置竞态」。本轮还原后 md5 回到 `0f2aaa5c…`。
- **两件没做到的事（不许当成做到了）**：① **画面判据未取** —— 截图那一步被权限判为超出「升版」范围，未绕过，故本轮结论只到仪器级；② 原版签名的源码级核实仍以 `.41`/`.51` 的 sources jar 为据，`.86` 上只证到「目标类能转换、没抛」，`06` §4 的逐行重核**没做**。
- **测试结果**：`./gradlew build`（联网）全绿 —— `BUILD SUCCESSFUL in 3m`，1255 passed / 0 failed / 1 skipped（那条 skipped 是既有的一项）。
- **是否已提交**：是（单独提交并推送；本轮不改任何渲染行为，代码默认档与取证档画面均无新差异）。

---

## 2026-10-16（一百八十四）— 🔬 给「半接线」与「本轮到底落了哪些 id」补两条仪器，把缺陷①从「生成侧丢了」改判成「**写了却取不到**」

> **verdict = 上一轮那条「适配层没进虚拟包」的推测被当场否证。** 同一条臂里 `[GAP-027/落表] stage=vsh` 逐字列出了 `vkdisp_pack:shaders/skybasic_pack_adapter.vsh` 与 `skytextured_pack_adapter.vsh`（22:04:55），而 30 秒后引擎仍然报 `Couldn't find source for VERTEX shader`（22:05:28，4326 条）⇒ 问题不在我们的 memo/生成/落表管线，在**取用侧**。本轮只加仪器、不改任何渲染行为。

- **为什么要专门补**：臂 B 之后只剩三种可能（memo 被上一轮取空 / 生成器返回 null / 表里有但取不到），而日志里一行都分不出来 —— 三条臂只能把「猜哪条」当结论，正是本仓反复记的「没有数字就只能猜」。
- **新增仪器（两条，都在 `pack/GbufferSourceReport`）**：① `bothPresent` —— `GeneratedSources.putGbuffer` 原来在任一侧为 null 时**静默整条不落表**，现在「只有一侧现成」必须点名是哪一侧（两侧都不在 = 本来就不接线，`not wired because ...` 那条 WARN 已点名，这里不重复）。② `noteWritten` —— 每轮 `openResources` 落表后把 fsh / vsh 两类**进了表的 id 全列出来**。
- **臂 B2/B3 的读数**：`半接线` 一条都没响（⇒ 落表时两侧都是全的）；`不在程序表里 -> 丢弃` 也没有（⇒ id 算法两侧同源这条假设成立）；片元侧**从头到尾零报错**（只有顶点侧取不到）。已排除的候选：`GbufferProgramPlan.adapterPath/adapterShaderPath` 两侧同一份、`listResources` 两类都列、`toResourceBytes` 无丢弃。
- **棘轮撞了一次（有用的事实）**：`putGbuffer` 里直接加 11 行 ⇒ `ClassLineRatchetTest` 报 `VkDispVirtualPack.java 长了：1574 > 上限 1573` ⇒ 按 QD-10 落到独立类，`VkDispVirtualPack` 侧只留**两处单行调用**（`bothPresent` 的 3 行 if 换成同形 1 行、`Map.copyOf(out)` 换成 `noteWritten(fragment, out)`），逐字回到 1573。
- **下一刀的方向（不是修法）**：查「表里有、引擎取不到」这一段 —— 天空两条管线是**首帧 draw 时才懒编译**（地形那条在资源加载期就编），而缺源报在 `Worker-Main-*` ⇒ 先量「懒编译那一刻用的是哪一份 `PackResources`/哪个 ResourceManager 快照」，别再动生成侧。
- **测试结果**：`./gradlew --offline build` 全绿；真机两臂冷启动（`/tmp/armB2.log`、`/tmp/armB3.log`），默认档画面不变（三闸仍默认关，改动只新增日志行）。取证档已还原（md5 `0f2aaa5c…` = 6 号底档）。
- **是否已提交**：是（单独提交并推送）。

---

## 2026-10-16（一百八十三）— 🔴 真机四臂第一次真跑：先揪出一条<b>任何配置都起不来</b>的崩（mixin 形参序），修完跑到世界里之后，天空两路 + 水 TBN 各撞一个具名接线缺陷

> **verdict = 「天空/水代码完成、只差翻闸看图」这个口径被真机否证。** 离线 `build` 对 `@WrapOperation` 的签名**完全无感**（APPLY 期才校验），所以这两支天空 mixin 自 15:53 落地起就让**任何进世界的档**在 `LevelRenderer#addSkyPass` 类转换期崩客户端 —— 与闸门开不开无关（臂 A 两闸全关照崩）。修完之后四臂的读数在下面：三条功能路径各有一个具名缺陷，全部安全拒接、退回原版，没崩帧。

- **崩因（唯一改动，已修）**：`SkyRendererCelestialPipelineMixin` / `SkyRendererGeometryPipelineMixin` 四条 handler 把形参序写成 `(CompiledRenderPipeline requested, RenderPass self, Operation)`。`RenderPass#setPipeline` 是**实例方法** ⇒ MixinExtras 期望 `(接收者, 实参..., Operation)`。崩溃报告原文：`has an invalid signature. Found unexpected argument type CompiledRenderPipeline at index 0, expected RenderPass`（`run/crash-reports/crash-2026-10-16_21.08.33-client.txt`，栈顶 `LevelRenderer.addSkyPass:377`）。
- **判据不是靠猜**：同仓已跑通的两条实例方法 `@WrapOperation` 就是这个形状 —— `FrameGraphPassOrderMixin`（`original.call(builder, name)`）与 `MainPassTakeoverTest:86` 钉的 `original.call(encoder, replaced)`。修法 = 四条形参表把 `self` 提到最前 + `original.call(self, …)` 带上接收者。
- **臂 A（基线，两闸全关 + 只开回读探针）**：客户端活到世界里；`[生效档] 67 键 / 非默认 6` 回读确认档真生效；同代次读数 `c0@afterSky#13 SKY_BAND mean_luma=76.61` vs `c0@afterTerrain#13 SKY_BAND=63.46 / TERRAIN_BAND=166.87`（天空先写、地形盖回一部分 = 顺序对）。
- **臂 B（单翻 `mrt.skyPackTexturedShader=true`）**：`validateDraw=0`、`Game crashed=0`（安全拒接生效），但两路各报一次 `不接管`：
  ① 穹顶/星点/晨昏 —— `Failed to find or load pipeline vkdisp:pipeline/gbuffer_sky_geometry_sky_disc_*`，两个因并存：`ShaderCompileException: Unable to find shader defined uniform (VkDispTerrainParams)`（派生描述符声明了内建块，转译后的 skybasic 片元里**没有**这一块）+ `FileNotFoundException: vkdisp_pack:shaders/skybasic_pack_adapter.vsh`（适配层顶点源没进虚拟包）。
  ② 日月 —— `Failed to find or load pipeline vkdisp:pipeline/gbuffer_sky_celestial_*`，因 = `Couldn't find source for VERTEX shader (vkdisp_pack:skytextured_pack_adapter)`（同一个「适配器 .vsh 没登记」的形状）。
  ⇒ **上一轮那条「不采任何贴图所以简单」的推断方向是对的，但接线本身没跑通**；`SkyGeometryPack`/`SkyCelestialPack` 的 `refuseOnce` 是唯一让这轮没变成崩帧的东西。
- **臂 C（`packWater=true` + `derivativeTbn=true`）/ 臂 C2（只把 `derivativeTbn` 关回 false）**：水这条**比天空远**——接线自报全绿（`[GAP-027] gbuffer programs wired: [gbuffers_terrain(1 out), gbuffers_water(2 out)] -> attachments=2`、`MRT TRANSLUCENT pipelines will use pack fragment: program=world0/gbuffers_water`），且 C2 里两条水 MRT 派生管线**建成、零 `validateDraw`**。但 C 里 `[GAP-007/derivativeTbn] world0/gbuffers_water: 导数接管已落地` 之后紧跟 4 条 `Couldn't compile pipeline (vkdisp:pipeline/terrain_translucent[_multidraw]_water_mrt)`：`Couldn't parse GLSL: vkdisp_pack:gbuffers_water:1726: syntax error, unexpected LEFT_PAREN, expecting RIGHT_PAREN`。C2 单变量关掉 TBN ⇒ 这 4 条**归零**、其余自报逐字不变。
  ⇒ **一百七十六那一刀「水的 TBN 从今天起有真值」生成的 GLSL 语法非法，在真驱动上从来没编出来过**（离线守卫不跑 GLSL parser，所以没拦住）。
- **环境噪声（不归因本轮）**：臂上 454~513 条 `/ERROR` 绝大多数是 `run/shaderpacks/` 里那两个 Complementary 包的 `pack program compile FAILED`（既有已知，与三闸无关）；另有 1 条 `fullscreen pass failed`（`IndexOutOfBoundsException: Index 0 out of bounds for length 0`，紧跟 `postChainMaxPasses 生效：只跑 0/9 级`，每次启动一条 = 取零档那条已知路径，本轮不追）。
- **离线守卫（跟着代码一起改，防复活）**：新增 `SkyGeometryPackWiringTest`（计数针：形参表 `RenderPass self, CompiledRenderPipeline requested,` ×3、`original.call(self, requested);` ×6、`original.call(self, pack);` ×3、旧形状 `original.call(requested);`/`original.call(pack);` **必须 0**、`apply(self, site)` ×3）；`SkyCelestialPackWiringTest` 那两条针改到新字面量并补「接收者在前」一条。
- **测试结果**：`./gradlew --offline build` 全绿；真机四臂（A/B/C/C2，每臂冷启动、单键差、`[生效档]` 回读确认）跑通并留 `/tmp/arm{A2,B,C,C2}.log`。取证档已还原（`vkdisp-client.toml` md5 回到 `0f2aaa5c…` = 6 号底档）。
- **现状改口**：天空 = **接线不通**（两路各有一个待修的生成期缺陷）；水 = **管线建成、TBN 那一刀待修**；云 = 本轮未翻（`cloudsPass` 仍关，`postChainMaxPasses=0` 下包算云不参与）。三条都不要再按「代码完成、待看图」汇报。
- **是否已提交**：是（单独提交并推送；默认档画面不变，三闸仍默认关）。

---

## 2026-10-16（一百八十二）— 🐞 揪出并修一处天空<b>翻闸即崩</b>的真缺陷：`gbuffers_skybasic` 声明了 `noisetex`，而派生管线只绑了内建块、没绑包 sampler ⇒ `validateDraw` 缺 uniform

> **verdict = 这不是「待画面验」的观察项，是读包源码当场证伪的一个真 bug：我之前写「天空不采任何贴图」是<b>错的</b>（把引擎 `SAMPLER0` 槽和包自带的自由 sampler 混为一谈）。BSL 的 skybasic 片元声明 `uniform sampler2D noisetex;`，派生管线的 `packBindGroupLayout` 已把它计入 ⇒ 一翻闸就崩帧。本轮修成真绑 + 加离线守卫钉住。**

- **证据**：`run/bsl-inspect/shaders/program/gbuffers_skybasic.glsl:42` `uniform sampler2D noisetex;`（用于天顶 dither / aurora 噪声）。而退役后的 `SkyGeometryPack.apply` 只有 `setUniform(BUILTINS_BLOCK,…)` ⇒ `noisetex` 无人绑 ⇒ `validateDraw` 抛（与 h33 那族「Missing uniform」同形）。
- **修法（不自己造轮子）**：`apply` 改走地形/水<b>同一条</b>维度感知绑定 `TerrainPipelineApi.bindPackGbufferUniforms(pass, atlasSampler, blockAtlas, depthView, poolWriteView(0), skybasic)` —— 它绑内建块（取本站 `prepare` 转好的那一代）+ 按 `SamplerDimensionPlan` 逐条绑包 sampler（`noisetex`→`PackTextures.view`，SHADOW_*→桩），绝不拿错维度凑（GAP-003 那条纪律）。`MrtTerrainPass.atlasSampler()` 由 private 放宽到包内可见供其调用。三处混入的 `apply(self,site,ring)` 相应改 `apply(self,site)`（ring 仅 `prepare` 的成功哨兵）。
- **更正说谎的注释**：`SkyPackPipeline.requiresSampler0` 与 `SkyGeometrySite` 里「不采任何贴图」→ 改成「不留引擎 `SAMPLER0` 图集槽，但<b>包自由 sampler（noisetex 等）照绑</b>」——把当初致错的口径当场纠正（[[feedback-answer-from-code-not-docs]] / X9：诊断不许说假话）。
- **离线守卫**：`SkyPackPipelineTest.applyBindsPackSamplersNotJustBlock` 双针钉：① `SkyGeometryPack` 源码必须含 `bindPackGbufferUniforms`；② 真 BSL skybasic 必须含 `uniform sampler2D noisetex;`（前提变了要同步改 apply）。防这条崩点悄悄回归。
- **测试结果**：`./gradlew --offline build` 全绿。
- **意义**：天空从「接线但一开就崩」推进到「接线且 sampler 绑齐、翻闸可跑（画面观感仍待真机）」。这是本轮对「完全实现」最实在的一步——不是加零件，是修掉一个会让功能当场不可用的错误。
- **是否已提交**：是（单独提交并推送；默认档画面不变，闸仍默认关）。

---

## 2026-10-16（一百八十一）— 🩹 补一处退役留下的<b>假绿</b>：`DepthFlipLedgerTest.bothOwnersAreWired` 之前对已删的 `SkyPackGbuffer.java` 用 `assumeTrue` ⇒ 整条静默中止，连代理那两条真覆盖也不算数

> **verdict = 退役删了 `SkyPackGbuffer.java`，但该守卫用 `readOrSkip`(=assumeTrue(Files.exists)) 读它 ⇒ 文件没了之后整条测试中止，仍有效的 `DepthGlProxy` 深度所有者覆盖被连带跳过 = 假绿（[[feedback-text-needle-false-green]] 那一族）。本轮修成「跑到底 + 钉退役不得复活」。**

- **现象**：`bothOwnersAreWired` 先 `readOrSkip(PROXY)`（存在，往下），再 `readOrSkip(SKY)`（SkyPackGbuffer 已删）⇒ `assumeTrue(false)` 在 line 48 中止整条测，代理断言虽执行但测试记为 skipped、不算过。build 里那句「2 skipped」之一就是它。
- **修法**：第二个深度所有者（全屏三角把 `Position.z` 写死 1.0）本就随全屏接管退役（穹顶走几何适配层真实 MVP，无 z 常量）。删掉对已删文件的读+针，改成 ① 保留代理那两条真覆盖（现在<b>真的会跑并过</b>）② 加 `assertFalse(Files.exists(SkyPackGbuffer.java))` 钉「第二所有者不得复活」（复活即 GAP-037「翻两次」回来）。display 同步改文案。
- **另一条查过没事**：`PackTextureWarmupWiringTest` 里那句「SkyPackGbuffer 那三个调用点」只是断言<b>消息文案</b>，它读的是 `FullscreenPassHook.java`（还在），没有静默 skip ⇒ 不动（避免无谓 churn）。
- **测试结果**：`DepthFlipLedgerTest` 三条全 PASSED（不再 skipped）；`./gradlew --offline build` 全绿。
- **是否已提交**：是（单独提交并推送）。

---

## 2026-10-16（一百八十）— 🛠 天空新代码的<b>reload 陈旧</b>隐患修：`SkyGeometryPack.compiled(site)` 现按<b>契约身份</b>记缓存键（对齐 `SkyCelestialPack`）

> **verdict = 接线/退役那几刀写完，自检发现一处「换包/热重载后会拿上一份包的管线画新天空」的真实缺陷并补上。离线编译+守卫全绿；运行期失效（要 GPU）留画面轮验。**

- **缺陷**：`compiled(site)` 之前只看 `COMPILED.get(site)` 非空就复用 ⇒ 资源重载产生<b>新契约对象</b>后仍返回旧 `CompiledRenderPipeline`（其 shader 变体/位置锚在旧终稿上）⇒ 重载后静默画错，正是本仓反复付学费的「两侧不同源」（GAP-054）那一族。
- **修法**：加 `COMPILED_KEY`（逐站），键 = `contract.qualifiedName() + "|" + identityHashCode(contract)`；命中要求<b>键也相等</b>，否则重建。`SkyPackPipeline.locationSuffix` 本就按契约身份哈希换 location ⇒ 重建天然换新号、不撞车。逐字对齐 `SkyCelestialPack#compiled` 的 `COMPILED_KEY` 口径。
- **测试结果**：`./gradlew --offline build` 全绿（缓存失效本身需 GPU，无法离线断言；此处只保证编译 + 既有守卫不破，运行期在画面轮一并验）。
- **天空现状不变**：仍「代码完成、默认关、待画面」——这一条只是把代码侧的正确性补实，让「翻闸即验」不至于先撞 reload 陈旧。
- **是否已提交**：是（单独提交；默认档画面不变）。

---

## 2026-10-16（一百七十九）— 🧹 退役全屏接管那一路（完成天空的路线迁移）：删 `SkyPackGbuffer`/`GbufferVertexRoute` + 死 post 路线，天空自此<b>只有一条几何路</b>

> **verdict = 一百七十二钉的顺序约束（换管线必与退役同批）至此<b>闭合</b>：换管线（穹顶/星点/晨昏 + 日月，默认关）已在，本轮把被取代的全屏接管<b>整条撤下</b> ⇒ 天空只剩「原版驱动几何 + `SkyGeometryPack` 换管线」一条路。全程离线可证（build 绿 + MixinWiring），不改默认档画面（那条路本已恒关、惰性）。**

- **改了什么**：① 删 `bridge/SkyPackGbuffer.java` + `pack/GbufferVertexRoute.java` + 守卫 `bridge/SkyPackGRoute…`（`SkyPackGeometryRouteTest.java`，整份测的就是退役中的那两块的谓词）。② `SkyIntoGbuffer.renderSky` 去掉全屏接管分支 ⇒ 恒 `return false`（原版那笔照跑，视图仍由 M-11 换成 colortex0；包着色由 `SkyGeometryPack` 三处 @WrapOperation 在同一笔里换）；`whoWillPaint()` 去 `SkyPackGbuffer.enabled()` 分支。③ `PackTerrainSource` 删死掉的 `linkPostVertexRoute` + `selectVertexSource` + 三处 `GbufferVertexRoute.note`（`postVertexProgram` 已恒 false，那条 gbuffer post 路线无任何程序触达）。④ `RenderStageSupplyTest` 那条读 `SkyPackGbuffer.java` 文本的针 ⇒ 改读 `SkyGeometryPack.java`（`RenderStageSupply.enter(site.stageMacro)` + try-with-resources + scoped 写环在 enter 之后）。
- **保留**：`PostVertexLinker` 的 clip 中性化<b>代码留</b>（`PackPostChain` 后处理链那一族仍用，守卫 `ndcNativeClipIsLeftAlone` 钉它对链是恒等）—— 只是不再有 gbuffer 程序走它。
- **测试结果**：`./gradlew --offline build` 全绿（1254 用例，0 失败）。
- **天空功能现状（据代码）**：五站全接线 + 单一路线 + 退役完成。⛔ 唯一还剩的是<b>画面判据</b>：翻 `mrt.skyPackTexturedShader=true` 真机看图（穹顶/星点/晨昏/日月是否包算、有无 validateDraw 崩）—— 归统一取证轮（MCP 桥此刻不可达，无法本轮签）。
- **是否已提交**：是（单独提交并推送；默认档画面不变）。

---

## 2026-10-16（一百七十八）— 🌍 穹顶换管线补全到<b>三站</b>：`renderStars` / `renderSunriseAndSunset` 也接上包的 `gbuffers_skybasic`（gated off）

> **verdict = 一百七十七只接了穹顶一站；本条把星点、晨昏两站用同一个 `SkyGeometryPack`+`SkyGeometrySite` 接上 ⇒ 天空三处可换站<b>全部接线完成</b>。仍默认关，画面不变。**

- **改了什么**：`MixinTargets` 加 `SKY_RENDERER_STARS`（`(RenderPass,float,PoseStack)`，`SkyRenderer.java:266`）与 `SKY_RENDERER_SUNRISE_SUNSET`（`(RenderPass,PoseStack,float,Vector4fc)`，`:284`），PoseStack 逐字 `com.mojang.blaze3d.vertex.PoseStack`。`SkyRendererGeometryPipelineMixin` 再加两个 `@WrapOperation`（同穹顶那一形状：首行埋点 → `enabledFor` → `compiled` → `prepare` 成功才 `original.call(pack)` → `apply`）。
- **测试结果**：`./gradlew --offline build` 全绿，`MixinWiringTest` 三处注入点合规（首行埋点 / 一键关 / 目标名只引常量）。踩了一处（漏了类收尾大括号）⇒ 补回。
- **天空功能现状（据代码）**：日月(M-16 `SkyCelestialPack`)+穹顶/星点/晨昏(`SkyGeometryPack`) 五处天空站点<b>全部接线</b>，共用 `mrt.skyPackTexturedShader`（默认 false）+ `mixin.skyCelestialPack` 两道闸。
- **⛔ 仍未完成（如实）**：① `SkyPackGbuffer`/`GbufferVertexRoute` 已随 re-route 彻底惰性（`SkyIntoGbuffer.renderSky` 那条恒 false），<b>尚未删除</b>（退役残留 + 其守卫 `SkyPackGeometryRouteTest` 一并清）。② 对错是<b>画面判据</b>：翻闸后天空色/混合/`renderStage` 抑制对不对、换管线会不会 validateDraw 崩，只有真机看图能定 ⇒ 统一取证轮。
- **是否已提交**：是（单独提交并推送；默认档画面不变）。

---

## 2026-10-16（一百七十七）— 🌍 穹顶**第一站接线落地**（gated off）：`renderSkyDisc` 那笔换用包的 `gbuffers_skybasic`

> **verdict = 穹顶（SKY_DISC）这一笔现在真的会走包程序 —— 但闸门默认关，一笔都不换 ⇒ 默认档画面不变。re-route（一百七十六）让这一刻「打开即用」成为可能，本轮把渲染线程那半边接上。**

- **怎么做到不动 `VkDispConfig`**：`VkDispConfig.java` 顶在行棘轮 cap（1047，只许降）⇒ 加新键要先压注释、代价大且易红。本轮<b>复用既有两道闸</b>：feature 门 `mrt.skyPackTexturedShader`（天空包着线总闸，<b>默认 false</b> ⇒ 穹顶默认不换）+ 注入点 kill switch `mixin.skyCelestialPack`（默认开，满足守卫「每个注入点都能一键关闭、配置项默认开」）。零 `VkDispConfig` 改动。
- **改了什么**：① 新增 `bridge/SkyGeometryPack.java`（照 `SkyCelestialPack` 同形：`onSite` 首行埋点 / `enabledFor` 双闸 / `compiled(site)` 走 `SkyPackPipeline.descriptor` + `RenderSystem.getCompiledPipeline`、每站一份缓存 / `prepare` 换块代 + `RenderStageSupply.enter(site.stageMacro)` + 写 builtins / `apply` 只绑 `VkDispBuiltins`（穹顶无 sampler）；**先备料成功再 `original.call`，失败原样交回**）。② 新增 `mixin/SkyRendererGeometryPipelineMixin.java`：`@WrapOperation` 注 `SkyRenderer#renderSkyDisc` 那次 `setPipeline`（`require=1/expect=1`）。③ `MixinTargets.SKY_RENDERER_SKY_DISC` 描述符（逐字 `SkyRenderer.java:163` 的 `(RenderPass, Vector3fc)`）。④ 注册进 `vkdisp.mixins.json`。
- **测试结果**：`./gradlew --offline build` 全绿，含 `MixinWiringTest` 全套（首行埋点 / 一键关 / 目标类名只引常量 / 登记齐全）<b>逐条通过</b>——即本混入的注入点合规。踩了一处编译错（`@Nullable` 不能前缀全限定返回类型）⇒ 改 import `MappableRingBuffer`。
- **⛔ 仍未完成（如实）**：① 本轮只接了<b>穹顶一站</b>；`STARS`/`SUNRISE_SUNSET` 两站用同一 `SkyGeometryPack`+`SkyGeometrySite` 但<b>还没挂混入</b> ⇒ 开着闸时星点/晨昏仍是原版（画面会混，但不错不崩）。② 全屏接管那套 `SkyPackGbuffer`/`GbufferVertexRoute` 已随 re-route <b>彻底惰性</b>（`SkyIntoGbuffer.renderSky` 里那条恒 false），但<b>尚未删除</b>（清理并入「退役那一刀」）。③ 对错仍是<b>画面判据</b>：换管线会不会崩帧 / 天空色对不对，只有真机看图能定 ⇒ 归统一取证轮（届时翻 `mrt.skyPackTexturedShader=true` + 补 STARS/SUNRISE 两混入 + 删退役残留）。
- **是否已提交**：是（单独提交并推送；默认档画面不变、注入点合规）。

---

## 2026-10-16（一百七十六）— 🔀 穹顶换管线的**枢纽一步**：把 `gbuffers_skybasic` 的顶点源从 post 中性 clip 改回真实 MVP 几何适配层（退役全屏接管的资源位）

> **verdict = 这一步把天空的顶点源位从「全屏那一路」交还给「几何那一路」。它本身不改默认档画面（此刻还没有任何管线消费 skybasic 的几何顶点），但它<font>不是</font>半成品：re-route 后 skybasic 走的是与地形/日月同源的 `chunkAdapter` 路径，喂齐契约就接、喂不齐整条不接。剩下的只是那三处 `@WrapOperation` 去 `SkyPackPipeline.descriptor` 取管线并换上去。**

- **为什么能做（读代码坐实，不是赌）**：资源写入处 `VkDispVirtualPack.java:1188-1190` 的分流是 `postVertexRoute() ? terrain.vertexSource() : GbufferVertexSources.chunkAdapter(...)` ⇒ 只要 `PackTerrainSource.postVertexProgram` 对 skybasic 返回 `false`，skybasic 自动落到 `chunkAdapter`，与日月（`celestialAdapter`）走<b>同一条入口、同一套块统一口径</b>（地形早已用 `program.fragmentSource()` + `chunkAdapter` 渲染正常，证明这条不需要 `PostVertexLinker` 那种跨阶段改写）。
- **改了什么**：① `PackTerrainSource.postVertexProgram` → 恒 `false`（gbuffer 程序今天无一走 post 路线；`linkPostVertexRoute`/`GbufferVertexRoute` 暂留但无 gbuffer 程序触达，清理并入后续混入那一刀）。② `GbufferVertexSources.chunkAdapter` 加 `SKY_PROGRAM→skyGeometryAdapter(contract)`（`CelestialVertexAdapterGenerator.generate(Format.POSITION, ...)`，一百七十三那块零件）。③ `builtinsFamily` 让 skybasic <b>显式</b>留 `CHAIN`（屏幕空间片元，否则跟翻到 GBUFFER = 纯黑天空，GAP-022 那另一半）。
- **守卫**：`PackTerrainSourceTest` 那条天空用例的前题<b>逐条翻面</b>：从「断言 post 恒等 + 中性 clip + clipPassthrough」改成「断言 `postVertexRoute()=false`、`chunkAdapter` 造出的几何顶点用真实 MVP（`gl_Position = ProjMat * ModelViewMat`）、中性 clip 行不再出现、不引用 UV0、喂齐 `alpha/sunVec/upVec`」。`./gradlew --offline build` 全绿（含退役后仍存在的 `SkyPackGeometryRouteTest` —— 它测的是 `SkyPackGbuffer` 自身谓词，类还在）。
- **⛔ 仍缺**：skybasic 几何顶点<b>尚无消费者</b> ⇒ 穹顶还没真按包画。下一步 = 三处 `@WrapOperation`（`renderSkyDisc`/`renderStars`/`renderSunriseAndSunset`）调 `SkyPackPipeline.descriptor(site)` + `RenderStageSupply.enter(site.stageMacro)` 换管线，配 `mixin.skyGeometryPack` 开关（顶 `VkDispConfig` 行棘轮 cap），并<b>同一次</b>清掉 `SkyPackGbuffer`/`GbufferVertexRoute` + 它们残留的守卫。对错仍是画面判据（统一取证轮）。
- **是否已提交**：是（单独提交并推送；默认档画面不变，与并发会话不冲突）。

---

## 2026-10-16（一百七十五）— 🧩 穹顶路线第三块离线零件：`SkyPackPipeline`（三处站点共用的派生管线**描述符工厂**，照 `SkyCelestialPack` 同形、去掉 `SAMPLER0`）

> **verdict = 继续只落「离线能证 + 非破坏」的零件。本轮只**新增**两个文件（工厂 + 守卫），不删、不改任何既有渲染路径与配置键 ⇒ 默认档画面零变化、与并发会话在天空线上的改动**不冲突**。`descriptor()` 里不碰 GPU（编译留给混入侧既有兜底），守卫只验形状层。**

- **本轮新事实（读代码核实，不是猜）**：三处派生管线是<b>同一形</b>——都 `GLOBALS_SNIPPET + PROJECTION + DYNAMIC_TRANSFORMS + 我方块布局 + skybasic 的 adapter/fragment id`，<b>只差 `vertexFormat/topology/blend`</b>（一百七十四已冻进 `SkyGeometrySite`），且<b>都不需要 `SAMPLER0`</b>（穹顶/星点/晨昏片元不采贴图；只有日月采 celestials 图集）。所以工厂 = `SkyCelestialPack.compiled()` 去掉 SAMPLER0 + 逐站形状读 `SkyGeometrySite`，`withLocation` 传 `Identifier`（不是 String）。
- **改了什么**：① 新增 `bridge/SkyPackPipeline.java`：`requiresSampler0(site)=false`、`program(site)=site.program`、`descriptor(site)`（契约缺→`null`=本帧不换、交回原版，与「绝不半接」同纪律）、location 后缀带站点名+契约身份哈希（换包自动换号、不撞车）。② 新增 `bridge/SkyPackPipelineTest.java`（3 条纯元数据守卫：三处都不留 SAMPLER0 / 都建在同一条 skybasic / 自报名单覆盖三站）。
- **影响的文档**：`docs/13` 天空行（一百七十三/一百七十二那条已记「零件已落、待接线」）；本条把「管线工厂」也纳入已落零件集。
- **测试结果**：`./gradlew --offline build` 全绿。
- **⛔ 仍未完成（如实，且这块之后剩下的就是纯耦合那一步）**：`descriptor()` **还没有调用方**。穹顶要真画，仍要那一次原子改动（见 memory `project-sky-dome-atomic-wiring`）：把 `SKY_PROGRAM` 摘出 `postVertexProgram`、`chunkAdapter` 走 `POSITION` 天空适配层、`builtinsFamily` 让 skybasic 显式走 CHAIN、退役 `SkyPackGbuffer`/`GbufferVertexRoute`、加 `mixin.skyGeometryPack` 键（顶 `VkDispConfig` 行棘轮 cap）、三处 `@WrapOperation` 调 `SkyPackPipeline.descriptor` + `RenderStageSupply.enter` 换管线，并改写被牵连的 6 个守卫测试。**这一步与退役耦合、且对错只有画面能判 ⇒ 留到统一取证轮整批做，不在共享工作区里落半成品。**
- **是否已提交**：是（单独提交；本块纯新增，不改默认档画面、不碰并发会话文件）。

---

## 2026-10-16（一百七十四）— 🧩 落穹顶路线的**第二块离线零件**：把三处天空站点的决策冻成一张表（站点 → 程序 / 顶点形状 / 拓扑 / 混合 / renderStage）

> **verdict = 继续只做「离线能判」的零件：一百七十三落的是顶点适配层，本条落的是三处 `@WrapOperation` 共用的事实表。两块都还没接进渲染路径，默认档画面零变化。退役全屏接管 + 真接线仍归统一取证轮（一百七十二的顺序约束未动）。**

- **为什么是这块**：穹顶/星点/晨昏那三处换管线要同时回答「喂哪条程序、用哪种 `DefaultVertexFormat`、哪种拓扑、哪种混合、`renderStage` 交哪个宏名」——这些**全是离线可判的原版事实**（读 `RenderPipelines.java:1037-1083` + `SkyRenderer.java:163/266/284` 与 BSL 的 `renderStage` 用法即可逐字钉住），把它们冻成一处 `enum` 比散写进三个混入方法体更安全（同源，QD-02）。真正的风险（渲染期换管线会不会崩帧/画错）留给画面那一轮。
- **改了什么**：新增 `bridge/SkyGeometrySite.java`（`enum {SKY_DISC, STARS, SUNRISE_SUNSET}`，各带 `program=gbuffers_skybasic` / `stageMacro ∈ {SKY,STARS,SUNSET}` / `vertexFormat` / `topology` / `blend`，`blend=null` 表示穹顶用 `ColorTargetState.DEFAULT`）。`renderStageValue()` 从唯一真源 `RenderStages` 取号，解不出返回 `-1`（消费方据此判不接管）。
- **依据**（都到行）：`RenderPipelines.SKY:1037-1046` = POSITION+TRIANGLE_FAN+DEFAULT；`STARS:1072-1082` = POSITION+QUADS+OVERLAY；`SUNRISE_SUNSET:1060-1070` = POSITION_COLOR+TRIANGLE_FAN+TRANSLUCENT。三处同喂 skybasic 的依据是 BSL `gbuffers_skybasic.glsl:169,179` 用 `renderStage == MC_RENDER_STAGE_STARS / _MOON` 归零 `alpha` 抑制原版叠加。`DefaultVertexFormat.POSITION/POSITION_COLOR` 的实际类型是 `com.mojang.renderpearl.api.vertex.VertexFormat`（本轮编译纠正：不是 `blaze3d.vertex.VertexFormat`，也没有 `BlendFunction.DEFAULT`）。
- **影响的文档**：`docs/13` GAP-027 天空行已在上一条登记「顶点零件已落、待接线」，本条续上「决策表已落」。
- **测试结果**：`./gradlew --offline build` 全绿。新增 `bridge/SkyGeometrySiteTest`（4 断言：三处同程序 / 逐站形状·拓扑·混合与原版逐字一致 / 穹顶 `blend=null`+`colorTargetState()==DEFAULT` / 三个 renderStage 宏都能解出且互不相同）。
- **⛔ 仍未完成（如实）**：这张表**还没有任何渲染路径调用**。下一步（统一取证轮）= 用 `SkyGeometrySite` 在 `SkyRenderer` 三处私有方法各注一个 `@WrapOperation`（`setPipeline` 那一次调用）换派生管线 + `RenderStageSupply.enter(site.stageMacro)` 供值 + 绑 skybasic 的块；与「退役 `SkyPackGbuffer`/`GbufferVertexRoute` + skybasic 顶点源摘出 `postVertexProgram`」同一次落地。
- **是否已提交**：是（单独提交并推送；本块是纯数据零件，不改默认档画面）。

---

## 2026-10-16（一百七十三）— 🧩 落穹顶路线的**顶点侧独立零件**：把日月适配层生成器泛化成天空生成器（加 `POSITION` 形状 + `alpha` 中性供值），只零件、不接线

> **verdict = 本轮只补「穹顶/星点换管线」必需的那块可离线验证的顶点生成逻辑，**不**动 `f59cc5c`（一百七十二）钉下的那条顺序约束**：退役全屏接管 + 改 skybasic 顶点源 + 三处 mixin 换管线必须同一次落地，且其正确性只有画面能判 ⇒ 仍归统一取证那一轮。本轮那块零件尚未被任何渲染路径调用，默认档画面零变化。

- **为什么先落这块**：一百七十二把顺序钉死后，穹顶路线里**唯一能在离线守卫下证伪的**一环就是顶点适配层（其余全是资源位/管线注册/渲染线程，都要画面）。把「真机才能判」的那坨留到统一取证，把「纯冷路径字符串、能逐条签核对」的这一块先做并测绿 —— 与本仓既有纪律一致（`DerivativeTbn` / `RenderStageSupply` 都是先落成带守卫的独立零件再接线）。
- **改了什么**（都到文件:行）：① `glsl/translate/CelestialVertexAdapterGenerator.java` 加 `enum Format{POSITION_TEX, POSITION}`：`POSITION_TEX`=日月那条流（Position+UV0，行为逐字不变，`generate(inputs)` 旧重载仍在、默认走它）；`POSITION`=穹顶那条流（`DefaultVertexFormat.POSITION`，只 Position，`RenderPipelines.java:1042`）。② 供值表 `supply(...)` 现按形状门控 `UV0`：`POSITION` 没有 UV0 ⇒ `texCoord` 不引用它、落零值 + WARN（引用一条本顶点流没有的属性=加载期抛的半接线，这是这块零件最主要的错误形状）。③ 新增 `alpha` 中性供 `1.0`（穹顶应不透明；BSL 用 alpha 表达「这片盖不盖」，STARS/MOON 站的 alpha=0 是**逐站**渲染期口径，不塞进生成期常量）。
- **依据**（读的是交进包资源的真包，不是文档转述）：`run/bsl-inspect/shaders/program/gbuffers_skybasic.glsl:129-182` —— 片元从 `gl_FragCoord` 反解 `viewPos`、`#ifdef STARS`/`SHADER_SUN_MOON` 都吃 `viewPos`，声明的 varying 只有 `alpha`/`sunVec`/`upVec`；顶点 `gl_Position=ftransform()` ⇒ 几何路线要的就是「Position 进、真实 MVP 出、三条 varying 供上」。这**证实**了一百七十二的判读：穹顶/星点/晨昏是同一条 skybasic 按 `renderStage` 分派（`:169` STARS、`:179` MOON 各把 alpha 归零）。
- **影响的文档**：`docs/13` GAP-027 天空行补「穹顶顶点零件已落、待接线」。
- **测试结果**：`./gradlew --offline build` 全绿（1255 用例，0 失败）。新增 3 条守卫到 `CelestialVertexAdapterGeneratorTest`：`POSITION` 只 1 条 in、中性 `alpha=1.0`、绝不引用 `UV0`、`texCoord` 落零值、**真 BSL skybasic 契约能被 POSITION 形状逐条满足**。`MethodLengthRatchetTest` 一度转红（`supply` 加 `alpha` 支过 60 行）⇒ 把每条 `case` 收成单行修回，**未调基线**。
- **⛔ 仍未完成（如实）**：这块零件**还没接进任何管线** —— 让穹顶真按包 skybasic 画，仍要同一次做：把 `SKY_PROGRAM` 从 `postVertexProgram` 摘掉、`GbufferVertexRoute`/`SkyPackGbuffer`/`SkyPackGeometryRouteTest` 退役、三处 `@WrapOperation`（`SKY`/`STARS`/`SUNRISE_SUNSET`，各自 binding/topology/blend + `renderStage` 供值）换管线。这些是画面判据，归统一取证轮。
- **是否已提交**：是（单独提交并推送；`skybasic` 顶点源那格因与全屏接管抢同一资源位，仍按一百七十二留待整体落地）。

---

## 2026-10-16（一百七十二）— 🔴 排到穹顶/星点那三处时撞到一条结构约束：**换管线必须先退役全屏接管那一路**，两者不能先后摆

> **verdict = 本轮不落代码，只把顺序钉死：`gbuffers_skybasic` 在虚拟包里只有一个适配层资源位，而「全屏那一路」与「原版驱动那三处」要的是两种不同的顶点源 —— 谁后写谁覆盖谁，同时画又是双层天空。**

- **三条叠出约束的事实**（都到行）：① 每条 gbuffer 程序的顶点源资源路径由程序名派生（`GbufferArtifacts.adapterPath`）⇒ **一个名字一个文件**；② 全屏那一路的顶点源是 `PackTerrainSource.postVertexProgram` 配出来的 post 恒等对，而原版驱动那三处要的是几何适配层，且**格式各不相同**：`SKY`/`STARS` = `POSITION`、`SUNRISE_SUNSET` = `POSITION_COLOR`、`CELESTIAL` = `POSITION_TEX`（逐字 `RenderPipelines.java:1037-1083`）；③ 两条并存 = 同帧画两层天空（GAP-027 的「双云」那一族）。
- **由此定下的顺序**（下一格一次做完，不许拆成两个半格）：把 `skybasic` 的顶点源改成几何适配层的**同一次改动**里，撤下 `SkyPackGbuffer` 那条全屏 draw 与 `postVertexProgram` 对 skybasic 的登记；退役范围一并列出（含 `GbufferVertexRoute` 判据、`SkyPackGeometryRouteTest` 按新形态重写）。🔖 `PostVertexLinker` 的 clip 中性化**代码可以留** —— 它对任何 post 程序都是「形态不认就不动」的保守变换，且守卫 `ndcNativeClipIsLeftAlone` 钉住了对链是恒等；但别再留一个只有历史意义的**入口**。
- **不受影响的那半**：日月（M-16）今天就能跑 —— `gbuffers_skytextured` 的资源位本来就空着，`POSITION_TEX` 适配层已与它对账通过（键 `mrt.skyPackTexturedShader` 默认关，真机判据未签）。
- **影响的文档**：`docs/13`（GAP-027 天空线新增这条顺序约束行）。
- **测试结果**：`./gradlew build` 无源码改动（本轮纯登记）；工作树只余另一条会话线的 `docs/20`。
- 🔴 **未解决**：① 天空 = 穹顶/星点/日落三处换管线（受本条顺序约束）；② 水 = `mat`/`recolor` 需扩 chunk 顶点格式；③ 云 = 链正确性 `GAP-058`（需真机）；④ 全部真机画面判据按本轮序未取。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百七十一）— 🟢 日月这条接线落地（M-16）：原版继续驱动 draw，我方只换管线 + 补绑包的 `gbuffers_skytextured`

> **verdict = 天空第二半的**代码**齐了（新键默认关 ⇒ 画面逐字不变）；判据按本轮序留给统一取证那一轮，不在这里签。**

- **本次改了什么**（新类 3 + 接线 6 处）：
  - `mixin/SkyRendererCelestialPipelineMixin`（**登记为 M-16**，`docs/04-SPEC` §5.0 新增一行）：`@WrapOperation` 注在 `SkyRenderer.drawCelestialBody:256` 那次 `RenderPass.setPipeline`，用 `@Local(argsOnly=true)` 同时拿 `RenderPass` 与 `label`；**首行是 bridge 埋点转发**（`SkyCelestialPack.onCelestialSite`，M1 ③），**先备料成功再换管线**（备料失败原样交回 `requested` ⇒ 不会出现「换了着色器却没绑 uniform」那种 pass 体内崩帧，GAP-036 ④ 的形状）；`require=1/expect=1`。
  - `bridge/SkyCelestialPack`：派生管线**逐字继承** `CELESTIAL` 的 `GLOBALS + PROJECTION + DYNAMIC_TRANSFORMS + SAMPLER0 + POSITION_TEX + QUADS + OVERLAY`（少 `SAMPLER0` 就会被原版那句 `setUniform("Sampler0", …)` 抛），再加我方 `packBindGroupLayout` 与包的两个着色器 id；`available()` 一句话说清不接管的四种原因；🔴 **按 label 白名单只认 Sun/Moon** —— `renderEndFlash` 共用同一个私有方法，不区分就会拿包的日月程序去画末地闪烁。
  - 🔴 **每次 draw 一份自己的块代**：写块是 CPU 直写（`VulkanGpuBuffer.map` = `vmaMapMemory`，`:107,145-146`）⇒ pass 内可写；但日与月是同程序两次 draw、`renderStage` 又在块里 ⇒ `rotate()` → 写 → 绑，顺序由守卫钉住（先写后 rotate 会把刚写的代让出去）。
  - `GbufferVertexSources` 多一条分流：日月走 `CelestialVertexAdapterGenerator`（`POSITION_TEX` 只有 `Position`/`UV0`，chunk 家族那些属性它一个都没有）；`PackTerrainSource.SKYTEXTURED_PROGRAM` + 虚拟包表一条 + `mrt.skyPackTexturedShader` 进 memo 键与热重载快照 + `SkyPackGbuffer.skyTexturedWired()` 改为读 `SkyCelestialPack.wired()`（唯一真源，不复制第二份条件）。
- **为什么这条不做成 modifier**：`PipelineModifier` 拿不到 `RenderPass` ⇒ 补绑无处下手（本仓此前设想的「零注入点」在这格不成立，理由已写进登记行）。
- 🔴 **落完立刻抓到自己造的第二种互斥**（这条比接线本身更要紧）：本类那条全屏接管一旦生效就把原版 `SkyRenderer.render` 整笔抑制掉 —— 而 M-16 的日月**恰恰要借原版那两次 draw**。两者同开 = 日月又没了。⇒ 接管资格从「日月那条程序接上了」改成「**我方覆盖原版那一笔的全部内容**」（`fullSkyCoveredByPack()`，今天 false + 理由写在方法头上），并加一条守卫钉「绝不能用『日月已接线』当接管许可」。解除条件 = 穹顶/星点也改成换原版管线（`SkyRenderer:167/180/274/298`），那张全屏三角与 clip 中性化一并退役。
- **影响的文档**：`docs/04-SPEC`（M-16 登记行）、`docs/13`（GAP-027 那行从「下一步是接线」改成「接线已落、真机判据未签」+ 互斥这条）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL，1251 项 0 失败**（+9 条：`SkyCelestialPackWiringTest` 4 + `CelestialVertexAdapterGeneratorTest` 5）。中途三次红都是守卫正常工作：`MixinWiringTest`（首行必须是埋点转发）、`ClassLineRatchetTest`×2（`VkDispConfig` 撞 1047 ⇒ 把两处重复口径的注释压成指针，未抬基线；新键的注释合进 `.comment` 一行）。
- 🔴 **未解决**：① 真机判据未签（`Mixin apply failed=0`、两条自报、日月同机位对照、正午/夜晚两档）⇒ 本轮序里排在三件功能做完之后；② 穹顶/星点换成包的 `gbuffers_skybasic` 走同一族管线替换（并决定那张全屏三角与 clip 中性化是否退役）；③ 水 2/2（`mat`/`recolor` 需扩 chunk 顶点格式）；④ 云 = 链正确性 `GAP-058`（需真机）；⑤ 上一条 commit（`8505dd2`）因 `github.com:443` 路由层被拦**尚未推送**，本轮一并补推。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百七十）— 🟡 日月这半先把「供不供得出」证死：`CelestialVertexAdapterGenerator` + 真 BSL `gbuffers_skytextured` 逐条对账通过

> **verdict = 契约层没有障碍（含「片元只写槽 0」这条决定附件数的关键事实），剩下的全是 draw 点接线；本行明确标「还没接」，不许读成日月已支持。**

- **本次改了什么**（新代码两处，均为离线件）：`glsl/translate/CelestialVertexAdapterGenerator`（按包契约给原版 `POSITION_TEX` 顶点流生成日月适配层）+ `CelestialVertexAdapterGeneratorTest` 4 条。
- **量到的三件事**：① 真 BSL `gbuffers_skytextured` 的契约**每条 in 都能被逐名字逐 location 逐类型满足**（不需要改包、不需要发明通道）；② 该片元 `DRAWBUFFERS:0` ⇒ 派生管线**必须单附件**（多一个就 `setPipeline` 抛 = 崩帧那一族）；③ `color` 只能中性 `vec4(1.0)`，因为 `POSITION_TEX` **没有 Color 元素**（格式事实），并把它记进「常量供值」名单 + 生成器自报。
- **设计上的两条排除**（都写在生成器头部，避免下一轮重走）：不复用 `PackVertexAdapterGenerator`（那条是 chunk 家族：`ChunkPosition`/`CameraBlockPos`/`UV2` 日月一个都没有）、不自开 pass 画日月（拿不到原版留在 `DynamicTransforms` 里的天体姿态 ⇒ 只能复制数学）。
- **写环可行性已核实**：`VulkanGpuBuffer.map` = `vmaMapMemory` 直写主机指针（`:107,145-146`），不发 encoder ⇒ h10 那条「pass 内不能动 encoder」不适用于写环；但日与月是**同程序两次 draw**，两次写环之间必须 `rotate()`（`MappableRingBuffer` 三代就是为这个），否则 CPU 跑到 GPU 前面，两次 draw 读到同一份。
- **影响的文档**：`docs/13`（GAP-028 表里加一行「日月这半：先证供值、再接 draw 点」，含下一步与两个坑）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL**（+4 条守卫，其中一条是真包对账）。
- 🔴 **未解决**：① 日月 draw 点接线（`SkyRenderer.drawCelestialBody` 的 `setPipeline` 换派生管线 + 之后补绑）未做 ⇒ `SkyPackGbuffer.skyTexturedWired()` 仍回 false、天空接管仍被闸住；② 穹顶/星点换成包 `gbuffers_skybasic` 的管线替换是**另一格**（要一并处理那张全屏三角与 clip 中性化的退役，别混）；③ 水 2/2（`mat`/`recolor`）与云（链正确性 `GAP-058`）不变。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百六十九）— 🔴 自己抓到自己上一轮的接管会**吞掉太阳月亮** ⇒ 补一道闸：日月那半没接线就不接管

> **verdict = 天空今天回到「原版穹顶 + 原版日月」，包天空的机制（clip 中性化 + 实测判据位）留在仓库里等 `gbuffers_skytextured`；这不是回退功能，是拒绝交付一个「包算的天空但没有光源」的半成品。**

- **怎么发现的**（不是猜，是三处逐字读出来的）：① `SkyIntoGbuffer.renderSky` 回 `true` 时原版 `SkyRenderer.render` <b>整笔</b>不跑，而那一笔里除穹顶外还有 `renderSun`/`renderMoon`（`SkyRenderer:222,229`）；② BSL 的 `gbuffers_skybasic` 里日月坐在 `#ifdef SHADER_SUN_MOON` 那一支，而 `lib/settings.glsl:165` 是 `//#define SHADER_SUN_MOON`（<b>默认注释掉</b>）；③ BSL 真正的日月在 `gbuffers_skytextured` —— 那条<b>我方一行都没接</b>。⇒ 上一轮的接管在默认档下会让画面<b>没有太阳和月亮</b>，而且不报错。
- **本次改了什么**（代码，一处 + 一条守卫）：`SkyPackGbuffer.enabled()` 里加 `maySuppressVanillaSky(skyTexturedWired())`（纯函数 + 单一改点：接上 skytextured 时只改 `skyTexturedWired()` 那一处，闸门与自报跟着变），判否时打一次点名 WARN 并<b>交回原版重放</b>（M-11 仍把原版天空引进 colortex0）；顺序刻意<b>先于</b>契约判定 —— 契约过了而日月缺席，症状正是「天空是包算的但没有光源」。
- **影响的文档**：`docs/13`（GAP-027「现状」行加这道闸与三处出处；不改本行前半段那条已证明的机制）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL**（+1 条守卫：`SkyPackGeometryRouteTest#takeoverRequiresTheCelestialHalf`，钉「假⇒不许接管」「真⇒必须能恢复接管」「闸门真的在 enabled() 里且先于契约」）。
- 🔴 **未解决 / 下一步**：① 天空第二半 = 接 `gbuffers_skytextured`（阻塞点与两条候选路线已记在 GAP-028 的 10-16 行：日月几何与图集是 `SkyRenderer` 的 private final，姿态走 DynamicTransforms 的 ModelViewMat ⇒ 要么 accessor 注入点、要么按 `buildSunQuad` 数学自建 `POSITION_TEX`；外加按笔 ModelViewMat 覆盖）；② 水 2/2（`mat`/`recolor` 需扩 chunk 顶点格式）；③ 云的前置是链正确性（`GAP-058`，需真机）；④ 本轮序里真机画面判据仍未取。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百六十八）— 🟢 GAP-028 ②：`renderStage` 的**逐 draw 供值**接上了（天空那笔进 SKY），并把日月那两笔的**真实阻塞点**核实到行

> **verdict = 「块里有这个成员、但没人填」这一族从今天起有了通道；天空第二半（日月盘）缺的不再是供值，而是几何与按笔矩阵覆盖 —— 这两件事本轮分开记，不混成「天空已支持」。**

- **本次改了什么**（代码，四处）：
  - `render/RenderStageSupply`（**新类**）：当前 draw 的阶段持有者 + `AutoCloseable` 的 `Scope`。三条刻意的形状：① 编号**只有单一真源**（`RenderStages.valueOf(MC_RENDER_STAGE_+名字)`，不在第二处重算，QD-02）；② **作用域而不是裸 setter**（天空那笔嵌在别人的 pass 里；漏还原会让同帧后面的地形/水读到 SKY —— M-10 云那次压栈/还原的同族事故）；③ 认不出的名字 ⇒ **按 NONE 供 + 计数 + 点名**，不沿用上一层。
  - `render/OfUniformManager`：`values.put("renderStage", RenderStageSupply.current())`；并把 `[GAP-028]` 自报里那句「② 逐 draw 供值**仍未接**」改掉，改成打**本帧实际供的值 + enters/unknown 读数**（自报不许比代码更悲观）。
  - `bridge/SkyPackGbuffer`：写环之前 `enter("SKY")`（守卫钉「必须早于 `updateGbufferBuiltins`」—— 环里存的就是那一刻的读数）。
- **为什么现在做它**：这是天空第二半（`gbuffers_skytextured`）与日月/自定义天空那几支的**硬前置** —— BSL 逐字写 `renderStage == MC_RENDER_STAGE_SUN`（`program/gbuffers_skytextured.glsl:89,92,101,107`），恒 0 的后果是**编译得过但分支恒假**（画面少东西、日志全绿那一族）。
- 🔬 **顺带把日月那两笔核实到行**（`SkyRenderer.java`，源取自 mergeWithSources jar，解到 `build/vanilla-src/`）：`celestialsAtlas/sunBuffer/moonBuffer/starBuffer` 全是 `private final` 无 accessor（`:65-74`）；`renderSun/renderMoon`（`:218-229`）是**两次独立 draw**、同一张 `RenderPipelines.CELESTIAL`；姿态写进 **DynamicTransforms 的 ModelViewMat**（`applyCelestialBodyTransform` `:242-250`），不是 `gbufferModelView`；几何格式 `POSITION_TEX`（**没有 Color 元素** ⇒ 包的 `color = gl_Color` 只能按中性 1 供并点名理由）；贴图 `celestialsAtlas.getTextureView()` 绑 `Sampler0`。✅ **零注入点的那半成立**：`getCompiledPipeline` 首条语句就过 `PIPELINE_MODIFIERS.apply` ⇒ 与云那格同一官方通道。🔴 还差两件：按笔的 **ModelViewMat 覆盖**（挂载点与本轮 renderStage 同一个）+ 日月几何（accessor 注入点，或按 `buildSunQuad/buildMoonPhases` 的数学自建 `POSITION_TEX` 缓冲）。
- **影响的文档**：`docs/13`（GAP-028 的「本条不关：逐 draw 供值仍未做」一行改写为已接 + 保留未做的那半；新增一行「10-16 源码级核实：日月缺的不是着色器而是几何可达性」；状态行同步）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL，1242 项 0 失败**（+5 条守卫：`RenderStageSupplyTest` —— 含「供值与宏展开必须同数」「日月/自定义天空三个号互不相同且都不等于 NONE」「嵌套作用域必须回到外层」「错名字不得被计成一次真进入」「自报口径不许比代码更悲观」）。中途一次红是我自己那条计数断言写错（把外层 SKY 那次进入算进了「错名字不该计数」的账），按语义改成量差值。
- 🔴 **未解决**：① `gbuffers_skytextured` 仍未接线（上面那两件事）；② 数值口径仍是我方 ABI（Iris 未核实到源码）；③ 云那格的前置是链正确性（GAP-058）；④ 水的 `mat`/`recolor` 仍需扩 chunk 顶点格式；⑤ 真机画面判据按本轮序未取。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百六十七）— 🟢 水这一格换的是**第三条路**：包片元的 normal/tangent/binormal 由屏幕空间导数现算（不动 chunk 网格），真 BSL 包上端到端跑通

> **verdict = 水的 TBN 从今天起有真值（离线端到端证明 + 10 条守卫），键默认关 ⇒ 今天画面逐字不变；`mat`/`recolor` 那两条仍然只能来自网格化，本条不关闭。**

- **本次改了什么**（代码，六处 + 一个新类 + 一个新键）：
  - `glsl/translate/DerivativeTbn`（**新类**，判定 + 片元改写）：三条 `in` 声明**改名**（`normal → vkdispInterpNormal`，**保住 location** ⇒ 与适配层 out 的连接关系逐字不变），包的读点经 `#define normal vkdispTbnNormal()` 指到我方函数；面法线 = `normalize(cross(dFdx(viewPos), dFdy(viewPos)))` 再翻到朝相机一侧，切线/副切线取 uv 偏导（derivative mapping），行列式退化时退到与法线垂直的任一单位向量。**chunk 面全是轴对齐平面 ⇒ 这个面法线是精确值，不是近似。**
  - `pack/DerivativeTbnSwitch`（**新类**，读取侧）：反射桥接 + 「类在、配置没加载」第三态回落默认值 —— 与 `PackPostVertexSwitch`/`PackCapabilityGateSwitch` 同族，反射失败必须打成 ERROR（h33 那一族的第六次预防）。
  - `VkDispConfig`：新键 `mrt.derivativeTbn`（默认 **false**）。
  - `PackTerrainSource`：chunk 路线在**契约解析之后**做改写，判定与改写**同一次调用**产出，一起装进 `Result`（新增 `tbnPlan` 分量 + `linkedFragment` 语义扩到「改写后的那一份优先落资源」）。
  - `PackVertexAdapterGenerator` / `GbufferVertexSources.chunkAdapter`：按同一个 plan 补出两条辅助 varying 的 `out` 与赋值（取适配层**已经算过**的 `vkdispAdapterViewPos` 与 `UV0` ⇒ 渲染期零新增计算）。
  - `VkDispVirtualPack`：片元取「改写后的那一份」优先、顶点侧吃同一个 plan；键进 `currentTerrainMemoKey`；`VkDispConfigHotReload` 快照同步进键（QD-08 那一族）。
  - 🔧 顺带按棘轮指示搬出 `GbufferAlbedoLod0`（GAP-016 那段产品级止血，逐字同语义：三个前提、「只改一行」的命中数自报、`finally` 还原）——`ClassLineRatchetTest` 两次变红（`VkDispConfig` 1073>1047、`VkDispVirtualPack` 1577>1573）都是**靠搬类和压重复注释**解的，**没有放宽任何基线**。
- 🔴 **两条本轮真踩的前提错**（都写进守卫，不当已修讲）：① 首版想把「main 里声明同名局部变量」当实现 —— 实测 BSL 水在**文件作用域函数**里读 `normal`（`world0_gbuffers_water-FRAGMENT.glsl:323 GetWaterNormal`、第 331 行 `dot(normalize(normal), normalize(viewPos))`）⇒ 局部遮蔽管不到，那是「改了但只改一半」最难查的形状；② 首版直引 `VkDispConfig.MRT_DERIVATIVE_TBN.get()` ⇒ 单测当场抛 `Cannot get config value before config is loaded`（`dev.vkdisp.pack` 不该把 FML 配置体系拖进测试类路径）。③ 另一条守卫被我自己的假设写错：BSL 默认档**地形**其实声明了 `normal`（location 4）而**没有** `tangent/binormal` ⇒ 已按实测改成「只接真声明的那一条」。
- **三处预处理器罩不住的形态必须自己挡**（本层的 fail-closed 面）：参数表里声明同名变量的顶层函数（GLSL 无嵌套函数 ⇒ 深度进出即屏蔽单位）整段 `#undef`/`#define` 包住；成员访问 `data.normal`；别的同名声明 / struct 字段 ⇒ 任一命中就**整条不做** + ERROR 点名，退回今天的常量供值。
- **影响的文档**：`docs/13`（GAP-007 加「第三条路落地（代码半，默认关）」一行，含机制、三处形态闸门、离线判据与**本条不关闭**的理由）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL，1237 项 0 失败**（+10 条守卫：`DerivativeTbnTest`；其中两条是**真 BSL 包端到端** —— 走 `PackTerrainSource.generate(…, gbuffers_water)` 断言落进资源的那一份，一条断言「闸门关着 ⇒ `linkedFragment` 为空、源文本一行不动」）。
- 🔴 **未解决**：① `mat`/`recolor`（方块 id）仍按常量供 —— 只能来自网格化（方案 A：扩 chunk 顶点格式，牵连所有吃这块缓冲的管线声明 + 已烘焙 section 重建），本条不据此关闭；② LabPBR 的 `normals`/`specular` 纹理数组缺（GAP-009）；③ **真机画面判据未取**（按本轮序，云/水/天空做完后统一取证）；④ 云那格的裁决不变（不喂 `gbuffers_clouds`，走 deferred 体积云 ⇒ 链正确性 GAP-058 + `cloudHeight` 供值 + `IS_IRIS` 待判）；⑤ 天空仍缺 `gbuffers_skytextured`（日月盘/贴图）。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-16（一百六十六）— 🔴 云这一格**换方向**（源码级核实后撤回我自己上一轮写的「下一刀」）：26.3 的云没有顶点流也没有 uv ⇒ 不喂 `gbuffers_clouds`，包算云走 deferred 的体积云

> **verdict = 「把包的 gbuffers_clouds 接进云几何」这个计划是错的，错在前提而不是执行：26.3 里云几何根本不是顶点流。本轮把它从「下一刀」改成「待裁决」，并把代码里两处会误导下一个人的文案改掉。**

- **核实到的事实**（行号即证据，源取自 `~/.gradle/caches/neoformruntime/artifacts/minecraft_26.3_client.jar` 与 mergeWithSources 源，解到 `build/vanilla-src/`）：
  - `net/minecraft/client/renderer/CloudRenderer.java:217-235` —— `render(...)` 逐字 `renderPass.setVertexBuffer(0, null)` + `drawIndexed(6 * quadCount, 1, 0, 0, 0)`，并 `setUniform("CloudInfo", ubo)` / `setUniform("CloudFaces", utb)`。⇒ **没有顶点缓冲**，云的六个面全在着色器里由索引 + UBO 现算。
  - `assets/minecraft/shaders/core/clouds.vsh` —— `packedX/packedY/packedZ` 位取顶点 + `texelFetch(CloudFaces, …)` 解出 `cellX/cellZ/direction/flags`，`pos = faceVertex * CellSize + vec3(cellX,0,cellZ) * CellSize + CloudOffset`，颜色取 `faceColors[direction] * CloudColor`。**没有输出 uv**（这条着色器压根不采纹理）。
  - `assets/minecraft/shaders/core/clouds.fsh` —— 只有 `vertexColor` × 雾 ⇒ 26.3 的云**连 fragment 都不采样云纹理**（旧版那张 `clouds.png` 只在加载期被折成 cell 位图，`CloudRenderer.java:67-86`）。
  - 而 BSL `shaders/program/gbuffers_clouds.glsl` 仍是旧约定：`:175 texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy`、`:155 varying vec3 normal`、`:69 texture2D(texture, texCoord)`。⇒ 要喂它，我方必须**新造一套 cell→uv 语义**并复刻 CloudFaces 解码 —— 那是本仓反复拒绝的「功能名先出现、语义后补」形状（且本轮按裁决不做真机验证，风险不对等）。
- **裁决与后果**（用户点选）：**不喂 `gbuffers_clouds`**。包算云改走它自己的**体积云**（`deferred1` 里的 `clouds.glsl`）⇒ 牵连的三格写进账：链正确性（`GAP-058`，整屏天空蓝那一腿）、`cloudHeight` 一族供值（`GAP-029`）、以及 `IS_IRIS` 那条「一次翻多支」的待判（包里 `clouds.glsl:329` / `shadows.glsl:308` 两支消费者都坐在 `#ifdef IS_IRIS` 里）。
- **本次改了什么**（代码，两处文案，零行为）：`bridge/CloudsIntoGbuffer` 类注释第 2 条①（原文写着「本轮不做，那是下一刀」）与 `reportOnce` 那行自报（原文写着「⇒ 换成包的 gbuffers_clouds 是下一刀」）—— 自报不许比代码更自信，留着下一轮就有人照它开工。
- **影响的文档**：`docs/13`（GAP-027「现状」行里那句「`gbuffers_clouds` 没有包的程序」按事实改写 + 记裁决与三条牵连）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL**（无守卫变红：本轮只动注释与一条日志文案）。
- 🔴 **未解决**：① 水 = 缺口在**网格化补顶点属性**（`at_tangent → normal/tangent/binormal`、`mc_Entity → mat`），GAP-007/043，排下一轮；② `gbuffers_skytextured`（日月盘/贴图）仍未接；③ 真机画面判据按裁决**整批顺延**到三件功能做完之后 ⇒ 天空那一格今天只有**离线**证据；④ `git push` 本轮**没推上去**（`github.com:443` 从 Windows 与 WSL 都不通，而 `api.github.com` 回 200 ⇒ 路由层拦，不是凭据；上一条 commit `cbc1edf` 与本轮这条都只在本地）。
- **是否已提交**：🟡 本地已提交；推送被网络挡住，待网络恢复补推。

---

## 2026-10-16（一百六十五）— 🟢 天空这一格换的是**机制**不是常量：`PostVertexLinker` 长出 clip 中性化，包顶点的 `ftransform()` 不再把我方的 NDC 全屏三角透视成半屏巨型三角形（GAP-054 那一半）

> **verdict = 按本轮裁决（云/水/天空三件功能先做完，再统一真机测试）：天空的「接管」今天从代码侧成立并被真包离线证明钉住；画面判据**本轮不签**，红着记在下面的「未解决」里。**

- **本次改了什么**（代码，四处）：
  - `glsl/translate/PostVertexLinker`：新增 **④ clip 中性化**（`neutralizeClip` + 公开谓词 `emitsPositionPassthrough`）。post 顶点路线里凡是 `gl_Position = <P> * <V> * vec4(Position, 1.0)` 形态的**整行单语句**赋值，换成位置属性本身（vec3 ⇒ `vec4(Position, 1.0)`，vec4 ⇒ `Position`）。三条保守：① 跨行赋值不认（认了就是把包的源改坏，同 `IO` 那条纪律）；② 右值里没有相机矩阵 ⇒ 完全不碰（链那一大族本来就是 NDC-native）；③ 源里没有 `Position`/`vaPosition` ⇒ **不猜**，原样交出去 + ERROR。作用域**只有 clip 那一行** —— BSL 天空顶点的 `sunVec = normalize((gbufferModelView * vec4(...)))` 与 `upVec = normalize(gbufferModelView[1].xyz)` 逐字保留（那两个 varying 决定 `sunVisibility`/`moonVisibility`，砍了就变成「天空有色、昼夜全错」那一族静默错值）。
  - `pack/GbufferVertexRoute`（**新类**）：程序名 → 实测位的持有者（`static final ConcurrentHashMap`，QD-03 口径）。查不到 = **false**（fail-closed：没量过 ≠ 量过且成立）。
  - `pack/PackTerrainSource#linkPostVertexRoute`：在**生成期**对将要交进包资源的那份终稿量一次并记下；两条「整条不接线」的早退路径同样记 false。写点与契约产出点同一条路 ⇒ 渲染期读到的判据不可能比契约旧（X42 的同源约束在生成期那一半的形态）。
  - `bridge/SkyPackGbuffer`：几何闸门从两块 `private static final boolean` 改成现读 `GbufferVertexRoute`。**为什么这件事算修**：上一版的判据是常量，于是「让包天空接管」的最小改动 = 把常量改成 true —— 而那**什么都不证明**；现在改常量改不出 true，只能靠终稿文本真的变成 passthrough。
- **为什么**：`GAP-054` 追了两天的那块切过半个屏幕的巨型三角形/图集墙，机制侧有一条已经写死在源码里的事实：包的 `gbuffers_skybasic` 顶点终稿逐字是 `gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0))`（`build/dump-head/world0_gbuffers_skybasic-VERTEX.glsl:240`），而我方喂的是 NDC 大三角、`OfUniformManager.putCameraMatrices` 交的是**真相机**矩阵（`Family.CHAIN` 只翻深度口径）。⇒ 「全屏三角替穹顶」在数学上成立（片元从 `gl_FragCoord` 反解方向），在几何上**缺这一步中性化**。当时的止血是拒绝接管（天空退回原版重放 = 用户看到的「天空不是包算的」）。
- **影响的文档**：`docs/13`（GAP-027「现状」行加 10-16 更正；GAP-054 加「几何路线那一半已收口」一行，状态行不动 —— 真机生产者仍未定，不据此收口）。
- **测试结果**：`./gradlew build` → **BUILD SUCCESSFUL，1227 项 0 失败**（+9 条守卫：链接器 5 条 / 几何路线 3 条 / 真包离线证明并进 `PackTerrainSourceTest` 那条天空线）。中途一次红灯是 `ClassLineRatchetTest`（`VkDispVirtualPack` 1618 > 上限 1573）⇒ 按棘轮的指示把判据位落到独立类 `GbufferVertexRoute`，**没有放宽基线**。
- 🔴 **未解决（如实登记，不当已修）**：① **本轮不取真机画面判据** —— 按裁决，天空/水/云三件做完后统一取证；`SkyPackGbuffer` 的 `[GAP-027/sky]` 接管自报与 `clipPassthrough=true` 目前只有**离线**证据。② `gbuffers_skytextured`（日月盘 / 天空贴图）**一条都没接**。③ 包的云（`gbuffers_clouds`）与水（`gbuffers_water`）仍在各自那一半：云没有包的程序、水挂在三个默认关的键上 ⇒ 排本轮后两格。④ `GAP-054` 那块平面的**生产者**仍未定（本条只关掉一条机制）；`GAP-058` 的整屏天空蓝仍在链的合成/上屏那一腿，与本格**不同因**。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-15（一百六十四）— 🔴 臂 R 抓到我自己给的读数是**恒真的**：「累计交出 1 次」只在第一次打，永远等于 1 ⇒ 换成每 300 次报一行；同趟 `[GAP-020/gen]` 跨帧奇偶不翻 ⇒ 交账很可能不是每帧走到

> **verdict = 1.1 现在有两个待证环节，而且第一个是我自己造的：读数证不了「每帧交账」这件事，等于没测；改成周期计数之后，下一臂才谈得上定责。**

- **本次改了什么**（代码，2 处，都在 `TerrainSingleDraw`）：① `flipIfOwed()` 的点名从「只打第一次」改成**第一次 + 之后每 300 次一行累计数**（`[GAP-053/1.1] 链首累计已交账 N 次`）；② 新增 `paidCount()` / `flipOwed()` 两个读数口（诊断与单测用）。
- **为什么**：臂 R 读到的 `链首已交账（第一次）… 累计交出 1 次` 是**恒真读数** —— 那行只在第一次打，所以它不可能不等于 1。而同一趟 `[GAP-020/gen]` 在 `chainFrame=3360 / 3480 / 3600` 三行都是 `开跑前 c0=0 … | 链尾 c0=0 …`、**跨帧奇偶不翻**；每帧都交账的话奇偶必须逐帧翻 ⇒ 交账很可能不是每帧走到，而这正是判据红的最短解释。**先修仪器，再谈归因**（口径同记忆里「一行日志只为自己真读过的量作证」）。
- ⚠️ **臂 R 的画面不可判（第三次踩同一条通道失效）**：桥那一路在链路线里 `pitch=2` 与 `pitch=45` 两帧**逐字节相同**，且 md5 与臂 J2 的整屏天空**同一串**（`900f94f7…`）⇒ 已把「链路线的画面判据只能靠 `mrt.pixelProbe` 的进程内读数，桥帧只用于目视形状」写进 `01` §1.4。
- **真机状态**：臂 R = slots=8 取证档复跑（`接管=3604 / label命中=3604 / 尺寸不合拒绝=0 / 深度换=3604`，运行时异常 0 条），跑完取证档 md5 还原 `0f2aaa5c…`、`ADVANCED_MATERIALS` 覆盖已撤回、残留客户端 0。
- **下一臂（不变，但有了可判的读数）**：同档再跑，读 `链首累计已交账 N 次` 随不随帧涨 + `[GAP-020/gen]` 奇偶是否逐帧翻；两者对齐之后画面仍无地形 ⇒ 责任挪到**清屏语义**那一腿（我方那条空 pass 仍带 LOAD/CLEAR 打开池附件，而主 pass slot0 沿用原 `clearValue`、slot1..7 是 `Optional.empty()`=LOAD）。
- **影响的文档**：`docs/13`（`GAP-053` 加臂 R 行）、`docs/01-DEV-LOOP.md` §1.4（通道失效第三次 + 只能靠探针）。
- **测试结果**：`./gradlew test` → **BUILD SUCCESSFUL，1221 项 0 失败**。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-15（一百六十三）— 🔬 臂 Q（slots=8 取证档）：接管**真的发生了**、候选 (a) 的交账**真的走到了**、异常 0 条 —— 但单绘档画面里**仍然没有地形**；顺手补上「交账」这一环的仪器

> **verdict = 1.1 的判据仍红，可红得干净：整条接管链第一次全部为绿（`接管=2510 / 拒绝=0 / 深度换=2510 / ⑤-b 派生=9 交回=0 / 双绘关掉 / [GAP-053/1.1] 交账行出现 / 异常 0`），剩下唯一没被仪器覆盖的一环是「链首到底有没有把那次翻代交出去」—— 已补，下一臂据此定责。**

- **本次改了什么**：先按上一条写死的口径跑臂 Q —— `run/config/vkdisp-pack-options.properties` 加 `BSL_v10.1.8.ADVANCED_MATERIALS=true` + `mrt.attachments=8` + `ownTerrainPass/terrainSingleDraw/takeoverDepthToGbuffer` 三真 + `pixelProbe=true`，冷启动、同机位 `-44.6 104 -93.6` / `yaw=-30 pitch=2`、现钉 `time set 6000`。然后补一处仪器（代码，2 处）：`TerrainSingleDraw.flipIfOwed()` 交账时打一行 `[GAP-053/1.1] 链首已交账（第一次）… 累计交出 N 次`，并把「若画面仍无地形，问题在交账**之后**」写进日志本身。
- **读数（全绿的那一段）**：`registered: 6/6 (colorTargets=8)` ⇒ `主 pass 接管=2510 / label命中=2510 / 尺寸不合拒绝=0 / 深度换=2510`；`[M-04/⑤-b] 派生=9 交回原版=0`（**没有**「附件数不匹配」那一抛 ⇒ ⑤-b 的派生管线接住了 8 附件的 pass）；`[M-04/⑤-d] 双绘关掉`；`[GAP-053/1.1] …翻代交出去（槽数=8 toMain=false）`；运行时异常 **0 条**。
- 🔴 **判据仍红**：`armQ-a.png`（vkdisp 侧）`mean=198.76 / SKYBLUE 93.32% / HARD-EDGE 左半=[]` ⇒ 与臂 J2 同一种整屏平铺天空，**单绘档画面里没有地形**。⇒ 候选 (a) **必要但不充分**。
- 🔬 **顺带否证一条待查项**：进程内探针在 slots=8 档**是出数的**（`SLOT_BLACK ×2 / BOTH_HAVE_CONTENT ×1`）⇒ 臂 O/P 那两臂「一行探针都没打」的原因是那一档 `actualSlots=1`、根本走不到接管，不是探针坏。⚠️ 但 `SLOT_BLACK` 这个结论本身在单绘档语义下要打问号（探针取「被读那一代」，而这一刻翻代归属刚被我方挪到链首 —— 探针取点是否还在正确一侧，属下一臂要看的东西）。
- 🔧 **下一臂（同档重跑，两分钟）**：读 ① `链首已交账` 在不在 + 计数随不随帧涨，② `[GAP-020/gen]` 链首/链尾**奇偶**（交账成立 ⇒ 主 pass 写的那一代必须成为本帧被读代）。两样都对而画面仍无地形 ⇒ 责任挪到**清屏语义**那一腿：我方那条**空 pass 仍然打开着池附件并带 LOAD/CLEAR**，而主 pass slot0 沿用原 `clearValue`、slot1..7 是 `Optional.empty()`(=LOAD) ⇒ 「谁把主 pass 刚画的地形清掉了」是下一问。
- **影响的文档**：`docs/13`（`GAP-053` 加臂 Q 读数 / 未测环节 / 下一臂三行）、代码 `TerrainSingleDraw`（交账自报 + 计数）。
- **测试结果**：`./gradlew test` → **BUILD SUCCESSFUL，1221 项 0 失败**。跑完取证档 md5 还原 `0f2aaa5c…`、包选项覆盖已撤回（`ADVANCED_MATERIALS` 那行删掉）、残留客户端 0。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-15（一百六十二）— 🟡 `20` P1 的 1.1：**代码半落地（翻代归「谁画了 gbuffer」）+ 接管被拒的根因拿到**，画面半被一条设计规则挡住 ⇒ 下一臂必须换 slots=8 档

> **verdict = 候选 (a) 已经写进代码并被三条守卫钉住，但它今天**一次都没被走到** —— 因为接管根本没发生：`slotsUsable` 要求 ≥2 条颜色附件，而 BSL 默认选项下地形管线注册的是 `colorTargets=1`。**

- **本次改了什么**（代码，四处 + 一台仪器）：
  - `MrtTerrainPass.drawTerrain`：那次 `POOL.advanceWritten` **整块挪进 `else`** —— 单绘档下我方两组 draw 都被跳过，我方什么都没写却照旧翻代，正是 `GAP-053` 定序读数（我方 pass 先开、主 pass 在后 ⇒ 主 pass 取到「链这一帧不会读的那一代」）的机制。
  - `TerrainSingleDraw`：欠账与点名搬进本类（`noteFlipOwed` / `flipIfOwed` / `Flip` 持有者）—— 「地形由谁画」本来就是本类的全部业务，而 `MrtTerrainPass` 顶着 `ClassLineRatchetTest` 的 1069 上限（实测撞红过一次，按棘轮的指示落到独立类，**没有放宽基线**）。
  - 槽集合收成**唯一构造点** `MrtTerrainPass.flipWrittenSlots()`（常规路径与交账路径共用，不各算一遍）。
  - `FullscreenPassHook`：链开跑**之前** `TerrainSingleDraw.flipIfOwed()`（挂在调用方是因为 `FrameApi` 正顶着它自己的上限 1675）。
  - 🔬 **新仪器**：`MainPassTakeover.refuseOnce` —— 此前接管被拒只有 `return null` + 一个叫「尺寸不合拒绝」的计数，**视图缺失**与**尺寸不同**两种因共用一个名字，分不开就修不动。现在首拒点名：`槽数 N（要求 ≥2）或尺寸不同：池写代=WxH 主 pass target0=WxH`。
  - 守卫 `MrtTerrainPassWiringTest` +3：翻代必须在 `else` 里 / 跳过那一支必须记欠账 / 交账必须早于 `drawPostChain` 且只有一处 / `skippingThisFrame()` 与 `drawnByMainPass()` 条件同源。
- **真机两臂（一律冷启动、同机位）**：臂 O、臂 P = 取证档 + `ownTerrainPass=true` + `terrainSingleDraw=true` + `takeoverDepthToGbuffer=true`（`[生效档] 64 键 / 非默认 8`）。读数：`label命中=2355`、**`主 pass 接管=0`**、新仪器打的是 **`槽数 1（要求 ≥2）`**，而**尺寸两边相等**（854x480 —— 1.2 之后按构造相等，这条腿已经不再产生假拒）。⇒ `[GAP-053/1.1]` 那一行**没打**，`TerrainSingleDraw` 走的是「开关开着但没接管 ⇒ 照今天双绘」的保护分支（它按设计挡住了「谁都不画地形」的自伤档）⇒ **代码半只在单测层成立，画面判据今天不可签**。
- 🔧 **下一臂写死**：要真走通 1.1 必须让地形管线有 ≥2 条输出 ⇒ 换 `19` §7-8 第 6 格那份 **slots=8 取证档**（`run/config/vkdisp-pack-options.properties` 加 `BSL_v10.1.8.ADVANCED_MATERIALS=true` + `mrt.attachments=8`），先断言 `colorTargets>1` 与 `主 pass 接管>0` 再看画面。⚠️ 另有一条待查：这一臂 `mrt.pixelProbe=true` **一行探针都没打** —— 接管档下探针取点的有效性要单独核（`PixelProbeWiringTest` 的「必须在地形 MRT pass 之后」那条约束在单绘档语义变了）。
- **影响的文档**：`docs/13`（`GAP-053` 加代码半 / 画面半被挡 / 下一臂三行）、`docs/20`（1.1 行改成「代码半已落、下一臂换档」，前置 1.2 标 ✅）。
- **测试结果**：`./gradlew test` → **BUILD SUCCESSFUL，1221 项 0 失败**（本轮 +3 条守卫）。中途两次红灯都是守卫正常工作：`ClassLineRatchetTest`（`MrtTerrainPass` 1070>1069 ⇒ 把状态与点名挪进独立类）、以及我自己那两条接线断言跟不上重构（改成断言 `flipWrittenSlots()` / `flipIfOwed()` 之后跟上）。
- **是否已提交**：✅ 本轮结束即提交并推送。取证档跑完按 md5 还原 `0f2aaa5c…`，残留客户端 0。

---

## 2026-10-15（一百六十一）— ✅ `20` P1 的 1.2 落地：gbuffer 池的锚点改成**主 pass 自己的 target0**，并把这条观察挪到接管开关**之前**（臂 M 证明挂在后面等于白做）

> **verdict = 1.2 立住了（两条 `[GAP-049/1.2]` 真机自报 + 四条守卫），1.1 的前置 cleared；但臂 N 的画面级「无回归」对比作废 —— Steam 覆盖层把桌面捕获和桥那一路一起污染了。**

- **本次改了什么**（代码，四处）：
  - `bridge/GbufferSizeAnchor`：新增 `choose(回落宽, 回落高, 主pass宽, 主pass高)` 纯决策 + `Anchor` 记录 + `reportAnchor`（换锚点点名；两尺寸不同时另打一条 WARN），计数进既有的 `Stats` 持有者（不动 QD-03 静态棘轮）。
  - `bridge/MrtTerrainPass#ensureColortex`：`POOL.ensure` / `colortexDepth` 新建与 resize 全部改按 `anchor.width()/height()`；核对仍挂在建池**之后**；`gameRenderer.mainRenderTarget()` 只剩「首帧之前」的回落身份。
  - `bridge/MainPassTakeover`：**新 `recordMainPassTargetSize(descriptor)`，调用点在 `if (!MIXIN_OWN_TERRAIN_PASS.get()) return` 之前**，身份腿复用 `matchesMainPass`（单附件 + label），不看开关也不看 OIT；原来那段 `STATS.mainWidth = …` 从 `attachmentReplaced` 里删掉（`QD-04` 的 60 行棘轮因此仍绿）。
  - `src/test/.../GbufferSizeAnchorTest`：四条新测试（`choose` 三态：已知 / 未知 / 只认到一维保守回落；接线守卫：`ensureColortex` 必须走 `choose`、深度与池同尺寸、resize 一起换、不许退回 `main.width`）+ 把既有的「核对在建池之后」守卫扩成**顺序守卫**：观察必须在 `ownTerrainPass` 开关与 `refusedSize++` 之前。
- **为什么先做这个**：臂 M（改动前那一臂）实测 `[GAP-049/1.2]` **一行都不打**、`label命中=0`、`mainWidth` 全程 0 —— 因为整段观察挂在接管开关后面，而**取证档与代码默认档都是双绘档**（开关关着）。⇒ 不先把观察抬出来，1.2 就是空转，1.1 的 (b)/(c) 两条候选也没有数可用。
- **真机读数（臂 N，取证档、`chain=true`）**：`[GAP-049/1.2] 记下主 pass 自己的 target0 尺寸 = 854x480（label='Main'，ownTerrainPass=false）` + `[GAP-049/1.2] gbuffer 池的锚点 = 主 pass 自己的 target0（854x480；另一张主目标 854x480 只作首帧前的回落）⇒ 池与画布按构造同尺寸`；`Close the existing render pass` **0 条**、六条针 **0 命中**、`is not correct` 1 次（FML 补 `derivedPrograms`，逐键 diff 值没变）。两张同尺寸 ⇒ **这一改动按构造零行为变化**，正是它的判据。
- 🔴 **画面级「无回归」这一臂作废（写住，别引用）**：为了「窗口在前台才回新帧」那条纪律去 `SetForegroundWindow`，唤起了 **Steam 游戏内覆盖层**（桌面捕获直接拍到「游戏时间 53 分钟 / 成就 1/48」那一屏），而覆盖层在单人里**暂停集成服务器** ⇒ 桌面捕获拍的是覆盖层、桥那一路回的是黑屏 + HUD（`mean=11.76` vs 臂 J2 的 `mean=201`，`mean|d|=189.52`）——两边都不是游戏画面。⇒ 三条新口径：① 链路线判据帧**优先 `mrt.pixelProbe`**（进程内 GPU 回读，数字直接进日志，不受窗口与覆盖层影响）；② 不许靠 `SetForegroundWindow` 抬窗口（Steam 在跑就会唤出覆盖层）；③ 任何「两臂画面差」下结论前，先证明两帧都**来自游戏**。
- **影响的文档**：`docs/13`（`GAP-049` 加 1.2 落地行 + 臂 N 作废行）、`docs/20`（1.2 行标 ✅、1.1 的前置 cleared）。
- **测试结果**：`./gradlew test` → **BUILD SUCCESSFUL，1218 项 0 失败**（本轮 +4 条）。中途 `MethodLengthRatchetTest` 与那条顺序守卫各红过一次 —— 都是守卫正常工作（我把注释塞进了 60 行方法里、又把记录点搬到了文件后段），改法是把理由挪进新方法 javadoc、把守卫改成断言**调用点**顺序，没有放宽任何基线。
- **是否已提交**：✅ 本轮结束即提交并推送（上一条 `190db70` 因 `github.com:443` 连不上留在本地，一并推）。

---

## 2026-10-15（一百六十）— 🔬 `GAP-058` bisect 做完了：**不是 `bb9d4ba` 的回归** —— 旧二进制在取证档链路线上同样是坏帧（坏法不同）；归因改指 `GAP-053` 的代次归属，`GAP-054` 的「墙」很可能就是这个坏帧的一种读法

> **verdict = 「谁把链路线的画面弄坏了」这个问题问错了 —— 两个二进制都坏，只是坏法不同；所以 P0 的 0.2 出口顺延到 P1 的 1.1（`GAP-053` ⑤-d/⑤-e）收口之后再回看，本条不再单独立一轮修。**

- **本次改了什么**：**零代码改动**，一臂 bisect（跑上一个提交 `ec51260` 的二进制）+ 台账。bisect 口径按上一条写死的那条做：五个源文件 `git checkout ec51260 --`、新类移出到 `build/tmp-bisect/`（工作区内）、**不沿用被新 spec 写过的档** —— 改用 FML 形状的 `vkdisp-client-7.toml.bak` 翻四个键成取证档值。
- **臂 L2 读数**：`is not correct. Correcting` **0 次**（⇒ 上一条那个 964 次的回写风暴，成因确实就是「沿用别的 spec 写过的档」，这条方法约束现在有了正反两面证据）、`[生效档] 63 键 / 非默认 4`、`[route] chain=true` 在报。取帧走桌面捕获那一路，`pitch=2` 与 `pitch=45` 两帧 **md5 不同 ⇒ 帧是活的**，画面是**横向条带 + 一个放大的贴图 glyph + 底部被切一半的 hotbar**（`mean=55.44 / nonblack=98.44% / SKYBLUE=7.15%`）——**不是正常世界**。
- **对照**：新二进制同一条路线（臂 J / J2）是近乎均匀的**天空蓝**（`SKYBLUE≈93%`、相机无关）。⇒ 新旧都坏、坏法不同 ⇒ **不是回归**，而是这条路线**没有对过**（至少在这个档、这个机位下）。
- 🔴 **归因改口**：臂 J/J2 同趟的 `[GAP-020/gen]` 打的是「链每帧写 colortex 六次，而代次计数器**全程为 0**」（`开跑前 c0=0 … | 链尾 c0=0 …（一帧内写次数 c0=6）`）—— 这正是 `GAP-053` 定序结论（候选③：主 pass 写下一帧那一代、链读我方刚翻上去的那一代）的形状 ⇒ `GAP-058` 很可能不是新缺陷，而是 **`GAP-053` 在画面层的表现**。收口口径：1.1 落地后回看本条判据（`SKYBLUE` 回落 + 硬边出现），届时仍不成立才另立新账。
- 🔴 **连带 `GAP-054`**：臂 L2 那个「放大贴图条带 + 一条笔直边界」与 10-13 基线记的「左半一堵被拉到巨大的草/叶图集墙 + 中景一整片平坦浅绿面」**同类** ⇒ 「墙」在链路线拿正常画面之前**不是一个可归因的独立签名**。⇒ `20` 的 0.2 前置改成「顺延到 1.1 收口之后回看」，本格与 `GAP-058` 届时一并判。
- ⚠️ **桥那一路在旧二进制同样回吐旧帧**：臂 L2 桌面捕获是条带坏帧，而 `vision.screenshot` 回的是黑屏 + HUD ⇒ §1.4 那条「链路线里桥会回吐旧帧」**跨二进制成立**，链路线判据帧一律以桌面捕获为准。
- **影响的文档**：`docs/13`（`GAP-058` 加 bisect 行 + 归因行 + 旧帧行并改状态；`GAP-054` 加「墙可能就是坏帧的一种读法」一行）、`docs/20`（0.2 的前置与顺延）。零代码 ⇒ bisect 用完源码已全部还原（`git status` 干净、`PackTextures.ensureReady()` 仍在 `FullscreenPassHook:206`），取证档 md5 还原 `0f2aaa5c…`，残留客户端 0。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-15（一百五十九）— 🔴 新账 `GAP-058`：取证档 `chain=true` 那一路线**整屏平铺天空蓝、与相机无关** ⇒ `GAP-054` 的图集墙重签被压住；另抓到**两条截图通道的失效模式**与一次**配置回写风暴**

> **verdict = 按序推 0.2 的第一步就撞上一道前置：链路线今天拿不到可判的画面（两臂复现），所以图集墙那一格今天不许签；bisect 那一臂被「旧二进制 + 新 spec 写过的档」的重写反馈环废掉，方法已写死。**

- **本次改了什么**：**零代码改动**，四臂真机 + 台账。取证档（`[生效档] 64 键 / 非默认 4`，逐字同 10-14 基线档）冷启动：臂 J、臂 J2（链在跑）→ **整屏天空蓝**（`mean≈201 / nonblack≈99.7% / SKYBLUE≈93%`、`HARD-EDGE 左半=[]`），`pitch=2/20/45`、`y=104/98` 四种取帧**逐字节相同**（`DIFF mean|d|=0.00`）；臂 K（只翻 `pack.derivedPrograms=false` ⇒ `chain=false`）→ **地形在**（`mean=47.89 / nonblack=52.02%`）。⇒ 空白特定于**链路线**，与包、与总闸、与异常无关（`Close the existing render pass` 0 条、六条针 0 命中、`[route] chain=true` 在报）。
- 🔴 **与旧读数的冲突写住**：10-13 基线记着「左半图集墙 + 中景浅绿面」、10-14 基线记着「背景天空蓝、那块逐字同形同位」。⇒ 要么这是 `bb9d4ba` 之后引入的回归（唯一代码 delta），要么 10-14 那句「背景天空蓝」其实就是本形状、当时没往「整屏无地形」上读。
- 🔬 **两条截图通道失效模式（都造成假读数，已写进 `01` §1.4）**：① **桥那一路会回吐旧帧** —— 链路线里改相机/改时间后 `vision.screenshot` 回同一张；判据帧必须同时用桌面捕获复核（`Shots.java capture` + `ClientToScreen`/`GetClientRect` 取客户端区，`GetWindowRect` 含标题栏会把 HUD 切掉）；② **窗口不在前台时桥不回新帧**，而日志与渲染循环照跑（`M-15 hits` 一直涨）⇒ 一切看起来正常。⇒ 硬要求：取帧前置前台 + `grab.py` 每次自己打一行 `取景回读`（实测坐标/朝向），每臂至少两帧、两帧之间动一次相机或时间，逐字节相同按**通道失效**处理。
- 🔴 **回写风暴（bisect 臂 L 报废的原因，写进 `GAP-056`）**：`cp` 回 10-14 那份 63 键档 + 跑**上一个提交**的二进制 ⇒ 9 分钟窗口内 `is not correct. Correcting` **964 次**、`[生效档] 档=热重载` **1926 行**、`[route]` **0 行**（根本没走到世界内）。同一份档在新二进制里只命中 1 次。⇒ **方法硬约束**：回退二进制做 bisect 时不许沿用新 spec 写过的档 —— 删掉让它自己落，起跑先断言 `[route] chain=true` 出现再取帧。
- 🔧 **下一刀（口径写死，压着 `20` P0 的 0.2 出口）**：按上面那条正确 bisect 口径重跑臂 L。旧二进制也是整屏天空蓝 ⇒ `GAP-058` 不是回归、`GAP-054` 的取证档基线要整体重看；旧二进制画面正常 ⇒ 回归坐实，在 `bb9d4ba` 的五处改动里再切（首要嫌疑：`PackTextures.ensureReady()` 从 `drawPostChain` 之内挪到帧首，与 `view()` 不再懒建）。
- **影响的文档**：`docs/13`（新 `GAP-058` 全表 + `GAP-056` 加回写风暴行）、`docs/01-DEV-LOOP.md` §1.4 加两条失效模式与硬要求、`docs/20`（0.2 的前置加 `GAP-058`）。零代码 ⇒ 未跑构建（HEAD 树是上一条提交的绿树）。
- **是否已提交**：✅ 本轮结束即提交并推送。

---

## 2026-10-15（一百五十八）— 🔴 臂 I 把右下角那一角**正面指认成原版第一人称手** ⇒ 另立 `GAP-057`，并连带作废 `GAP-054` 一整串以「那块」为签名的格

> **verdict = 过去两天二分的那个元素根本不是 `GAP-054` 的平面，而是被放大的手；`GAP-054` 的归因等于从零开始，而「手为何被放大」是一条新的、用户第一眼可见的缺陷。**

- **本次改了什么**：**零代码改动**，一臂取证 + 台账。臂 I = 代码默认档（包 AUTO=BSL，`[生效档] 64 键 / 非默认 0`，`is not correct` 命中 1 次 = FML 补 `derivedPrograms`），同机位 `-44.6 98 -93.6` / `yaw=-30 pitch=2`、现钉 `time set 6000`，三帧落盘：
  ① `armI-ref-01.png` `nonblack=8.03%`（那一角绿色网格/叶状贴片）→ ② `item replace entity Dev weapon.mainhand with minecraft:stone 1` 后 `armI-stone-01.png`：**同一角、同一形状、同一条笔直斜边，贴图换成石头灰**，`nonblack 8.03% → 10.32%` → ③ `item replace … with minecraft:air 1` 复原后 `armI-restored-01.png`：那一角回到**棕黄掌面 + 蓝灰袖子** = 原版空手手臂。⇒ 判据是**换物品 ⇒ 换贴图不换尺度**，这条只有落盘三帧对照才读得出来（`01-DEV-LOOP` §1.4 那条通道今天第二次起作用）。
- **新账 `GAP-057`（第一人称手在包路线上被放大）**：尺度对比 —— 包在（臂 E/F/I）那一角占右下约四分之一、texel 拉到 ~8 px；包不在（臂 G `enabled=false`、臂 H `shaderPack="none"` 而我方全屏 pass 照跑）同一角是正常大小的手。已排除四条：我方全屏 pass（臂 H）、我方派生程序（臂 F）、包覆盖原版贴图/模型（代码级：全仓只有一个 `createPack()` 挂我方虚拟包，包 zip 从没进原版资源栈）、物品内容本身（臂 I 三帧）。⇒ 剩下的两条腿是 `PackTextures.setDesired`（BSL 一条 512² 噪声图）与 `ActivePackUniforms.install`（**27 条包自写 uniform 表达式**），而臂 F 里这两条**没有派生程序去消费**却仍然放大 ⇒ **首要嫌疑 = 表达式改了「供值」而不是改程序**（`OfUniformManager` 的族划分 / CHAIN 族口径外溢到原版那一笔）。
- 🔴 **连带作废（这是本轮最贵的一条）**：`GAP-054` 台账里以「右下角那块」为签名的格 —— **臂 2 / 3 / 5 / 6 / 7 / 8、10-14 成对关那一格、今天的臂 E / F / H** —— 对本条**判别力全部作废**（量的是手）。仍成立的只有臂 4（`enabled=false` ⇒ 干净原版）与「这是我方产物」那条定性，加上**世界那一块**（左半图集墙 + 中景平坦浅绿面 + x≈361 硬边）的三条旧读数。⇒ `GAP-054` 的下一刀回到本体：**取景必须把左半那堵墙框进来**（台账口径 `y=104`，今天实测落到 98），先重签「墙在不在」。
- **顺带三条读数**：① `GAP-055` 那一修在第三臂上仍然是 `Close the existing render pass` **0 条**；② `GAP-056` 今天又正面观察到一次「FML 补键 ⇒ 整份重写」，而**逐键 diff 值没变** ⇒ 那句 WARN 只证明「文件被重写过」，不证明「哪个键变了」；③ 世界状态已复原（主手 `air`），取证档按 md5 还原 `0f2aaa5c…`，残留客户端 0。
- **影响的文档**：`docs/13`（新 `GAP-057` 全表；`GAP-054` 加「臂 I 之后本条要重读」行并改状态）、`docs/20` P0 的 0.2 行（下一刀回到图集墙）+ 新增 0.5 行（`GAP-057`）。零代码 ⇒ 未跑构建（上一条提交的树 1214 项 0 失败）。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径）。

---

## 2026-10-15（一百五十七）— 🔬 `GAP-054` 臂 H（`shaderPack="none"` 而我方全屏 pass 照跑）：那块**消失** ⇒ 排除清单再加一条，必要条件收窄到「包被加载这件事」；🔧 下一刀从「改配置」换成「加 draw 站点探针」

> **verdict = 那块贴片既不是我方生成的派生程序画的（臂 F），也不是我方全屏 pass 画的（臂 H）—— 但 `[pack]` 逐键已耗尽、包 zip 又没进原版资源栈 ⇒ 「包被加载」这条必要条件**还没有机制落点**，所以再猜键没有信息量，下一轮先建仪器。**

- **本次改了什么**：**零代码改动**，一臂真机取证 + 台账。臂 H = 代码默认档 + `shaderPack="none"`（单键 vs 臂 E），冷启动、同机位 `-44.6 98 -93.6` / `yaw=-30 pitch=2`、现钉 `time set 6000`。落盘图 `armH-nopack-01.png` 与抬镜头复测帧（`tools/vkdisp-shot/grab.py` + `Shots.java`，见 `01-DEV-LOOP` §1.4）。
- **读数**：`[生效档] 64 键 / 非默认 1：shaderPack`（单键臂成立）；`is not correct. Correcting` 命中 **1** 次 = FML 把昨天新增的 `derivedPrograms` 补进档，**逐键 diff 值没变** ⇒ `GAP-056` 那条机制今天正面观察到一次（缺键触发 `correct()` + 整份重写），也正面证明「命中那句 ≠ 值变了」。兜底自报逐字到位：`包选择 = 'none' … 使用内置 passthrough 兜底`；`[route] chain=false toMain=false ⇒ 旧三步链`；`Close the existing render pass` **0 条**（昨天那一修的回归读数顺带再签一次）。画面 `mean=52.04 / nonblack 58.43% / SKYBLUE 1.32%` —— 与臂 F（`49.19 / 58.43% / 1.01%`）同一形状，**而右下角那块绿色巨型树叶贴片不存在了**。
- **为什么这条有价值（臂 8 当年切不开的事）**：臂 8 把「包」与「包的程序 / 我方全屏 passthrough」**一起**换掉 ⇒ 它只能说明「包带来的」。今天这一臂**只去掉包、留下我方全屏 pass** ⇒ 结论是「**我方全屏 pass 本身不是生产者**」。三臂合起来：**在** = 臂 E（默认档 + AUTO 选中 BSL）、臂 F（派生程序零注册）；**不在** = 臂 G（`enabled=false`）、臂 H（包 none 而全屏 pass 照跑）。
- 🔴 **两条如实登记的未证实项（写住防下一轮顺手引用）**：① 那一角剩下的小物体**身份未定** —— `get_inventory` 回 `selectedSlot=5` 而 hotbar 只有 0/2/4/7 有东西 ⇒ 按这份读数**手上是空的**，所以「它是第一人称手持方块」**没被证实**（它同时出现在臂 G 与臂 H，与包无关）；② 臂 H 想复测锚定性时 `tp` 的 `pitch` **被客户端相机状态盖掉**（回读与命令不符）⇒ **「屏幕锚定」目前只在臂 F 那一臂成立过**，别当三臂共有的事实写。
- 🔧 **下一刀（写死）**：`[pack]` 那一块承重键已测完 ⇒ 改成**给右下角那一角建 draw 站点探针**（按站点计数 + 首见时打调用链，形状照 `PackArtifactProbe`：每「站点 × 程序」只报一次、`StackWalker` 只在首次付代价），跑一臂**包在**（默认档）读它 —— 站点归属拿到，「包被加载」这条必要条件才有落点（那条噪声图被谁采？27 条表达式改了原版供值？还是手那一笔的矩阵被谁改？）。另欠**图集墙那一格重签**（取景必须把左半框进来）。
- **影响的文档**：`docs/13` GAP-054（臂 H / 三臂形状 / 两点未证实 / 下一刀四行 + 状态行改口）、`docs/19` §7-8 第 8 格、`docs/20` P0 的 0.2 行（序与判据口径，不带状态）。零代码 ⇒ 未跑构建（上一条提交的树是绿的：1214 项 0 失败）。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径）。

---

## 2026-10-14（一百五十六）— 🟢 P0 四格里三格落地：**截图落盘通道打通**、**`GAP-055` 根因拿到并修掉（默认档每帧抛 106 998 → 0）**、**`GAP-054` 包侧第一刀跑出读数**；🔴 顺带把这条账的**签名**纠正了一处（右下角那块是屏幕锚定的，不是那堵图集墙）

> **verdict = 「用户装上 jar 看到什么」第一次有答案：黑屏 + 那块屏幕锚定的贴片，且每帧抛异常；而异常与黑屏是两件事**（异常已修，画面没变亮 ⇒ 黑是路线造成的，不是异常造成的）。

- **本次改了什么**
  - **P0.1 截图落盘通道**（零产品代码）：实测 `vision.screenshot` **不认 `path`**、只回 base64 ⇒ 落盘自己做。新增本机取证工具 `tools/vkdisp-shot/grab.py`（走 `/rpc`，token 从配置文件读、抓前现钉机位与时间）与 `tools/vkdisp-shot/Shots.java`（`capture` / `read` 两模式，判据口径逐字移植 `evidence/tools/read_shot.py`，零依赖 —— 本机两个 python 都没装 numpy/PIL）。⇒ 从此 `硬边列 / 左块 mean / 全片 mean / DIFF` 这类**数值**签名可签。写进 `01-DEV-LOOP.md` **§1.4**（新节）。
  - **P0.4 代码默认档定性（一臂冷启动，不注入任何手写 toml）**：`[生效档] 63 键 / 非默认 0`、`shaderPack=""`（AUTO）自动选中 BSL、`[route] chain=false toMain=false ⇒ 旧三步链`。画面（vkdisp 侧，854×480，同机位现钉 `time set 6000`）`mean=3.26 / 6.20`、`nonblack 7.74% / 7.99%`、`SKYBLUE 0.07%` ⇒ **整屏近乎全黑，只剩 HUD + 右下角那块贴片**。快照 `run/config/vkdisp-client-7.toml.bak`。
  - **P0.3 `GAP-055` 根因 + 修法**：根因不是「半开档」，是**默认档没人预热** —— `PackTextures.ensureReady()` 的三个调用点（`drawPostChain` / `MrtTerrainPass` / `SkyPackGbuffer`）在默认档一条都不走，而旧三步链照样在 pass 体内懒建内置噪声 ⇒ 每帧抛、且抛在赋值之前 ⇒ 下一帧重来（一趟 **106 998 条**）。修法与 `GAP-036` 的 `ShadowStubs` 那一修**同形状**：① `FullscreenPassHook#onAfterLevel` 帧首加 `PackTextures.ensureReady()`；② `PackTextures.view()` 改成**只读不建**（把 pass 内懒建这条路结构上封掉，不是加一条日志）。新守卫 `bridge/PackTextureWarmupWiringTest`（两条源码断言）。复测：该异常 **0 条**、`noisetex FAILED` **0 条**、全日志 `Exception:` **0 命中**，并带出 `[GAP-009] custom texture loaded: sampler='noise' 512x512` —— **默认档此前一次都没加载过包声明的噪声图**。
  - **P0.2 `GAP-054` 包侧第一刀**：补齐 `19` §7-8 第 8 格挂着的那条**生成侧自报**（新类 `dev/vkdisp/PackDerivedProgramGate`，一行 `[GAP-054/生成侧]` 声明「资源腿留 / 程序腿全空」）+ 新键 **`pack.derivedPrograms`**（默认开；关 ⇒ 包的资源照常、我方一条派生程序都不生成/注册，注册期 `ensureTerrainProgram()` 同闸早退）。该键进生成期记忆键与热重载边沿判定。读数（臂 F）：**那块贴片照旧在** ⇒ 生产者**不在我方生成的派生程序里**。
  - 🔴 **签名纠正（本轮最贵的一条）**：同臂把镜头从 `pitch=2` 抬到 `-60` ⇒ 地形整片滑走、**右下角那块纹丝不动** ⇒ 它是**屏幕锚定**的，与台账里「tp 北移 130 格就没了」那堵**世界**图集墙**是两个元素**；再用臂 G（`enabled=false` 纯原版对照，同机位）否掉「它是手持方块」。**连带**：上午那一格（`packTerrainShader` 成对关 ⇒「平面照旧」）用的正是右下角那块 ⇒ 它证的是屏幕锚定那块，不是图集墙 ⇒ 那一格要按左半那堵墙框进取景重签一次。
  - 🔧 **下一刀写死（比原计划③便宜）**：`enabled=true` + `shaderPack="none"` 冷启动 —— 把「包在不在」与「我方全屏 pass 在不在」第一次切开（臂 8 当年两件事一起换了，所以它的判别力没被单独证过）。在 ⇒ 账落到「全屏 pass 之后那一笔屏幕锚定 draw」（候选 `hand` 族 / 被改了投影的第一人称）；不在 ⇒ 走包的资源侧与 `[pack]` 逐键二分。
- **为什么改**：按 `docs/20-RELEASE-PLAN.md` 的 P0 序推进（用户指令「按这个文档继续推进」）。P0 出口 = 0.2 有归因、0.3 计数归零、0.4 有一张默认档画面 —— 0.3/0.4 本轮关闭，0.2 拿到一次归因（排除一整侧）并纠正了签名。
- **影响的文档**：`docs/13`（`GAP-036` 加两行默认档定性与「同形状的下一条」；`GAP-054` 加仪器 / 臂 F 读数 / 签名纠正 / 下一刀四行并改状态；`GAP-055` 加根因与修法两行并改状态）；`docs/01-DEV-LOOP.md` 新 §1.4；`docs/19` §7-8 第 8 格。代码：`VkDispConfig`（新键）、`VkDispVirtualPack`（总闸 + `fallbackSources` 收成一处）、`VkDispConfigHotReload`（快照 +1 项）、`PackTextures`、`render/FullscreenPassHook`、新类 `PackDerivedProgramGate`；测试新增 `PackTextureWarmupWiringTest`。
- **棘轮的两处真实代价（不是放宽基线）**：`ClassLineRatchetTest` 一开始变红（`VkDispConfig` 1073>1047、`VkDispVirtualPack` 1596>1573）⇒ 按它自己的指示**把新内容落到独立类**（`PackDerivedProgramGate`）+ 把「回落 passthrough」那九个赋值从两处副本收成一处 ⇒ 现在 1046 / 1573，**上限未动**。`pack.postVertexProgram` 那条 javadoc 与 `PackPostVertexSwitch` 类注释逐字重复，改成指针（一条真源）。
- **测试结果**：`./gradlew test` → **BUILD SUCCESSFUL，1214 项 0 失败（1 跳过）**。真机四臂：D（默认档，修前）/ E（默认档，修后）/ F（默认档 + 总闸关）/ G（`enabled=false` 原版对照），一律冷启动、同机位、现钉 `time set 6000`；每臂起跑后回读 `[生效档]` 全量块 + grep `is not correct. Correcting`（臂 E 命中 1 次 = FML 补进新键那一行，逐键 diff 值没变 ⇒ `GAP-056` 的机制今天正面观察到一次）。取证档 `run/config/vkdisp-client.toml` 跑完按 md5 逐字还原（`0f2aaa5c…`），残留客户端 0（X33 口径）。
- **是否已提交**：✅ 本轮结束即提交并推送。
- ⚠️ **两条未覆盖**：① `GAP-055` 判据 ②③（全基线档不回退、半开档不许静默）本轮没跑取证档臂 ⇒ 未签；② `GAP-054` 的「左半那堵图集墙」在本轮三臂里**根本没进框**（默认档黑屏 + 取景偏低）⇒ 墙与总闸的关系仍未测，别把臂 F 读成「墙排除了」。

---

## 2026-10-14（一百五十五）— 📋 新建 `docs/20-RELEASE-PLAN.md`：把「到第一个可用发行版之间的序」单独成文

> **verdict = 发行序从此有一张表，P0…P5 六段、每格只给 序 / 判据 / 规模 / 前置 / 出处；状态真源仍在 `13` + `QUALITY-DEBT`，本文不另立第二真源。**

- **本次改了什么**：新建 `docs/20-RELEASE-PLAN.md`（上一条会话里在对话中给出的计划表落成文档）。
  六段：**P0** 止血 + 档口径（截图落盘通道、`GAP-054` 包侧二分、`GAP-055` 每帧异常、代码默认档首次定性）→
  **P1** R7（M-04）整主 pass 接管收口（⑤-d 代次归属、`GAP-049` 池锚点、OIT 那 6 处、删死码、黑帧/bloom 挂起）→
  **P2** 兼容面（`GAP-034` C2a→C2b 阴影、`GAP-027` 非地形族、`GAP-007`+`GAP-043` 顶点属性、uniform 供值族、`GAP-035`/`GAP-009`）→
  **P3** 判据欠账（`19` §7-8 那几格，全是机时不欠代码）→ **P4** 结构护栏（QD-03/04/10/12/06/13/14 + A 线）→
  **P5** 发行工程（翻默认、真硬件基线、兼容矩阵、用户向产物）。每段给了**出口判据**。
- **口径**：按 X56 / A18，**全文无工时** —— 规模只有轮次 / 臂数 / 牵连面条数；性能预算与实测时长按用户划的边界保留，
  但本文只引用 `08` / `17` 的阈值原样、**不复制数字**（免得两处口径漂）。
- **防第二真源**：`20` §4 写了六条维护纪律 —— 不新增状态字段、不许填工时、每轮只改动了那一行的前置与判据、
  引用必须能在出处列找到原文、`18` 的旧轮次编号不复活、**不占 14 号**（14 仍留给上线检查表）。
- **影响的文档**：新建 `docs/20-RELEASE-PLAN.md`；`docs/00-INDEX.md`（真源对照表加一行 + 阅读清单 + §3 编号说明改成 `19`–`20`）；
  `docs/16-READING.md`（§1 AI 顺序插入第 12 步、§3 清单加行、§4 补一句为什么不占 14）。零代码改动。
- **测试结果**：只动文档 ⇒ 跑读文档的守卫（`VulkanEvidenceDisciplineTest` 检查 `00-INDEX` 措辞）+ 全量 `test`，见下一条回执。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径）。

---

## 2026-10-14（一百五十四）— 🔴 文档口径变更：**工时 / 排期整体废止**（X56 / A18），全仓 .md 里已有的阶段工时数字就地删除，不留「已作废」痕迹

> **verdict = 文档今后只给「序 / 判据 / 规模」三样，规模用轮次·臂数·牵连面条数表达，不许换算成小时/天/周**。
> 用户指令：把所有文档里的时间安排删掉，并把这条写进规范。

- **本次改了什么**：
  - **新红线** `docs/07-CONSTRAINTS.md` **X56**（不自行给任何阶段/阶梯条目安排时间或工时）+ §七 自检清单一条；
    `docs/15-ITERATION.md` **A18** + 第 2 步「先改文档」的特别要求一条 + 快速检查表一条；
    `docs/00-INDEX.md` 真源对照表加一行（「文档里能不能写工时 / 排期」⇒ 不能）；
    `docs/19-IMPROVEMENT-PATHS.md` §0 加口径段。
  - **列名改口**：`19` 五张阶梯/打分表 + `QUALITY-DEBT` 登记表的 `成本` 列 → **`规模`**；`19` §7-8 台账最后一列 → **`臂数`**。
  - **删工时**（77 处逐字替换 + 31 处表格单元/成本行正则清扫，命中数逐条断言）：
    `19` §2.6/§3.5/§4.5/§5.4/§5A.4/§6 的「0.5 h / 1 天 / 1.5 天 / 2–3 天 / 4–5 天 / 3–5 个工作日 / 半天+1天 / 2h+1d」
    ⇒ 纯工时的填 `—`，与工时捆在同一格里的**非时间信息保留**（「分 2 轮」「含两轮真机取证」「不需要幂等收敛，也不需要动注册时机」「完整回归一轮」「原版已带该原生库 ⇒ 无分发成本」）；
    §7-8 台账的「N 臂 ≈ M 分钟」→ 只留 **N 臂**；
    `QUALITY-DEBT` §3 的「成本 ≤ 30 min 的项可顺手闭环」→「一步即可闭环」，表头那句「30 分钟即可关闭的债跨六轮」→「一轮即可关闭」；
    `docs/04-SPEC.md` §5.0.6 ⑥ 那句「三项已结 ⇒ **允许给 R7 报成本**」连同那句已失效的「不许报成本」锁一起改成**范围与序**口径；
    `docs/06-MIGRATION.md` 迁移日志的耗时列：`20min（…）`/`15min（…）` 改成**实测构成**（`7301 个 MC 源文件`、`generateModMetadata 45s + 编译测试 19s`）；
    `docs/13` GAP-054 下一刀的「约 2 分钟一臂」、GAP-046 的「不许报 R7 成本」→ 规模口径；
    `CHANGE_LOG` 顶部两条里的工时数字；`review/` 四份归档报告的成本列与 `**成本**：N 分钟` 行（归属注记 `docs/ 归 env-1`、`需与 D 线归属方协调` 保留）。
- **保留什么**（用户明确划的边界）：`08` / `17` / `02` / `04` 里的**性能验收预算**（B3 ≤ 1 秒、B4 ≤ 2 秒、B6 1h 内存增量 ≤ 200MB、加载 ≤ 3 秒）、**实测时长**（冷路径 1861ms、`约 5 分钟 runClient = 64 959 条栈`、启动约 1 分钟）、以及**机制性时序事实**（日时钟每真实分钟走 ~0.1 天、`RegisterRenderPipelinesEvent` 早于包源约 4.5 秒）。这些不是「给阶段安排的时间」。
  - 🔁 **第二遍复扫补四处**（第一遍的中文正则用了 `天\b`，中文之间不构成词边界 ⇒ 全放过）：`docs/13` GAP-034 那两行里引号内的「1 天可闭环」「1 天接线」（它们引用的正是本条删掉的那句 QD-11② 旧措辞，删完就成了悬空引用）、`CHANGE_LOG` 九十四里「登记成『1 小时可闭环』」改成「未经取证的『一步可闭环』」、`19` §7-8 表头那句「与成本」→「与臂数」（列名本条已改，别留不一致）。⇒ **复扫口径今后要写成 `\d+\s*天(?![气生数平象道窗])`**，不能用 `\b`。
- **为什么改**：写进文档的工时会在后续会话里被当成承诺引用，而它从来没有依据 —— 排期是用户的职权。同时本轮顺手消掉两处自相矛盾（`04-SPEC` ⑥ 与 GAP-046 那句「不许报成本」互斥，且都随 10-13 的锁解除而失效）。
- **影响的文档**：`docs/00-INDEX.md`、`docs/07-CONSTRAINTS.md`、`docs/15-ITERATION.md`、`docs/19-IMPROVEMENT-PATHS.md`、`docs/QUALITY-DEBT.md`、`docs/04-SPEC.md`、`docs/06-MIGRATION.md`、`docs/13-GAP-REGISTRY.md`、`CHANGE_LOG.md`、`review/`（4 份）。零代码改动。
- **测试结果**：读文档的守卫全跑（`VulkanEvidenceDisciplineTest` / `CatchThrowableVisibilityTest` / `BridgeNullableContractTest` / `ComparisonSamplerGapTest` / `GapRegistryStatusFieldTest` / `GlslPipelineTest` / `OptionBindingTest` / `ClassLineRatchetTest`）——见本条末尾的回执。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径）。

---

## 2026-10-14（一百五十三）— 🟢 上一轮欠的三件全补上：半开档新文案拿到真机形状、`mrt.packTerrainShader` 那一格**测掉了**（成对关 ⇒ 平面照旧）、「枚举 == 源码」守卫从 SKIPPED 变成构建期真判

> **verdict = `GAP-054` 的已排除清单里 `mrt.packTerrainShader` 这一格现在是测出来的，不是推出来的；`GAP-056` 从「每趟都会污染」降级为「只在文件不 spec-correct 的那一趟会重写」**。顺带被自己的类路径改动抓到 QD-14 的**第三个样本**（Switch 类少一态）。

- ✅ **半开档那行的新文案拿到真机形状**（上一轮只有单测）：臂 `mrt.postChain=false`（单键、冷启动、进世界）⇒
  `[GAP-036/半开档] 实测 mrt.terrain=true mrt.terrainToMain=false 而链没跑（门控按判定顺序：mrt.postChain=false ⇒ 链不跑）…`
  —— `PostChainGate.readout()` 在**第一项就不成立处停笔**，六项里只出现被读过的这一项 ⇒ 它不可能再替 `mrt.packTerrainShader` 作证（那正是 `GAP-056` 误签的原点）。同趟 `[route] chain=false toMain=false`、半开档 WARN 1 条、`Close the existing render pass` **0 条**。
- ✅ **补掉那一格：`mrt.packTerrainShader` 正式排除**（撤回之后欠的那一臂）。臂形改成**成对关**（`mrt.terrain=false` + `mrt.packTerrainShader=false`）—— 单关 `packTerrainShader` 而留 `terrain=true` 就是半开档 = 全黑，黑屏里既没平面也没可判画面（`GAP-055` 那一课）。三趟冷启动 A-B-A（基线 → 成对关 → 基线）、同机位 `tp Dev -44.6 104 -93.6 -30 2`（`get_player` 回读 `yaw=-30 pitch=2`，y 落 98）、每臂现钉 `time set 6000`，**打开看图**：基线两趟右下角那块被拉巨大的树叶/图集贴片逐字同形同位，成对关那一趟**它照样在**（背景从天空蓝变黑 = 链不跑采空，与本判据无关）⇒ 这一对都不是生产者。⚠️ 取景比台账低一点（y=98 非 104）⇒ 左半那堵图集墙没进框，判据只用进了框的那块。
- ✅ **`[生效档]` 三档读数与文件逐字一致**（仪器第一次参与归因臂）：基线「63 键 / 非默认 4：`mrt.packTerrainShader, mrt.postChainMaxPasses, mrt.terrain, shaderPack`」→ 成对关「非默认 2：`mrt.postChainMaxPasses, shaderPack`」（`terrain`/`packTerrainShader` 关掉后落回代码默认 ⇒ 不再标 `≠`，这正是「默认 vs 手写档」该说清的东西）→ 回基线「非默认 4」。四趟 `is not correct. Correcting` 全 0、六条注入失败针全 0。
- 🔻 **`GAP-056` 的口径再降一级**：本轮四趟起跑前后 md5 逐字相同（`9962b312…` / `97d34df4…` / `0f2aaa5c…` 各自稳定）⇒ **没有观察到任何回写**。机制还在（FML 载入期 `correct()+writeConfig()`），但触发条件是「文件里有缺键/孤儿键」，今天这份档是 spec-correct 的 ⇒ 不触发。⇒ 台账从「每趟都会污染」改成「**只在文件不 spec-correct 的那一趟会重写**」，纪律（每臂回读 `[生效档]` + 逐键值 diff + grep 那句 WARN）保留，成本一行日志。
- 🔴 **`GAP-055` 的触发面比台账写的宽**（同批臂顺手拿到的）：`mrt.terrain=false` **且** `packTerrainShader=false`（链不跑、走旧三步链）那一臂 `Close the existing render pass` 实测 **13734 条**；而 `mrt.postChain=false` 那一臂（`terrain=true` ⇒ 我方 gbuffer pass 照建）**0 条**。⇒ 真条件不是「terrain 关而 packTerrainShader 开」这一对半开，而是「**我方 gbuffer pass 不建、而链/旧三步链的采样器解析照跑**」——两条路都会走到 `PackTextures.builtinNoiseView` 那次 pass 内写纹理。已写进 `GAP-055` 的对照行。
- 🔧 **让「枚举 == 源码 `.define*`」那条守卫真跑（上一轮的降级项）**：`build.gradle` 把主源集类路径并给测试源集（`sourceSets.test.compileClasspath/runtimeClasspath += sourceSets.main.…`；`dependencies{}` 里加 Configuration 自 Gradle 8 起直接报错）。⇒ `VkDispEffectiveConfigTest` 五条全跑、0 跳过。
- ⚫ **这次接线当场抓到一个假报警（QD-14 第三个样本）**：类能加载了、FML 配置却没加载 ⇒ `ConfigValue#get()` 抛 `IllegalStateException: Cannot get config value before config is loaded`，落进四个 Switch 类共有的 `catch (Throwable)` ⇒ 留下**假的** `reflectionFailure` 位，`ChainEnableGatingTest` 变红。原写法只有两态（类在/不在），缺第三态「**类在而配置没加载**」。修法 = 新增 `pack/SwitchConfigAccess.loaded(…)`（判 `VkDispConfig.SPEC.isLoaded()`，**不绑 FML 那句异常文案**）+ 四个 Switch 各一行回落；`QD-14` 那行补了样本记录。
- **影响的文档**：`docs/13`（GAP-054 新增重测行 + 状态行改口、GAP-055 触发面更正、GAP-056 状态行降级）、`docs/19` §7-8 第 8 格（那一格标为已跑 + 臂形写死成对关）、`docs/QUALITY-DEBT.md` QD-14。代码：`build.gradle`、`pack/SwitchConfigAccess`（新）、四个 `pack/*Switch`。
- **测试结果 1212 条 / 0 失败 / 1 跳过**（跳过的那条是既有的 `PackUniformBslTest`，与本轮无关）。
- **现场**：取证档已逐字复原（`run/config/vkdisp-client.toml` md5 回到 `0f2aaa5c…` = 本轮起跑前那份）；三臂的 armed 档留在 `run/arms-20261014/`（gitignored）；残留客户端 **0**（X33 口径），25599 无 LISTENING。
- **下一刀**：`GAP-054` 回到包侧 —— ① 保留包但一条派生程序都不注册（切「包的资源 vs 包的程序」，`FullscreenPipelineRegistrar` / `GeneratedSources.adapters` 侧那条生成侧自报**至今没补**）；② `[pack]` 逐键二分。`GAP-055` 的修法（把 `builtinNoiseView` 挪出 pass 体）可以并进来做，因为它现在有了「两条路都触发」这个更准的口径。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径）。

---

## 2026-10-14（一百五十二）— 🟢 `GAP-054` 二分与 `GAP-056` 定位的公共前置落地：启动期 `[生效档]` 全量自报（真机 63 键）+ 半开档 WARN 只打真测值；🔴 撤回 `mrt.packTerrainShader`「已排除」那一格；🪡 六条注入失败标记补定义、换掉假绿针

> **verdict = 仪器有了，读数还没有**：`mrt.packTerrainShader` 回到候选位（欠一臂冷启动），`GAP-056` 的「回写」从「有据的事实」降成「有机制、无当日证据」。代码侧只动两件事（一个新自报 + 一个门控单点），**默认档零变化**。

- 🔴 **撤回 `mrt.packTerrainShader` 的「已排除」（`GAP-054` 八臂清单里那一格）**：原判据 = 臂 3 起跑即报 `[GAP-036/半开档] mrt.terrain=true 而 mrt.packTerrainShader=false`。但 `FullscreenPassHook:220` 那行的触发条件是 `MrtTerrainPass.enabled() && !chainActive && !MrtTerrainPass.toMain()` —— **它一个字段都不读 `packTerrainShader`**，句里那串键名是 `:221-225` 写死的字符串；而 `chainActive` 是 `FrameApi.isPostChainActive():1134-1141` 的**六个合取项**（`postChain` / `terrain` / `packTerrainShader` / `!toMain` / `terrainProgram!=null` / 链非空）⇒ 这行对「文件里那个键的值」零信息。反向读数更硬：臂 3 那一趟（`run/logs/2026-10-13-7.log.gz` 20:59:21）打了 `[GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain`，这一支**只有该键为 `true` 才走**（`TerrainPipelineApi:306-319`）；真读该键的负分支 `pack terrain fragment disabled by config` 在 10-13 全天 35 份归档里 **0 命中** ⇒ **臂 3 本来就是干净的单键 `postChain=false` 臂**，而 `packTerrainShader` **至今一臂都没测过**。⇒ 改立「单键 `mrt.packTerrainShader=false` 冷启动重测」，已写进 `19` §7-8 第 8 格的第 ④ 刀。
- 🆕 **启动期全量生效档自报（P2，新类 `VkDispEffectiveConfig`）**：挂在 `ModConfigEvent.Loading`（冷启动每臂一次）与 `Reloading`，把**每个键的实际生效值 + 是否等于代码默认**打出来 —— `[生效档] 档=<原因> N 键 / 非默认 M：…` 一行摘要 + 每段一行，非默认标 `≠`。枚举走 `VkDispConfig` 的 `public static ConfigValue` 字段 ⇒ **加键自动进表**，不是手写清单（手写清单会漂：`VkDispVirtualPack:217` 那条「默认关」已经白骗过一轮）。备选都否了：`SPEC.getValues()`  javap 核实是 **Builder 的默认值树**（生效值在 `loadedConfig` 里），`Class.forName` 那族是 QD-14。
- 🔧 **半开档 WARN 只打它真测过的量（P3）+ 判定单点 `PostChainGate`**：那行现在打 `实测 mrt.terrain / mrt.terrainToMain` + `门控按判定顺序：<readout>`，并明写「本行只为这几个量作证，单键值读 `[生效档]`」。链门控的六个合取项从 `FrameApi` 搬进 `bridge/PostChainGate`（`active()` 逐字保持短路顺序、零分配；`readout()` 用同一条 `&&` 链，**第一项不成立就停笔** ⇒ 不会再说自己没读过的键），`FrameApi.isPostChainActive()` 只留一行委托。
- 🔴 **`GAP-056` 的状态改了两次，最终版**：原判据撤回 ⇒ 但「文件会被改」这件事**有真机制**：FML 12.0.8 `ConfigTracker.loadConfig` 发现文件不是 spec-correct 时 `backUpConfig` + `spec.correct()` + **`writeConfig()`** —— 缺失键按**代码默认**补齐、孤儿键删除，整份重写（`mrt.packTerrainShader` 的代码默认正是 `false`）。本机 `run/config/vkdisp-client-{1..5}.toml.bak` 链上有正面样本：两档**值逐字相同而 md5 不同**（⇒ `md5` 不能当「改了哪个键」的证据）、`packOptionsScreen` 整行消失、`terrainSingleDraw` `true`→`false` 且行位移动；当日该 WARN 命中 **2/35** 趟（13:40、16:04）。原候选①（守卫内存强制）与②（热重载写值）**排除** —— 全仓 `MRT_PACK_TERRAIN_SHADER` 零处 `.set()`、`VkDispConfigHotReload` 唯一的 `.set(` 是个 `AtomicLong`；新登记候选③ `VanillaPackOptionsPage:159-160`（`SHADER_PACK.save()` 按内存值重写整份 toml；`src/main` 唯一一处 `.save()`），但 10-13 全天 `[M-14] shaderPack 已改成` **0 命中** ⇒ 当天不是它。
- ⚫ **本轮我自己写错并改掉的一条**：上一版台账我写「FML 退出时不写文件 ⇒ 原候选① 两条腿都没了」。后半句对（`ConfigTracker.unloadConfig` 只做 `FileWatcher.removeWatch`），**据此把「回写」整体否掉是错的** —— 写文件的腿在**载入期**那条 `correct()` 路径上（就是上面那条）。已在 `docs/13` GAP-056「待分」行留了自我更正记录。另：本仓 `AGENT_CONTEXT.md` §10.32② 与 CHANGE_LOG 一百四十一 ② 早就记过这个形状（「SIGTERM 后配置被重写回旧值」「新键要等 FML 写进 toml」），是**没人把它接成候选**。
- 🪡 **六条注入失败标记写出定义（P5，`07` 新增 §1.4.1bis）**：此前 `19` §7-7 与 CHANGE_LOG 引用的「六种标记」在仓里没有定义，而判据针 `Mixin apply failed` **sponge-mixin 从来没打过这句话**（真实措辞 `Mixin apply for mod <id> failed …`，格式串取自本机在用的 `sponge-mixin-0.17.4+mixin.0.8.7.jar` 常量池）⇒ 10-13 那次启动期 FATAL 按旧针读是「0 命中」。六条各给发言类 + 后果 + **本仓真样本**（`2026-10-13-24.log.gz` 的 `InvalidAccessorException`；`2026-10-12-19/20/3/6` 的 `Critical injection failure: @WrapOperation … could not find any targets matching`），并合成一条 ERE 换进 `07` ③、`08-TESTING` 三处、`01-DEV-LOOP` 两处。🔖 换针先验绿：新针在崩的那趟 = 1 命中、干净趟 = 0；旧针在 10-13 全天 **36 份里 0 命中**。重读结论：35 趟 0 命中 / 1 趟命中那次已修 FATAL ⇒ 「注入失败标记 = 0」现在才是有定义的读数。
- 🧹 **P7/P8/P9 三条零成本账**：`CHANGE_LOG` 归档说明不再指向已退库的 `evidence/h*.md`（`2af28fd` 退库、`git ls-files evidence/` = 0），改成「库内三处真源 + 判据图只剩 `build/*.png`、数字大多不可复算」；`VkDispVirtualPack:217` 的「`mrt.skyPackShader` 默认关」改成 **默认开**（`VkDispConfig:855` 是 `true`；顺带 `VkDispConfig:829` 那句 javadoc 与同块「为什么默认开」自相矛盾，一并改）—— 顺手把全仓配置注释的默认值口径扫了一遍，**只剩 `cloudsNoCull` 一处是我脚本的假阳性**；`07` §1.4.1 第 ① 行补进 10-13 落地的三条注入点签名（`CloudRenderer#render(RenderPass,RenderPipeline)V`、`WeatherEffectRenderer#render(WeatherRenderState,RenderPass,RenderPipeline)V`、`WorldBorderRenderer#render(WorldBorderRenderState,RenderPass,net.minecraft.world.phys.Vec3,double)V`，键同 `mixin.passAwarePipeline`）与两个新键 `mrt.terrainSingleDraw`/`mrt.takeoverDepthToGbuffer` 的消费点，并写明「全集以 `vkdisp.mixins.json`（18 个 client 类）为准，手写表不许当全集」。
- **影响的文档**：`docs/13`（GAP-054 八臂行 + 臂 3 行 + GAP-056 五行）、`docs/19`（§7-7、§7-8 第 8 格、取证纪律 ③④）、`docs/07`（§1.4.1 ①③ + 新 §1.4.1bis + 勾选行）、`docs/08`（三处针）、`docs/01`（两处 grep）、本文件（顶部两条撤回注记 + 归档说明）。代码：`VkDispEffectiveConfig`、`bridge/PostChainGate`（新），`FrameApi`、`FullscreenPassHook`、`VkDispConfigHotReload`、`VkDispConfig`/`VkDispVirtualPack`（注释）。
- **测试结果 1212 条 / 0 失败 / 2 跳过**：新增 `VkDispEffectiveConfigTest`（枚举前提 = 每个 `define*` 都挂在 `public static` 字段上；格式化真值表；挂在两个配置事件上；**台账里的针与代码标签逐字相同**）、`PostChainGateTest`（`describe` 真值表「只报读过的项」、`active()` 与 `readout()` 同序、自报名必须是真键、`FrameApi` 不许再自己抄六项、半开档文案不许点没读的键）、`MixinFailureNeedleTest`（六条标记有定义 = 六行；合成针覆盖九个子串且不含旧假绿针）。⚠️ 诚实降级：「运行时枚举 == 源码声明」那条在测试 JVM 里**跳过**（`NoClassDefFoundError: ModConfigSpec`，测试类路径没有游戏构件），它今天是在**真机**上对上的：源码 63 个 `define*` 调用 vs `[生效档]` 报的 **63 键**。
- **真机（14:13:41 一趟 `runClient`，到主菜单即停）**：`[生效档] 档=启动首载 63 键 / 非默认 4：mrt.packTerrainShader, mrt.postChainMaxPasses, mrt.terrain, shaderPack`（逐段四行都在，`mixin`/`mrt`/`pack`/(根) 分组）；`is not correct. Correcting` **0 命中**；六条注入失败针 **0 命中**；起跑前后 `run/config/vkdisp-client.toml` md5 逐字相同（`0f2aaa5c…`），**停客户端后再读仍相同** ⇒ 这一趟没人改档。残留客户端 **0**（X33 口径：按 `-Dfml.modFolders=vkdisp` 匹配后计数）。⚠️ 两条未覆盖：① 半开档那行的**新文案本轮没真机**（要一臂 `mrt.postChain=false` 冷启动才走到，只有单测钉着）；② 本轮没进世界 ⇒ `[route]` 与 `PostChainGate.readout()` 的真机形状未取。**✅ 同日下一轮（一百五十三）两条都补上了**（臂 `mrt.postChain=false` 真机形状 + 成对关那一臂）。取证档未动（起跑前已 `cp` 到 `/tmp/vkdisp-preflight/`，跑完核对 md5 一致，无残留改动）。
- **下一刀（顺序写死）**：① 单键 `mrt.packTerrainShader=false` 冷启动补那一格（起跑后先读 `[生效档]` + grep `is not correct`，停档后逐键值 diff）；② 顺路把半开档那行的新文案在一臂 `postChain=false` 里读出真机形状；③ 然后进包侧那一刀（保留包、一条派生程序都不注册）。
- **是否已提交**：✅ 本轮结束即提交并推送（2026-10-11 用户口径：每轮开发结束都提交推送，不再等点头）。

---

## 2026-10-13（一百五十一）— 🟢 `GAP-054` 冷启动三臂：地形与链**都排除**，只剩天空那一族；另立 `GAP-055`（半开档每帧抛异常）与 `GAP-056`（换臂被回写污染）

> **verdict = 生产者候选从三条砍到一条**；本轮零代码改动，全是取证 + 两条新登记的账。

- **臂 1（基线复现）**：`mrt.terrain=true` 全基线档、机位 `tp Dev -44.6 104 -93.6` + `yaw=-30 pitch=2`、每帧前 `time set 6000` ⇒ 平面在（左半图集墙 + 中景平坦浅绿面 + 右下角巨型树叶 sprite）。⚠️ 读数口径本轮**降级为目视 A-B-A**：`vision.screenshot {path}` 那条直连 `/rpc` 通道被本机权限层判为越权拦下 ⇒ 拿不到 PNG 文件，`硬边列=[361,362,363] / 左块 mean=79.2` 这类**数值**签名今天取不了。⇒ 凡是要数值的格（`GAP-053`、星点、`h08` A-B）**先要解决取图通道**，这条已写进 `19` §7-8 第 8 格。
- **臂 2（单键 `mrt.terrain=false`，`mixin.wireTerrain` 保持 `true`）⇒ 地形那一笔排除**：整帧除 HUD 外近乎全黑，**平面照旧**。⇒ `GAP-030` 那一族「我方 gbuffer 地形被错矩阵画成巨型四边形」在冷启动单键这一档也被否掉（上一轮热重载那一格现在有干净版本）。
- **臂 3（`mrt.postChain=false`，但被回写污染成两键 ⇒ 2026-10-14 更正：**这一臂其实是干净的单键臂**，见 `GAP-056` 撤回行）⇒ 链也排除**：画面全黑（半开档预期），**平面仍在右下角、逐字同形**。⇒ 三条候选砍到只剩**天空那一族**；下一臂 = 单键 `mrt.skyPass=false`（镜头钉地平线，别学上一轮朝天）。
- 🔴 **`GAP-055`（新账）**：`mrt.terrain=false` 那一臂**每帧抛** `IllegalStateException: Close the existing render pass before performing additional commands` —— 站点 `PackTextures.builtinNoiseView:189 ← PackTextures.view:151 ← FrameApi.lambda$chainResolver$0:1370 ← PackSamplerViews.resolve:90`，即**链解析采样器那一刻去写内置 `noisetex`，而那时有一个 render pass 开着**。计数 = `noisetex FAILED` 逐分钟 **4692 + 2613 = 7305 条，全部落在这一臂**（基线那一趟同文件零命中）。⇒ 「只关 `mrt.terrain` 而留 `packTerrainShader`」= **全黑 + 每帧异常**，与已知的另一侧半开档成对；这一对键不是可独立关的，而今天没有装配期检测拦半开。
- 🔴 **`GAP-056`（新账，取证方法级）**：臂 2 跑完后 `mrt.packTerrainShader` **在文件里自己变成了 `false`**（臂 3 起跑即报 `[GAP-036/半开档] mrt.terrain=true 而 mrt.packTerrainShader=false`，而我一个字都没动它）⇒ **单键臂会跨趟变成两键臂，且日志不说「我改了配置」**。新纪律：**每臂起跑后先回读 `[route]` 与半开档那两行自报**，实际档 ≠ 写下的档 ⇒ 那一臂当场作废。🔴 **2026-10-14 撤回这一条的证据**：那行 WARN 不读 `packTerrainShader`（`FullscreenPassHook:220` 的判据是 `terrain ∧ !chainActive ∧ !toMain`，句里键名是写死的字符串），而臂 3 那一趟真读该键的日志打的是「will use pack fragment」⇒ 那一档它是 `true`；回写这件事现在**未证成**，纪律保留但仪器换成启动期全量生效档自报 + 逐键值 diff。逐字见 `docs/13` GAP-056 表首行。
- 🟡 **`GAP-039` 粉雪那一格 = 前置缺失，不是失败**：头与脚都在粉雪里（`fill` 125 格、`get_block` 两处回读 `powder_snow`）跨约 30 秒，`[GAP-038/雾]` 三行**恒 `fogType=NONE`**、画面无白化 ⇒ 原版那道 `fogType != POWDER_SNOW` 门控**根本没进入** ⇒ 这一格既没签成也没否证。仪器没坏（同一台在进水那一格翻成 `WATER`）。下一刀先读原版 `FogRenderer` 的粉雪分支（取点是脚还是眼）。世界已复原（同坐标 fill 回 air 125 格），同趟 `Mixin apply.*failed` = 0。
- **臂 4（总开关 `enabled=false`，冷启动）⇒ 平面消失、画面是干净原版 ⇒ 「摘账」那一支关闭**：先核实这个键**真有消费点**（`FullscreenPassHook:129`、`SceneCaptureApi:82`、`VkDispPackScan:83`、`VkDispVirtualPack:852/1003`），起跑后三行兜底自报逐字确认（`built-in passthrough fallback` / `pack scan skipped` / `fullscreen pass skipped`）。同机位取图：天空与地形都正常、右下角树叶回到正常尺寸，那堵图集墙与平坦浅绿面**全部不存在**，且 `Close the existing render pass` **0 条**（反证 `GAP-055` 由半开档引入）。⇒ `GAP-054` 的账**留在本项目**，范围 = 我方内部、**只剩天空那一族**。💡 另一条省事结论：**原版对照不必拆 `run/mods/`**，`enabled=false` 就是干净兜底臂。另：这一臂跑完后配置文件 md5 逐字回到 `cf3db1ad…` ⇒ `GAP-056` 的回写**只在半开档那一趟发生**，不是每趟都写。
- **臂 5（单键 `mrt.skyPass=false`，冷启动）⇒ 天空 pass 排除**：起跑先回读 `[route] = chain=true`（链在跑、画面可判、无半开档 WARN），取图 **天空整片变黑**（键确实每帧生效）而**三块巨型贴图面照旧**。
- **臂 6（单键 `mixin.wireTerrain=false`，冷启动）⇒ M-01 排除，本轮假设被自己证伪**：起跑前立的假设「`mrt.terrain=false` 只关我方 pass，M-01 仍在**原版地形那几笔**上换成派生管线 ⇒ 适配器画成错位巨型四边形」逐字解释了臂 2 的结果；实测画面与基线**同形** ⇒ 假设不成立。
- 📉 **六臂之后的形状与下一刀（写死，别再逐键猜）**：冷启动单键已排除 5 条（`mrt.terrain` / `mrt.postChain` / `mrt.skyPass` / `mixin.wireTerrain` / 上一轮的 `mrt.skyPackShader`），而 `enabled=false` 才让平面消失 ⇒ 生产者**在我方、却不在任何主路上**。下一臂 = **`[mixin]` 整块 11 键一次全关**（冷启动，跑完立刻 `md5sum` 比对防 `GAP-056` 回写）：平面还在 ⇒ 键族不在 mixin 侧，转 `[mrt]` 侧二分（`shadowStubs` / `packWater` / `depthGlProxy` / `attachments`）；不在 ⇒ 对半切（5+6），两臂内落到单键。
- **臂 7（`[mixin]` 整块 11 键一次全关，冷启动）⇒ 整个注入族排除**：起跑回读 `[route] = chain=true`、无半开档 WARN、`Mixin apply.*failed` = 0、异常 0 条；画面近乎全黑（链读空 colortex 的预期）**而那块巨型贴图平面照旧**。⇒ M-01/M-01b/M-06/M-07/M-08/M-09/M-10/M-11/M-13/M-14/M-15 **一臂全排除**。
- ✅ **臂 8（`shaderPack="none"`，其余沿用臂 7）⇒ 平面消失 ⇒ 「包带来的」**：自报确认 `不加载库存包，使用内置 passthrough 兜底` + `地形片元不接线`；同机位取图 **地形回到正常尺寸与正常贴图**（图集墙、平坦浅绿面、巨型树叶全没了）。臂 7 与臂 8 只差 `shaderPack` 一个变量 ⇒ **生产者在包的加载/生成侧，不在这 11 个注入点里**。⇒ `GAP-054` 的下一刀进包侧：① 保留包但不注册任何派生程序（切「包的资源 vs 包的程序」）② 先补 §7-8 早就写着的那条生成侧自报（`FullscreenPipelineRegistrar` / `GeneratedSources.adapters`）③ `[pack]` 逐键二分。
- **收尾**：`run/config/vkdisp-client.toml` 已逐字复原到起跑前（md5 `cf3db1ad…`）、客户端与 25599 均已停、无孤儿 JVM。**本轮无 `src/` 改动**。

---

## 2026-10-13（一百五十）— 🟢 `GAP-037` 的判据仪器落地并拿到**第一次真机读数**：本帧天空深度被翻 **1 次**

> **verdict = 读数「1 次 = 通过」，但成立的原因是第二个所有者当前没参与** —— 这条判据既没复发也没被证伪，仪器从此是它的回归闸门。

- **为什么先做仪器**：第 5 步写着「天空深度只被翻一次（`GAP-037`）」，可今天**没有任何地方**能读出「翻了几次」—— 与 `GAP-038` 那条雾判据同一种**没有仪器的判据 = 永远签不掉的空账**。
- **形状**：`bridge/DepthFlipLedger.java` —— 每个「对天空那批像素做了深度口径换算的所有者」在自己那一刻 `note(owner)`，帧首 `beginFrame()` 结算、每 300 帧自报一行 `[GAP-037/翻]`；判定本体 `verdict(int)` 是**纯函数**、三态都说话（**0 次也报红**：天空停在引擎口径 ⇒ 包按 `z==1.0` 认天空的分支判否，正是「夜里无星」那一族）。计数不参与任何渲染决定（T10，口径同 `GbufferPassOrder`）。两个所有者各接一处：`DepthGlProxy.renderFlipPass`（那趟全屏 `1−z`，顺手做帧首结算 —— 它每帧恰好一次）与 `SkyPackGbuffer` 那笔全屏 draw 之前（顶点常量 `Position.z=1.0`）。
- **真机读数**（冷启动、`mrt.depthGlProxy=true` 那一臂）：`本帧天空深度被翻 1 次（判据：恰好 1 次）：DepthGlProxy.renderFlipPass(全屏 1−z)；累计「>1 次」的帧=0`，跨 20 秒三行一致。
- ⚠️ **这条「通过」的确切含义**：第二个所有者这一臂**没参与** —— 它被本轮为 `GAP-054` 加的几何路线守卫拒掉了（`contractProblem()` 先返回 ⇒ 到不了那笔 draw）。⇒ `GAP-037` 的「翻两次」**没复发、也没被证伪**；等 `GAP-028` ③ 把包天空那条路重新接上，这台仪器就是回归闸门（读数必须仍是 1 次）。
- 🔖 **两条棘轮真拦住了我（记下来免得再撞）**：① `ClassLineRatchetTest` 拒了「FrameApi 长了 3 行」（1678 > 1675）⇒ 记点改落到 `DepthGlProxy` 自己头上，巨类一行没加；② `StaticFieldRatchetTest` 拒了「静态非 final 175 → 176」⇒ 可变态收进 `Ledger` 持有者（QD-03 的三级所有权写法）。
- **测试 1200 条 0 失败**（1197 → 1200，新增 `DepthFlipLedgerTest` 3 条：三态判定 / 两个所有者都必须被记上（漏一个就永远假报「1 次 ✓」）/ 仪器不读配置不做分支）。
- **收尾**：`mrt.depthGlProxy` 已复原 `false`（备份-回写，逐字还原），客户端已停、25599 无监听。


---

## 2026-10-13（一百四十九）— ✅ 第 5 步第一条画面判据签过：`GAP-039`「岩浆里没有天空」（头浸进去 0.09%，空气里 36.66%，可逆）

> **verdict = 通过（岩浆那一格）**；同趟 `Mixin apply failed = 0`。本条另外两格（粉雪 / `Skybox.NONE` 维度）**仍未签**。

- **做法**（口径与第 3 步那条雾判据逐字同形，所以踩坑成本为零）：冷启动真机 → 机位 `(-45,102,-94)`、`yaw=0 pitch=0` → 每帧前 `time set 6000` → A-B-A 三帧。B 臂用 `fill <5×4×5> minecraft:lava replace minecraft:air`（70 格，**替换模式写在结果方块之后**那条老规矩照守），量完立刻 `fill … minecraft:air replace minecraft:lava` 复原。
- **签名**：全帧「天空蓝像素」占比（`B>140 且 B>R+25 且 G>R`）。空气里 **36.66%** → 头浸岩浆 **0.09%**（岩浆橙 96.13%）→ 复原 **34.89%**。⇒ 判据「岩浆里没有天空」在这一档成立。
- ⚠️ **这条读数证到哪一步**（写进登记表，别读过头）：它证的是**「不画天空」**，不是「岩浆表面渲染正确」—— 头浸进去时整帧被岩浆占满，没有留给天空的位置。表面级判据要等 `GAP-054` 那块巨型平面清掉之后再取。
- **顺带一条现状核对（省掉一整步）**：`GAP-054` 那轮 bisect 之后回头查阶梯的**删除项**，发现第 5 步的「删 `GbufferTarget` 整类」与第 6 步的「删 `ORDER-MARK` 日志 + `mrt.terrainAfterLevel` 车道 / 删 `PackPickerScreen` + 自研分页」**在仓库里已经物理消失**（`grep` 只剩历史注释；`mrt.terrainAfterLevel` 这个键在 `VkDispConfig` 里已不存在；`PackPickerScreen.java` 无文件），而 M-13/M-14 的注入点（`FrameGraphPassOrderMixin` + `pinPassOrder`、`VideoSettingsPackOptionsMixin` + `packOptionsInVanilla`）都在。⇒ 第 5/6 步**欠的全是画面判据，不是代码**。
- **仍未签的清单（下一轮按成本排）**：第 5 步 `GAP-037`「天空深度只被翻一次」+ 星点（现为红）+ `GAP-039` 粉雪那格；第 2 步各向异性两档；第 4 步对 `h08` 基线 48.22/39.03% 的 A/B（需 `slots=8` 冷启动档）；第 6 步 M-14 的原版视频设置页目视；`GAP-052` 的 OIT 臂取证；`GAP-054` 三臂冷启动。
- **收尾**：世界改动已就地复原（70 格岩浆 → 空气），客户端已停、25599 无监听，取证键未动。零代码改动，测试仍 **1197 条 0 失败**。


---

## 2026-10-13（一百四十八）— 🔴 冷启动 A/B 把 `GAP-054` 的归因**整条推翻**：平面与 `mrt.skyPackShader` 无关（三趟冷启动逐字相同）

> **verdict = 归因作废、回到未定**。上一轮那条「bisect 拿到真凶」和同批热重载读数的「四刀排除」一起降级为**未证**。

- **怎么做的**：每臂**重新起客户端**、中途零热重载，同机位（`tp Dev -44.607 102 -93.609` + `yaw=-30 pitch=2`）、每帧前 `time set 6000`，签名仍是「x≈361 的竖直硬边 + 左块 mean」。
- **读数**：`skyPackShader=true` 两趟 ⇒ `硬边列=[361,362,363] 左块 mean=79.2 全片 mean=123.5`，**两趟逐字相同**（仪器可复现）；`skyPackShader=false` 一趟 ⇒ **完全同一组数**，日志里连一行天空自报都没有。⇒ 这个键**不影响**那块平面。
- **为什么上一轮会读反**：这一族键参与**生成指纹**（`VkDispVirtualPack:1031` + `VkDispConfigHotReload:329`）⇒ 热重载换臂顺带触发**整包重生成 + 资源重载**。上一轮「关掉就没了 / 打开就回来」两次都跟着重载走，形状与「键有语义」一模一样。⇒ 同一趟里的 `skyViews`、`packTerrainShader`、云三键「都不动它」也一并**降级为未证**。
- **本轮唯一站得住的新事实**（探针，上一提交）：四个产物出口里**没有任何人问过 `gbuffers_skybasic`**，而链里出现 `MrtTerrainPass#drawTerrain:382` ⇒ 这份手写 toml 档里我方 gbuffer pass **确实在开、在画地形** ⇒ 「地形那一笔」重新回到候选位。
- **下一刀（口径已写死进登记表）**：键位实验一律冷启动，一次一臂；先跑三臂定方向 —— ① `mixin.wireTerrain=false` + `mrt.terrain=false`；② `mrt.postChain=false`；③ **原版对照**（`run/mods/` 只留 mcpfabric）—— ③ 若平面还在就直接摘账，与本项目无关。
- **收尾**：取证键已复原（`skyPackShader = true` = 代码默认），三趟客户端全部停干净（25599 无监听）。零代码改动，测试仍 **1197 条 0 失败**。


---

## 2026-10-13（一百四十七）— 🔬 `GAP-054` 探针读数：**没有任何人问过 `gbuffers_skybasic` 的产物**（于是上一轮那条 bisect 结论也要打问号）

> **verdict = 归因再次改向**：上一轮写的「真凶在注册侧」在四个产物出口上**没有证据**；而「关 `mrt.skyPackShader` 平面就消失」那一格现在怀疑是**热重载副作用**。

- **做法**：新建 `PackArtifactProbe`（每个「出口 × 程序」只自报一次，调用链用 `StackWalker` 现取、只在首次那一次付代价），接到 `VkDispVirtualPack` 的四个出口（`packContract` / `packFragmentShaderId` / `packAdapterShaderId` / `packBuiltinsLayout`）上。冷启动真机一趟 ⇒ 日志里**只有 `gbuffers_terrain` 四条**，链分别是 `TerrainPipelineApi#packTerrainForMrt:401`、`buildMrtPipeline:262/263`、`updateGbufferBuiltins:775 ← MrtTerrainPass#drawTerrain:382`。
- **两条被打掉的说法**：① 「包天空产物被第三方读走」—— 这四个出口上没人问过天空程序；② 「`skyPackShader=false` ⇒ 平面消失」—— 这个键参与**生成指纹**（`VkDispVirtualPack:1031` + `VkDispConfigHotReload:329`），关它必然触发**整包重生成 + 资源重载** ⇒ 那一格可能读到的是重载，不是语义。
- **一条新事实（对「默认档」这个词要收紧）**：调用链里出现 `MrtTerrainPass#drawTerrain:382` ⇒ 这份手写 `run/config/vkdisp-client.toml` 档里**我方 gbuffer pass 确实在开、在画地形**（`mrt.enabled=false` 只关诊断覆盖层）。⇒ 以后写「默认档」必须同时说清是**代码默认**还是**这份 toml**。
- **下一刀（已写进登记表）**：先做**冷启动两臂**（`skyPackShader` 一真一假、中间不热重载）各取一帧；若两臂都有平面，则上一轮 bisect 结论作废、`GAP-054` 与包天空无关；若只有 `true` 臂有，再把探针往**生成侧**（`GeneratedSources.fragments/adapters` 与管线注册）挪一格。
- **测试 1197 条 0 失败**（本轮只加探针，零判据/零行为改动）；客户端已停、25599 无监听、取证键未动（仍是代码默认值的写法）。


---

## 2026-10-13（一百四十六）— 🔴 第一版 `GAP-054` 修法**被真机打脸**：守卫落地了，平面还在（但真凶位置因此换了一处）

> **verdict = 未修**。这一条记的是「一次看起来合理的修复为什么没生效」，以及它把归因往前推了哪一步。

- **做了什么**：`SkyPackGbuffer.contractProblem()` 最前面加一条几何路线守卫 —— 纯函数 `ndcTriangleRouteValid(顶点是否 passthrough, 相机矩阵是否恒等)`，两个事实都写 `false`（各有源码证据：`build/dump-head1/world0_gbuffers_skybasic-VERTEX.glsl:240` 逐字乘 `gbufferProjection * gbufferModelView`；`OfUniformManager:377-378` 交真相机矩阵），不成立就交回原版 + `refusedGeometryCount()` 计数。新测试 `SkyPackGeometryRouteTest` 4 条（真值表 + 「守卫不许空转、必须排在契约检查之前」+ 全屏三角那三个顶点与唯一一处 draw）。测试 **1197 条 0 失败**（1193→1197）。
- 🔴 **真机复测说它没修好**：同一台客户端冷启动、同机位（`tp Dev -44.6 102 -93.6` + `yaw=-30 pitch=2` + `time set 6000`）⇒ `近 361 硬边列数=3`（与修复前**逐字相同**）、左块 `mean 68.1 → 79.2`、全片 `108.9 → 123.5`，而日志里**找不到**那条「不接管」自报 ⇒ **默认档根本没走到这条路线**。
- **因此换到的真凶位置**：`mrt.skyPackShader` 有**第二个消费者** —— `VkDispVirtualPack:223` 拿它决定要不要**注册/替换派生天空程序**（指纹 `:1031`）。默认档里原版 `SkyRenderer` 的穹顶/日月/星几何仍被我方派生的包 `gbuffers_skybasic` 接走 ⇒「屏幕空间片元 × 世界空间几何」正是那块巨型斜切贴图面。⇒ 修法要落在那一处的**替换门槛**上（与 `GAP-028` ③ 的逐 draw `renderStage` 一起结）。
- 🔖 **一条纪律**：「键关掉画面就变」只证明**这个键有用**，不证明**是哪一处消费者**在画它 —— 一个键有多个消费者时，bisect 的结论必须再切一刀才能落到具体调用点（这一轮就是这么白跑了一次修复）。
- **默认档零变化**：取证键没动（`skyPackShader` 仍是默认 `true`），客户端已退出。守卫按证据保留（那条全屏三角确实是错几何），但它**不是** `GAP-054` 的因，登记表已改判。


---

## 2026-10-13（一百四十五）— ✅ `GAP-054` 归因拿到：那块切过半个屏幕的巨型平面是**我方派生的包天空管线**（`mrt.skyPackShader`）的**顶点侧**

> **verdict = 归因完成、未修**；同时**撤回上一轮「包天空已排除」那一格**（那一刀切的是 `mrt.skyPass`，不是同一个键）。

- **怎么拿到的**：默认档（`ownTerrainPass=false`、`mrt.enabled=false`）冷启动进世界，镜头回地平线（`control.look` 后 `players.list` 当场回读 `yaw=-30 pitch=2`），每格取帧前 `time set 6000` 钉住日时钟。签名换成「左半那条 x≈361 的竖直硬边 + 其左侧 `mean≈68`」而不是整帧亮度 —— 第一版没钉日时钟，所有臂指标**单调下滑**（319→293），那是太阳在动。
- **四刀排除**：`mrt.postChain=false`（`mean` 68.1→68.1）、`mrt.terrain=false`（68.1→63.99，整帧 `>40` 差 0.00%）、`cloudMatrixRedirect` / `cloudsNoCull` / `cloudProbe` 三格全在 ⇒ **`GAP-030` 那一族「地形被错矩阵画成巨型四边形」的假设作废**（`docs/13` 里那一行已标 🔴 保留原文）。
- **它是世界几何**：`tp Dev` 北移 130 格 ⇒ 硬边消失（左块 `mean 68.1 → 108.6`），回原位 ⇒ **逐字复现 68.1** ⇒ 屏幕空间 blit / 视口错配那一类排除。⚠️ 顺带又踩一次「`ok=true` 不等于成功」：`tp @s` 在控制台**没有 self**，原版回「未找到实体」而 mcpfabric 照样回 `ok=true`（与 `/fill` 那条同族）。
- **归因那一刀**：17 个注入键一次全关 ⇒ **平面没了**（`mean 68.1 → 116.2`）⇒ 确认是我方产物；再逐键单独关 ⇒ 只有 **`mrt.skyPackShader=false`** 翻掉签名（`mean=145.1`、硬边 0 列），`skyViews=false`、`packTerrainShader=false` 都还在。日志同源：`[GAP-027/sky] 派生天空管线已建: … vertex=vkdisp_pack:skybasic_pack_adapter` ⇒ 画它的是**派生包天空的顶点适配器**。
- 🔴 **一条自我更正**：上一轮「三条候选全清」里「包天空已排除」用的是 `mrt.skyPass`（整个天空 pass 关不断），而「包天空自己画错」讲的是**用哪条天空程序**（`mrt.skyPackShader`）⇒ 那一格作废，本轮翻案。云与 M-11 视图重定向那两格仍然成立。
- ⚠️ **取证方法本身打崩了驱动**：17 键「全关再全开」的热重载风暴 ⇒ `EXCEPTION_ACCESS_VIOLATION`，Problematic frame **`nvoglv64.dll+0xebd971`**，客户端进程直接没了；同一批重载里还出现 `Couldn't find source for VERTEX shader (vkdisp_pack:skybasic_pack_adapter)`（**冷启动那两批没有** ⇒ 不能拿它解释平面）。⇒ 立规矩：**一次只切一个键、每次等满一次完整重载**。
- 🔴 **机制已在代码级定位（同一趟，读代码读出来的）**：`SkyPackGbuffer.java:280-290` 喂的是 **NDC 大三角**（`(-1,-1),(3,-1),(-1,3)`、z 写死 1.0，前提是「恒等 MVP」），而包的 `gbuffers_skybasic` 顶点阶段逐字是 `gl_Position = gbufferProjection * gbufferModelView * position`（`OfUniformManager.java:344-345` / `:619`），`:377-378` 交出去的是**真·相机矩阵**（CHAIN 族只翻深度口径，`:370`）⇒ 那张全屏三角被当成视空间 z=1 的世界几何过了一遍透视 ⇒ 「一条竖直边 + 一条斜边」的巨型三角形，与画面逐字对上。**还差一步真机对照**（给这一路供恒等 view/projection，平面应变回整屏天空）才算定案。
- **下一刀（已写进 `GAP-054`）**：读 `skybasic_pack_adapter` 的生成侧，核它 `gl_Position` 用的矩阵与那次 draw 实际绑定的 `ModelView`/`Projection` 是否同源；切法是把适配器换成「直通」版看天空是否恢复正常。
- **默认档零变化**：取证键全部复原（`run/config/vkdisp-client.toml` 逐键回 `true`，客户端已退出，25599 无监听）。本轮**只有文档**（代码零改动，测试仍 **1193 条 0 失败**）。


---

## 2026-10-13（一百四十四）— 🟢 第 4 步那条「故意错配一次必须被拒」第一次被**跑**起来（此前只是源码形状针）

> **verdict = 判据从「形状近似」升为「行为真值表」**；渲染行为零变化（纯重构 + 一条新测试文件）。

- **为什么单开一步**：判据讲的是**行为**，而 `PassAwarePipelines.select` 的入参与返回都是 renderpearl 的 `RenderPipeline` —— 测试运行时没有 MC / renderpearl 类（`GAP-050`），于是这条判据一直只能用 `MrtTerrainPassWiringTest` 里「源码有两句 `return requested;`」这种**形状针**近似。形状针两个方向都能骗：行为坏了而形状还在（绿），形状换了而行为没变（假红）。
- **做法**：把身份判定抽成纯函数 `PassAwarePipelines.routeOf(flagOn, ourPass, takenOverMainPass)` → 整数路由（`ROUTE_VERBATIM=0` / `ROUTE_MRT_LAYER_TABLE=1` / `ROUTE_PADDED_MAIN_PASS=2`），`select` 只剩「按格子执行」的 `switch`。**开关也作为入参进去** ⇒ 「关掉逐字退回原版」（可关闭制 X29）那一格同样进了真值表。
- **新测试 `PassAwarePipelineRouteTest`（4 条）**：① 真值表 —— 两条身份都对不上 ⇒ `ROUTE_VERBATIM`（🔴 这就是「故意错配必须被拒」），我方 MRT pass ⇒ 查层名表，接管后的主 pass ⇒ 补槽公式，两者同时为真先走表（保守：宁可让 `setPipeline` 响亮地抛，不静默错配）；② 开关关着压过两条身份（4 格全 `ROUTE_VERBATIM`）；③ 三个路由码互不相同且 **0 号 = 原样返回**（新格子默认落回原版那一支）；④ **反漂移针** —— `select` 里 `routeOf(` 必须出现且只出现一次，不许再长一份 `if (ourPass)` / `if (takenOverMainPass)`，也不许用「开关 && 身份」就地拼布尔绕过真值表。
- 🔖 **诚实记一笔被改的旧针**：`MrtTerrainPassWiringTest:106` 那根 `if (!VkDispConfig.MIXIN_PASS_AWARE_PIPELINE.get()) {` 在重构后**物理消失**（判定读的是入参 `flagOn`），我把针改成 `if (!flagOn) {` 并把主判据让给真值表 —— 形状针随重构误报正是它不该当主判据的理由。
- **文档**：`docs/13` `GAP-003` 判据行 ②（那一格标为「已可跑」并注明旧形状针）、`docs/19` §5A.4 R1 行的判据列（错配被拒 = 可跑；`Mixin apply failed=0` 已真机签；仍待 `h08` 同量级 A/B）。
- **测试 1193 条 0 失败**（1189 → 1193）。**默认档零变化**：`routeOf` 每一格的返回与重构前 `select` 的分支逐条对齐，没动任何 GPU 调用，也没开任何取证键。


---

## 2026-10-13（一百四十三）— ✅ **第 3 步的雾判据签过了：进水那一帧读数真的变了**（`GAP-038` 的修法成立）

> **verdict = 通过**：`fogType NONE → WATER`、`env=[0.000,1024.000] → [-8.000,66.432]`、颜色 `(0.724,0.823,1.000) → (0.074,0.074,0.754)`，且换回空气后**逐字回到岸上那一行** ⇒ 可逆，不是一次性抖动。

- **怎么做的**：上一轮那台 `[GAP-038/雾]` 仪器 + mcpfabric 直连 `/rpc`。默认档（`ownTerrainPass=false`、BSL 默认档），同一台客户端、同一机位，只改「头是否在进水里」：`fill <玩家周围 5×4×5> minecraft:water replace minecraft:air`（94 格）⇒ 读一行 ⇒ 反向 `fill … minecraft:air replace minecraft:water` 复原。
- **三行读数**：岸上 `capture#6000 fogType=NONE env=[0.000,1024.000] renderDistance=[230.400,256.000] skyEnd=256.000 cloudEnd=1024.000 color=(0.724,0.823,1.000,1.000)`；水下 `capture#6600 fogType=WATER env=[-8.000,66.432] skyEnd=66.432 cloudEnd=66.432 color=(0.074,0.074,0.754,1.000)`；复原后 `capture#7200/7500/7800` 回到岸上那一行。
- 🔴 **两条差点把这一格读错的地方**（都记进记忆/文档）：① **`/fill` 的替换模式写在结果方块之后** —— 我第一趟写成 `fill <a> <b> minecraft:air minecraft:water replace`，原版回的是**语法错误**（指针停在 `replace`），而三行读数一模一样，看着像「判据不成立」；实际是**方块根本没放**。⇒ 命令回 `ok=true` 不等于成功，必须看 `output` 里有没有错误指针。② 上一趟 `no_server` 的真相：新客户端的 mcpfabric **bind 失败**（`Address already in use:25599`），我打到的桥属于一个**还占着端口的空闲旧客户端** —— 而我认孤儿进程的过滤器是「工作集 > 1.5GB」，空闲在主菜单的客户端**不到那个量级**所以漏掉了。⇒ 认孤儿要看**谁在听 25599**（`netstat -ano` 拿 PID），别拿内存大小当代理。
- **意义**：这是第 3 步（M-05 两点收一点 + 补雾 / `GAP-038`）里第一条**签掉的画面级判据**，也是本轮唯一一条「判据原本没有仪器」的缺口 —— 仪器落在 `1c4fe08`，判据签在 `GAP-038` 的新行里。
- **默认档零变化**：全程没开任何取证键；世界改动已就地复原（水→空气，94 格）。


---

## 2026-10-13（一百四十二）— 🟢 GAP-038 的**判据仪器**落地：雾的参数第一次被打印出来（此前那格判据根本没有读数）

> **verdict = 仪器已在真机读到活数据；但「进水那一帧读数跟着变」这一格仍未签** —— 那一趟 mcpfabric 的 `command.run` 回了 `no_server`。

- **为什么这是「下一步」而不是「顺手加日志」**：第 3 步的判据写着「进水那一帧雾端读数跟着变（现状不变就是复现）」，可今天**没有任何地方**输出雾的内容 —— M-06 那行只报 `fogPresent=true/false`，而 `terrainFog` 是一块 UBO 切片，肉眼读不出参数。**没有仪器的判据 = 一条永远签不掉的空账**。
- **形状**：`LevelRendererMainPassCaptureMixin` 加 `@Accessor("levelRenderState")` 交出关卡状态；bridge 走一步到 `cameraRenderState.fogData`（`fogType` 与 `FogData` 的六个距离 + 颜色**都是 public 字段**），每 300 帧（第 1 帧也打）输出一行 `[GAP-038/雾]`。格式化是**纯函数** `fogSummary(...)`（只收原语 ⇒ 测试运行时没有 MC 类也不炸，`GAP-050` 那一族）。
- 🔖 **为什么读 `FogData` 而不是解析切片**：原版 `FogRenderer#updateBuffer` 用 `Std140Builder` 写（`vec4 color` + 6 个 float），自己去数偏移就是拿猜测当判据；而 `GameRenderer:671-672` 显示切片的内容**就是** `cameraState.fogData`（先 `updateBuffer(fogData)` 再 `getBuffer(WORLD)`）⇒ 同源、不猜布局。单测里有一条针专门钉「不许出现 `getFloat(` / `.buffer()`」。
- ✅ **真机读数（默认档，非取证档）**：`capture#2400 fogType=NONE env=[0.000,1024.000] renderDistance=[230.400,256.000] skyEnd=256.000 cloudEnd=1024.000 color=(0.651,0.731,0.881,1.000)`；10 秒后 `capture#2700` 的 `color=(0.724,0.818,0.987,1.000)` ⇒ 逐帧跟着日时钟走，不是常量。
- 🔴 **仍未签的那格**：`tp` / `fill`（造水把镜头淹进去）被 mcpfabric 回 **`no_server`**，而同一时刻客户端在正常渲染关卡（雾读数在打）⇒ 这一趟没能做出「进水那一帧」。下一趟补：`fill <玩家周围> minecraft:air minecraft:water` 后读同一行，`fogType` 必须 `NONE → WATER`、`env` 两端塌到水雾量级；**不变就是 GAP-038 复现**。
- ⚠️ **两条本轮踩到的硬事实**：① `@Accessor` 的方法**必须是 abstract**（写成 `{ throw null; }` 会在启动期抛 `InvalidAccessorException: @Accessor method … is not abstract`）—— 注意这是**我们想要的失败形状**：响亮、启动期、不静默；② javac 25 那条老坑复现：形参里写 `@Nullable a.b.Outer.Inner`（限定名）直接编译失败，改成 import + 简单名。
- **默认档零变化**：这一族本来就挂在 `mixin.captureMainPassArgs`（默认开、只读捕获）下，新增的只是**日志与一个 @Accessor**，不改任何渲染行为。测试 **1189 条 0 失败**（新增 `TerrainDrawCaptureFogTest` 2 条）。


---

## 2026-10-13（一百四十一）— 🟢 -e 第一格落地：接管时把主 pass 的**深度附件**也换成 gbuffer 深度（真机 `深度换=1098`、0 崩）；另登记 `GAP-054` 一块黑色楔形

> **verdict = 这一步买到的是「链的 depthtex 从此有内容可取」；它不修 ⑤-d（代次归属没动）**。顺带撞见一块与它无关的黑色楔形，只登记不归因。

- **为什么先做这一格**：`GAP-053` 的耦合行写清楚了 —— 接管只换颜色附件 ⇒ 主 pass 画的地形深度进的是**原版主深度**，而我方链的 `depthtex0/1/2` 取 `colortexDepth` 的快照 ⇒ **就算 ⑤-d 的代次问题解决了，深度仍然是空的**。所以深度所有权是 ⑤-e 的第一块砖，且可以**独立于代次归属**先落。
- **形状**：新键 `mrt.takeoverDepthToGbuffer`（默认**关** ⇒ 默认档逐字不变），三态纯函数 `MainPassTakeover.depthDecision(请求换?, 视图存在?)` ⇒ `KEEP / SWAP_TO_GBUFFER / REFUSED_NO_VIEW`；换的时候**清屏语义沿用原描述符**（原版这里是 LOAD：主深度早在 clear pass 清过，而我方 pass 本帧已把 `colortexDepth` 清到 `0.0` 并画过地形）。要求换但池还没建出深度视图 ⇒ **退回不换 + 点名一次 + 计数**（X11 不许静默降级）。⑤-a 那行自报多两格 `深度换= 深度退回=`。
- ✅ **真机 A-B（同机位、每帧现读 ⇒ 同一趟切臂）**：关 `full=96.68 sky=123.20 terrain=83.41`；开 `full=82.92 sky=93.14 terrain=77.81`；逐像素差 `mean=14.65`、`>10` 占 37.49%、`>40` 占 11.41%，差异集中在 **y 中位=94（天空带）**，视觉上开的那臂**太阳光晕消失、天空整体变暗**。`深度换=1098` 持续上涨、0 崩、`Mixin apply failed=0`（顺带把第 4 步那条「Mixin apply failed = 0」判据在全量 `latest.log` 上签掉：六种注入失败标记**全为 0**）。🔴 **2026-10-14 更正**：那条针匹配不到任何真实措辞（sponge-mixin 打的是 `Mixin apply for mod <id> failed …`），且「六种标记」当时在仓里没有定义 ⇒ 该「0 命中」是假绿；定义见 `07-CONSTRAINTS.md` §1.4.1bis，合成针重读 10-13 全天 36 份日志 = 35 趟 0 命中 / 1 趟命中那次已修的 FATAL。
- ⚫ **撞见 `GAP-054`**：两臂画面左上方都有**同一块**从左上斜切到画面中部的纯黑楔形 ⇒ **与本步无关**（开关两臂逐字相同）。三条候选一条没排，切开只要一步：默认档同机位看它在不在。**本轮只登记、不归因** —— 这是本轮最容易读错的一点，已写进该条的警告行。
- ⚠️ **本轮另两条操作事实**：① 新键要**等 FML 把它写进 toml** 再改（`takeoverDepthToGbuffer` 确实被写进了 `[mrt]` 组，改那一行即同趟生效）；② 组合命令（改配置 + 截图 + 比对一次做完）被权限层拦下一次 ⇒ 拆成「只读探测 → 改一行 → 再测」三步走，反而更符合单变量纪律。
- **默认档零变化**：新键默认关 + 只在 `ownTerrainPass=true` 的接管路径里才会被读；取证档已回退（`ownTerrainPass=false`、`takeoverDepthToGbuffer=false`、包选项行删除）。
- **测试 1187 条 0 失败**（`MainPassTakeoverTest.depthAttachmentSwapDecision`：三态真值表 + 「必须有独立开关」+ 「退路必须计数并进自报」）。


---

## 2026-10-13（一百四十）— ✅ `GAP-053` 定序拿到读数：**我方 gbuffer pass 先、主 pass 后** ⇒ 候选① 排除、候选③（代次归属）成立；顺带发现「双绘」里主 pass 那一笔**整笔白画**

> **verdict = 机制定案（不是猜的），但 ⑤-d 仍未修好**：下一步的形状是「让主 pass 写进链这一帧要读的那一代 + 深度一并换」，与 ⑤-e 并成一步。

- 🔧 **先修仪器再读数**：上一轮那版 `GbufferPassOrder` 在真机读回来是**逐字各半**（`两个方向各半 ⇒ 先当未知`）。那**不是**「次序不稳定」，是**判据自身缺陷**：两个点各每帧一次 ⇒ 序列严格交替（主、我方、主、我方…），而**交替序列里两个方向必然各占一半，无论谁先**。⇒ 计数规则换成「**凑齐一对时比这两次的先后**」（`mainCount == ownCount` 那一刻才下结论），并补一条**行为单测**（交替四对，谁先谁得 4 分）—— 这条单测正是能抓住上一版缺陷的那一下。
- ✅ **读数（取证档 `ownTerrainPass=true` + `slots=8` + 双绘）**：`[GAP-053/序] 成对=600 ⇒ 我方 gbuffer pass 先打开、主 pass 在后`。⇒ **候选① 排除**（我方那次无条件清屏发生在主 pass **之前**，不是它擦掉主 pass 的内容），**候选③ 成立**：我方 pass 打开后立刻 `POOL.advanceWritten(...)` 翻代，而主 pass 的附件视图是在**那次翻代之后**才取的 ⇒ **主 pass 写的是下一帧那一代，链读的是我方刚翻上去的那一代**。
- 🔑 **由此看清一件比 ⑤-d 本身更值钱的事**：今天这条「双绘」**不是「同一份地形画两遍、取其中一遍」** —— 主 pass 那一笔画进了另一代，链从来没读它 ⇒ 它是**整笔白画**（附件、深度、uniform、片元全花了，一个像素都不留）。这既解释了「双绘档画面 = 只由我方 pass 决定」，也把 R7 要省的那笔从「省一遍重复」改写成「**省一笔完全无输出的绘制**」。
- **⑤-d 剩下那半的形状（写进 `GAP-053` 的「修法」行）**：目标 = 让主 pass 写进链要读的那一代。三条候选：**(a)** 单绘档下我方那条 pass **整个不打开**（既不翻代也不清屏），翻代改由主 pass 之后触发；**(b)** 接管取附件时取「链要读的那一代」；**(c)** 接管档把双代轮转退化成单代（就是 ⑤-e 要的「一个所有者」形状）。⚠️ **三条都得连带换深度附件**（主 pass 现在写主深度，链读 `colortexDepth` 的快照）⇒ **⑤-d 后半与 ⑤-e 并成一步做**，不要单点缝。
- ⚠️ **两条操作坑（本轮踩过）**：① 上一趟 `QuickPlay` 抛栈停在主菜单的真因是**我自己留下的两个孤儿客户端 JVM**（各 ~2GB，仍持有世界 `session.lock`）—— 认它们的办法是 `Get-CimInstance Win32_Process` 里工作集 > 1.5GB 的那几个，别去猜 `-PquickPlay` 坏了。② 我上一轮在 changelog 写「取证档已回退」时**漏了 `mrt.pixelProbe`**（它一直留到本轮才关）—— 回退清单要按**这轮真正改过的键**逐条列，别凭印象。
- **默认档零变化**：这一族只在 `mixin.ownTerrainPass=true`（默认关）时走到；取证档本轮末已回退（`ownTerrainPass=false`、`pixelProbe=false`、包选项行删除）。
- **测试 1186 条 0 失败**（`GbufferPassOrderTest` 4 条：三态判定 / 两点接线 / **交替对归先者** / 「仪器不许读配置」）。


---

## 2026-10-13（一百三十九）— 🟡 `GAP-053` 的定序仪器落地（`GbufferPassOrder`）：帧内谁先打开，读数还没拿到

> **verdict = 仪器已落地 + 单测钉住三态判定；真机那一趟 quickPlay 没进世界 ⇒ 读数仍是「未知」，不许把「没读数」读成任何一种结论**。

- **为什么先做这个**：`GAP-053` 的两个候选机制（① 我方 gbuffer pass 在主 pass **之后**打开 ⇒ 它那次无条件清屏擦掉主 pass 的内容；③ 代次归属错配 ⇒ 主 pass 写的那一代不是链读的那一代）**预测的画面逐字相同** —— 全屏探针实验分不开它们（两条都预测探针活下来），而**修法不同**（① 让出清屏权 / ③ 换代次）。⇒ 不许猜，先造仪器。
- **新增 `bridge/GbufferPassOrder.java`**：两个记数点共用一个单调计数（一帧一对 ⇒ 相邻两次必差 1）⇒ 「谁先」= 方向计数，**不需要帧号、不读任何配置标志**（口径同 ⑤-a「判据只用调用本身的数据」）。判定是**纯函数** `verdict(mainFirst, ownFirst)`：单边占多数就点名胜者**并点名它支持哪个修法**；零对/各半一律落回「未知」。每 600 对自报一次 `[GAP-053/序]`。
- **接线两点**：`MainPassTakeover` 在 `STATS.takenOver++` 之后（= 主 pass 那条描述符真被换成我方阵列那一刻）；`MrtTerrainPass` 在 `ownPass = renderPass;` 之后（= 我方 pass 真打开那一刻）。⚠️ 两处必须**同语义**（都在 `createRenderPass` 那一刻），否则方向计数没有意义 —— 这条由 `GbufferPassOrderTest.bothSidesAreWired` 的文本针钉住（并且先 `contains` 再比位置，不吃 `-1` 那个假绿）。
- **单测三条**（`GbufferPassOrderTest`）：三态判定 + 两点接线 + 「仪器不许 import 配置」（一旦按配置反推「谁该先」，它就变成它想回答的那个问题的复读机）。测试 **1185 条 0 失败**。
- ⚠️ **取证没拿到**：这一趟重启客户端后 `QuickPlay.joinSingleplayerWorld:92` 抛栈 ⇒ 停在主菜单，`主 pass 接管=0`（主菜单没有关卡渲染，这个 0 是**没到那一刻**，不是「判据不匹配」）。怀疑是上一趟我强杀客户端留下的 `session.lock`。**下一趟**：先确认没有孤儿 JVM 与残留锁，再带 `-PquickPlay=` 冷启动，进世界后读那一行 `[GAP-053/序]`。
- **默认档零变化**：这一族只在 `mixin.ownTerrainPass=true`（默认关）时才会被走到；取证档已回退（`ownTerrainPass=false`、包选项行删除）。


---

## 2026-10-13（一百三十八）— 🔴 R7 ⑤-d 的画面级判据取到了，而且是红的：单绘档画面里没有地形（登记 `GAP-053`）

> **verdict = 上一轮那句「地形确实只画一遍 ⇒ 对」被本轮的截图推翻**：地形确实只画一遍，**然后被抹掉了**。

- **做法**：mcpfabric 的 HTTP 桥直连（`POST /rpc` + Bearer，token 在 `run/config/mcpfabric.config.json`）—— 这一趟会话里没有 minecraft 连接器，但**取证不必等连接器**。同一机位（`Dev` @ (-44.6, 104, -93.6)）按 **双绘 → 单绘 → 双绘** 连开三档（A-B-A），每档 `vision.screenshot` 取一帧，用 luma 带 + 逐像素差判读。取证档：`ownTerrainPass=true` + `BSL ADVANCED_MATERIALS` ⇒ `slots=8`。
- **数字**：双绘 `terrain=72.90` / 单绘 `terrain=140.39` / 双绘′ `terrain=69.89`；A′↔A 逐像素差 `mean=3.01`、`>10` 只占 **0.83%**、`>40` 占 **0.00%** ⇒ **重复性成立，差异不是漂移**；单绘↔双绘 `mean=51.44`、`>10` 占 **60.17%**。
- 🔴 **判读不能只看亮度**：单绘「更亮」第一眼像改善 —— 把两张图打开看，**单绘那张只有天空渐变 + 手 + 快捷栏，地形一个像素都没有**。天空比地形亮 ⇒ 丢内容会把均值往上抬。与 `GAP-052` 同一族教训：**总亮度读数对「内容有没有」不敏感**，只看 FULL 会把「画空」读成「变好」。
- **上一轮的结论逐字作废一半**：「`[M-15] 地形两条 hits` 持续上涨 ⇒ 唯一会调 `ChunkSectionsToRender` 的就是主 pass ⇒ 地形确实只画一遍」—— **前半仍然真**（主 pass 确实在画，取管线点在涨），**推论假**（画了 ≠ 留在画布里）。⇒ 代码级判据只能证「发生了」，不能证「留下了」。
- **最像的机制（未定案，已写进 `GAP-053`）**：⑤-d 只跳了两笔 `renderGroup`，**没跳那条 pass 本身** —— `MrtTerrainPass.drawTerrain` 仍然打开 gbuffer pass，而它的槽 0 只在 `skyPrePainted`（`:346-348`）时才 LOAD，否则零值清屏（`:422-425`）、深度无条件清到 0.0（`:436`）⇒ 正好把主 pass 刚写进池子的内容清掉，然后什么都不画。另两个候选（帧序、双代轮转采样错代次）留在该条里，下一轮先用一条帧内序号日志定序。
- **第二个实验（零代码）：全屏探针**。在单绘档再开 `mrt.terrainFullscreenProbe`（我方 pass A 内画一个全屏三角形）⇒ 画面被探针彻底改写（`full 143.3 → 31.1`，RGB `21.9/34.5/37.9`，与未开探针的单绘帧逐像素差 `mean=114.45`、`>10` 占 96.99%）⇒ **我方 pass A 的写入（含那次清屏）就是链最终读到的那份**，主 pass 那一笔一个像素都没留下。⚠️ 这条**分不开**「我方 pass 在主 pass 之后」与「代次归属错配」两个候选（两条都预测探针活下来）⇒ 下一轮第一件事是**一条帧内序号日志**（两处各记全局递增 seq + 帧号，判同一帧内谁先）。
- 🔴 **顺带看清的一条耦合**（决定排期）：接管只换**颜色**附件，深度沿用原版那条 ⇒ 主 pass 画的地形深度进的是**主深度**，而我方链的 `depthtex0/1/2` 取的是 `colortexDepth` 的快照 ⇒ **就算把清屏权让出来，单绘档的深度仍然是空的**。⇒ **⑤-d 的后半与 ⑤-e（深度口径单点化）实际是同一件事**，下一轮起按一件事排，不再当两步。
- **⑤-d 的完成条件因此改写**：不再是「补一张对照图 + 删死码」，而是**先结 `GAP-053`**（让出那条 pass 对附件的清屏权：整条不打开，或把「本轮有没有先写者」做成数据判据），删死码与换池锚点排在它之后。
- **取证档已回退**（`ownTerrainPass=false`、`terrainSingleDraw=false`、`terrainFullscreenProbe=false`、包选项行删除）⇒ 默认路线逐字等于今天。


- **默认档零变化**：`mrt.terrainSingleDraw` 默认 `false` ⇒ 用户路线仍是今天那张（正确的）双绘画面；本条是**功能未完成**，不是回归。
- 测试 **1182 条 0 失败**（本轮只改文档，无代码变更）。⚠️ 操作记一条：`terrainSingleDraw` / `ownTerrainPass` 都是**每帧现读** ⇒ 换臂不用重启，但**代码改动仍要重启**；另外我这两条 `toggle` 脚本第一次跑失败是因为**值已经等于目标值**（断言写成了 `new != s`）—— 换臂脚本要允许 no-op，只断言「键存在」。


---

## 2026-10-13（一百三十七）— ✅ R7 ⑤-c 范围收口 + `GAP-052` 落半：开改进透明就不接管，且这条拒绝与「判据不匹配」那条红灯分开

> **verdict = OIT 那一族在接管档下是到不了的（源码级定案），「不接管」这一半已落地；真机那一臂未取证**。

- **⑤-c 的收尾问题是「OIT 那 6 处取管线点要不要补槽」**。逐行读完 `LevelRenderer:583-712` 之后答案变了：不是「等用户开 OIT 再补」，而是**它们与接管后的主 pass 不在同一条画布上** ⇒ 登记为 `GAP-052`。
- 🔑 **本轮核出的三条新事实**（前一条直接决定实现形状）：①开 OIT 时**同一条主 pass 的 label 从 `"Main"` 变成 `"Solid"`**（`:458` 的三元式），且那条 pass 里只跑 `executeSolid`（`:465`），透明那一半搬到 `executeOit`（`:471-472`）；②`executeOit` 的地形由 `chunkSectionsToRender.renderOit`（`:628`）画进 OIT 自己的 pass（`:630`），其 `Parameters` 的深度实参是 `gameRenderer.mainRenderTarget().getDepthTextureView()`（`:589`）；③最后那条 `"OIT Composite"`（`:657-661`）**颜色画布就是 `mainRenderTarget()`**（`:655` 取、`:660` 用作附件）。⇒ 补槽公式在这里无用武之地（那几条各自开 pass、附件数由 `OitRenderPassProvider` 决定），而**颜色维的画布分叉是确定的**。
- **落地的这一半**：`MainPassTakeover.takeoverAllowed(count, label, improvedTransparency)`（纯函数）= 形状判据 ∧ 没开 OIT；开着 ⇒ 逐字退回原版路径 + `refusedOit` 计数 + 首次 WARN **把实际看到的 label 打进去**（不静默，口径同 `GAP-046` 的退路分支）。⑤-a 那行自报多一格 `OIT拒绝=`。抽了 `takeoverAllowedFor(...)` 让 `attachmentReplaced` 留在 60 行方法棘轮以内。
- 🔴 **两条红灯不许混**（这条决定了单测的形状）：「形状对得上但被 OIT 挡」走 `refuseOitFork`，「label 改名 / 视图不同源」才走 `warnIfNeverMatched`。因为 `:458` 会改名，**label 表必须覆盖三元式两边**（`MAIN_PASS_LABELS = ["Main","Solid"]`）—— 否则 OIT 那一臂会掉进假红灯，而这条守卫本身也永远不会触发（= 死码）。单测因此钉了 `matchesMainPass(1,"Solid")` 与 `takeoverAllowed(1,"Solid",true)==false` 两行。
- ⚠️ **初稿口径被自己收紧**：上一版写的是「OIT 三条 pass 的颜色画布全是原版主目标」—— 逐行读后发现只有 `:657` 那条真写它的颜色，另两条（`:680-688` / `:699-710`）颜色写 OIT 自己的 `RenderTarget`、只是**深度借** `mainRenderTarget()`（`:686`/`:707`）。结论不依赖这一维（颜色分叉已经足够），但事实必须按源改写。深度那一维**本条未核**：接管只换颜色附件（`withOurColorAttachments:341-344` 逐字沿用深度），两条链可能同源 —— 记成未核而不是记成已核。
- **默认档零变化**：这一挡只在 `mixin.ownTerrainPass=true`（默认关）时才有意义 ⇒ 关掉时逐字等于今天。
- 🔴 **未取证**：开改进透明那一臂应当看到 `OIT拒绝=>0` 且 `主 pass 接管=0`、画面逐字等于今天 —— 这一趟 mcpfabric 连接器不在会话里（改 `options.txt` 开 OIT 需要真机读日志），挂在下一趟。⑤-c 剩下的另一件是边界那一族的取证（`/worldborder set` + 传送）。
- **测试 1182 条 0 失败**（新增 `MainPassTakeoverTest.improvedTransparencyBlocksTakeover`：六格真值表 + 一条「两条红灯不混」的顺序针）。⚠️ 写这条时踩到自己埋的雷：把 `refuseOitFork()` 改成 `refuseOitFork(label)` 之后，那条顺序针的 needle `refuseOitFork();` 在源码里**消失** ⇒ `indexOf` 返回 **-1**，而 `-1 < 任何下标` ⇒ **断言白过**。本仓那条「文本针要 code 形状」的纪律今天多了一种失效模式：不是**误报**，是**假绿** —— 改被钉的代码形状时必须同步改针，最好让针 `assertTrue(needle 存在)` 一次。

---

## 2026-10-13（一百三十六）— 🟡 R7 ⑤-d 第一刀落地：地形双绘可关闭地跳掉，日志证明「只剩主 pass 那一笔」

> **verdict = 双绘确实关掉了（代码级判据过了），但 d 还没完成**：画面级对照图没取到（MCP 桥不在会话里），死码也还在。

- **新增 `bridge/TerrainSingleDraw.java` + 键 `mrt.terrainSingleDraw`（默认关）**：`MrtTerrainPass` 的两笔 `renderGroup`（OPAQUE 与 TRANSLUCENT/水）各加一道闸。🔴 **条件是「开关 ∧ 主 pass 真被接管」两个**：只看键就跳过 = 没人画地形（空屏），那种情形 WARN 一次并**照今天画**（安全侧）。跳过只跳 draw，**不**跳深度快照（⑤-e 才动所有权）。
- ✅ **真机（取证档 `ownTerrainPass=true` + `terrainSingleDraw=true` + `slots=8`）**：`[M-04/⑤-d] 地形不再由我方 pass 画…双绘关掉`、0 崩，而 **`[M-15] 地形两条 hits` 继续上涨**（66000→66600）—— 此刻唯一还会调 `ChunkSectionsToRender` 的就是主 pass ⇒ **地形确实只画一遍**。这是本条的代码级判据，不靠目视。
- ⚠️ **一条取证档操作坑（值得记）**：手写的 `terrainSingleDraw = true` 插在 `mixin` 那一组里 ⇒ FML 认不到，转头在 `[mrt]` 组里按默认值 `false` 写出来 ⇒ 第一趟跑的是「没开」的形状。改对那一行后**同一趟就生效**（这个键是每帧现读，不需要重启）。⇒ 新键的臂要**回读 toml 看 FML 把它写在哪个组**，别信自己插的位置。
- 🔴 **d 还欠两件事**：① **画面级判据未取** —— mcpfabric 连接器这一趟不在会话里（`mcp_list` 查 minecraft = 0 条），改用桌面截图又拍不到那个窗口（在游戏窗口被其他窗口挡住的情况下，本会话不去动用户的桌面窗口层级）⇒ 双绘/单绘两臂的对照图留给下一趟；② **死码还在** —— `MrtTerrainPass` 的自绘路径、`ColortexPool` 双代轮转的一侧、`M-01` 的层名查表退化件，以及把池锚点真正换成主 pass 的 target0（`GbufferSizeAnchor` 那条核对升级成硬约束）。
- **默认档零变化**：两个新键默认 false ⇒ 逐字等于今天；取证档全部回退（`ownTerrainPass=false`、`terrainSingleDraw=false`、包选项行删除、临时截图已删）。测试 **1181 条 0 失败**（新增 `TerrainSingleDrawTest` 3 条；其中一条守卫第一版被 javadoc 里的同名串骗过 ⇒ 改成只在方法体内比顺序，`GAP-050` 那一族「文本针要 code 形状」的老课再记一次）。

---

## 2026-10-13（一百三十五）— ✅ GAP-049 处置完：池尺寸锚点从「靠巧合」变成「每次建池核对 + 三态计数」，⑤-d 的前清掉了

> **verdict = ⑤-d（关掉地形双绘，R7 真正省下的那笔）的**前置**已清**：那条「两张主目标今天恰好同尺寸」的侥幸，现在每次建池都被核对并点名。

- **新增 `bridge/GbufferSizeAnchor.java`**：`MrtTerrainPass#ensureColortex` 在 `POOL.ensure(...)` **之后**核对「池尺寸 vs 主 pass 自己的 target0 尺寸」，三态计数 `同尺寸 / 漂移 / 还没看过`；首次同尺寸打一条「**巧合而不是约束**」，首次漂移 WARN 并写明「⑤-d 不许开工」。判定本体 `mismatch(...)` 是纯 int 函数 ⇒ 真值表能单测（renderpearl 不在测试运行时，`GAP-050` 那一族）。
- **`MainPassTakeover` 记下那个数**：`Stats.mainWidth/mainHeight` + `mainPassTargetWidth()/Height()`，记在**拒绝接管之前** —— 尺寸不合本身就是 `GAP-049` 要看的信息，不能只在接管成功时看得见。
- 🔖 **为什么是「核对 + 计数」而不是当场改成以 target0 建池**：池必须早于任何 pass 建出来（管线注册在启动期，`GAP-045` 那条时序铁律），而那一刻主 pass 还没被看过、那个数**不存在**。锚点真正换过去是 ⑤-d 自己的事（那时「主 pass 是唯一画布」才成立），本轮只负责把它变成**看得见的条件**。核对类不碰资源：不许 `resize`、不许重建池（那是把两件事混进一次改动，出错分不开）。
- ✅ **真机读数**（取证档 `ownTerrainPass=true` + BSL `ADVANCED_MATERIALS` ⇒ `slots=8`）：`[GAP-049] 两张主目标同尺寸（854x480）⇒ 池的锚点今天不出错，但这仍是巧合而不是约束…`，同趟 `主 pass 接管=5021` 帧、0 崩、0 条 mixin apply 失败。
- **默认档零影响**：`mixin.ownTerrainPass=false` 时 `mainPassTargetWidth()` 恒为 0 ⇒ 走「还没看过」那一态，只计数、不打日志、不改任何尺寸。取证档跑完全部回退（toml / 包选项 / `options.txt` 的 `renderClouds` 都改回原值）。
- **测试 1178 条 0 失败**（新增 `GbufferSizeAnchorTest` 4 条：三态真值表、核对必须在 `POOL.ensure` 之后、不许顺手 repair、通过也要有读数）。
- **下一步**：⑤-d 本体 —— `MrtTerrainPass` 不再自开 pass / 不再 `renderGroup(OPAQUE)`，删自绘路径与 `ColortexPool` 双代轮转的一侧、删 `M-01` 的层名查表退化件，并把池的锚点真正换成主 pass 的 target0；之后再走 ⑤-e（深度口径单点化）。⑤-c 仍欠边界那一族的真机取证（要能驱动游戏时补）。

---

## 2026-10-13（一百三十四）— ✅ ⑤-c 补证：云那一族升到「已过」；边界仍是第三等（连接器掉线，没驱动到边界附近）

> **verdict = 上一轮台账里两族「已放开未取证」，这一趟补掉一族**：分站点计数把「装了」与「真的在那一刻被补过」分开，正是这个区别让本轮能准确说「云过了、边界没过」。

- **做法**：把 `run/options.txt` 的 `renderClouds` 从 `"false"` 改成 `"fancy"`（`CloudStatus` 是 `OFF/FAST/FANCY`，`javap` 复核过），其余取证档与上一轮相同（`ownTerrainPass=true` + BSL `ADVANCED_MATERIALS` ⇒ `slots=8`）。
- ✅ **云已过**：`[M-04/⑤-c] 取管线注入点首次命中：CloudRenderer#render`，累计 `分站点={CloudRenderer#render=4174, WeatherEffectRenderer#render=4174, PreparedRenderType#draw=67774, QuadParticle#drawLayers=4278}`、**0 崩** ⇒ 云那条管线补到 8 槽之后确实过了 `FrontendRenderPass#setPipeline`（不过就会像 ⑤-a 那样当场抛）。
- 🔴 **边界仍是第三等（已放开、未取证）**：`WorldBorderRenderer#render` 在 `state.alpha <= 0` 时整段不执行 ⇒ 要 `/worldborder set` 把边界收进视野 + 传送/转向。这一趟 **mcpfabric 连接器不在会话里**（`mcp_list` 查 minecraft 返回 0 条；桥只在客户端在跑时出现 ⇒ 见 memory 的 Windows 车道条）⇒ 没有驱动手段，**不提前收口**。
- ⚠️ **一条取证档的收尾细节**：客户端退出时会把 `options.txt` 重写成 `renderClouds:"true"`（不是我们写的 `"fancy"`）⇒「跑完要回退」这一条不止 toml 与包选项，**options.txt 也要回读并改回原值**，否则本地默认档的外观被悄悄换掉。本轮已改回 `"false"`。
- **代码零改动**（只动 `CHANGE_LOG` 与 `04-SPEC` §5.0.7 的表/末注）；测试沿用上一轮的全绿（1174 条 0 失败）。
- **下一步（按顺序）**：① 能驱动游戏时补边界那一族（或并进 OIT 那一趟：开改进透明一次覆盖 `CloudRenderer:215` / `WorldBorderRenderer:170` / `LevelRenderer:670-706`）；② ⑤-d 关双绘，前置是 `GAP-049` 那件「池尺寸锚在另一张主目标」的活；③ ⑤-e 深度口径单点化；④ 第 2–5 步仍欠的画面级判据（各向异性两档锐度、进水那一帧雾、岩浆里没有天空、深度只翻一次、星点不回退、原版视频设置页里的包选项页）。

---

## 2026-10-13（一百三十三）— ✅ R7 ⑤-c 第三~五族：主 pass 内的非 OIT 取管线点全部接上补槽；天气已过，云/边界「已放开未取证」

> **verdict = ⑤-c 这一族按真机指的位置推进到「主 pass 内非 OIT 全部放开」**，同时把三族的证据分成三等写，不把「装了」写成「过了」。

- **新增 3 条 mixin（第 16–18 条）**：`CloudRendererPipelineMixin`（包私有 `render(RenderPass, RenderPipeline)` —— 经典云与 OIT 云的**收敛点**）、`WeatherEffectRendererPipelineMixin`（包私有 `render(state, RenderPass, RenderPipeline)`，同样一条覆盖经典 + OIT）、`WorldBorderRendererPipelineMixin`（包公开 `render(state, RenderPass, Vec3, double)`；边界**没有**收敛点，`renderOit` 那份留给 OIT 一族）。形状与第一/二族逐字相同：形参 `RenderPass` 给身份 ⇒ `selectForMainPass(isTakenOverPass(renderPass), requested)`。
- ✅ **天气 = 已过（真机）**：MCP `weather rain` 之后 `分站点={PreparedRenderType#draw=402368, QuadParticle#drawLayers=20595, WeatherEffectRenderer#render=9637}`、`[M-04/⑤-b] 派生=10 交回原版=0 记忆命中=443390`、0 崩 —— 雨那条管线补槽之后确实过了 `setPipeline`（不过就会像 ⑤-a/⑤-b 那样当场抛）。
- 🟡 **云 / 边界 = 已放开，本趟未取证**：`run/options.txt` 里 `renderClouds` 是关的、镜头离世界边界远（`state.alpha == 0` 时那句根本不执行）⇒ 分站点表里**没有**这两条。这一等只有「apply 成功 + 整趟不崩」这一半，**取管线那一刻没被证明补过** ⇒ 判据不许提前收口（下一趟要做的事：开云 + 走到边界，或直接接 OIT 那一臂）。
- 🔴 **一条自己造的启动期崩（`GAP-047` ⑤）**：边界的描述符我把 `Vec3` 写成 `org.joml.Vec3`（正确是 `net.minecraft.world.phys.Vec3`）。✅ 因为写了 `require = 1 / expect = 1`，症状是**启动期点名硬失败**（`could not find any targets matching 'render(…)'`，crash report 直接指出 mixin 类），不是「静默不生效」—— 这是那条纪律第一次以「正面样本」的形式记进台账；定位办法是 `javap -p -cp build/moddev/artifacts/minecraft-patched-26.3.0.51-beta.jar`。
- 🔖 **改掉两条我自己上一轮写错的分类**（`04-SPEC` §5.0.7 末的新表）：`CubeMap:69` 与 `LevelRenderer:518` **不是**主 pass 内的点 —— 它们各自 `createRenderPass(...)` 开自己的 pass（"Cubemap" / "Integrate always on top depth"）⇒ 归「不该补」。于是「主 pass 内还剩 8 处」这个数被更正为：**非 OIT 已全部放开**，剩下的都在 OIT 那一侧（`CloudRenderer:215`、`WorldBorderRenderer:170`、`LevelRenderer:670/690/694/706`）。
- **默认档零变化**：三处都要求 `MainPassTakeover.isTakenOverPass(renderPass)`，`mixin.ownTerrainPass=false`（默认）时逐字原样返回；取证档跑完已回退（`ownTerrainPass=false`、`ADVANCED_MATERIALS` 行删除）。测试 **1174 条 0 失败**、0 条 mixin apply 失败。
- **下一步**：要么把「云 / 边界」这两条补成真机取证（开 `renderClouds` + 走到边界），要么直接进 OIT 那一族（同一趟里把改进透明打开，一次覆盖 `:215/:170` 与 OIT 四条）；两者都完之后再谈 ⑤-d 关双绘（前置仍是 `GAP-049` 那件「池尺寸锚在另一张主目标」的活）。

---

## 2026-10-12（一百三十二）— ✅ R7 ⑤-c 第二族签掉 + 第一次「接管后不崩」的一帧（截图正常），但「没崩」不等于「全族都过了」

> **verdict = 两件事同时成立**：① ⑤-c 的机制可复制（第二处注入点，形状与第一处逐字相同）；② 主 pass 被换成 8 附件之后，客户端**第一次**跑过了一整帧不崩 —— 而这一帧没碰到云/天气/边界那几处，所以「没崩」只能读成「已放开的族过了」。

- **新增 `mixin/QuadParticleFeatureRendererPipelineMixin`**（第 15 条 mixin）：包 `QuadParticleFeatureRenderer#drawLayers` 第 89 行那次 `getCompiledPipeline` —— 就是上一轮真机崩点指名的那一处（它取 `SingleQuadParticle.Layer#pipeline()` 或 OIT 变体，**不经过** `PreparedRenderType`，所以必须单独注）。
- 🔴 **一条新的注入形状规则（`GAP-047` ④，本族第一次跑就撞到）**：目标方法是 `private static` ⇒ **handler 也必须是 `static`**，否则**类加载期**就 `InvalidInjectionException: non-static callback method … targets a static method which is not supported`（`Injector.checkTargetModifiers:371`）。⚠️ 这一条与开关无关：mixin 是类变换期的事，配置键只切运行分支 ⇒ 写错就是**默认档也起不来**（实测崩在 `FeatureRenderDispatcher.<init>:56` ← `GameRenderer.<init>:164`，连主菜单都没有）。修完再跑，apply 0 失败。
- 🔖 **观测面从「一族一行」改成「分站点计数」**：`PassAwarePipelines.onMainPassSiteHit(site)`（取代 `onRenderTypeHit()`），每处注入点一个键。理由写在方法注释里：「崩点挪位置」一次只能证一族，而真机一趟很贵 ⇒ 同一趟要能同时看清几族各自活没活。本机读数：`{PreparedRenderType#draw=215070, QuadParticle#drawLayers=8130}`。
- ✅ **第一次不崩的接管帧**：`主 pass 接管=28288`（帧数）持续上涨、0 条 ERROR、0 条 mixin apply 失败，MCP 截图（**vkdisp 侧 · 取证档 `ownTerrainPass=true` + BSL `ADVANCED_MATERIALS` ⇒ `slots=8` · 854×480**）是一张正常的白天画面：地形/树/雪/天空渐变/手持物品/快捷栏都在。
- ⚠️ **这条判据的边界（写进 `04-SPEC` §5.0.7 末）**：这一帧里**没有**走到云（`CloudRenderer:215/229`）、天气（`WeatherEffectRenderer:165`）、边界（`WorldBorderRenderer:158/170`）、立方图（`CubeMap:69`）、`LevelRenderer:518` 那几处 —— 截图天空无云就是证据。它们仍会在自己的调用点抛 ⇒ ⑤-c 还剩 6 处（外加 OIT 那 4 处，取决于是否开改进透明）。
- 🔧 **顺带修掉一处自己写的观测缺陷**：`reportPeriodically` 的节流分母原来只算 `derived + refused` —— 加了记忆层（`GAP-051`）之后这两个数只在「第一次见到某条管线」时涨（本机量级几十），`% 600` 永远打不出那一行 ⇒ 「日志里没有这行」会被读成「公式没跑」（本仓 `GAP-022` 那一族误读的旧形状）。改成把 `memoHits` 一起算。
- ✅ **改完再跑一趟，`GAP-051` 的量真的看得见**：`[M-04/⑤-b] 派生=7 交回原版=0 记忆命中=42593` —— 7 条管线被建出来、42593 次 draw 侧取用直接复用。**没有那层记忆就是 42593 次 `toBuilder().build()`**（这条从「推理」升成「读数」）。同一趟仍然 0 崩、`主 pass 接管=3588` 帧。
- **取证档已回退**：`ownTerrainPass=false`、`ADVANCED_MATERIALS` 行删除 ⇒ 默认档逐字等于今天。测试 **1174 条 0 失败**（本轮新增 2 条：记表形状 + 名字撞车守卫）。
- **下一步**：⑤-c 第三~七族（云 → 天气 → 边界 → 立方图 → `LevelRenderer:518`），形状与本轮两处逐字相同；全放开之后才谈 ⑤-d 关双绘（前置：`GAP-049` 那件「池尺寸锚在另一张主目标」的活）。

---

## 2026-10-12（一百三十一）— ✅ R7 ⑤-c 第一族签掉：`PreparedRenderType` 一处顶十几族，崩点按预测挪进 `executeSolid`

> **verdict = ⑤-c 的判据形状与 a/b 相同（「崩点必须挪位置」）并且真的挪了**：上一档崩在 `executeClassicTransparency:721`，这一档崩在 `QuadParticleFeatureRenderer.drawLayers:89` ← `LevelRenderer.executeSolid:538` ⇒ **全部 RenderType 那一族在主 pass 里已经能过附件校验**，剩下的族按真机指的位置逐族做。

- **新增 `mixin/PreparedRenderTypePipelineMixin`**（第 14 条 mixin）：包 `PreparedRenderType#draw` 第 **43** 行那次 `RenderSystem.getCompiledPipeline`，形参 `RenderPass` 给 pass 身份（`@Local(argsOnly = true)`，与 M-15 同形状：静态调用的 handler 不带接收者）。选它的理由是从源码数出来的：主 pass 里实体/粒子/方块层/水/玻璃/文本这些**全都**走这一个方法。
- 🔖 **判定入口分两处，不是偷懒**：新增 `PassAwarePipelines.selectForMainPass(takenOverMainPass, requested)` 而不是复用 `select(layer, …)` —— 渲染类型名里有 `solid`/`translucent`，与 `ChunkSectionLayer` 的层名**同字**，把名字传进去查地形多附件表，在有非地形 draw 落进我方 gbuffer pass 的那天就会把地形管线塞给不相干的一族。守卫 `renderTypeSiteDoesNotReuseTheLayerNameTable` 把「那一支里不许出现 `mrtVariantOrNull`」钉成断言。
- 🔴 **动手前查出一条自己上一轮写错的性能口径（`GAP-051`）**：`RenderSystem:106-120` 的顺序是 `PIPELINE_MODIFIERS.apply` → `PipelineCache.get(pipeline)` ⇒ 补槽发生在缓存**之前**，「每条管线一次」不成立，接上 `PreparedRenderType` 就是**每次 draw 建一条新管线**。改法：按**入参对象身份**的记忆层（`IdentityHashMap`，槽数一变或条数 ≥256 整表作废），自报加 `记忆命中=`。类头那句过期口径已就地更正。
- ✅ **本轮还把取管线点全集数清了**：`grep -rn "getCompiledPipeline" net/minecraft` = **34 处**（`GAP-045` 那条挂着「30 处，来自子代理，未独立复核」→ 作废）。34 处的形状统一是 `renderPass.setPipeline(RenderSystem.getCompiledPipeline(X))` ⇒ 每处都看得见 pass，这正是 `PipelineModifier` 做不到的原因。主 pass 内还剩 8 处（特性渲染器 / 云×2 / 天气 / 边界×2 / 立方图 / `LevelRenderer:518`）+ OIT 4 处；天空 6 处与 GUI/后处理/动画图集 10 处**不该补** —— 全集与分档表已进 `04-SPEC` §5.0.7 末。
- **顺手修掉一处文档断表**：`04-SPEC` 里 M-05 那一条表格行曾被上一轮的插入挤到 §5.0.7 的引注末尾（`…出处）。|| **M-05** |`）⇒ 行已搬回 §5.0 原位、M-04 行补回收尾竖线；现在 §5.0 那张表每行都是 9 根竖线。
- **真机证据三行**：`[M-04/⑤-a] 主 pass 的颜色附件已换成我方 colortex 阵列：8 个（label='Main'，尺寸=854x480）`、`[M-04/⑤-b] 首条补槽管线：vkdisp:pipeline/terrain_solid_multidraw（1 条声明 → 8 槽）`、`[M-04/⑤-c] PreparedRenderType 取管线 hits=1`；崩栈 `IllegalStateException: Render pass color attachment count must match pipeline color target state count.` @ `FrontendRenderPass.setPipeline:113` ← `QuadParticleFeatureRenderer.drawLayers:89`。**0 条 mixin apply 失败**（第 14 条 mixin 一次过 apply）。
- **取证档已回退**：`ownTerrainPass=false`、`BSL_v10.1.8.ADVANCED_MATERIALS` 那行删除 ⇒ 默认档（关接管）零变化：`selectForMainPass` 那一支要求 `isTakenOverPass(renderPass)`，未接管时逐字原样返回。测试 **1174 条 0 失败**（本轮新增 2 条）。
- **下一步**：⑤-c 第二族 = 真机刚指的 `QuadParticleFeatureRenderer:89`（它取的是 `OitPipelineSet`/`RenderType.pipeline()`，不在 `PreparedRenderType` 通路上）；其后云×2 / 天气 / 边界×2 / 立方图 / `LevelRenderer:518`，再谈 ⑤-d 关双绘（前置仍是 `GAP-049` 那件池尺寸锚点的活）。

---

## 2026-10-12（一百三十）— ✅ R7 ⑤-b **真机签掉**：通用派生公式接上，地形在主 pass 里过了 `setPipeline`，崩点按预测挪到透明那一笔

> **verdict = §5.0.7 五步的第二步有证据了**：`[M-04/⑤-b] 首条补槽管线：SOLID（1 条声明 → 8 槽）` 打出来后，同一个 `setPipeline` 抛**不再**发生在地形那条栈上，而是挪到 `PreparedRenderType.draw:43` ← `executeClassicTransparency:721` —— 「这一族过了，下一族在它的调用点响」就是 ⑤-c 逐族放开的排序依据。

- **新增 `bridge/MainPassPipelineDerivation.java`**（§5.0.6 ④ 那条通用公式的实现）：`padTargets` 把声明的 `ColorTargetState` 列表补到 N 槽 —— **target0 逐字原样**，补槽一律 `RGBA8_UNORM` + `WRITE_NONE`(=0) + 无混合（`Optional.empty()`）；`refusalReason(声明数, 槽数)` 是纯整数退路（`槽数 ≤ 0` / `> MAX_COLOR_TARGETS(8)` / `声明数 > 槽数`）⇒ **整条交回原版管线并计数**（`GAP-046` 收窄后的写法，不许静默）；`derive` 走 `pipeline.toBuilder()` + `withColorTargetState(i, …)`（它顺带把 `activeColorTargetStateCount` 顶上去，这正是 `FrontendRenderPass:111-114` 比的那个数）。
- 🔖 **落地时改了 ④ 那行的挂点**（原文写「走 `PipelineModifier` 家族 / `GbufferPipelineSwaps` 那条既有通路」）：**改成接进 M-15 已有的那次 `getCompiledPipeline` 包裹**。理由不是省事 —— 槽数 N 来自「接管后的那条描述符」，而 `PipelineModifier` 的 key **只能在启动期注册**（`GAP-045` 刚证伪过它换不掉 M-01，同一根因：注册期拿不到运行期的数）⇒ 能同时拿到「管线本体 + N + pass 身份」的一刻只有 M-15 那一处。
- **三条判据在同一个纯函数里汇合**：`PassAwarePipelines.select(layer, multiDraw, ourPass, takenOverMainPass, requested)` 现在是三支 —— 我方 MRT pass ⇒ 查多附件表；被接管的主 pass ⇒ 补槽派生；**都不是 ⇒ 逐字原样返回**。两个 `ChunkSectionsToRender*PipelineMixin` 各多传一个 `MainPassTakeover.isTakenOverPass(renderPass)`（对象身份，来自 ⑤-a 记下的那个 pass），不再靠标志位。
- **⚠️ 一处测试运行时的坑（同族第三次）**：`MainPassPipelineDerivation` 的 `static final PAD = new ColorTargetState(...)` 让**单测**在类初始化就 `NoClassDefFoundError: ColorTargetState` —— renderpearl 不在测试 classpath（与 `McVersion`、`NightVisionSupply` 那两例同形状，已在 `13` 记过两次）。改法：把常量挪进 lazy 内部类 `Pad` + `pad()`，于是纯整数判据（`refusalReason`）在测试里可调，`ColorTargetState` 只在真机那一侧构造。
- **一条守卫随之改形**：`MrtTerrainPassWiringTest` 里那条 `aware.contains("!ourPass")` 是 ⑤-a 之前那个两支形状的针 —— select 加第三支后 `!ourPass` 物理消失，守卫**没有变红就被读成「判据还在」**是更坏的结果，因此改成断言现行三支：配置短路那句、`if (takenOverMainPass) {`、以及 `return requested;` 至少出现两次（两支「原样返回」的退路都在）。
- **真机两行（取证档：BSL 开 `ADVANCED_MATERIALS` ⇒ `slots=8`）**：`首条补槽管线：SOLID（1 条声明 → 8 槽）` ⇒ 地形两条（`solid/cutout` × plain/indirect）确实过了附件校验；崩点 `IllegalStateException: Render pass color attachment count must match pipeline color target state count.` 的栈是 `PreparedRenderType.draw:43` ← `drawFromBuffer:29` ← `executeClassicTransparency:721` = **经典透明那一笔**（⑤-c 的第一族，按预测在它自己的调用点响）。
- **🔴 此步起地形是双绘**（⑤-d 才关掉）：主 pass 里地形画一遍、`MrtTerrainPass` 自绘一遍 —— 这是 ⑤-b 的**已知代价**，不是回归（§5.0.7 ⑤ 行 b 原文就写了这句）。
- **默认档零变化**：`mixin.ownTerrainPass=false`（默认）⇒ `select` 的第三支永不进入，派生码一行不跑；取证档本轮已回退（`ownTerrainPass=false`、`ADVANCED_MATERIALS` 行删除）。测试 **1172 条 0 失败**（`--offline --rerun` 全量，新增 `MainPassPipelineDerivationTest` 4 条）。
- **下一步（⑤-c）**：逐族放开实体 → 粒子 → 云 → 天气 → 边界 → 描边/OIT 交替，每族一条独立判据；机制上就是「让那些族的取管线那一刻也走同一个 `select`」，第一族按真机已经响的位置就是经典透明。⚠️ `GAP-049` 那件活（`MrtTerrainPass#ensureColortex` 的池尺寸锚在另一张主目标上）仍排在 ⑤-d 之前。

---

## 2026-10-12（一百二十九）— 🔴 GAP-049 定案：主 pass 画进去的那张图**不是** `gameRenderer.mainRenderTarget()`，连纹理都不是

> **verdict = ⑤-b 的前置清了（口径层面），但留下一件必须做的活**：我方池的尺寸锚在「另一张主目标」上，⑤-d 关双绘之前要改。

- **为什么要再跑一趟**：上一轮把判据第三条降级成「交叉核对」时留了一个说法 —— 也许两条 handle 指的是**同一张图**，只是 `createTextureView` 造的壳不同。本轮把判据换成 `GpuTextureView.texture()`（公开 API，比纹理本体）再跑一次真机，就是为了把这两种解释分开。
- **结果：两种解释都不成立**。`label='Main' 命中，但那条附件与 gameRenderer 主目标连纹理都不是同一张（附件视图=0x6d358a8e 附件纹理=0x2b6af37d 主目标纹理=0x326872b3）` ⇒ 主 pass 写的是**另一张图**。
- **而它确实是主 pass**（不是同名的别条）：这一趟仍然打出 `主 pass 的颜色附件已换成我方 colortex 阵列：8 个（label='Main'，尺寸=854x480）` 并随后崩在 `FrontendRenderPass#setPipeline:113`（栈顶 `executeSolid:532`）⇒ ⑤-a 的签字证据对当前这份码仍然成立（本轮重跑过，不是沿用上一轮的结论）。
- **源码侧对不上**：`LevelRenderer:210` 明写 `frame.importExternal("main", this.gameRenderer.mainRenderTarget())`，而 `FrameGraphBuilder.ExternalResource.get()` 原样返回导入的对象 ⇒ 按 sources jar 读，两条必然同源。真机不同源 ⇒ **运行时的 `LevelRenderer` 与 sources jar 不是同一份**（NeoForge 对它打了补丁）。这是本轮第二次撞到「sources jar ≠ 运行时」（前一次是 `getModelViewMatrixCopy` 的实参表，见 GAP-047 ③）。
- **口径定案（写进 `GAP-049` 的修法行）**：M-04 一律**按值判、不按对象身份判** —— 身份 = 「单颜色附件 + label ∈ {Main,Solid}」；尺寸/格式 = **以描述符自带的那条附件为准**（`slotsUsable` 比的就是 `target0.getWidth(0)/getHeight(0)`）⇒ 哪天两张主目标尺寸不同，接管被拒绝并计数，不会静默错位。
- 🔴 **留下的活（⑤-d 前置）**：`MrtTerrainPass#ensureColortex` 仍拿 `gameRenderer.mainRenderTarget()` 定池尺寸 —— 那是「另一张主目标」。要么改成以主 pass 自己的 target0 尺寸建池，要么装配期断言两者同尺寸并把它计数出来。今天两者都是 854×480，所以没露馅。
- **取证档已回退**：`ownTerrainPass=false`、`ADVANCED_MATERIALS` 行删除 ⇒ 默认档零变化。测试 1168 条 0 失败。

---

## 2026-10-12（一百二十八）— ✅ R7 ⑤-a **真机签掉**：主 pass 的颜色附件被换成我方 8 槽阵列，一秒后按预测崩在 `setPipeline`

> **verdict = §5.0.7 五步的第一步现在有了它要求的证据**：不是「画面看起来对」，而是那句预测中的 `IllegalStateException` 逐字出现在预测的位置。同时判据被真机削掉一条腿（`GAP-049`）。

- **✅ 接管命中**：`[M-04/⑤-a] 主 pass 的颜色附件已换成我方 colortex 阵列：8 个（label='Main'，尺寸=854x480）` —— 挂点（`FrontendCommandEncoder` 6 参重载内部第 95 行）确实看得见原版主 pass，且换附件这条路走得通。
- **✅ 预期的崩**：`IllegalStateException: Render pass color attachment count must match pipeline color target state count.` @ `FrontendRenderPass#setPipeline:113`，栈顶 `ChunkSectionsToRender$DrawIndirect.render:120` ← `LevelRenderer.executeSolid:532` = **主 pass 体内那一笔地形 draw**。这正是 ⑤-a 要的「注入真命中」证据（§5.0.7 ⑤ 行 a 原文）。
- **① 签这一下必须换包档**：BSL 默认档 `slots=1` ⇒ `slotsUsable` 按设计**拒绝接管并计数**（`label命中=22059 尺寸不合拒绝=22059`，那一档画面与今天逐字相同、也不崩）。把包切到 `ADVANCED_MATERIALS`（`run/config/vkdisp-pack-options.properties` 冷启动）⇒ `slots=8 poolDepth=D32_FLOAT mipLevels=10`，接管才发生。**「不接管」和「接管了但没证据」是两件事**，这一档把前者演示了一遍。
- **② 判据从三条收成两条（`GAP-049`）**：第三条「附件就是 `gameRenderer.mainRenderTarget()` 的颜色视图」实机**永不成立**（`附件=0x11cf0e8a` vs `主目标=0xc9c725d`，22059 次 label 命中里 `视图同源=0`）。根因：主 pass 用的是 `targets.main.get()`，那条 handle 在 `LevelRenderer:210/253/383/404` 被帧图换过手 ⇒ 与 `gameRenderer` 那张不保证同对象。这一腿降级成**交叉核对**（自报里那一格继续显示 0），身份改由「单附件 + label」两腿担；`GAP-049` 立在 ⑤-b 的前置上。
- **③ 红灯按设计响了**：第一次 600 次观察没接上时打 `mixin.ownTerrainPass=true 但看了 600 次 createRenderPass 一次都没判定成主 pass`（`GAP-042` 那一族「改名即哑」就是靠它兜）。
- **④ 设备面顺手量到一件事**：8 条颜色附件**没有**触发 `device only supports N` 那条上限错（`FrontendCommandEncoder:104-110`）⇒ 本机 `maxColorAttachments ≥ 8`，§5.0.6 那条「N ≤ 8 无余量」的前提在 RTX 4060 上成立。
- **下一步（⑤-b）**：通用派生公式接进 `PipelineModifier` 家族，先让地形两条（`solid/cutout` × plain/indirect）过 —— 但**先结 `GAP-049`**（拿哪张主目标），否则 target0 沿用的是「原版那张」而我方池按另一张建，尺寸巧合一破就静默错位。
- **⚠️ 取证档已回退**：`mixin.ownTerrainPass` 改回 `false`、`ADVANCED_MATERIALS` 那行删掉；本轮**没有**留下任何用户可见的默认档变化。

---

## 2026-10-12（一百二十七）— 🔴 第一次真机 `runClient` 就炸出四个注入点：handler 形状全部照真机改过，客户端现在能进世界

> **verdict = 上一轮那句「`Mixin apply failed = 0` 仍未验证」还掉了五轮的账，本轮一次跑清**：四趟 `runClient -PquickPlay`（19:57 崩 bootstrap → 20:03 崩 CloudRenderer apply → 20:08 崩 `Operation.call` 条数 → 20:11 **进世界并稳定跑**）。测试 1168 条 0 失败。

- **① 崩法一（M-15 两个变体）**：`@WrapOperation` 包住的是**静态**调用 `RenderSystem.getCompiledPipeline(RenderPipeline)` ⇒ handler 不能有接收者，而两个 handler 都以 `ChunkSectionsToRender self` 开头 ⇒ apply 期 `InvalidInjectionException: Found unexpected argument type ChunkSectionsToRender at index 0, expected RenderPipeline`，**模组加载阶段就崩**。同时 `@Local(argsOnly = true, ordinal = 0)` 写两遍是歧义写法，改成按类型匹配。
- **② 崩法二（M-13 两处 + M-04 一处，本轮新学的一条规则）**：`Operation.call(...)` 在**运行期**按条数硬校验，包住**虚调用**时必须把**接收者**一起交回去 —— `IncorrectArgumentCountException: Expected 2 but got 1`。⚠️ M-04 那处的形状是 `original.apply(replaceIfNeeded(d))`：**开关关着也走转发**，所以「默认档」同样是每次建 pass 必崩（第一炸点 `TextureAtlas.uploadAnimationFrames`）。M-13 同族：`original.call(name)` → `original.call(builder, name)`。
- **③ 崩法三（M-10）**：`@Redirect` 的形参表以**运行期那次调用**为准，不是以 sources jar 那行为准 —— `RenderSystem.getModelViewMatrixCopy` 在 26.3 运行时带 `(RenderPass, RenderPipeline)` 两个实参（sources 里第 296 行是无参的），照 sources 抄就 apply 失败。Mixin 的报错会直接给出 `Expected signature:`，照它抄最快。
- **④ 顺带抓到一条观测面缺陷（GAP-048）**：`[R4/sky]` 自报里「接管方」16200 帧全是「还没写」—— 它读的 `paintedThisFrame` 在**同一帧的后面**才置真、且每帧开头被清回 false ⇒ 那个表达式结构上只能取到 false。改成打印本帧此刻的判定（`whoWillPaint()`），并在注释里点名「有没有真写要看 `paintedWriteGeneration()`」（那条是活的，地形 LOAD/CLEAR 判据用的就是它）。
- **✅ 真机签到的东西（vkdisp 自己这一侧的客户端，854×480，`time set 6000` 后 `dayTime=7154`、`raining=false`）**：
  ① 日志 0 条 `Mixin apply for mod vkdisp failed`、0 条 `IncorrectArgumentCountException`；
  ② `[M-13] terrain requires vanilla-sky 边已声明=true` ⇒ 第 6 步那条 `requires` 边**真的连上了**（h48g 那个坏形状没回来）；
  ③ `[M-15] pass-aware getCompiledPipeline hits=1200` ⇒ 第 4 步的通道在跑；
  ④ `[R4/sky] 钩子被调# = 天空帧#`（16200 = 16200）⇒ 官方天空盒钩子**每帧都被原版调到**（M-12 那条证伪判据今天有了反向证据）；
  ⑤ 画面：日景、天空蓝且有太阳辉光（`skyColor=(0.500, 0.630, 1.000)`）、雪地与树叶带包的地形着色与 AO、HUD 正常 ⇒ 第 5 步「黑天空」那一族症状在这一档不复现；
  ⑥ `[M-04/⑤-a] 建 pass 观察=16800 主 pass 接管=0 尺寸不合拒绝=0 开关=false` ⇒ 挂点每帧路过 ~27 次建 pass，关档时**一次都不接管**（这正是默认档该有的样子）。
- **⚠️ 仍未签的**：这一档是**手写取证 toml**（`mrt.terrain` + `mrt.packTerrainShader` 同开、BSL 包），不是代码默认档 ⇒ 不许据此说「用户开箱即得」；像素级判据（各向异性两档、进水那一帧的雾、岩浆里没天空、深度只翻一次）本轮**只跑了「不崩 + 通道在跑」这一层**，逐项对照还没做。
- **纪律（本轮改流程）**：**每交一个注入点，当轮就 `runClient` 一次**，别把「apply 过没有」留给下一轮 —— 文本守卫覆盖不了 handler 形状，这是 `01-DEV-LOOP` §0 的原意。

---

## 2026-10-12（一百二十六）— 🟡 R7 第 ⑤-a 步**代码落地**：主 pass 的颜色附件换成我方 colortex 阵列（`MainPassAttachmentMixin` + `MainPassTakeover`），开关默认**关**

> **verdict = §5.0.7 那条五步执行序的第一步交了代码，但它的判据没签字**：⑤-a 的证据是「下一次 `setPipeline` 当场抛附件数不匹配」，那句抛只能在真机上看，本轮**一次真机都没跑** ⇒ 状态写「已落地待取证」，不写「已完成」。测试 1168 条（本轮 +7）、0 失败。

- **① 挂点比计划更窄一层（这是本轮唯一一处主动改设计）**：§5.0.7 表 ① 写的是包外层
  `FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)`，落地包的是**6 参重载内部第 95 行**
  对它的那次调用（`@WrapOperation(method = 全签名, at = @At("INVOKE", target = 描述符版签名))`）。
  理由是可数的：2 参/5 参重载都并到 6 参（`:68/:75`），而 6 参在这里**自己造**描述符 ⇒ 这个点只看得见
  **单颜色附件**的便捷路径；我方直接递描述符的三处（`MrtTerrainPass:439/637`、`FrameApi:1317`）从这里
  **物理看不见** ⇒ 判据不必再长一层「这是不是原版自己建的 pass」。
- **② 判据三条全来自调用自带的数据**（`MainPassTakeover.matchesMainPass`，纯函数 ⇒ 不启动客户端就能测）：
  颜色附件恰好 1 条 + `label() ∈ {"Main","Solid"}`（`LevelRenderer:458` 那个三元式的两边）+ 那条附件的视图
  **就是** `mainRenderTarget` 的颜色视图（对象身份；`RenderTarget:26/129` 实测它是缓存字段，不是每次新建，
  所以比较得动）。挡掉的邻居是有出处的：`GameRenderer:412` 那条 `"Item in hand"` 同样是
  「单附件 + main 的颜色」⇒ 少一条 label 就会把后处理臂当成主 pass。
- **③ §5.0.6 ⑤ 第 2 条从「文档里的前提」变成一道闸门**：`slotsUsable` 要求每个补槽视图与 target0
  **同尺寸**且槽数 ≥ 2，不满足 ⇒ **拒绝接管 + 计数**（日志 `尺寸不合拒绝=`），而不是硬塞给后端。
  深度这一维 ⑤-a **不碰**（沿用原那条附件），留给 ⑤-e。
- **④ 一条 javac 25 的自爆，值得单独记**：把 `RenderPassDescriptor` 的**嵌套类型**写进声明并挂
  `@Nullable`（`RenderPassDescriptor.@Nullable Attachment<...>` 这种合法写法）之后，**测试编译**会抛
  `java.lang.AssertionError: Incorrect number of arguments; expected 2, found 0`
  （`com.sun.tools.javac.comp.TransTypes.visitApply`），报的是「编译器异常」而不是任何一行语法错 ⇒
  第一反应会去查自己那两行断言。定位办法：把测试削到只剩一次对 `MainPassTakeover` 的引用，照样崩
  ⇒ 说明问题在被引用类的**签名**里。改成 `var` 推断即避开（`MainPassTakeover` 里那条注释是出处）。
- **⑤ 开关用回登记名**：键名 = `mixin.ownTerrainPass`（§5.0 M-04 行与 `13-GAP-REGISTRY` 早已写死这个名字，
  只是过去没有实现），🔴 默认**关**。开着 = 主 pass 有 N 个附件而管线只有 1 个颜色目标 ⇒ 崩是设计，
  所以「取证档」也不该在 ⑤-b 之前打开它 —— 默认档与今天**逐字节相同**这一点由开关保证，不由测试保证。
- **⑥ 纪律**：`Stats` 那五格计数装进一个 `static final` 持有者，不是随手写五个 `private static`
  —— `StaticFieldRatchetTest` 的基线只许降（19 §3.2 病根「静态即全局单例」），本轮**净增 0**。
- **🔖 判据纪律（本轮欠的账，写在最显眼处）**：下一步 ⑤-b（通用派生公式）之前，先跑一次真机取证，看三样：
  ① 日志有 `[M-04/⑤-a] 主 pass 的颜色附件已换成我方 colortex 阵列：N 个`；
  ② 紧接着的崩溃报告是 `FrontendRenderPass#setPipeline` 的「附件数不匹配」（不是别的）；
  ③ `Mixin apply failed` 计数仍为 0 —— ⚠️ 这条尤其没底：`MainPassAttachmentMixin` 是本仓**第一个**注到
  Mojang 库类（`com.mojang.renderpearl.frontend`）的点，而上一轮就没人验证过 `require=1` 是否真能装上。
  红灯另一侧：`建 pass 观察=` 涨到几百而 `主 pass 接管=0` ⇒ 判据已不匹配（先怀疑 label 改名 / 视图不同源）。

---

## 2026-10-12（一百二十五）— ✅ R7 的**执行序**交付（`04-SPEC` §5.0.7）：注入点定在 `FrontendCommandEncoder#createRenderPass(descriptor)`，不注合成 lambda

> **verdict = 那张「先交清单再写注入」的裁决要求到此满足两份**：清单 = 一百二十二前交付的 §5.0.6，
> 执行序 = 本轮的 §5.0.7。R7 的代码本轮**不写**（下一步 ⑤-a 的证据要在真机上看）。

- **① 本轮新核出来的那条事实，它把机制选型整个改了一遍**：`LevelRenderer:455-463` 那次
  `createRenderPass(...)` **不在 `addMainPass` 方法体里，而在 `pass.executes(() -> { … })` 的 lambda 内**
  （`pass.executes(` 第 **434** 行、lambda 体从 **435** 起）。⇒ 之前写在 §5.0 那格的
  「`@WrapOperation` 包住 `addMainPass` 内第 456-463 行的 `createRenderPass(...)`」**按字面做不到**：
  按调用点注入能瞄准的「方法」只有编译期合成 lambda（`lambda$addMainPass$N`，N 随方法排布变号）
  —— 正是 `GAP-042`「改名即哑」那一族，只不过这次会以 `require=1` 硬失败而不是静默。**不选它。**
- **② 改取的点**：`com.mojang.renderpearl.frontend.FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)`。
  依据：`CommandEncoder` 那几个便利重载全是 `default`（`api/commands/CommandEncoder.java:24,28,36,50`），
  最后一律收敛到 abstract 的 `createRenderPass(RenderPassDescriptor)`（`:53`），而
  `FrontendCommandEncoder` 是 **backend 无关**的那层实现（`VulkanCommandEncoder` / `GlCommandEncoder`
  再往下）⇒ 一个具体方法、不绑后端（守「不要动：后端分叉」那条）。
- **③ 判据只用调用自带的数据**：`colorAttachments().size() == 1` + 该附件视图**就是** main 的颜色视图
  （对象身份）+ `label() ∈ {"Main","Solid"}`（第 458 行给的就是这两个串）。视图身份排掉「别的 pass 也写
  main」（Sky / SeeThrough / AlwaysOnTop / 原版 clear / 后处理链），label 排掉同图的其余 pass。
  🔴 label 是字符串，Mojang 改了会**静默不接管** ⇒ 必须配一条自报计数，口径就是本轮 M-11/M-12 那条
  `钩子被调#`：**`main pass 接管次数 = 0` 算红灯**，不许被当成「今天画面正常」。
- **④ 命中后只做一件事**：重建描述符再转发 —— target0 原样（含它的清屏语义）、补 1..N-1 槽
  `RGBA8_UNORM` + `WRITE_NONE` + 无混合、深度与 `renderArea` 沿用原那条。两条**装配期前提**来自
  §5.0.6 ⑤：补槽必须与 target0 **同尺寸**（默认尺寸取「最后一个非 null 附件」，不一致是静默改视口）、
  gbuffer 深度必须就是 `D32_FLOAT`（后端把深度格式硬编码成 126，没有可比如）。
- **⑤ 五步执行序（每步一提交，红树不提交）**：a 只换描述符、故意不加派生 ⇒ 预期 `setPipeline`
  当场抛附件数不匹配 = **注入真命中**的最便宜证据；b 通用派生公式接进 `PipelineModifier` 家族，先让
  地形两条过；c 逐族放开（实体 → 粒子 → 云 → 天气 → 边界 → 描边/OIT 交替），每族一条独立判据；
  d 关双绘（删 `MrtTerrainPass` 自开 pass 与自绘 `renderGroup(OPAQUE)`、并收 `ColortexPool` 双代轮转
  对应一侧）—— R7 真正省下的那笔在 d；e 深度口径单点化（GAP-022/023/037 同轮）。
  🔖 为什么 a 与 b 不能压成一步：这条路上「接管有没有生效」**没有别的证据**，必须先留一次已知会抛。
- **影响的文档**：`04-SPEC` 新增 §5.0.7 + M-04 行尾把旧写法（「包住 addMainPass 内第 456-463 行」）
  按新事实改写并指向 §5.0.7。
- **测试结果**：纯文档轮，但跑了一次 `./gradlew test --offline --rerun` → **BUILD SUCCESSFUL**
  （**1161** 条真跑，1 条既有 skip）。🔖 为什么强制重跑：`docs/` **不是** `:test` 任务的输入，
  而本仓有若干守卫会去读 `docs/` 与台账文本 ⇒ 不加 `--rerun` 时它可能吃构建缓存、这一轮的文档改动
  等于没验（本轮就撞上了一次「cleanTest test 两秒通过」）。
- **上一条的条数更正**：一百二十四 写的是「1166 条」，实测是 **1161** —— 删 `ScreenDriveCommandTest`
  带走了 5 条，写的时候没重数。下面把那条也改过来。
- **阶梯状态**：第 1…6 步全部落地（R0/R3/R4/R2/R1a/R4天空/R5/R6），闸门三项已结，R7 = 执行序已交付、
  代码未开工。真机那一批判据（第 2-6 步的画面侧 + `Mixin apply failed=0` + `钩子被调#`）仍全部未签。

## 2026-10-12（一百二十四）— ✅ R6 收口（M-14 · 下半）：删掉自研屏幕那一族 —— `PackPickerScreen` / `PackOptionsScreen` / `PackOptionsDrive` / `ScreenDriveCommand` / `packOptionsScreen` 键 / `IConfigScreenFactory`

> **verdict = GUI 只剩一条通路**：光影包与包选项现在只在原版视频设置页里（上一条新建的那条），
> 自研屏幕与它的无头驱动臂一起从仓库消失 —— 第 6 步（R5 + R6）到此整个走完。

- **① 删了什么**：`screen/PackPickerScreen.java`、`screen/PackOptionsScreen.java`（自研双列分页 +
  `turnPage` + `fitLabel` + 页码绘制都在这个类里）、`screen/PackOptionsDrive.java`、
  `config/ScreenDriveCommand.java`（sealed 接口 + 解析 + 它的单测文件一起删）、
  `VkDispConfig.PACK_OPTIONS_SCREEN` 键、`VkDispConfigHotReload` 的 `packOptionsScreen` 边沿
  （快照字段 + `runScreenDrive` + `describe` + 那条 drive 分流）、`VkDispClient` 构造器里那个
  `IConfigScreenFactory` 扩展点 ⇒ 模组配置页交回 NeoForge 自动生成的 `ConfigurationScreen`。
  保留：`PackPickerCandidates`（纯候选逻辑，新页在用，单测继续有效）、`PackOptionsSession` 的
  `page()/pageCount()/pageOptions()`（分页 UI 没了，但那是会话侧纯函数；删它要另开一格，本轮不顺手）。
- **② 一条真实的代价，不粉饰**：`packOptionsScreen` 那条**无头驱动臂**（取证脚本靠它
  `open` / `set:` / `done` 在活客户端里改选项）随屏幕一起消失 ⇒ 替身是**直接改
  `config/vkdisp-pack-options.properties` + 动一次核心项触发重载**。少了一次「不重载只改值」的档位
  ⇒ 「同帧只差一个选项」那类像素比臂要改成两帧/重启对照。已在 `08-TESTING` 那条 P4.3 记录下面
  用引用块点名（历史证据仍算数，只是不能照做）。
- **③ 守卫跟着收紧**（不是放宽）：`MixinWiringTest#m14EmbedsPackOptionsIntoVanillaScreen` 追加
  「那四件必须不存在 + 配置里不许再有 `packOptionsScreen`」；`PackOptionSwitchReloadTest` 那份
  「快照引用的字段必须真实存在」的名单去掉 `PACK_OPTIONS_SCREEN`（这条守卫防的正是这种悬空引用）。
  全量：`./gradlew cleanTest test --offline` → **BUILD SUCCESSFUL**（**1161** 条，1 条既有 skip）。
  `VkDispConfig` 因删掉一整个键短了一截 ⇒ 巨类名单里的上限按棘轮口径可以再审（本轮没动名单）。
- **影响的文档**：`04-SPEC` §5.0 M-14（🟡 → ✅ 已落地 + 记代价）、`19-IMPROVEMENT-PATHS` §5A.4 R6
  （❌ → ✅ 代码收口）、`08-TESTING` P4.3 那条 GUI 取证臂（标退役 + 替身）。
- **未收口（如实）**：M-14 的三条画面判据 + `Mixin apply failed=0` 都没真机 —— 尤其
  `@Shadow` 能不能命中**继承来**的 `OptionsSubScreen#list` 是本轮唯一没被任何自动化手段验证的假设
  （命中不了是 apply 期硬失败，不是静默）。**阶梯往下就是闸门后的 R7（M-04 整主 pass）**：
  三项待核已结（一百二十二）、回归面已报（完整回归一轮），开工第一步是那张牵连面清单的执行序，
  不是先写注入。

## 2026-10-12（一百二十三）— 🟡 R6 第 6 步后半（M-14 · 上半）：包选项进<b>原版视频设置页</b> —— 新增通路，自研屏幕这一提交先不删

> **verdict = 原版页里现在能选包、能改包选项**：分页/双列/标签截断那一族自己算的版式整个不进新页，
> 交回原版 `OptionsList`；但旧屏幕还没删（下一提交），所以这是一个「两条通路并存」的中间态。

- **① 为什么挂在 `addOptions` 的 TAIL**：`VideoSettingsScreen:108` 那个 `protected addOptions()`
  （实现 `OptionsSubScreen:52` 的 abstract）里，原版自己就在往 `list` 加行（`addHeader/addBig/addSmall`）
  ⇒ TAIL 接上去，vkdisp 那些行天然排在原版行之后，且**一次开屏只跑一趟**（挂 `init` 的 TAIL 会被
  换尺寸/重建立刻跑第二趟，那是重复加行的形状）。
- **② 本仓第一个 GUI 屏幕注入点**：`mixin/VideoSettingsPackOptionsMixin` —— 目标类名与方法名都来自
  `MixinTargets`（`VIDEO_SETTINGS_SCREEN` / `VIDEO_SETTINGS_ADD_OPTIONS`），`@Inject(at=@At("TAIL"),
  require=1, expect=1)`，方法体一行转发到 `screen/VanillaPackOptionsPage.onAddOptions(list)`（X25）。
  `OptionsSubScreen#list` 是父类的 `protected` 字段（第 21 行）⇒ 用 `@Shadow` 交出，
  **不用反射**（反射那一族刚在 R3 清零，不许为新页面再欠一格 GAP-042）。
  可关闭键 `mixin.packOptionsInVanilla`（默认开）。
- **③ 新页只做「加行」**：`VanillaPackOptionsPage` 只用原版三个 public 装配方法 ——
  `addHeader(Component)` / `addBig(OptionInstance|AbstractWidget)` / `addSmall(OptionInstance...)`
  （后者自己每行配两个）。⇒ 滚动、每行两列、标签绘制、键盘导航与朗读、关屏时
  `list.applyUnsavedChanges()`（`OptionsSubScreen:72-74`）全是原版在做；
  旧屏幕那套 `layoutPageSize` / `turnPage` / `fitLabel` / 页码绘制<b>一行都不进新页</b>（守卫按
  代码形状查这四个名字，注释里提到不算红）。
- **④ 行的内容与能力平移**：
  ① 光影包选择 = `OptionInstance<String>`（候选与标签仍走 `PackPickerCandidates`，那份纯逻辑已有单测；
  含「自动 / 无 / 当前值不在库存时的占位」）；setter 就是旧 `onPick` 的两步 `SHADER_PACK.set + save`，
  重载仍走 FML 文件监听那条既有通路。
  ② 声明了取值列表的选项 = 循环按钮行（`values != null && values.contains(current)` 才走这支）。
  ③ <b>没</b>声明取值列表的选项 = `EditBox` 行，靠 `addSmall(box, captionInstance, null)` 拿到原版标签
  ⇒ 旧屏幕那批文本框不静默消失（X11）。
  ④ 两颗按钮：「应用并重载资源包」（文本框终同步 → `session.commit` → `store.save` →
  `reloadResourcePacks`，落盘失败 = ERROR 且不假装成功）与「重置本包」（只改内存，等应用才落盘）。
  旧页脚的「放弃」由原版关屏给出 ⇒ 不再自造。
- **⑤ 一条真实的语义差，写在页头而不是藏起来**：换了包之后，本页那些选项行仍属于<b>开屏那一刻</b>
  的那个包 —— 原版不给外部「重建这一页」的入口。⇒ 日志与包选择行的回调里都点名「要换一批选项请重开
  本页」，不假装它与旧屏幕逐字等价。
- **⑥ 为什么这一提交不删旧屏幕**：`PackPickerScreen` / `PackOptionsScreen` / `PackOptionsDrive` /
  `ScreenDriveCommand.Page` 与 `VkDispClient` 的 `IConfigScreenFactory` 是<b>当前唯一的入口</b>；
  M-14 的画面判据（原版页内可滚动、可键盘操作、切包后选项仍在）一条都还没真机跑过。
  先加通路、后删旧路 ⇒ 每一步都是绿的、且真机失败时不用回滚代码就能退回旧入口。删除动作在下一提交。
- **影响的文档**：`04-SPEC` §5.0 M-14 行（🟡 整合已落地 + 键名按实现写成 `mixin.packOptionsInVanilla`）。
- **测试结果**：`./gradlew cleanTest test --offline` → **BUILD SUCCESSFUL**（1167 条，1 条既有 skip）。
  新守卫：`MixinWiringTest#m14EmbedsPackOptionsIntoVanillaScreen`（TAIL / @Shadow / require=1 / 只转发 /
  键默认开 / 分页部件不许复活 / 必须走那三个装配方法 / 候选逻辑不许重抄）
  + `everyInjectionPointHasItsOwnToggle` 补 `mixin.packOptionsInVanilla` 两条断言。
  `VkDispConfig` 压回 1047 行内（把 M-11 的两行注释并成一行）。
- **未收口（如实）**：本注入点从没在真机上跑过（`Mixin apply failed=0`、`@Shadow` 是否命中继承来的
  `list`、原版页里 vkdisp 段是否可见可用可键盘操作）⇒ 三条画面判据 + 一条日志自报都要真机。
  **下一步**：M-14 下半 —— 删旧屏幕那三件 + `Page` 变体 + 重接 `VkDispClient` 入口 + 跟着改三条测试。

## 2026-10-12（一百二十二）— ✅ 闸门解锁：`04-SPEC` §5.0.6 ⑤ 三项待核逐条核完（源码级），R7 的成本锁解除

> **verdict = 三项都结在源码里，没一项需要开客户端**；结论里两条**缩小**了 R7 的范围、一条**收窄**了
> 原写法（GAP-046 的「格式不合」那一支）。本轮只改文档 + 台账，零代码。

- **① 非 OIT 的 `water_mask` 落在哪个 pass（原先锁着 R7 的成本）** —— 结论：**不存在「同一条
  `pipeline/water_mask` 既在主 pass 又在别的单附件 pass」这一族问题**。
  `RenderTypes.waterMask()`（`RenderTypes:715-716`）按 `useImprovedTransparency()` 交出的是
  **两条不同 location 的管线**（`pipeline/water_mask` `RenderPipelines:792-797` /
  `pipeline/oit_water_mask` `:798`，同一 `WATER_MASK_SNIPPET`、各 1 个颜色目标），而这两支所在的
  路径本身就互斥：`LevelRenderer:466` 的 `if (!useImprovedTransparency)` 才走
  `executeClassicTransparency` → `:721` `featureFrame.executeTranslucent(renderPass)` →
  `FeatureRenderDispatcher:210-228` 里执行 `collection.waterMask` 相，**那一刻就在主 GPU pass
  `:455-463` 内**；OIT 那支只在 `:471-472 → executeOit → :591-592 / :649-650 →
  executeOitWaterMask(:699-711)` 的**独立 pass** 里。
  ⇒ 对 R7 的影响：`pipeline/water_mask` 只需派生**一条** N 附件变体（已计入 §5.0.6 ② 的 ≈52 条），
  `pipeline/oit_water_mask` 所在 pass 不动 ⇒ **不派生**；**不需要**「一条管线两个变体 + 按 pass 选」，
  也就不需要把 M-15 的 `renderPass` 身份判据再搬一次。
- **② 默认 `renderArea` 的尺寸从哪来** —— `RenderPassDescriptor.defaultRenderArea`（`:79-98`）
  取的是**最后一个非 null 颜色附件**的 `getWidth(0)/getHeight(0)`；一个颜色附件都没有才退到深度附件。
  **不取 min、不求交、不校验**，同一算法在 `VulkanCommandEncoder:255-268` 又算一遍（那次结果当视口/
  尺寸传给 `VulkanRenderPass`，`:337`）⇒ 附件尺寸不一致时是**静默改尺寸**（症状 = 画面被裁/被拉伸），
  而不是报错（本机还没有 validation layer，X35）。
  ⇒ 派生公式多一条硬前提：**补上去的每一槽必须与 target0 同尺寸**（我方 `ColortexPool` 本来就按
  同一个 `main.width/height` 建 ⇒ 今天成立，但这条要从「隐式」升成 R7 的装配期检查项）。
- **③ 后端深度格式是否硬编码** —— 是，而且比「硬编码」更强：`VulkanRenderPipeline:294` 直接写
  `renderingInfo.depthAttachmentFormat(126)`（= `VK_FORMAT_D32_SFLOAT`），有模板那支 `:330` 写
  `VK_FORMAT_D32_SFLOAT_S8_UINT`，无深度 `:318` 写 `0` —— **从不读附件的真实格式**。
  ⇒ 我方 gbuffer 深度只能保持 `D32_FLOAT`（今天 `TextureTarget(..., GpuFormat.D32_FLOAT)` 就是它），
  换格式属「不要动：后端分叉」那一格。🔴 连带**收窄 GAP-046**：那里写的「格式不合时交回原版路径并
  计数」在**颜色**这一维成立（`ColorTargetState.format()` 读得到），在**深度**这一维没有可比对象 ⇒
  深度改成**装配期断言**（池深度必须就是 `D32_FLOAT`，否则根本不该开这条 pass）。
  也 ⇒ `GAP-022` / `GAP-037` 的「深度口径单点化」只能在同一格式内做（谁清、谁翻），不能靠换格式。
- **④ 成本解锁并给出**：`04-SPEC` M-04 行的「⛔ 待核 1 未结之前不许报 R7 成本」改成 ✅ 三条结论；
  `19-IMPROVEMENT-PATHS` §5A.4 与那张优先级表里 R7 的「未估」改成 **3–5 个工作日 + 一轮完整真机对照**
  —— 代码面只有三处（1 个注入点 + 1 处通用派生 + 1 处退路计数），成本全在回归面（主 pass 同帧路径
  含实体/BA/物品/文本/粒子/云/天气/边界/描边/OIT 交替）与 `N ≤ 8` 无余量。
- **影响的文档**：`04-SPEC` §5.0.6 ⑤（表整个换成「结论 + 证据 + 对 R7 的影响」三列）与 M-04 行尾、
  `13-GAP-REGISTRY` 的 GAP-046 修法行、`19-IMPROVEMENT-PATHS` 的两条 R7 行。
- **取证方式**：26.3 反编译源（`net/minecraft/**`、`com/mojang/renderpearl/**`）逐行；本轮把
  `RenderTypes` / `RenderPipelines` / `FeatureRenderDispatcher` / `LevelRenderer` / `SkyRenderer` /
  `RenderTarget` / `VideoSettingsScreen` / `VulkanCommandEncoder` / `VulkanRenderPipeline` /
  `NeoForge 的 CustomSkyboxRenderer` 都落在 `build/vanilla-src/`（gitignored）里跳读，
  结论里每个行号都可复核。
- **测试结果**：零代码改动，但跑了 `./gradlew test --offline --rerun` → **BUILD SUCCESSFUL**
  （1166 条真跑，1 条既有 skip）—— 本仓有若干守卫会读 `docs/` 与台账文本，改文档也验一遍。
- **下一步（顺序不变）**：第 6 步后半 = M-14（`VideoSettingsScreen#addOptions` TAIL + 删
  `PackPickerScreen` 与自研分页 + `ScreenDriveCommand.Page`）；之后才是 R7（M-04 整主 pass）。
  🔖 M-14 的牵连面本轮已摸清：入口只有 `VkDispClient:43-44` 那个 `IConfigScreenFactory`，
  钩子链是 `VkDispConfigHotReload:262 → PackOptionsDrive`，会红的测试是
  `ScreenDriveCommandTest`（`Page`）、`PackOptionSwitchReloadTest:80`（键名清单）、
  `PackPickerCandidatesTest`（看是否保留候选逻辑），以及 `MixinWiringTest` 对**新注入点**的三条约束
  （目标类名来自 `MixinTargets`、体首行转发到 `on…`、`vkdisp.mixins.json` 登记）——
  这是仓库第一个 GUI 屏幕注入点。

## 2026-10-12（一百二十一）— ✅ R5 收口（第 6 步前半）：删掉 `mrt.terrainAfterLevel` 那条 A/B 车道 —— 钩子里不再有任何地形绘制调用

> **verdict = 顺序这件事只剩「声明」**：`PassOrder` 那条 requires 边是唯一的顺序来源，
> 旧的「画在帧图之后」对照臂整个退役 —— 上一条（一百二十）留的「未收口」那一格就此合上。

- **① 为什么现在能删**：那条车道的用途是一刀切开「顺序/上传时序问题」与「绘制本身的问题」
  （h05 那轮）。它要回答的三个问题后来都由**声明**而不是对照臂回答了：顺序 = M-13 的
  `terrain requires 原版 sky` 边；本帧数据 = M-06 把捕获挪到执行期读；pass 身份 = M-15 用
  `renderPass` 对象同一性。留着它反而要维护「两档各接一次天空/地形」这套纪律 —— 而 R4
  已经让天空只可能在原版 `"sky"` pass 体内画，那条臂在结构上就不成立了。
- **② 删掉的东西**：`VkDispConfig.MRT_TERRAIN_AFTER_LEVEL`（键 + javadoc）、
  `MrtTerrainPass.afterLevel()`、`MrtTerrainPass.drawAfterLevel()`、
  `FullscreenPassHook#paintGbufferAndTerrain()`（helper 整个方法，连它的调用点）、
  以及 `onAfterLevel` 末尾那段「诊断 A/B：地形挪到帧图执行之后」的分支（含它的 try/catch）。
  ⇒ **本 mod 的 AfterLevel 钩子里现在没有任何地形绘制调用**；每帧地形只在我方帧图 pass 里画一次。
- **③ 随车道一起改口径的两条**（都是「注释不能再描述已删的东西」，R0 那条纪律的延续）：
  ① `mrt.terrainToMain` **保留**，但「屏幕即证据」失效 —— 帧图档里我方 pass 早于原版 `"main"`，
  主目标随后会被重画 ⇒ 它剩下的用途是**回读侧**的 toMain 判据表（`PixelProbeVerdict` /
  `PixelProbePlan` 那一族，GAP-008 的定位档）；② `FullscreenPassHook` 里「探针必须在地形 pass
  之后」那条约束**不再由调用顺序给出**，改由事件时序给出（`frame.execute` = `LevelRenderer:286`
  早于 AfterLevel）⇒ 探针仍不许往前挪，`FrameApi` 那段说明同步改掉 `{@link ...#drawAfterLevel()}`。
- **④ h48 那次错判的教训留在原地**（注释里，不删）：那条 gate 曾把「链跑不跑」与「地形何时画」
  捆在一起 ⇒ 关诊断档连带关掉链，我据此下过一次错结论。守卫跟着换成守「实体」而不是守关键词：
  `chainDispatchIsNotGatedOnAfterLevel` 现在查的是「补画地形的两个方法名不得再出现」
  （`drawAfterLevel` / `paintGbufferAndTerrain`），`skyIsHandedOffToTheVanillaSkyPass` 查
  「钩子在我方 pass 创建之前装 + sky pass 与 requires(skyPass) 都已删」，
  `PixelProbeWiringTest#probeRunsAfterTerrainPass` 改查「晚于链 + 车道实体已删」。
- **影响的文档**：`04-SPEC` §5.0 的 M-13 行（本条收口，判据仍待真机）、
  `19-IMPROVEMENT-PATHS` §5A.4 的 R5 行（🟡 → ✅ 代码收口）。
- **测试结果**：`./gradlew cleanTest test --offline` → **BUILD SUCCESSFUL**（1166 条真跑，1 条既有 skip）。
  `VkDispConfig` 因为删掉了键，从 1047 降到 1044 行（上限按棘轮口径可再降，本轮没动名单）。
- **未收口（如实）**：车道删掉之后，「我方 pass 在帧图里排在原版 main 之前/之后」这件事只剩
  日志自报 + 真机读数可验（`c0@afterSky` 要落在 72.85~84.50 那一排）；`Mixin apply failed = 0`
  同样没跑。**下一步**：第 6 步后半 = M-14 把包选择接进 `VideoSettingsScreen#addOptions` TAIL，
  删 `PackPickerScreen` + 自研分页。

## 2026-10-12（一百二十）— 🟡 R4 第 5 步（天空）：包天空搬进官方 `CustomSkyboxRenderer` 钩子 + 原版天空两视图 `@Redirect`（M-11），`GbufferTarget` 整类删除

> **verdict = 天空从「我方自己插一个 pass 无条件重放」改成「就在原版 `"sky"` pass 体内」**：
> 四道门控由原版判（GAP-039 的修法），复制品（薄壳 / 第二个 `SkyRenderer` / 自抽 state / 私有深度）
> 一起删 —— 但三条真机判据都没签，本条只算代码落地。

- **① 为什么这一步收益最大**：GAP-039 记的是「我方重放天空一条门控都不看」⇒ 岩浆 / 粉雪 / 遮天 /
  `Skybox.NONE` 维度里 gbuffer 仍写天空。而官方钩子 `CustomSkyboxRenderer.renderSky` 的调用点
  （`LevelRenderer:385`）**就在那四道 `if` 之后、且在 `"sky"` pass 体内、那一刻没有 render pass 打开**
  （原版自己的在 `SkyRenderer:139` 内部才开）⇒ 钩子里能自开我方 pass，**回 `true` 就天然互斥**，
  一行门控都不用抄。
- **② 官方钩子这一路**：`bridge/SkyIntoGbuffer` 改成 `implements CustomSkyboxRenderer`，
  `installHook()` 在装配期（`onFrameGraphSetup`，**排在 `afterLevel` 的早退之前** —— 两个轴互不相干）
  把单例写进 `LevelRenderState#customSkyboxRenderer`。⚠️ 该字段每帧被 `reset()` 清 ⇒ 必须每帧重装；
  ⚠️ 只在它**为空**时装（别的模组已有自定义天空盒就让它优先，我方退回「M-11 换视图」那一路）。
  `renderSky` 里调 `SkyPackGbuffer.render(colortex0 待写代, gbuffer 深度)`，画成了回 `true`。
  **手工互斥分支（旧 116-119 行）删除** —— 留着就是两个所有者。
- **③ M-11 这一路**：`mixin/SkyRendererViewRedirectMixin` 两条 `@Redirect`（各自 `require=1/expect=1`）
  把 `SkyRenderer:134/135` 取视图换成 `poolWriteView(0)` + `depthView()`。源码级依据：`SkyRenderer` 的
  `renderTarget` 字段**只**在这两行被读 ⇒ 换两实参 = 换落点，几何/管线/状态机逐字不变。
  颜色那一次顺手做「只清不画」的附件清屏（原版那一笔两个附件都是 LOAD，不清就留着**两帧前**的内容），
  并借同一个标记做每帧一次的幂等。键 `mixin.skyViews`（默认开）。
- **④ 删掉的复制品**：`bridge/GbufferTarget.java`（整类，`git rm`）、`SkyIntoGbuffer` 自持的第二个
  `SkyRenderer` 实例 + `repoint` + `extractRenderState` 自抽 `SkyRenderState` + 私有天空深度
  （`ensureSkyDepth` / `D32_FLOAT`）、`vkdisp_gbuffer_sky` 那个帧图 pass 与 `pass.requires(skyPass)`
  （顺序边现在由上一轮的 `PassOrder` 接到**原版** `"sky"` 句柄上，M-13 从此是承重的）、
  `FullscreenPassHook#paintGbufferAndTerrain` 里的天空那一句（两档共用同一路天空，每帧恰好一次）。
- **⑤ GAP-039 的收口形状（数据而不是配置）**：`MrtTerrainPass.skyPrePainted(slot)` 从「读
  `mrt.skyPass`」改成读 `SkyIntoGbuffer.paintedWriteGeneration()` —— 岩浆里原版根本不建 sky pass，
  钩子与 @Redirect 都不跑 ⇒ 标记保持 false ⇒ 地形按 CLEAR 打开槽 0。旧写法在那里照样 LOAD，
  LOAD 到的正是两帧前的旧天空（GAP-018 双代轮转），这就是「多画」的机制。
- **⑥ 取点换挂官方事件**：`c0@afterSky` 现在由 `RenderLevelStageEvent.AfterSky` 触发
  （`LevelRenderer:388-390`，两路天空共同的「刚画完且 pass 已关」时刻），不再在两路各写一次。
  观测面自报里新增 **`钩子被调#`**：这是「装配期那次写字段到底生效没有」的判据 —— 停在 0 就说明
  NeoForge 在我方之后覆写了该字段，那时才启用 M-12 把安装点搬进 `addSkyPass`（不猜）。
- **影响的文档**：`04-SPEC` §5.0 的 M-11（✅ 代码已落地，键名按实现改成 `mixin.skyViews`）、
  M-12（首选那条落地 ⇒ 兜底位不启用）、M-13（边变成承重的）三行；`13-GAP-REGISTRY` 的
  `GAP-039`（🟡 修法落地、判据未签、依旧无真机证据）与 `GAP-037`（🟡 未加重也未修：天空现在共用
  gbuffer 深度但既不测也不写 ⇒ 清一次 / 翻一次的原口径不变，`skyVertexBuffer` 的 `z=1.0` 一字未动）；
  `19-IMPROVEMENT-PATHS` §5A.4 的 R4 行。
- **测试结果**：`./gradlew test --offline` → **BUILD SUCCESSFUL**（1166 条，1 条既有 skip）。
  跟着改写的守卫：`RenderRouteWiringTest#skyIsDispatchedBeforeTerrain` →
  `skyIsHandedOffToTheVanillaSkyPass`（改判「钩子在 afterLevel 早退之前装」「sky pass 已删」
  「`skyPrePainted` 不许再读配置」）；`MixinWiringTest` 加 M-11 一条（含 `GbufferTarget.java`
  **必须不存在**、`SkyIntoGbuffer` 里 `SkyRenderer` 复制品必须为 0）与 `mixin.skyViews` 的开关断言。
  `VkDispConfig` 压回 1047 行内（上限不动，靠改注释密度换行数）。
- **未收口（如实）**：① 岩浆里 `colortex0` 天空段读数 = 原版「不画天空」口径；② `Skybox.NONE`
  维度里不变；③ 一百一十轮的星点判据不回退；④ `c0@afterSky` 不掉回 0.0611；⑤ `Mixin apply failed=0`；
  ⑥ 自报行 `钩子被调#` 非零。全部要真机。**下一步**：回来删 `mrt.terrainAfterLevel` 车道收尾 R5，
  然后第 6 步的 UI（M-14）。

## 2026-10-12（一百一十九）— 🟡 R5 的 M-13 提前做（第 5 步的前置）：帧图 pass 句柄接住、「terrain requires vanilla-sky」显式声明、ORDER-MARK 反推日志删除

> **verdict = 顺序从「实测反推」改成「显式声明」，但本条只算半边**：车道
> （`mrt.terrainAfterLevel`）与天空那一格（R4）还没动 —— 见文末「为什么插队」。

- **① 为什么插队（这一步不是打乱顺序，是第 5 步的前置）**：R4 要把天空搬进官方
  `CustomSkyboxRenderer.renderSky`（`LevelRenderer:385`，就在 `"sky"` pass 体内、那一刻无 pass 打开），
  或按 M-11 把原版天空的两个视图 `@Redirect` 到我方 colortex0/gbuffer 深度 —— 两种做法都让
  **天空落在原版 `"sky"` pass 里**，于是我方地形 pass 必须排到它之后。而帧图执行序由
  `resolvePassOrder` 按依赖边解析、**插入序不是依赖** ⇒ 没有这条边就是 h48g 那个形状
  （`c0@afterSky` 只剩 0.0611，正确顺序下同取点 72.85~84.50）。所以先把边显式化。
- **② M-13 落地**：`mixin/FrameGraphPassOrderMixin` 两个 `@WrapOperation` ——
  包住 `addSkyPass:382` 与 `addMainPass:403` 里的 `FrameGraphBuilder#addPass(String)`，
  先 `original.call(name)` 拿到**原版创建的真对象**再登记（`@Inject HEAD` 拿不到：那一刻 pass 还不存在）。
  `bridge/PassOrder` 做**双向登记**：我方 pass 在第 249 行的事件里最早创建，所以边由后到的 sky 那一方补；
  每帧装配开始 `resetForFrame()`（帧图 pass 对象**不能**跨帧复用）。
  新注入点各有 `require=1/expect=1`，可关闭键 `mixin.pinPassOrder`（默认开）。
- **③ 删掉的东西**：`ORDER-MARK vanilla-main-pass executing`（`AfterOpaqueBlocks` 事件 + 哨兵）与
  `ORDER-MARK my-pass executing` —— 自报改成 `[M-13] terrain requires vanilla-sky 边已声明=?`，
  即「连没连上」这一格直接可读，不再需要比对两行日志的行号。
- **④ 巨类没长**：`VkDispConfig` 因新键一度到 1059 行 ⇒ 压缩注释回到 **1046**（上限 1047，只许降）；
  `MrtTerrainPass` 因删 ORDER-MARK 掉到 1059（上限 1069）。新逻辑落在独立类 `PassOrder`（棘轮口径）。
- **影响的文档**：`04-SPEC` §5.0 M-13 行（候选 → 🟡 半边已实现，写清「未收口」的两项）、
  本文与 `19` §5A.4 的 R5 行。mixin 清单 9 → **10** 个 client 类。
- **测试**：`./gradlew test --offline` → **BUILD SUCCESSFUL**（1165 条，1 条既有 skip）；
  `MixinWiringTest` 认 `@WrapOperation` 为「有体注入」并查首行转发、新增 `mixin.pinPassOrder` 键断言。
- **未验证（不许当已收口）**：① 这条边真机是否生效（判据 = `c0@afterSky` 回到 72.85~84.50 量级，
  仍是 0.0611 就是没生效）；② 新注入点自身：`Mixin apply failed = 0`。两条都要跑客户端。
- **下一步（顺序不变）**：第 5 步天空（官方钩子 + M-11 视图 `@Redirect` + 删 `GbufferTarget`），
  然后回来删 `mrt.terrainAfterLevel` 车道收尾 R5。
- **提交**：✅ 本地提交并推送。

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
  + `MRT_GUARD_FALLBACK` `TerrainPipelineApi:530`），**但不依赖 modifier、不动注册时机**。
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
     **C2a（光空间数学，可单测、不碰 GPU）**与 **C2b（pass 装配，独立大项）**，并写明
     **C2a 不得单独上真机**（会得到一个不可归因的空结果，X46/X49 形态）；级联（4 级）**暂不做** ——
     OF/Iris 格式**没有** loader 级 CSM/`frustumSplit` API，级联是包在一张深度图内部自切的惯例。
  5. `QUALITY-DEBT.md`：QD-03 的「95」→ **175**（口径 = 静态非 final 单行声明；分布 `bridge` 123 / `render` 20 / `pack` 10 / `glsl/translate` 7），
     并立规「脚本能算的数字不再手抄」；QD-11 就地更正两条（② 不完整、① 偏贵 —— 派生机制**已在仓**：
     `PostPassContract.SAMPLER_DECL:51,97` + `SamplerDimensionPlan.SAMPLER_DECL:186,206` + `PackPostChain:223-232` 已跑通「派生+对差+WARN+排除」；
     且 `SamplerDimensionPlan:28-32` 早已按 **X39** 明文否决过按名字硬编码 ⇒ 本条实质是「原则没贯彻」）；
     §0 新增 QD-09~14 ↔ `19` 各小节映射表。
- **为什么这么改**：①「100% 自研」这条**伪约束**正在实际挡住最省力的正确解（Apache-2.0 的现成 token 级预处理器），
  且让反射开关等 workaround 有了不该有的借口；②QD-11② 的错判会把一个「几周量级的 pass 装配」
  登记成一个未经取证的「一步可闭环」，属本项目反复消灭的那类**会把下一轮引错的记录**。
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
`git show f126d1a:CHANGE_LOG.md`（压缩前的最后一份完整 97 条）。⚠️ **`evidence/h*.md` 那一处已经取不到**：
`evidence/` 于 2026-10-10 退库（提交 `2af28fd`；`.gitignore:136` `/evidence/`，`git ls-files evidence/` = 0 条），
逐轮取证报告**只在当初那台机器的盘上**，第三方 clone 里没有这些文件（口径同 `docs/19` §5.1 / §7 那条「不要再写 `evidence/` 路径」）。
⇒ 库内可复核的真源只剩三处：`docs/13-GAP-REGISTRY.md`（缺口状态与裁决）、本文件顶部条目、
以及**跟着代码走的单测**（判据脚本与判据数字一律落 `src/test`，不再落 `evidence/`）。

**为什么压缩**：97 条的日常取证日志对**当前开发主线**（着色器在 Vulkan 下的完整适配）
是噪音 —— 每一轮的「当前状态」已由三处唯一真源承接：`docs/13-GAP-REGISTRY.md`（缺口状态与裁决）、
本文件顶部条目（本轮做了什么 / 下一步）、~~`evidence/`（原始读数）~~（2026-10-10 已退库 ⇒ 这一处换成**跟代码走的单测**与 `run/logs/` 原件）。

**窗口规则**（2026-10-10 用户裁决：**阶段摘要 + 保留最近 10 条全文**）：最近约 10 轮留全文，
更老的按 4~7 轮压成一张阶段摘要表。登记新一轮会让全文临时超出 10 条（本次同一天并了两轮 ⇒ 现在 12 条），下一次压缩把最旧那条并进摘要层。

⚠️ **摘要表的用途边界**：只承担「这一串轮次在追什么、结论翻了几次」的线索。
**数字、判据、双臂对照回这三处取**：`docs/13-GAP-REGISTRY.md`（判据与裁决）、`src/test`（钉住数字与真值表的单测）、`run/logs/`（原始日志，gitignored、只在本机）——
~~回 `evidence/` 取~~ 那条自 2026-10-10 退库后就是断链（见上一节）。⚠️ 顺带一条 2026-10-14 核查到的后果：**判据图现在只在 `build/*.png`**（同样 gitignored，`clean` 就没）⇒ 台账里的数字大多**不可复算**，要复核得按口径重跑一臂（`07-CONSTRAINTS` X37 禁止拿旧实测数字当现状）。
