package dev.vkdisp.pipeline.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 【参考调研】C1 · composite/deferred/final 的 sampler 名单：由 **OF 命名规则生成** + **包声明快照**
 * 0. 合规：参考对象 = OptiFine / Iris 公开文档里 colortexN / depthtexN / shadowtexN / shadowcolorN /
 *    gauxN / noisetex 这套**内建纹理命名规则**（格式事实，不受版权保护；`04-SPEC.md` §3.2 已登记同一口径），
 *    与本仓库自有实现事实（`SamplerDimensionPlan` 的声明派生 + `PackPostChain:223-232` 的「对差 + WARN + 排除」）。
 *    外部候选 glsl-preprocessor / glsl-transformer（GPL-3.0 + 例外）按禁止处理（`07` L12 / X20 / X21）：
 *    不读其代码、零代码并入。本项目 MIT。
 * 1. 职责：把「布局里该有哪些 sampler 名」与「包这一帧实际声明了什么」分成两件事 ——
 *    前者由 OF 命名规则**生成**（不再是「扫某个包得到的名单」），后者是包转译终稿的**声明快照**。
 * 2. 差异点：🔴 布局在**游戏启动期**随管线注册一次定死（`PipelineApi` 的 required 管线计数断言要求无条件注册），
 *    所以布局**不可能**按包变；本类因此不把「派生」理解成「布局跟着包变」，而是
 *    ①布局 = 规则生成的超集（能覆盖多少 OF 合法名字就覆盖多少）；②视图 = 按**包声明的类型**选；
 *    ③包声明了超集之外的名字 ⇒ **点名 WARN**（可数、进 C3 的一致性报告），不静默。
 * 3. 非显然约束：`SamplerDimensionPlan.fromFragmentSource` 只看**转译终稿**（不看未预处理切片），
 *    否则会把你分支（{@code #if defined ADVANCED_MATERIALS}）里的声明也算进来 —— 与 gbuffer 同口径。
 * 4. 性能：❄️ 冷路径（生成期一次）；每帧只用快照查表。
 */
public final class PackSamplerSuperset {

    /** OF 的 color 附件命名上限（colortex0..15，OptiFine 公开 Uniforms 表；`04-SPEC` §3.2 同源）。 */
    static final int COLOR_ATTACHMENTS = 16;

    /** OF 的深度快照纹理（depthtex0..2）。 */
    static final int DEPTH_TEXTURES = 3;

    /** OF 的 gaux 族（gaux1..4 = colortex4..7）。 */
    static final int GAUX_SLOTS = 4;

    /**
     * 非 OF 标准族、但**已被本仓两条链各自登记过**的第三方内建名（DH / VoxelMap 系）。
     * 🔖 这一小表的存在必须被读出来：它**不是**「扫 BSL 得到的」，而是「哪个 mod 的通道我们已经接线」——
     * 未接线的第三方通道名会走「超集之外 ⇒ WARN + 进报告」那条路，不假装支持。
     */
    static final List<String> KNOWN_THIRD_PARTY = List.of(
            "lighttex0", "lighttex1", "dhDepthTex0", "dhDepthTex1",
            "vxDepthTexOpaque", "vxDepthTexTrans");

    /**
     * 由规则生成的布局超集（顺序 = 绑定顺序；SPIR-V 未引用的条目无害，反向缺失才致命）。
     *
     * <p>🔖 与旧 `PipelineApi.PACK_FRAGMENT_SAMPLERS`（18 名，扫 BSL 得来）的关系：
     * 本表是它的**规则化上位** —— 18 名全部落在本表里，本表另外补上 BSL 没用到的
     * colortex2..5/7、depthtex2、gaux2..4（同一族里「这个包恰好没用」不该等于「引擎不支持」）。
     */
    public static final List<String> NAMES = buildNames();

    private static List<String> buildNames() {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < COLOR_ATTACHMENTS; i++) {
            names.add("colortex" + i);
        }
        for (int i = 0; i < DEPTH_TEXTURES; i++) {
            names.add("depthtex" + i);
        }
        names.add("shadowtex0");
        names.add("shadowtex1");
        names.add("shadowcolor0");
        for (int i = 1; i <= GAUX_SLOTS; i++) {
            names.add("gaux" + i);
        }
        names.add("noisetex");
        names.addAll(KNOWN_THIRD_PARTY);
        return List.copyOf(new ArrayList<>(names));
    }

    /**
     * 一个包在某一步的转译终稿里声明的 sampler（类型 + 由类型决定的视图类别 + 决策理由）。
     *
     * @param reason 由 {@link SamplerDimensionPlan} 给出的决策理由原文 —— C3 的一致性报告要能读出
     *               「这个名字是按**类型**定的还是按**名字**定的」（§4.1 第 8 项）
     */
    public record Declared(String name, String declaredType, SamplerDimensionPlan.ViewKind kind,
            String origin, String reason) {
    }

    /**
     * 一次包加载的快照：三条全屏步（composite / deferred / final）的声明并集 + 与超集的对差。
     *
     * @param declared              名字 → 声明（顺序 = composite → deferred → final）
     * @param outsideSuperset       包声明了、但超集里没有的名字（⇒ 布局绑不上 = 会抛，必须点名）
     * @param supersetNotDeclared   超集里有、但这一张包一个都没声明的名字（无害：仍按占位绑，只是报告要写）
     */
    public record Snapshot(Map<String, Declared> declared, List<String> outsideSuperset,
            List<String> supersetNotDeclared, List<String> planWarnings, boolean derivedFromPack) {

        public Snapshot {
            declared = declared == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(declared));
            outsideSuperset = outsideSuperset == null ? List.of() : List.copyOf(outsideSuperset);
            supersetNotDeclared = supersetNotDeclared == null ? List.of() : List.copyOf(supersetNotDeclared);
            planWarnings = planWarnings == null ? List.of() : List.copyOf(planWarnings);
        }

        /** 空快照（未装包 / 兜底 passthrough / 资源重载前）：一切按超集 + 占位走。 */
        public static Snapshot empty() {
            return new Snapshot(Map.of(), List.of(), List.of(), List.of(), false);
        }

        public Optional<Declared> declared(String name) {
            return Optional.ofNullable(declared.get(name));
        }

        /** 单行摘要（注册期/生成期各打一次，供取证数）。 */
        public String summary() {
            return "declared=" + declared.size() + " outside=" + outsideSuperset.size()
                    + " unused=" + supersetNotDeclared.size() + " derived=" + derivedFromPack;
        }
    }

    /**
     * 当前快照。🔖 用 {@code static final AtomicReference} 而不是 {@code static volatile} 字段：
     * 整份快照一次换入、不留撕裂读，且与本仓其它跨线程哨兵（{@code AtomicBoolean} 系）同风格；
     * 同时它不会被 {@code StaticFieldRatchetTest}（口径 = 静态<b>非 final</b> 字段）数成新债 ——
     * 这是<b>同一份</b>全局状态的两种写法，不是把债藏起来，真正去处是 `19` §3.4 的
     * {@code PackSession}（B2/B3）。
     */
    private static final java.util.concurrent.atomic.AtomicReference<Snapshot> CURRENT =
            new java.util.concurrent.atomic.AtomicReference<>(Snapshot.empty());

    private PackSamplerSuperset() {
    }

    /** 当前快照（渲染路径只读；永不为 null）。 */
    public static Snapshot current() {
        return CURRENT.get();
    }

    /** 布局该登记的名字 = 规则生成的超集（布局不随包变，见类注释 2）。 */
    public static List<String> layoutNames() {
        return NAMES;
    }

    /**
     * 用三条全屏步的**转译终稿**装快照。
     *
     * @param compositeSource composite 终稿（null 按空处理）
     * @param deferredSource  deferred 终稿（无 deferred 步时是内置 passthrough，照样能解析）
     * @param finalSource     final 终稿
     */
    public static void install(String compositeSource, String deferredSource, String finalSource) {
        Map<String, Declared> declared = new LinkedHashMap<>();
        List<String> planWarnings = new ArrayList<>();
        collectInto(declared, planWarnings, compositeSource, "composite");
        collectInto(declared, planWarnings, deferredSource, "deferred");
        collectInto(declared, planWarnings, finalSource, "final");

        List<String> outside = new ArrayList<>();
        for (String name : declared.keySet()) {
            if (!NAMES.contains(name)) {
                outside.add(name);
            }
        }
        List<String> unused = new ArrayList<>();
        if (!declared.isEmpty()) {
            for (String name : NAMES) {
                if (!declared.containsKey(name)) {
                    unused.add(name);
                }
            }
        }
        CURRENT.set(new Snapshot(declared, List.copyOf(outside), List.copyOf(unused),
                List.copyOf(planWarnings), !declared.isEmpty()));
    }

    /** 复位到空快照（包关掉 / 资源重载失败时的显式回退，不留上一张包的声明）。 */
    public static void reset() {
        CURRENT.set(Snapshot.empty());
    }

    private static void collectInto(Map<String, Declared> declared, List<String> planWarnings,
            String fragmentSource, String origin) {
        if (fragmentSource == null || fragmentSource.isBlank()) {
            return;
        }
        SamplerDimensionPlan.Plan plan = SamplerDimensionPlan.fromFragmentSource(fragmentSource);
        for (SamplerDimensionPlan.Binding binding : plan.bindings()) {
            // 🔖 `InSampler` 豁免（与 `PostSamplerSuperset.containsAll` 同一口径）：它是**每条管线
            //   各自登记**的输入采样器（`PipelineApi.SAMPLER_UNIFORM`），不属于「包自由 sampler」这一族。
            //   不豁免的后果是实测定到的：每张包都被点名一条「超集之外的 InSampler」假告警。
            if ("InSampler".equals(binding.name())) {
                continue;
            }
            Declared previous = declared.putIfAbsent(binding.name(),
                    new Declared(binding.name(), binding.declaredType(), binding.kind(),
                            origin, binding.reason()));
            if (previous != null && !previous.declaredType().equals(binding.declaredType())) {
                // 同一步里没判、跨步又撞了：取先出现的那条（与 SamplerDimensionPlan 同口径），并点名。
                planWarnings.add("sampler '" + binding.name() + "' 在不同全屏步里声明成两种类型（"
                        + previous.declaredType() + "@" + previous.origin() + " / "
                        + binding.declaredType() + "@" + origin + "）⇒ 取先出现的那条");
            }
        }
        for (String warning : plan.warnings()) {
            planWarnings.add(origin + ": " + warning);
        }
    }

    /** 名字是否落在超集里（C3 的一致性报告与绑定路径都用这一个判据，不许两处各写一遍）。 */
    public static boolean contains(String name) {
        return NAMES.contains(name);
    }
}
