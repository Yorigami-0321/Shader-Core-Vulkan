# 02 · 项目概览

> 2026-09-29 重写。目标主线已明确为：**基于原版 Vulkan 的、兼容 OptiFine/Iris 格式着色器包的着色器模组。**

---

## 1. 一句话

**`vkdisp`（Vulkan Shader Dispatcher）** 是一个 NeoForge **纯客户端**模组：跑在 Minecraft 原版自带的
Vulkan 渲染后端上，加载**存量 OptiFine / Iris 格式**的着色器包（BSL、Complementary、Sildur's…）。

> **独立实现，与 Sodium、Iris、OptiFine、Vitrail 均无关联。**
> 不依赖任何第三方前置，也不尝试替代任何第三方前置。

---

## 2. 问题（为什么需要它）

| 事实 | 后果 |
|---|---|
| 过去十年社区的着色器包几乎全是 **OptiFine 格式**，跑在 **OpenGL** 上 | 格式本身与 Vulkan 无关，可以复用 |
| Minecraft 26.3 把渲染后端换成了 **Vulkan** | 存量 pack 全部失效 |
| Iris / Sodium 是 **OpenGL** 时代的加载器 | 在 26.3 上无从下手 |
| 已有的原版 Vulkan 着色器模组（VulkanMod / Sulkan 等）**都不支持 OF/Iris 格式** | **没有现成轮子可借**——这是本项目要做的那件事 |

---

## 3. 关键前提：原版已经给了后端插口

26.3 的原版渲染层拆出一个独立的库 **Renderpearl**（`com.mojang.renderpearl.*`），
其中包含**后端抽象层**：

```
com.mojang.renderpearl.backend.api.*
    GpuDeviceBackend / CommandEncoderBackend
    BackendRenderPipeline (+ $CreateInfo)
    SpvModule (+ $Reflection / $Descriptor / $InterfaceVariable)
```

Vulkan 的具体实现就在 `com.mojang.renderpearl.backend.vulkan.*`。

**结论：本项目不写 Vulkan 设备、不写命令缓冲、不写 render pass、不写 SPIR-V 编译器。**
第三方可以通过官方的 `RenderPipeline.builder()` 注册自己的管线，这条路已被 Sulkan 实测证明。

---

## 4. 要自研的四件事（真正的活）

```
vkdisp
  ├── ① OF/Iris 格式解析     shaders.properties / gbuffers_* / composite* / block.properties …
  ├── ② pass 编排            shadow → gbuffers → deferred → composite → final 的帧图顺序
  ├── ③ GLSL 转译            OF 方言 → 原版编译通道能吃的形式（#include / const 选项 / 内建 uniform）
  └── ④ 选项 GUI             把 pack 自己声明的选项渲染成可调界面
                ↓
        原版 RenderPipeline / BindGroupLayout / GpuDevice（官方 API）
                ↓
        原版 Vulkan 后端（不碰）
```

**四件事的路径热度**（决定能不能用 C++/Rust，详见 `17-NATIVE.md` §3.2）：

| 模块 | 热度 | 实现语言 |
|---|---|---|
| ① 格式解析 | ❄️ 冷（加载时一次） | **纯 Java** |
| ② pass 编排 | 🔥 热（每帧）+ ❄️ 冷（规划时） | 纯 Java（原版 API 调用为主） |
| ③ GLSL 转译 | ❄️ 冷（加载时一次，结果进缓存） | **纯 Java** |
| ④ 选项 GUI | ❄️ 冷（打开时一次） | **纯 Java** |
| 顶点/UBO/管线键等**热路径工具** | 🔥 热 | **纯 Java**（原生仅为「未验证的可选项」） |

> **别被「预处理器 / 解析器 / 编译器」的复杂度骗了** —— 这三样本项目全是**冷路径**，
> 用 Rust 重写帧率收益为零。真正可能值得上原生的只有热路径工具，且必须先测出瓶颈。
>
> **注意上表最后一行的措辞**：即使是最热的路径，当前也定为**纯 Java**。
> C++/Rust 只是「写进文档备用的选项」，可行性未验证（`17-NATIVE.md` 状态声明）。

---

## 4.1 每一部分都要先找参考

**任何模块开工前先调研，写下「参考了什么 / 为什么不直接用 / 我们的差异点 / 许可证核对」**
（`17-NATIVE.md` §1）。默认参考清单：

| 模块 | 首选参考 |
|---|---|
| GLSL 预处理器 | **IrisShaders/glsl-preprocessor**（⚠️ GPL+例外 → 只读思路） |
| GLSL 转译 / AST | **IrisShaders/glsl-transformer**（⚠️ 自定义传染 → 只读思路） |
| OF 格式语义 | **Iris** 的 `shaderpack/parsing/`（格式规范是事实性信息） |
| 帧图 / 注入点 | Sulkan（GPL）/ Vitrail（LGPL）→ 只读思路 |
| 管线挂载模式 | VulkanMod（LGPL）→ 只读思路 |

