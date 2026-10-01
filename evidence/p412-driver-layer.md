# P4.1.2 驱动层三跑证据（2026-10-01）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- 进世界后取证：`run/logs/latest.log`（每次 runClient 轮转，旧跑变 `run/logs/<date>-N.log.gz`）；
  截图用 `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`（选 depth=32 窗口，裁客户区）。
- 环境：WSL2，llvmpipe 软件 Vulkan（Mesa 26.2.3，`VK_DRIVER_FILES=lvp_icd.json` 来自 `tools/vulkan-local/prefix`）。
- 客户区口径（三张统计一致）：crop `(x0,y0,x1,y1)=(35,62,w-37,h-39)`，`luma=(r*299+g*587+b*114)//1000`，
  非黑 = `luma>2`，客户区总像素 = 408408（截图整窗 930×577 含标题栏，整窗均值不可比）。

## 三跑对照（同代码路径逐步补绑定/改映射）

| 跑 | 时段 | 变更 | 缺 uniform | fullscreen pass failed | 客户区 mean_luma | 非黑像素 | 判定 |
|---|---|---|---|---|---|---|---|
| run1 | 08:22 | 布局超集已挂（18 采样器进 bind group），draw 侧未 setUniform | **15679** | **15679** | （无截图，draw 全抛） | — | 黑：validateDraw 拒画 |
| run2 | 08:34 | + `setPackSamplerUniforms`（18 名全绑同一 view，colortex0→viewC 空纹理） | 0 | 0 | **0.951** | 1904（0.47%） | 纯黑：绑对了名字、喂错了内容 |
| run3 | 08:46 | + OF 语义映射（colortex0→scene 场景色，gaux1→viewC） | 0 | 0 | **11.901** | 125337（30.69%） | **可见**：BSL composite 链出画 |

