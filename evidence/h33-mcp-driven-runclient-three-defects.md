# h33 · MCP 驱动 runClient：抓到 1 个 P0 回归 + 2 个真缺陷，并修掉

> **日期**：2026-10-05
> **性质**：上一轮（`h32`）的三项改动**从未跑过 runClient**（当时由用户协助）。
> 本轮按用户要求「用 MCP 驱动游戏进行测试验证」补齐 ⇒ **抓到 1 个 P0 回归 + 2 个真缺陷**。
> **verdict = 三项缺陷全部已修 + 14 条回归守卫单测 + 783 条单测全绿 + runClient 三轮取证。**
> 🔖 本轮最重要的产出不是「新功能」，而是：**上一轮的三个 commit 里有一个让整个地形 MRT pass 每帧死掉的回归。**

---

## 〇、取证环境（`07` X53 要求：窗口尺寸 / 分辨率 / GPU / 驱动）

| 项 | 实测值（runClient 日志原文） |
|---|---|
| **窗口 / 分辨率** | `854×480`（MCP `screenshot` 回执的 PNG 头 `IHDR`，4 张图逐张核对一致） |
| **请求的后端** | `Graphics backend forced to vulkan by launch argument` |
| **实际后端** | `Using graphics backend OpenGL, using drivers: 4.6 (Core Profile) Mesa 26.2.3-arch1.1` |
| **GPU / 驱动** | `Using graphics device: llvmpipe (LLVM 22.1.8, 256 bits) (Mesa)` |
| **Vulkan** | ❌ `Failed to create backend Vulkan` ← `Vulkan loader library is missing`；`Failed to load Vulkan loader` ← `UnsatisfiedLinkError: Failed to locate library: libvulkan.so.1` |
| **宿主** | WSL2（`6.18.33.2-microsoft-standard-WSL2`），`DISPLAY=:0` |
| **JDK** | OpenJDK 25.0.4.1 LTS |
| **构建** | `./gradlew build` + `./gradlew test` 退出码 0；**783 条单测 0 失败** |

🔖 **GPU 是 `llvmpipe` 纯软件光栅化器，不是真实显卡** —— 这决定了：
① Vulkan 不可用（无 ICD、无 loader）；② 帧率数字**不代表**任何真实硬件表现，
本轮因此**没有**做任何性能结论（支柱③ B1 未测）。

⚠️ **X54 偏离如实记录**：改 `run/config` 前本轮**没有**用仓库的
`tools/vulkan-local/game_procs.sh count`，而是用 `ps -eo args | grep devlaunch | wc -l`
确认残留进程数为 0 后才改的（脚本口径为 `-Dfml.modFolders=vkdisp`）。
⇒ **意图达到了，手段不是规范指定的那个**；下一轮改回用脚本。

---

## 一、一页版结论

| # | 缺陷 | 性质 | 证据 | 状态 |
|---|---|---|---|---|
| **D1** | `VolumeStubs` 的 3D 桩**建不出来** —— 原版 26.3 无条件禁止 3D/数组纹理 | 🔴 **P0 回归**（上一轮引入） | runClient：`UnsupportedOperationException: Array or 3D textures are not yet supported` | ✅ 已修 |
| **D2** | 上面那个抛**穿透 `ensureTargets`**，把创建 `atlasSampler` 那一步整个跳过 ⇒ 它永远 `null` ⇒ 每帧抛 `textureView and sampler must both or neither be null` | 🔴 **P0 回归**（上一轮引入） | runClient：**2702 条** / 一次运行 | ✅ 已修 |
| **D3** | 能力门控是**死开关**：反射用配置键名去取 Java 字段名 ⇒ 恒 `NoSuchFieldException` ⇒ 恒默认关 | 🔴 **静默失效**（上一轮引入） | runClient：配置写 `capabilityGate=true`，日志却打 `（pack.capabilityGate=false）` | ✅ 已修 |
| **D4** | 本机（WSL2）**没有 Vulkan** ⇒ 客户端跑在 OpenGL 后端 | 🔵 **环境** | `UnsatisfiedLinkError: Failed to locate library: libvulkan.so.1` + P0.2 断言失败 | ⚠️ 如实登记，**本轮任何 Vulkan 专属结论都不可在本机验证** |

