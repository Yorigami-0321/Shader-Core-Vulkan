# P4.1.4 final 步接线证据（2026-10-01，两跑闭环）

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
- 客户区口径与 p412/p413 一致：crop `(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`，
  非黑 = `luma>2`，客户区总像素 = 408408。
- 存档基线沿用 p413 取证：`worldTime=0`（时钟冻结）、`raining=0`（晴天）。

## 本轮改动对画面的语义

final 链（包声明 final 片元时激活）：`composite → offscreen3 → main`（final 是最后且
唯一的主目标写入）；final 顶点**不翻转**（attachment 恒等拷贝推导，PipelineApi
`FINAL_PIPELINE_ID` javadoc）：composite 换附件不换光栅化 → offscreen3 的 texel 逐位
等于旧链路 main 的 texel → final 恒等采样拷回 → 显示与旧链路一致（若 flipv 会垂直镜像）。
**截图方向判定 = 该推导的实测检验点。**

## 两跑对照（run1 暴露日志门缺陷 → 修复 → run2 归零）

| 跑 | 时段 | 变更 | final 证据 | vkdisp ERROR（排除 pack compile） | 客户区 mean_luma / 非黑 | 判定 |
|---|---|---|---|---|---|---|
| run1 | 12:05–12:07 | 首版接线 | `final source ready: present=true`、`layout parsed: slot=final 24/512`、`registered=9 compiled=9`、`final chain wired`、`buffer created: slot=final` —— 但 **`uploaded: slot=final` = 0 条** | **0** | **9.1930** / 97008（23.75%） | 链路通、画面同基线，但暴露 `OfUniformManager.logUploadOnce` **双布尔门缺陷**（`slot=final` 落 else 分支被 composite 标志吞掉） |
| run2 | 12:12–12:16 | 门改按槽位名 Set（`UPLOAD_LOGGED_SLOTS`） | **`uploaded: slot=final members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0`** | **0** | （未重拍，见下注） | **全绿**：第三槽上传摘要可见，样本与存档基线逐字吻合 |

