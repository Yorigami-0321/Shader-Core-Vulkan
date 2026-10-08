# h48 — 黑白闪屏的定位链（2026-10-06）

判据工具：`tools/vulkan-local/h48_flicker_capture.sh`（游戏自己的 F2 截图 + 纯 stdlib PNG 解码算亮度）。
**为什么不用像素探针当闪屏判据**：探针是异步回读，闪屏本身就是「读到的那一帧是什么」的问题，
用同一个异步通道自证会循环论证；F2 截图走的是游戏内的帧缓冲，是**另一条通道**。

## 一、症状是真的，不是回读假象

`mrt.pixelProbe=false`（探针全关）时截图亮度序列：

```
200.00 / 6.20 / 199.33 / 6.24 / 199.74 / 6.23 / 199.86 / 198.66 / 196.95
```

⇒ 屏幕确实在**白 ↔ 黑**交替；黑帧上 HUD（快捷栏/准心/聊天/手）正常 ⇒ 黑的是**后处理链的输出面**。

## 二、逐臂判据（一臂一个变量）

| 臂 | 变量 | 结果 | 判读 |
|---|---|---|---|
| R4 | 基线（补 `frameTime` 之后） | 白 251 / 黑 0 交替，约 30 秒后稳定全黑 | 症状面 |
| S1 | `AUTO_EXPOSURE=false` | 仍 `254.69 ↔ 0` 交替 | **证伪**「白 = 自动曝光 ÷0.125 的固定 ×8」是闪屏因（它只解释补 `frameTime` **之前**的恒定白） |
| S2 | `AUTO_EXPOSURE_RADIUS=0.002`（曝光计量 LOD→0） | 仍 `251.6 ↔ 0` | **证伪**「高 LOD 读到黑」是闪屏因 |
| T4 | 探针 every=1（逐帧） | 黑帧上 `colortex0` 自身 = 0、`colortex0@m8` = 0；亮帧 `colortex0 mean_luma=34.5`、`@m8=(67,53,32)` | 黑**起源于链的输入**：地形 MRT 的输出在那些帧根本没进池 |
| T2/T3 | 回读改双槽轮转 / 延迟一个节拍收割 | 交替比例不变 | **证伪**「整轮 0 = 回读缓冲被在途复用」——那些 0 是真的黑帧（双槽 + 跳过自报仍作为卫生保留） |

## 三、补 `frameTime` 的意义（为什么之前是恒定白）

BSL `composite3:196`、`composite5:371,378` 用 `exp2(-frameTime × SPEED)` 当**逐帧混合系数**。
我们此前根本不供 `frameTime`（它不在 `UniformCatalog`，是包声明后被收编进块、恒 0 的成员）
⇒ 混合系数恒 1 ⇒ `mix(新, 旧, 1)` 永远返回旧值，而旧值 = 附件初始 0 ⇒ 时序量（自动曝光、
DOF 聚焦、太阳可见度）**永久卡在 0**。恒定白的直接原因是 `color /= 2×0 + 0.125` = ×8。
补上 `frameTime`（`OfUniformManager` 取最近一次有效帧间隔，尖峰帧沿用旧值）之后，
这条反馈才真的开始跑 —— 于是恒定白变成了「黑白交替并收敛」。

## 四、GAP-018（同槽采样 + 附件别名）：修了，确实有效，但不足以解释闪屏

`FrameApi` 的写槽视图与 `chainResolver` 的采样视图此前同为 `poolView(slot)`；BSL 11 步里
**8 步的读写集重叠**（`composite`/`composite1..3` 读写 c0，`composite5/7` 读写 c2，
`composite6` 读写 c1）⇒ 同一子通道里既当颜色附件又当采样器 = Vulkan UB（本机无 validation
layer ⇒ 不报错）。修法 = 每槽**双代轮转**（读打 `cur`、写打另一代、pass 结束翻代）。

效果（同机位同时刻）：亮帧亮度 **199.7 → 45.3**（不再是削顶白），且**画面内容出来了**
（雪面、树、阴影、手都认得出来）。⇒ 别名确实在破坏链，修根有效。
但黑帧仍在（10 张里 2 张 9.2）⇒ 闪屏另有其因。

## 五、`terrainAfterLevel` 是第二个独立变量（诊断档被当成默认档用了很久）

`mrt.terrainAfterLevel=true`（我方地形 MRT pass 在帧图执行完之后重放）时：
`colortex0` 在 **2/3 的帧里是全 0**。改成 `false`（在地形数据当帧执行）后：
`colortex0 mean_luma=91.7`、`nonBlack=99.99%` **每帧都有**，画面从「白/黑闪」变成
「正常/偏暗」。⇒ 重放的那份 `ChunkSectionsToRender` 是帧图的瞬态资源，隔在帧图外重放
会拿到已被回收/清空的内容。取证车道一直开着这一档，是把「输入时有时无」带进所有结论的原因。

## 五½、🔴 第五节与第八节的结论作废：那一臂根本没在跑链

`FullscreenPassHook` 的链分派写成

```java
if (chainActive && MrtTerrainPass.enabled() && MrtTerrainPass.afterLevel()) { ... drawPostChain ... }
else { FrameApi.drawFullscreen(...); }   // 旧三步链
```

⇒ `mrt.terrainAfterLevel=false` 会**连带把整条链关掉**，那一臂跑的是旧三步链。
所以「45 luma 稳定画面」「闪屏消失」「GAP-019 可以降级为观察项」这三条都建立在
**旧三步链的画面**上，不是链的结果。已按本项目纪律**改写而非删除**。

修法：把两个轴拆开 —— 链是否执行只看 `chainActive`；`afterLevel` 只决定地形挂点。
并加 `[route]` 自报（只在决策变了时打一行）：

```
vkdisp: [route] 本帧渲染路径 = chain=true afterLevel=false toMain=false ⇒ 整条后处理链（colortex 按名接线）
```

接线保证写在 `RenderRouteWiringTest`（「从 `if (chainActive)` 到 `drawPostChain` 之间不得再出现
afterLevel」）。

## 五¾、更正后重测（route 自报 = chain=true，帧尾数字）

| 轮 | `colortex0`（链里最后写它的是 composite3） | `colortex1`（链输出） | `main` |
|---|---|---|---|
| #4 | mean_luma 28.53，nonBlack 53.98% | 110.45 | — |
| #5 | 5.64，nonBlack 9.99% | 45.96 | 45.99 |
| #6 | **0.00** | **0.00** | **0.00** |

⇒  intermittency 在**链内部**（同一条链逐级把内容打到越来越暗，直到整帧为 0），
不是「地形没进池」—— 之前那条推断也错了：帧尾的 `colortex0` 早被链覆写，
地形内容要看链**开跑前**的那一槽。下一线 = 用 `mrt.postChainMaxPasses` 做**链级二分**
（B2/B3/B6/B7 四臂），判据全部要求带 `[route] chain=true` 自报行。

## 五⅝、链级二分（`mrt.postChainMaxPasses`，route 自报 = chain=true，正午）

| 臂 | 跑到第几级 | 判读对象 | `colortex0` FULL |
|---|---|---|---|
| B2 | 2 级（`deferred` → `deferred1`） | deferred1 写 c0 | **mean_luma 37.36，nonBlack 74.3%，maxR=255** ⇒ 有内容 |
| B3 | 3 级（+ `composite`） | composite 写 c0 | **mean_luma 0.00，allZero=true** ⇒ 内容在这一级没了 |

⇒ **前线收窄到 BSL 的 `composite`（链内 index 2，写槽 [0,1]）**：它的输入（c0）有内容，
输出同一槽却是 0。下一刀 = 看 `composite` 里哪条分支把 `gl_FragData[0]` 写成 0
（候选：`color = texture2D(colortex0, texCoord)` 之后被 `if (...)` 分支覆盖 /
`#if` 关掉的路径让它根本没写 ⇒ 该附件停在 LOAD 的旧代上）。

## 六、当前判据（正午、晴天、探针关）

```
61.54 / 43.21 / 44.83 / 45.29 / 45.37 / 9.25 / 45.31 / 9.23 / 45.22 / 45.18
```
正常帧（≈45）是一张可辨认的 BSL 画面；暗帧（≈9.2）里**世界全黑但手与 HUD 正常**
⇒ 暗帧 = 地形内容没进链的输入，而不是链算错。天空仍是黑的 = 非地形 gbuffers 程序未接
（GAP-003/015 那条线，与本文的闪屏不是同一件事）。

## 七、GAP-019 的逐帧自报把最后一个假设也砍掉了

`MrtTerrainPass` 加了窗口自报（每 120 帧一行：本窗口见过几个不同的 draw 对象、
捕获与本帧是否 1:1）：

```
[GAP-019] 地形重放自报: 窗口=120 帧, 不同 draw 对象=120 个, capture#=120 framesDrawn=119 代次错配的帧=0
```

⇒ 原版**每帧新建** `ChunkSectionsToRender`，我方重放与捕获严格 1:1 ——
「捕获到的是被回收复用的池对象」这条假设**证伪**（不需要为它改代码）。

## 八、收口判据（正午、晴天、地形在帧图内、双代轮转、探针 every=60）

```
61.43 / 42.87 / 44.35 / 44.81 / 44.91 / 44.96 / 44.98 / 45.00 / 45.01 / 45.02
```

**暗帧不再出现**，亮度单调收敛 —— 那正是自动曝光该有的形状（首帧 61.4 是 `/time set`
之后曝光仍在收敛）。画面内容可辨认：雪面、树、树荫暗部、手里的物品、水面。
剩余已知缺陷是**天空全黑**（非地形 gbuffers 程序未接 ⇒ GAP-003/015 那条线），
与本文的闪屏不是同一件事。

## 九、元纪律（本轮新增两条）

1. **注入必须回读自证，而且自证不能污染被测量**：为「关掉聊天框」而盲打 Escape，
   实际打开了**暂停菜单**（Escape 在聊天框关着时的语义），于是拍到 10 张「Game Menu + 模糊世界」，
   亮度稳定得像「闪屏修好了」。这一臂作废。⇒ 现改为 `chat` 序列内部「先 Return 再 t」，
   不打 Escape。
