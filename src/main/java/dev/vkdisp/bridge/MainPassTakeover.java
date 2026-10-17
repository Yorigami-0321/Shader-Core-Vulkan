package dev.vkdisp.bridge;
/**
 * 【参考调研】R7 / M-04 第 ⑤-a 步：认出原版主 GPU pass 那一次建 pass，把它的颜色附件换成我方 colortex 阵列
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 原版 26.3.0.51-beta sources jar：{@code LevelRenderer} 第 <b>455-463</b> 行那次
 *    {@code createRenderPass(() -> useImprovedTransparency ? "Solid" : "Main",
 *    mainTarget.getColorTextureView(), Optional.empty(), mainTarget.getDepthTextureView(),
 *    OptionalDouble.empty())}（实测原文，两个附件都是 <b>LOAD</b>；它在 {@code pass.executes} 的
 *    <b>lambda 内</b> ⇒ 不能按调用点注入，见 {@code 04-SPEC} §5.0.7 顶部那条事实）；
 *    ② {@code com.mojang/renderpearl/frontend/FrontendCommandEncoder} 第 <b>80-96</b> 行：那个 <b>6 参</b>
 *    便利重载自己造描述符（第 89 行 {@code RenderPassDescriptor.builder(label).withColorAttachment(...)}）
 *    并在第 <b>95</b> 行 {@code return this.createRenderPass(descriptor.build())}；2 参/5 参重载都并到它（67-78）
 *    ⇒ 「包第 95 行那一次调用」= 只看得见<b>单颜色附件</b>的便捷路径，我方直接递描述符的那几处
 *    （{@code MrtTerrainPass:439/637}、{@code FrameApi:1317}）从这一点<b>物理看不见</b>；
 *    ③ {@code RenderPassDescriptor} 第 17-32 行（record：{@code Supplier<String> label} /
 *    可含 {@code null} 的颜色附件列表 / 深度附件 / {@code renderArea}；{@code Attachment(GpuTextureView, T)}）
 *    与第 34-77 行（builder：{@code builder(Supplier)}、{@code withColorAttachment(view, clear)}、
 *    {@code withDepthAttachment(view, clear)}、{@code withRenderArea}、{@code build()} 里那条
 *    「renderArea 缺省时取<b>最后一个非 null 颜色附件</b>的尺寸」= §5.0.6 ⑤ 第 2 条）；
 *    ④ {@code FrontendRenderPass} 第 107-121 行「pass 附件数与管线 color target 数、逐条格式必须相等」；
 *    ⑤ {@code RenderTarget} 第 26/105/129 行：{@code colorTextureView} 是<b>缓存字段</b>
 *    ⇒ 视图的对象身份可以直接当判据用（不是每次新建）。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以；本文件独立编写
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码（参考模组零接触，07-CONSTRAINTS L12）
 * 1. 官方/主实现：只借「描述符是自己造出来的」这一处公开结构 —— <b>不</b>替换 render pass 的生命周期、
 *    <b>不</b>动那 5 段各自独立的 GPU pass（§5.0.6 ①），只在这一次调用上换颜色附件，其余逐字转发。
 * 2. 备选（都否决，理由可数）：① 注 {@code LevelRenderer#addMainPass} 里那次调用 —— <b>做不到</b>：
 *    那行在 lambda 内，按调用点注入只能瞄合成方法 {@code lambda$addMainPass$N}（编号随方法排布变号 =
 *    {@code GAP-042} 那一族）；② 注 {@code VulkanCommandEncoder} —— <b>否决</b>：绑到后端
 *    （X7 +「不要动：后端分叉」）；③ 包第 95 行的<b>外层方法</b> {@code createRenderPass(RenderPassDescriptor)}
 *    —— <b>否决</b>：那一点我方自己的多附件 pass 也路过，判据得再长一层「这是不是原版」，而选内层点
 *    就不需要这一层；④ 给原版一个「N 附件的 mainRenderTarget」 —— <b>不可行</b>：
 *    {@code RenderTarget} 只有一个颜色纹理，那正是要绕的事实。
 * 3. 我们的差异点：🔴 <b>本步故意只做一半</b>（§5.0.7 ⑤-a）—— 换上 N 个颜色附件但<b>不</b>派生管线，
 *    于是 {@code setPipeline} 当场抛「附件数不匹配」。这不是缺陷，是本步<b>唯一的证据</b>：
 *    抛了 = 注入真命中主 pass；没抛而接管计数为 0 = 判据没匹配（label 改名 / 视图不同源）。
 *    ⚠️ 因此这条路径<b>开着就是崩</b> ⇒ 开关 {@code mixin.ownTerrainPass} 默认<b>关</b>，
 *    且 ⑤-b（通用派生公式）之前不许把它写进任何取证档。
 * 4. 许可证核对：本项目 LGPL-3.0-or-later；零第三方代码并入。
 * 5. 性能基线：每帧若干次判定（一次 size + 一次 label + 一次引用比较）；接管命中时多建一个 builder。
 */
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 主 pass 附件接管（M-04 的第 ⑤-a 步；登记表：{@code 04-SPEC} §5.0 M-04 行 + 执行序 §5.0.7）。
 *
 * <p>🔴 <b>判据只用调用本身的数据</b>，不用任何「现在到哪儿了」的静态标志（那是刚被 M-15 废掉的
 * {@code inMrtPass} 那一族）：当下两道闸门 —— ① 颜色附件<b>恰好一个</b>、② label ∈
 * {@code {"Main","Solid"}}（第 458 行给的就是这两个串，全原版只有这一个出处）。
 * 第三道「那个附件就是 {@code gameRenderer.mainRenderTarget()} 的颜色视图」<b>已降级成交叉核对</b>：
 * 真机实测它永远不成立（{@code targets.main.get()} 与后者不是同一对象）⇒ 见
 * {@link #matchesMainPass} 的 ⚠️ 条与 {@code GAP-049}。挡掉的邻居仍是有出处的：
 * {@code GameRenderer:412} 那条 {@code "Item in hand"} 也是「单附件 + 主目标的颜色」，
 * 它与主 pass 的<b>唯一</b>区别就是 label ⇒ label 这一腿不能省。
 *
 * <p>⚠️ <b>为什么还要一条计数</b>：② 是<b>字符串</b>比较，Mojang 改了 label 就静默不接管
 * （画面退回今天，不报错）。所以本类统计「看见多少次建 pass」与「其中接管几次」，看够了而一次都没接上
 * ⇒ 打一条 WARN 红灯（口径同 {@code SkyIntoGbuffer} 的「钩子被调#」）。
 */
public final class MainPassTakeover {

    /** 原版主 pass 的两个可能 label（{@code LevelRenderer:458} 的三元式两边）。 */
    private static final List<String> MAIN_PASS_LABELS = List.of("Main", "Solid");

    /** 每帧路过这个挂点的建 pass 次数 ≈ 个位数 ⇒ 600 次约一秒。 */
    private static final int REPORT_PERIOD = 600;

    /**
     * 观察面。🔖 五格计数全装进这一个 {@code static final} 持有者 ⇒ 本类<b>不</b>新增「静态可变字段」
     * （{@code StaticFieldRatchetTest} 数的正是那个口径，19 §3.2 病根「静态即全局单例」，基线只许降）。
     */
    private static final class Stats {
        private long seen;
        private long takenOver;
        private long refusedSize;
        /** 判据②那一腿的命中数（label ∈ 表内）—— 与 {@link #viewHits} 一起才分得清是哪条腿断的。 */
        private long labelHits;
        /** 判据①+②+③三条都过的次数（= 除我方附件是否备好之外，全部匹配）。 */
        private long viewHits;
        private RenderPass takenOverPass;
        private int takenOverSlots;
        /**
         * 主 pass <b>自己那条 target0</b> 的尺寸（{@code GAP-049} 要的那个数：不是
         * {@code gameRenderer.mainRenderTarget()} 的宽/高，而是描述符里那条附件的）。
         * 0 = 还没看过（首帧之前一定是 0 —— 管线注册早于任何 pass）。
         */
        private int mainWidth;
        private int mainHeight;
        /** 🔴 因「改进透明（OIT）开着」而拒绝接管的次数（{@code GAP-052}：OIT 那几条 pass 的画布是另一张图）。 */
        private long refusedOit;
        private boolean reportedOitFork;
        /** ⑤-e 第一格：深度附件被换成 gbuffer 深度的次数 / 因视图缺失退回不换的次数与点名位。 */
        private long depthSwapped;
        private long depthRefused;
        private boolean warnedNoDepthView;
        private boolean reportedViewMismatch;
        /** 1.1 的仪器：接管被拒的累计次数 + 只点名一次。 */
        private long refused;
        private boolean reportedRefuse;
        private boolean reportedFirstHit;
        private boolean warnedNoMatch;
    }

    private static final Stats STATS = new Stats();

    private MainPassTakeover() {
    }

    /**
     * mixin 的转发入口（包住 {@code FrontendCommandEncoder} 第 95 行那一次调用）。
     *
     * @param descriptor 原版刚造好的那条描述符（单颜色附件 + 可选深度 + renderArea）
     * @param original   被包住的那次调用 —— 不接管时传的<b>必须</b>是入参那一个对象
     */
    public static RenderPass onCreateRenderPass(RenderPassDescriptor descriptor,
            Function<RenderPassDescriptor, RenderPass> original) {
        RenderPassDescriptor replaced = attachmentReplaced(descriptor);
        RenderPass created = original.apply(replaced);
        if (replaced != descriptor) {
            // 🔖 记下这条 pass 本身：M-15 那一侧靠「renderPass == 这个对象」判「这次 draw 在接管后的
            //    主 pass 里」⇒ ⑤-b 的补槽只发生在那一刻。每帧建新对象，旧的自动失效（身份不再相等）。
            STATS.takenOverPass = created;
            STATS.takenOverSlots = replaced.colorAttachments().size();
            MainPassPipelineDerivation.reportPeriodically();
        }
        return created;
    }

    /** 这次 draw 是不是落在<b>接管后的主 pass</b> 里（对象身份；没接管时永远 {@code false}）。 */
    public static boolean isTakenOverPass(RenderPass renderPass) {
        return renderPass != null && renderPass == STATS.takenOverPass;
    }

    /**
     * 主 pass <b>有没有至少被接管过一次</b>（= 我方附件真的挂在原版主 pass 上）。
     *
     * <p>🔴 ⑤-d 用它当第二个条件：只开「单绘」而没接管 ⇒ <b>谁都不画地形</b>（空屏），
     * 那是自伤不是优化。这里读的是 {@link #STATS} 里那条被记下的 pass 对象，不是任何标志位。
     */
    public static boolean takeoverHappened() {
        return STATS.takenOverPass != null;
    }

    /** 接管后的主 pass 有几个颜色附件（= ⑤-b 要补到的条数）；没接管返回 0。 */
    public static int takenOverSlots() {
        return STATS.takenOverSlots;
    }

    /**
     * 主 pass 自己那条颜色附件（target0）的宽（{@code GAP-049} 的数）；0 = 还没看过。
     *
     * <p>⚠️ 与 {@code gameRenderer.mainRenderTarget().width} <b>不保证同源</b> —— 真机实测连纹理都不是
     * 同一张。这里给的是「主 pass 实际画进去的那张图的尺寸」，而池是按后者建的
     * ⇒ 两者是否巧合相等，由 {@link GbufferSizeAnchor#checkPoolSize} 每次建池时核对。
     */
    public static int mainPassTargetWidth() {
        return STATS.mainWidth;
    }

    /** 同上，高。 */
    public static int mainPassTargetHeight() {
        return STATS.mainHeight;
    }

    private static RenderPassDescriptor attachmentReplaced(RenderPassDescriptor descriptor) {
        STATS.seen++;
        if (STATS.seen % REPORT_PERIOD == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-a] 建 pass 观察={} 主 pass 接管={} label命中={} 视图同源={}"
                            + " 尺寸不合拒绝={} OIT拒绝={} 深度换={} 深度退回={} 开关={}",
                    STATS.seen, STATS.takenOver, STATS.labelHits, STATS.viewHits, STATS.refusedSize,
                    STATS.refusedOit, STATS.depthSwapped, STATS.depthRefused,
                    VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get());
        }
        // 🔴 1.2：主 pass 的 target0 尺寸要记在接管开关<b>之前</b>（为什么，见 recordMainPassTargetSize）
        recordMainPassTargetSize(descriptor);
        if (!VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get()) {
            return descriptor;
        }
        String label = labelOf(descriptor);
        if (!takeoverAllowedFor(descriptor, label)) {
            return descriptor;
        }
        GpuTextureView target0 = onlyColorView(descriptor);
        if (target0 == null) {
            warnIfNeverMatched();
            return descriptor;
        }
        STATS.labelHits++;
        // 🔖 尺寸在上面的 recordMainPassTargetSize 里已经记下（记在接管开关之前，理由见那里）。
        // 判据③「那个附件就是主目标的颜色纹理」—— 真机证明这一腿**永不成立**，故只当交叉核对留着：
        // 视图壳不同源（createTextureView 造的）还能解释，连**纹理本体**都不是同一张
        // （实测 附件纹理=0x2b6af37d vs 主目标纹理=0x326872b3）⇒ 主 pass 画进去的那张图不是
        // gameRenderer.mainRenderTarget()（GAP-049 定案）。身份由「单附件 + label」两腿担，
        // 而「接管有没有真命中主 pass」由后面那笔 setPipeline 抛负责证明（⑤-a 已签）。
        GpuTexture mainTexture = mainColorTexture();
        if (mainTexture != null && target0.texture() == mainTexture) {
            STATS.viewHits++;
        } else if (!STATS.reportedViewMismatch) {
            STATS.reportedViewMismatch = true;
            VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-a] label='{}' 命中，但那条附件与 gameRenderer 主目标<b>连纹理都不是</b>"
                            + "同一张（附件视图={} 附件纹理={} 主目标纹理={}；0x 是 identityHashCode）"
                            + " ⇒ 主 pass 的目标不是 gameRenderer.mainRenderTarget()，本类不据此拒绝接管；"
                            + "口径与后果见 GAP-049",
                    label, hex(target0), hex(target0.texture()), hex(mainTexture));
        }
        GpuTextureView[] ours = writeGenerationViews(target0.getWidth(0), target0.getHeight(0));
        if (ours == null) {
            STATS.refusedSize++;
            return descriptor;
        }
        STATS.takenOver++;
        GbufferPassOrder.noteMainPass();
        if (!STATS.reportedFirstHit) {
            STATS.reportedFirstHit = true;
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-a] 主 pass 的颜色附件已换成我方 colortex 阵列：{} 个"
                            + "（label='{}'，尺寸={}x{}）。⚠️ 本档<b>故意</b>不派生管线 ⇒ 下一次 setPipeline"
                            + " 应该抛「附件数不匹配」，那正是「注入真命中主 pass」的证据；"
                            + "不抛就是没命中，别把今天的画面读成接管后的效果",
                    ours.length, labelOf(descriptor), target0.getWidth(0), target0.getHeight(0));
        }
        return withOurColorAttachments(descriptor, ours);
    }

    /**
     * 1.2（{@code GAP-049} 的锚点数据源）：只把「主 pass 自己的 target0 尺寸」记下来，<b>不改任何决策</b>。
     *
     * <p>身份腿与接管判定同一条（{@link #matchesMainPass}：单附件 + 主 label），但<b>不看</b>
     * {@code ownTerrainPass} 开关、<b>不看</b> OIT —— 那两条门只在「要不要接管」那一步用。
     * 记过之后每帧一次整数比较即早退。
     */
    private static void recordMainPassTargetSize(RenderPassDescriptor descriptor) {
        if (STATS.mainWidth > 0) {
            return;
        }
        String label = labelOf(descriptor);
        if (!matchesMainPass(descriptor.colorAttachments().size(), label)) {
            return;
        }
        GpuTextureView target0 = onlyColorView(descriptor);
        if (target0 == null) {
            return;
        }
        STATS.mainWidth = target0.getWidth(0);
        STATS.mainHeight = target0.getHeight(0);
        VkDisp.LOGGER.info("vkdisp: [GAP-049/1.2] 记下主 pass 自己的 target0 尺寸 = {}x{}（label='{}'，"
                        + "ownTerrainPass={}）⇒ 观察在接管开关<b>之前</b>，双绘档也拿得到这个数，"
                        + "gbuffer 池的锚点从这一刻起按它建",
                STATS.mainWidth, STATS.mainHeight, label, VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get());
    }

    /**
     * 判定本体（<b>纯函数</b> ⇒ 不启动客户端就能单测，口径同 M-15 的 {@code PassAwarePipelines.select}）。
     *
     * <p>⚠️ 判据从三条收成两条是<b>真机逼出来的</b>（2026-10-12）：第三条「那个附件就是
     * {@code gameRenderer.mainRenderTarget()} 的颜色视图」在实机上<b>永远不成立</b> ——
     * {@code LevelRenderer:454} 用的是 {@code targets.main.get()}（第 210 行
     * {@code frame.importExternal("main", …)} 之后又被 253/383/404 行的 {@code readsAndWrites} 换过手），
     * 它交出的颜色视图与 {@code gameRenderer.mainRenderTarget()} 的<b>不是同一个对象</b>
     * （日志点名过：附件=0x11cf0e8a 主目标=0xc9c725d）。这条差异本身登记为 {@code GAP-049}
     * —— 我方多处把后者当「主目标」用，得逐条重核。当下用 label 那一腿担身份：
     * {@code "Main"} / {@code "Solid"} 这两个串在原版只有一个出处（{@code LevelRenderer:458}），
     * 而「改名即哑」由 {@link #warnIfNeverMatched} 那条红灯守着（口径同 {@code 钩子被调#}）。
     *
     * @param colorAttachmentCount 描述符里的颜色附件条数（含 null 占位）
     * @param label                {@code descriptor.label().get()} 的结果；取不到传 null
     */
    public static boolean matchesMainPass(int colorAttachmentCount, @Nullable String label) {
        return colorAttachmentCount == 1 && isMainLabel(label);
    }

    /**
     * 接管到底要不要发生（<b>纯函数</b>）：形状判据 {@link #matchesMainPass} <b>且</b>没开改进透明。
     *
     * <p>🔴 为什么 OIT 开着就不接管（{@code GAP-052}，源码级核实 {@code LevelRenderer}）：
     * 开改进透明时同一条 GPU pass 的 label 从 {@code "Main"} 变成 {@code "Solid"}（{@code :458} 的三元式），
     * 它里面只跑 {@code executeSolid}（{@code :465}），透明那一半改由 {@code executeOit}（{@code :471-472}）承担；
     * 而那条链<b>全程用 {@code gameRenderer.mainRenderTarget()} 当画布/深度源</b> ——
     * 地形画进 OIT 自己的 pass（{@code :628/:630}）但其深度是 {@code mainRenderTarget()} 的
     * （{@code mainDepthTextureView}，{@code :589}），最后 {@code "OIT Composite"}
     * 直接<b>写进 {@code mainRenderTarget()} 的颜色附件</b>（{@code :655} 取、{@code :660} 用作画布），
     * {@code "OIT cull Depth Bounds"}（{@code :686}）与 {@code "Water mask"}（{@code :707}）读它的深度。
     * 把主 pass 换成我方池子之后：前半帧的实体进池、后半帧在原版那张图上合成并出屏 ⇒
     * <b>池子成了孤儿而谁都不抛</b>（两张图都是合法附件 ⇒ <b>静默错</b>，比崩难查一个量级）。
     * 所以：要么哪天把 OIT 那一族也改指向池（⑤-e 的活），要么这一档<b>不接管</b>。
     * 本类选后者，并且<b>计数 + 点名</b>，不静默（口径同 {@code GAP-046} 的退路分支）。
     */
    public static boolean takeoverAllowed(int colorAttachmentCount, @Nullable String label,
            boolean improvedTransparency) {
        return !improvedTransparency && matchesMainPass(colorAttachmentCount, label);
    }

    /** 改进透明（OIT）此刻开着吗？客户端/渲染器还没起来时按「没开」处理（装配期不炸）。 */
    private static boolean improvedTransparencyOn() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft != null && minecraft.gameRenderer != null
                && minecraft.gameRenderer.useImprovedTransparency();
    }

    /**
     * 「这条描述符能不能接管」+ 两种<b>不同</b>的拒绝各归各的读数（抽出来是为了让
     * {@link #attachmentReplaced} 留在 {@code QD-04} 那条 60 行方法棘轮以内）。
     */
    private static boolean takeoverAllowedFor(RenderPassDescriptor descriptor, @Nullable String label) {
        boolean improvedTransparency = improvedTransparencyOn();
        if (takeoverAllowed(descriptor.colorAttachments().size(), label, improvedTransparency)) {
            return true;
        }
        if (improvedTransparency && matchesMainPass(descriptor.colorAttachments().size(), label)) {
            // 形状对得上、但因为 OIT 而**不该**接管 ⇒ 单独计数，别混进「判据不匹配」那条红灯
            refuseOitFork(label);
        } else {
            warnIfNeverMatched();
        }
        return false;
    }

    /** 因为 OIT 画布分叉而拒绝接管：计数 + 首次点名（口径同 {@code GAP-046} 的退路分支）。 */
    private static void refuseOitFork(@Nullable String label) {
        STATS.refusedOit++;
        if (STATS.reportedOitFork) {
            return;
        }
        STATS.reportedOitFork = true;
        VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-a] 主 pass 的形状对得上（单附件 + label='{}'），但「改进透明（OIT）」开着"
                + " ⇒ 这一档不接管：OIT 那一族全程以 gameRenderer.mainRenderTarget() 为画布/深度源"
                + "（最后 \"OIT Composite\" 写它的颜色），而我方主 pass 会写进 colortex 池"
                + " ⇒ 池子成了孤儿、屏幕照旧出图且谁都不抛（GAP-052）。"
                + "要么关掉改进透明，要么等 ⑤-e 把 OIT 的画布也统一到池子。计数={}",
                    label, STATS.refusedOit);
    }

    /** 因 OIT 而拒绝接管的次数（诊断/单测读数）。 */
    public static long oitRefusalCount() {
        return STATS.refusedOit;
    }

    /** 判据②那一腿单独拆出来：自报要分得清是「label 不匹配」还是「视图不同源」。 */
    private static boolean isMainLabel(@Nullable String label) {
        return label != null && MAIN_PASS_LABELS.contains(label);
    }

    /**
     * 补槽尺寸闸门（<b>纯函数</b>）：§5.0.6 ⑤ 第 2 条 —— 默认 {@code renderArea} 取「最后一个非 null
     * 颜色附件」的尺寸且<b>静默</b>，所以补槽必须与 target0 同尺寸；不一致就是在骗后端，直接不接管。
     * 槽数 &lt; 2 也在这里挡（单槽不叫接管）。
     */
    public static boolean slotsUsable(int ourSlotCount, int ourWidth, int ourHeight,
            int targetWidth, int targetHeight) {
        return ourSlotCount >= 2 && ourWidth == targetWidth && ourHeight == targetHeight;
    }

    /** 换颜色附件、其余原样（label / 深度 / renderArea 都沿用 —— ⑤-a 不管深度，那是 ⑤-e 的事）。 */
    private static RenderPassDescriptor withOurColorAttachments(RenderPassDescriptor descriptor,
            GpuTextureView[] ours) {
        // ⚠️ 这里一律用 var：把 RenderPassDescriptor 的嵌套类型写进签名再挂 @Nullable 会让 javac
        //    在 TransTypes 阶段自爆（"Incorrect number of arguments; expected 2, found 0"，实测）
        var builder = RenderPassDescriptor.builder(descriptor.label())
                // target0 连它的清屏语义一起沿用（原版这里是 Optional.empty() = LOAD）
                .withColorAttachment(ours[0], descriptor.colorAttachments().get(0).clearValue());
        for (int slot = 1; slot < ours.length; slot++) {
            builder.withColorAttachment(ours[slot], Optional.empty());
        }
        var depth = descriptor.depthAttachment();
        // 🔴 ⑤-e 第一格（`mrt.takeoverDepthToGbuffer`，默认关）：接管时连**深度附件**也换成我方 gbuffer 深度。
        //   为什么这是 ⑤-d 的前置而不是可选项：定序读数（GAP-053）证明主 pass 跑在我方 gbuffer pass
        //   **之后**，而链的 depthtex0/1/2 取的是 `colortexDepth` 的快照 ⇒ 不换深度，主 pass 画的地形深度
        //   永远进不了链（颜色对了也是空深度）。清屏语义**沿用原描述符**（原版这里是 LOAD：
        //   主深度早在 clear pass 里清过，而我方 pass 本帧已把 colortexDepth 清到 0.0 并画过地形）。
        boolean swapDepthRequested = VkDispConfig.MRT_TAKEOVER_DEPTH_TO_GBUFFER.get();
        GpuTextureView gbufferDepth = swapDepthRequested ? MrtTerrainPass.depthView() : null;
        int decision = depthDecision(swapDepthRequested, gbufferDepth != null);
        if (decision == DEPTH_SWAP_TO_GBUFFER) {
            builder.withDepthAttachment(gbufferDepth,
                    depth == null ? java.util.OptionalDouble.empty() : depth.clearValue());
            STATS.depthSwapped++;
        } else if (depth != null) {
            if (decision == DEPTH_SWAP_REFUSED_NO_VIEW) {
                noteDepthViewUnavailable();
            }
            builder.withDepthAttachment(depth.textureView(), depth.clearValue());
        }
        builder.withRenderArea(descriptor.renderArea());
        return builder.build();
    }

    /** 深度附件「不换」（今天的形状：只换颜色）。 */
    public static final int DEPTH_KEEP = 0;
    /** 深度附件换成我方 gbuffer 深度。 */
    public static final int DEPTH_SWAP_TO_GBUFFER = 1;
    /** 要求换，但那一刻池的深度还不存在 ⇒ **不静默**：退回不换 + 点名一次。 */
    public static final int DEPTH_SWAP_REFUSED_NO_VIEW = 2;

    /**
     * 换不换深度附件（<b>纯函数</b>）：测试运行时没有 renderpearl ⇒ 这里只判整数三态，
     * 设备侧才去取真的视图（口径同 {@code MainPassPipelineDerivation.refusalReason}）。
     */
    public static int depthDecision(boolean swapRequested, boolean gbufferDepthAvailable) {
        if (!swapRequested) {
            return DEPTH_KEEP;
        }
        return gbufferDepthAvailable ? DEPTH_SWAP_TO_GBUFFER : DEPTH_SWAP_REFUSED_NO_VIEW;
    }

    /** 「要换但视图还没建出来」—— 退路必须看得见（X11：不许静默降级）。 */
    private static void noteDepthViewUnavailable() {
        STATS.depthRefused++;
        if (STATS.warnedNoDepthView) {
            return;
        }
        STATS.warnedNoDepthView = true;
        VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-e] mrt.takeoverDepthToGbuffer=true 但 colortex 池的深度视图此刻还不存在"
                + " ⇒ 这一帧**不换**深度（逐字退回今天的形状，主 pass 仍写原版主深度）。"
                + "这通常意味着我方 gbuffer pass 还没建过池；计数={}", STATS.depthRefused);
    }

    /** 深度被换成 gbuffer 的次数（诊断/单测读数）。 */
    public static long depthSwapCount() {
        return STATS.depthSwapped;
    }

    /** 因视图缺失而退回不换的次数。 */
    public static long depthRefusalCount() {
        return STATS.depthRefused;
    }

    /** 颜色附件<b>恰好一条</b>且那条非 null 时才返回它挂的视图，否则 null（= 不是我们要的 pass）。 */
    private static @Nullable GpuTextureView onlyColorView(RenderPassDescriptor descriptor) {
        if (descriptor.colorAttachments().size() != 1) {
            return null;
        }
        var only = descriptor.colorAttachments().get(0);
        return only == null ? null : only.textureView();
    }

    /**
     * 候选附件 = 池的<b>待写那一代</b>（GAP-018 口径，与地形/云/天空完全一致）。
     * 尺寸与主目标不一致、或槽数不足 ⇒ 返回 null（= 不接管）。
     */
    private static @Nullable GpuTextureView[] writeGenerationViews(int targetWidth, int targetHeight) {
        MrtTerrainPass.prepareGbufferViews();
        int slots = MrtTerrainPass.actualSlots();
        GpuTextureView[] views = new GpuTextureView[slots];
        for (int slot = 0; slot < slots; slot++) {
            GpuTextureView view = MrtTerrainPass.poolWriteView(slot);
            if (view == null) {
                refuseOnce("池槽 " + slot + " 的**待写视图**还不存在（actualSlots=" + slots
                        + " 池大小=" + MrtTerrainPass.poolSize() + "）");
                return null;
            }
            if (!slotsUsable(slots, view.getWidth(0), view.getHeight(0), targetWidth, targetHeight)) {
                refuseOnce("槽数 " + slots + "（要求 ≥2）或尺寸不同：池写代="
                        + view.getWidth(0) + "x" + view.getHeight(0)
                        + " 主 pass target0=" + targetWidth + "x" + targetHeight
                        + "（池的锚点读数见 [GAP-049/1.2] 那一行）");
                return null;
            }
            views[slot] = view;
        }
        return views;
    }

    /**
     * 1.1 的仪器：接管被拒时**必须说清是哪一条腿**拒的。
     *
     * <p>此前这里只有一句 {@code return null}，而调用侧的计数叫「尺寸不合拒绝」—— 真机读到
     * {@code 尺寸不合拒绝=2025 / 主 pass 接管=0} 时，<b>视图缺失</b>与<b>尺寸不同</b>两种因
     * 共用一个名字，分不开就修不动（口径同 {@code 07} §九「降级点要写原因」）。只点名一次。
     */
    private static void refuseOnce(String reason) {
        STATS.refused++;
        if (STATS.reportedRefuse) {
            return;
        }
        STATS.reportedRefuse = true;
        VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-d] 主 pass 已判定（label 命中）但接管被拒：{}"
                + " ⇒ ⑤-d 单绘档在这一帧退回双绘（画面仍是今天的形状），累计拒绝 {} 次",
                reason, STATS.refused);
    }

    /** 🔴 红灯：开关开着、看了够多次建 pass，却一次都没接上 ⇒ 判据不再匹配（先怀疑 label 改名）。 */
    private static void warnIfNeverMatched() {
        if (STATS.takenOver == 0L && STATS.seen >= REPORT_PERIOD && !STATS.warnedNoMatch) {
            STATS.warnedNoMatch = true;
            VkDisp.LOGGER.warn("vkdisp: [M-04/⑤-a] mixin.ownTerrainPass=true 但看了 {} 次 createRenderPass"
                    + " 一次都没判定成主 pass ⇒ 判据已不匹配（label 改名？主目标颜色视图不同源？），"
                    + "这条注入等于没生效 —— 别把今天的画面读成接管后的效果；尺寸不合而拒绝 {} 次",
                    STATS.seen, STATS.refusedSize);
        }
    }

    /** 只用来判「是不是同一个对象」的打印形式（不是地址，是 identityHashCode，够区分对象身份）。 */
    private static String hex(@Nullable Object o) {
        return o == null ? "null" : "0x" + Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * 主目标的<b>颜色纹理本体</b>（判据③比的是它，不是视图壳）。取不到 ⇒ 返回 null（= 不接管）。
     *
     * <p>🔖 为什么不用 {@code getColorTextureView()} 的对象身份：{@code GpuTextureView} 是
     * {@code createTextureView(texture)} 造的壳，两条 handle 即使指向同一张图也不会是同一个对象
     * —— 上一版判据就是这么把主 pass 永久判飞的（{@code 视图同源=0}，22059 次 label 命中）。
     */
    private static @Nullable GpuTexture mainColorTexture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        return main == null ? null : main.getColorTexture();
    }

    private static @Nullable String labelOf(RenderPassDescriptor descriptor) {
        try {
            return descriptor.label() == null ? null : descriptor.label().get();
        } catch (Throwable t) {
            VkDisp.LOGGER.warn("vkdisp: [M-04] 取 render pass label 失败 ⇒ 本帧不接管：{}", t.toString());
            return null;
        }
    }
}