run2 → run3 只改了视图映射这一个变量，亮度比 11.901/0.951 ≈ 12.5×，非黑占比 0.47%→30.69% ——
画面变化可归因到映射修复（对照可检伪，P4.1 ⑤ 判据 2）。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/2026-10-01-2.log.gz`（=run1，08:22 段） | `ad3a75d8ebd0e6277e6837affbf24306d5296014a86fb8c71b05c504eedfde9f` |
| `run/logs/2026-10-01-1.log.gz`（=run2，08:34 段） | `7ec2c05e85f4d819b1b18cd1024a1de99bc12fe75508745a1350a75afefcd833` |
| `run/logs/latest.log`（=run3，08:46 段） | `9be796373af697691738ebcda26617be545ab159bb79c5ce5fac249a20ebd70a` |
| `tools/vulkan-local/evidence/p412_world.png`（run2 截图，8356 B，930×577） | `455a45b10ca7366933483434ad5db44ac45e4d010a255b854a65b9ee05e1b84f` |
| `tools/vulkan-local/evidence/p412_world2.png`（run3 截图，27766 B，930×577） | `3c912cb9624b62c984b74f4805c0cb16b2a60df282d57e9b3226cc1aa05aaf37` |

日志轮转对账（内容时间戳实测，非文件名推断）：`-2.log.gz` 内 08:22:50–08:22:55 段 = run1；
`-1.log.gz` 内 08:34:44–08:34:52 段 = run2；`latest.log` 内 08:46:01–08:48 段 = run3。
截图 mtime：`p412_world.png` 08:37（落 run2 窗口内）、`p412_world2.png` 08:47（落 run3 窗口内）。

## 关键日志行（原文）

### run1 — Missing uniform 爆发（15679 次）

```
[01Oct2026 08:22:55.438] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=8, compiled=8 (aligned)
[01Oct2026 08:22:55.441] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: builtins uniform buffer created (bytes=1024, zero-filled until OfUniformManager)
[01Oct2026 08:22:55.441] [Render thread/ERROR] [dev.vkdisp.VkDisp/]: vkdisp: fullscreen pass failed
java.lang.IllegalStateException: Missing uniform colortex0 (should be COMBINED_IMAGE_SAMPLER)
```

计数：`grep -c "Missing uniform colortex0"` = **15679**，`grep -c "fullscreen pass failed"` = **15679**；
run1 vkdisp ERROR（排除 pack program compile）= 15679（全部即此一条类）。

### run2 — 0 错误但黑屏（截图 p412_world.png）

```
[01Oct2026 08:34:44.328] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite source ready: fallback=false pack=BSL_v10.1.8 profile='' bytes=24515 diagnostics=373
[01Oct2026 08:34:44.328] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred source ready: present=true pack=BSL_v10.1.8 bytes=8262
[01Oct2026 08:34:49.039] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/composite stage=FRAGMENT file=world0/composite.fsh spvBytes=76576
[01Oct2026 08:34:49.057] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/deferred stage=FRAGMENT file=world0/deferred.fsh spvBytes=25632
[01Oct2026 08:34:49.278] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack compile done: stages=190 ok=49 failed=141
[01Oct2026 08:34:49.355] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=8, compiled=8 (aligned)
[01Oct2026 08:34:52.298] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred chain wired: scene -> offscreen2 -> main (pack deferred)
[01Oct2026 08:34:52.298] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite input source: deferred output (P3.3)
```

计数：`Missing uniform` = 0，`fullscreen pass failed` = 0，vkdisp ERROR（排除 pack program）= 0。
截图客户区：`mean=0.951 max=204 nonblack=1904 (0.47%)`，luma 桶 `[(0, 406504), (204, 1904)]`
—— 406504 像素全黑 + 1904 个 luma=204（HUD/文字残留），场景纹理无内容。

### run3 — 可见（截图 p412_world2.png）

```
[01Oct2026 08:46:01.489] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite source ready: fallback=false pack=BSL_v10.1.8 profile='' bytes=24515 diagnostics=373
[01Oct2026 08:46:01.489] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred source ready: present=true pack=BSL_v10.1.8 bytes=8262
[01Oct2026 08:46:05.699] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/composite stage=FRAGMENT file=world0/composite.fsh spvBytes=76576
[01Oct2026 08:46:05.717] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack program compiled OK: pack=BSL_v10.1.8 program=world0/deferred stage=FRAGMENT file=world0/deferred.fsh spvBytes=25632
[01Oct2026 08:46:05.947] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack compile done: stages=190 ok=49 failed=141
[01Oct2026 08:46:06.025] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=8, compiled=8 (aligned)
[01Oct2026 08:46:09.219] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred chain wired: scene -> offscreen2 -> main (pack deferred)
[01Oct2026 08:46:09.219] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite input source: deferred output (P3.3)
```

计数：`Missing uniform` = 0，`fullscreen pass failed` = 0，vkdisp ERROR（排除 pack program）= 0。
截图客户区：`mean=11.901 max=204 nonblack=125337 (30.69%)`，luma 桶
`[(0, 283071), (42, 11571), (38, 9729), (41, 8612), (45, 7736), (35, 7531)]`
—— 亮度分布在 35–45 有连续谱 = 真实场景内容（非 HUD 文字）。

## pack 编译矩阵（run3，`stages=190 ok=49 failed=141` 分类学）

FAILED 行 = 141 = **VERTEX 91 + FRAGMENT 50**（首错分类，python 逐行归类）：

| 阶段 | 首错 | 条数 | 涉及 program |
|---|---|---|---|
| VERTEX | `'location' : SPIR-V requires location for user input/output` | 36 | gbuffers/dh/shadow 系 .vsh（另有 30 条同文续行，'location' 全文共 66 行、**全部 .vsh**） |
| VERTEX | `'gl_MultiTexCoord0' : undeclared identifier` | 33 | composite×8 + deferred×2 + final（×3 维度）——**我们的管线配自造 fullscreen 顶点，此失败不入链** |
| VERTEX | `'gl_TextureMatrix' : undeclared identifier` | 20 | gbuffers_armor_glint/beaconbeam/clouds/damagedblock/spidereyes/weather/skytextured |
| VERTEX | `'Position' : undeclared identifier` | 2 | world0/world1 gbuffers_skybasic |
| FRAGMENT | `'texture' : can't use function syntax on variable` | 44 | gbuffers/dh 系 .fsh（OF 方言 texture() 函数化调用） |
| FRAGMENT | `'gbufferProjectionInverse' : redefinition` | 6 | dh_terrain/dh_water .fsh（×3 维度） |