2. **定长 sleep 不是「进世界」的判据**：改包选项会触发整包重编译，110/150 秒都可能不够；
   打空了的按键会被加载界面吞掉 ⇒ F2 一张图都没有。现改为轮询日志里的真信号，
   并且**空结果显式报错**（「没有截图」≠「画面正常」）。

## 十、黑帧责任侧钉死 + mip 判据（NeoForge 升版后重测）

### 10.1 两个观测点分责任（T8 臂，`mrt.pixelProbeAfterTerrain=true`）

| 轮 | `c0@afterTerrain`（链跑之前） | `colortex0`（帧尾） | `main`（帧尾） |
|---|---|---|---|
| #2 | 65.9 / 79.6 / 117.3 / 130.4（四个区各一） | 30.9 / 38.3 / 66.2 / 74.3 | 122.5 / 127.4 / 173.4 / 175.3 |
| #3 | **65.9 / 79.6 / 117.3 / 130.4（有内容）** | **0.0000** | **0.0000** |

⇒ 地形 MRT pass **每帧都把内容画进了池**；黑的是**链**。
（因此 §五¾ 里「地形没进池」的说法一并作废，GAP-019 已按此改写。）

### 10.2 单变量判据：链采样器钉 mip0（Y1 臂，`mrt.chainSamplerLod0=true`）

```
main   = 0(预热) / 163.9 / 78.2 / 135.6 / 186.2      ← 没有一帧是 0
colortex0 = 0(预热) / 61.8 / 13.4 / 63.5 / 118.0
```

对照 T8 / X1（完整 mip 范围）：`main = 0 / 122.5 / 0 / 122.5 / 122.5 / 0`。

⇒ **黑帧由 mip 选择决定**（登记为 GAP-020，与地形侧的 GAP-016 同族）。

### 10.3 为什么不能就这么交付

BSL 的 `BloomTile` 是**故意**靠导数取级的：`coord = (coord − offset) × exp2(lod)` ⇒ 坐标梯度
×2^lod ⇒ 隐式 LOD 自动变成 `lod`。把链采样器钉死 mip0 会把这套 tap 一起压到 0 级 ——
那正是 GAP-017 K 臂量到的「八 tap 同图 ⇒ 必然过曝」。所以 `chainSamplerLod0` 只是**判据档**；
产品档要么修好导数，要么按调用点区分（`texture2D` 未缩放坐标 ⇒ 显式 LOD0，缩放坐标 ⇒ 保留导数）。

### 10.4 本轮顺带完成的版本升级

`neo_version` 26.3.0.41-beta → **26.3.0.51-beta**，`net.neoforged.moddev` 2.0.147 → **2.0.148**
（来源 = 官方 maven metadata，2026-10-06 当时 26.3 线最新）。
`./gradlew build` 全绿；客户端在新版上真实起跑并有判据（V1/T8/Y1 三臂：
`backend=Vulkan`、`[route] chain=true`、`post chain executed: passes=11`），
除本机固有的 `flite`/`OpenAL` 缺失外无新增异常。`docs/05-VERSION.md` §2 已同步。

## 十一、逐 pass 追踪把黑帧钉到**第一个写 colortex0 的 pass**

`mrt.postChainTrace`（每级 pass 写完立刻回读它写过的槽，标签 `traceK<name>:cN`）。
判据必须是**同一臂、同一机位、逐帧**（`pixelProbeEvery=1`）—— 早期用 60 帧间隔取样，
一条曲线里混着不同帧的数字（同一标签同一轮出现 186.2 与 0.0 两个读数），因此
`TargetReadback.beginFrame()` 改为**帧首决定本帧是否取样**（地形后取点与逐 pass 追踪共用同一判定）。

好帧 / 黑帧对照（round 169 / 170，同一次运行）：

| 取点 | 好帧 #169 | 黑帧 #170 |
|---|---|---|
| `c0@afterTerrain`（链之前） | 93.689 | **93.689（一样）** |
| `trace1deferred1:c0` | 90.305 | **0.0000** |
| `trace1deferred1:c4` / `:c5` | 54.2 / 13.7 | 54.213 / 13.652（**有内容**） |
| 之后每一级 c0/c1 | 64.0 / 127.6 | 全部 0.0000 |

⇒ 黑帧从**链里第一个写 colortex0 的 pass** 就开始，而同一个 pass 写别的槽都正常；
地形侧完全无辜。周期 = **严格 3 帧**（`内容 / 内容 / 黑` 无限重复）。

## 十二、修根：金字塔必须与 mip0 **同源**（写完立刻重建，不再惰性重建）

真因不是「导数选错级」本身，而是 **GAP-018 的双代轮转 + 惰性金字塔重建** 这对组合：
旧代码在「下一个读者之前」才重建 ⇒ 每次写 mip0 之后，那一代的 mip1..N 还是**上一次**建的内容
（Z5 臂实测：`trace2composite:c0@m0=14.7` 而同步 `@m4=45.2 / @m8=34.0` —— 明显不同源）。
包用隐式导数取级时读到的是那个陈旧金字塔 ⇒ 某些帧整屏算成黑。

修法（`FrameApi`）：
- 删掉 `refreshMipPyramids` 与脏集；
- 帧首为声明了 mip 的槽建一次（地形写的那一代）；
- **每级 pass 写完之后**立刻对它写过的 mip 槽重建（`regeneratePyramidsForWritten`）。

判据（`mrt.chainSamplerLod0` 保持默认 false = 完整 mip 范围）：

```
Z6 臂 逐帧 main：190 帧里只有开局 3 帧（未进世界）为 0，其余 185 帧恒 93.1338
                [route] chain=true afterLevel=false toMain=false
F2 截图（探针全关，独立通道）：59.6 / 59.3 / 58.9 / 58.4 / 58.2 / 58.2 / 58.1
```

⇒ **黑白闪屏消失**。另有 3 张 23~27 的截图是注入 `/time` 时按键误开了**成就界面**
（看图才知道，光看亮度表会误读成「画面变暗」—— 元纪律：**判读对象必须自报它是什么**）。

## 十三、`chainSamplerLod0` 的定位（它是判据档，不是产品档，也差点误导）

Y1 臂「钉 mip0 ⇒ 黑帧消失」是真的，但当时据此写的「黑因 = 隐式导数选坏 mip」只说对了一半：
钉 mip0 之所以有效，是因为它让读者**永远不碰**那个陈旧金字塔。真正的修根在金字塔的时机，
不在采样器 —— 所以 `mrt.chainSamplerLod0` 保留为判据档（默认 false），产品档是 §十二。

## 十四、深度回读这条通道**不可信**（已删，别再造它）

想验「包按 GL 口径写 `z >= 1.0` 当天空，而我方地形 pass 的深度清屏是 `0.0`」这件事，
给探针加过一条 `depthtex0@gbuffer`（读 `colortexDepth.getDepthTexture()`）。实测读数：

```
SKY_BAND   meanRGB=(110.4, 112.0, 107.6) nonBlack=87.3%
TERRAIN_BAND meanRGB=(35.4, 35.3, 28.7)  nonBlack=27.8%
```

D32 若是 0.0f 应当**全字节 0**、若是 1.0f 应当是 `00 00 80 3F`（R=G=0、B=128、A=63）。
三个通道都 ~110 的形状**两种都不是** ⇒ 这是 `copyTextureToBuffer` 对 depth-aspect 图像
取到未定义内容，不是深度值。⇒ 该取点与 `depthTexture()` 已删除。
**结论**：本机判深度语义不能走回读，只能走「包侧行为的差分」（例如同一机位下
`z >= 1.0` 分支进/出画面的对照臂）。

## 十五、天空线：A/B 把责任**从深度**上切下来之前，先撤回一个我自己在登记表里写下的错机制

### 15.1 深度语义：源码级解决（不需要再猜，也不需要回读）

上一节刚说过「本机判深度语义只能走包侧差分」。但这一条**根本不用测** —— 后端源码就在
`build/moddev/artifacts/minecraft-patched-26.3.0.51-beta-sources.jar` 里：

`com/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder.java` 第 307-321 行：

```java
if (depthAttachment != null) {
    depthAttachmentInfo.imageLayout(1);              // GENERAL
    depthAttachmentInfo.storeOp(0);                  // STORE（恒置）
    OptionalDouble clearValue = depthAttachment.clearValue();
    if (clearValue.isPresent()) { ... depthAttachmentInfo.loadOp(1); }   // CLEAR
    else                        { depthAttachmentInfo.loadOp(0); }       // LOAD
}
```

`VK_ATTACHMENT_LOAD_OP_LOAD=0 / CLEAR=1 / DONT_CARE=2`。原版天空 pass 传的是
`OptionalDouble.empty()`（`SkyRenderer:139`）⇒ **天空 pass 不清深度**，它是 LOAD + STORE。
⇒ 登记表 GAP-003 行里我写的「深度附件清/覆盖了 gbuffer 深度 ⇒ 已确认有害」**撤回**：
那是从症状倒推的机制，深度一次都没测过，而后端源码直接否证了它。
（仍然成立的部分：STORE 恒置 ⇒ 天空那批片元的深度值会**写进**我方 gbuffer 深度。）

### 15.2 同一份代码的两臂 A/B：天空开 = 链输出恒 0（不是「闪」，是**每帧都塌**）

两臂只差 `mrt.skyPass`，其余档位与代码逐字相同（`pixelProbe=true every=20`、
`postChainTrace=true slots=0,1`、`terrainAfterLevel=false`、`enabled=false`），
观测面都用 `/gamerule doDaylightCycle false` + `/time set 6000` + `/weather clear` 钉住。

