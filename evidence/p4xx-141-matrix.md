# P4.x 141 阶段矩阵修复轮（2026-10-02，runClient 实测闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志本体在 gitignored 路径（`run/logs/`），本文件只存可复核的事实；
> 复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- **环境**：WSL2，lavapipe（llvmpipe / Mesa 软件 Vulkan，ICD 由 `tools/vulkan-local/env.sh`
  经 `VK_ICD_FILENAMES` 注入）；JDK 25（~/jdk/jdk-25.0.4.1+1）；NeoForge 26.3.0.23-beta。
- **取证窗口**：启动 → 进「New World」→ 渲染到帧 600+ 后 `VkDispPackScan` 在
  `ClientResourceLoadFinishedEvent` 扫 `run/shaderpacks/` 并对每个 stage 调用
  `ShaderCompileApi.compileStage`（驱动级 GLSL→SPIR-V），打印
  `vkdisp: pack compile done: stages=.. ok=.. failed=..`（L2536）。
- **判读口径**：达标线 = `stages=190 ok=190 failed=0`；同时核对
  `pipeline count … registered=9, compiled=9`、`Missing uniform=0`、`解析失败=0`、
  `fullscreen pass failed=0`。⚠️ 首错遮蔽：还有失败就会有新类冒出来把旧的藏住 ——
  同轮修掉或显式登记（T11），不许带着未知类收轮。

## 机制（本论实现，先文档后代码，字节码/驱动取证）

**① 141 阶段矩阵修复（`glsl/translate/LegacyBuiltinInjector`，转译第 8 段）**：

- 首错（2026-10-01 实测 `stages=190 ok=49 failed=141`）两类根因：
  - **(a) undeclared identifier**：`gl_MultiTexCoord0` ×33 / `gl_TextureMatrix` ×20 /
    `Position` ×2（FtransformExpander 冻结字面名展开后用而未声明）；
  - **(b) identifiers starting with "gl_" are reserved** ×91：补声明后驱动仍拒 gl_ 前缀
    （GLSL 公开词法：gl_ 前缀保留给语言内建，用户声明与使用皆非法）—— 注入 gl_ 名不是出路。
- 对应修复（双职责）：**替换 + 注入**。
  - **替换**（等行改写、行数不变、使用驱动）：属性旧名 → 语义等价合法名
    （`gl_MultiTexCoord0→UV0` / `gl_MultiTexCoord1→UV2` / `gl_Color→Color` /
    `gl_Normal→Normal` / `gl_Vertex→vec4(Position,1.0)` 或包内已声明名）；
    矩阵旧名 → 宿主按 `04-SPEC §3.2` 上传的 gbuffer 矩阵
    （`gl_ProjectionMatrix→gbufferProjection` / `gl_ModelViewMatrix→gbufferModelView` /
    `gl_ModelViewProjectionMatrix→(gbufferProjection*gbufferModelView)` /
    `gl_NormalMatrix→(transpose(inverse(mat3(gbufferModelView))))` /
    `gl_TextureMatrix[n]→mat4(1.0)`，下标随 token 消费；裸名无下标保留交驱动显式报错 T11）。
  - **注入**（只补「用而未声明」的属性名：`in vec4 UV0; / in vec4 UV2; / in vec4 Color; /
    in vec3 Normal; / in vec3 Position;`，插在 headerEnd 之后；location 由下游
    `IoLocationAdapter` 补写；属性类仅 VERTEX 阶段，矩阵类不注入声明）。
  - 声明行保护（表达式型替换遇该旧名显式声明行不动 token）；注释/字符串/预处理体内不算使用；
    第二遍输入是第一遍输出 → 替换门/注入门全关，逐字节不变、零诊断（幂等）。
- 此修复在 2026-10-01 22:15/22:22 提交（21461ea / 399e6a3），**此前从未运行时验证**；
  本论是它第一次 runClient 取证。

**② 首错遮蔽第二轮：Distant Horizons 兼容桩（GAP-002）**：

