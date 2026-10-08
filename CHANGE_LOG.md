# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---
---
---

## 2026-10-08（七十五）— 🔴🔴 接线补齐后的第一次真机取证，当场抓出两个真缺陷：一个是上一轮自己引入的，一个是「决策表全对、资源准备顺序错了」

> **verdict = 离线全绿 ≠ 接上了**：1066 条测试全过，而真机上 `noisetex` 每帧缺席 —— 顺序错了
> 证据：`docs/13-GAP-REGISTRY.md` GAP-023 / GAP-025 各加一行；`run/logs/latest.log` 逐字时间戳；修前/修后两臂对照

**本次改了什么**

1. **收尾上一轮的 GAP-023 ①**（未提交的 `DEPTH_SNAPSHOT_2D` 改绑 `ShadowStubs` 桩）并逐字核实其语义：
   桩是 1×1 `D32` 清到 0.0 = 本引擎反向 Z 的**远平面** = 「这一层此刻还没写过任何东西」，
   与 `depthtex1`（半透明后快照）在水自己正在画的这一刻**应有的真值**同口径；且**永不作附件**。
2. 🔴🔴 **缺陷①（本轮自己引入的）**：`ensureShadowStubs()` 只在 `mrt.shadowStubs=true` 时才 `init()`，
   而新的 `depthtex*` 分支**无条件**调 `ShadowStubs.depthView()`（它刻意不懒建）
   ⇒ **`mrt.shadowStubs=false` 那条 A/B 臂必然抛 `IllegalStateException`**。
   后果不是「多个异常」，而是**对照实验被换掉一个自变量**：那条臂本要复现 h25/h26 的「画面闪烁」，
   会退化成「地形整层不画」。修法 = 桩一律建，开关只决定**绑定**。
3. 🔴🔴 **缺陷②（更值得记：上一轮那条接线在真机上从未生效过）**：`PackTextures.ensureReady()` 全仓**只有一处**调用
   —— `FrameApi.java:1105`，而后处理链**排在地形 gbuffer pass 之后**
   ⇒ 地形 pass 消费 `noisetex` 时 `PackTextures.loaded` 必是空的 ⇒ 回落内置兜底，
   而内置兜底**在 render pass 打开期间新建 encoder** ⇒ 原版抛
   `Close the existing render pass before performing additional commands`。
   🔶 比「少绑一条」更糟：`builtinNoise` 停在 null ⇒ **每帧**重走同一条失败路径。
   **日志顺序本身就是证据**：修前 `18:34:26.318 [ERROR] builtin noisetex FAILED` 排在
   `18:34:26.356 custom texture loaded: noise 512x512` **之前**。
4. ✅ **按 A11 真机取证**（`run-client.sh`，真 Vulkan 后端，非 OpenGL 静默降级）：
   缺陷②修后 `builtin noisetex FAILED` **计数 1 → 0**，`custom texture loaded` 排到绑定之前，
   绑定自报行 `NOISE_2D=1` + `GAP-025 noisetex 用包声明的真值：texture.noise='tex/noise.png'`。
   GAP-023 ① 的自报行同步打出 `depthtex*=1x1 D32@0.0 桩（不是本 pass 附件，快照未实现）`。
5. **两条新守卫都做了红绿双向取证**（在 bug 版源码上确实红、在修好的源码上确实绿）。
   🔶 第一版守卫**是空的**：写成「多行字面量计数」，而本仓 `countCode` **逐行**匹配 ⇒ 恒 0 ⇒
   在 bug 版源码上照样 PASSED。改成**花括号深度**判定（init 那行不在任何 `if` 内）后才真正能抓。
   —— 又一次「守卫写了 ≠ 守卫会响」。

**为什么改**：A11 要求改主源码必须真机取证。不跑这一次，缺陷②会以「地形噪声是方块图集」的形式
长期留在产品里，而**单测永远是绿的**；缺陷①则会让专门复现 h25/h26 的那条 A/B 臂悄悄失去可比性。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-023 加缺陷①；GAP-025 加真机判据达成 + 根因②）。

**测试结果**：全量 **1066 / 0 失败 / 0 错误 / 0 skip**（1064 + 2）。

**是否已提交**：见本条目对应提交。

**⛔ 仍未完成**：① GAP-023 的 **②/③ 三张时刻快照**（blit）未做 ⇒ `depthtex*` 仍不含场景深度，
依赖 `z1 > z0` 的判据仍不可信；② GAP-025 **不关** —— 关闭条件里的另一半
（「没声明 noise 的包仍走内建并打一次性 WARN」）尚未在真机上验过；③ GAP-027 水的运行侧验收仍未做
（`mrt.packWater` 仍默认 false）；④ ⚠️ **行为变化要记**：`noisetex` 现在才真正绑上包图，
历史地形臂与之后的地形臂**不可直接比亮度**（这条自 4568258 起就成立，本轮才真正兑现）。

---
## 2026-10-08（七十四）— 🔑 顺藤摸到「实现了但没接上」的第二、三例：noisetex 一直绑的是方块图集

> **verdict = `SamplerDimensionPlan` 缺三个家族分支 ⇒ `noisetex` / `depthtex*` / `gaux*` 全落「图集占位」**
> 证据：`docs/13-GAP-REGISTRY.md` GAP-023 / GAP-025 的新增行；单测 4 条（含真 BSL 清单逐条落位）

**本次改了什么**

1. **推 GAP-027 接水时先撞上一堵墙**：水自带 `depthtex1`，而 gbuffer 绑定路径走
   `SamplerDimensionPlan` 的分类表 —— 该表**只认 4 类名字**（`texture_0` / `specular`+`normals` /
   `shadowtex*` / `shadowcolor0`），其余按声明类型落 `PLACEHOLDER_2D -> atlas`（**方块图集**）。
   ⇒ 水与地形的 `noisetex` / `depthtex*` / `gaux*` **全部绑的是方块图集**：
   类型对、内容全错、**一条错都不报**（本机无 validation layer）。
2. 🔴 **`noisetex` 是「实现了但没接上」的又一例**：GAP-025 的选源（`PackTextures.view`：
   包 `texture.noise` 优先 / 回退内置）**早就写好了**，但分类表里没有 `noisetex` 分支
   ⇒ 改了完全不生效。而 BSL **地形与水都声明 noisetex**（地形实测清单
   `[texture_0, noisetex, shadowtex0, shadowtex1, shadowcolor0]`）⇒ 这不是「接水才有」的问题。
3. 🔴 **同一语义两处表示**：链侧 `FrameApi` 把 `gaux*` 绑到 colortex（`gauxN = colortex(N+3)`），
   gbuffer 侧却归成图集占位 ⇒ 同一个名字在两条链上给不同答案（本项目反复吃过的那一族）。
4. **修法**（新增 3 个 `ViewKind` + 3 个绑定分支，**纯增量、零删除**）：
   - `NOISE_2D` → `PackTextures.view(name)`（接线 GAP-025）；
   - `DEPTH_SNAPSHOT_2D` → **按前缀**分派（与链侧 `startsWith("depthtex")` 同口径）；
     🔴 **分槽未实现** ⇒ 三名暂同源，但**一次性 WARN 自报**（不许静默 —— 否则取证者会把
     「`z1 > z0` 恒假」读成「水面没有半透明遮挡」）；
   - `GAUX_2D` → `gauxN → colortex(N+3)`，与链侧统一；解析失败 / 视图缺席**逐条点名**。
5. **单测 +4**：`noisetex` 不再落图集、`depthtex0..3` 与 `depthtex99` 前缀家族、`gaux1/2` 同口径、
   **真 BSL 地形 5 个与水 8 个清单逐条落到正确来源且零「不绑」**。

**为什么改**：这是接水的**正确性前置** —— 不修的话，打开 `mrt.packWater` 得到的是
「水面画出来了、判据全假」，比不接更难归因。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-023 / GAP-025 各加一行）。

**测试结果**：全量 **1063 / 0 失败 / 0 错误 / 1 skip**（1059 + 4）。

**是否已提交**：是。

**⛔ 仍未完成**：① 🔴 **本轮改了主源码**（`SamplerDimensionPlan` + `TerrainPipelineApi`），
按 A11 **必须跑 runClient 才能说完成 —— 未跑**（本机无车具脚本，无法自动进世界取证）；
② GAP-023 的分槽（三张时刻快照）仍未实现；③ 水的 `renderGroup(TRANSLUCENT)` 仍未接 ——
本轮的净效果是「接水时不会绑错」，**不是**「水已接上」；
④ ⚠️ **行为变化要记**：`noisetex` 从方块图集改成真噪声 ⇒ 历史地形臂与之后的地形臂
**不可直接比亮度**。

---

## 2026-10-08（七十三）— 红灯清零：守卫从「代码形状」搬回「性质」，并补上决策表的覆盖

> **verdict = 3 个红灯全是「实现正确、测试按旧结构写」—— 改动全在 `src/test/`，主源码零改动**
> 证据：本机复验 `1048 完成 / 3 失败` → 修后全量 `1059 / 0 失败 / 1 skip`

**本次改了什么**

1. **先摆判定依据，再动手**：三个旧字段名 `terrainSourceMemo` / `terrainAdapterMemo` /
   `terrainMemoKey` 在**主源码已零残留**（只活在测试里）；而逐程序落源
   （`GbufferArtifacts` 表 + `memoKeyFor() = currentTerrainMemoKey() + "|" + program`）
   是 GAP-027 的**必要结构**，回退它等于放弃接水 ⇒ **测试过时，不是实现漏改**。
2. **三条守卫搬家并加强**（不是放宽）：`takeTerrainSourceMemo()` / `takeTerrainAdapterMemo()`
   已退化为一行委托 ⇒ 守卫移到真正的单一真源 `takeSourceMemo(String)` / `takeAdapterMemo(String)`；
   新增「地形必须**经由**统一路径」（两份取走逻辑 = 两份会漂移的状态）；
   新增「键的算法只有一份」（`memoKeyFor` 必须复用 `currentTerrainMemoKey()` 且带程序名后缀）。
3. 🔴 **清空守卫改用缩进判据**（`indent >= 12` = 被 `if` 包住）：合法清空点有**两个**
   （键不符 ⇒ 整条作废 / A/B 开关），旧写法要求「必须恰好被 `MRT_GAP010_REGRESSION` 包住」，
   会把「键不符」这个**正确且必需**的分支判成违规 —— 守卫要求的是把实现改回去。
4. **记忆键清单补 `MRT_PACK_WATER_SHADER`**（GAP-027 新增的生成闸门，QD-08 第五例）：
   守卫要跟着新开关一起长，否则「加了开关但没进键」下次照样穿过去。
5. ✅ **补上声明了却缺失的覆盖**：`GbufferProgramPlan` 的 javadoc 自述「零原版类型依赖 ⇒
   可单测」，但在 `src/test/` 里曾**零引用** ⇒ 新增 `GbufferProgramPlanTest` **11 条**
   （路径/后缀纯函数、**水关着时三层行为逐字不变**、只有真挂水的层才关写深度、
   附件数取 max、自报行三要素、`Entry.wired` 语义）。

**为什么改**：`abbe786` 是子代理触轮次上限后的**备份提交**，自带 3 个红灯；不修则任何后续
改动的测试基线都不可信（红灯会被当成"已知噪声"忽略）。

**影响的文档**：本文件、`docs/QUALITY-DEBT.md` 的 QD-08 相关判据（守卫形态变化）；
测试文件 3 个（`VirtualPackMemoKeyTest`、`PackTerrainMemoTakeTest` 改，`GbufferProgramPlanTest` 新增）。

**测试结果**：全量 **1059 / 0 失败 / 0 错误 / 1 skip**（1048 + 11）。主源码零改动。

**是否已提交**：是。

**⛔ 仍未完成**：① `abbe786` 的**水接线运行侧验收仍未做**（水有没有画出来 / 附件数变几个，
全未知）—— 本次只改测试、不改变产品行为，故不触发 A11「必须跑 runClient」；
② GAP-023（depthtex 分槽）/ GAP-020（坏 mip 整帧黑）/ GAP-011（闪烁）状态不变。

---

## 2026-10-08（七十二）— 🔑 GAP-022 接线时被抓出我一个错结论；GAP-027 起步（水）；注入器一个真 bug

> **verdict = 矩阵那一半「推对了但接不了」—— 同一个 uniform 也喂顶点阶段；水的契约与前置依赖实测清楚**
> 证据：`evidence/h48w-gap022-real-matrices.md`、`evidence/h48-flicker-and-readback.md` §二十六/§二十七

**做了什么**

1. **GAP-027 第一步（已提交推送）**：`PackTerrainSource` 选源**参数化**（5 参 `generate`，
   4 参版委托 ⇒ 选包/门控/选项覆盖/编译/槽位兑现五处口径全部复用同一条链），
   `MrtPlan` 的冻结契约从「单程序」改成「**多程序取 max/并集**」（`freezePackPrograms`，仍一次 volatile 写入）。
   `slotRefusal` 顺手改成按被选中的限定名精确匹配（按后缀匹配会把没入选维度的拒绝理由算到本次头上 ⇒ 误拒）。
2. **实测水契约**（真 BSL 默认档，钉成单测）：**2 输出 / 槽 `[0,1]` / 8 sampler（含 `depthtex1`、`gaux1`、`gaux2`）/ 14 varying**，
   对照地形 1 输出 / 5 sampler / 9 varying。并证明顶点适配层**按每条程序各自**产出（14 条逐位置逐名字对齐、
   文本与地形不同）⇒ 接水不含顶点侧未知数。🔴 同时定下一条依赖：**水要 `depthtex1` ⇒ GAP-023 从「以后再说」变成接水前置**，
   并已把分槽机制写进登记表（同一张深度在三个时刻的两次 blit 快照 + usage/代次三条约束）。
3. **GAP-022 矩阵那一半接线落地**（子代理实现，我逐条核实）：`DepthConventionPair` 成对出口 + 13 条离线单测，
   历史槽只存**引擎口径**、翻只发生在出口 ⇒ 「上一帧被翻几次」机械化答案是恰好一次（`D2` 不是对合）。
   开关 `mrt.depthGlProxy` 仍默认 **false**；OFF 态逐字节不变。核实：包里**没有** `gbufferPreviousProjectionInverse`
   声明 ⇒ 未新增（不无据改动 OFF 态字节）。
4. 🔴 **接线时抓出我上一轮的一个错结论（这条比接线本身重要）**：`gbufferProjection` **同时是顶点阶段的投影矩阵**
   （转译终稿逐字 `gl_Position = gbufferProjection * gbufferModelView * position;`，
   `build/bench-golden/.../world0_gbuffers_terrain.vsh.trans.glsl:450`）。本前端设备深度值域是 `[0,1]`
   （`isZZeroToOne=true`），**没有 GL 那步 `(ndc+1)/2` 视口映射** ⇒ 喂 `D2·P` 会让顶点产出 `[-1,1]` 的 clip.z、
   光栅化深度越界。我 h48w §3.1 把「GL 视口的事实」当成了「这条 Vulkan 路径的事实」。
   数学部分（差 3.0e−9、逆配对、包口径往返）**仍然全对**，错在「所以可以换这一个 uniform」那一步。
   ⇒ 真正的修法是**按程序族分别供值**（`gbuffers_*` 留引擎口径、`composite*/deferred*` 给 GL 口径，
   已核实这五个片元文件确实读它）。已把 `mrt.depthGlProxy` 的用户可见 comment 从「只开这一半是半真半假」
   改成说清「两半都开反而会弄坏顶点」—— 旧文案会误导出**反方向**的错。
5. **GAP-026 修法②闸门首次真跑并通过**（`档位核验通过：STORE_RESIDUE_NONE`）。
   并记一条工具层事实：**别改正在被运行的脚本** —— 上一轮我在臂运行中插了 28 行，
   bash 按字节偏移续读 ⇒ 新闸门**整段被安静跳过**；那次运行的「无残留」是产品打的、不是闸门判的。
6. **注入器一个确定的 bug（已修，但不足以解释症状）**：`x11_input.py` 的 `key_for()` 只返回 keycode、
   丢掉命中的是第几层 ⇒ 要按 Shift 的字符全打成该键下层：`/`→`7`、`#`→`3`
   ⇒ **每一条以 `/` 开头的注入命令从来没生效过**（与 §二十.1 的「前导字符被吃掉」是两个不同的错：
   丢字符 vs 换字符，这也解释了为什么加垫子救不回来）。离线证明 + 已修。
   🔴 但修完再跑一臂，`clockTime` 仍从 23092 自己走到 25221 并跨过 24000 进新一天 ⇒ **命令还是没执行**。
   剩下候选未查（F2 能落地 ⇒ 「键完全进不去」不成立）。
   ⇒ **判读规矩立刻生效**：「观测面钉成正午+晴天」至今没有一次臂真正成立 ⇒ 跨臂比亮度必须带
   「两臂自报时刻不同」这个已知混杂量；可用的是①同一臂内做对照 ②用「是否为 0」而非「亮度高低」判有没有画面。
7. **更正 `04-SPEC:161`**：那句「链模式下上一次 gather 就等于上一帧」不成立 ——
   `gather` 一帧内最多被调 **4 次**（`FrameApi:801/:1110`、`TerrainPipelineApi:550/:620`）而历史每次 gather 轮一次
   ⇒ 同帧内第 2..4 次的「上一帧」就是本帧 ⇒ `previous == current` ⇒ 运动向量恒 0，
   TAA/运动模糊/DOF 重投影退化成原地累积。**这与「此前恒 0」是方向相反的另一种错**，两个都得防。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-022 加 h48z 更正行与新的关闭条件、GAP-023 补「接水前置」与机制、
GAP-026 加修法②状态、新增 GAP-027 实测契约）、`docs/04-SPEC.md:161`、
`evidence/h48w-gap022-real-matrices.md`（新）、`evidence/h48-flicker-and-readback.md` §二十六/§二十七。

**测试结果**：`./gradlew test -PquickPlay` 全绿 **1041 条 / 0 失败**（本轮新增
`MrtPlanTest` 3 条、`PackTerrainSourceTest` 2 条水契约、`DepthConventionPairTest` 13 条）。
运行侧：h48y 回归闸证明 `MrtPlan` 重构对运行期**逐字无影响**
（`colorTargets=1 declaredOutputSlots=[0]`、门控 `kept=9 skipped=2`、240 帧 0 空帧、luma 中位 141.95）。

**是否已提交**：是（`feat(h48x/GAP-027) 第一步`、`test(h48x/GAP-027)`、`docs(h48x/GAP-023)`、
`docs(h48y/GAP-026)`、`feat(h48z/GAP-022)`、`docs(h48z)`），全部已推送、与 `origin/master` 同步。

**⛔ 仍未完成**：① 水**正在接**（子代理做 `VkDispVirtualPack` 多程序化 + TRANSLUCENT 管线 + `renderGroup(TRANSLUCENT)`），
   我未复核前不算数；② GAP-023 depthtex 分槽未实现（已是接水的正确性前置）；
   ③ GAP-022 需要「按程序族分别供值」才算真修好；④ `gbufferPrevious*` 需要真的帧身份；
   ⑤ 聊天注入仍未通（命令从不执行）；⑥ 8 附件档每 3 帧空一次机制未定位；⑦ 云/实体/手/shadow 未接。

---

## 2026-10-08（七十一）— 🔑 GAP-022 矩阵那一半**推出来了**（并撤掉一条拦住自己的假否证）+ 登记 GAP-027

> **verdict = 反向 Z 的矩阵换算已证到 3.0e−9 并钉成单测；非地形 gbuffers 第一次有了官方非 mixin 接法**
> 证据：`evidence/h48w-gap022-real-matrices.md`、`docs/13-GAP-REGISTRY.md` GAP-022 / **GAP-027**

**做了什么**

1. **给取证加一条自报行**：`OfUniformManager` 新增 `[GAP-022/matrix]`（`debugLog=true` 时按帧节流），
   把**真正喂给包的那一对矩阵** + 三个已知距离的窗口深度一起打进日志。
   登记表原本就要求「只能用游戏里真实的矩阵，不能用自己重构造的」—— 这条是照它做的。
2. 🔴 **先踩两个读数坑，而它们正是上一轮「矩阵翻不动」的全部来源**：
   ① 把日志里的 `rN` 当成数学**第 N 行** ⇒ 按行算 `d=1` 得 **20.0**，而同一行日志自己打的真实读数是
      **0.04995361**（这条自相矛盾才是判出来的）；按列重建后三个读数逐位复现、`Q·Q⁻¹=I` 差 5.4e−8。
   ② 比较基准的 far 用 512 ⇒ 由真实矩阵**反解出真 far = 1024.001**、near = 0.05；
      上一轮被当成「joml 的 `m32` 是 ±0.05 而不是 ±1」的那个 `0.05000244` **恰恰就是 near**。
