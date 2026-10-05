# h40 · 数组纹理采样器：拿 2D 图冒充 = GAP-012/014 同族静默 UB ⇒ 改成不绑

> **日期**：2026-10-05
> **性质**：一处**真代码修复**（`.java` 有改动，本会话第 2 次代码变更）。
> **verdict = 堵掉一个从未被登记的静默 UB 类别；
> 而且判据是「在本包地形程序里出现几次」，不是「类型像不像 GAP-015」。**
> Vulkan 实测：对 BSL **零回归**（`by dimension` 逐字未变），810 条单测全绿（+3）。

---

## 一、一页版结论

| 项 | 结果 |
|---|---|
| **修的问题** | `sampler2DArray` / `sampler2DArrayShadow` 原来落进 2D 分支 ⇒ **喂一张普通 2D 图** |
| 为什么是 UB | 数组采样器在 Vulkan 里要求 **Arrayed=1** 的图像视图；喂 2D 图 = **描述符类型不匹配**（与 GAP-012「拿 2D 冒充 3D」、GAP-014「3D 纹理建不出来」**完全同族**），且本机无 validation layer ⇒ **一条错都不报** |
| **改成的处置** | **UNSUPPORTED（不绑）+ 可见告警**，与 cube / sampler3D 同等待遇 |
| **为什么这次能「不绑」而 GAP-015 不能** | 判据是**在本包地形程序里出现几次**：<br>`sampler2DArray` 在 BSL **274 个着色器源文件里 0 次** ⇒ 对本包零代价<br>`shadowtex0/1` 每种配置都在（`SHADOW_DEPTH_2D=2`）⇒ 不绑会让地形整条不渲染 |
| Vulkan 实测 | `by dimension` **与改前逐字相同**、ERROR **0**、`GAP-015` 仍 1 条、**数组告警 0 条**（印证 0 次） |
| 单测 | 807 → **810**（+3 条，并删掉 h39 那条锁住旧行为的用例） |

---

## 二、先核实事实，不猜（这一步差点翻车）

第一次去 `grep` 包里的 `sampler2DArray`，结果是 **0 个**。
🔖 但 h32 明确记过 BSL 有 **3 个 `sampler3D`** ⇒ **0 与 3 直接矛盾**，
说明是**我的工具坏了**，不是包的问题。

查下去发现：**这机器上没有 `unzip`**，`/tmp/bsl` 解出来是 **0 个文件**，
于是 `grep -r` 在空目录上跑 ⇒ 「什么都没找到」。

改用 Python `zipfile` 直接读，得到：

```
entries = 309
shader sources read = 274

--- sampler kinds in all shader sources ---
   143  sampler2D
    26  sampler3D
     5  sampler2DShadow

--- files declaring sampler2DArray* ---
(none)
```

⇒ **BSL v10.1.8 里 `sampler2DArray` 确实出现 0 次。**
🔖 记这一条是因为：**「grep 没找到」和「确实不存在」是两件事**。
h32 那条 3 个 `sampler3D` 是**别人**留下的事实，正是它让我没把 0 当成结论。

---

## 三、🔖 本轮真正的收获：把「判据」从直觉写成了明文

同一轮里我处理了两个**同族**的「原版建不出来」：

| | `shadowtex0/1`（GAP-015） | `sampler2DArray`（本轮） |
|---|---|---|
| 原版能力 | 建不出**比较**采样器 | 建不出**数组**纹理（GAP-014 已证） |
| 类型像不像 UB | 像 | 像 |
| 在 BSL **地形程序**里出现次数 | **2**（每种配置都在） | **0** |
| 处置 | **保留绑定 + 一次性 WARN 明示** | **不绑 + 告警** |

🔖 **两者处置相反，但理由是同一个数：出现几次。**

在 h38 我为 GAP-015 写的理由是「不绑会让地形整条不渲染」
—— 那是**针对这一个对象**的论证。本轮发现它其实是一条**可复用的判据**：

> **原版建不出来的采样器，处置不是由「类型像不像 UB」决定，
> 而是由「它在本包地形程序里出现几次」决定。**
> 出现 0 次 ⇒ 不绑（响亮失败，零代价）。
> 每种配置都在 ⇒ 不绑会毁掉渲染 ⇒ 保留绑定 + **明示**后果。

