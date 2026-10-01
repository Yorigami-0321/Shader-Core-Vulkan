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
