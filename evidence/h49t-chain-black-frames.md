# h49t → h49v · 「生产画面整帧黑」定位到**具体一级**：`deferred1` 的 AO 路径，周期严格 3

> 这条是 GAP-011 / GAP-019 / GAP-020 追了十几轮的症状（黑白闪屏 / 周期性空帧 / 整帧黑）。
> 本轮不修，只把它**钉死到一级 + 一个周期 + 一个可复现开关**，并否证「仪器」这一支。
> 全部取证都是现成观测面（`mrt.postChainTrace` + 像素探针），**零主源码改动**。

## 〇、环境（X53）

同 `evidence/h49-water-translucent-draw.md` §〇：lavapipe（CPU 软件 Vulkan）/ 854×480 /
`BSL_v10.1.8` 默认档 / 无 validation layer。起跑前 `game_procs.sh count` = 0。

## 一、方法：让产品自己逐级报数

配置（三臂只差一项）：`mrt.pixelProbe=true`、`pixelProbeEvery=1`、
`mrt.postChainTrace=true`、`mrt.postChainTraceSlots=0`、`mrt.enabled=false`（生产视图）、
`mrt.packWater=false`、`mrt.cloudsPass=false`、`mrt.depthGlProxy=false`。
标签 `traceK<program>:c0` = 链上第 K 个**写了槽 0** 的 pass 跑完后的 colortex0 读数。

## 二、h49t（基线）：黑帧不是「链把内容打没」这么简单 —— 它在**链的第一级**就已经是 0

| 帧 # | `c0@afterTerrain`（进链前） | `trace1deferred1:c0`（第一级输出） | 帧尾 `main` |
|---|---|---|---|
| 2 | 160.0003 | 160.0003 | 213.2457 |
| 3 | 144.0299 | 143.5721 | 195.5366 |
| **4** | **47.5122** | **0.0000** | **0.0000** |
| 5 | 35.4352 | 35.2855 | 52.6186 |
| 6 | 44.3241 | 43.9012 | 69.2794 |
| **7** | **44.3241** | **0.0000** | **0.0000** |
| 8… | 35.4352 / 44.3241 交替 | 跟随 | 跟随 |
| **10、13、16…** | 有内容 | **0.0000** | **0.0000** |

统计：**62 / 185 帧** 的第一级输出为 0，且 bad 帧间隔 = `[3,3,3,3,3,3,3,3,3]` —— **严格周期 3**。
🔴 关键读法：**`c0@afterTerrain` 在那些帧是有内容的** ⇒ 内容不是被「链中段」打没的，
而是**链的第一级 `deferred1` 自己输出 0**，帧尾 `main` 只是忠实地把它带到屏幕。
（GAP-019 当年定的「责任侧在链」仍然成立，但**级别**比当时以为的靠前得多。）

## 三、h49u：否证「仪器」这一支（`pixelProbeReadDelay` 3 → 1）

周期 3 太像「回读落地余量」（默认值就是 3）⇒ 先测仪器：把 `mrt.pixelProbeReadDelay` 改成 1。

| 指标 | h49t（delay=3） | h49u（delay=1） |
|---|---|---|
| 第一级输出为 0 的帧 | 62 / 185 | **62 / 185** |
| bad 帧间隔 | `[3,3,3,…]` | `[3,3,3,…]` |
| 具体读数 | `160.0003 / 143.5721 / 35.2855 / 43.9012` | **逐位相同** |

⇒ **不是仪器**（改余量对图案零影响，数值逐位不变）⇒ 是**产品**行为。
🔖 这一条值得单独留着：本仓此前有过「每 3 帧空一次」被怀疑成回读落地的轮次
（`evidence/h48` §二十一~二十四、`pixelProbeReadDelay` 的来历就是那次）。

## 四、h49v：一个包选项就把凶手叫出来 —— **AO**

`pack.optionOverrides=AO=false`（日志确认生效：`选项覆盖已改写进源: 命中 1/1 [AO=false]`）：

| 指标 | h49t（AO 开 = 包默认档） | h49v（AO=false） |
|---|---|---|
| 第一级输出为 0 的帧 | **62 / 185** | **1 / 179**（就是 #1 预热帧） |
| bad 帧间隔 | `[3,3,3,…]` | 无（凑不出间隔） |

