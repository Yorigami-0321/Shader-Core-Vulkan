# P4.3 选项 GUI 证据（2026-10-01，八张截图单会话闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- **选项屏幕驱动（与 p416/p417 同源方法，无输入注入）**：外部改
  `run/config/vkdisp-client.toml` 的 `packOptionsScreen` 值 → FML nightconfig FileWatcher
  （500ms 去抖）→ `ModConfigEvent.Reloading` → `VkDispConfigHotReload` **边沿分割**
  （driveChanged → `minecraft.execute` → `PackOptionsDrive.run`，**不触发资源重载**；
  `done` 导致的保存/改写本身会触发重载，两者互不嵌套）。
  语法：`""` = 中性（消费后归零）/ `open` / `set:NAME=VALUE` / `page:N` / `done`。
  `set`/`page`/`done` 要求选项屏幕已打开，否则 WARN `drive set rejected: 选项屏幕未打开`
  拒绝执行（T11，见下方 S8 正向证据）。
- **八步（单会话，BSL 半场 + fixture 半场）**：`open` → `set:SHARPEN=3` → `page:2` →
  `done` → 切 `shaderPack= vkdisp-fixture-dir` → `open` → `set:SHADOW_DARKNESS=0.20` →
  `done`；每步等对应日志行后截图：
  `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`
  （窗口 id 每次启动变化，先 `--list-windows`；本跑 = 0x00201ce7，930×577 depth=32）。
- **判读口径**：crop `(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`。
  fixture 地面带 = **y 510–535、x 60–260**（raw 930×577）——
  这是本轮校正后的带位：旧口径「行 400–520」会掺进黑窗边（y≥545）、hotbar（497–540）
  与纯黑天空（430–490），稀释比值；430–490 是全黑带（除零），仅 510–535 是稳定粉色地面。
  环境：WSL2，llvmpipe（Mesa 26.2.3），存档基线同 p413。

## 机制（本轮实现，字节码核实后落地）

**① GUI 覆盖根因与修复（P0 级，先于选项功能解决）**：

- 原入口 `RenderFrameEvent.Post` 在 `GameRenderer.render()` **返回之后**触发 ——
  此刻 GUI 已画进 main target，我方 final blit（offscreen3 → main）会整屏覆盖 GUI，
  实测图案盖住整个选项屏幕（GUI 层消失）。
- 改挂 `RenderLevelStageEvent.AfterLevel`：post 点在 `GameRenderer#renderLevel` 内、
  `LevelRenderer.render()` 返回**之后** —— 帧图已执行（SceneCaptureApi 已捕获本帧地形）、
  `render3dHud` / `guiRenderer.render()`（GUI 合成）**之前**；我方对 main 的写入先落，
  GUI 随后合成在其上（X9：合并 jar 字节码顺序实测，`FullscreenPassHook` 类【参考调研】第 1–2 条）。
- 实证：全部八张截图 GUI 可见（选项控件、toast、hotbar、准星、手部均合成在着色器输出之上）；
  反证留档 = `p03_mainmenu_pattern.png`（Post 期图案盖住整个主菜单）。
- 连带设计决定：AfterLevel 只在世界内触发 → **菜单态不再绘制全屏 pass**（菜单图案本是
  实测否决的 GUI 覆盖，不是功能）；菜单验收行只需「启动到主菜单，不崩」。
  `FrameApi` 类注释与链路判断注释同步改记。

**② 驱动链与三层快照**：

- 三层：默认值 → profile → store（`vkdisp-pack-options.properties`），开屏差分出
  `changed` 集合；**只有触碰项**进 store，改回基线的条目提交时删除（防固化，单测钉死）。
- `set` 只改会话内存（立即反映到控件）；`done` → `commitAndClose` → store 落盘 →
  `PackCompositeSource.generate(..., store)` 差分出 overrides → `ShaderPackCompiler`
  的 `OptionSourceRewriter`（DEFINE_WITH_VALUE，保留行尾注释）改写**包源文本**并重编译 →
  日志 `选项覆盖已改写进源: 命中 X/Y [..]` → 资源重载生效。
- 真实包 BSL 证据链：`#define SHARPEN -1 //[-1 0 1 2 3 4]`（`shaders/lib/settings.glsl:251`）
  被 `program/composite.glsl` 传递 include，改写命中 1/1。

**③ bytes=24515 疑点关闭（X9 当轮核销，非猜测）**：

