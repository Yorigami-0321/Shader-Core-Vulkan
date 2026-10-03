# H07 · 🔴 SPIR-V 编译**早就验证过**（我漏看了）+「能力上限 5 槽」≠「生产实际 1 槽」

> 任务来源：h06 留下的「① 翻译结果能否编译成 SPIR-V —— **未验证**」。
> 结果：① **那条是错的**，证据一直在我自己的日志里；② 顺带挖出一条**会崩客户端**的陷阱。
> 证据：`run/logs/*.log`（多趟历史日志）+ `evidence/h06` §2.1/§2.2（更正）+
> `src/test/java/dev/vkdisp/pack/TerrainProductionOutputCountTest.java`（新增，3 例，无头）。
> （2026-10-04）

## 结论先行

| # | h06 的说法 | 实际 |
|---|---|---|
| ① | 「翻译结果能否编译成 SPIR-V **尚未验证**，这是下一卡的点」 | 🔴 **早就验证过**。项目里本来就有「整包逐程序逐阶段跑 SPIR-V 编译」的通路（`VkDispPackScan#compileAndLog`），每次资源重载都跑。**BSL 地形片元三个维度目录全部编译成功，整包 190/190 零失败**，且此前每一趟日志都能 grep 到 |
| ② | h06 §2 实测「合成 5 个输出声明」 | ✅ 那是**能力上限**；**生产实际只有 1 个**（= 只用 colortex0）⇒ 从经验上**确认**了 h06 的「BSL 默认地形只写 colortex0」 |

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan + lavapipe） |
| 本轮类型 | **核实 + 加回归测试**；**未跑客户端**（证据取自既有日志 + 无头跑生产链路） |
| 证据来源 | `run/logs/latest.log` 等 6 份历史日志；`run/shaderpacks/BSL_v10.1.8.zip` |
| 残留进程 | ✅ 全程 `残留游戏进程数 = 0` |

## 1. SPIR-V 编译：既有通路的实测输出

**通路**（本次才读明白）：

```
VkDispPackScan#compileAndLog
  → ShaderPackCompiler.compile(discovered)      冷路径：include 展开 + OF 转译（纯文本）
  → bridge/ShaderCompileApi.compileStage(...)  驱动级：原版 GlslCompiler → SPIR-V
  → 每阶段一行日志 + 末行汇总
```

**历史日志实测**（`run/logs/latest.log`，`run/logs/mcp-*.log` 等 6 份均可 grep 到同一批）：

```
pack program compiled OK: pack=BSL_v10.1.8 program=world0/gbuffers_terrain  stage=FRAGMENT file=world0/gbuffers_terrain.fsh  spvBytes=79896
pack program compiled OK: pack=BSL_v10.1.8 program=world0/gbuffers_terrain  stage=VERTEX   file=world0/gbuffers_terrain.vsh  spvBytes=35736
pack program compiled OK: pack=BSL_v10.1.8 program=world-1/gbuffers_terrain stage=FRAGMENT file=world-1/gbuffers_terrain.fsh spvBytes=46692
pack program compiled OK: pack=BSL_v10.1.8 program=world-1/gbuffers_terrain stage=VERTEX   file=world-1/gbuffers_terrain.vsh spvBytes=35740
pack program compiled OK: pack=BSL_v10.1.8 program=world1/gbuffers_terrain  stage=FRAGMENT file=world1/gbuffers_terrain.fsh  spvBytes=63724
pack program compiled OK: pack=BSL_v10.1.8 program=world1/gbuffers_terrain  stage=VERTEX   file=world1/gbuffers_terrain.vsh  spvBytes=35620
pack compile done: stages=190 ok=190 failed=0
```

🔖 顺带说明：片元 SPIR-V 字节数按维度目录差异很大（46.7KB / 63.7KB / 79.9KB），
**不是**因为有的失败或被截断，而是三个目录的 `world*/gbuffers_terrain.fsh` 内容不同。

## 2. 🔴 陷阱：「能力上限 5 槽」≠「生产实际 1 槽」，混用会**崩客户端**

同一批日志里，地形片元的合成输出诊断**只有一条**：

```
pack diagnostic: INFO: program/gbuffers_terrain.glsl:425:
  包内未声明 location 0 的片元输出，已合成声明 layout(location = 0) out vec4 vkdispFragOut0;
```

（全包范围里出现过的最大 location 是 2，且来自**别的**程序，不是地形。）

**为什么 h06 量到 5、生产只有 1**：h06 喂的是**未预处理文本切片** ——
没展开 `#include`、没求值预处理条件 ⇒ 死分支 `#if defined ADVANCED_MATERIALS …` 仍在，
`gl_FragData[1..4]` 全被看见。生产链路先展开再求值 ⇒ 死分支消失。

🔴 **而混用的后果是崩**：`FrontendRenderPass#setPipeline` 校验
「render pass 颜色附件数 == 管线颜色目标数」，不等就抛 `IllegalStateException`
（`h05` 已实测该校验存在）⇒ **客户端直接崩**。

