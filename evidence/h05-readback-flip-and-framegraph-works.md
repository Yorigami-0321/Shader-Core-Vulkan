# H05 · 回读 blit 的 V 翻转 bug + **帧图内插 pass 其实是通的**（撤回 h04 §9）

> 验证对象：`PipelineApi` 新增的**不翻转**回读管线（`vkdisp:pipeline/mrtview_noflip`）
> + `MrtProbe` 两处回读改用它 + `MrtTerrainPass` 的帧图执行顺序探针（2026-10-03 晚）。
>
> **两个结论**：
> ① 🔴 **回读路径此前把画面上下颠倒了** —— 根因是给「中间目标 → 中间目标」用了
> 专为「包 composite 的 OF vUv 语义」准备的 `fullscreen_flipv`。
> ② ✅ **GAP-003 方案 A 的生产形态（帧图内插 pass）本来就通** ——
> h04 §9 那句「帧图内绘制从未成功」**作废**，它是深度修复前的旧观察 + 上面这个显示 bug 共同造成的误判。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan + lavapipe） |
| 客户端 | `./gradlew runClient -PquickPlay`，**5 趟** |
| 库存 | `shaderPack="none"`（隔离变量） |
| 操控方式 | 🔖 **MCP（`minecraft` server → mcpfabric NeoForge mod）** —— 本轮起改用 MCP 操控与取证 |
| 日志 | `run/logs/latest.log`（帧图+不翻转那一趟），sha256 `f7de53da…` |
| 残留进程 | ✅ 每趟 `tools/vulkan-local/game_procs.sh kill`；收尾**残留 = 0** |

## 0. 为什么这一轮换成 MCP 操控

用户要求「用 MCP 操作游戏验证」。核实结果与接线：

| 项 | 状态 |
|---|---|
| MCP server 本体 | 已在 `/home/yorigami/Minecraft/mcpfabric-src/mcp-server/dist/index.js`（75 个工具，含 `vision.screenshot`） |
| 桥接模组 | `mcpfabric-neoforge-0.5.0+26.3.jar` 已在 `run/mods/`；`26.3-neoforge` 是其 stonecutter 活动节点 |
| 🔴 **缺口** | 它只注册在 **CodeBuddy** 的配置里（`~/.codebuddy/.mcp.json`），**opencode 未加载** ⇒ 我这一轮开局时工具目录里只有 5 个 `opencode.*` |
| 处置 | `opencode mcp add --global minecraft …`（写进 `~/.config/opencode/opencode.json`，**不入库**，避免把 token 提交进仓库）⇒ 已热加载 |

🔖 **MCP 让取证质量实质提升**（都是本轮真实用到的）：

| 能力 | 消除了什么 |
|---|---|
| `control.look` | 视角**确定性赋值**（`setYRot/setXRot`，非模拟输入）⇒ A/B 对比不再受「玩家随手转了视角」干扰 |
| `world.setTime` / `setWeather` | 🔖 **消除了昼夜漂移**。h04 期间多趟截图因世界时间推进而不可比（一次是白天雪原、隔一次变夜间），曾让我误判过信号 |
| `vision.screenshot` | 直接抓 `mainRenderTarget` ⇒ 不依赖 X11 焦点、不受窗口装饰与失焦影响 |
| `players.setGameMode` / `teleport` | 一条命令进创造模式 + 固定机位 |

## 1. 用户报告的现象

用户在窗口里看到画面**上下颠倒**。用 MCP 把它变成可判定的事实：

```
set_time(6000) → 正午
set_weather(clear)
teleport(Dev, 17.5, 90, 399.5)      # 升到地面以上
look(yaw=90, pitch=0)                # 严格地平线视角
screenshot()
```

**正确渲染下**，pitch=0 时地面必在**下半屏**、天空在上半屏。实测相反：
**地形挂在上半屏并向下延伸**，下半屏是纯色（我的诊断清屏色）。

## 2. 决定性实验：把「回读」这一环摘掉

翻转可能来自两处，我用一次 A/B 切开：

| 配置 | 路径 | 结果 |
|---|---|---|
| `terrainToMain=true`、`mrt.enabled=false` | 我方 pass **直接写主目标**（不经回读 blit） | ✅ **地形朝向正确**（地面在下、仙人掌站立） |
| `terrainToMain=false`、`mrt.enabled=true` | 写 colortex → **回读 blit** → 主目标 | 🔴 上下颠倒 |

⇒ **我方 pass 的渲染是正确的；翻转在回读 blit。**

## 3. 根因

`MrtProbe.draw()` / `drawExternalView()` 用的是 `PipelineApi.mrtViewPipeline()`，
其顶点着色器是 **`vkdisp:fullscreen_flipv`**（`vUv = (uv.x, 1.0 - uv.y)`）。

项目自己的注释（`assets/vkdisp/shaders/fullscreen_flipv.vsh` 头部）写着：

> ⭐ 实测约定（P-1f）：**中间目标 → 主目标** 的采样必须 V 翻转 …

🔖 **这条约定被我误推了一层**：那次翻转补偿的是
**「包 composite 片元按 OF 原始 vUv 采样」**这件事，**不是引擎本身的取向**。
我方回读采样的是**引擎自己渲染出来的 colortex** —— 它与主目标**同一取向**
（两者都由 `core/terrain` 用同一投影、同样的 Vulkan 帧空间坐标写入）
⇒ 再翻一次就等于把画面**上下颠倒**。

**处置**：新增 `vkdisp:pipeline/mrtview_noflip`（与原管线只差顶点着色器），
两处回读改用它；**原翻转管线保留**（合成链仍需要它）。

