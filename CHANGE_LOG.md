# 变更记录（CHANGE_LOG）

> 格式与流程依据：docs/15-ITERATION.md「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---
---
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
