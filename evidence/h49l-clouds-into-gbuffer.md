# h49l / h49m · GAP-027 第二刀：把云搬进 gbuffer —— draw 确实发出了（9865 quad），但对 colortex0 **零写入**

> 被测改动：新增 `bridge/CloudsIntoGbuffer`（开关 `mrt.cloudsPass`，默认 **false**），
> 在地形 pass 关闭之后、`POOL.advanceWritten` 之前，开一个**只有一个颜色附件**的 pass，
> 把原版 `CloudRenderer.render(status, renderPass)` 画进 `colortex0` 的**待写那一代**。
> 取证入口：`bash tools/vulkan-local/run-client.sh iso -PquickPlay`
> （车具 `/tmp/opencode/h49l_clouds_ab.sh`，单变量 = `mrt.cloudsPass`）。

## 〇、环境（X53）

同 `evidence/h49-water-translucent-draw.md` §〇：`llvmpipe (LLVM 23.1.1, 256 bits)` /
Vulkan 1.4.354 / Mesa 26.2.4，帧图 `854x480`，包 `BSL_v10.1.8` 默认档，
本机**没有** validation layer ⇒ 「没报 validation error」不是证据（X35）。
两臂起跑前 `game_procs.sh count` 均为 0（X54）。

## 一、单变量表（X52）

| 键 | OFF | ON |
|---|---|---|
| `mrt.cloudsPass` | `false` | `true` ← **唯一差异** |
| `mrt.terrain` / `mrt.packTerrainShader` / `mrt.packWater` | `true` / `true` / `true` | 同 |
| `mrt.enabled` | `false`（生产视图） | 同 |
| `mrt.depthGlProxy` | `false` | 同 |
| `mrt.pixelProbe` / `Every` / `AfterTerrain` | `true` / `1` / `true` | 同 |
| 车具注入的命令 | `/gamerule advance_time false`、`/time set 6000`、`/weather clear`、`look --dy -520`（抬头） | 同 |

## 二、判据结果

| 判据 | OFF | ON |
|---|---|---|
| `[GAP-027/clouds] 云搬进 gbuffer: status=FANCY cloudColorAlpha=204 cloudHeight=192.33 cloudRange=64` | 0 条 | **1 条** ⇒ 分支真的进了，且云的绘制条件成立 |
| `[GAP-027/clouds] 网格状态（prepare 之后、render 之前）: quadCount=9865 textureReady=true facesBufferReady=true` | 0 条 | **1 条** ⇒ 🔑 **原版内部那个 `texture != null && quadCount != 0` 的静默早退没有发生 ⇒ `drawIndexed(6 × 9865)` 真的被发出了** |
| `c0@afterTerrain` vs `c0@afterClouds`（同帧、同臂） | — | **#417 与 #418 两帧逐位相同**：`(36.5255,45.6873,56.5242)` = `(36.5255,45.6873,56.5242)` ⇒ **云对 colortex0 零写入** |
| `Render thread/ERROR` | 2（`Narrator` + `SoundEngine`，与渲染无关） | 2（同一批）⇒ **没引入新异常**（尤其没有 `setPipeline` 附件数不匹配那类响亮失败） |

⇒ 本轮把「云没出现」切成了两半并**判掉一半**：不是「一条 draw 都没发」，是「**发了但没落地**」。
（这个区分值一轮：`quadCount` 是原版 private，不反射就永远只能猜。）

## 三、已经从源码**排除**的解释（别再往这些方向试）

