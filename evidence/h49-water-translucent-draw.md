# h49 · GAP-027 水的 `renderGroup(TRANSLUCENT)` 第一次真被发出 —— 以及它当场掉出来的两个真问题

> 被测改动：`MrtTerrainPass.drawTerrain()` 在地形那一组之后**再发一次** `renderGroup(TRANSLUCENT, …)`
> （判据 = 冻结计划里有没有 `gbuffers_water`，与管线注册侧同源）。
> 开关：`mrt.packWater`（默认 **false** ⇒ OFF 档整条分支不进）。
> 取证入口：`bash tools/vulkan-local/run-client.sh iso -PquickPlay`（车具 `h48_flicker_capture.sh` + 本轮新增的扫描车具）。

---

## 〇、环境（`07-CONSTRAINTS` X53：窗口尺寸 / 驱动必须登记）

| 项 | 实测值（逐字取自本轮日志） |
|---|---|
| 后端 | `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.4-arch1.1 (LLVM …)` |
| 设备自报 | `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.1, 256 bits)` |
| 帧图尺寸 | `gbuffer terrain targets ready: 854x480`（主目标同尺寸） |
| 包 | `BSL_v10.1.8.zip`，档位自报 `STORE_RESIDUE_NONE`（**包默认档**，残留 0 项） |
| validation layer | **本机没有** `VK_LAYER_KHRONOS_validation` ⇒ 「日志里没有 validation error」**不是**证据（X35） |
| 进程 | 起跑前 `game_procs.sh count` = 0（X54）；每臂结束 `game_procs.sh kill` |

⚠️ 本机是 **CPU 软件光栅化（lavapipe）** ⇒ 本轮**不产出任何性能结论**（取证铁律 1）。

---

## 一、单变量表（X52：两臂之间的**全部**差异，逐行列出）

| 配置键 | OFF 臂 | ON 臂 | 说明 |
|---|---|---|---|
| `mrt.packWater` | `false` | `true` | **本轮唯一的被测自变量** |
| `shaderPack` | `BSL_v10.1.8` | 同 | |
| `mrt.terrain` | `true` | 同 | MRT gbuffer pass 总开关 |
| `mrt.packTerrainShader` | `true` | 同 | |
| `mrt.depthGlProxy` | `false` | 同 | GAP-022 半翻档**关掉**：它是「取证档」，开着会把两臂都带进另一条缺陷里 |
| `mrt.enabled` / `mrt.viewSlot` | 见各臂小节 | 同 | `mrt.enabled=true` 会连带把清屏切成**诊断色**（`diagnosticClearMode()` 读 `MrtProbe.enabled()` == `mrt.enabled`）—— 这条本轮踩过，见 §四 |
| 二进制 | 同一份源码、同一次构建 | 同 | |

---

## 二、h49 首轮：ON 臂当场抓出一个「实现了但没接上」的第四例

ON 臂（首版接线，尚未修块环）日志里每帧一条、一次运行 **1032 条**：

```
vkdisp: [GAP-003] pack gbuffer builtins ring is null: program=gbuffers_water -> 不绑定 VkDispBuiltins（draw 将因 Missing uniform 抛）
```

根因（源码级，一句话）：`TerrainPipelineApi.updateTerrainBuiltins()` **只写地形那一条程序**，
而「建环 + 写字节」全在 `updateGbufferBuiltins(program)` 里面 —— 水的程序名从来没有调用点。
⇒ 水的 `VkDispBuiltins` 块**既没被创建、也没被写过**（47 个成员一个都没供进去）。

这与本项目已经抓过的三例同族：`noisetex` 缺分类表分支、`PackTextures.ensureReady()` 准备顺序错、
水的 `renderGroup` 从来没发出。**四例的共同形状：机制都在，缺的是那一次调用。**

