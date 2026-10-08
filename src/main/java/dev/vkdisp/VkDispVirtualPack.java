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
import dev.vkdisp.pipeline.model.GbufferProgramPlan;
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
     * 🔴 <b>GAP-027：gbuffer 程序表</b> —— 「有哪几条包 {@code gbuffers_*} 程序被接进 MRT pass」的
     * <b>唯一真源</b>。
     *
     * <p><b>为什么是一张表而不是十个硬编码点</b>（本轮的收口点）：GAP-003 之前，
     * 「地形」这一条程序的名字散在<b>路径 / 资源 id / 契约位 / 块布局 / 记忆键 / 取走校验 /
     * 生成链 / 片元资源服务</b>各处；接第二条程序（水）时若照着复制一遍，
     * 就得到两套会各自漂移的状态（本项目反复吃过：「造键用 A、校验用 B」「附件按新契约、
     * 槽位按旧契约」，X42）。现在<b>每一条程序只有一个 {@link GbufferArtifacts} 实例</b>，
     * 路径与 id 都是程序名的纯函数（{@code pipeline.model.GbufferProgramPlan}），
     * 记忆键 / 取走校验 / 生成链 / 资源服务全部按名字索引。
     *
     * <p>🔖 表<b>顺序</b>有语义：生成顺序 = 冻结顺序 = 自报顺序（{@code terrain} 在前，
     * 与 GAP-003 的历史日志口径逐字一致）。
     */
    static final class GbufferArtifacts {

        /** 包里的程序名（= 表的键，如 {@code gbuffers_terrain} / {@code gbuffers_water}）。 */
        final String program;

        /** 片元源在包内的路径（由程序名派生）。 */
        final String fragmentPath;

        /** 顶点适配层源在包内的路径（由程序名派生；<b>逐条程序各一份</b>，绝不复用）。 */
        final String adapterPath;

        /** 片元的资源 id（{@code assets/vkdisp_pack/<fragmentPath>}）。 */
        final Identifier fragmentResourceId;

        /** 适配层的资源 id（{@code assets/vkdisp_pack/<adapterPath>}）。 */
        final Identifier adapterResourceId;

        /** 片元的<b>管线侧</b>着色器 id（FileToIdConverter 分别解析到 .fsh/.vsh）。 */
        final Identifier fragmentShaderId;

        /** 适配层的管线侧着色器 id。 */
        final Identifier adapterShaderId;

        /**
         * 本条程序「生成期该不该跑」的闸门（各条各有自己的配置键）。
         *
         * <p>🔴 闸门读的<b>每一个</b>配置项都必须出现在
         * {@link VkDispVirtualPack#currentTerrainMemoKey()} 里 —— 由
         * {@code GenerationTimeSwitchInventoryTest} 当场兜住（QD-08 那一族已发生四次）。
         */
        final java.util.function.BooleanSupplier generationGate;

        /** 冻结的渲染契约（{@code null} = 本条不接线；volatile：资源线程写、渲染线程读）。 */
        volatile dev.vkdisp.pipeline.model.PackTerrainProgram contract;

        /** 本条程序片元里 VkDispBuiltins 块的 std140 布局（<b>逐条各解析各的</b>，X39）。 */
        volatile BuiltinsBlockLayout builtinsLayout = BuiltinsBlockLayout.empty();

        /** 记忆键（{@code null} = 尚未生成过）；键 = 基础键 + 程序名，见 {@link #memoKeyFor}。 */
        volatile String memoKey;

        /** 提前生成的片元源（{@code openResources} 直接复用，不重编）。 */
        volatile String sourceMemo;

        /** 与片元源<b>同批</b>生成的适配层源；{@code null} = 不接线（半接线 = 链接失败）。 */
        volatile String adapterMemo;

        GbufferArtifacts(String program, java.util.function.BooleanSupplier generationGate) {
            this.program = program;
            this.generationGate = generationGate;
            this.fragmentPath = dev.vkdisp.pipeline.model.GbufferProgramPlan.fragmentPath(program);
            this.adapterPath = dev.vkdisp.pipeline.model.GbufferProgramPlan.adapterPath(program);
            this.fragmentResourceId = Identifier.fromNamespaceAndPath(NAMESPACE, this.fragmentPath);
            this.adapterResourceId = Identifier.fromNamespaceAndPath(NAMESPACE, this.adapterPath);
            this.fragmentShaderId = Identifier.fromNamespaceAndPath(NAMESPACE,
                    dev.vkdisp.pipeline.model.GbufferProgramPlan.fragmentShaderPath(program));
            this.adapterShaderId = Identifier.fromNamespaceAndPath(NAMESPACE,
                    dev.vkdisp.pipeline.model.GbufferProgramPlan.adapterShaderPath(program));
        }

        /** 本条程序在当前配置下的记忆键（<b>造键与校验同一份算法</b>，X42）。 */
        String memoKeyFor() {
            return currentTerrainMemoKey() + "|" + program;
        }

        /** 是否接线（契约在且输出数 &gt; 0）。 */
        boolean wired() {
            dev.vkdisp.pipeline.model.PackTerrainProgram current = contract;
            return current != null && current.outputCount() > 0;
        }
    }

    /** 表（顺序 = 生成 / 冻结 / 自报顺序）。 */
    private static final java.util.LinkedHashMap<String, GbufferArtifacts> GBUFFER_PROGRAMS =
            buildGbufferPrograms();

    private static java.util.LinkedHashMap<String, GbufferArtifacts> buildGbufferPrograms() {
        java.util.LinkedHashMap<String, GbufferArtifacts> table = new java.util.LinkedHashMap<>();
        // GAP-003：地形（既有那条，闸门 = mrt.packTerrainShader）。
        table.put(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM, new GbufferArtifacts(
                dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM,
                () -> VkDispConfig.MRT_PACK_TERRAIN_SHADER.get()));
        // GAP-027：水（默认关，闸门 = mrt.packWater）。
        table.put(dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM, new GbufferArtifacts(
                dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM,
                () -> VkDispConfig.MRT_PACK_WATER_SHADER.get()));
        return table;
    }

    /**
     * GAP-003：包内资源（包自己的地形片元，相对 assets/ 的路径）。
     *
     * <p>🔖 <b>与前三者语义不同</b>：composite / deferred / final 是 <b>required 管线</b>，
     * 源必须永远存在（缺失会砸启动）；gbuffer 片元是<b>可选接线</b> ——
     * 没有就沿用原版 {@code core/terrain}，所以本条程序的产物<b>允许为 null</b>，
     * 且 null 时本资源<b>根本不提供</b>（没有任何管线引用它）。
     *
     * <p>🔖 GAP-027 之后它是<b>表里地形那一条的视图</b>（保留常量是为了日志对账与既有引用，
     * 值由 {@code GbufferProgramPlan.fragmentPath} 派生，不再手写第二遍）。
     */
    public static final String TERRAIN_PATH =
            dev.vkdisp.pipeline.model.GbufferProgramPlan.fragmentPath(
                    dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);

    /** 全量资源 id：assets/vkdisp_pack/shaders/gbuffers_terrain.fsh。 */
    private static final Identifier TERRAIN_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, TERRAIN_PATH);

    /**
     * GAP-003：包内资源（按包地形片元 varying 契约**生成**的顶点适配层）。
     *
     * <p>🔖 为什么是生成物而不是静态资产：实测 BSL 默认配置要 9 条 varying、开
     * {@code ADVANCED_MATERIALS} 后要 15 条；写死一份对另一个配置就是「少供」⇒
     * 驱动层在资源加载期抛 {@code ShaderCompileException: missing output at location 14}
     * ⇒ <b>客户端起不来</b>。详见 {@code glsl.translate.PackVertexAdapterGenerator}。
     * 🔴 并且<b>逐条程序各一份</b>：水的 14 条与地形的 9 条不是同一个签名（GAP-027）。
     */
    public static final String TERRAIN_ADAPTER_PATH =
            dev.vkdisp.pipeline.model.GbufferProgramPlan.adapterPath(
                    dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);

    /** 全量资源 id：assets/vkdisp_pack/shaders/terrain_pack_adapter.vsh。 */
    private static final Identifier TERRAIN_ADAPTER_ID =
            Identifier.fromNamespaceAndPath(NAMESPACE, TERRAIN_ADAPTER_PATH);

    // ────────────────────────────────────────────────────────────────────────────────
    // 🔴 通用后处理链（deferred*/composite*/final 全链）：16 个**固定槽位**片元资源。
    //   管线在启动期一次性注册（注册事件资源重载不再触发，见 ensureTerrainProgram 的时序证据），
    //   「槽 k ↔ 链里第 k 个程序」的映射在每次 openResources 重写这些源 —— 短链的尾部槽
    //   落内置 passthrough（required 编译恒成立；执行期按链长跳过，不会画它）。
    // ────────────────────────────────────────────────────────────────────────────────

    /** 后处理槽位数（与 {@link dev.vkdisp.bridge.PipelineApi#MAX_POST_PASSES} 同值，单点在 PackPostChain）。 */
    public static final int POST_SLOT_COUNT = dev.vkdisp.pack.PackPostChain.MAX_POST_PASSES;

    /** 第 k 个后处理槽的包内路径。 */
    public static String postPath(int slot) {
        return "shaders/post" + slot + ".fsh";
    }

    /** 第 k 个后处理槽的全量资源 id（片元）。 */
    public static Identifier postId(int slot) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, postPath(slot));
    }

    /** 第 k 个后处理槽的全量资源 id（VS 适配层）。 */
    public static Identifier postVshId(int slot) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, "shaders/post" + slot + ".vsh");
    }

    /** 第 k 个后处理槽的管线侧着色器 id（FileToIdConverter 会分别解析到 .vsh/.fsh）。 */
    public static Identifier postShaderId(int slot) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, "post" + slot);
    }

    /** 最近一次生成的链（空链 = 包没有可进链的后处理程序，FrameApi 走旧三步）。 */
    private static volatile dev.vkdisp.pack.PackPostChain.Chain postChain
            = dev.vkdisp.pack.PackPostChain.Chain.EMPTY;

    /** 链的只读视图（FrameApi 执行期消费；volatile 同 {@link #terrainProgram} 口径）。 */
    public static dev.vkdisp.pack.PackPostChain.Chain postChain() {
        return postChain;
    }

    /** 第 slot 个后处理槽的 VkDispBuiltins 布局（短链尾部/兜底 = 空布局 → 零填充基线）。 */
    private static final BuiltinsBlockLayout[] POST_BUILTINS_LAYOUTS =
            new BuiltinsBlockLayout[POST_SLOT_COUNT];

    static {
        for (int i = 0; i < POST_SLOT_COUNT; i++) {
            POST_BUILTINS_LAYOUTS[i] = BuiltinsBlockLayout.empty();
        }
    }

    /** 第 slot 槽布局（FrameApi 只读视图）。 */
    public static BuiltinsBlockLayout postBuiltinsLayout(int slot) {
        return POST_BUILTINS_LAYOUTS[slot];
    }

    /**
     * GAP-003：所选包的地形片元契约；{@code null} = 保持原版 core/terrain（不接线）。
     *
     * <p>🔖 <b>为什么是契约对象而不是裸字符串</b>：管线注册要用它的输出数、绑定组要用它的
     * sampler 名、顶点适配层要用它的 varying 签名 —— 三处都从同一个对象读，才不会出现
     * 「附件数 3、颜色目标 1」那种<b>崩客户端</b>的错配（X42）。
     * 🔴 GAP-027 之后它是<b>表里地形那一条</b>的视图（{@link #packContract(String)}），
     * 存储只有一个：{@code GbufferArtifacts.contract}（volatile：资源线程写、渲染线程读）。
     */
    public static dev.vkdisp.pipeline.model.PackTerrainProgram terrainProgram() {
        return packContract(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
    }

    /** GAP-027：所选包的水片元契约；{@code null} = 水不接线（TRANSLUCENT 层保持地形/原版）。 */
    public static dev.vkdisp.pipeline.model.PackTerrainProgram waterProgram() {
        return packContract(dev.vkdisp.pack.PackTerrainSource.WATER_PROGRAM);
    }

    /**
     * 表里某条程序的契约（{@code null} = 这条不接线）；名字不在表里时<b>显式报错</b>并按不接线处理
     * （不猜：拼错程序名的后果是「接了个不存在的东西」，本项目已为此立过红灯）。
     */
    public static dev.vkdisp.pipeline.model.PackTerrainProgram packContract(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        if (entry == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] 请求了不在 gbuffer 程序表里的程序 '{}'（表={}）"
                    + " -> 按**不接线**处理（绝不拿别条程序的契约凑数，X39）", program, GBUFFER_PROGRAMS.keySet());
            return null;
        }
        return entry.contract;
    }

    /** 表里所有程序名（顺序 = 生成 / 冻结 / 自报顺序）。 */
    public static java.util.List<String> gbufferProgramNames() {
        return java.util.List.copyOf(GBUFFER_PROGRAMS.keySet());
    }

    /** 管线的片元着色器 id（由程序名派生）；名字不在表里返回 {@code null} + ERROR（不猜）。 */
    public static Identifier packFragmentShaderId(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        if (entry == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] packFragmentShaderId('{}') 不在程序表里 -> null", program);
            return null;
        }
        return entry.fragmentShaderId;
    }

    /** 管线的顶点适配层着色器 id（由程序名派生，<b>逐条程序各一份</b>）。 */
    public static Identifier packAdapterShaderId(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        if (entry == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] packAdapterShaderId('{}') 不在程序表里 -> null", program);
            return null;
        }
        return entry.adapterShaderId;
    }

    /**
     * 某条程序片元里 VkDispBuiltins 块的 std140 布局（<b>逐条各解析各的</b>，X39）。
     *
     * <p>🔖 与前三套布局同一份理由（P4.1.3）：转译终稿 = 驱动编译的真源，Injector 内部的
     * 收编结果不外传 ⇒ 只能从终稿再解析一次。空布局 = 兜底/未接线 ⇒ 绑零填充缓冲。
     */
    public static BuiltinsBlockLayout packBuiltinsLayout(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        return entry == null ? BuiltinsBlockLayout.empty() : entry.builtinsLayout;
    }

    /** 地形片元块布局（只读视图；空 = 零填充）。 */
    public static BuiltinsBlockLayout terrainBuiltinsLayout() {
        return packBuiltinsLayout(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
    }

    /** 表里某条程序当前的产物快照（bridge 侧只读；{@code null} = 程序名不在表里）。 */
    static GbufferArtifacts gbufferEntry(String program) {
        return GBUFFER_PROGRAMS.get(program);
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
                        sources.gbufferFragments(), sources.gbufferAdapters(), sources.postSources(),
                        sources.postVertexSources()));
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
     * 一次生成的全部源（P3.3 deferred / P4.1.4 final / GAP-003 地形片元 / GAP-027 逐程序 gbuffer 片元表）。
     *
     * <p>🔖 {@code gbufferFragments} 与 {@code gbufferAdapters} 里的值<b>允许为 null</b>，
     * 且这不是「失败」而是「按设计不接线」：前三者服务 required 管线（缺失 = 启动失败），
     * gbuffer 程序服务可选的派生 MRT 管线（缺失 = 沿用原版 core/terrain，画面照常）。
     * 两者混成同一个兜底口径就会让「没找到水的片元」看起来像「找到了一个假的水片元」。
     *
     * <p>🔴 两个表的<b>键</b>都是程序名，且「片元有、适配层没有」这种半接线状态由
     * {@link #putGbuffer} 一处保证不会出现（半接线 = 驱动层链接失败，GAP-010 同族）。
     */
    private record GeneratedSources(String composite, String deferred, String finalSource,
            java.util.Map<String, String> gbufferFragments,
            java.util.Map<String, String> gbufferAdapters,
            String[] postSources, String[] postVertexSources) {

        /** 四源形态（一条 gbuffer 程序都不接；post 全兜底）。 */
        GeneratedSources(String composite, String deferred, String finalSource) {
            this(composite, deferred, finalSource, java.util.Map.of(), java.util.Map.of(),
                    fallbackPostSources(), fallbackPostVertices());
        }

        /**
         * 逐程序落源（<b>同生共死</b>）：片元为 null 时适配层一定也不进表。
         *
         * <p>🔖 之所以做成一个方法而不是两个 put：两处分开写，早晚会有一处忘了配平，
         * 而症状是「片元是包的、顶点还是原版」= 直接链接失败（GAP-010 实测过的形状）。
         */
        static void putGbuffer(java.util.Map<String, String> fragments,
                java.util.Map<String, String> adapters, String program,
                String fragmentSource, String adapterSource) {
            if (fragmentSource == null || adapterSource == null) {
                return;
            }
            fragments.put(program, fragmentSource);
            adapters.put(program, adapterSource);
        }

        /** 全兜底的 post 槽源数组（长度 = {@link #POST_SLOT_COUNT}）。 */
        static String[] fallbackPostSources() {
            String[] out = new String[POST_SLOT_COUNT];
            java.util.Arrays.fill(out, PackCompositeSource.FALLBACK_GLSL);
            return out;
        }

        /** 兜底 post VS（无输入契约 = 只有 vUv 的全屏三角形）。 */
        static String[] fallbackPostVertices() {
            String vsh = dev.vkdisp.glsl.translate.PackPostVertexAdapter
                    .generate(java.util.List.of()).glsl();
            String[] out = new String[POST_SLOT_COUNT];
            java.util.Arrays.fill(out, vsh);
            return out;
        }

        /** 由链生成 post 槽源（尾部槽 = passthrough 兜底）。 */
        static String[] postSourcesFrom(dev.vkdisp.pack.PackPostChain.Chain chain) {
            String[] out = fallbackPostSources();
            java.util.Arrays.setAll(out, i -> i < chain.passes().size()
                    ? chain.passes().get(i).renumberedSource() : PackCompositeSource.FALLBACK_GLSL);
            return out;
        }

        /** 由链生成 post 槽的 **VS 适配层**（h46：片元声明了第 4 条输入而 fullscreen.vsh 只有
         *  0..2 ⇒ required 管线链接失败会砸整次资源重载 —— 按契约逐 location 生成）。 */
        static String[] postVerticesFrom(dev.vkdisp.pack.PackPostChain.Chain chain) {
            String[] out = fallbackPostVertices();
            for (int i = 0; i < chain.passes().size() && i < POST_SLOT_COUNT; i++) {
                out[i] = dev.vkdisp.glsl.translate.PackPostVertexAdapter
                        .generate(chain.passes().get(i).inputs()).glsl();
            }
            return out;
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
                postChain = dev.vkdisp.pack.PackPostChain.Chain.EMPTY;
                java.util.Arrays.fill(POST_BUILTINS_LAYOUTS, BuiltinsBlockLayout.empty());
                dev.vkdisp.bridge.PackTextures.setDesired(null, null, java.util.Map.of());
                // GAP-021：总开关关闭 ⇒ 包自写 uniform 一并复位（漏这一步 = 上一张包的
                //   timeAngle 继续覆盖内建，画面「时间感没换」而日志全正常）。
                dev.vkdisp.pack.uniform.ActivePackUniforms.install(dev.vkdisp.pack.uniform.PackUniformSet.EMPTY);
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
            // 🔴 整链落状态（管线槽位源 = 重编号后的链源，尾部槽 = passthrough）：
            //   链与布局必须**同一份**——管线侧、执行侧、上传侧都从这里读，
            //   任何一处自己再算一遍就回到「两侧不一致而日志全正常」那一族（QD-02 第四例）。
            postChain = result.chain();
            String[] postSources = GeneratedSources.postSourcesFrom(result.chain());
            List<dev.vkdisp.pack.PackPostChain.Pass> chainPasses = result.chain().passes();
            for (int i = 0; i < POST_SLOT_COUNT; i++) {
                if (i < chainPasses.size()) {
                    POST_BUILTINS_LAYOUTS[i] =
                            BuiltinsBlockLayout.parse(chainPasses.get(i).renumberedSource());
                } else {
                    POST_BUILTINS_LAYOUTS[i] = BuiltinsBlockLayout.empty();
                }
            }
            if (!chainPasses.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (dev.vkdisp.pack.PackPostChain.Pass pass : chainPasses) {
                    names.append(names.length() == 0 ? "" : " → ").append(pass.programName())
                            .append(pass.attachmentSlots());
                }
                VkDisp.LOGGER.info(
                        "vkdisp: [chain] post chain active: pack={} passes={} names={}",
                        result.packName(), chainPasses.size(), names);
            }
            // GAP-009 素材线：texture.<sampler> 绑定表记下（上传在渲染线程懒做，见 PackTextures）。
            dev.vkdisp.bridge.PackTextures.setDesired(inventory, result.packName(),
                    result.textureBindings());
            // GAP-021：包自写的 uniform 表达式随本次激活一起安装（同一份冷路径产物，
            //   渲染侧每帧在 OfUniformManager.gather 末尾求值；兜底路径走下面的 EMPTY）。
            dev.vkdisp.pack.uniform.ActivePackUniforms.install(result.packUniforms());
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
            // 🔴 GAP-027：第二条（及以后）gbuffer 程序走**同一条**通用取用/生成链 ——
            //   这里没有「再复制一遍上面五行」，因为逻辑本来就在 takeSourceMemo /
            //   generateGbufferSource 这两个逐程序参数化的方法里（地形那两行是既有形状的特例）。
            java.util.Map<String, String> fragments = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> adapters = new java.util.LinkedHashMap<>();
            GeneratedSources.putGbuffer(fragments, adapters,
                    dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM, terrainSource, terrainAdapter);
            for (GbufferArtifacts entry : GBUFFER_PROGRAMS.values()) {
                if (entry.program.equals(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM)) {
                    continue; // 上面已按既有形状取过（同一份 memo，不是第二套状态）
                }
                takeOrGenerateGbuffer(entry, inventory, profile, selection, store, fragments, adapters);
            }
            return new GeneratedSources(
                    result.source(), result.deferredSource(), result.finalSource(), fragments,
                    adapters, postSources, GeneratedSources.postVerticesFrom(result.chain()));
        } catch (Throwable t) {
            hasDeferredProgram = false;
            hasFinalProgram = false;
            resetGbufferPrograms();
            compositeBuiltinsLayout = BuiltinsBlockLayout.empty();
            deferredBuiltinsLayout = BuiltinsBlockLayout.empty();
            finalBuiltinsLayout = BuiltinsBlockLayout.empty();
            postChain = dev.vkdisp.pack.PackPostChain.Chain.EMPTY;
            java.util.Arrays.fill(POST_BUILTINS_LAYOUTS, BuiltinsBlockLayout.empty());
            dev.vkdisp.bridge.PackTextures.setDesired(null, null, java.util.Map.of());
            dev.vkdisp.pack.uniform.ActivePackUniforms.install(dev.vkdisp.pack.uniform.PackUniformSet.EMPTY);
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
     * <p><b>为什么用键做记忆</b>：键 = {@code profile|selection|overrides}。
     * 配置热加载切包后键变化 ⇒ 重新生成；同一键重复调用直接复用
     * （注册与 openResources 各调一次，只付一次钱）。
     *
     * <p>🔖🔖 <b>键里必须有覆盖串</b>（本轮实测）：原键只有 {@code profile|selection}，
     * 改 {@code pack.optionOverrides} 时 composite 源会按新覆盖重新生成、
     * 地形契约却因键未变直接返回旧 memo ⇒ 两条链对同一份配置给出不同答案，
     * 而**没有一行日志**会说「地形契约被记忆命中」。凡是「在生成期被读一次」的
     * 配置项都必须进这个键。
     */
    public static synchronized void ensureTerrainProgram() {
        String profile = VkDispConfig.PACK_PROFILE.get();
        String selection = VkDispConfig.SHADER_PACK.get();
        // 🔖🔖 键由 {@link #currentTerrainMemoKey()} 造 —— 它是**单一真源**，
        //   取走时校验（{@link #takeSourceMemo}）用的是同一个算法。
        //   🔖 「造键用 A、校验用 B」会让校验永远通过；本轮首版就是这么写的，
        //   随后实测到 memo 与配置不符却仍被复用（见 takeSourceMemo 的注释）。
        // 🔖 键必须含**包选项覆盖串**：凡是「在生成期被读一次」的配置项都得进键
        //   （原键只有 profile|selection ⇒ 改覆盖串时 composite 侧按新配置、地形侧按旧配置，
        //   两条链互相矛盾而日志看起来完全正常 —— 与 h33 死开关同族）。
        String key = currentTerrainMemoKey();
        if (!VkDispConfig.ENABLED.get()) {
            // 总开关关掉时不提前编译（默认路径零额外冷路径开销，支柱③ B3/B4）。
            // 🔖 仍然要把每条程序的 memoKey 落下来：否则「关了总开关 → 开回来」这一步
            //   会因为键位仍是旧值而错误地命中上一轮的 memo。
            clearGbufferMemosWhenKeyChanges(key);
            return;
        }
        PackOptionStore store = null;
        for (GbufferArtifacts entry : GBUFFER_PROGRAMS.values()) {
            String programKey = entry.memoKeyFor();
            if (programKey.equals(entry.memoKey)) {
                continue; // 同键重复调用直接复用（注册与 openResources 各调一次，只付一次钱）
            }
            entry.memoKey = programKey;
            entry.sourceMemo = null;
            entry.adapterMemo = null;
            if (!entry.generationGate.getAsBoolean()) {
                continue; // 本条自己的开关关着 ⇒ 不编译（水关着时零额外冷路径开销）
            }
            if (store == null) {
                store = PackOptionStore.load(PackOptionStore.pathFor(gameDir()));
            }
            long started = System.nanoTime();
            entry.sourceMemo = generateGbufferSource(entry, inventoryDir(), profile, selection, store);
            VkDisp.LOGGER.info("vkdisp: [GAP-027] early gbuffer contract ready: program={} in {} ms (key={})",
                    entry.program, (System.nanoTime() - started) / 1_000_000L, programKey);
        }
    }

    /**
     * 总开关关闭时的键位维护：逐条把「与当前键不符」的 memo 清掉。
     *
     * <p>🔖 为什么不能整段 return（首版就是）：{@code enabled=false} 期间不更新键位 ⇒
     * 用户在关着的状态下切了包，再开回来时键位还是**两轮之前**的那一份 ⇒
     * {@link #takeSourceMemo} 的核对会被一份过期 memo 通过（症状 = 换了包但画面没换）。
     */
    private static void clearGbufferMemosWhenKeyChanges(String key) {
        for (GbufferArtifacts entry : GBUFFER_PROGRAMS.values()) {
            String programKey = key + "|" + entry.program;
            if (!programKey.equals(entry.memoKey)) {
                entry.memoKey = programKey;
                entry.sourceMemo = null;
                entry.adapterMemo = null;
            }
        }
    }

    /**
     * 把<b>所有</b> gbuffer 程序的产物一次复位（异常/总开关关闭路径用）。
     *
     * <p>🔴 必须逐条清且只在这一处清：残留的契约会让派生管线在下一轮注册里挂上
     * 一份「上一张包的」片元（画面错且日志全绿，X42）。
     */
    static synchronized void resetGbufferPrograms() {
        for (GbufferArtifacts entry : GBUFFER_PROGRAMS.values()) {
            entry.contract = null;
            entry.builtinsLayout = BuiltinsBlockLayout.empty();
            entry.memoKey = null;
            entry.sourceMemo = null;
            entry.adapterMemo = null;
        }
    }

    /**
     * 取走提前生成的源（{@code openResources} 用；无缓存返回 {@code null}）。
     *
     * <p>取走即清空 + 清键：这样下一次 {@link #ensureTerrainProgram} 看到「键为空」会重算，
     * 不会拿一份**上一轮**的契约去注册新一轮的管线（切包后拿到旧包片元 = 画面错且难归因）。
     *
     * <p>🔴🔶 GAP-010 根因（h16 定位）：取片元时<b>不得</b>顺手清适配层 memo。
     * 原实现清了它，而调用点是「先取片元、再取适配层」⇒ 适配层永远拿到 null
     * ⇒ 资源加载期找不到 {@code vkdisp_pack:terrain_pack_adapter} 的 VERTEX 源
     * ⇒ <b>12 条</b> resourceLoad/ERROR，且只能靠第二次资源重载自愈（用户在 UI 上看得见）。
     * 🔬 唯一的例外是 A/B 取证开关 {@code mrt.gap010Regression}（默认 false），见下面那处。
     */
    private static String takeTerrainSourceMemo() {
        return takeSourceMemo(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
    }

    /** 取走提前生成的适配层源（无缓存返回 {@code null}）。 */
    private static String takeTerrainAdapterMemo() {
        return takeAdapterMemo(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM);
    }

    /**
     * 逐程序取走片元 memo（GAP-003 的地形与 GAP-027 的水<b>共用这一份</b>校验逻辑）。
     *
     * <p>🔴🔖 **键不符就丢弃 memo**（GAP-003 那轮实测修的真缺陷，今天逐条程序都要吃到它）：
     * {@code ensureTerrainProgram} 只在**管线注册期**调一次，而注册事件在资源重载时**不再触发**
     * （原版 RenderPipelines 只初始化一次）。⇒ 重载后 memo 若还在，它就是**按上一轮配置**
     * 生成的那一份。实测症状：{@code pack.optionOverrides} 改成 {@code PARALLAX=true} 后，
     * 日志同时出现「composite 侧覆盖表已变」与「terrain source reused from early contract」
     * ⇒ 两条链对同一份配置给出**互相矛盾**的答案，而没有任何一行说「memo 是旧的」。
     * ⇒ 取走时必须核对键；不符就返回 null，让调用点走同步重生成（冷路径，慢但正确）。
     */
    private static String takeSourceMemo(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        if (entry == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] takeSourceMemo('{}') 不在程序表里（表={}）"
                    + " -> 返回 null（按不接线处理，绝不拿别条程序的 memo 凑数）",
                    program, GBUFFER_PROGRAMS.keySet());
            return null;
        }
        String currentKey = entry.memoKeyFor();
        if (entry.sourceMemo != null && !currentKey.equals(entry.memoKey)) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} 契约 memo 与当前配置不符（生成于 key={}，"
                    + "当前 key={}）⇒ **丢弃**并同步重生成。"
                    + "⚠️ 不丢弃的后果：composite 侧按新配置、本条程序按旧配置，"
                    + "两条链互相矛盾而日志看起来完全正常", program, entry.memoKey, currentKey);
            entry.sourceMemo = null;
            entry.adapterMemo = null;
        }
        String memo = entry.sourceMemo;
        entry.sourceMemo = null;
        // 🔬 A/B 用：mrt.gap010Regression=true 时**故意**丢掉适配层 memo = 复现 7206d6d 之前的状态
        //   （适配层元永远为 null ⇒ 资源加载期拿不到 VERTEX 源）。默认关，且开启即 WARN 自报。
        if (dev.vkdisp.VkDispConfig.MRT_GAP010_REGRESSION.get()) {
            entry.adapterMemo = null;
            VkDisp.LOGGER.warn("vkdisp: [GAP-010/AB] mrt.gap010Regression=true —— "
                    + "**故意**丢弃 {} 的适配层 memo，复现 7206d6d 之前的状态（仅供 A/B 取证）", program);
        }
        // 🔗 语义不变：两者仍然「同生共死」（片元为 null 时适配层也不进表，见 putGbuffer），
        //   而 takeAdapterMemo() 本身就会清自己，不需要这里多一手。
        entry.memoKey = null;
        return memo;
    }

    /** 逐程序取走适配层 memo（自己清自己，GAP-010 的那条纪律就在这里）。 */
    private static String takeAdapterMemo(String program) {
        GbufferArtifacts entry = GBUFFER_PROGRAMS.get(program);
        if (entry == null) {
            return null;
        }
        String memo = entry.adapterMemo;
        entry.adapterMemo = null;
        return memo;
    }

    /**
     * GAP-027：逐程序「取 memo，取不到就当场同步生成」的通用一步（水的源就走这里）。
     *
     * <p>🔖 地形那段既有形状（先 take、null 再 generate、然后重取适配层）与本方法是同一语义；
     * 之所以不复制第二遍逻辑：生成/校验/落表这三件事只要有一份实现，
     * 「半接线」（片元换了、适配层没换）就只可能出现在一个地方，而不是两个地方各自漂移。
     */
    private static void takeOrGenerateGbuffer(GbufferArtifacts entry, Path inventory, String profile,
            String selection, PackOptionStore store, java.util.Map<String, String> fragments,
            java.util.Map<String, String> adapters) {
        String source = takeSourceMemo(entry.program);
        String adapter = takeAdapterMemo(entry.program);
        if (source == null) {
            if (!entry.generationGate.getAsBoolean() || !VkDispConfig.ENABLED.get()) {
                return; // 这条本来就不该接（开关关着）⇒ 不编译、不落表
            }
            source = generateGbufferSource(entry, inventory, profile, selection, store);
            adapter = takeAdapterMemo(entry.program);
        }
        GeneratedSources.putGbuffer(fragments, adapters, entry.program, source, adapter);
    }

    /**
     * 按<b>本条程序</b>的 varying 契约生成顶点适配层（失败 → {@code null} = 不接线）。
     *
     * <p>🔖 与片元源同批生成：两者要么都给、要么都不给，
     * 免得出现「片元是包的、顶点还是原版」的半接线状态（那正是链接失败的直接来源）。
     *
     * <p>🔖 三个 {@code mrt.terrain*Probe} 是**地形那条单变量实验**的档位（GAP-008 取证线），
     * 只喂地形程序；别条程序一律按 {@code false} 生成中性适配层并自报一行。
     * 理由：那些档位的全部判据（h10~h15）都是在地形上取的，把它们静默加到水上，
     * 下一臂的「地形变亮/没变」就说不清了。
     */
    private static String generateGbufferAdapter(GbufferArtifacts entry,
            dev.vkdisp.pipeline.model.PackTerrainProgram program) {
        boolean terrainProgram = dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM.equals(entry.program);
        boolean fullLight = terrainProgram && VkDispConfig.MRT_TERRAIN_FULL_LIGHT_PROBE.get();
        boolean parallaxSkip = terrainProgram && VkDispConfig.MRT_TERRAIN_PARALLAX_SKIP_PROBE.get();
        boolean colorProbe = terrainProgram && VkDispConfig.MRT_TERRAIN_COLOR_PROBE.get();
        if (!terrainProgram && (VkDispConfig.MRT_TERRAIN_FULL_LIGHT_PROBE.get()
                || VkDispConfig.MRT_TERRAIN_PARALLAX_SKIP_PROBE.get()
                || VkDispConfig.MRT_TERRAIN_COLOR_PROBE.get())) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} 的适配层**不**应用 mrt.terrain*Probe 档"
                    + "（那些是地形单变量实验的判据，静默加到别条程序会让两臂不可比）", entry.program);
        }
        try {
            dev.vkdisp.glsl.translate.PackVertexAdapterGenerator.Result adapter =
                    dev.vkdisp.glsl.translate.PackVertexAdapterGenerator.generate(
                            program.inputs(), fullLight, parallaxSkip, colorProbe);
            for (TranslateDiagnostic diagnostic : adapter.diagnostics()) {
                logDiagnostic(diagnostic);
            }
            return adapter.glsl();
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-003] 顶点适配层生成 FAILED（原文如下）"
                    + " -> 本条 gbuffer 程序不接线（沿用原版 core/terrain）: program="
                    + entry.program, t);
            return null;
        }
    }

    /**
     * 当前配置下应使用的<b>基础</b>记忆键（{@code profile|selection|包地形开关|包水开关|overrides}）。
     *
     * <p>🔖 单一真源：{@link #ensureTerrainProgram} 造键、{@link #takeSourceMemo} 校验键，
     * 两处必须用同一份算法 —— 否则「造键用 A、校验用 B」会让校验永远通过（本轮就踩过）。
     * 🔴 逐条程序的键 = 本方法 + {@code |<程序名>}（{@link GbufferArtifacts#memoKeyFor}），
     * 每条程序各有一份 memo，但<b>键的算法只有一个</b>。
     */
    private static String currentTerrainMemoKey() {
        // 🔖🔖 QD-08 结构性守卫（`GenerationTimeSwitchInventoryTest`）枚举出的两项补齐：
        //   `ENABLED` 与 `MRT_PACK_TERRAIN_SHADER` 在本方法所在窗口内被读取
        //   （`ensureTerrainProgram` 用它们当生成前的闸门），却不在键里
        //   ⇒ 改动它们不会让地形契约重算，而 openResources 侧会按新值走
        //   ⇒ 两条链可能对同一份配置给出不同答案，而日志看起来完全正常。
        //   🔖 本项目的 QD-08 那一族已发生四次（debugLog 零消费点 / 反射键名当字段名 /
        //   optionOverrides 不进键 / 探针位置），所以这次是**枚举出来的**、不是想起来的。
        return VkDispConfig.PACK_PROFILE.get()
                + "|" + VkDispConfig.SHADER_PACK.get()
                + "|" + VkDispConfig.ENABLED.get()
                + "|" + VkDispConfig.MRT_PACK_TERRAIN_SHADER.get()
                // 🔴 GAP-027：`mrt.packWater` 同样在本窗口内被读（水那条的生成闸门），
                //   ⇒ 它**必须**进键，否则改水开关不会让任何契约重算，而 openResources 侧
                //   会按新值走（QD-08 第五例）。副作用（有意的、保守的）：改水开关时地形那条
                //   也会重算一次 —— 过度失效是安全的，不足才是要命的那一类。
                + "|" + VkDispConfig.MRT_PACK_WATER_SHADER.get()
                + "|" + dev.vkdisp.pack.PackOptionOverrideSwitch.spec();
    }

    private static String generateTerrainSource(Path inventory, String profile, String selection,
            PackOptionStore store) {
        // GAP-003 的既有形状：地形就是表里的**一条**，逻辑与水的完全同一份。
        return generateGbufferSource(GBUFFER_PROGRAMS.get(
                        dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM),
                inventory, profile, selection, store);
    }

    /**
     * 逐程序生成 gbuffer 片元源并把契约 / 适配层 / 块布局落进<b>该条程序自己的</b>表项。
     *
     * <p>🔴 与 GAP-003 的两处**刻意不同**（都在「静默错」那一族里，见每条的理由）：
     * <ol>
     *   <li><b>转译段级探针只对地形生效</b>：{@code mrt.terrainDerivativeProbe} /
     *       {@code mrt.terrain*SampleFactor} 那一组是 GAP-008 的<b>地形</b>单变量实验档位，
     *       静默把它们套到水上会让「地形那一臂变亮/没变」不再可比。
     *       ⇒ 非地形程序走中性窗口，并在此自报。</li>
     *   <li><b>失败只影响本条</b>：抛异常时只复位<b>本条</b>的契约/布局，
     *       地形那条不受影响（GAP-027 的判据 (c)：包没有水 ⇒ 水不接、地形照常）。</li>
     * </ol>
     */
    private static String generateGbufferSource(GbufferArtifacts entry, Path inventory,
            String profile, String selection, PackOptionStore store) {
        if (entry == null) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] generateGbufferSource 收到空表项 -> 不接线");
            return null;
        }
        boolean terrainProgram = dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM.equals(entry.program);
        entry.contract = null;
        // U0001f534 派生导数探针是**转译段级**的总闸（dcdx/dcdy 声明在片元里，顶点侧够不着）。
        //   只在生成地形源这段窗口里打开，并在 finally 复位：
        //   否则合成/延迟/最终四个程序的转译也会被它波及（它们可能也声明 dcdx）。
        boolean probeWas = dev.vkdisp.glsl.translate.DerivativeProbeAdapter.enabled();
        dev.vkdisp.glsl.translate.DerivativeProbeAdapter.setEnabled(
                terrainProgram && VkDispConfig.MRT_TERRAIN_DERIVATIVE_PROBE.get());
        // 🔖 采样因子探针同样是**转译段级**总闸，且同样是「静态开关 + 生成窗口内开、finally 复位」。
        //   不复位的后果与导数探针完全相同：合成/延迟/最终四个程序的转译也会被改写
        //   （它们也可能声明乘法链），症状是「开了诊断之后别的画面也变了」，极难归因。
        boolean sampleFactorWas = dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceSampleEnabled();
        boolean multiplierFactorWas =
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceMultiplierEnabled();
        boolean coordOutWas = dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceCoordOutEnabled();
        boolean coordOutFinalWas =
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceCoordOutFinalEnabled();
        boolean lodZeroWas = dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceLodZeroEnabled();
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceSample(
                terrainProgram && VkDispConfig.MRT_TERRAIN_SAMPLE_FACTOR_SAMPLE.get());
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceMultiplier(
                terrainProgram && VkDispConfig.MRT_TERRAIN_SAMPLE_FACTOR_MULTIPLIER.get());
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceCoordOut(
                terrainProgram && VkDispConfig.MRT_TERRAIN_COORD_OUT.get());
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceCoordOutFinal(
                terrainProgram && VkDispConfig.MRT_TERRAIN_COORD_OUT_FINAL.get());
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceLodZero(
                terrainProgram && VkDispConfig.MRT_TERRAIN_LOD_ZERO.get());
        // 🔖🔖 两侧同时开 = 两边都被换掉 = 什么都没分开。必须在这里就吵出来，
        //   而不是等跑完看画面 —— 那种「两臂都没变」会被读成「两个因子都不是原因」。
        if (terrainProgram && VkDispConfig.MRT_TERRAIN_SAMPLE_FACTOR_SAMPLE.get()
                && VkDispConfig.MRT_TERRAIN_SAMPLE_FACTOR_MULTIPLIER.get()) {
            VkDisp.LOGGER.error("vkdisp: [GAP-008] 🔴 采样因子探针的**左右两侧同时开启**"
                    + "（mrt.terrainSampleFactorSample=true 且 mrt.terrainSampleFactorMultiplier=true）"
                    + " ⇒ 乘法链两侧都被替换，**无法**分出是哪一侧为 0。"
                    + "已按「左侧开、右侧关」继续；要做右侧那一臂请只开右侧。⚠️ 本臂的结论不可用");
        }
        try {
            return realizeGbufferProgram(entry, inventory, profile, selection, store);
        } catch (Throwable t) {
            // 🔴 只复位**本条**：包没有水 / 水的契约解析不了，不该把地形那条一起打成不接线
            //   （X9/X11：keep vanilla behavior AND log why；株连是另一种静默错）。
            entry.contract = null;
            entry.builtinsLayout = BuiltinsBlockLayout.empty();
            VkDisp.LOGGER.error("vkdisp: [GAP-027] pack gbuffer fragment selection FAILED (原文如下)"
                    + " -> 本条程序不接线，派生 MRT 管线对该层保持原版 core/terrain: program="
                    + entry.program, t);
            return null;
        } finally {
            // U0001f534 必须在 finally 复位：探针是全局静态闸，漏复位会让后续
            //   合成/延迟/最终四个程序的转译也被改写（它们可能也声明 dcdx）。
            //   漏复位的症状是「开了诊断开关之后别的画面也变了」，极难归因。
            dev.vkdisp.glsl.translate.DerivativeProbeAdapter.setEnabled(probeWas);
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceSample(sampleFactorWas);
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceMultiplier(multiplierFactorWas);
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceCoordOut(coordOutWas);
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceCoordOutFinal(coordOutFinalWas);
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceLodZero(lodZeroWas);
        }
    }

    /**
     * 真正走「选包 → 编译 → 契约解析 → 适配层生成 → 探针改写」那一段（逐程序同一份实现）。
     *
     * <p>🔖 从 {@link #generateGbufferSource} 里拆出来只为一件事：探针窗口（开 / 复位）与
     * 契约落表是两层不同的责任，混在一个方法里就会长出第二个 100+ 行的编排体
     * （QD-04 棘轮的口径：拆方法，不抬基线）。
     *
     * @return 该片元的终稿（进虚拟包资源）；{@code null} = 本条不接线
     */
    private static String realizeGbufferProgram(GbufferArtifacts entry, Path inventory,
            String profile, String selection, PackOptionStore store) {
        boolean terrainProgram = dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM.equals(entry.program);
        dev.vkdisp.pack.PackTerrainSource.Result terrain = dev.vkdisp.pack.PackTerrainSource.generate(
                inventory, profile, selection, store, entry.program);
        for (TranslateDiagnostic diagnostic : terrain.diagnostics()) {
            logDiagnostic(diagnostic);
        }
        if (!terrain.wired()) {
            entry.builtinsLayout = BuiltinsBlockLayout.empty();
            // 🔴 一条「不接线」的自报，**原因**由 GbufferProgramPlan 统一口径（永不静默）。
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] {}", GbufferProgramPlan.notWiredReport(entry.program,
                    GbufferProgramPlan.Skip.ABSENT,
                    dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM));
            return null;
        }
        dev.vkdisp.pipeline.model.PackTerrainProgram program = terrain.program();
        entry.contract = program;
        entry.adapterMemo = generateGbufferAdapter(entry, program);
        entry.builtinsLayout = BuiltinsBlockLayout.parse(program.fragmentSource());
        logLayout(entry.program, entry.builtinsLayout);
        String fragment = program.fragmentSource();
        // 🔖🔖 采样因子探针在此生效（**不**接进 OfGlslTranslator，理由见该类 KEEP_OUT）：
        //   作用域天然是「本条程序的片元源」，且不引入任何跨程序 / 跨线程的静态开关。
        //   🔖 只改**赋值右值**，不碰任何声明 ⇒ outputCount / samplers / varyings 契约不变，
        //      所以不必重解析 PackTerrainProgram。非地形程序走的是中性窗口（上面已置 false），
        //      apply 在里面就是恒等变换 —— 不必再复制一份代码。
        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.Result sampleFactor =
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.apply(
                        dev.vkdisp.glsl.translate.ShaderStage.FRAGMENT, fragment);
        for (TranslateDiagnostic diagnostic : sampleFactor.diagnostics()) {
            logDiagnostic(diagnostic);
        }
        fragment = sampleFactor.text();
        // 🔴 GAP-016 **产品级修复**（由 h46 G 臂实测坐实，探针档转正）：
        //   本引擎里包地形片元对图集的隐式导数 LOD 会选到坏 mip ⇒ texture() 恒 0。
        //   ⇒ 把**albedo 乘法链那一行**的两参采样钉到显式 mip0（锚点与 h45 探针同一条线；
        //     其余采样行不在此段范围 —— 视差/材质细节若仍受坏 mip 影响，归 GAP-016 修根）。
        //   守卫：非诊断窗口里本段**必须只动一行**（h45 的 124 处教训 —— 自报命中行）。
        //   🔖 GAP-027：本段的锚点是**地形**那条的 albedo 行（h45/h46 的判据都取自那里），
        //      水是否需要同一段由 GAP-016 自己在程序的臂里判，不在此顺手套用（X39）。
        if (terrainProgram && !VkDispConfig.MRT_TERRAIN_LOD_ZERO.get()
                && VkDispConfig.MRT_TERRAIN_ATLAS_LOD0.get()
                && dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceLodZeroEnabled() == false) {
            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceLodZero(true);
            try {
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.Result productLod =
                        dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.apply(
                                dev.vkdisp.glsl.translate.ShaderStage.FRAGMENT, fragment);
                if (productLod.patchedLodZero() != 1) {
                    VkDisp.LOGGER.error("vkdisp: [GAP-016] 产品级 LOD0 段命中 {} 处（预期 1）"
                                    + " —— 只改一行是 h45 立的铁律，请人工核对：{}",
                            productLod.patchedLodZero(),
                            dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.lastLodHitLines());
                }
                fragment = productLod.text();
            } finally {
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.setForceLodZero(false);
            }
        }
        VkDisp.LOGGER.info(
                "vkdisp: [GAP-027] pack gbuffer fragment ready: program={} outputs={} declaredSlots={}"
                        + " samplers={} varyings={} bytes={} sampleFactorProbe[sample={} multiplier={}"
                        + " coordOut={} coordOutFinal={} lodZero={} hits={}]",
                program.qualifiedName(), program.outputCount(), program.declaredOutputSlots(),
                program.fragmentSamplers().size(), program.inputs().size(),
                fragment.getBytes(StandardCharsets.UTF_8).length,
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceSampleEnabled(),
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceMultiplierEnabled(),
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceCoordOutEnabled(),
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceCoordOutFinalEnabled(),
                dev.vkdisp.glsl.translate.SampleFactorProbeAdapter.forceLodZeroEnabled(),
                sampleFactor.patchedAny());
        return fragment;
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
        /**
         * 🔴 GAP-027：逐条 gbuffer 程序的片元字节（键 = 程序名）。
         *
         * <p>🔖 「这条程序不接线」= 表里<b>根本没有这一项</b>（于是资源查询返回 null =
         * 资源不存在），而不是返回一段兜底文本：此刻没有任何管线引用它，
         * 万一后来人引用了却拿到兜底，症状会变成「用的是内建 passthrough 而不是原版地形」
         * —— 比「资源缺失」更难归因。
         */
        private final java.util.Map<Identifier, byte[]> gbufferFragmentBytes;

        /** 逐条程序的顶点适配层字节（与该片元<b>同生共死</b>，半接线 = 链接失败）。 */
        private final java.util.Map<Identifier, byte[]> gbufferAdapterBytes;

        /** 🔴 后处理 16 槽片元字节（每槽恒非 null —— required 管线的兜底语义同 composite）。 */
        private final byte[][] postBytes;
        /** 🔴 后处理 16 槽 **VS 适配层**字节（h46：按片元输入契约生成，同 id 解析到 .vsh）。 */
        private final byte[][] postVertexBytes;

        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource) {
            this(location, compositeSource, deferredSource, finalSource,
                    java.util.Map.of(), java.util.Map.of(),
                    GeneratedSources.fallbackPostSources(), GeneratedSources.fallbackPostVertices());
        }

        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource, String terrainSource,
                String terrainAdapterSource) {
            this(location, compositeSource, deferredSource, finalSource,
                    solo(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM, terrainSource),
                    solo(dev.vkdisp.pack.PackTerrainSource.TERRAIN_PROGRAM, terrainAdapterSource),
                    GeneratedSources.fallbackPostSources(), GeneratedSources.fallbackPostVertices());
        }

        /** 单条程序的源表（{@code null} 值 = 这一条不接线 ⇒ 表里不留项）。 */
        private static java.util.Map<String, String> solo(String program, String source) {
            return source == null ? java.util.Map.of() : java.util.Map.of(program, source);
        }

        /** 资源 id 在包内的路径（{@code listResources} 的前缀匹配用它；就是构造时的同一条路径）。 */
        private static String pathOf(Identifier id) {
            return id.getPath();
        }

        /**
         * 「程序名 → 源文本」翻成「资源 id → 字节」（id 由程序名派生，与管线侧同一份算法）。
         *
         * @param fragment {@code true} = 片元（.fsh），{@code false} = 顶点适配层（.vsh）
         */
        private static java.util.Map<Identifier, byte[]> toResourceBytes(
                java.util.Map<String, String> sources, boolean fragment) {
            if (sources == null || sources.isEmpty()) {
                return java.util.Map.of();
            }
            java.util.Map<Identifier, byte[]> out = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> entry : sources.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                GbufferArtifacts artifacts = GBUFFER_PROGRAMS.get(entry.getKey());
                if (artifacts == null) {
                    VkDisp.LOGGER.error("vkdisp: [GAP-027] 生成了不在程序表里的 gbuffer 源 '{}' -> **丢弃**"
                            + "（没有任何管线会引用它；留着只会让资源侧多一份没人要的文件）", entry.getKey());
                    continue;
                }
                Identifier id = fragment ? artifacts.fragmentResourceId : artifacts.adapterResourceId;
                out.put(id, entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return java.util.Map.copyOf(out);
        }

        /**
         * 主构造器：逐程序表（键 = 程序名）+ 后处理 16 槽。
         *
         * <p>🔖 「程序名 → 源文本」在这里一次性翻成「资源 id → 字节」，id 由程序名派生
         * （{@link GbufferArtifacts}），与管线侧是<b>同一份</b>算法 ——
         * 资源侧再算一遍路径就是第二套状态，而两套路径算法漂移的后果是
         * 「包提供了但管线找不到」= 资源加载期缺 VERTEX/FRAGMENT 源（GAP-010 同族）。
         */
        VirtualPackResources(PackLocationInfo location, String compositeSource,
                String deferredSource, String finalSource,
                java.util.Map<String, String> gbufferFragments,
                java.util.Map<String, String> gbufferAdapters,
                String[] postSources, String[] postVertexSources) {
            this.location = location;
            this.compositeBytes = compositeSource.getBytes(StandardCharsets.UTF_8);
            this.deferredBytes = deferredSource.getBytes(StandardCharsets.UTF_8);
            this.finalBytes = finalSource.getBytes(StandardCharsets.UTF_8);
            this.gbufferFragmentBytes = toResourceBytes(gbufferFragments, true);
            this.gbufferAdapterBytes = toResourceBytes(gbufferAdapters, false);
            this.postBytes = new byte[POST_SLOT_COUNT][];
            this.postVertexBytes = new byte[POST_SLOT_COUNT][];
            String fallbackVsh = GeneratedSources.fallbackPostVertices()[0];
            for (int i = 0; i < POST_SLOT_COUNT; i++) {
                String src = postSources != null && i < postSources.length && postSources[i] != null
                        ? postSources[i] : PackCompositeSource.FALLBACK_GLSL;
                this.postBytes[i] = src.getBytes(StandardCharsets.UTF_8);
                String vsh = postVertexSources != null && i < postVertexSources.length
                        && postVertexSources[i] != null ? postVertexSources[i] : fallbackVsh;
                this.postVertexBytes[i] = vsh.getBytes(StandardCharsets.UTF_8);
            }
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
            // 🔖 gbuffer 片元「不接线」的那一条**返回 null**（= 资源不存在），而不是返回兜底文本：
            //   此刻没有任何管线引用 vkdisp_pack:<program>，注册路径不会去取它；
            //   万一有人后来引用了却拿到兜底，症状会变成「用的是内建 passthrough 而不是原版地形」
            //   —— 比「资源缺失」更难归因。
            //   🔴 GAP-027：查表而不是逐条 if —— 表里<b>只有本轮真的接上的</b>那几条。
            byte[] gbufferFragment = gbufferFragmentBytes.get(id);
            if (gbufferFragment != null) {
                return () -> new ByteArrayInputStream(gbufferFragment);
            }
            byte[] gbufferAdapter = gbufferAdapterBytes.get(id);
            if (gbufferAdapter != null) {
                return () -> new ByteArrayInputStream(gbufferAdapter);
            }
            // 🔴 后处理 16 槽（每槽恒在 —— 槽位管线是 required，兜底 = passthrough）。
            for (int i = 0; i < POST_SLOT_COUNT; i++) {
                if (postId(i).equals(id)) {
                    final int slot = i;
                    return () -> new ByteArrayInputStream(postBytes[slot]);
                }
                if (postVshId(i).equals(id)) {
                    final int slot = i;
                    return () -> new ByteArrayInputStream(postVertexBytes[slot]);
                }
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
            // 🔴 GAP-027：逐条 gbuffer 程序的资源（表里有的才列出来 = 「不接线的那条根本不提供」）。
            //   id → 路径由程序表反查，保证与 getResource 用的是<b>同一份</b> id 算法（X42）。
            for (java.util.Map.Entry<Identifier, byte[]> entry : gbufferFragmentBytes.entrySet()) {
                String resourcePath = pathOf(entry.getKey());
                if (normalized.isEmpty()
                        || resourcePath.equals(normalized)
                        || resourcePath.startsWith(normalized + "/")) {
                    final byte[] bytes = entry.getValue();
                    output.accept(entry.getKey(), () -> new ByteArrayInputStream(bytes));
                }
            }
            for (java.util.Map.Entry<Identifier, byte[]> entry : gbufferAdapterBytes.entrySet()) {
                String resourcePath = pathOf(entry.getKey());
                if (normalized.isEmpty()
                        || resourcePath.equals(normalized)
                        || resourcePath.startsWith(normalized + "/")) {
                    final byte[] bytes = entry.getValue();
                    output.accept(entry.getKey(), () -> new ByteArrayInputStream(bytes));
                }
            }
            for (int i = 0; i < POST_SLOT_COUNT; i++) {
                String postPath = postPath(i);
                if (normalized.isEmpty()
                        || postPath.equals(normalized)
                        || postPath.startsWith(normalized + "/")) {
                    final int slot = i;
                    output.accept(postId(slot), () -> new ByteArrayInputStream(postBytes[slot]));
                }
                String postVshPath = "shaders/post" + i + ".vsh";
                if (normalized.isEmpty()
                        || postVshPath.equals(normalized)
                        || postVshPath.startsWith(normalized + "/")) {
                    final int slot = i;
                    output.accept(postVshId(slot), () -> new ByteArrayInputStream(postVertexBytes[slot]));
                }
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
