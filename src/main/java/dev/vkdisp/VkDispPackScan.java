package dev.vkdisp;
/**
 * 【参考调研】P2.1/P2.2/P2.3 启动期扫包、选项枚举与阶段编译日志 / NeoForge 资源加载完成事件
 * 0. 合规核对（第 0 步闸门，不通过就换参考）：
 *    参考对象 = NeoForge 26.3.0.23-beta ClientResourceLoadFinishedEvent（官方事件，LGPL-2.1
 *    定义类 —— 只观察事件签名与触发时机，不复制其实现）+ 本仓库自研 B 线 ShaderPackScanner /
 *    ShaderPackService（不参考任何第三方扫包实现；Iris shaderpack/parsing 仅曾作为格式语义的
 *    只读调研对象，其代码未并入，格式本身属事实性信息，18-PARALLEL §4 A 线口径）。
 *    → 能否并入本项目（MIT）：可以 —— 只调用事件公开 API 与本方 pack/ 纯 Java 入口
 *    → 例外条款：无；不含任何 GPL / LGPL / ARR 代码
 * 1. 官方/主实现：ClientResourceLoadFinishedEvent —— 「客户端资源加载/重载成功之后」触发
 *    （javadoc 原文），首启在资源加载之后、初始界面建立之前（与 FullscreenPassHook 的门闩同一事件，
 *    触发点 ClientHooks#fireResourceLoadFinishedEvent）。库存目录取原版 gameDirectory（Minecraft
 *    的 public final File 字段，javap 核实）下的 shaderpacks/ —— 与原版资源包惯例同目录。
 *    扫包/解包/选项发现本体 = ShaderPackService.loadAll（冷路径，永不抛非受检异常）。
 * 2. 备选：FMLClientSetupEvent.enqueueWork —— 更早但早于资源体系就绪，语义弱，否决；
 *    每帧轮询 —— 无必要且刷屏，否决。二者均不构成自行补充特性，无需 GAP 登记（T12）。
 * 3. 我们的差异点：本类**只读库存、只打日志** —— 不注册虚拟资源包、不写 options.resourcePacks、
 *    不改用户包内任何文件（04-SPEC §3.1 只读不写；B 线「不许真的注册虚拟资源包」边界照旧）。
 *    显式可见性：包清单/选项/程序逐条 INFO（P2.1/P2.2 验收证据），扫描问题按 kind 分级
 *    （BROKEN_ZIP→ERROR，结构性问题→WARN，首次运行的 INVENTORY_MISSING / NO_PACKS_FOUND→INFO，
 *    全部显式打点，T11 不许「失败得像没发生过」）；诊断按 TranslateDiagnostic 原 severity 分流；
 *    整体 catch Throwable 打 ERROR 原文。总开关关闭走 WARN 降级分支（01-DEV-LOOP §5.1）。
 *    ④ P2.3（本轮）：扫包日志之后逐阶段跑 ShaderPackCompiler（include 展开 + OF 转译，冷路径）
 *    并把最终源经 bridge/ShaderCompileApi 交给原版 GlslCompiler 做**驱动级 GLSL→SPIR-V 编译** ——
 *    每阶段 OK/FAIL 日志 + 汇总计数（"含 #include 的 program 能编译通过" 的验收证据）；
 *    编译失败打原版错误原文（含 file:line，T11），转译失败的阶段不进驱动编译但同样显式报错。
 * 4. 许可证核对：本项目 MIT；只调用公开 API 与本方代码，无代码复制（07-CONSTRAINTS L5-L8）。
 * 5. 性能基线：启动/重载期一次性冷路径扫描（17-NATIVE §3.2），不做性能优化（T14 达标即停）。
 */

