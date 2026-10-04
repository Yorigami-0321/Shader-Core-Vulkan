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
| **QD-01** | G-02（连续六轮未动）· C-01 | 🟠 中 | `@Nullable` 严重不足：全主源码仅 **2 处**（`bridge/MixinTargets`）；`return null` **67 处**（上轮 50）。`07` §3.2 已写规范「所有可空返回值必须标注」，执行未跟上 | 先标注 `bridge/` 公开 API（`DeviceApi`/`PipelineApi`/`FrameApi`/`TextureApi` 等，约 10-15 处）；剩余内部 `return null` 登记为本表后续项 | 30 min（首批） | ⏳ 待实现 |
| **QD-02** | G-03（连续六轮未动）· C-02 | 🟠 中 | `debugLog` 仍是死开关：`VkDispConfig:31` 定义了 `DEBUG_LOG`，`VkDispConfigHotReload` 把它当核心项追踪（变了触发重载），但**全代码无任何 `if (DEBUG_LOG.get())` 消费点**。开关它无任何可观察效果 —— 比没有更糟（误导用户） | 二选一：① 在 `MrtTerrainPass`/`OfUniformManager`/`FullscreenPassHook` 补 3-5 处 `if (DEBUG_LOG.get()) LOGGER.info(...)` 真实消费；② 若短期不消费，删掉该配置项 + 热重载快照里的 `debugLog` 字段 | ① 20 min / ② 10 min | ⏳ 待实现 |
| **QD-03** | G-08 → C-03 | 🟡 低 | 静态非 final 可变字段 **50 → 95 翻倍**（2026-09-30→2026-10-04）。集中在 `bridge/`（缓存：`MappableRingBuffer`/`RenderPipeline`/`TextureTarget`）与 `render/`（`FullscreenPassHook` 标记/计数器、`OfUniformManager` 累加器）。渲染线程单线程访问不构成缺陷，但**单测间无法隔离**（静态状态无重置路径） | 给 `FullscreenPassHook` 与 `OfUniformManager` 的累加器加 `@VisibleForTesting` 重置方法（如 `@TestOnly static void resetState()`），单测 `@BeforeEach` 调一次；剩余登记为后续 | 40 min（首批） | ⏳ 待实现 |
| **QD-04** | C-06 | 🔵 观察 | 主源码仍有 **3 个 `>60` 行方法**（2026-09-30 时 17 个，已显著改善）。未定位具体位置 —— 若是 `LegacyBuiltinInjector`/`OfGlslTranslator` 核心转译逻辑，长方法可接受；若是别的，可能值得拆 | 下一轮审查先 `grep` 定位这 3 个方法，再判断是否需拆 | 下轮审查 | ⏳ 待定位 |

---

## 2. 已闭环的历史发现（保留作历史）

| 历史编号 | 内容 | 闭环时点 | 证据 |
|---|---|---|---|
| F-04 | `ModConfig.Type.COMMON` | 2026-10-04 复核 | `VkDisp.java:45` = `ModConfig.Type.CLIENT` ✅ |
| F-05 | `04-SPEC:86` namespace:path 错误 | 2026-10-04 复核 | 行号已变，84-88 行现为 `glsl/` 目录注释 ✅ |
| F-06 | `mods.toml` 悬空引用 `docs/22-版本基线.md` | 2026-10-04 复核 | `src/main/templates/META-INF/neoforge.mods.toml` 无 `22-`/`版本基线` 命中 ✅ |
| G-08 旧口径 | 「无 static 可变状态」结论是错的 | 2026-10-01 复核 | 已更正为 50 处；2026-10-04 复核为 95 处（见 QD-03）|

---

## 3. 闭环回路（强制）

> **问题**：G-02/G-03 跨六轮未动，根因是「审查发现 → 待办登记」回路缺失。本节是回路本身。

- **每轮迭代第 0 步**（`01-DEV-LOOP.md` §3 流程之前）：读本表，检查是否有「成本 ≤ 30 min」的项可顺手闭环。
- **每轮审查后**：把新发现追加到 §1 登记表（编号 `QD-xx` 递增，不复用）。
- **每项闭环时**：移到 §2「已闭环」并标 ✅，闭环 commit 引用本 ID。

> 本表不参与 `00-INDEX` 编号体系（无编号文档，类似 `AGENT_CONTEXT.md`）。
