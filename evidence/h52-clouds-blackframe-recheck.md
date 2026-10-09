# h52 · GAP-027 云：用 MCP 复测黑帧率 ⇒ **云开/关都是 ~33%，云不再是黑帧源**（否证旧「67%」）

> 本轮**无源码改动**，是一轮取证。目的：GAP-023 看图通道（h51）打通后，顺手用 MCP 把
> GAP-027「云进 gbuffer」那条此前靠 X11/残留档测出来的黑帧数字**重测一遍**，看它站不站得住。

## 〇、臂与闸门

入口 `run-client.sh iso -PquickPlay`；三臂档位核验都是 `[STORE_RESIDUE_NONE]`（包默认档，
GAP-026 残留门通过）、后端 `Using graphics backend Vulkan … llvmpipe`。
观测面经 MCP 钉死：`set_time 6000`（正午）+ `set_weather clear`；机位 MCP `teleport_player`。
共同配置：`terrain=true packTerrainShader=true packWater=true cloudsNoCull=true
pixelProbe=true pixelProbeEvery=1 pixelProbeAfterTerrain=true`。三臂只动两个开关：

| 臂 | `cloudsPass` | `depthGlProxy` | `main` 黑帧 | 周期 |
|---|---|---|---|---|
| A | **on** | off | 188/562 = **33.5%** | 间隔严格 3 |
| B | **on** | **on** | 0/34 = 0%（样本少，X55 不采信） | — |
| C | **off** | **on** | 12/36 = **33.3%** | — |

## 一、结论（方法学，比任何一条数字都重要）

**云开（A, 33.5%）与云关（C, 33.3%）在观测误差内相同 ⇒ 云不是黑帧源。**
这**否证**了登记表里 h49x 那条「一开云黑帧从 1/3 涨到 2/3」。那条 67% 几乎可以肯定来自
**GAP-026 的残留档**（`ADVANCED_MATERIALS=true` 的 8 附件档，h48r 查出它穿过了 h45 起所有臂、
而且**改变了被测量本身**）；本轮三臂都在 `[STORE_RESIDUE_NONE]` 下测，残留排除之后云的影响消失。

A 臂周期严格 3，与 GAP-020 的「周期 3 整帧黑」同形；按 **X55**（本机 lavapipe，黑帧率随每帧
CPU 工作量移动、测的是节奏不是内容），这条数字**不作云的正确性结论**。B 臂的 0% 是 34 帧的小样本，
同样按 X55 不采信（depthGlProxy 开着会每帧多一趟全屏翻转 pass ⇒ 改了节奏）。

## 二、云确实画进了 gbuffer（这条独立于黑帧率成立）

A 臂探针：`c0@afterClouds` 对 `c0@afterTerrain` 的差，426 帧里 **371 帧为正**（云加了内容）、
4 帧逐位相同；末帧差 `(+0.074, +0.074, +0.073)`。管线替换生效自报在场：
`[GAP-027] 云管线替换生效: location=minecraft:pipeline/clouds/transform/vkdisp/clouds_no_cull`，
网格 `quadCount=10143 textureReady=true`。⇒ 云 draw 真的发出且落进 colortex0，
与 h49o「withCull(false) 后云像素落地、R+0.88/B−1.26」同口径（量级仍小）。

## 三、照实记的不足

1. **云的观感还不对**：抬头（pitch −70）截图里天顶是**近黑**（`evidence/h52-images/h52-clouds-glproxyON-up.png`
   左侧是地形 overhang、右侧是 deferred 的绿色噪声纹，不是云的样子）；地平线角度（pitch −25）
   能看到地形但天上没有可辨认的云块。⇒ 「云进了 gbuffer」≠「云渲染对了」，后者仍是 GAP-027 开放项
   （换成包的 `gbuffers_clouds` 是下一刀，本轮没做）。
2. **本轮不改默认值**：`mrt.cloudsPass` 仍默认 **关**。理由：云的视觉未闭环（第 1 点），
   且黑帧率这条本机不可判（X55）⇒ 没有「云开 = 更对」的看图证据，就不翻默认。
3. B/C 两臂样本偏小（刚进世界的窗口），只用来佐证「云开/关黑帧率同量级」，不下更强结论。

截图：`evidence/h52-images/`（glproxyON/OFF × up/horizon 四张）。
