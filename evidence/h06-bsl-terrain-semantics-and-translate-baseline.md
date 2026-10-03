# H06 · 🔴 核实推翻 GAP-003 的前提：**BSL ≠ Iris 语义**，且文本层翻译链**已经通了**

> 任务来源：GAP-003 剩余阻塞「colortex1/2 的 gbuffer 语义」。
> 本轮按 **X9（先核实再写）** 做，结果**推翻了我自己登记的核心前提**，也**大幅降低了阻塞的估计**。
> 证据对象：`run/shaderpacks/BSL_v10.1.8.zip` 的真实源码 + 本仓库翻译链实测。
> （2026-10-03 晚）

## 结论先行（两条，都与既有登记相反）

| # | 既有登记的说法 | 实测 |
|---|---|---|
| ① | OF/Iris 的 gbuffer 语义是「`colortex0`=albedo / `colortex1`=normal+lightmap / `colortex2`=material」，所以要 3 槽 | 🔴 **那是 _Iris_ 的语义。BSL 用 OF 式 `gl_FragData[N]` + `/* DRAWBUFFERS:... */` 映射**，实测全部 gbuffer 程序的 DRAWBUFFERS 集合都是 `{0, 0367, 08, 08367}` ⇒ 高级材质下 **`gl_FragData[1]`→colortex3、`[2]`→colortex6、`[3]`→colortex7**，**不是 colortex1/2** |
| ② | 「多附件与 gl_FragData 改写是阻塞」 | ✅ **文本层已经通了**：BSL 真实 FSH 段（438 行）过 `OfGlslTranslator` **0 个 ERROR**，自动合成 **5 个** `layout(location=0..4) out vec4`，并收编 **29 条**游离 uniform |

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2 |
| 本轮类型 | **纯核实轮**（未跑客户端）—— 用 JUnit 直接喂真实包源码 |
| 库存包 | `run/shaderpacks/BSL_v10.1.8.zip`（309 条目） |
| 残留进程 | ✅ 全程 `残留游戏进程数 = 0`（本轮没开客户端） |

## 1. BSL 的地形片元到底长什么样

`shaders/program/gbuffers_terrain.glsl`：**623 行**，其中 FSH 段 **438 行**（第 10-447 行），
**27 个直接 `#include`**（`/lib/settings.glsl`、`/lib/surface/ggx.glsl`、`/lib/lighting/forwardLighting.glsl` …），
**44 条 uniform**。

写输出的那段（源码第 424-444 行，逐字）：

```glsl
/* DRAWBUFFERS:0 */
gl_FragData[0] = albedo;

#ifdef MCBL_SS
    /* DRAWBUFFERS:08 */
    gl_FragData[1] = vec4(lightAlbedo, 1.0);
    #if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR
    /* DRAWBUFFERS:08367 */
    gl_FragData[2] = vec4(smoothness, skyOcclusion, 0.0, 1.0);
    gl_FragData[3] = vec4(EncodeNormal(newNormal), float(gl_FragCoord.z < 1.0), 1.0);
    gl_FragData[4] = vec4(fresnel3, 1.0);
    #endif
#else
    #if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR
    /* DRAWBUFFERS:0367 */
    gl_FragData[1] = vec4(smoothness, skyOcclusion, 0.0, 1.0);
    gl_FragData[2] = vec4(EncodeNormal(newNormal), float(gl_FragCoord.z < 1.0), 1.0);
    gl_FragData[3] = vec4(fresnel3, 1.0);
    #endif
#endif
```

### 🔴 三条由此推出的事实

**① BSL 最多写 5 个槽，而且槽→colortex 的映射由 DRAWBUFFERS 决定，不是下标。**
`gl_FragData[1]` 在 `MCBL_SS` 分支对应 **colortex8**、在另一分支对应 **colortex3**。
⇒ 我方若按「下标 = 附件序号」绑定，**在启用这些选项的包上会绑错槽**（静默画面错误）。

**② BSL 默认配置下地形只写 colortex0。** 实测 `shaders/lib/settings.glsl`：

| 宏 | 状态 |
|---|---|
| `ADVANCED_MATERIALS` | `//#define ADVANCED_MATERIALS` ⇒ **关** |
| `MCBL_SS` | `//#define MCBL_SS` ⇒ **关** |
| `REFLECTION_SPECULAR` | `#define`（开）—— 但**只在 `defined ADVANCED_MATERIALS` 的块内**被用到 ⇒ 无效 |
| `IRIS_FEATURE_FADE_VARIABLE` | 未定义 |

⇒ 默认走 `DRAWBUFFERS:0` 分支；`newNormal` 计算了但**没有任何输出**（`EncodeNormal` 只出现在被 `#if` 包住的分支里，
`albedo.a` 也从未被赋值）⇒ **默认配置下地形不需要多附件**。

