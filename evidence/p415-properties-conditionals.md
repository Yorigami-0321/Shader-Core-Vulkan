# P4.1.5 properties 条件编译证据（2026-10-01，两跑闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- 进世界后取证：`run/logs/latest.log`；截图 `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`
  （窗口 id 每次启动变化，先 `--list-windows`）。
- 环境：WSL2，llvmpipe 软件 Vulkan（Mesa 26.2.3）；存档基线同 p413（worldTime=0、晴天）。
- 客户口径同 p414：crop `(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`。

## 本轮改动对日志的语义

`ConditionalPreprocessor` 扩展 shaders.properties 条件编译（18-PARALLEL §6.2）：

1. **CRLF 归一先于续行判定** —— `.properties` 行尾奇数个 `\` = 续行，但 BSL 文件全文 CRLF，
   `\` 后紧跟 `\r` 会破坏「看最后一个字符」的续行判定 → 后半行缺 `=` → 整份解析失败
   （run1 实证）；
2. **`#elif` 链**（Frame 重设计 `parentInclude/taken/include/afterElse`：嵌套在被剔除
   父级下的 `#else` 不得放行，旧 `!include` 反转会错误放行）；
3. **数值比较**（`== != < <= > >=`，两侧恒定求值不用 Java 短路；标识符已定义 → 1、
   未定义 → 0，口径对齐本仓库自有 `DefineProcessor.ExprEval`）；
4. 指令头/空表达式显式报错（T11），`#define`/`#include` 照旧显式拒绝。

**判定信号**：`shaders.properties 解析失败` WARN 归零 + `pack[1] … profiles=[…]` 由
`[]` 变五档（解析成功才拿得到 profile 段）。

## 两跑对照（run1 暴露 CRLF 缺陷 → 修复 → run2 归零）

| 跑 | 时段 | 解析失败 WARN | profiles= | 判定 |
|---|---|---|---|---|
| run1 | 13:03:40–13:09:36 | **3 条**（2× `composite source diagnostic` + 1× `pack diagnostic`，同一句） | `[]` | 首版续行判定未先摘 `\r` → BSL `shaders.properties` 行 129 续行半截无 `=` → 整份按无配置继续，profiles 一并丢失 |
| run2 | 13:10:14–13:14:51 | **0** | `[ULTRA, MINIMUM, MEDIUM, LOW, HIGH]` | **全绿**：五档 profiles 枚举可见，其余日志口径与 run1 逐字一致 |

- **WARN/ERROR 集合 diff（去时间戳逐行比对）**：run1 独有 = 上述 2 条唯一文本
  （合计 3 次出现）；run2 独有 = **0**（零新增告警，无副作用回归）。