| 取点（同一 round 内） | `skyPass=false`（对照） | `skyPass=true` |
|---|---|---|
| `c0@afterTerrain` | 有内容 | **14.4170** luma、nonBlack 55.9% |
| `c0@afterSky` | （不跑） | **81.3139** luma、nonBlack 82.3%、meanRGB=(73.3, 81.4, **104.6**) ⇒ **蓝主导 = 天空真的画进去了** |
| `trace1deferred1:c0` | 未命中（该臂无 trace 行） | **0.0000** `allZero=true`，每轮都是 |
| `colortex0`（帧尾） | 2.3169 | **0.0000** `allZero=true` |
| `colortex1`（帧尾） | 34.5901 | **0.0000** `allZero=true` |
| `main`（帧尾） | **34.5630** | **0.0000** `allZero=true` |
| F2 截图 | 10 张 44.18~45.22（自检「白天注入生效」） | 10 张 7.82~7.83（自检 ❌「疑似仍在夜晚」⇒ **画面这一侧不能当判据**，故本表全用探针数字） |
| 日志 `ERROR` 条数 | 0 | **0**（所以「链被异常打断」这条也否掉了） |

🔖 **三条当时踩到的判读陷阱，都记下来**：
1. **数字逐轮相同 ≠ 回读卡住**。对照臂 `main#188/189/190` 在 h47-lane-Z6 里也是同一串
   `91.6478…` 重复 —— 静止世界 + 不动相机本来就该给重复数字。判定要看**同一轮内的跨取点差分**，
   不是跨轮差分。
2. **链级 trace 标签不是 `colortex0@pass`，而是 `trace<序号><程序名>:c<槽>`**（`TargetReadback#probeChainPass`）。
   我一开始按前者 grep，得到「trace 一条都没有」，差点把「追踪没生效」当成一个结论。
3. **截图自检必须留在判据链里**：这一臂的 F2 全是夜晚亮度，画面侧完全不可用；
   没有自检行的话，「7.8 luma」会被误读成「天空臂把画面弄黑了」。
   ⇒ 天空臂的结论**只**由探针数字支撑。

### 15.3 天空臂的 0 是**真 0**，不是回读没落地（先证明探针有资格说话）

在拿「帧尾全 0」下任何结论之前，先确认这条通道不会自己造 0。`TargetReadback#collectReady`：

```java
if (!slot.copyReturned) {
    if (waited > COPY_STALL_TICKS && STALL_NOTED.compareAndSet(false, true)) {
        LOGGER.warn("… 拷贝回调 {} 个节拍没回来 ⇒ 释放该槽（**这轮该源没有数字**，不是画面为 0）");
    }
    continue;                       // ← 没回来的槽**绝不** finish
}
if (waited < READ_DELAY_TICKS) continue;
finish(slot);                       // ← 只有 copyReturned 才会读数
```

⇒ 读数只可能来自「拷贝已完成」的槽；而未完成的拷贝会打一条**带原文的 WARN**。
天空两臂的日志里 `ERROR` 0 条、`拷贝回调` 0 条 ⇒ **那些 0.0000 是纹理真值**，
不是缓冲没填。（这一条必须写下来：它同时也是「为什么 15.2 敢用探针数字当判据」的凭据。）

### 15.4 `mrt.skyOwnDepth` 臂：深度这条线**否掉了**，顺带掉出一个更要紧的事实

第三臂把天空挂到一张**私有深度**（每帧清到远平面 0.0）上，gbuffer 深度一个 bit 都不被天空碰
（自报行逐字：`深度 = 私有(每帧清)`）。结果：

| 取点 | `skyPass=true` + gbuffer 深度 | `skyPass=true` + 私有深度 | `skyPass=false` |
|---|---|---|---|
| `c0@afterTerrain` | 14.4170 / 39.0164 / 5.9607 / 25.8884（四个值轮转） | **同左，逐位相同** | **同左，逐位相同** |
| `c0@afterSky` | **81.3139**（每轮同一个数） | **81.3139**（每轮同一个数） | 不跑 |
| `colortex0` / `colortex1` / `main`（帧尾） | 0.0000 `allZero=true` | **0.0000 `allZero=true`** | 2.3169 / 34.5901 / **34.5630** |
| 日志 `ERROR` / 「拷贝回调没回来」WARN | 0 / 0 | 0 / 0 | 0 / 0 |

⇒ **结论 1（本臂的判据）**：天空写进深度的值**不是**链塌成 0 的原因。私有深度下 gbuffer 深度
   完全干净，帧尾照样全 0。⇒ 「给天空一张只读/独立深度」这个原计划方案**当场作废**，
   不用做了（它修不了任何东西）。
⇒ **结论 2（没打算掉出来的，但比结论 1 值钱）**：`c0@afterSky` 在**两种深度档下同值**，
   而且**不随地形内容变化**（`afterTerrain` 在四个值之间轮转，它恒 81.3139）。
   私有深度里没有地形 ⇒ 那一臂的天空必然画满全屏；两臂同值说明
   **gbuffer 深度那一臂的天空也画满了全屏** ⇒ 原版天空**根本没有被地形遮挡**。
   机制方向（未核实，下一步测）：`RenderPipelines.SKY` 用默认深度状态 + `core/sky.vsh`
   直接 `ProjMat * ModelViewMat * Position`（天空盘 z 在最远/最近那一端），
   与本引擎**反向 Z**（远 = 0.0）对不上 ⇒ 深度比较处处通过。
   ⚠️ 这条改变了「天空进 gbuffer」的语义：现在进的不是「补上天没被地形挡住的那片」，
   而是**把地形整片盖掉** —— 所以 15.2/15.4 的「天空臂」不能读成「天空画对了但链不给力」，
   至少要先承认「天空把 gbuffer 覆盖干了」。

## 十六、把天空排到地形**之前**之后发生的事（h48f/h48g 两批四臂）

### 16.1 `terrainAfterLevel=true` 批：链输出从恒 0 变成 `main=108`，但对照臂暴露了一个更大的坑

档位：`terrain=true`、`terrainAfterLevel=true`、`pixelProbe every=20`、白天钉死（两臂自检都是
「白天注入生效」），两臂只差 `mrt.skyPass`。

| 取点 | `skyPass=true`（新顺序） | `skyPass=false`（对照） |
|---|---|---|
| `c0@afterSky` | **每轮不同**：72.85 / 75.24 / 77.97 / 81.41 / 84.50 / 84.06 / 83.19 | 不跑 |
| `c0@chainStart` | 63.36 → 61.34（**低于** afterSky ⇒ 地形确实盖在天空之上） | 14.0059（与 afterTerrain 逐位相同） |
| `c0@afterTerrain` | — | **恒 14.0059**（新顺序前它在帧图档是 5.96~39.02 四个值轮转） |
| 帧尾 `colortex0` | 37.02 → 31.22 | **0.0000 `allZero=true`（每轮）** |
| 帧尾 `colortex1` | 108.04 → 108.56 | **0.0000** |
| 帧尾 `main` | **107.92 → 108.46** | **0.0000** |
| F2 亮度 | 84.94 / 7.80 / 85.96 / 93.83 / 8.49 / 10.43 / 93.80 | 4.86 / 4.85 / 4.82 / 34.34 / 5.37 / 4.80 / 4.80 / 4.79 / 4.78 / 34.30 |
| `ERROR` | 0 | 0 |

🔴 **本批最重要的读法不是「天空臂好了」，而是「对照臂也全 0」**：
`terrainAfterLevel=true` 且**不开天空**时，帧尾三源同样是 `0.0000` 每一轮。
⇒ 之前 15.2/15.4 里「天空开 = 链塌 0」这条**归因不成立**：那个 0 是
**地形在帧图外重放**这件事自己的后果（正是登记表 GAP-019 行第 351 条量过的
「`terrainAfterLevel=true` ⇒ 黑帧」形状，这次量到的是「几乎每帧」）。
我差点把「天空把内容盖光」和「地形重放本来就不出内容」两件事混成一件 ——
差别只在**对照臂有没有跟着改档位**。加了天空就必须同时把对照臂也搬到同一档位，
否则测的是档位而不是天空（这条写进 `08-TESTING.md` 的判据边界）。
⇒ 于是新顺序的真正成绩只有一个是干净的：**天空臂的帧尾从 0 变成了 108**，
说明天空先铺 + 地形 LOAD 这条路径**确实把内容送进了链**；而它到底是不是产品档能用的，
必须在 `terrainAfterLevel=false`（帧图档，地形可靠落池的那一档）里重测 —— 见 16.2。

### 16.2 帧图档接天空：**失败，且失败原因是「插入序 ≠ 执行序」**

把天空改成帧图 pass（`onFrameGraphSetup` 里先插 `vkdisp_gbuffer_sky` 再插 `vkdisp_gbuffer_terrain`），
档位回到取证一直用的那一档 `terrainAfterLevel=false`，白天注入两臂都成立。

| 取点 | 帧图档 + 天空（h48g fgSkyOn） | AfterLevel 档 + 天空（h48f skyOrderOn） |
|---|---|---|
| `c0@afterSky`（天空刚铺完） | **0.0611 / 0.0715 / 0.0780 / 0.0832** | 72.85 / 75.24 / 77.97 / 81.41 / 84.50 |
| `c0@chainStart`（链开跑前） | **14.03** | 63.36 → 61.34 |
| `c0@afterTerrain` | **14.03**（与 chainStart **逐位相同**） | — |
| 帧尾 `main` | 34.79 | 107.92 |
| `ERROR` | 0 | 0 |
| 天空自报 | 「地形 pass 的 colortex0 附件 = LOAD」也打了 ⇒ **配置生效、代码路径走到了** | 同 |

读法：`afterSky` 只有 0.06（≈ 黑），而 `chainStart` 已是 14.03 = 地形内容 ⇒ **天空铺完之后地形又画了一遍**
⇒ 天空在地形**之后**执行。而 LOAD 附件的语义是「地形不清槽 0」，所以地形那一片盖掉了天空的
那一片 —— 顺序完全反了。
⇒ 机制：帧图的执行序由**资源依赖**解析（`FrameGraphBuilder#resolvePassOrder`），
**插入序不是依赖**。我方两个 pass 都不声明「我要先读/写 colortex0」⇒ sky 被排到 terrain 之后。
`disableCulling()` 只保证 pass 不被剔除，**不保证顺序**（这一点此前被我当成顺序保证，是错的）。
⇒ 处理：**帧图档的天空挂点撤掉**（代码留在注释里说明为什么不能这么做），天空只挂 AfterLevel 档；
`skyPrePainted()`（地形槽 0 改 LOAD 的判据）同步要求 `afterLevel()`，
守卫测试 `RenderRouteWiringTest#skyIsDispatchedBeforeTerrain` 直接把这条写成断言。
⇒ 未核实的前置（下一轮如果要在帧图档接，先做这条）：原版 `FramePass` 到底有没有
「声明本 pass 读/写某个 `GpuTextureView`」的公开入口。**没核实就不做**（X9）。

