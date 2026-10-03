# H03 · M-05：地形 draw 数据**只读捕获**可行 —— 方案 A 的两个前提都成立

> 验证对象：`mixin.LevelRendererChunkCaptureMixin` + `bridge.TerrainDrawCapture`
> + `FullscreenPassHook` 的时序埋点（2026-10-03 新增）。
>
> **结论先行**：✅ **方案 A 需要的两个前提都成立** ——
> ① 地形 draw 数据对象**捕获得到**（且非 null，命中的是 `prepareChunkRendersIndirect`）；
> ② **时序成立**（捕获早于帧图 pass 体执行，AfterLevel 处已可见本帧捕获）。
> 🔴 **本轮没有画地形** —— 只验证前提，不动渲染行为。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（Vulkan + lavapipe） |
| 客户端 | `./gradlew runClient -PquickPlay`，1 趟 + 配置热加载切开关 |
| 库存 | `shaderPack="none"`（隔离变量，同 `h01` §5 / `h02`） |
| 日志 | `run/logs/latest.log`，sha256 `2c732ced…` |
| 残留进程 | ✅ 收尾 `残留游戏进程数 = 0` |

## 一行复现

```bash
source tools/vulkan-local/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew build && ./gradlew runClient -PquickPlay --console=plain
grep -E "M-05" run/logs/latest.log
```

## 1. 为什么需要这个注入点（官方事件给不了，已源码级证伪）

原版 `LevelRenderer#render` 的语句顺序（26.3.0.41-beta sources jar 行号）：

| 行 | 语句 |
|---|---|
| 249 | `ClientHooks.fireFrameGraphSetup(frame, targets, …)` ← **官方事件在此** |
| 271-275 | `chunkSectionsToRender = prepareChunkRenders[Indirect](…)` ← **地形 draw 数据在此创建** |
| 277 | `addMainPass(frame, featureFrame, terrainFog, chunkSectionsToRender, …)` |
| 286 | `frame.execute(resourceAllocator, …)` ← **pass 体在此才执行** |

⇒ **官方 `FrameGraphSetupEvent` 触发时，`chunkSectionsToRender` 尚未创建**（249 < 271）。
想在官方事件里拿到它是不可能的 —— 这不是「还没试」，是**读源码得出的事实**（X9）。

⇒ 唯一可行的官方层做法是：在**帧图的 pass 体里**用它。但 pass 体要画地形就需要这个对象，
而 pass 体在 286 行执行、对象在 271 行创建 ⇒ **只读捕获引用**即可，时序天然成立。

⚠️ **注意 `prepareChunkRenders` 与 `prepareChunkRendersIndirect` 是二选一**
（由 `usingMultiDrawIndirectForTerrain` 决定，第 269-275 行）。
**两个都必须注入** —— 漏掉 indirect 就捕获不到。本机实测命中的是 **indirect** 分支：

```
[15:44:39.135] vkdisp: [M-05] terrain draws captured from prepareChunkRendersIndirect
    (capture#1, non-null=true; read-only capture, vanilla behaviour unchanged)
```

🔖 这条实测恰好证明了「两个都要注入」不是纸上顾虑：**只注入非 indirect 分支的话，
本机（本机唯一的测试环境）会永远捕获不到**，而这个失效是静默的（没有报错，只是引用一直 null）。

## 2. 时序前提实测（方案 A 成立的关键）

`AfterLevel` 事件在 `LevelRenderer#render` 返回**之后**触发，即帧图已执行完（286 行已过）。
若捕获发生在 271-275 行，则此处必然能看到本帧的捕获：

```
[15:44:39.135] vkdisp: [M-05] terrain draws captured from prepareChunkRendersIndirect (capture#1, non-null=true)
[15:44:39.149] vkdisp: [M-05] capture visible at AfterLevel (render thread, after frame graph executed):
    captures=1 from=prepareChunkRendersIndirect nonNull=true
[15:44:39.366] vkdisp: [M-05] capture visible at AfterLevel …: captures=2 … nonNull=true
[15:44:39.553] vkdisp: [M-05] capture visible at AfterLevel …: captures=3 … nonNull=true
```

⇒ ✅ 捕获（271-275）**早于**帧图执行（286），pass 体执行时引用**已就绪**。
且捕获计数**每帧递增**（1→2→3），说明不是「碰巧捕获过一次」。

> 🔖 **为什么要专门验时序**：这是从源码读出的**推论**，不是实测。
> 本项目吃过「推论对而运行时不成立」的亏（`h01` §4 的截图矛盾：
> 两次 runClient 结论互相矛盾，最后靠控制实验才定案）。
> 「看起来应该对」和「实测确实对」在验收上是两件事。

## 3. 只读性验证（本注入点与 M-01/M-01b 的本质区别）

M-01/M-01b **改变**渲染行为（换管线 / 加 uniform）；M-05 **不改变任何东西**。

