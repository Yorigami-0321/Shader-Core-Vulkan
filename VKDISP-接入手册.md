# 把 AI 接进 vkdisp 的 runClient —— 操作手册

> **给执行者（人或 WSL 里的 agent）**：本文是照着做就能跑通的步骤。
> 前提：源码已克隆到 `D:/Code/Minecraft/mcpfabric-src`（Linux 侧路径自行对应）。
>
> ⚠️ **本手册不改vkdisp 任何源码**，只往`run/mods/` 放一个调试用模组。
> vkdisp 的构建脚本、mixin、打包产物一律不动。
>
> 🔖 **2026-10-03 已按本手册完整跑通一遍并取证**（`evidence/mcp-fabric-integration.md`）。
> 下面标 🔴 的段落是那次实测**发现手册写错的地方**，照抄会白跑一轮 ——
> 先看 §0.1，再看 §7.1。

---

## 0.1 🔴 先看这三条（手册与本机不符处，实测订正）

| # | 本手册原文说 | 本机实测 | 该怎么做 |
|---|---|---|---|
| **1** | §附末条：「CDN 被代理拦截，官方 jar 走本地构建」 | ✅ **CDN 是通的**：`curl` 200 / 185206 字节 | **直接下官方 jar**（用 §4 的备用路径，删掉 §3 的编译步骤）。本地编译**会被权限拦截**（见下） |
| **2** | §7：配置写 `~/.workbuddy/mcp.json`，并强调「**不是** `~/.workbuddy/.mcp.json`」 | ❌ **CodeBuddy Code 根本不读 `.workbuddy/`** | 改写 **`~/.codebuddy/.mcp.json`**（见 §7.1） |
| **3** | §7：「写完不会自动生效，要到连接器管理页点信任」 | 部分成立：**配置不热加载进已有会话**，但 stdio server **无需点信任** | 通道可用，但当前会话拿不到工具 ⇒ 用 §8.1 的 `mcp-drive.py` 按 MCP 协议直连补上 |

**为什么不能本地编译 mcpfabric**：在仓库外的第三方源码目录跑 `./gradlew` 会被
CodeBuddy 的 auto mode 分类器拦截（`~/Minecraft/mcpfabric-src` 与
`/mnt/d/Code/Minecraft/mcpfabric-src` **都拦**，后者虽在 `trustedDirectories` 里也一样）。
**别试，走 CDN。**

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

> 🔖 **2026-10-03 实测：跳过本节。** 直接走 §4 的 Modrinth 下载（CDN 通）。
> 本地编译有两个坑：① **会被 CodeBuddy 权限分类器拦截**（第三方仓库的构建脚本）；
> ② `gradlew` **没有可执行位**（`-rw-r--r--`），直接 `./gradlew` 报 `Permission denied`，
> 得先 `chmod +x gradlew`（换行确实是 LF，不用 `dos2unix` —— 这条手册说得对）。
>
> 下面保留仅为「必须离线构建时」的参考。

源码里已把活动节点切到 `26.3-neoforge`（改了 `stonecutter.gradle` 第 17 行）。

```bash
cd /path/to/mcpfabric-src
chmod +x gradlew                # ← 实测必做，否则 Permission denied

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
- `Permission denied` → 忘了 `chmod +x gradlew`
- 被 CodeBuddy 拦下 → **别绕，改走 §4 下载**

---

## 4. 安装到 vkdisp 开发客户端

**推荐路径（2026-10-03 实测走通，CDN 200 / 185206 字节）：**

```bash
cd /path/to/Shader-Core-Vulkan

curl -sSL -o run/mods/mcpfabric-neoforge-0.5.0+26.3.jar \
  "https://cdn.modrinth.com/data/eA63YgUh/versions/2tdDBYqB/mcpfabric-neoforge-0.5.0%2B26.3.jar"
```

若走本地构建（离线场景）：

```bash
cp /path/to/mcpfabric-src/26.3-neoforge/build/libs/mcpfabric-neoforge-0.5.0+26.3.jar \
   run/mods/
