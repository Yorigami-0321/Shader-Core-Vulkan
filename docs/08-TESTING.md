# 08 · 测试与验收标准

> 按新方向（原版 Vulkan 后端 + OF/Iris 格式引擎）重写。
> **测试不过不算完成。** 阶段划分与 `04-SPEC.md` §6 一致。
> 面向 agent 的可执行流程见 `01-DEV-LOOP.md`。

---

## 1. 验收总表

| 阶段 | 验收项 | 通过标准 | 证据 |
|---|---|---|---|
| **P0** 骨架 | 构建 | `gradlew build` 退出码 0，jar 含 `.class` | 命令输出 |
| **P0** 骨架 | 可启动 | 启动到主菜单，无 `Mixin apply failed` | 游戏日志 |
| **P0** 骨架 | 后端判定 | 日志显示当前为 Vulkan 后端 | 日志 |
| **P0** 骨架 | **首个可见产物** | 屏幕上出现自定义全屏 pass 画出的图案 | 截图 |
| **P1** 管线 | 参数可见变化 | 改 uniform 数值，画面实时变化 | 录屏/连续截图 |
| **P1** 管线 | 管线计数 | 注册数 == 编译成功数（不得静默少） | 诊断日志 |
| **P2** 解析 | 包识别 | 选中一个真实 OF 包后能被列出并选中 | 截图 |
| **P2** 解析 | `#include` | 含 `#include` 的 program 能编译通过 | 日志 |
| **P2** 解析 | composite 生效 | 选择开关后画面有对应变化 | 对比截图 |
| **P3** 完整链 | 影子 | `shadow` pass 输出非空、方向正确 | 调试视图 |
| **P3** 完整链 | gbuffers | 地形/实体走自定义目标而非原版目标 | 调试视图 |
| **P3** 完整链 | deferred | 延迟链各步输入输出正确 | 调试视图 |
| **P4** 兼容 | 主流包 | BSL / Complementary / Sildur's 主要效果可用 | **§10 兼容矩阵**（与 Iris 对比截图） |
| **P4** 兼容 | 🔴 **渲染正确**（非仅编译） | 逐 pass 调试视图 + 对比截图 + 用户目检三者交叉 | §10；⚠️ 编译通过 ≠ 渲染正确 |
| **P4** 兼容 | 切包无残留 | 切包/切 none 无顶点格式错乱、无闪烁 | 4 张对比截图 |
| **P4** 兼容 | 选项 GUI | pack 声明的选项能渲染并能改 | 截图 |
| **P4** 兼容 | 转译矩阵修复 | BSL 全 190 stage 编译 `ok=190 failed=0` | 日志 + `evidence/p4xx-141-matrix.md` |
| **P5** 性能 | 帧时间 | **B1** 不开包 ≤ 原版 +2% | §8 |
| **P5** 性能 | 冷路径 | **B3** 解析+转译 ≤ 1s；**B4** 切包 ≤ 2s（四段计时齐全） | §8 |
| **P6** 原生 | 🔴 **G 系列闸门** | 等价性测试全绿 + 性能对照 + 按 20% 阈值裁决 | §8.2 + `evidence/` |
| **P6** 原生 | FFI 安全 | 故意触发一次 Rust panic，JVM **不 abort** | §8.3（T17/N5） |

---

## 2. P0：骨架与首个可见产物

```bash
cd D:/Code/Minecraft/Shader-Core-Vulkan
./gradlew build
echo "exit=$?"

JAR=$(ls build/libs/*.jar | head -1)
unzip -l "$JAR" | grep -c '\.class'
unzip -l "$JAR"
```

**通过标准**
- `gradlew build` 退出码 0
- jar 内 `.class` 数量 > 0
- jar 内**不含** `net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/`
- `./gradlew runClient` 能进主菜单，日志无 `Mixin apply failed`

**关键断言（不得靠肉眼猜）**

```java
// 启动时打这条，用户可直接核对
LOGGER.info("vkdisp: backend={}, device={}", backendKind(), deviceInfo().name());
// 期望：backend=Vulkan（绝不能是 OpenGL）
// 值域注意：原版 backendName() 原值是 "Vulkan" / "OpenGL"（首字母大写，不是全大写），代码比较必须写 "Vulkan"
```

> ⚠️ **静默失败第一定律**：mixin 没生效时游戏**不会报错**，只会「什么都没发生」。
> 每个注入点必须自己打一条日志证明它被调用了。详见 `01-DEV-LOOP.md` §4。

---

## 3. P1：管线与 uniform

