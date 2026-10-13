package dev.vkdisp.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GAP-038 的雾读数仪器：格式化是纯函数，接线是一条文本针。 */
class TerrainDrawCaptureFogTest {

    private static final Path CAPTURE = Path.of("src/main/java/dev/vkdisp/bridge/TerrainDrawCapture.java");
    private static final Path MIXIN = Path.of("src/main/java/dev/vkdisp/mixin/LevelRendererMainPassCaptureMixin.java");

    private static String readOrSkip(Path path) {
        assumeTrue(Files.exists(path), () -> "源码不在（" + path + "）");
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            assumeTrue(false, "读不到 " + path);
            return "";
        }
    }

    @Test
    @DisplayName("读数行必须把「类型 + 六段距离 + 颜色」都带出来，且小数点不受区域设置影响")
    void summaryCarriesEveryField() {
        String line = TerrainDrawCapture.fogSummary("WATER", 1.0F, 5.0F, 80.0F, 96.0F, 128.0F, 192.0F,
                0.0235F, 0.5F, 0.75F, 1.0F);
        assertTrue(line.startsWith("fogType=WATER"), "进水那一帧最先变的就是类型：" + line);
        assertTrue(line.contains("env=[1.000,5.000]") && line.contains("renderDistance=[80.000,96.000]"),
                "起止距离必须成对出现（只报一个数就判不出「雾变近了还是变远了」）：" + line);
        assertTrue(line.contains("skyEnd=128.000") && line.contains("cloudEnd=192.000"), line);
        assertTrue(line.contains("color=(0.023,0.500,0.750,1.000)"), "颜色是包判「什么雾」的主要输入：" + line);
        // 🔖 Locale.ROOT：中文系统上 %f 会写成「1,000」⇒ 判据必须自己钉住小数点
        assertTrue(!line.contains("[1,000") && line.contains("1.000"), "小数分隔符必须是点：" + line);
    }

    @Test
    @DisplayName("仪器必须接在 M-06 的捕获点上，且读的是 FogData 而不是去猜 std140 字节")
    void instrumentReadsFogDataNotTheSlice() {
        String capture = readOrSkip(CAPTURE);
        assertTrue(capture.contains("fogSummary(") && capture.contains("[GAP-038/雾]"),
                "没有这行日志，第 3 步的判据（进水那一帧雾端读数跟着变）就没有仪器");
        assertTrue(capture.contains("cameraState.fogData"),
                "读切片要自己数 std140 偏移（那是拿猜测当判据）；读 FogData 与切片同源（GameRenderer:671-672）");
        assertTrue(!capture.contains("getFloat(") && !capture.contains(".buffer()"),
                "一旦自己去解析字节，布局改了读数会**悄悄错**而不是报错");

        String mixin = readOrSkip(MIXIN);
        assertTrue(mixin.contains("@Accessor(\"levelRenderState\")"),
                "取 levelRenderState 必须走 @Accessor（GAP-042：反射读私有字段 = 升版即静默失焦）");
        assertTrue(mixin.indexOf("TerrainDrawCapture.onCaptured(") > mixin.indexOf("@Accessor(\"levelRenderState\")")
                        || mixin.indexOf("@Accessor") > mixin.indexOf("TerrainDrawCapture.onCaptured("),
                "两条针都得在（缺一根时 indexOf 给 -1，会让顺序比较**假绿**）");
    }
}
