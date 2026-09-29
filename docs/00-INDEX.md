# 00 · 文档索引

> 工程：`vkdisp`（Vulkan Shader Dispatcher）· 许可证：**MIT**
> 定位：基于 Minecraft **原版 Vulkan 渲染后端**的、兼容 **OptiFine / Iris 格式**着色器包的引擎。
> 最后整理：2026-09-29

---

## 0. 一句话

**改代码 → 构建 → 跑 `runClient` 看真实产物 → 按错误修 → 循环到任务完成。**
详细步骤见 `01-DEV-LOOP.md`。

另外两条贯穿全程的纪律：**先找参考再动手**（`17-NATIVE.md` §1）、**先测出来再优化**（`17-NATIVE.md` §2–§3）。

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
17-NATIVE.md          ← 性能预算 / 参考先行 / 原生加速决策树
12-GAP-STRATEGY.md    ← 原版 Vulkan 没有的特性怎么办
13-GAP-REGISTRY.md    ← 上面那份的登记表（先登记再实现）
15-ITERATION.md       ← 迭代维护协议
16-READING.md         ← 按读者类型的阅读路径
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
| `07-CONSTRAINTS.md` | 许可证 §〇（MIT + P1/P2/P3）+ L1–L10 + 技术约束 T1–T16 + 红线 X1–X16 |
| `08-TESTING.md` | 阶段验收 + **性能硬指标** + 回归清单 |
| `12-GAP-STRATEGY.md` | 原版不支持时的自行补充规则 |
| `13-GAP-REGISTRY.md` | 缺口登记表 |
| `15-ITERATION.md` | 三层防乱协议（A1–A14） |
| `16-READING.md` | 按读者类型的阅读路径 |
| `17-NATIVE.md` | **性能预算 + 参考先行 + 原生（C++/Rust）加速决策树** |
| `AGENT_CONTEXT.md` | 跨会话记忆 |
| `_archive/` | 旧方向文档（07 / 08 / 10 / 15 / 17） |
| `_archive-开发计划-v2-旧方向.md` | 旧主计划 |

---

## 3. 编号说明

`02`–`08`、`12`–`17` 是本项目**自己的编号**，不代表文件序号连续。
`09`–`11`、`14` 刻意未使用（见 `16-READING.md` §4）。

---

## 4. 提醒

**`_archive/` 里的东西只用于查历史。** 里面的路线 A/B/C、「假 Sodium」、
`Shader-Core-Vulkan-docs/` 路径、`26.3.0.16-beta` 版本号等，**全部已作废**。
