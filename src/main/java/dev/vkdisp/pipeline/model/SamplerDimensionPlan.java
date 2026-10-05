package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】包地形片元的 sampler **维度**绑定决策（修「sampler3D 被喂 2D 视图」的 Vulkan UB）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库自有的 {@link PackTerrainProgram}（同一层事实：包片元要什么）、
 *    {@code dev.vkdisp.bridge.NeutralMaterialMaps} / {@code ShadowStubs}
 *    （同款「类型正确的中性桩」既有实现，MIT 自有代码）；
 *    ② GLSL / Vulkan 的**语言与规范事实**（{@code sampler3D} 的坐标是三维、
 *    描述符类型必须与视图的实际维度匹配）—— 语言事实不受版权保护。
 *    实测依据（本仓库 evidence）：
 *    <ul>
 *      <li>BSL v10.1.8 全包 sampler 声明统计（{@code python} 扫 zip）：
 *          {@code sampler2D} 34 个名字 / {@code sampler2DShadow} 3 个 /
 *          <b>{@code sampler3D} 4 个（{@code lighttex} / {@code lighttex0} / {@code lighttex1} /
 *          {@code voxeltex}）</b>；</li>
 *      <li>原实现把它们落到 {@code default -> atlas}（2D 视图）⇒ 描述符类型不匹配 = UB，
 *          且<b>本机无 validation layer ⇒ 不报错</b>（{@code AGENT_CONTEXT} §9.4.15）。</li>
 *    </ul>
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的纯 Java 决策类）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 的 sampler 维度语义（{@code sampler2D} 收 vec2 坐标、
 *    {@code sampler3D} 收 vec3）与 Vulkan「描述符类型 ↔ 视图维度必须匹配」的规范要求。
 * 2. 备选：
 *    <ul>
 *      <li>① 继续喂方块图集占位 —— <b>否决</b>，这就是要修的 bug：维度不匹配是 UB，
 *          驱动可以丢 draw / 给垃圾 / 无事发生，且我们这台机器上<b>没有任何一层会报错</b>。</li>
 *      <li>② 把这些 sampler 从绑定组里删掉 —— <b>否决</b>：布局多于 SPIR-V 是无害的，
 *          但删掉会让「包声明了它」这件事不可见；而且真正的类型正确解法成本很低
 *          （{@code createTexture} 有 {@code depthOrLayers} 参数）。</li>
 *      <li>③ 按名字硬编码 {@code lighttex0/1 -> 3D} —— <b>否决</b>：那是把「这个包恰好这么叫」
 *          写进产品逻辑，换个包就错（X39：不同包不同程序语义不同，不可套用）。
 *          本类从<b>声明的类型</b>读维度，与名字无关。</li>
 *    </ul>
 * 3. 我们的差异点：把「哪个 sampler 该喂哪种视图」变成**可单测的纯数据决策**，
 *    输入 = 片元里声明的 sampler 名 → 类型，输出 = 每个名字的
 *    {@link Binding}（视图类别 + 一句话理由）。
 *    <ul>
 *      <li>🔖 <b>维度来自声明的类型，不来自名字</b>（见备选③）。</li>
 *      <li>🔖 <b>类型不认识 ⇒ 显式降级并留诊断</b>，不静默当 2D 处理（X9 不猜）。</li>
 *      <li>🔖 <b>3D 桩的缺省值有数学依据</b>：{@code lighttex0} 是 OF 的体积光照贴图，
 *          本引擎没有它 ⇒ 语义等价于「无光照贡献」= 全 0 纹理
 *          （而不是喂图集，那会把图集的颜色当成体积光照强度）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（每次资源重载解析一次）；渲染期只读已解析结果。
 */
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 包地形片元的 sampler 维度 → 视图类别决策（修 Vulkan UB）。
 *
 * <p><b>它修什么</b>：BSL 声明了 4 个 {@code sampler3D}
 * （{@code lighttex} / {@code lighttex0} / {@code lighttex1} / {@code voxeltex}），
 * 而原实现把它们落到 {@code default -> atlas} —— 喂的是 <b>2D</b> 图集视图。
 * 描述符类型与视图维度不匹配在 Vulkan 里是<b>未定义行为</b>，
 * 且本机没装 validation layer ⇒ <b>不报任何错</b>。
 *
 * <p><b>本类只做决策，不碰 GPU</b>：输出是「名字 → 该喂哪一类视图 + 为什么」，
 * 由 {@code bridge} 侧照表取实际视图对象。这样决策可以单测，
 * 而「视图对象真的对不对」由 bridge 侧的桩自证（与 {@code ShadowStubs} 同款纪律）。
 */
