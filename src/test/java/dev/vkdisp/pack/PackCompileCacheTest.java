package dev.vkdisp.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackCompileCache} 契约：同键复用 / 换键隔离 / 容量上限。
 *
 * <p><b>最关键的一条是「换包必须换键」</b>：缓存按「路径 + 大小 + 修改时间」识别包身份。
 * 若只按路径，用户原地替换包文件后会吃到旧产物 —— 表现为「换了包但画面还是旧的」，
 * 且极难排查（所有日志都正常）。这条用真实临时文件锁住。
 */
class PackCompileCacheTest {

    private static ShaderPackCompiler.CompileResult fakeResult() {
        // 只需要一个非null 产物用于验证缓存行为；内容不参与断言
        return new ShaderPackCompiler.CompileResult(null, java.util.List.of(), java.util.List.of());
    }

    private static ShaderPackScanner.DiscoveredPack packAt(Path zip) {
        return new ShaderPackScanner.DiscoveredPack(
                "TestPack", ShaderPackScanner.Kind.ZIP, zip, "");
    }

    @Test
    void nullKeyNeverStores() {
        PackCompileCache.invalidate();
        PackCompileCache.put(null, fakeResult());
        assertNull(PackCompileCache.get(null), "null 键不参与缓存");
        assertEquals(0, PackCompileCache.size());
    }

    @Test
    void putThenGetReturnsSameInstance() {
        PackCompileCache.invalidate();
        PackCompileCache.Key key = new PackCompileCache.Key("pack-a", Map.of());
        var result = fakeResult();
        PackCompileCache.put(key, result);

        assertSame(result, PackCompileCache.get(key), "同键应返回同一实例");
        assertEquals(1, PackCompileCache.size());
    }

    @Test
    void differentOverridesMeanDifferentKeys() {
        PackCompileCache.invalidate();
        // 选项差分表参与键：改了选项就不该复用旧产物（选项改写发生在预处理前，会改宏展开）
        var a = new PackCompileCache.Key("pack-a", Map.of("OPT", "1"));
        var b = new PackCompileCache.Key("pack-a", Map.of("OPT", "2"));
        assertNotEquals(a, b, "差分表不同 → 必须不同键");

        PackCompileCache.put(a, fakeResult());
        assertNull(PackCompileCache.get(b), "不同键不许命中");
    }

    @Test
    void identityChangesWhenFileContentChanges(@TempDir Path dir) throws Exception {
        PackCompileCache.invalidate();
        Path zip = dir.resolve("pack.zip");
        Files.writeString(zip, "content-A");

        var first = ShaderPackScanner.DiscoveredPack.class;
        var packA = packAt(zip);
        String identityA = packA.identity();

        // 原地替换内容 + 强制修改时间（文件系统的 mtime 精度可能是秒级）
        Files.writeString(zip, "content-B-much-longer");
        Files.setLastModifiedTime(zip, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 10_000L));

        var packB = packAt(zip);
        assertNotEquals(identityA, packB.identity(),
                "包文件被替换后身份必须变化（否则会吃到旧包的缓存）");
        assertNotNull(first);
    }

    @Test
    void identityIsStableForUnchangedFile(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("pack.zip");
        Files.writeString(zip, "stable-content");
        var a = packAt(zip).identity();
        var b = packAt(zip).identity();
        assertEquals(a, b, "文件没变时身份必须稳定（否则缓存永远不命中）");
    }

    @Test
    void identityOfMissingPathDegradesGracefully() {
        // 路径不存在 → 不抛，返回带 missing 标记的身份
        String identity = new ShaderPackScanner.DiscoveredPack(
                "Ghost", ShaderPackScanner.Kind.ZIP, Path.of("no", "such", "pack.zip"), "").identity();
        assertNotNull(identity);
        assertTrue(identity.contains("missing"), "应标记 missing，实际=" + identity);
    }

    @Test
    void identityOfDirectoryPackWalksTree(@TempDir Path dir) throws Exception {
        Path packDir = dir.resolve("mypack");
        Path shaders = packDir.resolve("shaders");
        Files.createDirectories(shaders);
        Path f = shaders.resolve("composite.fsh");
        Files.writeString(f, "void main(){}");

        var pack = new ShaderPackScanner.DiscoveredPack(
                "DirPack", ShaderPackScanner.Kind.DIRECTORY, packDir, "shaders");
        String before = pack.identity();

        // 在目录树深处加一个文件 → 目录身份必须变（否则改包后仍吃旧缓存）
        Files.writeString(shaders.resolve("extra.glsl"), "// new");
        Files.setLastModifiedTime(shaders.resolve("extra.glsl"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000L));

        assertNotEquals(before, pack.identity(), "目录内新增文件必须改变包身份");
    }

    @Test
    void cacheEvictsWhenOverCapacity() {
        PackCompileCache.invalidate();
        for (int i = 0; i < PackCompileCache.MAX_ENTRIES + 4; i++) {
            PackCompileCache.put(new PackCompileCache.Key("pack-" + i, Map.of()), fakeResult());
        }
        assertTrue(PackCompileCache.size() <= PackCompileCache.MAX_ENTRIES,
                "缓存不得超过容量上限，实际=" + PackCompileCache.size());
    }

    @Test
    void invalidateClearsCounters() {
        PackCompileCache.invalidate();
        PackCompileCache.put(new PackCompileCache.Key("k", Map.of()), fakeResult());
        PackCompileCache.get(new PackCompileCache.Key("k", Map.of()));
        assertTrue(PackCompileCache.hitCount() >= 1);

        PackCompileCache.invalidate();
        assertEquals(0, PackCompileCache.size());
        assertEquals(0, PackCompileCache.hitCount());
        assertEquals(0, PackCompileCache.missCount());
    }

    @Test
    void keyOfNullDiscoveredIsNull() {
        assertNull(PackCompileCache.keyOf(null, Map.of()));
    }
}