修法（`TerrainPipelineApi.updateWiredGbufferBuiltins()`）：逐条写「本帧会画的每一条 gbuffer 程序」，
判定谓词与 `MrtTerrainPass` 决定「要不要发那次 `renderGroup(TRANSLUCENT)`」用的是**同一个**
`waterWiredInFrozenPlan()` ⇒ 不会出现「画了却没写块」或「写了块却没画」。

---

## 三、h49b：修完之后，两臂的自报行（判据达成的一侧）

| 判据 | OFF 臂 | ON 臂 |
|---|---|---|
| `[GAP-027] renderGroup(TRANSLUCENT) issued …` | **0 条** | **1 条**：`attachments=2 waterDeclaredSlots=[0, 1] waterSamplers=8` |
| `builtins ring created` | 地形 1 条（608 B） | 地形 608 B + **水 768 B** |
| `builtins ring is null: program=gbuffers_water` | 0 | **0**（修前 1032） |
| `ERROR` 总条数 | 4 | 4（**同一批既有的**：OpenAL/flite 3 条 + `gbuffers_skybasic` 编译失败 1 条，两臂逐字相同） |
| `wired (mrt variant): layer=TRANSLUCENT …` | 无 | `-> vkdisp:pipeline/terrain_translucent_multidraw_water_mrt` |
| `gbuffer terrain targets ready … slots=` | `slots=1` | `slots=2` |
| 水的契约 | 不生成 | `program=world0/gbuffers_water outputs=2 declaredSlots=[0, 1] samplers=8 varyings=14 bytes=60656`（生成 683–722 ms） |

⇒ **「水的 draw 确实发出了、绑定与块环确实就位」这一侧的判据达成**，
且 OFF 档的自报集合与改动前一致（那条分支整个不进）。

---

## 四、h49c：诊断清屏把槽 1 的读数变成了清屏色本身（本轮自己造的坑）

h49c 用 `mrt.enabled=true viewSlot=0` + `mrt.pixelProbeAfterTerrain=true`。ON 臂的槽 1 读数：

```
c1@afterTerrain#541 … area=FULL … meanRGB=(0.0000,0.0000,255.0000) mean_luma=18.4110 nonBlack=100.000%
```

三个取点区（`SKY_BAND` / `TERRAIN_BAND` / `FULL`）**逐位相同** = `TerrainSlotClear` 的**诊断蓝**。
⇒ 这不是水的输出，是清屏色；`mrt.enabled=true` 顺带把清屏切成了诊断色（`diagnosticClearMode()` 的判据是
「调试视图是否激活」，而 `MrtProbe.enabled()` 就是 `mrt.enabled`）。
⇒ 教训形状：**开关会连带改变被测量本身**（与 GAP-026 那条 store 残留是同一课）。

---

## 五、h49d：中性清屏下，槽 1 是**全零** —— 但这一格当时还判不了

`mrt.enabled=false`（生产视图 ⇒ 中性零清屏）+ `pixelProbeAfterTerrain=true`：

| 标签 | OFF | ON |
|---|---|---|
| `c0@afterTerrain` area=FULL | `meanRGB=(69.67,83.00,118.87) nonBlack=99.267%` | `meanRGB=(69.71,83.05,118.88) nonBlack=99.273%` |
| `c1@afterTerrain` area=FULL | **无此标签**（契约只声明槽 0 ⇒ 探针不取它） | `meanRGB=(0,0,0) nonBlack=0.000% allZero=true` |

槽 1 全零有两种**当时不可区分**的解释：① 水的片元一条都没跑；② 这一帧的画面里根本没有水。
**看 F2 截图判掉了 ② 的一半**：`h49d/ON/shots/2026-10-09_08.55.54.png` 里镜头埋在树冠里、
画面**没有任何水面** ⇒ 「槽 1 为 0」在这一臂**不能**当「水坏了」的证据（没有观测面就不许下结论）。

同时核实了包侧语义（`build/bench-golden/BSL_v10.1.8/world0_gbuffers_water.fsh.trans.glsl`）：

