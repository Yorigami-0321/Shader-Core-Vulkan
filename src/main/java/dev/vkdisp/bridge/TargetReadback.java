package dev.vkdisp.bridge;
/**
 * 【参考调研】GPU→CPU **像素回读探针**（把「看截图」变成「日志里的可复算数字」）
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = ① 原版 26.3 {@code net.minecraft.client.Screenshot#takeScreenshot}
 *    （Mojang EULA）—— **本项目第一次调用的官方公开回读入口**，本类逐字沿用它的三件事：
 *    <ul>
 *      <li>{@code CommandEncoder#copyTextureToBuffer(tex, buffer, 0L, callback, 0)}；</li>
 *      <li>回读缓冲 usage = {@code USAGE_COPY_DST | USAGE_MAP_READ}（该类用的整数值 <b>9</b>，
 *          因为 {@code GpuBuffer.USAGE_MAP_READ = 1}、{@code USAGE_COPY_DST = 8}）；</li>
 *      <li>在<b>回调</b>里 {@code buffer.map(true, false)} 读数据（回调由后端在 GPU 完成后触发：
 *          Vulkan 侧是 {@code queueForDestroy(callback::run)}）。</li>
 *    </ul>
 *    ② 原版 {@code FrontendCommandEncoder#copyTextureToBuffer} 与 {@code RenderTarget#createBuffers}
 *    （Mojang EULA，只读**签名与前置条件**）：核实了三条硬约束 ——
 *    回读**必须在 render pass 之外**（否则抛
 *    {@code Close the existing render pass before performing additional commands}）、
 *    源纹理必须带 {@code USAGE_COPY_SRC}、缓冲必须带 {@code USAGE_COPY_DST}；
 *    且原版 {@code RenderTarget} 建的纹理 usage = 15（含 COPY_SRC）⇒ 主目标与我们自建的
 *    colortex（{@code TextureTarget} 同样走那条构造）都可回读。
 *    许可证：Mojang EULA；**只调用公开 API，零源码搬运、零着色器文本搬运。**
 *    → 能否并入本项目（MIT）：可以 —— 本文件为独立编写的桥接封装
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触（07-CONSTRAINTS L12）
 * 1. 官方/主实现：原版 {@code Screenshot#takeScreenshot} 本身就是「GPU 回读成像素数组」的官方范式；
 *    本类与它的差别只在<b>用途</b>：截图落盘给眼睛看，本类把像素**统计成数字**打进日志。
 * 2. 备选：
 *    <ul>
 *      <li>① 用 MCP 截图 + 外部 python 脚本统计 —— <b>否决</b>：这是 h22～h42 一直在用的办法，
 *          但它有三个实测缺陷。① 需要<b>人</b>在运行之外再跑脚本，而 h42 §4.3 想分开的
 *          「输出黑」与「没落到主目标」需要<b>同帧两个数字</b>；② 采样区写死在脚本里，
 *          窗口尺寸一变就与历史数字不可比；③ 数字不在日志里 ⇒ 跨会话的读者（AI）看不到量化判据，
 *          只能重新截图重算 —— 这正是 {@code h42} 「推翻自己结论」那类事故的温床。</li>
 *      <li>② 在 GPU 侧用 compute shader 降采样后回读 —— <b>否决</b>：要新增管线与附件，
 *          且降采样会把「逐像素恰好 0」这个已被实测坐实的判据（h28）抹成「平均后非 0」。</li>
 *      <li>③ 只对 colortex（诊断视图）做 —— <b>否决</b>：h31 已经因为「误用诊断视图下结论」
 *          撤回过一次收尾（见 GAP-008 条目）。⇒ 必须能对<b>主目标</b>直接取数。</li>
 *    </ul>
 * 3. 我们的差异点：
 *    <ul>
 *      <li>🔖 <b>两个源各测一遍，「两者不一致」是一等公民</b>：主目标（用户看到的）与
 *          colortex（我方 pass 写出的）本该一致。「colortex 有内容 + 主目标全黑」= 地形 draw
 *          <b>没落到主目标</b>；「两者都全黑」= 包片元<b>输出就是黑</b>。h42 §4.3 明确说这二者
 *          本轮<b>没有分开</b>，本类就是分开它们的手段。</li>
 *      <li>🔖 <b>统计口径不在本类</b>：全部委托 {@code pipeline.model.PixelStats}
 *          （纯逻辑、可单测），本类只负责「把像素搬进 {@code byte[]}」与节流。</li>
 *      <li>🔖 <b>默认关 + 节流 + 每源一个缓冲</b>：回读要走一趟 GPU→CPU 拷贝并映射内存，
 *          不是可以每帧做的事（支柱③ B1 ≤ +2%）。故 {@code mrt.pixelProbe} 默认关、
 *          开启后按 {@code mrt.pixelProbeEvery} 帧节流，且**永不每帧打日志**（h34 的 499 行教训）。
 *          每源一个缓冲的理由：两个源同时回读进<b>同一个</b>缓冲会互相覆盖（实测语义上必然发生），
 *          那会让「两个数字」变成「同一个数字的两份解读」。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 可关闭的诊断路径（默认关 ⇒ 常规帧零开销）。开启时的拷贝代价**未测**，
 *    按用户指令本轮不做性能结论。
 */
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispConfig;
import dev.vkdisp.pipeline.model.MrtPlan;
import dev.vkdisp.pipeline.model.PixelStats;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * 把一块渲染目标（主目标 / 某个 colortex 槽）的像素<b>统计成数字</b>并打进日志。
 *
 * <p><b>它回答的问题</b>（h42 §4.3 登记为未做）：「画面全黑」有<b>两种</b>完全不同的成因 ——
 * ① 包的地形片元输出全黑；② 地形 draw 根本没落到我们以为的那个目标上。
 * 只看截图分不开，因为两者的截图都是黑的。⇒ 本类对<b>同一帧</b>的两个源各取一次数：
 * <pre>
 *   colortex 有内容 + 主目标全黑  ⇒ draw 没落到主目标（是「接线/落点」问题，不是「着色」问题）
 *   两者都全黑                    ⇒ 包片元输出就是黑（是 GAP-008 本体）
 *   两者都有内容                  ⇒ 主目标链路正常，问题在别处
 * </pre>
 *
 * <p><b>为什么不用截图</b>：见类注释【参考调研】备选① —— 三条实测缺陷，
 * 其中「数字不在日志里」最致命：本项目跨会话的读者是 AI，它读不到截图里没有的数字。
 *
 * <p><b>线程模型</b>：调用点在渲染线程（帧尾、所有 pass 已关闭）；回调也在渲染线程
 * （后端的 destroy queue 在 submit/destroy 时于渲染线程跑）。
 */
