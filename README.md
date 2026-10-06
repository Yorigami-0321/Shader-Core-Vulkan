# Vulkan Shader Dispatcher (`vkdisp`)

一个 NeoForge **纯客户端**模组：基于 Minecraft 原版自带的 Vulkan 渲染后端，实现一个能加载
**OptiFine / Iris 格式**着色器包的引擎。

## 构建

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 起客户端
```

Windows 下注意：仓库级 `core.autocrlf` 必须是 `false`（见 `.gitattributes`），
否则 `gradlew` 会被检出成 CRLF，Git Bash 直接 bad interpreter。

---

## 文档

工程文档包在 `docs/`，先读 `docs/16-READING.md`（索引入口 `docs/00-INDEX.md`）。

Mapping Names：本工程使用 Mojang 官方映射，其授权条款见
<https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>。

NeoForge 文档：<https://docs.neoforged.net/>
