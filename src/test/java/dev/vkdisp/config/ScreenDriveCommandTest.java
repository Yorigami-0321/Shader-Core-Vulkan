package dev.vkdisp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScreenDriveCommand} 语法契约：四动作 + 中性值 + 坏输入显式拒绝（T11，解析器不抛）。
 */
class ScreenDriveCommandTest {

    @Test
    void blankOrNullIsNeutralNone() {
        assertInstanceOf(ScreenDriveCommand.None.class, ScreenDriveCommand.parse(null));
        assertInstanceOf(ScreenDriveCommand.None.class, ScreenDriveCommand.parse(""));
        assertInstanceOf(ScreenDriveCommand.None.class, ScreenDriveCommand.parse("   "),
                "空白 = 把驱动值清回中性，不算错误");
    }

    @Test
    void openAndDoneParseToActions() {
        assertInstanceOf(ScreenDriveCommand.Open.class, ScreenDriveCommand.parse("open"));
        assertInstanceOf(ScreenDriveCommand.Open.class, ScreenDriveCommand.parse("  open  "),
                "首尾空白应容忍（手工编辑 TOML 常见）");
        assertInstanceOf(ScreenDriveCommand.Done.class, ScreenDriveCommand.parse("done"));
    }

    @Test
    void setSplitsAtFirstEqualsSoValuesMayContainEquals() {
        ScreenDriveCommand command = ScreenDriveCommand.parse("set:SHARPEN=4");
        ScreenDriveCommand.Set set = assertInstanceOf(ScreenDriveCommand.Set.class, command);
        assertEquals("SHARPEN", set.name());
        assertEquals("4", set.value());

        ScreenDriveCommand.Set free = assertInstanceOf(ScreenDriveCommand.Set.class,
                ScreenDriveCommand.parse("set:PATH=a=b"));
        assertEquals("PATH", free.name());
        assertEquals("a=b", free.value(), "值里再出现 = 必须原样保留（自由文本选项）");
    }

    @Test
    void malformedSetVariantsCarryReasons() {
        var missingEquals = assertInstanceOf(ScreenDriveCommand.Malformed.class,
                ScreenDriveCommand.parse("set:NOEQ"));
        assertTrue(missingEquals.reason().contains("="), missingEquals.reason());

        var emptyName = assertInstanceOf(ScreenDriveCommand.Malformed.class,
                ScreenDriveCommand.parse("set:=4"));
        assertTrue(emptyName.reason().contains("选项名"), emptyName.reason());

        var emptyValue = assertInstanceOf(ScreenDriveCommand.Malformed.class,
                ScreenDriveCommand.parse("set:NAME="));
        assertTrue(emptyValue.reason().contains("值"), emptyValue.reason());
    }

    @Test
    void pageParsesPositiveIntegersOnly() {
        ScreenDriveCommand.Page page = assertInstanceOf(ScreenDriveCommand.Page.class,
                ScreenDriveCommand.parse("page:3"));
        assertEquals(3, page.page1());

        assertInstanceOf(ScreenDriveCommand.Malformed.class, ScreenDriveCommand.parse("page:0"),
                "页号 1 基，0 必须被拒");
        assertInstanceOf(ScreenDriveCommand.Malformed.class, ScreenDriveCommand.parse("page:x"));
        assertInstanceOf(ScreenDriveCommand.Malformed.class, ScreenDriveCommand.parse("page:"));
    }

    @Test
    void unknownInstructionIsMalformedWithUsableReason() {
        ScreenDriveCommand.Malformed m = assertInstanceOf(ScreenDriveCommand.Malformed.class,
                ScreenDriveCommand.parse("reboot"));
        assertTrue(m.reason().contains("open"), "原因里应列出可用指令: " + m.reason());
        assertEquals("reboot", m.raw());
    }
}