**测试步骤**
1. 起客户端，进入世界
2. 打开诊断日志
3. 修改全屏 pass 的一个 uniform 值

**通过标准**
```
[✓] 画面随参数实时变化（不是只在启动时生效）
[✓] 关闭 pass 后画面回到原状
[✓] 日志中的「注册管线数」== 「编译成功数」
[✓] 无 ERROR / 无 Vulkan validation error
```

**管线计数断言（必须实现）**

```java
if (registered != compiled) {
    LOGGER.error("vkdisp: pipeline count mismatch: registered={}, compiled={}", registered, compiled);
}
// 允许编译失败，但不允许「失败得无声无息」
```

---

## 4. P2：格式解析阶段

**测试 pack**

| Pack | 用于验证什么 |
|---|---|
| 自制的**最小 pack**（1 个 composite） | 解析器本身 |
| BSL | `shaders.properties` 选项、`#include`、多 stage |
| Sildur's Vibrant Extreme | `shaders/` 根目录 + 各维度子目录（`world0/`、`world-1/`、`world1/`、`world_end/`） |

**通过标准**
- 包能被扫描到（`.zip` 与文件夹两种形态都要能识别）
- `shaders.properties` 的选项能被枚举出来
- `#include` 按 OF 语义解析（相对路径、可嵌套）
- `const int X = 0; // [0 1 2]` 这类选项常量能被识别
- 缺文件（比如没有 `shadow.fsh`）时**显式降级**并打日志，不静默跳过

---

## 5. P3：完整绘制链

**检查点**

| 环节 | 检查 |
|---|---|
| shadow | 光空间列表非空；阴影贴图内容合理（不是全黑/全白） |
| gbuffers | 地形、实体、水、天空分别走到对的 program |
| deferred | 每步的输入纹理是上一步的输出 |
| composite | 链顺序正确，最后一步写入主目标 |
| final | 画面回到屏幕，无重复后处理 |

**顶点 stride 自检（关键，必须实现）**

```java
// 不匹配会画成「拉伸的彩色尖刺」且不报错
int meshStride = meshFormat.getVertexSize();
int bindStride = pipeline.getVertexFormatBinding(0).getVertexSize();
if (meshStride != bindStride) {
    LOGGER.error("vkdisp: stride mismatch mesh={} binding={}, refusing to draw",
        meshStride, bindStride);
    return;
}
```

---

## 6. P4：切包回归（必跑）

```
步骤：
1. 加载 pack A，进世界，截图       → S1
2. 切到 pack B，截图               → S2
3. 切到 none，截图                 → S3
4. 再切回 pack A，截图             → S4

通过标准：S1 == S4；S2/S3 无「彩色尖刺」、无残留
```

> 这是着色器加载器的经典坑：管线缓存按 program 键，顶点格式变了旧管线会残留。

**执行方式（P4.2 落地，2026-10-01）**：切包由 `vkdisp-client.toml` 的 `shaderPack` 驱动，
**外部改值保存即自动生效**（FML FileWatcher → `ModConfigEvent.Reloading` → `VkDispConfigHotReload`
→ 资源重载），无需输入注入、无需重启 —— 全四步在**同一 runClient 会话**内完成：
`""` = 自动选包（S1/S4）、指定包名 = 切 B（S2）、`"none"` = 强制内置 passthrough（S3）。
每步等日志 `client resources loaded (initial=false)` 再截图。
首跑取证 `evidence/p417-pack-switch.md`：S1↔S4 静态地面带 identity=+1.0000、S2/S3 尖刺 0.000%。

**选项 GUI 取证（P4.3 落地，2026-10-01）**：选项屏幕由 `vkdisp-client.toml` 的
`packOptionsScreen` 驱动（同一 FileWatcher 热加载链，但走边沿分割 →
`PackOptionsDrive.run`，**不触发资源重载**；`done` 保存/改写自身触发重载）。
语法 `""` / `open` / `set:NAME=VALUE` / `page:N` / `done`；`set`/`page`/`done` 要求
屏幕已开，否则 WARN 拒绝（T11）。**帧注入点 = `RenderLevelStageEvent.AfterLevel`**
（`render/FullscreenPassHook`；P4.3 从 `RenderFrameEvent.Post` 迁移 —— Post 在 GUI
合成之后触发会整屏覆盖 GUI，实测否决），菜单态不再绘制全屏 pass。
取证 `evidence/p418-options-gui.md`：单会话八截图（BSL 284 项分页渲染 + set 回执 +
fixture 改值像素比 0.8910 = 理论 0.8889 + 未开屏 WARN 拒绝正向证据）。

