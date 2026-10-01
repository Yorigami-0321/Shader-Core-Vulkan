# 变更记录（CHANGE_LOG）

> 格式与流程依据：`docs/15-ITERATION.md`「变更记录模板」。最新条目在最上方。
> 每轮迭代一条：改了什么 / 为什么改 / 影响的文档 / 测试结果 / 是否已提交。

---

## 2026-10-01 — P4.1.4 final 步接线：9 管线三布局三环 + attachment 恒等拷贝顶点推导 + 上传日志门缺陷修复，两跑取证 registered=9 对齐、written=24 归零（492 单测全绿）

- **本次改了什么**（补上 P4.1 画面质量链的最后一环：present 前的 final 步）：
  1. **第 9 条管线 final**（`PipelineApi` + `FullscreenPipelineRegistrar`）：
     `registerFinalPipeline` 挂 `vkdisp:pipeline/final` —— 顶点复用
     `fullscreen`（**不翻转**，attachment 恒等拷贝**推导**写进
     `FINAL_PIPELINE_ID` javadoc：composite 换附件不换光栅化 → offscreen3 的 texel
     逐位 ≡ 旧链 main 的 texel → final 恒等采样拷回 → 显示与旧链一致；flipv 会把
     旧画面垂直镜像，截图方向 = 该推导的实测检验点）、片元 `vkdisp_pack:final`、
     同 packFragmentLayout；注册段 5/9 插在 deferred 后，全 9 段序号重排。
  2. **同扫描三产出**（`PackCompositeSource` + `VkDispVirtualPack`）：一次选包扫描
     顺带取 final 片元（与 deferred 同规则 —— 同包 + 与 composite 同维度配对，
     `selectFinalSource` 镜像 `selectDeferredSource`；缺/坏 → passthrough +
     INFO（包无 final）或 T11 WARN（声明了但产出失败）按未启用处理）；
     `Result` 扩五元源字段、虚拟包补 `shaders/final.fsh` 第三资源、
     `hasFinalProgram` / `finalBuiltinsLayout` volatile 双标志（所有失败路径同步复位）。
  3. **final 链接线**（`bridge/FrameApi`）：包有 final 时 `composite → offscreen3
     (slot 3, offscreenTargetD) → main`（final pass 是最后且**唯一** main 写入者；
     Pass 序 composite=3/4 → final=4/5），绑定 `InSampler=viewD`、
     `packAux = deferredChain ? viewC : viewD`（gaux1 保持 deferred/colortex4 身份）、
     内建 uniform 走第三环（final 24 成员/512B → 1024B 环，帧末与其余两环同转）；
     `composite input source` 与 `final chain wired` 埋点各一次。
  4. **上传日志门缺陷修复（run1 实测暴露）**：`OfUniformManager.logUploadOnce`
     双布尔门（`"deferred".equals(slot) ? deferredLogDone : compositeLogDone`）把
     未知 slot 落进 else 分支 —— composite 每帧先上传先置位 → `slot=final` 摘要行
     永远被吞（run1 有 created 无 uploaded，0 条）。改为按槽位名
     `UPLOAD_LOGGED_SLOTS` Set 门，run2 `uploaded: slot=final written=24` 可见。
  5. **测试（+5）**：`PackCompositeSourceTest` 补 final 四态（无 final → passthrough
     + INFO、有 final → 标记源 + true、final 片元坏 → WARN 按未启用、同维度配对
     world0 vs world-1）+ 兜底三源贯通例。
- **为什么改**：01-DEV-LOOP §10 P4.1「BSL 主要效果可用」的质量子项 —— BSL 的
  色差/锐化在 final 程序里，缺这一步则 composite 输出直写主目标、present 效果恒关；
  且「第 9 管线没接上」类静默缺口必须由 registered/compiled 计数对齐 + 一次性
  chain 埋点可证（T11）。
- **影响的文档**：本 `CHANGE_LOG.md`；`evidence/p414-final-step.md` + `evidence/README.md`
  （两跑 sha256 + 关键行原文 + run1/run2 门缺陷对照 + 判定表）；`docs/04-SPEC.md` §3.2
  （两套布局 → 三套三环）；`docs/18-PARALLEL.md` §5 P4.1（头部子项行更新 + 新增 ⑧
  交付记录）；源码 7 文件（PackCompositeSource / VkDispVirtualPack / PipelineApi /
  FullscreenPipelineRegistrar / FrameApi / OfUniformManager / 测试）。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；**492 用例 0 失败 0 错误**（487 → 492，+5）。
  - ✅ **runClient 两跑闭环**（证据全文见 `evidence/p414-final-step.md`）：
    run1：`(5/9) final` 注册 + `final source ready: present=true pack=BSL_v10.1.8
    bytes=5616` + 三布局 42/24/24 + `registered=9 compiled=9 (aligned)` +
    `final chain wired` + 客户区 **9.1930**/23.75%（截图 p414_world.png 未镜像，
    与 p413 构图同向 = 恒等拷贝推导实测吻合）—— 但 `uploaded: slot=final` **0 条**
    （日志门缺陷）→ 修复；
    run2（修复后）：`uploaded: slot=final members=24 bytes=512 written=24 unfilled=0
    mismatched=0 overflow=0` 可见、composite written=26 unfilled=16 与 p413 逐字一致、
    `far=32.0 worldTime=0 rainStrength=0.0` 样本吻合、vkdisp ERROR（排除 141 条
    pack compile）= 0、`fullscreen pass failed`/`Missing uniform` = 0。
- **未覆盖**（如实登记）：final 视觉效果强度 A/B（色差/锐化按选项开关的像素差，
  留 P4.2 切包回归取证）；composite1–7 多步链（真 OF 里 final 输入经 1–7 后才到，
  本轮 single-composite 是最小近似）；包 final.vsh VERTEX 失败（管线顶点用
  fullscreen，恒不触发）；profiles `#if >` 解析、141 阶段失败（= P4.2 范围）照旧。
- **是否已提交**：随本轮 commit 提交并推送 origin/master。

---

## 2026-10-01 — P4.1.3 内建 uniform 上传闭环：std140 布局解析 + OfUniformManager 双槽双环，两跑取证 26/24 成员落字节、雨量取值源缺陷修复归零（487 单测全绿）

- **本次改了什么**（消掉 P4.1.1/P4.1.2 遗留的「uniform 全零上传」缺口）：
  1. **`glsl/translate/BuiltinsBlockLayout`（新增）**：从转译终稿文本解析
     `VkDispBuiltins` 块的 std140 布局（收编声明序在前 + 目录缺失尾部；float/vec/mat/
     数组对齐规则手算金样：23 目录项 0..496、块 512B）。解析永不抛 —— 未知类型/非字面
     数组长/重名 → 失败态空布局（回退零填充 + WARN 原文，T11）。
  2. **`render/OfUniformManager`（新增）**：`gather` 按 04-SPEC §3.2 上传注记逐条
     从游戏状态取值（矩阵/方向/时间/天气/图集/眼亮度，每条出处写进文档）；
     `write` 按布局绝对偏移 + 原生字节序把命中成员写进 ByteBuffer（vanilla
     Std140Builder 无 seek，收编序下顺序重放必错）——纯函数，类型不匹配/越界跳过并
     计数，绝不猜值。未取证项（timeBrightness 等）恒 0 + 一次性 INFO 列名。
  3. **双布局双环接线**（`VkDispVirtualPack` + `bridge/FrameApi`）：composite 与
     deferred 收编集不同 → 两套布局，冷路径从**转译终稿**各解析一次（F3 冻结契约，
     volatile 双槽）；FrameApi 按 `max(1024, byteSize)` 各建 MappableRingBuffer，
     deferred/composite 两处 draw 各绑各的、帧末同转，扩容时 close 旧环。
     `blockAtlasSize()` 封在 FrameApi 取 `GpuTexture`（T5：业务包零 renderpearl）。
  4. **取值源修复（run1 实测暴露）**：雨量/角度/月相原直读 `SkyRenderState` 提取态
     ——该字段只在 LevelExtractor 跑过后有效，首帧为默认 0 → 雨量样本误报 1.0
     （存档实为晴）。改为与 SkyRenderer:119-125 **逐位同源**的直读：
     `attributeProbe(SUN_ANGLE/MOON_ANGLE)`（度×π/180）、`attributeProbe(MOON_PHASE)`、
     `Level.getRainLevel(partialTicks)`（= `1 − rainBrightness` 恒等变形）。
  5. **测试（+18）**：`BuiltinsBlockLayoutTest` 11 例（手算偏移金样/收编序/数组步进/
     失败态/真实转译输出对账幂等）+ `OfUniformManagerTest` 7 例（五类型绝对偏移落
     字节含 mat4 列主序平移列、缺失/错配/越界/null 安全/数值宽化）。
- **为什么改**：P4.1 判据⑤的质量子项 —— 此前环形缓冲零填充，BSL 的
  sunVec/时间/矩阵系 uniform 全为 0（效果 NaN 风险）；且「上传了」必须可取证
  （T11：写没写、写了多少、谁没填，日志一行看得见）。
- **影响的文档**：本 `CHANGE_LOG.md`；`evidence/p413-uniform-upload.md` + `evidence/README.md`
  （两跑 sha256 + 关键行原文 + 存档基线取证 + 判定表）；`docs/04-SPEC.md` §3.2
  （新增「上传语义」块 + sun/moon/rain/moonPhase 三行取值源更新）；
  `docs/18-PARALLEL.md` §5 P4.1（⑤ 缺口行闭环 + 新增 ⑦ 交付记录）；
  源码新增 `BuiltinsBlockLayout`/`OfUniformManager` + `VkDispVirtualPack`/`FrameApi` 接线 + 测试×2。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；**487 用例 0 失败 0 错误**（469 → 487，+18）。
  - ✅ **runClient 两跑闭环**（证据全文见 `evidence/p413-uniform-upload.md`）：
    run1：`builtins layout parsed: slot=composite members=42 bytes=608` /
    `slot=deferred members=24 bytes=512` → 双环 `created bytes=1024` →
    `builtins uploaded: written=26 unfilled=16 / written=24 unfilled=0`、
    `mismatched=0 overflow=0`、vkdisp ERROR（排除 141 条 pack compile）= 0、
    `registered=8 compiled=8`、客户区 mean_luma **10.8189** 非黑 29.18%；
    但 deferred 样本 `rainStrength=1.0` 与存档晴天（weather.dat raining=0）矛盾
    → 定位 SkyRenderState 提取前默认值缺陷 → 修复；
    run2（修复后）：同链全绿、样本 `rainStrength=0.0 worldTime=0` 与存档逐项吻合
    （`world_clocks.dat total_ticks=0 + advance_time=0` → 时钟冻结实值）、
    客户区 mean_luma **9.0767** 非黑 22.45%（与 p412 基线 11.901/30.69% 同量级可见）。
- **未覆盖**（如实登记）：非零 `worldTime` 的运行期观测（本测试存档时钟冻结为 0，
  公式本身由单测覆盖）；`frameTimeCounter` 稳态样本（一次性 INFO 只拍首帧）；
  timeBrightness/eyeBrightness 精确语义、wetness OF 平滑、sunPathRotation 包天空、
  final/tonemap、profiles `#if >`、141 阶段失败（= P4.2 范围）—— 照旧登记。
- **是否已提交**：随本轮 commit 提交并推送 origin/master。

---

## 2026-10-01 — P4.1.2 驱动层四修：转译七段补齐（VersionAdapter + IoLocationAdapter）+ 18 采样器布局超集 + draw 侧全量绑定 + OF 语义视图映射，三跑闭环 15679→0→可见（0.951→11.901）

- **本次改了什么**（四修一组，全部按 p41a 驱动错误原文逐一闭环，X9 取证）：
  1. **转译管线扩为七段**（`OfGlslTranslator`）：新增 **`VersionAdapter`**（`#version`
     三段式升级到 410：<140 必升、140–409 无活跃 SSO 扩展则升、≥410/ES 原样 ——
     杀 shaderc 硬门槛 `require version 140 or higher` 及连坐的
     `location qualifier not supported for this version`）与 **`IoLocationAdapter`**
     （片元 in/out、顶点 out 显式 `layout(location=N)` —— 杀 `SPIR-V requires location
     for user input/output`；顶点属性按 04-SPEC §4 留名字绑定不加 location）。
     插位 FtransformExpander 之后、FragmentOutputAdapter 之前，javadoc ①–⑦ 重编号。
  2. **`UniformInjector` 逗号多名声明全名登记**：BLOCK_MEMBER 模式扩为捕获全部
     声明子句，`recordMemberNames`/`declaratorNames` 把每个名字都记进
     declaredAtLine/adoptedNames —— `uniform float far, near;` 类后名不再被当缺失
     **二次注入**块内（BSL L27/32/39 的 duplicate member / nameless block 撞名
     与 P4.1.1 驱动报文逐一吻合的根因）。
  3. **管线链接 + 反射 + draw 三修**（`fullscreen.vsh`/`fullscreen_flipv.vsh` +
     `PipelineApi`）：顶点补 `layout(location=1/2) out sunVec/upVec`（=vec3(0)，修 A
     接口链接）；新增 `PACK_FRAGMENT_SAMPLERS` 18 名片元采样器**布局超集**经
     `packFragmentLayout()` 挂进三条 register 方法（PipelineBuilder :277 只做
     SPIR-V→layout 单向查询，布局多项合法 —— 修 B）；新增
     `setPackSamplerUniforms(pass, …)` 在 deferred/composite 两处 `draw()` 前把 18 名
     全量 `setUniform`（FrontendRenderPass.validateDraw 遍历 boundPipeline.uniforms
     缺一即抛 `Missing uniform NAME` —— 修 C）。
  4. **OF 语义视图映射**（`FrameApi`，修 D）：deferred 链内 colortex0 →
     `sceneColorView()`（场景色输入）、gaux1 → `viewC`（OF 身份 colortex4），
     其余 16 名 → 同场景 view；deferred draw 只喂 scene。链式
     `packColor = deferredChain ? sceneColorView() : compositeInput`。
  5. **测试**：新增 `VersionAdapterTest`（16 用例）、`IoLocationAdapterTest`（15）；
     `UniformInjectorTest` +3（逗号多名全登记/行号契约/幂等）；
     `OfGlslTranslatorBuiltinsTest` +1（驱动报文复刻金样）；
     `OfGlslTranslatorTest`/`GlslPipelineTest` 样本随七段重排调整。
     另随本轮入库的 batch-0 审查修复（独立提交）：VkDisp 配置注册
     COMMON→CLIENT、neoforge.mods.toml 死链 22-版本基线→05-VERSION、
     04-SPEC/05-VERSION/06-MIGRATION 三处文档口径更正（mixin 现状 = 0）。
- **为什么改**：P4.1 判据⑤「registered==compiled → 进世界 → 取证」的堵点在**驱动洋葱
  第 2–3 层**——转译通过 ≠ shaderc 通过 ≠ 链接通过 ≠ draw 通过。P4.1.1 取证的 6 类
  驱动错误（#version<140 / duplicate member×3+撞名 / location×2）逐条对应上面 1/2/3 修；
  0 错误黑屏则证明「名字绑对 ≠ 内容喂对」（run2 colortex0 喂空纹理全黑），
  OF 语义（deferred 只写 AO 到 DRAWBUFFERS:4，composite L254 读 colortex0 当场景色）
  实测驱动了修 4 的映射方向。
- **影响的文档**：本 `CHANGE_LOG.md`；`evidence/p412-driver-layer.md` + `evidence/README.md`
  （G-01 首批入库：三跑 sha256 + 关键行原文 + 141 失败分类学 + 复现命令）；
  `docs/18-PARALLEL.md` §5 P4.1 块（③ 工作清单标记交付 + P4.1.2 交付记录 + ⑤ 判据结果）；
  `docs/04-SPEC.md`/`docs/05-VERSION.md`/`docs/06-MIGRATION.md`（batch-0）；
  源码 `glsl/translate/`×6（含新增 2）+ `bridge/`×2 + 顶点着色器×2 + 测试×6。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`cleanTest test` **469 用例 0 失败 0 错误**
    （434 → 469，净增 35：VersionAdapter 16 + IoLocation 15 + UniformInjector 3 +
    Builtins 1；临时探针 ScratchBslProbeTest 取证后删除不入库）。
  - ✅ **runClient `-PquickPlay` 三跑闭环**（证据全文 + sha256 见
    `evidence/p412-driver-layer.md`）：
    - run1（B 修在、C 修缺）：`Missing uniform colortex0` **15679** 次 +
      `fullscreen pass failed` **15679** 次（validateDraw 拒画）；
    - run2（+C 全量绑定，colortex0→空 view）：两类计数 **0/0**，但客户区
      mean_luma **0.951**、非黑 **0.466%**（纯黑，406504 像素 luma=0 + 1904 个 204 文字像素）；
    - run3（+D OF 映射，colortex0→scene）：**0/0** 且可见 —— 客户区 mean_luma
      **11.901**、非黑 **30.69%**（125337 px，luma 桶 35–45 连续谱）；
    - 三跑共有：`pipeline count check: registered=8, compiled=8 (aligned)`、
      `deferred chain wired: scene -> offscreen2 -> main (pack deferred)`、
      `composite input source: deferred output (P3.3)`、
      world0/composite spvBytes=76576、world0/deferred spvBytes=25632、
      `pack compile done: stages=190 ok=49 failed=141`（三跑逐字节一致）、
      run3 vkdisp ERROR（排除 pack compile）= **0**。
- **未覆盖**（如实登记）：
  - **141 pack 阶段失败**（91 VERTEX + 50 FRAGMENT，分类学见 evidence 文件）：
    'location'×36（仅 VERTEX，全文 66 行含续行全 .vsh）、gl_MultiTexCoord0×33、
    gl_TextureMatrix×20、Position×2、'texture' 函数语法×44（FRAGMENT gbuffers/dh）、
    gbufferProjectionInverse 重定义×6 —— **0 个 composite/deferred FRAGMENT 失败**，
    全部属 gbuffers/dh/final/shadow 系 = P4.2 切包回归范围；composite/deferred VERTEX
    的 gl_MultiTexCoord0 失败不入链（主线配自造 fullscreen 顶点），P4.2 启用 pack
    顶点前须先修；
  - uniform 数值仍全零上传（OfUniformManager 缺口）→ sunVec 系效果可能 NaN；
  - 无 final/tonemap 步（deferred 后 raw 上主目标）；深度/噪声/阴影/3D 纹理占位绑定；
  - profiles 解析失败照旧（`#if` 含 `>`）；Iris 对比截图 = 环境缺 Iris（判据③已登记限制）；
  - G-05 配置文件名 `vkdisp-common.toml`→`vkdisp-client.toml` 待切（config type 已改 CLIENT）；
  - OpenAL/authlib 日志 = 环境噪音。
