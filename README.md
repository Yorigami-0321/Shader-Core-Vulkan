# Vulkan Shader Dispatcher (`vkdisp`)

一个 NeoForge **纯客户端**模组：基于 Minecraft 原版自带的 Vulkan 渲染后端，实现一个能加载
**OptiFine / Iris 格式**着色器包的引擎。

## 许可证

**LGPL-3.0-or-later**（`LICENSE` 是 FSF 原文，`GPL-3.0.txt` 是它按引用并入的 GPL-3.0 条款）。
2026-10-17 由 MIT 改写，动因与逐上游的可搬性判定见 [`NOTICE.md`](NOTICE.md)，工程侧的硬约束见
`docs/07-CONSTRAINTS.md` §〇 / §一。

"对光影包特性的处理"这一层按该裁决**直接移植 Iris / Vitrail**（两者 LGPL-3.0），不再自研；
Sodium（PolyForm Shield）、OptiFine / Beryl（ARR）、glsl-transformer（AGPL-3.0，只搬规则不引库）
仍然一行不搬。

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