| 判据 | 结果 |
|---|---|
| 注入位置 | `@At("RETURN")`（捕获返回值，不改它） |
| `cancellable` | **代码里零出现**（有单测守着，见 §6） |
| `setReturnValue` | **代码里零出现**（同上） |
| 视觉影响 | 开关 ON/OFF 两张截图 **逐字节相同**（`3e95e5c0…`） |
| validation error / `VUID-` / `Missing uniform` | **0** |
| vkdisp `ERROR` | **0** |
| `pack compile done` | `stages=190 ok=190 failed=0`（未回退） |
| `pipeline count check` | `registered=17 compiled=17 (aligned)` |

🔖 **「不改行为」不是靠声明，是靠证据**：截图逐字节相同 + 零 validation error +
单测禁止 `cancellable`/`setReturnValue` 三条同时成立才算。

## 4. 一键关闭实测

```
mixin.captureTerrainDraws = false   →  画面仍逐字节相同（3e95e5c0…）
```

关闭后捕获停止、引用为空，**我方多附件地形 pass 静默不开** ——
「未启用」不是失败，所以不报错（T11 的例外：显式关闭不是降级）。

## 5. 🔴 明确**未完成 / 未证明**的

| # | 项 | 说明 |
|---|---|---|
| 1 | 🔴 **地形仍未画进多附件 pass** | 本轮**只验前提**。捕获到的引用目前**没有任何消费者** |
| 2 | 🔴 **GAP-003 未完成** | M-05 只是方案 A 的入口。真正的地形多附件渲染是下一步 |
| 3 | 捕获到的对象**类型未在运行期核实** | 本类刻意持有为 `Object`（不让原版类型泄进业务层）⇒ 运行期只知「非 null」，**未验证它确实是 `ChunkSectionsToRender`**。下一步消费时才会真正核实 |
| 4 | 非 lavapipe 硬件 | 捕获本身与硬件无关，但 `usingMultiDrawIndirectForTerrain` 的取值**依赖设备能力** ⇒ 别的机器可能走非 indirect 分支（**这正是两个都要注入的原因**） |
| 5 | 多线程 | 捕获在渲染线程、`AfterLevel` 埋点也在渲染线程 ⇒ 无跨线程问题。**未测**其它读点 |
| 6 | 性能 | 每帧 1 次 volatile 写。**未测帧时间** |

## 6. 测试

**641 单测全绿**（本轮新增 2 例，均在 `MixinWiringTest`）：

| 用例 | 锁住什么 |
|---|---|
| `m05CapturesBothOverloads` | 两个重载都注入（漏一个 ⇒ 本机静默捕获不到） |
| `m05IsReadOnlyReturnInjection` | 恰好 2 个注入、全是 `@At("RETURN")`、代码里无 `cancellable` / `setReturnValue` |

🔖 **写这两个用例时自己踩了两次**（都是**测试自己的** bug，已修并留注释）：
① 用 `split` 全文数字面量 ⇒ 把类 javadoc 里【参考调研】引用的 `@Inject(...)` 示例算成了代码；
② 断言「无 `cancellable`」⇒ 被 javadoc 里**解释「cancellable 为何刻意为 false」的那段说明**判为违规。
⇒ 加了 `countCodeOccurrences`（只统计非注释行）。**教训：文本断言必须区分代码与注释**，
否则文档写得越详细，测试越容易误报。

## 7. 对「方案 A vs 方案 B」结论的影响

上轮结论（**A 更稳定**）**未被本轮推翻，反而被加强**：

- 方案 A 需要的是**只读捕获**（本轮已证可行）；
- 方案 B 需要的是**改原版 pass 的附件语义**（`addMainPass`，M-04）。

⇒ 两者的风险等级差在本轮被**实测**拉开：一个是「不改行为的引用捕获」，
另一个是「改原版渲染 pass」。且方案 A 的入口已通、已有关闭开关、已证明零视觉影响。

## 8. 下一步（方案 A 的第 2 步）

1. **消费捕获到的引用**：在帧图里插一个**只画地形**的多附件 pass
   （`executeSolid` 接受 `RenderPass` 作参数 ⇒ 地形单独 pass 在原版结构里是自然切点）。
2. ⚠️ **必须先解决的**：`renderGroup` 需要 `sampler` 与 `blockAtlas`
   （原版在 `LevelRenderer` 第 442-447 行自建 sampler、第 531 行取 atlas）——
   我方 pass 需自己准备这两个，或复用原版已建的。**这一项尚未核实**，是下一步的第一个卡点。
3. 附件 0 与主目标的关系：地形 pass 若另开 pass，附件 0 是否还写主目标？
   影响半透明地形与后续特性的混合顺序。

## 9. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| `run/config/vkdisp-client.toml` | `shaderPack` 改 `"none"`；新增 `mixin.captureTerrainDraws`（FML 自动写入默认值） | 收尾还原 `shaderPack = "BSL_v10.1.8"`；`captureTerrainDraws = true`（= 默认） |
| 同上 | 取证期间切 `captureTerrainDraws` true→false→true | ✅ 同上 |
| `run/config/vkdisp-pack-options.properties` | **未改动** | sha256 `e76d3fd3…` |
| 游戏进程 | 1 趟 runClient | ✅ 已结束，**残留 = 0** |