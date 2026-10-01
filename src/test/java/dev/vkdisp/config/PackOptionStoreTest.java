package dev.vkdisp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackOptionStore} 的格式契约：键拆分（最后一个点）、读写往返、坏行降级（T11）、
 * 按包清理。全部用自造文件（§7.6），不依赖任何第三方包。
 */
class PackOptionStoreTest {

    @TempDir
    Path dir;

    @Test
    void pathForResolvesUnderGameConfigDir() {
        Path gameDir = Path.of("run");
        assertEquals(Path.of("run", "config", "vkdisp-pack-options.properties"),
                PackOptionStore.pathFor(gameDir));
        assertNull(PackOptionStore.pathFor(null), "gameDir 缺失时应返回 null 而不是 NPE");
    }

    @Test
    void saveThenLoadRoundTripsEntriesInOrder() throws IOException {
        PackOptionStore store = PackOptionStore.empty();
        store.put("BSL_v10.1.8", "SHARPEN", "4");
        store.put("vkdisp-fixture-dir", "SHADOW_DARKNESS", "0.20");
        store.put("BSL_v10.1.8", "CHROMATIC_ABERRATION", "4");

        Path file = dir.resolve("config").resolve(PackOptionStore.FILE_NAME);
        store.save(file);
        assertTrue(Files.isRegularFile(file), "save 应创建父目录并落文件");

        PackOptionStore loaded = PackOptionStore.load(file);
        assertTrue(loaded.loadWarnings().isEmpty(), () -> "自写文件不应产生坏行警告: " + loaded.loadWarnings());
        assertEquals("4", loaded.get("BSL_v10.1.8", "SHARPEN").orElse(null));
        assertEquals("0.20", loaded.get("vkdisp-fixture-dir", "SHADOW_DARKNESS").orElse(null));
        assertEquals(Map.of("SHARPEN", "4", "CHROMATIC_ABERRATION", "4"),
                loaded.forPack("BSL_v10.1.8"));
        assertEquals(3, loaded.size());
    }

    @Test
    void missingFileLoadsAsEmptyStore() {
        PackOptionStore store = PackOptionStore.load(dir.resolve("nope.properties"));
        assertTrue(store.isEmpty());
        assertTrue(store.loadWarnings().isEmpty(), "文件不存在是常态（首次运行），不算警告");
        assertTrue(PackOptionStore.load(null).isEmpty(), "null 路径同样空存储");
    }

    @Test
    void dottedPackNameSplitsAtLastDot() {
        PackOptionStore store = PackOptionStore.empty();
        store.put("BSL_v10.1.8", "SHARPEN", "4");

        assertEquals(Map.of("SHARPEN", "4"), store.forPack("BSL_v10.1.8"),
                "包名含点时必须整段归属（按最后一个点拆）");
        assertTrue(store.forPack("BSL_v10").isEmpty(), "前缀包名不许误吸别人的条目");
        assertEquals("4", store.get("BSL_v10.1.8", "SHARPEN").orElse(null));
        assertTrue(store.packNames().contains("BSL_v10.1.8"));
    }

    @Test
    void clearPackOnlyRemovesThatPacksEntries() {
        PackOptionStore store = PackOptionStore.empty();
        store.put("a", "X", "1");
        store.put("a", "Y", "2");
        store.put("b", "X", "3");

        assertEquals(2, store.clearPack("a"));
        assertTrue(store.forPack("a").isEmpty());
        assertEquals(Map.of("X", "3"), store.forPack("b"));
        assertEquals(1, store.size());
        assertEquals(0, store.clearPack("a"), "重复清理返回 0");
    }

    @Test
    void malformedLinesAreSkippedWithWarnings() throws IOException {
        Path file = dir.resolve("bad.properties");
        Files.writeString(file, String.join("\n",
                "# 注释行",
                "no-equals-sign",
                ".missingPack=x",
                "good.pack.OPT=1"), StandardCharsets.UTF_8);

        PackOptionStore store = PackOptionStore.load(file);
        assertEquals(1, store.size(), "合法行照常进存储");
        assertEquals("1", store.get("good.pack", "OPT").orElse(null));
        assertEquals(2, store.loadWarnings().size(), () -> "坏行必须逐条留痕（T11）: " + store.loadWarnings());
        assertTrue(store.loadWarnings().get(0).contains("no-equals-sign")
                        || store.loadWarnings().get(1).contains("no-equals-sign"),
                "缺 = 的行要在警告文本里可复核");
    }

    @Test
    void valuesWithEqualsAndBackslashRoundTrip() throws IOException {
        PackOptionStore store = PackOptionStore.empty();
        store.put("p", "FREE_TEXT", "a=b\\c");

        Path file = dir.resolve("rt.properties");
        store.save(file);

        PackOptionStore loaded = PackOptionStore.load(file);
        assertEquals("a=b\\c", loaded.get("p", "FREE_TEXT").orElse(null),
                "值里的 = 不许被当成第二个分隔符，反斜杠必须往返守恒");
        assertFalse(loaded.isEmpty());
    }
}
