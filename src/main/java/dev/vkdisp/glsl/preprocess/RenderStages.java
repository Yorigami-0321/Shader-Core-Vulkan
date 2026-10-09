package dev.vkdisp.glsl.preprocess;
/**
 * 【参考调研】OF/Iris 的 {@code MC_RENDER_STAGE_*} 宏集合（GAP-028）
 * 0. 合规核对（第 0 条，L12 / X19）：
 *    参考对象 = ① {@code shaders.properties} 文档站
 *    {@code current/reference/macros/render_stages/}（**只取「有哪些阶段名、每个阶段画什么几何」
 *    这一层事实**，未复制其任何文字表述或代码）；
 *    ② 本仓库实测：{@code run/h27/shaderpacks/BSL_v10.1.8.zip} 的
 *    {@code shaders/program/gbuffers_skybasic.glsl:137,143-145,168-171,179} 与
 *    {@code shaders/lib/util/voxelMap.glsl:19-22}（全包共 19 处引用这一族）；
 *    ③ 真机日志（{@code evidence/h49-water-translucent-draw.md}）：
 *    {@code gbuffers_skybasic.vsh:243: error: 'MC_RENDER_STAGE_STARS' : undeclared identifier}。
 *    许可证：文档站 = 社区文档（事实层面）；BSL = 其自有许可（**只读它引用了哪些名字**，
 *    不复制其代码）；本项目 = MIT。→ 能否并入：可以（本文件是独立编写的常量表）
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：Iris / OptiFine 在预处理期把这组宏塞进包的着色器源，并提供 {@code renderStage}
 *    这个 {@code uniform int} 的逐 draw 值。
 * 2. 备选：① 继续不塞 —— 后果就是真机那样<b>两条 skybasic 程序编译失败</b>（{@code world0} 与
 *    {@code world1}），整条天空基础 pass 拿不到包的着色器；<b>否决</b>（支柱①）。
 *    ② 抄 Iris 的编号 —— <b>未做</b>：Iris 的数值口径本轮<b>没有核实到源码级</b>（X9 不许猜），
 *    而<b>包侧只用宏名比较</b>（BSL 逐字 {@code renderStage == MC_RENDER_STAGE_STARS}），
 *    所以编号只要<b>唯一且自洽</b>就能恢复兼容。见下面「数值口径」一节。
 * 3. 我们的差异点：<b>宏名集合按文档事实给全</b>（含 Iris 自己标注「当前未使用」的那几个 ——
 *    给了不伤，不给则引用它的包直接编译失败）；{@code renderStage} 的<b>逐 draw 供值</b>是
 *    <b>另一件事</b>，本类不假装做完（见 {@code docs/13-GAP-REGISTRY.md} GAP-028 的 ③）。
 * 4. 许可证核对：本项目 MIT；零第三方代码复制。
 * 5. 性能基线：❄️ 冷路径（预处理期建一次宏表），渲染期零访问。
 */
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code MC_RENDER_STAGE_*} 宏表（GAP-028 第一步：让包<b>编译得过</b>）。
 *
 * <p><b>为什么这张表必须存在</b>：包把「这一批几何是天空里的哪一层」写成
 * {@code renderStage == MC_RENDER_STAGE_STARS} 这类比较，而这些宏<b>由加载器提供</b> ——
 * 与我方此前漏掉 {@code MC_VERSION}（GAP-021）是<b>同一族</b>「包假定加载器会塞的引擎侧常量」。
 * 真机后果不是「效果不对」而是<b>整个 {@code gbuffers_skybasic} 顶点阶段编译失败</b>。
 * 🔖 它此前<b>没暴露</b>，是因为 {@code MC_VERSION} 恒 0 ⇒ 那条 {@code #if MC_VERSION >= 11605}
 * 一直走 {@code #else} 分支；h48x 把 {@code MC_VERSION} 供成 260300 之后才<b>第一次被编译</b>。
 *
 * <p>🔴 <b>数值口径（不许被读成「与 Iris 一致」）</b>：本表把 {@code NONE} 定为 0，
 * 其余按文档列出的阶段顺序从 1 递增。<b>这个编号是我方自己的 ABI</b> ——
 * Iris 的数值本轮未核实到源码。之所以可以这样：
 * <ul>
 *   <li>包侧的用法是<b>宏名比较</b>（BSL 逐字如此），只要「同名同值 + 值互不相同」就成立；</li>
 *   <li>反证也拿到了：BSL 自己给 {@code MC_RENDER_STAGE_MOON} 的兜底值是 {@code 1}，
 *       而我方按文档顺序给的是 {@code 6} ⇒ <b>连包作者的猜测都与 Iris 未必一致</b>，
 *       说明「数字」本来就不是包的比较依据；</li>
 *   <li>⚠️ <b>已知不受支持面</b>：若某个包<b>硬编码</b> {@code renderStage == 14} 这类数字，
 *       它在本引擎会拿到错阶段 ⇒ 真遇到时要把本表回填成核实过的 Iris 口径（登记在 GAP-028）。</li>
 * </ul>
 */
public final class RenderStages {

    /** 宏名前缀（OF/Iris 口径）。 */
    public static final String MACRO_PREFIX = "MC_RENDER_STAGE_";

    /** 阶段名（<b>不含前缀</b>），顺序 = 文档列出的阶段顺序；下标 + 0 即宏值。 */
    public static final List<String> STAGES = List.of(
            "NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID",
            "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES",
            "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID",
            "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW",
            "WORLD_BORDER", "HAND_TRANSLUCENT");

    private static final Map<String, String> MACROS = build();

    private RenderStages() {
    }

    private static Map<String, String> build() {
        Map<String, String> macros = new LinkedHashMap<>();
        for (int i = 0; i < STAGES.size(); i++) {
            macros.put(MACRO_PREFIX + STAGES.get(i), Integer.toString(i));
        }
        return Collections.unmodifiableMap(macros);
    }

    /** 要塞进预处理宏表的 {@code 宏名 → 十进制字面量}（保持文档顺序，日志可复算）。 */
    public static Map<String, String> macros() {
        return MACROS;
    }

    /** 某个宏的值；不是本族宏返回 {@code null}。 */
    public static Integer valueOf(String macroName) {
        String text = MACROS.get(macroName);
        return text == null ? null : Integer.valueOf(text);
    }

    /** 自报用的一行摘要（「塞了几个、NONE 是几、编号是谁的口径」三件事都要说得出）。 */
    public static String describe() {
        return "count=" + MACROS.size() + " NONE=0 numbering=vkdisp-own-ABI(Iris-numbers-unverified)";
    }

    /** 宏名列表（单测与文档用；不可变）。 */
    public static List<String> macroNames() {
        return new ArrayList<>(MACROS.keySet());
    }
}
