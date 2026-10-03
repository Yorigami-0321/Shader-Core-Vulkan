# H04 · 方案 A 第 2 步：地形**真的画进了 3 附件的 render pass** —— 根因是反向 Z 的 0.0/1.0

> 验证对象：`bridge.MrtTerrainPass`（新）、`TerrainPipelineApi` 的 **MRT 变体表**、
> M-01 的 pass 内管线切换、`MrtProbe#drawExternalView` 任意视图回读（2026-10-03 新增）。
>
> **结论先行**：✅ **地形 draw 数据能在我方自己控制的 3 附件 pass 里完成渲染**，
> 且**逐像素可判读**（colortex0 = 真地形；colortex1 = 只有清屏色，因为原版
> `core/terrain.fsh` 只声明 `layout(location = 0) out vec4 fragColor`）。
> 🔴 **仍不产出画面改进**（写自己的 colortex，主目标由原版绘制 ⇒ 地形被画两遍），开关默认关。
>
> ⚠️ **本轮最大的收获不是这个功能，而是那个根因**：本引擎是**反向 Z**，
> 自建深度目标必须清到 **0.0** 而不是惯例的 1.0。为这个 0.0/1.0 之差白跑了 **6 趟**客户端。
> 详见 §5。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan + **lavapipe**，软件光栅） |
| 客户端 | `./gradlew runClient -PquickPlay`，**18 趟**（其中 6 趟纯粹在找 §5 那个根因） |
| 库存 | `shaderPack="none"`（隔离变量，同 `h01`/`h02`/`h03`） |
| 关键配置 | `mrt.enabled`、`mrt.terrain`、`mrt.terrainAfterLevel`、`mrt.attachments=3` |
| ⚠️ validation layer | 🔴 **没有安装**（见 §6，这条让「0 validation error」彻底失去证据价值） |
| 残留进程 | ✅ 每趟 `tools/vulkan-local/game_procs.sh kill`；收尾「残留游戏进程数=0」 |

## 1. 卡点解除：`renderGroup` 的两个入参都能用**公开 API** 拿到（不需要 M-04）

h03 登记为「未核实的第一个卡点」，本轮逐个签名核实：

| 需要的东西 | 原版怎么拿（`LevelRenderer` 26.3） | 我方怎么办 | 结论 |
|---|---|---|---|
| `blockAtlas` | `:531` `textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()` | **同一条公开路径**：`Minecraft.getTextureManager()` → `getTexture`（public，`:91`）→ `getTextureView`（public，`AbstractTexture:45`）；`LOCATION_BLOCKS` 是 public static | ✅ 不需要 M-04 |
| `sampler` | `:442-447` **自建**（各向异性 + `shouldResetChunkLayerSampler` 门控） | **自建同款**：`RenderSystem.getDevice().createSampler(...)`（public，`:32-34`） | ✅ 不需要 M-04（**不用原版那个实例** —— 它是 private 字段 `:146`） |
| `lightmap` | `renderGroup` 内部自取 | 不用管 | ✅ |

⇒ `renderGroup(group, pass, sampler, atlas, wireframe)` 是 **public**，我方可在自己的 pass 里直接调它画地形。

⚠️ 语义注意（非阻塞，已写进代码注释）：采样器各向异性取决于用户纹理过滤设置，硬编码会低于原版；
采样器**必须懒建缓存**，每帧新建会泄漏 GPU 对象。

## 2. 为什么需要「活动标记」（本轮最核心的设计点）

M-01 注入在 `ChunkSectionLayer#pipeline(boolean)`。该方法**只知道「谁在调用我」，
不知道调用方开的是哪个 pass** —— 原版 `DrawSeparate#render` 只调它一次、不透传 pass 引用。

Vulkan 要求**管线颜色附件数与 render pass 附件数相等** ⇒ 同一批地形 draw 在两种 pass 里跑
必须用**两条不同的管线**：

| pass | 附件 | 管线 |
|---|---|---|
| 原版主 pass | 1 个（颜色目标是 main） | `vkdisp:pipeline/terrain_*`（h01 那 6 条） |
| 我方 gbuffer pass | 3 个（我方 colortex） | `vkdisp:pipeline/terrain_*_mrt`（本轮新增 6 条） |

⇒ 唯一可行的区分方式是我方在 pass 体内**置位/清位**一个标记（`MrtTerrainPass.active()`），
`derivedTerrainPipeline` 先看它。**置位/清位都在 `finally` 里** —— 漏清会让后续**原版** pass
拿到多附件管线。

