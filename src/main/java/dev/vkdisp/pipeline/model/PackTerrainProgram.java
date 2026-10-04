package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】GAP-003「包的地形片元接到派生 MRT 管线」的接口契约表 / 只观察本项目自产的转译终稿
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 {@code dev.vkdisp.glsl.translate} 8 段流水线的**产物格式**
 *    （{@code FragmentOutputAdapter} 合成的 {@code layout(location=N) out vec4 vkdispFragOutN;}
 *    与 {@code UniformInjector} 收编出的 {@code layout(std140) uniform VkDispBuiltins { … }}）
 *    —— 同为 MIT 自有代码，无任何外部参考对象，故不存在许可证问题。
 *    另参考本仓库 docs/13-GAP-REGISTRY.md 的 GAP-003 行与 evidence/h06、h07 的实测口径。
 *    → 能否并入本项目（MIT）：可以（本文件即被参考方本身）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码，也不含任何 Mojang 着色器文本
 * 1. 官方/主实现：无（原版没有「包片元契约」这个概念 —— 它是本项目把 OF 方言落到
 *    Vulkan 管线接口时**必须显式声明**的一层事实）。
 * 2. 备选：① 在注册管线处现解析文本 —— 否决：解析失败会在资源加载期抛，且无法单测；
 *    ② 复用 composite 那套硬编码 sampler 清单 —— 否决：那是 composite/deferred 的收编集，
 *    地形片的收编集是**另一批**（h06 实测），拿别的程序的清单套自己的包
 *    就是 X39「不同程序语义不同，不可套用」。
 * 3. 我们的差异点：把「这个包的地形片元到底要什么」变成**可单测的纯数据**（输出数 /
 *    自由 sampler 名 / 输入 varying 签名），让「附件数该是多少、绑定组该登记哪些条目、
 *    顶点适配层该产出哪些 varying」三件事**各有唯一真源**，而不是散落字面量。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：冷路径（每次资源重载解析一次）；渲染期零开销（只读已解析结果）。
 */
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「所选包的 gbuffers_terrain 片元」对渲染侧提出的**全部要求**（纯数据，零原版类型依赖）。
 *
 * <p><b>为什么必须有它</b>（本轮踩坑链的收敛）：整包产物里**已经有**包地形片元的 SPIR-V
 * （h07 实测 stages=190 ok=190 failed=0），但地形 draw 用的仍是原版 core/terrain
 * ⇒ 缺的不是「能不能编译」，而是**「管线要按它的形状声明什么」**。这三条在 Vulkan 上都是硬约束：
 * <ol>
 *   <li><b>颜色目标数</b>必须等于它的输出数 —— 多一个少一个，setPipeline 直接抛
 *       IllegalStateException **崩客户端**（X42）。</li>
 *   <li><b>绑定组布局</b>必须逐条登记它自由声明的 sampler —— STRICT_VALIDATION 下
 *       draw() 按**布局**校验，少一条即抛 Missing uniform。</li>
 *   <li><b>顶点侧</b>必须产出它 layout(location=N) in 的每一个 varying —— 位置对不上即链接失败。</li>
 * </ol>
 * 三条各有各的真源，散落字面量必然漂移 ⇒ 本类把它们一次解析、一次冻结。
 *
 * <p><b>解析口径</b>：只看**转译终稿**（预处理 + OF 转译之后），不看未预处理切片 ——
 * 后者会把死分支（#if defined ADVANCED_MATERIALS）里的声明也算进来，于是「能力上限」
 * 被误当成「生产实际」（h06/h07 的核心教训，见 TerrainProductionOutputCountTest）。
 */
