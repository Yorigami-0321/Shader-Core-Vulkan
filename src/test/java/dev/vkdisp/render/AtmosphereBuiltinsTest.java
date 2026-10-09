package dev.vkdisp.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GAP-029：链上「包当引擎会给、我方一直没给」的那批内建量。
 *
 * <p>钉两件事：① 三个承重量的**取值口径**（白天/夜晚、晴/雨、gamma 两端）；
 * ② 一条<b>覆盖网</b> —— 名字要么被供、要么被<b>显式登记为不供</b>，
 * 不许落在「没人知道它缺」那一格里（那正是本条缺陷得以活到 h50f 的原因）。
 */
@DisplayName("GAP-029 链上承重内建的取值与覆盖网")
class AtmosphereBuiltinsTest {

    private static final Path OF_UNIFORMS =
            Path.of("src/main/java/dev/vkdisp/render/OfUniformManager.java");

    /** 本次落地必须真的出现在供值路径里的名字（缺一即红）。 */
    private static final List<String> LOAD_BEARING =
            List.of("shadowFade", "timeBrightness", "screenBrightness");

    @Test
    @DisplayName("timeBrightness：白天(skyDarken=0)=1、夜晚(skyDarken=11)=0，两端夹住")
    void timeBrightnessMapsDayAndNight() {
        assertEquals(1.0F, AtmosphereBuiltins.timeBrightness(0), 0.0F);
        assertEquals(0.0F, AtmosphereBuiltins.timeBrightness(AtmosphereBuiltins.SKY_DARKEN_NIGHT), 0.0F);
        assertEquals(6.0F / 11.0F, AtmosphereBuiltins.timeBrightness(5), 0.001F);
        assertEquals(1.0F, AtmosphereBuiltins.timeBrightness(-3), 0.0F);
        assertEquals(0.0F, AtmosphereBuiltins.timeBrightness(15), 0.0F);
    }

    @Test
    @DisplayName("shadowFade：晴=1、暴雨=0（恒 0 会把 BSL 的光柱/高光整条乘没）")
    void shadowFadeFollowsRain() {
        assertEquals(1.0F, AtmosphereBuiltins.shadowFade(0.0F), 0.0F);
        assertEquals(0.0F, AtmosphereBuiltins.shadowFade(1.0F), 0.0F);
        assertEquals(0.25F, AtmosphereBuiltins.shadowFade(0.75F), 0.001F);
        assertEquals(0.0F, AtmosphereBuiltins.shadowFade(2.0F), 0.0F);
    }

    @Test
    @DisplayName("screenBrightness：原版 gamma ∈ [-1,1] 映到 [0,1]，默认 0 ⇒ 0.5")
    void screenBrightnessMapsGammaRange() {
        assertEquals(0.0F, AtmosphereBuiltins.screenBrightness(-1.0), 0.0F);
        assertEquals(0.5F, AtmosphereBuiltins.screenBrightness(0.0), 0.0F);
        assertEquals(1.0F, AtmosphereBuiltins.screenBrightness(1.0), 0.0F);
        assertEquals(1.0F, AtmosphereBuiltins.screenBrightness(3.0), 0.0F);
    }

    @Test
    @DisplayName("三个承重名字真的进了供值路径（不是只存在于新类里）")
    void loadBearingNamesAreActuallyPut() throws Exception {
        String source = Files.readString(OF_UNIFORMS);
        for (String name : LOAD_BEARING) {
            assertTrue(source.contains("values.put(\"" + name + "\""),
                    name + " 没有出现在 OfUniformManager 的供值路径里 ⇒ GAP-029 那一格仍是恒 0");
        }
    }

    @Test
    @DisplayName("覆盖网：供值的与显式不供的不得重叠，且 centerDepthSmooth 必须被点名")
    void suppliedAndDeclaredUnsuppliedAreDisjoint() {
        List<String> unsupplied = AtmosphereBuiltins.declaredUnsupplied();
        Set<String> seen = new HashSet<>();
        for (String name : unsupplied) {
            assertTrue(seen.add(name), "显式不供清单里有重名: " + name);
            assertFalse(LOAD_BEARING.contains(name),
                    name + " 既被列为「显式不供」又被列为承重要供 ⇒ 两个真源");
        }
        // 想供但供不了的那一个必须留在清单里：它是最容易被"顺手塞个像样的值"抹掉的一条。
        assertTrue(unsupplied.contains("centerDepthSmooth"),
                "centerDepthSmooth 需要时域平滑 + 中心像素读数，本轮不供 ⇒ 必须显式点名而不是静默缺值");
    }
}
