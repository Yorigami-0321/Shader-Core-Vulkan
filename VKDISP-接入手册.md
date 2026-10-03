# 把 AI 接进 vkdisp 的 runClient —— 操作手册

> **给执行者（人或 WSL 里的 agent）**：本文是照着做就能跑通的步骤。
> 前提：源码已克隆到 `D:/Code/Minecraft/mcpfabric-src`（Linux 侧路径自行对应）。
>
> ⚠️ **本手册不改vkdisp 任何源码**，只往`run/mods/` 放一个调试用模组。
> vkdisp 的构建脚本、mixin、打包产物一律不动。

---

## 0. 这个方案是什么

给 vkdisp 的开发客户端装一个 **AI 控制通道**，让 AI 能自己操控游戏、造场景、截图取证。

```
AI 客户端
   │  MCP（stdio）
   ▼
mcp-server（Node/TypeScript）
   │  HTTP + bearer token，127.0.0.1:25599
   ▼
mcpfabric（NeoForge mod，装在vkdisp 的 runClient 里）
   │  原版 API / KeyMapping / 截图系统
   ▼
同一个 Minecraft 26.3 客户端（vkdisp 已接管渲染管线）
```

**关键点：mcpfabric 和 vkdisp 装在同一个客户端实例里。**
vkdisp 是纯客户端渲染模组，只有这样截图才拿得到 vkdisp 改过的画面。

---

## 1. 为什么是mcpfabric（选型依据）

| 候选 | 为什么不选 |
|---|---|
| Mineflayer 系（yuno、egkristi 等 MCP） | bot 走网络协议层，**不是本地客户端**，抓不到 vkdisp 的渲染结果；且 mineflayer 官方 `testedVersions` 最高只到 1.21.11 + 26.1 snapshot，**26.3 未支持** |
| cypymd/mcp-server-mod 系 | 只支持 Fabric / 1.21.x，无 26.3，无 NeoForge |
| **mcpfabric（选中）** | MIT；**真 NeoForge 实现**（独立 `build.neoforge.gradle` + ModDevGradle）；`26.3-neoforge` 是正式构建节点；模组内嵌 HTTP 桥，截图直接抓 `mainRenderTarget`；`control.look` 直写 `setYRot/setXRot`，视角可精确复现 |

合规：MIT，与 vkdisp 的 MIT 一致。已核实仓库 `LICENSE` 文件（Copyright (c) 2026 dabinayo）。
`versions/26.3-neoforge/gradle.properties` 已用 `neoforge_version=26.3.0.10-beta`，与 vkdisp 同为 26.3 线。

---

## 2. 前置条件

```bash
java -version          # 必须是 25（mcpfabric 的 26.3 节点 java_version=25）
node --version         # 必须 >= 22.16（mcp-server 的 engines 要求，比 README 写的 20 高）
```

⚠️ 如果 JDK 25 未就绪，先用vkdisp 的 `fix-java-proxy-ca` 修好信任库（本机走 TLS 中间人代理）。

---

## 3. 构建 NeoForge 版模组

源码里已把活动节点切到 `26.3-neoforge`（改了 `stonecutter.gradle` 第 17 行）。

```bash
cd /path/to/mcpfabric-src

# 只构建这一个节点，不要用 chiseledBuild（那会构建全部 28 个节点）
./gradlew :26.3-neoforge:build

# 产物路径
ls -la 26.3-neoforge/build/libs/
```

期望产物：`mcpfabric-neoforge-0.5.0+26.3.jar`

**如果构建失败**，先看是不是这几种：
- `Unsupported class file major version` → JDK不是 25
- TLS / PKIX 错误 → 代理 CA 没导入 JDK 信任库
- 连不上 `maven.neoforged.net` → 代理问题，参照 vkdisp 文档里的 `fix-java-proxy-ca`

---

## 4. 安装到 vkdisp 开发客户端

```bash
cd /path/to/Shader-Core-Vulkan

cp /path/to/mcpfabric-src/26.3-neoforge/build/libs/mcpfabric-neoforge-0.5.0+26.3.jar \
   run/mods/
```

放在 `run/mods/` 而不是打包进 jar —— runClient 会自动带上它，不污染发布产物。

