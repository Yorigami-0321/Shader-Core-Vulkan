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

## 三、~~A/B：同一臂只差桩的清屏值~~ —— **本表作废**（原因见 §四bis）

原表写下「h50g 第 1 跑（桩 0.0）⇒ 云完全看不见；第 2 跑（桩 1.0）⇒ 云出现灰白色块状结构」，
并据此说「方向确认」。🔴 **那两跑的配置根本没落地** ⇒ 画面不是「BSL + MRT 地形」的状态，
那张表**不能当任何判据**。桩 `1.0` 这个改动目前的地位是：**代码级推理成立、真机未验证**。

本轮收尾时工作树已把它**改回 `0.0`** 去补那一格（`h50i` = 「只供 GAP-029、桩仍 0.0」），
与 `h50h`（GAP-029 + 桩 1.0）合起来才能把「33.8% → ?」这一笔账拆到两个改动头上。
1.0 保留与否等两格对比出结果再定 —— 见 `evidence/h50h-gap029-rerun.md`。

## 四bis、作废原因：一个没带引号的空串让 **FML 把整份配置按默认值重建**

脚本里逐字写的是 `lane_cfg.py --lane iso "pack.optionOverrides="`（想把 h50f 留下的
`TAA_MODE=1` 清掉），落盘成 `optionOverrides =`（空值没有引号）。真机日志第 47 行逐字：

```
[modloading-sync-worker/WARN] [net.neoforged.fml.config.ConfigTracker/CONFIG]:
Failed to load config vkdisp-client.toml:
com.electronwill.nightconfig.core.io.ParsingException: Invalid value containing only whitespaces.
Attempting to recreate
```

⇒ **FML 的行为是「重建整份配置」**，不是「丢掉这一个键」。事后回读文件确认那一跑实际是：

| 键 | 那一跑的实际值 | 我想设的值 |
|---|---|---|
| `shaderPack` | `""` | `BSL_v10.1.8` |
| `mrt.terrain` | `false` | `true` |
| `mrt.packTerrainShader` | `false` | `true` |
| `mrt.pixelProbe` | `false` | `true` |

⇒ 那一跑是**近乎纯原版**：既没有包片元、也没有 MRT 地形 pass，探针更是整个关着。
所以「`样本=0`」与那两张截图都不是 GAP-029 的判据。

🔖 **三条要记住的形状**：
1. **「配置没落地」会伪装成「改动没效果」** —— 与 GAP-023（桩被 A/B 开关门住）、GAP-026
   （持久化 store 泄漏进产品路径）、h49r（档位之间继承配置）同族，这次是**写入侧**产出坏 TOML。
2. **空串必须写成 `""`**：`lane_cfg.py 'pack.optionOverrides=""'` 才落盘成 `optionOverrides = ""`
   （本轮把两种写法都实测对比过）。
3. **判据必须自带落地证明**：从 h50h 起，取证臂在起跑前**逐键回读**配置并与期望值表比对，
   任一键不符就 `abort`；等待循环里出现 `Failed to load config vkdisp-client` 也 `abort`；
   采集窗 `样本=0` 同样 `abort`。这道闸门在它**第一次**运行时就抓出了「顶层键 `shaderPack`
   被我按 section 缩进漏匹配」的 bug，否则又是一臂白跑。

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
