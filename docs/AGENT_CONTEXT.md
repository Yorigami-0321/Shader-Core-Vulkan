# AGENT_CONTEXT — vkdisp

> 跨会话项目记忆。每次生成/更新文档包后同步。
> **2026-09-29 方向已彻底变更：以 §0 为准，旧方向全废。**

---

## 0. 当前方向（2026-09-29 用户指令，最高优先级）

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
| 锁定：MC / NeoForge / Java / MDG | 26.3 / **26.3.0.23-beta** / 25 / 2.0.147 |
| 工程形态 | **官方 MDK `NeoForgeMDKs/MDK-26.3-ModDevGradle`**（commit `eec248c`），已铺入 `D:/Code/Minecraft/Shader-Core-Vulkan` |
| 未来版本 | 按 `06-MIGRATION.md` §4 流程升级，**不做前瞻兼容设计** |

- **不依赖 Sodium**，**不替代 Sodium**，**完全不碰 Vitrail**（两者可共存）
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
—— 这就是旧计划苦苦寻找而未得的"官方后端插口"。旧计划锁死在 Sodium 上，没往原版看。

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
| **D12** | **文档包收进工程内 `docs/`；旧方向文档全部归档到 `docs/_archive/`** | **2026-09-29** | 用户明确指令（清理历史遗留） |
| **D13** | **新增 `01-DEV-LOOP.md` 作为给 agent 派活的标准流程** | **2026-09-29** | 用户明确指令 |
| **D14** | **新增 `17-NATIVE.md`：兼顾性能 + 参考先行；C++/Rust 仅作可选项写入文档** | **2026-09-29** | **用户明确指令**（「模组要兼顾性能，部分需求可改成用 c++ 或 rust 实现。每一部分的实现最好都先去找参考。」） |
| **D15** | **C++/Rust 可行性未验证 → 当前阶段纯 Java；不建 `accel/` 包、不配原生工具链** | **2026-09-29** | 用户追加指令：「**C++/Rust 作为可选项先写进文档就行。实际是否可行等后续**」（`17-NATIVE.md` 状态声明） |

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
- 归档（旧方向，只看历史）：`docs/_archive/` 与 `docs/_archive-开发计划-v2-旧方向.md`

### 工程骨架（已落地）

```
Shader-Core-Vulkan/
├── LICENSE                  MIT 全文（2026-09-29 定）
├── gradle.properties        mod_id=vkdisp, mod_license=MIT, minecraft_version=26.3,
│                            minecraft_version_range=[26.3,), neo_version=26.3.0.23-beta
├── build.gradle             官方 MDK（ModDevGradle 2.0.147, toolchain Java 25）
├── settings.gradle          foojay-resolver 1.0.0
├── gradle/wrapper/          Gradle 9.2.1
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
| `07-CONSTRAINTS.md` | 许可证（§〇 MIT + P1/P2/P3）+ 技术约束 T1–T16 + 红线 X1–X16 |
| `16-READING.md` | 阅读顺序（按新编号） |
| `15-ITERATION.md` | 三层防乱协议 |
| `_archive/*` | 📦 已归档：07 / 08 / 10 / 15 / 17（全部旧方向） |
| `_archive-开发计划-v2-旧方向.md` | 📦 旧方向完整开发计划 v2（只看历史） |

**编号说明**：`09`–`11`、`14` 刻意未使用（视觉规范 / 运营 / 埋点 / 发布清单不适用）。

---

## 5. 待用户决策（阻塞项）

| # | 决策点 | 状态 |
|---|---|---|
| Q1 | ~~**本项目许可证？** LGPL-3.0 / MIT~~ | ✅ **已定（2026-09-29）：MIT**。`LICENSE` 全文 + `gradle.properties` 的 `mod_license=MIT`；由此产生 P1/P2/P3 三条硬约束，**VulkanMod(LGPL) 也只能读不能抄**（`07-CONSTRAINTS.md` §〇） |
| Q2 | ~~是否仍锁 MC 26.3？~~ | ✅ **已定**：支持 **26.3 及之后**，主线 **26.3** |
| Q3 | **是否保留 Sodium 可选增强？** | ⏳ 待定。建议留到 P4，先不碰 |
| Q4 | ~~旧 `Shader-Core-Vulkan/` 目录怎么办？~~ | ✅ **已决并执行**：清空旧内容，换成官方 NeoForge 26.3 MDK |
| Q5 | **P0 是否开工？** | ⏳ **待定**（用户当前只要计划 + 骨架，未授权写功能代码） |
| Q6 | **是否有场景必须用原生（C++/Rust）？** | ✅ **已定：现在不做。** C++/Rust 只是「写进文档的可选项」，**实际是否可行等后续**；真要做须先过 `17-NATIVE.md` §6.2 第 0 关可行性验证（D15） |
| Q7 | **C++/Rust 到底可不可行？** | ⏳ **未验证**。需先写最小 FFI demo 打通四平台（`17-NATIVE.md` §6.2 第 0 关），在此之前不投入 |

---

## 6. 硬约束速查

- `mixins.json` 的 `compatibilityLevel` **必须 `JAVA_25`**（`JAVA_21` 在 Java 25 下静默跳过 mixin）
- `mods.toml` 的 `[[dependencies.<X>]]` 表名必须等于 `modId`
- modId / 包名 **绝不要用** `sodium` / `vitrail` / `iris` / `optifine`
- 顶点格式字段名必须与着色器 `attribute` 声明**字面一致**
- 仓库内需 `.gitattributes`（`* text=auto eol=lf`）+ 仓库级 `core.autocrlf=false`，
  并手工 `git update-index --chmod=+x gradlew`
- **clone 时若系统级 `core.autocrlf=true`，工作区文件会实际落盘成 CRLF** —— 光声明 `.gitattributes`
  不够，必须 `sed -i 's/\r$//'` 重写一遍（本工程已做）
- 通用重映射：`com.mojang.blaze3d.*` → `com.mojang.renderpearl.*`（简单名配对），
  例外 `RenderTarget`/`TextureTarget` 仍在 blaze3d.pipeline。完整表见 `Vitrail-Shaders/versions/26.3.remap`
- **每一部分开工前先找参考**（`17-NATIVE.md` §1，T13）：实现文件头部必须有【参考调研】注释块
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
