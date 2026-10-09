# h50d → h50f · 「每 3 帧一帧整帧黑」在 `frameCounter` 修好之后**依然存在**，且 `depthGlProxy=true` 把它从 1/3 变成 2/3

> 本轮起点是两件挂着的事：① GAP-022 的**看图**判据（h49x 那一臂因为「黑帧 + 两臂机位不同」作废）；
> ② h50c 把周期性黑帧归因到 `frameCounter` 之后，登记表里那格「未解释的一格」。
> 结论先说：**看图判据没过，而且没过的方式本身是一条新事实** —— 机位钉死之后，
> 黑帧率与机位无关、与 `depthGlProxy` 有关，`frameCounter` 那条修法**必要但不充分**。

## 〇、环境（X53）

lavapipe（CPU 软件 Vulkan）/ 854×480 / `BSL_v10.1.8` 默认档（`AO_STRENGTH=1.00`、`CLOUDS=2`、
`TAA_MODE=0`）/ 无 validation layer。全部经 `bash tools/vulkan-local/run-client.sh iso -PquickPlay`。
每臂起跑前 `game_procs.sh count` = 0，收臂后 `kill`。

## 一、方法上的两处修正（h49x 作废的两个原因）

1. **机位必须钉死**：h49x 靠 `x11_input.py look --dy`（XTEST **相对**移动）转向 ⇒ 两臂落点不同 ⇒
   「A/B 图」比的不是同一批像素。本轮改用 `/tp @s <x> <y> <z> <yaw> <pitch>` 的**绝对坐标 + 绝对朝向**。
   坐标从存档 NBT 直接读出来（`run/h27/saves/New World/players/data/<uuid>.dat`，gzip + 手写
   最小 NBT 解析）：`Pos=(-7.3091, 96.0, -11.0535)`、`Rotation=(63.8172, -36.0)`。
   自证注入生效：**同一臂内三个朝向必须给出三张不同的图**（h50d 实测 md5 全不同 ⇒ tp 的
   yaw/pitch 确实吃进去了）。
2. **画面通道与仪器通道分开**：看图那一臂（h50d）探针**全关**（`pixelProbe=false`、
   `postChainTrace=false`），只吃游戏自己的 F2；计数那一臂（h50e/h50f）探针开着，
   但只回答「黑帧率差多少」，不回答「画面对不对」。

## 二、h50d（看图，两臂只差 `mrt.depthGlProxy`，云 pass 关）

| 机位 | `depthGlProxy=false` | `depthGlProxy=true` |
|---|---|---|
| 仰视 pitch=-60 | 有内容（648 KB） | **整帧黑**（27 KB） |
| 平视 pitch=0 | 有内容（733 KB） | 有内容（687 KB） |
| 俯视 pitch=45 | 有内容（744 KB） | **整帧黑**（28 KB） |

⇒ **看图判据没过**：开着代理的臂里 3 张有 2 张是纯黑。`mrt.depthGlProxy` 默认值**继续不翻**。

两臂平视图共有的东西（与开关无关，登记为独立未判项）：屏幕右上一片**暗绿等高线状噪声**
（= BSL 的体积云，`CLOUDS=2` 走 `DrawCloudVolumetric`，逐像素重度依赖 `noisetex`），
以及左侧一块**发白的水面**。云那片暗绿不是我方 `mrt.cloudsPass` 画的（本臂它是关的）。

## 三、h50e（计数：黑帧率 + 逐级 + 双代同帧）

配置：`mrt.cloudsPass=false`、`packWater=false`、探针开（`pixelProbeEvery=1`、
`postChainTrace=true`、`postChainTraceSlots=0`），机位用 §一 的绝对 `/tp`，两个机位各采一段。

| 臂 | 机位 | `main` 为 0 的帧 | 黑帧间隔 |
|---|---|---|---|
| `depthGlProxy=false` | 仰视 -60 | **49/148 = 33.1%** | 逐字 `[…,3,3,3,…]`（帧号 ≡ 1 mod 3） |
| `depthGlProxy=false` | 平视 0 | **23/71 = 32.4%** | 同上（≡ 1 mod 3） |
| `depthGlProxy=true` | 仰视 -60 | **99/148 = 66.9%** | 每 3 帧里 2 帧（≢ 1 mod 3） |
| `depthGlProxy=true` | 平视 0 | **46/69 = 66.7%** | 同上 |

