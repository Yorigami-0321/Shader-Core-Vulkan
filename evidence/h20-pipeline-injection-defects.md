# h20 · 🔴🔶 定位到**两个具体缺陷**：① `active()` 门控会把 **8 附件管线**交给原版；② 自定义绑定组在我方 pass 之外**无人绑定**

> 任务来源：`AGENT_CONTEXT.md` §10.25 ⑥（纯静态对比派生管线与原版 SOLID 管线）。
> 取证方式：**全程纯静态**（读 `TerrainPipelineApi` + 原版 sources jar），**零客户端成本**。
>
> 判定：🔴「多附件管线被单附件 pass 使用」这条**部分成立，但触发条件不是 pass 污染**；
> 　　 🔴🔶 而是 `derivedTerrainPipeline` 的**两个代码级缺陷**。

---

## 〇、一句话结论

🔶 M-01 接线的管线**是单附件的**（`withColorTargetState` 单数）⇒「8 附件管线被误用」**不是**当前闪烁的直接原因；
但在同一段代码里找到了**两个真实的缺陷**，都能独立造成「画面时好时坏」：

| # | 缺陷 | 后果 |
|---|---|---|
| **①** | `if (MrtTerrainPass.active())` 门控：命中时把 **8 附件的 MRT 管线**返回给原版 | 标记一旦泄漏，原版所有地形层都会拿到 8 附件管线用在**单附件 pass** 里 ⇒ **Vulkan 未定义** |
| **②** | 派生管线声明了自定义绑定组 `VkDispTerrainParams`，但**只有我方 pass 会绑它** | 原版拿这条管线画地形时，该绑定组**无人绑定** |

---

## 一、上一轮假设的**修正**（先说清哪条错了）

h19 提出的假设是：「多附件管线被拿到只有 1 个 color attachment 的 pass 里用」。
🔶 **本轮静态核实后，这句话要拆成两半**：

| 命题 | 核实结果 |
|---|---|
| M-01 接线用的管线是 8 附件的 | ❌ **错**。`registerTerrainDerivedPipelines` 第 140 行用 `withColorTargetState`（**单数**），基底 `TERRAIN_SNIPPET`，只有 1 个 color target |
| 存在「8 附件管线被交给原版」的代码路径 | ✅ **对**。见 §二① |

🔶 即：**管线本身没错，错的是「谁在什么条件下能拿到它」。**

---

## 二、🔴🔶 缺陷①：`active()` 门控会把 MRT 管线交给原版

`TerrainPipelineApi.derivedTerrainPipeline`（第 306–322 行）：

~~~java
public static RenderPipeline derivedTerrainPipeline(String layer, boolean multiDraw) {
    if (MrtTerrainPass.active()) {                       // ← 全局静态布尔
        RenderPipeline mrt = DERIVED_MRT.get(key(layer, multiDraw));
        if (mrt != null) {
            WIRED.put(key(layer, multiDraw), Boolean.TRUE);
            return mrt;                                  // ← 8 附件管线
        }
    }
    ... // 否则返回单附件派生管线
}
~~~

🔶 `MrtTerrainPass.active()` 读的是 `inMrtPass`，一个**进程级静态布尔**。
它的生命周期被 `try { ... } finally { inMrtPass = false; }` 框住（这部分是对的，h06 已核过）。

🔶 **但风险不在 finally，在命中条件**：只要 `active()` 在**任何时刻**为 true，
而原版此刻正在为**单附件 pass** 取地形管线，它就会拿到 8 附件管线。
⚠️ 原版取 `ChunkSectionLayer#pipeline` 的时机**不受我方控制**（`LevelRenderer`、`SectionRenderDispatcher` 等多处）。

---

## 三、🔴🔶 缺陷②：自定义绑定组在我方 pass 之外**无人绑定**

### 3.1 我们的派生管线**追加**了一个绑定组