```
2025:    /* DRAWBUFFERS:01 */
2026:    vkdispFragOut0 = albedo;
2027:    vkdispFragOut1 = vec4(vlAlbedo, 1.0);
```

⇒ 水对槽 1 写的是 **alpha=1.0** 的替换值（`BlendFunction.TRANSLUCENT` 下 src alpha=1 ⇒ dst 被覆盖）。
所以「画面里有水却读到全零」才是真缺陷；「画面里没水读到全零」是**如实**。

---

## 六、h49e：给「水有没有落地」造观测面（原地转视角扫描）—— **第一次没造出来**

工具：`/tmp/opencode/h49e_sweep.sh`（本轮临时车具，逻辑：进世界后 N 步
`x11_input.py look --dy <俯仰> --dx 25 --steps 10`（每步 ≈250 像素偏航），每步一张 F2
+ 读最近一条 `c1@afterTerrain`）。配置 = h49d 的 ON 臂（中性清屏 + `pixelProbeAfterTerrain=true`），
**只跑 ON**（OFF 没有水这条路径，跑它没有对照价值）。

h49e 第一版：**8 步 × 只转偏航（`--dy 0`）**，读数逐位为 0：

| step | 探针（`c1@afterTerrain` area=FULL） |
|---|---|
| 1…8 | `meanRGB=(0.0000,0.0000,0.0000) nonBlack=0` 全部一样 |

⇒ 看着像「水一条片元都没出」。**但看截图就知道这一格还不算判据**：
`h49e-sweep/shots/2026-10-09_09.02.57.png` 里镜头是**朝上**的（画面 = 树冠 + 天空），
`09.01.26` 那张更是整帧黑（只有快捷栏与手持物）——
**八步里没有任何一步画面里出现水面** ⇒ 「`c1` 为 0」在这一臂仍然是**如实的 0**，不是缺陷证据。
（车具自己的坑：`look` 的 `--dx` 只转偏航，俯仰沿用上一次的值 ⇒ 只转圈不低头 = 永远看不到脚下的水。）

h49f 第二版（同工具，改两处）：先 `look --dy 520 --steps 20` 把镜头**压到地面视角**，
再转 12 步偏航 ⇒ 观测面里必须真的出现水，`c1` 的读数才有资格当判据。

结果：**12 步的 `c1@afterTerrain` 仍然逐位为 0**（`meanRGB=(0,0,0) nonBlack=0`，step 1…12 全同）。
读图（`h49f-sweep/shots/2026-10-09_09.08.09.png`）判掉这一格：
**画面里没有水面**（前景是一张贴脸的大块面、远处是针叶林与雪，且**已经入夜**、天上有星星
⇒ 昼夜注入依旧没落地，见 `CHANGE_LOG` h48 七十二 ⛔⑤），
所以「`c1` 为 0」在这一臂**还是如实的 0**，不是「水坏了」的证据。

| 图 | 说明 |
|---|---|
| `evidence/h49-images/h49d-ON-no-water-in-frame.png` | h49d ON 臂的 F2：镜头埋在树冠里、**画面里没有水** ⇒ 那一臂的 `c1=0` 不能当缺陷证据 |
| `evidence/h49-images/h49f-sweep-ground-view-no-water.png` | h49f 扫描第 5 步：镜头已压到地面视角，画面是针叶林 + 雪 + **入夜的星空**，仍然**没有水面** |

⇒ **本轮到此为止的定论**：
- 已证：水的 draw 发出、管线换到 `…_water_mrt`、8 条 sampler 与 768 B 的块环全部就位（§三）。
- **未证**：水片元有没有真的写进 colortex0/1 —— 因为**这台机器现在拿不到「画面里有水」的观测面**：
  车具只能转镜头（`look`），不能传送/不能造水块（聊天注入至今不通），而出生点周围
  24 步视角扫描（8 + 12）里没有一处出现水面。
