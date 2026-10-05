# h35 · QD-04 定位与更正 + QD-05 全仓审计：两条「结论早就写下来了、但从没被验证过」的债

> **日期**：2026-10-05
> **性质**：一轮**审查债闭环**，没有新功能。
> **verdict = 两条登记在案的债都在本轮**第一次被真正查**，
> 其中 QD-04 的登记数字是**错的**（写 3 个、实测 21 个）；两条都配上了构建期守卫。**
> 795 条单测全绿 + runClient 复验。
> 任务来源 = `QUALITY-DEBT.md` §3「第 0 步：检查本表是否有可低成本闭环的项」
> （QD-04 登记的下一步原文就是「下一轮审查先 grep 定位这 3 个方法」）。

---

## 一、一页版结论

| 项 | 登记的说法 | 本轮实测 | 处理 |
|---|---|---|---|
| **QD-04** | 「主源码仍有 **3** 个 `>60` 行方法（2026-09-30 时 17 个，**已显著改善**）」 | **21 个**（修正口径后） | 更正 + 新增 `MethodLengthRatchetTest` 棘轮 |
| **QD-05** | 「下轮审查按此 grep 全仓」 | **81 个 catch 块**：41 个已记录异常原文、40 个静默 | 审计 + 修 1 处真问题 + 新增 `CatchThrowableVisibilityTest` |
| 单测 | 791 | **795** | +4（棘轮 4 条、catch 守卫 4 条） |

🔖 **本轮的核心观察**：这两条债的共同点是
**「结论写下来了，但从没有任何东西让它保持正确」** ——
QD-04 的原定下一步是「下一轮审查先定位」，结果没人真的数过，数出来差 7 倍；
QD-05 的原定下一步是「下轮审查按此 grep 全仓」，同样没约束住任何东西。
⇒ 与 `h33` 的门控死开关、`h34` 的日志刷屏同属一族。**这才是要治的病。**

---

## 二、QD-04 · 长方法：登记的「3 个」是错的

### 2.1 实测（口径：正文行数 = 闭合行 − 声明行，即排除声明行、含闭合行）

```
$ python3 /tmp/methodlen.py
total methods analyzed : 679
methods > 60 lines     : 21
```

### 2.2 🔖 人工验证了 2 个样本（`07` §九 纪律：报数前先人工验证 1 个样本）

**样本 1 · `bridge/FrameApi.java:663`**（排行第一，356 行）

```
$ awk 'NR>=663' FrameApi.java | awk '{d+=gsub(/{/,"{"); d-=gsub(/}/,"}"); if(d==0){print "closes at line "NR+662; exit}}'
closes at abs line 1019
$ wc -l FrameApi.java
1020
$ sed -n '1017,1020p' FrameApi.java
        return new FrameSize(width, height);
    }
}
```

⇒ 656…1019 是**同一个方法的闭合**（文件最后一行 1020 是类的 `}`），
且没有下一个方法声明。**样本成立。**

**样本 2 · `glsl/translate/FragmentOutputAdapter.java:196`**（261 行）

```
closes at abs line 457
$ grep '"[^"]*[{}][^"]*"' FragmentOutputAdapter.java   →  无命中（字符串里没有花括号）
$ sed -n '452,457p'
        if (effective == ShaderStage.VERTEX) { ... }
        return "着色器阶段未知，无法确定 ... 拒绝改写";
    }
}
```

⇒ 196…457 是真方法，且**排除了「花括号出现在字符串里导致配平错乱」这一可能**。**样本成立。**

### 2.3 分类（QD-04 原话：「若是核心转译逻辑，长方法可接受」）

| 类别 | 数量 | 代表 | 判断 |
|---|---|---|---|
| 转译器核心 `glsl/translate/` | 9 | `FragmentOutputAdapter` `OfGlslTranslator` `LegacyBuiltinInjector` | **可接受**（按登记原话） |
| 解析器 / 布局 / 配置 | 5 | `PackCapabilityGate.apply` `VertexLayout.parseCanonicalText` | **可接受**（单趟线性解析） |
| **渲染编排 `bridge/` `render/`** | **6** | `FrameApi#drawFullscreen`(356) `MrtTerrainPass#drawTerrain`(143) `FullscreenPipelineRegistrar`(118) | **值得拆** |

⇒ 真正该动手的是那 6 个，且 `drawFullscreen` 一家占 356 行。
🔖 **本轮不拆**：改热路径要单独取证（runClient + 截图对照），不能和文档/守卫轮混在一起。

### 2.4 新增棘轮（`MethodLengthRatchetTest`）

- 基线 `BASELINE = 21`（不是登记的 3）
- 降低基线 = 进步，**提高基线必须在 diff 里被看见**
- 失败时输出**可执行的排行榜**（行数 + 文件:行 + 签名）而不是只报一个数字
- 配 3 条元测试：口径正确（排除声明行）、能数出超长方法、注释不算方法体

---

## 三、QD-05 · 全仓 catch 审计

### 3.1 实测

```
catch blocks total           : 81
  DO record the throwable    : 41
  SILENT                     : 40
```

### 3.2 🔖 分析器的两次自我修正（方法学留档）

第一版脚本报「0 个记录 / 45 个静默」—— **明显不可能**
（`FullscreenPassHook:201` 明明有 `LOGGER.error(..., t)`）。
第二版报「81 个全部无法解析」—— 我把 `} catch (X e) {` 对深度的净贡献算成了 +1，
实际是 **0**（闭合 try 块 + 开启 catch 块）。
⇒ 第三版修正后，用**已知会记录的三处**做 sanity check，确认分类正确才采信数字。
🔖 这就是 `07` §九「报数前先人工验证 1 个样本」为什么是硬要求。

