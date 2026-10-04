# h32 · 连续三轮功能开发：GAP-009 能力门控落地 + sampler3D 维度 UB 修复 + 诊断色泄漏修复

> **日期**：2026-10-04
> **性质**：三轮连续功能开发。**纯逻辑部分全部有单测；`.java` 有改动 ⇒ 按 `07` 规需要 runClient 取证，
> 本轮由用户协助跑**（见 §六「交给用户验证的清单」）。
> **verdict = 主源码编译通过 + 单测全绿；三项均为「修掉真实缺陷」而非「加新功能」。**

---

## 一、一页版结论

| 轮 | 做了什么 | 修的是什么缺陷 | 单测 |
|---|---|---|---|
| 一 | **GAP-009 方案 A 落地**：按能力门控（`config/PackCapabilityGate` + `pack/PackLangFile` + `pack/PackCapabilityGateSwitch` + 接线 `pack/PackTerrainSource`） | 依赖缺失素材的特性被强行启用 ⇒ 地形 albedo 被压成**恰好 0**（纯黑剪影，主目标 luma `0.0000`） | 16 条 |
| 二 | **sampler 维度正确绑定**（`pipeline/model/SamplerDimensionPlan` + `bridge/VolumeStubs` + 改 `bridge/TerrainPipelineApi`） | 包声明的 4 个 `sampler3D` 被喂 **2D** 图集视图 = 描述符类型不匹配 = **Vulkan UB**，且本机无 validation layer **不报错** | 15 条 |
| 三 | **诊断清屏色不再泄漏进产品画面**（`pipeline/model/TerrainSlotClear` + 改 `bridge/MrtTerrainPass` + 新增 `mrt.slotDiagnosticClear`） | 逐槽诊断色（槽 0 纯绿）**无条件**应用 ⇒ 天空那片保持纯绿 ⇒ **绿天空进最终画面** | 10 条 |

🔖 **三轮的共同点**：都是「**已定位但未修**」的缺陷，且都是**静默型**（不报错、不崩溃、画面慢慢变坏）。
这与本项目一贯消灭的失败形态一致 —— 宁可现在修，也不留着当已知问题。

---

## 二、轮一：GAP-009 方案 A —— 能力门控

### 2.1 落地口径（与 `13-GAP-REGISTRY` GAP-009 的裁决一致）

| 项 | 口径 |
|---|---|
| **改哪一层** | **选项层**：按能力门控掉依赖缺失素材的特性（**不**动资源/绑定层） |
| **判据** | 「**包自己声明**的依赖」+「**我们确实缺**这个能力」两者同时成立 |
| **硬编码选项名？** | ❌ **不硬编码**。判据是包 lang 里显示名末尾的 `*`（BSL 实测 19 条） |
| **逐包判定** | ✅ Complementary 有视差但**零星号**（复用原版 atlas、零外部依赖）⇒ 不门控 |
| **写用户文件？** | ❌ **不写**。只改内存里的 `PackOptions` 值（裁决依据：Iris 自己从不因能力缺失改写用户配置） |
| **默认开关** | **关**（`pack.capabilityGate=false`）。改变包语义的动作由用户显式开启 |
| **作用域** | 只在「包地形片元被接到派生 MRT 地形管线」这条路径上生效 |

### 2.2 🔴 为什么必须读 lang（这是本轮最关键的一步）

BSL v10.1.8 声明「本选项依赖资源包提供的材质贴图」的**唯一**机制是
**选项显示名末尾的 `*`**，而它写在 `shaders/lang/en_US.lang` 里：

```
option.PARALLAX=Parallax Occlusion Mapping*
option.ADVANCED_MATERIALS.comment=… requires a resource pack which contains specular and/or normal maps.
```

⇒ **不读 lang 就只能硬编码 `PARALLAX`**，而那会砍掉 Complementary 的可用视差（违反 X27）。
⇒ 本轮新增 `pack/properties/PackLangFile` 专门读它。

### 2.3 实测数据（本轮亲自扫包所得）

```
BSL_v10.1.8        : shaders/lang/en_US.lang，19 条以 * 结尾（18 个 option.* + 1 个 value.*）
Complementary_r5.9.3: shaders/lang/en_US.lang， 0 条以 * 结尾   ← 逐包判定的实测依据
```

🔖 **注意 `value.EMISSIVE.1=AdvMat Only*` 这一条**：它标的是「**这个枚举值**依赖高级材质」，
不是「这个**选项**依赖」⇒ `PackLangFile` **刻意不收** `value.*`
（收进来会让门控去关一个不存在的选项 `EMISSIVE.1`，且真选项 `EMISSIVE` 反而漏网）。