三条**同时**在场的事实（这是本轮真正的产出）：

1. **`frameCounter` 这条修法在本臂是自证生效的**：`[qd-02] ofUniform keys=24 frame=900
   frameClock=(frame=900 sameTokenHits=1799 noTokenCalls=0)` ⇒ 令牌每帧恰好推进一次、
   一次没退回去。**黑帧依然是 1/3。** ⇒ h50c 那句「根因是 frameCounter」**只对了一半**：
   它解释的是 h49t 那批数据里的一支，解释不了周期 3。
2. **黑帧不是「链的输入是空的」**（同一帧、同一取样窗口）：

   | 帧 # | `c0@afterTerrain` | `c0@afterSky` | `c0@skyReadGen` | `c0@chainStart` | `colortex4` | `trace1deferred1:c0` | `main` |
   |---|---|---|---|---|---|---|---|
   | 158 | 78.04 | 161.35 | 42.67 | 78.04 | 54.21 | 77.02 | 130.95 |
   | 159 | 87.04 | 161.35 | 47.63 | 87.04 | 54.21 | 85.55 | 142.02 |
   | **160** | **87.04** | 161.35 | **0.00** | **87.04** | **54.21** | **0.00** | **0.00** |

   黑帧上进链的 `colortex0` 有内容（87.04）、AO 缓冲 `colortex4` 有内容（54.21，且逐帧恒等），
   而 `deferred1` 的输出是**精确 0**。
3. **黑帧不是「链没跑」**：黑帧 #160 上有读数的标签集合与 #158/#159 **逐字相同**
   （9 级 trace 一个不少）⇒ 每一级都执行了，只是第一级就把整帧算成 0。

⇒ 责任被压到一处：**`deferred1` 自己把一帧有内容的输入算成了 0**。它 `main()` 里
能把整帧乘/混成 0 的只有两处：`color.rgb *= GetAmbientOcclusion(...)`（`deferred1.glsl:430`，
`ao=0 ⇒ 整帧 0`）与 `color.rgb = mix(color.rgb, cloud.rgb, cloud.a)`（`:572`，
`cloud.a=1 且 cloud.rgb=0`）。

## 四、h50f（叫出闸门：四臂纯配置，机位/时刻/其余配置逐字相同）

| 臂 | 覆盖 | 想切开什么 |
|---|---|---|
| CTRL | `AO_STRENGTH=1.00`（= 包默认值） | 对照：覆盖机制本身不改变画面 |
| AO0 | `AO_STRENGTH=0.00` | `pow(ao,0)=1` ⇒ 把 §三 的 A 变成恒等，**pass 结构不动**（不像 `AO=false` 会整条 `deferred` 被 `program.world0/deferred.enabled=AO` 关掉 —— 那正是 h49v 那臂的混杂量） |
| CLD0 | `CLOUDS=0` | 把 §三 的 B 整条拿掉 |
| TAA1 | `TAA_MODE=1` | 抖动相位从 `fract(dither + fc*0.618)`（非周期）换成 `fc*0.5`（周期 2）⇒ **若黑的周期从 3 变 2，周期是包侧相位给的；周期仍是 3 ⇒ 周期来自我方** |

（读数见下节，跑完即填。）

### h50f 实测（四臂，机位 pitch=-60、时刻 6000、晴、`depthGlProxy=false`）

| 臂 | 覆盖 | `main` 为 0 | 黑帧间隔 | `trace1deferred1:c0` | `c0@chainStart` |
|---|---|---|---|---|---|
| CTRL | `AO_STRENGTH=1.00` | **53/157 = 33.8%** | `[3,3,3,3,3,3,3,3,3,3,3,3]` | 53/157 = 33.8% | **0/157 = 0%** |
| AO0 | `AO_STRENGTH=0.00` | **104/156 = 66.7%** | `[2,1,2,1,2,1,…]` | 105/157 = 66.9% | 0/156 = 0% |
| **CLD0** | **`CLOUDS=0`** | **0/171 = 0.0%** | —— | **0/171 = 0.0%** | 0/171 = 0% |
| TAA1 | `TAA_MODE=1` | **113/170 = 66.5%** | `[2,1,2,1,2,1,…]` | 113/170 = 66.5% | 0/170 = 0% |

