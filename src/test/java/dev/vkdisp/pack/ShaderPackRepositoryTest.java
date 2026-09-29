package dev.vkdisp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B 线：ShaderPackRepository 路径规划验证。
 * 仅做解包索引 + 路径规划，不注册虚拟资源包（注册属主线）。
 */
class ShaderPackRepositoryTest {

    private static Path resourceDir(String rel) throws Exception {
        URL url = ShaderPackRepositoryTest.class.getResource(rel);
        assertNotNull(url, "fixture missing: " + rel);
        return Paths.get(url.toURI());
    }

    private static Path makeZip(@TempDir Path tmp, String name) throws IOException {
        Path zip = tmp.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("shaders/shaders.properties"));
            zos.write("shadow.enabled=true\n".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("shaders/composite.vsh"));
            zos.write("void main(){}\n".getBytes());
            zos.closeEntry();
        }
        return zip;
    }

    @Test
    void planDirectoryPack_indexesShaders() throws Exception {
        // /packs/valid 是 inventory，其下 demo/ 子目录是合法目录包
        var scan = ShaderPackScanner.scan(resourceDir("/packs/valid"));
        assertEquals(1, scan.packs().size());
        var plan = ShaderPackRepository.plan(scan.packs().get(0));
        assertEquals("demo", plan.packName());
        assertEquals(ShaderPackScanner.Kind.DIRECTORY, plan.kind());
        assertTrue(plan.shaderFiles().contains("shaders.properties"),
            "应包含 shaders.properties: " + plan.shaderFiles());
        assertTrue(plan.shaderFiles().contains("composite.vsh"),
            "应包含 composite.vsh: " + plan.shaderFiles());
        try (var in = plan.openShader("shaders.properties")) {
            String content = new String(in.readAllBytes());
            assertTrue(content.contains("shadow.enabled"), "应能读取 shaders.properties 内容");
        }
    }

    @Test
    void planZipPack_indexesAndReads(@TempDir Path tmp) throws Exception {
        makeZip(tmp, "ZPack.zip");
        var scan = ShaderPackScanner.scan(tmp);
        assertEquals(1, scan.packs().size());
        var plan = ShaderPackRepository.plan(scan.packs().get(0));
        assertEquals(ShaderPackScanner.Kind.ZIP, plan.kind());
        assertTrue(plan.shaderFiles().contains("shaders.properties"));
        try (var in = plan.openShader("shaders.properties")) {
            String content = new String(in.readAllBytes());
            assertTrue(content.contains("shadow.enabled"));
        }
    }

    @Test
    void openShader_missing_throws(@TempDir Path tmp) throws Exception {
        makeZip(tmp, "ZPack.zip");
        var scan = ShaderPackScanner.scan(tmp);
        var plan = ShaderPackRepository.plan(scan.packs().get(0));
        assertThrows(IOException.class, () -> plan.openShader("does-not-exist.vsh"));
    }
}
