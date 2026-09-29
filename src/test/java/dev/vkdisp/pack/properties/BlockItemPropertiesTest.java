package dev.vkdisp.pack.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】A 线 BlockProperties / ItemProperties 单测 / OF block/item.properties 语法事实
 * 0. 合规核对：参考 = OptiFine 官方文档 ID 映射语法 + Iris IdMap（LGPL-3.0，只读事实）；零代码复制，ARR 不碰。
 *    → 能否并入本项目 MIT：可以。本测试期望值独立硬编码，不含第三方 pack 片段（§7.6）。
 * 1. 主实现：逐格式断言（短格式 / 长格式 namespace:path / 属性格式 :type=value / 条件编译 / 重复 ID 报错）。
 * 2. 备选：无。
 * 3. 差异点：无。
 * 4. 许可证核对结论：MIT；零第三方代码。
 * 5. 性能基线：测试不进运行时。
 */
class BlockItemPropertiesTest {

    @Test
    void blockParsesShortLongAndAttributeFormats() {
        String text = """
                block.31=red_flower yellow_flower reeds
                block.32=minecraft:red_flower ic2:nether_flower
                block.33=minecraft:red_flower:type=white_tulip botania:reeds:type=green
                """;
        BlockProperties p = BlockProperties.parse(text);

        IdMapProperties.MappedId r0 = p.mappings().get(31).get(0);
        assertEquals("red_flower", r0.id());
        assertTrue(r0.properties().isEmpty());

        IdMapProperties.MappedId r1 = p.mappings().get(32).get(0);
        assertEquals("minecraft:red_flower", r1.id());

        IdMapProperties.MappedId r2 = p.mappings().get(33).get(0);
        assertEquals("minecraft:red_flower", r2.id());
        assertEquals("white_tulip", r2.properties().get("type"));

        IdMapProperties.MappedId r3 = p.mappings().get(33).get(1);
        assertEquals("botania:reeds", r3.id());
        assertEquals("green", r3.properties().get("type"));
    }

    @Test
    void itemParsesNamespacedIds() {
        String text = "item.5000=minecraft:diamond\nitem.5001=ic2:tesla_coil\n";
        ItemProperties p = ItemProperties.parse(text);
        assertEquals("minecraft:diamond", p.mappings().get(5000).get(0).id());
        assertEquals("ic2:tesla_coil", p.mappings().get(5001).get(0).id());
    }

    @Test
    void duplicateBlockIdIsRejected() {
        String text = "block.31=a\nblock.31=b\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> BlockProperties.parse(text));
        assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
    }

    @Test
    void nonIntegerIdIsRejected() {
        String text = "block.abc=minecraft:stone\n";
        assertThrows(IllegalArgumentException.class, () -> BlockProperties.parse(text));
    }

    @Test
    void conditionalBlockEntriesGatedByMacro() {
        String text = "block.31=a\n#ifdef EXTRA\nblock.32=b\n#endif\n";
        BlockProperties off = BlockProperties.parse(text);
        assertTrue(off.mappings().containsKey(31));
        assertEquals(Set.of(31), off.mappings().keySet());

        BlockProperties on = BlockProperties.parse(text, Set.of("EXTRA"));
        assertEquals(Set.of(31, 32), on.mappings().keySet());
    }

    @Test
    void fixtureMinimalPackParses() {
        BlockProperties b = BlockProperties.parse(
                new java.io.InputStreamReader(require("/packs/minimal/block.properties")));
        ItemProperties i = ItemProperties.parse(
                new java.io.InputStreamReader(require("/packs/minimal/item.properties")));
        assertTrue(b.mappings().containsKey(31));
        assertTrue(i.mappings().containsKey(5000));
    }

    private static java.io.InputStream require(String path) {
        java.io.InputStream in = BlockItemPropertiesTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("fixture 缺失：" + path);
        }
        return in;
    }
}