**如果 Modrinth CDN 能通，也可以直接下载官方 jar，省掉构建：**
```
https://cdn.modrinth.com/data/eA63YgUh/versions/2tdDBYqB/mcpfabric-neoforge-0.5.0%2B26.3.jar
```

---

## 5. 构建 MCP Server

```bash
cd /path/to/mcpfabric-src/mcp-server
npm install
npm run build          # 产物：dist/index.js
```

---

## 6. 首次启动：拿token

```bash
cd /path/to/Shader-Core-Vulkan
./gradlew runClient
```

进游戏后会自动生成 `run/config/mcpfabric.config.json`，里面有 `token`。

⚠️ **token 不会打进日志**（上游刻意设计），必须去文件里读。
桥默认监听 `http://127.0.0.1:25599`。

---

## 7. 接入 AI 客户端

`~/.workbuddy/mcp.json`（**不是** `~/.workbuddy/.mcp.json`）：

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["/path/to/mcpfabric-src/mcp-server/dist/index.js"],
      "env": {
        "MCPFABRIC_URL": "http://127.0.0.1:25599",
        "MCPFABRIC_TOKEN": "粘贴第6步拿到的token",
        "MCPFABRIC_TRANSPORT": "stdio"
      }
    }
  }
}
```

⚠️ 写完**不会自动生效**。要到连接器管理页右上角的自定义连接器入口，点「信任」启用。

⚠️ 本机有多个 MCP server，**新加这一条不要覆盖其他条目**。

---

## 8. 验证这套东西真的通了

按顺序做，每步都要有真实回执：

```bash
# 1) 桥活着吗（token 换成自己的）
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:25599/rpc \
  -d '{"jsonrpc":"2.0","id":1,"method":"info.get_status","params":{}}' -H "Content-Type: application/json"