⇒ 黑帧由 `deferred1` 里的 **AO 计算**产生，且**关掉 AO 就完全没有**。

### 源码侧的机制候选（本轮**未判**，但形状已经找到）

`shaders/lib/lighting/ambientOcclusion.glsl`（逐行取自 `BSL_v10.1.8.zip`）：

```glsl
52  float ao = 0.0;
55  if (z >= 1.0) return 1.0;
60  float linZ = GetLinearDepth(z, projectionInverse);
64/66  dither = fract(dither + frameCounter * 0.618);   // 或 * 0.5
```

⇒ `ao` 的**起点是 0.0**，唯一的「安全出口」是 `z >= 1.0` 提前返回 1.0。
而我方 gbuffer 深度是**反向 Z**（近 = 1.0、远/天空 = 0.0，X34 已源码级核实），
`z >= 1.0` 只在贴脸像素成立 ⇒ 绝大多数像素走的是「拿 `GetLinearDepth(z, projectionInverse)`
反解距离再累加」那条路，而 `projectionInverse` 此刻喂的是**引擎口径的逆矩阵**
（GAP-022 未修的那一半）。**累加一旦全落空，`ao` 就保持 0.0 ⇒ `deferred1` 写黑。**

⚠️ 但「为什么恰好每 3 帧一次」这一层本轮**没判** —— 三个候选按代价排：
① `MappableRingBuffer` 深度恰好是 3（`BUFFER_COUNT=3`）⇒ 与「1/3 帧」同量级，
   要查的是 `deferredBuiltins` 的写/绑/rotate 相位（本轮已初步排除：写与 rotate 的守卫对称）；
② `frameCounter` 我方是 `++` 每次 `gather()`，而 `gather()` **一帧内可被调多次**
   （h48z 已核实最多 4 次）⇒ `frameCounter` 每帧跳 2~4，`* 0.5` 的 `fract` 会踩到 2 的周期、
   `* 0.618` 则近似不规律 —— 这条要配「`frameCounter` 每帧恰好 +1」的修法一起测；
③ 金字塔/代次重建的节奏（GAP-017 / GAP-018）。

## 五、这一格与 GAP-022 是同一个洞（这条结论的实践意义）

GAP-022 的关闭条件写的是「深度与矩阵**按程序族分别**供值后，画面侧判据成立」。
本轮给出的正是它**最硬的一条动机**：不是「效果不够好」，而是
**包默认档下 AO 会把整帧周期性打黑**。
⇒ 下一刀应该做 GAP-022 的那一半（`gbuffers_*` 留引擎口径、`composite*/deferred*` 给 GL 口径），
而不是继续在 AO 周围加诊断。

## 六、本轮**不覆盖**什么

1. **没修任何东西**（零主源码改动）；周期 3 的**来处**未判（§四 三个候选）。
2. AO 关掉之后画面是否「对」未判（本臂只测「第一级输出是否为 0」）。
3. `mrt.depthGlProxy=true`（半翻档）下的行为**没测** —— 登记表明令禁止单独开它，本轮照办。
4. 云/水/天空的画面判据仍受这条黑帧压制：**它修好之前，画面侧结论都带这个混杂量。**

---

## 七、h49x：两臂只差 `mrt.depthGlProxy`（**且都开着 `cloudsPass`**）⇒ 拿到两条新事实

车具 `/tmp/opencode/h49x_pictures.sh`：观测面先钉住（`advance_time false` + `time set 6000` +
`weather clear`，全部有 `[CHAT]` 回执），抬头拍一张、回平视再拍一张。

| 指标 | OFF（`depthGlProxy=false`） | ON（`depthGlProxy=true`） |
|---|---|---|
| `trace1deferred1:c0` 输出为 0 的帧 | **112 / 334 ≈ 1/3**（与 h49t 的 62/185 同量级 ⇒ 周期 3 **复现**） | **161 / 242 ≈ 2/3** ⇒ **更糟** |
| `main` 前 12 帧 | `0, 213.45, 213.45, 0, 53.01, 69.6, 0, …` | `159.58, 0, 0, 110.02, 0, 0, 109.84, 0, …` |
| `[GAP-022]` 口径自报 | 只有 `ENGINE` | **两条都有**：`ENGINE`（`gbuffers_*` 那一族）+ `GL (depthtex=1-z, gbufferProjection=D2·P…)`（链） ⇒ **按族供值在日志里第一次看得见** |

