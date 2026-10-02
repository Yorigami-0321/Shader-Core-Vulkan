# 06 · 版本迁移预案（26.3 → 后续版本）

> 配套：`05-VERSION.md`（版本权威）、`04-SPEC.md`（组件清单）
> **本文只在需要升级版本时打开。** 平时不要读，避免过度设计。

---

## 1. 设计前提

**不做前瞻兼容，只做可迁移性。** 见 `05-VERSION.md` §4。

本文的作用不是"预测未来 API"，而是：
1. 把变化点**隔离**到少数文件，升级时改动集中
2. 准备好**排查顺序**，升级时按图索骥
3. 积累**迁移日志**，每次升级后回填

---

## 2. 变化点隔离设计（现在就落实）

### 2.1 `bridge` 包 —— 原版渲染 API 的唯一入口

```
bridge/
  RenderApi.java          // RenderPipeline 构建、BindGroupLayout、VertexFormat
  DeviceApi.java          // GpuDevice / CommandEncoder 获取
  FrameApi.java           // LevelRenderer / FrameGraphBuilder 插入
  TextureApi.java         // GpuTexture / GpuTextureView / RenderTarget
  MixinTargets.java       // 所有 mixin 目标的类名常量（便于集中改；当前 mixin 数 = 0）
```

**规矩**：
- **现状**：主线不走 mixin——帧注入用 NeoForge 官方 `RenderLevelStageEvent.AfterLevel`（P4.3 起，原因见 05-VERSION 帧注入点行）
  （`render/FullscreenPassHook`）；mixin **有条件开闸**（`07-CONSTRAINTS` M1，2026-10-02 成文），
  当前 `MixinTargets.MIXIN_CONFIG_COUNT = 0`，`mixin/` 包未建立。
  **开闸后唯一允许的注入点** = `net.minecraft.client.renderer.chunk.ChunkSectionsToRender#renderLayers`（private），
  且受 M1 五项编码约束 + X22–X26 约束。升级排查时**只改 `bridge/MixinTargets` 的常量**，
  注入方法体本身不含业务逻辑（X25）
- `pack/` / `glsl/` / `config/` / `screen/` 等业务包 **一律不得** `import com.mojang.renderpearl.*`
- 只有 `bridge/` 允许 import 原版渲染类型（若将来启用 `mixin/`，同样只允许转发）
- **`accel/`（加速层）例外说明**：它的接口只用纯 Java 类型，实现里若需触碰原版类型，
  同样必须经 `bridge/`。FFI（Panama）调用原生库不涉及原版渲染类型，因此可以直接做。
- 升级时用这条 grep 核对就够了：
  ```bash
  grep -rl "com\.mojang\.\(renderpearl\|blaze3d\)" src/main/java | sort
  ```
- **若含原生库**：升级时还要重建全部平台产物（`17-NATIVE.md` §6.3，回归项 R11）

### 2.2 mixin 只转发，不写业务

> **历史路线已弃用，当前不使用 mixin。** 主线帧注入走 NeoForge 官方
> `RenderLevelStageEvent.AfterLevel`（`render/FullscreenPassHook`），`neoforge.mods.toml` 的
> `[[mixins]]` 保持注释，`MixinTargets.MIXIN_CONFIG_COUNT = 0`。

**若将来启用 mixin**，规矩不变——mixin 只转发、不写业务（目标签名一变全废）：

```java
// ✅ 对：只转发
@Inject(method = "addMainPass", at = @At("HEAD"))
private void hook(FrameGraphBuilder builder, /* ... */ CallbackInfo ci) {
    FrameApi.insertOurPasses(builder);   // 业务在 bridge/FrameApi
}

// ❌ 错：业务逻辑写在 mixin 里，目标签名一变全废
```

> 启用时另见 `07-CONSTRAINTS.md` T1：`mixins.json` 的 `compatibilityLevel` 必须 `JAVA_25`。

### 2.3 版本常量单一数据源

- `gradle.properties`：`minecraft_version` / `minecraft_version_range` / `neo_version`（唯一数据源）
- 运行时版本判断：当前不需要，也未建 `Versions.java`；若将来需要，常量同样只放一处、**不要散落**

---

## 3. 26.3 已知的易变点清单

升级时**优先复查**这 4 类（按 26.3 的实际变动推断，风险从高到低）：

| # | 易变点 | 26.3 的事实 | 复查方法 |
|---|---|---|---|
| V1 | **渲染类型所在包** | 26.3 从 `blaze3d.*` 搬到 `renderpearl.*`（不完整搬迁） | `unzip -l` 游戏 jar，看目录结构是否又变了 |
| V2 | **后端 SPI 签名** | `BackendRenderPipeline$CreateInfo`、`SpvModule$Reflection` 等 | `javap -p` 对比新旧签名 |
| V3 | **`LevelRenderer` 渲染方法签名** | 本方案帧图插入点依赖 `render(...)` / `addMainPass(...)` | `javap -p` 看参数列表 |
| V4 | **`RenderPipeline.Builder` 链式 API** | `.withVertexShader` / `.withBindGroupLayout` / `.withShaderDefine` | 编译报错会直接指出 |