> **Iris 是全世界唯一成熟的 OF 格式实现，它的解析器就是格式的事实标准。**

---

## 5. 版本基线

| 项 | 值 |
|---|---|
| 支持范围 | Minecraft **26.3 及之后**发布的版本 |
| 当前主线 | **26.3** |
| 不支持 | **26.2 及之前**（那代没有 `renderpearl.backend.api`） |
| 锁定 | MC 26.3 / NeoForge 26.3.0.23-beta / Java 25 / MDG 2.0.147 |

> **权威文档：`05-VERSION.md`。** 任何版本相关表述与它冲突时以它为准。

---

## 6. 边界

**做**
- OF/Iris 格式包的解析、编排、转译、GUI。
- 所有 GPU 操作走原版 `com.mojang.renderpearl.*`。
- 原版 Vulkan 暂不支持、但 OF/Iris 语义必需的特性，**可以自行补充**（见 `12-GAP-STRATEGY.md`）。
- **兼顾性能**：以 `17-NATIVE.md` §2 的预算表为准（首要指标：**不装包时帧时间相对原版 ≤ +2%**）。
- **C++ / Rust 作为「可选项」写进文档**（`17-NATIVE.md`）：可行性**尚未验证**，当前阶段**不实现**。
  将来若某个热路径实测超预算，才按 §5 六问 + §6.2 第 0 关重新评估。

**不做**
- ❌ 不写 Vulkan 设备 / 命令缓冲 / render pass（官方有）
- ❌ 不写 SPIR-V 编译器（走原版编译通道）
- ❌ 不依赖 Sodium、不替代 Sodium、不做「假 Sodium」
- ❌ 不碰 Vitrail（两者可共存，互不干扰）
- ❌ 不抄 GPL-3.0（Sulkan）与 ARR（Beryl）的任何代码
- ❌ **现在不做任何原生（C++/Rust）实现**、不建 `accel/` 包、不配 CMake/cargo（`17-NATIVE.md` 状态声明）
- ❌ **不为「性能」而提前上原生**：冷路径（解析/预处理/转译）一律纯 Java（`17-NATIVE.md` §3.2）
- ❌ 不因为「原生更快」就跳过参考调研（每一部分都要先找参考，`17-NATIVE.md` §1）

---

## 7. 两条前置纪律（每一部分都适用）

| 纪律 | 含义 | 落地 |
|---|---|---|
| **参考先行** | 任何模块开工前先调研社区成熟实现，写下「参考了什么/为什么不直接用」 | `17-NATIVE.md` §1；实现文件头部【参考调研】注释块 |
| **先测后优** | 先跑通、先测量，超预算才优化；优化只做热路径 | `17-NATIVE.md` §2–§3 |

> **顺序不可颠倒：参考 → 测量 → 优化。**
> 跳过前两步直接写 C++/Rust，是本项目最容易犯、代价最大的错。

---

## 8. 成功标准（分级）

| 级别 | 标准 | 验证 |
|---|---|---|
| **最小可用** | 空模组能在屏幕上画出由自定义 `RenderPipeline` 产出的图案 | 截图 |
| **链路通** | 能识别并加载一个真实 pack，`#include` 能解，composite 有效果 | 截图 + 日志 |
| **目标达意** | BSL / Complementary 加载后地形、天空、水、阴影经 pack 着色 | 与 Iris 同场景对比 |
| **完整** | 主流 pack 主要效果可用 + 选项 GUI 可用 | 多包回归表 |
| **性能达标** | 不开包 ≤ +2%；开中等包 ≤ Iris+OF 的 110% | `17-NATIVE.md` §7.3 基线表 |

---

## 9. 一页流程图

```
Minecraft 26.3
  └── Renderpearl（原版渲染层）
        ├── renderpearl.api.*           ← 前端 API（管线的公开门面）
        ├── renderpearl.backend.api.*   ← ★ 后端 SPI（官方插口）
        └── renderpearl.backend.vulkan.*← 原版 Vulkan 实现（不碰）

vkdisp（本项目）
  ├── pack/     解析 OF/Iris 格式        ❄️ 冷，纯 Java
  ├── glsl/     方言转译                 ❄️ 冷，纯 Java
  ├── pipeline/ 用原版 Builder 注册管线
  ├── render/   编排帧图（🔥 热）
  ├── accel/    加速层门面（⏸️ 计划预留，当前**不建**）→ 现阶段只有 Java 路径
  └── bridge/   ← 所有原版渲染 API 的调用都收在这里（升级时只改这一处）
```