3. ✅ **正确翻法 = 左乘 `D2`（第 2 行 `(0,0,−2,1)`，即 `row_z ← row_w − 2·row_z`）**，四项判据全过：
   与标准 GL **`[-1,1]`** 投影逐元素差 **3.0e−9**；窗口深度恰好 `1 − z_engine`；x/y NDC 一动不动；
   包自己 `depth*2−1 → P⁻¹` 的往返逐位回到 −1/−16/−128/−1024。
   🔖 上一轮的 `(0,0,−1,1)` **也没错**，只是产出 `[0,1]` 那份 —— 症状是反解距离**差整一倍**
   （d=1→−0.5002、16→−8.063）。这条区分现在有测试钉住，不会再被「看起来能翻」糊过去。
4. **删掉 `matrixFlipIsNotTheSameAsDepthFlip` 那条红灯守卫**：它拦的是一条正确的式子；
   矩阵判据整体迁到 `GlDepthConventionRealMatrixTest`（**先校验「重建 = 真实读数」，再判翻法**）。
   实现按「矩阵作用在基向量上的结果」读列改列，不手搭 `D2` 猜左右乘 —— 手搭版先错了（逆配对差 13 倍）。
5. **登记 GAP-027（非地形 `gbuffers_*` 全未接）**，并写进本轮查到的一条**结构性事实**：
   原版有官方、非 mixin 的单点换管线入口 —— `RenderSystem.getCompiledPipelineNullable`（`:106`，
   首条语句就是 `pipeline = PIPELINE_MODIFIERS.apply(pipeline);` —— **这两行本轮从 sources jar 逐字复核过**）
   + NeoForge `RegisterPipelineModifiersEvent` + `push/popPipelineModifier`
   （「全游戏 30 处取管线都过它」这个数字来自子代理统计、**本轮未独立复核**，登记时已标注）
   ⇒ 实体/手/天气/云这些不走 `ChunkSectionLayer` 的 draw，第一次有了不逐个加 mixin 的接法。
   三个硬约束一并登记（必须配平否则 `ClientHooks:863` 抛；modifier 要幂等且改 `location`；
   未知管线按需编译 ⇒ 派生多附件变体不需要预注册）。第一刀选 **water**（与地形同一个已证收口点），
   第二刀 **clouds**（`CloudRenderer.render(CloudStatus, RenderPass)` 是 public 且自收 RenderPass）。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-022 两行重写 + 撤回标记、新增 **GAP-027**）、
`evidence/h48w-gap022-real-matrices.md`（新）。

**测试结果**：`./gradlew test -PquickPlay` 全绿 **1023 条 / 0 失败**
（本轮新增 `GlDepthConventionRealMatrixTest` 4 条；此前 `ChainEnableGatingTest` 8 条 +
`BslChainGatingEvidenceTest` 4 条）。运行侧：GAP-024 判据达成（`kept=9 skipped=2`、链 `passes=9`）、
GAP-026 自报行已在产品链路出现。

**⛔ 仍未完成**：① **GAP-022 只推到、没接线** —— `D2` 那一对还没进 `OfUniformManager`/`DepthGlProxy`，
   且「半翻比不翻更坏」这条旧结论**没被推翻** ⇒ 接线必须深度+矩阵**同帧**，并要画面判据
   （`isSky = z==1.0` 站对边、光柱/镜斑/体积云像素位置对上）；② GAP-027 一条程序都没接；
   ③ 8 附件档每 3 帧空一次的机制未定位；④ GAP-026 修法 ②③、GAP-023 分槽未动。

**是否已提交**：是。

---

## 2026-10-08（七十）— 🔴 黑白闪屏的自变量查清：不是链、不是仪器，是**store 残留把 BSL 编成了 8 附件档**

> **verdict = 周期 3 的空帧只在「`ADVANCED_MATERIALS=true` ⇒ 8 附件」那一档出现；真默认档两臂各 240 帧 0 空帧**
> 证据：`evidence/h48-flicker-and-readback.md` §二十一～§二十四

**做了什么**

1. **h48q `readDelay` A/B 定量了仪器贡献**：`delay=1` ⇒ 空帧 **66.7%**（形态 `00N`，间隔 `{1,2}`）；
   `delay=3` ⇒ **33.3%**（`N0N`，间隔 `{3:79}`）。⇒ §十八那条「回调排空 ≠ GPU 完成」确实每个周期
   **多造一个零**，但**周期 3 的基体加余量加不掉**。🔖 两臂观测面其实不同
   （自报：d1 夜+雨、d3 拂晓+晴）⇒ **luma 不许跨臂比**，反过来也证明周期与画面内容无关。
2. **顺文档矛盾挖出真根因**：登记表一处说「BSL 默认只写 colortex0」、另一处说「8 附件因 `ADVANCED_MATERIALS`
   默认为真」。核实到底：`DefineProcessor.java:87-88` 只在 `strip().startsWith("#")` 时进指令分支 ⇒
   `//#define` **复活不了**；生产链路单测 `TerrainProductionOutputCountTest` 断言默认档**只 1 个输出**并跑绿。
   ⇒ 分歧不在预处理器，在**本地状态**：`config/vkdisp-pack-options.properties` 里残留
   `BSL_v10.1.8.ADVANCED_MATERIALS=true`，被 `ShaderPackCompiler` 正常改写进源
   （`选项覆盖已改写进源: 命中 3/3`）。取证脚本传 `pack.optionOverrides=""` 只清**配置档通道**、
   `capabilityGate=false` 让门控**不干预**，门控按裁决又**不碰 store** ⇒ 残留从 h45 起穿过所有 BSL 臂。
3. **两臂互证并收窄 GAP-020**：清残留后 `capabilityGate` **开**（h48r）与**关**（h48s）两臂
   都是 `colorTargets=1 declaredOutputSlots=[0]` + **0 空帧**（h48s luma `min 62.74 / med 143.21`）。
   ⇒ ① 闪屏与**门控默认值无关** ⇒ **不改** `pack.capabilityGate` 默认（无依据不动产品行为）；
      ② GAP-009 的「albedo≡0」**绑的是 `ADVANCED_MATERIALS` 那一条 `GetMaterials` 路径**，
         闭包里 `PARALLAX`/`SSS`/`REFLECTION_*` 在这台后端上不产生黑（此前两件事在登记表里混写）。
4. **GAP-026 登记 + 修 ①**：`PackOptionsSession.residueReport(...)` 在 store 回放后做**工作值 vs 基线**差分，
   有差异 ⇒ `WARN STORE_RESIDUE`（点名每键 + 给默认值），无差异 ⇒ `INFO STORE_RESIDUE_NONE`
   （**计数 0 也要打**；`store==null` 也打）。运行侧已看到该行（h48t 逐字：
   `选项 [STORE_RESIDUE_NONE] 包 'BSL_v10.1.8' 的持久化选项与基线完全一致（残留 0 项）`）。
5. **撤回到处标注**：登记表 GAP-008 `h44` 行「BSL 默认 8 附件」**作废**（②③④ 数字改挂「`ADVANCED_MATERIALS=on` 档」名下）；
   `evidence/h44` §五两行就地标 ⛔；代码/测试注释与断言文案把 `[0,3,6,7]/8` 一律改称**残留档**
   （`PixelProbePlanTest` 常量 `BSL_DEFAULT` → `RESIDUE_AM`，就是为了不许再把它读成默认）。
6. **GAP-024 落地：后处理链终于执行包自己写的 `program.*.enabled`**（`ChainEnableGating` 纯决策 +
   `ProgramEnableGate` 三值求值，门控点在 `PackPostChain.build` 候选循环里、**先于**槽位上限检查；
   新开关 `pack.chainEnableGating` **默认开** —— 与 `capabilityGate` 默认关相反且有意：
   这一刀执行的是包**自己**的声明，不执行才是违约）。运行侧逐字：
   `gating=on considered=11 switches=6 kept=9 skipped=2 skippedNames=[composite2, composite3] unresolved=[]`
   ＋ `[chain] post chain executed: passes=9 first=deferred last=final`
   ⇒ 登记表判据「passes 11→9 且点名跳过原因」达成。
7. 🔴 **接 GAP-024 时挖出并修掉一条老 bug**：首跑自报的是 `skipped=[composite1, composite2, composite3]`
   —— **光柱被砍**，而选项表同时写着 `LIGHT_SHAFT default=true`、包里 `settings.glsl:205` 是 `#define`。
   查下去是 `PackPostChain` 按名去重**先到先得**，而 BSL 枚举顺序是 `world-1 → world0 → world1`
   ⇒ 候选里留的是 world-1 那条 Program，可它的片源**永远不会被选中**（`selectFragment` 给非偏好维度打
   `MAX_VALUE`）；BSL 恰好给两个维度写了不同表达式（`world0=LIGHT_SHAFT` 真 /
   `world-1=LIGHT_SHAFT && MULTICOLORED_BLOCKLIGHT` 假）⇒ 门控按一个**根本不进链的维度**做了决定。
   修法 = 去重改用与取源**同一套** `chainDimensionRank`（单点真源）。
   🔖 **这条 bug 在 `enabled` 之前就已存在**（`blend`/`alphaTest` 一直取错维度），只是没有可观察后果
   ⇒ 教训形式：**「数据取错来源」这类 bug，只在有人真读那个字段的那天才暴露**。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-020 加 h48q/h48r/h48s 三行与「先自报档位」的关闭前提、
GAP-009 收紧、GAP-008 `h44` 行作废、新增 **GAP-026**）、`evidence/h48-flicker-and-readback.md` §二十一～二十四、
`evidence/h44-declared-slots-and-lane-aware-verdict.md`、`MrtPlan`/`PixelProbePlan`/`PackTerrainProgram`/
`TerrainPipelineApi`/`TargetReadback` 注释。

**测试结果**：`./gradlew test -PquickPlay` 全绿（含新增 2 条 GAP-026 断言）；
运行侧三臂各自报档位（h48r `命中 8/8 …=false`、h48s `CAPABILITY_GATE_OFF`、h48t `STORE_RESIDUE_NONE`），
契约行与单测值**逐项相同** ⇒ 运行期与单测第一次对上。

**⛔ 仍未完成**：① 「8 附件档为何每 3 帧空一次」机制**未定位**（`ADVANCED_MATERIALS` 是包内合法开关，
用户开了就该能用 ⇒ GAP-020 **不收口**，候选收窄为 GAP-018 多槽兑现 / 未写槽清屏×双代轮转，
下一刀 = 残留档下只差 `mrt.attachments 1 vs 8` 的 A/B）；② GAP-026 修法 ②（车具起臂前判无效）与
③（一次性覆盖，改产品行为需裁决）未做；③ 实体/水/云/shadow 等 `gbuffers_*` 仍未进 colortex
（GAP-003/015 —— h48t 画面判读里「天上没有云」就是这条）；④ GAP-022 矩阵那一半、GAP-023 分槽未动。

**⛔ 本轮写错又自己改掉的一条**（保留原文形状，不做静默删除）：本条目初稿写过
「**F2 注入连续第三轮没落地** ⇒ 闪屏是否消失尚未由独立通道判定」。**这是错的** ——
同一批里的 h48t 臂 **10 张 F2 全部落地**（`mean_luma 80.62~108.85`，黑帧形态是 `≈4.5`），
独立通道已经给出答案：**真默认档不闪屏**（`evidence/h48-flicker-and-readback.md` §二十五）。
剩下未查清的只是「同一套脚本为什么 h48q d1 全不中、h48t 全中」这条**工具层**问题
⇒ 它改变的是「下次没图时该怎么判」，不改变画面结论。
另记：GAP-024 的「门控会不会影响 3 帧周期空帧」这条 A/B 在**默认档是空转的**
（默认档本来就 0 空帧，没有可变化的量）⇒ 它只在 8 附件档有意义。

**是否已提交**：是（本轮五条：`docs+forensics(h48q/r/s)`、`feat(h48t/GAP-026)`、`evidence(h48t)`、
`feat(h48u/GAP-024)`、`fix(h48u/GAP-024)`）。推送遇网络抖动（`Failed to connect to github.com:443`
两次、`SSL_read unexpected eof` 一次），已成功推上去一部分；剩余提交待网络恢复补推，**内容不丢**。

---

## 2026-10-06（六十九）— 🔴 黑白闪屏定位链：`frameTime` 供值 + GAP-018 双代轮转 + 帧图外重放的坑

> **verdict = 闪屏有两个独立成因，都已处理；BSL 画面从「恒定白屏」变成可辨认的着色世界**
> 证据：`evidence/h48-flicker-and-readback.md`（逐臂数字 + F2 截图判据工具）

**做了什么**

1. **补 `frameTime` 供值**（`OfUniformManager` 取最近一次有效帧间隔，尖峰帧沿用旧值）。
   BSL `composite3:196` / `composite5:371,378` 用 `exp2(-frameTime × SPEED)` 当逐帧混合系数，
   恒 0 ⇒ 混合永远返回旧值 ⇒ 时序量（自动曝光/DOF/太阳可见度）卡在附件初始 0 ⇒
   `color /= 2×0 + 0.125` = 固定 ×8 ⇒ **此前那张「恒定白屏」的真身**。
2. **GAP-018：colortex 每槽双代轮转**（`ColortexPool` 两代纹理 + `poolWriteView` /
   `advanceWrittenSlots`；地形 pass 与链的每个 pass 写完翻代）。BSL 11 步里 **8 步读写集重叠**，
   旧实现把同一张图同时当颜色附件和采样器 = Vulkan UB。修后亮帧 **199.7 → 45.3**，画面内容出来。
3. **GAP-017 的机制数字**：新增 `mrt.pixelProbeMipLevels`（`copyTextureToBuffer` 第 5 参 =
   mipLevel）⇒ 直接读 `colortex0@m8 = (67,53,32)` vs mip0 均值 34.5 ⇒ **金字塔顶部真的是降采样平均值**。
4. **回读卫生**：每源两槽轮转 + 「两槽都在途 ⇒ 跳过并自报」+ 「收割延迟一个探针节拍」。
5. **闪屏判据换通道**：`tools/vulkan-local/h48_flicker_capture.sh` 用**游戏自己的 F2 截图**
   （+ 纯 stdlib PNG 解码算亮度）—— 不用异步回读自证回读。`x11_input.py` 加 `chat` 子命令
   注入 `/time set 6000`、`/weather clear` 钉死观测面（用户指出夜晚会误判）。
6. **GAP-019 逐帧自报**（`MrtTerrainPass` 每 120 帧一行：见过几个不同 draw 对象 / 捕获与重放是否 1:1）
   ⇒ 实测 `不同 draw 对象=120 个, 代次错配=0` ⇒ 「捕获对象被回收复用」这条假设**证伪**。

**为什么改**：目标要求 BSL 全部功能生效，而画面是黑白闪屏；先要能判读，再谈修。

**🔴 本轮内自我更正（写在这里而不是悄悄改掉）**：`FullscreenPassHook` 的链分派带了
`MrtTerrainPass.afterLevel()` 条件 ⇒ `terrainAfterLevel=false` 会连带把**整条链**关掉、
退回旧三步链。因此「闪屏消失」「45 luma 稳定画面」两条判据其实测的是**旧三步链**，
不代表链生效。已拆 gate（链是否跑只看 `chainActive`）并加 `[route]` 自报 +
`RenderRouteWiringTest` 接线守卫；更正后的链内实测是 `colortex0 28.5 → 5.6 → 0.0`
逐轮塌掉 ⇒  intermittency 在链内部，GAP-019 重新开放（详见 `evidence/h48-…` §五½/§五¾）。

**同轮补的供值**：`gbufferPreviousModelView` / `gbufferPreviousProjection` /
`previousCameraPosition` 从「恒 0」改为供上一帧真值（换世界时与当帧对齐），
04-SPEC §3.2 相应行从「不填充」挪到「填充」，并有 `RenderRouteWiringTest` 钉住不许回退。

**影响的文档**：`docs/13-GAP-REGISTRY.md`（GAP-017 收口数字、GAP-018 新增+部分关闭、GAP-019 新增并降级为观察项）、
`docs/04-SPEC.md` §3.2（`frameTime` 进「非目录填充」行，含白屏机制出处）、
`evidence/h48-flicker-and-readback.md`（新）。

**踩到并写进纪律的两件事**：① 为关聊天框盲打 Escape ⇒ 打开了**暂停菜单**，
拍出 10 张「Game Menu + 模糊世界」亮度稳定得像修好了 —— 整臂作废（自证不能污染被测量）；
② 定长 sleep 不是「进世界」的判据 ⇒ 改为轮询日志真信号 + 空结果显式报错。

**测试**：`./gradlew build` 绿（含新增 `PostChainBslTest.everyDeclaredOutputIsActuallyAssigned`
与改写后的 `PixelProbeWiringTest.oneBufferPerSource`）。运行期判据见上面截图序列。

**未做/下一步**：天空全黑（非地形 gbuffers 程序未接 = GAP-003/015 线）；`gbufferPrevious*`
与 `shadowFade/nightVision/timeBrightness` 等仍恒 0（已在 unfilled 自报里点名）；
提交待用户流程走到提交步（本轮按「先让 BSL 生效再测提交」执行）。

### 追加（同日第二轮）：NeoForge 升版 + 黑帧责任侧钉死（GAP-020）

### 追加（同日第三轮）：黑天空的入口打通（`mrt.skyPass`，默认仍关）

- **新增**（零 mixin、不需要 M-04）：`GbufferTarget`（`RenderTarget` 公开构造器不建纹理 + protected
  视图字段 ⇒ 薄壳指向我方 colortex0/深度）+ `SkyIntoGbuffer`（公开 `SkyRenderer` 自建 pass、
  颜色 LOAD 语义 ⇒ 天空落在 gbuffer 里，正是 OF `gbuffers_skybasic` 的落点）。
- **踩到并修**：首臂 `SkyRenderer.renderSkyDisc:165` NPE（`"v" is null`）——原因是借用了原版
  **共享**的 `skyRenderState`（我们在 AfterLevel 才跑，那份没被填）。改为自己持 state 并调公开的
  `extractRenderState(level, partialTicks, camera, state)`。
- **机制判据达成**：同帧两个取点 `c0@afterTerrain = 71.0910` → `c0@afterSky = 64.1701`
  ⇒ 天空 pass 真的写进了 colortex0。
- **但黑帧回来了**（S2 臂 round #12 全 0，#10/#11 正常）⇒ 「天空 pass 的深度附件语义」这条未知
  从待测升为**已确认有害**：它覆盖我方 gbuffer 深度后，链按 `depthtex0` 把内容判成天空。
  下一步 = 给它只读深度或另建一张深度。**因此 `mrt.skyPass` 保持默认关**（不产出回归）。
- 配套取点：`c0@afterSky`（与 `c0@afterTerrain` 同一开关门控）。

- **版本**：`neo_version` 26.3.0.41-beta → **26.3.0.51-beta**，`net.neoforged.moddev`
  2.0.147 → **2.0.148**（官方 maven metadata 当时 26.3 线最新）。`./gradlew build` 全绿；
  客户端在新版上真起跑（V1/T8/Y1 三臂：`backend=Vulkan` + `[route] chain=true` +
  `post chain executed: passes=11`），除本机固有 `flite`/`OpenAL` 缺失外无新增异常。
  `docs/05-VERSION.md` §2 已同步。
- **新增判据开关**：`mrt.pixelProbeAfterTerrain`（链跑之前先取一次 colortex0）、
  `mrt.chainSamplerLod0`（链采样器钉 mip0）。
- **责任侧钉死**：`c0@afterTerrain` 每帧 40~130 有内容，而同轮帧尾 `colortex0 = main = 0`
  ⇒ **地形没问题，是链把内容打没了**（GAP-019 的旧表述作废并改写）。
- **单变量判据**：钉 mip0 后 `main = 163.9 / 78.2 / 135.6 / 186.2`，**无一帧为 0**；
  完整 mip 范围时 `main = 0 / 122.5 / 0 / 122.5 / 122.5 / 0` ⇒ 黑帧由 **mip 选择**决定，
  登记 **GAP-020**（与 GAP-016 同族）。
- **为什么不能就此交付**：BSL `BloomTile` 故意用「坐标 ×2^lod 借导数取级」，钉 mip0 会把它
  一起压平（= GAP-017 K 臂的必然过曝）⇒ 产品档要么按调用点区分（未缩放坐标的 `texture2D`
  显式改 LOD0，缩放坐标的保留导数），要么查清本后端隐式 LOD 为何落到高 mip。
- **同轮修掉的接线缺陷**：`FullscreenPassHook` 的链分派不再带 `afterLevel()`（两个轴拆开），
  并加 `[route]` 自报 + `RenderRouteWiringTest` 三条守卫。
- **🟢 黑白闪屏修根（GAP-020 关闭）**：逐 pass 追踪（`mrt.postChainTrace`）把黑帧钉到
  「链里第一个写 colortex0 的 pass」，而地形侧取点（`c0@afterTerrain`）证明地形每帧都有内容；
  真因 = **GAP-018 双代轮转 + 惰性金字塔重建** ⇒ 读者采到的 mip1..N 与它同时读的 mip0 不同源。
  修法：删掉 `refreshMipPyramids`/脏集，改为**每级 pass 写完立刻重建**（`regeneratePyramidsForWritten`）。
  判据：`chainSamplerLod0` 保持默认 false 时，逐帧 190 帧里仅开局 3 帧为 0，其余 **185 帧恒 93.1338**；
  独立通道（F2 截图，探针全关）`59.6 / 59.3 / 58.9 / 58.4 / 58.2 / 58.2 / 58.1` —— **闪屏消失**。
