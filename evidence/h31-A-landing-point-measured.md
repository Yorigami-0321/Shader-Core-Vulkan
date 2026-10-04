# h31 · ✅ **A 的两个落点实测完毕：用户可见画面几乎逐像素相同**，差别只在内部规模 ⇒ 建议落在 `PARALLAX` 层

> 任务来源：§10.36 ⑦ 裁决项的**决策输入**（用户明确要求实测 A/B 两个落点的画面后果）。
> 方式：隔离车道 `run/h27`，三臂单变量 + X51 + 主目标/诊断视图双观测面。
> 取证环境 `set_time(6000)` + `set_weather(clear)` + `look(yaw=90,pitch=0)`；`mrt.terrainColorProbe=false`。

---

## 〇、一句话结论

在**主目标**（用户真正看到的画面）上，关 `PARALLAX` 与关 `ADVANCED_MATERIALS`
**地形区逐像素数值完全相同**（luma 都是 `96.1485`，meanRGB 都是 `(90.96, 97.85, 94.61)`），
两帧画面肉眼也分不出（差别只在云层）。

但内部规模差一个数量级：**`8/7/15` vs `1/5/9`**（颜色目标/采样器/varying）。

🔶 ⇒ **该按工程依据选，不按视觉选**：落点取 **`PARALLAX`**，
因为它保住 GAP-003 的 **8 槽 gbuffer**；取 `ADVANCED_MATERIALS` 会让多附件语义**直接落空**。

🔶 附带：**关掉 `PARALLAX` 本身就是一次用户可见的修复** ——
主目标地形 luma **`0.0000` → `96.1485`**（见 §三）。

---

## 一、两落点的静态差异（实测日志，非推理）

| 落点 | `命中` 行 | 产物规模 | `terrain drawn into` |
|---|---|---|---|
| 关 **`PARALLAX`**（`ADVANCED_MATERIALS` 留开） | `命中 3/3 [… PARALLAX=false, SHARPEN=3, ADVANCED_MATERIALS=true]` | `colorTargets=8 samplers=7 varyings=15` | **8 attachment(s)** |
| 关 **`ADVANCED_MATERIALS`** | `命中 2/2 [PARALLAX=false, SHARPEN=3]` | `colorTargets=1 samplers=5 varyings=9` | **1 attachment** |

🔖 `PARALLAX` 是**嵌套在 `ADVANCED_MATERIALS` 内**的（`gbuffers_terrain.glsl:169-185`），
所以前者是「只摘掉依赖错配 UV 空间的那一个特性」，后者是连锅端。

## 二、🔖 一个容易误判的日志现象（**不是 bug**，已查清）

关 `ADVANCED_MATERIALS` 时 `命中` 行只显示 **2/2**，**没有**第三项
—— 看着像「覆盖没生效」，但同一趟的 `colorTargets=1` 证明它**确实生效了**。

真因：覆盖表是**与包默认值做差分**（`PackTerrainSource.diffAgainstDefaults:265-274`）：

```java
if (!entry.getValue().equals(defaults.get(entry.getKey()))) {
    overrides.put(entry.getKey(), entry.getValue());   // 等于默认值 ⇒ 不进表
}
```

而 `ADVANCED_MATERIALS` 的**包默认就是关**（所以默认档产物才是 `1/5/9`）
⇒ 写 `=false` 等于「写回默认值」⇒ 被正确地当作**空操作**丢弃。

🔖 这个设计是对的（没改动的选项不进表，见该方法注释）。
⚠️ 但**读者会误判**：X51 的「命中 N/N」是本项目确认变量生效的锚点，
而这里 `N` 的分母**会因空操作而变少**，日志没解释 ⇒ **登记为可观测性改进项**
（要么把空操作也列出来并标注 `=默认(空操作)`，要么在行尾注明分母来源）。**本轮不改。**

## 三、主目标（用户实际看到的画面）—— 三臂

采样：纯地形带 **57,218 px**（区域与 `h28` §六 定的口径一致）。

| 臂 | `ADVANCED_MATERIALS` | `PARALLAX` | luma | meanRGB | maxR | 非黑 |
|---|---|---|---:|---|---:|---:|
| **修复前**（`h27b` isoA） | 开 | 开 | **`0.0000`** | **`(0,0,0)`** | **0** | **`0.000%`** |
| 落点 ① | 开 | **关** | **`96.1485`** | `(90.96, 97.85, 94.61)` | 253 | `100.000%` |
| 落点 ② | **关** | 关 | **`96.1485`** | `(90.96, 97.85, 94.61)` | 253 | `100.000%` |

![落点①：关 PARALLAX，ADVANCED_MATERIALS 留开](h31-images/main-AMon-Poff.png)

