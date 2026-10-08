package dev.vkdisp.pack.uniform;

import java.util.List;
import java.util.Set;

/**
 * 【参考调研】GAP-021 包自写 uniform 表达式的语法树（`uniform.*` / `variable.*` 的值部分）
 * 0. 合规核对（第 0 步闸门，通过）：
 *    参考对象 = OptiFine/Iris 公开文档的**语法事实**（`uniform.<float|int|bool|vec2|vec3|vec4>.<名>
 *    =<表达式>` 与 `variable.` 同形；运算符 + - * / %、比较 == != < <= > >=、逻辑 ! && ||、
 *    向量分量访问 `.x/.y/.z/.w`（含 `.rgba`/`.stpq`/`.0123`，不支持 swizzle）——
 *    出处 = shaders.properties 官方参考「Custom Uniforms」页（本项目只读事实，零代码复制；
 *    Iris/OptiFine 实现本体属 LGPL/闭源 ⇒ 禁止读码，见 07-CONSTRAINTS L12 / X19-X21）。
 *    → 能否并入本项目（MIT）：可以（本文件是独立编写的 AST）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：无（OptiFine 无源码可参考，Iris 源码禁止读）⇒ 按文档语法自建。
 * 2. 备选：复用 {@code glsl/preprocess/DefineProcessor.ExprEval}（否决：那是**整数宏**口径，
 *    没有小数、没有函数调用、没有向量分量，且它在 glsl/ 线里 —— 那条线的表达式语义
 *    与 properties 的表达式语义不是一套，混用会让「#if 能不能调函数」这种问题变成不可判定）。
 * 3. 我们的差异点：① 三元 `? :` **不做** —— 文档的运算符/函数清单里没有它，
 *    BSL v10.1.8 的 28+13 行里也没有一处（实测核实），按 X9 不许发明语法；
 *    ② 矩阵分量访问 `matrix.<row>.<column>` **不做**（文档有、包没用，且需要喂进 Matrix4f，
 *    登记为缺口）；③ 树里只有 `double` 与「名+分量下标」两种叶子，
 *    于是本包全程零 Minecraft / 零 renderpearl / 零 joml 类型（架构约束见 06-MIGRATION §2.1）。
 * 4. 许可证核对结论：本项目 MIT；零第三方代码并入。
 * 5. 性能基线：❄️ 冷路径解析 + 每帧一次求值（十来个 double 运算），不做优化（T14 / X15）。
 */
sealed interface UniformExpr permits UniformExpr.Literal, UniformExpr.Name, UniformExpr.Unary,
        UniformExpr.Binary, UniformExpr.Call {

    /** 无引用整个表达式的名字。 */
    int NO_ELEMENT = -1;

    /**
     * 收集表达式引用到的全部标识符（依赖图与「未解析输入」点名用）。
     *
     * <p>🔖 为什么要它：BSL 的 `uniform.float.timeAngle` 引用 4 个 `variable.float.*`，
     * 求值顺序必须由依赖决定而不是由书写顺序决定（BSL 确实是先写变量后写 uniform，
     * 但 `isCold` 那条引用了**同段稍后才定义**的 yCold2/yCold3 —— 见 shaders.properties:172-175）。
     */
    default void collectNames(Set<String> out) {
        if (this instanceof Name name) {
            out.add(name.name());
        } else if (this instanceof Unary unary) {
            unary.operand().collectNames(out);
        } else if (this instanceof Binary binary) {
            binary.left().collectNames(out);
            binary.right().collectNames(out);
        } else if (this instanceof Call call) {
            for (UniformExpr argument : call.arguments()) {
                argument.collectNames(out);
            }
        }
    }

    /** 十进制字面量（文档口径：数字字面量；`pi` 由解析器直接折成常量，见 {@link UniformExpressionParser}）。 */
    record Literal(double value) implements UniformExpr {
    }

    /**
     * 引擎内建 / 先前定义的变量名。
     *
     * @param element 向量分量下标 0..3，{@link #NO_ELEMENT} = 取整个标量
     */
    record Name(String name, int element) implements UniformExpr {
    }

    /** 一元运算符（文档只给了取负与逻辑非两种）。 */
    record Unary(String operator, UniformExpr operand) implements UniformExpr {
    }

    /** 二元运算符（算术 + - * / %、比较 == != < <= > >=、逻辑 && ||）。 */
    record Binary(String operator, UniformExpr left, UniformExpr right) implements UniformExpr {
    }

    /**
     * 函数调用。
     *
     * <p>🔖 参数**全部留在树里**、不在解析期折叠：{@code if()} 与 {@code smooth()} 的
     * 取哪些分支是有副作用的（if 只求值命中的那支；smooth 要写跨帧状态），
     * 解析期折叠就等于替运行期做了决定。
     */
    record Call(String function, List<UniformExpr> arguments) implements UniformExpr {
    }
}
