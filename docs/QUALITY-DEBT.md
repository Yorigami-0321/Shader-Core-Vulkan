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

> 📌 **QD-09 ~ QD-14 的改进方案（调研 · 阶梯 · 验收）见 `19-IMPROVEMENT-PATHS.md`**。本表仍是**状态**的唯一出处，
> `19` 只是方案书（未执行）。对应关系：
>
> | 本表 | 方案落点 |
> |---|---|
> | QD-09（文本级翻译盲区静默） | `19` §2（阶梯 **A0** 止血 → A1 单一词法源 → A3 `#line` 归因 → A2 jcpp → A4 声明语法级） |
> | QD-10（巨型类）/ QD-03 / QD-12（静态可变） | `19` §3（阶梯 **B0** 三条棘轮 + reset 契约 → B1 代际号 → B2/B3 所有权三级） |
> | QD-11（换包即漂移的硬编码） | `19` §4（**C0** 反射面核实 → C1 sampler 派生 → C2a/C2b 阴影 → C3 一致性报告） |
> | QD-13（注释信噪比）/ QD-14（反射开关） | `19` §5（**D0** 规约与悬空引用 → D1 类型化配置） |
>
> 🔖 **本轮两条口径更正**：**QD-03 的「95」现为 175**（见该行）；**QD-11② 描述不完整**（见该行，已另立 `GAP-034`）。
> 🔖 规约建议（`19` §5.1）：凡脚本能算的指标一律由测试计算，文档不再手抄数字。

---

## 1. 登记表

