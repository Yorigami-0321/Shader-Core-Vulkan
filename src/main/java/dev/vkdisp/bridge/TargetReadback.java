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
import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.pack.PackOptionOverrideSwitch;
import dev.vkdisp.pipeline.model.MrtPlan;
import dev.vkdisp.pipeline.model.PackOptionEvidence;
import dev.vkdisp.pipeline.model.PixelProbePlan;
import dev.vkdisp.pipeline.model.PixelProbeVerdict;
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

    /**
 * 预热帧数下限：地形 MRT pass 画够这么多帧之前，探针**只报数字、不出对照结论**。
 *
 * <p>🔖 取 600（对齐 {@code MrtTerrainPass} 自己打 draw 统计的 300/1200 节奏，
 * 取中间值）：低于它时 colortex 很可能只有清屏值。
 * 🔖 这不是「先等等再说」，而是**明确拒绝出结论** —— 沉默与「有结论」在日志上无法区分。
 */
private static final long WARMUP_FRAMES = 600L;

    /** 「预热期内不出结论」只报一次。 */
    private static final AtomicBoolean WARMUP_NOTED = new AtomicBoolean();

    /** 「没有可回读的目标」只报一次。 */
    private static final AtomicBoolean NO_TARGET_NOTED = new AtomicBoolean();

    /**
     * 🔖🔖 本轮探针的**决策签名**（上次打过说明的那一条）。
     *
     * <p>决策本身由 {@link PixelProbePlan} 给出；这里只负责「决策变了才把说明打进日志」。
     * 为什么不每轮都打：探针默认每 300 帧一轮，一次三分钟取证就是几十行**逐字相同**的说明
     * （h33 刷过 2702 行、h34 刷过 499 行 —— 同一条纪律）。
     * 而「决策变了」（切档 / 切包 / 换附件数 / 换槽位集合）正是最该被看到的那一刻。
     */
    private static String lastPlanNote;

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

        // 🔖🔖 取证件（2026-10-05 实测踩到后才加的）：每轮先把「**实际生效**的包选项覆盖」
        //   打一遍，再打任何数字。理由见 PackOptionEvidence 的类注释 ——
        //   本项目自己就因为落盘 store 里残留着前一轮的 PARALLAX=false 而带偏了一整轮结论，
        //   而当时日志里每一行都正常。⇒ 覆盖来源必须**每轮**自报，不能只在启动时打一次
        //   （取证过程会热加载改配置，h44 就用了热加载切档）。
        RenderTarget main = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // 🔖 主目标先测：它是「用户看到的」那个面（h31 收尾被撤回的教训 = 判读对象必须自报）。
        boolean any = submit("main", main == null ? null : main.getColorTexture());
        // 🔖🔖 再测我方 pass 写出的槽：**挑哪一槽由 PixelProbePlan 决定，不由本类猜**。
        //   本类上一版硬编码「terrainToMain 档改测槽 1」，而实测默认档包声明写的是
        //   [0, 3, 6, 7]（outputCount=8）⇒ 槽 1 那一帧**不是附件**，读它必然是清屏值
        //   ⇒ 日志会报 `colortex1 allZero=true`，被读成「包片元输出黑」——
        //   那是**假证据**：真相是「那张图没有包的输出」。
        //   （同族第五例：把「附件存在」当成「附件被写了」。）
        PixelProbePlan plan = decidePlan();
        reportPlanOnce(plan);
        for (int slot : plan.colortexSlots()) {
            any |= submit("colortex" + slot, MrtTerrainPass.slotTexture(slot));
        }
        // 🔖 ③ 方块图集（texture_0 的真值）：把它也当一个源测一次。
        //   h13 只对图集的 mip 链做过**静态**核查，从未在**运行期**取过它的数字；
        //   而「包片元乘上去的那张图是不是黑的」正是 albedo ≡ 0 的头号候选输入。
        if (atlasProbeEnabled()) {
            any |= submit("blockAtlas", blockAtlasTexture());
        }
        if (!any && NO_TARGET_NOTED.compareAndSet(false, true)) {
            VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 本帧没有可回读的目标"
                    + "（主目标或 colortex 未建成）⇒ 未取到任何数字。"
                    + "⚠️ 「没有数字」与「数字是 0」是两件事，不要混读");
        }
    }

    /**
     * 测槽决策只在<b>决策变了</b>时打一行。
     *
     * <p>🔖 决策说明是长文本（槽位集合 + 降级原因），探针每 300 帧算一次；
     * 无节流就是一次三分钟几十行逐字相同的 WARN（h33 刷过 2702 行、h34 刷过 499 行）。
     * 而「决策变了」（切档 / 切包 / 换附件数 / 换槽位集合）恰恰最该被看到。
     */
    private static void reportPlanOnce(PixelProbePlan plan) {
        if (plan.note().equals(lastPlanNote)) {
            return;
        }
        lastPlanNote = plan.note();
        VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 本轮测槽决策: toMain={} attachments={} 测={}"
                        + " comparable={} truncated={} —— {}",
                MrtTerrainPass.toMain(), MrtTerrainPass.actualSlots(), plan.colortexSlots(),
                plan.comparable(), plan.truncated(), plan.note());
    }

    /**
     * 方块图集是否也纳入回读（默认关：它是一个**大得多**的纹理，回读缓冲按整帧尺寸分配）。
     *
     * <p>🔖 为什么不默认开：图集是 mip 链纹理、尺寸远大于主目标，
     * 每轮多一次整图 GPU→CPU 拷贝与一块同尺寸缓冲。
     * 而它是「输入侧」的独立一条证据，取证时才需要 ⇒ 按需开启。
     */
    public static boolean atlasProbeEnabled() {
        return VkDispConfig.MRT_PIXEL_PROBE_ATLAS.get();
    }

    /**
     * 🔖 当前生效的包选项覆盖（取证件）。
     *
     * <p>🔖 <b>为什么这里要读两个来源</b>：① {@code config/vkdisp-pack-options.properties}
     * （选项 GUI「完成」落盘，跨会话持久）；② {@code pack.optionOverrides} 配置串（不落盘）。
     * 只查一个必然漏 —— 本项目 2026-10-05 就因为漏查①而把「默认档」带偏了一轮。
     */
    static PackOptionEvidence currentEvidence() {
        java.util.Map<String, String> store = new java.util.LinkedHashMap<>();
        try {
            PackOptionStore loaded = PackOptionStore.load(PackOptionStore.pathFor(
                    net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()));
            for (java.util.Map.Entry<String, String> entry : loaded.entries().entrySet()) {
                store.put(entry.getKey(), entry.getValue());
            }
        } catch (Throwable t) {
            // 🔖 读不到 store **不等于**「没有覆盖」—— 必须说出来，否则又是静默空转。
            VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 读取包选项 store 失败（原文：{}）"
                    + " ⇒ 本轮取证件**只含** pack.optionOverrides 一路；"
                    + "若你以为 store 里有覆盖生效，这条 WARN 就是证据", t.toString());
        }
        return PackOptionEvidence.of(store, PackOptionOverrideSwitch.spec());
    }

    /**
     * 算出本轮测槽决策（纯逻辑委托，决策本身可单测）。
     *
     * <p>🔖 槽位集合取 {@link MrtPlan#packDeclaredOutputSlots()} —— 注册期与附件数
     * <b>同一次</b>冻结的那一份（见 {@code MrtPlan.FrozenPackContract}）。
     * 空集合 = 未知（未接包片元）⇒ 决策会明确降级为「只报原始数字，不产出对照结论」。
     */
    private static PixelProbePlan decidePlan() {
        return PixelProbePlan.decide(
                MrtTerrainPass.actualSlots(),
                MrtTerrainPass.toMain(),
                MrtPlan.packDeclaredOutputSlots(),
                MrtProbe.viewSlot());
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
            // 🔖 取证件跟在每个数字后面：单看一行数字无法判断它是哪个覆盖配置下测的。
            VkDisp.LOGGER.info("vkdisp: [pixel-probe] {} {} {}", roundTag, stats.format(label),
                    currentEvidence().format());
            // 🔖④ 同一份像素再按「天空带 / 地形带 / 整屏」分区各取一次数：
            //   单一矩形回答不了「黑的是天空还是地形」（h17 那个混淆的形态）。
            for (PixelStats.AreaSample area : PixelStats.areas(width, height)) {
                if (area.region().area() == 0L || area.region().equals(region)) {
                    continue; // 空区跳过；与中心矩形同形的区不重复打
                }
                PixelStats.Stats sub = PixelStats.of(pixels, width, bytesPerPixel, area.region());
                VkDisp.LOGGER.info("vkdisp: [pixel-probe] {} {} area={} {}",
                        roundTag, label, area.label(), sub.format("[" + area.label() + "]"));
            }
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
     *
     * <p>🔖🔖 <b>逐槽各自一条结论</b>（2026-10-05 改）：本类第一版只有一个 {@code lastVerdict}，
     * 而 {@link PixelProbePlan} 现在会测<b>多个</b>被写的槽。用单一状态位的结果是
     * 「槽 3 报了 SLOT_BLACK、槽 6 报 BOTH_HAVE_CONTENT」时，第二个槽的结论会被第一个槽
     * 的状态位吃掉（误判成「没变化」而不报）⇒ 恰恰漏掉最该看到的那一格。
     * ⇒ 状态按槽分别记；每槽只在**自己的**结论变化时报。
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
        // 🔖🔖 判读委托给纯逻辑的 PixelProbeVerdict：**必须带档位**。
        //   本类上一版有一张与档位无关的四分标签表，而那在 terrainToMain=true 下整张是反的
        //   （该档「主目标」就是我方 pass 的附件 0 = 包的 albedo）⇒ 2026-10-05 实测拿到
        //   「主黑 + colortex3 正常」时，旧标签会读成 NOT_ON_MAIN（"draw 没落到主目标"），
        //   而真相是 draw **落到了**、只是 albedo ≡ 0 = GAP-008 本体。
        PixelProbeVerdict verdict =
                PixelProbeVerdict.of(MrtTerrainPass.toMain(), main.isAllZero(), slot.isAllZero(), label);
        // 🔖🔖 预热门闩（2026-10-05 实测踩到）：探针按帧数节流，而地形 MRT pass 头几帧
        //   往往还没真的画出东西（区块未网格化 / 附件刚清屏）。
        //   实测后果：同配置、同机位连跑两次，colortex0 一轮读 12.42、另一轮读 **0.0000**，
        //   而 main 两轮**逐位相同**（13.1864）⇒ 差异只可能来自 colortex 那一路「还没画」。
        //   若拿那一轮当基线，实验臂就会得到一个「基线是全黑、探针臂有内容」的假结论
        //   ⇒ 比不测更坏。⇒ 预热期内**只报数字、不出对照结论**，并自报这是预热。
        if (MrtTerrainPass.framesDrawn() < WARMUP_FRAMES) {
            if (WARMUP_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 地形 MRT pass 只画了 {} 帧（< {}）"
                                + " ⇒ **预热期内不产出对照结论**，只报原始数字。"
                                + "⚠️ 头几帧的 colortex 可能只有清屏值 —— 实测同配置两轮可读出"
                                + " 0.0000 与 12.42 而 main 逐位相同；拿预热帧当基线会得到假结论",
                        MrtTerrainPass.framesDrawn(), WARMUP_FRAMES);
            }
            return;
        }
        // 🔖 只在该槽自己的结论变化时报告，不每轮重复。
        //   理由：verdict 是一个**状态**，不是每帧的事件。按探针间隔（默认 300 帧）重复报
        //   同一句话，一次三分钟取证就是几十行噪声（h33 的 2702 行 / h34 的 499 行同族）。
        //   而「结论从 BOTH_HAVE_CONTENT 变成 SLOT_BLACK」这种**变化**恰恰最该被看到。
        String previous = LAST_VERDICT.get(label);
        if (verdict.id().equals(previous)) {
            return;
        }
        LAST_VERDICT.put(label, verdict.id());
        String head = "vkdisp: [pixel-probe] 结论变化 = " + verdict.id() + "（" + label + "，"
                + (MrtTerrainPass.toMain() ? "toMain档" : "toMain=false档") + "）：";
        String numbers = " main=(" + main.format("main") + ") " + slot.format(label) + ")";
        switch (verdict.severity()) {
            case RED -> VkDisp.LOGGER.warn(head + verdict.meaning() + " 数字：" + numbers);
            case YELLOW -> VkDisp.LOGGER.warn(head + verdict.meaning() + " 数字：" + numbers);
            default -> VkDisp.LOGGER.info(head + verdict.meaning() + " 数字：" + numbers);
        }
    }

    /**
     * 每个 colortex 源<b>各自</b>上一次的对照结论（源标签 → verdict）。
     *
     * <p>🔖 必须是按源分开的一张表：测多个槽时单一状态位会互相吃掉结论（见 compareSources）。
     */
    private static final Map<String, String> LAST_VERDICT = new HashMap<>();

    /**
     * 方块图集的<b>纹理</b>（GPU→CPU 拷贝的源；{@code null} = 取不到）。
     *
     * <p>🔖 它是包地形片元 {@code texture_0} 的**真值**绑（见
     * {@code TerrainPipelineApi#bindPackTerrainUniforms} 的 {@code ATLAS_2D → atlas}），
     * 而「乘上去的那张图是不是黑的」正是 albedo ≡ 0 的头号输入侧候选。
     * 🔶 只取 {@code mip 0}（{@code getTexture()} 返回的就是基级）；mip 链本身属
     * {@code h13} 已静态核查过的范围，不在本探针口径内。
     */
    private static @Nullable GpuTexture blockAtlasTexture() {
        try {
            net.minecraft.client.renderer.texture.AbstractTexture texture =
                    net.minecraft.client.Minecraft.getInstance().getTextureManager()
                            .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            return texture == null ? null : texture.getTexture();
        } catch (Throwable t) {
            if (ATLAS_FAILURE_NOTED.compareAndSet(false, true)) {
                VkDisp.LOGGER.warn("vkdisp: [pixel-probe] 取方块图集纹理失败（原文：{}）"
                        + " ⇒ 本轮没有 texture_0 真值的运行期数字。"
                        + "⚠️ 「没取到」与「取到了且是黑的」是两件事", t.toString());
            }
            return null;
        }
    }

    /** 「取方块图集失败」只报一次（热路径日志 I/O 是真实开销，见 M-01 埋点教训）。 */
    private static final AtomicBoolean ATLAS_FAILURE_NOTED = new AtomicBoolean();

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