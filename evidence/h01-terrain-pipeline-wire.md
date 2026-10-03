# H01 · H 线 M-01/M-01b：派生地形管线接到地形 draw 上（GAP-003 通道 + GAP-004 块）

> 验证对象：`dev.vkdisp.mixin.{ChunkSectionLayerPipelineMixin, ChunkSectionsToRenderMixin}` +
> `bridge.TerrainPipelineApi` + `pipeline.model.TerrainDerivedPlan`（2026-10-03 新增）。
>
> **结论先行**：🔴 **通道真的通了，但 GAP-003（多附件）没做、GAP-004 的块还没有消费者。**
> 本轮把「派生管线能否被地形 draw 用上」与「自定义 uniform 块能否挂上并每帧绑定」
> 这两件事从「未验证」变成「实测通过」，同时**定位出 GAP-003 的真瓶颈在 render pass、不在管线**。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan 后端 + lavapipe，`tools/vulkan-local/env.sh`） |
| 客户端 | `./gradlew runClient -PquickPlay`（自动进 `run/saves/New World`），3 趟 |
| 库存 | `shaderPack="none"`（内置 passthrough 兜底）—— 本轮**故意不用 BSL**，理由见 §5 |
| 代码 | 工作树（本证据对应提交见文末） |
| 日志源 | `run/logs/latest.log`（不是 `debug.log`，后者重复写同一批记录） |
| 机器安静性 | 跑前 `cat /proc/loadavg` = 0.08，无其他 java 进程；**跑客户端期间未并行跑任何基准**（`AGENT_CONTEXT §9.4.13` 铁律） |

## 一行复现

```bash
source tools/vulkan-local/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew build                                   # 632 单测
./gradlew runClient -PquickPlay --console=plain  # 自动进世界
grep -E "M-01|M-01b|pipeline count check" run/logs/latest.log
# 开关对照：改 run/config/vkdisp-client.toml 的 wireTerrain（配置热加载，免重启）
```

## 1. 注入点登记表先行（X28：先登记，再写代码）

`docs/04-SPEC.md` §5.0 本轮**改判**了一处：M-01 的目标类从
`ChunkSectionsToRender#renderLayers` 改成 `ChunkSectionLayer#pipeline(boolean)`，
并新增 M-01b（`renderLayers`，绑 uniform）与 M-04（`LevelRenderer#addMainPass`，GAP-003 的入口）。

**为什么改判**（源码级核实，26.3.0.41-beta sources jar，行号可复现）：

| 事实 | 后果 |
|---|---|
| `renderLayers` 的两个 override 形参**整组共用**一个值 | 该组所有层被迫用同一条管线 |
| `ChunkSectionLayerGroup.OPAQUE = {SOLID, CUTOUT}`（`ChunkSectionLayerGroup.java:9`），一次 `renderGroup` 送两层进同一个 `renderLayers` | CUTOUT 会套用 SOLID 的状态 |
| `SOLID_TERRAIN` 无 `ALPHA_CUTOUT`；`CUTOUT_TERRAIN` = 0.5F；`TRANSLUCENT_TERRAIN` = 0.1F + `BlendFunction.TRANSLUCENT`（`RenderPipelines.java:349/379/393`） | 套错的后果 = cutout 失去 alpha 剔除 + 半透明地形变不透明，**且不抛任何异常** |

⇒ 按层解析的**唯一收口点**是 `ChunkSectionLayer#pipeline(boolean)`
（`ChunkSectionsToRender.java:121` 多重绘制分支、`:176` 分支绘制分支都走它）。
这个改判是**先做源码核实才动的代码**，不是写完发现不对再回头改。

🔖 **连带核实（容易漏）**：`pipeline(false)` **不只在 draw 时被调**，建网格时也被调 ——
`SectionRenderDispatcher.java:76` 与 `LevelRenderer.java:796` 取 `getVertexFormatBinding(0)`。
派生管线沿用同一 snippet ⇒ 顶点绑定逐项相同 ⇒ 该处行为不变。

## 2. GAP-004 的实测新事实：绑组条目**必须**有人绑

`PipelineApi` 的既有 P4.1.2 经验（字节码核实）：驱动层
`PipelineBuilder.generateBackendCreateInfo` 会按 SPIR-V 反射校验
「片元引用的每个 descriptor 都能在布局里查到」，而 `FrontendRenderPass.validateDraw`
在 STRICT_VALIDATION 下**按布局逐条**要求 `setUniform`。