- **配套修的取样判定**：`TargetReadback.beginFrame()` 在帧首决定「本帧是否取样」
  （地形后取点与逐 pass 追踪共用同一判定），否则一条曲线里会混着不同帧的数字
  （h48 实测：同一标签同一轮出现 186.2 与 0.0 两个读数）。

---


## 2026-10-05（六十八）— GAP-017 修根落地：colortex 真实 mip 链（池 + 降采样金字塔）⇒ K 臂全白退场

> **verdict = 白前线用「按包声明槽位、读前重建」的渲染金字塔修掉**：`ColortexPool`
> （原版 RenderTarget 逐类核实只有 mipLevels=1 ⇒ 自建多级纹理 + 每级视图）+ blit 管线逐级降采样
> + 脏集机制（写后标脏、读前重建，只对 `colortexNMipmapEnabled` 声明的槽付费）。
> L 臂实测 `main = (164.5,190.1,255.0)` 对比 K 臂 `(252.0,251.1,249.0)` ⇒ 全白消失、
> 出现天空蓝梯度。**观感逐像素对照仍未做**（GAP-017 保持开放）。935 条单测全绿。
> 证据 `evidence/h46-…` §L 臂；登记表 GAP-017 状态行已更新。

- 顺带：`MethodLengthRatchet` 抓到 `drawPostChain` 过线 ⇒ 当场拆出 `logChainExecutedOnce`
  （棘轮第一次拦到**新链自己**的长方法 —— 它是给链修路的，不是摆设）。
- 是否已提交：见本次提交（**不带任何 trailer**）。

## 2026-10-05（六十七）— 🔴 BSL 整条后处理链接入（colortex 按名接线）+ 内置 noisetex + GAP-008 决定性探针

> **verdict = 「composite 只喂 scene、只跑三步」的时代结束**：deferred*→composite*→final
> 全链进入引擎并在 **Vulkan 上真跑通**（BSL passes=11、40/40 管线编译对齐、逐帧执行自报）；
> 用户点名的「FrameApi 的 packColor 从 scene 改采 colortex」成为链上的一个自然结果。
> 🔴 **GAP-008 主因经 h46 四臂交叉改判 = 包片元对图集的隐式导数 LOD 选了坏 mip**（登记
> **GAP-016**，止血 `mrt.terrainAtlasLod0` 已验证生效）；新的前线症状 = 链输出近全白（H 臂 254.8）。
> 证据：evidence/h46-post-chain-integration.md。单测全绿（+50 条）；
> 取证全程 Vulkan（lavapipe），按用户指令不做性能结论。

- **✅ 整链的机制（此前没有任何东西保证链能建出来）**
  - `pipeline/model/PostPassContract`：按转译终稿解析「该 pass 写哪些 colortex 槽 + 声明哪些 sampler」；
  - `pipeline/model/PostOutputRenumber`：**colortex 槽号 → 附件下标**重编号
    （Vulkan 的 location 是附件下标；地形 pass 因附件恰为前缀而掩盖了这个差别）；拒绝形态全部抛；
  - `pack/PackPostChain`：OF 族序+序号整链（deferred*→composite*→final）、维度隔离、
    超集闸门（`PostSamplerSuperset`，含 InSampler 豁免）；
  - `VkDispVirtualPack`：16 个 `shaders/postK.fsh` 槽位资源（尾部 = passthrough）+ 每槽 builtins 布局；
  - `PipelineApi`：16 条定宽（8 颜色目标）后处理管线 + 全量 sampler 绑定；
    `FrameApi.drawPostChain`：**colortexN=池视图 / gaux1=colortex4 / depthtex=池深度 / shadow*=桩 /
    自定义纹理优先**；未写槽挂 scratch ⇒「既作附件又作采样器」构造性不可能（h26 那族的机制封堵）；
  - `FullscreenPassHook`：链模式时序 = **先地形 MRT 写 gbuffer，再跑链**（旧三步时代时序反了看不出来）。

- **🔴 本轮自己抓到并修掉的真 bug（BSL 真包测试现形）**
  - 多维度包同名程序**重复进链**：BSL 的 deferred 有 world-1/world0/world1 三条 ⇒ 链预算 16
    被 5×3 吃光，**composite5..final 整体消失且没有任何一行报错**。按名去重修复。
    🔖 与 h44「声明写哪些槽」同族：**「能建出来」不等于「建出来的是对的」**。

- **✅ GAP-008 决定性探针 ×2（`mrt.terrainCoordOutProbe` / `mrt.terrainLodZeroProbe`）**
  h45 把成因钉在 `texture(texture_0, texCoord)` 的返回值上，剩下二叉：
  坐标落错（图集约 26% 是透明填充）vs 采样器/LOD 侧坏。
  坐标档让 colortex0 直接携带 texCoord 数值（像素回读给数）；LOD 档把 `texture(s,c)` 换
  `textureLod(s,c,0.0)`。两档各自单变量 + 命中自报（与左右探针互斥时坐标档优先并 WARN）。

- **✅ GAP-009 素材线第一步：自定义纹理 + 内置 noisetex**
  - `PackTextureBindings`（纯）：`texture.<sampler>=path` 两段键按名绑定；**三段键
    （`texture.composite.colortex7`）语义未核实 ⇒ 不收并点名**（X9）；
  - `bridge/PackTextures`：渲染线程、开 pass 前懒上传（NativeImage → TextureTarget +
    writeToTexture），指纹比对换包重建；失败逐条 ERROR 回落显式占位；
  - **内置 noisetex**（64×64 固定种子确定性噪声；OptiFine 公开 API 事实：引擎自带该采样器）
    —— BSL 的 blue-noise 抖动/胶片颗粒自此有真值可采。

- **🔴 本轮运行期暴露并当场修掉的三个真缺陷（证据 §三）**
  1. 槽位管线**片元 id 形态**猜错（多带 `shaders/` 前缀）⇒ 16 条 required 管线
     「Couldn't find source」；对照既有范本本可避免 —— **猜 id = X9**。
  2. 超集缺 `depthtex2` ⇒ composite2/3 被闸门踢出。**闸门第一次真拦住了东西**，
     行为正确、清单缺员。
  3. VS 适配层 `vUv@0` 与契约 `texCoord@0` **location 重叠** ⇒ glslang 拒绝 ⇒
     全部 16 条 required 管线编译失败、**整次资源重载被砸**（用户会看见）。
     已按「契约占 0 就不输出 vUv」修复 + 3 条单测钉住。

- **✅ 运行期取证（iso 车道，MCP 驱动被会话权限拦 ⇒ 走 h43 起的进程内探针通道）**
  - E 臂（链基线）：`post chain executed: passes=11 first=deferred last=final`、
    `pipeline count check: registered=40, compiled=40 (aligned)`、
    `builtin noisetex created 64x64`、`custom texture loaded: sampler='noise' 512x512`。
  - F2 臂**作废**（探针自己取错坐标名 shadowPosXY ⇒ 测的不是被测对象；已修锚点 + 回归测试——
    「命中一处」≠「命中的是该测的那处」）。
  - F3 臂（输出直写坐标）：`texCoord ≈ (0.43,0.26–0.35)` **非零** ⇒ **撤回 F 臂「坐标为 0」判读**。
  - G 臂（显式 LOD0）：albedo 立刻非零 ⇒ 四臂交叉 + h45 交叉 ⇒ **主因 = 隐式导数 LOD 选坏 mip**
    ⇒ 登记 **GAP-016**，止血 = `mrt.terrainAtlasLod0`（图集采样器 maxLod=0，默认开、可关并 WARN）。
  - H 臂（止血开、探针全关）：`colortex0` 非零（止血与 G 臂逐字同形）；
    🔴 新症状：`main` 变**近全白（254.8）** —— 黑前线换成了白前线（链上某级过曝，下一刀逐级二分）。

- **✅ I/J 臂（同轮续）：白前线定位到级** —— `colortex1` 帧尾即白而 `colortex0` 正常
  ⇒ 白进入于 composite4（bloom），其 `colortex0MipmapEnabled` 要求**真实 mip 链**、
  我方池只有 mip0 ⇒ 下一个自行补充项 = colortex 池 mip 链（GAP-017 候选，未动手不登记完成）。
  产品级修复：地形片元 albedo 行随 `mrt.terrainAtlasLod0` 转正为显式 mip0（守卫：只动一行，否则 ERROR 自报）。

- **🔴 本轮没做 / 不承诺**：colortex mip 链（GAP-017 已登记，**止血 `maxLod=0` 经 K 臂实测证伪**
  —— 白是 BloomTile 八级 tap 全落 mip0 的必然抬升，不是驱动未定义；修根 = 渲染金字塔，方案已写进登记表）；
  GAP-016 的根因（图集 mip 链内容 vs lavapipe 导数路径 —— 按 mip 回读判据已备好）；
  shadow 真贴图进链（GAP-015 语义不变）；非地形的 gbuffers_*（water/entities/sky/hand…）；
  三段 texture 键；TAA 需要的 `gbufferPrevious*`；validation layer 仍无 ⇒ 按 X35 不说
  「无 validation error」；性能一律不下结论（取证铁律）。

- **是否已提交**：待测试段完成后随下条提交（**不带任何 trailer**）。

## 2026-10-05（六十六）— 像素回读探针 + 单变量 A/B 入口；并修掉它们各自暴露的三个真缺陷

> **verdict = 两件新取证工具落地；GAP-008 在本机 Vulkan 上**没有**复现
> （colortex0 有内容）；而 h42 §4.3 登记的「输出黑 vs 没落到主目标」
> 现在有了可复算的判别手段，并且它当场判出「两者都不是」。**
> 取证全程 Vulkan；按用户指令不做性能结论。859 条单测全绿（+42）。
> 证据：evidence/h43-pixel-probe-and-single-variable-ab.md。

- **✅ 新工具 1：`mrt.pixelProbe`（GPU→CPU 像素回读 + 统计，数字直接进日志）**
  - 官方公开回读入口 = 原版 `Screenshot#takeScreenshot` 用的
    `copyTextureToBuffer(tex, buffer, 0L, callback, 0)` + `USAGE_COPY_DST|USAGE_MAP_READ`。
  - 🔖 **统计口径逐字沿用已有工具**（采样区 `0.45/0.15/0.65/0.75`、黑阈值 8、Rec.709 luma），
    采样区**按比例**换算 ⇒ 换窗口尺寸仍与 h22/h31 的历史数字可比。
  - 🔖 **四种结论必须分清**：首版只写三分，漏掉的
    「主目标有内容 + colortex 全黑」**恰是 GAP-008 的定义形态** ⇒ 已补 + 守卫。

- **✅ 新工具 2：`pack.optionOverrides`（按名强制包选项，只改内存 ⇒ 真单变量 A/B）**
  - 解决 h42 §4.2 的未做项：能力门控一次改 9 项并改变程序形状，两臂不是单变量。
  - 代码里**零硬编码包特性名**（单测钉住；X27：Complementary 同样有视差却零外部依赖）。
  - 解析失败 ⇒ **一条都不改**；**钳制单独报**（钳制生效了，但不是你要的值）。

- **🔖🔖 单变量 A/B 实测（同一存档/机位/时间/天气，只改 `PARALLAX`）**
  | 臂 | 主目标 `mean_luma` | colortex0 `mean_luma` / 非黑 / `maxR` |
  |---|---|---|
  | `PARALLAX=false` | **16.6260** | 5.5936 / 28.005% / 15 |
  | `PARALLAX=true` | **16.6260** | 2.4961 / 25.703% / 62 |
  - ✅ **这套配置下 GAP-008 没有复现**（colortex0 有内容）。
  - ✅ **视差确实影响 colortex0 的输出**（数字变了）⇒ 不能说「视差无关」。
  - 🔴 **不能说「GAP-008 已修好」**：h42 复现它用的是 `terrainToMain=true`，
    而那一档本轮**没做成有效的两源对照**（原因见下）⇒ 该档仍未定位。

- **🔴🔴 修掉三个真缺陷（都是新工具自己暴露的）**
  1. **探针位置早于地形 pass** ⇒ `toMain` 档读到的「主目标」是写入前的内容，
     **恰好在需要它的那一档失效**。已移到 `FullscreenPassHook` 末尾（三条约束的唯一满足处）。
  2. 🔴🔴 **`toMain` 档拿 `colortex0` 当对照 = 一个刚通过全部单测、刚在 Vulkan 上跑出数字的诊断，
     在最需要它的那一档里给的是假证据**（该档附件 0 已被换成主目标视图，
     `colortex0` 根本没被写过）。⇒ 这条比另外两条更值得记：
     **「诊断能出数字」不等于「诊断给的数字对」。**
  3. **`pack.optionOverrides` 不进地形契约记忆键** ⇒ 改覆盖串后 composite 侧按新配置、
     地形侧按旧配置，**两条链互相矛盾而日志看起来完全正常**
     （QD-02 / h33 之后这一族的**第四例**）。已修并由实测日志自证。

- **✅ 顺带修两个取证工具缺陷**
  - `lane_cfg.py`：新增 `upsert`（节不存在时连节头一起建）+ `--lane main|iso`。
  - 🔴 **字符串项漏引号会让整份配置被丢弃**：NightConfig 抛 `ParsingException` 后
    **按默认值重建整份文件**（`shaderPack` 变回 `""`、所有开关回默认），
    唯一征兆是 FileWatcher 线程一行 WARN ⇒ 「改配置没生效」的真正原因会被完全错过。

- **🔴 本轮没做**：GAP-008 在 `terrainToMain=true` 档的真因；该档的有效两源对照
  （需 ≥2 个附件才可能，BSL 默认只写槽 0）；GAP-011 闪烁；
  validation layer（仍然没有 ⇒ 按 X35 **不得**说「无 validation error」）；
  性能结论（按用户指令不做）。

- **是否已提交**：见本次提交（**不带任何 trailer**）。

## 2026-10-05（六十五）— 🔴 在 Vulkan 上复现 GAP-008，并推翻 h31 的收尾结论

> **verdict = 本项目第一次在 Vulkan 主目标上复现 GAP-008；
> 而 h31 在 OpenGL 上测得的「关掉 PARALLAX 即可消除」在本机 Vulkan 上**不成立**。
> 同时：一条我自己写下的结论被我自己写的守卫当场推翻。**
> 取证全程 Vulkan; 按用户指令不做性能结论。817 条单测全绿, +1 类。
> 证据: evidence/h42-gap008-on-vulkan-not-reproduced.md。

- **🔴 GAP-008 在 Vulkan 主目标上复现（此前只有 OpenGL 观测）**
  - G1 = terrainToMain=true + capabilityGate=false => 主目标**近乎全黑**，
    仅零星方块可见，HUD（原版渲染）正常 => 正是登记的「剪影全黑：几何与槽位路由对，像素值不对」。
  - 🔖 观测面**刻意与 h31 一致**（都是主目标）—— 因为 h28/h29 正是因为误用诊断视图下过结论。

- **🔴🔴 推翻 h31 的收尾结论**
  - G2 = terrainToMain=true + capabilityGate=true => 地形**完全消失**，**更黑**。
  - 门控**确实**按预期关掉了视差（日志原文 `命中 9/9 [... PARALLAX=false ...]`），
    这正是 h31 要求的手工动作 => **但画面仍全黑**。
  - => **「关掉视差即可消除」是 OpenGL 产物，在 Vulkan 上未获证实。GAP-008 保持开放。**

- **🔖 但两组不是单变量（本轮拒绝下的结论）**
  | | G1 门控关 | G2 门控开 |
  |---|---|---|
  | outputs | **8** | **1** |
  | samplers | **7** | **5** |
  | varyings | **15** | **9** |
  => 门控改写 **9 个**选项并**改变派生程序形状**
  => **不能**说「视差无关」，**也不能**说「是另外 8 项导致的」。
  唯一站得住的是：**h31 依据的那个具体动作在 Vulkan 上没有得到证实。**

- **🔶 另一个未分辨因素（如实登记，未假装已坐实）**
  mrt.terrainToMain 的配置注释原文「**会清掉主目标画面**」
  => 两组黑屏里至少有一部分可能是「清屏了但地形 draw 没落到主目标」，
  而不是「包的地形片元输出全黑」。要分开需要一个判别手段，**已列为下一步**。

- **🔴🔴 一条被自己推翻的结论（本轮最该记的一条）**
  - 我在证据里先写下「**GAP-008 是唯一一条没有「状态」字段的缺口**」。
  - 随后**同一人同一轮写的守卫**当场数出另外 **9 条**
    （GAP-002/003/004/005/006/007/009/010/011）=> **那句话是错的**，已撤回。
  - 🔖 **「唯一」是最容易下、也最容易被自己的工具推翻的结论。**
    与 h33 死开关 / h34 刷屏 / h35 漏数 / h41 诊断说假话同族:
    **「结论写下来了，但从没有东西让它保持正确」**。
    这次的不同点是——**推翻它的是我自己写的工具**。

- **✅ 新增守卫 GapRegistryStatusFieldTest（3 条）**
  - 处置选**棘轮而非硬断言**: 当前有 9 条欠账，硬断言会让构建立刻长期红，
    等于把规范取消。基线 = 当前欠账 9 条，**只允许变短**。
  - 含 **2 条元测试**：证明「缺」与「有」两种样本都能被正确判定，否则守卫是空话。
  - 另断言 GAP-008 必须标为仍开放且注明其正面定位来自 OpenGL。

- **🔴 本轮没做**：定位 Vulkan 上的真因; 分清「输出黑」与「没落到主目标」;
  做真正单变量的 PARALLAX A/B（需单独关它而不动其余 8 项）;
  补齐其余 9 条缺口的状态字段（登记为 **QD-07**）; 性能结论（按用户指令不做）;
  validation layer（仍然没有）。

- **是否已提交**：见本次提交（**不带任何 trailer**）。


## 2026-10-05（六十四）— 顶点供值分三档：诊断原先说了假话（vTexCoord 其实有真值部分）

> **verdict = 独立复核 DefaultVertexFormat.BLOCK（确认既有结论正确），
> 并借此发现诊断文本不准确：把「部分有真值」说成「完全没有数据源」。
> 本轮唯一的代码改动是让诊断说实话。**
> 取证全程 Vulkan；按用户指令不做性能结论。814 条单测全绿（+4）。
> 证据：evidence/h41-…。

- **🔖 独立复核（本轮的前提，未改动既有结论）**
  - 从 minecraft-patched-26.3.0.41-beta.jar 反汇编 DefaultVertexFormat 静态块：
    BLOCK = **Position, Color, UV0, UV2**（**4 条，无 Normal**）；
    ENTITY = Position, Color, UV0, UV1, UV2, **Normal**。
  - 证实代码里那句注释「适配层可读取的原版地形顶点属性（BLOCK，字节码核实）」**是对的**，
    本轮**没有推翻任何东西**。顺手也确认了 MULTIDRAW_TERRAIN 走 POSITION。

- **🔴 本轮真正修的：诊断说了假话**
  - 旧诊断对 7 条 varying **一律**说「原版地形顶点缓冲**无对应属性**，登记为 GAP-007」。
  - **但 vTexCoord / vTexCoordAM 的 .xy 就是真实的 UV0** —— BLOCK 格式确实有它。
    缺的只是**图集重映射**（依赖同样缺失的 mat）与 z/w。
  - 🔖 **危害不是措辞**：照旧诊断，读者会以为这两个 varying **完全没有数据源**，
    进而把它当成「必须改网格顶点格式才能拿到」——而实际上它**已经有真值部分**，
    这会影响将来对 GAP-007 修法的取舍判断。**诊断不许说假话（X9）。**
  - 改为**三档**并各自说清理由：
    - 真值：Position / Color / UV0 / UV2（+MULTIDRAW 的 ChunkPosition / ChunkVisibility）
    - **部分真值**：vTexCoord / vTexCoordAM —— .xy 来自真实 UV0；缺图集重映射与 z/w
    - 纯常量：mat, recolor, normal, binormal, tangent —— 属性确实不存在

- **✅ Vulkan 运行期：新摘要按两档分别报数（原文）**
  varyings=15（纯常量供值 5 条：mat, recolor, normal, binormal, tangent；
  部分真值供值 2 条：vTexCoord, vTexCoordAM）
  两条诊断同时在场且**文本互不串台**：
  - 部分档：…只能**部分**供值：.xy 取自真实的 UV0（BLOCK 格式确有该属性），但缺图集重映射矩阵…
  - 纯常量档：…只能按常量供值（原版地形顶点缓冲无对应属性，登记为 GAP-007）…
  - backend=Vulkan、ERROR 0、残留游戏进程数=0

- **🔖 配了 4 条守卫**，其中一条是**反向断言**：
  部分真值档的诊断**不得**包含「无对应属性」这五个字
  => 将来若有人把两档合并回一句话，红灯会亮。另有断言纯常量档的五条**不得**被顺手挪走。