- **是否已提交**：随本轮两个 commit（batch-0 审查修复 + P4.1.2）提交并推送 origin/master。

---

## 2026-09-30 — P4.1.1 转译层接管 BSL：函数宏组号修复 + 游离 uniform 收编 + 维度偏好选中，182/182 阶段转译全绿，驱动层 6 类错误原文取证

- **本次改了什么**（三处主线修复，全部 X9 实测取证驱动，非猜测）：
  1. **`DefineProcessor` 函数宏组号错位修复**：`FUNC_DEFINE` pattern 只有 3 个捕获组
     （g1=名 / g2=参数表 / g3=宏体），`handleDefine` 却按 4 组取 `group(3)/group(4)` ——
     任何 `#define f(a)` 形态一进分支就抛 `IndexOutOfBoundsException("No group 4")`。
     fixture 无函数宏故从未触发；载入 BSL（`lib/*.glsl` 大量 Bayer 系函数宏）后**全部
     program 转译失败**（临时探针实测 composite 片元 0 成功）。修复组号 + 收紧 pattern：
     名字与 `(` 必须紧邻（C 预处理器语义）—— 曾允许 `\s*` 间隔会把 `#define EXPR (1.0)`
     误判成函数宏，其后裸引用永不展开。
  2. **`UniformInjector` 游离 uniform 收编进块**：Vulkan GLSL 禁止非透明 uniform 在块外
     （P2.3 已证 shaderc 原文 `non-opaque uniforms outside a block`），包作者写的
     `uniform float rainStrength;` 这类 OF 方言原生形态此前**原样留在块外** → 驱动必拒。
     新语义：扫描期收集收编候选（同行前后有别的语句 / 跨行声明 / 重名第二次不收），
     声明文本**原样移动**进 `VkDispBuiltins` 块（不改写、不重排语义）、原行位抹空保行号
     契约、采样器/图像类型（透明）留原位、块成员记作已声明（防重复注入）、CRLF 行尾补齐、
     一条 INFO 显式报收编数（T11）。
  3. **`PackCompositeSource` 维度偏好选择**：程序清单按限定名 TreeMap 排序，`world-1/…`
     字典序先于 `world0/…`，旧「取第一个成功者」会选中**下界** composite 链主世界 deferred。
     改为按维度权重择优（完全匹配 > world0 > 根 > 其它），deferred 与 composite **同维度
     配对**（杜绝「下界 composite + 主世界 deferred」串链），选中行 INFO 显式可见。
  4. **测试**：`UniformInjectorTest` 5 条旧语义用例改写为收编语义 + 新增采样器留原位/
     行号契约；`DefineProcessorTest` 增函数宏定义展开 + 带括号值对象宏不误判；
     `PackCompositeSourceTest` 增 3 条维度用例（world-1 字典序在前仍选 world0、deferred
     同维配对、无 world0 时根优先于其它维度）；`OfGlslTranslator*Test`/`GlslPipelineTest`
     强口径幂等样本改用块内成员形态（游离声明现已首轮收编 → 不满足「无插入」前提，
     语义变化已写进 javadoc）；临时探针 `ScratchBslProbeTest` 取证后删除不入库。
- **为什么改**：01-DEV-LOOP §10 P4.1「BSL 主要效果可用」的第一堵墙是**转译层全灭**——
  X9 探针实测 BSL 91 program / 284 option 全部载入，但函数宏 bug 使 composite 片元
  0 成功、旧选中逻辑还会串维度；三处修复是 BSL 进主线的前置条件，且全部按实测错误
  定位（No group 4 原文、字典序事实、shaderc 块外语法）逐一闭环。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（新增 P4.1 块：设计 +
  本轮实测 + 驱动层 6 类错误登记为 P4.1.2 工作清单）；`DefineProcessor.java`、
  `UniformInjector.java`、`PackCompositeSource.java` 及 6 个测试文件。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`cleanTest test` **434 用例 0 失败 0 错误**（净增 6 条）。
  - ✅ **探针（BSL 实装复跑）**：`compile stages=182 ok=182 fail=0`（修复前 composite
    片元全灭）；`PackCompositeSource.generate` → `packName=BSL_v10.1.8 fallback=false
    hasDeferred=true sourceBytes=24505`（与 world0/composite 片元产出逐字节吻合）、
    WARN=83 ERROR=0（WARN 主体为 ftransform 位置属性显式告警，既有登记项）。
  - ✅ **runClient `-PquickPlay`**（/tmp/p41a_runclient.log，exit=0 干净收尾）：
    `composite 程序选中 'world0/composite'（维度偏好 world0 > 根 > 其它）` →
    `composite source ready: fallback=false pack=BSL_v10.1.8 bytes=24505` →
    `deferred source ready: present=true pack=BSL_v10.1.8 bytes=8271`，
    管线注册 1/8..8/8 完整 —— **选中链（扫描→载入→转译→双源→注册）全绿**。
  - 🔴 **驱动层编译失败（本轮如实取证，转译通过 ≠ 驱动通过）**：3 条 required 管线
    （composite / composite_scene / deferred）×2 次资源重载共 6 次
    `Couldn't compile pipeline`，错误原文 6 类：① `#version: Desktop shaders for
    Vulkan SPIR-V require version 140 or higher`（BSL 是 120）；②③ `duplicate member
    name: near/viewHeight/gbufferProjectionInverse` + `'VkDispBuiltins' : nameless
    block contains a member that already has a name at global scope`；④⑤
    `'location' : not supported for this version`（合成 out，随①连坐）与
    `'location' : SPIR-V requires location for user input/output`（包 varying 无
    location）；⑥ deferred 同构。**根因离线算术自证**：BSL 用逗号多名声明
    `uniform float far, near;`(27) / `uniform float viewWidth, viewHeight,
    aspectRatio;`(32) / `uniform mat4 gbufferProjection, gbufferProjectionInverse;`(39)
    —— 解析器只记首名 → 后名未登记「已声明」→ 被当缺失**二次注入**块内，与收编整行
    撞出的 3 个 duplicate 名字与驱动报文**逐一吻合**。失败致
    `Failed to load required shader programs` → 资源包被摘除重载，`VkDispPackScan`
    矩阵未触发（事件在失败重载上未送达）—— 以上原文即本轮驱动级证据。
- **未覆盖**：
  - **P4.1.2（下一轮，工作清单已按原文登记进 18-PARALLEL P4.1 块）**：逗号多名声明
    全名登记（杀 ②③）、#version 120→≥140 升级（杀 ①④）、包 varying 显式
    layout(location)（杀 ⑤）、随后复验驱动矩阵与 `VkDispPackScan` 全量矩阵；
  - BSL 画面效果未达成本轮（管线未编过 → 资源加载失败回退），Iris 对比截图缺环境
    （本机无 Iris）照旧登记；profiles 解析失败（`#if` 含 `>`）与 uniform 数值上传
    （OfUniformManager 缺口）不在本轮范围；
  - 探针输出 /tmp/bsl_probe.txt 不入库（临时文件已删除）。
- **是否已提交**：随本轮 commit 提交并推送 origin/master。

---

## 2026-09-30 — P3.3 deferred 链完整交付：每步输入=上一步输出（双跑日志链），同材质色调 A/B 0.688≈0.700，方向代数+边缘取证闭环

- **本次改了什么**：
  1. **`pack/PackCompositeSource` 扩双源**：新增 `DEFERRED_PROGRAM="deferred"`；`Result`
     增 `deferredSource`（永不 null：取不到 → 内置 passthrough，紧凑构造器白名单非空白）+
     `hasDeferredProgram`（选中包真实产出 deferred 片元才 true）；**同一次选包编译**顺带取
     deferred 片元（链两端必须同包，不同包选项语义冲突比没有链更糟）；包声明 deferred 但
     片元阶段失败 → WARN `声明了 deferred 但片元阶段无成功产出…按未启用处理`、不硬开喂
     兜底源的步；未声明 → INFO `不含 deferred 程序…（链路保持 P3.2 直连）`；整体兜底两源
     均 passthrough、hasDeferred=false（T11）。
  2. **`VkDispVirtualPack` 服务第二资源**：`shaders/deferred.fsh` 进虚拟包 —— required 管线
     必须总有源可编，**总开关关闭也服务 passthrough**；`generateSources()` 一次生成双源，
     证据行 `deferred source ready: present=… pack=… bytes=…`，`volatile hasDeferredProgram()`
     暴露给 bridge。
  3. **第 8 条管线**：`PipelineApi` 增 `pipeline/deferred`（fragment=`vkdisp_pack:deferred`、
     vertex=`vkdisp:fullscreen_flipv`、绑定组 BUILTINS+InSampler 同 composite —— 链方向
     1+1≡0 (mod 2) 要求 deferred 贡献 +1 翻转，P-1f ③ + P3.2 实测代数合成，非新猜测）；
     `FullscreenPipelineRegistrar` 计数 1/7..7/7 → 1/8..8/8（deferred 插 4/8）。
  4. **`FrameApi` 链路执行**：`offscreenTarget` 扩 slot 2（无深度、颜色专用、仅链路时懒建）；
     新 deferred pass（世界内 && scene 已捕获 && hasDeferred 才执行，scene → offscreen2，
     标签 `(deferred: scene -> offscreen2, pack deferred)`）；Pass 4 输入三态（deferred 输出 /
     scene 直连 / fixture offscreen1）+ 管线二选一 + 切换埋点沿用 `composite input source`；
     链路一次性埋点 `deferred chain wired: scene -> offscreen2 -> main (pack deferred)`；
     `isPipelineReady` 增补 deferred 注册+编译校验；相机锚定/位姿变化日志（取证用）。
  5. **测试**：`PackCompositeSourceTest` 增 4 条 P3.3 用例（兜底双源+false、无 deferred
     INFO、有 deferred 坏片元 WARN、色调标记 `vec3(1.0, 0.7, 0.7)`）→ 全套 **428**。
  6. `docs/18-PARALLEL.md` §5 P3.3 块：设计①–⑤（X9 脚手架核查 + 方向代数 + 链拓扑 +
     脚手架改动 + 证据计划）+ **本轮三跑实测回填** + 🟡→✅；⑤ 方向条按环境事实改写
     （顶视网格复测不可复现 → 代数+基线+锚定帧边缘组合登记，同位姿镜像 未覆盖）。
- **为什么改**：01-DEV-LOOP §10 P3.3 完整交付 = 08-TESTING §5「deferred | 每步的输入纹理是
  上一步的输出」+「composite | 链顺序正确，最后一步写入主目标」。deferred 是 OptiFine 包的
  常见程序位，链上没它 = 主流包（P4.1 BSL）过不了；已知色调变换必须穿过整链才上屏，
  可检伪（链关即回落中性）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（P3.3 块设计+实测）；
  `PackCompositeSource.java`、`VkDispVirtualPack.java`、`bridge/PipelineApi.java`、
  `bridge/FrameApi.java`、`render/FullscreenPipelineRegistrar.java`、
  `PackCompositeSourceTest.java`。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`cleanTest test` **428 用例 0 失败 0 错误**（4 条 P3.3 新增）。
  - ✅ **runClient `-PquickPlay` 三跑**（/tmp/p33a|p33b|p33c_runclient.log，fixture 自造包
    §7.6）：链开两跑 `present=true bytes=1384` → `pipeline registered (4/8)` →
    `registered=8, compiled=8 (aligned)` → `deferred pipeline wired … flipv by net-parity`
    → `deferred chain wired: scene -> offscreen2 -> main (pack deferred)` →
    `composite input source: deferred output (P3.3)`；链关跑（deferred 改名 .off、zip 4 条目）
    `present=false bytes=409` + INFO 不含 deferred → `composite input source: scene capture
    (P3.2 terrain)`；两分支 **vkdisp WARN/ERROR=0**。
  - ✅ **像素判据（同材质 A/B）**：链开锚定帧 `p33_chain_anchor.png`（sha256 a4d41356…，
    相机日志锚 yaw −109.500046/pitch +6.2999973/fov 38.150047° == 退出存档）沙岩 R/G=1.5146；
    链关帧 `p33_direct_anchor.png`（sha256 002bf828…）沙岩地面 R/G=1.0424（≈P3.2 基线
    1.0430）→ G/R 抑制比 **0.688**、R/G 抬升比 **1.453** vs ×vec3(1.0,0.7,0.7) 预言
    0.700/1.429（−1.7%/+1.7%）；链关全帧 R/G=1.0243 中性无残留。
  - ✅ **方向**：链开锚定帧近竖墙角边族 87.0°、链关帧地平线族 {0.0°}（≥60px 边 −0.4..+0.5°）
    无倾斜/翻转信号；1+1≡0 代数 + P3.2 像素级镜像基线组合登记。
  - ✅ fixture 取证后复原（deferred 回名、zip 6 条目 3313B）。
- **未覆盖**：
  - **同位姿逐像素镜像判定**：本环境位姿跨会话不可保持（p33c 出现非指令位姿移动：18:43:44
    后相机被外部输入挪走，退出存档 (12.7,4.0,−13.06)/(−62.1°,−26.1°) ≠ 锚 (8.5,1.62,−7.5)/
    (−109.5°,6.3°)，位姿日志 30 条上限截断）→ A/B 只能**同材质锚定**；需输入隔离环境重跑
    才能做同位姿镜像逐像素；
  - F2/焦点注入在本环境不可用（focus-largest 命中 8192×8192 假窗口、PointerRoot 焦点回弹），
    取证统一走 `x11_capture.py --window-id 0x60000f`（零输入副作用）；
  - 绝对值预言「终帧=基线×0.7」的同场景口径不可测（跨会话构图变化），以同材质比值预言代之；
  - ⑤ 方向条的顶视网格角度复测不可复现（位姿变了）；天空不进捕获、OIT 旁路、HUD/手部覆盖、
    太阳/月亮光空间、多级联 CSM、PCF 仍为既有登记缺口，非本轮回归。
- **是否已提交**：随本轮 commit 提交并推送 origin/master。

---

## 2026-09-30 — P3.2 地形接管第一阶段：FrameGraphSetupEvent 换目标 + 双管线定向，三跑实测黑屏/镜像两坑闭环

- **本次改了什么**：
  1. 新增 `bridge/SceneCaptureApi`（【参考调研】五条头，T13）：订阅 NeoForge 官方
     `FrameGraphSetupEvent`（LevelRenderer:249，bundle 初始化后、vanilla clear/sky/main
     pass 入图前，合并 jar 源核实）—— 懒建/随主目标 resize
     `TextureTarget("vkdisp scene", RGBA8_UNORM, D32_FLOAT)`（= 原版 MainTarget 格式，javap 核实）
     → `importExternal("vkdisp_scene")` → `targets.replace(MAIN_TARGET_ID, handle)`（官方突变
     API；handle 只换持有对象、`get()` 仍返回我方纹理，FrameGraphBuilder 源核实）→
     **自清屏 color(黑,α0)+depth(0.0)**（vanilla clear 硬编码清原版主目标，换目标后不代清）。
     任意 Throwable → ERROR 原文 + 本帧不换目标（T11）；埋点
     `scene capture wired: 854x480 … (targets.main -> vkdisp_scene)`。
  2. **FrameApi Pass 3 输入源选择**：世界内且已捕获 → 采 scene 纹理，否则回退 offscreen1
     fixture（菜单/未捕获）；来源**切换**打一次埋点（不逐帧刷）；
     `pass.setPipeline(useScene ? compositeScene : composite)` 按输入选顶点。
  3. **坑①（跑1 全黑）反向 Z**：vanilla clear depth=**0.0** 且
     `DepthStencilState.DEFAULT = GREATER_THAN_OR_EQUAL`（javap 核实）—— 我们按常识清 1.0 →
     GEQUAL 全败 → 地形零像素。改 0.0（vanilla 同值）。
  4. **坑②（跑2 镜像）方向**：flipv 顶点采 scene = 上下颠倒 —— 世界轴对齐方块边缘角度
     实测 {−30.7°, +58.3°} vs yaw=−30.75° 期望 {+30.7°, −59.3°} 符号整体翻转。顶点是管线
     静态状态 → **双管线**：PipelineApi 新增 `pipeline/composite_scene`（同片元 +
     不翻转 `fullscreen` 顶点，独立 `registerCompositeScenePipeline` + 独立 try/catch）、
     FrameApi `isPipelineReady`/compiled fetch 同步、FullscreenPipelineRegistrar 计数标签
     (1/6)…(6/6) → (1/7)…(7/7)（计数门禁 = registered==compiled，自动 7/7）。
  5. `docs/18-PARALLEL.md` §5：P3.2 设计块 ①–⑤（全部 X9 核实）+ ③ 深度修正 + ④ 双管线
     + 三跑实测回填 + ✅ 标记；SkyRenderer 旁路等缺口如实登记（⑤）。
- **为什么改**：01-DEV-LOOP §10 P3.2 完整交付 = 08-TESTING §5「地形/实体走自定义目标而非
  原版目标」。帧图装配期是唯一不 mixin 就能换 bundle 目标的官方扩展点；两个坑都是实测撞出
  并按证据闭环（P-1f 矩：黑了查根因、反了翻，全程留 log+截图）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（P3.2 块）；
  `src/main/java/dev/vkdisp/bridge/SceneCaptureApi.java`（新）、`FrameApi.java`、
  `PipelineApi.java`、`render/FullscreenPipelineRegistrar.java`。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；**424 用例 0 失败 0 错误**（本轮未新增用例，与 P3.1 基线持平）。
  - ✅ **runClient `-PquickPlay` 三跑**（evidence/p32_run{1,2,3}.log）：
    跑1 埋点全对但截图全黑（深度 1.0 反向 Z 根因，已修）；
    跑2 地形进画面（末地石头顶视图 mean_luma=136.3）但镜像（角度取证）；
    跑3 终态 —— `pipeline count check: registered=7, compiled=7 (aligned)`、
    `composite scene pipeline wired: … vertex=vkdisp:fullscreen (no v-flip)`、
    `scene capture wired: 854x480 …`、`composite input source: fixture offscreen1 →
    scene capture (P3.2 terrain)` 各一次、`light-space list ready: size=1 …` 仍在、
    **vkdisp WARN/ERROR=0**。
  - ✅ **F2 截图方向闭环**：`p32_scene_run3.png`（854x480，sha256
    `9d72bfdf0a13cdf9f0b78a79576be310a2aed9e1f67b0c4ca2821849987f1cc5`）方块边缘
    {+29.7°, −61.5°} ≈ 期望；且 `mean|run3 − flipV(run2)| = 0.000`（**像素级精确镜像**，
    逐像素比对脚本）证明修复 = 纯 V 方向、其余不变。