| 轮次/取值 | composite bytes | final bytes |
|---|---|---|
| p417 无覆盖（SHARPEN=−1） | 24515 | 5616 |
| p418 覆盖 SHARPEN=4 | 24515 | 5615 |
| p418 覆盖 SHARPEN=3 | 24515 | 5615 |

- `composite` 恒 24515：SHARPEN 在 composite 的 include 图里**只出现在预处理指令行**
  （`#define` 声明 + `#if SHARPEN > 0 || …`，settings.glsl:489），`DefineProcessor`
  输出「只保留被选中分支并删除所有预处理指令」→ 声明行长度不进产物；
  条件两分支等价（见下）→ 字节不动。
- `final` 5616→5615 恰 −1 字节：唯一存活的代码位替换点是 `program/final.glsl:59`
  `float mult = 0.0625 * SHARPEN;`，`-1`（2 字符）→ `3`/`4`（1 字符）= −1 字节，逐轮吻合。
- 分支选择不随取值变：条件 `SHARPEN == -1 && defined FXAA && defined TAA` 里的
  FXAA/TAA 无条件定义（settings.glsl:242–243），唯一 `#undef` 在 `#ifdef RETRO_FILTER`
  内而 RETRO_FILTER 注释态（settings.glsl:466）→ −1/3/4 三态下 `SHARPEN_ENABLED`
  恒真、被选分支逐字节相同。4→3 同长（1 字符）本就不动字节。

## 阶段对照（单会话，`run/logs/p418-run3-full-sequence.log` 行号可复核）

| 时刻 | 动作 → 日志锚（行号） | 截图 |
|---|---|---|
| 18:24:16 | 启动载入：store 遗留 `SHARPEN=4` → `选项覆盖已改写进源: 命中 1/1 [SHARPEN=4]`（L482）→ composite `bytes=24515`（L484）→ final `bytes=5615`（L486） | — |
| 18:24:55→57 | `drive: raw='open'`（L3314）→ `opened: pack=BSL_v10.1.8 options=284 changed=1 profile='' storeEntries=1`（L3317） | **s1**（18:24:57） |
| 18:25:08→09 | `raw='set:SHARPEN=3'`（L3322）→ `drive set: ACCEPTED SHARPEN: requested=3 previous=4 applied=3`（L3323） | **s2**（18:25:09） |
| 18:25:20→21 | `raw='page:2'`（L3324）→ `drive page: 2 -> 第 2 页（共 16 页）`（L3325） | **s3**（18:25:21） |
| 18:25:33→36 | `raw='done'`（L3326）→ `saved: pack=BSL_v10.1.8 stored=1 removed=0 changed=[SHARPEN=3] store=./config/vkdisp-pack-options.properties`（L3327）→ 重载 `命中 1/1 [SHARPEN=3]`（L3700）、composite `bytes=24515`（L3702）、final `bytes=5615`（L3704） | — |
| 18:26:20 | 重载后世界（GUI 可见） | **s4**（18:26:20） |
| 18:26:55 | 同一配置写入连带 `shaderPack=vkdisp-fixture-dir` → `selection='vkdisp-fixture-dir'` composite `bytes=1655`（L6479）；`packOptionsScreen=''` 中性消费（L6473/6486） | — |
| 18:27:49→50 | `raw='open'`（L9255）→ `opened: pack=vkdisp-fixture-dir options=4 changed=0 profile='' storeEntries=1`（L9256） | **s5**（18:27:50） |
| 18:28:37→43 | `raw='done'`（L9261）→ `saved: stored=0 removed=0 changed=[]`（L9262，空提交不动 store）→ 世界基线 | **s6**（18:28:43） |
| 18:29:03 | `raw='set:SHADOW_DARKNESS=0.20'`（L12038）→ **`drive set rejected: 选项屏幕未打开（先 drive open）`**（L12039，T11 拒绝路径正向证据） | — |
| 18:29:04→06 | `raw='open'`（L12040）→ `opened`（L12041）→ `raw='set:SHADOW_DARKNESS=0.20'`（L12046）→ `ACCEPTED SHADOW_DARKNESS: requested=0.20 previous=0.10 applied=0.20`（L12047） | **s7**（18:29:06） |
| 18:29:17 | `raw='done'`（L12048）→ `saved: stored=1 removed=0 changed=[SHADOW_DARKNESS=0.20]`（L12049）→ 重载 `命中 1/1 [SHADOW_DARKNESS=0.20]`（L12053） | — |
| 18:30:23 | 重载后世界（地面变暗） | **s8**（18:30:23） |

## 截图判读（§1 验收行「pack 声明的选项能渲染并能改 | 截图」）

