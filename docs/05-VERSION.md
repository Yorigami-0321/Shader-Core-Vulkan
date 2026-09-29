# 05 · 版本基线（26.3 及之后）

> **本文是所有版本相关决定的唯一权威。** 与其他文档冲突时以本文为准。
> 确立日期：2026-09-29

---

## 1. 目标范围（明确）

| 项 | 值 |
|---|---|
| **支持范围** | Minecraft **26.3 及之后发布的新版本** |
| **当前主线** | **26.3**（一切开发、验证、验收以 26.3 为准） |
| **不支持** | 26.2 及之前的全部版本（**明确不支持，不为此做任何兼容设计**） |
| **未来版本** | 26.4 / 26.5 … 出现后按 §4 的迁移流程处理，不预先设计 |

**为什么是 26.3 起步**：本方向依赖原版自带的渲染后端抽象层
`com.mojang.renderpearl.backend.api.*`，该层在 26.3 才成形。26.2 及之前没有，
强行兼容会让整个架构倒退。

---

## 2. 版本锁定表（26.3 主线）

| 组件 | 版本 | 来源 |
|---|---|---|
| Minecraft | **26.3** | — |
| NeoForge | **26.3.0.23-beta** | 取自官方 MDK（`NeoForgeMDKs/MDK-26.3-ModDevGradle`，commit `eec248c`） |
| Java | **25** | 26.3 强制 |
| ModDevGradle | **2.0.147** | 官方 MDK 内声明 |
| Gradle | **9.2.1**（wrapper） | 官方 MDK 自带 |
| Mixin | 随 NeoForge | 不要 MixinGradle 插件 |
| MixinExtras | 随 NeoForge | `@WrapOperation` / `@Local` 等 |

**唯一数据源是仓库根的 `gradle.properties`**，本表只作镜像，冲突时以文件为准。

**禁止**：不要为了"顺便支持 26.2"而引入版本判断分支；不要用 `@Pseudo` 去兼容不存在的类。

---

## 3. 与本方向绑定的 26.3 事实

这些是 26.3 独有、且本方案直接依赖的：

| 事实 | 影响 |
|---|---|
| 原版已是 **Vulkan 后端**（`renderpearl.backend.vulkan.*`） | **不自己写 Vulkan 设备**，这是本方案成立的前提 |
| 原版提供 **后端 SPI**（`renderpearl.backend.api.*`） | 官方插口，不需要任何"替身"方案 |
| Blaze3D 类迁入 **Renderpearl**（`com.mojang.renderpearl.*`） | 引用/重映射必须用新包名 |
| 部分类**未迁**（`RenderTarget`、`TextureTarget`、`RenderSystem`… 仍在 blaze3d） | 重映射时注意例外，见 `06-MIGRATION.md` |
| Java **25**（字节码 major 69） | `mixins.json` 的 `compatibilityLevel` 必须 `JAVA_25` |
| 官方映射（dev 名 == runtime 名） | **不需要 refmap**；老工程的 `refmap` 字段要删 |

---

## 4. 向后兼容策略（26.4 及之后）

**总原则：不做前瞻性兼容设计，只做可迁移性设计。**

理由：Mojang 在 26.x 期间仍在持续重构渲染层（26.1 起去混淆、26.3 迁 Renderpearl），
提前猜 API 变化只会写出一堆用不上的抽象层。正确做法是**把变化点隔离**，让升级时改动集中在少数文件。

### 4.1 必须做的隔离（现在就做）

| 隔离层 | 做法 | 目的 |
|---|---|---|
| **渲染 API 访问** | 所有原版渲染 API 调用集中在一个 `bridge` 包，业务代码不直接 import `com.mojang.renderpearl.*` | 升级时只改 bridge |
| **mixin 目标** | mixin 集中登记在 `mixins.json`，每个 mixin 只做转发、不写业务逻辑 | 目标签名变化时改动最小 |
| **着色器格式解析** | 解析层完全独立，不依赖任何原版类型 | OF/Iris 格式本身不随 MC 变 |
| **版本常量** | 版本号只出现在 `gradle.properties` 与一处 `Versions.java` | 单一数据源 |

### 4.2 升级流程（26.4 出现时执行）

1. 改 `gradle.properties` 的 `minecraft_version` / `minecraft_version_range` / `neo_version`
2. 编译，收集全部**符号缺失**错误（不用猜，编译器会告诉你）
3. 按错误清单只改 `bridge` 包与 mixin 目标
4. **重点复查 4 类易变点**：
   - 原版渲染类型是否又搬了包（26.3 已搬过一次 blaze3d → renderpearl）
   - 原版后端 SPI 是否有签名变化（`BackendRenderPipeline$CreateInfo` 这类）
   - `LevelRenderer` 的渲染方法签名（本方案的帧图插入点依赖它）
   - `RenderPipeline.Builder` 的链式方法是否有增删
5. 跑 `06-MIGRATION.md` 里的回归清单
6. 迁移记录追加到 `06-MIGRATION.md` 的日志表

### 4.3 明确不做的事

- ❌ 不为未来版本预留 `if (version >= X)` 分支
- ❌ 不做多版本共存（一套 jar 支持 26.3 + 26.4）
- ❌ 不引入反射/字符串查找来绕开编译期检查（会破坏"静默失败可诊断"原则）

### 4.4 自行补充的特性也要走同一套升级流程

当 OF/Iris 语义必需、而原版 Vulkan 确实没有时，允许自行补充（策略见 `12-GAP-STRATEGY.md`）。
这类补充**不改变版本范围**，但必须在官方每次更新时一并复查：

> 升级流程第 4.5 步：**逐行复查 `13-GAP-REGISTRY.md`** —— 官方补上了就删掉自己的，API 变了就跟着改。

细节见 `12-GAP-STRATEGY.md` §5。

---

## 5. 对文档包的影响

以下文档的版本相关表述以本文为准：

| 文档 | 需注意 |
|---|---|
| `03-DIRECTION.md` | §5 分阶段计划均为 26.3 主线 |
| `04-SPEC.md` | §5 构建配置的版本号以本文 §2 为准 |
| `06-MIGRATION.md` | 升级流程与回归清单 |
| `12-GAP-STRATEGY.md` | 自行补充特性需走本文 §4.4 的复查 |
| `AGENT_CONTEXT.md` | D7 决策已更新为"支持 26.3 及之后，主线 26.3" |
| `_archive/*` | **已归档**（旧方向），忽略其全部版本表述 |
