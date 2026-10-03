# AGENT_CONTEXT — vkdisp

> 跨会话项目记忆。每次生成/更新文档包后同步。
> **2026-09-29 方向已彻底变更：以 §0 为准，旧方向全废。**
> **2026-10-02 目标升级为三支柱（兼容/稳定/高性能）+ mixin 松绑 + 原生可实测：以 §0.1 为准。**
> **最新交接快照：§10（2026-10-03，G2 收口 + B4 定位轮，任务停止前存档）。**
> 上一份快照：§9（2026-10-01，141 阶段矩阵修复轮）—— 中间各轮续记见 §9.4.x。
> **2026-10-03 续 §10：H 线 M-01/M-01b 已落地并取证**（见 §10.7），支柱①的最大阻塞项**不再是「一行代码都没写」**。

---

## 0.1 🔴 当前目标（2026-10-02 用户指令，**优先级最高**）

> 用户原话：「**目的依旧是高性能，稳定，以及保持兼容性让 optfine/iris 着色器完整的运行在 vulkan 上。
> 接下来需要测试 rust 和 java 原生实现的性能差异。然后略放开 mixin 的限制，
> 接下来的开发，需要去联网搜集资料，确保开发过程顺利。**」

### 三支柱（一切取舍的裁决顺序）

| 序 | 支柱 | 唯一度量 | 权威条款 |
|---|---|---|---|
| ① | **兼容**（OptiFine/Iris 着色器**完整**运行在 Vulkan 上） | pack × program 兼容矩阵（`08` §10） | `07` §〇·〇 / `02` §2 |
| ② | **稳定** | 崩溃 / validation error = 0；无静默降级；1h 内存增量 ≤ 200MB | `08` §8.3 |
| ③ | **高性能** | B1 帧时间 ≤ +2%；B3 冷路径 ≤ 1s；B4 切包 ≤ 2s | `17` §2.2 |

### 本轮推翻了三条旧铁律

| 旧口径 | 新口径 | 依据 |
|---|---|---|
| 默认零 mixin，全局只许 1 个注入点 | **mixin 松绑到「管线装配层」**：允许多注入点 + draw 侧装配，改为**登记制 + 可关闭制 + 逐个开启**；仍永久禁止注入 Sodium / 底层 GL 状态类 / 第三方区块渲染器 | 零 mixin 路线**源码级证伪**：`registerCustomPipelines` 用 `putIfAbsent` 改不了原版 location；`SOLID_TERRAIN` 写死 `ColorTargetState.DEFAULT` 单附件；`BindGroupLayouts.Globals` 仅 9 字段。三条路全断 ⇒ 登记为 GAP-003 / GAP-004 |
| 冷路径（解析/预处理/转译）一律纯 Java | **允许实测**（G 系列闸门），≥20% 净收益 + 等价性全绿才采用 | 「冷路径无收益」是推理过度：冷路径决定**切包等待**（B3/B4），不是帧率。现状 BSL 冷路径 1861ms 逼近 B4 的 2s |
| C++/Rust 是未验证的可选项 | **列为 G 线实测对象**，范围 = `glsl/` 预处理与转译 + `pack/` 解析 | 用户明确要求测试 Rust vs Java 差异 |

### 新增硬条款（2026-10-02）

- **T17 / N5 / N6 / X30**：`extern "C"` 必须 `catch_unwind(AssertUnwindSafe(…))`；**禁止 `panic = "abort"`**。
  Rust panic 穿 FFI = UB = **JVM abort = 游戏崩溃**。取证：`08` §8.3 要求故意触发一次 panic。
- **T18**：原生接口**按批**不按条（逐条调用会把 FFI 收益吃光）。
- **T19**：优先 **FFM（`java.lang.foreign`，Java 25 正式）+ `jextract`**，不用 JNI。
- **X27**：🔴 **不许为性能砍掉 pack 特性**（兼容优先级最高）。
- **X28 / X29**：注入点必须登记在 `04` §5.0；必须**逐个开启**、能**逐个关闭**。

### 联网核实结论（2026-10-02，本轮新增）

| 对象 | 结论 | 影响 |
|---|---|---|
| **glslang + SPIRV-Tools** | **Apache-2.0 / BSD-3，可合法并入 MIT 工程**（Fedora/openEuler/Arch 打包元数据三处一致） | 登记 **GAP-005**：是否用成熟 GLSL 前端替/辅自研 8 段转译器。本轮不执行 |
| **naga**（Rust） | GLSL→SPIR-V 约比 glslang 快 **30×**（kvark 2022 基准）；但**不支持 `#include` 与完整 `GL_*` 语义**，对 OF 方言包会编译失败 | 仅作「Rust 在转译类负载有数量级潜力」的证据；**禁止当本项目选型结论**（X32） |
| **FFM vs JNI** | FFM 于 Java 22 正式，Java 25 可直接用；`jextract` 自动生成绑定 | 选定 FFM，禁用 JNI（T19） |
| **Rust panic × FFI** | 官方 Nomicon：`extern "C"` 收到 panic 会终止进程，必须 `catch_unwind` | T17 / GAP-006 |

### 本轮的执行入口（新增两条并行线，见 `18` §4）

- **G 线**：Rust vs Java 冷路径性能对比（`glsl/` 转译 + `pack/` 解析），流程 G0–G4，裁决阈值 20%。
  - ✅ **G0 已完成（2026-10-02）**：`ColdPathBenchmark`（`src/test/java/dev/vkdisp/pack/`）+ 
    `evidence/g0-java-coldpath.md`。BSL 182 阶段的分段基线已建立。
  - 🟡 **G1 进行中（截至 2026-10-02 五）**：**inc 相与 pre 相各 182/182 逐字节一致**
    （`evidence/g1-rust-equivalence.md`），对照工程在**仓库外** `~/Minecraft/g1-rust-bench`
    （零第三方 crate，Rust 单测 32/32）。**转译相与 `pack/` 解析相未做 ⇒ G1 未完成。**
  - ✅ **G3 的口径阻塞已解除（2026-10-02 四）**：`evidence/g3-preliminary-phase-comparison.md`
    + `17-NATIVE.md` §7.4。9 轮**交替**分相对照：inc **Rust 快 75.1%**（4.01×）、
    def **Rust 慢 55.7%**（是首版实现慢，非语言天花板）、**const 243.9ms 未移植且占预处理段 51%**。
    ⇒ **const 的归属是「采用 / 不采用」的分界线**（见 §7.4 三种假设表）。
  - 🔴 **P1 已执行（2026-10-02 五）**：Java 侧「先挡后正则」前缀守卫**只省 4.9%**（246.1→234.1ms），
    **「const 是正则瓶颈」的假设被证伪** —— 真实热区是 split/strip 的字符串分配（≈35%）。
    见 `evidence/p1-const-prefix-guard.md`。这同时动摇了「const 按 inc 的 4× 移植」的乐观假设。
  - 🔴 **P3 已执行（2026-10-02 六）**：const **单遍实现**（不物化行数组、不为每行分配
    strip 结果）A/B 产物一致，只再省 **3.0%**；**P1 + P3 累计 7.5%**，
    **Java 侧微优化 const 的路线到此为止**。见 `evidence/p3-const-single-pass.md`。
    🔖 最重要的一条教训：**「JFR 采样占比」≠「可优化空间」** —— GC 发生在别的线程上
    （按栈含 `evaluate` 过滤根本采不到），而 `split` 是短命年轻代分配、bump 极快、
    逃逸分析还能吃掉一部分。
  - ✅ **PP 已执行（2026-10-02 七）** —— 见 `evidence/pp-parse-profile.md` 与 `17-NATIVE.md` §7.5。
    🔴 **① G0 四段口径有误**：「解析」段里约 **48%** 是被重复计算的预处理
    （`ShaderPackService.load` 为提取 uniform/属性已跑过一遍完整预处理）。
    ⇒ 上一轮据「解析占 43%」调方向建立在被高估的数字上。
    ✅ **② 生产路径确实把预处理算了两遍，已修复**：`GlslPipeline.runPreprocessed` +
    `load` 的预处理 sink，仅在无选项覆盖时复用。**生产入口 1458.5 → 885.0ms（快 39.3%）**，
    产物 182/182 逐字节一致。**纯 Java，无 FFI。**
    ⇒ **支柱③的第一优先级不是换语言，是消除重复计算**；**G 线需在新基线上重估**
    （旧对照的分母含重复计算）。
  - ✅ **客户端取证已补（2026-10-02 八）** —— `evidence/client-verify-preprocess-reuse.md`。
    runClient 两趟实测：冷路径 **3482 → 2696ms（−22.6%，786ms）**；
    `stages=190 ok=190 failed=0`、0 ERROR/WARN、日志行数相同、
    **190 条 SPIR-V 产物逐条一致**。客户端百分比低于离线（−22.6% vs −39.3%）但
    **绝对收益更大**（分母多了不受影响的驱动 SPIR-V 编译）⇒ **离线 39.3% 没有虚高**。
    顺带补上**启动冷路径计时埋点**（此前客户端日志零冷路径打点）。
  - ✅ **Round 10 已执行（2026-10-02 十二）—— G 线在新基线上的重估** ——
    见 `evidence/g4-recheck.md` 与 `17-NATIVE.md` §7.6。🔴 **重估不是裁决**（§5.1 要求 G1 完成，
    而 G1 还差**转译相**与 **`pack/` 解析相**）。
    🔖 **口径纠正**：§5.2 的阈值是「**Rust 端到端中位数**快 ≥20%」，
    **必须对整条冷路径算，不能只算预处理**；基准取**口径修正后**的生产入口 **895.3ms**（n=21），
    **不是旧的 1494.9ms**（旧四段表把预处理算了两遍）。
    9 轮交替分相：inc Java 160.6 / Rust 40.0（**快 75.1%**，4.01× 两轮复现）、
    def Java 77.1 / Rust 119.3（**慢 54.6%**）、**const Java 228.1 未移植且占预处理 49%**，
    三段合计 465.8ms。
    **四情形**：S1 当前范围（不移植 const）**+8.8% 🟡 暂缓** / S2 乐观（const 拿 inc 的 4.01×）
    **+27.9% ✅** / S3 保守（const 只有 def 的 1.55×）**−5.2% ❌** / S4 下界 **0% ❌**
    ⇒ **裁决完全悬在 const 一段上**。⚠️ **S2/S3 的 const 速度是假设、不是实测**，
    且 §7.4 已动摇 S2 的前提（inc 的 4× 优势来自「跳过正则」，而 const 的正则本来就不贵）
    ⇒ **不许按 27.9% 做规划**。
    ⚠️ **分解**：**只移植 inc = +13.5%，加上 def 反而降到 +8.8% —— def 的负贡献吃掉 42.1ms**。
    ⚠️ **Round 9 的口径修正不改变本结论**（分相对照读的是 golden，不受重复计算影响），
    **变的是基准**（1494.9 → 895.3，分母变小 ⇒ 百分比变大）。
    ✅ 等价比照重跑确认：inc/pre **各 182/182 逐字节一致**、Rust 单测 **32/32**。
    📌 附带发现：端到端里 Rust 完全没碰过的部分至少有 **169.3ms**（声明提取+选项组装+zip 读取，
    由 465.8ms 三段 vs 635.1ms 加载段交叉校验得出）+ **226.5ms**（转译）≈ **396ms**
    ⇒ 「只挑快的段移植」会引入 Java↔Rust 往返，正是 §5.2 里 20% 阈值要覆盖的持续成本。
    ⏭️ **下一步先测 const**，或在第二个包上复测一轮（S2 档要求），再谈 G2。
- **H 线**：管线装配层 mixin（GAP-003 + GAP-004 同批），**本项目兼容目标的最大阻塞项**。

---

## 0. 当前方向（2026-09-29 用户指令）

> 用户原话：「彻底换个方向，去实现基于原版 vulkan 同时兼容 optfine/iris 格式的着色器，
> **不依赖也不去尝试替换 Vitrail 的前置**。这里有两个类似的 mod，这些 mod 里面有可借鉴的地方吗」
>
> 追加指令：「**目标是 26.3 及之后发布的新版本这点需要明确，目前主线以 26.3 为准。**」
>
> 许可证指令：「**本项目许可证选择 MIT**」
>
> 流程指令：「**开发测试流程文档，要把模组构建，运行 gradlew runClient 测试实际产物，
> 然后再根据错误去修复，直到完成所有的开发任务**」
>
> 性能与参考指令：「**模组要兼顾性能，部分需求可改成用 c++ 或 rust 实现。
> 每一部分的实现最好都先去找参考。**」

**新定位**：一个**独立**的 NeoForge 客户端模组 —— 基于**原版自带的 Vulkan 渲染后端**，
实现一个能加载 **OptiFine / Iris 格式** 着色器包的引擎。**许可证 MIT，完全自研。**

### 版本基线（已明确，权威文档 `05-VERSION.md`）

| 项 | 值 |
|---|---|
| **支持范围** | **MC 26.3 及之后发布的新版本** |
| **当前主线** | **26.3**（一切开发/验证/验收以它为准） |
| **不支持** | 26.2 及之前（那代没有 `renderpearl.backend.api`） |
| 许可证 | **MIT**（`LICENSE` + `gradle.properties` 的 `mod_license=MIT`） |
| 锁定：MC / NeoForge / Java / MDG | 26.3 / **26.3.0.41-beta** / 25 / 2.0.147 |
| 工程形态 | **官方 MDK `NeoForgeMDKs/MDK-26.3-ModDevGradle`**（commit `eec248c`），已铺入 `D:/Code/Minecraft/Shader-Core-Vulkan` |
| 未来版本 | 按 `06-MIGRATION.md` §4 流程升级，**不做前瞻兼容设计** |