- **🔴 本轮没做**：让 normal/tangent/binormal 拿到真值（需改**网格顶点格式**，
  属跨层改动，风险高一档，不适合塞进诊断修正轮）；mat/recolor 的真实来源
  （依赖图集与生物群系染色管线）；GAP-008（两条候选成因仍未逐项二分验证，本轮未碰）；
  性能结论（按用户指令不做）；validation layer（仍然没有）。

- **是否已提交**：见本次提交（**不带任何 trailer**）。


## 2026-10-05（六十三）— 数组纹理采样器改成**不绑**；判据从个案上升为可复用规则

> **verdict = 堵掉一个从未被登记的静默 UB 类别（拿 2D 图冒充数组纹理）；
> 本轮真正的收获是把 GAP-015 的个案论证**上升为一条可复用判据**。**
> 取证全程 Vulkan；按用户指令不做性能结论。810 条单测全绿（+3）。
> 证据：`evidence/h40-…`。

- **🔴 修的问题（本轮唯一的代码改动）**
  - `sampler2DArray` / `sampler2DArrayShadow` 原来落进**普通 2D 占位分支**
    ⇒ **喂一张普通 2D 图**，而数组采样器在 Vulkan 里要求 **Arrayed=1** 的图像视图
    ⇒ 与 GAP-012「拿 2D 冒充 3D」、GAP-014「3D 纹理建不出来」**完全同族**，
    且本机无 validation layer ⇒ **一条错都不报**。
  - 改为 **`UNSUPPORTED`（不绑）+ 可见告警**，与 cube / sampler3D 同等待遇。

- **🔖 先核实事实，不猜（这一步差点翻车）**
  - 第一次 `grep` `sampler2DArray` 得 0，**但 h32 明确记过 BSL 有 3 个 `sampler3D`**
    ⇒ 0 与 3 矛盾 ⇒ 是**工具坏了**，不是包的问题。
  - 查下去：**这机器没装 `unzip`**，`/tmp/bsl` 解出 **0 个文件**，
    `grep -r` 在空目录上跑 ⇒ 「什么都没找到」。
  - 改用 Python `zipfile` 直读 274 个着色器源：
    `sampler2D` 143 / `sampler3D` 26 / `sampler2DShadow` 5 / **`sampler2DArray` 0**。
  🔖 记这一条：**「grep 没找到」与「确实不存在」是两件事**；
  h32 留下的那条 3 个 `sampler3D` 正是让我没把 0 当结论的锚点。

- **🔖🔖 本轮真正的收获：判据不是「类型像不像」，是「出现几次」**
  同一轮处理了两个**同族**的「原版建不出来」：

  | | `shadowtex0/1`（GAP-015） | `sampler2DArray`（本轮） |
  |---|---|---|
  | 原版缺什么 | **比较**采样器 | **数组**纹理（GAP-014 已证） |
  | 类型像不像 UB | 像 | 像 |
  | 在 BSL **地形程序**里出现次数 | **2**（每种配置都在） | **0** |
  | 处置 | **保留绑定 + 一次性 WARN 明示** | **不绑 + 告警** |

  ⇒ **处置相反，理由是同一个数。**
  h38 给 GAP-015 写的「不绑会让地形整条不渲染」其实是**针对那一个对象的论证**；
  本轮把它明确成可复用规则：
  > **原版建不出来的采样器，处置由「它在本包地形程序里出现几次」决定**，
  > 不是由「类型像不像 UB」决定。出现 0 次 ⇒ 不绑；每种配置都在 ⇒ 保留 + 明示。

  已写进代码注释 + 登记 + **一条单测锁死两边**（`theCriterionIsOccurrenceCountNotTypeSimilarity`：
  同一个 `Plan` 里同时断言「shadowtex0 ⇒ 保留」「arr ⇒ 不绑」），
  防止将来有人「为了统一」把其中一个改反。

- **写清不变的部分**：普通 `sampler2D`/`sampler2DShadow` 的占位行为**完全不变**（已加测试）；
  `sampler3D` 仍声明 `VOLUME_3D` ——
  🔖 **「决策层认为该绑什么」与「原版建不建得出来」是两件事**，
  决策层保持正确，建不出来由 GAP-014 侧记录降级（已加测试把这个区分固定）。

- **✅ Vulkan 实测：对 BSL 零回归，且与静态分析互相印证**
  ```
  backend   : 1   ERROR: 0   GAP-015: 1
  array warn: 0   ← 运行期印证「BSL 里 0 次」
  by dimension: ATLAS_2D=1 PLACEHOLDER_2D=1 NEUTRAL_MATERIAL_2D=2
                SHADOW_DEPTH_2D=2 SHADOW_COLOR_2D=1  ← 与改前**逐字相同**
  ```

- **🔴 本轮没做**：让数组纹理真能绑（**原版做不到**，这正是不绑的原因）；
  `sampler2DArrayShadow` 的比较采样器那一半（它同时踩两件事，本轮只挡了数组那件）；
  性能结论（按用户指令不做）；validation layer（仍然没有）；GAP-011（仍未复现）。

- **是否已提交**：见本次提交（**不带任何 trailer**）。

## 2026-10-05（六十二）— 修掉「名字压过类型」的入口；用**移动机位**再试一次 GAP-011

> **verdict = 修掉一个「拿 2D 冒充 3D」的入口（本轮唯一的代码改动）；
> GAP-011 在移动机位下**仍然复现不出来**，且这次把「看着像闪烁」的 2.17 pp 抖动
> **逐带证伪**成了区块流送。**
> 取证全程 Vulkan；按用户指令不做性能结论。807 条单测全绿（+4）。
> 证据：`evidence/h39-…`。

- **🔴 主项（h38 登记过的待改项，不是新挑的活）**
  - 旧代码的 `switch (name)` **无条件**先生效 ⇒ 若包写 `uniform sampler3D shadowtex0;`，
    会拿到**一张 1x1 D32 的 2D 深度图** —— **正是 GAP-012 修掉的那类「拿 2D 冒充」**，
    只是换了个入口、**从没被登记过**。
  - 修法：**名字只选来源、声明类型约束维度** —— 非 2D 声明一律落到既有的**类型路径**
    （`sampler3D`⇒VOLUME_3D / cube⇒UNSUPPORTED）。
  - **既有行为不变**：2D 声明与**裸 `sampler`**（维度未知）仍走名字规则，7 条绑定逐条照旧。
    裸 sampler 特意不剥夺名字规则：维度未知时不猜（X9），但也不因此把既有行为一并改掉。

- **🔖 顺带：QD-04 棘轮当场把本轮的红灯点亮了**
  ```
  MethodLengthRatchetTest > 主源码 >60 行方法数不得超过基线（QD-04 棘轮） FAILED
  ```
  `decide()` 涨到 **63 行**。**没有上调基线**，而是抽出两个方法：
  `byName()`（名字→来源，未命中返 null）+ `declaredNon2D()`（类型是否蕴含非 2D），
  `decide()` 降到 **36 行**。
  🔖 这次是**代码**让一条规范保持了正确 —— 正是 `h35` 那条纪律的用处。

- **🔖 副项：GAP-011 用**移动机位**试（此前所有测试都是静态机位）**
  - 🔖 **此前全部闪烁取证的共同盲点**：`h16`–`h38` 的 `.client` 取图**机位一律不动**，
    而原始症状是**用户正常游玩时**观察到的。静态机位很可能根本触发不了。
  - 本轮条件：`12 个 yaw（90°→360°→60°）× 每处连拍 3 帧 = 36 帧`。
  - 结果：同机位最大抖动 **2.169 pp**（yaw=150，30.951%→28.787%）。
  - 🔖 **但没有直接当结论** —— 区块流送同样会动这个数。对 yaw=150 做**逐带**分解：
    变化集中在 **mid 带**（70.019%→63.019%，**下降** = 有内容载入）；
    **terrain 带黑像素反而降到 0.000%**（地形全亮，没有一块变黑）。
    闪烁应表现为 terrain 带黑像素**上升** ⇒ **判定：这是区块流送，不是 GAP-011**。
  - ⇒ **区分清楚**：「本轮没复现」**不等于**「GAP-011 不存在」；
    剩余未试条件（真实玩家移动 / 昼夜天气切换 / 不同分辨率 / 大视距）已如实登记。

- **✅ Vulkan 运行期**：`backend=Vulkan` ✓、`GAP-015` 仍**恰好 1 条** ✓（本轮改动未破坏它）、
  ERROR **0** ✓、pass 帧数 1800 ✓、`残留游戏进程数=0`。

- **🔴 本轮没做**：复现 GAP-011（仍未复现）；`sampler2DArray` 是否该改 UNSUPPORTED
  （已加测试锁住现状，属独立决策 —— 改了会让用到它的包地形不渲染）；
  性能结论（按用户指令不做）；validation layer（仍然没有）。

- **是否已提交**：见本次提交（**不带任何 trailer**）。

## 2026-10-05（六十一）— 🔴 新缺口 GAP-015：原版**建不出比较采样器** ⇒ `sampler2DShadow` 绑的不是它要的东西

> **verdict = GAP-012 的同类问题第二次出现，这次在「采样器」这一侧。
> 事实经反汇编核实、未猜；已在 Vulkan 上实测确认说明会打且只打一次。**
> 按用户指令：只验功能、不做性能；取证全程在 Vulkan 上。
> 803 条单测全绿（+4）。证据：`evidence/h38-…`。

- **🔴 新缺口 GAP-015（本轮唯一的功能性发现）**
  - `GpuDevice` 只有**一个**采样器工厂
    `createSampler(AddressMode, AddressMode, FilterMode, FilterMode, int, OptionalDouble)`
    —— **签名里没有 `CompareOp`**；全类再无第二个入口。
    ⇒ **原版拿不到「比较采样器」**。
  - 而 BSL 把 `shadowtex0` / `shadowtex1` 声明为 **`sampler2DShadow`**（要比较采样器）
    ⇒ 只能绑**非比较**的 `atlasSampler` ⇒ **描述符类型不匹配 = Vulkan UB**（与 GAP-012 同族）。
  - 实测确认两条阴影绑定**确实存在**：`by dimension: … SHADOW_DEPTH_2D=2 …`。
  - 本机无 validation layer（`h37` §八）⇒ **一条错都不会报**。

- **🔖 一处差点踩进去的坑（本轮最该记的）**
  第一眼看到 `SamplerCache.getClampToEdge(FilterMode, boolean)`，
  差点就按「这个 boolean 是 compare 开关」写进文档。查 `LocalVariableTable` 才看清：
  `#84 = Utf8 useMipmaps` ⇒ 那个 boolean 是 **`useMipmaps`**，**不是 `compare`**。
  🔖 若没查这一步，文档会写出一个**看起来完全合理但错的**修法指引
  （「换个带 compare 的重载就行」）⇒ 下一个人会照着白找一遍。**这就是 X9「不猜」的具体价值。**

- **🔴 取舍：为什么「照样绑」而不是学 GAP-012/014 那样「不绑 + 报错」**
  | | GAP-012/014 的对象 | `shadowtex0/1` |
  |---|---|---|
  | 本包**地形程序**里出现次数 | **0** | **2** |
  | 不绑的后果 | 不影响渲染 | draw 抛 `Missing uniform` ⇒ **地形整条不渲染** |
  ⇒ 按支柱①（兼容优先），「阴影项不可信」**优于**「地形完全不画」。
  🔖 代价是 UB 继续存在 —— 那是**原版的结构性限制**，不是本项目实现失误；
  唯一诚实的选择是让它**一直可见**：已在一次性绑定摘要里加 WARN（写明后果 + 为何不绑）。

- **🔖 发现者被自己绊住的一条（如实登记，未默默留着）**
  `SamplerDimensionPlan` 对 `shadowtex*` 仍**按名字**分类（注释原文「它们全都是 2D ⇒ 先按名字定」），
  **没有**走 GAP-012 修复所立的「维度来自声明的类型」规则。
  - 本轮**没改**：改了会让「声明成 `sampler2D` 的 shadowtex」拿到 RGBA 桩而非深度桩 ⇒ 那才是把语义改错。
  - 已登记进 GAP-015 的「⚠️ 顺带发现」待改项。
  🔖 记它是因为：**「我们修了 X」只有在被修的那条路径上成立** ——
  GAP-012 修了未命中的 fallback，却留着命中分支按名字定，属**修得不彻底但没写出来**。

- **✅ Vulkan 运行期验证**
  ```
  [GAP-015] shadowtex* 是 sampler2DShadow（比较采样器），而本引擎**建不出比较采样器**…
  ⇒ 当前绑的是**非比较**采样器 = 描述符类型不匹配 = Vulkan UB。
  后果限定为：**阴影项的结果不可信**（不是崩溃、不是全黑）；地形本身仍会画。
  ```
  - `GAP-015` WARN 命中 **恰好 1 条**（一次性、无刷屏）
  - `backend=Vulkan` ✓；vkdisp ERROR **0** ✓
  - 新增 `ComparisonSamplerGapTest`（4 条）守「这条缺口必须一直可见」，
    其中一条**明确禁止**守卫反过来要求「不绑」（否则会把兼容优先的取舍悄悄推翻）

- **🔴 本轮没做**：做出类型正确的绑定（**原版做不到**，这正是 GAP-015 的定义）；
  `shadowtex*` 改为按声明类型分类（已登记）；性能结论（按用户指令不做）；
  validation layer（仍然没有）。

- **是否已提交**：见本次提交（一个功能一个 commit；**不带任何 trailer**）。

## 2026-10-05（六十）— 🔴 真 Vulkan 上的功能 A/B：一个**否定式但重要**的结论 + 取证铁律落地

> **verdict = 本项目第一次在真 Vulkan 后端跑完一组受控 A/B。
> 结果是 `shadowStubs` 修复在 lavapipe 上测不出任何画面差别（地形逐像素差 0.000），
> GAP-011 闪烁在**两条臂上都复现不出来**。**
> 按用户指令「只验证功能、不考虑性能」，本轮**零 `.java` 改动**、**零性能结论**。
> 证据：`evidence/h37-…`。

- **🔴 轮一 · `mrt.shadowStubs` A/B（首次在 Vulkan 上做）**
  - `true`（修复：shadowtex 绑专用 1×1 桩）vs `false`（**故意 UB**：绑本 pass 的读写附件）
  - 单变量证明：两组配置 `diff` 输出**恰好一行**（X52）
  - 受控条件：同存档 / 同机位（yaw 90 pitch 8）/ `dayTime=6000` / `weather=clear`
  - **结果：HUD、hotbar、地形三带逐像素差均为 `0.000`**，只有天空带 1.942（两次采图之间云层在动）

- **🔖 这个否定式结论怎么用**
  - **测出的**：lavapipe（CPU 软件 Vulkan）上，开关这个 UB **画面完全一样**。
  - **该保留的理由仍然成立**：Vulkan 规范明文禁止「同一 image 既作读写附件又作采样器」，
    这是 UB —— 规范允许驱动做任何事，lavapipe 恰好处理了不等于别的驱动也会。
  - 🔖 **必须说清的边界**：GAP-012 这条修复**只有规范层面的依据，没有本机复现证据**。
    若此前文档写过「修复后闪烁消失」之类因果结论，**那是没有证据的**
    （与 `h27` 撤回 `h24` 归因同一纪律）。已把这段写进 `13-GAP-REGISTRY`。

- **🔴 GAP-011 又排除一个候选**
  - 两臂各连拍 6 帧，黑色占比 spread **0.042 pp（修复）/ 0.044 pp（故意 UB）**
  - ⇒ **闪烁不是「shadowtex 绑读写附件这个 UB」造成的**（至少 lavapipe 上不是）
  - ⇒ 两条臂都**复现不出闪烁**，真实成因**仍未定位**。已写进 `13-GAP-REGISTRY` GAP-011。

- **🔖 方法学收获：这次对照成立、`h36` 那次不成立，差别在 HUD**
  | 对照 | HUD 像素差 >8 | 判定 |
  |---|---|---|
  | `h36` OpenGL vs Vulkan | **30.62%** | 🔴 不成立（连原版 HUD 都差 30%） |
  | 本轮 A vs B | **0.00%** | ✅ 成立 |
  🔖 HUD 是**不受本项目影响**的原版 UI ⇒ 它是「两次采图是否对齐」的天然对照物。
  ⇒ 反过来验证了 `h36` 拒绝下结论是对的。

- **🔴 轮二 · 落实用户指令「只验功能、不做性能；每轮测试必须在 Vulkan 上」**
  - `00-INDEX.md`「给 AI 读者」块顶部加**取证铁律**（与三支柱并列）：
    ① 只验证功能，不考虑性能（lavapipe 帧率不代表真实硬件 ⇒ 支柱③ 一律不下结论）；
    ② 每轮取证必须走 `run-client.sh` 且必须 `backend=Vulkan`；
    ③ 这两条有构建期守卫。
  - 新增 `VulkanEvidenceDisciplineTest`（4 条）：守住「规范被删或改松就红」——
    断言 `01-DEV-LOOP` 保留 Vulkan 入口与 lavapipe 说明、断言 §2 执行顺序里
    **不再**把裸 `./gradlew runClient` 当取证步骤、断言 `00-INDEX` 带着这条纪律。
    🔖 **为什么要守卫**：`h33`/`h34`/`h35` 三轮的证据文档都**如实写着**跑在 OpenGL，
    但仍然被当成有效证据用了三轮 ⇒ **如实记录 ≠ 记录了就能用**。

- **同步的文档**：`13-GAP-REGISTRY` 的 GAP-012（否定式结论 + 边界）与
  GAP-011（又排除一个候选）；`evidence/h37-…` 全新。**`CHANGE_LOG.md` 本条。**

- **是否已提交**：见本次提交（纯文档 + 守卫测试，**零 `.java` 改动**；不带任何 trailer）。

## 2026-10-05（五十九）— 🔴 首次在 **Vulkan 后端**取证：P0.2 终于达成（本轮零 `.java` 改动）

> **verdict = 本项目有史以来第一次拿到 Vulkan 产物。**
> **`P0.2` 自此达成**（`backend=Vulkan`）；vkdisp ERROR 从「每轮必有 1 条」变成 **0 条**。
> ⚠️ **环境解锁是并行线 env-1 的成果，不是本轮做的**；本轮做的是「发现它就绪，于是第一次用它取证」。
> 证据：`evidence/h36-…`。

- **🔴 一条此前一直存在的静默降级（本轮才被彻底堵上）**
  - 本机 WSL2 **系统级没有 Vulkan ICD**，而 `runClient` 带着 `--graphicsBackend VULKAN`。
    Minecraft 在 loader 缺失时**不崩也不退出**，只打两行然后
    **静默退回 OpenGL/llvmpipe 继续跑满取证帧数**：
    ```
    WARN  Failed to load Vulkan loader
    ERROR Failed to create backend Vulkan
    INFO  Using graphics backend OpenGL, using drivers: 4.6 …
    ```
  - ⇒ `h33` / `h34` / `h35` **三轮取证全部踩在这条降级上**。
    唯一征兆就是 `P0.2` 每轮断言失败那一条 ERROR —— 当时按「环境事实」记下，没有追根。
  - 现在 `tools/vulkan-local/run-client.sh`（并行线 env-1 提供）会在启动前 preflight **硬失败**，
    并在起跑后断言后端 ⇒ 这条降级被彻底堵上。

- **✅ P0.2 达成（日志原文）**
  ```
  Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1 (LLVM 22.1.8)
  Using graphics device: llvmpipe (LLVM 22.1.8, 256 bits) (0x10005)
  vkdisp: backend=Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)
  ```

- **✅ 前三轮的结论在 Vulkan 上全部成立（不是推断，是这次实测）**
  | 判据 | 结果 |
  |---|---|
  | vkdisp ERROR 总数 | **0**（此前恒为 1，且那 1 条就是「你不是 Vulkan」） |
  | `gbuffer terrain pass failed` | **0**（`h33` 修的级联未复发） |
  | `textureView and sampler must both or neither be null` | **0** |
  | `gbuffer terrain targets ready` | **1**（`h34` 修的 499 行刷屏未复发） |
  | `terrain slot clear` | `NEUTRAL (RGBA 0,0,0,0) for 8 slot(s)` |
  | pass 帧数 / mixin 命中 | **1800** / **x2,500,000** |
  | 退出方式 | 正常存档（`Gathered mod list to write to world save`），非崩溃 |

- **🔖 一条被拒绝的对照（与 `h34` 拒绝「75.29% 像素差」同一纪律）**
  本轮顺手做了「同存档、同机位、`dayTime=6000`、仅后端不同」的跨后端像素对照，
  原始数字 58.10% 像素差 >2、平均差 12.33。**但按屏幕分带看，这个对照不成立**：

  | 屏幕带 | 平均差 | >8 的像素 |
  |---|---|---|
  | **HUD（原版 UI，根本不经过 vkdisp）** | 5.937 | **30.62%** |
  | 地形 | 19.722 | 73.58% |
  | 天空 | 4.415 | 16.88% |

  连原版 HUD 都有 30% 的像素差 ⇒ 主导因素是**两次独立会话之间的运行期差异**
  （in-game time 仍在推进、云层在动），**不是后端语义差异**。
  ⇒ **不据此下任何判断**；并如实登记：要做真正成立的跨后端对照，
  需要可复现世界状态（固定 time **且** weather **且** 坐标/朝向）+ 确定性静物参照，两者目前都没有。