### 16.3 补上「未核实」那一步：`FramePass` 确实有声明顺序的公开入口（源码逐字）

16.2 结尾留的「未核实」不能留着猜（X9）。`com/mojang/blaze3d/framegraph/FramePass.java` 全文：

```java
public interface FramePass {
    <T> ResourceHandle<T> createsInternal(String name, ResourceDescriptor<T> descriptor);
    <T> void reads(ResourceHandle<T> handle);
    <T> ResourceHandle<T> readsAndWrites(ResourceHandle<T> handle);
    void requires(FramePass pass);          // ← 显式的 pass 间顺序依赖
    void disableCulling();
    void executes(Runnable task);
}
```

`FrameGraphBuilder#resolvePassOrder` 的实现决定了 `requires` 的方向语义：

```java
for (int id = pass.requiredPassIds.nextSetBit(0); ...) {
    this.resolvePassOrder(this.passes.get(id), ..., output);   // 先递归解析被要求方
}
for (Handle<?> handle : pass.writesFrom) { ... 写→读 边同样先解析 ... }
output.add(pass);                                             // 最后才把自己放进序列
```

⇒ **被 `requires` 的一方一定排在前面**（后序插入）。⇒ 帧图档的天空可以接，写法是
`terrainPass.requires(skyPass)`，而不是「先 addPass」。
🔖 顺带把 16.2 那条误读钉死：`disableCulling()` 只出现在 `identifyPassesToKeep`
（管**剔除**），与执行序无关 —— 我之前把它当成了顺序保证的一部分。
⇒ 落到代码 + 守卫：`MrtTerrainPass#onFrameGraphSetup` 里 `pass.requires(skyPass)`，
`RenderRouteWiringTest` 直接断言这行存在且位于 terrain `addPass` 之后
（「靠插入序排帧图」这种写法以后一进 diff 就红）。

## 十七、h48i/h48j：帧图档接天空「不生效」的真根因 —— **代次视图被缓存了**

### 17.1 现象（h48i，`terrainAfterLevel=false` + `skyPass=true`，`requires` 已生效）

| 取点 | 读数 |
|---|---|
| `c0@afterSky`（天空刚铺完，读**待写代**） | **0.0000 `allZero=true`**（每轮） |
| `c0@afterTerrain`（地形翻代后，读新的被读代） | **82.7168**（每轮同一个值） |
| `c0@chainStart` | **82.7168**（与 afterTerrain 逐位相同） |
| 帧尾 `colortex0` / `colortex1` / `main` | 0.0000 / 0.0000 / 0.0000 |
| 自报 | 「地形 pass 的 colortex0 附件 = **LOAD**」+「天空重放器就绪：目标 = colortex0 **待写代**」都打了 |
| `ERROR` | 0 |

⇒ 天空 pass 跑了、地形也在 LOAD 同一槽，但天空那一片是**空的**：连 `prepareGbuffer`
清出来的 `alpha=1.0` 都不在（`allZero=true` 是四通道全 0）⇒ 探针读的那张图**根本不是天空写的那张**。

### 17.2 根因（一行代码的形状，但症状完全不像它）

`GbufferTarget` 的视图只在 **rebuild 时**设过一次，而 GAP-018 的双代轮转里
「待写那一代」**每帧交替**（`ColortexPool.advanceWritten` 翻 `cur`）。于是：

```
帧2：cur=A → 天空画进 B；地形写 B、翻代 → cur=B        （这一帧对）
帧3：cur=B → 天空仍画进 B（缓存的视图）；地形写 A、翻代 → cur=A  （天空被丢在上一代）
帧4：cur=A → 天空又画进 B …
```

⇒ 从第二帧起，天空画进的**不是本帧地形要写的那一代** ⇒ 链永远读到「只有地形」，
而探针读的是「本帧待写代」⇒ 拿到的是那张**从没被写过的初生纹理** = 全 0。
`SkyRenderer` 每帧现取 `renderTarget.getColorTextureView()`（源码第 134 行）⇒ 修法就是
每帧重指视图，不必重建渲染器：新增 `GbufferTarget#repoint(color, depth)`，
在 `SkyIntoGbuffer.render()` 里**每次**调用。

🔖 **为什么这条值得单独一节**：它同时是 15.2/15.4/16.1 那几张表的**共同污染源**。
「`c0@afterSky` 恒 81.3139 不随地形变」「AfterLevel 档帧尾 `main=107.9`」这些读数
都出自**同一个 bug 尚未修掉的构建**，它们里的**代次相关结论一律待重测**：
本节的 h48j 两臂（帧图档、`requires` + `repoint`，只差 `skyPass`）才是干净的对照。
⇒ 规矩：**A/B 之前先确认取点读的是哪一代**。探针跟着代次走、绘图对象不跟着走，
量出来的就是「两个不同纹理之间的差分」，而不是「同一纹理的前后差分」。

### 17.4 但先别用 17.1 那批数字 —— **两臂全都落在探针自己声明的预热窗里**

h48j 两臂（帧图档，`repoint` 前 / 后）都打了这一条：

```
vkdisp: [pixel-probe] 地形 MRT pass 只画了 2 帧（< 600） ⇒ **预热期内不产出对照结论**，只报原始数字。
        ⚠️ 头几帧的 colortex 可能只有清屏值 —— 实测同配置两轮可读出 0.0000 与 12.42 而 main 逐位相同
```

而取点落在 `round #9 ~ #14`、`every=20` ⇒ 那只是**第 180~300 帧**。lavapipe 上跑到 600 帧
要更久，脚本里那个定长 `sleep 20` 根本不够 ⇒ **两臂的读数全在预热窗内**。
⇒ 所以 17.1 那张表（以及 15.2/15.4/16.1 里凡是每 20 帧、round < 30 的读数）**不能当对照用**：
「一臂 `main=0.0000`、另一臂 `main=140.5`」这个差异，与「两臂 `c0@afterTerrain` 逐位相同」
放在一起，最合理的解释就是**取样窗口本身**，不是渲染。
⇒ 处理（工具侧，不是判据侧）：`h48_flicker_capture.sh` 加了**预热闸门** ——
不在定长 sleep 后拍，而是**等探针自己的 round 号**过 `H48_MIN_ROUND`（本轮设 60，
配 `every=10` ⇒ ≥600 帧）才注入命令与连拍，并把预热末态打出来。
判据不变：`skyPass` 单变量、两臂同档位、同 round 区间跨取点对照。
⇒ 🔖 顺带说明为什么 17.2 那条**机制**推理不受影响：它不来自数字，来自代码级事实 ——
`ColortexPool.advanceWritten` 每帧翻 `cur`，而 `GbufferTarget` 的视图原来只在 rebuild 时
设一次，`SkyRenderer` 每帧现取 `getColorTextureView()`。⇒ 「天空从第二帧起写错代」
是结构结论，h48k 只是去量它修没修好。

### 17.5 预热窗后的干净对照：**「链输出恒 0」在两臂都不成立**

同一档位（`terrainAfterLevel=false` 帧图档）、同一构建、只差 `mrt.skyPass`、
都过了预热闸门（`every=10`，等到 round≥60 ⇒ ≥600 帧才采）：

| 取点 | `skyPass=true` | `skyPass=false`（对照） |
|---|---|---|
| `c0@afterSky` | 0.5270 → 0.9066（缓慢抬升，`allZero=false`） | 不跑 |
| `c0@afterTerrain` | **71.2899** | **73.2414** |
| `c0@chainStart` | 71.2899（与 afterTerrain 同） | 73.2414（同） |
| 帧尾 `colortex0` | 20.3347（round #110 有一次 **0.0000**） | **32.3517**（六轮全同） |
| 帧尾 `colortex1` | 137.0620（同轮一次 0.0000） | **123.5937**（六轮全同） |
| 帧尾 `main` | 137.0773（同轮一次 0.0000） | **123.5968**（六轮全同） |
| `ERROR` | 0 | 0 |
| 截图 | ❌ 没落地（本臂无画面判据） | ❌ 没落地 |

三条结论，按证据强度排：
1. ✅ **「链输出塌成 0」这条整体撤销** —— 预热窗之后两臂帧尾都是稳定非零（137.1 / 123.6）。
   此前所有「恒 0」判读（15.2 的表、15.4、16.1、17.1）都是**同一份测量偏差**：
   取点落在第 180~300 帧，而探针自己写了「< 600 帧不产出对照结论」。
   🔖 教训的通用形式：**判据窗口必须由被测系统自己声明的「预热结束」信号界定**，
   不能由脚本里的一个定长 `sleep` 代替。
2. 🔴 **天空还没有真的进到链的输入里**：开天空臂 `c0@chainStart=71.2899` 与对照臂 73.2414
   几乎一样（差 1.95），而 `c0@afterSky` 只有 0.53~0.91 ⇒ 天空那一层铺上去的是**近黑**，
   随后被地形盖住。两种可能未切开：① 世界此时是**夜**（本臂聊天注入没落地，夜空本来就黑）；
   ② 天空确实没画进这代。⇒ 下一刀必须是**白天钉住**的臂（注入要真落地，判据 =
   `c0@afterSky` 应显著高于 0.5，且与 `afterTerrain` 的差可读）。
   🔖 这里不再重犯 15.4 的错：那次我用「两臂 afterSky 逐位相同」推出「天空铺满全屏」，
   而那批数字同样在预热窗内 —— 那条推断**降回未证**。