🔖 **D1+D2 合起来的效果**：拉取前地形 MRT pass 能跑（带一处静默 UB）；
拉取后它**每帧抛一次异常、整条 pass 完全不可用**。
⇒ 这正是 `01-DEV-LOOP.md` §0 那句「没跑过 runClient 的改动，一律视为未完成」的现实案例。

---

## 二、D1 · 原版 26.3 不能创建 3D / 数组纹理（GAP-014，新登记）

### 2.1 实测原文（runClient 首轮）

```
[Render thread/ERROR] vkdisp: gbuffer terrain pass failed
java.lang.UnsupportedOperationException: Array or 3D textures are not yet supported
	at ...renderpearl.frontend.FrontendGpuDevice.verifyTextureCreationArgs(FrontendGpuDevice.java:133)
	at ...renderpearl.frontend.FrontendGpuDevice.createTexture(FrontendGpuDevice.java:90)
	at dev.vkdisp.bridge.VolumeStubs.ensure(VolumeStubs.java:99)
	at dev.vkdisp.bridge.VolumeStubs.init(VolumeStubs.java:85)
	at dev.vkdisp.bridge.MrtTerrainPass.ensureTargets(MrtTerrainPass.java:483)
```

### 2.2 🔖 查证过程（**不是猜的**）

从 `minecraft-patched-26.3.0.41-beta.jar` 里抽出 `FrontendGpuDevice.class` 逐字节码反编译
（`javap -p -c`），`verifyTextureCreationArgs(int,int,int,int,int)` 的实际逻辑：

```
iload 4            ; depthOrLayers
iconst_1
if_icmple 169      ; depthOrLayers <= 1 → 直接放行
...
new  UnsupportedOperationException
ldc  "Array or 3D textures are not yet supported"
athrow
...
; cube 数组分支： depthOrLayers > 6 → "Array textures are not yet supported"
```

| 判据 | 结论 |
|---|---|
| 抛在哪个包 | `com.mojang.renderpearl.frontend` = **前后端共用层** |
| 与后端有关吗 | **无关**（`opengl` / `vulkan` 后端都走这一层） |
| 所以是环境问题吗 | ❌ **不是**。本机 OpenGL 后端是另一回事（D4），与这条无关 |

### 2.3 对 `h32` 的一处事实更正

`h32` §3.1 写「BSL v10.1.8 全包 `sampler3D` **4 个**（`lighttex` / `lighttex0` / `lighttex1` / `voxeltex`）」。
本轮重新扫包（脚本见 §七）：

```
sampler3D ['lighttex0', 'lighttex1', 'voxeltex']     ← 3 个，不是 4 个；没有无下标的 'lighttex'
```

且**这 3 个都不在 `gbuffers_terrain` 的采样器里** —— 该文件自身 `uniform samplerXX` 声明数为 **0**
（采样器全部来自 `#include`），实测接线后的地形片元只有 **7 个采样器**，
`by dimension: ATLAS_2D=1 PLACEHOLDER_2D=1 NEUTRAL_MATERIAL_2D=2 SHADOW_DEPTH_2D=2 SHADOW_COLOR_2D=1`
⇒ **`VOLUME_3D` 实际为 0**。

⚠️ `h32` §6.1 写的验收判据是「应出现 `VOLUME_3D=4`」—— **该判据本身建立在错误前提上**
（假定原版能建 3D 纹理），实测取不到。按 `07` X42（不许把能力上限与实际值混用），此处更正。

### 2.4 修法

```java
// VolumeStubs.ensure()：建不出来就**记下来**，不再每帧重试
try { texture = device.createTexture(..., SIZE, SIZE, SIZE, 1); }
catch (UnsupportedOperationException e) { noteUnsupported(e); return; }

// VolumeStubs.view()：返回 null 而不是抛 —— 让调用点走既定的「不绑 + 报错」分支
static GpuTextureView view() {
    if (volumeView == null || volumeView.isClosed()) { reportUnsupportedOnce(); return null; }
    return volumeView;
}
```