| ID | 来源 | 级别 | 描述 | 最低闭环动作 | 成本 | 状态 |
|---|---|---|---|---|---|---|
| ~~**QD-01**~~ | G-02（**连续七轮未动**，`h34` 轮闭环）· C-01 | 🟠 中 | ~~`@Nullable` 严重不足：全主源码仅 **2 处**（且那 2 处只是 javadoc 里引用原版签名，**真实注解数为 0** —— `org.jspecify` 从未被 import）；`return null` **67 处**（上轮 50）。`07` §3.2 已写规范「所有可空返回值必须标注」，执行未跟上~~ → **2026-10-05 闭环（首批 + 守卫）**：`bridge/` 全部 **10 处**可空返回值补 `@Nullable`（含 2 个原版接口覆写），并新增 `BridgeNullableContractTest` 把规范变成**构建期红灯**。剩余内部 `return null` 转 **QD-06** | — | — | ✅ **已闭环** |
| ~~**QD-02**~~ | G-03（连续六轮未动）· C-02 | 🟠 中 | ~~`debugLog` 是死开关：只有定义与热重载快照、**零消费点** ⇒ 开关它无任何可观察效果，比没有更误导~~ → **2026-10-04 闭环**：采纳方案 ①，在 `OfUniformManager` / `MrtTerrainPass` / `FullscreenPassHook` 补 **3 处真实消费点**（分别报 uniform 键数与前几个键名、多附件 pass 的附件数+深度格式+挂的是原版还是包的片元、原本无条件的 uniform 传参周期行改为受控）。🔖 **三处全部节流**（每 300 / 300 / 120 帧）—— 这三段代码都在**每帧**执行路径上，无节流的 INFO 会把热路径变成 I/O 瓶颈（M-01 埋点 600→250000 是同一类教训）。实测日志已见 `[qd-02]` 行。证据 `evidence/h25-…` §6 | — | — | ✅ **已闭环** |
| **QD-03** | G-08 → C-03 | 🟡 低 | 静态非 final 可变字段 **50 → 95 翻倍**（2026-09-30→2026-10-04）。集中在 `bridge/`（缓存：`MappableRingBuffer`/`RenderPipeline`/`TextureTarget`）与 `render/`（`FullscreenPassHook` 标记/计数器、`OfUniformManager` 累加器）。渲染线程单线程访问不构成缺陷，但**单测间无法隔离**（静态状态无重置路径）。🔖 **2026-10-10 复算：95 → 175**（口径 = 静态非 final 的**单行**声明；分布 `bridge` 123 / `render` 20 / `pack` 10 / `glsl/translate` 7，命令见 `19-IMPROVEMENT-PATHS.md` 附录 A-A1）⇒ 本行的数字今后**由 `StaticFieldRatchetTest` 计算**，不再手抄（`19` §5.1） | 给 `FullscreenPassHook` 与 `OfUniformManager` 的累加器加 `@VisibleForTesting` 重置方法（如 `@TestOnly static void resetState()`），单测 `@BeforeEach` 调一次；剩余登记为后续。方案 `19` §3.4 / 阶梯 **B0** | 40 min（首批） | 🟡 **棘轮已落地（2026-10-10）**：`StaticFieldRatchetTest` 基线 175（只许降，失败信息按包分布排序）⇒ 本行数字的出处改成该测试的 `BASELINE`，不再手抄。**首批点名的三个 reset 钩子（`FullscreenPassHook`/`OfUniformManager`/`ActivePackUniforms`）与 `StaticHolderResetTest` 仍未做** |
| **QD-04** | C-06 | 🟠 中 | ~~主源码仍有 **3 个 `>60` 行方法**（2026-09-30 时 17 个，已显著改善）~~ → **2026-10-05 实测更正：那个「3」是错的**，正确值 **21 个**（口径：正文行数 = 闭合行 − 声明行，即排除声明行、含闭合行）。**「已显著改善」的结论一并作废。** 🔖 QD-04 原定的下一步就是「下一轮审查先定位这 3 个方法」—— 即**从没真的数过**，否则会立刻发现差 7 倍 | ✅ 已定位 + 已分类（`h35`）：转译器核心 9 / 解析器布局配置 5 / **渲染编排 6**（`FrameApi#drawFullscreen` **356 行**、`MrtTerrainPass#drawTerrain` 143、`FullscreenPipelineRegistrar` 118、`FullscreenPassHook` 97、`FrameApi#cameraMatrix` 75、`MrtProbe#draw` 62）。按本条原话「核心转译逻辑的长方法可接受」⇒ 前 14 个可接受；**渲染编排那 6 个值得拆** | 🔁 **部分闭环**：已建 `MethodLengthRatchetTest` 棘轮（基线 21，只许降不许升）+ 3 条元测试；**6 个渲染编排方法的拆分待单独一轮**（改热路径必须单独取证） |
| **QD-06** | QD-01 剩余项（`h34` 拆分） | 🟡 低 | `bridge/` 之外的内部 `return null` 仍有 **60 余处**（`glsl/translate` 10 / `VkDispVirtualPack` 8 / `pack` 12 / `config` 5 / 其余分散）。这些**不是对外 API**，调用点都在同包内，标注收益低于 `bridge/` 那一批；QD-01 的病根不是「漏标」而是「规范没有守卫」，守卫已建 ⇒ 本项优先级下调 | 按包逐个补；每补完一个包，在 `BridgeNullableContractTest` 里把该包目录纳入扫描范围 | 分包 15 min | ⏳ 待实现 |
| **QD-07** | h42 (registry structure debt) | 中 | 缺口登记表 13-GAP-REGISTRY 汇总表里 10 条缺口**没有状态字段**。本轮已给 GAP-008 补上；**仍欠 9 条**: GAP-002/003/004/005/006/007/009/010/011。**来历即自证**: 本轮先写下「GAP-008 是唯一一条没有状态字段的缺口」，随后**自己写的守卫当场数出另外 9 条** ⇒ 该句已撤回。处置 = **棘轮** (GapRegistryStatusFieldTest，基线 9 条，只允许变短)，非硬断言（否则构建立刻长期红）。收口 = 逐条补实际状态并缩小 BASELINE，**不得凭空编状态** |
| **QD-08** | h43（配置项「改了不生效」这一族已出现**第四例**） | 中 | **本轮实测第四例**：`pack.optionOverrides` 改了会触发资源重载、composite 侧按新覆盖重编（日志覆盖表确实变了），而**地形契约**因记忆键不含它而返回旧 memo ⇒ **两条链对同一份配置互相矛盾而日志看起来完全正常**。前三次是 QD-02（`debugLog` 零消费点）、h33（反射用键名当字段名 ⇒ 恒默认关）、以及本轮把探针位置放错导致 `toMain` 档失效。**这一族的共同形态：配置项存在、能读、某条链静默不生效，日志无异常。** 本轮已做：① 修掉该例（键加覆盖串分量 + 取走时核对键，不符就丢弃并 WARN 自证）；② 立 `VirtualPackMemoKeyTest`（含「造键与校验必须同一算法」这条 —— 首版造键用内联表达式、校验用别处，校验永远通过）；③ 立 `PackOptionSwitchReloadTest`（包选项类配置项必须进快照并触发重载）。⛔ **剩余同类项**：本表把「每轮第 0 步检查可低成本闭环项」当纪律，但**没有**一条机制把「新增一个在生成期被读的配置项」与「必须同步进记忆键/快照」绑在一起 —— 这是该族的结构性缺口，下一轮应把清单显式化 | 🔁 部分闭环（第四例已修 + 两组守卫）；结构性缺口（清单未显式化）待下一轮 |
| ~~**QD-05**~~ | `h33`（D3 的复发） | 🟠 中 | ~~**「死开关」这一族缺陷已复发第二次**。QD-02 是 `debugLog`；`h33` 抓到的是 `PackCapabilityGateSwitch` 用**配置键名**去取 **Java 字段名** ⇒ 每次 `NoSuchFieldException` 被 `catch (Throwable)` 吞掉 ⇒ 开关恒默认关，而**无异常、无告警、日志照打**~~ → **2026-10-05 闭环**（`h35`）：全仓审计 **81 个 catch 块**（41 已记录异常原文 / 40 静默），分类后确认**真正的只有 1 个** —— `TerrainPipelineApi#blockAtlasSizeOrEmpty` 的 `catch (Throwable t) { return new int[]{0,0}; }`，该值**每帧**喂进 `OfUniformManager`，尺寸错了 ⇒ OF uniform 静默走偏，而「为什么取不到」在日志里完全消失。已改为一次性 ERROR + 带异常原文 | ✅ 已闭环：① 修掉上述真问题；② 新增 `CatchThrowableVisibilityTest` —— 判据**不是「catch 了什么类型」**，而是「裸 `catch (Throwable x)` 必须用到 x 或在块内写明为何可丢弃」；配 3 条元测试 | — | — | ✅ **已闭环** |
| **QD-09** | 2026-10-10 核心代码审查（GLSL 翻译子系统） | 🟠 中 | **文本级翻译的已知盲区没有守卫**：`DefineProcessor` 不支持 `\` 续行宏（跨行 `#define` 会**静默产生错误展开**，不报 WARN）；`IoLocationAdapter`/`LegacyBuiltinInjector` 对跨行声明、同行多语句、宏体内旧内建名只能「保留原样交驱动报错」。这些盲区本身是文本级方案的既定取舍，但**静默错展开**不是 —— 它和 QD-08 同族（配置/源被读了、某条链静默走偏、日志无异常）<br>✅ **A0 已落地（2026-10-10，零语义变化）**：① `DefineProcessor` 续行 `\` → WARN（带包内行号，经 `errorAt` 同一条归因路）；② `DefineProcessor` 新增「指令行位于注释内」→ WARN（复用 `translate/CommentState` **同一套**状态机 ⇒ `/* */` 里的 `#define` 第一次可见，这正是「宏体内旧内建名残留」的上游根因）；③ `IncludeProcessor` 注释内 `#include` → WARN（🔖 本阶**照旧展开**，改成不展开属 A2）；④ `IoLocationAdapter` 跨行/半截 in-out 按**行首括号深度**分流：深度 0 ⇒ WARN，>0（函数参数折行）⇒ 保持安静（旧代码两类一起静默）。5 条负样本单测钉住「必须 WARN，不得静默」 | 剩余 ③ 长期项：`#if` 表达式与声明解析升级语法级（= `19` §2.6 **A1/A4**，A2 一并解决续行与注释语义） | ①②已做；③ 见 A1/A4 | 🔁 **部分闭环**（①②④⑤⑥可见性已落地 + 守卫；语法级留 A1/A2/A4） |
| **QD-10** | 2026-10-10 核心代码审查（bridge/render） | 🟠 中 | **巨型类**：`FrameApi` 1675 行、`VkDispVirtualPack` 1573 行、`PipelineApi` 1163 行、`MrtTerrainPass`/`TargetReadback`/`TerrainPipelineApi` 均 1000+ 行。QD-04 的棘轮只管**方法长度**，管不住类体量增长；这些类同时持有大量静态可变状态（与 QD-03 交叠），是「改一处牵全身」的主要来源 | 按 QD-04 同款做法建**类行数棘轮**（`ClassLineRatchetTest`，基线=当前各文件行数，只许降不许升）；`FrameApi` 拆分随 QD-04 的「6 个渲染编排方法拆分」同轮做，避免两次动热路径 | 棘轮 30 min；拆分单独一轮 | 🟡 **棘轮已落地（2026-10-10）**：`ClassLineRatchetTest` 每条巨类一个行数上限（1675/1573/1163/1131/1112/1069，只许降；比上限少超 40 行也红灯 ⇒ 拆小就得锁住）。**拆分本体（B2/B3）仍未做** |
| **QD-11** | 2026-10-10 核心代码审查（bridge/pipeline）+ 🔖 **同日 `19` 号文档二次取证更正两条** | 🟠 中 | **换包即漂移的硬编码假设**：① `PipelineApi:127-134` 的 `PACK_FRAGMENT_SAMPLERS` 18 个名字硬编码自 BSL 包扫描，其中 16 个绑 colorView 占位；② `FrameApi:130` 太阳光方向是硬编码常量 ⇒ 阴影不随太阳转（`LightSpaceList` 也只是单级联占位）。两者都无「与当前包实际声明不符」的检测，换非 BSL 包会**静默错位**。<br>🔴 **② 的更正（本条原判不完整）**：接了真角度也**不会有像素变化** —— 包的 `shadow` 程序**零消费者**（`grep -rn "ProgramStage.SHADOW" src/main/java` 只命中枚举自身）、`shadowtex0/1`+`shadowcolor0` 绑的是 **`ShadowStubs` 的 1×1 桩**（其类注释自述「没有真阴影贴图 ⇒ 阴影项不承诺」）、全仓**无阴影目标贴图**。⇒ 真实缺口已另立 **`GAP-034`**；本条 ② 只覆盖「光空间数学」那一半<br>🔖 **① 的更正（比原判便宜）**：派生机制**已在仓** —— `PostPassContract.SAMPLER_DECL:51,97` 与 `SamplerDimensionPlan.SAMPLER_DECL:186,206` 都从片元源码解析 `uniform samplerX`，`PackPostChain:223-232` 已跑「派生 + 对差 + WARN + 排除」。⇒ ① 不是「新建派生组件」，是**把这条已跑通的路接到 gbuffer/composite/deferred 上**；且 `SamplerDimensionPlan:28-32` 早已引 **X39** 明文否决「按名字硬编码」⇒ 本条实质是「已确立的原则没贯彻到 `PipelineApi`」 | ① sampler 集合改由**包自己的声明**派生（复用 `SAMPLER_DECL` + `PackTextureBindings`），硬编码表降级为 fallback 且不符必 WARN；🔴 更强的权威臂 = 原版 `SpvModule$Reflection/$Descriptor/$InterfaceVariable`（`02-OVERVIEW:58` 记的是**常量池类型名** ⇒ 类存在已证、**方法表面未验**）⇒ 动手前先 `javap -p` 核实并登记（**X41/X9**：未核实的 API 表面不许当设计地基）；② **拆两级**：C2a 数学（真角度 + 从包读 `shadowDistance`/`shadowIntervalSize`/`sunPathRotation` + texel 对齐 + 去掉懒缓存）可单测、不碰 GPU；C2b 见 `GAP-034`；③ 守卫单测两条（名单必须来自包；矩阵必须随时间变——**注意这只能验 C2a**）；④ 加 `PackConformanceReport`：包加载期把每张硬编码名单与包实际声明对差，逐名 WARN（`19` §4.4） | ①③约 2 h；②的 C2a 约 1 h；**C2b 未估**（独立大项，量级 ≥ GAP-003 的地形装配）⇒ **本条的「1 天可闭环」估计已作废** | 🟠 **部分闭环（2026-10-10）**：✅ **C0** 反射面已 `javap` 核实并登记（`19` §4.2 的 C0 表，两条限制：`dimensions()` 无公开常量表 / 测试类路径取不到二级嵌套类型）；✅ **C1** 名单来源改为**由 OF 命名规则生成**的超集 + 包声明快照 + 视图按类型路由（`PackSamplerSuperset` / `PackSamplerViews`，真机两轮取证、编译计数与改前逐位相同）；✅ **C3** `PackConformanceReport` 覆盖 §4.1 全部 10 张名单并接进 `08` §10.2。**仍未做**：② 的 C2a（光空间数学）与 C2b（包 shadow pass 装配 = `GAP-034`）、C1 暴露出的 `GAP-035`（全屏步绑定组启动期定死 ⇒ 包自造 sampler 名绑不上，本阶只做到点名） |
| **QD-12** | 2026-10-10 核心代码审查（pack/render） | 🟡 低 | **全局静态单例缺重置路径**：`ActivePackUniforms` 全局静态 + `SMOOTH_STATE` 静态可变状态，无 `resetState()` ⇒ 单测间无法隔离、多包切换有残留隐患。与 QD-03 同病根，但 QD-03 首批只点名了 `FullscreenPassHook`/`OfUniformManager` | 并入 QD-03 首批一起做：给 `ActivePackUniforms` 加 `@TestOnly static void resetState()`，单测 `@BeforeEach` 调用；多包切换残留路径补一条集成测试 | 随 QD-03，+20 min | ⏳ 待实现 |
| **QD-13** | 2026-10-10 核心代码审查（全仓） | 🟡 低 | **注释信噪比**：大量文件头 30-60 行「参考调研/合规核对/取证日志」（h48/h49 证据编号等），把「为什么」埋进「过程记录」里；取证细节本应只存 `evidence/`（已退库）与 commit message。另 `VkDispPackScan.java` 有重复 import（`ShaderPackCompiler` 两次） | ① 定一条注释规约进 `07-CONSTRAINTS`：文件头只留「本文件职责 + 非显然约束」，证据编号改为单行引用（`// 证据: h48`）；② 新增文件按规约执行，存量文件**只在被改动时**顺手收缩（不搞全仓批量重写）；③ 修掉重复 import | 规约 15 min；import 5 min | ⏳ 待实现 |
| **QD-14** | 2026-10-10 核心代码审查（pack/config） | 🟡 低 | **反射开关的结构性脆弱**：4 个 Switch 类靠 `Class.forName("dev.vkdisp.VkDispConfig")` + 反射读字段。h33 的字段名事故已修、`CatchThrowableVisibilityTest` 已保证失败可见，但「字段改名 ⇒ 开关静默回默认」的路径仍在（现在会报错，属可见降级，故降为 🟡） | 编译期直连替代反射：Switch 类与 `VkDispConfig` 同在 mod jar 内，`Class.forName` 的解耦收益存疑 —— 核实当初解耦动机（类加载顺序？）后，若无硬约束改直接引用；有硬约束则加一条元测试断言 `FIELD_NAME` 与 `VkDispConfig` 实际字段一致 | 核实+改造约 1 h | ⏳ 待实现 |

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
