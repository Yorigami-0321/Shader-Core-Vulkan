package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】VersionAdapter 单元测试 / shaderc 140 地板 + glslang location 门控原文
 * 0. 合规核对（第 0 步闸门）：
 *    参考对象 = 三份公开事实（零代码行并入）：
 *    ① 本仓库 runClient 实测 shaderc 原文（2026-09-30）：{@code Desktop shaders for Vulkan
 *       SPIR-V require version 140 or higher}；
 *    ② 本仓库 runClient 实测第 7 类驱动错误（2026-09-30）：
 *       {@code 'location qualifier on input' : not supported for this version or the enabled extensions}
 *       （composite.fsh:223/225、deferred.fsh:214）；
 *    ③ glslang 主线源码（BSD-3-Clause；ParseHelper.cpp EvqVaryingIn / EvqVaryingOut 门控、
 *       Versions.cpp profileRequires 行为判定，2026-09-30 取证）：片元 in / 顶点 out 要求
 *       「≥ 410 或启用 GL_ARB_separate_shader_objects」；Enable / Require / Warn 判启用。
 *    许可证：本文件为独立编写的测试，不含任何外部项目代码。
 * 1. 官方/主实现：GLSL 公开语义（#version 指令形态与 profile 尾词）；断言值取自门控原文。
 * 2. 备选：无 —— 直接喂字符串、断言输出字节（08-TESTING §8.1）。
 * 3. 被测差异点（与 {@link VersionAdapter} 类注释 3 对齐）：三段升级规则、注释保护、
 *    不合成、等行数零诊断、幂等、SSO 行为白名单、ES profile 恒原样。
 * 4. 许可证核对结论：MIT 项目内自写测试（07-CONSTRAINTS §〇 P1）。
 * 5. 性能基线：本文件无性能断言（纯单测）。
 */
class VersionAdapterTest {

    @Test
    void lowVersionIsUpgradedInPlace() {
        VersionAdapter.Result result = VersionAdapter.upgrade(
                "#version 120\nvoid main() {}\n");

        assertEquals("#version 410\nvoid main() {}\n", result.text(),
                "120 < 140 必须就地升到 410，其余字节不动");
        assertEquals(1, result.upgradedCount(), "恰好改写一条版本指令");
        assertTrue(result.diagnostics().isEmpty(), "纯方言改写不产生诊断");
    }

    @Test
    void midVersionsWithoutSsoAreUpgradedTo410() {
        assertEquals("#version 410\n", VersionAdapter.upgrade("#version 140\n").text(),
                "140 达到 shaderc 地板但未过 location 门控，无 SSO → 410");
        assertEquals("#version 410\n", VersionAdapter.upgrade("#version 150\n").text(),
                "150 无 SSO → 410");
        assertEquals("#version 410\n", VersionAdapter.upgrade("#version 330\n").text(),
                "330 无 SSO → 410（p41b 第 7 类错误的最小修复形态）");
        assertEquals("#version 410 core\n", VersionAdapter.upgrade("#version 330 core\n").text(),
                "330 core 无 SSO → 410 core，profile 尾词原位保留");
    }

    @Test
    void versionsAtOrAbove410AreUntouched() {
        for (String source : List.of("#version 410\n", "#version 410 core\n", "#version 450\n")) {
            VersionAdapter.Result result = VersionAdapter.upgrade(source);
            assertEquals(source, result.text(), "N ≥ 410 已过 location 门控，必须原样: " + source);
            assertEquals(0, result.upgradedCount(), "不得计入改写: " + source);
        }
    }

    @Test
    void midVersionWithActiveSsoIsUntouched() {
        for (String behavior : List.of("enable", "require", "warn")) {
            String source = "#version 330\n"
                    + "#extension GL_ARB_separate_shader_objects : " + behavior + "\n"
                    + "void main() {}\n";
            VersionAdapter.Result result = VersionAdapter.upgrade(source);
            assertEquals(source, result.text(),
                    "330 + 活跃 SSO（" + behavior + "）必须字节级原样 —— fixture 形态");
            assertEquals(0, result.upgradedCount(), "已满足门控不得计入改写");
        }
        String midWithSso = "#version 150\n"
                + "#extension GL_ARB_separate_shader_objects : require\n";
        assertEquals(midWithSso, VersionAdapter.upgrade(midWithSso).text(),
                "150 + 活跃 SSO 同样原样");
    }

    @Test
    void ssoBehaviorDisableDoesNotCountAsActive() {
        String source = "#version 330\n"
                + "#extension GL_ARB_separate_shader_objects : disable\n";
        assertEquals("#version 410\n#extension GL_ARB_separate_shader_objects : disable\n",
                VersionAdapter.upgrade(source).text(),
                "disable 不满足 profileRequires 启用判定 → 仍需升号");
    }

