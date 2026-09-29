package dev.vkdisp.pack.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】A 线 DimensionProperties 单测 / Iris dimension.properties 语法事实
 * 0. 合规核对：参考 = Iris 官方文档 dimension.properties 语法（仓库外事实，零代码复制）；本测试独立硬编码断言，
 *    不含第三方 pack 片段（§7.6）。→ 能否并入本项目 MIT：可以。
 * 1. 主实现：断言 dimension.<文件夹>=<维度ID> 解析、* 通配识别、重复文件夹报错。
 * 2. 备选：无。
 * 3. 差异点：worldN 文件夹结构由 B 线识别，本类只消费 dimension.properties 显式映射。
 * 4. 许可证核对结论：MIT；零第三方代码。
 * 5. 性能基线：测试不进运行时。
 */
class DimensionPropertiesTest {

    @Test
    void parsesDimensionEntriesAndWildcard() {
        String text = """
                dimension.netherShaders=minecraft:the_nether
                dimension.supportsShadows=minecraft:the_overworld minecraft:the_end *
                """;
        DimensionProperties p = DimensionProperties.parse(text);

        assertEquals(java.util.List.of("minecraft:the_nether"),
                p.entries().get("netherShaders").dimensionIds());
        assertFalse(p.entries().get("netherShaders").wildcard());

        DimensionProperties.DimensionEntry e = p.entries().get("supportsShadows");
        assertEquals(java.util.List.of("minecraft:the_overworld", "minecraft:the_end"), e.dimensionIds());
        assertTrue(e.wildcard(), "supportsShadows 应识别到 * 通配");
    }

    @Test
    void duplicateFolderIsRejected() {
        String text = "dimension.a=minecraft:the_overworld\ndimension.a=minecraft:the_end\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> DimensionProperties.parse(text));
        assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
    }

    @Test
    void fixtureMinimalPackParses() {
        DimensionProperties p = DimensionProperties.parse(
                new java.io.InputStreamReader(require("/packs/minimal/dimension.properties")));
        assertTrue(p.entries().containsKey("netherShaders"));
        assertTrue(p.entries().containsKey("supportsShadows"));
    }

    private static java.io.InputStream require(String path) {
        java.io.InputStream in = DimensionPropertiesTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("fixture 缺失：" + path);
        }
        return in;
    }
}