| 候选 | 核实结果 |
|---|---|
| 附件数 ≠ 管线颜色目标数 ⇒ `setPipeline` 抛 | 排除。`RenderPipelines.CLOUDS` / `FLAT_CLOUDS` 走 `CLOUDS_SNIPPET`（`RenderPipelines.java:217-224, 903-906`），我方那个 pass **只挂 1 个颜色附件**正是为它设计的；且两臂 ERROR 集合相同、无异常 |
| 原版内部 `texture == null \|\| quadCount == 0` 静默早退 | 排除（反射读数 `quadCount=9865`、`textureReady=true`、`facesBufferReady=true`） |
| 云贴图没绑 ⇒ 片元按 alpha=0 全 discard | 排除。`CloudRenderer` 的 `textures/environment/clouds.png` 是在 reload 期读成 **CPU 侧 `TextureData(long[] cells,…)`**（`CloudRenderer.java:45,57,64`），`render` 只绑 `CloudInfo`(UBO) 与 `CloudFaces`(UTB)（:233-234）⇒ **这条管线没有 Sampler0**，不存在「漏绑贴图」 |
| 深度比较口径把云全拒（反向 Z 下天空 = 0.0） | 排除（按默认值推）：本引擎地形管线逐字沿用 snippet 的 `CompareOp.GREATER_THAN_OR_EQUAL`（反向 Z：近 = 大），云深度 > 0.0 ⇒ 在「天空那片」本该**通过**。⚠️ 这条是**推理**不是实测 —— `CLOUDS_SNIPPET` 自己有没有覆盖深度状态，本轮没读到那一层（见 §五 的下一刀） |

## 四、这一臂的**画面侧没有观测面**（不许拿它当判据）

三张 F2 全部是**整帧黑**（只有快捷栏与手持物，`h49l-clouds/ON/shots/2026-10-09_11.14.29.png`）。
这正是 GAP-019/GAP-020 那条「链把地形输出打没 / 周期性空帧」家族 ——
生产视图下画面为黑时，**任何**「云看不看得见」的画面判据都无从谈起。
⇒ 本轮只用探针与自报判，不用 luma、不做跨臂比亮度（同 `evidence/h49` §十 的规矩）。
🔖 顺带一条排序信息：**这条黑帧缺陷现在是多轮判据的公共阻塞物**（水、云、天空的画面侧都被它挡着）
⇒ 它的优先级从「GAP-020 排查中」上升为**下一批判据的前置**。

## 五、下一刀（已定，按代价排）

1. **把「pass 到底写没写到这张纹理」与「云的 draw 有没有落地」分开**：
   给云那个 pass 加一档诊断 = 颜色附件按 **CLEAR 成洋红**打开（`mrt.cloudsDiagnosticClear`，默认关）。
   - 探针读到洋红 ⇒ pass 与纹理/代次都对 ⇒ 问题在**云的 draw 本身**（深度/位姿/裁剪）；
   - 仍读到地形的值 ⇒ 我方挂的 view 与被读的那一代**不是同一张图**（GAP-018 轮转口径错），
     那是比云更根本的一格，要先把代次模型修对。
2. 若 1 指向云的 draw：把 `CLOUDS_SNIPPET` 的**深度与裁剪状态读到行**（本轮只读了 snippet 的组成，
   没读它继承的 `MATRICES_FOG_SNIPPET` 里深度那一项的实参），再决定是「位姿被 model-view 残留带跑」
   还是「深度状态与 gbuffer 附件不配」。
3. 云搬进 gbuffer 之后才有意义的下一刀：**换成包的 `gbuffers_clouds`**
   —— 需要管线替换那条路（本轮核实：`RenderSystem.getCompiledPipeline:123` →
   `getCompiledPipelineNullable:106` 的**首条语句**就是 `PIPELINE_MODIFIERS.apply(pipeline)` ⇒
   对 `SkyRenderer`/`CloudRenderer` 这类内部直接取管线的路径**有效**；
   `PipelineModifier` 必须幂等且返回的管线要换 `location`，否则 `PipelineModifierStack:63-66` 抛）。

## 六、本轮**不覆盖**什么

1. **云为什么零写入**未判（§三 排除了四个方向，剩两个候选：pass/代次口径、云的 draw 状态）。
2. 云用的是**原版着色器**，不是包的 `gbuffers_clouds` ⇒ 「包的云生效」这条判据**没动**。
3. 天空同理（本轮另有一条更正）：`SkyIntoGbuffer` 搬进 gbuffer 的只是几何，
   用的仍是原版 `RenderPipelines.SKY/CELESTIAL/STARS/SUNRISE_SUNSET/END_SKY`
   ⇒ 登记表 GAP-027「现状」那一行原来写「`gbuffers_skybasic/skytextured` 已落地」**说重了**，已就地更正。
4. 生产视图整帧黑（GAP-019/020 家族）未修 —— 它现在是画面侧判据的公共阻塞物。

---

## 七、h49n：诊断档判掉「代次/挂错图」那一支 —— pass 与纹理口径是**对的**