public final class SamplerDimensionPlan {

    /** 视图类别（= 桩的种类；每类对应 bridge 侧的一个具体纹理对象）。 */
    public enum ViewKind {
        /** 真值：方块图集（{@code texture_0} / {@code tex} / {@code texture}）。 */
        ATLAS_2D,
        /** 材质贴图中性单位元（{@code specular} / {@code normals}）。 */
        NEUTRAL_MATERIAL_2D,
        /** 阴影深度桩（{@code shadowtex*}，需 2D 深度视图）。 */
        SHADOW_DEPTH_2D,
        /** 阴影颜色桩（{@code shadowcolor*}）。 */
        SHADOW_COLOR_2D,
        /**
         * 🔴 <b>3D 桩</b>（本轮新增）：给 {@code sampler3D} 用。
         * 语义 = 「无体积光照 / 无体素数据」，全 0。
         */
        VOLUME_3D,
        /** 未识别的 2D 占位（图集）；带 WARN，语义不承诺。 */
        PLACEHOLDER_2D,
        /** 未识别的维度：<b>不绑</b>（宁可响亮失败也不喂错类型）。 */
        UNSUPPORTED
    }

    /** 一个 sampler 的绑定决策。 */
    public record Binding(String name, String declaredType, ViewKind kind, String reason) {
        public Binding {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(declaredType, "declaredType");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }

        /** 是否喂得出类型匹配的视图（false ⇒ 调用方必须显式报错，不能绑错类型凑合）。 */
        public boolean bindable() {
            return this.kind != ViewKind.UNSUPPORTED;
        }
    }

    /** 一份完整的决策表（名字有序，便于日志与单测逐条断言）。 */
    public record Plan(List<Binding> bindings, List<String> warnings) {

