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

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.pack.PackCompositeSource;
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
                        sources.composite(), sources.deferred(), sources.finalSource()));
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
     * 一次生成的三源（P3.3 deferred / P4.1.4 final 开关
     * {@link #hasDeferredProgram} / {@link #hasFinalProgram} 随生成同步落盘）。
     */
    private record GeneratedSources(String composite, String deferred, String finalSource) {}

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
            PackCompositeSource.Result result = PackCompositeSource.generate(inventory, profile, selection);
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
            return new GeneratedSources(
                    result.source(), result.deferredSource(), result.finalSource());
        } catch (Throwable t) {
            hasDeferredProgram = false;
            hasFinalProgram = false;
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

    /** 库存目录（{@code <gameDir>/shaderpacks}）；不可用返回 null（扫描器会产出显式诊断）。 */
    private static Path inventoryDir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath().resolve("shaderpacks");
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

        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource) {
            this.location = location;
            this.compositeBytes = compositeSource.getBytes(StandardCharsets.UTF_8);
            this.deferredBytes = deferredSource.getBytes(StandardCharsets.UTF_8);
            this.finalBytes = finalSource.getBytes(StandardCharsets.UTF_8);
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
