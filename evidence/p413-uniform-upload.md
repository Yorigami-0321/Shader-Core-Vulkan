# P4.1.3 内建 uniform 上传证据（2026-10-01，两跑闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- 进世界后取证：`run/logs/latest.log`（每次 runClient 轮转，旧跑变 `run/logs/<date>-N.log.gz`）；
  截图 `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`（depth=32 窗口）。
- 环境：WSL2，llvmpipe 软件 Vulkan（Mesa 26.2.3）。
- 客户区口径与 p412 一致：crop `(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`，
  非黑 = `luma>2`，客户区总像素 = 408408。
- **存档基线（本轮新取证，决定样本值的判读）**：`run/saves/New World/data/minecraft/`：
  - `world_clocks.dat` → `minecraft:overworld.total_ticks = 0`；
  - `game_rules.dat` → `advance_time = 0`、`advance_weather = 0`（时间/天气冻结）；
  - `weather.dat` → `raining = 0`（晴天基线）；
  - 故**世界内 `worldTime=0` 是实值而非瞬态**（时钟存档即 0 且不推进）。

## 两跑对照（run1 暴露取值源缺陷 → 修复 → run2 归零）

| 跑 | 时段 | 变更 | deferred 样本 | composite 样本 | vkdisp ERROR（排除 pack compile） | 客户区 mean_luma / 非黑 | 判定 |
|---|---|---|---|---|---|---|---|
| run1 | 10:55 | 首版 gather（直读 `SkyRenderState` 提取态） | `rainStrength=1.0` `worldTime=0` | `far=32.0 rainStrength=0.0`（菜单） | **0** | **10.8189** / 119190（29.18%） | 上传链路通，但雨量样本与存档晴天矛盾 → 暴露取值源缺陷 |
| run2 | 11:18 | 修复：角度/月相/雨量直读 `attributeProbe` + `Level.getRainLevel` | `rainStrength=0.0` `worldTime=0` | `far=32.0 rainStrength=0.0`（菜单） | **0** | **9.0767** / 91697（22.45%） | **全绿**：样本与存档基线逐项吻合 |

**run1 缺陷根因（X9 取证链）**：`SkyRenderState.rainBrightness` 只在 `LevelExtractor`
跑到 `SkyRenderer.extractRenderState`（:122 `= 1 − level.getRainLevel`）后才有值，
字段默认 0；首帧/加载帧的一次性样本抓到默认值 → `rainStrength = 1 − 0 = 1.0`
（实际晴天应为 0）。同理 `sunAngle`/`moonPhase` 首帧为默认 0 / FULL。
**修复**（与 SkyRenderer 同源直读，逐位等价、不依赖提取态）：
`rainStrength = level.getRainLevel(partialTicks)`（= `1 − rainBrightness` 恒等变形）、
角度 = `attributeProbe(SUN_ANGLE/MOON_ANGLE)` 度 × π/180（SkyRenderer:119-120 同式）、
月相 = `attributeProbe(MOON_PHASE)`（:125 同源）。04-SPEC §3.2 表三行同步更新。

