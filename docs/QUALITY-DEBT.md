# 代码质量债登记表（QUALITY-DEBT）

> **性质**：本表登记**代码层面的质量债**（不是「原版不支持的能力」—— 那是 `13-GAP-REGISTRY`）。
> 每条债是「规范已写、执行未跟上」或「可测试性风险」的待办，附**最低闭环动作**与**成本估计**。
> **规则**：每轮迭代清单第一项 = 「检查本表是否有可低成本闭环的项」。避免「30 分钟即可关闭的债跨六轮」。
>
> 来源：`review/2026-10-04-文档清理后审查.md`。历次审查记录见 `review/`，本表是**持续追踪**的单一出处。

---

## 0. 状态图例

| 图例 | 含义 |
|---|---|
| 🟠 **中** | 影响可维护性 / 可测试性，但非阻塞 |
| 🟡 **低** | 风险信号，未构成缺陷 |
| 🔵 **观察** | 待定位 / 待实测 |
| ✅ **已闭环** | 已整改，保留作历史 |

---

## 1. 登记表

| ID | 来源 | 级别 | 描述 | 最低闭环动作 | 成本 | 状态 |
|---|---|---|---|---|---|---|
| ~~**QD-01**~~ | G-02（**连续七轮未动**，`h34` 轮闭环）· C-01 | 🟠 中 | ~~`@Nullable` 严重不足：全主源码仅 **2 处**（且那 2 处只是 javadoc 里引用原版签名，**真实注解数为 0** —— `org.jspecify` 从未被 import）；`return null` **67 处**（上轮 50）。`07` §3.2 已写规范「所有可空返回值必须标注」，执行未跟上~~ → **2026-10-05 闭环（首批 + 守卫）**：`bridge/` 全部 **10 处**可空返回值补 `@Nullable`（含 2 个原版接口覆写），并新增 `BridgeNullableContractTest` 把规范变成**构建期红灯**。剩余内部 `return null` 转 **QD-06** | — | — | ✅ **已闭环** |
| ~~**QD-02**~~ | G-03（连续六轮未动）· C-02 | 🟠 中 | ~~`debugLog` 是死开关：只有定义与热重载快照、**零消费点** ⇒ 开关它无任何可观察效果，比没有更误导~~ → **2026-10-04 闭环**：采纳方案 ①，在 `OfUniformManager` / `MrtTerrainPass` / `FullscreenPassHook` 补 **3 处真实消费点**（分别报 uniform 键数与前几个键名、多附件 pass 的附件数+深度格式+挂的是原版还是包的片元、原本无条件的 uniform 传参周期行改为受控）。🔖 **三处全部节流**（每 300 / 300 / 120 帧）—— 这三段代码都在**每帧**执行路径上，无节流的 INFO 会把热路径变成 I/O 瓶颈（M-01 埋点 600→250000 是同一类教训）。实测日志已见 `[qd-02]` 行。证据 `evidence/h25-…` §6 | — | — | ✅ **已闭环** |
| **QD-03** | G-08 → C-03 | 🟡 低 | 静态非 final 可变字段 **50 → 95 翻倍**（2026-09-30→2026-10-04）。集中在 `bridge/`（缓存：`MappableRingBuffer`/`RenderPipeline`/`TextureTarget`）与 `render/`（`FullscreenPassHook` 标记/计数器、`OfUniformManager` 累加器）。渲染线程单线程访问不构成缺陷，但**单测间无法隔离**（静态状态无重置路径） | 给 `FullscreenPassHook` 与 `OfUniformManager` 的累加器加 `@VisibleForTesting` 重置方法（如 `@TestOnly static void resetState()`），单测 `@BeforeEach` 调一次；剩余登记为后续 | 40 min（首批） | ⏳ 待实现 |
| **QD-04** | C-06 | 🟠 中 | ~~主源码仍有 **3 个 `>60` 行方法**（2026-09-30 时 17 个，已显著改善）~~ → **2026-10-05 实测更正：那个「3」是错的**，正确值 **21 个**（口径：正文行数 = 闭合行 − 声明行，即排除声明行、含闭合行）。**「已显著改善」的结论一并作废。** 🔖 QD-04 原定的下一步就是「下一轮审查先定位这 3 个方法」—— 即**从没真的数过**，否则会立刻发现差 7 倍 | ✅ 已定位 + 已分类（`h35`）：转译器核心 9 / 解析器布局配置 5 / **渲染编排 6**（`FrameApi#drawFullscreen` **356 行**、`MrtTerrainPass#drawTerrain` 143、`FullscreenPipelineRegistrar` 118、`FullscreenPassHook` 97、`FrameApi#cameraMatrix` 75、`MrtProbe#draw` 62）。按本条原话「核心转译逻辑的长方法可接受」⇒ 前 14 个可接受；**渲染编排那 6 个值得拆** | 🔁 **部分闭环**：已建 `MethodLengthRatchetTest` 棘轮（基线 21，只许降不许升）+ 3 条元测试；**6 个渲染编排方法的拆分待单独一轮**（改热路径必须单独取证） |
| **QD-06** | QD-01 剩余项（`h34` 拆分） | 🟡 低 | `bridge/` 之外的内部 `return null` 仍有 **60 余处**（`glsl/translate` 10 / `VkDispVirtualPack` 8 / `pack` 12 / `config` 5 / 其余分散）。这些**不是对外 API**，调用点都在同包内，标注收益低于 `bridge/` 那一批；QD-01 的病根不是「漏标」而是「规范没有守卫」，守卫已建 ⇒ 本项优先级下调 | 按包逐个补；每补完一个包，在 `BridgeNullableContractTest` 里把该包目录纳入扫描范围 | 分包 15 min | ⏳ 待实现 |
| **QD-07** | h42 (registry structure debt) | 中 | 缺口登记表 13-GAP-REGISTRY 汇总表里 10 条缺口**没有状态字段**。本轮已给 GAP-008 补上；**仍欠 9 条**: GAP-002/003/004/005/006/007/009/010/011。**来历即自证**: 本轮先写下「GAP-008 是唯一一条没有状态字段的缺口」，随后**自己写的守卫当场数出另外 9 条** ⇒ 该句已撤回。处置 = **棘轮** (GapRegistryStatusFieldTest，基线 9 条，只允许变短)，非硬断言（否则构建立刻长期红）。收口 = 逐条补实际状态并缩小 BASELINE，**不得凭空编状态** |
| ~~**QD-05**~~ | `h33`（D3 的复发） | 🟠 中 | ~~**「死开关」这一族缺陷已复发第二次**。QD-02 是 `debugLog`；`h33` 抓到的是 `PackCapabilityGateSwitch` 用**配置键名**去取 **Java 字段名** ⇒ 每次 `NoSuchFieldException` 被 `catch (Throwable)` 吞掉 ⇒ 开关恒默认关，而**无异常、无告警、日志照打**~~ → **2026-10-05 闭环**（`h35`）：全仓审计 **81 个 catch 块**（41 已记录异常原文 / 40 静默），分类后确认**真正的只有 1 个** —— `TerrainPipelineApi#blockAtlasSizeOrEmpty` 的 `catch (Throwable t) { return new int[]{0,0}; }`，该值**每帧**喂进 `OfUniformManager`，尺寸错了 ⇒ OF uniform 静默走偏，而「为什么取不到」在日志里完全消失。已改为一次性 ERROR + 带异常原文 | ✅ 已闭环：① 修掉上述真问题；② 新增 `CatchThrowableVisibilityTest` —— 判据**不是「catch 了什么类型」**，而是「裸 `catch (Throwable x)` 必须用到 x 或在块内写明为何可丢弃」；配 3 条元测试 | — | — | ✅ **已闭环** |