~~~java
RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET);
builder.withLocation(...)
        .withBindGroupLayout(BindGroupLayout.builder()   // ← 追加，不替换
                .withUniform(TERRAIN_PARAMS_UNIFORM, UniformType.UNIFORM_BUFFER)
                .build());
~~~

而原版 snippet 已有三个绑定组（`RenderPipelines.java:79-85`）：

~~~java
public static final RenderPipeline.Snippet TERRAIN_SNIPPET = RenderPipeline.builder(LIT_BLOCKS_SNIPPET)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
        .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
        .withVertexShader("core/terrain").withFragmentShader("core/terrain")
        .buildSnippet();
~~~

### 3.2 🔶 绑定者只在**我方 pass** 里

`VkDispTerrainParams` 只由 `MrtTerrainPass.drawTerrain` 里的 `TerrainPipelineApi.updateTerrainParams()` 写入，
绑定动作也只发生在 `drawTerrain` 内。⇒ **原版拿着这条管线画地形时，那个绑定组根本没有人绑。**

### 3.3 🔖 这为什么是「画面时好时坏」的温床

派生管线**被绑到了原版的地形绘制路径上**（M-01 的全部意义），而它携带的绑定组**只在我方 pass 里有人负责**。
两条路径对同一条管线的**状态要求不一致** —— 这是一类典型的「同一资源被两个消费者以不同前置条件使用」。

⚠️ **本轮未验证**这两条各自贡献多少。但它们是**代码里可直接读出的确定缺陷**，不是推测。

---

## 四、净结果

| 项 | 状态 |
|---|---|
| 「M-01 管线是 8 附件」 | ❌ **错**，它是单附件 |
| 缺陷① `active()` 门控可把 MRT 管线交给原版 | 🔴 **成立**（代码级确定） |
| 缺陷② 自定义绑定组在我方 pass 之外无人绑定 | 🔴 **成立**（代码级确定） |
| 两者对闪烁的贡献占比 | 🟡 **未验证** |
| 客户端成本 | ✅ **0**（全程纯静态） |
| 测试 | ✅ **699** 单测全绿（本轮未改产品代码） |

---

## 五、稳定（支柱②）

| 判据 | 值 |
|---|---|
| `Render thread/ERROR` | 2（**均与 vkdisp 无关**：Narrator、SoundSystem） |
| `Couldn't parse GLSL` | **0** |
| 残留游戏进程 | **0** |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer —— 而这恰是本问题的关键验证层）。

---

## 六、测试

本轮**未改产品代码**（纯静态定位）。`./gradlew build` ⇒ **699** 单测全绿。

---

## 七、本轮**没有**证明的

1. ⛔ **闪烁根因仍未闭环** —— 找到两个确定缺陷，但**没有证明其中任何一个就是闪烁的原因**。
2. ⛔ 「黑天」的机理仍**未解释**（`depth test` / 光照 / 全局绑定组三条路都没验）。
3. ⛔ GAP-008 未判定；GAP-007 / GAP-009 未动；GAP-010 未修；M-04 仍需用户裁决。

---

## 八、下一轮入口

1. **修缺陷②**（风险最低、收益最明确）：要么让原版路径也绑上 `VkDispTerrainParams`，
   要么把该绑定组从「接线用的派生管线」上摘掉（我方 MRT 管线保留即可）。
2. **给缺陷①加守卫**（X49 的「单变量对照」精神）：在 `active()` 为 true 时，
   先确认当前 pass 的 color attachment 数确实是 8，否则**回退原版管线**并自报回落次数（X45）。
3. 修完再回客户端验证闪烁是否消失；**GAP-010 并行**（用户可见的 12 条 ERROR）。

---

## 九、产物与哈希

本轮为**纯静态定位**，无新截图。参照 h19 的判据日志：
| 文件 | sha256 |
|---|---|
| `run/logs/latest.log`（h19 那趟） | `641199e27be811ca4fac975045564900949621311cbd392d59d3dcb9745cef33` |

收尾：残留游戏进程 0；配置保持默认（未启动客户端）。
