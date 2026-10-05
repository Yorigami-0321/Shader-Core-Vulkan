# h38 · 🔴 新缺口 GAP-015：原版**建不出比较采样器** ⇒ `sampler2DShadow` 绑的不是它要的东西

> **日期**：2026-10-05
> **性质**：源码级查证 + 登记 + 让缺口**可见**。按用户指令只验功能、不做性能。
> **verdict = GAP-012 的同类问题第二次出现，这次在「采样器」这一侧。**
> 已核实到**字节码/签名级**，已在 Vulkan 上实测确认说明会打，且**只打一次**。

---

## 一、一页版结论

| 项 | 内容 |
|---|---|
| **新缺口** | **GAP-015**：原版**没有「比较采样器」这个能力** ⇒ `shadowtex0/1`（声明为 `sampler2DShadow`）只能绑**非比较**采样器 ⇒ **描述符类型不匹配 = Vulkan UB** |
| **核实方式** | 从 `minecraft-patched-26.3.0.41-beta.jar` 逐类 `javap` 反汇编，**未猜** |
| **运行期验证** | Vulkan 后端实测：`[GAP-015]` WARN **恰好 1 条**（一次性、无刷屏），`SHADOW_DEPTH_2D=2`，vkdisp ERROR **0** |
| **单测** | 799 → **803**（+4 条守卫） |

---

## 二、根因：原版根本没有比较采样器这个工厂

```
$ javap -p GpuDevice.class | grep Sampler
  public abstract GpuSampler createSampler(AddressMode, AddressMode,
                                           FilterMode, FilterMode,
                                           int, java.util.OptionalDouble);
```

⇒ **只有一个工厂，签名里没有 `CompareOp`。** 全类再无第二个采样器入口。

### 2.1 🔖 一个差点踩进去的坑（这才是本轮最该记的）

第一眼看到 `SamplerCache` 里有：

```
  public GpuSampler getClampToEdge(FilterMode);
  public GpuSampler getClampToEdge(FilterMode, boolean);   ← 「这个 boolean 会不会是 compare？」
```

**差点就按「compare 开关」写进文档了。** 查 `LocalVariableTable` 才看清：

```
  #84 = Utf8    useMipmaps
  #85 = Utf8    Z
  #105 = Utf8   mipmaps
```

⇒ 那个 `boolean` 是 **`useMipmaps`**，**不是 `compare`**。
🔖 若没查这一步，本文会写出一个**看起来完全合理、但错的**修法指引
（「换个带 compare 的重载就行」）⇒ 下一个人会照着白找一遍。
**这就是 `07` X9「不猜」在实操中的具体价值。**

---

## 三、为什么这在 Vulkan 上是 UB

- 包把 `shadowtex0` / `shadowtex1` 声明为 **`sampler2DShadow`**（全包扫出 `sampler2DShadow` 3 个名字）。
- `sampler2DShadow` 在 Vulkan 里要求描述符带**比较**采样器（`VkCompareOp != VK_COMPARE_OP_NONE`）。
- 我们绑的是 `atlasSampler` —— `createSampler(CLAMP, CLAMP, LINEAR, LINEAR, 1, empty)`，**非比较**。
- ⇒ **描述符类型不匹配 = 未定义行为**（与 GAP-012「3D 视图喂 sampler3D」完全同族）。
- 且本机**无 validation layer**（`h37` §八：全盘搜到的三个 `VkLayer_khronos_validation` **全是 Windows `.dll`**）
  ⇒ **一条错都不会报**。

实测确认这两条确实在绑（`h38` 运行期日志原文）：

```
vkdisp: [GAP-003] pack terrain uniforms bound: blockMembers=42 samplers=7
  (by dimension: ATLAS_2D=1 PLACEHOLDER_2D=1 NEUTRAL_MATERIAL_2D=2
   SHADOW_DEPTH_2D=2 SHADOW_COLOR_2D=1; …)
```

---

## 四、🔴 取舍：为什么**照样绑**、而不是学 GAP-012/014 那样「不绑 + 报错」

GAP-012（cube / 不认识的类型）与 GAP-014（3D 纹理）都选了「**不绑 + ERROR**」。
本条**故意不同**，理由是可验证的：

