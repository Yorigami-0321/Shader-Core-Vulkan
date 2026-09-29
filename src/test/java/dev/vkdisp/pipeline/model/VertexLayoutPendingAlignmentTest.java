package dev.vkdisp.pipeline.model;

/**
 * 【参考调研】E 线单测 / P-1d「待实测对齐清单」（18-PARALLEL §10 P-1d）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4 属性表及其 2026-09-29 复核注记（mc_Entity 官方声明为 in vec3，
 *       但"底层元素类型 float32 / int16"文档未给 → 未定项）；② docs/18-PARALLEL.md §10 P-1d（剩余未定项：
 *       mc_Entity 底层元素类型直接决定字节数与 stride，E 线现值 47，须在 P1.2 构建真实 VertexFormat 时
 *       与原版实测对齐后再走 §3.2 定稿，此前任何线不许私改）；③ docs/07-CONSTRAINTS.md X9（待确认项不许
 *       用猜的值填）与 X8 / T9（stride 必须断言）。
 *    许可证：本仓库 docs 与自有代码（本项目 MIT）→ 可直接消费；OptiFine（sp614x/optifine 无 LICENSE = ARR，
 *    按 X20 不并入其文本表达）与参考模组（VulkanMod LGPL-3.0 / Sulkan GPL-3.0 / Beryl ARR）零接触、未读任何
 *    代码（L12）——本文件只消费 04-SPEC §4 复核注记里已登记的事实与本仓库自己的代码。
 *    → 能否并入本项目（MIT）：可以（本文件为独立编写的测试）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：JUnit 5 数值断言 —— 把"当前已知值"逐项钉死（offset / size / stride / 自洽性），
 *    把"未决项"写成显式常量 + 显式断言，并把"实测后要改哪几行"写成机器校验的行号清单。
 * 2. 备选：按 OF 官方"vec3"把 mc_Entity 直接推成 12 字节、stride 改成 55 —— 否决（着色器侧 vec3 不等于
 *    底层元素类型；无实测即改值 = 07 X9 猜值，正是本清单要拦的雷；且 F2 契约冻结，改它要走 18-PARALLEL §3.2）。
 * 3. 我们的差异点：① 断言与"待改行清单"绑定，清单行号本身也机器校验（行号漂移即红，清单不会烂掉）；
 *    ② 未决项常量 = null（= 尚未实测），P1.2 一旦回填该常量，本文件必红 → 强制同步更新全部相关行并走 §3.2；
 *    ③ 不改任何值，只让未决项可见（X9）。
 * 4. 许可证核对：本项目 MIT；JUnit 5 = EPL-2.0（仅 testImplementation 依赖，不进分发 jar）；零第三方代码复制。
 * 5. 性能基线：测试代码不进运行时，无性能影响（18-PARALLEL §7.7）。
 */
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.vkdisp.pack.VertexAttribute;

