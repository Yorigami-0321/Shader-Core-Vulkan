# NOTICE · 许可证与上游来源

## 本项目

Copyright (C) 2026 vkdisp contributors（作者：Yorigami `<C_nya@outlook.com>`）

本程序在 **GNU Lesser General Public License version 3，或（由你选择）任何后续版本**
（SPDX：`LGPL-3.0-or-later`）下发布。

| 文件 | 内容 | 取证 |
|---|---|---|
| `LICENSE` | LGPL-3.0 全文 | 与 <https://www.gnu.org/licenses/lgpl-3.0.txt> **逐字节一致**，`sha256 e3a994d8…23118` |
| `GPL-3.0.txt` | LGPL-3.0 按引用并入的 GPL-3.0 条款全文 | 与 <https://www.gnu.org/licenses/gpl-3.0.txt> **逐字节一致**，`sha256 3972dc97…36986` |
| 本文件 | 许可声明、第三方构件、移植台账 | — |

- 源码：`origin = https://github.com/Yorigami-0321/Shader-Core-Vulkan.git`（公开，LGPL 的"随附源码"义务由此满足）
- 分发的 mod jar 内含 `LICENSE` + `GPL-3.0.txt` + 本文件（`build.gradle` 的 jar 打包块显式声明为打包输入）

**换证记录**：2026-10-17 之前本项目为 **MIT**。改写的动因不是许可偏好，而是同一条用户裁决要把
"光影包特性的处理"从自研改为**直接移植 Iris / Vitrail**（两者均为 LGPL-3.0）—— MIT 与 LGPL 不同族，
并入即构成再许可，所以许可证必须先改。可单方改写的依据：`git log` 343 个 commit 里 341 个的著作权人是同一人，
另 2 个是机器人提交（`vkdisp <vkdisp@local>`），无第三方 contributor 需要点头。
MIT 授予的是无限制的再许可，故**已经按 MIT 拿走副本的下游不受影响，且本改写不可撤回**。

> "+or-later" 只加在我们自己的那部分上。Iris / Vitrail 的 `LICENSE` 是 FSF 原文、未附带"v3 或更高"的适用声明，
> 因此搬来的文件按 **v3** 履行义务即可；我们对自己写的部分给"+later"是扩权，不产生冲突。

---

## 移植政策（2026-10-17 用户裁决）

> 「直接把 Vitrail 和 Iris 对光影包特性的处理移植过来，不要自己实现了。」

按许可证族给出的可搬性判定，**这张表是 `docs/07-CONSTRAINTS.md` §一 的执行依据**：

| 上游 | 许可证（一手核过） | 判定 | 备注 |
|---|---|---|---|
| **Iris** `net.irisshaders.iris` | LGPL-3.0 | ✅ **可搬** | 本机检出 `D:/Code/Minecraft/Iris`，`bff1e69cb6c5519d8745784aa9c8b92984de67e7`，分支 `26.1`，工作树干净 |
| **Vitrail** `dev.vitrail` | LGPL-3.0 + 84 KB `NOTICE` | ✅ **可搬** | 本机检出 `D:/Code/Minecraft/Vitrail-Shaders`，`4a45f44bbe160ddae94da945a92e791cd96d1ec5`，分支 `fix/review-of-26-3-support`，⚠️ 工作树有 12 处改动且 `CHANGELOG.md` 处于未解决冲突（`UU`）⇒ **台账一律按 commit 记，不按工作树** |
| **VulkanMod** | LGPL-3.0 | ✅ **可搬**（同族） | 原 `L7` 的"只读思路"作废；`03-DIRECTION` §0 当初否掉它的唯一理由就是"MIT 与 LGPL 不同族"，该前提已随换证消失 |
| **glsl-transformer**（Iris 的 `pipeline/transform` 链它） | **AGPL-3.0** | ❌ **不引库** | 2026-10-17 裁决：**只搬规则，不引库**。引它 = 全项目顶到 AGPL。Iris 自己的 `LICENSE-DEPENDENCIES` 就警告过这一条 |
| **Sulkan** | GPL-3.0 | ❌ 不可搬 | GPL 比 LGPL 严，吸进来会把整个项目推到 GPL-3.0；要吸就得整体换 GPL |
| **Sodium** `net.caffeinemc` | **PolyForm Shield 1.0.0** | ❌ 不可搬 | 与我们的许可证无关，是它自己不许。Iris 的 `compat/sodium/**` 整片**不在移植面内**（本项目走原版 Vulkan，不需要它的顶点管线） |
| **OptiFine** / **Beryl** | ARR（闭源） | ❌ 不可搬 | 包格式的**行为**可以照做（那是事实），代码与文本一个字不搬 |

---

## 随附第三方构件

`libs/` 下三个 jar 随仓库分发（原因见 `libs/README.md`：`build.gradle` 用 `files()` 引用，路径不在就编不过）。
作用域是 `testImplementation` ⇒ **不进发布的 mod jar、不进运行期类路径**。三个都是上游原始构件、未修改，
故"声明改动"义务不适用。

| jar | Maven 坐标 | 许可证 | 上游 |
|---|---|---|---|
| `jcpp-1.4.14.jar` | `org.anarres:jcpp:1.4.14` | Apache-2.0 | <https://github.com/shevek/jcpp> |
| `slf4j-api-1.7.12.jar` | `org.slf4j:slf4j-api:1.7.12` | MIT | <https://www.slf4j.org> |
| `java-semver-0.8.0.jar` | `com.github.zafarkhaja:java-semver:0.8.0` | MIT | <https://github.com/zafarkhaja/java-semver> |

其余非自研内容：`TEMPLATE_LICENSE.txt`（NeoForge MDK 模板自带 MIT，只管模板文件）；
Mojang 映射的授权见 `README.md`。

---

## 移植台账

LGPL-3.0 §4 的"声明改动"义务逐文件落实在这里。**每个并入文件一行，无台账不落盘。**

| 我们的文件 | 上游仓库 @ commit | 来源文件:行 | 读取日期 | 改动声明 |
|---|---|---|---|---|
| — | — | — | — | **当前为空：2026-10-17 这一轮只换证，零行并入** |

搬的时候每落一个文件，同时做四件事：

1. 文件头注明来源仓库 + commit + `文件:行` + 读取日期 + 改了什么（照 Vitrail `NOTICE` 的行文口径，它已经替我们示范过一遍）
2. 该上游文件若自带 Apache-2.0 / MIT 材料（Vitrail `NOTICE` 里 jcpp 的取值、AMD FSR 的 GLSL 端口就是这类），**照抄其声明**，不得只写"改编自上游"
3. 从 Sodium / OptiFine / glsl-transformer 那里取到的是**规则或取值**而非代码时，在文件头写明"取的是规则不是代码"，并给出读取的类名与行号——Vitrail 对 `MixinDefaultFluidRenderer`、`VanillaTransformer` 都是这么记的
4. 台账 + 本文件同步，jar 里带上

## 表述纪律（换证后仍然保留的部分）

- 衍生来源**必须**声明（LGPL 的 prominent notice 义务），但不得写成"与 Iris / Vitrail 存在官方关联或获得背书"——归属 ≠ 背书
- 不得把本项目描述成"Sodium 替代品"（不同层的东西，且触碰其品牌）
