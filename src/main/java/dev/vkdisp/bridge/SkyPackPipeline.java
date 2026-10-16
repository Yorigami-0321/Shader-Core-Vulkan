package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶路线：把 {@link SkyGeometrySite} 那三处站点的<b>派生管线规格</b>建出来
 *                （三处只差顶点形状/拓扑/混合，块与绑定组同一形 —— 见 {@code SkyGeometrySite} 类注释第 3 条）
 * 0. 合规核对（第 0 步闸门）：参考对象 = 本仓库自有 {@link SkyCelestialPack#compiled()}（同一条「GLOBALS_SNIPPET +
 *    PROJECTION + DYNAMIC_TRANSFORMS + 我方块布局 + 包的 adapter/fragment shader id」的派生管线构造，且同样
 *    <b>先 build 描述符、编译交给 {@code RenderSystem.getCompiledPipeline} 在渲染线程做</b>）。
 *    与日月的唯一结构差异：穹顶/星点/晨昏<b>不采图集</b> ⇒ 不留 {@code SAMPLER0}。
 *    外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）。
 *    许可证：独立编写（MIT），零第三方代码、零 Mojang 着色器文本。
 * 1. 官方/主实现：原版这三处走各自管线（{@code RenderPipelines.{SKY,STARS,SUNRISE_SUNSET}}），没有「换成包程序」
 *    这回事 ⇒ 本类是本项目的派生管线工厂；它的<b>逐站形状差异全部读 {@link SkyGeometrySite}</b>（同源，QD-02），
 *    不在这里各写一遍。
 * 2. 备选：① 把管线构造直接写进三个 {@code @WrapOperation} —— <b>否决</b>：三份几乎相同的 builder 调用散写就会
 *       漂移（一处忘换 location 后缀就撞车）；② 复用 {@code SkyCelestialPack.compiled()} —— <b>否决</b>：那条绑
 *       {@code SAMPLER0}+日月程序，穹顶喂进去就是「拿日月图集程序画天空」的静默错（X9）。
 * 3. 我们的差异点：🔖 本类<b>只产出描述符</b>（{@code RenderPipeline.build}），<b>不</b>在这里调
 *    {@code getCompiledPipeline}（那要 GPU、跑在渲染线程的混入里）—— 这样离线守卫能验到「形状/绑定组/位置」这层，
 *    而真正编译失败仍由混入侧的既有兜底（{@code SkyCelestialPack} 同款：没落地就本帧不换）。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 描述符每程序每契约建一次（与 {@code SkyCelestialPack} 同一缓存口径）；渲染期不建。
 */
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vkdisp.VkDisp;
import dev.vkdisp.VkDispVirtualPack;
import dev.vkdisp.pipeline.model.PackTerrainProgram;
import java.util.List;

/** 由 {@link SkyGeometrySite} 建「包的 {@code gbuffers_skybasic}」派生管线描述符（穹顶/星点/晨昏共用）。 */
public final class SkyPackPipeline {

    /** 派生管线 location 前缀（每次重建换后缀 ⇒ 满足「改过必须换 location」契约，与 {@code SkyCelestialPack} 同族）。 */
    private static final String BASE_LOCATION = "pipeline/gbuffer_sky_geometry";

    private SkyPackPipeline() {
    }

    /**
     * 这条管线<b>不需要</b> {@code SAMPLER0}：穹顶/星点/晨昏的 {@code skybasic} 片元不采任何贴图
     * （日月那条才采 celestials 图集，见 {@link SkyCelestialPack}）。留着它反而是多余绑定组。
     */
    public static boolean requiresSampler0(SkyGeometrySite site) {
        return false;
    }

    /** 本条管线要绑的包程序（三处恒为 {@code gbuffers_skybasic}）。 */
    public static String program(SkyGeometrySite site) {
        return site.program;
    }

    /**
     * 建派生管线<b>描述符</b>（不编译）。失败（契约缺、shader id 缺）返回 {@code null} ⇒ 调用方（混入）本帧不换、
     * 交回原版 —— 与 {@code SkyCelestialPack} 同一条「备料没成就原样交回，绝不半接」纪律（GAP-036 ④）。
     */
    public static RenderPipeline descriptor(SkyGeometrySite site) {
        PackTerrainProgram contract = VkDispVirtualPack.packContract(site.program);
        if (contract == null) {
            return null;
        }
        net.minecraft.resources.Identifier location = net.minecraft.resources.Identifier
                .fromNamespaceAndPath(VkDisp.MOD_ID, locationSuffix(site, contract));
        return RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(location)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS)
                // 🔴 不绑 SAMPLER0：skybasic 片元不采贴图（见 requiresSampler0）。
                .withBindGroupLayout(TerrainPipelineApi.packBindGroupLayout(contract))
                .withVertexShader(VkDispVirtualPack.packAdapterShaderId(site.program))
                .withFragmentShader(VkDispVirtualPack.packFragmentShaderId(site.program))
                // 逐站形状全部读 SkyGeometrySite（与原版管线逐字一致；写错就是几何解释错）。
                .withVertexBinding(0, site.vertexFormat)
                .withPrimitiveTopology(site.topology)
                .withColorTargetState(site.colorTargetState())
                .build();
    }

    /** location 后缀：站点名 + 契约身份哈希 ⇒ 换包/换契约自动换号（不撞车）。 */
    private static String locationSuffix(SkyGeometrySite site, PackTerrainProgram contract) {
        return BASE_LOCATION + "_" + site.name().toLowerCase(java.util.Locale.ROOT)
                + "_" + Integer.toHexString((contract.qualifiedName() + System.identityHashCode(contract)).hashCode());
    }

    /** 诊断：三处站点 + 各自混合是否需要 sampler（恒 false，供自报/守卫读）。 */
    public static List<String> describeSites() {
        return java.util.Arrays.stream(SkyGeometrySite.values())
                .map(s -> s.name() + "(sampler0=" + requiresSampler0(s) + ")")
                .toList();
    }
}
