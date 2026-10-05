# h43 · 像素回读探针 + 单变量 A/B 入口；并在 Vulkan 上测出「地形 colortex 有内容、主目标正常」

> **日期**：2026-10-05
> **性质**：新增两件取证工具 + 修掉它们各自暴露的**三个真缺陷**。
> **verdict = GAP-008 在本机 Vulkan 上**没有**复现（两臂 colortex0 都有内容）；
> 而 h42 §4.3 登记的「输出黑 vs 没落到主目标」现在有了**可复算的判别手段**，
> 并且它当场判出「两者都不是」。**
> 取证全程 Vulkan；按用户指令不做性能结论。

---

## 一、一页版结论

| 项 | 结果 |
|---|---|
| 后端 | ✅ `Using graphics backend Vulkan` + `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.1, 256 bits)` |
| **新工具 1**：`mrt.pixelProbe` | GPU→CPU 回读 + 像素统计（`mean_luma` / 非黑占比 / `maxR` / 是否逐像素全黑），**数字直接进日志** |
| **新工具 2**：`pack.optionOverrides` | 按名强制包选项（`NAME=value`），**只改内存**、不写用户文件 ⇒ 真单变量 A/B |
| GAP-008 复现？ | ❌ **没有**。`terrainToMain=false` + `capabilityGate=false` 下 `colortex0` **有内容**（`mean_luma 5.5936`、非黑 `28.005%`、`maxR 15`） |
| 单变量 A/B 结果 | 🔖 **`PARALLAX` 关 / 开两臂，主目标 `mean_luma` 完全相同（`16.6260`，逐位一致）；`colortex0` 变了但两臂都非黑** |
| 修掉的真缺陷 | ① 探针位置早于地形 pass（`toMain` 档失效）② 探针在 `toMain` 档拿 `colortex0` 当对照（那档它**不是附件**）③ `pack.optionOverrides` 不进地形契约记忆键（重载后按旧配置生成） |
| 859 条单测全绿 | ✅（+42） |
| 残留游戏进程 | ✅ 0（收尾 kill 后复查） |

---

## 二、新工具 1：像素回读探针（`mrt.pixelProbe`）

### 2.1 为什么需要它

h22～h42 一直在用「MCP 截图 + 外部 python 脚本」取数字。那条路有三个实测缺陷：

1. **需要人在运行之外再跑脚本**，而 h42 §4.3 想分开的「输出黑」与「没落到主目标」需要**同帧两个数字**；
2. **采样区写死在脚本里**，窗口尺寸一变就与历史数字不可比；
3. 🔴 **数字不在日志里** ⇒ 跨会话的读者（AI）读不到量化判据，只能重新截图重算。

第 3 条最致命：本项目的读者跨会话是 AI，它看不到截图里没有的数字。

### 2.2 口径（逐字沿用已有工具，不另立一套）

| 量 | 口径 | 来源 |
|---|---|---|
| 采样区 | 中心 `x∈[45%,65%] / y∈[15%,75%]` | `evidence/tools/flicker_ratio.py` 的 `FX0,FY0,FX1,FY1`，那组比例是 h22 用前缀和在 12 张已入库截图上**反解**出来的 |
| 黑阈值 | RGB 三通道**都 ≤ 8** | 同上 `THR = 8` |
| 亮度 | Rec.709 `0.2126R + 0.7152G + 0.0722B` | `p24_luma.py` |

🔖 **采样区按比例换算** ⇒ 换窗口尺寸后仍与历史数字可比（单测断言了面积落在 h22 说的「约 6.4 万 px」）。

### 2.3 四种结论必须分清（本轮自己踩了一次）

```
colortex 有内容 + 主目标全黑  ⇒ NOT_ON_MAIN    draw 没落到主目标（落点/接线问题）
主目标有内容 + colortex 全黑  ⇒ SLOT_BLACK     我方 pass 写进 colortex 的地形是黑的（= GAP-008 本体）
两者都全黑                    ⇒ BOTH_BLACK
两者都有内容                  ⇒ BOTH_HAVE_CONTENT
```