## 3. 像素证据（三张截图，互相构成对照）

| 截图 | 配置 | 世界区 mean RGB | 说明 |
|---|---|---|---|
| `gb-colortex0-terrain.png` | 回读槽 0 | `(78.1, 71.1, 68.9)`，100% 非零 | 🔖 **colortex0 里是真地形**（泥土/石头墙、地面、正确的遮挡与贴图）。⚠️ **2026-10-03 晚更正**：这张图整体**上下颠倒**，我当时没看出来。「地形在里面」这个结论成立，**朝向不成立** —— 根因是回读 blit 用了翻转顶点着色器，见 `h05` |
| `gb-colortex1.png` | 回读槽 1 | `(2.3, 1.7, 237.4)`，100% 非零 | 🔖 **纯蓝 = 只有清屏色**。原版 `core/terrain.fsh` 只有 `layout(location=0) out vec4 fragColor` ⇒ 槽 1/2 **理应**没有被写 |
| `gb-main-terrain-on.png` | 回读关（看主目标） | 正常夜景渲染 | 🔖 我方 pass 已画 600+ 帧进 colortex，**主目标仍是正常的原版渲染**（无绿色、无破损） |

> 槽 1 是「应该空」的槽，它**恰好**是纯清屏色 —— 这比「三张都有内容」更有说服力：
> 它同时证明「三槽是三个不同存储」**和**「写入只落在 location 0」两件事。
> h02 用的是 R 通道指纹（槽 0 恰好是 0.0 = 黑），本轮把清屏色改成
> **逐槽高对比色**（绿/蓝/品红）—— 原因见 §7 的教训③。

另有 `gb-tomain-3attach-terrain.png`（诊断「画到主目标」模式）：地形由**我方 pass** 以
**3 附件管线**画进主目标，贴图、光照、遮挡全部正确 —— 这是最直接的「我方 pass 能画地形」证据。

## 4. 日志证据

```
[GAP-003/A] terrain MRT derived pipelines registered: 6/6 (colorTargets=3)
[GAP-003/A] gbuffer terrain targets ready: 854x480 slots=3 depth=D32_FLOAT
[GAP-003/A] wired (mrt variant): layer=SOLID  multiDraw=true -> vkdisp:pipeline/terrain_solid_multidraw_mrt
[GAP-003/A] wired (mrt variant): layer=CUTOUT multiDraw=true -> vkdisp:pipeline/terrain_cutout_multidraw_mrt
[GAP-003/A] terrain MRT pass frames=600 / 1200 / 1800
[GAP-003/A] captured draw groups: SOLID{groups=1,draws=296} CUTOUT{groups=0,draws=0} TRANSLUCENT{groups=0,draws=0}
vkdisp: pipeline count check: registered=23, compiled=23 (aligned)
```

| 事实 | 证明什么 |
|---|---|
| `registered=23 compiled=23` | 6 条 MRT 地形管线**真的编译过**（h02 纪律：编译不过必须显式暴露，否则会静默退回原版管线） |
| `wired (mrt variant)` × 2 | M-01 在我方 pass 内**确实切到了多附件变体**，且 `multiDraw=true` 命中 ⇒ 本机 indirect 路径也覆盖到 |
| `frames=600/1200/1800` | pass **不是只跑一帧**就失效（帧图剔除、捕获失效都只会跑首帧） |
| `SOLID{groups=1,draws=296}` | 捕获到的对象**确实带着 draw**（这条是 §5 定位过程的关键，见教训②） |

## 5. 🔴 根因：反向 Z —— 自建深度必须清 **0.0**，不是 1.0

**症状**：pass 正常执行、M-01 确实切了多附件管线、无任何异常日志，
但**一个片元都出不来** —— colortex / 主目标里只剩清屏色。

**根因**：本引擎是**反向 Z**（近平面 → 深度 1.0，远平面 → 深度 0.0）。
我按「Vulkan 惯例」把自建深度清到 **1.0**（= 近平面），
于是**每一个**地形片元都过不了 `GREATER` 系的深度测试。

#### 根因的三级直接证据（字节码级，非推断）

⚠️ 初版这里只写了「从清屏值反推」。2026-10-03 复核后升级为直接证据 ——
三条**必须同时成立**才自洽，缺一条就不可能是反向 Z：

