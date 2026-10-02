# P4.4-a 零 mixin 原版shader 覆盖 —— 实跑证伪（2026-10-02）

> **verdict = REJECTED（路线否决，非缺陷待修）**
> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 日志本体在 gitignored 路径（`run/logs/`），本文件只存可复核的事实。
> **结论**：P4.4-a「用资源包覆盖原版 program 源文本」在此架构下**不可行**。
> 已回退（`VkDispVirtualPack` 回到 `1678dde`，`VanillaShaderOverrides` 及其单测删除），
> 唯一产出保留 = M1 红线成文（`07-CONSTRAINTS` M1 + X22–X26 + `MixinTargets` 登记）。
>
> **入库字节口径**：文本已转 LF（`.gitattributes` 全仓库 `eol=lf`）。
> 复核用 `git hash-object evidence/p4x4a-vanilla-override-rejected.md`（git blob 口径，
> 入库后稳定）。**不要**在本文件内记 sha256 —— 写进去会改变自身字节，构造不出稳定自引用。

## 一行复现

```bash
# 本机（Windows 11 + RTX 4060 真实独显），第三轮实跑
"D:/Tools/gradle/gradle-9.4.1/bin/gradle.bat" runClient --no-configuration-cache --console=plain
```

- **环境**：本机 env（RTX 4060 / Windows 11）；NeoForge 26.3.0.23-beta；JDK 25。
- **判定线**：`P4.4 覆盖汇总: N/2` 中 N=2（覆盖生效）**且** 游戏可进（无
  `Failed to load required shader programs`）。本轮N=2 但**游戏进不去 ⇒ 路线否决**。
- **日志指纹**：`run/logs/latest.log` sha256 = `64ea53f4054b1c25…82b4f68`（2026-10-02 20:10）。
- **取证工具**：`tools/vkdisp-shot/Probe.java`（gitignored，不入库）—— 直接调产品
  `GlslPipeline` 把 BSL 源码过一遍转译链并落盘产物，避免"看日志猜产物"。

## 前两轮修复已确认生效（这部分成果是真的）

```
vkdisp: P4.4 覆盖原版 shader: shaders/core/terrain.vsh <- 10217 chars
vkdisp: P4.4 覆盖原版 shader: shaders/core/terrain.fsh <- 35076 chars
vkdisp: P4.4 覆盖汇总: 2/2 个原版 shader 被库存 gbuffers 源替换（零 mixin，未换管线）
```

| 轮次 | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 游戏全黑、进不去 | **把文件路径当源文本**（`Program.vertexShader` 存的是路径 `gbuffers_terrain.fsh`，28 字符）；且无 GLSL 版本护栏 | 覆盖源改取 `CompiledStage.result().text()`；加 `MIN_REQUIRED_GLSL_VERSION=140` 护栏 |
| 2 | 天空仍黑，汇总 `0/2` | `CompiledStage.programName()` 是**维度限定名**（实测 `world1/gbuffers_terrain`），裸名等值匹配永远miss | 后缀匹配 + `dimensionRank`多维度择优（world0 > 根 > 其它） |
| 3 | 覆盖 `2/2` 但仍启动失败 | **架构性证伪**（见下） | 无修法 ⇒ 回退 |

> **教训 A（字段名不告诉你它是什么）**：`Program.vertexShader` / `fragmentShader`
> 存路径不存源码；日志里的 `<- 28 chars` 就是判据。
> **教训 B（覆盖类改动的失败模式是"砸启动"）**：当时单测 630/0 全绿，实跑直接崩 ⇒
> 必须 runClient实跑（`01-DEV-LOOP` §0）。

## 第三轮根因：顶点属性接口契约不兼容（架构性，非分量笔误）

日志原文（13 个地形管线全部命中，solid/cutout/translucent + wireframe + oit × multi-draw）：

```
[resourceLoad/ERROR] [com.mojang.renderpearl.frontend.shaders.PipelineBuilder]:
Couldn't compile pipeline (minecraft:pipeline/solid_terrain):
com.mojang.renderpearl.util.ShaderCompileException: Not enough components for input
attribute UV0 in vertex shader minecraft:core/terrain, expected at least 4 got 2
   at PipelineBuilder.generateBackendCreateInfo(PipelineBuilder.java:169)
Caused by: java.lang.RuntimeException: Failed to load required shader programs
```

`PipelineBuilder.java:167-175` 的判定是 `vertexShaderInput.type().vectorSize() > format.componentCount()`
⇒ **shader 声明的分量数不得超过顶点缓冲元素**。

用产品代码离线跑同一份 BSL 源码（`Probe.java`，产物 sha256 前16位
`56718685db5ab35e` vsh / `52f01b9b305d71ca` fsh，vsh 10197 字符与实跑 10217 吻合），
拿到**真实接口契约**：