**141 阶段矩阵修复取证（2026-10-02）**：转译层第 8 段 `LegacyBuiltinInjector`
（GLSL 1.20 旧内建 token 级替换 + 「用而未声明」属性名声明注入）闭环了此前
`stages=190 ok=49 failed=141` 的 141 阶段矩阵失败（gl_MultiTexCoord\* / gl_TextureMatrix /
Position undeclared + gl_ 前缀 reserved 两波）；首错遮蔽揭示的 Distant Horizons 兼容新类
（`dhMaterialId` / `DH_BLOCK_*` / `DH_OVERDRAW`，本引擎不集成 DH）同轮修掉并登记
`13-GAP-REGISTRY.md` **GAP-002**。取证 `evidence/p4xx-141-matrix.md`：单跑
`pack compile done: stages=190 ok=190 failed=0` + `pipeline count check: registered=9,
compiled=9 (aligned)`，零 `undeclared identifier` / `are reserved` / `Missing uniform` /
`解析失败` / `fullscreen pass failed`，首错遮蔽闭合（DH 类已知、非未知类收轮）。

**P4.1 BSL 视觉基线（2026-10-02）**：141 矩阵修复后确认 BSL 实际渲染连贯、无静默破坏。
⚠️ 本环境 Agent 不支持查看图片（PNG Read 被内容过滤拒绝），视觉判读改 **luma 量化分带**
（p418 同源口径）+ 日志诊断 + **用户目检** 三方交叉。A/B：`shaderPack=""`（BSL）vs
`shaderPack="none"`（passthrough，config 热加载切）截图 luma —— BSL content 20.9 / 天空 6.9 /
地面 26.6；passthrough content 34.1 / 天空 28.3 / 地面 38.3（BSL 偏暗、天空 0.24×，因相机朝天且
世界时钟冻结黎明）；渲染连贯（无全黑/全白/彩色尖刺）、零 vkdisp ERROR、用户目检确认 BSL 观感正常
（更暗=晨昏风格化非缺陷）。取证 `evidence/p4x1-bsl-visual.md`（含两截图 sha256 + 分带表）。

---

## 7. 边界条件清单

| 边界 | 检查 |
|---|---|
| 空世界（超平坦、无方块） | 不崩、不画错 |
| 极高/极低 Y | 不崩 |
| 超大渲染距离（32+） | 不 OOM、不崩 |
| 极小渲染距离（2） | 正常 |
| 资源重载（F3+T） | 管线重建、不崩 |
| 退出世界再进 | 资源正确释放、不泄漏 |
| 窗口 resize / 全屏切换 | 不崩 |
| OpenGL 后端下运行 | 明确拒绝并给出可读提示（不硬崩） |

---

## 8. 性能验收（**硬指标**，带数字）

> 授权来源：用户要求「**高性能、稳定、兼容完整**」。
> **预算定义与测量规范见 `17-NATIVE.md` §2.2 与 §7。本节只列验收线。**
>
> ⚠️ 本版变更：旧版只定「不开包 ≤ +2%」一条帧时间指标，**冷路径无预算**，
> 导致「切包 1861ms」这类数据无处对标。现补 B3/B4/B7，并新增 §8.2 Rust 对比验收。

| 指标 | 验收线 | 测量 | 适用阶段 | 现状 |
|---|---|---|---|---|
| **B1 不开包**（模组加载但未启用） | 帧时间相对纯原版 **≤ +2%** | F3 屏，同场景同视角，3 次取中位数 | **P0 起必过** | 待测 |
| **B2 开包**（中等包 BSL） | 帧时间 **≤ 同机 Iris+OF 的 110%** | 同场景对比 | P2 起 | 待测 |
| **B3 冷路径**（全部 program 解析 + 转译） | **≤ 1 秒**（中等包） | 分段计时日志 | **P0 起必过** | 1861ms ⚠️ 超线 |
| **B4 切包端到端** | **≤ 2 秒**（中等包）；缓存命中 **≤ 0.5s** | 计时日志 | **P0 起必过** | 1861ms ⚠️ 接近 |
| **B5 首帧编译** | 无 **> 200ms** 单帧卡顿 | 帧时间直方图 | P3 起 | 待测 |
| **B6 常驻内存** | 1 小时无持续增长；增量 **≤ 200MB** | 任务管理器 / JFR | P1 起 | 待测 |
| **B7 换维度 / F3+T** | 不崩、不闪烁（零空窗） | 截图序列 + 日志 | P3 起 | 待做（P4.6） |