- 下一步该做的是**观测面**而不是继续猜：把聊天注入打通（`x11_input.py chat` 那条
  「命令从不执行」的未决项）就能 `/tp` 到水面或 `/setblock` 造水，
  顺带把「钉正午 + 晴天」这条一直只有愿望没有落地的前置真正立起来。



---

## 七、h49g：**撤回一条把本轮拦住的旧结论 —— 聊天注入其实是通的**

> 这一节是本轮最有用的一条：它不是修法，而是**把我自己拦住一整轮的前提判掉了**。

h48 七十二 ⛔⑤ 与 `evidence/h48-flicker-and-readback.md` §二十七 记的是
「**每一条以 `/` 开头的注入命令从来没生效过**」。本轮水判据「没有观测面」的整段理由
（§六）就建在那条上面 —— 所以值得单独查一次，而不是继续绕。

查法（`/tmp/opencode/h49g_chat_diag.sh`）：**逐步**注入并在每一步后 F2，
把「聊天框开没开 / 文本进没进去 / 最后那个 Return 落没落地」三件事分开看
（此前所有臂都只看最终 luma，从没定位过断在哪一步）：

| 步 | 注入 | 之后读到什么 |
|---|---|---|
| A | 只按 `t` | 截图正常 ⇒ 键进得去（F2 同一条通道一直能用） |
| B | `chat "/time set 6000"`（含 6 个需 Shift 的字符） | **HUD 打出 `Set minecraft:overworld to 6000 tick(s)`**（`h49g-chat/shots/B-after-chat.png`）⇒ **命令真的执行了** |
| C | 再补一次 `Return` | 无副作用（聊天框已关） |

另有独立的**进程内**佐证（不靠读图）：注入前最近一条 `[GAP-003/sky] 观测面自报` 是
`clockTime=8629`，注入后变成 `clockTime=6387` —— **时刻往回走了**，而 `/time set 6000` 之后
自然走 ~387 tick（≈19 秒 × 20 tick/s，正好是两次取样的间隔）就是这个样子。

### 🔴 而且旧判据的那半边也错了：`[CHAT]` **本来就进日志**

h49h 顺着做了一次完整回执核对，日志里逐字出现：

```
[CHAT] Incorrect argument for command
[CHAT] gamerule doDaylightCycle false<--[HERE]
[CHAT] Set minecraft:overworld to 6000 tick(s)
[CHAT] Set the weather to clear
[CHAT] Successfully filled 16 block(s)
```

⇒ 「整份日志里 `[CHAT]` 行数为 0」是 **bug 版**（`/` 被注入成 `7`）观察到的事实，
修完之后它一直是有的。⇒ **以后每条臂都该拿 `[CHAT]` 回执当命令落地判据**，
不再需要「读截图猜注入有没有生效」，也不需要拿 luma 当时刻证据。

### 🔴 顺带查出一条 26.3 的版本迁移事实（它同时解释了「那一臂 0 张截图」）

- 数据迁移表逐字（`net/minecraft/util/datafix/fixes/GameRuleRegistryFix.java:58`）：
  `renameAndFixField("doDaylightCycle", "minecraft:advance_time", convertBoolean)`
  ⇒ **26.3 里这条 gamerule 叫 `advance_time`**，`/gamerule doDaylightCycle false` 会报
  `Incorrect argument for command`。
- 🔖 更值钱的机制：**命令失败会把聊天界面留在打开状态** ⇒ 之后每一次 `press F2`
  都打进聊天框而不是触发截图（h49h 实测：那一臂 `Saved screenshot` 计数 **0**，
  而同一套注入在 h49g 拿到 3 张）。
  ⇒ 「F2 没落地」不一定是指针/焦点问题，**先看最近一条 `[CHAT]` 是不是报错**。


### 为什么旧结论会错（这条比结论本身值钱）