- **⚠️ 两件必须说清的边界**
  - **设备是 lavapipe（CPU 软件 Vulkan）**，不是独显（WSL2 的 GPU 直通只覆盖 CUDA/D3D12，
    NVIDIA 不投放 Vulkan ICD）⇒ **Vulkan 语义是真的，但帧率毫无参考价值**，
    支柱③ B1–B7 **仍然无结论**。
  - **validation layer 仍然没有**：设备支持 `VK_EXT_debug_utils (I)`，
    但 vkdisp 的 drain 仍报 `channel unavailable or empty`
    ⇒ 按 `07` X35 禁令，**不得**据此说「无 validation error」。

- **⚠️ 工作区并行改动**：env-1 的 `build.gradle` 与 `tools/vulkan-local/` **至今未提交**，
  本轮 0 个提交都不含它们。已在 `01-DEV-LOOP` §1.2 写明「后续任何一轮都应走
  `run-client.sh` 而不是裸 `./gradlew runClient`」。

- **是否已提交**：见本次提交（纯文档轮，无 `.java` 改动；**不带任何 trailer**）。

## 2026-10-05（五十八）— QD-04 定位与更正（登记的「3 个」实为 21 个）＋ QD-05 全仓 catch 审计

> **verdict = 两条登记在案的债都在本轮**第一次被真正查**。
> QD-04 的登记数字是**错的**（写 3 个、实测 21 个）；两条都配上了构建期守卫。
> 795 条单测全绿 + runClient 复验（vkdisp ERROR 仅剩环境事实 1 条）。**
> 任务来源 = `QUALITY-DEBT.md` §3「第 0 步」（QD-04 登记的下一步原文就是
> 「下一轮审查先 grep 定位这 3 个方法」）。

- **🔴 QD-04 的「已显著改善」结论作废**
  - 实测 **21 个** `>60` 行方法（口径：正文行数 = 闭合行 − 声明行，即排除声明行、含闭合行）。
  - 🔖 **这个「3」从来没被验证过**：QD-04 的原定下一步就是「下一轮审查先定位这 3 个方法」，
    结果没人真的数过 —— 一数就发现差 **7 倍**。与 G-08 旧口径（「无 static 可变状态」结论是错的）
    是同一类记账错误。
  - 人工验证了 **2 个样本**（`07` §九 纪律）：`FrameApi:663` 的 `drawFullscreen`
    闭合于 1019（文件共 1020 行，最后一行的 `}` 是类的），
    且**不存在下一个方法声明**；`FragmentOutputAdapter:196` 的 `parseOutDeclaration`
    闭合于 457，并专门 `grep` 确认**字符串里没有花括号**（排除配平被干扰的可能）。
  - 已分类：转译器核心 9 / 解析器布局配置 5 / **渲染编排 6**（`drawFullscreen` 一家 356 行）。
    按 QD-04 原话「核心转译逻辑的长方法可接受」⇒ 前 14 个可接受，渲染编排那 6 个值得拆。
  - 新增 `MethodLengthRatchetTest` 棘轮（基线 21，只许降不许升），
    失败时输出**可执行的排行榜**而不是只报一个数字。

- **🔴 QD-05 闭环：全仓 81 个 catch 块，只有 1 个是真问题**
  - 审计结果：**41 个已记录异常原文 / 40 个静默**。
  - 40 个静默块里绝大多数是**正当**的窄类型降级（`NumberFormatException` 14 个、
    `IOException` 12 个；抽查 `ShaderPackScanner:68` 是把 `|stat-failed` 记进缓存键文本，属带内记录）。
  - 🔖 **真正的那个**：`bridge/TerrainPipelineApi#blockAtlasSizeOrEmpty()`
    是 `catch (Throwable t) { return new int[]{0,0}; }` ——
    异常对象整个被丢掉，而这个值**每帧**喂进 `OfUniformManager`（两个调用点），
    尺寸错了 ⇒ 整条 OF uniform 静默走偏 ⇒ **画面不对但不报错**。
    这正是 `h33` 门控死开关那一族：**真错误伪装成默认值**。
    已改为**一次性** ERROR + 带异常原文（必须一次性，因为它每帧被调两次 ——
    与 `h34` 刚修掉的 499 行刷屏是同一类错误，不能重犯）。
  - 新增 `CatchThrowableVisibilityTest`：判据**不是「catch 了什么类型」**，
    而是「裸 `catch (Throwable x)` 必须用到 x，或在块内写明为何可以丢弃」。
    🔖 **为什么不一刀切禁止 `catch (Throwable)`**：本仓有正当的防御用法
    （`PackPrecompileScheduler:154` 明写「预编译失败不阻断切换，真正的加载会在同步路径里再试一次」），
    一刀切会逼人删掉必要的防御。配 3 条元测试（抓到无交代的 / 放过有注释的 / 窄类型不在范围内）。

- **🔖 分析器自身错了两次，都先怀疑数据再改代码**
  - 第一版报「0 个记录 / 45 个静默」—— **明显不可能**（`FullscreenPassHook:201` 明明有
    `LOGGER.error(..., t)`）⇒ 改脚本，不是下结论。
  - 第二版把 `} catch (X e) {` 对深度的净贡献算成 +1，实际是 **0**（闭合 try 块 + 开启 catch 块）。
  - 第三版修正后用**已知会记录的三处**做 sanity check，确认分类正确才采信数字。

- **🔖 一处按纪律自我否决的取证**
  - 首张截图是夜景（`dayTime=20411` ≈ 午夜），差点当成「画面变暗」的现象报上去。
  - 按 `07`「取证前固定时间/天气/视角，不让昼夜造成误判」，先 `get_time_and_weather` 确认，
  `set_time 6000` 重拍；并把那张夜景图**改名**为
  `h35-NIGHT-dayTime20411-not-a-regression.png` 留在证据目录里，让它自己说明白是什么。

- **🔴 本轮没做**：`FrameApi#drawFullscreen`（356 行）的拆分、其余 5 个渲染编排长方法；
  40 个静默 catch 的**逐个**语义复核（本轮只做了分类 + 抽样核验，已在证据里写明）。
  **Vulkan 后端验证依旧做不到**（本机无 Vulkan ICD），性能未测。

- **同步的文档**：`QUALITY-DEBT` 的 QD-04 更正 + 标「部分闭环」，QD-05 标「已闭环」；
  `evidence/h35-…` 全新（含 2 个人工验证样本的命令、审计分类表、棘轮与守卫说明）。**`CHANGE_LOG.md` 本条。**

- **是否已提交**：见本次提交（一个功能一个 commit；**不带任何 trailer**）。

## 2026-10-05（五十七）— 闭环 QD-01（连续七轮）＋ 抓住并修掉自己上一轮引入的日志刷屏

> **verdict = 把「规范写了、执行没跟上」这条债真正闭环（`.java` + 构建期守卫），
> 并靠 runClient 取证抓到**上一轮自己引入的一个新缺陷**（每帧 499 行 INFO）。**
> 787 条单测全绿 + runClient 复验：**vkdisp ERROR 仅剩环境事实 1 条**。

- **🔴 一处自我纠正（本轮最有价值的部分）：上一轮把日志改坏了**
  `h33` 拆 `ensureTargets` 时，把「`gbuffer terrain targets ready`」这条 INFO
  从「首次创建分支」挪到了 `ensureTargets` 末尾 —— 而 `ensureTargets` **每帧都跑**。
  ⇒ 本轮 runClient 实测：**一次运行刷了 499 行**完全相同的 INFO。
  这与 `h25` 的 M-01 埋点 600→250000、以及本文件自己注释里写的节流纪律，
  **是同一课**；上一轮刚把它写进注释，下一轮自己就犯了。
  ⇒ 已把该日志移回 `ensureColortex` 的「建好那一刻」，并新增守卫
  `MrtTerrainPassWiringTest#ensureTargetsHasNoUnconditionalInfoLog`：
  **`ensureTargets` 里出现无条件 `LOGGER.info` 直接让构建失败**。
  🔖 守卫同时反向断言「这条可诊断性不得为了不刷屏被删掉」——
  `h27` 记过 `MrtPlan` 槽 0 指纹恰好是黑、删掉诊断色就丢了区分能力那个坑。

- **轮一 · QD-01 闭环（连续七轮未动）**
  - 🔖 **先核实「欠债到底欠多少」**：`QUALITY-DEBT` 写「全主源码仅 2 处 `@Nullable`」——
    实测那 2 处**只是 javadoc 里引用原版签名**，`org.jspecify` **从未被 import 过**
    ⇒ 真实注解数为 **0**。债比登记的还多一点，但病根不是「漏标」，是**规范没有守卫**。
  - `bridge/` 全部 **10 处**可空返回值补 `@Nullable`：
    `MrtProbe#slotView` / `MrtTerrainPass#slotView` / `TerrainDrawCapture#current` /
    `TerrainDrawCapture#capturedFrom` / `TerrainPipelineApi#packTerrainForMrt` /
    `TerrainPipelineApi#derivedTerrainPipeline` / `VolumeStubs#view` /
    `DeviceApi#deviceInfoOrNull` + `ShaderCompileApi` 的 **2 个原版接口覆写**。
  - 🔖 **那 2 个覆写是本轮的额外发现**：已从 jar 反汇编核实，
    原版 `com.mojang.renderpearl.api.pipeline.ShaderSource` 的
    `getShader` / `getInclude` 在 class 文件里就带
    `RuntimeVisibleTypeAnnotations: org/jspecify/annotations/Nullable`
    ⇒ 我们的覆写**原本违反了自己的契约**，现已对齐。
  - 🔖 **不靠「写完就算」**：新增 `BridgeNullableContractTest`，扫描 `bridge/` 每个
    `return null;` 并要求其所在方法带 `@Nullable` ⇒ 新写一个会返回 null 的方法而忘了标注，
    `./gradlew test` 直接挂。**这才是「闭环」而不是「又写了一遍规范」。**
  - 🔖 **守卫自带 2 条元测试**：用「故意漏标注」的样本证明它抓得到，
    用「javadoc 里写 `return null;`」证明它不误报 ——
    **第一版守卫其实一条都不报**（正则误用 `matches()` 而非 `find()`，
    又把「类体深度」错当成「文件深度」），是元测试把它抓出来的。

- **🔴 剩余部分如实拆分**：`bridge/` 之外的内部 `return null` 仍有 **60 余处**，
  本轮**未做**（逐个核对调用点语义是另一轮的工作量）⇒ 登记为 **QD-06**，
  优先级下调的理由写进表里：守卫已建，规范不会再失效。

- **同步的文档**：`QUALITY-DEBT` 的 QD-01 移入「已闭环」并引用本条；
  新增 **QD-06**（剩余内部 `return null`）。**`CHANGE_LOG.md` 本条。**

- **一轮内的一次测试失败，是本轮引入的，已修掉**
  | 失败 | 根因 | 性质 |
  |---|---|---|
  | 元测试「守卫抓不到漏标注」 | 正则用 `matches()` + 类体深度判错 ⇒ 守卫形同虚设 | 🔴 **真 bug**（守卫本身无效） |

- **是否已提交**：见本次提交（一个功能一个 commit；**不带任何 trailer**）。

## 2026-10-05（五十六）— 🔴🔴 MCP 驱动 runClient：抓到 1 个 P0 回归 + 2 个真缺陷，全部修掉

> **verdict = 上一轮（`h32`）的三个 commit 里有一个让整条地形 MRT pass 每帧死掉的回归；
> 本轮靠 runClient 抓到并修掉，另修一个「静默失效」的死开关 + 一个日志刷屏问题。
> 783 条单测全绿 + runClient 三组 A/B 取证（C 组除环境事实外零 ERROR）。**
> 证据：`evidence/h33-mcp-driven-runclient-three-defects.md`（含 4 张 MCP 截图与像素级判据）。
> 任务来源 = 用户指令「用 MCP 驱动游戏进行测试验证。先拉取最新的推送，再进行开发」。

- **🔴🔴 本轮最重要的产出不是新功能，而是这个事实**：
  `h32` 的三项改动**从未跑过 runClient**（当时由用户协助），
  而其中 `273b94a`（GAP-012 的 3D 桩）**建不出来** ——
  原版 26.3 对 `depthOrLayers > 1` **无条件**抛
  `UnsupportedOperationException: Array or 3D textures are not yet supported`。
  它在 `ensureTargets` 里抛 ⇒ **把创建 `atlasSampler` 的那一步整个跳过**
  ⇒ `atlasSampler` 永远 `null` ⇒ 每帧 `setUniform(name, view, null)`
  ⇒ 实测一次运行 **1940 条 pass 失败 + 2702 条 setUniform 异常**。
  **拉取前 pass 能跑（带一处静默 UB），拉取后 pass 完全不可用。**

- **轮一 · GAP-014（原版无 3D / 数组纹理能力）—— 新登记**
  - 从 `minecraft-patched-26.3.0.41-beta.jar` 抽 `FrontendGpuDevice.class` **逐字节码核实**
    （`javap -p -c`）⇒ 抛点在 `com.mojang.renderpearl.frontend` = **前后端共用层**
    ⇒ **与 OpenGL/Vulkan 后端无关**，不是本机环境现象
  - `VolumeStubs`：`init()` 改为**探测 + 记录**（不再抛、不再每帧重试）；
    `view()` 返回 `null` ⇒ 沿用 GAP-012 已定的「**不绑 + ERROR**」响亮失败路径
  - 🔖 **一处自我纠正**：第一版把 ERROR 放在「探测到不支持」时报 ⇒ 实测发现
    **包根本没用到 sampler3D 也会吵**（`init()` 无条件调）
    ⇒ 改成**按需求**（`view()` 真被调用才报），只报一次

- **轮二 · `ensureTargets` 的级联：一个 sampler 的问题炸掉了整个 pass**
  - 根因定位到**行号**：`colortex` 在第 468 行已赋值，`atlasSampler` 在**第 484 行**（抛点下一行）
  - 下一帧 early-return 只看 `colortex != null` ⇒ **半初始化被当成已初始化** ⇒ 永不补建
  - 拆成四个互相独立的 ensure（`ensureColortex` / `ensureShadowStubs` / `VolumeStubs.init` /
    `ensureAtlasSampler`），采样器改为**每帧幂等兜底**
  - 加一道**可定位的停机点**（`atlasSamplerReady()`）——
    否则症状是看不出根因的 `textureView and sampler must both or neither be null`

- **轮三 · 能力门控是**死开关**（静默失效，与已闭环的 QD-02 同族）**
  - `PackCapabilityGateSwitch` 反射时用的是**配置键名** `pack.capabilityGate`，
    而真实 **Java 字段名**是 `CAPABILITY_GATE` ⇒ 每次 `NoSuchFieldException`
    ⇒ 被 `catch (Throwable)` 吞掉 ⇒ **恒返回默认关**
  - 实测原文：配置写 `capabilityGate = true`，日志打「（pack.capabilityGate=false）」
  - 修法：拆出独立 `FIELD_NAME`；`NoSuchFieldException` **单独捕获**并走可见报错路径
  - 🔖 **被单测抓出来的自身错误**：本来打算在开关类里直接打日志，
    结果**单测 classpath 上没有 slf4j**（`PackBooleanOptionTest` 炸成
    `NoClassDefFoundError: org/slf4j/LoggerFactory`）
    ⇒ 改为「记录原因 + `reflectionFailure()`」，由 `PackTerrainSource` 走既有
    `TranslateDiagnostic` 管道输出。**诊断手段不该把无关测试拖挂。**

- **附带修**：渲染期重复 ERROR 节流（实测 2702 行 / 次 → 1 行）。
  与 `h25` 的 M-01 埋点 600→250000 是**同一课**：热路径上的无节流日志会把 I/O 变瓶颈。

- **✅ 三组 runClient 取证（A/B/C）**
  | 组 | `pack.capabilityGate` | `mrt.enabled` | 结果 |
  |---|---|---|---|
  | A | `true` | `true` | 门控真的生效：`CAPABILITY_GATE_APPLIED`×18、关掉 **9 个**包特性、输出槽位 **8→1** |
  | B | `false` | `false` | `terrain slot clear = NEUTRAL`；天空偏绿像素 **79.01%(A) → 0.00%(B)** |
  | C | `false` | `false` | **除环境事实外零 ERROR**；`pass frames=600`、pass 失败 **0** 条 |

  🔖 **A 组顺带正面回答了 GAP-008 / GAP-009**：`ADVANCED_MATERIALS` 被门控关掉后，
  地形**可见且有光照**，不是纯黑剪影。

- **🔖 对 `h32` 的两处事实更正**（不沿用）
  1. BSL v10.1.8 的 `sampler3D` 是 **3 个**（`lighttex0`/`lighttex1`/`voxeltex`），
     **没有**无下标的 `lighttex` —— `h32` 写「4 个」。
  2. `h32` §6.1 的验收判据「应出现 `VOLUME_3D=4`」**不可达且前提错误**：
     那 3 个都不在 `gbuffers_terrain` 的采样器里（该文件自身 `uniform samplerXX` 数为 0），
     且原版根本建不出 3D 纹理 ⇒ 实际 **`VOLUME_3D` 为 0**。

- **🔴 环境事实登记**：本机 WSL2 **无 Vulkan ICD**（`libvulkan.so.1` 缺失）⇒ 客户端跑在
  **OpenGL** 后端 ⇒ `P0.2` 断言失败。按 `07` X42，**本轮任何 Vulkan 专属结论都不可在本机验证**；
  已如实登记，未把「OpenGL 上成立」写成「Vulkan 上成立」。
  🔖 本轮修的三条里，D1 来自原版 API 字节码、D2/D3 是纯逻辑/资源生命周期，**均与后端无关**。

- **同步的文档**：`13-GAP-REGISTRY` 新增 **GAP-014** 完整条目 + GAP-012/013 状态补上 `h33` 取证；
  `evidence/h33-…` 全新（含 4 张 MCP 截图 + 像素级判据 + 三份复算命令）。**`CHANGE_LOG.md` 本条。**

- **一轮内的三次测试失败，全部是本轮引入的，已全部修掉**
  | 失败 | 根因 | 性质 |
  |---|---|---|
  | 开关测试 2 条 | 单测 classpath 无 slf4j，诊断类自己打日志炸了无关测试 | 🔴 **真 bug**（诊断手段拖挂测试） |
  | 开关测试 1 条 | 我自己的注释里写了被断言的字面量 | 🟡 测试写法问题（改为只看非注释行） |
  | 守卫测试 1 条 | 报错时机写成「探测即报」，实测是噪声 | 🔴 **真设计问题**（改为按需求报） |

- **是否已提交**：见本次提交（一个功能一个 commit；**不带任何 trailer**）。

## 2026-10-05（五十五）— 🔴 连续三轮功能开发：GAP-009 能力门控落地 + sampler3D 维度 UB 修复 + 诊断色泄漏修复

> **verdict = 主源码编译通过 + 单测全绿 + 文档同步。三项都是「修掉真实缺陷」而非「加新功能」，
> 且都是静默型（不报错、不崩溃、画面慢慢变坏）。**
> **⏳ runClient 未跑**（本轮按用户指示「测试由用户辅助」）—— 但 `.java` 有改动，按 `07` 规**必须**取证。
> 证据与待验证清单见 `evidence/h32-three-round-dev-gate-sampler-clear.md` §六。

- **轮一 · GAP-009 方案 A 落地（用户 2026-10-04 已裁决，实现本轮补上）**
  - 新增 `config/PackCapabilityGate`（纯逻辑，16 条单测）+ `pack/properties/PackLangFile`
    （读 `shaders/lang/*.lang`，12 条单测）+ `pack/PackCapabilityGateSwitch`（配置读取侧）
  - 接线在 `pack/PackTerrainSource`；开关 `pack.capabilityGate`（**默认关**）
  - 🔖 **为什么必须读 lang**：BSL 声明「本选项依赖资源包材质贴图」的**唯一**机制是
    **显示名末尾的 `*`**（实测 `en_US.lang` **19 条**）⇒ 不读就只能硬编码 `PARALLAX`；
    而 Complementary 同样有视差却**零星号**（实测，复用原版 atlas、零外部依赖）
    ⇒ 硬编码会砍掉一个完全可用的包特性（违反 X27）
  - 🔖 **一处自我纠正**：首版把门控接在 `PackCompositeSource`（composite/deferred/final
    三个**全屏**步）—— **接错了**。GAP-009 的实测证据全部取自**地形**；
    在那里门控会在「地形片元没接线」时**白白砍包特性** ⇒ 已改接 `PackTerrainSource`，
    并在原调用点留注释说明**为什么不在那里**（防后来者又接错）
  - 🔖 **一处真实 bug（单测抓到）**：星号在链路上有**两处表示**（`PackLangFile` 剥掉星号 +
    门控按「标签带星号」判定）⇒ **门控恒空转且看起来完全正常**。已收敛为「星号只有
    `starMarkedOptions` 一处表示」。教训写进代码注释：**同一个语义不要有两处表示**
  - 门控三条「不」：不硬编码选项名 / 不关非布尔 / 不关「本来就是关的」（否则日志误判归因）