**③ 但 composite 读 colortex0 与 colortex1。** `shaders/program/composite.glsl` 的
`DRAWBUFFERS:01`（另见 `019`、`0195`），实测读到 colortex `{0,1,5,6,8,9}`。
⇒ 帧里至少要有 2 个 colortex；但**默认地形路径贡献 colortex0**。
（其他程序如 `gbuffers_water.glsl` 的 DRAWBUFFERS 是 `01`/`016`/`018`/`0186`，
`gbuffers_entities.glsl` 是 `0`/`03`/`0367`/`08`/`083`/`08367` ⇒ 同族映射，可交叉印证第 ① 条。）

## 2. 翻译链实测：**文本层已经通了**

把 BSL 真实 FSH 段喂进 `OfGlslTranslator.translate(ShaderStage.FRAGMENT, …)`：

```
输入行数            438
诊断总数            6（全部 INFO，**0 个 ERROR**）
  line=416  包内未声明 location 0 的片元输出，已合成声明 layout(location = 0) out vec4 vkdispFragOut0;
  line=420  … location 1 … vkdispFragOut1;
  line=424  … location 2 … vkdispFragOut2;
  line=425  … location 3 … vkdispFragOut3;
  line=426  … location 4 … vkdispFragOut4;
  line=0    29 条游离非透明 uniform 声明收编进 VkDispBuiltins 块
输出里残留 gl_FragData   false
```

⇒ `FragmentOutputAdapter` 已经实现了「`gl_FragData[n]` → `layout(location=n) out vec4`」
（含 DRAWBUFFERS 映射语义，见该类注释第 27 行），`UniformInjector` 也已把 OF 内建收编进块。

🔖 **所以「多附件」与「gl_FragData 改写」这两件事都不是阻塞** ——
这与我 GAP-003 条目里「多附件原语与地形接入都没做」的旧判断不符，已一并更正。

### 2.1 🔴🔴 更正：SPIR-V 编译**早就被验证过**，是本节自己漏看了

⚠️ **本节初版写的是**「文本层 ≠ 能编译，翻译结果能否编译成 SPIR-V **尚未验证**」——
**这句是错的**。项目里**本来就有一条对整包逐程序逐阶段跑 SPIR-V 编译的通路**
（`VkDispPackScan#compileAndLog` → `ShaderPackCompiler` 冷路径 → `bridge/ShaderCompileApi`
→ 原版 `GlslCompiler`），每次资源重载都会跑一遍并打日志。直接查历史日志：

```
vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/gbuffers_terrain  stage=FRAGMENT file=world0/gbuffers_terrain.fsh  spvBytes=79896
vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/gbuffers_terrain  stage=VERTEX   file=world0/gbuffers_terrain.vsh  spvBytes=35736
vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world-1/gbuffers_terrain stage=FRAGMENT file=world-1/gbuffers_terrain.fsh spvBytes=46692
vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world1/gbuffers_terrain  stage=FRAGMENT file=world1/gbuffers_terrain.fsh  spvBytes=63724
vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world1/gbuffers_terrain  stage=VERTEX   file=world1/gbuffers_terrain.vsh  spvBytes=35620
vkdisp: pack compile done: stages=190 ok=190 failed=0
```

⇒ **BSL 的地形片元（三个维度目录）早已成功编译成 SPIR-V**，整包 **190/190 零失败**，
而且已重复出现在此前每一趟的日志里（`run/logs/` 下多份均可 grep 到 12 条）。

🔖 **教训（本轮最贵的一条）**：断言「某能力未验证」之前，**先搜既有日志与既有代码路径**。
本项目把「冷路径整包编译 + 逐阶段 SPIR-V + 汇总计数」做得很完整，
我却在**没有搜日志**的情况下把它记成待办 —— 而这正是 §2 想回答的问题。
⇒ 立 **X41**。

### 2.2 🔖 「能力上限 5 槽」≠「生产实际 1 槽」—— 这个差别会**崩游戏**

同一批日志里，地形片元的合成输出诊断**只有一条**：

```
vkdisp: pack diagnostic: INFO: program/gbuffers_terrain.glsl:425:
        包内未声明 location 0 的片元输出，已合成声明 layout(location = 0) out vec4 vkdispFragOut0;
```

（全包范围里出现过的最大 location 是 2，且来自别的程序。）

**为什么本节 §2 量到 5、生产只有 1**：§2 喂的是**未预处理文本切片** ——
没展开 `#include`、没求值预处理条件 ⇒ 死分支 `#if defined ADVANCED_MATERIALS …` 仍在，
`gl_FragData[1..4]` 全被看见。生产链路先展开再求值 ⇒ 死分支消失。

⇒ 🔴 **两者都是真的，但不能混用**。本节 §2 的 5 是 **`FragmentOutputAdapter` 的能力上限**；
**生产实际值是 1**（= 只用 colortex0），这从经验上**确认了本节 ① 的结论**。