/**
 * P-1d 待实测对齐清单：把"mc_Entity 底层元素类型未定"这件事变成可见、会红、带改法的断言。
 *
 * <pre>
 * 未决项（07-CONSTRAINTS X9：禁止用猜的值填）
 *   · 已确定（04-SPEC §4 复核注记）：着色器侧声明 = in vec3 mc_Entity（xy = blockId / renderType）
 *   · 未确定（官方文档未给）      ：底层元素类型（float32 / int16 / 其它）
 *     → 它直接决定 mc_Entity 的字节数 → 直接决定本表的 stride（E 线现值 47）
 *   · 触发时机：P1.2 构建真实 VertexFormat 时与原版实测对齐，之后走 18-PARALLEL §3.2 定稿
 *
 * 当前值（本类逐项钉死；全部是"沿用 04-SPEC §4 旧值"，不是实测结论）
 *   · mc_Entity    = vec2s = 2 x int16 = 4 字节，offset = 35
 *   · mc_midTexCoord= vec2f，offset = 39（唯一排在 mc_Entity 之后的属性）
 *   · 全表 stride   = 47（12+4+8+4+4+3+4+8）
 *
 * P1.2 实测后需要改的行（按"改哪几行"列全；带 ★ 的行由 alignmentChecklistLineNumbersAreStillAccurate 机器校验）
 *   ★1. src/main/java/dev/vkdisp/pipeline/model/VertexElementFormat.java:46-47
 *       VEC2S 的 javadoc 与字节数来源（若 mc_Entity 不再是 2 x int16：要么新增/改映射，要么给 mc_Entity
 *       单独一个格式；UV1 / UV2 仍是 2 x int16，不得被顺手改掉）
 *   ★2. src/main/java/dev/vkdisp/pack/VertexAttribute.java:59  mc_Entity("vec2s", true)
 *       F2 冻结契约 —— 本线不许改：走 18-PARALLEL §3.2，由 env-1 统一改 + ContractVersion.VERSION +1
 *   ★3. src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java:49 / :52 / :55 / :73 / :172 / :206 / :209
 *       SPEC_OFFSETS / SPEC_SIZES / SPEC_STRIDE / "stride 必须等于"断言 / 规范文本快照 / 两处篡改用例
 *   ★4. 本文件（VertexLayoutPendingAlignmentTest）：CURRENT_OFFSETS / CURRENT_SIZES / CURRENT_STRIDE /
 *       MC_ENTITY_ELEMENT_TYPE_AFTER_P12 / CHECKLIST 五处必须同轮更新
 *   5. src/main/java/dev/vkdisp/pipeline/model/VertexLayout.java —— 无需改：只有规则性累加、无数值常量
 *       （layoutSourceHasNoHardCodedStride 机器断言这一条）
 *   6. docs/04-SPEC.md §4 属性表 + 复核注记 —— 归 env-1（18-PARALLEL §7.2：本线不碰 docs/）
 *   7. docs/18-PARALLEL.md §10 的 P-1d 状态行 —— 归 env-1
 *
 * 影响面（实测结论落点的算术，只是事实推演，不是当前值）
 *   · mc_Entity 是第 7 个属性，前 6 行 offset / size 不受它影响
 *   · 它只牵动：mc_midTexCoord 的 offset（39 → 35 + 新字节数）与全表 stride（47 → 43 + 新字节数）
 *     例：若实测为 3 x float32（12 字节）→ stride = 43 + 12 = 55（假设情形，禁止在实测前引用）
 * </pre>
 */
class VertexLayoutPendingAlignmentTest {

    /** 04-SPEC §4 属性表顺序（权威顺序；与 VertexLayoutTest 独立复写，避免跨文件常量耦合）。 */
    private static final List<VertexAttribute> SPEC_TABLE_ORDER = List.of(
            VertexAttribute.Position,
            VertexAttribute.Color,
            VertexAttribute.UV0,
            VertexAttribute.UV1,
            VertexAttribute.UV2,
            VertexAttribute.Normal,
            VertexAttribute.mc_Entity,
            VertexAttribute.mc_midTexCoord);

    /** 当前逐项 offset（现值，未实测前不许改）。 */
    private static final int[] CURRENT_OFFSETS = {0, 12, 16, 24, 28, 32, 35, 39};

    /** 当前逐项字节数（现值，未实测前不许改）。 */
    private static final int[] CURRENT_SIZES = {12, 4, 8, 4, 4, 3, 4, 8};

    /** 当前 stride = 47（P-1d 未决：mc_Entity 底层元素类型未实测，此值可能变）。 */
    private static final int CURRENT_STRIDE = 47;

    /**
     * P1.2 实测出来的 mc_Entity 底层元素类型（如 "float32 x 3"）。
     *
     * <p>{@code null} = 尚未实测。按 07-CONSTRAINTS X9，实测前禁止填任何猜测值 ——
     * 一旦有人回填本常量，{@link #mcEntityElementTypeIsStillUnmeasured()} 必红，
     * 强制同轮更新 CURRENT_* / CHECKLIST 并走 18-PARALLEL §3.2，不许只改一半。
     */
    private static final String MC_ENTITY_ELEMENT_TYPE_AFTER_P12 = null;

