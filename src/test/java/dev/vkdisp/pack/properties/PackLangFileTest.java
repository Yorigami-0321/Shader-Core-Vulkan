package dev.vkdisp.pack.properties;
/**
 * 【参考调研】包 lang 文件解析单测 / GAP-009 能力门控的判据来源
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = lang 文件的**格式事实**（{@code key=value} + Minecraft 单反斜杠转义）——
 *    不受版权保护；本测试的文本全部**自造最小样本**，
 *    🔶 **不复制任何第三方包的语言文件文本**（18-PARALLEL §7.6：只允许自造最小样本）。
 *    自造样本里的选项名（PARALLAX / ADVANCED_MATERIALS 等）是**标识符事实**，
 *    描述文本是本文件自己写的，不逐字复制。
 *    → 能否并入本项目（MIT）：可以（测试代码不进分发 jar）
 *    → 例外条款：无
 * 1. 官方/主实现：无（本类为本项目自研的只读解析器）。
 * 2. 备选：无。
 * 3. 我们的差异点（每条测试钉一个具体主张）：
 *    <ul>
 *      <li>🔴 <b>Minecraft 的转义是单反斜杠</b>，不是 Java properties 的双反斜杠
 *      ⇒ 用 {@code Properties.load} 会静默改写文本（本类的存在理由）。</li>
 *      <li>🔴 <b>星号被剥掉</b>再进标签表（星号是依赖标记，不是文案）。</li>
 *      <li>🔴 <b>{@code value.*} 不参与门控</b> —— 它说的是「这个枚举值依赖什么」，
 *      拿它关选项会关错东西。</li>
 *      <li>🔴 <b>多语言取并集而非交集</b> —— 包只会在默认语言里标星号，
 *      取交集会让门控恒空转（静默失效）。</li>
 *      <li>🔖 <b>坏行要警告</b>，缺文件不警告（缺 lang 是合法的）。</li>
 *    </ul>
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 包 lang 解析单测（钉住「星号是判据来源」这条链的正确性）。 */
class PackLangFileTest {

    /** 🔖 自造的最小 lang 样本：星号标记 + 注释里的显式依赖说明 + 一个枚举值标记。 */
    private static final String SAMPLE = """
            option.ADVANCED_MATERIALS=Advanced Materials
            option.ADVANCED_MATERIALS.comment=Enables specular and normal mapping.\\
            §e[*]§rThis option requires a resource pack which contains specular and/or normal maps.
            option.PARALLAX=Parallax Occlusion Mapping*
            option.PARALLAX_DEPTH=Parallax Depth*
            option.AO=Ambient Occlusion
            value.EMISSIVE.1=AdvMat Only*
            shaderpacks.packTitle=Test Pack
            """;

    @Test
    @DisplayName("🔴 星号标记被剥掉，且选项进星号集合")
    void starMarkerIsStrippedAndRecorded() {
        PackLangFile.Result result = PackLangFile.parse("en_US", SAMPLE);
        assertEquals("Parallax Occlusion Mapping", result.optionLabels().get("PARALLAX"),
                "星号是依赖标记，必须剥掉后再进标签表（它不是文案的一部分）");
        assertTrue(result.starMarkedOptions().contains("PARALLAX"));
        assertTrue(result.starMarkedOptions().contains("PARALLAX_DEPTH"));
        assertFalse(result.starMarkedOptions().contains("AO"), "AO 没有星号 ⇒ 不算声明依赖");
    }

    @Test
    @DisplayName("🔴 value.* 不参与门控（它说的是枚举值，不是选项）")
    void valueEntriesAreNotGathered() {
        PackLangFile.Result result = PackLangFile.parse("en_US", SAMPLE);
        assertFalse(result.starMarkedOptions().contains("EMISSIVE.1"),
                "value.EMISSIVE.1 标的是「这个枚举值依赖高级材质」，"
                        + "拿它去关选项会关错东西 ⇒ 刻意不收");
        assertFalse(result.optionLabels().containsKey("EMISSIVE.1"));
    }

    @Test
    @DisplayName("🔴 注释里的显式依赖说明被收进 hints（星号失效时的后备通道）")
    void commentHintsAreCollected() {
        PackLangFile.Result result = PackLangFile.parse("en_US", SAMPLE);
        String hint = result.hints().get("ADVANCED_MATERIALS");
        assertTrue(hint != null && hint.contains("specular"),
                "ADVANCED_MATERIALS 的说明里明写依赖 specular/normal maps ⇒ 必须收进 hints: " + hint);
        assertFalse(result.starMarkedOptions().contains("ADVANCED_MATERIALS"),
                "ADVANCED_MATERIALS 本身没有星号（它是「被依赖的那个」，不是「依赖别人的」）");
    }