**冷路径分段计时（必须打，B3/B4 的取证依据）**：

```java
long t0 = System.nanoTime();
var pack = scanner.scan(root);                       long t1 = System.nanoTime();
var options = propertiesParser.parse(pack);        long t2 = System.nanoTime();
var preprocessed = preprocessor.process(sources);   long t3 = System.nanoTime();
var translated = translator.translate(preprocessed);long t4 = System.nanoTime();
LOGGER.info("vkdisp: stage timing: scan={}ms parse={}ms preprocess={}ms translate={}ms total={}ms",
    ms(t1-t0), ms(t2-t1), ms(t3-t2), ms(t4-t3), ms(t4-t0));
```

> **只报总数不算数** —— 总数超线时无法定位到是哪一段，必须分四段打点。

**必留证据**：`17-NATIVE.md` §7.3 的基线数据表要回填实测值。
**没有实测数据的性能声明一律不认。**

### 8.1 优化纪律

```
① 先跑通（默认纯 Java）→ ② 测量 → ③ 超线才优化 → ④ 只优化超线的那一个环节 → ⑤ 再测
```

- **达标即停**，不许继续优化（超出授权范围，X12）
- 优化前先确认该环节是 🔥 热路径（影响 B1/B2）**或** ❄️ 冷路径（影响 B3/B4）
- 🔴 **不许为性能砍掉 pack 特性**（X27，三支柱里兼容优先级最高）
- 上原生必须先走完 `17-NATIVE.md` §5 的 **G 系列闸门**（T16）

> ⚠️ **本项目不追求**性能优于任何第三方实现。目标是**功能正确 + 不拖累原版 + 切包不折磨人**。
> 这三件事不冲突：先正确，再达标，不多做。

### 8.2 🔴 Rust vs Java 对比验收（G 系列，2026-10-02 新增）

> 触发条件：本轮实测范围 = `glsl/` 预处理与转译 + `pack/` 解析。
> 完整流程见 `17-NATIVE.md` §5.1，裁决阈值见 §5.2，报告模板见 §5.3。

| 步骤 | 验收项 | 通过标准 |
|---|---|---|
| **G0** | Java 基线已取 | 分四段计时 + 合计，样本 ≥5，取中位数与 p95，落盘 `evidence/` |
| **G1** | **等价性测试** | 同一输入下 Rust 与 Java 产物**逐 token 等价**；不一致 ⇒ 直接判不采用 |
| **G2** | FFM 打通 | Java → Rust → Java 单向跑通；**实测单次边界开销**（空函数调用）已记录 |
| **G3** | 性能对照 | 同机同包同时段；报 Rust 中位数 / Java 中位数 / 比值 / p95 |
| **G4** | 裁决 | 按 `17-NATIVE.md` §5.2 阈值判定，结论写回文档 + `evidence/` |

**裁决阈值（先定，避免事后找理由）**：

| 情形 | 裁决 |
|---|---|
| Rust 快 **≥ 20%** 且等价性全绿 | ✅ **采用** |
| 快 5%–20% | 🟡 **暂缓** → 换更大包复测一轮；仍在此区间则**不采用** |
| 差异 < 5% / Rust 更慢 / 等价性不通过 | ❌ **不采用**，登记结论后收工 |

**验收硬性要求**：

- [ ] 等价性测试**先于**性能对比完成（顺序不可颠倒：不等价的性能数字无意义）
- [ ] 实测了 FFM 单次边界开销（用于验证「按批粒度」是否真的够粗）
- [ ] 输入固定并登记 sha256（包名 + 版本 + program 数）
- [ ] 报告落 `evidence/`，含机器/JDK/采样数
- [ ] 裁决为「采用」时才建 `accel/` 包与配构建链；「不采用」时**不得留下半成品**
- [ ] 若采用：Java 保底路径仍完整可用（N1）、有 A/B 开关（N4）、`extern "C"` 有 `catch_unwind` 且未设 `panic = "abort"`（T17）

> ⚠️ **禁止拿公开基准直接当结论**（X32）。已知「naga 比 glslang 快约 30×」，
> 但该基准不含 `#include` 与 `GL_*` 扩展语义，对 OF 方言包会**编译失败**。
> 公开数据只能作潜力证据。

### 8.3 稳定性验收（2026-10-02 新增，三支柱之二）