### 🔴 事实 1：`cloudsPass` 一开，黑帧从 1/3 变 2/3 —— 云那一格与链**互相干扰**

h49w（`depthGlProxy=true`、**`cloudsPass=false`**）量到的是 **1/177**；
本轮同开关但 `cloudsPass=true` ⇒ **161/242**。
⇒ GAP-022 的按族供值**确实把黑帧压住了**，但那个结论只在**云关着**的配置下成立；
云一开就出现一个**新的、更严重的**周期性黑帧源。
⚠️ 本轮**没有**判它的机制（候选：云 pass 写 colortex0 的混合/alpha、云 pass 对 gbuffer 深度附件的
LOAD-结束操作、云写进的那一代与链读的那一代的相位）。**别把「云修好了」写进任何结论。**

### 🔴 事实 2：云**进画面了**（此前完全没有），但渲染是错的

两张图（`evidence/h49-images/h49x-clouds-off-glproxy.png`、`...-on-glproxy.png`）：
- 左上一角能看到**白色块状云**（OFF 臂尤其清楚）⇒ 「天上没有任何云」这条症状（h48t 画面判读）
  第一次被搬进画面；
- 但画面右上一大片是**暗绿色斑驳的「布」**，ON 臂里还多一块**亮白矩形** ⇒ 这不是云该有的样子，
  是「有一层几何被贴上/算错了东西」。两臂都有 ⇒ **不是 `depthGlProxy` 造成的**，是云那一格自己的。

### 因此本轮的判定边界（写清楚，别越）

1. `mrt.depthGlProxy` **默认仍 false**：看图判据（`isSky` 认对天空 / 光柱镜斑位置 / SSR 不再拿垃圾
   `viewPos`）**没通过** —— 画面被上面那片错误的「布」和新的黑帧节奏污染，无从判那三条。
2. `mrt.cloudsPass` **默认仍 false**：它把云搬进画面了，但同时(a)渲染不对、(b)与链互相干扰。
3. h49w 那条「按族供值压住周期 3 黑帧」的结论**要加限定词**：`cloudsPass=false` 时成立。

---

## 八、h49z + h50a：把「云 vs AO」判开 —— 一个 2×2，外加**撤回我上一条的说法**

h49x 那句「云一开，黑帧从 1/3 涨到 2/3 ⇒ 云与链互相干扰」是**归因归错了**。
补两臂（同一套逐级观测面：`c0@afterTerrain` / `c0@afterClouds` / `trace1deferred1:c0` / `main`）
之后，四个配置正好拼成一个 2×2：

| `deferred1` 输出为 0 的帧占比 | `depthGlProxy=false` | `depthGlProxy=true`（按族供值） |
|---|---|---|
| **云关**（h49t / h49w） | 62/185 = **33%** | 1/177 = **0.6%** ✅ 修好了 |
| **云开**（h49z / h50a） | 54/81 = **67%** | 52/78 = **67%** ❌ 修法失效 |

### 🔴 事实 A：云**没有**把颜色缓冲弄黑（撤回「云与链互相干扰」那句）

h49z 的分类计数（四信号齐全的 81 帧）逐字：

```
云把gbuffer弄黑              0
gbuffer有内容·AO弄黑          54
AO有内容·链后段弄黑            0
全正常                        26
gbuffer本来就黑               1
```

而 h50a 里 **77/78 帧 `c0@afterClouds > c0@afterTerrain`**（`131.594 → 135.040`、
`86.836 → 87.008`）⇒ 云那一格**每一帧都在往 colortex0 加内容**，
黑帧**全部**发生在 `deferred1`（AO）里，链后段一帧都没弄黑过。
⇒ 所以「云与链互相干扰」这个措辞**不对**：不是互相干扰，是**云让 AO 的那条老路更常走到黑**。

### 🔑 事实 B：新的机制假设（**未判**，但形状很具体）

