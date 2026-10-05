# h34 · 闭环 QD-01（连续七轮）＋ 抓住并修掉自己上一轮引入的日志刷屏

> **日期**：2026-10-05
> **性质**：一轮**质量债闭环**（不是新功能），外加一处**对上一轮的自我纠错**。
> **verdict = 787 条单测全绿 + runClient 复验零新增 ERROR + 字节码级证明「注解零行为变化」。**
> 任务来源 = `QUALITY-DEBT.md` §3「每轮迭代第 0 步：检查本表是否有可低成本闭环的项」
> （QD-01 成本 30 min，已连续**七轮**未动）+ 用户指令「根据开发文档持续进行开发」。

---

## 一、一页版结论

| 项 | 内容 |
|---|---|
| **QD-01** | 🔴 **闭环**。`bridge/` 全部 **10 处**可空返回值补 `@Nullable` + 新增**构建期守卫** |
| **自我纠正** | 🔴 上一轮（`h33`）把一条 INFO 挪进了**每帧执行**的方法 ⇒ 实测刷 **499 行**。已修，并加守卫防复发 |
| **单测** | 783 → **787**（+4：空安全守卫 3 条 + 日志守卫 1 条） |
| **字节码** | 7 个受影响类中 **6 个逐字节相同**；第 7 个只差那一条日志的**位置** |
| **runClient** | vkdisp ERROR 仅剩环境事实 1 条（`P0.2 OpenGL`）；pass 失败 **0**；setUniform 异常 **0** |

🔖 **本轮最有价值的一条不是「补了注解」，而是「守卫自己先坏了、被元测试抓住」。**
详见 §四。

---

## 二、🔴 自我纠正：上一轮引入的日志刷屏（499 行 → 1 行）

### 2.1 症状（本轮 runClient 实测）

```
$ grep -c 'gbuffer terrain targets ready' run/logs/latest.log
499
```

499 行**完全相同**的内容：
```
[Render thread/INFO] vkdisp: [GAP-003/A] gbuffer terrain targets ready: 854x480 slots=8 depth=D32_FLOAT
```

### 2.2 根因：上一轮重构时挪错了一行

`h33` 把 `ensureTargets` 拆成四个独立 ensure 时，把这条 INFO 从
「首次创建分支」挪到了 `ensureTargets` **末尾** —— 而 `ensureTargets` **每帧都跑**。

🔖 **讽刺之处**：`h33` 的同一批改动里，我**刚把这条纪律写进注释**
（`ShadowStubs` 的 WARN 必须一次性哨兵，因为「每帧兜底之后必须自己节流」），
下一轮自己就犯了同类错误。这说明**写在注释里的纪律拦不住人，只有守卫能**。

### 2.3 修法

把日志移回 `ensureColortex` 的「建好那一刻」，并在 `ensureTargets` 末尾留注释说明
「为什么这里没有日志」，避免下一个重构者再挪一次。

### 2.4 新增守卫（防复发）

`MrtTerrainPassWiringTest#ensureTargetsHasNoUnconditionalInfoLog`：

- `ensureTargets` 里出现无条件 `LOGGER.info` ⇒ **构建失败**；
- 同时**反向断言**「`gbuffer terrain targets ready` 这条可诊断性不得被删掉」——
  `h27` 记过「`MrtPlan` 槽 0 的指纹恰好是黑，删掉诊断色就丢了『没画 vs 很暗』的区分能力」那个坑。

---

## 三、轮一 · QD-01 闭环

### 3.1 🔖 先核实「欠债到底欠多少」

`QUALITY-DEBT` 原文：「全主源码仅 **2 处** `@Nullable`（`bridge/MixinTargets`）」。

**实测：那 2 处只是 javadoc 里引用原版方法签名，`org.jspecify` 从未被 import 过。**

```
$ grep -rn 'org.jspecify' src/main/java/    →  0 命中
```

⇒ 真实注解数为 **0**。债比登记的还多一点。
🔖 但**病根不是「漏标」** —— 是**规范没有任何东西让它持续**（QD-02 死开关、`h33` 门控死开关
都是这个形状）。所以本轮的重点是**守卫**，不是「又写一遍规范」。

