package dev.vkdisp.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pack.properties.ShaderProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 【参考调研】移植件的行为判据（bufferObject / image / 格式表 / 程序名 / 兜底链 / 混合）
 * 0. 合规核对（第 0 步闸门）：
 *    被测物 = {@code pack/model} 的 Vitrail LGPL-3.0 移植件（@4a45f44，其中 TargetFormat /
 *    ProgramFallbacks / BufferObject / ImageInformation 的表与文法转录自 Iris LGPL-3.0，见 NOTICE.md 台账）；
 *    本文件自身只写断言，不复制任何上游文本。
 *    → 能否并入本项目（LGPL-3.0-or-later）：可以（测试代码不进分发 jar）。
 * 1. 为什么这批要单独有判据：07 §〇 P1 说「搬来的每一行都要能指回来源」，而来源正确性要靠**读出来一样**才算数 ——
 *    这些规则（索引 ≤12、第十七条被拒、四词相对式、双空格使混合失效、单空格使混合失效、{@code dh_shadow}
 *    属 shadow 几何、{@code gbuffers_clouds} 兜底到 textured）没有一条能靠直觉推对。
 * 4. 许可证核对结论：本项目 LGPL-3.0-or-later；判据只调用移植件的公开 API。
 */
final class PortedPackFeatureModelTest {

