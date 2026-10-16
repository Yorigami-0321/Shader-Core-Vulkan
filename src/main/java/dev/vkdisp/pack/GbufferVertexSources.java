package dev.vkdisp.pack;
/**
 * 【自行补充】一条 {@code gbuffers_*} 程序的<b>顶点源</b>从哪来（GAP-027 天空线的责任分离）。
 *
 * <p>0. 合规核对：参考对象 = 本仓库自有实现 —— 顶点适配层生成器
 *    {@link dev.vkdisp.glsl.translate.PackVertexAdapterGenerator}（chunk 几何那一族）与
 *    {@link dev.vkdisp.glsl.translate.PostVertexLinker}（后处理链那一族，post 恒等 MVP）。
 *    外部候选（Iris / OptiFine / VulkanMod）零接触；许可证：本文件独立编写（MIT），
 *    不含任何 GPL / LGPL / ARR 代码（07-CONSTRAINTS §〇 P1 / L5-L8）。
 *
 * <p>1. 官方/主实现：本类不引入新机制，只是把「同一个虚拟包文件里长出两套顶点源决策」这件事
 *    收成一个出口。今天只有 {@link #chunkAdapter} 一条路被生产调用；
 *    post 那条（{@link PackTerrainSource#postVertexProgram}）由
 *    {@link PackTerrainSource} 在生成期直接配对好，本类只负责<b>判定谁该走哪条</b>。
 *
 * <p>2. 备选：① 继续留在 {@code VkDispVirtualPack} 里 —— <b>否决</b>：那个类正好压在
 *    {@code ClassLineRatchetTest} 的 1573 行上限（实测加天空的三处接线 = 1590 被拦），
 *    而棘轮的裁决就是「新增内容落到独立类里」（QD-10 / {@code 19} §3.1）；
 *    ② 给每条程序建一个顶点源策略对象 —— <b>否决</b>：今天只有两条路，两个分支不需要一层抽象。
 *
 * <p>3. 我们的差异点：🔴 <b>「片元是包的、顶点不是」= 半接线</b>，而半接线的症状不是「效果不对」
 *    而是<b>链接期抛异常</b>。所以本类的两条路都必须与片元<b>同批</b>产出、要么都不给
 *    （{@code generateGbufferSource} 那侧的既有纪律，这里不重复一遍判定）。
 *
 * <p>4. 许可证核对：本项目 MIT；零第三方代码并入。
 *
 * <p>5. 性能基线：❄️ 冷路径 —— 只在生成期（换包 / 换配置）被调，渲染期零调用、零分配。
 */
import dev.vkdisp.VkDisp;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.PackVertexAdapterGenerator;
import dev.vkdisp.pipeline.model.PackTerrainProgram;

/** 「这条 gbuffer 程序的顶点源从哪来」的唯一出口（渲染线程之外调用；无状态）。 */
public final class GbufferVertexSources {

    private GbufferVertexSources() {
    }

    /**
     * 本条程序是否走 post 顶点路线（顶点 = 包自己写的 {@code .vsh} + 恒等 MVP）。
     *
     * <p>🔖 判定住在 {@link PackTerrainSource}，因为<b>决定权在生成期</b>：只有在那里才拿得到
     * 该程序的顶点终稿。本类转手是为了让「谁走哪条」在一个文件里可读。
     */
    public static boolean postVertexRoute(String program) {
        return PackTerrainSource.postVertexProgram(program);
    }