原版 `renderLayers` 自己只绑 `TerrainUniform` / `Sampler0` / `Sampler2`
（`ChunkSectionsToRender.java:85-87`）⇒ 派生管线多出的那条**没人会绑**。
所以 M-01 只换管线会立刻 `Missing uniform VkDispTerrainParams` ⇒ **必须有 M-01b**。
这解释了「为什么两个注入点不能只做一个」，是本轮最实用的一条结论。

## 3. 实测证据

### 3.1 六条派生管线注册成功（逐条，不是合并计数）

```
[14:31:54.199] vkdisp: [M-01] terrain derived pipeline registered: layer=SOLID multiDraw=false
    location=vkdisp:pipeline/terrain_solid alphaCutout=<none> translucentBlend=false
[14:31:54.199] … layer=SOLID multiDraw=true  location=vkdisp:pipeline/terrain_solid_multidraw …
[14:31:54.199] … layer=CUTOUT multiDraw=false location=vkdisp:pipeline/terrain_cutout alphaCutout=0.5 …
[14:31:54.200] … layer=CUTOUT multiDraw=true  location=vkdisp:pipeline/terrain_cutout_multidraw …
[14:31:54.200] … layer=TRANSLUCENT multiDraw=false location=vkdisp:pipeline/terrain_translucent
    alphaCutout=0.1 translucentBlend=true
[14:31:54.200] … layer=TRANSLUCENT multiDraw=true  location=vkdisp:pipeline/terrain_translucent_multidraw …
[14:31:54.200] vkdisp: M-01 terrain derived pipelines registered: 6/6 (total registered=15)
```

`alphaCutout` / `translucentBlend` 两列与 §1 的原版参数表**逐条对齐** ——
这三条是「派生管线有没有把原版状态抄全」的全部差异项，由
`TerrainDerivedPlanTest` 用**独立于实现的期望值**锁住（8 例）。

### 3.2 通道真的通了 —— 打印的是我方 location，不是「对象非空」

```
[14:32:02.142] vkdisp: [M-01] hit (ChunkSectionLayer#pipeline) — … wiring active (enabled=true, derivedRegistered=6)
[14:32:02.142] vkdisp: [M-01] wired: layer=SOLID       multiDraw=false -> vkdisp:pipeline/terrain_solid
[14:32:02.143] vkdisp: [M-01] wired: layer=CUTOUT      multiDraw=false -> vkdisp:pipeline/terrain_cutout
[14:32:02.143] vkdisp: [M-01] wired: layer=TRANSLUCENT multiDraw=false -> vkdisp:pipeline/terrain_translucent
[14:32:02.229] vkdisp: [M-01] wired: layer=SOLID       multiDraw=true  -> vkdisp:pipeline/terrain_solid_multidraw
[14:32:02.229] vkdisp: [M-01] wired: layer=CUTOUT      multiDraw=true  -> vkdisp:pipeline/terrain_cutout_multidraw
[14:32:02.231] vkdisp: [M-01] wired: layer=TRANSLUCENT multiDraw=true  -> vkdisp:pipeline/terrain_translucent_multidraw
```

**6/6 全部被取用**（含多重绘制变体 ⇒ 本机 lavapipe 走了 `DrawIndirect` 路径）。
原版随后执行 `RenderSystem.getCompiledPipeline(<我方管线>)` ——
**这条日志就是「地形 draw 实际用的是我方管线」的直接证据**，
而不是靠「注册成功」间接推断。

### 3.3 GAP-004 的块每帧绑定，且零 validation error

```
[14:32:02.228] vkdisp: [M-01b] hit (ChunkSectionsToRender#renderLayers) — … entry reached
    (enabled=true, block=VkDispTerrainParams, bytes=32)
[14:32:02.229] vkdisp: [M-01b] terrain params ring created: bytes=32 (written once; no consumer yet)
```

| 判据 | 结果 |
|---|---|
| `Missing uniform` | **0** |
| `validation error` / `VUID-` | **0** |
| `Mixin apply failed` / `InvalidInjectionException` | **0** |
| vkdisp `ERROR` | **0**（余 2 条 = narrator / OpenAL，环境性，历史基线一致） |
| `pack compile done` | `stages=190 ok=190 failed=0`（未回退） |
| 管线计数 | `registered=15, compiled=15 (aligned)`（9 → 15，**6 条派生管线确实被驱动编译过**） |

