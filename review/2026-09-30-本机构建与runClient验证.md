# 本机构建与 runClient 验证报告

> ## ⛔ 本文档已过时（2026-10-04 标注）—— **其中的数字只对当时那一个 sha 有意义**
>
> **过时原因**：本文记录的是 `b15f55e`（2026-09-30 20:44–20:58）**本机**一次
> `gradle build` + `gradle runClient` 的执行结果，**一次性环境验证记录**。
> 此后代码已迭代多轮（源文件 77 → 107+、测试 434 → 700+），
> **本文中的构建产物、测试计数、日志行数一律不可用于判断现状**。
>
> ⚠️ 特别提醒：本文记录的「本机 Windows 环境可跑 build/test/runClient」这一**结论**
> 仍然有效且有用（它是第二验证环境的能力证明）；**过时的只是那些具体数字**。
>
> **当前状态请读**：`CHANGE_LOG.md` 顶部条目（当轮实测数字）+ `docs/QUALITY-DEBT.md`。
> **保留原因**：它是「本机具备全能力」这一事实的取证，且演示了环境验证该长什么样。

- **日期**：2026-09-30 20:44–20:58
- **执行环境**：本机 Windows 11（非 WSL、非 env-1）
- **代码基线**：`b15f55e`（P4.1.1，已 `git fetch` + `ff-only` 跟进）
- **执行范围**：本机复现 `gradle build` + `gradle runClient`，产出可复核证据
- **结论**：**本机可完整运行构建与客户端，且跑在真实独显 Vulkan 上**；旧结论「本沙箱禁止 runClient、无 GPU/图形环境」**已被实测推翻**

> 本报告与证据目录 `review/2026-09-30-本机验证证据/` 配套。所有 sha256 见该目录 `sha256-manifest.md`。

---

## 一、环境事实（三处修正）

| 项 | 旧记录 | 本机实测 | 影响 |
|---|---|---|---|
| `runClient` 可行性 | ❌ 禁止（无 GPU/图形环境） | ✅ **可运行**，真实渲染并出画 | 本环境从「只能做文档/纯逻辑」升级为**可做端到端验证** |
| 图形设备 | 无 | 1 个显示设备，物理 2560×1600（逻辑 1707×1067，DPI 150%） | 可截图取证 |
| Vulkan 设备 | — | **NVIDIA GeForce RTX 4060 Laptop GPU**（真实独显） | 非 llvmpipe 软件渲染，管线编译结果有代表性 |
| 🔴 Gradle 代理端口 | `127.0.0.1:50272` | **已失效**；当前有效 = `127.0.0.1:51274` | 直接照旧命令会 `Connection refused` 失败 |

工具链：Java **25.0.4.1** LTS + Gradle **9.4.1**（`D:/Tools/gradle/gradle-9.4.1`，与 wrapper 同版本）。

**推断**：本机为物理机/带独显的实例，`run/` 目录此前从未生成（首次运行会下载 5147 个资源）。

---

## 二、构建与单元测试

```
gradle build --console=plain   →   BUILD SUCCESSFUL in 35s
```

| 指标 | 本机实测 | env-1 报告 | 一致性 |
|---|---|---|---|
| 测试类 | **39** | — | — |
| 用例总数 | **434** | 434 | ✅ 完全一致 |
| 失败 | **0** | 0 | ✅ |
| 错误 | **0** | 0 | ✅ |
| 跳过 | **0** | 0 | ✅ |

数据来源为 Gradle 原生 XML（`build/test-results/test/*.xml`），非转述。

⇒ **这是 434 用例首次在第二环境被独立复现**，env-1 的测试结论不再是单点证据。

---

## 三、runClient 实测

### 3.1 启动链路

首次启动经代理下载 5147 个资源后正常启动：

```
[mojang/Window]: Created window using SDL video driver: windows
vkdisp: backend=Vulkan, device=NVIDIA GeForce RTX 4060 Laptop GPU
vkdisp: client setup, user=Dev
```

Vulkan 设备扩展（日志原文节选）：`VK_KHR_win32_surface`、`VK_KHR_swapchain`、`VK_KHR_surface`、
`VK_KHR_dynamic_rendering`、`VK_EXT_vertex_attribute_divisor` —— 完整的窗口呈现能力。

