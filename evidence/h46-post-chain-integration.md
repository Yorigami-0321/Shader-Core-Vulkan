# h46 · 🔴 BSL 整条后处理链接入（colortex 按名接线）—— 开发 + 首轮运行验证

> 取证方式：**MCP（mcpfabric）驱动** + `mrt.pixelProbe` 进程内数字；本机 **Vulkan 后端**（lavapipe）。
> ⚠️ 按取证铁律：不做任何性能结论；本机无 validation layer ⇒ 不含「无 validation error」式断言。

## 一、本轮开发内容（编译 + 单测级证据，先行）

- 整链：`PostPassContract` / `PostOutputRenumber` / `PackPostChain` /
  `PostSamplerSuperset` / 16 条定宽槽位管线 / `FrameApi.drawPostChain` /
  `FullscreenPassHook` 时序（先地形 MRT 后链）/ `MrtTerrainPass` 池扩容。
- 素材线：`PackTextureBindings`（两段键）+ `bridge/PackTextures`（真纹理上传）+
  **内置 noisetex**（64×64 确定性噪声）。
- GAP-008 探针：`mrt.terrainCoordOutProbe` / `mrt.terrainLodZeroProbe`。
- 单测：构建全绿；**真实 BSL 整链测试**（`PostChainBslTest`）：
  deferred/deferred1/composite/composite1…/final 成链、final 收尾、其它维度不混链、
  超集外 sampler 点名踢出。
- 本轮修掉的真 bug：多维度同名程序重复进链（BSL 实测吃光 16 槽预算、
  尾部整体消失且零报错 ⇒ 又一个「工具自己坏掉且不报错」样本）。

## 二、运行期验证（E 臂 = 链基线；F 臂 = 坐标探针）

### E 臂（`mrt.postChain=true`，探针全关）—— 链机制**通过**

逐字日志（`run/h27/logs/latest.log`，2026-10-05 22:32–22:34）：

```
vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.1, 256 bits)
vkdisp: pipeline count check: registered=40, compiled=40 (aligned)
vkdisp: [GAP-003/A] gbuffer terrain targets ready: 854x480 slots=8 pool=8 depth=D32_FLOAT
vkdisp: [GAP-009] builtin noisetex created: 64x64 (deterministic seed)
vkdisp: [GAP-009] custom texture loaded: sampler='noise' path='tex/noise.png' 512x512
vkdisp: [chain] post chain active: pack=BSL_v10.1.8 passes=11 names=deferred[4] → deferred1[0, 4, 5]
  → composite[0, 1] → composite1[0] → composite2[0] → composite3[0, 2] → composite4[1]
  → composite5[1, 2] → composite6[1] → composite7[1, 2] → final[0]
vkdisp: [chain] post chain executed: passes=11 first=deferred last=final
  (colortex-backed, 定宽 8 附件 + scratch；InSampler=scene)
```

像素探针（#3 稳定轮，TERRAIN_BAND 摘录）：

| 源 | meanRGB | mean_luma | nonBlack | allZero |
|---|---|---|---|---|
| `main` | (0,0,0) | 0.0000 | 0.000% | **true** |
| `colortex0`（albedo） | (0,0,0) | 0.0000 | 0.000% | **true** |
| `colortex3` | (0,248.13,0) | 177.4635 | 97.306% | false |
| `colortex6` | (124.55,212.13,248.13) | 196.1088 | 97.306% | false |
| `colortex7` | (83.26,83.26,83.26) | 83.2605 | 84.603% | false |

🔖 **判读（只下到证据支持的程度）**：
- ✅ **链机制端到端跑通**：11 步全部执行、40/40 管线编译对齐、无 `Missing uniform`/链接错误。
  E 臂前两次启动暴露并修掉的三个真缺陷：① 槽位片元 id 形态（多带 `shaders/` 前缀 ⇒
  required 编译「Couldn't find source」×16）；② `depthtex2` 不在超集（composite2/3 被闸门
  踢出 —— 闸门行为正确、清单缺员）；③ VS 适配层与契约 **location 0 重叠**（glslang
  「overlapping use of location 0」⇒ 全链 16 条 required 管线一起失败 ⇒ 砸整次资源重载）。
- 🔴 **GAP-008 原样存在**：`colortex0` 仍逐像素 ≡ 0，且这次是**经整条链**后 main 全黑 ——
  albedo≡0 就是当前画面的唯一压零项（其余槽形态正常）。
- ⚠️ 本机无 validation layer ⇒ 本文**不含**任何「无 validation error」式断言。

### F 臂（`mrt.terrainCoordOutProbe=true`）—— GAP-008 二叉判定：**坐标侧**

探针自报（逐字，证明开关生效且只动了一行）：

```
采样因子探针命中：左侧(采样) 0 处、右侧(乘子) 0 处、坐标输出 1 处、显式LOD0 0 处
采样因子探针命中: 第 1338 行: vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);
```

colortex0 此时 = `vec4(texCoord, 0.0, 1.0)`（×255 量化），实测（#1 轮，TERRAIN_BAND）：

```
colortex0#1 area=TERRAIN_BAND meanRGB=(0.0000,0.0000,0.0000) nonBlack=0.000% maxR=0 allZero=true
```

⇒ **texCoord 在每一个出片元的地方都是 (0,0)**（若只是落进填充区，坐标本身应仍有非零值；
这里是 0 本身）。判定落进 h45 §七 候选① 的**加强形态**：不是「坐标落进 26% 透明填充」，
而是 **`texCoord` varying 被供成 (0,0)** —— 坐标链路坏，采样器/LOD 侧被排除。
（同帧 colortex3/6/7 正常 ⇒ 片元确实在产出。）

### 下一刀的判据（已设计，未跑）

