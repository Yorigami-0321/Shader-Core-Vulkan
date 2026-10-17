package dev.vkdisp.pack.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【参考调研】程序兜底树（移植件）
 * 0. 合规核对（第 0 步闸门）：
 *    上游 = Vitrail {@code dev/vitrail/pack/model/ProgramFallbacks.java:1-161} @ 4a45f44…（LGPL-3.0，
 *    读取日期 2026-10-17）；**该文件自承派生自 Iris**（Vitrail {@code NOTICE} 第 20-23 行原文：
 *    「contains the gbuffers, shadow and Distant Horizons fallback table, adapted in 2026 from the enum
 *    {@code net.irisshaders.iris.shaderpack.loading.ProgramId}」，Iris 侧读取日期 2026-08-01）
 *    ⇒ 台账记两条上游（Vitrail + Iris），本项目与两者同为 LGPL-3.0 族。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以，逐文件进 {@code NOTICE.md} 移植台账（§〇 P1）。
 *    → 例外条款：无。
 * 1. 官方/主实现：Iris {@code ProgramId}（父链 + 混合覆盖挂在同名枚举上）；OptiFine 文档不铺开这条链。
 * 3. 我们的差异点：包名/缩进 + 本注释块；表体（父链、{@code gbuffers_clouds} 那条两权威不一致的边、
 *    DH 族、唯一一条非拒绝的混合覆盖）与 {@link #chain} 的**环路防护逐字保留**。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；本文件为 Vitrail 移植件，其 Iris 来源已在台账注明。
 * 5. 性能基线：❄️ 冷路径。
 *
 * <p>包没带某个程序时，由哪个程序接手。
 *
 * <p>不指望包把这些全写出来。包写 {@code gbuffers_terrain}，凡是画得像地形的都从它继承，
 * 所以这条链不是错误路径：它是这套格式本来的用法，一个只有四个程序的包是个能用的包。
 *
 * <p>父表取自本格式的参考实现 Iris。原品是枚举 {@code net.irisshaders.iris.shaderpack.loading.ProgramId}
 * （2026-08-01 读）。2026 年的改动：枚举变名→父映射；原枚举带的 public API 映射被去掉，
 * 八条混合覆盖里去掉七条 —— 那七条是 shadow 族各自带的 {@code BlendModeOverride.OFF}
 * （{@code shaderpack/loading/ProgramId.java:13-19}），{@code EntityProgram} 对整个 shadow 族一次性兑现它们，
 * 而不是逐个名字；{@link #blendOverride} 带的是第八条，也是唯一一条不是「拒绝」的。
 * 之所以复现而不是猜：没有别的权威 —— 格式文档没把这条链写出来，边角写错就意味着某个东西被错误的程序画了且没人报。
 */
public final class ProgramFallbacks {

    private static final Map<String, String> PARENTS = buildParents();

    private static final Map<String, BlendMode> BLEND_OVERRIDES = buildBlendOverrides();

    private ProgramFallbacks() {
    }

    private static Map<String, String> buildParents() {
        Map<String, String> parents = new LinkedHashMap<>();

        // shadow 族。除 lightning 外全部兜底到那一个 shadow 程序，lightning 先是实体。
        parents.put("shadow", null);
        parents.put("shadow_solid", "shadow");
        parents.put("shadow_cutout", "shadow");
        parents.put("shadow_water", "shadow");
        parents.put("shadow_entities", "shadow");
        parents.put("shadow_lightning", "shadow_entities");
        parents.put("shadow_block", "shadow");

        // gbuffers 族。两个根：basic 给无纹理几何，textured_lit 给一切被世界照亮的东西。
        parents.put("gbuffers_basic", null);
        parents.put("gbuffers_line", "gbuffers_basic");
        parents.put("gbuffers_textured", "gbuffers_basic");
        parents.put("gbuffers_textured_lit", "gbuffers_textured");
        parents.put("gbuffers_skybasic", "gbuffers_basic");
        parents.put("gbuffers_skytextured", "gbuffers_textured");
        // 两个权威唯一不一致的那个边：Iris 把云指向 textured，OptiFine 自己的文档则不给它任何兜底。
        // 这里 Iris 赢，因为它才是包实际照着写的那个实现；但哪天云画错了，
        // 「这个包根本没带云的程序」是要先看的第一种情况。
        parents.put("gbuffers_clouds", "gbuffers_textured");
        parents.put("gbuffers_terrain", "gbuffers_textured_lit");
        parents.put("gbuffers_terrain_solid", "gbuffers_terrain");
        parents.put("gbuffers_terrain_cutout", "gbuffers_terrain");
        parents.put("gbuffers_damagedblock", "gbuffers_terrain");
        parents.put("gbuffers_block", "gbuffers_terrain");
        parents.put("gbuffers_block_translucent", "gbuffers_block");
        parents.put("gbuffers_beaconbeam", "gbuffers_textured");
        parents.put("gbuffers_item", "gbuffers_textured_lit");
        parents.put("gbuffers_entities", "gbuffers_textured_lit");
        parents.put("gbuffers_entities_translucent", "gbuffers_entities");
        parents.put("gbuffers_entities_glowing", "gbuffers_entities");
        parents.put("gbuffers_lightning", "gbuffers_entities");
        parents.put("gbuffers_particles", "gbuffers_textured_lit");
        parents.put("gbuffers_particles_translucent", "gbuffers_particles");
        parents.put("gbuffers_armor_glint", "gbuffers_textured");
        parents.put("gbuffers_spidereyes", "gbuffers_textured");
        parents.put("gbuffers_hand", "gbuffers_textured_lit");
        parents.put("gbuffers_weather", "gbuffers_textured_lit");
        parents.put("gbuffers_water", "gbuffers_terrain");
        parents.put("gbuffers_hand_water", "gbuffers_hand");

        // Distant Horizons。它的 shadow 程序自立，不并入上面的 shadow 族。
        parents.put("dh_terrain", null);
        parents.put("dh_water", "dh_terrain");
        parents.put("dh_generic", "dh_terrain");
        parents.put("dh_shadow", null);

        // 合成没有任何兜底。缺一个 composite pass 就是那个 pass 不跑，这对包来说是想达到的正常事。
        parents.put("final", null);

        // 不是 Map.copyOf：树的根映射到 null，而不可变工厂拒绝 null 值而不是存它。
        return Collections.unmodifiableMap(parents);
    }

    /**
     * 包没为自己的程序点名混合方式时，该程序按什么混合。
     *
     * <p>只有一项，而这张表存在（而不是一个常量）是因为形状是 Iris 的：覆盖挂在程序**名字**上，
     * 与上面的父链来自同一个枚举（{@code shaderpack/loading/ProgramId.java:47-48}），
     * 所以第二条会是这里的另一行，而不是某个调用点的又一个特例。
     *
     * <p>🔴 <strong>键是「被问到的名字」，不是「最终服务它的那个文件</strong>，这正是它不能住在包的指令旁边的原因。
     * Iris 刻意从两个不同地方读这两样：包的 {@code blend.<program>} 按**解析后来源**的名字查，
     * 而这个默认按**被问的 id**查（{@code pipeline/programs/ShaderCreator.java:71}）⇒
     * 一个根本没带 {@code gbuffers_spidereyes} 的包，仍会经由它兜底到的 {@code gbuffers_textured}
     * 把眼睛加性地画出来。
     */
    private static Map<String, BlendMode> buildBlendOverrides() {
        Map<String, BlendMode> overrides = new LinkedHashMap<>();

        // 加性，也正是「让眼睛是光源而不是贴画」的那一条：源按自身 alpha 加权、目标完整保留，
        // 于是瞳孔是**加**在怪物皮肤上而不是替换它。游戏自己的管线按普通半透明混合，
        // 这正是本条顶掉的那个值；alpha 保持 ZERO, ONE，理由与 Iris 给它的相同。
        overrides.put("gbuffers_spidereyes", new BlendMode(false, "SRC_ALPHA", "ONE", "ZERO", "ONE"));

        return Collections.unmodifiableMap(overrides);
    }

    /** 包没为自己的程序点名混合方式时的兜底混合，或 null（表示调用方自己的默认值成立）。 */
    public static BlendMode blendOverride(String program) {
        return BLEND_OVERRIDES.get(program);
    }

    /** 参与兜底树的全部程序名。 */
    public static Set<String> names() {
        return PARENTS.keySet();
    }

    /**
     * 要尝试的程序序列，从被问的那个开始。走这张表取第一个包确实带了的，就是解析的全部。
     */
    public static List<String> chain(String program) {
        List<String> chain = new ArrayList<>();
        String current = program;

        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = PARENTS.get(current);
        }

        return List.copyOf(chain);
    }
}