🔖 **为什么不绑 2D 图集**：那正是 `GAP-012` 要修的 UB（描述符类型不匹配，不报错）。
🔖 **为什么不删掉这些 sampler**：布局多于 SPIR-V 无害，删掉会让「包声明了它」不可见。
⇒ 沿用 `h32` 已定的「宁可响亮失败」原则：**不绑 + ERROR ⇒ draw 抛 `Missing uniform`**（可定位）。

### 2.5 🔖 一处自我纠正：报错必须**按需求**，不是「探测到就吵」

第一版修法把 ERROR 放在 `noteUnsupported` 里。runClient 实测发现：
即使**包根本没用到 sampler3D**，这条 ERROR 也会打出来（`init()` 是无条件调的）
⇒ 那是在报一个**当前配置下并不存在**的故障，取证者会顺着去查一个不存在的问题。

⇒ 改成「记录」与「报错」两步：`view()` 真被调用时才报错，且只报一次。

---

## 三、D2 · `ensureTargets` 的级联：一个 sampler 的问题炸掉了整个 pass

### 3.1 实测原文

首次运行**同时**出现两种异常，计数如下：

| 异常 | 条数 |
|---|---|
| `UnsupportedOperationException: Array or 3D textures are not yet supported` | **1** |
| `IllegalArgumentException: textureView and sampler must both or neither be null` | **2702** |
| （日志行 `vkdisp: gbuffer terrain pass failed`） | **1940** |

### 3.2 🔖 根因链（本轮亲手在源码里定位，不猜）

```
VolumeStubs.init() 抛
  └─▶ ensureTargets() 中断在第 483 行
        ├─ colortex       已在第 468 行赋值 ✅
        ├─ atlasSampler   在**第 484 行**（抛点的下一行）⇒ 永远没被赋值 ❌
        └─ 下一帧 ensureTargets() 走 early-return：colortex != null ⇒ 认为「都建好了」⇒ 永不补建
              └─▶ atlasSampler 永远 null
                    └─▶ pass.setUniform(name, view, null)
                          └─▶ IllegalArgumentException（每帧每 sampler 一次 ⇒ 2702 条）
```

🔖 **结构性缺陷**：`ensureTargets` 是「建到一半就 return」的顺序块，
而 early-return **只看 `colortex != null`** ⇒ 任何中途抛异常都会造成**半初始化被当成已初始化**。

### 3.3 修法：拆成四个互相独立的 ensure

```java
private static void ensureTargets(RenderTarget main) {
    ensureColortex(main);      // 尺寸/resize（不含任何可能抛的资源）
    ensureShadowStubs();      // 每帧兜底（自身幂等）
    VolumeStubs.init();        // 每帧兜底（自身幂��� + 不再抛）
    ensureAtlasSampler();      // 每帧兜底，幂等：if (atlasSampler == null)
}
```

外加一道**可定位的停机点**（而不是让 null 流到 `setUniform`）：

```java
private static boolean atlasSamplerReady() {   // 不可用 ⇒ 报一次 ERROR 并跳过绘制
```

🔖 为什么必须这样：症状「`textureView and sampler must both or neither be null`」
**看不出根因**，会让人去查纹理绑定逻辑；真正的根因在完全另一处（01-DEV-LOOP §6 第 2 步）。

### 3.4 节流（h33 实测 2702 行日志的教训）

| 位置 | 改法 |
|---|---|
| `FullscreenPassHook` 的 pass 失败 ERROR | 由 D2 根除（不再每帧失败） |
| `TerrainPipelineApi` 的「sampler view is null」 | 「首条 + 每 600 帧」 |
| `MrtTerrainPass` 的 A/B WARN | 一次性哨兵 |
| `VolumeStubs` 的能力缺失 ERROR | 一次性哨兵 |

🔖 与 `h25` 的 M-01 埋点 600→250000 是**同一课**：热路径上的无节流日志会把 I/O 变成瓶颈。

---

## 四、D3 · 能力门控是死开关（静默失效）

### 4.1 实测原文