`registered=15 compiled=15` 是本轮**刻意加强**的一项：原先 6 条派生管线不在计数口径内，
编译失败会静默地让地形退回原版管线（典型的静默失败）。现已纳入（`PipelineApi.recordTerrainDerived`）。

### 3.4 一键关闭（实测生效，非仅存在配置项）

```
[14:33:13.180] vkdisp: [M-01] disabled by config (mixin.wireTerrain=false)
    -> vanilla terrain pipeline in use (derived still registered=6 and compiled)
```

改 `run/config/vkdisp-client.toml` 的 `wireTerrain` → FML FileWatcher（500ms 去抖）→
**免重启**、下一帧生效。注意日志措辞：派生管线**仍注册仍编译**，只是不再被取用 ——
所以「关掉开关」不等于「资源开销归零」，README/配置注释里已如实写明。

## 4. 🔴 两趟截图互相矛盾 —— 先当矛盾记着，不选对自己有利的那个

| 趟次 | ON | OFF | 表面结论 |
|---|---|---|---|
| run 1 | `2a76e7e2…` | `2a76e7e2…` | 逐字节相同 |
| run 2 | `2a76e7e2…` | `0b2fd206…` | 不同，OFF 明显更暗（天空 127.3→104.4、地面 116.2→86.7） |

🔖 run 1 的 ON 图与 run 2 的 ON 图**也是同一哈希**（`2a76e7e2…`），
即 run 2 的变化不是开关造成的（那期间开关都是 ON）⇒ **矛盾点在 OFF 那一侧**。

我**没有**在这里下结论，而是做了控制实验（§6）来判定「像素在本场景能否作判据」。
结论：run 2 的差异是**区块加载期的取帧时机**造成的假信号（成因已定位到日志时间线）。

## 5. 为什么这轮用 `shaderPack="none"` 而不是 BSL

- **隔离变量**：本轮只验证「地形 draw 用哪条管线」。BSL 会叠加 190 阶段的包编译与
  composite/deferred 链，失败时无法区分是 M-01 造成的还是包造成的。
- **冷路径更短**：`none` 走内置 passthrough，冷路径从约 3.4s 降到 3.1s（仍远超 B3 的 1s，
  那是另一条线的问题，见 `17-NATIVE.md`）。
- ⚠️ **代价**：本轮**没有**在 BSL 下验证 M-01 与包链共存。登记为未覆盖项。

## 6. 🔖 控制实验（run 3）：先把「像素能不能当判据」这件事本身测掉

上面 §4 的矛盾必须先解决，否则「开关无视觉影响」和「开关让画面变暗」两个结论都能自圆其说。
方法：同一趟客户端里连拍，**开关状态是唯一变量**，并用「同状态连拍两帧」验证画面本身是否稳定。

| 拍 | 开关 | 时刻 | sha256 | mean |
|---|---|---|---|---|
| `ctrl-a1.png` | ON | 14:37:0x | `3e95e5c0…` | 112.94 |
| `ctrl-b1.png` | **OFF** | 14:37:1x（改完配置后） | `3e95e5c0…` | 112.94 |
| `ctrl-b2-samestate.png` | OFF（同状态复拍） | 14:37:4x | `3e95e5c0…` | 112.94 |
| `ctrl-a2-reon.png` | **ON（改回）** | 14:38:3x | `3e95e5c0…` | 112.94 |

**四张逐字节相同**（`cmp` 通过）。配置切换的生效有日志独立佐证，不是「配置没生效所以图一样」：

```
[14:37:09.168] vkdisp: [M-01] disabled by config (mixin.wireTerrain=false) -> vanilla terrain pipeline in use
```

同时渲染循环确实活着（M-01 命中计数从 1250000 一路涨到 1750000），
排除「窗口无响应/画面冻住导致假阴性」。

⇒ **判定**：在**画面静止**的条件下，开关 ON/OFF **逐像素无差异**（run 3，四张同哈希）。
**run 1 的「相同」因此是可信的；run 2 的「不同」是假信号。**

