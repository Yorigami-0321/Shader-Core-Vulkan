# evidence/ —— 验收证据文本摘要（G-01 白名单目录）

> 审查项 G-01（review/2026-09-30-代码质量审查与改进方案.md 批次 1）：
> 验收证据此前只在 CHANGE_LOG 引用的 gitignored 路径（`run/logs/`、`/tmp/`、
> `tools/vulkan-local/evidence/`），第三方无法在库内复核。本目录入库**文本摘要**：
> 日志关键行原文 + sha256 + 一行复现 + 判定；被摘要的二进制本体不入库
> （`run/`、`tools/` 整目录 gitignored；第三方 pack 素材受 §7.6 限制也不入库）。

## 复核方式

1. 按文件内「一行复现」跑一轮 runClient（或拿到同路径的日志/截图原件）；
2. `sha256sum` 对账表内哈希，确认拿到同一份证据；
3. 按文件内的 grep/统计命令复算计数，与判定表比对。

## 索引

| 文件 | 覆盖轮次 | 一句话 |
|---|---|---|
| `p412-driver-layer.md` | P4.1.2（2026-10-01） | 驱动层四修三跑：15679×Missing uniform → 0 错误纯黑 → 0 错误可见（0.951→11.901），registered=8 compiled=8，141 阶段失败分类学 |
| `p413-uniform-upload.md` | P4.1.3（2026-10-01） | 内建 uniform 上传两跑闭环：双布局 42/24 成员解析 + written 26/24 零错配零越界 + 雨量取值源缺陷（SkyRenderState 提取前默认值）run1 暴露→run2 直读 probe/Level 归零，存档基线（时钟冻结 0/晴天）逐项判读 |
| `p414-final-step.md` | P4.1.4（2026-10-01） | final 步接线两跑闭环：第 9 管线 registered=9 compiled=9、三布局三环（42/24/24）+ uploaded slot=final written=24、日志门双布尔缺陷 run1 吞行→run2 按槽位名 Set 归零、attachment 恒等拷贝顶点推导（判定表「未镜像」行基于云团构图，已由 p416 强制位姿法取代） |
| `p415-properties-conditionals.md` | P4.1.5（2026-10-01） | properties 条件编译两跑闭环：CRLF 续行缺陷 run1 解析失败 ×3 + profiles=[] → 修复归零 + profiles 五档 [ULTRA…HIGH]、WARN 集 diff 只删该文本零新增、单测 492→501 |
| `p416-orientation.md` | P4.1.6（2026-10-01） | 画面方向矫正三跑闭环：强制位姿（Rotation 0,−15°）地平线 204（翻转）→ 392（正立）×2，根因 = P3.3 链 composite 顶点（彩色源是场景、净翻转守恒错算 deferred 一跳），B+ 采样源规则三处落地，run3↔run2 identity=+1.0000 / run1↔run3 镜像 +0.9450，包源与 present 排除 |
| `p417-pack-switch.md` | P4.2（2026-10-01） | 切包回归单会话四截图闭环：shaderPack 三态（自动/包名/none）+ FML 配置热加载驱动，S1↔S4 静态地面带 identity=+1.0000（残差=云飘移）、S2/S3 尖刺 0.000%、S2/S3 亮度比 0.7108=理论 0.711（fixture 全链）、141 矩阵 ERROR ×4 零新增 |
| `p418-options-gui.md` | P4.3（2026-10-01） | 选项 GUI 单会话八截图闭环：GUI 覆盖根因 Post→AfterLevel 迁移（八图 GUI 全可见）、packOptionsScreen 热驱动 open/set/page/done + 未开屏 WARN 拒绝，BSL 284 项分页渲染与 set 回执、fixture 改值像素比 0.8910=理论 0.8889，bytes=24515 疑点核销（指令剥离 + final −1 字节替换）、532 单测全绿 |
| `p4xx-141-matrix.md` | 141 阶段矩阵修复轮（2026-10-02） | 141 矩阵修复首次 runClient 闭环：LegacyBuiltinInjector 第 8 段转译（gl_ 旧内建→合法名/ gbuffer 矩阵 + 属性声明注入）落地，`stages=190 ok=190 failed=0` + `registered=9 compiled=9`；首错遮蔽第二轮 DH 兼容符号（dhMaterialId / DH_BLOCK_* / DH_OVERDRAW）同轮修掉并登记 GAP-002（INFO 显式诊断），零未知失败类收轮；576 单测全绿 |
| `p4x1-bsl-visual.md` | P4.1 BSL 视觉基线（2026-10-02） | P4.1「BSL 主要效果可用」视觉验证：本会话 Agent 不支持看图片 → luma 量化分带 + 日志诊断 + 用户目检三方交叉；BSL vs passthrough A/B（config 热加载切 none）content 20.9/34.1、天空 6.9/28.3、地面 26.6/38.3；渲染连贯、零 vkdisp ERROR、用户确认 BSL 观感正常（偏暗=晨昏风格化非缺陷） |
| `g0-java-coldpath.md` | G 系列 G0（2026-10-02） | **G 线第 0 关：Java 冷路径分段基准**（BSL 91 program / 182 阶段）。分段占比排序八趟稳定：properties/options 解析 **43.2%** > `#include` 预处理 **34.9%** > 转译 8 段流水线 **15.0%** > 包扫描 ~0%（具体百分比在噪声内浮动，勿当精确值引用）；四段合计 **1576.0ms**、生产入口 **1494.9ms**（n=9 中位数）。工具 `ColdPathBenchmark` 自带**语义等价自检**（分段产物与 `GlslPipeline` 逐字节相等，不等即终止）、**三相 golden**（546 条 sha256：`inc`/`pre`/`trans`，供 G1 逐段对齐）与 `--golden-only`（只重建 golden 不动基准数字）。🔴 同时暴露**噪声红线**：八趟跨次极差 ≈9%，与 §5.2 的 20% 裁决阈值同量级 |
| `g1-rust-equivalence.md` | G 系列 G1（2026-10-02） | **Rust 等价实现 · inc + pre 两相**：对照移植自研 `IncludeProcessor`（`#include` 展开）与 `DefineProcessor`（宏与条件编译 + 递归下降 `#if` 求值器），**两相各 182/182 逐字节一致**、0 不一致、0 ERROR、非 ASCII 计数 0、Rust 单测 32/32 且 0 编译告警。零第三方 crate（合规第 0 步零风险），对照工程在**仓库外** `~/Minecraft/g1-rust-bench`。记录 6 个 Java/Rust 语义差异（`strip`≠`trim` 的 NBSP、正则 `\s` 仅 ASCII、换行归一两步叠加、正则回溯假象、`replaceAll("\b\Q..\E\b")` 的 ASCII `\b` 与 `$`/`\` 转义、`isLetterOrDigit`≠`is_alphanumeric`）与 pre 相第一次 133/182 的排查过程（尾切片下标 + 跑的是旧二进制）。🔴 **未下性能结论**（单次冷跑 n=1，Java 侧尚无 inc/pre 单独计时）；**转译相 / `pack/` 解析相 / `SourceLineMap` 均未做 ⇒ G1 未完成** |
| `p4x4a-vanilla-override-rejected.md` | P4.4-a 路线否决（2026-10-02） | 零 mixin 覆盖原版 program 三轮实跑证伪：轮1 把路径当源文本（`<- 28 chars`）+ 缺 GLSL 版本护栏；轮2 `programName` 是维度限定名（`world1/gbuffers_terrain`）裸名匹配永远 miss（`0/2`）；轮3 覆盖 `2/2` 生效但 13 个地形管线全挂 `Not enough components for input attribute UV0, expected at least 4 got 2`。用产品 `GlslPipeline` 离线取真实产物坐实**接口契约不兼容**（vec4 UV0/UV2 vs 原版 RG32/RG16 2 分量、mc_Entity/mc_midTexCoord 原版无此属性、9 varying vs 5 固定、单附件 vs 多输出）⇒ **路线级否决非缺陷待修**，已回退；反向实证 M1 唯一注入点必要性（换 program 必须同时换顶点格式 + 附件数） |