3. ⚠️ **开天空臂仍有间歇 0**（5 轮里 1 轮 colortex0/colortex1/main 同时为 0），对照臂 6 轮没有。
   样本 1/5 vs 0/6，**不足以定责**；但方向上它和 GAP-019「重放有时不出内容」是同一形状，
   下一轮把 `every=1` 连续 200 帧跑一次两臂对照，才有资格说是不是天空带来的。

### 17.6 观测面自报一上来就值回票价：天空**想**写的颜色是亮的，但它那张纹理连清屏都没落进去

h48l（帧图档、`skyPass=true`、过了预热窗、`every=10`）：

```
[GAP-003/sky] 观测面自报: clockTime=9257（当地时 9257）,
              skyColor=(0.514, 0.620, 1.000) 这是天空片元要写进 colortex0 的值,
              rain=0.000 render#=600
```

同轮探针取点：

| 取点 | 读数 |
|---|---|
| `c0@afterSky`（天空写完，读**待写代**） | **0.0000 `allZero=true`**（每轮） |
| `c0@afterTerrain` / `c0@chainStart` | 73.2414 / 73.2414 |
| 帧尾 `colortex0` / `colortex1` / `main` | 在 `0.0000 allZero=true` 与 `32.3517 / 123.5937 / 123.5968` 之间**按轮交替**（#107 空、#108 满、#109 空、#110 空、#111 满） |
| 截图 | ❌ 又没落地（F2 没进帧）⇒ 画面侧仍无判据 |

🔑 三条硬事实，不是推断：
1. **世界时刻与天空颜色都由被测对象自己报了**：当地时 9257~11031（白天偏 afternoon），
   `skyColor=(0.514,0.620,1.000)` 是**亮蓝**。⇒ 17.5 里那句「也许夜空本来就黑所以读数近黑」**被否证**：
   天空要写的值不黑。
2. **`allZero=true` 意味着连 alpha 都是 0** ⇒ 我方在天空之前那次
   `clearColorTexture(待写代, (0,0,0,1))` **也没有落进这张被探针读的纹理**。
   清屏是 CPU 侧发起、不经任何着色器的写入 —— 它都不在，说明**探针读的那张图与天空/清屏写的
   图不是同一张**（或写入根本没执行），而不是「天空画了但很暗」。
3. **帧尾三源按轮交替**（0 ↔ 非 0），而且 `colortex0/colortex1/main` **同步**——
   三个不同纹理同一轮同时为 0、下一轮同时满，这个形状不像渲染，像**取点打到了不同代次**
   （双代轮转 + `every=10` 的奇偶耦合）。⇒ 之前把它读成「间歇性整帧塌黑」需要重新审视。

⇒ 下一刀（h48m，纯仪器改动、零渲染行为变化）：`probeAfterSky` 同帧**再取一次被读那一代**
（`c0@skyReadGen`）。判读表：
- `afterSky=0` 而 `skyReadGen` 有天空色 ⇒ **天空其实写在另一代**：我方的代次模型/翻代时机错
  （`ColortexPool.writeView/writeTexture` 都是 `1-cur`，代码上看应当同物 ⇒ 若成立，
  说明 pass 之间有人翻代，或 `SkyRenderer` 用的视图不是我方给的那个）；
- 两代都空 ⇒ 天空那批 draw 根本没落到任何纹理（回到 pass/管线层查）。

### 17.7 h48m：**天空确实落进了待写代**，而「黑帧」与「代次」是同步跳动的

同一档位、唯一改动 = `probeAfterSky` 多取一次**被读那一代**（纯仪器，零渲染行为变化）。
观测面这一轮自己报了：`clockTime=12124`（当地时 12124，黄昏前后）、
`skyColor=(0.440, 0.531, 0.857)`。

| 轮次 | `c0@afterSky`（待写代 = 天空刚写的） | `c0@skyReadGen`（被读代 = 上一帧的结果） | 帧尾 `main` |
|---|---|---|---|
| #28 | 48.0433 | 0.0000 `allZero` | **0.0000** `allZero` |
| #29 | 51.3734 | 0.0000 `allZero` | **0.0000** |
| #30 | 53.3853 | **32.2194** | **122.8393** |
| #31 | 55.4389 | 0.0000 `allZero` | 0.0000 |
| #32 | 57.4569 | 0.0000 | 0.0000 |
| #33 | 59.0582 | **32.2194** | **122.8393** |
| #34 | 60.4933 | 0.0000 | 0.0000 |
| #35 | 61.9222 | 0.0000 | 0.0000 |

两条当场可以下的结论：
1. ✅ **天空画进了 colortex0 的待写代**，而且数值跟着它自己的亮度走
   （48.04 → 61.92 单调升，与 `skyColor` 从 (0.514,0.620,1.000) 到 (0.440,0.531,0.857)
   这一路是同一条黄昏曲线）。⇒ h48l 那次「afterSky 全 0」不是天空没画，
   而是**另一件事**（见下面 17.8 的修法）。
2. 🔴 **「间歇性整帧塌黑」有测量侧的等价解释，而且它在数据里可见**：
   `c0@skyReadGen` 与帧尾 `main` **同轮同起同落**（两者都非零的轮 = #30/#33，
   其余轮两者同时为 0）。⇒ 高度指向**取点打到了这一帧没被写过的那一代**，
   而不是画面每两帧塌一次。要坐实只需把**两代同时取**（h48n 正在测）：
   - 两代都有内容 ⇒ 黑帧是取点问题（此前所有「间歇黑帧」结论要重读）；
   - 只有一代有 ⇒ GAP-018 的轮转账真的算错了。

### 17.7 h48m：**天空确实落进了待写代**（观测面自己报了它为什么后来变暗）

唯一改动 = `probeAfterSky` 同帧多取一次**被读那一代**（纯仪器，零渲染行为变化）。
观测面自报：`clockTime=12124`、`skyColor=(0.440, 0.531, 0.857)`。

| 轮 | `c0@afterSky`（待写代） | `c0@skyReadGen`（被读代） | 帧尾 `main` |
|---|---|---|---|
| #28 | 48.0433 | 0.0000 `allZero` | 0.0000 |
| #29 | 51.3734 | 0.0000 | 0.0000 |
| #30 | 53.3853 | **32.2194** | **122.8393** |
| #31 | 55.4389 | 0.0000 | 0.0000 |
| #32 | 57.4569 | 0.0000 | 0.0000 |
| #33 | 59.0582 | **32.2194** | **122.8393** |
| #34-35 | 60.4933 / 61.9222 | 0.0000 | 0.0000 |

✅ **天空画进了 colortex0 的待写代**，读数还跟着它自己那条黄昏曲线单调走
（48.04 → 61.92，与 `skyColor` 从 (0.514,0.620,1.000) 落到 (0.440,0.531,0.857) 同一段）。
⇒ h48l 那次「`c0@afterSky` 全 0」不是天空没画：那一轮观测面自报 `skyColor=(0.000,0.000,0.000)`
（夜空）。**没有这一行自报，这两件事在 RGB 数字上不可区分** —— 这就是观测面自报的价值。

### 17.8 h48n：双代同取把「测量说」否掉了 —— **黑帧是真的，而且是周期的**

h48n 把帧尾的槽 0/1 各取**两代**（`@readGen` / `@writeGen`）：

| 轮 | `c0@readGen` | `c0@writeGen` | `c1@readGen` | `c1@writeGen` | `main` |
|---|---|---|---|---|---|
| #25 | 17.0616 | 17.0616 | 115.5657 | 115.5657 | 115.5682 |
| #26 | **0.0000** | **0.0000** | **0.0000** | **0.0000** | **0.0000** |
| #27-28 | 17.0616 | 17.0616 | 115.5657 | 115.5657 | 115.5682 |
| #29 | **0.0000** | **0.0000** | **0.0000** | **0.0000** | **0.0000** |
| #30 | 17.0616 | 17.0616 | 115.5657 | 115.5657 | 115.5682 |

`main` 全序列：`0, N, N, 0, N, N, 0, N, N, 0, N, N, 0, N` —— **严格 3 轮一个零**。

两条结论：
1. 🔴 **「黑帧是取点打到空代」这条解释被否证**：黑帧轮里**两代同时为 0**，而两代在非黑轮
   逐位相同（静止世界 + 链每帧把两代都写满 ⇒ 本来就该相同）。⇒ **链的输出真的整帧为空**，
   ⚠️ 当时据此推的「连续 ≥2 帧为空」是**错的** —— h48o 逐帧数据否掉了（见 17.9）：
   槽 0 在**一帧之内**被 6 个 pass 写过 ⇒ 翻代 6 次（偶数）⇒ 两代都被同一帧写满 ⇒
   「两代同为零」只说明**这一帧**空，不说明连续两帧空。
   ⇒ 17.7 表里「`skyReadGen` 与 `main` 同步跳动」是同一件事的两个侧面，不是取点假象。
2. ✅ 观测面自报又一次直接改写了读法：这一轮 `skyColor=(0.000,0.000,0.000)`、当地时 15798
   ⇒ 天空本来就黑，所以本臂看不到天空亮度是**应该的**；而 `c0@afterSky` 的 area=FULL
   读数 2.1770（非 `allZero`）说明**基于 pass 的清屏落进去了**（alpha=1 那条通道有值），
   17.6 里「连清屏都不在」的怀疑也随之收掉——那是 `clearColorTexture` 的路径问题，
   改成附件清屏后不复现。
3. ⏳ 待测：`every=1` 逐帧取点（h48o），量黑帧的**真实周期**。观测到的「每 3 个采样轮一个零」
   在 `every=10` 下等价于「真实周期 ≈ 3 帧」的**混叠**（10 mod 3 = 1 ⇒ 采样相位每轮移 1）。
   3 帧周期是强信号：它指向某个**三深度环形资源**（原版动态 uniform 环 / 上传缓冲），
   而不是随机丢内容。逐帧数据到手才配定责。

## 十八、回读仪器会**自己造零**（源码级核实；这条直接影响「黑帧」是不是真的）

h48o 量到「严格每第 3 帧整帧为 0」之后，先别急着渲染层归因 —— 回读这条通道自己有一个
会产生周期零的机制。逐行核实 `com/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder.java`：