- **轮二 · 🔴 修 `sampler3D` 被喂 2D 视图的 Vulkan UB（本轮亲自扫包定位）**
  - 实测扫 BSL v10.1.8 全包：`sampler2D` 34 / `sampler2DShadow` 3 / **`sampler3D` 4**
    （`lighttex` / `lighttex0` / `lighttex1` / `voxeltex`）
  - 旧实现对未识别名字一律 `default -> atlas` ⇒ 这 4 个**拿到 2D 视图** =
    **描述符类型不匹配 = UB**，且本机**无 validation layer ⇒ 一层都不报错**
  - 新增 `pipeline/model/SamplerDimensionPlan`（**从声明的类型**读维度，不从名字；15 条单测）
    + `bridge/VolumeStubs`（4×4×4 `RGBA8` 全 0 的 3D 桩，语义 =「无体积光照/无体素数据」）
  - 🔖 新增「响亮失败」：cube 采样器与**不认识**的类型 ⇒ **不绑** + ERROR。
    宁可让 draw 抛 `Missing uniform`（可定位），也不拿 2D 视图冒充
  - 🔖 两处 **API 事实查 sources jar 核实、未猜**：`CommandEncoder#clear*` 返回 **`void`**
    （不能链式）；`writeToTexture(ByteBuffer,…)` **只写单层** ⇒ 3D 纹理逐层要 4 次，
    为「恒为 0」的内容不值 ⇒ 改用 `clearColorTexture` 一次清掉
  - ⚠️ **明确不承诺**：包的体积光 / 体积 AO 效果在本引擎上**不成立**（无真资源，
    且按 GAP-009 裁决不打包第三方光照/材质资产）

- **轮三 · 🔴 修诊断清屏色泄漏进用户画面（绿天空，`h27b` §六 定位后本轮修）**
  - 旧 `diagnosticClear` **无条件**把槽 0 清成纯绿；我方 pass **只画地形** ⇒ 天空那片
    **从不被画进 gbuffer** ⇒ 保持纯绿 ⇒ composite 采 `colortex0` ⇒ **绿天空进最终画面**
  - 新增 `pipeline/model/TerrainSlotClear`（纯逻辑，10 条单测）分两模式：
    **诊断模式**保留高对比逐槽色；**生产模式**零值清屏
  - 🔖 **为什么不只是「把绿改成黑」**：`MrtPlan` 给槽 0 的**指纹恰好是 `0.0`（黑）**
    ⇒ 「什么都没画」与「画了但很暗」在截图上**无法区分**（旧实现的注释记录了这个踩坑）。
    直接改黑 = **删掉一项可诊断性** ⇒ 该能力**保留、只限定作用域**
  - 🔖 判据是**调试视图是否激活**（`mrt.enabled`）而不是「`mrt.terrain` 是否开着」——
    取证时两者常同时开，但用户看到的画面必须是生产语义
  - 保留可诊断性的另一半：生产模式打一次 INFO 明说「天空黑是**预期行为，不是故障**」
  - A/B 逃生舱 `mrt.slotDiagnosticClear`（**默认关**，开启即 WARN 自报「会呈现假色天空」）
  - ⚠️ **黑天空本身仍未修**，且**不是**本条能修的：正确天空要由包的 gbuffer 程序画 ⇒ **M-04（未做）**

- **同步的文档**：`13-GAP-REGISTRY` 新增 **GAP-012**（sampler 维度）与 **GAP-013**（诊断色泄漏）
  两条完整条目 + GAP-009 补「A 已实现」段（含两处自我纠正）；`evidence/h32-…` 全新（60 行，
  含三份复算命令与交给用户的 runClient 验证清单）。

- **一轮内的三次测试失败，全部是本轮引入的，已全部修掉**（记录在案，因为其中两条是真 bug）：
  | 失败 | 根因 | 性质 |
  |---|---|---|
  | 门控 8 条全挂 | 星号两处表示不一致 | 🔴 **真 bug**（静默空转） |
  | lang 转义 / 续行 2 条 | 漏了 `§` 的 unicode 形式与反斜杠续行 | 🔴 **真 bug**（判据会丢） |
  | 影子别名守卫 2 条 | 断言字面量（`case "shadowtex0"…`）随重构改变 | 🟡 测试写法问题（意图未变） |
  ⚠️ 影子守卫那条的处理值得记：**改的是断言的字面量，不是放松断言** ——
  守卫的不变式（「默认走桩」「不可绑时不喂错维度」）一条没少，还补了一条新的维度守卫。

- **是否已提交**：见本次提交（一个功能一个 commit；**不带任何 trailer**）。

## 2026-10-04（五十四）— 🔴 防误判清理：删 `AGENT_CONTEXT` 两个历史快照；给 5 份过时文档加状态头；DLSS 裁决关闭

> **verdict = 纯文档轮（无 `.java` 改动，按 `07` 规不需 runClient）。目标是让下一个 AI 不可能读错状态。**
> 任务来源 = 用户指令「把所有旧文档、有问题的文档全清了，特别 review 里的，不要让其他 AI 因为文档误判」。

- **本轮改了什么**
  1. **删除 `docs/AGENT_CONTEXT.md` 的两个历史交接快照（原 §9 / §10，~2187 行，占该文件 86%）**，
     文件从 **205KB → 30KB**。删除理由不是「过时」而是**主动误导**：该文件是**跨会话自动加载**的
     上下文源，AI 读到「§10 快照：下一步做 X」会当成当前任务，而不是去读 `13-GAP-REGISTRY` 的真实状态。
     原位置留了「已删除 + 当前状态去哪读」的导航块。**§0–§8 活文档全部保留。**
  2. **新建 `review/README.md`**：给出「唯一真源对照表」+ 本目录 7 份文件的状态一览 + 维护规则。
  3. **给 5 份文档加⛔/🟢 状态头**（不删，见下）：`review/` 下 4 份 + `docs/18-PARALLEL.md`。
  4. **`docs/00-INDEX.md` 加「给 AI 读者：状态只能从这里取」块**（真源对照表 + 已知过时陷阱），
     并把 `review/` 纳入索引、把 `18-PARALLEL` 标为已过时。
  5. **GAP-009 裁决简报状态从「待裁决」改为「已裁决」**，并纠正其两处已被调研推翻的前提。
  6. **同轮早前的三条裁决落地**（见下条，此处一并记）：DLSS 完全不做 / mixin 依据撤回 / GAP-009 定 A 形态。

- **为什么保留过时文档而不是全删**
  它们承载删不掉的东西：**X 系列红线的历史出处**（`07` 的 X1–X56 全部来自 `2026-09-30-代码质量审查与改进方案.md`）
  与**审查方法学**（「统计方法长度必须排除声明行」「报数前先人工验证 1 个样本」等，已升格为 `07` §九清单条款）。
  ⇒ 做法是**加状态头 + 指向真源**。

- **影响的文档**：7 份（`AGENT_CONTEXT` / `00-INDEX` / `18-PARALLEL` / `13-GAP-REGISTRY` / `07-CONSTRAINTS` /
  `02-OVERVIEW` / `review/README` 新建 / `review/` 5 份加头）。**`CHANGE_LOG.md` 本条。**

- **自检**：残留矛盾 grep（仅命中「旧记载=错」的纠错句本身）；表格列数校验通过；
  `review/` 7/7 全部带状态标注（**自检抓到 1 份漏标 `本机构建与runClient验证.md`，已补**）；
  `docs/` 全域 grep DLSS = 0 命中。

- **备份（沙箱内 `rm` 不可靠，改文档前先备份到仓库外）**：
  `D:/Code/Minecraft/_vkdisp_doc_backup_20261004_2047/`（含 `AGENT_CONTEXT.md` 原文 205KB、
  `18-PARALLEL.md`、7 份 `review/`、`00-INDEX.md`，sha256 已校验一致）。

- **是否已提交**：❌ **未提交**（本轮全部为 M）。

## 2026-10-04（五十三）— 🔴🔴 两个根因假设**都被证伪**；闪烁**当前不可复现**；**撤回 `h24` 的归因**；另立 X52 / X53 / X54

> **verdict = 修掉一个真实的 Vulkan 未定义行为（但它不是闪烁的原因），并诚实地把「归因不成立」写进文档。**
> 任务来源 = `AGENT_CONTEXT` §10.32 ④ 剩下的四个候选。

- **本轮改了什么**
  1. **修 Vulkan 未定义行为**：包地形片元把 `shadowtex0/1`、`shadowcolor0` 绑到
     **本 render pass 自己的读写附件**（深度附件清屏 0.0 且地形写深度；colortex0 被清屏并写入）
     ⇒ 规范明文禁止，且**不报 validation error**（本机无 validation layer，§9.4.15）⇒ 留着就是赌驱动心情。
     改绑**专用 1×1 桩纹理**（`ShadowStubs`：1×1 `D32@0.0` + `RGBA8@0`，**永不作附件**）+ 6 条回归测试。
  2. **两个 A/B 开关**（把历史变更做成同二进制可切换变量）：`mrt.shadowStubs`（默认 `true`）、
     `mrt.gap010Regression`（默认 `false`）；开启即 **WARN 自报**，便于按 X51 从日志确认。
  3. **另修两个真实缺陷**：`PackTerrainMemoTakeTest#methodBody` 的子串截断、
     `x11_capture.py` 的 X11 `request length` 字段算错。
  4. 文档：新增 `evidence/h27-…` + `h27-images/`（9 张）；`AGENT_CONTEXT` **§10.33**、
     **§10.32 标注作废**；`13-GAP-REGISTRY` GAP-011 六条新行；`evidence/README.md`；**X52 / X53 / X54**。
- **✅ A/B 结果（四组，除待测开关外配置完全一致）**

  | 组 | `shadowStubs` | `gap010Regression` | 帧数 | 黑色占比 | 闪烁 |
  |---|---|---|---|---|---|
  | A | `true` | `false` | 18 | 47.39% ×18 | 无 |
  | B | **`false`（故意恢复 UB）** | `false` | 18 | 50.00% ×18 | 无 |
  | C | `true` | **`true`（精确复现前修复）** | 18 | 47.39% ×18 | 无 |
  | D | 出厂配置复核 | `false` | 10 | 47.38% ×10 | 无 |

  X51 逐组确认：A 有 `shadow stubs ready`；B 有 `mrt.shadowStubs=false` 自报且**无** stubs ready；
  C 有 `mrt.gap010Regression=true` 且 **`resourceLoad/ERROR` 回到 12 条**（与 `7206d6d` 记录逐条一致）。
  ⇒ **四组全部单相位，一次都没复现闪烁（82 帧）**。
- **❌ 候选①证伪**：B 组**故意**恢复别名 UB，仍然不闪 ⇒ **不是**原因（缺陷本身仍保留修复）。
- **❌ 候选②证伪**：C 组把 `7206d6d` 之前的状态**精确复现**（12 条 `resourceLoad/ERROR`）仍不闪；
  且 C 组占比与 A 组**完全相同** ⇒ 该修复**对画面零影响**，更非闪烁原因。
- **🔴🔴 撤回 `h24` 的归因（本轮最重要的产出）**：`git log` 显示
  `h21`(有闪烁) → `h22`(有闪烁) → **`7206d6d`** → `h24`(无闪烁) ⇒ `h24` 的「单变量对照」
  **至少有两个变量**；既然 `7206d6d` 已证伪 ⇒ 「闪烁 = M-01 管线替换」**不成立**，
  闪烁消失的真实原因**至今未知**。连带作废 §10.32 的 2×2（两格分属修复前后代码）。
  🔶 `h22` 的**判据**仍有效（独立于代码的测量）。
- **🔶 一个从未登记的名义变量：窗口尺寸**
  所有「有闪烁」证据（`h16`/`h17`/`h19`/`h21`/`h22`）都在 **854×480**（= MC `glfwCreateWindow` 默认尺寸，
  说明当时没人改过窗口）；`h25` 之后全在 **930×577**（改过、**无记录**）。
  ⚠️ **不是充分条件**（`h24` 同为 854×480 却无闪烁）。
  🔴 **本机不可测**：WSLg/XWayland 无 WM —— ctypes `XResizeWindow` 段错误、
  自研 `ConfigureWindow`(opcode 12) 请求无错但尺寸被服务端改回、`F11` 全屏注入成功但 `fullscreen` 仍 `false`
  ⇒ 记为**已知阻塞**，不做无根据推测。
- **🔴 GAP-011 的诚实状态**：根因**仍未定位**；候选①②已证伪；③④（15 varying 对齐 / 29 条 builtins 取值）**未测**；
  🔴 **当前 HEAD + 当前配置复现不出闪烁** ⇒ **缺少可观测现象，无法继续归因**。
  复现途径（都不是保证）：① 换一台**有窗口管理器**的机器；② 回到 h16–h22 的**世界存档状态**；
  ③ 换 GPU/驱动（当前 lavapipe **软件**渲染，「UB 表现依赖驱动」这条尤其相关）。
- **🔴 取证有效性披露**：本轮中段发现**另一个会话在同一工作树并行跑客户端**
  （它改了 `build.gradle` 加 `clientIso`，正是为了解决「并行会话互相覆盖」）
  ⇒ 有一段时间 `run/` 下同时存在两个客户端。逐项评估：**日志无交叉写**（A/B/C 三份日志各自
  **恰好 1 条**管线注册 + **恰好 1 条**开关自报）、**运行时取值全对**（靠 X51 的自报，不信配置文件）、
  截图无异常迹象（⚠️ 两个窗口同尺寸，**靠截图无法完全排除**抓错窗口）。
  🔖 这轮**正面验证了 X51**：配置被覆盖好几次，靠运行期自报才始终能判读「变量是否生效」。
  🔴 另有一趟运行**被配置回写毁掉**（原计划检验窗口尺寸的那趟实际是第二趟 C 组）。
- **新立三条红线**
  - **X52** 单变量对照的两臂之间若共享状态（代码/依赖/配置/世界存档/窗口尺寸）变过 ⇒ 该对照**无效**。
  - **X53** 用**没登记过名义变量**的历史证据做因果推断（本例：窗口尺寸正好把两类证据完全分开）。
  - **X54** 假设「后台任务结束 = 游戏结束」—— 被 SIGTERM（exit 143）后**客户端 JVM 会存活**，
    两个客户端并存抢 `session.lock`；**孤儿客户端退出时整份重写 `run/config`**
    ⇒ 这是 **X50 写回竞态的精确根因**（不是「kill 后立刻改」）。
- **取证环境**：每组均 `set_time(6000)` + `set_weather(clear)` + `look(yaw=90,pitch=0)`，不让昼夜误判。
- **测试**：`./gradlew build` exit 0，**712 单测全绿**；残留游戏进程数 = 0。

## 2026-10-04（五十二）— ✅ **2×2 补齐**：8 附件不是闪烁触发条件；另立 X50 / X51

> **verdict = 把闪烁的触发条件收敛到唯一一个：包自己的地形片元。**
> 任务来源 = `evidence/h25-…` §5 定的单变量实验。

- **本次改了什么**：
  1. 执行单变量实验：`packTerrainShader=false` **且** `mrt.attachments=8`
     （8 = 开启包片元时冻结出的槽位数）；6 帧连拍 + h22 校准判据；
  2. 新增证据 `evidence/h26-…` + `h26-images/` 六帧；`AGENT_CONTEXT` §10.32；`evidence/README.md` 索引；
  3. 新增 **X50 / X51** 两条纪律（本轮真实踩到，见下）。
- **✅ 结论：四格补齐，两个候选各自被单独排除**

  | 组 | `wireTerrain` | `packTerrainShader` | 附件 | 6 帧黑色占比 | 闪烁 |
  |---|---|---|---|---|---|
  | `h21` | ON | ON | 8 | 52.81%×3 + **99.89%**×3 | **有** |
  | `h24` | OFF | ON | 8 | 51.3% ×6 | 无 |
  | `h25` | ON | OFF | 3 | 0.38% ×6 | 无 |
  | **本轮** | **ON** | **OFF** | **8** | **0.38% ×6** | **无** |

  ⇒ M-01 管线替换（h25 与本轮都开着，都不闪）与 **8 附件的 pass**（本轮开着，不闪）
  **各自被排除** ⇒ 剩下**唯一**解释是**包自己的地形片元**，与其附件数无关。
- **🔴 本轮真实踩到：`attachments=8` 第一次没生效**（日志打 `colorTargets=3`）。
  排查发现配置值在我改完后**又被改回**，连从未碰过的键也一起变了。
  🔖 **根因比 X36 深一层**：`game_procs.sh kill` 虽已确认进程退出（`残留=0`），
  但**上一个客户端的关服配置回写**与我的编辑**存在竞态**。
  ⇒ 立 **X50**：`kill` 后 `sleep 12`、改完**直接读文件原文**、启动后再用**日志里的运行期证据**二次确认。
  ⇒ 立 **X51**：取证前只确认配置文件不算，**必须从日志确认变量已生效**
  （X46「先证明被测物在跑」的延伸）。
- **🔶 剩下的候选**：7 sampler 绑定 / `sampler3D lighttex0/1` vs 原版 2D lightmap /
  15 varying 与顶点适配层对齐 / 29 条 builtins 取值。
  🔖 **方法论障碍**：`packTerrainShader=true` 时槽位被 `MrtPlan.slotCount()` 里的
  `packOutputCount()` **强制**为 8（**优先于配置**）⇒ **无法用配置**做「包片元 × 更少附件」的格子；
  要分离这个**交互**必须改代码给 `slotCount()` 加上限参数。
- **取证环境**：开工即 `set_time(6000)` + `set_weather(clear)` + `look(yaw=90,pitch=0)`
  —— 用户明确提醒**不能让夜晚造成误判**。
- **测试**：705 单测全绿；`./gradlew build` exit 0；残留游戏进程数 = 0。

## 2026-10-04（五十一）— 🔴 **推翻 h24**：闪烁是**包自己的地形片元**造成的，不是 M-01 + 附带闭环 QD-02

> **verdict = 单变量对照推翻上一轮的因果结论，并给出一条可用的规避路径。**
> 任务来源 = `AGENT_CONTEXT.md` §10.30 ⑤「闪烁为何由管线替换导致」（三个候选均未验证）。

- **本次改了什么**：
  1. 补上 `h21`/`h24` 都缺的**第三个格子**：`wireTerrain=ON` + `packTerrainShader=OFF`；
  2. 新增证据 `evidence/h25-…` + `flicker-ab/` 六帧；把 §10.30 的结论**就地标注作废**（保留作历史）；
     新增 `AGENT_CONTEXT` §10.31；`evidence/README.md` 索引；
  3. 🔖 **附带闭环 QD-02**（`QUALITY-DEBT.md` §3 的强制回路要求每轮第 0 步查本表）：
     `vkdisp.debugLog` 此前**只有定义与热重载快照、零消费点** ⇒ 开关它**无任何可观察效果**、
     **比没有更误导**。补 **3 处真实消费点**，全部**节流**：`OfUniformManager`（每 300 帧报 uniform
     键数与前几个键名）、`MrtTerrainPass`（第 300/1200 帧报附件数 + 深度格式 + 挂的是原版还是包的片元）、
     `FullscreenPassHook`（原本**无条件**输出的 uniform 传参周期行改为受控）。实测日志已见 `[qd-02]` 行。
- **🔴 结论一：`h24` 的「闪烁根因坐实 = M-01 管线替换」不成立。**
  `h21`/`h24` 两组**同时动了两样东西**（`wireTerrain` 与 `packTerrainShader`）⇒ 只能证明**相关性**。
  本轮单变量：`wireTerrain=ON` + `packTerrainShader=OFF` ⇒ **无闪烁**
  （黑色像素占比 **0.38% ×6**，六帧一致，全部落在 h22 校准判据的「有内容相位」）
  ⇒ **M-01 管线替换开着、闪烁照样消失** ⇒ 触发条件是**包自己的地形片元**。
- **🔴 结论二：`h24` 的「尴尬结论」也作废。** 它说「M-01 就是通道，关掉 = 关掉通道，
  所以『用替换管线接管原版地形绘制』这条路线代价过高」。实测 `packTerrainShader=false` 时
  **M-01 完好**（`wired` 6 条）+ **多附件 pass 照跑**（`terrain drawn into`）+ **无闪烁**
  ⇒ 两者无必然联系，触发条件在包片元那一侧且**可单独关闭**。
  ⚠️ 代价要说清：关掉它 ⇒ 地形回到原版 `core/terrain` ⇒ **GAP-003 的 gbuffer 语义重新落空**。
- **X46 自证**：`M-01 wired` **6 条**、`terrain drawn into` **1**、
  `pack terrain fragment disabled by config` **1** ⇒ 三件事都**确实生效**，
  本轮既不是「通道没开」也不是「变量没读到」。