注：截图只有 run1 一张 —— 两跑的渲染路径代码**逐字节同一份**（run2 唯一差异是日志门，
只影响打印），故截图取 run1、日志证据取 run2，文件各自 sha256 对账。
run1 缺陷是「日志吞行」而非「数据未写」：`OfUniformManager.write` 每帧照常执行
（run2 行即其 stats 输出），缺陷只在一次性摘要的门逻辑。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/latest.log`（=run2，12:11:59–12:16:07，含 uploaded: slot=final 修复行） | `7902dab5cf8dffb8df3f62913c9c17bb033563f7b543a2df505ff76883f6c387` |
| `run/logs/2026-10-01-1.log.gz`（=run1，12:04:50 启动，缺 uploaded: slot=final = 缺陷对照） | `b5df6652ebd2781c286c1af24bfd5f0c6800bee4f7ed14df6753cc11b0fe40f5` |
| `tools/vulkan-local/evidence/p414_world.png`（run1 截图，33073 B，930×577） | `461868b703a0641a6b62678d9972326ff0229d8ab7ecb04558d9c76bfbe50a16` |

## 关键日志行（原文，run2）

### 注册与源生成

```
[01Oct2026 12:12:07.725] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline registered (5/9): vkdisp:pipeline/final
[01Oct2026 12:12:11.070] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final source ready: present=true pack=BSL_v10.1.8 bytes=5616
[01Oct2026 12:12:16.254] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack compile done: stages=190 ok=49 failed=141
[01Oct2026 12:12:16.328] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=9, compiled=9 (aligned)
[01Oct2026 12:12:16.330] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final chain wired: composite -> offscreen3 -> main (pack final)
[01Oct2026 12:12:16.333] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite input source: fixture offscreen1
[01Oct2026 12:12:19.164] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite input source: deferred output (P3.3)
```

- `final source ready: present=true pack=BSL_v10.1.8 bytes=5616`：BSL `world0/final.fsh`
  FRAGMENT 入选（同包同维度配对，P4.1.1 已证其 `spvBytes=11016` 驱动编译通过）；
  包 final.vsh 的 VERTEX 失败（`gl_MultiTexCoord0`）与本轮无关 —— 管线顶点是
  `vkdisp:fullscreen`，包顶点不用。
- `stages=190 ok=49 failed=141` 与 p412/p413 逐字一致（141 阶段失败 = P4.2 登记范围）。

### 布局解析（三槽齐）

```
[01Oct2026 12:12:11.070] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins layout parsed: slot=composite members=42 bytes=608
[01Oct2026 12:12:11.070] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins layout parsed: slot=deferred members=24 bytes=512
[01Oct2026 12:12:11.071] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins layout parsed: slot=final members=24 bytes=512
```

final 24 = 目录 23 + 收编净增 1（`aspectRatio`；final 只读 colortex1 + 视口四件套，
收编集比 composite 小得多）—— 与 deferred 同为 24/512 但收编成员不同，第三布局独立成槽。

### 上传摘要（一次性 INFO，run2 终态；run1 门缺陷对照）

```
[01Oct2026 12:12:16.332] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=composite members=42 bytes=608 written=26 unfilled=16 mismatched=0 overflow=0 sample={{far=32.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[bedrockLevel, blindFactor, darknessFactor, nightVision, darknessLightFactor, endFlashIntensity, shadowFade, timeBrightness] …+8
[01Oct2026 12:12:16.332] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=final members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0 sample={{far=32.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[]
[01Oct2026 12:12:19.163] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=deferred members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0 sample={{far=1024.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[]
```

- 三槽各一条：`created: slot=final bytes=1024` 环创建 + `uploaded: slot=final written=24`。
- composite `far=32.0`（菜单首帧 placeholder）/ deferred `far=1024.0`（世界首帧 depthFar）
  —— 与 p413 逐字一致；final 首帧也在菜单（32.0），同 composite 槽语义。
- run1 同日志有 `created: slot=final` 但 `uploaded: slot=final` **0 条**（双布尔门吞行）；
  修复（按槽位名 Set 门）后 run2 出行 = 缺陷闭环。

### 错误计数（run2，grep 复算）

- `grep -c "fullscreen pass failed"` = **0**；`grep -c "Missing uniform"` = **0**
- vkdisp ERROR（排除 `pack program compile FAILED`，141 条属 P4.2 登记范围；
  另排除 narrator/sound 两条环境 ERROR）= **0**
- `pipeline count check: registered=9, compiled=9 (aligned)`（P3.2 以来首次 8→9）
- `composite input source` 双切换（fixture→deferred→退出回 fixture）= 链路态机正常

## 判定

| 判据 | 结果 |
|---|---|
| 第 9 条管线注册且 compiled 计数对齐 | ✅ `(5/9)` 注册行 + `registered=9, compiled=9 (aligned)` |
| final 源入选同包同维度（present=true） | ✅ `present=true pack=BSL_v10.1.8 bytes=5616` |
| final 链一次性埋点可见 | ✅ `final chain wired: composite -> offscreen3 -> main (pack final)` |
| 第三布局/第三环/上传摘要三件套 | ✅ `slot=final 24/512` + `created bytes=1024` + `written=24 unfilled=0` |
| 上传样本与存档基线吻合 | ✅ `far=32.0 worldTime=0 rainStrength=0.0`（菜单首帧；基线同 p413） |
| 驱动零回归（ERROR/绘制链） | ✅ 0/0/0，链路 wired，输入双切换正常 |
| 画面无回归且**未镜像**（恒等拷贝推导实测） | ✅ 9.1930/23.75% vs p413 基线 9.0767/22.45% 同量级；截图构图与 p413_world2 同向（树冠朝上、无垂直翻转） |
| run1 暴露的日志门缺陷闭环 | ✅ run1 0 条 → run2 `written=24` 行可见（门改按槽位名 Set） |

**未覆盖（登记）**：final 的**视觉效果强度**（BSL final = 色差 + 锐化，`SHARPEN`/
`CHROMATIC_ABERRATION` 选项默认值下的像素差未做 A/B —— 本轮判据是接线正确性与零回归，
效果强度留 P4.2 切包回归时按选项开关取证）；composite1–7 多步链（final 的完整
OF 输入在真 OF 里经过 composite1–7 后才进 final，本轮 single-composite 输入是最小近似，
18-PARALLEL P4.2 范围）；包 final.vsh VERTEX 阶段失败（不使用包顶点，恒不触发）。