### 3.2 关键埋点（按时间序，全部本机产生）

```
20:53:16  virtual pack finder registered: id=vkdisp_pack required=true position=TOP
20:53:17  pipeline registered (1/8): vkdisp:pipeline/fullscreen
20:53:17  composite pipeline wired to pack shader: fragment=vkdisp_pack:composite vertex=vkdisp:fullscreen_flipv
20:53:17  composite scene pipeline wired: ... (no v-flip; P3.2 scene input)
20:53:17  deferred pipeline wired: ... (P3.3 chain step; flipv by net-parity)
20:53:17  pipeline registered (8/8): vkdisp:pipeline/blit (total=8)
20:53:17  composite source ready: fallback=true pack=null profile='' bytes=409 diagnostics=2
20:53:20  pack scan: inventory=.\shaderpacks exists=false initial=true
20:53:20  pack scan done: packs=0 programs=0 options=0 problems=1 diagnostics=0
20:53:20  pipeline count check: registered=8, compiled=8 (aligned)
20:53:20  light-space list ready: size=1 source=fixed-placeholder dir=(0.48, -0.8, 0.36) cascade0 near=0.1 far=8.0
20:53:20  geometry buffer created: size=336 expected=336 stride=28 vertices=12
20:53:20  composite input source: fixture offscreen1
20:53:20  shadow sample chain executed (854x480), lightMatrixPhase=2.145924
20:53:23 ~ 20:53:31  uniform VkDispParams phase=… at frame 120/240/360/480/600
```

**判定**：
- ✅ **8 条管线全部注册且全部编译成功**（`registered=8, compiled=8 (aligned)`）
- ✅ 虚拟资源包 `vkdisp_pack` 成功注册为 required 源
- ✅ 影子链每帧执行（`shadow sample chain executed`）
- ✅ 渲染循环稳定：帧 120→600 的 uniform phase 持续更新，无卡死
- ✅ **`vkdisp ERROR = 0`，全日志 `ERROR = 0`**

### 3.3 像素级判据（本机独立复现）

主菜单（placeholder 相机）状态下截取渲染输出：

| 区域 | 实测平均 RGB | 对照 |
|---|---|---|
| 红四边形（受光） | **(254.0, 0.2, 0.0)** | 纯红受光 |
| 绿四边形（亮部/受光） | **G = 254.2** | 纯绿受光 |
| 绿四边形（暗部/阴影） | **G = 89.0** | 阴影调暗 |
| **阴影调暗系数** | **89.0 / 254.2 = 0.3501** | **↔ 文档记录 0.35，精确吻合** |

⇒ 阴影链的**调暗系数在真机 GPU 上以 4 位有效数字复现**，证明几何→光空间→阴影采样→composite 整链在
本机 Vulkan 驱动上行为正确。

### 3.4 会话结局（完整闭环）

| 项 | 值 |
|---|---|
| 启动 | 20:52（资源已缓存后启动约 1 分钟） |
| 结束 | **21:00:59 —— `[minecraft/Minecraft]: Stopping!`**（正常关闭流程） |
| 总时长 | **9m 11s** |
| 退出码 | **`GRADLE_EXIT=0` / `BUILD SUCCESSFUL in 9m 9s`** |
| 崩溃 | 无 |
| `vkdisp ERROR` | **0**（全程） |

⇒ 客户端**完整跑完一个会话并正常退出**，不存在「启动即崩」或「退出时异常」的情况。

---

## 四、与 env-1 环境的差异（本机未覆盖的部分）

| 项 | 原因 | 性质 |
|---|---|---|
| 真实第三方包（BSL） | 本机 `run/shaderpacks` 不存在 | 预期内，非失败 |
| `pack scan: packs=0 problems=1` | 同上 → `INVENTORY_MISSING`（INFO 级 + hint） | ✅ T11 分级正确 |
| composite 走兜底 | 无包 → `fallback=true`，内置 passthrough | ✅ 设计预期（required 管线兜底） |
| 未进世界 | 本机无存档 | 相机为 `placeholder fallback (level=false)` |

---

## 五、未覆盖 / 存疑（如实登记）