`texCoord ≡ 0` 仍有两跳没分开：**属性 UV0 本身读 0/NaN**（MRT 包变体管线的顶点布局/格式错配）
vs **适配层→片元的 location 衔接把值丢了**。下一臂：coordOut 改为直接输出**属性**
（`vec4(UV0,0,1)` 在 VS 里透传一条专用 varying），与 FS 端 `texCoord` 的读数对比 ⇒
一跳一定论。NaN 假说一并覆盖（coordOut 写 NaN 属性时 RGBA8 落 0，与现观测一致）。

## 三、本轮运行期暴露并修掉的三个真缺陷

| # | 缺陷 | 症状（第一手） | 性质 |
|---|---|---|---|
| 1 | 槽位管线**片元 id 形态**错（多带 `shaders/` 前缀） | `Couldn't find source for FRAGMENT shader (vkdisp_pack:shaders/post0.fsh)` ×16 | 「猜 id 形态」= X9；对照范本即可避免 |
| 2 | 超集缺 **depthtex2** | `composite2/composite3 … 声明了超集外的 sampler [depthtex2] ⇒ 不进链` | 闸门**行为正确**、清单缺员 —— 机制第一次真拦住了东西 |
| 3 | VS 适配层 `vUv@0` 与契约 `texCoord@0` **location 重叠** | `'location' : overlapping use of location 0` ⇒ **全部 16 条 required 管线编译失败、整次资源重载被砸** | 一个模板细节放大成全链失败；已按「契约占 0 就不输出 vUv」修复 + 单测 |

## 四、判定口径

- 链跑通 ≠ 画面正确：E/F 臂的 `main` 全黑是 **GAP-008（texCoord≡0）经整条链的必然传导**，
  不是链的错误。链侧验收判据 = passes=11 执行、40/40 编译对齐、无 Missing uniform/链接错误。
- ⚠️ 本机无 validation layer ⇒ 本文**不含**「无 validation error」式断言。
- ⚠️ 按取证铁律：不做任何性能结论（lavapipe）。


### I / J 臂（全链槽位探针 + 产品级 LOD0）—— 白前线的定位推进

- I 臂：测集并入链槽后 `colortex1#2 FULL = (251.7,250.7,248.2)` 与 main 同白，
  而 `colortex0 = (45.4,36.0,19.2)`（正常图集色调）⇒ **白进入于写 colortex1 的第一级 = composite4（bloom）**。
- 静态对质（BSL `program/composite4.glsl`）：`const bool colortex0MipmapEnabled = true` +
  `BloomTile` 按 mip 层级采样 ⇒ **OF 语义要求 colortex 带真实 mip 链**；
  我方池纹理只有 mip0 ⇒ 高 LOD 采样行为由驱动决定（本机表现即过曝成白）。
  ⇒ 登记为下一个自行补充项：**colortex 池的 mip 链（GAP-017 候选）**。
- J 臂（产品级 LOD0 转正后）：main 仍 251 —— 与上判读一致（白在 composite4，不依赖 albedo 那行）。
  产品级 LOD0 段自身守卫生效（命中 1 行，未触发 ERROR）。

（本轮到此收线：止血 + 白前线定位完成，修根两项进入登记表。）

### L 臂（GAP-017 修根：ColortexPool 多级纹理 + blit 降采样金字塔 + 脏集机制）

```
main#2 main area=FULL meanRGB=(164.4542,190.1090,255.0000) mean_luma=189.3399
```

对比 K 臂 `main=(251.96,251.07,249.0)` ⇒ **全白退场**，B/G/B 通道出现天空蓝梯度。
判据边界：这是「白消失 + 色调方向正确」的数字证据，不是「画面与 Iris 逐像素一致」——
后者需要截图对照轮（MCP/允许后）。GAP-017 保持开放至此。

### M 臂（给金字塔加自报后重跑）—— **L 臂判读被证伪并撤回**

M 臂第一次启动：链根本没跑 —— `ColortexPool.levelCount` 差一（854×480 给 11 级，引擎校验
`mipLevels ≤ floor(log2(max))+1 = 10`）⇒ `createTexture` 每帧抛、`ensureColortex` 崩在半路，
`fullscreen pass failed` ×每帧。**L 臂的「白退场」= main 根本没被链重写的假象**
（数字 (164,190,255) 来自陈旧画面/旧路径，不是金字塔效果）。⇒ L 臂判读撤回。

修掉差一（`bitlen(max)` + `ColortexPoolLevelTest` 钉住三档）后 M3 臂：

```
vkdisp: [GAP-017] mip pyramid generating: slot=0 levels=10
vkdisp: [chain] post chain executed: passes=11 …
main#2      TERRAIN_BAND meanRGB=(254.93,254.93,254.93)
colortex0#2 TERRAIN_BAND meanRGB=(7.30,6.99,3.04)   ← albedo 正常偏暗（GAP-016 止血稳）
colortex1#2 TERRAIN_BAND meanRGB=(254.93,254.93,254.93)  ← bloom 仍全白
colortex2#2 全零
```

⇒ **真实 mip 链 + 金字塔已运行，bloom 仍饱和** ⇒ 白的成因不在「缺 mip 数据」这一层，
收窄到 `BloomTile` 的采样/表达式侧（候选：`texture2DLod` 转译的 lod 参数处理、
或 tap 权重求和在本配置下的量级）。GAP-017 保持开放；下一步 = 把 composite4 单独跑一臂
（`postChainMaxPasses` 类判据或 coordOut 式探针打到 BloomTile 上）拿中间量数字。

🔖 本轮的元教训（写进纪律）：**「症状消失」必须与「机制在跑」互相印证** ——
L 臂两臂之隔才靠自报戳破：没有 `mip pyramid generating` 这行，数字越好越危险。