```
 60:  private final DestructionQueue<Destroyer> destroyQueue = new DestructionQueue<>(2, ...)
219:  signalSemaphore(submitSemaphore, currentSubmitIndex, ...)
222:  currentSubmitIndex++
223:  if (!awaitSubmitCompletion(currentSubmitIndex - 2L, 5s)) throw ...
229:  destroyQueue.rotate()
```

⇒ 两件事同时成立：
1. `copyTextureToBuffer(..., callback, ...)` 的「完成」回调是**随销毁队列在 CPU 侧被执行**的
   （深度 2，每 submit 轮一次），**不是** GPU fence 完成的回调；
2. GPU 侧「第 N 个 submit 已经完成」最早要到第 **N+2** 次 submit 才被 `awaitSubmitCompletion` 等到。

而 `TargetReadback` 的落地余量此前是 **1 拍**（`READ_DELAY_TICKS=1`），`every=1` 时一拍 = 一帧。
⇒ 余量 < 2 时，我们可能把一块 **GPU 还没写过的回读缓冲**映射出来读，而它的初始内容就是零。
「整帧全 0 `allZero=true`」与真实黑帧在日志里**逐字同形**，而周期恰与「2~3」同量级。

处理（不靠猜，做成一臂就能判的 A/B）：
- 余量改为可调 `mrt.pixelProbeReadDelay`（默认 **3**），注释里写清上面两段行号；
- 判据：**调 1** 若复现「每第 3 帧为 0」⇒ 那是仪器假象，GAP-020 重开时挂着的 3 帧周期解释当场了结；
  **调 3/4** 若黑帧消失 ⇒ 黑帧不再算渲染缺陷，剩余问题回到「画面内容对不对」那条线上。
- 顺带修正 GAP-020 重开行里我写的一句推断：「两代同为零 ⇒ 连续 ≥2 帧为空」是错的，
  槽 0 一帧内被 6 个 pass 写（翻代 6 次 = 偶数）⇒ 两代都被同一帧写满，单帧空就让两代同空。

🔖 通用形式：**用「CPU 侧轮队列」冒充「GPU 完成信号」的通道，都要先问一句
「我读的时候它真的写完了吗」**，并把余量做成可调，好让「仪器」与「被测物」能用一臂分开。

## 十九、按**资源所有权**排除一条候选：`CrossFrameResourcePool(3)` 解释不了本症状

h48p 的源码调研给出过一条很贴合「周期 3」的假设：原版帧图的瞬态纹理池
`CrossFrameResourcePool(3)`（`GameRenderer:127`）会在 3 帧内把一张物理纹理重新发给别的 pass，
而带零清屏的 descriptor（`RenderTargetDescriptor.java:24-33`）会先把它清成零。

**但它管不到这次量到的那些纹理**，理由是按归属而不是按印象：

| 黑帧里读为零的源 | 谁创建它 | 在不在帧图资源池里 |
|---|---|---|
| `colortex0` / `colortex1` / `colortex2` | 我方 `ColortexPool`（`device.createTexture`，usage=15，见其类注释） | ❌ 不在 |
| `main`（主目标） | 原版 `GameRenderer` 持有的 `mainRenderTarget()`（常驻 TextureTarget） | ❌ 不在 |
| 池深度 `vkdisp gbuffer depth` / 天空私有深度 | 我方 `TextureTarget` | ❌ 不在 |

⇒ 帧图池只回收**它自己 `createsInternal` 出来的**纹理（OIT / 中间附件那一类），
而我方两个 pass 都没调用 `createsInternal`（`onFrameGraphSetup` 里只有 `addPass` +
`disableCulling` + `executes`）⇒ 这条链碰不到上面任何一个被读为零的纹理。
⇒ **本条从候选里划掉**，不需要为它做臂。剩下的只有：
① mip/金字塔与代次错配（GAP-020 原机制）；
② 回读仪器造零（§十八，`mrt.pixelProbeReadDelay` 一臂可判）；
③ 真实的渲染丢帧（地形重放在某些帧不出内容，GAP-019 那条老线）。

🔖 顺带钉一条通用纪律：**归因之前先问「这个资源是谁分配的」**。
「周期 3」这个特征看着像谁，不代表那个人碰得到这块内存 —— 这条省下一整臂取证。

## 二十、鼠标取证通道建立后掉出的两条**工具层事实**（都会改变判读）

### 20.1 聊天注入会吃前导字符 ⇒「白天注入生效」这条自检此前是**假判据**

camera-pitch 那一步实测：`chat "/time set 6000"` 落到游戏里是 `ime set 6000`
（命令语法错 ⇒ **什么都没执行**）。而 `h48_flicker_capture.sh` 原来的观测面自检只看
「最亮一张 mean_luma ≥ 20」⇒ 只要启动时**恰好**是白天，它就打「白天注入生效」。
⇒ h48e～h48k 若干臂的「白天」前置是**未经证实的**（其中 `skyColor=(0,0,0)` 那一臂其实是夜空，
这条由进程内自报抓出来，见 §17.6/17.8）。

两处修法（都在 `tools/vulkan-local/`）：
- `x11_input.py chat`：开框后先打 6 个 `#` 再退格删掉 ⇒ 丢字符只会丢在垫子上，命令本体完整；
- `h48_flicker_capture.sh`：判「是不是白天」改读**进程内**的
  `[GAP-003/sky] 观测面自报: clockTime=…（当地时 N）`（`4000 ≤ N ≤ 12000` 才算白天），
  luma 降级为旁证；拿不到这一行就明说「本臂画面侧结论未定」，**不许**拿 luma 顶替时刻证据。

🔖 通用形式：**凡是「我先把观测面钉住了」的断言，必须由被测进程自己回报，不能由注入器的
退出码或一张截图的统计量代替**——注入器不知道自己有没有被执行，进程知道。

### 20.2 同一次运行里黑帧是**真的**（独立于回读通道）

同一取证实测（F2 截图，与探针无关的通道）：一次连拍里 **4/10 张 mean_luma ≈ 4.5**，
其余 71.6～94.7，且 `look` 前后 90.9～94.7% 像素变化、`+260` 可逆回到原画面。
⇒ 同一运行、同一时刻下的「隔几张全黑」不是仪器造出来的，**§十八那条只能解释一部分或根本不解释**。
⇒ h48q 的 `readDelay 1 vs 3` A/B 因此**更**有必要：它现在测的是「仪器贡献了多少」，
而「是不是真黑」已由截图通道独立成立。
另记一条真实故障模式：**键盘注入会在合成器抢走焦点后静默失效**（那次 10 分钟里 F2 与 `/say`
全部没落地，只有重启客户端才恢复）；鼠标相对位移不受焦点影响（`look` 因此可用）。

## 二十一、h48q：`readDelay 1 vs 3` A/B 出数了 —— 仪器贡献被量化，但**两臂的观测面根本不同**

同一份编译产物、同一条链、只差 `mrt.pixelProbeReadDelay`（取点 `main`，各取末尾 240 帧）：

| 臂 | readDelay | 空帧占比 | 空帧间隔分布 | 序列形态 | 非空帧 luma |
|---|---|---|---|---|---|
| d1 | 1 | **160/240 = 66.7%** | `{1:80, 2:79}` | `00N 00N 00N…` | min 46.76 / med 54.72 / max 55.20 |
| d3 | 3 | **80/240 = 33.3%** | `{3:79}` | `N0N N0N N0N…` | min 66.36 / med 82.88 / max 130.81 |

能定的结论（两条都只靠这张表）：

1. **仪器造的零 = 每个周期恰好多一个**。1→3 只把占空比从 2/3 压到 1/3，
   而 **周期始终是 3**，且 d3 的间隔分布是 `{3:79}`（除首尾外**全部**严格等于 3，没有第二档）。
   ⇒ §十八那条机制（`copyTextureToBuffer` 的回调走 `queueForDestroy`，CPU 侧排空 ≠ GPU 侧完成，
   完成只在 `submitIndex-2` 处被等）被**定量**确认：余量不足时每个周期多误报一个空帧。
2. **余量加到 3 仍然剩一个严格周期 3 的零**，而且它在**两种完全不同的画面内容**下都出现
   （见 21.1）⇒ 这一份不是仪器余量能解释的，**渲染侧确有每 3 帧一次的空**（与 §20.2 的 F2 独立通道同向）。
3. 但 `3` 同时**正好等于 `MappableRingBuffer.BUFFER_COUNT`** —— 所以「d3 剩下的那一个零」
   仍可能是同一台仪器的**边界档**（第 3 tick 回收 = 恰好在写回的那一格）。
   ⇒ 本表**不足以**宣布根因，只足以宣布「d1 的数字不能当证据用」。
   下一刀必须换**不经过这条环的通道**（F2 连拍已具备，本臂没落地是另一件事，见 21.2）。

### 21.1 🔴 两臂的观测面不同，所以**luma 不许跨臂比**

进程内自报（唯一可信的时刻证据，§20.1 立的规矩）：

- d1：`clockTime=69206（当地时 21206）… skyColor=(0.000, 0.000, 0.000) … rain=1.000 render#=900`
  → **夜里 + 下雨**；且 `clockTime` 在两次自报之间从 69206 走到 70285 ⇒ **`/time set` 与 `/gamerule doDaylightCycle false` 都没生效**。
- d3：`clockTime=74352（当地时 2352）… skyColor=(0.514, 0.620, 1.000) … rain=0.000` → **拂晓 + 晴**，
  同样在走（74352→75319）。

⇒ 表里那列 luma（54.7 vs 82.9）是**夜 vs 昼**的差，不是「部分完成」的差，**不许**拿去支持任何机制结论。
⇒ 顺带把 §20.1 的教训再钉一遍：驱动脚本打的 `chat sent: '/time set 6000'` 只证明**注入器发了**，
不证明**游戏执行了**；两臂都通过了「chat sent」这一关却都在夜里/在黎明，且都在推进。

### 21.2 本臂 F2 一张没落地