```

放在 `run/mods/` 而不是打包进 jar —— runClient 会自动带上它，不污染发布产物。

✅ **实测确认：不需要改 `build.gradle`。** 原版 ModDevGradle 的 dev 运行环境会自动扫描
`run/mods/`（`latest.log` 里可见 `mcpfabric (jar(mods/mcpfabric-neoforge-0.5.0+26.3.jar))`）。
⇒ 后续任何调试模组都可以走这条路。

**验收 jar 没下错**（版本必须对上 vkdisp 的 NeoForge）：

```bash
python3 -c "
import zipfile;print(zipfile.ZipFile('run/mods/mcpfabric-neoforge-0.5.0+26.3.jar')\
 .read('META-INF/neoforge.mods.toml').decode())"
```

应看到 `version = \"0.5.0+26.3\"`、`versionRange = \"[26.3.0.10-beta,)\"`（vkdisp 是 `26.3.0.51-beta`，满足下限）。

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
source tools/vulkan-local/env.sh        # 本机走 lavapipe，必须先source（Vulkan ICD）
./gradlew runClient -PquickPlay         # -PquickPlay 自动进最近存档；取证需要进世界
```

进游戏后会自动生成 `run/config/mcpfabric.config.json`，里面有 `token`。

⚠️ **token 不会打进日志**（上游刻意设计），必须去文件里读。实测确认：日志里只有路径，没有 token 本身。
桥默认监听 `http://127.0.0.1:25599`。

**别把token 写进命令行**（会进 shell 历史与进程表）。实测可行的写法：

```bash
# 探活：token 从配置文件读，不落在命令行
python3 - <<'EOF'
import json,urllib.request
c=json.load(open('run/config/mcpfabric.config.json'))
r=urllib.request.Request(f"http://{c['host']}:{c['port']}/rpc",
    data=json.dumps({"jsonrpc":"2.0","id":1,"method":"info.status","params":{}}).encode(),
    headers={"Authorization":"Bearer "+c['token'],"Content-Type":"application/json"})
print(urllib.request.urlopen(r).read().decode())
EOF
```

🔖 **方法名是 `info.status`，不是 `info.get_status`**（写错会回 `No such method`）。
手册 §8 的示例命令方法名有误，下面已改。

---

## 7. 接入 AI 客户端

### 7.1 🔴 配置文件位置（CodeBuddy Code 实测）

**`~/.codebuddy/.mcp.json`** —— 原 §7 写的 `~/.workbuddy/mcp.json` 在本机**完全无效**
（CodeBuddy Code 不读 `.workbuddy/` 目录）。

实测优先级顺序（从高到低，读第一个存在的）：

| 作用域 | 路径（高→低） |
|---|---|
| USER | `~/.codebuddy/.mcp.json`（推荐） → `~/.codebuddy/mcp.json`（已废弃） → `~/.codebuddy.json`（旧版） |
| PROJECT | `<项目根>/.mcp.json` → `<项目根>/mcp.json` |

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "stdio",
      "command": "node",
      "args": ["/home/yorigami/Minecraft/mcpfabric-src/mcp-server/dist/index.js"],
      "env": {
        "MCPFABRIC_URL": "http://127.0.0.1:25599",
        "MCPFABRIC_TOKEN": "粘贴第6步拿到的token",
        "MCPFABRIC_TRANSPORT": "stdio"
      }
    }
  }
}
```

⚠️ 写完**不会热加载进已有会话**（实测：`WaitForMcpServers` 报
`no MCP server with this name is configured`）。**新开一个会话**即可加载。
stdio 类型的 server **不需要**去连接器管理页点信任。

⚠️ 本机有多个 MCP server，**新加这一条不要覆盖其他条目**。

---

## 8. 验证这套东西真的通了

### 8.1 当前会话拿不到工具时：用驱动脚本

配置写好后，**当前会话**通常还拿不到 `minecraft` 工具。用仓内的
`tools/mcp-drive.py` 按**真实 MCP stdio 协议**直连（真握手、真 `tools/call`），
回执与模型直连一致：

```bash
cd /path/to/Shader-Core-Vulkan
python3 tools/mcp-drive.py get_status
python3 tools/mcp-drive.py look '{"yaw":30,"pitch":-15}'
python3 tools/mcp-drive.py screenshot /tmp/shot.png      # 无参工具，第三参数是输出路径
python3 tools/mcp-drive.py describe_scene '{"maxDistance":48}'
```

它**从`run/config/mcpfabric.config.json` 读 token**，不落在命令行上。

### 8.2 逐步验证（每步都要有真实回执）

```bash
# 1) 桥活着吗（用 §6 的 python 片段，方法名 info.status）