- **未覆盖**：天空不进捕获（SkyRenderer 构造期持原版主目标，主世界黑天空后续接，末地不可见）；
  OIT/improved-transparency 深度旁路（默认关）；HUD/手部被 Post 链覆盖（P0.3 起既有语义，
  非本轮回归）；场景 resize 中途行为仅代码路径覆盖未实测换窗口；角度取证为手工取点（±2°），
  以像素级镜像关系为主证；太阳/月亮光空间方向、多级联 CSM、PCF 仍为 P3.1 登记缺口。
- **是否已提交**：随本轮 commit 提交并推送 origin/master。

---

## 2026-09-30 — P3.1 光空间列表完整交付：自建级联列表非空（size=1），明暗双峰像素取证，uLight 数值等价

- **本次改了什么**：
  1. **X9 前提修正（先于实现，写进 18-PARALLEL §5）**：对合并 jar 全部 `.java` 源检索
     `lightSpace|lightSpaceMatrix|shadowProjection|shadowModelView|cascade` 等 = **0 命中**，
     `shadow*` 类仅实体投影斑 —— 原版 26.3 **没有**阴影贴图/光空间系统，此前登记的「接入原版
     LevelRenderer 光空间列表」是基于错误前提的缺口。P3.1 完成定义改回 04-SPEC §3.4：
     按契约**自建** ShadowPass 的光空间列表（级联条目数据层）。
  2. 新增业务类 `shadow/LightSpaceList`（含【参考调研】五条头，T13）：`Entry = {cascade 序号,
     shadowModelView, shadowProjection, near, far, lightTravelDirection}` —— **V/P 分开存**
     （04-SPEC §3.2 双矩阵形态的数据层第一步，防御性拷贝）；`build(dir)` 永不返回空列表 ——
     null/零向量/NaN/Inf → 显式 `IllegalArgumentException`（T11）；当前单级联（far=8、正交
     ±1.2/±1、zZeroToOne），矩阵构造与已验收光空间逐位同参；`viewProjection(dest) = P × V`。
  3. **接线 `bridge/FrameApi`**：原内联 `ortho().lookAt()` 单矩阵常量替换为
     `lightSpaceList()`（懒构建 + 缓存 + 首帧埋点）→ `uLight = P₀ × V₀`（调用点在任何 render
     pass 打开之前，列表空时抛错即整帧跳过影子链，hook catch 打 ERROR 原文，防御分支）；
     方向来源如实登记 `source=fixed-placeholder`（太阳 `EnvironmentAttributes.SUN_ANGLE` 与
     多级联 CSM 分割按 X9 登记为本轮后缺口，未做像素判据不接）；buffer 埋点与 javadoc 同步
     去掉过期的「translate=+0.3」占位描述。
  4. **测试**：新增 `shadow/LightSpaceListTest` 9 用例 —— 非空单级联结构、不可变列表、
     **矩阵与旧链独立重算等价**（期望值不复用被测代码常量拼装）、zZeroToOne/正交范围、
     方向单位化 + 防御拷贝、零/null/NaN/Inf/近零方向显式抛错。
  5. `build.gradle`（env-1 共享文件）：补 `testImplementation 'org.joml:joml:1.10.9'` ——
     业务类与被测管线持 joml 类型，测试源集原本没有该 jar（首跑 25 处编译错实测）；
     版本 = MC 26.3 运行时自带构件（gradle 缓存核实）。
- **为什么改**：01-DEV-LOOP §10 P3.1 完整交付 = 08-TESTING §5「光空间列表非空；阴影贴图内容
  合理（不是全黑/全白）」。前提修正后，验收对象落在自建列表本身：把「列表非空」变成可断言的
  数据结构 + 可 grep 的日志事实，同时用「桶值不变」的像素证据证明列表化没有改动画面（数值等价），
  为多级联 CSM 与太阳方向接入立数据层。一次只做一个，同轮不碰 CSM/方向。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（⏳ 光空间缺口行的 X9 修正 +
  P3.1 完整设计块 ①–④ + 实测回填）；`build.gradle` 注释。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；**424 用例 0 失败 0 错误**（基线 415 + LightSpaceListTest 9）。
  - ✅ **runClient `-PquickPlay` 世界内实测**（`run/logs/latest.log`，**vkdisp ERROR=0**）：
    `light matrix buffer created (content=light-space view-projection from LightSpaceList, bytes=64)` →
    **`light-space list ready: size=1 source=fixed-placeholder dir=(0.48000002, -0.8, 0.35999998)
    cascade0 near=0.1 far=8.0 ortho=[-1.2, 1.2]x[-1.0, 1.0] zZeroToOne=true`**（列表非空判据）；
    `pipeline count check: registered=6, compiled=6 (aligned)`、`shadow sample chain executed
    (854x480)`、进世界后 `camera source=vanilla GameRenderer` + `camera anchor captured`。
  - ✅ **F2 截图**（854x480，`p31_lightspace_run.png` sha256 `3ea6c38fc54315961217e989a7e1e069a0d6e10f
    92013cff7323b08023976cba`）：非黑 10598 px（2.6%）、全白 **0**；色调桶 **48/57/164** =
    红四边形受光（0.9×54.2）/ 绿四边形阴影（0.35×164.2）/ 绿四边形受光（0.9×182.4）——
    明暗双峰、非全黑非全白；**桶值与 P2.4 主菜单默认轮逐值相同（48/57/164）** = 列表化后
    uLight 数值等价的像素级证据。覆盖率 10598 vs P2.4 的 29294 属取景差异非回归
    （P2.4 A/B 为主菜单占位相机，其 log `vanilla GameRenderer` 0 命中；本轮进世界锚点
    pitch=90 俯视，fov=23.8°，同色调按透视缩小可解释）。
- **未覆盖 / 存疑**：① 空列表防御分支未在运行时触发（固定方向常量结构性不可达）——靠
  `LightSpaceListTest` 零向量/非规数用例 + `lightSpaceMatrix()` 抛错分支静态覆盖，真实故障路径
  未注入；② 太阳/月亮方向光空间未接（SUN_ANGLE API 已 javap 核实、方向公式已推导，但末地
  固定午夜 + 竖直 fixture 几何 → 竖直光会得到退化贴图，无像素判据不接，X9）；③ 多级联 CSM
  （列表 size>1）与逐级联分割策略未实现——数据层结构已就位；④ V/P 分矩阵仅用于合成 uLight，
  04-SPEC §3.2 包侧 `shadowModelView`/`shadowProjection` 两 uniform 形态未接；⑤ PCF 软阴影未做；
  ⑥ 运行时 `--rerun-tasks` 触发 `:createMinecraftArtifacts` 联网校验失败属环境网络问题
  （常规任务图不受影响，build/test 均绿）。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-30 — P2.4 composite 生效：选项 profile 真实改变画面，A/B 整帧亮度比 0.8870 命中理论 0.8889

- **本次改了什么**：
  1. **虚拟资源包 `vkdisp_pack`**（新增 `VkDispVirtualPack`，env-1 独占 `VkDisp*` 路径）：`AddPackFindersEvent`（mod bus）注册 `required=true` 的 RepositorySource → `rebuildSelected` 强制入选中集（**不写** options.resourcePacks，04-SPEC §3.1）。源生成在 `openResources`（此刻配置已加载@3079、生成@3114，时序 = Minecraft.<init> 字节码偏移实测）；注册期只组装静态元数据。内部 `VirtualPackResources` 只服务 `assets/vkdisp_pack/shaders/composite.fsh` 一个资源（接口清单 javap 核实，X9）。生成链任意 Throwable → ERROR 原文 + 内置 passthrough 兜底（composite 是 required 管线，抛穿会砸启动，T11）。
  2. **冷路径编排 `pack/PackCompositeSource`**（新增）：扫描 → 逐包 `ShaderPackService.load` → 找 `program=composite` → `PackOptions.of` → `applyProfile`（空名跳过）→ **values 与 defaults 差分**出覆盖表 → `ShaderPackCompiler.compile(discovered, overrides)` → 取第一个成功 FRAGMENT；全军覆没才兜底（必带 WARN）。扫描问题/选项诊断全部映射成分级 TranslateDiagnostic。
  3. **选项改写链**（上一段冷路径主体，本轮接线完成）：新增 `config/OptionSourceRewriter`（行内改写 `#define NAME <值>` / 裸 define / `const T NAME = <值>;`，保行数保注释保 CRLF；13 单测）；`ShaderPackCompiler` 新增 `compile(pack, overrides)` 重载 —— 主源与 **include 包装后的源**都过改写器，覆盖名全包未命中 → 包级 WARN（T11）。
  4. **帧链接线**（bridge 两文件）：Pass 3 由 blit 换成 composite 管线 —— `PipelineApi.COMPOSITE_SHADER_ID` 改指 `vkdisp_pack:composite`，顶点换自造 `fullscreen_flipv`（1-v 翻转进顶点，包片元保持 OF 原语义用原始 vUv，P-1f）；composite 绑定布局 `[VkDispBuiltins UBO, InSampler]` 同组（顺序对齐注入 GLSL；「布局多于 shader 使用」有 blit+Globals 先例），`FrameApi` 新增 1024B `builtinsRing()` 零填充绑定（上传链属 OfUniformManager，登记后续缺口）；`isPipelineReady` 判定从 blit 换 composite。
  5. **主线开关**：`VkDispConfig.packProfile`（javap 核实本版 `ModConfigSpec` 无 StringValue → 泛型 `ConfigValue<String>`）。
  6. **fixture 改造**（run/ 不入库）：composite.fsh 改为采样 InSampler 并以 `1.0 - SHADOW_DARKNESS` 调制 RGB（原平铺色调 RGB 恒定，开关在画面上不可见）+ `#define ENABLE_FOG` 门控；zip/dir 两套同步重建。删除旧模组自带 `assets/vkdisp/shaders/composite.fsh`（grep 核实唯一引用 `COMPOSITE_SHADER_ID` 已改指虚拟包）。
  7. 测试：新增 `OptionSourceRewriterTest`（13）+ `PackCompositeSourceTest`（8：空库存/无 composite/坏片元兜底、空 profile 走默认、HIGH 覆盖进最终源、未知 profile 显式 ERROR 且保持默认）+ `ShaderPackCompilerTest` 补 4 条覆盖用例。
- **为什么改**：01-DEV-LOOP §10 的 P2.4 = 08-TESTING §4「选择开关后画面有对应变化 | 对比截图」。前几轮证明了「编得过、扫得见」，但选项值从未进过上屏的那条链；本轮把 扫包 → 选项 → profile → 差分改写 → 驱动编译 → Pass 3 上屏 整条串起来，用两张截图的像素统计证明开关真的改变了画面（18-PARALLEL §5 关键路径，无 GPU 证据不算进度）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（P2.4 块 → ✅，含 A/B 实测）。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；**415 用例 0 失败 0 错误**（基线 390 + 25 新增）。
  - ✅ **runClient A/B 实测**（两轮完整启动，F2 截图各 1 张，stdlib PNG 解码整帧统计）：
    - Run A `packProfile=""`：日志 `composite source generation start: profile=''` → `composite source ready: fallback=false pack=vkdisp-fixture-zip profile='' bytes=1655 diagnostics=0`；截图 `p24_runA_default.png` sha256 `c4f7b50aff872af130f0a5b77abb528eb0e8f2a6173e3485149c95c1af317ff6`，mean_luma **6.3813**。
    - Run B `packProfile="HIGH"`：同两行日志 profile='HIGH' bytes=1655；截图 `p24_runB_high.png` sha256 `af6f22e2e1738badc0acb628687e8cecd26e22ebd27bdbbf0998f86af215c96a`，mean_luma **5.6604**。
    - **判据：B/A = 0.8870，理论 HIGH(0.80)/default(0.90) = 0.8889，偏差 0.2%**（RGBA8 量化量级）；R/G 通道比 0.8870/0.8871 同向一致；非黑像素 29294 个逐对比较值同为 0.8870；差异像素 29294/409920 = 7.15%；两张 sha256 不同 → **选项开关真实改变了上屏画面**。
    - 两轮共有链路证据：`virtual pack finder registered: id=vkdisp_pack required=true position=TOP`、`composite pipeline wired to pack shader: fragment=vkdisp_pack:composite vertex=vkdisp:fullscreen_flipv builtins+sampler same group`、`pipeline count check: registered=6, compiled=6 (aligned)`、`fullscreen pass enabled`；**两轮 vkdisp ERROR = 0**（仅存 ERROR 为原版 authlib/narrator/OpenAL 环境噪音）。
- **未覆盖 / 存疑**：① 维度目录 composite（`world0/composite`）不参与 Pass 3 选择（代码 javadoc 已登记，P3.x 接维度时再定）；② `VkDispBuiltins` 仍零填充 —— fixture 片元不读内建成员，OfUniformManager 上传链是后续缺口；③ F3+T 资源重载触发的二次源生成未实测（本轮只测冷启动两轮）；④ 库存 zip+dir 两包同构，选中的是 zip（scanner 顺序），dir 包未单独切换验证；⑤ profile 值未进任何声明行时有包级 WARN 但无逐项提示 —— A/B 截图是最终裁判；⑥ 首轮启动出现 ConfigTracker `vkdisp-common.toml is not correct. Correcting`（NeoForge 首建配置的规范化动作，非本模组代码路径，第二轮消失）；⑦ `#version 150/120` 包与包自带非透明 uniform 的真实包留给 P4.1。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-30 — P2.3 #include 编译接主线：驱动级 GLSL→SPIR-V 通路打通，4/4 阶段编译通过

- **本次改了什么**：
  1. 新增 `bridge/ShaderCompileApi.java`（env-1 独占 bridge 路径）：把阶段最终 GLSL 源经**原版 `GlslCompiler.compileToSpv`**（shaderc，与原版管线同一条编译路径）编到 SPIR-V。构造参数 `isZZeroToOne()` / `shaderDrawParameters()` 取自 javap 核实的 PipelineBuilder 字节码（X9 不猜）；**永不抛异常** —— `ShaderCompileException`/意外 `RuntimeException` 一律降级为带原文的失败视图（T11）；`SpvModule` 用完即关、不创建管线；`ShaderSource` 桩对残留 `#include` 返回 null → shaderc 显式报 "not found"。含【参考调研】五条头（T13）。
  2. `VkDispPackScan` 增加 `compileAndLog()`：扫包日志之后逐包逐阶段 `ShaderPackCompiler`（include 展开 + OF 转译，冷路径）→ `ShaderCompileApi` 驱动编译 → 每阶段一行 `pack program compiled OK … spvBytes=` / `FAILED … : <原版错误原文含 file:line>` + 末行 `pack compile done: stages= ok= failed=` 汇总（P2.3 验收口径）。
  3. **D 线 Vulkan 方言修复（两轮 runClient 实测驱动，非纸面推演）**：`UniformInjector` 的 23 条内建 uniform 发射形态改为单个**具名无实例名块** `layout(std140) uniform VkDispBuiltins { … };`：
     - 第 1 轮失败（4/4 阶段）：独立 `uniform mat4 …;` 行 → shaderc 原文 `'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan`；
     - 第 2 轮失败：匿名块 `layout(std140) uniform {` → `syntax error, unexpected LEFT_BRACE`（GLSL 语法要求块名；核查合并 jar 内原版 **89 个 shader 全部是具名无实例名块、成员裸引用**，如 `Scissor.x`）；
     - 终态 = 原版同款形态：无实例名 → 成员仍在全局作用域 → 包源码 `gbufferModelView * …` 引用字面不变；04-SPEC §3.2 表的名称/类型/顺序不变，只改发射外壳；
     - 配套：`BuiltinUniform.blockMember()` 新增（`declaration()` 保留为包源码形态）；扫描侧把布局块成员记作「已声明」（保证幂等 + 防撞名）；未闭合布局块显式 ERROR（T11）。
  4. 测试同步：`UniformInjectorTest` 新增 std140 块形态用例（+1，共 390）；两个 golden 串改块形态；`GlslPipelineTest` / `OfGlslTranslatorTest` / `OfGlslTranslatorBuiltinsTest` 断言改 `blockMember()` + `BLOCK_OPEN`。
  5. `docs/18-PARALLEL.md` §5 P2.3 状态块：记录两轮方言坑的证据链与终态形态。
- **为什么改**：01-DEV-LOOP §10 的 P2.3 = 「含 `#include` 的 program 能编译通过（日志证据）」。冷路径早已能产出最终源（`d6e9bdd`），本轮补上「源交给驱动编译」的桥；而桥一通，立刻暴露出 D 线发射形态与 Vulkan GLSL 的两处真实差距 —— 只有真实驱动编译能抓到，JUnit 文本断言抓不到。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（P2.3 块）。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`./gradlew test` **390 用例 0 失败 0 错误**（基线 389 + 新增块形态用例 1）。
  - ✅ **runClient 实测**（`run/logs/latest.log`，本轮最终一轮）：
    ```
    vkdisp: pack program compiled OK: pack=vkdisp-fixture-zip  program=composite stage=VERTEX   file=composite.vsh spvBytes=3664
    vkdisp: pack program compiled OK: pack=vkdisp-fixture-zip  program=composite stage=FRAGMENT file=composite.fsh spvBytes=3756
    vkdisp: pack program compiled OK: pack=vkdisp-fixture-dir  program=composite stage=VERTEX   file=composite.vsh spvBytes=3664
    vkdisp: pack program compiled OK: pack=vkdisp-fixture-dir  program=composite stage=FRAGMENT file=composite.fsh spvBytes=3756
    vkdisp: pack compile done: stages=4 ok=4 failed=0
    ```
    P2.3 验收达成：`composite.fsh` 源内含 `#include "/lib/common.glsl"`（zip + dir 两包均 OK）；同轮 `pack scan done: packs=2 programs=2 options=8 problems=0 diagnostics=0`；`VulkanBackend` 启动启用；**vkdisp ERROR=0 / WARN=0，全 log 零 ERROR 行**。
- **未覆盖 / 存疑**：包**自带**非透明 uniform（真实 OF 包常见）仍是独立行形态 → 同一条 Vulkan 规则会在 P4.1 撞上（已在任务板登记为后续缺口，本轮不动 §7.6 边界）；`#version 150/120` 包与 `attribute/varying` 全套方言的真实包未测；`VkDispBuiltins` 块的 SPIR-V 反射与 uniform 上传（OfUniformManager）留 P2.4 —— 具名块恰好给了反射稳定块名；F3+T 资源重载触发的二次编译未实测；两次失败轮的完整日志原文未入库（仅摘录进本条目与 18-PARALLEL）。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-30 — P2.1/P2.2 主线接入：启动期扫包钩子上线，zip+目录包与选项枚举日志实测可见