`CloudRenderer.render` 用的 `RenderPipelines.CLOUDS` 继承
`DepthStencilState.DEFAULT = (GREATER_THAN_OR_EQUAL, **writeDepth=true**)`
（`renderpearl/api/pipeline/DepthStencilState.java` 逐字），
而我方云 pass 挂的是**同一张 gbuffer 深度附件**（`MrtTerrainPass.depthView()`，LOAD）
⇒ **云的深度被写进 `depthtex` 读的那张图**。
AO 那边（`ambientOcclusion.glsl:60 GetLinearDepth(z, projectionInverse)`）
一旦读到的是云层的深度而不是地形深度，累加就会全落空 ⇒ `float ao = 0.0` 保持 0 ⇒ 写黑。
这也解释了为什么「按族供值」在云开着时**不再够用**：
它修的是**矩阵口径**，而这里坏的是**深度内容本身**。

### 下一刀（按代价排，本轮**没做**）

1. **让云不写 gbuffer 深度**：云 pass 改挂一张**私有空白深度**（与 `SkyIntoGbuffer#ensureSkyDepth`
   同族），或给云一条 `writeDepth=false` 的派生管线（管线替换那条路本轮已走通，加一项状态即可）。
   判据现成：同一套四信号，看「云开 + glProxy=true」那一格能不能从 67% 掉回 ~0。
2. 若 1 成立，还要顺手判**云该不该被地形遮挡**（挂私有空白深度 = 云永远盖在地形上，
   那是另一种错；正解可能是「派生管线只关写入、保留测试」）。
3. 云那片「暗绿的布」仍未判 —— 它和这条黑帧可能是**同一个根**（AO 把整片乘成暗色），
   也可能是两件事。1 做完再看图才知道。

## 九、h50b：🔴 §八 那条「云写了 gbuffer 深度」的假设**被否证**

实现完 `mrt.cloudsNoDepthWrite`（派生云管线只关深度**写入**、保留测试）后，
重跑 §八 表格里最坏那一格（云开 + `depthGlProxy=true`）：

| 臂 | 云写深度? | `deferred1` 输出为 0 的帧 |
|---|---|---|
| h50a | 写（默认） | 52 / 78 = **67%** |
| **h50b** | **不写** | **50 / 76 = 66%** |

⇒ 在观测误差内**完全没变** ⇒ 「云的深度污染了 AO 读的 `depthtex`」**不是**这条 67% 黑帧的原因。
（顺手排除掉的是一整类：`c0@afterClouds` 与 `c0@afterTerrain` 的差依旧只有零点几到几，
云对颜色缓冲的贡献仍是「加内容」而不是「弄黑」。）

⇒ 处置：**默认值退回 `false`** —— 不拿一个没被证实的东西去改默认行为；
代码留着当对照诊断项。

### 那么 §八 的 2×2 还剩下什么形状？（本轮的诚实边界）

| `deferred1` 黑帧占比 | `depthGlProxy=false` | `depthGlProxy=true` |
|---|---|---|
| 云关 | 33% (h49t) | **0.6%** (h49w) ✅ |
| 云开 | 67% (h49z) | 67% (h50a) / **66%** (h50b，云不写深度) ❌ |

已排除的原因：云写颜色（0 帧）、云写深度（h50b 否证）、链后段（分类计数 0 帧）、
回读仪器（h49u 逐位相同）、矩阵口径（本行右上格已修好，右下格却不动）。
**剩下的共同点是「多了一个写 colortex0 的 pass」这件事本身**，
而它为什么能把 AO 从 1/3 推到 2/3 —— 本轮**没判**。
下一刀的两个候选（按代价排）：
① **uniform 环的在飞相位**：`MappableRingBuffer` 深度 3 + 原版允许 3 个 submit 在飞
  （`VulkanCommandEncoder:222-223`）⇒ 多一个 pass 会改每帧的提交次数，
  而 `deferredBuiltins` 每帧只写一次 —— 切法：把 `deferred` 那一步的块环深度临时调到 6 跑一臂；
② **AO 自己按 `frameCounter` 取 dither**（`ambientOcclusion.glsl:64/66`），
  而我方 `frameCounter` 是「每次 `gather()` +1」而非「每帧 +1」（h48z 已核实 `gather` 一帧可被调多次）
  ⇒ 多一个 pass 会改变 `gather` 的调用次数分布。切法：把 `frameCounter` 改成每帧恰好 +1 再跑同一格。

