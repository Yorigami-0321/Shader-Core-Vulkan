package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.pipeline.model.PostPassContract;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 GAP-030 的链接器守卫。
 *
 * <p>🔖 <b>为什么这些条必须存在</b>：本层的三条规则各自对应引擎里一个<b>硬抛</b>
 * （{@code PipelineBuilder} 的属性名匹配 / 按 location 链接 / 跨阶段块不比对）。
 * 前两条砸的是<b>整次资源重载</b>，第三条砸的是<b>画面而日志全正常</b> —— 第三种正是本项目
 * 反复付学费的形态（X11）。每条都用最小合成源钉死，不靠真包（真包那份见
 * {@code pack/PostChainPackVertexTest}）。
 */
class PostVertexLinkerTest {

    private static final String BLOCK_FS = """
            layout(std140) uniform VkDispBuiltins {
            mat4 gbufferModelView;
            };
            """;

    /** 一条简单的包 post VSH（已过 D 线形态：注入属性 + VkDispBuiltins + 带 location 的 out）。 */
    private static String vertex(String body) {
        return """
                #version 330
                layout(location = 1) in vec3 Position;
                layout(location = 2) in vec4 UV0;
                layout(std140) uniform VkDispBuiltins {
                mat4 gbufferModelView;
                vec3 sunPosition;
                mat4 gbufferModelViewInverse;
                };
                """ + body + """

                void main() {
                    gl_Position = vec4(Position, 1.0);
                    texCoord = UV0.xy;
                }
                """;
    }

    private static String fragment() {
        return """
                #version 330
                """ + BLOCK_FS + """
                layout(location = 0) in vec2 texCoord;
                layout(location = 3) out vec4 fragColor;
                void main() {
                    fragColor = vec4(texCoord.x);
                }
                """;
    }

    private static PostPassContract.FragmentInput in(int location, String type, String name) {
        return new PostPassContract.FragmentInput(location, type, name);
    }

    private static long count(String text, String needle) {
        return text.lines().filter(l -> l.contains(needle)).count();
    }

    @Test
    @DisplayName("🔴 一行两条 out（IoLocationAdapter 的逗号拆语句是同行拆的）不得被当成「没产出」")
    void multiStatementLineIsSplitBeforeMatching() {
        // 真包实测形态：BSL composite.vsh 的 `varying vec3 sunVec, upVec;` 到终稿是同一行的两条声明。
        String source = vertex("""
                layout(location = 0) out vec2 texCoord;
                layout(location = 1) out vec3 sunVec; layout(location = 2) out vec3 upVec;
                """);
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite", source, fragment(),
                List.of(in(0, "vec2", "texCoord"), in(1, "vec3", "sunVec"), in(2, "vec3", "upVec")));