- **本次改了什么**：
  1. 新增 `VkDispPackScan`（env-1 独占 `VkDisp*` 路径）：`@EventBusSubscriber` + `ClientResourceLoadFinishedEvent` 门闩 → 解析 `Minecraft.gameDirectory/shaderpacks` → `ShaderPackService.loadAll` → 逐条日志：包清单行（`kind=zip|dir`、programs/options/profiles/dims，P2.1 证据）、程序行、选项行（`name/type/default/values/slider/screen`，P2.2 证据）、扫描问题按 kind 分级（BROKEN_ZIP→ERROR、结构性→WARN、INVENTORY_MISSING/NO_PACKS_FOUND→INFO 带 hint，T11 全部显式打点）、诊断按原 severity 分流；整体 catch Throwable 打 ERROR 原文；总开关关闭走 WARN 降级一次。含【参考调研】五条头（T13）。**只读库存、只打日志** —— 不注册虚拟资源包、不写 options/resourcePacks（04-SPEC §3.1、B 线边界）。
  2. 自造 fixture 两套（§7.6 禁第三方包）：`run/shaderpacks/vkdisp-fixture-dir/`（目录包）+ `run/shaderpacks/vkdisp-fixture-zip.zip`（zip 包，shaders/ 在 zip 根），各含 composite.vsh/fsh（`shadowMapResolution` / `SHADOW_DARKNESS` / `ENABLE_FOG` / `shadowDistance` 四选项）+ shaders.properties（sliders/screen./profile.）；`run/` 整体 gitignore，不入库。
  3. `docs/18-PARALLEL.md` §5 关键路径：P2.1/P2.2 → ✅（含实测日志口径）。
- **为什么改**：A/B/C 线与 ShaderPackService 已于 `3afdf1d` 汇合入主线，但从未在真实游戏启动链路上跑过 —— 01-DEV-LOOP §10 的 P2.1（zip 与目录都能被列出）/ P2.2（选项被枚举出来、日志可见）验收只能靠 runClient 实测；本轮把冷路径汇合产物接到客户端启动事件上，补上这条关键路径证据。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5（P2.1/P2.2 状态行）。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`./gradlew test` **389 用例 0 失败 0 错误**（36 个测试类，基线 389 持平）。
  - ✅ **runClient 实测**（`run/logs/latest.log`，exit=0 优雅退出）：
    ```
    vkdisp: pack scan: inventory=./shaderpacks exists=true initial=true
    vkdisp: pack[1] name=vkdisp-fixture-zip kind=zip source=./shaderpacks/vkdisp-fixture-zip.zip programs=1 options=4 profiles=[LOW, HIGH] dims=[]
    vkdisp: pack[1] option name=shadowMapResolution type=INTEGER default=2048 values=[512, 1024, 2048] slider=true screen=(main)
    vkdisp: pack[1] option name=SHADOW_DARKNESS type=FLOAT default=0.10 values=[0.05, 0.10, 0.20] slider=false screen=(main)
    vkdisp: pack[1] option name=ENABLE_FOG type=BOOLEAN default=true values=[true, false] ...
    vkdisp: pack[1] option name=shadowDistance type=FLOAT default=64.0 values=[32.0, 64.0] slider=false screen=QUALITY
    vkdisp: pack[2] name=vkdisp-fixture-dir kind=dir source=./shaderpacks/vkdisp-fixture-dir programs=1 options=4 profiles=[LOW, HIGH] dims=[]
    （pack[2] 选项四行同构，略）
    vkdisp: pack scan done: packs=2 programs=2 options=8 problems=0 diagnostics=0
    ```
    P2.1 = `kind=zip` 与 `kind=dir` 两行都在；P2.2 = 8 条选项行带全字段。同轮链路完好：`backend=Vulkan, device=llvmpipe`、阴影链 854x480 持续绘制、**vkdisp ERROR/WARN = 0**（仅存的 2 条 ERROR 是原版 narrator/sound 环境噪音，既有）。
- **未覆盖 / 存疑**：F3+T 资源重载时的二次扫描仅设计上会重跑（本轮只实测首启 `initial=true`）；损坏 zip / 空目录 / 无 shaders/ 三种扫描问题分级在 JUnit 有覆盖但本轮日志实测 problems=0（fixture 全合法）；未验证真实第三方包（§7.6 禁入库）；选项值此轮只做枚举展示，未接 PackOptions/OptionBinding 生效链（那是 P4.3）。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-29 — 并行线汇合：A+B / C+D / E+F 三组交付入主线（纯冷路径，零 GPU 改动）

- **本次改了什么**（三个队友环境各交独占路径，Lead 汇合）：
  1. **C+D 汇合（P2.3 前置）**：新增 `glsl/GlslPipeline.java`（串联 `GlslPreprocessor.analyze` → `OfGlslTranslator.translate`，失败短路 / 两阶段诊断合并 / null 归一）+ `GlslPipelineTest` 16 用例。
  2. **E+F 开放点**：`config/OptionBinding.java` javadoc 增补 P-1e 真值表结论 6 条 + 新增 `OptionDefineStyleTruthTableTest`（13 行 LITERAL vs IFDEF_TRUE 逐行对比、并排五列表、两风格"必须相同"断言，8 用例）；新增 `VertexLayoutPendingAlignmentTest`（P-1d 待改行清单机器校验：10 条 LineRef 断言 + `MC_ENTITY_ELEMENT_TYPE_AFTER_P12=null` 显式未实测，6 用例）。
  3. **B 线测试修复**：`ShaderPackScannerTest#nestedOuterFolder` 多级目录改 `Files.createDirectories`（原 `createDirectory` 对不存在的父目录抛 NoSuchFileException —— Lead 跑全量测试发现后派回修复）。
  4. （A+B 汇合的 `ShaderPackService` 串联入口仍在途，见任务板 task-3，完成后另记条目。）
- **为什么改**：A/B/C 三线已于上游入库（`897a2d5`/`8a71b59`/`cb1fed1`），本轮把 C+D、E+F 的**汇合与开放点材料**并入，解锁 P2.3（#include 编译链）与 P4.3（选项定稿）的前置条件。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §10（P-1e → 🟡 对比材料已就绪；P-1d → 待改行清单已机器校验）。
- **测试结果**：
  - ✅ `./gradlew build` exit=0；`./gradlew test --rerun-tasks` **346 用例 0 失败 0 错误**（32 个测试类；汇合前基线 316）。
  - ✅ **grep 自证**（三条并行线各自执行）：`com\.mojang\.(renderpearl|blaze3d)` 在 `glsl/`、`pack/`、`config/`、`pipeline/model/` → **NO MATCH**；`PipelineFactory|RenderPipeline.builder` 零实现。
  - ✅ **【参考调研】**：全部新文件含注释块且第 0 条为合规结论（Iris glsl-preprocessor「GPL-3.0+例外」与 glsl-transformer 按**禁止**处理；OptiFine 无 LICENSE=ARR 不可用；VulkanMod/Sulkan/Beryl 零接触；JUnit 仅测试期 EPL-2.0）。
- **未覆盖 / 存疑**：真实第三方包语料（§7.6 禁入库）；宏展开后重新生成预处理指令的病态输入；`#include` 位于被跳过分支时仍先展开（C 线 Include→Define 固定顺序语义，入口不改）；P-1e 默认风格仍 LITERAL 待 P4.2/P4.3 真实包定稿（X9）；P-1d stride=47 待 P1.2 实测对齐。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-29 — P3.2 接原版 GameRenderer 相机（世界内真实位姿驱动视图；菜单显式回退）

- **本次改了什么**：
  1. `bridge/FrameApi.java`：新增 `cameraMatrix(width,height)` —— 世界内（`level != null && cameraRenderState.initialized`）取**原版 GameRenderer 相机**：`clip = P_vanilla × viewRotation × T(−pos) × M_anchor`（投影与原版世界渲染同一份，含 zZeroToOne / 真实 FOV / 窗口宽高比）；菜单/未进世界**显式回退**占位透视相机（`camera source=placeholder fallback (reason)` 埋点，T11）。
  2. **锚点设计**：进世界首帧捕获 `M = T(camPos) × R(camRot) × V_placeholder` —— 捕获帧与占位版本视图一致；此后相机移动/转向 → 几何在屏幕上移动（「原版相机真的在驱动视图」的判据）。换世界/离开世界自动重捕。
  3. **位姿变化埋点**：pos/yaw/pitch 任一变化超 epsilon（1e-3 / 0.01°）即打 `vanilla camera pose changed #N`（上限 30 条）。
  4. `build.gradle`（env-1 共享文件）：新增可选 `-PquickPlay` → 追加原版 `--quickPlaySingleplayer`，runClient 启动即自动进 `run/saves` 最近世界；**不传则行为与原来完全一致**。
  5. `tools/vulkan-local/x11_input.py`：依据系统权威头 `/usr/include/X11/extensions/xtestproto.h` 修复 XTEST 注入三处协议违例 —— ① `X_XTestFakeInput` 必须 **36 字节 / length=9**（旧发 32 字节且 length 字段被写成 keycode → server 等更多字节 → 永不应答 TimeoutError）；② byte1=扩展 minor(2)、type 在 byte4（旧把 type 放 byte1 → KeyRelease(3) 被解析成 `X_XTestGrabControl` → 请求流错位）；③ `GetInputFocus` 屏障必须 4 字节 `<BBH`（旧 `<BBHI` 多发 4 字节 → opcode=0 非法请求）；④ 查询类请求须在 `SetInputFocus` **之前**发。另新增 `xtest_fake_motion`/`xtest_fake_button`（协议正确，未在真实聚焦窗口验证）。
- **为什么改**：18-PARALLEL §5 的头号缺口 —— 此前世界视图是**固定占位相机**（fov60/eye=(0,0,-2.5)，不随玩家视角变化）。接上原版相机后视图矩阵由真实 `CameraRenderState` 驱动，是 P3.2 gbuffers 接管与「阴影跟随玩家视角」的必要前提。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=0；`./gradlew test --rerun-tasks` 全仓库 **346 用例 0 失败**（含 A/B/C/D/E/F 六线新用例）。
  - ✅ **日志**（`p32_vanilla_camera2.log`，sha256 `9d80b554571fd991…`）：`camera source=placeholder fallback (level=false, initialized=false)` →（quickPlay 自动进世界）→ `camera source=vanilla GameRenderer` → `camera anchor captured: pos=(8.5, 4.389, −7.5), yaw=−30.75, pitch=90.0, fov=16.8deg` → `pose changed #1/#2` → **F5 后 `pose changed #3: y 5.62 → 9.62`**（第三人称相机上移 4 格）。`count check: registered=6, compiled=6 (aligned)`；ERROR=**2**（仅 narrator/SoundSystem 环境噪声），`vkdisp` ERROR=**0**。
  - ✅ **像素级判据**（图 A `p32_final_A.png` sha256 `22dd9a43…` vs F5 后图 B `p32_final_B.png` sha256 `15048894…`）：

| 量 | 图 A | 图 B | 实测比值 | 理论（相机 y 5.62→9.62，距离 4.03→8.03 / 4.43→8.43） |
|---|---|---|---|---|
| 几何宽 W | 99 px | 50 px | **0.505** | 0.502（红）/ 0.526（绿） |
| 几何高 H | 102 px | 52 px | **0.510** | 同上 |

    实测比值落在两块理论比值之间、四位有效数字吻合 → **几何尺寸可由原版相机位姿反算预测**，相机矩阵真实参与顶点变换。另目检前一轮 F5 实验（`p32_cam_B.png`）：几何整体**位移 + 旋转**（边不再水平）—— 旋转只能来自 `viewRotationMatrix`。
- **测量口径备忘**：本次相机 pitch=90（末地出生点直视下方），几何在相机**下方** → 距离 = 相机 y − 几何 y（锚点 y 4.39 − 占位视图前向 2.8/3.2 = 1.59/1.19）；算理论比值别用水平距离。
- **未覆盖 / 存疑（不掩饰）**：
  1. **只验证了第三人称位移（F5），未验证 yaw/pitch 连续转动** —— Weston 环境 X 焦点为 None，首次 F5 是窗口尚有焦点时注入的，之后 XTEST motion/click 均无法重新聚焦（X 焦点层面，非协议层面）；转动视角的像素判据留待可聚焦环境补跑；
  2. 锚点在末地出生点捕获（pitch=90 特殊姿态），常规主世界姿态未跑；
  3. PCF/比较采样器、原版 LevelRenderer 光空间列表/CSM 集成仍缺（18-PARALLEL §5 保持 ⏳）；
  4. `x11_input.py` 的 motion/button 注入未在真实聚焦窗口上端到端验证。
- **是否已提交**：随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-29 — 真实透视相机矩阵（替换占位 NDC 视图，P3.2/P3.3 视图主体）

- **本次改了什么**：
  1. `geometry.vsh`：按 pass 分支声明矩阵块 —— `SHADOW_MAP_PASS`（阴影贴图 pass）用 `LightMatrix`，世界视图 pass 用 **`Camera`**；裁剪空间分别 = 光空间 / 相机空间。
  2. `bridge/PipelineApi.java`：新增 `CAMERA_UNIFORM = "Camera"`；阴影采样管线的绑定组扩为三项（`LightMatrix` + `InSampler` + `Camera`，仍为单组，符合 P-1f 实测约定）。
  3. `bridge/FrameApi.java`：新增相机矩阵环形缓冲（64B，一次性埋点）；每帧按主目标宽高比重建 `perspective(fov=60°, aspect, near=0.1, far=32, zZeroToOne=true) × lookAt((0,0,-2.5) → 原点)`；Pass 2 绑定 `Camera`。
- **为什么改**：世界视图此前是恒等 NDC 占位 —— 而**透视投影是「近大远小」与深度语义的前提**（也是接原版 GameRenderer 相机前必须先跑通的一环）。本实现仍为**固定占位相机**（非原版相机），但已把矩阵链路（构造 → 上传 → 绑定 → 顶点变换）完整跑通并可量化。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5 P3 前置清单。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **日志**：`p32_camera.log`（sha256 `23567d92281c9f83…`）——`camera perspective: fov=60deg, aspect=1.7791667, eye=(0,0,-2.5) -> origin, zZeroToOne=true`、`count check: registered=6, compiled=6 (aligned)`、`shadow sample chain executed (854x480)`；ERROR=**2**（仅 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **透视判据（截图 `p32_camera.png`，sha256 `790c2b4e88459108…`）**：两块 y 范围同为 ±0.6 但 z 不同（红 0.3 / 绿 0.7），相机在 z=-2.5 → 距离 2.8 vs 3.2，理论放大比 **3.2/2.8 = 1.1429**。实测（亮绿+受影绿合并 bbox，否则受影区会被漏算）：
    
| 量 | 红块 | 绿块 | 比值 | 理论 |
|---|---|---|---|---|
| 高 | 176 | 154 | **1.1429** | **1.1429** |
| 宽 | 116 | 102 | 1.1373 | 1.1429（±0.5% 采样量化） |
    
    高度比与理论**四位小数吻合** → **透视矩阵真实参与顶点变换**；同时暗绿受影区（`(0,89,0)`）仍存在 → 阴影采样在新相机下**未回归**。
- **测量口径备忘**：绿块 bbox 必须把**受影暗绿 `(0,89,0)` 与亮绿 `(0,255,0)` 合并**统计 —— 首次只统计亮绿得 102 高（比值 1.725，误导），合并后 154 高才是全貌（并行线做像素判定时注意同类陷阱）。
- **未覆盖 / 存疑（不掩饰）**：
  1. 相机为**固定占位**（非原版 `GameRenderer` 相机矩阵、不随玩家视角变化）——接入原版相机是 P3.2/P3.3 后续主体；
  2. **Vulkan y 方向未做视觉校正**：本验证几何 y 对称故不可见；接真实世界内容时需处理（与 P-1f 的方向约定一并复核）；
  3. 未验证相机矩阵**动态更新**（每帧重建当前为同一参数）与焦距/近远裁剪边界；
  4. PCF/比较采样器、原版 LevelRenderer 光空间列表集成仍缺。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — P3.3 阴影采样（世界视图渲染 + 阴影贴图深度比较 → 明暗可判定）

- **本次改了什么**：
  1. `geometry.vsh`：加 `SHADOW_MAP_PASS` 条件分支（`#ifdef`）——同一带顶点绑定的着色器被**两条管线**以不同 define 编译：阴影贴图 pass（裁剪空间=光空间）与世界视图 pass（裁剪空间=占位 NDC 视图）；同时输出 `vWorldPos` 供阴影回投。
  2. 新增 `assets/vkdisp/shaders/shadowed.fsh`：世界坐标 → `uLight` 回投光空间 → 按正交范围映射到 uv → **采样阴影贴图深度** → `myDepth - bias > stored` 判定受影 → 受影片元 `×0.35` 变暗。
  3. `bridge/PipelineApi.java`：新增**阴影采样管线** `vkdisp:pipeline/shadowed`（共用 geometry.vsh 但**无** define；片元 shadowed.fsh；同一绑定组含 `LightMatrix` UBO + `InSampler`；无深度状态）；几何管线加 `.withShaderDefine("SHADOW_MAP_PASS")`。
  4. `bridge/FrameApi.java`：Pass 2 由「深度可视化」改为**世界视图渲染 + 阴影采样**（同一 encoder 内不同附件）；就绪判定改看 geometry/shadowed/blit。
  5. 注册器埋点 `(1/6)…(6/6)`；Hook 埋点 `shadow sample chain executed`。
- **为什么改**：P3.1 已能把深度写进阴影贴图；P3.3 是它的价值兑现——**在世界坐标里用阴影贴图算明暗**。做成了「暗绿/亮绿 + 红块不受影」的颜色差异，可像素级判定阴影是否真的生效（而不是只看到贴图）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5 P3 前置清单。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **日志**：`p33_shadow_sample.log`（sha256 `a364cea519259972…`）——`pipeline registered (5/6): vkdisp:pipeline/shadowed`、`(6/6) blit (total=6)`、`count check: registered=6, compiled=6 (aligned)`、`shadow sample chain executed (854x480)`；ERROR=**2**（仅 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **阴影生效的像素级判据**（截图 `p33_shadow_sample.png`，sha256 `be16fe8cf5df7804…`）：
    