🔴🔖 **「主目标有内容 + colortex 全黑」这一格就是 GAP-008 的定义形态**，
而本类**第一版漏了它**（当时只写了三分）⇒ 漏掉的后果是日志会说「两源都有内容」，
取证者据此以为 colortex 正常。已补 + 守卫（`PixelStatsTest` / `PixelProbeWiringTest`）。

### 2.4 三条接线约束（都是实测踩出来的，不是设计出来的）

| # | 约束 | 踩坑经过 |
|---|---|---|
| ① | 必须在**所有** render pass 关闭之后 | 原版 `FrontendCommandEncoder#copyTextureToBuffer` 在 pass 内抛 `Close the existing render pass before performing additional commands` |
| ② | 必须在**地形 MRT pass 之后** | 首版放在 `FrameApi#drawFullscreen` 末尾，而地形 pass 由 Hook 在该方法**返回之后**调用 ⇒ `toMain` 档读到的「主目标」是**写入前**的内容，恰好在需要它的那档失效 |
| ③ | 必须在**诊断视图 blit 之前** | `MrtProbe.drawExternalView` 会把主目标覆盖成某个 colortex 的内容 ⇒ 两个数字指向同一张图，两源对照静默失效 |

⇒ 唯一同时满足处 = `FullscreenPassHook` 末尾。守卫同时**禁止**探针回到 `FrameApi`（否则将来有人搬回去）。

---

## 三、新工具 2：单变量 A/B 入口（`pack.optionOverrides`）

### 3.1 为什么必须有它

h42 §4.2 登记的未做项：能力门控在 BSL 上一次关掉 **9 个**选项，并把派生程序形状
从 `outputs 8→1` / `samplers 7→5` / `varyings 15→9` 一起改掉 ⇒ 两臂之间**不是单变量**，
于是既不能证明「视差无关」，也不能证明「是另外 8 项导致的」。

### 3.2 设计要点

- **通用按名覆盖**，不硬编码任何包特性名（`PARALLAX` 只存在于文档与单测，代码里零出现 ——
  单测 `doesNotHardcodeAnyPackOptionName` 钉住，理由是 Complementary 同样有视差却零外部依赖，X27）。
- **只改内存**，不写用户包配置文件（同能力门控那条裁决：Iris 自己也不这么干，无先例）。
- **解析失败 ⇒ 一条都不改**（不是「部分生效」）：半生效比全不生效更危险。
- **钳制单独报**（`VALUE_CLAMPED`）：钳制**生效了**，但生效的不是你写的那个值 ——
  写 `SHARPEN=99` 实际拿到 3 而日志只说「已覆盖」，归因就错了。
- 覆盖在**能力门控之后、差分之前**：门控是产品止血，覆盖是取证（取证者要能压过门控）。

---

## 四、两臂实测（同一存档 / 同一机位 / `dayTime=6000` / 天气 clear）

配置：`mrt.terrain=true` + `terrainAfterLevel=true` + `terrainToMain=false` +
`packTerrainShader=true` + `capabilityGate=false`，只有 `pack.optionOverrides` 不同。

| 臂 | 覆盖串 | 主目标 `mean_luma` | colortex0 `mean_luma` | colortex0 非黑 | colortex0 `maxR` |
|---|---|---|---|---|---|
| A | `PARALLAX=false` | **16.6260** | **5.5936** | 28.005% | 15 |
| B | `PARALLAX=true` | **16.6260** | **2.4961** | 25.703% | 62 |

🔖 **主目标两臂逐位相同**（`meanRGB=(14.1977,17.6455,13.6778)` 十余轮稳定复现）
⇒ 这就是 `terrainToMain=false` 的语义：**主目标由原版绘制**，我方 MRT pass 只写 colortex。

### 4.1 由此能说的与不能说的

- ✅ **能说**：在这套配置下，**GAP-008 的「colortex 全黑」没有复现**；
  且 `colortex0` 的内容**随 `PARALLAX` 变化**（`mean_luma` 5.59 → 2.50、`maxR` 15 → 62）
  ⇒ **视差确实影响 colortex0 的输出**，只是在本机没有把它压成 0。
