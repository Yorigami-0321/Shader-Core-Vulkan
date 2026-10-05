# h45 · 🔴🔴🔴 一次性新增 5 个功能 + 单变量 A/B ⇒ GAP-008 收窄到「`texture(texture_0, texCoord)` 返回 0」

> 取证方式：新增 5 个功能后，经 **MCP 驱动**在本机 **Vulkan 后端**（lavapipe）跑三臂 A/B。
> 判定：🟢 **albedo ≡ 0 的成因被分离到乘法链的左侧**，且**推翻了 h13/h29 的收窄**与 **h31 的结论**。
> ⚠️ 本轮同时**推翻了我自己在 h44 提交里写下的一个事实**（见 §五）。

---

## 〇、一句话结论

在 `ADVANCED_MATERIALS=true; PARALLAX=false`（**视差分支整段被预处理消掉、从未执行**）下，
把 albedo 乘法链**左侧（纹理采样结果）**强制成非零常量 ⇒ albedo 从**逐像素恰好 0** 变成
`mean_luma 15.2644 / nonBlack 99.009%`；而只强制**右侧（乘子）**为 `vec4(1,1,1,1)` ⇒ albedo **仍是 0**。

⇒ **乘子不是原因；`texture(texture_0, texCoord)` 的返回值本身是 0。**
⇒ 而方块图集**本身不黑**（`mean_luma 87.5068`、`nonBlack 74.214%`、`maxR 255`）
⇒ 成因落在**采样坐标**或 **LOD/导数**，不在图集内容、也不在 `color` 供值。

---

## 一、本轮新增的 5 个功能

| # | 功能 | 代码 | 解决的问题 |
|---|---|---|---|
| ① | **采样因子探针**（左/右两侧独立开关） | `glsl/translate/SampleFactorProbeAdapter.java` + 转译接线 | albedo 是**乘积**，两侧都可能为 0；旧手段分不开 |
| ② | **取证「取证件」** | `pipeline/model/PackOptionEvidence.java` + `PackOptionStore#entries()` | 取证者以为改了覆盖、实际没改 ⇒ A/B **静默空转** |
| ③ | **方块图集纹理回读** | `bridge/TargetReadback` + `mrt.pixelProbeAtlas` | `texture_0` 真值的**运行期**数字从未取过（h13 只做静态 mip 链核查） |
| ④ | **分区采样**（天空带/地形带/整幅） | `pipeline/model/PixelStats`（`Area` / `areas()`） | 单一矩形回答不了「黑的是天空还是地形」（h17 踩过） |
| ⑤ | **QD-08 结构性清单** | `test/GenerationTimeSwitchInventoryTest.java` | 「新增生成期配置项」与「必须同步进记忆键」从未被绑在一起 |

🔖 ⑤ 首次运行就**枚举出两个真的漏项**（`ENABLED` / `MRT_PACK_TERRAIN_SHADER` 在生成窗口内被读、
却不在记忆键里）⇒ 已补进 `currentTerrainMemoKey`。这是该族（QD-02 / h33 / h43 / h44 各一例）
第一次被**机制**而不是靠人记得来堵。

---

## 二、🔴🔴 本轮实测推翻的两条既有结论

### 2.1 推翻 h13 / h29 的收窄：「albedo 为 0 在视差分支内」

h13/h29 的结论建立在「`PARALLAX` 开着」的观测上。但本轮三臂全部是
**`PARALLAX=false`** —— 预处理把 `#ifdef PARALLAX` 整段消掉、**从未执行** ——
而 albedo **依然逐像素恰好 0**。

⇒ 视差分支**不是**成因。反而更强的证据来自 h44 的残留臂（`PARALLAX=false`）：
那一臂 albedo ≡ 0，而 A-base 臂（`PARALLAX=true`，包的真实默认）albedo 是
`mean_luma 1.4068 / maxR 59`（暗但**非零**）。
⇒ **关掉视差让 albedo 从「暗但非零」变成「恰好 0」** —— 视差分支反而是**部分救回**它的那一段。

### 2.2 推翻 h31：「关掉 `PARALLAX` 即可消除黑屏」

同上：`PARALLAX=false` 使 albedo 由 `1.4068` 掉到 `0.0000`。
🔴 该结论本就是 **OpenGL 产物**（h42 已指出），本轮在 Vulkan 上给出**反向**实测。

---

## 三、🔴🔖🔴 取证方法上的两个自身缺陷（都被本轮的工具链现形）

