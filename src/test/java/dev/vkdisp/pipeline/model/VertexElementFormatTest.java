package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线单测 / 顶点分量格式字节数（04-SPEC §4 类型列）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（类型列：vec2f / vec3f / vec2s / vec3b / vec4ub）；
 *    ② 公开的格式事实：f = 4 字节、s = 2 字节、b / ub = 1 字节。
 *    许可证：JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；本仓库 docs 为 MIT 项目自有文档。
 *    参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触 —— 按 docs/07-CONSTRAINTS.md L12
 *    「判不过就换参考，不再读它的代码」。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 标准断言（assertEquals / assertThrows / assertTrue），用例取值全部照抄
 *    本仓库 docs/04-SPEC.md §4 的属性表与 docs/08-TESTING.md §5 的 stride 自检要求。
 * 2. 备选：手写 main() 断言 —— 否决（F4 已接好 JUnit 5，且失败要能在 ./gradlew test 里显式红）。
 * 3. 我们的差异点：数值断言逐项照抄 04-SPEC §4 的类型列，另用 assertThrows/empty 断言未知类型必须显式不可用（T11）。
 * 4. 许可证核对：本项目 MIT；JUnit EPL-2.0 仅测试期依赖；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** 顶点分量格式：数值断言 + 未知类型显式不可用（T11）。 */
class VertexElementFormatTest {

    @Test
    void byteSizesMatch04SpecTable() {
        assertEquals(8, VertexElementFormat.VEC2F.byteSize(), "vec2f = 2 x float32");
        assertEquals(12, VertexElementFormat.VEC3F.byteSize(), "vec3f = 3 x float32");
        assertEquals(16, VertexElementFormat.VEC4F.byteSize(), "vec4f = 4 x float32");
        assertEquals(4, VertexElementFormat.VEC2S.byteSize(), "vec2s = 2 x int16");
        assertEquals(3, VertexElementFormat.VEC3B.byteSize(), "vec3b = 3 x int8");
        assertEquals(4, VertexElementFormat.VEC4UB.byteSize(), "vec4ub = 4 x uint8");
    }

    @Test
    void glslNamesMatch04SpecNaming() {
        assertEquals("vec2f", VertexElementFormat.VEC2F.glslName());
        assertEquals("vec3f", VertexElementFormat.VEC3F.glslName());
        assertEquals("vec4f", VertexElementFormat.VEC4F.glslName());
        assertEquals("vec2s", VertexElementFormat.VEC2S.glslName());
        assertEquals("vec3b", VertexElementFormat.VEC3B.glslName());
        assertEquals("vec4ub", VertexElementFormat.VEC4UB.glslName());
    }

    @Test
    void componentLayoutIsExplicitAndConsistent() {
        for (VertexElementFormat format : VertexElementFormat.values()) {
            assertEquals(format.componentCount() * format.componentBytes(), format.byteSize(),
                    "byteSize = componentCount x componentBytes：" + format.glslName());
            assertTrue(format.componentCount() >= 2 && format.componentCount() <= 4,
                    "分量个数限定在 2..4：" + format.glslName());
        }
        assertEquals(4, VertexElementFormat.VEC2F.componentBytes());
        assertEquals(2, VertexElementFormat.VEC2S.componentBytes());
        assertEquals(1, VertexElementFormat.VEC3B.componentBytes());
        assertEquals(1, VertexElementFormat.VEC4UB.componentBytes());
    }

    @Test
    void tryParseAcceptsSpecTypesAndTrimsWhitespace() {
        assertEquals(Optional.of(VertexElementFormat.VEC3F), VertexElementFormat.tryParse("vec3f"));
        assertEquals(Optional.of(VertexElementFormat.VEC4UB), VertexElementFormat.tryParse(" vec4ub "));
        assertEquals(Optional.of(VertexElementFormat.VEC2S), VertexElementFormat.tryParse("vec2s"));
    }

    @Test
    void tryParseRejectsUnknownTypesExplicitly() {
        assertEquals(Optional.empty(), VertexElementFormat.tryParse(null), "null 不可用");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse(""), "空串不可用");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse("   "), "空白不可用");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse("vec5f"), "未知分量数不可用");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse("vec2i"), "未知分量类型不可用");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse("mat4"), "矩阵类型未收录");
        assertEquals(Optional.empty(), VertexElementFormat.tryParse("float"), "标量类型未收录");
    }

    @Test
    void supportedNamesSnapshot() {
        assertEquals(List.of("vec2f", "vec3f", "vec4f", "vec2s", "vec3b", "vec4ub"),
                VertexElementFormat.supportedNames());
    }
}