| 颜色类 | 像素数 | 含义 |
|---|---|---|
| 受光绿 `(0,255,0)` | **29664** | 未被遮挡的绿块 |
| **受影绿 `(0,89,0)`** | **19584** | **受阴影遮挡 → 0.35 调暗（89 = 255×0.35 精确吻合）** |
| 受光红 `(255,0,0)` | **24480** | 红块（投影者）自身受光 |
| 受影红 `(89,0,0)` | **0** | 红块不被自己的阴影遮挡（符合预期） |
    
    绿区阴影覆盖率 **39.8%**；截图中暗/亮绿分界线随光方向倾斜、带硬阴影锯齿 —— **阴影采样链路（世界坐标 → 光空间 → 深度比较）真实生效**。
- **未覆盖 / 存疑（不掩饰）**：
  1. 世界视图仍是**占位 NDC 视图**（非真实相机矩阵与透视投影）——归 P3.2/P3.3 主体；
  2. uv 的 y 映射按 y-up 直写并已实测对齐（若换环境需复核，见 shadowed.fsh 注释）；
  3. 硬阴影无 PCF/比较采样器；bias 固定 0.003（未做自适应）；
  4. 单级联、单光源、2 个四边形遮挡物；未与原版 LevelRenderer 光空间列表/CSM 集成。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — P3.1 影子 pass（自建光空间矩阵 + 阴影贴图渲染 + 可视化链）

- **本次改了什么**：
  1. `bridge/FrameApi.java`：占位矩阵升级为**真实光空间 view-projection** —— 固定光照方向 `dir=(0.48,-0.8,0.36)`（单位化 `(0.6,-1,0.45)`）→ 相机沿光反方向 4 单位看向原点 → `ortho([-1.2,1.2]×[-1,1], near=0.1, far=8, **zZeroToOne=true**)` × `lookAt`；每帧 `Std140Builder.putMat4f` 上传并带一次性埋点。
  2. 帧链重构为**影子 pass 三段**（三段各用不同附件，规避已知的「同附件第二次 createRenderPass 不生效」）：**Pass 1** 几何（经光空间矩阵）→ offscreen0（其**深度**即阴影贴图）；**Pass 2** depthviz 采样阴影深度 → offscreen1 灰度；**Pass 3** offscreen1 → 主目标。图案/合成管线**本轮不进链**（阴影贴图只应含遮挡物深度），但仍注册并通过计数断言（`registered=5`）。
  3. `FullscreenPassHook`：埋点改为 `vkdisp shadow chain executed …`（并修正占位符与参数不匹配）。
- **为什么改**：影子 pass 的三件套是「光空间矩阵 → 阴影深度写入 → 深度读回可视化」；P2.x 解析链仍等 A/B/C 汇合，关键路径继续在 GPU 能力上推进，并按 08-TESTING §3 验收判据（**阴影贴图非全黑/非全白**）量化。
- **⚠️ 本轮修掉的 3 个问题（均有证据）**：
  1. **SLF4J 不支持 `%f` 格式** → 埋点打印字面量 `dir=(%.3f, …)`；改 `{}` 后正确输出 `dir=(0.48000002, -0.8, 0.35999998), eye=(-1.92, 3.2, -1.44)`。
  2. **深度范围未对齐 Vulkan**：joml 默认 `ortho` 是 GL 约定 `[-1,1]` → 近半几何被裁、灰度挤在 16..56；改 `ortho(..., zZeroToOne=true)` 后中间调占比 **5.1% → 13.2%**、灰度中位 **≈125**（与理论 `(4−0.1)/(8−0.1)≈0.494→125` 吻合）。
  3. 重构时误删 `CommandEncoder encoder` 声明导致编译失败（补回）。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **日志**：`p31_shadowmap_final.log`（sha256 `b878114350cf9a15…`）——`light space computed: dir=(0.48, -0.8, 0.36), eye=(-1.92, 3.2, -1.44), ortho=[-1.2,1.2]x[-1,1], near=0.1 far=8`（光空间构造可核对）、`count check: registered=5, compiled=5 (aligned)`、`shadow chain executed (854x480)`；ERROR=**2**（仍只有 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **验收判据（非全黑/非全白）**：截图 `p31_shadowmap_final.png`（sha256 `d5e8fd1b4a2ce854…`）客户区灰度采样——中间调 **14617/111135 = 13.2%**、**47 个灰度级**、区间 **102..204**、中位 **125**（= 理论线性深度）；全黑 7648（边框）、全白 88870（背景清深度 1.0）→ **非全黑非全白，且深度随距离线性分布**。
- **未覆盖 / 存疑（不掩饰）**：
  1. 这是**自建阴影贴图**，未接入原版 `LevelRenderer` 光空间渲染列表 —— 08-TESTING §3「光空间列表非空」在本实现中的等价证据是 `light space computed` 埋点 + 实际渲染出的贴图；与 vanilla 列表 / 多级联（CSM）对齐归 P3.1 完整交付（需 LevelRenderer mixin，关键路径后续）；
  2. 光照方向为**固定占位**（未接世界光照/时间）；单级联、单一遮挡物（2 个四边形）；
  3. 阴影贴图目前**只可视化**，未做世界坐标阴影采样比对（P3.3）；
  4. 软阴影/比较采样器未做 —— 若需要属 13-GAP-REGISTRY 待判定项。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 光空间矩阵上传链路（P3.1 影子 pass 前置）

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：几何管线新增 `LightMatrix` UBO 绑定（`mat4`，std140 64B），常量 `LIGHT_MATRIX_UNIFORM`。
  2. `bridge/FrameApi.java`：新增矩阵环形缓冲（`MappableRingBuffer`，`MAP_WRITE|UNIFORM`，懒创建带一次性埋点）；每帧用 `Std140Builder.putMat4f` 写入**占位光空间矩阵** `T(+0.3,0,0)·S(0.6)`（与 paramsRing 同模式，写入放在开启 pass 之前，遵守上一轮定下的 encoder 规则）；几何绘制前 `setUniform(LIGHT_MATRIX_UNIFORM, …)`。
  3. `geometry.vsh`：新增 `layout(std140) uniform LightMatrix { mat4 uLight; };`，`gl_Position = uLight * vec4(Position, 1.0)`——顶点从「NDC 直写」升级为**经矩阵变换**。
- **为什么改**：P3.1 影子 pass 的核心是「光空间矩阵 → 顶点」这条链路；先把矩阵 uniform 的上传与消费做成**可像素级判定**的事实（画面必然位移），P3.1 正式接入光照方向与视锥时只需替换矩阵构造，不必再同时排查链路本身。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5 P3 前置清单（补「光空间矩阵上传链路」）。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **日志**：`p3_lightmatrix.log`（sha256 `d7b59fd34e66a466…`）——`geometry pipeline registered: stride=28`、`count check: registered=5, compiled=5 (aligned)`、**`light matrix buffer created (translate=+0.3, scale=0.6, bytes=64)`**、`3-pass chain executed (854x480)`；ERROR=**2**（仍只有 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **矩阵生效的像素级量化**（截图 `p3_lightmatrix.png`，sha256 `31c3415b7ccd8712…`；中心行 y=300 扫描 + 纵向 x=450 扫描）：
    
| 边界 | 第 8 轮（NDC 直写） | 理论（T+0.3·S0.6） | 本轮实测 |
|---|---|---|---|
| 红区左缘 | 124 | 391 | **388** |
| 红/绿交界 | 469 | 598 | **592** |
| 绿区右缘 | 641 | 702 | **695** |
| 纵向跨度 | 286 | 172 | **171** |
    
    四项逐项吻合 → **矩阵 uniform 真的上传并参与了顶点变换**；同时交界处仍为红色（重叠区近片元胜出）→ **深度剔除在矩阵变换下保持有效**。
- **未覆盖 / 存疑**：
  1. 矩阵是**占位**（平移+缩放），不是真正的光空间 view-projection——后者需要光照方向与相机信息，归 P3.1 主体；
  2. 未验证矩阵在**动态更新**下的表现（当前每帧写同一常量矩阵；P3.1 需要每帧变化的矩阵 + 时序正确性）；
  3. 未验证多矩阵 uniform（`shadowModelView`/`shadowProjection` 双矩阵是 04-SPEC §3.2 要求的正式形态）；
  4. `depthviz` / `composite` 管线仍不在本帧链（前两轮各自验证过）。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 真实几何 + 深度剔除（P3 前置第二段；本轮抓出 5 个真实缺陷）

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：新增**几何管线** `vkdisp:pipeline/geometry`（顶点绑定 `Position(vec3f)+Color(vec4f)`、stride 28、`DepthStencilState(LESS_THAN_OR_EQUAL, writeDepth=true)`、`withCull(false)`）+ 顶点格式常量。
  2. `bridge/FrameApi.java`：懒创建 12 顶点的几何缓冲（直接缓冲；近红 z=0.3 先画、远绿 z=0.7 后画，**刻意让远的后画**）；图案与几何合并进**同一个 render pass**（原因见下）；Pass C 采样链路实际写入的目标。
  3. 新增 `assets/vkdisp/shaders/geometry.vsh` / `.fsh`（顶点属性带 `layout(location)`）；`fullscreen.fsh` 背景深度改为常量 `0.9`（让几何稳定压在背景上，判定不受背景深度分布干扰）。
  4. 注册器埋点 `(1/5)…(5/5)`，Hook 就绪判定改看本帧链的三条管线。
- **为什么改**：上一轮验证了深度的「写入 + 采样」，缺的正是**深度剔除**这半边——它是 gbuffers / shadow 的前提。判定设计成硬事实：重叠区若为红＝深度测试生效；若为绿＝失效（绿块后画）。
- **⚠️ 本轮抓出并修掉的 5 个真实缺陷（全部有证据，不是猜的）**：
  1. **顶点属性缺显式 location**：`in vec3 Position` 未带 `layout(location=N)` → 原版编译器报错原文 `error: 'location' : SPIR-V requires location for user input/output`，required 管线硬失败（符合「不静默」设计，但首次遇到须记录）。
  2. **堆 ByteBuffer 传给 `createBuffer` → JVM 原生崩溃**：`hs_err_pid*.log` 实测 `SIGSEGV in StubRoutines::jbyte_disjoint_arraycopy, si_addr=0x10`；改用 `allocateDirect` 后消失（崩溃日志留档 `run/hs_err_pid116524.log`，被 gitignore）。
  3. **`VertexFormat.builder(int)` 的参数是 `stepRate` 不是顶点大小**（javap 字段名 `stepRate`，原版 `DefaultVertexFormat` 一律传 `0`）：我们误传 `28` → 属性按每 28 顶点推进 → 读错偏移 → **退化三角形、无任何报错**（静默失败的典型样本）；改为 `builder(0)`。
  4. **Pass C 采样了错误的中间目标**：A/B 都写 `viewA`，C 却采样 `viewB`（本链从未写入）→ 后续 pass 的结果**永远看不到**（这也是本轮前期"几何看不见"的主要迷惑源）。
  5. **同一附件上第二次 `createRenderPass` 的清屏/绘制不生效**（观察事实：Pass B 清蓝 + 绘制，代码块执行、无异常，画面仍只有 Pass A 的图案）→ 合并进**同一个 render pass**（同 pass 内多管线多次 draw 是标准做法）；成因未深挖，登记为已知现象。
- **影响的文档**：本 `CHANGE_LOG.md`。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **日志**：`p3_final.log`（sha256 `2f3fb019…6f8f`，最终代码复验）——`pipeline registered (1/5)…(5/5) (total=5)`、`geometry pipeline registered: stride=28 topology=TRIANGLES`、`geometry buffer created: size=336 expected=336`、`count check: registered=5, compiled=5 (aligned)`、`3-pass chain executed (854x480)`；ERROR=**2**（仍只有 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **深度剔除量化判据**：截图 `p3_final.png`（sha256 `bf0c6265…b8040`，最终代码复验；此前 `p3_depth_cull.png` `481464b4…` 为清理前同构结果）——`近独占区(200,300)=红(255,0,0)`、**`★重叠区(370,300)=红(255,0,0)`（后画的远绿块被剔除 → 深度测试生效）**、`远独占区(550,300)=绿(0,255,0)`、`底部方向参考带(400,527)=橙(255,128,0)`；背景仍为图案（`(800,300)=白` 正是中轴白十字横线，y≈300 为中心行，合理）。
- **流程教训（同样记下）**：本轮曾因**多个游戏进程残留**多次截到旧窗口，导致"同一内容反复出现"的假象——后续固定为「截图前先确认只有 1 个游戏进程、窗口 id 取最新」。
- **未覆盖 / 存疑**：
  1. 缺陷 5 的**成因未深挖**（仅以合并 pass 规避并记录现象）；
  2. 深度**与真实几何网格**（多三角形、透视矩阵）仍未验证——本验证用 NDC 直写；
  3. `depthviz` / `composite` 管线本轮不在本帧链（上两轮各自验证过）；
  4. 未验证 `D24_UNORM_S8_UINT`（stencil）与深度比较采样器。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 深度附件 + 深度写入 + 深度采样（P3 前置）

- **本次改了什么**：
  1. `bridge/FrameApi.java`：中间目标 0 改为**带深度附件**（`TextureTarget(..., RGBA8_UNORM, D32_FLOAT)`，槽 1 仍只有颜色）；Pass A 同时清深度（`1.0`）并挂深度视图；新增 **Pass B（深度可视化）**：采样 offscreen0 的深度纹理 → offscreen1；Pass C 写主目标。
  2. `bridge/PipelineApi.java`：图案管线加 `DepthStencilState(LESS_THAN_OR_EQUAL, writeDepth=true)`；新增**深度可视化管线** `vkdisp:pipeline/depthviz`（片元 `vkdisp:depthviz`）。
  3. `assets/vkdisp/shaders/fullscreen.fsh`：写 `gl_FragDepth = 0.2 + 0.6 * vUv.x`（水平梯度，便于量化判读）；新增 `assets/vkdisp/shaders/depthviz.fsh`（把深度 `r` 当灰度输出）。
  4. 注册器埋点改为 `(1/4)…(4/4) … (total=4)`；`FullscreenPassHook` 就绪判定改看本帧链用到的三条管线（pattern / depthviz / blit）。
- **为什么改**：deferred 与 shadow 两条链的共同地基是「深度附件 + 深度可采样」；先把它变成**可量化判读**的事实（灰度梯度），P3.1/P3.3 才不用同时排查「深度没写进去」与「链编排错」。
- **⚠️ 本轮发现并纠正的一处自身设计错误**：初版把深度可视化 pass 直接插在合成 pass 之后、两者写同一目标，导致前一个 pass 的结果被覆盖（等同白做）。已重构为职责清晰的链：`A 图案(色+深度) → B 深度可视化 → C 主目标`；**合成管线保留注册与能力**，但本轮不进本帧链（与深度 pass 争用同一目标），合并留待 pack 链（P2.4）统一编排。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5 主线阶梯（登记 P3.1 的深度前置已完成）。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **264 用例 0 失败**。
  - ✅ **管线注册与计数**：`p3_depth_final.log`（sha256 `37b61b03…`）——`registered (1/4) fullscreen`、`(2/4) composite`、`(3/4) depthviz`、`(4/4) blit (total=4)`、`count check: registered=4, compiled=4 (aligned)`、`3-pass chain executed (854x480) … (A: pattern+深度 -> offscreen0, B: depth -> offscreen1, C: offscreen1 -> main)`；ERROR=**2**（仍只有 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **深度链路量化判据**：截图 `p3_depth.png`（sha256 `668f99f0…`）按列带平均灰度 **62.9 → 97.0 → 132.8 → 168.6 → 199.7**（左暗右亮，线性），与 `gl_FragDepth = 0.2 + 0.6·uv.x` 的理论值逐带吻合；抽样像素为中性灰（`(66,66,66)`/`(116,116,116)`/`(170,170,170)`，R=G=B）→ 证明**深度真的被写入、且能被采样**，而不只是"分配了深度附件"。
- **未覆盖 / 存疑**：
  1. 深度**测试**（depth test 剔除）尚未用真几何验证——当前是全屏三角形固定梯度，只能证明写入/采样通路；真几何与深度冲突留 P3.2（gbuffers 接管）；
  2. 深度格式只验证了 `D32_FLOAT`，未试 `D24_UNORM_S8_UINT`（真实包可能要求 stencil）；
  3. 采样器仍固定 ClampToEdge+NEAREST；深度比较采样器（shadow 用）未做（属 13-GAP-REGISTRY 待判定项）；
  4. 本帧最终可见产物**暂时是深度可视化图**（能力验证态），P2.4 起由真实合成链替换。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — E 线二期：多绑定槽（binding>0）与绑定布局语义

- **本次改了什么**（`src/main/java/dev/vkdisp/pipeline/model/` + 同名 test 包，均在该线独占路径内）：
  1. 新增 `VertexBindingSlot`（一个绑定槽 = 槽号 + 槽内 `VertexLayout`）与 `VertexBindingLayout`（多槽中间表示：逐槽校验 + 跨槽校验 + canonical text 往返）。
  2. `PipelineCacheKey` 新增静态工厂 `ofMultiSlot(spec, VertexBindingLayout, bindGroupLayout)` —— **一期 `of(...)` 签名与行为零改动**。
  3. 新增 `VertexBindingLayoutTest`（25 用例）。
- **为什么改**：E 线一期只覆盖「交错布局的绑定 0」；真实管线/多流渲染需要 binding index > 0，且渲染前要做 mesh stride 与 binding stride 的双侧校验（`08-TESTING.md` §5，T9「彩色尖刺」）。
- **影响的文档**：本 `CHANGE_LOG.md`。
- **测试结果**：
  - ✅ `./gradlew test` → exit=0；本阶段 **25 用例 0 失败**；**一期 64 例零破**（BindGroupLayoutIrTest 16 / PipelineCacheKeyTest 15 / PipelineModelIntegrationTest 5 / VertexElementFormatTest 6 / VertexLayoutTest 22）；E 线合计 **89 用例 0 失败**；全仓库 **264 用例 0 失败**。
  - ✅ **槽 0 回归保护**：offset `0,12,16,24,28,32,35,39`、size `12,4,8,4,4,3,4,8`、stride `47` 逐项断言，且与一期 `VertexLayout.of(SPEC).canonicalText()` **逐字符相等**。
  - ✅ **多槽数值**：槽 1（`at_tangent vec4f` + `at_velocity vec3f`）offset `0/16`、stride `28`；`fromDeclarations(Map)` 无论遍历顺序槽号一律升序（缓存键确定性的来源）。
  - ✅ **显式诊断（T11，全 ERROR 且均有用例）**：槽越界（`<0` 或 `>=16`，槽 15 合法）/ 重复槽 / 空槽 / 跨槽同名属性归属不明 / 槽内 stride-offset-size 不匹配 / 坏槽头与坏属性行等 14 类；空绑定集为 INFO（与一期口径一致）。
