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

## 五、现在仓库的状态与下一步

- `AtmosphereBuiltins` + 三条 `values.put` + `[GAP-029]` 自报：**保留**（§三 证明它到位，§一 证明它不伤黑帧）。
- `ShadowStubs` 深度清屏值：**回到 0.0**，注释改成「测量选出来的 + 口径仍未判」；
  守卫测试 `stubDepthStaysAtMeasuredBest` 钉这两格数字，并断言源码里必须留着「未判」那句。
- 下一步（按价值排序）：
  ① **云混合为什么把整帧算成 0**（`CLOUDS=0 ⇒ 0/171`，闸门已确认）——
     要一个能读 `cloud.a`/`cloud.rgb` 的观测面，而不是再猜；
  ② **周期 3 的来处**（`frameCounter` 已排除、`depthGlProxy` 只改相位计数）；
  ③ GAP-029 的下一批未填名（7 个生物群系旗标 + `darknessFactor` 一族）；
  ④ 画面侧「天空为什么是黑的」仍未判 —— 注意 §一 里 h50i 的截图：天空是黑的、
     右侧那片暗绿等高线状云**照旧在**，说明 GAP-029 的三名**没有**把天空/云的颜色救回来。