## 4. 🔴 这个 bug 为什么会躲过 h02 与 h04

| 轮次 | 回读的内容 | 为什么看不出翻转 |
|---|---|---|
| h02 | **R 通道常量指纹 + 平滑渐变** | 指纹与 V 无关；渐变上下翻转在数据上**不可区分** |
| h04 | 真地形 —— 但我**读错了** | 我看到「地面挂在上方」，**理解成「相机在仰视」**，没意识到 `pitch=0` 下地面本该在下方 |

⇒ 🔖 **教训（值得立规）**：**判据内容必须能区分被测的那个属性**。
用「平滑渐变 / 常量指纹」验证「采样坐标是否正确」是无效判据 ——
它对坐标错误完全不敏感。要验坐标，就得用**有明确空间结构**的内容（地形是最好的）。

## 5. ✅ 附带结论：帧图内插 pass 本来就是通的（撤回 h04 §9）

h04 §9 写「帧图内绘制从未成功」。**该结论作废**，两个原因：

1. 🔴 它建立在**深度修复之前**的观察上。h04 的帧图模式几次观察全在反向 Z 修复**之前**，
   修完**没有重测** ⇒ 把「同一个深度 bug 的另一种表现」当成了「帧图模式独有」。
   **违反本项目铁律**：改了共享状态后，所有旧的「某路径不通」结论必须**重测**。
2. 🔴 被上面这个显示 bug 掩盖 —— 帧图模式其实画出了地形，只是颠倒着，我读成了「没画」。

**重测结果（`terrainAfterLevel=false`，帧图内插 pass）**：✅ 地形朝向正确、内容正确。

附带测到两条以前不知道的事实：

| 事实 | 值 | 意义 |
|---|---|---|
| 🔖 我方 pass 在帧图里的**执行位置** | `ORDER-MARK my-pass`（行 1161）**早于** `ORDER-MARK vanilla-main-pass`（行 1163） | 我方 pass **排在原版主 pass 之前**（不声明资源依赖 ⇒ 由 `resolvePassOrder` 排在前面）。这**不是问题**：我方用**独立 colortex + 独立深度**，不与主 pass 共享附件 |
| draw 数据充足度 | `SOLID{groups=1,draws=580} CUTOUT{groups=1,draws=414} TRANSLUCENT{groups=1,draws=249}` | 三层都有 draw ⇒ 捕获数据完整 |
| 管线计数 | `registered=24 compiled=24 (aligned)` | 新增的不翻转管线也编译通过 |

🔖 **对方案 A 的意义**：生产形态（「帧图内插 pass」）**已通**，
`mrt.terrainAfterLevel` 这个 A/B 开关可以退役（保留无害）。
下一轮的阻塞不再是「帧图内插 pass」，而是
**colortex1/2 的 gbuffer 语义**（需要包的自研 `gbuffers_terrain`）。

## 6. 另一条被证实的旧结论（上一轮刚升级的）

顺带复核：`FrontendRenderPass#setPipeline` **确实**校验
「render pass 颜色附件数 == 管线颜色目标数」，不等就抛
`IllegalStateException`（本轮实测踩到：探针误用单目标管线 ⇒ 直接崩游戏，已改用 3 目标的 `vkdisp:mrt`）。

⇒ 🔖 修正 h04 §6 的一处措辞：**「附件数不匹配」这一类是响亮失败，不是静默失效**；
真正静默的是**深度清屏值**（没有任何一层检查它）。两类的可诊断性不同，要分开记。

## 7. 遗留（本轮明确**没有**证明的）

| 没证明 | 为什么 |
|---|---|
| ⛔ colortex1/2 的 gbuffer 语义 | 原版 `core/terrain.fsh` 只写 location 0（h04 已测得槽 1 = 纯清屏色）。要等包的自研 `gbuffers_terrain` 接入 |
| ⛔ 槽 0 里的**天空** | 天空在原版独立的 sky pass 里，我方 pass 只画地形 ⇒ colortex0 的天空区域是清屏色（截图中上半屏的纯色即此）。**符合预期**，不是缺陷 |
| ⛔ 画面改进 / 性能 | 主目标由原版绘制 ⇒ 地形被画两遍；代价未测 |
| ⛔ 与主场景的互相遮挡 | 我方用**独立深度**，本 pass 内地形不与主场景遮挡。诊断形态，不影响结论 |
| ⛔ 半透明地形的混合顺序 | 仍只画 `OPAQUE` 组（TRANSLUCENT 组有 249 个 draw 但未画） |

## 8. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| `run/config/vkdisp-client.toml` | `shaderPack` → `"none"`；`mrt.*` 六个开关 | ✅ `shaderPack = "BSL_v10.1.8"`；`mrt.enabled/terrain/terrainAfterLevel/terrainToMain/terrainFullscreenProbe = false`、`attachments = 3`、`viewSlot = 0` |
| `run/options.txt` | `pauseOnLostFocus` → `false` | ✅ `true` |
| **世界存档** | 🔖 **被改了**：MCP 施放了 `time set 6000`、`weather clear`、`gamemode creative`、`teleport Dev 17.5 90 399.5`、`look(yaw=90,pitch=0)` | ⚠️ **未还原**（时间/天气/坐标/视角是刻意固定以保证可复现，属取证环境变更；如需回到原状请说一声） |
| 全局 opencode 配置 | `~/.config/opencode/opencode.json` 新增 `minecraft` MCP server（含 token） | 🔴 **有意保留**，不入库；换机器需按 `VKDISP-接入手册.md` §7 重配 |
| 游戏进程 | **5 趟** runClient | ✅ 每趟 kill；收尾**残留 = 0** |