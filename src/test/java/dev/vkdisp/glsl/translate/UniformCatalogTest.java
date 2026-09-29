package dev.vkdisp.glsl.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 【参考调研】D 线单测（uniform 目录）/ 04-SPEC §3.2 内建 uniform 表
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 的 23 条内建 uniform 表（仓库内文档事实，不受版权保护）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0 + 例外条款）→ 按禁止处理
 *    （07-CONSTRAINTS L12 §1.3 陷阱 2 / X21），本任务不读其代码、零代码行并入。
 *    许可证：本文件为独立编写的纯 Java 测试，不含任何外部项目代码，也不含任何第三方着色器包片段
 *    （18-PARALLEL §7.6：第三方 pack 的 .glsl 片段禁止进单测预期值）。
 *    → 能否并入本项目（MIT）：可以（仅采纳不受版权保护的事实性信息）
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5（testImplementation，见 build.gradle 的 F4 基建）逐条断言清单与文档一致 ——
 *    少一条 / 类型不符 / 重名都直接失败，这是 D 线"注入完整"的可执行事实来源。
 * 2. 备选：无 —— 表太小，不值得引入快照比对框架。
 * 3. 我们的差异点：期望值在本测试里**独立硬编码**（不调用 UniformCatalog 生成期望值），
 *    避免"用实现验证实现"的空转断言。
 * 4. 许可证核对结论：本项目 MIT；参考按禁止处理，只读思路，零代码并入
 *    （07-CONSTRAINTS §〇 P1、L12 / X19 / X20 / X21）。
 * 5. 性能基线：测试代码不进运行时；冷路径无性能要求（18-PARALLEL §7.7）。
 */
/**
 * {@link UniformCatalog} 的完整性单测：清单必须与 04-SPEC §3.2 表逐条一致。
 */
class UniformCatalogTest {

    /** 04-SPEC §3.2 表的独立硬编码副本（"名字:类型"，顺序 = 文档表顺序）。 */
    private static final List<String> SPEC_32_TABLE = List.of(
            "gbufferModelView:mat4",
            "gbufferProjection:mat4",
            "gbufferModelViewInverse:mat4",
            "gbufferProjectionInverse:mat4",
            "shadowModelView:mat4",
            "shadowProjection:mat4",
            "cameraPosition:vec3",
            "sunPosition:vec3",
            "moonPosition:vec3",
            "shadowLightPosition:vec3",
            "frameTimeCounter:float",
            "frameCounter:int",
            "viewWidth:float",
            "viewHeight:float",
            "near:float",
            "far:float",
            "wetness:float",
            "rainStrength:float",
            "isEyeInWater:int",
            "worldTime:int",
            "worldDay:int",
            "atlasSize:ivec2",
            "eyeBrightnessSmooth:ivec2");

    private static final Pattern DECLARATION = Pattern.compile("uniform [a-z0-9]+ [A-Za-z_][A-Za-z0-9_]*;");

    @Test
    void tableMatchesSpec32ExactlyInOrder() {
        List<String> actual = new ArrayList<>();
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            actual.add(uniform.name() + ":" + uniform.type());
        }
        assertEquals(SPEC_32_TABLE, actual, "04-SPEC §3.2 的 23 条内建 uniform 必须逐条、按序一致");
        assertEquals(23, actual.size(), "表条目数必须是 23");
    }

    @Test
    void namesAreUnique() {
        Set<String> names = new HashSet<>();
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            assertTrue(names.add(uniform.name()), "重名内建 uniform：" + uniform.name());
        }
    }

    @Test
    void declarationsAreWellFormedGlsl() {
        for (BuiltinUniform uniform : UniformCatalog.uniforms()) {
            String declaration = uniform.declaration();
            assertTrue(DECLARATION.matcher(declaration).matches(),
                    "声明形态必须形如 uniform <type> <name>;，实际：" + declaration);
            assertFalse(uniform.descriptionZh().isEmpty(), "中文语义不许为空");
            assertFalse(uniform.descriptionEn().isEmpty(), "英文语义不许为空");
        }
    }

    @Test
    void findReturnsEntryForBuiltinAndNullForUnknown() {
        BuiltinUniform camera = UniformCatalog.find("cameraPosition");
        assertNotNull(camera);
        assertEquals("vec3", camera.type());
        assertTrue(UniformCatalog.isBuiltin("atlasSize"));
        assertNull(UniformCatalog.find("colortex0"), "非内建名必须返回 null，不许编造");
        assertNull(UniformCatalog.find(null), "null 名字必须安全返回 null");
        assertFalse(UniformCatalog.isBuiltin(null));
    }
}