`h48q-d1-driver.log` 尾部：`❌ 本臂**没有截图**（F2 没落地 / 世界没进去）⇒ 无判据`，
`run/h27/screenshots/` 实测为空目录，而世界**确实进了**（探针有 1200 个 render）。
⇒ §20.2 记的那条「键盘注入在焦点被抢后静默失效」在本轮**复现**（鼠标 `look` 同期有效：
`observed_delta=(0,-260)` 与注入值逐字相符）。
⇒ 判据缺口按本项目规矩**明写为缺口**，不许用探针数字冒充截图判据，也不许把「空表」读成「画面正常」。

## 二十二、🔴 一条改变**所有 h45 之后臂的归因**的发现：持久化选项 store 的残留一直在改写 BSL

### 22.1 起因：文档里两条互斥的说法

`docs/13-GAP-REGISTRY.md` 一处写「BSL 默认只写 colortex0」，另一处用
「`ADVANCED_MATERIALS` 默认为真」来解释运行日志里的 `colorTargets=8`。
而 BSL `shaders/lib/settings.glsl:69` 逐字是 `//#define ADVANCED_MATERIALS`（**注释掉**）。
两条不可能同时成立 ⇒ 去查我方预处理器有没有把注释掉的 `#define` 复活。

### 22.2 预处理器是**清白**的（两条都实测）

- `DefineProcessor.process` 只在 `line.strip().startsWith("#")` 时进指令分支
  （`src/main/java/dev/vkdisp/glsl/preprocess/DefineProcessor.java:87-88`）
  ⇒ `//#define …` 首字符是 `/`，走 else 分支**原样透传**给 GLSL 当注释 —— 复活不了。
- 生产链路实测：`TerrainProductionOutputCountTest`（真包 + 生产同款 include→define→translate）
  断言 BSL 默认档地形片元**只有 1 个颜色输出**（`maxLoc + 1 == 1`），本轮跑到 **绿**（`EXIT=0`）。
  同文件另测把「能力上限 5 槽」与「生产 1 槽」明确分成两个事实。

⇒ 单测是对的，**跑起来却是 8** ⇒ 差别只能在单测没有、运行期才有的东西上：本地状态。

### 22.3 真机制（逐字日志为证）

h48q d1 臂（`/tmp/h48q-d1-lane.log`）里同时存在三行，把它们串起来就是全部答案：

```
option name=ADVANCED_MATERIALS type=BOOLEAN default=false values=[true, false]   ← 我方扫包结果是对的
选项覆盖已改写进源: 命中 3/3 [PARALLAX=false, ADVANCED_MATERIALS=true, SHARPEN=3] ← 但它被改写进了源
[GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain
          colorTargets=8 declaredOutputSlots=[0, 3, 6, 7] … unwrittenAttachments=[1, 2, 4, 5]
```

残留就在**本地状态文件**（gitignore 的 `run/`，不是仓库内容）：
`run/config/vkdisp-pack-options.properties` 与 `run/h27/config/vkdisp-pack-options.properties`
各自写着 `BSL_v10.1.8.ADVANCED_MATERIALS=true`（后者还有 `PARALLAX=false`、`SHARPEN=3`；
mtime 分别是 2026-10-04 12:51 与 2026-10-05 20:23 —— 与 h45 那条取证线对得上）。

**为什么一直没被发现**（这条比结论重要）：

- `PackCapabilityGate` 设计上**只在内存里**关依赖缺失素材的特性，明确「`PackOptionStore` 一个字节都不碰」
  （`PackCapabilityGate.java:46-47`）⇒ 门控不会替我清掉残留；
- 取证脚本为了让臂之间保持**单变量**，显式传 `pack.capabilityGate=false pack.optionOverrides=""`
  （`tools/vulkan-local/h45_arm.sh:32`、`h47_chain_arm.sh:36`）——
  而 `optionOverrides=""` 清的是**配置档覆盖**，**不是 store**；
- 两件事叠起来 ⇒ 门控不干预 + 覆盖表为空 + store 仍带 `ADVANCED_MATERIALS=true`
  ⇒ `diffAgainstDefaults` 把它当成「与默认不同」照常注入 ⇒ **命中 3/3**。
- 运行期日志确实打了「命中 3/3 … ADVANCED_MATERIALS=true」（第 603 行），
  而且 `PixelProbePlan`/`PackOptionEvidence` 每行都带 `effective={…}` ——
  **信息一直在，只是没人读那一行**。⇒ 加判据不解决问题，**读判据**才解决问题。

### 22.4 影响范围（必须按「撤回」处理，不是按「补一句」处理）

1. **h45 之后所有 BSL 臂的编译配置都不是包默认**，而是「ADVANCED_MATERIALS=on / PARALLAX=off(仅 h27 档) / SHARPEN=3」。
   ⇒ 那些臂里 `colorTargets=8`、`unwrittenAttachments=[1,2,4,5]`、`varyings=15`、`samplers=7`
   全部是**这一档**的数字，**不是**「BSL 默认档」的数字。
2. 更要紧：GAP-009 的实测结论正是「`ADVANCED_MATERIALS` 打开 ⇒ 地形 albedo 被压成**恰好 0**
   （纯黑剪影，主目标地形区 luma `0.0000`）」，依据 `evidence/h31`（关掉后 0.0000 → 96.1485）。
   ⇒ 本轮这批「黑」的证据里，**有一部分是这条已知故障模式在被动复现**，
      用它推「空帧周期」的机制**不成立**（§二十一那张表的**周期**仍可用，
      因为它与内容无关：d1 在夜+雨、d3 在拂晓+晴，周期都是 3 —— 见 21.1）。
3. `MrtPlan.java:61-79` 那段「h45 第二次更正」把这件事记对了（包默认 false；8 槽是 store 残留），
   本轮**没有推翻它**，只是补上它缺的两环：
   残留**为什么**能穿过 `optionOverrides=""` 与 `capabilityGate=false`，以及它**至今仍在**生效。

### 22.5 本轮做的处置（都可逆）

- 备份：`/tmp/vkdisp-store-backup/main.properties`、`/tmp/vkdisp-store-backup/h27.properties`
  （原文件本就 untracked，`git check-ignore` 指到 `.gitignore:68 run/`）。
- 从两个 store 里删掉 `BSL_v10.1.8.*` 三行，保留 `vkdisp-fixture-dir.SHADOW_DARKNESS=0.20`
  （别的包、与本轮无关）⇒ 现在 BSL 走**真·包默认**。
- 重开一臂（`/tmp/h48r_clean_arm.sh`：清残留 + `pack.capabilityGate=true` + `readDelay=3`），
  判据先看进程内自报「有没有命中 / 门控关了哪几项」，再看周期 3 是否还在。

### 22.6 🔖 结构性缺口（登记，不顺手实现）

「**取证期的状态泄漏进产品路径**」这一类缺陷，目前**没有任何闸门**：
store 里残留什么，下一臂就默默带什么跑。候选修法（下一条要有实测口径再定）：
包加载时若 store 中存在**与该包默认值不同**的键，打一条 **WARN 列出键名与来源**，
让「我测的到底是哪一档」在**每次运行**都成为一条必须读的行，而不是等人事后 grep。

## 二十三、🟢 h48r：清掉残留 + 门控开 ⇒ **空帧归零**（周期 3 是那一档的性质，不是链的性质）

一臂（`/tmp/h48r_clean_arm.sh`：store 已清 BSL 键 + `pack.capabilityGate=true` + `readDelay=3`，
其余与 §二十一 d3 臂**逐字相同**）。

### 23.1 先证明「被测对象确实换了」——三条进程内自报

```
选项覆盖已改写进源: 命中 8/8 [PARALLAX=false, REFLECTION_RAIN=false, REFLECTION_SPECULAR=false,
                              REFLECTION_ROUGH=false, SELF_SHADOW=false, SSS=false,
                              NORMAL_DAMPENING=false, NORMAL_PLANTS=false]
[GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain
          colorTargets=1 declaredOutputSlots=[0] samplers=5 varyings=9 unwrittenAttachments=[]
观测面自报: clockTime=6675（当地时 6675）, skyColor=(0.514, 0.620, 1.000) … rain=0.000 render#=1200
```

- **列表里没有 `ADVANCED_MATERIALS`** ⇒ 它本来就是 false（门控只关「当前为真」的），
  即 §22.5 的清残留**确实生效**；这一条是「被测对象换成了真·包默认」的直接证据。
- 门控按设计把整条依赖闭包 8 项在内存里关掉（不写用户文件）。
- 🔖 **运行期与单测第一次对上**：`colorTargets=1 / declaredOutputSlots=[0] / samplers=5 / varyings=9`
  与 `TerrainProductionOutputCountTest` 断言的生产值**逐项相同**。
  ⇒ §二十二那条「单测绿、跑起来 8」的裂缝闭合：从来不是预处理器分歧，**只是两臂带的 store 不同**。

### 23.2 数字（同一条链、同一台仪器，只换包配置）

| 臂 | 包配置 | 附件 | 空帧 | 间隔分布 | 非空帧 luma |
|---|---|---|---|---|---|
| h48q d3 | store 残留 `ADVANCED_MATERIALS=true` + 门控**关** | 8（写 `[0,3,6,7]`） | **80/240 = 33.3%** | `{3:79}` | med 82.88 |
| **h48r** | **真·包默认** + 门控**开** | **1（写 `[0]`）** | **0/240** | — | 全程有内容 |

⇒ **周期 3 的空帧在这换了一档之后完全消失**（240 帧连续取点，一个 `0` 都没有）。

### 23.3 能定与不能定

🟢 **能定**：
1. 「空帧」**不是**回读仪器/`MappableRingBuffer(3)` 造的 —— 那一档 240 帧全有内容，
   仪器与环与上一臂完全同款。⇒ §18/§21 那条「仪器会自己造零」**只在余量不足时**成立
   （d1 每周期多一个零），**周期 3 的主体是渲染侧的**。
2. 「空帧」与 **8 附件 / 多槽 MRT 形态**强相关（写 `[0,3,6,7]`、`1/2/4/5` 无输出、
   双代次 ping-pong 的槽数从 1 变 8），与「包内容是否为黑」弱相关（那一档 luma 中位 82.88，有画面）。
