# h50g · GAP-029 落地验收，以及它掉出的第二条：**阴影桩的清屏值按引擎口径写，包按 GL 口径读**

> 本轮起点：h50f 把「每 3 帧一帧整帧黑」的闸门钉到 `deferred1` 的云混合（`CLOUDS=0 ⇒ 0/171`）。
> 顺着「云为什么算成黑/暗绿」去查云读什么 ⇒ 掉出 GAP-029（19 个 OF 内建我方从来没供）。
> 本文件记的是**补了之后真机看到什么**，包括一条没人预料到的连带发现。

## 〇、环境（X53）

lavapipe（CPU 软件 Vulkan）/ 854×480 / `BSL_v10.1.8` 默认档 / 无 validation layer。
入口逐字 `bash tools/vulkan-local/run-client.sh iso -PquickPlay`。
机位用绝对 `/tp @s -7.3091 96.0 -11.0535 63.8172 <pitch>`（坐标取自存档 NBT，见
`evidence/h50e-period3-black.md` §一），时刻 6000 + `advance_time false` + `weather clear`，
`mrt.cloudsPass=false`、`mrt.depthGlProxy=false`、`pack.optionOverrides=`（空）。

## 一、GAP-029 供值本身：自报行到位，值符合预期

```
vkdisp: [GAP-029] 链上承重内建已供值: shadowFade=1.0 timeBrightness=1.0 screenBrightness=0.75
（归一化是**我方口径**：timeBrightness 由 Level#getSkyDarken() 映到 白天=1/夜晚=0，
  Iris 的实现未取到源码 ⇒ 别把本行读成「与 Iris 数值一致」）。显式未供 16 个（…）
```

- `shadowFade=1.0`、`timeBrightness=1.0` ← 晴 + 正午，两个都该是 1.0 ✅。
- `screenBrightness=0.75` ← 本车道 `options.txt` 的 gamma 是 **0.5**（不是默认 0），
  `0.5*0.5+0.5 = 0.75` ✅ —— 这一格顺带证明「读的是真选项，不是写死的 0.5」。

## 二、连带发现：阴影桩 0.0 被包读成「贴脸就有遮挡物」

补上 `shadowFade` 之后**画面整体变暗**（`evidence/h50-images/h50d-off-up.png` 的天空是淡蓝、
地形亮；h50g 第一跑的天空是纯黑、地形暗）。原因不在 `shadowFade` 写错，而在它**第一次让
阴影项成为判据**：

| 环节 | 逐字出处 | 后果 |
|---|---|---|
| 包读 `shadowtex0` 用的是**比较采样器** | `shaders/lib/lighting/shadows.glsl:3 uniform sampler2DShadow shadowtex0;` → `:61 shadow2D(shadowtex, shadowPos).x` | 存进去的深度按 **GL 口径**（1.0 = 远 = 无遮挡）解释 |
| 我方桩清到 **0.0** | `ShadowStubs.java:72`（旧）`clearColorAndDepthTextures(…, depthTex, 0.0F)` | 被读成「最近遮挡物贴在脸上」⇒ `shadow = 0` ⇒ **全场景在影子里** |
| 为什么此前一直没暴露 | `shadows.glsl:220 shadow = mix(vec3(1.0), shadow, shadowFade)`，而 `shadowFade` **从来没供** ⇒ 恒 0 ⇒ 整个阴影项被跳过 | 桩里存什么都无所谓 ⇒ **两个缺陷互相挡着**（本项目第 N 例） |

⇒ 旧代码那句注释「反向 Z 下 0.0 = 远平面 ⇒ 阴影判为无遮挡」对**引擎自己的深度**成立，
对 `shadowtex*` 不成立。守卫测试 `ShadowSamplerAliasingTest.stubDepthIsClearedToFarPlane`
当时把这句**信念**编成了断言（`depthTex, 0.0F`），本轮改成按测量到的口径断言 1.0。

## 三、A/B：同一臂只差桩的清屏值

| 跑 | 桩深度 | 配置 | 画面（同机位 pitch=-60） |
|---|---|---|---|
| h50g 第 1 跑 | **0.0** | GAP-029 已供 | 天空纯黑、**云完全看不见**（云被算成全黑 ⇒ 与天空同色）、地形暗 |
| h50g 第 2 跑 | **1.0** | 同上 | 天空仍黑，但**云出现灰白色块状结构**（`evidence/h50-images/h50g2-stub1-up.png`）、地形亮度同量级 |

⇒ 方向确认：**1.0 才是「无遮挡」在包侧的读法**。云的可见性是阴影项的直接消费点，
这一格从「什么都没有」变成「有云的形状」，是本轮唯一一处**画面侧变好**的差分。

🔴 但**没修好的也说清**：天空仍然应该是蓝的（正午、晴），地形仍然偏暗。
⇒ 另开未判项（见 §五），不许把本条读成「GAP-029 之后画面已正确」。

## 四、仪器那一格：本臂的逐帧探针窗口**没采到数**，不许读成「0% 黑帧」

两跑都打出 `main: 样本=0`。原因：等待条件 `grep 'post chain executed'` 在 500 秒内没命中
⇒ 采集窗口整段落在「还没进世界」的时间里；而截图是在窗口之后按注入的 `F2` 拍的，
所以**画面通道有效、计数通道无效**。
⇒ 本轮**不产出**「GAP-029 之后黑帧率是多少」这个数 —— 那要重跑一臂（下轮第一件事）。
🔖 形状复盘：**「样本=0」与「为 0 的帧=0」是两件事**，脚本把后者印在了前者上面。
下一版脚本必须在 `样本=0` 时直接判失败退出，而不是继续打印一个看起来像结论的百分比。

## 五、本轮之后仍然开着的格子

1. **天空为什么是黑的、地形为什么偏暗**（0.0→1.0 只把云叫回来）。候选：
   ① `depthGlProxy=false` ⇒ `isSky = z == 1.0` 恒假 ⇒ BSL 那支 `color.rgb += GetSkyColor(viewPos, false)`
   根本不参与（天空色只剩原版天空 pass 写进 colortex0 的那一份）；
   ② `shadowPosition` 依赖的 `shadowModelView/shadowProjection` 是我方占位口径；
   ③ `lightCol`/`ambientCol` 链里 `dfade = 1.0 - pow(1.0 - timeBrightness, 1.5)` 从 0 变 1 之后
   与 `sunVisibility` 的组合。⇒ **三条都没判**。
2. **周期 3 的来处**（`evidence/h50e-period3-black.md` §四 结论 2）。
3. **GAP-029 剩余 16 个显式未供名**（`centerDepthSmooth` 是「想供但供不了」那一类）。
4. **GAP-022 的看图判据**：仍不过（h50d ⇒ `depthGlProxy` 默认值继续不翻）。