| 项 | 验收线 |
|---|---|
| 崩溃 / Vulkan validation error | 运行 1 小时 = **0 条** |
| 静默降级 | **0 处**（任何降级必须 WARN 或 ERROR，X11） |
| mixin 注入点可归因 | 出问题时能通过关闭**单个**注入点定位（M1⑤ / X29） |
| FFI panic 隔离 | 原生侧任意 `panic!` **不得**导致 JVM abort（N5 专项：故意触发一次 panic，须降级为错误码） |
| 切包闪烁 | P4.6 完成后切包零空窗（B7） |

---

---

## 9. 回归清单（每次改动后跑；脚本见 `01-DEV-LOOP.md` §3）

```
[ ] gradlew build 退出码 0，jar 含 class
[ ] jar 不含 net/caffeinemc、net/minecraft、dev/vitrail、com/mojang
[ ] mixins.json 的 compatibilityLevel 是 JAVA_25，且 [[mixins]] 已取消注释
[ ] 启动到主菜单，无 Mixin apply failed
[ ] 进入世界
[ ] 无 pack：世界正常渲染
[ ] 无 pack：帧时间相对原版 ≤ +2%（B1）
[ ] 加载 pack：composite 生效
[ ] 冷路径分段计时四段齐全（B3：合计 ≤ 1s）
[ ] 切 pack → none → 再切回：无彩色尖刺、无闪烁（B4/B7）
[ ] 管线注册数 == 编译成功数
[ ] 日志无 ERROR、无 validation error
[ ] 🔴 每个 mixin 注入点已登记在 04-SPEC §5.0，且可单独关闭
[ ] 若含原生库：Java 保底路径可独立跑通（N1）
[ ] 若含原生库：extern "C" 有 catch_unwind、未设 panic="abort"（T17）
[ ] 若含原生库：A/B 开关可切换且两端都能跑（N4）
```

### 9.1 🔴 WSL 端每轮必报的运行时数据（C-07，2026-10-04 立）

本机沙箱无法测帧时间与 GC，以下数据**由 WSL 端 `runClient` 每轮提交**（与本轮迭代一并落 `evidence/` 文本摘要，二进制不入库）：

| 数据 | 取法 | 判据 |
|---|---|---|
| **B1 帧时间** | F3 屏，同场景同视角，3 次取中位数；相对纯原版 | ≤ +2%（P0 起必过） |
| **GC 频率** | F3 屏「GC」字段，或 `-Xlog:gc` 日志统计每分钟次数 | 热路径每分钟 ≤ 2 次（pillar ② 稳定性） |
| **1h 内存增量** | 启动后稳定值记基线，跑 1h 再读 | ≤ 200MB（§8.3） |

> 缺这三项的轮次视为**未完成验收**（哪怕单测全绿）。`CHANGE_LOG` 每轮条目须含这三项或显式标注「本轮未动热路径，沿用上轮」。


---

## 10. 兼容矩阵验收（2026-10-02 新增，支柱①的落地形式）

> 「兼容完整」不是形容词，是一张**必须逐格填的表**。空格子就是未完成的兼容性。

| pack | program 总数 | 编译通过 | **渲染正确** | deferred 链 | composite 链 | 选项 GUI | 判定 |
|---|---|---|---|---|---|---|---|
| BSL 8.x | 190 | ✅ 190/190 | ⬜ | ⬜ | ⬜ | ✅ 284 项 | ⏳ |
| Complementary | — | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⏳ |
| Sildur's Vibrant | — | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⏳ |

**「渲染正确」的判定方式（不能只靠 luma）**：

| 方式 | 能证明什么 | 不能证明什么 |
|---|---|---|
| 逐 pass 调试视图（`colortex0..7` 可视化） | 附件分槽是否正确、链序是否正确 | 最终观感 |
| 与 Iris 同场景对比截图 | 效果是否接近 | —— |
| luma 分带量化 | 「画出来了」 | ❌ **画对了没有**（已被 P4.1 误判教训证实） |
| 用户目检 | 观感 | —— |

⇒ **「渲染正确」列的填充需要前两行 + 用户目检三者交叉**，单靠任何一项都不算数。

> ⚠️ **2026-10-02 教训（P4.1 误判）**：此前把「BSL 编译 190/190 + 画面连贯 + luma 有差异」
> 判为「BSL 视觉基线达成」，后来被复核推翻 —— 182 个 program 只接线 3 个、18 个 sampler
> 有 17 个绑到同一张图，这些**都不影响 `ok=190`**，但画面是错的。
> **编译通过 ≠ 渲染正确；luma 有差异 ≠ 渲染正确。**