        public Plan {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 查某个 sampler 的决策；未声明返回空。 */
        public Optional<Binding> binding(String name) {
            for (Binding binding : this.bindings) {
                if (binding.name().equals(name)) {
                    return Optional.of(binding);
                }
            }
            return Optional.empty();
        }

        /** 某个 sampler 的视图类别；未声明 / 不可绑返回 {@link ViewKind#UNSUPPORTED}。 */
        public ViewKind kindOf(String name) {
            return binding(name).map(Binding::kind).orElse(ViewKind.UNSUPPORTED);
        }

        /** 全部不可绑的 sampler 名（调用方必须逐条报错 —— T11）。 */
        public List<String> unsupportedNames() {
            List<String> names = new ArrayList<>();
            for (Binding binding : this.bindings) {
                if (!binding.bindable()) {
                    names.add(binding.name());
                }
            }
            return List.copyOf(names);
        }

        /** 单行摘要（绑定埋点日志用；不逐条刷屏，热路径上只打一次）。 */
        public String summary() {
            Map<ViewKind, Integer> counts = new LinkedHashMap<>();
            for (Binding binding : this.bindings) {
                counts.merge(binding.kind(), 1, Integer::sum);
            }
            StringBuilder text = new StringBuilder();
            for (Map.Entry<ViewKind, Integer> entry : counts.entrySet()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return text.isEmpty() ? "<none>" : text.toString();
        }
    }

    /** {@code uniform samplerXX name;} 的声明（类型必须完整匹配，{@code sampler} 裸声明不接受）。 */
    private static final Pattern SAMPLER_DECL =
            Pattern.compile("uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(sampler\\w*)\\s+([A-Za-z_]\\w*)\\s*;");

    private SamplerDimensionPlan() {
    }

    /**
     * 从<b>转译终稿</b>片元源解析 sampler 声明并产出决策表。
     *
     * <p>🔖 与 {@link PackTerrainProgram#parse} 同口径：只看转译终稿、不看未预处理切片 ——
     * 否则会把死分支（{@code #if defined ADVANCED_MATERIALS}）里的声明也算进来。
     */
    public static Plan fromFragmentSource(String fragmentSource) {
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        Map<String, String> declared = new LinkedHashMap<>();
        int braceDepth = 0;
        for (String rawLine : fragmentSource.split("\\R")) {
            String line = stripComment(rawLine).trim();
            // 块内成员（VkDispBuiltins 的成员名）不是自由声明，必须跳过。
            if (braceDepth == 0) {
                Matcher matcher = SAMPLER_DECL.matcher(line);
                while (matcher.find()) {
                    // 🔴 同名不同类型 = 包自身的不一致 ⇒ 取**先出现**的并留警告，
                    //   不静默取最后一个（后者会让「取哪条」变成不可复算的偶然，X9）。
                    String previous = declared.putIfAbsent(matcher.group(2), matcher.group(1));
                    if (previous != null && !previous.equals(matcher.group(1))) {
                        return planWith(declared, "包把 sampler '" + matcher.group(2)
                                + "' 声明成两种类型（" + previous + " / " + matcher.group(1)
                                + "）⇒ 取先出现的那条；这是包自身的不一致，请向包作者报告");
                    }
                }
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth = Math.max(0, braceDepth - 1);
                }
            }
        }
        return planWith(declared, null);
    }

    /** 由「名字 → 声明类型」表产出决策表（包级可见：单测与诊断可直接构造）。 */
    public static Plan fromDeclaredTypes(Map<String, String> declaredTypes) {
        Objects.requireNonNull(declaredTypes, "declaredTypes");
        return planWith(declaredTypes, null);
    }

    private static Plan planWith(Map<String, String> declared, String extraWarning) {
        List<Binding> bindings = new ArrayList<>(declared.size());
        List<String> warnings = new ArrayList<>();
        if (extraWarning != null) {
            warnings.add(extraWarning);
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            bindings.add(decide(entry.getKey(), entry.getValue(), warnings));
        }
        return new Plan(bindings, warnings);
    }

    /**
     * 名字规则（只选**来源**）。
     *
     * <p>🔖 <b>为什么它只能选来源、不能选维度</b>：GAP-012 的核心主张是
     * 「维度来自**声明的类型**」。旧实现让名字无条件压过类型，
     * 于是 `sampler3D shadowtex0` 会被喂一张 2D 深度图 —— 正是同一个 bug。
     * 现在 {@link #declaredNon2D} 先把非 2D 声明挡在门外。
     *
     * @return 命中的绑定；未命中返回 {@code null}（由调用方走类型路径）
     */
    private static Binding byName(String name, String type) {
        return switch (name) {
            // texture_0 恒为方块图集；specular/normals 是 LabPBR 那对 atlas；
            // shadowtex* 是阴影贴图；shadowcolor0 是阴影颜色贴图。
            case "texture_0", "texture", "tex" ->
                    new Binding(name, type, ViewKind.ATLAS_2D, "方块图集（真值）");
            case "specular", "normals" ->
                    new Binding(name, type, ViewKind.NEUTRAL_MATERIAL_2D,
                            "包要的逐方块材质贴图集本引擎没有 ⇒ 绑乘法单位元（GAP-009）");
            case "shadowtex", "shadowtex0", "shadowtex1" ->
                    new Binding(name, type, ViewKind.SHADOW_DEPTH_2D,
                            "绑专用 1x1 D32 桩（**不得**绑本 pass 的读写深度附件：Vulkan UB）");
            case "shadowcolor0" ->
                    new Binding(name, type, ViewKind.SHADOW_COLOR_2D, "绑专用 1x1 RGBA8 桩");
            default -> null;
        };
    }