import dev.vkdisp.bridge.ShaderCompileApi;
import dev.vkdisp.glsl.LineDirectiveInjector;
import dev.vkdisp.glsl.TranslateDiagnostic;
import dev.vkdisp.glsl.translate.ShaderStage;
import dev.vkdisp.pack.Option;
import dev.vkdisp.pack.PackCompileCache;
import dev.vkdisp.pack.Program;
import dev.vkdisp.pack.ShaderPack;
import dev.vkdisp.pack.ShaderPackCompiler;
import dev.vkdisp.pack.ShaderPackCompiler;
import dev.vkdisp.pack.ShaderPackScanner;
import dev.vkdisp.pack.ShaderPackService;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P2.1/P2.2/P2.3 主线接入：客户端资源加载完成后扫描 {@code <gameDir>/shaderpacks/}，
 * 把包清单（zip / 目录两种形态）与选项枚举逐条打进日志，并把各 program 的最终源做驱动级编译。
 *
 * <p>验收口径（01-DEV-LOOP §10）：
 * <ul>
 *   <li><b>P2.1</b> —— {@code .zip} 与目录两种形态都被列出（日志 {@code kind=zip} / {@code kind=dir}）；</li>
 *   <li><b>P2.2</b> —— 选项被枚举出来，日志可见（每选项一行 name/type/default/values/slider/screen）；</li>
 *   <li><b>P2.3</b> —— 含 {@code #include} 的 program 能编译通过（日志：逐阶段
 *       {@code pack program compiled OK} + 汇总 {@code pack compile done}，失败打原文）。</li>
 * </ul>
 *
 * <p>本类只读库存、只打日志：不注册虚拟资源包、不写用户的 options/resourcePacks（T11 / 04-SPEC §3.1）。
 * 驱动级编译经 bridge/ShaderCompileApi（P2.3 本轮新增），SPIR-V 用完即关、不创建管线。
 */
@EventBusSubscriber(modid = VkDisp.MOD_ID, value = Dist.CLIENT)
public final class VkDispPackScan {

    /** 总开关关闭的降级分支只警告一次（01-DEV-LOOP §5.1 降级点）。 */
    private static boolean disabledWarned;

    private VkDispPackScan() {
    }

    /**
     * 资源加载/重载完成门闩：此刻文件系统与资源体系均已就绪，扫描库存不会与原版加载竞争。
     * 资源重载（F3+T）会再次触发 —— 重新扫描是期望行为（包变更可被发现），日志带 initial 标记。
     */
    @SubscribeEvent
    static void onClientResourceLoadFinished(ClientResourceLoadFinishedEvent event) {
        if (!VkDispConfig.ENABLED.get()) {
            if (!disabledWarned) {
                disabledWarned = true;
                VkDisp.LOGGER.warn(
                        "vkdisp: pack scan skipped (fallback branch: config vkdisp.enabled=false)");
            }
            return;
        }
        try {
            // 埋点：冷路径两段各自计时。§3.2 把 B3（冷路径墙钟）列为支柱③的硬指标，
            // 而此前启动路径**没有任何耗时打点** —— 离线基准测得到，客户端里测不到，
            // 两者无法对账。加这两行是为了让「客户端实测」成为可能。
            long scanStart = System.nanoTime();
            scanAndLog(event.isInitial());
            long scanMillis = (System.nanoTime() - scanStart) / 1_000_000L;
            long compileStart = System.nanoTime();
            compileAndLog();
            long compileMillis = (System.nanoTime() - compileStart) / 1_000_000L;
            VkDisp.LOGGER.info(
                    "vkdisp: cold path timing: scan={} ms compile={} ms total={} ms"
                            + " (initial={}, reusePreprocess={})",
                    scanMillis, compileMillis, scanMillis + compileMillis,
                    event.isInitial(), ShaderPackCompiler.REUSE_PREPROCESS);
        } catch (Throwable t) {
            // 失败必须打 ERROR 原文（07-CONSTRAINTS T11：不许吞异常让它看起来能跑）。
            VkDisp.LOGGER.error("vkdisp: pack scan failed", t);
        }
    }

    /** 库存目录（{@code <gameDir>/shaderpacks}）；gameDirectory 不可用返回 null（调用方显式报错）。 */
    private static Path inventoryDir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath().resolve("shaderpacks");
    }

    /** 扫描 + 逐条日志。{@link ShaderPackService#loadAll} 永不抛非受检异常，此处只兜真正的意外。 */
    private static void scanAndLog(boolean initial) {
        Path inventory = inventoryDir();
        if (inventory == null) {
            VkDisp.LOGGER.error(
                    "vkdisp: pack scan aborted — game directory unavailable (initial={})", initial);
            return;
        }
        ShaderPackService.InventoryResult result = ShaderPackService.loadAll(inventory);
        VkDisp.LOGGER.info("vkdisp: pack scan: inventory={} exists={} initial={}",
                inventory, Files.isDirectory(inventory), initial);

        int optionCount = 0;
        int programCount = 0;
        int index = 0;
        for (ShaderPack pack : result.packs()) {
            index++;
            optionCount += pack.options().size();
            programCount += pack.programs().size();
            // P2.1 证据行：kind=zip | kind=dir 两种形态都要能出现。
            VkDisp.LOGGER.info(
                    "vkdisp: pack[{}] name={} kind={} source={} programs={} options={} profiles={} dims={}",
                    index, pack.name(), pack.fromArchive() ? "zip" : "dir", pack.rootPath(),
                    pack.programs().size(), pack.options().size(),
                    pack.profiles().keySet(), pack.dimensionFolders());
            for (Program program : pack.programs()) {
                VkDisp.LOGGER.info(
                        "vkdisp: pack[{}] program name={} stage={} vsh={} fsh={}",
                        index, program.name(), program.stage(),
                        program.vertexShader(), program.fragmentShader());
            }
            // P2.2 证据行：选项逐条枚举（名称/类型/默认值/候选值/滑条/所属子屏）。
            for (Option option : pack.options()) {
                VkDisp.LOGGER.info(
                        "vkdisp: pack[{}] option name={} type={} default={} values={} slider={} screen={}",
                        index, option.name(), option.type(), option.defaultValue(),
                        option.values(), option.slider(),
                        option.screen().isEmpty() ? "(main)" : option.screen());
            }
        }

        for (ShaderPackScanner.PackProblem problem : result.scanProblems()) {
            logScanProblem(problem);
        }
        for (TranslateDiagnostic diagnostic : result.diagnostics()) {
            logDiagnostic(diagnostic);
        }

        VkDisp.LOGGER.info("vkdisp: pack scan done: packs={} programs={} options={} problems={} diagnostics={}",
                result.packs().size(), programCount, optionCount,
                result.scanProblems().size(), result.diagnostics().size());
    }

    /**
     * P2.3：逐包逐阶段编译 —— 冷路径（{@link ShaderPackCompiler}：include 展开 + OF 转译）→
     * 驱动级编译（bridge/ShaderCompileApi → 原版 GlslCompiler → SPIR-V）。
     *
     * <p>日志口径：每阶段一行 {@code pack program compiled OK/FAILED}（失败带原版错误原文，含
     * file:line），末行 {@code pack compile done} 汇总计数。「含 #include 的 program 能编译通过」
     * 的验收 = 汇总中该阶段 failed=0 且 OK 行可见。包级装载诊断不在此重复打（scanAndLog 已打过
     * 同一批 load 诊断），这里只打转译阶段自身的诊断与驱动编译结果。
     */
    private static void compileAndLog() {
        Path inventory = inventoryDir();
        if (inventory == null) {
            VkDisp.LOGGER.error("vkdisp: pack compile aborted — game directory unavailable");
            return;
        }
        ShaderPackScanner.ScanResult scan = ShaderPackScanner.scan(inventory);
        int stages = 0;
        int ok = 0;
        int failed = 0;
        for (ShaderPackScanner.DiscoveredPack discovered : scan.packs()) {
            // P4.5：走编译缓存。切包热路径（generateSources）刚编过同一个包时命中，
            // 省掉一次全量转译（182 阶段 ≈ 3 秒，2026-10-02 用户报「客户端未响应」根因之一）。
            // 取证侧无选项差分表，故仅当热路径的差分表也为空时同键命中——那正是
            // 「用户没改过任何选项」的常见情况；改过选项则各编一次（正确的隔离，不硬合并）。
            ShaderPackCompiler.CompileResult compiled =
                    PackCompileCache.getOrCompile(discovered, java.util.Map.of());
            if (compiled.pack() == null) {
                // 包模型没组装出来：load 级诊断已由 scanAndLog 打过（同一发现路径），这里显式标记编译被跳过。
                failed++;
                VkDisp.LOGGER.error("vkdisp: pack compile skipped (pack model null): pack={}",
                        discovered.name());
                continue;
            }
            String packName = compiled.pack().name();
            for (ShaderPackCompiler.CompiledStage stage : compiled.stages()) {
                stages++;
                // 转译期自身的诊断（D 线 INFO/WARN/ERROR）只在这里打一次 —— scanAndLog 打的是 load 期诊断。
                for (TranslateDiagnostic diagnostic : stage.result().diagnostics()) {
                    logDiagnostic(diagnostic);
                }
                if (!stage.isSuccess()) {
                    failed++;
                    VkDisp.LOGGER.error(
                            "vkdisp: pack program compile FAILED (translation): pack={} program={} stage={} file={}",
                            packName, stage.programName(), stage.stage(), stage.sourceFile());
                    continue;
                }
                // 诊断名与原版 Identifier.toString() 同构，会出现在 shaderc 错误原文里。
                String debugName = "vkdisp:pack/" + packName + "/" + stage.sourceFile();
                ShaderCompileApi.StageResult driver = ShaderCompileApi.compileStage(
                        debugName, stage.result().text(), stage.result().lineMap(),
                        stage.stage() == ShaderStage.VERTEX);
                if (driver.success()) {
                    ok++;
                    VkDisp.LOGGER.info(
                            "vkdisp: pack program compiled OK: pack={} program={} stage={} file={} spvBytes={}",
                            packName, stage.programName(), stage.stage(), stage.sourceFile(),
                            driver.spvBytes());
                } else {
                    failed++;
                    logCompileFailure(packName, stage, driver);
                }
            }
        }
        VkDisp.LOGGER.info("vkdisp: pack compile done: stages={} ok={} failed={}", stages, ok, failed);
    }

    /** A3 因果归属：转译产物不可编译 = 本项目 ERROR（§2.2-c），不是「包的问题」。 */
    private static void logCompileFailure(String packName,
            ShaderPackCompiler.CompiledStage stage, ShaderCompileApi.StageResult driver) {
        LineDirectiveInjector.Attribution attribution =
                LineDirectiveInjector.attributeError(driver.error(), stage.result().lineMap());
        VkDisp.LOGGER.error(
                "vkdisp: pack program compile FAILED: pack={} program={} stage={} file={}: {} | {}",
                packName, stage.programName(), stage.stage(), stage.sourceFile(),
                driver.error(), attribution.summary());
    }

    /**
     * 扫描问题分级（T11：每条都显式打点，分级只影响严重度，不吞任何一条）：
     * BROKEN_ZIP = 输入数据损坏 → ERROR；结构性问题（缺 shaders/、外层多套目录、非包条目等）→ WARN；
     * 库存目录不存在 / 目录里没有包 = 首次运行的预期状态 → INFO（仍带 hint，不静默）。
     */
    private static void logScanProblem(ShaderPackScanner.PackProblem problem) {
        String line = "vkdisp: pack scan problem kind={} entry={}: {}";
        switch (problem.kind()) {
            case BROKEN_ZIP -> VkDisp.LOGGER.error(line,
                    problem.kind(), problem.entry(), problem.message());
            case INVENTORY_MISSING, NO_PACKS_FOUND -> VkDisp.LOGGER.info(line + " (create shaderpacks/ and place packs there)",
                    problem.kind(), problem.entry(), problem.message());
            default -> VkDisp.LOGGER.warn(line, problem.kind(), problem.entry(), problem.message());
        }
    }

    /** 诊断按原 severity 分流，格式沿用 {@link TranslateDiagnostic#format()}（含 file:line:col）。 */
    private static void logDiagnostic(TranslateDiagnostic diagnostic) {
        switch (diagnostic.severity()) {
            case ERROR -> VkDisp.LOGGER.error("vkdisp: pack diagnostic: {}", diagnostic.format());
            case WARN -> VkDisp.LOGGER.warn("vkdisp: pack diagnostic: {}", diagnostic.format());
            case INFO -> VkDisp.LOGGER.info("vkdisp: pack diagnostic: {}", diagnostic.format());
        }
    }
}
