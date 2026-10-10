package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 GAP-030：用<b>真实 BSL v10.1.8</b> 验「链各级顶点接的是包自己的 post 顶点程序」，
 * 并把三条引擎硬约束在源文本层面逐条对账（口径同 {@code PostChainBslTest}：
 * 生产实际值 ≠ 能力上限，也不许拿 fixture 的数字当真包的数字）。
 *
 * <p>本类同时是<b>探索用的打印</b>：第一轮就是靠它把 BSL 转译终稿里的真实属性名 / varying 顺序 /
 * VkDispBuiltins 成员读出来，而不是按文档猜（X9）。
 */
class PostChainPackVertexTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    /** {@code layout(location = N) out <type> <name>;} */
    private static final Pattern VS_OUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+out\\s+(\\w+)\\s+(\\w+)\\s*;");

    /** {@code layout(location = N) in <type> <name>;}（片元侧的输入契约）。 */
    private static final Pattern FS_IN = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s+in\\s+(\\w+)\\s+(\\w+)\\s*;");

    private static PackPostChain.Chain bslChain() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        String bslName = ShaderPackScanner.scan(INVENTORY).packs().stream()
                .map(ShaderPackScanner.DiscoveredPack::name)
                .filter(n -> n.startsWith("BSL_v10.1.8"))
                .findFirst().orElse(null);
        assertNotNull(bslName, "没扫到 BSL_v10.1.8");
        PackCompositeSource.Result result = PackCompositeSource.generate(INVENTORY, "", bslName, null);
        assertFalse(result.fallback(), "BSL 不该走兜底；诊断: " + result.diagnostics());
        return result.chain();
    }

    @Test
    @DisplayName("🔴 BSL 整链：每一级的顶点都是包自己写的（零级回落适配层）")
    void everyBslChainPassCarriesItsOwnVertexSource() {
        PackPostChain.Chain chain = bslChain();
        List<String> fellBack = new ArrayList<>();
        for (PackPostChain.Pass pass : chain.passes()) {
            if (!pass.hasPackVertexSource()) {
                fellBack.add(pass.programName());
            }
        }
        assertTrue(fellBack.isEmpty(),
                () -> "BSL 每个全屏步都有 .vsh，回落适配层的槽 = 本轮没接上：" + fellBack
                        + "（链=" + chain.passes().stream().map(PackPostChain.Pass::programName).toList()
                        + "）");
    }

    @Test
    @DisplayName("🔴 BSL 顶点源：ftransform() 走恒等档，不许出现投影×视图乘积")
    void bslVertexSourcesUseTheIdentityProjection() {
        for (PackPostChain.Pass pass : bslChain().passes()) {
            String vertex = pass.vertexSource();
            assertNotNull(vertex, pass.programName() + " 没有顶点源");
            assertFalse(vertex.contains("gbufferProjection * gbufferModelView"),
                    () -> "post 程序 " + pass.programName() + " 的 gl_Position 还在乘相机矩阵"
                            + "（GAP-030 的恒等档没生效）");
            assertTrue(vertex.contains("vec4(Position, 1.0)") || vertex.contains("vec4(vaPosition"),
                    () -> "post 程序 " + pass.programName() + " 的顶点源里找不到 NDC 直通形态");
        }
    }

    @Test
    @DisplayName("🔴 BSL：顶点 out 的 location 与片元 in 逐名同号（引擎只按 location 链接）")
    void bslVaryingLocationsAreAlignedByName() {
        List<String> problems = new ArrayList<>();
        for (PackPostChain.Pass pass : bslChain().passes()) {
            var fsLocations = readSlots(pass.renumberedSource(), FS_IN);
            var vsLocations = readSlots(pass.vertexSource(), VS_OUT);
            for (var entry : fsLocations.entrySet()) {
                Integer got = vsLocations.get(entry.getKey());
                if (got == null) {
                    problems.add(pass.programName() + ": 片元要 '" + entry.getKey() + "'@"
                            + entry.getValue() + "' 但顶点没有该 out");
                } else if (got != entry.getValue()) {
                    problems.add(pass.programName() + ": '" + entry.getKey() + "' 顶点在 location "
                            + got + " 而片元读 " + entry.getValue());
                }
            }
            // 同一 location 上顶点的类型必须与片元一致（引擎 :229 比向量长度，不一致就是编译失败）
            var vsTypes = readTypes(pass.vertexSource(), VS_OUT);
            var fsTypes = readTypes(pass.renumberedSource(), FS_IN);
            for (var entry : fsTypes.entrySet()) {
                String got = vsTypes.get(entry.getKey());
                if (got != null && !got.equals(entry.getValue())) {
                    problems.add(pass.programName() + ": '" + entry.getKey() + "' 顶点 " + got
                            + " vs 片元 " + entry.getValue());
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "varying 对齐失败:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("🔴 BSL：两阶段的 VkDispBuiltins 成员表逐条相等（引擎不比对块内部布局）")
    void bslBuiltinsBlockIsByteIdenticalAcrossStages() {
        for (PackPostChain.Pass pass : bslChain().passes()) {
            var vertexLayout = dev.vkdisp.glsl.translate.BuiltinsBlockLayout.parse(pass.vertexSource());
            var fragmentLayout = dev.vkdisp.glsl.translate.BuiltinsBlockLayout
                    .parse(pass.renumberedSource());
            assertFalse(vertexLayout.isEmpty(),
                    () -> pass.programName() + " 的顶点源解析不出 VkDispBuiltins 块");
            assertTrue(vertexLayout.members().equals(fragmentLayout.members()),
                    () -> pass.programName() + " 两阶段块成员不一致：顶点 " + vertexLayout.members()
                            + " / 片元 " + fragmentLayout.members());
        }
    }

    @Test
    @DisplayName("🔴 BSL：顶点属性全部落在冻结格式名单内（名单外的必须已被改成常量）")
    void bslVertexAttributesAreServable() {
        List<String> unservable = new ArrayList<>();
        for (PackPostChain.Pass pass : bslChain().passes()) {
            Matcher matcher = Pattern.compile(
                            "layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+(\\w+)\\s+(\\w+)\\s*;")
                    .matcher(pass.vertexSource());
            while (matcher.find()) {
                String type = matcher.group(1);
                String name = matcher.group(2);
                boolean floatBase = type.equals("float") || type.startsWith("vec");
                if (!floatBase || !dev.vkdisp.glsl.translate.PostVertexLinker.servableAttributes()
                        .contains(name)) {
                    unservable.add(pass.programName() + ":" + name + "(" + type + ")");
                }
            }
        }
        assertTrue(unservable.isEmpty(),
                () -> "这些顶点属性会撞引擎的「no matching vertex buffer element」（:139）："
                        + unservable);
    }

    private static java.util.Map<String, Integer> readSlots(String source, Pattern pattern) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            out.put(matcher.group(3), Integer.parseInt(matcher.group(1)));
        }
        return out;
    }

    private static java.util.Map<String, String> readTypes(String source, Pattern pattern) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            out.put(matcher.group(3), matcher.group(2));
        }
        return out;
    }


}