| # | 事实 | 出处（字节码核实） |
|---|---|---|
| ① | 投影矩阵把 `setPerspective` 的 **near / far 实参对调** | `net.minecraft.client.renderer.Projection#getMatrix`：`fstore_2 = zFar`、`fstore_3 = zNear`，随后压栈顺序为 `fload_2`(far) → `fload_3`(near)，即 `setPerspective(fov, aspect, zFar, zNear, zZeroToOne)`。JOML 形参是 `(fovy, aspect, zNear, zFar, zZeroToOne)` ⇒ **对调** ⇒ 近→1.0、远→0.0 |
| ② | 深度比较是 **`GREATER_THAN_OR_EQUAL`**，不是 `LESS` | `DepthStencilState` 的 `static {}`：`new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)` |
| ③ | 主目标深度格式 **`D32_FLOAT`**，clear 传 **`0.0`** | `com.mojang.blaze3d.pipeline.MainTarget` 构造：`RenderTarget(label, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)`；`LevelRenderer:255` `clearColorAndDepthTextures(..., depth, 0.0)` |

🔖 **一个容易误认的无关项**：`Projection#getMatrix` 里还有个
`DeviceInfo.isZZeroToOne()`，它只用来适配 **Vulkan 的 NDC `[0,1]` 与 OpenGL 的 `[-1,1]`**，
**与「反向」无关**。别把它当成反向 Z 的开关。

⇒ 三条合起来唯一自洽的解：**近平面 1.0 / 远平面 0.0 / 清 0.0（=远）/ 比较用 `>=`**。

**为什么这个坑这么难查**（这是本轮真正要登记的经验）：

| 性质 | 说明 |
|---|---|
| 零报错 | 不产生 validation error，日志全绿 |
| 零告警 | 没有 API 异常、没有异常堆栈 |
| 症状与「什么都没执行」**完全同形** | 「pass 没跑」「draw 没提交」「管线没切换」「附件数不匹配」在像素上**长得一模一样** |

### 定位路径（6 趟客户端，每一步都排除了一个假设）

| 趟 | 做的判别实验 | 结果 | 排除了什么 |
|---|---|---|---|
| 1 | 清屏色改成**逐槽高对比**（绿/蓝/品红） | 纯绿 | ✅ 排除「回读链路坏」—— 之前槽 0 的指纹恰好是 0.0（黑），「全黑」既可能是没画也可能是回读坏，**分不开** |
| 2 | `mrt.attachments=1`（管线与 pass 同时改成 1） | 仍全绿 | ✅ 排除「多附件本身有问题 / 附件数不匹配」 |
| 3 | 反射读 `drawGroupsPerLayer`（draw 条数） | 首帧 `groups=0` → 改到第 300 帧再探 | ❌ **教训②**：首帧区块还没网格化，`groups=0` 是必然的，我却一度把它当成「捕获数据是空的」 |
| 4 | 同上，第 300 帧 | `SOLID{groups=1,draws=296}` | ✅ 排除「没有 draw」 |
| 5 | 在我方 pass 里先画一个**已知可用**的全屏三角形（采样方块图集） | 🔖 **图集铺满全屏** | ✅ 排除「本 pass 不工作」（附件/视口/深度/管线全对）。🔖 **关键**：该三角形管线**没有深度状态** ⇒ 它不受深度影响，于是把「深度」单独暴露出来 |
| 6 | 深度清屏 1.0 → **0.0** | 🔖 **地形出现** | —— 根因确认 |

**代价**：18 趟客户端里 6 趟 purely 在找这个 0.0/1.0。**教训**：自建深度附件时，
**第一件事就是去读原版 clear pass 的清屏值**，而不是按 Vulkan 惯例猜。

## 6. 🔴 必须撤回的一条旧结论：本机**没有** validation layer

h02（以及本轮早期）多次写「0 validation error」。实测本机 prefix 与系统里
**都没有 `VK_LAYER_KHRONOS_validation`**，也**没有** `VK_LAYER` 环境变量。
`GpuDevice#getLastDebugMessages()` 实测返回空（本项目的 lavapipe 后端没有接调试消息通道）。

⇒ **「日志里没有 validation error」在本机不是任何证据**：
它既不能证明配置正确，也不能排除绑定缺失之类的问题。
⚠️ **2026-10-03 晚修正**：「附件数不匹配」这一类**其实是响亮失败** —— `h05` 实测
`FrontendRenderPass#setPipeline` 会校验「render pass 颜色附件数 == 管线颜色目标数」并抛
`IllegalStateException`。**真正静默的是深度清屏值**（没有任何一层检查它）。两类可诊断性不同，不要混记。
本轮 §5 里「附件数不匹配」之所以能一路静默到像素层面，就是这个原因。

