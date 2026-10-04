# h08 · 包自己的 gbuffers_terrain 片元**真的跑在地形 draw 上**（GAP-003 剩余阻塞解除）

> 任务来源：`docs/AGENT_CONTEXT.md` §10.4 第 6 条的 ①「把编译出的地形 SPIR-V 接到派生 MRT 地形管线的
> **片段着色器**（含配套顶点着色器与属性布局对齐）」与 ②「附件数改为跟随包的输出数」。
> 取证方式：**MCP 驱动真实客户端**（mcpfabric NeoForge 模组 + `tools/mcp-drive.py`）。
>
> 判定：✅ **通过**。附件数跟随包输出数（配置 3 → **实测 1**）生效；BSL 的片元着色器在客户端里
> 编译、链接、绑定并渲染出与原版明显不同的地形；无崩、无 vkdisp ERROR。

---

## 〇、一句话结论

**派生 MRT 地形管线现在用的是 BSL 自己的 `gbuffers_terrain`，不是原版 `core/terrain`。**
决定性证据 = **同一存档 / 同一时刻 / 同一机位**的两张 MCP 截图，地形像素**平均绝对差 48.22**、
**39.03% 的像素发生变化**；而 HUD / 准星 / 手部完全一致。

---

## 一、一行复现

~~~bash
# 配置（run/config/vkdisp-client.toml）
#   [mixin] wireTerrain=true, bindTerrainParams=true, captureTerrainDraws=true
#   [mrt]   terrain=true, terrainAfterLevel=true, terrainToMain=true, packTerrainShader=true
#           attachments=3  ← 故意留 3，用来证明附件数确实被包输出数（1）覆盖

bash tools/vulkan-local/game_procs.sh count        # 残留必须为 0（跑基准与跑客户端不得重叠）
source tools/vulkan-local/env.sh && ./gradlew runClient -PquickPlay

python3 tools/mcp-drive.py set_time '{"time":6000}'        # 固定昼夜（老毛病：昼夜漂移致截图不可比）
python3 tools/mcp-drive.py look     '{"yaw":35,"pitch":-12}'
python3 tools/mcp-drive.py screenshot evidence/h08-images/h08-B-pack-gbuffers-terrain.png

bash tools/vulkan-local/game_procs.sh kill          # 收尾贴残留=0
~~~

A 图（对照组）取自**同一份代码**的上一趟运行，配置只有 `packTerrainShader` 一项不同（false）。

---

## 二、时序发现（本轮最重要的实测结论，改写了设计前提）

### 2.1 🔴 管线注册**早于**包源生成约 4.5 秒

首趟运行（`packTerrainShader=true`，接线尚不存在）的日志原文：

~~~
08:31:49.704  INFO : vkdisp: [GAP-003] mrt pipeline registered: vkdisp:pipeline/mrt colorTargets=3
08:31:49.705  WARN : vkdisp: [GAP-003] pack terrain fragment requested but unavailable
                    (no pack gbuffers_terrain selected) -> MRT terrain pipeline keeps vanilla core/terrain
08:31:49.705  INFO : vkdisp: [GAP-003/A] terrain MRT derived pipelines registered: 6/6 (colorTargets=3)
08:31:54.212  INFO : 地形片元契约解析完成: program=world0/gbuffers_terrain outputs=1 samplers=5 varyings=9
08:31:54.213  INFO : vkdisp: [GAP-003] pack terrain fragment ready: program=world0/gbuffers_terrain
~~~

⇒ `RegisterRenderPipelinesEvent` 在 **49.704** 触发，虚拟包 `openResources` 生成包源在 **54.212**
—— **注册早 4.5 秒**，此刻包片元还不存在。

### 2.2 🔴 而且切包重载时该事件**根本不再触发**

改 `shaderPack` 触发一次完整资源重载后：

~~~
08:36:07.148  INFO : config hot-reload: ... shaderPack='vkdisp-fixture-dir' -> resource reload
08:36:07.213  WARN : vkdisp: [GAP-003] pack terrain fragment NOT wired -> ... keeps vanilla core/terrain
~~~

**没有新的 `terrain MRT derived pipelines registered` 行** ⇒ `RenderPipelines` 类只初始化一次，
管线注册**只在启动期发生一次**。