### 3.1 观测面没钉死 ⇒ 跨臂数字不可比（一度得出「colortex0 = 0 vs 12.42」的假 diff）

前两次臂之间只差一个包选项，读数却差了一个数量级。真正差异是**相机朝向**：
`quickPlay` 恢复上次退出时的视角，而探针读的是**固定中心矩形**，朝天/朝地完全不同。
⇒ 已把 `yaw=35 / pitch=-8 / dayTime=6000 / weather=clear` **写进驱动脚本**并在每臂回读自证
（`tools/vulkan-local/h45_arm.sh`）。

### 3.2 预热帧假象：同配置两轮读出 0.0000 与 12.42，而 `main` 逐位相同

钉死观测面后仍出现该现象 ⇒ 差异只可能来自 colortex 那一路「**还没画**」。
实测：`colortex0#5` 一轮 `0.0000 allZero=true`、一轮 `12.4204`，
同两轮 `main` **逐位相同**（`13.1864`）。
🔴 若拿预热帧当基线，实验臂就会得到「基线全黑、探针臂有内容」的**假结论** —— 比不测更坏。
⇒ 新增**预热门闩**：地形 pass 画够 600 帧之前，探针**只报数字、不出对照结论**并自报。

---

## 四、Vulkan 实测（三臂，逐字日志）

后端自证（三臂逐字相同）：

```
vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.1, 256 bits)
```

观测面自证（每臂回读）：`yaw=35 / pitch=-8 / dayTime≈6026 / raining=false`。
档位（每臂显式写死并读回）：`mrt.terrain=true`、`terrainAfterLevel=true`、
**`terrainToMain=false`**、`packTerrainShader=true`、`shadowStubs=true`、`capabilityGate=false`。

> 🔖 为什么用 `terrainToMain=false`：那一档会把主目标清成黑再画包的 albedo，
> 而 albedo ≡ 0 ⇒ **整个世界黑**（手/物品栏仍可见）。而 `terrainToMain=false` 下
> **`colortex0` 就是包的 albedo** —— 对本次要判的量而言是更合适的观测面。

包选项覆盖（三臂相同）：`ADVANCED_MATERIALS=true;PARALLAX=false`
（契约实测：`outputs=8 declaredOutputSlots=[0,3,6,7] unwrittenAttachments=[1,2,4,5]`）。

探针自报（证明开关确实生效、且只动了一行）：

```
采样因子探针命中：左侧(采样) 0 处、右侧(乘子) 1 处。
采样因子探针命中: 第 1338 行: vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);
```

### 三臂读数（`colortex0` = 包的 albedo，最后稳定轮）

| 臂 | 单变量 | `mean_luma` | `nonBlack` | `maxR` | `allZero` |
|---|---|---|---|---|---|
| **B0** | 两探针都关 | **0.0000** | **0.000%** | 0 | **true** |
| **C-sample** | 左侧 = `vec4(1.0,0.5,0.25,1.0)` | **15.2644** | **99.009%** | 65 | false |
| **D-mult** | 右侧 = `vec4(1,1,1,1)` | **0.0000** | **0.000%** | 0 | **true** |

⇒ **左侧是 0，右侧不是 0。**

### 输入侧排除：方块图集本身不黑

```
blockAtlas#1 region=409x1229 mean_luma=117.4409 nonBlack=94.326% maxR=255 allZero=false
blockAtlas#1 area=FULL  region=2048x2048 mean_luma=87.5068 nonBlack=74.214% maxR=255
```

🔖 采样区里约 **26% 的黑像素**是图集自身的透明填充 —— 这正是下一轮的候选：
若 `texCoord` 落进那片填充，`texture()` 就会返回 0，而图集整体指标看不出问题。

---

## 五、🔴🔖 推翻**我自己**在 h44 提交里写下的事实

h44 我写过：「BSL 的 `ADVANCED_MATERIALS` **默认是 true**，`MrtPlan` 的旧注释是错的」，
并据此**改掉了 `MrtPlan` 的注释**。

🔴 **那是错的，而且是我自己用残留状态反推出来的默认值。**

真相：扫包日志逐字写着 `option name=ADVANCED_MATERIALS type=BOOLEAN **default=false**`。
h43/h44 那些「8 附件」的臂，是 `config/vkdisp-pack-options.properties` 里
**残留**的 `ADVANCED_MATERIALS=true; PARALLAX=false` 造成的 ——
那一臂**既不是默认档，也不是单变量臂**。