![落点②：关 ADVANCED_MATERIALS](h31-images/main-AMoff.png)

🔶 **两个落点在地形区逐像素同值**（小数点后 4 位一致）；整帧 sha256 不同，
差别来自**云层/天空**（`time set 6000` 下仍有云在动）⇒ **不是「看起来一样」，是地形区同值**。

## 四、诊断视图（`viewSlot=0` = 原始 albedo）—— 三臂续成链

| 臂 | `ADVANCED_MATERIALS` | `PARALLAX` | luma | 非黑 |
|---|---|---|---:|---:|
| `h28` 对照 | 开 | 开 | `0.0000` | `0.000%` |
| `h29` | 开 | 关 | `12.3702` | `100.000%` |
| **本轮** | **关** | 关 | `10.2921` | `100.000%` |

🔶 原始 albedo 在两个落点下**都非 0**、但**数值不同**（12.37 vs 10.29）
⇒ 两者喂给 composite 的数据不同（槽数、`varyings` 数都不同），
**只是这一版 composite 把差异抹平了**。⚠️ 不代表两者内部等价。

## 五、🔴 顺带更正 `h29` 自己的一处判读

`h29` §五 写「画面仍不正确：整幅均匀暗棕、地形轮廓不可辨」——
🔴 **那句话用的是诊断视图（`viewSlot=0`）**，那里显示的是**原始 albedo 槽**，
**本来就不是用户看到的画面**，把它当「画面不正确」是**判读对象搞错了**。

主目标实测：两个落点都渲染出**完整、可辨认、有光照的地形**
（砂地、植被、树干、手部模型、hotbar 均正常）。

🔖 教训：**「诊断视图」与「主目标」是两个观测面，结论必须写明是哪一个**
——`h28`/`h29` 的黑屏结论全部来自诊断视图（那是对的，`albedo ≡ 0` 确实成立），
但**不能顺延成「用户看到的是黑屏」**。这一点本轮之前一直混着记。

## 六、⚠️ 仍未修（三个臂都在）

**天空仍然是黑的**（三张主目标截图一致）⇒ §10.30 ③ 拆出的**第三个独立缺陷未定位**，
本轮的结论**不覆盖**它。

## 七、给裁决的建议（🔴 仍是待用户拍板）

| | 落点 ①：关 `PARALLAX` | 落点 ②：关 `ADVANCED_MATERIALS` |
|---|---|---|
| 用户可见画面 | 与 ② **同值** | 与 ① **同值** |
| gbuffer 槽数 | **8**（GAP-003 保住） | 🔴 **1**（多附件语义落空） |
| 失去的包特性 | 视差 | 视差 + 高级材质（AO/光滑度/自发光等） |
| 实现成本 | 零代码（选项覆盖机制已验证） | 同左 |

🔶 **建议**：**A 落在 `PARALLAX` 层**，理由是它在**视觉等价**的前提下**多保住 8 槽 gbuffer**、
且**少砍一个包特性**（X27 精神上更站得住）。
⚠️ 但「按能力门控」的具体形态（门控哪些特性、怎么判定能力缺失、是否可被用户覆盖）
仍需拍板 —— 本轮只提供了决策所需的实测输入，**没有自行决定**。

## 八、产物与哈希

| 文件 | sha256（前 20 位） |
|---|---|
| `evidence/h31-images/main-AMon-Poff-1.png` | `e894f034f4161e680b72` |
| `evidence/h31-images/main-AMon-Poff-2.png` | `a6e1c8588e7eb7f367e4` |
| `evidence/h31-images/main-AMoff-1.png` | `d25f4eea87fd5183eccb` |
| `evidence/h31-images/main-AMoff-2.png` | `40ff259277510674f474` |
| `evidence/h31-images/diag-AMoff-1.png` | `ea88b62c0b03f0dab1ee` |

复算命令：

```bash
python3 evidence/tools/terrain_stats.py evidence/h27-images/isoA-stubsON-2.png -- \
        evidence/h31-images/main-AMon-Poff-2.png -- evidence/h31-images/main-AMoff-2.png
```

## 九、测试与残留

- `./gradlew build` ⇒ **exit 0**；本轮**未改产品代码**（只改了一个 evidence 工具的 `--` 多组切分，
  那是实测踩到的真 bug：只按第一个 `--` 切分 ⇒ 三组时第二个 `--` 被当成文件名）。
- 游戏进程残留 = **0**。
- ⚠️ 隔离车道**有意保留**在「`ADVANCED_MATERIALS=true` + `PARALLAX=false` + 主目标」状态，
  这是目前**最好的可渲染配置**，下一轮从它出发。
