# h48x · 🔴 `MC_VERSION` 从来没定义过 ⇒ BSL 的 53 处版本分支**一直按「1.7 之前」编译**

> 取证方式：读包 + 读我方两个条件求值器的实现 + 读 classpath 上那份 `version.json`。
> 全程 CPU-only（无 GPU、无客户端），结论由单测钉住。

## 一、起因：接水时撞见「同一个 uniform 也喂顶点阶段」的连锁排查

给 `gbuffers_water` 取契约时发现 `shaders.properties:167` 有一行
`#if MC_VERSION >= 11800`，它决定生物群集那批 `uniform.float.isCold/isDesert/…`
到底用**符号名**（`BIOME_GROVE`…）还是用**老数字 ID**（`10, 11, 12, …`）。
本轮之前我方一直取的是**老数字那一支**。

## 二、机制（两条实现事实合起来才成立）

1. 我方**从未定义** `MC_VERSION`：
   `grep -rn "MC_VERSION" src/main/java src/main/resources` 只命中一条**注释**
   （`pack/properties/ConditionalPreprocessor.java:23`，写着「未取证，登记为已知缺口」）。
2. 两个条件求值器对「未定义标识符」的口径都是**按 0 算**：
   - GLSL 侧 `glsl/preprocess/DefineProcessor`（`ExprEval` 标识符 → 宏值或 0）；
   - 属性侧 `pack/properties/ConditionalPreprocessor.BoolExpr.parsePrimary`
     逐字 `return m.contains(tok) ? 1.0 : 0.0;`。

⇒ `#if MC_VERSION >= 11800` 求成 `0 >= 11800` ⇒ **假** ⇒ 永远走 `#else`。
⇒ 推广到全部：**包里 `MC_VERSION` 出现 53 次、跨 32 个着色器文件**（外加 properties 那 1 次），
   每一处的分支选择都是错的 —— 也就是**整包被当成跑在 1.7 之前的老版本编译**。

🔖 这条与 GAP-026（store 残留）是同一族：**不是算错，是「缺一个输入 ⇒ 静默按默认值走」**，
而默认值恰好落在错的那一支上，且**没有任何一行日志会说**。

## 三、编码口径：从包自己的门限反推，不是猜

把包里出现过的全部 `MC_VERSION` 比较列出来，形态唯一（`major*10000 + minor*100 + patch`）：

```
   19  MC_VERSION < 12111        1.21.11
   11  MC_VERSION >= 11800       1.18.0
    9  MC_VERSION >= 11300       1.13.0
    3  MC_VERSION >= 11500       1.15.0
    3  MC_VERSION >  10800       1.8.0
    3  MC_VERSION <= 10710       1.7.10
    2  MC_VERSION >= 11605       1.16.5
    2  MC_VERSION >  12106       1.21.6
    1  MC_VERSION >= 12109 / 12111 / 10900 / 10800 / <11605 / <11500 / <10800
```

## 四、运行期真值：读到的不是「我以为的版本」，是 classpath 上那份 `version.json`

`SharedConstants.getGameVersion().name()` 的真身是 jar 里的 `version.json`，实测逐字：

```json
{ "id": "26.3", "name": "26.3", "world_version": 5023, "series_id": "main", "protocol_version": 777, ... }
```

⇒ `MC_VERSION = 26*10000 + 3*100 = 260300`，**高于上面全部门限**
⇒ 定义之后：所有「新版本」分支被选中、所有 `< 12111` 的老支被丢掉。这正是本包在 26.3 上应有的形状。

🔖 中途试过、被否掉的两条路：
- **单测里直接反射读 `SharedConstants`** ⇒ `ClassNotFoundException`（测试类路径没有原版类，实测）；
- **在 `McVersion` / 转译器里打日志** ⇒ `NoClassDefFoundError: net/neoforged/fml/config/IConfigSpec`
  （`VkDisp.LOGGER` 会拉起 FML 配置体系；只改一行注释的 `GlslPipelineTest` 当场变红，实测）。
  ⇒ 所以 `McVersion` **一行日志都不打**，自报挪到生产侧的 `OfUniformManager#reportConventions`。

## 五、修法（已落地，全部有单测）

| 位置 | 改动 |
|---|---|
| `dev/vkdisp/McVersion`（新） | 纯函数 `encode(name)` + 反射 `current()`；**取不到就返回 empty ⇒ 什么都不定义**（不喂猜的数，X9）；失败**不永久缓存**（首次转译可能早于 FML 就绪，一次失败钉死 = 偶发失败变永久降级） |
| `glsl/preprocess/DefineProcessor` | 新增 `engineMacros()` 塞入 `MC_VERSION`（提取成方法而不是摊在 `process()` 里 —— 那里顶在 QD-04 棘轮 60 行边上） |
| `pack/properties/ConditionalPreprocessor` | 标识符求值前先问 `McVersion.numericOf(tok)`；其余标识符仍是「定义/未定义」两态 |
| `render/OfUniformManager` | 每进程一条自报：拿到 = INFO（带原始版本串与编码口径），拿不到 = WARN（带原因），并附在 `[GAP-022/matrix]` 行里 |

测试：`McVersionTest`（编码表用包自己的门限独立写死 / 编不出来必须 empty /
GLSL 侧真的换支且宏名不残留正文 / `numericOf` 只认这一个宏）、
`ConditionalPreprocessorMcVersionTest`（定义后走新支、取不到时**保持旧行为**、普通选项宏不被数值支路带跑）。

## 六、还没做也还没证的（不许当已修）

1. **运行侧一次都没看过**。本轮全部是 CPU-only 证据；`[MC_VERSION] = 260300` 这行
   要在真客户端日志里出现才算「引擎真的定义上了」。
2. **换支之后的画面影响未判**。这一改会同时改变 53 处分支的取舍 ——
   它可能修好一批（生物群集 tint、新版本 API 路径），也**可能引入新的坏**
   （新支里用到的东西我方未必供得起）。⇒ 必须按「同臂、只换这一个开关」做对照，
   并且**先看有没有崩/有没有画面**，再谈画质。
   🔖 因此本轮**不**给它加「默认关」的余地：定义 `MC_VERSION` 是补齐引擎该给的事实，
   不是可选特性；但**下一次运行臂就是它的验收闸**，不过就把住。
3. `biome` 这个**输入本身**仍未供值（OF 的数字生物群集 ID 与 `BIOME_*` 符号名在 26.3 原版侧
   没有可核实对应物）。本轮只把「选哪一支」修对了；新支要真算出 `isCold/isDesert`
   还需要 `biome` + `BIOME_*` 一起供 —— 两者都从**同一个 registry 编号**导出即可自洽
   （比较是 `in(biome, BIOME_X)`，两侧同源就成立，不需要复刻 OptiFine 的历史编号表）。
   登记在 GAP-021 的「未解析输入」那一半。
