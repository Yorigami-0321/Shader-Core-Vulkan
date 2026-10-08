package dev.vkdisp.pack.uniform;

/**
 * 【参考调研】GAP-021 单条包自写 uniform 的定义（{@code shaders.properties} 一行的产物）
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = shaders.properties 官方参考「Custom Uniforms」页的**格式事实**：
 *    {@code uniform.<float|int|bool|vec2|vec3|vec4>.<名>=<表达式>} 与
 *    {@code variable.<同名空间>.<名>=<表达式>}；两者表达式的写法完全一致，
 *    区别只有一句：「uniform 会算好并上传 GPU，variable 只是中间量、不上传」。
 *    → 能否并入本项目（MIT）：可以（本文件是独立编写的记录类型）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无外部可读实现（Iris 源码禁读，OptiFine 闭源）⇒ 按文档格式自建。
 * 2. 备选：把整行原样塞进 {@code ShaderPack#properties()} 就完事（现状就是这么处理的，
 *    结果是**无人求值** ⇒ GAP-021）。本类的作用是把「一行文本」变成「可依赖图排序的节点」。
 * 3. 我们的差异点：
 *    ① {@link Kind#VARIABLE} 不进上传映射 —— 文档明说 variable 不上传；
 *    ② {@link Type#VEC2}/{@link Type#VEC3}/{@link Type#VEC4} **照文档识别、但不求值**
 *       （BSL v10.1.8 的 28+13 行全是 {@code float}，实测核实；向量输出要再造一套
 *        vec 上传通路，超出本条判据 ⇒ 由 {@link PackUniformSet} 点名跳过，不静默）；
 *    ③ 名字保留包给的原文（大小写敏感）：OF 的 uniform 名就是 GLSL 标识符，
 *       改成小写会和我们自己的收编名对不上。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 冷路径构造、每帧只读。
 */
record PackUniformDefinition(
        String name,
        Kind kind,
        Type type,
        UniformExpr expression,
        String source) {

    /** 上传语义二态（文档只给了这两种角色）。 */
    enum Kind {
        /** {@code uniform.*} —— 求值后写进 {@code gather()} 的结果（可覆盖我方同名内建）。 */
        UNIFORM,
        /** {@code variable.*} —— 只作中间量，不上传。 */
        VARIABLE;

        /** 指令前缀（properties 的键首段），用于错误信息回显。 */
        String prefix() {
            return this == UNIFORM ? "uniform" : "variable";
        }
    }

    /**
     * 声明类型（文档的五种 + {@link #UNKNOWN}）。
     *
     * <p>{@link #UNKNOWN} 不是「猜出来的类型」，而是**拒绝**：键写成
     * {@code uniform.string.foo} 这类文档没有的类型时，求值必须不发生、且要能点名。
     */
    enum Type {
        FLOAT,
        INT,
        BOOL,
        VEC2,
        VEC3,
        VEC4,
        UNKNOWN;

        /** 是否属于本项目当前会求值的标量类型（向量类型见类级差异点②）。 */
        boolean supported() {
            return this == FLOAT || this == INT || this == BOOL;
        }

        /** 类型段文本 → 枚举（大小写不敏感；不认识就是 {@link #UNKNOWN}，绝不落到 FLOAT）。 */
        static Type of(String text) {
            return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "float" -> FLOAT;
                case "int" -> INT;
                case "bool" -> BOOL;
                case "vec2" -> VEC2;
                case "vec3" -> VEC3;
                case "vec4" -> VEC4;
                default -> UNKNOWN;
            };
        }
    }

    /** 求值结果按声明类型落成上传映射用的值（{@code OfUniformManager.write} 认 Number/Boolean）。 */
    Object toUploadValue(double value) {
        return switch (type) {
            case FLOAT -> (float) value;
            // 🔖 取整口径 = GLSL 的 int 截断（向零取整），不是四舍五入：
            //   文档只写「int 类型」没写转换规则（X9），而包里唯一的 int 型用法是
            //   `frameCounter % 8` 这类**本来就是整数**的表达式，两种口径在这些点上同值。
            case INT -> (int) value;
            case BOOL -> value != 0.0;
            default -> throw new IllegalStateException("vkdisp: 非标量类型不许落成上传值: " + type);
        };
    }
}