- **不与任何第三方渲染模组做集成**（不依赖、不替代、不做「假 XX」），**完全不碰 Vitrail**（两者可共存）
- **不自己写 Vulkan 设备** —— 用原版 `com.mojang.renderpearl.backend.vulkan`
- 自研的部分：① OF/Iris 格式解析器 ② pass 编排 ③ GLSL 转译 ④ 选项 GUI
- **遇到原版 Vulkan 不支持的特性可以自行补充**，但必须先登记、必须收敛在 `platform/`、
  必须能在官方补上后一处回退（策略见 `12-GAP-STRATEGY.md`，登记表 `13-GAP-REGISTRY.md`）
- **MIT ⇒ 完全自研**：不得并入任何 LGPL / GPL / ARR 代码（`07-CONSTRAINTS.md` §〇）
- **开发循环**：改代码 → `./gradlew build` → `./gradlew runClient` 看真实产物 → 按错误修 →
  重跑，直到任务完成（`01-DEV-LOOP.md`）
- **两条前置纪律**：**参考先行**（每部分开工前先调研，`17-NATIVE.md` §1）+
  **先测后优**（`17-NATIVE.md` §2–§3）。顺序不可颠倒：**参考 → 测量 → 优化**
- **性能预算**（`17-NATIVE.md` §2）：不装包帧时间 ≤ 原版 **+2%**（P0 必过）；开中等包 ≤ Iris+OF 110%
- **原生加速（C++/Rust）**：**仅是「写进文档的可选项」，可行性未验证**（2026-09-29 用户追加：
  「C++/Rust 作为可选项先写进文档就行。实际是否可行等后续」）。
  **当前阶段一律纯 Java，不建 `accel/` 包、不配 CMake/cargo。**
  冷路径（解析/预处理/转译）**禁止**上原生（`17-NATIVE.md` §3.2）；
  将来若要上，须先过 §6.2 第 0 关可行性验证 + §5 六问 + 保留 Java 保底 + A/B 开关。
  **当前原生模块数 = 0**（`17-NATIVE.md` §6.1）

**核心新发现（推翻旧计划根基）**：26.3 原版自带**后端抽象层**
`com.mojang.renderpearl.backend.api.{GpuDeviceBackend, CommandEncoderBackend, BackendRenderPipeline, SpvModule}`
—— 这就是旧计划苦苦寻找而未得的"官方后端插口"。旧计划锁死在第三方渲染模组上，没往原版看。

---

## 1. 参考模组结论（用户问的重点）

| 模组 | 许可证 | 借鉴 | 核心价值 |
|---|---|---|---|
| **VulkanMod** | **LGPL-3.0** | ❌ 只读思路，不搬代码（本项目 MIT） | ⭐⭐⭐⭐⭐ `ExtendedRenderPipeline` 挂载模式、`ShaderManagerM` 注入点、`gl/VkGlProgram` 的 ID 映射、`GlUtil.vulkanFormat` 格式表、`shader/layout/*` 对齐工具 |
| **Sulkan** | **GPL-3.0** | ❌ **只能读思路，一行代码都不能抄** | ⭐⭐⭐⭐⭐ `VulkanDeviceShaderCompilerMixin` 证明第三方可在原版 Vulkan 上插 pipeline；`ShaderPipelines` 是官方 Builder 的活样板；帧图 `addPass` 插入法 |
| **Beryl** | **ARR** | ❌ 不可用 | 仅确认 VulkanMod 路线有人付费支持 |

> ⚠️ **MIT 决定之后：三个参考模组没有一个的代码能用。** 它们的价值全在"证明可行"+"该往哪打洞"。

**两个参考模组都完全不支持 OptiFine/Iris 格式**（全仓库 grep 为空）—— 这是真正的技术空白，
新方向没有现成轮子可造，必须自研。也没有任何参考模组做过"OF 包加载器"。

---

## 2. 关键决策

| # | 决策 | 日期 | 依据 |
|---|---|---|---|
| D1 | Sodium 是 **PolyForm Shield 1.0.0**（非 GPL），Noncompete 禁止竞争 | 2026-09-29 | 核对 LICENSE.md + Modrinth |
| D2 | 「假 Sodium」方案有法律风险，**放弃** | 2026-09-29 | PolyForm Noncompete/Competition |
| D3 | **整体切换到新方向**（原版 Vulkan 后端 + OF/Iris 引擎） | 2026-09-29 | 用户指令；风险清零 |
| D4 | 所有 GPU 操作走官方 `com.mojang.renderpearl.*` | 2026-09-29 | 用户约束：禁止重复造轮子 |
| D5 | ~~不抄 Sulkan(GPL)/Beryl(ARR) 代码；VulkanMod(LGPL) 可控移植~~ → **改为：三个参考模组一律只读思路、不搬代码** | 2026-09-29（2026-09-29 随 MIT 收紧） | 许可合规：MIT 与 LGPL/GPL 不同族 |
| D6 | 不向 Sodium 提 PR（CONTRIBUTING 拒绝任何 AI 生成代码） | 2026-09-29 | 合规 |
| **D7** | **支持范围 = MC 26.3 及之后；当前主线锁 26.3；不支持 26.2 及之前** | **2026-09-29** | **用户明确指令** |
| D8 | 不做前瞻性兼容设计，只做可迁移性隔离（`bridge` 包） | 2026-09-29 | 避免过度设计 |
| D9 | 只改计划、不动代码（用户本轮明确要求） | 2026-09-29 | 用户约束 |
| **D10** | **原版 Vulkan 不支持、而 OF/Iris 语义必需的特性，可自行补充；随官方更新动态调整** | **2026-09-29** | **用户明确指令**；收敛规则见 `12-GAP-STRATEGY.md` |
| **D11** | **工程换成官方 NeoForge 26.3 MDK（ModDevGradle）** | **2026-09-29** | 用户明确指令 |
| **D12** | **文档包收进工程内 `docs/`；旧方向文档归档**（**后于 2026-09-29 全部删除，见 D17**） | **2026-09-29** | 用户明确指令（清理历史遗留） |
| **D13** | **新增 `01-DEV-LOOP.md` 作为给 agent 派活的标准流程** | **2026-09-29** | 用户明确指令 |
| **D14** | **新增 `17-NATIVE.md`：兼顾性能 + 参考先行；C++/Rust 仅作可选项写入文档** | **2026-09-29** | **用户明确指令**（「模组要兼顾性能，部分需求可改成用 c++ 或 rust 实现。每一部分的实现最好都先去找参考。」） |
| **D15** | **C++/Rust 可行性未验证 → 当前阶段纯 Java；不建 `accel/` 包、不配原生工具链** | **2026-09-29** | 用户追加指令：「**C++/Rust 作为可选项先写进文档就行。实际是否可行等后续**」（`17-NATIVE.md` 状态声明） |
| **D16** | 🔴 **与 Sodium 彻底隔绝：不保留任何可选增强；零代码 / 零依赖 / 零集成 / 零兼容 / 零正面引用** | **2026-09-29** | **用户明确指令**：「**不保留，和 sodium 彻底隔绝开**」（`07-CONSTRAINTS.md` L11 + X18） |
| **D17** | 🔴 **旧方向归档文档全部删除，不留副本**（原 `docs/_archive/` 5 个文件 + `docs/_archive-开发计划-v2-旧方向.md`） | **2026-09-29** | **用户明确指令**：「**我已经把旧的文档存档删了，不要再去尝试恢复**」 |
| **D18** | 🔴 **调研的合规核对前置为第 0 步**：先核许可证 → 判不过就换参考、不再读它的代码 | **2026-09-29** | **用户明确指令**：「**还有一点需要写清，调研时一定要注意合规性**」（L12 + X19/X20/X21 + `17-NATIVE.md` §1.1.1） |

---

## 3. 原版 26.3 渲染 API（已从 jar 常量池核实）

85 个 `com.mojang.*` 渲染类型，69 个已迁入 Renderpearl：

```
com.mojang.renderpearl.api.*           前端 API
    GpuFormat / buffers.GpuBuffer(Slice) / commands.(CommandEncoder|RenderPass|GpuQueryPool)
    device.(GpuDevice|DeviceInfo|DeviceLimits) / pipeline.(RenderPipeline|BindGroupLayout|ShaderSource|CompiledRenderPipeline|BlendFunction|DepthStencilState|ColorTargetState|IndexType|PrimitiveTopology|UniformType|CompareOp|BlendFactor)
    textures.(GpuTexture|GpuTextureView|GpuSampler|FilterMode|AddressMode) / vertex.(VertexFormat|VertexFormatElement)

com.mojang.renderpearl.backend.api.*   ★ 后端 SPI（关键）
    GpuDeviceBackend / CommandEncoderBackend
    BackendRenderPipeline / BackendRenderPipeline$CreateInfo
    SpvModule / SpvModule$Reflection / $Descriptor / $InterfaceVariable

com.mojang.renderpearl.backend.vulkan.*   原版 Vulkan 实现
    VulkanDevice / VulkanCommandEncoder / VulkanRenderPipeline / VulkanRenderPass
    VulkanGpuBuffer / Texture / TextureView / Sampler / PhysicalDevice / VulkanUtils
    VulkanFeatureSets / init.(VulkanFeature|FeatureSet|VulkanPNextStruct)

com.mojang.renderpearl.frontend.*         FrontendRenderPipeline / shaders.SPIRVModule
```

**未迁入 renderpearl、仍在 blaze3d 的类型**（重映射时注意）：
`Blaze3D`、`ProjectionType`、`buffers.Std140Builder`、`pipeline.(PipelineCache|RenderTarget|TextureTarget)`、
`platform.(NativeImage|Window)`、`systems.(RenderSystem|SamplerCache|ScissorState)`、
`vertex.(BufferBuilder|ByteBufferBuilder|DefaultVertexFormat|MeshData|PoseStack|VertexConsumer)`

---

## 4. 工程与文档位置

- 工程根：`D:/Code/Minecraft/Shader-Core-Vulkan/`（已换成官方 NeoForge 26.3 MDK）
- 文档包：`D:/Code/Minecraft/Shader-Core-Vulkan/docs/`
- 🔴 **旧方向文档已于 2026-09-29 全部删除**（`docs/_archive/` + `docs/_archive-开发计划-v2-旧方向.md`）。
  **不要尝试恢复。** 只有 `docs/00`–`17` + `AGENT_CONTEXT.md` 是有效文档。

### 工程骨架（已落地）

```
Shader-Core-Vulkan/
├── LICENSE                  MIT 全文（2026-09-29 定）
├── gradle.properties        mod_id=vkdisp, mod_license=MIT, minecraft_version=26.3,
│                            minecraft_version_range=[26.3,), neo_version=26.3.0.41-beta
├── build.gradle             官方 MDK（ModDevGradle 2.0.147, toolchain Java 25）
├── settings.gradle          foojay-resolver 1.0.0
├── gradle/wrapper/          Gradle 9.4.1
├── .gitattributes           全仓库 LF（已是工作区实际行尾，不是只有声明）
├── src/main/java/dev/vkdisp/
│   ├── VkDisp.java          @Mod 主类，MOD_ID="vkdisp"
│   ├── VkDispClient.java    客户端入口（Dist.CLIENT）
│   └── VkDispConfig.java    模组自身配置（enabled / debugLog）
├── src/main/resources/assets/vkdisp/lang/  en_us.json / zh_cn.json
├── src/main/templates/META-INF/neoforge.mods.toml   ${mod_*} 占位待生成
└── docs/                    文档包（见下）
```

git 已初始化并提交（`51cb2b0` MDK 骨架 → `a6a0609` 文档清理 + 派活流程与特性缺口策略），
`core.autocrlf=false`，`core.filemode=false`，`gradlew` 索引内为 `100755`。
**本轮（许可证 MIT + 文档编号修正 + 开发循环文档）尚未 commit。**

### 文档清单（有效）

| 文件 | 说明 |
|---|---|
| **`00-INDEX.md`** | ★★★ 索引入口（新编号体系总览） |
| **`05-VERSION.md`** | ★★★ **版本权威**：支持 26.3 及之后，主线 26.3 |
| **`02-OVERVIEW.md`** | ★★★ 已按新方向重写 |
| **`03-DIRECTION.md`** | ★★★ 新方向总纲 + 参考模组分析 + 许可证 + 证据清单 |
| **`04-SPEC.md`** | ★★★ 组件清单 / OF uniform 表 / 构建配置 / 验收标准 |
| **`06-MIGRATION.md`** | ★★ `bridge` 隔离 + 升级流程（含缺口复查步）+ 回归清单 R1–R9 |
| **`01-DEV-LOOP.md`** | ★★★ **给 agent 派活用的纯步骤文档**（开发循环 + 埋点要求 + 终止条件） |
| **`12-GAP-STRATEGY.md`** | ★★ 原版不支持时可自行补充 + 动态调整 |
| **`13-GAP-REGISTRY.md`** | ★★ 补充策略的执行载体（先登记再实现） |
| **`17-NATIVE.md`** | ★★★ **性能预算 + 参考先行**；C++/Rust 仅作未验证的可选项（D14/D15） |
| `08-TESTING.md` | 阶段验收 + 回归清单 + **性能硬指标** |
| `07-CONSTRAINTS.md` | 许可证（§〇 MIT + P1/P2/P3）+ L1–L12 + 技术约束 T1–T16 + 红线 X1–X21 |
| `16-READING.md` | 阅读顺序（按新编号） |
| `15-ITERATION.md` | 三层防乱协议 |

**编号说明**：`09`–`11`、`14` 刻意未使用（视觉规范 / 运营 / 埋点 / 发布清单不适用）。

---

## 5. 待用户决策（阻塞项）