### 3.3 40 个静默块里，真正的只有 1 个

| 类型 | 数量 | 判断 |
|---|---|---|
| `catch (NumberFormatException)` | 14 | **正当**：解析用户/包文本降级到默认值，错误记在别处 |
| `catch (IOException)` | 12 | **正当**：同上；抽查 `ShaderPackScanner:68` 是把 `\|stat-failed` **记进缓存键文本**，属带内记录 |
| 其它窄类型 | 12 | **正当**：同上模式 |
| **裸 `catch (Throwable)` 无交代** | **1** | 🔴 **真问题** |

### 3.4 🔴 修掉的那个真问题

`bridge/TerrainPipelineApi.java#blockAtlasSizeOrEmpty()`：

```java
} catch (Throwable t) {
    return new int[] {0, 0};        // ← 异常对象整个被丢掉
}
```

**为什么危险**：这个值**每帧**喂进 `OfUniformManager`（`updateTerrainBuiltins` 的两个调用点，
第 488 / 548 行）。图集尺寸错了 ⇒ 整条 OF uniform 静默走偏 ⇒ **画面不对但不报错**。
而「纹理真的没加载好」与「这里就是没有图集」在日志里**完全一样**。

🔖 这正是 `h33` 门控死开关那一族：**真错误伪装成默认值**。

⇒ 改为**一次性** ERROR 并带上异常原文（按 `T11` 降级必须可见 + `X9` 不猜）；
**必须一次性**是因为它每帧被调两次 —— 与 `h34` 刚修掉的 499 行刷屏是同一类错误，不能重犯。

### 3.5 新增守卫（`CatchThrowableVisibilityTest`）

判据**不是「catch 了什么类型」**，而是：

> 裸 `catch (Throwable x)` 必须**用到 x**，或**在块内写明为什么可以丢弃**。

| 为什么这么定 | |
|---|---|
| 为什么不禁止一切 `catch (Throwable)` | 本仓有**正当**的防御用法（`PackPrecompileScheduler:154` 明确写着「预编译失败不阻断切换，真正的加载会在同步路径里再试一次并给出诊断」）。一刀切会逼人删掉必要的防御 |
| 为什么不只在 review 里手工查 | `h33` 的门控死开关就是手工检查漏掉的；QD-05 登记的原文「下轮审查按此 grep 全仓」同样没约束住任何东西 |
| 为什么不管窄类型 catch | `h35` 全仓审计已核实：81 个里 41 个已记录，其余多数是「降级 + 诊断」的正当做法 |

配 3 条元测试：能抓到无交代的丢弃 / 放过有注释的正当用法 / 窄类型不在范围内。

---

## 四、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| **拆 `FrameApi#drawFullscreen`（356 行）** | ❌ **未做**，有意留到单独一轮（改热路径必须单独取证，不能与文档/守卫轮混做） |
| 其余 5 个渲染编排长方法 | ❌ 未做（同上；棘轮已把基线钉住，不会再涨） |
| 40 个静默 catch 的**逐个**语义复核 | ❌ 未做。本轮只做了**分类 + 抽样人工核验**，并在文档里写明「多数正当」的依据是抽样而非全量 |
| `bridge/` 之外的 60 余处 `return null`（QD-06） | ❌ 未动 |
| **Vulkan 后端验证** | ❌ **做不到**（本机 WSL2 无 Vulkan ICD，GPU 为 `llvmpipe` 软件光栅）。本轮运行期结论均来自 OpenGL 后端 |
| 性能 | ❌ 未测（软件光栅无意义） |

---

## 五、复算 / 自检命令

```bash
# QD-04 棘轮
./gradlew test --tests 'dev.vkdisp.MethodLengthRatchetTest'

# QD-04 人工验证样本 1
awk 'NR>=663' src/main/java/dev/vkdisp/bridge/FrameApi.java \
  | awk '{d+=gsub(/{/,"{"); d-=gsub(/}/,"}"); if(d==0){print "closes at "NR+662; exit}}'
wc -l src/main/java/dev/vkdisp/bridge/FrameApi.java     # 1020

# QD-04 人工验证样本 2（并确认字符串里没有花括号）
grep '"[^"]*[{}][^"]*"' src/main/java/dev/vkdisp/glsl/translate/FragmentOutputAdapter.java

# QD-05 守卫
./gradlew test --tests 'dev.vkdisp.CatchThrowableVisibilityTest'

# QD-05 全仓审计（81 / 41 / 40）
python3 /tmp/catchaudit3.py

# runClient 复验
grep -cE 'gbuffer terrain pass failed|textureView and sampler must both' run/logs/latest.log
grep -c 'gbuffer terrain targets ready' run/logs/latest.log        # 期望 1
```

---

## 六、自检

- [x] 两条债都是**先查证再动手**，且查证结论与登记**不一致时如实更正**（3 → 21）
- [x] 报数前**人工验证了 2 个样本**，且第 2 个专门排除了「字符串里花括号」这一干扰项
- [x] 分析器自身出错时**先怀疑数据再改代码**（「0 个记录」明显不可能 ⇒ 改脚本，不是下结论）
- [x] 分类结论写明依据是**抽样**还是**全量**（§3.3 明确说 40 个静默块只做了抽样复核）
- [x] 两条债都配了**带元测试的守卫**，而不是只写进文档
- [x] 明确不做 `drawFullscreen` 的拆分并说明理由，没有含糊带过
- [x] 如实列出本轮**没做的事**（§四），尤其 Vulkan 与性能均无结论
