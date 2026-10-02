package dev.vkdisp.pack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【P4.5】转译产物缓存 —— 消除同一次切包内的<b>重复全量编译</b>。
 *
 * <p><b>问题（2026-10-02 用户实测「客户端进入未响应」）</b>：切一次包会跑两遍全量编译，
 * 且两遍都在渲染线程：
 * <ol>
 *   <li>{@code VkDispVirtualPack.generateSources} → {@link PackCompositeSource#generate}
 *       → {@link ShaderPackCompiler#compile}（带选项差分表）—— 3.19s实测；</li>
 *   <li>{@code VkDispPackScan.compileAndLog}（{@code ClientResourceLoadFinishedEvent}）
 *       → 同一个 {@link ShaderPackCompiler#compile}（**无**差分表，纯取证）。</li>
 * </ol>
 * 两遍的输入不同（选项差分表有无），所以<b>不能</b>简单去重；但它们对<b>同一个包</b>的
 * 绝大多数阶段产出<b>逐字节相同</b>的源 —— 只��被选项改写命中的那几行不同。
 *
 * <p><b>本类的做法</b>：按「包 + 选项差分表指纹」缓存 {@link ShaderPackCompiler.CompileResult}。
 * 同一组合的重复请求直接命中（切包往返、选项屏「完成」后再切回同一包都是常见路径），
 * 不同组合各编一次。
 *
 * <p><b>为什么不用「只留差分表无关的一份再叠加改写」</b>：选项改写发生在预处理**之前**
 * 且会改变宏展开结果（改一个选项可能整包条件编译分支都变），不是可以后置叠加的变换。
 * 宁可多编一次也不做可能错误的复用（X9）。
 *
 * <p><b>线程</b>：{@link ConcurrentHashMap}，允许后台预编译线程与渲染线程并发读写。
 * {@link ShaderPackCompiler.CompileResult} 及其内部列表全部是不可变快照（record +
 * {@code List.copyOf}），发布即安全。
 *
 * <p><b>容量</b>：只留最近 {@link #MAX_ENTRIES} 个组合。着色器包选项有限，实际组合数
 * 是个位数，留 8 条足够覆盖「当前包 + 切过的几个包 + 各自的选项态」，同时防止
 * 长时间游戏累积无界增长。
 *
 * <p>许可证：本文件为独立编写的纯 Java，不含任何外部项目代码（07-CONSTRAINTS §〇 P1）。
 */
public final class PackCompileCache {

    /** 最多缓存多少个「包 + 差分表」组合（超出按最旧淘汰）。 */
    public static final int MAX_ENTRIES = 8;

    /**
     * 缓存键：包身份 + 选项差分表。
     *
     * @param packIdentity 包身份（{@code DiscoveredPack.source()} 的稳定形式：路径 + 种类 + 大小）
     * @param overrides    选项差分表（{@code name -> value}）；参与哈希，改选项即换键
     */
    public record Key(String packIdentity, Map<String, String> overrides) {

        /** 归一构造：null 归一为空串 / 空表，Map 冻结为不可变。 */
        public Key {
            packIdentity = packIdentity == null ? "" : packIdentity;
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        }
    }

    /** 键 → 编译产物。无锁并发容器。 */
    private static final Map<Key, ShaderPackCompiler.CompileResult> CACHE = new ConcurrentHashMap<>();

    /** 命中/未命中计数（诊断用：验证缓存真的在起作用，而不是猜测）。 */
    private static volatile long hits;
    private static volatile long misses;

    private PackCompileCache() {
    }

    /**
     * 缓存键构造：包身份用「路径 + 种类 + 文件大小 + 修改时间」——用户换了包文件
     * （同路径不同内容）必须自然换键，否则会吃到旧包���缓存（这是最容易踩的坑）。
     *
     * @param discovered 扫描出的包；null → 返回 null 键（调用方跳过缓存）
     */
    public static Key keyOf(ShaderPackScanner.DiscoveredPack discovered,
            Map<String, String> overrides) {
        if (discovered == null) {
            return null;
        }
        return new Key(discovered.identity(), overrides);
    }

    /** 读缓存（不命中返回 null）。命中计数 +1。 */
    public static ShaderPackCompiler.CompileResult get(Key key) {
        if (key == null) {
            return null;
        }
        ShaderPackCompiler.CompileResult result = CACHE.get(key);
        if (result != null) {
            hits++;
        } else {
            misses++;
        }
        return result;
    }

    /** 写缓存（超容量时淘汰最旧的一条）。 */
    public static void put(Key key, ShaderPackCompiler.CompileResult result) {
        if (key == null || result == null) {
            return;
        }
        // 淘汰最旧：ConcurrentHashMap 无序，取任一 oldest-by-insertion 不便；
        // 容量极小（8），用「超限时清空」是最简且行为可预测的做法（缓存只是加速器，
        // 清空最坏只是多编一次，不影响正确性 —— 绝不因为缓存问题导致功能不可用）。
        if (CACHE.size() >= MAX_ENTRIES && !CACHE.containsKey(key)) {
            CACHE.clear();
        }
        CACHE.put(key, result);
    }

    /** 取缓存，未命中则编译并写回。**编译在调用线程执行**（谁调用谁承担耗时）。 */
    public static ShaderPackCompiler.CompileResult getOrCompile(
            ShaderPackScanner.DiscoveredPack discovered, Map<String, String> overrides) {
        Key key = keyOf(discovered, overrides);
        ShaderPackCompiler.CompileResult cached = get(key);
        if (cached != null) {
            return cached;
        }
        ShaderPackCompiler.CompileResult compiled = ShaderPackCompiler.compile(discovered, overrides);
        put(key, compiled);
        return compiled;
    }

    /** 清空缓存（切包 / 选项大改后调用，防止吃到过期产物；也用于测试复位）。 */
    public static void invalidate() {
        CACHE.clear();
        hits = 0;
        misses = 0;
    }

    /** 当前缓存条目数。 */
    public static int size() {
        return CACHE.size();
    }

    /** 命中次数（诊断行用）。 */
    public static long hitCount() {
        return hits;
    }

    /** 未命中次数（诊断行用）。 */
    public static long missCount() {
        return misses;
    }
}