    /**
     * 声明的类型是否蕴含**非 2D** 维度（h39）。
     *
     * <p>🔖 裸 `sampler`（无后缀）返回 {@code false}：维度未知时不猜（X9），
     * 但也不因此剥夺名字规则 —— 既有行为保持不变。
     */
    private static boolean declaredNon2D(String name, String declaredType) {
        String n = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT);
        return n.equals("sampler3d")
                || n.startsWith("sampler2darray")
                || n.startsWith("samplercube");
    }

    /** 单个 sampler 的决策（纯函数；包级可见便于单测逐条断言）。 */
    static Binding decide(String name, String declaredType, List<String> warnings) {
        String type = declaredType == null ? "" : declaredType;
        String normalized = type.toLowerCase(Locale.ROOT);
        // 🔖 名字只选**来源**；**声明类型约束维度**（h39）。
        //   非 2D 声明不走名字规则 ⇒ 直接落类型路径，由它决定。
        //   （裸 sampler 与 2D 声明仍走名字规则，维持既有行为。）
        if (!declaredNon2D(name, declaredType)) {
            Binding byName = byName(name, type);
            if (byName != null) {
                return byName;
            }
        }
        // 🔴 本轮的核心修复：维度来自**声明的类型**。
        if (normalized.equals("sampler3d")) {
            return new Binding(name, type, ViewKind.VOLUME_3D,
                    "sampler3D（OF 体积光照 / 体素贴图）⇒ 必须绑 3D 视图；"
                            + "喂 2D 视图是描述符类型不匹配 = Vulkan UB 且不报错");
        }
        if (normalized.equals("sampler2d") || normalized.equals("sampler2dshadow")
                || normalized.equals("sampler2darray")
                || normalized.equals("sampler2darrayshadow")) {
            return new Binding(name, type, ViewKind.PLACEHOLDER_2D,
                    "2D 采样器但无真值来源 ⇒ 绑方块图集占位（语义不承诺）");
        }
        if (normalized.equals("samplercube") || normalized.equals("samplercubeshadow")) {
            warnings.add("sampler '" + name + "' 声明为 " + type
                    + "（立方体采样器），本引擎暂无类型匹配的桩 ⇒ 未绑定（宁可响亮失败，"
                    + "也不拿 2D 视图冒充，那会静默产生错误画面）");
            return new Binding(name, type, ViewKind.UNSUPPORTED, "cube 采样器暂无类型匹配的桩");
        }
        // 🔶 裸 `sampler`（无维度后缀）或任何不认识的后缀 ⇒ 不猜（X9）。
        warnings.add("sampler '" + name + "' 的类型 '" + type
                + "' 不认识 ⇒ 未绑定（不猜维度：喂错类型 = 静默 UB）");
        return new Binding(name, type, ViewKind.UNSUPPORTED, "未识别的 sampler 类型，不猜维度");
    }

    /** 去行注释与块注释头（与 {@link PackTerrainProgram} 同口径）。 */
    private static String stripComment(String line) {
        int slash = line.indexOf("//");
        int block = line.indexOf("/*");
        int cut = line.length();
        if (slash >= 0) {
            cut = Math.min(cut, slash);
        }
        if (block >= 0) {
            cut = Math.min(cut, block);
        }
        return line.substring(0, cut);
    }

    /** 便于日志打印的一行摘要（绑定期只打一次，不逐帧刷屏）。 */
    public static String describe(Plan plan) {
        StringBuilder text = new StringBuilder(plan.summary());
        if (!plan.warnings().isEmpty()) {
            text.append(" warnings=").append(plan.warnings());
        }
        return text.toString();
    }
}
