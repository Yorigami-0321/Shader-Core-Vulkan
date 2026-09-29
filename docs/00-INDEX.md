# 00 · 文档索引

> 工程：`vkdisp`（Vulkan Shader Dispatcher）· 许可证：**MIT**
> 定位：基于 Minecraft **原版 Vulkan 渲染后端**的、兼容 **OptiFine / Iris 格式**着色器包的引擎。
> 最后整理：2026-09-29

---

## 0. 一句话

**改代码 → 构建 → 跑 `runClient` 看真实产物 → 按错误修 → 循环到任务完成。**
详细步骤见 `01-DEV-LOOP.md`。

另外两条贯穿全程的纪律：**先找参考再动手**（`17-NATIVE.md` §1）、**先测出来再优化**（`17-NATIVE.md` §2–§3）。
（`17-NATIVE.md` 里的 C++/Rust 部分是**可选项、可行性未验证**，当前阶段一律纯 Java。）

---

## 1. 按顺序读这些

```
00-INDEX.md           ← 本文
01-DEV-LOOP.md        ← 怎么干活（开发测试流程，动手前必读）
02-OVERVIEW.md        ← 是什么、要自研哪四件事、性能目标
03-DIRECTION.md       ← 为什么可行、参考模组能借鉴什么、许可证边界
04-SPEC.md            ← 组件清单、OF uniform 表、构建配置
05-VERSION.md         ← 版本权威（支持 26.3 及之后，主线 26.3）
07-CONSTRAINTS.md     ← 红线，不可违反
08-TESTING.md         ← 每个阶段的验收细则（含性能硬指标）与回归清单
```

再往下：

```
06-MIGRATION.md       ← bridge 包隔离 + 版本升级流程（第一天就要落实隔离）
17-NATIVE.md          ← 性能预算 / 参考先行（C++/Rust 是可选项，可行性未验证）
12-GAP-STRATEGY.md    ← 原版 Vulkan 没有的特性怎么办
13-GAP-REGISTRY.md    ← 上面那份的登记表（先登记再实现）
15-ITERATION.md       ← 迭代维护协议
16-READING.md         ← 按读者类型的阅读路径
18-PARALLEL.md        ← 并行开发路线（哪些能同时干、不许碰什么）
AGENT_CONTEXT.md      ← 跨会话记忆（决策、原版 API 清单、待决策项）
```

---

## 2. 全部文档

| 文件 | 内容 |
|---|---|
| `00-INDEX.md` | 本文 |
| `01-DEV-LOOP.md` | **开发测试流程**：构建 → runClient → 看真实输出 → 修错 → 循环 |
| `02-OVERVIEW.md` | 定位、问题、自研范围、性能目标、成功标准 |
| `03-DIRECTION.md` | 新方向总纲 + 证据清单 + 参考模组许可证边界 |
| `04-SPEC.md` | 组件清单（含参考/热度标注）、OF 内建 uniform、顶点格式扩展、构建配置 |
| `05-VERSION.md` | **版本权威**：支持范围、锁定表、兼容策略 |
| `06-MIGRATION.md` | bridge 隔离、升级步骤、回归 R1–R9 |
| `07-CONSTRAINTS.md` | 许可证 §〇（MIT + P1/P2/P3）+ L1–L12 + 技术约束 T1–T16 + 红线 X1–X21 |
| `08-TESTING.md` | 阶段验收 + **性能硬指标** + 回归清单 |
| `12-GAP-STRATEGY.md` | 原版不支持时的自行补充规则 |
| `13-GAP-REGISTRY.md` | 缺口登记表 |
| `15-ITERATION.md` | 三层防乱协议（A1–A17） |
| `16-READING.md` | 按读者类型的阅读路径 |
| `17-NATIVE.md` | **性能预算 + 参考先行**；C++/Rust 仅作未验证的可选项 |
| `18-PARALLEL.md` | **并行开发路线**：可并行的 6 条线、契约冻结闸门、边界与限制 |
| `AGENT_CONTEXT.md` | 跨会话记忆 |

---

## 3. 编号说明

`02`–`08`、`12`–`17` 是本项目**自己的编号**，不代表文件序号连续。
`09`–`11`、`14` 刻意未使用（见 `16-READING.md` §4）。

---

## 4. 提醒

**旧方向（改写第三方渲染器）的文档已于 2026-09-29 全部删除**，不留归档副本。
里面的路线 A/B/C、「假 Sodium」、`Shader-Core-Vulkan-docs/` 路径等**全部已作废**，
不要再去找，也不要尝试恢复。**只有 `docs/` 下的 `00`–`17` + `AGENT_CONTEXT.md` 是有效文档。**