---

## 2. 已闭环的历史发现（保留作历史）

| 历史编号 | 内容 | 闭环时点 | 证据 |
|---|---|---|---|
| F-04 | `ModConfig.Type.COMMON` | 2026-10-04 复核 | `VkDisp.java:45` = `ModConfig.Type.CLIENT` ✅ |
| F-05 | `04-SPEC:86` namespace:path 错误 | 2026-10-04 复核 | 行号已变，84-88 行现为 `glsl/` 目录注释 ✅ |
| F-06 | `mods.toml` 悬空引用 `docs/22-版本基线.md` | 2026-10-04 复核 | `src/main/templates/META-INF/neoforge.mods.toml` 无 `22-`/`版本基线` 命中 ✅ |
| **QD-01**（原 §1） | `@Nullable` 从 0 处补到 `bridge/` 全覆盖（10 处）+ 建构建期守卫 | 2026-10-05（`h34`） | `src/test/java/dev/vkdisp/bridge/BridgeNullableContractTest.java`（含 2 条元测试证明守卫本身有效）；见 `CHANGE_LOG.md` 五十七 |
| **QD-02**（原 §1） | `debugLog` 死开关 → 补 3 处节流消费点 | 2026-10-04 | `evidence/h25-flicker-is-pack-fragment-not-m01.md` §6；运行期日志 `[qd-02]` 行 |
| G-08 旧口径 | 「无 static 可变状态」结论是错的 | 2026-10-01 复核 | 已更正为 50 处；2026-10-04 复核为 95 处（见 QD-03）|

---

## 3. 闭环回路（强制）

> **问题**：G-02/G-03 跨六轮未动，根因是「审查发现 → 待办登记」回路缺失。本节是回路本身。

- **每轮迭代第 0 步**（`01-DEV-LOOP.md` §3 流程之前）：读本表，检查是否有「成本 ≤ 30 min」的项可顺手闭环。
- **每轮审查后**：把新发现追加到 §1 登记表（编号 `QD-xx` 递增，不复用）。
- **每项闭环时**：移到 §2「已闭环」并标 ✅，闭环 commit 引用本 ID。

> 本表不参与 `00-INDEX` 编号体系（无编号文档，类似 `AGENT_CONTEXT.md`）。
