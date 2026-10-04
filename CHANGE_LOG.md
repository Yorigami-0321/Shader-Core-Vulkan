# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。
---

## 2026-10-04（三十三）— ✅ DRAWBUFFERS 槽位兑现 + BSL 布尔选项可开 ⇒ 8 槽 gbuffer 真正跑起来

> **verdict = 兼容轮 + MCP 客户端取证（跑了真实游戏）。**
> 任务来源：`AGENT_CONTEXT` §10.14 ⑨ 与 GAP-003 第 ⑤ 条遗留。

- **本次改了什么**（三件事，一件比一件靠后，一件比一件隐蔽）：
  1. **新增转译第 ⑦½ 段 `DrawBuffersSlotAdapter`** —— 按包源码里的 `/* DRAWBUFFERS:… */` 把
     `layout(location = k)` 改写成包真正要的 colortex。**累积语义**（索引 k 取最后一条长度 > k 的标记）。
     排在 ⑦ 之后、⑧ 之前；等行数变换 ⇒ 行号映射不受影响。**三条显式拒绝**：单条内槽位重复 /
     需超过 `maxColorAttachments=8` / 标记未覆盖的输出；拒绝时 `PackTerrainSource` 跟着**拒绝接线**。
  2. **`ConstEvaluator` + `OptionSourceRewriter` 补 `//#define` 路径** —— 裸宏 / 注释掉的裸宏 = BOOLEAN；
     `//#define` + true ⇒ 去 `//`，false ⇒ **保持原样**（不补 `//` 污染包源）。
  3. **顶点适配层改为按契约生成**（`PackVertexAdapterGenerator`），随片元源**同生共死**；
     静态资产 `terrain_pack_adapter.vsh` **删除**（避免两份真源）。
- **🔴 消灭一条静默 bug**：`gl_FragData[k]` **不等于** `location k`。原先按��标绑定会把 BSL 的材质写进
  colortex1、法线写进 colortex2 —— **画面「有内容」但每个通道都错，没有任何一行日志会抱怨**。
- **🔴 挖出更靠前的真门槛**：BSL 的布尔选项此前**既不可见也不可改**。实测全包 **446 行裸 `#define`**
  + **37 行 `//#define`**，而原规则只认「带值 + `[...]` 候选表」⇒ 客户端 **284 个枚举选项里没有
  `ADVANCED_MATERIALS`、布尔数 = 0** ⇒ **多槽路径根本无法被触发**，h06 的结论此前只能停在纸面。
  ⇒ 选项 **284 → 386**，布尔 **0 → 102**。
- **🔴 只跑客户端才暴露的第三个问题**：静态适配层写死 9 条 varying，开高级材质后包要 **15** 条 ⇒
  `ShaderCompileException: Vertex shader missing output at location 14` ⇒ **资源加载失败、
  客户端进不了世界**（jstack 证实渲染线程停在主菜单）。⇒ 改为按契约生成；实测
  `顶点适配层已生成：varyings=15（常量供值 7 条：mat, recolor, normal, binormal, tangent, vTexCoord, vTexCoordAM）`。
- **实测（MCP 客户端）**：契约 `outputs=8 samplers=7 varyings=15`；管线 `colorTargets=8`、pass `slots=8`
  （配置 `mrt.attachments=3` **故意不改** ⇒ 两侧都被改成 8，h08 的冻结机制继续生效）；
  `terrain drawn into 8 attachment(s)`；`SOLID{groups=1,draws=610}`；
  **0 ShaderCompileException（修前 ≥3）/ 0 Missing uniform / 0 vkdisp ERROR / 残留进程 0**。
- **⛔ 画面不对，已登记 GAP-008（不假装已修）**：截图是绿色清屏底上的**纯黑剪影** —— 几何与槽位路由对
  （轮廓清晰、610 条 draw），**像素值错**。两条候选成因（常量供值的 7 条 varying 参与光照 / 新增
  `specular`·`normals` 采样器绑的是图集占位）**本轮未逐项二分验证**，故不先猜一个「看起来对」的绑定。
- **能力边界（如实登记）**：`DRAWBUFFERS:08367`（MCBL_SS + 高级材质同开）需 **9** 附件 > Vulkan 上限 8
  ⇒ **显式拒绝接线**，不夹取。已写成断言。
- **🔴 立 X44**：改写行的代码必须断言「输出行仍能被同一套 pattern 再解析回去」。本轮自造并修掉两个
  同类回归：① `OptionSourceRewriter` 首版从**前导空白**上切 2 个字符（以为那是 `//`）⇒ 整行改坏 ⇒
  预处理器报 `Range [0, -2) out of bounds` 并**丢掉全部 182 个编译阶段**；② 同类正则 `\s*` 吃掉行尾
  注释前的空格。
- **测试**：670 → **689** 单测全绿（本轮共新增 19 例：`DrawBuffersSlotAdapterTest` 8、
  `PackVertexAdapterGeneratorTest` 5、`PackBooleanOptionTest` 6）；`./gradlew build` BUILD SUCCESSFUL；
  残留游戏进程数 = 0。
- **⛔ 仍未完成**：8 槽像素值不对（GAP-008）；地形仍只画进**我方 pass**（M-04 未做，**不产出用户可见
  画面改进**）；GAP-007 的常量项从 3 条扩到 **7** 条；`specular`/`normals` 采样器是图集占位；
  只覆盖 OPAQUE 组；只验一个包（X39）；新出现的 102 个布尔选项对**选项屏幕**的影响未测。