CTRL 臂的覆盖自报逐字：`选项覆盖 [OPTION_OVERRIDE_ALREADY_EQUAL] 选项 'AO_STRENGTH' 本来就是 '1.00' ⇒ 未改动`
⇒ 对照成立：覆盖机制本身不动画面，33.8% 就是默认档的数。

**四条读出来的东西**：

1. **闸门是云混合，不是 AO** —— `CLOUDS=0` 一臂 171 帧**没有一帧**为 0，而其余三臂 33.8%~66.7%。
   ⇒ **撤回 h49v 那句「黑帧由 AO 产生」**：那一臂用的是 `AO=false`，而包逐字
   `program.world0/deferred.enabled=AO`（`shaders.properties`）⇒ 它关掉的是**整条 `deferred` pass**，
   不是 AO 那一次乘法 —— 两臂之间根本不是单变量。本轮改用 `AO_STRENGTH=0.00`
   （`pow(ao,0)=1` ⇒ 让那次乘法变恒等、pass 结构不动）之后，黑帧**不降反升到 2/3**
   ⇒ AO 不是闸门。（`pow(0,0)` 在 GLSL 里本就无定义，这一臂的数只能当“AO 不是闸门”用，
   不能当“AO 让画面变亮”用。）
2. **周期还是 3**：`[3,3,3,…]` 与 `[2,1,2,1,…]` 都是**每 3 帧一个循环**，只是循环里黑的个数从 1 变 2。
   ⇒ `AO_STRENGTH` / `TAA_MODE` 改变的是「3 个相位里有几个落到黑」，**没有改变周期本身**。
   ⇒ 周期 3 的来处仍未判（登记不关）。**`frameCounter` 那条修法已经把相位这条通道排除了**
   （`[qd-02] frame=300 sameTokenHits=600 noTokenCalls=0` = 每帧恰好 +1）。
3. **`c0@chainStart` 在四臂里恒 0%（157/156/171/170 帧全部有内容）** ⇒ 再一次独立确认：
   黑不是「进链的图是空的」，是 `deferred1` 把有内容的输入算成了 0。
4. 顺着「云为什么会算成 0/暗绿」去查云读什么 ⇒ 掉出 **GAP-029**：链上有 19 个 OF 内建
   我方从来没供，其中 `shadowFade` 与 `timeBrightness` 是承重的
   （`shadowFade` 恒 0 ⇒ `sunmoon.glsl:111 visibility *= shadowFade * LIGHT_SHAFT_STRENGTH`
   逐字把光柱整条乘没、`forwardLighting.glsl:76` 把太阳直射项乘没、`ggx.glsl:136` 把高光乘没；
   `timeBrightness` 恒 0 ⇒ `deferred1.glsl:478 sunColor = mix(lightMA, …, timeBrightness)`
   正午按午夜配色、`sky.glsl:11` 少 2 档曝光）。
   ⇒ 那片暗绿的云与「画面整体偏暗/灰」最省事的解释方向不是云画错了，
   而是**云被当成在没有太阳的午夜来算**。审计方法与 19 个名字的清单在登记表 GAP-029 行。

## 五、本轮改了什么结论

- **撤回**：h50c 的「根因是 frameCounter，不是 mip/代次」被当成「症状已修」。真话是：
  `frameCounter` 那一支是真的（它把 h49t 的 62/185 压到 1/177），但**周期 3 那支还在**，
  而且与机位无关、与 `depthGlProxy` 有关。
- **`mrt.depthGlProxy` 默认值不翻**（看图判据没过 + 黑帧率从 1/3 涨到 2/3）。
- GAP-022 的关闭条件里那句「`mrt.depthGlProxy=false` 时逐字节与今天一致」在 h50c 之后
  **不再可能成立**（`frameCounter` 修好 ⇒ 抖动相位变了 ⇒ OFF 态像素本来就该变）。
  回归基线改成「h50c 之后的 OFF 态」。