已把它写进代码注释、写进 `13-GAP-REGISTRY`，并用一条单测**锁死**：
`theCriterionIsOccurrenceCountNotTypeSimilarity` 同一个 `Plan` 里同时断言
「shadowtex0 ⇒ 保留」「arr ⇒ 不绑」，防止将来有人「为了统一」把其中一个改反。

---

## 四、改动与不变的部分

**改**：`sampler2DArray` / `sampler2DArrayShadow` 从 2D 占位分支里**拆出来**，
单独判为 `UNSUPPORTED` 并留可见告警。

**不变**（要写清，避免读者以为影响面比实际大）：

- 普通 `sampler2D` / `sampler2DShadow` 走占位分支的行为**完全不变**
  （已加测试 `plain2DPlaceholderUnchanged`）。
- `sampler3D` 仍声明 `VOLUME_3D` 决策 ——
  🔖 **「决策层认为该绑什么」与「原版建不建得出来」是两件事，不要混为一谈**。
  决策层保持正确（VOLUME_3D），建不出来由 GAP-014 侧记录降级。
  （已加测试 `arrayCubeAnd3DShareTheSameTreatment` 把这个区分固定下来。）

---

## 五、✅ Vulkan 实测：零回归，且印证了静态分析

```
backend   : 1     (backend=Vulkan)
ERROR     : 0
GAP-015   : 1     (未被本轮改动破坏)
array warn: 0     ← 数组告警一次没触发，运行时印证了「BSL 里 0 次」
by dimension: ATLAS_2D=1 PLACEHOLDER_2D=1 NEUTRAL_MATERIAL_2D=2
              SHADOW_DEPTH_2D=2 SHADOW_COLOR_2D=1   ← 与改前**逐字相同**
terrain MRT pass frames=600
```

🔖 这条对照说明一件事：**静态扫描（0 次）与运行期行为（告警 0 条）互相印证**，
不是只有一边有数据。

---

## 六、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| 让数组纹理**真的能绑** | ❌ **做不到**（原版建不出数组图像，GAP-014 已证实）⇒ 这正是「不绑」的原因 |
| 给 `sampler2DArrayShadow` 单独处理比较采样器问题 | ❌ 未做。它同时踩到数组纹理**和** GAP-015 两件事；本轮只挡了数组那件 |
| **性能结论** | ❌ 按用户指令不做 |
| **validation layer** | ❌ 仍然没有 ⇒ 按 X35 **不得**说「无 validation error」 |
| GAP-011 闪烁 | ❌ 仍未复现（h39 已把条件扩大到移动机位） |

---

## 七、复算 / 自检命令

```bash
# 关键事实：BSL 里 sampler2DArray 出现 0 次（注意：不要用 unzip，这机器没装）
python3 /tmp/scanarr.py

# 新增 3 条 + 保留的既有用例
./gradlew test --tests 'dev.vkdisp.pipeline.model.SamplerDimensionPlanTest'

# 运行期：数组告警 0 条、by dimension 与改前逐字相同
grep -ciE '数组纹理' run/logs/latest.log          # 0
grep -oE 'by dimension: [^;]*' run/logs/latest.log | head -1
```

---

## 八、自检

- [x] `grep` 结果 0 与 h32 记录的 3 个 `sampler3D` 矛盾 ⇒ 先查工具（发现无 `unzip`），**没有把工具故障当结论**
- [x] 改动前先拿到「本包出现 0 次」这个**决定性事实**，并写清它为什么决定处置
- [x] 把 GAP-015 的个案论证**上升为可复用判据**（出现几次），并明确两者处置相反
- [x] 判据写进代码注释 + 登记 + **一条单测同时锁住两边**（防止将来被「统一」掉
- [x] 写清**不变**的部分（普通 2D 占位、sampler3D 仍走 VOLUME_3D 决策）
- [x] 运行期「告警 0 条」与静态「0 次」互相印证，不是只靠一边
- [x] 全程 Vulkan；未做性能结论；验证后 `残留游戏进程数=0`