3. 🔖 **文档侧的 8/1 矛盾到此彻底了结**（§22.1 起的那条）：包默认 = 1 槽，残留档 = 8 槽，
   `MrtPlan` 的 h45 更正与 `TerrainProductionOutputCountTest` 都对。

🔴 **不能定（登记为开放，别顺手当结论用）**：
1. **8 附件那一档为什么每 3 帧空一次**，机制仍未定位。已知：与槽数相关、与代次 ping-pong 相关，
   未知：是 `GAP-018`（同槽既是附件又是采样器）在多槽下的兑现，还是清屏/LOAD 语义在多槽下的组合。
   ⇒ 这一档**用户是能进去的**（`ADVANCED_MATERIALS` 是包内合法开关，且我方门控默认**关**），
      所以 GAP-020 **不因此关闭**，只**收窄**成「多槽档专属」。
2. `pack.capabilityGate` 的代码默认是 **`false`**（`VkDispConfig.java:356`），
   而「默认关 ⇒ 包内 9 项依赖缺失素材的特性保持为真」正是产生坏画面的那一档。
   ⇒ 默认值该不该翻成 `true` 是**产品决策**，要 h48s 那一臂（清残留 + 门控**关**）的数字才能定，
      不能靠本轮「门控开就正常」直接推。

### 23.4 顺带：观测面钉死这一步**仍然没成**

`clockTime` 两次自报之间从 6675 走到 7423 ⇒ `/gamerule doDaylightCycle false` 依旧没执行。
本臂能出白天只是因为**开机时恰好是白天**，不是注入成功。
⇒ §20.1 的修法（改读进程内自报的「当地时」）只把**判读**修对了，**注入本身还没修**；
   驱动脚本那句 `⇒ 白天注入生效` 现在读的是自报时刻（合格），但它挡不住「时刻在走」这件事
   ⇒ 下一轮要么修键盘注入，要么把「时刻必须静止」写进臂的失败判据。

## 二十四、h48s：再补一臂把**门控**这个变量也切掉 ⇒ 空帧的唯一自变量是**那一档包配置**

h48s 臂 = h48r 的**全部设置**，只把 `pack.capabilityGate` 从 `true` 改回 **`false`**
（= 我方代码默认值，`VkDispConfig.java:356`，也正是**用户客户端所在的那一档**）：

| 臂 | store 残留 | 门控 | 包契约自报 | 空帧 / 240 | 非空帧 luma |
|---|---|---|---|---|---|
| h48q d3 | **有** `ADVANCED_MATERIALS=true` | 关 | `colorTargets=8 slots=[0,3,6,7] unwritten=[1,2,4,5]` | **80（33.3%）** 间隔恒 `{3:79}` | med 82.88 |
| h48r | 清 | **开**（关掉闭包 8 项） | `colorTargets=1 slots=[0] unwritten=[]` | **0** | （本臂未打） |
| **h48s** | 清 | **关** | `colorTargets=1 slots=[0] samplers=5 varyings=9 unwritten=[]` | **0** | min 62.74 / **med 143.21** / max 150.16 |

h48s 的配置自报（证明档位确实换了）：
```
WARN  选项 [CAPABILITY_GATE_OFF] 能力门控已由配置关闭（pack.capabilityGate=false）⇒ …保持包内原值
INFO  [GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain
      colorTargets=1 declaredOutputSlots=[0] samplers=5 varyings=9 unwrittenAttachments=[]
观测面自报: clockTime=6555（当地时 6555）, skyColor=(0.514, 0.620, 1.000) … rain=0.000 render#=1200
```

### 24.1 三条能定的结论

1. **空帧的唯一自变量是「哪一档包配置」，不是门控**：门控开与关两臂**都是 0 空帧**，
   而与它们只差残留的那一臂是 **每 3 帧空一次**。⇒ §23.3 里我留的那条「默认值该不该翻成 true」
   **与本症状无关**，本轮**不动** `pack.capabilityGate` 的默认值（不无依据地改产品行为）。
2. **GAP-009 的「黑」在这档不成立**：门控**关**着（`PARALLAX`/`SSS`/`REFLECTION_*` 全按包默认为真）
   而地形照样有画面，luma 中位 **143.21**（比残留档的 82.88 还亮）。
   ⇒ GAP-009 那条「albedo 被压成恰好 0」的成因**绑的是 `ADVANCED_MATERIALS` 这一项**，
      不是门控闭包整体 —— 登记表里这两件事此前混写在一起，应分列。
3. **用户看到的黑白闪屏，最可能的解释就是这条残留**：用户客户端走的正是 `run/config` 车道，
   它的 store 与 toml 与本轮清掉/复现的那一档逐字相同（`ADVANCED_MATERIALS=true` + 门控关）。
   ⇒ 残留已清（备份在 `/tmp/vkdisp-store-backup/`），**下一个人眼检查应当不再闪屏**；
      但这仍是**同一台仪器**给的结论 ⇒ 按本项目纪律，**必须由人眼或 F2 独立通道再判一次**才算收口
      （本轮 F2 又没落地：`run/h27/screenshots/` 空，键盘注入在焦点被抢后失效这条复现了第三轮）。

### 24.2 仍然开放的那一半（不要因为它消失了就当已修）

「**8 附件那档为什么每 3 帧空一次**」机制仍未定位 —— 本轮只是把触发条件钉清楚了：
写 `[0,3,6,7]`、附件 `1/2/4/5` 无输出、双代 ping-pong 的槽数从 1 变 8 时才会出现。
`ADVANCED_MATERIALS` 是包内**合法开关**（用户开了就该能用）⇒ **GAP-020 不收口**，
候选收窄到两条：① GAP-018（同槽既附件又采样器）在多槽下的兑现；② 未写槽的清屏/LOAD 语义 × 双代轮转。
下一刀：在**残留档**（手工把 `ADVANCED_MATERIALS=true` 塞回 `pack.optionOverrides`，
这次**显式**而不是残留）跑只差 `mrt.attachments=1 vs 8` 的 A/B —— 若 1 附件也出周期 3，
则是链的问题；若只有 8 附件出，就是多槽形态的问题。

## 二十五、🟢 h48t 的 **10 张 F2 全部落地** —— 独立通道确认「真默认档不闪屏」，并且第一次**看见**了画面

§二十四留了一条老实话：「0 空帧仍是同一台仪器给的结论，必须由人眼/F2 再判一次」。
这一臂补上了。**同一臂**（`mrt.pixelProbe=false`，纯加载 + F2）10 张全在
`run/h27/screenshots/`（已入库 `evidence/h48-images/h48t-default-1..10.png`）：

| # | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| `mean_luma` | 97.33 | 96.22 | 99.74 | **108.85** | 98.47 | 98.55 | 98.59 | 98.63 | 80.87 | 80.62 |

⇒ **10/10 都有画面**（§20.2 那批「黑帧」是 `mean_luma ≈ 4.5`，这里最低 80.62）
⇒ **回读仪器与 F2 两条独立通道第一次给出同一个答案**：真默认档**没有黑白闪屏**。
🔖 顺带纠正 §24.3 的一句：「F2 连续第三轮没落地」只对 **h48q 的 d1 臂**成立；
h48t 十张全落地。**为什么同一套脚本一次全中、一次全不中，本轮没有查** ⇒ 这是一条**未定的工具层事实**，
不许因为「这次中了」就当它稳定（下次再全不中时，别把「没图」读成「没画面」—— 那是本项目反复踩的坑）。

### 25.1 看见的东西（判读按图，不按数字）

`h48t-default-4.png`（抬头看天，luma 108.85）与 `h48t-default-9.png`（80.87）逐张看过：

🟢 **成立的部分**
- **天空真的在画面里并且是渐变蓝**，带**太阳本体**（一个亮块）与其周围的泛白辉光
  ⇒ GAP-003 天空线（先铺后盖 + 逐帧 `repoint`）**第一次拿到画面侧证据**，不再只有探针数字。
- **树叶有正确的镂空轮廓与深浅变化**、树干有法线朝向差 ⇒ 地形走了包的片元。
- **HUD / 快捷栏 / 手上物品正常**（原版在链之后画，与 §GAP-011 用户描述的形状一致）。
- **没有绿天空**（§h27b 那条「诊断清屏色泄漏」未复现）、**没有白屏**（GAP-017 族未复现）。

🔴 **看得见的问题（本轮只登记，不顺手修）**
1. **云没有**：抬头这片天上没有任何云层 ⇒ BSL `gbuffers_water`/`gbuffers_clouds`（实体/水/云）
   这几条 `gbuffers_*` **仍未进 colortex**（GAP-015 未完成部分）。
2. **树叶整体偏暗到接近黑**：背光是合理的，但 `#9` 那张暗部几乎没有层次
   ⇒ 与 **shadow 那条路没接**（`shadowtex0/1` 目前绑本 pass 深度占位，GAP-003 ③）方向一致，
      但**本轮没有判据把「该暗」与「少了一层阴影贴图」分开** ⇒ 不许当结论。
3. **地面不在画面里**：这一臂按判据要求抬了头 ⇒ **地形的水平视角还没看过**。
   下一臂必须**不抬头**（或只抬一半）再拍一轮，才能判「地形/光照是否正确」而不是「天空是否正确」。
4. 太阳是**硬边方块**而不是包设计的柔和日盘 ⇒ 可能是 `sun.png`/`customImages` 那条纹理没生效，
   **未核实**（X9：这里只记形状，不记成因）。

### 25.2 这一节改变了什么

- §二十四的「0 空帧」从**单通道**升级为**双通道一致** ⇒ 「黑白闪屏在真默认档消失」可以作为**已判**的结论用。
- 但**「BSL 正常生效」远未成立**：本轮第一次看清的是「天空对、云没有、阴影层没接、地面没看」
  ⇒ 下一线的重点从「修闪屏」转到 **GAP-015（把实体/水/云/shadow 这几条 `gbuffers_*` 接进 colortex）**。