🔖 **run 2 那个假信号的成因（已定位）**：run 2 的两张截图相隔约 40 秒，期间原版正在
`Resizing Chunk Sections`（14:33:45 最后一条）—— **区块还在陆续加载进视野**，
天空/地形像素随新区块进入而变化。它与开关无关，是**取帧时机落在加载期**。
run 3 取帧时加载早已结束（最后一条 `Resizing` 在 14:35:37，截图在其后 90 秒）。

> 🔖 **教训（可复用，且与 `08-TESTING.md` §10 的 P4.1 误判同族）**：
> 「截图有差异」不等于「我的改动造成了差异」。**先证明画面稳定（连拍两帧同哈希），
> 再用差异归因。** 本轮差点把一次加载期抖动写成「关掉 mixin 画面变暗」——
> 那会是一条完全错误的结论，且方向恰好对改动不利。

## 7. 未覆盖 / 未证明（不许当已完成引用）

| # | 项 | 说明 |
|---|---|---|
| 1 | 🔴 **GAP-003 多附件** | **完全未做**。已定位真瓶颈：原版地形 pass 由 `LevelRenderer.addMainPass` 的 `createRenderPass(name, colorView, Optional.empty(), depthView, OptionalDouble.empty())` 建出，**颜色附件恰好 1 个** ⇒ 管线侧加附件必与 pass 不匹配。须先做 M-04（拿 pass 所有权）+ 自研 gbuffer 片元。**支柱①「完整」二字仍无实现支撑。** |
| 2 | 🔴 **GAP-004 的块无消费者** | 地形片元仍是原版 `core/terrain`，**不读** `VkDispTerrainParams`。已证「能挂上、能每帧绑、不炸」，**未证「读得到、内容正确」。** |
| 3 | 视觉等价的**充分**证据 | §6 已判定「画面静止时开关 ON/OFF 逐像素无差异」（四张同哈希 + 配置生效日志 + 渲染循环存活）。⚠️ 但这只覆盖**静止画面**；动态场景（相机转动、水面动画、昼夜循环）下的等价性**未测**。 |
| 4 | BSL 共存 | §5，未验证。 |
| 5 | 实体 / 天空 draw | M-02 未开始（登记表已留行）。 |
| 6 | 多平台 / 真 GPU | 仅本机 lavapipe（软件 Vulkan）。`pipeline(true)` 分支虽被取用，但是 lavapipe 的能力集，不代表真实硬件。 |
| 7 | B1 帧时间 | 本轮**未测**。设计上热路径只有 2–6 次查表/帧（稳态），但**建网格**期约 1300 次/秒 ⇒ 仍需按 `17-NATIVE.md §7` 单独测。 |
| 8 | `MIXIN_CONFIG_COUNT` | 硬编码为 1（= 启用的 mixin 配置数），由 `MixinWiringTest` 与 toml 对照。 |

## 8. 🔖 本轮踩到并修掉的坑：埋点节流把自己变成了热路径

首版按「每 600 次打一行」节流，实测**进世界首段约 1300 次/秒**
（因为 `pipeline(false)` 在建网格时被大量调用）⇒ 每 0.45 秒往**渲染线程**写一行日志。

**后果**：单趟日志里 M-01 埋点行 **221 行**（`grep -c "M-01. hit"`），
其中 220 行是我自己的节流日志。日志 I/O 成了热路径开销 —— 违反支柱③（B1 ≤ +2%）。

**修正**：节流间隔 600 → **250000**，并保留「首次必打」。
实测：埋点行 221 → **1 行**，「是否还活着」的信息一点没丢。

> 🔖 **教训（可复用）**：注入点落在「也会被工具链调用」的方法上时，
> **调用频次的估计必须实测**，不能按「每帧几次」拍。`pipeline()` 是典型反例：
> 它既是 draw 路径，也是**网格构建**路径。

## 9. 测试

`./gradlew build` **BUILD SUCCESSFUL**，**632 单测全绿**（本轮新增 15 例）：

| 测试类 | 例数 | 锁住什么 |
|---|---|---|
| `TerrainDerivedPlanTest` | 8 | 6 条规格与原版参数**逐条**对齐（期望值独立于实现写死）；location 唯一生成式 + 两两不同；未知层名显式抛错；层名大小写严格 |
| `MixinWiringTest` | 7 | T1（`compatibilityLevel=JAVA_25`）；X26（`[[mixins]]` 已取消注释）；每个 `*Mixin.java` 都在 `vkdisp.mixins.json` 里；M1①（无字面量类名）；M1⑤（每点一个键）；M1③（注入体首行是埋点转发）；`MixinTargets` 常量与 26.3 包名一致 |