    @Test
    @DisplayName("🔴 多语言的星号取并集（取交集会让门控恒空转）")
    void multiLanguageStarsAreUnioned() {
        Map<String, java.util.function.Supplier<InputStream>> files = new LinkedHashMap<>();
        files.put("en_US", () -> stream("option.PARALLAX=Parallax*\n"));
        files.put("zh_CN", () -> stream("option.PARALLAX=\u89c6\u5dee\u6620\u5c04*\n"));
        PackLangFile.Result result = PackLangFile.parseAll(files);
        assertTrue(result.starMarkedOptions().contains("PARALLAX"),
                "任一语言标了星号就说明包认为它依赖 —— 包往往只在默认语言里标，"
                        + "取交集会让门控静默空转（= 黑地形回归）");
        // 先到先得 ⇒ en_US（语言码升序里 en_US < zh_CN）拿到英文标签。
        assertEquals("Parallax", result.optionLabels().get("PARALLAX"),
                "多语言合并按语言码升序、先到先得 ⇒ 结果可复算（不因加载顺序漂移）");
    }

    @Test
    @DisplayName("坏行产生警告（T11：门控判据不完整必须可见）")
    void badLineProducesWarning() {
        PackLangFile.Result result = PackLangFile.parse("en_US", "option.A=Ok\nthis line has no equals\n");
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertEquals("Ok", result.optionLabels().get("A"), "好行照常解析");
    }

    @Test
    @DisplayName("🔴 缺文件 / 空文本 ⇒ 空结果且无警告（包没有 lang 是合法情形）")
    void emptyInputIsNotAnError() {
        assertTrue(PackLangFile.parse("en_US", "").optionLabels().isEmpty());
        assertTrue(PackLangFile.parse("en_US", "").warnings().isEmpty());
        assertTrue(PackLangFile.parse("en_US", null).warnings().isEmpty());
        assertTrue(PackLangFile.parseAll(Map.of()).optionLabels().isEmpty());
    }

    @Test
    @DisplayName("🔴 Minecraft 单反斜杠转义被还原（不是 Java properties 的双反斜杠）")
    void minecraftEscapeIsDecoded() {
        // 颜色码用 unicode 形式、换行用 \n —— 两者在 Java properties 里语义完全不同：
        // Properties.load 会把 unicode 形式当普通字符（不当转义）、且 \n 的处理也不一致
        // ⇒ 本类自己按「单反斜杠」语义还原。
        assertEquals("§e[*]", PackLangFile.unescape("\\u00a7e[*]"));
        assertEquals("a\nb", PackLangFile.unescape("a\\nb"));
        assertEquals("a\\b", PackLangFile.unescape("a\\\\b"));
    }

    @Test
    @DisplayName("🔴 未知转义保留反斜杠（不静默吞掉 —— 吞掉会改变文本）")
    void unknownEscapeKeepsBackslash() {
        assertEquals("a\\qb", PackLangFile.unescape("a\\qb"),
                "未知序列保留原样：宁可留下可疑文本，也不静默改写");
    }

    @Test
    @DisplayName("注释行与空行被跳过")
    void commentsAndBlanksAreSkipped() {
        PackLangFile.Result result = PackLangFile.parse("en_US", "# a comment\n\n  \noption.A=Value\n");
        assertEquals(Map.of("A", "Value"), result.optionLabels());
    }

    @Test
    @DisplayName("langId 归一（去扩展名，供排序用）")
    void langIdIsNormalized() {
        assertEquals("en_us", PackLangFile.langIdOf("en_US.lang"));
        assertEquals("zh_cn", PackLangFile.langIdOf("zh_CN.LANG"));
        assertEquals("", PackLangFile.langIdOf(null));
    }

    @Test
    @DisplayName("🔴 UTF-8 BOM 被剥掉（否则第一个键匹配不上）")
    void bomIsStripped() {
        String withBom = "\uFEFFoption.PARALLAX=Parallax*\n";
        PackLangFile.Result result = PackLangFile.parse("en_US", withBom);
        assertTrue(result.starMarkedOptions().contains("PARALLAX"),
                "BOM 会让第一个键变成 \\uFEFFoption.PARALLAX ⇒ 星号读不出来 ⇒ 门控空转");
    }

    @Test
    @DisplayName("多行续行（lang 里常见的反斜杠续行）被拼成一条 hint")
    void lineContinuationIsJoined() {
        PackLangFile.Result result = PackLangFile.parse("en_US", """
                option.A.comment=first line\\
                second line
                """);
        String hint = result.hints().get("A");
        assertTrue(hint != null && hint.contains("first line") && hint.contains("second line"),
                "反斜杠续行要拼起来: " + hint);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