- 🔴 **不能说**：
  - 不能说「GAP-008 已修好」—— h42 复现它时用的是 `terrainToMain=true`，
    而那一档本轮**没有做成有效的两源对照**（原因见 §五.2），故该档仍未定位。
  - 不能说「视差无关」—— 两臂 colortex0 的数字**确实不同**。
  - 不能说「两臂是严格单变量」而不再核查：本轮已核对覆盖日志原文
    `命中 3/3 [SHARPEN=3, PARALLAX=…, ADVANCED_MATERIALS=true]` 与 `OPTION_OVERRIDE_SUMMARY`
    都只显示 `PARALLAX` 一项被改，程序形状 `outputs=8 samplers=7 varyings=15` 两臂一致。

---

## 五、本轮修掉的三个真缺陷

### 5.1 探针位置早于地形 pass（`toMain` 档失效）

- **症状**：`terrainToMain=true` 下探针读到的是地形 pass **写入前**的主目标。
- **修法**：探针移到 `FullscreenPassHook` 末尾（唯一同时满足三条约束处）。
- **守卫**：`probeRunsAfterTerrainPass` + `frameApiMustNotCallProbe`。

### 5.2 `toMain` 档拿 `colortex0` 当对照 —— 诊断给了一个假数字

- **症状**：该档**附件 0 已被换成主目标视图**，`colortex0` 这一帧**根本没被写过**
  ⇒ 读它必然是「零填充的旧内容」⇒ 日志报 `colortex0 allZero=true`，
  而真相是「那张图不是附件」。这会让人误判成「包片元输出黑」。
- **修法**：该档改测**确实被写**的槽（1..n）；若只有一个附件则**明确不产出两源对照结论**。
- **守卫**：`toMainLaneMustNotProbeSlotZero`。
- 🔖 **这条是本轮最值得记的一条**：一个刚写好、刚通过全部单测、刚在 Vulkan 上跑出数字的诊断，
  在**最需要它的那一档**里给的是假证据。**「诊断能出数字」不等于「诊断给的数字对」。**

### 5.3 `pack.optionOverrides` 不进地形契约记忆键（死开关第四例）

- **症状**：改覆盖串 → 资源重载 → 日志同时出现「composite 侧覆盖表已变」与
  `terrain source reused from early contract` ⇒ **两条链对同一份配置互相矛盾**，
  而**没有任何一行**说「memo 是按旧配置生成的」。
- **根因**：`ensureTerrainProgram` 的记忆键只有 `profile|selection`，
  且它只在**管线注册期**调一次，而注册事件在资源重载时**不再触发** ⇒ 重载后 memo 若还在，
  它就是上一轮配置的那一份。
- **修法**：① 键加覆盖串分量；② 取走 memo 时**核对键**，不符就丢弃并同步重生成（并 WARN 自报）。
- **实测确认**：改 `PARALLAX=true` 后日志出现
  `地形契约 memo 与当前配置不符（生成于 key=|BSL_v10.1.8|PARALLAX=false，当前 key=…|PARALLAX=true）⇒ **丢弃**并同步重生成`
  —— 这行日志**本身就是修法生效的证据**。
- **守卫**：`VirtualPackMemoKeyTest`（含「造键与校验必须是同一个算法」这一条）。

---

## 六、顺带修掉的两个取证工具缺陷

### 6.1 `lane_cfg.py` 只支持改已有键（新配置第一次取证要空跑一趟客户端）

新增 `upsert`：节内没有该键时**追加到该节末尾**；**节本身不存在时连节头一起建**
（只追加 `key = value` 会让它落进上一个节 ⇒ TOML 解析出另一个键名，**且不报错**）。
另外加了 `--lane main|iso`（此前硬编码隔离车道 —— 改错车道则那轮取证根本没测到想测的东西，
**而日志看起来完全正常**，h27 已实测过这类串味）。