| # | 决策点 | 状态 |
|---|---|---|
| Q1 | ~~**本项目许可证？** LGPL-3.0 / MIT~~ | ✅ **已定（2026-09-29）：MIT**。`LICENSE` 全文 + `gradle.properties` 的 `mod_license=MIT`；由此产生 P1/P2/P3 三条硬约束，**VulkanMod(LGPL) 也只能读不能抄**（`07-CONSTRAINTS.md` §〇） |
| Q2 | ~~是否仍锁 MC 26.3？~~ | ✅ **已定**：支持 **26.3 及之后**，主线 **26.3** |
| Q3 | ~~**是否保留 Sodium 可选增强？**~~ | ✅ **已定（2026-09-29）：不保留，与 Sodium 彻底隔绝**（`07-CONSTRAINTS.md` L11） |
| Q3b | 是否需要与**其它**第三方渲染模组（Vitrail 等）做集成？ | ❌ **不需要**。一律零集成，地形走原版 `SectionRenderDispatcher` |
| Q4 | ~~旧 `Shader-Core-Vulkan/` 目录怎么办？~~ | ✅ **已决并执行**：清空旧内容，换成官方 NeoForge 26.3 MDK |
| Q5 | **P0 是否开工？** | ⏳ **待定**（用户当前只要计划 + 骨架，未授权写功能代码） |
| Q6 | **是否有场景必须用原生（C++/Rust）？** | ✅ **已定：现在不做。** C++/Rust 只是「写进文档的可选项」，**实际是否可行等后续**；真要做须先过 `17-NATIVE.md` §6.2 第 0 关可行性验证（D15） |
| Q7 | **C++/Rust 到底可不可行？** | ⏳ **未验证**。需先写最小 FFI demo 打通四平台（`17-NATIVE.md` §6.2 第 0 关），在此之前不投入 |

---

## 6. 硬约束速查

- `mixins.json` 的 `compatibilityLevel` **必须 `JAVA_25`**（`JAVA_21` 在 Java 25 下静默跳过 mixin）
- `mods.toml` 的 `[[dependencies.<X>]]` 表名必须等于 `modId`
- modId / 包名 **绝不要用**任何第三方渲染模组的名字（`vitrail` / `iris` / `optifine`，也不要 `sodium`）
- 🔴 **Sodium 彻底隔绝（L11 / X18）**：构建脚本零 `sodium`/`caffeinemc` 坐标、无运行时探测、无集成分支；代码里 `sodium` 只允许出现在「划清界限」的否定式语句中
- 顶点格式字段名必须与着色器 `attribute` 声明**字面一致**
- 仓库内需 `.gitattributes`（`* text=auto eol=lf`）+ 仓库级 `core.autocrlf=false`，
  并手工 `git update-index --chmod=+x gradlew`
- **clone 时若系统级 `core.autocrlf=true`，工作区文件会实际落盘成 CRLF** —— 光声明 `.gitattributes`
  不够，必须 `sed -i 's/\r$//'` 重写一遍（本工程已做）
- 通用重映射：`com.mojang.blaze3d.*` → `com.mojang.renderpearl.*`（简单名配对），
  例外 `RenderTarget`/`TextureTarget` 仍在 blaze3d.pipeline。完整表见 `Vitrail-Shaders/versions/26.3.remap`
- **每一部分开工前先找参考**（`17-NATIVE.md` §1，T13）：实现文件头部必须有【参考调研】注释块
- 🔴 **调研的第 0 步是核许可证**（L12 / X19 / `17-NATIVE.md` §1.1.1）：
  只信仓库里的 `LICENSE` 文件（不信平台页面）；**无 LICENSE = ARR = 不可用**；
  **「GPL + 例外条款」一律按禁止处理**；判不过就换参考，**别再读它的代码**
  （先读代码就无法自证实现未受影响）
- **性能有数字**（`17-NATIVE.md` §2，T14）：不装包 ≤ 原版 +2%；超预算必须定位到环节
- **冷路径不上原生**（`17-NATIVE.md` §3.2，X15）：解析 / 预处理 / 转译一律纯 Java
- **原生（C++/Rust）当前不做**（X17）：可行性未验证前，不许动原生代码、不许建 `accel/`

---

## 7. P0 的第一件事

**空模组 + 一个用 `RenderPipeline.builder()` 注册的全屏 pass，屏幕上要出现自定义图案。**
这一步决定整条路是否成立，务必最先做，不要先写解析器。

同时落实 `06-MIGRATION.md` §2 的 `bridge` 包隔离 —— 从第一天就让
"原版渲染 API 访问集中在一处"成立，否则后续每次版本升级都要全项目搜改。

**同时测第一个性能基线**：不开包时帧时间相对纯原版 ≤ +2%（`17-NATIVE.md` §2）——
这是着色器模组最不能踩的线（不装包也掉帧）。

派活方式照 `01-DEV-LOOP.md` §8 的 P0.1 / P0.2 / P0.3 目标卡。

---

## 8. 版本升级备忘（26.4 出现时）

1. 只改 `gradle.properties` 的版本号 → 编译 → 收集符号缺失错误
2. **只改 `bridge/` 与 `mixin/`**（业务包不得 import 原版渲染类型）
3. 复查 4 类易变点：渲染类型包名 / 后端 SPI 签名 / `LevelRenderer` 渲染方法签名 / `RenderPipeline.Builder` 链式 API
4. **复查 `13-GAP-REGISTRY.md`** —— 官方补上了就删掉自己的补充实现（`12-GAP-STRATEGY.md` §5）
5. 跑回归清单 R1–R9（见 `06-MIGRATION.md` §5）
6. **若含原生库 → 重新构建全部平台产物**（ABI / 依赖可能变；`17-NATIVE.md` §6.3）
7. **若后端 SPI 被移除或大改 → 立即停止升级、回滚**，触发架构级重评

**不要**用 IDE 批量 replace package 盲改 —— 26.3 那次搬迁**不完整**
（`RenderTarget` / `TextureTarget` / `RenderSystem` 没搬），盲改会改坏。

---

## 9. 交接快照（2026-10-01 · 141 阶段矩阵修复轮，任务停止前存档）

> 用户指令：本轮修改提交 → 写交接文档 → 交接文档同样提交 → 停止任务。
> HEAD：`399e6a3`（测试转正）← `21461ea`（留档）← `4acde09`（P4.3）；工作树干净，已推送 origin/master。
> `./gradlew build` exit 0：**572 tests / 0 failures / 0 skipped**（47 个结果 XML）。
> 本轮**唯一未完成项 = runClient 实测取证**（见 §9.2 第 1 步）—— **已于 2026-10-02 续轮闭环**：
> runClient 达标 `stages=190 ok=190 failed=0` + `registered=9 compiled=9 (aligned)`，首错遮蔽揭示的
> Distant Horizons 兼容新类同轮修掉并登记 GAP-002；§9.4 记续轮结果。

### 9.1 本轮完成（都已提交推送）

第二跑实测 `pack compile done: stages=190 ok=96 failed=94` —— 首错从 141 降到 94，
四个旧错误类**全部归零**（证据口径 = 错误原文，内部编号 21461ea 提交信息里有串号，以本表为准）：

| 错误原文类 | 修复落点 | 说明 |
|---|---|---|
| `undeclared identifier`（旧内建 gl_MultiTexCoord0 / gl_TextureMatrix / Position …） | `LegacyBuiltinInjector` | 141 主类；第一版「注入 gl_ 声明」被第二跑 reserved 否决 → **本会话重写为替换+注入**（下详） |
| `SPIR-V requires location`（顶点 in 缺 location ×36） | `IoLocationAdapter` | P4.4：顶点 in 也补 `layout(location)`；绑定键仍是名字（PipelineBuilder 按 element.name() 查反射表） |
| `can't use function syntax on variable`（texture 目标名冲突 ×44 FRAGMENT） | `TextureFunctionRenamer` 第二阶段 | ⑦ 前的改名撞上包声明的同名变量 → 声明位/实参位等全部非调用位改写 `texture_N` |
| `redefinition`（dh/voxy 顶层**无关键字**同名全局被再注入块成员，D 类 6×FRAGMENT + 8 潜伏） | `UniformInjector` `TOP_LEVEL_GLOBAL` 登记 | 只登记「已声明」不改写行文本；范围收窄：整行 `;` 结尾 + `{}` 深度 0 + 仅 23 条 catalog 名 |
| （诊断口径）同行多语句 WARN 行号被插入行右移 | `OfGlslTranslator` 级间映射回填 | ⑤ 后诊断经 ⑤ 级映射 ∘ 上游回填，`OfGlslTranslatorTest.diagnosticsAfter…` 锁定 |

**reserved 第二波（本会话核心）**：补声明落地后驱动改报
`identifiers starting with "gl_" are reserved` ×91（stage=VERTEX）+ `texture2DGradARB` ×3 ——
**GLSL 公开词法保留 gl_ 前缀，用户代码声明与使用皆非法 → 注入 gl_ 名这条路被根本否决**，
`LegacyBuiltinInjector` 全量重写为「**token 级等行替换 + 属性声明注入**」：

- **属性类替换（仅 VERTEX）**：`gl_MultiTexCoord0→UV0`、`gl_MultiTexCoord1→UV2`、
  `gl_Color→Color`、`gl_Normal→Normal`、`gl_Vertex→位置操作数`（与 `FtransformExpander`
  同口径：包内声明 vec4 → 名字直接用；否则 `vec4(name, 1.0)`；未声明 →
  `vec4(Position, 1.0)`）。映射与 `GlslDeclarationExtractor.ATTRIBUTE_ALIASES` 同源
  （Iris 属性兼容档 ↔ 04-SPEC §4 表）。
- **矩阵类替换（任何阶段）**：`gl_ProjectionMatrix→gbufferProjection`、
  `gl_ModelViewMatrix→gbufferModelView`、`gl_ModelViewProjectionMatrix→(投影×视图)`、
  `gl_NormalMatrix→(transpose(inverse(mat3(模型视图))))`（OpenGL 公开法线矩阵定义）、
  `gl_TextureMatrix[n]→mat4(1.0)`（下标随 token 消费；裸名 / 声明行保留交驱动 T11）。
- **注入只剩**「用而未声明」的裸 `in vec4 UV0; / in vec4 UV2; / in vec4 Color; /
  in vec3 Normal; / in vec3 Position;`，由 ⑥ 补 location；行数 = 原行数 + 注入数，
  `Result.insertIndex/insertedLineCount` 行号契约不变。
- **声明行保护**：表达式型替换遇该旧名显式声明行不动 token（否则声明名位落进表达式打坏语法）；
  纯标识符替换声明 / 使用同步换名；`gl_Vertex` 声明行改名 `Position`。
- **幂等**：第二遍无 gl_ token（替换门关）+ Position 已 layout 声明（注入门关）→ 逐字节不变、零诊断。
- **texture2DGradARB → textureGrad**（`TextureFunctionRenamer.renames()`；ARB 扩展 →
  330 core 四参同名同参重命名；自动进 `targetNames()` 冲突消解集合）。
- 测试：`LegacyBuiltinInjectorTest` 共 19 例（DH 兼容 stub 两组 dhMaterialId + DH_BLOCK_* 为续轮新增）；`OfGlslTranslatorTest` 端到端改内容寻址
  + 新增顶点 ⑤+⑧ 合成映射 e2e；`TextureFunctionRenamerTest` +2（RENAME_CASES 行 + 四参 golden）。
  `FtransformExpander.POSITION_CANDIDATES` 改包级可见；`OfGlslTranslator` ⑤ javadoc 同步
  （**编排逻辑未变**，⑤ 仍可能插行，级间映射 / compose 语义全部沿用）。

### 9.2 下一步（按序执行即可续轮）

1. **runClient 取证**（缺的硬证据；日志里 `vkdisp: pack compile done:` 行 = `VkDispPackScan.java:220`）：
   ```bash
   mv run/logs/latest.log run/logs/pre-141fix-$(date +%s).log   # 每次启动前隔离旧日志
   source tools/vulkan-local/env.sh
   export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
   ./gradlew runClient -PquickPlay --console=plain
   ```
   达标线：**`stages=190 ok=190 failed=0`**；同时核对 `pipeline count … registered=9, compiled=9`、
   Missing uniform=0、解析失败=0、fullscreen pass failed=0。⚠️ **首错遮蔽**：还有失败就会有新类
   冒出来把旧的藏住 —— 同轮修掉或显式登记（T11），不许带着未知类收轮。游戏退出后再对日志取 sha256。
2. 证据文件 `evidence/p4xx-141-matrix.md`（G-01 格式：一行复现 + sha256 表 + 判定表 + 未覆盖登记；
   模板 `evidence/p418-options-gui.md`）+ `evidence/README.md` 索引行。
3. 文档同步：`08-TESTING.md` §1/§6；`18-PARALLEL.md:475` 陈旧行；可选 `04-SPEC.md` §3.2 补矩阵
   语义一句（gl_* → gbuffer* 的对应表已在 `LegacyBuiltinInjector` 类注释）。
4. `CHANGE_LOG.md` 15-ITERATION 条目（素材 = 两个提交信息 `21461ea` + `399e6a3` + §9.1 表；
   口径：四类归零 + reserved/texture2DGradARB 第二波 + 572 单测）。
5. 收尾 commit + push（轮次纪律：每轮结束都提交并推送）。

### 9.3 已登记课题（不阻塞主线）