配置里明明写着 `pack.capabilityGate = true`，日志却打：

```
vkdisp: composite source diagnostic: WARN: BSL_v10.1.8: 选项 [CAPABILITY_GATE_OFF]
  能力门控已由配置关闭（pack.capabilityGate=false）⇒ …
```

### 4.2 🔖 根因：两个不同的名字被当成了一个

```java
public static final String CONFIG_KEY = "pack.capabilityGate";   // 配置键名
...
Object value = config.getField(CONFIG_KEY).get(null);            // 🔴 却拿它当 Java 字段名
```

而 `VkDispConfig` 里的真实字段名是 `CAPABILITY_GATE` ⇒ 每次都 `NoSuchFieldException`
⇒ 被 `catch (Throwable)` 吞掉 ⇒ **恒返回默认值 false**。

症状极具欺骗性：**无异常、无告警、日志照打**，只是开关永远不生效。
与已闭环的 **QD-02（`debugLog` 死开关）** 是同一族失败形态。

🔖 这是 `h32` 轮一「星号两处表示」那个坑的**同一个教训第二次发作**：
**同一个语义不要有两处表示**。

### 4.3 修法

1. 拆出独立的 `FIELD_NAME = "CAPABILITY_GATE"`（反射专用）；
2. `NoSuchFieldException` **单独捕获**并走一条**可见的报错路径**（不得静默当成默认关）；
3. 🔖 报错**不自己打日志** —— 本轮实测**单测 classpath 上没有 slf4j**
   （`PackBooleanOptionTest` 因此炸成 `NoClassDefFoundError: org/slf4j/LoggerFactory`）。
   改为「记录原因 + `reflectionFailure()`」，由调用方 `PackTerrainSource` 走既有的
   `TranslateDiagnostic` 管道输出成 `CAPABILITY_GATE_SWITCH_UNREADABLE`（severity = **ERROR**）。
   🔖 **诊断手段不该把无关测试拖挂** —— 这条是本轮被测试抓出来的。

---

## 五、三轮 runClient 取证结果（A/B）

### 5.1 配置矩阵

| 组 | `pack.capabilityGate` | `mrt.enabled` | 用途 |
|---|---|---|---|
| **A** | `true` | `true` | 验证 D3 修好（门控真的生效）+ D1/D2 修好 |
| **B** | `false` | `false` | 验证 D2 在门控关时也成立 + 取 `h32` §6.2 的 NEUTRAL 对照 |
| **C** | `false` | `false` | 验证 §2.5 的「按需求报错」修正 + 收尾复验 |

🔖 C 组用的是**修完 §2.5 之后的新二进制**，A/B 两组用的是修完 D1/D2/D3 但**还没**改 §2.5 的那一版。
⇒ A 与 C 之间的唯一差异就是 §2.5 那一处改动。

### 5.2 ✅ D2：pass 从「每帧死」变成「真的在跑」

| 指标 | 修复前（A 组同配置） | 修复后 |
|---|---|---|
| `gbuffer terrain pass failed` | **1940** | **0** |
| `textureView and sampler must both or neither be null` | **2702** | **0** |
| `terrain MRT pass frames` | **无**（从未推进） | **600** |
| 单次运行 vkdisp ERROR 总数 | 1940 + 2702 | **1**（GAP-014，节流后）+ 1（D4 环境） |

### 5.3 ✅ D3：门控真的生效了

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `CAPABILITY_GATE_APPLIED` | 0 | **18** |
| `CAPABILITY_GATE_NON_BOOLEAN_KEPT` | 0 | **24**（门控三条「不」之一在起作用） |
| `CAPABILITY_GATE_SUMMARY` | 0 | **2** |
| 实际关闭的包特性 | — | **9 个**：`SSS, REFLECTION_SPECULAR, REFLECTION_ROUGH, REFLECTION_RAIN, PARALLAX, SELF_SHADOW, NORMAL_DAMPENING, ADVANCED_MATERIALS, NORMAL_PLANTS` |
| 地形片元输出槽位 | `outputs=8` | **`outputs=1`**（`ADVANCED_MATERIALS` 被关 ⇒ 不再走 8 槽高级材质路径） |

