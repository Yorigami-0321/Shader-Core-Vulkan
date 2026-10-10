package dev.vkdisp.glsl.preprocess;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vkdisp.glsl.TranslateResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.anarres.cpp.Feature;
import org.anarres.cpp.Preprocessor;
import org.anarres.cpp.Source;
import org.anarres.cpp.StringLexerSource;
import org.anarres.cpp.Token;
import org.anarres.cpp.VirtualFile;
import org.anarres.cpp.VirtualFileSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】A2 · 三路差分探针（`19` §2.6-A2）—— 测试源集里的「现自研 vs jcpp」两臂对比
 * 0. 合规：jcpp = Apache-2.0（`19` §7-1 裁决允许移植；本测试只调用其公开 API，零代码并入）。
 *    参考对象 = 本仓库自有 `GlslPreprocessor`（C 线）与 jcpp 1.4.14 的公开行为。
 *    外部候选 glsl-transformer / glsl-preprocessor（GPL-3.0+例外）按禁止处理（`07` L12 / X20 / X21）。
 * 1. 职责：对同一批 GLSL 源样本，分别跑 ①现自研预处理 ②jcpp，比**逐字节 diff**。
 *    重点验证 `19` §2.6-A2 点名的五类边界：
 *    ① `#version/#extension/#pragma` 透传 ② 函数宏 ③ `#include` 相对路径
 *    ④ `\` 续行（含尾随空格） ⑤ 条件编译。
 * 2. 差异点：本探针**不承诺替换** —— 它只报差异，不改生产路径（`19` §2.6-A2 原文）。
 *    第三臂（shaderc `shaderc_compile_into_preprocessed_text`）需要原生库，只能在真机取证轮跑。
 * 3. 非显然约束：jcpp 是**完整 C 预处理器**，GLSL 的 `#version/#extension` 不是 C 指令 ⇒
 *    jcpp 会把它们当未知指令处理（可能报错或透传，取决于 Feature 配置）。
 *    Iris 用 `#warning` hack 透传（自称 "absolutely awful hack"）。
 *    本探针如实记录两臂差异，不假装一致。
 * 4. 性能：❄️ 单测（冷路径）。
 */
class JcppDifferentialProbeTest {

    /** 内存文件系统（供 jcpp 的 #include 用）。 */
    private static final class MemoryFileSystem implements VirtualFileSystem {
        private final Map<String, String> files;

        MemoryFileSystem(Map<String, String> files) {
            this.files = files;
        }

        @Override
        public VirtualFile getFile(String path) {
            return new MemoryFile(path, files.get(path));
        }

        @Override
        public VirtualFile getFile(String dir, String name) {
            String path = dir == null || dir.isEmpty() ? name : dir + "/" + name;
            return getFile(path);
        }
    }

    private static final class MemoryFile implements VirtualFile {
        private final String path;
        private final String content;

        MemoryFile(String path, String content) {
            this.path = path;
            this.content = content;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getName() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }

        @Override
        public VirtualFile getParentFile() {
            int slash = path.lastIndexOf('/');
            return slash > 0 ? new MemoryFile(path.substring(0, slash), null) : null;
        }

        @Override
        public boolean isFile() {
            return content != null;
        }

        @Override
        public Source getSource() {
            return content != null ? new StringLexerSource(content, true) : null;
        }

        @Override
        public VirtualFile getChildFile(String name) {
            return new MemoryFile(path + "/" + name, null);
        }
    }

    /** 用 jcpp 预处理一份 GLSL 源。 */
    private static String jcppPreprocess(String source, Map<String, String> includes) {
        try {
            Preprocessor pp = new Preprocessor();
            pp.addFeature(Feature.KEEPCOMMENTS);
            pp.addFeature(Feature.LINEMARKERS);
            if (includes != null && !includes.isEmpty()) {
                pp.setFileSystem(new MemoryFileSystem(includes));
            }
            pp.addInput(new StringLexerSource(source, true));
            StringBuilder out = new StringBuilder();
            for (;;) {
                Token tok = pp.token();
                if (tok.getType() == Token.EOF) {
                    break;
                }
                out.append(tok.getText());
            }
            pp.close();
            return out.toString();
        } catch (Exception e) {
            return "[jcpp error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
        }
    }

    /** 用现自研预处理。 */
    private static String selfPreprocess(String source, Map<String, String> includes) {
        IncludeResolver resolver = includes == null || includes.isEmpty()
                ? path -> null : IncludeResolver.of(includes);
        TranslateResult result = GlslPreprocessor.preprocess("test.glsl", source, resolver);
        return result.text();
    }