0. **两次 `swapchain out of date`（WARN 级，均由窗口尺寸变化引起，非模组缺陷）**：

   | 时刻 | 报错尺寸 | 伴随 |
   |---|---|---|
   | 20:56:26 | 854×480（渲染目标尺寸） | 把遮挡窗口最小化（`WIN+DOWN`）之后 |
   | 21:00:54 | 2560×1494（全屏尺寸） | 客户端窗口被放大为全屏 |

   异常原文 `com.mojang.renderpearl.api.device.SurfaceException: Failed to present image,
   swapchain out of date`，抛出点 `Minecraft.renderFrame`（`VulkanGpuSurface.present`）。
   **归因**：两次都精确伴随**窗口尺寸变化** —— Windows 上窗口 resize/最小化/全屏切换导致
   Vulkan swapchain 失效是**标准行为**，客户端随即重建并继续，**不是模组缺陷，也不是渲染链故障**。
   **佐证**：两次之后客户端都继续正常运行，最终于 21:00:59 走正常关闭流程（`Stopping!`），
   退出码 `0`、`BUILD SUCCESSFUL`，**全程无崩溃、无 `vkdisp ERROR`**。
   ⚠️ 后续在自动化取证中操作窗口时，需预期这条 WARN 并与之区分。

1. **未验证 P4.1（BSL）**：本机无该包，P4.1.2 的驱动层修复状态无法在本机复现。
2. **未验证「选项开关改变画面」（P2.4 判据）**：需自造 fixture 包（`shaders/shaders.properties` + `composite.fsh`）并切换 `vkdisp.packProfile`，本轮未做。
3. **F2 原生截图未生效**：`run/screenshots/` 未生成 —— 客户端窗口与 WorkBuddy 存在前台焦点竞争，
   Robot 发送的 F2 未投递到游戏窗口。改用桌面捕获绕开（即本报告证据来源）。
4. **稳定性仅覆盖 9 分钟单会话**：本轮完成了 20:52→21:00:59（9m11s）的完整会话含正常退出，
   但未做长时（>30 min）连续渲染与内存增长的观测。
5. **代理端口为环境注入的临时值**：`51274` 系当前会话 `HTTP_PROXY` 环境变量提供，下次会话可能变化 ——
   不应写入文档作为固定值。
6. **`runClient` 的 configuration cache 报错**：`PrepareRun` 任务序列化失败（NeoForge moddev 插件已知问题），
   用 `--no-configuration-cache` 绕过；**不影响构建与运行**。

---

## 六、证据清单

目录：`review/2026-09-30-本机验证证据/`

| 文件 | 说明 |
|---|---|
| `mc-window-render.png` | 客户端窗口渲染输出（红/绿几何体 + 阴影分界），82,540 B |
| `runclient-full.log` | 完整 runClient 日志（含全部 vkdisp 埋点），65,564 B |
| `sha256-manifest.md` | 上列文件的 sha256 与单测统计 |
| `WinShot.java` / `ColorStats.java` / `SendKeyCombo.java` | 本机取证工具源码（截图 / 主色统计 / 按键投递） |

**复现命令**（代理端口按当前会话实际值替换）：

```bash
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<当前代理端口> \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<当前代理端口>"
D:/Tools/gradle/gradle-9.4.1/bin/gradle.bat build            --console=plain
D:/Tools/gradle/gradle-9.4.1/bin/gradle.bat runClient        --console=plain --no-configuration-cache
```

---

## 七、对项目的意义与建议

1. **F-01（证据不可复核）应降级**：本机已能产出**可落盘、带 sha256、可被第三方复算**的证据。
   建议把本机登记为项目的**第二验证环境**，承接「关键 A/B 帧 + 日志」的复核产出。
2. **建议把 `runClient` 纳入本环境常规回归**：纯逻辑改动跑单测、涉及渲染链的改动加跑 runClient（约 2 分钟启动，资源已缓存）。
3. **建议 env-1 跟进**：其提交中引用的 `evidence/p32_run*.log`、`/tmp/p41a_runclient.log`、截图 sha256
   仍无法在本机复核 —— 本机可代为复算的前提是这些文件能被提供。
4. **文档待同步**：`docs/18-PARALLEL.md` §7/§8 与记忆中的「无 GPU/图形环境」口径需要更新。

> 本次未修改 `CHANGE_LOG.md`（共享文件，避免与 env-1 并行开发冲突），也未改动任何源码。
