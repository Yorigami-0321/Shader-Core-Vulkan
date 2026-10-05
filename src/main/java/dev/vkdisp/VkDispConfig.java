package dev.vkdisp;
/**
 * 【参考调研】P0.1 骨架 / 官方 MDK 骨架
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    NeoForge MDK 26.3 模板（ModDevGradle）许可证 = MIT —— 证据：仓库根 TEMPLATE_LICENSE.txt（NeoForged，MIT）+ 官方仓库 LICENSE 文件；
 *    → 能否并入本项目（MIT）：可以（MIT 同族可并入，保留署名，已随分发）
 *    → 例外条款：无；不含任何 LGPL / GPL / ARR 内容
 * 1. 官方/主实现：NeoForge MDK 26.3 (ModDevGradle 2.0.147, commit eec248c) — 参考了：@Mod 注册方式、IEventBus 注入、配置注册。
 * 2. 备选：无（本阶段只要骨架）
 * 3. 我们的差异点：仅做 P0.1 骨架，不写渲染管线；后续按 01-DEV-LOOP.md 顺序推进。
 * 4. 许可证核对：MIT（本项目）；MDK 模板 MIT；不并入 LGPL/GPL 代码。
 * 5. 性能基线：P0.1 冷路径（启动日志），无优化需求。
 */

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组自身配置。用户着色器包自己声明的选项不放这里（那是运行时动态的，见 {@code config/PackOptions}）。
 *
 * <p>Phase 0 只放最小可用的开关；具体条目随功能推进再加。
 */