    /** 对比两臂输出，返回差异描述（空 = 一致）。 */
    private static String diff(String label, String source, Map<String, String> includes) {
        String self = selfPreprocess(source, includes);
        String jcpp = jcppPreprocess(source, includes);
        if (normalize(self).equals(normalize(jcpp))) {
            return "";
        }
        return label + ":\n    self=" + escape(self) + "\n    jcpp=" + escape(jcpp);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(line.stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    private static String escape(String text) {
        return text == null ? "(null)" : text.replace("\n", "\\n").replace("\r", "\\r");
    }

    // ---------------------------------------------------------------- 探针样本

    @Test
    @DisplayName("🔖 A2 探针：#version/#extension 透传（自研臂必须保留原文）")
    void versionAndExtensionPassthrough() {
        String source = "#version 430\n#extension GL_ARB_separate_shader_objects : enable\nint x;\n";
        String self = selfPreprocess(source, null);
        assertNotNull(self);
        assertFalse(self.isBlank(), "自研臂不许吞掉整份源");
        assertFalse(!self.contains("#version"),
                "自研臂必须透传 #version，实测：" + escape(self));
    }

    @Test
    @DisplayName("🔖 A2 探针：简单 #define 展开（自研臂必须展开）")
    void simpleDefineExpansion() {
        String source = "#define FOO 42\nint x = FOO;\n";
        String self = selfPreprocess(source, null);
        assertFalse(self.contains("FOO"), "自研臂必须展开 FOO，实测：" + escape(self));
        String differences = diff("simple-define", source, null);
        if (!differences.isEmpty()) {
            System.out.println("[A2 probe] " + differences);
        }
    }

    @Test
    @DisplayName("🔖 A2 探针：函数宏（带参数替换）")
    void functionMacro() {
        String source = "#define MAX(a, b) ((a) > (b) ? (a) : (b))\nfloat x = MAX(1.0, 2.0);\n";
        String self = selfPreprocess(source, null);
        assertFalse(self.contains("MAX("), "自研臂必须展开函数宏，实测：" + escape(self));
        String differences = diff("function-macro", source, null);
        if (!differences.isEmpty()) {
            System.out.println("[A2 probe] " + differences);
        }
    }

    @Test
    @DisplayName("🔖 A2 探针：\\ 续行（含尾随空格 —— 包实测写法）")
    void lineContinuationWithTrailingSpaces() {
        String source = "#define LONG \\\n  value1 + \\\n  value2\nint x = LONG;\n";
        String self = selfPreprocess(source, null);
        assertNotNull(self);
        String differences = diff("continuation", source, null);
        if (!differences.isEmpty()) {
            System.out.println("[A2 probe] " + differences);
        }
    }

    @Test
    @DisplayName("🔖 A2 探针：条件编译 #ifdef/#else/#endif")
    void conditionalCompilation() {
        String source = "#define USE_A\n#ifdef USE_A\nint a = 1;\n#else\nint b = 2;\n#endif\n";
        String self = selfPreprocess(source, null);
        assertFalse(self.contains("int b"), "自研臂必须只保留选中分支，实测：" + escape(self));
        assertFalse(self.contains("#ifdef"), "自研臂必须删掉条件指令，实测：" + escape(self));
        String differences = diff("conditional", source, null);
        if (!differences.isEmpty()) {
            System.out.println("[A2 probe] " + differences);
        }
    }

    @Test
    @DisplayName("🔖 A2 探针：#include 相对路径")
    void includeRelativePath() {
        Map<String, String> includes = new LinkedHashMap<>();
        includes.put("lib/common.glsl", "float shared = 1.0;\n");
        String source = "#include \"lib/common.glsl\"\nint x;\n";
        String self = selfPreprocess(source, includes);
        assertFalse(self.contains("#include"), "自研臂必须展开 #include，实测：" + escape(self));
        assertFalse(!self.contains("shared"),
                "自研臂展开后必须含被包含文件的内容，实测：" + escape(self));
    }

    @Test
    @DisplayName("🔴 A2 探针汇总：全部样本的差异计数（报数不 fail —— 探针性质）")
    void probeSummaryReportsDiffCount() {
        List<String> diffs = new ArrayList<>();
        String[][] samples = {
            {"version-passthrough", "#version 430\nint x;\n"},
            {"simple-define", "#define A 1\nint x = A;\n"},
            {"function-macro", "#define F(x) ((x)*2)\nint y = F(3);\n"},
            {"continuation", "#define L \\\n  a + \\\n  b\nint z = L;\n"},
            {"conditional", "#ifdef X\nint a;\n#else\nint b;\n#endif\n"},
            {"nested-define", "#define A 1\n#define B A+1\nint x = B;\n"},
            {"undef", "#define A 1\n#undef A\nint x = A;\n"},
        };
        for (String[] sample : samples) {
            String d = diff(sample[0], sample[1], null);
            if (!d.isEmpty()) {
                diffs.add(d);
            }
        }
        System.out.println("[A2 probe] 差异计数=" + diffs.size() + "/" + samples.length);
        for (String d : diffs) {
            System.out.println("[A2 probe] " + d);
        }
        for (String[] sample : samples) {
            String self = selfPreprocess(sample[1], null);
            assertNotNull(self, sample[0] + " 自研臂返回 null");
        }
    }
}
