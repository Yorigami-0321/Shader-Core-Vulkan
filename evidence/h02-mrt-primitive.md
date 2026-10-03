# H02 · GAP-003 多附件（MRT）原语验证 —— 管线侧与 pass 侧都通，地形未接入

> 验证对象：`bridge.MrtProbe` + `pipeline.model.MrtPlan` + `PipelineApi.registerMrtPipeline/registerMrtViewPipeline`
> + `assets/vkdisp/shaders/{mrt.fsh, mrtview.fsh}`（2026-10-03 新增）。
>
> **结论先行**：✅ **多附件原语在本机后端 + 驱动 + 我方管线构造上真的可用**
> （一个 pass 绑 3 个颜色附件、一条管线声明 3 个 `ColorTargetState`、一个片元写 3 路
> `layout(location=N) out`，三槽各自拿到**可量化区分**的内容，0 validation error）。
> 🔴 **但地形没被接进来，包的自研 `gbuffers_*` 片元也没有** ⇒ **GAP-003 仍未完成**。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan 后端 + lavapipe 软件驱动） |
| 客户端 | `./gradlew runClient -PquickPlay`（自动进 `run/saves/New World`），1 趟 + 配置热加载切换 |
| 库存 | `shaderPack="none"`（内置 passthrough）—— 理由同 `h01` §5：隔离变量 |
| 代码 | 工作树（本证据对应提交见文末） |
| 日志源 | `run/logs/latest.log` |
| 设备能力 | `deviceMaxColorAttachments = 8`（原版 `DeviceLimits.maxColorAttachments`，见日志） |
| 残留进程 | ✅ 收尾 `残留游戏进程数 = 0`；无 `hs_err_pid*.log` / `core.*` |

## 一行复现

```bash
source tools/vulkan-local/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew build
./gradlew runClient -PquickPlay --console=plain
# 开诊断视图（默认关）：
#   run/config/vkdisp-client.toml → [mrt] enabled = true, viewSlot = 0|1|2
grep -E "GAP-003|pipeline count check" run/logs/latest.log
```

## 1. 🔴 本轮为什么先验「原语」而不是直接做 M-04

M-04（拿地形 pass 的所有权）是 GAP-003 的真正入口，本轮**没有做**。先做原语的理由是源码级核实结论：

> 原版主 pass（`LevelRenderer.addMainPass`，同 jar 第 396-404、455-463 行）把
> **地形、实体、特性、云、描边画在同一个 pass、同一个单附件里**。

⇒ 若直接把那个 pass 改成多附件，**所有原版管线**（都声明 1 个 `ColorTargetState`）会与 pass
附件数不匹配 ⇒ 全部 validation error。那样得到的失败**不能区分**两种成因：
「后端/驱动根本不支持多附件」还是「后端支持、只是 pass 所有权没拿到」。
**而这两种成因的下一步完全不同**（前者要改路线，后者只是继续做 M-04）。

⇒ 先在**我方自己的 pass** 里把 MRT 原语跑通并量化，把这两种成因分开。
这正是 `17-NATIVE.md` §2–§3 的「先测后优」纪律。

## 2. 多附件原语实测

### 2.1 管线侧：本项目第一条多附件管线

```
[15:11:00.011] vkdisp: [GAP-003] mrt pipeline registered: vkdisp:pipeline/mrt colorTargets=3
    (first multi-attachment pipeline in this project; vertex=vkdisp:fullscreen_flipv fragment=vkdisp:mrt)
[15:11:00.011] vkdisp: pipeline registered (16/17): vkdisp:pipeline/mrt [GAP-003 mrt]
[15:11:00.012] vkdisp: pipeline registered (17/17): vkdisp:pipeline/mrtview (total=17)
```

用 `withColorTargetStates(0, 2, () -> ColorTargetState.DEFAULT)` 声明 3 个附件
（原版 `RenderPipeline.Builder` 的批量 API）。

### 2.2 计数对齐：多附件管线确实被驱动编译了

```
[15:11:08.310] vkdisp: pipeline count check: registered=17, compiled=17 (aligned)
```

17 = 9（原）+ 6（M-01 派生）+ 2（本轮）。
**关键**：把两条 MRT 管线**纳入计数口径**是有意的 —— 若它们编译失败而不在计数内，
断言看不出问题，就会变成「管线没编译但没人知道」的静默失败。