**快速对比新旧 API 的手段**（本工作区已验证可用）：

```bash
# 提取某个类的全部方法签名
JAVAP="/c/Program Files/Java/jdk-25.0.4.1/bin/javap.exe"
"$JAVAP" -p <Class>.class

# 从 jar 提取某命名空间的全部类型（见 mc-mod-reuse-audit skill 的脚本）
```

---

## 4. 升级操作步骤（照做）

```
1. 备份当前能跑的分支（git tag: v<ver>-working）
2. 改 gradle.properties 的版本号
3. ./gradlew compileJava  → 收集全部符号缺失错误
4. 只改 bridge/ 与 mixin/，逐条消错
5. 复查 §3 的 V1–V4 四类易变点
6. 【4.5 步】逐行复查 docs/13-GAP-REGISTRY.md：
     官方补上了吗？→ 补上了就打开开关做 A/B，一致则删掉自己的实现、改调原版、
                      状态改 🔁，并回填「官方更新复查记录」
                   → 没补上但 API 变了，同步改造补充实现
                   → 补充所依赖的原版能力被移除 → 走 §7 止损
7. 跑 §5 回归清单
8. 回填 §6 迁移日志

**若含原生库，额外一步**：重新构建全部平台的原生产物（`17-NATIVE.md` §6.3）。
版本升级时原生库的 ABI / 依赖可能变化，必须重建并重新验证 Java 保底路径仍可用（N1）。
```

**第 6 步不可跳过**（策略见 `12-GAP-STRATEGY.md` §5）：
不看登记表，自行补充的实现会永久沉积，最后没人分得清哪些是必要的、哪些是历史包袱。

**注意**：不要用 IDE 的批量 "replace package" 盲改 —— 26.3 那次搬迁是**不完整**的
（`RenderTarget` / `TextureTarget` / `RenderSystem` 没搬），盲改会改坏。

---

## 5. 升级回归清单

每次升级后必须全过：

| # | 检查项 | 通过标准 |
|---|---|---|
| R1 | `compatibilityLevel` 仍是 `JAVA_25` | 日志出现 `Compatibility level set to JAVA_25` |
| R2 | mixin 全部生效 | 每个注入点都有日志；**不是"没报错"就算过** |
| R3 | 自定义全屏 pass 可见 | 屏幕上出现自定义图案（Phase 0 验收项） |
| R4 | 后处理链可用 | 能开关、能传参、画面实时变化 |
| R5 | 有一个真实 OF 包能加载 | 不崩、不黑屏、有明显效果 |
| R6 | 顶点格式字段名一致 | 着色器 `attribute` 声明与 `VertexFormat` 字段**字面一致** |
| R7 | 无静默降级 | 失败必须显式报错，不允许"什么都不做" |
| R8 | jar 内容正确 | class 数 > 0、jar 不含 `net/minecraft` / `com/mojang` / 第三方模组类、`mods.toml` 依赖表名 == modId |
| R9 | 特性缺口已复查 | `13-GAP-REGISTRY.md` 的复查记录已回填（见 §4 第 6 步） |
| R10 | 性能仍在预算内 | 不开包 ≤ 原版 +2%；开包 ≤ Iris+OF 110%（`17-NATIVE.md` §2） |
| R11 | 原生库（若有）在新版本上重构建通过 | 四平台产物齐全；Java 保底路径可独立跑通（`17-NATIVE.md` §6.3） |

---

## 5.1 相关文档

| 文档 | 关系 |
|---|---|
| `05-VERSION.md` | 版本权威 + §4.4 自行补充特性走同一套流程 |
| `12-GAP-STRATEGY.md` | 自行补充的判定与收敛要求 |
| `13-GAP-REGISTRY.md` | §4 第 6 步的复查对象 |
| `17-NATIVE.md` | 性能预算；若含原生库，升级时要重建并重验 Java 保底路径（R11） |
| `08-TESTING.md` | 日常回归清单 |

---

## 6. 迁移日志（每次升级后回填）

| 版本 | 日期 | 改动文件 | 遇到的坑 | 耗时 |
|---|---|---|---|---|
| 26.3 | 2026-09-29 | —（基线） | 基线建立；工程已换成官方 MDK（NeoForge 26.3.0.23-beta） | — |

> 回填格式：一行一个版本，坑要写"现象 + 根因 + 修法"，便于下次查阅。

---

## 7. 升级失败的止损

若某次升级发现 **后端 SPI 被移除或大改**（V2 失效），说明本方案的地基变了：

1. 立即停止升级，回滚到上一个可用版本
2. 重新评估：原版是否仍提供等价插口？名字变了吗？
3. 若彻底没有了 → 触发架构级重评（此时才考虑"自己写后端"，
   **不要在没有插口的情况下硬推进**；🔴 也不得转向任何第三方渲染模组路线，见 `07-CONSTRAINTS.md` L11）

**判断标准**：Phase 0 的"自定义全屏 pass 可见"是所有后续工作的前提。
这一步在新版本上不成立，就不要继续。