    /**
     * 该程序的 {@code gbufferProjection} 该按<b>哪种深度口径</b>供（GAP-022 的分族供值）。
     *
     * <p>🔴 分族的判据是<b>「这个程序拿矩阵干什么」</b>，不是程序名的前缀：
     * {@code gbuffers_*} 的顶点阶段要用它写 {@code gl_Position} ⇒ 必须引擎口径
     * （喂 {@code D2·P} 会让 clip.z 掉出本前端的 {@code [0,1]} 值域）；
     * 而<b>屏幕空间</b>的那几条（今天 = {@code gbuffers_skybasic}，它从
     * {@code gl_FragCoord} 反解 {@code viewPos}、顶点只走恒等 MVP）与链同形 ⇒ 走 CHAIN，
     * 跟着 {@code mrt.depthGlProxy} 翻。
     * 实测：给它 GBUFFER 族时包算出来的天空是<b>纯黑</b>（半翻口径的另一半，GAP-022 已警告过
     * 「只翻一半比全不翻更难查」）。
     */
    public static dev.vkdisp.render.OfUniformManager.Family builtinsFamily(String program) {
        // 🔴 判据是「片元拿矩阵干什么」，不是程序名前缀：
        //   ① post 顶点路线那条（今天空集，见 PackTerrainSource.postVertexProgram）走 CHAIN；
        //   ② 天空穹顶那一条（gbuffers_skybasic）的片元<b>也是屏幕空间</b>（gl_FragCoord 反解 viewPos），
        //      它现在走<b>几何适配层</b>而非 post 路线 ⇒ 必须<b>显式</b>留 CHAIN，否则跟着翻到 GBUFFER 口径
        //      = 半翻那另一半（实测：给它 GBUFFER 族时包算出来的天空是纯黑，GAP-022 已警告过）。
        return postVertexRoute(program) || PackTerrainSource.SKY_PROGRAM.equals(program)
                ? dev.vkdisp.render.OfUniformManager.Family.CHAIN
                : dev.vkdisp.render.OfUniformManager.Family.GBUFFER;
    }

    /**
     * chunk 几何那一族的顶点适配层（地形 / 水）。失败 → {@code null} = 本条不接线。
     *
     * <p>🔖 三个 {@code mrt.terrain*Probe} 是<b>地形那条单变量实验</b>的档位（GAP-008 取证线），
     * 只喂地形程序；别条程序一律按 {@code false} 生成中性适配层并自报一行。理由：那些档位的
     * 全部判据（h10~h15）都是在地形上取的，把它们静默加到水/天空上，下一臂的
     * 「地形变亮/没变」就说不清了。
     *
     * @param program   程序名（只用于日志与「是不是地形」这一条判定）
     * @param contract  该程序<b>片元</b>的冻结契约（顶点必须逐位置逐名字喂上它要的 varying）
     */
    public static String chunkAdapter(String program, PackTerrainProgram contract) {
        return chunkAdapter(program, contract, null);
    }