### 2.4 门控的三条「不」（都是踩过的坑）

| 不 | 理由 |
|---|---|
| **不硬编码选项名** | Complementary 同样有视差却零外部依赖 ⇒ 硬编码会砍可用特性（X27） |
| **不关非布尔选项** | 缺能力只让**布尔开关**产生坏画面；数值选项的值本身无害，关它只是白白砍掉用户调过的数值 |
| **不把「本来就是关的」记成「我们关了它」** | 否则日志会说谎，下一次取证会误判归因 |

### 2.5 🔴 接线点的一处自我纠正

第一版把门控接在 `PackCompositeSource.generate`（composite/deferred/final 三源）里，**接错了**：

- GAP-009 的**全部**实测证据（h29 定位视差分支、h31 luma `0.0000 → 96.1485`）都取自**地形**；
- `PackCompositeSource` 产出的是三个**全屏**步，与地形片元无关；
- 在那里门控会在「地形片元没接线」时**白白砍掉包特性** ⇒ 违反 X27。

⇒ 已改接到 **`PackTerrainSource`**（地形片元专属的那条独立链），并在
`PackCompositeSource` 的调用点留了注释说明**为什么不在那里**（防止后来者又接错）。

### 2.6 一个刻意的设计退化（及其有效期）

星号只说「依赖某个能力」，不说依赖哪个。映射到具体能力时：
**只有 `MISSING_CAPABILITIES` 恰好一条时映射才是确定的**（候选集唯一 ⇒ 不是猜）；
≥2 条时 `declaredDependencies` **不映射**并产生 `CAPABILITY_GATE_AMBIGUOUS_STAR` 警告
（宁可门控空转 + 可见告警，也不按猜测关用户的特性）。

---

## 三、轮二：sampler 维度正确绑定（修 Vulkan UB）

### 3.1 缺陷（本轮亲自扫包统计所得）

```
BSL_v10.1.8 全包 sampler 声明统计：
  sampler2D        34 个名字
  sampler2DShadow   3 个（shadowtex / shadowtex0 / shadowtex1）
  sampler3D         4 个（lighttex / lighttex0 / lighttex1 / voxeltex）   ← 🔴
```

旧实现对未识别的名字一律 `default -> atlas` ⇒ 这 **4 个 `sampler3D` 拿到了 2D 图集视图**。

### 3.2 为什么这是必须修的 UB

`sampler3D` 在 Vulkan 里要求描述符类型是 **3D 图像视图**；喂 2D 视图是
**描述符类型不匹配 = 未定义行为**：驱动可以丢 draw / 给垃圾 / 无事发生，
**且本机没有 validation layer ⇒ 一层都不会报错**
（与 `h27` 的读写附件别名 UB 同一类：静默、无告警、只能靠推理发现）。

### 3.3 修法与取舍

| 方案 | 取舍 |
|---|---|
| ✅ **从声明的 sampler 类型读维度**，给 `sampler3D` 绑**类型匹配的 3D 桩** | 采用 |
| 继续喂 2D 图集占位 | 否决 = 就是这个 bug |
| 把这些 sampler 从布局里删掉 | 否决：布局多于 SPIR-V 无害，但会让「包声明了它」不可见 |
| 按名字硬编码 `lighttex0/1 -> 3D` | 否决：那是把「这个包恰好这么叫」写进产品逻辑，换个包就错（X39） |
| 造一张有内容的假体积光照图 | 否决：**编一个假的输入**，本项目最讨厌的失败形态（X9） |

### 3.4 🔖 3D 桩的缺省值有语义依据

`lighttex0` 是 OF 的**体积**光照贴图，本引擎**没有**它
（GAP-009 裁决：不打包第三方光照/材质资产）⇒ 语义等价于「**无光照贡献**」= **全 0 纹理**。
⚠️ 明确不承诺：包的体积光 / 体积 AO 效果在本引擎上**不成立** ——
但这比「喂 2D 图集」诚实得多（后者可能碰巧「看起来有东西」，换驱动就变）。

### 3.5 一处 API 事实核实（避免猜）

原版 `CommandEncoder` 的 `clear*` 系列**返回 `void`**（sources jar 逐行核实）⇒ 必须分两句写；
而 `writeToTexture(ByteBuffer, …)` 的参数含 `depthOrLayer` ⇒ **只写单层**，
3D 纹理要逐层调 4 次。为一个「恒为 0」的内容付 4 次上传不值得，
且 clear 路径没有「忘填某一层」这种静默失效的可能 ⇒ **用 `clearColorTexture` 一次清掉**。