### 2.3 pass 侧：3 附件 + 逐槽指纹

```
[15:11:43.386] vkdisp: [GAP-003] mrt colortex pool ready: 854x480 slots=3
    (roles=[colortex0/albedo, colortex1/normal+lightmap, colortex2/material])
[15:11:43.387] vkdisp: [GAP-003] mrt primitive verified: attachments=3
    deviceMaxColorAttachments=8 viewSlot=0 fingerprintR=0.0
```

`slots=3` 是**设备能力收敛后**的结果（`MrtPlan.clampSlots(8) = 3`，计划值即 3，未降档）。

### 2.4 🔖 判据设计：怎么证明「三个附件都真的被写了」

🔖 **这是本轮最需要设计的部分**。只截图看「画面有内容」是**证明不了分槽**的 ——
三个附件都可能是同一个全屏渐变，肉眼与 luma 都区分不出来。
若某个附件**没被真正写入**，画面会露出该附件的清屏色，而不是内容。

因此设计成**逐槽不同的 R 指纹**（`MrtPlan.fingerprintR`）：槽 0 → R=0，槽 1 → R=1/3，槽 2 → R=2/3；
G/B 通道放 `vUv` 渐变（确认每槽都是全屏有效内容而非单像素）。
每槽的**清屏色**也用同一个指纹值 ⇒ 「没被写」与「写了」在图上必然不同。

### 2.5 三槽量化判读（中心区域，避开 HUD）

取画面中央 60%×40%（避开底部 hotbar / 右上 debug / 准星）：

| 视图槽位 | 中心区 meanR 实测 | 理论值（`slot/3 × 255`） | 偏差 |
|---|---:|---:|---:|
| `viewSlot=0` | **8.6128** | 0.00 | +8.61 |
| `viewSlot=1` | **89.3264** | 84.99 | +4.34 |
| `viewSlot=2` | **170.0401** | 170.01 | **+0.03** |

✅ **三个槽位的 R 均值单调递增且各自贴近理论值** ⇒ 三个附件确实是**三个不同的存储**，
且切换 `viewSlot` 确实切换了显示的附件。

⚠️ **slot0 偏差 +8.61 的诚实说明**：slot0 的理论 R=0，任何非零都是额外贡献。
最可能的来源是**准星**（画面正中）与 HUD 元素落在采样区内 ——
slot2 偏差仅 +0.03 说明采样区大部分确实是纯渐变，而 slot0 的理论值为 0 时，
任何恒定的叠加都会表现为「偏差」（0 附近的绝对误差天然放大）。
**未取证**：未逐像素排除准星贡献，不宣称 slot0 精确等于 0。

### 2.6 三槽截图 sha256 互不相同

| 槽位 | sha256 |
|---|---|
| `mrt-slot0.png` | `9c4f1089…` |
| `mrt-slot1.png` | `ef793e08…` |
| `mrt-slot2b.png` | `b91429a1…` |

⚠️ `mrt-slot2.png`（第一次拍 slot2）与 `mrt-slot1.png` **同哈希** ——
原因是 FileWatcher 有 500ms 去抖，第一次拍时配置尚未生效。
重拍得 `mrt-slot2b.png` 后 R 均值 170.04，判据成立。
（登记这条是因为「同哈希」一度看起来像 bug，实际是取帧早于配置生效。）

## 3. 越界显式失败（X9 不猜）实测

```
[15:14:34.679] vkdisp: fullscreen pass failed
java.lang.IllegalArgumentException: mrt view slot 3 out of range 0..2 (device maxColorAttachments -> 3 slots)
    at dev.vkdisp.pipeline.model.MrtPlan.requireViewSlot(MrtPlan.java:130)
    at dev.vkdisp.bridge.MrtProbe.draw(MrtProbe.java:125)
```

把 `viewSlot` 配成 3（而实际只有 3 槽 ⇒ 合法下标 0..2）后**每帧显式抛错**，
而不是静默夹取到槽 0。**「配错了」必须看起来像配错了** ——
静默夹取会让「我配了 5 号槽」看起来生效、实际看的是别的槽。

## 4. 关闭后回到原版直连（对照）

`mrt.enabled = false` 后：

```
mrt-off-control.png  sha256 = 3e95e5c0…
```

