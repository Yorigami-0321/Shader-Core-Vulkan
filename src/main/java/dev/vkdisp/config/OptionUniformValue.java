package dev.vkdisp.config;
/**
 * 【参考调研】F 线选项模型 / 选项值的 uniform 形态（04-SPEC §3.5 的"选项 → uniform"一侧）
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = ① 本仓库 docs/04-SPEC.md §3.5（OptionBinding = 选项 → 着色器 #define / uniform 的绑定）
 *    与 §3.2（OF 内建 uniform 的类型语义：float/int/vec 等原版 GLSL 类型）；
 *    ② docs/18-PARALLEL.md §4 F 线（交付物：选项值 → #define 表 / uniform 值）与 §7.3（单测可打印比对）；
 *    ③ F2 已冻结的 dev.vkdisp.pack.OptionType（BOOLEAN / INTEGER / FLOAT / STRING）。
 *    许可证：本仓库自有文档与自有冻结契约（本项目 MIT）→ 可直接消费；第三方源码零接触（07-CONSTRAINTS L12）。
 *    → 能否并入本项目（MIT）：本文件为独立实现，可并入
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：GLSL uniform 的基本类型事实（不受版权保护的语言事实）：bool / int / float / 采样器等；
 *    文本选项在 GLSL 里没有对应标量类型，若要上传只能用字符串标签（本项目留给后续按包语义决定）。
 * 2. 备选：一个 record 带 Object value —— 否决（Object 让调用方必须做 instanceof 猜类型，且打印比对会出现
 *    "看不出类型"的值；F 线冷路径要的是"类型自明"）。备选：Map<String,Object> —— 同样否决。
 * 3. 我们的差异点：① 每个类型一个 record（BoolValue / IntValue / FloatValue / TextValue），
 *    共同实现本接口，调用方用模式匹配取类型化值；② 每个值都同时带 canonicalText（规范文本），
 *    保证同一份选项在 #define 表与 uniform 表里是可逐字符比对的同一文本；③ 不在这里做 uniform 上传
 *    （上传要真实 GPU 验证，属 18-PARALLEL §2.1 的关键路径）。
 * 4. 许可证核对：本项目 MIT；只用 JDK 标准库与自有 OptionType，零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径小对象（绑定快照时一次构造），不做任何性能优化（18-PARALLEL §7.7）。
 */
import java.util.Objects;

import dev.vkdisp.pack.OptionType;

/**
 * 一个选项值的 uniform 形态（类型自明、纯数据、可打印比对）。
 *
 * <p>类型映射（{@link dev.vkdisp.pack.OptionType} → GLSL uniform 类型）：
 * BOOLEAN → {@code bool}、INTEGER → {@code int}、FLOAT → {@code float}、STRING → 文本标签（不直接上传）。
 */
public interface OptionUniformValue {

    /** 选项名（uniform 名同此）。 */
    String name();

    /** 选项类型。 */
    OptionType type();

    /** 规范文本（与 #define 表里的替换文本一致）。 */
    String canonicalText();

    /** 单行打印形式，供日志与单测比对：{@code NAME TYPE text=...}。 */
    default String format() {
        return name() + " " + type() + " text=" + canonicalText();
    }

    /** 布尔选项的 uniform 值。 */
    record BoolValue(String name, boolean value, String canonicalText) implements OptionUniformValue {

        /** 紧凑构造器：name / canonicalText 非空。 */
        public BoolValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(canonicalText, "canonicalText");
        }

        @Override
        public OptionType type() {
            return OptionType.BOOLEAN;
        }

        @Override
        public String format() {
            return name() + " BOOLEAN text=" + canonicalText() + " bool=" + value();
        }
    }

    /** 整数选项的 uniform 值。 */
    record IntValue(String name, int value, String canonicalText) implements OptionUniformValue {

        /** 紧凑构造器：name / canonicalText 非空。 */
        public IntValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(canonicalText, "canonicalText");
        }

        @Override
        public OptionType type() {
            return OptionType.INTEGER;
        }

        @Override
        public String format() {
            return name() + " INTEGER text=" + canonicalText() + " int=" + value();
        }
    }

    /** 浮点选项的 uniform 值。 */
    record FloatValue(String name, float value, String canonicalText) implements OptionUniformValue {

        /** 紧凑构造器：name / canonicalText 非空。 */
        public FloatValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(canonicalText, "canonicalText");
        }

        @Override
        public OptionType type() {
            return OptionType.FLOAT;
        }

        @Override
        public String format() {
            return name() + " FLOAT text=" + canonicalText() + " float=" + value();
        }
    }

    /** 文本选项的 uniform 值（GLSL 侧无标量文本类型；此处只作为标签承载）。 */
    record TextValue(String name, String value, String canonicalText) implements OptionUniformValue {

        /** 紧凑构造器：name / value / canonicalText 非空。 */
        public TextValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(canonicalText, "canonicalText");
        }

        @Override
        public OptionType type() {
            return OptionType.STRING;
        }

        @Override
        public String format() {
            return name() + " STRING text=" + canonicalText();
        }
    }
}