- 单位纹理矩阵的**视觉正确性**（截图轮验证 UV 采样无回归 —— 替换语义对不对最终看画面）；
- Complementary / Sildur 包扫描（BSL 打通后）；Iris 对比 = 环境极限（登记不追）；
- dh duplicate-WARN（先于本轮存在）；pack 自声明 gl_ 名（声明行留原样 → 驱动可见）；
  表外旧名（`gl_MultiTexCoord2` / `gl_TextureMatrixOffset` / `gl_TexCoord`）→ 真被用时驱动
  T11 报错再扩表（X9 不猜）；
- 属性绑定数据的**运行时正确性**（vec4 输入绑 vec2 格式沿 mc_Entity 先例，画面轮确认）。

### 9.4 续轮结果（2026-10-02 · 141 矩阵修复轮闭环）

- **runClient 取证达标**：`pack compile done: stages=190 ok=190 failed=0`（L2536）+
  `pipeline count check: registered=9, compiled=9 (aligned)`（L2581）；零 `undeclared identifier` /
  `are reserved` / `Missing uniform` / `解析失败` / `fullscreen pass failed`。日志
  `run/logs/runclient-dhfix2.log`（547015 B，sha256 `812f5cd11e709516f2232a7bf26379d055339f7d537306f745400d820d97ae9e`）。
- **首错遮蔽第二轮（同轮修掉、显式登记）**：141 修复落地后失败 141→6，揭示 BSL `dh_*` 引用的
  Distant Horizons 注入符号（`dhMaterialId` / `DH_BLOCK_WATER` / `DH_BLOCK_LAVA` /
  `DH_BLOCK_LEAVES` / `DH_BLOCK_ILLUMINATED` / `DH_OVERDRAW`）—— 本引擎不集成 DH
  （07-CONSTRAINTS D3/D16）→ 在 `LegacyBuiltinInjector` 注册为普通全局 stub 并登记
  `13-GAP-REGISTRY.md` **GAP-002**（INFO 显式诊断，T11 不静默）。DH 类为已知类，非未知类收轮。
- **代码改动**：`LegacyBuiltinInjector` 扩 DH stub（6 符号）+ 裸声明正则扩 `const int` 双限定符形态
  （幂等判据需识别 `const int DH_BLOCK_* = N;`）；`LegacyBuiltinInjectorTest` 新增 dhMaterialId +
  DH_BLOCK_* 两组（共 19 例）；全仓 `./gradlew test` 576 用例 0 失败。
- **文档同步（§9.2 第 3 步已全部执行）**：`evidence/p4xx-141-matrix.md`（G-01）、`08-TESTING.md` §1/§6、
  `18-PARALLEL.md` ⑥-2（旧 `stages=190 ok=49 failed=141` 标为修复前基线）、`04-SPEC.md` §3.2、
  `13-GAP-REGISTRY.md` GAP-002、`CHANGE_LOG.md` 2026-10-02 条目。
- **未覆盖（登记）**：BSL 包选项 `PARAMETER` 默认值非法（STRING 型 default '1.00'）的 WARN 是包自身
  元数据怪癖（派生 '0.00'），不影响 `failed=0`，非本轮引入；DH 几何真实渲染（DH 不集成）；切包残留
  回归本轮未重跑（未动顶点格式链路，见 `evidence/p417-pack-switch.md`）。
- **收尾**：commit + push（轮次纪律）待执行（续轮 §9.2 第 5 步）。

### 9.4.1 续轮（2026-10-02 二）— P4.1 BSL 视觉正确性基线

- **P4.1 视觉验证**：141 矩阵修复后确认 BSL 实际渲染连贯、无静默破坏。⚠️ **本会话 Agent 不支持查看图片**
  （PNG Read 被内容过滤拒绝），视觉判读改 **luma 量化分带**（p418 同源口径）+ 日志诊断 + **用户目检** 三方交叉。
- **A/B**：`shaderPack=""`（BSL）vs `shaderPack="none"`（passthrough，config 热加载切）截图 luma ——
  BSL content 20.9 / 天空 6.9 / 地面 26.6；passthrough content 34.1 / 天空 28.3 / 地面 38.3
  （BSL 偏暗、天空 0.24×，因相机朝天且世界时钟冻结黎明）。渲染连贯（无全黑/全白/彩色尖刺）、零 vkdisp ERROR；
  **用户目检确认 BSL 观感正常**（更暗=晨昏风格化非缺陷）→ P4.1「BSL 主要效果可用」达成（Iris 对比局限内）。
- **证据**：`evidence/p4x1-bsl-visual.md` + 截图 `run/screenshots/p41-bsl-world.png`(sha `049a579e…`) /
  `p41-none-world.png`(sha `d11a2e98…`)。
- **未覆盖（登记）**：§9.3 待登记课题（单位纹理矩阵视觉正确性 / 属性绑定运行时正确性）本轮仅 luma 量化未见异常，
  未逐效果目检；Iris 金标准对比=环境极限；相机固定朝天+黎明，未验证正午/夜间/不同朝向。

### 9.4.2 续轮（2026-10-02 三）— G 线 G0：Java 冷路径分段基准

- **G0 已完成并推送**（`4dc951c`）：新增 `src/test/java/dev/vkdisp/pack/ColdPathBenchmark.java`
  + `evidence/g0-java-coldpath.md`。BSL 91 program / 182 阶段的分段基线已建立，
  **G1（等价 Rust 实现）的前置条件已满足**。
- **怎么跑**（证据文件里有同一份，一行复现）：
  ```bash
  ./gradlew compileTestJava
  java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
      --inventory run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 9 \
      --out evidence/g0-java-coldpath.md --golden build/bench-golden --notes "..."
  ```
  纯 CPU、离屏、不需要游戏客户端；`--label/--notes` **只收 ASCII**（中文 argv 会被 locale 解成乱码），
  主机信息由 `/proc/cpuinfo` 自动采集。
- **基线数字**（Ryzen 7 8745H / JDK 25.0.4.1，n=9 中位数）：
  解析 **588.7ms (40.9%)** > `#include` 预处理 **572.7ms (39.8%)** > 转译 **246.2ms (17.1%)** >
  包扫描 0.5ms；**四段合计 1440.5ms**，生产入口 **1419.4ms**。
  排序在七趟里稳定可引用，**具体百分比不要当精确值**（噪声内浮动）。
- 🔴 **G1/G3 开跑前必读的噪声红线**：本机未锁电源/频率，七趟四段合计中位数
  1438.0…1571.0ms，**极差 ≈9%**，**与 §5.2 的 20% 裁决阈值同量级** ⇒ 必须两侧**交替**测量、
  样本 ≥9、同时报 p95，**禁止拿单次最好值比值下结论**。详见 `17-NATIVE.md` §7.3 末。
- **golden 在 `build/bench-golden/`（不入库）**：364 条 sha256 清单，
  自身 sha256 `a1f372aa…`。G1 的等价性测试按它逐字节比对；
  不入库是因为内容派生自 BSL（第三方素材，`18-PARALLEL` §7.6），按上面命令可重新生成。
- **下一轮入口（二选一，按定位重定后的优先级）**：
  ① **G1**：Rust 等价实现（范围仅 `glsl/` 预处理+转译、`pack/` 解析）。⚠️ 8 段流水线的
     **逐字节**等价是硬门槛，建议先只做预处理段（占 39.8%，且边界最清楚）再攻转译段。
  ② **H 线 M-01**：管线装配层 mixin（GAP-003 多附件 + GAP-004 自定义 uniform 块**必须同批**），
     支柱①兼容的最大阻塞项 —— 但需 `runClient` 取证，且要遵守 X28（先登记 `04` §5.0）/ X29（逐个开启）。

### 9.4.3 续轮（2026-10-02 四）— G 线 G1 第一段：Rust `#include` 展开等价实现

- **成果**：**BSL 182/182 阶段逐字节一致，0 不一致**，Rust 单测 11/11。
  证据：`evidence/g1-rust-include-equivalence.md`。对照工程 `~/Minecraft/g1-rust-bench`（commit `2086d22`）。
- **本机装了 Rust 1.99.0**（rustup，minimal profile，`~/.cargo`）。此前**没有**任何 Rust 工具链，
  这是 G1 的硬前提，装在仓库外、只影响用户目录。
- **Java 侧本轮新增**（`ColdPathBenchmark`）：
  - golden 从两相扩到**三相**：`inc`（仅 include 展开）/ `pre`（define+const）/ `trans`（8 段转译），
    182 阶段 × 3 = **546 条** sha256。G1 因此可以**逐相**对齐，而不是对着最终产物猜。
  - `--dump-input <dir>`：导出**输入契约**（292 个文件的解码后文本 + `stages.txt` + 清单哈希）。
    Rust 侧因此**不必引第三方 crate 去解 zip** —— I/O 本就不在被测分段里（合规第 0 步零风险）。
  - `--golden-only`：只重建 golden，**不跑计时、不改 evidence**。
    没有它，G1 每对齐一次就要冲掉一遍基准数字、连带同步三处文档。
- **怎么跑**（`g1-rust-bench/README.md` 有同一份）：
  ```bash
  export PATH="$HOME/.cargo/bin:$PATH"
  # ① vkdisp 侧导出
  ./gradlew compileTestJava
  java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
      --inventory run/shaderpacks --pack BSL_v10.1.8 \
      --golden build/bench-golden --dump-input build/g1-input --golden-only
  # ② Rust 侧门槛
  cd ~/Minecraft/g1-rust-bench && cargo test --release
  ./target/release/g1-inc-check --input <vkdisp>/build/g1-input/BSL_v10.1.8 \
                               --golden <vkdisp>/build/bench-golden/BSL_v10.1.8
  ```
- **移植时踩到的四个 Java/Rust 语义差异**（已按 Java 语义修 + 单测锁定，详见证据文件）：
  ① `String.strip()` 用 `Character.isWhitespace`，**不含** U+00A0/U+2007/U+202F，而 Rust
  `trim()` **含** ⇒ 自建 `java_strip`；② Java 正则 `\s` 只有 ASCII 六种；
  ③ `normalize()` 吃一个结尾换行 + `split(-1)` 再丢一个末尾空串，两步不能合并；
  ④ 那个 include 正则的 `\s*` 贪婪但**不可能回溯成功** ⇒ 手写扫描等价。
- 🔴 **本轮没有证明的**：性能（Rust 单次冷跑 52.7ms，n=1；且 **Java 侧还没有 inc 单独计时**，
  G0 的预处理段是 include+define+const 合并）⇒ **G2 / G3 不得开始**。
  另外 `SourceLineMap` 未移植、诊断文案未逐条比对、只跑过 BSL。
- **下一轮入口（二选一）**：
  ① **G1 define 相**：移植 `DefineProcessor`（宏与条件编译，28KB Java）——靶子就是 golden 的 `pre`。
     这是当前最重的一块，可能要拆成多个小轮。
  ② **G1 转译相**：8 段流水线，单类 20–30KB，最重。
  ③ **补 G3 前置**：给 `ColdPathBenchmark` 加一趟「inc 单独计时」的 Pass C（不扰动既有四段口径），
     否则 G3 两侧没有同口径数字。

### 9.4.4 续轮（2026-10-02 五）— G 线 G1 第二段：Rust 宏与条件编译等价实现

- **成果**：**pre 相 182/182 逐字节一致**（连同上一轮的 inc 相，G1 已 2/4 相）。
  证据：`evidence/g1-rust-equivalence.md`（已由 `g1-rust-include-equivalence.md` 改名，两相合一）。
  对照工程 `~/Minecraft/g1-rust-bench` commit `96ec088`；Rust 单测 **32/32**、`cargo build` 0 告警。
- **怎么跑**（改过 Rust 源码后**必须先 `cargo build --release`**，否则跑的是旧产物）：
  ```bash
  export PATH="$HOME/.cargo/bin:$PATH"
  cd ~/Minecraft/g1-rust-bench && cargo test --release && cargo build --release
  for phase in inc pre; do
    ./target/release/g1-check --phase "$phase" \
        --input  <vkdisp>/build/g1-input/BSL_v10.1.8 \
        --golden <vkdisp>/build/bench-golden/BSL_v10.1.8
  done
  ```
- **pre 相的移植面**：`DefineProcessor`（693 行 Java）—— 指令分发、对象/函数宏、
  递归宏展开（`expanding` 集合防环）、以及一个完整的**递归下降 `#if` 表达式求值器**
  （`defined()`、比较、逻辑、算术、`Double.parseDouble` 口径）。
  **行号映射不在范围**：Java 侧 `inputLineMap` 只喂诊断，文本产物完全不依赖它。