public final class VkDispConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 总开关：关闭后本模组完全不介入渲染，用于快速二分定位问题。 */
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("总开关。关闭后本模组不介入任何渲染，用于快速二分定位问题。")
            .define("enabled", true);

    /**
     * 诊断日志：打开后在**各 pass 的周期点**追加计数类诊断行，便于排查静默失败。
     *
     * <p>🔖 **QD-02（2026-10-04 闭环）**：本开关此前**只有定义与热重载快照、零消费点**
     * ⇒ 开关它没有任何可观察效果，**比没有更糟**（误导用户以为自己在控制日志量）。
     * 现补三处真实消费点，全部**节流**（每 300 / 300 / 120 帧各一行）：
     * <ul>
     *   <li>{@code OfUniformManager} —— 本帧写进 uniform 块的键数与前几个键名（排查 uniform 缺失）；</li>
     *   <li>{@code MrtTerrainPass} —— 多附件 pass 的**附件数 + 深度格式 + 挂的是原版还是包的片元**；</li>
     *   <li>{@code FullscreenPassHook} —— 原本无条件输出的 uniform 传参周期行，改为受控。</li>
     * </ul>
     * ⚠️ 全部**无条件开启**（默认 true），所以关掉开关会真的少掉这些行 —— 那才是开关该有的效果。
     * ⚠️ 三处都做了节流：这三段代码都在**每帧**执行路径上，无节流的 INFO 会把热路径变成 I/O 瓶颈
     * （与 M-01 埋点「600 → 250000」是同一类教训）。
     */
    public static final ModConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("诊断日志。打开后在各 pass 的周期点追加计数诊断行（已节流）；关掉可观察地变安静。")
            .define("debugLog", true);

    /**
     * P2.4「开关能改变画面」的主线开关：着色器包 profile 预设名
     * （对应包 {@code shaders.properties} 里的 {@code profile.<名>} 条目）。
     *
     * <p>留空 = 使用包默认值。生效时机 = 虚拟资源包生成 composite 源时
     * （{@code dev.vkdisp.VkDispVirtualPack}，P4.2 起外部改值保存即自动生效 ——
     * FML 配置热加载触发 {@link net.neoforged.fml.event.config.ModConfigEvent.Reloading}
     * → {@link VkDispConfigHotReload} 调资源重载；GUI 保存同理）。
     * P4.3 选项 GUI 前的临时主线入口（18-PARALLEL §5 P2.4 ④）。
     */
    // javap 核实：本版 ModConfigSpec 无 StringValue 内部类，字符串条目 = 泛型 ConfigValue<String>。
    public static final ModConfigSpec.ConfigValue<String> PACK_PROFILE = BUILDER
            .comment("着色器包 profile 预设名（shaders.properties 的 profile.<名>）。留空 = 使用包默认值。")
            .define("packProfile", "");

    /**
     * P4.2 切包回归的主线开关：着色器包选择（{@code 08-TESTING.md} §6 四张截图法的切换驱动）。
     *
     * <p>三态语义（{@code PackCompositeSource.generate} 实现，单测覆盖）：
     * <ul>
     *   <li>{@code ""}（默认）—— 自动：按库存扫描顺序取第一个能编出 composite 的包
     *       （P2.4 既有行为，一行不改）；</li>
     *   <li>{@code "none"}（保留名）—— 强制内置 passthrough，不加载任何库存包
     *       （§6 第 3 步「切到 none」；显式 INFO 诊断，T11）；</li>
     *   <li>其它 —— 按包名<b>精确匹配</b>（包名 = 扫包日志 {@code pack[N] name=} 的值）；
     *       匹配失败 / 该包无成功产出 → 显式 WARN + 内置兜底，<b>绝不静默落到别的包</b>
     *       （静默换包会让 §6 的 S2 判据失去意义）。</li>
     * </ul>
     *
     * <p>生效时机与 {@link #PACK_PROFILE} 相同（虚拟资源包 openResources 生成源时），
     * P4.2 起外部改值保存 → FML 热加载 → 资源重载 → 自动切包（无需重启 / 手动 F3+T）。
     */
    public static final ModConfigSpec.ConfigValue<String> SHADER_PACK = BUILDER
            .comment("着色器包选择。\"\" = 按库存扫描顺序自动选择；\"none\" = 强制内置 passthrough"
                    + "（不加载任何包）；其它 = 按包名精确匹配（包名见扫包日志 pack[N] name=）。"
                    + "改值保存后自动生效（配置热加载 → 资源重载）。")
            .define("shaderPack", "");

    /**
     * P4.3 选项 GUI 的驱动通道：改值保存 → FML 热加载边沿 → 解析执行一次屏幕动作
     * （语法 = {@code dev.vkdisp.config.ScreenDriveCommand}，解析可单测；动作执行在
     * {@code dev.vkdisp.screen.PackOptionsDrive}）。
     *
     * <p>与 {@link #SHADER_PACK} 的关键差异：<b>不触发资源重载</b> ——
     * {@link VkDispConfigHotReload} 只在前四个核心项（enabled/debugLog/packProfile/shaderPack）
     * 变化时重载；本条目变化只跑屏幕动作（打开 / 改值 / 翻页 / 完成）。"done" 动作自己会
     * 调一次资源重载（改值要落进着色器）。
     *
     * <p>驱动值是<b>边沿语义</b>：与上一次快照不同才执行；执行完若不回写空串，下一个动作
     * 直接写新值即可（如 {@code open} → {@code set:SHARPEN=4} → {@code done} 三次保存）。
     * 空串 = 中性（把值清回 {@code ""} 不执行任何动作）。
     */
    public static final ModConfigSpec.ConfigValue<String> PACK_OPTIONS_SCREEN = BUILDER
            .comment("选项 GUI 驱动（P4.3，边沿执行一次）：\"\" = 无动作；\"open\" = 打开选项界面；"
                    + "\"set:选项名=值\" = 修改当前界面里的选项；\"page:页号\" = 翻页；"
                    + "\"done\" = 保存并关闭（落盘 + 资源重载）。改值保存即触发对应动作，"
                    + "且不单独触发资源重载（done 除外）。")
            .define("packOptionsScreen", "");

    /**
     * 🔴 H 线 M-01 开关：把**派生地形管线**接到地形 draw 上（GAP-003/GAP-004 的通道）。
     *
     * <p><b>每个注入点一个键</b>是 07-CONSTRAINTS M1 编码约束 ⑤ 的硬要求（出问题时能立刻
     * 二分定位是哪一个注入点）。本键只管 {@code ChunkSectionLayer#pipeline} 这一个注入点；
     * 绑 uniform 的那一个是 {@link #MIXIN_BIND_TERRAIN_PARAMS}。
     *
     * <p>关掉后本注入点立即回到原版行为（画面与纯原版逐像素一致），
     * **不需要重启**：配置热加载事件刷新后下一帧生效。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_WIRE_TERRAIN = BUILDER
            .comment("H 线 M-01：地形 draw 使用 vkdisp 派生地形管线（GAP-003/004 的通道）。"
                    + "关闭 = 该注入点完全不生效，画面回到原版直连（默认开）。")
            .define("mixin.wireTerrain", true);

    /**
     * 🔴 H 线 M-01b 开关：把派生管线新增的自定义 uniform 块绑到地形 RenderPass（GAP-004）。
     *
     * <p>与 {@link #MIXIN_WIRE_TERRAIN} 分开的原因：两者是**不同的注入点**，必须能各自单独关闭。
     * 只关本键而留着 M-01 会让派生管线的块无人绑定 → 驱动层按布局校验时抛
     * {@code Missing uniform}（可见失败，不是静默）；只关 M-01 而留着本键则是无害的多余绑定。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_BIND_TERRAIN_PARAMS = BUILDER
            .comment("H 线 M-01b：把 VkDispTerrainParams 块绑到地形 draw 的 render pass（GAP-004）。"
                    + "仅在 mixin.wireTerrain 生效时需要（默认开）。")
            .define("mixin.bindTerrainParams", true);

    /**
     * 🔴 GAP-003 多附件（MRT）能力验证开关 —— **默认关闭**。
     *
     * <p>打开后每帧会在 vkdisp 自己的 pass 里写 N 个 colortex 附件，并把选定槽显示到主目标
     * （画面被该槽内容覆盖）。这是**诊断视图**、不是用户功能，因此默认关：常规帧零开销
     * （支柱③ B1 ≤ +2%）。
     *
     * <p>⚠️ 本项<b>不等于</b> GAP-003 已完成：它验证的是「多附件原语在
     * 后端 + 驱动 + 我方管线构造上可用」，地形接入仍需 M-04（拿地形 pass 的所有权），
     * 见 {@code docs/04-SPEC.md} §5.0 的 M-04 行。
     */
    public static final ModConfigSpec.BooleanValue MRT_ENABLED = BUILDER
            .comment("GAP-003 多附件能力验证（诊断视图，默认关）。打开后画面被 colortex<viewSlot> 覆盖。")
            .define("mrt.enabled", false);

    /**
     * MRT 调试视图显示哪一个槽（0=colortex0/albedo、1=colortex1/normal+lightmap、2=colortex2/material）。
     *
     * <p>越界时 {@code MrtProbe} **显式抛异常**而不是静默夹取 —— 静默夹取会让
     * 「我配了 5 号槽」看起来生效、实际看的是 2 号槽（07-CONSTRAINTS X9 不猜）。
     */
    public static final ModConfigSpec.ConfigValue<Integer> MRT_VIEW_SLOT = BUILDER
            .comment("MRT 调试视图显示的槽位（0..2）。越界会显式报错而非静默夹取。")
            .defineInRange("mrt.viewSlot", 0, 0, 7);

    /**
     * 🔴 H 线 M-05 开关：**只读捕获**地形 draw 数据（默认开）。
     *
     * <p>捕获本身**不改变任何渲染行为** —— 关闭它，地形照原版渲染。它的作用是让
     * GAP-003 方案 A（我方自己的多附件 pass 画地形）能拿到 {@code ChunkSectionsToRender}：
     * 官方 {@code FrameGraphSetupEvent} 触发时该对象**尚未创建**（源码级核实：事件在
     * {@code LevelRenderer#render} 第 249 行，创建在第 271-275 行），所以只能在创建点捕获引用。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_CAPTURE_TERRAIN_DRAWS = BUILDER
            .comment("H 线 M-05：只读捕获地形 draw 数据引用（GAP-003 方案 A 的前置）。"
                    + "不改变渲染行为；关闭后我方多附件地形 pass 静默不开。")
            .define("mixin.captureTerrainDraws", true);

    /**
     * 🔴 GAP-003 方案 A 的地形多附件诊断开关 —— **默认关闭**。
     *
     * <p>打开后我方在帧图里插一个 pass，把地形（OPAQUE 组）画进 {@code N} 个 colortex 附件。
     * ⚠️ <b>注意代价</b>：写的是**自己的** colortex，主目标仍由原版绘制
     * ⇒ <b>地形被画两遍</b>，且本轮**不产出任何画面改进**。
     * 它的用途只有一个：验证「地形能进多附件 pass」。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_ENABLED = BUILDER
            .comment("GAP-003 方案 A 诊断：地形画进多附件 pass（默认关）。开启后地形被画两遍，"
                    + "主目标不受影响，屏幕画面不变。")
            .define("mrt.terrain", false);

    /**
     * 🔴 诊断 A/B：把地形 MRT pass 从「帧图内」挪到「帧图执行完之后」。
     *
     * <p>用途：帧图 pass 的顺序由资源依赖解析，我方 pass 不声明依赖 ⇒ 可能早于原版地形
     * 数据上传执行。本开关用来一刀切开「顺序/上传时序问题」与「绘制本身的问题」。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_AFTER_LEVEL = BUILDER
            .comment("诊断：地形 MRT 绘制改在帧图执行完之后执行（默认关）。")
            .define("mrt.terrainAfterLevel", false);

    /** 诊断：地形 MRT 附件 0 改写**主目标**（配 terrainAfterLevel 用，屏幕即证据）。 */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_TO_MAIN = BUILDER
            .comment("诊断：地形 MRT 附件 0 改用主目标颜色视图（默认关）。会清掉主目标画面。")
            .define("mrt.terrainToMain", false);

    /**
     * 地形 MRT pass 与其管线**共同**使用的附件数（1..8，默认 3）。
     *
     * <p>🔖 两侧必须一致：Vulkan 动态渲染要求「管线颜色附件数 == render pass 附件数」，
     * 不一致是 validation error；而本机**没有 validation layer** ⇒ 静默失效。
     * 置 1 可得到与原版同形的管线，用来把「附件数不匹配」与「别的原因」区分开。
     */
    public static final ModConfigSpec.IntValue MRT_ATTACHMENTS = BUILDER
            .comment("地形 MRT 附件数（管线与 pass 共用；本机无 validation layer，不匹配会静默失效）")
            .defineInRange("mrt.attachments", 3, 1, 8);

    /**
     * 🔴 GAP-003：派生 MRT 地形管线**改用包自己的 gbuffers_terrain 片元** —— 默认关闭。
     *
     * <p><b>为什么要单独一个键</b>（M1 编码约束 ⑤「逐个开启 + 逐个关闭」）：
     * 「地形画进多附件 pass」（mrt.terrain）与「片元来自包而不是原版」是两件独立的事 ——
     * 前者已验通（h04/h05）；后者依赖包地形片元的契约解析、绑定组条目与顶点适配层，
     * 且其中三条 varying 目前只能按常量供值（GAP-007）⇒ 必须能<b>一键退回</b>原版 core/terrain。
     *
     * <p>开启后若所选包<b>没有</b>可用的 gbuffers_terrain 片元，会显式 WARN 并保持原版
     * （不静默换片元、不崩）。
     */
    public static final ModConfigSpec.BooleanValue MRT_PACK_TERRAIN_SHADER = BUILDER
            .comment("GAP-003：派生 MRT 地形管线使用包自己的 gbuffers_terrain 片元"
                    + "（需配合 mrt.terrain=true；默认关 = 沿用原版 core/terrain）。")
            .define("mrt.packTerrainShader", false);

    /**
     * GAP-003/A：包地形片元的 {@code shadowtex0}/{@code shadowtex1}/{@code shadowcolor0}
     * 绑到**专用 1×1 桩纹理**，而不是本 MRT pass 自己的深度 / colortex0 附件。
     *
     * <p>🔴 <b>为什么默认开</b>：本 pass 的深度附件（清屏 0.0 + 地形写深度）与 colortex0
     * 都是<b>读写 render pass 附件</b>；Vulkan 里「同一 image 既作读写附件又作采样器」是
     * <b>未定义行为</b> —— 驱动可以丢 draw / 给垃圾 / 无事发生，
     * <b>且不报 validation error</b>（本机没装 validation layer）。
     * 🔶 实测症状与之吻合：闪烁的触发条件被 2×2 对照收敛到<b>只有包地形片元</b>
     * （原版 {@code core/terrain} 不声明这些 sampler ⇒ 不触发）。
     *
     * <p>🔖 <b>代价（如实登记）</b>：桩纹理里没有真阴影贴图 ⇒ 阴影项**不承诺**
     * （与原实现的语义承诺一致）。桩深度清到 0.0 = 本引擎反向 Z 的**远平面** ⇒ 阴影取「无遮挡」。
     *
     * <p>🔬 <b>这是 A/B 开关，不是给用户调的手柄</b>：置 {@code false} 会<b>故意恢复</b>
     * 上述未定义行为，仅用于同二进制单变量对照取证（见 {@code evidence/h27-…}）。
     */
    public static final ModConfigSpec.BooleanValue MRT_SHADOW_STUBS = BUILDER
            .comment("GAP-003/A：shadowtex0/1 与 shadowcolor0 绑专用 1x1 桩纹理（不绑本 pass 的读写附件，"
                    + "那是 Vulkan 未定义行为）。false = 故意恢复旧行为，仅供 A/B 取证。")
            .define("mrt.shadowStubs", true);

    /**
     * 🔬 A/B 开关：故意恢复 GAP-010 修复（{@code 7206d6d}）之前的行为。
     *
     * <p>🔶 <b>为什么需要它</b>：GAP-011「闪烁」的归因链上有一处被混淆的对照 ——
     * {@code h21}/{@code h22} 观测到闪烁（99.89% 全黑相位），紧接着 {@code 7206d6d}
     * 修好了「适配层元被顺手清掉」，然后 {@code h24} 就再也观测不到闪烁，
     * 于是 {@code h24} 把功劳记给了「关掉 M-01 管线替换」。
     * 但那不是单变量：<b>代码在 h21 与 h24 之间变过</b>。
     * 🔖 本开关把「修复前/修复后」变成同一个二进制里的可切换变量，重新拿到真正的单变量对照。
     *
     * <p>⚠️ <b>默认关，且不是给用户调的手柄</b>：置 true 会重现 12 条 {@code resourceLoad/ERROR}。
     */
    public static final ModConfigSpec.BooleanValue MRT_GAP010_REGRESSION = BUILDER
            .comment("🔬 A/B 取证用：故意丢弃适配层 memo，复现 7206d6d 之前的状态（默认关）。")
            .define("mrt.gap010Regression", false);

    /** 诊断：地形 MRT pass 里先画一个已知可用的全屏三角形（判别「pass 不工作」vs「地形不出片元」）。 */

    /**
     * U0001f50d 诊断单变量实验：顶点适配层把 lmCoord 强制成 (1,1)（满光照）。
     *
     * <p>用途：判定「高级材质路径的画面全黑是否由 lmCoord（天光通道）导致」。
     * h10 实测：BSL 该路径里有 {@code sceneLighting *= skylightSqr}，而
     * {@code skylightSqr = lightmap.y²}、{@code lightmap = clamp(lmCoord, 0, 1)}；
     * 而原版把天光/块光两个通道共用一个打包过的 UV2。
     * <p>开启后**只改这一个 varying**，其余全部不动（单变量，可回滚）。默认关。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_FULL_LIGHT_PROBE = BUILDER
            .comment("诊断：顶点适配层把 lmCoord 强制成 (1,1) 满光照（默认关，单变量实验）。")
            .define("mrt.terrainFullLightProbe", false);

    /**
     * U0001f50d 诊断单变量实验（h12 根因探针）：把 dist 强制成 1000.0。
     * <p>parallaxFade = clamp((1000-64)/32, 0, 1) = 1.0 ⇒ 命中
     * {@code GetParallaxCoord} 的早退 {@code if (parallaxFade >= 1.0 || ...) return texCoord;} ⇒
     * <b>视差分支整体跳过</b>、newCoord = texCoord ⇒ albedo 按普通方式采样。
     * <p>只改 dist 一个 varying（单变量）。判定：若画面亮起来 ⇒
     * 压零项在<b>视差分支</b>（即 GAP-007 的 vTexCoord / vTexCoordAM 常量供值）；若仍黑 ⇒
     * 候选 3/4/5 全否定，须换切分方向。默认关。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_PARALLAX_SKIP_PROBE = BUILDER
            .comment("诊断：顶点适配层把 dist 强制成 1000.0 以跳过视差分支（默认关，单变量实验）。")
            .define("mrt.terrainParallaxSkipProbe", false);

    /**
     * `🔦 诊断单变量实验（h13 候选 6）：把片元里的 dcdx/dcdy 初值改成 vec2(0.0)。
     * <p>按 GLSL 规定，显式导数为 0 时 textureGrad 的 LOD 选取应与 texture() 的隐式导数相同，
     * 因此「画面是否变亮」是一个干净的二值判据。
     * <p>dcdx/dcdy 声明在**片元**里（第 293 行），顶点侧探针够不着 ⇒ 只能在转译阶段做。
     * <p>开启时日志自报「已把 N 处导数置零」；N=0 时额外告警「开关没生效」。默认关。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_DERIVATIVE_PROBE = BUILDER
            .comment("诊断：把片元 dcdx/dcdy 初值改成 vec2(0.0)（默认关，单变量实验）。")
            .define("mrt.terrainDerivativeProbe", false);

    /**
     * U0001f50d 诊断单变量实验（h15 的新首要嫌疑）：把 color 强制成 vec4(1.0)。
     * <p>依据：两条路径的 albedo 首行逐字相同 ——
     * {@code albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);}
     * 若 color.rgb 为 0 ⇒ albedo 恒为 0，**与 texture / textureGrad / 光照全无关**。
     * <p>h10～h15 六个候选全部排除后，color 是**唯一一个还没被实验触及的因子**。
     * <p>判据：画面变亮 ⇒ color.rgb 本来就是 0（**根因坐实**）；仍全黑 ⇒ 排除。默认关。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_COLOR_PROBE = BUILDER
            .comment("诊断：顶点适配层把 color 强制成 vec4(1.0)（默认关，单变量实验）。")
            .define("mrt.terrainColorProbe", false);

    /**
     * U0001f50d **GAP-011 二分诊断**：跳过 `gameRenderer.lighting().setupFor(LEVEL)`。
     * <p>该调用改的是**全局**光照状态，而我方 pass 跑在 AfterLevel（帧图执行完之后），
 * 结束后没有任何 pass 会重画主目标 ⇒ 残留状态直接作用到**下一帧**。
     * <p>判据：跳过它之后**天空是否恢复成蓝色**（见 h17 证据）。默认 false = 保持当前行为。
     */
    public static final ModConfigSpec.BooleanValue MRT_SKIP_LIGHTING_SETUP = BUILDER
            .comment("诊断（GAP-011 二分）：跳过 lighting().setupFor(LEVEL)（默认关 = 保持当前行为）。")
            .define("mrt.skipLightingSetup", false);
    /**
     * 🟡 **GAP-009 方案 A：能力门控** —— 默认**关闭**。
     *
     * <p><b>它做什么</b>：着色器包的某些特性依赖本引擎**没有**的素材/能力
     * （典型：LabPBR 逐方块材质贴图集 + 材质 UV 空间）。强行启用会让画面坏掉 ——
     * BSL 上实测是地形 albedo 被压成<b>恰好 0</b>（纯黑剪影，主目标地形区 luma {@code 0.0000}）。
     * 打开后，vkdisp 会把「<b>包自己声明</b>依赖该能力」的开关<b>只在内存里</b>关掉，
     * 并给出 WARN 说明关了哪些、依据是哪份实测。
     *
     * <p>🔖 <b>为什么默认关</b>：门控会改变用户可见画面（关掉包特性）。
     * 按支柱①兼容 / ②稳定的口径，任何「改变包语义」的动作都该<b>由用户显式开启</b>，
     * 否则用户会「莫名其妙少了特性」—— 那正是最该避免的失败形态。
     *
     * <p>🔖 <b>为什么不硬编码选项名</b>（这是本键存在的意义）：
     * 门控判据是「包<b>自己</b>声明的依赖」（BSL 用选项显示名末尾的 {@code *}，
     * 共 19 条，见 {@code shaders/lang/en_US.lang}）+「我们<b>确实缺</b>这个能力」两者同时成立。
     * 实测 Complementary 同样有视差，但复用原版 atlas、<b>零外部依赖</b>
     * ⇒ 写成「视差一律关闭」会砍掉一个完全可用的包特性（违反 X27）。
     *
     * <p>🔖 <b>作用域</b>：只在「包地形片元被接到派生 MRT 地形管线」这条路径上生效
     * （GAP-009 的实测证据全部取自地形）。那条路没走时不干预 ——
     * 在没执行的地方砍特性同样违反 X27。
     *
     * <p>🔖 <b>不写你的 optionsv2.txt</b>：只改内存值。裁决依据 = Iris 自己从不因能力缺失
     * 改写用户配置（只写用户改过的值，且等于默认值的项被移除），本项目不采用无先例的设计。
     */
    public static final ModConfigSpec.BooleanValue CAPABILITY_GATE = BUILDER
            .comment("GAP-009 方案 A：按能力门控掉依赖缺失素材的包特性（默认关）。"
                    + "打开后 vkdisp 会把包自己声明依赖、而本引擎确实缺失的开关在内存里关掉"
                    + "（如 LabPBR 材质贴图集相关项）并打 WARN。不改写你的包配置文件。")
            .define("pack.capabilityGate", false);

    /**
     * 🔬 A/B 开关：**故意**用高对比逐槽诊断色（绿 / 蓝 / 品红）清地形 MRT pass 的各槽。
     *
     * <p>🔴 <b>默认关，且不建议打开</b>。背景（实测见 {@code evidence/h27b-…} §六）：
     * 我方 pass <b>只画地形</b>，天空那片区域永远不会被画进 gbuffer；
     * 而逐槽诊断色是<b>无条件</b>应用的 ⇒ 天空那片保持纯绿，
     * 包的 composite 又采 {@code colortex0} ⇒ <b>绿天空直接进最终画面</b>。
     *
     * <p><b>本开关存在的理由</b>：诊断色本身有正当用途 ——
     * {@code MrtPlan} 给槽 0 的指纹恰好是 {@code 0.0}（黑），
     * 于是「一个片元都没出」与「画了但很暗」在一张截图里<b>无法区分</b>。
     * 高对比色能一刀切开。⇒ 该能力保留，但<b>限定在取证时按需开启</b>，
     * 默认（以及调试视图之外的任何时候）一律零值清屏。
     *
     * <p>⚠️ 打开后若该帧进了用户画面，会看到<b>假色天空</b>（绿/蓝/品红）。
     * 开启即 WARN 自报，避免「不知情地」把它当成渲染故障去查。
     */
    public static final ModConfigSpec.BooleanValue MRT_SLOT_DIAGNOSTIC_CLEAR = BUILDER
            .comment("A/B 取证用：故意用高对比逐槽诊断色清屏（默认关）。"
                    + "诊断色不代表任何渲染语义，出现在用户画面上会呈现为假色天空，仅供取证。")
            .define("mrt.slotDiagnosticClear", false);

    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_FULLSCREEN_PROBE = BUILDER
            .comment("诊断：地形 MRT pass 内先画全屏三角形（默认关）。")
            .define("mrt.terrainFullscreenProbe", false);

    /**
     * 🔬 **单变量取证覆盖**：按名字强制包选项的值（默认空串 = 不覆盖）。
     *
     * <p>🔖 <b>它为什么必须存在</b>（h42 §4.2 登记的未做项）：能力门控在 BSL 上一次关掉
     * <b>9 个</b>选项，并把派生程序形状一起改掉（{@code outputs 8→1}、{@code samplers 7→5}、
     * {@code varyings 15→9}）⇒ 两臂之间<b>不是单变量</b>。要验证「视差到底相不相关」，
     * 就必须有一个「<b>只改一个指定名字</b>」的入口。
     *
     * <p>语法：{@code NAME=value}，多条用 {@code ;} 分隔，例如 {@code "PARALLAX=false"}。
     *
     * <p>🔖 <b>为什么不是「再给视差加一个专用开关」</b>：那等于把 {@code PARALLAX}
     * 硬编码进产品配置 —— Complementary 同样有视差却零外部依赖（X27：不许无谓砍包特性），
     * 而且硬编码之后下轮要试别的选项又得再加一个键。必须是按名通用的机制。
     *
     * <p>🔖 <b>只改内存</b>，一个字节都不写用户的包配置文件（同能力门控那条裁决）。
     */
    public static final ModConfigSpec.ConfigValue<String> OPTION_OVERRIDES = BUILDER
            .comment("单变量取证覆盖：强制包选项的值，语法 NAME=value，多条用 ';' 分隔"
                    + "（例：\"PARALLAX=false\"）。只在内存里生效，不改写你的包配置文件。"
                    + "🔬 这是取证开关：能力门控一次改一整个闭包，两臂之间不是单变量。")
            .define("pack.optionOverrides", "");

    /**
     * 🔖 **像素回读探针**：把主目标 / colortex 的像素统计成数字打进日志（默认关）。
     *
     * <p><b>它回答什么</b>（h42 §4.3 登记的未分辨因素）：「画面全黑」有两种<b>完全不同</b>的成因 ——
     * ① 包的地形片元输出全黑；② 地形 draw 根本没落到我们以为的那个目标上。
     * 两者截图都是黑的，<b>只看截图分不开</b>。本探针对<b>同一帧</b>的两个源各取一次数：
     * <pre>
     *   colortex 有内容 + 主目标全黑 ⇒ draw 没落到主目标（落点问题，不是着色问题）
     *   两者都全黑                   ⇒ 包片元输出就是黑（GAP-008 本体）
     * </pre>
     *
     * <p>🔖 <b>为什么不用 MCP 截图 + 外部 python 脚本</b>（h22～h42 一直在用的办法）：
     * ① 它需要<b>人</b>在运行之外再跑脚本，而这里要的是<b>同帧两个数字</b>；
     * ② 采样区写死在脚本里，窗口尺寸一变就与历史数字不可比；
     * ③ <b>数字不在日志里</b> ⇒ 跨会话的读者（AI）读不到量化判据，只能重新截图重算 ——
     * 这正是 {@code h42}「推翻自己结论」那类事故的温床。
     *
     * <p>🔖 <b>默认关</b>：回读要走一趟 GPU→CPU 拷贝并映射内存，不是可以在每帧做的事
     * （支柱③ B1 ≤ +2%）。开启后按 {@link #MRT_PIXEL_PROBE_EVERY} 帧节流。
     *
     * <p>⚠️ <b>统计口径不在配置里</b>：采样区比例固定为中心
     * {@code x∈[45%,65%] / y∈[15%,75%]}，逐字等于 {@code evidence/tools/flicker_ratio.py}
     * 里被 h22 校准出来的那一组 —— 换掉它就等于让本机数字与历史证据不可比。
     */
    public static final ModConfigSpec.BooleanValue MRT_PIXEL_PROBE = BUILDER
            .comment("像素回读探针：把主目标与 colortex 的像素统计（mean_luma / 非黑占比 / maxR /"
                    + " 是否逐像素全黑）打进日志，并区分「输出黑」与「没落到主目标」（默认关）。")
            .define("mrt.pixelProbe", false);

    /**
     * 像素回读探针的节流间隔（帧）。
     *
     * <p>🔖 <b>为什么默认 300 而不是 1</b>：回读是 GPU→CPU 拷贝 + 内存映射，
     * 每帧做会直接违反支柱③。且探针的价值是「有没有数字」，不是「每帧一个数字」——
     * h42 那类问题都是<b>稳定复现</b>的（黑屏不是闪烁），采样频率不参与结论。
     */
    public static final ModConfigSpec.IntValue MRT_PIXEL_PROBE_EVERY = BUILDER
            .comment("像素回读探针的间隔帧数（默认 300）。回读要走 GPU→CPU 拷贝，不能每帧做。")
            .defineInRange("mrt.pixelProbeEvery", 300, 1, 100000);

    /**
     * 🔬 像素回读探针**额外**把方块图集（{@code texture_0} 的真值）也测一次（默认关）。
     *
     * <p>🔖 <b>它回答什么</b>：包地形片元的 albedo 是
     * {@code texture(texture_0, texCoord) * vec4(color.rgb, 1.0)} 一类乘法，
     * 而 {@code h44} 已证明「只有 albedo 那一路 ≡ 0、其余输出正常」
     * ⇒ 乘法链的两侧就是候选：<b>采样结果</b>与<b>乘子</b>。
     * {@code h28} 只在<b>顶点侧</b>排除过 {@code color}，而图集本身的<b>运行期</b>数字
     * 从未被取过（{@code h13} 只做了 mip 链的静态核查）。
     *
     * <p>🔖 <b>为什么默认关</b>：图集是 mip 链纹理、尺寸远大于主目标，
     * 回读缓冲按整幅分配 ⇒ 每轮多一次整图 GPU→CPU 拷贝与一块同尺寸缓冲。
     * 它是「输入侧」的独立一条证据，取证时才需要 ⇒ 按需开启。
     *
     * <p>⚠️ 开它只增加一个<b>观测面</b>，不改变任何渲染行为。
     */
    public static final ModConfigSpec.BooleanValue MRT_PIXEL_PROBE_ATLAS = BUILDER
            .comment("像素回读探针额外测方块图集纹理本身（texture_0 真值，默认关；图集很大，回读更贵）。")
            .define("mrt.pixelProbeAtlas", false);

    /**
     * 🔬 **采样因子探针·左侧**：把乘法链左侧（采样结果）强制成
     * {@code vec4(1.0, 0.5, 0.25, 1.0)}（默认关，单变量）。
     *
     * <p>🔖 <b>它回答什么</b>（{@code h44} 已把 GAP-008 收窄到这一步）：
     * albedo 是 {@code texture(texture_0, texCoord) * vec4(color.rgb, 1.0)} 这类<b>乘积</b>，
     * 两侧都可能为 0。强制左侧为非零常量后：
     * <ul>
     *   <li>画面<b>变亮</b> ⇒ 原采样结果为 0 ⇒ 问题在<b>输入侧</b>（图集内容 / 采样坐标 / 采样器绑定）；</li>
     *   <li>画面<b>仍全黑</b> ⇒ 采样不是原因，问题在右侧乘子或更下游。</li>
     * </ul>
     *
     * <p>🔖 <b>与 {@link #MRT_TERRAIN_COLOR_PROBE} 的区别**（后者不因此被删）：
     * 那个改的是<b>顶点适配层供的 varying</b>，排除的是「供值错」；
     * 本项改的是<b>片元里采样调用的返回值</b>，排除不了前者也排除不了后者。
     * 🔴 <b>本项与 {@link #MRT_TERRAIN_SAMPLE_FACTOR_MULTIPLIER} 不可同时开</b> ——
     * 两侧同时换掉就等于什么都没分开（转译段会就此自报 WARN）。
     *
     * <p>⚠️ 默认关，且开启即自报命中数与命中行原文（X45：不自报就分不清
     * 「开关没生效」与「结论不成立」）。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_SAMPLE_FACTOR_SAMPLE = BUILDER
            .comment("诊断：把 albedo 乘法链左侧（纹理采样结果）强制成非零常量（默认关，单变量）。"
                    + "变亮 ⇒ 原采样为 0；仍黑 ⇒ 采样不是原因。不可与右侧探针同时开。")
            .define("mrt.terrainSampleFactorSample", false);

    /**
     * 🔬 **采样因子探针·右侧**：把乘法链右侧（乘子）强制成单位元 {@code vec4(1,1,1,1)}（默认关）。
     *
     * <p>🔖 保持乘积结构不变、只把乘子换成单位元 ⇒ 变亮即说明<b>原乘子为 0**。
     * 🔴 同样<b>不可与左侧探针同时开</b>（两侧都换掉就分不出是哪一侧）。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_SAMPLE_FACTOR_MULTIPLIER = BUILDER
            .comment("诊断：把 albedo 乘法链右侧（乘子）强制成 vec4(1,1,1,1)（默认关，单变量）。"
                    + "变亮 ⇒ 原乘子为 0。不可与左侧探针同时开。")
            .define("mrt.terrainSampleFactorMultiplier", false);

    /**
     * 🔬 **坐标数值探针**（h45 §七 候选① 的直接判据）：把命中行的整个右值换成
     * {@code vec4(<采样坐标>, 0.0, 1.0)} ⇒ colortex0 变成「texCoord 的可视化」。
     *
     * <p>🔖 <b>它回答什么</b>：h45 已把 albedo≡0 的成因钉在 {@code texture(texture_0, texCoord)}
     * 的返回值上，剩下的分叉是「坐标落错」vs「采样器/纹理侧坏」—— 只有 h45 里约 26%
     * 的图集透明填充这一种「坐标落错」形态，单看截图分不开。开本档后像素数字
     * 直接携带坐标值（×255 量化）：读数 ≈ 0 ⇒ 坐标链路坏；读数 = 图集合理值 ⇒ 排除坐标。
     *
     * <p>🔴 与左右两侧探针同开时坐标档优先（适配器会 WARN，那条线只剩坐标证据）。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_COORD_OUT = BUILDER
            .comment("诊断：把 albedo 行的右值换成 vec4(采样坐标,0,1)，让像素直接携带坐标数值"
                    + "（默认关，单变量）。读数≈0 ⇒ 坐标链路坏；图集合理值 ⇒ 排除坐标侧。"
                    + "⚠️ 判据洞见 terrainCoordOutFinalProbe 的注释 —— 精确判定请用输出直写档。")
            .define("mrt.terrainCoordOutProbe", false);

    /**
     * 🔬 **输出直写坐标档**（h46 修正判据洞）：把最终输出对 albedo 的裸赋值
     * （{@code gl_FragData[0] = albedo;} / {@code vkdispFragOut0 = albedo;}）的右值
     * **整体**换成 {@code vec4(<采样坐标>,0,1)} ⇒ 读数 = 纯坐标，跳过 GetLighting 等
     * 全部下游乘法。
     *
     * <p>🔖 <b>为什么需要它</b>：F 臂用 {@link #MRT_TERRAIN_COORD_OUT} 只换了**第一处**赋值，
     * 而 ADVANCED_MATERIALS 路径在其后还有光照/衰减乘法 ⇒ 「读数 0」区分不了
     * 「坐标为 0」与「坐标正常但被下游压零」。本档把这两件事一刀切开（单变量：只动输出行）。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_COORD_OUT_FINAL = BUILDER
            .comment("诊断：把最终输出行 (gl_FragData[0]/vkdispFragOut0 = albedo) 的右值整体换成 "
                    + "vec4(采样坐标,0,1)，跳过全部下游衰减 ⇒ 读数=纯坐标（默认关，单变量）。")
            .define("mrt.terrainCoordOutFinalProbe", false);

    /**
     * 🔬 **显式 LOD0 探针**（h45 §七 候选②）：把命中行的两参数 {@code texture(s, c)}
     * 换成 {@code textureLod(s, c, 0.0)}，其余一概不动。
     *
     * <p>🔖 强线索（h45 §七）：{@code PARALLAX=true} 时同一采样器走 {@code textureGrad} 却非零，
     * 而 {@code PARALLAX=false} 的裸 {@code texture()} 逐像素恰好 0 —— 两条采样路径只差
     * 「显式梯度 vs 隐式导数」。开本档：非零 ⇒ 隐式导数选了坏 mip（LOD 侧坐实）；
     * 仍零 ⇒ LOD 因素排除，只剩坐标/绑定。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_LOD_ZERO = BUILDER
            .comment("诊断：把 albedo 行的 texture(s,c) 换成 textureLod(s,c,0.0)（默认关，单变量）。"
                    + "非零 ⇒ 隐式导数选了坏 mip；仍零 ⇒ LOD 因素排除。")
            .define("mrt.terrainLodZeroProbe", false);

    /**
     * 🔴 **通用后处理链**（deferred* → composite* → final 全链，按名采 colortex）。
     *
     * <p><b>它补上的是什么</b>：旧三步链（deferred/composite/final 各一条）只喂包的
     * 三个同名程序，BSL 的 {@code composite1..7}（光柱/动感模糊/DOF/FXAA/TAA…）
     * 从未被执行；而 composite 的彩色输入绑的是**场景色**而不是包的 gbuffer。
     * 本链把整包后处理序列跑起来，采样按名接进 colortex 池
     * （「FrameApi 的 packColor 从 scene 改采 colortex」的机制化版本）。
     *
     * <p><b>生效前提</b>（缺一即回旧三步路径，回退有日志不自检）：
     * {@code mrt.terrain} + {@code mrt.packTerrainShader} 开启、地形包片元已接线、
     * 所选包产出 ≥1 个后处理程序、且**不在** {@code mrt.terrainToMain} 诊断档
     * （那一档的语义是「让我方地形画直接进屏幕」，与链的「链输出进屏幕」互斥）。
     *
     * <p>默认开：关掉它 = 主动退回单程序旧链（取证 A/B 用）。
     */
    public static final ModConfigSpec.BooleanValue MRT_POST_CHAIN = BUILDER
            .comment("通用后处理链：跑完整 deferred*/composite*/final 序列并按名接 colortex"
                    + "（默认开；关闭 = 退回旧的三步链）。前提见注释。")
            .define("mrt.postChain", true);

    /**
     * 🔴 **GAP-016 止血开关**（默认开）：包地形链的方块图集采样器钉 `maxLod = 0`。
     *
     * <p>依据（h45/h46 三臂交叉）：{@code texture()} 的隐式导数 LOD 对本链选了坏 mip ⇒
     * albedo ≡ 0；{@code textureLod(…,0.0)} / {@code textureGrad} 均非零；
     * {@code texCoord} 数值本身正常（F3 臂）。钉 mip0 = 止血，**不是修根**，
     * 代价与回退条件见 {@code 13-GAP-REGISTRY.md} GAP-016。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_ATLAS_LOD0 = BUILDER
            .comment("GAP-016 止血：包地形图集采样器 maxLod=0（默认开；关闭即回到实测恒 0 的"
                    + "隐式导数 LOD 路径，仅用于复现/修根对照）。")
            .define("mrt.terrainAtlasLod0", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VkDispConfig() {
    }
}
