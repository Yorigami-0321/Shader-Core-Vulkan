package dev.vkdisp.pipeline.model;
/**
 * 【参考调研】E 线纯计算件 / 名字合法性校验小工具
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §4「字段名必须与着色器里的 attribute 声明完全一致」；
 *    ② Khronos GLSL 语言规范里标识符的词法事实（首字符字母或下划线，后续字母/数字/下划线）；
 *    ③ docs/07-CONSTRAINTS.md T11（校验结果要能显式暴露，不许静默放过）。
 *    许可证：GLSL 规范是 Khronos 公开发布的事实性规范文本 → 只取「哪些字符合法」这一不受版权保护的事实，
 *    零文本搬运；本仓库 docs 为本项目自有文档。参考模组（VulkanMod / Sulkan / Beryl）零接触
 *    —— 按 docs/07-CONSTRAINTS.md L12「判不过就换参考，不再读它的代码」，本文件不含任何参考模组源码。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL 标识符词法（等价正则 [A-Za-z_][A-Za-z0-9_]*）。
 * 2. 备选：用 java.lang.Character.isJavaIdentifierStart —— 否决（Java 标识符允许 Unicode 与 '$'，
 *    比 GLSL 宽，会把非法属性名当合法名字放过）。
 * 3. 我们的差异点：两条可测谓词 —— ① GLSL 标识符合法（属性名/绑定名）；② 缓存键文本安全
 *    （无空白、无 '|'、无控制字符，用于缓存键的长度前缀编码）。校验不抛异常，由调用方转成
 *    ModelDiagnostic；只有 PipelineCacheKey 这种「必须给出确定键」的入口才抛 IllegalArgumentException。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制，只含规范词法事实。
 * 5. 性能基线：❄️ 冷路径（构建管线/格式时一次性校验），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.Objects;

/**
 * E 线内部的命名校验工具（不对外暴露：本包外没有消费场景）。
 *
 * <p>所有谓词对 {@code null} 都返回 {@code false}（不抛），让调用方统一转成
 * {@link ModelDiagnostic}（T11 显式诊断）。
 */
final class ModelNames {

    private ModelNames() {
    }

    /** GLSL 标识符词法：{@code [A-Za-z_][A-Za-z0-9_]*}（GLSL 规范事实；天然不含空白与分隔符）。 */
    static boolean isGlslIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!isIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 是否可安全嵌入缓存键文本：无空白、无 '|' 分隔符、无 ISO 控制字符。 */
    static boolean isCacheTextSafe(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '|' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 要求非空白字符串。
     *
     * @throws NullPointerException     值为 null
     * @throws IllegalArgumentException 值为空白字符串
     */
    static String requireNonBlank(String value, String what) {
        Objects.requireNonNull(value, what);
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }

    /**
     * 要求可安全嵌入缓存键文本（非空白且 {@link #isCacheTextSafe}）。
     *
     * @throws NullPointerException     值为 null
     * @throws IllegalArgumentException 值为空白，或含空白 / '|' / 控制字符
     */
    static String requireCacheTextSafe(String value, String what) {
        requireNonBlank(value, what);
        if (!isCacheTextSafe(value)) {
            throw new IllegalArgumentException(
                    what + " must not contain whitespace, '|' or control characters, but was: " + value);
        }
        return value;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }
}