- **本轮新增的两个语义坑**（前四个见证据文件）：
  ⑤ `substitute` 用 `replaceAll("\\b" + Pattern.quote(p) + "\\b", arg)` —— Java 的
     `\w`/`\b` 默认**只认 ASCII**，且替换串里 `\` 与 `$` 有转义语义 ⇒ 手写
     `replace_all_word_bounded` + `expand_replacement` 逐条复刻；
  ⑥ `Character.isLetterOrDigit` ≠ Rust `is_alphanumeric`（后者含 Nl/No）⇒
     `java_is_digit` 对非 ASCII 取保守 false，并把任何非 ASCII 字符记进
     `non_ascii_ident`（本轮 BSL 计数为 0）。
- 🔴 **pre 相第一次只跑出 133/182，两个坑叠在一起**（详见证据与 README）：
  ① **真 bug —— 尾切片下标空间搞混**：`take_ascii_ident` 返回的 `after` 是 `chars` 的
     尾切片、起点不在 0，而扫描 `(`…`)` 却按绝对下标从 1 开始 ⇒ 参数表与宏体整体错位。
     难发现是因为**症状伪装成「部分正确」**（多参宏第一个参数不生效、第二个是对的），
     而且**手写的简单片段单测全绿** —— 只有拿真实包跑才暴露（BSL 49/182 触发）。
     回归测试 `func_define_with_tail_slice_offsets` 用真实数据锁住。
  ② **假线索 —— 跑的是旧二进制**：修完没重新 `cargo build --release` 就跑检查器，
     对着一个**已经不存在的 bug** 又查了一轮。教训：**「跑出来不对劲」的第一反应
     应该是确认产物最新，而不是先看代码。**
- **下一轮入口（三选一）**：
  ① **G1 转译相**（最重）：`OfGlslTranslator` 8 段流水线，单类 20–30KB，需分多轮；
     大概率重演「片段单测全绿、真实数据才炸」，每轮都拿真实包验。
  ② **补 `pack/` 解析相的 golden**：该相目前**没有靶子**，需先在 `ColdPathBenchmark`
     里给 `properties/options 解析` 段补一相导出。
  ③ **补 G3 前置**：给基准加一趟 inc / pre **单独计时**（像已有的 Pass B 那样另起一趟，
     不扰动既有四段口径），否则 G3 两侧没有同口径数字。

### 9.4.5 续轮（2026-10-02 六）— G3 前置：分相对照数据（两侧第一次有同口径数字）

- **成果**：新增 `evidence/g3-preliminary-phase-comparison.md` + `17-NATIVE.md` §7.4。
  🔴 **不是 G3 裁决**（§5.1 要求 G1 先完成），但**终结了「两侧没有同口径数字」的阻塞**。
- **两侧各加一个同口径的分相计时**（都不扰动既有口径）：
  - Java：`ColdPathBenchmark --phase-timing` —— 预处理段拆成 inc / def / const 三段，
    并在计时前逐阶段核对产物与 golden 逐字节一致（182/182 通过）；
  - Rust：`g1-check --mode bench` —— 同样的预热/样本/中位数+p95 口径。
  - 两侧都吐 `G3DATA` 机器可读行，供交替编排抓数。
- 🔴 **口径对称性（这才是关键）**：Rust 的 def 直接读 golden 的 inc 产物，
  所以 Java 的 def **必须**用**计时外缓存**的 `IncludeProcessor.Result`，
  否则 def 的耗时里会混进 include 的工时 —— 那样两侧就不是同一个口径。
- **数据**（9 轮交替，跨轮中位数，BSL 182 阶段）：

  | 相位 | Java | Rust | 比值 |
  |---|---:|---:|---|
  | inc | 158.8ms | 39.6ms | **Rust 快 75.1%**（跨轮极差仅 1.4%，结论稳） |
  | def | 76.4ms | 118.9ms | **Rust 慢 55.7%** |
  | const | **243.9ms** | **未移植** | — |

  交替协议确实把噪声压住了：inc/def 跨轮极差 1.4%–2.8%，远低于 G0 整趟的 ≈±9%。
- 🔴 **本轮最大的发现**：**const（选项常量扫描）243.9ms，占预处理段 51%，且不产出任何文本变化**
  （类注释原文「本处理器**不修改**文本」）。也就是说 **G1 移植的两段恰好是较小的 49%**。
  三种假设下 const 的归属直接决定裁决方向：
  不移植 → Rust 反而慢 16%；按 inc 的速度移植 → 快 54%；按 def 的速度（保守）→ 快 34%。
  ⇒ **在搞清 const 之前，「要不要上 Rust」没有答案。**
- ⚠️ **def 那 1.56× 的限定**：是**这个首版实现**的数，不是语言天花板。已排查并**排除分配瓶颈**
  （把 `replace_all_word_bounded` 改成全字节扫描 + 未命中不分配，def 只从 118.2 → 118.3ms），
  瓶颈在「逐字符推进 + 替换后重扫」的结构上。**不许拿它论证「Rust 不行」。**
- **另一条更便宜的路**（本轮不实施，但要记）：`ConstEvaluator` 对每行无条件跑两条正则
  （约 190 万行次，绝大多数立即失配），「先 `startsWith` 挡一道再上正则」是**纯 Java、
  小改动、低风险**的优化。若它能吃掉大部分 243.9ms，同样的收益**不需要 FFI / 四平台产物 /
  panic 边界防御 / 未来 ABI 维护** —— 而这些正是 §5.2 里 20% 阈值要显著超过的持续成本。
- **怎么跑交替测量**（两侧命令见证据文件「口径对称性」一节）：
  ```bash
  export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
  for r in $(seq 1 9); do
    java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
        --inventory run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 9 \
        --golden build/bench-golden --phase-timing | grep G3DATA
    ~/Minecraft/g1-rust-bench/target/release/g1-check --mode bench --warmup 3 --iterations 9 \
        --input build/g1-input/BSL_v10.1.8 --golden build/bench-golden/BSL_v10.1.8 | grep G3DATA
  done
  ```
- **下一轮入口（按优先级）**：
  ① **P1（推荐）**：实测 Java 侧「先挡后正则」能把 const 砍掉多少 —— 便宜、低风险，
     且直接决定 const 是否还需要 Rust；
  ② **P2**：决定 const 移植与否（它是分界线）；
  ③ 才是 G1 转译相（Java 4190 行，最重）—— 建议**等 P1/P2 的结论**再投入。

### 9.4.6 续轮（2026-10-02 七）— P1：const 前缀守卫（只 4.9%）+ 假设被证伪

- **成果**：`evidence/p1-const-prefix-guard.md`。前缀守卫**已落地**（可证明等价、A/B 产物一致、
  4 条边界单测、留 A/B 开关），但**只省 12.0ms / 4.9%**。
- 🔴 **本轮真正的产出是证伪**：「const 的 244ms 是正则主导」是**错的**。
  - 独立探针：同一批 311,902 行上，两条正则合计仅 **~18ms**（`^const` 是字面量前缀，
    Java 正则失配时本来就很便宜）。
  - JFR 采样（const 相 128 采样）：**`strip` 18.0% + `split` 17.2% ≈ 35%** 是字符串分配，
    正则入口约 27%，循环簿记 37.5%。
- **对决策的影响**：
  - 「用廉价前置判断替掉 const 的 Rust 移植」这条便宜路径**走不通**（4.9% ≪ 20% 阈值）；
  - 动摇了 §7.4 的**假设 B**（const 按 inc 的 4.01× 移植 → 快 54%）——
    inc 的 4× 优势来源正是「跳过正则」，而 const 的正则本来就不贵（**推断，非实测**）；
  - 天平已偏向「不值得为 const 上 Rust」，但**尚未裁决**。
- **等价性怎么证的**（const 产物不含文本，只能靠清单）：新增 `--dump-const` 落盘
  「选项 + 诊断」全量清单，A/B 两次落盘 **sha256 相同**（51,336 条选项、0 诊断）。
  守卫的可证明性来自两条 pattern 的 `^` 锚定，以及 `originOf` 越界返回 UNKNOWN_LINE、
  **永不抛**（所以推迟它不改变异常语义）。
- **新增单测 4 条**（最容易被人改坏的边界）：行内含关键字但**行首没有**（若写成 `contains`
  就会误认）、strip 后仍应放行、前缀相符但语法不符应正常失配、冲突禁用+WARN 必须照旧走到。
  ⚠️ 写其中一条时我误判了 `^const\s+(…)\s*=\s*([^;]+);` 的行为 —— `[^;]+` 可以把
  `= ` 后面的空格吃掉，于是**空值 const 本来就会命中**。这是既有怪癖，与守卫无关，
  已单独用 `emptyConstValueStillMatchesPattern` 留痕。
- **测试结果**：607 → **612 单测全绿**；`--phase-timing` 另起一趟，**G0 的四段口径未动**。
- **下一轮入口**：
  - **P2**（裁决用）：const 移植与否 —— 分界线，且天平已偏；
  - **P3**（工程用）：对 const 做**结构性改写**（单遍扫描、只对可能命中的行 strip）并单独取证；
    注意它是 **Java 侧改动，不进 G 线的 FFI 账**，别和 G 线的裁决混在一起；
  - 才是 G1 转译相（Java 4190 行）—— 建议等 P2 有结论。

### 9.4.7 续轮（2026-10-02 八）— P3：const 单遍实现（再省 3.0%）+ 一条方法论教训

- **成果**：`evidence/p3-const-single-pass.md`。`ConstEvaluator` 拆成两条实现
  （`-Dvkdisp.const.singlepass` 选择）：原实现 `split` + 逐行 `strip`；
  **单遍实现**不物化行数组、按 `indexOf('\n')` 滚动区间、只用下标求 strip 等价区间、
  **只有前缀通过的行才 `substring`**。冲突消解抽成共用 `finish(...)` 保证后半段口径一致。
- **等价性**：`--dump-const` 清单 **51,336 条选项 + 0 诊断，两条实现与 P1 基线三方同哈希**。
  新增 4 条**行边界**单测（单遍最容易错的不是正则，是行数/行号）：
  `split` 的「丢一个末尾空串」口径、空文本 0 行、**空白行必须占行号**、
  尾部 `\r` 按空白裁掉、行内含关键字但行首不是。**612 → 616 单测**。
- **数据**：234.7 → **227.6ms（3.0%）**；P1 + P3 累计 **246.1 → 227.6ms = 7.5%**，
  仍远低于 §5.2 的 20% 阈值 ⇒ **Java 侧微优化 const 的路线到此为止**。
- 🔖 **本轮最值钱的是那条教训（不是那 3.0%）**：
  **「JFR 采样占比」≠「可优化空间」**。P1 的 JFR 指认 `split`+`strip` ≈ 35%，
  真做掉只省 3%，原因：**GC 发生在别的线程上**（按栈含 `ConstEvaluator.evaluate` 过滤
  根本采不到 GC 线程），而 `split` 产生的是短命年轻代对象、bump 分配极快、
  JIT 逃逸分析还能吃掉一部分。
  ⇒ **以后看到「某方法占 X%」，先问「有多少是可消除的」，再决定要不要动手。**
- 🔴 **顺带暴露的更大空白（下一轮应优先）**：G0 四段里
  **`properties/options 解析` 占 43%（约 681ms），是整条冷路径最大的单块，
  却从未被 profile、也没有 G1 的 golden** —— 比 const 更大、更黑。
  建议下轮做 **PP（profile parse）**：先用 JFR + `parse/` 源码定位它的时间去向，
  再决定是「Java 侧可优化」还是「值得进 G1 移植」。
- **测试结果**：`./gradlew build` BUILD SUCCESSFUL；**616 单测全绿**；
  `--phase-timing` 另起一趟，G0 四段口径未动。
- **怎么 A/B 测**：
  ```bash
  java -Dvkdisp.const.singlepass=true|false -cp build/classes/java/test:build/classes/java/main \
       dev.vkdisp.pack.ColdPathBenchmark --inventory run/shaderpacks --pack BSL_v10.1.8 \
       --warmup 3 --iterations 9 --golden build/bench-golden --phase-timing | grep G3DATA
  java -Dvkdisp.const.singlepass=true|false -cp … ColdPathBenchmark \
       --inventory run/shaderpacks --pack BSL_v10.1.8 --dump-const /tmp/ab/<mode>   # 等价性
  ```

### 9.4.8 续轮（2026-10-02 九）— PP：发现生产路径把预处理算两遍（修后快 39.3%）

- **成果**：`evidence/pp-parse-profile.md` + `17-NATIVE.md` §7.5。
- 🔴 **G0 四段口径有误**：JFR 采样「含 `ShaderPackService.load`」的栈，约 **48%** 其实是
  预处理与声明提取 —— `load` 为了提取 uniform / 顶点属性，对每个 program 的两个阶段
  各跑一次完整 `GlslPreprocessor.analyze`，与「`#include` 预处理」段量的是同一件事。
- 🔴 **顺线索发现的真实缺陷**：`ShaderPackCompiler.compile` 里，`load` 算一遍（为提取声明）、
  `compileStage` 又算一遍（为转译）⇒ 生产冷路径上**同一批 182 个文件预处理被算两遍**。
  （`compile` 原有注释已承认「`load` 内部已 plan 过一次」，说明重复被知道，只是只想到
  `MountPlan`、没往预处理上想。）
- **修复**（三处）：
  ① `GlslPipeline.runPreprocessed(stage, preProcessed)` 新入口，**`analyze` 的后半段改为
     委托给它** ⇒ 两条路径共用同一段诊断合并代码，结构上杜绝逻辑分叉；
  ② `ShaderPackService.load` 增加重载，回填「源文件路径 → 预处理产物」sink；
  ③ `ShaderPackCompiler` 在**无选项覆盖**时建 sink、`compileStage` 命中即复用。
     ⚠️ 有覆盖时 resolver 被改写，两次预处理本就不同，**必须各算一次** —— 这是显式判断，
     不是隐含约定。
  开关 `-Dvkdisp.reuse.preprocess=false`（既是 §7.3 红线要求的交替测量手段，也是回退保险）。
- **等价性**：新增 `--dump-compile`，把 182 个阶段的**文本 + 逐条诊断 + 包级诊断**全量落盘；
  原路径与复用路径 **sha256 相同**（`181eb949…`），374 条包级诊断一致。616 单测全绿。
- **数据**（9 轮同二进制交替，预热 3、样本 9）：
  原路径 **1458.5ms**（p95 1613.8）→ 复用 **885.0ms**（p95 989.6），
  **快 573.5ms / 39.3% / 1.65×**，纯 Java 无 FFI。
  跨轮极差 18.9%–26.4%（生产入口含 zip I/O 与整轮 GC），
  但效应量远大于噪声。
