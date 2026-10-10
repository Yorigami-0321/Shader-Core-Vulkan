package dev.vkdisp.render;
/**
 * 【参考调研】P4.1.3 内建 uniform 写入单测（write 纯函数）/ std140 绝对偏移落字节
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = 本仓库 docs/04-SPEC.md §3.2 上传注记（布局与缓冲）+ BuiltinsBlockLayout
 *    的 std140 偏移规则（同一规则的消费侧）。期望字节全部手算写出。
 *    许可证：本文件为独立编写的测试代码（MIT）；不含任何 GPL / LGPL / ARR 代码。
 * 1. 官方/主实现：合成块 → {@link BuiltinsBlockLayout#parse} → {@link OfUniformManager#write}
 *    → 对 direct ByteBuffer 绝对读断言。gather() 不单测（依赖 vanilla 运行态 —— 由
 *    runClient 证据行覆盖，X9 取证走真实跑）。
 * 2. 备选：无。
 * 3. 我们的差异点：偏移期望 = 手算常数（不复用被测实现的任何计算）。
 * 4. 许可证核对结论：本项目 MIT。
 * 5. 性能基线：❄️ 冷路径单测（T14）。
 */
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.glsl.translate.BuiltinsBlockLayout;
import dev.vkdisp.glsl.translate.UniformInjector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OfUniformManagerTest {

    /** 合成注入器形态块源。 */
    private static BuiltinsBlockLayout layout(String... memberLines) {
        StringBuilder source = new StringBuilder();
        source.append(UniformInjector.BLOCK_HEADER).append('\n');
        source.append(UniformInjector.BLOCK_OPEN).append('\n');
        for (String line : memberLines) {
            source.append(line).append('\n');
        }
        source.append(UniformInjector.BLOCK_CLOSE).append('\n');
        BuiltinsBlockLayout parsed = BuiltinsBlockLayout.parse(source.toString());
        assertTrue(parsed.failure() == null, parsed.failure());
        return parsed;
    }

    /** 直接缓冲（write 内部会设 native order，读侧同序）。 */
    private static ByteBuffer buffer(int bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        return buffer;
    }

    @Test
    void writesEverySupportedTypeAtHandComputedOffsets() {
        // 手算 std140：float a@0 → vec3 b 对齐16@16(12) → int c@28 → ivec2 d 对齐8@32(8)
        // → mat4 e 对齐16@48(64) → 块尾 112。
        BuiltinsBlockLayout layout = layout(
                "float a;", "vec3 b;", "int c;", "ivec2 d;", "mat4 e;");
        assertEquals(0, layout.find("a").offset());
        assertEquals(16, layout.find("b").offset());
        assertEquals(28, layout.find("c").offset());
        assertEquals(32, layout.find("d").offset());
        assertEquals(48, layout.find("e").offset());
        assertEquals(112, layout.byteSize());

        Matrix4f matrix = new Matrix4f().translate(1.0F, 2.0F, 3.0F);
        Map<String, Object> values = Map.of(
                "a", 1.5F,
                "b", new Vector3f(1.0F, 2.0F, 3.0F),
                "c", 7,
                "d", new int[] {11, 22},
                "e", matrix);

        ByteBuffer dst = buffer(256);
        OfUniformManager.WriteStats stats = OfUniformManager.write(layout, values, dst);

        assertEquals(5, stats.written(), stats.toString());
        assertEquals(0, stats.missing());
        assertEquals(0, stats.mismatched());
        assertEquals(0, stats.overflow());
        assertEquals(1.5F, dst.getFloat(0));
        assertEquals(1.0F, dst.getFloat(16));
        assertEquals(2.0F, dst.getFloat(20));
        assertEquals(3.0F, dst.getFloat(24));
        assertEquals(7, dst.getInt(28));
        assertEquals(11, dst.getInt(32));
        assertEquals(22, dst.getInt(36));
        // mat4 列主序（JOML get(float[]) = GLSL 内存序）：平移在最后一列 → 元素 12..14。
        assertEquals(1.0F, dst.getFloat(48 + 0 * 4), "m00");
        assertEquals(1.0F, dst.getFloat(48 + 12 * 4), "m03 = tx");
        assertEquals(2.0F, dst.getFloat(48 + 13 * 4), "m13 = ty");
        assertEquals(3.0F, dst.getFloat(48 + 14 * 4), "m23 = tz");
        // 未写区域保持 0（绝对写只碰声明字节）。
        assertEquals(0.0F, dst.getFloat(4));
    }

    @Test
    void missingValuesAreCountedNotGuessed() {
        BuiltinsBlockLayout layout = layout("float a;", "float b;");
        ByteBuffer dst = buffer(32);
        OfUniformManager.WriteStats stats =
                OfUniformManager.write(layout, Map.of("a", 1.0F), dst);
        assertEquals(1, stats.written());
        assertEquals(1, stats.missing());
        assertEquals(List.of("b"), stats.missingNames(), "未填充成员名上报给一次性 INFO");
        assertEquals(1.0F, dst.getFloat(0));
        assertEquals(0.0F, dst.getFloat(4), "缺失成员保持零值，绝不猜");
    }

    @Test
    void typeMismatchIsSkippedAndCounted() {
        // 包把同名成员声明成异型（注入期 WARN 过）→ 跳过写入、计 mismatched。
        BuiltinsBlockLayout layout = layout("float a;");
        ByteBuffer dst = buffer(16);
        OfUniformManager.WriteStats stats =
                OfUniformManager.write(layout, Map.of("a", "not a number"), dst);
        assertEquals(0, stats.written());
        assertEquals(1, stats.mismatched());
        assertEquals(0.0F, dst.getFloat(0), "不匹配的值不得落字节");
    }

    @Test
    void numberValuesCoerceAcrossScalarTypes() {
        // pack 声明 float worldTime 而值是 Integer → Number 宽化照写（吸收录编异型）。
        BuiltinsBlockLayout layout = layout("float a;", "int b;");
        ByteBuffer dst = buffer(16);
        OfUniformManager.WriteStats stats = OfUniformManager.write(layout,
                Map.of("a", 4, "b", 9.0F), dst);
        assertEquals(2, stats.written(), stats.toString());
        assertEquals(4.0F, dst.getFloat(0));
        assertEquals(9, dst.getInt(4));
    }

    @Test
    void overflowMembersAreSkippedWithoutThrowing() {
        // 环尺寸 < 块字节的防御态（换包后未及重建）→ 计 overflow，绝不越界写。
        BuiltinsBlockLayout layout = layout("mat4 big;");
        ByteBuffer dst = buffer(8);
        OfUniformManager.WriteStats stats = OfUniformManager.write(layout,
                Map.of("big", new Matrix4f()), dst);
        assertEquals(0, stats.written());
        assertEquals(1, stats.overflow());
        assertEquals(0, dst.getInt(0), "越界成员不写");
    }

    @Test
    void emptyAndNullInputsAreNoops() {
        ByteBuffer dst = buffer(16);
        OfUniformManager.WriteStats empty =
                OfUniformManager.write(BuiltinsBlockLayout.empty(), Map.of("x", 1), dst);
        assertEquals(0, empty.written());
        assertEquals(0, empty.missing());

        OfUniformManager.WriteStats nullLayout = OfUniformManager.write(null, null, dst);
        assertEquals(0, nullLayout.written());

        BuiltinsBlockLayout layout = layout("float a;");
        OfUniformManager.WriteStats nullValues = OfUniformManager.write(layout, null, dst);
        assertEquals(1, nullValues.missing(), "null 值表 = 全部缺失，不抛");
        assertEquals(0.0F, dst.getFloat(0));
    }

    @Test
    void untranslatedPassthroughLayoutWritesNothing() {
        // 兜底源无块 → 空布局 → 零统计（FrameApi 侧保持绑定初始零值缓冲）。
        BuiltinsBlockLayout layout =
                BuiltinsBlockLayout.parse("void main() { gl_FragColor = vec4(1.0); }");
        ByteBuffer dst = buffer(64);
        OfUniformManager.WriteStats stats =
                OfUniformManager.write(layout, Map.of("far", 1000.0F), dst);
        assertEquals(0, stats.written());
        assertEquals(0, stats.missing());
        byte[] head = new byte[16];
        for (int i = 0; i < head.length; i++) {
            head[i] = dst.get(i);
        }
        assertArrayEquals(new byte[16], head, "缓冲前 16 字节全零");
    }

    @Test
    @DisplayName("🔴 cloudHeight 必须走 attributeProbe，不得回落到 LevelRenderState 字段（GAP-033 真机定案）")
    void cloudHeightMustComeFromAttributeProbe() throws java.io.IOException {
        // 事故形态（真机 [GAP-033] 自报行量到）：`levelState.cloudHeight` 在我方取值时机是
        //   **0.0** —— 它要等 LevelExtractor:223 才写进 render state，而 gather() 在提取之前。
        //   后果：包里 atmospherics/clouds.glsl:329 与 lighting/shadows.glsl:308 都拿它当
        //   **云层底高** ⇒ 云层被压到 y=0、相机在层上方 ⇒ 真机「长条云 / 放射指状条带」，
        //   且正上方完全没有云（两个症状同源，修后两格一起消失）。
        // 🔖 这条与 probeAngle 的注释是同一条教训的第二次落地（「SkyRenderState 字段在提取前
        //   为默认值」p413）⇒ 守卫钉的是**取法**，不是数值，因为数值对不对由真机自报行判。
        String source = Files.readString(Path.of(
                "src/main/java/dev/vkdisp/render/OfUniformManager.java"));
        assertTrue(source.contains("EnvironmentAttributes.CLOUD_HEIGHT"),
                "cloudHeight 必须从 EnvironmentAttributes.CLOUD_HEIGHT 取（原版默认 192.33，"
                        + "见 data/worldgen/DimensionTypes.java:38）");
        Assumptions.assumeTrue(source.contains("values.put(\"cloudHeight\""),
                "找不到 cloudHeight 的供值点，本守卫的前提变了，请重判而不是让它空过");
        assertTrue(!source.contains("values.put(\"cloudHeight\", inWorld ? levelState.cloudHeight"),
                "cloudHeight 不得再读 levelState.cloudHeight —— 那个字段在 LevelExtractor "
                        + "跑之前恒为 0，正是 GAP-033 长条云的根因");
        // 取法必须与同文件里已验证可用的那条 probe 路径同形状（不另造一套读数机制）
        assertTrue(source.contains("probeFloat(probe, EnvironmentAttributes.CLOUD_HEIGHT"),
                "cloudHeight 必须走 probeFloat(probe, …) —— 与 probeAngle 同一个 probe 来源");
    }
}