`MixinWiringTest` 的价值在于：它把**「mixin 静默不加载」这一类不报错的失效**
变成 `./gradlew build` 的红灯。写它时自己踩了两次（javadoc 里出现 `@Inject(` 字样被误当注解；
方法签名跨行导致取到签名行当首行）—— 两次都是测试自己的 bug，已修并留注释。

## 10. 产物核对（`01-DEV-LOOP` §3.2）

| 项 | 结果 |
|---|---|
| `./gradlew build` | exit 0 |
| jar | `build/libs/vkdisp-0.1.0.jar`，`.class` **207** 个 |
| 含 `vkdisp.mixins.json` | ✅ |
| 含 `META-INF/neoforge.mods.toml` 且 `[[mixins]] config="vkdisp.mixins.json"` | ✅ |
| 含 `LICENSE` | ✅ |
| 不含 `net/minecraft/**` / `com/mojang/**` | ✅ 0 命中 |
| mixin 类入 jar | `dev/vkdisp/mixin/{ChunkSectionLayerPipelineMixin, ChunkSectionsToRenderMixin}.class` |

## 11. 运行期环境副作用披露

| 对象 | 改动 | 还原情况 |
|---|---|---|
| `run/config/vkdisp-client.toml` | `shaderPack` 由 `"BSL_v10.1.8"` 改为 `"none"`（本轮取证用，理由见 §5） | 收尾时还原为 `BSL_v10.1.8`（原始值，已用 sha256 核对） |
| 同上 | 新增 `[mixin] wireTerrain / bindTerrainParams` 段（FML 自动写入默认值） | ✅ 取证结束时为 `true` / `true`（= 默认） |
| 同上 | 取证期间反复改 `wireTerrain`（false→true） | ✅ 同上 |
| `run/config/vkdisp-pack-options.properties` | **未改动** | sha256 `e76d3fd3…`（与 B4 轮记录一致） |
| 游戏进程 | **3 趟** runClient | ✅ 全部已结束，**残留游戏进程数 = 0**；只剩 Gradle daemon（pid 10284），按 `AGENT_CONTEXT §9.5` 不 kill；无 `hs_err_pid*.log` / `core.*` 新增 |

## 11.1 取证用到的日志与截图哈希

| 文件 | sha256 | 说明 |
|---|---|---|
| `run/logs/pre-m01-*.log` | — | run 1 前的历史日志（已隔离改名） |
| `run/logs/m01-run1-*.log` | `3e2751ad…` | run 1（ON→OFF，节流未修） |
| `run/logs/m01-run2-*.log` | — | run 2（ON→OFF，出现矛盾信号） |
| `run/logs/m01-run3-*.log` | `21f6008e…` | run 3（控制实验，节流已修） |
| `run/screenshots/ctrl-a1.png` | `3e95e5c0…` | 控制组 ON |
| `run/screenshots/ctrl-b1.png` | `3e95e5c0…` | 控制组 OFF |
| `run/screenshots/ctrl-b2-samestate.png` | `3e95e5c0…` | 同状态复拍（画面稳定性基线） |
| `run/screenshots/ctrl-a2-reon.png` | `3e95e5c0…` | 改回 ON 后复拍 |
| `run/screenshots/m01-run2-on.png` / `-off.png` | `2a76e7e2…` / `0b2fd206…` | §4 矛盾信号的两张原始图 |

## 12. 下一轮入口

1. **M-04**（`LevelRenderer#addMainPass`）—— GAP-003 的唯一入口，支柱①最大阻塞项。
   已登记（`04-SPEC` §5.0 M-04 行），需逐个开启（M1/X29），可关闭键 `mixin.ownTerrainPass`。
2. **GAP-003 的自研 gbuffer 片元**：多附件通了之后，片元换成我们自己的 `gbuffers_*` 翻译产物
   ⇒ 那时才谈得上「块被消费」（GAP-004 收口）。
3. **B1 帧时间**：M-01 落地后热路径多了一次查表 + 每帧 2–3 次 `setUniform`，
   按 `17-NATIVE.md §7` 单独测（本轮未测，已登记）。
4. **BSL 下共存验证**（§5 未覆盖项）：M-01 与 190 阶段包链同时开。