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
 *    → 能否并入本项目（MIT）：可以；本文件独立编写
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
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：每帧若干次判定（一次 size + 一次 label + 一次引用比较）；接管命中时多建一个 builder。
 */
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
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
 * {@code inMrtPass} 那一族）：三条一起 —— ① 颜色附件<b>恰好一个</b>、② label ∈ {@code {"Main","Solid"}}
 * （第 458 行给的就是这两个串）、③ 那个附件<b>就是</b>主目标的颜色视图（对象身份）。
 * ②③ 各排掉一半假阳：只写 main 颜色的 pass 有 Sky / SeeThrough / AlwaysOnTop / {@code "Item in hand"}
 * （{@code GameRenderer:412}）/ 原版 clear / 后处理链，它们 label 不同；label 相同的又不会挂 main 的颜色。
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
        return original.apply(attachmentReplaced(descriptor));
    }

    private static RenderPassDescriptor attachmentReplaced(RenderPassDescriptor descriptor) {
        STATS.seen++;
        if (STATS.seen % REPORT_PERIOD == 0L) {
            VkDisp.LOGGER.info("vkdisp: [M-04/⑤-a] 建 pass 观察={} 主 pass 接管={} 尺寸不合拒绝={} 开关={}",
                    STATS.seen, STATS.takenOver, STATS.refusedSize,
                    VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get());
        }
        if (!VkDispConfig.MIXIN_OWN_TERRAIN_PASS.get()) {
            return descriptor;
        }
        GpuTextureView target0 = onlyColorView(descriptor);
        boolean sameAsMain = target0 != null && target0 == mainColorView();
        if (!matchesMainPass(descriptor.colorAttachments().size(), labelOf(descriptor), sameAsMain)
                || target0 == null) {
            warnIfNeverMatched();
            return descriptor;
        }
        GpuTextureView[] ours = writeGenerationViews(target0.getWidth(0), target0.getHeight(0));
        if (ours == null) {
            STATS.refusedSize++;
            return descriptor;
        }
        STATS.takenOver++;
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
     * 判定本体（<b>纯函数</b> ⇒ 不启动客户端就能单测，口径同 M-15 的 {@code PassAwarePipelines.select}）。
     *
     * @param colorAttachmentCount     描述符里的颜色附件条数（含 null 占位）
     * @param label                    {@code descriptor.label().get()} 的结果；取不到传 null
     * @param attachmentIsMainColorView 那个唯一的附件是否<b>就是</b>主目标的颜色视图（对象身份）
     */
    public static boolean matchesMainPass(int colorAttachmentCount, @Nullable String label,
            boolean attachmentIsMainColorView) {
        return colorAttachmentCount == 1
                && label != null
                && MAIN_PASS_LABELS.contains(label)
                && attachmentIsMainColorView;
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
        if (depth != null) {
            builder.withDepthAttachment(depth.textureView(), depth.clearValue());
        }
        builder.withRenderArea(descriptor.renderArea());
        return builder.build();
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
            if (view == null
                    || !slotsUsable(slots, view.getWidth(0), view.getHeight(0), targetWidth, targetHeight)) {
                return null;
            }
            views[slot] = view;
        }
        return views;
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

    private static @Nullable GpuTextureView mainColorView() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        return main == null ? null : main.getColorTextureView();
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