- ✅ **G0 口径已修（2026-10-02 九）** —— `evidence/g0-caliber-fix.md`。**本轮改的是仪器。**
  分段表**四段 → 三段**：`load` 本就为提取 uniform/属性跑过一遍完整预处理，
  旧表又单列一行「`#include` 预处理」把它**加了两遍**，还把加载段错叫成
  「properties/options 解析」掩盖了这一点。段名改为「加载与预处理」，
  **转译段的输入直接取自 `load` 交出的产物**（与生产路径同源）。
  修正后 n=21：加载与预处理 **635.1ms（72.8%）**、转译 **226.5ms（26.0%）**、
  扫描 0.5ms，生产入口 **895.3ms**。
  ✅ 新增**口径交叉校验**自检（分段三段 vs 生产入口，容差 ±25%，实测 +2.7% ✅）——
  这道自检**当场抓出我写错的分母**（拿程序数当阶段数，报了「182/91」）。
  ⚠️ 旧表的「解析 43.2% / 预处理 34.9%」**量的是同一件事**，已作废。
- ✅ **G 线在新基线上重估 + const 移植（Round 10）** —— `evidence/g4-recheck.md` + §7.6。
  🔴 **口径纠正**：§5.2 的阈值是「Rust **端到端**中位数快 ≥20%」，**必须对整条冷路径算**，
  此前几轮一直在用预处理口径谈这件事。基准取修正后的生产入口 895.3ms。
  const 移植后（182/182 等价）端到端 **26.6%–27.7%**。
- ✅ **补 G2 闸门 + def 去倒挂（Round 11）** —— `evidence/g2-ffi-boundary.md`。
  🔴 **此前把 FFI 边界开销当成了 0**。实测后：按批（550 次）**0.030ms ⇒ 端到端 36.3%**；
  按条（623,804 次）33.7ms ⇒ 32.5%；**按条 + 每次重取句柄 1243.6ms ⇒ −102.6%**。
  🔖 **T18「按批不按条」直接决定 G 线成不成立**。
  ✅ **N5 首次拿到运行时举证**（`always_panics()` 返回 −1 且 JVM 存活）。
  **def 从「慢 1.59×」修成「快 1.75×」**（122.9 → 44.1ms），判定是**实现差距非语言天花板**
  （同工程里 inc 快 3.28×、const 快 4.67%，唯独 def 慢）。计数坐实宏逻辑几乎不干活
  （311,720 行里只有 **128 次函数宏替换**）；分段计时显示 `handle_directive` 占 **84%**。
- 🔴 **36.3% 是上界，不是净收益**：持续成本一分没扣；大载荷传输开销未测；
  **G1 仍差转译相（4190 行）与 `pack/` 解析相 ⇒ 按 §5.1，G3/G4 仍不得开始**。
- ✅ **大载荷 + 转译相评估（Round 12/13）**：按批 + 大载荷下端到端 **36.3% → 35.7%**；
  🔖 **载荷变大不增加跨界成本**（5.917ms 里跨界机制只占 0.009ms；净跨界从 1KB 起为负，
  16MB 档 Rust 快 1.94×）。**B4 埋点已偿债验证**：端到端 3294ms vs 预编译 77ms
  ⇒ **资源重载段 3260ms（99.0%），瓶颈在资源重载本身**，不在编译也不在缓存。
  转译相 207.0ms（23.1%）、8 段无一独大 ⇒ **Lead 建议不做**
  （4190 行换 +14.7%，性价比是已完成的 1/3.6；且转译出错是**画面不对**，X27）。
- 🔴 **Round 12 我自己犯的错**：同时派「跑客户端的」和「跑基准的」，三者抢 CPU 污染了数据
  ⇒ 已立 `AGENT_CONTEXT §9.4.13`：**客户端与基准不得重叠，必须串行并显式放行**；
  「测试全绿」不等于「数字可信」。
- ⏭️ **下一轮入口（三项）**：
  ① **查清 B4 那 3260ms 资源重载的成分** —— 并量化 **lavapipe（软件 Vulkan）的失真倍数**
     （若 190 个管线的 SPIR-V 编译在软件驱动上占大头，B4 数字就不代表真实硬件）；
  ② **FFM 回传方向**（Rust→Java）—— G2 最后一个未测方向；
  ③ **G 线持续成本估算** —— 35.7% 是上界，扣掉构建链/四平台产物/ABI 维护后剩多少尚未估。

### 9.4.9 续轮（2026-10-02 十）— 客户端取证：复用预处理在真实客户端里确认（−22.6%）

- **成果**：`evidence/client-verify-preprocess-reuse.md`。
- **补上了什么**：上一轮 `pp-parse-profile` 明确登记「**没有 runClient 取证**」——本轮补上。
- **顺带补上的基建缺口**：`VkDispPackScan` 的资源加载完成入口**此前没有任何冷路径耗时打点**
  （只有切包路径有 `pack precompile done in {} ms`）。已加：
  ```
  vkdisp: cold path timing: scan={} ms compile={} ms total={} ms (initial={}, reusePreprocess={})
  ```
  日志带 `reusePreprocess` 实际取值 ⇒ **以后每趟客户端日志都能自证走的是哪条路径**。
  （`ShaderPackCompiler.REUSE_PREPROCESS` 因此改为 public。）
- **客户端实测**（`./gradlew runClient`，停在主菜单、`initial=true`，190 阶段）：

  | 实现 | scan | compile | 冷路径合计 |
  |---|---:|---:|---:|
  | 原路径 | 849ms | 2633ms | **3482ms** |
  | 复用 | 721ms | **1975ms** | **2696ms（−22.6%）** |

- **行为等价**：`stages=190 ok=190 failed=0`、0 ERROR/WARN、日志行数相同（1563）、
  **190 条 SPIR-V 产物（包×程序×阶段×文件×字节数）逐条一致**。
- 📌 **为什么客户端 −22.6% 而离线 −39.3%，但绝对收益更大（786 vs 573.5ms）**：
  客户端的 `compileAndLog` 还要把每个阶段交给**原版驱动做 GLSL→SPIR-V 编译**，
  这部分**不受本次优化影响**，被加进两边分母。绝对收益一致 ⇒ **离线的 39.3% 没有虚高**。
- 🔴 **没有证明的**：**每侧只有 1 趟（n=1）**且两趟**非交替**（OFF 跑第二趟、页缓存更热
  ⇒ 偏差是压低 ON 的，真实收益可能 ≥ 22.6%）；**切包路径（B4）未取证**；
  `PackCompileCache` 交互未验；**未进世界目检**（画面项未做）；**G0 四段口径仍未修**。
- **下一轮入口（三项）**：
  ① **修 G0 四段口径** —— `runPass` 仍把 `load` 当「纯解析」量；
  ② **切包路径（B4）取证** —— `pack precompile done in {} ms` 已有埋点，直接可测；
  ③ **G 线在新基线上重估**。

### 9.4.10 续轮（2026-10-02 十一）— G0 口径修正：四段改三段 + 交叉校验自检

- **成果**：`evidence/g0-caliber-fix.md`。**本轮改的是仪器，不是数字。**
- **改了什么**：
  ① 分段表**四段 → 三段**。旧口径里 `load` 单列为「properties/options 解析」，
     又另有一行「`#include` 预处理」把 Σ `GlslPreprocessor.preprocess` 加进去 ——
     但 `load` 为了提取 uniform / 属性声明**本来就已跑过一遍完整预处理**
     ⇒ 分段合计把预处理**算了两遍**，而「解析」这个名字掩盖了这一点。
  ② 段名改为「**加载与预处理**」——**名字不能继续骗人**。
  ③ **转译段的输入直接取自 `load` 交出的预处理产物**（复用 sink），
     既不重复计算，又保证**转译吃到的输入与生产路径完全同源**。
  ④ 新增**口径交叉校验**自检：报告固定带一行「分段三段 vs 生产入口」，容差 ±25%。
- 🔖 **为什么这道自检是必需的**：旧口径的失败模式正是「**每段都合理、合起来偏大**」——
  光看单段发现不了。有了它，口径一漂报告就自己喊。
- **容差取 ±25% 的理由（两头夹逼）**：下限 —— 实测同机同构建这段差值在
  **−3.8% ~ +12.1%** 间摆，太低会误报；上限 —— 要防的「预处理算两遍」会表现为
  **+48%**（463/1460），太高就抓不住。
- ✅ **自检当场抓出我自己的 bug**：第一版拿「阶段数」和 `pack.programs().size()` 比
  （程序数 ≠ 阶段数，一个程序有 VERTEX/FRAGMENT 两阶段），
  于是报了荒谬的「复用 182/91 个阶段」。修正分母后不再报警。
  —— **这正是自检该干的事：让口径错误当场暴露，而不是等人看百分比才发现。**
- **修正后数据**（n=21，预热 3，BSL 182 阶段）：
  包扫描 0.5ms(0.1%)、**加载与预处理 635.1ms(72.8%)**、**转译 226.5ms(26.0%)**、
  分段三段合计 872.1ms、生产入口 **895.3ms**；**交叉校验 +2.7% ✅**。
- ⚠️ **所有基于旧四段表的结论都要重新审视**：旧表的「解析 43.2% / 预处理 34.9%」
  **量的是同一件事**，已作废。Rust 侧的 inc/def/const 分相**不受影响**
  （它读 golden，本来就没有那一行重复计算），变的是**基线**。
- 🔴 **踩到的小坑**：`writeEvidence` 只在传了 `--out` 时才跑；
  第一次重跑漏了这个参数，控制台表已是新的而证据文件仍是旧的 —— 差点误以为没生效。
- **测试结果**：616 单测全绿。
- **怎么重跑（注意 `--out` 是必需的）**：
  ```bash
  export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
  java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
    --inventory run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 21 \
    --golden build/bench-golden --out evidence/g0-java-coldpath.md \
    --label "G0-r9-口径修正后" --notes "三段口径：预处理已含在加载段，不再单列"
  ```

### 9.4.11 续轮（2026-10-02 十二）— Round 10：G 线在新基线上的重估（裁决悬在 const）

- **成果**：`evidence/g4-recheck.md` + `17-NATIVE.md` §7.6（新增）+ `18-PARALLEL.md` G 线进度表。
- 🔴 **本轮是「重估」不是「裁决」**：§5.1 要求 G1 完成才能进 G3/G4，而 G1 还差
  **转译相**与 **`pack/` 解析相**。本轮回答的是 §7.5 末尾那个悬着的问题（「G 线需在新基线上重估」）。
- 🔖 **本轮最重要的口径纠正**（写下来是因为它容易再次搞错）：
  - §5.2 的阈值原文是「**Rust 端到端中位数**快 ≥ 20%」⇒ **必须对整条冷路径算**，
    **不能只对预处理算**。拿预处理内的快慢比 20% 阈值 = **拿小分母比大阈值**，必然虚高。
  - 基准取**口径修正后**的生产入口 **895.3ms**（n=21，`evidence/g0-caliber-fix.md`），
    **不是旧的 1494.9ms**。
  - ⚠️ 1494.9 → 895.3 的落差**主因是 PP 的预处理复用优化，不是口径修正**（口径修正改的是
    「这些数字分别代表什么」）。另：895.3 与 `pp-parse-profile` 的 885.0 是同一路径的两次独立测量，
    **未做差异归因**。
- **分相数据**（9 轮交替，与 G3 前置同协议，本轮重跑）：

  | 相位 | Java | Rust | 比值 | 跨轮极差 |
  |---|---:|---:|---|---:|
  | inc | 160.6 ms | 40.0 ms | **Rust 快 75.1%**（4.01×） | 13.3% / 8.9% |
  | def | 77.1 ms | 119.3 ms | **Rust 慢 54.6%** ⚠️ | 4.8% / 13.7% |
  | const | **228.1 ms** | **未移植** | — | 7.3% |

  三段合计 **465.8ms**，**const 占 49.0%**。⚠️ 极差是**跨轮极差**不是 p95，
  且**五个相的极差全部由第 9 轮抬高**（原始表里第 9 轮在五列各有一次全局最大值），
  中位数对单轮离群稳健，**但成因未取证**。
- **四情形 × §5.2**（基准 895.3ms，四者唯一差别是 `const` 归谁）：

  | 情形 | const | 节省 | 端到端快 | §5.2 |
  |---|---|---:|---:|---|
  | S1 当前范围 | 不移植 | 78.4 ms | **+8.8%** | 🟡 暂缓（快 5%–20%） |
  | S2 乐观 | 按 inc 的 4.01× | 249.7 ms | **+27.9%** | ✅ 落「采用」行 |
  | S3 保守 | 按 def 的 1.55× | −46.1 ms | **−5.2%** | ❌ Rust 慢 |
  | S4 下界 | 全不采用 | 0.0 ms | **0.0%** | ❌ 差异 <5% |

- ⚠️ **分解（本轮第二个关键数）**：**只移植 inc = +13.5%（120.6ms），加上 def 反而降到 +8.8%**
  ⇒ **def 的负贡献吃掉 42.1ms**。当前 G1 范围里唯一让数字变差的段就是 def。
- ⚠️ **S2/S3 的 const 速度是假设、不是实测**：两端（4.01× 与 1.55×）之间**没有任何 const 实测点**，
  属于**用现有两点连线外推第三点**。且 §7.4 已动摇 S2 的前提（inc 的 4× 优势来自「跳过正则」，
  而 const 的正则本来就不贵，独立探针量得仅 ~18ms）⇒ **不许按 27.9% 做规划**。
- ⚠️ **Round 9 的口径修正不改变本结论 —— 变的是基准**：
  旧四段表把预处理算两遍，但**分相对照读的是 golden**，Rust 侧本来就没有那一行重复计算，
  比较的「同一相 Java vs Rust」两边被测对象都没被算两遍。**真正变的是分母**（1494.9 → 895.3，
  分母变小 ⇒ 同样的绝对节省换算成百分比后变大）—— 这正是不重算就会出错的地方。