public record PackTerrainProgram(
        String packName,
        String qualifiedName,
        String fragmentSource,
        int outputCount,
        List<String> fragmentSamplers,
        List<Input> inputs) {

    /** OF 内建块名（与 UniformInjector / PipelineApi.BUILTINS_UNIFORM 同名）。 */
    public static final String BUILTINS_BLOCK = "VkDispBuiltins";

    /** 输入 varying 签名（layout(location = N) in &lt;type&gt; &lt;name&gt;;）。 */
    public record Input(int location, String type, String name) {
        public Input {
            if (location < 0) {
                throw new IllegalArgumentException("varying location 不可为负: " + location);
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
        }
    }

    /** 归一构造：列表冻结、输出数至少 1（片元至少一个颜色输出，契约才成立）。 */
    public PackTerrainProgram {
        Objects.requireNonNull(packName, "packName");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        if (fragmentSource.isBlank()) {
            throw new IllegalArgumentException("vkdisp: 地形片元源不许为空白（" + qualifiedName + "）");
        }
        if (outputCount < 1) {
            throw new IllegalArgumentException(
                    "vkdisp: 地形片元输出数至少为 1（" + qualifiedName + " 解析得 " + outputCount + "）");
        }
        fragmentSamplers = fragmentSamplers == null ? List.of() : List.copyOf(fragmentSamplers);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    /**
     * 绑定组布局需要的「内建块 + 全部自由 sampler」名（顺序稳定：块在前、sampler 按源序）。
     *
     * <p>顺序沿 P-1f ② 的既有口径：内建块在源中最靠前 ⇒ 块在前、采样器随后。
     */
    public List<String> bindGroupUniformNames() {
        List<String> names = new ArrayList<>(fragmentSamplers.size() + 1);
        names.add(BUILTINS_BLOCK);
        names.addAll(fragmentSamplers);
        return List.copyOf(names);
    }

    /** 该片元是否自由声明了某个 sampler（绑定期的「谁需要占位视图」判据）。 */
    public boolean declaresSampler(String name) {
        return fragmentSamplers.contains(name);
    }

    /** 是否自由声明了某个 varying（顶点适配层的「这条要不要供值」判据）。 */
    public boolean declaresInput(String name) {
        return inputs.stream().anyMatch(input -> input.name().equals(name));
    }

    /**
     * 解析一个**转译终稿**片元源，产出契约。
     *
     * @param packName        产出它的包名（纯记录）
     * @param qualifiedName   程序全名（如 world0/gbuffers_terrain；日志对账用）
     * @param fragmentSource  转译终稿全文（预处理 + OF 转译之后）
     * @throws IllegalArgumentException 解析不出任何输出时（= 契约不成立，显式失败，T11）
     */
    public static PackTerrainProgram parse(String packName, String qualifiedName, String fragmentSource) {
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        int maxLocation = -1;
        List<Input> inputs = new ArrayList<>();
        Set<String> samplers = new LinkedHashSet<>();
        int braceDepth = 0;
        for (String raw : fragmentSource.split("\\R")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            // 块内成员（VkDispBuiltins 的四十多个成员）不是自由声明，必须跳过；
            // 否则会把 cameraPosition 之类的成员名误当成 sampler 名登记进绑定组。
            if (braceDepth == 0) {
                // 一行里可能有**多个**声明 —— 实际 BSL 地形片元就是这种两两并排的写法：
                //   layout(location = 0) in float mat; layout(location = 1) in float recolor;
                // 首版解析器按行首锚定只认第一个，结果 location 0 被错配成 recolor、
                // location 2 被错配成 lmCoord，静默少认 4 个 varying（首次实现时实测）。
                // ⇒ 逐个声明 findAll，不按行取首个。
                Matcher out = LOCATION_OUT.matcher(line);
                while (out.find()) {
                    maxLocation = Math.max(maxLocation, Integer.parseInt(out.group(1)));
                }
                Matcher in = LOCATION_IN.matcher(line);
                while (in.find()) {
                    inputs.add(new Input(Integer.parseInt(in.group(1)), in.group(2), in.group(3)));
                }
                Matcher sampler = SAMPLER_UNIFORM.matcher(line);
                boolean sawSampler = false;
                while (sampler.find()) {
                    samplers.add(sampler.group(1));
                    sawSampler = true;
                }
                // 声明了 sampler 却一个名字都没解析出来 = 形状不认识，显式失败而不是漏掉（X9/T11）。
                if (!sawSampler && line.startsWith("uniform sampler")) {
                    throw new IllegalArgumentException(
                            "vkdisp: 无法解析 sampler 声明（不猜，X9）: " + line);
                }
            }
            // 花括号深度只看块的起止：'{' 与 '}' 同行出现（如 uniform X { ... };）也正确配平。
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth = Math.max(0, braceDepth - 1);
                }
            }
        }
        if (maxLocation < 0) {
            throw new IllegalArgumentException(
                    "vkdisp: " + qualifiedName + " 的转译终稿里找不到任何"
                            + " layout(location = N) out vec4 —— 契约不成立（拒绝猜测，X9）");
        }
        inputs.sort(Comparator.comparingInt(Input::location));
        return new PackTerrainProgram(packName, qualifiedName, fragmentSource,
                maxLocation + 1, List.copyOf(samplers), inputs);
    }

    private static final Pattern LOCATION_IN =
            Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+"
                    + "([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern LOCATION_OUT =
            Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\b");
    private static final Pattern SAMPLER_UNIFORM =
            Pattern.compile("uniform\\s+sampler\\w*\\s+([A-Za-z_]\\w*)\\s*;");

    /** 去行注释与块注释头（源里两种注释都有）。 */
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
}
