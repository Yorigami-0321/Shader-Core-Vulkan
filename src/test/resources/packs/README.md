# 测试 pack 固定样本（fixture）

依据 `docs/18-PARALLEL.md` §7.6：

- ✅ 只放**自造最小 pack**（如 minimal：1 个 composite），进本目录的用例随仓库分发。
- ❌ 禁止提交 BSL / Complementary / Sildur's 等**第三方包**（污染 MIT 授权链）。
- ✅ 第三方包只允许放在本地做手工验证，不进仓库、不进 jar。
- ❌ 禁止把第三方 pack 的任何 `.glsl` 片段复制进单测预期值。

目录约定（随各并行线落地时创建）：
`minimal/` —— A/C 线最小解析样本（1 个 composite + shaders.properties）
`broken/` —— 损坏样本（坏 zip、缺文件），供 B 线边界用例
