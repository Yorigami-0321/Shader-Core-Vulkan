# h50h / h50i · 把「GAP-029 供值」与「阴影桩清屏值」拆开量 —— 结论：供值中性，**桩改 1.0 是回归**

> 上一份证据（`evidence/h50g-shadowstub-ab.md`）里那两张图**作废**（配置被 FML 按默认值重建，
> 那一跑近乎纯原版）。本文件是重做：加了两道硬闸门，并把两个改动拆成两格分别量。
> 起点问题很具体：`h50f CTRL` 的黑帧是 **33.8%**，我一次提交里同时改了 GAP-029 供值与桩清屏值，
> 之后测到 **66.7%** —— 这笔账必须拆到两个改动头上，不能含糊。

## 〇、方法（两道闸门，都是被 h50g 那次白跑逼出来的）

- **闸门 A（配置落地证明）**：起跑前用 `lane_cfg.py` 应用 15 个键，然后**逐键回读**
  `run/h27/config/vkdisp-client.toml` 与期望值表比对，任一键不符 ⇒ `exit 1`。
  顶层键（`shaderPack` / `enabled`）没有缩进、`enabled` 出现两次 ⇒ 匹配用 `^\s*key = value$` 且
  「任一处相等即算落地」。**这道闸门第一次运行就抓出自己漏匹配顶层键**（否则又是一臂白跑）。
- **闸门 B（不产出假数）**：等待循环里发现 `Failed to load config vkdisp-client` ⇒ abort；
  采集窗 `main` 样本数为 0 ⇒ abort 并退出码非 0。
  🔖 修的是 h50g 那个形状：**「样本=0」被印成了「为 0 的帧=0 = 0.0%」**，看起来像一个结论。

机位仍用绝对 `/tp @s -7.3091 96.0 -11.0535 63.8172 -60`；时刻 6000 + `advance_time false` + 晴；
`mrt.terrain/packTerrainShader=true`、`cloudsPass=false`、`packWater=false`、`depthGlProxy=false`、
`pixelProbe=true pixelProbeEvery=1 postChainTrace=true postChainTraceSlots=0`、
`pack.optionOverrides=""`（**必须带引号**，见 §四）。

## 一、三格对照（同一观测面、同一机位，只差被检的那一格）

| 臂 | GAP-029 三名供值 | 桩深度清屏 | `main` 为 0 的帧 | 黑帧间隔 | 两张截图 |
|---|---|---|---|---|---|
| h50f CTRL | ❌ 没供 | 0.0 | 53/157 = **33.8%** | `[3,3,3,3,…]` | 有内容 |
| **h50i** | ✅ 供 | **0.0** | 55/167 = **32.9%** | `[3,3,3,3,…]` | 有内容（737 KB / 669 KB） |
| **h50h** | ✅ 供 | **1.0** | 116/174 = **66.7%** | `[1,2,1,2,…]` | **两张都是纯黑**（28 KB / 28 KB） |

⇒ **两笔账分开了**：
1. **GAP-029 的供值在黑帧这一格上是中性的**（33.8% → 32.9%，周期与间隔形态都没动）。
   ⇒ 它既不是黑帧的原因，也不是黑帧的解药 —— 它是**另一件事**（光柱/高光/日光配色，见 §三）。
2. **桩从 0.0 改到 1.0 是把黑帧翻倍**（32.9% → 66.7%，每 3 帧里 2 帧黑）。
   ⇒ **本仓已把它改回 0.0**，守卫测试改成钉「测量支持哪一边」而不是「哪一边有原理」。

## 二、⚠️ 那条「包按 GL 口径读 shadowtex ⇒ 桩该是 1.0」的推理**被真机否了一次**

推理链本身是自洽的：BSL `shaders/lib/lighting/shadows.glsl:3` 声明
`uniform sampler2DShadow shadowtex0`、`:61` 用 `shadow2D(shadowtex, vec3(uv, z)).x`，
GL 口径里 1.0 = 远平面 = 无遮挡 ⇒ 桩该存 1.0；而 `shadowFade` 此前恒 0 让
`shadows.glsl:220 shadow = mix(vec3(1.0), shadow, shadowFade)` 把整个阴影项跳过，
所以桩的内容一直没被消费过。