- **🔶 收敛后的新问题与它的切分点**：开启包片元时**槽位数冻结成 8**
  （实测 `colorTargets=8`；关闭时为 1 或 3）⇒「闪烁」目前与**两件事同时**变化：
  ① 包片元本身、② **附件数 3 → 8**。
  **下一轮第一实验（单变量）**：`packTerrainShader=false` **且** `mrt.attachments=8`
  ⇒ 出现闪烁 ⇒ 触发条件是 **8 附件的 pass**（lavapipe 附件压力方向），与包片元无关；
  仍不闪 ⇒ 确凿是**包片元本身**（7 sampler 绑定 / 15 varying / `sampler3D lighttex0/1` 三者之一）。
  ⚠️ 正是 **X49** 要求的「≥2 候选能解释症状时先做单变量对照」——
  `h16`/`h17` 连续两轮押错方向的根因。
- **测试**：705 单测全绿；`./gradlew build` exit 0；残留游戏进程数 = 0；`mrt`/`mixin` 配置已还原。
- **⚠️ 未还原（刻意）**：`run/options.txt` 的 `pauseOnLostFocus=false` —— h21–h24 各轮取证都依赖它
  （否则失焦即暂停、画面停渲、截图作废）；世界存档的时间/天气/视角为保证可复现亦未还原。

## 2026-10-04（五十）— 审查改进项落地（纯文档）：T5 豁免 mixin / 升版回归清单 / WSL 硬要求 / QUALITY-DEBT 登记 / AGENT_CONTEXT 过时修复

> **verdict = 文档轮（审查改进落地）**。任务来源：`review/2026-10-04-文档清理后审查.md`（用户审阅后指令「X-01 选方案 A，其余改进项全部写入文档，不改代码，然后推送」）。
> **未改产品代码**（纯文档 + 新建 1 份质量债文档，无 `.java` 改动 ⇒ 不需 runClient）。

- **本次改了什么**：
  - **X-01（方案 A）**：`07-CONSTRAINTS` **T5** 改写 —— 「业务包」定义为 `pack/`·`glsl/`·`render/`·`pipeline/`·`screen/`，**不含 `mixin/`**；`mixin/` 装配层豁免 T5（注入原版方法时签名级引用不可避免），仍受 X25 约束（只转发不写业务）。
  - **C-04**：`07-CONSTRAINTS` 新增 **§1.4.1 NeoForge 升版 mixin 签名回归清单**（4 步：grep 签名 → 改 MixinTargets → runClient → hit 日志），与 `06-MIGRATION` R1–R9 同源。
  - **C-07**：`08-TESTING` §9 之后新增 **§9.1 WSL 端每轮必报运行时数据**（B1 帧时间 / GC 频率 / 1h 内存增量），缺三项的轮次视为未完成验收。
  - **D-01 + D-02**：`AGENT_CONTEXT` 行 1030 三支柱表「兼容」现状改写（GAP-003 已通 h08/h09、当前阻塞 = GAP-011 管线替换代价）；§1 顶部补 2026-10-04 最新焦点指针。
  - **D-03 + D-04**：`00-INDEX` 行 5「最后整理」更新为 2026-10-04；`04-SPEC` §3.2 转译组件清单后补「两个按需适配器段」说明（⑦½ `DrawBuffersSlotAdapter` / 7¾ `DerivativeProbeAdapter`，默认关，不算第 9 段）。
  - **新建 `docs/QUALITY-DEBT.md`**（无编号文档，类似 `AGENT_CONTEXT`）：登记 4 项代码质量债 —— **QD-01** `@Nullable`（G-02 连续六轮未动）/ **QD-02** `debugLog` 死开关（G-03）/ **QD-03** 静态可变字段 50→95 / **QD-04** 3 个 `>60` 行方法待定位；附「每轮迭代第 0 步」闭环回路（避免「30 分钟即可关闭的债跨六轮」）；§2 保留已闭环历史（F-04/F-05/F-06）。
  - `00-INDEX` §2 登记 `QUALITY-DEBT.md`。
- **为什么改**：审查发现「代码本身健康，真正持续漏水的是质量债闭环回路缺失 + 两条文档过时」。
  用户指令明确：X-01 选方案 A、其余改进项写入文档、不改代码。本轮把文档过时与规范类（D-01/D-02/D-03/D-04/X-01/C-04/C-07）直接落实，代码改动类（C-01/C-02/C-03/C-06）登记到 QUALITY-DEBT 待后续轮次。
- **测试**：纯文档轮，无代码改动；`grep` 复核 `07` T5 / `08` §9.1 / `AGENT_CONTEXT` 行 1030 / `QUALITY-DEBT` 已登记。
- **提交**：本轮改动随本条目一并提交并推送。

## 2026-10-04（四十九）— 文档清理：修复 `13` 三处结构错误；同步 `13`/`04`/`00` 与 h08–h24 的新事实

> **verdict = 文档轮（清理）**。任务来源：用户指令「清理文档中的错误和冲突、规范化」。
> **未改产品代码**（纯文档修正，无 `.java` 改动 ⇒ 不需 runClient）。

- **本次改了什么**：
  - `13-GAP-REGISTRY.md` 修复**三处结构错误**：① 删除 §1 表头前漂浮的 `h24` 内容初稿
    （`3548b08` 误粘贴，与 GAP-011 条目内最终版逐字重复，且含未渲染的 `U274c` 转义）；
  ② 恢复三支柱重写轮（`a218297`）丢失的 `## 2. 字段说明` 标题（§1 直跳 §3）；
  ③ 把 `05854eb` 追加到文末的 `h10` 进度段**并入 GAP-008 条目**（h13 段之前，按证据时序），
    并把其中已被 `h12` 实验证伪的「候选 3 = lmCoord 映射错误」结论改为带 `h12` 证伪标注的准确表述。
  - `13-GAP-REGISTRY.md` **GAP-004 状态**：「块尚无消费者——片元仍是原版 `core/terrain`」
    已过时（`h08` 起片元 = 包 `gbuffers_terrain`，42 块成员 + 5 sampler 绑定，`VkDispBuiltins` 608 字节环每帧填值）。
  - `04-SPEC.md` **§5.0 登记表 + §5.0.3 / §5.0.5**：M-01 / M-01b 状态与 §5.0.5「仍未完成」表内
    4 行（包片元接线 / SPIR-V 接进派生管线 / 附件槽位 / uniform 供给）均已被 `h08`/`h09` 完成，逐行更新；
    §5.0.5 结尾「下一轮」指引改写为现状 + 剩余缺口清单。
  - `00-INDEX.md`：`13` 号文档描述从「GAP-003/005/006」更新为「GAP-001–011」。
  - `CHANGE_LOG.md` 自身：删除 `bde173a` 误插入到文件中部的重复文件头（`# 变更记录` + 模板 + `---`）。
- **为什么改**：`3548b08`（`test(gap-011)`）与 `05854eb`（`test(gap-008)`）各在 `13` 留下一段
  位置错误的漂浮内容；`4f72bd8` 只重写了 GAP-010/011 两条，`h08`/`h09` 的成果未同步进
  `04-SPEC` 的注入点登记表与 §5.0.5，条目写着「⛔ 未做」的项实际已完成 —— 会误导下一轮排期。
- **测试**：纯文档轮，无代码改动；`grep` 复核 `13` 的 §1–§4 结构完整、`U274c` 残留 = 0、
  文末无漂浮段；`h10` 段落位 GAP-008 条目内（h13 之前）；`CHANGE_LOG` 仅一处文件头。
- **提交**：本轮改动随本条目一并提交。

## 2026-10-04（四十八）— 🔶 闪烁根因坐实 = M-01 管线替换；🔴 「黑天」拆成第三个独立缺陷

> **verdict = 诊断轮 + 文档同步。** 任务来源：`AGENT_CONTEXT` §10.29（`h20` 之后唯一未被排除的路径）。
> **未改产品代码**（纯取证 + 文档同步）。

- **本次改了什么**：用**单变量对照**（只关 `mixin.wireTerrain`，其余全开）跑客户端取证，
  并把结论同步进 `13-GAP-REGISTRY.md` GAP-011、`AGENT_CONTEXT.md` §10.30。
- **为什么改**：`h20` 修的两处缺陷经 `h21` 实测无效，路径只剩 M-01 管线替换；不验证它就无从推进。
- **🔶 关键**：单变量对照成立 —— `terrain drawn into` = **1**（我方 pass 照跑）、`M-01 wired` = **0**（管线替换未生效），
  排除了「关掉通道同时也关掉了别的东西」这个混淆。
  6 帧黑色像素占比**全部 51.3%**（< 60% = 有内容相位），**无一进入全黑相位** ⇒ **闪烁消失**。
  对照 `h21`（`wireTerrain=true`）：52.81% ×3 + **99.89%** ×3，两相交替。
- **🔰 但这不等于「关掉就好」**：M-01 正是 GAP-003 的**通道本身**
  （`ChunkSectionLayer#pipeline` 的返回值可被替换 = 整个 MRT 地形方案的前提），
  关掉它 = 关掉通道 ⇒ 这是「**用替换管线接管原版地形绘制**」这条路线本身代价过高，**不是一个孤立 bug**。
- **🔴 同时拆出第三个独立缺陷：黑天与闪烁无关**。`wireTerrain=false` 下地形完全正常，但**天空仍是黑的**。
  🔶 我此前一直把「黑天」当闪烁的表征，**实际是两条不同的因果链**；混在一起会让两边都定位不到 ——
  这解释了为什么前面几轮反复绕回原点。
- **顺带排除两条（纯静态）**：① 「派生管线状态不一致」（基底 snippet / color target / `ALPHA_CUTOUT` /
  绑定组 / depth stencil **全部相同**）；② 「某个调用点拿到错东西」（原版 4 处 `layer.pipeline` 调用**全部等价**）。
- **仍未定位**：① 管线替换**为何**导致闪烁（编译产物替换 / `getCompiledPipeline` 缓存 / 多套管线交替编译，均未验证）；
  ② **黑天**的成因（第三因）；③ GAP-008 未判定；GAP-007 / GAP-009 未动。
- **测试**：`./gradlew build` BUILD SUCCESSFUL，**705** 单测全绿；残留游戏进程数 = 0；配置已复原。


## 2026-10-04（四十七）— 文档同步：GAP-010 标记已修 / GAP-011 归因收敛；派生管线与原版逐项等价（排除一条路径）

> **verdict = 文档轮**（`15-ITERATION.md` 第 2 步「先改文档」）。**未改产品代码**。

- **本次改了什么**：把 `13-GAP-REGISTRY.md` 的 **GAP-010 / GAP-011** 两条重写。
  GAP-011 原条目里堆了 `h16`/`h17` 两条**已被推翻**的归因，现收敛为「当前唯一未被排除的路径（M-01 的管线替换）」，
  并把 `h22` 校准出的**取证判据**（黑色像素占比 `>90%` / `<60%`；唯一哈希数不可用）直接写进条目。
  GAP-010 标记为**已修**，并写清：当时按「提前登记」去想，**实际缺口是「取用顺序」** —— 适配层不是「来晚了」，
  是被片元的 take 顺手抹掉。
- **为什么改**：`h19`/`h21` 的新证据没有同步进文档，条目里的结论会误导下一轮；
  而且条目里写着「唯一哈希数 > 1 ⇒ 闪烁」这种**已被 `h22` 证伪**的判据。
- **🔴 新排除一条路径**（纯静态）：核对原版 `RenderPipelines` 与本项目 `TerrainDerivedPlan`，
  确认派生管线与原版**逐项等价**（基底 snippet / color target / `ALPHA_CUTOUT` / 绑定组 / depth stencil 全部相同，
  location 故意不同）⇒ 「派生管线状态不一致导致天空黑」**排除**。
- **测试**：`./gradlew build` BUILD SUCCESSFUL，**705** 单测全绿（本轮未改产品代码）。
- **仍未证明**：GAP-011 闪烁根因未定位；GAP-008 未判定；GAP-009 真材质集需资源包配套资产；
  GAP-007 未做；M-04 仍需用户裁决。


## 2026-10-04（四十六）— 🔴 GAP-010 **根因定位并修复**：`resourceLoad/ERROR` 12 → 0

> 取证方式：**纯静态定位** → **实现修复** → **客户端验证**。
> 🔴 **GAP-010 已解决** —— 这是本会话第一次拿到「用户可见问题被真正消掉」的闭环。

- **① 根因（一行「顺手多清一下」的代码）**：`takeTerrainSourceMemo()` 在取走**片元** memo 时，
  **顺手把适配层 memo 也置了 null**：

  ~~~java
  private static String takeTerrainSourceMemo() {
      String memo = terrainSourceMemo;
      terrainSourceMemo = null;
      terrainAdapterMemo = null;   // 🔴 问题就在这一行
      terrainMemoKey = null;
      return memo;
  }
  ~~~

  而调用点恰好是**先取片元、再取适配层**：

  ~~~java
  String terrainSource   = takeTerrainSourceMemo();   // ← 这一行把适配层清了
  String terrainAdapter = takeTerrainAdapterMemo();  // ← 于是永远拿到 null
  ~~~

  🔶 ⇒ 适配层字节恒为 `null` ⇒ `VirtualPackResources` 不投放 `terrain_pack_adapter`
  ⇒ 资源加载期 `PipelineBuilder` 找不到它的 VERTEX 源 ⇒ **12 条** `resourceLoad/ERROR`。
  🟡 之所以看起来「非致命」：日志里资源重载了**两次**，第二次就不报了 ⇒
  症状是**依赖一次重载才自愈**，而且**用户在 UI 上看得见**。
  🔖 代码注释原本写着「与 `takeTerrainSourceMemo()` 同批取走：两者要么都给、要么都不给」——
  **实现与注释的意图正好相反**：不是同批取走，而是把适配层**丢掉了**。
- **② 修法**：**删掉那一行**，并在原地写清为什么不能有它。
  🔖 **语义不变**：「同生共死」依然成立（片元为 `null` 时适配层也是 `null`），
  而 `takeTerrainAdapterMemo()` **本来就会清自己**，不需要这里多一手。
- **③ 验证**：`resourceLoad/ERROR` **12 → 0**；
  `terrain source reused from early contract` 仍在（片元源仍被正确复用，未多编一次）；单测 **705** 全绿（**+4**）。
- **④ 回归测试**（新建 `PackTerrainMemoTakeTest`，4 条纯源码断言）：
  ① 取片元 memo 时**不得**出现 `terrainAdapterMemo`；
  ② 但**自己**的两个 `= null` 必须留着（删掉会拿到过期源）；
  ③ 适配层 memo **必须由 `takeTerrainAdapterMemo()` 自己清理**；
  ④ 生成链必须**同生共死**（取不到片元时重生成并重取适配层）。
  🔰 `methodBody` 剥掉注释后再断言 —— **第一版断言失败恰恰是因为它把注释也读了**。
- **⑤ 文档清理**（本轮附带）：删掉 `AGENT_CONTEXT.md` / `CHANGE_LOG.md` 里 **23 处**
  「下一轮入口 / 下一步」这类**排期式清单**（共 24 行）—— 交接信息不该伪装成计划表。
  清理后检查**无空章节**残留。
- **⑥ 本轮未证明**：闪烁/黑天（GAP-011）**仍未定位**；GAP-008 未判定；GAP-007 / GAP-009 未动。


## 2026-10-04（四十五）— 🔴🔴 **判据校准**：对照组（模组关=纯原版）连拍 6 帧 = 6 种不同哈希 ⇒ 旧判据作废

> **verdict = 稳定轮（方法论校准）。** 任务来源：`AGENT_CONTEXT` §10.26 ⑤①（先给判据做对照）。
> **本轮未改产品代码**（纯取证/校准）。

- **① 🔴 旧判据彻底作废**：把模组总闸**关掉**（= 纯原版，画面完全正常，用户没报告任何闪烁），
  同一机位连拍 6 帧 ⇒ **6 张全部不同**，精确哈希无一重复。
  🔶 ⇒ **MC 画面本来就在逐帧变化**（云、水面、动画、抗锯齿、光照插值），
  「唯一哈希数 > 1」**不能**判闪烁 —— 我前几轮拿它当判据，是**用了一把没校准的尺子**。
- **② 🔶 校准出的有效判据：黑色像素占比二值化**（地形区 64000 px）：
  对照组 6 帧主色全部是 `RGB(183,212,255)` **16.82%**（天空蓝，从不全黑）；
  实验组 3 帧 **99.89%** 全黑、3 帧 **52.81%** 黑（天空黑）。**两者无任何重叠**
  ⇒ 判据定稿：**> 90% ⇒ 全黑相位；< 60% ⇒ 有内容相位**。
  🔰 为什么是「大尺度统计」：像素级恒等要求静止画面，而 MC 逐帧变化；
  「画面是不是黑的」这种**语义级**属性才是稳定可观测量。
- **③ 🔶 旧结论复核：全部保留**。h17/h19/h21 的结论都成立 ——
  真正成立的理由不是「哈希数 > 1」，而是**实验组出现 99.89% 全黑帧、而对照组从不超过 20%**。
  🔶 结论保住了，但支撑它们的尺子当时是坏的；现在换成经对照校准的尺子。
- **④ GAP-010 定位到代码位置**（未修复）：`packTerrainForMrt()` 在注册期调用 `ensureTerrainProgram()`，
  其注释自陈**「注册期早于 openResources 约 4.5 秒」**；而实测**注册 20.804 → 构建 22.124 找不到源**
  （差 **1.32 秒**）⇒ 契约算出来了，但**虚拟资源没被资源加载侧看到**。
- **⑤ 测试**：本轮未改产品代码；**701** 单测全绿；残留进程 0。
- **⑥ 下一轮**：① **GAP-010**（消掉 12 条 `Couldn't find source for VERTEX shader`，用户可见）；
  ② 回到 GAP-008；③ GAP-007 / M-04；
  ④ ✏ **后续所有截图判读一律用 §② 定稿判据**，并保留「模组关」对照组作为校准基线。


## 2026-10-04（四十四）— 🔴 两个缺陷修掉了，但**闪烁没有解决**；🔴 我的闪烁判据本身可能是错的

> **verdict = 稳定轮（修复 + 诚实的否定结果）。** 任务来源：`AGENT_CONTEXT` §10.25 ⑥。
> h20 静态定位 → 本轮**实现修复 + 客户端验证**。

- **① 本轮实现的两处修复（都是正确的防御性修补，都留）**：
  **(a)** 接线管线不再携带「无人绑定的自定义绑定组」：`registerTerrainDerivedPipelines` 给**接线用**的管线
  追加了 `TERRAIN_PARAMS_UNIFORM` 绑定组，而 `VkDispTerrainParams` **只有我方 MRT pass 会绑**；
  M-01 却把这条管线交回**原版**地形绘制路径 ⇒ 那边没人绑它。修法：摘掉（自定义块由 **MRT 变体**承载）。
  **(b)** `MrtTerrainPass.active()` 门控加 attachment 数守卫：该布尔为 true 并**不能证明**「此刻取管线的原版调用点
  正处于那个 8 附件 pass 里」⇒ 前提不成立时原版单附件 pass 会拿到 8 附件管线（**Vulkan 未定义**）。
  修法：新增 `hasExpectedAttachmentCount()`，不满足时**绝不交出 MRT 管线**，走单附件分支并去重 WARN 自报（X45）。
- **② 🔴 客户端验证：修复无效**。三道闸全过（`terrain drawn into`=1、`M-01 wired`=2），
  但**守卫「回退原版管线」告警 = 0 次** ⇒ 缺陷① 在真实运行中**根本没触发**；
  6 连拍唯一画面数 = **4**，症状与 h17/h19 **逐像素同类**（地形正常、黑天灰云）。
  ✅ 确定：缺陷①②是真实代码缺陷，修掉降低未来风险；🔴 确定：**这两处不是闪烁的原因**。
- **③ 🔴🔴 我的「闪烁判据」本身可能是错的（本轮最该记的一条）**：
  我一直用「连拍 N 帧 ⇒ 唯一哈希数 > 1 ⇒ 闪烁」。但**画面本来就会变**（区块加载、云飘、水面动画、相机微动），
  静止机位下连拍 6 帧出现多种哈希**完全正常**。🔶 我**从来没做过对照组**（模组总闸关时连拍 6 帧）。
  若同样如此 ⇒ 判据**完全无效**，h17「闪烁客观证实」那条结论**也要打问号**。
  🔖 **这与 X49 是同一个错误**：又犯了「拿未经验证的指标当判据」。X49 应补：**判据本身也必须先对照验证**。
- **④ 测试**：**701** 单测全绿（+2：守住缺陷①、② 不被改回去）；残留进程 0；配置已复原。
  ② **GAP-010**（用户可见的 12 条 ERROR）；③ 回到 GAP-008；④ GAP-007 / M-04。


## 2026-10-04（四十三）— 🔴🔶 **方向翻转**：pass 一次都没跑，闪烁**依然在** ⇒ 根因是 M-01 管线替换

> **verdict = 稳定轮（定位 + 归因翻转）。** 任务来源：`AGENT_CONTEXT` §10.24 ⑦（用户要求：先解决闪烁）。
> **本轮未改产品代码**（纯定位 + 静态读码）。

- **① 候选④ 排除（纯静态，零客户端成本）**：读原版 `ChunkSectionsToRender` 全文，
  它用的是 **pass 级** `renderPass.setUniform(...)`，**没有** `setShaderTexture` ⇒ 不碰全局槽 0。
  上一轮「全局槽 0 被污染」的推断**没有代码依据**，纯属推测。
  🔖 **教训**：这条本该上轮就纯静态读码确认，我却当成「需要客户端验证的头号嫌疑」。
  **能静态证伪的假设，先静态证伪** —— 比跑一次客户端（≈4 分钟）快两个数量级。