旧判据是「日志里 `[CHAT]` 行数为 0」+「`clockTime` 跨过了 24000 进了新的一天」。
两条都**不成立**：
1. 玩家自己打的命令的执行结果走的是 **HUD 聊天框**，`/time set` 这类**不落 latest.log**
   ⇒ 「日志里没有」不等于「游戏里没发生」（本项目反复踩的同一格：`没有数字 ≠ 数字是 0`）；
2. `clockTime` 跨天只证明 `doDaylightCycle false` **那一臂**没落地，
   而那一臂跑在 **`key_for()` 丢 Shift 层** 的 bug 版上（`/` 被注入成 `7` ⇒ `7time set …`
   被当普通聊天发出去）。a729faa 修掉那个 bug 之后，**没有任何一臂重测过这条**
   ⇒ 一条已经过时的判据继续当结论用，把「给判据造观测面」这件事白白拦了一轮。

### 这条撤回连带影响哪些旧结论（X37：改了共享状态就要重测引用它的结论）

- ✅ **作废**：「本机不能 `/tp`、不能造水块 ⇒ 水的画面判据只能等运气」。
  观测面现在**可以自己造**（h49h 用 `/fill … minecraft:water replace minecraft:air`，
  只替换空气 ⇒ 不破坏取证世界的任何方块）。
- ⚠️ **待重测**：h48 系列里凡是拿「本臂聊天注入没落地，所以世界是夜」当解释的段落
  （`docs/13-GAP-REGISTRY.md` GAP-019 的 h48k 那一行、`evidence/h48` §二十.1 与 §二十六）——
  那些臂确实**有可能**是注入没落地（bug 版），但也可能另有原因；
  本轮**不重判**它们，只把「注入从不生效」这条**默认解释**撤掉。
- 🔖 「观测面钉成正午 + 晴天」从**愿望**变成**可执行**：h49h 起，每条臂都可以先
  `/gamerule doDaylightCycle false` + `/time set 6000` + `/weather clear`，
  并用「HUD 回执 + `clockTime` 不再前进」双判据核验它真的落地了
  （而不是像旧自检那样拿截图亮度当时刻证据）。

---

## 八、h49h / h49i / h49j：**观测面造出来了**，于是水的判据从「无从判」变成「**这是一个真缺陷**」

有了 §七 那条撤回，就可以自己造水面（`/fill … minecraft:water replace minecraft:air`
只替换空气 ⇒ **不破坏取证世界的任何方块**），并把时刻钉死。三臂递进：

| 臂 | 发生了什么 | `c1@afterTerrain` |
|---|---|---|
| h49h | `/gamerule doDaylightCycle false` 报 `Incorrect argument for command` ⇒ **聊天框留在打开状态** ⇒ 之后 4 次 F2 全打进聊天框（`Saved screenshot` = 0）。但 `/fill` 回执 `Successfully filled 16 block(s)` 说明造水这条路可行 | 全零（且当时还不能判） |
| h49i | 改用 26.3 的真名 `/gamerule advance_time false` ⇒ 回执 `Game rule advance_time is now set to false`，**`clockTime` 连续三条都是 6000（时刻真的被钉住了）**；`TRANSLUCENT{draws=4}`。但镜头还朝上（`--dy 100` 只有约 15°，抵不过上一轮留下的仰角） | 全零（画面里仍没水） |
| **h49j** | 先把镜头压平再放水：回执 `Successfully filled 27 block(s)` + `Set the weather to clear`，`TRANSLUCENT{groups=1,draws=20}`，四张 F2 里**画面全是水**（`evidence/h49-images/h49j-water-in-frame-c1-still-zero.png`：大片青色水面 + 雪地 + 正午） | **仍然全零**：`meanRGB=(0,0,0) nonBlack=0.000% allZero=true`（#319…#322 连续四帧） |

### 🔴 定论（这一格本轮终于有权判了）