但真机给的是**相反的方向**：1.0 那一格画面更差（黑帧更多、两张截图全黑）。
⇒ 三种可能，本轮**没判**：① 阴影项在 `deferred1` 之前根本不参与（`SHADOW` 分支/管线差异），
变化其实来自别处；② `shadow2D` 在本后端被降级成普通采样（GAP-015：原版给不出比较采样器），
比较方向与包假设不同；③ 1.0 让云/曝光那条链更容易饱和到 `a=1, rgb=0`。
🔖 记下来的纪律：**「按口径应该如此」不是判据**。本仓这一条已经第二次把信念当断言写进测试
（上一次是这条测试的前身，钉的是 0.0 + 「0.0 = 远平面」那句），两次都被测量打回。

## 三、供值**确实进了 uniform 块**（不是只进了 Java 的 Map）

`[uniforms]` 上传自报（h50i 全程日志，逐 pass）：

| pass | members | written | unfilled | 未填的名字 |
|---|---|---|---|---|
| post0 | 24 | 24 | 0 | —— |
| post1 | 42 | 34 | 8 | `darknessFactor, isDesert, isMesa, isCold, isSwamp, isMushroom, isSavanna, isJungle` |
| **post2** | 42 | 31 | 11 | `darknessFactor, darknessLightFactor, endFlashIntensity, endFlashPosition, isDesert, isMesa, isCold, isSwamp, isMushroom, isSavanna, isJungle` |
| post3 | 28 | 27 | 1 | `darknessFactor` |
| post4–post8 | 24–27 | 全填 | 0 | —— |

⇒ `shadowFade` / `timeBrightness` / `screenBrightness` **不在任何一行的 unfilled 名单里**
⇒ 三个名字已经写进块、被包真的读到（否则它们会像 `darknessFactor` 一样被点名）。
⇒ 顺带量出一件新的：**7 个生物群系旗标 `isDesert/isMesa/isCold/isSwamp/isMushroom/isSavanna/isJungle`
在 post1/post2/post5 上恒未填**（BSL 用它们选天气色/植被色）⇒ 归 GAP-029 的下一批。

## 四、h50g 那一臂为什么整份配置没了（写进 `07-CONSTRAINTS` 那一族）

`lane_cfg.py --lane iso "pack.optionOverrides="`（想把上一臂留下的 `TAA_MODE=1` 清掉）
落盘成 `optionOverrides =`（空值不带引号）⇒ night-config 抛
`ParsingException: Invalid value containing only whitespaces`，FML 的反应是
**`Attempting to recreate` = 整份配置按默认值重建**，不是丢掉那一个键。
⇒ 那一跑实际是 `shaderPack=""`、`mrt.terrain=false`、`pixelProbe=false`。
⇒ 正确写法：`lane_cfg.py 'pack.optionOverrides=""'`（本轮实测两种写法的落盘结果并对比过）。

## 六、h50j：把 `deferred1` 的**三个输出槽**一起取 ⇒ 责任从「包算错」转到「链看到的图不是刚写的那张」

h50j = h50i 同一套配置，只把 `mrt.postChainTraceSlots` 从 `0` 改成 `0,4,5`
（BSL `deferred1.glsl:616/621` 逐字 `/*DRAWBUFFERS:045*/` ⇒ 它的三个输出是 colortex0 = 颜色、
colortex4 = `cloudViewLength`（`:618`）、colortex5 = 反射色 + mask（`:622`））。
复现率逐字对上了 h50i：`main` 黑帧 **56/170 = 32.9%**、间隔 `[3,3,3,…]`（黑帧号 136,139,142,…）。

| 标签 | 黑帧（56 帧） | 非黑帧（114 帧） |
|---|---|---|
| `trace0deferred:c4`（AO pass 的输出，`deferred1` 的**输入**之一） | **52.498 恒等** | **52.498 恒等** |
| `trace1deferred1:c4`（云射线长度，`deferred1` 的输出） | **54.213 恒等** | **54.213 恒等** |
| `trace1deferred1:c0`（颜色输出） | **0.000（56/56）** | 21.389 |
| `trace1deferred1:c5`（反射输出） | **0.000（56/56）** | 50.355 |
| `c0@chainStart`（进链时槽 0 的内容） | 27.028（= **上一帧**的值） | 18.029 / 27.028 交替 |

三条读出来的东西：