- ✅ **等价比照重跑确认**：inc / pre **各 182/182 逐字节一致**、Rust 单测 **32/32**
  ⇒ §5.2「输出一致性不通过 ⇒ ❌」这行**不触发**（但只覆盖已移植的两相，const 无一致性结论）。
- 📌 **附带发现（跨模式一致性校验）**：本轮三段 465.8ms vs 修正后 G0「加载与预处理」635.1ms
  ⇒ 预处理占加载段 **73%**，余 **169.3ms** 是**声明提取 + 选项组装 + zip 读取**。
  两条记录来自不同测量模式/轮次/样本数却落在同一量级 ⇒ 两种口径没打架；
  但它同时说明**端到端里 Rust 完全没碰过的部分至少有 169.3 + 226.5（转译）≈ 396ms**
  ⇒ 「只挑快的段移植」会引入 Java↔Rust 往返，**这正是 §5.2 里 20% 阈值要覆盖的持续成本**。
- ⚠️ **顺带修正 §7.4 的一处旧表述**：「顺带暴露的更大空白 —— `properties/options 解析` 占 43%（约 681ms）」
  已被 PP 修正为 **169.3ms**（差额是重复计算的预处理）。**以 §7.6 为准。**
- ⏭️ **下一步的顺序**：
  1. **先测 `const`**（哪怕只做一个结构不同的实现）—— 唯一能把 S1/S2/S3 分开的动作；
  2. **或在第二个包上复测一轮** —— 满足 §5.2 🟡 档「在生产量最大的包上复测」的要求
     （本文件单趟数据**不能**充抵那一次复测）；
  3. 两者都做完再谈 **G2（FFM）**；G1 的**转译相与 `pack/` 解析相仍必须补齐**（§5.1 硬顺序，不可跳）。
- **测试结果**：本轮**未改代码**，无新单测；Rust 单测 32/32 为重跑确认。
- **怎么重跑**（⚠️ 命令能跑通但**数字会变**；Rust 侧 `--phase` 取 `inc`/`pre`，而**吐的标签是 `def`**）：
  ```bash
  export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
  export PATH="$HOME/.cargo/bin:$PATH"
  # Java 分相
  java -cp build/classes/java/test:build/classes/java/main dev.vkdisp.pack.ColdPathBenchmark \
    --inventory run/shaderpacks --pack BSL_v10.1.8 --warmup 3 --iterations 9 \
    --golden build/bench-golden --phase-timing | grep G3DATA
  # Rust 分相
  ~/Minecraft/g1-rust-bench/target/release/g1-check --mode bench --phase inc|pre \
    --warmup 3 --iterations 9 \
    --input build/g1-input/BSL_v10.1.8 --golden build/bench-golden/BSL_v10.1.8 | grep G3DATA
  ```

### 9.4.12 续轮（2026-10-03 十三）— 团队并行三路：补 G2 剩余 + 验上一轮的埋点 + 转译相评估

- **本轮三路并行，写范围严格互斥**：

  | 队友 | 任务 | 写范围 |
  |---|---|---|
  | `ffi-big` | 大载荷 FFM 传输开销（G2 唯一未测的运行时代价） | `g2-ffi-demo/` + `FfmBoundaryProbe.java` + `evidence/g2-large-payload.md` |
  | `b4-verify` | 客户端验证 Round 11 已提交但**从未验证**的两处生产改动 | 仅 `evidence/b4-end-to-end-verify.md` |
  | `trans-scout` | G1 转译相 4190 行的**范围评估**（不移植） | `ColdPathBenchmark.java`（仅新增 `--trans-timing`）+ `evidence/trans-stage-profile.md` |

- 🔴 **本轮最要紧的一件事**：`b4-verify` 要验的是 Round 11 那笔**悬着的技术债** ——
  B4 端到端埋点挂在 `ClientResourceLoadFinishedEvent` 上，**而它到底会不会在热重载路径上触发，
  上一轮完全没验证**。若不触发，那个「修」是**死的**，B4 仍然只看得到 precompile 那一段。
  **队友不改生产代码**（发现 bug 报给 Lead 改，这是既有分工）。
- 🔴 **纪律沿用**：队友**不 git commit**，Lead 统一验证与提交；
  **Lead 对每个结论独立复核，不采信自报数字**（Round 10/11 都是这么做的，
  也确实抓出了「队友测 4 轮 vs §7.3 要求 n≥9」以及「我自己传中文 `--label` 的老坑」）。
- **运行纪律**：`b4-verify` 跑客户端 —— **禁止 `pkill -f GradleDaemon`**，只 `kill <游戏 pid>`，
  且必须贴出「残留游戏进程数=0」的证明（用户明确要求主动结束游戏进程）。

### 🔴 9.4.13 测量污染纪律（2026-10-03 补，因 Round 12 的一次事故）

> Round 12 我同时派了「跑 runClient 的队友」和「跑基准的两个队友」，结果基准数字被污染。
> 这是 **Lead 的协调失误**，由 `trans-scout` 发现并正确处置（一批数字全部作废、停手等信号）。

## 铁律

1. **任何基准运行前先确认机器是安静的**：
   ```bash
   cat /proc/loadavg
   ps -eo pid,pcpu,etimes,args --no-headers | awk '$2>20'
   ```
   load 明显偏高、或存在别的 `runClient` / 基准进程 ⇒ **数字一律不可用**。
2. 🔖 **团队并行时，跑客户端的任务与跑基准的任务不得重叠**，必须**串行并显式放行**：
   跑客户端的人负责**收尾时 kill 游戏进程并通知 Lead**，由 Lead 发信号放行基准重测。
   光「看一眼 load」不够 —— 必须有**谁放行、谁确认**这一环。
3. 🔴 **污染的特征极其隐蔽**：日志照打、测试照过、`./gradlew build` 照绿，
   **只有数字悄悄变了**。所以**「测试全绿」不等于「数字可信」**。

## 如何识别污染（可复用判据）

`trans-scout` 复跑三次的回不来形状，本身就是指纹：

| 相位 | 干净基线 | 复跑 1 / 2 / 3 |
|---|---|---|
| inc | 161–162.5 | 175.4 / 176.2 / **213.3** |
| def | 77.0–77.3 | 82.2 / 82.9 / **89.7** |
| const | 227.9–231.7 | 246.1 / 246.3 / **283.1** |

🔖 **一贯噪声是 ±9%，而第三跳的幅度远超噪声带，且单调恶化** ——
这个形状本身就是污染的指纹，**不是**随机噪声。
⇒ **若复跑出现「逐次单调恶化」，先查机器状态，别急着怀疑自己的代码。**

## 9.5 环境与红线速查（详见持久记忆 + §6）

- 一切 java/gradle 前缀 `export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"`（隧道 hook.so 否则 EINVAL）；
- 禁止前台 `sleep`、禁止 `pkill -f GradleDaemon`（游戏进程 `kill <pid>` 可以）、禁止 `--rerun-tasks`、
  解压用 Python zipfile；hash 证据在游戏退出后取；
- **输入注入已封**（XTEST/xdotool/ydotool/wtype 及任何替代 = 同样失败结论）：证据只走配置热加载 +
  进程内组件调用（p416/p417/p418 同源法）；窗口 id 每次启动都变，枚举用 `x11_capture.py --list-windows`；
- 红线不变：IrisShaders/glsl-transformer **按禁止处理零代码并入**（MIT only）、X9 不猜测
  （javap/字节码算验证）、T5 业务包不 import `com.mojang.renderpearl.*`/blaze3d（bridge/ 例外）、
  L11/X18 `sodium|caffeinemc` 零命中、`docs/` 归本机 env-1。

---

## 10. 交接快照（2026-10-03 · G2 收口 + B4 定位轮，任务停止前存档）

> 用户指令（本轮原话）：「**写交接文档并提交推送当前所有的更改，不用管是否完成**」。
> ⇒ 本轮**不追求把事情做完**，只要求把「**现在在哪 / 下一步做什么 / 哪些还没证明**」写清楚再停。
> **未完成 ≠ 已完成**：本节里写「⛔ / 🟡 / 未证明」的，续轮一律按未完成处理，不许引用成已交付。
>
> **仓库状态**：HEAD `18f9bcf`（G2 回传方向 + B4 重载成分 + `17-NATIVE.md` §7.7 收敛）
> ← `0f87a1b` ← `fbc0461`；交接前 `git status` 干净、`origin/master` 无未推提交；
> **本轮唯一改动 = 本节 + `CHANGE_LOG.md`（二十一）条目**，随本节一并 commit + push。
> **最近一次全量验证在 HEAD 那次提交**：`./gradlew build` BUILD SUCCESSFUL、
> **617 单测全绿**（探针 0 个 `@Test`）、Rust `g2-ffi-demo` 17/17、残留游戏进程 = 0。
> **本轮交接未重跑构建与 runClient**（按用户口径）⇒ **续轮第一件事先跑一遍 `./gradlew build` 确认**。
> **当前无 teammate 在跑**（`list_agents` 仅 lead）；团队编制上限 8，近两轮已改为**复用成员**而非新建。

### 10.1 三支柱现在各在哪（唯一度量口径，别用形容词）

| 支柱 | 唯一度量 | 现状 | 判定 |
|---|---|---|---|
| ① **兼容** | pack × program 兼容矩阵（`08` §10） | BSL **190 阶段全绿** + 视觉基线可用（`evidence/p4xx-141-matrix` / `p4x1-bsl-visual`）；**H 线 M-01/M-01b 已落地**（派生地形管线真的被地形 draw 用上 + 自定义块每帧绑定，`evidence/h01-terrain-pipeline-wire.md`）—— **但 gbuffer 多附件（GAP-003）仍未做**，且真瓶颈已定位在 **render pass**（原版地形 pass 只有 1 个颜色附件）⇒ 须先做 M-04 | 🟡 **通道已通，最大阻塞项从「零代码」推进到「多附件」** |
| ② **稳定** | 崩溃 / validation = 0；无静默降级 | 每轮 runClient 取证 0 条 vkdisp ERROR（余 2 条 = narrator/OpenAL 环境性）；G2 实测**崩过一次 JVM（SIGABRT）**，根因（悬垂指针 + `Vec` 扩容重分配）已定位、形态 C 可规避 | 🟡 靠逐轮取证维持，**未做 1h 内存增量测量（B6）** |
| ③ **高性能** | B1 帧时间 ≤+2%；B3 冷路径 ≤1s；B4 切包 ≤2s | 见下表 | 🟡 **B4 明确不达标，且瓶颈已定位** |

**③ 的三条预算实测**（口径不同不许混算）：

| # | 目标 | 实测 | 判定 / 出处 |
|---|---|---|---|
| **B1** 不开包帧时间 | ≤ +2% | **本轮未测**（上一次 P0.1 时代） | ⏳ **交接时是空白**，续轮若动热路径必须补 |
| **B3** 冷路径 | ≤ 1s（中等包） | 离线生产入口 **895.3ms**（n=21）✅ / 客户端实测 **2696ms**（含驱动 GLSL→SPIR-V）❌ | ⚠️ **两条口径未统一，B3 判定悬空**（`g0-caliber-fix` vs `client-verify-preprocess-reuse`） |
| **B4** 切包墙钟 | ≤ 2s，缓存命中 ≤ 0.5s | 冷切 **2262–2986ms** ❌ / 缓存命中 **703–740ms** ❌；端到端（含渲染线程）**5.3–7.3s**；最新埋点：端到端中位数 **3294ms**，其中**资源重载段 3260ms（99.0%）**、预编译仅 77ms | ❌ **两条腿都超预算**；`b4-pack-switch` / `b4-end-to-end-verify` |

### 10.2 G 线（Rust vs Java 冷路径）—— 数字**只有一个出处**

🔖 **引用 G 线任何数字请引 `docs/17-NATIVE.md` §7.7**，并连带分母出处（噪声红线 ±9%）。
本节只放闸门状态，**不复制数字**（复制必错 —— §7.6 与 §7.7 的 const 就是新旧两版）：

| 闸门 | 状态 | 卡在哪 |
|---|---|---|
| **G0** Java 基线 | ✅ 完成（口径已修正为三段） | — |
| **G1** 等价 Rust 实现 | 🟡 **3/5 相**（inc / def / const 各 182/182 逐字节一致） | 🔴 **`pack/` 解析相未移植**；转译相（4190 行）**评估过、Lead 建议不做** |
| **G2** FFM 打通 demo | ✅ 完成（边界 + 大载荷 + 回传三向全测） | 接口形态已定 **C（写进 Java 堆外缓冲）** |
| **G3** 对照报告 | 🟡 数据已就位 | 依赖 G1 |
| **G4** 裁决 | ⛔ **不得开始** | §5.1 要求 G1 完成；**且只有 BSL 一个包**（§5.2 🟡 档要求第二个包复测） |

🔖 **当前端到端数字 35.5% 是上界、不是净收益** —— 构建链 / 四平台产物 / FFM 接线 /
panic 边界防御 / ABI 维护这些**持续成本一分没扣，且都还没发生**。

🔴 **一个必须由人裁的矛盾（交接重点）**：§5.1 把「G1 完成」设为 G3/G4 的硬门槛，
而 G1 的第 5 相（转译相）Lead 已给出**「现在不要做」**的结论（单位行数收益只有预处理的 1/3.6、
转译出错是画面不对）。⇒ 要么**补移植**、要么**由用户裁决放宽 §5.1 并写回文档**，
**两者都没做 ⇒ G4 现在无解，不许自行跳过。**

### 10.3 上一轮（CHANGE_LOG 二十，2026-10-03）交付了什么

