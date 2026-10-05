package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vkdisp.pipeline.model.PostSamplerSuperset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 用**真实 BSL v10.1.8** 走生产链路量测后处理整链 —— 守卫「链真的能被建出来」。
 *
 * <p>🔖 为什么这条必须存在：单元测试钉住了排序/重编号/拒绝规则，但「BSL 十个全屏步
 * 在默认配置下各自声明哪些槽、有没有步被我们的闸门踢掉」只能对真包量一次。
 * 这与 {@code TerrainProductionOutputCountTest} 是同一口径：**生产实际值 ≠ 能力上限**，
 * 也不许拿 fixture 的数字当真包的数字。
 */
class PostChainBslTest {

    private static final Path INVENTORY = Path.of("run/shaderpacks");

    @Test
    @DisplayName("🔴 BSL 整链：deferred/composite/final 全族在场、契约齐备、无越界")
    void bslChainBuildsWithTheExpectedFamilies() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        String bslName = ShaderPackScanner.scan(INVENTORY).packs().stream()
                .map(ShaderPackScanner.DiscoveredPack::name)
                .filter(n -> n.startsWith("BSL_v10.1.8"))
                .findFirst().orElse(null);
        assertNotNull(bslName, "没扫到 BSL_v10.1.8");

        PackCompositeSource.Result result =
                PackCompositeSource.generate(INVENTORY, "", bslName, null);
        assertFalse(result.fallback(), "BSL 不该走兜底；诊断: " + result.diagnostics());

        PackPostChain.Chain chain = result.chain();
        List<String> names = chain.passes().stream().map(PackPostChain.Pass::programName).toList();
        assertTrue(names.contains("deferred"), () -> "BSL 的 deferred(AO) 必须进链；实际: " + names);
        assertTrue(names.contains("composite"), () -> "BSL 的 composite 必须进链；实际: " + names);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("composite") && n.length() > 9),
                () -> "BSL 的 composite1..7 至少有一步进链；实际: " + names);
        assertTrue(names.contains("final"),
                () -> "BSL 的 final 必须进链且是最后一步；实际: " + names);
        assertEquals("final", names.get(names.size() - 1), "final 必须收尾");

        List<String> problems = new ArrayList<>();
        for (PackPostChain.Pass pass : chain.passes()) {
            if (pass.attachmentSlots().isEmpty() || pass.attachmentSlots().size() > 8) {
                problems.add(pass.programName() + " 槽数越界: " + pass.attachmentSlots());
            }
            if (!PostSamplerSuperset.NAMES.containsAll(pass.samplerNames())) {
                problems.add(pass.programName() + " sampler 越界: " + pass.samplerNames());
            }
            if (pass.attachmentSlots().size() > 8) {
                problems.add(pass.programName() + " 附件下标越界");
            }
        }
        assertTrue(problems.isEmpty(), () -> "链内契约违例：" + problems
                + "\n踢出记录: " + result.diagnostics());
    }

    @Test
    @DisplayName("🔖 链里每一步的重编号源都含「location = 0」起步的连续下标（不是 colortex 槽号）")
    void everyPassSourceUsesContiguousAttachmentIndices() {
        Assumptions.assumeTrue(Files.isDirectory(INVENTORY), "库存目录不在本地（run/shaderpacks/）");
        String bslName = ShaderPackScanner.scan(INVENTORY).packs().stream()
                .map(ShaderPackScanner.DiscoveredPack::name)
                .filter(n -> n.startsWith("BSL_v10.1.8"))
                .findFirst().orElse(null);
        assertNotNull(bslName);
        PackCompositeSource.Result result =
                PackCompositeSource.generate(INVENTORY, "", bslName, null);

        for (PackPostChain.Pass pass : result.chain().passes()) {
            List<Integer> slots = pass.attachmentSlots();
            for (int i = 0; i < slots.size(); i++) {
                final int index = i;
                assertTrue(pass.renumberedSource().contains("layout(location = " + index + ") out"),
                        () -> pass.programName() + " 的重编号源应含附件下标 " + index
                                + "；实际声明: " + pass.attachmentSlots());
            }
        }
    }
}