# 2) vkdisp 的诊断日志有输出吗（证明 vkdisp 本身正常）
grep -n "vkdisp:" run/logs/latest.log | tail -20

# 3) 模组加载了吗
grep -n "mcpfabric" run/logs/latest.log | head
# 期望看到：[mcpfabric] HTTP bridge listening on http://127.0.0.1:25599
```

再让 AI 依次做：**`get_status` → `look`(指定 yaw/pitch) → `screenshot` → `describe_scene`**。
（注意模型侧工具名是 snake_case：`describe_scene`，桥侧 RPC 才叫 `vision.describeScene`。）

**验收判据**：截图内容必须是 **vkdisp 渲染后的画面**，不是原版。

### 8.3 🔴 「看起来像 vkdisp」不是判据 —— 改成两级可执行判据

原 §8 写的「画面有自定义 pass 效果 / 对照日志确认管线已接管」**不可执行**。
2026-10-03 取证时改成了下面两级（已实测通过，证据见 `evidence/mcp-fabric-integration.md`）：

**一级（相关性）**：pack A→B→none→A 四张，同一 runClient 会话内，每张前用 `look` 固定视角。

```bash
bash tools/vulkan-local/mcp-pack-ab.sh     # 已在仓内，切包+截图一条链
```

判据：不同包之间像素**必须有实质差异**；而**同一包、同一视角**间隔约 1 分钟的两张，
差异必须**远小于**跨包的差异（实测 1.91 vs 8.0–24.3）。
后者是关键 —— 它排除了「差异只是时间噪声」这个替代解释。

**二级（决定性）**：开 vkdisp 自己的 MRT 诊断视图，让画面被**vkdisp 的 `colortex0` 附件**覆盖：

```bash
# run/config/vkdisp-client.toml 的 [mrt] 段：enabled = true（viewSlot 保持 0）
# 重启 runClient，然后
python3 tools/mcp-drive.py screenshot /tmp/mrt.png
```

判据：截图应是**一片单色/渐变、无地形几何**的图像，同时日志出现
`vkdisp: [GAP-003] mrt primitive verified: ... viewSlot=0`。

🔖 **为什么这是决定性的**：`colortex0` 是 vkdisp 自己的多附件渲染目标，
**原版渲染管线在任何配置下都产生不出这个画面**。
拿到这张图，就排除了「截图抓的是原版主目标 / 窗口合成画面」这一整类可能。

⚠️ 测完**记得把 `[mrt].enabled` 改回 `false`** —— 改配置会触发资源重载，
可能让客户端弹暂停菜单（见 §9）。

如果截图看起来是纯原版 → 说明抓的不是 vkdisp 那个实例的渲染目标，要回来查。

---

## 9. 常见坑

| 现象 | 原因 | 处置 |
|---|---|---|
| 模组没加载 | NeoForge 版本不匹配 / jar 放错目录 | 看 `latest.log` 有无 mcpfabric 条目 |
| 连不上桥 | 游戏没开、token 错、端口被占 | `curl` 探活；看 `mcpfabric.config.json` |
| `No such method: info.get_status` | 方法名错了 | 桥侧是 **`info.status`**（无 `get_` 前缀） |
| 视角设了但没变 | AI 每次截图前又调了 `look` | 一次调用内固定视角，别反复重设 |
| 截图是原版画面 | 抓错渲染目标 | 走 §8.3 的两级判据定位 |
| 改配置后画面变GUI | 改 `run/config/*.toml` 触发资源重载，客户端弹暂停菜单 | **重启 runClient**，让配置在启动时就位（比在运行中改可靠） |
| 🔴 **游戏里按 ESC / F3 无反应** | WSLg 下游戏是 **Wayland 客户端**，`x11_input.py` 走 XTEST，注入**报成功但无效** | 游戏内按键走 MCP 的 `control.*` / `run_command`，**不要走 X11 注入** |
| 🔴 `x11_input.py press` 报错窗口不对 | 它默认 `focus-largest`，本机最大映射窗口是 8192×8192 伪窗口，不是 MC | 显式给 `--window-id`（`x11_input.py list` 可查，MC 窗口是 854×480 那个） |
| 造场景失败 | 玩家不在创造模式 | `command.run_command` 设 `gamemode creative` |
| WSL 里跑不通 | runClient 需要图形环境 | 必须在有显示的环境跑，不能纯headless |
| `./gradlew` Permission denied | `gradlew` 没有可执行位 | `chmod +x gradlew`（换行没问题，不用 `dos2unix`） |
| 编译 mcpfabric 被拦 | 第三方仓库的构建脚本被权限分类器拒绝 | 改走 §4的 Modrinth 下载（CDN 是通的） |

---

## 10. 下一步（跑通之后再做）

> 🔖 **2026-10-03 更新：第 1 项已完成**（见 `evidence/mcp-fabric-integration.md`）；
> 第 2 项有一条**已被既有证据否掉的子目标**，别重复做。

原版跑通后，按优先级：

1. ✅ **切包回归自动化** —— **已做**。仓内 `tools/vulkan-local/mcp-pack-ab.sh`：
   pack A→B→none→A 四张、同一 runClient 会话内、每张前用 MCP `look` 固定视角。
   🔖 **切包不能走 `command.run_command`**（vkdisp 的包切换是**配置热加载**驱动的，
   不是游戏内命令）—— 脚本改 `run/config/vkdisp-client.toml` 的 `shaderPack`，
   由 FML FileWatcher 触发。
2. **帧时间采样** —— vkdisp 性能预算是「不开包帧时间 ≤ 原版 +2%」，需要在同视角下采样。
   mcpfabric 目前没有帧时间工具，可能要加（见下）。
   ⚠️ 但**先看 `evidence/b4-reload-profile.md`**：切包耗时的缺口在**埋点停表早**，
   不是驱动问题 —— MCP 能简化驱动，**测不出那个缺口**。别把 MCP 当成解法。
3. **分带亮度统计** —— 已有 `tools/vulkan-local/p24_luma.py`（**注意路径**，
   §10 原文写的 `tools/vkdisp-shot/ColorStats.java` 已不存在），逻辑可复用，**不需要 MCP**。
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

### 🔴 67 vs 75：两个数都是对的，指的不是一回事

2026-10-03 实测：`info.status` 返回的 `methods` 列表 = **67 个**（= 模组侧 RPC 契约，与上表一致）；
而 MCP `tools/list` 返回 **75 个**工具。差的 8 个是 mcp-server 侧的本地/编排工具
（`agent_brief`、`observe`、`map_view`、`remember`、`recall`、`memory_update`、
`memory_verify`、`goal_*` 等），它们**不打桥**。

⇒ 引用时要说清是哪个数：**「模组侧 RPC 67 个」/「模型侧可见工具 75 个」**。

### 🔴 模型侧工具名是 snake_case，桥侧才是 dotted

上表是**桥侧 RPC 名**。模型看到的工具名不同，例如
`vision.screenshot` → **`screenshot`**、`vision.describeScene` → **`describe_scene`**、
`info.status` → **`get_status`**、`command.run` → **`run_command`**、
`world.getBlock` → **`get_block`**、`world.fill` → **`fill_blocks`**。

混用会拿到 `No such method`。对照表在 `mcp-server/src/tools.ts`（每条都有 `name` + `method`）。

### 其他已核实事实

- `mcpfabric-neoforge-0.5.0+26.3.jar` 在 Modrinth **真实存在**（API 版本号 `2tdDBYqB`，180 KB）
  —— 🔖 **2026-10-03 实测 CDN 可下载**（HTTP 200 / 185206 字节），
  与本节原先「CDN 被拦截」的记载相反，见 §0.1
- jar 内 `META-INF/neoforge.mods.toml` 声明 `versionRange = "[26.3.0.10-beta,)"`，
  vkdisp 是 `26.3.0.51-beta` ⇒ 满足
- 上游 `LICENSE` = MIT；`mcp-server/package.json` license = MIT
- 截图实现：`Screenshot.takeScreenshot(mainRenderTarget(mc), ...)` —— 直接抓渲染目标。
  ✅ 已用 §8.3 的两级判据实证：抓到的确实是 vkdisp 接管后的目标
- 视角实现：`p.setYRot(yaw)` / `p.setXRot(pitch)` —— 确定性赋值，非模拟输入。
  ✅ 实测 `look(45,-20)` 后 `describe_scene` 回执里 `yaw/pitch` 原样返回，截图可复现
- `describe_scene` 的 45 条射线命中坐标与 `player.getState` 自洽 ⇒ 世界查询也是真读
- `versions/26.3-neoforge/gradle.properties`：`neoforge_version=26.3.0.10-beta`、`java_version=25`
- `mcp-server` 要求 Node **>= 22.16**（package.json `engines`，高于 README 写的 20）。
  ✅ 实测 v22.23.2 / npm 12.1.0 满足
- `gradlew` 是 LF 换行，无需 `dos2unix`；🔖 但**可执行位默认没有**，需 `chmod +x`
- 🔖 **`run/mods/` 会被 runClient 自动加载**（实测，无需改 `build.gradle`）
- 🔖 **WSLg 下游戏是 Wayland 客户端，X11 的 XTEST 注入报成功但无效** —— 见 §9
- 活动节点已由 `1.21.8` 改为 `26.3-neoforge`
- ⚠️ **原记载（已作废）**：「本机（Windows 沙箱）CDN 被代理拦截，官方 jar 走本地构建」。
  🔖 **2026-10-03 在 WSL 下实测 CDN 通**（HTTP 200 / 185206 字节），
  且本地构建会被权限分类器拦截 ⇒ **官方 jar 走下载，见 §4**。
  这条差异说明**「Windows 沙箱」与「WSL」的网络环境不同**，两者结论不可互相套用。

---

## 附二：2026-10-03 跑通时的环境与产物

| 项 | 值 |
|---|---|
| 环境 | WSL2（`DISPLAY=:0`、`WAYLAND_DISPLAY=wayland-0`），Vulkan 走 **lavapipe/llvmpipe**（`tools/vulkan-local/env.sh`） |
| JDK | OpenJDK 25.0.4.1（Microsoft-14951822） |
| Node / npm | v22.23.2 / 12.1.0 |
| jar 来源 | Modrinth CDN 官方 jar，185206 字节，sha256 见 `evidence/mcp-fabric-integration.md` |
| 工具面 | 桥侧 67 个 RPC / 模型侧 75 个工具 |
| 证据 | `evidence/mcp-fabric-integration.md`（正文）<br>`tools/vulkan-local/evidence/mcp-2026-10-03/`（截图与日志本体，gitignored） |
| 新增脚本 | `tools/mcp-drive.py`（MCP stdio 驱动器）<br>`tools/vulkan-local/mcp-pack-ab.sh`（pack A→B→none→A 四截图） |

**收尾检查清单**（每次取证后照做）：

- [ ] `bash tools/vulkan-local/game_procs.sh count` → 残留进程数 0
- [ ] `run/config/vkdisp-client.toml` 的实验性开关已复原（本轮：`[mrt].enabled = false`、总开关 `enabled = true`）
- [ ] 二进制本体在 gitignored 路径下，`evidence/` 只放文本摘要（G-01 规范）