1. **G2 回传方向测完 ⇒ 接口形态定为 C**：A/B/C 三形态整包 1.183–1.207ms、≥1KB 全落噪声内
   ⇒ 选 C（不付性能代价，拿掉 use-after-free 与漏 free 两个正确性风险）；端到端 **36.3% → 35.5%**。
   🔴 附带一次真实 JVM 崩溃（SIGABRT）修正了「句柄应当缓存」的旧说法（前提 = 地址稳定）。
2. **B4 那 2926ms 拆开了**（`evidence/b4-reload-profile.md`）：`openResources`/`generateSources`
   **705ms（24.1%）** + 原版资源重载 **1743ms（59.6%）** + `scanAndLog` **388ms（13.3%）** +
   驱动 SPIR-V ~35ms（1.2%）+ GC 161ms 单列 ⇒ **vkdisp 自己占约 1093ms ≈ 37.4%**。
   ✅ lavapipe **不是**成因（561 个原生采样里无 lavapipe/LLVM/glslang 帧）；⚖️ 失真倍数**仍量不出来**。
3. **文档收敛**：新增 `17-NATIVE.md` §7.7「G 线当前状态汇总」= **单一出处**。
4. `.gitignore` 补 `core.*`（这次崩溃的 core dump 落过仓库根）。

### 10.4 下一步（按序执行即可续轮）

1. **先跑 `./gradlew build` 确认 HEAD 还绿**（本轮没跑），并复核 `git log -1` 与本节一致。
2. **B4：攻资源重载段**（3260ms 里的 99%），不是继续优化编译或缓存（那只碰 1%）：
   - 🔴 **先澄清一处归属**：`scanAndLog` 的 **~700ms** 与 `trans-scout` 归给
     `openResources`/`generateSources()` 的 **705ms** 数值高度接近 ——
     **可能是同一件事，本轮没验证** ⇒ 用埋点把两者分开，**别在没分开前当两块优化**；
   - 样本补到 **≥5 组**（现 4 组，§7.1 未达标）；原版那 **1743ms** 未细拆；
   - ⚠️ **跑 runClient 与跑基准不得重叠**（§9.4.13 铁律），收尾 `kill <游戏 pid>` 并贴残留=0 证明。
3. **G 线二选一（先裁再动）**：补 `pack/` 解析相 / 裁决放宽 §5.1 转译相要求；
   之后才谈第二个包复测与 G3/G4。复现命令见 `17-NATIVE.md` §7.7「复现」块
   （`JAVA_TOOL_OPTIONS` 与 `PATH` 前缀不可少）。
4. ~~**H 线开工准备**~~ → ✅ **已完成（2026-10-03 两轮）**，见 §10.7。
   5. 🔴 **续轮第一入口 = M-04 的取舍分析**（不是直接写代码）：
      原版主 pass 把**地形/实体/特性/云/描边画在同一个 pass 同一个单附件**（源码级核实），
      ⇒ 给地形加附件会让所有原版管线不匹配。必须先在
      **A（地形单独一个多附件 pass）** 与 **B（整 pass 多附件 + 为四类 draw 各派生一份管线）** 之间选。
      🔖 **这属于「换一条路」级别的影响面，建议由用户裁决**（同本节「必须由人裁的矛盾」性质）。
      登记表：`04-SPEC.md` §5.0 M-04 行 + §5.0.4 的取舍表。
5. **遗留小项**（不阻塞，但别忘了）：① 切包路径上**复用闸门默认关闭**（`REUSE_PREPROCESS &&
   overrides.isEmpty()`）的 A/B —— 决定性一臂只有 n=2/侧，**勿当定论**；② B1 未复测；
   ③ `PackCompileCache` 逐条淘汰已修，但 **3 包库存就撞顶 8/8** 的容量问题仍在；
   ④ BSL zip 的 mtime 被 `touch` 过（内容未变），会让下一次缓存键不同。
6. **收尾**：`CHANGE_LOG.md` 15-ITERATION 条目 + commit + push（每轮结束都提交推送）。

### 10.5 明确**未完成 / 未证明**的（不许当已完成引用）

- **G1 未完成** ⇒ 按 §5.1，**G3/G4 不得开始**；**35.5% 是上界**，持续成本未扣；
- **只有 BSL 一个包**（宏密度 / 选项密度不同，比值会变）；**未测并发跨界 / 跨平台**；
  `Arena` 泄漏只有结构性保证；大载荷只测到 16MB（真实整包 17,298,868 字节略超）；
- **B4 的原版资源重载 1743ms 未细拆**；lavapipe 失真倍数**未量化**（本机无真实 GPU，不编数）；
- ~~**H 线一行代码都没写**，兼容支柱①的「完整」二字当前**没有实现支撑**~~
  → **2026-10-03 已过时**：M-01/M-01b 已落地并取证（§10.7）。
  **但 GAP-003 多附件仍未做** ⇒ 「完整」二字**依然没有实现支撑**，只是阻塞点更精确了（M-04）。
- 「**用 Rust 重写整个渲染引擎**」这一诉求**尚未裁决**（`review/2026-10-02-阻塞项与开放问题登记.md` §3.1）；
- **B1 / B6 本轮无数据**；B3 的离线与客户端两条口径**未统一**。

### 10.6 纪律指针（续轮开工前扫一眼）

- 🔴 **测量污染**：`§9.4.13` —— 跑基准前确认机器安静；**跑客户端与跑基准串行并显式放行**；
  「测试全绿」≠「数字可信」；复跑**逐次单调恶化** = 污染指纹（不是随机噪声）。
- 🔖 **判据本身要先验证**（2026-10-03 新增，见 §10.7）：**截图有差异 ≠ 你的改动造成的差异**。
  先做**同状态连拍**证明画面稳定，再用差异归因。本轮差点把一次区块加载期抖动
  写成「关掉 mixin 画面变暗」—— 那是一条方向恰好对改动不利的错误结论。
- **环境**：`§9.5` —— `JAVA_TOOL_OPTIONS` 前缀、禁前台 `sleep` / `pkill -f GradleDaemon` /
  `--rerun-tasks`、证据 sha256 在游戏退出后取。

### 10.7 ✅ H 线 M-01/M-01b 已落地（2026-10-03，支柱①开工）

> 证据：`evidence/h01-terrain-pipeline-wire.md`。
> 🔖 **§10.1 与 §10.5 写的「⛔ H 线一行代码都没写」已过时** —— 通道通了，但 GAP-003 仍未做。

| 项 | 状态 |
|---|---|
| **M-01** `ChunkSectionLayer#pipeline` | ✅ 6/6 派生管线被地形 draw 取用（日志逐条打出**我方 location**） |
| **M-01b** `ChunkSectionsToRender#renderLayers` | ✅ 自定义块每帧绑定，0 `Missing uniform` / 0 validation error |
| **M-04** `LevelRenderer#addMainPass`（方案 B） | ⏸️ 已登记（`04-SPEC` §5.0），**未实现** |
| **M-05** `LevelRenderer#prepareChunkRenders*`（方案 A 入口） | ✅ **已实现并取证**（`evidence/h03-…`）：捕获命中 **indirect** 分支、非 null、**时序成立**；**不改任何渲染行为**（ON/OFF 截图逐字节相同） |
| GAP-003 多附件 | ⛔ **未做** |
| GAP-004 块被消费 | ⛔ **未做**（地形片元仍是原版 `core/terrain`，不读我们的块） |
| 登记点改判 | ✅ `renderLayers` → `ChunkSectionLayer#pipeline`（理由见 `04-SPEC` §5.0.1） |

🔖 **GAP-003 的真瓶颈已定位：render pass，不在管线**（源码级核实）——
原版地形 pass 由 `LevelRenderer.addMainPass` 的
`createRenderPass(name, colorView, Optional.empty(), depthView, …)` 建出，**颜色附件恰好 1 个**
⇒ 管线侧加附件必然与 pass 不匹配。

🔖 **两条路线的风险等级已实测拉开**（2026-10-03，见 §10.9）：
**方案 A（地形单独一个多附件 pass）的入口 M-05 已通** —— 只需**只读捕获**地形 draw 数据，
不改任何原版渲染行为，ON/OFF 截图逐字节相同；
**方案 B（M-04 改原版主 pass 的附件语义）** 未开工，风险高一档。
⚠️ 官方 `FrameGraphSetupEvent` **给不了**该数据（已源码级证伪：事件在 `render` 第 249 行，
对象在第 271-275 行才创建）⇒ 必须注入捕获。

🔖 **两条可复用结论**：
1. **「换管线」与「绑块」必须分成两个注入点** —— 驱动层 STRICT_VALIDATION **按布局逐条**要求
   `setUniform`，而 `pipeline()` 拿不到 RenderPass。
2. **注入点的调用频次必须实测** —— `pipeline()` 在**建网格**时被调约 1300 次/秒；
   首版按 600 次节流的埋点让单趟日志多了 221 行，**日志 I/O 自己拖慢了热路径**
   （节流改 25 万次后降到 1 行）。

- **可关闭键**：`mixin.wireTerrain` / `mixin.bindTerrainParams`（FML 热加载生效、免重启）。
- **未覆盖**：BSL 共存、B1 帧时间、动态画面下的视觉等价、非 lavapipe 硬件。

### 10.8 ✅ GAP-003 多附件**原语**已验通（2026-10-03）

> 证据：`evidence/h02-mrt-primitive.md`。**证明了能力，没证明地形接入。**

| 项 | 状态 |
|---|---|
| 3 附件 pass（`RenderPassDescriptor` + 3 × `withColorAttachment`） | ✅ 可用 |
| 3 目标管线（`withColorTargetStates(0, 2, …)`，本项目第一条多附件管线） | ✅ 可用，`registered=17 compiled=17` |
| 片元 3 路输出（`layout(location=0/1/2) out`） | ✅ 可用，0 validation error |
| 「三槽拿到可区分内容」 | ✅ 逐槽 R 指纹，中心区 meanR **8.61 / 89.33 / 170.04**（理论 0 / 85.0 / 170.0） |
| 越界槽位 | ✅ **显式抛错**（实测 ERROR 原文 `mrt view slot 3 out of range 0..2`），不静默夹取 |
| 关闭后画面 | ✅ 与上一轮控制组**逐字节相同**（零影响） |
| 🔴 地形接入 / 包的自研 `gbuffers_*` 片元 / `colortex1` 格式 / 真机 | ⛔ 未做 |

🔖 **本轮最要紧的设计**：「三个附件都被写了」**不能靠截图证明**（三张可能都是同一渐变）。
做法 = 逐槽写**不同 R 指纹**（0 / ⅓ / ⅔）+ 每槽清屏色同指纹，
使「没被写」（露清屏色）与「写了」（渐变+指纹）在图上必然不同。

🔖 **本轮把 M-04 的难点具体化了**（原本只知「要给地形加附件」）：
原版主 pass 里混着 `executeSolid` / `executeClassicTransparency` / `executeOit` /
`executeOutline` / `executeSeeThrough` / `executeAlwaysOnTop` **六类 draw**，
⇒ **不能只加附件**，得先在 A/B 两个方案间取舍（表见 `04-SPEC.md` §5.0.4）。
**该取舍属「换一条路」级别，建议由用户裁决。**

- **可关闭键**：`mrt.enabled`（默认**关** ⇒ 常规帧零开销）+ `mrt.viewSlot`。
- **测试**：639 单测全绿（新增 7 例 `MrtPlanTest`）。
- **红线**：`§6` + `07-CONSTRAINTS` —— T5 bridge 例外、L11/X18 sodium 零命中、
  X9 不猜值、A8 先登记 GAP 再实现。
- **文档单一出处**：G 线数字 → `17-NATIVE.md` §7.7；闸门流程 → §5；预算 → §2.2；
  并行线与判据 → `18-PARALLEL.md`；证据索引 → `evidence/README.md`。

### 10.9 ✅ M-05 只读捕获可行 —— 方案 A 的两个前提都成立（2026-10-03）

> 证据：`evidence/h03-terrain-draw-capture.md`。**只验前提，没画地形。**

| 前提 | 状态 |
|---|---|
| 地形 draw 数据**捕获得到**（非 null） | ✅ 命中 `prepareChunkRendersIndirect` |
| **时序成立**（捕获早于帧图 pass 体执行） | ✅ AfterLevel（帧图执行之后）可见 `captures=1/2/3` |
| **不改任何渲染行为** | ✅ ON/OFF 截图**逐字节相同**；0 validation error |
| 🔴 地形画进多附件 pass | ⛔ **未做** —— 捕获的引用目前**无消费者** |

🔖 **官方事件给不了，已源码级证伪**：`fireFrameGraphSetup` 在 `LevelRenderer#render`
第 249 行，而 `prepareChunkRenders*` 在第 271-275 行才创建 ⇒ 事件触发时对象尚不存在。
但 pass 体在第 286 行 `frame.execute()` 才执行 ⇒ **只读捕获引用**时序天然成立。

🔖 **两个重载都必须注入**（`prepareChunkRenders` 与 `prepareChunkRendersIndirect` 二选一，
由设备能力 + 关卡设置决定）：本机实测命中 **indirect** 分支 ⇒
只注入非 indirect 分支的话，本机**永远捕获不到**，且这个失效**是静默的**。

🔖 **下一轮的第一个卡点（尚未核实）**：`renderGroup` 需要 `sampler` 与 `blockAtlas`
（原版在 `LevelRenderer` 第 442-447 行自建 sampler、第 531 行取 atlas）——
我方 pass 需自己准备这两个，或复用原版已建的。

- **可关闭键**：`mixin.captureTerrainDraws`（默认开；关闭后捕获停止、引用为空、多附件 pass 静默不开）。
- **测试**：641 单测全绿（新增 2 例，锁「两个重载都注入」+「只读：无 cancellable/setReturnValue」）。
