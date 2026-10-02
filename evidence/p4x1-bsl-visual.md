# P4.1 BSL 视觉正确性基线（2026-10-02，luma 量化 A/B + 用户目检）

> G-01 文本摘要：日志/截图关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`run/screenshots/`），本文件只存可复核事实。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- **环境**：WSL2，lavapipe（llvmpipe / Mesa 软件 Vulkan）；JDK 25；NeoForge 26.3.0.23-beta。
- **配置**：`run/config/vkdisp-client.toml` 默认 `shaderPack = ""`（按扫描序自动选 BSL，B 在 v 前）；
  A/B 通过配置热加载改 `shaderPack = "none"`（passthrough）触发资源重载（p417 同源方法）。
- **取证窗口**：启动 → 进世界（quickPlay New World，世界时钟冻结 `worldTime=0` = 黎明）→
  渲染若干帧后 `VkDispPackScan` 在 `ClientResourceLoadFinishedEvent` 打印
  `pack compile done: stages=190 ok=190 failed=0`（L2536）。
- **视觉判读手段（重要限制）**：**本环境 Agent 不支持查看图片**（PNG Read 被内容过滤拒绝），
  p418 用的「逐像素 diff」无法在本会话落地。改用 **luma 分带量化**（见下）+ 日志诊断做连贯性判读；
  **最终「观感是否正确」由用户目检确认**（见判定）。

## 机制（本轮目的）

**P4.1 验收「BSL 主要效果可用」的视觉基线**：141 阶段矩阵修复落地后（`stages=190 ok=190 failed=0`，
`evidence/p4xx-141-matrix.md`），需确认 BSL 实际渲染连贯、无静默破坏——尤其 141 修复的两类替换：
属性旧名→合法名、矩阵旧名→ gbuffer 矩阵、以及 `gl_TextureMatrix[n]→mat4(1.0)`（动画纹理/图集 UV 命中此
替换）是否破坏视觉。无 Iris 金标准对比（环境缺 Iris = 登记限制），故以「渲染连贯 + 无错误 + 用户目检」判可用。

## 阶段对照（单会话，`run/logs/runclient-p41-visual.log` 行号可复核）

| 时刻 | 动作 → 日志锚（行号） | 说明 |
|---|---|---|
| 17:04:56 | `pack compile done: stages=190 ok=190 failed=0`（L2536）+ `client resources loaded (initial=true)`（L2537） | BSL 自动选中、编译全绿、进世界 |
| 17:04:59 | `pipeline count check: registered=9, compiled=9 (aligned)`（L2581） | 管线计数对齐 |
| 17:07:0x | 改 `shaderPack="none"` → 配置热加载 → `client resources loaded (initial=false)`（重载） | A/B：切 passthrough |
| 17:08:51 | `pack compile done: stages=190 ok=190 failed=0`（L3700，重载后重扫） | 重载后重扫（none 仍走同扫描入口，计数口径不变） |
| 17:09:xx | 游戏客户端退出（Dev lost connection），日志落盘 | 取证完成 |

## 截图与 luma 量化（A/B）

| 文件 | sha256 | content 均值 | 天空带(y150–300) 均值 | 地面带(y450–530) 均值 |
|---|---|---|---|---|
| `run/screenshots/p41-bsl-world.png`（930×577） | `049a579e427c1016d55f70e2b8f4ad734f4de2ea8fca464e8df53138d967050a` | 20.9 | 6.9 | 26.6 |
| `run/screenshots/p41-none-world.png`（930×577，passthrough） | `d11a2e98d0a03d7f53aedd00ab08252640e62a2238b982c237ecfd55b58f0be2` | 34.1 | 28.3 | 38.3 |
| **BSL / none 比值** | — | 0.61 | **0.24** | 0.69 |

> 量化口径（p418 同源）：luma = (r·299+g·587+b·114)/1000；内容区 crop (35,62)-(w-37,h-39)，步长 3 采样。
> 截图时刻相机朝上、画面大部分为天空，故天空带样本量远大于地面带。

## 判定

| 判据 | 结果 |
|---|---|
| 渲染连贯（非全黑/全白/彩色尖刺） | ✅ BSL content 均值 20.9、`distinct_sampled`=104（正常值），无尖刺/全黑/全白特征 |
| 无 vkdisp ERROR | ✅ 日志 grep `vkdisp.*ERROR` 计数 = 0；仅已知 ftransform WARN（T11 显式、良性，gl_Vertex→Position 冻结字面名展开） |
| BSL 偏暗（尤其天空 0.24×）是否渲染缺陷 | ✅ **用户目检确认 BSL 观感正常**：更暗/风格化晨昏色（非纯黑、非颜色错乱），非渲染缺陷 |
| 141 矩阵修复未破坏视觉 | ✅ 渲染连贯、无错误、用户确认观感正常；`gl_TextureMatrix→mat4(1.0)` 未造成可见 UV 灾难 |
| P4.1 验收「BSL 主要效果可用」 | ✅（在 Iris 对比局限内）达成：编译 190/190 + 渲染连贯 + 用户目检正常 |

**未覆盖（登记）**：

1. **本环境 Agent 不支持查看图片**（PNG Read 被内容过滤拒绝）—— 视觉「观感是否正确」改为
   luma 量化分带 + 日志诊断 + **用户目检**三方交叉；逐像素 diff 法（p418）本会话无法落地。
2. **Iris 金标准对比 = 环境极限**（04-SPEC/ handover 登记）—— P4.1 无法做逐效果像素对比，
   以「连贯 + 无错误 + 用户目检」判可用。
3. **§9.3 待登记课题未逐项目检**：`单位纹理矩阵视觉正确性`、`属性绑定数据运行时正确性` 本轮仅以
   luma 量化未见明显异常，未做逐效果（动画纹理/太阳方向/阴影方向）目检；留待后续专项或用户反馈。
4. A/B 时相机固定朝天、世界时钟冻结黎明，未验证正午/不同朝向与夜间 BSL 表现。
