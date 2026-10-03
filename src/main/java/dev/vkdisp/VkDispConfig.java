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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VkDispConfig() {
    }
}