- 141 修复落地后失败从 141 → 6，揭示**新类**（首错遮蔽）：BSL 的 `dh_terrain` / `dh_water`
  引用一组 Distant Horizons 注入的块类型 / 材质宏：`dhMaterialId` + `DH_BLOCK_WATER` /
  `DH_BLOCK_LAVA` / `DH_BLOCK_LEAVES` / `DH_BLOCK_ILLUMINATED` / `DH_OVERDRAW`，
  全包 grep 无任何声明（DH 注入时提供）。本引擎不集成 DH（07-CONSTRAINTS D3/D16）。
- 同轮修掉（不把未知类带进收轮）：在 `LegacyBuiltinInjector#INJECTIONS` 注册上述符号为
  普通全局 stub（`int dhMaterialId;` + 五个 `const int DH_BLOCK_* / DH_OVERDRAW`），
  使用驱动门（用而未声明才注入）、任何阶段都注入、已声明则跳过、幂等；
  并产生一条 **INFO 显式诊断**（T11 不静默，文本含 `GAP-002` 与 `DH_`）。
- 代码头部【参考调研】注释块 + 诊断已就位；`docs/13-GAP-REGISTRY.md` GAP-002 已登记覆盖
  全部 6 个符号。

## 阶段对照（单会话，`run/logs/runclient-dhfix2.log` 行号可复核）

| 时刻 | 动作 → 日志锚（行号） | 说明 |
|---|---|---|
| 16:45:23 | `composite source diagnostic: WARN: BSL_v10.1.8: 选项 [INVALID_DEFAULT_VALUE] option 'PARAMETER' default '1.00' is invalid for STRING; derived '0.00'`（L1157） | BSL 包选项元数据既有怪癖，WARN 不阻编译，不影响 `failed=0`（登记，非本轮引入） |
| 16:45:23 | `composite source diagnostic: INFO: 自行补充（GAP-002）：声明 Distant Horizons 兼容桩 …`（L1198，×5 条 composite/final） | DH stub 注入显式可见（T11 不静默） |
| 16:45:28 | `vkdisp: pack compile done: stages=190 ok=190 failed=0`（L2536） | **达标线达成** |
| 16:45:31 | `vkdisp: pipeline count check: registered=9, compiled=9 (aligned)`（L2581） | 管线计数对齐（不静默少） |

## 判定

| 判据 | 结果 |
|---|---|
| `stages=190 ok=190 failed=0` | ✅ L2536 |
| `pipeline count … registered=9, compiled=9 (aligned)` | ✅ L2581 |
| `Missing uniform=0` / `解析失败=0` / `fullscreen pass failed=0` | ✅ 日志零命中（grep 空集） |
| 首错遮蔽闭合（无未知失败类收轮） | ✅ 141 → 6 → 0；DH 符号类已登记 GAP-002，非未知类 |
| DH stub 显式可见（T11 不静默） | ✅ L1198 INFO 含 GAP-002 / DH_ |
| 零残留 gl_ token（reserved 不再触发） | ✅ 日志零 `are reserved` / `undeclared identifier` |
| 单元回归 | ✅ `LegacyBuiltinInjectorTest` 19 用例全绿（新增 dhMaterialId + DH_BLOCK_* 两组；裸声明正则扩 `const int` 双限定符形态）；全仓 `./gradlew test` 576 用例 0 失败 |

**未覆盖（登记）**：

1. **真实输入路径**（鼠标直接操作着色器包 GUI 选项）—— 输入注入被禁，本轮仅配置热加载 + 进程内驱动；
2. **BSL 包选项 `PARAMETER` 默认值非法（STRING 型 default '1.00'）** —— 本条 WARN（L1157）是 BSL 包自身
   元数据怪癖，派生为 '0.00'，不影响编译（`failed=0`），非本轮引入，留作已知非阻塞性 WARN；
3. **DH 几何真实渲染** —— DH 不集成，stub 仅求「不装 DH 时 dh_* 着色器可编译」，DH 形状本就不渲染；
4. 本轮未做切包回归（P4.2 四步截图）—— 仅验证 141 矩阵修复 + DH stub 的编译闭环；
   切包残留回归见 `evidence/p417-pack-switch.md`，本轮未改动顶点格式链路，无回归预期。