public final class TargetReadback {

    /** 每源一个回读缓冲（源标签 → 缓冲；尺寸变化时重建）。 */
    private static final Map<String, GpuBuffer> BUFFERS = new HashMap<>();

    /** 每源在途请求数（重建缓冲前必须归零，否则旧回调会 map 到已关闭的缓冲）。 */
    private static final Map<String, Integer> PENDING = new HashMap<>();

    /** 每源缓冲对应的字节数。 */
    private static final Map<String, Long> BYTES = new HashMap<>();

    /** 「源纹理缺 USAGE_COPY_SRC」只报一次（不每帧刷屏）。 */
    private static final AtomicBoolean NOT_COPY_SRC_NOTED = new AtomicBoolean();

    /** 「提交回读失败」只报一次（带异常原文）。 */
    private static final AtomicBoolean SUBMIT_FAILURE_NOTED = new AtomicBoolean();

    /** 「回读回调失败」只报一次（带异常原文）。 */
    private static final AtomicBoolean CALLBACK_FAILURE_NOTED = new AtomicBoolean();

    /** 「没有可回读的目标」只报一次。 */
    private static final AtomicBoolean NO_TARGET_NOTED = new AtomicBoolean();

    /** 「terrainToMain 档下改测槽 1」只报一次（说明为什么不是槽 0）。 */
    private static final AtomicBoolean toMainSlotNoted = new AtomicBoolean();

    /** 「terrainToMain 档下没有第二个被写的目标」只报一次。 */
    private static final AtomicBoolean toMainNoSlotNoted = new AtomicBoolean();

    /** 提交成功次数（诊断视图：探针有没有真跑过）。 */
    private static long submitted;

    /**
     * 已完成的统计（键 = {@code 源标签#轮次}），用于两源对照。
     *
     * <p>🔖 <b>为什么键里带轮次</b>：两源对照必须来自<b>同一帧</b>。
     * 键不带轮次就会出现「拿这一帧的主目标配上一帧的 colortex」这种跨帧硬比 ——
     * 那比不比对更坏（它会给出一个看起来有依据、实则无效的结论）。
     */
    private static final Map<String, PixelStats.Stats> LAST = new HashMap<>();

