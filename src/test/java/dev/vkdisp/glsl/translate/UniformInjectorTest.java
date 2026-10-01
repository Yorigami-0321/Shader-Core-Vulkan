package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.glsl.TranslateDiagnostic;

/**
 * 【参考调研】D 线单测（内建 uniform 注入）/ 04-SPEC §3.2 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 与 docs/18-PARALLEL.md §4 D 线完成标准 ——
 *    仓库内文档事实，不受版权保护。外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→
 *    按禁止处理（07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，样本全部为本任务自造（18-PARALLEL §7.6）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 断言"注入完整（23 条全在）+ 不重复 + 幂等 + 注入点不越过 #version /
 *    #extension"这四件事，对应 04-SPEC §3.2 与 18-PARALLEL §4 D 线完成标准。
 * 2. 备选：无 —— 文本级断言足够，不引入快照框架。
 * 3. 我们的差异点：边界用例（空输入 / 仅注释 / 重复声明 / 类型不符 / 未闭合注释 / CRLF）
 *    全部显式断言"诊断而非崩溃"，对应 18-PARALLEL §7.3 的边界用例清单要求。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link UniformInjector} 的单测：注入完整性、不重复、注入点位置、幂等与边界输入。
 */
class UniformInjectorTest {

    private static final String MINIMAL = "#version 330 core\nvoid main() {}\n";