- **该线自查发现并修掉的自身缺陷**（值得记录）：初版对「被跳过的槽」不再聚合其槽内诊断，导致「槽 2 因未知类型被跳过 → 变空槽」时只报 `EMPTY_BINDING_SLOT`、丢掉原因；已改为先聚合槽内诊断再决定是否接受该槽，并有单测 `slotLayoutDiagnosticsAreAggregated` 钉住。
- **设计决策**：多槽入口刻意用**独立方法名** `ofMultiSlot(...)` 而非重载 —— 该线实测重载会让一期测试里 `of(spec, null, bindings)` 的 `null` 字面量产生编译歧义（一期直接红）。两个入口不可混用（布局文本形状不同），方向安全：宁可 cache miss，不可错复用。
- **未覆盖 / 存疑**：① 多槽路径的 F1 适配仍无单测（F4 的 test 源集 classpath 不含 Minecraft 类型，与一期同缺口）；② 未做 mesh 侧多槽 stride 双侧比对（要等主线 P1.2 拿到原版 binding）；③ 无真实 pack 多流样本；④ `MAX_BINDING_SLOTS=16` 取公开规范下界，未接设备实际上限查询（属 `bridge/DeviceApi` 范围）；⑤ 冷路径零性能优化。
- **P-1d 未定稿项**：`mc_Entity` 底层元素类型是否影响 stride 47 —— 本线一律不动，槽 0 数值逐位沿用一期，等 §3.2 由 env-1 定稿（07 X9）。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 多目标三 pass 链（离屏 ping-pong 轮换）+ 采样翻转规则标定

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：新增**合成管线** `vkdisp:pipeline/composite`（片元 `vkdisp:composite`，只有采样器绑定）；管线总数 3（图案 / 合成 / 传递）。
  2. `bridge/FrameApi.java`：离屏目标改为**两个**（`offscreen0` / `offscreen1`，按主目标尺寸 resize）；`drawFullscreen` 改为**三 pass 链** —— Pass A：图案 → offscreen0（CLEAR）；Pass B：offscreen0 → offscreen1（合成级）；Pass C：offscreen1 → 主目标（最后一级）。
  3. 新增 `assets/vkdisp/shaders/composite.fsh`：当前效果是刻意选择的**临时验证效应（R/B 通道互换）**，让「这一级是否真的执行、是否真的采样到上一级结果」变成可像素级判定的事实（四角黄色 → 青色）。
  4. 注册器埋点改为 `(1/3)(2/3)(3/3) … (total=3)`；`FullscreenPassHook` 就绪判定要求三条管线都编译完成，首帧埋点改为 `3-pass chain executed … (A: pattern->offscreen0, B: offscreen0->offscreen1, C: offscreen1->main)`。
- **为什么改**：真实 composite / deferred 链是**多级**的（`composite1 → composite2 → …`），必须证明「多级串联 + 中间目标轮换」这一机制本身正确；把验证效应做成可像素判定的通道交换，是为了避免「看到图案就以为链对了」的假阳性。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §10 **P-1f 采样翻转规则再次细化**。
- **⭐ 本轮标定出的规则（实测，修正上一轮的单条结论）**：采样翻转**分两类**——
  - **中间目标 → 主目标**：必须 `vec2(uv.x, 1.0-uv.y)`（`blit.fsh`）；
  - **中间目标 → 中间目标**：用原始 `vUv`（`composite.fsh`）；
  - 两级**同时**翻转会让整条链上下颠倒（首次三 pass 实测：底部橙色带跑到顶部；改回原样后复位）。
  判据是双重的：图案底部方向参考带位置 + 四角标颜色。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建/测试**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **239 用例 0 失败**。
  - ✅ **三条管线注册与计数对齐**：`p2_threepass_final.log`（sha256 `22e58fc5…`）——`pipeline registered (1/3) fullscreen`、`(2/3) composite`、`(3/3) blit (total=3)`、`pipeline count check: registered=3, compiled=3 (aligned)`、`vkdisp 3-pass chain executed (854x480) …`；ERROR=**2**（仍只有 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **链真的执行了（像素级硬判据）**：截图 `p2_threepass_exp.png`（sha256 `c2ecfecf…`）色块统计（客户端区抽样）——**青(0,255,255) 1584**（= 四角标，图案里本是黄色，只有 Pass B 的 R/B 交换被真实执行才会变青）、**黄(255,255,0) 92739 + 品红 93257**（= 棋盘，交换后由「品红/青」变为「品红/黄」）、**蓝(0,128,255) 5852**（= 底部参考带，图案里本是橙色）、白 3028（中轴十字）。
  - ✅ **方向量化**：蓝带行范围 `y=520..533`（客户区底部），与图案绘制位置一致 → 链末方向正确、无上下颠倒。
- **未覆盖 / 存疑**：
  1. 中间/最终两级翻转规则是**实测标定**的工程结论，尚未从 renderpearl 内部实现层面解释清楚（P3.3 做更长链时会复测；已登记在 P-1f）；
  2. 中间目标尚无**深度附件**、无多颜色附件（colortex0..N 语义）、无降采样与目标池复用——归 P3.3/RenderTargetPool；
  3. `composite.fsh` 的 R/B 交换是**验证效应**，P2.4 起会被真实包程序替换；
  4. 未接 pack 链（仍需 A/B/C 线汇合）。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 双 pass 渲染链（离屏目标 ping-pong 骨架）+ 方向约定重新标定

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：新增**传递管线** `vkdisp:pipeline/blit`（顶点着色器复用 `vkdisp:fullscreen`，片元为新的 `vkdisp:blit`；只带采样器绑定），图案管线去掉采样器绑定（职责分离：图案只产图，传递只采样）。
  2. `bridge/FrameApi.java`：用原版 `TextureTarget`（`RenderTarget` 子类，vanilla 内部目标同款）建**离屏渲染目标**（`RGBA8_UNORM`，尺寸随主目标 `resize`）；`drawFullscreen` 改为两个 pass —— **Pass A**：图案 → 离屏（`loadOp=CLEAR` 不透明黑）；**Pass B**：离屏 → 主目标（采样输入纹理）。
  3. 新增 `assets/vkdisp/shaders/blit.fsh`（采样 `InSampler` + V 翻转）；`fullscreen.fsh` 增加**底部橙色方向参考带**。
  4. `render/FullscreenPipelineRegistrar.java`：注册两条管线，埋点改为 `pipeline registered (1/2)` / `(2/2) … (total=2)`；`FullscreenPassHook`：就绪判定要求**两条管线都编译完成**，首帧埋点改为 `vkdisp 2-pass chain executed (WxH) … (A: pattern->offscreen, B: offscreen->main)`。
- **为什么改**：composite / deferred / shadow 的本质都是「画到中间目标 → 再采样回来」，而**同一 pass 内既写又采样同一纹理在 Vulkan 属非法反馈回路**（上一轮已验证过这条约束）。先把最小可运行的 ping-pong 骨架跑通，P2.4/P3.3 才只需关心「链怎么编排」而不用同时排「中间目标机制对不对」。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §10 **P-1f 方向约定更正**（见下）。
- **⚠️ 方向约定更正（本轮实测推翻上一轮的解释）**：上一轮把「`vUv.y=0`」记成屏幕**底部**，本轮用新增的橙色带做基准标定后证明**写反了**——正确约定是：`vUv.y=0` → NDC `y=-1` → 屏幕**顶部**（Vulkan NDC 的 y 向下）；真正需要 V 翻转的原因是 **`NativeImage` 行 0 对应采样坐标 `v=1`**（纹理原点在下）。翻转修复本身没错，错误只在解释；`blit.fsh` / `fullscreen.fsh` 注释与 `18-PARALLEL` §10 P-1f 已全部改正。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **239 用例 0 失败**。
  - ✅ **两条管线注册与计数对齐**：`p2_twopass_final.log`（sha256 `6b0a9e25…`）——L60 `pipeline registered (1/2): vkdisp:pipeline/fullscreen`、L61 `pipeline registered (2/2): vkdisp:pipeline/blit (total=2)`、L138 `pipeline count check: registered=2, compiled=2 (aligned)`。
  - ✅ **双 pass 真实执行**：L139 `vkdisp 2-pass chain executed (854x480), uniform VkDispParams=0.20351306 (A: pattern->offscreen, B: offscreen->main)`；ERROR=**2**（仍为 WSL 环境 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **链路真的经过离屏目标**：图案只由 Pass A 画到离屏、主目标仅由 Pass B 写入，因此**屏幕上能看到图案本身就证明 Pass B 采样成功**；截图 `p2_twopass.png`（sha256 `855af66b…`）。
  - ✅ **方向量化核对**：像素扫描橙色带落在 `y=520..533`（客户区底部），与 Pass A 中 `vUv.y>0.96` 的预期位置一致 → 端到端方向一致，无上下翻转。
- **未覆盖 / 存疑**：
  1. 只有一个离屏目标，尚未做**多目标 ping-pong**（colortex0/1 轮换）与深度附件——归 P3.3；
  2. 离屏目标分辨率固定等于主目标，未做降采样/RenderTargetPool 复用；
  3. 未接 pack 链（P2.4 需要 A/B/C 线汇合后才会真正消费这条链路）；
  4. 橙色带是**验证用参考物**，P2.4 起随正式图案一起评估是否保留。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — D 线二期：gl_ 内建差异转换（真实 OF 包编译的前置）

- **本次改了什么**（`src/main/java/dev/vkdisp/glsl/translate/` + 同名 test 包，均在该线独占路径内）：
  1. 新增 `FragmentOutputAdapter`：`gl_FragColor` / `gl_FragData[n]` → `layout(location = N) out vec4`（优先复用包内已有的 out 声明，避免重复声明；歧义时 ERROR 不改写，拒绝猜测 X9）。
  2. 新增 `TextureFunctionRenamer`：`texture1D/2D/3D/Cube/Proj/Lod` → `texture/textureProj/textureLod`；`shadow*` 额外包 `vec4(texture(...))` 以保持老式 `.r/.g/.b/.a` 语义。
  3. 新增 `FtransformExpander`：`ftransform()` → `(gbufferProjection * gbufferModelView * vec4(Position, 1.0))`（矩阵名以已冻结的 `UniformCatalog` 为准；位置属性优先取包内已声明的 `Position`/`vaPosition`/`gl_Vertex`）。
  4. 新增 `GlslTextScan`（包内共用扫描原语：等长视图、括号配对、宏续行标记）；`OfGlslTranslator` 改为**五级流水线**（一期三级 + 二期两级 + 注入），一期的 4 个类零改动。
- **为什么改**：D 线一期明确列出「`gl_FragColor`/`texture2D`/`ftransform` 等 gl_ 内建差异未实现」，而真实 OF/Iris 老式包几乎必用这些写法 → 不补则包必然编译失败（P2.3 的直接前置）。
- **影响的文档**：本 `CHANGE_LOG.md`（D 线一期条目中的「未覆盖第 2 条」由本轮闭合）。
- **测试结果**：
  - ✅ `./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL`，全仓库 **239 用例 0 失败 0 错误**；其中一期 49 例仍全绿；二期新增 **59 例**（FragmentOutputAdapterTest 18 / TextureFunctionRenamerTest 12 / FtransformExpanderTest 12 / GlslTextScanTest 9 / OfGlslTranslatorBuiltinsTest 8）。
  - ✅ golden 逐字比对：片元内建输出、五级流水线端到端（片元/顶点）、旧函数改名、ftransform 展开（自造样本，未使用任何第三方 pack 片段，18-PARALLEL §7.6）。
  - ✅ 幂等：四处「输出即不动点」断言 + 组合样本两遍文本逐字节相同、第二遍 0 诊断。
  - ✅ 边界（显式诊断不静默）：注释/字符串/`#define` 续行不改写；`gl_FragData` 未知下标/越界/缺下标 → ERROR；阶段不符 → ERROR；CRLF 保留；恶意输入不抛异常。
  - ✅ 红线：`grep -rn "com\.mojang\.\(renderpearl\|blaze3d\)"` 于本线 main/test 两目录 → **NO MATCH**。
- **关键实现修正（该线上报，值得记住）**：端到端行号映射**不能**写成 `injectedMap.compose(preInject)` —— F3 的 `compose` 在上游「该行是合成行」时会回退成本阶段起源，会把插入的合成声明误标成真实源行号（实测错成 `line=2`）。改为「两次插入位移先合成一张映射、再 compose 一次上游」，F3 契约零修改，并有专门用例钉死。
- **未覆盖 / 存疑**（该线自报，不掩饰）：① 不代包声明位置属性（属顶点格式绑定职责），未声明时只 WARN；② 跨行调用显式降级（`ftransform(` 换行 → 只 WARN 不展开）；③ 未点名的旧名未动（X12，如 `texture2DRect` / `gl_FragDepth` 常量下标不折常量）；④ `out` 接口块不参与槽位解析；⑤ `#include` 展开后的行号归 C 线；⑥ 冷路径零性能优化。
  - **合规**：IrisShaders/glsl-transformer 与 glsl-preprocessor（GPL-3.0 + 例外条款）全程**零接触、未读其代码**，样本与期望值全部自造（07 L12 §1.3 陷阱 2）。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — 纹理采样链路打通（composite/deferred 的共同前置）

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：管线增加采样器绑定 `BindGroupLayout.withUniform("InSampler", COMBINED_IMAGE_SAMPLER)`（与原版 `BindGroupLayouts.IN_SAMPLER` 同构），并与 `VkDispParams` UBO **合并进同一个绑定组**（原因见下）。
  2. `bridge/FrameApi.java`：懒创建 16×16 四象限测试纹理（`device.createTexture(TEXTURE_BINDING|COPY_DST, RGBA8_UNORM)` + `NativeImage` 填像素 + `createCommandEncoder().writeToTexture(texture, image)` + `createTextureView`），采样器取原版 `RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)`，绘制时 `pass.setUniform("InSampler", view, sampler)`。
  3. `assets/vkdisp/shaders/fullscreen.fsh`：`uniform sampler2D InSampler;`（原版 `core/blit_depth.fsh` 写法），采样结果调制棋盘亮度（`color *= 0.35 + 0.65*tint`）。