⇒ 我方当前状态是**自洽的**：`MrtPlan.slotCount()` 两侧同源，诊断 pass 与 MRT 地形管线都用它，
而**当前挂的是原版 `core/terrain`（1 个输出）**，与默认 `slotCount` 的实际取值一致 ⇒ 不会触发该校验。
⚠️ 但一旦把 BSL 的地形 SPIR-V 接进派生管线，就必须让附件数**跟着包的输出数走**，不能沿用「Iris 口径的 3」。

## 3. 新增回归测试（无头，3 例，走生产同款链路）

`src/test/java/dev/vkdisp/pack/TerrainProductionOutputCountTest.java`：

| 测试 | 断言 | 挡住的坑 |
|---|---|---|
| `productionTerrainFragmentWritesExactlyOneSlot` | 整包转译 **0 ERROR**；地形片元**三个维度目录**的输出数**各为 1** | 把「能力上限」当「生产实际」 ⇒ 排期/建 pass 时按 5 槽走 |
| `capabilityIsFiveButProductionIsOne` | 未预处理切片 = **5**，生产 = **1**，且前者 > 后者 | 让人**记住这两个是不同的数**，而不是各自记一个数后混用 |
| `attachmentCountMismatchIsLoud` | 管线与 pass 的附件数必须同取 `MrtPlan.slotCount()`；且 `SLOT_COUNT` **没被悄悄改成 5** | 悄悄改常量导致两侧不匹配 ⇒ `setPipeline` 抛异常 ⇒ 崩 |

🔖 这三个测试走的是**生产同款链路**（`ShaderPackScanner.scan(run/shaderpacks)`
→ `ShaderPackCompiler.compile`），不是文本切片 —— 这是它与 `TerrainProgramTranslateBaselineTest`
（能力口径）的**本质区别**，也是本轮的核心方法论收获。

## 4. 🔖 本轮最贵的一条教训（已立 X41）

**断言「某能力未验证」之前，先搜既有日志与既有代码路径。**

本项目把「冷路径整包编译 + 逐阶段 SPIR-V + 汇总计数」做得很完整，
日志里白纸黑字写着 `stages=190 ok=190 failed=0`，
我却在**没搜日志**的情况下，把「能否编译成 SPIR-V」记成待办 ——
而这正是我上一轮给自己出的题。**代价**：多花一轮，还差点让人按错误的未完成项排期。

⇒ 与已有的 X37（改共享状态后重测）、X38（判据内容要能区分被测属性）、X40（先断言输入规模）
并列，它属于同一族：**别把自己的记忆当证据，日志和代码才是。**

## 5. 真正的剩余缺口（比 h06 写的更靠后一步）

h06 把缺口写成「翻译结果能否编译成 SPIR-V」。🔴 **该项不成立**。
真正的缺口更靠后：

> **整包编译产物里已经有 BSL 地形片元的 SPIR-V（46.7–79.9KB），
> 但地形 draw 用的仍然是原版 `core/terrain`。**
> 派生 MRT 地形管线是从 `RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET` 建的，
> 片段着色器是**原版的**（只有 `layout(location=0) out vec4 fragColor`）。

所以「接入」这一步真正要做的是**接线**，不是「能不能编译」：

| 待接线项 | 状态 |
|---|---|
| 把编译出的地形 SPIR-V 作为派生 MRT 地形管线的**片段着色器** | ❌ 未做 |
| 配套的**顶点**着色器（包的 `gbuffers_terrain.vsh`，需对齐原版顶点属性布局） | ❌ 未做 |
| 附件数改为**跟随包的输出数**（默认配置 = 1，不是 3） | ❌ 未做 |
| 44 条 OF uniform 的**取值供给**（GAP-004 那个块目前只收编了声明） | ❌ 未做 |
| `sampler3D lighttex0/1` vs 原版 **2D** lightmap 的结构性不匹配 | ❌ 未处理（🔴 但它显然**没有阻止编译**，见 §1 ⇒ 该担心的形式要改） |
| DRAWBUFFERS 映射（`gl_FragData[1]`→colortex3 等） | ❌ 未实测（默认配置下这些分支是死的，量不到；需开 `ADVANCED_MATERIALS` 才能验） |

🔖 **一处要改的担忧**：`sampler3D lighttex0/1` 与原版 2D lightmap 的不匹配，
我原本列为结构性阻塞，但既然整包编译 190/190 通过，**它在编译层面不构成阻塞** ——
真正的问题只会出现在**渲染期绑定采样器时**。⇒ 担忧的形式要改，不能按原样挂着。

## 6. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| 本轮 | **未开客户端、未改运行期配置** | — |
| 新增文件 | `src/test/java/dev/vkdisp/pack/TerrainProductionOutputCountTest.java`（3 例，无头） | 入库 |
| 修改文件 | `TerrainProgramTranslateBaselineTest.java`（新增包级 `rawSliceOutputCount()` 供对照；类注释补「能力口径 vs 生产口径」说明）；`evidence/h06` §2 新增 §2.1/§2.2 与更正 | 入库 |
| 游戏进程 | 0 趟 | ✅ **残留 = 0** |