两跑同项对账（逐字一致，除样本雨量）：
`members=42/24`、`bytes=608/512`、`written=26/24`、`unfilled=16/0`、
`mismatched=0`、`overflow=0`、`registered=8, compiled=8 (aligned)`、
`pack compile done: stages=190 ok=49 failed=141`（= P4.1.2 基线逐字一致）、
`fullscreen pass failed` 0 次、`Missing uniform` 0 次。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/2026-10-01-1.log.gz`（=run1，10:55 段，含 rainStrength=1.0 样本） | `b1eee47b83a9e4706a59c7b7c8e14761cd3e6419cc77a69bb2b0b3249a79fa78` |
| `run/logs/latest.log`（=run2，11:18 段，rainStrength=0.0） | `361766524c09b7a6c61ef546f31792ef1b17efcdbbb133bd5dd4cf52ff897937` |
| `tools/vulkan-local/evidence/p413_world.png`（run1 截图，26935 B，930×577） | `3492d1829e0a7d159a3547e3eefad51fc4c9b422e15242e72ec51134b315489e` |
| `tools/vulkan-local/evidence/p413_world2.png`（run2 截图，24542 B，930×577） | `85f5159f7cb1924e07d2cd2f29f7a807348cb68fd8fad646b08447935f0646a0` |

日志归属对账（内容时间戳实测）：`-1.log.gz` 内 10:55:14–10:57 段 = run1
（deferred 样本 `rainStrength=1.0`）；`latest.log` 内 11:18:41–… 段 = run2
（deferred 样本 `rainStrength=0.0`）。

## 关键日志行（原文）

### 布局解析（两跑逐字一致，VkDispVirtualPack 冷路径）

```
[01Oct2026 10:55:26.458] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins layout parsed: slot=composite members=42 bytes=608
[01Oct2026 10:55:26.458] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins layout parsed: slot=deferred members=24 bytes=512
```

composite 42 = 目录 23 + 收编净增 19；deferred 24 = 目录 23 + 收编净增 1
（收编中与目录重名者顶替尾部、不重复计 —— 该规则由
`BuiltinsBlockLayoutTest.parsesRealTranslatorOutputAdoptedFirstThenCatalogTail` 锚定）——
两步收编集不同 → 双布局，与 04-SPEC §3.2「布局与缓冲」预测一致。
42×…×608 ≤ 1024 环、512 ≤ 1024 环 → 无扩容。

### 上传摘要（一次性 INFO，run2 终态）

```
[01Oct2026 11:18:51.996] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uniform buffer created: slot=composite bytes=1024
[01Oct2026 11:18:51.996] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=composite members=42 bytes=608 written=26 unfilled=16 mismatched=0 overflow=0 sample={{far=32.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[bedrockLevel, blindFactor, darknessFactor, nightVision, darknessLightFactor, endFlashIntensity, shadowFade, timeBrightness] …+8
[01Oct2026 11:18:54.692] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uniform buffer created: slot=deferred bytes=1024
[01Oct2026 11:18:54.692] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=deferred members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0 sample={{far=1024.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[]
```

判读：
- `written=26` = 填充集 23 目录 + aspectRatio/timeAngle/moonPhase 命中数取交集
  （收编成员重名不重复计）；`unfilled=16` 全为 X9 未取证项（timeBrightness 等）→ 恒 0 + 列名，
  行为 = 04-SPEC「不填充」行。
- deferred `written=24 unfilled=0`：该步块内成员全被填充集覆盖。
- `far=32.0`（composite 首帧在菜单：placeholderCamera 0.1/32 同参）/ `far=1024.0`
  （deferred 首帧在世界：`cameraState.depthFar`）—— 两槽各自首帧语义正确。
- `mismatched=0 overflow=0`：类型全匹配、608/512 ≤ 环 1024 无越界。

### 错误计数（run2，grep 复算）

- `grep -c "fullscreen pass failed"` = **0**；`grep -c "Missing uniform"` = **0**
- vkdisp ERROR（排除 `pack program compile FAILED`，141 条属 P4.1.2 登记的 P4.2 范围）= **0**
- `pipeline count check: registered=8, compiled=8 (aligned)`、
  `deferred chain wired: scene -> offscreen2 -> main (pack deferred)`、
  `composite input source: deferred output (P3.3)` 均在。

## 判定

| 判据 | 结果 |
|---|---|
| 布局解析双槽各出一行（members/bytes 与转译终稿一致） | ✅ 42/608、24/512，两跑一致 |
| 环形缓冲按槽创建（created: slot=… bytes=1024） | ✅ composite、deferred 各一 |
| 上传摘要写出真实值（written>0、样本逐项可判） | ✅ 26/24；far/雨量/时钟与存档基线吻合 |
| 不填充项零猜测（unfilled 列名，恒 0） | ✅ 16 名一次性列出（timeBrightness 等） |
| 驱动零回归（ERROR/绘制链） | ✅ 0/0/0，registered==compiled，链路 wired |
| 画面无回归（对照 p412 run3 基线 11.901/30.69%） | ✅ 9.08/22.45% 同量级可见（位姿/加载差，非黑帧） |
| 取值源不依赖渲染提取态（run1 缺陷修复验证） | ✅ run2 `rainStrength=0.0` = weather.dat 晴天实值 |

**未覆盖（登记）**：同步后 `worldTime` 的非零值观测（本存档时钟冻结为 0，无法在本环境证明
`floorMod(t,24000)` 对非零 t 的运行期输出 —— 单测覆盖公式本身）；`frameTimeCounter`
稳态值（一次性样本只拍到首帧 0.0）；eyeBrightness/timeBrightness 等登记近似的精确语义。