- 两跑共同不变量：`stages=190 ok=49 failed=141`（141 阶段失败 = P4.2 登记范围）、
  `registered=9, compiled=9 (aligned)`、`final chain wired`、三布局 42/24/24、
  `builtins uploaded` 三槽（composite written=26 unfilled=16 / final 24/0 / deferred 24/0）。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/p415-run1.log`（664423 B，13:03:40–13:09:36，解析失败 ×3 缺陷对照） | `09e791e87650addb96e58c0938097cbb4a1ebc6a3d5f6b616a26490e6428ba74` |
| `run/logs/p415-run2.log`（661980 B，13:10:14–13:14:51，解析失败 0 + profiles 五档） | `881d95876779119f5fc1667e2083162f05ec7b348be63cc7cc5cdb3a2da0eff4` |
| `tools/vulkan-local/evidence/p415_world.png`（104548 B，930×577，run2 窗口截图） | `bb788bdca3b140cc06904e960f3251a21a9df88f7de5c3f03546f3c3d668f171` |

## 关键日志行（原文）

### run1：解析失败 ×3（同一句，三个日志前缀）

```
[01Oct2026 13:03:52.592] [Render thread/WARN] [dev.vkdisp.VkDisp/]: vkdisp: composite source diagnostic: WARN: shaders.properties: vkdisp: shaders.properties 解析失败，按无配置继续: vkdisp: shaders.properties 行缺少 '='（行 129）：smooth(11, if(in(biome, 3, 25, 34, 131, 162), 1, 0) * yCold1, 10, 10) + \
[01Oct2026 13:03:54.957] [Render thread/WARN] [dev.vkdisp.VkDisp/]: vkdisp: pack diagnostic: WARN: shaders.properties: vkdisp: shaders.properties 解析失败，按无配置继续: vkdisp: shaders.properties 行缺少 '='（行 129）：smooth(11, if(in(biome, 3, 25, 34, 131, 162), 1, 0) * yCold1, 10, 10) + \
```

行 129 正文以 `+ \` 结尾（CRLF 文件中 `\` 后是 `\r`）—— 续行合并失败的指纹。

### run1 → run2：profiles 由空变五档

```
[01Oct2026 13:03:54.939] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack[1] name=BSL_v10.1.8 kind=zip source=./shaderpacks/BSL_v10.1.8.zip programs=91 options=284 profiles=[] dims=[world-1, world0, world1]
[01Oct2026 13:10:28.532] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack[1] name=BSL_v10.1.8 kind=zip source=./shaderpacks/BSL_v10.1.8.zip programs=91 options=284 profiles=[ULTRA, MINIMUM, MEDIUM, LOW, HIGH] dims=[world-1, world0, world1]
```

- `options=284`、`programs=91`、`dims` 两跑一致 —— 差异只在解析成功与否（profiles 段
  是 `shaders.properties` 解析产物，`[ULTRA, MINIMUM, MEDIUM, LOW, HIGH]` 顺序 = BSL
  文件中 `profile.<NAME>` 行序）。
- 解析失败后按无配置继续 = T11 显式 WARN，不静默（run2 该 WARN 归零即闭环）。

### run2：不变量（与 run1 逐字一致）

```
[01Oct2026 13:10:30.673] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack compile done: stages=190 ok=49 failed=141
[01Oct2026 13:10:30.748] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=9, compiled=9 (aligned)
[01Oct2026 13:10:30.750] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final chain wired: composite -> offscreen3 -> main (pack final)
[01Oct2026 13:10:30.752] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=composite members=42 bytes=608 written=26 unfilled=16 mismatched=0 overflow=0 sample={{far=32.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} …
[01Oct2026 13:10:30.752] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=final members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0 sample={{far=32.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[]
[01Oct2026 13:10:33.459] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uploaded: slot=deferred members=24 bytes=512 written=24 unfilled=0 mismatched=0 overflow=0 sample={{far=1024.0, worldTime=0, frameTimeCounter=0.0, rainStrength=0.0}} unfilledNames=[]
```

### 截图（run2 窗口，仅作「世界仍可见」回归凭证）

`p415_world.png` 客户区：mean_luma=**26.2996**，非黑 176713/408408（**43.27%**）——
与 p414 基线同量级可见（截图仅证明零画面回归；方向取证属 p416）。

### 单测

`./gradlew build` exit=0；**501 用例 0 失败 0 错误**（492 → 501，+9：
`ShaderPropertiesTest` +8（数值比较 / #elif 链 / CRLF 续行回归 / 续行先于指令识别 /
嵌套 #else 放行回归 / 或链不短路 / 比较语法显式报错 / 小数字面量）+
`BlockItemPropertiesTest` +1（idMap #elif + 续行））。

## 判定

| 判据 | 结果 |
|---|---|
| 解析失败 WARN 归零 | ✅ run1 3 条 → run2 **0** 条（WARN 集 diff = 只删这 2 条唯一文本，零新增） |
| profiles 五档枚举可见 | ✅ `profiles=[ULTRA, MINIMUM, MEDIUM, LOW, HIGH]`（run1 `[]` 对照） |
| CRLF 续行缺陷回归测试在库 | ✅ `crlfLineEndingsStillJoinContinuations`（注释标注「p415 run1 缺陷回归」） |
| 零回归（链路/上传/阶段矩阵） | ✅ registered=9 compiled=9、final chain wired、uploaded 三槽、stages 190/49/141 与 run1 逐字一致 |
| 单测 | ✅ 501 全绿（492→501） |

**未覆盖（登记）**：比较式的**取值环境**（`MC_VERSION` 编码、选项当前数值）仍按
「已定义=1 / 未定义=0」两态求值（X9 不猜，ConditionalPreprocessor javadoc 已登记）——
`#if MC_VERSION >= 11800` 类分支在值环境取证前恒走 #else；profiles 的**应用语义**
（切档真正改写 `#define`）沿 P2.4 选项链路，本轮只证枚举可见；截图方向判定不在本轮
（p416 专项）。