- **② 🔴🔶 决定性的二分**：`captureTerrainDraws=false` ⇒ `drawTerrain()` 因 `captured == null` **完全早退**，
  而 `wireTerrain` 保持开 ⇒ **M-01 管线替换照常生效**。两个变量被**完全分开**。
  实测：`terrain drawn into` = **0**（pass 一次没画）、`M-01 wired: layer=SOLID` = **2**（替换生效）、
  6 连拍唯一画面数 = **3**（**仍在闪**），症状与 h17 那一帧完全一致。
- **③ 🔶 GAP-011 方向翻转**：闪烁与黑天**不是我方 pass 造成的**，而是 **M-01 管线替换**。
  🔴 h16（主目标被清黑）、h17（pass 泄漏状态）**两轮归因都是错的**。
  🔖 **新增纪律 X49**：有 ≥2 个候选都能解释症状时，**先设计一个「只开一个变量」的对照实验**，
  而不是继续在候选上做加法（跳过 A、看 B……）。**对照实验的收益远高于逐个排除。**
- **④ 下一步**：纯静态对比派生管线与原版 SOLID 管线的状态（color attachments 数 / depth format /
  depth test+write / blend / cull）。两个待验证假设：
  「**多附件管线被拿到单附件 pass 里用**」⇒ Vulkan 未定义 ⇒ **时好时坏 = 闪烁**；
  「**depth test 打开而 depth 未清**」⇒ 写坏 depth ⇒ **天空被深度剔除 = 黑天**。
- **⑤ 测试**：**699** 单测全绿；残留游戏进程数 = 0；配置已复原。
- **⑥ 下一轮**：① 纯静态对比管线状态；② 若确认 ⇒ 给 M-01 加**守卫**（调用上下文只有 1 个
  color attachment 时回退原版管线并自报回落次数），这同时能消掉闪烁；③ **GAP-010 并行**。


## 2026-10-04（四十二）— 🔴 候选②排除（跳过 `lighting().setupFor` 闪烁仍在）+ GAP-010 时序铁证

> **verdict = 稳定轮（诊断二分）。** 任务来源：`AGENT_CONTEXT` §10.23 ⑦ + 用户报告的「进游戏时提示资源包加载失败」。

- **① 候选② 排除（本轮主实验）**：按「**每步只动一处**」的纪律，新增 `mrt.skipLightingSetup`（默认 false = 保持当前行为），
  在 pass 体内跳过 `gameRenderer.lighting().setupFor(LEVEL)` 并自报一次。
  结果：**跳过已生效**（日志自报）+ 地形 pass 仍在跑，但**用户确认闪烁仍在** ⇒ **候选②排除**。
  剩余候选：① `bindDefaultUniforms`、③ `inMrtPass`（低）、
  **④ `draws.renderGroup(..., atlasSampler, atlas, false)` 的全局绑定（新头号嫌疑）**。
- **② 新头号嫌疑**：`renderGroup` 是原版 API，惯例做法是 `RenderSystem.setShaderTexture(0, atlas)`
  —— 那是**全局 shader texture 槽 0**。若如此，**原版随后/下一帧任何从槽 0 取纹理的 pass**
  （典型就是天空）拿到的会是**方块图集** ⇒ **天空黑**。
  🔖 与 h17 观测**完全吻合**：地形正常（我们自己画，用 pass 里的 atlas）、**只有天空黑**（走全局槽 0）、且闪烁。
  ⚠️ 未验证（跳过 `renderGroup` 就没有地形了）⇒ 应**纯静态**读原版 sources 确认。
- **③ 🔴 用户报告的「资源包加载失败」= GAP-010，时序铁证（精确到毫秒）**：
  `11:50:20.804` 管线【注册】成功 → `11:50:22.124` 资源侧【构建】时找不到适配层的 VERTEX 源
  ⇒ **注册早于构建 1.32 秒**，典型的**时序竞争**。
  🟡 它没被当成致命错误，是因为**资源重载了两次**（第二次后 12 条 ERROR 不再出现 ⇒ 自愈）
  ⇒ GAP-010 是「**非致命但明确失败**」：不直接造成闪烁，但是**支柱②的污点**，且**用户在 UI 上看得见**。
- **④ 优先级修正**：GAP-011 仍排在 GAP-010 前面（用户要求先解决闪烁），但两者应**并行推进**
  —— 不能把 GAP-010 当成「无害噪声」继续挂起。
- **⑤ ⚠️ 本轮截图混入暂停菜单**（MCP 无点击工具）⇒ 不可用于判读天空颜色；
  闪烁判定以**用户现场确认**为准（4 连拍哈希 2 种，仅作「画面在变」的佐证）。
- **⑥ 测试**：**699** 单测全绿；残留游戏进程数 = 0；配置已复原。
- **⑦ 下一轮**：① **纯静态**读原版 `renderGroups` 确认 `setShaderTexture`；② 若确认则在 pass 结束前恢复槽 0；
  ③ **GAP-010 并行**：把适配层源登记提前到管线注册之前，消掉 12 条 ERROR。


## 2026-10-04（四十一）— 🔴 闪烁的真相：**地形完全正常，只有天空变黑** ⇒ 状态泄漏，不是主目标被清

> **verdict = 稳定轮（定位）。** 任务来源：`AGENT_CONTEXT` §10.22 ⑦①（GAP-011，P0）。**本轮未改产品代码。**

- **① 闪烁客观证实**：MCP **连拍 4 帧** ⇒ 唯一哈希数 = **2**（3 张全黑 + 1 张「有画面」）。
  用户报告的「高频闪烁」不是观感，是**两帧哈希不同**。
- **② 🔴 决定性的那一帧：天空黑了，地形没坏**。同一存档/机位/时刻：
  h14（模组**关**）= 蓝天 + 白云 + 正常地形；h17（模组**开** + 地形 pass 运行）= **黑天 + 灰云 + 同一份地形**。
  🔶 地形**逐像素正常** ⇒ `terrainToMain=false` 时主目标颜色**确实没被清**（与静态读码一致：
  `ensureTargets` 建的是**独立** `TextureTarget`）⇒ 被破坏的是**天空/雾渲染**。
- **③ 🔴 修正 h16 的错误结论**：「主目标被每帧清黑 + 画黑地形」**是错的**。
  🔖 **这正是 X48 的价值**：继续用单帧截图，这个错误结论会一路带下去；
  **多拍几帧 + 找一个正常基线对照**，两个错误一起暴露。
- **④ 状态泄漏候选（均未验证）**：`drawTerrain` 每帧在 **AfterLevel** 跑，做了三件全局性动作 ——
  ① `RenderSystem.bindDefaultUniforms(renderPass)`（绑到我们自己的 pass，未恢复）；
  ② `gameRenderer.lighting().setupFor(LEVEL)`（改全局光照，未复位）；③ `inMrtPass`（已有 `finally`，风险低）。
  ⚠️ **未定位到具体是哪一行**。候选清单 ≠ 结论。
- **⑤ 未解释**：全黑相位为何**连地形都没有**，而「有画面」相位地形正常 ⇒ 登记为待查，本轮不做断言。
- **⑥ 测试**：**699** 单测全绿；残留游戏进程数 = 0；配置已复原。
- **⑦ 下一轮**：**二分状态泄漏** —— 先临时跳过候选 ② `lighting().setupFor(LEVEL)`，
  看天空是否恢复蓝；恢复 ⇒ 锁定，仍黑 ⇒ 试候选 ①。**每步只动一处**，
  每次都用「4 连拍哈希 + 与 h14 对照」判定（X48）。修好后再回 GAP-008。


## 2026-10-04（四十）— 🔴🔴 症状是**高频闪烁**，不是「一直黑」；新登记 GAP-011（P0）

> **verdict = 稳定轮（症状定位 + 方法论）。** 任务来源：`AGENT_CONTEXT` §10.21 ⑨①。
> 本轮新增 `color` 探针（有效），但**判读作废** —— 因为观测面被污染。

- **🔴🔴 症状的准确描述（此前全部记错了）**：我抓到 99.89% 纯黑，本以为「画面一直是黑的」。
  **用户现场观察：屏幕在高频地从「有画面」变黑、又变回有画面。** ⇒ 纯黑**只是闪烁的一个相位**，
  「有画面」的那些帧恰恰是**原版渲染**。
  🔶 🔶 `h15`/`h16` 两趟截图 sha256 **逐字节相同** ⇒ 不是「两趟结果一致」，而是**都抓到了闪烁的同一相位**。
  我据此得出的「两趟画面一致」结论**作废**。
- **🔎 新立 X48（单帧截图不能作为判据）**：在**画面本身不稳定**的系统上，单帧截图只捕捉一个相位。
  ⇒ 画面会变的东西必须用**不受它影响的观测面**去看 —— 本项目就是 ``viewSlot`` 诊断视图（**直接读我方 pass 的附件**）。
  这与 X46 是同一条链的第三环：X46 = 取证前先证明**被测物在跑**；X48 = 取证时必须证明**观测面不受被测现象影响**。
- **哪些判据仍然成立**（不要一刀切）：
  ✅ **基于日志行**的（三道闸、探针命中 2 处、`skyOcclusion = 1.0`）；
  ✅ **h13 的槽 3**（用的是 ``viewSlot``，不是主目标）；
  ✅ **h14 的「总闸关着」**（依据是日志硬证据）；
  ⛔ **基于单帧主目标截图**的（h09–h13 画面判读、h15「候选 6 被否」、本轮 color）—— 全部降级为不可判。
- **🔵 新登记 GAP-011（P0，盖过一切）：8 槽地形 pass 每帧污染主目标** ⇒ 高频闪烁。
  机制：`FullscreenPassHook.onAfterLevel` → `MrtTerrainPass.drawTerrain` **每帧**执行（``ORDER-MARK`` 只打 1 次是**一次性**埋点，不是频率）；
  当附件与主目标共享视图时主目标被**每帧清黑 + 画黑色地形**，原版地形下一帧才回来 ⇒ 交替。
  🔵 它盖过 GAP-008 的理由：**不修它，后面每一轮实验都读不出结论**（观测面被污染），且画面不可用。
  🔖 两者不要混：GAP-011 = **画面不可用**；GAP-008 = **能看画面但高级材质地形为黑**。
- **本轮完成的实现（有效部分）**：新增 `color` 单变量探针 ``mrt.terrainColorProbe``。
  三道闸**全部通过**（总闸跳过 0 次 / 探针自报已开启 / 顶点侧两开关均关 / ``terrain drawn into`` 出现）
  ⇒ **探针确实生效了，只是观测面不对**。
- **测试**：**699** 单测全绿（697 → **+2**）：① color 开/关的取值；② **单变量断言**（只允许 ``color`` 那一行不同）。
- **下一轮优先级已重排**：① **GAP-011（P0）**；② 取证改走 ``viewSlot``；③ 回到 GAP-008；④ GAP-010 / GAP-007 / M-04。


## 2026-10-04（三十九）— 🔴 候选 6 被否；🔴 X47 的解释是错的；🔴 真正原因是**模组总闸**

> **verdict = 兼容 + 稳定轮（诊断）。** 任务来源：`AGENT_CONTEXT` §10.20 ⑧。**本轮未改产品代码。**

- **🔴 先纠正上一轮的错误结论**：M-01 **不是基准**。逐行核实埋点 `HIT_LOG_EVERY = 250_000L`、
  `onWireTerrainHit()` 每 25 万次打一行 ⇒ 它是**无限增长的命中计数器**，**永远不会「跑完」**
  ⇒ 「等 M-01 跑完」这个判据**本身就不可能成立** ⇒ **X47 作废**。而且它的存在是**好消息**：
  `hit x6250000` 说明 M-01 注入点**确实在被调用**（地形 draw 在跑）。
- **🔴 真正的原因**（顺着日志往下读才发现，第 158 行）：
  `[WARN] fullscreen pass skipped (fallback branch: config vkdisp.enabled=false)`
  **整个模组没启用** ⇒ pass 不执行、探针不生效、屏幕上是纯原版画面。与「基准阻塞」完全无关。
- **闸门清单往前推了一层**：最前面还缺一道（**模组总闸**），而且它**遮住**了后两道 ——
  总闸关着时「探针没命中」与「pass 没跑」的**失败信息长得完全一样** ⇒ 极易误判成去修探针。
  **上一轮正是这样归错因的。**
- **🔴 候选 6 被否**：探针在客户端确认命中 **2 处**（`dcdx`+`dcdy`），顶点侧两开关均**关**
  （严格单变量），画面**仍全黑**。顺带验证了 **`finally` 复位是必要的**：
  `已把 0 处` 出现 620 次（合成/延迟/最终四个程序，它们不声明 dcdx/dcdy）、`已把 2 处` 出现 108 次。
- **六个候选至此全部排除** ⇒ **排除法见底**，必须换策略：去证明**最上游那一项**。
- **🔴 可疑发现**：`h08-B` 那张「暖色地形」当时**没开** `terrainToMain` ⇒ 我们看到的很可能一直是**原版画面**，
  **从未真正看过自己 pass 输出的 colortex0**。⇒ **候选 4 降级为「未验证」**。
  🔖 教训：**结构性的推论也必须建立在已验证的前提上**。
- **🔴 新首要嫌疑：`color`**。首行 `albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);`
  若 `color.rgb` 为 0 ⇒ `albedo ≡ 0`，**与 texture / textureGrad / 光照全无关**，
  且这是**唯一一个还没被任何实验触及的因子**。本轮未能核实原版网格往 `Color` 里填了什么。
- **测试**：``./gradlew build`` BUILD SUCCESSFUL，**697** 单测全绿；残留游戏进程数 = 0；配置已复原。
- **🔴 仍未完成**：GAP-008 根因未坐实；GAP-010 未修；GAP-007 常量项 7 条；GAP-009 未实现；
  地形仍只画进我方 pass（**不产出用户可见画面改进**，M-04 未做）。



## 2026-10-04（三十八）— 🔍 派生导数探针落地；🔴 单测当场抓到探针自己的致命缺陷；🔴 客户端那趟**作废**

> **verdict = 兼容 + 稳定轮（工具 + 方法论）。** 任务来源：`AGENT_CONTEXT` §10.19 ⑥①。
> 无头部分**有效**，客户端部分**作废**（见第 ④ 条）。

- **① 为什么必须新增转译段**：`dcdx`/`dcdy` 的声明在**片元里**（BSL 实测第 293/294 行），顶点适配层够不着。
  新增第 **7¾ 段** ``DerivativeProbeAdapter``，排在 7½ 之后、⑧ 之前。
  🔖 按 GLSL 规定，显式导数为 0 时 `textureGrad` 的 LOD 选取与 ``texture()` 相同 ⇒
  「画面是否变亮」是**干净的二值判据**。
- **② 🔴 单测当场抓到探针自己的致命缺陷**：首版正则只匹配 ``dFdx`，漏了 ``dFdy` ⇒
  `dcdy` 根本没被改。日志只报「已把 N 处」，N=1 看着很正常 ⇒ **没有任何一处会提醒「漏了 y 方向」**。
  🔴 即使这个实验**安静地退化成半个变量**，结论仍像「跑了实验」。**若无头断言，它会一路骗到发布。**
  修法：分别捕获**名字方向**与**函数方向**（``dc(dx|dy)` / ``dF(dx|dy)`）并核对一致性。
- **③ 实现要点**：默认关（逐字节恒等）、只在生成地形片元源的窗口打开并 **`finally` 复位**、等行数、
  保行尾注释、不误伤、X45 自报。
- **④ 🔴 客户端那趟**作废**：截图看着「完全正常」，但它**什么都证明不了** ——
  `terrain drawn into` = **0**（pass 一次都没跑）、探针自报 = **0**、`terrainToMain=false`。
  真因：**M-01 冷路径基准长时间占住渲染线程**（`hit x6250000` 仍在跑），地形片元源从未生成。
  🔶 **当时的截图不得被下一轮引用**（它是 `terrainToMain=false` 下的纯原版渲染）。
- **⑤ 🔎 新立 X46**（取证前必须先证明「被测物确实在跑」）：「画面看着正常」是**最危险**的取证结果 ——
  它和「一切正常」在像素上完全一样。判据必须是**可数的日志行**，而不是观感。
  与 X9 同属一条，但 X46 补上它没覆盖的一半：**X9 说「要核实」，X46 说「核实哪一行日志才算数」**。
- **⑥ 新立 X47**（M-01 冷路径基准会阻塞 runClient 的资源生成）：实测基准跑到 `hit x6250000` 仍未结束，
  期间地形片元源**一次都没生成** ⇒ 跑 `runClient` 取证**必须先确认 M-01 已跑完**。
  与「不要并发基准和 runClient」是同一族问题的**同进程版本**：不是并发，是**先后**。
- **⑦ 测试**：``./gradlew build`` BUILD SUCCESSFUL，**697** 单测全绿（691 → **+6**）。
- **🔴 仍未完成**：候选 6 **未验证**；GAP-010 的 12 条 ERROR 未修；GAP-007 常量项 7 条；
  GAP-009 真材质集未实现；地形仍只画进我方 pass（**不产出用户可见画面改进**，M-04 未做）。

## 2026-10-04（三十七）— 🔴🔴 GAP-008 第一次**正面证明**片元跑完了：只有 `albedo` 是 0

> **verdict = 兼容 + 稳定轮（取证，重大推进）。**
> 任务来源：`AGENT_CONTEXT` §10.18 ⑦（候选 6 的静态核查 + 探针）。
> 本轮**未改产品代码** —— 全部结论来自测量与文档修正。

- **🔶 一句话**：把诊断视图切到 **colortex3**（高级材质路径**确实写**的那个槽）后，画面是
  **亮绿色地形剪影**（`vec4(smoothness, skyOcclusion, 0, 1)` 的 `.g` 通道）
  ⇒ **片元着色器完整跑完、输出正常**；唯独 `albedo`（槽 0）是 0。
  🔖 GAP-008 第一次从「排除法逼近」变成「**有正面证据**」。
- **① 静态核查：图集 mip 链不是我们的锅**。`blockAtlas()` 返回的是**原版**
  `TextureAtlas.LOCATION_BLOCKS` 视图，不是本引擎 `createTexture` 出来的 ⇒ mip 由原版生成填充
  ⇒ **候选 6 待查项 ① 排除**。
- **② 🔴 先修一个测量仪器本身的缺陷：诊断视图当时是哑的**。只把 `viewSlot` 改成 1
  （而 `mrt.enabled` 仍是 false、`terrainToMain` 仍是 true），截图与 `h09-C` 的 sha256 **逐字节相同**
  ⇒ 切槽根本没生效。已改正为 `mrt.enabled=true` + `terrainToMain=false`。
  🔖 **方法论**：这次「仪器哑了」是靠**哈希完全相同**发现的 —— 数值指标可能被误读成「结果一样」，
  而哈希相同是**无歧义**的仪器故障证据。
- **③ 槽 1：整幅没有地形**（99.89% 纯清屏色 `RGB(0,0,255)`，仅 0.11% 是 UI/HUD）。
  **正因为**高级材质路径写的是**槽 0/3/6/7** ⇒ **h09 的槽位映射第一次得到画面层面的独立验证**。
- **④ 🔶🔶 槽 3：片元着色器完整跑完，输出正常**。采样 `RGB(255,0,255)` 62.89%（品红清屏）
  + `RGB(0,255,0)` 37.11%（地形）⇒ 纯绿 = `.g` 满值 ⇒ `skyOcclusion = lightmap.y = 1.0`（精确值）。
  ⇒ **同一个片元里，除了 `albedo` 之外的一切都活着且正确**（光照/天光/法线/菲涅尔全部正常产出），
  **`albedo` 是在进入这些计算之前就已经被写成 0 的**。
- **⑤ 🔴 顺带纠正一处我自己的推导错误**：`§10.18` 写过「若 `lmCoord=(1,1)` 则画面应过曝发白」，
  但实测发现适配层的 clamp 上界写的是 `vec2(0.9333, 1.0)` —— **`.y` 的上界是 `1.0`**。
  ⇒ 默认配置下 `lightmap.y` **本来就饱和在 1.0** ⇒ **实验 A 的探针在结构上根本改变不了任何东西**。
  这**不是**推翻实验 A，而是**解释**了它；两路证据（`skyOcclusion=1.0` 的正面证据 + 实验 A 的无变化）一致。
  🔖 **方法论**：单变量探针要**先证明它能改变被测量**，否则「无变化」既可能是「假设错」
  也可能是「探针本来就在那个值上」。
- **⑥ 测试**：`./gradlew build` BUILD SUCCESSFUL，**691** 单测全绿。
- **⛔ 仍未完成**：候选 6 **未验证**（`dFdx(texCoord)` 是否退化）；GAP-010 的 12 条 ERROR 未修；
  地形仍只画进我方 pass（**不产出用户可见画面改进**，M-04 未做）；GAP-007 常量项 7 条；
  GAP-009 真材质贴图集未实现。
---