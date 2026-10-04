# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---

## 2026-10-04（三十四）— 🟡 GAP-008 切分开跑：中性材质贴图**证伪了候选 1/2**（2026-10-04）

> **verdict = 稳定 + 兼容轮（诊断/取证）。**
> 任务来源：`AGENT_CONTEXT` §10.15 ⑨ 第 1 条 —— **先定位，不猜绑定**。
> 取证方式：静态链路分析（逐行读 1483 行转译终稿）+ MCP 驱动真实客户端的单变量 A/B。

- **① 先把乘法链静态列全**（不猜）：对 `albedo` 的乘法只有三处候选 ——
  `ao*ao`、`1 - metalness*smoothness`、`sceneLighting *= skylightSqr`。
  🔖 默认配置路径里 **`GetMaterials` 出现 0 次**（整段在 `#if defined ADVANCED_MATERIALS` 里）
  ⇒ 解释了「为什么只有开高级材质才全黑」。
- **② 🔴 实验 1 已执行，假设被证伪**：把 `specular`/`normals` 从方块图集换成**中性材质贴图**
  （乘法单位元 `(0,0,0,255)` / `(128,128,255,255)`，语义=「没有材质覆盖、没有 AO、法线朝上」，
  是可解释的缺省而非编一个假的输入）。实测 **48.41% 的像素变了**（证明两个采样器确实被读到）
  **但画面仍全黑** ⇒ 它们**不是主因**。⚠️ 若当初直接「改个看起来对的绑定」宣布修好，就是一次**假绿**。
- **③ 🔴 顺带揪出并修掉一个每帧抛的回归（本轮自己造的）**：在 pass 打开期间懒建贴图上传 ⇒
  `IllegalStateException: Close the existing render pass before performing additional commands`
  ⇒ **每帧一次**（懒建在 pass 内永远失败 ⇒ 永远重建）。改为开 pass 之前 `ensureCreated()`，
  与既有 `MappableRingBuffer` map/close 纪律同源；复跑实测贴图建 1 次、pass 正常、**0 条 pass failed**。
- **④ 定位收敛到候选 3**：`skylightSqr = lightmap.y²`、`lightmap = clamp(lmCoord, 0, 1)`，
  而原版把**天光与块光打包进同一个 UV2**（`uv2.y` 恒 0）⇒ `lmCoord.y ≡ 0` ⇒ `sceneLighting ≡ 0`；
  OF 语义下 `lmCoord` 应当是 `(块光, 天光)` 两条独立通道 ⇒ **这是一处真实的映射错误**。
- **⑤ 🟡 但尚未坐实**：单变量开关 `mrt.terrainFullLightProbe`（打开时**只**把 `lmCoord` 改成
  `vec2(1.0)`，其余 14 条 varying 与 7 个采样器绑定**全部不动**）已实现并通过无头测试，
  **本轮客户端没进世界、实验未执行** —— `--quickPlaySingleplayer` 本轮未生效，4 分 26 秒后 FML
  **正常关闭退出**（不是崩溃；jstack 在上一趟已证实该状态下渲染线程空闲在 `limitDisplayFPS`）。
  ⚠️ 顺带记一笔：**quickPlay 有随机不生效的现象**，本项目观测到 6 次里约 2 次没进世界。
- **⑥ 新登记 GAP-009**：逐方块材质贴图集（OF 的 `specular`/`normals`）本引擎没有。
  缺省语义已落地（中性乘法单位元）并验证「两个采样器确实被读到」；**真材质集未实现**。
- **测试**：`./gradlew build` BUILD SUCCESSFUL（689 例全绿；本轮改了生成器签名，
  `PackVertexAdapterGeneratorTest` 5 例与 `PackTerrainSourceTest` 签名对账用例同步跟改）。
- **⛔ 仍未完成**：压零项尚未坐实（GAP-008 仍开着）；客户端 quickPlay 随机不生效未定位；
  地形仍只画进我方 pass（**不产出用户可见画面改进**，M-04 未做）；GAP-007 常量项从 3 条涨到 **7** 条。
---