**composite/deferred FRAGMENT 失败 = 0** —— 实际入链的两个 pack program 每轮都
`pack program compiled OK`（spvBytes=76576 / 25632）。141 个失败全部属于
gbuffers/dh/final/shadow 系（P4.2 切包回归范围），不阻 P4.1 判据。
三跑矩阵逐字节一致（stages=190 ok=49 failed=141 各轮相同），翻译层输出稳定。

## 判定（对照 18-PARALLEL §5 P4.1 ⑤ 完成判据）

| # | 判据 | 结果 |
|---|---|---|
| 1 | 管线 registered==compiled | ✅ 三跑均 `registered=8, compiled=8 (aligned)` |
| 2 | 进世界 BSL 视觉生效（对照 fixture 基线可检伪） | ✅ run2 黑（0.951/0.47%）→ run3 可见（11.901/30.69%），单变量（视图映射）归因；P2.4 fixture 基线 mean_luma 6.3813，run3 11.901 量级合理 |
| 3 | 与 Iris 对比截图 | ⚠️ **环境缺 Iris = 已登记限制**（18-PARALLEL P4.1 ⑤ 原文预留），本机不装 Iris，不伪造对比 |

## 本轮修复与证据的对应（四修）

| 修 | 层 | 内容 | 消掉的证据 |
|---|---|---|---|
| A | 链接 | `fullscreen.vsh`/`fullscreen_flipv.vsh` 增 `layout(location=1/2) out sunVec/upVec`（=vec3(0) 初值） | 片元输入 location 接口不匹配（PipelineBuilder :204 逐 location 查） |
| B | 反射 | `PipelineApi.PACK_FRAGMENT_SAMPLERS` 18 名进片元 bind group 布局超集（:277 只查 SPIR-V→layout，布局多项合法） | `Unable to find shader defined uniform` |
| C | draw | `PipelineApi.setPackSamplerUniforms` 在 deferred/composite 两处 draw 前 setUniform 全部 18 名（validateDraw 遍历 boundPipeline.uniforms 全量） | run1 的 15679× `Missing uniform colortex0` |
| D | 映射 | 链内 colortex0→`sceneColorView()`（场景色），gaux1→`viewC`（OF 身份 colortex4）；deferred 只读 scene | run2 的 0 错误纯黑（colortex0 喂了空纹理） |

## 未覆盖（如实登记）

- **141 个 pack 阶段失败**（上表全部类目）：gbuffers/dh/final VERTEX 三类 + gbuffers/dh FRAGMENT
  texture 方言/重定义两类 —— 属 P4.2 切包回归范围，本轮不修；
- composite/deferred **VERTEX** gl_MultiTexCoord0 失败虽在矩阵内，但主线管线配自造 fullscreen
  顶点，该阶段不入链（未覆盖 ≠ 无影响：P4.2 若启用 pack 顶点需先修）；
- uniform 数值仍全零上传（OfUniformManager 缺口）→ sunVec 系效果可能 NaN；
- 无 final/tonemap 步（deferred 链后直接上主目标，色调为 raw）；
- 深度/噪声/阴影/3D 纹理均为占位绑定（同 view 系）；
- profiles 解析失败照旧（`#if` 表达式含 `>`）；
- Iris 对比截图 = 环境限制（判据 3）；
- OpenAL `IllegalStateException` 与 authlib 拉取日志 = 环境噪音，与 vkdisp 无关。