| 契约面 | BSL 转译产物声明 | 原版管线提供 | 判定 |
|---|---|---|---|
| vsh 输入 loc0 | `vec4 UV0` | `DefaultVertexFormat.BLOCK` `UV0 = RG32_FLOAT` → **2 分量** | ❌ 分量不足 |
| vsh 输入 loc1 | `vec4 UV2` | `UV2 = RG16_SINT` → **2 分量** | ❌ 分量不足 |
| vsh 输入 loc3 | `vec3 Normal` | `BLOCK` 格式**无 Normal** | ❌ 无匹配元素 |
| vsh 输入 loc5 | `vec4 mc_Entity` | **无此属性** | ❌ 无匹配元素 |
| vsh 输入 loc6 | `vec4 mc_midTexCoord` | **无此属性** | ❌ 无匹配元素 |
| vsh 输出 | 9 个 varying（mat/recolor/texCoord/lmCoord/normal/sunVec/upVec/eastVec/color） | 原版 `core/terrain` 期望 **5 个固定** varying（sphericalVertexDistance / cylindricalVertexDistance / vertexColor / texCoord0 / chunkVisibility） | ❌ 签名不符 |
| fsh 输出 | 1 个（`vkdispFragOut0`） | `ColorTargetState.DEFAULT` = **单附件 RGBA8** | ⚠️ 仅单附件 |

### 为什么"把 vec4 改成 vec2"救不了（首错遮蔽，必须一次看清）

本轮**不收工在首个报错上**——改完分量数还会连续撞三道墙：

1. **`vec4 UV0` 是 vkdisp 自己注入的**（`glsl/translate/LegacyBuiltinInjector` 第148 行
   `in vec4 UV0;`）：OF 方言 `gl_MultiTexCoord0` 是 vec4，而原版顶点缓冲只给 2 分量。
   改成 `vec2` 后 → 撞下一句：`mc_Entity` / `mc_midTexCoord` / `at_tangent`
   三个属性原版 `BLOCK` 格式**根本不存在** ⇒ `does not have a matching vertex buffer element`。
2. **varyings 签名不符**：BSL 9 个 varying vs 原版 5 个固定 varying，且名字完全不同。
3. **附件数不符**：BSL gbuffer 是多输出模型（gbuffer0/1/2 + albedo/normal/mat），
   原版 `core/terrain` 单附件。

⇒ **覆盖 = 替换整份program，而整份 program 的接口必须匹配原版管线固定的顶点格式与附件状态。
BSL 这类第三方 pack 的接口与之结构性不同族。** 这是补差解决不了的路线级不兼容，
不是再加一个护栏能救的。

## 顺带核实（此前 GAP-B 的定量结论，本轮坐实）

`assets/minecraft/shaders/include/globals.glsl` 全文 9 字段（`CameraBlockPos` /
`GlintAlpha` / `CameraOffset` / `GameTime` / `ScreenSize` / `MenuBlurRadius` /
`UseRgss`）—— **无相机矩阵、无太阳方向**。转译层只能塞 `mat4(1.0)` 桩
（`gl_TextureMatrix[n] → mat4(1.0)`），BSL 拿不到真实 gbuffer 投影/视图矩阵。
即使前三道墙都过，画面也不正确（印证"环境缺 uniform ⇒ BSL 半边仍 🟡"）。

## 对 M1 红线的实证支撑（本轮唯一保留的产出）

P4.4-a 的失败**反向证明了 M1 那一个注入点的必要性**：
换掉 `core/terrain` 的 program 文本 ⇒ 必须同时换掉**顶点格式**（`BLOCK` → BSL 的
7 属性布局）与**附件数**（单 → 多），而这两者在零 mixin 下都无入口：
- 换 program 文本：资源包覆盖即可（已证伪于接口契约）
- 换顶点格式：需 `RenderPipeline` 的vertex binding 变更 ⇒ 需接管地形 draw
- 换附件数：`ColorTargetState.DEFAULT` 写死单附件 ⇒ 同上

⇒ 二者**是同一个开关的两面**：M1 唯一允许的注入点
`net.minecraft.client.renderer.chunk.ChunkSectionsToRender#renderLayers`
（private，7 参，末两个是 `renderPipelineOverride`）。P4.4-b 走这条路。

## 复现产物清单

| 文件 | 位置 | 说明 |
|---|---|---|
| `latest.log` | `run/logs/`（gitignored） | 13 个管线失败 + 覆盖 2/2 日志 |
| `probe_world1_vsh.glsl` | `run/vkdisp-probe/`（gitignored） | 转译后 vsh 真实产物（10197 字符） |
| `probe_world1_fsh.glsl` | `run/vkdisp-probe/`（gitignored） | 转译后 fsh 真实产物（28412 字符） |
| `Probe.java` | `tools/vkdisp-shot/`（gitignored） | 离线取证探针，可复跑 |