### 3.6 一处「响亮失败」的新行为

cube 采样器与任何**不认识**的 sampler 类型 ⇒ **不绑**，并 ERROR。
理由：宁可让 draw 抛 `Missing uniform`（响亮失败、可定位），
也不拿 2D 视图冒充（静默 UB）。
🔖 这是与旧 `default -> atlas` 的**根本区别**：旧实现在这里**总能**绑出一个「看起来能用」的视图。

---

## 四、轮三：诊断清屏色不再泄漏进产品画面

### 4.1 缺陷（`evidence/h27b` §六 已定位，本轮修）

旧实现**无条件**把槽 0 清成纯绿 `RGB(0,255,0)`：

```java
// 旧 MrtTerrainPass#diagnosticClear —— 无条件应用
case 0 -> new Vector4f(0.0F, 1.0F, 0.0F, 1.0F);
```

因果链（h27b 实测定位）：

```
我方 MRT pass 的 colortex0 每帧被清成纯绿
  → 我们的 pass **只画地形**，天空从没被画进 gbuffer ⇒ 天空那片保持纯绿
  → 包的 composite 采 colortex0 ⇒ **绿天空直接进最终画面**
```

### 4.2 🔖 为什么不只是「把绿改成黑」

旧实现的注释写明了诊断色的**正当用途**：

> `MrtPlan` 给槽 0 的**指纹恰好是 `0.0`（黑）**，
> 一旦「什么都没画」与「画了但很暗」同时发生，两者**在截图上无法区分**。
> （实测踩坑：黑屏既可能是回读坏，也可能是没画。）

直接改成黑 = **删掉一项可诊断性**，那是「看着更干净、实际丢了信息」的典型改动。

### 4.3 本轮的取舍：保留能力，限定作用域

| 模式 | 清屏色 | 何时 |
|---|---|---|
| **诊断模式** | 绿 / 蓝 / 品红（高对比，**保留**） | `mrt.enabled` 调试视图激活，**或** `mrt.slotDiagnosticClear=true`（A/B 开关，开启即 WARN） |
| **生产模式** | **零值** `(0,0,0,0)` | 其余全部情况 ⇒ 天空那片是**黑**而不是绿 |

🔖 **判据是「调试视图是否激活」而不是「`mrt.terrain` 是否开着」** ——
取证时两者经常同时开（h27b 就是），但用户看到的画面必须是生产语义。

🔖 **零值同样不含假信息**：真正的天空要由包的 gbuffer 程序去画，那属于 **M-04（未做）**。
黑 ≠ 正确，但黑**不骗人**；纯绿会让用户以为本项目画了绿天。

### 4.4 保留可诊断性的另一半：说明文案

生产模式下打一次 INFO，明说「天空黑是**预期行为，不是故障**」：

```
terrain slot clear = NEUTRAL (RGBA 0,0,0,0) for N slot(s) —— 我方 pass 只画地形，
天空那片区域不会被画进 gbuffer；因此它显示为**黑**是预期行为，不是故障
（此前是纯绿 = 诊断色泄漏进产品画面，见 evidence/h27b §六）。
```

🔖 不这么做，取证者会把「设计如此」误读成「又坏了」，然后去查一个不存在的问题。

---

## 五、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| **runClient 取证** | ❌ **未做**（用户协助）。三轮都是 `.java` 改动，按 `07` 规**必须**跑 |
| **GAP-009 的 B 方案（LabPBR atlas loader）** | ❌ 未做（本轮只做 A 止血） |
| **黑天空本身** | ❌ **仍未修**，且**不是**本轮能修的 —— 正确天空要由包的 gbuffer 程序画 ⇒ **M-04（未做）** |
| **GAP-011 闪烁** | ❌ 仍未定位（`h27` 已排除候选①②，③④ 未测；当前 HEAD 复现不出） |
| **cube 采样器** | ❌ 无类型匹配的桩 ⇒ 现在**不绑**（响亮失败）。真要支持需另立一轮 |
| **`@Nullable`（QD-01）** | ❌ 仍未做（连续多轮的质量债，本轮未碰） |

---

## 六、交给用户验证的清单（runClient）

### 6.1 轮二（优先级最高 —— 它是 UB，且不报错）

```properties
# run/config/vkdisp-client.toml
mrt.terrain = true
mrt.packTerrainShader = true
```

**看什么**（日志）：