1. **不是云那条链算出的 0**。`deferred1` 写进 colortex4 的 `cloudViewLength` 在黑帧与非黑帧
   **逐位相同**（54.213）⇒ 云射线那段没换相。⇒ §七 里「要读 `cloud.a`/`cloud.rgb`」那条
   下一步**优先级下降**：云是闸门（`CLOUDS=0 ⇒ 0/171` 仍然成立），但黑帧的**触发**不在云自己的量上。
2. **黑帧上 `deferred1` 的两个「依赖输入」的输出同时精确为 0**（颜色 c0、反射 c5），
   而它**自己算出来**的 c4 不受影响。反射那条要 `GetMaterials(...)` 给 `smoothness > 0` 才会写非零
   （`deferred1.glsl:363-365`）⇒ c5 恒 0 = **材质槽也读到 0**。
   ⇒ 一句话：**链在那一帧看到的所有 colortex 输入都是 0**，而不是「某个量算错了」。
3. 🔴 **可是探针说图不是空的**：`c0@chainStart`（`MrtTerrainPass.slotTexture(0)`，链开跑前取）
   黑帧上是 **27.028**，且**逐字等于上一帧**的值。
   ⇒ 「探针读到的那张图有内容」与「着色器采到的那张图是 0」**同时成立** ——
   这两件事之间只可能是**绑定的视图不是被写的那一张**。

⇒ **下一步（已开任务）**：给观测面加一个**每帧的代次索引**——把「槽 k 的 `cur`（被读那一代）」
在 ① 地形 pass 写完翻代后、② 链开跑前、③ 每个链 pass 绑定采样器时 各打一次，
与探针的帧号对齐。**这个数一旦拿到，周期 3 就 either 归零 either 被排除**，
不再需要在 AO / 云 / 曝光之间猜。
🔖 口径：探针读的是 `POOL` 当场返回的纹理，绑定读的是 `poolView(slot)` 当场返回的视图 ——
两者**在同一个时刻**取才可比，所以这一步必须在**绑定处**打点，不能在探针处猜。

## 六bis、本轮自己犯的一个流程错（记下来，因为它差点毁掉一份证据）

派生 h50j 时用了 `sed -i` **就地**改 h50h 的臂脚本（把 `DIR` 改成 h50i），于是 h50j 的派生
`sed 's#DIR=…h50h-gap029-rerun#…#'` **匹配不到任何东西** ⇒ h50j 整跑写进了
`/tmp/opencode/h50i-stub0/`，**覆盖了 h50i 的原始日志**。
⇒ 数字与截图都已经在 §一 的表与 `evidence/h50-images/` 里落库，结论不受影响；
但**原始 h50i 日志没了**。
🔖 纪律：派生臂脚本只能 `cp` 之后再改副本，**不许 `sed -i` 改母本**；
臂脚本的 `DIR` 应当在脚本内**自证**（打印出来）而不是靠外部改名。

## 八、h50k：新加的「每帧代次索引」**否证了我上一条的推论**

h50j 的读数是「链采到的图是 0，而探针读同一槽有内容」，我据此写下的下一步是
「**绑定的视图不是被写的那一张** ⇒ 去查代次」。h50k 就是去量这个的：
`FrameApi.requireChainReady` 里抄一份链开跑前的各槽被读代，链尾再抄一份，
`[GAP-020/gen]` 在**探针开着时每帧一行**（探针关着时保持原来 120 帧一行，热路径日志纪律不变）。

同一臂复现：`main` 黑帧 **57/170 = 33.5%**、间隔逐字 `[3,3,3,…]`。

```
chainFrame=283 开跑前 c0=1 c1=0 c2=0 c4=0 | 链尾 c0=0 c1=1 c2=0 c4=0
chainFrame=284 开跑前 c0=1 c1=1 c2=0 c4=0 | 链尾 c0=0 c1=0 c2=0 c4=0
chainFrame=285 开跑前 c0=1 c1=0 c2=0 c4=0 | 链尾 c0=0 c1=1 c2=0 c4=0
chainFrame=286 开跑前 c0=1 c1=1 c2=0 c4=0 | 链尾 c0=0 c1=0 c2=0 c4=0
```