**渲染（八图全数可见 GUI 合成，AfterLevel 修复实证）**：

- **s1**：BSL 选项屏，头行 `pack=BSL_v10.1.8 options=284 changed=1 page=1 profile=""`
  （无 last 行 = 尚未驱动），双列 × 9 行控件（名称+值按钮）、◀▶ 翻页、`放弃/重置本包/完成`
  底栏；toast、世界背景合成在后 —— 控件与原版 GUI 同帧可见。
- **s2**：同页控件**逐像素不变**（widget 区 diff = **0.00%**，294000 采样点零差），
  仅头行新增 `last: drive ACCEPTED SHARPEN: requeste…`（头区 diff 14.17%）——
  驱动回执实时渲染进屏幕。
- **s3**：`page=2`、`last: drive page 2`，行内容整体换血（REFLECTION/PARALLAX/EMISSIVE 系）——
  分页渲染真实生效（双列 × 9 行 = 每页 18 项，284 项 → ceil = 16 页，与日志「共 16 页」一致）。
- **s5**：fixture 选项屏，`options=4 changed=0`，单列 4 控件：`shadowMapResol… 2048`、
  `SHADOW_DARKNESS 0.10`、`ENABLE_FOG true`（**BOOLEAN 以 LITERAL 风格渲染值文本**）、
  `shadowDistance 64.0`。
- **s7**：`changed=1` + `last: drive ACCEPTED SHADOW_DARKNESS:` + 值 `0.20` ——
  相对 s5：头行 2（last 行）diff 36.51%、**SHADOW_DARKNESS 值格 84 px 差**
  （0.10→0.20 文本换字），同款控件布局（灰按钮采样 4735 vs 4721）。
- **s4**：世界画面 —— toast「Move with W…」、准星、9 格 hotbar、手部全部合成在
  着色器输出之上（修复前这些一律被 final blit 抹掉）。

**能改（保存 → 改写 → 上屏 → 像素）**：

- BSL 半场：`set` 回执 `previous=4 applied=3` → `done` 落 store（stored=1）→
  `命中 1/1 [SHARPEN=3]` 改写包源 → 重载（s4）。
- fixture 半场（**A/B 像素闭环**）：fixture `composite.fsh` 语义
  `shade = 1.0 - SHADOW_DARKNESS` → 0.10 时 shade=0.90、0.20 时 shade=0.80，理论比
  **0.8889**。实测粉色地面带（y510–535, x60–260）：
  **s6 = 109.7547，s8 = 97.7959，ratio = 0.8910，delta = 0.0021** ✅
  （目检：s8 云与地面均比 s6 暗一档）。
- 拒绝路径：屏幕未开时 `set` 被 WARN 拒绝（L12039），开屏后同值重发即 ACCEPTED ——
  T11 显式拒绝而非静默丢弃。

## 不变量与零回归

- `stages=190 ok=49 failed=141` **×6 逐字一致**（启动 + 5 次重载重扫）；
  ERROR 直方图 = 已知 141 阶段矩阵的既有指纹（gl_MultiTexCoord / SPIR-V location /
  texture() 函数语法等 6 类，按 ×6 份数）+ 环境类（SoundEngine ×6、Narrator ×1）——
  **零新错误类**。
- `pipeline count check: registered=9, compiled=9 (aligned)`；
  `Missing uniform` = 0、`fullscreen pass failed` = 0、`解析失败` = 0（p415 修复保持）。
- `./gradlew build` exit=0；**532 用例 0 失败 0 错误**（505 → 532，新增
  `PackOptionStoreTest` / `PackOptionsSessionTest` / `ScreenDriveCommandTest` +
  `PackCompositeSourceTest` store 差分组）。