- ✅ 应出现 `volume stub ready (4x4x4 RGBA8@0)`；
- ✅ 应出现 `pack terrain uniforms bound: … by dimension: {…VOLUME_3D=4…}`（BSL 默认配置下）
  —— **这一行里的 `VOLUME_3D=4` 就是本轮修复的直接证据**（此前这 4 个是 2D 占位）；
- ✅ 应出现 `[GAP-003] sampler plan: …` 的 WARN 行（cube / 不认识类型，各 0 条属正常）；
- ❌ 若看到 `sampler 'xxx' 类型 'yyy' 无类型匹配的视图 -> **不绑定**` ⇒ 那是**预期**的响亮失败，
  但要确认随后 draw 抛的 `Missing uniform` 是**已知**的（不是本轮引入的新问题）。

### 6.2 轮三（绿天空）

**看什么**：

- ✅ 默认（`mrt.slotDiagnosticClear=false`）应打 `terrain slot clear = NEUTRAL …` 一次；
- ✅ 屏幕上天空那片应是**黑**（不是绿）；
- 🔬 若要复现旧行为做对照：置 `mrt.slotDiagnosticClear=true` ⇒ 应打 WARN 且天空变绿。

### 6.3 轮一（能力门控）

```properties
pack.capabilityGate = true
mrt.terrain = true
mrt.packTerrainShader = true
```

**看什么**（BSL）：

- ✅ 应出现 `选项 [CAPABILITY_GATE_APPLIED] 选项 'PARALLAX' 声明依赖本引擎缺失的能力…` ×N；
- ✅ 应出现 `选项 [CAPABILITY_GATE_SUMMARY] 能力门控共关闭 N 个包特性：[…]`；
- ✅ **画面地形应当可见**（此前开 `ADVANCED_MATERIALS` 时是纯黑剪影）。

**反向验证（关键）**：切到 Complementary ⇒ 应出现
`CAPABILITY_GATE_NO_DECLARATION`（零星号 ⇒ 不门控），**且视差仍在**。
这一条是「逐包判定、不硬编码」的唯一直接证据，**务必跑**。

---

## 七、复算 / 自检命令

```bash
# 单测（本轮新增 41 条：门控 16 + sampler 维度 15 + 清屏色 10；另 lang 解析 12）
./gradlew test --console=plain

# 复核「BSL 有 19 条星号 / Complementary 有 0 条」这一事实（决定门控逐包判定成立）
python3 - <<'PY'
import zipfile
for pack in ('BSL_v10.1.8', 'ComplementaryReimagined_r5.9.3'):
    z = zipfile.ZipFile(f'run/shaderpacks/{pack}.zip')
    for n in z.namelist():
        if n.startswith('shaders/lang/') and n.endswith('.lang'):
            t = z.read(n).decode('utf-8', 'replace')
            stars = [l for l in t.split('\n') if l.rstrip().endswith('*')]
            print(pack, n, 'star-marked =', len(stars))
PY

# 复核「BSL 有 4 个 sampler3D」这一事实（轮二修复的直接依据）
python3 - <<'PY'
import zipfile, re
z = zipfile.ZipFile('run/shaderpacks/BSL_v10.1.8.zip')
hits = {}
for n in z.namelist():
    if n.endswith(('.glsl', '.fsh', '.vsh')):
        t = z.read(n).decode('utf-8', 'replace')
        for m in re.finditer(r'sampler(2DShadow|2DArray|3D|Cube|CubeShadow|2D)\s+(\w+)', t):
            hits.setdefault('sampler' + (m.group(1) or '2D'), set()).add(m.group(2))
for k in sorted(hits):
    print(k, sorted(hits[k]))
PY
```

---

## 八、自检

- [x] 三项都是**已定位未修**的真实缺陷，且都是静默型（不报错、画面慢慢坏）
- [x] 每项都有**可复算的实测依据**（扫包统计 / 既有 evidence），无一处靠猜（X9）
- [x] 门控**不硬编码选项名**，且用 Complementary（零星号）作为逐包判定的实测对照
- [x] 轮二维度**来自声明的类型**而非名字（单测用 `someVendorVolumeLookup` 验证）
- [x] 轮三**保留了诊断色的可诊断性**，只是限定作用域（不是「删掉换取干净」）
- [x] 两处自我纠正已记入文档：轮一**接线点接错后改**（§2.5）、`SamplerDimensionPlan`
      首版的无意义表达式已清理
- [x] `clearColorTexture` 返回 `void`、`writeToTexture` 只写单层 —— 两处均**查 sources jar 核实**，未猜
- [x] 单测 41 条新增全部围绕**具体设计主张**，不是覆盖率凑数
- [x] 如实列出**本轮没做的事**（§五），尤其 runClient 未跑
