package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B 线：ShaderPackScanner 扫包行为验证。期望值独立硬编码，不依赖第三方包。
 *
 * 语义约定（handover §6.1）：scan 的入参是「库存目录（inventory，对应游戏 shaderpacks/）」，
 * 其**每个子条目**（子目录或 .zip）才是一个包。因此：
 * - /packs/valid 是一个 inventory，内含 demo/ 子目录，demo/shaders/... 才是合法包。
 * - /packs/broken 是一个 inventory，内含 nested/（多嵌套一层）与 no-shaders/（缺 shaders）。
 */
class ShaderPackScannerTest {

    private static Path resourceDir(String rel) throws Exception {
        URL url = ShaderPackScannerTest.class.getResource(rel);
        assertNotNull(url, "fixture missing: " + rel);
        return Paths.get(url.toURI());
    }

    @Test
    void validDirectoryPack_isDiscovered() throws Exception {
        // /packs/valid 是 inventory，其下 demo/ 子目录含 shaders/ → 1 个目录包
        var r = ShaderPackScanner.scan(resourceDir("/packs/valid"));
        assertEquals(1, r.packs().size(), "应发现 1 个合法目录包");
        assertTrue(r.problems().isEmpty(), "合法包不应有问题: " + r.problems());
        var p = r.packs().get(0);
        assertEquals("demo", p.name());
        assertEquals(ShaderPackScanner.Kind.DIRECTORY, p.kind());
        assertEquals("shaders", p.shadersPrefix());
    }

    @Test
    void validZipPack_isDiscovered(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("MyPack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("shaders/shaders.properties"));
            zos.write("shadow.enabled=true\n".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("shaders/composite.vsh"));
            zos.write("void main(){}\n".getBytes());
            zos.closeEntry();
        }
        var r = ShaderPackScanner.scan(tmp); // tmp 作为 inventory，内含 1 个 zip 包
        assertEquals(1, r.packs().size(), "zip 包应被发现");
        var p = r.packs().get(0);
        assertEquals("MyPack", p.name());
        assertEquals(ShaderPackScanner.Kind.ZIP, p.kind());
        assertEquals("shaders/", p.shadersPrefix());
    }

    @Test
    void emptyInventory_reportsNoPacks(@TempDir Path tmp) {
        var r = ShaderPackScanner.scan(tmp);
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.NO_PACKS_FOUND, r.problems().get(0).kind());
    }

    @Test
    void missingInventory_reportsMissing() {
        var r = ShaderPackScanner.scan(Path.of("this-path-does-not-exist-xyz"));
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.INVENTORY_MISSING, r.problems().get(0).kind());
    }

    @Test
    void brokenZip_reportsBroken(@TempDir Path tmp) throws Exception {
        Path inventory = tmp.resolve("inventory");
        Files.createDirectory(inventory);
        Path corrupt = inventory.resolve("broken.zip");
        Files.write(corrupt, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07});
        var r = ShaderPackScanner.scan(inventory);
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.BROKEN_ZIP, r.problems().get(0).kind());
    }

    @Test
    void directoryWithoutShaders_reportsMissing(@TempDir Path tmp) throws Exception {
        Path inventory = tmp.resolve("inventory");
        Files.createDirectory(inventory);
        Path noShaders = inventory.resolve("noShaders");
        Files.createDirectory(noShaders);
        Files.writeString(noShaders.resolve("readme.txt"), "hi");
        var r = ShaderPackScanner.scan(inventory);
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.MISSING_SHADERS_DIR, r.problems().get(0).kind());
    }

    @Test
    void nestedOuterFolder_reportsNested(@TempDir Path tmp) throws Exception {
        Path inventory = tmp.resolve("inventory");
        Files.createDirectory(inventory);
        Path outer = inventory.resolve("outer");
        Files.createDirectory(outer);
        Files.createDirectories(outer.resolve("inner").resolve("shaders")); // 多级创建：outer/inner 需先存在
        var r = ShaderPackScanner.scan(inventory);
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.OUTER_FOLDER_NESTED, r.problems().get(0).kind());
    }

    @Test
    void emptyPackEntry_reportsEmptyEntry(@TempDir Path tmp) throws Exception {
        Path inventory = tmp.resolve("inventory");
        Files.createDirectory(inventory);
        Files.createDirectory(inventory.resolve("emptyPack")); // 空目录作为包条目
        var r = ShaderPackScanner.scan(inventory);
        assertTrue(r.packs().isEmpty());
        assertEquals(1, r.problems().size());
        assertEquals(ShaderPackScanner.ProblemKind.EMPTY_PACK_ENTRY, r.problems().get(0).kind());
    }

    @Test
    void brokenInventory_fixture_twoProblems() throws Exception {
        // /packs/broken 作为 inventory：nested/（多嵌套一层）+ no-shaders/（缺 shaders）
        var r = ShaderPackScanner.scan(resourceDir("/packs/broken"));
        assertEquals(0, r.packs().size(), "broken inventory 不应有合法包");
        assertEquals(2, r.problems().size(), "应恰好 2 条 problem: " + r.problems());
        List<ShaderPackScanner.ProblemKind> kinds = r.problems().stream().map(ShaderPackScanner.PackProblem::kind).toList();
        assertTrue(kinds.contains(ShaderPackScanner.ProblemKind.OUTER_FOLDER_NESTED),
            "应包含 OUTER_FOLDER_NESTED: " + kinds);
        assertTrue(kinds.contains(ShaderPackScanner.ProblemKind.MISSING_SHADERS_DIR),
            "应包含 MISSING_SHADERS_DIR: " + kinds);
    }
}