| | GAP-012 / GAP-014 的对象 | 本条的对象 `shadowtex0/1` |
|---|---|---|
| 在本包**地形程序**里出现几次 | **0 次**（`VOLUME_3D` 实测为 0；cube 0） | **2 次**（`SHADOW_DEPTH_2D=2`） |
| 不绑的后果 | 不影响渲染 | draw 抛 `Missing uniform` ⇒ **地形整条不渲染** |

⇒ 在支柱①（**兼容优先**）的口径下，「画面里阴影项不可信」**优于**「地形完全不画」。
🔖 代价是**这条 UB 继续存在** —— 它是**原版的结构性限制**（本项目做不出正确绑定），
不是本项目的实现失误。**唯一诚实的选择是让它一直可见。**

---

## 五、🔖 一条**发现者被自己绊住**的记录

`SamplerDimensionPlan` 对 `shadowtex*` 是**按名字**分类的，注释原文：

> 「它们全都是 2D ⇒ 先按名字定，再对没命中的按维度兜底。」

⇒ 它**没有**走 GAP-012 修复所立的那条「维度来自**声明的类型**」的规则。
这与 GAP-012 的修复初衷**不一致**（GAP-012 的核心主张就是「别按名字猜」）。

- 现在**没改**：改了会让「声明成 `sampler2D` 的 `shadowtex&`」拿到 RGBA 桩而不是深度桩
  ⇒ 那才是把语义改错。
- 已登记为 GAP-015 的「⚠️ 顺带发现」待改项，属独立一轮。

🔖 记这一条是因为：**「我们修了 X」这句话，只有在被修的那条路径上成立**。
GAP-012 修了未命中的 fallback，却留着命中分支按名字定 —— 属于**修得不彻底但没写出来**，
本轮才暴露。

---

## 六、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| 做出**类型正确**的绑定 | ❌ **做不到**（原版没有比较采样器工厂）⇒ 这是 GAP-015 的定义 |
| 让 `shadowtex*` 改为**按声明类型**分类 | ❌ 未做（已登记为待改项，见 §五） |
| **性能结论** | ❌ 按用户指令不做；且设备是 lavapipe CPU 软件 Vulkan，帧率本就无参考价值 |
| **validation layer** | ❌ 仍然没有 ⇒ 按 X35 **不得**说「无 validation error」 |
| GAP-011 闪烁 | ❌ 仍未定位（`h37` 刚排除一个候选） |

---

## 七、复算 / 自检命令

```bash
# 原版没有比较采样器工厂（本条缺口的事实依据）
javap -p /tmp/rpg/com/mojang/renderpearl/api/device/GpuDevice.class | grep -i sampler

# SamplerCache 的 boolean 是 useMipmaps 不是 compare
javap -v /tmp/rpg/com/mojang/blaze3d/systems/SamplerCache.class | grep -A2 useMipmaps

# 包确实声明成 sampler2DShadow
python3 /tmp/scan-samplers.py | grep sampler2DShadow

# 运行期：说明只打一次 + 两条阴影绑定确实存在
grep -c 'GAP-015' run/logs/latest.log        # 1
grep -oE 'by dimension: [^;]*' run/logs/latest.log | head -1

# 守卫
./gradlew test --tests 'dev.vkdisp.bridge.ComparisonSamplerGapTest'
```

---

## 八、自检

- [x] 缺口事实来自**反汇编**（`GpuDevice` 签名 + `SamplerCache` 的 `LocalVariableTable`），未猜
- [x] 差点误判「那个 boolean 是 compare」⇒ 查参数名后改正，并把这次差点踩坑写进文档
- [x] 「照样绑」的取舍给了**可验证的理由**（对象出现次数 0 vs 2），不是凭感觉
- [x] 运行期在 **Vulkan** 上验证：WARN **恰好 1 条**、`SHADOW_DEPTH_2D=2`、ERROR **0**
- [x] 配了 4 条守卫，其中一条**明确禁止**守卫反过来要求「不绑」
- [x] 顺带发现「`shadowtex*` 仍按名字分类」并如实登记，没有默默留着
- [x] 如实列出本轮**没做的事**（§六）
