# AGENT_CONTEXT — vkdisp

> 跨会话项目记忆。每次生成/更新文档包后同步。
> **2026-09-29 方向已彻底变更：以 §0 为准，旧方向全废。**
> **2026-10-02 目标升级为三支柱（兼容/稳定/高性能）+ mixin 松绑 + 原生可实测：以 §0.1 为准。**
> **最新交接快照：§9（2026-10-01，141 阶段矩阵修复轮，任务停止前存档）。**

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
    `evidence/g0-java-coldpath.md`。BSL 182 阶段的分段基线已建立，并落盘 364 条 golden sha256
    供 G1 做输出一致性测试。**G1 的前置条件已满足**。
  - 🔴 **G1 开跑前必读**：三趟实测跨次漂移 ≈±9%，**与 20% 裁决阈值同量级** ⇒ G3 必须两侧
    交替测量、样本 ≥9、报 p95，禁止用单次最好值比值（`17-NATIVE.md` §7.3 末的红线）。
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

### 9.5 环境与红线速查（详见持久记忆 + §6）

- 一切 java/gradle 前缀 `export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"`（隧道 hook.so 否则 EINVAL）；
- 禁止前台 `sleep`、禁止 `pkill -f GradleDaemon`（游戏进程 `kill <pid>` 可以）、禁止 `--rerun-tasks`、
  解压用 Python zipfile；hash 证据在游戏退出后取；
- **输入注入已封**（XTEST/xdotool/ydotool/wtype 及任何替代 = 同样失败结论）：证据只走配置热加载 +
  进程内组件调用（p416/p417/p418 同源法）；窗口 id 每次启动都变，枚举用 `x11_capture.py --list-windows`；
- 红线不变：IrisShaders/glsl-transformer **按禁止处理零代码并入**（MIT only）、X9 不猜测
  （javap/字节码算验证）、T5 业务包不 import `com.mojang.renderpearl.*`/blaze3d（bridge/ 例外）、
  L11/X18 `sodium|caffeinemc` 零命中、`docs/` 归本机 env-1。