- 红线复检：`com.mojang.renderpearl` / blaze3d 仅 `bridge/`（T5）；构建脚本与源码
  `sodium|caffeinemc` 零命中（L11/X18）；驱动全程无输入注入（与 p416/p417 同源
  配置热加载方法）。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/p418-run3-full-sequence.log`（3273673 B，18:24–18:31 单会话八阶段全链） | `efb5cecf60b1391138fbd97ebe5fdd22e2a32827e16941a733e0171c1fe39485` |
| `tools/vulkan-local/evidence/p418_s1_bsl_options_screen.png`（89605 B，BSL 284 选项开屏） | `efca3adc1a1f6df8bdc99541bcea0746532a5dc1fdfb4749f75bb9b6030b0b5a` |
| `tools/vulkan-local/evidence/p418_s2_bsl_set_sharpen3.png`（90375 B，set 回执渲染） | `7dedce787e48e85b0557cfea2c9dd1d577e203fa481dd92d5c0cfc59bcf52b3e` |
| `tools/vulkan-local/evidence/p418_s3_bsl_page2.png`（90550 B，第 2 页换血） | `c0a004ddc6f7f51228c77593b8648da24f1e33c6419b8ed43f9877c7450089f7` |
| `tools/vulkan-local/evidence/p418_s4_after_done_world.png`（107872 B，done 重载后世界 GUI 可见） | `092a862a327a4f89e737e3c5ae1fdf4c4337ee59a493424808c9c78cfae568f9` |
| `tools/vulkan-local/evidence/p418_s5_fixture_options.png`（153267 B，fixture 4 选项 0.10） | `d6a37f3b789c2c59df756b59bed02f8177e791feb181cfa76089c468373e73a9` |
| `tools/vulkan-local/evidence/p418_s6_fixture_default_090.png`（46220 B，基线地面 109.7547） | `f41de7036e583ebd376d335023bb0ed1f7e11698b5fb8d55b77400bc663fbfc1` |
| `tools/vulkan-local/evidence/p418_s7_fixture_set_darkness020.png`（154710 B，0.20 changed=1 回执） | `3cb592af7d025d146207a80af1405d7e4dd6d103709a7234920673a0fe6c0f3d` |
| `tools/vulkan-local/evidence/p418_s8_fixture_darkness020_world.png`（47806 B，地面 97.7959 ratio=0.8910） | `aa7d35f7969d9a6199fba7f3fae3a1b04cad87b69096385b2e8dea38dc516747` |

## 判定

| 判据 | 结果 |
|---|---|
| pack 声明的选项能渲染（08-TESTING §1 P4.3 行） | ✅ BSL 284 项双列分页（s1/s3）、fixture 4 项含 BOOLEAN（s5）、驱动回执渲染（s2/s7） |
| 能改 | ✅ `set` ACCEPTED（previous→applied）→ `done` 落盘 → `命中 1/1` 改写包源 → 重载；fixture 像素比 0.8910 = 理论 0.8889（delta 0.0021） |
| 改动会话内闭环（非冷启动） | ✅ 单 runClient 会话 8 阶段，open/set/page/done 全程热驱动 |
| GUI 不被覆盖 | ✅ 八图 GUI 全可见；入口 AfterLevel（字节码 X9），Post 实测否决留档 |
| 拒绝路径显式（T11） | ✅ 未开屏 `set` → WARN rejected（L12039），同值开屏后 ACCEPTED |
| store 语义（只写触碰项） | ✅ 空 `done` stored=0（L9262）；触碰 `done` stored=1 removed=0（L3327/L12049） |
| 零回归 | ✅ stages 190/49/141 ×6、registered=9 compiled=9、Missing uniform=0、解析失败=0、532 单测全绿、零新错误类 |
| 选项值确实进了编译产物（bytes 疑点核销） | ✅ final 5616→5615（`0.0625 * SHARPEN` 替换 −1 字节）；composite 24515 恒定（仅指令行+等价分支，见机制 ③） |

**未覆盖（登记）**：

1. **真实输入路径**（鼠标点击/键盘直接操作控件）—— 输入注入被禁，本轮全部经
   `packOptionsScreen` 配置热加载 + 进程内 widget 调用驱动（p416/p417 同源方法）；
2. **滑杆型控件**：语料里值列表选项（BSL INTEGER/FLOAT 281 个）渲染为值循环按钮，
   无滑杆证据的包不猜（X9）；STRING 选项 3 个只读展示、本轮未改；
3. **选项屏幕打开时切包**（pack-switch-while-open）—— 本轮切包时屏幕已关；
4. **菜单态**：drive 只在世界内实测；菜单态不再绘制 pass（AfterLevel 只在世界内触发，
   菜单图案 = 实测否决的 GUI 覆盖，见机制 ①）；
5. run1 时段旧截图（17:27–17:38，三张 sha 同为 `7c97f105…` = 陈旧重复帧）作废，
   不入本表；本表只收 run3 受控序列（18:24–18:30）；
6. 外部干扰三跑记录：外部点「完成」×1、外部关窗 ×2（均 code 0 退出）—— 每次均
   隔离日志后重开受控复跑，run3 序列内无外部交互；
7. GUI 截图依赖窗口 id（每跑变化）与 X11 抓取时序，同状态复抓可能得到不同 PNG
   （内容等价判读靠上文 diff 口径，不靠 sha 相等）。