# 2) vkdisp 的诊断日志有输出吗（证明 vkdisp 本身正常）
grep -n "vkdisp:" run/logs/latest.log | tail -20
```

再让 AI 依次做：**`get_status` → `look`(指定 yaw/pitch) → `screenshot` → `describeScene`**。

**验收判据**：截图内容必须是 **vkdisp 渲染后的画面**，不是原版。
判别方法：vkdisp 启用时画面有自定义 pass 效果；或对照 `grep vkdisp: run/logs/latest.log` 确认管线已接管。
如果截图看起来是纯原版 → 说明抓的不是 vkdisp 那个实例的渲染目标，要回来查。

---

## 9. 常见坑

| 现象 | 原因 | 处置 |
|---|---|---|
| 模组没加载 | NeoForge 版本不匹配 / jar 放错目录 | 看 `latest.log` 有无 mcpfabric 条目 |
| 连不上桥 | 游戏没开、token 错、端口被占 | `curl` 探活；看 `mcpfabric.config.json` |
| 视角设了但没变 | AI 每次截图前又调了 `look` | 一次调用内固定视角，别反复重设 |
| 截图是原版画面 | 抓错渲染目标 | 确认 vkdisp 已接管 pass（看日志计数） |
| 造场景失败 | 玩家不在创造模式 | `command.run_command` 设 `gamemode creative` |
| WSL 里跑不通 | runClient 需要图形环境 | 必须在有显示的环境跑，不能纯headless |

---

## 10. 下一步（跑通之后再做）

原版跑通后，按优先级：

1. **切包回归自动化** —— vkdisp 文档 §8 要求「pack A → B → none → A 四张截图，同一 runClient 会话内完成」。
   mcpfabric 有 `command.run_command`，可配合 `vision.screenshot` 串成一条链。
2. **帧时间采样** —— vkdisp 性能预算是「不开包帧时间 ≤ 原版 +2%」，需要在同视角下采样。
   mcpfabric 目前没有帧时间工具，可能要加（见下）。
3. **分带亮度统计** —— 项目里已有 `tools/vkdisp-shot/ColorStats.java`，逻辑可复用。
4. **专用工具集** —— 若前面几项都需要改mod，再考虑 fork 后加 vkdisp 专用 RPC
   （注意：fork 后每轮要重建两个 mod，成本高于「AI 编排现有工具」）。

---

## 附：本次核实过的事实

### 精确调用签名（从源码读出，非猜测）

**`control.look`** —— 所有参数可选；不给则读当前值
```json
{"yaw": 45.0, "pitch": -20.0}          // 绝对值
{"deltaYaw": 90.0, "deltaPitch": 0.0}  // 相对增量
```
pitch 会被 `Mth.clamp` 钳到 [-90, 90]。返回 `{yaw, pitch, x, y, z, ...}`。

**`control.lookAt`** —— 需要 `x`/`y`/`z`（绝对坐标），自动算出 yaw/pitch
```json
{"x": 100.5, "y": 64.0, "z": -200.5}
```

**`world.fill`** —— 需要 `from` / `to`（各为 `{x,y,z}`）、`blockId`、`dimension`（可选）
```json
{"from":{"x":-10,"y":60,"z":-10},"to":{"x":10,"y":60,"z":10},"blockId":"minecraft:stone"}
```

**`command.run`** —— 需要 `command`（前导 `/` 会被自动剥掉）；受 `enableCommands` 开关与 OP 等级约束
```json
{"command": "gamemode creative"}
```

### 全部 67 个 RPC 方法（`router.register` 实测计数，README 称「50+」为保守说法）

| 分组 | 方法 |
|---|---|
| info | `info.status`、`info.capabilities`、`session.info` |
| control | `control.look`、`control.lookAt`、`control.setInput`、`control.jumpOnce`、`control.stop`、`control.startUsing`、`control.stopUsing` |
| interact | `interact.breakBlock`、`interact.stopBreaking`、`interact.placeBlock`、`interact.useItem`、`interact.attackEntity`、`interact.useEntity`、`interact.dropItem` |
| vision | `vision.screenshot`、`vision.describeScene` |
| perception | `perception.blocks`、`perception.entities`、`perception.scan` |
| world | `world.getBlock`、`world.getBlocks`、`world.findBlocks`、`world.setBlock`、`world.fill`、`world.setTime`、`world.setWeather`、`world.getTimeAndWeather`、`world.getDimensions`、`world.raycast` |
| nav | `nav.pathTo`、`nav.status`、`nav.stop` |
| inventory | `inventory.selectHotbar`、`inventory.dropSlot`、`inventory.swapSlots` |
| container | `container.open`、`container.state`、`container.click`、`container.transfer`、`container.close` |
| craft | `craft.place`、`recipes.query` |
| entities | `entities.query`、`entities.get`、`entities.summon`、`entities.remove` |
| players | `players.list`、`players.get`、`players.teleport`、`players.setGameMode`、`players.give`、`players.applyEffect`、`players.message`、`players.kick` |
| command | `command.run` |
| chat | `chat.send`、`chat.getRecent` |
| events | `events.getRecent` |
| player | `player.getState`、`player.getInventory`、`player.getEquipment`、`player.getStatusEffects` |

⚠️ **`vision.screenshot` 受 `enableVision` 开关约束**；`command.run` 受 `enableCommands` 约束。
默认都是开的（按上游说明），但若 AI 报 `unavailable`，先查 `run/config/mcpfabric.config.json`。

### 其他已核实事实

- `mcpfabric-neoforge-0.5.0+26.3.jar` 在 Modrinth **真实存在**（API 版本号 `2tdDBYqB`，180 KB）
- 上游 `LICENSE` = MIT；`mcp-server/package.json` license = MIT
- 截图实现：`Screenshot.takeScreenshot(mainRenderTarget(mc), ...)` —— 直接抓渲染目标
- 视角实现：`p.setYRot(yaw)` / `p.setXRot(pitch)` —— 确定性赋值，非模拟输入
- `versions/26.3-neoforge/gradle.properties`：`neoforge_version=26.3.0.10-beta`、`java_version=25`
- `mcp-server` 要求 Node **>= 22.16**（package.json `engines`，高于 README 写的 20）
- `gradlew` 是 LF 换行，WSL 下可直接执行，无需 `dos2unix`
- 活动节点已由 `1.21.8` 改为 `26.3-neoforge`
- ⚠️ 本机（Windows 沙箱）**CDN 被代理拦截**：`cdn.modrinth.com` 返回 307 后 CONNECT 502，
  故官方 jar 走本地构建。