已同步：`evidence/h02-mrt-primitive.md` 加了同样的撤回说明。

## 7. 本轮踩到并修掉的其他坑（都值得登记）

### ① 「残留游戏进程数=0」曾是我贴的**假零**

我用 `pgrep -f "net.minecraft.client.main.Main"` 计数，但 NeoForge devlaunch 起的客户端
**命令行主类是 `net.neoforged.devlaunch.Main`**（真正的 MC 主类由它反射调用，不在命令行里）
⇒ 模式打不中任何东西；更糟的是 `pgrep -f` 会**匹配到我自己那条 grep**，于是 `-c` 又数出 2。
假零的后果很实：两个客户端并存抢 `run/saves/New World/session.lock`，
后启动的那个**开不了世界**，白跑一整趟。

→ 本机新增 `tools/vulkan-local/game_procs.sh {list|count|kill}`（按 `-Dfml.modFolders=vkdisp` 匹配，
`kill` 会等退出、必要时升级 SIGKILL；🔴 `/tools/` 不入库，判据见 §11 的规则本身），
并登记为 **X33**；本轮该轮此前的「残留=0」作废。

### ② 诊断探针不能跑在首帧

反射探针挂在「第一次执行」上，而首帧区块还没网格化 ⇒ 必然打出 `groups=0`，
看起来像「捕获到的数据是空的」。已改成第 300 / 1200 帧再探。

### ③ 槽 0 的「指纹色」恰好是黑色，掩盖了故障

`MrtPlan.fingerprintR(0) = 0.0` ⇒ 槽 0 的清屏色是纯黑。于是「**什么都没画**」和
「回读链路坏了」在截图上完全同形。本轮把地形 pass 的清屏色改成逐槽高对比色后，
这两个假设才第一次能被一张截图分开。

### ④ 帧图**装配期 ≠ 执行期**

`FrameGraphSetupEvent` 在 `LevelRenderer#render` 第 **249** 行触发（装配），
而 M-05 的捕获在第 **271-275** 行（`prepareChunkRenders`），pass 体在第 **286** 行
（`frame.execute()`）才执行。我在装配期读 `TerrainDrawCapture.current()` ⇒ 拿到的是
**上一帧**的对象，其 `DynamicGpuBuffer` 切片已被本帧上传环形复用覆盖。
⇒ 捕获必须在 pass 体内读。已有构建期红灯。

### ⑤ 客户端**退出时会回写配置文件**

在游戏运行期间改 `run/config/vkdisp-client.toml`，改动会被关服时的回写覆盖 ⇒
「明明改了却不生效」。另外无节区感知的正则（`^(\s*)enabled = false$`）会把**顶层总开关**
一起改掉，我曾因此把整个 mod 关掉、误判成「地形 pass 不跑」。
→ 本机新增 `tools/vulkan-local/set_cfg.py`（按节区改键），纪律固定为
**先 kill → 再改 → 校验 → 再启动**（X36）。

### ⑥ 失焦自动暂停毁掉了取证窗口

窗口失焦 ⇒ 单人游戏自动暂停 ⇒ 关卡不再渲染 ⇒ 截图拍到的是暂停菜单。
取证期间把 `run/options.txt` 的 `pauseOnLostFocus` 临时置 false，收尾已还原。

## 8. 本轮明确**没有**证明的事（防误读）

| 没证明 | 为什么 |
|---|---|
| ⛔ colortex1/2 里有正确内容 | 原版 `core/terrain.fsh` 只写 location 0 ⇒ 槽 1/2 是清屏色。真正的 gbuffer 语义（法线 / 材质）要等包的自研 `gbuffers_terrain` 被翻译接入 |
| ⛔ 画面有任何改进 | 主目标由原版绘制；我方 colortex 只有诊断回读看得到 |
| ⛔ 半透明地形（TRANSLUCENT 组） | 本轮只画 `ChunkSectionLayerGroup.OPAQUE`（实测 `draws=0`，该组尚无内容） |
| ⛔ 特性 / 云 / 天气 / 世界边界 | 仍在原版 pass 里，牵连面刻意收到最小 |
| ✅ 帧图内绘制可用 | ✅ **2026-10-03 晚已重测通过**（见 §9 的更正与 `h05`）。本条在原文是「⛔ 未证明」，现已推翻 |
| ⛔ 性能 | 开启时地形被画两遍，**代价未测**（B1 待办） |