    /**
     * chunk 几何那一族的顶点适配层（带 GAP-007 的导数接管辅助 varying）。
     *
     * <p>🔴 {@code tbnPlan} 必须是<b>片元那一次改写用的同一个 plan</b>：两侧合成 varying 的 location
     * 由它算，各算一遍就是「片元读 14 而顶点写 15」这种静默错值（X42）。
     */
    public static String chunkAdapter(String program, PackTerrainProgram contract,
            dev.vkdisp.glsl.translate.DerivativeTbn.Plan tbnPlan) {
        if (PackTerrainSource.SKYTEXTURED_PROGRAM.equals(program)) {
            // 🔴 日月<b>不能</b>吃 chunk 适配层：那条要 ChunkPosition/UV2 这类日月几何根本没有的属性
            //    （欠供在加载期就是 ShaderCompileException ⇒ 启动砸掉）。走专用的 POSITION_TEX 生成器。
            return celestialAdapter(contract);
        }
        if (PackTerrainSource.SKY_PROGRAM.equals(program)) {
            // 🔴 穹顶走 POSITION 形状的<b>几何适配层</b>（穹顶顶点流只有 Position，无 UV0/ChunkPosition）——
            //    chunk 适配层要的那些属性天空几何根本没有；日月/穹顶各走自己的专用生成器（同一条纪律）。
            return skyGeometryAdapter(contract);
        }
        boolean terrainProgram = PackTerrainSource.TERRAIN_PROGRAM.equals(program);
        boolean fullLight = terrainProgram && dev.vkdisp.VkDispConfig.MRT_TERRAIN_FULL_LIGHT_PROBE.get();
        boolean parallaxSkip = terrainProgram
                && dev.vkdisp.VkDispConfig.MRT_TERRAIN_PARALLAX_SKIP_PROBE.get();
        boolean colorProbe = terrainProgram && dev.vkdisp.VkDispConfig.MRT_TERRAIN_COLOR_PROBE.get();
        if (!terrainProgram && (dev.vkdisp.VkDispConfig.MRT_TERRAIN_FULL_LIGHT_PROBE.get()
                || dev.vkdisp.VkDispConfig.MRT_TERRAIN_PARALLAX_SKIP_PROBE.get()
                || dev.vkdisp.VkDispConfig.MRT_TERRAIN_COLOR_PROBE.get())) {
            VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} 的适配层**不**应用 mrt.terrain*Probe 档"
                    + "（那些是地形单变量实验的判据，静默加到别条程序会让两臂不可比）", program);
        }
        try {
            PackVertexAdapterGenerator.Result adapter = PackVertexAdapterGenerator.generate(
                    contract.inputs(), fullLight, parallaxSkip, colorProbe, tbnPlan);
            for (TranslateDiagnostic diagnostic : adapter.diagnostics()) {
                log(diagnostic);
            }
            return adapter.glsl();
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-003] 顶点适配层生成 FAILED（原文如下）"
                    + " -> 本条 gbuffer 程序不接线（沿用原版 core/terrain）: program=" + program, t);
            return null;
        }
    }

    /**
     * 日月那条程序的顶点源（原版 {@code POSITION_TEX} 顶点流 + 包契约 → 生成的适配层）。
     *
     * &lt;p&gt;🔴 失败 ⇒ {@code null} = 本条不接线（与 chunk 适配层同一条纪律）：日月片元的 uv 决定
     * 采图集哪一块，顶点少一条通道就是「半接线」，而半接线的症状是加载期抛或静默错值，不是「效果差」。
     */
    public static String celestialAdapter(PackTerrainProgram contract) {
        try {
            dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator.Result adapter =
                    dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator.generate(contract.inputs());
            for (TranslateDiagnostic diagnostic : adapter.diagnostics()) {
                log(diagnostic);
            }
            if (adapter.glsl() == null) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} 的日月适配层判否 ⇒ 本条不接线"
                        + "（日月保持原版着色；缺的通道见上面逐条诊断）",
                        PackTerrainSource.SKYTEXTURED_PROGRAM);
            }
            return adapter.glsl();
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] 日月适配层生成 FAILED -> 本条不接线: program={}",
                    PackTerrainSource.SKYTEXTURED_PROGRAM, t);
            return null;
        }
    }

    /**
     * 穹顶那条程序（{@code gbuffers_skybasic}）的顶点源：原版 {@code POSITION} 顶点流 + 包契约 →
     * 生成的<b>几何适配层</b>（{@code Format.POSITION}，逐字见 {@link dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator}）。
     * 失败 ⇒ {@code null} = 本条不接线（与 {@link #celestialAdapter} 同一条「绝不半接」纪律）。
     */
    public static String skyGeometryAdapter(PackTerrainProgram contract) {
        try {
            dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator.Result adapter =
                    dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator.generate(
                            dev.vkdisp.glsl.translate.CelestialVertexAdapterGenerator.Format.POSITION,
                            contract.inputs());
            for (TranslateDiagnostic diagnostic : adapter.diagnostics()) {
                log(diagnostic);
            }
            if (adapter.glsl() == null) {
                VkDisp.LOGGER.warn("vkdisp: [GAP-027] {} 的穹顶几何适配层判否 ⇒ 本条不接线（穹顶保持原版着色）",
                        PackTerrainSource.SKY_PROGRAM);
            }
            return adapter.glsl();
        } catch (Throwable t) {
            VkDisp.LOGGER.error("vkdisp: [GAP-027] 穹顶几何适配层生成 FAILED -> 本条不接线: program={}",
                    PackTerrainSource.SKY_PROGRAM, t);
            return null;
        }
    }

    private static void log(TranslateDiagnostic diagnostic) {
        switch (diagnostic.severity()) {
            case ERROR -> VkDisp.LOGGER.error("vkdisp: gbuffer 顶点源诊断: {}", diagnostic.format());
            case WARN -> VkDisp.LOGGER.warn("vkdisp: gbuffer 顶点源诊断: {}", diagnostic.format());
            case INFO -> VkDisp.LOGGER.info("vkdisp: gbuffer 顶点源诊断: {}", diagnostic.format());
        }
    }
}