    @Test
    void injectsAllTwentyThreeBuiltinsWhenAbsent() {
        UniformInjector.Result result = UniformInjector.inject(MINIMAL);
        assertEquals(23, result.injected().size(), "04-SPEC §3.2 的 23 条一条都不能少");
        assertEquals(26, result.insertedLineCount(), "识别注释 + 块开行 + 23 条成员 + 块闭行");
        assertEquals(1, result.insertIndex(), "#version 之后、首条代码之前");
        assertTrue(result.diagnostics().isEmpty());
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertEquals(1, count(result.text(), uniform.blockMember()),
                    "声明必须出现且仅出现一次：" + uniform.name());
        }
    }

    /**
     * P2.3 驱动实测的方言要求（差异点 ④）：注入的非透明 uniform 必须在具名 std140 块内 ——
     * 独立 {@code uniform} 行被 shaderc 以 {@code 'non-opaque uniforms outside a block'} 拒绝，
     * 匿名块被以 {@code syntax error, unexpected LEFT_BRACE} 拒绝（GLSL 要求块名）。
     */
    @Test
    void injectedBuiltinsAreWrappedInNamedStd140Block() {
        UniformInjector.Result result = UniformInjector.inject(MINIMAL);
        assertTrue(result.text().contains(UniformInjector.BLOCK_HEADER + "\n" + UniformInjector.BLOCK_OPEN),
                "识别注释后必须紧跟具名 std140 块开行：\n" + result.text());
        assertTrue(result.text().contains(UniformInjector.BLOCK_OPEN + "\nmat4 gbufferModelView;\n"),
                "成员必须在块内且是全局作用域可用形态：\n" + result.text());
        assertTrue(result.text().contains("\n" + UniformInjector.BLOCK_CLOSE + "\n"),
                "块必须有闭行：\n" + result.text());
        // MINIMAL 输入除块开行外没有任何 uniform 记号 —— 成员行不带 uniform 关键字。
        assertEquals(1, count(result.text(), "uniform "),
                "整个注入文本里 uniform 关键字只应出现在块开行");
        assertEquals(26, result.insertedLineCount(), "插入行数 = 注释 1 + 开行 1 + 成员 23 + 闭行 1");
    }

    @Test
    void injectionPointIsAfterPreprocessorHeaderOnly() {
        String source = """
                #version 330 core
                #define FOO 1
                #extension GL_ARB_shading_language_include : enable
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("#version 330 core", lines.get(0), "#version 必须仍在第一行");
        assertEquals("#define FOO 1", lines.get(1));
        assertEquals("#extension GL_ARB_shading_language_include : enable", lines.get(2),
                "#extension 必须仍在任何非预处理记号之前");
        assertEquals(UniformInjector.BLOCK_HEADER, lines.get(3));
        assertEquals(UniformInjector.BLOCK_OPEN, lines.get(4));
        assertEquals("mat4 gbufferModelView;", lines.get(5));
        assertEquals(3, result.insertIndex());
    }

    @Test
    void doesNotDuplicateAlreadyDeclaredBuiltin() {
        // P4.1 收编语义：游离声明的内建不再「原样留在块外」（过不了 Vulkan），而是**移动**进块 ——
        // 不重复注入（22 条）+ 声明文本保留恰好一次 + 原行抹空。
        String source = "#version 330 core\nuniform mat4 gbufferModelView;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(22, result.injected().size());
        assertEquals(1, count(result.text(), "mat4 gbufferModelView;"),
                "声明文本（去 uniform 关键字）必须在块内出现且仅出现一次");
        assertEquals(0, count(result.text(), "uniform mat4 gbufferModelView;"),
                "游离原行必须被抹空（P4.1：块外非透明 uniform 过不了 shaderc）");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.INFO && d.message().contains("收编")),
                () -> "收编必须显式可见（T11），实际: " + result.diagnostics());
    }

    @Test
    void allPlainBuiltinsAreAdoptedIntoBlockInSourceOrder() {
        StringBuilder source = new StringBuilder("#version 330 core\n");
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            source.append(uniform.declaration()).append('\n');
        }
        source.append("void main() {}\n");
        UniformInjector.Result result = UniformInjector.inject(source.toString());
        assertTrue(result.injected().isEmpty(), "全部已声明 → 无缺失注入项");
        assertEquals(23 + 3, result.insertedLineCount(), "收编 23 行 + 注释/开行/闭行 3 行");
        // 收编后全部成员在块内、逐字节幂等（第二遍无游离声明可收）。
        UniformInjector.Result second = UniformInjector.inject(result.text());
        assertEquals(result.text(), second.text(), "收编路径同样必须幂等");
        assertTrue(second.injected().isEmpty());
        assertEquals(0, second.insertedLineCount());
        assertTrue(second.diagnostics().isEmpty(), () -> "第二遍不该再有收编/注入诊断: " + second.diagnostics());
    }

    @Test
    void typeMismatchWarnsAndKeepsPackDeclarationText() {
        String source = "#version 330 core\nuniform vec4 cameraPosition;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(22, result.injected().size(), "已声明的名字不再注入");
        // 收编只移动不改写：类型不符时块内成员仍是包声明的 vec4（P4.1 语义）。
        assertEquals(1, count(result.text(), "\nvec4 cameraPosition;\n"),
                "包内声明文本必须原样保留（块内）");
        assertEquals(0, count(result.text(), "uniform vec3 cameraPosition;"));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> {
            if (diagnostic.severity() != TranslateDiagnostic.Severity.WARN) {
                return false;
            }
            return diagnostic.line() == 2
                    && diagnostic.message().contains("vec4") && diagnostic.message().contains("vec3");
        }), () -> "类型不符必须 WARN 且指回第 2 行，实际: " + result.diagnostics());
    }

    @Test
    void duplicateUniformDeclarationWarns() {
        String source = "#version 330 core\nuniform vec4 colortex0;\nuniform vec4 colortex0;\nvoid main() {}\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(23, result.injected().size());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.severity() == TranslateDiagnostic.Severity.WARN
                                && diagnostic.line() == 3),
                () -> "重复声明必须 WARN 且指回第 3 行，实际: " + result.diagnostics());
        // 收编只收首现（重名第二次留原位，由驱动显式报错；两份都进块会变成块内重名）。
        assertEquals(1, count(result.text(), "\nvec4 colortex0;\n"),
                "重名只收首现，块内至多一份");
    }

    @Test
    void packNamedBlockMembersAreRecordedNotAdopted() {
        String source = """
                #version 330 core
                uniform OfSceneParams {
                    mat4 gbufferModelView;
                };
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(22, result.injected().size(),
                "无实例名块的成员是全局作用域声明，gbufferModelView 已声明 → 不再注入"
                        + "（注入会造成同名重复，驱动显式报错）");
        assertEquals(0, count(result.text(), "\nmat4 gbufferModelView;\n"),
                "已声明的名字不许出现在注入块里");
        // 块内成员不被收编（仍在包块原位）。
        assertEquals(1, count(result.text(), "    mat4 gbufferModelView;"));
    }

    @Test
    void plainPackUniformsAreAdoptedButSamplersStayOutside() {
        String source = """
                #version 330 core
                uniform float rainStrength;
                uniform sampler2D colortex0;
                uniform mat4 gbufferModelView;
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(1, count(result.text(), "\nfloat rainStrength;\n"),
                "游离非透明 uniform 必须收进块内");
        assertEquals(1, count(result.text(), "\nmat4 gbufferModelView;\n"),
                "游离内建同规则收编（且不重复注入）");
        assertEquals(1, count(result.text(), "uniform sampler2D colortex0;"),
                "采样器是透明类型：留原位，不进 UBO 块");
        assertEquals(0, count(result.text(), "uniform float rainStrength;"),
                "原行抹空（保行号契约）");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("收编")));
        // 行号契约：抹空不删行 → 总行数只增不减。
        assertEquals(result.text().split("\n", -1).length,
                source.split("\n", -1).length + result.insertedLineCount(),
                "抹空保行号：输出行数 = 输入行数 + 插入行数");
    }

    /**
     * P4.1.2 逗号多名字（BSL 实测形态自造样本，非抄包）：{@code uniform float far, near;} 的
     * **全部**声明名必须登记 —— 只记首名会让 near 被当成缺失再注入一次，与收编进块的整行撞名
     * （驱动实测 {@code duplicate member name} 取证）。
     */
    @Test
    void commaSeparatedPlainUniformsAreAdoptedOnceWithEveryNameRegistered() {
        String source = """
                #version 330 core
                uniform float viewWidth, viewHeight, aspectRatio;
                uniform float far, near;
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(1, count(result.text(), "float viewWidth, viewHeight, aspectRatio;"),
                "逗号多名字整行收编，只出现一次");
        assertEquals(1, count(result.text(), "float far, near;"),
                "逗号多名字整行收编，只出现一次");
        assertEquals(0, count(result.text(), "\nfloat viewWidth;\n"),
                "viewWidth 已随收编行登记，不许按目录再注入一份");
        assertEquals(0, count(result.text(), "\nfloat viewHeight;\n"),
                "viewHeight 是后续声明名，同样已登记");
        assertEquals(0, count(result.text(), "\nfloat far;\n"), "far 已登记，不再注入");
        assertEquals(0, count(result.text(), "\nfloat near;\n"), "near 已登记，不再注入");
        assertEquals(19, result.injected().size(),
                "viewWidth/viewHeight/far/near 四条已声明 → 只注入其余 19 条");
        assertEquals(0, count(result.text(), "uniform float viewWidth"),
                "原行抹空（保行号契约）");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("2 条")),
                () -> "两行收编应报一条数量 INFO：" + result.diagnostics());

        // 幂等：收编行在块内以逗号多名字形态被整行记录 → 第二遍无缺失、无收编、逐字节相同。
        UniformInjector.Result second = UniformInjector.inject(result.text());
        assertEquals(result.text(), second.text(), "第二遍必须逐字节相同");
        assertTrue(second.injected().isEmpty());
        assertTrue(second.diagnostics().isEmpty(),
                () -> "第二遍不该有诊断：" + second.diagnostics());
    }

    /**
     * 逗号行里任一名字已被收编 → 整行不再收编（部分进块会撞名），重复名字照常 WARN（T11）。
     */
    @Test
    void duplicateNameInCommaListWarnsAndLeavesLaterLineOutside() {
        String source = """
                #version 330 core
                uniform float a, b;
                uniform float b;
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                        d.severity() == TranslateDiagnostic.Severity.WARN
                                && d.message().contains("b 重复声明") && d.line() == 3),
                () -> "第二行的 b 重复必须 WARN 且指回第 3 行：" + result.diagnostics());
        assertEquals(1, count(result.text(), "\nfloat a, b;\n"), "首行整行收编一次");
        assertEquals(1, count(result.text(), "uniform float b;"),
                "重名行保留原样（不部分收编），交由驱动显式报错");
    }

    /** 第二遍扫描块成员时，逗号多名字成员的全部名字都要记作已声明（幂等的另一半）。 */
    @Test
    void commaSeparatedBlockMembersAreAllRecordedForIdempotency() {
        String source = """
                #version 330 core
                layout(std140) uniform VkDispBuiltins {
                float far, near;
                };
                void main() {}
                """;
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(21, result.injected().size(),
                "far / near 已在块内（逗号成员全登记）→ 只注入其余 21 条");
        assertEquals(0, count(result.text(), "\nfloat far;\n"), "far 不许再注入");
        assertEquals(0, count(result.text(), "\nfloat near;\n"), "near 不许再注入");
        assertEquals(1, count(result.text(), "float far, near;"), "包声明原样保留");
        assertTrue(result.diagnostics().isEmpty(), () -> "无重复无类型冲突：" + result.diagnostics());
    }

    @Test
    void injectionIsIdempotent() {
        UniformInjector.Result first = UniformInjector.inject(MINIMAL);
        UniformInjector.Result second = UniformInjector.inject(first.text());
        assertEquals(first.text(), second.text(), "第二遍必须逐字节相同");
        assertTrue(second.injected().isEmpty());
        assertEquals(0, second.insertedLineCount());
        assertTrue(second.diagnostics().isEmpty());
    }

    @Test
    void emptyInputSkipsInjectionWithWarning() {
        UniformInjector.Result result = UniformInjector.inject("");
        assertEquals("", result.text());
        assertTrue(result.injected().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(TranslateDiagnostic.Severity.WARN, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("不含任何代码行"));
    }

    @Test
    void commentOnlyInputSkipsInjectionWithWarning() {
        String source = "// only a comment\n/* and a block comment */\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(source, result.text());
        assertTrue(result.injected().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("不含任何代码行")));
    }

    @Test
    void unterminatedBlockCommentIsError() {
        String source = "#version 330 core\nvoid main() {}\n/* open\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertEquals(TranslateDiagnostic.Severity.ERROR, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("未闭合"));
    }

    @Test
    void crlfInputProducesCrlfOutputOnly() {
        String source = "#version 330 core\r\nvoid main() {}\r\n";
        UniformInjector.Result result = UniformInjector.inject(source);
        assertTrue(result.text().contains("\r\n"));
        assertFalse(result.text().replace("\r\n", "").contains("\n"),
                "CRLF 文件里不许混入裸 LF");
        assertTrue(result.text().startsWith("#version 330 core\r\n"), "首行内容不许被改动");
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
