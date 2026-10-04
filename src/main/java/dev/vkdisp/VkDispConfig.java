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

    /** 诊断日志：打开后在各个 pass 与管线构建点输出计数类日志。 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOG = BUILDER
            .comment("诊断日志。打开后输出管线构建/编译计数，便于排查静默失败。")
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
    public static final ModConfigSpec.BooleanValue MRT_TERRAIN_FULLSCREEN_PROBE = BUILDER
            .comment("诊断：地形 MRT pass 内先画全屏三角形（默认关）。")
            .define("mrt.terrainFullscreenProbe", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VkDispConfig() {
    }
}
