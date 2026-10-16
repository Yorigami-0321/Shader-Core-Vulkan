package dev.vkdisp.bridge;
/**
 * 【自行补充】GAP-027 穹顶路线：把「原版天空的哪一处站点该喂包的哪条程序、按哪种顶点形状画」
 *                这三处 `@WrapOperation` 共用的<b>决策表</b>冻成一处（逐字对账原版 26.3）
 * 0. 合规核对（第 0 步闸门）：参考对象 = ① 原版 26.3 的<b>可观察格式事实</b>（只取「用哪条顶点流 /
 *    哪种拓扑 / 哪种混合」这一层，不取任何着色器文本）：`SkyRenderer` 的三处私有方法与它们各自的
 *    `setPipeline` 站点（`SkyRenderer.java:163,167` = renderSkyDisc；`:266,274` = renderStars；
 *    `:284,298` = renderSunriseAndSunset），以及那三条管线的定义（`RenderPipelines.java:1037-1083`）；
 *    ② 本仓库自有的 {@link SkyCelestialPack}（日月那一格的同类形状：换管线前先备好块字节 + 逐站
 *    `renderStage`）。外部候选（Iris / OptiFine / VulkanMod / Sulkan / Beryl）零接触（07-CONSTRAINTS L12）。
 *    许可证：独立编写（MIT），零第三方代码、零 Mojang 着色器文本。
 * 1. 官方/主实现：原版这三处各自走 `RenderPipelines.{SKY,STARS,SUNRISE_SUNSET}`，没有「换成包程序」
 *    这回事 ⇒ 本表是本项目的责任分离点：把「站点 → 程序 / 顶点形状 / 渲染阶段宏」收在一处，
 *    这样三处混入与派生管线构造读<b>同一份事实</b>，各写一遍就会不一致（QD-02 同族）。
 * 2. 备选：① 把这三行直接写进各个 `@WrapOperation` 方法体 —— <b>否决</b>：顶点形状 / 拓扑 / 混合
 *       与 `renderStage` 必须<b>同源于管线定义</b>，散在三处改一处漏一处就是静默错（X42）；
 *    ② 复用 {@link SkyCelestialPack} 那条管线 —— <b>否决</b>：日月是 `POSITION_TEX`+`SAMPLER0`+图集，
 *       而穹顶/星点/晨昏是 `gbuffers_skybasic`（屏幕空间片元、无 sampler），两种顶点源，不能凑。
 * 3. 我们的差异点：🔑 <b>三处都喂同一条 `gbuffers_skybasic`，只有顶点形状与 `renderStage` 不同</b>。
 *    这不是猜：BSL 的 `gbuffers_skybasic.glsl:169,179` 用 `renderStage == MC_RENDER_STAGE_STARS /
 *    _MOON` 把那些站的 `alpha` 归零来抑制原版叠加 —— 即<b>同一个片元程序按渲染阶段分派</b>。
 *    🔴 本表<b>只是数据</b>（不参与任何判定、每站一份），消费它的混入与管线构造尚未落地（见
 *    CHANGE_LOG 一百七十三/一百七十二的顺序约束：换管线与退役全屏接管必须同一次做，且只能画面判）。
 * 4. 许可证核对：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 纯枚举常量，渲染期零计算；本表被读的那一刻也不建任何 GPU 资源。
 */
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.vkdisp.glsl.preprocess.RenderStages;
import dev.vkdisp.pack.PackTerrainSource;

/** 原版天空的三处「可换成包 `gbuffers_skybasic`」的站点，各自冻结一份顶点形状与渲染阶段。 */
public enum SkyGeometrySite {

    /** 天空穹顶：`SkyRenderer#renderSkyDisc`（`:163`）走 {@code RenderPipelines.SKY}（`:1037-1046`，混合 = {@code ColorTargetState.DEFAULT}）。 */
    SKY_DISC(PackTerrainSource.SKY_PROGRAM, "SKY",
            DefaultVertexFormat.POSITION, PrimitiveTopology.TRIANGLE_FAN, null),

    /** 星点：`SkyRenderer#renderStars`（`:266`）走 {@code RenderPipelines.STARS}（`:1072-1082`）。 */
    STARS(PackTerrainSource.SKY_PROGRAM, "STARS",
            DefaultVertexFormat.POSITION, PrimitiveTopology.QUADS, BlendFunction.OVERLAY),

    /** 晨昏辉光：`SkyRenderer#renderSunriseAndSunset`（`:284`）走 {@code RenderPipelines.SUNRISE_SUNSET}（`:1060-1070`）。 */
    SUNRISE_SUNSET(PackTerrainSource.SKY_PROGRAM, "SUNSET",
            DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.TRIANGLE_FAN, BlendFunction.TRANSLUCENT);

    /** 本站点要换成哪条包程序（三处都是 `gbuffers_skybasic`）。 */
    public final String program;

    /** 喂给这笔 draw 的 `renderStage` 宏名（不含 {@link RenderStages#MACRO_PREFIX} 前缀）。 */
    public final String stageMacro;

    /** 顶点流形状 —— 与原版管线<b>逐字相同</b>（换了它 = 几何解释错，比换混合更致命）。 */
    public final VertexFormat vertexFormat;

    /** 拓扑 —— 与原版管线逐字相同。 */
    public final PrimitiveTopology topology;

    /** 混合函数；{@code null} = 用 {@code ColorTargetState.DEFAULT}（穹顶那一笔就是它，见 {@link #colorTargetState()}）。 */
    public final BlendFunction blend;

    SkyGeometrySite(String program, String stageMacro, VertexFormat vertexFormat,
            PrimitiveTopology topology, BlendFunction blend) {
        this.program = program;
        this.stageMacro = stageMacro;
        this.vertexFormat = vertexFormat;
        this.topology = topology;
        this.blend = blend;
    }

    /** 本站点该给派生管线的颜色目标状态：与原版一致（穹顶 = DEFAULT，其余 = 各自混合函数）。 */
    public ColorTargetState colorTargetState() {
        return blend == null ? ColorTargetState.DEFAULT : new ColorTargetState(blend);
    }

    /** 本站的渲染阶段编号（从唯一真源 {@link RenderStages} 取；宏名不认识返回 {@code -1}，由消费方判不接管）。 */
    public int renderStageValue() {
        Integer value = RenderStages.valueOf(RenderStages.MACRO_PREFIX + stageMacro);
        return value == null ? -1 : value;
    }
}
