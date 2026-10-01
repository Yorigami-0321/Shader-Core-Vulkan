package dev.vkdisp.glsl.translate;
/**
 * 【参考调研】P4.1.3 VkDispBuiltins 块布局解析 / std140 规则 + 本仓库注入块形态
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.2（块语义 + P4.1.3 上传注记，仓库内文档事实）；
 *    ② OpenGL SPIR-V/std140 对齐规则的公开事实性描述（标量 4、vec2 8、vec3/vec4 16、
 *    矩阵按列向量步进 16、数组元素步进 roundUp(元素大小,16)、块尾 roundUp 到最大对齐 16）；
 *    ③ 本类自己的发射方 UniformInjector（BLOCK_OPEN/BLOCK_CLOSE/成员行形态）。
 *    外部候选 IrisShaders/glsl-transformer（GPL-3.0+例外）→ 禁止处理，零代码并入
 *    （07-CONSTRAINTS L12 / X19-X21）。
 *    许可证：本文件为独立编写的纯 Java 实现，不含任何外部项目代码。
 *    → 能否并入本项目（MIT）：可以 —— 只采纳不受版权保护的事实性规则与本仓库自有格式
 *    → 例外条款：无；本文件不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：解析**转译终稿文本**（F3 冻结契约：TranslateResult 不外传 UniformInjector
 *    内部的 Result，终稿 = 驱动实际编译的真源）里的 `layout(std140) uniform VkDispBuiltins {`
 *    块，按发射序（收编声明在前 + 目录缺失在后）逐成员计算 std140 偏移，产出
 *    成员名 → (offset, size) 索引与块字节数。composite 与 deferred 收编集不同 → 各解析一次。
 * 2. 备选：把布局经 TranslateResult 外传（否决：破坏 F3 冻结契约）；假设目录序块
 *    （否决：收编行在前，B SL 实测 `float far, near;` 等前置于目录成员 —— X9）。
 * 3. 我们的差异点：只认与 {@link UniformInjector#BLOCK_OPEN} 逐字节相同的开行（本类是
 *    该块的唯一合法解析器，不猜包作者的其它 std140 块）；解析失败（未知类型 / 非字面量
 *    数组长度）→ 空布局 + failure 原文（调用方 WARN，回退零填充 —— T11 显式不静默）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入（07-CONSTRAINTS §〇 P1 / L5-L8）。
 * 5. 性能基线：❄️ 冷路径 —— 每次 generateSources 解析一次（~26 成员），帧路径只读索引；
 *    不做任何预优化（18-PARALLEL §7.7、T14 达标即停）。
 */
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VkDispBuiltins 内建块的 std140 布局（04-SPEC §3.2 上传注记「布局与缓冲」）。
 *
 * <p><b>为什么从文本重解析</b>：块成员顺序 = 收编声明（源序、`uniform` 关键字已剥）
 * 在前 + {@link UniformCatalog} 缺失项在后 —— 顺序取决于所选包收编了什么，**不是**目录序；
 * 而 F3 冻结契约下 {@code UniformInjector.Result} 不出转译入口，终稿文本是唯一真源。
 *
 * <p><b>用法</b>：冷路径 {@link #parse(String)}（VkDispVirtualPack 对 composite/deferred
 * 各一次，volatile 静态发布）→ 帧路径 {@link #find(String)} / {@link #byteSize()}
 * 只读。空布局（无块 / 解析失败）= 调用方回退零填充。
 */
public final class BuiltinsBlockLayout {

    /**
     * 块成员声明行（与 {@link UniformInjector} 的 BLOCK_MEMBER 同形态：可选精度限定 +
     * 类型 + 单名或逗号多名声明子句 + 分号）。两处独立声明：跨类私有成员不可见，
     * 形态一致性由 {@link BuiltinsBlockLayoutTest} 以注入器真实输出对账。
     */
    private static final Pattern MEMBER = Pattern.compile(
            "(?:(?:lowp|mediump|highp)\\s+)?([A-Za-z_]\\w*)\\s+"
                    + "([A-Za-z_]\\w*(?:\\s*\\[[^]]*\\])?"
                    + "(?:\\s*,\\s*[A-Za-z_]\\w*(?:\\s*\\[[^]]*\\])?)*)\\s*;");

    /** 单个声明名（数组剥掉维度 —— 本项目填充集无数组值，数组成员只参与偏移推进）。 */
    private static final Pattern DECLARATOR = Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[([^]]*)])?");

    /** 矩阵类型（无转置维度写法 matN 与 matCxR 写法都认）。 */
    private static final Pattern MATRIX = Pattern.compile("mat([234])(?:x([234]))?");

    /**
     * 块成员（声明序）。
     *
     * @param name  成员名（块内全局作用域，与包源码引用名一致）
     * @param type  GLSL 类型（不含数组维度）
     * @param offset std140 偏移（字节）
     * @param size  占用字节（数组成员 = 步进 × 个数）
     */
    public record Member(String name, String type, int offset, int size) {}

    /** 零成员 / 未找到块 / 解析失败的共享空实例（byteSize=0）。 */
    private static final BuiltinsBlockLayout EMPTY =
            new BuiltinsBlockLayout(List.of(), Map.of(), 0, null);

    /** 声明序成员表。 */
    private final List<Member> members;

    /** 成员名索引（帧路径 O(1) 查偏移）。 */
    private final Map<String, Member> byName;

    /** 块总字节 = roundUp(末偏移+末尺寸, 16)；0 成员 = 0。 */
    private final int byteSize;

    /** 解析失败原文（块存在但无法安全算偏移）；null = 正常（含"无块"）。 */
    private final String failure;

    private BuiltinsBlockLayout(List<Member> members, Map<String, Member> byName,
            int byteSize, String failure) {
        this.members = members;
        this.byName = byName;
        this.byteSize = byteSize;
        this.failure = failure;
    }

    /** 空布局（无块或失败前的中间态）；{@link #failure()} 恒 null。 */
    public static BuiltinsBlockLayout empty() {
        return EMPTY;
    }

    /** 声明序成员（不可变）；空布局 = 空表。 */
    public List<Member> members() {
        return members;
    }

    /** 按成员名查（无此成员 = {@code null}）。 */
    public Member find(String name) {
        return byName.get(name);
    }

    /** 块总字节（环形缓冲尺寸 = max(1024, 本值)）。 */
    public int byteSize() {
        return byteSize;
    }

    /** 是否无成员可写（空布局 → 调用方回退零填充）。 */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** 解析失败原文（未知类型 / 非字面量数组维 / 结构异常）；null = 成功或无块。 */
    public String failure() {
        return failure;
    }

    /**
     * 从转译终稿文本解析 VkDispBuiltins 块。
     *
     * <p>永不抛：任何意外 → 空布局 + failure 原文（T11）。无块（passthrough 兜底源）不是
     * 失败 —— 正常返回空布局、failure=null。
     *
     * @param source 转译终稿（null 按空串）
     */
    public static BuiltinsBlockLayout parse(String source) {
        if (source == null || source.isEmpty()) {
            return EMPTY;
        }
        try {
            String[] lines = source.split("\n", -1);
            int openIndex = -1;
            for (int i = 0; i < lines.length; i++) {
                if (stripEol(lines[i]).equals(UniformInjector.BLOCK_OPEN)) {
                    openIndex = i;
                    break;
                }
            }
            if (openIndex < 0) {
                return EMPTY;
            }
            List<Member> parsed = new ArrayList<>();
            Map<String, Member> index = new LinkedHashMap<>();
            for (int i = openIndex + 1; i < lines.length; i++) {
                String line = stripEol(lines[i]);
                // 块闭行（本方发射为整行 "};"；防御：行内 "}" 之前的成员同循环处理）。
                int closeBrace = line.indexOf('}');
                if (closeBrace >= 0) {
                    String before = stripTailComment(line.substring(0, closeBrace)).strip();
                    if (!before.isEmpty()) {
                        String failure = consumeMember(before, parsed, index);
                        if (failure != null) {
                            return failed(failure);
                        }
                    }
                    break;
                }
                String decl = stripTailComment(line).strip();
                if (decl.isEmpty() || decl.startsWith("#")) {
                    continue;
                }
                String failure = consumeMember(decl, parsed, index);
                if (failure != null) {
                    return failed(failure);
                }
            }
            int cursor = parsed.isEmpty() ? 0
                    : parsed.getLast().offset() + parsed.getLast().size();
            int bytes = roundUp(cursor, 16);
            return new BuiltinsBlockLayout(
                    Collections.unmodifiableList(parsed),
                    Collections.unmodifiableMap(index),
                    bytes, null);
        } catch (RuntimeException ex) {
            return failed("parse threw " + ex);
        }
    }

    /** 失败态：空成员 + 原文（调用方 WARN 后回退零填充）。 */
    private static BuiltinsBlockLayout failed(String reason) {
        return new BuiltinsBlockLayout(List.of(), Map.of(), 0, reason);
    }

    /**
     * 消费一行成员声明（可能逗号多名）。返回 null = 成功；否则失败原文。
     * 成功时成员按声明序追加进 {@code parsed}，偏移从当前末成员推进
     * （重名 = 失败：驱动会报 duplicate member）。
     */
    private static String consumeMember(String decl, List<Member> parsed,
            Map<String, Member> index) {
        Matcher member = MEMBER.matcher(decl);
        if (!member.matches()) {
            return "unparseable member line: '" + decl + "'";
        }
        String type = member.group(1);
        TypeSize base = typeSize(type);
        if (base == null) {
            return "unsupported member type '" + type + "' in declaration: '" + decl + "'";
        }
        int cursor = parsed.isEmpty() ? 0
                : parsed.getLast().offset() + parsed.getLast().size();
        String[] declarators = member.group(2).split(",");
        for (String raw : declarators) {
            Matcher declMatcher = DECLARATOR.matcher(raw.strip());
            if (!declMatcher.matches()) {
                return "unparseable declarator '" + raw.strip() + "' in declaration: '" + decl + "'";
            }
            String name = declMatcher.group(1);
            String arrayLiteral = declMatcher.group(2);
            int align = base.align;
            int size = base.size;
            if (arrayLiteral != null) {
                // std140 数组：对齐 16、步进 roundUp(元素大小,16)、总数 = 步进 × 个数。
                String literal = arrayLiteral.strip();
                if (!literal.matches("\\d+")) {
                    return "non-literal array size '" + arrayLiteral + "' on member '" + name + "'";
                }
                int count = Integer.parseInt(literal);
                if (count <= 0) {
                    return "non-positive array size " + count + " on member '" + name + "'";
                }
                int stride = roundUp(base.size, 16);
                align = 16;
                size = stride * count;
            }
            if (index.containsKey(name)) {
                return "duplicate member name '" + name + "'";
            }
            int offset = roundUp(cursor, align);
            Member m = new Member(name, type, offset, size);
            parsed.add(m);
            index.put(name, m);
            cursor = offset + size;
        }
        return null;
    }

    /** 去行尾 CR（split("\n") 后的 Windows 残留）。 */
    private static String stripEol(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * 去行尾注释（`//` 与 `/*` 起）。块内成员无字符串字面量，首个命中即切安全 ——
     * 收编行可能带原作者行尾注释（`float far; // depth`）。
     */
    private static String stripTailComment(String line) {
        int slash = line.indexOf("//");
        int block = line.indexOf("/*");
        int cut = -1;
        if (slash >= 0 && block >= 0) {
            cut = Math.min(slash, block);
        } else if (slash >= 0) {
            cut = slash;
        } else if (block >= 0) {
            cut = block;
        }
        return cut >= 0 ? line.substring(0, cut) : line;
    }

    /** roundUp 到 align 的正整数倍（align 已由 {@link #typeSize} 保证为 4/8/16）。 */
    private static int roundUp(int value, int align) {
        return (value + align - 1) / align * align;
    }

    /** 类型的 std140 基础对齐与大小；未知类型 = null。 */
    private static TypeSize typeSize(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new TypeSize(4, 4);
            case "vec2", "ivec2", "uvec2", "bvec2" -> new TypeSize(8, 8);
            case "vec3", "ivec3", "uvec3", "bvec3" -> new TypeSize(16, 12);
            case "vec4", "ivec4", "uvec4", "bvec4" -> new TypeSize(16, 16);
            default -> matrixSize(type);
        };
    }

    /**
     * 矩阵类型（matN = matNxN；matCxR 列×行）。std140：矩阵按列向量存、列步进 =
     * roundUp(列对齐,16) = 16（行列 1..4 全覆盖）→ 对齐 16、大小 = 列数 × 16。
     */
    private static TypeSize matrixSize(String type) {
        Matcher matcher = MATRIX.matcher(type);
        if (!matcher.matches()) {
            return null;
        }
        int cols = Integer.parseInt(matcher.group(1));
        int rows = matcher.group(2) == null ? cols : Integer.parseInt(matcher.group(2));
        if (rows < 1 || rows > 4) {
            return null;
        }
        int colStride = roundUp(rows * 4, 16);
        return new TypeSize(16, cols * colStride);
    }

    /** std140 对齐/大小对。 */
    private record TypeSize(int align, int size) {}
}