        assertTrue(r.packVertexSupplied(), () -> "该槽不该回落：\n" + r.diagnostics());
        assertEquals(1, count(r.vertexSource(), "out vec3 sunVec;"),
                () -> "sunVec 出现两次 = 包已产出的 varying 被再合成一遍（重复声明，驱动直接编译失败）；实际:\n"
                        + r.vertexSource());
        assertEquals(1, count(r.vertexSource(), "out vec3 upVec;"));
        assertEquals(List.of(), r.synthesizedVaryings(), "三个 varying 都是包自己产出的");
        // location 必须逐名对齐（引擎只按 location 链接）
        assertTrue(r.vertexSource().contains("layout(location = 1) out vec3 sunVec;"));
        assertTrue(r.vertexSource().contains("layout(location = 2) out vec3 upVec;"));
    }

    @Test
    @DisplayName("🔴 名单外的顶点属性必须降级为常量并出 ERROR，绝不带着它进管线")
    void unservableAttributeBecomesConstant() {
        String source = vertex("""
                layout(location = 0) out vec2 texCoord;
                layout(location = 7) in vec2 someWeirdAttribute;
                """);
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite1", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));

        assertTrue(r.degradedAttributes().contains("someWeirdAttribute"),
                () -> "实际降级名单: " + r.degradedAttributes());
        assertTrue(r.vertexSource().contains("const vec2 someWeirdAttribute = vec2(0.0);"),
                () -> "声明必须整行换成常量；实际:\n" + r.vertexSource());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity()
                        == TranslateDiagnostic.Severity.ERROR && d.message().contains("someWeirdAttribute")),
                "降级必须点名（X11），WARN 不够 —— 它砸的是整次重载");
    }

    @Test
    @DisplayName("🔴 整型顶点属性与冻结格式的浮点元素基类型不符 ⇒ 同样降级（引擎 :154 硬抛）")
    void integerAttributeBecomesConstant() {
        String source = vertex("""
                layout(location = 0) out vec2 texCoord;
                layout(location = 3) in ivec2 UV2;
                """);
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite4", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));
        assertTrue(r.degradedAttributes().contains("UV2"),
                () -> "UV2 在冻结名单里但基类型是整型，仍不可服务；实际: " + r.degradedAttributes());
        assertTrue(r.vertexSource().contains("const ivec2 UV2 = ivec2(0);"));
    }

    @Test
    @DisplayName("🔴 只有顶点声明的 uniform 追加到共用块尾，且两阶段成员表逐条相等")
    void vertexOnlyUniformIsAppendedToSharedBlock() {
        String source = vertex("""
                layout(location = 0) out vec2 texCoord;
                """).replace("mat4 gbufferModelView;", "mat4 gbufferModelView;\nfloat onlyInVertex;");
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite5", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));

        assertTrue(r.packVertexSupplied(), () -> "实际诊断: " + r.diagnostics());
        assertEquals(1, count(r.vertexSource(), "float onlyInVertex;"));
        assertEquals(1, count(r.fragmentSource(), "float onlyInVertex;"),
                () -> "片元侧必须也有该成员（否则顶点读的是不存在的偏移）；实际:\n" + r.fragmentSource());
        var vertexLayout = BuiltinsBlockLayout.parse(r.vertexSource());
        var fragmentLayout = BuiltinsBlockLayout.parse(r.fragmentSource());
        assertEquals(fragmentLayout.members(), vertexLayout.members(),
                "两阶段块成员必须逐条同序同类型（引擎不比对块内部布局 = 静默喂垃圾）");
        // 追加只在块尾：片元既有成员的偏移不许被挪动
        assertEquals(fragmentLayout.find("gbufferModelView").offset(),
                vertexLayout.find("gbufferModelView").offset());
    }

    @Test
    @DisplayName("🔖 片元要而包顶点没产出的 varying：世界向量走口径公式，uv 名补 UV0 属性")
    void synthesizedVaryingsUseDocumentedFallbacks() {
        String source = vertex("layout(location = 0) out vec2 texCoord;");
        PostVertexLinker.Result r = PostVertexLinker.link("world0/deferred1", source, fragment(),
                List.of(in(0, "vec2", "texCoord"), in(3, "vec3", "upVec"), in(4, "vec2", "vertexCoords")));

        assertTrue(r.packVertexSupplied());
        assertEquals(List.of("upVec", "vertexCoords"), r.synthesizedVaryings(),
                () -> "实际合成名单: " + r.synthesizedVaryings());
        assertTrue(r.vertexSource().contains("upVec = normalize(mat3(gbufferModelViewInverse)"),
                () -> "世界向量必须走我方口径公式而不是零向量；实际:\n" + r.vertexSource());
        assertTrue(r.vertexSource().contains("vertexCoords = vec2(0.0);"),
                "不认识的名字按零值供并 ERROR（不猜一个像的，X9）");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity()
                == TranslateDiagnostic.Severity.ERROR && d.message().contains("vertexCoords")));
        // texCoord 由包自己产出 ⇒ 不许被再合成一遍
        assertEquals(1, count(r.vertexSource(), "out vec2 texCoord;"));
    }

    @Test
    @DisplayName("🔴 不被片元消费的顶点输出搬到空闲 location，且不与片元输入号撞车")
    void unconsumedOutputMovesToFreeLocation() {
        String source = vertex("""
                layout(location = 0) out vec2 texCoord;
                layout(location = 3) out vec4 unusedByFragment;
                """);
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite6", source, fragment(),
                List.of(in(0, "vec2", "texCoord"), in(3, "vec2", "someOther")));

        assertTrue(r.packVertexSupplied());
        assertTrue(r.vertexSource().contains("out vec4 unusedByFragment;"));
        assertTrue(r.vertexSource().contains("out vec2 someOther;"), "片元要的 someOther 必须被合成出来");
        // someOther 占 3；unusedByFragment 必须搬走且不是 3
        String moved = r.vertexSource().lines()
                .filter(l -> l.contains("out vec4 unusedByFragment"))
                .findFirst().orElseThrow();
        assertFalse(moved.contains("location = 3"), () -> "两条 out 同 location = glslang 直接拒；实际行: " + moved);
        // 声明不许被删（删了要把赋值一起删，赋值右侧可能依赖中间量）
        assertEquals(1, count(r.vertexSource(), "unusedByFragment;"));
    }

    @Test
    @DisplayName("🔴 顶点侧缺 VkDispBuiltins 块 ⇒ 整槽回落（不许让两阶段各读一套偏移）")
    void missingBlockDeclinesPackVertex() {
        String source = """
                #version 330
                layout(location = 1) in vec3 Position;
                layout(location = 0) out vec2 texCoord;
                void main() {
                    gl_Position = vec4(Position, 1.0);
                    texCoord = vec2(0.0);
                }
                """;
        PostVertexLinker.Result r = PostVertexLinker.link("world0/final", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));
        assertFalse(r.packVertexSupplied(), "没有共用块就必须回落");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity()
                == TranslateDiagnostic.Severity.ERROR && d.message().contains("VkDispBuiltins")));
        assertNotNull(r.fragmentSource(), "回落时片元源必须原样带回，不许丢");
    }

    @Test
    @DisplayName("🔖 冻结名单与适配层的口径共用同一份真源（两处各写一遍就会漂移）")
    void sharedVocabularyIsSingleSourced() {
        assertTrue(PostVertexLinker.servableAttributes().contains("Position"),
                "LegacyBuiltinInjector 注入的名字必须在冻结名单里");
        assertTrue(PostVertexLinker.isWorldVectorName("sunVec"));
        assertFalse(PostVertexLinker.isWorldVectorName("texCoord"));
        // 🔴 原版 VertexFormat.Builder 在 17 条属性时抛 "Having more than 16 attributes are not
        //   supported" —— 那砸的是**管线注册期**（客户端起不来），所以名单长度要有守卫而不是靠人记。
        assertTrue(PostVertexLinker.servableAttributes().size() <= 16,
                () -> "冻结属性名单超过引擎硬上限 16: " + PostVertexLinker.servableAttributes());
        assertEquals(PostVertexLinker.servableAttributes().size(),
                java.util.Set.copyOf(PostVertexLinker.servableAttributes()).size(),
                "重名会被 Builder.validateUniqueName 抛（同上一条一样是注册期失败）");
        // 适配层的世界向量零值必须被分类出来（GAP-030 之前它只写在注释里，从没接线）
        PackPostVertexAdapter.Result adapter = PackPostVertexAdapter.generate(List.of(
                in(0, "vec2", "texCoord"), in(1, "vec3", "sunVec"), in(2, "vec3", "cameraSpaceJunk")));
        assertEquals(List.of("sunVec"), adapter.worldVectorZeros(),
                () -> "实际零值名单: " + adapter.zeroSupplied());
    }
}