🔴🔴 **而混用的后果是崩客户端**：若按「5 槽」建 pass，而管线颜色目标数是 1（或反之），
`FrontendRenderPass#setPipeline` 会校验「render pass 颜色附件数 == 管线颜色目标数」
并抛 `IllegalStateException`（`h05` 已实测该校验存在）⇒ **客户端直接崩**。

已加两个无头回归（`TerrainProductionOutputCountTest`，3 例，走**生产同款链路**
`ShaderPackScanner.scan` → `ShaderPackCompiler.compile`）：
① 生产实际输出数 == 1（三个维度目录逐个断言）；
② 「能力上限 ≠ 生产实际」这条对照本身；
③ 守卫 `MrtPlan.slotCount()` 仍是两侧唯一来源，且 `SLOT_COUNT` 没被悄悄改成 5。

## 3. 🔴 探针本身踩的坑（值得登记，因为它**差点给出假绿**）

第一版探针用「`indexOf("#ifdef FSH")` … `indexOf("\n#endif")`」截取 FSH 段，只取到 **20 行**
（真实 438）。而那 20 行里**恰好一句 `gl_FragData` 都没有** ⇒ 探针会输出
「`stillHasGlFragData=false`、`success=true`、0 诊断」——**一轮漂亮的假绿**。

真实原因两条：
1. 该文件是 **CRLF**，`.split("\n")` 后每行尾部带 `\r`；
2. **嵌套的 `#endif` 也顶格**（第 29 行就是），所以「找下一个 `#endif`」必然在第一层嵌套处截断。

⇒ 必须**按嵌套计数**配对。已写成常驻测试
`src/test/java/dev/vkdisp/pack/TerrainProgramTranslateBaselineTest.java`（3 例），
其中 `terrainFragmentTranslatesWithoutErrors` 额外断言 `行数 > 300`，
就是为了让「截取退化」立刻变红而不是静默给出假绿。

🔖 **这是 X38 的又一次实例**：判据必须真的作用在被测内容上。
这次的具体形态是「**先断言输入规模，再看结论**」。

## 4. 对 GAP-003 的更正（已写回 `13-GAP-REGISTRY.md`）

| 项 | 更正后 |
|---|---|
| 缺口描述 | ⛔ 不再是「补齐 colortex1/2 的法线/材质」（那是 **Iris** 的语义），而是「**让 BSL 自己的地形片元跑起来**」 |
| 槽位数 | 🔴 `MrtPlan.SLOT_COUNT = 3` 对 BSL **不够**（最多 5），且**附件顺序要服从 DRAWBUFFERS 而非下标** |
| 阻塞清单（下一轮要逐条核实/解决） | ① 翻译结果能否编译成 SPIR-V；② `sampler3D lighttex0/1` vs 原版 **2D** lightmap 的结构性不匹配；③ 44 条 OF uniform 的取值供给（GAP-004 那个块，只收编了声明、还没供值）；④ 按 DRAWBUFFERS 决定附件顺序 |

## 5. 本轮明确**没有**证明的事

| 没证明 | 说明 |
|---|---|
| ~~⛔ 翻译结果能编译成 SPIR-V~~ | ✅ **已验证（本节 §2.1 更正）**：三个维度目录全部编译成功，整包 190/190 零失败 |
| ⛔ DRAWBUFFERS 映射是否被正确实现 | `FragmentOutputAdapter` 注释说「n 由 DRAWBUFFERS 决定」，但**未用 BSL 的 `0367`/`08` 分支实测过映射结果**（默认配置下这些分支是死的，量不到） |
| ⛔ 编译出的地形 SPIR-V **被真正用上** | 🔴 这才是真正的缺口：整包编译产物里有 BSL 地形片元的 SPIR-V，但**地形 draw 用的仍是原版 `core/terrain`** —— 派生 MRT 地形管线是从 `MULTIDRAW_TERRAIN_SNIPPET` 建的，片段着色器是原版的 |
| ⛔ 44 条 uniform 的值从哪来 | 只收编了声明 |
| ⛔ `sampler3D` 能否用 | 原版 lightmap 是 `GpuTextureView` 2D |
| ⛔ 任何画面改进 | 本轮纯核实 |

## 6. 运行期环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| 本轮 | **未开客户端、未改运行期配置**（`shaderPack` 仍是上一轮收尾还原的 `BSL_v10.1.8`） | — |
| 新增文件 | `src/test/java/dev/vkdisp/pack/TerrainProgramTranslateBaselineTest.java`（常驻回归测试，3 例） | 入库 |
| 临时文件 | 探针 `ScratchTerrainTranslateProbeTest.java` | ✅ 已删（换成常驻测试） |
| 游戏进程 | 0 趟 | ✅ **残留 = 0** |