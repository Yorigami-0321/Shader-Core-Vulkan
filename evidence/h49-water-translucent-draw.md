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

## 七、本轮**不覆盖**什么（显式清单，别把这些读成已验）

1. **水的画面效果对不对**未判 —— 那需要 `depthtex1` 的真快照（GAP-023 ②/③ 未做）、
   水的顶点属性上限（GAP-007）、以及一个**画面里真的有水**的机位。
2. **`z1 > z0` 一族判据仍不可信**：`depthtex0/1/2` 本轮依旧同绑那张 1×1 D32@0.0 桩
   （绑定自报行逐字：`depthtex*=1x1 D32@0.0 桩（**不是**本 pass 附件，快照未实现）`）。
3. **跨臂比亮度（F2 luma）本轮不作判据**：h49 / h49b 两轮的「哪一臂在闪」是**换位**的
   （h49：OFF 闪、ON 不闪；h49b：OFF 不闪、ON 闪），同一配置跨轮次不稳定
   ⇒ 那条周期性暗帧属于呈现/回读侧的已知仪器问题（GAP-020 家族），本轮不据此下任何结论。
4. 云 / 实体 / 手 / 天气 / `shadow` 仍未接（GAP-027 的其余程序）。
5. `gbuffers_skybasic` 编译失败（`'MC_RENDER_STAGE_STARS' … error`）两臂都在，
   **不是本轮引入**，本轮也未修（登记为待办，见 CHANGE_LOG 的 ⛔）。
