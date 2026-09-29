package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 顶点分量格式 → 字节数
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「顶点格式扩展」属性表（任务点名的权威依据：每项的类型列）；
 *    ② 公开的图形格式事实：分量宽度（f = 32 位浮点、s = 16 位有符号整数、b = 8 位有符号整数、
 *      ub = 8 位无符号整数），即 OpenGL/Vulkan 顶点属性的通用格式事实，不受版权保护。
 *    许可证：本仓库 docs（MIT 项目自有）+ 通用格式事实 → 可并入。
 *    ⚠️ 18-PARALLEL §4 E 线参考列提到 VulkanMod 的 GlUtil.vulkanFormat「格式表思路」：VulkanMod = LGPL-3.0，
 *    与 MIT 不同族 → 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」，
 *    本文件不读、不参考其任何源码；格式表事实来源 = 04-SPEC §4 + 公开格式事实。
 *    → 能否并入本项目（MIT）：本文件为独立实现的枚举，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：04-SPEC §4 属性表的类型列（vec3f / vec4ub / vec2f / vec2s / vec3b，外加 at_tangent 的 vec4f）。
 * 2. 备选：泛解析任意 "vecN<type>" 字符串 —— 否决（未知分量数/未知分量类型必须显式报错（T11），
 *    用白名单枚举把「合法格式」钉死，比泛解析更不容易静默算错 stride）。
 * 3. 我们的差异点：① 只收 04-SPEC §4 与 F2 VertexAttribute 用到的 6 种格式；
 *    ② 字节数 = 分量数 × 分量宽度（紧凑打包，不插入隐式对齐填充，见 VertexLayout 的说明）；
 *    ③ 未知格式返回 Optional.empty()，由 VertexLayout 转成 UNKNOWN_ATTRIBUTE_TYPE 显式诊断。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含文档事实与公开格式事实。
 * 5. 性能基线：❄️ 冷路径（构建顶点格式时查一次表），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 顶点分量格式白名单（04-SPEC §4 命名法：字母后缀表示分量类型，数字表示分量个数）。
 *
 * <p>{@link #byteSize()} = 分量数 × 分量宽度（f = 4 字节、s = 2 字节、b = 1 字节、ub = 1 字节）。
 * 本枚举**不做隐式对齐填充**：stride 与 offset 全部由 {@link VertexLayout}
 * 按「插入顺序累加字节数」推导，理由是 04-SPEC §4 只给出类型列、未给出对齐列，
 * 而 08-TESTING §5 的 stride 自检需要一个唯一确定的数值来源。
 */
public enum VertexElementFormat {

    /** 2 × float32（04-SPEC §4：UV0 / mc_midTexCoord）。 */
    VEC2F("vec2f", 2, 4),

    /** 3 × float32（04-SPEC §4：Position）。 */
    VEC3F("vec3f", 3, 4),

    /** 4 × float32（04-SPEC §4 未列，OF 官方属性表 at_tangent = vec4，F2 VertexAttribute.at_tangent 消费）。 */
    VEC4F("vec4f", 4, 4),

    /** 2 × int16（04-SPEC §4：UV1 / UV2 / mc_Entity）。 */
    VEC2S("vec2s", 2, 2),

    /** 3 × int8（04-SPEC §4：Normal）。 */
    VEC3B("vec3b", 3, 1),

    /** 4 × uint8（04-SPEC §4：Color）。 */
    VEC4UB("vec4ub", 4, 1);

    private final String glslName;
    private final int componentCount;
    private final int componentBytes;

    VertexElementFormat(String glslName, int componentCount, int componentBytes) {
        this.glslName = glslName;
        this.componentCount = componentCount;
        this.componentBytes = componentBytes;
    }

    /** 04-SPEC §4 命名法下的类型名（如 vec3f）。 */
    public String glslName() {
        return this.glslName;
    }

    /** 分量个数（如 vec3f = 3）。 */
    public int componentCount() {
        return this.componentCount;
    }

    /** 单个分量的字节数（f = 4、s = 2、b / ub = 1）。 */
    public int componentBytes() {
        return this.componentBytes;
    }

    /** 该格式占用的字节数（分量数 × 分量宽度）。 */
    public int byteSize() {
        return this.componentCount * this.componentBytes;
    }

    /**
     * 按类型名查格式（首尾空白会被裁剪）。
     *
     * @param glslName 04-SPEC §4 命名法类型名（如 vec2s）；null / 空白 / 未收录 → empty
     * @return 命中的格式；未收录返回 {@link Optional#empty()}（调用方必须转成显式诊断，T11）
     */
    public static Optional<VertexElementFormat> tryParse(String glslName) {
        if (glslName == null) {
            return Optional.empty();
        }
        String trimmed = glslName.trim();
        for (VertexElementFormat format : values()) {
            if (format.glslName.equals(trimmed)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** 全部受支持类型名（按声明顺序），用于诊断消息与单测快照。 */
    public static List<String> supportedNames() {
        List<String> names = new ArrayList<>(values().length);
        for (VertexElementFormat format : values()) {
            names.add(format.glslName);
        }
        return List.copyOf(names);
    }
}