与上一轮 M-01 的控制组 `ctrl-a1.png`（同机位、同世界、`mrt` 尚不存在即关闭）
**逐字节相同** ⇒ ① 本轮新增功能在关闭时对画面**零影响**；
② 顺带再次印证 `h01` §6 的方法论：**先证明画面稳定，再用差异归因**
（正因为上一轮建立了「同状态连拍同哈希」这条基线，本轮的对照才有意义）。

## 5. 稳定与产物核对

| 判据 | 结果 |
|---|---|
| `validation error` / `VUID-` | **0** |
| `Missing uniform` | **0** |
| vkdisp `ERROR` | **0**（除 §3 那次**故意**配置的越界） |
| `stages=190 ok=190 failed=0` | ✅ 未回退 |
| `pipeline count check` | `registered=17 compiled=17 (aligned)` |
| `./gradlew build` | exit 0 |
| jar `.class` 数 | 210（含 `assets/vkdisp/shaders/{mrt.fsh, mrtview.fsh}`） |
| jar 含 `vkdisp.mixins.json` / `LICENSE` / 不含 `net/minecraft` | ✅ |

### 测试

**639 单测全绿**（本轮新增 7 例 `MrtPlanTest`）：
槽位数与 OF location 对应；**指纹逐槽不同且落在 [0,1)**（分槽判据的前提）；
格式统一；设备能力收敛（含 `0` / 负数的荒谬输入）；越界显式抛错；
`SlotSpec` 构造越界抛错；计划槽数不超原版硬上限 8。

## 6. 🔴 明确**未完成 / 未证明**的

| # | 项 | 说明 |
|---|---|---|
| 1 | 🔴 **地形接入多附件** | **完全未做** = M-04。且本轮把难度具体化了：原版主 pass 里混着实体/特性/云/描边，**不能只改附件数** |
| 2 | 🔴 **包的自研 `gbuffers_*` 片元** | 未做。`mrt.fsh` 是我方验证件，不是包的 `gbuffers` 翻译产物 |
| 3 | 🔴 **GAP-004 的块仍无消费者** | 地形片元仍是原版 `core/terrain`，不读 `VkDispTerrainParams`（承自 `h01`） |
| 4 | 槽位格式 | 统一 `RGBA8_UNORM`。真实 OF gbuffer 需要 `colortex1` 之类用 `RGBA16_FLOAT` 存法线 —— **格式兼容性未测** |
| 5 | 设备覆盖 | 仅 lavapipe（`maxColorAttachments=8`）。真实 GPU / 移动端未测 |
| 6 | 性能 | MRT 绘制**未测帧时间**。默认关闭 ⇒ 常规帧零开销，但开启后的代价未量化 |
| 7 | 附件数上限 | 只用 3 槽（原版上限 8）。降档路径由单测覆盖，**未在真机上实测**（本机 8 ≥ 3，未触发降档） |
| 8 | 其它 draw 类型 | 实体（M-02）/ 天空：未开始 |

## 7. 下一步

1. **M-04**（`LevelRenderer#addMainPass`）：⚠️ 本轮已把它的难点具体化 ——
   **不能只给地形 pass 加附件**，因为原版把实体/特性/云/描边也画在里面。
   需要先决定「地形单独一个 pass」还是「整个主 pass 多附件 + 所有原版管线都跟着改」。
   两条路的代价差别很大，**建议先做取舍分析再动手**。
2. `colortex1` 改 `RGBA16_FLOAT` + 法线语义（MRT 原语已通，格式是独立一步）。
3. GAP-004 收口：包的自研 `gbuffers_*` 片元接上后，`VkDispTerrainParams` 才有消费者。

## 8. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| `run/config/vkdisp-client.toml` | `shaderPack` 改 `"none"`；新增 `[mrt]` 段（`enabled`/`viewSlot` 由 FML 自动写入默认值） | 收尾还原 `shaderPack = "BSL_v10.1.8"`；`[mrt] enabled = false`（= 默认） |
| 同上 | 取证期间改 `viewSlot` 0→1→2→3→0、`enabled` true→false | ✅ 同上 |
| `run/config/vkdisp-pack-options.properties` | **未改动** | sha256 `e76d3fd3…` |
| 游戏进程 | 1 趟 runClient | ✅ 已结束，**残留 = 0** |