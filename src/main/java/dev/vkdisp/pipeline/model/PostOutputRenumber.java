package dev.vkdisp.pipeline.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后处理片元的 {@code layout(location = colortex槽号)} → {@code layout(location = 附件下标)} 重编号。
 *
 * <p><b>为什么必须重编号</b>（OF/Iris 语义在本引擎里的唯一落点）：Vulkan 里片元输出的
 * location 是**本 pass 附件数组的下标**，而转译终稿的 location 是 **colortex 槽号**
 * （h44 起由 {@code DrawBuffersSlotAdapter} 钉死的口径）。地形 MRT pass 恰好「附件 = 前缀
 * [c0..cN-1]」，两者数值相等，掩盖了这个差别；后处理 pass 写的槽是**稀疏**的
 * （例：BSL composite 只写 colortex4），不换算就会写到错误的附件上。
 *
 * <p>🔖 <b>等行数改写</b>：行号映射不受影响（同 ⑦½ / 探针段口径）。
 *
 * <p>🔴 <b>拒绝条件必须响亮</b>：出现「声明的 location 不在槽集合里」= 解析/计划不一致，
 * 抛异常而不是猜 —— 猜出来的附件布局是静默错画的新家（07 X11）。
 */
public final class PostOutputRenumber {

    private static final Pattern OUT_DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)(\\s+out\\s+)([A-Za-z_]\\w*"
                    + "(?:\\s+[A-Za-z_]\\w*)?)(\\s*;)");

    private PostOutputRenumber() {}

    /**
     * 执行重编号。
     *
     * @param source 转译终稿
     * @param slots  该 pass 的槽集合（**必须升序**；附件下标 = 该槽在此列表中的位置）
     * @return 重编号后的源
     * @throws IllegalArgumentException 槽列表非升序不齐 / 源里没有对应 out 声明 / 声明的槽不在列表里
     */
    public static String apply(String source, List<Integer> slots) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("vkdisp: post 重编号的源不许为空");
        }
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("vkdisp: post 重编号至少要有一个槽");
        }
        for (int i = 1; i < slots.size(); i++) {
            if (slots.get(i) <= slots.get(i - 1)) {
                throw new IllegalArgumentException(
                        "vkdisp: post 槽集合必须严格升序，实际 " + slots);
            }
        }
        Matcher matcher = OUT_DECL.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int rewritten = 0;
        int last = 0;
        List<Integer> seen = new ArrayList<>();
        while (matcher.find()) {
            int slot = Integer.parseInt(matcher.group(1));
            int index = slots.indexOf(slot);
            if (index < 0) {
                throw new IllegalArgumentException(
                        "vkdisp: 片元声明 location=" + slot + " 不在计划槽集合 " + slots
                                + " 里 —— 契约解析与计划不一致，**不猜附件布局**（X11）");
            }
            if (seen.contains(slot)) {
                throw new IllegalArgumentException(
                        "vkdisp: 同一槽 " + slot + " 出现第二条 out 声明 —— 附件布局有歧义，拒绝");
            }
            seen.add(slot);
            out.append(source, last, matcher.start())
                    .append("layout(location = ").append(index).append(')')
                    .append(matcher.group(2)).append(matcher.group(3)).append(matcher.group(4));
            last = matcher.end();
            rewritten++;
        }
        out.append(source, last, source.length());
        if (rewritten == 0) {
            throw new IllegalArgumentException(
                    "vkdisp: 重编号一处 out 声明都没命中（开关没生效 ≠ 结论不成立，X45）；源前 200 字符: "
                            + source.substring(0, Math.min(200, source.length())));
        }
        if (rewritten != slots.size()) {
            throw new IllegalArgumentException(
                    "vkdisp: out 声明数 " + rewritten + " 与槽集合数 " + slots.size()
                            + " 不符 ⇒ 有附件永远没人写、或有声明没被换算，拒绝执行");
        }
        return out.toString();
    }
}