⇒ **槽 0 的被读代在链开跑前恒等于 1、逐帧不变**（与「一帧内槽 0 被写 6 次 = 偶数 ⇒ 不翻代」的预测一致），
槽 1 按预测以**周期 2** 翻（写 5 次 = 奇数）。
⇒ 🔴 **「链把 colortex0 绑到了错的那一代」这条推论作废**：代次连 2 相位都不动，谈不上 3 相位。
h50j 那句「探针读到的与着色器采到的不是同一张」**仍然可能对**，但**原因不是代次**。

顺手再排除一条（读源码，不花机时）：**深度也没有环** ——
`com/mojang/blaze3d/pipeline/RenderTarget.java` 的 `depthTexture` / `depthTextureView`
都是**单个字段**（第 27-28 行），`resize` 时整体重建（第 98-100 行），
`getDepthTextureView()` 直接返回那一个视图（第 137-139 行）
⇒ `MrtTerrainPass.depthView()` 每帧都是同一张图，**「深度为 3 的环形资源」这条候选不存在**。

⇒ 现在唯一还站得住的「3」是 **`MappableRingBuffer` 的 `BUFFER_COUNT=3`**（uniform 块环；
原版 `VulkanCommandEncoder` 允许 3 个 submit 在飞 ⇒ 环深 3 正好是「CPU 可覆写 GPU 还在读的槽」的临界值）。
但**它解释不了 h50j 的形状**（uniform 被覆写只会让值变旧，不会让 `c0` 与 `c5` 同时精确为 0）。

## 九、下一刀（想清楚了再动手，别再猜）

h50j 那三条读数里，**唯一能同时解释「c0=0 且 c5=0 而 c4 不变」**的是分支形状：
`deferred1.glsl:358 if (z < 1.0) { …材质/反射/AO/Fog… }` —— 写 `gl_FragData[2]`（=colortex5）
的反射那段**整个在这个 if 里面**，而 `gl_FragData[1] = cloudViewLength`（=colortex4）在外面。
⇒ **黑帧 = 那一帧 `depthtex0` 处处读到 1.0** ⇒ `z < 1.0` 恒假 ⇒ 反射槽保持 0、颜色走天空支路。

要证它需要一个**把链的 depthtex0 变成可读颜色**的观测面：
`vkdisp:pipeline/depthviz`（`PipelineApi:193/499-503`；片元 `assets/vkdisp/shaders/depthviz.fsh:13`
逐字就是 `texture(InSampler, vUv).r`）**已经注册但不在本帧链里执行**（`FrameApi:853` 明写）。
⇒ 下一刀 = 探针开着时把 depthviz 作为**额外一级**画进一个 RGBA8 池槽并逐帧取数，
判据一句话：**黑帧那一帧的 depth 读数是不是恒等 1.0**。
🔴 不要拿 `DepthGlProxy` 那张 R32F 直接喂现有探针 —— 探针的字节布局按 RGBA8 算，
格式不对会产出「看起来像数字」的垃圾（X37 那一族）。

## 十、仓库现在的状态与下一步（按价值排序）

- `AtmosphereBuiltins` + 三条 `values.put` + `[GAP-029]` 自报：**保留**（§三 证明它进了 uniform 块，
  §一 证明它对黑帧中性）。
- `ShadowStubs` 深度清屏值：**回到 0.0**，注释改成「测量选出来的 + 口径仍未判」；
  守卫测试 `stubDepthStaysAtMeasuredBest` 钉这两格数字，并断言源码里必须留着「未判」那句。
- 下一步：
  ① **每帧代次索引**（§六 的结论直接指向它）：在「地形翻代后 / 链开跑前 / 每个链 pass 绑定
     `colortexN` 时」三处打同一口径的代次号，与探针帧号对齐 ⇒ 一次测完就能把周期 3 归零或排除；
  ② **周期 3 的来处**（`frameCounter` 已排除；`depthGlProxy`/`AO_STRENGTH`/`TAA_MODE`/桩清屏值
     都只改变「3 个相位里几个落黑」，不改变周期）；
  ③ GAP-029 的下一批未填名（7 个生物群系旗标 + `darknessFactor` 一族）；
  ④ 画面侧「天空为什么是黑的」仍未判 —— h50i/h50j 的截图里天空是黑的、右侧那片暗绿等高线状的云
     **照旧在** ⇒ GAP-029 的三名**没有**把天空/云的颜色救回来。