### 6.2 字符串项漏引号会让**整份配置**被丢弃（🔴 最隐蔽的一条）

把 `optionOverrides = PARALLAX=true`（无引号）写进 TOML ⇒ NightConfig 抛
`ParsingException: Invalid sequence 117e in numberPARALLAX=true. Attempting to recreate`
⇒ **整份配置按默认值重建** ⇒ `shaderPack` 从 `BSL_v10.1.8` 变回 `""`、所有 `mrt.*` 开关回默认、
`pixelProbe` 回关。

🔖 唯一征兆是 FileWatcher 线程里一行 WARN；画面照常跑、日志照打 ⇒ 取证者只会看到「改配置没生效」，
**不会**想到配置文件本身被报废。修法：`quote()` 对**非裸值白名单**一律加引号
（白名单 = `true|false|数字`，宁多引号不缺引号）。

---

## 七、自检

- [x] 全程 Vulkan（`Using graphics backend Vulkan` + `vkdisp: backend=Vulkan`），设备 lavapipe
- [x] 两臂的覆盖串、程序形状、采样区、日志原文全部列出
- [x] 明确区分「能说」与「不能说」，并把 h42 那一档仍未定位写清
- [x] 三个真缺陷各有症状/根因/修法/守卫
- [x] 记录了一条「诊断给假数字」的新形态（§五.2）
- [x] 859 条单测全绿（+42），`./gradlew build` 退出码 0
- [x] 取证后 `残留游戏进程数=0`
- [x] 按用户指令**不做**性能结论；validation layer 仍然没有

---

## 八、复算命令

```bash
# 后端
grep -m2 -E 'Using graphics backend|vkdisp: backend=' run/h27/logs/latest.log

# 两源统计（主目标 / colortex0）
grep 'pixel-probe' run/h27/logs/latest.log | tail -6

# 覆盖是否真生效（只看被改的那一项）
grep -oE 'OPTION_OVERRIDE_SUMMARY.*' run/h27/logs/latest.log
grep -oE '命中 [0-9]+/[0-9]+ \[.*\]' run/h27/logs/latest.log | tail -2

# memo 键不符被丢弃（本轮修法的自证）
grep '地形契约 memo 与当前配置不符' run/h27/logs/latest.log

# 外部脚本复核（口径一致性）
python3 tools/vulkan-local/flicker_ratio.py evidence/h43-images/final-armA-parallax-false.png
---

## 九、🔴 取证环境的一处坏掉（顺带修好，否则本轮根本起不来）

本轮第一次跑 `preflight.sh` 直接红：

```
[FAIL] 设备探测失败（loader 与 ICD 都能解析，但建不出 Vulkan 实例）：
vkCreateInstance FAILED res=-9
```

**根因**：`tools/vulkan-local/prefix/usr/lib/libvulkan_lvp.so` 需要 `libLLVM.so.22.1`，
而本机 `llvm-libs` 已升级到 **23.1.1** ⇒ `dlopen` 失败 ⇒ ICD 解析得到、驱动加载不到。
🔖 `ldd` 一行就暴露：「不是 Vulkan 坏了，是这个 .so 的依赖没了」。

**修法（免 root，与本仓库既有的 prefix 思路一致）**：取与本机 `llvm-libs` 匹配的那份
`vulkan-swrast`（mesa 26.2.4，Arch 官方包，sha256 与包库一致）解出
`libvulkan_lvp.so` + `lvp_icd.json`，覆盖 prefix 内那两份。修后：

```
[ OK ] 设备探测通过：llvmpipe (LLVM 23.1.1, 256 bits) (api 1.4.98)
```

🔖 值得记的：**这是一次「静默降级」被工具挡住**的实例 —— 如果没有 `preflight.sh` 的
硬失败，这一次取证会像 h33/h34/h35 那样**在 OpenGL 上跑完全程**而日志看起来一切正常。
本轮 `prefix/` 在 `.gitignore` 里，所以这一修不随仓库分发，但**它会在别人机器上以同样形态复发**。


```