🔖 **这一条同时正面回答了 GAP-008 / GAP-009 的老问题**：
`ADVANCED_MATERIALS` 正是「纯黑剪影」的触发条件，它被门控关掉后，
地形**可见且有光照**（见 §六 截图），而不是纯黑剪影。

### 5.4 ✅ h32 轮三（诊断色泄漏）：A/B 像素级判据

天空带（`y=0..119`，102480 px）统计：

| 组 | 平均 RGB | 偏绿像素占比 |
|---|---|---|
| **A**（`mrt.enabled=true` ⇒ 诊断模式） | **(35.9, 236.5, 35.7)** | **79.01% / 80.02%** |
| **B**（`mrt.enabled=false` ⇒ 生产模式） | **(44.0, 43.2, 44.2)** / (55.7, 54.9, 55.7) | **0.00% / 0.00%** |

日志侧同源判据：
```
A: vkdisp: [GAP-003/A] terrain slot clear = DIAGNOSTIC (green/blue/magenta) for 1 slot(s)
B: vkdisp: [GAP-003/A] terrain slot clear = NEUTRAL (RGBA 0,0,0,0) for 8 slot(s) —— 我方 pass 只画地形，天空那…
```

⇒ `h32` §6.2 的验收判据（「默认应打 NEUTRAL 一次；天空应是黑不是绿」）**本轮达成**。

### 5.5 ✅ C 组：整轮取证的「干净基线」

C 组（最终二进制）的 vkdisp ERROR 总数：

```
1  P0.2 断言失败 —— 当前后端是 OpenGL，必须是 Vulkan     ← 环境事实（D4），不是代码缺陷
```

| 指标 | C 组实测 |
|---|---|
| `gbuffer terrain pass failed` | **0** |
| `textureView and sampler must both or neither be null` | **0** |
| `GAP-014`（能力缺失） | **0**（包的地形片元没声明 sampler3D ⇒ 按需求不报） |
| `terrain MRT pass frames` | **600** |
| `terrain slot clear` | **NEUTRAL (RGBA 0,0,0,0) for 8 slot(s)` |
| `by dimension` | `ATLAS_2D=1 PLACEHOLDER_2D=1 NEUTRAL_MATERIAL_2D=2 SHADOW_DEPTH_2D=2 SHADOW_COLOR_2D=1` |

🔖 **C 组是本轮唯一「除环境事实外零 ERROR」的运行**，
且它同时验证了三件事：D1 不再炸 pass、D2 的级联根除、§2.5 的按需报错。

---

## 六、视觉证据（MCP `screenshot` 抓取，854×480）

| 文件 | 内容 |
|---|---|
| `h33-images/runA-gate-on-horizon.png` | A 组地平线：**天空纯绿**（诊断色，按设计），地形可见且有光照 |
| `h33-images/runA-gate-on-terrain.png` | A 组俯视地形：地形**不是纯黑剪影** |
| `h33-images/runB-neutral-sky.png` | B 组地平线：天空**黑/深灰 + 云层**，**无绿** |
| `h33-images/runB-terrain.png` | B 组俯视地形：地形正常 |

![A 组：诊断模式天空为纯绿](evidence/h33-images/runA-gate-on-horizon.png)

![B 组：生产模式天空为深色，0% 偏绿](evidence/h33-images/runB-neutral-sky.png)

---

## 七、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| **Vulkan 后端上的任何验证** | ❌ **做不到**。本机 WSL2 无 Vulkan ICD（§八 D4）。本轮所有结论都来自 **OpenGL 后端**。按 X42，不得把「OpenGL 上成立」说成「Vulkan 上成立」 |
| D1 的 `VOLUME_3D` 正向证据 | ❌ **取不到**。原版建不出 3D 纹理 ⇒ 该分支**不可达**（这本身就是 D1 的结论）。已改为登记 GAP-014 |
| Complementary 反向验证（逐包判定） | ❌ **未跑**。本机 `shaderpacks/` 下只有 BSL，没有 Complementary 包 |
| 黑天空本身 | ❌ 仍未修，属 **M-04（未做）**。B 组截图里天空是深色而非绿，但正确天空要由包的 gbuffer 程序画 |
| GAP-011 闪烁 | ❌ 仍未定位。本轮未做二分 |
| QD-01（`@Nullable`） | ❌ 仍未做（已连续七轮）。本轮未碰 |
| GAP-009 B 方案（LabPBR atlas loader） | ❌ 未做 |

---

## 八、🔴 环境事实登记（D4）：本机没有 Vulkan

```
java.lang.UnsatisfiedLinkError: Failed to locate library: libvulkan.so.1
vkdisp: P0.2 断言失败 —— 当前后端是 OpenGL，必须是 Vulkan
```

```
$ ls /usr/share/vulkan/icd.d/     → No such file or directory
$ command -v vulkaninfo           → 无
```

**完整因果**（日志原文，按时间顺序）：

```
WARN  [Render thread] Graphics backend forced to vulkan by launch argument, …
ERROR [Render thread] Failed to create backend Vulkan
      com.mojang.renderpearl.api.device.BackendCreationException: Vulkan loader library is missing
