package dev.vkdisp;
/**
 * 【参考调研】P2.4 虚拟资源包 vkdisp_pack（AddPackFindersEvent + 内存 PackResources）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① NeoForge 26.3 官方事件 net.neoforged.neoforge.event.AddPackFindersEvent
 *    （LGPL-2.1 定义类 —— 只观察 javap 签名 addRepositorySource/getPackType 与触发时机，不复制实现）；
 *    ② 原版 Minecraft 26.3 资源包公共 API（Mojang EULA —— javap 核实的公开构造器与接口：
 *    Pack/PackLocationInfo/Pack.Metadata/PackSelectionConfig/PackResources/PackResources$ResourceOutput，
 *    独立手写实现，零源码文本搬运）；③ 本仓库 docs/04-SPEC.md §2（命名空间 vkdisp_pack）/
 *    §3.1（不写 options.resourcePacks）与 docs/18-PARALLEL.md §5 P2.4 ①②③（设计与时序）。
 *    → 能否并入本项目（MIT）：可以 —— 只调用官方公开 API + 本方纯 Java 冷路径入口
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：AddPackFindersEvent.addRepositorySource（官方注册入口）+ Pack 强校验构造器
 *    （required=true 由 PackRepository.rebuildSelected 强制入选中集，字节码已核实 ——
 *    因此**绝不写** options.resourcePacks，04-SPEC §3.1）。
 * 2. 备选：① 写 options.resourcePacks 让用户选项面板带包 —— 否决（04-SPEC §3.1 只读红线 + 会污染
 *    用户配置）；② 直接读包文件当 mod 内资源塞 assets/ —— 否决（assets 是构建期静态的，库存包运行期才知）；
 *    ③ 用 Pack.readMetaAndCreate 读 pack.mcmeta —— 否决（需要真文件，内存源没有；直接构造 Metadata 更直接，
 *    兼容性显式 COMPATIBLE 不做版本猜测，X9）。
 * 3. 我们的差异点：
 *    ① **生成时机在 openResources**（不是注册时）——注册发生在 Minecraft 构造期 1602 偏移，
 *       此刻配置尚未加载（ClientModLoader.finish@3079 才加载完）；openAllSelected@3114 生成源，
 *       两个事件的字节码偏移实测见 18-PARALLEL §5 P2.4 ②；
 *    ② **永不抛穿资源加载**：生成链任意 Throwable → ERROR 原文 + 内置 passthrough 兜底
 *       （composite / deferred 都是 required 管线，抛穿会砸启动；T11 要求显式可见而非静默）；
 *    ③ PackResources 是内存实现（三资源：composite/deferred/final 三片元），
 *       无文件句柄，close 为空操作。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只观察官方签名（07-CONSTRAINTS L5-L8 / X19-X21）。
 * 5. 性能基线：❄️ 冷路径（注册一次 + 每次资源加载生成一次），清晰优先不做优化（18-PARALLEL §7.7）。
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import dev.vkdisp.config.PackOptionStore;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.pack.PackCompileCache;
import dev.vkdisp.pack.PackCompositeSource;
import dev.vkdisp.pack.PackPrecompileScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * P2.4：虚拟资源包 {@code vkdisp_pack}（04-SPEC §2）——把「库存包的 composite 片元源」
 * 接进原版资源系统，使 {@code vkdisp_pack:composite} 这一管线片元 id 可被 ShaderManager 解析。
 *
 * <p>注册链：AddPackFindersEvent（mod bus，Minecraft 构造期偏移 1602）→ addRepositorySource →
 * {@code PackRepository.reload}@1612 枚举到本包 → rebuildSelected 按 {@code required=true}
 * 强制入选中集（**不写** options.resourcePacks，04-SPEC §3.1）→ openAllSelected@3114 调
 * {@code openResources} 生成源（此刻配置已加载@3079）→ 首次资源加载@3153 编译管线着色器。
 *
 * <p>源内容（{@link PackCompositeSource}）：总开关关闭 / 库存无可用 composite → 内置 passthrough
 * 兜底（必带 WARN，T11）；否则 = 第一个能编出 composite 片元的库存包 + profile 覆盖差分改写。
 * composite 是 required 管线 —— 无论哪条分支，源**永远存在**（管线编译失败会砸启动）。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class VkDispVirtualPack {

    /**
     * 虚拟包命名空间（04-SPEC §2）；管线片元 id = {@code vkdisp_pack:composite} /
     * {@code :deferred} / {@code :final}。
     */
    public static final String NAMESPACE = "vkdisp_pack";

    /** 包内资源：composite 片元（相对 assets/ 的路径）。 */
    public static final String COMPOSITE_PATH = "shaders/composite.fsh";

    /** 包内资源（P3.3）：deferred 步片元（相对 assets/ 的路径）。 */
    public static final String DEFERRED_PATH = "shaders/deferred.fsh";

    /** 全量资源 id：assets/vkdisp_pack/shaders/composite.fsh（ShaderManager FileToIdConverter 解析口径）。 */
    private static final Identifier COMPOSITE_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, COMPOSITE_PATH);

    /** 全量资源 id（P3.3）：assets/vkdisp_pack/shaders/deferred.fsh。 */
    private static final Identifier DEFERRED_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, DEFERRED_PATH);

    /** 包内资源（P4.1.4）：final 步片元（相对 assets/ 的路径）。 */
    public static final String FINAL_PATH = "shaders/final.fsh";

    /** 全量资源 id（P4.1.4）：assets/vkdisp_pack/shaders/final.fsh。 */
    private static final Identifier FINAL_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, FINAL_PATH);

    /**
     * GAP-003：包内资源（包自己的地形片元，相对 assets/ 的路径）。
     *
     * <p>🔖 <b>与前三者语义不同</b>：composite / deferred / final 是 <b>required 管线</b>，
     * 源必须永远存在（缺失会砸启动）；地形片元是<b>可选接线</b> ——
     * 没有就沿用原版 {@code core/terrain}，所以 {@link #terrainProgram()} 允许为 null，
     * 且 null 时本资源<b>根本不提供</b>（没有任何管线引用它）。
     */
    public static final String TERRAIN_PATH = "shaders/gbuffers_terrain.fsh";

    /** 全量资源 id：assets/vkdisp_pack/shaders/gbuffers_terrain.fsh。 */
    private static final Identifier TERRAIN_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, TERRAIN_PATH);

    /**
     * GAP-003：最近一次 {@code openResources} 生成时选中的包地形片元契约（{@code null} = 不接线）。
     *
     * <p>🔖 <b>为什么是契约对象而不是裸字符串</b>：管线注册要用它的输出数、绑定组要用它的
     * sampler 名、顶点适配层要用它的 varying 签名 —— 三处都从同一个对象读，才不会出现
     * 「附件数 3、颜色目标 1」那种<b>崩客户端</b>的错配（X42）。
     * volatile：生成在资源加载线程写、渲染线程读（与 {@link #hasDeferredProgram} 同款）。
     */
    private static volatile dev.vkdisp.pipeline.model.PackTerrainProgram terrainProgram;

    /**
     * GAP-003：包内资源（按包地形片元 varying 契约**生成**的顶点适配层）。
     *
     * <p>🔖 为什么是生成物而不是静态资产：实测 BSL 默认配置要 9 条 varying、开
     * {@code ADVANCED_MATERIALS} 后要 15 条；写死一份对另一个配置就是「少供」⇒
     * 驱动层在资源加载期抛 {@code ShaderCompileException: missing output at location 14}
     * ⇒ <b>客户端起不来</b>。详见 {@code glsl.translate.PackVertexAdapterGenerator}。
     */
    public static final String TERRAIN_ADAPTER_PATH = "shaders/terrain_pack_adapter.vsh";

    /** 全量资源 id：assets/vkdisp_pack/shaders/terrain_pack_adapter.vsh。 */
    private static final Identifier TERRAIN_ADAPTER_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, TERRAIN_ADAPTER_PATH);

    /** GAP-003：所选包的地形片元契约；{@code null} = 保持原版 core/terrain（不接线）。 */
    public static dev.vkdisp.pipeline.model.PackTerrainProgram terrainProgram() {
        return terrainProgram;
    }

    /**
     * GAP-003：地形片元里 VkDispBuiltins 块的 std140 布局（收编集与 composite/deferred **不同**，各解析各的）。
     *
     * <p>🔖 与前三套布局同一份理由（P4.1.3）：转译终稿 = 驱动编译的真源，Injector 内部的
     * 收编结果不外传 ⇒ 只能从终稿再解析一次。空布局 = 兜底/未接线 ⇒ 绑零填充缓冲。
     */
    private static volatile BuiltinsBlockLayout terrainBuiltinsLayout = BuiltinsBlockLayout.empty();

    /** 地形片元块布局（只读视图；空 = 零填充）。 */
    public static BuiltinsBlockLayout terrainBuiltinsLayout() {
        return terrainBuiltinsLayout;
    }

    /**
     * P3.3 链路开关：最近一次 {@code openResources} 生成时，所选包是否真实产出了 deferred 片元。
     *
     * <p>默认 false（资源加载前 / 兜底路径 / 总开关关闭均为 false → FrameApi 走 P3.2 直连基线）。
     * volatile：生成在资源加载线程写、渲染线程读（18-PARALLEL §5 P3.3 ④）。
     */
    private static volatile boolean hasDeferredProgram;

    /** P3.3：所选包是否声明并成功产出了 deferred 片元（FrameApi 链路判据，只读视图）。 */
    public static boolean hasDeferredProgram() {
        return hasDeferredProgram;
    }

    /**
     * P4.1.4 链路开关：最近一次 {@code openResources} 生成时，所选包是否真实产出了 final 片元。
     *
     * <p>默认 false（资源加载前 / 兜底路径 / 总开关关闭均为 false → FrameApi 保持
     * composite 直写主目标基线）。volatile：生成在资源加载线程写、渲染线程读（同
     * {@link #hasDeferredProgram}）。
     */
    private static volatile boolean hasFinalProgram;

    /** P4.1.4：所选包是否声明并成功产出了 final 片元（FrameApi 链路判据，只读视图）。 */
    public static boolean hasFinalProgram() {
        return hasFinalProgram;
    }

    /**
     * P4.1.3：composite 转译终稿的 VkDispBuiltins std140 布局（冷路径解析一次）。
     *
     * <p>块成员顺序 = 收编声明（源序）在前 + 目录缺失在后 → composite 与 deferred
     * **收编集不同 → 布局不同**，各自解析、各绑各的环形缓冲（04-SPEC §3.2 上传注记）。
     * volatile：生成在资源加载线程写、渲染线程读（与 {@link #hasDeferredProgram} 同款）。
     * 空布局（兜底 passthrough 无块 / 解析失败）= FrameApi 回退零填充。
     */
    private static volatile BuiltinsBlockLayout compositeBuiltinsLayout = BuiltinsBlockLayout.empty();

    /** P4.1.3：deferred 转译终稿的块布局（无 deferred 程序 = 空布局）。 */
    private static volatile BuiltinsBlockLayout deferredBuiltinsLayout = BuiltinsBlockLayout.empty();

    /** composite 块布局（FrameApi 只读视图；空 = 零填充）。 */
    public static BuiltinsBlockLayout compositeBuiltinsLayout() {
        return compositeBuiltinsLayout;
    }

    /** deferred 块布局（FrameApi 只读视图；空 = 零填充）。 */
    public static BuiltinsBlockLayout deferredBuiltinsLayout() {
        return deferredBuiltinsLayout;
    }

    /** P4.1.4：final 转译终稿的块布局（无 final 程序 = 空布局；三布局第三槽）。 */
    private static volatile BuiltinsBlockLayout finalBuiltinsLayout = BuiltinsBlockLayout.empty();

    /** final 块布局（FrameApi 只读视图；空 = 零填充 / 步未开）。 */
    public static BuiltinsBlockLayout finalBuiltinsLayout() {
        return finalBuiltinsLayout;
    }

    /**
     * 元数据段：兼容范围取到荒谬的宽（0..9999）——本包是自造必需品，不做版本猜测（X9）；
     * 兼容性判断实际由 {@link Pack.Metadata} 里显式写死的 {@code COMPATIBLE} 承担。
     */
    private static final PackMetadataSection PACK_METADATA = new PackMetadataSection(
            Component.literal("vkdisp generated composite source"),
            new InclusiveRange<>(new PackFormat(0, 0), new PackFormat(9999, 0)));

    private VkDispVirtualPack() {}

    /** 注册 RepositorySource（mod bus；与 FullscreenPipelineRegistrar 同款订阅写法）。 */
    @SubscribeEvent
    static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        try {
            event.addRepositorySource(VkDispVirtualPack::loadPacks);
            // 证据行：required=true → rebuildSelected 强制入选（字节码核实，18-PARALLEL §5 P2.4 ①）。
            VkDisp.LOGGER.info(
                    "vkdisp: virtual pack finder registered: id={} required=true position=TOP"
                            + " (forced into selection by rebuildSelected; options.resourcePacks untouched)",
                    NAMESPACE);
        } catch (Throwable t) {
            // 注册失败 = composite 管线找不到片元 = 启动必炸 → 必须显式 ERROR（T11）。
            VkDisp.LOGGER.error("vkdisp: virtual pack finder registration FAILED", t);
        }
    }

    /** {@code RepositorySource.loadPacks} 回调：每次资源仓库重载都产出一个新 Pack 实例。 */
    static void loadPacks(Consumer<Pack> consumer) {
        try {
            consumer.accept(createPack());
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: virtual pack creation FAILED", t);
        }
    }

    /**
     * 构造虚拟包。只在此处组装**静态**结构（元数据 / 选择配置）——
     * 不碰库存、不碰配置（注册时机早于配置加载，见类 javadoc 时序）。
     */
    static Pack createPack() {
        PackLocationInfo location = new PackLocationInfo(
                NAMESPACE,
                Component.literal("vkdisp pack (generated)"),
                PackSource.BUILT_IN,
                Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackMetadataResources openMetadata(PackLocationInfo loc) {
                // 元数据读取可能早于配置加载：返回静态兜底源，不做库存扫描。
                return new VirtualPackResources(loc, PackCompositeSource.FALLBACK_GLSL,
                        PackCompositeSource.FALLBACK_GLSL, PackCompositeSource.FALLBACK_GLSL);
            }

            @Override
            public Stream<PackResources> openResources(PackLocationInfo loc, Pack.Metadata meta) {
                // openAllSelected@3114：此刻配置已加载@3079 → 生成真正生效的源（P2.4 ③ 时机）。
                GeneratedSources sources = generateSources();
                return Stream.of(new VirtualPackResources(loc,
                        sources.composite(), sources.deferred(), sources.finalSource(),
                        sources.terrain(), sources.terrainAdapter()));
            }
        };
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("vkdisp generated composite source"),
                PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(),
                List.of());
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, false);
        return new Pack(location, supplier, metadata, selection);
    }

    /**
     * 一次生成的四个源（P3.3 deferred / P4.1.4 final / GAP-003 地形片元）。
     *
     * <p>🔖 {@code terrain} <b>允许为 null</b>，且这不是「失败」而是「按设计不接线」：
     * 前三个源服务 required 管线（缺失 = 启动失败），地形片元服务可选的派生 MRT 地形管线
     * （缺失 = 沿用原版 core/terrain，画面照常）。两者混成同一个兜底口径就会让
     * 「没找到地形片元」看起来像「找到了一个假的地形片元」。
     */
    private record GeneratedSources(String composite, String deferred, String finalSource, String terrain,
            String terrainAdapter) {

        /** 四源形态（地形源为 null = 不接线）。 */
        GeneratedSources(String composite, String deferred, String finalSource) {
            this(composite, deferred, finalSource, null, null);
        }
    }

    /**
     * 生成 composite + deferred + final 三片元源（{@link PackCompositeSource} 冷路径编排）。
     * 永不抛：任意失败 → ERROR 原文 + 内置 passthrough（required 管线必须总有源可编，T11）。
     * 每条路径都显式写 {@link #hasDeferredProgram} / {@link #hasFinalProgram}
     * （兜底/异常 = false → FrameApi 走基线：不开 deferred 链、composite 直写主目标）。
     */
    static GeneratedSources generateSources() {
        try {
            boolean enabled = VkDispConfig.ENABLED.get();
            String profile = VkDispConfig.PACK_PROFILE.get();
            // P4.2 切包（§6 四张截图法的驱动）："" = 自动扫描顺序 / "none" = 强制兜底 / 其它 = 精确包名。
            String selection = VkDispConfig.SHADER_PACK.get();
            if (!enabled) {
                hasDeferredProgram = false;
                hasFinalProgram = false;
                compositeBuiltinsLayout = BuiltinsBlockLayout.empty();
                deferredBuiltinsLayout = BuiltinsBlockLayout.empty();
                finalBuiltinsLayout = BuiltinsBlockLayout.empty();
                VkDisp.LOGGER.warn(
                        "vkdisp: composite source: mod disabled (vkdisp.enabled=false)"
                                + " -> built-in passthrough fallback");
                return new GeneratedSources(PackCompositeSource.FALLBACK_GLSL,
                        PackCompositeSource.FALLBACK_GLSL, PackCompositeSource.FALLBACK_GLSL);
            }
            Path inventory = inventoryDir();
            VkDisp.LOGGER.info(
                    "vkdisp: composite source generation start: profile='{}' selection='{}' inventory={}",
                    profile, selection, inventory);
            // P4.3：回放选项屏幕的持久化覆盖（文件不进 FML 监听 —— 每次生成读一次，冷路径）。
            PackOptionStore store = PackOptionStore.load(PackOptionStore.pathFor(gameDir()));
            if (!store.isEmpty()) {
                VkDisp.LOGGER.info(
                        "vkdisp: pack option overrides loaded: entries={} packs={} file={}",
                        store.size(), store.packNames(), PackOptionStore.FILE_NAME);
            }
            for (String warning : store.loadWarnings()) {
                VkDisp.LOGGER.warn("vkdisp: pack option store: {}", warning);
            }
            PackCompositeSource.Result result =
                    PackCompositeSource.generate(inventory, profile, selection, store);
            for (TranslateDiagnostic diagnostic : result.diagnostics()) {
                logDiagnostic(diagnostic);
            }
            // 证据行：A/B 对比时用（pack / profile / fallback / 源大小 / 诊断数）。
            VkDisp.LOGGER.info(
                    "vkdisp: composite source ready: fallback={} pack={} profile='{}'"
                            + " selection='{}' bytes={} diagnostics={}",
                    result.fallback(), result.packName(), result.profile(), selection,
                    result.source().getBytes(StandardCharsets.UTF_8).length,
                    result.diagnostics().size());
            hasDeferredProgram = result.hasDeferredProgram();
            // P3.3 证据行：deferred 步是否开（present=true 才会走 scene -> offscreen2 -> main 链）。
            VkDisp.LOGGER.info(
                    "vkdisp: deferred source ready: present={} pack={} bytes={}",
                    result.hasDeferredProgram(), result.packName(),
                    result.deferredSource().getBytes(StandardCharsets.UTF_8).length);
            hasFinalProgram = result.hasFinalProgram();
            // P4.1.4 证据行：final 步是否开（present=true 才会走 composite -> offscreen3 -> main 链）。
            VkDisp.LOGGER.info(
                    "vkdisp: final source ready: present={} pack={} bytes={}",
                    result.hasFinalProgram(), result.packName(),
                    result.finalSource().getBytes(StandardCharsets.UTF_8).length);
            // P4.1.3：从转译终稿解析 VkDispBuiltins 块布局（F3 冻结契约：Injector 内部
            // Result 不外传，终稿 = 驱动编译的真源）；各步收编集不同 → 各布局各环。
            compositeBuiltinsLayout = BuiltinsBlockLayout.parse(result.source());
            deferredBuiltinsLayout = result.hasDeferredProgram()
                    ? BuiltinsBlockLayout.parse(result.deferredSource())
                    : BuiltinsBlockLayout.empty();
            finalBuiltinsLayout = result.hasFinalProgram()
                    ? BuiltinsBlockLayout.parse(result.finalSource())
                    : BuiltinsBlockLayout.empty();
            logLayout("composite", compositeBuiltinsLayout);
            logLayout("deferred", deferredBuiltinsLayout);
            logLayout("final", finalBuiltinsLayout);
            // GAP-003：包地形片元契约（**独立**一条链，失败绝不影响上面三源）。
            // 之所以不并进 PackCompositeSource.generate：那是一条「必有源」的 required 管线链，
            // 它的兜底语义是 passthrough；而地形片的正确兜底是「不接线、用原版 core/terrain」。
            String terrainSource = takeTerrainSourceMemo();
            String terrainAdapter = takeTerrainAdapterMemo();
            if (terrainSource != null) {
                VkDisp.LOGGER.info("vkdisp: [GAP-003] terrain source reused from early contract"
                        + " (registration-time generation; no second compile)");
            } else {
                terrainSource = generateTerrainSource(inventory, profile, selection, store);
                terrainAdapter = takeTerrainAdapterMemo();
            }
            return new GeneratedSources(
                    result.source(), result.deferredSource(), result.finalSource(), terrainSource,
                    terrainAdapter);
        } catch (Throwable t) {
            hasDeferredProgram = false;
            hasFinalProgram = false;
            terrainProgram = null;
            terrainBuiltinsLayout = BuiltinsBlockLayout.empty();
            compositeBuiltinsLayout = BuiltinsBlockLayout.empty();
            deferredBuiltinsLayout = BuiltinsBlockLayout.empty();
            finalBuiltinsLayout = BuiltinsBlockLayout.empty();
            VkDisp.LOGGER.error("vkdisp: composite source generation FAILED (原文如下)"
                    + " -> built-in passthrough fallback", t);
            return new GeneratedSources(PackCompositeSource.FALLBACK_GLSL,
                    PackCompositeSource.FALLBACK_GLSL, PackCompositeSource.FALLBACK_GLSL);
        }
    }

    /**
     * GAP-003：选包地形片元并把契约落进 {@link #terrainProgram}，返回其源（{@code null} = 不接线）。
     *
     * <p><b>为什么必须独立 try/catch</b>：上面三源是 required 管线，崩了会砸启动；
     * 地形片元是可选接线，崩了只该退回原版 core/terrain。把两者放同一个 try 里，
     * 一个选择 bug 就会连带把 composite 也打成 passthrough（画面突变却报的是另一个原因）。
     */
    /**
     * GAP-003：<b>提前</b>生成地形片元契约，供<b>管线注册期</b>使用。
     *
     * <p><b>为什么必须提前</b>（本轮 runClient 实测，唯一的时序证据）：
     * 日志显示 RegisterRenderPipelinesEvent 在 <b>08:31:49.70</b> 触发，
     * 而虚拟包 openResources 生成包源在 <b>08:31:54.21</b> ——
     * 注册比生成<b>早约 4.5 秒</b>；且资源重载（切包）时该事件<b>不再触发</b>
     * （RenderPipelines 类只初始化一次）⇒ 「等生成完再注册」这条路在原版上根本不存在。
     * 若不提前，注册期取不到契约 ⇒ 派生 MRT 地形管线永远用原版 core/terrain，
     * 而症状只是「开了开关没效果」，不报错（典型的静默失效）。
     *
     * <p><b>提前的代价与出口</b>：这是一次冷路径编译（约 0.7–3 秒，见 b4 埋点），
     * 发生在启动期管线注册处。PackCompileCache 让随后的 openResources 命中缓存，不重复付费。
     *
     * <p><b>为什么用键做记忆</b>：键 = profile|selection。配置热加载切包后键变化 ⇒ 重新生成；
     * 同一键重复调用直接复用（注册与 openResources 各调一次，只付一次钱）。
     */
    public static synchronized void ensureTerrainProgram() {
        String profile = VkDispConfig.PACK_PROFILE.get();
        String selection = VkDispConfig.SHADER_PACK.get();
        String key = profile + "|" + selection;
        if (key.equals(terrainMemoKey)) {
            return;
        }
        terrainMemoKey = key;
        terrainSourceMemo = null;
        if (!VkDispConfig.ENABLED.get() || !VkDispConfig.MRT_PACK_TERRAIN_SHADER.get()) {
            // 总开关或本开关关掉时不提前编译（默认路径零额外冷路径开销，支柱③ B3/B4）。
            return;
        }
        long started = System.nanoTime();
        terrainSourceMemo = generateTerrainSource(inventoryDir(), profile, selection,
                PackOptionStore.load(PackOptionStore.pathFor(gameDir())));
        VkDisp.LOGGER.info("vkdisp: [GAP-003] early terrain contract ready in {} ms (key={})",
                (System.nanoTime() - started) / 1_000_000L, key);
    }

    /** 地形契约的记忆键（profile|selection）；null = 尚未生成过。 */
    private static String terrainMemoKey;

    /** 提前生成时的源文本（openResources 直接复用，不重编）。 */
    private static String terrainSourceMemo;

    /** 与地形源同批生成的适配层源；{@code null} = 不接线。 */
    private static String terrainAdapterMemo;

    /**
     * 取走提前生成的源（{@code openResources} 用；无缓存返回 {@code null}）。
     *
     * <p>取走即清空 + 清键：这样下一次 {@link #ensureTerrainProgram} 看到「键为空」会重算，
     * 不会拿一份**上一轮**的契约去注册新一轮的管线（切包后拿到旧包片元 = 画面错且难归因）。
     */
    /**
     * 按地形片元的 varying 契约生成顶点适配层（失败 → {@code null} = 不接线）。
     *
     * <p>🔖 与 {@link #takeTerrainSourceMemo()} 同批取走：两者要么都给、要么都不给，
     * 免得出现「片元是包的、顶点还是原版」的半接线状态（那正是链接失败的直接来源）。
     */
    private static String generateTerrainAdapter(dev.vkdisp.pipeline.model.PackTerrainProgram program) {
        try {
            dev.vkdisp.glsl.translate.PackVertexAdapterGenerator.Result adapter =
                    dev.vkdisp.glsl.translate.PackVertexAdapterGenerator.generate(
                            program.inputs(), VkDispConfig.MRT_TERRAIN_FULL_LIGHT_PROBE.get(),
                            VkDispConfig.MRT_TERRAIN_PARALLAX_SKIP_PROBE.get(),
                            VkDispConfig.MRT_TERRAIN_COLOR_PROBE.get());
            for (TranslateDiagnostic diagnostic : adapter.diagnostics()) {
                logDiagnostic(diagnostic);
            }
            return adapter.glsl();
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-003] 顶点适配层生成 FAILED（原文如下）"
                    + " -> 地形片元不接线（沿用原版 core/terrain）", t);
            return null;
        }
    }

    private static String takeTerrainSourceMemo() {
        String memo = terrainSourceMemo;
        terrainSourceMemo = null;
        terrainAdapterMemo = null;
        terrainMemoKey = null;
        return memo;
    }

    /** 取走提前生成的适配层源（无缓存返回 {@code null}）。 */
    private static String takeTerrainAdapterMemo() {
        String memo = terrainAdapterMemo;
        terrainAdapterMemo = null;
        return memo;
    }

    private static String generateTerrainSource(Path inventory, String profile, String selection,
            PackOptionStore store) {
        terrainProgram = null;
        // U0001f534 派生导数探针是**转译段级**的总闸（dcdx/dcdy 声明在片元里，顶点侧够不着）。
        //   只在生成地形源这段窗口里打开，并在 finally 复位：
        //   否则合成/延迟/最终四个程序的转译也会被它波及（它们可能也声明 dcdx）。
        boolean probeWas = dev.vkdisp.glsl.translate.DerivativeProbeAdapter.enabled();
        dev.vkdisp.glsl.translate.DerivativeProbeAdapter.setEnabled(
                VkDispConfig.MRT_TERRAIN_DERIVATIVE_PROBE.get());
        try {
            dev.vkdisp.pack.PackTerrainSource.Result terrain =
                    dev.vkdisp.pack.PackTerrainSource.generate(inventory, profile, selection, store);
            for (TranslateDiagnostic diagnostic : terrain.diagnostics()) {
                logDiagnostic(diagnostic);
            }
            if (!terrain.wired()) {
                terrainBuiltinsLayout = BuiltinsBlockLayout.empty();
                VkDisp.LOGGER.warn("vkdisp: [GAP-003] pack terrain fragment NOT wired"
                        + " -> derived MRT terrain pipeline keeps vanilla core/terrain");
                return null;
            }
            dev.vkdisp.pipeline.model.PackTerrainProgram program = terrain.program();
            terrainProgram = program;
            terrainAdapterMemo = generateTerrainAdapter(program);
            terrainBuiltinsLayout = BuiltinsBlockLayout.parse(program.fragmentSource());
            logLayout("terrain", terrainBuiltinsLayout);
            VkDisp.LOGGER.info(
                    "vkdisp: [GAP-003] pack terrain fragment ready: program={} outputs={} samplers={}"
                            + " varyings={} bytes={}",
                    program.qualifiedName(), program.outputCount(),
                    program.fragmentSamplers().size(), program.inputs().size(),
                    program.fragmentSource().getBytes(StandardCharsets.UTF_8).length);
            return program.fragmentSource();
        } catch (Throwable t) {
            terrainProgram = null;
            terrainBuiltinsLayout = BuiltinsBlockLayout.empty();
            VkDisp.LOGGER.error("vkdisp: [GAP-003] pack terrain fragment selection FAILED (原文如下)"
                    + " -> derived MRT terrain pipeline keeps vanilla core/terrain", t);
            return null;
        } finally {
            // U0001f534 必须在 finally 复位：探针是全局静态闸，漏复位会让后续
            //   合成/延迟/最终四个程序的转译也被改写（它们可能也声明 dcdx）。
            //   漏复位的症状是「开了诊断开关之后别的画面也变了」，极难归因。
            dev.vkdisp.glsl.translate.DerivativeProbeAdapter.setEnabled(probeWas);
        }
    }

    /**
     * P4.5：<b>后台线程</b>预编译 —— 把秒级冷路径编译移出渲染线程。
     *
     * <p>本方法只做「纯文件 IO + 纯 Java 转译」：读包、跑 {@link PackCompositeSource#generate}
     * 走一遍（产物落进 {@link PackCompileCache}）。<b>刻意不做</b>：
     * <ul>
     *   <li>不写 {@link #hasDeferredProgram} / {@link #hasFinalProgram} 等渲染线程状态
     *       ——那些由随后的同步 {@link #generateSources()} 设置，避免跨线程可见性问题；</li>
     *   <li>不解析布局、不打布局证据行 —— 同上，留给同步路径；</li>
     *   <li>不碰任何 GL / 渲染资源（编译 SPIR-V 需要设备上下文，留在同步路径）。</li>
     * </ul>
     *
     * <p><b>失败语义</b>：任何异常 → ERROR 原文 + 不写缓存。真加载时 {@code openResources}
     * 会同步重编一次并给出同样的诊断（T11：加速器故障不等于功能不可用）。
     */
    static void precompile(PackPrecompileScheduler.Target target) {
        if (target == null) {
            return;
        }
        try {
            Path inventory = inventoryDir();
            if (inventory == null) {
                VkDisp.LOGGER.warn("vkdisp: precompile skipped (game directory unavailable)");
                return;
            }
            // 选项差分表与同步路径同源：读同一个存储文件（只读，构造时一次完成）
            PackOptionStore store = PackOptionStore.load(PackOptionStore.pathFor(gameDir()));
            PackCompositeSource.Result result =
                    PackCompositeSource.generate(inventory, target.profile(), target.selection(), store);
            VkDisp.LOGGER.info(
                    "vkdisp: pack precompile finished: pack={} fallback={} bytes={} diagnostics={}"
                            + " (cache entries={})",
                    result.packName(), result.fallback(),
                    result.source().getBytes(StandardCharsets.UTF_8).length,
                    result.diagnostics().size(), PackCompileCache.size());
        } catch (Throwable t) {
            // 不抛给后台线程（否则调度器状态卡在 COMPILING）；不阻断切换。
            VkDisp.LOGGER.error("vkdisp: pack precompile failed (will retry synchronously on reload)", t);
        }
    }

    /**
     * P4.1.3 布局证据行（每轮 generateSources 一条）：成员数 / 块字节数；
     * 解析失败按 WARN 原文打出（T11）—— FrameApi 侧回退零填充。
     */
    private static void logLayout(String slot, BuiltinsBlockLayout layout) {
        if (layout.failure() != null) {
            VkDisp.LOGGER.warn(
                    "vkdisp: builtins layout FAILED (zero-fill fallback): slot={} reason={}",
                    slot, layout.failure());
            return;
        }
        VkDisp.LOGGER.info(
                "vkdisp: builtins layout parsed: slot={} members={} bytes={}",
                slot, layout.members().size(), layout.byteSize());
    }

    /**
     * 游戏根目录；客户端未就绪返回 null（选项存储与库存目录都退化为"无"）。
     *
     * <p><b>线程</b>：{@code Minecraft.getInstance()} 在主线程创建后对其它线程可见，
     * 读 {@code gameDirectory}（一个普通 {@link java.io.File} 字段）本身是安全的。
     * P4.5 的后台预编译线程也走这里—— 故此处<b>不</b>做渲染线程断言（断言会在预编译时误报）。
     */
    private static Path gameDir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath();
    }

    /** 库存目录（{@code <gameDir>/shaderpacks}）；不可用返回 null（扫描器会产出显式诊断）。 */
    private static Path inventoryDir() {
        Path gameDir = gameDir();
        return gameDir == null ? null : gameDir.resolve("shaderpacks");
    }

    /** 诊断按原 severity 分流（与 VkDispPackScan 同口径）。 */
    private static void logDiagnostic(TranslateDiagnostic diagnostic) {
        switch (diagnostic.severity()) {
            case ERROR -> VkDisp.LOGGER.error("vkdisp: composite source diagnostic: {}", diagnostic.format());
            case WARN -> VkDisp.LOGGER.warn("vkdisp: composite source diagnostic: {}", diagnostic.format());
            case INFO -> VkDisp.LOGGER.info("vkdisp: composite source diagnostic: {}", diagnostic.format());
        }
    }

    /**
     * 内存 {@link PackResources}：服务三个资源（{@code shaders/composite.fsh} +
     * {@code shaders/deferred.fsh} + {@code shaders/final.fsh}，P3.3/P4.1.4 三管线三片元）。
     *
     * <p>接口清单经 javap 核实：{@link PackResources} 三个抽象方法 + 继承自
     * {@code PackMetadataResources} 的 {@code location/getRootResource/getMetadataSection/close}
     * （NeoForge 扩展 {@code isHidden()} 有默认实现，不必覆写）。
     */
    static final class VirtualPackResources implements PackResources {

        private final PackLocationInfo location;
        private final byte[] compositeBytes;
        private final byte[] deferredBytes;
        private final byte[] finalBytes;
        /** GAP-003 地形片元字节；{@code null} = 不接线（此时本资源**不存在**，见 getResource）。 */
        private final byte[] terrainBytes;

        /** GAP-003 顶点适配层字节；与 {@link #terrainBytes} 同生共死（半接线 = 链接失败）。 */
        private final byte[] terrainAdapterBytes;

        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource) {
            this(location, compositeSource, deferredSource, finalSource, null, null);
        }

        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource, String terrainSource,
                String terrainAdapterSource) {
            this.location = location;
            this.compositeBytes = compositeSource.getBytes(StandardCharsets.UTF_8);
            this.deferredBytes = deferredSource.getBytes(StandardCharsets.UTF_8);
            this.finalBytes = finalSource.getBytes(StandardCharsets.UTF_8);
            this.terrainBytes = terrainSource == null ? null : terrainSource.getBytes(StandardCharsets.UTF_8);
            this.terrainAdapterBytes = terrainAdapterSource == null ? null
                    : terrainAdapterSource.getBytes(StandardCharsets.UTF_8);
        }
        @Override
        public PackLocationInfo location() {
            return location;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }
            if (COMPOSITE_ID.equals(id)) {
                return () -> new ByteArrayInputStream(compositeBytes);
            }
            if (DEFERRED_ID.equals(id)) {
                return () -> new ByteArrayInputStream(deferredBytes);
            }
            if (FINAL_ID.equals(id)) {
                return () -> new ByteArrayInputStream(finalBytes);
            }
            // 🔖 地形片元「不接线」时**返回 null**（= 资源不存在），而不是返回兜底文本：
            //   此刻没有任何管线引用 vkdisp_pack:gbuffers_terrain，注册路径不会去取它；
            //   万一有人后来引用了却拿到兜底，症状会变成「用的是内建 passthrough 而不是原版地形」
            //   —— 比「资源缺失」更难归因。
            if (TERRAIN_ID.equals(id) && terrainBytes != null) {
                return () -> new ByteArrayInputStream(terrainBytes);
            }
            if (TERRAIN_ADAPTER_ID.equals(id) && terrainAdapterBytes != null) {
                return () -> new ByteArrayInputStream(terrainAdapterBytes);
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !NAMESPACE.equals(namespace)) {
                return;
            }
            // path 形态：""（全量）/ "shaders" / "shaders/composite.fsh" —— 前缀匹配即可。
            String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            if (normalized.isEmpty()
                    || COMPOSITE_PATH.equals(normalized)
                    || COMPOSITE_PATH.startsWith(normalized + "/")) {
                output.accept(COMPOSITE_ID, () -> new ByteArrayInputStream(compositeBytes));
            }
            if (normalized.isEmpty()
                    || DEFERRED_PATH.equals(normalized)
                    || DEFERRED_PATH.startsWith(normalized + "/")) {
                output.accept(DEFERRED_ID, () -> new ByteArrayInputStream(deferredBytes));
            }
            if (normalized.isEmpty()
                    || FINAL_PATH.equals(normalized)
                    || FINAL_PATH.startsWith(normalized + "/")) {
                output.accept(FINAL_ID, () -> new ByteArrayInputStream(finalBytes));
            }
            if (terrainBytes != null && (normalized.isEmpty()
                    || TERRAIN_PATH.equals(normalized)
                    || TERRAIN_PATH.startsWith(normalized + "/"))) {
                output.accept(TERRAIN_ID, () -> new ByteArrayInputStream(terrainBytes));
            }
            if (terrainAdapterBytes != null && (normalized.isEmpty()
                    || TERRAIN_ADAPTER_PATH.equals(normalized)
                    || TERRAIN_ADAPTER_PATH.startsWith(normalized + "/"))) {
                output.accept(TERRAIN_ADAPTER_ID,
                        () -> new ByteArrayInputStream(terrainAdapterBytes));
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Set.of(NAMESPACE) : Set.of();
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... paths) {
            // pack.mcmeta / pack.png 这类根资源本包没有（元数据走构造器，不经文件）。
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
            if (type == PackMetadataSection.CLIENT_TYPE
                    || type == PackMetadataSection.SERVER_TYPE
                    || type == PackMetadataSection.FALLBACK_TYPE) {
                return (T) PACK_METADATA;
            }
            return null;
        }

        @Override
        public void close() {
            // 内存实现，无句柄可关。
        }
    }
}
