# h50m · GAP-023 第一格：`depthtex0` 从「活深度」变成**不可变快照**，并且证明这一换是**逐位无损**的

> 起点：GAP-023 登记的是「`depthtex0/1/2` 三个名字绑同一张深度视图 ⇒ 包里所有
> 「比较两个深度层」的逻辑恒等失效」（直接受害者逐字：BSL `composite.glsl:333 z1 > z0` 恒假；
> 而 `gbuffers_water` 的自由 sampler 清单里就有 `depthtex1` ⇒ 这是接水的**前置**）。
> 本轮落的是**第一格**，并且把「这一格买到什么、没买到什么」写清楚。

## 〇、环境（X53）与臂形状

lavapipe（CPU）/ 854×480 / `BSL_v10.1.8` 默认档 / 入口逐字 `run-client.sh iso -PquickPlay`。
机位 `/tp @s -7.3091 96.0 -11.0535 63.8172 -60`、时刻 6000 + `advance_time false` + 晴；
`cloudsPass=false`、`packWater=false`、`depthGlProxy=false`、探针与逐级 trace 全开
（`postChainTraceSlots=0,4,5`）。配置闸门 A：✓ 15 键一致。

## 一、两条自报都在，且说的是真话

```
vkdisp: [GAP-023] 深度快照已建: 854x480 format=D32_FLOAT usage=7（3 张 = depthtex0/1/2 的三个时刻）
vkdisp: [GAP-023] depthtex* 绑定源: depthtex0 ⇒ 快照=有（该时刻） | taken=[0]/3
        (0=opaque,1=translucent,2=top) terrainFrames=1（快照缺席时回退活深度，不静默换绑）…
```

⇒ `taken=[0]/3` 这一格是重点：它**明说 1/2 号还没就位**。
没有这一行，下一轮就会把「三名仍同源」读成「快照机制已到位、是包不配合」。

## 二、逐位无损：换绑定源之前 / 之后，四个探针的值集**完全相同**

h50l 与 h50m 的唯一差别就是本轮这次改动（链的 `depthtex*` 从**活深度**换成**D32 快照**）：

| 标签 | h50l（链绑活深度） | h50m（链绑快照） |
|---|---|---|
| `main` | `{0.0, 57.7745}`（169 帧） | `{0.0, 57.7745}`（170 帧） |
| `depthviz` | `{16.1362}` | `{16.1362}` |
| `c0@chainStart` | `{18.0286, 27.0276}` | `{18.0286, 27.0276}` |

⇒ **blit 是忠实的**：链换了一张图绑，输出值一个都没变。
🔖 为什么这两臂可以比：它们都带着 depthviz 探针（每帧多一个全屏 pass），
所以「仪器扰动」这一项在两边相同 —— 这正是 h50l 那条仪器纪律的用法：
**同仪器形状下比，才能归因到被测改动**。
（黑帧率两臂都是 ~66%，与不带探针的 h50k 33.5% 不同 —— 那是已登记的探针扰动，不是本条改坏的。）

## 三、这一格买到什么、没买到什么（照实说）

**买到**：`depthtex0` 变成不可变快照 ⇒ 之后任何写深度的 pass 都改不了链看到的深度。
今天云那一格（`mrt.cloudsPass=true` 且 `cloudsNoDepthWrite=false`）就会写 gbuffer 深度
⇒ 链的 `depthtex0` 会被云回头改掉。这条性质以前没有。

**没买到**：`depthtex1/2` 仍与 0 号同源，所以 **`z1 > z0` 仍然恒假，GAP-023 不关**。
原因不是没想做，是机制上缺一块：blit 是 encoder 命令，**render pass 打开期间不能发**
（h10 实测规则），而「不透明之后」这一刻正好处在地形 pass **里面**。
⇒ 下一刀 = 把地形 pass 拆成「不透明一段（清屏 + 画）+ 半透明一段（全 LOAD）」，
中间发两次 blit，各喂 0 号与 1 号。这一步的牵连面都在 `MrtTerrainPass` 那个方法里
（附件 LOAD/CLEAR 语义、`skyPrePainted`、水那段的重绑、GAP-018 翻代时机、探针取点），
所以单独一臂做、单独一臂判，不和本格混在一起。

## 四、离线侧新增的 6 条单测（`DepthSnapshotsTest`）

`slotOf` 三名各映射一格 / 非 `depthtex` 形态一律 -1；usage = 7 且**不含** RENDER_ATTACHMENT；
格式名必须是 `GpuFormat` 深度族里真实存在的字面量；`beginFrame` 后任何一格都不算就位；
越界下标恒 false。
🔴 顺带把一条边界钉进测试注释：**测试源集没有 renderpearl**，所以
`view()`（返回 `GpuTextureView`）与 `take(…, GpuTexture)` 都**不能**被测试调用 ——
本轮两次实测报错逐字：`error: cannot access GpuTextureView` / `cannot access GpuTexture`。
⇒ 因此「本帧有没有」被单独开成纯函数 `has(int)`，而不是让测试去够 GPU 面。
