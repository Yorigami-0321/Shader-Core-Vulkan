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

    // ────────────────────────────────────────────────────────────────────────────────
    // ④ clip 中性化（GAP-054：喂 NDC 全屏三角时，clip 里不许留相机矩阵）
    // ────────────────────────────────────────────────────────────────────────────────

    /** BSL {@code gbuffers_skybasic} 顶点终稿的<b>实测</b>形态（dump 逐字，含那条 sunVec/upVec）。 */
    private static String bslSkybasicShape() {
        return """
                #version 330
                layout(location = 1) in vec3 Position;
                layout(location = 0) in vec4 Color;
                layout(std140) uniform VkDispBuiltins {
                mat4 gbufferModelView;
                mat4 gbufferProjection;
                vec3 sunPosition;
                mat4 gbufferModelViewInverse;
                };
                layout(location = 0) out float alpha;
                layout(location = 1) out vec3 sunVec;
                layout(location = 2) out vec3 upVec;

                void main() {
                    float ang = fract(timeAngle - 0.25);
                    sunVec = normalize((gbufferModelView * vec4(vec3(-sin(ang), cos(ang)) * 2000.0, 1.0)).xyz);
                    upVec = normalize(gbufferModelView[1].xyz);
                    gl_Position = (gbufferProjection * gbufferModelView * vec4(Position, 1.0));
                    alpha = 1.0;
                }
                """;
    }

    @Test
    @DisplayName("🔴 真包实测形态：clip 那一行被中性化成 Position 原样出去，而 sunVec/upVec 一个字不动")
    void skyClipWriteIsNeutralizedButCameraMathStays() {
        String source = bslSkybasicShape();
        assertFalse(PostVertexLinker.emitsPositionPassthrough(source),
                "喂进去之前必须判 false —— 这一行乘的是真相机矩阵，NDC 三角会被透视成半屏巨型三角形");

        PostVertexLinker.Result r = PostVertexLinker.link("world0/gbuffers_skybasic", source,
                fragment(), List.of(in(0, "float", "alpha"), in(1, "vec3", "sunVec"),
                        in(2, "vec3", "upVec")));

        assertTrue(r.packVertexSupplied(), () -> "该槽不该回落：\n" + r.diagnostics());
        assertTrue(r.vertexSource().contains("gl_Position = vec4(Position, 1.0);"),
                () -> "clip 必须整行换成位置属性；实际:\n" + r.vertexSource());
        assertFalse(r.vertexSource().contains("gbufferProjection * gbufferModelView * vec4(Position"),
                "换完不许留旧句式（留着就是「决定了但没落地」）");
        // 🔴 中性化的作用域<b>只有 clip 那一行</b>：太阳方向是 gbufferModelView 乘出来的，砍了就没了
        assertTrue(count(r.vertexSource(),
                "sunVec = normalize((gbufferModelView * vec4(vec3(-sin(ang), cos(ang)) * 2000.0, 1.0)).xyz);") == 1,
                "sunVec 的相机数学必须原样保留（B 档：天空接管但太阳/月亮方向全错，画面有内容而每个通道都错）");
        assertTrue(r.vertexSource().contains("upVec = normalize(gbufferModelView[1].xyz);"),
                "upVec 同理");
        assertTrue(PostVertexLinker.emitsPositionPassthrough(r.vertexSource()),
                "终稿谓词必须对**换完之后那份**回 true（SkyPackGbuffer 的几何闸门读的就是它）");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity()
                        == TranslateDiagnostic.Severity.WARN && d.message().contains("中性化")),
                "改写必须点名（X11）：这是一次「包写的数学被我方改了」的动作，静默改源是本仓反复付学费的形态");
    }

    @Test
    @DisplayName("🔖 NDC-native 的包顶点（链那一大族）本层不碰、也不报中性化")
    void ndcNativeClipIsLeftAlone() {
        String source = vertex("layout(location = 0) out vec2 texCoord;\n");
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));
        assertTrue(r.packVertexSupplied());
        assertEquals(1, count(r.vertexSource(), "gl_Position = vec4(Position, 1.0);"),
                "本来就原样出去的，不许被再写一遍（多一处 = 有人又兜了一层）");
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.message().contains("中性化")),
                () -> "不该有中性化诊断；实际: " + r.diagnostics());
        assertTrue(PostVertexLinker.emitsPositionPassthrough(r.vertexSource()));
    }

    @Test
    @DisplayName("🔴 源里没有位置属性时<b>不猜</b>屏幕坐标从哪来：原样交出去 + ERROR，谓词回 false")
    void clipWriteWithoutPositionAttributeIsNotGuessed() {
        String source = """
                #version 330
                layout(location = 3) in vec3 vertexFromSomewhere;
                layout(std140) uniform VkDispBuiltins {
                mat4 gbufferProjection;
                };
                layout(location = 0) out vec2 texCoord;

                void main() {
                    gl_Position = gbufferProjection * vec4(vertexFromSomewhere, 1.0);
                    texCoord = vec2(0.0);
                }
                """;
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite9", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));
        assertTrue(r.vertexSource().contains("gbufferProjection * vec4(vertexFromSomewhere, 1.0)"),
                () -> "没有 Position/vaPosition 可依据时不许造一个；实际:\n" + r.vertexSource());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity()
                        == TranslateDiagnostic.Severity.ERROR && d.message().contains("不猜")),
                () -> "不猜这件事必须打成 ERROR；实际: " + r.diagnostics());
        assertFalse(PostVertexLinker.emitsPositionPassthrough(r.vertexSource()),
                "谓词回 false ⇒ 调用方的几何闸门拒喂全屏三角 = 退回原版重放，而不是画半张");
    }

    @Test
    @DisplayName("🔖 跨行的 clip 赋值整条不认（认了就是把源改坏），谓词因此回 false")
    void multiLineClipAssignmentIsNotTouched() {
        String source = """
                #version 330
                layout(location = 1) in vec3 Position;
                layout(std140) uniform VkDispBuiltins {
                mat4 gbufferProjection;
                };
                layout(location = 0) out vec2 texCoord;

                void main() {
                    gl_Position = gbufferProjection
                            * vec4(Position, 1.0);
                    texCoord = vec2(0.0);
                }
                """;
        PostVertexLinker.Result r = PostVertexLinker.link("world0/composite7", source, fragment(),
                List.of(in(0, "vec2", "texCoord")));
        assertTrue(r.vertexSource().contains("gl_Position = gbufferProjection"),
                "只认整行单语句（同 IO 那条纪律）：跨行改写会把包的源改坏");
        assertFalse(PostVertexLinker.emitsPositionPassthrough(r.vertexSource()),
                "认不出形态时判据必须回 false，而不是「大概没问题」");
    }

    @Test
    @DisplayName("🔖 谓词的空输入与「根本没写 clip」都必须回 false（false = 安全方向）")
    void passthroughPredicateFailsClosed() {
        assertFalse(PostVertexLinker.emitsPositionPassthrough(null));
        assertFalse(PostVertexLinker.emitsPositionPassthrough(""));
        assertFalse(PostVertexLinker.emitsPositionPassthrough("void main() { }\n"),
                "没写 gl_Position 的源不能被判成「原样出去了」");
        assertFalse(PostVertexLinker.emitsPositionPassthrough(
                "gl_Position = vec4(0.0);\n"), "右值不引用位置属性 = 屏幕坐标没有来源");
    }
}