### 3.2 补上的 10 处

| 类 | 方法 | 可见性 | 返回 null 的条件 |
|---|---|---|---|
| `MrtProbe` | `slotView(int)` | public | 越界 / 未建 |
| `MrtTerrainPass` | `slotView(int)` | public | 未建 / 越界 |
| `TerrainDrawCapture` | `current()` | public | 未启用 / 未捕获 |
| `TerrainDrawCapture` | `capturedFrom()` | public | 未捕获 |
| `TerrainPipelineApi` | `derivedTerrainPipeline(String, boolean)` | public | 开关关闭 / 未知层 |
| `TerrainPipelineApi` | `packTerrainForMrt()` | 包内 | 配置关闭 / 无包片元 |
| `VolumeStubs` | `view()` | 包内 | 3D 纹理建不出来（GAP-014） |
| `DeviceApi` | `deviceInfoOrNull()` | private | 设备未就绪 |
| `ShaderCompileApi` | `ShaderSource#getShader` | 覆写原版 | 空 include 桩 |
| `ShaderCompileApi` | `ShaderSource#getInclude` | 覆写原版 | 空 include 桩 |

### 3.3 🔖 一个额外发现：我们的覆写原本违反了原版契约

已从 `minecraft-patched-26.3.0.41-beta.jar` 反汇编核实，原版接口自己就带注解：

```
$ javap -v com/mojang/renderpearl/api/pipeline/ShaderSource.class
  public abstract java.lang.String getShader(Identifier, ShaderType);
  org.jspecify.annotations.Nullable          ← 原版就这么标
  public abstract ShaderSource$CachedIncludeSource getInclude(Identifier);
  org.jspecify.annotations.Nullable          ← 原版就这么标
```

⇒ 我们的两个覆写**原本没有`@Nullable`**，与被覆写的契约不一致。现已对齐。

### 3.4 🔖 守卫：把规范变成构建期红灯

`BridgeNullableContractTest` 扫描 `bridge/` 每个 `.java`，
用「注释剥离 + 类体深度 + 方法声明匹配」定位每个 `return null;` 所在的方法，
要求它带 `@Nullable`。

⇒ **新写一个会返回 null 的方法而忘了标注，`./gradlew test` 直接挂。**

---

## 四、🔖 守卫自己先坏了 —— 元测试把它抓出来

第一版守卫对「故意漏标注」的样本**一条都不报**。两条元测试各暴露一个独立 bug：

| 元测试 | 抓到的 bug | 修法 |
|---|---|---|
| 「守卫要真的能抓到漏标注」 | 正则用了 `matches()`（要求整行匹配），而声明行后面还跟着 `)`、`{` ⇒ **永不命中** | 改 `find()`（正则锚在 `^`，只要求行首匹配） |
| 同上（连带） | 把「方法声明所在层」当成 `depth == 0`，但**类体是 depth 1** ⇒ 类声明一开括号，所有方法都被判成「不在 depth==0」 | 显式跟踪 `classDepth` |
| 「守卫不得把注释里的 return null 当违例」 | 注释未剥离 | 加 `stripNonCode` |

🔖 **这就是「守卫要有元测试」的理由**：一个永远通过的守卫比没有守卫更危险，
因为它会让人以为规范在自动执行。本轮这条元测试直接把「形同虚设」变成了「有效」。

---

## 五、🔖 字节码级证明：注解零行为变化

`@Nullable` 只增加 `RuntimeVisibleTypeAnnotations` 属性，理论上不改指令 ——
但「理论上」不算证据。实测：对 7 个受影响类做 `git stash` 前后对比编译产物。

