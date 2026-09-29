# Vulkan Shader Dispatcher (`vkdisp`)

一个 NeoForge **纯客户端**模组：基于 Minecraft 原版自带的 Vulkan 渲染后端，实现一个能加载
**OptiFine / Iris 格式**着色器包的引擎。

> **独立实现，与 Sodium、Iris、OptiFine、Vitrail 均无关联**，也不依赖、不替代其中任何一个。

---

## 版本基线

| 项 | 值 |
|---|---|
| 支持范围 | Minecraft **26.3 及之后**发布的版本 |
| 当前主线 | **26.3** |
| 不支持 | 26.2 及之前（那代没有原版后端 SPI `com.mojang.renderpearl.backend.api`） |
| Java | 25 |
| NeoForge | 26.3.0.23-beta |
| ModDevGradle | 2.0.147 |

权威文档：`docs/22-版本基线.md`。

---

## 为什么不自己写 Vulkan 驱动

Minecraft 26.3 原版自带渲染后端抽象层 `com.mojang.renderpearl.backend.api.*`
（`GpuDeviceBackend` / `CommandEncoderBackend` / `BackendRenderPipeline` / `SpvModule`），
Vulkan 实现就在 `com.mojang.renderpearl.backend.vulkan.*`。

所以本模组**不写设备、不写命令缓冲、不写 render pass**，只做原版没做的那部分：

1. OF / Iris 格式包解析（真正的技术空白，现成轮子不存在）
2. pass 编排（shadow / gbuffers / deferred / composite）
3. GLSL 转译（把 OF 方言喂给原版编译通道）
4. 选项 GUI

---

## 构建

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 起客户端
```

Windows 下注意：仓库级 `core.autocrlf` 必须是 `false`（见 `.gitattributes`），
否则 `gradlew` 会被检出成 CRLF，Git Bash 直接 bad interpreter。

---

## 文档

工程文档包在 `docs/`，先读 `docs/16-阅读优先级指引.md`。

Mapping Names：本工程使用 Mojang 官方映射，其授权条款见
<https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>。

NeoForge 文档：<https://docs.neoforged.net/>
