# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。

---

## 2026-09-29 — P0.3 首个可见产物（全屏图案上屏）+ 闸门 F1/F2/F3/F4 落地

- **本次改了什么**：
  1. **P0.3 实现**（impl-coder，task-2）：新增 `bridge/PipelineApi.java`（用原版 `RenderPipelines.POST_PROCESSING_SNIPPET` 构建并注册全屏管线）、`bridge/FrameApi.java`（按原版 `PostPass` 序列 `createRenderPass → setPipeline → bindDefaultUniforms → draw(3,1,0,0)` 绘制）、`render/FullscreenPipelineRegistrar.java`（mod bus `RegisterRenderPipelinesEvent` 接线）、`render/FullscreenPassHook.java`（game bus `RenderFrameEvent.Post` 接线）、`assets/vkdisp/shaders/fullscreen.vsh|.fsh`（`gl_VertexIndex` 全屏三角形 + 品红/青棋盘图案）。
  2. **P0.3 复验缺陷修复**（lead）：三轮 runClient 实测定位并修掉两处启动期 ERROR：
     - run1：`FrameApi.drawFullscreen` 把「管线尚未编译完成」当致命失败，早期帧刷 **11 条** `vkdisp: fullscreen pass failed` ERROR（违反 `01-DEV-LOOP.md` §9「日志无 ERROR」）；
     - run2：首修后改为每帧轮询 `getCompiledPipelineNullable`，却撞上原版启动窗口期的 **fallback PipelineCache**（`GameRenderer.preloadUiShader`，绑定旧 ResourceManager），每帧触发一次失败加载 → 原版记 **24 条** `Couldn't preload shader vkdisp:shaders/fullscreen.vsh` ERROR；
     - run3：改用官方 `ClientResourceLoadFinishedEvent` 作门闩（GLSL 编译属资源重载的一部分，此刻管线缓存才就绪），重载完成前完全不触碰管线缓存 → **ERROR 全部清零**。
  3. **闸门 F1**（bridge 契约冻结）：`ContractVersion`（版本常量 + §3.2 变更流程）、`RenderApi`（`PipelineSpec` + 注册/查询签名）、`TextureApi`（`TextureView` + 主目标视图）、`MixinTargets`（mixin 目标常量集中表），与既有 `DeviceApi`/`FrameApi`/`PipelineApi` 凑齐 `06-MIGRATION.md` §2.1 的 5 接口。
  4. **闸门 F2**（`pack/` 数据模型冻结，contract-pack）：`ShaderPack`/`Program`/`ProgramStage`/`Option`/`OptionType`/`Dimension`/`VertexAttribute`/`UniformDecl` 共 8 类，全部 record/enum + 构造校验 + 不可变集合。
  5. **闸门 F3**（`glsl/` 契约冻结，contract-glsl）：`TranslateResult`（文本 + 诊断 + 行号映射）、`TranslateDiagnostic`（severity/原文件/行/列）、`SourceLineMap`（双向逐行查询 + `compose` 端到端合成）。
  6. **闸门 F4**（测试基建）：`build.gradle` 接 JUnit 5（BOM 5.13.4）+ `test` 任务启用 JUnit Platform；`src/test/` 骨架 + 冒烟测试 + `src/test/resources/packs/README.md`（fixture 许可证限制）。
  7. **E 线（管线纯计算件）**（line-e，task-7）：`pipeline/model/` 14 个文件 —— `VertexLayout`（04-SPEC §4 逐项 offset/size/**stride=47** + `isConsistent()`）、`PipelineCacheKey` + `PipelineSpecIr` + `CanonicalText`（长度前缀单射编码 + SHA-256 指纹，**属性类型变化也换键** = T9「彩色尖刺」单测闸门）、`BindGroupLayoutIr`（可打印/可回读的绑定布局 IR）、`ModelDiagnostic`（显式诊断）。
  8. **D 线（GLSL 转译）**（line-d，task-6）：`glsl/translate/` 9 主 + 4 测 —— `OfGlslTranslator`（编排 + `dMap.compose(cMap)` 端到端行号映射）、`AttributeRewriter`（顶点 `attribute→in`/`varying→out`、片元 `varying→in`，等行数重写）、`UniformInjector` + `UniformCatalog`（04-SPEC §3.2 **23 条**内建 uniform 只补缺失、注入点在头部之后）、`ShaderStage`/`GlslDeclaration`/`CommentState`/`SourceLines`（注释等长空格化保列位）。
  9. **文档复核**：`docs/04-SPEC.md` §4 增加复核注记 —— 核实 OF 官方属性表（来源：OptiFine 规范文档 `shaders.txt`「Attributes」节，仅取格式事实零文本搬用）：`mc_Entity` 官方为 **vec3**（非 §4 的 vec2s）、`vaUV1`=overlay / `vaUV2`=lightmap（§4 用途有误）、`at_*` 三项存在；**底层元素类型文档未给 → 禁止猜值**（07 X9），留 P1.2 实测定稿（`18-PARALLEL` §10 P-1d）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 的 P0.3 完成标准「屏幕上出现自定义全屏 pass 画出的图案（非黑屏、非崩）」；`docs/18-PARALLEL.md` §3 要求先冻结 F1–F4 契约闸门，A–F 并行线才可开工（F2/F3 为 C/D/E/F 的共同输入）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §3.0 现状快照（F1–F4 全部 ✅、已解锁并行线更新）与 §10 待办 P-1/P-1b/P-1c/P-1d；`docs/04-SPEC.md` §4（新增复核注记：OF 官方属性表出入 + 未定项）。其余 `docs/01`–`17` 正文未改动。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/` 已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` → `BUILD SUCCESSFUL`，exit=**0**。
  - ✅ **产物六项核对**：`.class` 数=**10**；含 `META-INF/neoforge.mods.toml`；含 `LICENSE`；禁列 `net/minecraft`/`com/mojang`/`net/caffeinemc`/`dev/vitrail` = **0**。
  - ✅ **P0.3 可见产物（run3 最终代码）**：`p03_final_vulkan_clean.log`（sha256 `e2e9630f…47de9`）——L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1`；L60 `vkdisp: pipeline registered (count=1): vkdisp:pipeline/fullscreen`；L136 `vkdisp: client resources loaded (initial=true), fullscreen pass enabled`；**L137 `vkdisp fullscreen pass executed (854x480)`**。
  - ✅ **截图**：`p03_mainmenu_final_a.png` / `_b.png`（930x577，sha256 `820d23eae4641c5b192c700313063f7517320c6d0414b14317ca58b933d8a6ff`，两窗口像素统计 mean=196.78 非黑屏）；图案=品红/青 8×8 棋盘 + 黄色四角标 + 白色中轴十字 + 黑色边框，全屏覆盖主菜单。
  - ✅ **ERROR 收敛三轮对比**：run1 = 11 条我方 ERROR（`p03_run1_startup_errors.log` sha256 `caaebca6…388181`）→ run2 = 24 条原版预加载 ERROR（`p03_run2_vanilla_preload_errors.log`）→ run3 = **0 条**；run3 `Couldn't preload shader` = **0**、`Mixin apply failed` = **0**、`FATAL` = **0**。
  - ⚠️ **ERROR=2（环境性，非本项目）**：`Narrator` 加载 `libflite.so` 失败（WSL 无 TTS）、`SoundEngine` `Failed to open OpenAL device`（WSL 无声卡）——与 P0.1/P0.2 同一批已知环境缺失。
  - ✅ **闸门 F4 测试**：`./gradlew test` → `F4InfraSmokeTest` **2/2 PASSED**，exit=0。
  - ✅ **E 线单测**：`./gradlew test` exit=0，E 线 5 个测试类 **64 用例 0 失败**（VertexLayoutTest 22 / BindGroupLayoutIrTest 16 / PipelineCacheKeyTest 15 / VertexElementFormatTest 6 / PipelineModelIntegrationTest 5），全仓库合计 **100 用例 0 失败**；数值断言逐项 offset `0,12,16,24,28,32,35,39`、size `12,4,8,4,4,3,4,8`、stride `47`；8 个不同 Program → 8 个不同键文本与指纹；文本篡改逐项显式 ERROR。
  - ✅ **D 线单测**：`./gradlew test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`，D 线 **49/49**（AttributeRewriterTest 19 / OfGlslTranslatorTest 14 / UniformInjectorTest 12 / UniformCatalogTest 4），全仓库合计 **115 用例 0 失败**；幂等以文本为不动点（golden 两轮逐字节相同、二轮零诊断）；诊断回填验证（片元 `attribute` 的 ERROR 经 `SourceLineMap` 定位到 `shaders/lib/common.glsl:57`）；16 例 hostile 输入无异常逃逸。
  - ✅ **闸门 F2/F3 自检**（各线成员执行）：`./gradlew compileJava` exit=0；红线 `grep -rn "com\.mojang\.\(renderpearl\|blaze3d\)"` 于 `pack/`、`glsl/`、`translate/` 均 **NO MATCH**；F3 另跑独立行为冒烟 **35 条断言全绿**。
  - **GAP 登记**：**不需要**——P0.3 全程使用官方事件（`RegisterRenderPipelinesEvent` / `RenderFrameEvent.Post` / `ClientResourceLoadFinishedEvent`），无自行补充。
  - 本轮无性能改动（P0.3 冷路径，每帧一次 draw），`17-NATIVE.md` §2 性能预算不适用。
- **未覆盖 / 存疑**（不掩饰）：
  1. P0.3 仅在**主菜单**截图验收（用户本轮指定口径），未进世界复核图案与世界渲染的叠加顺序；
  2. 管线按 **required** 注册：真编译失败表现为原版资源重载硬失败（红屏 + `Failed to load required shader programs`），而非我方 ERROR 路径——属刻意选择（失败绝不静默），排查入口已写入类注释；
  3. F2 上报的 `04-SPEC.md` §4 出入（`mc_Entity` 记 vec2s vs OF 官方 vec3；UV1/UV2 用途描述）**未当场判定**，已登记为待办（影响 E 线 stride 表），不许用猜的值填（07 X9）；
  4. **E 线未覆盖**：F1 适配方法 `PipelineSpecIr.of(RenderApi.PipelineSpec, String)` 只有 main 源集编译证据——F4 的 test 源集 classpath 不含 Minecraft 类型，单测无法构造；是否把 MC 加入 test 源集属共享文件改动，待 env-1 决定（已登记）；与主线真实 binding 的双侧 stride 比对留 P1.2；无 binding>0 多槽用例。
  5. **D 线已知限制**（该线自报）：幂等以文本为准（插入行时行号映射按 F3 语义必然变化，未断言）；`gl_FragColor`/`texture2D`/`ftransform` 等 gl_ 内建差异**未实现**（不在本任务完成标准内，若要归 P2.3 另开任务）；多行声明 / 同行多名 uniform / UBO 块内同名 / `#if 0` 头部为未覆盖边界。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行，成员不自行 commit）。

---

## 2026-09-29 — 新增并行开发路线（18-PARALLEL）+ 索引同步 + .gitignore 完善

- **本次改了什么**：
  1. 新增 `docs/18-PARALLEL.md`：把关键路径（P0.2→P4.3）之外的工作拆成 6 条**可并行线**（A–F），定义唯一并行判据（内存数据进出 / 不触 `renderpearl` / 不需 `runClient` / 单测可断言，**四条缺一不可**）、契约冻结闸门 F1–F4、以及**边界与限制**（硬边界 10 条「不许」、共享文件清单、并行线证据规范、红线映射、fixture 许可证限制、冷路径性能纪律）。
  2. `docs/00-INDEX.md` 两处同步登记 18（§1 阅读顺序 + §2 文档表）。
  3. `.gitignore` 完善为 12 分节：新增 `/shaderpacks/`（第三方 pack 不入库，对齐 18-PARALLEL §7.6）、`runs/` `run-data/` `/crash-reports/` `/logs/`、`hs_err_pid*.log` `*.hprof`、NetBeans / Visual Studio / Windows / macOS / Linux 各套垃圾文件、`.env` `local.properties`、`/*.log` `*.tmp` `.cache/`、`.workbuddy/`；末尾新增「必须入库文件清单」注释块（防误伤 `gradle/wrapper/gradle-wrapper.jar`）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 的 P0.x–P4.x 是一条单链，关键路径上每一步都要 GPU 证据、只能在单环境串行；而自研四件事中三件（解析 / 转译 / GUI 数据层）是冷路径、本不需要 GPU —— 把这部分提前并行，可消掉 P2 阶段的串行工期（`04-SPEC.md` §7 把「GLSL 转译工作量被低估」列为**高风险**项，越早开工越好）。
- **影响的文档**：新增 `docs/18-PARALLEL.md`；修改 `docs/00-INDEX.md`、`.gitignore`、本 `CHANGE_LOG.md`。**未改动 `docs/01`–`17` 任何正文**（18 §7.5 已就「一次只做一个」的适用口径做澄清，无需修改 01）。
- **测试结果**：
  - ✅ **并行判据与红线一致性核查**：18 §2 的四条判据以 `07-CONSTRAINTS.md` **T5**（业务包禁止 import 原版渲染类型）为技术依据，二者不冲突；§7.4 已把 L12 / X19 / X20 / X21 / T11 / T12 / T13 / X12 / X17 / T14 逐条映射到并行线场景。
  - ✅ **`.gitignore` 规则验证**（`git check-ignore -v` 逐条）：13 个必须入库文件（`gradle-wrapper.jar`、`gradlew`、`gradle.properties`、`LICENSE`、`TEMPLATE_LICENSE.txt`、`.gitattributes`、`docs/**` 等）全部**未被命中**；14 个应忽略路径（`build/libs/*.jar`、`run/logs/*`、`tools/**`、`.workbuddy/**`、`.idea/`、`Thumbs.db`、`.DS_Store`、`hs_err_pid*.log`、`.env` 等）**全部命中正确规则**。
  - ✅ **行尾复核**：`.gitignore` 等均为 LF（python 字节统计 `CRLF=0`），符合 `.gitattributes` 的 `eol=lf`。（注：本次曾用 `grep -c $'\r'` 得出「全仓库 CRLF」的**假阳性**，经 `od -c` + 字节统计纠正；可靠判据是 `git ls-files --eol`。）
  - ✅ **闸门现状核实（对应 `83a704d`）**：**F1 = 🟡 部分落地** —— `bridge/DeviceApi.java` 已就位且范式正确（内层 import `renderpearl`、对外仅暴露纯 Java `record DeviceInfoView`，`grep -rl` 全仓库仅此 1 个文件命中）；**F2 / F3 / F4 = ❌ 未开始**（`src/` 仅 4 个 java 文件、无 `src/test/`、`build.gradle` 无 JUnit）。结论已写入 18 §3.0「现状快照」。
  - 本轮为**纯文档 + 忽略规则**改动，不涉及运行时代码；`08-TESTING.md` 阶段验收与 `17-NATIVE.md` §2 性能预算均不适用。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-29 — P0.2 确认跑在 Vulkan 后端（backend/device 断言）+ bridge 隔离落地

- **本次改了什么**：
  1. 新增 `src/main/java/dev/vkdisp/bridge/DeviceApi.java` —— 原版渲染 API 唯一入口（`docs/06-MIGRATION.md` §2 bridge 隔离）：暴露纯 Java 视图 `DeviceInfoView`（record）与 `backendKind()` / `deviceInfo()`；设备未就绪返回 `"UNKNOWN"` / 抛明确异常，绝不静默。
  2. `VkDispClient` 增加 P0.2 关键断言日志 `vkdisp: backend={}, device={}`（`event.enqueueWork` 保证在渲染线程、设备创建后执行；后端不是 Vulkan 就打 ERROR，不静默）。
  3. `build.gradle` 的 runClient 强制 `programArguments '--graphicsBackend','VULKAN'`（DEFAULT 顺序先试 GL，llvmpipe 可用即被选中，永远轮不到 Vulkan，故必须显式强制）。
  4. 按 api-scout 只读复核意见修复 3 处（task-5）：`VkDispClient` 【参考调研】块补记 P0.2 断言调研出处（08-TESTING §2 + 原版 Minecraft 529-531 用法范本 + task-4 调研）；`logBackendAssertion` 先判 `DeviceApi.deviceReady()`，设备未就绪打 ERROR 即返回（消除「UNKNOWN 之后必抛异常」连带路径）；`docs/08-TESTING.md` §2 补值域注记（官方 backendName 原值 `"Vulkan"/"OpenGL"` 首字母大写，勿写 `"VULKAN"` 导致失配）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 P0.2 完成标准「日志打印出后端类型与设备名，且不是 OPENGL」；`docs/08-TESTING.md` §2 关键断言「backend=VULKAN（绝不能是 OPENGL）」；`docs/06-MIGRATION.md` §2 要求原版渲染 API 访问集中在 bridge 一处。
- **影响的文档**：本 `CHANGE_LOG.md`（新增本条目 + 附录自检表第 1/2/3/10/11/14/15 行更新）；`docs/08-TESTING.md` §2（+2 行：期望值改为 `backend=Vulkan`、补 backendName 值域注记）。
- **测试结果**（运行日志证据：`tools/vulkan-local/evidence/latest_p02_vulkan.log`，sha256 `f740e8e210d4c6ae85e953dfa54bf80fd9b2e213118350de4cbcf2c9e44de072`，该目录已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` `BUILD SUCCESSFUL`，exit=**0**。
  - ✅ **产物六项核对**：jar=`build/libs/vkdisp-0.1.0.jar`，`.class` 数=**5**（含 bridge 2 个：`DeviceApi.class` + `DeviceApi$DeviceInfoView.class`）；`LICENSE` 在 jar 内；禁列 `net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/` = **0/0/0/0**。
  - ✅ **bridge 隔离**：`grep -rl 'com\.mojang\.\(renderpearl\|blaze3d\)' src/main/java` → 仅 1 个文件 `src/main/java/dev/vkdisp/bridge/DeviceApi.java`。
  - ✅ **P0.2 关键断言（backend=Vulkan，绝不是 OpenGL）**：L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1 (LLVM 22.1.8)`；L94 `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)`；L93 `vkdisp: client setup, user=Dev`。
  - ✅ **后端强制生效**：L50 WARN `Graphics backend forced to vulkan by launch argument, in-game preferred graphics backend setting is ignored`（`--graphicsBackend VULKAN`）。
  - ✅ **完整游玩会话（加分证据）**：Vulkan 后端下进入单人世界 `New World` 游玩后正常退出，17:52:38 `Stopping!` 干净收尾，**全程无 vkdisp 错误**。
  - ✅ **最终代码复验**（含上述第 4 项复核修复的提交版本，加载完成后停止测试客户端）：`tools/vulkan-local/evidence/latest_p02_vulkan_final.log`，sha256 `c4aeb704f6b83dfc3633a417eae732bb886bab8376f3bb0f94748bd57b1f1a50`；L50 forced WARN、L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1 (LLVM 22.1.8)`、L94 `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)`；ERROR=**2**（同两条环境缺失）、FATAL=**0**、`Mixin apply failed`=**0**、vkdisp ERROR=**0**。（前一条 `f740e8e2…` 为同日首次 P0.2 验证运行，含完整游玩会话。）
  - ⚠️ **错误分类**：ERROR=**2** 条，均为已知环境缺失（narrator `libflite.so`、OpenAL 设备），FATAL=**0**，`Mixin apply failed`=**0**，vkdisp 相关 ERROR=**0**。
  - **GAP 登记**：调研结论为**不需要登记**（官方 `renderpearl.backend.api` 机制完整，无自补特性）。
  - 本轮无性能改动，`17-NATIVE.md` §2 性能预算不适用。
- **是否已提交**：是：随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行，成员不自行 commit）。

---

## 2026-09-29 — P0.1 构建与产物核验 + runClient 运行验证

- **本次改了什么**：
  1. P0.1 验收的构建环节：`./gradlew build` 并对 `build/libs/*.jar` 做六项产物核对（class 数 / neoforge.mods.toml+modId / LICENSE / 不含 net/minecraft、com/mojang、net/caffeinemc、dev/vitrail 等）。
  2. P0.1 验收的运行环节：`./gradlew runClient` 真实启动到主菜单并取证（WSL2 免 root 方案：本地解压 Vulkan loader + lavapipe ICD，经 LD_LIBRARY_PATH / VK_DRIVER_FILES 注入；临时产物在 `tools/vulkan-local` 与 `run/`，不在仓库根留日志）。
  3. 本轮收尾整洁工作：删除仓库根全部 `*.log` 临时构建日志；核对 `.gitignore` 覆盖 `/build*.log`、`/build_*.log`（另有 `*.build.log`）；新建本 `CHANGE_LOG.md`；预跑 `docs/07-CONSTRAINTS.md` §七 中不需运行证据的静态自检项（见文末附录）。
  4. runClient 实测发现并修复 1 处缺陷：lang 文件缺 `vkdisp.configuration.*.tooltip` 键（游戏日志告警「The following keys have fallbacks...」）→ 为 `en_us.json` / `zh_cn.json` 各补 2 个 tooltip 键，随后重新 `./gradlew build` + `runClient` 复验，告警消失。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 对 P0.1 的完成标准是「`./gradlew build` 退出码 0；`./gradlew runClient` 进主菜单；日志无 `Mixin apply failed`」；`docs/15-ITERATION.md` 要求每次迭代落变更记录，`docs/07-CONSTRAINTS.md` §七 要求每次提交前过自检清单。
- **影响的文档**：新增根目录 `CHANGE_LOG.md`（本轮唯一新增/修改的文档）。本轮未改动 `docs/` 下任何文件。
- **测试结果**（2026-09-29 实测回填；证据快照在 `tools/vulkan-local/evidence/`：`stage_a.log`、`stage_b*.log`、`latest*.log`、`mainmenu.png`、`titlemenu.png`，该目录已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` 退出码 **0**（`BUILD SUCCESSFUL`；完整构建 1m35s，之后增量复验 4s/1s）。
  - ✅ **产物六项核对**：`build/libs/vkdisp-0.1.0.jar` —— `.class` 数=**3**（`VkDisp`/`VkDispClient`/`VkDispConfig`）；含 `META-INF/neoforge.mods.toml` 且 `modId="vkdisp"` == `gradle.properties:24`；含 `LICENSE`（`build.gradle` 新增 `tasks.named('jar', Jar).configure { from('LICENSE') }` 后复验通过，T3 报告第 3 项硬伤闭合）；`net/minecraft`、`com/mojang`、`net/caffeinemc`、`dev/vitrail` 各 **0** 命中。
  - ✅ **runClient 进主菜单**：17:07 启动 → 17:08:12 图集/资源就绪（`Loaded 0 entity animations`）→ 窗口 `Minecraft NeoForge* 26.3` 854x480；截图 `tools/vulkan-local/evidence/titlemenu.png`（sha256 `8a62d4ba...de50aff`，854x480，全象限高色彩多样性非黑屏）。首启拦截过一次无障碍引导屏（`AccessibilityOnboardingScreen`，`onboardAccessibility:false` 已落 `run/options.txt`），第二次复验直达主菜单。
  - ✅ **埋点**：`latest.log:92` `vkdisp: client setup, user=Dev`；Mod List 含 `Vulkan Shader Dispatcher 0.1.0 (vkdisp)`。
  - ✅ **静默失败扫描**：`Mixin apply failed`=0；`FATAL`=0；无 `crash-reports/`。
  - ⚠️ **ERROR=2 条，均为环境性、零 vkdisp 栈帧、游戏继续正常**：① `com.mojang.text2speech.Narrator` 加载 `libflite.so` 失败（WSL 无 TTS 库）；② `SoundEngine` `Failed to open OpenAL device`（WSL 无声卡）→ 原版自动 `Turning off sounds & music`。
  - ✅ **退出干净**：`Stopping!` → `Closing FML Loader` → `Clearing ModLoader`，Gradle 任务 `BUILD SUCCESSFUL` exit 0。
  - ✅ 静态自检（§七 不需运行证据的项）：结果见文末附录 —— 原 1 项不通过（【参考调研】缺第 0 条）已修复，现全过/不适用。
  - 本轮无性能改动，`17-NATIVE.md` §2 性能预算不适用。
- **是否已提交**：是：随本条目一并 commit 并推送至 `origin/master`。

---

## 附录：docs/07-CONSTRAINTS.md §七 提交前自检清单 —— 静态项预跑（2026-09-29，执行人 docs-upkeep）

> 全部项已回填（2026-09-29 实测）；表内证据已按 P0.2 复验更新（第 1/2/3/10/11/14/15 行）。

| # | 清单项 | 结果 | 证据 / 说明 |
|---|---|---|---|
| 1 | gradlew build 通过，jar 含 class | 通过 | P0.2 复验：`./gradlew build` EXIT=0（BUILD SUCCESSFUL）；jar 内 **5** 个 class（`VkDisp`/`VkDispClient`/`VkDispConfig` + bridge `DeviceApi`/`DeviceApi$DeviceInfoView`） |
| 2 | jar 含 LICENSE（MIT 署名要求） | 通过 | P0.2 复验：jar 根级含 `LICENSE`（`build.gradle` 的 `tasks.named('jar', Jar).configure { from('LICENSE') }` 持续生效） |
| 3 | jar 不含 net/caffeinemc、net/minecraft、dev/vitrail、com/mojang | 通过 | P0.2 复验：禁列 4 包（`net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/`）= 0/0/0/0 |
| 4 | mixins.json 是 JAVA_25 | 不适用 | `find . -name '*mixins*' -not -path './build/*' -not -path './.git/*'` 结果为空：本工程尚无 mixins.json；`neoforge.mods.toml:48-49` 的 `#[[mixins]]`/`#config` 均为注释态 |
| 5 | 无硬编码版本号（都走 gradle.properties） | 通过（附注） | `gradle.properties`：`minecraft_version=26.3`、`neo_version=26.3.0.23-beta`、`mod_version=0.1.0`、`mod_license=MIT`；`build.gradle:17` `version = mod_version`、`:42` `version = project.neo_version`、`:141-148` 全部走属性。附注：仅剩 plugins DSL 常量 `net.neoforged.moddev '2.0.147'`（build.gradle:4）、`foojay-resolver-convention '1.0.0'`（settings.gradle:9）与 toolchain `JavaLanguageVersion.of(25)`（build.gradle:38），三项均为 `docs/05-VERSION.md` §2 已登记版本（Gradle plugins DSL 不接受属性插值）；**Lead 复核结论：按此口径判通过（2026-09-29）** |
| 6 | 无复制来的第三方代码 | 通过 | `src/main/java/dev/vkdisp/` 4 个文件（`VkDisp`/`VkDispClient`/`VkDispConfig` 手写骨架 + `bridge/DeviceApi.java` 26.3 设备 API 薄封装，均为手写、零源码搬运）+ lang/mods.toml 资源；MDK 模板来源在根目录 `TEMPLATE_LICENSE.txt` 声明（"This license applies to the template files as supplied by github.com/NeoForged/MDK"，MIT） |
| 7 | 对外文字无「Sodium 替代品 / Iris 兼容 / OptiFine 官方」类表述 | 通过（1 处边界表述经 Lead 复核） | `grep -rniE 'sodium|optifine|iris' README.md src/main/templates src/main/resources` 无 sodium；唯一边界为 `neoforge.mods.toml:42`「兼容 OptiFine / Iris 格式着色器包」（讲的是**格式**，非模组背书），`README.md:6` 明确「独立实现，不与任何第三方渲染优化模组或着色器加载器做集成」。**Lead 复核结论：属格式描述、非模组背书，判通过（2026-09-29）** |
| 8 | 构建脚本无 sodium / caffeinemc 坐标，无运行时探测/集成分支 | 通过 | `grep -rniE 'sodium|caffeinemc' build.gradle settings.gradle gradle.properties gradle/ gradlew` → `NO MATCH` |
| 9 | 代码里 sodium 只出现在否定式语句 | 通过 | `grep -rni 'sodium' src/` → `NO MATCH`（一次都没出现，天然满足） |
| 10 | 新增的 GPU 操作走 renderpearl | 不适用 | P0.2 仍无 GPU 操作代码：`bridge/DeviceApi.java` 仅做后端/设备信息查询（`RenderSystem.tryGetDevice()` + 官方 `DeviceInfo`），不发 GPU 命令 |
| 11 | 业务包没有 import com.mojang.renderpearl.* | 通过 | P0.2 口径：`grep -rl 'com\.mojang\.\(renderpearl\|blaze3d\)' src/main/java` → 仅 `src/main/java/dev/vkdisp/bridge/DeviceApi.java`；原版类型引用全部收敛在 bridge 隔离包（`06-MIGRATION.md` §2），业务包（bridge 之外）零 import |
| 12 | 每个 mixin 注入点有日志 | 不适用 | 无 mixin（见第 4 行） |
| 13 | 顶点 stride 有断言 | 不适用 | 无顶点/渲染相关代码 |
| 14 | 新增模块有【参考调研】注释块（T13） | 通过 | 4 处：`VkDisp.java:3`、`VkDispClient.java:3`、`VkDispConfig.java:3`、`bridge/DeviceApi.java:2`（P0.2 新增）均有「【参考调研】」块 |
| 15 | 🔴【参考调研】**第 0 条**写了合规结论，且不是「未核实」 | 通过 | 4 处均有第 0 条合规结论：3 处原有（MDK 模板 MIT 可并入、无 LGPL/GPL/ARR、无例外条款）+ `bridge/DeviceApi.java`（参考=原版 renderpearl 设备 API，仅观察 javap 签名与官方调用点、零源码搬运；Mojang EULA/NeoForge LGPL 不并入代码，薄封装可并入，无例外条款） |
| 16 | 🔴 所有参考项目都查过仓库的 LICENSE 文件 | 通过 | 唯一参考 = NeoForge MDK，其 LICENSE 正文即仓库根 `TEMPLATE_LICENSE.txt`：「MIT License / Copyright (c) 2023 NeoForged project / …template files as supplied by github.com/NeoForged/MDK」（仓库文件级证据，非平台页面） |
| 17 | 🔴 没把「无 LICENSE」当可用、没把「GPL + 例外条款」当可用 | 通过 | 本模块参考仅 MDK（MIT）；无 ARR/GPL 参考进入实现 |
| 18 | 性能相关改动附实测数据（T14） | 不适用 | 本轮无性能改动；注释块第 5 条注明「P0.1 冷路径（启动日志），无优化需求」 |
| 19 | 未擅自开始原生（C++/Rust）实现（X17） | 通过 | `find src -name '*.cpp' -o -name '*.rs' -o -name '*.c' -o -name '*.h'` → 空；`build.gradle:113-135` dependencies 块无任何实际依赖（仅 MDK 示例注释） |
| 20 | 若含原生库：四平台产物 / Java 保底 / A/B 开关 | 不适用 | 无原生库 |
| 21 | 文档已同步 | 通过 | `git status --porcelain` 对 `docs/` 无未提交改动；README 两处死链已修复为 `docs/05-VERSION.md` / `docs/16-READING.md` |

**补充静态发现（非清单项，供 Lead 处理）**：

- `tools/`（本地 Vulkan 前缀与证据快照）：已由 `.gitignore` 新增 `/tools/` 与 `*:Zone.Identifier` 覆盖（本轮落地），不入库。
- 仓库根 `*.log` 已在本轮删除（清理由另一成员执行，commit 前完成）；`.gitignore` 已含 `/build*.log`、`/build_*.log`、`*.build.log`。**`run/` 目录按任务要求保留**（运行证据）。