## 9. ✅ 更正：帧图**内**绘制其实是通的（原文结论作废）

🔴 **原文写的是**：「`mrt.terrainAfterLevel=false` 时**从来没出过地形**，原因未区分」。

**这条结论是错的**，两个原因：

1. 🔴 **它建立在深度修复*之前*的观察上**。§5 那个反向 Z 的根因是在本轮后半段才修的；
   帧图模式的几次观察全部发生在修复**之前**，修完后**没有重测**过帧图模式
   ⇒ 把「同一个深度 bug 的另一种表现」当成了「帧图模式独有的问题」。
   **这违反本项目自己的铁律**：改了共享状态（深度清屏值）之后，
   所有此前的「某路径不通」结论都必须**重测**，不能默认继承。

2. 🔴 **它被一个显示 bug 掩盖了**：回读 blit 用了 `fullscreen_flipv`
   ⇒ 帧图模式其实**画出了**地形，只是回读时整体上下颠倒，
   而「颠倒的地形」我当时读成了「地形挂在天上、下面一片清屏色 = 没画出来」。

✅ **2026-10-03 晚重测结论（`h05`）**：`terrainAfterLevel=false`（帧图内插 pass）
**正常工作**，地形朝向正确。附加实测事实：

| 事实 | 值 |
|---|---|
| 我方 pass 在帧图里的执行位置 | 🔖 **排在原版主 pass 之前**（`ORDER-MARK` 日志行号 1161 < 1163：`AfterOpaqueBlocks` 在 `executeSolid` 内触发） |
| draw 数据是否充足 | ✅ `SOLID{groups=1,draws=495} CUTOUT{groups=1,draws=466} TRANSLUCENT{groups=1,draws=432}` |

⇒ **方案 A 的生产形态（「帧图内插 pass」）已通**，
`terrainAfterLevel` 这个 A/B 开关可以退役（保留无害）。

## 10. 新增的构建期守卫

`MrtTerrainPassWiringTest`（9 条）守的都是**静默失效**：

| 断言 | 守住的坑 |
|---|---|
| `pass.disableCulling()` 恰好 1 处 | 帧图会剔除「产出未被消费」的 pass，**且不报错** |
| 活动标记在 `try/finally` 里清 | 漏清 ⇒ 原版 pass 拿到多附件管线 |
| M-01 在 MRT pass 内取 `DERIVED_MRT` | 两套管线混用 |
| 附件数取 `MrtPlan.slotCount()`（单点真源，且**不用**写死的 `SLOT_COUNT`） | 两侧脱钩 ⇒ 附件数不匹配 ⇒ **静默失效** |
| 🔖 自建深度清到 **0.0**、**禁止** 1.0 | 反向 Z 的远/近平面搞反 ⇒ 地形全被深度测试掉 |
| 回读槽数取**地形 pass** 的 | 探针那套未建（=0）⇒ 每帧抛异常 |
| 捕获在 pass 体（执行期）读 | 装配期读 ⇒ 拿到上一帧已被环形复用覆盖的数据 |
| `toMain` 诊断模式下**不做** colortex 回读 | 会把刚画进主目标的唯一证据覆盖掉 |
| `mrt.terrain` 默认 `false` | 诊断路径不进常规帧 |
| 只画 OPAQUE + 不写主目标 | 牵连面失控 |

## 11. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| `run/config/vkdisp-client.toml` | `shaderPack` → `"none"`；`mrt.*` 多个开关 → `true` | ✅ 收尾 `shaderPack = "BSL_v10.1.8"`；`mrt.enabled/terrain/terrainAfterLevel/terrainToMain/terrainFullscreenProbe = false`、`viewSlot = 0`、`attachments = 3` |
| `run/options.txt` | `pauseOnLostFocus` → `false` | ✅ 收尾 `true` |
| `run/config/vkdisp-pack-options.properties` | **未改动** | — |
| 游戏进程 | **18 趟** runClient | ✅ 每趟 `tools/vulkan-local/game_procs.sh kill`；收尾**残留 = 0** |
| 新增工具 | `tools/vulkan-local/game_procs.sh`、`tools/vulkan-local/set_cfg.py` | 🔴 **本机脚本，未入库** —— `.gitignore` 第 80 行 `/tools/` 整个目录不入库（与既有的
`env.sh` / `x11_capture.py` 同处境）。换机器要照 `AGENT_CONTEXT.md` §9.4.14 与 `07-CONSTRAINTS`
X33/X36 的**判据**自行实现，脚本本身不是仓库资产 |