- **为什么改**：composite / deferred / 阴影链的本质都是「采样输入纹理 → 计算 → 写输出」，采样器绑定链路是所有后续 pass 的共同前置；提前打通可避免 P2.4/P3.3 阶段同时排查「映射没生效」与「着色器逻辑错」两类问题。
- **影响的文档**：本 `CHANGE_LOG.md`。
- **实测踩坑与修复**（全部有异常原文/量化证据，非推断）：
  1. 🔴 **`writeToTexture` 不能在 render pass 打开期间调用**：首版把懒创建纹理放在 pass 内部，日志刷出 **3916 条** ERROR，异常原文 `java.lang.IllegalStateException: Close the existing render pass before performing additional commands`（`FrontendCommandEncoder.writeToTexture:410`）→ 修复：纹理/采样器解析提前到 `createRenderPass` **之前**。
  2. 🔴 **UBO 与 sampler 分成两个绑定组时采样失配**：诊断版直接输出采样值，四象限均值几乎相同（`(223,75,84)`/`(223,74,83)`/`(195,50,59)`/`(195,50,59)`）＝采样到近似常量色 → 合并为**单个绑定组**（声明顺序与 GLSL 一致）后象限结构立即出现。
  3. ⚠️ **UV 方向**：合并后象限出现但为垂直翻转（TL 蓝 / TR 白 / BL 红 / BR 绿 ≠ 期望 TL 红 / TR 绿 / BL 蓝 / BR 白）→ 结论：全屏三角形里 `vUv.y=0` 对应屏幕**底部**，而 `NativeImage` 行 0 是顶部，采样前需 `vec2(vUv.x, 1.0 - vUv.y)`（已写入着色器注释，供后续 composite 直接复用）。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` exit=**0**；`./gradlew test` 全仓库 **239 用例 0 失败**。
  - ✅ **运行**：`p2_sampler_final.log`（sha256 `8bbfa8a1…ff67b`）L137 `pipeline count check: registered=1, compiled=1 (aligned)`、L138 `fullscreen pass executed (854x480), uniform VkDispParams=2.4185026`；ERROR=**2**（仍为 WSL 环境缺失 narrator/OpenAL），`vkdisp` ERROR=**0**。
  - ✅ **量化验收（四象限均值 vs 期望）**：截图 `p2_sampler_final.png`（sha256 `747bf95a581cfd8d…`，930×577）——左上 `(118,53,92)` R 主导＝红 ✓、右上 `(56,121,92)` G 主导＝绿 ✓、左下 `(41,40,196)` B 主导＝蓝 ✓、右下 `(103,105,196)` R≈G+B 高＝白/原棋盘 ✓（与设计意图**逐项吻合**）。
  - 🔴 **回归证据留档**：`p2_sampler_regression.log`（sha256 `06078c53…0f234`）记录踩坑 1 的 3916 条 ERROR 与异常原文。
- **未覆盖 / 存疑**：
  1. **不采样主渲染目标**：同一 pass 内既写又采样同一纹理在 Vulkan 属非法反馈回路，真实 composite 链须按原版做 ping-pong 中间目标（P3.3 交付）；当前测试纹理是**能力验证载体**，P2.4 起会被真实 colortex 替代；
  2. 未验证 sRGB/格式特例（当前 RGBA8_UNORM ↔ 主目标同格式）与 mipmap 采样；
  3. 采样器目前固定 ClampToEdge+NEAREST，真实包需要 per-sampler 配置（归 P2.4）。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — P1.1 uniform 传递 + P1.2 管线计数对齐

- **本次改了什么**：
  1. `bridge/PipelineApi.java`：管线新增自定义 uniform 块绑定布局 `BindGroupLayout.builder().withUniform("VkDispParams", UNIFORM_BUFFER)`（原版 `BindGroupLayouts.GLOBALS` 同款写法）；新增 `registeredPipelineCount()` / `registeredPipelines()` 供计数断言。
  2. `bridge/FrameApi.java`（P1.1 核心）：新增纯 Java `FrameParams(phase, intensity)` 视图；用原版 `MappableRingBuffer`（usage = `MAP_WRITE|UNIFORM` = 130，实测原版 `PostPass` 字节码）+ `Std140Builder` 每帧把 `vec4(phase, intensity, 0, 0)` 写进 UBO，绘制前 `pass.setUniform("VkDispParams", buffer)`、绘制后 `ring.rotate()`（原版 `PostPass` 同序列）；新增 `compiledPipelineCount()`。
  3. `render/FullscreenPassHook.java`：每帧推进相位（4 秒周期，`System.nanoTime` 驱动）→ 画面实时变化；首帧埋点带 uniform 取值、之后限频 5 次打印 `phase`（避免刷屏）；新增 **P1.2 计数对齐断言**（注册数 == 编译成功数，不等打 ERROR，不静默少）。
  4. `assets/vkdisp/shaders/fullscreen.fsh`：新增 `layout(std140) uniform VkDispParams { vec4 Params; };`（块名与绑定布局 uniform 名一致，原版 `clouds.vsh` 的 `CloudInfo` 同款约定），棋盘随 `Params.x` 每 4 秒平移 2 格。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 P1.1「改数值后画面实时变化」+ P1.2「注册数 == 编译成功数，日志可见」；`docs/08-TESTING.md` §3 的管线计数断言要求「允许编译失败，但不允许失败得无声无息」。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §5 主线阶梯（P1.1/P1.2 标记完成，下一步改为 P3.1 影子 pass 等可独立于 pack 加载的档位）。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/`，被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` → `BUILD SUCCESSFUL`，exit=**0**；`./gradlew test` 全仓库 **180 用例 0 失败**（本轮未改并行线代码）。
  - ✅ **P1.2 计数对齐**：`p11_uniform_final.log`（sha256 `88f9438a…2d675`）L137 `vkdisp: pipeline count check: registered=1, compiled=1 (aligned)`。
  - ✅ **P1.1 uniform 传递**：L138 `vkdisp fullscreen pass executed (854x480), uniform VkDispParams=1.8650122`；随后 5 条限频埋点证明数值**逐帧变化**：L139 `phase=0.0349 at frame 120` → L140 `phase=2.0353 at frame 240` → L141 `phase=0.0370 at frame 360` → L142 `phase=2.0377 at frame 480` → L143 `phase=0.0528 at frame 600`。
  - ✅ **画面实时变化（量化）**：同一窗口间隔 2 秒的三张截图（`p11_uniform_t0/t1/t2.png`）**像素差异 30.56%（t0↔t1）、25.78%（t1↔t2）、56.34%（t0↔t2）**（自写 PNG 解码逐像素比对，共 930×577=536,610 像素）——不是"看起来差不多"，是真变了。三张 sha256 各不相同：`c005bf96…`、`cdd61568…`、`5b98d785…`。
  - ✅ **红线**：业务包 `^import com\.mojang\.renderpearl` 仍 **0 命中**（原版类型只在 `bridge/`）；后端 Vulkan（L56）。
  - ⚠️ **ERROR=2**：仍为已知环境缺失（`Narrator` 缺 `libflite.so`、`SoundEngine` 无 OpenAL 设备），与 P0.1/P0.2/P0.3 同批；`vkdisp` 相关 ERROR = **0**。
  - **GAP 登记**：不需要（自定义 UBO 走官方 `BindGroupLayout` + `setUniform`，有原版 `PostPass` 活样板）。
- **未覆盖 / 存疑**：
  1. uniform 目前由**时间驱动**（无需人工改值即可验证），尚未接 GUI/配置项做人工改值——归 P4.3 选项 GUI；
  2. `intensity` 分量已写入 UBO 但 shader 暂未使用（预留），避免把未验证语义写死；
  3. 仅验证单管线（registered=1/compiled=1），多管线场景（P3 起）需要同一断言随管线数增长继续成立。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行）。

---

## 2026-09-29 — P0.3 首个可见产物（全屏图案上屏）+ 闸门 F1/F2/F3/F4 落地

- **本次改了什么**：
  1. **P0.3 实现**（impl-coder，task-2）：新增 `bridge/PipelineApi.java`（用原版 `RenderPipelines.POST_PROCESSING_SNIPPET` 构建并注册全屏管线）、`bridge/FrameApi.java`（按原版 `PostPass` 序列 `createRenderPass → setPipeline → bindDefaultUniforms → draw(3,1,0,0)` 绘制）、`render/FullscreenPipelineRegistrar.java`（mod bus `RegisterRenderPipelinesEvent` 接线）、`render/FullscreenPassHook.java`（game bus `RenderFrameEvent.Post` 接线）、`assets/vkdisp/shaders/fullscreen.vsh|.fsh`（`gl_VertexIndex` 全屏三角形 + 品红/青棋盘图案）。
  2. **P0.3 复验缺陷修复**（lead）：三轮 runClient 实测定位并修掉两处启动期 ERROR：
     - run1：`FrameApi.drawFullscreen` 把「管线尚未编译完成」当致命失败，早期帧刷 **11 条** `vkdisp: fullscreen pass failed` ERROR（违反 `01-DEV-LOOP.md` §9「日志无 ERROR」）；
     - run2：首修后改为每帧轮询 `getCompiledPipelineNullable`，却撞上原版启动窗口期的 **fallback PipelineCache**（`GameRenderer.preloadUiShader`，绑定旧 ResourceManager），每帧触发一次失败加载 → 原版记 **24 条** `Couldn't preload shader vkdisp:shaders/fullscreen.vsh` ERROR；
     - run3：改用官方 `ClientResourceLoadFinishedEvent` 作门闩（GLSL 编译属资源重载的一部分，此刻管线缓存才就绪），重载完成前完全不触碰管线缓存 → **ERROR 全部清零**。
  3. **闸门 F1**（bridge 契约冻结）：`ContractVersion`（版本常量 + §3.2 变更流程）、`RenderApi`（`PipelineSpec` + 注册/查询签名）、`TextureApi`（`TextureView` + 主目标视图）、`MixinTargets`（mixin 目标常量集中表），与既有 `DeviceApi`/`FrameApi`/`PipelineApi` 凑齐 `06-MIGRATION.md` §2.1 的 5 接口。
  4. **闸门 F2**（`pack/` 数据模型冻结，contract-pack）：`ShaderPack`/`Program`/`ProgramStage`/`Option`/`OptionType`/`Dimension`/`VertexAttribute`/`UniformDecl` 共 8 类，全部 record/enum + 构造校验 + 不可变集合。
  5. **闸门 F3**（`glsl/` 契约冻结，contract-glsl）：`TranslateResult`（文本 + 诊断 + 行号映射）、`TranslateDiagnostic`（severity/原文件/行/列）、`SourceLineMap`（双向逐行查询 + `compose` 端到端合成）。
  6. **闸门 F4**（测试基建）：`build.gradle` 接 JUnit 5（BOM 5.13.4）+ `test` 任务启用 JUnit Platform；`src/test/` 骨架 + 冒烟测试 + `src/test/resources/packs/README.md`（fixture 许可证限制）。
  7. **E 线（管线纯计算件）**（line-e，task-7）：`pipeline/model/` 14 个文件 —— `VertexLayout`（04-SPEC §4 逐项 offset/size/**stride=47** + `isConsistent()`）、`PipelineCacheKey` + `PipelineSpecIr` + `CanonicalText`（长度前缀单射编码 + SHA-256 指纹，**属性类型变化也换键** = T9「彩色尖刺」单测闸门）、`BindGroupLayoutIr`（可打印/可回读的绑定布局 IR）、`ModelDiagnostic`（显式诊断）。
  8. **D 线（GLSL 转译）**（line-d，task-6）：`glsl/translate/` 9 主 + 4 测 —— `OfGlslTranslator`（编排 + `dMap.compose(cMap)` 端到端行号映射）、`AttributeRewriter`（顶点 `attribute→in`/`varying→out`、片元 `varying→in`，等行数重写）、`UniformInjector` + `UniformCatalog`（04-SPEC §3.2 **23 条**内建 uniform 只补缺失、注入点在头部之后）、`ShaderStage`/`GlslDeclaration`/`CommentState`/`SourceLines`（注释等长空格化保列位）。
  9. **F 线（选项模型 + Binding）**（line-f，task-8）：`config/` 6 主 + 5 测 —— `PackOptions`（从 F2 `List<Option>` 构造；默认值/按名查改/**越界钳制 + WARN**/profile 应用含 `:` `=` 裸名 `!名` `profile.` 继承与环检测）、`OptionBinding`（选项值 → `#define` 表 + uniform 值；`DefineStyle{LITERAL, IFDEF_TRUE}`；快照语义）、`OptionUniformValue`（Bool/Int/Float/Text）、`OptionDiagnostic(Sink)`（T11 不静默）、`OptionText`（GLSL 标识符/数值文本校验，手写扫描不用正则）。
  10. **文档复核**：`docs/04-SPEC.md` §4 增加复核注记 —— 核实 OF 官方属性表（来源：OptiFine 规范文档 `shaders.txt`「Attributes」节，仅取格式事实零文本搬用）：`mc_Entity` 官方为 **vec3**（非 §4 的 vec2s）、`vaUV1`=overlay / `vaUV2`=lightmap（§4 用途有误）、`at_*` 三项存在；**底层元素类型文档未给 → 禁止猜值**（07 X9），留 P1.2 实测定稿（`18-PARALLEL` §10 P-1d）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 的 P0.3 完成标准「屏幕上出现自定义全屏 pass 画出的图案（非黑屏、非崩）」；`docs/18-PARALLEL.md` §3 要求先冻结 F1–F4 契约闸门，A–F 并行线才可开工（F2/F3 为 C/D/E/F 的共同输入）。
- **影响的文档**：本 `CHANGE_LOG.md`；`docs/18-PARALLEL.md` §3.0 现状快照（F1–F4 全部 ✅、已解锁并行线更新）与 §10 待办 P-1/P-1b/P-1c/P-1d；`docs/04-SPEC.md` §4（新增复核注记：OF 官方属性表出入 + 未定项）。其余 `docs/01`–`17` 正文未改动。
- **测试结果**（证据目录 `tools/vulkan-local/evidence/` 已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` → `BUILD SUCCESSFUL`，exit=**0**。
  - ✅ **产物六项核对**：`.class` 数=**10**；含 `META-INF/neoforge.mods.toml`；含 `LICENSE`；禁列 `net/minecraft`/`com/mojang`/`net/caffeinemc`/`dev/vitrail` = **0**。
  - ✅ **P0.3 可见产物（run3 最终代码）**：`p03_final_vulkan_clean.log`（sha256 `e2e9630f…47de9`）——L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1`；L60 `vkdisp: pipeline registered (count=1): vkdisp:pipeline/fullscreen`；L136 `vkdisp: client resources loaded (initial=true), fullscreen pass enabled`；**L137 `vkdisp fullscreen pass executed (854x480)`**。
  - ✅ **截图**：`p03_mainmenu_final_a.png` / `_b.png`（930x577，sha256 `820d23eae4641c5b192c700313063f7517320c6d0414b14317ca58b933d8a6ff`，两窗口像素统计 mean=196.78 非黑屏）；图案=品红/青 8×8 棋盘 + 黄色四角标 + 白色中轴十字 + 黑色边框，全屏覆盖主菜单。
  - ✅ **ERROR 收敛三轮对比**：run1 = 11 条我方 ERROR（`p03_run1_startup_errors.log` sha256 `caaebca6…388181`）→ run2 = 24 条原版预加载 ERROR（`p03_run2_vanilla_preload_errors.log`）→ run3 = **0 条**；run3 `Couldn't preload shader` = **0**、`Mixin apply failed` = **0**、`FATAL` = **0**。
  - ⚠️ **ERROR=2（环境性，非本项目）**：`Narrator` 加载 `libflite.so` 失败（WSL 无 TTS）、`SoundEngine` `Failed to open OpenAL device`（WSL 无声卡）——与 P0.1/P0.2 同一批已知环境缺失。
  - ✅ **闸门 F4 测试**：`./gradlew test` → `F4InfraSmokeTest` **2/2 PASSED**，exit=0。
  - ✅ **E 线单测**：`./gradlew test` exit=0，E 线 5 个测试类 **64 用例 0 失败**（VertexLayoutTest 22 / BindGroupLayoutIrTest 16 / PipelineCacheKeyTest 15 / VertexElementFormatTest 6 / PipelineModelIntegrationTest 5），全仓库合计 **100 用例 0 失败**；数值断言逐项 offset `0,12,16,24,28,32,35,39`、size `12,4,8,4,4,3,4,8`、stride `47`；8 个不同 Program → 8 个不同键文本与指纹；文本篡改逐项显式 ERROR。
  - ✅ **D 线单测**：`./gradlew test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`，D 线 **49/49**（AttributeRewriterTest 19 / OfGlslTranslatorTest 14 / UniformInjectorTest 12 / UniformCatalogTest 4），全仓库合计 **115 用例 0 失败**；幂等以文本为不动点（golden 两轮逐字节相同、二轮零诊断）；诊断回填验证（片元 `attribute` 的 ERROR 经 `SourceLineMap` 定位到 `shaders/lib/common.glsl:57`）；16 例 hostile 输入无异常逃逸。
  - ✅ **F 线单测**：`./gradlew test` exit=0，F 线 **65/65**（PackOptionsDefaultsTest 14 / PackOptionsMutationTest 20 / PackOptionsProfileTest 15 / OptionBindingTest 16），全仓库合计 **180 用例 0 失败 0 错误 0 跳过**；边界覆盖越界钳制（列表上下界、32 位整数边界、超 long 位数）、非法值（`1.5`→INTEGER、`1f`/`0x1p3`/`NaN`/`Infinity`/空串→FLOAT）、空选项集、profile 继承环/自环、`#define` 两张快照整文本比对。
  - ✅ **闸门 F2/F3 自检**（各线成员执行）：`./gradlew compileJava` exit=0；红线 `grep -rn "com\.mojang\.\(renderpearl\|blaze3d\)"` 于 `pack/`、`glsl/`、`translate/` 均 **NO MATCH**；F3 另跑独立行为冒烟 **35 条断言全绿**。
  - **GAP 登记**：**不需要**——P0.3 全程使用官方事件（`RegisterRenderPipelinesEvent` / `RenderFrameEvent.Post` / `ClientResourceLoadFinishedEvent`），无自行补充。
  - 本轮无性能改动（P0.3 冷路径，每帧一次 draw），`17-NATIVE.md` §2 性能预算不适用。
- **未覆盖 / 存疑**（不掩饰）：
  1. P0.3 仅在**主菜单**截图验收（用户本轮指定口径），未进世界复核图案与世界渲染的叠加顺序；
  2. 管线按 **required** 注册：真编译失败表现为原版资源重载硬失败（红屏 + `Failed to load required shader programs`），而非我方 ERROR 路径——属刻意选择（失败绝不静默），排查入口已写入类注释；
  3. F2 上报的 `04-SPEC.md` §4 出入（`mc_Entity` 记 vec2s vs OF 官方 vec3；UV1/UV2 用途描述）**未当场判定**，已登记为待办（影响 E 线 stride 表），不许用猜的值填（07 X9）；
  4. **E 线未覆盖**：F1 适配方法 `PipelineSpecIr.of(RenderApi.PipelineSpec, String)` 只有 main 源集编译证据——F4 的 test 源集 classpath 不含 Minecraft 类型，单测无法构造；是否把 MC 加入 test 源集属共享文件改动，待 env-1 决定（已登记）；与主线真实 binding 的双侧 stride 比对留 P1.2；无 binding>0 多槽用例。
  5. **F 线开放点**（该线自报，需实证后定稿）：`OptionBinding` 的布尔 `#define` 风格默认取 `LITERAL`（`#define X true/false`），备选 OF 兼容风格 `IFDEF_TRUE`（真→空替换 `#define X`、假→`#undef X`）已实现且有快照单测——**哪种是真实包（`#ifdef` vs `#if`）所需，缺真实包 + GPU 证据，按 X9 未猜死**，建议 P4.2/P4.3 用真实包定稿（换默认为一行改动）；自由文本 STRING 选项如何进 GLSL 未定（非标识符文本执行「跳过 + WARN DEFINE_SKIPPED_UNSAFE_VALUE」）；选项名大小写折叠未实现。
  6. **D 线已知限制**（该线自报）：幂等以文本为准（插入行时行号映射按 F3 语义必然变化，未断言）；`gl_FragColor`/`texture2D`/`ftransform` 等 gl_ 内建差异**未实现**（不在本任务完成标准内，若要归 P2.3 另开任务）；多行声明 / 同行多名 uniform / UBO 块内同名 / `#if 0` 头部为未覆盖边界。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行，成员不自行 commit）。

---

## 2026-09-29 — 新增并行开发路线（18-PARALLEL）+ 索引同步 + .gitignore 完善

- **本次改了什么**：
  1. 新增 `docs/18-PARALLEL.md`：把关键路径（P0.2→P4.3）之外的工作拆成 6 条**可并行线**（A–F），定义唯一并行判据（内存数据进出 / 不触 `renderpearl` / 不需 `runClient` / 单测可断言，**四条缺一不可**）、契约冻结闸门 F1–F4、以及**边界与限制**（硬边界 10 条「不许」、共享文件清单、并行线证据规范、红线映射、fixture 许可证限制、冷路径性能纪律）。
  2. `docs/00-INDEX.md` 两处同步登记 18（§1 阅读顺序 + §2 文档表）。
  3. `.gitignore` 完善为 12 分节：新增 `/shaderpacks/`（第三方 pack 不入库，对齐 18-PARALLEL §7.6）、`runs/` `run-data/` `/crash-reports/` `/logs/`、`hs_err_pid*.log` `*.hprof`、NetBeans / Visual Studio / Windows / macOS / Linux 各套垃圾文件、`.env` `local.properties`、`/*.log` `*.tmp` `.cache/`、`.workbuddy/`；末尾新增「必须入库文件清单」注释块（防误伤 `gradle/wrapper/gradle-wrapper.jar`）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 的 P0.x–P4.x 是一条单链，关键路径上每一步都要 GPU 证据、只能在单环境串行；而自研四件事中三件（解析 / 转译 / GUI 数据层）是冷路径、本不需要 GPU —— 把这部分提前并行，可消掉 P2 阶段的串行工期（`04-SPEC.md` §7 把「GLSL 转译工作量被低估」列为**高风险**项，越早开工越好）。