**水片元没有落进 gbuffer 的 colortex1。** 前提逐条已证：
`renderGroup(TRANSLUCENT) issued` 1 条、管线换到 `…_water_mrt`、水的 8 条 sampler 全绑上、
`VkDispBuiltins` 环 768 B 已写、包侧对槽 1 写的是 `vec4(vlAlbedo, 1.0)`（alpha=1 ⇒
`BlendFunction.TRANSLUCENT` 下应当**整像素覆盖** dst），而画面里确实有水、`TRANSLUCENT` 有 20 条 draw。
⇒ 「全零」不再是「如实的零」（§六 那一格作废），**是一个缺陷**。

### 下一轮要切开的四种可能（本轮**未**判，别当已知）

| # | 候选 | 已有的旁证 / 反证 | 一刀切开的办法 |
|---|---|---|---|
| ① | **深度测试把水全拒**：水的 MRT 管线是 `GREATER_THAN_OR_EQUAL` + **写深度关**（GAP-027 第一步的决定），而水的 `gl_Position` 由我方**顶点适配层**产出 ⇒ 若 clip.z 口径不对就恒不过测试 | 无旁证（本轮没测） | 诊断档把比较改成 `ALWAYS` 跑一臂：槽 1 有内容 ⇒ 就是这一条 |
| ② | 片元 `discard`（转译稿 `world0_gbuffers_water.fsh.trans.glsl:1904` 确实有一条 `discard`） | 无旁证 | 诊断档把该行短路（与地形那族 `*Probe` 同形） |
| ③ | 适配层 14 条 varying 与原版 `BLOCK` 顶点格式不匹配 ⇒ 几何退化/零面积 | 地形 9 条能出画（`c0` 有内容）⇒ 机制本身通；水多出的 5 条未证 | 打一条「水的 draw 覆盖了多少像素」的直判据（或先只喂前 9 条做 A/B） |
| ④ | 混合状态 | 弱：alpha=1 覆盖，理论上不可能给零 | 顺带排除 |

⚠️ 另外本轮**没判**的：水在 colortex0 上有没有内容（`c0@afterTerrain` 两臂都有内容，
但那是地形 + 链的混合，切不出水那一份）。

---

## 九、本轮**不覆盖**什么（显式清单，别把这些读成已验）

1. **水为什么没落地**未判（§八 那四个候选一个都没切开）；**水的画面效果对不对**也未判 ——
   那还要 `depthtex1` 的真快照（GAP-023 ②/③ 未做）与水的顶点属性上限（GAP-007）。
2. **`z1 > z0` 一族判据仍不可信**：`depthtex0/1/2` 本轮依旧同绑那张 1×1 D32@0.0 桩
   （绑定自报行逐字：`depthtex*=1x1 D32@0.0 桩（**不是**本 pass 附件，快照未实现）`）。
3. **跨臂比亮度（F2 luma）本轮不作判据**：h49 / h49b 两轮的「哪一臂在闪」是**换位**的
   （h49：OFF 闪、ON 不闪；h49b：OFF 不闪、ON 闪），同一配置跨轮次不稳定
   ⇒ 那条周期性暗帧属于呈现/回读侧的已知仪器问题（GAP-020 家族），本轮不据此下任何结论。
4. 云 / 实体 / 手 / 天气 / `shadow` 仍未接（GAP-027 的其余程序）。
5. ~~`gbuffers_skybasic` 编译失败~~ ⇒ **本轮内已修**（GAP-028 第一步：宏表进引擎，
   两臂各验 `compile FAILED` 2 → 0、顶点阶段产出 `spvBytes=928/915`）。
   **未修的是另一半**：`renderStage` 这个 uniform 还没有供值 ⇒ 包所有「是某阶段」的分支恒假。
6. **水的槽 0 贡献**未切出来（`c0@afterTerrain` 混着地形与链的内容，切不出水那一份）。