```
$ javap -p -c -cp build/classes/java/main <每个类>   # 改前 / 改后各一份

IDENTICAL  dev.vkdisp.bridge.MrtProbe
DIFFERS    dev.vkdisp.bridge.MrtTerrainPass
IDENTICAL  dev.vkdisp.bridge.DeviceApi
IDENTICAL  dev.vkdisp.bridge.VolumeStubs
IDENTICAL  dev.vkdisp.bridge.TerrainPipelineApi
IDENTICAL  dev.vkdisp.bridge.TerrainDrawCapture
IDENTICAL  dev.vkdisp.bridge.ShaderCompileApi
```

**7 个里 6 个逐字节相同** ⇒ 补 8 处 `@Nullable` 确实**没有改动任何一条指令**。

唯一有差异的 `MrtTerrainPass` 也已逐方法核对，差异**只有那一行日志换了个位置**：

| 方法 | 改前行数 | 改后行数 | 含义 |
|---|---|---|---|
| `ensureColortex` | 107 | **129** | **+22** = 日志搬了进来 |
| `ensureTargets` | 30 | **8** | **−22** = 日志搬了出去 |

字符串 `gbuffer terrain targets ready` 在两份产物里**都恰好出现 1 次**
⇒ 是**搬迁**不是删除。其余 diff 全是常量池下标重排（`#517`→`#509` 等），语义无变化。

⚠️ **明确不做的事**：本轮**没有**拿两次运行的截图做「像素完全一致」的对比。
试过，但两次运行的**世界状态不同**（时间/云层/机位都没固定），
实测差异 75.29% 像素 —— 那是世界漂移，不是行为变化。
按 `07` 的取证纪律（取证前固定时间/天气/视角）这不构成有效对照，**故不据此下结论**。

---

## 六、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| `bridge/` 之外的内部 `return null`（**60 余处**） | ❌ **未做** ⇒ 登记 **QD-06**（优先级下调：守卫已建，规范不会再失效） |
| QD-03（静态可变状态无测试隔离） | ❌ 未动 |
| QD-04（3 个 `>60` 行方法待定位） | ❌ 未动 |
| **Vulkan 后端验证** | ❌ **做不到**（见 `h33` §八：本机 WSL2 无 Vulkan ICD，GPU 是 `llvmpipe` 软件光栅）。本轮所有运行期结论均来自 OpenGL 后端 |
| GAP-011 闪烁 | ❌ 未动 |
| 性能 | ❌ **未测**（`llvmpipe` 软件光栅上的帧率不代表任何真实硬件，支柱③ B1 本轮无结论） |

---

## 七、复算 / 自检命令

```bash
# 单测（787 条，0 失败）
./gradlew test --console=plain

# 复核「org.jspecify 从未被 import 过」（QD-01 欠债的真实规模）
grep -rn 'org.jspecify' src/main/java/

# 复核「日志刷屏已修」
grep -c 'gbuffer terrain targets ready' run/logs/latest.log     # 期望 1

# 复核「bridge/ 里没有未标注的可空返回值」
./gradlew test --tests '*BridgeNullableContractTest*'

# 复核原版接口契约（javap -v 看 RuntimeVisibleTypeAnnotations）
javap -v /tmp/rpg/com/mojang/renderpearl/api/pipeline/ShaderSource.class | grep -A1 Nullable

# 字节码等价（改前改后各 dump 一次再 diff）
javap -p -c -cp build/classes/java/main dev.vkdisp.bridge.MrtProbe
```

---

## 八、自检

- [x] 两项缺陷都**先复现再修**（499 行是实测数，不是推断）
- [x] 「欠债规模」先核实再动手（发现真实注解数是 **0**，不是登记的 2）
- [x] 额外发现并修了「覆写违反原版空安全契约」，依据是 **class 文件反汇编**不是猜
- [x] 守卫配了**元测试**，并记录了「第一版守卫形同虚设」这个事实
- [x] 「注解无行为变化」用**字节码对比**证明，不是用「理论上」
- [x] 明确拒绝了一次**无效对照**（两次运行 75% 像素差 = 世界漂移），没有拿它下结论
- [x] 剩余的 60 余处如实登记为 QD-06，没有假装「已全量完成」
- [x] 如实列出本轮**没做的事**（§六），尤其 Vulkan 与性能均无结论
