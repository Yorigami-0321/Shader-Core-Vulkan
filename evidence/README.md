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
| `p414-final-step.md` | P4.1.4（2026-10-01） | final 步接线两跑闭环：第 9 管线 registered=9 compiled=9、三布局三环（42/24/24）+ uploaded slot=final written=24、日志门双布尔缺陷 run1 吞行→run2 按槽位名 Set 归零、attachment 恒等拷贝顶点推导截图实测未镜像 |
