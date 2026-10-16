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
     * 🔴 R2 / M-06 开关：**只读捕获**地形 draw 数据 + 地形雾切片（默认开，不改变渲染行为）。
     * 取法 = 原版第 **277** 行那次无条件的 {@code addMainPass}（两支在此汇合），旧 M-05 两点已删。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_CAPTURE_MAIN_PASS_ARGS = BUILDER
            .comment("R2 M-06：只读捕获 LevelRenderer#addMainPass 的 terrainFog 与地形 draw 数据"
                    + "（GAP-003 方案 A 的前置 + GAP-038 补雾的来源）。不改变渲染行为；"
                    + "关闭后我方多附件地形 pass 静默不开。")
            .define("mixin.captureMainPassArgs", true);

    /**
     * 🔴 R3 / M-07 开关：地形图集**复用原版那个采样器实例**（默认开）—— 各向异性与
     * {@code shouldResetChunkLayerSampler} 门控一起继承（{@code GAP-041}）。
     * ⚠️ 关档不是「画面不变」：退回公开 {@code samplerCache} 的 LINEAR 档 ⇒ 各向异性恒 1，
     * 且 {@code mrt.terrainAtlasLod0} 那条 maxLod=0 止血不再作用于地形图集（GAP-016 复现时本键是 A/B 位）。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_REUSE_CHUNK_SAMPLER = BUILDER
            .comment("R3 M-07：地形图集复用原版 LevelRenderer#chunkLayerSampler（各向异性跟用户设置、"
                    + "重建门控一并继承）。关闭 = 退回公开 samplerCache 的 LINEAR 档，"
                    + "各向异性恒 1（GAP-041 的坏档，默认开）。")
            .define("mixin.reuseChunkSampler", true);

    /**
     * 🔴 R1a / M-15 开关：多附件地形变体只在<b>身份对上</b>的 pass 里交出（默认开）。
     * 关档 = 我方 pass 里只用单附件版 ⇒ {@code setPipeline} 当场抛 ⇒ A/B 位，不是可用的默认。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_PASS_AWARE_PIPELINE = BUILDER
            .comment("R1a M-15：地形多附件变体由 renderPass 的对象身份决定要不要交出"
                    + "（取代 inMrtPass 静态标志 + finally 纪律，GAP-040）。关闭 = 我方 pass 里只用"
                    + "单附件版 ⇒ setPipeline 当场抛，属 A/B 档。")
            .define("mixin.passAwarePipeline", true);

    /**
     * 🔴 R5 / M-13 开关：显式声明「我方 gbuffer pass requires 原版 sky pass」（默认开）。
     * 帧图执行序按依赖解析、<b>插入序不是依赖</b> ⇒ 不连边时天空可被排到地形之后（h48g 实测
     * {@code c0@afterSky} 只剩 0.0611，正确顺序下同取点 72.85~84.50）。⚠️ 关档 = 不连边。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_PIN_PASS_ORDER = BUILDER
            .comment("R5 M-13：声明「我方 gbuffer pass requires 原版 sky pass」这条帧图边。"
                    + "关闭 = 不连边、顺序回到按依赖猜（默认开）。")
            .define("mixin.pinPassOrder", true);

    /**
     * 🔴 R3 / M-08 开关：地形 draw 条数自报走**访问器**（默认开）。关掉只少一行自报、不改渲染行为 ——
     * 它替代的是反射那一族（{@code GAP-042}：改名即哑、失败只 WARN）。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_TERRAIN_DRAW_PROBE = BUILDER
            .comment("R3 M-08：地形每层 draw 组条数用 @Accessor 读出并自报（替掉反射）。默认开。")
            .define("mixin.terrainDrawProbe", true);

    /** 🔴 R3 / M-09 开关：云 quadCount 自报走**访问器**（默认开）。同 M-08，关掉只少一行自报。 */
    public static final ModConfigSpec.BooleanValue MIXIN_CLOUD_PROBE = BUILDER
            .comment("R3 M-09：CloudRenderer#quadCount 用 @Accessor 读出并自报（替掉反射）。默认开。")
            .define("mixin.cloudProbe", true);

    /**
     * 🔴 R3 / M-10 开关：云的 modelview 由 {@code @Redirect} 交出**实参**（默认开）⇒ 重放不再
     * 压/还原全局栈（「漏还原 ⇒ 污染同帧后续原版 pass」这一族消失）。
     * ⚠️ 关档 = 交回原版那句：原版不受影响，我方重放读裸栈 ⇒ 放射长条复现（A/B 档，WARN 一次）。
     */
    public static final ModConfigSpec.BooleanValue MIXIN_CLOUD_MATRIX_REDIRECT = BUILDER
            .comment("R3 M-10：@Redirect 原版云取 modelview 的实参为我方相机矩阵，于是不再压/还原全局栈。"
                    + "关闭 = 原版路径不变、我方重放回到锚错位姿的坏档（默认开）。")
            .define("mixin.cloudMatrixRedirect", true);

    /**
     * 🔴 GAP-003 方案 A 的地形多附件 gbuffer 开关 —— <b>默认关</b>（与 {@link #MRT_PACK_TERRAIN_SHADER} 成对）。
     *
     * <p>打开后我方在帧图里插一个 pass，把地形（OPAQUE 组）画进 {@code N} 个 colortex 附件。
     *
     * <p>🔴 <b>单独开它 = 全黑，不是「画面不变」</b>（2026-10-12 真机量出来的，旧注释那句
     * 「地形被画两遍、主目标不受影响、屏幕画面不变」已被否证）：地形只进 colortex，
     * 而把 colortex 带回主目标的是后处理链 —— 链的生效条件里就含本键<b>与</b>
     * {@code mrt.packTerrainShader}（{@code FrameApi#isPostChainActive}）。
     * 半开档实测：{@code main} allZero=true、同帧 {@code c0} 有内容（nonBlack=100%）
     * = 探针口径的 <b>NOT_ON_MAIN</b>，且<b>零异常</b>（不是崩，是没人写主目标）。
     * ⇒ 本键与 {@code mrt.packTerrainShader} 是<b>一对</b>，要开一起开、要关一起关；
     * 单开本键时 {@code FullscreenPassHook} 打一次点名 WARN，不静默。
     *
     * <p>🟡 <b>翻默认差一步</b>（2026-10-12 真机 RTX 4060 / Vulkan 三档取证）：
     * 两条都关（= 今天的默认）时画面是「地形在、<b>天空整片黑</b>」，且包天空
     * {@code [GAP-027/sky]} 自报 <b>0 行</b> ⇒ 一百一十轮交付的天空不在这条路上；
     * 两条都开 + BSL 时同机位白天 = 蓝天 + 日晕 + 地形 AO、夜里 = 夜空渐变 + 月晕，
     * {@code [route] chain=true}、{@code fullscreen pass failed} 0 条 —— <b>好包档已合格</b>。
     * 🔴 <b>坏包档不合格</b>：换到 {@code ComplementaryReimagined_r5.9.3}（其
     * {@code world1/gbuffers_*} 全部编译失败）后，本 pass 在 {@code MrtTerrainPass#drawTerrain}
     * 的 {@code renderGroup} 处抛 {@code IllegalStateException: Missing uniform VkDispBuiltins
     * (should be UNIFORM_BUFFER)}，而它跑在<b>帧图 pass 体内</b>没人接 ⇒
     * {@code ReportedException: Render Frame} = <b>客户端直接死</b>
     * （旧默认档下同一个包只是「没地形没光影」，不崩）。
     * ⇒ 翻默认<b>暂缓</b>；阻塞项登记在 GAP-036 ④（含栈与两侧差异），解开后再翻。
     */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_ENABLED = BUILDER
            .comment("GAP-003 方案 A：地形画进多附件 gbuffer pass（默认关）。与 mrt.packTerrainShader 是**一对**："
                    + "只开本键 = 半开档，地形只进 colortex 而链不跑 ⇒ 屏幕预期全黑（实测 main allZero + c0 有内容），"
                    + "日志点名一次。两条一起开才是包路线（好包 BSL 已真机复判；坏包会崩帧，见 GAP-036 ④）。")
            .define("mrt.terrain", false);

    /**
     * 诊断：地形 MRT 附件 0 改写**主目标**。
     *
     * <p>⚠️ 「屏幕即证据」这句随 {@code mrt.terrainAfterLevel} 车道的退役一起失效（R5 / 2026-10-12）：
     * 现在地形只可能在帧图里画，而我方那个 pass <b>早于</b>原版 {@code "main"} pass ⇒ 主目标随后会被
     * 原版重画。本档剩下的用途是**回读侧**：{@code PixelProbeVerdict} 的 toMain 那一族标签
     * （GAP-008 的定位档 —— 「主黑 + 槽正常」在那一排里读作 albedo ≡ 0，而不是「没落到主目标」）。
     */
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
     * 🔴 GAP-003：派生 MRT 地形管线<b>用包自己的 gbuffers_terrain 片元</b> —— 默认<b>关</b>，
     * 与 {@link #MRT_TERRAIN_ENABLED} <b>成对</b>（只开那条 = 半开档、屏幕全黑；翻默认的共同阻塞项
     * = GAP-036 ④「坏包 + 两条都开 ⇒ renderGroup 抛 Missing uniform VkDispBuiltins 带走客户端」）。
     *
     * <p>保留成独立一键的理由是 M1 ⑤「逐个开启 + 逐个关闭」：「画进多附件 pass」与「片元来自包」
     * 是两件事，后者还要契约解析/绑定组/顶点适配层，且三条 varying 仍按常量供（GAP-007）
     * ⇒ 必须能一键退回原版 {@code core/terrain} 做对照。包里没有该程序时显式 WARN，不静默换片元。
     * <p>📎 逐臂真机读数与半开档实测：{@code docs/13} 的 GAP-003 / GAP-036。
     */
    public static final ModConfigSpec.BooleanValue MRT_PACK_TERRAIN_SHADER = BUILDER
            .comment("GAP-003：MRT 地形管线用包自己的 gbuffers_terrain 片元（默认关，与 mrt.terrain 成对）。"
                    + "关掉 = 沿用原版 core/terrain；🔴 只开 mrt.terrain 而关本键 = 半开档，屏幕预期全黑，"
                    + "要对照请两条一起关。包里没有 gbuffers_terrain 时显式 WARN（但坏包+两条都开仍会崩帧，GAP-036 ④）。")
            .define("mrt.packTerrainShader", false);

    /**
     * 🔴 <b>GAP-027：把包自己的 {@code gbuffers_water} 接进 MRT gbuffer pass</b> —— 默认<b>关闭</b>。
     *
     * <p>与 {@link #MRT_PACK_TERRAIN_SHADER} 同一条链（选包 → 门控 → 覆盖 → 编译 → DRAWBUFFERS 兑现），
     * 只换程序名；接上后 {@code TRANSLUCENT} 层用<b>水的</b>片元 + <b>水的</b>适配层 + <b>水的</b>绑定组
     * （实测 BSL 默认档 2 输出 / 8 sampler / 14 varying，而地形是 1 / 5 / 9 ⇒ 清单不可套用地形，X39），
     * 并在同一 pass 内 OPAQUE 之后再画一次 TRANSLUCENT 组（{@code MrtTerrainPass#drawWaterSegment}）。
     *
     * <p>默认关：它换包语义、又把附件数从「按地形定」改成「按两条程序取 max」
     * （{@code MrtPlan.freezePackPrograms}），不匹配就是 {@code setPipeline} 抛 ⇒ 崩客户端；按支柱①/②
     * 这类接线要用户显式开启。<b>关掉逐字节等于今天</b>；包里没这条程序时一行 {@code not wired because
     * absent} 都不静默（X9/X11/T11）。🔖 生成期读一次 ⇒ 必须进 {@code currentTerrainMemoKey} 与热重载
     * 核心快照（QD-08，{@code GenerationTimeSwitchInventoryTest} 兜）。📎 逐臂读数：{@code docs/13} GAP-027。
     */
    public static final ModConfigSpec.BooleanValue MRT_PACK_WATER_SHADER = BUILDER
            .comment("GAP-027：把包的 gbuffers_water 接进 MRT gbuffer pass（TRANSLUCENT 层改用"
                    + "水的片元/适配层/绑定组，并在同一 pass 里多画一次半透明组）。"
                    + "需配合 mrt.terrain=true；默认关 = 水沿用原画法、附件数只按地形契约定。"
                    + "包里没有 gbuffers_water 时会打一行 not wired because absent 并只影响水。")
            .define("mrt.packWater", false);

    /**
     * 🔴 GAP-007：包片元的 {@code normal}/{@code tangent}/{@code binormal} 由屏幕空间导数现算
     * （默认关 = 逐字等于今天的常量供值）。判据与闸门见 {@code glsl/translate/DerivativeTbn} 头部。
     */
    public static final ModConfigSpec.BooleanValue MRT_DERIVATIVE_TBN = BUILDER
            .comment("GAP-007：包片元的 normal/tangent/binormal 由屏幕空间导数现算（默认关 = 逐字等于今天的常量供值；"
                    + "只在该程序片元真声明这三条时生效，预处理器罩不住的形态整条不做）。理由见 DerivativeTbn/DerivativeTbnSwitch 头部。")
            .define("mrt.derivativeTbn", false);

    /**
     * 🔴 GAP-027 天空线第三刀：原版日月那两笔 draw 改用包的 {@code gbuffers_skytextured}（默认关）；
     * 原版仍驱动几何/姿态/baseVertex。逐字依据与否决项见 {@code bridge/SkyCelestialPack} 头部（QD-13）。
     */
    public static final ModConfigSpec.BooleanValue MRT_SKY_PACK_TEXTURED_SHADER = BUILDER
            .comment("GAP-027：日月两笔 draw 改用包的 gbuffers_skytextured（开=换 drawCelestialBody 的管线并补绑；关=逐字等于今天）")
            .define("mrt.skyPackTexturedShader", false);

    public static final ModConfigSpec.BooleanValue MIXIN_SKY_CELESTIAL_PACK = BUILDER
            .comment("M1 ⑤：注入点 skyCelestialPack 的一键关（SkyRenderer#drawCelestialBody 的管线换绑）。"
                    + "关掉 = 混入原样交回原版管线，日月回到原版着色器。")
            .define("mixin.skyCelestialPack", true);

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
     * <p>该调用改的是**全局**光照状态，而我方 pass 现在跑在帧图内（R5 起 AfterLevel 车道已删），
     * 残留状态会作用到同帧后续原版 pass 与下一帧。<p>判据：跳过它之后**天空是否恢复成蓝色**（h17 证据）。默认 false = 保持当前行为。
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
     * 🔴 <b>GAP-024：链装配期执行包自己写下的 {@code program.*.<名>.enabled}</b> —— 默认<b>开</b>。
     *
     * <p><b>它修的是什么</b>：{@code ShaderProperties} 早就把这些表达式收进了
     * {@code programSwitches}，{@code ShaderPackService.deriveSettings} 也早就把它落到
     * {@code Program#settings()} 的 {@code enabled} 键上 —— 但 {@code PackPostChain} 的装配
     * 循环<b>从不读它</b>。结果：包里明确关着的特性级（BSL 默认档的 MOTION_BLUR / DOF
     * 两级 composite）照样每帧执行 —— 画面在「按包声明跑」与「全都跑」之间没有区别，
     * 而这两臂的像素差就是被跳过的功能。这属于「数据在、没接线」那一族（QD-02 死开关的镜像形态：
     * 有消费方但没人读源）。
     *
     * <p>🔖 <b>为什么默认开（与 {@link #CAPABILITY_GATE} 的默认关相反，且这个相反是有意的）</b>：
     * 本开关做的事是「执行<b>包自己</b>写下的开关」。关掉它 = 让包明确关着的那一级继续跑
     * = <b>违反包的声明</b>（支柱①兼容的口径），所以默认按包意图生效。
     * {@code pack.capabilityGate} 关的却是「我方<b>替</b>包关掉它<b>没声明</b>要关的东西」，
     * 那种改变包语义的动作才需要用户显式开启。
     *
     * <p>🔖 <b>那它为什么还要存在</b>：A/B 取证。要证明「门控本身有没有副作用」，
     * 必须在同一二进制里把这一刀切出来（h45 单变量纪律），而不是靠改代码重编两版。
     *
     * <p>🔖 <b>保守处置</b>：表达式认不出来（语法不支持 / 名字不在选项表里）时
     * <b>保留</b>那一级并自报（{@code ProgramEnableGate} 的三值口径）；
     * 跳过只发生在表达式<b>确定为假</b>时。无论跳过几级，每次装配都有一行
     * {@code [GAP-024] post chain enable-gating:} 自报（含 gating=on/off），
     * 两臂的日志各自说明自己跑了哪个行为。
     */
    public static final ModConfigSpec.BooleanValue CHAIN_ENABLE_GATING = BUILDER
            .comment("GAP-024：后处理链装配时执行包自己写的 program.*.enabled 开关（默认开）。"
                    + "关闭 = 回到旧行为（关了特性的步也照跑），仅供 A/B 取证；"
                    + "表达式认不出时一律保留该级并在 [GAP-024] 自报行里点名。")
            .define("pack.chainEnableGating", true);

    /**
     * 🔴 GAP-030：后处理链各级<b>顶点</b>用包自己的 post 顶点程序（默认开）。
     * 「关」不是保守档 —— 它回到「世界向量按零值供」那档已定案根因（屏幕双向镜像虚影 + 长条云）。
     * 逐字教训与反射常量口径见 {@code pack/PackPostVertexSwitch}（配置键名 ≠ Java 字段名）。
     */
    public static final ModConfigSpec.BooleanValue PACK_POST_VERTEX_PROGRAM = BUILDER
            .comment("GAP-030：后处理链各级顶点跑包自己的 post 顶点程序 + 标准全屏顶点缓冲（默认开）。"
                    + "关闭 = 回到我方顶点适配层（世界向量按零值供）= 已定案的「镜像虚影/长条云」根因档，"
                    + "仅供 A/B 取证；单槽接口对不齐时不分开关都按整槽回落并打 ERROR。")
            .define("pack.postVertexProgram", true);

    /** 🔴 <b>GAP-054 的包侧二分总闸</b>（默认开 = 今天的行为）：关 ⇒ 包的资源照常而派生程序一条不注册。
     *  为什么需要这一刀、判据与处置形状见 {@code PackDerivedProgramGate} 与 {@code docs/13} 的 GAP-054 行。 */
    public static final ModConfigSpec.BooleanValue PACK_DERIVED_PROGRAMS = BUILDER
            .comment("GAP-054 包侧二分总闸（默认开 = 今天的行为）：关闭 ⇒ 包照常加载、包自带的贴图/模型资源"
                    + "照常生效，但三源回落内置 passthrough、逐程序 gbuffer 片元与顶点适配层清零、链 EMPTY。")
            .define("pack.derivedPrograms", true);

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
     * 🔬 回读**落地余量**（探针节拍数；实现见 {@code TargetReadback#readDelayTicks()}）。
     *
     * <p><b>为什么这条必须可调</b>：{@code VulkanCommandEncoder#copyTextureToBuffer} 的
     * 「完成」回调走 {@code queueForDestroy}（<b>销毁队列</b>，CPU 侧轮转，不是 fence）
     * ⇒ 「回调回来了」<b>不等于</b>「拷贝落进了缓冲」。核实到行（同一文件）：
     * 第 60 行 {@code new DestructionQueue<>(2, ...)} + 第 229 行每次 submit 轮一次；
     * 第 219-223 行每次 submit 只 {@code awaitSubmitCompletion(currentSubmitIndex - 2)}
     * ⇒ 第 N 个 submit 的 GPU 完成<b>最早</b>要到第 N+2 次 submit 才被等到。
     * 余量小于这个 2 时，取到的可能是<b>还没被 GPU 写过的缓冲</b>，而它的初始内容是零 ⇒
     * 日志里的「整帧全 0」与「画面真的是黑的」逐字同形。
     *
     * <p>🔴 这正好是 h48o 量到的<b>严格 3 帧周期</b>空帧（160 个连续样本 {@code 0 N N}、
     * 间隔恒 3、每次只空 1 帧）的头号候选：周期与「在飞深度 2 + 每帧一次轮转」同量级。
     * 默认给 <b>3</b>（≥ 等到完成所需的最短距离），并且可以 A/B：
     * 调到 1 若能<b>复现</b>「每第 3 帧为 0」，就证明那是仪器造的假黑帧，
     * GAP-020 重开时挂着的「3 帧周期」解释当场了结；调到 3/4 若黑帧消失，
     * 黑帧这件事从渲染缺陷清单里划掉（剩下的是另一回事：画面内容对不对）。
     */
    public static final ModConfigSpec.IntValue MRT_PIXEL_PROBE_READ_DELAY = BUILDER
            .comment("回读落地余量（探针节拍数，默认 3；引擎的 submit 完成最早在 +2 次 submit 后才可保证）。"
                    + "调 1 用来复现「每第 3 帧全 0」这个仪器假象。")
            .defineInRange("mrt.pixelProbeReadDelay", 3, 1, 20);

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
     * 🔬 **链级二分判据**（h47 N 臂后续）：只执行链的前 N 步（0 = 不跑链）。
     * 用途：bloom 级（BSL 第 5 步）之前的 deferred/composite1..3 谁把画面抬亮，
     * 一臂一个 N、帧尾探针给数 —— 与 h45 单变量 A/B 同一族手段，为 GAP-017 收口服务。
     * ⚠️ N < 全链时 final 不跑 ⇒ main 不被链写，判读对象是**被允许跑的最后一级的输出槽**。
     */
    public static final ModConfigSpec.IntValue MRT_POST_CHAIN_MAX_PASSES = BUILDER
            .comment("取证二分：只跑链的前 N 步（默认 64 = 全链；0=不跑）。判读看最后被跑级的输出槽探针。")
            .defineInRange("mrt.postChainMaxPasses", 64, 0, 64);

    /** 探针在链模式下额外测哪些槽（逗号分隔；默认 0,1,2 —— bloom 前线三判点）。 */    public static final ModConfigSpec.ConfigValue<String> MRT_PIXEL_PROBE_CHAIN_SLOTS = BUILDER
            .comment("链模式探针槽位（逗号分隔，默认 \"0,1,2\"；scratch 槽永远是 0 不占预算）。")
            .define("mrt.pixelProbeChainSlots", "0,1,2");

    /**
     * 🔬 GAP-017 的**机制**判据：把 colortex 的指定 mip 级也各测一次（逗号分隔；空 = 不测）。
     *
     * <p>为什么需要它：金字塔生成自报只证明「在生成」，证明不了「高 LOD 里真的是降采样平均值」。
     * BSL 的 bloom / 自动曝光按 {@code colortexNMipmapEnabled} 采 LOD 8~9，那一级若是黑或
     * 等于 mip0 ⇒ 必然过曝，而 mip0 的数字完全看不出来（h48）。
     */
    public static final ModConfigSpec.ConfigValue<String> MRT_PIXEL_PROBE_MIP_LEVELS = BUILDER
            .comment("把 colortex 的指定 mip 级也各测一次（逗号分隔，默认 \"\"=不测；"
                    + "例 \"8,9\" 读金字塔顶部）。")
            .define("mrt.pixelProbeMipLevels", "");

    /**
     * 🔬 GAP-019 判据：在**地形 MRT pass 刚画完**时额外打一次 colortex0。
     *
     * <p>帧尾那一槽早被链覆写 ⇒ 「帧尾为 0」分不清是「地形没画进池」还是「链把它打没了」，
     * 而这两个要修的不是同一个东西。默认关（每帧多一次全屏回读），只在定位时开。
     */
    public static final ModConfigSpec.BooleanValue MRT_PIXEL_PROBE_AFTER_TERRAIN = BUILDER
            .comment("定位用：地形 MRT pass 刚画完时额外测一次 colortex0（标签 c0@afterTerrain，默认关）。")
            .define("mrt.pixelProbeAfterTerrain", false);

    /**
     * 🔬 **GAP-016 同族判据**（默认关）：把后处理链的采样器钉 `maxLod = 0`。
     *
     * <p>用途：链里 `composite` 这类**纯 `texture2D`（隐式导数）**读 colortex 的步骤，
     * 与地形图集当年「导数选坏 mip ⇒ albedo ≡ 0」是同一族风险（GAP-016）。
     * 开了它黑帧消失 ⇒ 黑因就是导数选 mip；代价是包**故意**的高 LOD tap（bloom/曝光计量）
     * 全部落到 mip0（已知会过曝，见 GAP-017 K 臂）⇒ 这是判据档，不是产品档。
     */
    public static final ModConfigSpec.BooleanValue MRT_CHAIN_SAMPLER_LOD0 = BUILDER
            .comment("取证判据：后处理链采样器钉 maxLod=0（默认关 = 完整 mip 范围）。"
                    + "开 = 检验「隐式导数选坏 mip」这一族是否就是黑帧成因。")
            .define("mrt.chainSamplerLod0", false);

    /**
     * 🔬 **链级逐 pass 追踪**（默认关）：每跑完一级就把「这一级写过的槽」回读一次。
     *
     * <p>为什么需要它：帧尾只有最终态，「哪一级把内容打没」只能靠二分（一臂一个 N）反复重启，
     *   而重启之间相机/时刻还会漂。逐 pass 追踪把整条曲线放进**同一臂、同一机位**里，
     *   黑在哪一级一目了然。标签形如 {@code trace2composite:c0}。
     * <p>代价（明写）：每个被追踪的槽每帧一次全屏回读 ⇒ 只在定位时开；
     *   槽集由 {@code mrt.postChainTraceSlots} 限定（默认 0,1,2）。
     */
    public static final ModConfigSpec.BooleanValue MRT_POST_CHAIN_TRACE = BUILDER
            .comment("取证：每级后处理 pass 跑完就回读它写过的槽（标签 traceK<name>:cN，默认关）。")
            .define("mrt.postChainTrace", false);

    /** 逐 pass 追踪要盯哪些槽（逗号分隔）。 */
    public static final ModConfigSpec.ConfigValue<String> MRT_POST_CHAIN_TRACE_SLOTS = BUILDER
            .comment("逐 pass 追踪的槽位（逗号分隔，默认 \"0,1,2\"；只在 mrt.postChainTrace 开时生效）。")
            .define("mrt.postChainTraceSlots", "0,1,2");

    /**
     * GAP-003 非地形 gbuffer 线 · 第一步：让**天空**落进 colortex0（<b>默认开</b>）。
     *
     * <p>机制（R4 / 2026-10-12 换代，两条都走官方通路）：天空不再由我方插一个 pass 来画，而是
     * <b>落在原版 {@code "sky"} pass 体内</b> —— 包的天空走官方 {@code CustomSkyboxRenderer.renderSky}
     * （{@code LevelRenderer:385}，回 {@code true} 即与原版天然互斥，GAP-039 那四道门控由原版判），
     * 原版那一笔走 M-11 的视图 {@code @Redirect}（{@code SkyRenderer:134/135}）。
     * 🔖 <b>顺序 = 天空先、地形后盖</b>（h48e 实测：地形深度裁不住原版天空，「后补天空」会把刚画好
     * 的地形整片盖掉），由 M-13 的 {@code requires} 边保证 —— 靠<b>插入序</b>排帧图 pass 无效（h48g
     * 实测，见 {@code 06-MIGRATION.md} V5）。
     *
     * <p>🔴 <b>为什么默认从关翻成开</b>（2026-10-11 真机 RTX 4060 定案，判据数字见 GAP-019 天空行）：
     * ① BSL v10.1.8 把 {@code SKY_DEFERRED} <b>注释掉了</b>（{@code lib/settings.glsl:163}）⇒
     *    链里 {@code deferred1.glsl:473} 那支算天空的代码根本不参与编译 ⇒ <b>天空只能来自 gbuffer</b>；
     *    关着量的 {@code c0@afterTerrain} SKY_BAND 逐字 {@code (0,0,0) allZero=true}，
     *    画面 = 黑天空 + 一团长条白 = 用户报的「云渲染错误」。
     * ② 当年留关的唯一理由是 h48e「开天空 ⇒ 链输出恒 0」，那条<b>已在 h48k 被本仓自己撤销</b>
     *    （数字取自第 180~300 帧，没过预热闸门）⇒ 撤结论时没同步撤默认值。本轮补齐 h48k 留的判据：
     *    {@code c0@afterSky} SKY_BAND {@code mean_luma=160.06 nonBlack=100%}（与自报
     *    {@code skyColor=(0.502,0.628,1.000)}×255 逐位吻合），且 {@code main} 的 {@code allZero}
     *    在 1289 次读数里 <b>0 次</b>。
     *
     * <p><b>回退条件</b>：关掉 = 钩子不装、视图不换、槽 0 由地形自己清 ⇒ 回到「黑天空」那一档。
     */
    public static final ModConfigSpec.BooleanValue MRT_SKY_PASS = BUILDER
            .comment("GAP-003：天空落进 colortex0（在原版 \"sky\" pass 体内、地形之前，默认开）。"
                    + "🔴 关掉它 = 包的云画在纯黑底上 —— BSL 把 SKY_DEFERRED 注释掉了，天空只能来自 gbuffer"
                    + "（2026-10-11 真机定案，判据见字段注释与 GAP-019 天空行）。")
            .define("mrt.skyPass", true);

    /**
     * GAP-027 天空线第二刀：gbuffer 里的天空改用<b>包的 {@code gbuffers_skybasic}</b>（默认<b>开</b>，理由见下面「为什么默认开」）。
     *
     * <p>买到的是包算的天空（{@code GetSkyColor} 渐变 + {@code DrawStars} + {@code SunGlare} +
     * {@code nightVision}/{@code CLASSIC_EXPOSURE}），替掉的是上一条那一笔<b>原版平面底色</b>。
     * 🔖 走「一次全屏 draw」而不是重放穹顶几何：该程序的片元是<b>屏幕空间</b>的
     * （{@code gbuffers_skybasic.glsl:84-87} 用 {@code gl_FragCoord} 反解 {@code viewPos}），
     * 顶点不读任何属性 ⇒ 与后处理链那一族同形；离线证明见
     * {@code PackTerrainSourceTest#skybasicUsesPostVertexRouteAndNeedsOnlyOneAttachment}。
     * 🔴 开档时原版那一笔<b>必须不画</b>（两层天空同框 = 与 GAP-027 的双云同族）。R4 起这件事
     * 由原版第 385 行的短路给出（{@code renderSky} 回 {@code true} ⇒ 不再调 {@code skyRenderer.render}），
     * 不再是我方两个读点自己协调；自报行仍要说清本帧走的是哪条。
     *
     * <p>✅ <b>为什么默认开</b>（2026-10-11 真机 RTX 4060 两档复判）：正午天顶 = 包的蓝色渐变天空
     * + 体积云（原版档是平坦 {@code (128,160,255)} 天顶色）；夜里天顶 = <b>星点回来了</b>
     * （原版档无星的因是 {@code gl_FragCoord.z}：链那一族的全屏缓冲把 {@code Position.z} 写成 0，
     * 而包把屏幕深度当远平面用 ⇒ 星点噪声对所有像素塌成同一纹素；本类因此自带一份 z=1 的缓冲，
     * 见 {@code SkyPackGbuffer#skyVertexBuffer}）。
     *
     * <p><b>回退条件</b>：关掉 = 回到 {@code mrt.skyPass} 的原版重放档（上一轮定案的默认画面）；
     * 生成期配不上 post 顶点、或该程序输出不是单槽 0、或这条 pass 建立/执行抛 ⇒
     * <b>自动退回原版重放并点名一次</b>（实测过：探针包故意写坏片元时，画面回到原版档、
     * 日志一行「派生天空管线建立失败 ⇒ 回到原版重放」，不是黑屏）。
     */
    public static final ModConfigSpec.BooleanValue MRT_SKY_PACK_SHADER = BUILDER
            .comment("GAP-027：gbuffer 的天空改用包的 gbuffers_skybasic（一次全屏 draw；默认开）。"
                    + "关掉 = 回到 mrt.skyPass 的原版重放档；配不上顶点/输出不是单槽 0/这一路抛 ⇒ 自动退回原版档并点名一次，不静默换绑。")
            .define("mrt.skyPackShader", true);

    /** 🔴 R4 / M-11 开关：原版天空取视图实参的那两次调用换成我方 gbuffer（默认开；04-SPEC §5.0）。关档 = 原版天空回主目标、槽 0 由地形清屏 ⇒ 黑天空档。 */
    public static final ModConfigSpec.BooleanValue MIXIN_SKY_VIEWS = BUILDER
            .comment("R4 M-11：SkyRenderer#render 的两次取视图交出 colortex0 待写代 + gbuffer 深度（旧薄壳已删）。关闭 = 原版天空回主目标。")
            .define("mixin.skyViews", true);

    /** 🔴 R6 / M-14 开关：包选项进原版视频设置页（默认开；登记见 04-SPEC §5.0）。关档 = 本页不出现 vkdisp 那一段。 */
    public static final ModConfigSpec.BooleanValue MIXIN_PACK_OPTIONS_IN_VANILLA = BUILDER
            .comment("R6 M-14：把光影包与包选项嵌进原版视频设置页的 OptionsList（滚动、每行两列、标签、关屏 apply 都由原版做）。关闭 = 本页没有 vkdisp 段（自研屏幕已随 R6 删除，只能改配置文件）。")
            .define("mixin.packOptionsInVanilla", true);

    /** 🔴 R7 / M-04 ⑤-a 开关（登记键名沿用 §5.0 M-04 行）：默认<b>关</b> —— 开着=主 pass 换附件但不派生管线 ⇒ {@code setPipeline} 当场抛。 */
    public static final ModConfigSpec.BooleanValue MIXIN_OWN_TERRAIN_PASS = BUILDER
            .comment("R7 M-04 ⑤-a：把原版主 pass 的颜色附件换成我方 colortex 阵列（FrontendCommandEncoder 建描述符那一刻）。"
                    + "⚠️ ⑤-b 的通用派生公式没接之前开着就是崩（附件数 ≠ 管线颜色目标数）⇒ 默认关，只给取证档。")
            .define("mixin.ownTerrainPass", false);

    /** 🔴 R7 ⑤-d 第一刀（默认<b>关</b>）：主 pass 已被接管时，我方 gbuffer pass <b>不再</b>自己画地形（关双绘）。 */
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_SINGLE_DRAW = BUILDER
            .comment("R7 ⑤-d：关掉地形双绘。开着且主 pass 真被我方接管 ⇒ MrtTerrainPass 不再发 renderGroup(OPAQUE/TRANSLUCENT)，"
                    + "地形只由原版那一笔画一次。⚠️ 只在 mixin.ownTerrainPass=true 时才有意义（否则没人画地形）。")
            .define("mrt.terrainSingleDraw", false);

    /** 🔴 R7 ⑤-e 第一格（默认<b>关</b>）：接管主 pass 时把它的<b>深度附件</b>也换成我方 gbuffer 深度。 */
    public static final ModConfigSpec.BooleanValue MRT_TAKEOVER_DEPTH_TO_GBUFFER = BUILDER
            .comment("R7 ⑤-e：接管主 pass 时连深度附件一起换成 colortex 池的 gbuffer 深度（默认关 = 只换颜色，"
                    + "主 pass 的地形深度仍进原版主深度 ⇒ 链的 depthtex0/1/2 看不到它，见 GAP-053 的「修法」行）。")
            .define("mrt.takeoverDepthToGbuffer", false);

    /**
     * GAP-027 第二刀：把**云**画进 gbuffer（默认关）。
     *
     * <p><b>为什么要这一格</b>：真机画面判读（h48t 的 F2）直接看见「天上没有任何云」，
     * 而 BSL 默认档云是开的 ⇒ 不是选项问题，是这条 draw 根本没走 gbuffer。
     * 包的 {@code composite}/{@code final} 会读回 colortex0 ⇒ 云不进 gbuffer 就永远拿不到云的贡献。
     *
     * <p><b>机制与天空那一格的区别</b>（这条决定了它更简单）：原版
     * {@code CloudRenderer#render(CloudStatus, RenderPass)} 是 public 且
     * <b>自己收 RenderPass</b>（不像 {@code SkyRenderer} 要从 {@code RenderTarget} 取视图）
     * ⇒ 我方直接开一个<b>只有一个颜色附件</b>的 pass 给它就行。
     * 🔴 <b>必须 1 个附件</b>：它内部用原版 {@code RenderPipelines.CLOUDS / FLAT_CLOUDS}
     * （1 个颜色目标），而我方地形 pass 在接了水之后是 2 个附件 ⇒
     * 附件数 ≠ 颜色目标数会被 frontend 的 {@code setPipeline} 校验<b>当场抛</b>（响亮失败）。
     *
     * <p>🔖 <b>顺序 = 地形之后、翻代之前</b>（两件事各自的理由）：
     * ① 原版序列就是「solid → … → translucent → clouds」（{@code LevelRenderer} 第 727/738 行）；
     * ② GAP-018 的双代轮转里 {@code advanceWritten} 一翻代，「待写那一代」就换了 ⇒
     * 云必须和地形写<b>同一代</b>，否则链读到「只有地形、没有云」（h48i 的天空就是踩过这个）。
     *
     * <p>⚠️ <b>本格只把云搬进 gbuffer，云用的还是原版着色器</b> ——
     * 换成包的 {@code gbuffers_clouds} 需要管线替换那条路（GAP-027 登记的
     * {@code RenderSystem#getCompiledPipelineNullable} 首条语句就是
     * {@code PIPELINE_MODIFIERS.apply(pipeline)}），那是下一刀。
     */
    /**
     * 🔬 GAP-027 云那一格的**诊断档**（默认关）：把云 pass 的颜色附件按 CLEAR 成洋红打开。
     *
     * <p><b>它切的是什么</b>：h49l 量到「draw 发了（{@code quadCount=9865}）但
     * {@code c0@afterClouds} 与 {@code c0@afterTerrain} 逐位相同」。这句话有两种完全不同的读法：
     * ① 云的 draw 状态不对（深度 / 位姿 / 裁剪）⇒ 要修的是云；
     * ② 我方那个 pass 挂的 view 与被读的代次<b>根本不是同一张图</b>
     *    （GAP-018 的双代轮转口径错）⇒ 那要先修代次模型，<b>比云更根本</b>。
     * 洋红是「一定看得见」的颜色：读到洋红 ⇒ ② 排除、问题在云的 draw；
     * 仍读到地形的值 ⇒ ② 成立。
     *
     * <p>⚠️ <b>开启会毁掉本帧 colortex0 的地形内容</b>（CLEAR 而不是 LOAD）⇒
     * 它只能当取证档用，<b>永不默认开</b>，且结论里必须写明「本臂是诊断档」。
     */
    public static final ModConfigSpec.BooleanValue MRT_CLOUDS_DIAGNOSTIC_CLEAR = BUILDER
            .comment("🔬 GAP-027 诊断：云 pass 按 CLEAR 洋红打开（默认关；开启会毁掉本帧 colortex0 的"
                    + "地形内容）。判据 = c0@afterClouds 读到洋红 ⇒ 云 pass 与代次口径都对、"
                    + "问题在云的 draw；仍读到地形值 ⇒ 挂的 view 与被读的代次不是同一张图。")
            .define("mrt.cloudsDiagnosticClear", false);

    /**
     * GAP-027：云走「关背面裁剪」的派生管线。默认<b>开</b> —— 关掉就等于「云一条像素都不写」，
     * 它不是「可选的增强」而是<b>修正一个把云剔光的错误状态</b>（证据链见下面 comment）。
     *
     * <p>依据（h49n/h49o 实测）：原版 {@code RenderPipelines.CLOUDS}（FANCY 云）<b>带</b>背面裁剪，
     * 而我方 gbuffer pass 里同一份云几何被<b>剔光</b>（洋红底上零写入）；切到 {@code fast}
     * 走 {@code FLAT_CLOUDS}（逐字 {@code withCull(false)}）后云像素落地。
     * ⇒ 本格用 NeoForge 的管线替换通道把 {@code CLOUDS} 换成 {@code withCull(false)} 的派生版。
     *
     * <p>⚠️ 它只解决「被剔光」，<b>不</b>解决「覆盖只有 ~0.1%」那一半（位姿/相机偏移未查）。
     */
    /**
     * GAP-027：云**不写** gbuffer 深度。默认<b>关</b> —— 🔴 <b>h50b 把它自己的假设否证了</b>：
     * 开着它跑「云开 + {@code depthGlProxy}=true」那一格，{@code deferred1} 黑帧
     * <b>50/76 ≈ 66%</b>，与不开时（h50a：52/78 = 67%）在观测误差内相同
     * ⇒ 「云把深度写进 AO 读的 depthtex」<b>不是</b>那条 67% 黑帧的原因。
     * 代码留着当对照诊断项（它顺手排除的是「深度内容被云污染」这一整类），
     * 但<b>不拿一个没被证实的东西去改默认行为</b>。
     *
     * <p>🔑 <b>为什么需要它（h49z/h50a 那个 2×2）</b>：原版
     * {@code RenderPipelines.CLOUDS} 继承 {@code DepthStencilState.DEFAULT}
     * = 逐字 {@code (GREATER_THAN_OR_EQUAL, writeDepth=true)}
     * （{@code renderpearl/api/pipeline/DepthStencilState.java}），
     * 而我方云 pass 挂的是<b>同一张 gbuffer 深度附件</b>（{@code MrtTerrainPass.depthView()}）
     * ⇒ 云的深度被写进链里 AO 读的那张 {@code depthtex}。
     * 实测：云关时 GAP-022 的按族供值把 {@code deferred1} 的黑帧从 33% 压到 0.6%，
     * 云开时两种开关都是 <b>67%</b> ⇒ 修矩阵口径不够，<b>坏的是深度内容本身</b>。
     *
     * <p>🔖 <b>只关写入、保留测试</b>：写成「云挂一张私有空白深度」也能让 AO 干净，
     * 但那样山就再也挡不住云了 —— 那是换一种错。
     */
    public static final ModConfigSpec.BooleanValue MRT_CLOUDS_NO_DEPTH_WRITE = BUILDER
            .comment("GAP-027：云只关深度**写入**、保留深度测试（默认关：h50b 否证了它的假设）。"
                    + "依据 = CLOUDS 继承 DepthStencilState.DEFAULT(writeDepth=true) 而云 pass 挂的"
                    + "就是 gbuffer 那张深度 ⇒ 云深被写进 AO 读的 depthtex（h49z/h50a：云开时黑帧 67%，"
                    + "且与 depthGlProxy 无关）。")
            .define("mrt.cloudsNoDepthWrite", false);

    public static final ModConfigSpec.BooleanValue MRT_CLOUDS_NO_CULL = BUILDER
            .comment("GAP-027：云改用 withCull(false) 的派生管线（默认**开**）。"
                    + "依据 = h49n→h49s 四臂：原版 CLOUDS 带背面裁剪 ⇒ 云在我方 pass 里被剔光"
                    + "（诊断洋红底上零写入），换成 withCull(false) 后生产档 LOAD 下"
                    + "c0@afterClouds 与 c0@afterTerrain 出现稳定差 (R+0.88 / B−1.26)。"
                    + "关掉 = 回到「云一条像素都不写」，只用于对照取证。")
            .define("mrt.cloudsNoCull", true);

    public static final ModConfigSpec.BooleanValue MRT_CLOUDS_PASS = BUILDER
            .comment("GAP-027：把原版云画进 gbuffer colortex0（地形之后、链之前；默认关）。"
                    + "本格只搬几何、不换成包的着色器 —— 换包着色器要走管线替换那一刀。")
            .define("mrt.cloudsPass", false);

    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_ATLAS_LOD0 = BUILDER
            .comment("GAP-016 止血：包地形图集采样器 maxLod=0（默认开；关闭即回到实测恒 0 的"
                    + "隐式导数 LOD 路径，仅用于复现/修根对照）。")
            .define("mrt.terrainAtlasLod0", true);

    /**
     * 🔴 <b>GAP-022 ①：GL 口径深度代理</b>（默认关 —— 未验证到「画面真的对」之前不默认生效）。
     *
     * <p><b>它修的是什么</b>：引擎窗口深度是<b>反向 Z</b>（近平面 = 1.0、天空 = 0.0；
     * 源码事实 = {@code Projection#getMatrix} 里那句 {@code float near = this.zFar; float far = this.zNear;}
     * × {@code VulkanDevice.java:91-95} 给 {@code DeviceInfo.isZZeroToOne} 传 true ⇒ X34 从经验规律
     * 升级为源码事实），而包全部按「1.0 = 天空」写：BSL {@code deferred1.glsl:337 isSky = z == 1.0}、
     * {@code ambientOcclusion.glsl:55 z>=1.0 return 1.0}、{@code :59 hand = z<0.56} …
     * ⇒ 天空永远不被认成天空、中远景被当成「手」把光柱按 {@code 1−hand} 抹掉、AO/雾拿垃圾 viewPos。
     *
     * <p><b>机制</b>：地形 pass 之后、链第一级之前跑<b>一次全屏 pass</b>，把
     * {@code 1 − z_engine} 写进一张 R32F 离屏图；链里的 {@code depthtex*} 改绑那张图
     * （实现见 {@code dev.vkdisp.bridge.DepthGlProxy}）。
     * 同一组 (near, far) 下 {@code z_gl = 1 − z_engine} <b>恒等</b>（GAP-022 的代数证明），
     * 所以一次逐像素取反就是全精度正确的换算，不需要知道 near/far。
     *
     * <p>🔴 <b>必须与矩阵那一半同帧生效</b>：包里 {@code GetLinearDepth} 用 {@code depth×2−1}
     * 反解 NDC，再乘 {@code gbufferProjectionInverse} —— 只翻深度不翻投影矩阵会比全错更难查：
     * 像「有阴影但位置全歪」。<b>接线已完成</b>（h48w）：同一个开关现在同时决定
     * {@code depthtex* = 1 − z} 与 {@code OfUniformManager} 交出的那几本投影矩阵
     * （{@code gbufferProjection = D2·P} / {@code gbufferProjectionInverse = P⁻¹·D2inv}，
     * 上一帧那一本同口径；本体见 {@code render/DepthConventionPair}），
     * 一个帧内只读一次配置 ⇒ 结构上不存在「一半翻了一半没翻」。
     * 生效与否则由 {@code [GAP-022] depth convention = …} 那行自报（<b>两种状态各一条</b>）。
     *
     * <p><b>默认关的理由</b>（不是「还没写完」）：矩阵那一半是<b>数值</b>推出来的，
     * 而 h48w §四 明写不许因为「推出来了」就默认改产品 —— 判据在<b>画面侧</b>
     * （{@code isSky = z==1.0} 翻完站对边、SSR/体积云/光柱/镜斑的像素位置对上）。
     * 开着它做单变量取证时，结论必须写清当时自报行报的是哪一种口径。
     *
     * <p><b>还没被这一对覆盖的</b>（登记，免得把「成对」读成「全都成对了」）：
     * ① {@code shadowProjection} / {@code shadowtex*} <b>不翻</b> —— 代理只顶替 {@code depthtex*}，
     * 跟着翻光源空间反而是新的半翻（GAP-015/016 另案）；
     * ② {@code gbufferProjection} 同时被包的<b>顶点级</b>用（{@code ftransform()} 展开成
     * {@code gbufferProjection * gbufferModelView * …}，见 {@code glsl.translate.FtransformExpander}）
     * ⇒ 开档时地形顶点写进 gbuffer 的深度也会跟着换口径，而 {@code DepthGlProxy} 又对那张图再取一次
     * {@code 1 − z}。这两条的组合<b>只有画面能判</b>，也正是默认关着的理由。
     *
     * <p><b>降级是可见的</b>：开关开着而代理缺席（纹理没建出来 / 管线没编出来 / 深度视图为 null）
     * ⇒ 一次性 WARN 指名缺了哪一条，此时绑过去的仍是反向 Z 原图（X11：不许静默换绑）。
     */
    public static final ModConfigSpec.BooleanValue MRT_DEPTH_GL_PROXY = BUILDER
            .comment("GAP-022 ①：给链一张 GL 口径深度代理（depthtex* = 1 − 引擎反向 Z，"
                    + "天空=1.0 / 近=0.0），并同帧翻 gbufferProjection/Inverse（半翻比不翻更坏）。"
                    + "开着但代理没跑成会打一次 WARN（不静默换绑）。"
                    + "🔴 但**开着不等于画面正确**，仅供取证：gbufferProjection 同时被顶点阶段用"
                    + "（转译终稿 gl_Position = gbufferProjection * gbufferModelView * position），"
                    + "翻完之后顶点产出的是 [-1,1] 的 clip.z，而本前端的设备深度值域是 [0,1]"
                    + "（DeviceInfo.isZZeroToOne=true，没有 GL 那步 (ndc+1)/2 视口映射）"
                    + "⇒ 光栅化进 gbuffer 的深度会越界，深度测试连带失真。"
                    + "要真正生效必须按程序族分别供值（gbuffers_* 的顶点阶段留引擎口径、"
                    + "composite/deferred 那批全屏步给 GL 口径），见登记表 GAP-022 的 h48z 更正。")
            .define("mrt.depthGlProxy", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VkDispConfig() {
    }
}