    @Test
    @DisplayName("bufferObject：索引 0..12 收，13 丢弃并点名原因")
    void bufferObjectIndexCeiling() {
        assertTrue(BufferObject.parse("12", "1024", new java.util.LinkedHashMap<>()) == null);
        assertEquals("only indices 0 to 12 are allowed",
                BufferObject.parse("13", "1024", new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("bufferObject：size<1 是「关掉这条 buffer」，不是错误索引")
    void bufferObjectSizeBelowOneIsDropped() {
        assertEquals("size below one disables the buffer",
                BufferObject.parse("0", "0", new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("bufferObject：两词=绝对大小+可选名，四词=相对式，三词不成形")
    void bufferObjectWordForms() {
        Map<Integer, BufferObject> one = new java.util.LinkedHashMap<>();
        assertNull(BufferObject.parse("1", "4096 ssboData", one));
        assertEquals(Optional.of("ssboData"), one.get(1).name());
        assertFalse(one.get(1).relative());

        Map<Integer, BufferObject> rel = new java.util.LinkedHashMap<>();
        assertNull(BufferObject.parse("2", "4 true 0.5 1.0", rel));
        assertTrue(rel.get(2).relative());
        assertEquals(0.5F, rel.get(2).scaleX());
        assertEquals(Optional.empty(), rel.get(2).name());

        assertEquals("a relative buffer takes four words",
                BufferObject.parse("3", "4 true 0.5", new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("image：第十七条被拒前就已按词数/格式逐条判死")
    void imageGrammar() {
        List<ImageInformation> ok = new java.util.ArrayList<>();
        assertNull(ImageInformation.parse("footprint",
                "none RGBA RGBA8 UNSIGNED_BYTE false false 64 64", Map.of(), ok));
        assertEquals(PackTexture.Shape.TEXTURE_2D, ok.get(0).shape());
        assertEquals(Optional.empty(), ok.get(0).sampler());

        assertEquals("expected at least six words",
                ImageInformation.parse("x", "none RGBA RGBA8 UNSIGNED_BYTE false", Map.of(),
                        new java.util.ArrayList<>()));
        assertEquals("format NOPE internal RGBA8 pixel type UNSIGNED_BYTE",
                ImageInformation.parse("x", "none NOPE RGBA8 UNSIGNED_BYTE false false 8 8", Map.of(),
                        new java.util.ArrayList<>()));
    }

    @Test
    @DisplayName("image：7/8/9 词分别是 1D/2D/3D；相对式必须恰好八词")
    void imageShapes() {
        List<ImageInformation> out = new java.util.ArrayList<>();
        ImageInformation.parse("a", "colortex1 RED R8 UNSIGNED_BYTE false false 32", Map.of(), out);
        ImageInformation.parse("b", "colortex1 RED R8 UNSIGNED_BYTE false false 32 32 32",
                Map.of(), out);
        assertEquals(PackTexture.Shape.TEXTURE_1D, out.get(0).shape());
        assertEquals(PackTexture.Shape.TEXTURE_3D, out.get(1).shape());

        assertEquals("a relative image takes two size words",
                ImageInformation.parse("c", "none RED R8 UNSIGNED_BYTE false true 0.5", Map.of(), out));

        List<ImageInformation> rel = new java.util.ArrayList<>();
        assertNull(ImageInformation.parse("f", "none RGBA RGBA8 FLOAT false true 0.5 0.25",
                Map.of(), rel));
        assertTrue(rel.get(0).relative());
        assertEquals(PackTexture.Shape.TEXTURE_2D, rel.get(0).shape());
    }

    @Test
    @DisplayName("image：尺寸位写选项名时由 defines 落成整数（Iris 先跑预处理器的那条规则的替代）")
    void imageSizeTokenFallsBackToDefines() {
        List<ImageInformation> out = new java.util.ArrayList<>();
        assertNull(ImageInformation.parse("d", "none RED R8 UNSIGNED_BYTE false false footprintSize",
                Map.of("footprintSize", "256"), out));
        assertEquals(256, out.get(0).width());

        assertEquals("size is not a number",
                ImageInformation.parse("e", "none RED R8 UNSIGNED_BYTE false false missing",
                        Map.of(), out));
    }

    @Test
    @DisplayName("TargetFormat：三通道被提升且 alphaAdded=true；乱名字回落但不抛")
    void targetFormatPromotion() {
        TargetFormat.Resolution rgb8 = TargetFormat.resolve("RGB8");
        assertEquals(TargetFormat.Reason.PROMOTED, rgb8.reason());
        assertTrue(rgb8.alphaAdded());
        assertEquals(TargetFormat.RGBA8_UNORM, rgb8.used());

        assertEquals(TargetFormat.Reason.EXACT, TargetFormat.resolve("R11F_G11F_B10F").reason());
        assertEquals(TargetFormat.Reason.REPLACED, TargetFormat.resolve("RGB9_E5").reason());
        TargetFormat.Resolution typo = TargetFormat.resolve("RGBA8_UNORMM");
        assertEquals(TargetFormat.Reason.UNKNOWN, typo.reason());
        assertEquals(TargetFormat.RGBA8_UNORM, typo.used());
        assertEquals("RGBA", TargetFormat.defaultFormat().declared());
        assertTrue(TargetFormat.resolve("RGBA8UI").used().integer());
    }

    @Test
    @DisplayName("PixelType：packed 给的是整个 texel 的宽度，乘通道数就会向文件要三倍字节")
    void pixelTypePackedVsPlain() {
        assertEquals(3L, PixelType.UNSIGNED_BYTE.bytesPerTexel(PixelFormat.RGB));
        assertEquals(12L, PixelType.FLOAT.bytesPerTexel(PixelFormat.RGB));
        // packed 的整个 texel 就是一个字：按普通类型那样乘通道数，就会向文件要四倍字节。
        assertEquals(4L, PixelType.UNSIGNED_INT_10_10_10_2.bytesPerTexel(PixelFormat.RGBA));
        assertEquals(Optional.empty(), PixelType.parse("UNSIGNED_BYTE_9_9_9"));
    }

    @Test
    @DisplayName("BlendMode：off≠读不懂；分隔符是单个空格 ⇒ 双空格使整条失效")
    void blendModeForms() {
        assertEquals(Optional.of(BlendMode.OFF), BlendMode.parse("off"));
        BlendMode four = BlendMode.parse("src_alpha ONE ZERO ONE").orElseThrow();
        assertEquals("SRC_ALPHA ONE ZERO ONE", four.toString());
        BlendMode two = BlendMode.parse("ONE ZERO").orElseThrow();
        assertEquals("ONE ZERO ONE ZERO", two.toString());
        assertEquals(Optional.empty(), BlendMode.parse("ONE  ZERO SRC_ONE ONE"));
    }

    @Test
    @DisplayName("ProgramNames：dh_shadow 属 shadow 几何；_a 是计算后缀；编号上限 99")
    void programNamesEdges() {
        assertTrue(ProgramNames.shadowGeometry("dh_shadow"));
        assertTrue(ProgramNames.geometry("dh_shadow"));
        assertEquals(Optional.empty(), ProgramNames.parse("composite100"));
        ProgramNames.ProgramName c = ProgramNames.parse("composite21_a").orElseThrow();
        assertEquals("composite", c.family());
        assertEquals(21, c.slot());
        assertTrue(c.isCompute());
        assertEquals("deferred4", ProgramNames.computeBase("deferred4_a").orElseThrow());
        assertEquals("composite2", ProgramNames.computeBase("composite2").orElseThrow());
    }

    @Test
    @DisplayName("ProgramNames：帧序与指令折叠序是两张不同的表（放一处就会漂）")
    void programOrdering() {
        assertTrue(ProgramNames.before("composite2", "composite10"));
        assertTrue(ProgramNames.before("prepare", "prepare1"));
        assertEquals(3, ProgramNames.frameRank("gbuffers_terrain"));
        assertEquals(3, ProgramNames.directiveRank("gbuffers_terrain"));
        assertEquals(0, ProgramNames.directiveRank("shadowcomp"));
        assertEquals(1, ProgramNames.frameRank("shadowcomp"));
    }

    @Test
    @DisplayName("ProgramFallbacks：水→terrain→textured_lit→textured→basic；云跟 Iris 走 textured")
    void fallbackChain() {
        assertEquals(List.of("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit",
                        "gbuffers_textured", "gbuffers_basic"),
                ProgramFallbacks.chain("gbuffers_water"));
        assertEquals(List.of("gbuffers_clouds", "gbuffers_textured", "gbuffers_basic"),
                ProgramFallbacks.chain("gbuffers_clouds"));
        assertEquals(List.of("shadow_lightning", "shadow_entities", "shadow"),
                ProgramFallbacks.chain("shadow_lightning"));
        assertEquals(List.of("composite3"), ProgramFallbacks.chain("composite3"));
    }

    @Test
    @DisplayName("ProgramFallbacks：唯一的非拒绝混合覆盖挂在「被问的名字」上")
    void spiderEyesBlend() {
        BlendMode eyes = ProgramFallbacks.blendOverride("gbuffers_spidereyes");
        assertEquals("SRC_ALPHA ONE ZERO ONE", eyes.toString());
        assertNull(ProgramFallbacks.blendOverride("gbuffers_terrain"));
        assertNull(ProgramFallbacks.blendOverride("gbuffers_textured"));
    }

    @Test
    @DisplayName("TextureStage：七个名字闭合；final 与 composite 同族；shadow 不在表内")
    void textureStages() {
        assertEquals(List.of("setup", "begin", "shadowcomp", "prepare", "gbuffers", "deferred",
                "composite"),
                java.util.Arrays.stream(TextureStage.values()).map(Enum::name)
                        .map(String::toLowerCase).toList());
        assertEquals(Optional.empty(), TextureStage.parse("shadow"));
        assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("gbuffers_water"));
        assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("shadow"));
        assertEquals(Optional.of(TextureStage.COMPOSITE), TextureStage.of("final"));
    }

    @Test
    @DisplayName("PackTexture：冒号=游戏的资源；Raw.bytes() 是分辨截断 blob 的唯一依据")
    void packTextureRules() {
        PackTexture.Raw raw = new PackTexture.Raw(PackTexture.Shape.TEXTURE_2D,
                TargetFormat.resolve("RGBA8"), 16, 16, 0, PixelFormat.RGBA,
                PixelType.UNSIGNED_BYTE);
        assertEquals(1024L, raw.bytes());
        assertTrue(PackTexture.gameResource("minecraft:textures/atlas/blocks.png"));
        assertFalse(PackTexture.gameResource("textures/colortex.png"));
    }

    @Test
    @DisplayName("ShaderProperties：两族进结构化视图，被拒的行点名，原文仍留在 directives()")
    void propertiesDispatch() {
        ShaderProperties parsed = ShaderProperties.parse("""
                sliders=A
                bufferObject.0=4096
                bufferObject.1=8192 ssboName
                bufferObject.13=1024
                bufferObject.2=4 true 0.5 1.0
                image.footprint=none RED R8 UNSIGNED_BYTE false false 64 64
                image.broken=none NOPE R8 UNSIGNED_BYTE false false 4 4
                """);
        assertEquals(3, parsed.bufferObjects().buffers().size());
        assertTrue(parsed.bufferObjects().hasName("ssboName"));
        assertEquals(List.of("bufferObject.13=1024 : only indices 0 to 12 are allowed"),
                parsed.bufferObjects().dropped());
        assertEquals(1, parsed.images().images().size());
        assertEquals(1, parsed.images().dropped().size());
        assertEquals("4096", parsed.directives().get("bufferObject.0"));
    }

    @Test
    @DisplayName("TargetName：legacy 别名按位置解，不按猜")
    void legacyAliasesResolveByPosition() {
        assertEquals(0, TargetName.index("gcolor").getAsInt());
        assertEquals(1, TargetName.index("gdepth").getAsInt());
        assertEquals(2, TargetName.index("gnormal").getAsInt());
        assertEquals(3, TargetName.index("composite").getAsInt());
        assertEquals(4, TargetName.index("gaux1").getAsInt());
        assertEquals(7, TargetName.index("gaux4").getAsInt());
        assertEquals(7, TargetName.index("colortex7").getAsInt());
        assertEquals(OptionalInt.empty(), TargetName.index("gaux5"), "别名表到 gaux4 为止");
        assertEquals(OptionalInt.empty(), TargetName.index("gdepthtex"), "它不是颜色目标");
        assertEquals(OptionalInt.empty(), TargetName.index("colortex32"), "上限 32 是排他的");
    }

    @Test
    @DisplayName("TargetName：colorimg 不认别名（参考实现自己构造的集合就是这个形状）")
    void imageNameSetHasNoAliases() {
        assertEquals(7, TargetName.imageIndex("colorimg7").getAsInt());
        assertEquals(OptionalInt.empty(), TargetName.imageIndex("gaux4img"));
        assertEquals(OptionalInt.empty(), TargetName.imageIndex("colorimg100"), "两位数字封顶");
        assertEquals(Optional.of("gaux4"), TargetName.legacyAlias(7));
        assertEquals(Optional.empty(), TargetName.legacyAlias(8));
    }

    @Test
    @DisplayName("TargetName：带后缀的指令名拆成索引+后缀；程序名的目录形态被剥平")
    void suffixedDirectivesAndBareNames() {
        TargetName.Suffixed a = TargetName.split("gaux1Format").orElseThrow();
        assertEquals(4, a.index());
        assertEquals("Format", a.suffix());

        TargetName.Suffixed b = TargetName.split("colortex14Clear").orElseThrow();
        assertEquals(14, b.index());
        assertEquals("Clear", b.suffix());

        assertEquals(Optional.empty(), TargetName.split("colortex7"), "没有后缀不是 split 的活");
        assertEquals("composite1", TargetName.bareName("world0/composite1"));
        assertEquals("composite1", TargetName.bareName("composite1"));
    }

    @Test
    @DisplayName("AlphaTest：off/false 都是不测，读不懂是 empty 而不是不测")
    void alphaTestParsing() {
        assertEquals(Optional.of(AlphaTest.OFF), AlphaTest.parse("off"));
        assertEquals(Optional.of(AlphaTest.OFF), AlphaTest.parse("FALSE"));
        assertEquals(Optional.empty(), AlphaTest.parse("GREATER"), "只有一个词 ⇒ empty");
        assertEquals(Optional.empty(), AlphaTest.parse("GREATER  0.5"), "双空格是参考实现会拒的一行");
        assertEquals(AlphaTest.Function.GREATER,
                AlphaTest.parse("GL_GREATER 0.5").orElseThrow().function());
        // 第三个 token 忽略而不是拒：行尾一句注释不该害得包丢掉阈值。
        assertEquals(0.5F, AlphaTest.parse("GREATER 0.5 trailing").orElseThrow().reference());
        assertFalse(AlphaTest.OFF.tests());
        assertTrue(AlphaTest.CUTOUT.tests());
    }

    @Test
    @DisplayName("AlphaTest：发出的是取反形态，阈值按包写的样子印（不许 1.0E-4）")
    void alphaTestDiscardText() {
        assertEquals("", AlphaTest.OFF.discard("x"));
        assertEquals("discard;", new AlphaTest(AlphaTest.Function.NEVER, 0F).discard("x"));
        assertEquals("if (!(x > 0.5)) { discard; }", AlphaTest.CUTOUT.discard("x"));
        // 🔴 实测：上游注释承诺「印成包写的 0.0001」，而它自己的实现给的是 0.00010
        //   （Float.toString(0.0001F) 是 "1.0E-4"，BigDecimal 的 scale 把它落成五位）。
        //   数值等价、GLSL 合法 ⇒ 本仓**按上游行为断言**，不悄悄改成注释承诺的样子；
        //   要改就得在 NOTICE.md 台账里把这条声明成我方改动。
        assertEquals("if (!(x > 0.00010)) { discard; }", AlphaTest.NON_ZERO.discard("x"));
        assertEquals("if (!(x > 0.1)) { discard; }", AlphaTest.ONE_TENTH.discard("x"));
        assertEquals("if (!(x == 0.25)) { discard; }",
                new AlphaTest(AlphaTest.Function.EQUAL, 0.25F).discard("x"));
    }

    @Test
    @DisplayName("ShaderProperties：alphaTest 进结构化视图，读不懂的行点名，原文不丢")
    void alphaTestsDispatch() {
        ShaderProperties parsed = ShaderProperties.parse("""
                alphaTest.gbuffers_terrain=GREATER 0.5
                alphaTest.gbuffers_hand=off
                alphaTest.gbuffers_water=not-a-test
                """);
        assertEquals(2, parsed.alphaTests().size());
        assertEquals(AlphaTest.Function.GREATER,
                parsed.alphaTests().get("gbuffers_terrain").function());
        assertEquals(AlphaTest.OFF, parsed.alphaTests().get("gbuffers_hand"));
        assertEquals(1, parsed.alphaTestDropped().size());
        assertEquals("GREATER 0.5", parsed.directives().get("alphaTest.gbuffers_terrain"));
    }

    @Test
    @DisplayName("回归针：链侧解析器走移植件的表，不许再把 gaux* 硬编成某一个槽位")
    void chainResolverDelegatesToThePortedTable() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of("src/main/java/dev/vkdisp/bridge/FrameApi.java");
        if (!java.nio.file.Files.isReadable(file)) {
            return;
        }
        String src = java.nio.file.Files.readString(file);
        assertTrue(src.contains("TargetName.index(name)"),
                "颜色目标的名字→槽位必须由 pack/model/TargetName 回答");
        assertFalse(src.contains("poolView(4)"),
                "缺陷针：此前所有 gaux* 一律绑 colortex4（注释写的却是 N+3）⇒ 不许回来");
    }
}