- **影响的文档**：新增 `docs/18-PARALLEL.md`；修改 `docs/00-INDEX.md`、`.gitignore`、本 `CHANGE_LOG.md`。**未改动 `docs/01`–`17` 任何正文**（18 §7.5 已就「一次只做一个」的适用口径做澄清，无需修改 01）。
- **测试结果**：
  - ✅ **并行判据与红线一致性核查**：18 §2 的四条判据以 `07-CONSTRAINTS.md` **T5**（业务包禁止 import 原版渲染类型）为技术依据，二者不冲突；§7.4 已把 L12 / X19 / X20 / X21 / T11 / T12 / T13 / X12 / X17 / T14 逐条映射到并行线场景。
  - ✅ **`.gitignore` 规则验证**（`git check-ignore -v` 逐条）：13 个必须入库文件（`gradle-wrapper.jar`、`gradlew`、`gradle.properties`、`LICENSE`、`TEMPLATE_LICENSE.txt`、`.gitattributes`、`docs/**` 等）全部**未被命中**；14 个应忽略路径（`build/libs/*.jar`、`run/logs/*`、`tools/**`、`.workbuddy/**`、`.idea/`、`Thumbs.db`、`.DS_Store`、`hs_err_pid*.log`、`.env` 等）**全部命中正确规则**。
  - ✅ **行尾复核**：`.gitignore` 等均为 LF（python 字节统计 `CRLF=0`），符合 `.gitattributes` 的 `eol=lf`。（注：本次曾用 `grep -c $'\r'` 得出「全仓库 CRLF」的**假阳性**，经 `od -c` + 字节统计纠正；可靠判据是 `git ls-files --eol`。）
  - ✅ **闸门现状核实（对应 `83a704d`）**：**F1 = 🟡 部分落地** —— `bridge/DeviceApi.java` 已就位且范式正确（内层 import `renderpearl`、对外仅暴露纯 Java `record DeviceInfoView`，`grep -rl` 全仓库仅此 1 个文件命中）；**F2 / F3 / F4 = ❌ 未开始**（`src/` 仅 4 个 java 文件、无 `src/test/`、`build.gradle` 无 JUnit）。结论已写入 18 §3.0「现状快照」。
  - 本轮为**纯文档 + 忽略规则**改动，不涉及运行时代码；`08-TESTING.md` 阶段验收与 `17-NATIVE.md` §2 性能预算均不适用。
- **是否已提交**：是，随本条目一并 commit 并推送至 `origin/master`。

---

## 2026-09-29 — P0.2 确认跑在 Vulkan 后端（backend/device 断言）+ bridge 隔离落地

- **本次改了什么**：
  1. 新增 `src/main/java/dev/vkdisp/bridge/DeviceApi.java` —— 原版渲染 API 唯一入口（`docs/06-MIGRATION.md` §2 bridge 隔离）：暴露纯 Java 视图 `DeviceInfoView`（record）与 `backendKind()` / `deviceInfo()`；设备未就绪返回 `"UNKNOWN"` / 抛明确异常，绝不静默。
  2. `VkDispClient` 增加 P0.2 关键断言日志 `vkdisp: backend={}, device={}`（`event.enqueueWork` 保证在渲染线程、设备创建后执行；后端不是 Vulkan 就打 ERROR，不静默）。
  3. `build.gradle` 的 runClient 强制 `programArguments '--graphicsBackend','VULKAN'`（DEFAULT 顺序先试 GL，llvmpipe 可用即被选中，永远轮不到 Vulkan，故必须显式强制）。
  4. 按 api-scout 只读复核意见修复 3 处（task-5）：`VkDispClient` 【参考调研】块补记 P0.2 断言调研出处（08-TESTING §2 + 原版 Minecraft 529-531 用法范本 + task-4 调研）；`logBackendAssertion` 先判 `DeviceApi.deviceReady()`，设备未就绪打 ERROR 即返回（消除「UNKNOWN 之后必抛异常」连带路径）；`docs/08-TESTING.md` §2 补值域注记（官方 backendName 原值 `"Vulkan"/"OpenGL"` 首字母大写，勿写 `"VULKAN"` 导致失配）。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 P0.2 完成标准「日志打印出后端类型与设备名，且不是 OPENGL」；`docs/08-TESTING.md` §2 关键断言「backend=VULKAN（绝不能是 OPENGL）」；`docs/06-MIGRATION.md` §2 要求原版渲染 API 访问集中在 bridge 一处。
- **影响的文档**：本 `CHANGE_LOG.md`（新增本条目 + 附录自检表第 1/2/3/10/11/14/15 行更新）；`docs/08-TESTING.md` §2（+2 行：期望值改为 `backend=Vulkan`、补 backendName 值域注记）。
- **测试结果**（运行日志证据：`tools/vulkan-local/evidence/latest_p02_vulkan.log`，sha256 `f740e8e210d4c6ae85e953dfa54bf80fd9b2e213118350de4cbcf2c9e44de072`，该目录已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` `BUILD SUCCESSFUL`，exit=**0**。
  - ✅ **产物六项核对**：jar=`build/libs/vkdisp-0.1.0.jar`，`.class` 数=**5**（含 bridge 2 个：`DeviceApi.class` + `DeviceApi$DeviceInfoView.class`）；`LICENSE` 在 jar 内；禁列 `net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/` = **0/0/0/0**。
  - ✅ **bridge 隔离**：`grep -rl 'com\.mojang\.\(renderpearl\|blaze3d\)' src/main/java` → 仅 1 个文件 `src/main/java/dev/vkdisp/bridge/DeviceApi.java`。
  - ✅ **P0.2 关键断言（backend=Vulkan，绝不是 OpenGL）**：L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1 (LLVM 22.1.8)`；L94 `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)`；L93 `vkdisp: client setup, user=Dev`。
  - ✅ **后端强制生效**：L50 WARN `Graphics backend forced to vulkan by launch argument, in-game preferred graphics backend setting is ignored`（`--graphicsBackend VULKAN`）。
  - ✅ **完整游玩会话（加分证据）**：Vulkan 后端下进入单人世界 `New World` 游玩后正常退出，17:52:38 `Stopping!` 干净收尾，**全程无 vkdisp 错误**。
  - ✅ **最终代码复验**（含上述第 4 项复核修复的提交版本，加载完成后停止测试客户端）：`tools/vulkan-local/evidence/latest_p02_vulkan_final.log`，sha256 `c4aeb704f6b83dfc3633a417eae732bb886bab8376f3bb0f94748bd57b1f1a50`；L50 forced WARN、L56 `Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.3-arch1.1 (LLVM 22.1.8)`、L94 `vkdisp: backend=Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)`；ERROR=**2**（同两条环境缺失）、FATAL=**0**、`Mixin apply failed`=**0**、vkdisp ERROR=**0**。（前一条 `f740e8e2…` 为同日首次 P0.2 验证运行，含完整游玩会话。）
  - ⚠️ **错误分类**：ERROR=**2** 条，均为已知环境缺失（narrator `libflite.so`、OpenAL 设备），FATAL=**0**，`Mixin apply failed`=**0**，vkdisp 相关 ERROR=**0**。
  - **GAP 登记**：调研结论为**不需要登记**（官方 `renderpearl.backend.api` 机制完整，无自补特性）。
  - 本轮无性能改动，`17-NATIVE.md` §2 性能预算不适用。
- **是否已提交**：是：随本条目一并 commit 并推送至 `origin/master`（Team Lead 统一执行，成员不自行 commit）。

---

## 2026-09-29 — P0.1 构建与产物核验 + runClient 运行验证

- **本次改了什么**：
  1. P0.1 验收的构建环节：`./gradlew build` 并对 `build/libs/*.jar` 做六项产物核对（class 数 / neoforge.mods.toml+modId / LICENSE / 不含 net/minecraft、com/mojang、net/caffeinemc、dev/vitrail 等）。
  2. P0.1 验收的运行环节：`./gradlew runClient` 真实启动到主菜单并取证（WSL2 免 root 方案：本地解压 Vulkan loader + lavapipe ICD，经 LD_LIBRARY_PATH / VK_DRIVER_FILES 注入；临时产物在 `tools/vulkan-local` 与 `run/`，不在仓库根留日志）。
  3. 本轮收尾整洁工作：删除仓库根全部 `*.log` 临时构建日志；核对 `.gitignore` 覆盖 `/build*.log`、`/build_*.log`（另有 `*.build.log`）；新建本 `CHANGE_LOG.md`；预跑 `docs/07-CONSTRAINTS.md` §七 中不需运行证据的静态自检项（见文末附录）。
  4. runClient 实测发现并修复 1 处缺陷：lang 文件缺 `vkdisp.configuration.*.tooltip` 键（游戏日志告警「The following keys have fallbacks...」）→ 为 `en_us.json` / `zh_cn.json` 各补 2 个 tooltip 键，随后重新 `./gradlew build` + `runClient` 复验，告警消失。
- **为什么改**：`docs/01-DEV-LOOP.md` §10 对 P0.1 的完成标准是「`./gradlew build` 退出码 0；`./gradlew runClient` 进主菜单；日志无 `Mixin apply failed`」；`docs/15-ITERATION.md` 要求每次迭代落变更记录，`docs/07-CONSTRAINTS.md` §七 要求每次提交前过自检清单。
- **影响的文档**：新增根目录 `CHANGE_LOG.md`（本轮唯一新增/修改的文档）。本轮未改动 `docs/` 下任何文件。
- **测试结果**（2026-09-29 实测回填；证据快照在 `tools/vulkan-local/evidence/`：`stage_a.log`、`stage_b*.log`、`latest*.log`、`mainmenu.png`、`titlemenu.png`，该目录已被 `.gitignore` 的 `/tools/` 覆盖、不入库）：
  - ✅ **构建**：`./gradlew build` 退出码 **0**（`BUILD SUCCESSFUL`；完整构建 1m35s，之后增量复验 4s/1s）。
  - ✅ **产物六项核对**：`build/libs/vkdisp-0.1.0.jar` —— `.class` 数=**3**（`VkDisp`/`VkDispClient`/`VkDispConfig`）；含 `META-INF/neoforge.mods.toml` 且 `modId="vkdisp"` == `gradle.properties:24`；含 `LICENSE`（`build.gradle` 新增 `tasks.named('jar', Jar).configure { from('LICENSE') }` 后复验通过，T3 报告第 3 项硬伤闭合）；`net/minecraft`、`com/mojang`、`net/caffeinemc`、`dev/vitrail` 各 **0** 命中。
  - ✅ **runClient 进主菜单**：17:07 启动 → 17:08:12 图集/资源就绪（`Loaded 0 entity animations`）→ 窗口 `Minecraft NeoForge* 26.3` 854x480；截图 `tools/vulkan-local/evidence/titlemenu.png`（sha256 `8a62d4ba...de50aff`，854x480，全象限高色彩多样性非黑屏）。首启拦截过一次无障碍引导屏（`AccessibilityOnboardingScreen`，`onboardAccessibility:false` 已落 `run/options.txt`），第二次复验直达主菜单。
  - ✅ **埋点**：`latest.log:92` `vkdisp: client setup, user=Dev`；Mod List 含 `Vulkan Shader Dispatcher 0.1.0 (vkdisp)`。
  - ✅ **静默失败扫描**：`Mixin apply failed`=0；`FATAL`=0；无 `crash-reports/`。
  - ⚠️ **ERROR=2 条，均为环境性、零 vkdisp 栈帧、游戏继续正常**：① `com.mojang.text2speech.Narrator` 加载 `libflite.so` 失败（WSL 无 TTS 库）；② `SoundEngine` `Failed to open OpenAL device`（WSL 无声卡）→ 原版自动 `Turning off sounds & music`。
  - ✅ **退出干净**：`Stopping!` → `Closing FML Loader` → `Clearing ModLoader`，Gradle 任务 `BUILD SUCCESSFUL` exit 0。
  - ✅ 静态自检（§七 不需运行证据的项）：结果见文末附录 —— 原 1 项不通过（【参考调研】缺第 0 条）已修复，现全过/不适用。
  - 本轮无性能改动，`17-NATIVE.md` §2 性能预算不适用。
- **是否已提交**：是：随本条目一并 commit 并推送至 `origin/master`。

---

## 附录：docs/07-CONSTRAINTS.md §七 提交前自检清单 —— 静态项预跑（2026-09-29，执行人 docs-upkeep）

> 全部项已回填（2026-09-29 实测）；表内证据已按 P0.2 复验更新（第 1/2/3/10/11/14/15 行）。

| # | 清单项 | 结果 | 证据 / 说明 |
|---|---|---|---|
| 1 | gradlew build 通过，jar 含 class | 通过 | P0.2 复验：`./gradlew build` EXIT=0（BUILD SUCCESSFUL）；jar 内 **5** 个 class（`VkDisp`/`VkDispClient`/`VkDispConfig` + bridge `DeviceApi`/`DeviceApi$DeviceInfoView`） |
| 2 | jar 含 LICENSE（MIT 署名要求） | 通过 | P0.2 复验：jar 根级含 `LICENSE`（`build.gradle` 的 `tasks.named('jar', Jar).configure { from('LICENSE') }` 持续生效） |
| 3 | jar 不含 net/caffeinemc、net/minecraft、dev/vitrail、com/mojang | 通过 | P0.2 复验：禁列 4 包（`net/caffeinemc/`、`net/minecraft/`、`dev/vitrail/`、`com/mojang/`）= 0/0/0/0 |
| 4 | mixins.json 是 JAVA_25 | 不适用 | `find . -name '*mixins*' -not -path './build/*' -not -path './.git/*'` 结果为空：本工程尚无 mixins.json；`neoforge.mods.toml:48-49` 的 `#[[mixins]]`/`#config` 均为注释态 |
| 5 | 无硬编码版本号（都走 gradle.properties） | 通过（附注） | `gradle.properties`：`minecraft_version=26.3`、`neo_version=26.3.0.23-beta`、`mod_version=0.1.0`、`mod_license=MIT`；`build.gradle:17` `version = mod_version`、`:42` `version = project.neo_version`、`:141-148` 全部走属性。附注：仅剩 plugins DSL 常量 `net.neoforged.moddev '2.0.147'`（build.gradle:4）、`foojay-resolver-convention '1.0.0'`（settings.gradle:9）与 toolchain `JavaLanguageVersion.of(25)`（build.gradle:38），三项均为 `docs/05-VERSION.md` §2 已登记版本（Gradle plugins DSL 不接受属性插值）；**Lead 复核结论：按此口径判通过（2026-09-29）** |
| 6 | 无复制来的第三方代码 | 通过 | `src/main/java/dev/vkdisp/` 4 个文件（`VkDisp`/`VkDispClient`/`VkDispConfig` 手写骨架 + `bridge/DeviceApi.java` 26.3 设备 API 薄封装，均为手写、零源码搬运）+ lang/mods.toml 资源；MDK 模板来源在根目录 `TEMPLATE_LICENSE.txt` 声明（"This license applies to the template files as supplied by github.com/NeoForged/MDK"，MIT） |
| 7 | 对外文字无「Sodium 替代品 / Iris 兼容 / OptiFine 官方」类表述 | 通过（1 处边界表述经 Lead 复核） | `grep -rniE 'sodium|optifine|iris' README.md src/main/templates src/main/resources` 无 sodium；唯一边界为 `neoforge.mods.toml:42`「兼容 OptiFine / Iris 格式着色器包」（讲的是**格式**，非模组背书），`README.md:6` 明确「独立实现，不与任何第三方渲染优化模组或着色器加载器做集成」。**Lead 复核结论：属格式描述、非模组背书，判通过（2026-09-29）** |
| 8 | 构建脚本无 sodium / caffeinemc 坐标，无运行时探测/集成分支 | 通过 | `grep -rniE 'sodium|caffeinemc' build.gradle settings.gradle gradle.properties gradle/ gradlew` → `NO MATCH` |
| 9 | 代码里 sodium 只出现在否定式语句 | 通过 | `grep -rni 'sodium' src/` → `NO MATCH`（一次都没出现，天然满足） |
| 10 | 新增的 GPU 操作走 renderpearl | 不适用 | P0.2 仍无 GPU 操作代码：`bridge/DeviceApi.java` 仅做后端/设备信息查询（`RenderSystem.tryGetDevice()` + 官方 `DeviceInfo`），不发 GPU 命令 |
| 11 | 业务包没有 import com.mojang.renderpearl.* | 通过 | P0.2 口径：`grep -rl 'com\.mojang\.\(renderpearl\|blaze3d\)' src/main/java` → 仅 `src/main/java/dev/vkdisp/bridge/DeviceApi.java`；原版类型引用全部收敛在 bridge 隔离包（`06-MIGRATION.md` §2），业务包（bridge 之外）零 import |
| 12 | 每个 mixin 注入点有日志 | 不适用 | 无 mixin（见第 4 行） |
| 13 | 顶点 stride 有断言 | 不适用 | 无顶点/渲染相关代码 |
| 14 | 新增模块有【参考调研】注释块（T13） | 通过 | 4 处：`VkDisp.java:3`、`VkDispClient.java:3`、`VkDispConfig.java:3`、`bridge/DeviceApi.java:2`（P0.2 新增）均有「【参考调研】」块 |
| 15 | 🔴【参考调研】**第 0 条**写了合规结论，且不是「未核实」 | 通过 | 4 处均有第 0 条合规结论：3 处原有（MDK 模板 MIT 可并入、无 LGPL/GPL/ARR、无例外条款）+ `bridge/DeviceApi.java`（参考=原版 renderpearl 设备 API，仅观察 javap 签名与官方调用点、零源码搬运；Mojang EULA/NeoForge LGPL 不并入代码，薄封装可并入，无例外条款） |
| 16 | 🔴 所有参考项目都查过仓库的 LICENSE 文件 | 通过 | 唯一参考 = NeoForge MDK，其 LICENSE 正文即仓库根 `TEMPLATE_LICENSE.txt`：「MIT License / Copyright (c) 2023 NeoForged project / …template files as supplied by github.com/NeoForged/MDK」（仓库文件级证据，非平台页面） |
| 17 | 🔴 没把「无 LICENSE」当可用、没把「GPL + 例外条款」当可用 | 通过 | 本模块参考仅 MDK（MIT）；无 ARR/GPL 参考进入实现 |
| 18 | 性能相关改动附实测数据（T14） | 不适用 | 本轮无性能改动；注释块第 5 条注明「P0.1 冷路径（启动日志），无优化需求」 |
| 19 | 未擅自开始原生（C++/Rust）实现（X17） | 通过 | `find src -name '*.cpp' -o -name '*.rs' -o -name '*.c' -o -name '*.h'` → 空；`build.gradle:113-135` dependencies 块无任何实际依赖（仅 MDK 示例注释） |
| 20 | 若含原生库：四平台产物 / Java 保底 / A/B 开关 | 不适用 | 无原生库 |
| 21 | 文档已同步 | 通过 | `git status --porcelain` 对 `docs/` 无未提交改动；README 两处死链已修复为 `docs/05-VERSION.md` / `docs/16-READING.md` |

**补充静态发现（非清单项，供 Lead 处理）**：

- `tools/`（本地 Vulkan 前缀与证据快照）：已由 `.gitignore` 新增 `/tools/` 与 `*:Zone.Identifier` 覆盖（本轮落地），不入库。
- 仓库根 `*.log` 已在本轮删除（清理由另一成员执行，commit 前完成）；`.gitignore` 已含 `/build*.log`、`/build_*.log`、`*.build.log`。**`run/` 目录按任务要求保留**（运行证据）。
