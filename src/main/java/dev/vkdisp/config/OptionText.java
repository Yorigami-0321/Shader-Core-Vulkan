package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 取值文本校验与规范化（纯函数）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.1 / §3.5（Option/OptionType 的取值形态：true|false、
 *    整数列表、浮点列表、文本值；选项 → #define / uniform）；② docs/08-TESTING.md §4
 *    （"const int X = 0; // [0 1 2]" 型选项常量的取值必须能识别与求值）；③ docs/07-CONSTRAINTS.md T11。
 *    许可证：本仓库自有文档（本项目 MIT）→ 可直接消费。第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL ES 的十进制字面量与标识符词法事实（不受版权保护的语言事实）：
 *    标识符 = [A-Za-z_][A-Za-z0-9_]*；整数字面量 = 可选符号 + 十进制数字；
 *    浮点字面量 = 数字（含小数点或指数），且 GLSL 里 "1f" / "0x1p3" / "NaN" / "Infinity" 都不是合法十进制字面量。
 * 2. 备选：用 java.util.regex.Pattern —— 否决（同样的判定用一次字符扫描即可完成，且正则里反斜杠转义
 *    容易在"文档化校验规则"这件事上制造歧义；冷路径清晰优先，零性能考量）。
 *    备选：Float.parseFloat 直接信任输入 —— 否决（它接受 "1f"、"0x1p3"、"Infinity"，会把非法选项值静默变成有效值，
 *    违反 T11）。
 * 3. 我们的差异点：① 只认 ASCII 标识符（GLSL 不含 Unicode 标识符，Character.isLetter 放行中文会生成非法 GLSL）；
 *    ② 浮点规范化保证输出一定是 GLSL float 形态（整数形态补 ".0"，见 canonicalFloat），
 *    避免 "#define X 2" 被 GLSL 当成 int 造成类型不匹配；③ 所有校验都是纯函数、可单测。
 * 4. 许可证核对：本项目 MIT；只用 JDK 标准库类型，零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（切包 / 改选项时调用），不做任何性能优化（18-PARALLEL §7.7）。
 */

/** F 线取值文本的校验与规范化（包内工具，纯函数、无状态）。 */
final class OptionText {

    private OptionText() {
    }

    /** 是否为 GLSL 标识符（ASCII 字母/下划线开头，后接 ASCII 字母/数字/下划线）。 */
    static boolean isGlslIdentifier(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (!isAsciiLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isAsciiLetter(c) && !isAsciiDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** 是否为十进制整数字面量文本（可带 +/-）。 */
    static boolean isIntegerText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int i = 0;
        if (text.charAt(0) == '+' || text.charAt(0) == '-') {
            i = 1;
        }
        if (i >= text.length()) {
            return false;
        }
        for (; i < text.length(); i++) {
            if (!isAsciiDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否为十进制浮点字面量文本：{@code [+-]? ( digits [. digits*] | . digits ) [eE [+-]? digits] }。
     *
     * <p>整数形态（如 {@code "2"}）也算合法浮点文本（GLSL 里 {@code 2} 是 int，但选项值 "2" 属于合法的浮点取值，
     * 由 {@link #canonicalFloat(float)} 补成 {@code "2.0"}）。
     */
    static boolean isDecimalText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int i = 0;
        int n = text.length();
        if (text.charAt(i) == '+' || text.charAt(i) == '-') {
            i++;
        }
        boolean anyDigit = false;
        while (i < n && isAsciiDigit(text.charAt(i))) {
            i++;
            anyDigit = true;
        }
        if (i < n && text.charAt(i) == '.') {
            i++;
            while (i < n && isAsciiDigit(text.charAt(i))) {
                i++;
                anyDigit = true;
            }
        }
        if (!anyDigit) {
            return false;
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            int exponentDigits = 0;
            while (i < n && isAsciiDigit(text.charAt(i))) {
                i++;
                exponentDigits++;
            }
            if (exponentDigits == 0) {
                return false;
            }
        }
        return i == n;
    }

    /** 解析十进制整数文本；不合法或超出 long 范围时返回 null（绝不静默截断）。 */
    static Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析有限 32 位浮点文本；不合法 / 非有限值时返回 null。 */
    static Float parseFloatOrNull(String text) {
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? Float.valueOf(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 规范化浮点输出：保证文本一定是 GLSL float 形态（{@code 2} → {@code 2.0}），且与 Locale 无关。 */
    static String canonicalFloat(float value) {
        String text = Float.toString(value);
        if (text.indexOf('.') < 0 && text.indexOf('E') < 0 && text.indexOf('e') < 0) {
            text = text + ".0";
        }
        return text;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
