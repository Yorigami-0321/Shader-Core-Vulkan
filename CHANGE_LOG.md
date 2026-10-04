# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---

## 2026-10-04（三十二）— ✅ 包自己的 `gbuffers_terrain` 真的跑在地形 draw 上 + 附件数跟随包输出数

> **verdict = 接线轮 + MCP 客户端取证（跑了真实游戏）。**
> 任务来源：`AGENT_CONTEXT` §10.4 第 6 条的 ①②③ —— h07 证明 SPIR-V 早就编好了，缺的是「接上去」。

- **本次改了什么**：
  1. **新真源 `pipeline/model/PackTerrainProgram`** —— 包地形片元的**接口契约**（输出数 / 自由 sampler 名 /
     输入 varying 签名），一次解析一次冻结。附件数、绑定组条目、适配层签名**三处读同一个对象**。
  2. **新 `pack/PackTerrainSource`** —— 按 composite 同款三态选包选出 `gbuffers_terrain` 并解析契约；
     **无产出即 `null` = 不接线**（不是兜底 passthrough —— 正确兜底是沿用原版 `core/terrain`）。
  3. **虚拟包新增第 4 个资源** `shaders/gbuffers_terrain.fsh`；`null` 时**不提供**该资源（返回 null 而非兜底）。
  4. **新顶点适配层** `assets/vkdisp/shaders/terrain_pack_adapter.vsh` —— 包的 VS 要 7 个顶点属性，
     原版 `DefaultVertexFormat.BLOCK` 只有 4 个 ⇒ 按原版格式取数，逐位置产出包的 9 条 OF varying。
  5. **`TerrainPipelineApi`** —— MRT 变体在开关打开且契约存在时改用 `适配层 VS + 包片元`，
     并按契约**逐条**登记绑定组（`VkDispBuiltins` + 5 个 sampler）；新增每帧上传
     （`VkDispTerrainParams` 眼空间太阳方向 + `VkDispBuiltins` 42 个成员）与绑定摘要埋点。
  6. **`MrtPlan#freezePackOutputCount`** —— 附件数在**管线注册那一刻冻结**，pass 每帧读同一值。
  7. **新配置键 `mrt.packTerrainShader`**（默认关，M1「逐个开启 + 逐个关闭」）。
  8. 新增无头回归 `PackTerrainProgramTest`(7) + `PackTerrainSourceTest`(4)。
  9. 文档：新增 `evidence/h08-…` 与两张截图；`13-GAP-REGISTRY` GAP-003 状态列改写 + 新登记 **GAP-007**；
     `AGENT_CONTEXT` 新增 §10.14。
- **🔴 最重要的发现（时序铁律）**：`RegisterRenderPipelinesEvent` **启动期只触发一次**，且**早于**虚拟包
  `openResources` 生成包源约 **4.5 秒**（实测 08:31:49.704 vs 08:31:54.212）；切包触发的资源重载**不会**让它
  再触发。⇒ **「等包源好了再注册管线」这条路在原版上不存在**，只能**提前**算契约
  （`VkDispVirtualPack#ensureTerrainProgram`，按 `profile|selection` 记忆，实测 3620ms）。
  不修的症状是「开关打开但什么都没发生、且不报错」—— 典型的静默失效。
- **② 附件数跟随包输出数：实测生效。** 配置 `mrt.attachments=3` 故意不改 → 管线与 pass 两侧都是 **1**
  （`colorTargets=1` / `slots=1`），契约 `outputs=1`。**X42 的坑真正堵上**（冻结而非现算）。
- **③ 属性布局对齐：走适配层。** 9 条 varying 里 **6 条真值**（texCoord / lmCoord / sunVec / upVec /
  eastVec / color）、**3 条常量**（mat / recolor / normal）—— 后者因原版地形顶点缓冲既无 `mc_Entity`
  也无 `Normal` ⇒ **新登记 GAP-007**。适配层签名与包片元签名由单测**逐位置逐名字对账**。
- **决定性取证（MCP）**：同一存档 / `time set 6000` / `yaw=35, pitch=-12` / clear，两张截图的地形像素
  **平均绝对差 48.22**、**39.03%** 像素变化，HUD/准星一致；噪声基线（同包复现差 1.91）只到 1/25。
  日志：`MRT terrain pipelines will use pack fragment … colorTargets=1`、`blockMembers=42 samplers=5`。
- **稳定**：0 条 vkdisp ERROR、0 崩、`Missing uniform` / `IllegalStateException` 均未出现、残留进程 0。
  ⚠️ 仍**不**声称「0 validation error」（本机无 validation layer，沿用 §9.4.15 纪律）。
- **🔴 立 X43**：契约解析器首版按**行首**锚定匹配，而 BSL 转译终稿里声明是**两两并排写在同一行**的
  ⇒ location 0 被错配成 `recolor`、location 2 被错配成 `lmCoord`，**静默少认 4 条 varying**。
  **一行里可能有多个声明，必须逐个 findAll**；已写成断言。
- **⛔ 仍未完成**：地形只画进**我方 pass**，主目标仍由原版绘制 ⇒ **本轮不产出用户可见画面改进**
  （M-04 方案 B 未做）；GAP-007 三条常量；`shadowtex0/1` 占位 ⇒ 包阴影不成立；只覆盖 OPAQUE 组；
  只覆盖 BSL 默认配置；提前生成 3620ms 对 B3/B4 的账未补；只验了一个包（X39）。
- **测试**：659 → **670** 单测全绿（新增 11 例）；`./gradlew build` BUILD SUCCESSFUL；残留游戏进程数 = 0。