🔖 **推论（换一条路级别）**：「等包源生成完再注册管线」这条路在原版上**不存在**。
只能反过来：**把契约提前算出来**，让注册期就能拿到。

### 2.3 修法与代价

`VkDispVirtualPack#ensureTerrainProgram()`：在 `packTerrainForMrt()` 里被调用，按 `profile|selection`
记忆，只算一次；`openResources` 直接取用缓存（`PackCompileCache` 同时命中，不重复编）。

~~~
08:39:29.905  INFO : pack terrain fragment ready: program=world0/gbuffers_terrain outputs=1 samplers=5 varyings=9 bytes=35076
08:39:29.905  INFO : vkdisp: [GAP-003] early terrain contract ready in 3620 ms (key=|BSL_v10.1.8)
08:39:29.906  INFO : vkdisp: [GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain colorTargets=1 samplers=5 varyings=9
08:39:29.907  INFO : vkdisp: [GAP-003/A] terrain MRT derived pipelines registered: 6/6 (colorTargets=1)
08:39:30.825  INFO : vkdisp: [GAP-003] terrain source reused from early contract (registration-time generation; no second compile)
~~~

⚠️ **代价已量化**：提前生成耗时 **3620 ms**（启动期一次性），**仅在本开关打开时发生**
（默认关 ⇒ 常规启动零额外冷路径开销，支柱③ B3/B4 不受影响）。
这 3.6 秒里含整包选项链的冷路径编译，与 P4.5 的 `PackPrecompileScheduler` 是同一笔开销，
**不是新增成本**，只是**换了发生时机**（提前到启动期而不是切包时）。

---

## 三、②「附件数跟随包的输出数」—— 实测生效

配置里 `mrt.attachments = 3`（故意不改），实测两侧都变成 **1**：

| 侧 | 日志原文 | 值 |
|---|---|---|
| 契约 | `program=world0/gbuffers_terrain outputs=1` | **1** |
| 管线（注册期冻结） | `terrain MRT derived pipelines registered: 6/6 (colorTargets=1)` | **1** |
| pass（每帧读同一冻结值） | `gbuffer terrain targets ready: 854x480 slots=1 depth=D32_FLOAT` | **1** |

🔖 **X42 的坑被真正堵上**：附件数不是「现算」的，而是**在管线注册那一刻冻结**
（`MrtPlan#freezePackOutputCount`）。若两侧各自现算，就会出现「注册读 3、绘制读 1」
⇒ `setPipeline` 抛 `IllegalStateException` **崩客户端**。本趟实测 **0 崩、0 异常**。

---

## 四、绑定侧：逐条绑，不靠「反正差不多」

~~~
08:39:39.861  INFO : vkdisp: [GAP-003] terrain builtins ring created: bytes=608
08:39:39.862  INFO : vkdisp: [GAP-003] pack terrain uniforms bound: blockMembers=42 samplers=5
                (texture_0=图集真值; noisetex/shadowcolor0=占位; shadowtex0/1=本 pass 深度)
08:39:39.862  INFO : vkdisp: [GAP-003/A] wired (mrt variant): layer=SOLID multiDraw=true -> vkdisp:pipeline/terrain_solid_multidraw_mrt
08:39:39.862  INFO : vkdisp: [GAP-003/A] wired (mrt variant): layer=CUTOUT multiDraw=true -> vkdisp:pipeline/terrain_cutout_multidraw_mrt
08:39:39.862  INFO : vkdisp: [GAP-003/A] terrain drawn into 1 attachment(s) pass (group=OPAQUE, draws=1)
08:40:19.528  INFO : vkdisp: [GAP-003/A] captured draw groups: SOLID{groups=1,draws=611} CUTOUT{groups=1,draws=415} TRANSLUCENT{groups=1,draws=164}
~~~

**42 个块成员 + 5 个 sampler 一条不漏** ⇒ 未触发 `Missing uniform 名`
（STRICT_VALIDATION 下 `validateDraw` 按**布局**逐条校验，少一条就抛）。
这是「接上了」与「看起来接上了」的分界。

| 名 | 视图 | 性质 |
|---|---|---|
| `texture_0` | 方块图集 | ✅ 真值 |
| `noisetex` | 方块图集 | ⚠️ 占位（噪声图不是图集，采到值不承诺） |
| `shadowtex0` / `shadowtex1` | 本 pass 深度视图 | ⚠️ 类型匹配 `sampler2DShadow` 的 D32 深度，但装的是本 pass 地形深度 ⇒ **阴影结果不承诺** |
| `shadowcolor0` | colortex 槽 0 | ⚠️ 占位 |
| `VkDispBuiltins` | 608 字节环，每帧由 `OfUniformManager` 按 std140 偏移填 | ✅ 42 个成员真值 |

---

## 五、顶点侧：配套适配层（③「属性布局对齐」的实现形态）

🔖 **包的顶点着色器不能直接用**：它要 7 个顶点属性（UV0/UV2/Color/Normal/Position/mc_Entity/mc_midTexCoord），
而原版地形顶点缓冲 `DefaultVertexFormat.BLOCK` 只有 **4 个**（Position/Color/UV0/UV2）。
补属性要改区块网格化，属另一层工程 ⇒ 本轮新增**适配层**
`assets/vkdisp/shaders/terrain_pack_adapter.vsh`：按原版顶点格式取数，**逐位置**产出包片元要的 9 条 varying。

| location | 名字 | 取值 | 性质 |
|---:|---|---|---|
| 0 | `mat` | 常量 0.0 | ⚠️ 包 VS 由 `mc_Entity.x/100` 的方块 id 推出；原版缓冲无此属性 |
| 1 | `recolor` | 常量 0.0 | ⚠️ 同上 |
| 2 | `texCoord` | `UV0` | ✅ 真值 |
| 3 | `lmCoord` | `UV2 / 16.0` 后套包 VS 原 clamp | ✅ 真值（单位换算 OF 0..1 ↔ 原版 0..15 格） |
| 4 | `normal` | 常量 `(0,1,0)` | ⚠️ 包 VS 由 `Normal` 属性推出；BLOCK 格式**没有** Normal |
| 5 | `sunVec` | `VkDispTerrainParams.SunDir`（眼空间 `sunPosition`） | ✅ 真值 |
| 6 | `upVec` | `ModelViewMat[1].xyz` | ✅ 真值（与包 VS 同一条算式） |
| 7 | `eastVec` | `ModelViewMat[0].xyz` | ✅ 真值（与包 VS 同一条算式） |
| 8 | `color` | `Color` + 包 VS 的 alpha 兜底 | ✅ 真值 |

🔖 **三条 ⚠ 是已登记的缺口 GAP-007**：它们影响「哪些方块被认成树叶/自发光」「法线朝向」这类
**光照细节**，**不影响**「包的片元真的跑在地形 draw 上」这一结论。

🔖 **防静默失效**：适配层的 out 签名与包片元的 in 签名由 `PackTerrainSourceTest`
逐位置逐名字对账（`adapterOutputsMatchPackFragmentInputs`）。换包后忘改适配层会**测试红灯**，
而不是在客户端里莫名链接失败。

---

## 六、决定性判据：两张 MCP 截图的像素差

**同一存档、同一时刻（`time set 6000`）、同一机位（`yaw=35, pitch=-12`）、同一天气（clear）**，
**唯一差别 = 片元着色器来源**。取图区 y 80..435（避开 HUD 与准星区），854×480：

| 图 | 片元着色器 | 平均绝对差（逐通道） | 最大通道差 | 变化像素占比 |
|---|---|---:|---:|---:|
| `h08-A-vanilla-core-terrain.png` | 原版 `core/terrain` | 基准 | — | — |
| `h08-B-pack-gbuffers-terrain.png` | BSL `gbuffers_terrain` | **48.22** | **198** | **39.03%** |

图面差异与数字一致：A 是原版明亮沙色，B 是 BSL 自己那套偏暗偏暖的 PBR 处理；
**HUD / 准星 / 手部完全一致** ⇒ 差异确实来自片元着色器，不是机位 / 时间 / 天气漂移。

🔖 **噪声基线对照**：`evidence/mcp-fabric-integration.md` §3.3 用「同包间隔一分钟两张平均差 1.91」
作为可复现性基线。本轮 48.22 是它的 **25 倍** ⇒ 差异不是噪声。

---

## 七、稳定（支柱②）

| 判据 | 实测 |
|---|---|
| `vkdisp` ERROR 行数 | **0** |
| 客户端崩溃 | 无（运行约 135 s 后由 `game_procs.sh kill` 主动关闭） |
| 残留游戏进程 | **0**（`残留游戏进程数=0`） |
| `Missing uniform` / `IllegalStateException` | 均未出现 |
| 非 vkdisp 的 ERROR | 2 条，均环境性：narrator（缺 flite）、OpenAL（无音频设备） |

⚠️ **仍不声称「0 validation error」**：本机**没有装 Vulkan validation layer**
（沿用 §9.4.15 既有纪律）。本轮判据是「不崩 + 画面确实变了 + 布局条目逐条绑齐」。

---

## 八、测试

~~~
./gradlew build   →  BUILD SUCCESSFUL
新例 PackTerrainProgramTest  7 例（一行两声明 / 块成员不是 sampler / 输出数 / 注释不算声明 /
                                  无输出显式抛 / 绑定组顺序 / 空白源拒绝）
新例 PackTerrainSourceTest   4 例（真实 BSL 契约冻结 / 适配层签名对账 /
                                  shaderPack=none 不接线 / 指定包不存在不落到别的包）
常驻 TerrainProductionOutputCountTest 3 例（X42 生产口径）
~~~

🔖 **实现期真踩的一个坑（立 X43）**：契约解析器首版按**行首**锚定匹配声明，而 BSL 的转译终稿里
声明是**两两并排写在同一行**的（`layout(location = 0) in float mat; layout(location = 1) in float recolor;`）
⇒ 只认得出每行的第一个，**location 0 被错配成 `recolor`、location 2 被错配成 `lmCoord`**，
静默少认 4 条 varying。若不对账，接线会在客户端里以「链接失败」的形式炸出来，
而根因却在几小时前的解析器里。⇒ 已写成断言：**按声明逐个 findAll，不按行取首个**。

---

## 九、本轮**没有**证明的（不许当已完成引用）

1. ⛔ **地形只是被画进我方 pass**，主目标仍由原版管线照常绘制 ⇒ **本轮不产出任何用户可见的画面改进**。
   真正把地形接进主链（M-04 方案 B）仍未开始。
2. ⛔ `mat` / `recolor` / `normal` 三条 varying **按常量供值**（GAP-007）⇒ 光照细节不正确。
3. ⛔ 阴影 `shadowtex0/1` 绑的是本 pass 深度占位 ⇒ **包的阴影结果不成立**。
4. ⛔ 只覆盖 **OPAQUE 组**（固体 + cutout），半透明地形未覆盖。
5. ⛔ 只覆盖 BSL **默认配置**（`ADVANCED_MATERIALS` 关 ⇒ 1 槽）。若开启（5 槽），
   附件顺序必须服从 `DRAWBUFFERS` 而非下标（h06 的结论），本轮未做。
6. ⛔ 启动期提前生成契约耗时 **3620 ms**；它是否影响 B3 / B4 **本轮未测**
   （开关默认关 ⇒ 常规路径不受影响，但开启时的账要补）。
7. ⛔ 只验了 BSL 一个包。**X39：不同包的 gbuffer 语义不同，不可套用** —— 换包需重跑
   `PackTerrainSourceTest` 的契约冻结用例。

---

## 十、产物与哈希

| 文件 | sha256 |
|---|---|
| `run/logs/latest.log`（本趟） | `d237c7ac599eb453aaa5f9e149b32023c6b98e464e732786a916660b4f9fc5e5` |
| `evidence/h08-images/h08-A-vanilla-core-terrain.png` | `392e10cea3968b44a53529addb5d07cc2eebcb6e45071de619051922326a7a5e` |
| `evidence/h08-images/h08-B-pack-gbuffers-terrain.png` | `19681b43bc0bfeacfbe3c67e98d4e5944f41d88631d27d8475a365e469778f89` |

MCP 回执（同一会话内）：

~~~
get_status -> integratedServer=true, playerCount=1, 75 tools
set_time   {"time":6000}         -> {"command":"time set 6000","success":true,"resultValue":6000}
look       {"yaw":35,"pitch":-12} -> {"yaw": 35, "pitch": -12}
screenshot                     -> image/png 854x480
~~~

GUI 侧残余进程：收尾 `game_procs.sh kill` → `残留游戏进程数=0`。