加一档 `mrt.cloudsDiagnosticClear`（默认关）：云 pass 的颜色附件按 **CLEAR 洋红** 打开。

| 读数（同臂同帧） | 逐字 |
|---|---|
| `[GAP-027/clouds] 网格状态 … quadCount=9746 textureReady=true facesBufferReady=true` | draw 发了 |
| `c0@afterTerrain#415 area=FULL meanRGB=(255.0000,0.0000,255.0000)` | 🔴 **洋红被读到了** |
| `c0@afterClouds#415 area=FULL meanRGB=(255.0000,0.0000,255.0000)` | 云在洋红上**一个像素都没改** |

⇒ §五 的候选 ② （「我方挂的 view 与被读的代次不是同一张图」）**排除** ——
云 pass 写的就是探针读的那张图，GAP-018 的代次口径在这一格是对的。
⇒ 剩下的唯一方向：**云的 draw 本身没产生像素**。

## 八、h49o：**根因是背面裁剪**（`CLOUDS` 裁、`FLAT_CLOUDS` 不裁）

纯配置判别（不改代码）：把车道画质从 `fancy` 改成 `fast` ⇒ 原版走 `FLAT_CLOUDS`
（`RenderPipelines.java:903-904` 逐字 `withCull(false)`），而 `CLOUDS`（:906）**没有**关裁剪。

| 臂 | status | 管线 | `c0@afterClouds` area=FULL |
|---|---|---|---|
| h49n | `FANCY` | `CLOUDS`（裁剪开） | `(255.0000, 0.0000, 255.0000)` ⇒ **零写入** |
| h49o | `FAST` | `FLAT_CLOUDS`（`withCull(false)`） | `(255.0000, **0.2588**, 255.0000)`，下一帧 `0.2657` ⇒ **绿通道离开 0 = 云像素落地了** |

⇒ 🔑 **判定：云在我方 gbuffer pass 里被当成背面剔光。** 同一份几何、同一条 shader，
只差 `withCull(false)` 一项，就从「零写入」变成「有写入」。
深度比较那一支也已从源码排除：`DepthStencilState.DEFAULT = (GREATER_THAN_OR_EQUAL, writeDepth=true)`
（`renderpearl/api/pipeline/DepthStencilState.java`）⇒ 与反向 Z 相容，不是它。

### 这条为什么比「云」本身大

`withCull(true)`（默认）的原版几何管线**不止云**：实体、手、天气、粒子……
⇒ 「**我方自建的 render pass 里，绕序判定与主目标不一致**」是一整类「几何搬进 gbuffer 却什么都不写」
的共同根因候选。⚠️ 但**不要顺手推广结论**：地形在同一族 pass 里是**正常出画**的
（`c0@afterTerrain` 一直有内容），所以「所有 pass 都翻转」不成立 ——
要么地形那条管线的绕序/裁剪恰好不受影响，要么差异在别处（视口 Y 向、目标类型 swapchain vs texture）。
**本轮未判这一层。**

### 下一刀（已定，两条各自独立）

1. **让 FANCY 云能落地**：给我方云 pass 用一条 `withCull(false)` 的派生云管线
   （走 `RegisterRenderPipelinesEvent` 注册，或走本轮核实过的管线替换那条路）。
   代价小、判据现成（洋红底上绿通道离开 0）。
2. **查清绕序差异的来处**：同一臂内把云几何画进**主目标**与画进 **colortex** 各一次，
   比较覆盖面积；或读 `VulkanRenderPass` 对 swapchain 与 image 目标的视口/`frontFace` 设置。
   这条查清了，实体/手/天气那一整类的「搬进来却不写」才有解释。

### ⚠️ 本轮遗留的两个「别读过头」

- h49o 的覆盖只有 `0.2588/255 ≈ 0.1%` 的画面量级 ⇒ **裁剪不是全部故事**：
  抬头看天时云本该盖住大片画面，所以还有一格（位姿 / 相机偏移 / 云层在相机之上还是之下）未查。
- 取证车道被改过又改回：`run/h27/options.txt` 的 `graphicsPreset` 在 h49o 期间是 `"fast"`，
  跑完已恢复 `"fancy"`（本臂结论只在**明说档位**的前提下成立）。