⇒ `MrtPlan` 原来的注释（「默认只写 colortex0」）**是对的**，本轮已恢复并补上完整实测依据。
🔖 教训比结论更重要：**用残留状态反推默认值，与「漏带覆盖导致静默空转」是同一个坑的两面**。
⇒ 本轮新增的 ②（取证件）正是从机制上堵这条路：每条像素证据行都带
`overrides[store=… config=… effective=…]`，两个来源都出现、为空时显式打 `<none>`。

---

## 六、本轮自身引入、并当场修掉的三个缺陷

| 缺陷 | 症状 | 修法 |
|---|---|---|
| 探针的 `diagnostics` **没并进**返回列表 | 改写生效、但「命中 N 处」永不出现 ⇒ 无法区分「开关没生效」与「结论不成立」 | 补进列表 + 新增 `TranslateStageDiagnosticTest`（结构性守卫：每个 `XxxProbeAdapter.apply` 的 `diagnostics()` 必须被读取，且 `.text()` 必须被用） |
| 探针用**静态开关**接在 `OfGlslTranslator` 里 | 该方法**不知道自己在翻哪个程序** ⇒ 泄漏到 composite，实测改写了 `float cloudViewLength = texture(gaux1, screenPos.xy).r * (far * 2.0);` ⇒ **该臂数字作废** | 移出流水线，改由 `VkDispVirtualPack#generateTerrainSource` 直接对地形片元源调用（作用域天然正确，且无跨线程静态开关） |
| 乘子探针命中**任何**乘法赋值 | BSL 地形片元上命中 **124 处**，把 `float f = a * b;` 改成 `= a * vec4(1,1,1,1)` ⇒ **类型错误** ⇒ `ShaderCompileException`、地形契约掉回原版 | 两个开关都只认「左值是采样调用」的行（命中数自报把它现形：预期 1、实测 124） |

🔖 三者都是**「工具自己坏掉且不报错」**的形态 —— 与本项目反复消灭的是同一类。

---

## 七、GAP-008 现在的状态

**已排除**（本轮实测）：落点/接线、整条 draw 未出片元、附件绑定错、几何/深度全丢、
清屏色/诊断色混入、**方块图集内容为黑**、**乘法链右侧（`color` 乘子）为 0**、
**视差分支为成因**（关掉它反而更黑）。

**已定位**：albedo ≡ 0 的成因在 **`texture(texture_0, texCoord)` 的返回值**。

**下一步的候选（本轮未测）**：
① `texCoord` 落进图集透明填充（图集约 26% 像素为黑）⇒ 可用
`mrt.terrainParallaxSkipProbe` 之外的「新坐标探针」或直接取 `texCoord` 数值验证；
② **LOD / 导数**：`texture()` 的隐式导数在这条链上是否给出异常 LOD（对照：`PARALLAX=true` 时
同一采样器走 `textureGrad` 却**非零** —— 这本身是很强的线索，说明两条采样路径行为不同）。

---

## 八、纪律核对

- **§3.2 六项**：Vulkan 后端三臂逐字自证；⚠️ 本机**无 Vulkan validation layer**
  ⇒ 本文**不含**任何「无 validation error」式断言；**未做任何性能结论**（按指令）；
  取证环境修复（`libvulkan_lvp.so`）在 `tools/`（gitignore），不随仓库分发。
- **单测**：915 条全绿（本轮 +29）；`./gradlew build` 退出码 0。
- **残留**：取证结束后 `game_procs.sh kill` + `count` 清零；隔离车道配置已还原。

---

## 九、代码位置

- `glsl/translate/SampleFactorProbeAdapter.java`（新，含 **KEEP_OUT** 说明）：乘法链两侧分离探针
- `pipeline/model/PackOptionEvidence.java`（新）：取证取证件（两个来源 + 合并优先级）
- `pipeline/model/PixelStats.java`：`Area` / `areas()` 分区采样
- `bridge/TargetReadback.java`：图集源、分区取数、取证件随行、**预热门闩**
- `bridge/TerrainPipelineApi.java` / `VkDispVirtualPack.java`：探针接线与作用域
- `glsl/translate/OfGlslTranslator.java`：移除泄漏的探针段 + 诊断并入纪律
- 测试：`SampleFactorProbeAdapterTest`、`TranslateStageDiagnosticTest`（新）、
  `PackOptionEvidenceTest`、`PixelStatsAreasTest`、`GenerationTimeSwitchInventoryTest`（新）
- 工具：`tools/vulkan-local/h45_arm.sh`（按臂重启 + 钉死观测面 + 读回自证）