    /** 探针轮次号（两源对照时用来确认两个数字来自同一帧）。 */
    private static long round;

    /** 距下次探针还有几帧（节流计数）。 */
    private static long framesUntilProbe;

    private TargetReadback() {
    }

    /** 像素回读探针开关（默认关 ⇒ 常规帧零开销）。 */
    public static boolean enabled() {
        return VkDispConfig.MRT_PIXEL_PROBE.get();
    }

    /** 本次运行提交成功的回读次数（诊断视图；纯 long）。 */
    public static long submittedCount() {
        return submitted;
    }

    /**
     * 帧尾调用：按配置对「主目标 + 当前 colortex 槽」各提交一次回读。
     *
     * <p>🔖 <b>必须在所有 render pass 关闭之后调用</b>（原版
     * {@code FrontendCommandEncoder#copyTextureToBuffer} 在 pass 内会抛
     * {@code Close the existing render pass before performing additional commands}）。
     * 本方法只被 {@link FrameApi#drawFullscreen} 尾部调用，那里整条链的 pass 都已关闭。
     */
    public static void probeFrameTail() {
        if (!enabled()) {
            return;
        }
        if (framesUntilProbe > 0L) {
            framesUntilProbe--;
            return;
        }
        framesUntilProbe = Math.max(1L, VkDispConfig.MRT_PIXEL_PROBE_EVERY.get());
        round++;

        RenderTarget main = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // 🔖 主目标先测：它是「用户看到的」那个面（h31 收尾被撤回的教训 = 判读对象必须自报）。
        boolean any = submit("main", main == null ? null : main.getColorTexture());
        // 🔖 再测我方 pass 写出的某一槽：两者的差值就是「落点」与「内容」的分离判据。
        if (MrtTerrainPass.enabled() && MrtTerrainPass.actualSlots() > 0) {
            // 🔴🔖 **槽 0 在 terrainToMain 模式下不是附件**（附件 0 已被换成主目标视图）。
            //   本轮实测踩到：那一档下 `colortex0` 根本没被写过，读它必然是「零填充的旧内容」
            //   ⇒ 日志会报 `colortex0 allZero=true`，而**真相是「那张图这一帧不是附件」**。
            //   那正是本项目最该消灭的形态：诊断给出一个**看起来像证据**的假数字。
            //   ⇒ 该档下改测**确实被写**的槽（1..n）；若只有一个槽（没得挑），
            //   就**明确报「本档下无法做两源对照」**，而不是拿一个不存在的数字充数。
            int slots = MrtTerrainPass.actualSlots();
            if (MrtTerrainPass.toMain()) {
                if (slots >= 2) {
                    int slot = 1;
                    if (!toMainSlotNoted.getAndSet(true)) {
                        VkDisp.LOGGER.warn("vkdisp: [pixel-probe] mrt.terrainToMain=true ⇒ 槽 0 已被换成"
                                + "主目标视图，**colortex0 这一帧不是附件**；本探针改测确实被写的 colortex"
                                + slot + "（bsl 默认配置只写槽 0，故该档的两源对照不含 albedo 槽）");
                    }
                    any |= submit("colortex" + slot, MrtTerrainPass.slotTexture(slot));
                } else if (!toMainNoSlotNoted.getAndSet(true)) {
                    VkDisp.LOGGER.warn("vkdisp: [pixel-probe] mrt.terrainToMain=true 且只有 " + slots
                            + " 个附件 ⇒ 除主目标外**没有第二个被写的目标** ⇒ 本档**不产出**两源对照结论"
                            + "（只报主目标的数字）。⚠️ 拿 colortex0 当对照会得到假证据："
                            + "它在 terrainToMain 档下根本不是附件");
                }
            } else {
                int slot = MrtPlan.requireViewSlot(MrtProbe.viewSlot(), slots);
                any |= submit("colortex" + slot, MrtTerrainPass.slotTexture(slot));
            }
        }
        if (!any) {
            if (NO_TARGET_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 本帧没有可回读的目标"
                        + "（主目标或 colortex 未建成）⇒ 未取到任何数字。"
                        + "⚠️ 「没有数字」与「数字是 0」是两件事，不要混读");
            }
        }
    }

    /**
     * 提交一次回读。
     *
     * @param label   日志标签（写明这是哪个观测面 —— 判读对象必须自报）
     * @param texture 源纹理（null / 未建成 → 返回 false，**不是**「全黑」）
     * @return 是否成功提交
     */
    private static boolean submit(String label, @Nullable GpuTexture texture) {
        if (texture == null || texture.isClosed()) {
            return false;
        }
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int bytesPerPixel = texture.getFormat().blockSize();
        // 🔖 前置条件（源码级核实，见【参考调研】②）：源纹理必须带 USAGE_COPY_SRC，
        //   否则 FrontendCommandEncoder 抛 "Texture needs USAGE_COPY_SRC to be a source for a copy"。
        if ((texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            if (NOT_COPY_SRC_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.error("vkdisp: [pixel-probe] 源纹理 {} 没有 USAGE_COPY_SRC（usage={}）"
                        + " ⇒ 无法回读。原版 RenderTarget#createBuffers 建的纹理 usage=15（含 COPY_SRC），"
                        + "若这里不是 15，说明该目标不是走原版 RenderTarget 建的", label, texture.usage());
            }
            return false;
        }
        PixelStats.Region region = PixelStats.Region.centered(width, height);
        if (region.area() == 0L) {
            return false;
        }
        long needed = (long) width * height * bytesPerPixel;
        GpuBuffer target = bufferFor(label, needed, width, height, bytesPerPixel);
        if (target == null) {
            return false;
        }
        String roundTag = label + "#" + round;
        PENDING.merge(label, 1, Integer::sum);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    texture, target, 0L,
                    () -> finish(label, roundTag, target, width, height, bytesPerPixel, region),
                    0);
            submitted++;
            return true;
        } catch (Throwable t) {
            PENDING.merge(label, -1, Integer::sum);
            if (SUBMIT_FAILURE_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.error("vkdisp: [pixel-probe] 提交回读失败（{}，原文）：{}",
                        roundTag, t.toString());
            }
            return false;
        }
    }

    /**
     * 回调：GPU 已完成 ⇒ 映射内存、统计、打日志、必要时做两源对照。
     *
     * <p>🔖 <b>为什么不在这里重新校验缓冲</b>：{@code bufferFor} 只在「该源无在途请求」时
     * 才重建，所以只要本请求还在途，{@code buffer} 就不可能被换掉或关闭
     * ⇒ 回调里的 {@code target} 必然仍有效。这条不变式是 {@code PENDING} 计数的全部作用。
     */
    private static void finish(String label, String roundTag, GpuBuffer target,
            int width, int height, int bytesPerPixel, PixelStats.Region region) {
        try {
            byte[] pixels;
            try (GpuBufferSlice.MappedView view = target.map(true, false)) {
                pixels = new byte[view.data().remaining()];
                view.data().get(pixels);
            }
            PixelStats.Stats stats = PixelStats.of(pixels, width, bytesPerPixel, region);
            LAST.put(roundTag, stats);
            VkDisp.LOGGER.info("vkdisp: [pixel-probe] {} {}", roundTag, stats.format(label));
            compareSources(label, roundTag);
        } catch (Throwable t) {
            if (CALLBACK_FAILURE_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.error("vkdisp: [pixel-probe] 回读回调失败（{}，原文）：{}",
                        roundTag, t.toString());
            }
        } finally {
            PENDING.merge(label, -1, Integer::sum);
        }
    }

    /**
     * 🔖 「两个源不一致」是一等公民 —— 显式说出来，取证者才不会自己脑补。
     *
     * <p>🔖 <b>同帧校验</b>：两个数字必须来自<b>同一轮</b>。本类的回调按提交顺序触发
     * （主目标先提交），因此「已见到 colortex 的统计」时，主目标的统计若已存在则同轮 ——
     * 不成立就不对照（宁可不出结论，也不拿跨帧数字硬比）。
     */
    private static void compareSources(String label, String roundTag) {
        if (!label.startsWith("colortex")) {
            return;
        }
        int hash = roundTag.lastIndexOf('#');
        String mainTag = "main" + roundTag.substring(hash);
        PixelStats.Stats main = LAST.get(mainTag);
        PixelStats.Stats slot = LAST.get(roundTag);
        if (main == null || slot == null || main.samples() == 0L || slot.samples() == 0L) {
            return; // 缺一侧或无样本 ⇒ 本轮**不构成**对照结论（不是「两者一致」）
        }
        boolean mainZero = main.isAllZero();
        boolean slotZero = slot.isAllZero();
        // 🔖🔖 **四种组合都必须区分**，不能只有「都黑 / 不都黑」两分。
        //   本类第一版写成三分（两黑 / 都非黑 / 主黑槽非黑），漏掉的正是
        //   「主目标有内容、colortex 逐像素全黑」这一格 —— 而那恰好是
        //   **GAP-008 的定义形态**（包片元 albedo ≡ 0；主目标此时是原版画面，看着正常）。
        //   漏掉它 ⇒ 日志会说「两源都有内容」⇒ 取证者据此以为 colortex 正常 ⇒ 又一次误判。
        String verdict = (mainZero && slotZero) ? "BOTH_BLACK"
                : (!mainZero && slotZero) ? "SLOT_BLACK"
                : (mainZero && !slotZero) ? "NOT_ON_MAIN"
                : "BOTH_HAVE_CONTENT";
        // 🔖 只在结论变化时报告，不每轮重复。
        //   理由：verdict 是一个**状态**，不是每帧的事件。按探针间隔（默认 300 帧）重复报
        //   同一句话，一次三分钟取证就是几十行噪声（h33 的 2702 行 / h34 的 499 行同族）。
        //   而「结论从 BOTH_HAVE_CONTENT 变成 SLOT_BLACK」这种**变化**恰恰最该被看到。
        if (verdict.equals(lastVerdict)) {
            return;
        }
        lastVerdict = verdict;
        switch (verdict) {
            case "NOT_ON_MAIN" -> VkDisp.LOGGER.warn(
                    "vkdisp: [pixel-probe] 🔴 结论变化 = NOT_ON_MAIN：colortex 有内容（{}）"
                            + "而主目标逐像素全黑（{}）⇒ 地形 draw **没有落到主目标**"
                            + "（落点/接线问题，不是着色问题）。"
                            + "这条把「输出黑」与「没落到主目标」分开了（h42 §4.3 登记的未分辨因素）",
                    slot.format("colortex"), main.format("main"));
            case "SLOT_BLACK" -> VkDisp.LOGGER.warn(
                    "vkdisp: [pixel-probe] 🔴 结论变化 = SLOT_BLACK：主目标有内容（{}）"
                            + "而 colortex 逐像素全黑（{}）⇒ 我方 pass **写进 colortex 的地形是黑的**"
                            + "（= GAP-008 本体：包片元输出黑）。⚠️ 主目标此刻是原版画面，"
                            + "看着「正常」不构成任何证据 —— h31 的收尾就是这么被推翻的",
                    main.format("main"), slot.format("colortex"));
            case "BOTH_BLACK" -> VkDisp.LOGGER.warn(
                    "vkdisp: [pixel-probe] 结论变化 = BOTH_BLACK：两源都逐像素全黑"
                            + " ⇒ 包地形片元输出黑**且**没落到主目标（或主目标也被清成零）");
            default -> VkDisp.LOGGER.info(
                    "vkdisp: [pixel-probe] 结论变化 = BOTH_HAVE_CONTENT：两源都有内容"
                            + " ⇒ 主目标链路正常；若画面仍不对，问题在两者之后（合成/上屏）");
        }
    }

    /** 上一次的对照结论（null = 尚未对照过）；与结论相同则不重复报告。 */
    private static String lastVerdict;

    /** 按字节数取该源的回读缓冲（尺寸变化时重建；重建前要求该源无在途请求）。 */
    private static @Nullable GpuBuffer bufferFor(String label, long bytes,
            int width, int height, int bytesPerPixel) {
        GpuBuffer existing = BUFFERS.get(label);
        if (existing != null && !existing.isClosed() && Long.valueOf(bytes).equals(BYTES.get(label))) {
            return existing;
        }
        // 🔖 在途请求未归零时不重建：旧请求的回调还要 map 这个缓冲
        //   （bufferFor 是本类唯一改动 BUFFERS 的地方 ⇒ 这个不变式由它自己保证）。
        if (PENDING.getOrDefault(label, 0) > 0) {
            return null;
        }
        if (existing != null) {
            existing.close();
        }
        int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ;
        GpuBuffer created = RenderSystem.getDevice().createBuffer(
                () -> "vkdisp pixel probe readback " + label, usage, bytes);
        BUFFERS.put(label, created);
        BYTES.put(label, bytes);
        VkDisp.LOGGER.info("vkdisp: [pixel-probe] 回读缓冲就绪: source={} {}x{} blockSize={} bytes={}",
                label, width, height, bytesPerPixel, bytes);
        return created;
    }
}