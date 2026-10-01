package dev.vkdisp.config;

import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.ShaderPack;
import dev.vkdisp.pack.ShaderPackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackOptionsSession}：三层快照（默认 → profile → store）、触碰差分、提交语义、分页纯函数。
 * 全部用自造包（§7.6），无 MC 依赖。
 */
class PackOptionsSessionTest {

    @TempDir
    Path inventory;

    /** 自造包：两个数值选项 + 一个布尔选项 + 一个 profile。 */
    @BeforeEach
    void fixturePack() throws IOException {
        Path pack = inventory.resolve("fixture");
        Files.createDirectories(pack.resolve("shaders"));
        Files.writeString(pack.resolve("shaders", "composite.fsh"), String.join("\n",
                "#version 150",
                "#define SHADOW_DARKNESS 0.10 // [0.05 0.10 0.20]",
                "#define LIGHT_BOOST 1.0 // [1.0 1.5 2.0]",
                "#define ENABLE_FOG true // [true false]",
                "void main() {}"), StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("shaders", "shaders.properties"),
                "profile.HIGH=SHADOW_DARKNESS=0.20\n", StandardCharsets.UTF_8);
    }

    private ShaderPack loadPack() {
        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        assertEquals(1, result.packs().size(), () -> "期望恰好 1 个包: " + result.diagnostics());
        return result.packs().get(0);
    }

    @Test
    void baselineIsDefaultsWithoutProfileOrStore() {
        ShaderPack pack = loadPack();
        PackOptionsSession session = PackOptionsSession.create(pack, "", PackOptionStore.empty());

        assertEquals("0.10", session.baseline().get("SHADOW_DARKNESS"));
        assertEquals(0, session.changedCount(), "刚开屏没有任何触碰项");
        assertEquals("", session.profileName());
        assertEquals("fixture", session.packName());
    }

    @Test
    void profileShiftsBaselineSoProfileValuesAreNotPersisted() {
        ShaderPack pack = loadPack();
        PackOptionsSession session = PackOptionsSession.create(pack, "HIGH", PackOptionStore.empty());

        assertEquals("0.20", session.baseline().get("SHADOW_DARKNESS"),
                "基线 = 默认+profile（profile 改动不算用户触碰）");
        assertEquals(0, session.changedCount(), "只开屏不改 → profile 值不进触碰集");

        PackOptionsSession.CommitResult commit = session.commit(PackOptionStore.empty());
        assertEquals(0, commit.stored(), "profile 值绝不该被固化进存储（否则之后改 profile 永不生效）");
    }

    @Test
    void storeWinsOverProfileAndCountsAsTouched() {
        PackOptionStore store = PackOptionStore.empty();
        store.put("fixture", "SHADOW_DARKNESS", "0.05");
        ShaderPack pack = loadPack();

        PackOptionsSession session = PackOptionsSession.create(pack, "HIGH", store);

        assertEquals("0.05", session.options().value("SHADOW_DARKNESS"),
                "GUI/存储必须后于 profile 生效（用户改动优先）");
        assertEquals("0.20", session.baseline().get("SHADOW_DARKNESS"));
        assertEquals(1, session.changedCount(), "store 值 ≠ profile 基线 = 触碰项");
        assertEquals(List.of("SHADOW_DARKNESS=0.05"), session.changedEntries());
    }

    @Test
    void commitWritesOnlyTouchedAndRemovesReverted() {
        ShaderPack pack = loadPack();
        PackOptionStore store = PackOptionStore.empty();
        PackOptionsSession session = PackOptionsSession.create(pack, "", store);

        session.set("SHADOW_DARKNESS", "0.20");
        session.set("LIGHT_BOOST", "1.5");
        PackOptionsSession.CommitResult commit = session.commit(store);

        assertEquals(2, commit.stored());
        assertEquals(List.of("SHADOW_DARKNESS=0.20", "LIGHT_BOOST=1.5"), commit.changedEntries());
        assertEquals("0.20", store.get("fixture", "SHADOW_DARKNESS").orElse(null));
        assertFalse(store.contains("fixture", "ENABLE_FOG"), "没动过的选项不许写进存储");

        // 改回基线 → 提交应删除旧条目（配置回到"无覆盖"语义）；
        // LIGHT_BOOST 仍触碰着 → 本轮仍计一次 put（幂等重写，不丢也不多写别的）。
        session.set("SHADOW_DARKNESS", "0.10");
        PackOptionsSession.CommitResult second = session.commit(store);
        assertEquals(1, second.stored(), "只有仍触碰着的 LIGHT_BOOST 计入本轮写入");
        assertEquals(List.of("LIGHT_BOOST=1.5"), second.changedEntries());
        assertEquals(1, second.removed());
        assertFalse(store.contains("fixture", "SHADOW_DARKNESS"), "改回基线的条目必须被清掉");
        assertTrue(store.contains("fixture", "LIGHT_BOOST"), "没改回去的条目必须保留");
    }

    @Test
    void unknownStoreOptionIsKeptAndDiagnosed() {
        PackOptionStore store = PackOptionStore.empty();
        store.put("fixture", "GONE_OPTION", "1");
        ShaderPack pack = loadPack();

        PackOptionsSession session = PackOptionsSession.create(pack, "", store);

        assertTrue(session.buildDiagnostics().stream()
                        .anyMatch(d -> "STORE_UNKNOWN_OPTION".equals(d.code())),
                "存储里的未知选项必须显式 WARN（T11），实际: " + session.buildDiagnostics());
        session.commit(store);
        assertTrue(store.contains("fixture", "GONE_OPTION"),
                "提交不许顺手删掉未知条目（那是用户数据，只能经「重置本包」清除）");
    }

    @Test
    void booleanStoreValueRoundTripsThroughSession() {
        PackOptionStore store = PackOptionStore.empty();
        store.put("fixture", "ENABLE_FOG", "false");
        ShaderPack pack = loadPack();

        PackOptionsSession session = PackOptionsSession.create(pack, "", store);

        assertEquals("false", session.options().value("ENABLE_FOG"));
        assertEquals(1, session.changedCount());
        PackOptionsSession.CommitResult commit = session.commit(store);
        assertEquals(List.of("ENABLE_FOG=false"), commit.changedEntries());
    }

    @Test
    void pagingSlicesInDeclarationOrderWithClampedBounds() {
        ShaderPack pack = loadPack(); // 3 个选项
        PackOptionsSession session = PackOptionsSession.create(pack, "", null);

        assertEquals(3, session.definitions().size());
        assertEquals(2, session.pageCount(2), "3 项 / 每页 2 = 2 页");
        assertEquals(3, session.pageCount(0), "非法页大小按每页 1 项处理 → 3 项 3 页");

        List<Option> first = session.pageOptions(0, 2);
        assertEquals(2, first.size());
        assertEquals("SHADOW_DARKNESS", first.get(0).name(), "分页必须保声明序");
        List<Option> second = session.pageOptions(1, 2);
        assertEquals(1, second.size(), "3 项 / 每页 2 → 第 2 页只剩尾项");
        assertEquals("ENABLE_FOG", second.get(0).name());

        assertEquals(1, session.page(99, 2), "越界页号钳到最后一页");
        assertEquals(0, session.page(-5, 2), "负页号钳到第一页");
        assertTrue(session.pageOptions(9, 2).size() <= 2,
                "越界页钳后仍返回合法切片");
    }
}