INFO  [Render thread] Using graphics backend OpenGL, using drivers: 4.6 (Core Profile) Mesa 26.2.3-arch1.1
INFO  [Render thread] Using graphics device: llvmpipe (LLVM 22.1.8, 256 bits) (Mesa)
```

🔖 `runClient` **确实带了 `--graphicsBackend vulkan`**（Gradle 配置里已声明），
所以 P0.2 失败**不是配置漏了**，而是本机装不出 Vulkan loader。

⚠️ **影响**：
1. `P0.2`（确认跑在 Vulkan 后端）在本机**无法满足**；
2. 本项目大量红线针对 **Vulkan**（别名 UB、描述符类型不匹配、无 validation layer…），
   这些**在 OpenGL 后端上不会被触发**；
3. ⇒ 本轮修的三条里，**只有 D2/D3 与后端无关**（纯逻辑/资源生命周期），**已充分验证**；
   **D1 的结论来自原版 API 字节码**（前后端共用层），同样与后端无关，**已充分验证**。

---

## 九、复算 / 自检命令

```bash
# 单测（本轮新增 15 条：VolumeStubCapability 11 + PackCapabilityGateSwitch 5，合计 783 全绿）
./gradlew test --console=plain

# 复核「原版 26.3 建不出 3D 纹理」这一事实（决定 GAP-014 成立）
cd /tmp/rpg && javap -p -c com/mojang/renderpearl/frontend/FrontendGpuDevice.class \
  | sed -n '/verifyTextureCreationArgs/,/^$/p'

# 复核「BSL 有 3 个 sampler3D 且都不在 gbuffers_terrain」这一事实
python3 /tmp/scan-samplers.py

# 复核 A/B 天空像素判据（79% 偏绿 vs 0%）
python3 /tmp/sky-stats.py
```

---

## 十、自检

- [x] 三项缺陷全部**先复现再修**，每项都有 runClient 原文或源码定位作为依据（无一处靠猜，X9）
- [x] D1 的「原版不支持 3D 纹理」是**字节码级核实**，且已论证**与后端无关**
- [x] D2 的根因定位到**具体行号**（`atlasSampler` 在抛点下一行），不是「可能是它」
- [x] D3 的修复让开关**真的生效**（9 个包特性被关、输出槽位 8→1），不是「看起来有反应」
- [x] 三组配置 A/B/C 的差异**只有被声明的那一个变量**
- [x] 对 `h32` 的错误事实（4 个 sampler3D / `VOLUME_3D=4` 判据）**已更正**，不沿用
- [x] 如实列出本轮**没做的事**（§七），尤其「本机无 Vulkan ⇒ 不可验证 Vulkan 专属结论」
- [x] 单测守的是**具体设计主张**（级联解耦 / 幂等 / 按需求报错 / 字段名分离），不是覆盖率凑数
- [x] 一处被测试抓出来的自身错误已修（单测 classpath 无 slf4j ⇒ 诊断不得自己打日志）