    @Test
    void commentedSsoDoesNotCountAsActive() {
        String source = "#version 330\n"
                + "// #extension GL_ARB_separate_shader_objects : require\n"
                + "void main() {}\n";
        assertEquals("#version 410\n"
                + "// #extension GL_ARB_separate_shader_objects : require\n"
                + "void main() {}\n",
                VersionAdapter.upgrade(source).text(),
                "注释里的 SSO 指令不得参与门控判定");
    }

    @Test
    void lowVersionWithSsoStillUpgradedToFloor() {
        String source = "#version 120\n"
                + "#extension GL_ARB_separate_shader_objects : require\n";
        assertEquals("#version 410\n"
                + "#extension GL_ARB_separate_shader_objects : require\n",
                VersionAdapter.upgrade(source).text(),
                "shaderc 140 地板优先：SSO 豁免不了地板，120 恒升");
    }

    @Test
    void esProfileIsNeverUpgraded() {
        for (String source : List.of("#version 300 es\n", "#version 310 es\n")) {
            VersionAdapter.Result result = VersionAdapter.upgrade(source);
            assertEquals(source, result.text(),
                    "ES 序列无 410，升号会产出不存在的 410 es —— 必须原样: " + source);
            assertEquals(0, result.upgradedCount(), "ES profile 不得计入改写");
        }
        String esWithBody = "#version 300 es\nvoid main() {}\n";
        assertEquals(esWithBody, VersionAdapter.upgrade(esWithBody).text(),
                "带主体的 ES 源同样原样");
    }

    @Test
    void profileSuffixAndTrailingCommentArePreserved() {
        String source = "#version 110 compatibility // legacy profile\nvoid main() {}\n";
        String expected = "#version 410 compatibility // legacy profile\nvoid main() {}\n";

        VersionAdapter.Result result = VersionAdapter.upgrade(source);

        assertEquals(expected, result.text(), "只替换版本数字，profile 与行尾注释原位保留");
        assertEquals(1, result.upgradedCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void commentedFakeDirectivesAreProtected() {
        String source = "/*\n#version 100\n*/\n"
                + "#version 120\nvoid main() {}\n";
        String expected = "/*\n#version 100\n*/\n"
                + "#version 410\nvoid main() {}\n";

        VersionAdapter.Result result = VersionAdapter.upgrade(source);

        assertEquals(expected, result.text(), "注释体内的伪指令不动，真实指令升级");
        assertEquals(1, result.upgradedCount(), "只统计真实指令");
    }

    @Test
    void upgradeIsIdempotent() {
        VersionAdapter.Result first = VersionAdapter.upgrade("#version 120\nvoid main() {}\n");
        VersionAdapter.Result second = VersionAdapter.upgrade(first.text());

        assertEquals(first.text(), second.text(), "410 不在改写条件内，第二遍逐字节不变");
        assertEquals(0, second.upgradedCount(), "第二遍不再计入改写");
        assertTrue(second.diagnostics().isEmpty());
    }

    @Test
    void crlfIsPreservedWithoutBareLf() {
        VersionAdapter.Result result = VersionAdapter.upgrade("#version 120\r\n");

        assertEquals("#version 410\r\n", result.text(), "CRLF 原样保留，不得退化成裸 LF");
        assertEquals(1, result.upgradedCount());
    }

    @Test
    void indentedDirectiveIsUpgraded() {
        VersionAdapter.Result result = VersionAdapter.upgrade("  #version 100\nvoid main() {}\n");

        assertEquals("  #version 410\nvoid main() {}\n", result.text(),
                "行首空白由正则消费、改写落在原下标，缩进原样保留");
        assertEquals(1, result.upgradedCount());
    }

    @Test
    void missingDirectiveIsNotSynthesized() {
        VersionAdapter.Result result = VersionAdapter.upgrade("void main() {}\n");

        assertEquals("void main() {}\n", result.text(), "不合成插入（行数契约）");
        assertEquals(0, result.upgradedCount());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void overflowVersionDigitsAreLeftUntouched() {
        String source = "#version 99999999999999999999999999999\n";
        VersionAdapter.Result result = VersionAdapter.upgrade(source);

        assertEquals(source, result.text(), "溢出版本号原样，交由驱动显式报错（T11）");
        assertEquals(0, result.upgradedCount());
    }

    @Test
    void nullAndEmptyInputAreSafe() {
        VersionAdapter.Result fromNull = VersionAdapter.upgrade(null);
        assertEquals("", fromNull.text(), "null 按空串处理");
        assertEquals(0, fromNull.upgradedCount());
        assertTrue(fromNull.diagnostics().isEmpty());

        VersionAdapter.Result fromEmpty = VersionAdapter.upgrade("");
        assertEquals("", fromEmpty.text(), "空串原样");
        assertEquals(0, fromEmpty.upgradedCount());
    }
}