    /** 待改行清单的一条：文件 + 行号 + 该行当前内容（行号漂移即红，清单不会烂掉）。 */
    private record LineRef(String file, int line, String content) {
    }

    /** ★ 机器校验的待改行（P1.2 落地时按此逐行改；docs/ 归 env-1，故不进本清单）。 */
    private static final List<LineRef> CHECKLIST = List.of(
            new LineRef("src/main/java/dev/vkdisp/pipeline/model/VertexElementFormat.java", 46,
                    "/** 2 × int16（04-SPEC §4：UV1 / UV2 / mc_Entity）。 */"),
            new LineRef("src/main/java/dev/vkdisp/pipeline/model/VertexElementFormat.java", 47,
                    "VEC2S(\"vec2s\", 2, 2),"),
            new LineRef("src/main/java/dev/vkdisp/pack/VertexAttribute.java", 59,
                    "mc_Entity(\"vec2s\", true),"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 49,
                    "private static final int[] SPEC_OFFSETS = {0, 12, 16, 24, 28, 32, 35, 39};"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 52,
                    "private static final int[] SPEC_SIZES = {12, 4, 8, 4, 4, 3, 4, 8};"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 55,
                    "private static final int SPEC_STRIDE = 47;"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 73,
                    "assertEquals(47, layout.stride(), \"stride 必须等于各项 size 之和（12+4+8+4+4+3+4+8）\");"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 172,
                    "+ \"stride=47\\n\""),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 206,
                    "String tampered = VertexLayout.of(SPEC_TABLE_ORDER).canonicalText()"
                            + ".replace(\"stride=47\", \"stride=48\");"),
            new LineRef("src/test/java/dev/vkdisp/pipeline/model/VertexLayoutTest.java", 209,
                    "assertEquals(47, parsed.stride(), \"以重新推导值为准\");"));

    // ---------------------------------------------------------- 当前值钉死

    @Test
    void currentOffsetTableIsPinnedPendingP12Measurement() {
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);

        assertFalse(layout.hasErrors(), "合法属性集不应有 ERROR：" + layout.errors());
        assertEquals(SPEC_TABLE_ORDER.size(), layout.attributeCount());
        for (int i = 0; i < SPEC_TABLE_ORDER.size(); i++) {
            VertexAttribute attribute = SPEC_TABLE_ORDER.get(i);
            VertexLayout.AttributeOffset row = layout.findAttribute(attribute.name())
                    .orElseThrow(() -> new AssertionError("missing attribute " + attribute.name()));
            assertEquals(CURRENT_OFFSETS[i], row.offset(), attribute.name() + " offset（现值）");
            assertEquals(CURRENT_SIZES[i], row.byteSize(), attribute.name() + " byteSize（现值）");
            assertEquals(attribute.glslType(), row.format().glslName(), attribute.name() + " 类型（现值）");
        }
        assertEquals(CURRENT_STRIDE, layout.stride(), PENDING_MESSAGE);
        assertEquals(47, layout.stride(), "钉死现值 47（= 12+4+8+4+4+3+4+8）；" + PENDING_MESSAGE);
        assertTrue(layout.isConsistent(), "offset 必须是前序 size 累加、stride 必须是总和");
    }

    private static final String PENDING_MESSAGE =
            "PENDING P1.2：mc_Entity 底层元素类型未实测（X9 禁止猜值），实测后按【待实测对齐清单】改";

    @Test
    void mcEntityRowIsTheOnlyRowTheOpenItemCanMove() {
        VertexLayout layout = VertexLayout.of(SPEC_TABLE_ORDER);

        VertexLayout.AttributeOffset mcEntity = layout.findAttribute("mc_Entity").orElseThrow();
        assertEquals(35, mcEntity.offset(), "mc_Entity 现 offset（= 前 6 项 size 之和）");
        assertEquals(4, mcEntity.byteSize(), "mc_Entity 现字节数 = vec2s = 2 x int16（沿用旧值，非实测）");
        assertEquals(VertexElementFormat.VEC2S, mcEntity.format());

        VertexLayout.AttributeOffset last = layout.findAttribute("mc_midTexCoord").orElseThrow();
        assertEquals(39, last.offset(), "唯一排在 mc_Entity 之后的属性：它的 offset 与 stride 会被未决项牵动");
        assertEquals(8, last.byteSize());
    }

    @Test
    void mcEntityCandidateSizesAreFactsNotCurrentValues() {
        assertEquals(4, VertexElementFormat.VEC2S.byteSize(), "现映射 2 x int16 = 4 字节");
        assertEquals(12, VertexElementFormat.VEC3F.byteSize(),
                "若实测为 3 x float32 的字节数事实：stride 会变成 47 - 4 + 12 = 55（假设情形，非当前值）");
        assertEquals(47, VertexLayout.of(SPEC_TABLE_ORDER).stride(),
                "无论候选怎么算，当前值仍钉在 47 —— 实测落地前不许改（X9）");
    }

    @Test
    void mcEntityElementTypeIsStillUnmeasured() {
        assertNull(MC_ENTITY_ELEMENT_TYPE_AFTER_P12, MC_ENTITY_SHOULD_STAY_NULL_HINT);
    }

    private static final String MC_ENTITY_SHOULD_STAY_NULL_HINT =
            "P-1d 仍未实测（X9：保持 null，禁止填猜测值）。"
                    + "如果这是 P1.2 实测后回填的值：本断言会红，请按类顶【待实测对齐清单】同轮更新 "
                    + "CURRENT_OFFSETS / CURRENT_SIZES / CURRENT_STRIDE / CHECKLIST，"
                    + "并走 18-PARALLEL §3.2 定稿（F2 契约由 env-1 改），不许只改一半。";

    // ---------------------------------------------------------- 清单行号机器校验

    @Test
    void alignmentChecklistLineNumbersAreStillAccurate() {
        for (LineRef ref : CHECKLIST) {
            List<String> lines = readSourceLines(ref.file());
            assertTrue(ref.line() - 1 < lines.size(),
                    "P-1d 清单引用越界：" + ref.file() + ":" + ref.line() + "（文件只有 " + lines.size() + " 行）");
            String actual = lines.get(ref.line() - 1).trim();
            assertEquals(ref.content(), actual,
                    "P-1d 对齐清单行号漂移：" + ref.file() + ":" + ref.line()
                            + "。若是 P1.2 实测或 §3.2 契约变更落地，请同步更新本清单与 CURRENT_* 常量；"
                            + "若是无关改动，请把 LineRef 的行号/内容改到当前位置。");
        }
    }

    @Test
    void vertexLayoutSourceHasNoHardCodedStride() {
        String source = String.join("\n",
                readSourceLines("src/main/java/dev/vkdisp/pipeline/model/VertexLayout.java"));
        assertFalse(source.contains("47"),
                "VertexLayout 应只有规则性累加、不得硬编码 stride —— 若真出现了 47，说明有人把未决值写死进主代码了");
    }

    // ---------------------------------------------------------- 源码读取（只读）

    private static List<String> readSourceLines(String relativePath) {
        Path file = resolveFromProjectRoot(relativePath);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("P-1d 对齐清单读不到源文件：" + file.toAbsolutePath(), failure);
        }
    }

    private static Path resolveFromProjectRoot(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 5 && cursor != null; up++) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("P-1d 对齐清单找不到源文件：" + relativePath
                + "（工作目录 " + System.getProperty("user.dir") + "）");
    }
}
