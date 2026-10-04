# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---

## 2026-10-04（三十六）— U0001f534 候选 3 与候选 5 **双双证伪** ⇒ 范围收敛到**一个算子**：`textureGrad`

> **verdict = 兼容 + 稳定轮（诊断/取证，重大收窄）。**
> 任务来源：`AGENT_CONTEXT` §10.17 ⑧（重跑实验 2，本轮判读终于可信）。

- **U0001f534→U0001f533 一句话**：本轮两次实验都**拿到了可信判读**（日志自报开关已开启），两次都**证伪**了目标候选；
  最后靠**逐行比对工作路径与坏路径**，锁定唯一一个「坏路径有、工作路径没有」的 `albedo` 算子。
- **① 实验 A（候选 3 `lmCoord`）⇒ 证伪**：日志自报 `lmCoord=满光照诊断开关=已开启`，
  画面仍纯黑。推导复核：若 `lmCoord=(1,1)` 则 `blockLighting ≈ 0.42 × 2.2² ≈ 2.0` ⇒ 画面本应**过曝发白**；
  它却仍恰好全黑 ⇒ `albedo` 在**进入 `GetLighting` 之前**就已经是 0。
- **② 实验 B（候选 5 视差分支）⇒ 证伪**：`dist = 1000` ⇒ `parallaxFade = 1.0` ⇒
  命中 `GetParallaxCoord` 早退 ⇒ 视差分支整体跳过。日志自报 `dist=视差跳过诊断开关=已开启`（同时 `lmCoord=…关` ⇒ 严格单变量）。
  画面仍纯黑。
- **③ U0001f7e1 候选 4 被结构性排除**：两条路径 `main()` 的**第一行逐字相同**
  （`vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);`），而默认路径用**同一表达式**算出了**可见**的暖色地形
  （`h08-B` 截图）⇒ `texture` 采样 / `color` / `texture_0` 本身都没问题。
- **④ U0001f50d 关键推进：范围缩到一个算子**。坏路径把 `albedo` 整个**重算了一遍**：
  `albedo = textureGrad(texture_0, newCoord, dcdx, dcdy) * vec4(color.rgb, 1.0);`，
  其中 `vec2 dcdx = dFdx(texCoord);`。实测 `textureGrad` 次数：**默认路径 0 次 / 高级材质路径 7 次**。
  ⇒ 这是目前**唯一一个「坏路径有、工作路径没有」的 `albedo` 算子**。
- **⑤ U0001f7e1 待查两个子项**（本轮预算用尽，未取证）：① 绑给 `texture_0` 的方块图集**mip 链是否可用**
  （`textureGrad` 用显式 LOD；只有 1 级 mip 而 LOD > 0，或各级 mip 未填充 ⇒ 采样结果可以是 0）；
  ② `dFdx(texCoord)` 在**反向 Z / MRT pass** 下是否退化为 0。
- **⑥ 可观测能力：探针 1 → 2**，且互不干扰（X45 的延续）：新增 `mrt.terrainParallaxSkipProbe`（只改 `dist`），
  与既有 `mrt.terrainFullLightProbe`（只改 `lmCoord`）各自自报状态 ⇒ 每趟都是严格单变量。
- **⑦ 测试**：`./gradlew build` BUILD SUCCESSFUL，**691** 单测全绿（生成器签名加第二个探针参数，
  `PackVertexAdapterGeneratorTest` 与 `PackTerrainSourceTest` 同步跟改）。
- **⑧ 顺带清理**：修掉前几轮 heredoc 转义造成的一批 `U0001f7e1` 这类**字面量 emoji 残留**（含测试源码里的 8 处）。
- **⛔ 仍未完成**：候选 6 **未验证**（GAP-008 仍开着，但范围已缩到一个算子）；GAP-010 的 12 条 ERROR 未修；
  地形仍只画进我方 pass（**不产出用户可见画面改进**，M-04 未做）；GAP-007 常量项 7 条；GAP-009 真材质集未实现。
---